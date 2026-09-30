---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-09-30 12:30
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
- [2026-09-29] **版本沿革速查**：177（WB 修复）→178→…→183（0.10.31，高光线已复验通过未打 tag）→
  184–188（0.10.32–0.10.36 C 专项，**tag `v0.10.36-m4` 已推**）→
  189–201（0.10.37–0.10.49 A 专项攻坚，见 handoffs/A专项DFT合并与Mali写入丢失攻坚；
  当前 **0.10.49-m4（201）= compute program 现场创建，待真机验证**）
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

- [2026-09-30] **A 专项（DFT 合并）实现现状**：`kFsMfnrFft` compute（按 CFA
  平面独立合并、16×16 平面 tile 半重叠、寄存器 FFT、内核内 tile RMS→σ、
  输出 4 张 RGBA16F image2D writeonly 相位组静态分支）+ `kFsMfnrFftSum` 收口
  进 tex_fused_；**compute program 拍照时现场创建用完删除**（init 创建的在该
  Mali 上写入丢失）；prefs `mfnr_dft`；诊断 `mfnr.fft.store_check`
  （sum/micro/spikeOk）+ `store_lost` 自检回退 mismatch；dftGpu 实测 477–798ms
  （门槛 250ms，8×8 tile 储备未动）；`tools/fft-mirror` 离线镜像通过
- [2026-09-30] **CI（android.yml）不再上传 APK artifact**（500MB 配额）；
  validate-shaders.sh 的 comp 校验 = 430 core 转换（有 ESSL 严格性盲区，见
  lessons）；glslang ES profile 只放行 r32f image
- [2026-09-30] X300 Mali compute program 规律：**init 创建 → 写入全丢；现场
  创建 → 正常**（spikeOk:true ×2 对照实锤）；GLSL 混链（跨版本 vs/fs）报
  "Shader languages do not match"

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

## D 专项实现现状（2026-09-30，v0.10.53）

- [2026-09-30] **DFT（A 专项）落地形态**：相位组输出 4×RGBA16F image2D
  writeonly，**必须 glTexStorage2D**；tile 索引 `tx0 = 4·wg.x + gx − 1`、
  `ty = 2·wgRow + gy − 1`；uCfaLut 必须下发；compute program 拍照时现场创建；
  `mfnr.fft.store_check`（sum/micro/microSt/spikeOk）+ store_lost 回落 mismatch
- [2026-09-30] **D-1 色度多尺度**（`kFsChromaDown/Atrous/Apply`，0.10.53）：
  半分辨率 à trous 5 级、B3 样条、Wiener 收缩 k=8、revert=0.08；缓冲 = 2× 近似
  层 + 5× 细节层 + 1× σ 图（RGBA16F，1/2 分辨率 ≈25MB/张）；MRT 分级绑定、
  手动双线性上采样；失败回落 P4（tex_dn_）
- [2026-09-30] **实测（15:16 组，ISO 1269，强度 1.2）**：σC 1/4/8/16/32px =
  0.81/0.48/0.31/0.18/0.22 vs PC 1.33/0.78/0.65/0.44/0.39；σY 2.87/2.03/1.44
  vs PC 0.72/0.94/0.63；边缘宽 4.84 vs 4.62；dnMs 253ms
- [2026-09-30] **各向异性长核离线（D-2 前瞻）**：L=16/kr=2.5 → σY 暗 1.80 /
  中 1.32 / 亮 0.59（PC 0.72/0.94/0.63），边缘 4.78 vs 4.62
- [2026-09-30] **工具**：`tools/chroma-scale-eval.py`（σC 1–32px + 色斑可视化
  ×12）、`tools/check-fft-tile-cover.py`（tile 覆盖/窗和守卫，`--buggy` 复现
  旧故障）、`test_temp/analysis/atrous_tune.py`（à trous 离线镜像）
- [2026-09-30] **降噪版本现状（0.10.56-m4 / 208）**：A 专项已收（DFT 相位组 4×
  RGBA16F image2D，**必须 glTexStorage2D**；tile 索引 `4·wg.x+gx−1`；uCfaLut 必须
  下发）；D-1 色度多尺度（半分辨率 à trous 5 级、k=8、revert=0.08）**已超 PC**
  （σC 1–32px 优 1.6–2.4×）；亮度各向异性核**已停用**（`uL=0`，两次真机判抹纹理）
- [2026-09-30] **纹理保真口径**：`tools/texture-fidelity-eval.py` —— 纹理块取自
  低噪参照（PC）的低频梯度，测 **2/4/8/16px 多尺度带通比** + 局部对比度；同刻
  实测 0.10.55 的 8px 带通 3.17 vs PC 3.59（−12%）而局部对比度仅 −1.4%
  （细纹在 2–4px，粗尺度指标会漏）
- [2026-09-30] **单帧后段空间降噪上限**：à trous 逐点收缩 40–50%；等方双边
  r=4/8/12 无差异；各向异性长核在保纹理约束下 ≈25–30% 且已证抹纹理
- [2026-09-30] **版本现状（0.10.69-m4 / 221）**：D-2 时域×空间联合 P0
  （`kFsRawDenoiseFusedTs`，4 帧 × 24 同相位邻域 = **96 样本**，实测降噪 **2.3×**）；
  D-1 色度多尺度 k=2.5/revert=0.20（σC 1–32px 优于 PC 1.6–2.4×）；亮度核已停用
  （`uL=0`）；TPDF dither（±1LSB）+ 无 sin hash；LSC 软 knee（w=0.3）；默认 JPEG 100
- [2026-09-30] **raw.render 诊断字段**：`p0s`（P0 样本数 96/9）、`p0w`（中心权重和，
  理论期望 26.1）、`cms`/`cmsK`、`nzPre`/`nzPost`（**去信号**噪声 σ = 同相位 lag-2
  差分 std/√2）、`rmsPre`/`rmsPost`（tile RMS，**信号主导，不可作降噪判据**）；
  `raw.lsc.damp` 带 `softW=0.3`
- [2026-09-30] **LSC 实测参数（X300 主摄）**：网格 17×13，center=1.000，
  **corner=3.874**（BR 3.701）；阻尼后角落 3.179。硬 knee 的斜率突变在**列 1
  = 原图 x≈181 = W/17**（换算交付 JPEG x=105）
- [2026-09-30] **P0 权重实测**：`p0w` 随场景在 15.3–35.3 波动（越亮采纳越多）；空间
  σ² 6.48 时降噪 2.38×/2.29×，放宽到 25 反而降到 1.68×/1.33×（偏差项吃掉收益）
- [2026-09-30] **量化/dither 实测**：成片最平坦块 44.7% 相邻像素相同、|Δ| 中位恰
  1.000；TPDF ±1LSB → 35.6%；JPEG q95 会把 dither 效果吃掉一半（26.9%→31.2%），
  加大 dither 幅度在 q95 下饱和无效；**4:2:0 只损失 6–8% 色度细节**
