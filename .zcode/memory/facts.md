---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-05 20:30
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->
<!-- 2026-10-05 d3 整理:压缩折行、删除被后续版本取代的"当时现状",保留当前有效值与口径。 -->

## 环境

- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；依赖上限锁定（2026.09 版 AndroidX 需 AGP 9.1+/SDK 37）
- [2026-09-18] 项目路径含中文（Downloads/胶片相机）：AGP externalNativeBuild 丢中文段（CXX1400），已改自定义 CMake Exec 直驱 NDK
- [2026-09-23] 构建命令（本机已验证）：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest`（系统默认 Java 8 跑不动 AGP 8.13）
- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、1=前置、2-5 隐藏物理、6=第二逻辑组）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、白阶 1023、maxISO 3200；RGBA16F 无线性过滤
- [2026-09-29] X300 compute 能力（caps.detected 实测）：maxComputeSharedMemory 32768、maxComputeInvocations 1024、image units 21、GPU timer 可用；RGBA16F + GL_FLOAT readPixels 可用
- [2026-09-21] adb 安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`；手机存储 Mac 直读 `~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`
- [2026-09-18] 日志/审计导出：`/storage/emulated/0/Android/data/com.filmkit.app/files/{logs,audit}`（vivo 办公套件回传，落点 `~/Downloads/vivo办公套件/`）；未捕获异常写 `logs/crash-latest.txt` + JSONL `app.crash`
- [2026-09-21] 离线工具链：python3 + numpy + PIL + rawpy 0.27（`postprocess(use_camera_wb=True, no_auto_bright=True, gamma=(2.222,4.5))`）；mimosa 插件已禁用（`~/.zcode/cli/config.json`）；调参脚本 `tools/tuning/`

## 路径与约定

- [2026-09-18] Git 远端 `git@github.com:wanglei96/Filmkit.git` main；**CI 仅手动触发**（`workflow_dispatch`，push 自动构建顶爆 500MB Actions 配额）；出包在本地做
- [2026-09-18] APK 产物：`app/build/outputs/apk/debug/app-debug.apk` → 根目录 `filmkit-<versionName>.apk` 单文件覆盖制；**出包前 `rm -rf app/build/outputs/apk`**（防残留旧 so）；`*.apk` gitignore
- [2026-09-18] 引擎 so `libfilmengine.so`，JNI 入口 `com.filmkit.app.EngineBridge`；源码 `core/engine/src/`（jni/Bridge.cpp、render/FrameRenderer.cpp、render/Align.{h,cpp}、spike/SpikeFftMerge.cpp、film/FilmParams.h）
- [2026-09-20] shader 校验 `tools/validate-shaders.sh`（提取 FrameRenderer.cpp 的 `R"(...)"` 交 glslang，ES 3.1；本地二进制 `tools/bin/glslang`，gitignore）；**发版前必跑**
- [2026-09-18] 版本约定：发版打附注标签 `v<versionName>` 并 push；Conventional Commits（英文）；**每轮对话只打一个 tag**（中间验证包只出 APK 不打 tag）；版本号单一来源（build.gradle + BuildConfig）
- [2026-10-05] **版本现状**：本轮改动跨 v0.10.162–176（快照记"10 个交付包"）；仓库现存 `filmkit-0.10.163`–`0.10.176`（含 `0.10.164-diag`），**0.10.162 无对应 APK 包**；最新 `filmkit-0.10.176.apk`（versionCode 315），HEAD `a91c9d6`；**验收 tag 最新仍为 v0.10.133**（本轮未打 tag）；`app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` 是 FilmParams 槽位唯一来源

## 引擎实现现状

- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；硬约束 LUT 必须在 OETF 后
- [2026-10-05] **FilmParams 契约 kCount=56**（`film/FilmParams.h:14`，v0.10.175 起；旧记忆的 36/43/48 均已过时）：[0] exposure_ev、[1..3] wb、[29] 空槽不复用、[31..34] 外观层/LUT 强度、[35] auto_tone（默认关）、[45] hr_raw、[47] defringe、[48] moire、[49] hl_grecon、[50] ca_corr、[51] chroma_gate=2.0、[52] cg_scope=1.0、[53] df_gate=0.0、[54] pc3d=0（默认关）、[55] pc3d_msize=0（自动 ⇒ 核 11）；**按实际长度读取（下限 26）**；⚠️ `CameraPresets.kt:170` 注释仍写 kCount=54，下发数组必须按 56（越界直接崩）
- [2026-09-29] **影调链**：filmic 原生件（darktable v5 固定参数，base_ev 默认 +0.7）；黑白点自动三版证伪后默认关；`updateFilmicEV` 的 white 判据 = max 驱动探针 + 整体 +ev（渲染域对齐）；statistics 512×288、P99/P99.9/P100
- [2026-09-29] **gamut mapping v5**（min+相锁+Y clamp+desaturate(0)+Yrg+clip chroma）；**输出 OETF 真 sRGB**（`kFsOut`/`kFsHist`/`measureSceneStats` 三处同步）；原生小波高光重建；锐化 = 线性显示域 luma USM（kFsOut 内）
- [2026-09-27] **MFNR 采集**：maxImages 4 + 帧 1-3 拷贝即 close（maxImages 5+4 全程持有致 HAL 预配 125MB 闪退）；prefs `mfnr` 三档（0 关 / 1 自动暗光 ISO≥600 或 ≥25ms / 2 强制）
- [2026-09-30] **MFNR 对齐（0.10.70 起）**：最细精化层 = **1/2 域**（2×2 Bayer 平均灰度，HDR+ §4 原版），tile 64@1/2 域、金字塔 L4→L1；位移量化 2 mosaic px；`allow_l1=false` 走旧 1/4 域（A/B）；日志 `alignL1`
- [2026-09-30] **P0 时域×空间联合**（`kFsRawDenoiseFusedTs`）：4 帧 × 24 同相位邻域 = 96 样本；`uSub`（0.10.76 起默认 1）= 逐通道网格亚像素双线性（`linSubZ`），0 = 旧"round 到偶数"；诊断 `p0s`/`p0w`
- [2026-09-30] **降噪现状**：P0/P4/mismatch 全走 **σ²=A·x+B** 逐像素；强度滑杆 0–2（1.0 = 真实 σ；<1 走输出 mix）；D-1 色度 à trous 5 级（k=2.5、revert 0.20）**已超 PC**；模型优先级 = 用户导入 > 内置实测 > Camera2 > ISO 估算
- [2026-09-30] **DFT 合并（A 专项）**：`kFsMfnrFft` compute，按 CFA 平面独立、16×16 平面 tile 半重叠（步长 8 + 修改升余弦窗，窗和恒 1）；窗函数查表 `sWin`；输出 4 张 RGBA16F image2D writeonly，**目标必须 `glTexStorage2D`**；compute program 现场创建用完删除；`mfnr.fft.store_check` 自检 + 失败回落 mismatch；dftGpu 冷机 500~550ms / 热机 830~870ms
- [2026-10-01] **拍照侧引擎（v0.10.85）**：`g_renderer_still`（`std::atomic` 指针）+ 独立 `EglCore`/1×1 pbuffer/`FrameRenderer`；`nativeStillStart` 幂等、`nativeStillDestroy` **连 context 一起销毁**（空闲 10s 由 Kotlin 侧触发）；LUT / YUV 矩阵为**进程级快照**、start 时下发；JNI 成片回传统一走 `rgbaToJavaArray`（OOM → 清异常返回 nullptr）；`AndroidManifest` 开 `largeHeap`
- [2026-10-05] **紫边/高频链现状**：`hr_wavelet` 默认关（模块保留可 A/B；曾占 ≈752MB 纹理 + ≈103 全分辨率 pass/张，且 `w=(w>0.9)?w*w:0` 是 +29% 梯度的来源已移除）；HR 前置到 P0 之前、统计用 `uStat`（原 `uStat` 是死参数，与 `uMos` 同源 = 历史 exp21"改 src 即全改"的结构性根因）；defringe 4 pass 半分辨率（`[47]` 默认开）；强边缘色度门逐像素（`[51]`/`[52]`）；ESD3D2 移植 `[54]` 默认关（核尺寸由噪声模型算、两趟 + 引导上采样）

