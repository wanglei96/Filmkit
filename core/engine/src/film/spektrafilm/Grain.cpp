#include "Grain.h"
#include "SpatialFilter.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <future>
#include <limits>
#include <random>

namespace filmkit::spektrafilm {
namespace {

struct ParticleLayer {
    double floor, maxDensity, population;
};

struct LayerKnot {
    double x, inverseStep;
    Triplet density;
};

double interpolate(std::span<const LayerKnot> knots, double x, std::size_t layer) {
    if (x <= knots.front().x) return knots.front().density[layer];
    if (x >= knots.back().x) return knots.back().density[layer];
    // Measured stock curves can locally reverse. Keep the reference's scalar
    // searchsorted(right) decisions without requiring a partitioned range.
    std::size_t lo = 0, hi = knots.size();
    while (lo < hi) {
        const std::size_t mid = lo + (hi - lo) / 2;
        if (x < knots[mid].x) hi = mid;
        else lo = mid + 1;
    }
    const auto& upper = knots[lo];
    const auto& lower = knots[lo - 1];
    const double t = (x - lower.x) * lower.inverseStep;
    return lower.density[layer] + t * (upper.density[layer] - lower.density[layer]);
}

bool positiveFinite(double value) {
    return value > 0.0 && std::isfinite(value);
}

}  // namespace

bool applyFilmGrain(std::span<const Triplet> input, std::span<Triplet> output,
                    std::size_t width, std::size_t height, double pixelUm,
                    std::span<const Triplet> totalCurve, std::span<const GrainLayers> layers,
                    EmulsionType type, const GrainParams& p, std::uint64_t seed,
                    const GrainReplay* replay, GrainTimings* timings, std::size_t samplingWorkers,
                    std::size_t filterWorkers) {
    using Clock = std::chrono::steady_clock;
    const auto elapsed = [](Clock::time_point start) {
        return std::chrono::duration<double>(Clock::now() - start).count();
    };
    if (timings) *timings = {};
    if (!height || !width || width > input.size() / height || width * height != input.size() ||
        output.size() != input.size() || input.data() == output.data()) return false;
    if (!p.active) {
        std::copy(input.begin(), input.end(), output.begin());
        return true;
    }
    if (!samplingWorkers || samplingWorkers > 8 || !positiveFinite(pixelUm) ||
        !positiveFinite(p.particleAreaUm2) || totalCurve.empty() ||
        (p.layered ? layers.size() != totalCurve.size() || totalCurve.size() < 2 : !p.singleModelLayers) ||
        !std::isfinite(p.finalBlurPx) || !std::isfinite(p.dyeCloudBlur) ||
        !std::isfinite(p.microBlurUm) || !std::isfinite(p.microDeviationNm)) return false;
    const double deviation = p.microDeviationNm * 0.001 / pixelUm;
    const double microBlurPx = p.microBlurUm / pixelUm;
    const bool useClumping = p.layered && deviation > 0.05;
    if (!std::isfinite(deviation) || !std::isfinite(microBlurPx) ||
        (replay && (!replay->developed || (useClumping && !replay->clumping)))) return false;
    for (const auto& pixel : input) {
        for (double v : pixel) if (!std::isfinite(v)) return false;
    }

    const auto n = input.size();
    const auto count = p.layered ? 3 : p.singleModelLayers;
    const bool finalBlur = p.finalBlurPx > (p.layered ? 0.0 : 0.4);
    const bool spatial = finalBlur || (p.layered && p.dyeCloudBlur > 0.0) ||
                         (useClumping && microBlurPx > 0.4);
    std::vector<double> plane(n), filtered(spatial ? n : 0), scratch(spatial ? n : 0);
    const double sign = type == EmulsionType::Positive ? -1.0 : 1.0;
    const double pixelArea = pixelUm * pixelUm;
    const double logVariance = useClumping ? std::log1p(deviation * deviation) : 0.0;
    if (!positiveFinite(pixelArea) || !std::isfinite(logVariance)) return false;
    std::normal_distribution<double> normal;
    std::mt19937_64 microRng(seed);
    std::vector<LayerKnot> knots(p.layered ? totalCurve.size() : 0);
    constexpr std::size_t blockSize = 32768;
    const std::size_t blocks = 1 + (n - 1) / blockSize;
    const std::size_t workers = replay ? 1 : std::min(samplingWorkers, blocks);

    for (std::size_t c = 0; c < 3; ++c) {
        const double area = p.particleAreaUm2 * p.particleScale[c];
        if (!positiveFinite(area) || !std::isfinite(p.densityFloor[c]) ||
            !(p.uniformity[c] >= 0.0 && p.uniformity[c] <= 1.0)) return false;
        std::array<ParticleLayer, 3> particles;
        if (p.layered) {
            Triplet maximum;
            maximum.fill(-std::numeric_limits<double>::infinity());
            for (std::size_t k = 0; k < knots.size(); ++k) {
                const double x = sign * totalCurve[k][c];
                if (!std::isfinite(x)) return false;
                knots[k] = {x, 0.0, {layers[k][0][c], layers[k][1][c], layers[k][2][c]}};
                if (k && x != knots[k - 1].x) knots[k - 1].inverseStep = 1.0 / (x - knots[k - 1].x);
                for (std::size_t s = 0; s < 3; ++s) {
                    if (!std::isfinite(knots[k].density[s])) return false;
                    maximum[s] = std::max(maximum[s], knots[k].density[s]);
                }
            }
            if (knots.front().x > knots.back().x) return false;
            const double total = maximum[0] + maximum[1] + maximum[2];
            if (!positiveFinite(total)) return false;
            for (std::size_t s = 0; s < 3; ++s) {
                const double fraction = maximum[s] / total;
                const double floor = fraction * p.densityFloor[c];
                const double layerArea = area * p.layerScale[s];
                if (!positiveFinite(layerArea)) return false;
                particles[s] = {floor, maximum[s] + floor, pixelArea * fraction / layerArea};
            }
        } else {
            double maximum = -std::numeric_limits<double>::infinity();
            for (const auto& knot : totalCurve) maximum = std::fmax(maximum, knot[c]);
            particles[0] = {p.densityFloor[c], maximum + p.densityFloor[c],
                            pixelArea / area / static_cast<double>(count)};
        }
        for (std::size_t s = 0; s < count; ++s) {
            const auto spec = particles[p.layered ? s : 0];
            if (!positiveFinite(spec.maxDensity) || !positiveFinite(spec.population) ||
                spec.population >= static_cast<double>(std::numeric_limits<std::int64_t>::max()) * 1e-6) return false;
            const double od = spec.maxDensity / spec.population;
            if (!positiveFinite(od)) return false;
            const auto particlesStarted = timings ? Clock::now() : Clock::time_point{};
            // Streams belong to fixed pixel blocks, not workers. Scheduling
            // cannot move random draws or change the layer accumulation order.
            const auto sampleBlocks = [&](std::size_t worker) {
                for (std::size_t block = worker; block < blocks; block += workers) {
                    std::seed_seq stream{static_cast<std::uint32_t>(seed), static_cast<std::uint32_t>(seed >> 32),
                                         static_cast<std::uint32_t>(c), static_cast<std::uint32_t>(s),
                                         static_cast<std::uint32_t>(block), 1U};
                    std::mt19937_64 rng(stream);
                    std::poisson_distribution<std::uint64_t> poisson;
                    const auto start = block * blockSize;
                    const auto stop = start + std::min(blockSize, n - start);
                    for (std::size_t i = start; i < stop; ++i) {
                        const double d = p.layered ? interpolate(knots, sign * input[i][c], s) : input[i][c];
                        const double probability = std::clamp((d + spec.floor) / spec.maxDensity, 1e-6, 1.0 - 1e-6);
                        const double saturation = 1.0 - probability * p.uniformity[c] * (1.0 - 1e-6);
                        const double mean = spec.population / saturation;
                        // Exact Poisson thinning: only developed particles are consumed.
                        // Keep the two original parameters for upstream draw replay.
                        const auto developed = replay
                            ? replay->developed(replay->context, c, s, i, mean, probability)
                            : poisson(rng, decltype(poisson)::param_type(mean * probability));
                        plane[i] = static_cast<double>(developed) * od * saturation;
                    }
                }
            };
            std::array<std::future<void>, 7> pending;
            for (std::size_t worker = 1; worker < workers; ++worker) {
                pending[worker - 1] = std::async(std::launch::async, sampleBlocks, worker);
            }
            sampleBlocks(0);
            for (std::size_t worker = 1; worker < workers; ++worker) pending[worker - 1].get();
            if (timings) timings->particlesSeconds += elapsed(particlesStarted);
            const auto dyeStarted = timings ? Clock::now() : Clock::time_point{};
            const std::vector<double>* dye = &plane;
            if (p.layered && p.dyeCloudBlur > 0.0) {
                if (!gaussianBlur(plane, filtered, scratch, width, height, p.dyeCloudBlur * std::sqrt(od), filterWorkers)) return false;
                dye = &filtered;
            }
            for (std::size_t i = 0; i < n; ++i) output[i][c] = (s ? output[i][c] : 0.0) + (*dye)[i];
            if (timings) timings->dyeCloudSeconds += elapsed(dyeStarted);
        }
    }

    // Stream the independent lognormal field one channel at a time. Sample
    // order differs from upstream, but the channel-local filtering does not.
    const auto finishStarted = timings ? Clock::now() : Clock::time_point{};
    const double logDeviation = std::sqrt(logVariance);
    for (std::size_t c = 0; c < 3; ++c) {
        if (useClumping) {
            for (std::size_t i = 0; i < n; ++i) {
                plane[i] = replay ? replay->clumping(replay->context, c, i, deviation)
                                  : std::exp(-0.5 * logVariance + logDeviation * normal(microRng));
            }
            const std::vector<double>* field = &plane;
            if (microBlurPx > 0.4) {
                if (!gaussianBlur(plane, filtered, scratch, width, height, microBlurPx, filterWorkers)) return false;
                field = &filtered;
            }
            for (std::size_t i = 0; i < n; ++i) output[i][c] *= (*field)[i];
        }
        for (std::size_t i = 0; i < n; ++i) {
            const double sum = output[i][c];
            output[i][c] = (p.layered ? sum : sum / static_cast<double>(count)) - p.densityFloor[c];
            if (finalBlur) plane[i] = output[i][c];
        }
        if (finalBlur) {
            if (!gaussianBlur(plane, filtered, scratch, width, height, p.finalBlurPx, filterWorkers)) return false;
            for (std::size_t i = 0; i < n; ++i) output[i][c] = filtered[i];
        }
    }
    if (timings) timings->finishSeconds = elapsed(finishStarted);
    return true;
}

}  // namespace filmkit::spektrafilm
