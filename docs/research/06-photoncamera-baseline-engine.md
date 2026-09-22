# PhotonCamera：RAW → 基础色彩外观这一层，以及用户 3D LUT 的承载方式

> 独立源码取证。所有行号对应本地工作副本
> `/tmp/photoncamera`（`git log -1` = `2557a1d6e5fa0c2017fa4797b3a3557c54137dbe`，`Mon Sep 21 17:40:31 2026 +0800`，
> `origin = https://github.com/bjzhou/PhotonCamera.git`）。
> 已用 `raw.githubusercontent.com/bjzhou/PhotonCamera/{master,main}/...` 抽查本报告引用的 5 个关键文件，均返回 HTTP 200，
> 即这些文件确实存在于上游仓库（**行号仅对本报告写明的本地 commit 有效**）。
> 逐字原文一律保持原样，不翻译。

---

## 0. 结论速览（证据见后文各节）

| 层 | 域（PhotonCamera 实际实现） | 证据 |
|---|---|---|
| 去马赛克后线性相机 RGB | scene-linear（相机原生 RGB） | `RawLinearRcdPass.kt:263` |
| CCM（色彩校正矩阵） | scene-linear | `RawLinearRcdPass.kt:273` |
| DCP HueSatMap（**线性**版） | scene-linear | `RawLinearRcdPass.kt:277-286` |
| 引擎 tone / profile 管线（HueSatMap→LookTable→ToneCurve） | 引擎工作空间 | `AdobeCurveToneAlgorithm.kt:374-393` |
| 引擎输出 gamut 变换 | 线性（纯 gamut 矩阵，无 OETF） | `RawDemosaicProcessor.kt:7544-7552` |
| 显示编码（sRGB 分段 OETF） | 由下游完成（纹理为 sRGB 编码位图） | `PreviewColorShaderModules.kt:8-18`；`RawEngineTonePass.kt:416-425` |
| ColorRecipe（曝光/对比/饱和/vibrance/LCH/三向调色…） | 显示编码 sRGB（各步自行 linear 往返） | `PreviewColorShader.kt:184-281` |
| **用户 3D LUT** | **显示编码 sRGB（非线性）** | `PreviewColorShader.kt:311-328`；`LutImageProcessor.kt:3106-3127` |

关键一句话：**PhotonCamera 的用户 `.cube` LUT 默认吃的是「sRGB 分段 OETF 编码后的显示值」，并在非线性 sRGB 域做混合**；
它把「域标注」放在自研二进制 `.plut` 的头部，而不是 `.cube` 里。

---

## 1. 完整管线顺序（证据）

### 1.1 线性 pass：CCM + 线性 DCP HueSatMap（scene-linear 域）

`app/src/main/java/com/hinnka/mycamera/raw/RawLinearRcdPass.kt:268-287`：

```glsl
                if (uClampProfileRgb != 0) {
                    rgb = min(rgb, max(uCameraWhite, vec3(0.001)));
                }
                rgb = uColorCorrectionMatrix * rgb;
                if (uClampProfileRgb != 0) {
                    rgb = clamp(rgb, vec3(0.0), vec3(1.0));
                }
                if (uLinearDcpHueSatEnabled != 0) {
                    rgb = dngApplyHueSatMap(
                        rgb,
                        uLinearDcpHueSatMap,
                        uLinearDcpHueSatDivisions,
                        uLinearDcpHueSatEncoding,
                        uHueSatSupportOverrange != 0
                    );
                }
                rgb *= uExposureGain;
                fragColor = vec4(rgb, 1.0);
```

- 该 pass 的着色器声明了 `uniform sampler3D uLinearDcpHueSatMap;`（`RawLinearRcdPass.kt:162`）、
  `uniform mat3 uColorCorrectionMatrix;`（`:163`）、`uniform vec3 uCameraWhite;`（`:164`）。
