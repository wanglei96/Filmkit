---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-27 20:05
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-27] **轻度锐化层** | 待做 | 线性显示域（filmic 后、OETF 前）luma USM；补 MFNR 后观感细节（PC finishing 差距 10% 的来源之一）| handoffs/MFNR阶段一四轮迭代验收达标.memo.md
- [2026-09-27] **MFNR 阶段二 robust merge** | 待立项 | 运动检测防鬼影；参考 timothybrooks/hdr-plus DFT 维纳收缩 + Night Sight mismatch map；帧数 4→8 评估（σ 再 ÷√2）一并决策 | 同上快照
- [2026-09-27] **可切换渲染引擎** | 待做（用户要求）| 我们 filmic / 哈苏式 / AgX 三选；AgX 等 MFNR 落地后评估（已完成，无具体痛点不换曲线）| 同上快照
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源 | facts.md

## 下一步

- **轻度锐化层**：管线顺序已定稿（filmic 后、OETF 前 luma USM），小改动补观感
- **帧数 4→8 与阶段二设计**：先帧数（收益确定）还是先 robust merge（运动场景），待用户排优先级
- **v0.9.5-m4 已验收打 tag**（main=22e37ac）；MFNR 全链路、日志字段、测量方法见 facts.md

## 最近快照

- [2026-09-27 20:05] handoffs/MFNR阶段一四轮迭代验收达标.memo.md — active
- [2026-09-27 16:10] handoffs/域映射曝光链定案与MFNR立项.memo.md — absorbed
- [2026-09-24 14:15] handoffs/哈苏曲线换底与预览域差异排查.memo.md — absorbed
- [2026-09-24 10:20] handoffs/对标PhotonCamera与方向链路修复.memo.md — absorbed
- [2026-09-23 17:15] handoffs/自适应影调回归中间调与降噪换径.memo.md — absorbed
