# 开源相机的多帧 RAW 融合与提速手段调研（面向 T3 空间域融合替换 DFT）

- 调研日期：2026-11-12（Asia/Shanghai）
- 范围：其他开源相机 App / RAW 处理实现的**多帧 RAW 融合**及**提速手段**；不涉及我方业务代码改动
- 性质：**只读调研**。所有结论要么带 `仓库/文件:行`，要么明确写「无证据」
- 我方行号快照：`core/engine/src/render/FrameRenderer.cpp` 在调研期间正被并行修改（`git status` 显示 `M`），行号以 **2026-11-12 20:5x 快照**为导航；定位请优先用函数名 / shader 常量名 / 日志 key（`kFsMfnrFuse` / `kFsRawDenoiseFusedTs` / `kFsMfnrFft` / `passMfnrFft` / `mfnr_dft`）

## 核查方法与限制（先说清楚，避免把推断当证据）

1. **`web_search` 在本会话不可用**（缺 API key），全部证据来自 GitHub REST API、`raw.githubusercontent.com` 与本地 `git clone --depth 1`（克隆到 `/tmp/ossres/`，不在项目目录内，未污染工作区）。
2. **所有仓库 HEAD 均为 2026-11-12 抓取**，行号对应当次快照；仓库后续变化不回溯。
3. 「有/无多帧 RAW 融合」的判定标准是**源码里能读到融合逻辑**（权重、累加、对齐）。只有 API 头文件 + 预编译 `.so`、只有 APK、只有配置仓库的，一律记为「无可读实现」，不算证据。
4. 频率域/空间域的判定标准是**源码里有无 FFT/DFT/Wiener 相关算子**（不是看 README 描述）。

## 摘要（结论先行）

1. **开源里真正「多帧 RAW 融合 + 可读源码」的实现一共 5 个**：`timothybrooks/hdr-plus`（Halide/CPU）、`jhfmat/ISP-pipeline-hdrplus`（C++/CPU）、`amonod/hdrplus-python`（Python/CPU，唯一走 DFT 的完整 HDR+）、`KillerInk/FreeDcam`（DNG 文件级朴素平均）、`jcelaya/hdrmerge`（RAW 域曝光合并，按噪声选像素）。另有 PC（Kotlin/GLES、GPU、已知）与 Almalence 版 OpenCamera（融合逻辑闭源）。
2. **除 PC 外，没有任何一个开源实现把多帧 RAW 融合跑在 GPU 上。** 可读实现全是 CPU（Halide AOT、OpenMP、朴素循环）。想抄「GPU 上怎么快」只能抄 PC，其余只能抄**算法结构与权重设计**。
3. **HDR+ 最有名的 Halide 复现（`hdr-plus`）根本不用 FFT**：它是**空间域**、按 16×16 tile 的 L1 距离做时域加权（`merge.cpp:63-71`），tile 半重叠 + 升余弦窗空间混合（`merge.cpp:121-141`），融合输出仍是 **uint16 Bayer mosaic**（`merge.cpp:139`）。这直接证明「HDR+ 的空域变体」在 CPU 上是成熟、可跑通全链路的。
4. **空域实现的权重设计比我们想象的更简陋**：`hdr-plus` 与 `ISP-pipeline` 都**没有噪声模型**，权重只由 tile 平均 L1 距离决定（`1/max(1, dist/8−10/8)`，`dist>300` 直接置 0）；对齐只到 mosaic 偶数像素（`align.cpp:126` 的 `2*alignment`）。我们 `passRawDenoiseFused` 的 `σ²(x)=A·x+B`、饱和权重、短帧暗部惩罚、亚像素位移，在**噪声模型与对齐精度上已经领先**；缺的是**逐像素级 rejection 几何**（PC 有，开源没有）。
5. **对我们最有价值的不是换算法，而是换掉 DFT 的两笔硬成本**：4 张全分辨率 `RGBA16F` 写出 + 收口 pass 再全读一遍（≈400–900MB 带宽，见 `docs/reports/pc-still-perf-RESEARCH.md:83`）。`hdr-plus` 的做法——**融合直接产出 Bayer mosaic，全链路只有一次去马赛克**——正是省掉这两笔成本的形态。
6. **必须纠正一处内部口径**：`pc-still-perf-RESEARCH.md:209/211` 把 `passRawDenoiseFused` 记作「4 帧 × 3×3 = 36 样本」，但当前代码实际是 **±4、步长 2 的 5×5 同相位邻域**（`FrameRenderer.cpp:3685-3688`，自身注释 `:3704-3705` 也写「每帧 24 个同相位邻域 × 4 帧 = 96」）⇒ **96 样本**，不是 36。`16812` 处「4 帧各取 3×3 = 36 独立样本」是过时注释。做 T3 预算时必须按 96 算，否则会低估 2.7× 采样成本。
7. **用户点名的 `merkouris/hdrplus` 与 `johanneskopf/hdrplus` 在 GitHub 上不存在**（API 返回 404，`hdrplus` 全站搜索的 15 个结果里也没有这两个 owner）。见 ① 末尾「无证据清单」。

---

## ① 项目清单与是否有多帧 RAW 融合

### 1.1 总表

