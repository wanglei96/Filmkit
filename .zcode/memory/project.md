---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-10-02 10:05
status: active
---

# 项目状态

## 目标

胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学（追平 Adobe/libraw 标准链），风格（浓淡、胶片味）= 用户提供的理光系 LUT（M4）。

## 结构

- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ **RenderClient（预览渲染线程）+ StillRenderClient（拍照渲染线程，v0.10.85）** + CameraAudit + `camera/StdColorMatrix.kt`（DNG 标准链）
- `core/engine/`：C++20 引擎——render/（FrameRenderer + Align）、gl/（EglCore/Capabilities）、spike/、log/（JSONL+GPU 计时）、jni/Bridge.cpp
- `docs/`：开发计划（§0 需求基线 D1–D13）、x300-能力审计.md、**research/（11 份独立调研：PhotonCamera 管线 / darktable / RawTherapee / Blender AgX / ACES-libplacebo / 相机外观引擎 / filmulator / ISP 标准链与 AE·MFNR / 09=频域降噪专项）**
- `app/src/main/assets/luts/`：**42 个内置 .lut（uint8、33³、4.3MB）**，格式 `[size][titleLen][title][catLen][category][data]`；源为 `~/Downloads/LUT/滤镜.cube/` 的 64³ 导出（`tools/gen-lut-assets.py`）
- `tools/`：validate-shaders.sh、**check-diag-fields.py（出包前必跑）**、**check-log-json.py（分析日志前先跑）**、**still-thread-verify.py + still-thread-run.sh（拍照线程验收：按开关分组出中位）**、align-bench.cpp + align-bench-gen.py、p0-subpixel-eval.py、ab-100pct.py、chroma-scale-eval.py、texture-fidelity-eval.py、denoise-strength-eval.py、check-fft-tile-cover.py、chroma-denoise-eval.py、tone-scan.py、gen-lut-assets.py/gen-lut-previews.py、dng-color-dump.py、color-metrics.py、ccm-chain-ab.py、check-srgb-oetf.py、check-lsc-mapping.py、lut-audit.py、tuning/

## 进行中（2026-10-02 晚：NN 判死，转 HDR+）

- [2026-10-02] **HDR+ bracketing 评估** | 最高 | 1×/4×/16× 曝光阶梯实拍（超越
  FX 的进光杠杆）；hdr-plus-swift 为参考实现。**NN 判死后成为算法层主线**
- [2026-10-02] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、golden image 接 CI
- [2026-10-02] **NN 微调（暂缓）** | 降级 | 管线已建成（PyTorch 移植 / 亚像素对齐 /
  N2N 配对 / 评估全跑通），但需几十个场景数据 —— 4 场景实测全部过拟合。代码与
  开关保留（`nn_dn` 默认关），数据充足可直接捡起
- [2026-10-01] **更强 NN 模型评估** | 降级 | GPU 裕量 5–10×,AP-BSN/CPR <2s
- [2026-10-01] **可选：NN 强度滑杆** | 待定 | `out = α·nn + (1−α)·原图`，让真机
  自己找权衡点（~30 分钟）

## 已完成（近期）

- [2026-10-02] **NN 集成五轮 + 判死（v0.10.102–0.10.106，253→257）**：NN pass 全链接入
  → 逐层修口径（JNI 桥 / KSigma 量纲 / 训练口径 anchor / MFNR neff 折减 / 通道序）
  → **nnGain 全与公式逐位吻合但观感零改善**（文字梯度 -24%、平坦区斑块状伪影），
  不如 P0 → 判定"跨域模型不可直接用" | handoffs/NN集成定因与判死转HDR+.memo.md
- [2026-10-02] **微调管线建成 + 四次微调全失败**：PyTorch 移植（与 onnxruntime 等价
  2e-6）、亚像素对齐（0.0115px）、Noise2Noise 配对、评估与 TFLite 导出；四次微调
  留出集全过拟合（+12.0/+9.9/+11.7/+5.06%），瓶颈 = 数据量（4 场景 vs 官方 3 万+）
  | handoffs/NN集成定因与判死转HDR+.memo.md
- [2026-10-02] **NN 集成三轮（v0.10.102–0.10.104，253→255）**：NN pass 全链接入
  （MFNR+单帧、NnInferEngine 常驻推理线程、失败回退 P0 真机验证零丢照片）→
  官方口径两处误读定案（cvt_k×256 增益、BGGR 通道语义）→ 离线复现恒等 0.997；
  教训：注释常数非公式、去直流指标测不出全局偏移 | handoffs/NN集成口径定案离线恒等.memo.md
