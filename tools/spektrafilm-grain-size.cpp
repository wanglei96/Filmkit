#include "film/spektrafilm/Profile.h"
#include "jni/JpegEncode.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <stdexcept>

using namespace filmkit::spektrafilm;

std::vector<std::byte> readAsset(const char* path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) throw std::runtime_error("asset missing");
    std::vector<std::byte> bytes(in.tellg());
    in.seekg(0); in.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
    if (!in) throw std::runtime_error("asset truncated");
    return bytes;
}

int main(int argc, char** argv) {
    if (argc != 5) return 2;
    try {
        const auto bytes = readAsset(argv[1]), table = readAsset(argv[2]);
        std::string error;
        const auto loaded = loadNegativeProfile(bytes, &error, table);
        if (!loaded) throw std::runtime_error(error);
        std::ifstream in(argv[3], std::ios::binary);
        std::array<std::uint32_t, 2> shape{};
        in.read(reinterpret_cast<char*>(shape.data()), sizeof(shape));
        const auto [w, h] = shape;
        if (!w || !h || std::uint64_t(w) * h > 16000000) return 2;
        std::vector<Triplet> input(std::size_t(w) * h);
        in.read(reinterpret_cast<char*>(input.data()), input.size() * sizeof(Triplet));
        if (!in || in.peek() != std::char_traits<char>::eof()) return 2;
        auto p = *loaded;
        p.pixelUm /= std::max(w, h);
        // Same fixed glare field and seed for both runs: grain is the only variable.
        for (bool grain : {false, true}) {
            p.grain.active = grain;
            auto image = input;
            RenderTimings timing;
            if (!renderNegativeSrgb(image, w, h, p, SpectralBackend::Scalar,
                                    {8, 8, 8, 4}, 63, 64, &timing)) return 1;
            std::vector<std::uint8_t> rgb(image.size() * 3);
            for (std::size_t i = 0; i < image.size(); ++i) for (std::size_t c = 0; c < 3; ++c) {
                if (!std::isfinite(image[i][c])) return 1;
                rgb[3 * i + c] = static_cast<std::uint8_t>(std::floor(std::clamp(image[i][c], 0.0, 1.0) * 255 + .5));
            }
            std::vector<std::uint8_t> jpeg;
            if (!filmkit::encodeJpegTurbo444(rgb.data(), w, h, 3, 100, &jpeg)) return 1;
            std::ofstream out(std::string(argv[4]) + (grain ? "/on.jpg" : "/off.jpg"), std::ios::binary);
            out.write(reinterpret_cast<const char*>(jpeg.data()), jpeg.size());
            if (!out) return 1;
            std::cout << "grain=" << grain << " bytes=" << jpeg.size() << " render_s=" << timing.total << '\n';
        }
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n'; return 1;
    }
}
