---
summary: 学习型/生成式成像在移动端的真实可行形态调研。工业界主流 = 传统 ISP 主链 + 并行专用小模型 + 统一多后端运行时（vivo X300 真机 85 库二进制取证）；其内部真实落点是"参数/gain 预测式"（Google HDRNet PGTM：低分辨率推理 → bilateral grid gain → 烘焙 DNG PGTM → 成片路径零 NN），与端到端替代是不同路线。扩散成片渲染判定不做（算力差约两个数量级 + 幻觉致命）。部署硬约束：ncnn 唯一活跃同构选项、Arm NN 已停维护、MTK APU 为二值前提。建议：选 ② 形态并优先参数预测式（低频可参数化 → ②；高频逐像素 → ③），附验证路径与 7 条失败模式（3 条本项目已踩）。
created: 2026-10-13
status: draft
---

# 12 · 学习型 / 生成式成像在移动端的真实可行形态

> 调研范围：**整个成像链的学习化与生成式**这一格。
> 明确**不覆盖**：(a) 影调映射的学习化 / image-adaptive 3D LUT / HDRNet → 见 `13-学习型影调与自适应色彩映射.md`；(b) 色彩科学 / 自动白点 → 见 `16-影调科学前沿与自动白点.md`。
> 服务决策：自研 Android 胶片相机引擎（C++20 + OpenGL ES，vivo X300 / 天玑 9500 + Mali GPU + MTK APU）**是否 / 如何引入神经或生成式组件**。

## 结论先行（TL;DR）

1. **工业界主流形态 = 混合式：传统 3A/ISP 主链不动，AI 以"并行/旁路专用小模型"挂载，底下垫一层统一的多后端推理运行时。** 本机真机取证（85 个 vivo native 库，§1.4）证据最强：`libvdnn.so` 是完整商业级推理运行时（会话/内存池/**fence 异步**/功耗调度/**GPU kernel autotune**/OpenCL+CPU-int8 三套 profiler），被 **20 个模块**共享；AI 功能库则是"**按算子逐层手搓的量化小模型 + gemmlowp NEON int8 + 平台后端探测（HVX/GPU/APU）**"。**没有任何证据支持"端到端大网络替代 ISP"**；传统算法库（`libcamalgo.mfnr`）与 AI 库（`libainr`）在真机上**并存**。

2. **★ 在该形态内部，工业界的真实落点是"参数/gain 预测式"（本文路线 ②）——NN 只输出低维映射，成片路径保持确定性查表。** 硬实证：Google **HDRNet PGTM**（MGC/魔改 GCam 取证，§4.1.1）输入 **`[1,192,256,4]`**、输出 **`[1,12,16,8,1,2]` 的 bilateral grid gain**（钳 `[0.03,30]`，**不是 RGB**）、**CPU XNNPACK 推理**、最终**烘焙成 DNG `ProfileGainTableMap2`（64×48 空间格 × ≥257 强度节点）** ⇒ **成片路径上零 NN，只有查表**。同类参数预测式还有 GenISP、CRISPnet。**分野源于信息论：增益/影调低频可参数化，噪声/细节高频必须看全分辨率** ⇒ 给出判据：**低频环节用 ②，高频环节只能 ③**。

3. **生成式（diffusion / one-step）用于成片渲染：不做。** 障碍不是工程量而是**约两个数量级的算力/带宽差距**——最强实时方案（SwiftVR）在**消费级 RTX 5090** 上刚做到 1080p 26 FPS，手机要处理 12MP 且受热/功耗约束；叠加**幻觉致命**（该领域公认核心矛盾：幻觉"plausible but incorrect"，HalluGen 量化 IoU **0.86→0.36**，属安全关键问题）与**控制粒度仅 1 个标量**（RCOD/IDaS-SR）。**未取证**到任何厂商在成片主链用扩散的一手宣称。

4. **部署硬约束**：**ncnn 是唯一"活跃维护 + 与本项目 C++ 引擎同构"的选项**（Vulkan/NEON、零拷贝、int8+fp16）；**OpenCL 在 vivo 量产已验证**（Mali 上需配 kernel autotune）；**Arm NN 官方已停维护**（真机里有 `libarmnn.so` ⇒ **"库在机器上"≠"路线是活的"**）；**MTK APU 是二值前提**（NeuroPilot 是否对第三方开放未说明，**不应作为方案前提**）。

5. **对本案的建议**：选 **② 形态**，且**优先"参数预测式"而非"像素输出式"**。P0 = 局部色调映射（低频，方案论证属 `13-学习型影调与自适应色彩映射.md`）；P2 = 神经降噪（**高频，只能走 ③**，须先满足 §4.3 全部前置条件——本项目历史瓶颈在**数据与对齐**，不在算力）。明确不做：端到端替代、扩散成片。**3 条已实际踩过的失败模式**见 §4.4 带 ★ 项：**数值对齐但观感零改善 / 跨域权重直接复用 / 过拟合少数场景**。

## 0. 证据分级与本文口径

本文结论按下表标注强度。**"未取证"≠"不存在"**，仅表示本轮预算内未拿到一手来源。

| 级别 | 含义 | 标注 |
|---|---|---|
| A | 源码/二进制原文、论文原文、官方文档（可复现） | `[A]` |
| B | 官方技术博客/挑战赛报告/厂商技术页 | `[B]` |
| C | 二手推断、媒体转述 | `[C·未验证]` |
| D | 本方推演（无外部来源，但基于 `[A]` 事实） | `[D]` |

**工具环境限制（如实记录）**：`web_search` 在本会话**不可用**（无 API key）⇒ 已改用 `web_fetch` 直连 **arXiv 检索 API** 与厂商官方文档，"发现式检索"能力受限。故部分条目以"未取证"收尾，而非硬凑来源。

## 1. 移动端 AI-ISP 的真实现状（2023–2026）

### 1.1 工业界主流形态：混合式（传统主链 + 并行 AI 小模型）

**结论：成立，且证据来自三个互不依赖的方向 `[A]`。**

**(a) 真机二进制（最强，见 §1.4）**：`libcamalgo.mfnr`（传统多帧降噪）与 `libainr`（AI 降噪）**在同一台机器上并存**；`libvdnn` 作为共享运行时被 20 个模块调用。工业界做的是"**加一条 AI 旁路**"，不是"换掉主链"。

**(b) 公开资料对该形态的独立描述**。`arXiv:2609.11134`（LAION-Mobile, 2026-09）在动机段转述一篇 ICML position paper 时，把现代手机的"neural ISP"具体列举为 **multi-sensor fusion / noise suppression / motion-blur suppression**：

