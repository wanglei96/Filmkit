#pragma once

#include "Development.h"

namespace filmkit::spektrafilm {

struct DirCouplers {
    bool active = true;
    std::array<Triplet, 3> inhibition{};  // Donor rows, receiver columns; includes amount.
    double diffusionUm = 20.0;
    double tailUm = 200.0;
    double tailWeight = 0.06;
};

// Produces density after DIR and before grain. Exposure and density must not overlap.
bool developFilmBeforeGrain(std::span<const Triplet> exposure, std::span<Triplet> density,
                            std::size_t width, std::size_t height, double pixelSizeUm,
                            std::span<const double> logExposureKnots, std::span<const Triplet> stockDensity,
                            Triplet gamma, EmulsionType type, const DirCouplers& dir,
                            std::size_t filterWorkers = 1);

}  // namespace filmkit::spektrafilm
