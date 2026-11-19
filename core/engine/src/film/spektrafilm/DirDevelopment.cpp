#include "DirDevelopment.h"
#include "SpatialFilter.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace filmkit::spektrafilm {
namespace {

Triplet inhibitors(Triplet density, const Triplet& maximum, EmulsionType type,
                   const std::array<Triplet, 3>& matrix) {
    if (type == EmulsionType::Positive) {
        for (std::size_t c = 0; c < 3; ++c) density[c] = maximum[c] - density[c];
    }
    Triplet result;
    for (std::size_t c = 0; c < 3; ++c) {
        result[c] = density[0] * matrix[0][c] + density[1] * matrix[1][c] + density[2] * matrix[2][c];
    }
    return result;
}

DensityCurve preInhibitionCurve(std::span<const double> axis, std::span<const Triplet> normalized,
                                const Triplet& maximum, Triplet gamma, EmulsionType type,
                                const DirCouplers& dir) {
    std::vector<Triplet> shifts;
    shifts.reserve(normalized.size());
    for (const auto& knot : normalized) shifts.push_back(inhibitors(knot, maximum, type, dir.inhibition));
    std::vector<Triplet> corrected(axis.size());
    std::vector<double> shifted(axis.size());
    for (std::size_t c = 0; c < 3; ++c) {
        for (std::size_t i = 0; i < axis.size(); ++i) {
            shifted[i] = axis[i] - shifts[i][c];
            if (!std::isfinite(shifted[i]) || (i && shifted[i] < shifted[i - 1])) {
                throw std::invalid_argument("DIR inhibition requires a monotonic shifted curve");
            }
        }
        for (std::size_t i = 0; i < axis.size(); ++i) {
            const auto hi = std::upper_bound(shifted.begin(), shifted.end(), axis[i]);
            if (hi == shifted.begin()) corrected[i][c] = normalized.front()[c];
            else if (hi == shifted.end()) corrected[i][c] = normalized.back()[c];
            else {
                const auto j = static_cast<std::size_t>(hi - shifted.begin() - 1);
                const double slope = (normalized[j + 1][c] - normalized[j][c]) / (shifted[j + 1] - shifted[j]);
                corrected[i][c] = normalized[j][c] + (axis[i] - shifted[j]) * slope;
            }
        }
    }
    return DensityCurve(axis, corrected, gamma);
}

bool diffuse(std::span<const Triplet> exposure, std::span<Triplet> correction,
             std::size_t width, std::size_t height, double sigma, double decay, double tailWeight,
             std::size_t filterWorkers) {
    constexpr std::array amplitudes{0.1633, 0.6496, 0.1870};
    constexpr std::array ratios{0.5360, 1.5236, 2.7684};
    std::vector<double> plane(exposure.size()), filtered(exposure.size()), scratch(exposure.size());
    std::vector<double> tail(exposure.size());
    for (std::size_t c = 0; c < 3; ++c) {
        for (std::size_t i = 0; i < plane.size(); ++i) plane[i] = correction[i][c];
        if (!gaussianBlur(plane, filtered, scratch, width, height, sigma, filterWorkers)) return false;
        for (std::size_t i = 0; i < plane.size(); ++i) correction[i][c] = (1.0 - tailWeight) * filtered[i];
        for (std::size_t j = 0; j < amplitudes.size(); ++j) {
            if (!gaussianBlur(plane, filtered, scratch, width, height, ratios[j] * decay, filterWorkers)) return false;
            for (std::size_t i = 0; i < plane.size(); ++i) {
                const double value = amplitudes[j] * filtered[i];
                tail[i] = j == 0 ? value : tail[i] + value;
            }
        }
        for (std::size_t i = 0; i < plane.size(); ++i) {
            correction[i][c] = exposure[i][c] - (correction[i][c] + tail[i] * tailWeight);
        }
    }
    return true;
}

}  // namespace

bool developFilmBeforeGrain(std::span<const Triplet> exposure, std::span<Triplet> density,
                            std::size_t width, std::size_t height, double pixelSizeUm,
                            std::span<const double> axis, std::span<const Triplet> stock,
                            Triplet gamma, EmulsionType type, const DirCouplers& dir, std::size_t filterWorkers) {
    if (!width || !height || width > exposure.size() / height || width * height != exposure.size() ||
        density.size() != exposure.size() || exposure.data() == density.data()) return false;
    if (!dir.active) return prepareInitialFilmDevelopment(axis, stock, gamma).develop(exposure, density);
    if (!std::isfinite(dir.diffusionUm) || (dir.diffusionUm > 0.0 &&
        (!(pixelSizeUm > 0.0) || !std::isfinite(pixelSizeUm) || !std::isfinite(dir.tailUm) ||
         !std::isfinite(dir.tailWeight)))) return false;

    Triplet minimum, maximum{};
    minimum.fill(std::numeric_limits<double>::infinity());
    for (const auto& knot : stock) {
        for (std::size_t c = 0; c < 3; ++c) minimum[c] = std::fmin(minimum[c], knot[c]);
    }
    std::vector<Triplet> normalized(stock.begin(), stock.end());
    for (auto& knot : normalized) {
        for (std::size_t c = 0; c < 3; ++c) {
            knot[c] -= minimum[c];
            maximum[c] = std::fmax(maximum[c], knot[c]);
        }
    }
    const DensityCurve initial(axis, normalized, gamma);
    const auto beforeDir = preInhibitionCurve(axis, normalized, maximum, gamma, type, dir);
    if (!initial.develop(exposure, density)) return false;
    for (auto& pixel : density) pixel = inhibitors(pixel, maximum, type, dir.inhibition);
    if (dir.diffusionUm > 0.0) {
        if (!diffuse(exposure, density, width, height, dir.diffusionUm / pixelSizeUm,
                     dir.tailUm / pixelSizeUm, dir.tailWeight, filterWorkers)) return false;
    } else {
        for (std::size_t i = 0; i < density.size(); ++i) {
            for (std::size_t c = 0; c < 3; ++c) density[i][c] = exposure[i][c] - density[i][c];
        }
    }
    return beforeDir.develop(density, density);
}

}  // namespace filmkit::spektrafilm
