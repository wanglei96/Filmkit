# PhotonCamera 拍照链性能调研（面向 FilmKit 6.2–7.3s 的压缩）

- 调研对象：`/tmp/pc-full`（PhotonCamera，HEAD `e657e62e`）
- 对照对象：FilmKit `core/engine` + 0.13.6/0.13.9 真机剖面（`.zcode/memory/facts.md:120`）
- 范围：**只讲拍照（still）链的 GPU/CPU 成本结构**。预览卡顿、strip 条带与 EGL 上下文优先级由另一路调研负责，本文不展开。
- 性质：只读调研，未改任何业务代码。

> **证据口径声明**：本文 PC 侧全部结论来自源码结构（文件:行），**不含 PC 在 X300 上的实测毫秒数**——本机无法运行 PC。凡"预估收益"均标注为推断，依据是 FilmKit 自己的实测剖面与像素量级换算，不是 PC 的实测值。
>
> **行号快照**：`core/engine/src/render/FrameRenderer.cpp` 在本次调研期间（20:05–20:11）正被并行修改（`git status` 显示 `M`，含 0.13.8 的 `drawStriped` 实验代码），行号已按 **20:11 快照**校对。若后续继续改动，请以**函数名 + 日志 key**（`passMfnrFft` / `mfnr.fft.compile_ms` / `alignCpuMs` / `dnMs`）为准锚定，行号仅作导航。

---

## 0. 结论摘要

1. **PC 的 still 链没有独立去马赛克级、没有 FFT、没有频域收缩。** 它在 **RAW Bayer 域**用「归一化卷积 + 逐帧拒绝权重」做多帧融合（`GlesMgcRawSpatialStacker.kt:6714` 起），融合时顺带重建全分辨率绿通道（`GlesMgcGreenDemosaicShaders`），融合完才进色彩/影调。我们 1.2–1.5s 的 DFT 在 PC 里根本没有对应物。
2. **PC 的成本控制靠"降域"而不是"优化大 pass"**：guide/rejection 在 RAW/2（`MgcSpatialRejectionGeometry.kt:33-36`）、merge weight 在 RAW/4、对齐金字塔 1/2→1/4→1/16→1/64（`GlesMgcRawSpatialStacker.kt:3663-3664`）、finish 可整体降到 1/2 或 1/4 再引导上采样（`raw/MgcFinishResolution.kt:7-31`、`raw/MgcGuidedUpsample.kt:33-71`）。**真正全分辨率的只剩 merge 的逐像素加权和 + 最后输出。**
3. **PC 全程零中间 readback**：RAW 以 `R16UI` 单通道直传（`GlesMgcRawSpatialStacker.kt:7449-7463`），合并累加器是 4 张 `R32F`（`:6751-6753`），只在最后导出一次 RGB16（`:6562`、`:6612-6642`）。我们的对齐每帧 `glReadPixels` 3–4 次（`FrameRenderer.cpp:17724-17751`），DFT 前后各一次 `glFinish`（`:16654`、`:17902`）。
4. **PC 的对齐在 GPU 上做**（LK 金字塔 + block LK compute，`:3821`、`:4168`、`:4286`），最细层就是 RAW/2。我们的 `alignCpuMs` 164–203ms 是 CPU SAD（`Align.cpp:14-17`），外加同步读回。
5. **最便宜、最确定的一刀**：`passMfnrFft` **每次拍照都现场 `compileShader`+`glLinkProgram`**（`FrameRenderer.cpp:16486-16511`），而且已经埋了日志 `mfnr.fft.compile_ms`。PC 是 `initPrograms` 一次性初始化、按需裁剪（`GlesMgcRawSpatialStacker.kt:604-609`）。这条改动几分钟、零质量风险。
6. **路 A（空间域融合）在 PC 里有完整可对照的实现，路 B（overlap 25%）与性能目标相反**：我们当前 DFT 是 50% 重叠（hop 8 / tile 16，`FrameRenderer.cpp:3815-3818`）；把 overlap 降到 25% 意味着覆盖数从 4/像素降到 ~1.78/像素，工作量约 ×0.44，但窗和不再恒 1（升余弦窗只在 50% 重叠时和为 1），必须重做窗归一化，且 tile 边界质量要重新验证。**overlap 增大方向（如 25%→50%）只会更慢**，不能作为提速选项。

