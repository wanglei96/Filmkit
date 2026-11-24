#include "film/spektrafilm/SpatialFilter.h"
#include "film/spektrafilm/ParallelPixels.h"

#include <array>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

using namespace filmkit::spektrafilm;

namespace {

std::vector<double> scalarFir(const std::vector<double>& input, std::ptrdiff_t w,
                              std::ptrdiff_t h, double sigma) {
    const auto radius = static_cast<std::ptrdiff_t>(3.0 * sigma + 0.5);
    std::vector<double> weights(2 * radius + 1);
    double total = 0.0;
    for (auto k = -radius; k <= radius; ++k) {
        const double x = k / sigma;
        total += weights[k + radius] = std::exp(-0.5 * (x * x));
    }
    for (auto& weight : weights) weight /= total;
    const auto reflect = [](std::ptrdiff_t x, std::ptrdiff_t size) {
        while (x < 0 || x >= size) x = x < 0 ? -1 - x : 2 * size - 1 - x;
        return x;
    };
    std::vector<double> vertical(input.size()), output(input.size());
    for (std::ptrdiff_t y = 0; y < h; ++y) for (std::ptrdiff_t x = 0; x < w; ++x) {
        for (auto k = -radius; k <= radius; ++k) {
            vertical[y * w + x] += input[reflect(y + k, h) * w + x] * weights[k + radius];
        }
    }
    for (std::ptrdiff_t y = 0; y < h; ++y) for (std::ptrdiff_t x = 0; x < w; ++x) {
        for (auto k = -radius; k <= radius; ++k) {
            output[y * w + x] += vertical[y * w + reflect(x + k, w)] * weights[k + radius];
        }
    }
    return output;
}

std::vector<double> scalarRecursive(std::vector<double> pixels, std::size_t w,
                                   std::size_t h, double sigma) {
    const double q = 0.98711 * sigma - 0.96330;
    const double q2 = q * q, q3 = q2 * q;
    const double norm = 1.57825 + 2.44413 * q + 1.4281 * q2 + 0.422205 * q3;
    const double a = 2.44413 * q + 2.85619 * q2 + 1.26661 * q3;
    const double b = -(1.4281 * q2 + 1.26661 * q3), c = 0.422205 * q3;
    const double input = 1.0 - (a + b + c) / norm;
    for (bool vertical : {false, true}) {
        const std::size_t lines = vertical ? w : h, length = vertical ? h : w;
        for (std::size_t line = 0; line < lines; ++line) {
            const auto at = [&](std::size_t i) -> double& {
                return vertical ? pixels[i * w + line] : pixels[line * w + i];
            };
            const auto sweep = [&](bool reverse) {
                double x = at(reverse ? length - 1 : 0), y = x, z = x;
                for (std::size_t k = 0; k < length; ++k) {
                    auto& value = at(reverse ? length - 1 - k : k);
                    value = input * value + (a / norm) * x + (b / norm) * y + (c / norm) * z;
                    z = y; y = x; x = value;
                }
            };
            sweep(false);
            sweep(true);
        }
    }
    return pixels;
}

}  // namespace

