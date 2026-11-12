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
- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；依赖上限锁定（2026.09 版 AndroidX 需 AGP 9.1+/SDK 37）；构建命令 `JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest`
- [2026-09-18] 项目路径含中文：AGP externalNativeBuild 丢中文段（CXX1400），已改自定义 CMake Exec 直驱 NDK

- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、1=前置、2-5 隐藏物理、6=第二逻辑组）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、白阶 1023、maxISO 3200；RGBA16F 无线性过滤
- [2026-09-21] adb：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`；手机存储 Mac 直读 `~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`
- [2026-09-18] 日志/审计导出：`/storage/emulated/0/Android/data/com.filmkit.app/files/{logs,audit}`（vivo 办公套件回传，落点 `~/Downloads/vivo办公套件/`）；未捕获异常写 `logs/crash-latest.txt` + JSONL `app.crash`
- [2026-09-21] 离线工具链：python3 + numpy + PIL + rawpy 0.27；mimosa 插件已禁用；调参脚本 `tools/tuning/`

## 路径与约定
- [2026-09-18] Git 远端 `git@github.com:wanglei96/Filmkit.git` main；**CI 仅手动触发**；出包在本地做
- [2026-09-18] APK 产物：`app/build/outputs/apk/{release,debug}/*.apk` → 根目录 `filmkit-<versionName>-<TAG>.apk`；**出包前 `rm -rf app/build/outputs/apk`**；`*.apk` gitignore
- [2026-09-18] 引擎 so `libfilmengine.so`，JNI 入口 `com.filmkit.app.EngineBridge`；源码 `core/engine/src/`；shader 校验 `tools/validate-shaders.sh`（`tools/bin/glslang`）发版前必跑

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

- [2026-10-01] `tools/still-thread-verify.py`、`align-bench`、`p0-subpixel-eval.py`、`check-log-json.py`（**分析前先跑**）、`check-diag-fields.py`（**出包前必跑**）；`tools/bake-m9-lut.py` 解码 LUTS.bin → 64³ LUT（`--check-only`/`--size`）

## 对标数据与测量口径（10-03 → 10-08）
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
- [2026-10-14] **`EngineScope.kt`**：`FILMIC_OWNED_SLOTS` = {5..10,35,37,38,40}；`NEUTRALIZE` 14 槽（28=1.0/20-22=1.0/36=0/35=0/5..10=0.01/37,38=−7.65/+4.56）；`applyEngineScope` slot∈{0,未注册} 返回同一实例；编辑器跳过公共段（0.12.61）；引擎 1 的 7 个 `uSig*` 全是曲线形状常量。

- [2026-10-15] 10bit HEIF 交付链定稿；`Bitmap.compress`+`Gainmap` = 4:2:0，4:4:4 + HDR 走 libultrahdr
- [2026-10-19] **期 B**：工作空间切 Rec.2020 线性；交付域保持 sRGB；域错配三处修复（`3fa65af`→`a3aedfe`）

## HDR 交付与自动曝光（10-20，压缩）
- [2026-10-20] **gainmap 数学两版可切**（prefs `gainmap_math`）：0 = PC（offset 1e-4）/ 1 = 官方（offset 1e-7 + sdr<2/255 钳 gain≤2.3）；都归一化 [0.25,4.0]
- [2026-10-20] **App 内 HDR 显示三条件**（`inSampleSize=1` + `findActivity()` 解包 + `colorMode=COLOR_MODE_HDR`）；**导出 SDR** 用 `uhdr_dec_get_base_image` 无损取 base。
- [2026-10-20] **AE 判据常量**：`kAeRawWhiteEv 2.438307`、`kAeMiddleGrey 0.1845`、headroom 0.1、限幅 ±2EV、高光判据 P99.5；**渲染端 AE 已在 0.12.71 全删**（只剩 ExposurePlanner）
- [2026-10-22] **teq 9 档**（q=i−8 EV；云第 8 档、天空主体第 6 档，压深只给第 6 档）；**局部对比度基线**：rawpy 19.4% / 我们 17.6% / PC 25.9%。

- [2026-10-24] **测光实现**：`MeteringTargets.kt`；`SPOT_AE_FRACTION=0.06`、`HIGHLIGHT_AE_FRACTION=0.16`；高光点来自 32×32 网格

## P4 基线与徕卡一瞬（10-25/26）
- [2026-10-25] **`tools/hdr-baseline-eval.py`**：JPEG/R 无损解析（MPF 对照 vendored libultrahdr）+ base/gainmap/HDR 重建统计；报告 `docs/reports/hdr-baseline-20261025-REPORT.md`
- [2026-10-25] **P4 现状**：`kFsGainmap` 三档（0=真实比值/1=旧 sceneY/2=官方）；`gainTrue` clamp[1,4]；0.12.230 起改为 `sdrLin + max(0, hdrLin − 1/anchor)`（标准语义，中灰 ratio 恒 1）
- [2026-10-25] **LUT 加载链**：`parseBinary` size∈2..128；引擎 `setLut` → `glTexImage3D RGBA16F size³`；采样 = `clamp(c×(N−1))` + 手写三线性 texelFetch（格点口径 i/(N−1)）
- [2026-10-25] **色温槽语义**：`[88] temp_mired` = 目标白点 mired 偏移（基准 6500K、恒等 0，`EditorExport.kt:495`）；UI K 滑杆右移 = 变暖；**6500K = 不做补偿，不是锁 WB**
- [2026-10-25] **recipe-fit**：`--mode camera` 单链（TARGETS[leica_m9] 六点）；`--white-ev 4.0 --lut-min 1.0 --lat-split --hi-floor 3.5`；`--export` 生成 `fit/builtin-curves.json`
- [2026-10-25/26] **真机指纹**：M9 样张库彩色 p25 中位 20、≥240 0.08%、C\*P90 ≈29；我们一期 C\*P90 33.2、≥240 0.02%；MainActivity 锁竖屏（0.12.86 起）。

## PC 影调与暗部软边（10-29）
- [2026-10-29] **PC 有两套 tone 引擎**：`AdobeCurveToneAlgorithm`（DCP profile 的 ToneCurve，逐通道 + `adobeRgbTone`）与 `DarktableFilmicToneAlgorithm` / `DarktableSigmoidToneAlgorithm`（与同源，系数按 darktable `computeFilmicToneCurveUniforms` 算）；`assets/dcp/` 只有 10 个 profile，**没有 vivo** ⇒ 该机上不适用 DCP 路径
- [2026-10-29] **亮缝宽度实测**（FILMKIT_20261029-100221 红框 x1626-1942/y3139-3677）：RAW 2×2 块 34px（0.035→0.150）、我们 D4 26px、成片 24px、libraw AHD 14px；对比度 RAW 4.3× / D4 4.8× / 成片 1.5× / AHD 2.6×

## 高光三症与饱和掩码（10-30）
- [2026-10-30] **sat mask 判据**：`wb >= gain[c] − 3σ`，1px 膨胀 + 2×2 聚合，日志 satmP/satmRaw/satmHit/satmCov。
- [2026-10-30] **gainmap 读数口径**：`raw.render` 的 gm* 恒 0（设计）；真值在 `gainmap.readback` 的 gmMin/gmMean/gmMax，`ratio = 0.25 × 16^g`。
- [2026-10-30] **徕卡标定边界**：`recipe-fit.py` 从 filmic 之后接起，RAW/CCM/WB/kFsFilm 的 sat·split·mono 是"未验证段" ⇒ 预设不在饱和度补偿之后标定。

- [2026-11-12] 版本 **0.13.0(708)** 当前（dev/main 已合并、tag `v0.13.0`）、回退 0.12.258；**7 条内置预设**（键总 100）；自烘 64³ LUT 三个（`ricoh_positive_v1`/`fuji_nc_v2`/`kodak_5219_deep`）+ **`ricoh_negative_ye` 33³**（LUMIX Lab 导出，自带影调）。
- [2026-11-02] **`diag.stats.sat` = `[20][21][22]` 的实际下发值**（判断预设饱和是否生效的唯一日志字段）；`setFilmParams` 换数组后必须把 21 个 applier 缓存置哨兵；改 assets 后单测必须 `--rerun-tasks`。

## 预设群与 LUT 特征（11-02）
- [2026-11-02] **理光正片**：`ricoh_positive_v1` 灰轴 暗 +1.36 / 中 −1.06 / **高光 +0.64**（负片高光是 −1.9）；色相 红 29°、绿 134°、蓝 303°、C 0.98~1.02。
- [2026-11-02] **富士NC**：`fuji_nc` 原资产灰阶 **100%→91.8%**（压白 8.2%）、灰轴 暗 a−2.8 / 中高光 a+3.1~+3.7；`fuji_nc_v2` 归一后 L\* 恒等 0.26、白点 1.43/255、高光去色阈值 min(RGB) 0.86。
- [2026-11-02] **哈苏自然**：探针色相 0、灰轴 0、肤 C×1.23；官方 13 张 C\* p50 11.3、灰轴散布 ±5 无共同符号 ⇒ 不系统偏色。
- [2026-11-02] **柯达5219**：原资产抬黑 4.7%（base fog）、暗 b−4.8；`kodak_5219_deep` 暗 b −6.2、黑点压到 0.5/255、暗部 a +0.45。
- [2026-11-02] **暗角实现**：darktable 余弦 `vw=0.5−0.5cos(πt)`、`dscale=radius/100`、`fscale=feather/100`；4:3 竖图角落 cplen 1.252。**M9 机内 JPEG 默认开镜头暗角校正 ⇒ 原厂几乎无暗角**。
- [2026-11-02] **外部资料入口**：柯达电影站 `kodak.com/en/motion/product/camera-films/<id>/`（**单数 product**，`/page/` 与 `motion.kodak.com` 均 404）；哈苏样片 `hasselblad.com/learn/sample-images/x-system/`（84 张，原图 20MB 级）；fujifilm-x.com 是 SPA + Cloudflare，样片不可抓。

## 显示域闭环与蓝天（11-04）

- [2026-11-04] **显示域闭环实测**（成片中心 1/2 鲁棒中灰 vs 取景器 `pvDispMid`）：0.1375/0.130、0.0997/0.093、0.0344/0.034 ⇒ 差 0.02~0.13 EV；改前 0.9 EV。日志字段 `dispMid`/`dispAdd`/`dispErr`（0.12.233 起拆开）与 `preview.filmic` 的 `pvDispMid`。
- [2026-11-04] **闭环收敛数据**：15:42:29 `dispAdd 1.18 → dispErr −0.9`（全量步进过冲）；0.7 阻尼+两轮后 16:42/16:49 的 `dispErr` 均在 0.04~0.09。
- [2026-11-04] **PC ML-AE 规格**：`assets/mgc_ae/` 三个 tflite（short 62272B / long 61264B / portrait 62272B）；输入 64×64×9（`ln RGB` + `ln scene` + 语义哨兵，long 拆 4+5 两输入）；输出 logGain，`gain = exp(logGain) − 1e-6`；Mac CPU 三模型一套 6 ms。
- [2026-11-04] **官方理光负片蓝天**：晴天深蓝天 `IP025159` h 270.9 / C\* 17.8 / L\* 46.8；同场景正片 `IP025354` C\* 25.2；阴天街景（6509 第 2 格）h 258.1 / C\* 13.1 / L\* 77.8。样片存 `test_temp/_official_{neg,pos}_sky_*.jpg`。
- [2026-11-04] **LUT 判据 3 改口径**：p99.9 <12（原 max<12 永远被 sRGB 色域边界顶到 29.3）；当前 p99.9 9.3、max 22.8、边界对 166 万个已排除。

## LUT 换血与采集端判据（11-11）

- [2026-11-11] **`ricoh_negative_ye.lut`**（33³、107846 B、title「理光 负片 · 杨子祯」）：由「理光负片 杨子祯(1).cube」逐字节转换，**自带影调**——灰阶 0.25→0.31 / 0.50→0.57 / 0.75→0.78。同场景天空 hue 260.8（旧的归一版 276.2、Filmix 263.2）。
- [2026-11-11] **`nativeEngineGetStats` 新增 `pw999`**：当帧预览链 P99.9 ÷ `g_domain_ratio` 换算到 RAW 等效域。实测预览 1.03 vs 成片 2.91（低 1.88EV，因预览经 ISP tonemap）⇒ 只能当"有没有超白点"的粗判据。
- [2026-11-11] **`hlOn` 语义修正**：条件只剩 `hl_protect >= 1`（原来还要求 `!ae_auto && !bracket && hdr_fusion<=0` ⇒ 出厂恒假、从未生效）。闭环补偿上限 `kDispAddMax = 0.60` EV（`FrameRenderer.cpp` 显示域闭环段）。

## 天空闭环与诊断（11-12）

- [2026-11-12] **预览 vs 成片的 filmic 输入中位**：`pvLastMid 0.1267 / stillMid 0.0276 = 4.59`（+2.2EV）⇒ vivo 的 YUV 去 gamma 后仍被 ISP tonemap；`ratio` 日志（`g_domain_ratio`）实测 2.0→4.59。
- [2026-11-12] **闭环护栏参数**：每步补 EV 后测成片顶部天空 P99（`skyProfile` u8），涨 >0.03（≈8 LSB）或 >0.88 就撤销该步；硬上限 `kDispAddMax 0.60`；`dispErr` 只记账不递归。
- [2026-11-12] **诊断开关**：prefs `sky_diag`（默认 false）→ `nativeEngineSetSkyDiag` → `g_sky_diag`；控制 `skyD4/skyFm/skyOut/d4Y/fmY`，关闭时零额外读回；闭环护栏的 `skyP99` 不受控。工具 `tools/sky-diag.py` 读三段表。
