// Link the retained baseline HighlightRecovery.cpp with its five public function
// names suffixed Reference, and link the production source normally.
#include "../core/engine/src/render/HighlightRecovery.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <random>
#include <stdexcept>

namespace filmkit {
bool highlightGenerateGuidesReference(const HighlightRawView&, const HighlightRawView&,
    const std::array<float, 3>&, float, float, const HighlightNoise&, const HighlightNoise&,
    HighlightGuideSet*, std::string*);
bool highlightAssessMasksReference(const HighlightRawView&, const HighlightGuideSet&,
    const HighlightFlowView&, bool, HighlightMaskAssessment*, std::string*);
}

namespace {
using namespace filmkit;
void require(bool ok, const char* message) {
  if (!ok) throw std::runtime_error(message);
}
template <class T> void same(const std::vector<T>& a, const std::vector<T>& b) {
  require(a.size() == b.size(), "vector size changed");
  require(a.empty() || std::memcmp(a.data(), b.data(), a.size() * sizeof(T)) == 0,
          "vector bytes changed");
}
void same(const HighlightGuide& a, const HighlightGuide& b) {
  require(a.width == b.width && a.height == b.height, "guide shape changed");
  same(a.rgb, b.rgb); same(a.variance, b.variance);
}
void same(const HighlightGuideSet& a, const HighlightGuideSet& b) {
  same(a.base, b.base); same(a.short_unscaled, b.short_unscaled);
  same(a.short_normalized, b.short_normalized);
  same(a.native_clipping, b.native_clipping); same(a.highlight, b.highlight);
  require(a.refExposureScale == b.refExposureScale &&
          a.shortExposureScale == b.shortExposureScale, "guide calibration changed");
}
void same(const HighlightMaskAssessment& a, const HighlightMaskAssessment& b) {
  same(a.adjusted, b.adjusted); same(a.inpainting, b.inpainting);
  same(a.short_clipping, b.short_clipping); same(a.normalized_error, b.normalized_error);
  require(a.status == b.status && a.has_support == b.has_support &&
          a.clipped_pixels == b.clipped_pixels &&
          a.short_clipped_highlight_pixels == b.short_clipped_highlight_pixels &&
          a.largest_inpainting_area == b.largest_inpainting_area &&
          a.clipped_ratio == b.clipped_ratio &&
          a.short_clipping_overlap == b.short_clipping_overlap, "assessment summary changed");
}
struct Input {
  std::vector<uint16_t> base, shortRaw;
  HighlightRawView b, s;
  HighlightNoise noise;
  std::vector<float> flow;
  HighlightFlowView fv;
  Input(int w, int h, unsigned cfa, int mode, std::mt19937& random) {
    b.width = w; b.height = h; b.stride = w + 7; b.cfaLut = cfa;
    b.white = 4095; b.black_phase = {{64, 65, 67, 69}};
    base.resize(static_cast<std::size_t>(b.stride) * h, 65535);
    shortRaw = base;
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
      const auto p = static_cast<std::size_t>(y) * b.stride + x;
      base[p] = mode == 0 ? 64 + random() % 300 : mode == 1 ?
                (random() % 61 == 0 ? 4095 : 64 + random() % 1200) : 4095;
      shortRaw[p] = mode == 2 ? 4095 : 64 + random() % 500;
    }
    b.data = base.data(); s = b; s.data = shortRaw.data();
    noise.shot = {{.00007f, .00008f, .00009f, .00006f}};
    noise.read = {{.0000003f, .0000004f, .0000005f, .0000002f}};
    fv.width = (w + 15) / 16; fv.height = (h + 15) / 16; fv.strideRaw = 16;
    flow.resize(static_cast<std::size_t>(fv.width) * fv.height * 2);
    fv.xy = flow.data();
  }
};
HighlightGuideSet generate(Input& in, bool reference, float scale) {
  HighlightGuideSet g;
  std::string error;
  const auto fn = reference ? highlightGenerateGuidesReference : highlightGenerateGuides;
  require(fn(in.b, in.s, {{1.09375f, 1.f, 1.287109f}}, .83f, scale,
             in.noise, in.noise, &g, &error), error.c_str());
  return g;
}
HighlightMaskAssessment assess(Input& in, const HighlightGuideSet& guides, bool reference) {
  HighlightMaskAssessment result;
  std::string error;
  const auto fn = reference ? highlightAssessMasksReference : highlightAssessMasks;
  require(fn(in.s, guides, in.fv, true, &result, &error), error.c_str());
  return result;
}
}

