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
inline constexpr float kSigmoidDefaultContrast = 1.5f;
inline constexpr float kSigmoidDefaultSkew = 0.0f;
inline constexpr float kSigmoidDefaultWhiteTarget = 100.0f;
inline constexpr float kSigmoidDefaultBlackTarget = 0.0152f;
inline constexpr float kSigmoidDefaultHuePreservation = 100.0f;

}  // namespace filmkit