---

## 1. PC 的拍照链全景（pass 序列 / 尺寸 / 格式）

### 1.1 入口与调度

| 环节 | 位置 | 说明 |
|---|---|---|
| 拍照任务串行化 | `processor/CaptureProcessingQueue.kt:19-49` | 单协程 + `Channel.UNLIMITED` 串行消费，一张一个 request；内存由 ImageReader 50 槽在上游封顶（`:24` 注释）。**没有并发拍照**，连拍排队。 |
| 融合入口 | `processor/GlesMgcRawFusion.kt:38-103` → `:224-243` | 先做基础帧选择（RAW 内容锐度），再选 Spatial / Sabre 两条栈。 |
| GL 线程与上下文 | `GlesMgcRawSpatialStacker.kt:594`、`:596` | `lowerCurrentThreadPriority`（降到 `THREAD_PRIORITY_BACKGROUND`，见 `GlesGpuScheduler.kt:276-288`）后 `attachCurrentEgl()` 或 `initEgl()`；即 **拍照有自己的 EGL 上下文（后台低优先级）**，不借用预览上下文。 |
| 程序初始化 | `:604-609` | `initPrograms(includeBento…, includeReferenceHighlightMask…, includeTemporalProcessing…)` 一次编译，**按帧角色裁剪**。 |

### 1.2 pass 序列（Spatial RGB 路径，按代码顺序）

| # | Pass | 输出域/格式 | 位置 |
|---|---|---|---|
| 0 | RAW 上传 + 热像素抑制 | 全分辨率 `R16UI`（单通道，`glTexSubImage2D` + `UNPACK_ROW_LENGTH` 直传裁剪区） | `:7449-7463`、`:7276-7303` |
| 1 | 灰金字塔 | RAW/2 → 1/4 → 1/16 → 1/64，`R16I` | `:3658-3724` |
| 2 | 参考帧 LK 乘积（每级一次） | tile 级梯度/结构张量 | `:3759-3820`、`:3821` |
| 3 | Block LK（每帧 × 每级，compute） | tile 位移场 | `:4168-4200`、`:4286` |
| 4 | guide（每帧） | RAW/2，`RGBA16F` | `:4405`、`:659-668` |
| 5 | covariance（每帧） | RAW/2，`RGB10_A2` | `:4439`、`:669-688` |
| 6 | rejection 链（每帧）：acceptance → pixelDiff → downsample → filter → postprocess → dilate | RAW/2 与 RAW/4 域 | `:4665-4916` |
| 7 | 逐帧准备（upload/校准/权重/位移转换） | 复用 1–6 的纹理 | `:4922`、`:148` `PreparedTemporalFrame` |
| 8 | merge 逐帧 4 个 pass：convert → greenBalance → green → accumulate(compute) | 全分辨率累加器 4×`R32F`（R/G/B/权重） | `:6746-6753`、`:6755-6849` |
| 9 | resolve + outputAssembler | 全分辨率 RGB16 单次导出（`w*h*3*2B`） | `:6852-6872`、`:6562`、`:6612-6620` |

**量化（4080×3072 输入）**：
- 全分辨率纹理只有：RAW `R16UI`（25MB/帧）、绿引导平面（`R16I` ×3，带 padding，`:6747-6750`）、4×`R32F` 累加器（各 50MB，共 200MB）、输出 RGB16（75MB）。
- 其余一律 ≤ RAW/2 或 RAW/4。

### 1.3 与"独立去马赛克级"的关系

