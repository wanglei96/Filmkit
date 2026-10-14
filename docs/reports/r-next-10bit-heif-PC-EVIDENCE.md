# 下一期取证：10bit HEIF 交付链 + 公共工作空间切 Rec.2020 线性（外部一手事实）

> **取证对象**：PhotonCamera（工作副本 `/tmp/pc-tone`，Java/Kotlin + GLSL）与 vivo X300 真机残留（`/tmp/x300-samples`）。
> **纪律**：只读源码，未修改任何文件、未构建、未运行 App。每条结论标注 `文件:行` 或命令输出；推断显式标注「推断」。
> **对任务书的更正**：任务书称「sigmoid/filmic/AgX/Spektrafilm = BT2020 + 0.7EV」。实测 **Spektrafilm 的工作空间是 ProPhoto，不是 BT2020**（`raw/RawRenderingEngine.kt:56-61`）。0.7EV 确为这四个引擎共享，但 BT2020 的只有 **AgX / DarktableSigmoid / DarktableFilmic** 三个。

---

## ① 结论摘要

1. **PC 没有 10bit HEIF。** 全仓 grep `heif|heic|10bit|bit depth` 在 `strings.xml` 里**零命中位深文案**；唯一 HEIF 写点是 `gallery/HeicExportEncoder.kt:34-79`，走 AndroidX `HeifWriter.INPUT_MODE_BITMAP`（:52），把 `Bitmap` 原样交给系统编码器，**没有任何位深 / profile / HDR 声明**。
2. **PC 的位深在 GPU 内部是够的**：全链路 `GL_RGBA16F` / `GL_HALF_FLOAT`，交付 `Bitmap` 也是 `Bitmap.Config.RGBA_F16` 并带 `ColorSpace` 对象（`raw/RawDemosaicProcessor.kt:9109-9124`）。**断点在编码器**：位深准备好、编码没接上。
3. **PC 的公共工作空间是"每引擎一个"**，不是单一公共空间：9 个引擎分属 `ProPhoto / HNCS / SRGB / BT2020` 四种工作空间（`raw/RawRenderingEngine.kt:10-74`）。切一个公共 Rec.2020 线性空间意味着**打破 PC 现有的 per-engine 工作空间模型**。
4. **PC 已经给出了"运行时由色度坐标推导工作↔输出矩阵"的完整做法**，无需硬编码 mat3：`computeWorkingToOutputTransform` = `outputFromXyz(D50) · xyzFromWorking(D50)`（`raw/RawDemosaicProcessor.kt:7579-7587`）。这块可直接照抄形态。
5. **PC 的 OETF 纪律是正确的、值得照抄**：引擎 tone pass **输出线性 RGB**（契约写在注释里，`raw/RawEngineTonePass.kt:499-502`），`RawSrgbPass` 是**唯一**显示编码点（`raw/RawSrgbPass.kt:66-81`），用精确 sRGB 分段函数而非 `pow(x,1/2.2)`。
6. **但 PC 有一处 OETF 近似违纪**：HNCS 的 HDR base curve 采样路径用 `pow(engineOutput, 2.2)` 近似解码（`raw/RawEngineTonePass.kt:288-292`），非 HNCS 引擎则直接当线性用 —— 同一 uniform 两条语义。
7. **PC 的 `RawExposureCompensationDomain`（Curve/Linear）是死字段**：全 Kotlin 源码里只被日志引用（`raw/RawDemosaicProcessor.kt:3770`），查不到任何按域分叉的数学分支。**引擎切换亮度差的真实机制是 `defaultExposureCompensationEv` → `linearGain = 2^EV` → profile gain 整体乘**（`raw/RawProfileExposureGl.kt:59` + `raw/RawEngineTonePass.kt:401`），0.7EV ≈ ×1.6245。
8. **PC 的所有 HDR/gainmap 数学锚死在 linear sRGB**：`HdrBufferEncoding` 只有一个取值 `LINEAR_SRGB`（`hdr/GainmapSource.kt:11-13`），gainmap 亮度系数硬编码 BT.709 的 0.2126/0.7152/0.0722（`hdr/GpuReferenceGainmapProducer.kt:543-545`）。**切 Rec.2020 线性时这部分 PC 零参考，必须自研。**
9. **vivo X300 的 LUT 容器已完整逆向（一手、可复现）**：56 字节头 + 33³ × 3 × float32 LE = 431300 字节；两种 magic `CUBE2BIN8` / `3DLUTBIN8`；资产命名 `p3to2020` / `bt2020top3` 直接暴露其 HDR 交付在 BT.2020 域。
10. **vivo 的 10bit 交付只有字符串级证据**：`YUV_FMT_P010`、`bit_depth_luma_minus8=`、`bt2020FullYuv2Rgb10bit`（GLSL 全文可读）、`XDR_GAIN_MAP` 等来自 `strings -a dex/*.dex`；native `.so` 字符串已被剥离，无法读成代码逻辑。

---

## ② PC 编码/交付链事实

### 2.1 HEIF：存在，但只是 8bit 容器

