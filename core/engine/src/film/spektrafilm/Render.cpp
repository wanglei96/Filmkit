#include "Render.h"
#include "OutputGamut.h"
#include "ParallelPixels.h"
#include "SpectralLut.h"
#include "SpatialWorkspace.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <optional>

namespace filmkit::spektrafilm {
namespace {

template <class Exact, class Finish>
bool projectFrame(std::span<Triplet> pixels, const SpectralProjection& projection,
                  SpectralBackend backend, std::size_t workers, bool useSpectralLut,
                  const std::pair<Triplet, Triplet>& bounds, Exact exact, Finish finish) {
    constexpr std::size_t kSpectralLutSide = 49;
    SpectralLut lut;
    const bool ready = useSpectralLut &&
        lut.prepare(projection, backend, bounds.first, bounds.second, kSpectralLutSide, workers);
    return parallelPixels(pixels.size(), workers, [&](auto begin, auto count) {
        const auto part = pixels.subspan(begin, count);
        if (!ready) return exact(part);
        const auto resolve = [&](std::span<Triplet> run, bool fromTable) {
            if (run.empty()) return true;
            if (!fromTable) return exact(run);
            decodeLogSpectralLight(run, backend);
            for (auto& v : run) v = finish(v);
            return true;
        };
        std::size_t start = 0;
        bool fromTable = false;
        for (std::size_t i = 0; i < count; ++i) {
            Triplet logLight;
            const bool found = lut.sample(part[i], logLight);
            if (found != fromTable) {
                if (!resolve(part.subspan(start, i - start), fromTable)) return false;
                start = i;
                fromTable = found;
            }
            if (found) part[i] = logLight;
        }
        return resolve(part.subspan(start), fromTable);
    });
}

template <class Buffer>
bool renderSrgb(Buffer& image, std::size_t size, std::size_t width, std::size_t height,
                const NegativeRenderParams& p, SpectralBackend backend,
                RenderWorkers workers, std::uint64_t grainSeed,
                std::uint64_t glareSeed, RenderTimings* timings,
                const GrainReplay* grainReplay, std::span<const double> glareReplay,
                bool useSpectralLut) {
    using Clock = std::chrono::steady_clock;
    if (timings) *timings = {};
    const auto view = [&](Buffer& buffer) -> std::span<Triplet> {
        if constexpr (requires { buffer.get(); }) return {buffer.get(), size};
        else return buffer;
    };
    if (!width || !height || width > size / height || width * height != size ||
        !workers.pixels || workers.pixels > 8 || !workers.grain || workers.grain > 8 ||
        !workers.output || workers.output > 8 || !workers.filters || workers.filters > 8 ||
        !hasSpectralBackend(backend)) return false;
    RenderTimings t;
    const auto start = Clock::now();
    std::optional<ScopedPixelWorkers> pool;
    const auto maxWorkers = std::max({workers.pixels, workers.grain, workers.output, workers.filters});
    if (size >= 16384) {
        auto* current = ScopedPixelWorkers::current();
        if (!current || !current->supports(maxWorkers)) pool.emplace(maxWorkers);
    }
    auto previous = start;
    const auto mark = [&](double& field) {
        const auto now = Clock::now();
        field = std::chrono::duration<double>(now - previous).count();
        previous = now;
    };
    const auto pixels = [&] { return view(image); };
    SpatialWorkspace workspace(size);
    auto enlarger = p.enlarger;
    if (!p.directScan && p.compensatePrintExposure) {
        // Match the recipe's unboosted, unblurred midgray reference. Scene
        // exposure correction must not be compensated away by the enlarger.
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
    const double exposureEv = p.exposureEv + p.captureExposureEv;
    double exposureGain = 1.0;
    if (p.boost.ev == 0.0 && !prepareFilmExposureGain(exposureEv, p.boost, exposureGain)) return false;
    if (!parallelPixels(size, workers.pixels, [&](auto begin, auto count) {
            const auto part = pixels().subspan(begin, count);
            return projectHanatosRaw(part, part, p.xyzFromRgb, p.response, p.responseSide, exposureGain);
        })) return false;
    mark(t.hanatos);
    if ((p.boost.ev != 0.0 && !boostFilmRaw(pixels(), exposureEv, p.boost)) ||
        !finishFilmLogExposure(pixels(), width, height, p.pixelUm, p.lensBlurUm,
                               p.halation, p.exposureCorrection, workers.filters, &workspace)) return false;
    mark(t.filmExposure);
    Buffer work = [size] {
        if constexpr (requires (Buffer b) { b.get(); }) return std::make_unique_for_overwrite<Triplet[]>(size);
        else return Buffer(size);
    }();
    if (!developFilmBeforeGrain(pixels(), view(work), width, height, p.pixelUm,
                                p.filmAxis, p.filmStock, p.filmGamma,
                                p.filmType, p.dir, workers.filters, &workspace)) return false;
    image.swap(work);
    mark(t.dir);
    if (p.grain.active) {
        // Grain owns its own work planes; do not increase its peak memory use.
        workspace.release();
        if (!applyFilmGrain(pixels(), view(work), width, height, p.pixelUm, p.grainTotal, p.grainLayers,
                            p.filmType, p.grain, grainSeed, grainReplay,
                            &t.grainParts, workers.grain, workers.filters)) return false;
        image.swap(work);
    }
    Buffer{}.swap(work);
    mark(t.grain);
    std::pair<Triplet, Triplet> filmBounds{};
    if (useSpectralLut) {
        for (const auto& knot : p.filmStock) for (int c = 0; c < 3; ++c) {
            filmBounds.first[c] = std::min(filmBounds.first[c], knot[c]);
            filmBounds.second[c] = std::max(filmBounds.second[c], knot[c]);
        }
        for (int c = 0; c < 3; ++c) {
            filmBounds.first[c] = std::min(filmBounds.first[c], -p.grain.densityFloor[c]);
        }
    }
    if (!p.directScan) {
        if (!projectFrame(pixels(), enlarger, backend, workers.pixels, useSpectralLut, filmBounds,
                          [&](auto part) {
                              return exposePaperRaw(enlarger, part, part, p.printExposure, p.references, backend);
                          }, [&](Triplet raw) {
                              for (auto& v : raw) v = v * p.printExposure * p.references.exposureScale;
                              return raw;
                          })) return false;
        mark(t.paperExposure);
        if (!parallelPixels(size, workers.pixels, [&](auto begin, auto count) {
                return developPaper(pixels().subspan(begin, count), p.paperCurve);
            })) return false;
        mark(t.paperDevelopment);
    }
    if (!projectFrame(pixels(), p.scanner, backend, workers.pixels, useSpectralLut,
                      !useSpectralLut || p.directScan ? filmBounds : p.paperCurve.densityBounds(),
                      [&](auto part) {
                          return projectSpectralLight(p.scanner, part, part, backend);
                      }, [](Triplet xyz) { return xyz; })) return false;
    mark(t.scanSpectral);
    if (!finishPaperScanXYZ(p.scanner, pixels(), width, height, p.references, p.glare,
                            glareSeed, glareReplay, workers.filters, workers.pixels, &workspace)) return false;
    mark(t.scanGlare);
    Triplet white{};
    for (const auto& band : p.scanner.bands) {
        for (std::size_t c = 0; c < 3; ++c) white[c] += band.illuminant * band.response[c];
    }
    for (auto& value : white) value /= p.scanner.normalizer;
    {
        // Scan filtering overwrites all used work planes, so no clearing pass is needed.
        if (!finishScanSrgb(pixels(), width, height, white, p.scanLensBlur, p.unsharpSigma,
                            p.unsharpAmount, true, workspace.plane(0), workspace.plane(1),
                            workspace.plane(2), workers.output, workers.filters)) return false;
    }
    mark(t.scanOutput);
    pool.reset();
    t.total = std::chrono::duration<double>(Clock::now() - start).count();
    if (timings) *timings = t;
    return true;
}

}  // namespace

bool renderNegativeSrgb(std::vector<Triplet>& image, std::size_t width, std::size_t height,
                        const NegativeRenderParams& p, SpectralBackend backend, RenderWorkers workers,
                        std::uint64_t grainSeed, std::uint64_t glareSeed, RenderTimings* timings,
                        const GrainReplay* grainReplay, std::span<const double> glareReplay,
                        bool useSpectralLut) {
    return renderSrgb(image, image.size(), width, height, p, backend, workers, grainSeed, glareSeed,
                      timings, grainReplay, glareReplay, useSpectralLut);
}

bool renderNegativeSrgb(std::unique_ptr<Triplet[]>& image, std::size_t size,
                        std::size_t width, std::size_t height, const NegativeRenderParams& p,
                        SpectralBackend backend, RenderWorkers workers, std::uint64_t grainSeed,
                        std::uint64_t glareSeed, RenderTimings* timings, bool useSpectralLut) {
    return image && renderSrgb(image, size, width, height, p, backend, workers, grainSeed, glareSeed,
                               timings, nullptr, {}, useSpectralLut);
}

bool negativeNeutralResponse(const NegativeRenderParams& params, SpectralBackend backend,
                              std::span<Triplet> response, std::size_t workers) {
    if (response.size() < 2) return false;
    auto calibration = params;
    calibration.grain.active = false;
    calibration.glare.roughness = 0.0;
    return parallelPixels(response.size(), workers, [&](auto begin, auto count) {
        std::vector<Triplet> patch(1);
        for (std::size_t i = begin; i < begin + count; ++i) {
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
    }, 16);
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
