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

## 6b. ⛔ 事故记录（2026-10-14，0.11.34，用户真实照片损坏 2 张）

**现象**：切到 HEIF 后再拍照，产出仍是 `.jpg`，但**画面霓虹伪彩 + 边缘浮雕、文件 37MB / 43MB**（同场景正常 6.4MB）。**两张照片不可恢复**（已用两种通道序尝试反解，均为噪声）。

**根因方向（架构师复核）**：**10bit packed 缓冲被当成 8bit `ARGB_8888` 解释**。两个 4 字节格式的**长度完全相同**（3072×4080×4 = 50135040）⇒ **任何长度/尺寸校验都抓不到**；数据流的关键一跳是 `native readback ByteArray → Bitmap(ARGB_8888) → nativeEncodeJpeg444(bitmap)`（`JpegEncoder.cpp:53`、`CameraController.kt:3727`）。

**不可恢复的原因**（写下来免得再试）：`GL_RGB10_A2` 的 4 字节布局是**跨通道位打包**（`R[7:0] | G[5:0]<<2|R[9:8] | B[3:0]<<4|G[9:6] | A[1:0]<<6|B[9:4]`），JPEG 只有 3 通道 ⇒ **第 4 字节（含 B 高 6 位）永久丢失**；且 JPEG 有损 + RGB↔YCbCr 往返的 ±1 LSB 会跨通道放大。

**已暴露的三处防线缺口（修复必须覆盖）**：
1. **格式靠长度推断** ⇒ 必须让交付缓冲带**显式格式**，在 `ByteArray → Bitmap` 一跳**断言**（8bit 档才允许 `ARGB_8888` 解释）。
2. **自检只做在 HEIF 路径** ⇒ 事故全程**没有任何 `photo.heif_*` 事件**（它是 JPEG 路径在交付错数据）；8bit 交付路径也必须有一条最小自检/断言并留日志。
3. ~~**根因链仍有一环未闭合**~~ **已闭合（FIX1，同日）**：`CameraController.kt:3005-3014` 的 **MFNR burst 落盘回调硬编码 `queueJpegSave`**，绕过同一下发点（`:3004`）冻结的交付档 ⇒ native 按 `GL_RGB10_A2` 读回、Kotlin 按 `ARGB_8888` 错读。**决定性证据两条**：① `heif10.selfcheck` 只在 `ten_bit=true` 的提交路径被调用 ⇒ 该张下发时确实是 10bit，而落盘无 `photo.heif_queued` ⇒ 只有 burst 路径能同时满足（`:3014` 是全文件唯一无条件 `queueJpegSave` 的成片落盘点）；② **位打包的物理证据** —— 按 `2_10_10_10_REV` 布局，废片的 G 通道应呈 4 段同构、B 通道 16 段同构，实测段间相关 **G=0.961/0.945、B=0.9977/0.9983**（R 无结构 0.058/−0.186），正常片全部 ~0。

**修复形态（FIX1）**：删成员 `stillTenBit`（会被渲染期间的第二张覆盖 —— 与 `stillRot/stillAspect` 同型事故）⇒ 三处下发点各自 `val tenBit = tenBitForCapture()` 随回调传参；**唯一分派点 `deliverStill(bytes,w,h,snap,tenBit)`**（单帧 + burst 四条收尾全经它）+ 无条件留痕 `photo.deliver {"bits":8|10}`；**`DeliverFormat`（RGBA8 / RGB10A2_PACKED）+ `fmt` 参数故意无默认值**（编译期强制）+ 进 `Bitmap` 前断言（不匹配 ⇒ `photo.deliver_format_mismatch` + 先降位再落盘，失败而非静默且不丢片）；native 不改，改为离线机械守卫（第 ⑨ 组）。变异检验：M1/M3/M4 分别红 2/1/3 条。

**真机判据（修复后必须能判定）**：`切 HEIF → 切回 JPEG → 拍照` 必须与 `从未切过 HEIF 的 JPEG` 等价（同场景同参数）。

## 6c. ✅ 期 A 里程碑：10bit HEIF 端到端打通（2026-10-15，0.11.37）

