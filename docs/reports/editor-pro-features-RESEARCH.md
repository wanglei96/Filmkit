# 编辑器「功能矩阵 + UI 规格」调研报告

> 子代理交付 · 2026-10-09 · 供架构师排工
> 任务来源：用户验收 v0.11.3 编辑器后反馈三条 —— ①「调节功能不全」（点名裁剪/色温/色调/高光/阴影）②「面板太 low，不够专业」③「不知道怎么做就去搜开源项目借鉴，比如 PC、Filmic、dazz」

**本报告只读仓库、未修改任何现有文件**（唯一写入 = 本文件）。所有代码引用带 `文件:行号`，所有联网结论带 URL。

---

## 0. 摘要（架构师先看这 10 行）

| # | 结论 | 依据 |
|---|---|---|
| 1 | **「面板太 low」的根因不是配色，是控件形态**：当前 `StrengthRow` 是「标签 + 细滑杆 + 百分比」的**设置页表单**形态，而专业 App 的滑杆是**满宽、无行内标签、带中点刻度、读数是物理量、双击重置** | §3.1 逐条对比 |
| 2 | **最便宜的一张卡 = 颗粒 UI**：`kFsStyle` **已经**读 `uGrainAmount/uGrainSize`，`passStyle()` 已经在传 `p.grain_amount/p.grain_size` —— 引擎已实现，纯 UI 接线 | `FrameRenderer.cpp:4621-4622, 4853-4857, 12093-12094` |
| 3 | **用户点名的五项里，只有「裁剪」是几何变换**，另外四项（色温/色调/高光/阴影）都能并入**已有的那一次线性域成对往返**，不新增 pass | §1.3 / §2.2 |
| 4 | **色温/色调不能简单映射到 `[1..3]`**：2 自由度 → 3 增益是**非双射**，反解会跳读数（ADR-7 已记该陷阱）。推荐**新增 `[88][89]` 两槽**存 mired/tint，引擎内算增益 | §1.3.2 |
| 5 | **高光/阴影的落点是显示域 luma 分区**，与 `[4] contrast`/`[78] clarity` 同域同段，可共用门控 | §1.3.4 |
| 6 | **裁剪必须在引擎输入侧做**（不是导出时裁位图），否则暗角/局部调整的归一化坐标会与画面错位 | §1.3.1 |
| 7 | **PC 的可抄之处有三样**：调色板配色 token（`cam_surface*`）、`HorizontalPicker` 分组切换器、`KnobView` 双层弧形刻度盘（含 8dp 刻度线 / 11dp 间距 / 1° 步进 / 选中色） | §3.4 |
| 8 | **PC 的编辑器本身很弱**（它是相机 App，不是修图 App）—— 功能矩阵对标要用 Lightroom/Snapseed/Filmix，UI 质感对标用 PC/Dazz | §1.1 |
| 9 | 面板结构建议：**底部 Tab 分组（5 组）+ 组内纵向滑杆列表**，取代当前的「一长条纵向滚动 + SectionHeader」 | §3.2 |
| 10 | 排期建议：**P0 两张卡**（面板骨架重做 + 颗粒接线），**P1 三张卡**（影调四件套 / 色温色调 / 裁剪） | §4 |

---

## 1. ① 功能矩阵（对标成熟 App）

### 1.1 对标对象的定位与「该抄它什么」

| App | 性质 | 开源性 | **该抄什么** | 不该抄什么 |
|---|---|---|---|---|
| **PhotonCamera (PC)** | 开源相机 App（计算摄影） | ✅ GPL，`github.com/eszdman/PhotonCamera`（本次已 clone 到 `/tmp/pc_probe` 逐行读过） | **UI 骨架与质感**：调色板 token、分组选择器、弧形刻度盘旋钮、面板圆角/模糊半径 | **功能清单**：它的编辑器只有 LUT 类后段，没有裁剪/曲线/HSL —— 对标功能会跑偏 |
| **Dazz Cam** | 闭源胶片相机 App | ❌ | **胶片参数的分组方式**与「底部横向滚动条 + 手势调参」的轻量交互 | 它的极简（它连滑杆都很少，靠预设 + 少量滑块）—— 我们要的是"全" |
| **Filmix** | 闭源胶片相机/修图 App | ❌（仅 APK 发布仓库 `Jasonyzz94/FilmixCam-releases`） | **参数清单**（与用户点名的五项高度重合）与「一键暂停效果 / 联动强度」交互 | 它的 Pro 模式（快门/ISO/对焦）—— 那是拍摄端，不是编辑器 |
| **Lightroom Mobile** | 商业标杆 | ❌ | **功能分级与分组结构**（Light/Color/Effects/Detail/Optics 五组）、双击重置、双指看剪切警告 | — |
| **Snapseed** | 商业标杆（免费） | ❌ | **工具化组织**（每个工具一个独立页面 + 独立参数集）与「选择性调整」的交互 | 它的"工具页"形态对编辑器偏重（我们要的是连续调参） |
| **泼辣修图 Polarr** | 商业 | ❌ | **专业/简易双模式**、可自定义工作界面 | 上百款工具的量级 —— 超出胶片 App 的定位 |
| **FiLMiC Pro** | 商业视频 App | ❌ | （**注：用户已澄清此为笔误，第三个 App 是 Filmix**）仅作为「弧形滑杆 = 专业感」的旁证保留 | — |

> **来源**：PC 源码 = 本地 clone（URL `https://github.com/eszdman/PhotonCamera`，clone 于 2026-10-09）；Lightroom 分组 = <https://www.lightroomqueen.com/lightroom-mobile-editing-global-sliders/>；Snapseed 工具清单 = <https://support.google.com/snapseed/answer/6183571>；Filmix = <https://apps.apple.com/us/app/filmix-camera/id6761850384> + <https://www.9k9k.com/app/101082.html>；Dazz = <https://dazzcam.app/zh/> + <https://www.pconline.com.cn/ask/244844.html> + <https://www.readsblogs.com/blog/is-dazz-cam-the-best-app-for-retro-filters/>；FiLMiC 弧形滑杆 = <https://filmicpro.com/FilmicProQSGv6.pdf>（**注：该项为笔误项，仅作"弧形滑杆 = 专业感"的旁证**）。

### 1.2 各 App 的实际参数清单（有据可查的部分）

**Lightroom Mobile**（来源见上，2017 原文 + Adobe 现行帮助目录 <https://helpx.adobe.com/lightroom/mobile.html>）：

- **Light**：Exposure · Contrast · Highlights · Shadows · Whites · Blacks（+ Tone Curve 按钮）
- **Color**：White Balance（**Temperature + Tint**）· Vibrance · Saturation（+ B&W / Color Mix 按钮）
- **Effects**：Clarity · Dehaze · Vignette（+ Split Toning）
- **Detail**：Sharpening · Noise Reduction
- **Optics**：镜头配置文件开关
- 交互原文两条（**可直接照抄**）：「To reset the sliders back to their default position, **double tap on the slider handle**」「drag with **2 fingers** instead of 1 to show the clipping warnings」

**Snapseed**（来源：Google 官方帮助页）：工具 = Tune Image · Details · **Curves** · **White Balance** · **Crop** · Rotate · **Perspective** · Expand · **Selective** · Brush · **Healing** · Vignette · Text；滤镜 = HDR Scape · Glamour Glow · Tonal Contrast · Drama · Grunge · Grainy Film · Vintage · Retrolux · Noir · B&W · Frames · Face · Lens Blur。

**Filmix**（来源：App Store 描述 + 9k9k 中文页）：
- 49 个内置预设；胶片 look = **滤镜 + 颗粒 + 影调 + 色温**
- Pro 模式（拍摄端）：快门 · ISO · 曝光补偿 · 白平衡 · 对焦 + 直方图
- **编辑器参数（9k9k 明列）**：曝光 · **阴影** · 对比度 · 亮度 · 饱和度 · **色温**；自定义 **颗粒大小** · **褪色程度（fade）** · **暗角**
- 交互：**实时预览**；「**一键暂停效果（Pause effects）**」「**联动强度（link strengths）**」「按预设自动归档」

**Dazz Cam**（来源：pconline 问答 + 评测文章）：
- 参数：**颗粒强度 · 青橙色偏 · 阴影密度 · 高光滚降 · 色温滑块 · 曝光补偿 · 焦段 · 底片模式 · 白平衡 · 双重曝光**
- UI：**底部可滑动工具栏**（左右滑切换胶卷/镜头）；右上角「⋯」管水平仪/网格；右下角相机图标切镜头
- 评测原文：「camera interface presents a live feed with a **scrollable filter bar at the bottom**. **Swipe gestures** let you adjust grain levels, toggle light leaks, or refine color shifts.」
- 双模式：**FQS（新手）/ FXN（进阶）**

