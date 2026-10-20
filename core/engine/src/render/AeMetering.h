#pragma once

// 自动曝光决策（v0.11.61，2026-10-20）
//
// 目标（用户 2026-10-20 原话）：高光不过曝、暗部有细节、亮度接近现实。
// 本模块只负责**渲染端**的全局 EV：不改变采集（光子），只决定成片把 RAW 的
// 哪个亮度映射到显示中灰。
//
// 为什么需要它
// ------------
// 采集端的高光保护（ExposurePlanner ④）必须在**拍摄前**决策，只能拿上一张成片
// 的 P99.9 做预测 ⇒ 会话第一张必然没有统计（真机日志 `hlEv:-99`），场景一变就
// 用错统计。PC/MGC 的做法相反：先拍 RAW，再按**本张**统计决定 BaselineExposure。
// 本模块就是把那一层搬到我们的渲染链里：`film_src`（RAW 线性、曝光前）统计
// P50 / P99.9，据此一次性决定 film pass 的 EV。
//
// 两条约束（HDR+ §4 + darktable autodetect 口径）
// ------------------------------------------------
// ① 高光：`P99.9` 提亮后不得撞 RAW 饱和数据白点（2.438 EV，减去 headroom）。
//    物理含义：RAW 一旦饱和，信息已丢，任何曲线都救不回。
// ② 中灰：`P50` 应落在显示中灰 18.45%（= sRGB 118/255）。
//    物理含义：这就是"亮度接近肉眼"的可测量定义 —— 不是"平均亮度好看"。
//
// 冲突时**高光优先**（HDR+ §4 原文："set this exposure low enough to avoid
// blowing out highlights"）。代价是画面偏暗，靠后续的 bracket 采集（短帧补
// 高光）从根上解决，而不是在这里放宽约束。
//
// 纯函数、不依赖 GL —— 与 SigmoidParams 同一条设计约束，可离线对拍与自测。

namespace filmkit {

// RAW 饱和数据白点（log2 相对中灰）：`log2(1.0 / 0.1845)`。
// 与 Kotlin `ExposurePlanner.WHITE_POINT_EV` 同源同值 —— 两处必须一起改。
inline constexpr float kAeRawWhiteEv = 2.438307f;

// 显示中灰锚点：darktable `MIDDLE_GREY`（与 filmic 的 kGrey 同源）。
inline constexpr float kAeMiddleGrey = 0.1845f;

// 高光余量：白点前留 0.1 EV，避免量化/舍入把 P99.9 推过白点。
inline constexpr float kAeHighlightHeadroomEv = 0.1f;

// 自动 EV 的安全限幅：欠曝上限 = **2 EV**，与 gainmap 的 `[0.25, 4.0]`（±2 EV）对齐 ——
// AE 把 base 压暗超过 2 EV 后，Ultra HDR 的增益图就表达不了（被截断 ⇒ 高光信息丢失），
// 那等于用 HDR 的动态范围换 SDR 的暗部，得不偿失。真正的 >2 EV 场景交给 bracket 采集。
// 提亮上限 2 EV：再高是在用 EV 拉噪声，应该交给多帧/降噪。
inline constexpr float kAeMaxUnderEv = 2.0f;
inline constexpr float kAeMaxOverEv = 2.0f;

/** 输入 = 本张 RAW 的统计（`FrameRenderer::measureSceneStats` 的 p_mid / p_hi995）。 */
struct AeInputs {
  float p_mid = 0.0f;      // 线性 P50（max 通道，与 filmic 同口径）
  float p_hi995 = 0.0f;    // 线性 P99.5（max 通道）—— 允许 0.5% 像素超白点
  float user_ev = 0.0f;    // 已经下发给 film pass 的 EV（基准 + 用户 + 采集欠曝补偿）
  bool valid = false;      // 统计失败 ⇒ 不介入（保持历史行为）
};

/** 决策结果；`ev` 是**额外**加在 film pass exposure_ev 上的量。 */
struct AeDecision {
  float ev_mid = 0.0f;      // 中灰锚定要求的 EV（已扣 user_ev）
  float ev_hl = 0.0f;       // 高光约束允许的 EV（已扣 user_ev）
  float ev = 0.0f;          // 最终采用（clamp 后）
  const char* why = "none"; // none / invalid / mid / highlight
  bool active() const { return why[0] != 'n' && why[0] != 'i'; }
};

/** 纯决策：无副作用，不读全局状态。 */
AeDecision decideAe(const AeInputs& in);

/**
 * 自测（零依赖断言库）。在 `FrameRenderer::initialize` 里跑一次：
 * 失败只打日志不中断（诊断包能看到），成功不产生噪声。
 * 覆盖：中灰锚定、高光优先、限幅、无效输入。
 */
bool aeMeteringSelfTest(char* err, int err_cap);

}  // namespace filmkit
