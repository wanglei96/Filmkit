---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-22 22:20
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
- [2026-09-21] adb 安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`；手机存储 Mac 直读 `~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`
- [2026-09-18] 日志/审计导出：`/storage/emulated/0/Android/data/com.filmkit.app/files/{logs,audit}`（vivo 办公套件回传，落点 `~/Downloads/vivo办公套件/`）；未捕获异常写 `logs/crash-latest.txt` + JSONL `app.crash`
- [2026-09-21] 离线工具链：python3 + numpy + PIL + rawpy 0.27（`postprocess(use_camera_wb=True, no_auto_bright=True, gamma=(2.222,4.5))`）；mimosa 插件已禁用（`~/.zcode/cli/config.json`）；调参脚本 `tools/tuning/`

## 路径与约定
- [2026-09-18] Git 远端 `git@github.com:wanglei96/Filmkit.git` main；**CI 仅手动触发**（`workflow_dispatch`，push 自动构建顶爆 500MB Actions 配额）；出包在本地做
- [2026-09-18] APK 产物：`app/build/outputs/apk/debug/app-debug.apk` → 根目录 `filmkit-<versionName>.apk` 单文件覆盖制；**出包前 `rm -rf app/build/outputs/apk`**（防残留旧 so）；`*.apk` gitignore
- [2026-09-18] 引擎 so `libfilmengine.so`，JNI 入口 `com.filmkit.app.EngineBridge`；源码 `core/engine/src/`（jni/Bridge.cpp、render/FrameRenderer.cpp、render/Align.{h,cpp}、spike/SpikeFftMerge.cpp、film/FilmParams.h）
- [2026-09-20] shader 校验 `tools/validate-shaders.sh`（提取 FrameRenderer.cpp 的 `R"(...)"` 交 glslang，ES 3.1；本地二进制 `tools/bin/glslang`，gitignore）；**发版前必跑**
- [2026-09-18] 版本约定：发版打附注标签 `v<versionName>` 并 push；Conventional Commits（英文）；**每轮对话只打一个 tag**（中间验证包只出 APK 不打 tag）；版本号单一来源（build.gradle + BuildConfig）

## 引擎实现现状
- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；硬约束 LUT 必须在 OETF 后
- [2026-10-13] **FilmParams 契约 kCount=99**（`film/FilmParams.h:41`，阶段 0 引擎插槽起；旧记忆的 36/43/48/56/59/**98** 均已过时）：[0] exposure_ev、[1..3] wb、[29] 空槽不复用、[31..34] 外观层/LUT 强度、[35] auto_tone（默认 0）、[37] filmic_black_ev / [38] filmic_white_ev、[42] white_hl=1、[43] white_floor、[44] white_probe=0.5、[45] hr_raw、[46] rcd_cdfix=1、[47] defringe、[48] moire、[49] hl_grecon、[50] ca_corr、[51] chroma_gate=2.0、[52] cg_scope=1.0、[53] df_gate=0.0、[54] **pc3d=1（替换档默认）**、[55] pc3d_msize=0、[56] rcd_g_orig=0、[57] hl_rb_recon=0、[58] rcd_p3_split=0、[59] color_smooth、[88]/[89] 色温色调（相对量）、[90..94] 高光/阴影/白/黑/褪色、[95..97] 柔光（默认 0.0/0.35/0.70）、**[98] engine_slot**（阶段 0，0 = 内置 filmic、默认）；**按实际长度读取（下限 26）**；⚠️ 下发数组必须按 **99**，且**六处同步**：`FilmParams.h` 的 `kCount` / 同文件字段声明行 `float <名> = …; // <槽位>`（机器可读，`FilmParamKeysTest` 正则抽取）/ `fromDoubles` 防呆 / Kotlin `CameraPresets.neutral()` 与 `COUNT` / Kotlin `FilmParamKeys.bySlot`（v0.10.196 只改 C++ ⇒ 启动崩）。**契约守卫**：`python3 tools/check-filmparams-contract.py`（2026-10-13 新增，四路对拍，补"C++ kCount 单独漂移"这条既有测试不覆盖的缝）
- [2026-10-13] **`kFsFilm` = 公共模板三段 + 引擎注入段**（阶段 1）：`filmSlices()` 用四个锚点切分、`filmSource(slot)` 拼接、`init()` 编译拼接结果；**单真源 + 运行时切片**（避免人为接缝）。锚点缺失 ⇒ 退回整体字面量 + kError。**期 B 后新基线**（旧值已作废）：`kFsFilm` chars 25887 / bytes 36587 / crc32 `0x235cf3f5`；`kFsSigmoid` 16175 / 24028 / `0xfbab6a8c`；`film_d5` `0xf891c297`、`film_d5_sigmoid` `0x7c2646ef`。⚠ `FILM_SRC_BASELINE` 尚未写入守卫脚本，`--splice-selftest` 会误报。
- [2026-10-13] **filmic 黑白点默认值两层口径（别混）**：**代码默认** `[37]/[38]` = **−7.65 / +4.56**（`FilmParams.h:161-162` + `CameraPresets.kt:122-123`，darktable/PC 固化值）；**2026-10-13 真机定档**白点 = **4.0**（经 prefs 覆盖，死白起点 3.55 EV）
- [2026-09-27] **MFNR 采集**：maxImages 4 + 帧 1-3 拷贝即 close（maxImages 5+4 全程持有致 HAL 预配 125MB 闪退）；prefs `mfnr` 三档（0 关 / 1 自动暗光 ISO≥600 或 ≥25ms / 2 强制）
- [2026-09-30] **MFNR 对齐（0.10.70 起）**：最细精化层 = **1/2 域**（2×2 Bayer 平均灰度，HDR+ §4 原版），tile 64@1/2 域、金字塔 L4→L1；位移量化 2 mosaic px；`allow_l1=false` 走旧 1/4 域（A/B）；日志 `alignL1`
- [2026-09-30] **P0 时域×空间联合**（`kFsRawDenoiseFusedTs`）：4 帧 × 24 同相位邻域 = 96 样本；`uSub`（0.10.76 起默认 1）= 逐通道网格亚像素双线性（`linSubZ`），0 = 旧"round 到偶数"；诊断 `p0s`/`p0w`
- [2026-09-30] **降噪现状**：P0/P4/mismatch 全走 **σ²=A·x+B** 逐像素；强度滑杆 0–2（1.0 = 真实 σ；<1 走输出 mix）；D-1 色度 à trous 5 级（k=2.5、revert 0.20）**已超 PC**；模型优先级 = 用户导入 > 内置实测 > Camera2 > ISO 估算
- [2026-09-30] **DFT 合并（A 专项）**：`kFsMfnrFft` compute，按 CFA 平面独立、16×16 平面 tile 半重叠（步长 8 + 修改升余弦窗，窗和恒 1）；窗函数查表 `sWin`；输出 4 张 RGBA16F image2D writeonly，**目标必须 `glTexStorage2D`**；compute program 现场创建用完删除；`mfnr.fft.store_check` 自检 + 失败回落 mismatch；dftGpu 冷机 500~550ms / 热机 830~870ms
- [2026-10-01] **拍照侧引擎（v0.10.85）**：`g_renderer_still`（`std::atomic` 指针）+ 独立 `EglCore`/1×1 pbuffer/`FrameRenderer`；`nativeStillStart` 幂等、`nativeStillDestroy` **连 context 一起销毁**（空闲 10s 由 Kotlin 侧触发）；LUT / YUV 矩阵为**进程级快照**、start 时下发；JNI 成片回传统一走 `rgbaToJavaArray`（OOM → 清异常返回 nullptr）；`AndroidManifest` 开 `largeHeap`
- [2026-10-07] **紫边/高频链现状**：`hr_wavelet` 默认关（模块保留可 A/B；曾占 ≈752MB 纹理 + ≈103 全分辨率 pass/张）；HR 前置到 P0 之前、统计用 `uStat`；defringe 4 pass 半分辨率（`[47]` 默认开，**替换档下被旁路**）；强边缘色度门逐像素（`[51]` κ 滑杆，替换档下同被旁路）；ESD3D2 `[54]` **默认替换档**（v0.10.181，旁路 P4/defringe/D-1）；`[55]` 自动核真机 = **9**（builtin ext 噪声通路）；`[56]`/`[57]`/`[58]` 实验开关默认 0（判定依据见 decisions.md 10-07 条）；P3 拆双 pass 的 3a 输出纹理 = `tex_rcd_a_`（+100MB RGBA16F），性能 rcdAMs+rcdBs 实测 15~61ms

