#include "film/spektrafilm/Profile.h"

#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <stdexcept>

using namespace filmkit::spektrafilm;

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    try {
        std::ifstream asset(argv[1], std::ios::binary);
        const std::vector<char> raw{std::istreambuf_iterator<char>(asset), {}};
        std::string error;
        const auto profile = loadNegativeProfile(std::as_bytes(std::span(raw)), &error);
        if (!profile) throw std::runtime_error(error);
        std::ifstream input(argv[2], std::ios::binary);
        std::uint32_t header[3]{};
        input.read(reinterpret_cast<char*>(header), sizeof(header));
        if (!input || header[0] != 0x53484452 || header[1] != 65 || header[2] != 3) return 2;
        double worst = 0.0;
        for (double ev : {-2.0, 0.0, 2.0}) {
            auto params = *profile;
            params.pixelUm /= 4080;
            params.exposureEv += ev;
            std::vector<Triplet> expected(header[1]), actual(header[1]);
            input.read(reinterpret_cast<char*>(expected.data()), expected.size() * sizeof(Triplet));
            if (!input || !negativeNeutralResponse(params, SpectralBackend::Scalar, actual)) return 1;
            for (std::size_t i = 0; i < actual.size(); ++i) for (std::size_t c = 0; c < 3; ++c) {
                const double delta = std::abs(expected[i][c] - actual[i][c]);
                if (!std::isfinite(delta) || delta > 1e-9) {
                    std::cerr << "ev=" << ev << " sample=" << i << " channel=" << c
                              << " expected=" << expected[i][c] << " actual=" << actual[i][c] << " delta=" << delta << '\n';
                    throw std::runtime_error("neutral response mismatch");
                }
                worst = std::max(worst, delta);
            }
        }
        std::cout << "patches=195 rgb_values=585 ev=-2,0,2 max_error=" << worst << " passed=1\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
