---
summary: vivo X300 相机引擎第一手取证（adb 真机拉取 + 二进制/字节码静态提取）：引擎分层、ACES 式影调管线、自有 LUT 容器、蔡司公式、HAL 侧自适应决策
created: 2026-10-13
status: draft
---

# 工业界引擎取证：vivo X300（V2509A）原生相机

> 取证方式：**用户真机 adb 拉取样本 → 本地静态提取**（`strings` / 自建 DEX 可打印串提取 / LUT 头解析）。
> 本报告由架构师接手完成（原取证卡在收尾前因预算耗尽失败、未落盘；其已完成的样本拉取与部分分析被继承）。
> **证据等级**：`[A]` 二进制/字节码原文 · `[B]` 官方文档/公开资料 · `[C]` 推断（标"未验证"）。

---

## 0. 结论速览

1. **vivo 相机的成片渲染是"shader 管线 + 曲线纹理 + 3D LUT 混合"，不是学习型端到端渲染**：tone mapping 以**模式枚举**驱动（`TONEMAP_MODE` / `mToneMappingMode` / `mToneMappingSpace` + `isToneMapSupport` 能力探测），配一条 ACES 式 HDR 管线与逐通道曲线查表。`[A]`
2. **app 层（Java/Kotlin + 相机自带 native）里找不到"自适应白点/自动影调"实现**：全量 dex 可打印串中 `autoTone`/`auto_tone` **0 命中**，`histogram` 47 条**全部是 UI 组件**（取景直方图显示），`percentile` 命中 `SlidingPercentile.java`（属 Camera2 帧率统计，非影调）。`[A]`
3. **自适应决策不在 app 层，而在 HAL/3A 层**：系统侧存在 `libcam.hal3a.custom.ae.HDRStateEvaluator.so`（HDR 状态评估）、`com.vivo.stats.{aec,awb}.so`、`libaaa_toneutil.so`、`com.vivo.stats.vivo3DLut.so`，以及逐传感器 `chromatix.{ccm,awb,lsc}` 与 `aiawb_params_PD2509.txt` —— **这些文件未 root 不可读**（已实测 `Permission denied`），内容未取证。`[A]+[C]`
4. **LUT 是 vivo 自有容器格式**：magic `CUBEB2BIN8` / `3DLUTBIN8`，56 字节头、网格字段 `0x21`(=33)、**33³ float32**，与本项目内置 LUT 同规格。`[A]`
5. **AI 与传统链路在真机上并存**（`libcamalgo.mfnr` 与 `libainr` 同存、`libcamalgo.aics/vaics` 与 `libaiawb_moon` 同存）⇒ 工业界是**加旁路**而非换主链；与 12 号报告的结论一致。`[A]`
6. **相机 app 自带的推理栈是自研 OpenCL 引擎 + XNNPACK 包装**（`libvdnn.so` 内含 OpenCL kernel 源码串、`libxnn.so` 为 XNNPACK 风格 API），与 Google 走 TFLite/XNNPACK CPU（13 号报告）形成两种路线并存。`[A]`

---

## 1. 取证环境与限制（必须先读）

| 项 | 事实 |
|---|---|
| 设备 | vivo **V2509A**（X300），Android **16**，`ro.board.platform=mt6993`（天玑 9500）`[A]` |
| adb | 本机 `~/Library/Android/sdk/platform-tools/adb` v37.0.1；**REA 的 adb provider 不可用**（`REA_ADB_PATH` 未设）⇒ 改用 shell 直接 `adb pull`，REA 只用于本地文件分析 |
| REA jadx | **不可用**（`Set REA_JADX_MCP_JAR to ... jadx-headless-mcp 0.7.1 JAR`）⇒ DEX 分析改用自建提取（python 正则扫全部 `classes*.dex` 的可打印串，20 字符起） |
| **权限硬边界** | `/vendor/lib64/*`、`/odm/lib64/*` 对 shell **Permission denied**（已实测多个文件）⇒ **只能取到文件名清单，取不到内容**。这是本次取证最重要的限制：**vivo 的成片主链在 HAL/vendor 侧，恰恰是读不到的那一层** |
| 可读范围 | `/system/app/VivoCamera/**` 全可读（含 APK 外置 native 库）、`/sdcard/**` 可读 |

