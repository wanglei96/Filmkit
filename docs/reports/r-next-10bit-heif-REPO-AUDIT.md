# R-NEXT-10BIT-HEIF · 本仓现状盘点（REPO AUDIT）

> 范围：**只读源码盘点**，未修改任何源码、未构建、未跑 App。
> 目标：为「切 Rec.2020 线性工作空间」与「新增 10bit HEIF 交付链」两期决策提供爆炸半径清单。
> 证据口径：全部为 `文件:行`；凡推断均标 **[推断]**。

---

## ① 结论摘要

1. **交付链是「RGBA16F 中间 → RGBA8 成片 target → glReadPixels UNIGNED_BYTE → ARGB_8888 Bitmap → JPEG」**；唯一真降位点在 `core/engine/src/render/FrameRenderer.cpp:8519`（另有 RAW 静拍同型点）。
2. **编码侧两条路**：`nativeEncodeJpeg444`（stb，q>90 才 4:4:4，`<91` 钳到 91）与回退 `Bitmap.compress`（Android 恒 4:2:0）。质量默认 **100**。
3. **切 Rec.2020 的爆炸半径（必须一起改，共 6 类）**：
   - ① filmic v5 gamut mapping 的 **sRGB 原色矩阵整组**：`FrameRenderer.cpp:4261-4273`（`kLmsSrgbToLms*` / `kLmsToSrgbRow*`）+ `:4334-4367`（sRGB 边界 chroma 解析解）；
   - ② CCM 链终点 = 线性 sRGB：`app/src/main/java/com/filmkit/app/camera/StdColorMatrix.kt:29-33`（`X2S`）+ `FrameRenderer.cpp:1001-1013`（`kFsCcm` 的 `uCcm`）；
   - ③ 预览/静拍**输入去 gamma 点**：`FrameRenderer.cpp:331`（`kFsOes`）与 `:347`（`kFsStill`）的 `pow(rgb, vec3(2.2))` —— **不是**真 sRGB 分段，且 `check-srgb-oetf.py` 抓不到它；
   - ④ OETF 契约共 **5 处**（非用户记忆的 3 处）：shader 内 `srgbEncode` **4 份**（`:5019 / :5449 / :5960 / :6076`）+ CPU 侧 **1 份**（`:14245`）；
   - ⑤ luma 权重族（锐化的 Rec.709 权重 `:5067` + `FilmParams.h:91`）在原色改变后不再等亮度；
   - ⑥ **缺项而非改项**：当前无任何输出端 gamut mapping（kFsOut 末端是硬 `clamp(c,0,1)`，`:5189`）。
4. **LUT 资产（42 个 `.lut`）不需要重标**：它们在 OETF **之后**（`FilmParams.h:125`），域仍是 display 域；受影响的只是"输出色域标签"。
5. **DNG 底片保留存在**（`CameraController.kt:3389` `saveDng` + `DngCreator`，开关 `dng_enabled`）；**HEIF/HEIC/GainMap 为零实现** —— 唯一出现处是"拒绝识别"白名单（`EditorExport.kt:210-220`）。
6. **Gradle / assets 无任何 HEIF 能力**（`app/build.gradle.kts:127-138` 只有 exifinterface + tflite）。
7. **分期决策的关键守卫**：A 组零回归 CRC/逐位基线**全部会因切工作空间同时变红**，包括 `film_d5.npy CRC32 = 0x589f8364`（`tools/engine-shader-offline.py:886`）与 `FILM_SRC_BASELINE` 分段 crc32（`:409-422`）。
8. **文档已写死铁律**：`docs/engine-slots-design.md:441-446` —— 换工作空间 = D4 定义改变 = **所有预设重标**，必须单独一期，不可与插槽重构同期，否则丢掉"`[98]=0` 时 CRC32 逐字节等价"的零回归证明力。
9. **10bit HEIF 与工作空间相对解耦**（输出端位深，不动 D4 定义与 `film_d5` 基线）⇒ **可先做**；但 `engine-slots-design.md:437-440` 已裁定：10bit 救不了 filmic 的连续不可逆 plateau，收益会被吞掉。

---

## ② 交付链现状

### 2.1 路径 A：拍照 / 静拍成片

