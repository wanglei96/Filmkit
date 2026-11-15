// Standalone CPU regression; no Android, GL, CMake, RAW fixture or daylight needed.
// c++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread -Icore/engine/src/render \
//   tools/check-highlight-geometry.cpp core/engine/src/render/HighlightGeometry.cpp \
//   core/engine/src/render/Align.cpp -o /tmp/check-highlight-geometry
// /tmp/check-highlight-geometry
#include "HighlightGeometry.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

using namespace filmkit;
namespace {
int checks = 0;
void check(bool b, const std::string& message) {
  ++checks;
  if (!b) throw std::runtime_error(message);
}
void near(float actual, float expected, const std::string& message, float eps = 1e-5f) {
  check(std::isfinite(actual) && std::abs(actual - expected) <= eps,
        message + " actual=" + std::to_string(actual) + " expected=" + std::to_string(expected));
}
HighlightGeometryGrid grid(int nx, int ny, int stride = 8) {
  return {nx * stride, ny * stride, nx, ny, stride, 0.5f};
}
struct Fixture {
  HighlightGeometryGrid g;
  std::vector<float> base, short_rgb, forward, reverse;
  std::vector<uint8_t> mask;
  std::vector<HighlightTileSupport> support;
  explicit Fixture(HighlightGeometryGrid v) : g(v),
      base(static_cast<size_t>(g.width) * g.height * 3, 0.25f), short_rgb(base),
      forward(static_cast<size_t>(g.grid_width) * g.grid_height * 2, 0.f),
      reverse(forward), mask(static_cast<size_t>(g.width) * g.height, 0) {}
  void run() {
    check(highlightObservableSupport(g, base, short_rgb, mask, forward, reverse, &support),
          "support shape valid");
  }
  void highlight(int tx, int ty, int dx = 0, int dy = 0) {
    mask[static_cast<size_t>(ty * g.tile_stride + dy) * g.width +
         tx * g.tile_stride + dx] = 255;
  }
  void donors(float confidence = 1.f) {
    support.assign(static_cast<size_t>(g.grid_width) * g.grid_height, {});
    for (auto& s : support) s.confidence = confidence;
  }
  HighlightGeometryResult resolve() const {
    return highlightResolveGeometry(g, mask, forward, support);
  }
};

void testSupport() {
  Fixture f(grid(5, 5));
  f.run();
  for (const auto& s : f.support) {
    near(s.confidence, 1.f, "identity support");
    near(s.fb_error, 0.f, "identity cycle");
    near(s.valid_fraction, 1.f, "16 observations");
    near(s.clipped_fraction, 0.f, "not clipped");
  }
  // A cycle is evaluated on the mapped reverse tile, not the original tile.
  const size_t t = 6;
  f.forward[t * 2] = 16.f;  // +8 guide pixels = one whole PC tile
  f.reverse[7 * 2] = -16.f;
  f.reverse[t * 2] = 200.f;
  f.run();
  near(f.support[t].confidence, 1.f, "reverse lookup at mapped tile");
  f.reverse[7 * 2] = -10.f;  // fb=3 guide -> hard rejection
  f.run();
  near(f.support[t].confidence, 0.f, "inconsistent cycle rejected");
  near(f.support[t].fb_error, 3.f, "cycle metric uses guide not mosaic units");
  near(f.support[t].valid_fraction, 0.f, "early FB rejection has no observations");
  f.reverse[7 * 2] = -13.25f;  // fb=1.375 midpoint of .75..2 -> .5
  f.run();
  near(f.support[t].confidence, 0.5f, "continuous FB weighting");
  f.reverse[7 * 2] = -12.f;
  f.run();
  near(f.support[t].confidence, 0.f, "fb=2 accepted but has zero weight");
  f.forward[t * 2] = std::numeric_limits<float>::quiet_NaN();
  f.run();
  check(std::isinf(f.support[t].fb_error), "invalid flow does not fake perfect cycle");
  near(f.support[t].confidence, 0.f, "NaN forward rejected");
  f.forward[t * 2] = 1000000.f;
  f.run();
  near(f.support[t].confidence, 0.f, "off-frame forward rejected");
  f.forward.assign(f.forward.size(), 0.f);
  f.reverse.assign(f.reverse.size(), 0.f);
  f.reverse[t * 2] = std::numeric_limits<float>::infinity();
  f.run();
  near(f.support[t].confidence, 0.f, "Inf reverse rejected");

  Fixture photometric(grid(3, 3));
  for (auto& x : photometric.short_rgb) x += 0.10f;
  photometric.run();
  near(photometric.support[4].confidence, 0.5f, "RGB agreement midpoint", 2e-5f);
  // Green matters twice as much as red/blue: this is not a scalar luma shortcut.
  photometric.short_rgb = photometric.base;
  for (size_t p = 0; p < photometric.mask.size(); ++p)
    photometric.short_rgb[p * 3 + 1] += 0.20f;
  photometric.run();
  near(photometric.support[4].confidence, 0.5f, "phase/color weighted agreement", 2e-5f);
  for (auto& x : photometric.short_rgb) x = 3.f;
  photometric.run();
  near(photometric.support[4].valid_fraction, 0.f, "guide outliers unobservable");
  near(photometric.support[4].confidence, 0.f, "no observation not identity");
  for (auto& x : photometric.short_rgb) x = std::numeric_limits<float>::quiet_NaN();
  photometric.run();
  near(photometric.support[4].valid_fraction, 0.f, "NaN guides excluded");
  near(photometric.support[4].confidence, 0.f, "NaN guides cannot grant support");

  Fixture observation_count(grid(1, 1));
  std::fill(observation_count.base.begin(), observation_count.base.end(), 3.f);
  for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x)
    for (int c = 0; c < 3; ++c)
      observation_count.base[(static_cast<size_t>(2 * y + 1) * 8 + 2 * x + 1) * 3 + c] = .25f;
  observation_count.run();
  near(observation_count.support[0].valid_fraction, .25f, "exactly four spatial observations");
  near(observation_count.support[0].confidence, 1.f, "four observations meet hard minimum");
  observation_count.base[(3 * 8 + 3) * 3] = 3.f;
  observation_count.run();
  near(observation_count.support[0].valid_fraction, 3.f / 16.f, "only three spatial observations");
  near(observation_count.support[0].confidence, 0.f, "three observations fail hard minimum");

