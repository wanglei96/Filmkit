# RAW 去马赛克：PC 实现真相、可复用开源盘点、我方基线（2026-11-12）

- 触发：性能专项里 RCD 的 `dirMs`（方向场）占单张 8s 的 ~1.2–1.5s，用户问「能否移植 PC 的算法 / 有没有更快更好的开源实现」。
- 结论一句话：**PC 没有可移植的独立 demosaic；Spektrafilm/SpektraLab 也没有（都用 LibRaw）；开源里没有现成的"更快更好"GPU 实现。** 我方 GPU RCD 已是第一梯队，优化方向是把结构做回 RawTherapee 标准（半分辨率方向场 + `lpf` 输入），不是换算法。

> ⚠️ 本文修正了两处历史文档误读（`rcd-linecompare` 报告之前的 `RawLinearRcdPass` 命名误读），并记录一个尚未解释的基线矛盾（见 §4）。

---

## 1. PC 的 RAW 链路到底用什么（证据）

### 1.1 真机 RAW 拍摄：GPU 融合内置绿重建，没有独立 demosaic

- `app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt:44-52` 头注释：
  > The stage order, constants and shader equations come from this APK's `libgcastartup.so`.
  > … The RGB output executes MGC 9.7.047 V25's complete native-grid `MergeRgbRaw16F16` AOT,
  > including its **internal full-resolution green reconstruction**. Sabre likewise completes
  > fusion, ResolveSabre and **VGN** on the native grid.
- 真机日志（X300，2026-11-12 15:43:11）：
  - `MGC Spatial V25 RGB merge frames=2 native=4080x3072 output=4080x3072 merge=256ms`
  - `MGC Spatial RGB dispatch complete frames=2 took=619ms`
  - `RAW_DNG_BUFFER_BYPASS source=GPU_STACK_TEXTURE`（融合后的 RGB 直接当 LinearRaw，不经独立 demosaic）
- 即：**去马赛克不是单独一级**，绿重建被包在 MGC/Sabre 融合里，随后 VGN 做色噪声。它来自 GCam 专有二进制的逆向，许可不清晰，且与我们「4 帧 Bayer 域对齐 + DFT 融合」的架构强耦合，**不适合移植**。

### 1.2 源码里的 `RcdDemosaicAlgorithm.kt` 是死代码

```bash
grep -rn "RcdDemosaicAlgorithm" app/src/main --include=*.kt | grep -v "class RcdDemosaicAlgorithm"
# → 无输出（全仓零实例化、零调用）
```

### 1.3 AMaZE 存在，但只在 LibRaw（DNG 文件路径）

- `app/src/main/cpp/LibRaw/src/demosaic/amaze_demosaic.cpp`
- `app/src/main/cpp/LibRaw/src/postprocessing/dcraw_process.cpp:189`：`else if (quality == 14) amaze_interpolate();`
- Java/Kotlin 侧入口：`RawDemosaicProcessor.kt:242 processDngNative(...)`，注释「使用 LibRaw 处理 **DNG 文件**」。
- 结论：用户记忆里的「PC 用 AMaZE」有出处——但那是**导入 DNG 文件**的路径，不是相机 RAW 流。

### 1.4 `RawLinearRcdPass` 是命名误读（修正 06 号文档）

`RawLinearRcdPass.kt` 的 shader 只做：`rgb = CCM * rgb` → 可选 clamp → DCP HueSatMap → `rgb *= uExposureGain`。
名字里的 "Rcd" 是历史残留，**与 RCD 算法无关**。06 号文档原句「该 pass 名为 `RawLinearRcdPass`（Linear + RCD 去马赛克），本身即其域标注」已改。

---

## 2. Spektrafilm / SpektraLab 内部有 demosaic 吗：没有

| 项目 | RAW 解码 | demosaic 归属 |
|---|---|---|
| Spektrafilm（`/tmp/spektrafilm-main`） | `rawpy`（LibRaw 的 Python 绑定，`pyproject.toml` 依赖） | LibRaw；项目只做胶片光谱/密度 |
| SpektraLab-Android（`SAINTTaiYi/SpektraLab-Android`，Vulkan） | C++ `#include <libraw/libraw.h>` + `dcraw_process` | LibRaw；注释实测「20MP 一次 `dcraw_process` 2.4s」 |
| SpektraLab Windows 系（多个转译版） | 同上 | 同上 |

两者都是 LibRaw 的调用方，不提供可复用的去马赛克实现。

---

## 3. 开源实现盘点（12MP / 手机）