PC **没有** RCD/AMaZE 这种独立 demosaic pass：绿通道在 merge 内由 `DemosaicGreenRaw16` 引导重建（`GlesMgcRawSpatialStacker.kt:6741-6744`、`GlesMgcGreenDemosaicShaders`），红蓝由归一化卷积直接给出。这与我们在 `facts.md:119` 记的"PC 的 RAW 去马赛克不是独立级"一致，也解释了 PC 为什么**省掉一整个全分辨率 pass**。我们这边 `dirMs 39–66ms` 本来就不贵，但"merge 顺带 demosaic"仍是结构上的省。

---

## 2. GPU 工作分解：PC 到底"省"在哪

### 2.1 域降采样（最大的一条）

```
guide / rejection : RAW/2   (2040×1536)   MgcSpatialRejectionGeometry.kt:33-36
merge weight      : RAW/4   (1020×768)    :35-36
filter            : RAW/4 / downsample    :47-48
对齐金字塔         : RAW/2, 1/4, 1/16, 1/64  GlesMgcRawSpatialStacker.kt:3663-3664
```

注释里直接写了 4080×3064 的运行时缓冲：`2040×1532, 1021×767, 256×193, 65×49`（`:3664`）。**对齐只在 65×49 的粗层上也可能收敛**（tile 8，`MgcRawLkPyramidPlan.kt:24-56`），成本可忽略。

对照我们：金字塔 1/2 域起（`FrameRenderer.cpp:17714`），但**最细精化层放在 1/2 域**并要 readback 全部层（`:17719`），CPU SAD 在 1/2 域上跑。

### 2.2 格式：单通道优先

- RAW 输入 `GL_R16UI`（`:646-651`），不是 `RGBA16UI` 打包。带宽 2B/px。
- 对齐中间 `GL_R16I`（`:3667-3672`）。
- 累加器 `GL_R32F` 单通道 ×4（`:6751-6753`），因为 merge 要 `imageLoad`+`imageStore` 读改写，ES 3.1 只保证 `r32f` 的这条路（我们 DFT 注释里也踩过同一个坑，见 `FrameRenderer.cpp:3830-3831`）。
- 只有 guide / 最终输出用 `RGBA16F` / RGB16。

对照我们：DFT 输出 **4 张全分辨率 `RGBA16F`**（`FrameRenderer.cpp:16435-16450`）≈ 4080×3072×8B×4 = **401MB 写入**，收口 pass 再把这 4 张全读一遍合成（`:16680-16690`）≈ 再加 500MB 带宽。这是 DFT 路径除 FFT 计算外的一笔硬成本。

### 2.3 零中间 readback / GPU 内生

- merge 全程 GPU，累加器停在显存（`:6820-6849`）。
- 唯一的 CPU 读回是**强度图的 PBO 异步排队**（`:5839 queueTextureReadback`、`:5988 queuePreparedTextureReadback`、`:6095 mapPixelPackBuffer`），且注释明确"等第一个 CPU 消费者才 materialize"（`:6128`）。
- 最终导出可全程 GPU：`exportGpuLinearRgbSource` + `GpuLinearRgbStorage.RGBA16UI` 直接把纹理交给下游（`:6621-6642`），不落 CPU。
- 诊断读回（`readRgba32f`、`readR8Mask`、`countActiveMaskPixelsGpu`）都在开关后面。

对照我们：对齐每帧 3–4 次 `glReadPixels`（`FrameRenderer.cpp:17724-17751`），**同步阻塞到 GPU 空闲**；DFT 前后两次 `glFinish`（`:16654`、`:17902`）；降噪链末尾一次 `glFinish`（`:15127`）。这些同步点让 CPU 无法把下一段工作提前提交。

### 2.4 tile 的真实用法：合并本身**不切 tile**

`MgcSpatialRgbTilePlanner` 提供两种切法：1024² 输出 tile（`:51`、`:54-77`）和全宽横带（`:85-103`）。但 Spatial RGB 路径的合并走的是**单 tile = 全图**：

