# 下一期标准做法调研：10bit HEIF 交付链 + 公共工作空间切 Rec.2020 线性

> 卡片类型：**标准做法调研**（工业界 / 平台规范 / 成熟开源实现），**只写报告，不改源码、不构建、不跑 App**。
> 姊妹卡（并行，同一期）：`docs/reports/r-next-10bit-heif-REPO-AUDIT.md`（本仓爆炸半径）、`r-next-10bit-heif-PC-EVIDENCE.md`（PC/vivo 一手取证）。
> 对标口径：**标准做法**（ISO/IEC、平台官方文档、成熟开源实现源码）；PhotonCamera 仅为参照物，不是目标。
> 证据强度标注：**【一手源码】** / **【官方文档】** / **【规范】** / **【二手博客】** / **【项目内既有结论】**。
> 调研日期：2026-10-14。

---

## ① 结论摘要

1. **Jetpack 的 `HeifWriter` 用不了**：10bit 支持被硬编码 `false`（`useBitDepth10`/`highBitDepthEnabled`），Builder 里**没有任何色彩/ICC API**，缓冲输入只收 `YUV_420_888`（§2.2，源码行号齐全）。
2. **10bit HEIF 只有一条路**：`MediaCodec` HEVC **Main10** + `MediaMuxer(MUXER_OUTPUT_HEIF)`，色彩走 `MediaFormat` 的 `color-standard`/`color-transfer`/`color-range`（API 24+）+ `KEY_PROFILE`/`KEY_LEVEL`（§2.3/2.4）。
3. **色彩标签的标准做法是 `colr/nclx`（CICP）**：BT.2020 线性 = `primaries=9` + `transfer=8`，是规范内合法组合；HDR 惯例则是 `transfer=16`(PQ)/`18`(HLG)（§2.5）。
4. **不写标签 ≠ 中性**：成熟解码端（libheif）会把未定义字段**逐项替换为 sRGB/BT.709/BT.601 默认**（`nclx.cc:360-373`）⇒ 线性数据被当 sRGB 解释会系统性偏亮偏灰。**必须写标签，且必须实测下游是否认**（§2.6/§⑦）。
5. **Ultra HDR 与 10bit HEIF 生态位不同，不是替代关系**：Ultra HDR = 8bit JPEG + gain map（向后兼容优先，Android 15 起同时写 ISO 21496-1）；10bit HEIF = 原生 10bit 宽色域。质量优先 + 10bit 目标 ⇒ **HEIF 是主线**，Ultra HDR 属另一期可选分支（§3）。
6. **BT.2020 线性作为工作空间有直接的一手依据**：darktable **默认工作空间就是 linear Rec2020**（`colorin.c:87`、`colorspaces.c:678-689`）；ACES 选 AP1 的官方理由是"工作空间原色要能包住交付色域"（`acescg.md:18`）（§4.1）。
7. **BT.2020 的工作点是 D65，与 sRGB/DCI-P3 相同** ⇒ 换域**不需要**白点适配链（这是相对 ProPhoto D50 的决定性优势）；矩阵按 **SMPTE RP 177** 由原色+白点推导（`acescg.md:91-96`）（§4.2）。
8. **换工作空间牵动 8 个面**（CCM / gamut 收口 / 饱和与色相标定 / LUT 域 / 预设 / 输出映射 / 数值范围与统计阈值 / 零回归基线）——每一项都是"域语义变化"导致，必须逐项显式处理（§4.3）。
9. **10bit 只救量化不可逆，救不了连续不可逆**：sigmoid/ACES 的损失来自 8bit 量化（10bit 可救）；filmic 白点后的 plateau 是映射本身不可逆（任何位深都救不了）——项目 17 号报告的结论在标准依据上成立（§5.2）。
10. **dither 强度必须按输出位深重标**（darktable 一手：`levels = MAX(16, MIN(15*bds+1, 256))`，`dither.c:326`）；**本项目现状是按 8bit 标定的**（`FrameRenderer.cpp:4749`）⇒ 本期必查（§5.3、判据 C2）。
11. **判据建议 6 组（A–F）**：A 容器/标签（可 100% 自动，最先做）、B 10bit vs 8bit 层次保留（沿用"可分辨跨度"，单位换 10bit LSB）、C banding/dither（能直接抓出未重标）、D 色域边界可分辨性、E 同显示域对照的零回归、F 兼容性矩阵（§⑥）。
12. **三个"不实测就不能开工"的问题**：① X300 是否有可用的 still-image HEVC Main10 编码器（P010 输入）；② `color-*` 键是否真的落进 HEIF `colr` box；③ 下游（相册/微信/PC）如何解释 `transfer=8`（§⑦）。

---

## ② Android 平台 10bit HEIF 编码能力与色彩标签

### 2.1 平台侧只有两条路（结论先行）

| 路径 | 10bit 输入 | 色彩标签 | 对本期的可用性 |
|---|---|---|---|
| `androidx.heifwriter.HeifWriter`（Jetpack） | ❌ 硬编码 false | ❌ Builder 无任何色彩/ICC API | **不可用**（见 2.2） |
| `MediaMuxer(MUXER_OUTPUT_HEIF)` + 自建 `MediaCodec` HEVC 编码器 | ✅ 取决于编码器（P010 / RGBA_1010102） | ✅ 走 `MediaFormat` 的 `color-*` 键 | **唯一可行**（见 2.3/2.4） |

### 2.2 `androidx.heifwriter` 的能力边界（一手源码，逐条行号）

源码基准：`androidx-main`（2026-10-14 抓取）。
`https://raw.githubusercontent.com/androidx/androidx/androidx-main/heifwriter/heifwriter/src/main/java/androidx/heifwriter/HeifWriter.java`
`https://raw.githubusercontent.com/androidx/androidx/androidx-main/heifwriter/heifwriter/src/main/java/androidx/heifwriter/HeifEncoder.java`

