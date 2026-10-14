# 实现卡 A0 · 10bit HEIF 交付链真机能力探针（gate）— 交付报告

> 卡片：**实现卡 A0**（方案 `docs/rec2020-heif-design.md` §4.1 的 gate）。
> 日期：2026-10-14。
> 交付形态：**可装包 + 用户点一下出结论的诊断探针**（不依赖拍照、不依赖 native 渲染）。
> 边界：本卡**只诊断**，不接入拍照/交付路径；**未改动** `core/engine/`（native）、
> `CameraController.kt`、`FrameRenderer.cpp`（另一张卡正在改这些文件，避免冲突）。
> 出包：`filmkit-0.11.32-HEIF10-PROBE.apk`（versionCode 381 / versionName 0.11.32）。

---

## ① 这张卡要回答的三个问题（方案 §4.1）

| # | 问题 | 探针里的对应动作 | 结论落在哪条日志 |
|---|---|---|---|
| Q1 | X300 上是否有可用于 **still image** 的 **HEVC Main10** 编码器、是否支持 **P010（10bit）** 输入？ | 遍历 `MediaCodecList` 的全部 HEVC 编码器：`profileLevels`（含 Main10 / Main10HDR10 / Main10HDR10Plus）、`colorFormats`（含 `COLOR_FormatYUVP010`=54）、`FEATURE_HdrEditing`/`FEATURE_HlgEditing`、尺寸对齐与量程；再**真编一帧**验证 | `heif10.encoder`（每个编码器一条）+ `heif10.probe` |
| Q2 | `MediaFormat` 的 `color-standard`/`color-transfer`/`color-range` 是否**真的写进**产出的 HEIF `colr/nclx` box？ | 显式设 `BT2020`(6) + `LINEAR`(1) + `FULL`(1) → 编码 → `MediaMuxer(MUXER_OUTPUT_HEIF)` 落盘 → **回读自己产出的 .heic**，解析 `colr` box 的 `nclx` 四元组，与请求值逐项对照 | `heif10.nclx`（`nclxMatch` = 判据 A1） |
| Q3 | 产出的 10bit HEIF 是否编码成功、文件结构与位深如何（`bit_depth_luma_minus8` 等）？ | 解析 `hvcC`（HEVCDecoderConfigurationRecord）：`general_profile_idc` / `tier` / `level_idc` / `chromaFormat` / `bitDepthLumaMinus8` / `bitDepthChromaMinus8`，外加 `ispe` 尺寸与顶层 box 清单 | `heif10.nclx`（位深字段）+ `heif10.probe` |

> **方案原话的第三问**（"下游如何解释 transfer=8"）**不在本探针范围**：那需要系统相册/微信/PC
> 逐端人工看（方案 §4.5 判据 F），本卡只负责把"带正确标签的 10bit HEIF"产出来。

---

## ② 用户操作步骤（3 步）

### 步骤 1 · 装包
```bash
adb install -r filmkit-0.11.32-HEIF10-PROBE.apk
```
（包在仓库根目录：`filmkit-0.11.32-HEIF10-PROBE.apk`，versionName **0.11.32** / versionCode **381**）

### 步骤 2 · 点一下跑探针
App 内：**设置（右上角）→ 「实验与诊断」抽屉 → 「10bit HEIF 能力探针」**（抽屉内第一项）。

点完按钮标题变成「10bit HEIF 探针（运行中…）」，几秒后下方出现两行结论，形如：
```
10bit HEIF 探针输出：/storage/emulated/0/Android/data/com.filmkit.app/files/heif10
main10-p010(c2.vivo.encoder.hevc): colr=nclx primaries=9/transfer=8/matrix=9/full=1 bitDepthLumaMinus8=2 hvcC后=true ｜ ctrl-8bit(...): ...
```
（上面是**格式示例，不是实测值** —— 本卡没有 X300，真值必须由这次运行产出。）

### 步骤 3 · 导出结论（两条独立通路）
1. **日志（全部结论都在这里）**：设置 → 「日志」 → 「导出 JSONL」，存成 `filmkit-logs-*.jsonl`。
   需要的三类事件：`heif10.probe`（流程/每步成败）、`heif10.encoder`（编码器能力清单）、`heif10.nclx`（容器回读）。
   日志本体同时留在设备 `Android/data/com.filmkit.app/files/logs/` 下。
