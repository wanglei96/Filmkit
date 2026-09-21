---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-21 00:10
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-21] **M3 RAW 质量与性能（主线）** | M3-1 RCD 已验收（477ms、颜色一致，v0.5.28–31）；下一步 M3-2 降噪优化（用户点名）+ 基准色彩内核/高光重建（治发灰）| handoffs/RCD色偏修复收官M3降噪待优化.memo.md
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈 | handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡；601 解码已定论 | facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机 | project.md
- [2026-09-18] 超广角黑帧 | 已规避，根因未明 | project.md

## 下一步

- M3-2 降噪：先对真机样张（ISO~1200 暗光）量化噪声现状，再选型 RAW 域方法（noiseProfile 自适应，≤900ms 预算）
- 基准色彩内核 + 高光重建：tone curve/黑位/norm-controlled 高光（发灰=直方图无黑无白）
- 真机项：色温灰卡精校（WB_CAL_R/B）、MF 屈光度手感复验、桌面 Lightroom 开 DNG 随手确认

## 最近快照

- [2026-09-21 00:10] handoffs/RCD色偏修复收官M3降噪待优化.memo.md — active
- [2026-09-20 17:05] handoffs/M2完成M3开工RAW端到端交付.memo.md — absorbed
- [2026-09-20 10:50] handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md — absorbed
- [2026-09-19 22:30] handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md — absorbed
- [2026-09-18 11:09] handoffs/修复对焦黑屏与出包约定.memo.md — absorbed
