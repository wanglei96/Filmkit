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

// 显示中灰锚点：darktable `MIDDLE_GREY`（与 filmic 的 kGrey 同源）—— **EV 基准**，
// 高光判据的"相对中灰"仍以它为分母（改它会移动 RAW 白点的 EV）。
inline constexpr float kAeMiddleGrey = 0.1845f;

// 中灰**目标**（线性）：中灰锚定要落到的显示亮度。
// 2026-10-21 真机对比（同场景）：我们 mean 74.7 / p50 94，PC mean 147.1 / p50 188
// ⇒ 18.45%（sRGB 118）比 PC 暗约 1.4 EV。默认取 0.45（sRGB ~180），对齐 PC 的观感。
inline constexpr float kAeTargetMid = 0.45f;

// 高光余量（相对 **RAW 白点**）：量化/舍入的安全边距。
// 2026-10-20 定案：AE 的高光上限用 **RAW 白点 2.438 EV**（不是引擎死白点 6.22）。
// 用 6.22 时（0.11.70~0.11.81）天空这类大面积高光会被推到曲线肩部 ⇒ 真机反复
// "死白一片"（0.11.81：hi240 18.89%）。RAW 白点是"光子还能区分的上限"，压到它
// 之内的高光必然有层次；亮度损失由局部影调（tone equalizer）的暗部 +0.3EV 补回。
inline constexpr float kAeHighlightHeadroomEv = 0.1f;

// v0.11.87：**有 bracket 信息时**的高光上限 = 显示域 ~243 LSB 的位置（4.8 EV）。
// 为什么不再用 RAW 白点 2.438：融合后 >1.0 的高光是短帧带回来的**真实信息**，
// 不是 RAW 裁切。把它压回白点等于把 bracket 的收益扔掉（真机 4641：aeP995 4.08EV，
// 压 1.74EV ⇒ 中灰掉到 sRGB 60，室内全黑）。
//
// 为什么也不用到死白点 6.22：曲线在 6.22 前 1.5 EV 只有 ~10 LSB（阶段 2 报告 §4.2：
// 5.0EV→247.35、6.0EV→251.52），推到那里等于"有值但看不出层次"。4.8 EV ≈ 243 LSB
// 是"还能分辨"的上限，与 kAeMaxClippedFrac=0.5% 的裁切口径配套。
//
// 用法（FrameRenderer::passAutoExposure）：仅当 bracket 短帧的裁切比例 < 0.5% 时
// 才允许放宽到这个值；短帧也饱和（信息真的没了）时退回 kAeRawWhiteEv。
inline constexpr float kAeDisplayClipEv = 4.8f;

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
  // v0.11.88：**强制启用高光约束**。旧逻辑"裁切比例 ≤0.5% ⇒ 不约束"在 bracket 下是
  // 灾难：真裁切 0 ⇒ 高光判据整个关掉 ⇒ 提亮到限幅、白墙/窗外撞白（真机 2712：
  // aeEv +2.0、hi240 3.97%）。裁切比例只该决定"白点用哪个"，不该决定"要不要约束"。
  bool force_hl = false;
  // v0.11.88：**大面积高光软约束**。P95 提亮后不得越过 soft_ev（230 LSB 附近）——
  // 管的是"天空/白墙占画面 30-40%"这类 P99.5 看不见的过亮（真机 2815 天空灰白）。
  float p_hi95 = 0.0f;
  // 0.12.47：4.0 → 4.3（用户要求"逆光的也放宽试试"）。真机 102721/102756 的 aeMid
  // 被 ev_hl_soft 卡在 1.01（p95 1.46 ⇒ 满额只能 1.01），target 提高对它无效；
  // 放宽到 4.3 让逆光窗也能多提 0.3EV。代价 = hi240 上升（102756 已 9.06%），
  // 若天空死白则回退这一项，target 0.18 不变。
  float soft_ev = 4.3f;
  // v0.12.46：**鲁棒测光 A/B**。`robust=true` 时用 `p_robust`（截尾 log 均值）替代
  // `p_mid`，目标中灰改用 `target_robust`（固定值）而不是取景器折算值。
  // 为什么：取景器目标 `pv_geo/域比` 与 RAW 实测有系统性偏差（真机 0723 低 0.39EV、
  // 0601 高 1.46EV），拿它当目标只能做保守补偿；固定目标 + 鲁棒统计才可标定。
  float p_robust = 0.0f;
  bool robust = false;
  float target_robust = 0.18f;   // 0.12.47：0.17 → 0.18（+0.08EV，用户要"再亮一点点"）
  // 目标中灰（线性）。darktable MIDDLE_GREY = 0.1845 是"中性正确"的锚；
  // PhotonCamera 实测同场景中灰 ≈ 0.5（sRGB 188/255）—— 它匹配取景器，取景器更亮。
  // 2026-10-21：默认对齐 PC（0.45），用户仍可用设置页"亮度偏好"调回标准。
  float target_mid = kAeTargetMid;
  bool valid = false;      // 统计失败 ⇒ 不介入（保持历史行为）
};

/** 决策结果；`ev` 是**额外**加在 film pass exposure_ev 上的量。 */
struct AeDecision {
  float ev_mid = 0.0f;      // 中灰锚定要求的 EV（已扣 user_ev）
  float ev_hl = 0.0f;       // 高光约束允许的 EV（已扣 user_ev）
  float ev_hl_soft = 0.0f;  // v0.11.88：大面积高光（P95）允许的 EV
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