- `renderSpatialRgbMerge` 里 `fullOutputTile = MgcSpatialRgbTile(index = 0, outputCore = fullOutput)`（`:6564-6565`），随后 `initStorage(listOf(fullOutputTile))`（`:6583`）。
- 合并循环按**帧**迭代（`:6755`），不按 tile 迭代。

tile 化只出现在两处：对齐 atlas 的 `TILE_SIZE = 16` 索引（`GlesMgcSpatialRgbMergeShaders.kt:30`、`:6543-6544`），以及 `TILED_RECONSTRUCTION` 阶段（`GlesGpuCompletion.kt:10`，Bento/强度路径）。

**这对我们是反向信息**：PC 并不是靠"把 merge 拆成小 tile 提交"省时间，而是靠每帧 4 个**短 pass**（convert → greenBalance → green → accumulate，`:6755-6849`）天然形成细粒度的 GPU 任务边界，外加帧间 `yieldToUiRenderer()`（`:6849`）。我们的 DFT 是**一个 dispatch 里跑完整个相位组的 FFT + 逐频收缩**（`FrameRenderer.cpp:16587-16608`），颗粒度差一个量级。**"拆 tile"与"拆 pass"不是一回事**，照搬前者不会得到后者的效果。

### 2.5 让出与背压：fence 化提交（still 侧影响提交节奏）

- 每帧 merge 结束 `GlesGpuScheduler.yieldToUiRenderer()` → `glFlush()` + `Thread.yield()`（`:6849`、`GlesGpuScheduler.kt:299-302`）。
- 每 pass 结束打 `glFenceSync`，进 `PassWindow`（`GlesGpuScheduler.kt:77-88`），资源冲突才等（`:93-106`、`:175-183`），**不是 `glFinish`**。
- 等待统一走 `glClientWaitSync` + 100ms 切片（`GlesGpuCompletion.kt:96`、`:154-171`），超时轮询而不是阻塞整个队列尾；`GpuStackCompletionTimeline` 的检查点是"插入但不等待，第一个 CPU 消费者按序等"（`:35-74`）。
- 注释里有 Mali 专项经验：生产者 fence 在消费者已提交后可能不 retire，所以要用资源声明在编码前 retire（`GlesGpuScheduler.kt:22-28`）。这是 Mali 上"分帧提交"能成立的关键前提之一。

---

## 3. 对齐：算法、分辨率、成本

### 3.1 PC

- 算法：**Lucas-Kanade 金字塔**（block 级）+ 全局直方图峰值候选（`estimateGlobalAlignmentCandidate`，`:4080`），每级 tile 64/32/16/8（`MgcRawLkPyramidPlan.kt:28-53`），每级迭代若干次（`renderLucasKanadeLevel`，`:4168`）。
- 分辨率：最细 RAW/2（`2040×1532`），逐级 1/4、1/16、1/64（`:3663-3664`）。
- 执行：GPU，compute（`:4286` `glDispatchCompute(gridWidth, gridHeight, 1)`）。
- 参考帧的 LK 乘积只算一次，所有帧复用（`ReferenceAlignmentProducts`，`:138-146`、`:3759`）。
- 子像素：由 LK 迭代收敛 + `renderUpsampledAlignment` 上采样（`:3992`）。
- 读回：仅全局候选的直方图峰值（小），无全图读回。

### 3.2 我们

- 算法：tile 级 SAD + 抛物线亚像素（`core/engine/src/render/Align.cpp`，全文 369 行），tile 32/16/8 逐级精化。
- 分辨率：1/2 域（`FrameRenderer.cpp:17714-17719`）。
- 执行：**CPU**，`parallelRows` 8 线程（`Align.cpp:14-37`）；实测 `alignCpuMs` 164–203ms（3 个非参考帧的累计，`FrameRenderer.cpp:17724-17751` 的 `align_cpu_ms +=`）。
- 读回：每帧 3–4 次同步 `glReadPixels`（`17724-17751`），1/2 域 ≈ 3MB/帧。

