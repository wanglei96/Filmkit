// Link with production JpegEncode.cpp and the same libjpeg-turbo static library.
#include "../core/engine/src/jni/JpegEncode.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <random>
#include <stdexcept>

namespace {
void require(bool ok, const char* message) {
  if (!ok) throw std::runtime_error(message);
}
std::vector<uint8_t> encode(const std::vector<uint8_t>& rgba, int w, int h,
                            int quality, bool reference) {
  std::vector<uint8_t> jpeg;
  if (reference) {
    // Match UltraHdrEncoder's current full-frame copy followed by RGBA -> RGB.
    std::vector<uint8_t> packed(rgba.size());
    std::memcpy(packed.data(), rgba.data(), rgba.size());
    std::vector<uint8_t> rgb(static_cast<std::size_t>(w) * h * 3);
    for (std::size_t i = 0, n = static_cast<std::size_t>(w) * h; i < n; ++i)
      for (int c = 0; c < 3; ++c) rgb[3 * i + c] = packed[4 * i + c];
    require(filmkit::encodeJpegTurbo444(rgb.data(), w, h, 3, quality, &jpeg), "RGB encode failed");
  } else {
    require(filmkit::encodeJpegTurbo444(rgba.data(), w, h, 4, quality, &jpeg), "RGBA encode failed");
  }
  require(jpeg.size() > 4 && jpeg[0] == 0xff && jpeg[1] == 0xd8 &&
          jpeg[jpeg.size() - 2] == 0xff && jpeg.back() == 0xd9, "invalid JPEG markers");
  return jpeg;
}
std::vector<uint8_t> input(int w, int h, int mode, std::mt19937& random) {
  std::vector<uint8_t> rgba(static_cast<std::size_t>(w) * h * 4);
  for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
    for (int c = 0; c < 4; ++c) {
      const auto p = (static_cast<std::size_t>(y) * w + x) * 4 + c;
      rgba[p] = c == 3 || mode == 0 ? static_cast<uint8_t>(random()) :
                mode == 1 ? static_cast<uint8_t>((x * 5 + y * 3 + c * 51) % 256) : 127;
    }
  return rgba;
}
}
int main(int argc, char** argv) {
  try {
    std::mt19937 random(0x4a504547);
    int count = 0;
    for (const auto& dims : {std::array<int, 2>{{1, 1}}, {{7, 9}}, {{8, 8}},
                             {{63, 47}}, {{513, 257}}})
      for (int mode = 0; mode < 3; ++mode) for (int q : {1, 90, 95, 100}) {
        const auto rgba = input(dims[0], dims[1], mode, random);
        require(encode(rgba, dims[0], dims[1], q, true) ==
                encode(rgba, dims[0], dims[1], q, false), "RGB/RGBA JPEG bytes differ");
        ++count;
      }
    std::printf("PASS %d exact RGB/RGBA JPEG comparisons, random alpha ignored\n", count);
    if (argc == 2 && std::strcmp(argv[1], "--benchmark") == 0) {
      const auto rgba = input(3072, 4080, 1, random);
      const auto expected = encode(rgba, 3072, 4080, 100, true);
      require(encode(rgba, 3072, 4080, 100, false) == expected, "full-size bytes changed");
      for (int block = 0; block < 4; ++block) for (int item = 0; item < 4; ++item) {
        const bool ref = ((item == 0 || item == 3) != (block % 2 != 0));
        const auto start = std::chrono::steady_clock::now();
        const auto jpeg = encode(rgba, 3072, 4080, 100, ref);
        const double ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
        require(jpeg == expected, "benchmark bytes changed");
        std::printf("BENCH block=%d reference=%d ms=%.3f bytes=%zu\n", block, ref, ms, jpeg.size());
        std::fflush(stdout);
      }
    }
    return 0;
  } catch (const std::exception& error) {
    std::fprintf(stderr, "FAIL %s\n", error.what());
    return 1;
  }
}