int main(int argc, char** argv) {
  try {
    std::mt19937 random(0x434c4950);
    int cases = 0;
    for (unsigned cfa : {0x94u, 0x61u, 0x49u, 0x16u})
      for (const auto& dims : {std::array<int, 2>{{8, 8}}, {{9, 11}}, {{32, 63}},
                               {{127, 65}}, {{256, 131}}})
        for (int mode = 0; mode < 3; ++mode) {
          Input in(dims[0], dims[1], cfa, mode, random);
          const float scale = mode == 0 ? .01f : mode == 1 ? 3.2f : 16.f;
          const auto a = generate(in, true, scale), b = generate(in, false, scale);
          same(a, b);
          // Identity, fractional tile jumps/ties, and far out-of-image anchors.
          for (int pattern = 0; pattern < 3; ++pattern) {
            for (std::size_t p = 0; p < in.flow.size(); ++p)
              in.flow[p] = pattern == 0 ? 0.f : pattern == 1 ?
                  (static_cast<int>(random() % 33) - 16) * .5f :
                  ((p & 1) ? -200.f * dims[1] : 200.f * dims[0]);
            same(assess(in, a, true), assess(in, b, false));
            ++cases;
          }
          for (float invalid : {std::numeric_limits<float>::infinity(),
                                 std::numeric_limits<float>::quiet_NaN(),
                                 std::numeric_limits<float>::max()}) {
            in.flow[0] = invalid;
            HighlightMaskAssessment ra, rb;
            std::string ea, eb;
            require(!highlightAssessMasksReference(in.s, a, in.fv, true, &ra, &ea) &&
                    !highlightAssessMasks(in.s, b, in.fv, true, &rb, &eb),
                    "invalid flow must fail closed");
            same(ra, rb); require(ea == eb, "invalid-flow error changed");
          }
          in.fv = {};
          HighlightMaskAssessment ra, rb;
          std::string ea, eb;
          require(highlightAssessMasksReference(in.s, a, in.fv, false, &ra, &ea) &&
                  highlightAssessMasks(in.s, b, in.fv, false, &rb, &eb), "null flow failed");
          same(ra, rb);
          ++cases;
        }
    std::printf("PASS %d exact guide/mask comparisons, clipping halos, flow and fail-closed\n", cases);
    {
      Input in(17, 19, 0x61, 1, random);
      auto guides = generate(in, true, 3.2f);
      for (auto* guide : {&guides.base, &guides.short_unscaled, &guides.short_normalized})
        for (auto* values : {&guide->rgb, &guide->variance})
          for (std::size_t position : {std::size_t(0), std::size_t(1), std::size_t(2),
                                      std::size_t(3), std::size_t(4), values->size() - 1})
            for (float invalid : {std::numeric_limits<float>::infinity(),
                                   -std::numeric_limits<float>::infinity(),
                                   std::numeric_limits<float>::quiet_NaN()}) {
              const float original = (*values)[position];
              (*values)[position] = invalid;
              HighlightMaskAssessment a, b;
              std::string ea, eb;
              require(!highlightAssessMasksReference(in.s, guides, in.fv, true, &a, &ea) &&
                      !highlightAssessMasks(in.s, guides, in.fv, true, &b, &eb),
                      "every guide component must reject nonfinite values");
              same(a, b); require(ea == eb, "invalid-guide error changed");
              (*values)[position] = original;
            }
      std::printf("PASS 108 invalid-guide lane/tail checks\n");
    }
    if ((argc == 2 || argc == 3) && std::strcmp(argv[1], "--benchmark") == 0) {
      const int mode = argc == 3 ? std::atoi(argv[2]) : 1;
      require(mode >= 0 && mode <= 2, "benchmark mode must be 0, 1 or 2");
      Input in(4096, 3072, 0x61, mode, random);
      for (auto& value : in.flow) value = (static_cast<int>(random() % 17) - 8) * .5f;
      {
        const auto a = generate(in, true, 3.2f), b = generate(in, false, 3.2f);
        same(a, b); same(assess(in, a, true), assess(in, b, false));
        std::printf("PASS full 4096x3072 byte comparison mode=%d\n", mode);
      }
      for (int block = 0; block < 4; ++block) for (int item = 0; item < 4; ++item) {
        const bool ref = ((item == 0 || item == 3) != (block % 2 != 0));
        const auto start = std::chrono::steady_clock::now();
        auto guides = generate(in, ref, 3.2f);
        const auto middle = std::chrono::steady_clock::now();
        auto mask = assess(in, guides, ref);
        const auto end = std::chrono::steady_clock::now();
        std::printf("BENCH block=%d reference=%d guide_ms=%.3f mask_ms=%.3f total_ms=%.3f clipped=%zu\n",
          block, ref, std::chrono::duration<double, std::milli>(middle - start).count(),
          std::chrono::duration<double, std::milli>(end - middle).count(),
          std::chrono::duration<double, std::milli>(end - start).count(), mask.clipped_pixels);
        std::fflush(stdout);
      }
    }
    return 0;
  } catch (const std::exception& error) {
    std::fprintf(stderr, "FAIL %s\n", error.what());
    return 1;
  }
}
