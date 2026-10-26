---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-26 10:17
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->
<!-- 2026-10-26 d3 整理:压缩折行、删除被后续版本取代的"当时现状",保留当前有效值与口径。 -->

## 环境
- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；依赖上限锁定（2026.09 版 AndroidX 需 AGP 9.1+/SDK 37）
- [2026-09-18] 项目路径含中文：AGP externalNativeBuild 丢中文段（CXX1400），已改自定义 CMake Exec 直驱 NDK
- [2026-09-23] 构建命令（本机已验证）：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest`
- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、1=前置、2-5 隐藏物理、6=第二逻辑组）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、白阶 1023、maxISO 3200；RGBA16F 无线性过滤
- [2026-09-21] adb：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`；手机存储 Mac 直读 `~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`
- [2026-09-18] 日志/审计导出：`/storage/emulated/0/Android/data/com.filmkit.app/files/{logs,audit}`（vivo 办公套件回传，落点 `~/Downloads/vivo办公套件/`）；未捕获异常写 `logs/crash-latest.txt` + JSONL `app.crash`
- [2026-09-21] 离线工具链：python3 + numpy + PIL + rawpy 0.27；mimosa 插件已禁用；调参脚本 `tools/tuning/`

## 路径与约定
- [2026-09-18] Git 远端 `git@github.com:wanglei96/Filmkit.git` main；**CI 仅手动触发**；出包在本地做
- [2026-09-18] APK 产物：`app/build/outputs/apk/{release,debug}/*.apk` → 根目录 `filmkit-<versionName>-<TAG>.apk`；**出包前 `rm -rf app/build/outputs/apk`**；`*.apk` gitignore
- [2026-09-18] 引擎 so `libfilmengine.so`，JNI 入口 `com.filmkit.app.EngineBridge`；源码 `core/engine/src/`
- [2026-09-20] shader 校验 `tools/validate-shaders.sh`（本地二进制 `tools/bin/glslang`）；**发版前必跑**
- [2026-09-18] 版本约定：发版打附注标签 `v<versionName>` 并 push；Conventional Commits（英文）；每轮只打一个 tag；版本号单一来源（`app/build.gradle.kts`）

## 引擎实现现状
- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射 → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；硬约束 LUT 必须在 OETF 后
- [2026-10-13] **FilmParams 契约 kCount=99**；**六处同步**：`FilmParams.h` kCount / 字段声明行 / `fromDoubles` / Kotlin `CameraPresets.neutral()`+`COUNT` / `FilmParamKeys.bySlot`；守卫 `python3 tools/check-filmparams-contract.py`
- [2026-10-13] **`kFsFilm` = 公共模板三段 + 引擎注入段**（单真源 + 运行时切片；锚点缺失退回整体字面量）；**期 B 后基线**：`kFsFilm` 36587B crc32 `0x235cf3f5`、`kFsSigmoid` 24028B `0xfbab6a8c`、film_d5 `0xf891c297`、film_d5_sigmoid `0x7c2646ef`
- [2026-10-13] **filmic 黑白点两层口径**：**代码默认** `[37]/[38]` = **−7.65 / +4.56**；**真机定档**白点 **4.0**（R-3，死白起点 3.55 EV，经预设覆盖）
- [2026-09-27] **MFNR 采集**：maxImages 4 + 帧 1-3 拷贝即 close；prefs `mfnr` 三档（0 关 / 1 自动 / 2 强制）
- [2026-09-30] **MFNR 对齐**：最细精化层 = 1/2 域、tile 64、金字塔 L4→L1；位移量化 2 mosaic px
- [2026-09-30] **P0 时域×空间联合**：4 帧 × 24 同相位邻域 = 96 样本；`uSub`=1 逐通道网格亚像素
- [2026-09-30] **降噪现状**：P0/P4/mismatch 全走 σ²=A·x+B；强度 0–2（1.0=真实 σ）；D-1 色度 à trous 已超 PC；模型优先级 = 用户导入 > 内置实测 > Camera2 > ISO 估算
- [2026-09-30] **DFT 合并**：按 CFA 平面独立 16×16 tile 半重叠；输出 4 张 image2D writeonly，**目标必须 `glTexStorage2D`**；dftGpu 冷机 500~550ms / 热机 830~870ms
- [2026-10-01] **拍照侧引擎**：`g_renderer_still` + 独立 EglCore/FrameRenderer；`nativeStillDestroy` 连 context 一起销毁（空闲 10s）；LUT/YUV 矩阵进程级快照；OOM → 清异常返回 nullptr
- [2026-10-07] **紫边/高频链**：hr_wavelet 默认关；defringe [47] 默认开（替换档旁路）；`[51]` 色度门；**ESD3D2 [54] 默认替换档**（旁路 P4/defringe/D-1）；`[55]` 自动核真机 = 9；[56]/[57]/[58] 默认 0