**PC**（源码实测）：手动面板 = **对焦 · 曝光 · ISO · EV · 白平衡** 五格（`circularbarlib/src/main/res/layout/manual_palette.xml`），每格 = 图标在文字下方 + 15sp 文字；上方叠一个 **96dp 高的 KnobView 弧形刻度盘**。

### 1.3 功能矩阵（分级 + 实现路径）

**难度口径**：★ = 半张卡以内 · ★★ = 一张卡 · ★★★ = 需要新算法/离线标定 · ★★★★ = 需要新数据源（如 RAW）
**依赖口径**：`契约` = 需改 `FilmParams.h::kCount`（**三处同改**，见 ADR-1）；`E4-x` = 依赖既有胶片参数卡。

#### 1.3.1 必备（相册编辑器标配 —— 缺了用户会说「功能不全」）

| 功能 | 作用 | 我们的实现路径 | 难度 | 依赖 | 现状 |
|---|---|---|---|---|---|
| **裁剪 + 比例 + 旋转/拉直** | 构图；去边角杂物 | **几何变换**。方案 A（推荐）：**引擎输入侧 UV 子矩形** —— 保持 `edit_tex_` 不动，`kFsStyle` 采样时 `uv = uCropOrigin + vUV0 * uCropSize`，CPU 侧把输出 FBO 尺寸设为裁剪后像素尺寸。**必须在渲染前生效**，这样暗角 `[66..68]`、局部调整 `[79..87]` 的归一化坐标自动相对新画幅（Lightroom 语义）。方案 B（不推荐）：导出时 `Bitmap` 裁位图 —— 暗角会与画面错位 | ★★ | 无新槽位（裁剪参数不入 `FilmParams`，走编辑器会话独立参数）；需重建输出 FBO | **引擎未实现** |
| **色温（Temperature）** | 冷暖校正/风格化 | **线性域通道增益**，并入**已有的曝光×WB 成对往返段**（`kFsStyle` main 内 `uExpWbOn` 门控）⇒ **零额外 pass**。语义建议 = **mired 偏移**（相对 6500K），而非绝对开尔文 | ★★ | **契约 +2**（`[88][89]`，见下） | **引擎未实现**（当前只有 RGB 三条增益） |
| **色调（Tint）** | 洋红↔绿校正 | 同上，同一次往返内写 G 通道（或 R+B 反向） | ★ | 同上（与色温同一张卡） | **引擎未实现** |
| **高光（Highlights）** | 压回已记录的高光、救平顶白 | **显示域 luma 分区增益**：`w_hl = smoothstep(0.5, 1.0, luma)`，`c *= exp2(gain_hl * w_hl)`。与 `[4] contrast` 同域同段 | ★★ | **契约 +1**（`[90]`） | **引擎未实现** |
| **阴影（Shadows）** | 提亮暗部、找回暗部细节 | 对称：`w_sh = 1 - smoothstep(0.0, 0.5, luma)` | ★★ | **契约 +1**（`[91]`，与高光同卡） | **引擎未实现** |
| **白平衡吸管（点选中性色）** | 「这张偏黄」的最直接解法 | **UI 取色 + 反解增益**：预览区点击 → 从输入 buffer 读该点 RGB `(r,g,b)` → 以 G 为参考求增益：`gainR = g/r`、`gainG = 1`、`gainB = g/b`（**归一化到 G 不变**）→ 写入 `[1..3]`。**注意 letterbox 坐标换算**（ADR §E2-7 已记该陷阱） | ★★ | 无（复用 `[1..3]`） | **引擎未实现**（`[1..3]` 有，取色 UI 无） |
| **颗粒（Grain）** | 胶片质感的头号来源 | **引擎已实现**：`kFsStyle` 内 `if (uGrainAmount > 0.0)` 段，`passStyle()` 已传参 | ★ | 无 | ⚠️ **引擎有、UI 没接**（最便宜） |
| **暗角** | 胶片镜头感 | 已有 | — | — | ✅ 已有 |
| **LUT + 强度** | 风格 | 已有 | — | — | ✅ 已有 |
| **参数重置（单条 / 单组 / 全部）** | 试错成本 | UI 纯逻辑 | ★ | 无 | ⚠️ 部分（有单组「重置」文字，无单条、无全部） |
| **撤销 / 重做** | 试错成本 | UI 状态栈（存 `DoubleArray` 快照，每步一次渲染） | ★★ | 无 | **未实现** |
| **直方图** | 专业感刚需；判断过曝/欠曝 | **引擎有 `kFsHist` pass**（`FrameRenderer.cpp:4869`），但**只被拍照预览链路使用，编辑器链路零引用**（本次实测：`grep kFsHist` 在编辑相关代码中无命中）⇒ 需新增编辑器专用统计（或复用同款 shader） | ★★ | 引擎侧需接线；ADR §5.4 T-1 记「`kFsHist` 未含暗角、颗粒也没进」需一并补齐 | ⚠️ **引擎有 pass、编辑器链路没接** |

#### 1.3.2 色温/色调的**关键设计决策**（本报告最重要的技术判断）

**问题**：用户要「色温、色调」两条滑杆，但引擎的 WB 是 `[1..3]` 三条 RGB 增益。**2 个自由度 → 3 个增益不是双射**：同一组 (R,G,B) 可对应多组 (T, tint)。若 UI 让用户拖色温、然后从增益**反解**读数，松手后读数会跳（ADR-7 已把这条记为陷阱，也是 E2-3 当初选 R/G/B 三条的原因）。

**三个方案对比**：

| 方案 | 做法 | 优点 | 缺点 | 判定 |
|---|---|---|---|---|
| A. 纯 UI 映射 | 色温/色调滑杆拖动时**正向**算增益写入 `[1..3]`；读数只在拖动时更新，不反解 | **零契约改动** | ① 读数与参数状态分离（重进编辑器读数归零但画面仍偏色）② 与 RGB 三条滑杆共享槽位 ⇒ 两个控件互相覆盖，违反「单一参数源」 | ❌ 违反 ADR-1 |
| **B. 新增 `[88][89]`（推荐）** | `[88]` = 色温 mired 偏移（−100..+100，0 = 恒等）、`[89]` = tint 偏移（−100..+100，0 = 恒等）。引擎内：`gains = cat_matrix([88],[89])`，再乘 `[1..3]`（后者降级为「RGB 微调」）。**全部并入同一次线性域往返** ⇒ 零额外 GPU 成本 | ① 双射、可逆、读数永不跳 ② 与 `[0]`/`[1..3]` 同域同段 ③ 快照天然可存 ④ 语义清晰（相对偏移，不是绝对开尔文，避免"原片白平衡未知"的伪精确） | 契约 +2（三处同改） | ✅ **推荐** |
| C. 用绝对开尔文 | `[88]` = 色温 K（2000..12000） | 读数符合直觉 | ① JPEG 输入的白平衡是**相机已经烧进去的**，显示"5500K"是**伪精确**（我们不知道原片 WB）② 需要 CAT 全套 + 参考白点假设 | ❌ 伪精确 |

**方案 B 的引擎公式**（建议，供架构师派工时给实现者）：

```glsl
// 域：线性显示域（与 uEv/uWbGain 同段，共用 uExpWbOn 门控 ⇒ 零额外往返）
// [88] tempOffset: -100..+100，0 = 恒等；[89] tintOffset: -100..+100，0 = 恒等
// mired 近似：Δmired = tempOffset * 0.5  (⇒ ±50 mired ≈ ±1000K @ 5000K)
vec3 wb = uWbGain;                                  // [1..3] RGB 微调
wb.r *= exp2( uTemp * 0.001736 );                   // 2^(50/40) ≈ 2.38 @ +100
wb.b *= exp2(-uTemp * 0.001736 );
wb.g *= exp2(-uTint * 0.000868 );                   // 洋红↔绿，幅度取色温的一半
// 与曝光合并成一次成对往返
```

> ⚠️ 上式为**形态建议**，系数需离线标定（用一张中性灰 + 三色块测试图，验证「拖到 +100 时白点位移 ≈ 1000K」）。**不要照抄系数上机**。

#### 1.3.3 进阶（专业用户会找，缺了会觉得「不够专业」）

