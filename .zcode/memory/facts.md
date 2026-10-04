---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-02 10:05
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->
<!-- 2026-09-30 d3 整理:删被取代的"当时版本现状"与重复条目,当前有效参数保留。 -->

## 环境

- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；依赖上限锁定（2026.09 版 AndroidX 需 AGP 9.1+/SDK 37）
- [2026-09-18] 项目路径含中文（Downloads/胶片相机）：AGP externalNativeBuild 丢中文段（CXX1400），已改自定义 CMake Exec 直驱 NDK
- [2026-09-23] 构建命令（本机已验证）：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest`（系统默认 Java 8 跑不动 AGP）
- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、6=第二逻辑组、2/3/4/5=隐藏物理、1=前置）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、白阶 1023、maxISO 3200；**16F 无线性过滤**；实测 1024² 三段 spike 0.3–0.5ms/段
- [2026-09-29] X300 compute 能力（caps.detected 实测）：`maxComputeSharedMemory 32768`、`maxComputeInvocations 1024`、image units 21、GPU timer 可用；RGBA16F + `GL_FLOAT` readPixels 可用
- [2026-09-21] adb 安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`；手机存储可 Mac 直读：`~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`
- [2026-09-18] 设备日志导出：`/storage/emulated/0/Android/data/com.filmkit.app/files/logs`（用户经 vivo 办公套件回传，落点 `~/Downloads/vivo办公套件/`）；审计导出同目录 `files/audit/`
- [2026-09-21] 离线分析工具链：python3 + numpy + PIL + rawpy 0.27（`postprocess(use_camera_wb=True, no_auto_bright=True, gamma=(2.222,4.5))`）
- [2026-09-27] mimosa 插件已禁用（`~/.zcode/cli/config.json` enabledPlugins=false，备份 config.json.bak-mimosa）；调参脚本在 `tools/tuning/`

## 路径与位置

- [2026-09-18] Git 远端：`git@github.com:wanglei96/Filmkit.git` main；**CI 改为仅手动触发**（`workflow_dispatch`，2026-09-30；此前 push main/PR 自动构建顶爆 500MB Actions 配额）
- [2026-09-18] APK 产物：`app/build/outputs/apk/debug/app-debug.apk` → 根目录 `filmkit-<versionName>.apk` 单文件覆盖制；**出包前 `rm -rf app/build/outputs/apk`**（防残留旧 so）；`*.apk` gitignore
- [2026-09-18] 引擎 so：`libfilmengine.so`，JNI 入口 `com.filmkit.app.EngineBridge`；引擎源码 `core/engine/src/`（jni/Bridge.cpp、render/FrameRenderer.cpp、render/Align.{h,cpp}、spike/SpikeFftMerge.cpp）
- [2026-09-18] 崩溃捕获：未捕获异常写 `files/logs/crash-latest.txt` + JSONL `app.crash`
- [2026-09-20] shader 校验：`tools/validate-shaders.sh`（提取 FrameRenderer.cpp 的 R"(...)" 交 glslang，ES 3.1）；本地二进制 `tools/bin/glslang`（gitignore）；发版前必跑
- [2026-09-29] 频域降噪 spike：`core/engine/src/spike/SpikeFftMerge.{h,cpp}`（v0/v1/v2）+ JNI `nativeRunFftSpike`；入口=设置页「能力 Spike」；测量包 `filmkit-spike-fft.apk`

## 命令与版本约定

- [2026-09-18] 版本管理：发版打附注标签 `v<versionName>` 并 push；Conventional Commits（英文）；版本号单一来源（build.gradle + BuildConfig）
- [2026-09-22] **每轮对话只打一个 tag（验收版）**；中间验证包只出 APK 不打 tag（2026-09-30 又违反过一次：一轮打了 6 个，已清理）
- [2026-10-01] **当前版本：250（`0.10.99-m4`）**，验收 tag **v0.10.99-m4** 已 push；根目录 `filmkit-0.10.99-m4.apk`
- [2026-09-29] **X300 三摄噪声事实**：HAL 对隐藏物理镜头返回主摄模型（pairs 逐位相同）；实测 70MM=3.56×、15MM=2.16×；内置标定 `assets/noise_models/x300_{70mm,15mm}.c` 按"V2509A+等效焦距"匹配；同刻三镜头 A/B 残余噪声 1.22×/1.45×

