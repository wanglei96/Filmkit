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
- [2026-10-02] **NN spike 包沿革**：251（`0.10.100-nnspike`，用户已装，GPU 被 CompatibilityList 拒）→ **252（`0.10.101-nnspike2`，GPU 强制版，功能与已装相同仅版本号修正）**；验证包不打 tag
- [2026-10-01] **版本沿革速查**：183（0.10.31 高光线）→ 184–188（0.10.32–36 C 专项）→ 189–221（0.10.37–0.10.69 A/D 专项）→ 222–234（0.10.70–0.10.84 对齐精度/亚像素/性能）→ 235–242（0.10.85–0.10.92 拍照独立线程/连拍 OOM/空闲释放）→ **243–250（0.10.93–0.10.99 预览降载/单帧迁移/M4 LUT 导入导出/相对滑杆）**
- [2026-09-29] **Camera2 SENSOR_NOISE_PROFILE 真机形态**：Array<Pair<Double,Double>> = 4 个 (S,O) **标量对**；SDK 签名 `Pair<Double, Double[]>` 与真机不符；`as? DoubleArray` 对装箱数组永败
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

- [2026-09-30] **端到端耗时账**（0.10.80/81，第 2 张，totalMs 1607）：采集 258 + DNG 落盘 144 + 上传与金字塔 37 + **CPU 对齐 345**（3 帧 mfnrAlign 210 + 4 帧 capturePyr 165）+ **DFT 571** + **后段 633**（RCD 335 + 高光重建 245 + 降噪 40）+ 后台编码 332（与前面并行）；快门间隔 0.54s
- [2026-09-30] **raw.render 诊断字段全集**：`p0s`（96/9）、`p0w`（中心权重和，期望 26.1）、`cms`/`cmsK`、`nzPre`/`nzPost`（同相位 lag-2 差分 std/√2，**去信号**）、`nzPreMed`/`nzPostMed`/`nzPreP90`/`nzPostP90`/`nzN`（全图 48960 tile 统计）、`rmsPre`/`rmsPost`（tile RMS，**信号主导不可作判据**）、`dftUp`/`dftDsp`/`dftRb`/`dftGpu`、`alignMs`/`alignRbMs`/`alignCpuMs`/`alignL1`、`abNzPostMed`/`abP0w`（同帧 A/B 对照遍）、`nzSrc`/`nzS`/`nzO`、`wb`、`w99/w999/wmax/cp`
- [2026-09-30] **PC 对标（同场景同曝光 ISO 1386 vs 1402）**：暗部 σY 1.323 vs 0.586（**差 2.26×**，旧数据 4.7×）、中间调 1.050 vs 0.610；暗部 σC 0.338 vs 0.787（**好 2.3×**）、中间调 0.322 vs 0.935（好 2.9×）；边缘 P99.5 12.11 vs 10.90；纹理保真比 0.92
- [2026-09-30] **LSC 实测参数（X300 主摄）**：网格 17×13，center=1.000，corner=3.874（BR 3.701）；阻尼后角落 3.179；硬 knee 的斜率突变在**列 1 = 原图 x≈181 = W/17**（交付 JPEG x=105）
- [2026-09-30] **量化/dither**：成片最平坦块 44.7% 相邻像素相同、|Δ| 中位恰 1.000；TPDF ±1LSB → 35.6%；JPEG q95 吃掉一半 dither 效果（26.9%→31.2%），加大幅度在 q95 下饱和无效；4:2:0 只损失 6–8% 色度细节

## 拍照线程实测数据（2026-10-01）

- [2026-10-01] **连拍崩溃现场**：`Fatal signal 6 (SIGABRT) in tid 28125 (FilmStill)`；OOM 报 `Failed to allocate 50135052 … target footprint 248965720 / growth limit 268435456`（237MB/256MB）；修复后连拍 `burst[still]` 7 张全成功、`queued/saved` 4/4 零丢失
- [2026-10-01] **Graphics 内存**（`dumpsys meminfo`，逐 2s 采样）：预览基线 392MB；**开独立线程峰值 3011MB、拍完残留 2418MB**；**关独立线程峰值 2556MB、拍完回落 459MB** → 独立线程的代价 = 峰值 +455MB、常驻 +1.96GB（空闲释放后回到 ~400MB）
- [2026-10-01] **帧率**（`render.fps` = 逐秒实际渲染帧数）：空闲 30~32fps；拍照期间最低 **13~25fps**；`preview_gap` 只在采集期记到过 467ms（阈值 400ms）
- [2026-10-01] **合成耗时**（热机单张）：`totalMs` 2091~3336ms（`dftGpu` 855~922、`passMs` 711~2021、`alignCpuMs` 185~737）；冷机首次可达 5133ms
- [2026-10-01] **同帧 A/B 画质等价**：两条路径 CRC32 `3615113902` 完全相同、均值 109.455、`p0w` 19.093、`nzPreMed/nzPostMed` 逐项一致