- 顺序明确：**CCM → clamp → DCP HueSatMap → 曝光增益**，全部在去马赛克后的**线性**域。
  该 pass 名为 `RawLinearRcdPass`（"Linear" + RCD 去马赛克），本身即其域标注。

### 1.2 引擎 tone pass：Adobe profile 管线（HueSatMap → LookTable → ToneCurve）

`app/src/main/java/com/hinnka/mycamera/raw/AdobeCurveToneAlgorithm.kt:374-393`：

```glsl
        vec3 applyAdobeProfilePipeline(vec3 color, bool preserveHighlights) {
            color = applyDcpHueSatMap(color, preserveHighlights);
            if (uProfileExposureRampEnabled) {
                color = applyProfileExposureRamp(color, preserveHighlights);
                color = applyDcpLookTable(color, preserveHighlights);
            } else {
                color = applyDcpLookTable(color, preserveHighlights);
                color = applyProfileExposureRamp(color, preserveHighlights);
            }
            return color;
        }

        vec3 applyAdobeProfilePipeline(vec3 color) {
            return applyAdobeProfilePipeline(color, false);
        }
    """.trimIndent()
    val ADOBE_COMBINED_FUNCTIONS = """
        vec3 applyEngineTone(vec3 color) {
            color = applyAdobeCurve(color);
            return uOutputTransform * color;
        }
```

即 **HueSatMap → LookTable（与 ProfileExposureRamp 次序可切换）→ ToneCurve(`applyAdobeCurve`)→ `uOutputTransform`**。
HueSatMap/LookTable 都在 **HSV** 域查表（`AdobeCurveToneAlgorithm.kt:205` `vec3 sampleDcpMap(sampler3D tableTexture, ivec3 divisions, vec3 hsv)`；
`:267` `vec3 applyDcpHsvMap(...)`），与 DNG HueSatMap 的 HSV 插值语义一致。

### 1.3 引擎输出只是 gamut 矩阵，**没有 OETF**

`app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt:7544-7552`：

```kotlin
    private fun computeWorkingToOutputTransform(
        workingSpace: ColorSpace,
        outputSpace: ColorSpace
    ): FloatArray {
        val workingFromXyz = computeXyzD50ToGamut(workingSpace) ?: return identityMatrix3x3()
        val xyzFromWorking = invertMatrix3x3(workingFromXyz) ?: return identityMatrix3x3()
        val outputFromXyz = computeXyzD50ToGamut(outputSpace) ?: return identityMatrix3x3()
        return multiplyMatrix3x3(outputFromXyz, xyzFromWorking)
    }
```

纯 3×3 色彩空间（gamut）变换，不含任何传递函数。
`RawEngineTonePass.kt:416-425` 定义了 `vec3 linearToSrgb(vec3 color)`（标准 sRGB 分段，`0.0031308` 拐点、`1.055/2.4/-0.055`），
说明**显示编码是独立于 tone mapping 的另一个职责**。

> 注：`RawEngineTonePass.kt` 中 `linearToSrgb` 是否在其 `main()` 中被调用，本次未逐行确认（`grep -n "linearToSrgb"` 只命中定义行 416）。
> 因此「引擎 pass 内部是否自带输出编码」标记为**待确认**；但 PhotonCamera 的 LUT 着色器一律先把输入 `srgbToLinear(...)`
> （`PreviewColorShader.kt:317`），这本身就是「进入该着色器的纹理是 sRGB 编码」的直接证据。

### 1.4 引擎是**可插拔枚举**，每个引擎自带 workingColorSpace

`app/src/main/java/com/hinnka/mycamera/raw/RawRenderingEngine.kt:10-70`（节选）：