| 能力问题 | 一手事实 | 落点 |
|---|---|---|
| 能否 10bit 输入 | **不能**。构造函数把 `highBitDepthEnabled` 硬编码 `false`；`HeifEncoder` 同样两处 `useBitDepth10=false` | `HeifWriter.java:410`；`HeifEncoder.java:72-73`、`:75` |
| 支持哪些 `MediaFormat` 键 | 只**读出**并改写输出格式：`KEY_MIME`（写成 `MIMETYPE_IMAGE_ANDROID_HEIC`）、`KEY_WIDTH/HEIGHT`、`KEY_TILE_WIDTH/HEIGHT`、`KEY_GRID_ROWS/COLUMNS`。**从不设置** `KEY_COLOR_STANDARD` / `KEY_COLOR_TRANSFER` / `KEY_COLOR_RANGE` / `KEY_PROFILE` / `KEY_LEVEL` | `HeifEncoder.java:89-99` |
| 是否支持 ICC profile 写入 | **没有这个 API**。`HeifWriter.Builder` 全部公开方法 = `setRotation` / `setGridEnabled` / `setQuality` / `setMaxImages` / `setPrimaryIndex` / `setHandler` / `setEncoderPreference`（**无 `setIcCProfile`**） | `HeifWriter.java:182/197/209/228/243/256/271` |
| 输入像素格式 | 缓冲模式**仅 `YUV_420_888`**（8bit 4:2:0）；`Bitmap` 模式内部同样落到 8bit YUV | `HeifWriter.java:303`（doc 原文 "only support YUV_420_888"） |
| 分辨率/切片 | 默认开 grid：切片 `512×512`、编码块 `32`、最大压缩比 `0.25`；>1 张图时按 `KEY_IS_DEFAULT` 标 primary | `HeifEncoder.java:49-53`；`WriterBase.java:401-419` |

> **标准做法判断**：`androidx.heifwriter` 的设计目标是"相机连拍的 8bit HEIC 容器"，不是 HDR/10bit 交付工具。本期**不应**试图在它上面打补丁（改不了硬编码的 `false`），直接走 `MediaCodec` 是标准做法，也是唯一做法。

### 2.3 `MediaCodec` 的 HEVC Main10 与 10bit 输入格式

- 10bit profile 常量在 `MediaCodecInfo.CodecProfileLevel`：`HEVCProfileMain10`、`HEVCProfileMain10HDR10`、`HEVCProfileMain10HDR10Plus`（另见 `AV1ProfileMain10` 系列）。【官方文档】<https://developer.android.com/reference/android/media/MediaCodecInfo.CodecProfileLevel>
- 10bit 像素格式在 `MediaCodecInfo.CodecCapabilities`：`COLOR_FormatYUVP010`（"P010 is 10-bit-per component 4:2:0 YCbCr semiplanar format"）、`COLOR_FormatYUVP210`、`RGBA_1010102`。【官方文档】<https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities>
- **能力必须先查再信**：`FEATURE_HdrEditing`（`"hdr-editing"`）与 `FEATURE_HlgEditing`（`"hlg-editing"`）是运行期能力门；文档原文大意为"HDR editing support means that the codec accepts 10-bit HDR input … using an HDR capable profile (and 10-bit HDR input)"、"…supports `RGBA_1010102`"。**静态不能断言 X300 支持，必须 `MediaCodecList` 逐个 encoder 查**（见 §⑦）。同上文档页。
- 宽度对齐：官方明确 `VideoCapabilities.getWidthAlignment()` "This is a power-of-2 value that video width must be a multiple of"。【官方文档】<https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities>

### 2.4 `MediaFormat` 的色彩键（API 24+）

| 键 | 常量值字符串 | 取值 | 出处 |
|---|---|---|---|
| `KEY_COLOR_STANDARD` | `"color-standard"` | `COLOR_STANDARD_UNSPECIFIED`(0) / `BT709` / `BT601_NTSC` / `BT601_PAL` / **`BT2020`** | <https://developer.android.com/reference/android/media/MediaFormat> |
| `KEY_COLOR_TRANSFER` | `"color-transfer"` | `COLOR_TRANSFER_UNSPECIFIED`(0) / **`LINEAR`** / `SDR_VIDEO` / `ST2084`(PQ) / `HLG` | 同上 |
| `KEY_COLOR_RANGE` | `"color-range"` | `COLOR_RANGE_FULL` / `COLOR_RANGE_LIMITED` | 同上 |
| `KEY_PROFILE` / `KEY_LEVEL` | `"profile"` / `"level"` | 值见 `CodecProfileLevel`；**官方原文：profile 只是 hint（编码器可能静默换档），且设 profile 必须同时设 level** | 同上（`KEY_PROFILE` 段） |

> ⚠ 平台只暴露 4 个"标准"取值（BT709/BT601×2/BT2020）与 4 个传递函数取值，**没有"BT.2020 线性"这种组合的直接枚举**——线性是 `COLOR_STANDARD_BT2020` + `COLOR_TRANSFER_LINEAR` 的组合。这对得上 HEIF 侧的 nclx（见 2.5）。

### 2.5 HEIF 侧色彩标签的标准做法：`colr` box / `nclx`(CICP)

HEIF（ISO/IEC 23008-12）的图像色彩信息走 `colr` box；其中 `nclx` 类型承载 CICP 四元组：`colour_primaries` / `transfer_characteristics` / `matrix_coefficients` / `full_range_flag`（CICP = ITU-T H.273 / ISO/IEC 23091-2）。成熟开源实现 libheif 的枚举即该表的一手落地：

`https://raw.githubusercontent.com/strukturag/libheif/master/libheif/api/libheif/heif_color.h`

| 字段 | 本期相关取值 | 出处 |
|---|---|---|
| `colour_primaries` | 1 = BT.709-5；**9 = ITU-R BT.2020-2 / BT.2100-0**；11 = SMPTE RP 431-2（DCI-P3）；12 = SMPTE EG 432-1（Display P3） | `heif_color.h:140-154` |
| `transfer_characteristics` | 1 = BT.709-5；**8 = linear**；13 = IEC 61966-2-1（sRGB）；14/15 = BT.2020 10bit/12bit；**16 = BT.2100-0 PQ**；**18 = BT.2100-0 HLG** | `heif_color.h:156-174` |
| `matrix_coefficients` | 0 = RGB/GBR；1 = BT.709-5；6 = BT.601-6；9/10 = BT.2020 非恒定/恒定亮度；14 = ICtCp | `heif_color.h:177-193` |
| 结构 | `heif_color_profile_nclx { version, color_primaries, transfer_characteristics, matrix_coefficients, full_range_flag, ... }` | `heif_color.h:195-210` |