  Fixture clipped(grid(3, 3));
  clipped.highlight(1, 1, 1, 1);  // first of 4x4 PC sample sites for stride 8
  clipped.run();
  near(clipped.support[4].clipped_fraction, 1.f / 16.f, "clipped observation counted");
  near(clipped.support[4].valid_fraction, 15.f / 16.f, "clipped excluded from valid");
  near(clipped.support[4].confidence, 0.f, "single clip vetoes donor");

  // Nonuniform linear guide + fractional displacement checks GL_LINEAR behavior.
  Fixture fractional(grid(3, 3));
  for (int y = 0; y < fractional.g.height; ++y)
    for (int x = 0; x < fractional.g.width; ++x)
      for (int c = 0; c < 3; ++c) {
        const size_t o = (static_cast<size_t>(y) * fractional.g.width + x) * 3 + c;
        fractional.base[o] = 0.2f + 0.01f * x + 0.005f * y + 0.01f * c;
        fractional.short_rgb[o] = fractional.base[o] - 0.0075f;
      }
  for (size_t i = 0; i < fractional.forward.size(); i += 2) {
    fractional.forward[i] = 1.f; fractional.forward[i + 1] = 1.f;
    fractional.reverse[i] = -1.f; fractional.reverse[i + 1] = -1.f;
  }
  fractional.run();
  near(fractional.support[4].confidence, 1.f, "fractional bilinear RGB alignment");

