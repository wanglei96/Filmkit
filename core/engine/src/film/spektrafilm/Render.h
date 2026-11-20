#pragma once

#include "DirDevelopment.h"
#include "FilmExposure.h"
#include "Grain.h"
#include "PrintScan.h"

#include <utility>

namespace filmkit::spektrafilm {

// Prepared spectral recipe; direct scans omit printing, not film development.
struct NegativeRenderParams {
    EmulsionType filmType = EmulsionType::Negative;
    bool directScan = false;
    bool compensatePrintExposure = false;
    Triplet midgrayRaw{};
    double pixelUm = 0.0;
    std::array<Triplet, 3> xyzFromRgb{};
    std::size_t responseSide = 0;
    std::vector<Triplet> response;
    double exposureEv = 0.0;
    double captureExposureEv = 0.0;  // Scene exposure correction, not a print reference adjustment.
    HighlightBoost boost;
    double lensBlurUm = 0.0;
    FilmHalation halation;
    double exposureCorrection = 1.0;
    std::vector<double> filmAxis;
    std::vector<Triplet> filmStock;
    Triplet filmGamma{1.0, 1.0, 1.0};
    DirCouplers dir;
    std::vector<Triplet> grainTotal;
    std::vector<GrainLayers> grainLayers;
    GrainParams grain;
    SpectralProjection enlarger, scanner;
    double printExposure = 1.0;
    PrintReferences references;
    DensityCurve paperCurve;
    PaperGlare glare;
    double scanLensBlur = 0.0;
    double unsharpSigma = 0.0;
    double unsharpAmount = 0.0;

    explicit NegativeRenderParams(DensityCurve curve) : paperCurve(std::move(curve)) {}
};

struct RenderWorkers {
    std::size_t pixels = 4;
    std::size_t grain = 4;
    std::size_t output = 4;
    std::size_t filters = 1;
};

struct RenderTimings {
    double hanatos = 0.0;
    double filmExposure = 0.0;
    double dir = 0.0;
    double grain = 0.0;
    GrainTimings grainParts;
    double paperExposure = 0.0;
    double paperDevelopment = 0.0;
    double scanSpectral = 0.0;
    double scanGlare = 0.0;
    double scanOutput = 0.0;
    double total = 0.0;
};

// Overwrites scene-linear Rec.2020 with encoded sRGB, without clipping.
// Includes temporary-buffer allocation; no file I/O or reference pixel reads.
// On failure discard the image. Replay hooks exist only for oracle validation.
bool renderNegativeSrgb(std::vector<Triplet>& image, std::size_t width, std::size_t height,
                         const NegativeRenderParams& params, SpectralBackend backend,
                         RenderWorkers workers, std::uint64_t grainSeed,
                         std::uint64_t glareSeed, RenderTimings* timings = nullptr,
                         const GrainReplay* grainReplay = nullptr,
                         std::span<const double> glareReplay = {});

// Independent uniform patches, not a blurred ramp. Only stochastic texture is
// disabled for this display-reference calibration; the photograph is unchanged.
// Returns the selected recipe's full negative/paper/scan linear RGB response.
bool negativeNeutralResponse(const NegativeRenderParams& params, SpectralBackend backend,
                              std::span<Triplet> response);

// Versioned engine-private controls, independent of the shared FilmParams ABI.
bool applyRenderOptions(NegativeRenderParams& params, std::span<const double> options);

}  // namespace filmkit::spektrafilm
