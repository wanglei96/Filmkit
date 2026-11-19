#include "film/spektrafilm/OutputGamut.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <fstream>

int main(int argc, char** argv) {
    using filmkit::spektrafilm::PerceptualGamutTable;
    using filmkit::spektrafilm::lookupMaximumChroma;
    using filmkit::spektrafilm::reinhardKnee;

    constexpr std::array<double, 2> lightness{1.0, 110.0};
    constexpr std::array<double, 4> hue{-3.141592653589793, -1.570796326794897,
                                        0.0, 1.570796326794897};
    constexpr std::array<double, 8> values{1.0, 2.0, 3.0, 4.0,
                                           11.0, 12.0, 13.0, 14.0};
    const PerceptualGamutTable table{lightness, hue, values};
    double result = 0.0;
    if (!lookupMaximumChroma(table, 1.0, -3.141592653589793, result) ||
        std::abs(result - 1.0) > 1e-12) return 1;
    if (!lookupMaximumChroma(table, 110.0, 3.141592653589793, result) ||
        std::abs(result - 11.0) > 1e-12) return 2;
    if (!lookupMaximumChroma(table, 55.5, -2.356194490192345, result) ||
        std::abs(result - 6.5) > 1e-12) return 3;
    const double seamA = reinhardKnee(1.0, 0.7, 1.0, 2.2);
    const double seamB = reinhardKnee(1.0 + 1e-9, 0.7, 1.0, 2.2);
    if (!(seamA <= 1.0 && seamB <= 1.0 && std::abs(seamB - seamA) < 1e-8)) return 4;
    if (argc == 2) {
        std::ifstream file(argv[1], std::ios::binary);
        std::uint32_t count = 0;
        file.read(reinterpret_cast<char*>(&count), sizeof(count));
        if (!file || count == 0 || count > 10000) return 5;
        double maxError = 0.0;
        for (std::uint32_t i = 0; i < count; ++i) {
            std::array<double, 6> row{};
            file.read(reinterpret_cast<char*>(row.data()), sizeof(row));
            if (!file) return 6;
            filmkit::spektrafilm::Triplet actual{};
            if (!filmkit::spektrafilm::compressSrgbCam16Ucs(
                    {row[0], row[1], row[2]}, actual)) return 7;
            for (std::size_t c = 0; c < 3; ++c) {
                maxError = std::max(maxError, std::abs(actual[c] - row[c + 3]));
            }
        }
        std::printf("max_compression_error=%.9g\n", maxError);
        if (maxError > 2e-8) return 8;
    }
    std::puts("PASS: CAM16-UCS conversion, gamut map, lookup, and knee");
    return 0;
}