- [2026-10-05] **紫边相关 shader 落点**：`core/engine/src/render/FrameRenderer.cpp`——`kFsChromaDenoise`（P4 色度）/ `kFsPc3d`（ESD3D2 移植）/ `kFsHrMask`（小波 HR mask）/ `passHighlightRaw` + `runHighlightReconstruct`（RAW 域高光重建）/ `kFsDefringe*`（defringe 4 pass）；槽位常量与默认值在 `film/FilmParams.h` + `ui/CameraPresets.kt`

## PhotonCamera 参照事实（/tmp/photoncamera，hinnka 版）

- [2026-09-28] **PC 降噪实现**：Spatial merge 空间域 RBF（`GlesMgcRawSpatialShaders.kt:1654`）+ Sabre 结构张量各向异性核（`GlesMgcRawSabreShaders.kt:139`）+ FullResolutionDenoise 五级金字塔（`raw/MgcFullResolutionDenoise.kt`）；噪声模型 = 128-bin 半 bin 居中功率谱；去马赛克噪声传播标定 `DemosaicNoisePropagationCalibrator.kt`
- [2026-09-30] **PC 的"快门即撒手"架构**：`isCapturing` 只锁采集窗口；帧存 per-shot `PhotoCaptureContext`（`CapturedPhotoFrame(SafeImage)`，零拷贝持有 Image）；`resolveImageReaderMaxImages()` 按需放大；处理完才 close
- [2026-09-30] **PC 的线程/context 模型**：预览在 `CameraGLSurfaceView`（GLSurfaceView 自有 GL 线程）；各后处理器**各自 `eglCreateContext`**（`GlesMgcRawSpatialStacker:9537`、`GlesMgcRawBaseFrameSelector:708`、`OglBokehProcessor:1796`）——这是它拍完不卡预览的原因
- [2026-09-28] PC 多帧曝光 = 长帧快门硬上限 10ms + 模拟增益 cap；filmic = darktable v5 默认固定参数；局部色调映射 `DngPhotonLocalToneMapper.kt`；色彩体系 = Hncs；用户实测本次选 filmic 不经 DCP
- [2026-09-24] 我们的 DNG 未写 BaselineExposure（vivo DngCreator 不写该 tag）；DNG 无 ForwardMatrix1/2（CameraCharacteristics 给全）；`.hcurve`（哈苏，不可复制）= 68B 头 + 65536×uint16 LE
- [2026-10-03] **PC 色度降噪架构**：`RawToYuv` → `MeasureMoire`（输入 Y 平面，输出 **1/4 分辨率 3 通道强度图**）→ 该图**替换**送给 `ChromaDenoise` 的强度（`mgc_full_resolution_denoise_jni.cpp:1021-1064`）；`ChromaDenoise` = 四级色度金字塔 + 4 level × 3 通道 × 2 branch 独立 shot/quadratic/read 表；`MeasureMoire` 本体是编译好的 Halide capsule（`mgc_demoire_capsule.bin`），公式不可读
- [2026-10-05] **PC ESD3D2（PC3D）**：核尺寸由噪声模型算 `min(7+(int)ks−(int)ks%2, 21)`（本机 ⇒ 11），两趟 + 引导上采样（`esd3d2_steered.glsl` / `ESD3D2.java` / `convdiff.glsl` / `guidedupsample.glsl` / `GLUtils.gaussdown`）；**`cap=0.2` 在 PC 里从不触发（实测吃满率 0.00%）**；**PC 成片本身也是 4:2:0**；PC 的 `CorrectingFlow` 只对 OnePlus 生效（样片 vivo 走 passthrough）

## 工具与脚本

