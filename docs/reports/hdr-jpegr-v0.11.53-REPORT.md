# HDR（Ultra HDR / JPEG-R + HEIC gainmap）v0.11.53 实施报告

## 一、PC 一手源码对比（2026-10-19，`bjzhou/PhotonCamera@27d4f78`）

用户提供了 PC 真源，此前记忆里记录的 `/tmp/photoncamera` 是空壳。对比结论：

| 交付格式 | PC 的实现 | 本项目 | 对齐情况 |
|---|---|---|---|
| **JPEG 4:4:4 + HDR** | `Jpeg444ExportEncoder.writeJpegR()` → `jpeg_r_encoder.cpp:packageJpegR()` → libultrahdr `set_compressed_image(BASE)` + `set_gainmap_image` | `UltraHdrEncoder.cpp` 同路径 | ✅ **逐行同源**，补齐 3 处（见 §三） |
| **JPEG 4:2:0 + HDR** | `UltraHdrWriter.writeJpeg()`：`bitmap.gainmap = gm` + `Bitmap.compress` | `PlatformGainmapWriter.writeJpeg420()` | ✅ 同做法（平台 API） |
| **HEIC + HDR** | `HeicExportEncoder.write()`：`bitmap.gainmap = gm` + `HeifWriter` | `PlatformGainmapWriter.writeHeic()` | ✅ 同做法（平台 API） |
| **相册 HDR 显示** | `MainActivity.applyPreferredWindowColorMode()`：`window.colorMode = COLOR_MODE_HDR` | `MainActivity.applyWindowHdrMode()` | ✅ 同做法 |
| **gainmap 数学** | `hdr/RawGainmapMath.kt`：`ln()` + offset `1e-4` + 固定 `[0.25, 4.0]` + `DOWNSAMPLE=2` | `kFsGainmap`（GLSL）+ `UltraHdrEncoder.cpp`（参照实现） | ✅ 逐字对齐 |
| **GPU 算 gainmap** | `GpuReferenceGainmapProducer`（GL 内算） | `kFsGainmap` pass | ✅ 同架构 |

**PC 的 base 编码器**：`writeNative`（自研 4:4:4）—— 与我们的 stb 4:4:4 同定位。
**PC 的平台限制**：4:2:0 与 HEIC 路径都要求 Android 14+（`UPSIDE_DOWN_CAKE`），且
`packageJpegR` 前置检查 `gainmapDirection == SDR_TO_HDR`。

## 二、两版 gainmap 数学 A/B 实测（`tools/gainmap-math-ab.py`）

真机 DNG（4080×3072），统一到 **8bit [0,1] 口径**后对比（⚠ 首版脚本直接拿未归一化的
`log2` 值比归一化的 PC 值，结论完全带偏 —— 已修正，这是同型于"D5 域口径"的方法论坑）：

| 项 | A 官方（自适应范围） | A' 官方（固定 0.25~4） | B PC（固定 0.25~4） |
|---|---|---|---|
| 8bit 值域 | 0 ~ 1（**吃满全量程**） | 0.501 ~ 0.750 | 0.502 ~ 0.838 |
| 暗部 gain（sdr<2/255） | **0.092**（几乎不增益） | — | 0.817 |
| 还原误差 mean / p99 | 0.23% / 0.38% | — | 0.27% / 0.54% |
| A' vs B 逐像素差 | — | mean 0.0069 / p99 0.127 / max 0.312 | — |

**结论：选 PC 版**。理由不是"PC 更好"，而是：
- 官方**自适应范围**在**无高光场景**会把 max 压到 0.44 ⇒ 8bit 码值全花在极窄区间，
  暗部被压到 0（完全不变亮）。本样片高光像素占比 0%，正是这种场景。
- PC 的固定 `[0.25, 4.0]` + offset `1e-4` 让**码值分配不随场景漂移**，暗部 ratio 天然
  趋近 1（不增益）—— 行为可预测。
- 两版逐像素差 mean 0.0069（≈0.7% 码值），在可接受范围内。

官方版**保留为可切换实现**（`computeGainOfficial` / `affineMapGain`，`[[maybe_unused]]`），
按项目铁律不预删。

## 三、按 PC 补齐的 3 处（4:4:4 路径）

1. **`uhdr_enc_set_output_format(enc, UHDR_CODEC_JPG)`** —— PC 显式选容器；默认虽为 JPG，
   但显式写出可防上游改默认值时静默变容器。
2. **产物自检 `is_uhdr_image()`** —— PC 编码后必须校验通过才落盘。不校验的话，元数据
   写坏的图会以"看起来正常的 JPEG"流出去，下游按 SDR 显示 —— **静默降级比直接失败更难查**。
3. **gainmap 的 `cg/ct/range = UNSPECIFIED`** —— PC 的做法。gainmap 是单通道辅助数据，
   色彩语义由 base 决定（`use_base_cg=1`）；填具体值会让 libultrahdr 试图按彩色解释它。

