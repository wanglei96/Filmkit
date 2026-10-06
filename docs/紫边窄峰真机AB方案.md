# 4px 紫边窄峰逐环节真机 A/B 方案

日期：2026-10-05
性质：**只读方案文档**——本文档不修改任何代码；第 3 节列出的改动点均为"待拍板后实施"的建议，本轮一概不实施。
输入依据：审计报告 `docs/reports/pcfix-REPORT.md`（§4 剖面、§5 装配表、§7.3 建议）；FilmKit 源码（以本会话读到的为准，全部标注 文件:行号）。

---

## 0. 目的与判定口径

审计结论（REPORT §4.3 / §7.3）：我们的紫边是**紧贴边缘的窄峰**（FWHM 4px @1777 尺度、前沿斜率 1.80/px、峰在暗侧 1px 处），PC 是跨 20+px 的宽缓斜坡（无局部峰）。范围已收敛到**高频环节**（去马赛克伪彩 / 小尺度色度处理 / 锐化过冲），但尚未点名到具体 pass。本文档给出逐环节真机 A/B 的完整执行方案。

### 0.1 判定指标（唯一口径，对应 tools/purple-fringe-kpi.py）

> ✅ `tools/purple-fringe-kpi.py` 已落地，自检 PASS（PC 基准样张复现 3.68413 / 0.44607%，n=18707）。指标定义以脚本实现为准；`--self-check` 随时可复核口径未被改动。

三件套（全部在 **1777×2360 BOX 下采样**后测，与 PC 基准同尺度——REPORT §3.1 证明 ∇>60 类指标跨分辨率不可比）：

1. **剖面 core（主指标）**：边缘对齐平均色度剖面。
   - 取 ∇（前向差分，lum=(R+G+B)/3）的 3×3 山脊且 ∇>60 的点；
   - 沿中心差分梯度方向对齐（亮侧 = +t），累加 ±12px 平均 chroma（=(R+B)/2−G）；
   - 扣远端基线：**core = mean(profile, |t|≤2) − mean(profile, |t|∈[8,12])**；
   - 对 N≈4000 条边缘取均值 ⇒ 零均值噪声被平均掉（σ/√N≈0.1），读的是**结构性**紫边。
2. **零假设选择超额**：`V(∇>60 mask) − V(同数量随机像素)`（同种子、5 次重复）。免疫全局白平衡与噪声水平（REPORT §3.3b）。
3. **形状三参数**：FWHM、峰值位置 t_peak、前沿最大斜率（px⁻¹）。FWHM 失去"4px 窄峰"形态（展宽成斜坡/无局部峰）与 core 下降同为"归因成功"的必要证词。

废弃指标：`∇>60 均值 v` **不作跨轮判定**（REPORT §3.6：同地图内读数可差 25.8×），只作参考列。

### 0.2 基准值

| 量 | PC | 我们（10-05 五张，1777 尺度） |
|---|---|---|
| 剖面 core | **+1.08** | +4.62 / +2.81 / +2.61 / +2.52 / −0.25 |
| 零假设选择超额 | **+1.668** | +3.37 ~ +9.72 |
| FWHM | 无局部峰（宽缓斜坡） | 4~7px 窄峰 |

同场景连拍可重复性（REPORT §3.6）：同场景对读数比 1.05×（195630/195631）⇒ **判定阈值必须显著大于 ±5%**。

---

## 1. 环节-开关映射表

> 除非另注，本表每格都是**读到的**（源码原文 + 文件:行号）。运行时默认值 = `CameraPresets.neutral()` + `CameraController` prefs 下发层的合成结果。

先给管线实际装配（读到的：`FrameRenderer.cpp` `finishStillChain`，RCD 档）：

```
MFNR 融合(tex_fused_，mfnr 触发时)
→ HR 前置 [45]/[49]（passHighlightRaw，7851-7897）
→ P0 RAW 降噪（passRawDenoiseFused，c.dn 控制，7929-7962）
→ 去马赛克：RCD 三 pass（7963-7972）/ CPU AMaZE（7784-7801）/ bilinear 内联（8012）
   （LSC 在 RCD/AMaZE 档内做 WB 域乘；prefs "lsc" 关 → lsc=null 恒等）
→ [50] CA 校正（passCaCorrect，7994-8004，仅 RCD 档）
→ CCM（passCcm → tex_scene_，8005）
→ [54] PC3D（passPc3d：读 tex_scene_ → tex_pc3d_，8034-8045，在 c.dn 块内）
→ P4 色度降噪（passChromaDenoise：读 tex_scene_/tex_pc3d_ → tex_dn_，8046-8048）
→ [47] defringe（passDefringe：tex_dn_ → tex_df_，8053）
→ D-1 色度多尺度（passChromaMultiScale：tex_df_/tex_dn_ → tex_scene_，8055-8056）
→ passFilm（filmic/gamut/局部色调/look/HR 小波[41]）
→ passOutput（高光滚降 → 锐化[39] → OETF → 3D LUT → 颗粒，4123-4137）
→ JPEG（4:4:4 默认，jpeg_444）
```

### 1.1 候选环节逐行

