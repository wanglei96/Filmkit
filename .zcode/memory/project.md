---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-10-03 16:10
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

## 进行中（2026-10-03 下午）

- [2026-10-03] **品红 = 逆光边缘伪彩（紫边）** | 最高 | 前几轮测错区域（"天空"2.1% 混入了室内
  天花板）；把全部 `mag≥10` 像素标出后可见品红**全在逆光悬挂物（衣服/毛巾/树枝/挂绳）边缘**——
  该区域成片 **14.74%**、RAW 域（双线性+CCM）21.14% ⇒ **整条链只压掉 30%**。PC 做法：`MeasureMoire`
  在 Y 平面出逐像素强度图 → **替换** ChromaDenoise 的强度；我们的 P4/D-1 是全局参数、无伪彩感知
  | handoffs/高光重建阈值修正与品红伪彩定位.memo.md
- [2026-10-03] **亮部亮度降噪** | 高 | 根治白点的正道（不改影调）。对标 PC 亮部 σY 差 1.67×；
  P0 bilateral 在全图只降 21~32%，亮部为何更弱待查（`σ²=A·x+B` 在亮部更大 ⇒ 权重应更大，实测相反）
- [2026-10-03] **超广畸变自校正** | 高 | HAL 路线已全关（exp7 实测 `distModes:none`、
  `lensDistortion` 为 AOSP 占位值、`preCorr==active`）⇒ 必须引擎 GPU 自校正（去马赛克前、
  逐 CFA 平面反向映射保相位）；**需拍板**：完整校正（视场窄 5~10%）/ 部分校正 / 不校正
- [2026-10-03] **影调白点默认值拍板** | 中 | `whiteEv` 4.0（darktable 默认、有白点）vs
  6.x（白点消失、更柔）；属观感基准变更，等用户在不同场景试过后定
- [2026-10-03] **窗户场景采集曝光只给 12ms** | 中 | 客厅同类场景给到 100ms，窗户只 12~13ms
  （光子少 8×）；exp12 已改"曝光给足"，需查 `ExposurePlanner` 四条约束为何在强光场景收敛到 12ms
- [2026-10-02] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、成像会话摘要、golden image 接 CI

- [2026-10-02] **NN 微调（暂缓）** | 降级 | 管线已建成（PyTorch 移植 / 亚像素对齐 / N2N 配对 /
  评估全跑通），但需几十个场景数据 —— 4 场景实测全部过拟合。代码与开关保留（`nn_dn` 默认关）

## 已完成（近期）

- [2026-10-03] **高光重建收线（exp19–exp23，276→280）**：逐行对照 darktable 找到 `threshold`
  语义偏差（原版 = 白点 `2^white_source × grey`，我们写死 1.0）⇒ 真机 `clipB` **523~601 → 2**、
  色块消失；mask / `init_reconstruct` / RGB-RATIOS 组合三处核对为一致。品红侧：opposed 的
  chrominance 因输入被 P0 降噪而系统性偏大 3.7×（`v−refavg` 被拉负）⇒ 置 0（洋红像素
  6.21%→0.83%，另三张 DNG 禁用前后差 0.007）；`inpaint_noise` 的彩色噪声相对信号 20%
  （是 darktable 约 3 倍）⇒ `noise_level` 置 0 并跳过恒等 pass
- [2026-10-03] **对标定位唯一短板 = 亮部降噪（exp16–exp18，273→275）**：同场景对标 PC ⇒ 亮部
  σY 0.82/0.49（1.67×）、色度反超 1.2~1.8×；白点确认为随机涨落（同机位 IoU 6.8%≈随机 5.4%）
  且与 dn 无关；噪声模型经 RAW 域实测证实准确（1.04~1.39）；`whiteEv` 6.2 可消白点（代价对比度）；
  杯口色块由用户开关实验定位到 `hr_wavelet`。期间 exp13 全画面红（mosaic 通道约定 bug）、
  exp15 RCD 色差回归原版、exp16 修黑白点滑杆失效、exp17 σ 修正发糊已回退
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
- [2026-10-02] **NN 路线全程 + 判死（v0.10.100–0.10.106，253→257）**：spike 达标（真机 GPU
  26ms/tile、纹理保留 78–86% 优于 P0）→ 集成五轮逐层修口径（JNI 桥 / KSigma / cvt_k×256 /
  通道序 / neff 折减）→ **nnGain 与公式逐位吻合而观感零改善**（文字梯度 −24%、斑块状伪影）⇒
  跨域模型不可直接用；四次微调（监督×3 + N2N）留出集**全过拟合**，瓶颈 = 数据量（4 场景 vs
  官方 3 万+）⇒ 转 HDR+ | handoffs/NN集成定因与判死转HDR+.memo.md

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