## PhotonCamera 参照事实（/tmp/photoncamera，hinnka 版）
- [2026-09-28] **PC 降噪实现**：Spatial merge 空间域 RBF（`GlesMgcRawSpatialShaders.kt:1654`）+ Sabre 结构张量各向异性核（`GlesMgcRawSabreShaders.kt:139`）+ FullResolutionDenoise 五级金字塔（`raw/MgcFullResolutionDenoise.kt`）；噪声模型 = 128-bin 半 bin 居中功率谱；去马赛克噪声传播标定 `DemosaicNoisePropagationCalibrator.kt`
- [2026-09-30] **PC 的"快门即撒手"架构**：`isCapturing` 只锁采集窗口；帧存 per-shot `PhotoCaptureContext`（`CapturedPhotoFrame(SafeImage)`，零拷贝持有 Image）；`resolveImageReaderMaxImages()` 按需放大；处理完才 close
- [2026-10-03] **PC 色度降噪架构**：`RawToYuv` → `MeasureMoire`（输入 Y 平面，输出 **1/4 分辨率 3 通道强度图**）→ 该图**替换**送给 `ChromaDenoise` 的强度（`mgc_full_resolution_denoise_jni.cpp:1021-1064`）；`ChromaDenoise` = 四级色度金字塔 + 4 level × 3 通道 × 2 branch 独立 shot/quadratic/read 表；`MeasureMoire` 本体是编译好的 Halide capsule（`mgc_demoire_capsule.bin`），公式不可读

