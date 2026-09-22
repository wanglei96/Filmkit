---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-09-21 19:46
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->

## 环境

- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；JDK17+Gradle 在 ~/.filmkit-dev/（系统无 JDK17/Gradle）
- [2026-09-18] 项目路径含中文（Downloads/胶片相机）：AGP externalNativeBuild 路径解析会丢中文段（CXX1400）——已改自定义 CMake Exec 任务直驱 NDK，见 app/build.gradle.kts
- [2026-09-18] 依赖上限：2026.09 版 AndroidX 需 AGP 9.1+/SDK 37；当前锁定 Compose BOM 2026.06.01 / core-ktx 1.17.0 / activity-compose 1.11.0
- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、6=第二逻辑组、2/3/4/5=隐藏物理、1=前置）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、maxISO 3200、OIS [0,1]；16F 无线性过滤
- [2026-09-18] X300 实测 GL 性能：1024² spike 三段 0.3–0.5ms/段（GPU 中位数）

## 路径与位置

- [2026-09-18] Git 远端：git@github.com:wanglei96/Filmkit.git，主分支 main；CI（.github/workflows/android.yml）在 push main/PR 时构建+单测并上传 APK artifact
- [2026-09-18] APK 产物：app/build/outputs/apk/debug/app-debug.apk；出包覆盖仓库根 filmkit.apk（单文件覆盖制，2026-09-18 起不再按版本号新建副本）；filmkit.apk 已 gitignore，发布走 GitHub Releases/CI artifact
- [2026-09-18] 设备日志导出：/storage/emulated/0/Android/data/com.filmkit.app/files/logs（用户经 vivo办公套件回传）
- [2026-09-18] 审计导出：同上 files/audit/camera-audit-*.json
- [2026-09-18] 引擎 so：libfilmengine.so，JNI 入口 com.filmkit.app.EngineBridge

## 命令与操作