- [2026-10-01] `tools/still-thread-verify.py` + `still-thread-run.sh`（拍照线程验收：预览停更/burst 耗时/CRC 等价/落盘计数/帧率曲线）；`align-bench.cpp`+`align-bench-gen.py`（对齐离线基准）、`p0-subpixel-eval.py`、`check-log-json.py`（**分析前先跑**）、`check-diag-fields.py`（**出包前必跑**，六点核对 compile/link/zero/uniforms/use/delete）
- [2026-09-30] 其余 `tools/`：validate-shaders.sh、check-fft-tile-cover、chroma-scale-eval、texture-fidelity-eval、denoise-strength-eval、ab-100pct、chroma-denoise-eval、tone-scan、gen-lut-assets、dng-color-dump、color-metrics、ccm-chain-ab、check-srgb-oetf、check-lsc-mapping、lut-audit、tuning/
- [2026-09-22] 内置 LUT 资产 42 个 `.lut`（uint8、33³、4.3MB）与 `~/Downloads/LUT/滤镜.cube/` 逐点一致；`docs/research/` = 12 个编号调研（00 活入口 / 01–08 冻结证据附录 / 09 频域降噪 / 10 ARRI 引擎计划 + 内建编辑器与预设）+ filmulator 专题 + README + `90-工作流原始转储`（勿删，含独有内容）
- [2026-10-05] **紫边审计报告** `/tmp/pcfix/REPORT.md`（578 行：口径自证 + 四维消融 + 零假设 + 剖面）；前序 `/tmp/amaze`、`/tmp/chromagate{,2}`、`/tmp/pc3d`、`/tmp/jpeg444`、`/tmp/pc-full`、`/tmp/darkrg`；**均在 /tmp，重启即失、未沉淀 tools**

## 对标数据与测量口径（10-03 → 10-05）

- [2026-10-03] **亮部降噪 = 唯一成像短板**：同场景 PC 亮部 σY 我们 0.82 / PC 0.49（**1.67×**，差距随亮度单调放大）；色度多尺度 1→32px 全面占优 1.2~1.8×；噪声模型 RAW 域实测准（R 1.39/G 1.04/G2 1.23/B 1.34）
- [2026-10-05] **紫边对标基准修正**：`∇>60` 均值口径可自证复现 PC 的 **3.68413 / 0.44607%**，但它把 PC 高估 ≈**2.2×**（零假设检验：3.684 里只有 **+1.668** 与边缘有关）；正确目标 = **边缘局域超额 1.67 / 剖面 core 1.08**；**同一地点相隔 7 秒的两张成片读数可差 25.8×** ⇒ 该口径禁作跨图 KPI
- [2026-10-05] **剖面形状**：我们 = 窄峰（**FWHM 4px**）、PC = 宽缓斜坡（**20+px、无峰**）；真机 1px 色度噪点 10.5%→**0.04%**（融合+P0/P4/D-1 有效）；用户所见品红主体 = **>32px 低频色度噪声云**（P90 3 级、品红绿成对、中亮区集中），根因 4.8ms 短曝光
- [2026-10-05] **AMaZE（排除去马赛克路径）**：完整 CPU 多线程实现 **247ms@12MP** + 设置三档可选；离线六项指标**五项为负**（∇>60 伪彩 AMAZE 30.0 vs DCB 19.2）
- [2026-10-05] **4:4:4 JPEG（排除编码路径）**：实测生效（SOF0 变 4:4:4、ms=293）但紫边未解决；反证 = PC 成片本身也是 4:2:0
- [2026-10-05] **色度门实测**：0.173 全局放宽 `wy` ⇒ 真机"一片红一片黄一片紫"；0.174 改逐像素门控（门覆盖率 73.7%→1.6%）后色渗判据全绿
- [2026-10-05] **HR 阈值域修复实测**：真机 Y 加权和之差 **+2.03% → +0.03%**、clipB **289→60**；`uGRecon` 关闭后高光区 B/G **1.087→1.025**
- [2026-10-05] **测量证据两则**：rawpy 的 `black_level_per_channel` 是取整值（DNG 真值 `[63.99,64.01,64.01,64.01]`，相位极差 ≤0.03 ADU）⇒ "R 相位黑电平 63 致暗部偏红"假设被推翻；旧诊断照片是二次传输重编码副本（1777×2360 ≠ 原生 3072×4080、DQT 非标准表）⇒ **用户截图不能作证据，必须取相册原图**

## 相机能力与标定（仍有效）