架构师亲自操作（adb 装包 → 快门 → `adb pull` → **独立解析，不信日志**）：

| 项 | 实测 |
|---|---|
| 文件 | `Pictures/FilmKit/FILMKIT_20261015-081325.heic`（164732 B） |
| 结构 | `ftyp(heic) + meta(hdlr/pitm/iinf/iloc/iprp(ipco(hvcC/ispe/pixi/colr)/ipma)) + mdat` |
| `hvcC` | `len=105 ver=1 profile_idc=2`(Main10) `level=183 chromaFormat=1 bdlMinus8=2 bdcMinus8=2 lengthSize=3 numArrays=3` ⇒ **真 10bit** |
| `pixi` | 3 通道 × **10bit** |
| `colr/nclx` | **primaries 1 / transfer 13 / matrix 1 / full_range 1** = R1 拍板口径 |
| macOS 原生 | `3072×4080 / bitsPerSample 10 / heic`，**强制解码成功且颜色正常**（判据 F 的 macOS 端 ✅） |
| 链路日志 | `photo.heif_csd {csdInLen:79 → csdOutLen:105, converted:true, frameMode:"annexb", ok:true}`、`photo.heif_saved {bits:10}` |

**写死的认知**：编码器 `csd-0` 是 **Annex-B**（`00 00 00 01` + VPS/SPS/PPS），**不是** hvcC；"实测 106 B hvcC"是 `MediaMuxer` 产出的 MP4 里的转换产物。走 muxer 的两条路（探针三臂 / 8bit 回退档）由 muxer 免费做这步，**自研容器必须自己转**（`HevcAnnexB`：切分 + H.265 SPS 完整语法解析 + hvcC 组装 + 帧长度前缀化）。

**期 A 仍待办**：① 判据 F 的 **Android 相册 / 微信 / Windows** 三端人工看；② 码率标定（10bit 档 161KB、8bit 档 368KB，场景不同不可直接比）；③ 8bit HEIF 与 JPEG 的画质对照。

## 6d. ⚠ 0.11.39 真机复核：HEIC「细节还是不行」的算术根因（2026-10-15 晚，用户报）

用户报（同场景样片 + 日志 `filmkit-logs-20261015-200134`）：

| | JPEG | HEIC(10bit) | 比 |
|---|---|---|---|
| 文件 | `FILMKIT_20261015-200100.jpg` = 6,912,525 B | `FILMKIT_20261015-200054.heic` = 392,100 B | **1/17.6** |
| 影调（亮度分位数） | p10 35.3 / p50 144.3 / p90 217.3 | p10 33.3 / p50 146.0 / p90 216.0 | **无偏**（≤2 LSB） |
| 高频（拉普拉斯能量比） | 3.045 | 0.987 | **0.324** ← 用户说的"细节不行" |

分块比值 p10 0.207 / p50 0.310 / p90 0.423 = **全画面均匀损失**（不是局部）。且对照 8bit 档（198 KB，高频 0.30）
⇒ **体积翻倍只换来 0.30→0.32** ⇒ 瓶颈不是"编码器抠门"，而是**预算本身被锁死**。

**根因（算术链，日志字段逐个对上）**：

```
photo.heif_encoded {"bitrateMode":"cq","qp":-1,"bitrate":100000000,
                    "verdict":"TOO_SMALL","tries":2,
                    "targetMin":2036736,"targetMax":3055104}     ← 目标 2.0~3.0 MB，实际 392 KB
```

1. `KEY_BIT_RATE` 单位是 **bit / 秒**，而静态图只编 **1 帧** ⇒ 单帧预算 = `bitrate / frame-rate`；
2. `FRAME_RATE` 当时照抄视频口径 = **30** ⇒ 想要 2.45 MB/帧就得报 **611 Mbps**；
3. 而真机 `c2.mtk.hevc.encoder` 声明 `bitrateRange.upper` = **100 Mbps** ⇒ `bitrateFor` 把 611 夹到 100
   ⇒ 单帧预算 = 100/30 = **3.33 Mbit = 416 KB**；
