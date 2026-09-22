---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-21 19:46
status: active
---

# 项目状态

## 目标

胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学（追平 Adobe/libraw 标准链，§3.2-7 / §3.4-4），风格（浓淡、胶片味）= 用户提供的理光系 LUT（M4）。

## 结构

- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（渲染线程）+ CameraAudit + `camera/StdColorMatrix.kt`（DNG 标准链）
- `core/engine/`：C++20 引擎——pipeline/（FrameRenderer 三段链）、gl/、spike/、log/（JSONL+GPU 计时）、jni/Bridge.cpp
- `core/engine/src/yuv/` 已删除（RG8 上传在 Mali 静默失败，预览改 SurfaceTexture 路径）
- `docs/`：开发计划（§0 需求基线 D1–D13）、x300-能力审计.md
- `app/src/main/assets/luts/`：6 个内置 .cube（理光负片/正片、富士CC/NC、徕卡M9/黑白，33³，**原版名**）＋ `lut-preview-src.png`（卡片预览的固定测试图，CC0，见同名 LICENSE）
- `app/src/main/java/com/filmkit/app/lut/`：`CubeLut`（解析）/ `LutLibrary`（清单+缓存）/ `LutPreview`（卡片预览：一次性生成 + 磁盘缓存）
- `tools/`：validate-shaders.sh、check-lsc-mapping.py、dng-color-dump.py、color-metrics.py、gen-lut-previews.py

## 进行中

- [2026-09-22] **M4 资产系统（主线）** | **M4-1 内置 LUT ✅（v0.6.0→v0.6.8-m4）**：6 个 .cube 入库、纯 Kotlin 解析、display 域四面体插桩、强度滑杆、卡片预览、选择页底部弹出 → **下一步＝色彩专项**（用户对现 LUT 效果不满意），随后 .cube/Hald 导入导出、65³ 融合烘焙、预设包 | handoffs/M4内置LUT引擎落地与tag规则收紧.memo.md
- [2026-09-22] **色彩专项（待启动）** | 用户明确要开新窗口做；起手三件事：LUT 域假设核对、强度混合曲线、基准链与 LUT 职责边界 | handoffs/M4内置LUT引擎落地与tag规则收紧.memo.md
- [2026-09-22] **LUT 噪点待收口** | 用户反馈开 LUT 后彩噪/亮噪明显增加；离线算得 LUT 放大率仅 0.67–1.30×，缺同场景 A/B（强度滑杆 0% vs 100%）→ 用户说"先不动" | facts.md
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源（WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）| facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机

## 已完成（近期）

- [2026-09-22] **M4-1 内置 LUT 引擎（v0.6.0–v0.6.8-m4）**：6 个 .cube 入库为内置 LUT（原版名）、纯 Kotlin 解析器 + 单测、引擎 RGBA16F 3D 纹理 + tetrahedral 插值（接在输出 OETF 之后，kFsHist 同源）、强度滑杆、卡片预览（固定测试图一次性生成 + 缓存）、选择页底部弹出、焦段条单信息与悬浮菜单 3 列；期间修 sampler 未绑定导致的全黑回归与 EXIF 曝光时间静默丢弃
- [2026-09-22] **仓库治理**：tag 规则改「每轮对话一个 tag」并清理 56 个中间 tag；`git filter-repo` 移除误提交的 3 张截图（仓库 25M→13M）
- [2026-09-21] **M3 画质四件（v0.5.43–v0.5.47）**：P0 LSC（均匀白柜面角落/中心 0.61–0.67 → 0.90–0.99，中心增益 1.00）；实时直方图（显示域 OETF 同源，7fps 轮询）；DNG 标准色彩链（自建标定矩阵，真机 A/B 同亮度 C* +14% / sep +16% / 近中性 −10.7pp）；色彩外观层参数回中性
- [2026-09-21] **"寡淡"诊断复核（推翻 v0.5.42 结论）**：libraw 参照默认 auto_bright 抬高亮度 2.5×，原"饱和 −21%/分离 −29%"是亮度差；CIELAB C* 原生输出下我们 9.25 vs libraw 标准链 8.42；色域裁剪（负值 0.7%）与影调（base_tone A/B）均排除 → 差异只在色调映射落点
- [2026-09-21] **M3-2 降噪两档 + 基准色彩内核 + 高光重建 + 斑马纹（v0.5.33–v0.5.41）**：两档定稿（标准 P0 0.28/P4 2.20）；修 SENSOR_NOISE_PROFILE 静默失效；色彩内核四轮；预览过曝斑马纹（不进成片）
- [2026-09-21] **M3-1 RCD 质量档去马赛克（v0.5.28–31）**：三段 fragment 管线 + 质量档开关；真机 477ms 达标；修 G 位填充与 CFA LUT 位序两 bug
- [2026-09-20] **M2 全部完成（含 M2-3 RAW 端到端 v0.5.20–21）**：RAW_SENSOR 采集 + DngCreator 底片 + 引擎 submitRaw；DNG 已验证可打开
- [2026-09-20] 底部 UI 四轮重构（v0.5.22–26）；M3 基建（v0.5.27：raw.render 分段计时 + shader 构建期校验）