**样本清单**（本地 `/tmp/x300-samples/`）：

| 样本 | 大小/数量 |
|---|---|
| `VivoCamera.apk`（`/system/app/VivoCamera/VivoCamera.apk`） | 396,094,854 B，**APK 内无 `.so`** |
| `vivoapp-libs/`（`/system/app/VivoCamera/lib/arm64/`） | **81 个 .so**，97,297,504 B（全量） |
| `dex/`（APK 内 `classes*.dex`） | 10 个（`classes.dex` 13.1MB … `classes10.dex` 9.8MB） |
| `assets/`（APK 内 `assets/cube/*`） | 8 个 `.bin`，各 **431,300 B** |

**vivo 的打包方式本身是一条工程事实**：相机 native 库**不打包进 APK**，而是外置在 `/system/app/VivoCamera/lib/arm64/`（81 个），APK 只有 dex + 资源。`[A]`

---

## 2. 影调/色彩管线结构（本案最关心的一节）

### 2.1 ACES 式 HDR tone mapping 管线（dex 内嵌 GLSL 原文）

DEX 中直接内嵌了 shader 源码字符串，其中一条完整表达式为：`[A]`

```glsl
gl_FragColor.rgb = OETF(OutputTransform(NormalizeLuminance(ToneMap(ScaleLuminance(InputTransform(EOTF(gl_FragColor.rgb)))))));
```

配套出现的函数与符号名（均 `[A]`，来自同一批 GLSL 串）：

- 函数：`InputTransform` / `ScaleLuminance` / `ToneMap` / `NormalizeLuminance` / `OutputTransform` / `OETF` / `EOTF`
- HDR 曲线：`ScaleLuminance_ST2084`（PQ）、`toneCurveHLG`
- 曲线查表：`uniform sampler2D toneCurveTexture;` + 逐通道 `calFloat(texture2D(toneCurveTexture, vec2(clamp(gl_FragColor.r, 0.0, 1.0), 0.5)).ra)`
- 模式控制：`TONEMAP_MODE`、`TONEMAP_AVAILABLE_TONE_MAP_MODES`、`mToneMappingMode`、`mToneMappingSpace`、`isToneMapSupport`、`isSupportToneMapMode`

**解读**（`[C]`，标注为推断）：这是一条 **"输入变换 → 亮度缩放 → 色调映射 → 亮度归一 → 输出变换"** 的标准 HDR 显示映射骨架，与我们管线里的 filmic/AgX 同层，但**参数是"模式"而非"端点"**：`mToneMappingSpace`（工作色彩空间）+ `mToneMappingMode`（模式枚举）+ 能力探测，是典型的"**按能力/模式选曲线**"设计，而不是"按场景统计算端点"。

### 2.2 LUT 体系：容器、混合与多种查表形态

**资产格式**（`assets/cube/*.bin`，8 个，各 431,300 B）`[A]`：

| 项 | 值 |
|---|---|
| magic | `CUBEB2BIN8`（6 张）/ `3DLUTBIN8`（`static_lut_dolby_hlg_preview{,_x200}.bin`） |
| 头部 | `magic(10B) + 00000021 + 00000001 + …`（56 字节头） |
| 网格 | `0x21` = **33** |
| 数据类型 | **float32**（33³×3×4 = 431,244 B + 56 B 头 = 431,300 B ✓） |
| 文件名语义 | `bt2020top3`、`p3to2020(_preview)`、`pd2548_*`（机型代号）、`dolby_hlg_preview(_x200)`、`wm` |

⇒ **色彩空间转换 + 机型相关（pd2548）+ 显示（dolby_hlg）三类用途**，全部走 33³ LUT。

