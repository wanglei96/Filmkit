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
- [2026-09-30] **P0 时域×空间联合**：4 帧 × 24 同相位邻域 = 96 样本；`uSub`=1 逐通道网格亚像素
- [2026-09-30] **降噪现状**：P0/P4/mismatch 全走 σ²=A·x+B；强度 0–2（1.0=真实 σ）；D-1 色度 à trous 已超 PC；模型优先级 = 用户导入 > 内置实测 > Camera2 > ISO 估算

## PhotonCamera 参照事实（/tmp/photoncamera、/tmp/pc-full）
- [2026-09-28] **PC 降噪**：Spatial merge RBF + Sabre 结构张量 + FullResolutionDenoise 五级金字塔；噪声模型 128-bin 半 bin 居中功率谱
- [2026-09-30] **PC 的"快门即撒手"架构**：`isCapturing` 只锁采集窗口；per-shot `PhotoCaptureContext`；处理完才 close
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
- [2026-10-20] **AE 判据常量**：`kAeRawWhiteEv 2.438307`、`kAeMiddleGrey 0.1845`、headroom 0.1、限幅 ±2EV、高光判据 P99.5；**渲染端 AE 已在 0.12.71 全删**（只剩 ExposurePlanner）
- [2026-10-22] **teq 9 档**：第 i 档对应 q=i−8 EV；云/镜面 q≈−0.5（第 8 档）、天空主体 q≈−2.0（第 6 档）；压深只给第 6 档
- [2026-10-22] **局部对比度实测基线**（1777×2360 同区域）：rawpy 单帧 19.4% / 我们 17.6% / PC 25.9%（PC 在链路里增加细节）

## 测光与 HDR 对标（10-24）
- [2026-10-24] **测光实现**：`MeteringTargets.kt`；`SPOT_AE_FRACTION=0.06`、`HIGHLIGHT_AE_FRACTION=0.16`；高光点来自 32×32 网格
- [2026-10-24] **gainmap 旧版中灰基线 1.1**（P4 已替换为 gainTrue 真实比值）

## P4 基线与徕卡一瞬（10-25/26）
- [2026-10-25] **`tools/hdr-baseline-eval.py`**：JPEG/R 无损解析（MPF 对照 vendored libultrahdr）+ base/gainmap/HDR 重建统计；报告 `docs/reports/hdr-baseline-20261025-REPORT.md`
- [2026-10-25] **P4 现状**：`kFsGainmap` 三档（0=真实比值默认 / 1=旧 sceneY / 2=官方）；`gainTrue` = hdrY×(0.1845/ae_p50_) ÷ sdrY，clamp[1,4]；uniform `uSceneMid`；prefs `gainmap_math` 三档
- [2026-10-25] **LUTS.bin 布局（PC M9 配方）**：427744B；curveOffset 0xcf8 / matrixOffset 0xb44 / calibrationOffset 0xcd0；matrixIndex=CS×5+SAT=2、curveIndex=CS×5+CON=2（sRGB/标准饱和/标准对比）；矩阵 = Q16 整数、**行和 8198/8192/8185（Q13 ÷8192 = 1.000，保 WB）**；曲线 2048 点、首 0 末 1 单调
- [2026-10-25] **M9 配方语义（数据验证）**：矩阵输出 `>>16` 后 = 线性值/8 的 11-bit 编码（clamp 2047 只在纯白）；**曲线输入域 = 线性值 11-bit 编码**（table[368]=0.588 ↔ 线性 0.18→0.59）、输出 sRGB 编码；分支 R≥G 在切换面连续（R 行差 [1152,−1147,0]）
- [2026-10-25] **`leica_m9_ccd.lut`**：64³ uint8 786454B、TITLE "徕卡 M9 CCD"、分类 徕卡；判据 mean 0.13 LSB / max 20 LSB（钳制面）/ 灰轴 1.63 LSB / 格点跳变 56.7（配方固有陡斜率）
- [2026-10-25] **LUT 加载链**：`parseBinary` size∈2..128；引擎 `setLut` → `glTexImage3D RGBA16F size³`；采样 = `clamp(c×(N−1))` + 手写三线性 texelFetch（格点口径 i/(N−1)）
- [2026-10-25] **色温槽语义**：`[88] temp_mired` = 目标白点 mired 偏移（基准 6500K、恒等 0，`EditorExport.kt:495`）；UI K 滑杆右移 = 变暖；**6500K = 不做补偿，不是锁 WB**
- [2026-10-25] **recipe-fit 现状**：`--mode camera` 单链拟合（成片优先）；TARGETS[leica_m9] = 52→28.6、85→71.8、122→122.1、160→157.9、200→191.3、243→219.5（暗部 M9、中高段旧观感）；`--white-ev 4.0 --lut-min 1.0 --lat-split --hi-floor 3.5`；报告 `fit/builtin-curves.json`（`--export` 生成）
- [2026-10-25] **真机指纹**（同口径）：M9 样张库彩色 p25 中位 20、≥240 中位 0.08%、C*P90 中位 ≈29；我们一期成片 C*P90 33.2、≥240 0.02%
- [2026-10-25] **方向锁**：MainActivity `screenOrientation="portrait"`（0.12.86 起）
- [2026-10-26] **版本**：0.12.89(538) tag v0.12.89；包 `filmkit-0.12.88-M9TONE.apk`(537)、`filmkit-0.12.89-M9TONE2.apk`(538)

