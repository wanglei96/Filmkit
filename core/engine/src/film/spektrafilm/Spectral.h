#pragma once

#include <array>
#include <span>

namespace filmkit::spektrafilm {

using Triplet = std::array<double, 3>;

struct SpectralBand {
    Triplet dyeDensity;
    double baseDensity;
    double illuminant;
    Triplet response;
};

struct SpectralProjection {
    std::array<SpectralBand, 81> bands;
    Triplet gain{1.0, 1.0, 1.0};
    Triplet preflash{};
    double normalizer = 1.0;
};

enum class SpectralBackend { Scalar, AdvSimd };

bool hasSpectralBackend(SpectralBackend backend) noexcept;

// Each call projects one contiguous pixel range; scheduling is left to the caller.
// Input and output must be disjoint or identical. An unavailable backend or
// unequal span lengths returns false without modifying output.
bool projectLogSpectral(const SpectralProjection& projection,
                        std::span<const Triplet> density,
                        std::span<Triplet> output,
                        SpectralBackend backend) noexcept;

}  // namespace filmkit::spektrafilm
