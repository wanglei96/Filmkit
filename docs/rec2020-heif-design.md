# 10bit HEIF 交付链 + 公共工作空间切 Rec.2020 线性 —— 方案

> 状态：**待用户拍板分期与两处取舍**（2026-10-14 起）
> 一手来源（三张调研卡，全部已落盘）：
> - 本仓盘点 `docs/reports/r-next-10bit-heif-REPO-AUDIT.md`（262 行 / 99 处 文件:行）
> - PC + vivo 取证 `docs/reports/r-next-10bit-heif-PC-EVIDENCE.md`（348 行）
> - 标准做法 `docs/reports/r-next-10bit-heif-STANDARD-PRACTICE.md`（348 行 / 每条带 URL 或行号）
> 上位约束：`AGENTS.md`（质量优先、严禁简化关键质量管线、先 100% 实现再实测再评估）；
> 既有铁律 `docs/engine-slots-design.md:441-446`（换工作空间必须单独一期）。

## 1. 目标（用户已拍板）

1. **交付位上 10bit HEIF**（2026-10-13 拍板）：连带 10bit 输出路径 + HEIF 编码 + 交付色域与工作空间联动。
2. **公共工作空间切 Rec.2020 线性**（同上，且明确**必须单独一期**）。
3. 2026-10-14 追加口径："跟 PC 一致"——该口径已在引擎 1（sigmoid contrast 1.5）落地；
   **本期不适用**（见 §2.6：PC 的 HEIF 只到 8bit，无 10bit 可对齐）。

## 1b. 用户拍板（2026-10-14，本条覆盖一切推断）

| # | 拍板 | 落地含义 |
|---|---|---|
| P1 | **分期 A → B，同意** | §3 生效，不合并 |
| P2 | **默认引擎切 sigmoid**（理由：用户个人观感默认拍照全面好于 filmic；filmic 后期可能删，本期先留） | `[98] engine_slot` 默认 0 → **1**（C++ `FilmParams.h` + Kotlin `CameraPresets.kt` + 内置预设 + 相关测试口径）。⚠ `[98]=0` 的"逐字节零回归"判据**保留为守卫**（它证明引擎 0 路径没坏），但"默认档基线"语义随之改变：今后默认档的零回归以**引擎 1** 为准（`film_d5_sigmoid` 基线） |
| P3 | **保存格式放系统设置、用户自选：默认 JPEG，可选 HEIF** | 设置页新增"照片格式"项 + prefs 键；**默认路径仍是现有 JPEG 链（不动）**，HEIF 为可选档。**落地分两步（2026-10-14 追记）**：① **8bit HEIF 先落地** —— 照抄 PC 的现成实现（`gallery/HeicExportEncoder.kt:34-79` = `androidx HeifWriter` + `INPUT_MODE_BITMAP` + `addBitmap`），写的是**标准 HEIF 图片容器**，零风险；② **10bit 是增强档** —— 真机探针已证明 `MediaCodec` + `MediaMuxer(MUXER_OUTPUT_HEIF)` 产出 `ftyp,moov,free,mdat`（MP4 结构，非 HEIF 图片），10bit 需自研写入器或其它 API，路线见 `docs/reports/r-next-heif-container-ROUTE.md`。编码失败/不可用 ⇒ **回落 JPEG**（照片优先，项目红线） |

## 2. 决定方案的六条一手结论

