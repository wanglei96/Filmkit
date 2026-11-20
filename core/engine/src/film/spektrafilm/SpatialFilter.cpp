#include "SpatialFilter.h"
#include "ParallelPixels.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <memory>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace filmkit::spektrafilm {
namespace {

using Index = std::ptrdiff_t;

template <class Function>
void parallelLines(Index lines, std::size_t workers, const Function& run) {
    parallelPixels(static_cast<std::size_t>(lines), workers, [&](auto begin, auto count) {
        run(static_cast<Index>(begin), static_cast<Index>(begin + count));
        return true;
    }, 4);
}

Index reflect(Index i, Index length) noexcept {
    if (0 <= i && i < length) return i;
    const Index period = 2 * length;
    i %= period;
    if (i < 0) i += period;
    return i < length ? i : period - 1 - i;
}

template <Index FixedRadius = 0>
void fir(const double* input, double* output, Index width, Index height,
         double sigma, Index computedRadius, std::size_t workers) {
    const Index radius = FixedRadius ? FixedRadius : computedRadius;
    std::array<double, 19> weights{};
    double total = 0.0;
    for (Index i = -radius; i <= radius; ++i) {
        const double x = static_cast<double>(i) / sigma;
        weights[i + radius] = std::exp(-0.5 * (x * x));
        total += weights[i + radius];
    }
    for (Index i = 0; i <= 2 * radius; ++i) weights[i] /= total;
    const Index first = std::min(radius, width);
    const Index last = std::max(first, width - radius);

    // Small-sigma filtering is vertical first, with half-sample reflection.
    parallelLines(height, workers, [&](Index begin, Index end) {
        const std::unique_ptr<double[]> rowStorage(new double[width]);
        auto* row = rowStorage.get();
        for (Index y = begin; y < end; ++y) {
            std::array<const double*, 19> rows;
            for (Index k = -radius; k <= radius; ++k) {
                rows[k + radius] = input + reflect(y + k, height) * width;
            }
            const auto sumRange = [&](Index x, Index endX, auto source, double* dest) {
#if defined(__aarch64__)
                // Independent pixels stay in registers for all taps; tap order is unchanged.
                for (; endX - x >= 8; x += 8) {
                    auto s0 = vdupq_n_f64(0.0), s1 = s0, s2 = s0, s3 = s0;
                    for (Index k = 0; k <= 2 * radius; ++k) {
                        const auto* p = source(k) + x;
                        s0 = vaddq_f64(s0, vmulq_n_f64(vld1q_f64(p), weights[k]));
                        s1 = vaddq_f64(s1, vmulq_n_f64(vld1q_f64(p + 2), weights[k]));
                        s2 = vaddq_f64(s2, vmulq_n_f64(vld1q_f64(p + 4), weights[k]));
                        s3 = vaddq_f64(s3, vmulq_n_f64(vld1q_f64(p + 6), weights[k]));
                    }
                    vst1q_f64(dest + x, s0);
                    vst1q_f64(dest + x + 2, s1);
                    vst1q_f64(dest + x + 4, s2);
                    vst1q_f64(dest + x + 6, s3);
                }
#endif
                for (; x < endX; ++x) {
                    double sum = 0.0;
                    for (Index k = 0; k <= 2 * radius; ++k) sum += source(k)[x] * weights[k];
                    dest[x] = sum;
                }
            };
            sumRange(0, width, [&](Index k) { return rows[k]; }, row);
            auto* dst = output + y * width;
            const auto edge = [&](Index x) {
                double sum = 0.0;
                for (Index k = -radius; k <= radius; ++k) {
                    sum += row[reflect(x + k, width)] * weights[k + radius];
                }
                dst[x] = sum;
            };
            for (Index x = 0; x < first; ++x) edge(x);
            sumRange(0, last - first, [&](Index k) { return row + (first - radius) + k; }, dst + first);
            for (Index x = last; x < width; ++x) edge(x);
        }
    });
}

struct RecursiveWeights {
    double input, a, b, c;