| 功能 | 作用 | 实现路径 | 难度 | 依赖 | 现状 |
|---|---|---|---|---|---|
| **影调曲线（Tone Curve）** | 精确控制影调分布；S 曲线 | **显示域 LUT 化**：256 点 1D 曲线在 CPU 侧算成 LUT 纹理（或直接算进 `uCurve` 数组，但 Mali 禁数组参数 ⇒ 用 1D 纹理），`kFsStyle` 内一次采样。UI = 贝塞尔/控制点编辑器 | ★★★ | 契约 +N（控制点）或走独立 JNI 上传 1D LUT（推荐，**零契约改动**，仿 `nativeEngineSetLut`） | **引擎未实现** |
| **分区饱和度（高/中/低）** | 更精细的色彩控制 | **引擎已读 `[21]`**，`[20]/[22]` 已有槽位但引擎未分区读（ADR §5.7 第 2 条明确"首版不做"） | ★★ | 无新槽位 | ⚠️ **引擎有槽位、未实现分区**；UI 已同写三槽（合规） |
| **分离色调（Split Toning）** | 阴影暖/高光冷 —— 胶片味重 | `[23] split_warm` / `[24] split_cool` **已有槽位**，拍照链路有、`kFsStyle` 零引用 | ★★ | 无新槽位 | ⚠️ **引擎有槽位、编辑器链路未实现** |
| **HSL / 颜色混合（8 色相通道）** | 单色调整（"让天空更蓝"） | 显示域 8 段色相 mask × 3 属性。**Mali 禁数组/动态索引** ⇒ 需展开成常量 if 链或查表纹理 | ★★★ | 契约 +3（H/S/L）或走独立 LUT | **引擎未实现** |
| **清晰度/纹理/去雾三分** | Lightroom Effects 组 | `[78] clarity` 已有；纹理 = 更小半径、去雾 = 局部对比 + 黑点 | ★★ | 契约 +2 | ⚠️ 部分（只有清晰度） |
| **噪点消除（降噪强度）** | 高 ISO 片救急 | JPEG 输入只能做显示域降噪（效果有限）。**建议不做**：ADR-7 已明确「已丢失的信息找不回来」 | ★★★ | — | 建议**明确不做**并在 UI 说明 |
| **锐化（USM）** | 输出锐化 | `[39] sharpen` 是拍照链路（OETF 前）；编辑器链路需另开槽位或复用 `[78]` 的双半径 | ★★ | 契约 +1 | **引擎未实现** |
| **光晕 / Halation** | 胶片高光溢出（胶片味二号来源） | `[62..65]` **已有槽位**，E4-6 卡；`kFsStyle` 当前零引用 | ★★★ | E4-6 | ⚠️ **引擎有槽位、未实现** |
| **褪色（Fade / 黑位抬升）** | Filmix/Dazz 的「褪色程度」 | 显示域黑位抬升：`c = c * (1 - f) + f * lift`。**最便宜的胶片味开关之一** | ★ | 契约 +1 | **引擎未实现** |
| **漏光（Light Leak）** | Dazz 的标志性效果 | 叠加一张程序化/贴图的光晕层，位置/强度/色相可调 | ★★ | 需 1 张贴图或程序化噪声 | **引擎未实现** |
| **局部调整（径向/线性）** | 压暗天空、提亮主体 | **引擎已实现**（`[79..87]`），E2-7 UI 正在做 | — | — | ⚠️ **引擎有、UI 在做** |

#### 1.3.4 专业（锦上添花，本期不建议排）

| 功能 | 说明 | 判定 |
|---|---|---|
| 局部曲线 / 局部色彩 | Lightroom 的 mask 内全套参数 | 引擎 mask 已有，扩参数即可；**排在局部调整验收之后** |
| 颜色范围蒙版 / 亮度蒙版 | AI 之前的"专业"标志 | 需要 CPU 侧 mask 生成 + 上传，成本高 |
| 透视校正 / 几何 | Snapseed Perspective | 需 4 点单应矩阵；对胶片 App 优先级低 |
| 修复/污点去除（Healing） | Snapseed Healing | 需 patch 匹配算法，**成本远超收益** |
| 双重曝光 | Dazz 有 | 引擎层 = 两图混合；需 UI 选第二张图。**可做但不急** |
| 边框 / 日期戳 | Dazz 的标志性元素 | **极便宜**（Kotlin 位图叠加），且胶片感强 —— 可考虑提前 |
| 预设管理 | ADR E3 已规划 | 按原计划 |
| 直方图 + 过曝/欠曝警告 | 专业刚需 | 见 §1.3.1 |
| 前后对比（分割/并排） | 专业刚需 | 见 §3.5 |

---

## 2. ② 对照我们的现状

### 2.1 现状盘点（源码实测）

**编辑器链路 `kFsStyle`**（`core/engine/src/render/FrameRenderer.cpp:4615` 起，传参在 `passStyle()` = `:12086-12180`）实际读取的 uniform：

| 槽位 | 参数 | `kFsStyle` 是否读 | `passStyle` 是否传 | UI 是否接 | 结论 |
|---|---|---|---|---|---|
| `[0]` | 曝光 EV | ✅ `uEv` | ✅ | ✅ 基础组 | 全通 |
| `[1..3]` | WB RGB 增益 | ✅ `uWbGain` | ✅ | ✅ 基础组（红/绿/蓝三条） | 全通 |
| `[4]` | 对比度 | ✅ `uContrast` | ✅ | ✅ 基础组 | 全通 |
| `[20..22]` | 分区饱和 | 只读 `[21]` | ✅ `sat_mid` | ✅ 基础组（同写三槽） | 合规（ADR §5.7） |
| `[26][27]` | **颗粒强度/ISO** | ✅ `uGrainAmount`/`uGrainSize` | ✅ | ❌ **未接** | ⚠️ **引擎有、UI 没接** |
| `[34]` | LUT 强度 | ✅ | ✅ | ✅ 色彩组 | 全通 |
| `[66..68]` | 暗角 | ✅ | ✅ | ✅ 暗角组 | 全通 |
| `[78]` | 清晰度 | ✅ `uClarity` | ✅ | ✅ 基础组 | 全通 |
| `[79..87]` | 局部调整 | ✅ `uLocal*` | ✅ | 🔄 E2-7 在做 | 进行中 |
| `[23][24]` | 分离色调 | ❌ 零引用 | ❌ | ❌ | **引擎未实现（编辑器链路）** |
| `[62..65]` | 光晕 | ❌ 零引用 | ❌ | ❌ | **引擎未实现**（E4-6） |
| `[5..10]` | 趾肩 latitude | ❌ 零引用 | ❌ | ❌ | **引擎未实现**（E4-4） |
| `[60][61]` | 颗粒密度偏置/枢轴 | ❌（`kFsStyle` 只用 `[26][27]`） | ❌ | ❌ | **引擎未实现** |
| `[28][37][38][39]` | 影调/黑白点/锐化 | ❌ | ❌ | ❌ | **引擎未实现**（ADR §ADR-12 订正：属"没做"非"不可逆"） |

**UI 现状**（`app/src/main/java/com/filmkit/app/ui/EditorScreen.kt`）：

| 项 | 现状 | 行号 |
|---|---|---|
| 分组 | `EditorParamGroup` 三组已实现（BASIC/COLOR/VIGNETTE）+ 两组 TODO（GRAIN/HALATION，`implemented=false`） | `:939-951` |
| 面板形态 | 嵌在预览与底部操作区之间（**非浮层**），高度上限 `屏高 × 0.45`，`verticalScroll` | `:1007-1013` |
| 组标题 | 复用设置页 `SectionHeader`：11sp / Mono / **强调色琥珀** / `padding(top=14.dp, bottom=6.dp)` | `SettingsScreen.kt:1387-1396` |
| 滑杆行 | `StrengthRow`：`[标签 11sp Mono CreamDim] [RelativeSlider weight(1f)] [数值 12sp Mono 强调色]`，行高 32dp，`padding(top=6.dp)` | `CameraScreen.kt:2359-2400` |
| 滑杆本体 | `RelativeSlider`：轨道高 **4dp**、圆角全高、inactive `ChipBorder(#333333)`、active `Accent(#E8842C)`；拇指 **20dp 圆形 `Cream(#F2EAD9)`**；**无中点标记、无刻度、无图标** | `CameraScreen.kt:2268-2345` |
| 读数 | 恒为 `v * 100%`（**百分比**） | `CameraScreen.kt:2392` |
| 重置 | 每组一个右对齐小字「重置」（11sp Mono） | `EditorScreen.kt:1202-1215` |
| before/after | **按住画面**看原图（`detectTapGestures(onPress + tryAwaitRelease)`），提示文字 10sp | `EditorScreen.kt:735-760` |
| 渲染节流 | 拖动中只改读数，**松手才渲染一次**（`SliderRenderGate`） | `EditorScreen.kt:1367-1390` |
| 配色 | `FilmTheme`：Body `#0D0D0D`、PanelSolid `#141414`、ChipBg `#1C1C1C`、ChipBorder `#333333`、Cream `#F2EAD9`、CreamDim `#99F2EAD9`、Accent `#E8842C` | `FilmTheme.kt:20-37` |

### 2.2 缺什么（分成两类 —— 后者便宜得多）

#### A 类：**引擎没实现**（要动 shader + 契约，成本高）