4. 实测 392 KB = 该预算的 **94%**（吻合）⇒ 编码器确实按这个预算编 ⇒ 高频被砍到 32%。

⇒ **不是格式不行、也不是编码器不行，是码率报错了 30 倍**。（同一个 `FRAME_RATE=30` 也解释了 8bit 档的 198 KB。）

**0.11.40 修复**（`filmkit-0.11.40-HEIF-DETAIL.apk`，389）：

- **`HeifQuality.FRAME_RATE` 30 → 1**（`Hevc10Encoder` / `Heif8Encoder` 两处同源引用）：静态图 1 帧的**正确语义**；
  要报的码率降到 **20.4 Mbps**，**低于**上限 ⇒ 不再被夹 ⇒ 单帧预算拿到 2.45 MB。
- **QP 键换官方**：`video-qp-i-min` / `video-qp-i-max`（`javap android-36` 实测存在）替代 MTK vendor 键
  —— 后者真机实测**失败**（设了连 `csd-0` 都拿不到：`photo.heif_csd {len:0, ok:false}`）。
  静态图 all-intra ⇒ min==max==QP 即**恒定质量**。
- **诊断补齐**（这两条是"下次不用再猜"的设施）：
  · `photo.heif_try` —— **每一路**都留一条（0.11.39 只剩最后一路，"哪条路真的驱动质量"只能反推）；
  · `photo.heif_enc_caps` —— 编码器**自述能力**：`bitrateMax` / `fpsMin,fpsMax` /
    **`cqSupported`**（`isBitrateModeSupported`）/ `qpMin,qpMax`。一条日志即可判定
    "CQ 模式到底被不被支持"和"`FRAME_RATE=1` 是否落在编码器接受范围（本改动唯一的真机风险）"。
- 未达标时的 `verdict=TOO_SMALL` 仍升级成 WARN（**不许静默压过头**，0.11.39 就是这么被发现的）。

**待真机验证（本改动尚未上机）**：一张 HEIF + 一张 JPEG 对照 ⇒ 期望 `photo.heif_try` 里有一路
`verdict=OK`、`photo.heif_encoded.bytes` 落在 2.0~3.1 MB、高频 ≥ JPEG 的 0.7。
⚠ 诚实边界：`FRAME_RATE=1` 是**算术推断 + 语义正确**（不是实测确证）；同时换 QP 键覆盖了
"CQ 生效但按默认 QP 编"这一竞争解释 —— 两条路一起上，一轮真机即可定案。

## 6e. ⛔ 0.11.40 / 0.11.41 真机：**码率这条线走死了**（2026-10-15 晚）

### 编码器自述能力（`photo.heif_enc_caps`，0.11.40 起）

| 字段 | 实测 | 含义 |
|---|---|---|
| `codec` / `hw` | `c2.mtk.hevc.encoder` / true | 硬编 |
| `bitrateMax` | **100,000,000** | 确证 6d 的反推（上轮是从 `bitrate:1e8` 倒推的） |
| `fpsMin` / `fpsMax` | 0.0016 / 79.4 | ⇒ `FRAME_RATE=1` **合法**（6d 唯一的真机风险排除） |
| `cqSupported` / `cbrSupported` / `vbrSupported` | true / true / **true** | 三种 `bitrate-mode` 都被接受 |
| `qpMin` / `qpMax` | 0 / 100 | `EncoderCapabilities.qualityRange`（**不是** HEVC QP 0..51） |

### 三路实测（`photo.heif_try`，同场景 12MP 10bit）

| 版本 | 路 | 键与预算 | 产出 | 判定 |
|---|---|---|---|---|
| 0.11.40 | `qp` | CQ + 官方 `video-qp-i-min/max`=16 | **失败**（拿不到 `csd-0`） | 键不被接受 |
| 0.11.40 | `cq` | CQ + `quality`=100 + 20.4 Mbps | 635 KB | 预算的 26% |
| 0.11.40 | `cbr` | CBR + 20.4 Mbps | 198 KB | 更小 |
| 0.11.41 | `vbr` | VBR + 20.4 Mbps | 219 KB | — |
| 0.11.41 | `vbr-hi` | VBR + **100 Mbps**（给满） | **187 KB** | **预算 ×5 反而更小** |
| 0.11.41 | `cq` | CQ + `quality`=100 + 20.4 Mbps | **649 KB** | 三者中最大 |