| 实现 | 运行域 | 质量 | 速度 | 可复用性 |
|---|---|---|---|---|
| AMaZE（LibRaw / RawTherapee） | CPU | 第一梯队 | 秒级（2–4s） | 可作**离线质量基准**，不进实时链 |
| RCD（RawTherapee / darktable） | CPU | 接近 AMaZE | 快 | 我方已 GPU 化 |
| LMMSE / DHT / AAHD / VNG4 | CPU | 中 | 中 | 无优势 |
| GPU 高质量 demosaic | — | — | — | **没有成熟公开实现** |

---

## 4. 我方 RCD 现状与两个未解问题

### 4.1 结构与 RT 的差别

| 项 | RawTherapee（`docs/reports/rcd-linecompare/rt-rcd_demosaic.cc`） | 我方 `kFsRcdDir` |
|---|---|---|
| 方向场分辨率 | `PQ_Dir / P_CDiff_Hpf / Q_CDiff_Hpf` = `tileSize*tileSize/2`（**1/2**，`col += 2`） | 全分辨率 `w×h` |
| 方向统计输入 | 全分辨率算出的 `lpf` 低通图（`lpindx = indx / 2`） | 原始 CFA 五点高通 |
| 每像素采样数 | ~9（lpf）+ 方向在 1/4 像素上 | ~45（编译器 CSE 后）～93（字面展开） |

### 4.2 定全分辨率的原因（查得到，但不是质量决策）

`docs/开发计划.md:164`（v0.5.28 评审记录）原文：

> 评审确认**全 GPU 实现**：重点控制 shader 分支发散（比值共用采样、**方向场一次算全图复用**），已按此落地 v0.5.28

即决策目标是「算一次给 P2/P3 复用」，**没有讨论过分辨率**；首次实现（`965d298`）就是全分辨率，**没有过半分辨率版本，也就没有过画质 A/B**。

### 4.3 已解：`dirMs` 的 54ms → 1200ms 不是退化，是**计时口径**把 HR+P0 算进去了

0.13.5（2026-11-12）把 `d0` 挪到 P1 提交前（起点 `glFinish`）并加 `EXT_disjoint_timer_query`，
冷机支架同场景 3 张（16:50:47 / 16:51:13 / 16:51:40）：

| preMs（HR+P0） | dirMs（P1 净墙钟） | dirGpuMs（P1 GPU） | gMs（P2） | gGpuMs |
|---|---|---|---|---|
| 945 / 1002 / 1160 | 65 / 70 / 85 | 48 / 65 / 66 | 14 / 22 / 28 | 6 / 9 / 9 |

- **P1 方向场真身只有 65~85ms（GPU 48~66ms）**，与 v0.5.29 记录的 54ms 同量级 ⇒ 从来没有退化过。
- 旧的 `dirMs`（909~1486ms）实际是 **HR 高光重建 + P0 RAW bilateral + P1** 的合计；`d0` 打在
  HR/P0 之前，`d1` 打在 P1 的 `glFinish` 之后。
- 结论：**RCD 不是性能热点**。改半分辨率方向场最多省 ~50ms，不值得动质量管线。
- 真正的热点（同场景）：`fuseMs`（DFT）1299~1374ms、`preMs`（HR+P0）945~1160ms、
  `dnMs`（P4/PC3D/D1）687~945ms、编码 ~500ms。

---

## 5. 结论与下一步（2026-11-12 修正）

**不做 RCD 半分辨率改造**：P1 净耗时 65~85ms，省 50ms 不值得冒画质风险。

下一步按真实热点重排：
1. **拆分 `preMs`**：HR（`hlMs` 236~284ms）之外，P0 占 660~925ms —— 单列 `p0Ms` 确认。
2. **DFT 融合 1.3s**：先去「每次现场编译 compute shader」（`passMfnrFft` 里 `compileShader + linkProgram`，已知待办），再评估算法侧。
3. **P4/PC3D/D1 降噪 687~945ms**：与 PC 的 1340ms 同量级，不是弱项，但占总额可观。
4. 编码 ~500ms：stb 单线程；换 libjpeg-turbo 4:4:4 可省 300ms（新增依赖，待拍板）。
5. 预览卡顿：需「帧到达 vs 渲染完成」诊断区分 HAL 与 GPU 占用。

---

## 6. 复现命令

```bash
# PC 是否引用 RCD
grep -rn "RcdDemosaicAlgorithm" app/src/main --include=*.kt | grep -v "class RcdDemosaicAlgorithm"

# AMaZE 的位置
grep -rn "amaze_interpolate" app/src/main/cpp/LibRaw/src/postprocessing/dcraw_process.cpp

# 我方方向场尺寸与计时
grep -n "tex_dir_, &fbo_dir_" core/engine/src/render/FrameRenderer.cpp
grep -n "dirMs" core/engine/src/render/FrameRenderer.cpp

# 手机温度
adb shell dumpsys thermalservice | grep -E "Thermal Status|mName=(CPU|SKIN)"
```