| 缺的功能 | 用户是否点名 | 需要 |
|---|---|---|
| 色温 / 色调 | ✅ 点名 | 契约 +2（`[88][89]`）+ `kFsStyle` 往返段扩写 |
| 高光 / 阴影 | ✅ 点名 | 契约 +2（`[90][91]`）+ `kFsStyle` 显示域新段 |
| 裁剪 | ✅ 点名 | **无契约**；输出 FBO 尺寸可变 + UV 子矩形 + UI 手势 |
| 白平衡吸管 | 隐含（"不专业"） | 无契约；UI 取色 + 反解 |
| 影调曲线 | — | 1D LUT 上传（仿 `nativeEngineSetLut`） |
| 分离色调 | — | `[23][24]` 已有槽位，仅需 `kFsStyle` 实现 |
| 褪色 Fade | — | 契约 +1 |
| 光晕 Halation | — | E4-6 已有排期 |
| 颗粒密度偏置/枢轴 | — | `[60][61]` 已有槽位，仅需 `kFsStyle` 实现 |
| 直方图（补齐暗角/颗粒） | — | 复用 `kFsHist`，补两段 |

#### B 类：**引擎有、UI 没接**（**纯 Kotlin，一张卡能收多个**）

| 缺的功能 | 证据 | 成本 |
|---|---|---|
| **颗粒 UI** | `FrameRenderer.cpp:4621-4622`（uniform 声明）、`:4853-4857`（使用）、`:12093-12094`（传参） | ⭐ **最低**：加一组滑杆 + 2 个 `EditorExport` 映射函数。**唯一前置**：ADR-11 的预览/全分辨率颗粒尺度差异（`gl_FragCoord` 相关）需确认口径 |
| **局部调整 UI** | `:12120-12150` 已全部传参 | E2-7 已在做 |
| **直方图** | `kFsHist` pass 已存在（`FrameRenderer.cpp:4869`），但**编辑器链路未接入**（需新增一次编辑器侧统计调用）⇒ 严格说这是 A+B 混合：引擎侧接线 + UI | 中低；需确认数据通路（readPixels 或已有回调） |
| **单条参数重置** | 引擎无关，纯 UI | 双击滑杆（Lightroom 做法），几乎零成本 |
| **撤销/重做** | 引擎无关，纯 UI（`DoubleArray` 快照栈） | 低 |
| **全部重置** | `resetBasicParams` 已有（`EditorScreen.kt:80` 导入） | 极低 |

### 2.3 一句话结论

> **用户抱怨的「功能不全」，一半是真缺（色温/色调/高光/阴影/裁剪 = A 类），一半是「引擎有但没露出来」（颗粒、直方图 = B 类）。而「面板太 low」**完全**是 UI 问题 —— 引擎侧一个字节都不用改。**

---

## 3. ③ UI 规格（重点）

### 3.1 「专业感」的差距在哪（逐条对比，不泛泛）

| # | 维度 | 我们当前 | 成熟 App 的做法 | 差距性质 |
|---|---|---|---|---|
| 1 | **滑杆行形态** | `标签 + 滑杆 + 数值%` **三段式表单**（像设置页的 `SettingSwitchRow`） | **满宽滑杆**，标签在滑杆**上方左侧**小字，数值在**拖动时才浮现**于上方右侧 | **结构级** —— 这是"low"的头号原因 |
| 2 | **中点标记** | ❌ 无。对比度/饱和度/色温的"不变点"在滑杆中点，用户看不见 | Lightroom：中点有细竖线；PC `KnobView`：每 **1°** 一条刻度线（`currentAngle += 1.0d`），线宽 `2.0f`，长 `8dp`、间距 `11dp` | **结构级** |
| 3 | **刻度** | ❌ 无 | PC：`manual_knob_dash_length=8dp` / `manual_knob_dash_padding=11dp` | 结构级 |
| 4 | **读数单位** | 恒为 `%`（曝光显示 `100%` 而非 `+1.0 EV`） | 物理量：`EV` / `K` / `±100` / `0..100` | **语义级** —— 百分比让"专业用户"立刻出戏 |
| 5 | **参数图标** | ❌ 无 | PC：每格 `17dp` 图标（`manual_knob_icon_size`）在文字**上方**；Lightroom：每组 Tab 有图标 | 结构级 |
| 6 | **分组切换** | 无切换器，**纵向滚一长条**（组多了滚很久） | Lightroom：**底部工具栏切 5 组**；PC：`HorizontalPicker`（`sideItems=2`，选中项 `colorPrimaryContainer` 底 + `colorOnPrimaryContainer` 字）；Dazz：底部可滑动工具栏 | **结构级** |
| 7 | **重置入口** | 每组一个右对齐小字「重置」 | **双击滑杆**重置单条（Lightroom 原文）+ 顶部/底部「重置全部」 | 交互级 |
| 8 | **面板材质** | 纯色 `#141414` 不透明 | PC：**半透明 + 模糊**（`cam_panel_corner_radius=28dp`、`cam_panel_blur_radius=32dp`、`cam_surface=#CC121417`） | 视觉级 |
| 9 | **组标题层级** | `SectionHeader`：**11sp** / Mono / 强调色 —— 比参数标签（11sp）**一样大**，层级塌了 | Lightroom：组标题 **15-17sp** 主文字色，与参数标签（12-13sp 次级色）拉开 | 排版级 |
| 10 | **直方图** | ❌ 无 | 专业编辑器**必备**（Lightroom 顶部常驻，可拖动） | 功能级 |
| 11 | **过曝/欠曝警告** | 无（拍照页有斑马纹 `[30]`，编辑器没有） | Lightroom：**双指拖动**显示剪切警告（原文） | 功能级 |
| 12 | **对比方式** | 按住画面看原图（✅ 这个做法本身是对的） | Lightroom：按住即可 + **分割线对比**（长按顶部图标） | 功能级（次要） |
| 13 | **撤销/重做** | ❌ 无 | 全部专业 App 都有 | 功能级 |
| 14 | **参数联动** | ❌ 无（RGB 三条独立） | Lightroom：Temp/Tint 两条；Dazz：颗粒↔预设强度联动 | 功能级 |
| 15 | **按压反馈** | 有 `pressScale`（✅） | + **触觉反馈**（PC 有 `Vibration` 类，见 `SettingsBarLayout` 构造器） | 交互级（易补） |

**一句话**：我们的面板是**「设置页控件 + 相机主题色」**，成熟 App 的面板是**「为连续调参设计的专用控件」**。差距不在颜色，在**控件形态与信息层级**。

### 3.2 布局结构规格（可照着实现）

#### 3.2.1 推荐方案：底部 Tab 分组 + 组内纵向滑杆列表

```
┌─────────────────────────────────────┐
│  ← 编辑            直方图        ⋯  │  SubPageTopBar（已有）
├─────────────────────────────────────┤
│                                     │
│                                     │
│            预览区（weight 1f）       │  ← 保持现状：面板不覆盖画面
│                                     │
│                                     │
├─────────────────────────────────────┤
│ [重置全部]              [撤销] [重做]│  工具行 h=40dp
├─────────────────────────────────────┤
│  参数区（Tab 选中的组，纵向滚动）     │  ← 高度 = 内容自适应，上限 屏高×0.42
│  ┌───────────────────────────────┐  │
│  │ 曝光              +1.0 EV     │  │  滑杆行 h=44dp
│  │ ───────────●──────────────    │  │  轨道 4dp，中点刻度，拇指 20dp
│  ├───────────────────────────────┤  │
│  │ 对比度                +12     │  │
│  │ ────────────●─────────────    │  │
│  └───────────────────────────────┘  │
├─────────────────────────────────────┤
│  光   │  色  │  效果  │  细节  │ 几何 │  分组 Tab 栏 h=56dp
└─────────────────────────────────────┘
```

**尺寸与动效参数**：

| 元素 | 规格 | 备注 |
|---|---|---|
| 预览区 | `weight(1f)`，`ContentScale.Fit` | 现状保持（面板不覆盖画面 = 能看四角暗角，这是当前设计的优点，**不要改成浮层**） |
| 参数区高度 | `heightIn(max = screenHeightDp * 0.42f)` + `verticalScroll` | 现状 0.45 → 0.42（给 Tab 栏腾 56dp，保证预览不被挤太狠） |
| 参数区水平 padding | `start/end = 16.dp` | 现状一致 |
| 分组 Tab 栏 | 高 **56dp**；5 项等分；图标 **24dp** + 文字 **11sp**；选中 = 强调色图标/文字 + **顶部 2dp 指示条**（宽 24dp 居中，圆角 1dp）；未选中 = `CreamDim` | 参考 PC `HorizontalPicker` 的"选中态用容器色块"思路，但在竖排 Tab 上改用指示条（更省高度） |
| Tab 切换动效 | 内容区 `AnimatedContent` + `slideInHorizontally(±it/6) + fadeIn(120ms)`，方向随 Tab 索引增减 | 复用 `FilmMotion` 风格（`panelEnter` 已有 spring 参数：`dampingRatio=0.9f, stiffness=380f`） |
| 组内间距 | 组标题 `padding(top=20.dp, bottom=8.dp)`；滑杆行 `padding(top=8.dp)` | — |
| 工具行 | 高 **40dp**，「重置全部」左对齐 12sp，「撤销/重做」右对齐 24dp 图标（禁用态 `CreamDim` 40% 透明度） | — |