## 四、关键架构决策：gainmap 必须在 GPU 算

**约束**（项目 2026-10-05 真机实测）：ES 3.0 **只保证** `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)`，
浮点纹理读回需要扩展，真机没有。且该失败路径当年还破坏了渲染状态 ⇒ 成片全黑。

**因此**：不能把 `tex_scene_`（RGBA16F 场景线性）直接读回 CPU。方案改为：

```
tex_scene_ (D4 Rec.2020 线性) ─┐
                                ├→ kFsGainmap (GLSL) → tex_gainmap_ (RGBA8, 1/2 分辨率)
tex_cap_   (D5 sRGB 8bit)     ─┘                              ↓ glReadPixels (8bit, 保证可用)
                                                     nativeReadGainmap() → 3MB（12MP 时）
                                                              ↓
                                              UltraHdrEncoder (stb 4:4:4 base + 封装)
```

**域口径**（`kFsGainmap` 的核心）：HDR 侧先 `workToSrgb`（与 `kFsOut` 同一矩阵）转 sRGB 线性，
SDR 侧先反 OETF 回 sRGB 线性，再各取 Rec.709 luma。**不能**直接拿 Rec.2020 的 Y 系数算 HDR luma
（同型于 D5 域口径 bug）。

## 五、实现清单

| 文件 | 改动 |
|---|---|
| `core/engine/third_party/ultrahdr/`（新增 1.6MB） | vendored libultrahdr v2.0.2 + image_io |
| `core/engine/third_party/ultrahdr/CMakeLists.txt` | vendor 构建（libjpeg-turbo 自动拉） |
| `core/engine/third_party/ultrahdr/download.cmake` | tarball 下载 + 3 次重试 |
| `core/engine/CMakeLists.txt` | `option(FILMKIT_HDR OFF)` + `include()`（非 add_subdirectory） |
| `core/engine/src/jni/UltraHdrEncoder.cpp` | JNI 封装（base 4:4:4 + 预计算 gainmap + libultrahdr） |
| `core/engine/src/jni/Bridge.cpp` | `nativeHdrAvailable` / `nativeReadGainmap` / gainmap 尺寸 |
| `core/engine/src/render/FrameRenderer.cpp` | `kFsGainmap` shader + `passGainmap` + `readGainmap` |
| `core/engine/src/render/FrameRenderer.h` | gainmap 纹理/成员/接口声明 |
| `app/src/main/java/com/filmkit/app/hdr/HdrDelivery.kt`（新增） | 能力探测 + JPEG/R 编码 + 平台 Gainmap 构造 |
| `app/src/main/java/com/filmkit/app/hdr/PlatformGainmapWriter.kt`（新增） | JPEG 4:2:0 + HEIC 两条平台路径 |
| `app/src/main/java/com/filmkit/app/EngineBridge.kt` | 3 个新 JNI 声明 |
| `app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt` | 「HDR 成片」开关（**默认关**） |
| `app/src/main/java/com/filmkit/app/MainActivity.kt` | 窗口 HDR 模式（相册 HDR 显示前提） |
| `app/src/main/AndroidManifest.xml` | `tools:overrideLibrary="androidx.heifwriter"` |
| `app/build.gradle.kts` | `heifwriter:1.1.0` 依赖 + `-PfilmkitHdr` 开关 |
| `tools/gainmap-math-ab.py`（新增） | 两版数学 A/B 实测脚本 |

## 六、实测数据

**封装闭环**（host，官方解码器回读）：
- `uhdr_is_supported_gainmap_image = 1`
- base SOF0：**4:4:4 保住**（h=1 v=1 三分量）
- gainmap：160×120（1/2 分辨率）
- 元数据回读：`maxBoost=4.0 / minBoost=0.25 / gamma=1.0 / hdr_capacity=[1.0, 4.0] / use_base_cg=1`
- HDR 解码：出 half-float 图

**构建数据**：
| 配置 | APK | 说明 |
|---|---|---|
| `FILMKIT_HDR=OFF`（默认） | **51.6MB** | 日常构建，无 HDR |
| `FILMKIT_HDR=ON` | **64.0MB** | +12.4MB（libjpeg-turbo + libultrahdr 静态链接） |

⚠ **+12.4MB 是显著体积代价**。若要压缩，方向：只保留 libultrahdr 的封装路径
（裁掉 HEIF/AVIF/GLES 分支）+ 用 `-Os` 编 libjpeg-turbo。**未实测，待定**。

## 七、交付链接入（已完成）

### 线程模型（本期的核心难点）

`nativeReadGainmap` 读的是**拍照渲染线程** EGL 上下文里的 `tex_gainmap_`，而落盘在
`saver` 单线程。链路：