- [2026-10-03] **X300 畸变/几何能力（exp7 真机，4 次会话 × 2 cameraId 全一致）**：`DISTORTION_CORRECTION_AVAILABLE_MODES` = **none**、`LENS_DISTORTION` = `0.001,0,0,0,0`（AOSP 占位）、`LENS_RADIAL_DISTORTION` 空、`PRE_CORRECTION_ACTIVE_ARRAY == ACTIVE_ARRAY`；`LENS_INTRINSIC_CALIBRATION` fx=fy=**2790.18**（主摄）/ **2414.06**（超广）；AOSP 原文：校正 *"not applied to any RAW output"* 且该控制 *"on by default"* ⇒ 连 YUV 也未校正；超广桶形畸变实测幅度 **5.7%**（未校正）
- [2026-10-03] **预览域 vs 成片域**：同场景 `w999`（EV 前 P99.9）预览 **2.09** / 成片 **2.674**（另一组 2.4383/2.674）⇒ 差 0.24~0.58EV 且逐次不同；预览走 OES（ISP 已 tonemap），成片是 RAW 归一化域 ⇒ 曝光判据只认成片域 `sw999`
- [2026-09-29] **X300 三摄噪声事实**：HAL 对隐藏物理镜头返回主摄模型（pairs 逐位相同，可作为继承指纹）；实测 70MM=3.56×、15MM=2.16×；内置标定 `assets/noise_models/x300_{70mm,15mm}.c` 按"V2509A+等效焦距"匹配；同刻三镜头 A/B 残余噪声 1.22×/1.45×
- [2026-10-02] **三摄 GCam 标定**：主摄 HPB A=2.72e-7 dg_ref 6400（100 张实测）、超广 JN1 A=3.07e-7（借用 NubiaZ50Ultra，非本机实测）、长焦 LYT602 A=1.05e-6；`BUILT_IN` 原先**无主摄条目**（主摄一直走 HAL cam2，其 G 通道比 R/B 低一半）；真机 `src:"builtin"` ×3 验证通过；手动选定是**全局**的（不随镜头切换）
- [2026-10-02] **曝光 ×N + ISO ÷N 纯赚 SNR**：离线解析收益 √N 与物理上限逐位吻合（N=8.66 → 2.95×）；约束三条 = ISO 下限 / 快门上限 / 运动预算；纹理预算维持**细节优先 2.2px**；真机 250ms 档 `expCap:"250ms" expN:"8.33" altIso:167`，nzPreMed 0.005219→0.001688（**1/3.1**）；ISO167 = Filmix 的 ISO166
- [2026-10-01] **连拍显存与耗时**：修后 7 张全成功零丢失；独立线程代价 = 峰值 +455MB、常驻 +1.96GB（空闲释放回 ~400MB）；热机单张 totalMs 2091~3336ms；同帧 A/B CRC32 逐字节等价；`capture()` 在渲染期静默吞快门（"连按两下只出一张"根因）
- [2026-10-01] **LUT 库结构**：`app/src/main/java/com/filmkit/app/lut/`（CubeLut 解析 1D/3D/二进制、MAX_SIZE 128；LutIO 嗅探/Hald·Unwrapped/重采样；LutLibrary 内置 42 + 用户库 files/luts、id 前缀 u_）；预览降载走 `g_preview_scale`；LUT 强度上限 1.5
- [2026-10-02] **NN 路线判死但代码保留**：spike 真机 GPU **26ms/tile**（7× CPU）、12MP 0.51s 零温升、纹理保留 78–86% 优于 P0；集成五轮逐层修口径（JNI 桥 / KSigma / cvt_k×256 / 通道序 / neff 折减）后 **nnGain 与公式逐位吻合而观感零改善**（文字梯度 −24%、斑块状伪影）⇒ 跨域模型不可直接用；四次微调（监督×3 + N2N）留出集全过拟合（4 场景 vs 官方 3 万+）；代码落点 `nn/NnInferEngine.kt` + `kFsNnPack/Unpack`、`kNnAnchorS=1.0093e-3`，prefs nn_dn/nn_ab 默认关
- [2026-10-04] **编辑器与预设调研（docs/research/10 号）**：预设 schema 增量 = 颗粒强度/尺寸/密度调制、halation 阈值/强度/半径/色相、暗角、趾肩、层间效应；E1–E5 阶段；基础设施 = CameraPresets 槽位 + `nativeEngineSubmitStill/Raw` 单帧直通 + 胶卷位占位（CameraScreen:1232）
