#include "SpatialFilter.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <future>
#include <limits>

namespace filmkit::spektrafilm {
namespace {

using Index = std::ptrdiff_t;

template <class Function>
void parallelLines(Index lines, std::size_t workers, const Function& run) {
    workers = std::min(workers, static_cast<std::size_t>(lines));
    const auto range = [&](std::size_t worker) {
        run(lines * static_cast<Index>(worker) / static_cast<Index>(workers),
            lines * static_cast<Index>(worker + 1) / static_cast<Index>(workers));
    };
    std::array<std::future<void>, 7> pending;
    for (std::size_t i = 1; i < workers; ++i) pending[i - 1] = std::async(std::launch::async, range, i);
    range(0);
    for (std::size_t i = 1; i < workers; ++i) pending[i - 1].get();
}

Index reflect(Index i, Index length) noexcept {
    if (0 <= i && i < length) return i;
    const Index period = 2 * length;
    i %= period;
    if (i < 0) i += period;
    return i < length ? i : period - 1 - i;
}

void fir(const double* input, double* output, double* scratch,
         Index width, Index height, double sigma, std::size_t workers) {
    const auto radius = static_cast<Index>(3.0 * sigma + 0.5);
    std::array<double, 19> weights{};
    double total = 0.0;
    for (Index i = -radius; i <= radius; ++i) {
        const double x = static_cast<double>(i) / sigma;
        weights[i + radius] = std::exp(-0.5 * (x * x));
        total += weights[i + radius];
    }
    for (Index i = 0; i <= 2 * radius; ++i) weights[i] /= total;

    // Small-sigma filtering is vertical first, with half-sample reflection.
    parallelLines(height, workers, [&](Index begin, Index end) {
        for (Index y = begin; y < end; ++y) {
            auto* row = scratch + y * width;
            std::fill_n(row, width, 0.0);
            for (Index k = -radius; k <= radius; ++k) {
                const auto* src = input + reflect(y + k, height) * width;
                const double weight = weights[k + radius];
                for (Index x = 0; x < width; ++x) row[x] += src[x] * weight;
            }
            for (Index x = 0; x < width; ++x) {
                double sum = 0.0;
                for (Index k = -radius; k <= radius; ++k) {
                    sum += row[reflect(x + k, width)] * weights[k + radius];
                }
                output[y * width + x] = sum;
            }
        }
    });
}

struct RecursiveWeights {
    double input, a, b, c;

    double step(double value, std::array<double, 3>& history) const noexcept {
        const double next = input * value + a * history[0] + b * history[1] + c * history[2];
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
    parallelLines(height, workers, [&](Index begin, Index end) {
        for (Index y = begin; y < end; ++y) {
            const auto* src = input + y * width;
            auto* row = scratch + y * width;
            std::array<double, 3> state;
            state.fill(src[0]);
            for (Index x = 0; x < width; ++x) row[x] = weights.step(src[x], state);
            state.fill(row[width - 1]);
            for (Index x = width; x-- > 0;) row[x] = weights.step(row[x], state);
        }
    });
    constexpr Index chunk = 32;
    // Join the complete row sweeps before any column reads their results.
    parallelLines(1 + (width - 1) / chunk, workers, [&](Index begin, Index end) {
        for (Index block = begin; block < end; ++block) {
            const Index x0 = block * chunk;
            const Index count = std::min(chunk, width - x0);
            std::array<std::array<double, 3>, chunk> state;
            for (Index x = 0; x < count; ++x) state[x].fill(scratch[x0 + x]);
            for (Index y = 0; y < height; ++y) {
                const Index base = y * width + x0;
                for (Index x = 0; x < count; ++x) output[base + x] = weights.step(scratch[base + x], state[x]);
            }
            const Index last = (height - 1) * width + x0;
            for (Index x = 0; x < count; ++x) state[x].fill(output[last + x]);
            for (Index y = height; y-- > 0;) {
                const Index base = y * width + x0;
                for (Index x = 0; x < count; ++x) output[base + x] = weights.step(output[base + x], state[x]);
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
        fir(input.data(), output.data(), scratch.data(), width, height, sigma, workers);
    } else {
        const auto weights = youngVanVliet(sigma);
        if (!std::isfinite(weights.input) || !std::isfinite(weights.a) ||
            !std::isfinite(weights.b) || !std::isfinite(weights.c)) return false;
        recursive(input.data(), output.data(), scratch.data(), width, height, weights, workers);
    }
    return true;
}

}  // namespace filmkit::spektrafilm