```
StillRenderClient.submitRawBurst { onDone(bytes, w, h) }   ← 拍照线程（GL 上下文在此）
  └─ CameraController.done lambda                          ← **同线程**，此处读 gainmap
       ├─ readGainmapSnapshot()  → nativeReadGainmap()     ← 必须在这里，下一次 submit 会覆盖
       └─ deliverStill(..., gainmap)                       ← 渲染线程
            └─ queueJpegSave(..., gainmap)                 ← 渲染线程
                 └─ saver.execute { saveJpeg(..., gainmap) } ← saver 线程
```

**为什么不能晚读**：`tex_gainmap_` 是复用的成员纹理，下一次拍照会覆盖它。所以必须在
`onDone` 内、`scheduleIdleRelease()` 之前读出。

### 三条路径的优先级（`saveJpeg`）

| 条件 | 路径 | base 色度 |
|---|---|---|
| `jpeg_444` 开 + 有 gainmap | **自研 JPEG/R**（libultrahdr 封装） | **4:4:4** |
| `jpeg_444` 关 + 有 gainmap + Android 14+ | 平台 Gainmap（`Bitmap.setGainmap` + `compress`） | 4:2:0 |
| 其余 | 普通 SDR JPEG（原行为一字不动） | 4:4:4 或 4:2:0 |

任何一步失败 ⇒ 落回普通 SDR JPEG（照片优先红线）。

### 画幅裁剪的 gainmap 同步（易错点）

`saveJpeg` 里有"成片按画幅居中裁切"（WYSIWYG），裁后 `outBmp` 小于渲染尺寸。
而 gainmap 是按**渲染尺寸**算的 ⇒ **不裁就错位**，HDR 增益会落在错误位置。
已加 `cropGainmap()`：源像素 (x,y) ↔ gainmap (x/2, y/2)，裁剪起点同样除 2 取整，
越界返回 null（该张回落普通 JPEG，宁可无 HDR 不可错位 HDR）。

## 八、HEIC + HDR：需要拍板的质量取舍

**当前状态：代码已实现，但未接入交付链。**

| 方案 | 位深 | gainmap | 实现 |
|---|---|---|---|
| 项目现有 HEIC | **10bit**（自研容器 + HEVC Main10） | ❌ | 已真机验收 |
| PC 的 HEIC HDR | **8bit**（平台 `HeifWriter`） | ✅ | `PlatformGainmapWriter.writeHeic()` 已写好 |

**冲突**：开 HDR 要从 10bit 降到 8bit。这不是"顺手加上"能解决的 —— 要么接受降位，
要么给自研容器写 ISO 21496-1 gainmap box（工作量大、需真机验证 box 兼容性）。

按项目铁律「质量取舍必须实测、不许替用户拍板」，此处**留给用户决定**。

## 九、待真机验证

- ① 4:4:4 JPEG/R 在系统相册 / Chrome / Google Photos 是否显示 HDR 增益
- ② 4:2:0 JPEG + gainmap 同样验证
- ③ 微信 / Windows / macOS 是否回落 SDR（不偏色）
- ④ App 内相册/编辑器回看（期 A 已知缺口：HEIC 无特判，JPEG/R 未测）
- ⑤ 编码耗时（12MP：stb 4:4:4 + libultrahdr 封装 + gainmap 读回 + 裁剪）
- ⑥ gainmap 数值正确性（用 `tools/gainmap-math-ab.py` 对真机成片复算）
- ⑦ 画幅裁剪场景（16:9 拍 4:3 等）下 HDR 增益是否与画面吻合

## 十、工程坑（避免再踩）

1. **中文路径**：`add_subdirectory` 出来的深层作用域里 `file(GLOB)` / `add_library` 都解析
   不出源文件（`No SOURCES`），`file(RENAME)` 也失败。绕法 = 主 CMakeLists 用 `include()`，
   且全部用**相对 core/engine 的路径**。
2. **libjpeg-turbo 拒绝 add_subdirectory**（其 CMakeLists 第 61 行主动报错），必须
   `execute_process` 在配置期同步构建。
3. **stb 不能两个 TU 都 define `STB_IMAGE_WRITE_IMPLEMENTATION`**（duplicate symbol），
   第二个 TU 只 include 头文件复用第一份实现。
4. **XMP 必须显式开**：upstream 默认只写 ISO 21496-1，而 Android 相册/Chrome 认的是
   Adobe XMP gainmap（`UHDR_WRITE_XMP`）。
5. **heifwriter 要求 minSdk 28**，项目是 26 ⇒ `tools:overrideLibrary` + 运行时版本守卫
   （HDR 全部入口都有 `SDK_INT >= 34` 检查，26/27 永远走不到）。
6. **比色脚本必须先统一口径**：未归一化的 `log2` 值与归一化的 `[0,1]` 值不能直接比大小。
7. **gainmap 必须随成片同步裁剪**：成片画幅裁切后 gainmap 不裁 ⇒ HDR 增益错位。