| # | 结论 | 证据 |
|---|---|---|
| 2.1 | **androidx `HeifWriter` 不可用于 10bit**：`highBitDepthEnabled`/`useBitDepth10` 硬编码 `false`，Builder 无任何色彩/ICC API（仅 7 个 setter），缓冲输入只收 `YUV_420_888` ⇒ **唯一路径 = `MediaCodec` HEVC Main10 + `MediaMuxer(MUXER_OUTPUT_HEIF)` + `MediaFormat` 的 `color-*` 键** | HeifWriter.java:410 · HeifEncoder.java:72-75 · :303（STANDARD §②） |
| 2.2 | **色彩标签必须显式写**：HEIF `colr/nclx`（CICP）；BT.2020 线性 = primaries **9** + transfer **8**（合法）；无标签时解码端**按 sRGB 默认解释** | libheif nclx.cc:314-322 / 360-373（STANDARD §②） |
| 2.3 | **切 Rec.2020 有标准依据**：darktable 默认工作空间 = **linear Rec2020**；ACES 选 AP1 的理由是"工作空间原色要包住交付色域"；**BT.2020 = D65 ⇒ 不牵动白点链** | colorin.c:87/200 · colorspaces.c:678-689 · acescg.md:18（STANDARD §④） |
| 2.4 | **本仓现状**：唯一真降位点 = `FrameRenderer.cpp:8519` `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` → `:8503 tex_cap_=GL_RGBA8`；8bit 假设 **17 处**（`/255.0`、`kFsHist` 256×96 RGBA8、`ARGB_8888`…）；**无位深参数槽**（下一个可用 `[99]`，`kCount` 现 99） | REPO-AUDIT §②③ |
| 2.5 | **dither 必须按输出位深重标**（本期**最易漏项**）：darktable 一手 `dither.c:326` 按位深缩放；本项目 dither 是按 8bit 标定的 | FrameRenderer.cpp:4749（STANDARD §⑥） |
| 2.6 | **这一期 PC 不是参照物**：PC 唯一 HEIF 写点 `HeicExportEncoder.kt:34-79` 走 `HeifWriter.INPUT_MODE_BITMAP`+`addBitmap`，**无位深/profile/HDR 声明**（8bit）；PC 渲染链末端已是 `Bitmap.Config.RGBA_F16`+`ColorSpace`（`raw/RawDemosaicProcessor.kt:9124`）⇒ **位深到位、编码没接上**。PC 的 HDR 侧（gainmap）锚死 linear sRGB（`hdr/GainmapSource.kt:11-13`、`GpuReferenceGainmapProducer.kt:543-545`） | PC-EVIDENCE §①②⑦ |

| 2.7 | **真机探针实测（2026-10-14，vivo X300 / Android 16 / SDK 36）**：**Q1 ✓** 硬件 `c2.mtk.hevc.encoder` 支持 `Main10/Main10HDR10` + **P010(54)**（软件臂无 10bit、上限 512×512 不可用）；**Q2 ✓** `colr/nclx` 真落盘 = **primaries 9 / transfer 8 / matrix 9 / full_range 1**（平台把 Android 常量 `BT2020=6`/`LINEAR=1` 映射成 CICP）；**Q3 ✓** `hvcC` `profile_idc=2`(Main10) / `bitDepthLuma·ChromaMinus8=2` ⇒ **真 10bit**；**Q4 ✓ 自研容器可行** —— 架构师手拼 HEIF（650 B）被 macOS 原生解码器强制解码出 1024×256 真实像素（原型 `tools/heif10-container-ref.py`）。⚠ `MediaMuxer(MUXER_OUTPUT_HEIF)` + `video/hevc` 产出 **MP4**（`ftyp=mp42`+`moov/trak` 视频轨）非 HEIF 图片；AOSP 唯一钥匙是 track MIME=`image/heic`（`MPEG4Writer.cpp:2314`），但其 `colr`/`pixi` 被 `camera_heif_gainmap()` flag 门控 | probe 报告 + `r-next-heif-container-ROUTE.md` |

**可直接照抄的两块一手资产**（切域时用）：
- 工作空间→输出域的矩阵**构造法**（非硬编码）：`computeWorkingToOutputTransform = outputFromXyz(D50)·xyzFromWorking(D50)`（`raw/RawDemosaicProcessor.kt:7579-7587`）+ BT2020 色度坐标 `:24-29`。
- vivo 的 10bit 链路：`bt2020FullYuv2Rgb10bit`（BT.2020 全范围 NCL）、P010 高低位打包、`ENABLE_P3` 控制的 `bt2020ToP3` 转换点；LUT 容器 = 56B 头 + 33³×3 float32 = 431300B（magic `CUBE2BIN8`/`3DLUTBIN8`）。

## 3. 分期（建议 A → B，不合并）

| 期 | 内容 | 爆炸半径 | 可独立验收 |
|---|---|---|---|
| **A** | **10bit HEIF 交付链**：位深收口 + 输出提位深 + 10bit 编码 + nclx 标签 + dither 重标 | 仅输出端；**不碰 D4 定义、不碰 `film_d5` 零回归基线**（REPO-AUDIT §⑥ 确认解耦） | ✅ 容器/标签可 100% 自动化 |
| **B** | **公共工作空间切 Rec.2020 线性**：gamut 矩阵组 + CCM 终点 + 预览/静拍输入口径 + 全部预设重标 | **A 组零回归守卫会同时全红**（见 §5） | 需重建基线 |