| 环节 | 管线位置 | prefs 名（核实） | 设置页入口 | 槽位 | 关掉后的等价行为（读到的） |
|---|---|---|---|---|---|
| **去马赛克算法** | P0 后 | `demosaic_algo`（int 0/1/2；旧键 `demosaic_quality` 布尔迁移，`SettingsScreen.kt:246-268`） | 设置→**画质**→"RAW 去马赛克"（三档：快速/RCD 质量/Amaze） | 无槽位——独立参数进 `submitRawBuf`（`CameraController.kt:2514-2515`）→ `submitRaw(..., demosaic, ...)`（`FrameRenderer.cpp:7539`） | ⚠ **复合开关**：`const bool rcd = demosaic == 1;`（7565）、`c.amaze = demosaic == 2`（7595）、**`c.dn = rcd && noise_strength > 0.f`（7601）** ⇒ 切到 bilinear/AMaZE 时 **P0 bilateral、HR 前置、[50]、P4、[47]、D-1、[54] 整段全部不跑**（8019 `if (c.dn)`；7787-7788 注释明说 AMaZE 档"不走 P0/HR 前置、不进 P4"）。见 §2.5 解读规则 |
| **MFNR 多帧融合** | 最上游（Bayer 域，4 帧恒曝光） | `mfnr`（int 0/1/2，`SettingsScreen.kt:280-292`） | 设置→**画质**→"多帧融合"（关/自动/开） | 无槽位（采集路径分支，`CameraController.kt:1662-1684`） | 0 = 永不触发（单帧）。触发条件（`mfnrShouldTrigger`，`CameraController.kt:778-785`）：`mode≥1 && rawMode && demosaic==1`；自动档 `liveIso ≥ 600 \|\| 曝光 ≥ 25ms`（772-773）。**2 = 强制档（白天也触发）是 A/B 工具**；⚠ 仅 RCD 档可用（781） |
| **P0 RAW 降噪** | 融合后/HR 后，去马赛克前 | `noise_reduction_strength`（float 0..2，`SettingsScreen.kt:302-333`；旧键 `noise_reduction`/`noise_reduction_level` 迁移，`CameraController.kt:792-805`） | 设置→**画质**→"RAW 降噪"滑杆 | 无槽位——`denoiseParams(...)` 生成 `NoiseCoef nc`（`FrameRenderer.cpp:7580, 7723-7772`） | strength=0 ⇒ `c.dn=false` ⇒ P0 退化为纯线性化+WB（`kNoDn` 全零系数，shader 精确跳过邻域，7815、7814 注释）+ **P4/[47]/D-1/[54] 整段跳过**。⚠ 与上面同一复合问题 |
| **P4 色度降噪（pass 本体）** | CCM 后 | **无开关** | 无 | 无 | 只有 `c.dn=false` 才整段跳过；`[48]=0` 仅回到"无伪彩增强的 P4"（v0.10.164），7×7 双边平滑本体仍在。单独关需要临时诊断开关（§3） |
| **[48] 伪彩感知强度（档 A）** | P4 内部 | **无 prefs**（`grep filmParams[48]` CameraController 无命中） | **无** | `[48] moire`，默认 2.0（`CameraPresets.kt:144`） | =0 ⇒ `uMoire=0` ⇒ `scEff0 = sc`（逐位回 v0.10.164，`FrameRenderer.cpp:918-919`：`R1（只增强：uMoire = 0 ⇒ scEff 逐位 = sc）`；下发 6270） |
| **[51] 强边缘色度门 κ** | P4 + D-1 最细两级 | `chroma_gate`（**bool**，true→`CHROMA_GATE=2.0`→[51]，`CameraController.kt:1418-1424`、常量 1095） | 设置→**画质**→"强边缘色度门" | `[51] chroma_gate`，默认 2.0（`CameraPresets.kt:172`） | false→[51]=0 ⇒ P4 `cgGate=0 ⇒ cgRelax=0 ⇒ scEff 逐位=scEff0`、pcGate=0（`FrameRenderer.cpp:929-935`）；D-1 `if (uCgK > 0.0 && uStep <= 2)` 整段跳过（1328）⇒ **P4/D-1 逐位回 v0.10.172**（`CameraController.kt:1412` 注释同口径） |
| **[52] 权重形式（w2 vs 字面版）** | P4 内部 | **无 prefs** | **无** | `[52] cg_scope`，默认 1.0（`CameraPresets.kt:173`） | 0 = 字面版：`pcGate = (uCgScope > 0.5) ? cgGate : 0.0`（935）——不切 w2 分支，只放大 wc 阈值（"实测近乎无效，留作 A/B 反证"） |
| **[53] 紫边判据梯度自适应** | [47] defringe 的 apply 判据 | **无 prefs** | **无** | `[53] df_gate`，**默认 0.0 = 关**（`CameraPresets.kt:174`） | 0 = `thr` 逐位不变（`FrameRenderer.cpp:1108` `if (uDfGate > 0.0)`；下发 7069）。现状已关，无需拍"关"，只可拍"开" |
| **[47] defringe（边缘紫边抑制）** | P4 后、D-1 前（Lab 域） | `defringe`（bool，`CameraController.kt:1360-1366`） | 设置→**画质**→"边缘紫边抑制" | `[47] defringe`，默认 1.0（`CameraPresets.kt:139`） | false→[47]=0 ⇒ `df_ok = false`，passDefringe 不调用（8053）⇒ D-1 输入回落 `tex_dn_`（8056）⇒ 整层旁路 |
| **D-1 色度多尺度（pass 本体）** | defringe 后、passFilm 前 | **无开关** | 无 | 无 | 只有 `c.dn=false` 才跳过（8019）；运行失败回落 P4 输出（6341 注释"返回 false = 未执行（调用方回落 tex_dn_）"）。单独关需要临时诊断开关（§3） |
| **[54] PC3D（PC ESD3D2 完整移植）** | CCM 后、P4 前（读 tex_scene_） | `pc3d`（int 0/1/2，`SettingsScreen.kt:423-441`；下发 `CameraController.kt:1438-1443`） | 设置→**画质**→"色度降噪·多尺度"（关/替换档/叠加档，点按循环） | `[54] pc3d`，**默认 0 = 关**（`CameraPresets.kt:189`） | 0 ⇒ 整段不执行、`tex_scene_` 直通 P4（8034 `if (p.pc3d > 0.5f)` 不进；8023 注释"与改动前逐位相同"）。1 = 替换档：`pc3d_replace = (p.pc3d < 1.5f)`（8043）⇒ P4/[47]/D-1 旁路（8046 `if (!pc3d_replace)`）、passFilm 读 `tex_pc3d_`（8075）。2 = 叠加档：P4 改读 `tex_pc3d_`（8042、6257 `src_tex ? src_tex : tex_scene_`）后正常串联 |
| **[55] pc3d_msize（核尺寸）** | PC3D 内部 | `pc3d_msize`（int 0/7/11/15/21，`SettingsScreen.kt:442-458`；下发 1444-1450，非法值归 0） | 设置→**画质**→"色度降噪核尺寸" | `[55]`，默认 0 = 自动 | 0 = PC 公式自动（本机噪声模型 ⇒ 11，`FrameRenderer.cpp:6858-6867`）；>0 强制奇数 7..21（6870-6874） |
| **[50] CA 校正** | D3（RCD 后/CCM 前），**仅 RCD 档** | `ca_corr`（bool，`CameraController.kt:1402-1408`） | 设置→**画质**→"镜头色差校正" | `[50] ca_corr`，默认 1.0（`CameraPresets.kt:158`） | false→[50]=0 ⇒ passCaCorrect 不调用（7994 `if (p.ca_corr > 0.5f)`）⇒ `ccm_src` 仍是 `tex_work_`（逐位同改动前，7983 注释）。**本批样片实测不动作**：角部位移 0.13/0.41px < 门限 0.5px 且内外半区不相容 ⇒ caApplied=0（`CameraController.kt:1396-1400`；门限常量 `kCaMinShift=0.5`，`FrameRenderer.cpp:7086`）——**免拍轮** |
| **轻度锐化（luma USM）** | passOutput 内：高光滚降后、OETF 前线性显示域 | `sharpen`（bool，true→`SHARPEN=0.5`，`CameraController.kt:1280-1286`、常量 1065） | 设置→**画质**→"轻度锐化" | `[39] sharpen`，运行时默认 0.5（`CameraPresets.kt:102`；`FilmParams.h:85` 缺参回退 0.0 仅防旧包） | false→[39]=0 ⇒ shader `if (uSharpen > 0.0) sharpenLuma(c, uv)` 整段跳过（`FrameRenderer.cpp:4128`）⇒ RGB 逐位直通 |
| **[45] hr_raw（RAW 域高光重建）** | HR 前置：P0 **前**（仅 RCD 档） | `hr_raw`（bool，`CameraController.kt:1329-1335`） | 设置→**影调与色彩**→"高光重建·RAW 域" | `[45]`，默认 1.0（`CameraPresets.kt:129`） | false→[45]=0 ⇒ `hr_on=false`（7851）⇒ HR 不跑，且单帧的"前置展开一趟"也不跑（7862 `else if (hr_on)`；7856 注释"关 HR = 原链路逐位不变"） |
| **[49] hl_grecon（G 通道重建）** | HR apply 支内部 | `hl_grecon`（bool，`CameraController.kt:1381-1387`） | 设置→**影调与色彩**→"高光重建·G 通道重建" | `[49]`，默认 1.0（`CameraPresets.kt:151`） | false→[49]=0 ⇒ shader `if (uGRecon > 0.5)` 两支自创机制整段跳过（7881 下发；3448）⇒ apply 只剩 darktable opposed 外推 = PC OpposedGL 口径。离线已证该两支贡献"过曝边缘品红 B/G 偏差的 71%"（`FilmParams.h:166-168`） |
| **[46] rcd_cdfix** | RCD 色差段（passRcdChroma 内） | `rcd_cdfix`（bool，`CameraController.kt:1345-1351`） | 设置→**实验与诊断**→"去马赛克·色差原版" | `[46]`，默认 1.0（`CameraPresets.kt:134`） | false→0 = 旧行为（梯度两项 + clamp 到 4 对角色差）——是"加 clamp"的方向，与"关掉环节"相反；本方案不动它 |
| **LSC 镜头阴影校正** | RCD/AMaZE 档内（WB 域乘增益网格） | `lsc`（bool，`SettingsScreen.kt:336-349`） | 设置→**画质**→"镜头阴影校正" | 无槽位（`CameraController.kt:3076-3080`：prefs 关 → `lsc = null` ⇒ 引擎恒等增益） | 不是紫边候选（低频渐晕），列出仅为完整性；**不设轮** |
| **[41] hr_wavelet（filmic 小波高光重建）** | passFilm 内（filmic 黑白点后） | `hr_wavelet`（bool，**默认 false**，`SettingsScreen.kt:598-611`、`CameraController.kt:1313-1319`） | 设置→**影调与色彩**→"高光重建·小波" | `[41]`，默认 0.0（`CameraPresets.kt:116`） | 默认已关（PC 无此模块）。**不设轮**（除非 [45]/[49] 轮出现反常时复核） |
| **MFNR 频域合并/对齐精度/P0 亚像素**（`mfnr_dft`/`align_l1`/`p0_sub`） | 融合与 P0 内部 | 三者均有 prefs + **实验与诊断**抽屉入口（`SettingsScreen.kt:720-764`） | 同左 | 无槽位 | 默认：dft 关 / align_l1 开 / p0_sub 开。非紫边候选，**不设轮** |
| **JPEG 4:4:4** | 编码 | `jpeg_444`（bool 默认 true，`SettingsScreen.kt:135-142`） | 设置→"JPEG 色度采样"（画质抽屉附近） | 无 | 已被受控 A/B 排除（REPORT §4.2：−0.8%~−9.3% 且形状不变）——**免拍** |

