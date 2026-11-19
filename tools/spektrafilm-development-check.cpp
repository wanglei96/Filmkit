#include "film/spektrafilm/Development.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
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
}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) return 2;
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
