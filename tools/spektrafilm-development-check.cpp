#include "film/spektrafilm/Development.h"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

using namespace filmkit::spektrafilm;

namespace {
template <class T>
void read(std::istream& source, T& value) {
    if (!(source >> value)) throw std::runtime_error("invalid development fixture");
}

void read(std::istream& source, double& value) {
    std::string token;
    read(source, token);
    char* end = nullptr;
    value = std::strtod(token.c_str(), &end);
    // Formatted stream extraction can reject representable subnormal knots.
    if (end == token.c_str() || *end != '\0') {
        throw std::runtime_error("invalid development number: " + token);
    }
}

void read(std::istream& source, Triplet& values) {
    for (auto& v : values) read(source, v);
}

DensityCurve prepare(std::istream& source, int paper, std::size_t knots, std::size_t layers) {
    std::vector<double> axis(knots);
    if (paper == 0) {
        Triplet gamma;
        read(source, gamma);
        for (auto& knot : axis) read(source, knot);
        std::vector<Triplet> stock(knots);
        for (auto& knot : stock) read(source, knot);
        return prepareInitialFilmDevelopment(axis, stock, gamma);
    }
    if (paper != 1 || !layers || layers > 32) throw std::runtime_error("invalid model");
    int positive, active;
    PaperMorph morph;
    read(source, positive);
    read(source, active);
    morph.active = active;
    read(source, morph.gamma);
    read(source, morph.fastGamma);
    read(source, morph.slowGamma);
    read(source, morph.channelGamma);
    read(source, morph.exhaustion);
    PaperModel model;
    for (auto& channel : model) {
        channel.resize(layers);
        for (auto& layer : channel) {
            read(source, layer.center);
            read(source, layer.amplitude);
            read(source, layer.sigma);
        }
    }
    for (auto& knot : axis) read(source, knot);
    return preparePaperDevelopment(axis, model, morph, positive ? EmulsionType::Positive : EmulsionType::Negative);
}

bool exactKnotLookup() {
    constexpr std::array axis{-50.0, -1.0, 0.0, 0.0, 0.0, 1e-7, 2e-7, 3e-7, 50.0};
    std::array<Triplet, axis.size()> stock{};
    for (std::size_t i = 0; i < stock.size(); ++i) stock[i].fill(static_cast<double>(i));
    DensityCurve curve(axis, stock);
    const auto check = [&](double x, double expected) {
        std::array<Triplet, 1> pixel{{{x, x, x}}};
        return curve.develop(pixel, pixel) &&
               std::all_of(pixel[0].begin(), pixel[0].end(), [&](double y) {
                   return std::bit_cast<std::uint64_t>(y) == std::bit_cast<std::uint64_t>(expected);
               });
    };
    constexpr double inf = std::numeric_limits<double>::infinity();
    if (!check(-inf, 0.0) || !check(inf, stock.back()[0])) return false;
    for (double knot : axis) for (double x : {std::nextafter(knot, -inf), knot, std::nextafter(knot, inf)}) {
        const auto hi = std::upper_bound(axis.begin(), axis.end(), x);
        double expected = stock.front()[0];
        if (hi == axis.end()) expected = stock.back()[0];
        else if (x > axis.front()) {
            const auto lo = static_cast<std::size_t>(hi - axis.begin() - 1);
            const double t = (x - axis[lo]) * (1.0 / (axis[lo + 1] - axis[lo]));
            expected = stock[lo][0] + t * (stock[lo + 1][0] - stock[lo][0]);
        }
        if (!check(x, expected)) return false;
    }
    return true;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc > 2) return 2;
    if (!exactKnotLookup()) {
        std::cerr << "density curve knot lookup differs from upper_bound\n";
        return 1;
    }
    if (argc == 1) return 0;
    try {
        std::ifstream source(argv[1]);
        std::size_t cases;
        read(source, cases);
        if (!cases || cases > 32) return 2;
        bool passed = true;
        for (std::size_t k = 0; k < cases; ++k) {
            int paper;
            std::size_t knots, samples, layers;
            read(source, paper);
            read(source, knots);
            read(source, samples);
            read(source, layers);
            if (knots < 2 || knots > 4096 || !samples || samples > 1000000) return 2;
            const auto curve = prepare(source, paper, knots, layers);
            std::vector<Triplet> input(samples), expected(samples);
            for (std::size_t i = 0; i < samples; ++i) {
                read(source, input[i]);
                read(source, expected[i]);
            }
            const auto start = std::chrono::steady_clock::now();
            if (!curve.develop(input, input)) return 2;
            const std::chrono::duration<double> elapsed = std::chrono::steady_clock::now() - start;
            double maxError = 0.0;
            for (std::size_t i = 0; i < samples; ++i) {
                for (std::size_t c = 0; c < 3; ++c) {
                    if (!std::isfinite(input[i][c]) || !std::isfinite(expected[i][c])) return 1;
                    maxError = std::max(maxError, std::abs(input[i][c] - expected[i][c]));
                }
            }
            const double tolerance = k == 1 || k == 2 ? 3e-10 : 1e-12;
            passed &= maxError <= tolerance;
            std::cout << std::setprecision(10) << "case=" << k << " pixels=" << samples
                      << " max_abs_error=" << maxError << " tolerance=" << tolerance
                      << " compute_s=" << elapsed.count() << '\n';
        }
        return passed ? 0 : 1;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
}