**运行时 LUT 用法**（GLSL 串）`[A]`：存在**多种查表形态**与统一的混合强度：
- `lutMapping1x64(textureColor)`（1D 64 点曲线）
- `lutMapping8x8(textureColor, sLutTexture)`
- `lutMappingSoftgray(textureColor)`（"柔灰"，疑为胶片感/褪色类外观）
- `lutMapping(...)` + `uniform highp float lutMixLevel;` ⇒ `mix(basePixel.rgb, lutMapping(...), lutMixLevel)`

⇒ 与我们的 `[34] LUT 强度` 概念同构（LUT + 强度混合），但**vivo 把"曲线/LUT/柔灰"做成了可组合的映射族**。

### 2.3 引擎类族与"蔡司公式"

`[A]` 从 dex 提取到的渲染相关类（259 条 `com/vivo/imageprocess` 条目中的关键项）：

- `com/vivo/imageprocess/ImageProcessRenderEngine`（含内部类 **`ZeissFormulaParam`**）
- `com/vivo/rendernodes/glnode/filterRenderEngineParam/ZeissFormulaParam`（**另一处**，属于 `com/vivo/rendernodes` 的 **GL 节点式渲染框架**）
- `FilterType`、`GlesUtils`、`ImageUtils`、`CustomFilterBean{,$Texture,$Uniform}`、`SeqFrameManager`
- `ImageProcessEffect` / `ImageProcessEffectPreEngine`
- `videoprocess/{AeEffect, AbsEffect, Vlog3Info, Vlog3Theme, PicFilmTheme, RememoryTheme, TextEffect, CustomEffect, VideoEffect}`
- 滤镜类型常量：`FILTER_TYPE_ZEISS_FORMULA`、`FILTER_TYPE_ZEISS_STAR_LIGHT`
- 蔡司镜头风格配置键：`cfg_style_zeiss_Lens_{biotar,planar,sonnar,distagon_v2}`、`cfg_style_tilt_zeiss_Lens_*`、`top_bar_1_5_watermark_logo_zeiss`

**判断（含不确定性）**：这一族**更像"图片/视频特效与编辑引擎"而非"拍照成片主链"**——理由：① 类族集中在 `imageprocess`（图像处理）+ `videoprocess` 子包，带 `SeqFrameManager`（序列帧）；② 资产是显示/色彩空间 LUT 与水印/风格配置；③ 蔡司相关的键是**镜头风格（biotar/planar/sonnar/distagon）与水印**，属拍摄 UI/特效层。`[C]`
拍照成片主链应在 HAL/vendor（`libcam.hal3a.*`、`libcamalgo.*`、`chromatix.*`、`com.vivo.stats.*`）——**那一层本次未能读取**。

---

## 3. native 库取证（相机自带 81 库）

### 3.1 推理与算法栈（字符串级证据）

| 库 | 证据（`[A]`） | 判读 |
|---|---|---|
| `libvdnn.so`（2.0MB） | 内含 **OpenCL kernel 源码串**：`__global const uchar* input`、`__read_only image2d_t input0/1`、`__global const float* input` | vivo 自研 DNN 运行时，**走 GPU/OpenCL** |
| `libxnn.so`（830KB） | `GetInput` / `GetInputShape` / `GetInputType` / `GetInputName index out of range.` / `GetInput may receive unsupported data type.` | **XNNPACK 风格包装**（CPU int8/float） |
| `libImageProcessPost.so`（670KB） | `brightSpotDetect`、`VIAC_DetectBrightSpot_WithPreview`、`HighLightParams`、`highlightNums`、`ntiCvAlphaVer_2_0_41_VivoThreadPool` | **高光/亮点检测**存在（与"白点/高光保护"直接相关）；含自己的线程池 |
| `libnti_rportrait.so`(13.4MB) / `libnti_picopo.so`(5.9MB) / `libnti_double_exposure_jni.so` | 体积与命名 | NTI 系列算法库（人像/多帧曝光等） |
| `libaimatte_jni_v1/v2.so` | 命名 | AI matting（抠图）|
| `libvglfilter.so`(9.3MB) / `libimagepipeline.so` / `libnativerender.so` / `libmesEglImageRender.so` / `libeglImageRender.so` / `libgl_node.so` | 命名 + `com/vivo/rendernodes/glnode` 类族 | GL 滤镜/渲染节点框架 |

