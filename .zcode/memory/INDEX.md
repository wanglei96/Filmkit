---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-13 22:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-13）

- [2026-10-13] **引擎切换（公共管线 + 引擎插槽）** | **阶段 0/1 已完成 + 真机验收通过**，下一卡 = 阶段 2 | 阶段 0 = `[98]` 契约 + `EngineRegistry` 骨架；离线链补齐 = FILM 段 + PC3D；阶段 1 = `kFsFilm` 单真源切片（拼接逐字节等价 35154 B / `0x4fa1ebd3`）+ `[98]` 契约登记。真机（包 `0.11.27`）：`engine.film_src` 6/6 条 `identical:true`、无 error/fatal、出片正常 | handoffs/引擎插槽阶段0-1落地与真机验收.memo.md + docs/engine-slots-design.md + docs/reports/stage0-engine-slot-BASELINE.md
- [2026-10-13] **白点定档真机验收** | 待确认 | 内置 `[38]` 已 2.5~2.7 → 4.0（死白起点 3.55 EV）。**待验**：白色物体高光是否不再死白、亮部变暗（−15.9~−18.0 LSB）是否可接受 ⇒ 方案 A（4.0）/ B（回滚）定档 | docs/reports/r2-white-point-highlight-REPORT.md + r3-white-point-refit-REPORT.md
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；修正方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路，方向随之改变）| facts.md「品红/紫边与曝光」
- [2026-10-03] **亮部亮度降噪** | 挂起 | 对标 PC 唯一成像短板（亮部 σY 1.67×）；P0 全图只降 21~32%，亮部为何更弱待查 | facts.md「对标数据与测量口径」
- [2026-10-03] **超广畸变自校正** | 待拍板 | HAL 路线全关 ⇒ 引擎 GPU 自校正；完整 / 部分 / 不校正三选一 | facts.md「相机能力与标定（仍有效）」

## 下一步

1. **阶段 2（下一卡）**：加引擎 1 = sigmoid —— `EngineRegistry` 表加第二项；`filmSource(1)` 返回"公共三段 + 引擎 1 注入段"（阶段 1 已预留注入点）；CPU 侧新增 `computeSigmoidParams` 三锚点解析求解；GLSL 照 darktable `sigmoid.c` / PC `DarktableSigmoidToneAlgorithm.kt`（**本地路径 `/tmp/pc-tone/app/src/main/java/com/hinnka/mycamera/raw/` —— 包名是 `com.hinnka.mycamera`，不是 `com.bjzhou.*`**）。判据 = 方案 §7 五条（可分辨跨度 / 中灰漂移 ≤1.3 LSB / 线性域严格单调 / 真机 A/B / **`engine_slot=0` 时 `film_d5` 仍为 `0x589f8364`**）。
2. **待用户点头两项**：判据口径写死（「可分辨跨度」= 从白纸起每 0.1 EV ≥1 LSB 能持续多远）；公共工作空间切 Rec.2020 **单独一期**（与 10bit HEIF 交付链配套，不可与插槽重构同期）。
3. **并行待办**：补拍照链 `engine.still.filmic` 日志（`engine.preview.filmic` 只在 `!still`）；真机采"成片 CRC32"基线（本机无 adb；离线链不含 HR/`uLocal*`/`kFsOut`）。

## 最近快照

- [2026-10-13 22:15] handoffs/引擎插槽阶段0-1落地与真机验收.memo.md — active
- [2026-10-13 16:40] handoffs/引擎切换调研与插槽方案定案.memo.md — absorbed
- [2026-10-13 11:00] handoffs/相册切图闪修与预设面板重做至v0.11.26.memo.md — absorbed
- [2026-10-08 16:05] handoffs/边缘彩边专项收尾与v0.11.0发布.memo.md — absorbed
- [2026-10-07 14:35] handoffs/品红带机理闭环与替换档转正.memo.md — absorbed

> **本次交接（速度轴·平衡 `s2` × 深度轴·深度 `d3`）**：新建快照 1 份；全量吸收并**执行行数上限** —— decisions 130→119、lessons 130→119（合并同类：阶段 0/1 契约口径、离线链两条、MFNR 反直觉合集、方法论合集等）；facts/project 补真机验收与里程碑；旧 active（引擎切换调研）标 `absorbed`；**归档 5 份**（`created` 2026-09-29 的 absorbed，已超 14 天）⇒ `handoffs/` 22 份、`archive/` 26 份。