**由此得到本期两条标签方案（都是规范内的合法组合）**：
1. **BT.2020 + 线性**：`primaries=9, transfer=8`（+ `full_range_flag=1`）。
2. **BT.2020 + PQ/HLG**（HDR10/HLG 交付惯例）：`primaries=9, transfer=16/18`。

libheif 支持 8/9/10/12 bit HEVC 编解码（16bit 明确拒绝）：`README.md:299-304`，<https://raw.githubusercontent.com/strukturag/libheif/master/README.md>。

### 2.6 没有色彩标签时，解码端会怎么解释（一手实现）

libheif 的行为是最能代表"成熟解码端"的一手样本：

- `nclx_profile::set_sRGB_defaults()` → `primaries=1`(BT.709)、`transfer=13`(sRGB)、`matrix=6`(BT.601)、full range：`libheif/nclx.cc:314-322`。
- `nclx_profile::replace_undefined_values_with_sRGB_defaults()` → 未指定（`unspecified=2`）的字段**逐个替换成上述 sRGB/709/601 默认**：`libheif/nclx.cc:360-373`。

⇒ **标准做法层面的含义**：HEIF 里"不写标签" ≠ 中性。解码端会按 **sRGB/BT.709 传递函数**解释像素值；若我们交付的是"线性 BT.2020"，不写标签 = 对方按 sRGB 曲线解释线性数 = **系统性偏亮/偏灰的错误观感**。所以**必须写标签**，且必须实测"写了标签之后，微信/相册/PC 是否真的按标签解释"（§⑦）。

### 2.7 已知坑（平台限制 + 兼容性）

| 坑 | 一手/规范依据 | 本期对策（标准做法） |
|---|---|---|
| 编码器宽高对齐（非 2 的幂、奇数尺寸） | `getWidthAlignment()` 是 2 的幂，宽必须是它的倍数（官方文档，见上）；HEVC 4:2:0 本身要求偶数 | 编码前 pad 到 `max(alignment, 2)` 再交给编码器，**不要**改成"缩放到对齐尺寸"（会破坏交付分辨率） |
| HEIF 分片（grid） | androidx 实现**默认开 grid**，切片 `512×512`（`HeifEncoder.java:49-53`）；libheif 明确"所有 tile 必须同尺寸、同色彩空间、同色度格式、同位深、同分量"（`libheif/api/libheif/heif_tiling.h:102-103`） | 大分辨率交付时用 grid，且 tile 尺寸统一；tile 内容不得跨 tile 依赖（去马赛克/降噪必须在**编码前**完成，编码器不做跨 tile 参考） |
| 10bit 编码器在部分机型上"报了 Main10 但只能录视频" | 静态 profile 列表 ≠ still image 可用；`KEY_PROFILE` 只是 hint | 运行期 `MediaCodecList` 查 `FEATURE_HdrEditing` + `VideoCapabilities.isSizeSupported()` + 实测编码一帧 |
| 社交/相册兼容性未知 | 无公开规范保证 | 真机实测矩阵，见 §⑥ 判据 F |

---

## ③ 交付路线取舍：10bit HEIF vs Ultra HDR（JPEG gainmap）

### 3.1 两条路线各自的规范定义（一手）

**Ultra HDR（JPEG + gain map）**——Android 官方规范 v1.1，<https://developer.android.com/media/platform/hdr-image-format>：
- 容器 = **JPEG 主图（8bit SDR）+ 单色 gain map JPEG（作为 MPF 第二图附加）+ GContainer XMP 元数据**（行 449/502/571/590-599/718）。
- 设计目标明确写"Be backward compatible, so that on naive viewers, the conventional SDR image is displayed"（行 468）。
- 官方建议：**尽可能用单通道 gain map**（行 614-619）；`map_gamma` **建议 1.0**（行 674）；gain map 可降采样到主图 **1/4 宽高（1/16 像素）**（行 725-727）；gain map JPEG 质量建议 **85–90**（行 715-716）。
- **ISO 21496-1 兼容（关键）**：规范含专章 "ISO 21496-1 Compatibility"（行 1073-1100）——"ISO 21496-1 provides an alternative encapsulation"；**"should support encoding and decoding for both Ultra HDR v1 and ISO 21496-1"**；**"the implementation or app should prefer using the ISO 21496-1"**；**Android 15 起同一 JPEG 同时写两套元数据**。
- 开源实现 libultrahdr（Google）：输入 HDR 意图支持 `P010 / rgba1010102 / rgbaf16`，传递函数支持 `HDR_LINEAR / HDR_PQ / HDR_HLG`；<https://raw.githubusercontent.com/google/libultrahdr/main/README.md>（行 3-11、37-47、56）。

**10bit HEIF**——见 §②：`colr/nclx`（primaries=9 BT.2020，transfer=8/16/18）+ HEVC Main10。它不是"元数据增强的 8bit SDR"，而是**原生高位深广色域容器**。

### 3.2 取舍（标准做法层面，不替用户拍板）

| 维度 | Ultra HDR（JPEG+gainmap） | 10bit HEIF | 证据 |
|---|---|---|---|
| 向后兼容 | **极强**：任何 JPEG 解码器都能出 SDR 图（规范即以此为目标） | 弱：不认 HEIC/不支持 10bit 的读图器会失败或按 8bit 误读 | Ultra HDR 规范行 468 |
| 最高画质 | 主图受 **8bit + 4:2:0 JPEG** 限制；gain map 有损且常降采样 1/16 | **10bit 全分辨率**；色度仍 4:2:0（HEVC Main10 常见）但有 4 倍码值 | 规范行 713-727；§② 2.3 |
| 观感上限 | 只有"亮度增益"（gain map 是标量），色域仍以主图色彩空间为界 | 可携带 **BT.2020 宽色域**（primaries=9） | libultrahdr README；libheif `heif_color.h` |
| 实现成本 | 需**额外渲染一版 SDR** 并反算 gain map（两版一致性是质量风险点） | 只需 10bit 输出路径 + 编码器（无第二版渲染），但**无现成 Jetpack 封装** | libultrahdr README 行 35-47 |
| 生态位 | Android 官方给"HDR 照片"定的交付格式（Android 15 起双写 ISO 21496-1） | 广色域/高位深静态图；苹果侧对应 HEIF/Adaptive HDR 一系 | Ultra HDR 规范行 1083-1090 |