### 1.2 没有开关、需加临时诊断开关才能 A/B 的环节

**读到的结论**（这是本方案最关键的清单）：

1. **D-1 色度多尺度本体**（`passChromaMultiScale`）：无任何独立开关。它挂在 `if (c.dn)`（8019）与 defringe 之后（8055-8056），唯一的"关"路径是 `c.dn=false`（会连带 P0/[47]/P4 全跳，不是单变量）。→ 临时开关见 §3-A。
2. **P4 色度降噪本体**（`passChromaDenoise`）：无独立开关。`[48]`/`[51]` 只是它内部的两个**增强旋钮**（都关 = 回到 v0.10.164 的双边平滑），不是"关 pass"。输出写 `tex_dn_`，下游 [47]/D-1 硬编码读 `tex_dn_`（7022、6360 `src_tex ? src_tex : tex_dn_`）⇒ 绕过需要改线。→ 见 §3-B。
3. **[48] moire（伪彩感知强度）**：有槽位（[48]）但**无 prefs 键、无设置页入口**（CameraController 无 `filmParams[48]` 下发代码，grep 核实）——运行时恒为 `CameraPresets.neutral()` 的 2.0。→ 见 §3-C。
4. **[52] cg_scope、[53] df_gate**：同样**有槽位无 prefs/UI**（恒 1.0 / 0.0）。[53] 默认已是 0（无需关，只能拍"开"）；[52]=0（字面版）留作反证轮，优先级低。→ 见 §3-C。
5. **去马赛克算法的"单变量化"**：`demosaic_algo` 本身有设置页开关，但它**不是**单变量（§1.1 第一行的复合问题；AMaZE 档缺 P0/HR 是已知未完成项，`FrameRenderer.cpp:7787-7788` 注释）。不打算补齐 AMaZE 档的 P0/HR（工程量大），用 §2.5 的解读规则消化复合性。

