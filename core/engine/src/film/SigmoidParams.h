#pragma once

// 引擎 1（sigmoid）的 CPU 侧参数求解 —— docs/engine-slots-design.md §7。
//
// 一手依据：darktable `src/iop/sigmoid.c` 的 `commit_params`（master，2026-10-13 抓取），
// **逐行照抄**，不做简化、不做重新推导。官方设计约束（源码注释逐字）：
//   f(scene_zero) = display_black_target
//   f(scene_grey) = MIDDLE_GREY = 0.1845
//   f(scene_inf)  = display_white_target
//   "Slope at scene_grey independent of skewness i.e. only changed by the contrast parameter."
//
// 为什么参数求解放 CPU：GLSL 侧只做曲线求值（吃 7 个标量 uniform）⇒ 公式只有一处实现，
// 不存在"CPU 与 GPU 各写一遍公式"的漂移面（对齐 `computeFilmicSpline` 的既有做法）。
//
// 本文件与 `.cpp` 刻意**不依赖 GL/EGL/Android**，可独立编译与数值对拍
// （同 `EngineRegistry.h` 的设计约束）。
//
// ⚠ **精度口径：全程 float32**（`powf`），与 darktable 实跑一致 ——
// `dt_iop_sigmoid_data_t` 的字段与 `commit_params` 的局部量都是 `float`。因此
// contrast=1.5 得到 `film_power = 1.490909`（**不是** double 的 1.5）。
// PhotonCamera 硬编码的 `1.5 / 0.00142637086 / 0.354355423` 是 **double 预算值**，
// 与本实现差约 0.6% —— 那是**口径差异，不是"更正确"**（17 号报告 §1.3 已独立复算两组值）。