#### 3.2.2 分组方案（5 组，覆盖 §1 矩阵的必备+进阶）

| Tab | 名称 | 参数（按此顺序） | 备注 |
|---|---|---|---|
| 1 | **光** | 曝光 `[0]` · 对比度 `[4]` · **高光** `[90]` · **阴影** `[91]` · 黑点 · 白点 | Lightroom 的 Light 组结构 |
| 2 | **色** | **色温** `[88]` · **色调** `[89]` · 饱和度 `[20..22]` · 自然饱和度 · **分离色调**（阴影暖/高光冷 `[23][24]`） | 色温/色调放最前（用户点名） |
| 3 | **效果** | LUT + 强度 `[34]` · **颗粒** `[26][27]` · 清晰度 `[78]` · 暗角 `[66..68]` · 褪色 · 光晕 `[62..65]` | 胶片味集中在这一组 |
| 4 | **局部** | 局部调整（径向/线性，`[79..87]`，E2-7） | 单独一组，因为它需要画布交互 |
| 5 | **几何** | **裁剪 + 比例 + 旋转/拉直** | 裁剪需要独立的全屏编辑模式（见 §3.6） |

> **为什么不学 Snapseed 的「每工具一页」**：Snapseed 的工具各自独立、参数少（1-2 条），适合"工具页"；我们的胶片参数需要**跨组联动对比**（调完颗粒想顺手调暗角），Tab 分组更合适。**这是有依据的设计选择，不是偷懒。**

### 3.3 控件样式规格（数值可直接抄）

#### 3.3.1 滑杆（核心控件 —— 替换 `RelativeSlider`）

| 属性 | 当前值 | **目标值** | 来源/理由 |
|---|---|---|---|
| 行高 | 32dp | **44dp**（滑杆行总高；触摸热区含行内 padding，**达到 48dp 无障碍下限**） | 当前 32dp 偏小；48dp 是 Android 无障碍最小触摸目标（**注：M3 Slider 组件自身高度为 44dp 量级，本项数值来自既有知识，未逐条核对官方页面**） |
| 轨道高度 | 4dp | **4dp（保持）** | Lightroom / Snapseed 的轨道同为 4dp 量级；PC 干脆用刻度线不用轨道 |
| 轨道圆角 | 全高圆角 | 保持 | — |
| 轨道 inactive 色 | `ChipBorder #333333` | **保持** `#333333`（或按 §3.6 提到 30% 奶油白） | — |
| 轨道 active 色 | `Accent #E8842C` | **保持** | — |
| **中点刻度** | ❌ | **新增**：宽 **2dp** × 高 **10dp** 竖线，色 `CreamDim #99F2EAD9`，垂直居中；**仅当 range 跨越恒等值时才画**（如对比度 1.0、色温 0、高光 0） | 学 PC 的 `drawLine`（`KnobView.java:161`）+ Lightroom 的中点标记 |
| **刻度组** | ❌ | 可选：每 1/4 量程一条 **1dp × 6dp** 竖线，色 `#33F2EAD9`（20% 透明度） | 学 PC `manual_knob_dash_length=8dp` / `dash_padding=11dp` 的密度感 |
| **拇指形态** | 20dp 圆点 | **二选一（推荐 B）**：<br>**A. 保持 20dp 圆点**（现状，Material 2 风格）+ 拖动时放大到 24dp<br>**B. 改竖条 handle：3dp 宽 × 20dp 高，圆角 1.5dp**（专业修图 App 通用形态，且与 PC 的刻度线语言同构） | B 更"调色台"、A 更"系统控件"。**若目标是"不 low"，选 B** —— 圆点拇指是设置页/系统控件的语言（**注：M3 Slider 的 handle 本身也是 4dp 宽 × 44dp 高的竖条，非圆点**） |
| 拇指色 | `Cream #F2EAD9` | **保持**；拖动时加 **2dp 强调色描边** | 视觉抓取点 |
| 拇指阴影 | 无 | 加 `shadow(elevation = 2.dp)` | 与轨道分离 |
| 标签 | 行内左侧 11sp Mono `CreamDim` | **移到滑杆上方左侧**，12sp `Cream`（不用 Mono，用默认字体） | 层级：标签是"名称"不是"读数" |
| 读数 | 行内右侧 12sp Mono `Accent`，恒 `%` | **滑杆上方右侧**，13sp Mono，**物理量**：曝光 `+1.0 EV` / 色温 `+30` / 对比度 `+12` / 颗粒 `35` | 见 §3.3.3 单位表 |
| 读数淡出 | 无 | 拖动结束 **1.5s** 后读数从 `Accent` 淡到 `CreamDim`（`animateColor`，300ms）；再次拖动恢复 `Accent` | 减少视觉噪音（成熟 App 通用做法） |
| **双击重置** | ❌ | **新增**：`detectTapGestures(onDoubleTap = { reset() })`，重置到该参数的恒等值 + 一次渲染 | Lightroom 原文："double tap on the slider handle" |
| 触觉反馈 | 无 | 拖动**跨过中点**时 `HapticFeedbackType.TextHandleMove`；重置时 `LongPress` | PC 有 `Vibration` 类；调参类 App 通用做法 |
| 相对拖动 | ✅ 已有（`RelativeSlider`） | **保持**（按下不跳变，这是真机踩坑换来的，见 `CameraScreen.kt:2310-2335` 注释） | **不要回退成 M3 `Slider`**（M3 Slider 的按下 snap 会让拇指跳变，这正是 `RelativeSlider` 存在的原因） |

#### 3.3.2 图标

| 项 | 规格 |
|---|---|
| 风格 | **线性（outlined）**，描边 **1.5dp**，24dp 网格 —— 线性比填充更"工具感"，且与 `CreamDim` 文字配重更轻 |
| 尺寸 | Tab 图标 **24dp**；参数行图标（可选）**20dp**；工具行图标 **24dp** |
| 色 | 未选中 `CreamDim`；选中 `Accent`；禁用 `#4DF2EAD9`（30% 透明度） |
| 参数行图标 | 建议**不加**（PC 的 5 格布局是相机快捷面板，编辑器参数多到 20+ 条，逐条配图标会变吵）→ **只在 Tab 上配图标** |
| 来源 | 用 Material Symbols Outlined（`androidx.compose.material:material-icons-extended`）；**不要自绘** |

**Tab 图标建议**（Material Symbols 名）：
`光` = `WbSunny` / `色` = `Palette` / `效果` = `AutoAwesome`（或 `Grain`）/ `局部` = `CenterFocusStrong` / `几何` = `Crop`

#### 3.3.3 读数单位表（**这一条直接决定"专业感"**）

| 参数 | 当前显示 | **目标显示** | 量程 | 恒等值 |
|---|---|---|---|---|
| 曝光 | `100%` | `+1.0 EV` / `0.0 EV` | −3.0 .. +3.0 EV | `0.0` |
| 对比度 | `100%` | `+12`（−100 .. +100 的整数） | 0.01 .. 2.0（映射到 ±100） | `0` |
| 饱和度 | `100%` | `+12` | 0 .. 2.0（映射到 −100 .. +100） | `0` |
| 色温 | — | `+30`（mired 偏移，或 `暖 30`） | −100 .. +100 | `0` |
| 色调 | — | `+15`（`洋红` / `绿` 方向提示） | −100 .. +100 | `0` |
| 高光 / 阴影 | — | `−40` / `+25` | −100 .. +100 | `0` |
| 清晰度 | `0%` | `0 .. 100`（无符号，因为 0 = 关） | 0 .. 1.0 | `0` |
| 颗粒强度 | — | `35` | 0 .. 100 | `0` |
| 颗粒 ISO | — | `400`（**物理量，直接显示 ISO**） | 20 .. 6400 | `400` |
| 暗角强度 | `0%` | `0 .. 100` | 0 .. 1.0 | `0` |
| 暗角半径/羽化 | `80%` | `80` | 0 .. 200 | `80` / `50` |
| LUT 强度 | `100%` | `100` | 0 .. 1.0 | — |

> **规则**：有物理单位的一律用物理单位（EV / ISO）；无物理单位的用 **±100 或 0..100 的整数**；**不再出现 `%`**（百分比是"设置页"的语言，不是"调色台"的语言）。

### 3.4 从 PhotonCamera 直接抄的部分（源码实证）

> 全部来自本次 clone：`https://github.com/eszdman/PhotonCamera`（GPL-3.0，可借鉴需注意许可证：**GPL 代码不能直接复制进闭源 App**，但**设计参数（尺寸/间距/层级）不受版权保护，可以照抄数值**）。