**反向证据（同样重要）**：对 `libImageProcessPost/Pre`、`libvglfilter`、`libimagepipeline`、`libnativerender` 五个库做 `strings` 过滤 `tone|curve|histogram|adaptive|lut|highlight`，**除 `libImageProcessPost.so` 的高光检测符号外，未命中任何影调/曲线/LUT 符号**。`[A]`
⇒ 这些 app 侧库**不含影调曲线实现**（要么被 strip、要么影调在 HAL 侧、要么曲线以资源形式传入 shader）——**不能据此断言"不存在"，只能说"本层未取证到"**。

### 3.2 app 层关键词全扫结果（回答"是否自适应"）

对全部 10 个 dex 的 521,276 条唯一可打印串做关键词统计 `[A]`：

| 关键词 | 命中 | 说明 |
|---|---|---|
| `autoTone` / `auto_tone` | **0** | app 层**无**自动影调实现 |
| `tonemap`（`TONEMAP_MODE` 等） | 2 + 4（`mToneMapping*`） | **模式枚举**式控制 |
| `toneCurve` | 6 | 曲线纹理查表（GLSL） |
| `histogram` | 47 | **全部为 UI**：`advanced_histogram_view_*`、`pref_camera_histogram_entryvalues`、`HistogramViewManager`、`HistogramPresenter` |
| `percentile` | 1 | `SlidingPercentile.java`（Camera2 帧率/统计，非影调） |
| `adaptive`（影调相关） | 0 | 仅视频编码 `mb_adaptive_frame_field_flag`、UI `adaptiveDialogWidth` 等无关项 |
| `whitePoint` | 2 | `whitePointD65`、`whitePoint`（色彩空间常量，非"白点参数"） |
| `sceneType` | 16 | 场景类型字段（与 `Shot2ShotFixedInterval{sceneType=` 等在一起）|

⇒ **app 层不存在"按场景统计自动定白点/自动影调"的实现**；影调是**模式 + 曲线 + LUT 强度**的确定性组合。

---

## 4. 系统侧（只有清单，内容未取证）

`/vendor/lib64/`（相机相关，按功能分组）`[A]`：

- **传统 3A / ISP 主链**：`libcam.hal3a.v3.tone.mgr.v4l2.so`、`libcam.hal3a.v3.ai3a_v4l2.so`、`libcam.hal3a.custom.ae.HDRStateEvaluator.so`、`libcam.halisp.*`（tuning data provider / top control）、`libcamalgo.{mfnr, ais2.mfnr, aics, vaics, hdr10p, ais2.bss}.so`、`libaaa_toneutil.so`、`libHdr2Sdr.so`、`com.vivo.stats.{aec, awb, af_9.5, pdlib, vivo3DLut, vivotintless}.so`
- **AI 旁路**：`libainr.so`、`libaiawb_moon.so`、`libaiawb_moon_model.so`、`libaiseg_{model,cache,p1ggm,sun}.so`、`libaifd_model.so`、`libaidepth_tuning.so`、`libaimemc.so`、`libai_relight.so`、`libaispq.so`、`libaiste.so`
- **推理运行时**：`libarmnn.so`、`libarmnn_ndk.mtk.vndk.so`、`APUWare{Aiste, ApusysAidlServer, UtilsAidlServer}.so`
- 其它：`libSuperStarV2.so`、`libImageProcessPost.so`、`libDBAccessor_ISP.so`、`libVivoTuningFileSelector.so`

`/odm/lib64/camera/` `[A]`：