```kotlin
enum class RawRenderingEngine(
    val shaderId: Int,
    val workingColorSpace: ColorSpace,
    val defaultExposureCompensationEv: Float,
    val exposureCompensationDomain: RawExposureCompensationDomain
) {
    AdobeCurve(
        shaderId = 0,
        workingColorSpace = ColorSpace.ProPhoto,
        defaultExposureCompensationEv = 0f,
        exposureCompensationDomain = RawExposureCompensationDomain.Curve
    ),
    Hncs(
        shaderId = 6,
        workingColorSpace = ColorSpace.HNCS,
        ...
    ),
    Lumix(
        shaderId = 7,
        // Output/adjustment space only: calibration maps source WB camera RGB
        // to S9 camera RGB before the PhotoStyle curve/LUT, without sRGB encoding.
        workingColorSpace = ColorSpace.SRGB,
        ...
    ),
    Canon(
        shaderId = 8,
        // Output/adjustment space; the renderer owns its Picture Style input domain.
        workingColorSpace = ColorSpace.SRGB,
        ...
    ),
    AgX(shaderId = 1, workingColorSpace = ColorSpace.BT2020, ...),
    Spektrafilm(shaderId = 2, workingColorSpace = ColorSpace.ProPhoto, ...),
    DarktableSigmoid(shaderId = 3, workingColorSpace = ColorSpace.BT2020, ...),
```

Lumix 与 Canon 的注释是**「相机厂商外观做成引擎」的直接自述**：
厂商外观由「标定 + PhotoStyle 曲线/LUT」构成，且渲染器自己拥有该 LUT 的输入域，**不经 sRGB 编码**。

同时 `raw/` 目录下存在各厂商/各理论的独立算法实现（文件清单 `ls app/src/main/java/com/hinnka/mycamera/raw/`）：
`AgXToneAlgorithm.kt`、`DarktableSigmoidToneAlgorithm.kt`、`DarktableFilmicToneAlgorithm.kt`、
`CanonToneAlgorithm.kt`、`CanonIccProfile.kt`、`LumixToneAlgorithm.kt`、`SpektrafilmToneAlgorithm.kt`、
`HncsToneAlgorithm.kt`、`ACR3Curve.kt`、`DcpProfile.kt`、`DcpHueSatMapGl.kt`、`DngProfileToneCurve.kt`。

---

## 2. 用户 3D LUT 的应用域（核心证据）

### 2.1 预览着色器：线性化 → gamut 矩阵 → 曲线编码 → 采样 → 在非线性 sRGB 混合

`app/src/main/java/com/hinnka/mycamera/lut/PreviewColorShader.kt:311-329`：

```glsl
                if (uLutEnabled && uLutIntensity > 0.0) {
                    vec3 lutInColor;
                    float effectiveLutIntensity = uLutIntensity;
                    if (uVideoLogEnabled) {
                        lutInColor = color.rgb;
                    } else {
                        vec3 linearRGB = srgbToLinear(max(color.rgb, vec3(0.0)));
                        effectiveLutIntensity *= lutMaskWeight(uLutMaskType, linearRGB);
                        ${if (needsLogInput) "linearRGB = prepareLutLinearInput(linearRGB, uLutCurve);" else ""}
                        vec3 colorSpaceRGB = applyLutColorSpace(linearRGB, uLutColorSpace);
                        lutInColor = applyLutCurve(colorSpaceRGB, uLutCurve);
                    }
                    float scale = (uLutSize - 1.0) / uLutSize;
                    float offset = 1.0 / (2.0 * uLutSize);
                    vec3 lutCoord = lutInColor * scale + offset;
                    vec4 lutColor = texture(uLutTexture, lutCoord);
                    // 保留大于 1.0 的混合权重，沿 LUT 色差外推以支持最高 200% 强度。
                    color.rgb = mix(color.rgb, lutColor.rgb, effectiveLutIntensity);
                    color.rgb = sanitizeColor(color.rgb);
                }
```

### 2.2 导出（bake）路径把同一件事写得更直白

`app/src/main/java/com/hinnka/mycamera/lut/LutImageProcessor.kt:3106-3127`：