**标准做法结论**：两者**不是替代关系**，生态位不同。若本期目标是"质量优先、10bit 全链路、大色域交付"，**10bit HEIF 是主线**；Ultra HDR 是"社交平台 HDR 观感"路线，属于**另一期**的可选交付分支（且它天然要求"再渲染一版 SDR"，与本期"位深/色域升级"目标正交）。

> 备注：vivo/OPPO 的 10bit 或 HDR 交付形态属于**产品选择**而非标准，本卡不给结论；由姊妹卡 `r-next-10bit-heif-PC-EVIDENCE.md`（vivo X300 取证）负责。

---

## ④ 工作空间选 BT.2020 线性的标准依据与牵动面清单

### 4.1 三个成熟实现的"工作空间"选择（一手对照）

| 实现 | 工作空间 | 一手出处 |
|---|---|---|
| **darktable** | **linear Rec2020 RGB**（默认，D65 + Rec2020 原色 + gamma 1.0） | `src/iop/colorin.c:87`（`type_work` 默认 `DT_COLORSPACE_LIN_REC2020`）、`:200`（回退默认同值）；`src/common/colorspaces.c:678-689`（`_colorspaces_create_linear_rec2020_rgb_profile`：`cmsBuildGamma(NULL, 1.0)` + `D65xyY` + `Rec2020_Primaries`） |
| **ACES** | **ACEScg（AP1 原色）** = "A Working Space for CGI Render and Compositing" | `docs/encodings/acescg.md:9`；理由在 `:16`/`:18`：AP0 覆盖全视觉域但 CGI 算法不适用超宽原色；**AP1 "plot slightly outside of the visual gamut - in order to be able to fully encapsulate both the Rec-2020 gamut and the DCI-P3 gamut for a range of white points"** |
| **Blender/AgX** | OCIO `scene_linear: Linear Rec.709`；但 **AgX 的处理基（base primaries）是 Rec.2020** | `release/datafiles/colormanagement/config.ocio:29-30/56-60`；AgX Rec.2020 基见同配置 `:87-92` 与 `AgX_Base_Rec2020.cube` |

**从中能提炼的标准原则（三条）**：
1. **工作空间原色必须 ≥ 交付色域**——ACES 选 AP1 的官方理由就是"要能完全包住 Rec.2020 与 P3"（`acescg.md:18`）。若交付 BT.2020 而工作空间是 sRGB 线性，交付前端必然发生超域裁剪。
2. **工作空间也是可以"大于必要"的**——darktable 用 Rec2020 作为通用工作空间（不是每种交付都重设）；ACES 用 AP1（比 Rec2020 大）。
3. **"处理基"与"场景线性工作空间"可以是两层**——Blender 的场景线性是 Rec.709 线性，而 AgX 的色相旋转/inset 在 Rec.2020 基上做（config.ocio + cube）。**反面含义**：把工作空间从 sRGB 线性换成 BT.2020 线性，等于同时改变了"所有以基为参照的调色参数"的语义，不只是换一个矩阵。

**OCIO 对工作空间角色的权威定义**（"标准做法"的概念基准）：`scene_linear` = "the scene-referred linear-to-light color space, often the same as the reference space"；`rendering` = 用于在场景参考色空间之间转换。
<https://opencolorio.readthedocs.io/en/latest/guides/authoring/overview.html>（抓取文本行 263-294）

### 4.2 BT.2020 线性的具体做法（primaries / 白点 / 转换）

- **原色与白点（官方数据）**：BT.2020 原色红 (0.708, 0.292)、绿 (0.170, 0.797)、蓝 (0.131, 0.046)，白点 **(0.3127, 0.3290) = D65**。【官方文档】`ColorSpace.Named.BT2020`，<https://developer.android.com/reference/android/graphics/ColorSpace.Named>（抓取文本行 2446-2453）。
- **白点不变是选 BT.2020 相对 ProPhoto 的关键优势**：BT.2020 与 sRGB/DCI-P3 同为 **D65** ⇒ 切域**不需要**白点适配（Bradford CAT）链，只需换原色矩阵；ProPhoto RGB 是 D50，会牵动整条白点链。darktable 的做法同样把工作空间与显示/输出 profile 分开、白点各自声明（`colorspaces.c:682-684` 的 `D65xyY`）。
- **矩阵的推导方法（标准做法）**：由原色色度坐标 + 白点，按 **SMPTE RP 177** 的方法求 RGB↔XYZ 的归一化原色矩阵（NPM）及其逆。ACES 官方规范明确用此法并给出 10 位有效数字的传递矩阵：`TRA_1 = NPM⁻¹_AP1 · NPM_AP0`（`acescg.md:91-96`、`:126-130`）。
- **负值/超域值的处理（标准做法）**：ACES 官方明确"理想情况应保留负值；若不可行，**把负值替换为 0 是可接受的**"（`acescg.md:39`）。⇒ 换到更宽的基之后，"域外像素"的判据与收口策略**必须重新定义**（在 sRGB 基下是"域外"的像素，在 BT.2020 基下可能已经域内）。

### 4.3 把工作空间从"sRGB 线性"切到"BT.2020 线性"的牵动面检查清单

> 下列每一项都是"域语义变化"直接导致，不是实现细节。**逐项确认 = 这一期的实际工作量**。