## 工具与脚本
- [2026-10-01] `tools/still-thread-verify.py` + `still-thread-run.sh`（拍照线程验收：预览停更/burst 耗时/CRC 等价/落盘计数/帧率曲线）；`align-bench.cpp`+`align-bench-gen.py`（对齐离线基准）、`p0-subpixel-eval.py`、`check-log-json.py`（**分析前先跑**）、`check-diag-fields.py`（**出包前必跑**，六点核对 compile/link/zero/uniforms/use/delete）

## 对标数据与测量口径（10-03 → 10-05）
- [2026-10-08] **GLSL/Mali 铁律（v0.10.197-201）**：① **FilmParams 加槽位必须四处同改**（C++ `kCount` / Kotlin `DoubleArray(N)` / `CameraPresets.neutral()` / `FilmParamKeys`）+ 下发点 size 兜底 —— 只改 C++ 会 `ArrayIndexOutOfBoundsException` 装包即崩。② **`float m[9]` 数组参数与动态索引在 Mali 编不过**（`prog_=0` ⇒ 静默 return）；**多行宏 `#define` + 反斜杠同样不生效** ⇒ 定案写法 = **完全内联**。**新 pass 没效果先查 `prog_ != 0`**，并把 `X K`=下发 / `X Ok`=链接 / `X On`=执行 三字段写进日志。③ 中值对**宽色带无效**（邻域异常 >50%），只能迭代扩窗。
- [2026-10-08] **色差修复的三次纠正（v0.10.203-209）**：① PC3D 把 1~3px 伪彩**摊成 12~90px 宽带** ⇒ ±3px 探测点全落在带内（覆盖率 0.0%）⇒ v0.10.205 **把 color_smooth 前置到 PC3D 之前**（比"让 [51] 生效"更根本）。② 判据从"两侧都中性"改**局部色度峰**（`ch0 > 1.05×max(两侧)` 且 `min(两侧) < 0.5×ch0`）—— 真实边缘一侧背景本身有色度。③ 替换值从"6 点平均"→"最中性探测点"→**去饱和**（`r_t=(r−g)·0.15`、`b_t=(b−g)·0.15`，只用本像素 ⇒ 色度增加恒为 0）。**教训：任何"用邻居值替换"必须先证明邻居未被污染**。④ `[51]` 只在 P4 内执行 ⇒ 替换档（[54]=1）旁路 P4 时怎么调都不生效（日志 `cgK/cgMean/cgCov` 全零）。
- [2026-10-06] **紫边真机基线（1777 尺度，跨场景不可混用，每场景以同场景 PC 为基准）**：台灯逆光 PC 超额 ≈0 / 叠加 +2.84 / 关闭 +2.34 / 替换 +4.24；红笔亮场景：基线 +1.89 / κ0 +1.15 / defringe 关 +0.80 / **锐化关 +0.12≈PC** / 多尺度关 +0.87；**色散 = 成片域 dRG +0.9~1.0px 而 RAW 域三通道对齐 ⇒ 管线产生**。

