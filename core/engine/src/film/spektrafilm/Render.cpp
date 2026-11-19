#include "Render.h"
#include "OutputGamut.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <future>

namespace filmkit::spektrafilm {
namespace {

template <class Function>
bool parallelPixels(std::size_t pixels, std::size_t workers, Function operation) {
    workers = std::min(workers, pixels);
    const auto run = [&](std::size_t worker) {
        const auto begin = pixels * worker / workers;
        return operation(begin, pixels * (worker + 1) / workers - begin);
    };
    std::array<std::future<bool>, 7> pending;
    for (std::size_t i = 1; i < workers; ++i) pending[i - 1] = std::async(std::launch::async, run, i);
    bool ok = run(0);
    for (std::size_t i = 1; i < workers; ++i) ok = pending[i - 1].get() && ok;
    return ok;
}

}  // namespace

bool renderNegativeSrgb(std::vector<Triplet>& image, std::size_t width, std::size_t height,
                         const NegativeRenderParams& p, SpectralBackend backend,
                         RenderWorkers workers, std::uint64_t grainSeed,
                         std::uint64_t glareSeed, RenderTimings* timings,
                         const GrainReplay* grainReplay, std::span<const double> glareReplay) {
    using Clock = std::chrono::steady_clock;
    if (timings) *timings = {};
    if (!width || !height || width > image.size() / height || width * height != image.size() ||
        !workers.pixels || workers.pixels > 8 || !workers.grain || workers.grain > 8 ||
        !workers.output || workers.output > 8 || !workers.filters || workers.filters > 8 ||
        !hasSpectralBackend(backend)) return false;
    RenderTimings t;
    const auto start = Clock::now();
    auto previous = start;
    const auto mark = [&](double& field) {
        const auto now = Clock::now();
        field = std::chrono::duration<double>(now - previous).count();
        previous = now;
    };
    const auto pixels = [&] { return std::span<Triplet>(image); };
    auto enlarger = p.enlarger;
    if (!p.directScan && p.compensatePrintExposure) {
        // Original FilmingStage's unboosted, unblurred, simple-development
        // midgray reference, recomputed at this capture's actual exposure.
        std::array<Triplet, 1> reference{{p.midgrayRaw}};
        for (auto& v : reference[0]) v = std::log10(std::fmax(v * std::exp2(p.exposureEv), 0.0) + 1e-10);
        const DensityCurve curve(p.filmAxis, p.filmStock, p.filmGamma);
        auto unit = enlarger;
        unit.gain = {1.0, 1.0, 1.0}; unit.preflash = {};
        if (!curve.develop(reference, reference) ||
            !projectLogSpectral(unit, reference, reference, backend)) return false;
        const double mean = (reference[0][0] + reference[0][1] + reference[0][2]) / 3.0;
        enlarger.gain.fill(std::pow(10.0, -mean));
    }
    if (!parallelPixels(image.size(), workers.pixels, [&](auto begin, auto count) {
            const auto part = pixels().subspan(begin, count);
            return projectHanatosRaw(part, part, p.xyzFromRgb, p.response, p.responseSide);
        })) return false;
    mark(t.hanatos);
    if (!boostFilmRaw(image, p.exposureEv, p.boost) ||
        !finishFilmLogExposure(image, width, height, p.pixelUm, p.lensBlurUm,
                               p.halation, p.exposureCorrection, workers.filters)) return false;
    mark(t.filmExposure);
    std::vector<Triplet> work(image.size());
    if (!developFilmBeforeGrain(image, work, width, height, p.pixelUm,
                                p.filmAxis, p.filmStock, p.filmGamma,
                                p.filmType, p.dir, workers.filters)) return false;
    image.swap(work);
    mark(t.dir);
    if (!applyFilmGrain(image, work, width, height, p.pixelUm, p.grainTotal, p.grainLayers,
                        p.filmType, p.grain, grainSeed, grainReplay,
                        &t.grainParts, workers.grain, workers.filters)) return false;
    image.swap(work);
    std::vector<Triplet>().swap(work);
    mark(t.grain);
    if (!p.directScan) {
    if (!parallelPixels(image.size(), workers.pixels, [&](auto begin, auto count) {
            const auto part = pixels().subspan(begin, count);
            return exposePaperRaw(enlarger, part, part, p.printExposure, p.references, backend);
        })) return false;
    mark(t.paperExposure);
    if (!parallelPixels(image.size(), workers.pixels, [&](auto begin, auto count) {
            return developPaper(pixels().subspan(begin, count), p.paperCurve);
        })) return false;
    mark(t.paperDevelopment);
    }
    if (!parallelPixels(image.size(), workers.pixels, [&](auto begin, auto count) {
            const auto part = pixels().subspan(begin, count);
            return projectLogSpectral(p.scanner, part, part, backend);
        })) return false;
    mark(t.scanSpectral);
    if (!finishPaperScanXYZ(p.scanner, image, width, height, p.references, p.glare,
                            glareSeed, glareReplay, workers.filters)) return false;
    mark(t.scanGlare);
    Triplet white{};
    for (const auto& band : p.scanner.bands) {
        for (std::size_t c = 0; c < 3; ++c) white[c] += band.illuminant * band.response[c];
    }
    for (auto& value : white) value /= p.scanner.normalizer;
    std::vector<double> plane(image.size()), filtered(image.size()), scratch(image.size());
    if (!finishScanSrgb(image, width, height, white, p.scanLensBlur, p.unsharpSigma,
                        p.unsharpAmount, true, plane, filtered, scratch, workers.output, workers.filters)) return false;
    // Include final work-buffer release in the measured render time.
    std::vector<double>().swap(plane);
    std::vector<double>().swap(filtered);
    std::vector<double>().swap(scratch);
    mark(t.scanOutput);
    t.total = std::chrono::duration<double>(Clock::now() - start).count();
    if (timings) *timings = t;
    return true;
}

bool negativeNeutralResponse(const NegativeRenderParams& params, SpectralBackend backend,
                              std::span<Triplet> response) {
    if (response.size() < 2) return false;
    auto calibration = params;
    calibration.grain.active = false;
    calibration.glare.roughness = 0.0;
    std::vector<Triplet> patch(1);
    for (std::size_t i = 0; i < response.size(); ++i) {
        const double x = static_cast<double>(i) / (response.size() - 1);
        patch[0] = {x, x, x};
        if (!renderNegativeSrgb(patch, 1, 1, calibration, backend, {1, 1, 1, 1}, 0, 0)) return false;
        for (std::size_t c = 0; c < 3; ++c) {
            const double v = patch[0][c];
            response[i][c] = v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
            if (!std::isfinite(response[i][c])) return false;
        }
    }
    return true;
}

bool applyRenderOptions(NegativeRenderParams& p, std::span<const double> v) {
    // v1: version, grain, area scale, grain blur, halation, scatter, DIR,
    // print EV, scanner blur, unsharp radius, unsharp amount, film size mm.
    if (v.size() != 12 || v[0] != 1.0 ||
        !std::all_of(v.begin(), v.end(), [](double x) { return std::isfinite(x); }) ||
        (v[1] != 0.0 && v[1] != 1.0) || v[2] < 0.1 || v[2] > 4.0 ||
        v[3] < 0.0 || v[3] > 3.0 || v[4] < 0.0 || v[4] > 3.0 ||
        v[5] < 0.0 || v[5] > 3.0 || v[6] < 0.0 || v[6] > 2.0 ||
        v[7] < -3.0 || v[7] > 3.0 || v[8] < 0.0 || v[8] > 3.0 ||
        v[9] < 0.0 || v[9] > 3.0 || v[10] < 0.0 || v[10] > 2.0 ||
        v[11] < 10.0 || v[11] > 100.0) return false;
    p.grain.active = v[1] != 0.0;
    p.grain.particleAreaUm2 *= v[2]; p.grain.finalBlurPx = v[3];
    p.halation.halationAmount *= v[4]; p.halation.scatterAmount *= v[5];
    for (auto& row : p.dir.inhibition) for (auto& x : row) x *= v[6];
    p.printExposure *= std::exp2(v[7]);
    p.scanLensBlur = v[8]; p.unsharpSigma = v[9]; p.unsharpAmount = v[10];
    p.pixelUm = v[11] * 1000.0;
    return true;
}

}  // namespace filmkit::spektrafilm