```glsl
                // === LUT 处理（在色彩配方之后） ===
                if (uLutEnabled && uLutIntensity > 0.0) {
                    bool isP3 = (uInputColorSpace == 1);
                    vec3 linearInput = srgbToLinear(color.rgb);
                    vec3 lutLinearInput = prepareLutLinearInput(linearInput, uLutCurve);
                    ...
                    vec3 colorSpaceRGB = applyLutColorSpace(lutLinearInput, uLutColorSpace);
                    vec3 lutInColor = applyLutCurve(colorSpaceRGB, uLutCurve);
                    
                    float scale = (uLutSize - 1.0) / uLutSize;
                    float offset = 1.0 / (2.0 * uLutSize);
                    vec3 lutCoord = lutInColor * scale + offset;
                    vec4 lutColor = texture(uLutTexture, lutCoord);

                    // 在非线性 sRGB 空间混合；超过 1.0 时沿 LUT 色差外推，支持最高 200% 强度。
                    vec3 srgbColor = linearToSrgb(linearInput);
                    color.rgb = mix(srgbColor, lutColor.rgb, effectiveLutIntensity);
```

两条注释逐字确认：**「LUT 处理（在色彩配方之后）」**、**「在非线性 sRGB 空间混合」**。
即 LUT **在 ColorRecipe 与 tone curve 之后**，混合发生在 **display-encoded（非线性）sRGB** 域。

### 2.3 传递函数核心是**标准 sRGB 分段**，不是纯幂

`app/src/main/java/com/hinnka/mycamera/lut/PreviewColorShaderModules.kt:8-18`：

```glsl
        vec3 linearToSrgb(vec3 l) {
            vec3 absL = abs(l);
            vec3 result = mix(absL * 12.92, 1.055 * pow(absL, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, absL));
            return sign(l) * result;
        }

        vec3 srgbToLinear(vec3 c) {
            vec3 absC = abs(c);
            vec3 result = mix(absC / 12.92, pow((absC + 0.055) / 1.055, vec3(2.4)), step(0.04045, absC));
            return sign(c) * result;
        }
```

`app/src/main/java/com/hinnka/mycamera/color/TransferCurve.kt:29-33` 中 `SRGB` 的参数同样是分段形式：

```kotlin
    SRGB(
        0, 0,
        1.055f, -0.055f, 0.41666667f, 0.0f, 12.92f, 0.0f,
        0.0031308f, 0.04045f, 0.46135f, 1.0f, 2
    ),
```

`type = 2` 走 `a * reflection^c + b`（`TransferCurve.kt:103-105`），即 `1.055·x^(1/2.4) − 0.055`。

---

## 3. `.cube` 的 DOMAIN_MIN / DOMAIN_MAX 在 PhotonCamera 里怎么用

`app/src/main/java/com/hinnka/mycamera/lut/CubeLutParser.kt:148-157`：

```kotlin
    /**
     * 将值标准化到 [0, 1] 范围
     */
    private fun normalizeValue(value: Float, min: Float, max: Float): Float {
        return if (min == 0f && max == 1f) {
            value.coerceIn(0f, 1f)
        } else {
            ((value - min) / (max - min)).coerceIn(0f, 1f)
        }
    }
```

调用点 `CubeLutParser.kt:62-64 / 88-90`，每条数据行都被 `normalizeValue(values[i], domainMin[i], domainMax[i])` 归一化。

**结论：DOMAIN_MIN/MAX 被当作「输入域标注」使用——用来把 LUT 的表格值线性重映射进 [0,1]，
之后就丢失了**（`LutConfig` 只保留 `size/data/byteBuffer/title/configDataType/curve/colorSpace/outputColorSpace`，
见 `LutConfig.kt:18-27`，**没有 domain 字段**）。这等价于假定 LUT 的采样域永远是 [0,1] 归一化后的立方体。