---

## 2. A/B 矩阵设计

### 2.0 总原则（项目方法论铁律）

- **一次只动一个开关**；每轮与基线**同刻连拍**（同场景同曝光），**冷机取中位**（连拍间隔让机身温度/增益策略一致）。
- 每状态拍 **5 张**（同场景读数噪声 ~5%，REPORT §3.6；5 张中位对单张离群有免疫）。
- 场景：**暗光逆光高对比边缘**——对齐 REPORT 用的 2026-10-05 逆光基准（暗室/夜间窗框、灯管、晾衣杆类跨亮暗直线边缘 ≥3 根，边缘两侧有中性面）。三脚架或依托，同机位同构图。
- 曝光：同刻连拍自动曝光即可；记录每张的亮度中位（成片对曝光敏感，REPORT §3.4——轮内亮度中位漂移 >10% 则该轮作废重拍）。
- 每张都导出 **JSONL 日志**（见 §4.4），先用日志字段核对开关真的生效（§2.3），再看剖面。

### 2.1 轮次表

| 轮 | 开关精确设置 | 设置页路径（或 prefs） | 拍摄 | 日志生效判据（raw.render / settings 事件） |
|---|---|---|---|---|
| **R0** | 全默认基线 | — | 5 张 | dn=1.00、df=1、cgK=2、pc3dOn=0、hlGRecon=1、moGain=2.0 |
| **R1** | [54]=**2** 叠加档（其余默认） | 画质→"色度降噪·多尺度"点 2 次（关→替换档→**叠加档**） | 5 张 | `pc3dOn=1`、`pc3dM∈[7,21]`、`pc3dSc=1..4`、`pc3dMs` 与 dnMs 同量级；pc3dOn=0 而 [54]>0.5 = **没生效**（shader 编译/纹理失败） |
| **R2** | [54]=**1** 替换档 | 同上点 1 次 | 5 张 | 同上 + `df=0`（替换档 P4/[47]/D-1 整段旁路，8046，`df_ok` 保持初始 false，8016）。⚠ `cms` 字段在旁路时**不被清零**（它在 passChromaMultiScale 内部置位，6347）——可能残留上一帧值，不作 R2 判据 |
| **R3** | [47] **关** | 画质→"边缘紫边抑制"开关关 | 5 张 | `df=0`、dfCavg 缺省 |
| **R4** | [49] **关**（高光重建·G 通道重建） | 影调与色彩→"高光重建·G 通道重建"关 | 5 张 | `hlGRecon=0`（hlOk 不变） |
| **R5** | [51] **关**（强边缘色度门） | 画质→"强边缘色度门"关 | 5 张 | `cgK=0`、`cgMean=0`、`cgCov=0`（8125-8127：cgK≤0 时这三个字段恒 0） |
| **R6** | [39] **关**（轻度锐化） | 画质→"轻度锐化"关 | 5 张 | 无直接引擎字段（passOutput 不回读）——用目视锐度 + 设置事件 `settings.sharpen {"on":false}` 佐证 |
| **R7** | [45] **关**（RAW 域高光重建） | 影调与色彩→"高光重建·RAW 域"关 | 5 张 | `hlPre=0`、`hlOk=0`、hlCnt=0；且 RCD 分支日志不再有前置展开耗时 |
| **R8** | [48]=**0**（伪彩感知强度关） | **无 UI**——需 §3-C 的临时通路（改 CameraPresets[48]=0 出诊断包，或加 prefs） | 5 张 | `moGain=0`（moMean/moP95 应≈0——统计 pass 在 moire≤0 且 cgK=0 时跳过，6281） |
| **R9** | 去马赛克 = **Amaze**（复合变量） | 画质→"RAW 去马赛克"→Amaze | 3 张（CPU 慢 +0.3~1s/张） | `settings.demosaic_quality {"algo":2}`；raw.render **不再出现** `dn/kP0/df/moGain` 等字段（`if (c.dn)`/`if (c.rcd)` 块整体缺省，8184/8192）——字段缺失本身就是证据 |
| **R10a** | `mfnr`=**0**（关多帧） | 画质→"多帧融合"→关 | 5 张 | `mfnr.trigger {"trigger":false}` |
| **R10b** | `mfnr`=**2**（强制多帧，白天也触发） | 同上→"开" | 5 张 | `mfnr.trigger {"trigger":true}`；注意需 RCD 档 |