| # | 环节 | 位置 | 内容 |
|---|---|---|---|
| 1 | 引擎内成片渲染 | `FrameRenderer.cpp:8516`（YUV 静拍 `submitStill`）/ `:11130`（RAW 静拍 `finishStillChain`，定义 `:10730`） | `passOutput(dst_w, dst_h, p, true, false)` —— 第二参 `true` = 成片（斑马纹绝不进成片） |
| 2 | 成片 target | `FrameRenderer.cpp:8503` | `makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8, dst_w, dst_h)`，注释 `@domain: D5 —— 成片 RGBA8 输出` |
| 3 | **唯一真降位点** | `FrameRenderer.cpp:8519` | `glReadPixels(0, 0, dst_w, dst_h, GL_RGBA, GL_UNSIGNED_BYTE, out_rgba->data())` |
| 4 | 上传为 Bitmap | `CameraController.kt:3469` | `Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)`；跨距/拷贝见 `:843` 注释"bytes 为 RGBA（与 Bitmap ARGB_8888 内存序一致）" |
| 5 | 编码（4:4:4） | `CameraController.kt:3532-3538` | `EngineBridge.nativeEncodeJpeg444(outBmp, jpegQ)`；prefs `jpeg_444` 默认 `true`（`:3524`） |
| 6 | 编码（回退） | `CameraController.kt:3556` | `outBmp.compress(Bitmap.CompressFormat.JPEG, jpegQ, os)` —— Android 恒 4:2:0 |
| 7 | JNI 实现 | `core/engine/src/jni/JpegEncoder.cpp:53` | stb_image_write；`quality > 90` 才输出 4:4:4（stb `subsample = quality <= 90 ? 1 : 0`），`<91` 钳到 91（`EngineBridge.kt:212`） |
| 8 | 质量档 | `CameraController.kt:3518` | `prefs.getInt("jpeg_quality", 100)`；设置页可选 95/100（`SettingsScreen.kt:122/130`） |
| 9 | 落盘 | `CameraController.kt` MediaStore 段 | `MIME_TYPE = "image/jpeg"`，文件名 `FILMKIT_<时间>[_LUT].jpg` |

### 2.2 路径 B：编辑器 / 查看页（对已成片再处理）

- 输入 = 已成片 RGBA8、**显示域 sRGB、已过 OETF**（`FrameRenderer.cpp:5198-5201`）。
- `kFsStyle`（`:5220`）**只重跑 OETF 之后三段**：3D LUT → 颗粒 → TPDF dither；`:5200-5201` 是 **ADR-7 硬约束**："本 shader 内任何地方都不得插入 OETF/EOTF 转换"。
- 落盘：`EditorScreen.kt:1212 encodeEditorJpeg(out, prefs.getInt("jpeg_quality", 100))` → `EditorExport.kt:1691-1700` → `nativeEncodeJpeg444`，回退 `bmp.compress(CompressFormat.JPEG, quality, bos)`。

### 2.3 `kFsOut` 的输出契约（`FrameRenderer.cpp:4750`）

- **输入**：`uTex` = **线性显示域（D5）**，来自 RGBA16F `tex_work_` / `tex_scene_`（`:8185 / :8193`）；
- **shader 内顺序**（以**代码**为准）：锐化（`:5098 c = srgbEncode(c)`）→ 影调分区/褪色 → 柔光 Screen → 暗角（`:5152`）→ **display 域 3D LUT**（`:5164`）→ 颗粒（`:5170`）→ TPDF dither（`:5183`）→ `oColor = vec4(clamp(c, 0.0, 1.0), 1.0)`（`:5189`）；
- **输出契约 = 显示域 sRGB 编码值，[0,1]，量化目标 RGBA8**；
- ⚠ **注释漂移**：`:4803` 的自述顺序写作"影调分区/褪色 → 柔光 → 暗角 → **颗粒 → LUT** → dither"，与代码（暗角 → LUT → 颗粒）**不一致**，以代码为准。
- `kFsOut` 头注释自述"Pass3：输出变换（OETF + 颗粒 + dither），**量化到 8bit**"（`:4749`）。

### 2.4 OETF 的确切调用点与次数（订正）

| 位置 | 载体 | 说明 |
|---|---|---|
| `FrameRenderer.cpp:5019` | `kFsOut` 内 `vec3 srgbEncode` | 成片主链，`:5098` 调用一次 |
| `FrameRenderer.cpp:5449` | `kFsStyle` 内 `vec3 srgbEncode` | 编辑器链（配对 `srgbDecode` 在 `:5443`）；调用点 `:5519 x = srgbEncode(srgbDecode(vec3(x)) * g).r`、`:5672 c = srgbEncode(lin)` |
| `FrameRenderer.cpp:5960` | `kFsHaloMix` 内 `vec3 srgbEncode` | 配对 `srgbDecode` 在 `:5958`；由 `uDecodeIn` 分支（编辑器链路 = 显示域） |
| `FrameRenderer.cpp:6076` | `kFsHist`（定义 `:6026`）内 `vec3 srgbEncode` | 实时直方图/斑马纹，注释 `:6023-6025`"OETF 与 kFsOut 一致 —— 直方图必须是显示域、用户实际看到的分布" |
| `FrameRenderer.cpp:5864` | 另一份 `srgbDecode`（`kFsHaloThr` 段） | 只有 decode 无 encode |
| `FrameRenderer.cpp:14245` | **CPU 侧** `measureSceneStats` 的 sRGB EOTF 折回 | `return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);` |

