#pragma once

#include "Spectral.h"

#include <vector>

namespace filmkit::spektrafilm {

enum class EmulsionType { Negative, Positive };

class DensityCurve {
public:
    DensityCurve(std::span<const double> logExposure, std::span<const Triplet> knots,
                 Triplet gamma = {1.0, 1.0, 1.0});

    // Inputs and outputs can be disjoint or identical. Work can be split into spans.
    bool develop(std::span<const Triplet> logExposure, std::span<Triplet> density) const noexcept;

private:
    struct Knot { double x, y, inverseNextStep; };
    std::array<std::vector<Knot>, 3> channels_;
};

struct EmulsionLayer { double center, amplitude, sigma; };
using PaperModel = std::array<std::vector<EmulsionLayer>, 3>;

struct PaperMorph {
    bool active = true;
    double gamma = 1.0;
    double fastGamma = 1.0;
    double slowGamma = 1.0;
    Triplet channelGamma{1.0, 1.0, 1.0};
    double exhaustion = 0.0;
};

// Prepare once per profile/chemistry change, not per image. Stock reference
// corrections use the unmorphed measured stock curve, not this curve.
DensityCurve preparePaperDevelopment(std::span<const double> logExposure, const PaperModel& model,
                                     const PaperMorph& morph, EmulsionType type);

// Initial bottom-film curve only. DIR and grain follow its evaluation.
DensityCurve prepareInitialFilmDevelopment(std::span<const double> logExposure,
                                           std::span<const Triplet> stockDensity, Triplet gamma);

}  // namespace filmkit::spektrafilm
