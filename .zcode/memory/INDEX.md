---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-20 10:50
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-20] **M2-3 RAW 端到端（主线）** | 采集控制层已交付，剩 RAW_SENSOR 采集 + DngCreator 存 DNG + 引擎 submitRaw；WB 补偿不进 DNG 口径待明确 | project.md
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈 | handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡；601 解码已定论 | facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机 | project.md
- [2026-09-18] 超广角黑帧 | 已规避，根因未明 | project.md

## 下一步

- 回归主线 M2-3 RAW：RAW_SENSOR 4080×3072 + DngCreator 存 DNG（dng_enabled 开关已有）+ 引擎 submitRaw
- 色温估计校准 M3 灰卡精校（WB_CAL_R=0.923/WB_CAL_B=1.238）
- MF 屈光度滑杆手感真机复验

## 最近快照

- [2026-09-20 10:50] handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md — active
- [2026-09-19 22:30] handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md — absorbed
- [2026-09-18 11:09] handoffs/修复对焦黑屏与出包约定.memo.md — absorbed
- [2026-09-18 10:20] handoffs/M1完成M2镜头与专业UI攻坚.memo.md — absorbed