可选补充轮（视前面结果决定，见 §5 判停）：

| 轮 | 设置 | 用途 |
|---|---|---|
| R11 | `noise_reduction_strength`=**0**（"RAW 降噪"滑杆拉到 0） | **P0..[54] 全段消融**（复合变量）：窄峰若消失 ⇒ 峰产生于 P0/P4/[47]/D-1/[54] 中某一层；若仍在 ⇒ 指向去马赛克本体/融合/锐化/色调链 |
| R12 | [52]=0（字面版，需 §3-C 通路） | [51] 轮的反证（门效果是否来自 w2 权重形式） |
| R13 | [53]=1.0（df_gate 开，需 §3-C 通路） | 给 [47] 的判据加梯度自适应（REPORT §7.2 指出全图均值判据会漏贴边紫边） |

### 2.2 推荐轮次顺序与理由

**R0 → R1 → R2 → R3 → R4 → R5 → R6 → R7 → R8 → R9 → R10**，即：

1. **先拍 [54] 的两档（R1/R2）**：REPORT §7.3 建议第 1 条——这是唯一"照抄 PC 机制、代码已就绪、只差一个开关"的路（[54] 默认关的依据"离线达不到 PC 3.68"已被审计推翻：3.68 含 2.02 全局偏置，虚高 2.2 倍）。R2（替换档）同时旁路 P4/[47]/D-1，单轮信息量最大：**若 R2 干净而 R1 不干净 ⇒ 窄峰部分产生在我们自己的 P4/[47]/D-1 段；若 R2/R1 都干净 ⇒ PC 式大核色度平滑确实治本；若都不动 ⇒ [54] 排除**。
2. **[47]（R3）**：它是"我们有而 PC 全仓库没有"的层（REPORT §5.3）， Lab 域 13×13 邻居替换**主动改写强边缘附近的 a/b 色度**——若替换本身引入新色度结构，关掉应立竿见影。
3. **[49]（R4）**：离线逐位仿真已证 G 重建两支贡献"过曝边缘品红 B/G 偏差的 71%"（`FilmParams.h:166-168`）——逆光窗场景必有 RAW 饱和 ⇒ 该路径必然参与，且是"我们对 PC 的主动偏离"。
4. **[51]（R5）→ [39]（R6）→ [45]（R7）**：门与锐化都直接作用于 1~3px 尺度；[39] 改动成本最低但机理上 luma-only（保色度坐标，`FrameRenderer.cpp:4078-4079` 注释），预期影响剖面 mask 的选取而非 chroma 本身，故排后。[45] 整层关闭对画质副作用最大，排 [49] 之后。
5. **[48]（R8）放后面**：需要临时通路（改代码出包），成本高于纯设置页轮。
6. **R9 算法档放最后**：复合变量，解读有限定（§2.5），且 AMaZE 慢。
7. **R10 MFNR 压轴**：R0 基线在暗光必然已触发自动 MFNR（`liveIso≥600 || 曝光≥25ms`，暗光逆光场景大概率命中——此为**推算**，以日志 `mfnr.trigger` 为准），R10a 回答"多帧融合本身贡献多少"。

最小轮次（时间只够拍 3 轮时）：**R1（[54]=2）、R3（[47] 关）、R6（[39] 关）**——分别覆盖"PC 有我们无"、"我们有 PC 无"、"全局高频"三个假设方向。

### 2.3 每轮执行模板

1. 改开关（设置页）→ 回取景器。
2. 同机位连拍 5 张（间隔 ~2s）。
3. 导出/查看日志，核对 §2.1 生效判据字段；不生效则修了再拍（**判定前必须先证明开关生效**）。
4. 样张拉回 Mac，跑 `tools/purple-fringe-kpi.py`（备用的 /tmp/pcfix 脚本已清理，方法存档见 `docs/reports/pcfix-REPORT.md`）→ 记录 core / 选择超额 / FWHM / t_peak / 斜率 / 亮度中位。
5. 恢复开关 → 下一轮。

### 2.4 判定数据的归并

每轮产出：`core_中位(5张) ± 极差`、`选择超额中位`、`FWHM 中位`、`亮度中位范围`。与 R0 比：
- 相对变化 Δcore% = (core_RX − core_R0) / core_R0。
- 形状变化：FWHM 与"有无局部峰"逐张记录。

