---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-20 09:45
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

## 引擎实现现状
- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；硬约束 LUT 必须在 OETF 后
- [2026-10-13] **FilmParams 契约 kCount=99**（`film/FilmParams.h:41`，阶段 0 引擎插槽起；旧记忆的 36/43/48/56/59/**98** 均已过时）：[0] exposure_ev、[1..3] wb、[29] 空槽不复用、[31..34] 外观层/LUT 强度、[35] auto_tone（默认 0）、[37] filmic_black_ev / [38] filmic_white_ev、[42] white_hl=1、[43] white_floor、[44] white_probe=0.5、[45] hr_raw、[46] rcd_cdfix=1、[47] defringe、[48] moire、[49] hl_grecon、[50] ca_corr、[51] chroma_gate=2.0、[52] cg_scope=1.0、[53] df_gate=0.0、[54] **pc3d=1（替换档默认）**、[55] pc3d_msize=0、[56] rcd_g_orig=0、[57] hl_rb_recon=0、[58] rcd_p3_split=0、[59] color_smooth、[88]/[89] 色温色调（相对量）、[90..94] 高光/阴影/白/黑/褪色、[95..97] 柔光（默认 0.0/0.35/0.70）、**[98] engine_slot**（阶段 0，0 = 内置 filmic、默认）；**按实际长度读取（下限 26）**；⚠️ 下发数组必须按 **99**，且**六处同步**：`FilmParams.h` 的 `kCount` / 同文件字段声明行 `float <名> = …; // <槽位>`（机器可读，`FilmParamKeysTest` 正则抽取）/ `fromDoubles` 防呆 / Kotlin `CameraPresets.neutral()` 与 `COUNT` / Kotlin `FilmParamKeys.bySlot`（v0.10.196 只改 C++ ⇒ 启动崩）。**契约守卫**：`python3 tools/check-filmparams-contract.py`（2026-10-13 新增，四路对拍，补"C++ kCount 单独漂移"这条既有测试不覆盖的缝）
- [2026-10-13] **`kFsFilm` = 公共模板三段 + 引擎注入段**（阶段 1）：`filmSlices()` 用四个锚点切分、`filmSource(slot)` 拼接、`init()` 编译拼接结果；**单真源 + 运行时切片**（避免人为接缝）。锚点缺失 ⇒ 退回整体字面量 + kError。**期 B 后新基线**（旧值已作废）：`kFsFilm` chars 25887 / bytes 36587 / crc32 `0x235cf3f5`；`kFsSigmoid` 16175 / 24028 / `0xfbab6a8c`；`film_d5` `0xf891c297`、`film_d5_sigmoid` `0x7c2646ef`。⚠ `FILM_SRC_BASELINE` 尚未写入守卫脚本，`--splice-selftest` 会误报。
- [2026-10-13] **离线执行器覆盖 = P0→RCD→CCM→FILM（+PC3D）**（`tools/engine-shader-offline.py`）：产物 `p0/rcd_g/rcd_d3/ccm_d4/**film_d5**.npy`；**FILM 段 2026-10-13 补齐**（此前文件头声称已接入而实现没有 ⇒ 阶段 1 判据曾无法执行，用户拍板 A 方案先补链），配套 `tools/filmic-spline-ref.py`（`computeFilmicSpline` 逐行对照）+ `tools/check-film-offline.py`（自检：solve5 对拍 numpy / CPU 复现 vs GPU 实跑差 2.44e-04）+ `tools/film-offline-REPORT.md`。**基线**（`docs/reports/stage0-engine-slot-BASELINE.md` §3.3）：`film_d5` 默认口径 `crc32=0x589f8364`、白点 4.0 口径 `0xd46d7b5e`。**仍不在链内**：HR 高光重建（真机 FILM 的输入是它 ⇒ 离线基线不能判成片观感）/ `uLocal*` / PC3D 的 LSC 与噪声标定 / `kFsOut` / MFNR / 同帧成片 CRC32（需真机 adb）
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
- [2026-09-30] **PC 的线程/context 模型**：预览在 `CameraGLSurfaceView`（GLSurfaceView 自有 GL 线程）；各后处理器**各自 `eglCreateContext`**（`GlesMgcRawSpatialStacker:9537`、`GlesMgcRawBaseFrameSelector:708`、`OglBokehProcessor:1796`）——这是它拍完不卡预览的原因
- [2026-09-24] 我们的 DNG 未写 BaselineExposure（vivo DngCreator 不写该 tag）；DNG 无 ForwardMatrix1/2（CameraCharacteristics 给全）；`.hcurve`（哈苏，不可复制）= 68B 头 + 65536×uint16 LE
- [2026-10-03] **PC 色度降噪架构**：`RawToYuv` → `MeasureMoire`（输入 Y 平面，输出 **1/4 分辨率 3 通道强度图**）→ 该图**替换**送给 `ChromaDenoise` 的强度（`mgc_full_resolution_denoise_jni.cpp:1021-1064`）；`ChromaDenoise` = 四级色度金字塔 + 4 level × 3 通道 × 2 branch 独立 shot/quadratic/read 表；`MeasureMoire` 本体是编译好的 Halide capsule（`mgc_demoire_capsule.bin`），公式不可读
- [2026-10-05] **PC ESD3D2（PC3D）**：核尺寸由噪声模型算 `min(7+(int)ks−(int)ks%2, 21)`（本机 ⇒ 11），两趟 + 引导上采样（`esd3d2_steered.glsl` / `ESD3D2.java` / `convdiff.glsl` / `guidedupsample.glsl` / `GLUtils.gaussdown`）；**`cap=0.2` 在 PC 里从不触发（实测吃满率 0.00%）**；**PC 成片本身也是 4:2:0**；PC 的 `CorrectingFlow` 只对 OnePlus 生效（样片 vivo 走 passthrough）

## 工具与脚本
- [2026-10-01] `tools/still-thread-verify.py` + `still-thread-run.sh`（拍照线程验收：预览停更/burst 耗时/CRC 等价/落盘计数/帧率曲线）；`align-bench.cpp`+`align-bench-gen.py`（对齐离线基准）、`p0-subpixel-eval.py`、`check-log-json.py`（**分析前先跑**）、`check-diag-fields.py`（**出包前必跑**，六点核对 compile/link/zero/uniforms/use/delete）
- [2026-09-30] 其余 `tools/`：validate-shaders.sh、check-fft-tile-cover、chroma-scale-eval、texture-fidelity-eval、denoise-strength-eval、ab-100pct、chroma-denoise-eval、tone-scan、gen-lut-assets、dng-color-dump、color-metrics、ccm-chain-ab、check-srgb-oetf、check-lsc-mapping、lut-audit、tuning/
- [2026-09-22] 内置 LUT 资产 42 个 `.lut`（uint8、33³、4.3MB）与 `~/Downloads/LUT/滤镜.cube/` 逐点一致；`docs/research/` = 12 个编号调研（00 活入口 / 01–08 冻结证据附录 / 09 频域降噪 / 10 ARRI 引擎计划 + 内建编辑器与预设）+ filmulator 专题 + README + `90-工作流原始转储`（勿删，含独有内容）

## 对标数据与测量口径（10-03 → 10-05）
- [2026-10-08] **GLSL/Mali 铁律（v0.10.197-201）**：① **FilmParams 加槽位必须四处同改**（C++ `kCount` / Kotlin `DoubleArray(N)` / `CameraPresets.neutral()` / `FilmParamKeys`）+ 下发点 size 兜底 —— 只改 C++ 会 `ArrayIndexOutOfBoundsException` 装包即崩。② **`float m[9]` 数组参数与动态索引在 Mali 编不过**（`prog_=0` ⇒ 静默 return）；**多行宏 `#define` + 反斜杠同样不生效** ⇒ 定案写法 = **完全内联**。**新 pass 没效果先查 `prog_ != 0`**，并把 `X K`=下发 / `X Ok`=链接 / `X On`=执行 三字段写进日志。③ 中值对**宽色带无效**（邻域异常 >50%），只能迭代扩窗。
- [2026-10-08] **色差修复的三次纠正（v0.10.203-209）**：① PC3D 把 1~3px 伪彩**摊成 12~90px 宽带** ⇒ ±3px 探测点全落在带内（覆盖率 0.0%）⇒ v0.10.205 **把 color_smooth 前置到 PC3D 之前**（比"让 [51] 生效"更根本）。② 判据从"两侧都中性"改**局部色度峰**（`ch0 > 1.05×max(两侧)` 且 `min(两侧) < 0.5×ch0`）—— 真实边缘一侧背景本身有色度。③ 替换值从"6 点平均"→"最中性探测点"→**去饱和**（`r_t=(r−g)·0.15`、`b_t=(b−g)·0.15`，只用本像素 ⇒ 色度增加恒为 0）。**教训：任何"用邻居值替换"必须先证明邻居未被污染**。④ `[51]` 只在 P4 内执行 ⇒ 替换档（[54]=1）旁路 P4 时怎么调都不生效（日志 `cgK/cgMean/cgCov` 全零）。
- [2026-10-06] **紫边真机基线（1777 尺度，跨场景不可混用，每场景以同场景 PC 为基准）**：台灯逆光 PC 超额 ≈0 / 叠加 +2.84 / 关闭 +2.34 / 替换 +4.24；红笔亮场景：基线 +1.89 / κ0 +1.15 / defringe 关 +0.80 / **锐化关 +0.12≈PC** / 多尺度关 +0.87；**色散 = 成片域 dRG +0.9~1.0px 而 RAW 域三通道对齐 ⇒ 管线产生**。

## 相机能力与标定（仍有效）
- [2026-10-03] **X300 畸变/几何能力（exp7 真机，4 次会话 × 2 cameraId 全一致）**：`DISTORTION_CORRECTION_AVAILABLE_MODES` = **none**、`LENS_DISTORTION` = `0.001,0,0,0,0`（AOSP 占位）、`LENS_RADIAL_DISTORTION` 空、`PRE_CORRECTION_ACTIVE_ARRAY == ACTIVE_ARRAY`；`LENS_INTRINSIC_CALIBRATION` fx=fy=**2790.18**（主摄）/ **2414.06**（超广）；AOSP 原文：校正 *"not applied to any RAW output"* 且该控制 *"on by default"* ⇒ 连 YUV 也未校正；超广桶形畸变实测幅度 **5.7%**（未校正）
- [2026-10-03] **预览域 vs 成片域**：同场景 `w999`（EV 前 P99.9）预览 **2.09** / 成片 **2.674**（另一组 2.4383/2.674）⇒ 差 0.24~0.58EV 且逐次不同；预览走 OES（ISP 已 tonemap），成片是 RAW 归一化域 ⇒ 曝光判据只认成片域 `sw999`
- [2026-09-29] **X300 三摄噪声事实**：HAL 对隐藏物理镜头返回主摄模型（pairs 逐位相同，可作为继承指纹）；实测 70MM=3.56×、15MM=2.16×；内置标定 `assets/noise_models/x300_{70mm,15mm}.c` 按"V2509A+等效焦距"匹配；同刻三镜头 A/B 残余噪声 1.22×/1.45×
- [2026-10-02] **曝光 ×N + ISO ÷N 纯赚 SNR**：离线解析收益 √N 与物理上限逐位吻合（N=8.66 → 2.95×）；约束三条 = ISO 下限 / 快门上限 / 运动预算；纹理预算维持**细节优先 2.2px**；真机 250ms 档 `expCap:"250ms" expN:"8.33" altIso:167`，nzPreMed 0.005219→0.001688（**1/3.1**）；ISO167 = Filmix 的 ISO166
## 相册与预设（10-11 → 10-13）
- [2026-10-13] **参数表单一来源 = `app/src/main/java/com/filmkit/app/ui/ParamControls.kt`**（`EditorParam` / `AdjustParams` / `ColorParams` / `TextureParams` / `LutStrengthParam` / `EditorSlider` / `CAMERA_NO_SLIDER_SLOTS` / `CAMERA_TUNABLE_SLOT_PARAM`）；由 PS-3 从 `EditorScreen.kt` **逐字搬移**（−429 行纯删除、零新增），拍照界面与编辑器共用 ⇒ 两页读数不可能漂移
- [2026-10-13] **相册查看页关键纯函数**（`PhotoViewScreen.kt`）：`frameAfterPageChange`（切页原子替换）、`pageAlphaAt(isSettledPage, fading, fade)`（alpha 与 zoom/pan 同一道闸）、`bitmapBelongsToPage` + `visibleBitmapFor`（归属校验，防旧页像素画到新页）、`zoomAboutCentroid(pan, centroid, center, z1, z2)`（两指中心缩放）、`previewRequestFor`（预览档 = 容器一半）、`PRELOAD_RADIUS=2`、`SPINNER_DELAY_MS=150`
- [2026-10-13] **`auto_tone`（`[35]`，默认 0）**：0 = 黑白点用 `[37]/[38]` 固定值；1 = 成对自动。**引擎的"白点高光感知"（v0.10.27 加，修过"釉面/白瓷密集高光一路白上去"）只在 `auto_tone=1` 生效** ⇒ 预设走固定白点路径，治本方向 = 让预设也走自适应

## 引擎插槽与调研（10-13）
- [2026-10-13] **引擎插槽方案 `docs/engine-slots-design.md`**：`kFsFilm:4046`（曝光/局部色调映射/filmic/gamut/外观层混在一个 pass）拆三段 —— 公共前段（`uExposure`、`uLocal*`、`uHalDomainMap`/`uDomainRatio`）/ **引擎段（影调曲线 + darktable filmic v5 gamut）** / 公共后段（`uSat`/`uSplit`/`uMono`/`uLook*` + 黑白电平）；**保持单 pass**（初始化期字符串拼接）；`kFsOut:4544`（编码+颗粒+LUT+暗角+柔光+锐化）与 `kFsStyle:5014`（编辑器链）不动。
- [2026-10-13] **引擎契约 5 项 + 参数面**：域（`workingColorSpace` + 到 D4 的 mat3）/ 曝光补偿域（Linear/Curve + defaultEV）/ **私有参数走独立有序 KV（绝不进 FilmParams）** / 资产 manifest 全量哈希 / 失败严格（绝不 identity 伪装，回落引擎 0）。只加 **`[98] engine_slot`**（0 = 内置 filmic）；阶段 0–1 硬判据 = **`[98]=0` 时同帧 CRC32 逐字节等价**。
- [2026-10-13] **PC 引擎插槽一手事实**（`/tmp/pc-tone` @ `b9cf590`）：**9 个引擎**（`RawRenderingEngine.kt:10-74`：AdobeCurve 0 / **AgX 1** / Spektrafilm 2 / **DarktableSigmoid 3** / DarktableFilmic 4 / Hncs 6 / Lumix 7 / Canon 8 / Leica 10；5 与 9 空缺，Fuji 于 `56d33aa` 移除）；公共模板 `RawEngineTonePass.kt:417-505`（引擎只注入 uniforms/profile/functions 四段，**输出契约线性 RGB**，OETF 只做一次）；`DarktableSigmoidToneAlgorithm.kt` 有可逐行对照的 sigmoid GLSL（`paper_exposure=0.354355423`、`film_fog=0.00142637086`、`film_power=1.5`、`paper_power=1.0`）。
- [2026-10-13] **HDRNet PGTM 形态（Google，PC 复刻）**：输入 `[1,192,256,4]`（第 4 通道引导 = `min(short BT601 luma×hdrRatio,12)`）→ 输出 `[1,12,16,8,1,2]` bilateral grid 系数 → gain 钳 `[0.03,30]` → **烘焙 DNG `ProfileGainTableMap2`（64×48 × ≥257）⇒ 成片路径零 NN**；推理 = **CPU XNNPACK**（非 NNAPI/GPU）；**权重 = 逆向提取的 Google 专有资产（`guide_coeffs.pb` MD5 `046e9e01…`、`uGainLimits`），不可复制、只能自训练**；`MGC` = **魔改 GCam**（`libgcastartup.so` + `libgcam` 标签）。

## 引擎插槽阶段 2/3 与全局开关（10-14）
- [2026-10-14] **阶段 3 契约 `EngineScope.kt`**（`app/src/main/java/com/filmkit/app/camera/`，架构师落、三卡共用）：`FILMIC_OWNED_SLOTS` = {5..10, 35, 37, 38, 40}；`NEUTRALIZE` **14 槽** = `28`=1.0 / `20`,`21`,`22`=1.0 / `36`=0.0 / `35`=0.0 / `5..10`=`LATITUDE_UI_PIVOT`(0.01) / `37`,`38`=`FILMIC_BLACK/WHITE_EV_PIVOT`(−7.65/+4.56)；`KNOWN_ENGINE_SLOTS`={0,1}；`applyEngineScope(p, slot)`（slot∈{0,未注册} ⇒ **返回同一实例**，零回归）；`effectiveEngineSlot(presetSlot, override)`。
- [2026-10-14] **引擎指纹（判定引擎真在工作）**：引擎 0 `film_d5` = `0x589f8364`；引擎 1 链上 = **`0x8fdb4e38`**；**空转（`uToneK=0`）= `0x189e4126`**（与"引擎 0 + uToneK=0"逐字节相同）。**日志判据 = `raw.render` 的 `tone=1.0`**（P0 未修时是 0.0）。引擎 1 shader 源码 = 23211 B / 15781 字符 / `0xbf298386`。

## 10bit HEIF 交付链（10-15）

- [2026-10-15] **编码器能力（`c2.mtk.hevc.encoder`，真机 `photo.heif_enc_caps`）**：`bitrateMin/Max` = 1 / **100 Mbps**；`fpsMin/Max` = 0.0016 / 79.4（⇒ `frame-rate=1` 合法）；`cqSupported` / `cbrSupported` / `vbrSupported` 全 true；`qualityRange` = **0..100**（不是 HEVC QP 0..51）。
- [2026-10-15] **QP–体积曲线（12MP 10bit，真机 `photo.heif_try`）**：QP 16 ⇒ 0.85 MB · 12 ⇒ 1.65 · 10 ⇒ 2.05 · **4 ⇒ 3.13（iso708）/ 4.34（iso1191）** · 0 ⇒ 4.62 / 5.92 MB。**画质在 QP≈4 饱和**（QP4 ↔ QP0 同场景逐像素 p50 0.33 / p90 0.67 / **p99 1.00** / max 2.3 LSB，体积 +36%）。
- [2026-10-15] **自研 HEIF 容器结构**：`ftyp(heic) + meta(hdlr/pitm/iinf/iloc/iprp(ipco(hvcC/ispe/pixi/colr)/ipma)) + mdat`；`colr/nclx` = 1/13/1/1。`hvcC` 必须**自己**从 Annex-B 转（编码器给的是 `00 00 00 01` + VPS/SPS/PPS 约 80 B；此前所谓"106 B hvcC"是 `MediaMuxer` 的产物）；`lengthSizeMinusOne` 由 hvcC 自己声明（**不许硬断言 3**）。
- [2026-10-15] **位深链路（真机自检口径）**：成片 target `GL_RGB10_A2` + readback `GL_UNSIGNED_INT_2_10_10_10_REV`（LSB-first：bit0-9 R / 10-19 G / 20-29 B）；`heif10.selfcheck` 六用例全过。真源 `core/engine/src/render/OutputBitDepth.h`（`kOutput10*` 一组），守卫 `tools/check-bitdepth-single-source.py`（PASS）。
- [2026-10-15] **交付档实机表现**：12MP 10bit HEIC 定稿后约 **3.1~4.4 MB**（同场景 JPEG q100 4:4:4 约 5.1~6.9 MB），单次编码 1.2~1.5 s。`colr` 已由 macOS 原生解码器 + 微信 + 系统相册 + Windows 四端确认正常（**判据 F 全过**）。

## 期 B 域空间（10-19）

- [2026-10-19] **域矩阵真源** `tools/rec2020-domain-matrices.py`：构造法（`outputFromXyz(D50)·xyzFromWorking(D50)`，**非硬编码**）+ 三重对拍 —— 内部（sRGB→LMS 2.22e-16 / XYZ(D50)→sRGB 3.38e-04）+ 跨实现（PhotonCamera `DarktableFilmicToneAlgorithm.kt:14-19` 的 **BT2020 版** LMS 9.8e-11、逆 5.8e-09、`PreviewColorShaderModules.kt:341` 的 sRGB→BT2020 4.4e-07）。阶段 4（AP0/D60）只需再用它换一组。
- [2026-10-19] **PC 的引擎工作空间（一手源码 `/tmp/pc-tone`）**：`RawRenderingEngine.kt:10-74` —— AgX / DarktableSigmoid / **DarktableFilmic** 跑 **BT2020**，Spektrafilm/AdobeCurve 跑 ProPhoto，Lumix/Canon/Leica 跑 SRGB，Hncs 跑 HNCS。输出统一 `computeWorkingToOutputTransform(…, ColorSpace.SRGB)`（`RawDemosaicProcessor.kt:7105`）⇒ **PC 是"内部大盘子、输出小盘子"**；其 RAW 引擎显式 `includeShadowsHighlights = false`（阴影高光类调整在 LUT 阶段、Lab 域做，且**只有一处实现**）。
- [2026-10-19] **引擎 1 的 7 个 `uSig*` 全是曲线形状常量**（`uSigWhiteTarget` / `uSigBlackTarget` / `uSigPaperExposure` / `uSigFilmFog` / `uSigFilmPower` / `uSigPaperPower` / `uSigHuePreservation`，来自 PC 的 `DT_SIGMOID_*`）—— **里面没有"对比度"** ⇒ 引擎 1 此前完全没有对比度实现（滑杆无效的真因）。

## 色相路线与 HDR（10-19）

- [2026-10-19] **红杯 ROI 的色相/色差（修正 D5 域口径后）**：sigmoid ΔE 3.69 / Δh 4.43° · filmic 2.11 / 1.50° · ACES 原生 11.71 / 3.44° · ACES 重锚（×1.7919）4.69 / 1.58° · Oklab L-only 5.42 / 1.83°。⇒ ACES 无优势；Oklab 用 ΔE 换 Δh。
- [2026-10-19] **Oklab L-only 的 GLSL 实现**（已回滚，仅存档）：`kFsSigmoidDef` 内 `sigmoidOklabTone`，矩阵 = Oklab M1·inv(sRGB→XYZ)·Rec.2020→XYZ（预乘），`L' = cbrt(sigmoid(Y))`，`a/b` 按 `L'/L` 缩放；`uSigHuePreservation` 控制与 per-channel 的 mix（1.0 = Oklab）。回滚后 `kFsSigmoid` 恢复 `0xfbab6a8c`。
- [2026-10-19] **PC 的 HDR JPEG 实现（一手源码）**：`gallery/Jpeg444ExportEncoder.kt` = 自研 4:4:4 JPEG（native `writeNative`）+ gainmap JPEG（`encodeGainmapNative`）+ **libultrahdr `packageJpegR`**（`cpp/jpeg_r_encoder.cpp`，vendored `libultrahdr`/`libjpeg-turbo`）；元数据 `ratioMin/Max` 0.25~4.0、`gamma`、`epsilonSdr/Hdr`、`displayRatioSdr/Hdr`。`hdr/UltraHdrWriter.kt`（`Bitmap.compress` + `Gainmap`）是 **4:2:0** 的另一条。
- [2026-10-19] **PC 的 gainmap 数学**：`hdr/RawGainmapMath.kt` —— `ln((HDR+1e-4)/(SDR+1e-4))` 归一化到 `[ln(0.25), ln(4.0)]`，`DOWNSAMPLE=2`，SDR/HDR 都是 linear sRGB 亮度。
- [2026-10-19] **默认 AE 不读场景统计**：`sw999` 只在 `updateFilmicEV` 的 `if (st.valid && auto_on)` 内写（`FrameRenderer.cpp:14204-14239`）；默认 `[35]=0`、引擎 1 下 `EngineScope` 再归零 ⇒ `sceneHighlightEv()` 返回 NaN ⇒ `ExposurePlanner` 的高光保护不参与。期 B 换域不影响默认 AE；开启自动影调后才需重调。
- [2026-10-19] **工具**：`tools/aces2-offline-eval.py`（ACES/Oklab vs filmic/sigmoid，含 D5→sRGB 修正、ΔE2000/Δh、同白纸对齐）· `tools/hdr-probe.py`（adb 显示/编码/属性探针，无设备时输出待办清单）。

## HDR 交付（10-20）

- [2026-10-20] **HDR 三条路径**：JPEG **4:4:4** = 自研 stb base + libultrahdr `packageJpegR`（`set_compressed_image(BASE)` → `set_gainmap_image` → `set_output_format(JPG)` → `is_uhdr_image` 自检，base 保住 4:4:4）；JPEG **4:2:0** 与 **HEIC** = 平台 `Bitmap.setGainmap` + `compress` / `HeifWriter`。开关 `-DFILMKIT_HDR`（默认 OFF）+ `-PfilmkitHdr=true`；`.so` 21MB、APK 46MB（+12MB）。
- [2026-10-20] **gainmap 必须在 GPU 算**（`kFsGainmap`）：ES 3.0 只保证 `GL_RGBA+UNSIGNED_BYTE` 读回（浮点读回真机不可用）⇒ 只读回 1/2 分辨率 8bit（12MP ≈3MB）。采样核 **4×4 步长 2**（重叠 50% 隐式平滑）。域口径：HDR 侧 `workToSrgb`、SDR 侧反 OETF，各取 Rec.709 luma。**两张输入方向相反**（`kFsOut` 带 `uFlipY=1.0`）⇒ HDR 侧采样用 `(x, 1-y)`。
- [2026-10-20] **gainmap 数学两版可切**（`setGainmapMath`，prefs `gainmap_math`）：0 = PC（offset **1e-4**，默认）/ 1 = 官方（offset **1e-7** + 暗部 `sdr<2/255` 钳 gain≤2.3）。都归一化到固定 `[0.25, 4.0]`。真机证据：官方版 gainmap JPEG **570KB**（正常几十 KB）、`>=0.90` 像素 **1.61%** 散布 ⇒ "太阳把栏杆覆盖"。
- [2026-10-20] **App 内 HDR 显示三条件**：① `BitmapFactory` + **`inSampleSize=1`**（>1 丢 gainmap；`ImageDecoder` **不解析 MPF 多图**）② **`context.findActivity()`** 逐层解包（`as? Activity` 在 Compose 里拿不到）③ `window.colorMode = COLOR_MODE_HDR`，**不检查 `configuration.isScreenHdr`**（该值在 X300 上恒 false 而系统相册能显示 HDR）。
- [2026-10-20] **导出 SDR 版**：`nativeExtractSdrBase` 用 `uhdr_dec_get_base_image` **无损取 base**（不重压）⇒ 落盘 `…_SDR.jpg`。
- [2026-10-20] **HDR 与 PC 的差距（同场景实测）**：SDR base 平均亮度我们 **85.2/98.4** vs PC **50.7**（1.7~1.9×）；`>=240` 占比 **3.38%/2.22%** vs PC **0.37%**；**HDR 输出几乎相同**（P90 0.714 vs 0.697、P99 0.861 vs 0.866）⇒ PC = 暗 base + 大增益，我们 = 亮 base + 小增益，高光在 8bit 截断处已丢信息（**归因自动曝光策略，用户已定单独开期**）。

## 自动曝光（10-20，渲染端本张统计）

- [2026-10-20] **AE 判据与常量**（`core/engine/src/render/AeMetering.h`）：`kAeRawWhiteEv 2.438307`（= Kotlin `ExposurePlanner.WHITE_POINT_EV`，两处必须同改）· `kAeMiddleGrey 0.1845` · headroom `0.1` · 限幅 ±2EV（欠曝上限与 gainmap `[0.25,4.0]` 对齐）· 高光判据 = **P99.5**（0.5% 裁切，PC legacy matcher 口径）。决策 `min(ev_mid, ev_hl)`，高光优先。
- [2026-10-20] **AE 日志字段**（`raw.render`）：`aeOn` / `aeEv`（**追加**到 `ev` 上，实际生效 = ev + aeEv）/ `aeMid` / `aeHl` / `aeP50` / `aeP995` / `aeP999` / `aeWhy`（0 none / 1 mid / 2 highlight / 3 clamped / 4 invalid）；`photo.stats` = `sw999` / `mean`（BT.601, step 4）/ `hi240`（%）。
- [2026-10-20] **实测基线（0.11.64，台灯+窗，同场景两张）**：`aeEv −1.11 / −1.11`、`aeP50 0.0696`、`aeP995 2.0273`、`mean 44.8 / 45.2`、`hi240 0.00%`、`gainmap:true` ×2。对照 PC 同场景 `mean 89.6` / `hi240 0.08%` ⇒ 仍有约 1EV 要靠 bracket 补。
- [2026-10-22] **teq 9 档曲线的档位映射**（`setTeqCurveFromAe`）：第 i 档对应 `q = i−8` EV。云/镜面反光 q≈−0.5（落 `curve[8]` 白点档）、天空主体 q≈−2.0（落 `curve[6]`）。**压深只能给第 6 档**，第 8 档必须为 0（否则白云被压成乌云）。大面积高光压深 = `-(p95_rel_white + 0)`，其中 `p95_rel_white = log2(aeP95/0.1845) + aeEv − log2(1/0.1845)`。
- [2026-10-22] **局部对比度实测基线**（1777×2360，同区域柜门区）：rawpy 单帧（无融合/降噪/锐化）**19.4%** / 我们成片 **17.6%** / PC **25.9%**（绝对值 σ 7.59 / 13.52 / 20.61；亮度 Y 39.1 / 76.9 / 79.5）。⇒ 我们的链路只比“完全不处理”低 1.8 个百分点，**PC 高出 6.5 个百分点**（它在链路里增加了细节）。
