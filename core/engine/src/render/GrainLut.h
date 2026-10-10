// E4-3 颗粒（granularity）：相纸响应 LUT 的**纯数学**（darktable src/iop/grain.c 逐行移植）。
//
// 为什么单独成头（而不是写在 FrameRenderer.cpp 的匿名 namespace 里）：
//   离线验收要求"Python/numpy 独立复算 ↔ **C++ 侧输出**逐点对照"（任务卡 §离线验证 1）。
//   若对照脚本自己另写一份 C++ 公式，对照的是两份各自实现的代码，证明不了真机数据。
//   抽成头 ⇒ FrameRenderer 与 tools/grain-exp/grain-lut-dump.cpp 用**同一份**实现，
//   且本文件无 GL / 无日志依赖，可独立编译。
//
// 数值纪律（任务卡"先 100% 按参考实现跑通"）：
//   公式、常量、运算次序逐字对齐 darktable grain.c 的 paper_resp / paper_resp_inverse /
//   evaluate_grain_lut。**唯一**的结构改动 = 把与循环无关的 `delta` 提到循环外
//   （同一个输入表达式 ⇒ 逐位等价，非近似）。
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>

namespace filmkit {
namespace grain {

// darktable grain.c 的同名常量（GRAIN_LUT_*）。
constexpr int kLutSize = 128;
constexpr float kDeltaMax = 2.0f;      // GRAIN_LUT_DELTA_MAX
constexpr float kDeltaMin = 0.0001f;   // GRAIN_LUT_DELTA_MIN
constexpr float kPaperGamma = 1.0f;    // GRAIN_LUT_PAPER_GAMMA（gp）
constexpr float kScaleFactor = 213.2f; // GRAIN_SCALE_FACTOR（UI 的 ISO 换算）
constexpr float kLightnessStrengthScale = 0.15f;  // GRAIN_LIGHTNESS_STRENGTH_SCALE

// darktable `paper_resp` 里的 delta = 2·exp((mb/100)·ln(0.0001))。与 mb 有关、与像素无关
// ⇒ 提到循环外（darktable 在每个像素/每个 LUT 格重算一遍，值相同）。
inline float grainDelta(float mb) {
  return kDeltaMax * std::exp((mb / 100.0f) * std::log(kDeltaMin));
}

// darktable `paper_resp(exposure, mb, gp)`（delta 已提出）。
inline float paperResp(float exposure, float delta) {
  return (1.0f + 2.0f * delta) /
             (1.0f + std::exp((4.0f * kPaperGamma * (0.5f - exposure)) / (1.0f + 2.0f * delta))) -
         delta;
}

// darktable `paper_resp_inverse(density, mb, gp)`（delta 已提出）。
inline float paperRespInverse(float density, float delta) {
  return -std::log((1.0f + 2.0f * delta) / (density + delta) - 1.0f) * (1.0f + 2.0f * delta) /
             (4.0f * kPaperGamma) +
         0.5f;
}

// darktable `evaluate_grain_lut`（GRAIN_LUT_SIZE² 个 float，布局 lut[j*SIZE + i]：
//   i = 噪声轴 gu ∈ [-0.5, 0.5]，j = 亮度轴 l ∈ [0, 1]）。
//
// ⚠ [61] grain_pivot（规格 §3.1.5，**唯一一处非 darktable 原版数学**）：
//   `l' = pow(l, k)`，`k = 1 / (1 + 3·pivot)`，作用在 paper_resp_inverse 的**亮度输入**上。
//   本实现把它折进 LUT（CPU 侧）而不是放在 shader 里 —— 判断与理由见交付报告"超出规格的判断"：
//     ① pivot = 0 ⇒ k = 1 ⇒ 走下面的短路分支，**逐位**等于 darktable 原版（GPU 的
//        pow(x, 1.0) 无此保证：ES 3.0 的 pow 精度继承自 exp2(y·log2(x))，验收判据 5
//        "pivot=0 逐位等于不启用 pivot" 会被浮点误差打破）；
//     ② 12.5M px × 1 次 pow 的 GPU 成本归零，改为 [61] 变化时 16384 次 CPU pow。
//   ⚠⚠ 减数必须是 **lp（pow 之后的值）而不是 l**：LUT 的物理含义是"噪声 gu 带来的
//   亮度增量"，`paper_resp(inverse(y)) == y` ⇒ gu = 0 时增量恒 0（darktable 的
//   `- l` 与 `- lp` 在 pivot = 0 时是同一个式子）。若照字面只替换 inverse 的输入而
//   保留 `- l`，则 gu = 0 处 LUT = 100·(l' − l) ≠ 0 ⇒ [61] 会变成一个**整体亮度**
//   旋钮（pivot=0.3 时全图 −23.5 L 单位 ≈ −60/255，离线实测），与它"移动颗粒调制
//   峰值"的用途完全不符。因此这里取 `- lp`。
//   代价：pivot > 0 时插值发生在**原始亮度空间**（而非 pow 后空间），与"shader 内 pow"
//   有 ~1/127 采样分辨率的离散化差异；pivot = 0 时两者完全相同。
//
// ★★ 指数取**倒数**（E4-3b，架构师裁决，2026-10-10）：
//   规格 §3.1.5 自相矛盾 —— **公式**写 `pow(l, 1 + 3·pivot)`，**文字**写"pivot > 0 ⇒ 暗部落在
//   相纸曲线的陡段 ⇒ 颗粒峰值移向暗部"。E4-3 按公式字面实测：峰值 l = 0.496 → 0.583 → 0.693
//   （移向**亮部**），与文字意图**相反**。裁决：**以文字意图为准**，指数改为倒数
//   `k = 1 / (1 + 3·pivot)`。理由：
//     ① 规格的**文字**承载的是调研需求（`editor-preset-RESEARCH.md` §2.4"负片暗部颗粒重"），
//        公式只是实现细节 —— 两者矛盾时**需求优先**。
//     ② 数学：相纸响应 `paper_resp'(e)` 的峰值在 e = 0.5（中灰）。"暗部颗粒最重"要求把暗部
//        亮度映射到曲线峰值：f(0.3) = 0.5 ⇒ k = ln(0.5)/ln(0.3) ≈ 0.577。倒数式在 p = 0.3 时
//        给出 k ≈ 0.526 ✓ 方向正确；原式 k = 1 + 3p = 1.9 给出 f(0.3) = 0.1（**偏离**峰值）✗。
//     ③ pivot = 0 时两式同为 k = 1.0f ⇒ 走短路分支 ⇒ **逐位不变**，零回归风险
//        （E4-3b 实测：改前/改后 pivot = 0 的 LUT 二进制 md5 相同）。
//   调用方（FrameRenderer::ensureGrainLut）已把 pivot clamp 到 [0, 1] ⇒ 分母 ∈ [1, 4]，k ∈ (0, 1]。
inline void buildGrainLut(float* lut, float mb, float pivot) {
  const float delta = grainDelta(mb);
  const float k = 1.0f / (1.0f + 3.0f * pivot);  // E4-3b：倒数 ⇒ 峰值随 pivot 移向**暗部**
  for (int j = 0; j < kLutSize; ++j) {
    const float l = static_cast<float>(j) / static_cast<float>(kLutSize - 1);
    const float lp = (k == 1.0f) ? l : std::pow(l, k);
    const float inv = paperRespInverse(lp, delta);
    for (int i = 0; i < kLutSize; ++i) {
      const float gu = static_cast<float>(i) / static_cast<float>(kLutSize - 1) - 0.5f;
      lut[j * kLutSize + i] = 100.0f * (paperResp(gu + inv, delta) - lp);
    }
  }
}

// float → IEEE half（R16F 上传用）。LUT 值域 ±100 且非 NaN / 非 denormal ⇒ 只做指数搬移
// + 截断尾数（相对误差 ≤ 2^-11，远小于 LUT 自身 1/127 的采样分辨率）。
inline uint16_t floatToHalf(float f) {
  uint32_t x = 0;
  std::memcpy(&x, &f, sizeof(x));
  const uint32_t sign = (x >> 16) & 0x8000u;
  const int32_t e = static_cast<int32_t>((x >> 23) & 0xffu) - 127 + 15;
  if (e <= 0) return static_cast<uint16_t>(sign);             // 下溢 → ±0
  if (e >= 31) return static_cast<uint16_t>(sign | 0x7c00u);  // 上溢 → ±inf（到不了）
  return static_cast<uint16_t>(sign | (static_cast<uint32_t>(e) << 10) |
                               ((x & 0x7fffffu) >> 13));
}

// [27] ISO → darktable 的 zoom（单调：ISO 越大 ⇒ zoom 越大 ⇒ 空间频率越低 ⇒ 颗粒越粗）。
// darktable: `scale` 参数本身 = ISO/GRAIN_SCALE_FACTOR（见其 $MIN/$MAX 注解），
//            `zoom = (1.0 + 8·scale/100) / 800.0`。
// **clamp 上限是必需的**：旧实现只防了 0（`max(uGrainSize, 0.25)`），size 极大时全部噪声
// 坐标落进同一格 ⇒ 全图加同一灰度 = 一条真实的静默失败路径（设计文档 §3.1.7 点名）。
inline float grainZoom(float iso) {
  const float scale = (iso < 20.0f ? 20.0f : (iso > 6400.0f ? 6400.0f : iso)) / kScaleFactor;
  return (1.0f + 8.0f * scale / 100.0f) / 800.0f;
}

}  // namespace grain
}  // namespace filmkit