2. **产出的 .heic（容器证据，可用 PC 端 `heif-info`/`exiftool` 复核）**：
   ```bash
   adb pull /sdcard/Android/data/com.filmkit.app/files/heif10/ ./heif10
   ```
   目录里会有两个文件：`probe-main10-p010-<ts>.heic`（10bit 主路径）与
   `probe-ctrl-8bit-<ts>.heic`（8bit 对照路径）。
   ⚠ Android 11+ 的系统文件管理器**看不到** `Android/data/`，所以推荐 `adb pull`
   （debug 包也可 `adb shell run-as com.filmkit.app` 进内部目录，但外部 files 目录只走 adb pull）。

---

## ③ 探针流程（每一步都独立报告成败）

1. `heif10.probe phase=start`：SDK / 机型 / 尺寸 / 帧数 / 输出目录。
2. **枚举编码器**（Q1）：`MediaCodecList(REGULAR_CODECS)` 过滤 `isEncoder && 支持 video/hevc`，逐个 dump。
3. **CPU 造已知图案**：1024×256；上半 = **水平 1024 级灰阶**（第 x 列 = 码值 x，中性色度）；
   下半 = **8 个色块**（饱和红/绿/蓝/黄/青/品红 + 白 + 黑），按 BT.2020 NCL 全范围系数
   （`Y'=0.2627R+0.6780G+0.0593B`；`Cb` 除 1.8814 / `Cr` 除 1.4746）算成 10bit YCbCr。
4. **编码 1 帧**（探针口径）：`MediaCodec` HEVC + `KEY_COLOR_FORMAT=P010` + `KEY_PROFILE=HEVCProfileMain10`
   + `KEY_LEVEL` + `KEY_COLOR_STANDARD/TRANSFER/RANGE` 显式设置 → `MediaMuxer(MUXER_OUTPUT_HEIF)` 落盘。
5. **回读 `.heic`**（Q2/Q3）：递归遍历 box 树（`meta`→`iprp`→`ipco`）取 `colr`(nclx) / `hvcC` / `ispe`，
   另做一次全文 box type 命中计数兜底（`colrHits`/`hvcCHits`）。
6. **8bit 对照臂**：完全相同的 `color-*` 键，改走 `COLOR_FormatYUV420Flexible`。
   存在的理由：机型若**没有** P010，Q2（标签是否落盘）仍能被这一臂回答；两臂 nclx 的差异
   本身就是"位深是否影响标签"的一手证据。
7. `heif10.probe phase=end` + `nativeFlushLogs()`；返回值 = 输出目录 + 两臂一句话摘要（设置页直接显示）。

**失败策略**：任一步失败只记日志（含 `MediaCodec` 抛出的**异常类名 + message + 栈前 6 帧**），
不吞异常、不中断后续检查项；两条臂互不影响。

---

## ④ 代码落点清单（文件:行）