### 2.5 R9（复合变量）的解读规则

R9 改的是 `demosaic_algo`，实际同时改变：去马赛克本体（RCD→AMaZE）+ P0 有无 + HR 前置有无 + [50]/P4/[47]/D-1/[54] 全段有无（§1.1）。因此：
- **R9 窄峰仍在（core 不降）**⇒ P0/P4/[47]/D-1/[54]/RCD 本体全部排除，指向 **AMaZE 本体、融合（R9 无 MFNR）、锐化、色调链**；下一步用 R11/R10 细分。
- **R9 窄峰消失**⇒ 候选缩到"P0 / RCD 本体 / P4 段"之一，无法单凭 R9 定名——用 R1-R8 的单变量结果交叉裁决；若 R1-R8 全部无变化，则加做 R11（全段消融）+ §3 的 D-1/P4 临时开关。
- ⚠ R9 不可与 R1-R8 的单变量结论混同解读；它只用于**排除**，不用于**点名**。

---

## 3. 需新增的临时诊断开关（列出，不实施）

| 编号 | 目标 | 最小改动点（文件 + 位置 + 改法一句话） | 工作量估计 |
|---|---|---|---|
| **A** | **D-1 本体旁路**（保持 P0/P4/[47] 正常） | `core/engine/src/render/FrameRenderer.cpp:8055` —— `mst_ok = passChromaMultiScale(...)` 外包一层临时开关条件（false 时不调用，`mst_ok` 保持 false，既有回落分支 8076-8077 自动让 passFilm 读 `tex_dn_`，**回落路径是现成的、已验证的**）。开关通路建议按项目惯例加 FilmParams 槽位：`FilmParams.h:14` `kCount = 56 → 57` + 新字段 + `fromDoubles`（335-337 附近）+ `CameraPresets.kt:24` `DoubleArray(56)→(57)` + 默认值 + `CameraController.kt` 仿 `applyPc3dPref`（1438-1451）加下发 ~12 行 + `SettingsScreen.kt` Lab 抽屉加一行。**槽位契约 kCount 与 Kotlin 数组必须同步，否则越界崩**（`FilmParams.h:10-13` 注释明示） | 0.5 天（含 tools/check-diag-fields.py 适配——`cms` 字段现有 EXPECT 条目会要求 passChromaMultiScale 调用先于日志，开关化后需确认 cms=0 语义；+ tools/validate-shaders.sh 不涉及） |
| **B** | **P4 本体旁路**（保持 P0 正常、[47]/D-1 改读 tex_scene_） | 改两处数据流：① `FrameRenderer.cpp:8053` defringe 的输入——`passDefringe` 内 `glBindTexture(GL_TEXTURE_2D, tex_dn_)`（7022、7060 两处）改由参数传入（P4 被旁路时传 `tex_scene_`）；② `FrameRenderer.cpp:8056` D-1 的 `src_tex` 参数传 `tex_scene_`（形参现成，6344-6346 `GLuint src_tex`）。开关通路同 A（可共用同一个临时槽位的不同值，或再 +1 槽） | 1 天（改 pass 签名 + 数据流验证；validate-shaders.sh 必跑——shader 不动但建议全量回归） |
| **C** | **[48]/[52]/[53] 的 prefs 通路**（不加 UI 也能 adb 驱动） | `app/src/main/java/com/filmkit/app/camera/CameraController.kt` 仿 `applyChromaGatePref`（1418-1424）各加 ~10 行：`applyMoirePref`（prefs `moire` float → `filmParams[48]`）、`applyCgScopePref`（prefs `cg_scope` → [52]）、`applyDfGatePref`（prefs `df_gate` → [53]），并挂进 `applyTonePref()` 的调用列表（1197-1213）。prefs 写入端：要么 SettingsScreen 加行（推荐，Lab 抽屉），要么临时用 debug 包 `adb shell` + run-as 改 xml（仅 debuggable 可用，release 不行——推算） | 每槽 0.5~1 小时；三槽合计半天。出包前跑 tools/check-diag-fields.py（moGain/cgScope/dfG 字段语义不新增，预计无需改） |
| **D** | （不建议本轮做）AMaZE 档补 P0/HR/[50]/P4 段 | 工程大（AMaZE 档 CPU 链与 GPU 链拼装，`FrameRenderer.cpp:7784-7801` 注释明说"未完成项，见 REPORT.md"） | 数天；用 §2.5 解读规则代替 |

---

## 4. 执行手册

### 4.1 装机