> **给用户的订正**：记忆中"`kFsOut` / `kFsHist` / `measureSceneStats` 三处同步真 sRGB"实际是 **shader 4 处 + CPU 1 处 = 5 处**（多出 `kFsStyle`、`kFsHaloMix`）。
> 另注：`measureSceneStats`（定义 `:13414`）内还有**第二种**折回 —— 对数域 `:13449 to_lin = exp2(enc * 20.0f - 12.0f)`（`uStatMode=1`，自动 EV 判据专用），它不是 sRGB，别与 `:14245` 混为一谈。
> `tools/check-srgb-oetf.py:21` 的提示语写"kFsOut / kFsHist 应各一份"也已过时（脚本用 `findall` 循环全验，不会因此失败）。

### 2.5 4:4:4 是怎么做的

- Android 侧**无 API 可改**采样因子：`Bitmap.compress` → Skia → libjpeg-turbo 恒 4:2:0；`YuvImage.compressToJpeg` 同样固定；NDK 29 sysroot 无 `jpeglib.h`（`JpegEncoder.cpp:3-10` 一手实测记录）。
- 做法 = **自带编码器**：捆 stb_image_write v1.16（`core/engine/third_party/stb/stb_image_write.h`，`:33-35` `STB_IMAGE_WRITE_IMPLEMENTATION`），q>90 时 stb 自身输出 4:4:4 且 q100 量化表退化为全 1。
- 动因与实测：`docs/reports/jpeg444-REPORT.md:6,16-22`（4:2:0 在中性边缘引入 ΔC p99 9.77 / max 49.17，4:4:4 p99 1.36 / max 1.94；体积 1.81×；stb q100 338ms vs libjpeg 4:2:0 56ms）。

---

## ③ 「线性 sRGB 工作空间」落点清单（切 Rec.2020 的爆炸半径）

> 「是否必须重标」= 该处语义是否随公共工作空间（D4 定义）改变而失效。

| # | 位置 | 内容 | 必须重标？ |
|---|---|---|---|
| 1 | `FrameRenderer.cpp:4261-4263` | `kYchD65R = 0.21902143` / `kYchD65G = 0.54371398`（D65 锚点） | **是**（原色变 ⇒ 锚点变） |
| 2 | `FrameRenderer.cpp:4264-4273` | `kLmsSrgbToLmsL/M/S` + `kLmsToSrgbRowR/G/B` —— **sRGB 原色 ↔ LMS 矩阵硬编码** | **是**（换成 Rec.2020 版；darktable 本就是 BT.2020 域） |
| 3 | `FrameRenderer.cpp:4275-4280` | `gamutSrgbToLms` / `gamutLmsToSrgb`（函数名即写死 sRGB） | **是**（改名 + 重标） |
| 4 | `FrameRenderer.cpp:4334-4367` | `gamutClipChromaWhite/Black` —— "**sRGB 色域边界**上恒 Y 恒色相的最大 chroma"（darktable 解析解） | **是**（边界多边形换 Rec.2020） |
| 5 | `FrameRenderer.cpp:4251` | 注释自述"管线工作域 BT.2020 vs **我们 sRGB** —— 它 gamut check 后回 BT.2020，我们管线本就是 sRGB 线性，check 完即输出，**无需回转**" | **是**（切域后必须补"输出端回转/mapping"，此注释与逻辑同时失效） |
| 6 | `StdColorMatrix.kt:29-33` | `X2S` = Bradford D50→D65 × **sRGB 原色矩阵**（离线算好，9 个 float） | **是**（CCM 链终点域） |
| 7 | `StdColorMatrix.kt:36` | 白点表 `D65 = (0.95047, 1, 1.08883)` / `A = (1.09850, 1, 0.35585)` | 否（白点不变）；但其下游原色矩阵变 |
| 8 | `FrameRenderer.cpp:1001-1013` | `kFsCcm`：`uCcm`（注释"sensor→**sRGB** 行主序"），D3→D4 截断 `clamp(rgb, 0, 64)` | **是**（矩阵重标；`64` 上界与域量程口径相关，需复评） |
| 9 | `FrameRenderer.cpp:427`（`kFsRaw`） | `rgb = uCcm * (rgb * uGain * lscAt(p))` | LSC 增益 `uLsc`（`:371 / :615`）是**无量纲乘性增益** ⇒ **否**；WB 增益 `:111-126`（255 域，CPU 侧）同样**否** |
| 10 | `FrameRenderer.cpp:331`（`kFsOes:323`） | `vec3 lin = pow(rgb, vec3(2.2)) * uWb;` —— 预览链**输入去 gamma** | **是**（且此处是 `pow(2.2)` 近似，与 `kFsOut` 真 sRGB **不同源**，见 ⑦-3） |
| 11 | `FrameRenderer.cpp:347`（`kFsStill:339`） | 同上（YUV 静拍输入） | **是** |
| 12 | `FrameRenderer.cpp:5019 / 5449 / 5960 / 6076 / 14245` | OETF/EOTF（显示域） | **视交付域而定**：若交付仍 sRGB/P3 ⇒ **否**；若交付 Rec.2020 PQ/HLG ⇒ **是**。**工作空间（线性域）切换本身不要求改 OETF** —— 这是关键区分 |
| 13 | `app/src/main/assets/luts/*.lut`（**42** 个） | display 域（`FilmParams.h:125`"M4 3D LUT（display 域，插在输出 OETF 之后）"）；`docs/开发计划.md:20` D10"默认按显示域（Rec.709/Gamma 2.2）处理" | **否**（在 OETF 之后，域不变）；仅"输出色域标签"需重标 |
| 14 | `FrameRenderer.cpp:5067`（锐化） | `dot(srgbEncode(highlightRolloff(texture(uTex, uv).rgb)), lumaWeight)` —— OETF **前**线性显示域的 3×3 USM | **是（弱）**：luma 权重族依赖原色，切域后不再等亮度 |
| 15 | `FilmParams.h:91` | "`l` 用 **Rec.709 luma**（编辑器侧 U-2 起从 Rec.601 改为 709，与 `kFsFilm` 同源）" | **是（弱）**，同 14 |
| 16 | `FrameRenderer.cpp:5178`（颗粒） | `float l = dot(c, vec3(0.299, 0.587, 0.114));`（Rec.601） | 否（显示域） |
| 17 | `FrameRenderer.cpp:5186`（斑马纹） | `dot(c, vec3(0.2126, 0.7152, 0.0722))`（Rec.709） | 否（显示域） |
| 18 | `FrameRenderer.cpp:5152-5163`（暗角）/ soft glow（`:5938-5990` 段） | 纯显示域乘性/加性 | 否 |
| 19 | `FrameRenderer.cpp:6452 / 6605` | `kFsSigmoidDef` / `kFsSigmoidMain`（引擎 1） | **挂点已预留**：`docs/engine-slots-design.md:244`"非默认 `inset` 是'**Rec.2020 基础 primaries**'那期的挂点" |
| 20 | `FrameRenderer.cpp:111-126`（相机 WB 增益）/ `FilmParams.h:514`（`engine_slot = 0`，槽 98）/ `:643-645`（缺槽兼容） | 增益比值 / 插槽 | 否 |

