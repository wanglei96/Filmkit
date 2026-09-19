---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-19 22:30
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-19] 手动 WB 引擎侧补偿（v0.5.9 待真机验证） | 进行中 | handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md
- [2026-09-19] M2 RAW 端到端（控制层已交付，剩 RAW+DNG 与 submitRaw） | 进行中 | project.md
- [2026-09-19] MF 滑杆与超广黑帧规避 | 待复验/已规避 | project.md

## 下一步

- 真机验证 v0.5.9：WB 拖动平滑性、拉到自动数字=自动观感、倍率 2.9x/0.6x、自定义焦段、EV 实际范围（session_ready 日志 evRange）
- 色温估计校准精校（灰卡/标准光源，WB_CAL_R=0.923/WB_CAL_B=1.238）；WB 补偿不进 DNG 的口径在 M2-3 明确
- 回归 M2-3：RAW 采集（RAW_SENSOR 4080×3072）+ DngCreator 存 DNG，读设置页 dng_enabled 开关

## 最近快照

- [2026-09-19 22:30] handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md — active
- [2026-09-18 11:09] handoffs/修复对焦黑屏与出包约定.memo.md — absorbed
- [2026-09-18 10:20] handoffs/M1完成M2镜头与专业UI攻坚.memo.md — absorbed