## 引擎实现现状

- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；LUT 必须在 OETF 后
- [2026-09-29] **FilmParams 契约 kCount=43**：`[0] exposure_ev=base_ev+用户EV`、`[1]/[3] wb`、`[20..22] sat_comp`、`[29] hl_recon`（槽位保留不消费）、`[39] sharpen`、`[40] gamut`、`[42] white_hl`；按实际长度读取（下限 26）
- [2026-09-29] **影调链**：filmic 原生件（darktable v5 固定参数，base_ev 默认 +0.7）；黑白点自动三版证伪后默认关；`updateFilmicEV` 的 white 判据 = max 驱动探针 + 整体 +ev（渲染域对齐）；statistics 512×288、P99/P99.9/P100
- [2026-09-29] **gamut mapping v5**（min+相锁+Y clamp+desaturate(0)+Yrg+clip chroma）；**输出 OETF 真 sRGB**（kFsOut/kFsHist/measureSceneStats 三处同步）；原生小波高光重建；锐化 = 线性显示域 luma USM（kFsOut 内）
- [2026-09-27] **MFNR 采集**：maxImages 4 + 帧 1-3 拷贝即 close（**maxImages 5+4 全程持有致 HAL 预配 125MB 闪退**）；设置三档 prefs `mfnr`（0/1 自动暗光 ISO≥600 或 ≥25ms/2 强制）
- [2026-09-30] **MFNR 对齐（0.10.70 起）**：最细精化层 = **1/2 域**（2×2 Bayer 平均灰度，HDR+ §4 原版），tile 64@1/2 域、金字塔 L4→L1 逐级；位移量化 2 mosaic px；`allow_l1=false` 可强制走旧 1/4 域路径（A/B 用）；日志 `alignL1`（3=新路径）
- [2026-09-30] **P0 时域×空间联合**（`kFsRawDenoiseFusedTs`）：4 帧 × 24 同相位邻域 = 96 样本；**`uSub`（0.10.76 起默认 1）= 逐通道网格亚像素双线性**（`linSubZ`），0 = 旧"round 到偶数"；诊断 `p0s`/`p0w`
- [2026-09-30] **降噪现状**：P0/P4/mismatch 全走 **σ²=A·x+B** 逐像素；强度滑杆 0–2（1.0=真实 σ）；D-1 色度 à trous 5 级（k=2.5/revert=0.20）**已超 PC**；模型优先级：用户导入 > 内置实测 > Camera2 > ISO 估算
- [2026-09-30] **DFT 合并（A 专项）**：`kFsMfnrFft` compute，按 CFA 平面独立、16×16 平面 tile 半重叠（步长 8 + 修改升余弦窗，窗和恒 1）；**窗函数查表 `sWin`**（0.10.77）；输出 4 张 RGBA16F image2D writeonly，**必须 glTexStorage2D**；compute program **现场创建用完删除**；`mfnr.fft.store_check` 自检 + 失败回落 mismatch；**dftGpu 冷机 500~550ms / 热机 830~870ms**

- [2026-10-01] **拍照侧引擎（v0.10.85）**：`g_renderer_still`（`std::atomic` 指针）+ 独立 `EglCore`/1×1 pbuffer/`FrameRenderer`；`nativeStillStart` 幂等、`nativeStillDestroy` **连 context 一起销毁**（空闲 10s 由 Kotlin 侧触发）；LUT / YUV 矩阵为**进程级快照**、start 时下发；JNI 成片回传统一走 `rgbaToJavaArray`（OOM → 清异常返回 nullptr）；`AndroidManifest` 开 `largeHeap`

## PhotonCamera 参照事实（/tmp/photoncamera，hinnka 版）