**必须新增（当前完全不存在）**：
- **输出端 gamut mapping**（Rec.2020 → 交付色域）—— `kFsOut` 末端只有硬 `clamp(c, 0, 1)`（`:5189`）；`engine-slots-design.md:446` 只写了"必须配输出端 gamut mapping"，算法与落点未定。
- **10bit 输出链** + **HEIF 编码器**（见 ④、⑤）。

**域定义参考**：`docs/refactor-architecture.md:420-421` 列 12 个域（D0–D12），其中 D3 = WB+LSC、**D4 = 场景线性 sRGB（CCM 输出）**、D5 = 显示域；`:625-626` 给出 D4/D5 的输入、白点、值域与消费点。

---

## ④ 位深与量化面现状

### 4.1 已经是 16F / 高位深的

| 位置 | 格式 |
|---|---|
| `FrameRenderer.cpp:8185`（`tex_scene_`）、`:8193`（`tex_work_`） | `GL_RGBA16F` |
| `FrameRenderer.cpp:7889` | `glTexImage3D(..., GL_RGBA16F, 1,1,1, ...)`（3D LUT） |
| `FrameRenderer.cpp:6621-6636` | 统计 Σ 累加附件首选 `GL_RGBA32F`（不可渲染时回落 `GL_RGBA16F`），注释"**不在 GPU 端做任何 8bit 归一化**" |
| `FrameRenderer.cpp:3584 / 3678 / 3793 / 3803` | mosaic/融合域 `RGBA16F` 相位布局或 `R16F` 单通道 |
| `FrameRenderer.cpp:1015`（`kFsAmazeUp` 注释） | CPU AMaZE 结果上传为 `RGB16F` |
| `FrameRenderer.cpp:8158` | 探针 `glReadPixels(..., GL_HALF_FLOAT, raw)`（RGBA16F 路径） |
| `docs/开发计划.md:160`（位深策略） | "从传感器位深（≥10bit）到最终 dither 前全程 ≥ half-float，禁止中途 8bit 量化" —— **在引擎中间链路成立** |

### 4.2 8bit 假设清单

