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

## 进行中（2026-10-03）

- [2026-10-03] **红杯白斑攻坚** | 最高 | 红杯釉面密集白点（显示域 15~56 级、**两帧 IoU=0**）；
  已排除脉冲尖峰/坏点、锐化过冲、色度噪声、真实纹理四类假设，确认是**亮区噪声**（1~2.3σ）。
  曝光判据域错位已修（exp12：判据改用**成片域** `sw999`）+ 默认**曝光给足**。
  **等 exp12 真机复验**：白斑减少 ⇒ 根因是光子不足；没变 ⇒ 查去马赛克在红色高饱和区的色度行为
- [2026-10-03] **超广畸变自校正** | 高 | HAL 路线已全关（exp7 实测 `distModes:none`、
  `lensDistortion` 为 AOSP 占位值、`preCorr==active`）⇒ 必须引擎 GPU 自校正（去马赛克前、
  逐 CFA 平面反向映射保相位）；**需拍板**：完整校正（视场窄 5~10%）/ 部分校正 / 不校正
- [2026-10-03] **固定影调观感验收** | 中 | 影调层已收敛为 darktable 默认 −8/+4 一套参数
  （exp10/11）；亮场 / 暗场 / 光影三场景各一张，确认暗部能沉、高光不闷
- [2026-10-02] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、成像会话摘要、golden image 接 CI
- [2026-10-02] **NN 微调（暂缓）** | 降级 | 管线已建成（PyTorch 移植 / 亚像素对齐 / N2N 配对 /
  评估全跑通），但需几十个场景数据 —— 4 场景实测全部过拟合。代码与开关保留（`nn_dn` 默认关）

## 已完成（近期）

- [2026-10-03] **超广畸变能力探测（v0.10.109-exp7，263）**：AOSP 一手取证（RAW 永不校正 +
  该控制默认 ON）+ 真机实测 `distModes:none` / `lensDistortion` 占位 / `preCorr==active`
  ⇒ **HAL 路线全关，必须 GPU 自校正** | docs/x300-能力审计.md §4
- [2026-10-03] **高光保护 + 横拍裁切修复（v0.10.110-exp8，265）**：曝光目标改"刚好不烧高光"
  （`ExposurePlanner` 第 4 约束，n 可 <1；欠曝量由成片 EV 补偿）真机 `clipB` **890→163**；
  横拍被裁成竖幅（丢 44% 画面）修复 = 方向/画幅**快门瞬间快照** + 按姿态定向
- [2026-10-03] **影调层收敛（v0.10.112/113-exp10/11，267/268）**：4 个互相干扰的白点旋钮
  （自适应影调/高光自适应白点/白点下限边距/白点上探幅度）→ 最终**全部撤掉**，标准模式唯一，
  固定 darktable 出厂默认 **−8/+4 EV**
- [2026-10-03] **曝光判据域修正（v0.10.114-exp12，269）**：判据从预览域 `w999` 改为**成片域**
  `sw999`（实测预览 2.09 vs 成片 2.674，差 0.58EV 且逐次不同）+ 默认**曝光给足**（余量 0.35→0.1）
- [2026-10-02] **曝光决策专项（v0.10.107-exp → exp4，258→261）**：离线定量证明曝光 ×N 的
  σ 收益 = √N（解析值与物理上限逐位吻合、决策边界 d\*≈2.2px）→ ExposurePlanner + 陀螺仪
  motion metering + 曝光上限手动档；真机 250ms 档拿到 **ISO167（= Filmix 同款）**，噪声
  降到基准 1/3.1 | handoffs/曝光决策落地与亮部降噪修正.memo.md
- [2026-10-02] **亮部降噪失效修正（v0.10.108-exp5，262）**：根因 = 强度档被当 σ 乘子 ⇒
  `exp(−0.5d²)` 指数塌陷 ⇒ 降噪近似归零且亮部最惨（盘沿 σY 4.48 vs Filmix 1.72/PC 0.92，
  中间调我们反而最好 1.06）；改为 **σ 恒真值 + 强度<1 走输出 mix**；真机 P90 降 21~29%
  | docs/denoise-strength-sigma.md
- [2026-10-02] **三摄 GCam 噪声标定接入（v0.10.108-exp6，263）**：主摄 HPB（100 张实测）/
  超广 JN1 / 长焦 LYT602 内置为 assets；发现 `BUILT_IN` 原先**无主摄条目**（主摄一直走
  HAL cam2，其 G 通道比 R/B 低一半）；真机 `src:"builtin"` 验证通过 | 同上
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