- [2026-09-28] **PC 降噪实现**：Spatial merge 空间域 RBF（`GlesMgcRawSpatialShaders.kt:1654`）+ Sabre 结构张量各向异性核（`GlesMgcRawSabreShaders.kt:139`）+ **FullResolutionDenoise 五级金字塔**（`raw/MgcFullResolutionDenoise.kt`）；噪声模型 = 128-bin 半 bin 居中功率谱；去马赛克噪声传播标定 `DemosaicNoisePropagationCalibrator.kt`
- [2026-09-30] **PC 的"快门即撒手"架构**：`isCapturing` 只锁采集窗口；帧存 per-shot `PhotoCaptureContext`（`CapturedPhotoFrame(SafeImage)`，**零拷贝持有 Image**，`SafeImage.planes` 惰性解析在**处理线程**）；`resolveImageReaderMaxImages()` 按需放大；处理完才 close
- [2026-09-30] **PC 的线程/context 模型**：预览在 `CameraGLSurfaceView`（GLSurfaceView 自己的 GL 线程）；各后处理器**各自 `eglCreateContext`**（`GlesMgcRawSpatialStacker:9537`、`GlesMgcRawBaseFrameSelector:708`、`OglBokehProcessor:1796`）——这是它拍完不卡预览的原因
- [2026-09-28] PC 多帧曝光 = 长帧快门硬上限 10ms + 模拟增益 cap；filmic = darktable v5 默认固定参数；局部色调映射 `DngPhotonLocalToneMapper.kt`；色彩体系 = Hncs；用户实测本次选 filmic 不经 DCP
- [2026-09-24] 我们的 DNG 未写 BaselineExposure（vivo DngCreator 不写该 tag）；DNG 无 ForwardMatrix1/2（Characteristics 给全）；`.hcurve`（哈苏，不可复制）= 68B 头 + 65536×uint16 LE

## 工具与脚本

- [2026-10-01] **本轮新增**：`tools/still-thread-verify.py`（拍照线程验收：按 `settings.still_thread` 切换点分组，输出预览停更中位/最大、每路 burst 中位耗时、CRC 等价性、落盘计数、`render.fps` 帧率曲线）、`tools/still-thread-run.sh`（adb 一键 `install` / `pull`+分析 / `mem`）

- [2026-09-30] **本轮新增**：`tools/align-bench.cpp` + `tools/align-bench-gen.py`（对齐精度离线基准：真 DNG 金字塔 + FFT 相移真值 + 链接真实 Align.cpp）、`tools/p0-subpixel-eval.py`（P0 采样离线评估，与合成真值比误差）、`tools/check-log-json.py`（日志 JSON 合法性守卫，**分析前先跑**）
- [2026-09-30] `tools/` 既有：validate-shaders.sh、check-diag-fields.py（**出包前必跑**）、check-fft-tile-cover.py、chroma-scale-eval.py（σC 1–32px）、texture-fidelity-eval.py（2/4/8/16px 带通 + 局部对比度）、denoise-strength-eval.py（σY 分层）、ab-100pct.py（同刻 100% 裁剪配准对比）、chroma-denoise-eval.py、tone-scan.py、gen-lut-assets.py、dng-color-dump.py、color-metrics.py、ccm-chain-ab.py、check-srgb-oetf.py、check-lsc-mapping.py、lut-audit.py
- [2026-09-22] 内置 LUT 资产：42 个 `.lut`（uint8、33³、4.3MB）；与 `~/Downloads/LUT/滤镜.cube/` 64³ 逐点一致；显示名/分类在资产头，prefs `lut_cat_*`/`lut_cats` 存覆盖
- [2026-09-24] `docs/research/` 11 份 + README 索引（00=活入口、01–08 证据附录、**09=频域降噪专项**、90=原始转储勿删）

## 本轮实测数据（2026-09-30）

- [2026-09-30] **PC 对标（同场景同曝光 ISO 1386 vs 1402）**：暗部 σY 1.323 vs 0.586（**差 2.26×**，旧数据 4.7×）、中间调 1.050 vs 0.610；暗部 σC 0.338 vs 0.787（**好 2.3×**）、中间调 0.322 vs 0.935（好 2.9×）；边缘 P99.5 12.11 vs 10.90；纹理保真比 0.92
## 拍照线程与 LUT（2026-10-01，压缩）