| # | 项目 | 多帧 RAW 融合 | 频域/空域 | 帧数 | 分辨率策略 | GPU/CPU | 关键证据 |
|---|---|---|---|---|---|---|---|
| 1 | `timothybrooks/hdr-plus`（Halide，MIT，673★） | **有**（对齐→融合→去马赛克→影调，完整 HDR+） | **空域**（无任何 FFT/DFT/Wiener 符号） | 由输入决定（`Raws.size()`） | 全分辨率 Bayer；权重统计在 1/2 层；对齐 tile 32 / hop 16；融合 tile 32 / hop 16 | **CPU**（Halide Generator AOT，无 Android/GPU target） | `src/merge.cpp:18-103`、`src/align.cpp:77-131`、`src/Burst.cpp:9-21` |
| 2 | `jhfmat/ISP-pipeline-hdrplus`（C++/VS，1020★） | **有**（HDR+ 全流水线，Windows 工程） | **空域**（块匹配 SAD + L1 权重；无 FFT） | 外部参数 `nFrameNum`（默认 1） | 全分辨率 RAW；块 16；权重按 32 窗半重叠；**定点 2^14** | **CPU**（含 NEON SAD 分支） | `Layer/HDRPlus_BlockMatchFusion.cpp:390-533`、`:580-625`、`Mat/Basicdef.h:126` |
| 3 | `amonod/hdrplus-python`（AGPL，190★） | **有**（HDR+ 全链路，论文级） | **频域**（`torch.fft` / `np.fft`，逐频 Wiener 收缩 + 频域空域降噪） | burst 目录内 `.dng` 数量 | 全分辨率 Bayer；tile 16；temporal/spatial 两级收缩 | **CPU**（numpy/torch，无 CUDA 路径要求） | `package/algorithm/merging.py:193-220`、`:335-393`、`params.py:45` |
| 4 | `KillerInk/FreeDcam`（372★） | **有但极简**（DNG 文件级堆栈） | 空域（逐像素 `(new+acc)/2`） | 任意文件数 | 全分辨率 16bit CFA | CPU 单线程 | `app/src/main/jni/freedcam/DngStacker.cpp:177-211` |
| 5 | `jcelaya/hdrmerge`（GPL，HEAD 2026-07） | **有**（多帧 RAW → 单 DNG，曝光包围） | 空域（**逐像素选帧**（README 称 "less noisy pixels"）+ MTB 平移对齐） | ≥2，任意 | 全分辨率 RAW（16/24/32bit 输出） | CPU（OpenMP） | `src/ImageStack.cpp:126-155`、`src/Image.cpp:179-223`、`README.md:3,21` |
| 6 | `almalence/OpenCamera`（1321★，HEAD 2018-10） | **有 API、无可读实现** | 未知（闭源 `.so`） | 未知 | 未知 | 预编译 `.so`（armeabi-v7a） | `jni/include/almashot/almashot_raw.h:11-27`（CONFIDENTIAL）、`jni/nightprocessing/almashot-night.cpp:273` |
| 7 | `libcamera-org/libcamera`（镜像，HEAD `c19d051`） | **无** | — | — | — | — | 唯一 HDR 相关 `src/ipa/rpi/controller/rpi/hdr.cpp:29-40` 是 **sensor 内建 HDR 模式控制**（cadence/channel_map），不是融合 |
| 8 | `raspberrypi/libcamera-apps`（HEAD `7b69b7c`） | **有但不在 RAW 域**（8-bit 成品图累加） | 空域（帧累加） | json `num_frames` | 全分辨率 **8-bit YUV** | CPU post-processing stage | `post_processing_stages/hdr_stage.cpp:12-20`（注释自认 raw 域不可行）、`:131-134`（自认「没马力做对齐」）、`:503,527` |
| 9 | PhotonCamera（Kotlin/GLES，已知） | **有**（MGC SpatialMerge 移植） | 空域（RAW Bayer 归一化卷积 + rejection 权重，3×3；guide/rejection 在 RAW/2、merge weight 在 RAW/4） | 4 | 全分辨率；不切 tile，每帧 4 个短 pass + 帧间 yield | **GPU** | 既有结论，见 `docs/reports/pc-still-perf-RESEARCH.md:16,103`；本报告复核仓库 HEAD `e657e62e` |
| 10 | GCam ports（MGC / AGC / LMC / BSG / Sabre） | 产品上**有**，**无源码** | 未知 | 未知 | 未知 | 未知（`libgcastartup.so`） | 无——见 1.3 |
| 11 | Halide 官方示例（`halide/Halide` `apps/`） | **无** | — | — | — | — | `apps/` 目录无 hdrplus；只有 `fft`/`nl_means`/`bilateral_grid`/`wavelet`/`local_laplacian` 等**单帧**算子示例 |
| 12 | 「mobile-raw 项目」 | **未找到对应项目** | — | — | — | — | 见 1.3 |

### 1.2 逐个说明（只写源码里读到的东西）

**1) `timothybrooks/hdr-plus`** —— 本报告最重要的正面证据。
融合是纯空间域：先 `box_down2` 得到 1/2 层（`merge.cpp:36`），在 16×16 降采样 tile 上算「参考帧 vs 备帧」的平均 L1 距离（`merge.cpp:63`），再映射成 tile 权重（`merge.cpp:65-71`，公式见 ③）；然后**在原始 mosaic 分辨率上**做加权平均（`merge.cpp:90`），最后用 32×32 半重叠 + 升余弦窗做空间混合（`merge.cpp:121-141`）。输出是 `u16` Bayer（`merge.cpp:139`）——**融合完仍然是 mosaic**，去马赛克在后面的 `finish` 阶段才做。
对齐是自己写的三层金字塔 argmin（`align.cpp:77-131`），tile 32×32、重叠 16（`align.h:3-5`），最终位移乘 2 落到 mosaic 域（`align.cpp:126`）⇒ **像素级量化，无亚像素**。
帧数不写死：`Burst::ToBuffer` 用 `Raws.size()` 建 3D buffer（`Burst.cpp:9-21`），命令行传几个 raw 就融合几个（`bin/stack_frames.cpp` usage）。
**平台**：`CMakeLists.txt` 只链接 `Halide::Generator` + PNG/LibRaw/TIFF，无 `ANDROID`、无 OpenCL/CUDA target ⇒ CPU（Halide AOT 生成）。

**2) `jhfmat/ISP-pipeline-hdrplus`** —— 与 1) **同构**（同一套算法被改写成工程 C++）：`EstimatedWeight` 在 16×16 块上累计 SAD（`BlockMatchFusion.cpp:151`、`:430-475`），`AvgSad >> 8` 后套 `NormDist = max(1, (AvgSad − MinDist)/AmountFactor)`，`NormDist > (MaxDist−MinDist)` 置 0，否则 `1/NormDist`（`:481-490`）；常量 `AmountFactor=4, MinDist=10, MaxDist=300`（`.h:27-32`）。`MergeTemporal` 用 `SCALEVALUE=2^14` 定点累加（`:527-533`），`MergeWeight` 用 32 点升余弦窗混合（`:592`、`:625`）。融合同样输出 Bayer（`unsigned int` 累加器、`MultiUshortImage` 输出）。**它的价值是证明这套空域融合可以被定点化 + NEON 化**（SAD 有 `USE_NEON` 分支，`:430-450`）。