- 唯一 HEIF 写实现：`app/src/main/java/com/hinnka/mycamera/gallery/HeicExportEncoder.kt`（全文 80 行）。
  - 能力探测 `:21-32`：`MediaCodecList(REGULAR_CODECS).codecInfos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, true) } }` → **只查"有没有 HEIC 编码器"，不查 profile / 位深 / HDR 能力**。这是 PC 进不了 10bit 的第一道门。
  - 实际写入 `:48-66`：`HeifWriter.Builder(path, w, h, HeifWriter.INPUT_MODE_BITMAP)`，链上只有 `setQuality`、`setMaxImages(1)`、`setPrimaryIndex(0)`、`setGridEnabled(true)`，然后 `addBitmap(bitmap)` → **没有 `setBitDepth`、没有 HEVC Main10 profile、没有 HDR/色彩空间声明**（该 API 族也不存在对应 setter，说明 `INPUT_MODE_BITMAP` 这条路本身就到不了 10bit）。
  - gainmap 附加条件 `:44-46`：`if (SDK_INT >= UPSIDE_DOWN_CAKE && gainmapResult != null) bitmap.gainmap = gainmapResult.gainmap` → HDR 增益只在 API 34+ 通过 `Bitmap.gainmap` 走系统通道，PC 自己不写 gainmap 元数据。超时常量 `:16 ENCODE_TIMEOUT_MS = 10_000L`。
- 内部原片写 HEIC：`gallery/GalleryManager.kt:942-975 writeInternalOriginalPhoto(...)`；文件名 `:131 HIGH_QUALITY_PHOTO_FILE = "original.heic"`；质量 `:133 INTERNAL_HEIC_QUALITY = 100`（写死满分质量，无位深概念）；失败回退 `:974` → JPEG 兜底。对外导出 `:1743-1749`。

### 2.2 JPEG 侧（PC 真正的"高保真交付"在 JPEG）

- `gallery/Jpeg444ExportEncoder.kt:318-330` 是色彩空间标签最完整的地方：`{EXTENDED_SRGB, LINEAR_EXTENDED_SRGB, LINEAR_SRGB}` → `UHDR_CG_BT_709`；`{DISPLAY_P3, DCI_P3}` → `UHDR_CG_DISPLAY_P3`；`{BT2020, BT2020_HLG, BT2020_PQ}` → `UHDR_CG_BT_2100`。常量块我只核到 `:339 private const val UHDR_CG_DISPLAY_P3 = 1`（见 ⑦）。
- `Bitmap.CompressFormat.JPEG` 直接编码点：`viewmodel/GalleryViewModel.kt:678`、`:4526-4527`、`ui/camera/PuzzleScreen.kt:1109`。

### 2.3 设置项与持久化键

| 项 | 证据 |
|---|---|
| `settings_use_heic_export` = "HEIC Export" / "Prefer native HEIC export on supported devices." | `res/values/strings.xml:475-476` |
| `settings_use_jpeg_444_export_description`：\"Files are larger, HEIC export is disabled, and Ultra HDR gain maps are preserved when available.\" | `res/values/strings.xml:478` |
| `settings_ultra_hdr_gain_map` = "Ultra HDR" / "Generate Ultra HDR output for photos captured in Professional mode." | `res/values/strings.xml:994-995` |
| `useHeicExport: Boolean = false`（key `USE_HEIC_EXPORT`，读 `:627`，写 `:2206`） | `data/UserPreferencesRepository.kt:221` |
| `applyUltraHDR: Boolean = false`（key `APPLY_ULTRA_HDR`，读 `:868`，写 `:2301`） | `:233` |
| `ultraHdrGainMapEnabled: Boolean = false`（key `ULTRA_HDR_GAIN_MAP_ENABLED`，读 `:914`，写 `:2438`） | `:259` |
| UI 入口 | `ui/settings/SettingsScreen.kt:586,1480-1489`；状态 `:368,372` |

**关键否定证据**：`grep -iE "heic|heif|10 ?bit|ultra ?hdr|hdr" res/values/strings.xml` 的 25 条命中里**没有任何一条提到位深**。→ PC 在 UI 层就**未实现 10bit 选项**。

### 2.4 PC "最接近 10bit" 的实现形态

按证据链，PC 距 10bit HEIF 只差最后一步，具体断点如下：

```
GL_RGBA16F 中间/交付纹理（RawDemosaicProcessor.kt:6630,6660,6713,6830,6866,6914,6968,7010）
  → readPixels() → createBitmap(w,h,Bitmap.Config.RGBA_F16, colorSpace)   [:9124]
  → HeifWriter.INPUT_MODE_BITMAP + addBitmap(bitmap)                       [HeicExportEncoder.kt:52,64]
  → ✗ 无位深/profile 声明，落盘位深由系统编码器决定
```

---

## ③ PC 工作空间与矩阵链

### 3.1 `ColorSpace` 枚举全部取值

`app/src/main/java/com/hinnka/mycamera/raw/ColorSpace.kt:6-93`，12 个取值，每个由 8 个色度坐标 `(xr,yr,xg,yg,xb,yb,xw,yw)` 定义。关键取值：

| 枚举值 | 行 | R | G | B | W |
|---|---|---|---|---|---|
| `SRGB` | :12-17 | 0.640,0.330 | 0.300,0.600 | 0.150,0.060 | 0.3127,0.3290 |
| `DCI_P3` | :18-23 | 0.680,0.320 | 0.265,0.690 | 0.150,0.060 | 0.314,0.351 |
| **`BT2020`** | **:24-29** | **0.708,0.292** | **0.170,0.797** | **0.131,0.046** | **0.3127,0.3290** |
| `ACES_AP1` | :54-59 | 0.713,0.293 | 0.165,0.83 | 0.128,0.044 | 0.32168,0.33767 |
| `ProPhoto` | :60-65 | 0.734699,0.265301 | 0.159597,0.840403 | 0.036598,0.000105 | 0.345704,0.358540 |
| **`HNCS`** | **:87-92** | *同 ProPhoto* | *同 ProPhoto* | *同 ProPhoto* | *同 ProPhoto* |

