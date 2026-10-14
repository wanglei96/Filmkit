// FilmKit 引擎 1（darktable sigmoid / generalized log-logistic）的 CPU 侧参数求解。
//
// 契约（**不可改**）：`core/engine/src/film/SigmoidParams.h`
//
// 一手来源：darktable master `src/iop/sigmoid.c`
//   https://github.com/darktable-org/darktable/blob/master/src/iop/sigmoid.c
//   （2026-10-13 架构师抓取；2026-10-14 本卡重新下载逐字核对 —— **与任务卡副本逐字一致**，
//     本文件所有 `sigmoid.c:NNN` 行号即该 master 版本的行号，可 `curl` 后 grep 复核。）
//
// ── 逐行对照表（官方行号 → 本文件行号）──────────────────────────────────────
//   sigmoid.c:37      #define MIDDLE_GREY 0.1845f            → SigmoidParams.h kSigmoidMiddleGrey
//   sigmoid.c:300-316 _generalized_loglogistic_sigmoid(...)  → L39-58  generalizedLoglogisticSigmoid
//   sigmoid.c:307     clamped_value = fmaxf(value, 0.0f)     → L48
//   sigmoid.c:311     film_response = powf(...)              → L51
//   sigmoid.c:312     paper_response = magnitude * powf(...) → L53-54
//   sigmoid.c:315     return dt_isnan(...) ? ...             → L57
//   sigmoid.c:318-384 commit_params(...)                     → L60-135 computeSigmoidParams
//   sigmoid.c:333     ref_film_power                         → L77
//   sigmoid.c:334     ref_paper_power                        → L78
//   sigmoid.c:335     ref_magnitude                          → L79
//   sigmoid.c:336     ref_film_fog                           → L80
//   sigmoid.c:337-338 ref_paper_exposure                     → L81-82
//   sigmoid.c:339     delta = 1e-6f                          → L83
//   sigmoid.c:340-346 ref_slope（中心差分）                   → L84-90
//   sigmoid.c:348     paper_power = powf(5.0f, -skew)        → L93
//   sigmoid.c:351     temp_film_power = 1.0f                 → L96
//   sigmoid.c:352     temp_white_target                       → L97
//   sigmoid.c:353-354 temp_white_grey_relation                → L98-99
//   sigmoid.c:355     temp_paper_exposure                     → L100
//   sigmoid.c:356-362 temp_slope（中心差分）                   → L101-107
//   sigmoid.c:365     film_power = ref_slope / temp_slope     → L111
//   sigmoid.c:368     white_target = 0.01f * ...              → L114
//   sigmoid.c:369     black_target = 0.01f * ...              → L115
//   sigmoid.c:370-371 white_grey_relation                     → L116-117
//   sigmoid.c:372-373 white_black_relation                    → L118-119
//   sigmoid.c:375-377 film_fog                                → L121-123
//   sigmoid.c:378-379 paper_exposure                          → L124-125
//   sigmoid.c:381     color_processing（本卡不实现）           → 见下方"刻意省略"
//   sigmoid.c:382     hue_preservation = fminf(fmaxf(...))    → L130
//
// ── 浮点口径（**必须保持**）────────────────────────────────────────────────
// darktable 的 `dt_iop_sigmoid_data_t` 字段（sigmoid.c:166-167）与 `commit_params` 内
// 全部局部量都是 `float`，幂运算走 `powf` ⇒ 官方**实跑是 float32 全程**。
// 本实现用 `std::pow(float, float)`（C++ 的 float 重载，libc++/glibc 下即 `powf`）+
// `std::fmax` / `std::fmin` / `std::isnan` 保持同一口径。
// ⚠ 这不是"精度不够"：contrast = 1.5 时 float32 的 `film_power` = 1.490909，而 double
//   口径给 1.5（`film_power ≡ contrast` 在 double 下是精确恒等式）。两者差 ~0.6%，
//   混用会让"与官方逐位对齐"失效 —— 见 docs/research/17-独立复核-影调架构量化.md §1.3。
//   PhotonCamera 的硬编码常量（1.5 / 0.00142637086 / 0.354355423）正是 **double** 口径，
//   不是"PC 更正确"，只是口径不同（本卡报告 §1 有完整对照）。
//
// ── 刻意省略（**不是简化，是越界**）─────────────────────────────────────────
//   · sigmoid.c:381 `color_processing`（per-channel / RGB-ratio 二选一）与
//     sigmoid.c:384-389 `purity` / `inset[3]` / `rotation[3]` / `base_primaries`
//     —— 这些属于 GLSL 侧的**色彩处理路径选择**与 AgX 式 primaries 调整，
//     不在本卡契约（`SigmoidParams` 只有 7 个标量）内。
//   · 官方在 `commit_params` 之前还有 `_calculate_adjusted_primaries`（:386-457）等，
//     同上，与本 7 个标量无关。
//   · 契约头的 `SigmoidParams` 没有 `color_processing` 字段 ⇒ 无法也无需赋值。
//     GLSL 侧走哪条路径由引擎注入段自己决定（`docs/engine-slots-design.md` §7）。