⇒ **结论（硬结论）**：`bitrate-mode` 三种模式都被平台声明支持，但**产出与 `KEY_BIT_RATE` 无关**
（20.4 Mbps 与 100 Mbps 给出 219 / 187 KB）⇒ **这颗编码器不读码率键**。
所以 6d 的 `FRAME_RATE=1` 只解决了"被夹"（392→649 KB），**没有解决质量**。

### 质量侧量化（`202534.heic` / `203557.heic` vs 同场景 JPEG）

| 指标 | 值 | 含义 |
|---|---|---|
| 体积 | 649 KB vs 6.89 MB（1/10.6） | — |
| 影调 p50 | 86.0 vs 87.3 | **无偏** |
| 像素级高频 | **0.378** | 颗粒被抹 |
| 强边缘（梯度 p99） | 0.904 | **结构几乎无损** |
| 平坦区高频 / 细节区高频 | 0.182 / 0.438 | 损失集中在"像素级颗粒" |

对照图：`test_temp/_diag/颗粒损失-左JPEG-右HEIC.png`（左有细密颗粒，右被整片磨平）。
⇒ **损失形态 = 编码器把高频整片平滑掉**（典型视频编码器行为），不是结构丢失、不是色彩问题。

### 0.11.42（诊断轮，`filmkit-0.11.42-HEIF-QP-DIAG.apk` / 391）

不再在码率上做文章，改为把**最后两个旋钮**问清楚：

1. 路 ① `qp`：官方 QP 键（i-min / i-max / average **三键同值**）换到 **VBR** 下重试 ——
   0.11.40 在 CQ 下失败；且**失败原因现在会进 `photo.heif_try_failed`**（0.11.41 新增设施）。
2. 路 ② `cq-low`：`quality`=**0** —— 与路 ③ 的 100 构成对照：体积相同 ⇒ `quality` 键也无效。
3. 路 ③ `cq`：`quality`=100（当前已知最优 649 KB，兜底）。
4. **`photo.heif_out_format`：dump `outputFormat` 全部键**（含 vendor 私有键）——
   MTK 会**回显**它认的键（上轮见过 `vendor.mtk.ext.venc.visual.quant`），
   把"有哪些旋钮"列出来再决定拧哪个（回显本身就是判据）。

### 如果 QP 与 vendor 键都不行的备选路线（**待用户拍板**）

| 路线 | 内容 | 代价 |
|---|---|---|
| **a. 8bit HEIF + 软件编码器** | `c2.android.hevc.encoder`（探针实测：只有 Main/MainStill，**无 Main10** ⇒ 只能 8bit）按 QP/码率工作，质量可控 | **丢 10bit**；12MP 耗时要实测（可能数十秒 ⇒ 只能做成"质量档"选项） |
| b. HEIF 定位成小体积档 | 交付用 JPEG 4:4:4 q100（6.9 MB，质量已够），HEIF 只作分享/省空间 | 用户可能不接受 |
| c. 继续挖 vendor 键 | 0.11.42 的 `heif_out_format` 出来后按回显的键逐个试 | 轮次成本；可能无质量键 |

## 6f. ✅ 找到真正的质量旋钮：官方 QP 键 + **VBR**（2026-10-15 晚，0.11.42 → 0.11.43）

0.11.42 的诊断轮一次问清了三件事（`photo.heif_try` + `photo.heif_out_format`）：

| 问题 | 实测 | 结论 |
|---|---|---|
| QP 键为什么在 0.11.40 失败？ | **`video-qp-i-min/max` + VBR 下完全正常**（QP16 ⇒ 851 KB、QP12 ⇒ 1,653 KB） | 0.11.40 的失败是**与 CQ 模式冲突**，不是键本身不行 |
| `quality` 键有没有用？ | `quality=0` ⇒ **8 KB**；`quality=100` ⇒ 649 KB | **有效但天花板低**（100 只有 649 KB） |
| 有没有 vendor 质量键？ | 见下 | 都是 `-1`（未设），唯一非 -1 是 `luma-aq-enable=1`（AQ 开着） |