## PC 影调与暗部软边（10-29）
- [2026-10-29] **PC 有两套 tone 引擎**：`AdobeCurveToneAlgorithm`（DCP profile 的 ToneCurve，逐通道 + `adobeRgbTone`）与 `DarktableFilmicToneAlgorithm` / `DarktableSigmoidToneAlgorithm`（与同源，系数按 darktable `computeFilmicToneCurveUniforms` 算）；`assets/dcp/` 只有 10 个 profile，**没有 vivo** ⇒ 该机上不适用 DCP 路径
- [2026-10-29] **亮缝宽度实测**（FILMKIT_20261029-100221 红框 x1626-1942/y3139-3677）：RAW 2×2 块 34px（0.035→0.150）、我们 D4 26px、成片 24px、libraw AHD 14px；对比度 RAW 4.3× / D4 4.8× / 成片 1.5× / AHD 2.6×
- [2026-10-29] **RCD 与 AMaZE 的抖动差**（同一 CFA 输入、粉椅高光边）：R/G lag-1 σ = 0.312（RCD） vs **0.018（AMaZE）**

## 高光三症与饱和掩码（10-30）
- [2026-10-30] **WB 域 vs raw/1023 域**：WB 域中性白 1,1,1（opposed 前提成立）；raw/1023 域是 0.476/1.0/0.684（随 WB 变）。
- [2026-10-30] **粉云实测**（165153.dng）：G 饱和像素 WB 中位 R 1.300 / G 1.000 / B 1.139；参考场 0.944/1.036；块级 mask 覆盖 4.23%（离线）。
- [2026-10-30] **sat mask 判据**：`wb >= gain[c] − 3σ`（σ=√(A·clamp(wb,0,1)+B)），1px 膨胀 + 2×2 聚合，日志 satmP/satmRaw/satmHit/satmCov；`filmkit-0.12.205-hdr-coexist.apk` = 只提亮高光那版。
- [2026-10-30] **gainmap 读数口径**：`raw.render` 的 gm* 恒 0（设计）；真值在 `gainmap.readback` 的 gmMin/gmMean/gmMax，`ratio = 0.25 × 16^g`。
- [2026-10-30] **徕卡标定边界**：`recipe-fit.py` 从 filmic 之后接起，RAW/CCM/WB/kFsFilm 的 sat·split·mono 是"未验证段" ⇒ 预设不在饱和度补偿之后标定。

## 预设开关与理光负片（11-01）
- [2026-11-02] 版本 **0.12.227(678)** 当前；回退 0.12.226；HDR 对照 `filmkit-0.12.205-hdr-coexist.apk`。
- [2026-11-02] **7 条内置预设**（builtin.json，键总数 92）：徕卡一瞬 / 理光负片 / 理光正片 / 富士NC / 哈苏自然 / 柯达5219 / 徕卡黑白；**锁 filmic 的 5 条**（除理光负片、徕卡黑白）。
- [2026-11-02] **64³ 自烘 LUT 四个**：`ricoh_negative_v2`（bake-ricoh-lut）、`ricoh_positive_v1`（同脚本 `--preset positive`）、`fuji_nc_v2`（normalize 归一）、`kodak_5219_deep`（normalize + 暗部加蓝/压黑）；33³ 原资产 `kodak_5219`/`fuji_nc`/`hasselblad_classic` 保留可手选。
- [2026-11-02] **`diag.stats.sat` = `[20][21][22]` 的实际下发值**（判断预设饱和是否生效的唯一日志字段）；`setFilmParams` 换数组后必须把 21 个 applier 缓存置哨兵；改 assets 后单测必须 `--rerun-tasks`。

## 预设群与 LUT 特征（11-02）
- [2026-11-02] **理光正片**：`ricoh_positive_v1` 灰轴 暗 +1.36 / 中 −1.06 / **高光 +0.64**（负片高光是 −1.9）；色相 红 29°、绿 134°、蓝 303°、C 0.98~1.02。
- [2026-11-02] **富士NC**：`fuji_nc` 原资产灰阶 **100%→91.8%**（压白 8.2%）、灰轴 暗 a−2.8 / 中高光 a+3.1~+3.7；`fuji_nc_v2` 归一后 L\* 恒等 0.26、白点 1.43/255、高光去色阈值 min(RGB) 0.86。
- [2026-11-02] **哈苏自然**：`hasselblad_classic` 探针色相 0.0°、灰轴 0、端点 0→100、肤 C×1.23；哈苏官方 13 张样片 C\* p50 11.3 / 黑 11.95% / 白 0.85%，逐张灰轴散布 ±5 无共同符号 ⇒ 不系统偏色。
- [2026-11-02] **柯达5219**：原资产灰阶 10%→15.9%、50%→56.5%、100%→97.3%（抬黑 4.7% = base fog）；灰轴 暗 b−4.8 / 高 b+1.9；`kodak_5219_deep` 加深后暗 b −6.2、黑点压到 0.5/255、暗部 a +0.45。
- [2026-11-02] **暗角实现**：darktable 余弦 `vw=0.5−0.5cos(πt)`、`dscale=radius/100`、`fscale=feather/100`；4:3 竖图角落 cplen 1.252。**M9 机内 JPEG 默认开镜头暗角校正 ⇒ 原厂几乎无暗角**。
- [2026-11-02] **外部资料入口**：柯达电影站 `kodak.com/en/motion/product/camera-films/<id>/`（**单数 product**，`/page/` 与 `motion.kodak.com` 均 404）；哈苏样片 `hasselblad.com/learn/sample-images/x-system/`（84 张，原图 20MB 级）；fujifilm-x.com 是 SPA + Cloudflare，样片不可抓。
