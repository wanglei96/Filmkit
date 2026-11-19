#pragma once

#include "Development.h"

#include <cstdint>

namespace filmkit::spektrafilm {

struct ScanLevels {
    bool black = false;
    bool white = false;
    double blackSrgb = 0.01;
    double whiteSrgb = 0.98;
};

struct PrintReferences {
    bool correctXyz = false;
    double slope = 1.0;
    double offset = 0.0;
    double exposureScale = 1.0;
};

// The paper stock curves are the measured, unmorphed curves used by the
// black/white reference. The creative paper curve is passed to development.
PrintReferences preparePrintReferences(const SpectralProjection& enlarger,
                                       const SpectralProjection& scanner,
                                       Triplet filmBlack, Triplet filmWhite,
                                       std::span<const double> paperAxis,
                                       std::span<const Triplet> paperStock,
                                       const ScanLevels& levels);

// Dest can alias source. Diffusion, when enabled, is applied to raw between
// these two calls, before taking the second log and developing the paper.
bool exposePaperRaw(const SpectralProjection& enlarger, std::span<const Triplet> film,
                    std::span<Triplet> raw, double printExposure,
                    const PrintReferences& references, SpectralBackend backend);
bool developPaper(std::span<Triplet> raw, const DensityCurve& curve);

struct PaperGlare {
    bool active = true;
    double percent = 0.03;
    double roughness = 0.7;
    double blurPx = 0.5;
};

// Converts the scanner's log XYZ to linear XYZ, then applies the scan level
// correction and paper glare. Replay contains unblurred percentages.
bool finishPaperScanXYZ(const SpectralProjection& scanner, std::span<Triplet> logXyz,
                        std::size_t width, std::size_t height,
                        const PrintReferences& references, const PaperGlare& glare,
                        std::uint64_t seed, std::span<const double> replay = {},
                        std::size_t filterWorkers = 1);

// Complete the original scanner output after the linear-XYZ scan correction.
// The three scalar buffers are caller-owned, reusable pixel-sized work planes.
bool finishPaperScanSrgb(const SpectralProjection& scanner,
                         std::span<Triplet> logXyz,
                         std::size_t width, std::size_t height,
                         const PrintReferences& references, const PaperGlare& glare,
                         std::uint64_t seed,
                         double lensBlurSigma, double unsharpSigma,
                         double unsharpAmount, std::span<double> plane,
                         std::span<double> filtered, std::span<double> scratch,
                         std::span<const double> replay = {},
                         std::size_t workerCount = 4);

}  // namespace filmkit::spektrafilm