> 实现缺陷（本次核实的顺带发现，供参考）：`domainMin/domainMax` 的默认值在数据行之前是 `0/1`，
> 若 `.cube` 把 `DOMAIN_MIN/MAX` 写在数据行**之后**，先被暂存的 `tempDataList`（`CubeLutParser.kt:36, 61-65`）
> 会用旧 domain 归一化；且当 `min == 0f && max == 1f` 时直接 `coerceIn`（不区分"显式写了 0 1"与"没写"）。
> 规范要求 DOMAIN 出现在数据之前，故实务影响有限，但这是「把 domain 只用于归一化」这一设计的副作用。

---

## 4. LUT「colorspace / trc 域字段」在 PhotonCamera 里如何生效

这是 PhotonCamera 最值得 FilmKit 借鉴的部分：**域是显式的 uniform，并且有配套的持久化载体**。

### 4.1 两个 uniform

`PreviewColorShader.kt:35-37`：

```glsl
            uniform int uLutMaskType;
            uniform int uLutCurve;
            uniform int uLutColorSpace;
```

### 4.2 曲线编码（trc）分支

`PreviewColorShaderModules.kt:287-325`（`EXTENDED_LUT_CURVES` 节选）：

```glsl
        vec3 applyLutCurve(vec3 l, int curveType) {
            if (curveType == 0) {
                return linearToSrgb(l);
            }
            if (curveType == 1) return l;
            if (curveType == 2) {
                return mix(5.6 * l + 0.125, 0.241514 * log10(l + 0.00873) + 0.598206, step(0.01, l));
            }
            if (curveType == 3) {
                return mix((l * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0, (420.0 + log10((l + 0.01) / (0.18 + 0.01)) * 261.5) / 1023.0, step(0.01125, l));
            }
            ...
            return l;
        }
```

对照 `TransferCurve.kt:14-83`，`shaderId` 与曲线一一对应：
`0=SRGB, 1=LINEAR, 2=VLOG, 3=SLOG3, 4=FLOG2, 5=LOGC4, 6=APPLE_LOG, 7=HLG, 8=ACES_CCT, 9=LOG3G10, 10=LLOG`。

### 4.3 gamut 矩阵

`PreviewColorShaderModules.kt:334-362`：

```glsl
        vec3 applyLutColorSpace(vec3 rgb, int colorSpace) {
            if (colorSpace == 0) return rgb;
            if (colorSpace == 1) {
                return mat3(0.875905, 0.035332, 0.016382, 0.122070, 0.964542, 0.063767, 0.002025, 0.000126, 0.919851) * rgb;
            }
            if (colorSpace == 2) {
                return mat3(0.627404, 0.069097, 0.016391, 0.329283, 0.919540, 0.088013, 0.043313, 0.011362, 0.895595) * rgb;
            }
            ...
            if (colorSpace == 6) {
                return mat3(0.613083, 0.070004, 0.020491, 0.341167, 0.918063, 0.106764, 0.045750, 0.011934, 0.872745) * rgb;
            }
```

`colorSpace == 6` 的矩阵即常见的 **sRGB → ACES AP1** 线性矩阵；
`colorSpace == 2` 的矩阵即 **sRGB → Rec.2020**。因此 `applyLutColorSpace` 语义是
**把 sRGB 线性数据转到 LUT 所声明的 gamut**，调用顺序是「先 `srgbToLinear` → 矩阵 → 曲线编码」（`PreviewColorShader.kt:317-321`），
即 **线性 → 换基 → 编码**，顺序正确。

id 映射见 `app/src/main/java/com/hinnka/mycamera/lut/LutShaderMappings.kt:11-27`：

```kotlin
    fun colorSpaceId(colorSpace: ColorSpace): Int {
        return when (colorSpace) {
            ColorSpace.SRGB -> 0
            ColorSpace.DCI_P3 -> 1
            ColorSpace.BT2020 -> 2
            ColorSpace.ARRI4 -> 3
            ColorSpace.AppleLog2 -> 4
            ColorSpace.ProPhoto -> 0
            ColorSpace.HNCS -> 0
            ColorSpace.S_GAMUT3_CINE -> 5
            ColorSpace.ACES_AP1 -> 6
            ColorSpace.VGamut -> 7
            ColorSpace.RED -> 9
            ColorSpace.FilmLightEGamut -> 0
        }
    }
```

