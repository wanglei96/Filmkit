// c++ -std=c++17 -O3 -Wall -Wextra -Werror tools/check-histogram-weights.cpp -o /tmp/check-histogram-weights
#include "../core/engine/src/render/HistogramWeights.h"

#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {
constexpr int hw = 512, hh = 288;
using Histogram = std::array<double, 256>;
float original(int x, int y) {
  const float cx = hw * 0.5f, cy = hh * 0.5f;
  const float inv_hw2 = 1.0f / (hw * 0.5f), inv_hh2 = 1.0f / (hh * 0.5f);
  constexpr float kSigma = 0.32f;
  const float dx = (x + 0.5f - cx) * inv_hw2;
  const float dy = (y + 0.5f - cy) * inv_hh2;
  const float g = std::exp(-0.5f * (dx * dx + dy * dy) / (kSigma * kSigma));
  return 0.35f + 0.65f * g;
}
Histogram histogram(const std::vector<unsigned char>& pixels, bool cached,
                    float fraction, int bins) {
  Histogram out{};
  const auto& weights = filmkit::histogramCenterWeights();
  const bool center_only = fraction > 0.0f && fraction < 1.0f;
  const float half_w = hw * fraction * 0.5f, half_h = hh * fraction * 0.5f;
  for (int y = 0; y < hh; ++y) {
    if (center_only && std::fabs(y + 0.5f - hh * 0.5f) > half_h) continue;
    for (int x = 0; x < hw; ++x) {
      if (center_only && std::fabs(x + 0.5f - hw * 0.5f) > half_w) continue;
      const size_t i = static_cast<size_t>(y) * hw + x;
      out[pixels[i] * (bins - 1) / 255] += static_cast<double>(cached ? weights[i] : original(x, y));
    }
  }
  return out;
}
volatile double sink;
}  // namespace

int main() {
  const auto& weights = filmkit::histogramCenterWeights();
  for (int y = 0; y < hh; ++y) for (int x = 0; x < hw; ++x) {
    const float reference = original(x, y);
    if (std::memcmp(&reference, &weights[static_cast<size_t>(y) * hw + x], sizeof(float))) {
      std::printf("FAIL weight %d,%d\n", x, y); return 1;
    }
  }
  std::vector<unsigned char> pixels(hw * hh);
  unsigned random = 1234567u;
  for (auto& pixel : pixels) { random = random * 1664525u + 1013904223u; pixel = random >> 24; }
  int fixtures = 0;
  for (int bins : {2, 16, 64, 256}) for (float fraction : {0.f, .5f, 2.f/3.f, 1.f}) {
    const auto a = histogram(pixels, false, fraction, bins);
    const auto b = histogram(pixels, true, fraction, bins);
    if (std::memcmp(a.data(), b.data(), sizeof(a))) {
      std::printf("FAIL weighted bins=%d fraction=%.8f\n", bins, fraction); return 1;
    }
    ++fixtures;
  }
  std::printf("PASS %zu bit-identical weights and %d complete weighted histograms\n", weights.size(), fixtures);
  for (int round = 0; round < 6; ++round) {
    const bool cached = round % 2 != 0;
    const auto start = std::chrono::steady_clock::now();
    for (int repeat = 0; repeat < 100; ++repeat) {
      const auto h = histogram(pixels, cached, 0.f, 256);
      sink = h[repeat % 256];
    }
    std::printf("BENCH cached=%d ms_per_frame=%.6f\n", cached,
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now()-start).count()/100.0);
  }
}
