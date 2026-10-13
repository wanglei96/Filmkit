---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-13 18:35
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
- [2026-10-07] **版本现状**：`filmkit-0.10.181.apk`（versionCode 320：`[54]`=1 **替换档默认**、`[56]` rcd_g_orig / `[57]` hl_rb_recon / `[58]` rcd_p3_split 三实验开关默认 0、3a/3b 填充值 per-channel 满阱钳）；**成片文件名小写 `filmkit_*`**；提交 2a67c42（feat）+ c7fef2d（docs: research/11 号）已 push；**验收 tag 最新仍为 v0.10.133**（未打 tag）；`app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` 是 FilmParams 槽位唯一来源

## 引擎实现现状

- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；硬约束 LUT 必须在 OETF 后
- [2026-10-13] **FilmParams 契约 kCount=99**（`film/FilmParams.h:41`，阶段 0 引擎插槽起；旧记忆的 36/43/48/56/59/**98** 均已过时）：[0] exposure_ev、[1..3] wb、[29] 空槽不复用、[31..34] 外观层/LUT 强度、[35] auto_tone（默认 0）、[37] filmic_black_ev / [38] filmic_white_ev、[42] white_hl=1、[43] white_floor、[44] white_probe=0.5、[45] hr_raw、[46] rcd_cdfix=1、[47] defringe、[48] moire、[49] hl_grecon、[50] ca_corr、[51] chroma_gate=2.0、[52] cg_scope=1.0、[53] df_gate=0.0、[54] **pc3d=1（替换档默认）**、[55] pc3d_msize=0、[56] rcd_g_orig=0、[57] hl_rb_recon=0、[58] rcd_p3_split=0、[59] color_smooth、[88]/[89] 色温色调（相对量）、[90..94] 高光/阴影/白/黑/褪色、[95..97] 柔光（默认 0.0/0.35/0.70）、**[98] engine_slot**（阶段 0，0 = 内置 filmic、默认）；**按实际长度读取（下限 26）**；⚠️ 下发数组必须按 **99**，且**六处同步**：`FilmParams.h` 的 `kCount` / 同文件字段声明行 `float <名> = …; // <槽位>`（机器可读，`FilmParamKeysTest` 正则抽取）/ `fromDoubles` 防呆 / Kotlin `CameraPresets.neutral()` 与 `COUNT` / Kotlin `FilmParamKeys.bySlot`（v0.10.196 只改 C++ ⇒ 启动崩）。**契约守卫**：`python3 tools/check-filmparams-contract.py`（2026-10-13 新增，四路对拍，补"C++ kCount 单独漂移"这条既有测试不覆盖的缝）
- [2026-10-13] **kFsFilm 已拆成「公共模板三段 + 引擎注入段」**（阶段 1，提交 `ef1f50e`）：`FrameRenderer.cpp` 的 `filmSlices()`（:4583）用**四个锚点字符串**在 `kFsFilm` 体内 `find` 出偏移，切成 head `[0,a)` / engine0_def `[a,b)` / mid `[b,c)` / engine0_main `[c,d)` / tail `[d,end)`；`filmSource(slot)`（:4634）按该顺序组装；`init()` 用拼接结果编译。**单真源 + 运行时切片**（不是物理拆成三段字面量 —— 那样 25140 字符会出现 3 处人为接缝，等价性无法由构造保证）。锚点（字符口径）：a=3839 b=18617 c=21805 e=22115 d=22153（**engine_main 含收尾 `    }`**，故与"按行号 433..439"口径差 5 字符）。**判据值**：拼接 == 原文 = 496 行 / 25140 字符 / **35154 字节** / **crc32 0x4fa1ebd3** / sha256 f1ebb317…563c。锚点缺失 ⇒ 退回整体字面量 + kError。
- [2026-10-13] **阶段 1 的两条运行期日志**：① `engine.film_src`（初始化期一次）`{"slot":0,"bytes":35154,"chars":25140,"crc32":"0x4fa1ebd3","identical":true,"zero_regression":"ok"}` —— 真机上"拼接是否等价"直接可读，不必等离线；② `engine.slot_unavailable`（`[98] != 0` 时，atomic 去重只记一条）`{"requested":N,"fallback":0,"stage":1}` = 契约 5 的"不静默降级"。
- [2026-10-13] **`[98]` 是"第三种东西"**：进预设、拍照链路读（`passFilm`）、但**不是可调参数**。三集合语义（**别混**）：`CAMERA_PATH_SLOTS`（拍照链路读取，含 98）/ `CAMERA_NO_SLIDER_SLOTS`（**是参数**、但暂无滑杆：26/27/60/61，**不含 98**）/ **`CAMERA_ENGINE_SELECT_SLOTS`**（新增，= {98}，"**根本不是参数**"——引擎选择，阶段 3 走**引擎选择面**）。`fullEditReadonlySlots()` 靠 `it !in CAMERA_PATH_SLOTS` 天然把 [98] 挡在参数面板外 ⇒ **零 UI 变化**。
- [2026-10-13] **EngineRegistry 骨架**（`core/engine/src/film/EngineRegistry.{h,cpp}`，阶段 0）：`kEngineCount = 1`，唯一表项 `{0, "filmic", "LinearSRGB", ExposureDomain::kLinear, 0.0f}`；`findEngine(slot)` 未命中返回 `nullptr`（**不回落**，回落是调用方策略）；无 GL/EGL 依赖；**渲染路径零引用**（阶段 1 才接线）
- [2026-10-13] **离线执行器覆盖 = P0→RCD→CCM→FILM（+PC3D）**（`tools/engine-shader-offline.py`）：产物 `p0/rcd_g/rcd_d3/ccm_d4/**film_d5**.npy`；**FILM 段 2026-10-13 补齐**（此前文件头声称已接入而实现没有 ⇒ 阶段 1 判据曾无法执行，用户拍板 A 方案先补链），配套 `tools/filmic-spline-ref.py`（`computeFilmicSpline` 逐行对照）+ `tools/check-film-offline.py`（自检：solve5 对拍 numpy / CPU 复现 vs GPU 实跑差 2.44e-04）+ `tools/film-offline-REPORT.md`。**基线**（`docs/reports/stage0-engine-slot-BASELINE.md` §3.3）：`film_d5` 默认口径 `crc32=0x589f8364`、白点 4.0 口径 `0xd46d7b5e`。**仍不在链内**：HR 高光重建（真机 FILM 的输入是它 ⇒ 离线基线不能判成片观感）/ `uLocal*` / PC3D 的 LSC 与噪声标定 / `kFsOut` / MFNR / 同帧成片 CRC32（需真机 adb）
- [2026-10-13] **filmic 黑白点默认值两层口径（别混）**：**代码默认** `[37]/[38]` = **−7.65 / +4.56**（`FilmParams.h:161-162` + `CameraPresets.kt:122-123`，darktable/PC 固化值）；**2026-10-13 真机定档**白点 = **4.0**（经 prefs 覆盖，死白起点 3.55 EV）
- [2026-09-29] **影调链**：filmic 原生件（darktable v5 固定参数，base_ev 默认 +0.7）；黑白点自动三版证伪后默认关；`updateFilmicEV` 的 white 判据 = max 驱动探针 + 整体 +ev（渲染域对齐）；statistics 512×288、P99/P99.9/P100
- [2026-09-29] **gamut mapping v5**（min+相锁+Y clamp+desaturate(0)+Yrg+clip chroma）；**输出 OETF 真 sRGB**（`kFsOut`/`kFsHist`/`measureSceneStats` 三处同步）；原生小波高光重建；锐化 = 线性显示域 luma USM（kFsOut 内）
- [2026-09-27] **MFNR 采集**：maxImages 4 + 帧 1-3 拷贝即 close（maxImages 5+4 全程持有致 HAL 预配 125MB 闪退）；prefs `mfnr` 三档（0 关 / 1 自动暗光 ISO≥600 或 ≥25ms / 2 强制）
- [2026-09-30] **MFNR 对齐（0.10.70 起）**：最细精化层 = **1/2 域**（2×2 Bayer 平均灰度，HDR+ §4 原版），tile 64@1/2 域、金字塔 L4→L1；位移量化 2 mosaic px；`allow_l1=false` 走旧 1/4 域（A/B）；日志 `alignL1`
- [2026-09-30] **P0 时域×空间联合**（`kFsRawDenoiseFusedTs`）：4 帧 × 24 同相位邻域 = 96 样本；`uSub`（0.10.76 起默认 1）= 逐通道网格亚像素双线性（`linSubZ`），0 = 旧"round 到偶数"；诊断 `p0s`/`p0w`
- [2026-09-30] **降噪现状**：P0/P4/mismatch 全走 **σ²=A·x+B** 逐像素；强度滑杆 0–2（1.0 = 真实 σ；<1 走输出 mix）；D-1 色度 à trous 5 级（k=2.5、revert 0.20）**已超 PC**；模型优先级 = 用户导入 > 内置实测 > Camera2 > ISO 估算
- [2026-09-30] **DFT 合并（A 专项）**：`kFsMfnrFft` compute，按 CFA 平面独立、16×16 平面 tile 半重叠（步长 8 + 修改升余弦窗，窗和恒 1）；窗函数查表 `sWin`；输出 4 张 RGBA16F image2D writeonly，**目标必须 `glTexStorage2D`**；compute program 现场创建用完删除；`mfnr.fft.store_check` 自检 + 失败回落 mismatch；dftGpu 冷机 500~550ms / 热机 830~870ms
- [2026-10-01] **拍照侧引擎（v0.10.85）**：`g_renderer_still`（`std::atomic` 指针）+ 独立 `EglCore`/1×1 pbuffer/`FrameRenderer`；`nativeStillStart` 幂等、`nativeStillDestroy` **连 context 一起销毁**（空闲 10s 由 Kotlin 侧触发）；LUT / YUV 矩阵为**进程级快照**、start 时下发；JNI 成片回传统一走 `rgbaToJavaArray`（OOM → 清异常返回 nullptr）；`AndroidManifest` 开 `largeHeap`
- [2026-10-07] **紫边/高频链现状**：`hr_wavelet` 默认关（模块保留可 A/B；曾占 ≈752MB 纹理 + ≈103 全分辨率 pass/张）；HR 前置到 P0 之前、统计用 `uStat`；defringe 4 pass 半分辨率（`[47]` 默认开，**替换档下被旁路**）；强边缘色度门逐像素（`[51]` κ 滑杆，替换档下同被旁路）；ESD3D2 `[54]` **默认替换档**（v0.10.181，旁路 P4/defringe/D-1）；`[55]` 自动核真机 = **9**（builtin ext 噪声通路）；`[56]`/`[57]`/`[58]` 实验开关默认 0（判定依据见 decisions.md 10-07 条）；P3 拆双 pass 的 3a 输出纹理 = `tex_rcd_a_`（+100MB RGBA16F），性能 rcdAMs+rcdBs 实测 15~61ms

- [2026-10-07] **淡紫线定位与 RAW 域事实（新样片 142415/142512，用户标注位）**：用户所指紫边 = **窗帘边缘/窗框竖边那条 1~3px 细线**（成片 x 1000–1250、y 424–1645，y=600 行峰在 x=1063，dBG 峰 104/8bit）；**不是**窗框白区左边缘色带（10-06 定案那条，勿混）。RAW 域核对：该处 B 通道实测高于 G（sensor Y=2003 时 B=225.7 / G2=126.0，B/G≈1.8；暗区 B/G≈2.0 稳定），**RAW 域本身就有该色度**——与 11 号 §7.2「RAW 域无品红」的旧口径**不矛盾但需限定**（旧结论针对白区左边缘过渡形状，本处是通道强度比）。**RCD 段无责（两路独立验证）**：①subagent 全量逐行对照——我方 vs RawTherapee 官方版 B−G 差异 p99.9 0.77 / max 3.2（/8bit），我方 P1 五点高通/lpf 权重/3b 四正邻双轴与 RT 逐项等价；②本轮离线对照——紫线处 REF 原版 B−G=40.8~53.0 vs 我们 53.2~66.1（同量级）。**新事实：原版 RCD 2.3 有第二绿位（FC=3）数组越界 bug**（`rgb` 为 `float(*)[3]`，`rgb[indx][FC]=…` 在 GRBG 下写 `rgb[indx][3]` ⇒ G2 位 `rgb[·][1]` 恒 0；RawTherapee 已修正）⇒ `docs/research/11` §8 与 `/tmp/rcd_ab*.py` 的"原版"实为**修正版**口径，字面对齐原版会引入 max 147/8bit 伪影（**不做**）
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
- [2026-10-06] **审计报告已存档 `docs/reports/`**（pcfix / pc3d / pc3d-reval / amaze / jpeg444 / chromagate2 / cacorr / phase-sigma 共 8 份+配套数据，代码内 27 处 /tmp 引用已改写；/tmp 原件已删，与 test_temp 旧场景合计释放 ≈9GB；PC 源码保留 `/tmp/pc`、`/tmp/photoncamera`）；**本轮评估报告未沉淀（重启即失）**：`/tmp/ab-1006`、`/tmp/ab-1006b`、`/tmp/ab-s4`；新工具 `tools/purple-fringe-kpi.py`、方案 `docs/紫边窄峰真机AB方案.md`

## 对标数据与测量口径（10-03 → 10-05）

- [2026-10-03] **亮部降噪 = 唯一成像短板**：同场景 PC 亮部 σY 我们 0.82 / PC 0.49（**1.67×**，差距随亮度单调放大）；色度多尺度 1→32px 全面占优 1.2~1.8×；噪声模型 RAW 域实测准（R 1.39/G 1.04/G2 1.23/B 1.34）
- [2026-10-05] **紫边对标基准修正**：`∇>60` 均值口径可自证复现 PC 的 **3.68413 / 0.44607%**，但它把 PC 高估 ≈**2.2×**（零假设检验：3.684 里只有 **+1.668** 与边缘有关）；正确目标 = **边缘局域超额 1.67 / 剖面 core 1.08**；**同一地点相隔 7 秒的两张成片读数可差 25.8×** ⇒ 该口径禁作跨图 KPI
- [2026-10-05] **剖面形状**：我们 = 窄峰（**FWHM 4px**）、PC = 宽缓斜坡（**20+px、无峰**）；真机 1px 色度噪点 10.5%→**0.04%**（融合+P0/P4/D-1 有效）；用户所见品红主体 = **>32px 低频色度噪声云**（P90 3 级、品红绿成对、中亮区集中），根因 4.8ms 短曝光
- [2026-10-05] **AMaZE（排除去马赛克路径）**：完整 CPU 多线程实现 **247ms@12MP** + 设置三档可选；离线六项指标**五项为负**（∇>60 伪彩 AMAZE 30.0 vs DCB 19.2）
- [2026-10-05] **4:4:4 JPEG（排除编码路径）**：实测生效（SOF0 变 4:4:4、ms=293）但紫边未解决；反证 = PC 成片本身也是 4:2:0
- [2026-10-05] **色度门实测**：0.173 全局放宽 `wy` ⇒ 真机"一片红一片黄一片紫"；0.174 改逐像素门控（门覆盖率 73.7%→1.6%）后色渗判据全绿
- [2026-10-05] **HR 阈值域修复实测**：真机 Y 加权和之差 **+2.03% → +0.03%**、clipB **289→60**；`uGRecon` 关闭后高光区 B/G **1.087→1.025**
- [2026-10-05] **测量证据两则**：rawpy 的 `black_level_per_channel` 是取整值（DNG 真值 `[63.99,64.01,64.01,64.01]`，相位极差 ≤0.03 ADU）⇒ "R 相位黑电平 63 致暗部偏红"假设被推翻；旧诊断照片是二次传输重编码副本（1777×2360 ≠ 原生 3072×4080、DQT 非标准表）⇒ **用户截图不能作证据，必须取相册原图**
- [2026-10-08] **紫边判据重写与 color_smoothing 引入（v0.10.194-196）**：① 根因 = **量纲陷阱** —— 阈值 5.0 在显示域 JPG 上量、shader 跑线性域（linMid≈0.07/pHi≈3.3）⇒ 恒 false 全线漏检；**给 shader 定阈值前必须用 `tools/engine-shader-offline.py` 量线性域分布**。② 定案判据（全图 `cavg=|B−R|` 均值）：`br0 > 3×cavg`（**全分辨率**，半分辨率 2×2 稀释 1~3px 窄线）+ `gr0 > 0`（紫象限 B>R>G）+ `base_std > 2×cavg`（窄带性）+ `|∇L|·255 > 5`；**显示域 B−G 不是紫边判据**（暖色饱和区 G 低也 B−G>60）。③ 替换 = 保亮度去饱和（R/B 同拉向 G + 三通道等量加法补偿）。④ v0.10.195：真机 `dfCavg` 比离线同帧大 **1.4~2.3×** ⇒ 阈值 3.0→**0.8×cavg** + 绝对限 `clamp(0.8×cavg, 0.012, 0.06)`（防真机 cavg 异常放大时判据静默失效）；**D4↔成片是转置关系**（`np.rot90(L,3)[:,::-1]` ≡ `L.T`，corr **0.9207**）⇒ 用户整图截图可按比例换算定位。⑤ v0.10.196 引入 `[59] color_smooth`（darktable `color_smoothing`，色差域 3×3 中值，`basics.c` 逐行移植）—— 唯一**不依赖色相判据**的通解，也是我们此前唯一缺的 darktable 环节；三处必须修正：梯度门 gL∈[6,16]、**只降不升**（中值只保证取邻位、不保证降色度）、保亮度补偿。
- [2026-10-08] **GLSL 兼容铁律与 2-pass 定案（v0.10.197-201）**：① v0.10.196 **装包即崩** = 加 `[59]` 只改 C++ `kCount` 未同步 Kotlin `DoubleArray(59)` ⇒ `ArrayIndexOutOfBoundsException`；**FilmParams 加槽位必须四处同改 + 下发点 size 兜底**。② v0.10.196/197「完全没效果」= **shader 静默编译失败**：`float m[9]` 数组参数/动态索引在 Mali 编不过 ⇒ `prog_cs_=0` ⇒ 静默 return，且 `fs_cs` 不在 `initPrograms` 失败检查列表里；**多行宏（`#define` + 行尾反斜杠）在 GLSL 预处理器同样不生效** ⇒ **定案写法 = 完全内联**（19 次比较 ×2 通道全展开，无数组/无循环/无宏）。**新 pass 没效果先查 `prog_ != 0`，并把诊断字段写进日志**（`csK`=下发 / `csOk`=链接 / `csOn`=执行，三字段三分钟定位）。③ 1 pass 消除过饱和 95%、**2 pass 98%**（收益拐点，ping-pong 两张 RGBA16F）；中值对**宽色带**（邻域异常占比 >50%）无效，只能靠迭代扩窗。④ v0.10.200 真机判死（用户「效果很差」）⇒ revert：**梯度门 20/50 把紫边（gL 6~20 段）挡在门外** ⇒ 只剩 defringe 单路压紫；**离线链路无 PC3D 是根因** —— 判据涉及邻域/跨边缘采样的改动，离线必须含真机同款前置。
- [2026-10-08] **从「两侧中性」到「局部色度峰」再到「去饱和」（v0.10.203-209）**：① **伪彩带宽真相**：PC3D 把 RCD 的 1~3px 伪彩**摊成 12~90px 宽带**（112643 成片剖面）⇒ ±3px 探测点全落在带内 ⇒ 203 覆盖率 **0.0%**。② v0.10.205 **把 color_smooth 前置到 PC3D 之前**（链路 `D4 → color_smooth → PC3D → [P4] → defringe → film`）—— 伪彩回窄带 ⇒ 尺度 [3,6]、sideK 0.35；**前置比「让 [51] 生效」更根本**。③ v0.10.206 判据改**局部色度峰**（`ch0 > peakK×max(两侧)` 且 `min(两侧) < sideK×ch0`）—— 紫线一侧背景**本身有色度**，「两侧都中性」结构性失效；peakK=1.05，代价仅 −1.25%。④ v0.10.207 替换值从「6 点平均」改「**最中性探测点**」（真机峰 ch=47 而两侧 33/0，平均只降到 1/3 ⇒ 肉眼仍是彩边）；**统计口径必须与用户所见一致** —— 我统计紫向 B>R>G（全图 19 像素），用户截图最明显的是**蓝向 B>G>R**（区域 139168 个、B−R max 105）⇒ **每轮必须裁用户截图对应区域自己看**。⑤ v0.10.208「PC3D 之后再跑一次」**真机判死**（中性/暗部染蓝、全图过冲 932→1209）：根因 = 搬邻居色差时**邻居被蓝色背景污染**。⑥ v0.10.209 定案：替换改**去饱和**（`r_t=(r−g)·0.15`、`b_t=(b−g)·0.15`，只用本像素信息 ⇒ **色度增加像素恒为 0**），改写 0.5~1.8%、ΔL max 0.27/255、色度总量 −6.0%。**教训：任何「用邻居值替换」的策略，必须先证明邻居未被处理目标污染**。⑦ **`[51]` 只在 P4 内执行**：替换档（[54]=1）旁路 P4 ⇒ 怎么调都不生效（日志 `cgK`/`cgMean`/`cgCov` 全零）；即使切叠加档，[51] 也在 PC3D 之后 ⇒ 面对已摊宽的伪彩，效果有限。
- [2026-10-07] **品红带机理闭环（docs/research/11 号 §6-8）**：成片白区左边缘 = B 通道 ~8px 缓爬 vs G ~2px 陡跳的错位带（品红）；RAW 域 G/B 形状重合（光学干净，单帧多列平均剖面）；单帧 ≈ 4 帧（融合赦免）；dn=0 更重（P0 缓解者，p0A_B/p0A_G ≈ 1.46 抹缓 B 边缘为次嫌疑）；**B 缓 G 陡错位 = RCD 类算法固有**（全无 LIM 公平直译：原版 = 我们 = [56] 两模式 = +86~88 WB 域，LIM(0,1) 在 WB 域削顶曾伪造"原版 43.8"）；[57] 判定离线复现：品红带满阱率 R 18.7%/G 32.1%/B 24.4%、目标态 0.1%、allSat 18.6%
- [2026-10-06] **真机三轮基线（1777 尺度，新 KPI；跨场景不可混用，每场景以同场景 PC 为基准）**：台灯逆光（主场景）PC 超额 ≈0、叠加 +2.84、关闭 +2.34、替换 +4.24（替换暗区独有紫向 +2.80）；红笔亮场景六组：基线 +1.89、κ0 +1.15、defringe 关 +0.80（绿边/红渗反升 = 交换）、锐化关 **+0.12≈PC**、多尺度关 +0.87；**色散 = 成片域 dRG +0.9~1.0px 而 RAW 域三通道对齐 ⇒ 管线产生（非光学非 [51]）**；台灯窗框品红窄峰 +10@3px（135918，关锐化不消失）

## 相机能力与标定（仍有效）

- [2026-10-03] **X300 畸变/几何能力（exp7 真机，4 次会话 × 2 cameraId 全一致）**：`DISTORTION_CORRECTION_AVAILABLE_MODES` = **none**、`LENS_DISTORTION` = `0.001,0,0,0,0`（AOSP 占位）、`LENS_RADIAL_DISTORTION` 空、`PRE_CORRECTION_ACTIVE_ARRAY == ACTIVE_ARRAY`；`LENS_INTRINSIC_CALIBRATION` fx=fy=**2790.18**（主摄）/ **2414.06**（超广）；AOSP 原文：校正 *"not applied to any RAW output"* 且该控制 *"on by default"* ⇒ 连 YUV 也未校正；超广桶形畸变实测幅度 **5.7%**（未校正）
- [2026-10-03] **预览域 vs 成片域**：同场景 `w999`（EV 前 P99.9）预览 **2.09** / 成片 **2.674**（另一组 2.4383/2.674）⇒ 差 0.24~0.58EV 且逐次不同；预览走 OES（ISP 已 tonemap），成片是 RAW 归一化域 ⇒ 曝光判据只认成片域 `sw999`
- [2026-09-29] **X300 三摄噪声事实**：HAL 对隐藏物理镜头返回主摄模型（pairs 逐位相同，可作为继承指纹）；实测 70MM=3.56×、15MM=2.16×；内置标定 `assets/noise_models/x300_{70mm,15mm}.c` 按"V2509A+等效焦距"匹配；同刻三镜头 A/B 残余噪声 1.22×/1.45×
- [2026-10-02] **三摄 GCam 标定**：主摄 HPB A=2.72e-7 dg_ref 6400（100 张实测）、超广 JN1 A=3.07e-7（借用 NubiaZ50Ultra，非本机实测）、长焦 LYT602 A=1.05e-6；`BUILT_IN` 原先**无主摄条目**（主摄一直走 HAL cam2，其 G 通道比 R/B 低一半）；真机 `src:"builtin"` ×3 验证通过；手动选定是**全局**的（不随镜头切换）
- [2026-10-02] **曝光 ×N + ISO ÷N 纯赚 SNR**：离线解析收益 √N 与物理上限逐位吻合（N=8.66 → 2.95×）；约束三条 = ISO 下限 / 快门上限 / 运动预算；纹理预算维持**细节优先 2.2px**；真机 250ms 档 `expCap:"250ms" expN:"8.33" altIso:167`，nzPreMed 0.005219→0.001688（**1/3.1**）；ISO167 = Filmix 的 ISO166
- [2026-10-01] **连拍显存与耗时**：修后 7 张全成功零丢失；独立线程代价 = 峰值 +455MB、常驻 +1.96GB（空闲释放回 ~400MB）；热机单张 totalMs 2091~3336ms；同帧 A/B CRC32 逐字节等价；`capture()` 在渲染期静默吞快门（"连按两下只出一张"根因）
## 相册与预设（10-11 → 10-13）

- [2026-10-13] **参数表单一来源 = `app/src/main/java/com/filmkit/app/ui/ParamControls.kt`**（`EditorParam` / `AdjustParams` / `ColorParams` / `TextureParams` / `LutStrengthParam` / `EditorSlider` / `CAMERA_NO_SLIDER_SLOTS` / `CAMERA_TUNABLE_SLOT_PARAM`）；由 PS-3 从 `EditorScreen.kt` **逐字搬移**（−429 行纯删除、零新增），拍照界面与编辑器共用 ⇒ 两页读数不可能漂移
- [2026-10-13] **相册查看页关键纯函数**（`PhotoViewScreen.kt`）：`frameAfterPageChange`（切页原子替换）、`pageAlphaAt(isSettledPage, fading, fade)`（alpha 与 zoom/pan 同一道闸）、`bitmapBelongsToPage` + `visibleBitmapFor`（归属校验，防旧页像素画到新页）、`zoomAboutCentroid(pan, centroid, center, z1, z2)`（两指中心缩放）、`previewRequestFor`（预览档 = 容器一半）、`PRELOAD_RADIUS=2`、`SPINNER_DELAY_MS=150`
- [2026-10-13] **filmic 黑白点**：`[37] filmic_black_ev`（引擎默认 −7.65）、`[38] filmic_white_ev`（引擎默认 4.56，`FilmParams.h:156`）；引擎无绝对 clamp（仅 `black ≤ white − 0.2`）；UI 值 = 槽位 EV 直通，**量程复用 `SettingsScreen.kt` 既有滑杆**（黑点 −12..−1 / 白点 1..8）
- [2026-10-13] **内置三条预设 `[38]` = 4.0**（R-3 定案，原 2.7471/2.6138/2.5438）：死白起点 2.60/2.45/2.40 → **三条齐平 3.55 EV**；高光层次预算 6.6/3.0/1.1 → 24.4/21.0/15.8 LSB；代价 = 白纸 2.30EV −15.9~−18.0 LSB、拟合残差 8~10 → 12.7~17.4。配方见 `app/src/main/assets/presets/builtin.json`
- [2026-10-13] **编辑器链路白点上限 2.4377 EV**：`computeEditorCurve` 的 `wh = clamp(srgbEncode(grey·2^white_ev), grey_disp+0.05, 1.0)` ⇒ `white_ev ≥ log2(1/0.1845)` 时 `wh` 恒 1.0（显示域最亮即 1.0）；拍照链路不受此限
- [2026-10-13] **`auto_tone`（`[35]`，默认 0）**：0 = 黑白点用 `[37]/[38]` 固定值；1 = 成对自动。**引擎的"白点高光感知"（v0.10.27 加，修过"釉面/白瓷密集高光一路白上去"）只在 `auto_tone=1` 生效** ⇒ 预设走固定白点路径，治本方向 = 让预设也走自适应
- [2026-10-13] **出包后版本/构建要点**：跑 gradle 必须 `JAVA_HOME=/opt/homebrew/opt/openjdk@23`（默认 java 8 跑不动 AGP 8.13）；单测必须 `--rerun-tasks`（否则 build cache 假绿）；出包 `tools/release.sh <code> <name>` 需同一 JAVA_HOME

- [2026-10-01] **LUT 库结构**：`app/src/main/java/com/filmkit/app/lut/`（CubeLut 解析 1D/3D/二进制、MAX_SIZE 128；LutIO 嗅探/Hald·Unwrapped/重采样；LutLibrary 内置 42 + 用户库 files/luts、id 前缀 u_）；预览降载走 `g_preview_scale`；LUT 强度上限 1.5
- [2026-10-02] **NN 路线判死但代码保留**：spike 真机 GPU **26ms/tile**（7× CPU）、12MP 0.51s 零温升、纹理保留 78–86% 优于 P0；集成五轮逐层修口径（JNI 桥 / KSigma / cvt_k×256 / 通道序 / neff 折减）后 **nnGain 与公式逐位吻合而观感零改善**（文字梯度 −24%、斑块状伪影）⇒ 跨域模型不可直接用；四次微调（监督×3 + N2N）留出集全过拟合（4 场景 vs 官方 3 万+）；代码落点 `nn/NnInferEngine.kt` + `kFsNnPack/Unpack`、`kNnAnchorS=1.0093e-3`，prefs nn_dn/nn_ab 默认关
- [2026-10-04] **编辑器与预设调研（docs/research/10 号）**：预设 schema 增量 = 颗粒强度/尺寸/密度调制、halation 阈值/强度/半径/色相、暗角、趾肩、层间效应；E1–E5 阶段；基础设施 = CameraPresets 槽位 + `nativeEngineSubmitStill/Raw` 单帧直通 + 胶卷位占位（CameraScreen:1232）

## 引擎插槽与调研（10-13）
- [2026-10-13] **引擎插槽方案 `docs/engine-slots-design.md`**：`kFsFilm:4046`（曝光/局部色调映射/filmic/gamut/外观层混在一个 pass）拆三段 —— 公共前段（`uExposure`、`uLocal*`、`uHalDomainMap`/`uDomainRatio`）/ **引擎段（影调曲线 + darktable filmic v5 gamut）** / 公共后段（`uSat`/`uSplit`/`uMono`/`uLook*` + 黑白电平）；**保持单 pass**（初始化期字符串拼接）；`kFsOut:4544`（编码+颗粒+LUT+暗角+柔光+锐化）与 `kFsStyle:5014`（编辑器链）不动。
- [2026-10-13] **引擎契约 5 项 + 参数面**：域（`workingColorSpace` + 到 D4 的 mat3）/ 曝光补偿域（Linear/Curve + defaultEV）/ **私有参数走独立有序 KV（绝不进 FilmParams）** / 资产 manifest 全量哈希 / 失败严格（绝不 identity 伪装，回落引擎 0）。只加 **`[98] engine_slot`**（0 = 内置 filmic）；阶段 0–1 硬判据 = **`[98]=0` 时同帧 CRC32 逐字节等价**。
- [2026-10-13] **PC 引擎插槽一手事实**（`/tmp/pc-tone` @ `b9cf590`）：**9 个引擎**（`RawRenderingEngine.kt:10-74`：AdobeCurve 0 / **AgX 1** / Spektrafilm 2 / **DarktableSigmoid 3** / DarktableFilmic 4 / Hncs 6 / Lumix 7 / Canon 8 / Leica 10；5 与 9 空缺，Fuji 于 `56d33aa` 移除）；公共模板 `RawEngineTonePass.kt:417-505`（引擎只注入 uniforms/profile/functions 四段，**输出契约线性 RGB**，OETF 只做一次）；`DarktableSigmoidToneAlgorithm.kt` 有可逐行对照的 sigmoid GLSL（`paper_exposure=0.354355423`、`film_fog=0.00142637086`、`film_power=1.5`、`paper_power=1.0`）。
- [2026-10-13] **HDRNet PGTM 形态（Google，PC 复刻）**：输入 `[1,192,256,4]`（第 4 通道引导 = `min(short BT601 luma×hdrRatio,12)`）→ 输出 `[1,12,16,8,1,2]` bilateral grid 系数 → gain 钳 `[0.03,30]` → **烘焙 DNG `ProfileGainTableMap2`（64×48 × ≥257）⇒ 成片路径零 NN**；推理 = **CPU XNNPACK**（非 NNAPI/GPU）；**权重 = 逆向提取的 Google 专有资产（`guide_coeffs.pb` MD5 `046e9e01…`、`uGainLimits`），不可复制、只能自训练**；`MGC` = **魔改 GCam**（`libgcastartup.so` + `libgcam` 标签）。
- [2026-10-13] **vivo X300 引擎取证（真机 adb 拉取，样本 `/tmp/x300-samples/`）**：`VivoCamera.apk` 396MB **内无 .so**（native 外置 `/system/app/VivoCamera/lib/arm64/`，**81 库**）+ 10 dex + 8 张 LUT；app 层 = **ACES 式 GLSL**（`OETF(OutputTransform(NormalizeLuminance(ToneMap(ScaleLuminance(InputTransform(EOTF(x)))))))` + `ScaleLuminance_ST2084`/`toneCurveHLG`）+ **模式枚举**（`TONEMAP_MODE`/`mToneMappingMode`/`mToneMappingSpace`）；LUT 容器 `CUBEB2BIN8`/`3DLUTBIN8`（56B 头、网格字段 0x21=**33**、**33³ float32**，431300 B）；**全量 dex 扫描：`autoTone` 0 命中、`histogram` 47 条全是 UI ⇒ app 层无自适应影调**；推理栈 `libvdnn`（含 OpenCL kernel 源串）+ `libxnn`（XNNPACK API）；`/vendor`、`/odm` 未 root **Permission denied**（成片主链未取证）。
- [2026-10-13] **影调判据口径（待写死进方案）**：主判据用「**可分辨跨度**」= 从白纸起每 0.1 EV ≥1 LSB 能持续多远；对齐白纸 204 LSB 后 filmic **1.65 EV** / sigmoid c=1.2 **1.95 EV** / ACES2 **1.90 EV**（优势仅 **0.25~0.30 EV**），三架构只用掉 8bit 上界的 **32~41%**。**「死白起点」换阈值即翻转排序**（≤1 时 sigmoid 4.25 > filmic 3.55；≤2 时 filmic 3.90 > sigmoid 3.10）；filmic 的 plateau 在 **4.00 EV**（= 白点），是**连续不可逆**（任何位深都救不了）。
- [2026-10-13] **PC 引擎下线的真实代价**：Fuji 引擎移除 = **33 个文件改动 + Room DB v49→v50**（`GalleryDatabase.kt` +42/−2、`MIGRATION_49_50` 整表重建跳过 `rawFujiFilmSimulation`）⇒ 引擎注册表必须集中化、**引擎私有参数不进 DB 列**。
