---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-21 17:45
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-21] **M3 RAW 质量与性能（主线）** | M3-1 RCD ✅、M3-2 降噪两档 ✅ 定稿、基准色彩内核 ✅（v0.5.41）、高光重建/斑马纹 ✅ → **下一步 P0 镜头阴影校正（LSC）**（X300 数据已验证：17×13、mode=1、角落增益 6.24）| handoffs/降噪两档定稿色彩内核落地并调研LSC.memo.md
- [2026-09-21] **色彩引擎：P1 色彩外观层（治"寡淡"）** | 量化 = 饱和度 -21% / 通道分离 -29% / 近中性像素 +10pp（中性色本身准）；DCP 只解决色准、其 LUT 不能提饱和 → 走 3D LUT；P2 补 DNG 标准矩阵链（ForwardMatrix1/2 内已有、引擎未用）| docs/开发计划.md「色彩引擎调研结论」
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈 | handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡 + 标准光源（WB_CAL_R=0.923 / WB_CAL_B=1.238 单点推得）| facts.md
- [2026-09-19] MF 滑杆手感复验（待真机）· [2026-09-18] 超广角黑帧（已规避，根因未明）| project.md

## 下一步

- **P0 LSC**：STATISTICS_LENS_SHADING_CORRECTION_MAP → 17×13×4 增益纹理 → RAW 解码时按相位双线性采样（验收=暗角消失、角落色偏改善）
- **P1 色彩外观层**：3D LUT（自建 .cube 或 HueSatMap 语义的 hue/sat twist），GPU GL_TEXTURE_3D + LINEAR 一次采样；目标参照 = 厂商直出
- 真机项：vivo 直出同场景对标、桌面 Lightroom 开 DNG、色温灰卡精校

## 最近快照

- [2026-09-21 17:45] handoffs/降噪两档定稿色彩内核落地并调研LSC.memo.md — active
- [2026-09-21 10:05] handoffs/M3降噪v1彩噪达优待调参.memo.md — absorbed
- [2026-09-21 00:10] handoffs/RCD色偏修复收官M3降噪待优化.memo.md — absorbed
- [2026-09-20 17:05] handoffs/M2完成M3开工RAW端到端交付.memo.md — absorbed
- [2026-09-20 10:50] handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md — absorbed
