---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-30 21:50
status: active
---

# 项目状态

## 目标

胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学（追平 Adobe/libraw 标准链），风格（浓淡、胶片味）= 用户提供的理光系 LUT（M4）。

## 结构

- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（预览渲染线程）+ CameraAudit + `camera/StdColorMatrix.kt`（DNG 标准链）
- `core/engine/`：C++20 引擎——render/（FrameRenderer + Align）、gl/（EglCore/Capabilities）、spike/、log/（JSONL+GPU 计时）、jni/Bridge.cpp
- `docs/`：开发计划（§0 需求基线 D1–D13）、x300-能力审计.md、**research/（11 份独立调研：PhotonCamera 管线 / darktable / RawTherapee / Blender AgX / ACES-libplacebo / 相机外观引擎 / filmulator / ISP 标准链与 AE·MFNR / 09=频域降噪专项）**
- `app/src/main/assets/luts/`：**42 个内置 .lut（uint8、33³、4.3MB）**，格式 `[size][titleLen][title][catLen][category][data]`；源为 `~/Downloads/LUT/滤镜.cube/` 的 64³ 导出（`tools/gen-lut-assets.py`）
- `tools/`：validate-shaders.sh、**check-diag-fields.py（出包前必跑）**、**check-log-json.py（分析日志前先跑）**、**align-bench.cpp + align-bench-gen.py（对齐精度离线基准）**、**p0-subpixel-eval.py（P0 采样离线评估）**、ab-100pct.py、chroma-scale-eval.py、texture-fidelity-eval.py、denoise-strength-eval.py、check-fft-tile-cover.py、chroma-denoise-eval.py、tone-scan.py、gen-lut-assets.py/gen-lut-previews.py、dng-color-dump.py、color-metrics.py、ccm-chain-ab.py、check-srgb-oetf.py、check-lsc-mapping.py、lut-audit.py、tuning/

## 进行中（2026-09-30 夜：D-2 收线，转性能收口）

- [2026-09-30] **性能收口** | 最高 | 已完成：DFT 窗表（dftGpu 冷机 850→500~550ms）、
  JPEG 落盘异步（主线程省 0.35s）、**快门提前解锁（间隔 2.5s→0.54s）** |
  下一步 = **拍照独立线程/context 重做**（0.10.83 失败已回退，须带开关）|
  handoffs/对齐精度亚像素落地与快门异步化.memo.md
- [2026-09-30] **渲染链后段 676ms** | 待做 | RCD 335 + 高光重建 245 + 降噪 40；
  GPU pass，动它需先出画质 A/B（用户红线：画质不降）
- [2026-09-30] **降噪专项 D** | **已收线** | D-1 色度超 PC 2.3~2.9×；D-2 对齐精度 1/2 域
  + P0 亚像素（**同帧 +43.7%/+46.5%**）；亮度仍差 PC **2.26×**（旧 4.7×）；
  颗粒 = 胶片感（用户拍板，降噪对标暂缓）
- [2026-09-30] **DFT 继续挖（冷机 500~550ms）** | 待做 | 先做冷机分段测量定位真热点，
  不再凭代码推断（第二刀已证明 GPU 上"减少算术量"可能更慢）
- [2026-09-29] **MGC-AE 曝光决策** | 远期 | 采集侧高光保护
- [2026-09-29] **弱彩差/ColorChecker** | 降级（色彩主观）| 剩余嫌疑=模拟 LSC bug、RCD 弱彩

## 已完成（近期）

- [2026-09-30] **对齐精度 + P0 亚像素（v0.10.70–0.10.76）**：最细对齐层 1/4→**1/2 域**
  （HDR+ §4 原版），离线 p50 0.68→0.27px、错位 >1px 的 tile 23%→5%；P0 时域采样改
  逐通道网格亚像素（替代 round 到偶数）→ **同帧实测降噪 +43.7%/+46.5%**（残余噪声
  降到 70%）；两者协同（只换层 +2.5%）。离线否证四方向（层预平滑/λ 罚项/搜索半径/
  L4 步长）→ 暗光是信息问题（SNR<1）
- [2026-09-30] **日志与 DFT 修复（v0.10.73/0.10.77）**：`jnum()` 修 68 处"截断出非法
  JSON"（`"1139."` 会让整行被解析工具静默丢弃）；DFT 窗函数查表（省约 16 亿次 SFU cos）
  → dftGpu 冷机 500~550ms