> 注意：`ProPhoto / HNCS / FilmLightEGamut` 都映射到 **0（恒等）**。也就是说这些工作空间里的引擎 LUT
> **不会做任何换基**——它们的"域"由引擎自身实现承担（见 §1.4 注释），这一点在结论中很重要。

### 4.4 域的**默认值**：sRGB / sRGB

`app/src/main/java/com/hinnka/mycamera/lut/LutConfig.kt:18-27`：

```kotlin
data class LutConfig(
    val size: Int,
    val data: FloatArray? = null,
    val byteBuffer: ByteBuffer? = null,
    val title: String = "",
    val configDataType: Int = CONFIG_DATA_TYPE_UINT8,
    val curve: TransferCurve = TransferCurve.SRGB,
    val colorSpace: ColorSpace = ColorSpace.SRGB,
    val outputColorSpace: android.graphics.ColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),
) {
```

`LutShaderMappings.transferCurveId` 同样是 `curve ?: TransferCurve.SRGB`（`LutShaderMappings.kt:8-10`）。
→ **`.cube` 被导入时，默认按「sRGB 编码 + sRGB gamut」解释**。

### 4.5 域标注的持久化载体：自研 `.plut`，**不是 `.cube`**

`app/src/main/java/com/hinnka/mycamera/lut/LutParser.kt:20-37`：

```kotlin
    /** Read only the binary header when building filter lists; do not load 3D textures. */
    fun readInputEncoding(inputStream: InputStream): Pair<TransferCurve, ColorSpace> {
        val input = java.io.DataInputStream(inputStream)
        fun readInt() = Integer.reverseBytes(input.readInt())
        require(readInt() == MAGIC_PLUT) { "Expected a PLUT header" }
        val version = readInt()
        require(version in 1..4) { "Unsupported PLUT version: $version" }
        require(readInt() > 1) { "Invalid LUT size" }
        require(readInt() in 0..1) { "Unsupported LUT data type" }
        val curve = if (version >= 2) {
            val id = readInt()
            requireNotNull(TransferCurve.entries.firstOrNull { it.storageId == id })
        } else TransferCurve.SRGB
        val gamut = if (version >= 3) {
            requireNotNull(ColorSpace.entries.getOrNull(readInt()))
        } else ColorSpace.SRGB
        return curve to gamut
    }
```

调用点 `LutManager.kt:216-222`：

```kotlin
            val encoding = if (lut.fileName.isBlank()) null else runCatching {
                val input = if (lut.isBuiltIn) context.assets.open(lut.fileName)
                    else java.io.File(lut.fileName).inputStream()
                input.use(LutParser::readInputEncoding)
            }.onFailure {
                PLog.w(TAG, "Cannot read LUT input encoding: ${lut.id}", it)
            }.getOrNull()
            lut.copy(
                category = overriddenCategory ?: lut.category,
                isFavorite = overriddenFavorite ?: lut.isFavorite,
                inputCurve = encoding?.first,
                inputColorSpace = encoding?.second
            )
```

而 `readInputEncoding` **第一条 `require` 就是 `MAGIC_PLUT`**。对 `.cube` 文本文件该 `require` 必然失败 →
被 `runCatching{}.getOrNull()` 吞掉 → `LutInfo.inputCurve = null`、`inputColorSpace = null`
（`LutConfig.kt:112-113`）：

```kotlin
    val inputCurve: TransferCurve? = null,
    val inputColorSpace: ColorSpace? = null,
```

**结论：`.cube` 文件在 PhotonCamera 里没有携带域信息的能力，只能落到默认 sRGB/sRGB；
需要域标注时，PhotonCamera 使用自研二进制格式 `.plut`（version ≥2 存 curve，≥3 存 gamut）。**
`.cube` 分支在 `LutParser.parse` 里回退到 `CubeLutParser.parse(stream)`（`LutParser.kt:58-59`），
而 `CubeLutParser` 返回的 `LutConfig` 只设 `size/data/title`（`CubeLutParser.kt:110-114`），
`curve/colorSpace` 取默认值。