| # | 位置 | 内容 |
|---|---|---|
| 1 | `FrameRenderer.cpp:8503` | 成片 target `tex_cap_ = GL_RGBA8` |
| 2 | `FrameRenderer.cpp:8519` | `glReadPixels(..., GL_RGBA, GL_UNSIGNED_BYTE, ...)` —— **唯一真降位点** |
| 3 | `FrameRenderer.cpp:8222` | 预览成片 target `tex_out_s_ = GL_RGBA8` |
| 4 | `FrameRenderer.cpp:8432 / 8476` | `GL_RGB8` + `GL_UNSIGNED_BYTE`：静拍输入的 RGB888 上传（YUV→RGB 已在 CPU 侧降到 8bit） |
| 5 | `FrameRenderer.cpp:5183`（`kFsOut`）/ `:5814`（`kFsStyle`） | TPDF dither `(dhash(...) + dhash(...) - 1.0) / 255.0` —— **`/255.0` 是 8bit 专属常量** |
| 6 | `FrameRenderer.cpp:6023` + `:6026`（`kFsHist`） | 直方图下采样到 **256×96 RGBA8**，每次读回 24KB |
| 7 | `FrameRenderer.cpp:6660-6663 / 6757` | 诊断位打包 `8×2 RGBA8` + `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)`（ES 3.0 保证的唯一组合） |
| 8 | `FrameRenderer.cpp:1012`（`kFsCcm`） | D3→D4 截断 `clamp(rgb, vec3(0.0), vec3(64.0))` —— **域量程常量（非位深）**，但 64 与 headroom 定义绑定 |
| 9 | `FrameRenderer.cpp:14245` | CPU 侧 sRGB EOTF 折回（统计域） |
| 10 | `CameraController.kt:3469`、`EditorEngineSession.kt:284`、`EditorScreen.kt:4475` | `Bitmap.Config.ARGB_8888` |
| 11 | `CameraController.kt:843` | 注释"bytes 为 RGBA（与 Bitmap ARGB_8888 内存序一致）" |
| 12 | `JpegEncoder.cpp:62-66` | `info.format != ANDROID_BITMAP_FORMAT_RGBA_8888` 即返回 nullptr（HARDWARE / RGB_565 不在覆盖范围） |
| 13 | `app/src/test/.../OutDigestTest.kt:26` | 摘要均值按 0..255 口径断言 |
| 14 | `tools/check-style-passthrough.py:136` | "浮点输出 → 8bit（等价 `glReadPixels` RGBA8 的量化）→ 前 3 通道" |
| 15 | `tools/check-halation.py:27` | "判据 = **输出位深（8bit）内**逐位相同" |
| 16 | `tools/check-softglow.py:19` | "两条链路逐位相同 ……（U-4 口径，**8bit**）" |
| 17 | `FrameRenderer.cpp:7868` | `makeTex(&tex_local_, &fbo_local_, GL_RGBA8, kLocalW, kLocalH)` —— 局部色调映射低频图 **RGBA8**；**未确认其承载量**（疑似隐藏降位点，见 ⑦-8） |

### 4.3 `[0..98]` 之外是否还有隐藏位深常量

- **没有"位深"参数槽存在**。位深是**编译期常量**散落各处（`/255.0`、`GL_RGBA8`、`GL_UNSIGNED_BYTE`、`ARGB_8888`），无集中定义点 ⇒ **[推断] 做 10bit 时必须先把这些常量收口成一处**。
- 槽位账本：`FilmParams.h:22 / :38`"v0.11.27（阶段 0）：**98 → 99**，+`[98] engine_slot`"，实际字段 `:514 float engine_slot = 0.0f; // 98`。⇒ `kCount = 99`，`0..98` 全占。
- **下一个可用槽 = `[99]`**。但 `tools/check-filmparams-contract.py:42/80` 会校验 `FilmParams.h` 的 `kCount = N` 与 Kotlin `COUNT` **四处同步** —— 加槽必触发。
- **10bit-ready 的东西：当前为零**。没有任何 10bit 代码路径；`GL_RGB10_A2` / `GL_UNSIGNED_INT_2_10_10_10_REV` / P010 / HEVC Main10 均无引用。

---

## ⑤ 既有 HEIF / DNG 能力

### 5.1 HEIF / HEIC：**零实现**

- 唯一出现处是**拒绝识别**（安全检查，不是能力）：
  - `EditorExport.kt:210-220` —— `isJpegSource` 为**白名单**："只认'是 JPEG'，其余一律不放行 —— DNG 之外还有 **HEIC** / PNG / …"；HEIC 头 = `....ftypheic`（第 4~12 字节）。
  - `app/src/test/.../EditorExportTest.kt:292-311` —— `heicHead` 定义 + `assertFalse("HEIC", isJpegSource(heicHead))`。
  - `app/src/test/.../EditorOverwriteWarnTest.kt:81-91` —— 同一判据的端到端用法。
