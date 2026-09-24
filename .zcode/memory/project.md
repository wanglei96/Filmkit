---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-23 17:15
status: active
---

# 项目状态

## 目标

胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学（追平 Adobe/libraw 标准链，§3.2-7 / §3.4-4），风格（浓淡、胶片味）= 用户提供的理光系 LUT（M4）。

## 结构

- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（渲染线程）+ CameraAudit + `camera/StdColorMatrix.kt`（DNG 标准链）
- `core/engine/`：C++20 引擎——pipeline/（FrameRenderer 三段链）、gl/、spike/、log/（JSONL+GPU 计时）、jni/Bridge.cpp
- `core/engine/src/yuv/` 已删除（RG8 上传在 Mali 静默失败，预览改 SurfaceTexture 路径）
- `docs/`：开发计划（§0 需求基线 D1–D13）、x300-能力审计.md、**research/（11 份独立调研报告：PhotonCamera 管线 / darktable / RawTherapee / Blender AgX / ACES-libplacebo / 相机外观引擎 / filmulator / ISP 标准链与 AE·MFNR）**
- `app/src/main/assets/luts/`：**42 个内置 .lut（uint8 二进制、33³、4.3MB）**，格式 `[size][titleLen][title][catLen][category][data]`，中文显示名 + 品牌分类写在头部；源为 `~/Downloads/LUT/滤镜.cube/` 的 64³ 导出（`tools/gen-lut-assets.py` 重采样）＋ `lut-preview-src.png`（卡片预览固定测试图，CC0）
- `app/src/main/java/com/filmkit/app/lut/`：`CubeLut`（解析）/ `LutLibrary`（清单+缓存）/ `LutPreview`（卡片预览：一次性生成 + 磁盘缓存）
- `tools/`：validate-shaders.sh、check-lsc-mapping.py、dng-color-dump.py、color-metrics.py、gen-lut-previews.py、**gen-lut-assets.py（64³ cube → 33³ 二进制资产）**、**tone-scan.py（成片反推 baseTone 输入 + 参数扫描 + 对照图）**、lut-audit.py、**chroma-denoise-eval.py（v0.6.37 新增：从 DNG 重建 P4 输入域，评估暗部彩噪残余 / 真实色度边缘保留 / 低频偏差）**

## 进行中

- [2026-09-24] **基础色彩引擎（主线）** | 进行中 | v0.6.9→**v0.7.1-m4**。本轮挖到**总根源**：我们的 `baseTone`（黑点截断 + S 曲线在 mn=0.2184 以下压暗）暗部比 PhotonCamera 的**哈苏（HNCS）曲线低 565×**（x=0.01 哈苏 0.0455 / 我们 0.0001），已**换底**为哈苏形状的 Reinhard 曲线 `y=(1+k)x/(1+kx)`（k=5）；`kTarget` 0.38→0.26→0.07→**0.04**、增益下限放开 0.35（静拍也允许压暗）。同期修：预览过曝（**HAL OES 流与 RAW 链线性中位差 1.70–3.87×** → 预览直通 `uPreviewBypass`）、室内"糊了一层"（全局 ag 打满 3.0 推爆亮物）、高光发红（色度保护误护过曝灯管）。**未打 tag** | handoffs/哈苏曲线换底与预览域差异排查.memo.md
- [2026-09-23] **M 档 RAW 所见即所得预览** | 待立项（用户拍板"先不做"）| 卡点是**性能非功能**：RAW 全链路 12.5MP 需 210–500ms，实时需 <33ms。决定性未知量＝**X300 的 RAW 输出尺寸列表**（`CameraAudit.kt:86` 已记录，导审计即可判）。**LUT/预设已在预览生效**（与拍照同一 `passFilm`/`passOutput`），缺的只是 RAW 链 | 同上快照
- [2026-09-23] **多帧融合（MFNR）＝ 彩噪的根治方案** | 待立项 | 彩噪跨帧独立、信号跨帧相关 ⇒ 时间维度完美可分离；HDR+ 一手原话 "Fusing a burst…**makes aggressive spatial denoising unnecessary**"。上了多帧即不需要大尺度色度平滑（后者亮度细节零损失、色度边缘只剩 8–20%）。N=4 约 0.7–1.0s、N=8 约 1.0–1.5s | 同上快照
- [2026-09-23] **待标定/定案三件** | 待基准 | ① 黄绿带色相 −3~8°（须先定基准：灰卡/色卡或厂商直出）② 提亮方式（若室外仍偏灰 → 中间调曲线 `pow(y,g)`，增益会把高光一起推高）③ ag 目标值标定（现 0.26 让绝大多数场景打满，"自适应"只剩很亮场景起作用）| 同上快照
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源（WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）| facts.md

