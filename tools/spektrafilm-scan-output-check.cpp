#include "film/spektrafilm/OutputGamut.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <span>
#include <vector>

using filmkit::spektrafilm::Triplet;

template <class Range>
bool read(std::istream& in, Range& range) {
    return static_cast<bool>(in.read(reinterpret_cast<char*>(range.data()),
                                     static_cast<std::streamsize>(range.size() * sizeof(range[0]))));
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream in(argv[1], std::ios::binary);
    std::array<std::uint32_t, 3> header{};
    if (!read(in, header)) return 3;
    const auto [cases, width, height] = header;
    const std::size_t count = std::size_t(width) * height;
    if (!count || count > 100000 || cases != 2) return 4;
    Triplet white{};
    if (!read(in, white)) return 5;
    std::vector<Triplet> image(count), expected(count);
    std::vector<double> plane(count), filtered(count), scratch(count);
    double maximumError = 0.0;
    for (std::uint32_t test = 0; test < cases; ++test) {
        std::array<double, 3> settings{};
        if (!read(in, settings) || !read(in, image) || !read(in, expected)) return 6;
        if (!filmkit::spektrafilm::finishScanSrgb(
                image, width, height, white, settings[0], settings[1],
                settings[2], true, plane, filtered, scratch)) return 7;
        double caseMaximum = 0.0;
        for (std::size_t i = 0; i < count; ++i) {
            for (std::size_t c = 0; c < 3; ++c) {
                const double error = std::abs(image[i][c] - expected[i][c]);
                if (!std::isfinite(error)) return 8;
                maximumError = std::max(maximumError, error);
                caseMaximum = std::max(caseMaximum, error);
            }
        }
        std::cout << "case=" << test << " max_error=" << caseMaximum << '\n';
    }
    std::cout << "max_scan_output_error=" << maximumError << '\n';
    return maximumError <= 2e-8 ? 0 : 1;
}
