#pragma once

#include <cstdint>
#include <vector>

namespace filmkit {

// ── MFNR 阶段一对齐（v0.9.0）────────────────────────────────────────────
// HDR+（Hasinoff et al. 2016 §4）式 tile 级粗到细平移对齐，纯 CPU、无 GL 依赖
//（金字塔是小图 readback，~0.5MB/帧）。只学算法不抄代码（IPOL/PhotonCamera 同纪律）。
//
// 域约定：对齐在 mosaic 的 1/4、1/8、1/16 分辨率灰度金字塔上进行（GPU 侧
// 2×2 Bayer 块平均 + sqrt 预编码 + box 下采样，见 kFsMfnrPyr / kFsMfnrDown）。
// 1/2 层（最终 tile 网格所在层）不做 readback，位移由 1/4 层精化结果 ×2 得出。
// 输出位移统一换算到 **mosaic 像素域**（= 1/4 域位移 ×4，含亚像素）。
// 融合采样时 GPU 侧 round 到**最近偶数**：1/2 域整数位移的 2 倍恒为偶数，
// Bayer 相位（R/G/B 棋盘）因此不翻转；亚像素 0.5 格 = mosaic 2px，偶数化
// 误差 ≤2 mosaic px —— 对均值错位可忽略（彩噪抑制是低频目标）。

// 一帧的对齐金字塔（CPU 侧，RGBA8 readback 提取 R 通道后的紧凑灰度）。
struct MfnrPyramid {
  std::vector<uint8_t> l2, l3, l4;  // 行紧凑，sqrt 预编码域的 8bit 灰度
  int w2 = 0, h2 = 0;               // 1/4 域
  int w3 = 0, h3 = 0;               // 1/8 域
  int w4 = 0, h4 = 0;               // 1/16 域
};

// 一帧对齐结果：位移场 + 置信度。
struct MfnrAlignOut {
  std::vector<float> off;   // 2*tiles 交错 (x,y)，mosaic 域
  std::vector<float> conf;  // tiles，[0,1]；0 = 弱纹理/失败 → 融合回退参考帧
  float gx = 0.f, gy = 0.f; // 全局位移（mosaic 域，日志）
  float gcost = 0.f;        // 全局最优 SAD（8bit 灰度和，日志）
  float conf_med = 1.f;     // 置信度中位（日志）
  // 位移幅度分布（|off|，mosaic 域；日志用——判定帧间位移量级，
  // 决定亚像素融合升级的必要性）
  float dx_med = 0.f, dx_p90 = 0.f;
};

// tile 64×64 @ 1/2 域（= 128×128 mosaic；Night Sight 暗光档 tile 64@1/4 同量级
// —— v0.9.4 的 dMed/dP90 实测手持帧间位移 8-21px，32 tile 的 256 采样点在
// ISO 1000+ 被 SAD 抹平，翻倍后采样 ×4、tile 数减半，对齐时间基本不变）
constexpr int kMfnrTile = 64;

// 对 cur 帧对齐到 ref。tiles_x/y = 1/2 域 tile 网格（FrameRenderer 按
// w1=(w+1)/2、tiles=ceil(w1/kMfnrTile) 算出后传入）。
MfnrAlignOut mfnrAlign(const MfnrPyramid& ref, const MfnrPyramid& cur,
                       int tiles_x, int tiles_y);

}  // namespace filmkit