## 相机能力与标定（仍有效）
## 相册与预设（10-11 → 10-13）
- [2026-10-13] **参数表单一来源 = `app/src/main/java/com/filmkit/app/ui/ParamControls.kt`**（`EditorParam` / `AdjustParams` / `ColorParams` / `TextureParams` / `LutStrengthParam` / `EditorSlider` / `CAMERA_NO_SLIDER_SLOTS` / `CAMERA_TUNABLE_SLOT_PARAM`）；由 PS-3 从 `EditorScreen.kt` **逐字搬移**（−429 行纯删除、零新增），拍照界面与编辑器共用 ⇒ 两页读数不可能漂移
- [2026-10-13] **相册查看页关键纯函数**（`PhotoViewScreen.kt`）：`frameAfterPageChange`（切页原子替换）、`pageAlphaAt(isSettledPage, fading, fade)`（alpha 与 zoom/pan 同一道闸）、`bitmapBelongsToPage` + `visibleBitmapFor`（归属校验，防旧页像素画到新页）、`zoomAboutCentroid(pan, centroid, center, z1, z2)`（两指中心缩放）、`previewRequestFor`（预览档 = 容器一半）、`PRELOAD_RADIUS=2`、`SPINNER_DELAY_MS=150`
- [2026-10-13] **`auto_tone`（`[35]`，默认 0）**：0 = 黑白点用 `[37]/[38]` 固定值；1 = 成对自动。**引擎的"白点高光感知"（v0.10.27 加，修过"釉面/白瓷密集高光一路白上去"）只在 `auto_tone=1` 生效** ⇒ 预设走固定白点路径，治本方向 = 让预设也走自适应

## 引擎插槽与调研（10-13）
- [2026-10-13] **引擎插槽方案 `docs/engine-slots-design.md`**：`kFsFilm:4046`（曝光/局部色调映射/filmic/gamut/外观层混在一个 pass）拆三段 —— 公共前段（`uExposure`、`uLocal*`、`uHalDomainMap`/`uDomainRatio`）/ **引擎段（影调曲线 + darktable filmic v5 gamut）** / 公共后段（`uSat`/`uSplit`/`uMono`/`uLook*` + 黑白电平）；**保持单 pass**（初始化期字符串拼接）；`kFsOut:4544`（编码+颗粒+LUT+暗角+柔光+锐化）与 `kFsStyle:5014`（编辑器链）不动。
- [2026-10-13] **引擎契约 5 项 + 参数面**：域（`workingColorSpace` + 到 D4 的 mat3）/ 曝光补偿域（Linear/Curve + defaultEV）/ **私有参数走独立有序 KV（绝不进 FilmParams）** / 资产 manifest 全量哈希 / 失败严格（绝不 identity 伪装，回落引擎 0）。只加 **`[98] engine_slot`**（0 = 内置 filmic）；阶段 0–1 硬判据 = **`[98]=0` 时同帧 CRC32 逐字节等价**。
- [2026-10-13] **PC 引擎插槽一手事实**（`/tmp/pc-tone` @ `b9cf590`）：**9 个引擎**（`RawRenderingEngine.kt:10-74`：AdobeCurve 0 / **AgX 1** / Spektrafilm 2 / **DarktableSigmoid 3** / DarktableFilmic 4 / Hncs 6 / Lumix 7 / Canon 8 / Leica 10；5 与 9 空缺，Fuji 于 `56d33aa` 移除）；公共模板 `RawEngineTonePass.kt:417-505`（引擎只注入 uniforms/profile/functions 四段，**输出契约线性 RGB**，OETF 只做一次）；`DarktableSigmoidToneAlgorithm.kt` 有可逐行对照的 sigmoid GLSL（`paper_exposure=0.354355423`、`film_fog=0.00142637086`、`film_power=1.5`、`paper_power=1.0`）。

