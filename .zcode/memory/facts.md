---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-09-29 19:45
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->
<!-- 2026-09-29 d3 整理:删除被取代的"当时现状"条目,当前有效参数与事实未删。 -->

## 环境

- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；依赖上限锁定（2026.09 版 AndroidX 需 AGP 9.1+/SDK 37）
- [2026-09-18] 项目路径含中文（Downloads/胶片相机）：AGP externalNativeBuild 丢中文段（CXX1400），已改自定义 CMake Exec 直驱 NDK
- [2026-09-23] 构建命令（本机已验证）：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest`（系统默认 Java 8 跑不动 AGP）
- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、6=第二逻辑组、2/3/4/5=隐藏物理、1=前置）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、白阶 1023、maxISO 3200；**16F 无线性过滤**；实测 1024² 三段 spike 0.3–0.5ms/段
- [2026-09-29] X300 compute 能力（caps.detected 实测）：`maxComputeSharedMemory 32768`、`maxComputeInvocations 1024`、image units 21、GPU timer 可用；RGBA16F + `GL_FLOAT` readPixels 可用
- [2026-09-21] adb 安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`；手机存储可 Mac 直读：`~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/storage/emulated/0/Pictures/`
- [2026-09-18] 设备日志导出：`/storage/emulated/0/Android/data/com.filmkit.app/files/logs`（用户经 vivo 办公套件回传，落点 `~/Downloads/vivo办公套件/`）；审计导出同目录 `files/audit/`
- [2026-09-21] 离线分析工具链：python3 + numpy + PIL + rawpy 0.27（`postprocess(use_camera_wb=True, no_auto_bright=True, gamma=(2.222,4.5))`）
- [2026-09-27] mimosa 插件已禁用（`~/.zcode/cli/config.json` enabledPlugins=false，备份 config.json.bak-mimosa）；调参脚本在 `tools/tuning/`（filmic_offline/filmic_verify/filmic_preview/sky/whole_cmp）

## 路径与位置

- [2026-09-18] Git 远端：`git@github.com:wanglei96/Filmkit.git` main；CI（.github/workflows/android.yml）push main/PR 构建+单测+传 APK artifact
- [2026-09-18] APK 产物：`app/build/outputs/apk/debug/app-debug.apk` → 根目录 `filmkit-<versionName>.apk` 单文件覆盖制；**出包前 `rm -rf app/build/outputs/apk`**（防残留旧 so）；`*.apk` gitignore
- [2026-09-18] 引擎 so：`libfilmengine.so`，JNI 入口 `com.filmkit.app.EngineBridge`；引擎源码 `core/engine/src/`（jni/Bridge.cpp、render/FrameRenderer.cpp、render/Align.{h,cpp}、spike/SpikePass.cpp、spike/SpikeFftMerge.cpp）
- [2026-09-18] 崩溃捕获：未捕获异常写 `files/logs/crash-latest.txt` + JSONL `app.crash`
- [2026-09-20] shader 校验：`tools/validate-shaders.sh`（提取 FrameRenderer.cpp 的 R"(...)" 交 glslang，ES 3.1）；本地二进制 `tools/bin/glslang`（16.6.0，gitignore）；发版前必跑
- [2026-09-29] 频域降噪 spike：`core/engine/src/spike/SpikeFftMerge.{h,cpp}`（v0/v1/v2 三变体）+ JNI `nativeRunFftSpike(w,h,iters,overlap,variant)`；入口=设置页「能力 Spike」→「DFT 合并 spike」；测量包 `filmkit-spike-fft.apk`

## 命令与版本约定

- [2026-09-18] 版本管理：发版打附注标签 `v<versionName>` 并 push；Conventional Commits（英文）；版本号单一来源（build.gradle + BuildConfig，v0.10.22 起 `FilmKitApp.VERSION = BuildConfig.VERSION_NAME`）
- [2026-09-22] **每轮对话只打一个 tag（验收版）**；中间验证包只出 APK 不打 tag；tag 清单与恢复命令 `docs/git-tags-backup-20260922.txt`
- [2026-09-29] **发布现状**：最新 **v0.10.31-m4（versionCode 183，`fde052f`）**，已推 main，根目录 `filmkit-0.10.31-m4.apk`；**未打 tag**（高光线待明天光线复验后收）；测量包 `filmkit-spike-fft.apk` 并存
- [2026-09-29] **版本沿革速查**：177（WB 修复）→178→…→183（0.10.31，高光线待复验）→
  184（0.10.32 C 模型全链）→185（0.10.33 回归修复+导入/切换）→186（0.10.34 结构 A+滑杆）→
  187（0.10.35 AE 锁定+多摄标定）→**188（0.10.36-m4 通道序修复，tag `v0.10.36-m4` 已推）**