- [2026-09-30] **快门与保存异步化（v0.10.80–0.10.82）**：JPEG 编码落盘移后台单线程队列
  （主线程 8~21ms 撒手）；`capturing` 从渲染回调提前到采集完成（**快门间隔 0.54s**）；
  在飞上限 2；删"多帧合成中"提示
- [2026-09-30] **PC 对标（同场景同曝光）**：暗部 σY 差 **2.26×**、色度**好 2.3~2.9×**、
  边缘高 11%、纹理保真 0.92；并查明 PC"快门即撒手"= 预览与各后处理器**各自独立
  EGL context**
- [2026-09-30] **D-2 前置（v0.10.53–0.10.69）**：D-1 色度 à trous 多尺度（k=2.5/
  revert=0.20）超 PC；时域×空间联合 P0（96 样本）；TPDF dither；LSC 软 knee 修暗光竖线
- [2026-09-30] **A 专项 DFT 合并收线（v0.10.37–0.10.63）**：按 CFA 平面独立、16×16 半
  重叠 + 升余弦窗、寄存器 FFT；根因=imageStore 目标纹理必须 `glTexStorage2D`；
  compute program 现场创建
- [2026-09-29] **C 专项收线 = 噪声模型全链与多摄标定（v0.10.36-m4，tag 已推）**：
  逐像素 σ²=A·x+B（P0/P4/mismatch/LSC/÷neff/tile RMS）、强度滑杆 0–2、可导入/可切换
  标定（GCam .c）、X300 三摄内置实测标定；修四个真机 bug；同刻三镜头残余噪声
  4.4×/2.2× → **1.22×/1.45×**
- [2026-09-27] **MFNR 阶段一验收（v0.9.0→v0.9.5-m4）**：4 帧 Bayer 域 tile 对齐 + 每通道
  网格亚像素双线性融合；纯融合/裸帧 σ 比 0.442-0.451 ≈ √4 理论
- [2026-09-27] **域映射曝光链定案（v0.7.8–v0.8.2-m4，tag v0.8.2-m4）**：EV = filmic 前
  线性域；删旧 log 域胶片链；预览直通退役、域映射上线（真 WYSIWYG）；基准曝光设置项
- [2026-09-24] **对标 PC 重标定 + 方向链路（v0.6.38–v0.6.44-m4）**：高光去饱和判据换
  max 通道；`orientationStep`=设备物理旋转（**绝不能喂 Display.getRotation()**）；
  画幅裁切跟设备姿态
- [2026-09-23] **局部色调映射与自适应影调收敛（v0.6.24–v0.6.37-m4）**：M4-7 局部色调
  映射；判据五次反复后定稿（中间调锚定）；P4 色度换径；预览流上限 2160；abiFilters 固定 arm64
- [2026-09-22] **调研结论复核 + 色度判据三次修正 / 基础色彩引擎调优 / 独立调研定案**：
  42 个 LUT 资产、Material3 暗色主题、baseTone 正交化、自适应影调、高光软肩；
  核实 PC 无"理光/徕卡引擎"、RawProfile 未接线
- [2026-09-22] **仓库治理**：tag 规则改「每轮对话一个 tag」；`git filter-repo` 移除误提交截图
- [2026-09-21] **M3 画质四件 + 寡淡复核（v0.5.33–v0.5.47）**：P0 LSC、实时直方图、
  DNG 标准色彩链、"寡淡"实为亮度差（libraw auto_bright）
- [2026-09-20/21] **M2 全部完成 + M3-1 RCD（v0.5.20–v0.5.31）**：RAW 端到端 + DngCreator
  底片；RCD 三段 fragment 管线 + 质量档
- [2026-09-18/19] **M1/M2 起步（v0.4.6–v0.5.19）**：UI 专项定稿、采集修复、镜头与专业 UI

## 本轮（09-28 影调迁移 → 09-29 高光线/WB）后现状（摘要）

- [2026-09-28] **v0.10.22→0.10.24**：EV=filmic 前线性域；OETF 真 sRGB 三处同步；
  版本号单一来源；固定曲线 + 原生小波 HR + gamut 五步 + 锐化
- [2026-09-29] **v0.10.25/0.10.26**：base_ev 默认 +0.7 + LSC 边缘软限幅；M 档 WB 断链
  修复（raw 域 gains）；WB 验收通过
- [2026-09-29] **v0.10.31（183）**：白点探针 + white 判据整体 +ev（渲染域对齐），
  治"整张白"；测试方法论三条固化（跨时刻污染 / 判据取值范围 / 同刻 A/B）
