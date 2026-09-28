---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-28 20:13
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-28] **"灰 vs PC"根因定位** | 进行中 | 全局参数全排除（EV 4 档实测/工作域/结构
  分解）；区域级硬差异 = 木桌局部对比 −26%/HF −25% + 白壶高光 −0.7EV；待查频段分解 /
  PC Local Laplacian SDR 生效条件 / PC LSC 暗角 | handoffs/EV前置落地与灰根因收窄.memo.md
- [2026-09-28] **HR 时延优化** | 待做 | hrMs≈280ms（110 全屏 pass）影响拍照时延 |
  handoffs/EV前置落地与灰根因收窄.memo.md
- [2026-09-28] **降噪 NLM（darktable denoiseprofile）** | 待立项 | PC 692 行 GLES3.1
  compute 移植可对照；需引擎加 compute 基建；8× 差距主体 | project.md
- [2026-09-28] **MFNR 频域融合（NS 式 7）** | 待立项 | 逐频维纳收缩；0.10.17 审核定案
  差距 #1 | project.md
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源 | facts.md

## 下一步

- **修 /tmp 多尺度带能量脚本**（shape 广播 bug）对 195052/PC 跑木桌/白壶/红杯 4 层频段
  分解；并用统一场景 DNG 离线模拟链（无降噪）与成片对比 HF——定位"局部对比差"来源
- **查 PC 源码**：Local Laplacian 在普通 SDR filmic 的生效条件（photonHdrRequested /
  hasProfileGainTableMap 分支）+ "shading"/LSC（我们 LSC 全开无暗角，PC 是否保留暗角）
- **白壶 0.7EV 高光差**：确认 PC 引擎默认 0.7EV（Linear 域）在链中位置与 AE 净曝光；
  根因定位完成后向用户汇报数据、得拍板再动手

## 最近快照

- [2026-09-28 20:13] handoffs/EV前置落地与灰根因收窄.memo.md — active
- [2026-09-28 19:30] handoffs/filmic原生件迁移与黑白点自动撤销.memo.md — absorbed
- [2026-09-28 10:45] handoffs/轻度锐化与MFNR阶段二十轮迭代.memo.md — absorbed
- [2026-09-27 20:05] handoffs/MFNR阶段一四轮迭代验收达标.memo.md — absorbed
- [2026-09-27 16:10] handoffs/域映射曝光链定案与MFNR立项.memo.md — absorbed