- [2026-10-01] **连拍崩溃与显存代价**：OOM 报 `Failed to allocate 50135052 … target footprint 248965720 / growth limit 268435456`（237/256MB）；修后连拍 7 张全成功、queued/saved 4/4 零丢失。显存（`dumpsys meminfo` 逐 2s）：预览基线 392MB、**开独立线程峰值 3011MB / 拍完残留 2418MB**、关线程峰值 2556MB / 回落 459MB ⇒ 独立线程代价 = 峰值 +455MB、常驻 +1.96GB（空闲释放后回 ~400MB）
- [2026-10-01] **合成耗时与帧率**：热机单张 `totalMs` 2091~3336ms（dftGpu 855~922、passMs 711~2021、alignCpuMs 185~737），冷机首次可达 5133ms；空闲 30~32fps、拍照期最低 13~25fps；**同帧 A/B 画质等价**（两条路径 CRC32 `3615113902` 完全相同）；MFNR 4 帧 `render.burst_done ms ≈ 3.9–4.3s`（align 835 + fuse 1278 + dftGpu 614）、采集 257–313ms，`capture()` 在渲染期**静默吞快门**（"连按两下只出一张"的根因）
- [2026-10-01] **LUT 库结构**：`app/src/main/java/com/filmkit/app/lut/`（CubeLut 解析 1D/3D/二进制、MAX_SIZE 128；LutIO 嗅探/Hald·Unwrapped/重采样；LutLibrary 内置 42 + 用户库 files/luts、id 前缀 u_）；预览降载走 `g_preview_scale`（进程级 atomic）；单帧静拍直通 `nativeEngineSubmitRaw/Still` + `bindLut` 强度上限 1.5

## NN 路线与对齐（2026-10-02，NN 已判死）

- [2026-10-02] **spike 达标但集成判死**：真机 GPU **26ms/tile**（7× CPU）、12MP 0.51s 零温升；纹理保留 78–86% 优于 P0 交换比。集成五轮逐层修口径（JNI 桥 / KSigma 量纲 / cvt_k×256 / 通道序 / neff 折减）后 **nnGain 与公式逐位吻合而观感零改善**（文字梯度 −24%、平坦区斑块状伪影而非白噪）⇒ **跨域模型不可直接用**；四次微调（监督×3 + N2N）留出集**全过拟合**（+12.0/+9.9/+11.7/+5.06%），瓶颈 = 数据量（4 场景 vs 官方 3 万+）
- [2026-10-02] **NN 代码落点（保留、默认关）**：`app/src/main/java/com/filmkit/app/nn/NnInferEngine.kt`（常驻推理线程 + GpuDelegate + CPU 熔断）、`FrameRenderer.{h,cpp}`（kFsNnPack/Unpack、runNnPass、nnSemTables、**kNnAnchorS=1.0093e-3**、cvt_k×neff）、`Bridge.cpp`；prefs nn_dn/nn_ab 默认关；raw.render 含 nn/nnOk/nnFail/nnMs/nnTiles/nnGain
- [2026-10-02] **对齐工具实测**：位移误差 0.0115px（合成 6 帧）、噪声 ×3.22、异场景剔除 wn 0.304 vs 同场景 0.996；真实手持 8 帧 ×2.16；93 帧/5 场景中 4 个可用

## 曝光决策（10-02 晚，压缩）

- [2026-10-02] **曝光 ×N + ISO ÷N = 亮度与高光裁切不变、纯赚 SNR**：离线解析收益 √N 与物理上限逐位吻合（N=8.66 → 2.95×）；约束三条 = ISO 下限 / 快门上限 / 运动预算；纹理预算维持**细节优先 2.2px**（一度放宽到 4.5px 属误读用户意见替用户做取舍，已撤回）。**真机 250ms 档**：`expCap:"250ms" expN:"8.33" altIso:167 altSec:0.2500`；nzPreMed 0.005219→0.001688（**1/3.1**）、成片域 0.002104→0.000417（1/5.0）；**ISO167 = Filmix 的 ISO166**
- [2026-10-02] **三图同场景定量**（盘沿亮白 Y≈211–224）：σY 我们 **4.48** / Filmix 1.72 / PC 0.92；中间调（茶几 Y≈162）我们 **1.06（三家最好）**；碗内壁 lag-1 自相关 0.939 ≈ Filmix 0.938 ⇒ **真实纹理非噪声**
- [2026-10-02] **三摄 GCam 标定（`assets/noise_models/`）**：主摄 HPB A=2.72e-7 dg_ref 6400（100 张实测）、超广 JN1 A=3.07e-7（借用 NubiaZ50Ultra，非本机实测）、长焦 LYT602 A=1.05e-6；`BUILT_IN` 原先**无主摄条目**；真机 `src:"builtin"` ×3 验证通过

