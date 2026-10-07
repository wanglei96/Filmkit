#pragma once

#include <cstring>

namespace filmkit {

// 胶片模拟参数（M1 简化模型，docs/开发计划.md §5 的子集）。
// 通过 JNI double 数组传输，顺序由 kIdx* 常量与 Kotlin 侧约定保持一致。
struct FilmParams {
  // v0.6.34 起实际为 37 项（[35] auto_tone + [36] local_tone）。
  // 此常量当前无引用者，但正因它停在 36，记忆库 facts 曾按它记成 "kCount = 36"，
  // 与 Kotlin 侧 `CameraPresets.neutral()` 的 `DoubleArray(42)` 保持一致（v0.10.15 起）；
  // 数组越界会直接崩，改这里必须同步改那边。
  static constexpr int kCount = 59;  // v0.10.180：+[58] rcd_p3_split（v0.10.179：+[57] hl_rb_recon（v0.10.178：+[56] rcd_g_orig（v0.10.175：+[55] pc3d_msize、+[54] pc3d（v0.10.173 +[53] df_gate、+[52] cg_scope、+[51] chroma_gate；v0.10.170 +[50] ca_corr；v0.10.169 +[49] hl_grecon；v0.10.165 +[48] moire；v0.10.134 +[47] defringe；v0.10.117 +[46] rcd_cdfix、v0.10.115 +[45] hr_raw、v0.10.111 +[43] white_floor）））