| # | 牵动面 | 为什么必须动 | 标准做法 |
|---|---|---|---|
| 1 | **输入 CCM（sensor→工作空间）** | CCM 的目标基从 sRGB 变成 BT.2020，矩阵**必须重算**（等价于 `M_sRGB→BT2020 · CCM_old`，但应重新做最小二乘/白平衡标定而非硬乘） | darktable 的 `colorin` 模块 = 输入 profile → 工作 profile 的显式转换；ACES 用 NPM 法求矩阵 |
| 2 | **gamut 收口 / 域边界判据** | 原"超出 sRGB 边界即裁剪"的逻辑在更宽的基下语义变化；直接沿用会把 BT.2020 能表达的颜色误裁 | 收口应定义在**交付色域**上（BT.2020），而非工作空间上（同 ACES 的 gamut compression 思路） |
| 3 | **饱和度 / 色相标定** | 同一数值在不同原色域下的感知饱和度不同；AgX/sigmoid 的 `purity` / `inset` / `rotation` / `base_primaries` 是**按基定义的参数**（darktable 甚至把 `working profile` 与 `Rec2020` 做成二选一） | 参数必须按新基**重标**，或显式声明引擎的 base primaries（项目内已记：`docs/research/01-darktable.md:162-189`） |
| 4 | **3D LUT / 查找域** | 显示域 LUT（OETF 之后）不受影响；**线性域 LUT** 若按 sRGB 线性域设计，输入域变了 ⇒ 必须重做或重新解释 | 明确每个 LUT 的"域"（display / scene-linear），不要跨域复用 |
| 5 | **预设（数值即语义）** | 预设里存的是**数值**，数值的含义由域决定；换域 = 所有颜色类预设的行为改变 | 提供"重标/迁移"，并对每个预设跑同图 A/B（不能只跑单元测试） |
| 6 | **输出变换** | 工作域 → 交付（BT.2020）= 恒等（这就是选它的收益）；工作域 → 显示（sRGB / P3）需要 **gamut mapping** | 显示链必须保留到目标色域的映射（不得只 clamp） |
| 7 | **纹理格式与数值范围** | 更宽的基 + 线性光 ⇒ 负值/超白更多；RGBA16F 精度够，但**几何/统计类判据的阈值**（如"域外像素占比"）会变 | 统一在交付域评估质量指标（见 §⑥） |
| 8 | **零回归基线** | 所有以像素值/CRC 为口径的基线全部失效（这是"必须单独一期"的量化理由） | 换域当期**重建基线**，并以"同域对照"方式做 A/B（新旧域各自渲染、再映射到同一显示域比较） |

**Android 平台侧的一个硬事实（影响第 6 项）**：`ColorSpace.Named.BT2020` **不是线性**的，它是 BT.2020 原色 + BT.2020 自己的 OETF（分段 γ2.2）。平台没有"BT.2020 线性"的 Named 空间；要线性必须用 `ColorSpace.Rgb` 自定义（线性 transfer 参数），或走 `MediaFormat` 的 `COLOR_TRANSFER_LINEAR`。【官方文档】<https://developer.android.com/reference/android/graphics/ColorSpace.Named>（抓取文本行 2446-2468）。

---

## ⑤ 位深与影调映射的交互：10bit 能救什么、救不了什么

### 5.1 量化：10bit 买到的是 4 倍码值（可推导事实）

8bit → 10bit 的可分辨级数从 256 变为 1024（**4 倍**）；一位深度的量化步长是 `1/(2^n − 1)`。这不需要外部引用，但**它的收益只有在"曲线本身仍单调"时才兑现**。

### 5.2 已被本项目量化过的结论（项目内一手，标准依据在此之上补强）

| 结论 | 项目内出处 | 标准依据（本卡补） |
|---|---|---|
| 对 **sigmoid / ACES**（线性域严格单调、渐近不发散）：8bit 下的信息损失**只**来自量化 ⇒ 提高位深可救（严格说 10bit 是缩小 4 倍，真正"可逆"要浮点/16bit 中间产物） | `docs/research/17-独立复核-影调架构量化.md:287` | 量化误差 = 步长/2，与位深一一对应 |
| 对 **filmic**：白点之后输出**逐位恒定**（实测 plateau = 4.00 EV），是**连续不可逆**，与位深无关 | `docs/research/17-独立复核-影调架构量化.md:288` | 单调映射的信息丢失是映射本身造成的，任何位深都不恢复（与量化可逆性是两类问题） |
| 正确表述 | `docs/research/17-独立复核-影调架构量化.md:290`：**"sigmoid/ACES 在 8bit 下损失的是量化可逆性（提高位深可救）；filmic 在 plateau 之后损失的是映射可逆性（任何位深都救不了）"** | 同上 |
| 8bit 交付是"白纸够白 vs 白纸之上有层次"的物理上界（`(255−白纸LSB)×0.1 EV`） | `docs/research/17-独立复核-影调架构量化.md:235-252`（8bit 硬上界表） | 上界由码值数决定 |

⇒ **对下一期的直接含义**：上 10bit 让"量化可逆"的判据口径必须同步改成"以 10bit 码值为单位"（否则拿 8bit 阈值去测 10bit 链，会把 4 倍余量当成 0 收益）。

### 5.3 dithering / banding 的标准做法

- **dither 的必要性与强度标定**：量化到有限级数会产生轮廓（banding），dither（Floyd–Steinberg 误差扩散等）是教科书级对策；darktable 的 `dither` 模块描述即 "reduce banding and posterization effects in output"（`src/iop/dither.c:114`），其**自动模式把强度与输出位深绑定**：`levels = MAX(16, MIN(15 * bds + 1, 256))`，`bds` = 每样本位深（`:326`），并区分 `IMAGEIO_INT8`/`IMAGEIO_INT16` 两条路径（`:357`、`:366`）。
- **本项目的落点与风险**：当前 `kFsOut` 的注释明确是 "OETF + 颗粒 + dither，**量化到 8bit**"（`core/engine/src/render/FrameRenderer.cpp:4749` 的 `kFsOut` 注释原文），即 **dither 幅度是按 8bit LSB 标定的**。切 10bit 后若不重标，dither 会变成"用 4 倍以上的噪声去抖一个更细的台阶"——**这是本期必查项**（§⑦）。
- **颗粒 vs dither 不要混**：颗粒是**创作层**（外观），dither 是**量化层**（技术）；10bit 改变了后者的标定，不应顺手改前者。

---

## ⑥ 建议的可执行验收判据

> 原则：判据必须**可自动算**、且**阈值有物理含义**（可分辨性/位深/码值），不用"看着更好"这种口径。
> 本节的判据与项目既有判据体系兼容：主判据仍用"**可分辨跨度**"（`docs/research/17-独立复核-影调架构量化.md:159`），只是把单位从 8bit LSB 换成 10bit LSB。

### 判据 A：容器与标签正确性（可 100% 自动，最该先做）