## PhotonCamera 参照事实（/tmp/photoncamera、/tmp/pc-full）
- [2026-09-28] **PC 降噪**：Spatial merge RBF + Sabre 结构张量 + FullResolutionDenoise 五级金字塔；噪声模型 128-bin 半 bin 居中功率谱
- [2026-09-30] **PC 的"快门即撒手"架构**：`isCapturing` 只锁采集窗口；per-shot `PhotoCaptureContext`；处理完才 close
- [2026-10-03] **PC 色度降噪**：`MeasureMoire`（1/4 分辨率强度图）替换送入 ChromaDenoise 的强度；本体是 Halide capsule（不可读）
- [2026-10-13] **PC 引擎插槽**：9 个引擎（AdobeCurve 0 / AgX 1 / Spektrafilm 2 / DarktableSigmoid 3 / DarktableFilmic 4 / Hncs 6 / Lumix 7 / Canon 8 / Leica 10）；公共模板 `RawEngineTonePass.kt:417-505`（引擎只注入 uniforms/profile/functions 四段，输出契约线性 RGB）

## 工具与脚本
- [2026-10-01] `tools/still-thread-verify.py`、`align-bench`、`p0-subpixel-eval.py`、`check-log-json.py`（**分析前先跑**）、`check-diag-fields.py`（**出包前必跑**）
- [2026-10-25] **`tools/bake-m9-lut.py`**：解码 LUTS.bin → 64³ LUT + 四条判据；`--check-only`、`--size`；理论依据见下条

## 对标数据与测量口径（10-03 → 10-08）
- [2026-10-08] **GLSL/Mali 铁律**：FilmParams 加槽必须四处同改；**`float m[9]` 数组参数与动态索引编不过**（prog_=0 静默 return）；**多行宏不生效**（完全内联）；新 pass 没效果先查 `prog_ != 0`
- [2026-10-08] **色差修复三次纠正**：PC3D 把伪彩摊成宽带 ⇒ color_smooth 前置；判据改局部色度峰；替换值用去饱和；**任何"用邻居值替换"必须先证明邻居未被污染**
- [2026-10-06] **紫边真机基线（1777 尺度，跨场景不可混用）**：台灯逆光 PC 超额 ≈0；红笔亮场景锐化是主放大器（关锐化 +0.12≈PC）；**色散 = 成片域 dRG +0.9~1.0px 而 RAW 域对齐 ⇒ 管线产生**

## 相机能力与标定
- [2026-10-22] **X300 后置 camera id**：0 = 逻辑主摄（0.67–10x）；2 = 物理主摄（RAW 4080×3072，SupportSATRemosaic=1）；3 = 长焦 70.3mm；4 = 超广 15.4mm；5 = 潜望 165mm；6 = 逻辑组（0.6–100x）；三个 23.6mm = id 0/2/6
- [2026-10-22] **ISZ**：remosaic key 全可下发且 set 成功，但 RAW 尺寸/crop 不变，`vsdofFeatureOpticalZoomSets=0` ⇒ 标准版主摄不可达
- [2026-10-22] **相册/删除事实**：`createTrashRequest` 在 vivo 落成真删；`mediaStillExists` = 单条 URI query + `is_trashed`；`saveJpeg` 写盘 + `IS_PENDING=0` + EXIF 完成后才发布 `lastPhotoUri`；`ThumbCache` 全方法 `@Synchronized`

## 相册与预设（10-11 → 10-13）
- [2026-10-13] **参数表单一来源 = `ui/ParamControls.kt`**（拍照界面与编辑器共用 ⇒ 读数不漂移）
- [2026-10-13] **`PhotoViewScreen.kt` 关键纯函数**：`frameAfterPageChange` / `pageAlphaAt` / `bitmapBelongsToPage` / `zoomAboutCentroid` / `previewRequestFor`；`PRELOAD_RADIUS=2`、`SPINNER_DELAY_MS=150`
- [2026-10-13] **`auto_tone`（[35]，默认 0）**：0 = 固定黑白点；1 = 成对自动（引擎的"白点高光感知"只在 1 时生效）