## 引擎插槽阶段 2/3 与全局开关（10-14）
- [2026-10-14] **阶段 3 契约 `EngineScope.kt`**（`app/src/main/java/com/filmkit/app/camera/`，架构师落、三卡共用）：`FILMIC_OWNED_SLOTS` = {5..10, 35, 37, 38, 40}；`NEUTRALIZE` **14 槽** = `28`=1.0 / `20`,`21`,`22`=1.0 / `36`=0.0 / `35`=0.0 / `5..10`=`LATITUDE_UI_PIVOT`(0.01) / `37`,`38`=`FILMIC_BLACK/WHITE_EV_PIVOT`(−7.65/+4.56)；`KNOWN_ENGINE_SLOTS`={0,1}；`applyEngineScope(p, slot)`（slot∈{0,未注册} ⇒ **返回同一实例**，零回归）；`effectiveEngineSlot(presetSlot, override)`。
- [2026-10-14] **引擎指纹（判定引擎真在工作）**：引擎 0 `film_d5` = `0x589f8364`；引擎 1 链上 = **`0x8fdb4e38`**；**空转（`uToneK=0`）= `0x189e4126`**（与"引擎 0 + uToneK=0"逐字节相同）。**日志判据 = `raw.render` 的 `tone=1.0`**（P0 未修时是 0.0）。引擎 1 shader 源码 = 23211 B / 15781 字符 / `0xbf298386`。

## 10bit HEIF 交付链（10-15）


## 期 B 域空间（10-19）

- [2026-10-19] **引擎 1 的 7 个 `uSig*` 全是曲线形状常量**（`uSigWhiteTarget` / `uSigBlackTarget` / `uSigPaperExposure` / `uSigFilmFog` / `uSigFilmPower` / `uSigPaperPower` / `uSigHuePreservation`，来自 PC 的 `DT_SIGMOID_*`）—— **里面没有"对比度"** ⇒ 引擎 1 此前完全没有对比度实现（滑杆无效的真因）。

## 色相路线与 HDR（10-19）


## HDR 交付（10-20）

- [2026-10-20] **gainmap 数学两版可切**（`setGainmapMath`，prefs `gainmap_math`）：0 = PC（offset **1e-4**，默认）/ 1 = 官方（offset **1e-7** + 暗部 `sdr<2/255` 钳 gain≤2.3）。都归一化到固定 `[0.25, 4.0]`。真机证据：官方版 gainmap JPEG **570KB**（正常几十 KB）、`>=0.90` 像素 **1.61%** 散布 ⇒ "太阳把栏杆覆盖"。
- [2026-10-20] **App 内 HDR 显示三条件**：① `BitmapFactory` + **`inSampleSize=1`**（>1 丢 gainmap；`ImageDecoder` **不解析 MPF 多图**）② **`context.findActivity()`** 逐层解包（`as? Activity` 在 Compose 里拿不到）③ `window.colorMode = COLOR_MODE_HDR`，**不检查 `configuration.isScreenHdr`**（该值在 X300 上恒 false 而系统相册能显示 HDR）。
- [2026-10-20] **导出 SDR 版**：`nativeExtractSdrBase` 用 `uhdr_dec_get_base_image` **无损取 base**（不重压）⇒ 落盘 `…_SDR.jpg`。
- [2026-10-20] **HDR 与 PC 的差距（同场景实测）**：SDR base 平均亮度我们 **85.2/98.4** vs PC **50.7**（1.7~1.9×）；`>=240` 占比 **3.38%/2.22%** vs PC **0.37%**；**HDR 输出几乎相同**（P90 0.714 vs 0.697、P99 0.861 vs 0.866）⇒ PC = 暗 base + 大增益，我们 = 亮 base + 小增益，高光在 8bit 截断处已丢信息（**归因自动曝光策略，用户已定单独开期**）。

## 自动曝光（10-20，渲染端本张统计）

