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

struct WeightedOutput {
    double* data;
    double weight;
    bool add;

    void write(Index offset, const double* values, Index count) const noexcept {
        auto* dst = data + offset;
        if (add) {
            for (Index x = 0; x < count; ++x) dst[x] += weight * values[x];
        } else {
            for (Index x = 0; x < count; ++x) dst[x] = weight * values[x];
        }
    }
};

std::array<double, 19> firWeights(double sigma, Index radius) {
    std::array<double, 19> weights{};
    double total = 0.0;
    for (Index i = -radius; i <= radius; ++i) {
        const double x = static_cast<double>(i) / sigma;
        weights[i + radius] = std::exp(-0.5 * (x * x));
        total += weights[i + radius];
    }
    for (Index i = 0; i <= 2 * radius; ++i) weights[i] /= total;
    return weights;
}

template <Index FixedRadius = 0>
void fir(const double* input, double* output, Index width, Index height,
         double sigma, Index computedRadius, std::size_t workers, WeightedOutput weighted) {
    const Index radius = FixedRadius ? FixedRadius : computedRadius;
    const auto weights = firWeights(sigma, radius);
    const Index first = std::min(radius, width);
    const Index last = std::max(first, width - radius);

    // Small-sigma filtering is vertical first, with half-sample reflection.
    parallelLines(height, workers, [&](Index begin, Index end) {
        const std::unique_ptr<double[]> rowStorage(new double[width]);
        const std::unique_ptr<double[]> filteredRow(weighted.data ? new double[width] : nullptr);
        auto* row = rowStorage.get();
        for (Index y = begin; y < end; ++y) {
            std::array<const double*, 19> rows;
            for (Index k = -radius; k <= radius; ++k) {
                rows[k + radius] = input + reflect(y + k, height) * width;
            }
            const auto sumRange = [&](Index x, Index endX, auto source, double* dest) {
#if defined(__aarch64__)
                // Equal symmetric weights share a multiply; every tap remains in FP64.
                for (; endX - x >= 8; x += 8) {
                    const auto* center = source(radius) + x;
                    auto s0 = vmulq_n_f64(vld1q_f64(center), weights[radius]);
                    auto s1 = vmulq_n_f64(vld1q_f64(center + 2), weights[radius]);
                    auto s2 = vmulq_n_f64(vld1q_f64(center + 4), weights[radius]);
                    auto s3 = vmulq_n_f64(vld1q_f64(center + 6), weights[radius]);
                    for (Index k = 0; k < radius; ++k) {
                        const auto* left = source(k) + x;
                        const auto* right = source(2 * radius - k) + x;
                        const auto step = [&](auto sum, Index offset) {
                            return vfmaq_n_f64(sum, vaddq_f64(vld1q_f64(left + offset),
                                                               vld1q_f64(right + offset)), weights[k]);
                        };
                        s0 = step(s0, 0);
                        s1 = step(s1, 2);
                        s2 = step(s2, 4);
                        s3 = step(s3, 6);
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
            auto* dst = weighted.data ? filteredRow.get() : output + y * width;
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
            if (weighted.data) weighted.write(y * width, dst, width);
        }
    });
}

template <Index FixedRadius = 0>
void unsharpRgbRows(const Triplet* input, const std::array<std::span<double>, 3>& output,
                    Index width, Index height, double sigma, Index computedRadius,
                    double amount, std::size_t workers) {
    const Index radius = FixedRadius ? FixedRadius : computedRadius;
    const auto weights = firWeights(sigma, radius);
    const Index first = std::min(radius, width);
    const Index last = std::max(first, width - radius);
    parallelLines(height, workers, [&](Index begin, Index end) {
        const auto rowStorage = std::make_unique_for_overwrite<double[]>(3 * width);
        const std::array<double*, 3> row{rowStorage.get(), rowStorage.get() + width,
                                         rowStorage.get() + 2 * width};
        for (Index y = begin; y < end; ++y) {
            std::array<const Triplet*, 19> rows;
            for (Index k = -radius; k <= radius; ++k) rows[k + radius] = input + reflect(y + k, height) * width;
            const auto* original = rows[radius];
            Index x = 0;
#if defined(__aarch64__)
            for (; x < width - width % 8; x += 2) {
                const auto center = vld3q_f64(original[x].data());
                float64x2x3_t v;
                for (int c = 0; c < 3; ++c) v.val[c] = vmulq_n_f64(center.val[c], weights[radius]);
                for (Index k = 0; k < radius; ++k) {
                    const auto lo = vld3q_f64(rows[k][x].data());
                    const auto hi = vld3q_f64(rows[2 * radius - k][x].data());
                    for (int c = 0; c < 3; ++c) v.val[c] = vfmaq_n_f64(v.val[c], vaddq_f64(lo.val[c], hi.val[c]), weights[k]);
                }
                for (int c = 0; c < 3; ++c) vst1q_f64(row[c] + x, v.val[c]);
            }
#endif
            for (; x < width; ++x) {
                Triplet sum{};
                for (Index k = 0; k <= 2 * radius; ++k) {
                    for (int c = 0; c < 3; ++c) sum[c] += rows[k][x][c] * weights[k];
                }
                for (int c = 0; c < 3; ++c) row[c][x] = sum[c];
            }
            std::array<double*, 3> dest;
            for (int c = 0; c < 3; ++c) dest[c] = output[c].data() + y * width;
            const auto scalar = [&](Index col) {
                Triplet sum{};
                for (Index k = -radius; k <= radius; ++k) {
                    const Index sample = reflect(col + k, width);
                    for (int c = 0; c < 3; ++c) sum[c] += row[c][sample] * weights[k + radius];
                }
                for (int c = 0; c < 3; ++c) dest[c][col] = original[col][c] + amount * (original[col][c] - sum[c]);
            };
            for (x = 0; x < first; ++x) scalar(x);
#if defined(__aarch64__)
            for (; x < last - (last - first) % 8; x += 2) {
                const auto source = vld3q_f64(original[x].data());
                for (int c = 0; c < 3; ++c) {
                    const auto* p = row[c] + x;
                    auto sum = vmulq_n_f64(vld1q_f64(p), weights[radius]);
                    for (Index k = 0; k < radius; ++k) {
                        sum = vfmaq_n_f64(sum, vaddq_f64(vld1q_f64(p + k - radius),
                                                          vld1q_f64(p + radius - k)), weights[k]);
                    }
                    const auto v = source.val[c];
                    vst1q_f64(dest[c] + x, vaddq_f64(v, vmulq_n_f64(vsubq_f64(v, sum), amount)));
                }
            }
#endif
            for (; x < width; ++x) scalar(x);
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
#if defined(__aarch64__)
    float64x2_t step(float64x2_t value, std::array<float64x2_t, 3>& history) const noexcept {
        // Put the newest dependency last so older taps can run ahead.
        const auto older = vfmaq_n_f64(vmulq_n_f64(history[2], c), history[1], b);
        const auto next = vfmaq_n_f64(vfmaq_n_f64(older, value, input), history[0], a);
        history = {next, history[0], history[1]};
        return next;
    }
#endif
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

#if defined(__aarch64__)
constexpr Index kBand = 16, kPairs = 4, kRows = kPairs * 2;
using ForwardRow = std::array<float64x2_t, kPairs>;

template <bool Packed>
void recursiveRows(const double* src, double* scratch, Index y, Index width, Index height,
                   const RecursiveWeights& weights, ForwardRow* forward) {
    const auto load = [&](const double* p) {
        return vld1q_lane_f64(p + width, vld1q_lane_f64(p, vdupq_n_f64(0.0), 0), 1);
    };
    std::array<std::array<float64x2_t, 3>, kPairs> state;
    for (Index p = 0; p < kPairs; ++p) state[p].fill(load(src + 2 * p * width));
    for (Index x = 0; x + 1 < width; x += 2) {
        #pragma clang loop unroll(full)
        for (Index p = 0; p < kPairs; ++p) {
            const auto* from = src + x + 2 * p * width;
            const auto upper = vld1q_f64(from), lower = vld1q_f64(from + width);
            forward[x][p] = weights.step(vzip1q_f64(upper, lower), state[p]);
            forward[x + 1][p] = weights.step(vzip2q_f64(upper, lower), state[p]);
        }
    }
    if constexpr (!Packed) {
        if (width % 2) {
            for (Index p = 0; p < kPairs; ++p) {
                forward[width - 1][p] = weights.step(load(src + width - 1 + 2 * p * width), state[p]);
            }
        }
    }
    for (Index p = 0; p < kPairs; ++p) state[p].fill(forward[width - 1][p]);
    if constexpr (!Packed) {
        if (width % 2) {
            for (Index p = 0; p < kPairs; ++p) {
                const auto v = weights.step(forward[width - 1][p], state[p]);
                auto* dst = scratch + (y + 2 * p) * width + width - 1;
                vst1q_lane_f64(dst, v, 0);
                vst1q_lane_f64(dst + width, v, 1);
            }
        }
    }
    for (Index x = width - width % 2; x > 0;) {
        x -= 2;
        #pragma clang loop unroll(full)
        for (Index p = 0; p < kPairs; ++p) {
            const auto hi = weights.step(forward[x + 1][p], state[p]);
            const auto lo = weights.step(forward[x][p], state[p]);
            auto* dst = Packed ? scratch + (x / kBand * height + y + 2 * p) * kBand + x % kBand
                               : scratch + (y + 2 * p) * width + x;
            vst1q_f64(dst, vzip1q_f64(lo, hi));
            vst1q_f64(dst + (Packed ? kBand : width), vzip2q_f64(lo, hi));
        }
    }
}

template <bool Reverse, class Write>
void recursiveBand(const double* from, double* to, Index height, Index fromStride,
                   Index toStride, const RecursiveWeights& weights, Write write) {
    std::array<std::array<float64x2_t, 3>, kBand / 2> history;
    const double* edge = from + (Reverse ? height - 1 : 0) * fromStride;
    #pragma clang loop unroll(full)
    for (Index x = 0; x < kBand; x += 2) history[x / 2].fill(vld1q_f64(edge + x));
    for (Index i = 0; i < height; ++i) {
        const Index y = Reverse ? height - 1 - i : i;
        const auto* src = from + y * fromStride;
        auto* dst = to + y * toStride;
        #pragma clang loop unroll(full)
        for (Index x = 0; x < kBand; x += 2) {
            write(dst + x, weights.step(vld1q_f64(src + x), history[x / 2]), y * kBand + x);
        }
    }
}

void recursivePair(const double* input, double* first, double* second, Index width, Index height,
                   const RecursiveWeights& a, const RecursiveWeights& b, std::size_t workers,
                   WeightedOutput weighted, double secondWeight) {
    // Two filters consume the same cached rows. Join their results per column
    // band before writing the full-resolution accumulation back to memory.
    parallelLines(height / kRows, workers, [&](Index begin, Index end) {
        const auto forward = std::make_unique_for_overwrite<ForwardRow[]>(width);
        for (Index y = begin * kRows; y < end * kRows; y += kRows) {
            recursiveRows<true>(input + y * width, first, y, width, height, a, forward.get());
            recursiveRows<true>(input + y * width, second, y, width, height, b, forward.get());
        }
    });
    parallelLines(width / kBand, workers, [&](Index begin, Index end) {
        for (Index block = begin; block < end; ++block) {
            const Index x = block * kBand;
            auto* lhs = first + x * height;
            auto* rhs = second + x * height;
            const auto store = [](double* dst, float64x2_t v, Index) { vst1q_f64(dst, v); };
            recursiveBand<false>(lhs, lhs, height, kBand, kBand, a, store);
            recursiveBand<false>(rhs, rhs, height, kBand, kBand, b, store);
            recursiveBand<true>(lhs, lhs, height, kBand, kBand, a, store);
            recursiveBand<true>(rhs, weighted.data + x, height, kBand, width, b,
                [&](double* dst, float64x2_t v, Index offset) {
                    auto sum = vmulq_n_f64(vld1q_f64(lhs + offset), weighted.weight);
                    if (weighted.add) sum = vaddq_f64(vld1q_f64(dst), sum);
                    vst1q_f64(dst, vaddq_f64(sum, vmulq_n_f64(v, secondWeight)));
                });
        }
    });
}
#endif

void recursive(const double* input, double* output, double* scratch,
               Index width, Index height, const RecursiveWeights& weights, std::size_t workers,
               WeightedOutput weighted) {
#if defined(__aarch64__)
    constexpr Index chunk = kBand;
    const bool packed = width % chunk == 0 && height % kRows == 0;
#else
    constexpr Index chunk = 32;
#endif
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
    parallelLines((height + kRows - 1) / kRows, workers, [&](Index begin, Index end) {
        const auto forward = std::make_unique_for_overwrite<ForwardRow[]>(width);
        for (Index y = begin * kRows; y < end * kRows; y += kRows) {
            if (height - y < kRows) {
                for (Index tail = y; tail < height; ++tail) rowSweep(tail);
                break;
            }
            const double* src = input + y * width;
            if (packed) recursiveRows<true>(src, scratch, y, width, height, weights, forward.get());
            else recursiveRows<false>(src, scratch, y, width, height, weights, forward.get());
        }
    });
#else
    parallelLines(height, workers, [&](Index begin, Index end) {
        for (Index y = begin; y < end; ++y) rowSweep(y);
    });
#endif
    // Join the complete row sweeps before any column reads their results.
    parallelLines(1 + (width - 1) / chunk, workers, [&](Index begin, Index end) {
        for (Index block = begin; block < end; ++block) {
            const Index x0 = block * chunk;
            const Index count = std::min(chunk, width - x0);
#if defined(__aarch64__)
            if (count == chunk) {
                const auto store = [](double* dst, float64x2_t value, Index) { vst1q_f64(dst, value); };
                const auto accumulate = [&](double* dst, float64x2_t value, Index) {
                    value = vmulq_n_f64(value, weighted.weight);
                    if (weighted.add) value = vaddq_f64(vld1q_f64(dst), value);
                    vst1q_f64(dst, value);
                };
                if (packed) {
                    auto* band = scratch + x0 * height;
                    recursiveBand<false>(band, band, height, chunk, chunk, weights, store);
                    if (weighted.data) {
                        recursiveBand<true>(band, weighted.data + x0, height, chunk, width, weights, accumulate);
                    } else {
                        recursiveBand<true>(band, output + x0, height, chunk, width, weights, store);
                    }
                } else if (weighted.data) {
                    auto* band = output + x0 * height;
                    recursiveBand<false>(scratch + x0, band, height, width, chunk, weights, store);
                    recursiveBand<true>(band, weighted.data + x0, height, chunk, width, weights, accumulate);
                } else {
                    recursiveBand<false>(scratch + x0, output + x0, height, width, width, weights, store);
                    recursiveBand<true>(output + x0, output + x0, height, width, width, weights, store);
                }
                continue;
            }
#endif
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
            if (weighted.data) {
                // The weighted path only publishes into the accumulation plane.
                // Pack each forward column band into unused output storage.
                auto* band = output + x0 * height;
                for (Index y = 0; y < height; ++y) {
                    stepRow(scratch + y * width + x0, band + y * count);
                }
                initialize(band + (height - 1) * count);
                std::array<double, chunk> row;
                for (Index y = height; y-- > 0;) {
                    stepRow(band + y * count, row.data());
                    weighted.write(y * width + x0, row.data(), count);
                }
            } else {
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
        }
    });
}

bool validPlanes(std::span<const double> input, std::span<double> output, std::span<double> scratch,
                 std::size_t width, std::size_t height, std::size_t workers) {
    constexpr auto maxIndex = static_cast<std::size_t>(std::numeric_limits<Index>::max()) / 2;
    return width && height && width <= maxIndex / height && width * height == input.size() &&
           output.size() == input.size() && scratch.size() == input.size() && workers && workers <= 8 &&
           input.data() != output.data() && input.data() != scratch.data() && output.data() != scratch.data();
}

bool finiteWeights(const RecursiveWeights& weights) {
    return std::isfinite(weights.input) && std::isfinite(weights.a) &&
           std::isfinite(weights.b) && std::isfinite(weights.c);
}

bool blur(std::span<const double> input, std::span<double> output, std::span<double> scratch,
          std::size_t width, std::size_t height, double sigma, std::size_t workers,
          WeightedOutput weighted) {
    if (!std::isfinite(sigma) || !validPlanes(input, output, scratch, width, height, workers)) return false;
    if (sigma <= 0.0 || (sigma < 3.0 && 3.0 * sigma + 0.5 < 1.0)) {
        if (weighted.data) {
            return parallelPixels(input.size(), workers, [&](auto begin, auto count) {
                weighted.write(begin, input.data() + begin, count);
                return true;
            });
        }
        std::copy(input.begin(), input.end(), output.begin());
    } else if (sigma < 3.0) {
        const auto radius = static_cast<Index>(3.0 * sigma + 0.5);
        const auto run = [&]<Index FixedRadius>() {
            fir<FixedRadius>(input.data(), output.data(), width, height, sigma, radius, workers, weighted);
        };
        // Compile the common short kernels with fixed loop bounds, retaining
        // the same weights and boundary handling.
        switch (radius) {
            case 1: run.template operator()<1>(); break;
            case 2: run.template operator()<2>(); break;
            case 3: run.template operator()<3>(); break;
            case 4: run.template operator()<4>(); break;
            default: run.template operator()<0>(); break;
        }
    } else {
        const auto weights = youngVanVliet(sigma);
        if (!finiteWeights(weights)) return false;
        recursive(input.data(), output.data(), scratch.data(), width, height, weights, workers, weighted);
    }
    return true;
}

}  // namespace

bool unsharpRgbFir(std::span<const Triplet> input, const std::array<std::span<double>, 3>& output,
                   std::size_t width, std::size_t height, double sigma, double amount,
                   std::size_t workers) {
    if (!width || !height || width > input.size() / height || input.size() != width * height ||
        !(sigma >= 1.0 / 6.0 && sigma < 3.0) || !std::isfinite(amount) || !workers || workers > 8 ||
        !std::ranges::all_of(output, [&](auto plane) { return plane.size() == input.size(); })) return false;
    const auto radius = static_cast<Index>(3.0 * sigma + 0.5);
    const auto run = [&]<Index FixedRadius>() {
        unsharpRgbRows<FixedRadius>(input.data(), output, width, height, sigma, radius, amount, workers);
    };
    switch (radius) {
        case 1: run.template operator()<1>(); break;
        case 2: run.template operator()<2>(); break;
        case 3: run.template operator()<3>(); break;
        case 4: run.template operator()<4>(); break;
        default: run.template operator()<0>(); break;
    }
    return true;
}

bool gaussianBlur(std::span<const double> input, std::span<double> output,
                  std::span<double> scratch, std::size_t width, std::size_t height,
                  double sigma, std::size_t workers) {
    return blur(input, output, scratch, width, height, sigma, workers, {nullptr, 0.0, false});
}

bool gaussianBlurWeighted(std::span<const double> input, std::span<double> output,
                          std::span<double> scratch, std::span<double> accumulation,
                          double weight, bool add, std::size_t width, std::size_t height,
                          double sigma, std::size_t workers) {
    if (accumulation.size() != input.size() || accumulation.data() == input.data() ||
        accumulation.data() == output.data() || accumulation.data() == scratch.data()) return false;
    return blur(input, output, scratch, width, height, sigma, workers, {accumulation.data(), weight, add});
}

bool gaussianBlurMixture(std::span<const double> input, std::span<double> output,
                         std::span<double> scratch, std::span<double> accumulation,
                         std::span<const GaussianTap> taps, std::size_t width, std::size_t height,
                         std::size_t workers) {
    if (taps.empty() || !validPlanes(input, output, scratch, width, height, workers) ||
        accumulation.size() != input.size() || accumulation.data() == input.data() ||
        accumulation.data() == output.data() || accumulation.data() == scratch.data()) return false;
    for (std::size_t i = 0; i < taps.size();) {
        const auto [sigma, weight] = taps[i];
#if defined(__aarch64__)
        if (width % kBand == 0 && height % kRows == 0 && i + 1 < taps.size() &&
            sigma >= 3.0 && taps[i + 1].sigma >= 3.0) {
            const auto a = youngVanVliet(sigma), b = youngVanVliet(taps[i + 1].sigma);
            if (!finiteWeights(a) || !finiteWeights(b)) return false;
            recursivePair(input.data(), scratch.data(), output.data(), width, height,
                          a, b, workers, {accumulation.data(), weight, i != 0}, taps[i + 1].weight);
            i += 2;
            continue;
        }
#endif
        if (!blur(input, output, scratch, width, height, sigma, workers,
                  {accumulation.data(), weight, i != 0})) return false;
        ++i;
    }
    return true;
}

}  // namespace filmkit::spektrafilm
