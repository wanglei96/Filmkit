---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-20 10:50
status: active
---

# 项目状态

## 目标

胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。

## 结构

- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（渲染线程）+ CameraAudit
- `core/engine/`：C++20 引擎——pipeline/（FrameRenderer 三段链）、gl/（EglCore/Capabilities）、spike/、log/（JSONL+GPU 计时）、jni/Bridge.cpp
- `core/engine/src/yuv/` 已删除（RG8 上传在 Mali 静默失败，预览改 SurfaceTexture 路径）
- `docs/`：开发计划 v2.0（§0 需求基线 D1–D13）、x300-能力审计.md

## 进行中

- [2026-09-20] **M3 RAW 质量与性能（主线）** | 基建已交付（raw.render 分段计时 + tools/validate-shaders.sh 静态校验）；下一步 M3-1 RCD 去马赛克（研读 PhotonCamera 结构+论文自写，GPL 隔离；bilinear A/B）| 无卡点
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈 | handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡+标准光源（WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）；601 解码已定论 | facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机 | project.md
- [2026-09-18] 超广角黑帧 | 已规避，根因未明 | project.md

## 已完成（近期）

- [2026-09-20] **M2 全部完成（含 M2-3 RAW 端到端 v0.5.20–21）**：RAW_SENSOR 采集 + DngCreator 底片 + 引擎 submitRaw（R16UI→黑阶/WB/bilinear/CCM→汇流胶片链）；真机收敛（CCM 转置品红已修、DNG orientation 已修、M 档快捷开关 raw_mode 与 DNG 解耦）；唯一尾巴 DNG Lightroom 打开待用户确认
- [2026-09-20] 底部 UI 四轮重构（v0.5.22–26）：预览框 weight(1f, fill=false) 自适应、瓦片移出预览框单行、快门/图库上移；画幅 prefs 持久化
- [2026-09-20] M3 基建（v0.5.27）：raw.render 分段计时（≤900ms 出口度量）+ shader 构建期静态校验（tools/validate-shaders.sh，8/8 过）
- [2026-09-20] 开源对标调研固化（PhotonCamera GPL 隔离/降噪 SOTA/Filmic RGB/多帧边界结论）→ 开发计划 M3 节
- [2026-09-20] 成像质量专项（v0.5.13–15）+ 变焦交互三轮（v0.5.10–12）+ 水平仪（v0.5.16–19）：详见 handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md（absorbed）