**`outputFormat` 回显的 vendor 键清单**（0.11.42 起每路一条 `photo.heif_out_format`，备查）：

```
vendor.mtk.ext.venc.visual.quant = -1          vendor.mtk.ext.venc.visual.bfrm-quant = -1
vendor.mtk.ext.venc.visual.pfrm-quant = -1     vendor.mtk.ext.venc.visual.rd = -1
vendor.mtk.ext.venc.visual.aqoffset-reduction=-1   vendor.mtk.ext.venc.visual.luma-aq-enable = 1
vendor.mtk.ext.venc.visual.luma-aq-10bit-enhance=-1
latency = 3   priority = 0   intra-refresh-period = 0   video-qp-average = 0（回显 0，未生效）
```

**质量改善（同场景一手，`205420.heic` 1.65 MB vs `205406.jpg` 6.86 MB）**：

| 指标 | 0.11.41（649 KB） | **0.11.42（1.65 MB，QP=12）** |
|---|---|---|
| 全局高频比 | 0.378 | **0.594** |
| 最平坦 25% 块 | 0.182 | **0.375** |
| 最细节 25% 块 | 0.438 | **0.716** |
| 强边缘（梯度 p99） | 0.904 | 0.943 |
| 影调 p50 | 无偏 | 无偏（81 vs 80） |

**QP–体积标定**（真机两点，每降 4 QP 体积约 ×1.94）⇒ 目标区间中值 2.45 MB 对应 **QP ≈ 10**。

**0.11.43（`filmkit-0.11.43-HEIF-QP10.apk` / 392）**：
- `QP_DEFAULT` 16 → **10**（真机外推值）、`QP_STEP` 4 → **2**（细收敛）、`MAX_QP_TRIES` 2（qp 路两次）
- 三路精简为**两路**：`qp`（VBR + 官方 QP 键，唯一强旋钮）→ `cq`（`quality`=100，兜底：
  非 MTK 平台 QP 键静默失效时用；在 MTK 上永远选不中，因为它更小）
- 删 `cq-low` 诊断路与 `CQ_QUALITY_LOW`（诊断已完成，不留死代码）

**待真机验证**：QP=10 是否把体积送进 2.04~3.06 MB 且高频继续上升（期望 ≥0.65）。
若 QP=10 落在区间外，自校正会走 QP=8 或 12（各一次）—— 反正每次拍照最多 2 次编码。

## 7. 待拍板 / 待实测

**已拍板（2026-10-14）**：P1 分期 A → B ✓ · P2 默认引擎切 sigmoid ✓ · P3 照片格式设置项（默认 JPEG / 可选 HEIF）✓ · **R1 交付域与传输函数 = sRGB 显示域 + 10bit（选项 A）** ✓ —— 详见 §1b。

**R1 的落地含义（选项 A）**：
- **取数点 = 现有 `tex_cap_`**（OETF **后**、LUT/颗粒/锐化之后），**不新增取数点**、不动 D4/线性域定义 ⇒ 与期 B 完全解耦。
- `colr/nclx` = **primaries 1（BT.709/sRGB）/ transfer 13（IEC 61966-2-1 sRGB）/ matrix 1（BT.709）/ full_range 1**；`pixi` = 3×10bit。
- 理由：① 成片本来就是显示域，10bit 的价值在**避免 8bit 量化损失**（高光肩部/暗部层次），不是交付线性中间数据；② 下游解释风险是**唯一用代码消不掉**的风险（Android 相册/微信/Windows 三方），A 直接绕开；③ 改动最小（只提位深）。
- **线性档（primaries 9 / transfer 8）不进本期**：它需要在 OETF **前**新增取数点并保留 16F 精度，收益（后期可重新调色）对"交付成片"的诉求不成立；`HeifContainerWriter` 的 CICP 保持参数化，将来要加只是换参数。

