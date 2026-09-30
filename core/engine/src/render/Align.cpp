#include "Align.h"

#include <algorithm>
#include <cmath>

namespace filmkit {

namespace {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// SAD：a 的 (x0,y0) tile 对 b 的 (x0+dx, y0+dy) tile，步长 step 采样。
// 所有读地址逐点 clamp（tile 32/16/8 不整除图像尺寸时，末行末列 tile 的
// x0+采样点会跨过图像宽 —— 基址不带 x0、索引单独 clamp，杜绝越界读）。
inline int sadTile(const uint8_t* a, const uint8_t* b, int w, int h, int x0,
                   int y0, int dx, int dy, int tw, int th, int step) {
  int s = 0;
  for (int y = 0; y < th; y += step) {
    const int ay = clampi(y0 + y, 0, h - 1);
    const int by = clampi(y0 + y + dy, 0, h - 1);
    const uint8_t* pa = a + static_cast<size_t>(ay) * w;
    const uint8_t* pb = b + static_cast<size_t>(by) * w;
    for (int x = 0; x < tw; x += step) {
      s += std::abs(pa[clampi(x0 + x, 0, w - 1)] -
                    pb[clampi(x0 + x + dx, 0, w - 1)]);
    }
  }
  return s;
}

// ref tile 的纹理度：平均梯度（步长 step 采样点上的 |∂x|+|∂y|）。
inline float gradTile(const uint8_t* a, int w, int h, int x0, int y0, int tw,
                      int th, int step) {
  int g = 0, n = 0;
  for (int y = 0; y < th; y += step) {
    const int ay = clampi(y0 + y, 0, h - 1);
    const int ay1 = clampi(y0 + y + 1, 0, h - 1);
    const uint8_t* pa = a + static_cast<size_t>(ay) * w;
    const uint8_t* pb = a + static_cast<size_t>(ay1) * w;
    for (int x = 0; x < tw; x += step) {
      const int xx = clampi(x0 + x, 0, w - 1);
      const int xx1 = clampi(x0 + x + 1, 0, w - 1);
      g += std::abs(pa[xx1] - pa[xx]) + std::abs(pb[xx] - pa[xx]);
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

// SAD 采样窗口（1/2 域像素）。**输出网格（kMfnrTile）不变**，只是每层匹配时
// 看的范围 —— 暗光下匹配不稳的根源是采样点数不足（√N），窗口放大 2× 让采样点
// 数 ×4。相邻 tile 的窗口因此重叠（计算冗余，但网格密度不变）。
// 离线扫描用（2026-09-30，σ=0.008 暗光档）：见 tools/align-bench.cpp。
#ifndef ALIGN_SAD_TILE
#define ALIGN_SAD_TILE kMfnrTile
#endif

// 置信度判据参数：残差（每采样平均 SAD）相对 ref tile 纹理度 —— 见 matchTile。
constexpr float kConf0 = 0.40f, kConf1 = 0.90f, kGradEps = 4.0f;

// ── 暗光对齐：四个已实测否证的方向（2026-09-30，离线基准 tools/align-bench*）──
// 口径 = "错位 >1 mosaic px 的 tile 占比"（P0 同相位邻域半径只有 ±4px）。
// 中光 σ=0.002 时新层 5%；**暗光 σ=0.008 时 46%**（旧层 55%）。为救暗光试过：
//   ① 金字塔层 N×N 预平滑：46% → 56%（3×3）/ 65%（5×5）—— 削弱纹理特征，
//      匹配峰值更钝，反而更差。
//   ② 位移罚项 λ|d − d_parent|²（只加最细层）：旋转场景 5% → 39%（锁死真实
//      形变）；每层都加：5% → 88-100%（λ 大时解退化为"零位移"，|err| p50
//      恰好等于真值本身 = 指纹）。
//   ③ 搜索半径 ±2 → ±8：暗光 46% → 47%，p90 从 13 恶化到 49（噪声下 SAD
//      无可靠极小值，范围越大跳得越远）。
//   ④ L4 全局粗搜步长 2 → 1（消除 32 mosaic px 量化）：结果完全不变，成本
//      翻倍（L3 的 ±2 @1/8 域已足够纠正该量化）。
// 结论：暗光下 1/2 域块匹配是**信息不足**（SNR<1），不是参数问题。原版做法是
// "tile 随噪声放大"（NS：亮光 16px、暗光 64px；我们物理尺寸已 128 mosaic px）
// 或转向合并侧鲁棒性（HDR+ §5 "cannot rely on alignment alone"）。

// 单 tile 匹配结果（层内像素单位）。
struct TileMatch {
  int bx = 0, by = 0;   // 整数位移
  float sx = 0.f, sy = 0.f;  // 抛物线亚像素（±0.5 内）
  float conf = 0.f;     // [0,1]，0 = 弱纹理/失败 → 融合回退参考帧
  int cost = 1 << 30;
};

// 在 (cx,cy) 的 ±range 邻域搜 SAD 最小位移，再做抛物线亚像素。
// full=false 时跳过亚像素与置信度（只取初值用，省两次 SAD 重算）。
inline TileMatch matchTile(const uint8_t* a, const uint8_t* b, int aw, int ah,
                           int x0, int y0, int tile, int step, int cx, int cy,
                           int range, bool full) {
  TileMatch m;
  for (int dy = cy - range; dy <= cy + range; ++dy) {
    for (int dx = cx - range; dx <= cx + range; ++dx) {
      const int s = sadTile(a, b, aw, ah, x0, y0, dx, dy, tile, tile, step);
      if (s < m.cost) {
        m.cost = s;
        m.bx = dx;
        m.by = dy;
      }
    }
  }
  if (!full) return m;
  // 亚像素：x/y 各自的 ±1 邻域 cost（独立重算）
  const int cp = sadTile(a, b, aw, ah, x0, y0, m.bx + 1, m.by, tile, tile, step);
  const int cm = sadTile(a, b, aw, ah, x0, y0, m.bx - 1, m.by, tile, tile, step);
  const int cy1 = sadTile(a, b, aw, ah, x0, y0, m.bx, m.by + 1, tile, tile, step);
  const int cym = sadTile(a, b, aw, ah, x0, y0, m.bx, m.by - 1, tile, tile, step);
  m.sx = subpixel(static_cast<float>(cm), static_cast<float>(m.cost),
                  static_cast<float>(cp));
  m.sy = subpixel(static_cast<float>(cym), static_cast<float>(m.cost),
                  static_cast<float>(cy1));
  // 置信度：对齐残差（每采样平均）相对 ref tile 纹理度 —— 残差被梯度解释得
  // 越充分越可信；弱纹理（梯度→0）或噪声主导 → conf→0 回退参考帧。
  const float grad = gradTile(a, aw, ah, x0, y0, tile, tile, 2);
  const float sad_norm =
      static_cast<float>(m.cost) / static_cast<float>(tile * tile / 4);
  m.conf = 1.f - smoothstepf(kConf0, kConf1, sad_norm / std::max(grad, kGradEps));
  return m;
}

}  // namespace

MfnrAlignOut mfnrAlign(const MfnrPyramid& ref, const MfnrPyramid& cur,
                       int tiles_x, int tiles_y, bool allow_l1) {
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

  // ── 2) L3（1/8 域）每 tile 精化：初值 = 2×L4 全局位移 ────────────────
  // 3) L2（1/4 域）每 tile 精化：±2。tile 随 kMfnrTile 逐级减半（1/2 域 64 →
  //    L2 域 32 → L3 域 16，同一物理区域），输出到 1/2 域 tile 网格（tiles_x/y）。
  //    位移域换算 L2→mosaic = ×4。
  constexpr int kTile3 = kMfnrTile / 4, kTile2 = kMfnrTile / 2, kRange = 2;
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

  // ── 3) L2（1/4 域）每 tile 精化（±2） ───────────────────────────────
  // 1/2 域 64px tile ≡ L3 域 16px tile（同一区域逐级减半），网格同
  // tiles_x/y。有 L1（1/2 域）时本层只作**初值**（整数位移，省亚像素与置信度）；
  // 无 L1 = 旧路径，本层就是最细层（位移域换算 L2→mosaic = ×4）。
  const bool use_l1 = allow_l1 && ref.w1 > 0 && cur.w1 > 0 &&
                      ref.l1.size() == static_cast<size_t>(ref.w1) * ref.h1 &&
                      cur.l1.size() == static_cast<size_t>(cur.w1) * cur.h1 &&
                      ref.l1.size() > 0;
  out.used_l1 = use_l1;
  std::vector<int> d2x(static_cast<size_t>(n_tiles), 0);
  std::vector<int> d2y(static_cast<size_t>(n_tiles), 0);
  float conf_sum = 0.f;
  for (int ty = 0; ty < tiles_y; ++ty) {
    for (int tx = 0; tx < tiles_x; ++tx) {
      const int x0 = tx * kTile2, y0 = ty * kTile2;
      // 1/2 域 32px tile ≡ L3 域 8px tile（同一区域逐级减半），网格同尺寸
      const int i3 = clampi(tx, 0, nx3 - 1) + clampi(ty, 0, ny3 - 1) * nx3;
      const int vx = d3x[static_cast<size_t>(i3)], vy = d3y[static_cast<size_t>(i3)];
      const TileMatch m =
          matchTile(ref.l2.data(), cur.l2.data(), ref.w2, ref.h2, x0, y0, kTile2, 1,
                    2 * vx, 2 * vy, kRange, !use_l1);
      const int idx = ty * tiles_x + tx;
      d2x[static_cast<size_t>(idx)] = m.bx;
      d2y[static_cast<size_t>(idx)] = m.by;
      if (!use_l1) {
        out.off[static_cast<size_t>(idx) * 2 + 0] = (m.bx + m.sx) * 4.f;
        out.off[static_cast<size_t>(idx) * 2 + 1] = (m.by + m.sy) * 4.f;
        out.conf[static_cast<size_t>(idx)] = m.conf;
        conf_sum += m.conf;
      }
    }
  }

  // ── 4) L1（1/2 域）每 tile 精化（±2）+ 亚像素 + 置信度 ───────────────
  // 最细层 = 2×2 Bayer 块平均灰度（HDR+ §4 原版最细层），初值 = 2×L2 位移。
  // 位移域换算 L1→mosaic = ×2：量化从 4 mosaic px 降到 2，是 P0 同相位邻域
  // （±4 mosaic px）精度提升的直接来源。tile 与输出网格同为 tiles_x/y。
  if (use_l1) {
    for (int ty = 0; ty < tiles_y; ++ty) {
      for (int tx = 0; tx < tiles_x; ++tx) {
        const int idx = ty * tiles_x + tx;
        const int x0 = tx * kMfnrTile + kMfnrTile / 2 - ALIGN_SAD_TILE / 2;
        const int y0 = ty * kMfnrTile + kMfnrTile / 2 - ALIGN_SAD_TILE / 2;
        const TileMatch m = matchTile(
            ref.l1.data(), cur.l1.data(), ref.w1, ref.h1, x0, y0, ALIGN_SAD_TILE, 2,
            2 * d2x[static_cast<size_t>(idx)], 2 * d2y[static_cast<size_t>(idx)],
            kRange, true);
        out.off[static_cast<size_t>(idx) * 2 + 0] = (m.bx + m.sx) * 2.f;
        out.off[static_cast<size_t>(idx) * 2 + 1] = (m.by + m.sy) * 2.f;
        out.conf[static_cast<size_t>(idx)] = m.conf;
        conf_sum += m.conf;
      }
    }
  }
  out.conf_med = n_tiles > 0 ? conf_sum / n_tiles : 0.f;
  // 全局位移：L4 是 1/16 域，1px = 16 mosaic px（旧版写 ×4 = L2 域，口径修正）。
  out.gx = static_cast<float>(bgx) * 16.f;
  out.gy = static_cast<float>(bgy) * 16.f;
  // 位移幅度分布。过滤条件 conf > 0.2 在 L1 路径下形同不过滤 —— 离线基准
  // （tools/align-bench.cpp + align-bench-gen.py，2026-09-30 实测）该"残差/纹理度"
  // 判据在 1/4 域误杀 87% 好 tile、换到 1/2 域后 38 个失败 tile 只检出 0 个，
  // **两代都无判别力**，故仅作幅度统计的粗过滤，不可用作对齐门控（门控走
  // mismatch 权重）。真正识别重复纹理误匹配需要峰值锐度类判据，未做。
  std::vector<float> mags;
  mags.reserve(static_cast<size_t>(n_tiles));
  for (int t = 0; t < n_tiles; ++t) {
    if (out.conf[static_cast<size_t>(t)] <= 0.2f) continue;
    const float dx = out.off[static_cast<size_t>(t) * 2];
    const float dy = out.off[static_cast<size_t>(t) * 2 + 1];
    mags.push_back(std::sqrt(dx * dx + dy * dy));
  }
  if (!mags.empty()) {
    std::sort(mags.begin(), mags.end());
    out.dx_med = mags[mags.size() / 2];
    out.dx_p90 = mags[mags.size() * 9 / 10];
  }
  return out;
}


// 对齐残差（诊断，v0.10.74）：见 Align.h 的说明。评估层优先 1/2 域（最接近
// 融合实际采样：融合按 mosaic round 到最近偶数采样，等价于 1/2 域整数位移），
// 无 l1 数据时退 1/4 域。位移用**与融合一致的取整**（round 到 mosaic 偶数），
// 保证评的是"实际会被平均进去的那个位置"，而不是理想亚像素。
float mfnrAlignResidual(const MfnrPyramid& ref, const MfnrPyramid& cur,
                        const std::vector<float>& off, int tiles_x,
                        int tiles_y) {
  const bool use1 = ref.w1 > 0 && cur.w1 > 0 &&
                    ref.l1.size() == static_cast<size_t>(ref.w1) * ref.h1 &&
                    cur.l1.size() == static_cast<size_t>(cur.w1) * cur.h1;
  const uint8_t* a = use1 ? ref.l1.data() : ref.l2.data();
  const uint8_t* b = use1 ? cur.l1.data() : cur.l2.data();
  const int aw = use1 ? ref.w1 : ref.w2;
  const int ah = use1 ? ref.h1 : ref.h2;
  const int tile = use1 ? kMfnrTile : kMfnrTile / 2;
  const int scale = use1 ? 2 : 4;  // 评估层 → mosaic 的倍数
  const int n_tiles = tiles_x * tiles_y;
  if (!a || !b || aw <= 0 || ah <= 0 || n_tiles <= 0 ||
      off.size() < static_cast<size_t>(n_tiles) * 2) {
    return -1.f;
  }
  std::vector<float> per_tile;
  per_tile.reserve(static_cast<size_t>(n_tiles));
  for (int ty = 0; ty < tiles_y; ++ty) {
    for (int tx = 0; tx < tiles_x; ++tx) {
      const int t = ty * tiles_x + tx;
      // mosaic 位移 → round 到最近偶数（= 融合采样的相位保持取整）→ 评估层像素
      const float mx = off[static_cast<size_t>(t) * 2 + 0];
      const float my = off[static_cast<size_t>(t) * 2 + 1];
      const int dx = static_cast<int>(std::floor(mx * 0.5f + 0.5f)) * 2 / scale;
      const int dy = static_cast<int>(std::floor(my * 0.5f + 0.5f)) * 2 / scale;
      const int x0 = tx * tile, y0 = ty * tile;
      long acc = 0;
      int n = 0;
      for (int y = 0; y < tile; y += 2) {
        const int ry = clampi(y0 + y, 0, ah - 1);
        const int cy2 = clampi(y0 + y + dy, 0, ah - 1);
        const uint8_t* pa = a + static_cast<size_t>(ry) * aw;
        const uint8_t* pb = b + static_cast<size_t>(cy2) * aw;
        for (int x = 0; x < tile; x += 2) {
          const int rx = clampi(x0 + x, 0, aw - 1);
          const int cx2 = clampi(x0 + x + dx, 0, aw - 1);
          acc += std::abs(static_cast<int>(pa[rx]) - static_cast<int>(pb[cx2]));
          ++n;
        }
      }
      if (n > 0) per_tile.push_back(static_cast<float>(acc) / n);
    }
  }
  if (per_tile.empty()) return -1.f;
  std::sort(per_tile.begin(), per_tile.end());
  return per_tile[per_tile.size() / 2];  // 全图中位（抗局部运动/无纹理区）
}



}  // namespace filmkit
