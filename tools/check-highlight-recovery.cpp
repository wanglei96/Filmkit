// Standalone CPU regression (no GL/Android/build-file changes required):
// c++ -std=c++17 -O2 -Wall -Wextra -Werror -Icore/engine/src/render \
//   tools/check-highlight-recovery.cpp core/engine/src/render/HighlightRecovery.cpp \
//   -o /tmp/check-highlight-recovery && /tmp/check-highlight-recovery
#include "HighlightRecovery.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

using namespace filmkit;
namespace {
int assertions = 0;
void require(bool ok, const std::string& message) {
  ++assertions;
  if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
void near(float a, float b, float tolerance, const std::string& message) {
  require(std::abs(a - b) <= tolerance, message + ": " + std::to_string(a) +
                                                " vs " + std::to_string(b));
}
struct Raw {
  int w = 64, h = 48, stride = 71;
  unsigned lut = 0x94;
  std::array<float, 4> black{{64, 65, 67, 69}};
  std::vector<uint16_t> pixels = std::vector<uint16_t>(stride * h, 65535);
  HighlightRawView view() const {
    HighlightRawView v;
    v.data = pixels.data(); v.width = w; v.height = h; v.stride = stride;
    v.black_phase = black; v.white = 4095; v.cfaLut = lut;
    return v;
  }
  void set(int x, int y, float signal) {
    int p = (y & 1) * 2 + (x & 1);
    pixels[y * stride + x] = static_cast<uint16_t>(std::round(
        black[p] + (4095 - black[p]) * std::max(0.f, std::min(signal, 1.f))));
  }
  void fill(const std::array<float, 4>& phases) {
    for (int y = 0; y < h; ++y)
      for (int x = 0; x < w; ++x) set(x, y, phases[(y & 1) * 2 + (x & 1)]);
  }
};
const std::array<float, 3> identity{{1, 1, 1}};
HighlightNoise noiseless;
HighlightGuideSet generate(const Raw& base, const Raw& shortRaw, float scale = 2.f,
                           std::array<float, 3> wb = identity, float refScale = 1.f) {
  HighlightGuideSet g;
  std::string error;
  const bool ok = highlightGenerateGuides(base.view(), shortRaw.view(), wb, refScale,
                                          scale, noiseless, noiseless, &g, &error);
  require(ok, "generate: " + error);
  return g;
}
HighlightMaskAssessment assess(const Raw& shortRaw, const HighlightGuideSet& guides,
                               bool support = true, float dx = 0.f, float dy = 0.f,
                               int strideRaw = 16) {
  const int gw = (shortRaw.w + strideRaw - 1) / strideRaw;
  const int gh = (shortRaw.h + strideRaw - 1) / strideRaw;
  std::vector<float> flow(2 * gw * gh);
  for (int i = 0; i < gw * gh; ++i) { flow[2 * i] = dx; flow[2 * i + 1] = dy; }
  HighlightFlowView fv;
  fv.xy = flow.data(); fv.width = gw; fv.height = gh; fv.strideRaw = strideRaw;
  HighlightMaskAssessment result;
  std::string error;
  const bool ok = highlightAssessMasks(shortRaw.view(), guides, fv, support, &result, &error);
  require(ok, "assess: " + error);
  HighlightMaskAssessment merge;
  require(highlightAssessMergeMasks(shortRaw.view(), guides, fv, support, &merge, &error), error);
  require(merge.adjusted == result.adjusted && merge.inpainting == result.inpainting &&
          merge.status == result.status && merge.has_support == result.has_support &&
          merge.clipped_pixels == result.clipped_pixels && merge.clipped_ratio == result.clipped_ratio &&
          merge.largest_inpainting_area == result.largest_inpainting_area,
          "merge-only assessment retains the same masks and decision");
  require(merge.short_clipping.empty() && merge.normalized_error.empty(),
          "merge-only assessment omits diagnostic maps");
  return result;
}
void recoverableAndColor() {
  Raw base, shortRaw;
  base.fill({{1.f, .3f, .3f, .05f}});
  shortRaw.fill({{.5f, .15f, .15f, .025f}});
  auto g = generate(base, shortRaw);
  auto a = assess(shortRaw, g);
  require(a.accepted(), "recoverable clipped red accepted");
  require(a.clipped_pixels == g.highlight.size(), "all red native clips recorded");
  require(a.largest_inpainting_area == 0, "recoverable short leaves no holes");
  require(std::all_of(a.adjusted.begin(), a.adjusted.end(), [](uint8_t v){return v == 255;}),
          "recoverable short keeps full M");
  for (std::size_t i = 0; i < g.base.variance.size(); ++i) {
    require(g.base.rgb[3 * i] > g.base.rgb[3 * i + 1] * 3.f, "red object remains red");
    require(g.base.rgb[3 * i + 1] > g.base.rgb[3 * i + 2] * 5.f, "RGB not scalar neutralized");
  }
  std::cout << "PASS recoverable native clip; RGB hue retained; no scalar CFA inpaint\n";
}
void diagnosticOnly() {
  Raw base, shortRaw;
  base.fill({{1, 1, 1, 1}}); shortRaw.fill({{1, 1, 1, 1}});
  auto g = generate(base, shortRaw);
  auto a = assess(shortRaw, g);
  require(a.accepted(), "short also clipped is diagnostic-only, not veto");
  near(a.short_clipping_overlap, 1.f, 0.f, "native short clip overlap");
  require(a.largest_inpainting_area == 0, "short clip alone never creates ghost hole");
  require(std::all_of(a.adjusted.begin(), a.adjusted.end(), [](uint8_t v){return v == 255;}),
          "short clip does not zero M");
  std::cout << "PASS short also clipped: overlap=1, M=255, accepted\n";
}
void admissionStates() {
  Raw base, shortRaw;
  base.fill({{1, 1, 1, 1}}); shortRaw.fill({{0, 0, 0, 0}});
  auto g = generate(base, shortRaw);
  auto a = assess(shortRaw, g);
  require(a.status == HighlightAdmission::LargeInpaintingHole, "large ghost rejects whole frame");
  require(a.largest_inpainting_area == g.highlight.size(), "ghost topology area");
  a = assess(shortRaw, g, false);
  require(a.status == HighlightAdmission::NoAlignmentSupport, "no support rejects before hole reason");
  HighlightMaskAssessment absentFlow;
  std::string error;
  require(highlightAssessMasks(shortRaw.view(), g, HighlightFlowView{}, false, &absentFlow, &error),
          "null flow diagnostic permitted without support");
  require(!absentFlow.accepted(), "absent corrected flow never accepted");
  require(!highlightAssessMasks(shortRaw.view(), g, HighlightFlowView{}, true, &absentFlow, &error),
          "null flow forbidden with claimed support");
  base.fill({{.2f, .2f, .2f, .2f}}); shortRaw.fill({{.1f, .1f, .1f, .1f}});
  g = generate(base, shortRaw);
  a = assess(shortRaw, g, false);
  require(a.status == HighlightAdmission::NoHighlights, "no highlights is separate no-op state");
  require(a.clipped_pixels == 0 && !a.accepted(), "no-highlight frame not injected");
  for (int scenario = 0; scenario < 3; ++scenario) {
    // Empty mask, native clipping at an edge, and guide-only clipping.
    base.fill({{.2f, .2f, .2f, .2f}});
    if (scenario == 1) base.set(base.w - 1, base.h - 1, 1.f);
    const float refScale = scenario == 2 ? 6.f : 1.f;
    const auto full = generate(base, shortRaw, 2.f, identity, refScale);
    HighlightGuideSet merge;
    bool empty = scenario != 0;
    require(highlightPrepareMergeGuides(base.view(), shortRaw.view(), identity, refScale, 2.f,
                                        noiseless, noiseless, &merge, empty, &error), error);
    require(empty == (scenario == 0), "only a zero reference mask bypasses short guides");
    require(merge.highlight == full.highlight && merge.native_clipping == full.native_clipping &&
            merge.base.rgb == full.base.rgb && merge.base.variance == full.base.variance,
            "merge preparation keeps the complete reference unchanged");
    if (empty) {
      require(merge.short_unscaled.rgb.empty() && merge.short_normalized.rgb.empty(),
              "no short buffers on the empty mask path");
      require(assess(shortRaw, full, false).adjusted == merge.highlight,
              "zero reference mask is the same adjusted mask as the complete solver");
    } else {
      require(merge.short_unscaled.rgb == full.short_unscaled.rgb &&
              merge.short_unscaled.variance == full.short_unscaled.variance &&
              merge.short_normalized.rgb == full.short_normalized.rgb &&
              merge.short_normalized.variance == full.short_normalized.variance,
              "nonempty mask preserves both full short guides");
    }
  }
  std::cout << "PASS large ghost rejected; unsupported rejected; no-highlights distinguished\n";
}
void nativeThresholdAndScaling() {
  Raw base, shortRaw;
  base.fill({{1.f, .4f, .4f, .1f}}); shortRaw.fill({{1.f, .2f, .2f, .05f}});
  auto g = generate(base, shortRaw, .1f, {{.1f, .1f, .1f}}, .1f);
  require(g.base.rgb[0] < .02f, "WB/exposure suppress guide but not native detection");
  require(std::all_of(g.highlight.begin(), g.highlight.end(), [](uint8_t b){return b == 255;}),
          "native clipping cannot be disguised by WB/exposure");
  auto a = assess(shortRaw, g);
  near(a.short_clipping_overlap, 1.f, 0.f, "short native clipping ignores WB/exposure");
  base.fill({{.98f, .2f, .2f, .2f}}); shortRaw.fill({{.1f, .1f, .1f, .1f}});
  g = generate(base, shortRaw, 2.f, {{2.f, 1.f, 1.f}});
  require(g.native_clipping[0] == 0 && g.highlight[0] == 255,
          "guide ramp OR native clip, not AND");
  // Integer ceil threshold on a nonzero-black red phase, every native code below
  // threshold must stay clear even with larger padding sentinels in the row.
  base.fill({{.1f, .1f, .1f, .1f}});
  const int threshold = static_cast<int>(std::ceil(base.black[0] +
                                   (4095.f - base.black[0]) * (250.f / 255.f)));
  base.pixels[20 * base.stride + 20] = static_cast<uint16_t>(threshold - 1);
  g = generate(base, shortRaw, 2.f, {{.1f, .1f, .1f}});
  require(std::none_of(g.native_clipping.begin(), g.native_clipping.end(),
                      [](uint8_t b){return b != 0;}), "integer code below ceil threshold not clipped");
  base.pixels[20 * base.stride + 20] = static_cast<uint16_t>(threshold);
  g = generate(base, shortRaw, 2.f, {{.1f, .1f, .1f}});
  require(std::count(g.native_clipping.begin(), g.native_clipping.end(), 255) == 9,
          "native clipping uses exact 3x3 quad footprint");
  std::cout << "PASS integer native threshold, padded stride, WB/exposure invariance, guide OR ramp\n";
}
void noiseAndBayerLayouts() {
  Raw raw;
  raw.fill({{.2f, .2f, .2f, .05f}});
  // One green quad is bright; center guide chooses its unfiltered green only
  // when the full 18-sample variance exceeds three times the original LUT noise.
  raw.set(20, 21, .8f); raw.set(21, 20, .8f);
  HighlightGuide low, high;
  HighlightNoise highNoise;
  highNoise.read = {{0, .5f, .5f, 0}};
  std::string error;
  require(highlightGenerateGuide(raw.view(), identity, 1, noiseless, &low, &error), error);
  require(highlightGenerateGuide(raw.view(), identity, 1, highNoise, &high, &error), error);
  const std::size_t p = 10 * low.width + 10;
  near(low.rgb[3 * p + 1], .8f, .001f, "low noise picks center green");
  near(high.rgb[3 * p + 1], .35f, .001f, "high noise picks 3x3 binomial green");
  require(low.variance[p] < 0 && high.variance[p] >= 0, "guide retains signed full variance branch");
  // Correct mapping and per-native-phase black subtraction for all four Bayer layouts.
  for (unsigned lut : {0x94u, 0x61u, 0x49u, 0x16u}) {
    raw.lut = lut;
    for (int y = 0; y < raw.h; ++y)
      for (int x = 0; x < raw.w; ++x) {
        const int phase = (y & 1) * 2 + (x & 1);
        const int c = (lut >> (phase * 2)) & 3u;
        raw.set(x, y, c == 0 ? .6f : c == 1 ? .3f : .1f);
      }
    HighlightGuide guide;
    require(highlightGenerateGuide(raw.view(), identity, 1, noiseless, &guide, &error), error);
    near(guide.rgb[0], .6f, .001f, "CFA red");
    near(guide.rgb[1], .3f, .001f, "CFA green");
    near(guide.rgb[2], .1f, .001f, "CFA blue");
  }
  std::cout << "PASS original noise LUT semantics, signed variance, four CFA layouts\n";
}
void exactMaskBoundariesAndConnectivity() {
  Raw base, shortRaw;
  base.fill({{.1f, .1f, .1f, .1f}}); shortRaw.fill({{0, 0, 0, 0}});
  auto g = generate(base, shortRaw, 1.f);
  auto original = g;
  const int w = g.base.width;
  // 79 pixels linked only diagonally between the first two blocks proves
  // 8-connectivity (not four-connected or independent per-region acceptance).
  for (int i = 0; i < 79; ++i) {
    const int x = i < 40 ? 1 + i % 8 : 9 + (i - 40) % 8;
    const int y = i < 40 ? 1 + i / 8 : 6 + (i - 40) / 8;
    const int p = y * w + x;
    g.highlight[p] = 255;
    for (int c = 0; c < 3; ++c) g.base.rgb[3 * p + c] = 1.f;
  }
  auto a = assess(shortRaw, g);
  require(a.largest_inpainting_area == 79 && a.accepted(), "79-pixel diagonal-connected hole accepted");
  const int p80 = 10 * w + 16;
  g.highlight[p80] = 255;
  for (int c = 0; c < 3; ++c) g.base.rgb[3 * p80 + c] = 1.f;
  a = assess(shortRaw, g);
  require(a.largest_inpainting_area == 80 && !a.accepted(), "80-pixel hole rejects exactly at boundary");
  g = original;
  const int p = 12 * w + 12;
  g.highlight[p] = 255;
  for (int c = 0; c < 3; ++c) { g.base.rgb[3 * p + c] = 1.f; g.short_unscaled.rgb[3 * p + c] = 1.5f; }
  a = assess(shortRaw, g);
  near(a.normalized_error[p], 0.f, 0.f, "brighter short does not produce symmetric difference ghost");
  require(a.adjusted[p] == 255, "one-sided positive difference keeps M");
  for (int c = 0; c < 3; ++c) { g.base.rgb[3 * p + c] = .51f; g.short_unscaled.rgb[3 * p + c] = 0.f; }
  a = assess(shortRaw, g);
  require(a.normalized_error[p] < .9f && a.adjusted[p] == 255, "RGB error below .9 does not fall back");
  for (int c = 0; c < 3; ++c) g.base.rgb[3 * p + c] = .53f;
  a = assess(shortRaw, g);
  require(a.normalized_error[p] >= .9f && a.adjusted[p] < 255, "RGB error above .9 quantizes fallback");
  // Partial mask, non-neutral but sufficiently bright two channels: PC's 128/255
  // minimum and 250/255 middle test, independently of highlight==1 override.
  g.highlight[p] = 254;
  g.base.rgb[3 * p] = 1.f; g.base.rgb[3 * p + 1] = 250.f / 255.f;
  g.base.rgb[3 * p + 2] = 128.f / 255.f;
  a = assess(shortRaw, g);
  require(a.inpainting[p] == 255, "128/255 smallest and 250/255 middle boundary included");
  g.base.rgb[3 * p + 2] = 127.f / 255.f;
  a = assess(shortRaw, g);
  require(a.inpainting[p] == 0, "127/255 smallest excluded for partial ramp");
  g.base.rgb[3 * p + 2] = 128.f / 255.f; g.base.rgb[3 * p + 1] = 249.f / 255.f;
  a = assess(shortRaw, g);
  require(a.inpainting[p] == 0, "249/255 middle excluded for partial ramp");
  std::cout << "PASS one-sided RGB .9, exact 79/80 eight-connectivity, 128/250 thresholds\n";
}
void mirroredBilinearAndClipFootprint() {
  Raw base, shortRaw;
  base.fill({{.1f, .1f, .1f, .1f}}); shortRaw.fill({{0, 0, 0, 0}});
  auto g = generate(base, shortRaw, 1.f);
  const int w = g.base.width, h = g.base.height;
  // A horizontal RGB ramp lets expected bilinear interpolation be computed
  // independently; dx=1 native pixel is .5 guide pixel. At the right boundary,
  // mirrorUv plus clamped linear sampler yields the last texel, not wrap.
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x)
      for (int c = 0; c < 3; ++c) {
        g.base.rgb[3 * (y * w + x) + c] = 1.f;
        g.short_unscaled.rgb[3 * (y * w + x) + c] = x / float(w - 1);
      }
  std::fill(g.highlight.begin(), g.highlight.end(), 255);
  auto a = assess(shortRaw, g, true, 1.f, 0.f);
  const int x = 10, y = 10;
  const float gained = std::floor(65535.f * ((x + .5f) / (w - 1))) / 65535.f;
  near(a.normalized_error[y * w + x], std::sqrt(3.f) * (1.f - gained), .0001f,
       "fractional mirrored bilinear");
  near(a.normalized_error[y * w + w - 1], 0.f, .0001f, "mirror right border");
  a = assess(shortRaw, g, true, 4.f, 0.f);
  near(a.normalized_error[y * w + w - 1], std::sqrt(3.f) / (w - 1), .0001f,
       "mirrored right overflow samples reflected interior");
  shortRaw.set(20, 20, 1.f);
  a = assess(shortRaw, g, true, 0.f, 0.f);
  require(std::count(a.short_clipping.begin(), a.short_clipping.end(), 255) == 9,
          "short clip three-by-three native footprint");
  a = assess(shortRaw, g, true, 4.f, 0.f);
  require(a.short_clipping[10 * w + 8] == 255 && a.short_clipping[10 * w + 12] == 0,
          "short clipping follows positive forward displacement");
  a = assess(shortRaw, g, true, 1.f, 0.f);
  require(a.short_clipping[10 * w + 8] == 0 && a.short_clipping[10 * w + 9] == 255 &&
          a.short_clipping[10 * w + 11] == 0,
          "native footprint anchor uses GLSL ties-to-even, not round-away");
  std::cout << "PASS mirror bilinear, fractional flow, transported native clip footprint\n";
}
void pcQuantizationFlowAndGuideBorders() {
  Raw base, shortRaw;
  base.fill({{.1f, .1f, .1f, .1f}}); shortRaw.fill({{.1f, .1f, .1f, .1f}});
  auto g = generate(base, shortRaw, 2.828f);
  const int w = g.base.width, h = g.base.height;
  for (int y = 0; y < h; ++y)
    for (int x = 0; x < w; ++x)
      for (int c = 0; c < 3; ++c) {
        g.base.rgb[3 * (y * w + x) + c] = .731234f + .01f * c;
        g.short_unscaled.rgb[3 * (y * w + x) + c] = x / float(8 * w);
      }
  std::fill(g.highlight.begin(), g.highlight.end(), 219);
  std::vector<float> flow(2 * 4 * 3, 0.f);
  for (int ty = 0; ty < 3; ++ty)
    for (int tx = 0; tx < 4; ++tx) flow[2 * (ty * 4 + tx)] = tx & 1 ? 4.f : 0.f;
  HighlightFlowView fv;
  fv.xy = flow.data(); fv.width = 4; fv.height = 3; fv.strideRaw = 16;
  HighlightMaskAssessment a;
  std::string error;
  require(highlightAssessMasks(shortRaw.view(), g, fv, true, &a, &error), error);
  for (int x : {6, 7, 8, 9, 14, 15, 16, 17}) {
    const int p = 10 * w + x;
    const float sampleX = x + ((x / 8) & 1 ? 2.f : 0.f);
    const float shortSignal = sampleX / (8 * w);
    float e2 = 0.f;
    for (int c = 0; c < 3; ++c) {
      const float qbase = std::round(65535.f * g.base.rgb[3 * p + c]) / 65535.f;
      const float gained = std::floor(65535.f * shortSignal * 2.828f) / 65535.f;
      const float d = std::min(gained - qbase, 0.f);
      e2 += d * d;
    }
    const float expectedError = std::sqrt(e2);
    near(a.normalized_error[p], expectedError, .00001f,
         "PC tile-nearest flow (not center bilinear), U16 base round/short floor");
    const float fallback = expectedError >= .9f ? std::floor(expectedError * 255.f) / 255.f : 0.f;
    const int expectedM = std::max(0, std::min(255, static_cast<int>(std::floor(
                                  (1.f - fallback) * (219.f / 255.f) * 255.f))));
    require(a.adjusted[p] == expectedM, "PC adjusted mask exact floor to byte");
  }
  for (int y = 0; y < base.h; ++y)
    for (int x = 0; x < base.w; ++x) {
      const int c = (base.lut >> (2 * ((y & 1) * 2 + (x & 1)))) & 3u;
      base.set(x, y, c == 0 ? .05f + .02f * (x / 2) : .2f);
    }
  HighlightGuide borderGuide;
  require(highlightGenerateGuide(base.view(), identity, 1, noiseless, &borderGuide, &error), error);
  near(borderGuide.rgb[0], .09f, .0004f, "guide left output0 mirrors center2");
  near(borderGuide.rgb[3], .07f, .0004f, "guide output1 retains center1");
  near(borderGuide.rgb[3 * (w - 1)], .05f + .02f * (w - 3), .0005f,
       "guide right output mirrors to extent-3");
  for (float value : borderGuide.rgb) {
    int e;
    const float fraction = std::frexp(std::abs(value), &e);
    const float significand = e < -13 ? std::ldexp(std::abs(value), 24) : fraction * 2048.f;
    near(significand, std::round(significand), 0.f, "guide stores exact IEEE binary16 values");
  }
  // PC uses ceil(RAW/2), clamped quad loads and duplicated final native phase
  // at odd extents. Exercise that path under sanitizers as well.
  base.w = shortRaw.w = 63; base.h = shortRaw.h = 47;
  base.fill({{.3f, .2f, .2f, .1f}}); shortRaw.fill({{.15f, .1f, .1f, .05f}});
  g = generate(base, shortRaw);
  require(g.base.width == 32 && g.base.height == 24, "odd RAW uses ceil guide geometry");
  a = assess(shortRaw, g);
  require(a.status == HighlightAdmission::NoHighlights, "odd-size border clipping stays clear");
  std::cout << "PASS PC U16/R8/binary16 quantization, nearest flow tiles, guide mirror, odd extents\n";
}
void mergeMasks() {
  std::vector<uint8_t> selected, linear;
  std::string error;
  // Exact half-bin: writing (1-M) to R8 first must produce 127, not the
  // 128 that direct round(M*255) would produce.
  std::vector<uint8_t> source(16, 0);
  for (int y = 0; y < 4; ++y)
    for (int x = 0; x < 4; ++x) source[y * 4 + x] = y & 1 ? 255 : 0;
  require(highlightBuildMergeMasks(source, 4, 4, 8, 8, true, &selected, &linear, &error), error);
  require(selected == std::vector<uint8_t>(4, 127), "R8 complement/recomplement preserves half-bin convention");
  require(linear == std::vector<uint8_t>(4, 0), "uniform merge mask selects no linear kernel");

  // One-quantum nonuniformity is enough: min/max must be tested AFTER sampling
  // and R8 conversion, with no amplitude threshold. Border neighborhoods clamp.
  source.assign(12 * 10, 100);
  for (int y = 4; y < 6; ++y)
    for (int x = 6; x < 8; ++x) source[y * 12 + x] = 101;
  require(highlightBuildMergeMasks(source, 12, 10, 24, 20, true, &selected, &linear, &error), error);
  require(selected[2 * 6 + 3] == 101 && std::count(selected.begin(), selected.end(), 101) == 1,
          "2x RAW/2 to RAW/4 linear sampling exact for flat blocks");
  require(std::count(linear.begin(), linear.end(), 255) == 9,
          "one-R8-code local variation creates exact 3x3 kernel neighborhood");
  require(linear[0] == 0 && linear[1 * 6 + 2] == 255 && linear[3 * 6 + 4] == 255,
          "3x3 minmax uses RAW/4 dimensions and coordinates");

  const int rw = 61, rh = 45, sw = 31, sh = 23, mw = 15, mh = 11;
  source.resize(sw * sh);
  for (int y = 0; y < sh; ++y)
    for (int x = 0; x < sw; ++x) source[y * sw + x] = (x * 17 + y * 39 + x * y * 3) % 256;
  require(highlightBuildMergeMasks(source, sw, sh, rw, rh, true, &selected, &linear, &error), error);
  require(selected.size() == std::size_t(mw * mh), "PC odd RAW/4 dimensions use floor not ceil");
  for (int y = 0; y < mh; ++y)
    for (int x = 0; x < mw; ++x) {
      // Independent double-precision, four-tap tensor-product oracle. Exclude
      // only mathematical half-byte ties, where GPU float interpolation decides.
      const double sx = (x + .5) * sw / mw - .5, sy = (y + .5) * sh / mh - .5;
      const int ix = int(std::floor(sx)), iy = int(std::floor(sy));
      double v = 0;
      for (int dy = 0; dy < 2; ++dy)
        for (int dx = 0; dx < 2; ++dx) {
          const int xx = std::max(0, std::min(sw - 1, ix + dx));
          const int yy = std::max(0, std::min(sh - 1, iy + dy));
          v += source[yy * sw + xx] * (dx ? sx - ix : 1 - (sx - ix)) *
                                             (dy ? sy - iy : 1 - (sy - iy));
        }
      const double referenceCode = 255 - v;
      if (std::abs(referenceCode - std::floor(referenceCode) - .5) > .0005)
        require(selected[y * mw + x] == 255 - std::lround(referenceCode),
                "odd extent samples destination UV center, not fixed 2x2 source box");
      int lo = 255, hi = 0;
      for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
          const int xx = std::max(0, std::min(mw - 1, x + dx));
          const int yy = std::max(0, std::min(mh - 1, y + dy));
          lo = std::min(lo, int(selected[yy * mw + xx]));
          hi = std::max(hi, int(selected[yy * mw + xx]));
        }
      require(linear[y * mw + x] == (lo != hi ? 255 : 0), "odd extents exact 3x3 RAW/4 minmax");
    }
  // A nonsymmetric pattern and swapped non-square domains catch row/column and
  // stride transposition bugs that uniform or square-only tests cannot expose.
  std::vector<uint8_t> transposed(source.size()), selectedT, linearT;
  for (int y = 0; y < sh; ++y)
    for (int x = 0; x < sw; ++x) transposed[x * sh + y] = source[y * sw + x];
  require(highlightBuildMergeMasks(transposed, sh, sw, rh, rw, true, &selectedT, &linearT, &error), error);
  for (int y = 0; y < mh; ++y)
    for (int x = 0; x < mw; ++x) {
      require(selectedT[x * mh + y] == selected[y * mw + x], "merge selection transpose covariance");
      require(linearT[x * mh + y] == linear[y * mw + x], "linear kernel transpose covariance");
    }
  require(highlightBuildMergeMasks(source, sw, sh, rw, rh, false, &selected, &linear, &error), error);
  require(selected == std::vector<uint8_t>(mw * mh, 0) && linear == selected,
          "whole-frame rejection clears selected and linear masks");
  require(!highlightBuildMergeMasks(source, sh, sw, rw, rh, true, &selected, &linear, &error),
          "transposed source metadata without RAW transpose rejected");
  require(selected.empty() && linear.empty(), "invalid merge-mask dimensions clear outputs");
  require(!highlightBuildMergeMasks(source, sw, sh, rw, rh, true, &selected, &selected, &error),
          "merge-mask output aliases rejected");
  std::cout << "PASS merge masks odd dimensions, complementary R8 quantization, 3x3, transpose, rejection\n";
}
void invalidInput() {
  Raw raw;
  raw.fill({{.1f, .1f, .1f, .1f}});
  auto view = raw.view();
  HighlightGuide guide;
  std::string error;
  view.cfaLut = 0;
  require(!highlightGenerateGuide(view, identity, 1, noiseless, &guide, &error), "bad CFA rejected");
  view = raw.view(); view.stride = 1;
  require(!highlightGenerateGuide(view, identity, 1, noiseless, &guide, &error), "bad RAW stride rejected");
  view = raw.view();
  HighlightNoise badNoise; badNoise.read[2] = std::numeric_limits<float>::quiet_NaN();
  require(!highlightGenerateGuide(view, identity, 1, badNoise, &guide, &error), "nonfinite noise rejected");
  require(guide.rgb.empty(), "failed output cleared");
  auto g = generate(raw, raw);
  float flow[2] = {std::numeric_limits<float>::quiet_NaN(), 0.f};
  HighlightFlowView fv; fv.xy = flow; fv.width = 1; fv.height = 1; fv.strideRaw = 64;
  HighlightMaskAssessment a;
  require(!highlightAssessMasks(view, g, fv, true, &a, &error), "nonfinite flow rejected");
  require(a.status == HighlightAdmission::InvalidInput && a.adjusted.empty(), "assessment fails closed");
  std::cout << "PASS malformed/nonfinite inputs fail closed\n";
}
}  // namespace
int main() {
  recoverableAndColor();
  diagnosticOnly();
  admissionStates();
  nativeThresholdAndScaling();
  noiseAndBayerLayouts();
  exactMaskBoundariesAndConnectivity();
  mirroredBilinearAndClipFootprint();
  pcQuantizationFlowAndGuideBorders();
  mergeMasks();
  invalidInput();
  std::cout << "PASS all " << assertions << " highlight recovery assertions\n";
}