**待真机实测（期 A gate）**：§4.1 的三个问题（Q1/Q2/Q3 已由 2026-10-14 探针实测通过，见 §2.7）。

## 6g. ✅ 定稿：QP = 4（画质饱和点）+ 质量优先口径收口（2026-10-15 深夜，0.11.44 → 0.11.46）

### 完整真机曲线（`photo.heif_try`，X300 `c2.mtk.hevc.encoder` + VBR + 官方 QP 键）

| QP | 16 | 12 | 10 | **4** | **0** |
|---|---|---|---|---|---|
| 体积 | 0.85 MB | 1.65 MB | 2.05 MB | **3.13 MB**（iso708）/ **4.34 MB**（iso1191） | 4.62 MB / **5.92 MB** |
| 全局高频（vs 同场景 JPEG） | 0.378 | 0.594 | 0.616 | — | — |

### 决定终值的 A/B（0.11.45：**同一次拍照**两档同时落盘）

`…213046_qp4.heic`（4.34 MB）↔ `…213046_qp0.heic`（5.92 MB），同场景逐像素：

| 指标 | QP=4 | QP=0 | 差 |
|---|---|---|---|
| 全局高频 | 3.1873 | 3.2226 | **+1.1%** |
| 最平坦 25% 块 | 2.057 | 2.094 | +1.8% |
| **逐像素绝对差** | — | — | **p50 0.33 / p90 0.67 / p99 1.00 / max 2.3 LSB** |

⇒ **多付 36% 体积换来"p99 差 1 个 LSB"** ⇒ **QP=4 就是画质饱和点**，QP=0 是纯浪费。
差异可视化：`test_temp/_diag/QP0-vs-QP4-左QP4-中QP0-右差异x20.png`。

### 0.11.46 定稿改动（`filmkit-0.11.46-HEIF-FINAL.apk` / 395）

1. **`QP_DEFAULT` = 4**（终值）；**`QP_MIN` = 4**（下界 = 终值，再往下零收益）。
2. **`MAX_QP_TRIES` = 1 ⇒ 禁自校正**。这一条是**质量优先的关键**：QP 固定在饱和点后，
   同一个 QP 在不同场景的产出会在 3.13~4.34 MB 间浮动（**体积是结果、不是目标**）；
   若还让 `verdict` 驱动自校正，`TOO_BIG` 会把 QP 往高推 = **为了体积主动降质量**。
   现在区间只作日志警示（`photo.heif_try` 的 `verdict`）。
3. **`TARGET_MAX_RATIO` 0.60 → 0.70**（理由同上：不许把"已饱和"的片子判成过大）。
4. `MAX_TOTAL_TRIES` 3 → 2：定稿后只有一路 ⇒ **正常拍照恒 1 次编码（1.2~1.5 s）**，
   上限只为"该路被编码器拒绝时换 cq 兜底路"。
5. **删掉 0.11.45 的对照档落盘**（诊断完成；它每次拍照会多写一份同样大小的文件）。
   底层的 `onImage` / `onAttempt` 回调保留（通用能力，换平台重新标定时还要用）。
6. `JPEG_REFERENCE_BPP` 3.25 → **4.2**（三张同场景 JPEG 实测 5.11 / 6.08 / 6.86 MB
   ⇒ 3.26 / 3.88 / 4.38 bpp；旧值取自最小的那张 ⇒ 区间整体偏低）。区间 = **2.63 ~ 4.61 MB**。

### 四轮真机得到的**不可动摇的结论**（换平台时按此顺序排查）

1. **编码器不读 `KEY_BIT_RATE`**（20.4 Mbps 与 100 Mbps 给 219 / 187 KB）⇒ 码率不是旋钮；
2. **`bitrate-mode` 必须是 VBR**：官方 QP 键在 CQ 下直接被拒（拿不到 `csd-0`）；
3. **官方 QP 键是唯一的强质量旋钮**（`video-qp-i-min` / `-max`；`video-qp-average` 回显 0 = 未生效）；
4. **`quality` 键有效但天花板低**（0 ⇒ 8 KB、100 ⇒ 649 KB）；
5. **vendor 键全是 -1**（`vendor.mtk.ext.venc.visual.*`），没有隐藏的质量旋钮；
6. **画质在 QP≈4 饱和** —— 继续降只买噪声，不买细节。

