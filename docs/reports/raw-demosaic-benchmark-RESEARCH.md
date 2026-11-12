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

### 4.3 未解矛盾：`dirMs` 54ms（v0.5.29）vs 1200–1560ms（现在）

- `e0b9712` 提交信息：「On-device A/B (12MP X300): RCD 1969ms vs bilinear 359ms, gMs=1414ms dominant」，优化后 `dirMs=54ms`。
- 2026-11-12 同场景实测：`dirMs` 1202 / 1376 / 1433（波动 30%），`gMs` 18–22ms。
- 当前 shader 相比 v0.5.29 只多 `lpf`（9 taps）与 `uMos` 分支，不足以解释 20×。
- 推测方向（未验证）：当时的 `dirMs` 口径/尺寸与现在不同，或受 GPU 频率/热状态影响。**在解释清楚之前，任何"改半分辨率能省 X ms"的估计都不可靠。**

---

## 5. 下一步（按顺序）

1. **坐实基线**：冷机（Thermal Status 0）、支架固定、同场景，拍 3 张，记录 `dirMs/fuseMs/dnMs/passMs/totalMs` 与温度。
2. **`rcd_dir_half` 开关 A/B**（默认关）：方向场 1/2 分辨率 + `lpf` 输入（即 RT 结构）。
3. **三方对比**：现状 / RT 结构 / AMaZE（LibRaw 离线），用 `tools/rcd-linecompare*.py` 比边缘细节与伪彩，而不是目视。
4. 数据出来再决定是否默认开、是否延伸改其他 pass。

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