- [2026-10-20] **AE 判据与常量**（`core/engine/src/render/AeMetering.h`）：`kAeRawWhiteEv 2.438307`（= Kotlin `ExposurePlanner.WHITE_POINT_EV`，两处必须同改）· `kAeMiddleGrey 0.1845` · headroom `0.1` · 限幅 ±2EV（欠曝上限与 gainmap `[0.25,4.0]` 对齐）· 高光判据 = **P99.5**（0.5% 裁切，PC legacy matcher 口径）。决策 `min(ev_mid, ev_hl)`，高光优先。
- [2026-10-22] **teq 9 档曲线的档位映射**（`setTeqCurveFromAe`）：第 i 档对应 `q = i−8` EV。云/镜面反光 q≈−0.5（落 `curve[8]` 白点档）、天空主体 q≈−2.0（落 `curve[6]`）。**压深只能给第 6 档**，第 8 档必须为 0（否则白云被压成乌云）。大面积高光压深 = `-(p95_rel_white + 0)`，其中 `p95_rel_white = log2(aeP95/0.1845) + aeEv − log2(1/0.1845)`。
- [2026-10-22] **局部对比度实测基线**（1777×2360，同区域柜门区）：rawpy 单帧（无融合/降噪/锐化）**19.4%** / 我们成片 **17.6%** / PC **25.9%**（绝对值 σ 7.59 / 13.52 / 20.61；亮度 Y 39.1 / 76.9 / 79.5）。⇒ 我们的链路只比“完全不处理”低 1.8 个百分点，**PC 高出 6.5 个百分点**（它在链路里增加了细节）。

## X300 相机 id 与 ISZ（10-22 晚实测）

- [2026-10-22] **X300 后置 camera id 结构**（遍历 0..10）：0 = 逻辑相机（0.67–10x）、**2 = 物理主摄**（6.25mm/23.6mm，RAW 4080×3072，`SupportSATRemosaic=1`、`cfaRemosaicType=4`）、6 = 逻辑相机（0.6–100x）、3 = 物理长焦（13.3mm/70.3mm，RAW 4096×3072）、5 = 物理潜望（31.25mm/165mm）、4 = 物理超广（2.32mm/15.4mm，RAW 4080×3060）、1 = 前置。**三个 23.6mm = id 0/2/6**。
- [2026-10-22] **ISZ 可下发的 vendor key（X300，set 全成功）**：`com.mediatek.control.capture.remosaicenable`、`…capture.seamless.remosaicenable`、`…insensorzoomfeature.insensorzoomIsInternalAP`、`vivo.control.seamless.roiRemosaic`、`vivo.control.seamless.remosaic.enable`、`android.control.zoomMethod`；但 2.115x 时 `raw.meta` 仍 `in=4080x3072 crop=0,0,4080x3072`，且 `vsdofFeatureOpticalZoomSets=0`（无档位暴露）。
- [2026-10-22] **PC 的「保持原分辨率」实现**（`/tmp/pc-bjzhou` @ 27d4f78）：`raw/RawDigitalZoomResampling.kt` 拍摄时按 `activeSourceBounds` 记 `rawPhysicalOutputWidth/Height`；`RawDemosaicProcessor` 以该尺寸为 reference 建 `RawOutputGeometry` ⇒ 输出 = 物理尺寸 × outputScale；重采样 = Lanczos-3（6×6 tap，`processor/GlesLanczosResampling.kt`）或 MGC RAISR（只支持整数 2x，选中即锁 2x）；`MgcFinishResolution` 另按 output/crop 比值做 downsample→guided upsample。**设置默认关**。
- [2026-10-22] **相册/删除/缩略图事实**：`MediaStore.createDeleteRequest` / `createTrashRequest` 均 API 30+，vivo 上 trash 落成真删；`mediaStillExists` = 单条 URI query + `is_trashed` 列；`saveJpeg` 现在在写盘 + `IS_PENDING=0` + EXIF 全完成**之后**才发布 `lastPhotoUri`（原先 insert 后就发 ⇒ 缩略图偶发永久空白）；`ThumbCache` 全方法 `@Synchronized`。

## AE 实测与定性（10-23）