### 3.3 判断

`alignCpuMs 164–203` 是 3 个非参考帧的累计值；无论按帧均摊还是按累计看，PC 的对齐成本在这条链里接近 0（GPU、1/64 粗层收敛、零读回），我们的对齐是"CPU 算 + GPU 等"的双重开销。**对齐本身不是最大头（DFT 才是），但它是唯一"改动可控、收益确定"的一块**——尤其是把读回改成 PBO 异步这一半，不动算法就能拿。

---

## 4. 异步 / 流水线：PC 在结构上做对的几件事

1. **一张一条协程、串行消费**（`CaptureProcessingQueue.kt:29-44`）——没有多张并发的显存峰值，也没有锁竞争。
2. **fence 而不是 finish**（`GlesGpuCompletion.kt:141-152`）：`glFenceSync` + `glFlush` + 切片 `glClientWaitSync`。`glFinish` 只出现在错误回退路径（`GlesGpuScheduler.kt:83`、`:128`）。
3. **每个 pass 一个 fence + 资源声明**（`GlesGpuScheduler.kt:77-88`），既保留 2 个 pass 在飞（`:31`），又不会让消费者排在生产者后面（`:64-70`）。
4. **计时归因与同步分离**：`awaitSubmittedWork` 先等 fence 再计 transfer，避免把整个队列尾算进 `glReadPixels`（`GlesGpuCompletion.kt:88-94` 注释）——这正是我们 `alignRbMs/alignCpuMs` 想做的事，但我们现在是用 `glFinish` 硬切。
5. **compute 只用在真需要的地方**：整个 stacker 里只有 7 处 `glDispatchCompute`（`:4286` LK、`:5432` 强度、`:6066`/`:6946` pack、`:6831` merge 累加、`:7388`/`:7398` 热像素），其余全是 fragment pass。唯一的全图 dispatch 是 merge 的逐帧累加（`:6831`），而它是**逐像素 4 读 4 写**的短内核；**没有任何一个 dispatch 里塞 FFT / 卷积这类长任务**。

---

## 5. 与我们的剖面逐项对照

实测剖面（`.zcode/memory/facts.md:120`，0.13.6/0.13.9 冷机，4080×3072，4 帧）：

| 段 | 我们 | PC 的对应结构 | 差距性质 |
|---|---|---|---|
| DFT 合并 | `fuseMs` 1201–1561 | 无 FFT；空间域归一化卷积（`:6714`） | **算法级差**（最大头） |
| P0 降噪 | `p0Ms` 300–352 | 无独立 P0（融合时带权重） | 结构差 |
| 预处理 | `preMs` 949–1223（含 ~340ms 未归因） | 热像素 `:7276` + guide RAW/2 | 域差 + 归因差 |
| 降噪链 | `dnMs` 542–1131（全分辨率） | native 库静态 kernel（`raw/MgcFullResolutionDenoise.kt:952/973` 的 `external fun`，非 GL pass），finish 可 2×/4× 降采样（`raw/MgcFinishResolution.kt:7-31`） | **策略差**（降域 vs 全分辨率） |
| 对齐 | `alignCpuMs` 164–203 + 同步读回 | GPU LK，零读回 | 实现差 |
| 方向场/RCD | `dirMs` 39–66 | 无独立级 | 我们已不慢 |
| 显示闭环 | `dispMs` 378–423 | 无对应 | 我们独有 |
| 编码 | 189–290 | — | — |
| 合计 | `totalMs` 5162–6523 / 快门→落盘 6.15–7.27s | — | — |

### 5.1 「A（空间域融合）/ B（overlap 25%）」的依据

