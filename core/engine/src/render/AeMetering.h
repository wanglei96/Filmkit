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
// ① 高光：`P99.5` 提亮后不得撞**当前引擎的死白起点**（− 余量 0.5 EV）。
//    物理含义：RAW 饱和只说明没有更多光子；决定"会不会死白"的是引擎曲线，
//    而引擎 1（sigmoid）比引擎 0（filmic）多 1.7 EV 余量。
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
// ⚠ 这是**采集域**白点（RAW 饱和），不是 AE 该用的高光上限（见 kAeDeadWhiteMarginEv）。
inline constexpr float kAeRawWhiteEv = 2.438307f;

// 渲染域白点 = **当前引擎曲线的死白起点**（量化后输出不再增长的位置），
// 相对中灰 0.1845 的 EV。**2026-10-20 修正**：AE 此前压到 RAW 饱和白点
// （2.438 EV），而曲线能容纳得更远 —— 等于白白多压 1.1~1.7 EV
// （真机 0.11.69 实测 aeEv -1.97、mean 27.6、窗外死白）。
//
// 数值来源：`docs/reports/stage2-sigmoid-REPORT.md` §4.2（合成 EV 阶梯喂真实 shader，
// 判定"量化后不再增长"）——**引擎 0 filmic 4.52 EV / 引擎 1 sigmoid 6.22 EV**。
// ⚠ 引擎 1 是默认引擎，它的白点**不能**从 `[38]` 读：`[38]` 在 `EngineScope` 的
// `FILMIC_OWNED_SLOTS` 里，引擎 1 下会被归零（0.11.70 的错误）。
inline constexpr float kAeSigmoidDeadWhiteEv = 6.22f;
inline constexpr float kAeFilmicDeadWhiteEv = 4.52f;
// filmic 的 `[38]`（white_ev）可调时死白起点随之平移：white_ev 4.18 → 4.52（+0.34）。
inline constexpr float kAeFilmicWhiteOffsetEv = 0.34f;

// 显示中灰锚点：darktable `MIDDLE_GREY`（与 filmic 的 kGrey 同源）。
inline constexpr float kAeMiddleGrey = 0.1845f;

// 高光余量（相对 **RAW 白点**）：量化/舍入的安全边距。
// 2026-10-20 定案：AE 的高光上限用 **RAW 白点 2.438 EV**（不是引擎死白点 6.22）。
// 用 6.22 时（0.11.70~0.11.81）天空这类大面积高光会被推到曲线肩部 ⇒ 真机反复
// "死白一片"（0.11.81：hi240 18.89%）。RAW 白点是"光子还能区分的上限"，压到它
// 之内的高光必然有层次；亮度损失由局部影调（tone equalizer）的暗部 +0.3EV 补回。
inline constexpr float kAeHighlightHeadroomEv = 0.1f;

// 允许裁切的像素比例（PhotonCamera `maximumClippedFraction`，同值 0.005）。
inline constexpr float kAeMaxClippedFrac = 0.005f;
// 未超裁切阈值时的高光约束 = 无穷（不介入）。
inline constexpr float kAeNoHighlightLimitEv = 1e3f;

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
  // 渲染域死白起点（EV 相对中灰）：**按引擎填**（sigmoid 6.22 / filmic [38]+0.34）。
  // 高光上限 = white_ev − kAeHighlightHeadroomEv。
  float white_ev = kAeSigmoidDeadWhiteEv;
  // v0.11.77：RAW 裁切像素比例（>=98% 白点）。PC legacy matcher 的
  // `maximumClippedFraction = 0.005` 判据的**量本身** —— 未超阈值时不压高光，
  // 哪怕分位很亮（此前用 P99.5 亮度当代理，会把"0.1% 像素很亮"误判成裁切）。
  float sat_frac = 0.0f;
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