- [2026-10-23] **EV 补偿在 X300 生效**（`ae.probe` 诊断包）：+1EV ⇒ ISO 490→931、+2EV ⇒ sec 0.010→0.030 + ISO 802→1630、−1EV ⇒ ISO 490→242；回读 `resSteps` 与请求一致（个别帧滞后一次）。v0.7.8 的"几乎不执行"只对静拍 `AE_MODE_OFF` 成立。
- [2026-10-23] **同机位 EXIF 对拍**（`152436` vs `PhotonCamera_152446.无`）：快门 2.20 vs 2.02 ms、ISO 50 vs 50、f/1.7 vs 1.7 ⇒ **采集一致**；成片 p5/p25/p50/p75/p95 = 7.2/34.3/98.7/174/195 vs 12.2/65.6/171.4/230/232 ⇒ PC 是"中暗部整段提起"，属 tone mapping。
- [2026-10-23] **提亮对天空饱和的量化**：baseEv 0→0.7 时天空 L* 75.1→84.0、蓝天 C* p90 9.7→7.1；PC 同 L* 84 时 p90 15.8（其输入色度更高，不能据此断定我们压缩过强）。
- [2026-10-23] **鲁棒中灰 vs 几何均值**（186 样本）：`rob/geo` 中位 +0.16EV、p25 −0.05、p75 +0.33、范围 −0.60~+0.51，68% 场景 robust 更高（⇒ AE 更暗）。这是"不接鲁棒测光"的量化依据。
- [2026-10-23] **PC 的 sigmoid 与我们的实现逐行一致**（`DarktableSigmoidToneAlgorithm.kt:80-127` vs `FrameRenderer.cpp:7243-7291`，含 `preserveSigmoidHueAndEnergy`、通道排序、energyTarget 两支求解）；`uSigWhiteTarget` 两边都是 1.0 ⇒ 差异不在曲线，在输入色度/CCM。
- [2026-10-23] **PC 默认渲染引擎是 AdobeCurve**（`UserPreferencesRepository.kt:144`），sigmoid 只是选项 —— 但用户实拍时手动切成了 sigmoid，所以此条**不是**本轮差异的解释。
- [2026-10-23] **相册编辑器饱和度失效根因**：`EditorEngineSession` 出口调 `engineScoped` ⇒ `applyEngineScope` 把公共段 `[20]/[21]/[22]` 置 1.0。修复 = `applyEngineScopeForEditor`（跳过公共段）。
- [2026-10-24] **阴天过曝实测**（`090816`，baseEv 0.3）：`aeP50 0.52`、`aeSatRaw 41%`、`aeBrkSat 0%`、`aeEv 0`、`hi240 35.9%`、`teqHl −1.41`。成因 = 0.12.63 的长帧过曝放宽在 ISO 贴底时对阴天同样生效；短帧虽未饱和，但长帧过曝幅度超出其覆盖能力。
- [2026-10-24] **bracket 短帧 −3EV 的噪声代价**：短帧 `ratio 8` ⇒ 归一化 ×8、噪声 ×8，高光区（长帧饱和处）明显；收到 `ratio 6` 后消失。
- [2026-10-24] **白点 4.8 与 6.22 的差别**（夕阳窗景 `173032`）：6.22 时 `aeEv` 冲到 2.0、天空 p95 246、`>240` 占 8.6%；4.8 时 `aeEv` 落到 ~1.0、p95 回到 ~210。

## 测光与 HDR 对标（10-24）