**为什么 A 先**：A 的技术风险集中在**编码器可用性**（gate 见 §4.1），与工作空间无关；B 一旦先做，所有预设与全部零回归基线要重标一次，此时再改输出位深会**二次重标**。
**为什么不合期**：B 会让 `[98]=0` 的逐字节零回归证明力消失（`engine-slots-design.md:441-446` 铁律），与 A 的输出端改动混在一起将无法归因。

## 4. 期 A 落地路线

### 4.1 Gate（**必须先跑通，否则路线作废**）：X300 真机编码能力探针
三个只有真机能答的问题（STANDARD §⑦）：
1. X300 是否有可用于 **still image** 的 **HEVC Main10** 编码器且支持 **P010** 输入（静态常量 ≠ 机型可用，`KEY_PROFILE` 只是 hint）；
2. `MediaFormat` 的 `color-standard`/`color-transfer` 是否**真的写进 HEIF 的 `colr/nclx` box**（AOSP 无公开保证，androidx 根本不设这些键）；
3. 下游（系统相册 / 微信 / PC）如何解释 **transfer=8（线性）** —— 决定"线性交付"是否必须改 PQ/HLG 或必须同时出 SDR 备份。

**交付形态**：最小诊断探针（设置页「实验与诊断」入口 + 日志/文件回读），装包 → 点一次 → 导出结果 ⇒ 不依赖用户手拍。
探针内容：枚举 HEVC 编码器 → 查 Main10/10bit 支持 → 用已知测试图编 10bit → 写 HEIF → **回读并解析 `colr/nclx`** → 报告结论。

### 4.2 位深收口（可与 4.1 并行，路线无关）
把散落的 8bit 常量收成**单一真源**（REPO-AUDIT §③ 的 17 处），为提位深做准备；**行为零变化**（现有守卫全绿即证明）。
落点：`FrameRenderer.cpp` 的 `/255.0`（:5183/:5814）、`kFsHist` 尺寸/格式（:6023/:6026）、`CameraController.kt:3469` 的 `ARGB_8888`、`tex_cap_`/readback 组合（:8503/:8519）。

### 4.3 输出提位深 + 编码
- 中间纹理保持 RGBA16F（现状）；**降位点**改为 10bit 输出（`GL_RGB10_A2` 或 16F readback 后 CPU 转 10bit，按 4.1 实测的输入要求定）。
- 编码：`MediaCodec` HEVC Main10 → `MediaMuxer(MUXER_OUTPUT_HEIF)`；`KEY_COLOR_STANDARD`/`KEY_COLOR_TRANSFER`/`KEY_COLOR_RANGE` 按 4.1 结论写。
- **回退必须保留**：编码器不可用/失败 ⇒ 回落现有 JPEG 路径（照片优先，不丢片——项目红线）。

### 4.4 dither 重标（§2.5）
dither 幅度按输出位深缩放（darktable `dither.c:326` 口径），10bit 下重算；**判据 C 专门抓这一项**。

### 4.5 期 A 判据（A–F，STANDARD §⑥）
- **A** 容器/标签：HEIF box 解析（`colr/nclx` primaries/transfer/range 逐字段）+ 位深（`bit_depth_luma_minus8`）—— 可 100% 自动化。
- **B** 可分辨跨度：沿用项目判据，**单位换 10bit LSB**（8bit 阈值 1 LSB → 10bit 4 LSB 需重新标定）。
- **C** dither 标定：能直接抓出"dither 未重标"（8bit 幅度用在 10bit 上）。
- **D** 色域边界测试图（为 B 期预置）。
- **E** 交付链对照：**同显示域对照**，不要求像素级 CRC 零回归（位深变更下 CRC 判据不适用）。
- **F** 下游解释：系统相册/微信/PC 各看一次（人工，记录是否色偏/变暗）。

### 4.6 照片格式设置项（用户拍板 P3）
设置页新增「照片格式」：**JPEG（默认，现有链不动）** / **HEIF（10bit）**。prefs 键沿用既有命名风格；保存路径按选项分派；**HEIF 失败回落 JPEG 并记日志**。实现依赖 §4.1 gate 的编码器与标签结论。

