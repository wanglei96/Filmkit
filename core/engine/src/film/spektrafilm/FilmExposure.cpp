#include "FilmExposure.h"
#include "ParallelPixels.h"
#include "SpatialFilter.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <vector>

namespace filmkit::spektrafilm {
namespace {

bool anyPositive(const Triplet& value) {
    return std::any_of(value.begin(), value.end(), [](double x) { return x > 0.0; });
}

double mitchell(double t) {
    constexpr double b = 1.0 / 3.0, c = 1.0 / 3.0;
    const double x = std::abs(t);
    const double x2 = x * x;
    const double x3 = x2 * x;
    if (x < 1.0) return ((12 - 9*b - 6*c)*x3 + (-18 + 12*b + 6*c)*x2 + (6 - 2*b)) / 6.0;
    if (x < 2.0) return ((-b - 6*c)*x3 + (6*b + 30*c)*x2 + (-12*b - 48*c)*x + (8*b + 24*c)) / 6.0;
    return 0.0;
}

struct Taps {
    std::array<std::size_t, 4> index;
    std::array<double, 4> weight;
};

Taps responseTaps(double position, std::ptrdiff_t end) {
    const double coord = std::clamp(position, 0.0, static_cast<double>(end));
    const auto base = std::min(static_cast<std::ptrdiff_t>(coord), end - 1);
    const double t = coord - static_cast<double>(base);
    Taps taps;
    for (std::ptrdiff_t k = 0; k < 4; ++k) {
        auto i = base + k - 1;
        if (i < 0) i = -i;
        if (i > end) i = 2 * end - i;
        taps.index[k] = static_cast<std::size_t>(i);
        taps.weight[k] = mitchell(t - static_cast<double>(k - 1));
    }
    return taps;
}

}  // namespace

bool projectHanatosRaw(std::span<const Triplet> rgb, std::span<Triplet> raw,
                       const std::array<Triplet, 3>& xyzFromRgb,
                       std::span<const Triplet> response, std::size_t side) {
    if (rgb.size() != raw.size() || side < 2 || side > response.size() / side ||
        side * side != response.size()) return false;
    const auto end = static_cast<std::ptrdiff_t>(side - 1);
    for (std::size_t i = 0; i < rgb.size(); ++i) {
        Triplet xyz;
        for (std::size_t c = 0; c < 3; ++c) {
            const auto& row = xyzFromRgb[c];
            xyz[c] = row[0]*rgb[i][0] + row[1]*rgb[i][1] + row[2]*rgb[i][2];
            if (!std::isfinite(xyz[c])) return false;
        }
        const double sum = (xyz[0] + xyz[1]) + xyz[2];
        if (!std::isfinite(sum)) return false;
        const double denominator = std::max(sum, 1e-10);
        const double oneMinusX = 1.0 - xyz[0] / denominator;
        const double x = std::clamp(oneMinusX * oneMinusX, 0.0, 1.0);
        const double y = std::clamp((xyz[1] / denominator) / std::max(oneMinusX, 1e-10), 0.0, 1.0);
        const auto tx = responseTaps(x * end, end);
        const auto ty = responseTaps(y * end, end);
        Triplet result{};
        double norm = 0.0;
        for (std::size_t a = 0; a < 4; ++a) {
            for (std::size_t b = 0; b < 4; ++b) {
                const double weight = tx.weight[a] * ty.weight[b];
                const auto& tap = response[tx.index[a] * side + ty.index[b]];
                norm += weight;
                for (std::size_t c = 0; c < 3; ++c) result[c] += weight * tap[c];
            }
        }
        for (std::size_t c = 0; c < 3; ++c) raw[i][c] = (norm != 0.0 ? result[c] / norm : result[c]) * sum;
    }
    return true;
}

bool boostFilmRaw(std::span<Triplet> raw, double exposureEv, const HighlightBoost& boost) {
    if (raw.empty() || !std::isfinite(exposureEv) || !std::isfinite(boost.ev) || boost.ev < 0.0 ||
        !(boost.range >= 0.0 && boost.range <= 1.0) || !std::isfinite(boost.protectEv) ||
        boost.protectEv < 0.0) return false;
    const double gain = std::exp2(exposureEv);
    for (auto& pixel : raw) for (auto& value : pixel) value *= gain;
    if (boost.ev == 0.0) return true;

    double maximum = raw.front().front();
    for (const auto& pixel : raw) for (double value : pixel) maximum = std::max(maximum, value);
    if (maximum < 0.0 || !std::isfinite(maximum)) return false;
    if (maximum == 0.0) {
        std::fill(raw.begin(), raw.end(), Triplet{});
        return true;
    }
    const double knee = std::min(0.184 * std::exp2(boost.protectEv), maximum);
    if (knee == maximum) return true;
    const double shape = std::pow(28.0, 1.0 - boost.range);
    const double top = shape * (1.0 - knee / maximum);
    const double denominator = std::expm1(top) - top;
    if (!(denominator > 0.0)) return false;
    const double scale = (std::exp2(boost.ev) - 1.0) / denominator * maximum;
    const double inverse = 1.0 / maximum;
    for (auto& pixel : raw) {
        for (auto& value : pixel) {
            if (value > knee) {
                const double x = shape * ((value - knee) * inverse);
                value += scale * (std::expm1(x) - x);
            }
        }
    }
    return true;
}

bool finishFilmLogExposure(std::span<Triplet> raw, std::size_t width, std::size_t height,
                           double pixelUm, double lensBlurUm, const FilmHalation& h,
                           double exposureCorrection, std::size_t filterWorkers) {
    if (!width || !height || width > raw.size() / height || width * height != raw.size() ||
        !(pixelUm > 0.0) || !std::isfinite(pixelUm) || !std::isfinite(lensBlurUm) ||
        !std::isfinite(exposureCorrection) || !(exposureCorrection > 0.0)) return false;
    Triplet core, tail, strength, sigma;
    for (std::size_t c = 0; c < 3; ++c) {
        core[c] = h.scatterCoreUm[c] * h.scatterScale / pixelUm;
        tail[c] = h.scatterTailUm[c] * h.scatterScale / pixelUm;
        strength[c] = h.halationStrength[c] * h.halationAmount;
        sigma[c] = h.halationFirstSigmaUm[c] * h.halationScale / pixelUm;
    }
    const bool scatter = h.active && h.scatterAmount > 0.0 && (anyPositive(core) || anyPositive(tail));
    const bool reflect = h.active && h.bounces > 0 && anyPositive(strength) && anyPositive(sigma);
    if (lensBlurUm > 0.0 || scatter || reflect) {
        const auto count = raw.size();
        // Input, blur work planes and the first accumulation tap are fully written on use.
        const std::unique_ptr<double[]> inputStorage(new double[count]);
        const std::unique_ptr<double[]> outputStorage(new double[count]);
        const std::unique_ptr<double[]> scratchStorage(new double[count]);
        const std::unique_ptr<double[]> accum(scatter || reflect ? new double[count] : nullptr);
        std::span<double> input(inputStorage.get(), count), output(outputStorage.get(), count);
        const std::span<double> scratch(scratchStorage.get(), count);
        std::vector<double> weights;
        if (reflect) {
            weights.resize(h.bounces);
            double sum = 0.0;
            for (std::size_t k = 0; k < weights.size(); ++k) {
                weights[k] = std::pow(h.bounceDecay, static_cast<double>(k));
                sum += weights[k];
            }
            if (!(sum > 0.0) || !std::isfinite(sum)) return false;
            for (auto& weight : weights) weight /= sum;
        }
        const auto blur = [&](double s) { return gaussianBlur(input, output, scratch, width, height, s, filterWorkers); };
        for (std::size_t c = 0; c < 3; ++c) {
            if (!parallelPixelIndices(count, filterWorkers, [&](auto i) {
                input[i] = raw[i][c];
            })) return false;
            if (lensBlurUm > 0.0) {
                if (!blur(lensBlurUm / pixelUm)) return false;
                std::swap(input, output);
            }
            if (scatter) {
                constexpr std::array weights{0.1633, 0.6496, 0.1870};
                constexpr std::array ratios{0.5360, 1.5236, 2.7684};
                for (std::size_t k = 0; k < weights.size(); ++k) {
                    if (!blur(ratios[k] * std::max(tail[c], 1e-6))) return false;
                    if (!parallelPixelIndices(count, filterWorkers, [&](auto i) {
                        const double tap = weights[k] * output[i];
                        accum[i] = k ? accum[i] + tap : tap;
                    })) return false;
                }
                if (!blur(std::max(core[c], 1e-6))) return false;
                if (!parallelPixelIndices(count, filterWorkers, [&](auto i) {
                    const double mixed = (1.0 - h.scatterTailWeight[c]) * output[i] + h.scatterTailWeight[c] * accum[i];
                    input[i] = (1.0 - h.scatterAmount) * input[i] + h.scatterAmount * mixed;
                })) return false;
            }
            // A zero-strength channel has no reflected contribution.
            if (reflect && (strength[c] != 0.0 || !std::isfinite(sigma[c]))) {
                for (std::size_t k = 0; k < weights.size(); ++k) {
                    if (!blur(std::max(sigma[c] * std::sqrt(static_cast<double>(k + 1)), 1e-6))) return false;
                    if (!parallelPixelIndices(count, filterWorkers, [&](auto i) {
                        const double tap = weights[k] * output[i];
                        accum[i] = k ? accum[i] + tap : tap;
                    })) return false;
                }
                if (!parallelPixelIndices(count, filterWorkers, [&](auto i) {
                    input[i] += strength[c] * accum[i];
                    if (h.renormalize) input[i] /= 1.0 + strength[c];
                })) return false;
            }
            if (!parallelPixelIndices(count, filterWorkers, [&](auto i) {
                raw[i][c] = input[i];
            })) return false;
        }
    }
    return parallelPixels(raw.size(), filterWorkers, [&](auto begin, auto count) {
        for (auto& pixel : raw.subspan(begin, count)) {
            for (auto& value : pixel) value = std::log10(std::fmax(value * exposureCorrection, 0.0) + 1e-10);
        }
        return true;
    });
}

}  // namespace filmkit::spektrafilm
