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

// 引擎 1 的默认参数（方案 §7 + 17 号报告 §1.6 的「同白纸亮度」口径）：
// contrast = 1.2 ⇒ 白纸（2.30 EV）输出 **203.60 LSB**（float32 口径），与引擎 0
// （filmic 白点 4.0）的 204 LSB 对齐 ⇒ 判据 ①「可分辨跨度」可在**同白纸亮度**下比较。
// 严格对齐 204.0 LSB 需 `display_white_target = 99.4`（17 号 §1.6 用的值）；默认 100
// 与它差 0.4 LSB，远小于判据粒度（1 LSB）⇒ 默认用官方值 100，报告里两档并列。
// ⚠ 这不是"照抄 darktable 默认 1.5"：1.5 时白纸 218.84 LSB（float32），与引擎 0
//   不同口径，判据 ① 无法直接比较。1.2 是**判据口径**要求的值，且它仍是标准 sigmoid 参数。
inline constexpr float kSigmoidDefaultContrast = 1.2f;
inline constexpr float kSigmoidDefaultSkew = 0.0f;
inline constexpr float kSigmoidDefaultWhiteTarget = 100.0f;
inline constexpr float kSigmoidDefaultBlackTarget = 0.0152f;
inline constexpr float kSigmoidDefaultHuePreservation = 100.0f;

}  // namespace filmkit