#### 3.4.1 调色板配色 token（`app/src/main/res/values/colors.xml`）

PC 的相机 chrome 用一套**独立于主题的固定暗色**（注释原文："The viewfinder is always dark, independent of the app's light/dark Material You theme"）：

| token | 值 | 用途 | 我们的对应 |
|---|---|---|---|
| `cam_surface` | `#CC121417` | 面板底（80% 不透明） | `FilmTheme.Panel #E6141414`（**可改成 #CC121417 更接近 PC，但我们的机身是暖炭黑，建议保持暖调**） |
| `cam_surface_container` | `#991E2227` | 容器（60%） | 可新增 `PanelSoft` |
| `cam_surface_container_high` | `#E6262B31` | 高容器（90%） | 可作**选中态容器底** |
| `cam_on_surface` | `#F2FFFFFF` | 主文字（95%） | `Cream #F2EAD9`（同明度，**暖色**—— 这点我们比 PC 更适合胶片主题） |
| `cam_on_surface_variant` | `#B3FFFFFF` | 次级文字（**70%**） | `CreamDim #99F2EAD9`（**60%**）—— 同构但**透明度低 10pp**（我们的次级文字略暗；若嫌弱可提到 `#B3F2EAD9` 与 PC 对齐） |
| `cam_outline` | `#4DFFFFFF` | 描边（30%） | `ChipBorder #333333`（≈20%）—— **建议提到 30%**（`#4D3A3630`）让分隔更清楚 |
| `cam_primary` | `#FFA8C7FA` | 强调色 | `Accent #E8842C` ✅ |
| `cam_panel_scrim` | `#66121417` | 面板下的 40% 遮罩 | 可新增 |
| `cam_panel_corner_radius` | **28dp** | 面板圆角 | 当前面板是全宽无圆角 → **建议浮层化时用 28dp** |
| `cam_panel_blur_radius` | **32dp** | 面板背景模糊半径 | 需要 `RenderEffect`（API 31+），**可选**（低端机降级为纯色） |

#### 3.4.2 分组切换器（`layout_modeswitcher.xml`）

PC 用 `HorizontalPicker`（wefika 库）做模式切换：

```xml
app:sideItems="2"                                  <!-- 选中项两侧各显示 2 个 -->
app:selectedColor="?attr/colorOnPrimaryContainer"  <!-- 选中文字色 -->
app:selectedBackgroundColor="?attr/colorPrimaryContainer"  <!-- 选中底块 -->
android:textColor="@color/cam_on_surface_variant"  <!-- 未选中文字 -->
android:textSize="@dimen/mode_chip_text_size"      <!-- 15sp -->
android:padding="@dimen/mode_switcher_padding"     <!-- 8dp -->
```

**可抄的形态**：选中项 = **一个色块底 + 高对比文字**（不是下划线）。
**我们的适配**：竖向 Tab 栏高度有限，用「指示条 + 强调色」更省空间；但**「选中 = 色块/指示条 + 文字变强调色」这条要抄**（当前 `SectionHeader` 是纯文字，无选中态可言）。

#### 3.4.3 弧形刻度盘 `KnobView`（**PC 最"专业"的控件**，`circularbarlib/`）

| 参数 | 值 | 出处 |
|---|---|---|
| 刻度盘区高 | **96dp** | `circularbarlib/src/main/res/values/dimens.xml:11` |
| 刻度线长 | **8dp** | `dimens.xml:2` (`manual_knob_dash_length`) |
| 刻度线间距 | **11dp** | `dimens.xml:3` (`manual_knob_dash_padding`) |
| 刻度步进角 | **1.0°**（`currentAngle += 1.0d`） | `KnobView.java:159` |
| 刻度线宽 | **2.0f**（`m_Paint.setStrokeWidth(2.0f)`） | `KnobView.java:76` |
| 刻度线色 | 白 `-1`（未选中） | `KnobView.java:77` |
| 参数文字 | **13sp**，粗体，带 **1px 黑色阴影**（`shadowDx/Dy/Radius = 1`） | `styles.xml` `ManualModeKnobText` + `BoldTextWithShadow` |
| 选中文字色 | `?android:colorControlActivated`（= 主题强调色） | `styles.xml` `ManualModeKnobTextSelected` |
| 图标尺寸 | **17dp**（`manual_knob_icon_size`），内边距 10dp | `dimens.xml:4-5` |
| **双层结构** | **Primary（外层全尺寸）+ Secondary（内层 `INNER_WHEEL_SCALE` 缩放）= 上一个参数作为内圈小刻度盘** | `KnobView.java:120-140`（`setPrimaryWheel` / `setSecondaryWheel`） |
| 生长动画 | `setDomeProgress(0..1)`，`scale(1, progress)` 从面板顶边长出 | `KnobView.java:104-125` |
| 面板出入场位移 | **125dp**（`manual_panel_slide`） | `dimens.xml:12` |

**我们该不该抄 KnobView？**
- ❌ **不抄弧形旋钮本体**：它是为**相机取景器**设计的（参数少、要求不遮挡画面、单手拇指弧线扫动）。相册编辑器的参数有 20+ 条，弧形旋钮每条要占 96dp 高，装不下。
- ✅ **抄三样**：① **刻度线的密度感**（8dp 长 / 11dp 间距 → 我们的滑杆刻度用 6dp 长 / 每 1/4 量程一条）② **参数文字带 1px 阴影**（在预览图上叠加文字时的可读性技巧，**我们的"按住画面看原图"提示文字可以直接用**）③ **选中态用强调色**。

### 3.5 交互规格

| 交互 | 规格 | 依据 |
|---|---|---|
| **拖动预览** | **保持现状**（拖动中只更新读数，**松手渲染一次**）。理由：ADR 记录的真机数据 —— 全分辨率后段 < 55ms，但完整链 502ms(RCD) ⇒ 拖动中渲染会卡 | `editor-ADR.md` §5「E2-1 性能基线」+ `SliderRenderGate` |
| **双击重置单条** | 双击滑杆任意位置 → 该参数回恒等值 + 渲染一次 + 触觉反馈 | Lightroom 官方原文 |
| **长按看原图** | **保持现状**（按下即见、松手即回）。**改进**：提示文字加 PC 式 1px 黑阴影；把提示从 10sp 提到 11sp；提示位置从底部居中改到**右上角**（底部容易被手指遮） | `EditorScreen.kt:748-760` + PC `BoldTextWithShadow` |
| **分割对比** | 可选（P2）：长按顶部「对比」图标 → 画面中间出现 2dp 竖线（`Accent`），左右拖动分割线，左=原图右=成片 | Lightroom 分割视图 |
| **过曝/欠曝警告** | 可选（P2）：**双指按住**画面 → 过曝像素闪红、欠曝闪蓝（引擎侧可用 `[30] zebra_thr` 同款逻辑，编辑器链路需新增一个预览专用 uniform） | Lightroom 双指做法 |
| **参数联动** | ① **色温/色调 ↔ RGB 微调**：色温/色调是主控件，RGB 三条降级为"微调"（放在同组的"高级"折叠里）；两者**乘性叠加**，互不覆盖 ② **颗粒强度 = 0 时**，颗粒 ISO/密度滑杆置灰（不可达参数不给操作） ③ **LUT 未选时**，强度滑杆隐藏（现状已如此 ✅） | Dazz/Filmix 的「联动强度」；现状 LUT 已是此逻辑 |
| **撤销/重做** | 每次「松手渲染」前把 `DoubleArray` 压栈（上限 20 步）；UI 显示"可撤销"时图标才点亮 | 通用 |
| **重置全部** | 工具行左侧「重置」→ 二次确认（`AlertDialog`）→ 所有槽位回快照基线 | — |
| **裁剪模式** | 进入后：预览区变全屏（面板收起）、显示九宫格（3×3，1dp `#4DFFFFFF`）、四角 + 四边共 8 个把手（**把手视觉 3dp 线 + 24dp 触摸热区**）、底部一行比例 chip（`原始 / 1:1 / 4:3 / 3:2 / 16:9`）+ 「拉直」滑杆（−45°..+45°，带 1° 吸附）+ 完成/取消 | Snapseed/Lightroom 的裁剪页；PC 有 `viewfinder_round_corner_radius=40px` 的圆角遮罩可参考"裁剪框外的暗化" |

### 3.6 配色与排版规格

**沿用 `FilmTheme`，只做必要补充**（不新造颜色值 —— 遵守 `FilmTheme.kt` 顶部注释的规范）：

| 新增 token | 值 | 用途 |
|---|---|---|
| `PanelGlass` | `#CC141414` | 面板半透明底（对应 PC `cam_surface #CC121417` 的 80% 不透明度做法） |
| `Divider` | `#26F2EAD9` | 组间分隔线（1dp） |
| `Disabled` | `#4DF2EAD9` | 禁用态文字/图标（30%） |
| `TrackInactive` | 复用 `ChipBorder #333333` | 滑杆未激活轨道 |
| `TickDim` | `#33F2EAD9` | 次级刻度线（20%） |

