---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-19 17:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-19）

- [2026-10-19] **待重标 5 项（除预设）** | 待重估 | 分离色调/黑白 `[23][24][25]`、分区饱和度 `[20..22]`、光晕 `[62..65]`、场景统计/自动 EV `kFsHist`、局部色调映射 `kFsLocal`。⚠ `[36]` 引擎 1 下旁路；默认 AE 不读 `sw999`（高光保护默认未启用）| docs/reports/phaseB-old-domain-baseline-ARCHIVE.md §7
- [2026-10-19] **HDR 实施（按 PC 路线）** | 待开工 | 自研 4:4:4 JPEG + gainmap + **libultrahdr** `packageJpegR`（vendored `libultrahdr`/`libjpeg-turbo`）；**不做探针**（PC 已验证）。工作量 1–2 周，独立一期 | docs/reports/hdr-probe-REPORT.md + PC `gallery/Jpeg444ExportEncoder.kt` / `cpp/jpeg_r_encoder.cpp`
- [2026-10-19] **HEIC UI 决策** | 待拍板 | 用户明确不喜欢 HEIC（4:2:0 色度差、亮度优势不明显、细节差）；默认已是 JPEG。待定：加提示（推荐）或移除选项 | 快照「色相路线收口与HDR路线定案」
- [2026-10-14] **阶段 3 真机验收** | 待用户执行 | 判据 0–7；**先验判据 0**（引擎 1 必须真的改变画面 / 日志 `tone=1.0`）；判据 ① 需固定机位 + 强高光场景 | docs/reports/stage3-acceptance-CHECKLIST.md
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路）| facts.md「品红/紫边与曝光」

## 下一步

1. **HDR 实施**：引入 `libultrahdr` + `libjpeg-turbo`，移植 `jpeg_r_encoder.cpp`，接 4:4:4 base + gainmap，出包真机看相册/微信。
2. **待重标 5 项**：先做默认参数 A/B 量化（`[36]` 与 `kFsHist` 优先），再决定是否动预设。
3. **真机验收**：判据 F（HEIC 四端）+ 阶段 3 判据 0；0.11.49 已验三项（② 饱和 / ④ `[40]` 隐藏 / 对比度滑杆）。

## 最近快照

- [2026-10-19 17:20] handoffs/色相路线收口与HDR路线定案.memo.md — active
- [2026-10-19 15:55] handoffs/期B域切换落地与域错配修复.memo.md — active
- [2026-10-15 22:05] handoffs/10bitHEIF交付链定稿与期A收官.memo.md — absorbed
- [2026-10-14 15:10] handoffs/引擎插槽阶段2-3落地与全局开关.memo.md — absorbed
- [2026-10-13 22:15] handoffs/引擎插槽阶段0-1落地与真机验收.memo.md — absorbed

> **本次交接（速度轴·平衡 `s2` × 深度轴·平衡 `d2`，2026-10-19）**：新建快照 1 份（色相路线收口与HDR路线定案）；四个分层增量吸收（project 补「色相路线评估与 HDR 路线」4 条 · decisions 补「色相路线与 HDR」4 条 · lessons 补 5 条 · facts 补「色相路线与 HDR」6 条）；期 B 快照保留 active（待重标未完成）；未归档（无超期 absorbed）。⚠ d2 只做增量，未逐条重排旧分层。