- [2026-09-29] **Camera2 SENSOR_NOISE_PROFILE 真机形态**：Array<Pair<Double,Double>> =
  4 个 (S,O) **标量对**（CFA 相位序、per-frame 无 gain 字段）——SDK 签名
  `Pair<Double, Double[]>` 与真机不符；`as? DoubleArray` 对装箱数组永败
- [2026-09-29] **X300 三摄噪声事实**：HAL 对隐藏物理镜头返回主摄模型（pairs 逐位相同）；
  实测真实倍数 70MM=3.56×、15MM=2.16×（DNG 差分比值法）；内置标定
  `assets/noise_models/x300_{70mm,15mm}.c`（A=S/iso、D=HAL O）按"V2509A+等效焦距"匹配；
  同刻三镜头 A/B 残余噪声 1.22×/1.45×（1.0 档）

## 引擎实现现状（2026-09-29）

- [2026-09-27] **管线顺序（定稿）**：多帧融合(Bayer) → 黑阶+WB → 去拜耳 → LSC → 高光重建 → CCM → 局部色调映射(可选) → filmic/AgX → 基准曝光+EV → 高光滚降 → 锐化 → OETF → 3D LUT → 颗粒 → JPEG；LUT 必须在 OETF 后
- [2026-09-29] **FilmParams 契约 kCount=43**：`[0] exposure_ev=base_ev+用户EV`（filmic 前线性域）、`[1]/[3] wb`、`[20..22] sat_comp`、`[29] hl_recon`（槽位保留不消费）、`[39] sharpen`、`[40] gamut`、`[42] white_hl`；按实际长度读取（下限 26）
- [2026-09-29] **影调链现状**：filmic 原生件（darktable v5 固定参数）——固定曲线（base_ev 默认 +0.7，v0.10.25）；黑白点自动三版真机证伪后默认关；`updateFilmicEV` 的 white 判据 = max 驱动探针 + **整体 +ev**（渲染域对齐，v0.10.31）；statistics 512×288、P99/P99.9/P100，诊断字段 `w99/w999/wmax/cp` 在 raw.render/preview.filmic
- [2026-09-29] **gamut mapping v5**（v0.10.17 恢复完整五步：色度 min+相锁+Y clamp+desaturate(0)+Yrg+clip chroma）；**输出 OETF 真 sRGB**（三处同步：kFsOut/kFsHist/measureSceneStats.to_lin）；**原生小波高光重建**（[41]，阈值恒 1.0）；锐化 = 线性显示域 luma USM（kFsOut 内，[39]）
- [2026-09-29] **降噪现状（C 专项后）**：P0/P4/mismatch 全部走噪声模型 **σ²=A·x+B**
  逐像素（P0 `sr=√(A_c·x+B_c)`；P4 同域 + LSC 增益传播；mismatch = var(ref)+var(alt)；
  融合后系数 ÷neff）；强度 = 滑杆 0–2（prefs `noise_reduction_strength`，1.0=真实 σ，
  k_p0=s / k_p4=1.5s）；**tile RMS 归约 pass 就位**（A 的 σ 输入底座）；
  模型优先级：用户导入标定 > 内置实测（型号+焦距）> Camera2 > ISO 估算