  Fixture edge({19, 18, 3, 3, 8, .5f});
  edge.run();
  near(edge.support[8].confidence, 0.f, "partial tile center outside guide unobservable");
  check(std::isinf(edge.support[8].fb_error), "outside center lacks cycle measurement");
  // Center is inside, but only one sample row is inside: exactly four observations.
  Fixture four({24, 13, 3, 2, 8, .5f});
  four.run();
  near(four.support[3].valid_fraction, 0.5f, "partial tile has eight samples");
  Fixture few({24, 12, 3, 2, 8, .5f});
  few.run();
  near(few.support[3].confidence, 0.f, "center at image limit is not observable");

  auto invalid_grid = f.g;
  invalid_grid.tile_stride = 0;
  check(!highlightObservableSupport(invalid_grid, f.base, f.short_rgb, f.mask,
                                   f.forward, f.reverse, &f.support), "zero stride rejected");
  check(f.support.empty(), "invalid support clears stale result");
  check(!highlightObservableSupport(f.g, f.base, f.short_rgb, f.mask,
                                   f.forward, f.reverse, nullptr), "null output rejected");
  f.forward.pop_back();
  check(!highlightObservableSupport(f.g, f.base, f.short_rgb, f.mask,
                                   f.forward, f.reverse, &f.support), "short flow rejected");
}

