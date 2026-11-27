#include "film/spektrafilm/ParallelPixels.h"
#include "film/spektrafilm/Profile.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string_view>

using namespace filmkit::spektrafilm;

std::vector<std::byte> read(const char* path) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) throw std::runtime_error("input missing");
    std::vector<std::byte> bytes(in.tellg());
    in.seekg(0); in.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
    if (!in) throw std::runtime_error("input truncated");
    return bytes;
}

static int run(int argc, char** argv) {
    if (argc < 6 || argc > 9) return 2;
    try {
        const auto bytes = read(argv[1]), table = read(argv[2]);
        std::string error;
        const auto loaded = loadNegativeProfile(bytes, &error, table);
        if (!loaded) throw std::runtime_error(error);
        auto p = *loaded;
        const bool altered = std::stoi(argv[5]) != 0;
        std::array<double, 12> options{1, 0, 1, .65, 1, 1, 1, 0, 0, .7, .7, 35};
        if (altered) options = {1, 0, 1.5, .9, .4, 1.3, .6, .8, .25, .9, .4, 50};
        // Opt in to same-seed, FP64 comparisons across the full recipe catalog.
        if (const auto* grain = std::getenv("FILMKIT_RECIPE_GRAIN"); grain && std::string_view(grain) == "1") {
            options[1] = 1;
        }
        if (!applyRenderOptions(p, options)) throw std::runtime_error("valid options rejected");
        auto invalid = options; invalid[1] = .5;
        if (applyRenderOptions(p, invalid)) throw std::runtime_error("invalid options accepted");
        p.exposureEv = std::stod(argv[4]);
        const bool neutral = argc >= 7 && std::string_view(argv[6]) == "neutral";
        if (argc >= 7 && !neutral && std::string_view(argv[6]) != "image") return 2;
        if (argc >= 8) p.captureExposureEv = std::stod(argv[7]);
        const bool useSpectralLut = argc == 9 && std::string_view(argv[8]) == "lut";
        if (argc == 9 && (!useSpectralLut || neutral)) return 2;
        p.pixelUm /= 4080;
        p.glare.roughness = 0;
        const auto backend = hasSpectralBackend(SpectralBackend::AdvSimd)
            ? SpectralBackend::AdvSimd : SpectralBackend::Scalar;
        const auto* largeMode = std::getenv("FILMKIT_RECIPE_LARGE");
        const bool large = !neutral && largeMode && *largeMode;
        const bool aligned = large || std::getenv("FILMKIT_RECIPE_ALIGNED") != nullptr;
        const std::size_t w = large ? 192 : (aligned ? 32 : 17);
        const std::size_t h = large ? 96 : (aligned ? 24 : 13);
        std::vector<Triplet> image(w * h);
        for (std::size_t y = 0; y < h; ++y) for (std::size_t x = 0; x < w; ++x) {
            const double dx = aligned ? 2.0 * x / (w - 1) : double(x) / 8;
            const double dy = aligned ? 2.0 * y / (h - 1) : double(y) / 6;
            image[y * w + x] = {dx, dy, double((x * 7 + y * 3) % 23) / 11};
        }
        if (neutral) {
            image.resize(65);
            if (!negativeNeutralResponse(p, backend, image)) throw std::runtime_error("HDR reference failed");
            auto parallel = image;
            if (!negativeNeutralResponse(p, backend, parallel, 4) || image != parallel)
                throw std::runtime_error("parallel HDR reference differs from serial reference");
        } else {
            auto pooled = image;
            auto owned = std::make_unique_for_overwrite<Triplet[]>(image.size());
            std::copy(image.begin(), image.end(), owned.get());
            const RenderWorkers workers{4, 4, 4, 2};
            if (!renderNegativeSrgb(image, w, h, p, backend, workers, 63, 64, nullptr, nullptr, {}, useSpectralLut))
                throw std::runtime_error("render failed");
            // Use the render's scheduler for this small reference fixture too.
            ScopedPixelWorkers group(4);
            if (!renderNegativeSrgb(pooled, w, h, p, backend, workers, 63, 64, nullptr, nullptr, {}, useSpectralLut) ||
                std::memcmp(image.data(), pooled.data(), image.size() * sizeof(Triplet)) != 0)
                throw std::runtime_error("pooled render differs from fresh workers");
            if (!renderNegativeSrgb(owned, image.size(), w, h, p, backend, workers, 63, 64, nullptr, useSpectralLut) ||
                std::memcmp(image.data(), owned.get(), image.size() * sizeof(Triplet)) != 0)
                throw std::runtime_error("directly written buffers differ from vector buffers");
        }
        for (const auto& pixel : image) for (const auto x : pixel)
            if (!std::isfinite(x)) throw std::runtime_error("nonfinite render");
        std::ofstream out(argv[3], std::ios::binary);
        out.write(reinterpret_cast<const char*>(image.data()), image.size() * sizeof(Triplet));
        out.close();
        if (!out) throw std::runtime_error("output failed");
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}

int main(int argc, char** argv) {
    const int status = run(argc, argv);
#if defined(__ANDROID__)
    // As in check-renderer-device, do not finalize two libc++ runtimes.
    std::fflush(nullptr);
    std::_Exit(status);
#endif
    return status;
}