---

## 5. PhotonCamera 侧的「相机外观引擎」数据源

- **DCP（Adobe 相机配置文件）**：`raw/DcpProfile.kt`、`raw/DcpHueSatMapGl.kt`、`raw/DcpToneCurve.kt`、
  `raw/DngEmbeddedProfile.kt`、`raw/DngSdkColorSpec.kt`、`app/src/main/cpp/dcp/dng_sdk/`（内嵌 Adobe DNG SDK）、
  `app/src/main/cpp/dcp_parser.cpp`。
- **厂商引擎**：`CanonProfile.kt` / `CanonIccProfile.kt` / `CanonPictureStyle.kt` / `CanonToneAlgorithm.kt`（Canon Picture Style + ICC）、
  `LumixProfile.kt` / `LumixPhotoStyle.kt` / `LumixToneAlgorithm.kt`（Panasonic PhotoStyle）、
  `HncsProfile.kt`（Hasselblad HNCS，注释里给出 `CRawColorCorrection::GetColorSpaceChangeMatrix` 矩阵，见 `ColorSpace.kt:74-85`）。
- **物理模型引擎**：`app/src/main/cpp/spektrafilm/`（`spectral_filming.cpp`、`spectral_printing.cpp`、
  `spectral_gamut.cpp`、`spectral_data.cpp`）——光谱级胶片/印片模拟，不是 LUT。
- **理论引擎**：`AgXToneAlgorithm.kt`、`DarktableSigmoidToneAlgorithm.kt`、`DarktableFilmicToneAlgorithm.kt`。

`ColorSpace.kt:74-85` 逐字：

```kotlin
    /**
     * Phocus HNCS working basis.
     *
     * Chromaticities are derived from the recovered
     * CRawColorCorrection::GetColorSpaceChangeMatrix RGB-to-XYZ(D50) matrix
     * [0.79767, 0.13519, 0.03134; 0.28804, 0.71188, 0.00009;
     * 0, 0, 0.82491]. The separate enum identity prevents an HNCS camera map
     * from being evaluated in an unrelated ProPhoto processing contract.
     */
```

→ **数据源包含「从厂商软件/固件中恢复出来的矩阵」**（"recovered"），以及 ICC / DCP / 自标定。

### PhotonCamera 自实现的 AgX（可与其他项目的官方源码交叉验证）

`app/src/main/java/com/hinnka/mycamera/raw/AgXToneAlgorithm.kt`（`internal object AgXToneShader`）：

```glsl
        const float DT_AGX_DEFAULT_RANGE_EV = 16.5;
        const float DT_AGX_MIN_RANGE_EV = 0.2;
        const float DT_AGX_CURVE_GAMMA = 2.2;
        const float DT_AGX_PIVOT_Y = 0.4586564469;
        const float DT_AGX_SLOPE = 3.0;
        const float DT_AGX_HUE_MIX = 0.6;
```
```glsl
        vec3 agxBaseToRendering(vec3 color) {
            return vec3(
                dot(vec3(0.8535098168, 0.0870498824, 0.0594403008), color),
                dot(vec3(0.1209748385, 0.7561015246, 0.1229236368), color),
                dot(vec3(0.0964595535, 0.0689548151, 0.8345856314), color)
            );
        }
```
```glsl
        float agxLogEncode(float value) {
            float relativeValue = max(DT_AGX_EPSILON, value / 0.18);
            float blackEv = min(uAgxBlackRelativeExposure, uAgxWhiteRelativeExposure - DT_AGX_MIN_RANGE_EV);
            float whiteEv = max(uAgxWhiteRelativeExposure, blackEv + DT_AGX_MIN_RANGE_EV);
            float rangeEv = max(DT_AGX_MIN_RANGE_EV, whiteEv - blackEv);
            return clamp((log2(max(relativeValue, 0.0)) - blackEv) / rangeEv, 0.0, 1.0);
        }

        float agxSigmoid(float value, float power) {
            return value / pow(1.0 + pow(value, power), 1.0 / power);
        }
```

