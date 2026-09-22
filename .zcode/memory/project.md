---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-22 22:13
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
- `tools/`：validate-shaders.sh、check-lsc-mapping.py、dng-color-dump.py、color-metrics.py、gen-lut-previews.py、**gen-lut-assets.py（64³ cube → 33³ 二进制资产）**、**tone-scan.py（成片反推 baseTone 输入 + 参数扫描 + 对照图）**、lut-audit.py

## 进行中

- [2026-09-22] **基础色彩引擎（主线）** | 真机实测"发闷/色彩淡"根因在基础引擎而非 LUT。已完成 v0.6.9→**v0.6.23-m4**（42 个 LUT 资产 + 中文分类、Material3 暗色主题、baseTone 旋钮正交化、自适应影调、高光软肩、色度降噪、**色度判据三次修正：域错误 0.25→0.06 → 亮度相对 rel=max\|cc\|/luma → knee 0.4 定向补中间调弱彩**）→ **下一步＝① sRGB 分段 OETF（三处同步）/ ③ 切镜头 AE 重置 / 黄绿色相偏差定案 / 暗场景局部色调映射** | handoffs/调研复核与色度判据三次修正.memo.md
- [2026-09-22] **待定案两件（本轮新增）** | ① **黄绿带色相偏差 −3~8°**（用户"树叶绿不准"的真正形态）：已排除色度层与全局色偏，指向 CCM 路径；动手前须先定基准（灰卡/色卡或厂商直出，libraw 不作绝对标尺）② **暗场景亮度**（"456 比真实场景暗"）：全局曲线已到高光硬顶，只能靠局部色调映射 | 同上快照
- [2026-09-22] **色彩专项（已启动）** | 原三件起手事已答：① 域假设**正确**（LUT 接 display 域、OETF 之后，与 PhotonCamera 同域）② 强度混合曲线**现状已最优**（编码域线性 mix = 感知均匀）③ 职责边界：真正缺的是"基础引擎把 RAW 变成正常照片"这一层 | 同上快照
- [2026-09-22] **噪点待收口（升级为 MFNR 议题）** | 提亮 1.1EV 使色度 σ +84%；单帧降噪已到瓶颈，根治要**多帧融合** | 同上快照
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源（WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）| facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机

## 已完成（近期）

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
