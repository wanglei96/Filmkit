#include "HighlightGeometry.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <numeric>
#include <utility>

namespace filmkit {
namespace {

constexpr int kCollarRadius = 4;
constexpr int kMinCollarTiles = 4;
constexpr float kMinSupport = 0.4f;

bool imageCount(int w, int h, size_t channels, size_t* count) {
  if (w <= 0 || h <= 0 || channels == 0) return false;
  const size_t sw = static_cast<size_t>(w), sh = static_cast<size_t>(h);
  if (sw > std::numeric_limits<size_t>::max() / sh / channels) return false;
  *count = sw * sh * channels;
  return true;
}

bool validGrid(const HighlightGeometryGrid& g, size_t* pixels, size_t* tiles) {
  if (!imageCount(g.width, g.height, 1, pixels) ||
      !imageCount(g.grid_width, g.grid_height, 1, tiles) ||
      *tiles > static_cast<size_t>(std::numeric_limits<int>::max()) ||
      g.tile_stride <= 0 || !std::isfinite(g.flow_to_guide) ||
      g.flow_to_guide <= 0.f) return false;
  // Exact coverage avoids a silent domain/stride mismatch at integration time.
  return g.grid_width == 1 + (g.width - 1) / g.tile_stride &&
         g.grid_height == 1 + (g.height - 1) / g.tile_stride;
}

bool validLevel(const std::vector<uint8_t>& a, const std::vector<uint8_t>& b,
                int aw, int ah, int bw, int bh) {
  size_t n = 0;
  return aw == bw && ah == bh && imageCount(aw, ah, 1, &n) &&
         a.size() == n && b.size() == n;
}

float smoothstep(float lo, float hi, float x) {
  const float t = std::max(0.f, std::min(1.f, (x - lo) / (hi - lo)));
  return t * t * (3.f - 2.f * t);
}

bool boundedFlow(float x, float y) {
  // Same strict limit as the shader; NaN/Inf also fail these comparisons.
  return std::abs(x) < 1.0e5f && std::abs(y) < 1.0e5f;
}

float rgbAt(const std::vector<float>& rgb, int width, int x, int y, int c) {
  return rgb[(static_cast<size_t>(y) * width + x) * 3 + c];
}

float bilinear(const std::vector<float>& rgb, int w, int h,
               float x, float y, int c) {
  const int x0 = static_cast<int>(std::floor(x));
  const int y0 = static_cast<int>(std::floor(y));
  const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
  const float fx = x - x0, fy = y - y0;
  const float a = rgbAt(rgb, w, x0, y0, c);
  const float b = rgbAt(rgb, w, x1, y0, c);
  const float d = rgbAt(rgb, w, x0, y1, c);
  const float e = rgbAt(rgb, w, x1, y1, c);
  return (a * (1.f - fx) + b * fx) * (1.f - fy) +
         (d * (1.f - fx) + e * fx) * fy;
}

}  // namespace

HighlightBidirectionalFlow highlightAlignBidirectional(
    const MfnrPyramid& base, const MfnrPyramid& short_frame,
    int grid_width, int grid_height, bool allow_l1, int tile_stride) {
  HighlightBidirectionalFlow result;
  size_t tiles = 0;
  if (tile_stride < 4 || tile_stride % 4 != 0 ||
      !imageCount(grid_width, grid_height, 1, &tiles) ||
      tiles > static_cast<size_t>(std::numeric_limits<int>::max()) ||
      !validLevel(base.l2, short_frame.l2, base.w2, base.h2,
                  short_frame.w2, short_frame.h2) ||
      !validLevel(base.l3, short_frame.l3, base.w3, base.h3,
                  short_frame.w3, short_frame.h3) ||
      !validLevel(base.l4, short_frame.l4, base.w4, base.h4,
                  short_frame.w4, short_frame.h4) ||
      base.w3 != 1 + (base.w2 - 1) / 2 ||
      base.h3 != 1 + (base.h2 - 1) / 2 ||
      base.w4 != 1 + (base.w3 - 1) / 2 ||
      base.h4 != 1 + (base.h3 - 1) / 2) return result;

  const bool have_l1 = !base.l1.empty() || !short_frame.l1.empty() ||
                       base.w1 != 0 || base.h1 != 0 ||
                       short_frame.w1 != 0 || short_frame.h1 != 0;
  if (have_l1 &&
      (!validLevel(base.l1, short_frame.l1, base.w1, base.h1,
                   short_frame.w1, short_frame.h1) ||
       base.w2 != 1 + (base.w1 - 1) / 2 ||
       base.h2 != 1 + (base.h1 - 1) / 2)) return result;
  const int tile = have_l1 ? tile_stride : tile_stride / 2;
  const int width = have_l1 ? base.w1 : base.w2;
  const int height = have_l1 ? base.h1 : base.h2;
  if (grid_width != 1 + (width - 1) / tile ||
      grid_height != 1 + (height - 1) / tile) return result;

  result.forward = mfnrAlign(base, short_frame, grid_width, grid_height, allow_l1, tile_stride);
  result.reverse = mfnrAlign(short_frame, base, grid_width, grid_height, allow_l1, tile_stride);
  result.valid = result.forward.off.size() == tiles * 2 &&
                 result.reverse.off.size() == tiles * 2;
  return result;
}

bool highlightObservableSupport(
    const HighlightGeometryGrid& g,
    const std::vector<float>& base_rgb, const std::vector<float>& short_rgb,
    const std::vector<uint8_t>& mask,
    const std::vector<float>& forward, const std::vector<float>& reverse,
    std::vector<HighlightTileSupport>* output) {
  if (!output) return false;
  output->clear();
  size_t pixels = 0, tiles = 0, rgb_count = 0;
  if (!validGrid(g, &pixels, &tiles) ||
      !imageCount(g.width, g.height, 3, &rgb_count) ||
      base_rgb.size() != rgb_count || short_rgb.size() != rgb_count ||
      mask.size() != pixels || forward.size() != tiles * 2 ||
      reverse.size() != tiles * 2) return false;
  output->resize(tiles);
  for (int ty = 0; ty < g.grid_height; ++ty) {
    for (int tx = 0; tx < g.grid_width; ++tx) {
      const size_t t = static_cast<size_t>(ty) * g.grid_width + tx;
      auto& s = (*output)[t];
      const float fx = forward[t * 2] * g.flow_to_guide;
      const float fy = forward[t * 2 + 1] * g.flow_to_guide;
      const float mx = (tx + 0.5f) * g.tile_stride + fx;
      const float my = (ty + 0.5f) * g.tile_stride + fy;
      if (!boundedFlow(fx, fy) || !(mx >= 0.f && my >= 0.f) ||
          !(mx < g.width && my < g.height)) continue;
      const int rx = std::min(static_cast<int>(std::floor(mx / g.tile_stride)),
                              g.grid_width - 1);
      const int ry = std::min(static_cast<int>(std::floor(my / g.tile_stride)),
                              g.grid_height - 1);
      const size_t r = static_cast<size_t>(ry) * g.grid_width + rx;
      const float bx = reverse[r * 2] * g.flow_to_guide;
      const float by = reverse[r * 2 + 1] * g.flow_to_guide;
      if (!boundedFlow(bx, by)) continue;
      s.fb_error = std::hypot(fx + bx, fy + by);
      if (!(s.fb_error <= 2.f)) continue;
      float agreement = 0.f, valid = 0.f, clipped = 0.f;
      for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
          const int64_t px = static_cast<int64_t>(tx) * g.tile_stride +
                            (2 * x + 1) * static_cast<int64_t>(g.tile_stride) / 8;
          const int64_t py = static_cast<int64_t>(ty) * g.tile_stride +
                            (2 * y + 1) * static_cast<int64_t>(g.tile_stride) / 8;
          const float qx = static_cast<float>(px) + fx;
          const float qy = static_cast<float>(py) + fy;
          if (px >= g.width || py >= g.height || !(qx >= 0.f && qy >= 0.f) ||
              !(qx < g.width && qy < g.height)) continue;
          if (mask[static_cast<size_t>(py) * g.width + static_cast<size_t>(px)] != 0) {
            clipped += 1.f;
            continue;
          }
          float delta = 0.f;
          bool observable = true;
          constexpr float weights[3] = {0.25f, 0.5f, 0.25f};
          for (int c = 0; c < 3; ++c) {
            const float a = rgbAt(base_rgb, g.width, static_cast<int>(px),
                                  static_cast<int>(py), c);
            const float b = bilinear(short_rgb, g.width, g.height, qx, qy, c);
            if (!std::isfinite(a) || !std::isfinite(b) ||
                std::abs(a) > 2.f || std::abs(b) > 2.f) observable = false;
            delta += std::abs(a - b) * weights[c];
          }
          if (!observable) continue;
          agreement += 1.f - smoothstep(0.04f, 0.16f, delta);
          valid += 1.f;
        }
      }
      s.valid_fraction = valid / 16.f;
      s.clipped_fraction = clipped / 16.f;
      if (valid >= 4.f && clipped == 0.f)
        s.confidence = (agreement / valid) *
                       (1.f - smoothstep(0.75f, 2.f, s.fb_error));
    }
  }
  return true;
}