  float exposure_ev = 0.0f;   // 0
  float wb_r = 1.0f;          // 1
  float wb_g = 1.0f;          // 2
  float wb_b = 1.0f;          // 3
  float contrast = 1.0f;      // 4  log 域对比系数
  float shoulder[3] = {0.0f, 0.0f, 0.0f};  // 5..7 高光软肩强度（per-channel）
  float toe[3] = {0.0f, 0.0f, 0.0f};       // 8..10 阴影 toe 强度
  float cross[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // 11..19 log 域染料串扰
  float sat_shadow = 1.0f;    // 20
  float sat_mid = 1.0f;       // 21
  float sat_high = 1.0f;      // 22
  float split_warm = 0.0f;    // 23 阴影偏暖
  float split_cool = 0.0f;    // 24 高光偏冷
  float monochrome = 0.0f;    // 25
  float grain_amount = 0.0f;  // 26
  float grain_size = 1.0f;    // 27
  // M3 色彩内核：基准影调强度（0 = 恒等/旧行为，1 = 完整基准 S 曲线）。
  // 默认 1.0 —— 中灰锚定、只补黑位与高光 rolloff，不是风格化。
  float tone_strength = 1.0f;  // 28
  // [29] 曾为 hl_recon（自研"中性外推"高光重建，CCM 前用未饱和通道外推）：
  // v0.10.15 审计定性为对 darktable 原生处理的简化替代；RAW 域 highlights
  // 模块（[45] hr_raw）落地后全仓零引用，已删除。索引 29 留空不复用
  //（Kotlin 侧 film 数组仍在 29 占位，删除只影响 C++ 解析）。
  // 过曝提示阈值（显示域亮度，0 = 关）：仅预览 passOutput 使用，成片一律 0。
  float zebra_thr = 0.95f;  // 30
  // ── M3 P1 色彩外观层（治"寡淡"）──────────────────────────────────
  // 诊断：中性色准（R/G 0.987）但饱和 -21%、通道分离 -29%、近中性像素多 10pp
  // —— 颜色全挤在中性附近。外观层 = 亮度保持的饱和增益 + 色度 S 曲线。
  float look_strength = 1.0f;  // 31 强度（0 = 恒等，A/B 通路）
  float look_sat = 1.0f;       // 32 饱和增益（luma 不动，色差放大）；1.0 = 中性占位
  float look_chroma = 1.0f;    // 33 色度 S 曲线端点增益；1.0 = 中性占位
  // ── M4 3D LUT（display 域，插在输出 OETF 之后）─────────────────────
  // LUT 资产本身走独立 JNI（nativeEngineSetLut，一次性上传 3D 纹理）；
  // 这里只带**混合强度**：0 = 旁路（A/B 通路），1 = 全量。未选 LUT 时无意义。
  float lut_strength = 1.0f;   // 34
  // ── M4-6 自适应影调（默认开、可关）──────────────────────────────
  // 真机实测：M 档实拍场景的**中间调**普遍偏低（与 PhotonCamera 同机位对照，
  // 中位 79/255 vs 131，而高光只差 1.1×）—— 固定曝光补偿救不了。
  // ── v0.7.3 起语义变更，v0.7.6 默认改为 0 ─────────────────────────────
  // ⚠️ v0.10.16 语义升级 = **黑白点成对自动**（darktable autotune 原生语义：
  // black = P0.5 → clamp(log2(p/grey), −16, −1)；white = P99.5 → clamp(.., 1, 16)；
  // security 0）。⚠️ v0.10.19 **默认关**（三版真机证伪：0.10.16 中间调低/水壶过曝/
  // 天空过饱和、0.10.17 全面糟糕化、0.10.18 power 5.17 无改善——自动分位定端点把
  // power 推离固定曲线的 3.62 设计值；PC 的 SDR 链实锤无场景归一，固定 filmic 默认
  // 参数 + DngBaselineExposure + AE 曝光决策）。HR 裁剪阈值已与曲线解耦（恒 1.0），
  // 本开关降级为可选实验工具；亮度校准 = [0] base_ev。
  float auto_tone = 0.0f;      // 35 0 = 关（黑白点用 [37]/[38]，默认）；1 = 成对自动
  // ── M4-7 局部色调映射（默认开、可关）────────────────────────────
  // 全局曲线在高 DR 场景（边缘高光 + 中央暗部）里两难：保高光则中央暗、
  // 提中央则高光爆（离线实测中位锚定会让 6.6%–13% 像素过曝）。
  // 本项 = 暗部线性提亮比例（局部亮度越低越满额，到 knee 以上归零）：
  // 0 = 关（A/B 通路），>0 = 强度。高光区强度为 0，故与自适应影调互不冲突。
  float local_tone = 0.25f;    // 36 0 = 关；>0 = 暗部线性提亮比例（标定旋钮）
  // ── v0.7.6：filmic 的黑白点交给用户配置（PC 的做法）─────────────────
  // 单位 = **相对中灰 18.45% 的 EV**，物理量，与机型/曲线无关：
  //   black_ev = "多暗算黑"（黑点是曲线的 toe 端点，不是硬截断）
  //   white_ev = "多亮算白"（显示上限锚在哪）
  // 默认 −7.65 / +4.56 = darktable 默认值，也是 PhotonCamera 的固化值（两边一致）。
  // 为什么不自动：v0.7.3–v0.7.5 实测自动黑点（P0.5 分位）本质是"把每个场景最暗的
  // 0.5% 钉在显示黑"—— 与已被否掉的 kTarget 中位锚定同型（抹掉"这个场景暗部有多深"），
  // 实测比 PC 的固定值高 2EV ⇒ 暗部压得更死、画面更暗。
  // auto_tone 开时黑点改由场景分位自动，白点仍用这里配置的值。
  float filmic_black_ev = -7.65f;  // 37
  float filmic_white_ev = 4.56f;   // 38
  // ── v0.9.6：轻度锐化（luma USM，OETF 前线性显示域）─────────────────────
  // 管线定稿顺序：滚降后、OETF 前（kFsOut 内）。为什么不在 OETF 后（PC 的
  // sharpen 在 sRGB 域）：sRGB 斜率在暗部 >1，会放大暗部噪声。乘法式相对增益
  // 保色度坐标。0 = 关（A/B 通路）；默认 0 = 缺参/旧长度时保持旧行为，
  // Kotlin 侧 CameraPresets[39] 给标定值（0.5）。
  float sharpen = 0.0f;        // 39
  // ── v0.10.13/14：filmic 之后的 gamut 收尾（标准算法补全 + 实测修正）──────
  // v0.10.13 实装 darktable v7 完整五步；真机 A/B 泛白（暗部色度 -17~33%，链级
  // 模拟定位 = 跨域的 Ych 色度 min + desaturate 在无场景归一链上成全局去饱和）。
  // v0.10.14 只保留保险丝职责：Y 显示域 clamp / Yrg 锥空间检查 / 恒 Y 恒色相
  // 解析 clip chroma（sRGB 色域映射）——只作用于病态与域外像素，域内零改动。
  // 默认 1.0（开），0 = A/B 通路。
  float gamut = 1.0f;          // 40
  // ── v0.10.15：darktable filmic 高光重建（à trous 小波）───────────────────
  // darktable filmicrgb.c 的原生功能（enable_highlight_reconstruction +
  // mask/inpaint/wavelets 全链，参数 = darktable 默认）；PC 完整移植、我们此前
  // 用自研"中性外推"（[29]，已删）替代并在审核中定为简化。
  // 本层作用于线性 RGB 域（CCM 后、filmic 前），修复部分通道裁剪高光
  // （灯管/过曝边缘的品红残留即此类）。
  // ── v0.10.162：默认**关**（1 = 开，0 = 关）────────────────────────────
  // 依据：对标对象 PC 没有小波高光重建模块（它的高光处理只有 OpposedGL 一套
  // = 本项目的 [45] hr_raw），本模块与其职责重复 ⇒ 按 PC 路子本该退场；先默认关、
  // 链与开关保留（用户可手动开回做 A/B）。Kotlin 侧 prefs 默认值同步为 false
  // （CameraController.applyHrWaveletPref 是决定下发值的那一层）。
  // v0.10.160 的档位 2 三处改动（mask 硬限 / uAchroma / β=0）已一并回退，
  // 本模块行为逐字节回到 v0.10.159 状态。
  float hr_wavelet = 0.0f;     // 41
  // ── v0.10.27：白点高光感知（auto_tone 开时生效）──────────────────────────
  // 真机定案："高光太白压不住"的根因 = 显示域统计 clamp 使 P99 对线性 ≥1.0 的
  // 强光源失明 → whiteEv 恒钉 2.438 下限 → dr 被压到 ~8EV → 肩部只覆盖 2.5EV，
  // 高光平顶白墙（151748 高光块 7.99%）。修正 = 对数域统计（256 桶）+ P99.9 驱动
  // 白点上探（≤+1.5EV、物理钳制不变）+ darktable contrast 参数按 power 前后比
  // 补偿中间调回退。1 = 开（默认）；0 = A/B 通路（v0.10.18 行为）。
  float white_hl = 1.0f;       // 42
  // 43：filmic 白点下限的场景自适应边距（EV）。0 = 旧行为（下限恒为 RAW 饱和数据
  // 白点 2.438）；>0 = 下限改为 min(2.438, w99 + 本值) —— 治"场景高光远低于饱和点、
  // 白点却被物理下限钉高 1.5EV ⇒ 高光全落在肩部以下不被压缩"（2026-10-03 真机：
  // 红杯釉面密集高光点变白斑，w99 仅 1.03EV 而白点 2.53EV）。
  float white_floor = 0.0f;    // 43
  // 44：白点上探幅度上限（EV）。旧硬编码 +1.5 —— 真机实测（2026-10-03）该闸**每次
  // 都满额**：红杯场景 w99 仅 1.03EV，白点被 wmax（单点最大值）推到 w99+1.5 = 2.53EV，
  // 比场景高光高 1.5EV ⇒ 1% 以上的高光全落在肩部以下不被压缩 ⇒ 密集高光点变白斑。
  // 降到 0.5 后白点 = w99+0.5，既保留"P99 看不见的近饱和高光"上探能力（白壶案），
  // 又不再把整段高光推白。
  float white_probe = 0.5f;    // 44
  // 45：RAW 域高光重建（darktable highlights 模块 "inpaint opposed"）。作用在
  // 去马赛克**前**的线性 mosaic（tex_mos_，已扣黑阶 + WB，白点 1.0）：对被裁剪
  // 通道用"对立通道"（3×3 photosite 邻域内另两个通道的立方均值）外推 + 全图
  // 色度校正。补的是 [41] hr_wavelet 够不到的一环 —— 后者在 film 域修影调，
  // 恢复不了 RAW 饱和时丢掉的通道信息（2026-10-03 真机：曝光给足使饱和像素 6×，
  // 壶身玻璃反光带留彩色伪影）。默认开；0 = A/B 通路（行为同 exp12）。
  float hr_raw = 1.0f;         // 45
  // 46：RCD 色差段回归 RawTherapee 原版（M3-3）。1 = 原版行为（对角梯度三项 +
  // **无限幅**，边缘感知由梯度反比加权承担）；0 = 旧行为（梯度两项 + clamp 到
  // 4 对角色差范围）。
  // 真机实测（2026-10-03）根因：clamp 的限幅范围取自**跨边界**的 4 对角邻居，
  // 等于把红侧的色差锁进允许区间 ⇒ 红色扩散进白瓷（杯口白瓷深处 Cr +4.3，
  // 边界被抬到 +20~26），窗户过曝区同样出现大面积色块。默认 1（回归原版）；
  // 0 = A/B 通路。设置页开关写 prefs("rcd_cdfix")。
  float rcd_cdfix = 1.0f;      // 46
  // 47：逆光边缘紫边抑制（v0.10.134，RawTherapee Defringe 同形态复现）。
  // 2026-10-04 样片定量：品红伪影 56~66% 贴着物体轮廓（紫边渐晕），平坦区孤立点
  // 仅 2~3%——P4/D-1 的保边判据恰好保护它，整链对紫边近零压制。本层在 P4 后
  // Lab 域做"色度相对局部基线的偏离"检测（RT PF_correct_RT 核心机制）+ Lab a
  // 通道色相门（红物体 a 高，开门区与红分离）+ 低偏离邻居加权替换（L 零损失）。
  // 离线定稿：检测模糊 σ8（全分辨率等效）/ 替换窗口 13×13 / 阈值 t23（k=2.43），
  // 基准样片紫边 ≥10 占比 ↓63%、红物体色度 +1%、σ_HP(R-G) −28%（其中紫边本身
  // 就是 σ_HP 的主要贡献者之一）。默认开；0 = A/B 通路。prefs("defringe")。
  float defringe = 1.0f;       // 47
  // 48：伪彩感知色度降噪强度（档 A，v0.10.165）。把 PhotonCamera 主线
  // `esd3d2_steered.glsl:94-98` 的 `sigZ = max(sigY, min(chromaNoise·MOIRE, 0.2))`
  // **架构**搬进 P4：在现有 7×7 循环的 3×3 子集上算"局部色度非均匀度 × 结构门控"
  // （chromaDev × gate，后者 = |∇Y|/(|∇Y|+σc)），归一成 moire ∈ [0,1)，再放大 P4
  // 的值域阈值：scEff = min(sc·(1 + moire·[48]), 4·sc)。方向与 PC 的 sigZ 一致 ——
  // σc 变大 ⇒ 色度差异更容易被当噪声平均掉 ⇒ 伪彩处更积极平滑色度。
  // 0 = 关（A/B 通路，P4 逐位回到 v0.10.164）；默认 2.0 = 离线标定值
  //（tools 口径：σC(1) 降幅达标的拐点，见 /tmp/stageA/REPORT.md）。
  // 与 PC 的 `MOIRE=1.5` **不是同一量纲**：PC 把度量直接乘成 σ（赋值式），我们是
  // 把 σ 放大 (1+moire·k) 倍（倍率式），不可按数值对标。
  // cap 恒 4·sc ⇒ [48] ≤ 3 时封顶永不触发（σ 放大 ≤ 1+[48]）。
  float moire = 2.0f;          // 48
  // 49：G 通道重建（RAW 域高光重建 kFsHlApply 内"自创机制"两支的总开关，v0.10.169）。
  // 1 = 开 = **现有行为逐位不变**（含 G 三级池化比 + allSat 三通道同步改写两支）；
  // 0 = 关 ⇒ shader 内 `if (uGRecon > 0.5)` 整段跳过，apply 支只剩分支①
  //（darktable opposed 外推 `max(v, refavg+chroma)`），即回到 PhotonCamera
  // OpposedGL 口径。
  // 依据（离线逐位仿真 + 与 PhotonCamera 逐行对照审计）：高光区 B/G 1.0856 →
  // 1.0217、R/G 0.9773 → 1.0051；这两支自创机制贡献"过曝边缘品红"B/G 偏差的 71%
  //（G 三级池化比 +0.0384 / allSat 改写 +0.0255，chroma 强制 0 反向抵消 −0.0218）。
  // 反证（勿轻改）：① allSat 改"只抬不压"离线最差（全图 R−G +0.00381 更红）；
  // ② 单独恢复 chroma 无效（被 allSat 支屏蔽）；③ 普通 G 支改 max 过冲 3×。
  // ⚠ 风险：allSat 在本片占 G 饱和像素 44.15%，台灯类场景可能退化 ⇒
  // **默认必须保持 1.0（不改变默认画质）**，仅作真机同帧 A/B 开关。prefs("hl_grecon")。
  float hl_grecon = 1.0f;      // 49
  // 50：镜头横向色差校正（lateral CA，v0.10.170）。作用在 **D3（RCD 去马赛克后 /
  // passCcm 前）** 的相机 RGB 域：色差是纯几何位移，此域通道尚未被色彩矩阵混合，
  // 改 R/B 不会污染 CCM 的输入；G 作为参考通道逐位不动（PhotonCamera 与 darktable
  // 同口径）。算法 = 自动估计 + 径向位移校正，**不需要镜头配置文件**：
  //   ① 1/8 box 降采样 → readPixels → CPU 端逐环带一维径向 Lucas-Kanade，
  //      解 d·r̂ = k·r 的过原点最小二乘（只用强梯度像素：LCA 只在高对比边缘显现）；
  //   ② 位移 = clamp(k·r, ±uMaxShift)，沿径向双线性重采样 R/B。
  // 判据（两条都必须满足才动作，见 FrameRenderer.cpp 的 kCaMinShift/kCaConsist）：
  //   ① 画面角部位移 ≥ 0.5px；② 内（r<0.6R）外半区拟合出的 k 一致（容差 0.5×）。
  // 任一不满足 ⇒ 该通道不动作；两通道都不动作 ⇒ **整段跳过**（tex_work_ 直通 CCM）。
  // 值语义：0 = 关（A/B 通路，不跑任何 pass）；>0 = 开关 + 估计位移的强度倍率
  // （1.0 = 全额）。prefs("ca_corr")。
  // ⚠ 默认 1.0 的依据与局限见 tools/../报告 docs/reports/cacorr-REPORT.md：对 2026-10-05 的
  // 基准样片（暗室逆光）实测 **R/B 相对 G 的径向位移 ≈ 0.1px、r 相关性 ≈ 0**
  // ⇒ 本模块对该片**不动作**（caApplied=0，零副作用）；默认开的价值只在于覆盖
  // 真实存在 LCA 的镜头/场景。若真机出现异常，把 CameraPresets[50] 改 0.0 即默认关。
  float ca_corr = 1.0f;        // 50
  // ── 51..53：强边缘色度门 v2（v0.10.174；v0.10.173 的形态有严重副作用）─────
  // 依据（一手）：PC `esd3d2.glsl:78-82`（= `esd3d2_steered.glsl:94-98`，两版
  // 逐字相同，后者是 PC 当前默认档）：
  //     chromaDiff *= max(abs(xDelta), abs(yDelta));        // 结构门
  //     float sigZ = max(sigY, min(abs(chromaNoise)*MOIRE, 0.2));
  //     vec4 w2 = 1.0 - d*d/(d*d + sigZ);                   // 色度路，无独立亮度项
  // 判别实验（用户实测，2026-10-05）：PC 强边缘比我们**更锐**（Laplacian 70.54 vs
  // 63.71）却把 ∇>60 紫边压到 3.68（我们 14.13；∇>60 均值旧口径，后被
  // docs/reports/pcfix-REPORT.md 证伪：3.68 含 55% 与边缘无关的全局偏置、把 PC 高估
  // ≈2.2×，正确基准 = 边缘局域超额 1.67 / 剖面 core 1.08；同场景定性结论不变）
  // ⇒ PC 是"只在色度路做梯度自适应抑制、亮度路完全不动"。已排除色差/去马赛克/
  // JPEG 4:4:4/色调曲线/锐化等候选。
  // ★ v0.10.173 的错法与真机后果（2026-10-05，用户："完全不可用，一片红、一片
  //   黄、一片紫"）：把 PC 的**绝对** cap 0.2 当 scEff、并让 wy 的阈值也用它。
  //   ① 0.2 相对真实 σc（中灰 ≈0.01、暗部 ≈0.005）是 20~40× ⇒ |∇L| ≥ 0.1 就吃满
  //      ⇒ **73.7% 的像素**（真机同源读数 p4scEff+ = 34~87%）被放宽；
  //   ② 门只看 |∇L|、不看色度 ⇒ 亮部平坦区（OETF 斜率大）也开门；
  //   ③ D-1 的同一门在 5 级 à trous 上是 **100% 覆盖** ⇒ 每级 detail 被整片抹掉
  //      ⇒ 输出色度 ≈ 最粗尺度（32 px）均值。**"片"量级的色块只能来自这一级。**
  //   离线归因（docs/reports/chromagate2-REPORT.md §3）：只 D-1 的门 hue15blk = 12.65%
  //   （≈ 现状 11.90%）、只 P4 的门 = 3.20%、全关 = 0%；平坦区 mean|R−G| 在
  //   现状下掉 10~25%（真机同源的"色渗"量化）。
  // ★ v2 的形态（三条，全部由离线数据定）：
  //   ① **逐像素门** gate = smoothstep(0.10, 0.30, |∇L|)：下阈 0.10 的依据是
  //      "显示域（8-bit）中心差分 ∇ ≤ 10 的像素必须 gate = 0" —— 线性域
  //      |∇L| = (∇/255)·d(lin)/d(disp)，d(lin)/d(disp) 在 disp=1.0 取最大 2.46
  //      ⇒ ∇=10 对应 |∇L| ≤ 0.0965 < 0.10（可复算）。上阈 = 3×下阈。
  //   ② **有界放宽**：上限 = 16·σc（相对），不再用绝对 0.2 ⇒ 暗部自动收紧到
  //      0.08（σc≈0.005），亮部仍足够。
  //   ③ **权重换成 PC 的 w2 形式**（彻底放弃"放宽 wy"）：w = mix(w_gauss, w2, gate)，
  //      w2 = 1 − d²/(d² + σ)，d = |c − c0|（3D，含亮度差）。gate = 0 ⇒ 逐位等于
  //      改动前。**D-1 只在最细两级**（uStep ≤ 2 = 2/4 px，与紫边同尺度）。
  //   收益/代价（4 张样片，成片域全链模拟，口径见 docs/reports/chromagate2-REPORT.md §4）：
  //   ∇>60 紫边 0.41~0.77×（现状 0.19~0.68×）；平坦区 mean|R−G| 只降 0.01~1.4%
  //   （现状降 10~25%）；色相偏移 >15° 的块占比 0.30~2.8%（现状 1.9~17.3%）。
  //   [51] chroma_gate = κ（0 = 关 ⇒ P4/D-1 逐位回到 v0.10.172）。
  //        κ=2 沿用 PC 的 0.2 标定（成片域 ∇≈60 在中灰对应线性域 |∇L| ≈ 0.1）。
  //        真机 prefs("chroma_gate")，**默认见 CameraPresets.neutral()**。
  //   [52] cg_scope = 权重形式（1 = PC 的 w2 = 1−d²/(d²+σ)，**默认**；
  //        0 = 字面版：只放大 wc 的阈值、权重仍是 gauss，实测近乎无效，
  //        留作 A/B 反证）。
  //   [53] df_gate = 紫边抑制判据的梯度自适应强度（0 = 关 = v0.10.172 行为）。
  //        >0 时 kFsDefringeApply 的阈值 thr = uThreshK·uChromaAvg/(1+df_gate·|∇L|)
  //        —— 低风险替代方案（只作用于已判定的紫边像素），**默认关**，待真机拍板。
  float chroma_gate = 2.0f;    // 51 κ（0 = 关）
  float cg_scope = 1.0f;       // 52 1 = PC 的 w2 权重形式；0 = 只放大 wc（字面版）
  float df_gate = 0.0f;        // 53 紫边判据梯度自适应（0 = 关）
  // ── 54/55：PC ESD3D2 完整移植（v0.10.175）──────────────────────────────
  // 一手来源（逐行读）：PC `denoise/esd3d2_steered.glsl`（PC 默认档）+
  // `postpipeline/ESD3D2.java`（核尺寸公式 + 两趟编排）+ `utils/convdiff.glsl`
  // （GradBuffer 算子）+ `denoise/guidedupsample.glsl` + `GLUtils.java:238-280`
  // （gaussdown）。**与 v0.10.174 的差别不是判据、是结构**：
  //   ① 核：MSIZE×MSIZE（PC 公式给 7..21；本机噪声模型 ⇒ 11），对称折叠取四象限；
  //   ② 尺度：噪声自适应降采样（scale 1..4）→ 低分辨率趟（MOIRE = 0）→
  //      guided upsample → 全分辨率第二趟（MOIRE = 1.5）；
  //   ③ 伪彩强度：chromaDiff ×**convdiff 梯度幅值** → 3 通道极差 × MOIRE →
  //      **绝对 cap 0.2** → **只进色度路 sigZ**（亮度路 sigY = 噪声方差模型）
  //      ⇒ 强边缘处色度被整核（≤21px）平均，而亮度逐点保真。
  // [54] pc3d：0 = 关 ⇒ 整段不执行、tex_scene_ 直通 P4（与 v0.10.174
  //   逐位相同）；
  //   1 = **替换档**（照抄 PC 的链路地位）：PC 链上 ESD3D2 是去马赛克后唯一的
  //       降噪 pass（PostPipeline.java:1504）⇒ PC3D 输出直接进 film，
  //       P4/defringe/D-1 三步旁路（代码全部保留、改回 0 即恢复 —— 不预删）；
  //   2 = **叠加档（默认）**：PC3D → P4 → defringe → D-1 串联。
  //   为什么默认叠加档（2026-10-05 新 KPI 重估，docs/reports/pc3d-reval-REPORT.md）：
  //   叠加档主 KPI（剖面 core）×0.676、四片全改善、色渗红线全绿 ⇒ 默认开，
  //   **待真机 pc3dMs/画质验证后定稿**。旧"默认关"依据（∇>60 均值旧口径，
  //   docs/reports/pc3d-REPORT.md；"PC 的 3.68" 旧基准）已被推翻（docs/reports/pcfix-REPORT.md
  //   §3.3b/§7.2：3.68 含 55% 与边缘无关的全局偏置、把 PC 高估 ≈2.2×）。
  // [55] pc3d_msize：核尺寸。0 = **自动**（照抄 PC 公式）；>0 = 强制 MSIZE
  //   （奇数，夹到 7..21）。用途 = 真机拍板"核尺寸的 GPU 代价 vs 收益"（15/21
  //   的代价表见 REPORT）。默认 0。
  // 真机判据（日志字段 pc3dOn/pc3dM/pc3dSc/pc3dMs，见 finishStillChain）：
  //   pc3dOn=1 且 pc3dM∈[7,21]、pc3dSc=1..4 ⇒ 生效；
  //   pc3dOn=0 而 [54]>0.5 ⇒ **没生效**（shader 编译/链接失败或纹理分配失败）；
  //   pc3dMs 与 dnMs 同量级 ⇒ 成本正常；pc3dMs ≫ dnMs ⇒ 过强（核尺寸调小的信号）。
  //   cg* 字段（v0.10.174 的 P4 门）在 [54] 开启后**语义不变**（仍在 P4 内），
  //   健康区间照旧：cgMean ≤ 0.15、cgCov ≤ 0.05。
  float pc3d = 1.0f;           // 54 0 = 关；1 = **替换档（默认，v0.10.181 转正，
                               //    用户真机拍板 2026-10-07：紫边细、红笔不色散）**；
                               //    2 = 叠加档（2026-10-05 新 KPI 重估
                               //    docs/reports/pc3d-reval-REPORT.md）；
                               //    运行时默认值以 Kotlin 下发为准（CameraPresets.neutral()
                               //    同步改 1.0），此处仅为未收到下发时的 C++ 兜底值
  float pc3d_msize = 0.0f;     // 55 0 = 自动（PC 公式）；>0 = 强制核尺寸（奇数 7..21）

  // 56：RCD P2(G@RB)/G 位色度段回归原版（v0.10.178）。1 = 原版行为：
  // P2 的 G 估计**不做**四邻实测范围限幅（参考实现 STEP 3 只有 LIM 0..1），
  // G 位的 R/B 填充按参考实现 STEP 4.3（只用 ±1 近对 + 三项梯度反比加权、
  // 无 ±3 远对混合、无限幅）；0 = 旧行为（v0.5.28/30 实现期产物：P2 clamp 到
  // 四邻 G 范围 + G 位"近对 0.75/±3 均值 0.25 固定混合 + clamp"）。
  // 动机（2026-10-06 原版逐行对照）：与 [46] 同型的"clamp 限幅范围取自跨边界
  // 邻居"结构在 P2/G 位仍然存在 —— 亮暗交界暗侧把插值 G 钳向 R/B 位四邻的
  // 暗侧 G 范围（⇒ R−G/B−G 增大 ⇒ 品红窄峰，宽度 = 邻域半径 ≈ 3px）、亮侧
  // 对称地产生绿向；候选解释台灯逆光 B 类品红窄峰（+10@3px）。v0.10.117 只
  // 回归了对角段（chromaDiag），P2/axialFill 漏网。⚠ 默认 0 = **旧行为逐位
  // 不变**（clamp 当年是为压伪彩加的，正面/负面作用都未单独实测过）；1 = A/B
  // 通路，改默认前必须真机数据说话。prefs("rcd_g_orig")。
  float rcd_g_orig = 0.0f;     // 56

  // 57：clip 过渡带 R/B 满阱比例反推（v0.10.179，kFsHlApply 内新支）。
  // 1 = 启用：allSat（三通道全饱和）与普通 G 反推（c==1）都够不着的 clip
  //     过渡带 photosite —— 本通道（R/B）已满阱、其余通道未满 —— 按**三级
  //     参考比**由 G 与对侧通道反推**替换**（不做 max、不做软门控；与 allSat
  //     分支 v0.10.161 哲学同源：满阱原值 = WB 增益伪影、不含场景信息）。
  //     动机 = 2026-10-06 台灯样张 RAW 域定案的 clip 过渡带品红窄带
  //     （docs/research/11 号 §6）：亮窗大面积 clip 后 WB 后 clip 场 =
  //     (gainR 2.027, G 1.0, gainB 1.443) 的强品红底色，过渡带 R/B 满阱比例
  //     原样保留经 CCM 后成 4~6px 品红窄带。
  // 0 = 旧行为**逐位不变**（shader 内该支判据不满足 ⇒ 不执行任何新计算
  //     路径，默认）；1 = A/B 通路，改默认前必须真机数据说话。
  // prefs("hl_rb_recon")。
  float hl_rb_recon = 0.0f;    // 57

  // 58：RCD P3 拆双 pass（v0.10.180）。1 = 色度段拆成 3a（R/B 位对角填充
  //     = 原版 STEP 4.2，输出中间纹理 tex_rcd_a_）→ 3b（G 位轴向填充，交叉轴
  //     ±1 从 3a 读已填充的对侧色），恢复原版 STEP 4.3 的**四正邻双轴**色度
  //     估计（v0.10.181 起 3a/3b 填充值钳到 per-channel 满阱 = 原版 LIM(0,1)
  //     的域正确翻译；直译对照更正：早期"原版 43.8 vs 我们 85.6 的 2× 放大"
  //     是直译 LIM(0,1) 在 WB 域削顶的假象，全无 LIM 口径下两者 ≈ +87 无差异
  //     —— docs/research/11 号 §8/§9）。真机 A/B（2026-10-07，pc3d 替换档）：
  //     品红带峰 43.4 vs 40.6 持平；灯盘高光区无钳时 +31%（70.3 vs 53.5），
  //     v0.10.181 加钳后应回平。代价 = 多一次全分辨率 RGBA16F 往返
  //     （实测 rcdAMs+rcdBs 仅 15~61ms，可忽略）。
  // 0 = 旧行为**逐位不变**（旧 shader/调用路径零改动，默认存档）；1 = A/B 通路。
  // prefs("rcd_p3_split")。
  float rcd_p3_split = 0.0f;   // 58

  void fromDoubles(const double* v, int n) {
    // 防呆：按实际长度读取（26 = M1 核心段 0..25 的下限）。旧长度不丢核心参数，
    // 缺失的新参数保持默认 —— 契约长度不一致时"整包静默全丢"比越界更危险。
    if (!v || n < 26) return;
    exposure_ev = static_cast<float>(v[0]);
    wb_r = static_cast<float>(v[1]);
    wb_g = static_cast<float>(v[2]);
    wb_b = static_cast<float>(v[3]);
    // 防呆：全零白平衡 = 全黑输出（X300 事故），回退中性
    if (wb_r == 0.0f && wb_g == 0.0f && wb_b == 0.0f) {
      wb_r = wb_g = wb_b = 1.0f;
    }
    contrast = static_cast<float>(v[4]);
    if (contrast <= 0.0f) contrast = 1.0f;  // 0 = 全画面同一灰度，同族事故
    for (int i = 0; i < 3; ++i) {
      shoulder[i] = static_cast<float>(v[5 + i]);
      toe[i] = static_cast<float>(v[8 + i]);
    }
    for (int i = 0; i < 9; ++i) cross[i] = static_cast<float>(v[11 + i]);
    // 防呆：全零串扰矩阵 = log 域信号清零，回退单位阵
    bool cross_zero = true;
    for (int i = 0; i < 9; ++i) {
      if (cross[i] != 0.0f) {
        cross_zero = false;
        break;
      }
    }
    if (cross_zero) {
      const float ident[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
      memcpy(cross, ident, sizeof(ident));
    }
    sat_shadow = static_cast<float>(v[20]);
    sat_mid = static_cast<float>(v[21]);
    sat_high = static_cast<float>(v[22]);
    split_warm = static_cast<float>(v[23]);
    split_cool = static_cast<float>(v[24]);
    monochrome = static_cast<float>(v[25]);
    grain_amount = static_cast<float>(v[26]);
    if (n > 27) grain_size = static_cast<float>(v[27]);
    if (n > 28) tone_strength = static_cast<float>(v[28]);
    if (n > 30) zebra_thr = static_cast<float>(v[30]);
    if (n > 31) look_strength = static_cast<float>(v[31]);
    if (n > 32) look_sat = static_cast<float>(v[32]);
    if (n > 33) look_chroma = static_cast<float>(v[33]);
    if (n > 34) lut_strength = static_cast<float>(v[34]);
    if (n > 35) auto_tone = static_cast<float>(v[35]);
    if (n > 36) local_tone = static_cast<float>(v[36]);
    // v0.7.6：filmic 黑白点（用户可配，默认 −7.65/+4.56）；旧长度时保持默认值
    if (n > 37) filmic_black_ev = static_cast<float>(v[37]);
    if (n > 38) filmic_white_ev = static_cast<float>(v[38]);
    if (n > 39) sharpen = static_cast<float>(v[39]);
    if (n > 40) gamut = static_cast<float>(v[40]);
    if (n > 41) hr_wavelet = static_cast<float>(v[41]);
    if (n > 42) white_hl = static_cast<float>(v[42]);
    if (n > 43) white_floor = static_cast<float>(v[43]);
    if (n > 44) white_probe = static_cast<float>(v[44]);
    if (n > 45) hr_raw = static_cast<float>(v[45]);
    if (n > 46) rcd_cdfix = static_cast<float>(v[46]);
    if (n > 47) defringe = static_cast<float>(v[47]);
    if (n > 48) moire = static_cast<float>(v[48]);
    if (n > 49) hl_grecon = static_cast<float>(v[49]);
    if (n > 50) ca_corr = static_cast<float>(v[50]);
    // v0.10.173：强边缘色度梯度门（[51] κ / [52] 作用域 / [53] 紫边判据自适应）
    if (n > 51) chroma_gate = static_cast<float>(v[51]);
    if (n > 52) cg_scope = static_cast<float>(v[52]);
    if (n > 53) df_gate = static_cast<float>(v[53]);
    // v0.10.175：PC ESD3D2 完整移植（[54] 开关 / [55] 强制核尺寸）
    if (n > 54) pc3d = static_cast<float>(v[54]);
    if (n > 55) pc3d_msize = static_cast<float>(v[55]);
    // v0.10.178：RCD P2/G 位色度段回归原版（[56]）
    if (n > 56) rcd_g_orig = static_cast<float>(v[56]);
    // v0.10.179：clip 过渡带 R/B 满阱比例反推（[57]）
    if (n > 57) hl_rb_recon = static_cast<float>(v[57]);
    // v0.10.180：RCD P3 拆双 pass（[58]）
    if (n > 58) rcd_p3_split = static_cast<float>(v[58]);
  }
};

}  // namespace filmkit