## 5. 期 B 爆炸半径（8 面 + 守卫清单）

**必须一起改的落点**（REPO-AUDIT §③）：
1. filmic v5 gamut 的 **sRGB 原色/LMS 矩阵整组** + sRGB 色域边界解析解 —— `FrameRenderer.cpp:4261-4273`、`:4334-4367`（`:4251` 注释"我们管线本就是 sRGB 线性，check 完即输出"届时效）。
2. **CCM 链终点** = 线性 sRGB —— `StdColorMatrix.kt:29-33`（`X2S` 9 float）+ `FrameRenderer.cpp:1001-1013`（`uCcm` 注释 + `clamp(0,64)`）。
3. **预览/静拍输入去 gamma 口径** —— `FrameRenderer.cpp:331`（`kFsOes`）/`:347`（`kFsStill`）的 `pow(rgb, vec3(2.2))`（**非真 sRGB**，且 `check-srgb-oetf.py` 只抓 `srgbEncode` 函数体、**抓不到它**）⇒ 切域前必须先定这个输入域口径。
4. 其余：饱和与色相标定、LUT 域（42 个 `.lut` 在 OETF 后 display 域 ⇒ 不必改）、预设全量重标、输出映射、数值范围与阈值。

**不必改**：LSC（`uLsc` 是无量纲乘性增益）、42 个 LUT 资产（display 域）。

**会同时变红的 A 组守卫**（REPO-AUDIT §⑥）：
`tools/engine-shader-offline.py:886`（`film_d5` CRC32 `0x589f8364`）+ `:409-422`（`FILM_SRC_BASELINE` 分段 crc32）· `check-film-offline.py:134` · `check-toeshoulder.py:5-21`（60 系数逐位）· `check-u2/u3/u4` · `check-halation.py:27` · `check-softglow.py:19` · `check-style-passthrough.py:136` · `check-srgb-oetf.py` · `domain-contract-check.py`；测试侧 `StdColorMatrixTest.kt:56-65`。
⇒ **期 B 开工第一步 = 把这批守卫按"新域下的新基线"重建，并保留旧基线作历史对照**（不许直接改期望值了事）。

## 6. 拍板结果 / 待实测

**已拍板（2026-10-14）**：P1 分期 A → B ✓ · P2 默认引擎切 sigmoid ✓ · P3 照片格式设置项（默认 JPEG / 可选 HEIF）✓ · **R1 交付域与传输函数 = sRGB 显示域 + 10bit（选项 A）** ✓ —— 详见 §1b。

**R1 的落地含义（选项 A）**：
- **取数点 = 现有 `tex_cap_`**（OETF **后**、LUT/颗粒/锐化之后），**不新增取数点**、不动 D4/线性域定义 ⇒ 与期 B 完全解耦。
- `colr/nclx` = **primaries 1（BT.709/sRGB）/ transfer 13（IEC 61966-2-1 sRGB）/ matrix 1（BT.709）/ full_range 1**；`pixi` = 3×10bit。
- 理由：① 成片本来就是显示域，10bit 的价值在**避免 8bit 量化损失**（高光肩部/暗部层次），不是交付线性中间数据；② 下游解释风险是**唯一用代码消不掉**的风险（Android 相册/微信/Windows 三方），A 直接绕开；③ 改动最小（只提位深）。
- **线性档（primaries 9 / transfer 8）不进本期**：它需要在 OETF **前**新增取数点并保留 16F 精度，收益（后期可重新调色）对"交付成片"的诉求不成立；`HeifContainerWriter` 的 CICP 保持参数化，将来要加只是换参数。

**待真机实测（期 A gate）**：§4.1 的三个问题（Q1/Q2/Q3 已由 2026-10-14 探针实测通过，见 §2.7）。

**其他未确认项**（三份报告各自的 ⑦ 节，共 25 条）：`pow(2.2)` 是债还是刻意 · 42 个 `.lut` 是否携带域元数据 · `FrameRenderer.cpp:7868 tex_local_=GL_RGBA8` 是否为更早的隐藏降位点 · `computeXyzD50ToGamut` 是否 Bradford · vivo HEIC 静态照片是否真为 10bit 等。