void testAdmission() {
  Fixture empty(grid(2, 2));
  empty.donors(0.f);
  auto r = empty.resolve();
  check(r.status == HighlightGeometryStatus::NoHighlights && r.usable(),
        "no highlights succeeds without global support");
  check(r.flow == empty.forward && r.regions == 0, "no-op flow preserved");
  empty.highlight(0, 0);
  r = empty.resolve();
  check(r.status == HighlightGeometryStatus::NoObservableSupport && !r.usable() && r.flow.empty(),
        "no observations rejects injection, not fallback to plateau flow");
  empty.donors();
  r = empty.resolve();
  check(!r.usable() && r.supported_tiles == 3, "three exterior donors insufficient");

  Fixture f(grid(7, 7));
  f.donors();
  f.highlight(3, 3);  // NOT a 4x4 shader sample; must still mark whole tile highlight
  const int center = 3 * 7 + 3;
  for (size_t t = 0; t < f.support.size(); ++t) {
    f.forward[t * 2] = 2.f; f.forward[t * 2 + 1] = -4.f;
  }
  f.forward[center * 2] = 999.f;
  f.forward[center * 2 + 1] = -999.f;
  r = f.resolve();
  check(r.status == HighlightGeometryStatus::Resolved && r.regions == 1 &&
        r.collar_regions == 1 && r.global_regions == 0, "collar region admitted");
  check(r.supported_tiles == 48 && r.highlight_tiles == 1, "mask excludes false-positive support");
  near(r.flow[center * 2], 2.f, "plateau x replaced");
  near(r.flow[center * 2 + 1], -4.f, "plateau y replaced");
  for (size_t t = 0; t < f.support.size(); ++t) if (t != static_cast<size_t>(center)) {
    near(r.flow[t * 2], f.forward[t * 2], "non-highlight x unchanged");
    near(r.flow[t * 2 + 1], f.forward[t * 2 + 1], "non-highlight y unchanged");
  }
  f.highlight(4, 4);  // diagonal contact -> same component
  r = f.resolve();
  check(r.regions == 1 && r.highlight_tiles == 2, "8-connected diagonal grouping");
  f.highlight(0, 6);  // unsupported exterior must not bridge these groups
  f.support[5 * 7 + 1].confidence = 0.f;
  f.support[4 * 7 + 2].confidence = 0.f;
  r = f.resolve();
  check(r.regions == 2 && r.highlight_tiles == 3, "unsupported exterior is not a highlight region");

  // Explicit inverse-square confidence weighting; finite rejected exterior is not fixed.
  Fixture weighted(grid(7, 7));
  weighted.donors(0.f);
  weighted.highlight(3, 3);
  const int donor[] = {3 * 7 + 2, 3 * 7 + 4, 1 * 7 + 3, 5 * 7 + 3};
  const float confidence[] = {1.f, .4f, .8f, .6f};
  const float value[] = {2.f, 10.f, 20.f, 30.f};
  double sum = 0, total = 0;
  for (int i = 0; i < 4; ++i) {
    weighted.support[donor[i]].confidence = confidence[i];
    weighted.forward[donor[i] * 2] = value[i];
    weighted.forward[donor[i] * 2 + 1] = -2.f * value[i];
    const double w = confidence[i] / (i < 2 ? 1. : 4.);
    total += w; sum += w * value[i];
  }
  weighted.forward[0] = 1234.f;
  r = weighted.resolve();
  near(r.flow[center * 2], static_cast<float>(sum / total), "confidence/distance2 interpolation");
  near(r.flow[center * 2 + 1], static_cast<float>(-2. * sum / total), "vector interpolation");
  near(r.flow[0], 1234.f, "unobservable exterior preserved");

  // Global fallback is per-axis weighted median, not mean, vector median, upper
  // even-count median or solver's gx/gy. Local collar has fewer than four donors.
  Fixture global(grid(16, 8));
  global.donors(0.f);
  global.highlight(0, 0);
  const int far[] = {15, 31, 47, 63};
  const float xs[] = {0.f, 10.f, 20.f, 100.f};
  const float ys[] = {80.f, 60.f, -10.f, -40.f};
  for (int i = 0; i < 4; ++i) {
    global.support[far[i]].confidence = 1.f;
    global.forward[far[i] * 2] = xs[i];
    global.forward[far[i] * 2 + 1] = ys[i];
  }
  r = global.resolve();
  check(r.global_regions == 1 && r.collar_regions == 0, "sparse collar uses global donors");
  near(r.flow[0], 10.f, "PC lower median on even equal weights");
  near(r.flow[1], -10.f, "global median independently sorts y");
  global.support[far[3]].confidence = 4.f;
  r = global.resolve();
  near(r.flow[0], 100.f, "confidence-weighted global x median");
  near(r.flow[1], -40.f, "confidence-weighted global y median");
  global.support[far[3]].confidence = std::numeric_limits<float>::quiet_NaN();
  r = global.resolve();
  check(!r.usable() && r.flow.empty() && r.supported_tiles == 3, "nonfinite support excluded");
  global.support[far[3]].confidence = 1.f;
  global.forward[far[3] * 2] = std::numeric_limits<float>::infinity();
  r = global.resolve();
  check(!r.usable(), "nonfinite donor flow excluded");

  // Radius must not count the same clamped border donor multiple times.
  Fixture border(grid(6, 6));
  border.donors(0.f); border.highlight(0, 0);
  for (int i : {1, 6, 7, 35}) border.support[i].confidence = 1.f;
  r = border.resolve();
  check(r.collar_regions == 0 && r.global_regions == 1, "three unique border donors cannot form collar");
  // Shape errors are distinguishable from physically absent observations.
  border.mask.pop_back();
  r = border.resolve();
  check(r.status == HighlightGeometryStatus::InvalidInput && r.flow.empty(), "mask shape rejected");
}