| 项 | 判据 | 工具 |
|---|---|---|
| A1 | 输出文件含 `colr` box 且为 `nclx`，字段 = `primaries=9`（BT.2020）、`transfer=8`（linear）或 16/18（PQ/HLG）、`full_range=1` | `heif-info`（libheif）/ `exiftool` |
| A2 | 位深 = 10（解码后 plane bit depth） | libheif `heif_image_get_bits_per_pixel` |
| A3 | 文件可被 libheif 无警告解码（8/9/10/12bit 是 libheif 支持域，见 libheif README:299-304）；**A1 失败即不通过**（标签缺失 = 下游按 sRGB 解释，见 §2.6） | `heif-info` / `heif-convert` |

### 判据 B：10bit vs 8bit 的层次保留（主判据，可自动）

- **B1 可分辨跨度**：以"白纸亮度"为起点，统计"每 0.1 EV 输出差 ≥ 1 LSB"能持续多远。
  - 8bit 口径（现状基线）：`≥1 LSB(8bit)/0.1EV`；**10bit 口径（本期）**：`≥1 LSB(10bit)/0.1EV`。
  - 通过条件：**10bit 跨度 ≥ 8bit 跨度**，且 10bit 下"每 0.1 EV 平均 LSB 数"达 8bit 的 4 倍量级（256→1024）。
  - ⚠ 阈值等号问题：`docs/research/17-独立复核-影调架构量化.md:136` 已指出"恰好 1 LSB"落在等号上会使判据不稳定 ⇒ **同时报告 ≤1 / ≤2 两个阈值下的结果**（`docs/research/17-独立复核-影调架构量化.md:167` 的三阈值表做法）。
- **B2 灰阶楔台阶数**：渲染 0.02 EV 步进的灰阶楔，统计**可分辨台阶数** N8 / N10；通过条件 `N10 > N8`（方向性判据，不设硬倍数，因为曲线形状会限制上限）。
- **B3 单调性**：线性域输出对 0.02 EV 步进**严格单调**（相邻差 > 0）。这是"量化可逆"的前提；对 filmic 这类"连续不可逆平顶"架构，B3 必红 —— 这也是"上 10bit 必须换掉 filmic"的量化证据（`docs/research/17-独立复核-影调架构量化.md:288-290`）。

### 判据 C：banding / dither（可自动，且能直接抓出"dither 未重标"）

- **C1 同值游程**：在灰阶楔或天空渐变区，统计水平/垂直方向的"连续相同码值"最长游程 `R_max`（10bit 码值域）。
  - 与 8bit 基线比：`R_max(10bit)` 不应长于 `R_max(8bit)` 的同尺度归一化值；
  - 阈值含义：`R_max` 直接对应可见台阶宽度。
- **C2 dither 幅度标定**：dither 后的局部残差标准差 `σ_dither` 应与 **1 LSB** 同量级（10bit 下 ≈ 1/1023，而不是 8bit 的 1/255）。
  - 一手依据：darktable 的自动 dither 强度**随输出位深变化**：`levels = MAX(16, MIN(15 * bds + 1, 256))`（`src/iop/dither.c:326`，`bds` = 每样本位深）。
  - **这是本期最容易漏、且能自动抓到的一项**（现状见 §5.3：`core/engine/src/render/FrameRenderer.cpp:4749` 的 dither 是按 8bit 标定的）。
- **C3 颗粒不回归**：颗粒（创作层）参数与效果不变——用"关 dither、只留颗粒"的对照臂逐像素比（把量化层与创作层分离测量）。

### 判据 D：色域边界可分辨性（可自动，需先造测试图）

- **测试图**：在 BT.2020 线性域生成一组**等亮度、等感知步长的色度扫描块**（跨越 sRGB 三角形边界内外），导出参考值（浮点）。
- **判据**：
  - D1 **边界外不塌陷**：sRGB 域外的相邻色块，在 10bit HEIF 解码后仍**彼此可分辨**（ΔE_ITP 或 ΔE00 > 可分辨阈值）；
  - D2 **与参考的偏差上界**：解码值与浮点参考的 ΔE 上界（建议用 ΔE_ITP，因交付域是 BT.2020）；
  - D3 **对照组**：同图按 8bit sRGB 交付时，边界外色块应出现 ΔE≈0 的塌陷（证明测试图确实探到了 sRGB 边界）。
- 备注：D1/D2 的"可分辨阈值"必须**写死在报告里并同时给两个阈值**（同 B1 的教训：阈值一变排序就翻）。

### 判据 E：交付链零回归（口径必须改，不能沿用像素级 CRC）

- **E1 变量隔离**：换工作空间当期**必须**保持"旧链可跑、新链并行"（这与项目已定"必须单独一期"一致）；否则无法区分"域变化"与"实现 bug"。
- **E2 零回归口径 = 同显示域对照**：把两条链的输出都映射到**同一显示域**（sRGB 显示）后比较；要求差异可用"域变化"解释（逐像素差异图应表现为系统性色相/饱和度偏移，而非随机噪点/结构性损坏）。
- **E3 白名单**：给出"被允许变化的文件/基线清单"（CRC32 基线全量重建），清单外任何差异视为回归。
- **E4 结构性判据不变**：几何（对齐/裁切/尺寸）、元数据（EXIF/方向）、文件名与相册入库行为必须逐位不变。

### 判据 F：兼容性矩阵（半自动，记录式）

记录内容（每端一格）：**能否显示 / 色彩是否正确 / 是否发灰发暗 / 是否报错或降级为 SDR**。
接收端清单：① 系统相册（Android）② 微信（原图 + 压缩）③ PC（Windows 照片、Chrome、Lightroom Classic、macOS 预览）④ 第三方（Google Photos、Instagram）。

通过条件不设"全部正常"（不受控），但**必须逐格记录**并作为"是否需要同时出 Ultra HDR/SDR 备份"的决策输入。

---

## ⑦ 待确认 / 必须真机实测项

> 按"不实测就会做错决定"的程度排序。前 3 项决定**这一期能不能按 10bit HEIF 主线开工**。

