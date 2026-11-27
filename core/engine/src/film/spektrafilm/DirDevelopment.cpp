#include "DirDevelopment.h"
#include "ParallelPixels.h"
#include "SpatialFilter.h"
#include "SpatialWorkspace.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
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
             std::size_t filterWorkers, SpatialWorkspace* workspace) {
    constexpr std::array amplitudes{0.1633, 0.6496, 0.1870};
    constexpr std::array ratios{0.5360, 1.5236, 2.7684};
    const auto count = exposure.size();
    // Every plane is written in full before its first read, including the first tail tap.
    SpatialWorkspace local(count);
    auto& buffers = workspace ? *workspace : local;
    const auto plane = buffers.plane(0), filtered = buffers.plane(1),
               scratch = buffers.plane(2), tail = buffers.plane(3);
    std::array<GaussianTap, amplitudes.size()> taps;
    for (std::size_t j = 0; j < taps.size(); ++j) taps[j] = {ratios[j] * decay, amplitudes[j]};
    for (std::size_t c = 0; c < 3; ++c) {
        if (!parallelPixelIndices(plane.size(), filterWorkers, [&](auto i) {
            plane[i] = correction[i][c];
        })) return false;
        if (!gaussianBlurMixture(plane, filtered, scratch, tail, taps, width, height, filterWorkers)) return false;
        if (!gaussianBlur(plane, filtered, scratch, width, height, sigma, filterWorkers)) return false;
        if (!parallelPixelIndices(plane.size(), filterWorkers, [&](auto i) {
            const double core = (1.0 - tailWeight) * filtered[i];
            correction[i][c] = exposure[i][c] - (core + tail[i] * tailWeight);
        })) return false;
    }
    return true;
}

// Generate all three donor-weighted inhibitor planes before writing any
// source exposure. The full-frame Gaussian passes now read these directly.
bool developInPlace(std::span<Triplet> exposure, std::size_t width, std::size_t height,
                    double pixelSizeUm, const DensityCurve& initial, const DensityCurve& beforeDir,
                    const Triplet& maximum, EmulsionType type, const DirCouplers& dir,
                    std::size_t workers, SpatialWorkspace* workspace) {
    const std::size_t count = exposure.size();
    const bool spread = dir.diffusionUm > 0.0;
    SpatialWorkspace local(count);
    auto& work = workspace ? *workspace : local;
    std::array<std::unique_ptr<double[]>, 2> extra;
    std::array<std::span<double>, 3> field;
    if (spread) {
        field[0] = work.plane(0);
        for (std::size_t c = 1; c < 3; ++c) {
            extra[c - 1] = std::make_unique_for_overwrite<double[]>(count);
            field[c] = {extra[c - 1].get(), count};
        }
    }
    if (!parallelPixels(count, workers, [&](std::size_t begin, std::size_t n) {
        std::array<Triplet, 256> developed;
        for (std::size_t offset = 0; offset < n;) {
            const auto start = begin + offset;
            const auto length = std::min(n - offset, developed.size());
            const auto part = exposure.subspan(start, length);
            const auto initialDensity = std::span(developed).first(length);
            if (!initial.develop(part, initialDensity)) return false;
            for (std::size_t i = 0; i < length; ++i) {
                const auto shifts = inhibitors(initialDensity[i], maximum, type, dir.inhibition);
                for (std::size_t c = 0; c < 3; ++c) {
                    if (spread) field[c][start + i] = shifts[c];
                    else part[i][c] = part[i][c] - shifts[c];
                }
            }
            offset += length;
        }
        return true;
    })) return false;
    if (spread) {
        constexpr std::array amplitudes{0.1633, 0.6496, 0.1870};
        constexpr std::array ratios{0.5360, 1.5236, 2.7684};
        const double sigma = dir.diffusionUm / pixelSizeUm, decay = dir.tailUm / pixelSizeUm;
        std::array<GaussianTap, amplitudes.size()> taps;
        for (std::size_t j = 0; j < taps.size(); ++j) taps[j] = {ratios[j] * decay, amplitudes[j]};
        const auto filtered = work.plane(1), scratch = work.plane(2), tail = work.plane(3);
        for (std::size_t c = 0; c < 3; ++c) {
            if (!gaussianBlurMixture(field[c], filtered, scratch, tail, taps, width, height, workers)) return false;
            if (!gaussianBlur(field[c], filtered, scratch, width, height, sigma, workers)) return false;
            if (!parallelPixelIndices(count, workers, [&](std::size_t i) {
                const double core = (1.0 - dir.tailWeight) * filtered[i];
                exposure[i][c] = exposure[i][c] - (core + tail[i] * dir.tailWeight);
            })) return false;
            if (c != 0) extra[c - 1].reset();
        }
    }
    return parallelPixels(count, workers, [&](std::size_t begin, std::size_t n) {
        const auto part = exposure.subspan(begin, n);
        return beforeDir.develop(part, part);
    });
}

}  // namespace

bool developFilmBeforeGrain(std::span<const Triplet> exposure, std::span<Triplet> density,
                            std::size_t width, std::size_t height, double pixelSizeUm,
                            std::span<const double> axis, std::span<const Triplet> stock,
                            Triplet gamma, EmulsionType type, const DirCouplers& dir,
                            std::size_t filterWorkers, SpatialWorkspace* workspace) {
    if (!width || !height || width > exposure.size() / height || width * height != exposure.size() ||
        density.size() != exposure.size() ||
        (workspace && workspace->size() != exposure.size())) return false;
    if (!dir.active) {
        const auto curve = prepareInitialFilmDevelopment(axis, stock, gamma);
        return parallelPixels(density.size(), filterWorkers, [&](auto begin, auto count) {
            return curve.develop(exposure.subspan(begin, count), density.subspan(begin, count));
        });
    }
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
    if (exposure.data() == density.data()) {
        return developInPlace(density, width, height, pixelSizeUm, initial, beforeDir,
                              maximum, type, dir, filterWorkers, workspace);
    }
    const bool spread = dir.diffusionUm > 0.0;
    if (!parallelPixels(density.size(), filterWorkers, [&](auto begin, auto count) {
        const auto input = exposure.subspan(begin, count);
        const auto output = density.subspan(begin, count);
        if (!initial.develop(input, output)) return false;
        for (std::size_t i = 0; i < output.size(); ++i) {
            output[i] = inhibitors(output[i], maximum, type, dir.inhibition);
            if (!spread) {
                for (std::size_t c = 0; c < 3; ++c) output[i][c] = input[i][c] - output[i][c];
            }
        }
        return true;
    })) return false;
    if (spread && !diffuse(exposure, density, width, height, dir.diffusionUm / pixelSizeUm,
                           dir.tailUm / pixelSizeUm, dir.tailWeight, filterWorkers, workspace)) return false;
    return parallelPixels(density.size(), filterWorkers, [&](auto begin, auto count) {
        const auto part = density.subspan(begin, count);
        return beforeDir.develop(part, part);
    });
}

}  // namespace filmkit::spektrafilm
