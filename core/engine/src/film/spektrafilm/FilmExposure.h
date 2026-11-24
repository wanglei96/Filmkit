#pragma once

#include "Spectral.h"

namespace filmkit::spektrafilm {

class SpatialWorkspace;

// The original Hanatos response table stores per-stock raw sensitivities,
// not developed film or the final look. xyzFromRgb includes CAT16 adaptation.
// Input and output may be identical or disjoint.
bool projectHanatosRaw(std::span<const Triplet> linearRgb, std::span<Triplet> raw,
                       const std::array<Triplet, 3>& xyzFromRgb,
                       std::span<const Triplet> response, std::size_t side,
                       double exposureGain = 1.0);

struct HighlightBoost {
    double ev = 0.0;
    double range = 0.3;
    double protectEv = 4.0;
};

bool prepareFilmExposureGain(double exposureEv, const HighlightBoost& boost, double& gain);

struct FilmHalation {
    bool active = true;
    double scatterAmount = 1.0;
    double scatterScale = 1.0;
    Triplet scatterCoreUm{2.2, 2.0, 1.6};
    Triplet scatterTailUm{9.3, 9.7, 9.1};
    Triplet scatterTailWeight{0.78, 0.65, 0.67};
    double halationAmount = 1.0;
    double halationScale = 1.0;
    Triplet halationStrength{0.05, 0.015, 0.0};
    Triplet halationFirstSigmaUm{65.0, 65.0, 65.0};
    std::size_t bounces = 3;
    double bounceDecay = 0.5;
    bool renormalize = true;
};

// Optical diffusion (if active) is applied to raw between these calls.
bool boostFilmRaw(std::span<Triplet> raw, double exposureEv, const HighlightBoost& boost);
bool finishFilmLogExposure(std::span<Triplet> raw, std::size_t width, std::size_t height,
                           double pixelUm, double lensBlurUm, const FilmHalation& halation,
                           double exposureCorrection, std::size_t filterWorkers = 1,
                           SpatialWorkspace* workspace = nullptr);

}  // namespace filmkit::spektrafilm