## LUT 资产互通与预览降载（2026-10-01）

- [2026-10-01] **LUT 库结构**：`app/src/main/java/com/filmkit/app/lut/`（CubeLut 解析 1D/3D/二进制、MAX_SIZE 128；LutIO 嗅探/Hald·Unwrapped/重采样/writeCube/writeHaldPng；LutLibrary 内置 42 + 用户库 files/luts、id 前缀 u_）；日志 lut.import/lut.export/lut.delete/lut.parse
- [2026-10-01] **预览降载**：`g_preview_scale` 进程级 atomic（FrameRenderer static）；JNI nativeEngineSetPreviewScale；日志 render.preview_scale、preview.frame 加 rs 字段；触发链在 CameraController（快门降 0.5 / 合成完升 1.0，15s 兜底）
- [2026-10-01] **单帧静拍直通**：nativeEngineSubmitRaw/Still 加 useStill 参数；StillRenderClient.submitRawBuf/submitStillBuf（失败自愈重建重试一次）；render.single_done 日志；bindLut 强度上限 1.5（>1 沿色差外推）

## NN 路线实测（2026-10-02）

- [2026-10-02] **NN spike 真机数据（X300, PMRID 512tile FP16, 20 块=12.58MP）**：GPU delegate 单块中位 **26ms**（p90 28）、全图 **0.51s**、**24.86MP/s**、零温升；CPU XNNPACK×4 181ms、3.65s、3.45MP/s、零温升
- [2026-10-02] **PMRID 离线质量（X300 噪声模型合成 + clean 参照）**：纹理保留率 4px 78%（暗光 ISO640）/86%（白天 ISO3200）、8px 91–97%、平坦 σ −77~80%、2px 增强 1.7–2×；对比锚点（10-01 三 APP 同场景 4px）：PC 2.24 / Filmix 2.19 / 我们 1.47
- [2026-10-02] **PMRID 移植口径（10-02 晚经 256/257 两次修订，最终版）**：输入 = img01 × cvt_k × 256，**cvt_k = S_train·neff/S_input**，S_train = 官方 K(1600)/V = **1.0093e-3**（img01 域）、neff = 4、clamp[0.25,16]；**通道序 = 官方语义 ch0=R/ch1=Gr/ch2=Gb/ch3=B**（`nnSemTables` 按 CFA 给相位；打包图空间朝向不翻转）。NHWC [1,512,512,4]、tile 512/步进 496/重叠 16、FP16 tflite 2.1MB（守卫 0.76%）；Filmix 曝光 = ISO166+1/4s（+3.6EV 进光）
  - 修正沿革：255 用 `S_anchor/S_iso`（X300 S(1600)=4.5e-4）且通道序 (B,Gr,Gb,R)；256 修 anchor 为训练口径并 ×neff；257 修通道序
  - ~~01 域 × 959/256~~（0.10.102）与 ~~cvt_k=S_anchor/S_iso + BGGR 语义~~（0.10.104）均已推翻
- [2026-10-02] **tools 新增**：`tools/midband-attrib.py`（三图/分组归因：EXIF 聚类 + 配准 + 归一带通 + 分尺度噪声 + CIELAB）、`tools/nn/convert_pmrid.py`（torch→onnx→tf→tflite FP16 + 数值守卫）、`tools/nn/quality_eval.py`（合成噪声 + 分块推理 + 保留率表；⚠ 其 959/256 口径注释已被官方源码推翻）

## NN 与对齐工具实测（10-02，NN 已判死）

