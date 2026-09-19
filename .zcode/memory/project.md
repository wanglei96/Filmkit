---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-19 22:30
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

- [2026-09-19] 手动 WB 引擎侧补偿 | v0.5.9 已交付待真机验证：HAL 恒 AWB AUTO + wbGains(目标/估计) 补偿写 filmParams wb 位；色温估计校准系数待灰卡精校 | 详见 handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md
- [2026-09-19] M2 RAW 端到端 | 进行中：镜头发现/设置页/采集控制层已交付；剩 RAW+DNG 采集、引擎 submitRaw、审计报告归档 docs/；注意 WB 补偿只影响 JPEG/预览、不进 DNG 的口径 | 无卡点
- [2026-09-19] 手动对焦（MF 滑杆 v0.5.2 交付） | 待真机复验屈光度 10D 试值手感 | AF 面板 A=连续自动
- [2026-09-18] 超广角镜头（id 4）黑帧 | 已提供设置页镜头开关规避 | 根因未明（隐藏镜头流配置兼容性）；注意与 v0.4.4 修复的「切镜头 session 未建立黑屏」是两回事

## 已完成（本窗口）

- 相机 UI 专项（v0.4.6–0.5.1）：方向 C 定稿、四轮反馈迭代、主动审计触感动效包；UI 设计规范已写入 docs/开发计划.md
- 对焦与镜头切换修复（v0.4.4–0.4.5）：用户真机确认生效
