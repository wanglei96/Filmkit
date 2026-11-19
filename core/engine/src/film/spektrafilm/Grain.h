#pragma once

#include "Development.h"

#include <cstdint>

namespace filmkit::spektrafilm {

using GrainLayers = std::array<Triplet, 3>;  // [layer][channel]

struct GrainParams {
    bool active = true;
    bool layered = true;
    std::size_t singleModelLayers = 1;
    double particleAreaUm2 = 0.2;
    Triplet particleScale{1.6, 1.6, 3.2};
    Triplet layerScale{2.0, 1.0, 0.5};
    Triplet densityFloor{0.03, 0.03, 0.03};
    Triplet uniformity{0.97, 0.99, 0.97};
    double finalBlurPx = 0.65;
    double dyeCloudBlur = 1.0;
    double microBlurUm = 0.2;
    double microDeviationNm = 30.0;
};

// Replay hooks accept the original distribution parameters so a checker can
// validate them as well as the deterministic work after each random draw.
struct GrainReplay {
    void* context = nullptr;
    std::uint64_t (*developed)(void*, std::size_t channel, std::size_t layer,
                               std::size_t pixel, double poissonMean, double probability) = nullptr;
    double (*clumping)(void*, std::size_t channel, std::size_t pixel, double deviation) = nullptr;
};

struct GrainTimings {
    double particlesSeconds = 0;
    double dyeCloudSeconds = 0;
    double finishSeconds = 0;
};

// Input and output are disjoint. totalCurve is the stock density curve with
// its channel minima removed; layers are the unmodified stock sublayer curves.
bool applyFilmGrain(std::span<const Triplet> input, std::span<Triplet> output,
                    std::size_t width, std::size_t height, double pixelUm,
                    std::span<const Triplet> totalCurve, std::span<const GrainLayers> layers,
                    EmulsionType type, const GrainParams& params, std::uint64_t seed,
                    const GrainReplay* replay = nullptr, GrainTimings* timings = nullptr,
                    std::size_t samplingWorkers = 1, std::size_t filterWorkers = 1);

}  // namespace filmkit::spektrafilm