- **Gradle 依赖**：`app/build.gradle.kts:127-138` 只有 `androidx.core.ktx` / activity-compose / compose BOM / material3 / icons / `androidx.exifinterface:1.3.7` / tflite 三件套。**无** `androidx.heifwriter`、无 libheif、无 `MediaCodec` / `MediaMuxer` 引用（grep `MediaCodec|HeifWriter|heif|AVIF` 在 `*.kt/*.cpp/*.h/*.kts/*.xml/*.toml` 内零命中）。
- **assets**：`app/src/main/assets/` = `luts/`(42) + `noise_models/` + `presets/` + `pmrid_512_fp16.tflite` + LUT 预览图 —— **无 HEIF 相关资产**。
- **规划（未落地）**：`docs/开发计划.md:130` 编码 = "JPEG（libjpeg-turbo）/ **HEIF** / PNG；可选 16bit TIFF（M6）"；`:337` 目录规划 `io/ # JPEG/HEIF/PNG/TIFF、DNG Writer`。
- ⇒ **10bit HEIF 是纯新增**，至少需要：① RGBA16F → 10bit 缓冲的输出链；② 编码器（`androidx.heifwriter.HeifWriter` 或 `MediaCodec`(HEVC Main10) + `MediaMuxer`，**选型未定**，见 ⑦-6）；③ 色彩标签（Rec.2020 primaries + transfer + HDR static metadata）。

### 5.2 GainMap：**零引用**（grep `gainmap` 无命中）

### 5.3 DNG 底片保留：**存在且完整（系统 `DngCreator` 路径）**

| 位置 | 内容 |
|---|---|
| `CameraController.kt:3389` | `saveDng(...)` —— "DNG 原片保存：`DngCreator` 按 HAL 元数据写（asShotNeutral / 黑阶 / CCM 全在其中）" |
| `SettingsScreen.kt:81 / :115-117` | 开关 prefs `dng_enabled`（默认 `false`） |
| `CameraController.kt:2736`（YUV 路径）/ `:2900`（RAW 路径） | 读 `dng_enabled` |
| `CameraController.kt:2755 / :2936` | 实际调用 `saveDng(img, result, ch)` / `saveDng(ref, refResult, ch)` |
| `CameraController.kt:2418 / 2402-2451` | `stillFormat = if (rawMode) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888`（M 档 `raw_mode`）；`:2451` 注释"参考帧保留 Image（`DngCreator` 只吃 Image）" |
| `CameraController.kt:284 / :299` | Image 生命周期约束（DngCreator 必须持有 Camera2 的 Image；`maxImages=4` 帧循环、仅参考帧保留） |
| `ImageDecode.kt:16` | 读 DNG 时写真实 sensor 方向（1/6/3/8，见 `DngCreator.setOrientation`） |
| `FilmParams.h:139` | 管线读 `DngBaselineExposure`（+ 参数 + AE 曝光决策） |
| `docs/开发计划.md:110` | "M2 用系统 `DngCreator` 快速落地；**M4 自研 DNG Writer**（写入完整色彩矩阵与噪声档案，支持曝光偏置），保证'底片'可被第三方 Raw 冲图软件正确打开" —— **自研 Writer 未落地** |

> 结论：DNG 是**输入/底片侧**能力，与 10bit HEIF **交付侧正交**。但切 Rec.2020 后，DNG 内的 CCM/白点元数据口径需与新的 `StdColorMatrix` 链一致（后者是 `CameraCharacteristics` 旁路，不经 HAL CCM，见 `StdColorMatrix.kt:24-26`）。

---

## ⑥ 会被打红的判据与守卫（**对分期决策最关键**）

### A 组：零回归 CRC / 逐位基线（切工作空间 ⇒ 同时变红）

| 守卫 | 钉住的判据 |
|---|---|
| `tools/engine-shader-offline.py:886` | **`film_d5.npy` CRC32 = `0x589f8364` = 零回归守卫**；引擎 1 输出另存 `film_d5_sigmoid.npy`，**不覆盖** `film_d5.npy` |
| `tools/engine-shader-offline.py:409-422` | `FILM_SRC_BASELINE` = `kFsFilm` 源码**分段 chars/bytes/crc32/sha256**：`head 0x79355c53` / `engine0_def 0x05f5351d` / `mid 0x0dab15dc` / `engine0_main 0xbe41521e` / `tail 0x20921afd` / 全串 `0x4fa1ebd3` —— **改 `kFsFilm` 内一个字符即红** |
| `tools/engine-shader-offline.py:30` | 基线出处 = `docs/reports/stage0-engine-slot-BASELINE.md §3.3` |
| `tools/check-film-offline.py:121 / :134` | 读 `film_d5.npy`；`assert dif.max() < 2e-3, 'CPU 与 GPU 的 tone 路径不一致（超 float16 量化量级）'` |
| `tools/check-toeshoulder.py:5-21` | `[5..10]` 全默认时 `computeFilmicSpline` 的 **60 个系数逐位** = 固化基线 `EXPECT_HEX`（2026-10-11 起改为自包含，不依赖 git 历史） |
| `tools/check-halation.py:9 / :27` | `[62]=0` 逐位零回归 + **零 pass 零纹理**；判据 = 输出位深（8bit）内逐位相同 |
| `tools/check-softglow.py:11 / :19` | `[95]=0` 逐位零回归 + 零 pass 零纹理；两条链路逐位相同（8bit） |
| `tools/check-style-passthrough.py:35 / :43 / :58 / :70 / :136` | 真 sRGB 曲线证据（对照 numpy 解析解 ≤2 LSB，且与 `pow(2.2)` 近似在暗部可区分）；⑭a/⑮a 默认零回归（≤1 LSB，只剩 dither） |
| `tools/check-u2-editor-curve-sat.py:7` | 默认值**逐位零回归**（自包含基线，替代 `git show HEAD`） |
| `tools/check-u3-camera-contrast-wb.py:6` | `[4]=1.0` / `[88]=[89]=0` 时拍照链路输出**逐位相同**；`:122` 源码结构变了即 `SystemExit` |
| `tools/check-u4-camera-display-domain.py:6 / :96` | `[78]=0` / `[90..94]=0` 时**逐位零回归**；三块定位标记各命中**恰好一次**（`assert hits == 1`） |