int main() {
    {
        ScopedPixelWorkers workers(8);
        const auto noop = [](auto, auto) { return true; };
        for (std::size_t n : {8, 2, 4, 8}) {
            std::vector<std::atomic<unsigned>> visits(1027);
            if (!parallelPixelIndices(visits.size(), n, [&](auto i) { ++visits[i]; })) return 1;
            for (const auto& visit : visits) if (visit.load() != 1) return 1;
        }
        if (parallelPixels(8, 8, [](auto begin, auto) { return begin != 1; })) return 1;
        try {
            parallelPixels(8, 8, [](auto begin, auto) {
                if (begin == 1 || begin == 4) throw std::runtime_error("worker failure");
                return true;
            });
            return 1;
        } catch (const std::runtime_error&) {
            if (!parallelPixels(8, 8, [&](auto, auto) { return parallelPixels(2, 2, noop); })) return 1;
        }
    }
    for (std::size_t count : {0, 1, 31, 131071, 131073}) {
        for (std::size_t workers : {1, 4, 8}) {
            for (std::size_t block : {4, 16384}) {
                std::vector<std::atomic<unsigned>> visits(count);
                if (!parallelPixels(count, workers, [&](auto begin, auto length) {
                    if (begin > count || length > count - begin) return false;
                    for (auto i = begin; i < begin + length; ++i) ++visits[i];
                    return true;
                }, block)) return 1;
                for (const auto& value : visits) if (value.load() != 1) return 1;
                if (count && parallelPixels(count, workers, [&](auto begin, auto length) {
                    return !(begin <= count / 2 && count / 2 - begin < length);
                }, block)) return 1;
            }
        }
    }
    for (const auto [w, h] : {std::pair{1, 7}, {5, 3}, {9, 11}, {22, 1}, {35, 37}, {192, 96}}) {
        const auto n = static_cast<std::size_t>(w) * h;
        std::vector<Triplet> rgb(n);
        std::vector<double> source(n), reference(n), scratch(n);
        std::array<std::vector<double>, 3> result;
        for (auto& channel : result) channel.resize(n);
        for (std::size_t i = 0; i < n; ++i) {
            rgb[i] = {std::sin(i * .043), std::cos(i * .023), double(i % 127) / 63.0};
        }
        for (double sigma : {1.0 / 6.0, 0.7, 0.94, 1.45, 1.87, 2.99}) {
            constexpr double amount = 0.7;
            if (!unsharpRgbFir(rgb, {result[0], result[1], result[2]}, w, h, sigma, amount, 4)) return 1;
            for (std::size_t c = 0; c < 3; ++c) {
                for (std::size_t i = 0; i < n; ++i) source[i] = rgb[i][c];
                if (!gaussianBlur(source, reference, scratch, w, h, sigma, 4)) return 1;
                for (std::size_t i = 0; i < n; ++i) {
                    const double expected = source[i] + amount * (source[i] - reference[i]);
                    if (std::bit_cast<std::uint64_t>(result[c][i]) != std::bit_cast<std::uint64_t>(expected)) {
                        std::cerr << "RGB unsharp mismatch " << w << 'x' << h << " sigma=" << sigma
                                  << " channel=" << c << " pixel=" << i << '\n';
                        return 1;
                    }
                }
            }
        }
    }
    const auto noop = [](auto, auto) { return true; };
    if (parallelPixels(100, 0, noop) || parallelPixels(100, 9, noop) ||
        parallelPixels(100, 4, noop, 0)) return 1;
    const std::array<std::array<std::size_t, 2>, 9> sizes{{
        {1, 1}, {3, 5}, {16, 8}, {32, 24}, {35, 17}, {151, 129}, {256, 128}, {257, 65}, {65, 257}}};
    constexpr std::array sigmas{0.0, 0.16, 0.3, 0.5, 1.0, 1.3, 2.33143, 2.999,
                                3.0, 12.496, 35.522, 64.543, 226.0, 454.0};
    std::size_t checked = 0, weightedChecked = 0, mixtureChecked = 0;
    double worst = 0.0;
    for (const auto& [w, h] : sizes) {
        std::vector<double> input(w * h), reference(w * h), output(w * h), scratch(w * h);
        std::vector<double> accumulation(w * h);
        for (std::size_t i = 0; i < input.size(); ++i) {
            input[i] = 0.5 * std::sin(static_cast<double>(i) * 0.17) + static_cast<double>(i % 73) * 0.03;
        }
        const std::array<std::array<GaussianTap, 4>, 3> mixtures{{
            {{{12.496, 0.1633}, {35.522, 0.6496}, {64.543, 0.1870}, {2.33143, 0.17}}},
            {{{0.16, 0.1}, {0.5, 0.2}, {226.0, 0.3}, {454.0, 0.4}}},
            {{{3.0, 0.25}, {12.496, -0.5}, {226.0, 0.5}, {454.0, 0.75}}},
        }};
        for (const auto& taps : mixtures) {
            for (std::size_t i = 0; i < taps.size(); ++i) {
                if (!gaussianBlurWeighted(input, output, scratch, reference, taps[i].weight,
                                          i != 0, w, h, taps[i].sigma, 1)) return 2;
            }
            for (std::size_t workers : {1, 4, 8}) {
                std::fill(accumulation.begin(), accumulation.end(), std::numeric_limits<double>::quiet_NaN());
                if (!gaussianBlurMixture(input, output, scratch, accumulation, taps, w, h, workers)) return 2;
                for (std::size_t i = 0; i < input.size(); ++i) {
                    if (std::bit_cast<std::uint64_t>(accumulation[i]) != std::bit_cast<std::uint64_t>(reference[i])) {
                        std::cerr << "mixture mismatch " << w << 'x' << h << " workers=" << workers
                                  << " pixel=" << i << '\n';
                        return 1;
                    }
                    ++mixtureChecked;
                }
            }
        }
        input.front() = 8.0;
        input.back() = -0.25;
        for (double sigma : sigmas) {
            if (sigma >= 3) reference = scalarRecursive(input, w, h, sigma);
            else if (sigma > 0 && 3 * sigma + 0.5 >= 1) reference = scalarFir(input, w, h, sigma);
            else reference = input;
            const auto matches = [&](double actual, double expected) {
                if (!std::isfinite(actual)) return false;
                worst = std::max(worst, std::abs(actual - expected));
#if defined(__aarch64__)
                // FP64 FMA changes roundoff, amplified by the long IIR poles.
                if (sigma >= 3) return std::abs(actual - expected) <= 5e-9;
                if (sigma < 3 && 3 * sigma + 0.5 >= 1) return std::abs(actual - expected) <= 8e-15;
#endif
                return std::bit_cast<std::uint64_t>(actual) == std::bit_cast<std::uint64_t>(expected);
            };
            std::vector<double> singleWorker;
            for (std::size_t workers : {1, 4, 8}) {
                if (!gaussianBlur(input, output, scratch, w, h, sigma, workers)) return 2;
                if (workers == 1) singleWorker = output;
                else if (singleWorker != output) return 1;
                for (std::size_t i = 0; i < output.size(); ++i) {
                    if (!matches(output[i], reference[i])) {
                        std::cerr << "filter mismatch " << w << 'x' << h << " sigma=" << sigma
                                  << " workers=" << workers << " pixel=" << i << '\n';
                        return 1;
                    }
                    ++checked;
                }
                for (bool add : {false, true}) {
                    constexpr double weight = 0.6496;
                    if (!add) std::fill(accumulation.begin(), accumulation.end(),
                                        std::numeric_limits<double>::quiet_NaN());
                    if (!gaussianBlurWeighted(input, output, scratch, accumulation,
                                              weight, add, w, h, sigma, workers)) return 2;
                    for (std::size_t i = 0; i < output.size(); ++i) {
                        const double tap = weight * reference[i];
                        const double expected = add ? tap + tap : tap;
                        if (!matches(accumulation[i], expected)) {
                            std::cerr << "weighted filter mismatch " << w << 'x' << h << " sigma=" << sigma
                                      << " workers=" << workers << " add=" << add << " pixel=" << i << '\n';
                            return 1;
                        }
                        ++weightedChecked;
                    }
                }
            }
        }
        if (gaussianBlur(input, output, scratch, w, h, 1.0, 0) ||
            gaussianBlur(input, output, scratch, w, h, 1.0, 9) ||
            gaussianBlurWeighted(input, output, scratch, input, 0.5, false, w, h, 1.0, 1) ||
            gaussianBlurWeighted(input, output, scratch, {}, 0.5, false, w, h, 1.0, 1)) return 1;
    }
    std::cout << "filter_parallel_values=" << checked
              << " weighted_values=" << weightedChecked << " mixture_values=" << mixtureChecked
              << " max_abs_error=" << worst << " failed=0\n";
}
