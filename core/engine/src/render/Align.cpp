#include "Align.h"

#include <algorithm>
#include <cmath>

namespace filmkit {

namespace {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// SAD：a 的 (x0,y0) tile 对 b 的 (x0+dx, y0+dy) tile，步长 step 采样。
// 双侧都 clamp 到图像边界（位移大时贴边 tile 仍可评估）。
inline int sadTile(const uint8_t* a, const uint8_t* b, int w, int h, int x0,
                   int y0, int dx, int dy, int tw, int th, int step) {
  int s = 0;
  for (int y = 0; y < th; y += step) {
    const int ay = clampi(y0 + y, 0, h - 1);
    const int by = clampi(y0 + y + dy, 0, h - 1);
    const uint8_t* pa = a + static_cast<size_t>(ay) * w + x0;
    const uint8_t* pb = b + static_cast<size_t>(by) * w;
    for (int x = 0; x < tw; x += step) {
      s += std::abs(pa[x] - pb[clampi(x0 + x + dx, 0, w - 1)]);
    }
  }
  return s;
}

// ref tile 的纹理度：平均梯度（步长 step 采样点上的 |∂x|+|∂y|）。
inline float gradTile(const uint8_t* a, int w, int h, int x0, int y0, int tw,
                      int th, int step) {
  int g = 0, n = 0;
  for (int y = 0; y < th; y += step) {
    const int yy = clampi(y0 + y, 0, h - 1);
    const int y1 = clampi(y0 + y + 1, 0, h - 1);
    const uint8_t* pa = a + static_cast<size_t>(yy) * w + x0;
    const uint8_t* pb = a + static_cast<size_t>(y1) * w + x0;
    for (int x = 0; x < tw; x += step) {
      const int xx = clampi(x0 + x, 0, w - 1);
      const int x1 = clampi(x0 + x + 1, 0, w - 1);
      g += std::abs(pa[x1] - pa[xx]) + std::abs(pb[xx] - pa[xx]);
      ++n;
    }
  }
  return n > 0 ? static_cast<float>(g) / n : 0.f;
}

// 抛物线亚像素：三点 (cm, c0, cp) 拟合极值偏移，clamp ±0.5。
inline float subpixel(float cm, float c0, float cp) {
  const float denom = cm - 2.f * c0 + cp;
  if (std::abs(denom) < 1e-6f) return 0.f;
  const float d = 0.5f * (cm - cp) / denom;
  return std::min(std::max(d, -0.5f), 0.5f);
}

inline float smoothstepf(float t0, float t1, float x) {
  const float t = std::min(std::max((x - t0) / (t1 - t0), 0.f), 1.f);
  return t * t * (3.f - 2.f * t);
}

}  // namespace

MfnrAlignOut mfnrAlign(const MfnrPyramid& ref, const MfnrPyramid& cur,
                       int tiles_x, int tiles_y) {
  MfnrAlignOut out;
  const int n_tiles = tiles_x * tiles_y;
  out.off.assign(static_cast<size_t>(n_tiles) * 2, 0.f);
  out.conf.assign(static_cast<size_t>(n_tiles), 0.f);
  if (n_tiles <= 0 || ref.w4 <= 0 || cur.w4 <= 0) return out;

  // ── 1) L4（1/16 域）全局粗搜 ────────────────────────────────────────
  // 顶层不做 tile（4×4 tile 对暗光 8bit 噪声太小），整图穷搜 ±16、步长 2；
  // λ 先验（0.02/px²）压大位移假峰。cost 归一到全图后与 λ 项同量纲相加。
  constexpr int kR4 = 16, kStep4 = 2;
  constexpr float kLambda4 = 0.02f;
  const int samples4 = (ref.w4 / 2) * (ref.h4 / 2);
  int best4 = 1 << 30;
  int bgx = 0, bgy = 0;
  for (int dy = -kR4; dy <= kR4; dy += kStep4) {
    for (int dx = -kR4; dx <= kR4; dx += kStep4) {
      const int s = sadTile(ref.l4.data(), cur.l4.data(), ref.w4, ref.h4, 0, 0,
                            dx, dy, ref.w4, ref.h4, kStep4);
      const int c = s + static_cast<int>(
                           kLambda4 * static_cast<float>(dx * dx + dy * dy) *
                           samples4);
      if (c < best4) {
        best4 = c;
        bgx = dx;
        bgy = dy;
      }
    }
  }
  out.gcost = static_cast<float>(best4) / std::max(samples4, 1);

  // ── 2) L3（1/8 域）每 tile 精化：tile 8×8，初值 = 2×L4 全局位移 ──────
  constexpr int kTile3 = 8, kTile2 = 16, kRange = 2;
  const int nx3 = (ref.w3 + kTile3 - 1) / kTile3;
  const int ny3 = (ref.h3 + kTile3 - 1) / kTile3;
  std::vector<int> d3x(static_cast<size_t>(nx3 * ny3), 0);
  std::vector<int> d3y(static_cast<size_t>(nx3 * ny3), 0);
  for (int ty = 0; ty < ny3; ++ty) {
    for (int tx = 0; tx < nx3; ++tx) {
      const int x0 = tx * kTile3, y0 = ty * kTile3;
      int best3 = 1 << 30;
      int bx = 0, by = 0;
      for (int dy = 2 * bgy - kRange; dy <= 2 * bgy + kRange; ++dy) {
        for (int dx = 2 * bgx - kRange; dx <= 2 * bgx + kRange; ++dx) {
          const int s = sadTile(ref.l3.data(), cur.l3.data(), ref.w3, ref.h3,
                                x0, y0, dx, dy, kTile3, kTile3, 1);
          if (s < best3) {
            best3 = s;
            bx = dx;
            by = dy;
          }
        }
      }
      d3x[static_cast<size_t>(ty * nx3 + tx)] = bx;
      d3y[static_cast<size_t>(ty * nx3 + tx)] = by;
    }
  }

  // ── 3) L2（1/4 域）每 tile 精化（±2）+ 亚像素 + 置信度 ──────────────
  // 输出到 1/2 域 tile 网格（tiles_x/y）。1/2 域 32px tile = L2 域 16px tile =
  // L3 域 8px tile 的同一区域；位移域换算 L2→mosaic = ×4。
  constexpr float kConf0 = 0.40f, kConf1 = 0.90f, kGradEps = 4.0f;
  float conf_sum = 0.f;
  for (int ty = 0; ty < tiles_y; ++ty) {
    for (int tx = 0; tx < tiles_x; ++tx) {
      const int x0 = tx * kTile2, y0 = ty * kTile2;
      // 1/2 域 32px tile ≡ L3 域 8px tile（同一区域逐级减半），网格同尺寸
      const int i3 = clampi(tx, 0, nx3 - 1) + clampi(ty, 0, ny3 - 1) * nx3;
      const int vx = d3x[static_cast<size_t>(i3)], vy = d3y[static_cast<size_t>(i3)];
      int best2 = 1 << 30;
      int bx = 0, by = 0;
      for (int dy = 2 * vy - kRange; dy <= 2 * vy + kRange; ++dy) {
        for (int dx = 2 * vx - kRange; dx <= 2 * vx + kRange; ++dx) {
          const int s = sadTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2,
                                x0, y0, dx, dy, kTile2, kTile2, 1);
          if (s < best2) {
            best2 = s;
            bx = dx;
            by = dy;
          }
        }
      }
      // 亚像素：x/y 各自的 ±1 邻域 cost（独立重算）
      const int c0 = sadTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2, x0,
                             y0, bx, by, kTile2, kTile2, 1);
      const int cp = sadTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2, x0,
                             y0, bx + 1, by, kTile2, kTile2, 1);
      const int cm = sadTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2, x0,
                             y0, bx - 1, by, kTile2, kTile2, 1);
      const int cy1 = sadTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2, x0,
                              y0, bx, by + 1, kTile2, kTile2, 1);
      const int cym = sadTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2, x0,
                              y0, bx, by - 1, kTile2, kTile2, 1);
      const float sx = subpixel(static_cast<float>(cm), static_cast<float>(c0),
                                static_cast<float>(cp));
      const float sy = subpixel(static_cast<float>(cym), static_cast<float>(c0),
                                static_cast<float>(cy1));
      // 置信度：对齐残差（每采样平均）相对 ref tile 纹理度 —— 残差被梯度
      // 解释得越充分越可信；弱纹理（梯度→0）或噪声主导 → conf→0 回退参考帧。
      const float grad = gradTile(ref.l2.data(), ref.w2, ref.h2, x0, y0, kTile2,
                                  kTile2, 2);
      const float sad_norm =
          static_cast<float>(best2) / static_cast<float>(kTile2 * kTile2 / 4);
      const float conf =
          1.f - smoothstepf(kConf0, kConf1, sad_norm / std::max(grad, kGradEps));
      const int idx = ty * tiles_x + tx;
      out.off[static_cast<size_t>(idx) * 2 + 0] = (bx + sx) * 4.f;
      out.off[static_cast<size_t>(idx) * 2 + 1] = (by + sy) * 4.f;
      out.conf[static_cast<size_t>(idx)] = conf;
      conf_sum += conf;
    }
  }
  out.conf_med = n_tiles > 0 ? conf_sum / n_tiles : 0.f;
  out.gx = static_cast<float>(bgx) * 4.f;
  out.gy = static_cast<float>(bgy) * 4.f;
  return out;
}

}  // namespace filmkit