其余 6 个（`VGamut` :30-35、`ARRI4` :36-41、`AppleLog2` :42-47、`S_GAMUT3_CINE` :48-53、`RED` :66-71、`FilmLightEGamut` :72-77）为相机厂商/Log 空间，与下一期无关，未逐一列出。

- `BT2020` 坐标与 ITU-R BT.2020 标准一致（任务关键：可直接用于我们的 Rec.2020 线性工作空间）。
- **`HNCS` 的色度坐标与 `ProPhoto` 逐位相同**（:60-65 vs :87-92），但作为独立枚举存在。`ColorSpace.kt:78-86` 的注释说明其来源：`CRawColorCorrection::GetColorSpaceChangeMatrix` 的 RGB-to-XYZ(D50) 矩阵 `[0.79767,0.13519,0.03134; 0.28804,0.71188,0.00009; 0,0,0.82491]`，并解释"独立枚举身份防止 HNCS 相机映射在无关的 ProPhoto 处理契约里被求值"。
- **HNCS 在矩阵计算时绕过色度坐标**：`raw/RawDemosaicProcessor.kt:7589-7592`
  ```kotlin
  private fun computeXyzD50ToGamut(colorSpace: ColorSpace): FloatArray? {
      if (colorSpace == ColorSpace.HNCS) {
          return invertMatrix3x3(HncsProfileManager.HNCS_RGB_TO_XYZ_D50)
      }
      val primaries = colorSpace.primaries   // :7593
  ```
  → HNCS 用专用常量矩阵；其余 11 个空间都由 `primaries` + `whitePoint` 现场推导。

### 3.2 工作空间 → 输出空间：运行时推导，无硬编码 mat3

`raw/RawDemosaicProcessor.kt:7579-7587`：

```kotlin
val workingFromXyz = computeXyzD50ToGamut(workingSpace) ?: return identityMatrix3x3()
val xyzFromWorking = invertMatrix3x3(workingFromXyz) ?: return identityMatrix3x3()
val outputFromXyz  = computeXyzD50ToGamut(outputSpace)  ?: return identityMatrix3x3()
return multiplyMatrix3x3(outputFromXyz, xyzFromWorking)
```

- 中转空间是 **XYZ D50**（函数名与 `computeXyzD50ToGamut` 明示）。
- `computeXyzD50ToGamut` 由 `primaries`/`whitePoint` 构造 mS 矩阵（`:7606-7607` 起，`xr/yr, xg/yg, xb/yb`）→ **BT2020→sRGB 之类的转换是"经 D50 XYZ 中转"推导出来的**。
- ⚠ **同一实现在 `raw/RawToneMappingGl.kt:60-68` 又存在一份逐字相同的拷贝**（含 `:70-74 identityMatrix3x3`）。→ 双份重复实现，是我们**必须避免照抄**的设计缺陷。
- 是否使用 Bradford 色适应，未读取完整实现 → 见 ⑦。

### 3.3 完整矩阵链（从 RAW 到显示/交付）

| # | 阶段 | 证据 |
|---|---|---|
| 0 | 相机 RAW + ColorMatrix / ForwardMatrix → ProPhoto 或 WB-camera-RGB | `RawDemosaicProcessor.kt:3031 EquivalentCameraCalibration.sourceToProPhoto`、`:3046-3049 targetCameraColorTransform.sensorToProPhoto`、`:3026 sourceColorCorrectionMatrix` |
| 1 | `profileToEngineTransform`（profile 域 → 引擎工作空间） | uniform `RawEngineTonePass.kt:433`；应用 `:150 uProfileToEngineTransform * exposedProfileColor`；Filmic 变体 `raw/DarktableFilmicHighlightReconstructionAlgorithm.kt:61 uProfileToEngineTransform * (color * uProfileExposureLinearGain)` |
| 2 | 引擎 tone 曲线 → `uOutputTransform`（工作空间 → 输出空间） | uniform 块 `RawEngineTonePass.kt:526-529`；应用点 AgX `raw/AgXToneAlgorithm.kt:254`、Sigmoid `raw/DarktableSigmoidToneAlgorithm.kt:128`、Filmic `raw/DarktableFilmicToneAlgorithm.kt:385`、Spektrafilm `raw/SpektrafilmToneAlgorithm.kt:77`、AdobeCurve `raw/AdobeCurveToneAlgorithm.kt:393` |
| 3 | **线性 RGB 输出契约** | `RawEngineTonePass.kt:493-503`（见 ④.2） |
| 4 | 唯一显示编码（linear → sRGB） | `raw/RawSrgbPass.kt:66-81`；调用点 `RawDemosaicProcessor.kt:7338-7354`；实例 `:1412 private val srgbPass = RawSrgbPass(fullscreenQuad)` |
| 5 | 交付 Bitmap（16F + ColorSpace 对象） | `RawDemosaicProcessor.kt:9109-9124` |

**BT2020 ↔ sRGB / ProPhoto 的转换点位置**：**不在任何固定位置**，而是
- 输入侧：由 `profileToEngineTransform` 一次性吸收（矩阵值 = `computeWorkingToOutputTransform(profileSpace, engineWorkingSpace)`）；
- 输出侧：由 `uOutputTransform` 一次性吸收（`computeWorkingToOutputTransform(engineWorkingSpace, outputSpace)`）。
- 已查到的实例化点：`RawDemosaicProcessor.kt:509-511 computeWorkingToOutputTransform(warmupColorEngine.workingColorSpace, ColorSpace.SRGB)`。
- 小结：`BT2020` ↔ `SRGB` 之间**没有任何硬编码 mat3 常量**，矩阵全部运行时推导。→ **没有矩阵数值可抄，可抄的是算法**。