```bash
# 出包（若本轮只改 Kotlin/无改动，用现有包亦可；改了引擎必须先过两道闸）
python3 tools/check-diag-fields.py          # 新增/改动日志字段时必跑（§3-A/C 场景）
tools/validate-shaders.sh                   # 改动任何 GLSL 字符串时必跑（本轮预计不涉及）
./gradlew assembleDebug                     # 或 assembleRelease；产物默认
#   app/build/outputs/apk/debug/app-debug.apk（文件名按实际构建输出为准——推算，gradle 标准路径）

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首轮装机后一次性核对设置基线（全部恢复默认）：去马赛克=RCD 质量、多帧融合=自动、RAW 降噪=1.00、LSC/锐化/边缘紫边抑制/镜头色差校正/强边缘色度门=开、色度降噪·多尺度=关、核尺寸=自动、高光重建·RAW 域/G 通道重建=开、小波=关、DNG 按需。

### 4.2 拍摄场地与机位

- 复刻 2026-10-05 逆光基准场景：暗室/夜间，画面含 ≥3 根高对比直线边缘（窗框、灯管、杆件），亮侧接近/达到饱和、暗侧中性。
- 三脚架或稳定依托；同机位同构图拍完全部轮次（可在取景器里用胶带/便签定位）。
- 每轮 5 张 + 轮间不动曝光设置（自动档同刻连拍；若跨刻补拍，先重拍 R0 三张校验可重复性再继续）。

### 4.3 样张导出路径

App 落盘两分支（读到的 `CameraController.kt:3321-3344`：API≥29 走 MediaStore `Pictures/FilmKit`，失败回退 `getExternalFilesDir(Pictures)/FilmKit`）：

```bash
# 方式一：adb（对应 MediaStore 分支的公共目录）
adb pull /storage/emulated/0/Pictures/FilmKit/ ./ab-samples/
# 方式二：app 私有目录分支
adb pull "/storage/emulated/0/Android/data/com.filmkit.app/files/Pictures/FilmKit/" ./ab-samples/
# 方式三：vivo 文件管理系统直拷（Mac 挂载）
#   ~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/FilmKit/
#   （回退分支在 .../Android/data/com.filmkit.app/files/Pictures/FilmKit/）
```

文件名规则（读到的 `CameraController.kt:3310`）：`FILMKIT_yyyyMMdd-HHmmss.jpg`，同秒自动加后缀区分。**记录表人工补齐开关状态**——文件名里没有开关信息（这是本轮流程的已知缺口，靠 §4.4 的日志弥补）。

### 4.4 日志采集（判定前必做）

- App 内：设置/日志页（`LogScreen.kt:83-92`）→"导出 JSONL"→ 分享/保存后一并拉回。
- 或 `adb logcat -d > roundX.log`（引擎日志走 nativeAppendLog）。
- 每张成片对应一条 `raw.render`（含 `dn/kP0/kP4/p4A/p4B/dnMs/df/dfCavg/moGain/moMean/cgK/cgMean/cgCov/dfG/pc3dOn/pc3dM/pc3dSc/pc3dMs/hlOk/hlGRecon/hlPre/caApplied/tone/ev/wb/blackEv/whiteEv` 等，读到的 `FrameRenderer.cpp:8107-8348`）+ 一条 `mfnr.trigger`（`CameraController.kt:1672-1678`）。
- 归档命名：`ab-samples/R1_pc3d2/FILMKIT_xxx.jpg` + 同目录 `round1.jsonl`。

### 4.5 记录表格式

```
| 轮次 | 文件 | 开关状态（本轮变量=值，其余默认） | 时刻 | 亮度中位 | core | 选择超额 | FWHM | t_peak | 斜率 | 备注 |
| R0-1 | FILMKIT_20261005-XXXXXX | baseline | 20:31 | 32.1 | 4.61 | 9.71 | 4 | -1 | 1.79 |  |
```

（core/超额/FWHM 由 tools/purple-fringe-kpi.py 输出；脚本已落地，自检 PASS（PC 复现 3.68413/0.44607%）。）

### 4.6 判定流程

1. 先核对日志生效字段（§2.1 列）——**字段不达标的轮次一律作废**。
2. 剔除亮度中位偏离轮中位 >10% 的张数。
3. 每轮取 core 中位与 R0 中位比较 → 套 §5 判停表。
4. core 变化显著的轮，再看形状（FWHM/斜率/峰位）确认是"峰被抹平/源头消失"还是"整体色度漂移"（后者看选择超额是否同降）。

---

## 5. 预期与判停

基准：PC 剖面 core = **+1.08**、选择超额 = **+1.668**（REPORT §4.1/§3.3b，1777 尺度）。我们 R0 预期 core ≈ 2.5~4.6（10-05 五张的分布）。

**归因成功判据（读到多少算归因成功）**：关闭/开启环节 X 后，
- **主因**：core 中位降幅 **≥50%** 且绝对值 **≤1.5**（≈ PC 1.08 + 40% 裕量）**且** FWHM 失去窄峰形态（展宽/无局部峰）⇒ X 是窄峰主因；下一轮做"X 关闭态的新默认"复核 + 全面画质回归。
- **贡献者**：降幅 **20%~50%** ⇒ X 是成因之一；与下一嫌疑开关做**组合轮**（两个同时关）验证可加性。
- **排除**：|Δcore| **≤10%**（低于同场景可重复性 1.05× 的两倍噪声带）⇒ X 排除，划掉。
- 中间地带（10%~20%）：重拍一轮（各 5 张）再判；仍模糊按"弱贡献"记录，不单独定论。

### 5.1 每轮可能的读数组合 → 结论

| 轮 | 读数组合 | 结论 |
|---|---|---|
| R1/R2（[54]） | R2 core 降到 ≤1.5 且形状变斜坡 | **PC ESD3D2 结构就是缺失的那环**——按 [54]=1 或 2 定新默认（结合 R1/R2 对比决定替换还是叠加），转画质回归 |
| | R1、R2 都降但只到 2~3 | 大核色度平滑有效但不足 ⇒ [54] 开 + 继续找残余来源（跑 R3-R7） |
| | R1/R2 无变化（±10%） | [54] 排除；注意核对 pc3dOn 是否=1（没生效的轮不算数） |
| R3（[47] 关） | core 大降 + 峰消失 | **defringe 本身在制造/加剧窄峰**（替换窗引入新色度结构）⇒ 重设计判据（R13 的 df_gate 方向）或降参，而不是默认关 |
| | core 不降反升 | defringe 在压窄峰（符合设计预期），保留；残余来自别处 |
| R4（[49] 关） | core 降 ≥50% | G 重建两支是主因（与离线 71% 品红贡献一致）⇒ 评估默认关（回到 PC OpposedGL 口径），盯台灯类场景退化风险（`FilmParams.h:171-172`） |
| R5（[51] 关） | core 大降 | 门 v2 的 w2 跨亮度取色在制造窄峰 ⇒ 回 v0.10.172 语义或收紧 kCgG0/kCgRel |
| R6（[39] 关） | core 大降且 FWHM 变化 | 锐化过冲参与（虽然 luma-only，可能通过 mask 选取放大 core）⇒ 降 [39] 或改在 OETF 后（PC 口径）重标定 |
| R7（[45] 关） | core 大降 | RAW 域 HR 外推引入 ⇒ 与 R4 交叉：R4 也大降 ⇒ 责任在 [49] 两支；R4 不降而 R7 降 ⇒ opposed 外推本体/色度校正步 |
| R8（[48]=0） | core 大降 | 伪彩增强的 σc 放大在强边缘处反向制造色度平滑差 ⇒ 重新标定 uMoireK/封顶 |
| R9（AMaZE） | 窄峰消失 | 按 §2.5：候选=P0/RCD 本体/P4 段，与 R1-R8 交叉；全无变化 ⇒ 上游（融合/光学/对齐）或色调链——下一阶段立项 |
| R10a/b | mfnr=0 时 core 显著降 | 多帧融合对齐残差贡献窄峰 ⇒ 查 mismatch 门与对齐（align_l1/p0_sub 维度另立轮） |
| **全部轮无变化** | 所有单变量 |±10%| | **指向去马赛克本体（RCD）或融合域上游**——下一轮：R11（strength=0 全段消融）+ R9 交叉；R11 仍无变化 ⇒ 换算法档深查（AMaZE/bilinear 档各自的剖面），必要时 §3-B 的 P4 旁路开关 |

### 5.2 判停

- 任一轮触发"主因"判据 ⇒ 停止矩阵，转入该环节的修复设计 + 全面 A/B 回归（降噪/色准/锐度三口径）。
- R1-R8 全部"排除"而 R9/R10 有信号 ⇒ 收窄到算法/融合，追加 §3-A/B 临时开关轮。
- 全部排除 ⇒ 窄峰产生于未列环节（融合对齐/光学/色调链），凭本轮数据另立专项——此时必须把"剖面 core + 选择超额"固化为标准 KPI（REPORT §7.3 建议第 4 条），废弃 ∇>60 均值跨图对比。

---

## 附：本方案引用的代码事实清单（全部读到的）

| 事实 | 出处 |
|---|---|
| `c.dn = rcd && noise_strength > 0.f`；`rcd = demosaic==1`；`c.amaze = demosaic==2` | core/engine/src/render/FrameRenderer.cpp:7565, 7595, 7601 |
| P4/[47]/D-1/[54] 全部在 `if (c.dn)` 块内 | FrameRenderer.cpp:8019-8062 |
| [54]=0 整段不执行；=1 替换档旁路 P4/[47]/D-1；=2 叠加档 | FrameRenderer.cpp:8023-8046, 8075 |
| [47]=0 时 passDefringe 不调用、D-1 回落 tex_dn_ | FrameRenderer.cpp:8053-8056 |
| P4 [48]=0 / [51]=0 的逐位回退路径 | FrameRenderer.cpp:918-919, 929-935; D-1: 1328 |
| [50] 双判据与不动作实测 | FrameRenderer.cpp:7086-7088, 7994-8004; CameraController.kt:1396-1400 |
| [39]=0 跳过 sharpenLuma | FrameRenderer.cpp:4128 |
| [45]=0 时 HR 与前置展开均不跑 | FrameRenderer.cpp:7851, 7856, 7862 |
| [49]=0 跳过两支自创机制；离线 71% 品红贡献 | FrameRenderer.cpp:7881, 3448; FilmParams.h:166-172 |
| [48]/[52]/[53] 无 prefs/UI（[53] 默认 0） | CameraPresets.kt:172-174；grep `filmParams[48]` CameraController.kt 无命中 |
| demosaic/mfnr/noise_reduction/lsc/defringe/ca_corr/chroma_gate/pc3d/pc3d_msize/hr_raw/hr_wavelet/hl_grecon/rcd_cdfix/sharpen 的 prefs 键与 UI 归属 | SettingsScreen.kt:246-298, 302-333, 336-349, 350-363, 366-379, 383-396, 403-416, 423-458, 598-646, 784-797; CameraController.kt:1280-1451 |
| MFNR 三档与触发条件（ISO 600 / 25ms / 仅 RCD） | CameraController.kt:770-785, 1662-1684 |
| 成片落盘路径与文件名 | CameraController.kt:3310, 3321-3344 |
| raw.render 日志字段（cg/pc3d/df/mo/hl/ca 族） | FrameRenderer.cpp:8107-8348 |
| tools/ 现状：有 check-diag-fields.py / validate-shaders.sh；**无 purple-fringe-kpi.py**（并行开发中） | 本会话 `ls tools/` 核实 |

**推算项（有依据非直接观测）**：R0 基线在暗光触发自动 MFNR（按亮度中位与触发阈值推算，以日志为准）；APK 产物路径（gradle 默认）；R8 无 UI 通路下 run-as 改 prefs 的可行性（仅 debuggable）。