### B 组：域 / 契约静态检查

| 守卫 | 判据 |
|---|---|
| `tools/check-srgb-oetf.py` | 抓**所有**`vec3 srgbEncode(vec3 c) { ... }` 体：`step` 方向必须为 `step(c, vec3(0.0031308))`、常量 `1.055 / -0.055 / 1/2.4 / 12.92`、9 个采样点对 IEC 61966-2-1；**并校验 CPU 侧 `FrameRenderer.cpp:14245` 的 `to_lin` 常量 = (0.04045, 12.92, 0.055, 1.055, 2.4)** —— 不同步即红 |
| `tools/domain-contract-check.py` | `makeTex` 纹理台账 + `@domain: Dn` 标注 + "域重载嫌疑"（≥3 pass 共用一纹理）；`退出码 0 = 无 unlabeled 且无重载嫌疑`。**切工作空间 = D4 定义变化 ⇒ 域表（`refactor-architecture.md:420-421`）与所有 `@domain` 标注要重审** |
| `tools/check-filmparams-contract.py:42 / :80 / :109-116` | `FilmParams.h` 的 `static constexpr int kCount = N` 与 Kotlin `const val COUNT = N` **四处同步**；找不到即 `FAIL` |
| `tools/check-lsc-mapping.py:47-59` | LSC 增益网格角点（`CORNER ±0.05`）、中心（`1.0 ±0.02`）、恒等输入恒 1.0、单调性断言 |
| `tools/check-diag-fields.py:158-195` | 诊断字段写入行必须晚于其数据来源调用（顺序纪律） |
| `tools/check-log-json.py` / `check-diag-stats.py` / `check-fft-tile-cover.py` / `check-hr-pre.py` | 日志 JSON 合法、诊断统计、FFT tile 覆盖与窗和、HR 前置条件 |

### C 组：单元测试

| 测试 | 钉住的判据 |
|---|---|
| `app/src/test/.../EditorExportTest.kt:292-311` | **HEIC 头必须 `assertFalse(isJpegSource(...))`** —— 新增 HEIF 导出后若改 `isJpegSource` 语义即红 |
| `app/src/test/.../StdColorMatrixTest.kt:56-65` | `X2S` 9 项按 `2e-3f` 逐项断言 + **每行和 = 1.0（白点守恒，`5e-3f`）** —— **重算 Rec.2020 矩阵必红** |
| `app/src/test/.../OutDigestTest.kt:26 / :34-40` | 输出摘要均值按 0..255 口径；CRC32 全量、单像素敏感、尺寸不一致不越界 |
| `app/src/test/.../EditorOverwriteWarnTest.kt:81-91` | DNG（`II*\0`）/ JPEG（`FF D8 FF`）文件头判据 |
| `app/src/test/.../Stage3EngineSlotPresetAuditTest.kt`、`EngineScopeTest.kt`、`EngineSlotUiTest.kt`、`EngineOverrideUiTest.kt` | `[98]` 插槽在 UI / 预设 / 作用域上的一致性 —— **切工作空间"所有预设重标"时，这组是现成的验收骨架** |

### D 组：对分期决策的直接含义（用户铁律的代码侧证据）

- `docs/engine-slots-design.md:441-446` 已写死：
  > **公共工作空间：已拍板（用户，2026-10-13）—— Rec.2020 线性，但必须单独一期**，不与插槽重构同期。…… ⚠ **换工作空间 = 整条链处理域改变（`refactor-architecture.md` 的 D4 定义变化）= 所有预设重标**，属**管线级改动**，必须单独一期 + 真机实测，**不可与插槽重构同期** —— 否则丢掉"`[98]=0` 时 CRC32 逐字节等价"这条零回归证明力。