### 3.4 引擎工作空间 / 默认 EV 全表（`raw/RawRenderingEngine.kt`）

| 引擎 | `shaderId` | `workingColorSpace` | `defaultEv` | domain | 行 |
|---|---|---|---|---|---|
| `AgX` | 1 | **`BT2020`** | **0.7** | Linear | :50-55 |
| `Spektrafilm` | 2 | **`ProPhoto`** ⚠ | **0.7** | Linear | :56-61 |
| `DarktableSigmoid` | 3 | **`BT2020`** | **0.7** | Linear | :62-67 |
| `DarktableFilmic` | 4 | **`BT2020`** | **0.7** | Linear | :68-73 |
| `AdobeCurve` | 0 | `ProPhoto` | 0 | **Curve** | :16-21 |
| `Hncs` | 6 | `HNCS` | 0 | Linear | :22-27 |
| `Lumix` | 7 | `SRGB` | 0 | Linear | :28-35 |
| `Canon` | 8 | `SRGB` | 0 | Linear | :36-42 |
| `Leica` | 10 | `SRGB` | 0 | Linear | :43-49 |

- 常量 `RawRenderingEngine.kt:3 const val RAW_RENDERING_ENGINE_DEFAULT_EXPOSURE_EV = 0.7f`。
- 辅助谓词 `:88-89 usesCameraInputDomain = isLumix || isHncs || isCanon || isLeica`（四个"消费相机域输入"的引擎）。
- 持久化兼容 `:91-100 fromPersistedName`：旧值 `"HncsCcm"` / `"HncsLut"` 都折叠到 `Hncs`。
- ⚠ `shaderId` 与枚举顺序**不一致**（`Hncs=6` 却排第 2，`Leica=10` 排第 6）→ 跨版本持久化脆弱，不建议照抄。

---

## ④ PC OETF / 位深

### 4.1 `linearToSrgb` 的两处定义（逐字相同）

**A. `raw/RawEngineTonePass.kt:438-448`** —— 定义了但**在 `main()` 里未被调用**（该文件 `main()` 见 4.2，直接输出 `color`）。

**B. `raw/RawSrgbPass.kt:66-76`** —— 与实际使用点同一文件，**与 A 逐字相同**（12.92 / 1.055 / 2.4 / 0.0031308 精确分段）：

```glsl
void main() {
    vec3 color = texture(uInputTexture, vTexCoord).rgb;
    // Retain the former UNORM target's SDR range without its 8-bit quantization.
    fragColor = vec4(clamp(linearToSrgb(color), 0.0, 1.0), 1.0);
}
```

→ **精确 sRGB 分段 OETF**（12.92 线性段 + 1.055/2.4/0.0031308 幂段），**不是 `pow(x,1/2.2)` 近似**。
→ `:80` 的注释"Retain the former UNORM target's SDR range **without its 8-bit quantization**"是 PC 自己承认：**这里曾是 8bit UNORM 目标，现在只保留 SDR 范围限制、去掉了 8bit 量化**。
→ 但 `clamp(..., 0.0, 1.0)` **硬截断到 SDR 范围**：超过 1.0 的高光在此丢失。这是"10bit/HDR 高位深路径必须替换掉的最后一道 SDR 闸门"。

### 4.2 显示编码只做 1 次（架构性保证）

`raw/RawEngineTonePass.kt:493-503` 的 `main()`：`prepareEngineInput` → `applyEngineTone` → `$shadowsHighlightsApply` → `applyBlackWhiteLevels` → `fragColor = vec4(color, 1.0)`，并附契约注释：

```glsl
    // Engine tone and adjustment passes have a linear-RGB output contract.
    // RawSrgbPass performs the single display encoding after all
    // linear-domain adjustments are complete.
```

→ **引擎 tone pass 与后续线性域调整（shadows/highlights、blacks/whites）全部在线性域完成，OETF 恰好一次。** `LumixToneAlgorithm.kt:50` 的注释重申同一纪律："linear sRGB and encodes once in RawSrgbPass. Decode here so neutral editing..."。

### 4.3 唯一的 OETF 近似违纪点（HNCS 专属）

`raw/RawEngineTonePass.kt:279-292`（`hdrBaseCurveFragmentShader`）：

```kotlin
val engineOutputToLinearSrgb = if (isHncs) {
    "uHdrEngineToLinearSrgb * pow(max(engineOutput, vec3(0.0)), vec3(2.2))"
} else { "engineOutput" }
```

- `:296` 声明 `uniform mat3 uHdrEngineToLinearSrgb;`，`:301-303` 包装成 `hdrEngineOutputToLinearSrgb(engineOutput)`。
- → **HNCS 在此路径用 2.2 幂近似解码**，非 HNCS 引擎则把自带输出**直接当线性**用。同一函数两条语义。
- **推断**：2.2 幂与精确 sRGB 分段在暗部（<0.0031308）差异最大（分段走 12.92 线性段），会造成暗部偏差；且这是"HDR 参考缓冲"链路，误差会传导到 gainmap。
- 该矩阵的具体数值来源未取证 → 见 ⑦。

### 4.4 位深事实

**内部（16bit half-float）**：`raw/RawDemosaicProcessor.kt` 全链路 —— `:5274`、`:6630`、`:6660`、`:6830-6835`、`:6866`、`:6968`、`:7010-7015` 均为 `GL_RGBA16F`；`:6713-6714`、`:6914-6915` 为 `glTexImage2D(..., GL_RGBA16F, ..., GL_RGBA, GL_HALF_FLOAT, null)`；`:8998` 为 `glReadPixels(..., GL_RGBA, GL_HALF_FLOAT, readback)`。