| 文件 | 行 | 内容 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/diag/Heif10BitProbe.kt`（**新增**，729 行） | :34-86 | **Android 常量核实块**（逐条 `javap` 来源 + Android↔CICP 映射警告 + P010 对齐约定） |
| | :106 | `run(context)` 入口：输出目录 + 流程编排 + 摘要返回 |
| | :158 | `probeEncoders()`：HEVC 编码器能力 dump（Q1） |
| | :210 / :227 | profile/level 与色彩格式的符号名映射（未列出时给数值/十六进制） |
| | :244 | `buildPattern()`：1024 级灰阶 + 8 饱和色块 |
| | :272 | `rgb2020ToYuv10()`：BT.2020 NCL 全范围 RGB→YCbCr10 |
| | :289 | `runArm()`：按能力选编码器 + 一臂完整流程 |
| | :337 | `encodeHeif()`：MediaFormat 组装 + MediaCodec + MediaMuxer |
| | :354-356 | `KEY_COLOR_STANDARD=BT2020(6)` / `KEY_COLOR_TRANSFER=LINEAR(1)` / `KEY_COLOR_RANGE=FULL(1)` |
| | :363 | `KEY_PROFILE=HEVCProfileMain10(2)` + `KEY_LEVEL=HEVCMainTierLevel41` |
| | :383 | `MediaCodec.createByCodecName(info.name)`（按能力选型，见 §⑦-1） |
| | :388 | `MediaMuxer(path, MUXER_OUTPUT_HEIF(3))` |
| | :484 | `fillInput()`：优先 `getInputImage()`，失败兜底按 `KEY_STRIDE`/`KEY_SLICE_HEIGHT` 手填 |
| | :569 | `putSample()`：P010（MSB-aligned 16bit）/ 8bit 写样本 |
| | :584 / :603 | `HeifFacts` + `parseBoxes()`（**纯函数**，无 Android 依赖，供单测） |
| | :614 | `parseHeif()`：回读 + 请求值/实际值逐项对照 + `heif10.nclx` |
| | :660 | `walkBoxes()`：box 树递归（`colr`/`hvcC`/`ispe`） |
| | :712 | `countHits()`：全文 box type 命中计数（解析器兜底） |
| `app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt` | :822-852 | **唯一 UI 改动**：「实验与诊断」抽屉内新增一行 `SettingNavRow` + 结果 `Text`（后台协程跑探针，UI 不阻塞）；imports 只加 4 条 |
| `app/src/test/java/com/filmkit/app/diag/Heif10BitProbeTest.kt`（**新增**，116 行） | :1-116 | HEIF box 解析器单测 4 条（nclx 正常值 / 8bit+limited / 无 colr / 截断垃圾字节） |
| `app/build.gradle.kts` | :87-88 | versionCode 380→**381**，versionName 0.11.31→**0.11.32** |

**没有改动的**：`core/engine/**`（native）、`CameraController.kt`、`FrameRenderer.cpp`、
`AndroidManifest.xml`（探针不需要新权限；写的是应用自己的外部私有目录）、任何依赖。

---

## ⑤ Android 常量核实记录（铁律：键名/取值不凭记忆）

来源：本机 `javap -constants -classpath /Users/wl/Library/Android/sdk/platforms/android-36/android.jar <类>`
（2026-10-14 实测输出，逐条抄录；SDK = `platforms/android-36`）。**下面每条都写进了源码注释块**
（`Heif10BitProbe.kt:34-86`）。

| 常量 | 值 | 备注 |
|---|---|---|
| `MediaFormat.KEY_PROFILE` / `KEY_LEVEL` | `"profile"` / `"level"` | 官方文档：设 profile 必须同时设 level；profile 只是 hint |
| `MediaFormat.KEY_COLOR_STANDARD` | `"color-standard"` | `BT709=1` / `BT601_PAL=2` / `BT601_NTSC=4` / **`BT2020=6`** |
| `MediaFormat.KEY_COLOR_TRANSFER` | `"color-transfer"` | **`LINEAR=1`** / `SDR_VIDEO=3` / `ST2084=6`(PQ) / `HLG=7` |
| `MediaFormat.KEY_COLOR_RANGE` | `"color-range"` | `FULL=1` / `LIMITED=2` |
| `MediaFormat.KEY_STRIDE` / `KEY_SLICE_HEIGHT` | `"stride"` / `"slice-height"` | **是公开常量**（API 23），不是 vendor 私有键 |
| `MediaFormat.MIMETYPE_VIDEO_HEVC` | `"video/hevc"` | |
| `CodecCapabilities.COLOR_FormatYUVP010` | **54**（0x36） | API 33；`COLOR_FormatYUVP210=60` |
| `CodecCapabilities.COLOR_FormatYUV420Flexible` | 2135033992（0x7F420888） | 8bit 对照臂用 |
| `CodecCapabilities.COLOR_Format32bitABGR2101010` | 2130750114（0x7F00AAA2） | 另一条 10bit 输入候选（本探针未走） |
| `CodecCapabilities.FEATURE_HdrEditing` / `FEATURE_HlgEditing` | `"hdr-editing"` / `"hlg-editing"` | 运行期能力门，已 dump |
| `CodecProfileLevel.HEVCProfileMain10` | **2** | `Main=1` / `MainStill=4` / `Main10HDR10=4096` / `Main10HDR10Plus=8192` |
| `CodecProfileLevel.HEVCMainTierLevel41` | 4096 | 本探针请求的 level |
| `MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF` | **3** | API 28 |

**⚠ 本卡发现的关键陷阱（已写进源码注释）**：Android 的 `COLOR_STANDARD_*` / `COLOR_TRANSFER_*`
**不是 CICP 值**，中间隔着一层平台映射：

| 语义 | Android 枚举 | HEIF `nclx`（CICP） |
|---|---|---|
| BT.2020 原色 | `COLOR_STANDARD_BT2020` = **6** | `colour_primaries` = **9** |
| 线性传递函数 | `COLOR_TRANSFER_LINEAR` = **1** | `transfer_characteristics` = **8** |

所以"设了 6 ⇒ 落盘 9"是**推导，不是事实** —— 这正是 Q2 必须真机实测的原因，也是本探针
`nclxMatch` 判据的取值依据（期望 `primaries=9 / transfer=8 / full_range=1`）。

---

## ⑥ 编译与测试结果

```
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
```
- `compileDebugKotlin`：**BUILD SUCCESSFUL**，探针文件 **0 warning**（初版两条 deprecation 警告已消除）。
- `testDebugUnitTest`：**BUILD SUCCESSFUL**，`TOTAL=668 FAIL=0 ERR=0`
  （现有 664 条 + 本卡新增 4 条 `Heif10BitProbeTest`，**全绿**）。
- 新增的 4 条单测覆盖的是本探针**唯一的自动化判据链**（nclx 四元组 + hvcC 位深）：
  手工按 ISO/IEC 23008-12 / 14496-15 构造字节，断言解析结果与字段偏移；
  含"没有 colr box"与"截断垃圾字节"两个负例 —— 用于把"编码器没写标签"与"我们没解析到"分开。

---

## ⑦ 出包与产物核对

- 出包：`rm -rf app/build/outputs/apk` → `./gradlew assembleDebug` → 复制到仓库根并改名
  `filmkit-0.11.32-HEIF10-PROBE.apk`（42,210,092 字节）。
- 包内 `lib/arm64-v8a/libfilmengine.so`：**9,074,232 字节，md5 = `8949b4a514883909f5498b3f334fce01`**
  （本卡未改 native，无需与旧版比较）。
- 包内确有探针类：`classes3.dex`（45 处引用）+ `classes5.dex`（1 处）。
- 产物清单：`classes*.dex` × 10 + `lib/arm64-v8a/libfilmengine.so`。

### 7.1 ⚠ 与并行卡的交集（必须知道）
本卡在**共享工作区**执行，同一时段另一张卡（期 A 的 **4.2 位深收口**）也在改代码：
`core/engine/src/render/FrameRenderer.cpp`（16:06）、`OutputBitDepth.h`（16:05）、
`CameraController.kt` / `EditorEngineSession.kt` / `EngineBridge.kt`（16:07）——**均早于本次出包（16:13）**。
因此：

- 本卡的**源码改动与之零重叠**（本卡只碰 `diag/`、`SettingsScreen.kt`、`app/build.gradle.kts`）。
- 但**这一版 APK 的 native 与其 Kotlin 侧包含了并行卡的改动**（其目标是"行为零变化"，未由本卡验证）。
- 对探针结论**无影响**：探针只走 Android 平台 API（`MediaCodec`/`MediaMuxer`）+ 纯 Kotlin 解析，
  不调用 `EngineBridge` 的渲染/编码入口，只用它的日志通道（`nativeAppendLog`）与 `kv()`。
- 上面的 `libfilmengine.so` md5 是**那一版构建**的产物；若需要"只含 A0 改动"的严格基线包，
  需在并行卡暂停落盘的窗口重出一次。


---

## ⑧ 还没验证的项（**本卡最重要的诚实清单**）

### 8.1 探针本身没在真机跑过（首要）
本卡手上**没有 X300**，因此 Q1/Q2/Q3 的**真机结论仍是空的** —— 交付的是"能出结论的工具"，
不是结论本身。以下每一条都要等这次运行的数据。

### 8.2 编码与输入侧（可能让主路径失败或数据错位）
1. **P010 样本对齐假设未验证**：探针按 **MSB-aligned**（10bit 值放 16bit 高 10 位，`v10 shl 6`）写。
   若该机型要求 LSB-aligned，灰阶会整体量化错位。**但 nclx 与 `bitDepthLumaMinus8` 判据不受影响**
   （它们只看容器，不看像素值）。要证伪必须解码回像素 —— 本探针不解码。
2. **P010 的 UV 平面顺序（U 在前）与交错布局未验证**；`getInputImage()` 路径不受影响（用编码器自报的
   stride），**手填兜底路径**受影响。
3. **`getInputImage()` 对 `COLOR_FormatYUVP010` 是否返回可用 Image 未知**：真机才知道走 Image 路径
   还是手填路径（日志 `phase=inputImage` / `phase=inputBuffer` 会明确写出来）。
4. **`KEY_PROFILE` 只是 hint**：编码器可能静默换档。我们能看到的只有 `hvcC.general_profile_idc`
   与 `bitDepthLumaMinus8`；**"内部是否真按 10bit 精度处理"无法从容器证明**。
5. **`matrix_coefficients` 实际值**：Android 侧没有 matrix 键，期望由 `color-standard=BT2020` 推导为 9；
   实际值只能实测（`heif10.nclx` 的 `matrix` 字段）。
6. **HEIF 是否被 grid 分片**：AOSP `MediaMuxer` 默认单 item，但未在 X300 上验证（看 `topLevel` 与
   `colrHits`/`hvcCHits`；分片会让命中数 >1）。

### 8.3 分辨率/性能（方案的 §7-4/§7-5，本卡不覆盖）
7. **最大支持尺寸、各位深下的对齐、48MP 级可用性**：只 dump 了 `VideoCapabilities`，没实测多分辨率档。
8. **10bit 全分辨率编码耗时与内存**：本探针只编 1 帧 1024×256，**没有打点**，不能回答快门等待与 OOM 风险。

### 8.4 交付链语义（交给期 A 的后续卡）
9. **下游解释（判据 F）**：系统相册 / 微信 / PC 如何解释 `transfer=8`（线性）—— 需要人工逐端看，
   本卡不覆盖。这也是方案 §6 待拍板第 3 项（线性 vs PQ/HLG vs 双出）的输入。
10. **`transfer=8` 的观感**（是否发灰/偏亮）与"是否必须同时出 SDR 备份"：同上，人工判据。
11. **编码器实际写入的 `nclx` 是否被下游认**：本探针只证明"我们产出的容器里有什么"，不证明"别人怎么读"。

---

## ⑨ 与方案的偏离 / 工程判断（都已在源码注释里写明）

1. **`createByCodecName` 代替 `createEncoderByType`**：方案 §4.3 写 `createEncoderByType("video/hevc")`，
   但探针的任务是"查哪个编码器真能做" —— 系统默认编码器可能不是声明 P010 的那个。
   故按能力选型后用 `createByCodecName`；**若 X300 的默认 HEVC 编码器即所选，两者等价**
   （`codecName` 每次运行都记进日志，供核对）。
2. **多了一条 8bit 对照臂**（方案没写）：目的是让机型即使没有 P010 也能回答 Q2，
   且两臂 nclx 差异是"位深是否影响标签"的一手证据。
3. **帧数 = 1**：HEIF 是 still image；多帧会变成多 item 容器，不是本 gate 的目标。
   分辨率 1024×256（水平 1024 级灰阶需要宽度 ≥ 1024）、码率 4 Mbps、帧率 30、I 帧间隔 1s
   —— **全部是探针口径，非交付口径**（源码注释块已标）。
4. **不做 App 内 .heic 导出按钮**：解析结论已 100% 进 JSONL（可从设置页导出），
   `.heic` 只是给 PC 端复核用的附加证据，走 `adb pull` 即可（少一处 UI 改动）。

---

## ⑩ 取证边界

- 本卡**只**新增 2 个文件（探针 + 单测）、改 2 个文件（设置页入口、版本号），**未碰** native 与拍照链。
- 未在真机运行；§⑧ 全部为"待实测"。**在拿到 X300 这次运行的数据前，本卡不给"能不能做"的结论。**
- 常量与 API 事实来自本机 `android-36` 的 `javap` 实测输出；CICP 值表来自方案引用的 libheif 一手源码
  （`heif_color.h:140-193`）；HEVC 配置记录字段偏移来自 ISO/IEC 14496-15。