**排版层级（关键 —— 当前层级的毛病是「组标题和参数标签一样大」）**：

| 层级 | 字号 | 字重 | 字色 | 字体 | 用途 |
|---|---|---|---|---|---|
| L1 页面标题 | 17sp | SemiBold | `Cream` | Serif | 「编辑」（现状 ✅） |
| L2 分组标题 | **15sp** | SemiBold | `Cream` | 默认 | 组内小标题（如「白平衡」「颗粒」）—— **当前是 11sp 强调色 Mono，太小太花** |
| L3 参数标签 | 12sp | Normal | `Cream` | 默认 | 「曝光」「色温」 |
| L4 读数 | 13sp | SemiBold | `Accent` | **Mono** | `+1.0 EV`、`+30` |
| L5 说明/提示 | 11sp | Normal | `CreamDim` | 默认 | 「按住画面看原图」 |
| Tab 文字 | 11sp | Medium | 选中 `Accent` / 未选 `CreamDim` | 默认 | 5 个 Tab |

> ⚠️ **一个反直觉但重要的改动**：`SectionHeader` 当前用**强调色琥珀 + Mono + 11sp**。三者叠加的效果是"像终端日志"而不是"像专业面板"。专业 App 的分组标题是**中性主文字色 + 较大字号**，强调色**只留给"当前值/选中态"**。建议分组标题改为 `Cream 15sp`，把琥珀色让给读数。

**间距体系**（8dp 基准）：
- 面板水平 padding：**16dp**
- 组间：**20dp**
- 组标题 → 首条滑杆：**8dp**
- 滑杆行间距：**8dp**（行高 44dp ⇒ 视觉密度 ≈ 52dp/条，一屏 0.42×800dp ≈ 336dp 可容 **6 条**，配 Tab 分组刚好）
- 滑杆上方标签 ↔ 滑杆轨道：**6dp**
- Tab 栏内 padding：`vertical = 6dp`

**暗色主题对比度校验**（WCAG AA 需 ≥ 4.5:1，大字 ≥ 3:1）：

| 组合 | 对比度 | 判定 |
|---|---|---|
| `Cream #F2EAD9` on `PanelSolid #141414` | **15.39:1** | ✅ AAA |
| `CreamDim`（`#99F2EAD9` = **60%** alpha）合成后 ≈ `#99948A` on `#141414` | **6.13:1** | ✅ AA（正文可用） |
| `Accent #E8842C` on `#141414` | **6.81:1** | ✅ AA（正文可用） |
| `Disabled`（`#4DF2EAD9` = **30%** alpha）合成后 ≈ `#575550` | **2.46:1** | ⚠️ 仅限禁用态（无需满足 AA） |
| `ChipBorder #333333` on `#141414` | **1.46:1** | ⚠️ 仅作描边/轨道（非文本，OK） |

> 计算方式：sRGB 线性化后 `(L_lighter+0.05)/(L_darker+0.05)`（本表数值已用脚本复算，非目测）。
> **建议把 `ChipBorder` 提到 `#4D3A3630`（30% 奶油白）**，让轨道和描边在暗底上更明确 —— 这是 PC 用 `#4DFFFFFF`（30%）的做法。

---

## 4. ④ 优先级排序（用户感知价值 ÷ 实现成本）

### 4.1 排期表

| 优先 | 卡片 | 内容 | 成本 | 用户感知 | 依赖 | 建议 |
|---|---|---|---|---|---|---|
| **P0-1** | **面板骨架重做** | ① 底部 Tab 分组栏（5 组，56dp）② 滑杆控件升级（44dp 行高 / 中点刻度 / 双击重置 / 物理量读数 / 拖动放大拇指 / 跨中点触觉）③ 排版层级（L2 15sp / L3 12sp / L4 13sp Mono）④ 新增 4 个主题 token ⑤ 单条重置 + 重置全部 | **★★**（纯 UI，1 张卡） | ⭐⭐⭐⭐⭐ **直接解「太 low」** | 无（引擎零改动） | **先做** —— 它同时把「读数单位」「中点」「双击」三件事一次性做对，后续每加一个参数都白赚 |
| **P0-2** | **颗粒 UI 接线** | `EditorParamGroup.GRAIN.implemented = true`；加「颗粒」组（强度 `[26]` / ISO `[27]`）；`EditorExport.kt` 加两个映射函数 | **★**（引擎已有） | ⭐⭐⭐⭐ 胶片味立现 | ⚠️ **前置确认** ADR-11 的预览/全分辨率颗粒尺度口径 | **可与 P0-1 合并成一张卡**（都是 UI 层，且 P0-1 的 Tab 栏需要至少 4 个组才有意义 —— 颗粒正好补上第 4 组） |
| **P1-1** | **影调四件套：高光 / 阴影 / 黑点 / 白点** | 契约 +2..4（`[90][91]` + 可选黑/白点）；`kFsStyle` 加显示域 luma 分区段（与 `[4] contrast` 同段，共用门控） | ★★（1 张卡 + 离线标定） | ⭐⭐⭐⭐⭐ **用户点名** | 契约三处同改 | **P1 首张** |
| **P1-2** | **色温 / 色调** | 契约 +2（`[88][89]`，mired/tint 语义）；并入现有曝光×WB 往返段（**零额外 GPU 成本**）；UI 两条滑杆 + RGB 微调折叠；白平衡吸管（可选同卡或拆出） | ★★（1 张卡） | ⭐⭐⭐⭐⭐ **用户点名** | 契约三处同改；需离线标定系数 | **P1 第二张**（与 P1-1 可并行，但**都改契约 ⇒ 建议合并成一张「契约扩展卡」**，避免两次动 `kCount` 引发两次启动崩溃风险） |
| **P1-3** | **裁剪 + 比例 + 拉直** | 引擎：输出 FBO 尺寸可变 + `kFsStyle` UV 子矩形（`uCropOrigin`/`uCropSize`）；UI：全屏裁剪模式（九宫格 + 8 把手 + 比例 chip + 拉直滑杆） | ★★★（1-1.5 张卡） | ⭐⭐⭐⭐⭐ **用户点名**；且是"相册编辑器标配"的第一条 | 无契约改动（裁剪参数走编辑器会话，不进 `FilmParams`）；**需确认 `EditorEngineSession` 是否支持尺寸变化** | **P1 第三张**（独立于契约扩展，可并行） |
| **P2-1** | **直方图 + 过曝警告** | 复用 `kFsHist`；补暗角/颗粒进直方图（ADR §5.4 T-1/T-2 的待办）；UI 右上角常驻迷你直方图（可点击放大） | ★★ | ⭐⭐⭐⭐ 专业感强 | 引擎侧补齐 `kFsHist` | P2 首张 |
| **P2-2** | **撤销 / 重做** | UI 状态栈（`DoubleArray` 快照，20 步） | ★ | ⭐⭐⭐ | 无 | 与 P0-1 同批做更省（工具行已经要加） |
| **P2-3** | **褪色 / 分离色调** | 褪色 = 契约 +1（黑位抬升）；分离色调 = `[23][24]` **已有槽位**，仅需 `kFsStyle` 实现 | ★★ | ⭐⭐⭐⭐ 胶片味 | 契约（褪色） | 与 P1-1 合并契约扩展 |
| **P3** | **影调曲线 / HSL** | 1D LUT 上传（仿 `nativeEngineSetLut`）；HSL 需 8 段色相 mask | ★★★★ | ⭐⭐⭐ 专业用户才要 | 大 | **本期不做**，等 P0-P2 验收后按用户反馈决定 |
| **P3** | **光晕 Halation** | E4-6 既有排期 | ★★★ | ⭐⭐⭐⭐ | E4-6 | 按原排期 |
| **P3** | **边框 / 日期戳 / 漏光** | Kotlin 位图叠加 / 程序化噪声 | ★ | ⭐⭐⭐（胶片感强但非"专业"） | 无 | 可作为"彩蛋卡"插入 |

### 4.2 合并建议（减少卡数）

| 合并 | 理由 |
|---|---|
| **P0-1 + P0-2 + P2-2** → 一张「编辑器面板 v2」卡 | 全是 UI 层，同一个文件（`EditorScreen.kt` + `CameraScreen.kt` 的控件）+ `FilmTheme.kt`；Tab 栏需要 ≥4 个组，颗粒正好补齐；工具行一次做完（重置/撤销/重做） |
| **P1-1 + P1-2 + P2-3(褪色)** → 一张「契约扩展 + 影调色彩段」卡 | **三次契约改动合并成一次**（`kCount` 88 → 92..94），规避 ADR-1 记录的"漏一处 = 启动崩溃"风险；且高光/阴影/色温/色调**都能并进已有的成对往返/显示域段**，实现者一次把 `kFsStyle` 的新段写完 |
| **P1-3 裁剪** 独立一张 | 它是几何变换，与色彩链路无交集；且需要 UI 的全屏交互模式（独立工作量） |