- [2026-10-24] **逆光同机位实测**（17:19，点测光）：我们天空 227/221/216、`hi240 3.35%`、mean 51.6；PC 天空 196/187/183、`hi240 0.22%`、mean 62.2、B/G 0.976 vs 0.978 ⇒ 白平衡不是主因，**单帧装不下 12EV（室内 5 + 天空 7），是 bracket 问题**。
- [2026-10-24] **测光实现位置**：`MeteringTargets.kt`（5 单测）；`CameraController.setMeteringMode/setAeLock/setHighlightMeterPoint`；`Bridge.cpp` `g_meter_manual`/`g_meter_focus_x/y` 进程快照；`SPOT_AE_FRACTION=0.06`、`HIGHLIGHT_AE_FRACTION=0.16`；高光点来自 `histogramFrom` 32×32 网格。
- [2026-10-24] **PC HDR 两套体系（10-24 晚重查）**：YUV bracket（`shouldUseJpgMaxHdrCapture` 有 `!isRawCapture` 门槛）= 0EV×N 堆叠 + 侧帧各 1 直通 + Mertens（`GlesYuvStacker.kt:3451`：highlightValidity 0.960/0.995、deghost 黑门 0.055~0.145 白门 0.865~0.975、census、referenceStructure；三级归一回退）；**RAW 模式** = `RawmaxExposurePlanner`（实测曝光中位数分组 base/normal/short/long）+ `processBurstRaw` 全帧进 MGC 融合（`enableHdrFusion`、`supportsBracketExposure`）+ HDR 参考场（RawHdrReferencePass，HDR/SDR 比值）。**帧数 = 多帧设置数 + 2**（`resolveHdrBracketZeroEvFrameCount`），提帧数只提 0EV 降噪。
- [2026-10-24] **我们的 HDR+ 包围曝光（P3 定稿）**：帧序 0/0/+2.2/−1.5（`HdrBracketConfig` 常量 HDR_LONG_EV=2.2/HDR_SHORT_EV=−1.5/HDR_LONG_MAX_SHUTTER_NS=10ms）；`planBracketExposure` 照抄 PC planManualExposure；**侧帧 ISO 钳模拟顶 3200**；引擎 frame_scale[i]=2^−ev[i] 全帧进对齐+DFT（satW 饱和权重接管高光、mismatch 拒运动）；per-frame σ²=A·s·x′+B·(gr·s)²；日志 `hdrFs[]`（归一系数）/`bracket.actual`（实测 EV+sat）；JNI `hdrEv/hdrGainArr/hdrHighIdx`。0.12.84 UI 两档化默认开，旧 bracket 退役。
- [2026-10-24] **gainmap 中灰基线 1.1**（`FrameRenderer.cpp:7094`）从 SDR base 反推、无长帧参考；PC gainmap 是真实 +2.2EV 数据 ⇒ P4 改造点。

## P4 基线与徕卡调研（10-25）

- [2026-10-25] **`tools/hdr-baseline-eval.py`**：JPEG/R 无损解析（MPF 对照 vendored libultrahdr）+ base/gainmap/HDR 重建统计（重建 = 官方 applyGain，gamma=1、W=1 满档）；`--json` 可复跑。报告 `docs/reports/hdr-baseline-20261025-REPORT.md` + json。
- [2026-10-25] **P4 现状（0.12.85+）**：`kFsGainmap` 三档（0=真实比值默认 / 1=旧 sceneY 塑形 / 2=官方公式）；`gainTrue` = hdrY×(0.1845/ae_p50_) ÷ sdrY，clamp[1,4]；新 uniform `uSceneMid`（ae_p50_ 同 submit 下发）；prefs `gainmap_math` 三档循环（迁移 1→2）。
- [2026-10-25] **旧版 gainmap 指纹（已被取代）**：`ratioFromScene = clamp(max(1.1, sceneY×1.6), 1.1, 2.5)`（SDR base 不参与）；码值 136 针 96.5%、212 针 2.5 封顶；0.12.85 后 = 128 针（ratio=1）、255 针（4x 顶格）。
- [2026-10-25] **PC M9 配方（徕卡一瞬对标的一手）**：`/tmp/pc-full/app/src/main/assets/leica/m9/`（m9-1_216.upd 固件提取，manifest 审计链 + SHA256）；`PROCESS/LUTS.bin` 427KB = 20×2048 曲线 + R≥G 分支双 3×3 矩阵（行和=1，matrixIndex=cs×5+sat）+ 双光源标定（illum 17/21）；实现 `LeicaProfile.kt`。矩阵已解码，**曲线域语义不明**。
- [2026-10-25] **M9 彩色指纹库**：`docs/research/leica-m9-samples/`（11 彩色 + 8 黑白，IT之家/爱范儿网络样张）；指纹 = p25≈21、p95 中位 165、≥240 中位 0.1%、色彩像素 C*P90 中位 24.5（暗部也带色）、色相双向（30~90° 暖 + 240~270° 青蓝）。
- [2026-10-25] **方向锁**：MainActivity `screenOrientation="portrait"`（0.12.86 起）；UI autoRotate（PhotonCamera 式）未立项前窗口恒竖。
