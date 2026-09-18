---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-18 10:20
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

- [2026-09-18] M2 RAW 端到端 | 进行中：镜头发现/设置页/采集控制层已交付；剩 RAW+DNG 采集、引擎 submitRaw、审计报告归档 docs/ | 无卡点
- [2026-09-18] 对焦与镜头切换修复 | v0.4.5 已交付：触屏对焦用户实测可用；圆圈居中+AF 旋转映射待复验 | 详见 handoffs/修复对焦黑屏与出包约定.memo.md
- [2026-09-18] 相机 UI 专项重设计 | 待启动：用户不满意当前 UI；建议新窗口先设计定稿再重构 CameraScreen | 面板重复/布局参照未执行是主要欠账
- [2026-09-18] 手动对焦（屈光度 10D 试值） | 触屏对焦已验证可用；屈光度滑杆仍待实测
- [2026-09-18] 超广角镜头（id 4）黑帧 | 已提供设置页镜头开关规避 | 根因未明（隐藏镜头流配置兼容性）；注意与 v0.4.4 修复的「切镜头 session 未建立黑屏」是两回事