### 4.3 给架构师的三条风险提示

1. **契约改动是本项目历史事故高发区**（`FilmParams.h` 注释原文：「v0.10.196 真实事故：只改了本常量（59→60）⇒ 装包后应用启动即崩」）。P1 的三次扩展**必须合并成一次**，并按 ADR-1 的四处同步清单走。
2. **颗粒 UI 的 ADR-11 陷阱**：`kFsStyle` 的颗粒用 `gl_FragCoord.xy * 6.0 / uGrainSize`（`FrameRenderer.cpp:4854`）—— 这是**屏幕像素坐标**，意味着**预览（1216×1621）与全分辨率导出（4080×3072）的颗粒物理尺度不同**。接 UI 前必须先定口径（按短边归一化？还是导出时按比例放大 `uGrainSize`？）。**这是"引擎有"但"接 UI 会露馅"的典型**。
3. **不要照抄 PC 的 KnobView**：它是取景器控件（96dp 高 / 参数少），编辑器 20+ 参数装不下。抄它的**刻度密度、文字阴影、选中色**即可。

---

## 5. 附录

### 5.1 来源清单（全部 URL）

| 用途 | URL | 获取方式 |
|---|---|---|
| PC 源码（配色/dimens/布局/KnobView） | <https://github.com/eszdman/PhotonCamera> | `git clone --depth 1` 到 `/tmp/pc_probe`，逐文件读取（GPL-3.0） |
| Lightroom Mobile 分组结构 + 双击重置 + 双指警告 | <https://www.lightroomqueen.com/lightroom-mobile-editing-global-sliders/> | web_fetch |
| Lightroom Mobile 官方工具目录 | <https://helpx.adobe.com/lightroom/mobile.html> | web_fetch（目录页） |
| Snapseed 工具清单 | <https://support.google.com/snapseed/answer/6183571> | DDG 摘要（页面本体未抓） |
| Filmix App Store 描述（49 预设 / Pro 模式 / 暂停效果 / 联动强度） | <https://apps.apple.com/us/app/filmix-camera/id6761850384> | web_fetch |
| Filmix Google Play | <https://play.google.com/store/apps/details?id=com.jason.filmlut_mobile> | DDG 摘要 |
| Filmix 中文功能清单（曝光/阴影/对比度/亮度/饱和度/色温 + 颗粒大小/褪色/暗角） | <https://www.9k9k.com/app/101082.html> | web_fetch |
| Filmix 发布仓库（确认闭源） | <https://github.com/Jasonyzz94/FilmixCam-releases> | DDG 摘要（README 原文："本仓库不公开应用源代码"） |
| Dazz Cam 官网 | <https://dazzcam.app/zh/> | DDG 摘要 |
| Dazz Cam 参数清单（颗粒强度/青橙色偏/阴影密度/高光滚降/色温/曝光补偿） | <https://www.pconline.com.cn/ask/244844.html> | DDG 摘要 |
| Dazz Cam 界面入口（底部可滑动工具栏 / 右上角⋯） | <https://www.pconline.com.cn/ask/850837.html> | DDG 摘要 |
| Dazz Cam 交互（scrollable filter bar + swipe gestures） | <https://www.readsblogs.com/blog/is-dazz-cam-the-best-app-for-retro-filters/> | DDG 摘要 |
| FiLMiC Pro v6 弧形滑杆（**笔误项，仅作旁证**） | <https://filmicpro.com/FilmicProQSGv6.pdf> | DDG 摘要 |
| Material 3 Slider 规范 | <https://m3.material.io/components/sliders/specs> | web_fetch（**页面为 JS 渲染，正文未取到**；§3.3.1 中标注"保持"的 M3 相关值来自本人既有知识 + 与项目现状一致，**未逐条核对官方页面**） |

### 5.2 本次调研的能力边界（如实说明）

1. **`web_search` 工具在本环境不可用**（报错：`DeepSeek search has no API key`）。全部联网检索改用 `web_fetch` 直接抓 `https://html.duckduckgo.com/html/?q=...` 的搜索页，再按需抓具体 URL。
2. **本地 `PhotonCamera` worktree 是空目录**（`/Users/wl/.trae-cn/worktrees/PhotonCamera` 存在但无内容；`~/Downloads/PhotonCamera*` 只有 3 张 PC 拍的 JPG 样片，非源码）。源码改由联网 clone 获取，**已成功且逐文件读过**（结论可信度高）。
3. **Dazz Cam / Filmix 均为闭源**，且**我无法查看截图**（`web_fetch` 只能取文本，图片内容取不到）。因此：
   - 它们的**功能清单**来自 App 商店描述与中文评测文章（有据可查，见 §5.1）；
   - 它们的**UI 细节**（滑杆粗细、字号、颜色值）**未能取得** —— §1.2 里 Dazz 的"底部可滑动工具栏"有来源，但**具体的控件尺寸没有任何来源，本报告没有编造**。
   - 若架构师需要 Dazz 的像素级 UI 规格，**必须让有屏幕的人（用户）提供截图**。
4. **PC 的许可证是 GPL-3.0**：代码不能直接复制进本项目（若本项目不是 GPL）。本报告只抄**设计参数**（尺寸/间距/配色值），这些不受版权保护；**若架构师打算复制 PC 的 Java/Kotlin 代码，需先确认本项目的许可证兼容性**。
5. §3.3.1 表格中标注「M3 标准」的数值（44dp 触摸目标、20dp 拇指、4dp 轨道）来自本人既有知识，**未逐条核对 M3 官网**（该页面 JS 渲染，抓取失败）。

### 5.3 本报告的关键代码引用索引

| 文件 | 行号 | 内容 |
|---|---|---|
| `core/engine/src/film/FilmParams.h` | `kCount = 88`；`[26][27]` 颗粒；`[62..65]` 光晕；`[66..68]` 暗角；`[78]` 清晰度；`[79..87]` 局部 | 契约现状 |
| `core/engine/src/render/FrameRenderer.cpp` | `:4615` | `kFsStyle` shader 起始 |
| 同上 | `:4621-4622` | `uGrainAmount` / `uGrainSize` uniform 声明 |
| 同上 | `:4853-4857` | 颗粒实际使用（`gl_FragCoord.xy * 6.0 / uGrainSize + uSeed`）← **ADR-11 陷阱所在** |
| 同上 | `:12086-12180` | `passStyle()` 全部传参（含 `p.grain_amount` / `p.grain_size`） |
| 同上 | `:6224-6226` | `u_style_` 的 grain uniform location 查询 |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | `:939-951` | `EditorParamGroup` 枚举（3 实现 + 2 TODO） |
| 同上 | `:960-1096` | `EditorParamsPanel`（面板形态/高度上限/纵向滚动） |
| 同上 | `:1115-1196` | `EditorBasicGroup` |
| 同上 | `:1202-1215` | `GroupReset`（右对齐小字「重置」） |
| 同上 | `:1367-1390` | `SliderRenderGate`（松手才渲染） |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | `:2268-2345` | `RelativeSlider`（轨道 4dp / 拇指 20dp / 相对拖动） |
| 同上 | `:2359-2400` | `StrengthRow`（标签+滑杆+百分比） |
| `app/src/main/java/com/filmkit/app/ui/FilmTheme.kt` | `:20-37` | 配色 token |
| 同上 | `:60-78` | `FilmMotion` 转场 |
| `app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt` | `:1387-1396` | `SectionHeader`（11sp 强调色 Mono） |
| `docs/reports/editor-ADR.md` | ADR-1 / ADR-7 / ADR-11 / ADR-12 / §5.4 T-1 / §5.7 | 契约与约束 |
| `/tmp/pc_probe/app/src/main/res/values/colors.xml` | — | PC 调色板 token |
| `/tmp/pc_probe/app/src/main/res/values/dimens.xml` | — | PC 尺寸体系 |
| `/tmp/pc_probe/circularbarlib/src/main/res/values/dimens.xml` | `:2-12` | KnobView 刻度参数 + `manual_knob_height=96dp` + `manual_panel_slide=125dp` |
| `/tmp/pc_probe/circularbarlib/src/main/res/layout/manual_palette.xml` | — | PC 手动面板 5 格布局 |
| `/tmp/pc_probe/circularbarlib/.../knobview/KnobView.java` | `:74-78` / `:104-125` / `:143-170` / `:176-200` | 刻度线绘制 / dome 动画 / 1° 步进 / 双层 wheel |
| `/tmp/pc_probe/app/src/main/res/layout/layout_modeswitcher.xml` | — | `HorizontalPicker` 分组切换器参数 |
