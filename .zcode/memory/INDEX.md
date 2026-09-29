---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-29 12:10
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-29] **177 复测 M 档 WB 修复** | 进行中 | M 档拉 WB 滑杆成片应随预览变色
  （raw.render `wb` 字段核对；修复 = `1c437dc`，RAW 链 gains 乘入用户补偿）
  | handoffs/M档WB断链修复与色度差复测待办.memo.md
- [2026-09-29] **ColorChecker ΔE2000 专项** | 已立项 | 用户拍板"色准对照物 = 实物
  本身"（成片比实物淡是事实；+0.7EV 后对 PC 已收敛 −1.1%）；降噪压弱彩已被三连拍
  A/B 证伪 | handoffs/配平落地与弱彩对标转实物.memo.md
- [2026-09-28] **亮度配平** | 待做 | LSC(+0.52EV) 与 base_ev(+0.7) 叠加复核——不能用
  全局 EV（已证无效），从曲线工作点/采集曝光入手；高光不足 0.3~0.66EV（PC 采集曝光
  更高）同批看 | handoffs/OETF修正与色度差定位到前置链.memo.md
- [2026-09-28] **降噪 NLM / MFNR 频域融合** | 待立项 | 见 project.md
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源 | facts.md

## 下一步

- **装 filmkit-0.10.26-m4.apk 复测 M 档 WB**（+0.7EV 饱和度收敛已同刻三方验收通过）
- **ColorChecker ΔE2000 专项**开工（弱彩对标转实物）
- **工具已就绪**：`tools/ccm-chain-ab.py`（一张照片 + DNG 对拍两条链）

## 最近快照

- [2026-09-29 12:00] handoffs/M档WB断链修复与色度差复测待办.memo.md — active
- [2026-09-29 10:45] handoffs/配平落地与弱彩对标转实物.memo.md — active
- [2026-09-28 22:25] handoffs/OETF修正与色度差定位到前置链.memo.md — absorbed
- [2026-09-28 20:13] handoffs/EV前置落地与灰根因收窄.memo.md — absorbed
- [2026-09-28 19:30] handoffs/filmic原生件迁移与黑白点自动撤销.memo.md — absorbed
