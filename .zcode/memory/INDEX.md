---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-28 20:13
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-28] **色度差：filmic 前的色彩矩阵（主）** | 进行中 | 同亮度 C* 低 17~21%；
  排除 min（只在 L*>50 高亮区生效）/降噪/LSC/去马赛克/工作域；我们 std 32.5、hal 29.2
  均低于 PC 37.2；PC 本次亦选 filmic ⇒ 第三条矩阵来源未定位 | handoffs/OETF修正与色度差定位到前置链.memo.md
- [2026-09-28] **亮度配平（LSC 0.52EV + base_ev 0.5）** | 待做 | 全局 EV 已证无效，须从
  曲线工作点/采集曝光入手 | 同上快照
- [2026-09-28] **高光不足 0.3~0.66EV** | 待做 | PC 采集曝光更高（2011/30.3ms vs 我们
  1457/20ms）| 同上快照
- [2026-09-28] **降噪 NLM / MFNR 频域融合** | 待立项 | 见 project.md
- [2026-09-20] 色温估计校准精校 | 待灰卡 + 标准光源 | facts.md

## 下一步

- **色度差**：在 filmic 之前找第三条色彩矩阵来源（非 std ForwardMatrix、非 HAL CCM）；
  PC 选 filmic 仍高 15% 的原因未定位（用户已排除 DCP 路线与 HncsRenderPlan 追查）
- **亮度**：重新配平 LSC(+0.52EV) 与 base_ev(+0.5) 的叠加——不能用全局 EV（已证无效）
- **工具已就绪**：`tools/ccm-chain-ab.py`（一张照片 + DNG 即可对拍两条链）

## 最近快照

- [2026-09-28 22:25] handoffs/OETF修正与色度差定位到前置链.memo.md — active
- [2026-09-28 20:13] handoffs/EV前置落地与灰根因收窄.memo.md — absorbed
- [2026-09-28 19:30] handoffs/filmic原生件迁移与黑白点自动撤销.memo.md — absorbed
- [2026-09-28 10:45] handoffs/轻度锐化与MFNR阶段二十轮迭代.memo.md — absorbed
- [2026-09-27 20:05] handoffs/MFNR阶段一四轮迭代验收达标.memo.md — absorbed
- [2026-09-27 16:10] handoffs/域映射曝光链定案与MFNR立项.memo.md — absorbed
