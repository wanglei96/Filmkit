#include "film/spektrafilm/Profile.h"

#include <array>
#include <fstream>
#include <iostream>
#include <stdexcept>

using namespace filmkit::spektrafilm;

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    try {
        std::ifstream asset(argv[1], std::ios::binary);
        const std::vector<char> bytes{std::istreambuf_iterator<char>(asset), {}};
        std::string error;
        const auto profile = loadNegativeProfile(std::as_bytes(std::span(bytes)), &error);
        if (!profile) throw std::runtime_error(error);
        auto params = *profile;
        params.pixelUm /= 4080;
        // Isolate grain without removing deterministic mean paper glare.
        params.glare.roughness = 0.0;
        const auto backend = hasSpectralBackend(SpectralBackend::AdvSimd)
            ? SpectralBackend::AdvSimd : SpectralBackend::Scalar;
        std::ofstream output(argv[2], std::ios::binary);
        constexpr std::size_t side = 256;
        for (double grey : {0.005, 0.02, 0.08, 0.18, 0.5}) {
            for (bool grain : {false, true}) {
                params.grain.active = grain;
                std::vector<Triplet> image(side * side, {grey, grey, grey});
                if (!renderNegativeSrgb(image, side, side, params, backend,
                                       {4, 4, 4, 4}, 63, 64)) {
                    throw std::runtime_error("neutral grain render failed");
                }
                output.write(reinterpret_cast<const char*>(image.data()),
                             image.size() * sizeof(Triplet));
                std::cout << "grey=" << grey << " grain=" << grain << '\n';
            }
        }
        output.close();
        if (!output) throw std::runtime_error("grain result write failed");
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
