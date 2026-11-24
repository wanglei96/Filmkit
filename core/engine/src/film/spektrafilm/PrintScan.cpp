#include "PrintScan.h"
#include "ExposureMath.h"
#include "OutputGamut.h"
#include "ParallelPixels.h"
#include "SpatialFilter.h"
#include "SpatialWorkspace.h"

#include <algorithm>
#include <cmath>
#include <random>
#include <stdexcept>

namespace filmkit::spektrafilm {
namespace {

constexpr double kFloor = 1e-10;
constexpr double kMidgray = 0.184;

Triplet scannerWhite(const SpectralProjection& scanner) {
    Triplet white{};
    for (const auto& band : scanner.bands) {
        for (std::size_t c = 0; c < 3; ++c) white[c] += band.illuminant * band.response[c];
    }
    for (auto& value : white) value /= scanner.normalizer;
    return white;
}

double decodeSrgb(double x) {
    // The reference decodes a neutral RGB triplet and then applies its
    // published, rounded sRGB to XYZ and XYZ to sRGB matrices before averaging.
    constexpr std::array<Triplet, 3> toXyz{{
        {0.4124, 0.3576, 0.1805}, {0.2126, 0.7152, 0.0722}, {0.0193, 0.1192, 0.9505}}};
    constexpr std::array<Triplet, 3> toRgb{{
        {3.2406, -1.5372, -0.4986}, {-0.9689, 1.8758, 0.0415}, {0.0557, -0.2040, 1.0570}}};
    const double decoded = x <= 0.04045 ? x / 12.92 : std::pow((x + 0.055) / 1.055, 2.4);
    Triplet xyz;
    for (std::size_t c = 0; c < 3; ++c) xyz[c] = decoded * (toXyz[c][0] + toXyz[c][1] + toXyz[c][2]);
    double sum = 0.0;
    for (const auto& row : toRgb) sum += row[0] * xyz[0] + row[1] * xyz[1] + row[2] * xyz[2];
    return sum / 3.0;
}

double exposureAtDensity(std::span<const double> density, std::span<const double> exposure,
                         double value) {
    if (value < density.front()) return exposure.front();
    if (value >= density.back()) return exposure.back();
    std::size_t lo = 0, hi = density.size();
    while (lo < hi) {
        const auto mid = lo + (hi - lo) / 2;
        if (value < density[mid]) hi = mid;
        else lo = mid + 1;
    }
    const auto i = lo - 1;
    if (value == density[i]) return exposure[i];
    return exposure[i] + (value - density[i]) * ((exposure[i + 1] - exposure[i]) /
                                               (density[i + 1] - density[i]));
}

}  // namespace

PrintReferences preparePrintReferences(const SpectralProjection& enlarger,
                                       const SpectralProjection& scanner,
                                       Triplet filmBlack, Triplet filmWhite,
                                       std::span<const double> paperAxis,
                                       std::span<const Triplet> paperStock,
                                       const ScanLevels& levels) {
    if (!levels.black && !levels.white) return {};
    if (paperAxis.size() < 2 || paperAxis.size() != paperStock.size()) {
        throw std::invalid_argument("invalid paper reference curve");
    }
    std::array references{filmBlack, filmWhite};
    const DensityCurve stock(paperAxis, paperStock);
    if (!projectLogSpectral(enlarger, references, references, SpectralBackend::Scalar) ||
        !stock.develop(references, references) ||
        !projectLogSpectral(scanner, references, references, SpectralBackend::Scalar)) {
        throw std::invalid_argument("could not compute paper reference densities");
    }
    const double yBlack = std::pow(10.0, references[0][1]);
    const double yWhite = std::pow(10.0, references[1][1]);
    const double black = levels.black ? decodeSrgb(levels.blackSrgb) : yBlack;
    const double white = levels.white ? decodeSrgb(levels.whiteSrgb) : yWhite;
    const double slope = (white - black) / (yWhite - yBlack + kFloor);
    const double offset = black - slope * yBlack;
    const double midgray = (kMidgray - offset) / slope;
    if (!(midgray > 0.0) || !std::isfinite(midgray)) {
        throw std::invalid_argument("invalid print midgray correction");
    }
    std::vector<double> density;
    density.reserve(paperStock.size());
    for (const auto& knot : paperStock) density.push_back((knot[0] + knot[1] + knot[2]) / 3.0);
    double base = 0.0;
    for (const auto& band : scanner.bands) base += band.baseDensity;
    base /= static_cast<double>(scanner.bands.size());
    const double corrected = exposureAtDensity(density, paperAxis, -std::log10(midgray) - base);
    const double original = exposureAtDensity(density, paperAxis, -std::log10(kMidgray) - base);
    return {true, slope, offset, std::pow(10.0, corrected - original)};
}

bool exposePaperRaw(const SpectralProjection& enlarger, std::span<const Triplet> film,
                    std::span<Triplet> raw, double printExposure,
                    const PrintReferences& references, SpectralBackend backend) {
    if (!projectSpectralLight(enlarger, film, raw, backend)) return false;
    for (auto& pixel : raw) {
        for (auto& v : pixel) v = v * printExposure * references.exposureScale;
    }
    return true;
}

bool developPaper(std::span<Triplet> raw, const DensityCurve& curve) {
    logExposure(raw, 1.0);
    return curve.develop(raw, raw);
}

bool finishPaperScanXYZ(const SpectralProjection& scanner, std::span<Triplet> xyz,
                        std::size_t width, std::size_t height,
                        const PrintReferences& references, const PaperGlare& glare,
                        std::uint64_t seed, std::span<const double> replay,
                        std::size_t filterWorkers, std::size_t pixelWorkers,
                        SpatialWorkspace* workspace) {
    if (!width || !height || width > xyz.size() / height || width * height != xyz.size() ||
        !pixelWorkers || pixelWorkers > 8 ||
        (!replay.empty() && replay.size() != xyz.size()) ||
        (workspace && workspace->size() != xyz.size())) return false;
    if (references.correctXyz && !parallelPixels(xyz.size(), pixelWorkers, [&](auto begin, auto count) {
        for (auto& pixel : xyz.subspan(begin, count)) {
            const double y = pixel[1];
            const double scaledY = std::clamp(references.slope * y + references.offset, 0.0, 1.0);
            const double scale = scaledY / (y + kFloor);
            for (auto& v : pixel) v *= scale;
        }
        return true;
    })) return false;
    if (!glare.active || glare.percent <= 0.0) return true;
    const Triplet illuminant = scannerWhite(scanner);
    SpatialWorkspace local(xyz.size());
    auto& buffers = workspace ? *workspace : local;
    if (replay.empty()) {
        const auto randomField = buffers.plane(0);
        const double variance = std::log1p(glare.roughness * glare.roughness);
        const double location = std::log(glare.percent) - 0.5 * variance;
        const double deviation = std::sqrt(variance);
        std::mt19937_64 rng(seed);
        std::normal_distribution<double> normal;
        // Retain the seeded sample order and distribute the independent exponential work.
        for (auto& v : randomField) v = location + deviation * normal(rng);
        if (!parallelPixels(xyz.size(), pixelWorkers, [&](auto begin, auto count) {
            for (auto& v : randomField.subspan(begin, count)) v = std::exp(v);
            return true;
        })) return false;
        replay = randomField;
    }
    if (glare.blurPx > 0.0) {
        const auto filtered = buffers.plane(1), scratch = buffers.plane(2);
        if (!gaussianBlur(replay, filtered, scratch, width, height, glare.blurPx, filterWorkers)) return false;
        replay = filtered;
    }
    return parallelPixels(xyz.size(), pixelWorkers, [&](auto begin, auto count) {
        for (std::size_t i = begin; i < begin + count; ++i) {
            const double amount = replay[i] / 100.0;
            for (std::size_t c = 0; c < 3; ++c) xyz[i][c] += amount * illuminant[c];
        }
        return true;
    });
}

bool finishPaperScanSrgb(const SpectralProjection& scanner,
                         std::span<Triplet> xyz,
                         std::size_t width, std::size_t height,
                         const PrintReferences& references, const PaperGlare& glare,
                         std::uint64_t seed,
                         double lensBlurSigma, double unsharpSigma,
                         double unsharpAmount, std::span<double> plane,
                         std::span<double> filtered, std::span<double> scratch,
                         std::span<const double> replay,
                         std::size_t workerCount) {
    return finishPaperScanXYZ(scanner, xyz, width, height, references,
                              glare, seed, replay) &&
           finishScanSrgb(xyz, width, height, scannerWhite(scanner),
                          lensBlurSigma, unsharpSigma, unsharpAmount, true,
                          plane, filtered, scratch, workerCount);
}

}  // namespace filmkit::spektrafilm