**3) `amonod/hdrplus-python`** —— 唯一把论文 §5 频域收缩完整实现的仓库，正好是**我们 DFT 路径的参考实现**：`A = |D|²/(|D|²+noise)`、`out = alt + A·(ref−alt)`（`merging.py:193-200`），噪声缩放 `patchSize²/4²·2·temporalFactor`（`:213-216`），tile 16 / 半重叠 / 升余弦窗（`:122-151`、`params.py:45`），逐通道 2D DFT → 合并 → IDFT（`:335-393`）。CPU、极慢（Python），**没有可迁移的加速手段**，但可用来核对我们的收缩系数与窗函数。

**4) `KillerInk/FreeDcam`** —— 有 `DngStacker`，但就是 `(本帧 + 累加值)/2` 的递归平均（`DngStacker.cpp:177-211`），**无对齐、无权重、无噪声模型、无 tile、单线程**。它证明「多帧 RAW 叠加」门槛很低，也证明**没有对齐与 rejection 的叠加做不出手持降噪**（这正好是我们 T3 不能省 rejection 的反例）。

**5) `jcelaya/hdrmerge`** —— RAW 域多帧合并，但目的是**曝光包围**（`README.md:3`）：README 的说法是「output raw is built from the less noisy pixels of the input」，实现上是逐像素**选帧**（`generateMask` 取第一个「包含该点且邻域未饱和」的帧，`ImageStack.cpp:184-200`），不是加权平均；MTB（Median Threshold Bitmap）金字塔 + 3×3 邻域搜索做平移对齐（`Image.cpp:179-223`，逐层 `dx<<=1`），OpenMP 并行（`ImageStack.cpp:126-155`）。**对 T3 的可用点只有 MTB 对齐的性价比**（位运算 XOR+popcount，参考实现 30 行）。

**6) `almalence/OpenCamera`** —— 必须点名：它是「Almalence Imaging SDK for Open Camera」，含 `almashot_raw.h` / `supersensor.h` / `fstacking.h` 等 API 头，但头文件开头即 `CONFIDENTIAL: CONTAINS CONFIDENTIAL PROPRIETARY INFORMATION`（`almashot_raw.h:11-27`），实现只在 `jni/prebuilt/armeabi-v7a/` 的预编译库里。夜拍 JNI 只是把 YUV 帧喂给 `Super_Process`（`almashot-night.cpp:273`）。⇒ **零算法证据**，不能作为「开源多帧 RAW 融合」引用。

**7) `libcamera`** —— **没有多帧 RAW 融合**。全仓唯一的 HDR 代码是 RPi IPA 的 `rpi.hdr`，读 `cadence` / `channel_map`，并依赖 `stitch_status.h` / `tonemap_status.h` ⇒ 它是**传感器内建 HDR 模式（多曝光通道拼接）的控制算法**，不合并 burst。这条要写清楚，因为很多资料会把「libcamera 支持 HDR」误读成「有 HDR+ 式融合」。

**8) `libcamera-apps`** —— 有 `hdr_stage`，但是**8-bit 成品域多帧累加**：文件头注释直接写「this runs as a post-processing stage on fully-processed 8-bit images. Normally I'd rather do HDR in the raw domain ... but clearly that's not possible here」（`hdr_stage.cpp:12-20`），累加器是 `int16_t` 的 Y/U/V（`:136-150`），末尾 `Scale(16.0/num_frames)` 再 tonemap（`:527`）。对齐？注释写「we don't have the horsepower to do any fancy alignment」（`:131-134`）⇒ **完全不对齐**。这是「画质换速度」的极端样本，不是 T3 的参考。

**9) PhotonCamera** —— 用户已确认的事实，本报告只复核、不重复：3×3 RAW 邻域归一化卷积、rejection 权重、不切 tile、每帧 4 短 pass + 帧间 yield（`docs/reports/pc-still-perf-RESEARCH.md:16,103`）。**它是唯一 GPU 空域多帧 RAW 融合的可读实现**，也是 T3 的算法上限参照。

### 1.3 明确「没有证据」的项目（不许编造）

| 项目 | 结论 | 依据 |
|---|---|---|
| `merkouris/hdrplus` | **仓库不存在/不可访问** | GitHub API `repos/merkouris/hdrplus` → **404**；`hdrplus` 全站搜索 15 个结果中无此 owner |
| `johanneskopf/hdrplus` | **仓库不存在/不可访问** | 同上，**404** |
| GCam ports（MGC / AGC / LMC / BSG / Sabre） | **无公开源码**，无可引用的 `文件:行` | 分发形态是 APK + 配置（本次尝试的 `BigKaka/AGC` 路径 API 返回 404，因此连配置仓也未能取证）；MGC 的融合实现在 `libgcastartup.so` 里，PC 只是从 `.so` 反推 tuning 参数（`pc-still-perf-RESEARCH.md:166`）。任何「MGC 源码第 N 行」的说法都是伪造 |
| 「mobile-raw 项目」 | **未找到可信对应项目** | GitHub 搜索 `mobile-raw` 的前 5 名全是无关项目（VPN 客户端、PMRID 论文仓库等）；`mobile raw fusion` 亦无匹配的相机项目 |
| ProCamera / ProCamera2 | **闭源 App，无源码** | 不在本次可取证范围；不引用 |
| Halide 官方 HDR+ 示例 | **不存在** | `api.github.com/repos/halide/Halide/contents/apps` 的目录清单里无 `hdrplus`；「Halide 的 HDR+」实际指 1) 的外部仓库 |
| `jhfmat/ISP-pipeline-hdrplus` 的 GPU 部分 | **无** | 工程是 VS/C++，无 CUDA/OpenCL，只有 NEON 分支 |

---

## ② 加速手段分类表（工程优化 vs 画质换速度）

### 2.1 不降画质（纯工程优化，可直接迁移）