#include "SigmoidParams.h"

#include <cmath>

namespace filmkit {

namespace {

// ── sigmoid.c:298-316 `_generalized_loglogistic_sigmoid`（逐字）──────────────
// 官方原文（含注释）：
//   // Declared here as it is used in the commit params function
//   DT_OMP_DECLARE_SIMD(uniform(magnitude, paper_exp, film_fog, film_power, paper_power))
//   static inline float _generalized_loglogistic_sigmoid(const float value, ...)
// 本函数省略 `DT_OMP_DECLARE_SIMD`（OpenMP SIMD 声明，只影响向量化，不影响数学结果；
// 本卡是每帧一次的 CPU 标量求解，无 SIMD 收益）。
inline float generalizedLoglogisticSigmoid(float value,
                                           float magnitude,
                                           float paper_exp,
                                           float film_fog,
                                           float film_power,
                                           float paper_power) {
  // sigmoid.c:307
  const float clamped_value = std::fmax(value, 0.0f);
  // sigmoid.c:308-310（官方注释逐字保留：为什么用这个等价形式）
  //   // The following equation can be derived as a model for film + paper but it has a pole at 0
  //   // magnitude * powf(1.0f + paper_exp * powf(film_fog + value, -film_power), -paper_power);
  //   // Rewritten on a stable around zero form:
  // sigmoid.c:311
  const float film_response = std::pow(film_fog + clamped_value, film_power);
  // sigmoid.c:312
  const float paper_response = magnitude * std::pow(film_response / (paper_exp + film_response), paper_power);

  // sigmoid.c:314-315  // Safety check for very large floats that cause numerical errors
  //                    dt_isnan → std::isnan
  return std::isnan(paper_response) ? magnitude : paper_response;
}

}  // namespace

// ── sigmoid.c:318-384 `commit_params`（逐字；只保留与本卡 7 个标量相关的部分）──
SigmoidParams computeSigmoidParams(float middle_grey_contrast,
                                   float contrast_skewness,
                                   float display_white_target,
                                   float display_black_target,
                                   float hue_preservation) {
  SigmoidParams out;

  // sigmoid.c:325-330（官方注释逐字，这是四条设计约束，不是可选的）：
  //   /* Calculate actual skew log logistic parameters to fulfill the following:
  //    * f(scene_zero) = display_black_target
  //    * f(scene_grey) = MIDDLE_GREY
  //    * f(scene_inf)  = display_white_target
  //    * Slope at scene_grey independent of skewness i.e. only changed by the contrast parameter.
  //    */

  // sigmoid.c:332  // Calculate a reference slope for no skew and a normalized display
  // sigmoid.c:333
  const float ref_film_power = middle_grey_contrast;
  // sigmoid.c:334
  const float ref_paper_power = 1.0f;
  // sigmoid.c:335
  const float ref_magnitude = 1.0f;
  // sigmoid.c:336
  const float ref_film_fog = 0.0f;
  // sigmoid.c:337-338
  const float ref_paper_exposure
      = std::pow(ref_film_fog + kSigmoidMiddleGrey, ref_film_power)
        * ((ref_magnitude / kSigmoidMiddleGrey) - 1.0f);
  // sigmoid.c:339
  const float delta = 1e-6f;
  // sigmoid.c:340-346（中心差分；`/ 2.0f / delta` 的**分两步除法顺序**逐字保留 ——
  // 它不是 `/(2.0f*delta)`，float 下结果不同）
  const float ref_slope
      = (generalizedLoglogisticSigmoid(kSigmoidMiddleGrey + delta, ref_magnitude, ref_paper_exposure,
                                       ref_film_fog, ref_film_power, ref_paper_power)
         - generalizedLoglogisticSigmoid(kSigmoidMiddleGrey - delta, ref_magnitude, ref_paper_exposure,
                                         ref_film_fog, ref_film_power, ref_paper_power))
        / 2.0f / delta;

  // sigmoid.c:347-348  // Add skew
  out.paper_power = std::pow(5.0f, -contrast_skewness);

  // sigmoid.c:350  // Slope at low film power
  // sigmoid.c:351
  const float temp_film_power = 1.0f;
  // sigmoid.c:352
  const float temp_white_target = 0.01f * display_white_target;
  // sigmoid.c:353-354
  const float temp_white_grey_relation
      = std::pow(temp_white_target / kSigmoidMiddleGrey, 1.0f / out.paper_power) - 1.0f;
  // sigmoid.c:355
  const float temp_paper_exposure = std::pow(kSigmoidMiddleGrey, temp_film_power) * temp_white_grey_relation;
  // sigmoid.c:356-362
  const float temp_slope
      = (generalizedLoglogisticSigmoid(kSigmoidMiddleGrey + delta, temp_white_target, temp_paper_exposure,
                                       ref_film_fog, temp_film_power, out.paper_power)
         - generalizedLoglogisticSigmoid(kSigmoidMiddleGrey - delta, temp_white_target, temp_paper_exposure,
                                         ref_film_fog, temp_film_power, out.paper_power))
        / 2.0f / delta;

  // sigmoid.c:364-365
  //   // Figure out what film power fulfills the target slope
  //   // (linear when assuming display_black = 0.0)
  out.film_power = ref_slope / temp_slope;

  // sigmoid.c:367  // Calculate the other parameters now that both film and paper power is known
  // sigmoid.c:368
  out.white_target = 0.01f * display_white_target;
  // sigmoid.c:369
  out.black_target = 0.01f * display_black_target;
  // sigmoid.c:370-371（注意：官方这里重算一次 `white_grey_relation`，用的是
  // `module_data->white_target`（= :368 刚写入的值）而不是 `temp_white_target`。
  // 两者数值恒等，但**运算序列不同** —— 逐字保留，不合并。）
  const float white_grey_relation
      = std::pow(out.white_target / kSigmoidMiddleGrey, 1.0f / out.paper_power) - 1.0f;
  // sigmoid.c:372-373
  const float white_black_relation
      = std::pow(out.black_target / out.white_target, -1.0f / out.paper_power) - 1.0f;

  // sigmoid.c:375-377
  out.film_fog = kSigmoidMiddleGrey * std::pow(white_grey_relation, 1.0f / out.film_power)
                 / (std::pow(white_black_relation, 1.0f / out.film_power)
                    - std::pow(white_grey_relation, 1.0f / out.film_power));
  // sigmoid.c:378-379
  out.paper_exposure
      = std::pow(out.film_fog + kSigmoidMiddleGrey, out.film_power) * white_grey_relation;

  // sigmoid.c:381 `module_data->color_processing = params->color_processing;`
  //   ⇒ **省略**：契约结构体无此字段（见文件头"刻意省略"）。
  // sigmoid.c:382
  out.hue_preservation = std::fmin(std::fmax(0.01f * hue_preservation, 0.0f), 1.0f);

  return out;
}

}  // namespace filmkit
