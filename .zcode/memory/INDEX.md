---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-20 17:10
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-20] **M3 RAW 质量与性能（主线）** | 基建已交付（raw.render 分段计时 + shader 静态校验）；下一步 M3-1 RCD 去马赛克（PhotonCamera 结构+论文自写，GPL 隔离；bilinear A/B）| handoffs/M2完成M3开工RAW端到端交付.memo.md
- [2026-09-20] 变焦轮盘优化 | 已搁置（UI 收敛），灵敏度参数待手感反馈 | handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md
- [2026-09-20] 色温估计校准精校 | 待 M3 灰卡；601 解码已定论 | facts.md
- [2026-09-19] MF 滑杆手感复验 | 待真机 | project.md
- [2026-09-18] 超广角黑帧 | 已规避，根因未明 | project.md

## 下一步

- M3-1 RCD 去马赛克：研读 eszdman/PhotonCamera demosaicp* 管线 + Karacan 论文自写（GPL 隔离不抄码），bilinear A/B 基线
- 真机验：M 档拍摄 raw.render 计时（12MP ≤900ms 基线）；DNG 用 Lightroom 打开确认（M2 唯一尾巴）
- 真机项：色温灰卡精校、MF 屈光度手感复验

## 最近快照

- [2026-09-20 17:05] handoffs/M2完成M3开工RAW端到端交付.memo.md — active
- [2026-09-20 10:50] handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md — absorbed
- [2026-09-19 22:30] handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md — absorbed
- [2026-09-18 11:09] handoffs/修复对焦黑屏与出包约定.memo.md — absorbed
- [2026-09-18 10:20] handoffs/M1完成M2镜头与专业UI攻坚.memo.md — absorbed