- [2026-10-02] **NN 路线可行性验证闭环（v0.10.100–0.10.101）**：三 APP 对比 +
  5 组开关 A/B 中频归因（P0 否证/MFNR 保留）→ PMRID→TFLite FP16 → 真机 GPU
  26ms/tile（7× CPU）、12MP 0.51s 零温升；离线质量对照纹理保留 78–86% 优于
  P0 交换比 | handoffs/NN降噪spike达标与中频归因定案.memo.md

- [2026-10-01] **M4 无损 LUT 导入导出 + 预览降载收线（v0.10.93–0.10.99，tag v0.10.99-m4）**：
  实验判读排除降噪、定位 MFNR 链路 → 方案 A（预览中间链 0.5× + blit 升采，单帧
  RAW/YUV 迁独立线程）；M4 §6.2 .cube 1D/3D + Hald PNG 双向、用户库、分类管理；
  滑杆跳变根因 = M3 Slider 按下 snap，换相对拖动 | handoffs/M4无损LUT导入导出与预览降载收线.memo.md
- [2026-10-01] **拍照独立线程落地 + 连拍崩溃定案（v0.10.85–0.10.92）**：独立线程 +
  独立 EGL context（PC 同构），**只启动不销毁**消除 0.10.83 的竞态；同帧 A/B
  **CRC32 逐字节相同**（画质等价）；连拍闪退定案 = Java 堆 OOM 在 JNI 回传处 pending
  exception → ART abort，`largeHeap` + `ExceptionCheck` 修复后 7 张全成功零丢失；
  空闲 10s 释放 2GB 中间纹理（须连 context 一起销毁）；让路方案实测无效已回退
- [2026-09-30] **对齐精度 + P0 亚像素（v0.10.70–0.10.76）**：最细对齐层 1/4→**1/2 域**
  （HDR+ §4 原版），离线 p50 0.68→0.27px、错位 >1px 的 tile 23%→5%；P0 时域采样改
  逐通道网格亚像素 → **同帧实测降噪 +43.7%/+46.5%**；两者协同（只换层 +2.5%）
- [2026-09-30] **日志与 DFT 修复（v0.10.73/0.10.77）**：`jnum()` 修 68 处"截断出非法
  JSON"；DFT 窗函数查表 → dftGpu 冷机 500~550ms
- [2026-09-30] **快门与保存异步化（v0.10.80–0.10.82）**：JPEG 编码落盘移后台单线程队列
  （主线程 8~21ms 撒手）；`capturing` 提前到采集完成（**快门间隔 0.54s**）；在飞上限 2
- [2026-09-30] **PC 对标（同场景同曝光）**：暗部 σY 差 **2.26×**、色度**好 2.3~2.9×**、
  边缘高 11%、纹理保真 0.92；PC"快门即撒手" = 各后处理器**各自独立 EGL context**
- [2026-09-30] **D-2 与 A 专项（v0.10.37–0.10.69）**：D-1 色度 à trous 多尺度超 PC；
  时域×空间联合 P0（96 样本）；TPDF dither；LSC 软 knee；DFT 合并（CFA 平面独立、
  16×16 半重叠 + 升余弦窗，根因 = imageStore 目标必须 `glTexStorage2D`）
- [2026-09-29] **C 专项收线 = 噪声模型全链与多摄标定（v0.10.36-m4）**：逐像素
  σ²=A·x+B、强度滑杆 0–2、可导入标定（GCam .c）、三摄内置标定；同刻三镜头残余噪声
  4.4×/2.2× → **1.22×/1.45×**
- [2026-09-28/29] **影调与色度主线**：EV = filmic 前线性域、OETF 真 sRGB 三处同步、
  filmic 原生件 + 小波 HR + gamut 五步；base_ev +0.7；M 档 WB 断链修复（raw 域 gains）；
  白点探针治"整张白"
- [2026-09-27] **MFNR 阶段一 + 域映射曝光链（v0.7.8–v0.9.5-m4）**：4 帧 Bayer 域 tile
  对齐 + 每通道网格亚像素双线性融合（σ 比 0.442-0.451 ≈ √4）；预览直通退役（真 WYSIWYG）
- [2026-09-21/24] **M2/M3 收线**：RAW 端到端 + DngCreator 底片、RCD 三段 fragment 管线、
  P0 LSC、实时直方图、DNG 标准色彩链；对标 PC 重标定与方向链路（orientationStep =
  设备物理旋转）
