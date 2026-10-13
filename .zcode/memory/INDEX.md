---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-13 17:25
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-13）

- [2026-10-13] **引擎切换（公共管线 + 引擎插槽）** | **阶段 0 已完成提交**（`6a11833`），**当前卡 = 离线执行器补 `kFsFilm`+PC3D** | 用户拍板：引擎切换要做、第 2 步直接对齐 PC、交付上 10bit HEIF。阶段 0 = `[98] engine_slot`（kCount 99，六处同步）+ `EngineRegistry` 骨架 + 基线 + 守卫脚本；判据全绿（596/0、shader 零改动）。**⚠ 两个缺口**（架构师实测）：① 离线执行器只跑到 D4，**未接 `kFsFilm`** ⇒ 阶段 1 的 CRC32 判据无法执行（用户拍板 A：先补离线链）；② 拍照链无 filmic 参数日志 | handoffs/引擎切换调研与插槽方案定案.memo.md + docs/engine-slots-design.md + docs/reports/stage0-engine-slot-BASELINE.md
- [2026-10-13] **v0.11.26 真机验收（白点定档）** | 待确认 | 内置 `[38]` 已 2.5~2.7 → 4.0（死白起点 3.55 EV）。**待验**：白色物体高光是否不再死白、亮部变暗（−15.9~−18.0 LSB）是否可接受 ⇒ 方案 A（4.0）/ B（回滚）定档 | docs/reports/r2-white-point-highlight-REPORT.md + r3-white-point-refit-REPORT.md
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；修正方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路，方向随之改变）| facts.md「品红/紫边与曝光」
- [2026-10-03] **亮部亮度降噪** | 挂起 | 对标 PC 唯一成像短板（亮部 σY 1.67×）；P0 全图只降 21~32%，亮部为何更弱待查 | facts.md「对标数据与测量口径」
- [2026-10-03] **超广畸变自校正** | 待拍板 | HAL 路线全关 ⇒ 引擎 GPU 自校正；完整 / 部分 / 不校正三选一 | facts.md「相机能力与标定（仍有效）」

## 下一步

1. **离线执行器补 `kFsFilm` + PC3D**（用户 2026-10-13 拍板 A 方案；**阶段 1 的阻塞前置**）—— 现状只跑到 CCM/D4，文件头注释声称已接 FILM 与实现不符；补齐后才有"拆 `kFsFilm` 后逐字节等价"的可执行判据。
2. **阶段 1**：`kFsFilm:4046` 拆成 `kFilmTemplateHead/Mid/Tail`，filmic + gamut 搬进引擎 0 注入段；判据 = **CRC32 逐字节等价**（对 `docs/reports/stage0-engine-slot-BASELINE.md` 的基线）+ `validate-shaders.sh` + `prog_film_≠0`。
3. **阶段 2**：sigmoid 引擎（GLSL 照 darktable `sigmoid.c` / PC `DarktableSigmoidToneAlgorithm.kt`；CPU 侧三锚点解析求解；注册表两项；零新增资产）。
4. **待用户点头两项**：判据口径写死（「可分辨跨度」）；公共工作空间切 Rec.2020 **单独一期**（与 10bit HEIF 交付链配套，不可与插槽重构同期）。
5. **并行待办**：真机验 v0.11.26 白点（白杯子高光层次 + 亮部是否偏暗）；真机采"成片 CRC32"基线（本机无 adb，离线只到 D4）。

## 最近快照

- [2026-10-13 16:40] handoffs/引擎切换调研与插槽方案定案.memo.md — **内容已落地（阶段 0 提交 `6a11833`）**，进度以本文件 + project.md 为准
- [2026-10-13 11:00] handoffs/相册切图闪修与预设面板重做至v0.11.26.memo.md — absorbed
- [2026-10-08 16:05] handoffs/边缘彩边专项收尾与v0.11.0发布.memo.md — absorbed
- [2026-10-07 14:35] handoffs/品红带机理闭环与替换档转正.memo.md — absorbed
- [2026-10-06 14:20] handoffs/紫边排查翻案与色散定案.memo.md — absorbed

> 已归档 21 份（`archive/`）。**本次校准（阶段 0 收口）**：`kCount` 98→**99**、新增 `[98] engine_slot` 与 `EngineRegistry` 事实、「离线执行器只到 D4」缺口、三条阶段 0 决策与四条教训（守卫之间的缝 / 文件头注释会撒谎 / 连带失败先定性 / 契约同步六处）。分层行数 = facts 114 / decisions 126 / lessons 126 / project 69。**最近一份快照的内容已全部落地（阶段 0 提交 `6a11833`）**，按 `meow-recall` 边界它仍是 `active`（写入是 `/handoff` 的职责）—— 下次 `/handoff` 时应标 `absorbed` 或归档。
