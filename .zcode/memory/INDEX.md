---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-27 16:10
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-27] **多帧融合（MFNR）阶段一** | **P0 立项（用户拍板，优先于 AgX）** | Bayer 域平移对齐（相位相关/局部搜索，OIS 残余小）+ N=4 时域均值，插在 P0 降噪位（demosaic 前）；4 帧 burst + 进度提示；验收 = chroma-denoise-eval 三指标 + 真机暗光 A/B。收益 = 彩噪根治（√N SNR）→ 砍 P4 色度平滑恢复色度边缘 | handoffs/域映射曝光链定案与MFNR立项.memo.md
- [2026-09-27] **轻度锐化层** | 待做 | 线性显示域（filmic 后、OETF 前）luma USM——管线顺序已与用户定稿 | 同上快照
- [2026-09-27] **可切换渲染引擎** | 待做（用户要求）| 我们 filmic / 哈苏式 / AgX 三选；AgX 等 MFNR 落地后再评估（无具体痛点不换曲线）| 同上快照
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源 | facts.md

## 下一步

- **MFNR 阶段一开工**：Bayer 域对齐 + N=4 均值闭环，真机暗光 A/B 定收益
- **真机验收 v0.8.2 的 ratio 持久化**：离开相机页再回来，preview.filmic 的 ratio 应保持不回落 2.0
- **v0.8.2 全量已在 GitHub**（main=48ed77d，tag v0.8.2-m4）

## 最近快照

- [2026-09-27 16:10] handoffs/域映射曝光链定案与MFNR立项.memo.md — active
- [2026-09-24 14:15] handoffs/哈苏曲线换底与预览域差异排查.memo.md — absorbed
- [2026-09-24 10:20] handoffs/对标PhotonCamera与方向链路修复.memo.md — absorbed
- [2026-09-23 17:15] handoffs/自适应影调回归中间调与降噪换径.memo.md — absorbed
- [2026-09-22 22:13] handoffs/调研复核与色度判据三次修正.memo.md — absorbed
