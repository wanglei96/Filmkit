#pragma once

#include <array>
#include <cmath>

namespace filmkit {

inline constexpr int kHistogramWidth = 512;
inline constexpr int kHistogramHeight = 288;

// Cache geometry only, never pixels, bins or statistics from an earlier frame.
inline const std::array<float, kHistogramWidth * kHistogramHeight>&
histogramCenterWeights() {
  static const auto weights = [] {
    constexpr int hw = kHistogramWidth, hh = kHistogramHeight;
    std::array<float, hw * hh> out{};
    const float cx = hw * 0.5f, cy = hh * 0.5f;
    const float inv_hw2 = 1.0f / (hw * 0.5f), inv_hh2 = 1.0f / (hh * 0.5f);
    constexpr float kSigma = 0.32f;
    for (int y = 0; y < hh; ++y) {
      for (int x = 0; x < hw; ++x) {
        const float dx = (x + 0.5f - cx) * inv_hw2;
        const float dy = (y + 0.5f - cy) * inv_hh2;
        const float g = std::exp(-0.5f * (dx * dx + dy * dy) / (kSigma * kSigma));
        out[static_cast<size_t>(y) * hw + x] = 0.35f + 0.65f * g;
      }
    }
    return out;
  }();
  return weights;
}

}  // namespace filmkit