> "the widely used on-device neural image-signal processing pipelines (like multi-sensor fusion or noise and motion-blur suppression) increasingly shift the imaging paradigm from simple lens projections towards computational photography. Hence, **devices actually generate, rather than record photos**."
> — [arXiv:2609.11134](https://arxiv.org/abs/2609.11134)（`[A]` 论文原文；但该句是对第三方论文的**转述**，属二级引用）

要点有二：(i) 被点名的全是**具体环节**，没有任何一处是"端到端网络"；(ii) "devices generate rather than record" 指的是**计算摄影（多帧融合）**，**不是**扩散生成——这一点常被二手文章混淆，本文严格区分。

**(c) 竞赛任务设置本身反映工业界需求**：MAI 2026 的图像复原赛道任务是 **real image denoising**（单环节），而非"替代整条 ISP"（详见 §1.3）。挑战赛的题目选择即需求信号。

**形态命名（本文口径）**：传统 3A/ISP 主链 + **并行/旁路专用小模型** + 统一多后端推理运行时。这同时解释了真机取证中"AI 库与传统算法库并存"与"运行时被 20 个模块共享"两个现象——**只有运行时统一，才可能让每个环节各自挂小模型**。

**但"混合式"还不够精确，不足以支撑选型。** 本文在 §4.1 把它细化为**四条路线**，工业界的真实落点是：

> **②「参数/gain 预测式」——NN 只输出低维映射参数，成片路径保持确定性查表。**
> 硬实证 = Google **HDRNet PGTM**（§4.1.1）：`[1,192,256,4]` → `[1,12,16,8,1,2]` bilateral grid gain（**不是 RGB**）→ 烘焙成 DNG `ProfileGainTableMap2` → **成片路径零 NN**。同类：**GenISP**（"regress global color correction parameters"，[2205.03688](https://arxiv.org/abs/2205.03688)）、**CRISPnet**（[2203.10562](https://arxiv.org/abs/2203.10562)）。
> 分野判据（§4.1.2）：**低频可参数化的环节 → ②；高频逐像素的环节 → ③ 像素输出式**。

### 1.2 端到端网络替代 ISP：学术前沿，非量产形态

**该路线真实存在、且效果显著，但有三个量产级障碍；其中第三个对本项目是决定性的。**

**代表工作（全部 `[A]` 论文原文）**：

| 工作 | 出处 | 关键数据 |
|---|---|---|
| PyNET（开创性） | [arXiv:2002.05509](https://arxiv.org/abs/2002.05509) | 单模型 RAW→RGB，**目标是模仿 Canon 5D Mark IV 的成片**；10k 对 Huawei P20 RAW-RGB |
| AIM 2020 / Mobile AI 2021 ISP 挑战赛 | [2011.04994](https://arxiv.org/abs/2011.04994) / [2105.07809](https://arxiv.org/abs/2105.07809) | 目标写得很直白："**replace classical hand-crafted ISPs**"；MTK Dimensity 1000+ NPU 上 Full HD **60–100 ms** |
| Mobile AI & AIM 2022（GPU 版） | [arXiv:2211.03885](https://arxiv.org/abs/2211.03885) | **TFLite + Snapdragon 8 Gen 1 GPU**，Full HD **20–50 ms** |
| **Modular Neural ISP**（最重要） | [arXiv:2512.08564](https://arxiv.org/abs/2512.08564)（v3 2026-03，Afifi/Brown） | **全管线仅 0.5 M – 3.9 M 参数**；显式提供"**full control over multiple intermediate stages**"，理由 = scalability / debuggability / 未见相机泛化 / 匹配不同用户风格；支持无限次后编辑重渲染 |
| Small-Pixel Era Neural ISP | [arXiv:2606.07675](https://arxiv.org/abs/2606.07675)（2026-06） | 0.35 µm 像素下 MTF50 **745 cycles/mm**，相对传统 ISP **2.5–3×** 提升；LPIPS 0.244→0.151。**但是受控仿真**，作者来自 Glass Imaging（商业公司） |

**障碍 1 — 速度量级仍差一档。** 竞赛口径是 **Full HD（~2MP）**，量产要 12MP。`2211.03885` 的 Full HD 20–50 ms 若线性外推到 12MP 即 **120–300 ms**（还未计预览实时性）。这不是"不可行"，是"代价明确"。

**障碍 2 — 训练目标决定了它只能"克隆风格"。** PyNET 系列的监督信号是**某台参考相机的成片**（Canon 5D / FujiFilm GFX100），学到的映射是 `RAW → 那台相机的风格`。它能做到"像 P20 的 ISP"，做不到"按 98 个槽位调参"。

**障碍 3（决定性）— 端到端网络的参数控制面是黑箱，与本项目的根本诉求冲突。** 本项目的引擎是 **98 槽参数化**（`core/engine/src/film/FilmParams.h:35`）、预设可存可分享、编辑器可回溯；端到端单网络把"降噪强度 / 高光滚降 / 颗粒"等语义参数**压缩成不可寻址的权重**，上述能力全部消失。

**注意学术界自己的修正方向**：`2512.08564` 的卖点恰恰是**模块化 + 中间阶段可控**——这等于承认端到端黑箱在**产品**上不可用。**因此"端到端替代"的可行形态不是单网络，而是"每个阶段一个可独立控制的小网络"，即 §1.1 的混合式。** 这是本文最关键的一次形态收敛。

### 1.3 挑战赛与公开基线（Mobile AI / MAI 系列）

`Mobile AI Workshop` 与 `MAI (Mobile AI) Workshop`（CVPR 系列）是这条线最集中的一手数据源。**与本案技术栈重合度最高的一篇**：

> **Real Image Denoising with Knowledge Distillation for High-Performance Mobile NPUs**
> [arXiv:2605.03680](https://arxiv.org/abs/2605.03680) · CVPR Workshops 2026, pp. 3792–3800 · 代码+数据：`github.com/ABrain-One/NN-Dataset`

| 维度 | 该文数据 | 对本项目的意义 |
|---|---|---|
| 任务 | 真实图像**去噪**（单环节，非整链） | 与 §4.1 的 P0 候选 A 同型 |
| 模型 | **LiteDenoiseNet，1.96 M 参数**（student） | 参数量级参照 |
| 蒸馏 | α=0.9 高比例 KD：**21.2× 参数压缩，PSNR 差距 1.63 dB → 0.05 dB**（恢复 teacher 99.8%） | **解决本项目"数据不足无法训大模型"的现成路径** |
| 硬件 | **MediaTek Dimensity 9500**（= 本案 X300 同款 SoC）+ Snapdragon 8 Elite | **直接可对标** |
| 耗时 | 官方 Full HD（1088×1920）协议下 **MTK 34.0 ms** / 高通 46.1 ms；全分辨率 2432×3200 出 37.58 dB | 12MP 量级的真实锚点 |
| **NPU 设计律** | 只用 **标准 3×3 conv + ReLU + nearest-neighbor 上采样**；优先 NPU-native 原语 | **算子兼容性 > 理论 FLOPs** |
| **"Inference Inversion"** | 严格遵循 NPU 兼容算子时，**NPU 比集成 GPU 快 3.88×** | 直接反驳"GPU 一定最快"；与 §3.2 对照 |
| 分辨率策略 | 渐进上下文扩展，训练裁到 **1024×1024** | 显存/激活预算的工程解法 |

**同赛道其他一手条目（供交叉参照）**：
- `arXiv:2604.20291`（MAI 2026）：**INT8 TFLite 固定 shape 部署图**，QAT + teacher-guided；29.79 dB/0.8634 SSIM。证明"**INT8 + 固定 shape**"是竞赛级交付口径。
- `arXiv:2211.05910`（2022）：INT8 SISR on Synaptics VS680 NPU，Full HD 达 **60 FPS** ⇒ 小模型 + 专用 NPU 的吞吐可以很高。
- `arXiv:2105.10288`：为 Synaptics Dolphin NPU 做**量化鲁棒**设计（末层 Clipped ReLU），uint8 下 30× 参数少于 VDSR 仍超越之 ⇒ **"为量化而设计"比"训练后量化"有效**。
- `arXiv:2208.14600`：MTK **Dimensity 9000** 上 **0.09 W @ 30FPS**（Mobile AI 2022 视频超分冠军）⇒ 功耗可做到个位数百 mW 量级。
- `arXiv:2603.29535`（Mobile AI Workshop CVPR 2026）：**生成式**在 edge 的部署形态——把 **LoRA 权重当运行时输入**而非编译进图，共享一个底座模型多任务切换，**内存 −6×、延迟 −4×**。（任务为图像编辑/物体移除，非成像链；是 §2 的一条 `[A]` 旁证。）

### 1.4 vivo X300 真机取证与公开形态的交叉验证

**取证对象**：`/tmp/x300-samples/vivo/vivoapp-libs/`（85 个 native `.so`，从 vivo 相机 APK 静态提取）。全部结论为 **`[A]` 二进制原文级**。

#### (1) `libvdnn.so` = vivo 自研统一推理运行时（VDNN），非某个单一模型

`nm -D` 导出的 C++ 符号直接暴露了一个商业级推理引擎的完整 API 面（节选，来自 `nm -D --defined-only libvdnn.so`）：

| 符号（节选） | 说明（本机判读） |
|---|---|
| `createEngine` / `createSession` / `releaseSession` / `releaseAllSessions` | 引擎-会话两级生命周期；会话按**字符串名**寻址 ⇒ 模型以**命名图注册**（非单模型硬编码），**同一模型多会话并发** |
| `setNumThreads` / `setPriority(Priority)` / `setPowerMode(PowerMode)` / `setFrequencyHint(u8)` | 线程数 + 优先级 + 功耗模式 + **频率提示** ⇒ 面向热/续航的调度 |
| `setDeadlineTime(i)` / `setPowerOffTime(i)` | **推理截止时间**与掉电时间 ⇒ 实时性是一等公民 |
| `setGpuFloat16(b)` / `setInferenceType(InferenceType)` | GPU fp16 开关 + 推理精度/类型可切换 |
| `setTuningGpuKernels(b)` / `setTunedModelSavePath(path)` | **GPU kernel 自动调优 + 调优结果落盘复用**（OpenCL autotune） |
| `setMemoryPoolBuffer(MemoryPoolType, void*, i, i)` / `setRuntimeSharedBuffer` / `setImportBufferForever` | 显式**内存池 + 跨进程/跨模块共享缓冲 + 长驻导入** ⇒ 零拷贝意图 |
| `setFencedInferenceMode(b)` | **fence 同步的异步推理** ⇒ 与 GPU 渲染管线重叠的关键能力 |
| `OpenclProfile` / `CpuInt8Profile` / `CpuUint8Profile`（各含 `recordBegin/Before/After/End`） | **三套独立 profiler** ⇒ 量产中**真实使用**的三条执行路径：OpenCL GPU、CPU int8、CPU uint8 |
| `cpuinfo_parse`（外部链接） | 绑定 `cpuinfo` 做 CPU 特性探测 |

**并且**：`libvdnn.so` 内嵌**可读的 OpenCL kernel 源码字符串**（`__read_only image2d_t input`、`__private const int4 input_shape // (inn, inh, inc_4, inw)`、`__global const float* input`、`__global const uchar* input` 等）⇒ **GPU 推理走 OpenCL，卷积按 `image2d` + 通道 4 对齐打包**，与 `setGpuFloat16` 的 fp16 路径对应。

**共享面（"统一运行时"最硬的证据）**：统计各库对 `vdnn|libxnn|nti_|aimatte|ainr|neuropilot|apuware` 的字符串引用数，`libvdnn.so` 自身 354，其后依次为 `libvglfilter.so` **68**（滤镜链）、`libnti_cv.so` **48**、`libdistortcorrect.so` **30**（畸变校正）、`libaimatte_jni_v1/2.so` **25**（抠图）、`libvivo_cv.so` **23**，其余 14 个库各 1–8 ⇒ **一个运行时被 20 个成像/编辑模块复用**，且穿透到畸变校正与前后处理（`libImageProcessPre/Post.so`）这类"ISP 级"环节。这**不是**"某个功能塞了个模型"，而是**平台级基础设施**。

#### (2) `libnti_picopo.so` / `libnti_rportrait.so` = 专用量化小模型 + 多后端探测

两库（5.9 MB / 13.4 MB）暴露的实现路径：

- **模型在代码里按算子逐层构建**：`NnLibModel::AddConv/AddConvQ8/AddDepthWiseConv(Q8)/AddFc(Q8)/AddAdd(Q8)/AddAvgPool(Q8)/AddConcat(Q8)/AddDepth2Space/AddDequantize`，源路径 `impl_nnlib/nnlib_model.cpp`。Q8 算子显式带 `int32_t` 量化参数（per-tensor scale/zero-point）⇒ **int8 是默认路径而非可选项**。
- **CPU 侧用 gemmlowp + NEON**：`impl_gemmlowp/lowp_{emul,esum,init,power}.cpp`、`libcttm_runtime/gemmlowp/internal/kernel_neon.h`、大量 `gemmlowp::GemmWithPackedRhsTask<...>` int8 packed-RHS GEMM 实例化（`CellFormat<4,2>` / `CellFormat<4,16>`）⇒ **TFLite 的量化 GEMM 内核 gemmlowp 被内嵌**。
- **后端按平台能力运行时探测**：`Enter nthread = %d, enable_hvx = %d, enable_gpu = %d`、`These is only on GPU Version cl_f16 / cl_f32 / cl_u8` ⇒ 一次编译、三种精度/后端。
- **跨 SoC 可移植中间件**：`NnLibWrapper::loadFunction` + `hexagon_nn_tensordef/-perfinfo/-padding_type` 是高通 **Hexagon NN (nnlib) 的 dlopen 路径**；它出现在**天玑 9500** 上 ⇒ 可移植层，Hexagon 分支在 MTK 休眠。
- **任务类型均为窄任务**：`num_anchors_[featuremap_index] * 4 == input[...]->count()` ⇒ **带 anchor 的检测头**；`rule_segment_impl.h` ⇒ **分割**；`FACE_ORIENTATION_FACEAREA_SMALL` ⇒ 人脸朝向/面积分档。
- 系统侧 `libaiawb_moon.so` + **独立的 `libaiawb_moon_model.so`** ⇒ **模型权重与运行时代码分离打包**（可单独 OTA/调优）。

#### (3) 系统侧（仅库名可得；`/vendor/lib64`、`/odm/lib64` 未 root 不可读）

`libainr.so`(AI 降噪)、`libaiawb_moon{,_model}.so`(AI AWB + 独立 model 库)、`libaiseg_model.so`、`libaifd_model.so`、`libaidepth_tuning.so`、`libarmnn{,_ndk.mtk.vndk}.so`(Arm NN)、`APUWare{Aiste,ApusysAidlServer,UtilsAidlServer}.so`(MTK APU)、`libcamalgo.{mfnr,aics,vaics,hdr10p,ais2.bss}.so`、`libHdr2Sdr.so`。

**判读**：`libcamalgo.mfnr`（传统多帧降噪）/`aics`/`vaics`/`hdr10p`/`ais2.bss` **是传统算法库**，与 `libainr`（AI 降噪）**并存** —— "传统主链 + AI 旁路"在**库布局层面**的直接体现：**AI 版本与传统版本同时存在，而非 AI 取代传统**。

#### (4) 形态结论

真机证据支持并在细节上**强于**起点判据：

> **传统 3A/ISP 主链 + 并行专用 AI 小模型 + 统一多后端推理运行时（NEON-int8 / OpenCL-fp16 / HVX / APU）+ 模型资产独立打包。**

两点对本案有直接指导意义的补充：
- **异步 + fence + 内存池 + autotune** 是量产运行时标配而非"高级选项"（`setFencedInferenceMode` / `setMemoryPoolBuffer` / `setTuningGpuKernels`）。
- **量化（int8）是默认路径**，fp16/fp32 是备选 ⇒ 直接决定模型的精度预算与训练策略。
- 真机侧的形态与 `13-学习型影调与自适应色彩映射.md` 取证到的 Google HDRNet PGTM（§4.1.1）**相互独立且互相支持**：两者都指向"**专用小模型 + 确定性主链**"，差别只在 vivo 把网络嵌在管线内、Google 把结果烘焙成表。

## 2. 生成式（diffusion / one-step）用于成片渲染

### 2.1 2024–2026 可复现实现与产品盘点

**结论：扩散用于底层视觉（超分/去噪/去模糊）已是 2024–2026 的主流前沿，代码与权重普遍开源，可复现性高；但**没有任何一条落在"手机成像链"上**——全部是桌面/数据中心 GPU 的离线或近实时方案。`[A]` 论文原文**

已核实的代表性工作（均为 `[A]`，含开源仓库）：

| 工作 | 出处 | 关键数据 / 定位 |
|---|---|---|
| **HYPIR** | [2507.20590](https://arxiv.org/abs/2507.20590) | 扩散权重初始化 + **对抗微调**：不用 diffusion loss、不用迭代采样、不用 adapter ⇒ **单次前向**；保留"可调纹理丰富度 + 文本引导"控制力 |
| **AdcSR** | [2411.13383](https://arxiv.org/abs/2411.13383)（CVPR 2025） | 把 one-step 扩散蒸馏成 diffusion-GAN 混合：**推理时间 −73%、计算 −78%、参数 −74%**，相对前代 one-step **9.3×** |
| **GenDR-Pix** | [2602.10630](https://arxiv.org/abs/2602.10630)（ICLR 2026） | **去掉 VAE**（pixelshuffle 反演回像素空间）：**2.8× 加速、−60% 内存**，4K 图 **1 秒 / 6 GB**（数据中心 GPU 口径） |
| **RDIM** | [2609.33020](https://arxiv.org/abs/2609.33020) | 残差扩散隐式模型：**单步**重建 + **可在确定性↔随机采样间插值的方差控制** |
| **SwiftVR** | [2606.09516](https://arxiv.org/abs/2606.09516) | **H100：31 FPS@2560×1440；消费级 RTX 5090：26 FPS@1920×1080** —— 自述为"首个在消费级 GPU 上做到实时 1080p 流式生成的复原模型" |
| FlashVSR / FastVR / DOVE | [2510.12747](https://arxiv.org/abs/2510.12747) / [2609.36757](https://arxiv.org/abs/2609.36757) / [2505.16239](https://arxiv.org/abs/2505.16239)（NeurIPS 2025） | one-step 流式视频复原：A100 17 FPS@768×1408；H20 11 FPS@1080p；DOVE **28×** 加速 |

**产品侧**：本轮**未取证**——没有找到任何手机厂商公开宣称在**成片主链**上用扩散/生成式渲染的一手来源。已取证的生成式 on-device 形态是**编辑类**（物体移除、提示引导变换）而非成像链：`arXiv:2603.29535`（Mobile AI Workshop CVPR 2026）把 **LoRA 权重作为运行时输入**共享单一底座模型，实现 **−6× 内存 / −4× 延迟**的多任务 GenAI 推理。**这暗示生成式在手机上的落脚点大概率是"相册编辑"而非"快门直出"。**

### 2.2 幻觉细节对"相机直出"的致命性评估

**致命，且这不是本文的推测——是该领域 2024–2026 公认的核心矛盾，学术界正在专门治理它。**

**(a) 幻觉是被独立命名、独立量化的现象 `[A]`**：

- `arXiv:2512.03345`（**HalluGen**，2025-12）定义最直白："Generative models are prone to hallucinations: **plausible but incorrect structures absent in the ground truth**"，并明确指出这在 **safety-critical 领域**（医疗、工业检测、遥感）"undermine reliability and trust"。**量化代价：分割 IoU 从 0.86 掉到 0.36**。该文建立了首个大规模幻觉数据集（4,350 张标注图）与无参考幻觉检测器。
- `arXiv:2410.05601`（**ReFIR**，NeurIPS 2024）："existing LRMs often suffer from **the hallucination dilemma**, i.e., producing incorrect contents or textures when dealing with severe degradations, due to their **heavy reliance on limited internal knowledge**"。
- `arXiv:2506.09993` / `2512.08922`（文本区域）：扩散复原"frequently generate **plausible but incorrect text-like patterns**" ⇒ **文字会被"编"成看起来对的假字**。
- `arXiv:2608.09373`（ACM MM 2026）：提出 **content drift（内容漂移）**，表现为"visual detail degradation and **textual semantic shift**"。

**(b) 为什么对"相机直出"比对"修图工具"严重得多 —— 三条本文的推论 `[D]`（基于上述 `[A]` 事实）**：

1. **验收标准不可协商**。修图工具的价值是"把废片救回来"，用户接受"AI 补的"；**相机直出的价值恰恰是"这是真实发生过的"**。胶片相机的用户预期是"颗粒/光晕/影调是我的风格化"，而不是"细节是模型编的"。
2. **失效是长尾且不可预知的**。`2602.01864`（Ada-RefSR, ICLR 2026）的动机段指出真实退化使对应关系不可靠，必须"Trust but Verify"地自适应门控参考信息——**连学术界都只能做"启发式抑制"，做不到"保证无幻觉"**。相机面对的场景分布是无界的（任何纹理、任何文字、任何结构），长尾失效无法穷举测试。
3. **本项目已有同类实证**。NN 降噪集成后出现"**斑块状伪影**"、文字梯度 −24%（`.zcode/memory/facts.md` 2026-10-02）——那是**判别式**网络都产生的结构损伤；生成式网络的损伤只会更强（它被显式训练去"合成合理内容"）。

**(c) 本领域给出的缓解手段（可作为"若坚持要做"的前置条件）`[A]`**：忠实性优先的结构保真分支（`2505.00687` GuideSR 的 Guidance Branch 直接保留原分辨率退化输入的结构）、检索增强外部真值（`2410.05601` ReFIR，training-free）、显式方差/现实主义控制（`2609.33020` RDIM、`2509.10122` RCOD、`2604.24136` IDaS-SR）、参考相关性门控（`2602.01864`）、幻觉检测器（`2512.03345` SHAFE）。

### 2.3 确定性、可控性、速度、显存

| 维度 | 一手结论 | 来源 |
|---|---|---|
| **确定性** | **默认不确定**（"the randomness characteristics of diffusion models"，随机种子变化即输出变化）。可做到确定：一致性/单步映射"**noise-invariant**"，但代价是"**lack inherent variance modeling and can degrade under severe corruption**"。最优解是**可调插值**（RDIM 的可控方差、RCOD/IDaS-SR 的单标量 fidelity↔realism 滑杆） | [2406.16477](https://arxiv.org/abs/2406.16477)、[2609.33020](https://arxiv.org/abs/2609.33020)、[2509.10122](https://arxiv.org/abs/2509.10122)、[2604.24136](https://arxiv.org/abs/2604.24136) |
| **可控性** | one-step 方法的固有短板："**lack flexible control mechanisms to adaptively prioritize these competing objectives**"（多步方法可靠调采样步数实现）；补救手段是训练时分组/蒸馏对齐（RCOD）。**注意：这些"控制"都是全局 1 个标量，与本项目 98 槽语义参数不可同日而语** | [2509.10122](https://arxiv.org/abs/2509.10122) |
| **保真代价** | 显式存在且被反复承认："PnP + 扩散 **improved perceptual quality while sacrificing the data fidelity**"；单超参调和 distortion-perception | [2503.01288](https://arxiv.org/abs/2503.01288)（CVPR 2025） |
| **速度** | 最强实时成绩在 **RTX 5090 / H100 级**：1080p 26–31 FPS。**手机侧零一手数据** | [2606.09516](https://arxiv.org/abs/2606.09516)、[2609.36757](https://arxiv.org/abs/2609.36757) |
| **显存** | 4K 图 **6 GB**（已是极致优化后）；**VAE 是延迟与内存的双重瓶颈**，去掉它是主要优化方向；退化输入分辨率是内存边界，超限只能 tile-by-tile | [2602.10630](https://arxiv.org/abs/2602.10630) |

**§2 小结（形态判断）**：生成式成片渲染在手机上的**障碍不是工程量，是约两个数量级的算力/带宽差距**——最强方案在消费级桌面 GPU（RTX 5090）上刚做到 1080p 实时，而手机要处理 12MP 且预热/功耗受限。**叠加 §2.2 的幻觉致命性与 §2.3 的控制粒度缺陷，本文判定：不做。** 但保留一个**非成像链**的观察：`2603.29535` 的 LoRA-as-runtime-input 形态，是"日后做相册编辑 AI 功能"的正确部署姿势（与本报告的建议不冲突，因为它不碰快门直出）。

## 3. 移动端推理部署现实（硬约束）

### 3.1 运行时矩阵（可拿到 vs 纸面能力）

**核心判据：`runtime 在机器上存在` ≠ `这个路线是活的`。真机里同时装着已停维护的 Arm NN，这就是最佳反例。**

| 运行时 | 状态 | 证据与判读 |
|---|---|---|
| **MTK NeuroPilot / APU** | **官方存在，第三方可得性未说明** | `[B]` 官方页：NPU = **Deep Learning Accelerators (Performance Cores) + Visual Processing Units (Flexible Cores) + 硬件多核调度器 + NeuroPilot SDK**；宣称"**write once, apply everywhere**"、含 **compiler + profiler + application libraries**；**"NeuroPilot SDK extends the Android NNAPI … bring their code closer-to-metal"**。官方列出的框架是 **TF / TF Lite / Caffe / Caffe2 / MXNet / NNabla**（**不含 PyTorch 直出、不含 ONNX Runtime**）。"extends NNAPI"与"closer-to-metal"的措辞**强烈暗示这是需要厂商关系的 SDK 而非公开下载物**——但**官方页未明说，标注为待确认** |
| **Arm NN** | **❌ 官方已停止维护** | `[A]` 官方 README 原文：**"Arm NN is now a legacy project and is no longer actively maintained by Arm. … no further updates, including security patches … should be expected"**。**但天玑 9500 真机系统侧确实存在 `libarmnn{,_ndk.mtk.vndk}.so`** ⇒ 系统里有它，纯属历史遗留/VNDK 打包 |
| **ncnn** | **✅ 活跃，且与本项目最契合** | `[A]` 官方 README（release 日期 **2026-05-26**）：**无第三方运行时依赖**、纯 C++（C API + Python）；CPU 侧 **ARM NEON + 多核调度**，GPU 侧 **Vulkan**；**fp16 存储/计算 + int8 量化 + 自定义层**；**显式 blob/workspace 分配器**（低内存足迹）；**模型 `.param`/`.bin` 直接内存引用加载**（零拷贝）；Android 预编译含 `android-vulkan` 包；社区已有 `zimage-ncnn-vulkan` 等**跑图像生成**的项目 |
| **OpenCL（GPU 推理）** | **✅ vivo 量产即在用** | `[A]` 真机 `libvdnn.so` **内嵌可读 OpenCL kernel 源码**（`__read_only image2d_t`、`int4 input_shape // (inn, inh, inc_4, inw)`），配套 `setGpuFloat16` + **kernel autotune（`setTuningGpuKernels` / `setTunedModelSavePath`）** |
| **TFLite / XNNPACK** | **✅ 事实标准** | `[A]` 竞赛侧：`2211.03885`（TFLite + Snapdragon GPU）、`2604.20291`（**INT8 TFLite 固定 shape 部署图**）。真机侧：`libxnn.so` 的 strings 含 **TFLite API**（`tflite_Tensor_type`、`GetInput float`）⇒ 它是 **TFLite 系封装**而非裸 XNNPACK；`libnti_*.so` 内嵌 **gemmlowp**（TFLite 的量化 GEMM 内核）+ NEON kernel |
| **NNAPI** | **未取证（本轮）** | MTK 官方称 NeuroPilot "extends" NNAPI（`[B]`）；Google 侧的弃用/迁移状态本轮抓取失败，**标"未取证"，不作为结论依据** |

**对本案的可执行结论**：**唯一"现在就能拿到、活跃维护、且与本项目 C++20 引擎同构"的是 ncnn（Vulkan/NEON，零拷贝，int8+fp16）**；OpenCL 路线在 vivo 量产中得到验证，但 Mali 上 OpenCL 驱动质量与 autotune 成本需自测；**MTK APU 属于"要么拿到 NeuroPilot、要么用不上"的二值状态，不应作为方案前提**。

### 3.2 Mali-G 上的 GPU compute（OpenCL / Vulkan）

- **量产现实（`[A]` 真机）**：vivo 的选择是 **OpenCL + `image2d` + 通道 4 对齐打包 + fp16**，并且**必须做 kernel autotune 且把调优结果落盘复用**（否则每次冷启都要重调）。这说明 Mali 上跑推理**可行但需要一项专门的调优基础设施**。
- **ncnn 的选择是 Vulkan 而非 OpenCL**（`[A]` README）。本项目引擎已用 OpenGL ES；**GLES 3.1+ 的 compute shader 与 Vulkan compute 是两条不同的路**，选型时要明确（GLES compute 在 Mali 上同样可用，但 ncnn/VDNN 均未走此路，属**本文未取证区域**）。
- **"GPU 一定最快"是错的，且有两个方向的实证**：
  - `[A]` `2605.03680` 的 **"Inference Inversion"**：严格遵循 NPU 兼容算子时，**NPU 比集成 GPU 快 3.88×**。
  - `[A]` `2505.06461`：iPhone 15 Pro 上 llama.cpp **CPU-only（2 线程 F16）17 tok/s > GPU 12.8 tok/s**，归因于 **GPU 内存传输开销**（注意：该负载是 memory-bound 的 LLM，与 compute-bound 的去噪不可直接类比）。
  - `[A]` 本项目实测：GPU **26 ms/tile = 7× CPU**、12MP **0.51 s** ⇒ **在本案的负载形状下 GPU 是赢的**。

### 3.3 实测数据量级（模型体积 / 内存 / 12MP 单张耗时）

**外部锚点（`[A]`，可比性最好的一条在 §1.3）**：

| 量级 | 数据 | 来源 |
|---|---|---|
| ≤2 M 参数 | 竞赛级去噪模型 **1.96 M**（12MP 全分辨率 37.58 dB）；全学习模块化 ISP **0.5–3.9 M**（整条管线） | [2605.03680](https://arxiv.org/abs/2605.03680)、[2512.08564](https://arxiv.org/abs/2512.08564) |
| 34 ms | 天玑 9500（**本案同款 SoC**）Full HD 1088×1920 去噪（官方协议） | [2605.03680](https://arxiv.org/abs/2605.03680) |
| 20–100 ms | Full HD 级 ISP/复原在移动 NPU/GPU 上的竞赛区间（2019–2022 各届） | [2105.07809](https://arxiv.org/abs/2105.07809)、[2211.03885](https://arxiv.org/abs/2211.03885) |
| 0.09 W @30FPS | MTK Dimensity 9000 上极小超分网络的功耗 | [2208.14600](https://arxiv.org/abs/2208.14600) |

**本项目已有的一手实测（最可信的一档，`[A]`）**：

- **NN 降噪 spike**：真机 GPU **26 ms/tile**（7× CPU）；整张 12MP **0.51 s**，**零温升**；纹理保留率 78–86%，**优于** P0 传统路径。⇒ **性能与"保纹理"两项都不是拦路虎。**
- 集成五轮逐层修口径（JNI 桥 / KSigma / `cvt_k×256` / 通道序 / neff 折减）后：**`nnGain` 与公式逐位吻合，但观感零改善**（文字梯度 −24%、斑块状伪影）。
- 四次微调（监督 ×3 + N2N）在留出集上**全过拟合**：本地 **4 个场景** vs 官方 **3 万+**。⇒ **真正的瓶颈是数据与对齐，不是算力。**
- 连拍/显存参照：独立线程峰值 **+455 MB**、常驻 **+1.96 GB**（空闲释放回 ~400 MB）；热机单张 totalMs **2091–3336 ms**。
- 落点：`nn/NnInferEngine.kt` + `kFsNnPack/Unpack`、`kNnAnchorS=1.0093e-3`，prefs `nn_dn`/`nn_ab` **默认关**。
来源：`.zcode/memory/facts.md`（2026-10-02 / 2026-10-01 条目）。

**关键推论 `[D]`**：把"1.96 M 参数 / 天玑 9500 / 34 ms"与本项目"12MP / 0.51 s"并置，可看出**模型侧已无悬念，瓶颈全在数据与对齐**——这与 §4.4 的失败模式清单完全一致。

## 4. 对本案的直接建议

### 4.1 形态选择：② 管线指定环节挂专用小模型——**且优先"参数预测式"而非"像素输出式"**

**决策：不选 ①（端到端替代），不选 ③（完全不用），选 ②；在 ② 内部按下面四路线分类再选子形态。**

#### 四路线分类（本报告的核心分类，取代笼统的"混合式 vs 端到端"）

| 路线 | 网络输出 | 成片路径 | 一手代表 |
|---|---|---|---|
| **① 端到端像素生成** | 全分辨率 RGB 像素 | 网络即整条管线 | PyNET [2002.05509]；Mobile AI ISP 挑战赛 [2105.07809][2211.03885] |
| **② 参数/gain 预测（烘焙式）** | **低维映射参数**（gain map / bilateral grid / 全局增益），**不是像素** | **确定性查表/算子** | **HDRNet PGTM（§4.1.1）**；GenISP [2205.03688]；CRISPnet [2203.10562] |
| **③ 环节内像素复原** | 该环节处理后的像素 | 网络嵌在管线某一步 | MAI 2026 去噪 [2605.03680]；vivo `libnti_*`（真机） |
| **④ 专用分析（不产像素）** | 语义（分割/检测/深度） | 输出供其他模块当参数用 | vivo `libaiseg_model` / `libaifd_model` / `libnti_picopo`（真机） |

**①被排除的决定性理由**（非工程量）：端到端网络的参数控制面是**不可寻址的权重**，与本项目 **98 槽参数化 + 预设可存可分享 + 编辑器可回溯**的架构根本冲突（§1.2 障碍 3）。学术界自己的修正方向（Modular Neural ISP [2512.08564] 强调模块化与中间阶段可控）也指向同一结论。

**③被降级的理由**：③输出全分辨率像素，其失效是**逐像素级、不可预知、不可钳位**的——这正是本项目 NN 降噪判死的形态（§3.3）。③不是不能用，而是**必须先满足 §4.3 的全部前置条件**才可启动。

**②被提升为首选的理由**：见下。

#### 4.1.1 ② 的硬实证：Google HDRNet PGTM 的真实落地

并行卡（`13-学习型影调与自适应色彩映射.md`）在 **MGC（= modded Google Camera / 魔改 GCam，**非**某商业相机引擎）** 开源 fork `bjzhou/PhotonCamera` 中取证到 Google 自家 HDRNet 的**完整真实用法**（文件级证据）：

| 环节 | 事实 |
|---|---|
| 网络输入 | **`[1,192,256,4]`** float32——**低分辨率 192×256**；第 4 通道是**引导图**（`min(short BT601 luma × hdrRatio, 12)`）；shape 由运行时 `check()` 硬校验 |
| 网络输出 | **`[1,12,16,8,1,2]`** = **bilateral grid 系数**（12×16 空间格 × 8 深度 bin × 2 仿射 = **3072 floats**）——**输出是 gain，不是 RGB**；钳到 **`[0.03, 30]`** |
| 上采样 | 引导上采样由 JNI 展开（`dng_hdrnet_pgtm_jni.cpp`，923 行）；引导曲线是**学出来的 16 项分段线性亮度曲线**（来自 `guide_coeffs.pb`），不是手工双线性 |
| 推理位置 | **CPU / XNNPACK，4 线程**（`setUseXNNPACK(true)`，`Interpreter` **未挂任何 delegate**）——**不是 GPU，不是 NNAPI** |
| 推理频率 | **不在热路径**：在生成/刷新 DNG 表时执行；曝光匹配阶段对候选 EV（−4..4）**逐一推理**，属**导入期一次性的有界搜索** |
| **最终产物** | **烘焙成 DNG `ProfileGainTableMap2`**：空间格 **64×48** × 强度节点 `max(257, ceil(M·257)+1)` ⇒ **成片路径上零 NN，只有查表** |
| 附带收益 | HDRNet 与 Dehaze **串联且共同烘焙进同一张 PGTM** ⇒ 原本"单独的 PGTM + 单独的 dehaze pass"**合并为一次表查询** |
| 模型体积 | `hdrnet_coefficients.tflite` = **1.58 MiB** |
| **耗时** | **未取证**（13 号卡明确标注：仓库内无 HDRNet 专属耗时日志；据模型体积与输入尺寸**推断**十毫秒级，**不作为结论**） |
| **许可** | 权重为**逆向提取的 Google 专有资产**（无开源许可，**本项目不应复制**）；可借鉴的是**结构与接口设计**；本项目若要只能**自训练** |

#### 4.1.2 「是否也是其它厂商/竞赛方案的共同做法？」——是，且分布有明确规律

**是的，但按任务类型分野**（下列除标注外均为 `[A]` 论文原文）：

**参数预测式（②）出现的场合 = 全局或半全局的影调/色彩/增益任务**：

- **GenISP** [arXiv:2205.03688](https://arxiv.org/abs/2205.03688)：原文"**two image-to-parameter modules that take down-sized image as input and regress global color correction parameters**" ⇒ 教科书式的参数预测式。
- **CRISPnet** [arXiv:2203.10562](https://arxiv.org/abs/2203.10562)：用 **image metadata + 基于图像分类的全局语义**来决定处理 ⇒ 场景/参数预测式。
- **Modular Neural ISP** [arXiv:2512.08564](https://arxiv.org/abs/2512.08564)：全学习但**模块化 + 中间阶段完全可控** ⇒ 朝"可控参数"收敛。
- **vivo AI AWB**（`libaiawb_moon{,_model}.so`）：白平衡的输出天然是**全局增益**而非像素；且**模型权重独立打包成 `_model` 库** ⇒ 与"参数/资产分离"同构。**此项为 `[D]` 推断（本轮仅有库名，无行为取证）**。

**像素输出式（③）出现的场合 = 逐像素高频复原任务**：

- MAI 2026 去噪 [2605.03680]、Mobile AI ISP 挑战赛 [2105.07809]、vivo `libnti_*`（真机）。

**分野的根本原因是信息论约束，不是工程偏好 `[D]`（基于上述 `[A]` 事实的推论）**：

> **增益/影调是低频、低维、有界的**（3072 个系数、钳到 `[0.03, 30]`），192×256 的输入**足以**预测它；
> **噪声与细节是高频的**，192×256 的输入在信息论上**不足以**恢复 12MP 的高频内容——必须让网络看全分辨率。

**这条推论给出了本案可直接使用的工程判据（本报告最有价值的产出）：**

> **低频可参数化的环节 → 用 ② 参数预测式（低分辨率推理 + 全分辨率确定性查表）**
> **高频逐像素的环节 → 只能用 ③ 像素输出式（或维持传统算法）**

#### 4.1.3 ② 对「实时性 / 确定性 / 可复现性」三个工程约束的意义

| 约束 | ② 参数预测式（如 HDRNet PGTM） | ① 端到端 / ③ 像素输出式 |
|---|---|---|
| **实时性** | 推理成本**与图像分辨率解耦**：输入 192×256（约 49 k 像素）vs 12MP（约 1.2×10⁷ 像素）⇒ **像素数差约 244×**；且**不是每帧**、可只在成片前跑一次有界搜索。**本条为结构性事实**（`[A]` 输入 shape）；**绝对耗时未取证**。进一步：**Google 明确选了 CPU 而非 NPU/GPU** ⇒ 在该张量尺寸下**卸载/调度开销可能已超过计算本身**——与 §3.2 的 "Inference Inversion"（大张量上 NPU 快 3.88×）**方向相反、并不矛盾**：**小张量 CPU 优、大张量 NPU 优**，这是一个可实测的选型判据 | 需在 12MP 上跑多步/大网络：Full HD 20–100 ms ⇒ 外推 12MP **数百 ms 量级** |
| **确定性** | **成片路径零 NN**。只要 NN 本身确定（CPU FP32 XNNPACK，**无 GPU 浮点非确定性**），整条链**可逐位复现**；且输出被**钳位到有界区间**，神经网络失效被限制在**低维、可检查**的空间里 | 全分辨率像素级输出，含 GPU fp16 累加与随机采样 ⇒ 重跑不一定逐位一致 |
| **可复现性 / 可调试性** | 产物是**可 dump、可比较、可手改、可入库**的表（3072 floats，或 64×48×257 的 PGTM）⇒ **与本项目 98 槽参数 + 预设可存可分享的架构天然同构**，甚至可直接把烘焙表当作一种**预设资产** | 产物是不可寻址的权重，调参即重训 |

> **必须如实标注的两点**：(i) 13 号卡明确 **耗时未取证**，本文不据此给性能承诺；(ii) 权重为 **Google 专有资产、不可复制**，本项目只能自训练——**这是一条许可约束，不是工程量问题**。自训练的监督信号来源属 `13-学习型影调与自适应色彩映射.md` 的论证范围，本文不越界。

### 4.2 建议的挂载点排序与实施代价

**排序判据 = §4.1.2 的"低频/高频"判据。** 标注 ★ 者为跨卡边界（方案论证归属另一张卡，本文只给形态定位）。

| 优先级 | 挂载点 | 形态 | 为什么符合判据 | 实施代价（不构成否决理由） |
|---|---|---|---|---|
| **P0** ★ | **局部色调映射 / image-adaptive 影调** | ② 参数预测式 | 低频、低维、有界；已有 Google 量产实证；与 98 槽架构同构 | 训练一套权重（**不能复制**）；新增 1 个 TFLite/XNNPACK 依赖 + 一次低分辨率推理 + 全分辨率查表 pass；**方案论证见 13 号卡** |
| **P0** | **链上其它低频环节**（可按同一模板复制） | ② 参数预测式 | 同样满足低频/低维/有界 | 每个环节一套权重 + 资产；共享同一个推理运行时 ⇒ **边际成本递减** |
| **P1** ★ | CCM / 自动白平衡 | ② 参数预测式 | 输出天然是 3×3 矩阵或 3 个增益 | **属 16 号卡（色彩科学/自动白点）范围**，本文仅确认其形态归属 ② |
| **P2** | **多帧融合后的神经降噪** | ③ 像素输出式 | **高频**，②在信息论上不可行 | 需自建数据集与对齐口径（**本项目历史瓶颈就在此**）；新增运行时 + 显存（参照实测峰值 +455 MB）；**必须先满足 §4.3 全部前置条件** |
| **不做** | 端到端替代整链 | ① | 参数控制面冲突 | — |
| **不做** | 扩散/生成式成片 | — | §2：算力差约两个数量级 + 幻觉致命 + 控制粒度仅 1 个标量 | — |

**可选的非推理增益项**：训练数据侧的合成/域适配。`arXiv:2206.02715`（Samsung Labs）证明用**白天图合成夜间 raw** 训练夜mode ISP，**仅需 5–10% 真实数据**即可接近全真实数据训练的效果；`arXiv:2508.14730`（Samsung Labs）开源了光照/传感器映射数据集与轻量网络，**直接针对"跨传感器泛化"**——这正是本项目"跨域模型不可直接用"痛点的对症文献。**这两项不增加推理期成本，只降训练期数据门槛。**

### 4.3 验证路径（离线 → 真机 A/B → 数值 + 观感双判据）

**本项目的既有惯例保留不变，但必须按历史教训加固：**

**阶段 0 · 对齐口径先于训练（新增，针对最贵的失败模式）**
`arXiv:2609.02831`（BMVC 2026，代码 `github.com/mv-lab/AISP`）的核心结论必须作为前置约束：
> "placement alone does not determine performance … **RGB restoration models trained considering the ISP transformations achieve the best overall performance** … image reconstruction performance **strongly depends on the alignment between the restoration model and the target imaging pipeline**."

⇒ **训练时必须把本引擎的 ISP 变换放进监督回路**（用引擎自身的确定性输出当 teacher/参考），而不是拿公开数据集或别家 ISP 的成片当目标。本项目 NN 降噪判死的根因正是**跨域**（跨域权重 + 4 场景 vs 3 万+）。

**阶段 1 · 离线跑通**
固定权重与输入，验证**逐位可复现**；对 ② 形态额外验证"低分辨率推理 + 全分辨率查表"展开后的 gain 图**无块状/格状伪影**（双边网格上采样的经典失效）。

**阶段 2 · 真机 A/B**
同一 RAW、同一参数、开/关 NN 各出图；记录 totalMs、峰值/常驻显存、温升。**参照现有基线**：12MP NN 0.51 s、零温升、峰值 +455 MB、热机单张 2091–3336 ms。

**阶段 3 · 双判据（缺一不可）**
- **数值判据**：`nnGain` 与公式吻合是**必要不充分条件**——本项目已实测"**逐位吻合但观感零改善**"。
- **观感判据**：必须包含**结构保真度**指标而非仅 PSNR/SSIM/LPIPS——历史失效的可见形态是"**文字梯度 −24%**"与"**斑块状伪影**"。⇒ 建议固定加入**文字/边缘梯度保持率**与**平坦区纹理保留率**两项，作为"观感"的**可量化代理**；并保留人工盲评。
- **量化门禁**（新增）：int8 是工业默认路径（§1.4），而 `arXiv:2105.10288` 的教训是"**为量化而设计** > 训练后量化"。⇒ 验证必须**在 int8 图上做**，不能只在 fp32 图上通过。

### 4.4 失败模式（≥3 条；★ = 本项目已实际踩过）

| # | 失败模式 | 机理 | 对策 |
|---|---|---|---|
| **1 ★** | **数值对齐但观感零改善** | 指标与感知脱钩；逐位吻合只证明算子实现正确，不证明有效 | 结构保真度指标进判据（§4.3 阶段 3）；**数值通过不得单独作为放行依据** |
| **2 ★** | **跨域权重/口径直接复用** | teacher 管线的色彩/噪声/影调空间与本引擎不一致 ⇒ 网络学到错误映射 | 阶段 0 对齐口径；`2609.02831` 的 ISP-aware 监督 |
| **3 ★** | **过拟合到少数场景** | 4 场景 vs 官方 3 万+；留出集全过拟合 | 先用 `2206.02715` / `2508.14730` 的合成与域映射把数据量做上去，**再**谈模型 |
| **4** | **量化后精度塌陷** | int8 是工业默认路径；高动态范围增益（如 PGTM 钳位 `[0.03, 30]`）在量化下易失真 | 为量化而设计（Clipped ReLU 类手段，`2105.10288`）；QAT + 固定 shape 部署图（`2604.20291`）；**验证在 int8 图上做** |
| **5** | **算子不兼容导致整子图回退** | TFLite delegate 遇 unsupported op 会把**整个子图**退回 CPU，性能断崖 | 只用 NPU-native 原语（3×3 conv / ReLU / nearest upsample，`2605.03680`）；**把算子集合当硬约束在设计期锁定** |
| **6** | **双边网格上采样的格状伪影** | ② 形态特有：低分辨率系数展开到全分辨率时空间格边界可见 | 阶段 1 专项检查；必要时加空间平滑/重叠 |
| **7** | **热与功耗调度** | 长时拍摄降频；推理与渲染争抢 GPU | 参照量产运行时的做法（`libvdnn` 的 `setPowerMode`/`setDeadlineTime`/`setFrequencyHint`，见 §1.4）；本项目实测"零温升"是当前有利证据 |

## 5. 未取证 / 待补

如实列出，**不以推断冒充实证**：

1. **HDRNet PGTM 的绝对耗时**——13 号卡与本文均确认仓库内无耗时日志。**"十毫秒级"是未验证推断，不作为可行性依据。**
2. **MTK NeuroPilot 对第三方 App 的开放程度**——官方页只写"extends Android NNAPI / closer-to-metal"，**未说明获取途径**。这是本案"能否用 APU"的二值前提，需向 MTK 或其代理确认。
3. **Android NNAPI 的当前弃用/迁移状态**——本轮抓取失败（返回本地化空页）。不作为结论依据。
4. **Mali-G 上 GLES 3.1 compute shader 跑推理的实证**——vivo VDNN 走 OpenCL、ncnn 走 Vulkan，**两者都不走 GLES compute**；本案引擎若想复用 GLES 上下文，该路径**无一手先例**。
5. **手机端扩散式成片渲染的产品级实现**——本轮未找到任何厂商公开的一手宣称。
6. **`libnti_picopo` / `libnti_rportrait` 的具体网络结构与任务清单**——本文只做到"按算子构建 + int8 + 多后端探测 + 含检测头/分割"的层面，未做进一步逆向。
7. **vivo AI AWB / AI 降噪的实际输出形态（参数 vs 像素）**——本文 §4.1.2 的相关表述标注为 `[D]` 推断，**无行为取证**。
8. **本项目 98 槽参数与 ② 形态烘焙表的接口设计**——属工程落地设计，本文只指出"二者同构、可对接"，未给具体 schema。
