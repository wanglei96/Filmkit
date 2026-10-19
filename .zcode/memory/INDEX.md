---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-19 15:58
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-19）

- [2026-10-19] **期 B 收尾：待重标 7 项** | 待重估 | 域已切完、链路自洽，但观感基准变了：U-1 `[23][24][25]`（**生效域已变**）、`[20..22]` 分区饱和、`kFsHaloThr` 阈值、`kFsHist` 统计/自动 EV、`kFsLocal` knee、内置预设。**用户已定"预设先不动"** ⇒ 留到"域完成后重估" | docs/reports/phaseB-old-domain-baseline-ARCHIVE.md §7-8
- [2026-10-19] **`FILM_SRC_BASELINE` 未写入守卫脚本** | 待办 | `engine-shader-offline.py` 仍钉旧值（拼接 `0x4fa1ebd3` / `film_d5 0x589f8364`）⇒ `--splice-selftest` 会误报。新值见最新快照 | 同上
- [2026-10-14] **阶段 3 真机验收** | 待用户执行 | 判据 0–7；**先验判据 0**（引擎 1 必须真的改变画面 / 日志 `tone=1.0`）；判据 ① 需固定机位 + 强高光场景 | docs/reports/stage3-acceptance-CHECKLIST.md
- [2026-10-13] **白点定档真机验收** | 待确认 | 内置 `[38]` 已 2.5~2.7 → 4.0（死白起点 3.55 EV）。**待验**：白色物体高光是否不再死白、亮部变暗（−15.9~−18.0 LSB）是否可接受 | docs/reports/r2/r3-white-point-*REPORT.md
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路）| facts.md「品红/紫边与曝光」

## 下一步

1. **真机验（包 `0.11.49-REC2020-FIX2.apk` / 398）**：判据 F（HEIC 四端）+ 阶段 3 判据 0；已验通过项 = ② 饱和修正 / ④ `[40]` 隐藏 / 对比度滑杆。⚠ 判据 F **没覆盖"App 内回看 HEIC"** ⇒ 该缺口已记（用户报过编辑器显示异常、未复现）。
2. **域完成后的重估**（技术门已开）：filmic 去留 · 内置预设重标策略 · 阶段 4（ACES，§9.4"先看数据别默认上"）· 原厂风格引擎（⚠ PC 无哈苏、且富士已被其自己移除 `56d33aa` ⇒ 法务风险）。
3. **阶段 4 前置已满足**：`workingColorSpace` 非 D4 ⇒ ACES 挂点（把 `XYZ(D50)→Rec.2020` 换成 `→AP0(D60)`）用 `tools/rec2020-domain-matrices.py` 换一组即可。

## 最近快照

- [2026-10-19 15:55] handoffs/期B域切换落地与域错配修复.memo.md — active
- [2026-10-15 22:05] handoffs/10bitHEIF交付链定稿与期A收官.memo.md — absorbed
- [2026-10-14 15:10] handoffs/引擎插槽阶段2-3落地与全局开关.memo.md — absorbed
- [2026-10-13 22:15] handoffs/引擎插槽阶段0-1落地与真机验收.memo.md — absorbed
- [2026-10-13 16:40] handoffs/引擎切换调研与插槽方案定案.memo.md — absorbed

> **本次交接（速度轴·平衡 `s2` × 深度轴·深度 `d3`，2026-10-19）**：新建快照 1 份（期B域切换落地与域错配修复）；四个分层增量吸收（project 补「期 B」5 条 · decisions 补「期 B 域空间」5 条 · lessons 补 5 条 · facts 补「期 B 域空间」5 条）；旧 active（10bit HEIF 交付链定稿）标 `absorbed`；**归档 11 个**（created ≤ 2026-10-04 的 absorbed ⇒ `git mv` 进 `archive/` 且 `status: archived`）⇒ handoffs/ 余 9 份、archive/ 41 份。活跃任务移除已完成的「期 B 域空间」，新增「待重标 7 项」与「`FILM_SRC_BASELINE` 未写」。⚠ d3 的"全量去重/合并历史分层"受本次预算限制**只做了增量吸收**，未逐条重排旧分层。