| # | 手段 | 开源出处（文件:行） | 说明 | 我方现状 / 迁移性 |
|---|---|---|---|---|
| E1 | **融合后仍在 Bayer 域，全链路只去马赛克一次** | `hdr-plus/src/merge.cpp:139`（输出 `u16` mosaic）；`ISP-pipeline/.../MergeWeight:580-625`（输出 Bayer） | 避免「先 demosaic 再融合」带来的 3× 数据量与两遍色度处理 | 我方 `tex_fused_` 已是线性 mosaic（`kFsMfnrFuse` 输出单通道 R16F），**已具备**；DFT 路径的 4×`RGBA16F` 是全分辨率相位层，属反例 |
| E2 | **tile 半重叠 + 升余弦窗空间混合**（不是空间降噪核） | `hdr-plus/src/merge.cpp:121-141`（`weight(v)=0.5−0.5cos(2π(v+0.5)/32)`）；`ISP-pipeline:592,625` | 消除 tile 边界接缝；窗和恒 1 只在 50% 重叠成立 | 我方 DFT 已用同一族窗（hop 8 / tile 16）；空间域路径若改用 3×3 邻域则**不需要**窗（天然连续，PC 同款） |
| E3 | **权重统计降域**（1/2 层算 L1/统计，全分辨率只做乘加） | `hdr-plus/src/merge.cpp:36`（`box_down2`）+ `:63` | 权重像素数降到 1/4 | 我方已有 32px cell 的 mismatch/权重图（`tex_w_`，`FrameRenderer.cpp:15963-15972`），等价手段已在用 |
| E4 | **粗到细金字塔对齐**（3 层，粗层收敛大位移） | `hdr-plus/src/align.cpp:89-116`（layer_3→2→1→0） | 大位移不必全分辨率全搜索 | 我方 `Align.cpp` 走 CPU 金字塔 + `glReadPixels`；**对齐本身不是最大头**，但读回是（`pc-still-perf-RESEARCH.md:134`） |
| E5 | **位运算对齐（MTB + XOR/popcount）** | `hdrmerge/src/Image.cpp:179-223` | 曝光对齐的极廉价做法（每层 9 候选、逐层 ×2） | 精度只到整像素，**不适合亚像素手持**；可作「粗对齐预筛」候选，需实测 |
| E6 | **并行 + 向量化调度**（Halide `parallel`/`vectorize`，OpenMP） | `hdr-plus/src/merge.cpp:98-102,146-148`、`align.cpp:63-65`；`hdrmerge/src/ImageStack.cpp:129-138`（`#pragma omp parallel for`） | CPU 侧把融合摊到多核 + SIMD | 我方在同一条流水线上是 GPU 并行，**已超越**；这条对我们是「确认上限」而非「可抄」 |
| E7 | **定点化中间量**（2^14 缩放、`unsigned short` 累加） | `ISP-pipeline/.../BlockMatchFusion.cpp:527-533`、`:625`；`FreeDcam:177-211`（16bit） | 省带宽/省寄存器；代价是量化误差 | 我方全 float16/32，2^14 定点在 GPU 上收益存疑（Mali 上 half 已足够），**不建议** |
| E8 | **程序/shader 复用**（init 时一次性建 program） | PC `GlesMgcRawSpatialStacker.kt`（`initPrograms` 一次，见既有报告 `:604-609` 引述） | 避免每次拍照 `compileShader` 的冷启动尖峰 | 我方 0.13.11 已缓存 DFT program（`FrameRenderer.cpp:16486-16500` 注释），**已修**；空间域新 pass 也要照此办理 |
| E9 | **帧间 yield / 小 job 流水线**（每帧 4 短 pass + `yieldToUiRenderer`） | PC `GlesMgcRawSpatialStacker.kt:876,1607,1800,...`（大量 `yieldToUiRenderer` 调用点） | 缩短单次 GPU job，让预览插队 | 我方 0.13.10 已把 DFT 按 tile 行拆块、在飞 2（`FrameRenderer.cpp:16599` `kFftRowBlocks`）⇒ **手段已落地** |
| E10 | **异步/零读回**（GPU 内累加器，最后只导出一次） | PC（4 张 `R32F` 累加器，见 `pc-still-perf-RESEARCH.md:83`） | 省掉每帧 readback 同步 | 我方对齐仍每帧 3–4 次 `glReadPixels`（`readL()` 的 `:17714-17729`，`readL` 被调用 4 次），**未做**；与 T3 正交，可单独做 |
| E11 | **融合与降噪合并成一个 pass**（融合输出即降噪输出） | `hdr-plus` 的 `merge` 本身就是「时域去噪」（`merge.cpp:12-16` 注释）；PC 融合时顺带重建全分辨率绿通道 | 少一趟全分辨率读+写 | 我方 `kFsMfnrFuse` → `tex_fused_` → `kFsRawDenoiseFused(Ts)` 是两趟；**T3 的核心收益点**（见 ④） |

### 2.2 用画质换速度（必须 A/B，不许默认）

| # | 手段 | 开源出处（文件:行） | 画质代价 | 我方是否已有/是否可试 |
|---|---|---|---|---|
| Q1 | **权重统计降到 1/2 或 1/4 域、甚至 32px cell** | `hdr-plus:36`（1/2）；PC（rejection 在 RAW/2、merge weight 在 RAW/4，已知） | 局部运动/遮挡处的权重空间分辨率不足 ⇒ 鬼影或「降噪断层」 | 我方 `tex_w_` 已是 32px cell RGBA8（`15963-15972`）；再降就是真降级，需 A/B |
| Q2 | **对齐量化到偶数 mosaic 像素（放弃亚像素）** | `hdr-plus/align.cpp:126`（`2*alignment`） | 8–21px 位移下 ±0.5px 残差会磨细节（我方 `kFsRawDenoiseFusedTs` 注释 `:3605-3610` 有实测：σ=0.002 时量化版残余噪声比不做邻域平均还差 31%） | 我方已有亚像素 `linSub/linSubZ`，**不能回退**；这是「开源实现比我们弱」的地方 |
| Q3 | **硬阈值 rejection**（`dist>300` 直接丢帧） | `hdr-plus/merge.cpp:71`；`ISP-pipeline:482` | 阈值过高 ⇒ 该帧整块被丢（等效帧数下降）；过低 ⇒ 鬼影 | 我方是连续权重（`satW/darkW`）+ mismatch 图，**更强**；T3 若要引入 rejection，应做功利的连续函数而非硬截断 |
| Q4 | **降低 overlap（50%→25%）** | 频域专有，我方 DFT 选项（`pc-still-perf-RESEARCH.md:174`） | 窗和不再恒 1（亮度纹波）；tile 边界相位残差 | 仅对 DFT 有意义；改 T3 后此选项消失 |
| Q5 | **减少帧数（4→3/2）** | `hdr-plus`（帧数=输入）；`libcamera-apps`（`num_frames` 配置） | 直接掉 SNR（√N）与运动鲁棒性 | 由用户拍板，不许默认 |
| Q6 | **在 8-bit 成品域融合** | `libcamera-apps/hdr_stage.cpp:12-20,136-150` | 高光/暗部信息在 8bit 已丢；且无对齐 | **不可取**（反例） |
| Q7 | **无对齐直接平均** | `FreeDcam/DngStacker.cpp:177-211`；`libcamera-apps:131-134` | 手持必糊/重影 | **不可取**（反例） |