**A 有完整参照，而且 Google 自己就是从 FFT merge 走到 Spatial merge 的**：PC 直接移植 MGC V25 的 `SpatialMergeProcessor`（`GlesMgcSpatialMergeTuning.kt:42` 注释 `SpatialMergeParams::BuildForSnr at libgcastartup.so+0x386b3cc`），kernel 是每输出样本 **3×3 RAW 邻域**（`MgcSpatialRgbTilePlanner.kt:42-49` 注释），权重来自逐帧 rejection（`:4665-4916`），全程归一化卷积。它没有 tile 边界问题（3×3 邻域天然连续），也就不需要 overlap/window。

成本量级：每输出像素 ~9 次乘加 × 4 帧 ≈ 36 次；我们 DFT 是每 16×16 tile 两次 16 点 FFT + 逐 bin 收缩，按每像素折算高一个量级，且要 4×全分辨率 `RGBA16F` 写出。**A 的理论收益是把 `fuseMs` 从 1.2–1.5s 压到几百 ms 量级，并且顺带干掉 400MB 的中间纹理。**

另外，**PC 不拆相位组**：它直接在 RAW 网格上按 CFA 位置取 4 个白平衡增益（`:6729-6736` 的 `redSite`、`:400-406` 的 4 通道 WB），归一化卷积的输出就是一张全分辨率 RGB16（`:6562`）。所以 PC 根本没有"4 张全分辨率相位层"这个问题——我们 DFT 的 4 张 `RGBA16F` 是"4 相位组并行 + overlap-add"的副产物，不是合并本身必需的。

**B 要先弄清方向**：
- 我们当前是 **50% 重叠**：tile 16、步长 8（`FrameRenderer.cpp:3815-3818`），每像素被 4 个 tile 覆盖，窗和恒 1（升余弦窗在 50% 重叠时的性质）。
- 若 B 指"overlap 从 50% **降到** 25%"：覆盖数降到 ~1.78/像素，工作量 **×0.44**（预估 `dftDsp` 降约 55%），但 (a) `½−½cos(2π(x+½)/16)` 的窗和不再是 1，overlap-add 会产生亮度纹波，必须加窗归一化（多了逐像素 4 点窗和查表，小成本）；(b) hop 变大后 tile 边界的相位/对齐残差更明显，**质量风险必须 A/B**。按项目"质量优先"铁律，这条路只能作为"实测后由用户拍板"的候选。
- 若 B 指"overlap 增到 25%→50%"：那是**更慢**的方向，不能当性能手段。

**结论：性能方向选 A（空间域），B 只能作为 A 落地前的止血实验，且必须带质量 A/B。**

---

## 6. 可借鉴清单（按「收益 ÷ 成本」排序）

### T0 · 立即可做（分钟级，零质量风险）

| # | 做法 | PC 证据 | 我方落点 | 预估 |
|---|---|---|---|---|
| 1 | **DFT compute program 缓存到 init**，不要在每次拍照里 `compileShader`+`glLinkProgram` | `GlesMgcRawSpatialStacker.kt:604-609`（initPrograms 一次） | `FrameRenderer.cpp:16486-16511` | 省掉 `mfnr.fft.compile_ms` 全值（已埋点，先看日志） |
| 2 | **`glFinish` 换 fence**：DFT 自检、DFT 后、降噪后三处 | `GlesGpuCompletion.kt:141-152`、`GlesGpuScheduler.kt:77-88` | `FrameRenderer.cpp:16654`、`:17902`、`:15127` | 预估 50–150ms（取决于队列尾长度） |

### T1 · 低成本中收益（半天–1 天）

| # | 做法 | PC 证据 | 我方落点 | 预估 |
|---|---|---|---|---|
| 3 | **对齐读回 PBO 化 + 双缓冲**：4 层读回先全部提交，再等 fence | `:5839`、`:6095`、`:6128` | `FrameRenderer.cpp:17724-17751` | 省掉 3–4 次同步等待，`alignRbMs` 大部分 |
| 4 | **降噪/色度链的 `glFinish` 全部换 fence**，只在最后落盘前等一次 | 同 T0-2 | `FrameRenderer.cpp:15127` 等 | 预估 50–200ms |

