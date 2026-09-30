#pragma once

#include <cstdint>
#include <vector>

namespace filmkit {

// ── MFNR 阶段一对齐（v0.9.0）────────────────────────────────────────────
// HDR+（Hasinoff et al. 2016 §4）式 tile 级粗到细平移对齐，纯 CPU、无 GL 依赖
//（金字塔是小图 readback，~0.5MB/帧）。只学算法不抄代码（IPOL/PhotonCamera 同纪律）。
//
// 域约定：对齐在 mosaic 的 1/2、1/4、1/8、1/16 分辨率灰度金字塔上进行（GPU 侧
// 2×2 Bayer 块平均 + sqrt 预编码 + box 下采样，见 kFsMfnrPyr / kFsMfnrDown）。
// **最细精化层 = 1/2 域**（v0.10.70，D-2 对齐精度专项）：HDR+ §4 原文
// "we perform a coarse-to-fine alignment on four-level Gaussian pyramids of the
// downsampled-to-gray raw input … At the finest pyramid level, tiles are 32×32
// pixels"，其最细层即 2×2 Bayer 块平均后的灰度图（12 Mpix raw → 3 Mpix gray）。
// 此前最细层停在 1/4 域，位移量化 = 1 个 1/4 域像素 = **4 mosaic px**，而 P0
// 的同相位邻域半径只有 ±4 mosaic px —— 量化误差本身就是邻域量级（v0.10.65
// 的 N_eff 实验：外围样本带进系统偏差、放宽权重反而变差）。移到 1/2 域后
// 量化 = 2 mosaic px，亚像素抛物线再降一个量级。
// 输出位移统一换算到 **mosaic 像素域**（1/2 域 ×2 / 1/4 域 ×4，含亚像素）。
// 融合采样时 GPU 侧 round 到**最近偶数**：1/2 域整数位移的 2 倍恒为偶数，
// Bayer 相位（R/G/B 棋盘）因此不翻转；偶数化误差 ≤1 mosaic px（旧路径为
// 2 mosaic px）—— 亚像素重采样见 kFsMfnrFuse 的逐通道网格双线性。

// 一帧的对齐金字塔（CPU 侧，RGBA8 readback 提取 R 通道后的紧凑灰度）。
// l1 为空 = 旧路径（最细层 L2，1/4 域）；非空 = 1/2 域最细层。
struct MfnrPyramid {
  std::vector<uint8_t> l1, l2, l3, l4;  // 行紧凑，sqrt 预编码域的 8bit 灰度
  int w1 = 0, h1 = 0;               // 1/2 域（最细精化层）
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
  // 生效性诊断（防静默回退）：本次是否真的走了 1/2 域最细层。l1 未读回/尺寸
  // 不符时 Align 会静默回退旧路径 —— 那条路径没有日志痕迹，必须显式上报。
  bool used_l1 = false;
};

// tile 64×64 @ 1/2 域（= 128×128 mosaic；Night Sight 暗光档 tile 64@1/4 同量级
// —— v0.9.4 的 dMed/dP90 实测手持帧间位移 8-21px，32 tile 的 256 采样点在
// ISO 1000+ 被 SAD 抹平，翻倍后采样 ×4、tile 数减半，对齐时间基本不变）
constexpr int kMfnrTile = 64;

// 对 cur 帧对齐到 ref。tiles_x/y = 1/2 域 tile 网格（FrameRenderer 按
// w1=(w+1)/2、tiles=ceil(w1/kMfnrTile) 算出后传入）。
// allow_l1=false 强制走旧路径（最细层 1/4 域）—— 用于同帧 A/B 诊断：同一次
// 拍摄的同一 burst 跑两遍对齐，唯一差异就是最细层。
MfnrAlignOut mfnrAlign(const MfnrPyramid& ref, const MfnrPyramid& cur,
                       int tiles_x, int tiles_y, bool allow_l1 = true);

// 对齐残差（诊断，v0.10.74）：给定位移场，在 1/2 域（无 l1 时退 1/4 域）上按
// **与实际融合采样一致**的取整（mosaic 位移 round 到最近偶数）逐 tile 算
// |ref − cur(shifted)| 的平均值，返回全图**中位**残差（8bit sqrt 域灰度级）。
//
// 为什么需要它：2026-09-30 连续 4 轮真机 A/B（7 张）全部作废 —— 每张的
// ISO（2368…10160）、场景纹理、手抖幅度都不同，"降噪倍数"这类端到端指标
// 被这些混杂因素主导，看不出对齐层的影响。同一帧数据下跑两种对齐、比较
// 同一个残差量，才能把"对齐质量"单独隔离出来。
float mfnrAlignResidual(const MfnrPyramid& ref, const MfnrPyramid& cur,
                        const std::vector<float>& off, int tiles_x,
                        int tiles_y);

}  // namespace filmkit
