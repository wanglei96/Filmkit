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

- [2026-09-20] **M2-3 RAW 端到端（下一步主线）** | 采集控制层已交付，剩 RAW_SENSOR 4080×3072 采集、DngCreator 存 DNG（设置页 dng_enabled 开关已有）、引擎 submitRaw；口径：WB 补偿只影响 JPEG/预览不进 DNG | 无卡点
- [2026-09-20] 变焦轮盘优化 | 已搁置（用户确认 UI 收敛）：半圆轮盘 v0.5.10 落地、跳变已修、切镜已提速；灵敏度参数（wheelSpanPx 等）留待手感反馈再调
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡+标准光源（WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）；601 解码已 A/B 定论正确 | 无卡点
- [2026-09-19] MF 滑杆（v0.5.2 交付） | 屈光度手感待真机复验 | AF 面板 A=连续自动
- [2026-09-18] 超广角镜头（id 4）黑帧 | 已提供设置页镜头开关规避 | 根因未明；与切镜头黑屏是两回事

## 已完成（近期）

- [2026-09-20] **成像质量专项（v0.5.13–15）**：log 域分段延展修暗部截断、EXIF 写回+JPEG 质量设置、601/709 定论、预设收敛单一中性标准——与 Filmix 对标指标全面追平/反超（详见 handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md）
- [2026-09-20] 变焦交互三轮（v0.5.10–12）：半圆轮盘、缩小跳变根因、切镜资源复用提速、画质优先+迟滞
- [2026-09-20] 水平仪 + 构图辅助进菜单（v0.5.16–19）：旋转线式、双向对齐、滞回防闪
- [2026-09-19] 手动 WB 引擎侧补偿 | **v0.5.9 真机验证通过**（用户确认观感正常）
- [2026-09-19] 相机 UI 专项（v0.4.6–0.5.1）+ 对焦修复（v0.4.4–0.4.5）| 全部真机确认收敛

