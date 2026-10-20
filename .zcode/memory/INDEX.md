---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-20 14:58
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-20）

- [2026-10-20] **bracket 采集 + 饱和感知融合** | 设计已定,待实施 | 1 短 3 长、短帧帧 0 作参考、k=2；两条融合路径都要改。用户已拍板方向，下一窗口从第 1 步开始 | docs/engine-bracket-design.md · 快照「自动曝光落地与bracket设计」
- [2026-10-20] **HDR 剩余真机验证** | 待用户执行 | ① HEIC + HDR（8bit 降位）② JPEG 4:2:0 + HDR ③ 导出 SDR 版 ④ gainmap 数学 PC/官方双档 | docs/reports/hdr-acceptance-CHECKLIST.md
- [2026-10-19] **HEIC UI 决策** | 待拍板 | 用户明确不喜欢 HEIC（4:2:0 色度差、亮度优势不明显、细节差）；默认已是 JPEG。待定：加提示（推荐）或移除选项 | 快照「HDR成片交付链落地与App内HDR显示」
- [2026-10-19] **待重标 5 项（除预设）** | 待重估 | 分离色调/黑白 `[23][24][25]`、分区饱和度 `[20..22]`、光晕 `[62..65]`、场景统计/自动 EV `kFsHist`、局部色调映射 `kFsLocal` | docs/reports/phaseB-old-domain-baseline-ARCHIVE.md §7
- [2026-10-14] **阶段 3 真机验收** | 待用户执行 | 判据 0–7；**先验判据 0**（引擎 1 必须真的改变画面 / 日志 `tone=1.0`）| docs/reports/stage3-acceptance-CHECKLIST.md

## 下一步

1. **bracket 第 1 步**：采集 1 短 3 长 + `shortRatio` 透传 + 日志；融合仍同曝光，先验采集与回退。
2. **HDR 剩余验证**：HEIC + 4:2:0 + 导出 SDR 三条路径，以及 gainmap 数学双档 A/B。
3. **待重标 5 项 / HEIC UI**：先做默认参数 A/B 量化，再决定是否动预设与选项。

## 最近快照

- [2026-10-20 14:58] handoffs/自动曝光落地与bracket设计.memo.md — active
- [2026-10-20 09:29] handoffs/HDR成片交付链落地与App内HDR显示.memo.md — absorbed
- [2026-10-19 17:20] handoffs/色相路线收口与HDR路线定案.memo.md — absorbed
- [2026-10-19 15:55] handoffs/期B域切换落地与域错配修复.memo.md — absorbed
- [2026-10-15 22:05] handoffs/10bitHEIF交付链定稿与期A收官.memo.md — absorbed

> **本次交接（s2 × d3，2026-10-20）**：新建快照 1 份（自动曝光落地与bracket设计）；四个分层全量吸收 + 压行
> （project 98 行 · facts 119 行 · decisions 119 行 · lessons 119 行，均 ≤120）；旧 active 快照
> 「HDR成片交付链…」标 `absorbed`；10 份快照全部 ≤14 天，本次归档 0 份（archive 43 份不变）。