### 2.3 一条容易搞混的区分（值得写进设计文档）

- **「tile 化」≠「多 pass 流水线」**。`hdr-plus`/`ISP-pipeline` 的 tile 是**为了半重叠窗与权重统计**（算法需要），`libcamera-apps` 的每帧一次 `Accumulate` 是**流式累加**（省内存/省延迟）。PC 之所以「不切 tile 也快」，是每帧 4 个短 pass + 帧间 yield（`pc-still-perf-RESEARCH.md:103`），不是靠切 tile。
- 我方 DFT 已经既有 tile 又有拆块（`kFftRowBlocks`），这两项**不再是瓶颈**；T3 想赢，必须赢在**少写 4 张全分辨率纹理**与**少一趟全分辨率读**（E1+E11），而不是继续切块。

### 2.4 逐项核对用户清单里的 11 类手段（含「无证据」项）

| 手段 | 开源中有无 | 证据 / 说明 |
|---|---|---|
| 半分辨率 / 降域 | **有，但只降「权重统计」，不降「融合输出」** | `hdr-plus/src/merge.cpp:36`（`box_down2` 只用于 L1 统计）；PC（rejection@RAW/2、weight@RAW/4，已知）。**没有任何项目把融合本身放在半分辨率**（那会直接掉细节） |
| 分离卷积 | **无证据** | 5 个可读实现里没有任何一个在多帧融合中使用可分离卷积；`hdr-plus`/`ISP-pipeline` 的块统计与 SAD 都是 2D 直接累加（`merge.cpp:26` 的 `RDom(0,16,0,16)`、`BlockMatchFusion.cpp:430-475` 的双重循环） |
| tile 化 | **有** | `hdr-plus` 对齐 tile 32/重叠 16（`align.h:3-5`）、融合 tile 32 半重叠（`merge.cpp:108-141`）；`ISP-pipeline` 块 16（`BlockMatchFusion.cpp:151`）；`hdrplus-python` tile 16（`params.py:45`）；PC **不切**（已知） |
| 多 pass 流水线 | **有** | `libcamera-apps` 每帧一次 `Accumulate`（`hdr_stage.cpp:503`）；PC 每帧 4 个短 pass（已知）。注意两者都是「帧级」流水，不是把单帧计算拆 pass |
| 异步读回 | **无证据** | 所有可读实现都是 CPU 进程内直接读写内存，不存在 GPU→CPU 异步读回；PC 是「不读回」（零 readback），不是「异步读回」。**这一项没有可抄的开源实现** |
| GPU 对齐（LK / 光流） | **无证据（没有 LK/光流）** | `hdr-plus` 是金字塔 argmin（`align.cpp:77-131`）、`ISP-pipeline` 是 16×16 块 SAD（`BlockMatchFusion.cpp:151,430-475`）、`hdrmerge` 是 MTB XOR+popcount（`Image.cpp:179-223`）、`hdrplus-python` 是块匹配。**没有一个用 Lucas-Kanade 或稠密光流**；PC 的 GPU 对齐是粗层金字塔收敛（已知），也不是 LK |
| 零拷贝 | **仅 PC** | PC 的 RAW 直传 + GPU 内累加（已知）；CPU 实现无此概念 ⇒ 其余项目**无证据** |
| shader 复用 | **有（仅 PC）** | PC `initPrograms` 一次（已知，`pc-still-perf-RESEARCH.md:187` 引述）；CPU 实现无 shader |
| 融合与去马赛克 / 降噪合并成一个 pass | **有** | `hdr-plus` 的 `merge` 本身就是时域降噪、输出仍是 mosaic、去马赛克后置（`merge.cpp:12-16,139`）；PC 融合时顺带重建全分辨率绿通道（已知） |
| 跳过某些 pass | **有（可配置）** | `hdrplus-python`：`if options['spatialFactor'] > 0` 才做频域空域降噪（`merging.py:380-384`）；`libcamera-apps`：注释建议 HDR 时关色度降噪以加快累加（`hdr_stage.cpp:16`） |
| 帧间权重近似 | **有** | `hdr-plus` 用 `1/max(1, dist/8−10/8)` 近似（`merge.cpp:65-71`，非线性+硬截断）；`ISP-pipeline` 再定点化（`BlockMatchFusion.cpp:527-533`）；PC 权重降域（已知） |

> 结论：11 项里 **7 项有开源证据**、**4 项没有**（分离卷积、异步读回、GPU 光流对齐、零拷贝——除 PC）。这 4 项都不能写「业界通行做法」。

---

## ③ 空间域融合的成熟实现要点（公式/拒绝/对齐/域/噪声模型）

### 3.1 `hdr-plus` 的 `merge_temporal`（最完整的开源空域多帧 RAW 融合）

```cpp
// src/merge.cpp:26,63-71  （Halide 表达式，逐字转写）
RDom r0(0, 16, 0, 16);                  // 16×16 降采样 tile 归约
dist      = Σ|ref − alt| / 256;          // 该 tile 的平均 L1 距离
norm_dist = max(1, dist/8 − 10/8);       // factor = 8, min_dist = 10
weight    = (norm_dist > (300−10)) ? 0 : 1/norm_dist;   // max_dist = 300
total     = Σ_{alt frames} weight + 1;   // 参考帧权重恒 1
out       = Σ weight·alt/total + ref/total;             // :90-92
```