## 引擎插槽与调研（10-13 → 10-14）
- [2026-10-13] **引擎插槽方案** `docs/engine-slots-design.md`：`kFsFilm` 拆三段（公共前段/引擎段/公共后段），**保持单 pass**（初始化期字符串拼接）；引擎契约 5 项 + 私有参数走独立 KV + 资产 manifest 全量哈希 + 失败严格回落引擎 0
- [2026-10-13] **引擎指纹**：引擎 0 `film_d5` = `0x589f8364`；引擎 1 链上 = `0x8fdb4e38`；空转（uToneK=0）= `0x189e4126`；**日志判据 = `raw.render` 的 `tone=1.0`**
- [2026-10-14] **`EngineScope.kt`**：`FILMIC_OWNED_SLOTS` = {5..10, 35, 37, 38, 40}；`NEUTRALIZE` 14 槽（28=1.0 / 20,21,22=1.0 / 36=0 / 35=0 / 5..10=0.01 / 37,38=−7.65/+4.56）；`applyEngineScope` slot∈{0,未注册} 返回同一实例（零回归）；`applyEngineScopeForEditor` 跳过公共段（0.12.61）
- [2026-10-19] **引擎 1 的 7 个 `uSig*` 全是曲线形状常量**（无"对比度"）⇒ 引擎 1 的对比度此前无实现

## 10bit HEIF / 期 B / 色相（10-15 → 10-19，压缩）
- [2026-10-15] 10bit HEIF 交付链定稿；`Bitmap.compress`+`Gainmap` = 4:2:0，4:4:4 + HDR 走 libultrahdr
- [2026-10-19] **期 B**：工作空间切 Rec.2020 线性；交付域保持 sRGB；域错配三处修复（`3fa65af`→`a3aedfe`）

## HDR 交付与自动曝光（10-20，压缩）
- [2026-10-20] **gainmap 数学两版可切**（prefs `gainmap_math`）：0 = PC（offset 1e-4）/ 1 = 官方（offset 1e-7 + sdr<2/255 钳 gain≤2.3）；都归一化 [0.25,4.0]
- [2026-10-20] **App 内 HDR 显示三条件**：`BitmapFactory` + `inSampleSize=1`；`context.findActivity()` 逐层解包；`window.colorMode = COLOR_MODE_HDR`（不检查 `configuration.isScreenHdr`）
- [2026-10-20] **导出 SDR 版**：`nativeExtractSdrBase` 用 `uhdr_dec_get_base_image` 无损取 base
- [2026-10-20] **HDR 与 PC 差距**：SDR base 平均亮度 85.2/98.4 vs PC 50.7；≥240 3.38%/2.22% vs 0.37%；HDR 输出几乎相同（P90 0.714 vs 0.697）
- [2026-10-20] **AE 判据常量**：`kAeRawWhiteEv 2.438307`、`kAeMiddleGrey 0.1845`、headroom 0.1、限幅 ±2EV、高光判据 P99.5；**渲染端 AE 已在 0.12.71 全删**（只剩 ExposurePlanner）
- [2026-10-22] **teq 9 档**：第 i 档对应 q=i−8 EV；云/镜面 q≈−0.5（第 8 档）、天空主体 q≈−2.0（第 6 档）；压深只给第 6 档
- [2026-10-22] **局部对比度实测基线**（1777×2360 同区域）：rawpy 单帧 19.4% / 我们 17.6% / PC 25.9%（PC 在链路里增加细节）
- [2026-10-23] **同机位 EXIF 对拍**：快门 2.20 vs 2.02ms、ISO 50 vs 50 ⇒ 采集一致；成片 p5/p25/p50/p75/p95 = 7.2/34.3/98.7/174/195 vs 12.2/65.6/171.4/230/232（PC 中暗部整段提起）
- [2026-10-23] **提亮对天空饱和量化**：baseEv 0→0.7 时天空 L* 75.1→84.0、蓝天 C* p90 9.7→7.1
- [2026-10-23] **鲁棒中灰 vs 几何均值**（186 样本）：`rob/geo` 中位 +0.16EV、68% 场景更高 ⇒ 不接鲁棒测光的依据
- [2026-10-24] **阴天过曝实测**（090816）：`aeSatRaw 41%`、`hi240 35.9%` ⇒ 长帧放宽在 ISO 贴底时对阴天同样生效（已回退）
- [2026-10-24] **白点 4.8 vs 6.22**（夕阳窗景）：6.22 时 `aeEv` 冲到 2.0、天空 p95 246、>240 占 8.6%；4.8 时 `aeEv` ~1.0、p95 ~210