    template <class Sample>
    Sample step(Sample value, std::array<Sample, 3>& history) const noexcept {
        const Sample next = input * value + a * history[0] + b * history[1] + c * history[2];
        history = {next, history[0], history[1]};
        return next;
    }
};

RecursiveWeights youngVanVliet(double sigma) noexcept {
    const double q = 0.98711 * sigma - 0.96330;
    const double q2 = q * q;
    const double q3 = q2 * q;
    const double normalizer = 1.57825 + 2.44413 * q + 1.4281 * q2 + 0.422205 * q3;
    const double a = 2.44413 * q + 2.85619 * q2 + 1.26661 * q3;
    const double b = -(1.4281 * q2 + 1.26661 * q3);
    const double c = 0.422205 * q3;
    return {1.0 - (a + b + c) / normalizer, a / normalizer, b / normalizer, c / normalizer};
}

void recursive(const double* input, double* output, double* scratch,
               Index width, Index height, const RecursiveWeights& weights, std::size_t workers) {
    // Large-sigma filtering sweeps complete rows, then complete columns.
    // Each reverse sweep starts from the last forward result, not the input edge.
    const auto rowSweep = [&](Index y) {
        const auto* src = input + y * width;
        auto* row = scratch + y * width;
        std::array<double, 3> state;
        state.fill(src[0]);
        for (Index x = 0; x < width; ++x) row[x] = weights.step(src[x], state);
        state.fill(row[width - 1]);
        for (Index x = width; x-- > 0;) row[x] = weights.step(row[x], state);
    };
#if defined(__aarch64__)
    // Interleave independent row pairs to hide the recurrence latency. Each
    // row keeps its original arithmetic and edge conditions in one FP64 lane.
    constexpr Index pairs = 4, rows = pairs * 2;
    parallelLines((height + rows - 1) / rows, workers, [&](Index begin, Index end) {
        for (Index y = begin * rows; y < end * rows; y += rows) {
            if (height - y < rows) {
                for (Index tail = y; tail < height; ++tail) rowSweep(tail);
                break;
            }
            const double* src = input + y * width;
            double* row = scratch + y * width;
            const auto load = [&](const double* p) {
                return vld1q_lane_f64(p + width, vld1q_lane_f64(p, vdupq_n_f64(0.0), 0), 1);
            };
            const auto store = [&](double* p, float64x2_t v) {
                vst1q_lane_f64(p, v, 0);
                vst1q_lane_f64(p + width, v, 1);
            };
            std::array<std::array<float64x2_t, 3>, pairs> state;
            for (Index p = 0; p < pairs; ++p) state[p].fill(load(src + 2 * p * width));
            const auto step = [&](const double* from, double* to) {
                for (Index p = 0; p < pairs; ++p) {
                    const Index offset = 2 * p * width;
                    store(to + offset, weights.step(load(from + offset), state[p]));
                }
            };
            for (Index x = 0; x < width; ++x) step(src + x, row + x);
            for (Index p = 0; p < pairs; ++p) state[p].fill(load(row + 2 * p * width + width - 1));
            for (Index x = width; x-- > 0;) step(row + x, row + x);
        }
    });
#else
    parallelLines(height, workers, [&](Index begin, Index end) {
        for (Index y = begin; y < end; ++y) rowSweep(y);
    });
#endif
    constexpr Index chunk = 32;
    // Join the complete row sweeps before any column reads their results.
    parallelLines(1 + (width - 1) / chunk, workers, [&](Index begin, Index end) {
        for (Index block = begin; block < end; ++block) {
            const Index x0 = block * chunk;
            const Index count = std::min(chunk, width - x0);
            // Keep each recurrence tap contiguous so independent columns vectorize.
            std::array<double, chunk> newest, middle, oldest;
            const auto initialize = [&](const double* edge) {
                std::copy_n(edge, count, newest.data());
                std::copy_n(edge, count, middle.data());
                std::copy_n(edge, count, oldest.data());
            };
            const auto stepRow = [&](const double* src, double* dst) {
                for (Index x = 0; x < count; ++x) {
                    const double next = weights.input * src[x] + weights.a * newest[x] +
                                        weights.b * middle[x] + weights.c * oldest[x];
                    oldest[x] = middle[x];
                    middle[x] = newest[x];
                    newest[x] = dst[x] = next;
                }
            };
            initialize(scratch + x0);
            for (Index y = 0; y < height; ++y) {
                const Index base = y * width + x0;
                stepRow(scratch + base, output + base);
            }
            initialize(output + (height - 1) * width + x0);
            for (Index y = height; y-- > 0;) {
                const Index base = y * width + x0;
                stepRow(output + base, output + base);
            }
        }
    });
}

}  // namespace

bool gaussianBlur(std::span<const double> input, std::span<double> output,
                  std::span<double> scratch, std::size_t width, std::size_t height,
                  double sigma, std::size_t workers) {
    constexpr auto maxIndex = static_cast<std::size_t>(std::numeric_limits<Index>::max()) / 2;
    if (width == 0 || height == 0 || width > maxIndex / height ||
        width * height != input.size() || output.size() != input.size() ||
        scratch.size() != input.size() || !std::isfinite(sigma) || !workers || workers > 8 ||
        input.data() == output.data() || input.data() == scratch.data() || output.data() == scratch.data()) {
        return false;
    }
    if (sigma <= 0.0 || (sigma < 3.0 && 3.0 * sigma + 0.5 < 1.0)) {
        std::copy(input.begin(), input.end(), output.begin());
    } else if (sigma < 3.0) {
        const auto radius = static_cast<Index>(3.0 * sigma + 0.5);
        const auto run = [&]<Index FixedRadius>() {
            fir<FixedRadius>(input.data(), output.data(), width, height, sigma, radius, workers);
        };
        // Compile the common short kernels with fixed loop bounds, retaining
        // the same weights, boundary handling and accumulation order.
        switch (radius) {
            case 1: run.template operator()<1>(); break;
            case 2: run.template operator()<2>(); break;
            case 3: run.template operator()<3>(); break;
            case 4: run.template operator()<4>(); break;
            default: run.template operator()<0>(); break;
        }
    } else {
        const auto weights = youngVanVliet(sigma);
        if (!std::isfinite(weights.input) || !std::isfinite(weights.a) ||
            !std::isfinite(weights.b) || !std::isfinite(weights.c)) return false;
        recursive(input.data(), output.data(), scratch.data(), width, height, weights, workers);
    }
    return true;
}

}  // namespace filmkit::spektrafilm