- **域**：Bayer mosaic（融合前后都是 mosaic，`:139` 输出 `u16`）。
- **空间维**：**没有空间邻域核**。这里的「空间」只是 32×32 半重叠 tile 的升余弦窗混合（`:121-141`），不是 3×3/5×5 邻域平均。**与我们 `passRawDenoiseFused` 的 5×5 同相位邻域是两种东西**。
- **rejection**：tile 级硬阈值（`dist>300` 权重 0），无逐像素几何拒绝、无遮挡检测。
- **噪声模型**：**没有**。权重只看 L1 距离，不区分信号强度（暗部噪声大，L1 天然偏大 ⇒ 暗部备帧被系统性低估权重）。
- **对齐**：三层金字塔 argmin，最终 `×2` 落到 mosaic 偶数像素（`align.cpp:126`），**无亚像素**。
- **成本形态**：每输出像素 ~4 次乘加 + 4 次混合；内存只有 1 张 mosaic（对比我们 DFT 的 4 张 `RGBA16F`）。

### 3.2 `ISP-pipeline-hdrplus`：同构 + 工程化

- 权重：`NormDist = max(1, (AvgSad − 10)/4)`，`> 290` 置 0，否则 `1/NormDist`（`BlockMatchFusion.cpp:481-490`，常量 `.h:27-32`；与 3.1 只差 `AmountFactor=4` vs `8`）。
- 定点：`SCALEVALUE = 1<<14`（`:3-4`），权重与累加全定点（`:527-533`），窗混合定点（`:625`）。
- 加速：SAD 有 NEON 版（`:430-450`），块 16（`:151`）。
- 结论：**这套算法在 CPU 上已经被工程化到可跑几十 MP**；说明「空域多帧 RAW 融合」的算力门槛不高，我们 GPU 化只会有余量。

### 3.3 `hdrmerge`：不是加权，是「逐像素选帧」

- README 说输出由输入里「less noisy pixels」构成（`README.md:3`），实现是 `generateMask` 逐像素取**第一个覆盖该点且邻域未饱和**的帧（`ImageStack.cpp:184-200`，判据 `contains` + `isSaturatedAround`）——**不是加权平均，也没有噪声度量**；全局 MTB 平移对齐（`Image.cpp:179-223`）。
- 对 T3 的价值：**曝光包围场景下「选像素」优于「加权平均」**（避免短帧暗部噪声污染）。若 T3 要顺带支持 bracket（我方已有 `uFrameScale/uSatK`），可借这个思路做「按 SNR 选择而非平均」。

### 3.4 PC（已知，复述要点以便对照）

- 3×3 RAW 邻域归一化卷积 + 逐帧 rejection 权重；guide/rejection 在 RAW/2、merge weight 在 RAW/4；不切 tile，每帧 4 短 pass + 帧间 yield（`pc-still-perf-RESEARCH.md:16,103`）。
- 与我方差异：PC **融合时顺带重建全分辨率绿通道**（融合与部分去马赛克合并），我们融合后再走 RCD/AMaZE —— 这是 PC 少一趟全分辨率 pass 的原因之一。

### 3.5 共性总结（5 个可读实现 + PC）

| 维度 | 开源空域实现（hdr-plus / ISP-pipeline / FreeDcam / hdrmerge） | PC | 我方 `passRawDenoiseFused(Ts)` |
|---|---|---|---|
| 是否 Bayer 域 | 是 | 是 | 是（`tex_mfnr_` R16UI + `tex_fused_` R16F） |
| 时域加权 | 有（1/dist） | 有（rejection 权重） | 有（`satW·darkW·mismatch`） |
| 空间核 | **无**（只有 tile 窗） | **3×3** | **5×5 同相位（24 邻域/帧）** |
| 噪声模型 | **无**（hdr-plus/ISP-pipeline） | 有 | **有**（`σ²=A·x+B`，`uNoiseA/B`） |
| 逐像素 rejection | 无（tile 硬阈值） | 有 | 部分（32px cell mismatch 图） |
| 亚像素对齐 | 无 | 有 | **有**（`linSub`/`linSubZ`） |
| 每像素样本数 | 4（帧数） | 4×9=36 | 4×24=96（注释写 36 是旧值） |
| GPU | 无 | 有 | 有 |

**一句话**：开源空域实现赢在「便宜」（权重降域、定点、无额外全分辨率中间纹理），输在「模型」（无噪声模型、无逐像素 rejection、无亚像素）。**我们恰好相反**。

---

## ④ 对 T3 的适用性判断

### 4.1 我方现状（代码事实，供预算用）

| 项 | 事实 | 证据 |
|---|---|---|
| 默认路径 | **DFT 默认开启**（`prefs mfnr_dft` 默认 `true`） | `app/src/main/java/com/filmkit/app/camera/CameraController.kt:4056` |
| DFT 形态 | 4 相位组 × 16×16 tile 半重叠，逐频收缩 `uC=256`，4 张 `RGBA16F` 输出，收口 pass 合成 | `FrameRenderer.cpp:3835`（`kFsMfnrFft`）、`:16568`（`uC`）、`:16459`（`passMfnrFft`）、`:16599`（拆块） |
| 空间域路径 | `kFsMfnrFuse`：4 帧逐像素加权（**无空间核**）→ `tex_fused_`；再 `kFsRawDenoiseFused`（25 样本）或 `kFsRawDenoiseFusedTs`（4 帧 × 24 邻域 = **96 样本**） | `:3183`、`:3488`、`:3579`、`:3685-3688`、`:16812`（Ts 分支入口） |
| Ts 的权重 | `w = exp(−0.5·(d/σ)² − 0.5·(dx²+dy²)/6.48)`，`σ=√(A·x+B)·√5`（`uSrScale`） | `:3686-3703`、shader 注释 `:3578` |
| 现有实测 | `fuseMs` 1201–1561 ms（DFT 合并），占拍照链最大单项 | `docs/reports/pc-still-perf-RESEARCH.md:154` |

### 4.2 与开源成熟实现的差距（按「T3 会不会因此掉画质」排序）

