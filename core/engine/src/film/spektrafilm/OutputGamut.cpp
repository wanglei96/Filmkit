#include "OutputGamut.h"
#include "SpatialFilter.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <limits>
#include <numbers>
#include <thread>

namespace filmkit::spektrafilm {
namespace {

#include "Cam16SrgbCmax.inc"

constexpr PerceptualGamutTable kSrgbCam16Gamut{
    kCam16SrgbLightness, kCam16SrgbHue, kCam16SrgbCmax};

constexpr double kSrgbToXyz[3][3] = {
    {0.4124, 0.3576, 0.1805}, {0.2126, 0.7152, 0.0722}, {0.0193, 0.1192, 0.9505},
};
constexpr double kXyzToSrgb[3][3] = {
    {3.2406, -1.5372, -0.4986}, {-0.9689, 1.8758, 0.0415}, {0.0557, -0.2040, 1.0570},
};

constexpr double kCat16[3][3] = {
    {0.401288, 0.650173, -0.051461},
    {-0.250268, 1.204414, 0.045854},
    {-0.002079, 0.048952, 0.953127},
};
constexpr double kCat16Inverse[3][3] = {
    {1.8620678550872327, -1.0112546305316843, 0.14918677544445175},
    {0.3875265432361372, 0.6214474419314753, -0.00897398516761252},
    {-0.01584149884933386, -0.03412293802851557, 1.0499644368778496},
};
constexpr Triplet kWhite{95.04559270516715, 100.0, 108.90577507598785};
constexpr double kLuminanceAdaptation = 64.0;
constexpr double kBackground = 20.0;
constexpr double kSurroundC = 0.69;
constexpr double kSurroundF = 1.0;
constexpr double kSurroundNc = 1.0;
constexpr double kUcsC1 = 0.007;
constexpr double kUcsC2 = 0.0228;

Triplet multiply(const double matrix[3][3], Triplet value) {
    Triplet result{};
    for (std::size_t row = 0; row < 3; ++row) {
        for (std::size_t col = 0; col < 3; ++col) {
            result[row] += matrix[row][col] * value[col];
        }
    }
    return result;
}

double signedPower(double value, double exponent) {
    return std::copysign(std::pow(std::abs(value), exponent), value);
}

double response(double value, double fL) {
    const double x = std::pow(fL * std::abs(value) / 100.0, 0.42);
    return 400.0 * std::copysign(1.0, value) * x / (27.13 + x) + 0.1;
}

double inverseResponse(double value, double fL) {
    const double x = std::abs(value - 0.1);
    return std::copysign(100.0 / fL, value - 0.1) *
           signedPower(27.13 * x / (400.0 - x), 1.0 / 0.42);
}

double achromatic(Triplet rgb, double nbb) {
    return (2.0 * rgb[0] + rgb[1] + 0.05 * rgb[2] - 0.305) * nbb;
}

bool finite(Triplet value) {
    return std::isfinite(value[0]) && std::isfinite(value[1]) &&
           std::isfinite(value[2]);
}

struct ViewingState {
    Triplet adaptation;
    double fL;
    double n;
    double nbb;
    double z;
    double aw;
};

ViewingState prepareViewingState() {
    const double k = 1.0 / (5.0 * kLuminanceAdaptation + 1.0);
    const double k4 = k * k * k * k;
    const double fL = 0.2 * k4 * 5.0 * kLuminanceAdaptation +
                      0.1 * (1.0 - k4) * (1.0 - k4) *
                          std::cbrt(5.0 * kLuminanceAdaptation);
    const double degree = std::clamp(
        kSurroundF * (1.0 - std::exp((-kLuminanceAdaptation - 42.0) / 92.0) / 3.6),
        0.0, 1.0);
    const Triplet coneWhite = multiply(kCat16, kWhite);
    Triplet adaptation{};
    for (std::size_t i = 0; i < 3; ++i) {
        adaptation[i] = degree * 100.0 / coneWhite[i] + 1.0 - degree;
    }
    Triplet adaptedWhite{};
    for (std::size_t i = 0; i < 3; ++i) {
        adaptedWhite[i] = response(adaptation[i] * coneWhite[i], fL);
    }
    const double n = kBackground / 100.0;
    const double nbb = 0.725 * std::pow(1.0 / n, 0.2);
    const double z = 1.48 + std::sqrt(n);
    return {adaptation, fL, n, nbb, z,
            achromatic(adaptedWhite, nbb)};
}

const ViewingState kViewing = prepareViewingState();

bool valid(const PerceptualGamutTable& table) {
    return table.lightness.size() >= 2 && table.hue.size() >= 2 &&
           table.maximumChroma.size() ==
               table.lightness.size() * table.hue.size();
}

double lookupPreparedGamut(const PerceptualGamutTable& table, double lightness,
                           double hueRadians) {
    const auto& l = table.lightness;
    const auto& h = table.hue;
    const double huePosition = (hueRadians - h.front()) / (h[1] - h[0]);
    const double hueFloor = std::floor(huePosition);
    const auto bins = static_cast<std::ptrdiff_t>(h.size());
    const auto hueBin = static_cast<std::ptrdiff_t>(hueFloor) % bins;
    const auto h0 = static_cast<std::size_t>(hueBin < 0 ? hueBin + bins : hueBin);
    const std::size_t h1 = (h0 + 1) % h.size();
    const double hf = huePosition - hueFloor;

    const double clampedL = std::clamp(lightness, l.front(), l.back());
    const auto upper = std::upper_bound(l.begin(), l.end(), clampedL);
    const std::size_t l0 = std::min<std::size_t>(
        std::distance(l.begin(), upper) - 1, l.size() - 2);
    const std::size_t l1 = l0 + 1;
    const double lf = (clampedL - l[l0]) / (l[l1] - l[l0]);
    const auto at = [&](std::size_t li, std::size_t hi) {
        return table.maximumChroma[li * h.size() + hi];
    };
    const double low = at(l0, h0) * (1.0 - hf) + at(l0, h1) * hf;
    const double high = at(l1, h0) * (1.0 - hf) + at(l1, h1) * hf;
    return low * (1.0 - lf) + high * lf;
}

}  // namespace

bool lookupMaximumChroma(const PerceptualGamutTable& table, double lightness,
                         double hueRadians, double& result) noexcept {
    if (!valid(table) || !std::isfinite(lightness) || !std::isfinite(hueRadians)) return false;
    const auto l = table.lightness;
    const auto h = table.hue;
    for (std::size_t i = 1; i < l.size(); ++i) {
        if (!(l[i - 1] < l[i]) || !std::isfinite(l[i] - l[i - 1])) return false;
    }
    for (std::size_t i = 1; i < h.size(); ++i) {
        if (!(h[i - 1] < h[i]) || !std::isfinite(h[i] - h[i - 1])) return false;
    }
    const double period = (h[1] - h[0]) * h.size();
    const double wrapped = std::fmod(hueRadians - h.front(), period);
    if (!std::isfinite(wrapped)) return false;
    result = lookupPreparedGamut(table, lightness, h.front() + wrapped);
    return std::isfinite(result);
}

double reinhardKnee(double distance, double threshold, double limit,
                    double power) noexcept {
    if (!(power > 0.0) || !(limit > threshold) ||
        !std::isfinite(distance)) {
        return distance;
    }
    if (distance <= threshold) return distance;
    const double scale = limit - threshold;
    const double x = (distance - threshold) / scale;
    return threshold +
           scale * x / std::pow(1.0 + std::pow(x, power), 1.0 / power);
}

bool xyzToCam16Ucs(Triplet xyz, Triplet& jab) noexcept {
    if (!finite(xyz)) return false;
    const auto& state = kViewing;
    for (double& channel : xyz) channel *= 100.0;
    const Triplet cone = multiply(kCat16, xyz);
    Triplet adapted{};
    for (std::size_t i = 0; i < 3; ++i) {
        adapted[i] = response(state.adaptation[i] * cone[i], state.fL);
    }
    const double a = adapted[0] - 12.0 * adapted[1] / 11.0 + adapted[2] / 11.0;
    const double b = (adapted[0] + adapted[1] - 2.0 * adapted[2]) / 9.0;
    double hue = std::atan2(b, a);
    if (hue < 0.0) hue += 2.0 * std::numbers::pi;
    const double eccentricity = 0.25 * (std::cos(hue + 2.0) + 3.8);
    const double A = achromatic(adapted, state.nbb);
    const double J = 100.0 * signedPower(A / state.aw, kSurroundC * state.z);
    const double denominator = adapted[0] + adapted[1] + 21.0 * adapted[2] / 20.0;
    const double t = denominator == 0.0
                         ? 0.0
                         : (50000.0 / 13.0) * kSurroundNc * state.nbb * eccentricity *
                               std::hypot(a, b) / denominator;
    const double C = signedPower(t, 0.9) * signedPower(J / 100.0, 0.5) *
                     std::pow(1.64 - std::pow(0.29, state.n), 0.73);
    const double M = C * std::pow(state.fL, 0.25);
    const double Jp = (1.0 + 100.0 * kUcsC1) * J / (1.0 + kUcsC1 * J);
    const double Mp = std::log1p(kUcsC2 * M) / kUcsC2;
    jab = {Jp, Mp * std::cos(hue), Mp * std::sin(hue)};
    return finite(jab);
}

bool cam16UcsToXyz(Triplet jab, Triplet& xyz) noexcept {
    if (!finite(jab)) return false;
    const auto& state = kViewing;
    const double Jp = jab[0];
    const double J = -Jp / (kUcsC1 * Jp - 1.0 - 100.0 * kUcsC1);
    const double ap = jab[1];
    const double bp = jab[2];
    const double Mp = std::hypot(ap, bp);
    const double M = std::expm1(kUcsC2 * Mp) / kUcsC2;
    double hue = std::atan2(bp, ap);
    if (hue < 0.0) hue += 2.0 * std::numbers::pi;
    const double C = M / std::pow(state.fL, 0.25);
    const double jSafe = std::max(J, std::numeric_limits<double>::epsilon());
    const double t = std::pow(C / (std::sqrt(jSafe / 100.0) *
                                   std::pow(1.64 - std::pow(0.29, state.n), 0.73)),
                              1.0 / 0.9);
    const double A = state.aw * signedPower(J / 100.0, 1.0 / (kSurroundC * state.z));
    const double p2 = A / state.nbb + 0.305;
    constexpr double p3 = 21.0 / 20.0;
    const double sinH = std::sin(hue);
    const double cosH = std::cos(hue);
    const double n = p2 * (2.0 + p3) * (460.0 / 1403.0);
    double a = 0.0, b = 0.0;
    const double p1 = t == 0.0 ? 0.0 : (50000.0 / 13.0) * kSurroundNc * state.nbb *
                      (0.25 * (std::cos(hue + 2.0) + 3.8)) / t;
    if (t != 0.0 && std::abs(sinH) >= std::abs(cosH)) {
        const double p4 = p1 / sinH;
        b = n / (p4 + (2.0 + p3) * (220.0 / 1403.0) * cosH / sinH -
                 27.0 / 1403.0 + p3 * (6300.0 / 1403.0));
        a = b * cosH / sinH;
    } else if (t != 0.0) {
        const double p5 = p1 / cosH;
        a = n / (p5 + (2.0 + p3) * (220.0 / 1403.0) -
                 (27.0 / 1403.0 - p3 * (6300.0 / 1403.0)) * sinH / cosH);
        b = a * sinH / cosH;
    }
    const Triplet adapted{
        (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0,
        (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0,
        (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0,
    };
    Triplet cone{};
    for (std::size_t i = 0; i < 3; ++i) {
        cone[i] = inverseResponse(adapted[i], state.fL) / state.adaptation[i];
    }
    xyz = multiply(kCat16Inverse, cone);
    for (double& channel : xyz) channel /= 100.0;
    return finite(xyz);
}

bool compressSrgbCam16Ucs(Triplet linearRgb, Triplet& compressedRgb) noexcept {
    if (!finite(linearRgb)) return false;
    Triplet jab{};
    if (!xyzToCam16Ucs(multiply(kSrgbToXyz, linearRgb), jab)) return false;

    const double Jp = reinhardKnee(jab[0] / 100.0, 0.7, 1.0, 2.2) * 100.0;
    const double Cp = std::hypot(jab[1], jab[2]);
    const double hp = std::atan2(jab[2], jab[1]);
    const double CpMax = lookupPreparedGamut(kSrgbCam16Gamut, Jp, hp);
    const double safeMax = std::max(CpMax, 1e-9);
    const double normalized = Cp / safeMax;
    const double compressedChroma = reinhardKnee(normalized, 0.0, 1.0, 6.0) * safeMax;
    const Triplet mappedJab{Jp, compressedChroma * std::cos(hp),
                           compressedChroma * std::sin(hp)};
    Triplet xyz{};
    if (!cam16UcsToXyz(mappedJab, xyz)) return false;
    compressedRgb = multiply(kXyzToSrgb, xyz);
    return finite(compressedRgb);
}

bool finishScanSrgb(std::span<Triplet> xyz, std::size_t width,
                    std::size_t height, Triplet scannerWhiteXyz,
                    double lensBlurSigma, double unsharpSigma,
                    double unsharpAmount, bool encodeSrgb,
                    std::span<double> plane,
                    std::span<double> filtered,
                    std::span<double> scratch,
                    std::size_t workerCount, std::size_t filterWorkers) {
    if (width == 0 || height == 0 || width > std::numeric_limits<std::size_t>::max() / height ||
        xyz.size() != width * height || plane.size() != xyz.size() ||
        filtered.size() != xyz.size() || scratch.size() != xyz.size() ||
        !finite(scannerWhiteXyz) || scannerWhiteXyz[1] <= 0.0 ||
        !std::isfinite(lensBlurSigma) || lensBlurSigma < 0.0 ||
        !std::isfinite(unsharpSigma) || unsharpSigma < 0.0 ||
        !std::isfinite(unsharpAmount) || unsharpAmount < 0.0) {
        return false;
    }
    constexpr double cat02[3][3] = {
        {0.7328, 0.4296, -0.1624},
        {-0.7036, 1.6975, 0.0061},
        {0.0030, 0.0136, 0.9834},
    };
    constexpr double cat02Inverse[3][3] = {
        {1.096123820835514, -0.27886900021828726, 0.18274517938277304},
        {0.4543690419753592, 0.4735331543074118, 0.07209780371722913},
        {-0.00962760873842936, -0.00569803121611342, 1.0153256399545427},
    };
    constexpr Triplet d65{0.9504559270516716, 1.0, 1.0890577507598784};
    const double whiteScale = scannerWhiteXyz[1];
    for (double& value : scannerWhiteXyz) value /= whiteScale;
    const Triplet sourceCone = multiply(cat02, scannerWhiteXyz);
    const Triplet targetCone = multiply(cat02, d65);
    for (double component : sourceCone) {
        if (!std::isfinite(component) || component <= 0.0) return false;
    }
    Triplet adaptation{};
    for (std::size_t c = 0; c < 3; ++c) adaptation[c] = targetCone[c] / sourceCone[c];

    if (workerCount == 0) return false;
    workerCount = std::min<std::size_t>({workerCount, 8, xyz.size()});
    std::atomic<bool> validPixels{true};
    const auto mapRange = [&](std::size_t begin, std::size_t end) {
        for (std::size_t i = begin; i < end; ++i) {
            Triplet& value = xyz[i];
            if (!finite(value)) {
                validPixels.store(false, std::memory_order_relaxed);
                continue;
            }
            Triplet cone = multiply(cat02, value);
            for (std::size_t c = 0; c < 3; ++c) cone[c] *= adaptation[c];
            Triplet linearRgb = multiply(kXyzToSrgb, multiply(cat02Inverse, cone));
            Triplet compressed{};
            if (!compressSrgbCam16Ucs(linearRgb, compressed)) {
                validPixels.store(false, std::memory_order_relaxed);
                continue;
            }
            value = compressed;
        }
    };
    if (workerCount == 1) {
        mapRange(0, xyz.size());
    } else {
        std::array<std::thread, 7> threads;
        std::size_t started = 0;
        try {
            for (std::size_t worker = 1; worker < workerCount; ++worker) {
                const std::size_t begin = xyz.size() * worker / workerCount;
                const std::size_t end = xyz.size() * (worker + 1) / workerCount;
                threads[started] = std::thread(mapRange, begin, end);
                ++started;
            }
        } catch (...) {
            for (std::size_t i = 0; i < started; ++i) threads[i].join();
            return false;
        }
        mapRange(0, xyz.size() / workerCount);
        for (std::size_t i = 0; i < started; ++i) threads[i].join();
    }
    if (!validPixels.load(std::memory_order_relaxed)) return false;

    for (std::size_t channel = 0; channel < 3; ++channel) {
        for (std::size_t i = 0; i < xyz.size(); ++i) plane[i] = xyz[i][channel];
        if (lensBlurSigma > 0.0) {
            if (!gaussianBlur(plane, filtered, scratch, width, height, lensBlurSigma, filterWorkers)) return false;
            for (std::size_t i = 0; i < xyz.size(); ++i) xyz[i][channel] = filtered[i];
        }
        if (unsharpSigma > 0.0 && unsharpAmount > 0.0) {
            for (std::size_t i = 0; i < xyz.size(); ++i) plane[i] = xyz[i][channel];
            if (!gaussianBlur(plane, filtered, scratch, width, height, unsharpSigma, filterWorkers)) return false;
            for (std::size_t i = 0; i < xyz.size(); ++i) {
                xyz[i][channel] = plane[i] + unsharpAmount * (plane[i] - filtered[i]);
            }
        }
    }
    if (encodeSrgb) {
        for (Triplet& rgb : xyz) {
            // The published rounded matrices are not exact inverses.
            rgb = multiply(kXyzToSrgb, multiply(kSrgbToXyz, rgb));
            for (double& value : rgb) {
                value = value <= 0.0031308
                            ? 12.92 * value
                            : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
            }
        }
    }
    return true;
}

}  // namespace filmkit::spektrafilm