**交付 Bitmap（16F + ColorSpace 对象）**：`:9109-9124` 的 `readPixels(width, height, colorSpace, label)` 内 `bitmap = createBitmap(width, height, Bitmap.Config.RGBA_F16, colorSpace = colorSpace)`（`:9124`）。

- tiled 路径的目标分配：SDR 目标用 `workingColorSpace`（`:4375-4381`），HDR 参考目标用 `hdrColorSpace`（`:4386-4393`），后者初值见 `:4370 android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB`。
- **唯一的 8bit 回读是旧自动曝光预览，与成片无关**：`:8939-8952 readLegacyExposurePreviewFrame` 用 `GL_UNSIGNED_BYTE` + `IntArray` 手工组 ARGB（`:8945-8951`）。

**HDR/gainmap 域**：
- `hdr/GainmapSource.kt:11-13 enum class HdrBufferEncoding { LINEAR_SRGB }`、`:15-17 enum class LuminanceGainMapEncoding { LINEAR_RATIO }` —— **两者都只有一个取值**。
- `gallery/PhotoProcessor.kt:336-344` 用它标注 HDR 参考：`encoding = HdrBufferEncoding.LINEAR_SRGB, description = "raw_linear_srgb_selected_engine_highlight_reference"`。
- `hdr/GpuReferenceGainmapProducer.kt:54-62`：非 `LINEAR_SRGB` 输入**直接拒绝**（`"Rejecting RAW gainmap input: expected=..., actual=..."`），并要求 `ColorSpace.Named.LINEAR_EXTENDED_SRGB`；`:543-545` 硬编码 BT.709 亮度系数 `const vec3 LINEAR_SRGB_LUMA = vec3(0.2126, 0.7152, 0.0722);`。
- → **PC 的整个 HDR 侧锚死在 linear sRGB + BT.709 亮度系数，没有任何 Rec.2020 锚点。**

### 4.5 曝光补偿域与"引擎切换亮度差"机制

