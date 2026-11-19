#include "film/spektrafilm/Profile.h"

#include <cmath>
#include <fstream>
#include <iostream>
#include <stdexcept>

using namespace filmkit::spektrafilm;

std::vector<std::byte> read(const char* path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) throw std::runtime_error("input missing");
    std::vector<std::byte> bytes(in.tellg());
    in.seekg(0); in.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
    if (!in) throw std::runtime_error("input truncated");
    return bytes;
}

int main(int argc, char** argv) {
    if (argc != 6 && argc != 7) return 2;
    try {
        const auto bytes = read(argv[1]), table = read(argv[2]);
        std::string error;
        const auto loaded = loadNegativeProfile(bytes, &error, table);
        if (!loaded) throw std::runtime_error(error);
        auto p = *loaded;
        const bool altered = std::stoi(argv[5]) != 0;
        std::array<double, 12> options{1, 0, 1, .65, 1, 1, 1, 0, 0, .7, .7, 35};
        if (altered) options = {1, 0, 1.5, .9, .4, 1.3, .6, .8, .25, .9, .4, 50};
        if (!applyRenderOptions(p, options)) throw std::runtime_error("valid options rejected");
        auto invalid = options; invalid[1] = .5;
        if (applyRenderOptions(p, invalid)) throw std::runtime_error("invalid options accepted");
        p.exposureEv = std::stod(argv[4]);
        p.pixelUm /= 4080;
        p.glare.roughness = 0;
        const auto backend = hasSpectralBackend(SpectralBackend::AdvSimd)
            ? SpectralBackend::AdvSimd : SpectralBackend::Scalar;
        constexpr std::size_t w = 17, h = 13;
        std::vector<Triplet> image(w * h);
        for (std::size_t y = 0; y < h; ++y) for (std::size_t x = 0; x < w; ++x) {
            image[y * w + x] = {double(x) / 8, double(y) / 6, double((x * 7 + y * 3) % 23) / 11};
        }
        if (argc == 7) {
            image.resize(65);
            if (!negativeNeutralResponse(p, backend, image)) throw std::runtime_error("HDR reference failed");
        } else if (!renderNegativeSrgb(image, w, h, p, backend, {4, 4, 4, 2}, 63, 64))
            throw std::runtime_error("render failed");
        for (const auto& pixel : image) for (const auto x : pixel)
            if (!std::isfinite(x)) throw std::runtime_error("nonfinite render");
        std::ofstream out(argv[3], std::ios::binary);
        out.write(reinterpret_cast<const char*>(image.data()), image.size() * sizeof(Triplet));
        if (!out) throw std::runtime_error("output failed");
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
