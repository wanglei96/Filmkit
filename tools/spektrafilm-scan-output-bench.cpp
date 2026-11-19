#include "film/spektrafilm/OutputGamut.h"

#include <bit>
#include <chrono>
#include <cstdint>
#include <iomanip>
#include <cstdlib>
#include <iostream>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 3 && argc != 4) return 2;
    const std::size_t width = std::strtoull(argv[1], nullptr, 10);
    const std::size_t height = std::strtoull(argv[2], nullptr, 10);
    const std::size_t workers = argc > 3 ? std::strtoull(argv[3], nullptr, 10) : 1;
    if (!width || !height || width > 20000 || height > 20000 ||
        width > 100000000 / height) return 2;
    const std::size_t count = width * height;
    std::vector<filmkit::spektrafilm::Triplet> image(count);
    std::vector<double> plane(count), filtered(count), scratch(count);
    for (std::size_t y = 0; y < height; ++y) {
        for (std::size_t x = 0; x < width; ++x) {
            const double fx = static_cast<double>(x) / width;
            const double fy = static_cast<double>(y) / height;
            image[y * width + x] = {0.12 + 0.65 * fx, 0.08 + 0.72 * fy,
                                     0.16 + 0.58 * (1.0 - fx) + 0.04 * fy};
        }
    }
    constexpr filmkit::spektrafilm::Triplet d50{0.9642119944211994, 1.0,
                                                0.8251882845188288};
    const auto start = std::chrono::steady_clock::now();
    if (!filmkit::spektrafilm::finishScanSrgb(image, width, height, d50,
                                               0.0, 0.7, 0.7, true,
                                               plane, filtered, scratch, workers)) return 1;
    const auto end = std::chrono::steady_clock::now();
    std::uint64_t hash = 14695981039346656037ULL;
    for (const auto& pixel : image) {
        for (double value : pixel) {
            hash = (hash ^ std::bit_cast<std::uint64_t>(value)) * 1099511628211ULL;
        }
    }
    const auto ms = std::chrono::duration<double, std::milli>(end - start).count();
    std::cout << width << 'x' << height << " output_ms=" << ms
              << " workers=" << workers << " frame_hash=" << std::hex << hash << '\n';
}