namespace filmkit {

// darktable `MIDDLE_GREY`（与 `FrameRenderer.cpp` 的 `kGrey = 0.1845` 同源）。
inline constexpr float kSigmoidMiddleGrey = 0.1845f;

// `_generalized_loglogistic_sigmoid` 的已求解标量（GPU 侧直接吃这 7 个数）。
struct SigmoidParams {
  float white_target = 1.0f;       // 0.01 · display_white_target（100% ⇒ 1.0）
  float black_target = 0.000152f;  // 0.01 · display_black_target（0.0152% ⇒ 0.000152）
  float paper_exposure = 0.0f;
  float film_fog = 0.0f;
  float film_power = 1.0f;
  float paper_power = 1.0f;
  float hue_preservation = 1.0f;  // 已 clamp 到 [0,1]（UI 侧是百分数）
};

// 逐行照抄 darktable `commit_params`（对照注释见 `.cpp`）。
//   middle_grey_contrast : 官方同名参数（0.1..10，默认 1.5）
//   contrast_skewness    : 官方同名参数（-1..1，默认 0）
//   display_white_target : **百分数**（官方同名参数，20..1600，默认 100）
//   display_black_target : **百分数**（官方同名参数，0..15，默认 0.0152）
//   hue_preservation     : **百分数**（官方同名参数，0..100，默认 100）
SigmoidParams computeSigmoidParams(float middle_grey_contrast,
                                   float contrast_skewness,
                                   float display_white_target,
                                   float display_black_target,
                                   float hue_preservation);

// 引擎 1 的默认参数 = **darktable / PhotonCamera 的官方默认**（2026-10-14 用户拍板
// 「跟 PC 一致」）：contrast = **1.5**、skew = 0、display_white_target = 100、
// display_black_target = 0.0152、hue_preservation = 100。
//   · **PC 的硬编码常量就是这个默认下的解**（`DarktableSigmoidToneAlgorithm.kt:6-10`
//     的 paper_exposure 0.354355423 / film_fog 0.00142637086 / film_power 1.5 是
//     **double 预算值**）；本实现走 float32 求解（= darktable 实跑口径）得
//     paper_exposure 0.359695 / film_fog 0.001384322 / film_power 1.490909。
//     参数差 ≈1.3%，**8bit 输出上逐点差 ≤0.41 LSB**（−6…+8 EV 全域实测，mean 0.168）
//     ⇒ 与 PC 在 8bit 下不可分辨；**不要**为了"逐位像 PC"改回硬编码常量。
//   · 白纸（2.30 EV）输出 **218.84 LSB**（float32；PC 常量 219.24）。
//   · ⚠ **代价（如实记录，不隐去）**：不再"与引擎 0 同白纸口径"（引擎 0 纯曲线
//     205.13 / 端到端 214.20 LSB）⇒ 判据 ①「可分辨跨度」由 2.00 EV（c=1.2）降为
//     **1.40 EV**。这是**判据口径的产物、不是质量下降**：PC 从不做跨引擎白纸对齐
//     （各引擎用自己的标准参数），"切引擎观感差异明显"正是这么来的。
//   · 历史：c=1.2 是 2026-10-13 为「判据 ① 同白纸口径可比」人为取的值（白纸 203.60
//     LSB ≈ 引擎 0 的 204），2026-10-14 用户拍板与 PC 一致 ⇒ **该人为约束作废**。
// ── **不要动这个值**（2026-10-22 用 PC 源码核对后更正）──────────────────────────
// 曾一度想把它从 1.5 提到 1.8 来压暗部，理由是"与 PC 暗部不一致"。**那是错的**：
// PC 源码（bjzhou/PhotonCamera, raw/DarktableSigmoidToneAlgorithm.kt）里写死的
// 参数与我们的派生结果**几乎逐位相同**：
//   PC: DT_SIGMOID_PAPER_EXPOSURE 0.354355423 / FILM_FOG 0.00142637086 / FILM_POWER 1.5
//   我们(日志 sig_pe/sig_ff/sig_fp):  0.359695 / 0.001384 / 1.490909
// 即**两边是同一条 darktable sigmoid**，暗部差异不在这里。真凶是下面这一层：
// 我们默认开着 local_tone（局部色调映射，暗部线性提亮 25%），而 PC 的
// ShadowsHighlightsParams 默认是 (highlights=0, shadows=0) —— 它那层是关的。
// ── **保持 1.5：与 PC 的暗房曲线逐位一致，不要动**（2026-10-22 定案）──────────
// PC 源码（bjzhou/PhotonCamera, raw/DarktableSigmoidToneAlgorithm.kt）写死的参数：
//   PAPER_EXPOSURE 0.354355423 / FILM_FOG 0.00142637086 / FILM_POWER 1.5
// 我们 computeSigmoidParams(1.5) 的派生结果：0.359695 / 0.001384 / 1.490909 —— 同一条曲线。
//
// 曾两次试图把它提到 1.7/1.8 来"补暗部对比度"，两次都回退：
//   · 实测副作用（用引擎真实代码算）：白纸 218.84 → 226.52（+7.7 LSB），
//     而 PC 白纸是 219.24 ⇒ 会破坏"与 PC 白纸一致"的标定；+4EV 也从 247.4 → 250.4，
//     高光更早 clip。中灰恒 119.01 不动，所以只有高光端受损。
//   · 用户决定：**不在引擎里改，留到预设里自己调对比度**。
//
// 暗部"平"的真实来源（同区域实测，1777×2360）：
//   rawpy 单帧(无融合/降噪/锐化) 相对对比度 19.4% / 我们 17.6% / PC 25.9%
//   ⇒ 我们的链路只抹了 1.8 个百分点（不是主因），**PC 反而比"完全不处理"高 6.5 个点**，
//   说明它在链路里增加了细节。候选：去马赛克档（我们 RCD、它 AMaZE 系）。
//   未定论，留待后续。
inline constexpr float kSigmoidDefaultContrast = 1.5f;
inline constexpr float kSigmoidDefaultSkew = 0.0f;
inline constexpr float kSigmoidDefaultWhiteTarget = 100.0f;
inline constexpr float kSigmoidDefaultBlackTarget = 0.0152f;
inline constexpr float kSigmoidDefaultHuePreservation = 100.0f;

}  // namespace filmkit