| # | 待确认项 | 为什么必须真机 | 怎么测（最小成本） |
|---|---|---|---|
| 1 | **X300 是否存在可用于 still image 的 HEVC Main10 编码器**（且支持 P010 输入） | 静态文档只给常量，不保证机型实现；`KEY_PROFILE` 是 hint，编码器可能静默换档 | `MediaCodecList` 遍历 encoder：查 `FEATURE_HdrEditing`、`profileLevels` 含 `HEVCProfileMain10`、`COLOR_FormatYUVP010`、`VideoCapabilities.isSizeSupported(w,h)`；再实编一帧验证 |
| 2 | **`MediaFormat` 的 `color-standard`/`color-transfer` 是否真的写进 HEIF 的 `colr/nclx`** | AOSP 的 HEIF 封装路径没有公开保证（androidx HeifWriter 根本不设这些键）；若写不进去，判据 A1 全红 | 最小 demo：MediaCodec(HEVC) + `MediaMuxer(MUXER_OUTPUT_HEIF)`，设 `COLOR_STANDARD_BT2020`+`COLOR_TRANSFER_LINEAR`，用 `heif-info`/`exiftool` 看 `colr` |
| 3 | **下游对 `transfer=8`（linear）的解释**：系统相册 / 微信 / PC 是"按 nclx 正确解释"还是"当 sRGB 直接显示" | 这决定"线性交付"是否必须改成 PQ/HLG，或必须同时出 SDR 备份 | 同一张图的 3 个变体（linear=8 / PQ=16 / 无标签）各出一份，逐端截图对比 |
| 4 | **分辨率与对齐上限**：编码器 `getWidthAlignment()`、最大支持尺寸、grid tile 尺寸约束 | 48MP 级交付会撞上编码器限制；grid 的分片规则会影响编码器跨 tile 质量 | `VideoCapabilities` 全量 dump + 实测 4 个分辨率档 |
| 5 | **全分辨率 10bit 编码耗时与内存**（决定快门后等待与 OOM 风险） | 10bit/P010 数据量与 8bit 不同；某些机型 10bit 走 CPU 软编 | 打点（项目已有 `photo.saved` 的 `encMs` 口径，见 `core/engine/src/jni/JpegEncoder.cpp:19-20` 注释） |
| 6 | **10bit 下 dither/颗粒的观感与"噪点是否变脏"** | dither 幅度重标（判据 C2）是数值问题，但"变脏"是人眼判据；颗粒与 dither 会互相掩蔽 | 真机成片 A/B：{8bit 基线, 10bit + 旧 dither, 10bit + 重标 dither} 三臂，固定机位同帧 |
| 7 | **换工作空间后肤色/饱和度的标定** | 域变化对肤色与饱和红的影响最大，且与"预设"耦合；数值判据（ΔE）与观感可能不一致 | 固定机位 + 含肤色/饱和色卡场景，新旧域映射到同显示域后逐块 ΔE + 人工盲评 |
| 8 | **vivo X300 自身 HDR/10bit 交付形态**（对照物，不是目标） | 属于产品选择，用于定位"系统相册/微信在 X300 上实际认什么" | 由姊妹卡 `r-next-10bit-heif-PC-EVIDENCE.md` 取证 + 本卡判据 F 记录 |

### 尚未取到一手的项（诚实标注）

- **ISO/IEC 21496-1 的原文条款号**：Android 官方规范页有 "ISO 21496-1 Compatibility" 专章（抓取文本行 1073-1100），但 ISO 官网页面对本卡返回 **HTTP 403**，未取到标准原文；本卡只引用 Android 官方对该标准的表述，**不臆测条款内容**。
- **Apple 侧（Adaptive HDR / ISO 21496-1 命名）**：仅见到二手来源（Wikipedia 条目 <https://en.wikipedia.org/wiki/Ultra_HDR> 行 75），**未取到 Apple 一手文档**，标为待确认。
- **vivo / OPPO 的 10bit 交付形态**：无一手来源，交由姊妹卡取证。

---

## 附：来源清单与证据强度

> 全部外部来源为本卡 **2026-10-14 只读抓取**（curl 原文到 `/tmp/std`，逐条 grep 定位行号）。
> 本卡**未修改任何源码、未构建、未运行 App**；唯一写入 = 本文件。姊妹卡 `r-next-10bit-heif-REPO-AUDIT.md` / `r-next-10bit-heif-PC-EVIDENCE.md` 由并行任务产出，引用时以对方最终版为准。

### A. 平台官方文档 / 规范（【官方文档】）

| 来源 | URL | 用于 |
|---|---|---|
| androidx `HeifWriter` 参考页 | <https://developer.android.com/reference/androidx/heifwriter/HeifWriter> | §2.2 |
| `MediaFormat`（色彩键与取值、`KEY_PROFILE` 说明） | <https://developer.android.com/reference/android/media/MediaFormat> | §2.3/2.4 |
| MediaCodec 三页：`CodecCapabilities`（P010/P210/RGBA_1010102、`FEATURE_HdrEditing`/`FEATURE_HlgEditing`）、`CodecProfileLevel`（`HEVCProfileMain10` 系列）、`VideoCapabilities`（`getWidthAlignment`） | `https://developer.android.com/reference/android/media/MediaCodecInfo.{CodecCapabilities,CodecProfileLevel,VideoCapabilities}` | §2.3/2.7 |
| `ColorSpace.Named`（BT2020 原色 / D65 / 其 OETF；BT2020_HLG/PQ；DISPLAY_BT2020）与 `Gainmap`（API 34） | `https://developer.android.com/reference/android/graphics/{ColorSpace.Named,Gainmap}` | §4.2/4.3、§3 |
| **Ultra HDR Image Format v1.1**（含 "ISO 21496-1 Compatibility" 专章） | <https://developer.android.com/media/platform/hdr-image-format> | §3 |
| ISO/IEC 23008-12（HEIF）与 ITU-T H.273（CICP）原文 | **未取到**（ISO 站点对本卡返回 403）；CICP 值表改用 libheif 枚举作为一手替代 | §2.5（诚实标注） |

### B. 成熟开源实现源码（【一手源码】）