| 差距 | 开源做法 | 我方现状 | 对 T3 的影响 |
|---|---|---|---|
| **D1 逐像素 rejection 缺失** | PC 有完整 rejection 几何（RAW/2 guide + RAW/4 weight）；开源（hdr-plus 系）反而只有 tile 硬阈值 | 只有 32px cell 的 `tex_w_` mismatch 图（`15963-15972`） | **最大画质风险**：手持 1/4s 下局部运动（树叶、行人）会在 32px 内被平均 ⇒ 鬼影。T3 必须先把 rejection 粒度做细（哪怕复用 `tex_mis_` 的 32px 网格并做双线性），否则 A/B 会输给 DFT |
| **D2 无频域分离能力** | —（开源空域实现都没有；PC 也没有） | DFT 逐频收缩：低频强收缩、高频弱收缩，天然「保细节 + 压低频噪」 | 空域固定核宽是**单一噪声-细节折衷**。要接近 DFT 的画质，需要「双核宽」（小核保边 + 大核压噪）或「引导滤波」，成本会反超。**这是 T3 的算法级上限，必须 A/B 而不能假设等价** |
| **D3 样本数 / 采样成本** | 开源 4 样本/像素；PC 36 | 96（Ts） | 若 T3 直接沿用 Ts 的 96 采样，成本 ≈ 现在 P0（25 样本）的 3.8 倍 texelFetch，**提速可能被吃掉**。合理 T3 应取 **36（4×3×3）**，与 PC 同口径 |
| **D4 中间纹理** | 开源只有 1 张 mosaic；PC 只有 4×`R32F` 累加器 | DFT 4×`RGBA16F` 全分辨率（≈400MB 写 + 500MB 读）；空间域路径已有 `tex_fused_` | **T3 的核心收益**：省掉 DFT 的 4 张相位层与收口 pass。这条是确定的、可量化的 |
| **D5 融合与降噪分两趟** | `hdr-plus` 的 merge 即去噪（一趟）；PC 融合时顺带重建绿通道 | `kFsMfnrFuse` → `tex_fused_` → `kFsRawDenoiseFused(Ts)` 两趟 | T3 应把两者合成**一个 pass**（在融合循环里直接取 3×3 邻域样本），省一趟全分辨率读+写。这是 E11，纯工程收益 |
| **D6 对齐读回** | 开源全 CPU（无 readback 概念）；PC 零读回 | 每帧 3–4 次 `glReadPixels`（`readL()` 的 `:17714-17729`，`readL` 被调用 4 次） | 与 T3 正交，但会**掩盖** T3 的收益（若 T3 省 800ms、对齐仍阻塞 200ms，总时长改善会打折）。建议 T3 同期把读回改 PBO/fence |

### 4.3 「开源里有没有可直接照搬的空域多帧融合」——结论

- **算法骨架：有，而且不止一个**（hdr-plus / ISP-pipeline / PC）。它们的共同骨架是：**Bayer 域 + tile 统计权重 + 时域加权 + 半重叠窗（或 3×3 邻域）+ 一次去马赛克**。
- **可照搬的只有骨架，不能照搬权重**：hdr-plus 系没有噪声模型（暗部权重会系统性偏低），直接用会让暗部降噪不足；我们已有 `σ²(x)=A·x+B`，应保留。
- **没有可直接照搬的「4 帧 Bayer GPU 空域加权融合」完整实现**（PC 是唯一，且已知）。所以 T3 是「用 PC 的算法口径 + 我们已有的噪声模型/亚像素对齐/饱和处理」重写一个 pass，而不是移植某个仓库。

### 4.4 建议的验证路径（只给判据，不改代码）

1. **先做零风险的 E11/E1**：把 `kFsMfnrFuse` 与 `kFsRawDenoiseFused(Ts)` 合成一个 pass，输出直接 `tex_mos_`。观测：`fuseMs`、`p0Ms`、`totalMs`、`neff`。质量对照用「同一 RAW 的 DFT 成片 vs T3 成片」的 PSNR/SSIM + 100% 裁切肉眼（噪声、细节、鬼影）。
2. **D1 单独验证**：固定 T3，只把 rejection 图从 32px cell 换成逐像素（或 8px），看鬼影指标（局部运动区域的时间方差）与 `neff`。若鬼影不改善，说明 32px 够用，可省。
3. **D2 必须 A/B，不许默认**：同一场景下比较 DFT 与 T3 的「高频细节保留度」（如 100% 裁切的边缘锐度/纹理能量）与「平坦区噪声 σ」。若 T3 在噪声持平下细节低 >5%，按项目铁律**不换**（质量优先）。
4. **D6 并行做**：读回改 PBO/fence，避免 T3 的收益被对齐阻塞吃掉。
5. 任何「降 overlap / 减帧 / 半分辨率」选项（Q1–Q5）都必须单变量 A/B 并由用户拍板。

### 4.5 需要纠正/澄清的两处内部口径

1. **36 vs 96 样本**（见摘要 6）。`pc-still-perf-RESEARCH.md:209,211` 与 `FrameRenderer.cpp:16812` 注释写「4 帧 × 3×3 = 36」，但 Ts shader 实际循环是 `dy=-4..4 step 2`（5 值）⇒ 24 邻域/帧 ⇒ **96**。T3 的预算与「预期收益」若按 36 推导，会高估收益约 2.7×。
2. **「hdr-plus 是 HDR+ 的原版实现」不成立**：它是**空间域简化版**（无 FFT、无 Wiener、无噪声模型），论文 §5 的逐频收缩在 `amonod/hdrplus-python` 里才有。引用时不要混用，否则会把「空域已被官方验证」误当成「空域与频域等价」。

---

## ⑤ 证据索引（仓库 URL + 文件:行）

> 所有仓库 HEAD 抓取于 2026-11-12。`/tmp/ossres/` 为本次调研的临时克隆目录（不在项目内）。

### 5.1 有完整/部分多帧 RAW 融合

