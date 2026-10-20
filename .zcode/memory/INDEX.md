---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-20 09:45
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-20）

- [2026-10-20] **高光顶白（自动曝光策略）** | 待拍板方向 | 同场景实测 SDR base 平均亮度我们 **85.2/98.4** vs PC **50.7**、`>=240` 占比 **3.38%/2.22%** vs PC **0.37%**；HDR 输出几乎相同 ⇒ 差距在 base 曝光，不在 gainmap。方向：HDR 模式单独降曝光 / 全局改 tone map shoulder / 不动 | 快照「HDR成片交付链落地与App内HDR显示」· facts.md「HDR 交付」
- [2026-10-20] **HDR 剩余真机验证** | 待用户执行 | ① HEIC + HDR（8bit 降位，代码已接）② JPEG 4:2:0 + HDR ③ 导出 SDR 版 ④ gainmap 数学 PC/官方双档对比 | docs/reports/hdr-acceptance-CHECKLIST.md
- [2026-10-19] **待重标 5 项（除预设）** | 待重估 | 分离色调/黑白 `[23][24][25]`、分区饱和度 `[20..22]`、光晕 `[62..65]`、场景统计/自动 EV `kFsHist`、局部色调映射 `kFsLocal`。⚠ `[36]` 引擎 1 下旁路；默认 AE 不读 `sw999` | docs/reports/phaseB-old-domain-baseline-ARCHIVE.md §7
- [2026-10-19] **HEIC UI 决策** | 待拍板 | 用户明确不喜欢 HEIC（4:2:0 色度差、亮度优势不明显、细节差）；默认已是 JPEG。待定：加提示（推荐）或移除选项。⚠ 与「HEIC HDR 接受 8bit 降位」的取舍相关，宜一并定 | 快照「HDR成片交付链落地与App内HDR显示」
- [2026-10-14] **阶段 3 真机验收** | 待用户执行 | 判据 0–7；**先验判据 0**（引擎 1 必须真的改变画面 / 日志 `tone=1.0`）；判据 ① 需固定机位 + 强高光场景 | docs/reports/stage3-acceptance-CHECKLIST.md

## 下一步

1. **高光顶白定方向**：三选一（HDR 模式单独降曝光 / 全局改 shoulder / 不动），按铁律需用户拍板后再动，**不许拍脑袋改曝光**。
2. **HDR 剩余验证**：HEIC + 4:2:0 + 导出 SDR 三条路径的真机验收，并做完 gainmap 数学双档 A/B（设置页「gainmap 数学」可切）。
3. **待重标 5 项**：先做默认参数 A/B 量化（`[36]` 与 `kFsHist` 优先），再决定是否动预设。

## 最近快照

- [2026-10-20 09:29] handoffs/HDR成片交付链落地与App内HDR显示.memo.md — active
- [2026-10-19 15:55] handoffs/期B域切换落地与域错配修复.memo.md — absorbed
- [2026-10-15 22:05] handoffs/10bitHEIF交付链定稿与期A收官.memo.md — absorbed
- [2026-10-14 15:10] handoffs/引擎插槽阶段2-3落地与全局开关.memo.md — absorbed
- [2026-10-13 22:15] handoffs/引擎插槽阶段0-1落地与真机验收.memo.md — absorbed

> **本次交接（速度轴·平衡 `s2` × 深度轴·深度 `d3`，2026-10-20）**：新建快照 1 份（HDR成片交付链落地与App内HDR显示，137 行）；四个分层**全量吸收 + 压行**（project 97 行 · facts 119 行 · decisions 120 行 · lessons 119 行，均 ≤120）；期B 与色相路线两份快照标 `absorbed`；归档 2 份超期 absorbed（紫边归因 10-05、紫边排查翻案 10-06）⇒ handoffs 10 份 / archive 43 份。⚠ 压缩时删除了已固化进代码或细节已在 archive 的旧条目。