- ⇒ **切工作空间那一期必须独占**：上表 A 组**全部**会同时变红，无法区分"工作空间改动的回归"与"同期其它重构的回归"。
- ⇒ **10bit HEIF 可与之解耦、先做**：它是输出端位深（`GL_RGBA8` → 10bit）与编码器新增，**不动 D4 定义、不动 `film_d5` 基线**（只要 10bit 路径不改 `kFsFilm` 与 `kFsOut` 的**数值**，只改末端承载精度）。
  ⚠ 但 `engine-slots-design.md:439-440` 已裁定：**10bit 只能救 sigmoid/ACES 的"量化不可逆"，救不了 filmic 的连续不可逆平顶** ⇒ "上 10bit"本身就要求换掉 filmic，否则位深收益被 plateau 吞掉（`docs/research/17-独立复核-影调架构量化.md:35`：filmic plateau 是**连续不可逆，任何位深都不可逆**；三种架构只用掉 8bit 上界的 32~41%）。

---

## ⑦ 未确认项

1. **`srgbEncode` 的实际份数与 `check-srgb-oetf.py` 提示语不符**：脚本 `:21` 写"kFsOut / kFsHist 应各一份"，实际 4 份（`:5019 / :5449 / :5960 / :6076`）。脚本用 `findall` 循环全验故不会失败，但**是否还有第 5 处**（如 `film/` 目录下的引擎 shader、`kFsSigmoidDef` 段内）未逐一确认。
2. **`kFsOut` 内注释与代码顺序漂移**：`:4803` 自述"暗角 → 颗粒 → LUT"，代码是"暗角（`:5152`）→ LUT（`:5164`）→ 颗粒（`:5170`）"。以代码为准；是否需要修订注释未与作者确认。
3. **`kFsOes:331` / `kFsStill:347` 的 `pow(rgb, vec3(2.2))` 是既有技术债还是刻意**：它与 `kFsOut` 的"真 sRGB 分段"（`:5019`，`check-srgb-oetf.py` 专门守护、并在 `:4-8` 说明 pow 近似暗部往返误差可达 9/255）**不同源**。YUV/OES 输入的 transfer 本就是 2.2 近似，还是漏改？**切 Rec.2020 前必须先定这个输入域口径**。
4. **`measureSceneStats`（`:13414`）内的两种折回分工**：对数域 `:13449 to_lin = exp2(enc*20-12)`（`uStatMode=1`）与 sRGB EOTF `:14245`。未确认 `:14245` 是否只在 sRGB 分支被调用、以及对数域分支是否绕过它。
5. **42 个 `.lut` 资产的实际域标签**：`FilmParams.h:125` 与 `docs/开发计划.md:20` 都声明 display 域，但资产是**自定义二进制格式**（非 `.cube`，见 `app/src/main/assets/luts/fuji_eterna.lut` 的二进制头），且 `tools/gen-lut-assets.py` 内未 grep 到域/primaries 声明 ⇒ **未验证资产内部是否携带域元数据**；若 10bit HEIF 走 Rec.2020 交付，LUT 输出色域标签需要重新标定。
6. **10bit HEIF 的 Android 侧选型未定**：`androidx.heifwriter.HeifWriter`（API 29+，吃 YUV/RGBA，**是否支持 10bit 输出未确认**）vs `MediaCodec`(HEVC Main10) + `MediaMuxer`（10bit 可行、复杂度高）。Gradle 无任何相关依赖，需新增。
7. **输出端 gamut mapping 的算法与落点未定**：`engine-slots-design.md:446` 只写"必须配输出端 gamut mapping"。当前 `kFsOut` 末端无 mapping，只有硬截断（`:5189`）。
8. **`tex_local_`（`:7868`）的 `GL_RGBA8` 是否构成隐藏降位点**：它是局部色调映射的低频图（管线位置在 filmic 之前）。若其承载的是"比值/增益"则 8bit 可能够用；若承载亮度绝对值则是比 `:8519` 更早的降位点。**未确认其物理量**。
9. **`docs/reports/stage0-engine-slot-BASELINE.md §3.3`** 未逐行核对（仅通过 `engine-shader-offline.py:30` 的引用确认其存在与作用）。
10. **10bit 交付对 `check-*` 系列的连带影响未评估**：`check-halation.py:27` / `check-softglow.py:19` / `check-style-passthrough.py:136` 都把"8bit 逐位"写进判据；10bit 期若改末端量化，这三处需同步改口径（或明确保持 8bit 验收面）。

---

### 附：本报告未使用的证据渠道

- 未执行任何构建、未运行 Gradle 单测、未跑 `tools/*.py`（判据结论均来自脚本源码中的断言文本）。
- 未通读 `FrameRenderer.cpp`（15000+ 行），所有结论均来自 `grep -n` 定位后的定点精读片段。