结构：**primaries inset（`agxBaseToRendering`）→ log2 编码（相对 0.18，EV 范围归一化）→ sigmoid → 输出 gamut 矩阵**。
注意 `pivot_y = 0.4586564469` 恰是 sRGB 编码中间灰（§7 表中 0.18 → 0.46136 的近似值，取自 darktable 的 AgX 实现），
以及 `DT_AGX_CURVE_GAMMA = 2.2`、`DT_AGX_SLOPE = 3.0`，都与 darktable 的 AgX 参数体系同源（**待由 darktable 源码核实，见 01-darktable.md**）。

---

## 6. 与 FilmKit 症状相关的量化差异（**本节数值为本次自算，非源码引文**）

前提（来自任务背景描述，非本报告核实结论）：FilmKit 的输出 OETF 是**纯 2.2 幂**；PhotonCamera 是 **sRGB 分段**（§2.3 已核实）。

python3 计算结果：

```
linear   sRGB_enc   2.2pow   delta(rel %)
0.001    0.01292    0.04329   +235.0%
0.002    0.02584    0.05932   +129.6%
0.005    0.06101    0.08997    +47.5%
0.01     0.09985    0.12328    +23.5%
0.02     0.15170    0.16894    +11.4%
0.05     0.24780    0.25623     +3.4%
0.1      0.34919    0.35112     +0.6%
0.18     0.46136    0.45866     -0.6%
0.5      0.73536    0.72974     -0.8%
0.9      0.95469    0.95324     -0.2%
1.0      1.00000    1.00000     +0.0%
```

即：**中间调与高光几乎相同（<1%），但阴影/暗部（线性 ≤0.02）被纯 2.2 幂系统性抬高 11%~235%**。
若一个 display-referred LUT 的设计输入是 sRGB 编码值，而被喂入 2.2 幂编码值，
则它在**阴影区采样到的坐标普遍偏亮**；对"压暗阴影、提高对比/饱和"的浓郁型风格 LUT，
这一偏移正好落在削弱其作用的方向上 → 表现为整体观感**「更淡、更灰」**。

这条链条中「域不匹配」是**确定**的（两侧 OETF 已核实/已给定），
但「它是 FilmKit 观感变淡的主因还是次因」**未在本报告中定量验证**（需要 FilmKit 侧实测，属于另一项工作）。

---

## 7. 明确「未找到 / 未核实」的条目

- PhotonCamera 未提供 `.cube` 的 colorspace/trc 标准扩展；域标注只存在于自研 `.plut` 与（可能的）XMP 路径。
  `XmpLutParser.kt:111-112` 的签名含 `colorSpace: ColorSpace = ColorSpace.SRGB` / `curve: TransferCurve = TransferCurve.SRGB` 默认参数，
  但本次**未逐行核实 XMP 中域字段的解析取值**（`XmpLutParser.kt:174-199`、`:410-411` 仅显示重采样使用 `TransferCurve.SRGB.logToLinear`）。
- `RawEngineTonePass.kt` 的 `main()` 是否调用其 `linearToSrgb`（定义于 `:416`）未确认；`grep` 只命中定义处。
- `.cube` 规范（Adobe Cube LUT Specification 1.0）官方 URL 本次下载失败（`wwwimages2.adobe.com` 与 `www.adobe.com` 均 404，
  `web.archive.org` 超时），**规范原文引自 04-aces-libplacebo.md 的独立取证结果**。
- PhotonCamera 的 spektrafilm / DNG Photon Profile Gain Table / MGC 等模块只做了存在性核实，未逐行审读算法。