- [2026-09-27] **MFNR 实现**：对齐 `render/Align.{h,cpp}`（kMfnrTile=64@1/2 域 = 128 mosaic；L4→L2 金字塔 SAD + 亚像素）；融合 `kFsMfnrFuse`（4 帧、逐通道网格亚像素双线性、权重来自 mismatch）；`kFsMfnrMismatch`（m=d²/(d²+sσ²)，32px cell）；日志 `mfnr.stage`/`raw.render`（motMed/nsSng/misD 等）
- [2026-09-27] MFNR 采集：maxImages 4 + 帧 1-3 拷贝即 close；设置三档 prefs `mfnr`（0/1 自动暗光 ISO≥600 或 ≥25ms/2 强制）
- [2026-09-28] MFNR 阶段二参数：`kMisLo=0.10/kMisHi=0.35/kMotLo=8/kMotHi=20/kOffDev=6/kMisDilate=2`；ns 折减 `ns = ns_single/√neff`；短参考帧 `MFNR_REF_MAX_EXPOSURE_NS=15ms`、压缩比 ≤1.5×
- [2026-09-28] X300 ISO 能力：id0 主摄 `SENSITIVITY_RANGE 50..19200 / maxAnalog 3200 / boost 100..100`；id1 前置 `50..7680`
- [2026-09-27] **测量方法（噪声）**：低频判据选块（32×32 box 低频图梯度 <1.5）+ 16 块级高频残差 std；跨噪声水平比较必须无偏；σ/亮度归一后跨图可比
- [2026-09-29] **高光白的全链证据**（172002）：RAW 满井饱和 0.001%；LSC 网格（OpcodeList3 提取：4 平面 13×17、四角 5.0~5.3、中心 1.0、damp 后 ~3.85）应用后渲染域 ≥1.0 占 9.88%——白是渲染域产物非采集过曝
- [2026-09-29] **DNG OpcodeList3 的 LSC 网格提取**：tag 51009、大端 f32、4 平面（R/Gr/Gb/B）×221 点（13 行×17 列）、数据偏移 `allf[24/268/512/756]`；opcode 头部 top/left/bottom/right 解析仍错位、覆盖范围假设未验证（模拟先怀疑模拟）

## PhotonCamera 参照事实（/tmp/photoncamera，hinnka 版）

- [2026-09-28] **PC 实现事实**：① 多帧曝光 = 长帧快门硬上限 10ms + 模拟增益 cap（fallback 800）；② filmic = darktable v5 默认固定参数（black −7.65/white 4.56/power 3.6148，中灰锚定）；③ 无 tone 侧场景归一（`sceneExposureGain` 是 HDR 参考链专用）；④ 降噪 = MGC 恢复版：**Spatial merge 空间域 RBF**（`processor/GlesMgcRawSpatialShaders.kt:1654` mergeBayer）+ **Sabre 结构张量各向异性核**（`GlesMgcRawSabreShaders.kt:139` constructCovariance）+ **FullResolutionDenoise 五级金字塔**（`raw/MgcFullResolutionDenoise.kt`，频响 `(respOffset−cos²)+(cos+cosOff)²`，luma/chroma SNR 5/20/40 调参 protobuf `assets/mgc_denoise/*.binarypb`）；⑤ 噪声模型 = 128-bin 半 bin 居中功率谱（`processor/MgcSpatialDenoiseModel.kt`，绿通道三抽头相关拟合）；⑥ 去马赛克噪声传播标定 `raw/DemosaicNoisePropagationCalibrator.kt`（O(N log N) FFT 方向功率谱）
- [2026-09-28] PC 局部色调映射：`DngPhotonLocalToneMapper.kt`（Local Laplacian GLES3.1 compute）+ PGTM 生成器（HDR 路径，SDR 生效条件未确认）
- [2026-09-28] PC 的色彩体系 = Hncs（`HncsProfile.kt` 等）；`colorCorrection` 只是低光去饱和；用户实测本次选 filmic 不经 DCP
- [2026-09-24] 我们的 DNG 未写 BaselineExposure（vivo DngCreator 不写该 tag）；DNG 无 ForwardMatrix1/2（Characteristics 给全）
- [2026-09-24] `.hcurve` 格式（哈苏资产，不可复制）：68B 头 + 65536×uint16 LE；来源哈苏专有 SDK

## 工具与脚本

- [2026-09-29] `test_temp/analysis/`：g_hl_auto.py（白点判据离线复算）等 g_* 分析脚本；样片 `test_temp/FILMKIT_*.dng`（gitignore）
- [2026-09-22] `tools/`：validate-shaders.sh、dng-color-dump.py、color-metrics.py、gen-lut-assets.py（64³ cube→33³）、gen-lut-previews.py、tone-scan.py、chroma-denoise-eval.py、ccm-chain-ab.py、check-srgb-oetf.py、check-lsc-mapping.py、lut-audit.py
- [2026-09-22] 内置 LUT 资产：42 个 `.lut`（uint8、33³、4.3MB；格式 `[size][titleLen][title][catLen][category][data]`）；与 `~/Downloads/LUT/滤镜.cube/` 64³ 逐点一致；显示名/分类在资产头，prefs `lut_cat_*`/`lut_cats` 存覆盖
- [2026-09-24] `docs/research/` 11 份 + README 索引（00=活入口、01–08 证据附录、**09=频域降噪专项（2026-09-29）**、90=原始转储勿删）
