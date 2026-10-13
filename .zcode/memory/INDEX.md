---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-13 17:55
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-13）

- [2026-10-13] **引擎切换（公共管线 + 引擎插槽）** | **阶段 0 已完成**（`6a11833`）+ **离线链已补齐**（`f098ba8`）；**下一卡 = 阶段 1 拆 `kFsFilm`** | 用户拍板：引擎切换要做、第 2 步直接对齐 PC、交付上 10bit HEIF。阶段 0 = `[98] engine_slot`（kCount 99，六处同步）+ `EngineRegistry` 骨架 + 基线 + 守卫脚本；判据全绿（596/0、shader 零改动）。离线链现状 = P0→RCD→CCM→**FILM**（+PC3D，LSC 恒等），FILM 基线 `0x589f8364`（默认）/ `0xd46d7b5e`（白点 4.0）。**⚠ 剩余缺口**：拍照链无 filmic 参数日志（`engine.preview.filmic` 只在预览分支）；HR/`uLocal*`/`kFsOut` 不在离线链内 ⇒ 成片零回归仍需真机 | handoffs/引擎切换调研与插槽方案定案.memo.md + docs/engine-slots-design.md + docs/reports/stage0-engine-slot-BASELINE.md
- [2026-10-13] **v0.11.26 真机验收（白点定档）** | 待确认 | 内置 `[38]` 已 2.5~2.7 → 4.0（死白起点 3.55 EV）。**待验**：白色物体高光是否不再死白、亮部变暗（−15.9~−18.0 LSB）是否可接受 ⇒ 方案 A（4.0）/ B（回滚）定档 | docs/reports/r2-white-point-highlight-REPORT.md + r3-white-point-refit-REPORT.md
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；修正方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路，方向随之改变）| facts.md「品红/紫边与曝光」
- [2026-10-03] **亮部亮度降噪** | 挂起 | 对标 PC 唯一成像短板（亮部 σY 1.67×）；P0 全图只降 21~32%，亮部为何更弱待查 | facts.md「对标数据与测量口径」
- [2026-10-03] **超广畸变自校正** | 待拍板 | HAL 路线全关 ⇒ 引擎 GPU 自校正；完整 / 部分 / 不校正三选一 | facts.md「相机能力与标定（仍有效）」

## 下一步

1. **阶段 1（下一卡）**：`kFsFilm:4046` 拆成 `kFilmTemplateHead/Mid/Tail`，filmic + gamut 搬进引擎 0 注入段；判据 = **`film_d5.npy` CRC32 逐字节等价**（对 `docs/reports/stage0-engine-slot-BASELINE.md` §3.3：默认 `0x589f8364` / 白点 4.0 `0xd46d7b5e`）+ `validate-shaders.sh` + `prog_film_≠0`；真机同帧 A/B 兜底。
2. **阶段 2**：sigmoid 引擎（GLSL 照 darktable `sigmoid.c` / PC `DarktableSigmoidToneAlgorithm.kt`；CPU 侧三锚点解析求解；注册表两项；零新增资产）。
3. **待用户点头两项**：判据口径写死（「可分辨跨度」）；公共工作空间切 Rec.2020 **单独一期**（与 10bit HEIF 交付链配套，不可与插槽重构同期）。
4. **并行待办**：真机验 v0.11.26 白点（白杯子高光层次 + 亮部是否偏暗）；真机采"成片 CRC32"基线（本机无 adb）；补拍照链 `engine.still.filmic` 日志。

## 最近快照

- [2026-10-13 16:40] handoffs/引擎切换调研与插槽方案定案.memo.md — **内容已落地（阶段 0 提交 `6a11833`）**，进度以本文件 + project.md 为准
- [2026-10-13 11:00] handoffs/相册切图闪修与预设面板重做至v0.11.26.memo.md — absorbed
- [2026-10-08 16:05] handoffs/边缘彩边专项收尾与v0.11.0发布.memo.md — absorbed
- [2026-10-07 14:35] handoffs/品红带机理闭环与替换档转正.memo.md — absorbed
- [2026-10-06 14:20] handoffs/紫边排查翻案与色散定案.memo.md — absorbed

> 已归档 21 份（`archive/`）。**本次校准（阶段 0 收口 + 离线链补齐）**：`kCount` 98→**99**、`[98] engine_slot` 与 `EngineRegistry` 事实、**离线链覆盖改写为 P0→RCD→CCM→FILM（+PC3D）** + 两组 `film_d5` 基线、**黑白点两层口径**（代码默认 −7.65/+4.56 vs 真机定档 4.0）、四条阶段 0 决策与五条教训（守卫之间的缝 / 文件头注释会撒谎 / 连带失败先定性 / 契约同步六处 / 默认值两层口径）。**最近一份快照的内容已全部落地（阶段 0 提交 `6a11833`）**，按 `meow-recall` 边界它仍是 `active`（写入是 `/handoff` 的职责）—— 下次 `/handoff` 时应标 `absorbed` 或归档。