## 测光与 HDR 对标（10-24）
- [2026-10-24] **逆光同机位实测**（点测光）：我们天空 227/221/216、`hi240 3.35%`；PC 196/187/183、0.22% ⇒ 白平衡不是主因，**单帧装不下 12EV，是 bracket 问题**
- [2026-10-24] **测光实现**：`MeteringTargets.kt`；`SPOT_AE_FRACTION=0.06`、`HIGHLIGHT_AE_FRACTION=0.16`；高光点来自 32×32 网格
- [2026-10-24] **PC HDR 两套体系**：YUV bracket（`!isRawCapture` 门槛）= 0EV×N 堆叠 + 侧帧直通 + Mertens；RAW 模式 = RawmaxExposurePlanner + 全帧进 MGC 融合
- [2026-10-24] **我们的 HDR+ 包围（P3）**：帧序 0/0/+2.2/−1.5；`planBracketExposure` 照抄 PC；侧帧 ISO 钳 3200；frame_scale=2^−ev 全帧进对齐+DFT；per-frame σ²；日志 `hdrFs[]`/`bracket.actual`；0.12.84 UI 默认开
- [2026-10-24] **gainmap 旧版中灰基线 1.1**（P4 已替换为 gainTrue 真实比值）

## P4 基线与徕卡一瞬（10-25/26）
- [2026-10-25] **`tools/hdr-baseline-eval.py`**：JPEG/R 无损解析（MPF 对照 vendored libultrahdr）+ base/gainmap/HDR 重建统计；报告 `docs/reports/hdr-baseline-20261025-REPORT.md`
- [2026-10-25] **P4 现状**：`kFsGainmap` 三档（0=真实比值默认 / 1=旧 sceneY / 2=官方）；`gainTrue` = hdrY×(0.1845/ae_p50_) ÷ sdrY，clamp[1,4]；uniform `uSceneMid`；prefs `gainmap_math` 三档
- [2026-10-25] **LUTS.bin 布局（PC M9 配方）**：427744B；curveOffset 0xcf8 / matrixOffset 0xb44 / calibrationOffset 0xcd0；matrixIndex=CS×5+SAT=2、curveIndex=CS×5+CON=2（sRGB/标准饱和/标准对比）；矩阵 = Q16 整数、**行和 8198/8192/8185（Q13 ÷8192 = 1.000，保 WB）**；曲线 2048 点、首 0 末 1 单调
- [2026-10-25] **M9 配方语义（数据验证）**：矩阵输出 `>>16` 后 = 线性值/8 的 11-bit 编码（clamp 2047 只在纯白）；**曲线输入域 = 线性值 11-bit 编码**（table[368]=0.588 ↔ 线性 0.18→0.59）、输出 sRGB 编码；分支 R≥G 在切换面连续（R 行差 [1152,−1147,0]）
- [2026-10-25] **`leica_m9_ccd.lut`**：64³ uint8 786454B、TITLE "徕卡 M9 CCD"、分类 徕卡；判据 mean 0.13 LSB / max 20 LSB（钳制面）/ 灰轴 1.63 LSB / 格点跳变 56.7（配方固有陡斜率）
- [2026-10-25] **LUT 加载链**：`parseBinary` size∈2..128；引擎 `setLut` → `glTexImage3D RGBA16F size³`；采样 = `clamp(c×(N−1))` + 手写三线性 texelFetch（格点口径 i/(N−1)）
- [2026-10-25] **色温槽语义**：`[88] temp_mired` = 目标白点 mired 偏移（基准 6500K、恒等 0，`EditorExport.kt:495`）；UI K 滑杆右移 = 变暖；**6500K = 不做补偿，不是锁 WB**
- [2026-10-25] **0.12.89 M9 预设参数**：contrast 1.188 / shoulder 0.0256,0.0132,0.2632 / toe 0.227,0.1371,0.7798 / black_ev −4.879 / white_ev 4.0 / sat_shadow 1.0 / sat_high 0.85 / split 0.1,0.05 / grain 20@160 / vignette −0.18,85,55 / LUT leica_m9_ccd×1.0
- [2026-10-25] **recipe-fit 现状**：`--mode camera` 单链拟合（成片优先）；TARGETS[leica_m9] = 52→28.6、85→71.8、122→122.1、160→157.9、200→191.3、243→219.5（暗部 M9、中高段旧观感）；`--white-ev 4.0 --lut-min 1.0 --lat-split --hi-floor 3.5`；报告 `fit/builtin-curves.json`（`--export` 生成）
- [2026-10-25] **真机指纹**（同口径）：M9 样张库彩色 p25 中位 20、≥240 中位 0.08%、C*P90 中位 ≈29；我们一期成片 C*P90 33.2、≥240 0.02%
- [2026-10-25] **方向锁**：MainActivity `screenOrientation="portrait"`（0.12.86 起）
- [2026-10-26] **版本**：0.12.89(538) tag v0.12.89；包 `filmkit-0.12.88-M9TONE.apk`(537)、`filmkit-0.12.89-M9TONE2.apk`(538)
