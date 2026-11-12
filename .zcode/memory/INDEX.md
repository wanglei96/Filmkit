---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-11-12 19:50
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-11-12）

- [2026-11-12] **性能优化专项** | 进行中 | 热点已拆清（DFT 1.2~1.5s / P0 300~352ms / P4 0.5~1.1s，
  RCD 仅 65~85ms）；同场景 8.0→6.2~7.3s；下一步 DFT 三选一待拍板 | 快照「性能剖面与RCD归因」
- [2026-11-12] **Spektrafilm 引擎 + 预设标定** | 待做（性能后）| slot=2、场景线性输入、65³ LUT；
  先 Portra 400，再 Ektar / Vision3 500T / Velvia | 快照「天空白块归因与0.13.0」
- [2026-11-11] **理光目标曲线重标** | 待做 | 用新 LUT 样片重跑 `recipe-fit.py`，把摘掉的
  `BuiltinPresetCurveTest` 理光断言挂回去 | 快照「LUT换血与AE高光保护」
- [2026-11-11] **绿植/绿段对齐** | 待量 | 同一张图分别量绿植与天空（掩码两次取错区域） | 同上
- [2026-11-11] **AE 高光保护效果** | 待样片 | `hlOn=true` 后 `hlEv` 是否可靠、`pHi` 能否降下来 | 同上

## 下一步

0. 性能专项：DFT 三条路待用户拍板（A 空间域融合 -1s / B overlap 25% -300~400ms / C 转攻卡顿用
   `EGL_CONTEXT_PRIORITY_LEVEL_IMG`）。
1. Spektrafilm 引擎：先验证输入域/输出契约，再烘 Portra 400。
2. 理光目标曲线重标（recipe-fit）。

## 最近快照

- [2026-11-12 19:49] handoffs/性能剖面与RCD归因.memo.md — active
- [2026-11-12 15:20] handoffs/天空白块归因与0.13.0.memo.md — absorbed
- [2026-11-11 16:15] handoffs/LUT换血与AE高光保护.memo.md — absorbed
- [2026-11-10 14:50] handoffs/显示域闭环与蓝天定档.memo.md — absorbed
- [2026-11-02 17:15] handoffs/胶片预设群与LUT归一方法论.memo.md — absorbed

> **本次交接（s2 × d3，2026-11-12）**：新建快照 1 份（性能剖面与RCD归因）；全量吸收并压到上限
> （project 52 / decisions 120 / lessons 120 / facts 120）；旧 active 标 absorbed；无超 14 天快照
> 可归档（最早 10-30，距今 13 天）；本轮无发版 tag（0.13.1→0.13.9 为测试包）。