| 来源 | 抓取 URL | 关键行 |
|---|---|---|
| androidx heifwriter（`HeifWriter.java` / `HeifEncoder.java` / `WriterBase.java`） | `https://raw.githubusercontent.com/androidx/androidx/androidx-main/heifwriter/heifwriter/src/main/java/androidx/heifwriter/` | HeifWriter 182/197/209/228/243/256/271（Builder API）、303（仅 `YUV_420_888`）、410（`highBitDepthEnabled=false`）；HeifEncoder 49-53（512×512 grid / block 32 / max ratio 0.25）、72-75（`useBitDepth10=false`）、89-99（只设 MIME/TILE/GRID）；WriterBase 401-419（grid 行数、`KEY_IS_DEFAULT`） |
| libheif（`heif_color.h` / `nclx.cc` / `README.md`） | `https://raw.githubusercontent.com/strukturag/libheif/master/`（`libheif/api/libheif/heif_color.h`、`libheif/nclx.cc`、`README.md`） | heif_color.h 140-154（primaries）/156-174（transfer）/177-193（matrix）/195-210（nclx 结构）；nclx.cc 314-322（sRGB 默认）、360-373（未定义值替换）；README 299-304（8/9/10/12bit，拒绝 16bit） |
| libultrahdr `README.md` | `https://raw.githubusercontent.com/google/libultrahdr/main/README.md` | 3-11（gain map 原理）、35-47（API 场景）、56（HDR_LINEAR/PQ/HLG 输入） |
| darktable（`src/iop/colorin.c` / `src/common/colorspaces.{c,h}` / `src/iop/dither.c`） | `https://raw.githubusercontent.com/darktable-org/darktable/master/` + 左列路径 | colorin.c 87、200（默认 `DT_COLORSPACE_LIN_REC2020`）；colorspaces.c 678-689（gamma 1.0 + D65 + Rec2020 原色）；colorspaces.h 86；dither.c 114（模块描述）、326（`levels` 随 `bds`）、357/366（INT8/INT16） |
| ACES `docs/encodings/acescg.md`（"A Working Space for CGI Render and Compositing"） | `https://raw.githubusercontent.com/aces-aswf/aces-docs/main/docs/encodings/acescg.md` | 9/16/18（AP1 与"包住 Rec.2020+P3"的理由）、39（负值可置 0）、91-96/126-130（SMPTE RP 177 矩阵） |
| OpenColorIO 文档（`scene_linear` 角色定义） | <https://opencolorio.readthedocs.io/en/latest/guides/authoring/overview.html> | 抓取文本 263-294 |
| Blender `config.ocio` | `https://raw.githubusercontent.com/blender/blender/main/release/datafiles/colormanagement/config.ocio` | 29-30/56-60（`scene_linear: Linear Rec.709`）、87-92（Rec.2020 视图 + `AgX Base Rec.2020`） |

### C. 项目内既有证据（【项目内既有结论】）

| 文件:行 | 内容 |
|---|---|
| `docs/research/17-独立复核-影调架构量化.md:287-290` | "sigmoid/ACES 损失量化可逆性（位深可救）vs filmic 损失映射可逆性（任何位深不可救）" |
| `docs/research/17-独立复核-影调架构量化.md:136`、`:159`、`:167`、`:235-252` | "可分辨跨度"判据的来源、阈值等号缺陷、8bit 硬上界表 |
| `docs/research/01-darktable.md:131-137`、`:162-189` | darktable sigmoid 的输入/输出域声明与 `base_primaries` 选项（`working profile` / `Rec2020`） |
| `core/engine/src/render/FrameRenderer.cpp:4749` | `kFsOut` = "OETF + 颗粒 + dither，**量化到 8bit**"（dither 按 8bit 标定） |
| `core/engine/src/film/EngineRegistry.cpp:35-37` | 当前两个引擎的 `working_space = "LinearSRGB"`（本期要换的就是它） |
| `core/engine/src/render/FrameRenderer.cpp:1006` | `uCcm` 注释：**sensor→sRGB 行主序**（换域后必须重标，§4.3 第 1 项） |
| `core/engine/src/jni/JpegEncoder.cpp:1-20` | 现交付链 = stb JPEG 4:4:4（8bit/通道）；`Bitmap.compress` 恒 4:2:0 的历史结论 |
| `.zcode/memory/decisions.md:105-106` | 用户已拍板：上 10bit HEIF、判据改"可分辨跨度"、公共工作空间 **Rec.2020 线性**且必须单独一期 |

### D. 证据强度自评

| 结论 | 强度 | 说明 |
|---|---|---|
| androidx `HeifWriter` 不支持 10bit / 无色彩 API | **强（一手源码）** | 硬编码常量 + 完整 Builder API 清单 |
| 10bit 路径 = MediaCodec Main10 + MediaMuxer HEIF | **中（官方常量存在）** | 常量存在 ≠ 机型可用；可用性必须实测（§⑦-1） |
| `color-*` 键 → HEIF `colr/nclx` | **弱（未验证）** | 无公开保证；列为实测项（§⑦-2） |
| 色彩标签：CICP 值表（primaries=9 / transfer=8/16/18）+ 无标签 → sRGB 默认解释 | **强（一手源码）** | libheif 枚举与 `nclx.cc` 两条路径逐条；ISO 原文未取到 |
| Ultra HDR 规范细节（单通道 / map_gamma / 降采样 / ISO 21496-1 双写）与 BT.2020=D65+原色坐标 | **强（官方规范/文档）** | 官方页逐条行号；Android `ColorSpace.Named` 表 |
| darktable / ACES / Blender 的工作空间选择 | **强（一手源码 / 规范）** | 逐行 |
| "工作空间应 ≥ 交付色域" | **中（由 ACES 单点理由外推）** | ACES 是 CGI 场景的理由；摄影场景的对照样本只有 darktable 的默认值 |
| 10bit 与 filmic plateau 的交互 | **强（项目内已量化 + 标准依据一致）** | 量化误差 vs 映射不可逆是两类问题 |
| dither 需按位深重标 | **强（一手源码 + 项目现状对比）** | darktable `dither.c:326` vs 项目 8bit 标定 |
| 下游兼容性（相册 / 微信 / PC） | **无** | 必须真机实测（§⑦-3、判据 F） |

### E. 本卡边界

- 本卡只给"标准做法 + 来源"，**不替用户拍板**；"Ultra HDR 是否也要做""线性还是 PQ/HLG 交付"属于产品决策。
- 本卡不含 vivo X300 真机事实（姊妹卡 `r-next-10bit-heif-PC-EVIDENCE.md` 负责），也不含本仓爆炸半径清单（`r-next-10bit-heif-REPO-AUDIT.md` 负责）。
- 所有"必须实测"项已集中在 §⑦；**在拿到真机数据前，本卡不给"能不能做"的最终结论**。