- [2026-10-02] **NN 集成代码落点（保留，默认关）**：`app/.../nn/NnInferEngine.kt`（NnInfer 常驻线程、GpuDelegate 强制 + CPU 熔断、inferTile JNI）、`FrameRenderer.{h,cpp}`（kFsNnPack/Unpack、runNnPass、nnSemTables、**kNnAnchorS=1.0093e-3**、cvt_k×neff、tex_nn_*）、`Bridge.cpp`（nnBridgeInfer 首调自初始化、nativeRunNnRoundtrip）；prefs nn_dn/nn_ab 默认关；raw.render 含 nn/nnOk/nnFail/nnMs/nnTiles/nnGain（check-diag 已登记）
- [2026-10-02] **对齐工具实测**：位移误差 0.0115px（合成 6 帧）、噪声 ×3.22、异场景剔除 wn 0.304 vs 同场景 0.996；真实手持 8 帧 ×2.16；93 帧/5 场景中 4 个可用（场景 5 位移 103–114px 报废）
- [2026-10-02] **旧噪声模型来源**：DNG tag 51041 = Camera2 SENSOR_NOISE_PROFILE，顺序 (R,G,B)、G 取中间项；本批 S_G = 1.291e-3…2.105e-4（ISO 4781…216）；CFAPattern=[[1,0],[2,3]]=GRBG（**已被下述三摄 GCam 标定取代**）

## 曝光决策与降噪修正实测（10-02 晚）

- [2026-10-02] **离线曝光杠杆**（`tools/exposure-lever-eval.py`，场景 1 ISO4781/38.46ms，clean = 15 帧对齐平均）：解析 σ 收益 1.42/2.00/**2.95×**（N=2/4/8.66）与 √N 逐位吻合；`tex_keep` 仅与帧内模糊 d 有关（2px 0.956 / 4px 0.918 / 8px 0.842）；细节优先口径决策边界 **d\*≈2.2px**；1 mosaic px ≈ 0.017°
- [2026-10-02] **真机 250ms 档**：`expCap:"250ms" expN:"8.33" altIso:167 altSec:0.2500`（HAL 逐位执行）；nzPreMed 0.005219→0.001688（**1/3.1**）、成片域 0.002104→0.000417（1/5.0）；**ISO167 = Filmix 的 ISO166**
- [2026-10-02] **三图同场景定量（盘沿亮白 Y≈211–224；Filmix 局部配准 SAD 8.6 / PC SAD 41 不可比）**：σY 我们 **4.48** / Filmix 1.72 / PC 0.92；中间调（茶几 Y≈162）我们 **1.06（三家最好）** / 2.67 / 1.12；碗内壁 lag-1 自相关 0.939 ≈ Filmix 0.938 ⇒ **真实纹理非噪声**
- [2026-10-02] **σ 修正前后真机指纹**：修正后 `nzPreP90→nzPostP90` = 0.002474→0.001845 / 0.015045→0.010735 / 0.011360→0.009010（**降 21~29%**），修正前只降 4%（0.006035→0.005806）；修复前 `p0A/nzS = 0.099`（σ 仅真值 0.31×）
- [2026-10-02] **三摄 GCam 标定（`assets/noise_models/`）**：主摄 HPB A=2.72e-7 dg_ref 6400（**100 张实测**：暗场 50 + 亮场 50、相邻帧差法）、超广 JN1 A=3.07e-7 dg_ref 1600（**同传感器借用 NubiaZ50Ultra，非本机实测**）、长焦 LYT602 A=1.05e-6 dg_ref 3200；`BUILT_IN` 原先**无主摄条目**（主摄一直走 HAL cam2，其 G 比 R/B 低一半）；真机 `src:"builtin"` ×3 验证通过
- [2026-10-02] **超广角桶形畸变**：221313 竖直边拟合直线残差中位 **50.7px**（图宽 887 = **5.7%**）、两侧都偏 = 系统性；代码 `grep DISTORTION|LENS_INTRINSIC` 零结果（RAW 流 HAL 不做几何校正）
- [2026-10-02] **MFNR 耗时（真机）**：4 帧渲染 `render.burst_done ms ≈ 3.9–4.3s`（align 835 + fuse 1278 + dftGpu 614）、采集 257–313ms；`capture()` 在渲染期**静默吞快门**（"连按两下只出一张"的根因）