MfnrPyramid pyramid(int n, int dx, int dy) {
  MfnrPyramid p;
  p.w1 = p.h1 = n;
  p.w2 = p.h2 = (n + 1) / 2;
  p.w3 = p.h3 = (p.w2 + 1) / 2;
  p.w4 = p.h4 = (p.w3 + 1) / 2;
  p.l1.resize(static_cast<size_t>(n) * n);
  auto signal = [](int x, int y) {
    // Nonperiodic multiscale texture: a sinusoid-only fixture has genuine distant
    // coarse-level aliases and is not a unique-translation oracle for SAD.
    auto hash = [](int ix, int iy) {
      uint32_t v = static_cast<uint32_t>(ix) * 0x9e3779b9u +
                   static_cast<uint32_t>(iy) * 0x85ebca6bu;
      v ^= v >> 16; v *= 0x7feb352du; v ^= v >> 15;
      return static_cast<float>(v & 255u);
    };
    auto noise = [&](float scale) {
      const float px = x / scale, py = y / scale;
      const int ix = static_cast<int>(std::floor(px)), iy = static_cast<int>(std::floor(py));
      const float fx = px - ix, fy = py - iy;
      return (hash(ix, iy) * (1.f - fx) + hash(ix + 1, iy) * fx) * (1.f - fy) +
             (hash(ix, iy + 1) * (1.f - fx) + hash(ix + 1, iy + 1) * fx) * fy;
    };
    return static_cast<uint8_t>(20.f + .65f * noise(16.f) + .2f * noise(4.f));
  };
  for (int y = 0; y < n; ++y) for (int x = 0; x < n; ++x)
    p.l1[static_cast<size_t>(y) * n + x] = signal(x - dx, y - dy);
  auto down = [](const std::vector<uint8_t>& src, int w, int h) {
    const int ow = (w + 1) / 2, oh = (h + 1) / 2;
    std::vector<uint8_t> dst(static_cast<size_t>(ow) * oh);
    for (int y = 0; y < oh; ++y) for (int x = 0; x < ow; ++x) {
      int sum = 0;
      for (int j = 0; j < 2; ++j) for (int i = 0; i < 2; ++i)
        sum += src[static_cast<size_t>(std::min(2 * y + j, h - 1)) * w + std::min(2 * x + i, w - 1)];
      dst[static_cast<size_t>(y) * ow + x] = static_cast<uint8_t>((sum + 2) / 4);
    }
    return dst;
  };
  p.l2 = down(p.l1, p.w1, p.h1);
  p.l3 = down(p.l2, p.w2, p.h2);
  p.l4 = down(p.l3, p.w3, p.h3);
  return p;
}