HighlightGeometryResult highlightResolveGeometry(
    const HighlightGeometryGrid& g, const std::vector<uint8_t>& mask,
    const std::vector<float>& forward,
    const std::vector<HighlightTileSupport>& support) {
  HighlightGeometryResult result;
  size_t pixels = 0, count = 0;
  if (!validGrid(g, &pixels, &count) || mask.size() != pixels ||
      forward.size() != count * 2 || support.size() != count) return result;
  const int n = static_cast<int>(count);
  std::vector<uint8_t> highlight(count, 0);
  for (size_t p = 0; p < pixels; ++p) {
    if (mask[p] == 0) continue;
    const size_t tx = (p % static_cast<size_t>(g.width)) / g.tile_stride;
    const size_t ty = (p / static_cast<size_t>(g.width)) / g.tile_stride;
    highlight[ty * g.grid_width + tx] = 1;
  }
  result.highlight_tiles = std::accumulate(highlight.begin(), highlight.end(), 0);
  const auto supported = [&](int t) {
    return !highlight[t] && std::isfinite(support[t].confidence) &&
           support[t].confidence >= kMinSupport &&
           std::isfinite(forward[static_cast<size_t>(t) * 2]) &&
           std::isfinite(forward[static_cast<size_t>(t) * 2 + 1]);
  };
  std::vector<int> donors;
  for (int t = 0; t < n; ++t) if (supported(t)) donors.push_back(t);
  result.supported_tiles = static_cast<int>(donors.size());
  if (result.highlight_tiles == 0) {
    result.status = HighlightGeometryStatus::NoHighlights;
    result.flow = forward;
    return result;
  }

  std::vector<int> group(count, -1), queue(count);
  std::vector<std::vector<int>> groups;
  for (int start = 0; start < n; ++start) {
    if (!highlight[start] || group[start] >= 0) continue;
    const int gid = static_cast<int>(groups.size());
    int head = 0, tail = 1;
    queue[0] = start;
    group[start] = gid;
    while (head < tail) {
      const int t = queue[head++], tx = t % g.grid_width, ty = t / g.grid_width;
      for (int y = std::max(0, ty - 1); y <= std::min(g.grid_height - 1, ty + 1); ++y)
        for (int x = std::max(0, tx - 1); x <= std::min(g.grid_width - 1, tx + 1); ++x) {
          const int nb = y * g.grid_width + x;
          if (highlight[nb] && group[nb] < 0) {
            group[nb] = gid;
            queue[tail++] = nb;
          }
        }
    }
    groups.emplace_back(queue.begin(), queue.begin() + tail);
  }
  result.regions = static_cast<int>(groups.size());
  if (donors.size() < kMinCollarTiles) {
    result.status = HighlightGeometryStatus::NoObservableSupport;
    return result;
  }

  float global[2] = {0.f, 0.f};
  for (int axis = 0; axis < 2; ++axis) {
    auto sorted = donors;
    std::stable_sort(sorted.begin(), sorted.end(), [&](int a, int b) {
      return forward[static_cast<size_t>(a) * 2 + axis] <
             forward[static_cast<size_t>(b) * 2 + axis];
    });
    double total = 0.0;
    for (int t : sorted) total += support[t].confidence;
    double accumulated = 0.0;
    for (int t : sorted) {
      accumulated += support[t].confidence;
      global[axis] = forward[static_cast<size_t>(t) * 2 + axis];
      if (accumulated >= total * 0.5) break;
    }
  }
  result.flow = forward;
  std::vector<int> stamp(count, -1), collar;
  for (size_t gid = 0; gid < groups.size(); ++gid) {
    collar.clear();
    for (int t : groups[gid]) {
      const int tx = t % g.grid_width, ty = t / g.grid_width;
      for (int y = std::max(0, ty - kCollarRadius);
           y <= std::min(g.grid_height - 1, ty + kCollarRadius); ++y)
        for (int x = std::max(0, tx - kCollarRadius);
             x <= std::min(g.grid_width - 1, tx + kCollarRadius); ++x) {
          const int nb = y * g.grid_width + x;
          if (highlight[nb] || stamp[nb] == static_cast<int>(gid)) continue;
          stamp[nb] = static_cast<int>(gid);
          if (supported(nb)) collar.push_back(nb);
        }
    }
    const bool from_collar = collar.size() >= kMinCollarTiles;
    if (from_collar) ++result.collar_regions; else ++result.global_regions;
    for (int t : groups[gid]) {
      const size_t o = static_cast<size_t>(t) * 2;
      if (!from_collar) {
        result.flow[o] = global[0];
        result.flow[o + 1] = global[1];
        continue;
      }
      double total = 0.0, sx = 0.0, sy = 0.0;
      for (int nb : collar) {
        const double dx = nb % g.grid_width - t % g.grid_width;
        const double dy = nb / g.grid_width - t / g.grid_width;
        const double weight = support[nb].confidence / (dx * dx + dy * dy);
        total += weight;
        sx += forward[static_cast<size_t>(nb) * 2] * weight;
        sy += forward[static_cast<size_t>(nb) * 2 + 1] * weight;
      }
      result.flow[o] = static_cast<float>(sx / total);
      result.flow[o + 1] = static_cast<float>(sy / total);
    }
  }
  result.status = HighlightGeometryStatus::Resolved;
  return result;
}

}  // namespace filmkit
