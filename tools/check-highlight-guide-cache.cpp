// Compile this runner separately against baseline and candidate recovery sources.
// Compare their binary outputs with cmp; run on-device in ABBA order for timing.
// c++ -std=c++17 -O2 -pthread -Icore/engine/src/render \
//   tools/check-highlight-guide-cache.cpp core/engine/src/render/HighlightRecovery.cpp \
//   -o /tmp/check-highlight-guide-cache
// /tmp/check-highlight-guide-cache /tmp/guide.bin [--full]
#include "HighlightRecovery.h"

#include <algorithm>
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace filmkit;
namespace {
template <typename T>
void write(std::ofstream& out, const std::vector<T>& values) {
  out.write(reinterpret_cast<const char*>(values.data()),
            static_cast<std::streamsize>(values.size() * sizeof(T)));
  if (!out) throw std::runtime_error("output write failed");
}
void writeGuide(std::ofstream& out, const HighlightGuide& guide) {
  write(out, guide.rgb);
  write(out, guide.variance);
}
void run(int w, int h, unsigned cfa, float scale, unsigned seed,
         int repeats, std::ofstream& out) {
  const int stride = w + 11;
  std::vector<uint16_t> base(static_cast<size_t>(stride) * h, 65535);
  std::vector<uint16_t> shortRaw = base;
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x) {
      seed = seed * 1664525u + 1013904223u;
      const uint16_t code = static_cast<uint16_t>((seed >> 16) % 4200);
      base[static_cast<size_t>(y) * stride + x] = code;
      shortRaw[static_cast<size_t>(y) * stride + x] = code / 3;
    }
  HighlightRawView b;
  b.data = base.data(); b.width = w; b.height = h; b.stride = stride;
  b.white = 4095; b.black_phase = {{64, 65, 67, 69}}; b.cfaLut = cfa;
  auto s = b; s.data = shortRaw.data();
  HighlightNoise noise;
  noise.shot = {{.00007f, .00008f, .00009f, .00006f}};
  noise.read = {{.0000003f, .0000004f, .0000005f, .0000002f}};
  HighlightGuideSet guides;
  std::string error;
  std::vector<double> ms;
  for (int i = 0; i < repeats; ++i) {
    const auto start = std::chrono::steady_clock::now();
    if (!highlightGenerateGuides(b, s, {{2.09375f, 1.f, 1.787109f}},
                                 .83f, scale, noise, noise, &guides, &error))
      throw std::runtime_error(error);
    ms.push_back(std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start).count());
  }
  writeGuide(out, guides.base);
  writeGuide(out, guides.short_unscaled);
  writeGuide(out, guides.short_normalized);
  write(out, guides.native_clipping);
  write(out, guides.highlight);
  // Exercise both signed-variance paths and fail-closed whole-frame assessment.
  HighlightMaskAssessment assessment;
  if (!highlightAssessMasks(s, guides, {}, false, &assessment, &error))
    throw std::runtime_error(error);
  write(out, assessment.adjusted);
  write(out, assessment.inpainting);
  write(out, assessment.short_clipping);
  write(out, assessment.normalized_error);
  std::cout << w << 'x' << h << " cfa=" << cfa << " scale=" << scale << " ms=";
  for (double value : ms) std::cout << value << ',';
  std::cout << '\n';
}
}  // namespace
int main(int argc, char** argv) {
  try {
    if (argc < 2 || argc > 3)
      throw std::runtime_error("usage: check-highlight-guide-cache OUTPUT [--full]");
    const bool full = argc == 3 && std::string(argv[2]) == "--full";
    if (argc == 3 && !full) throw std::runtime_error("unknown option");
    std::ofstream out(argv[1], std::ios::binary);
    if (!out) throw std::runtime_error("cannot open output");
    if (full) {
      run(4096, 3072, 0x61, 3.2f, 42, 3, out);
    } else {
      for (unsigned cfa : {0x94u, 0x61u, 0x49u, 0x16u})
        for (auto size : {std::array<int, 2>{{8, 8}}, {{9, 11}}, {{63, 47}},
                          {{64, 48}}, {{127, 65}}})
          for (float scale : {.01f, 1.f, 3.2f, 16.f})
            run(size[0], size[1], cfa, scale, cfa * 17 + size[0], 1, out);
    }
    std::cout << "PASS guide, clipping and assessment snapshots\n";
    return 0;
  } catch (const std::exception& e) {
    std::cerr << "FAIL " << e.what() << '\n';
    return 1;
  }
}
