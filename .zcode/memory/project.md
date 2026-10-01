---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-10-01 13:05
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

## 进行中（2026-10-01：独立线程落地，转帧率定位）

- [2026-10-01] **拍照期间预览帧率** | 最高 | 现状 = 空闲 30~32fps、拍照期间**最低
  13~25fps**；四种调度手段（DFT 分片 / glFlush / 真 sleep / 线程降优先级）实测
  **全部无效且负优化**，已完整回退（引擎 diff 归零）→ 卡顿定性为 **GPU 资源硬约束** |
  下一步 = 用现成开关做**瓶颈定位**（降噪 0 / bilinear / MFNR 关，各拍一张），
  再定"降预览负载 / 优化主因 / 接受" | handoffs/拍照独立线程落地与连拍崩溃定案.memo.md
- [2026-10-01] **渲染链后段 GPU** | 待做 | `passMs` 711~2021ms（RCD + 高光重建 +
  降噪）是合成耗时大头；动它必须先出画质 A/B（用户红线：画质不降）
- [2026-10-01] **单帧 RAW / YUV 静拍仍在预览线程** | 待做 | 独立线程开关目前只覆盖
  MFNR；迁移时 YUV 矩阵也要同步给拍照实例
- [2026-09-30] **DFT 继续挖（冷机 500~550ms）** | 待做（降级）| 先做冷机分段测量
  定位真热点，不凭代码推断
- [2026-09-29] **MGC-AE 曝光决策 / 弱彩差** | 远期·降级 | 采集侧高光保护；色彩主观类

## 已完成（近期）

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