### T2 · 中成本中收益（2–5 天）

| # | 做法 | PC 证据 | 我方落点 | 预估 |
|---|---|---|---|---|
| 5 | **DFT 输出从 4×全分辨率 `RGBA16F` 降到分块累加**（相位组输出只保留 band，或直接累加进单张） | PC 只有一组 4×`R32F` 累加器（`:6751-6753`），无 4 份整图 | `FrameRenderer.cpp:16435-16450`、`:16680-16690` | 省 ~400–900MB 带宽，预估 150–400ms |
| 6 | **finish 链降域 + 引导上采样**（先对 1/2 或 1/4 做色彩/降噪，再全分辨率引导回） | `raw/MgcFinishResolution.kt:7-31`、`raw/MgcGuidedUpsample.kt:33-71` | `FrameRenderer.cpp` 降噪链（`dnMs`） | 2× 降域理论 ÷4 计算量；`dnMs` 542–1131 可能 → 200–400，**但质量必须 A/B，不能默认开** |
| 7 | **对齐 GPU 化（LK 金字塔）** | `:3658-3724`、`:4168`、`:4286` | `Align.cpp` 整体替换 | 干掉 `alignCpuMs` 164–203 + 读回 |

### T3 · 高成本高收益（1–2 周，需立项）

| # | 做法 | PC 证据 | 我方落点 | 预估 |
|---|---|---|---|---|
| 8 | **路 A：空间域融合替代 DFT** | `GlesMgcRawSpatialStacker.kt:6714-6849` + `GlesMgcSpatialMergeTuning.kt` + `MgcSpatialRejectionGeometry.kt` | **`passRawDenoiseFused`（`:16812`）已经是 4 帧 × 3×3 同相位邻域 = 36 样本的 GPU 空间融合**，可作原型起点 | `fuseMs` 1.2–1.5s → 预估 300–600ms |

> 说明：T3-8 不必从零写。我们 P0 的 `passRawDenoiseFused`（`FrameRenderer.cpp:16812-16906`，`dbg_p0s_ = 96`）做的正是"时域 × 空间联合 36 样本加权"，与 PC 的归一化卷积同构，缺的是逐帧 rejection 权重与逐帧位姿注入。把它扩展成 merge 主路径、让 DFT 退为可选，**比新写一套便宜一个量级**。

### 明确不适用 / 不能照抄的

| PC 做法 | 为什么不适用 |
|---|---|
| `EGL_CONTEXT_PRIORITY_LEVEL_IMG`（`GlesGpuScheduler.kt:226-247`） | 这是**预览 vs still 的资源抢占**手段，属另一路调研；对 still 总耗时没有缩短作用。 |
| native 库静态 kernel 降噪（`raw/MgcFullResolutionDenoise.kt:952/973`，依赖 `libmy-native-lib`） | 闭源/移植成本；且它与 GL 队列无 fence 关系、吃的是另一份资源，照搬也压不掉我们 GPU 侧的时间。可抄"降域"策略，不必抄实现。 |
| `CaptureProcessingQueue` 单协程串行 | 我们已有独立拍照线程（见 `.zcode/memory/archive/拍照独立线程落地与连拍崩溃定案.memo.md`），结构等价。 |
| `exportGpuLinearRgbSource` / `RGBA16UI` GPU 直通 | 我们成片要落 HEIF/JPEG 且要过显示域闭环，不能全程留在 GPU；但"中间段不落 CPU"这一条可以局部抄。 |

---

## 7. 最小验证实验建议

按"先便宜后贵、每步只动一个变量、都带日志字段"的顺序。所有观测字段都已在现有 flow 日志里，不需要新增埋点。

### 实验 1（约 30 分钟）：DFT program 缓存