- **逐传感器调优**：`libc.vivo.IMX885PD2509_MIPI_RAW.chromatix.{ccm, zsl_preview.awb, zsl_snapshot.awb, preview.lsc}*.so`（另有 IMX09APD2502 / IMX06CPD2502 组；`PD2509` = 本机代号）
- **AI AWB 参数**：`aiawb_params_PD2509.txt`
- **AEC 参数**：`com.vivo.aec.pd2509_imx885{,_ultra}.bin` 等
- OIS 标定：`02371703_ois_static_cal.txt`

**判读**（`[C]`）：vivo 的自适应发生在 **HAL 3A 决策层**（AE 曝光目标 + AWB + `HDRStateEvaluator` 的 HDR 状态判定 + `chromatix` 逐传感器 LSC/CCM 标定），而**不是**在成片渲染的曲线参数上做文章。也就是说：**"场景自适应"与"外观曲线"在架构上是分离的两层**——这与我们当前"把场景自适应压进 filmic 白点参数"的做法**方向相反**。

---

## 5. 对我们"换引擎"的启示

1. **外观（look）与场景自适应应当分层**：vivo 把自适应放在 3A（曝光/AWB/HDR 状态），把外观放在"模式 + 曲线 + LUT"，两层互不污染。我们的痛点恰恰是两层耦合在同一个 `[38]` 上（16 号报告已从数学上证明确实如此）。
2. **"学习型"不必进成片路径**：Google 的 HDRNet 只产出 gain map（13 号报告），vivo app 层干脆是确定性 shader + LUT。两条路线都指向"成片阶段零 NN / 零不确定推理"。
3. **LUT 容器与混合语义是工业界标配**：vivo 用 33³ float32 + `lutMixLevel`，与我们的 LUT 强度机制同构 ⇒ 我们已有的 LUT 通道是**对的架构**，缺的是"曲线族 + 模式"的组合能力（`lutMapping1x64` / `8x8` / `Softgray` 这类可组合映射）。
4. **高光保护是独立模块**：vivo 有专门的 `brightSpotDetect` / `HighLightParams`（app 侧），说明"密集高光点检测"被当作**独立于影调曲线的模块**处理——这正是我们 `[43] white_floor`（治釉面/白瓷白斑）想解决的问题，值得对照实现。
5. **取证判据**：`/vendor` 不可读 ⇒ 任何"厂商怎么做的"结论都必须标注"app 层可见 / HAL 层不可见"；不要把 app 层当主链（本次差点误判 `imageprocess` 为拍照主链）。

---

## 6. 未取证 / 待办（诚实清单）

1. **HAL/vendor 侧内容全部未取证**（`/vendor/lib64`、`/odm/lib64` 权限拒绝）。这是最大的空白：vivo 的影调决策（是否自适应、怎么定白点）就在这一层。
   - 可行路径（需用户拍板）：① 用户设备 root 后重新取证；② 从公开的 MTK/vivo 文档或固件包取证（需另立卡）；③ 用 `dumpsys media.camera` / Camera2 元数据侧信道观察（只能看到接口面，看不到实现）。
2. **app 层 dex 的方法体未反编译**（REA jadx 不可用，只有字符串级证据）⇒ "`mToneMappingMode` 的取值集合"、"曲线从哪来（资源/下发/生成）"未确认。
3. **native 库符号被 strip**：`libImageProcessPost.so` 等只有少量 C++ 符号可读，未做反汇编（REA 的 Ghidra provider 未在本轮用于该库）。
4. 未对照 `com.vivo.gallery`（相册编辑）与 `com.vivo.alphacamera`（4.1MB APK）——后者可能是专业模式，含 RAW 链线索。
5. 未做真机行为取证（拍同一场景改设置看输出分布）——属"controlled-replay"，需另立卡。

---

## 附：证据文件位置

- 样本：`/tmp/x300-samples/{VivoCamera.apk, vivoapp-libs/(81), dex/(10), assets/(8)}`
- 中间产物：`dex-glsl-dump.txt`（564 条 GLSL）、`dex-imageprocess-classes.txt`（259 条类名）
- 本报告的复现命令：`adb pull` + `strings -a` + python 正则提串 + LUT 头解析（口径见 §1）