| 项目 | URL | HEAD | 证据（文件:行） |
|---|---|---|---|
| hdr-plus | https://github.com/timothybrooks/hdr-plus | `ef4dd2c`（2026-01-12） | `src/merge.cpp:12-16`（merge_temporal 注释）、`:18-103`（权重+加权平均+调度）、`:26`（16×16 归约）、`:36`（box_down2）、`:57-59`（factor/min/max）、`:63`（L1 距离）、`:65-71`（权重公式）、`:75-78`（total_weight）、`:90`（加权平均）、`:98-102`（parallel/vectorize）、`:121-141`（升余弦窗混合）、`:139`（输出 u16 mosaic）、`:146-148`（调度）；`src/align.h:3-10`（T_SIZE 32 / 重叠 16 / MIN-MAX_OFFSET）；`src/align.cpp:63-65`（调度）、`:77-131`（三层金字塔）、`:126`（×2 量化）；`src/Burst.cpp:9-21`（帧数=输入数）；`CMakeLists.txt:37-73`（Halide Generator，无 Android/GPU target） |
| ISP-pipeline-hdrplus | https://github.com/jhfmat/ISP-pipeline-hdrplus | `3f06306`（2024-09-24） | `Layer/HDRPlus_BlockMatchFusion.cpp:151`（Blocksize 16）、`:390`（EstimatedWeight）、`:430-450`（NEON SAD）、`:481-490`（NormDist 权重）、`:498`（MergeTemporal）、`:527-533`（2^14 定点）、`:580`（MergeWeight）、`:592`（32 点升余弦）、`:625`（窗混合）、`:804-807`（调用链）；`Layer/HDRPlus_BlockMatchFusion.h:27-32`（AmountFactor=4/MinDist=10/MaxDist=300）；`Mat/Basicdef.h:126`（nFrameNum 默认 1） |
| hdrplus-python | https://github.com/amonod/hdrplus-python | `98ebf17`（2022-06-27） | `package/algorithm/merging.py:44-45`（mergeBurst 输出 Bayer）、`:122-140`（升余弦窗）、`:151`（overlap 拼回）、`:193-200`（Wiener 收缩公式）、`:204-220`（noiseScaling）、`:223-236`（pairwise DFT）、`:238-268`（频域空域降噪）、`:335-393`（逐通道 DFT→合并→IDFT）；`package/algorithm/params.py:45`（tileSizes 16/16/16/8）；`package/algorithm/hdrplus.py:54-84`（burst→align→merge→finish） |
| FreeDcam | https://github.com/KillerInk/FreeDcam | `4ce7b61`（2026-06-18） | `app/src/main/jni/freedcam/DngStacker.cpp:1-40`（JNI 入口）、`:177-211`（`(new+acc)/2` 递归平均）、`:214-279`（写 DNG/CFA）；无对齐/无权重/无噪声模型 |
| hdrmerge | https://github.com/jcelaya/hdrmerge | `2dbfd7d`（2026-07-24） | `README.md:3`（多帧 raw→DNG、README 称 less noisy pixels）、`:21`（自动平移矫正）、`:95`（OpenMP）；`src/ImageStack.cpp:126-155`（align/release）、`:184-200`（generateMask 饱和选帧）；`src/Image.cpp:179-223`（MTB 金字塔 + 3×3 搜索，`dx<<=1`） |
| almalence/OpenCamera | https://github.com/almalence/OpenCamera | `24b600e`（2018-10-02） | `jni/include/almashot/almashot_raw.h:11-27`（CONFIDENTIAL 声明）、`:57`（`Raw_AutoWB` 等 API 头）；`jni/nightprocessing/almashot-night.cpp:273`（`Super_Process`）；`jni/prebuilt/armeabi-v7a/`（预编译库，无源码） |
| libcamera-apps | https://github.com/raspberrypi/libcamera-apps | `7b69b7c`（2026-11-12） | `post_processing_stages/hdr_stage.cpp:12-20`（自认 8-bit 域、raw 域不可行）、`:81`（num_frames）、`:136-150`（int16 YUV 累加）、`:131-134`（自认无对齐）、`:503`（Accumulate）、`:527`（Scale 16/N） |

### 5.2 确认「无多帧 RAW 融合」

| 项目 | URL | 证据 |
|---|---|---|
| libcamera | https://github.com/libcamera-org/libcamera（镜像；官方在 gitlab.freedesktop.org/camera/libcamera）HEAD `c19d051` | `src/ipa/rpi/controller/rpi/hdr.cpp:1-40`（HDR 模式控制：`cadence`/`channel_map`，依赖 `stitch_status.h`/`tonemap_status.h`）；全仓无 burst 融合 |
| Halide | https://github.com/halide/Halide | `apps/` 目录清单（API 抓取）无 `hdrplus`；`fft`/`nl_means`/`bilateral_grid`/`wavelet`/`local_laplacian` 均为单帧算子 |

### 5.3 我方对照（只读引用）

| 事实 | 文件:行 |
|---|---|
| `kFsMfnrFuse`（4 帧逐像素加权，无空间核） | `core/engine/src/render/FrameRenderer.cpp:3183-3310` |
| `kFsRawDenoiseFused`（融合后 5×5 步长 2 = 25 样本） | 同上 `:3488-3565` |
| `kFsRawDenoiseFusedTs`（4 帧 × 24 邻域 = 96 样本，`uSrScale=√5`） | 同上 `:3570-3705`，循环 `:3685-3688`，样本数注释 `:3704-3705` |
| `kFsMfnrFft` / `passMfnrFft`（DFT，`uC=256`，4×`RGBA16F`） | 同上 `:3835`、`:16459`、`:16568`、`:16599` |
| Ts 分支入口（`allow_ts`） | 同上 `:16808-16812` |
| `mfnr_dft` 默认 true | `app/src/main/java/com/filmkit/app/camera/CameraController.kt:4056` |
| PC 对照与 T3 草案 | `docs/reports/pc-still-perf-RESEARCH.md:16,83,103,134,154,166,174,209,211` |

### 5.4 未取得证据（重复声明，防止被后续引用误用）

- `merkouris/hdrplus`、`johanneskopf/hdrplus`：GitHub API **404**，不存在或不可访问。
- GCam ports（MGC/AGC/LMC/BSG/Sabre）：**无公开源码**，无 `文件:行`。
- 「mobile-raw 项目」：GitHub 搜索无匹配。
- ProCamera（iOS/Android 商业 App）：闭源，未取证。
- `jhfmat/ISP-pipeline-hdrplus` 的 GPU 路径：不存在（纯 CPU + NEON）。