## 畸变、白斑与预览域（10-03，压缩）

- [2026-10-03] **X300 畸变/几何能力（exp7 真机，4 次会话 × 2 个 cameraId 全一致）**：`DISTORTION_CORRECTION_AVAILABLE_MODES` = **none**、`LENS_DISTORTION` = `0.001,0,0,0,0`（AOSP 占位）、`LENS_RADIAL_DISTORTION` 空、`PRE_CORRECTION_ACTIVE_ARRAY == ACTIVE_ARRAY`（无裁边区）；`LENS_INTRINSIC_CALIBRATION` fx=fy=**2790.18**（主摄）/ **2414.06**（超广）。AOSP 原文：校正 *"not applied to any RAW output"* 且该控制 *"on by default"* ⇒ 连 YUV 也未校正
- [2026-10-03] **预览域 vs 成片域**：同场景 `w999`（EV 前 P99.9）预览 **2.09** / 成片 **2.674**（另一组 2.4383/2.674）⇒ 差 0.24~0.58EV 且逐次不同；预览走 OES（ISP 已 tonemap），成片是 RAW 归一化域
- [2026-10-03] **白斑（红杯釉面）根因 = 亮部亮度噪声**：显示域幅度 15~56 级；两帧 IoU = 0（随机）；四类假设已排除（RAW 域两帧异常位置重合仅 0.5%、锐化邻域 d 均值 +110 无暗环、σC/σY 仅 0.23~0.27、两帧高频相关 −0.007）。RAW 域模型准：DNG 平坦区（ISO 531）信号 <200 段实测/模型 = **0.96~1.06**，高信号段残差来自饱和/边缘而非噪声
- [2026-10-03] **横拍裁切 bug 指纹**：`photo.crop srcA:1.328 dstA:0.750 out:2304x3072`（横图被裁成竖幅，丢 44%）；触发条件 = 拍照时 `orientationStep` 为横、渲染完成前用户转回竖屏（MFNR 渲染 3~4s）

## 对标与开关实测（10-03 下午）

- [2026-10-03] **对标 PhotonCamera（同场景、相隔 14 秒）**：平坦块分亮度层 σY —— 亮部(170+) 我们 **0.82** / PC **0.49**（**1.67×**）；中亮(120-170) 0.66/0.49；中暗(60-120) 0.82/0.66。**差距随亮度单调放大 ⇒ 唯一短板 = 亮部亮度降噪**。色度多尺度 1→32px 我们 **0.98→0.28** / PC 1.17→0.35（**全面占优 1.2~1.8×**）
- [2026-10-03] **噪声模型在 RAW 域是准的**：CFA 平面独立、中值残差法实测 σ vs 模型 —— R 1.39、G1 1.04、G2 1.23、B 1.34（同量级）⇒ "降噪模型不准"可排除
- [2026-10-03] **filmic 白点滑杆的真实效果**：`whiteEv` 4.53 → **6.20** ⇒ 红釉白点消失，代价是对比度降低；机制 = 白点越远、映射区间越宽、曲线越平、微小起伏被压缩
- [2026-10-03] **降噪强度与白点无关**：dn 0/0.45/1.0 ⇒ 釉面 d>15 = 0.38%/0.50%/0.53%（反而略升）；`nzPost` 分别降 0%/21%/32% ⇒ **亮度噪声最多只压掉 32%**

## 高光重建与品红（10-03 晚，本窗口）