- [2026-09-18] 构建+单测：`JAVA_HOME=$HOME/.filmkit-dev/jdk-17.0.20.1+1/Contents/Home ./gradlew assembleDebug testDebugUnitTest`
- [2026-09-18] 命令行构建替代 JDK：系统默认 Java 8 跑不动 AGP，可用 `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`（Homebrew JDK23，实测 assembleDebug 通过）
- [2026-09-18] 版本管理约定：发版时打附注标签 `v<versionName>`（如 v0.4.5-m2）并 push；提交信息用 Conventional Commits（英文）；版本号在 app/build.gradle.kts（versionCode/versionName）+ docs/开发计划.md 记录
- [2026-09-18] X300 焦段事实：启用镜头等效焦段 15/24/70（超广 id4、主摄 id2/0、长焦 id3）；焦段条由 lensEquivs() 生成，倍率基线 mainEquiv()
- [2026-09-19] 发布现状：最新 v0.5.9-m2（versionCode 40，tag v0.5.9-m2 已推送）；FilmKitApp 启动日志 version 字段需随版本同步改（历次遗漏过）
- [2026-09-19] UI token：ui/FilmTheme.kt（色板 Body/Panel/Cream/Amber→Accent/OnAccent + Mono/Serif + FilmMotion 浮层动效）；prefs 键 accent_argb / histogram / grid / shutter_sound / wheel_zoom / custom_focals / dng_enabled / lens_on_<id> / ev_auto / ae_auto / wb_auto / wb_k / iso / shutter_v / ev
- [2026-09-19] 崩溃捕获：未捕获异常写 files/logs/crash-latest.txt + JSONL `app.crash`（含堆栈）；X300 闪退排查先导日志看这两处
- [2026-09-19] WB 色温估计：优先 CaptureResult.COLOR_CORRECTION_GAINS（X300 不回填），兜底变换矩阵对角线 + 校准系数 WB_CAL_R=0.923/WB_CAL_B=1.238（单点推得，M3 灰卡精校）；kFromGains=50K 步进扫描组合对数距离
- [2026-09-19] adb 安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`
- [2026-09-20] 发布现状：最新 v0.5.19-m2（versionCode 50，tag 已推送）；FilmKitApp 启动/崩溃 version 字段已同步 0.5.19-m2
- [2026-09-20] prefs 键新增：jpeg_quality（95/100 成像质量）、level（水平仪）、yuv_709（静拍解码矩阵，已定论 601 正确保留备查）；既有 grid（三分线，入口移至菜单面板构图辅助区）
- [2026-09-20] 手机存储可从 Mac 直读：`~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`——成片原样张可直接取做像素分析（PIL/numpy 对比有效）
- [2026-09-20] 成像链结构：静拍 CPU YUV→RGB（FrameRenderer.cpp，矩阵参数化 setYuvMatrix/JNI nativeEngineSetYuvMatrix）→ kFsFilm log 域分段延展（±4EV 有效 + 域外半斜率外推至 ~±9EV）→ JPEG 质量 95/100 + exifinterface 补写 EXIF
- [2026-09-20] 发布现状：最新 v0.5.27-m2（versionCode 58，tag 已推送）；M2 全部完成，M3 开工（基建：计时+shader 校验）
- [2026-09-20] M 档 RAW 链：prefs raw_mode 快捷开关（顶栏「M」）→ RAW_SENSOR 4080×3072 → DngCreator（dng_enabled 才存底片，MIME image/x-adobe-dng）→ 引擎 submitRaw：R16UI 整型纹理（texelFetch+UNPACK_ROW_LENGTH）→ shader 黑阶/WB/bilinear/CCM → 汇流 pass2/3；prefs capture_aspect 画幅持久化
- [2026-09-20] X300 RAW result 键真机确认回填：SENSOR_NEUTRAL_COLOR_POINT（如 0.4895/1.0/0.671）、COLOR_CORRECTION_TRANSFORM（对角 ~1.5/1.14）、SENSOR_DYNAMIC_BLACK_LEVEL（64×4）、白阶 1023；raw.meta 日志可核对
- [2026-09-20] raw.render 日志：uploadMs/passMs/totalMs（glFinish 后计时，真实端到端）；M3 性能出口=12MP 全链路 ≤900ms（标准档）
- [2026-09-20] shader 校验：tools/validate-shaders.sh（提取 FrameRenderer.cpp 全部 GLSL 按 vert/frag 交 glslang）；本地二进制 tools/bin/glslang（16.6.0，gitignore）；发版前必跑
- [2026-09-21] 发布现状：最新 v0.5.32-m3（versionCode 63，tag 已推送）；M3-2 降噪 v1 真机验证完成（彩噪达优/细节过猛），下一步调参
- [2026-09-21] prefs 键新增：noise_reduction（bool，默认 true=降噪标准档，仅 RCD 质量档生效）；设置页「RAW 降噪」行
- [2026-09-21] raw.render 日志字段追加：dn("std")/ns(实测 σ)/dnMs（P4 计时）；raw.meta 加 noise 字段——X300 实测不回填 SENSOR_NOISE_PROFILE（恒 0），引擎回退 0.030
- [2026-09-21] 降噪管线资源：tex_mos_（P0 输出线性 mosaic，raw 尺寸）、tex_dn_（P4 输出，dst 尺寸）、程序 prog_raw_dn_/prog_chroma_dn_；shader 共 13 条（校验 13/13）；纹理单元：P2 tex=0/mos=1/dir=2，P3 tex=0/mos=1/dir=2/g=3
- [2026-09-21] 降噪真机数据（ISO~570 同链路 A/B）：彩噪 1.41→0.74（达优 ≤1.0）、亮噪 1.56→0.68、细节 Laplacian -48% 过猛；耗时 开 640ms / 关 676ms / bilinear 133ms（P0 mosaic float 使 P1 提速）
- [2026-09-21] prefs 键新增：demosaic_quality（bool，默认 true=RCD 质量档；false=bilinear 快速档）；设置页「RAW 去马赛克」行
- [2026-09-21] raw.render 日志字段：uploadMs/passMs/totalMs/w/h + demosaic("rcd"/"bilinear") + RCD 时 dirMs/gMs（真机实测 477ms = dir 58 + g 7 + 色度胶片 ~238 + 上传 ~30 + 读回 ~150）
- [2026-09-21] RCD 管线资源：新增纹理 tex_dir_/tex_g_（RGBA16F，raw 尺寸）、程序 prog_rcd_dir_/g_/chroma_；shader 共 11 条（校验脚本 11/11）；CFA LUT uCfaLut 由 cfaLutOf 编码（k0..k3 依次在 bits 0/2/4/6，GRBG=0x61）
- [2026-09-22] 发布现状：最新 **v0.6.8-m4（versionCode 88）**；本窗口发 v0.5.48-m3（EXIF）→ v0.6.0…v0.6.8-m4（M4-1 全链路）。**tag 规则：每轮对话只打一个 tag（验收版）**、中间验证包不打；远端当前仅 4 个 tag：v0.5.27-m2 / v0.5.47-m3 / v0.5.48-m3 / v0.6.8-m4
- [2026-09-22] **FilmParams 契约 35 项**（DoubleArray，Kotlin=ui/CameraPresets.kt）：28=基准影调强度、29=高光重建 hl_recon、30=过曝提示 zebra_thr(0.95)、31..33=色彩外观层、**34=LUT 强度**（0=旁路）；引擎按实际长度读取（下限 26）
- [2026-09-21] prefs 键新增：base_tone（bool，默认 true，基准影调）、hl_recon（bool，默认 true，高光重建仅 RCD）、zebra（bool，默认 true，过曝斑马纹；开关在快捷面板，日志 quick.zebra）
- [2026-09-21] 降噪两档定稿参数：标准 P0 srK 0.28 / P4 scK 2.20、强 P0 2.20 / P4 2.60；raw.render 记 dn/srK/scK/ns/nsHal/dnMs
- [2026-09-21] 色彩内核参数（kFsFilm baseTone）：黑点 0.0020 / 枢轴 0.2184（线性 0.22）/ p 1.20 / 中间调 gamma 0.90；高光去饱和 smoothstep(0.85,1.30,n0) 向峰值收敛；raw.render 记 tone/hl
- [2026-09-21] **X300 提供镜头阴影数据**：STATISTICS_LENS_SHADING_CORRECTION_MAP 17×13、MODE=1、中心增益 1.000、角落 6.236（R 通道）→ 未校正时角落原始亮度仅中心 ~16%；日志 camera.raw.lsc
- [2026-09-21 复核订正] **色彩量化基准**：旧记录（165345 vs libraw：饱和 -21% / 分离 -29% / 近中性 +10pp）**混入了亮度差** —— libraw 侧 rawpy 默认 `auto_bright=on` 把亮度自动拉高约 2.5×（37.6→94.4），`sep`/`neutral` 是绝对量，跨图比较必须同亮度。**各自原生输出下用 CIELAB C\* 比**：我们 9.25 @ 亮度 63.5 vs libraw 标准链（`no_auto_bright=True`）8.42 @ 37.6 → **我们色度不低**；色域裁剪（CCM 后负值仅 0.7%）与影调（base_tone 开关 A/B 反而开更高）均排除。→ "寡淡"是伪结论，真实差异是**色调映射策略**（我们亮度落在 libraw 的 37.6/94.4 之间）；测色度首选 **C\***，别用随亮度漂移的 sat/sep
- [2026-09-21 更正] **色彩标定数据要分两个面看（别混淆）**：① **DNG 文件**（DngCreator 产物，小端；`tools/dng-color-dump.py` 实测）含 ColorMatrix1/2（D65/A）、CameraCalibration1/2（单位阵）、CalibrationIlluminant1/2（21/17）、AsShotNeutral、BaselineExposure、OpcodeList2（17×17 GainMap），**不含 ForwardMatrix1/2**；② **CameraCharacteristics**（v0.5.45 真机探针 `camera.raw.calib`）**给全**：SENSOR_COLOR_TRANSFORM1/2、SENSOR_FORWARD_MATRIX1/2、SENSOR_CALIBRATION_TRANSFORM1/2（单位阵）、SENSOR_REFERENCE_ILLUMINANT1/2（21/17；**注意 illuminant2 类型是 Byte**）。结论：这些键**只在 CameraCharacteristics**（静态标定）不在 CaptureResult；"DNG 缺标签"只是 DngCreator 没写，"引擎拿不到"是错的
- [2026-09-21] **DCP 关键事实**（调研核验）：ColorMatrix1/2 不参与颜色校正（只反推 CCT）；决定成色的是 ForwardMatrix + HueSatMap/LookTable（作用在已白平衡的线性数据上）；DCP 的 LUT 不能改灰度/不能提升饱和度；Adobe DNG SDK 许可宽松（可再分发，商业需 indemnify）；dcamprof 为 GPLv3（仅离线生成可用）；厂商 DCP 不可随包分发
- [2026-09-21] 离线分析工具链（本机）：python3 + numpy + PIL + **rawpy 0.27**（`postprocess(use_camera_wb=True, gamma=(2.222,4.5))`）；样张可从 `~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/.../pictures/filmkit/` 直读
- [2026-09-21] **LSC 实现（v0.5.43）**：`submitRaw` 新增 lsc/lsc_cols/lsc_rows → RGB16F 网格纹理（NEAREST，shader 内手写 4-tap 双线性）；uniform `uLsc`/`uLscSize`；无数据/关闭 → 1×1 全 1 恒等；prefs `lsc`（设置页「镜头阴影校正」）、日志 `settings.lsc`、`raw.render.lsc`（"17x13"/"off"）
- [2026-09-21] **实时直方图（v0.5.44）**：`kFsHist` + `FrameRenderer::histogram(int*,int)`（渲染线程，256×96 RGBA8 + readPixels + 256 桶）；JNI `nativeEngineHistogram(IntArray)`；UI 在 IO 线程 ~7fps 轮询（`RenderClient.pollHistogram`）+ Canvas 峰值开方压缩；prefs `histogram`
- [2026-09-21] **DNG 标准色彩链（v0.5.46）**：`app/src/main/java/com/filmkit/app/camera/StdColorMatrix.kt`（X2S = XYZ D50→线性 sRGB 常量；只支持 illuminant 21/D65 与 17/A）；`prefs ccm_std` 默认开，日志 `settings.ccm_std`、`raw.meta.ccmSrc=std|hal`；真机实测 std 矩阵 = 1.622,-0.554,-0.068,-0.141,1.343,-0.202,0.008,-0.576,1.568
- [2026-09-21] **出包命名**：`filmkit-<versionName>.apk`（出包前删旧包）；`.gitignore` 的 `*.apk` 已覆盖
- [2026-09-22] **内置 LUT（已入库，M4-1）**：`app/src/main/assets/luts/` 6 个 .cube（33³、ASCII 文件名，显示名取 TITLE＝原版名、暂不中性化），仓库根 `luts/` 原件已删；`lut/CubeLut.kt` 解析（手写扫描，畸形输入抛异常）、`LutLibrary` 清单+缓存（日志 `lut.parse`）、`LutPreview` 卡片预览（固定测试图 `assets/lut-preview-src.png` × LUT 一次性生成，缓存 `filesDir/lut-preview/<key>@320.png`，日志 `lut.preview`）；引擎 `FrameRenderer::setLut`（RGBA16F 3D 纹理；**init 建 1×1×1 恒等纹理、关闭态也必须绑定**）+ kFsOut/kFsHist 内 tetrahedral 采样（接在 OETF 之后）；JNI `nativeEngineSetLut`（`EngineBridge` → `RenderClient.setLut` → `CameraController.setLut`，会话重建后 start() 自动重传）
- [2026-09-22] prefs 键新增：`lut_id`（选中内置 LUT）、`lut_strength`（LUT 强度 0..1，默认 1）、`lut_in_name`（LUT 名写入成片文件名，默认开）、`focal_strip`（焦段条显示 `focal`|`mult`，默认 focal）；日志 `settings.lut` / `settings.lut_in_name` / `quick.focal_strip`
- [2026-09-22] `tools/gen-lut-previews.py`：生成卡片预览用的固定测试图（默认合成场景；`--input 中性照片.jpg` 换图、`--dump DIR` 离线导出各 LUT 预览审查）；测试图来源 CC0（Wikimedia Commons "Fishmonger smiling"，许可见 `app/src/main/assets/lut-preview-src.LICENSE.txt`）
- [2026-09-21] **色彩外观层实现（v0.5.44，参数已回中性）**：`kFsFilm` 的 `uLook`/`uLookSat`/`uLookChroma`（FilmParams[31..33]）；设置页「色彩外观层」→ prefs `color_look`
- [2026-09-22] **内置 LUT 资产改为 42 个 .lut（v0.6.9–v0.6.13）**：格式 `[size:1B][titleLen:1B][title(UTF-8)][catLen:1B][category(UTF-8)][data: size³×3B RGB]`（索引同 .cube，红最快）；源 `~/Downloads/LUT/滤镜.cube/` 的 43 个 64³（`Ricoh-HDF` 与 `Ricoh-Positive` md5 相同，去重后 42）；`tools/gen-lut-assets.py` 重采样到 33³ uint8（4.3MB，ASCII 要 40MB）；`LutLibrary` **扫目录**不再硬编码；显示名与品牌分类读资产头部；prefs `lut_cat_<id>` / `lut_cats` 存归属覆盖与自定义分类（**不动资产**，重新生成资产不冲掉分类）；强度上限 **150%**
- [2026-09-22] **Material3 暗色主题（v0.6.11）**：根因 `MainActivity` 里是**空的 `MaterialTheme {}`**（项目此前从没用过它），所有 M3 组件（`AlertDialog`/`OutlinedTextField`）吃默认浅色方案；`FilmTheme.colorScheme()` 用 `darkColorScheme` 把语义色映射到项目色板（`surface`→`PanelSolid` 不透明 `0xFF141414`/`onSurface`→Cream/`outline`→ChipBorder/`primary`→Accent），`remember(accent)` 缓存防全树重组
- [2026-09-22] **基准影调 baseTone（v0.6.17–v0.6.19 定稿）**：`ev 0.35`（关自适应时的固定线性曝光）/ `blk 0.0080` 黑点 / `mn 0.2184` 中灰不动点 / `p 1.60` S 强度；`g` 幂提亮已删；高光 `knee 0.80` = 自适应目标白点，曲线 `lc = knee + (1-knee)(1-exp(-u))`，`u=(l-knee)/(1-knee)`；去饱和 `mix(cc, vec3(lc), smoothstep(knee, 1.6, l))`
- [2026-09-22] **自适应影调（v0.6.18）**：`FrameRenderer::histogramFrom(src, out, n)`（源参数化，UI 直方图仍读 `tex_work_`）+ `updateAutoGain(src)`；显示域 P95（64 桶直方图）→ `lin = disp^2.2` → `gain = clamp(0.80/lin, 1, 4)`；EMA `0.7*old + 0.3*new`（首帧直通）；`FilmParams[35] auto_tone`（1 开 / 0 退回 `exp2(0.35)`）；设置页「自适应影调」→ prefs `auto_tone`（默认 true）、日志 `settings.auto_tone`
- [2026-09-22] **降噪与色彩外观现状（v0.6.19）**：P4 色度系数标准 **2.75** / 强 **3.25**（由 2.20/2.60 上调，因提亮 ~1.1EV 使色度 σ +84%）；`CameraPresets` 的 `look_strength=1.0` / `look_sat=1.0`（**全局饱和保持中性**）/ `look_chroma=1.25`
- [2026-09-22] **`FilmParams` 契约扩到 `kCount = 36`**（新增 [35] auto_tone）；`fromDoubles` 尾部追加 `if (n > 35) auto_tone = v[35];`，Kotlin 侧 `CameraPresets.neutral()` 同步为 `DoubleArray(36)`
- [2026-09-22] **日志与 prefs 增量**：`raw.calib` 按 **cameraId 各记一条**并加 `cameraId` 字段；`raw.meta` 加 `lens` 字段；prefs 新增 `auto_tone`、`lut_cat_<id>`、`lut_cats`
- [2026-09-22] **取证结论：X300 主摄与长焦共用同一套标定矩阵** — 日志实测 `cameraId=0` 与 `cameraId=3` 的 `ct1/fm1` **逐位完全相同**（`ct1=0.6668,-0.1589,…`、`fm1=0.6731,0.1950,…`）；但 `AsShotNeutral` 与 LSC 网格**按镜头分别给**（LSC corner 主摄 6.24 / 长焦 3.43）
- [2026-09-22] **`tools/tone-scan.py`**：从成片反推 baseTone 输入 + 参数扫描 + 出对照图；`--legacy` 用 ≤0.6.13 的旧归一化公式（`t = xn/mn` 或 `(xn-mn)/(1-mn)`）；前提是标准预设下整链 = baseTone + OETF（filmCurve 中性恒等、`uSat=1`、`uSplit=0`、外观层恒等）