## 已完成（近期）

- [2026-09-24] **色彩引擎换底 + 预览域差异排查（v0.6.45–v0.7.1-m4）**：① 天空去饱和区间 (0.40,0.75)→**(0.32,0.60)**（成片反推实测天空 n0 中位 0.460，旧区间只触发 7.7%）② 自适应影调 kTarget 0.38→0.26、kMax 1.9→3.0（双场景 ag 扫描：室外需 1.2 实际 1.576、阳台需 3.0 实际打满 1.9）③ **预览过曝**：加 `preview.ag`/`linMid` 观测后定位到**预览走 HAL 的 OES 流（已被 ISP tone 过）、静拍走 RAW 链**，同场景同曝光下线性中位差 1.70–3.87×，截图实测预览中位 225/255 vs 成片 161/255 → v0.6.47 放开下限、v0.6.48 **预览直通** ④ 室内两修：去饱和加 `rel` 色度保护（红杯 R 高 G/B 低、**根本没过度曝**却被整段去饱和）+ ag 高光淡出（全局增益打满会推爆局部亮区）⑤ 高光发红：色度保护**只用 rel 一刀切**会保护过曝灯管（R/B 饱和 G 未饱和 ⇒ rel 高）→ 加 luma 门限（灯管 1.04 vs 红杯 <0.4）⑥ **换底**：`baseTone` → Reinhard k=5 ⑦ 压暗恢复对比度（kTarget 0.04）
- [2026-09-24] **对标 PhotonCamera 重标定 + 方向链路 + 文档规整（v0.6.38–v0.6.44-m4）**：基准换成 **PhotonCamera 同机位成片**后用户两条反馈全部坐实（天空 C\* 19.20/sat 0.232 vs PC 8.45/0.114＝**2.3×**；全图 P50 161.9 vs PC 196.9）。**高光去饱和 (0.85,1.30)→(0.40,0.75)**——判据用线性域 max 通道，天空 `n0≈0.59`，旧区间 w 恒为 0、**实拍从未触发**；模拟验证天空 C\* 19.13→**8.02**、sat→**0.100**（PC 8.46/0.114），中间调 w=0 不受影响。`kTarget 0.26→0.38` / `kMax 1.6→1.9`。**降噪 A/B 实测抹掉暗部 C\* 24.8%**（P0 11% + P4 15%，与离线吻合）。**方向链路五版**：`defaultDisplay.rotation` 快照不更新 → `OrientationEventListener`；预览旋转恒 0（修多转 90°）；**旋转语义修正**（`Display.getRotation()` 与设备物理旋转**相反**，竖/倒竖恰好重合掩盖了它）＋ PC 的 gravity 平放过滤 ＋ `OrientationStepTest`；**画幅裁切与 DNG 方向改跟设备姿态**（日志 `photo.crop dstA:0.750` 定案）；转窗口方案**错误已回退**。**文档**：删 `handoff/` 整棵（独有内容先移 `test_temp/archive/`）、重写 README、开发计划补 M4-9（18 个版本）、`docs/research/README.md` 索引、两个"取证清单"改名
- [2026-09-23] **局部色调映射与自适应影调判据收敛（v0.6.24–v0.6.37-m4）**：落地 **M4-7 局部色调映射**（比值判据＋8×8 盒式下采样＋手写双线性上采样，修掉真机"亮斑"）；自适应影调判据 P95→中位→P95→P99→**中位** 五次反复后定稿（**职责＝提中间调以提色彩**，`kTarget 0.26` / `kMax 1.6`）；**P4 色度降噪换径**（σs 会被半径截断 ⇒ 半径 2→3、σc 地板 0.015→0.008、`ns` 回退改按 ISO 估算）；静拍 EMA 直通；三个真机缺陷修复（关降噪反而慢 4–7 倍 / `raw.wait_result` 误报 / 局部低频图固定尺寸致预览与成片提亮尺度差 3.4 倍）；`raw.meta` 加预览/拍照曝光对（**实测一致**，排除曝光差异假设）；预览流上限 1440→2160；`abiFilters` 固定 arm64（APK 39.2→28.2MB）；新增 `tools/chroma-denoise-eval.py`
- [2026-09-22] **调研结论复核 + 色度判据三次修正（v0.6.21–v0.6.23-m4，已验收 tag）**：独立复核推翻 ④（纹素中心校正，恒等 LUT 实测证明现行已等价）、修正 ① 的机制（中间调仅 +1.27/255、"削弱 LUT"反向）、拆分 ③；色度层判据改**亮度相对**并定向调 knee（真机剖面 0.59/0.81/0.86/1.00 → 0.68/0.93/1.07/1.03，clip 全 0%）；建立 **DNG+libraw 缺口剖面**方法定案"淡在中间调弱彩"；版本号改单一来源；`test_temp/` 入 gitignore
- [2026-09-22] **基础色彩引擎调优（v0.6.9–v0.6.20-m4）**：42 个 LUT 资产（中文名+品牌分类+可增删分类，强度上限 150%）；Material3 暗色主题（根因是 `MainActivity` 空的 `MaterialTheme{}`）；`baseTone` 旋钮正交化（删 `g` 幂提亮 → 线性域曝光，`ev`/`p`/`blk` 各管一亮/对比/黑位）；**自适应影调**（场景显示域 P95 → 线性增益，限幅 [1,4]×，EMA；设置页可关）；**高光软肩滚降**（`clamp` → 指数软肩 + 去饱和）；P4 色度降噪 3.25/2.75、`look_chroma` 1.25；`raw.calib` 按 cameraId 各记一条
- [2026-09-22] **独立调研定案**：4 路并行 + ISP 补跑，核实 PhotonCamera 的 LUT 链路（预览/成片都在非线性 sRGB 显示域）、无"理光/徕卡引擎"、`RawProfile` 是未接线代码；拿到 darktable/RawTherapee/AgX/ACES/libplacebo 的做法与 HDR+/Night Sight/libcamera 一手参数
- [2026-09-22] **M4-1 内置 LUT 引擎（v0.6.0–v0.6.8-m4）**：6 个 .cube 入库为内置 LUT（原版名）、纯 Kotlin 解析器 + 单测、引擎 RGBA16F 3D 纹理 + tetrahedral 插值（接在输出 OETF 之后，kFsHist 同源）、强度滑杆、卡片预览（固定测试图一次性生成 + 缓存）、选择页底部弹出、焦段条单信息与悬浮菜单 3 列；期间修 sampler 未绑定导致的全黑回归与 EXIF 曝光时间静默丢弃
- [2026-09-22] **仓库治理**：tag 规则改「每轮对话一个 tag」并清理 56 个中间 tag；`git filter-repo` 移除误提交的 3 张截图（仓库 25M→13M）
- [2026-09-21] **M3 画质四件（v0.5.43–v0.5.47）**：P0 LSC（均匀白柜面角落/中心 0.61–0.67 → 0.90–0.99，中心增益 1.00）；实时直方图（显示域 OETF 同源，7fps 轮询）；DNG 标准色彩链（自建标定矩阵，真机 A/B 同亮度 C* +14% / sep +16% / 近中性 −10.7pp）；色彩外观层参数回中性
- [2026-09-21] **"寡淡"诊断复核（推翻 v0.5.42 结论）**：libraw 参照默认 auto_bright 抬高亮度 2.5×，原"饱和 −21%/分离 −29%"是亮度差；CIELAB C* 原生输出下我们 9.25 vs libraw 标准链 8.42；色域裁剪（负值 0.7%）与影调（base_tone A/B）均排除 → 差异只在色调映射落点
- [2026-09-21] **M3-2 降噪两档 + 基准色彩内核 + 高光重建 + 斑马纹（v0.5.33–v0.5.41）**：两档定稿（标准 P0 0.28/P4 2.20）；修 SENSOR_NOISE_PROFILE 静默失效；色彩内核四轮；预览过曝斑马纹（不进成片）
- [2026-09-21] **M3-1 RCD 质量档去马赛克（v0.5.28–31）**：三段 fragment 管线 + 质量档开关；真机 477ms 达标；修 G 位填充与 CFA LUT 位序两 bug
- [2026-09-20] **M2 全部完成（含 M2-3 RAW 端到端 v0.5.20–21）**：RAW_SENSOR 采集 + DngCreator 底片 + 引擎 submitRaw；DNG 已验证可打开
- [2026-09-20] 底部 UI 四轮重构（v0.5.22–26）；M3 基建（v0.5.27：raw.render 分段计时 + shader 构建期校验）
