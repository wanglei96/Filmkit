#include "OutputGamut.h"
#include "ParallelPixels.h"
#include "SpatialFilter.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <numbers>

#if defined(FILMKIT_SPEKTRAFILM_ARM_CAM16)
#include <arm_neon.h>
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2vv_pow(float64x2_t, float64x2_t);
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2vv_atan2(float64x2_t, float64x2_t);
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2vv_hypot(float64x2_t, float64x2_t);
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2v_log1p(float64x2_t);
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2v_expm1(float64x2_t);
#endif

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
    double nbb;
    double z;
    double aw;
    double chromaScale;
    double colorfulnessScale;
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
    return {adaptation, fL, nbb, z, achromatic(adaptedWhite, nbb),
            std::pow(1.64 - std::pow(0.29, n), 0.73), std::pow(fL, 0.25)};
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
    const double squared = x * x;
    const double powered = power == 6.0 ? (squared * squared) * squared : std::pow(x, power);
    return threshold +
           scale * x / std::pow(1.0 + powered, 1.0 / power);
}

namespace {

struct Cam16Hue {
    double angle, sinH, cosH, eccentricity;
};

Cam16Hue hueFromAngle(double hue) {
    return {hue, std::sin(hue), std::cos(hue), 0.25 * (std::cos(hue + 2.0) + 3.8)};
}

struct Cam16Polar {
    double lightness, chroma;
    Cam16Hue hue;
    double achromaticResponse = 0.0;
};

bool xyzToCam16Polar(Triplet xyz, Cam16Polar& jch, bool reuseDirection = false) noexcept {
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
    const double radius = std::hypot(a, b);
    Cam16Hue direction;
    if (reuseDirection && radius > 1e-12) {
        const double sinH = b / radius, cosH = a / radius;
        // cos(h + 2) via the normalized opponent coordinates. Both CAM16
        // transforms use the same eccentricity and hue direction.
        static const double cos2 = std::cos(2.0), sin2 = std::sin(2.0);
        direction = {hue, sinH, cosH, 0.25 * (cosH * cos2 - sinH * sin2 + 3.8)};
    } else {
        direction = hueFromAngle(hue);
    }
    const double A = achromatic(adapted, state.nbb);
    const double J = 100.0 * signedPower(A / state.aw, kSurroundC * state.z);
    const double denominator = adapted[0] + adapted[1] + 21.0 * adapted[2] / 20.0;
    const double t = denominator == 0.0
                         ? 0.0
                         : (50000.0 / 13.0) * kSurroundNc * state.nbb * direction.eccentricity *
                               radius / denominator;
    const double C = signedPower(t, 0.9) * std::copysign(std::sqrt(std::abs(J / 100.0)), J) * state.chromaScale;
    const double M = C * state.colorfulnessScale;
    const double Jp = (1.0 + 100.0 * kUcsC1) * J / (1.0 + kUcsC1 * J);
    const double Mp = std::log1p(kUcsC2 * M) / kUcsC2;
    jch = {Jp, Mp, direction, A};
    return std::isfinite(Jp) && std::isfinite(Mp) && std::isfinite(hue);
}

bool cam16PolarToXyz(Cam16Polar jch, Triplet& xyz, bool preserveAchromatic = false) noexcept {
    const auto& state = kViewing;
    const double Jp = jch.lightness;
    const double J = -Jp / (kUcsC1 * Jp - 1.0 - 100.0 * kUcsC1);
    const double M = std::expm1(kUcsC2 * jch.chroma) / kUcsC2;
    const double C = M / state.colorfulnessScale;
    const double jSafe = std::max(J, std::numeric_limits<double>::epsilon());
    const double t = std::pow(C / (std::sqrt(jSafe / 100.0) * state.chromaScale), 1.0 / 0.9);
    const double A = preserveAchromatic ? jch.achromaticResponse
        : state.aw * signedPower(J / 100.0, 1.0 / (kSurroundC * state.z));
    const double p2 = A / state.nbb + 0.305;
    constexpr double p3 = 21.0 / 20.0;
    const double sinH = jch.hue.sinH, cosH = jch.hue.cosH;
    const double n = p2 * (2.0 + p3) * (460.0 / 1403.0);
    double a = 0.0, b = 0.0;
    const double p1 = t == 0.0 ? 0.0 : (50000.0 / 13.0) * kSurroundNc * state.nbb *
                      jch.hue.eccentricity / t;
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

#if defined(FILMKIT_SPEKTRAFILM_ARM_CAM16)
#include "OutputGamutNeon.inc"
#endif

}  // namespace

bool xyzToCam16Ucs(Triplet xyz, Triplet& jab) noexcept {
    Cam16Polar jch;
    if (!xyzToCam16Polar(xyz, jch)) return false;
    jab = {jch.lightness, jch.chroma * jch.hue.cosH, jch.chroma * jch.hue.sinH};
    return finite(jab);
}

bool cam16UcsToXyz(Triplet jab, Triplet& xyz) noexcept {
    if (!finite(jab)) return false;
    double hue = std::atan2(jab[2], jab[1]);
    if (hue < 0.0) hue += 2.0 * std::numbers::pi;
    return cam16PolarToXyz({jab[0], std::hypot(jab[1], jab[2]), hueFromAngle(hue)}, xyz);
}

bool compressSrgbCam16Ucs(Triplet linearRgb, Triplet& compressedRgb) noexcept {
    if (!finite(linearRgb)) return false;
    Cam16Polar jch;
    if (!xyzToCam16Polar(multiply(kSrgbToXyz, linearRgb), jch, true)) return false;

    const double normalizedLightness = jch.lightness / 100.0;
    const double Jp = reinhardKnee(normalizedLightness, 0.7, 1.0, 2.2) * 100.0;
    const bool positiveChroma = jch.chroma > 0.0;
    double Cp = jch.chroma;
    double hp = jch.hue.angle;
    if (hp > std::numbers::pi) hp -= 2.0 * std::numbers::pi;
    if (!positiveChroma) {
        // Keep the original hue convention for negative chroma and signed zeros.
        const double a = jch.chroma * std::cos(jch.hue.angle);
        const double b = jch.chroma * std::sin(jch.hue.angle);
        Cp = std::hypot(a, b);
        hp = std::atan2(b, a);
    }
    const double CpMax = lookupPreparedGamut(kSrgbCam16Gamut, Jp, hp);
    const double safeMax = std::max(CpMax, 1e-9);
    const double normalized = Cp / safeMax;
    const double compressedChroma = reinhardKnee(normalized, 0.0, 1.0, 6.0) * safeMax;
    Triplet xyz{};
    // Compression changes lightness and chroma, but not hue. Stay in polar
    // coordinates instead of repeatedly recovering them from Cartesian UCS.
    if (positiveChroma) {
        // When the lightness knee is inactive, inverse J is the original A.
        const bool preserveAchromatic = normalizedLightness >= 0.0 && normalizedLightness <= 0.7;
        if (!cam16PolarToXyz({Jp, compressedChroma, jch.hue, jch.achromaticResponse},
                             xyz, preserveAchromatic)) return false;
    } else if (!cam16UcsToXyz({Jp, compressedChroma * std::cos(hp),
                              compressedChroma * std::sin(hp)}, xyz)) return false;
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
    if (!parallelPixels(xyz.size(), workerCount, [&](std::size_t begin, std::size_t count) {
        const auto adapt = [&](Triplet value) {
            Triplet cone = multiply(cat02, value);
            for (std::size_t c = 0; c < 3; ++c) cone[c] *= adaptation[c];
            return multiply(kXyzToSrgb, multiply(cat02Inverse, cone));
        };
        auto i = begin;
#if defined(FILMKIT_SPEKTRAFILM_ARM_CAM16)
        for (; begin + count - i >= 2; i += 2) {
            if (!compressSrgbCam16UcsPair(adapt(xyz[i]), adapt(xyz[i + 1]), xyz[i], xyz[i + 1])) return false;
        }
#endif
        for (; i < begin + count; ++i) {
            Triplet& value = xyz[i];
            if (!finite(value)) return false;
            Triplet compressed{};
            if (!compressSrgbCam16Ucs(adapt(value), compressed)) return false;
            value = compressed;
        }
        return true;
    })) return false;

    const bool blur = lensBlurSigma > 0.0;
    const bool sharpen = unsharpSigma > 0.0 && unsharpAmount > 0.0;
    if (blur || sharpen) {
        for (std::size_t channel = 0; channel < 3; ++channel) {
            if (!parallelPixels(xyz.size(), workerCount, [&](auto begin, auto count) {
                for (std::size_t i = begin; i < begin + count; ++i) plane[i] = xyz[i][channel];
                return true;
            })) return false;
            if (blur) {
                if (!gaussianBlur(plane, filtered, scratch, width, height, lensBlurSigma, filterWorkers)) return false;
                std::swap(plane, filtered);
            }
            if (sharpen) {
                if (!gaussianBlur(plane, filtered, scratch, width, height, unsharpSigma, filterWorkers)) return false;
            }
            if (!parallelPixels(xyz.size(), workerCount, [&](auto begin, auto count) {
                if (sharpen) {
                    for (std::size_t i = begin; i < begin + count; ++i) {
                        xyz[i][channel] = plane[i] + unsharpAmount * (plane[i] - filtered[i]);
                    }
                } else {
                    for (std::size_t i = begin; i < begin + count; ++i) xyz[i][channel] = plane[i];
                }
                return true;
            })) return false;
        }
    }
    if (!encodeSrgb) return true;
    return parallelPixels(xyz.size(), workerCount, [&](auto begin, auto count) {
        for (Triplet& rgb : xyz.subspan(begin, count)) {
            // The published rounded matrices are not exact inverses.
            rgb = multiply(kXyzToSrgb, multiply(kSrgbToXyz, rgb));
            for (double& value : rgb) {
                value = value <= 0.0031308
                            ? 12.92 * value
                            : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
            }
        }
        return true;
    });
}

}  // namespace filmkit::spektrafilm