### 仍未做（期 A 遗留）

- ~~判据 F 三端人工确认~~ ⇒ **2026-10-15 全部通过**：macOS ✅ · 微信 ✅ · 系统相册 ✅ ·
  Windows ✅（用户实测）。
- **4:2:0 色度是硬件硬限**（P010；探针实测 `p210: false`，无 4:2:2 / 4:4:4 10bit 路径）
  ⇒ 色度细节上 HEIF 档不如 JPEG 4:4:4，这是**格式 + 硬件**的取舍，不是 bug。

### 期 A 收官状态（2026-10-15）

**功能**：设置 →「拍摄」→「照片格式」（默认 JPEG / 可选 HEIF 10bit）⇒ 已定稿
（`filmkit-0.11.46-HEIF-FINAL.apk` / 395）。链路 = 引擎 10bit readback（`GL_RGB10_A2` +
`GL_UNSIGNED_INT_2_10_10_10_REV`）→ CPU BT.709 全范围 4:2:0 → P010 → 硬件 HEVC Main10
（VBR + 官方 QP 键，QP=4）→ 自研 HEIF 容器（`colr/nclx = 1/13/1/1`，`pixi` 3×10bit）→
落盘 + 读头自检；三级回退 `10bit → 8bit HEIF → JPEG`。

**下一期**：期 B —— 公共工作空间切 **Rec.2020 线性**（§5 的 8 面爆炸半径 + A 组守卫会整体变红，
必须单独一期）。

**下下一期**：工程师插槽**阶段 4 = ACES 预留**（`docs/engine-slots-design.md` §8, §9）——
前置条件正是期 B 的 `workingColorSpace` 非 D4，所以顺序天然衔接，**域空间不是"另一件事"，它就是阶段 4 的技术门**。
- §9.1 域挂点：把 `ComputeXyzD50ToGamut` 对应的 `XYZ(D50) → 线性 sRGB(D65)` 换成 `→ AP0(D60)`；其他不动。
- §9.2 有一项必须**先拍板**：`display_peak_nits`（绝对亮度锚 + "0EV/中灰 ↔ nits"口径）—— 没它 tone scale 无法参数化。
- §9.3 资产：hue 表（≈6 KB RGBA32F）+ cusp 表；§9.4 判据沿用 §7 五条 + 高饱和色 hue 偏移（ΔE2000/Δh）。
- **上不上 ACES 先看数据，别默认上**：§9.4 + 着色判据里，统一口径下 ACES 的层次只比 sigmoid 优 0.25~0.30 EV（三架构都只用到 8bit 上界的 32~41%），**唯一可能拉开差距的是高饱和色 hue 偏移**；所以域空间完成 + 阶段 4 技术门跑通后，用高饱和色样张实测 ACES 是否有 visible gain，再决定。

**域空间完成后的伴随重估**（用户 2026-10-14 明示"预设先不动，等做完了再重新评估"）：
- **内置预设重标**：§5 的 8 面之一就是"预设重 tagged"；域变了，预设标定基础跟着变 ⇒ 借此重估内置预设策略。
- **filmic 去留（债到期）**：2026-10-13 记录"上 10bit 本身就是要求换掉 filmic（10bit 救不了 filmic 连续不可逆的平顶）"；10bit 期 A 已交付，这条债现在到期 ⇒ 域空间完成后重评 filmic 是否保留（用户 10-14 原话"后期可能想删"）。

**其他未确认项**（三份报告各自的 ⑦ 节，共 25 条）：`pow(2.2)` 是债还是刻意 · 42 个 `.lut` 是否携带域元数据 · `FrameRenderer.cpp:7868 tex_local_=GL_RGBA8` 是否为更早的隐藏降位点 · `computeXyzD50ToGamut` 是否 Bradford · vivo HEIC 静态照片是否真为 10bit 等。
