---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-09-21 17:45
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

- [2026-09-21] **M3 RAW 质量与性能（主线）** | M3-1 RCD ✅；M3-2 降噪两档 ✅ 定稿（标准档强边缘 0% 损失、体积 -26%）；基准色彩内核 ✅（v0.5.41 定稿）；高光重建/过曝斑马纹 ✅ → **下一步 P0 镜头阴影校正（LSC，X300 数据已验证：角落增益 6.24）**，其后 P1 色彩外观层（治"寡淡"）| handoffs/降噪两档定稿色彩内核落地并调研LSC.memo.md
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈 | handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡+标准光源（WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）；601 解码已定论 | facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机 | project.md
- [2026-09-18] 超广角黑帧 | 已规避，根因未明 | project.md

## 已完成（近期）

- [2026-09-21] **M3-2 降噪两档 + 基准色彩内核 + 高光重建 + 斑马纹（v0.5.33–v0.5.41）**：两档定稿（标准 P0 0.28/P4 2.20，强边缘 0% 损失、体积 -26%）；修 SENSOR_NOISE_PROFILE 静默失效；色彩内核四轮（三点锚定 S + norm-controlled + 高光去饱和 + 中间调提亮，黑位 P0.1 20.4→3.9、亮度对齐关档 -2.5%）；高光重建（CCM 前中性外推）；预览过曝斑马纹（不进成片）
- [2026-09-21] **色彩引擎深度调研（v0.5.42）**：DCP 字段分工与硬限制、开源管线做法、自有 DNG 解剖；量化"寡淡"= 中性色准（R/G 0.987）但饱和度 -21% / 通道分离 -29% / 近中性像素 +10pp；gap 表（LSC 零实现、DNG 矩阵链只用 1/3、无外观层）→ docs/开发计划.md「色彩引擎调研结论」
- [2026-09-21] **M3-2 降噪 v1（v0.5.32）**：P0 RAW 域同色 bilateral + P4 色度平滑；真机 A/B 彩噪 1.41→0.74 达优、亮噪 -56%、细节 -48% 过猛待调参；noiseProfile X300 不回填走回退 0.030
- [2026-09-21] handoff/ 交接文件夹（README + 快照拷贝，本地维护不上 git）
- [2026-09-21] **M3-1 RCD 质量档去马赛克（v0.5.28–31）**：三段 fragment 管线（方向场/G@RB/色度+CCM）+ 质量档开关；真机 477ms 达标；连修 G 位填充 Bayer 结构错（饱和度减半）与 CFA LUT 位序反（发红）两 bug，用户确认颜色已一致；算法出处更正（Luis Sanz Rodriguez 开源算法，非"Karacan 论文"）
- [2026-09-20] **M2 全部完成（含 M2-3 RAW 端到端 v0.5.20–21）**：RAW_SENSOR 采集 + DngCreator 底片 + 引擎 submitRaw（R16UI→黑阶/WB/bilinear/CCM→汇流胶片链）；真机收敛（CCM 转置品红已修、DNG orientation 已修、M 档快捷开关 raw_mode 与 DNG 解耦）；DNG 已验证可打开（M2 尾巴关闭）
- [2026-09-20] 底部 UI 四轮重构（v0.5.22–26）：预览框 weight(1f, fill=false) 自适应、瓦片移出预览框单行、快门/图库上移；画幅 prefs 持久化
- [2026-09-20] M3 基建（v0.5.27）：raw.render 分段计时（≤900ms 出口度量）+ shader 构建期静态校验（tools/validate-shaders.sh，8/8 过）
- [2026-09-20] 开源对标调研固化（PhotonCamera GPL 隔离/降噪 SOTA/Filmic RGB/多帧边界结论）→ 开发计划 M3 节
- [2026-09-20] 成像质量专项（v0.5.13–15）+ 变焦交互三轮（v0.5.10–12）+ 水平仪（v0.5.16–19）：详见 handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md（absorbed）