- EV → 线性增益（唯一换算点）：`raw/RawProfileExposureGl.kt:56-65` —— `:58 val exposureEv = profileExposureCompensation + dngBaselineExposure + dcpBaselineExposureOffset`；`:59 val linearGain = 2.0f.pow(exposureEv)`。
- uniform 与应用点：`raw/RawEngineTonePass.kt:532 uniform float uProfileExposureLinearGain;` → `:401 return color * uProfileExposureLinearGain;`（包在 `prepareProfileGainInput` 里，`:394-404` 有引擎分支版本）。绑定：`raw/RawProfileExposureGl.kt:154-157`、`raw/RawRenderingEngineToneAlgorithm.kt:444`、`RawDemosaicProcessor.kt:7371,7390`。
- → **`AgX` / `Spektrafilm` / `DarktableSigmoid` / `DarktableFilmic` 四个引擎在渲染前就整体乘 2^0.7**（≈1.6245），其余五个乘 2^0 = 1。**这就是"引擎切换亮度差"的直接机制**。
- 关联数学（同文件）：`:78-79 white = 1f / 2^positiveExposureEv`、`:119 toneSlope = 2.0f.pow(exposureEv)`、`:10 DEFAULT_BLACK_RENDER_DISABLE_BASELINE_EV = 2f` + `:147` 禁用判定、`:161-177 useRamp/rampSlope/rampBlack/rampRadius/rampQScale 绑定。
- **`RawExposureCompensationDomain`（`RawRenderingEngine.kt:5-8`）在数学上是空的**：全 Kotlin 源码 grep 的全部命中都是枚举声明（`RawRenderingEngine.kt:14,20,26,34,41,48,54,60,66,72`）与**日志**（`RawDemosaicProcessor.kt:3770 "engineCompensationDomain=${colorEngine.exposureCompensationDomain}"`）。**没有任何 if/when 按域分叉。**
  → **推断**：PC 的 Curve/Linear 域区分目前只是元数据/日志；或分叉在未 grep 到的位置。**我们的 Curve/Linear 必须真做两条路径，不能照抄这个空壳。**
- 相关日志上下文：`RawDemosaicProcessor.kt:3741 val engineDefaultExposureCompensation = colorEngine.defaultExposureCompensationEv`、`:3768-3770`；`:3875` 注释说明 AdobeCurve 保留 BaselineExposure 给 DNG SDK 曝光 ramp，而 HNCS 消费方式不同。

---

## ⑤ vivo X300 真机事实

样本目录：`/tmp/x300-samples/`（`VivoCamera.apk` 396MB、`vivo-effectengine.apk` 5.6MB、`dex/` 10 个 dex、`assets/` 8 个 LUT、`vivo/vivoapp-libs/` native so、`dex-glsl-dump.txt`、`dex-imageprocess-classes.txt`）。

### 5.1 LUT 容器格式（**一手、完全可复现**）

命令：`xxd -l 48 assets/*.bin` + `python3 struct` 抽检。

- **8 个 .bin 全部恰好 431300 字节** = 56（头）+ 33³ × 3 × 4（float32）= 56 + 431244。**33³ float32 得到确认**。
- **两种 magic（9 字符 ASCII + 3 字节 0 填充到 12 字节边界）**：
  - `CUBE2BIN8`（`43 55 42 45 32 42 49 4e 38`）：`bt2020top3.bin`、`p3to2020.bin`、`p3to2020_preview.bin`、`pd2548_bt2020top3.bin`、`pd2548_p3to2020.bin`、`wm.bin`
  - `3DLUTBIN8`（`33 44 4c 55 54 42 49 4e 38`）：`static_lut_dolby_hlg_preview.bin`、`static_lut_dolby_hlg_preview_x200.bin`
- **头字段布局**（hexdump 直读）：

  | 偏移 | 字节 | 含义 |
  |---|---|---|
  | 0x00–0x08 | `43 55 42 45 32 42 49 4e 38` | magic "CUBE2BIN8"（9 字符）+ `00 00 00` 填充 |
  | 0x0C–0x0F | `21 00 00 00` | **小端 u32 = 33**（网格边长） |
  | 0x10–0x13 | `01 00 00 00` | 小端 u32 = 1（维度/层数标记，语义见 ⑦） |
  | 0x14–0x37 | 全 0 | 保留（合计 56 字节头） |
  | 0x38– | float32 LE × 3 通道 × 33³ | LUT 数据 |

- **语义抽检（python3 struct.unpack）**：
  - `p3to2020.bin`：首项 `(0.0, 0.0, 0.0)`，**末项 `(0.9043, 0.9043, 0.9043)`** —— 与"P3(D65) 白点转到 BT.2020 后三通道相等且 ≈0.9043"一致 → **推断：这是 P3→BT.2020 的 3D 矩阵 LUT**。
  - `static_lut_dolby_hlg_preview.bin`：首项 `(0.001, 0.001, 0.001)`，末项 `(1.0, 1.0, 1.0)`（Dolby/HLG 预览 LUT）。
- → 命名 `p3to2020` / `bt2020top3` / `pd2548_*`（pd2548 疑为机型代号）**直接说明 vivo 的 HDR 交付在 BT.2020 域运行**，且**色彩空间转换被物化成 33³ float32 3D LUT 资产**，而非运行时算矩阵。

### 5.2 10bit / HDR 交付路径（**仅字符串级证据**）

命令：`strings -a dex/*.dex | grep -iE ...`。**注意：native `.so` 字符串已被剥离**（`strings vivo/libImageProcessPost.so` 只输出 `A_UPQIP`/`F]OPQ`/`PQHy`/`YCPQ]` 等乱码），**无法从 native 侧读成代码逻辑**。

**GLSL 片段（dex 里的完整着色器字面量，可从 `strings -a dex/classes6.dex` 读到全文；行号取自 `/tmp/c6.txt`）**：

```glsl
mat3 bt2020FullYuv2Rgb10bit = mat3(
    1.0, 1.0, 1.0,  0.0, -0.164553, 1.881400,  1.474600, -0.571353, 0.0);
...
    vec3 rgb = bt2020FullYuv2Rgb10bit * yuv;
```

- 矩阵数值与 **ITU-R BT.2020 非恒定亮度全范围** YCbCr→RGB 一致（`R = Y + 1.4746·Cr`、`B = Y + 1.8814·Cb`、`G = Y − 0.164553·Cb − 0.571353·Cr`）。
- `#extension GL_EXT_YUV_target : require` + `uniform __samplerExternal2DY2YEXT baseTexture`（`/tmp/c6.txt:38815,38819`）→ 走 EGL 外部 YUV 采样，**10bit YUV 不经 CPU 展开**。

**limited→full 10bit 去偏、P3 转换点与 3D LUT 采样**（`/tmp/c6.txt:38797-38809`）：

```glsl
   originalPixel = limitedYuv2FullRgb10bit * (originalPixel - vec3(0.0625f, 0.5f, 0.5f));
   if(ENABLE_P3 == 1) { fragColor.rgb = bt2020ToP3 * fragColor.rgb; }
   if(ENABLE_USE_3D_LUT == 1) { fragColor.rgb *= vec3((LUT_SIZE-1.0f)/LUT_SIZE, ...); ... }
```

- `- vec3(0.0625, 0.5, 0.5)` 是**视频范围（64/940）→ 全范围**的 10bit 去偏（0.0625 = 64/1024）。
- **转换点受 `ENABLE_P3` 开关控制**：BT.2020 → P3 只在与 P3 设备协商时执行。
- 3D LUT 用 `uniform mediump sampler3D lut3dtex` + `uniform float LUT_SIZE` 硬件三线性采样（scale/offset 半纹素对齐），**未见 tetrahedral 插值代码**。
- 10bit 打包辅助：`getP010Low(y0)` / `getP010High(y0)` 成对出现（`gl_FragColor = vec4(getP010Low(u0), getP010High(u0), ...)`）→ **P010（10bit YUV 4:2:0，MSB/LSB 分装两个 16bit lane）的 GL 打包**。

**字符串级证据清单**（全部来自 `strings -a dex/*.dex`，无法定位到具体类/方法）：

| 类别 | 命中的字符串（均来自 `strings -a dex/*.dex`） |
|---|---|
| P010 / 10bit 通路 | `YUV_FMT_P010`、`VIDEO_P010_BT2020_FULL_FRAGMENT_SHADER`、`VIDEO_P010_BT2020_LIMITED_FRAGMENT_SHADER`、`getP010Low`/`getP010High` |
| **MPEG 位深语法** | `bit_depth_luma_minus8=`、`bit_depth_chroma_minus8=`、`bitDepthLumaMinus8PaddingBits=`、`bitDepthChromaMinus8PaddingBits=`、`pcm_sample_bit_depth_luma_minus1`、`pcm_sample_bit_depth_chroma_minus1` |
| HEIC 容器 | `HeifExifUtil`、`isHeifFormat`、`isHeifHeader`、`IMAGE_HEIC`、`IMAGE_HEIC_SEQUENCE`、`INGEST_SRC_IMAGE_HEIC`、`CAMERA_HEIF_EXTEND_JPEG_FORMAT_DATA`、`setting_heif_image_storage_summary` |
| gainmap | `XDR_GAIN_MAP`、`isGainmap=`、`gainmap_dest`、`GainMap color layers`、`Created GainMap color layer with color: 0x`、`GainMapColorLayerPlugin`（`com/vivo/watermark/plugin/impl/GainMapColorLayerPlugin`） |
| 色彩空间 / EGL / HDR10+ | `EGL_GL_COLORSPACE_BT2020_HLG_EXT`、`EGL_GL_COLORSPACE_BT2020_LINEAR_EXT`、`getBt2020Hlg`、`HDR10_TO_HLG`、`DolbyOrHDR10`、`mBt2020ToXyz`、`convertBetweenColorSpaces`、`convertBetweenColorSpacesX2`、`setHDR10Plus`、`isHDR10PlusOn`、`isMTKHDR10On`、`video_hdr10plus_hint_content_new`、`com.mediatek.streamingfeature.hdr10` |
| 相机能力开关（P010 vs RGB 重处理） | `VIVO_CAPTURE_IS_NEED_P010_OR_RGB`、`productSupportRGBOrP010Reprocess`、`productSupportSoftBrpInP010OrRGB`、`BufferWidthCoefficientForRGBOrP010Reprocess`、`SinglePhysicalCameraDummyIdNumber_RGBOrP010`、`decoder will output to image buffer isP010 = ` |
| 素材类 | `Lcom/vivo/videoeditorsdk/render/YUVP010Reader;`、`Lcom/vivo/imageprocess/ImageProcessRenderEngine;`、`SeqFrameManager` |

**必读警告**：
- `GainMapColorLayerPlugin` 位于 **watermark 插件**包下 → **vivo 的 gainmap 是参与成片合成/渲染的色层**，不是事后附着的元数据（推断）。
- `bit_depth_luma_minus8=` 是通用 MPEG 位深语法字符串，**不能证明静态照片 HEIC 路径确实写 10bit**（也可能只服务 HDR10+ 视频）。**仅字符串级证据**。
- 抽取出的 GLSL 里 `#version 300 es` 出现在代码**之后**（`/tmp/c6.txt:38833`）—— `strings` 拼接多个独立着色器字面量后的**排序假象**，不代表 vivo 源码里 `#version` 位置错误。

---

## ⑥ 对下一期的直接含义

### 6.1 可以直接照抄（形态/算法）

1. **公共工作空间切 Rec.2020 线性的做法 PC 已给全**：`ColorSpace.BT2020` 的 8 个色度坐标（`raw/ColorSpace.kt:24-29`）+ **运行时由 D50 XYZ 推导 working↔output 矩阵**（`raw/RawDemosaicProcessor.kt:7579-7587`）。不需要硬编码任何 mat3 就能得到正确的 Rec.2020 线性工作空间。
2. **"单一显示编码点"架构必须照抄**：引擎 tone pass 输出线性 RGB（契约在 `raw/RawEngineTonePass.kt:499-502`），`RawSrgbPass` 是唯一 OETF 点（`raw/RawSrgbPass.kt:81`）。这把"OETF 次数正确"变成结构性保证，而非依赖纪律。
3. **精确 sRGB 分段 OETF 的参考实现**（`raw/RawSrgbPass.kt:66-76`：12.92 / 1.055 / 2.4 / 0.0031308）。**不要**用 `pow(x, 1/2.2)`。
4. **EV → 线性增益的换算与注入点**（`raw/RawProfileExposureGl.kt:58-59` 的 `2^EV`；`raw/RawEngineTonePass.kt:401` 的 profile gain 阶段整体乘）。形态可抄；但 **`exposureCompensationDomain` 的 Curve/Linear 两条数学路径 PC 是空壳（④.5），必须自己实现。**
5. **HEIC 能力探测的 UX 模式**（`HeicExportEncoder.isSupported` + UI 隐藏开关 `ui/settings/SettingsScreen.kt:586,1480-1489`）。我们的探测必须**扩展到查 profile/位深能力**，而不是只查编码器是否存在。

### 6.2 必须自研（PC 零参考）

1. **10bit HEIF 落盘**。PC 是死路：`HeifWriter.INPUT_MODE_BITMAP` + 无位深声明（`gallery/HeicExportEncoder.kt:48-58`）。必须自己做编码参数协商（HEVC Main10 profile + 显式位深 + `INPUT_MODE_SURFACE`/`BUFFER` 传 10bit 平面）。vivo 的 `bit_depth_luma_minus8` / `YUV_FMT_P010` 说明真机侧确实在 bitstream 层写位深，**但只有字符串级证据，不能作设计依据**。
2. **HEIC 的色彩空间与 gainmap 元数据**。PC 只在 JPEG 侧写 `UHDR_CG_*` 标签（`gallery/Jpeg444ExportEncoder.kt:318-330`），**HEIC 侧完全没有 colr/nclx/gainmap 写入**。
3. **Rec.2020 线性的 HDR 参考锚点与 gainmap 数学**。PC 的 `HdrBufferEncoding` 只有一个取值 `LINEAR_SRGB`（`hdr/GainmapSource.kt:11-13`），gainmap 亮度系数硬编码 BT.709 的 `0.2126/0.7152/0.0722`（`hdr/GpuReferenceGainmapProducer.kt:543-545`）。切 Rec.2020 线性必须同时改：编码枚举值、亮度系数、gainmap 域定义、以及 `GpuReferenceGainmapProducer` 的输入校验（`:54-62`）—— 四处 PC 全要改，等于零参考。
4. **SDR 范围的最后一道闸门**：`raw/RawSrgbPass.kt:81` 的 `clamp(..., 0.0, 1.0)` 是 PC 明文保留的 SDR 限制（注释 `:80` 自认"曾是 UNORM 目标"）。做 10bit HDR 交付时这一处必须换成"不裁剪 + headroom"的编码路径。
5. **LUT 资产格式**。vivo 用 56B 头 + `CUBE2BIN8`/`3DLUTBIN8` + 33³ float32（⑤.1）；PC 用自有二进制（`lut/LutParser.kt:73-77`：version ≥2 带 `curveStorageId`、≥3 带 `colorSpaceOrdinal` 并按 `ColorSpace.entries.getOrElse(ordinal)` 还原 —— **按枚举序号持久化，加值即破坏旧文件**）。两边都不能照抄。

### 6.3 PC 的坑（明确不要照抄）

| 坑 | 位置 | 说明 |
|---|---|---|
| 矩阵函数两份重复实现 | `raw/RawDemosaicProcessor.kt:7579-7587` vs `raw/RawToneMappingGl.kt:60-68`（含 `identityMatrix3x3` 也两份） | 我们的实现必须单一来源 |
| HNCS 与 ProPhoto 色度坐标相同却做两个枚举 | `raw/ColorSpace.kt:60-65` vs `:87-92`，靠 `if (colorSpace == HNCS)` 特判绕过（`RawDemosaicProcessor.kt:7590`） | 易错；应把"色度坐标"与"专用矩阵"显式建模 |
| `shaderId` 与枚举顺序不一致 | `raw/RawRenderingEngine.kt`（Hncs=6 排第 2，Leica=10 排第 5）；LUT 也按枚举序号持久化（`lut/LutParser.kt:76-77`） | 跨版本持久化脆，加枚举值即破坏旧文件 |
| HNCS 用 2.2 幂近似 OETF | `raw/RawEngineTonePass.kt:288-292` | 与精确分段在暗部不一致；若我们有"绑定曲线采样"通道，必须用精确分段 |
| `exposureCompensationDomain` 是死字段 | `raw/RawRenderingEngine.kt:5-8` vs 唯一使用点 `RawDemosaicProcessor.kt:3770`（日志） | Curve/Linear 语义未落地 |
| 引擎工作空间分裂（4 种） | `raw/RawRenderingEngine.kt:10-74` | 切"公共 Rec.2020 线性"意味着推翻这个模型，需评估对 5 个 `SRGB`/`ProPhoto`/`HNCS` 引擎的影响 |

---

## ⑦ 未取证清单

以下条目**本卡未取得一手证据**，并给出可直接复现的取证命令。

| # | 未取证项 | 复现命令 / 方法 |
|---|---|---|
| 1 | `computeXyzD50ToGamut` 的完整色适应实现（是否 Bradford / D50 适应）——只读到 mS 构造起始两行 | `sed -n '7589,7670p' .../raw/RawDemosaicProcessor.kt` |
| 2 | `exposureCompensationDomain` 是否在 native 或未 grep 位置有数学分叉 | `grep -rn "exposureCompensationDomain" /tmp/pc-tone/app/src`（全仓含 native） |
| 3 | `uHdrEngineToLinearSrgb`（`RawEngineTonePass.kt:296`）矩阵的数值来源 | `grep -rn "uHdrEngineToLinearSrgb" /tmp/pc-tone/app/src/main` |
| 4 | **PC HEIC 实际落盘位深（8 或 10）** —— 静态不可判定，取决于系统编码器；本卡禁运行 | 真机 `heif-info` / `exiftool -BitDepth` 或运行后检查 `original.heic` |
| 5 | `UHDR_CG_BT_709` / `UHDR_CG_BT_2100` 字面值（只核到 `UHDR_CG_DISPLAY_P3 = 1`） | `sed -n '335,345p' .../gallery/Jpeg444ExportEncoder.kt` |
| 6 | PC `RawSrgbPass` 是否有 10bit/HDR 变体（只找到 sRGB 单变体，未穷举 `raw/`） | `ls .../raw/ \| grep -iE "pass\|srgb\|p3\|2020"` |
| 7 | vivo HEIC 的 `colr`/`nclx` 标签与 gainmap 版本（ISO 21496-1 还是私有 `XDR_GAIN_MAP`）；**vivo 静态照片 HEIC 是否真为 10bit**（`bit_depth_luma_minus8` 是通用 MPEG 字符串，可能只服务 HDR10+ 视频，**仅字符串级证据**） | 需真机 HEIC 样本 + `exiftool -a -G1`，或反汇编 `libImageProcessPost.so`；静态不可判定 |
| 8 | `mBt2020ToXyz` 的用途、`convertBetweenColorSpacesX2` 的调用点、vivo 相机→HEIC 的完整矩阵链顺序 | 需 dex 反编译定位类/方法；`strings -a` 无法给出归属 |
| 9 | `libImageProcessPost.so` / `libImageProcessPre.so` 的色彩管线逻辑 | **`strings` 输出已被剥离，无法取证**；需反汇编（REA / Ghidra） |
| 10 | vivo LUT 头偏移 0x10 的值 `1` 的确切语义（层数？通道布局？） | 需反汇编 native LUT 解析器，或对比 `bt2020top3`（疑为 3 层）与非 3 层资产头部差异 |

---

> **取证方法附注**：PC 侧用 `grep -n` 定位后 `sed -n 'A,Bp'` / read 精读片段；vivo 侧用 `xxd -l 48` 读 LUT 头、`python3 struct.unpack` 抽检 LUT 数值、`strings -a dex/*.dex`（再落盘到 `/tmp/c6.txt` 后 `grep -n -B6 -A6` 取 GLSL 上下文）。全部引用行号已在收尾时用 `sed -n` 逐条回读校验。