void testAlign() {
  auto a = pyramid(256, 0, 0), b = pyramid(256, 4, 2);
  const auto both = highlightAlignBidirectional(a, b, 4, 4);
  check(both.valid && both.forward.used_l1 && both.reverse.used_l1, "real bidirectional Align reused");
  const auto direct_forward = mfnrAlign(a, b, 4, 4);
  const auto direct_reverse = mfnrAlign(b, a, 4, 4);
  check(both.forward.off == direct_forward.off, "forward identical to independent Align call");
  check(both.reverse.off == direct_reverse.off, "reverse independently recomputed, not negated");
  for (int t : {5, 6, 9, 10}) {
    near(both.forward.off[t * 2], 8.f, "known translation forward mosaic x", 1.f);
    near(both.forward.off[t * 2 + 1], 4.f, "known translation forward mosaic y", 1.f);
    near(both.reverse.off[t * 2], -8.f, "known translation reverse mosaic x", 1.f);
    near(both.reverse.off[t * 2 + 1], -4.f, "known translation reverse mosaic y", 1.f);
  }
  const auto fine = highlightAlignBidirectional(a, b, 32, 32, true, 8);
  check(fine.valid && fine.forward.off.size() == 2048 && fine.reverse.off.size() == 2048,
        "eight-quad grid genuinely solved in both directions");
  check(fine.forward.off == mfnrAlign(a, b, 32, 32, true, 8).off &&
        fine.reverse.off == mfnrAlign(b, a, 32, 32, true, 8).off,
        "fine grid wrapper passes tile size to independent solver calls");
  std::vector<float> errors;
  for (int y = 4; y < 28; ++y) for (int x = 4; x < 28; ++x) {
    const size_t t = static_cast<size_t>(y) * 32 + x;
    errors.push_back(std::hypot(fine.forward.off[t * 2] - 8.f,
                                fine.forward.off[t * 2 + 1] - 4.f));
  }
  std::sort(errors.begin(), errors.end());
  std::cout << "fine8 translation mosaic error p50=" << errors[errors.size() / 2]
            << " p90=" << errors[errors.size() * 9 / 10] << '\n';
  check(errors[errors.size() / 2] < 1.f, "fine-grid translation median below one mosaic pixel");
  Fixture fine_support(grid(32, 32));
  for (size_t p = 0; p < a.l1.size(); ++p) for (int c = 0; c < 3; ++c) {
    fine_support.base[p * 3 + c] = a.l1[p] / 255.f;
    fine_support.short_rgb[p * 3 + c] = b.l1[p] / 255.f;
  }
  fine_support.forward = fine.forward.off;
  fine_support.reverse = fine.reverse.off;
  fine_support.highlight(16, 16);
  fine_support.run();
  auto admitted = fine_support.resolve();
  std::vector<float> accepted_errors;
  size_t false_matches = 0;
  for (int y = 4; y < 28; ++y) for (int x = 4; x < 28; ++x) {
    const size_t t = static_cast<size_t>(y) * 32 + x;
    if (fine_support.support[t].confidence < .4f) continue;
    const float err = std::hypot(fine.forward.off[t * 2] - 8.f,
                                 fine.forward.off[t * 2 + 1] - 4.f);
    accepted_errors.push_back(err);
    if (err > 2.f) ++false_matches;
  }
  std::sort(accepted_errors.begin(), accepted_errors.end());
  check(!accepted_errors.empty(), "fine-grid support has interior observations");
  std::cout << "fine8 supported=" << accepted_errors.size() << "/" << errors.size()
            << " error p50=" << accepted_errors[accepted_errors.size() / 2]
            << " p90=" << accepted_errors[accepted_errors.size() * 9 / 10]
            << " error>2mosaic=" << false_matches << '\n';
  check(admitted.usable() && admitted.regions == 1 && admitted.supported_tiles >= 4,
        "fine-grid real Align produces observable collar admission");
  // Break ALL reverse cycles with an impossible return. A forward match alone
  // must never admit the short, even if RGB agreement and reference clipping pass.
  std::fill(fine_support.reverse.begin(), fine_support.reverse.end(), 20000.f);
  fine_support.run();
  admitted = fine_support.resolve();
  check(admitted.status == HighlightGeometryStatus::NoObservableSupport &&
        admitted.supported_tiles == 0 && admitted.flow.empty(),
        "fine-grid broken backward solution rejects highlight injection");
  check(!highlightAlignBidirectional(a, b, 32, 32, true, 6).valid,
        "invalid fine stride rejected before Align");
  check(!highlightAlignBidirectional(a, b, 5, 4).valid, "Align grid mismatch rejected before solver");
  b.l3.pop_back();
  check(!highlightAlignBidirectional(a, b, 4, 4).valid, "short pyramid rejected before solver");
  b = a;
  a.l1.clear(); b.l1.clear(); a.w1 = a.h1 = b.w1 = b.h1 = 0;
  const auto old = highlightAlignBidirectional(a, b, 4, 4);
  check(old.valid && !old.forward.used_l1 && !old.reverse.used_l1, "valid legacy pyramid explicitly supported");
  a.w4 = 0;
  check(!highlightAlignBidirectional(a, b, 4, 4).valid, "invalid coarse level fails closed");
}
}  // namespace

int main() {
  try {
    testSupport();
    testAdmission();
    testAlign();
    std::cout << "PASS highlight geometry: " << checks
              << " checks (FB vector cycles, RGB observable support, region collars, weighted median, real Align)\n";
    return 0;
  } catch (const std::exception& e) {
    std::cerr << "FAIL after " << checks << " checks: " << e.what() << '\n';
    return 1;
  }
}