- [2026-10-03] **filmic HR 的 `threshold` 语义**（逐行对照 `filmicrgb.c:3120`）：原版 = **白点** `2^(white_source + reconstruct_threshold) × grey_source`（后者默认 0）；旧实现写死 `1.0`（RAW 饱和点）在 WB 后域系统性偏低——R/B 增益 1.8/1.7 把未饱和像素的欧氏范数抬过 1.0 ⇒ 大面积误重建。改后真机 `clipB` **523~601 → 2**，色块消失
- [2026-10-03] **`threshold` 的计算式**：`max(2^(ev_white_ − exposure_ev) × 0.1845, 1.0)`；下限 1.0 = `2^2.4383EV × kGrey`（与 `kWhiteSat` 同源）。其余三处（mask 公式/feather、`init_reconstruct`、RGB-RATIOS 组合）与原版核对一致
- [2026-10-03] **opposed chrominance 的引擎/原版偏差**：同 DNG 同增益下引擎 `hlChroma` G **−0.250** vs 原版公式 **−0.047**（3.7×）；`hlCnt` 接近（47269/48925）而 `hlSum` 差 4 倍 ⇒ **非样本集合、而是输入被 P0 降噪**（同色 3×3 平滑压低饱和旁的 G，而 refavg 走 R/B 外推受影响小）。修复值 = `ref + chroma` ⇒ 该负值把补回的 G 又扣掉
- [2026-10-03] **品红位置的实测口径**：把全部 `mag≥10` 像素标出 ⇒ **全在逆光悬挂物（衣服/毛巾/树枝/挂绳）边缘**，天空本体几乎没有。该区域成片 **14.74%**、RAW 域（双线性+CCM）**21.14%** ⇒ 整条链只压掉 30%；DNG 该区域真饱和 **12.40%** photosite（R 9.77 / G 13.44 / B 12.95）
- [2026-10-03] **PC 色度降噪架构（源码实证）**：`RawToYuv` → **`MeasureMoire`**（输入 Y 平面，输出 **1/4 分辨率 3 通道强度图**）→ 该图**替换**送给 `ChromaDenoise` 的强度（`mgc_full_resolution_denoise_jni.cpp:1021-1064`，注释原文 *"replaces only the strength map supplied to ChromaDenoise"*）；`ChromaDenoise` = **四级色度金字塔** + **4 level × 3 通道 × 2 branch** 独立 shot/quadratic/read 表 + 频响参数（`response_offset`/`response_cosine_offset`）。`MeasureMoire` 本体是编译好的 Halide capsule（`mgc_demoire_capsule.bin`），公式不可读
- [2026-10-03] **inpaint_noise 的相对幅度**：`sigma = pix × noise_level / threshold`；我们 `threshold`=1.0（RAW 饱和点）而 darktable 白点远高于数据最大值（本例 9.6 vs 1.4）⇒ 同等公式下相对幅度约 3 倍、逐通道独立 ⇒ 彩色颗粒。`noise_level` 已置 0
- [2026-10-03] **版本沿革（本窗口）**：276 exp19（threshold 修正）→ 277 exp20（hlSum/hlCnt3 诊断）→ 278 exp21（统计源换 `tex_fused_`，**有害已回退**）→ 279 exp22（禁 chrominance）→ **280 exp23（关 inpaint 噪声，当前推荐）**

## 设置页与图标（10-04）

- [2026-10-04] **版本 281–288（0.10.126–133）**：设置页四抽屉整理 / 图标五版迭代 / 验收 tag v0.10.133（2f8bbfc）；图标 = 光圈叶片+居中镜片 **vector 13 path**（`drawable/ic_launcher_fg.xml`）+ legacy 4 密度 PNG + monochrome，资源结构 = PC 同构
- [2026-10-04] **编辑器调研 docs/research/10 号**（6e94f99）：预设 schema 提案（47 槽不动 + 颗粒强度/尺寸/密度调制、halation 阈值/强度/半径/色相、暗角、趾肩、层间效应）、E1–E5 阶段；基础设施 = CameraPresets 47 槽 + `nativeEngineSubmitStill/Raw` 单帧直通 + 胶卷位占位（CameraScreen:1232）
