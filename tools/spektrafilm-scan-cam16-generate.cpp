// Offline generator for the candidate scan-cam16-v1.fkcam16 APK asset.
// macOS / Apple Clang, from the repository root:
//   s=core/engine/src/film/spektrafilm
//   clang++ -std=c++20 -O3 -ffp-contract=off -pthread -I"$s" -Wl,-dead_strip \
//     tools/spektrafilm-scan-cam16-generate.cpp "$s/OutputGamut.cpp" -o /tmp/scan-cam16-generate
//   /tmp/scan-cam16-generate /tmp/scan-cam16-v1.fkcam16
//   cmp /tmp/scan-cam16-v1.fkcam16 app/src/main/assets/spektrafilm/scan-cam16-v1.fkcam16
// Run the full quality matrix before replacing an asset; identical size is not enough.

#include "OutputGamut.h"
#include "ParallelPixels.h"
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <numeric>
#include <vector>

namespace filmkit::spektrafilm {
namespace {
#include "OfflineScanLut.inc"

int generate(const char* path) {
    using Lut = ScanCamLut;
    static_assert(std::endian::native == std::endian::little && sizeof(Triplet) == 24);
    std::vector<Triplet> nodes(Lut::nodeCount);
    std::vector<uint8_t> cells(Lut::cellCount);
    const Lut table{reinterpret_cast<const std::byte*>(nodes.data()), cells.data()};
    constexpr std::size_t workers = 8;
    const auto linear = Lut::linear;
    const auto oetf = Lut::oetf;
    const auto offset = Lut::offset;
    constexpr auto side = Lut::side, intervals = Lut::intervals;
    const double nan = std::numeric_limits<double>::quiet_NaN();
    parallelPixels(nodes.size(), workers, [&](std::size_t begin, std::size_t count) {
        for (std::size_t i = begin; i < begin + count; ++i) {
            if (!compressSrgbCam16Ucs({linear(i/(side*side)), linear(i/side%side), linear(i%side)}, nodes[i])) {
                nodes[i] = {nan, nan, nan};
            }
        }
        return true;
    }, 1024);
    parallelPixels(cells.size(), workers, [&](std::size_t begin, std::size_t count) {
        for (std::size_t i = begin; i < begin + count; ++i) {
            const int r = i/(intervals*intervals), g = i/intervals%intervals, b = i%intervals;
            const auto base = offset(r,g,b);
            // Empirical prefilter only; test unseen samples and real RAW separately.
            bool valid = true;
            for (int sample = 0; sample < 9 && valid; ++sample) {
                const auto frac = [sample](int axis) {
                return sample == 8 ? .5 : (((sample >> axis) & 1) ? .75 : .25);
            };
                const Triplet f{frac(0), frac(1), frac(2)};
                Triplet exact;
                valid = compressSrgbCam16Ucs({linear(r+f[0]),linear(g+f[1]),linear(b+f[2])}, exact);
                if (!valid) break;
                const auto approx = table.interpolate(base, f);
                for (int c = 0; c < 3; ++c) {
                    valid &= std::abs(oetf(exact[c]) - oetf(approx[c])) <= .12 / 255.0;
                }
            }
            cells[i] = valid;
        }
        return true;
    }, 256);
    const auto count = std::accumulate(cells.begin(), cells.end(), 0);
    std::printf("safe cells: %d/%zu; bytes=%zu\n", count, cells.size(), Lut::byteCount);
    if (FILE* out = std::fopen(path, "wb")) {
        const bool ok = std::fwrite(nodes.data(), sizeof(Triplet), nodes.size(), out) == nodes.size() &&
            std::fwrite(cells.data(), 1, cells.size(), out) == cells.size();
        return std::fclose(out) != 0 || !ok;
    }
    return 1;
}
}  // namespace
}  // namespace filmkit::spektrafilm

int main(int argc, char** argv) {
    return argc == 2 ? filmkit::spektrafilm::generate(argv[1]) : 1;
}
