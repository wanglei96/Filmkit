#include "film/spektrafilm/OutputGamut.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream file(argv[1], std::ios::binary);
    std::uint32_t count = 0;
    file.read(reinterpret_cast<char*>(&count), sizeof(count));
    if (!file || count == 0 || count > 10000) return 3;
    double maxForward = 0.0;
    double maxRoundtrip = 0.0;
    for (std::uint32_t i = 0; i < count; ++i) {
        std::array<double, 9> row{};
        file.read(reinterpret_cast<char*>(row.data()), sizeof(row));
        if (!file) return 4;
        const filmkit::spektrafilm::Triplet xyz{row[0], row[1], row[2]};
        const filmkit::spektrafilm::Triplet expectedJab{row[3], row[4], row[5]};
        const filmkit::spektrafilm::Triplet expectedXyz{row[6], row[7], row[8]};
        filmkit::spektrafilm::Triplet jab{};
        filmkit::spektrafilm::Triplet roundtrip{};
        if (!filmkit::spektrafilm::xyzToCam16Ucs(xyz, jab) ||
            !filmkit::spektrafilm::cam16UcsToXyz(expectedJab, roundtrip)) {
            std::fprintf(stderr, "conversion rejected fixture %u\n", i);
            return 5;
        }
        for (std::size_t c = 0; c < 3; ++c) {
            maxForward = std::max(maxForward, std::abs(jab[c] - expectedJab[c]));
            maxRoundtrip = std::max(maxRoundtrip,
                                    std::abs(roundtrip[c] - expectedXyz[c]));
        }
    }
    std::printf("max_forward=%.9g max_inverse=%.9g\n", maxForward, maxRoundtrip);
    return maxForward <= 2e-9 && maxRoundtrip <= 2e-10 ? 0 : 1;
}