- **改哪**：`core/engine/src/render/FrameRenderer.cpp:16486-16505`，把 `compileShader/glCreateProgram/glLinkProgram` 移到 `ensureMfnrFftTargets`（`:16426`）或 init 阶段，成员变量缓存，失败时仍回退到现场编译。
- **观测**：`mfnr.fft.compile_ms`（`:16511-16512` 已埋）、`dftDsp`、`fuseMs`、`totalMs`。
- **判据**：`compile_ms` → ~0；`fuseMs` 的下降量 ≈ 原 `compile_ms`。若 `compile_ms` 本来就 <30ms，这条就不用做了（日志会直接告诉你）。

### 实验 2（半天）：对齐读回 PBO 化

- **改哪**：`FrameRenderer.cpp:17724-17751` 的 `readL` 改成"提交 4 个 PBO 读回 → 一个 fence → 再 map"，用双缓冲跨帧重叠。
- **观测**：`alignRbMs`、`alignCpuMs`（`:18260` 已埋）、`totalMs`。
- **判据**：`alignRbMs` 应显著下降（现在它包含 GPU 队列尾），`alignCpuMs` 不变（算法没动）。

### 实验 3（1 天）：DFT overlap 25% 的 A/B（止血候选）

- **改哪**：`FrameRenderer.cpp:3888` 的 `sWin[16]` 窗表 + `:16587-16608` 的组/`uTxMax` 循环 + 窗和归一（hop≠tile/2 时 `½−½cos` 窗和不为 1，需逐像素 4 点窗和）。
- **观测**：`dftDsp`、`dftGpu`、`fuseMs`、`totalMs`；质量侧比对同一 RAW 的成片（PSNR + 肉眼 tile 边界纹波）。
- **判据**：`dftDsp` 应 ≈ ×0.44；若成片出现网格状纹波即否决。**结果交用户拍板，不自行默认。**

### 实验 4（2–3 天）：降噪链 1/2 域 + 引导上采样

- **改哪**：`FrameRenderer.cpp` 的 `passChromaDenoise`/`passPc3d` 调用处（`:15066-15127`）改成低分辨率输入 + 复用现有全分辨率引导上采样（若没有，需新写一个 guided upsample pass）。
- **观测**：`dnMs`、`totalMs`，以及成片与全分辨率版的逐像素差（重点看高频细节与色噪）。
- **判据**：`dnMs` 预期 ÷3 左右；质量下降超阈值即回退（项目铁律：质量优先）。

### 实验 5（1–2 周）：路 A 原型（用 P0 骨架）

- **改哪**：以 `passRawDenoiseFused`（`:16812`）为骨架，把"36 样本等权/噪声权重"换成 PC 式**归一化卷积 + 逐帧 rejection 权重**（权重图可复用现有 mismatch/conf 的产物），产出 `tex_fused_`；DFT 保留为 prefs 开关做 A/B。
- **观测**：`fuseMs`、`neff`、`p0Ms`（P0 是否可并入 merge）、`totalMs`；质量对照 DFT 版（噪声、细节、鬼影）。
- **判据**：`fuseMs` 与 `p0Ms` 之和 < 原 `fuseMs` 即算赢；质量必须逐项不降。

---

## 附：本文引用的 PC 关键文件

- `processor/GlesMgcRawFusion.kt`（入口与帧调度）
- `processor/GlesMgcRawSpatialStacker.kt`（8414 行，主栈）
- `processor/MgcSpatialRejectionGeometry.kt`（域尺寸）
- `processor/MgcSpatialRgbTilePlanner.kt`（1024 tile / band 规划）
- `processor/MgcSpatialMergeTuning.kt`（MGC 参数反推）
- `processor/MgcRawLkPyramidPlan.kt`（LK 金字塔几何）
- `processor/GlesGpuScheduler.kt` / `GlesGpuCompletion.kt`（fence/优先级/让出）
- `processor/CaptureProcessingQueue.kt`（串行队列）
- `raw/MgcFinishResolution.kt` / `raw/MgcGuidedUpsample.kt`（finish 降域）
- `raw/MgcFullResolutionDenoise.kt`（native 库降噪 + 强度图）
