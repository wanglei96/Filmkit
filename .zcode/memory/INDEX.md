---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-13 18:35
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-13）

- [2026-10-13] **引擎切换（公共管线 + 引擎插槽）** | **阶段 0/1 已完成**（`6a11833` 契约与骨架 / `f098ba8` 离线链 / `ef1f50e` 阶段 1）；**下一卡 = 阶段 2 sigmoid 引擎** | 用户拍板：引擎切换要做、第 2 步直接对齐 PC、交付上 10bit HEIF。阶段 0 = `[98]` + `EngineRegistry` 骨架；阶段 1 = `kFsFilm` 三段拆解 + 引擎 0 注入（单真源切片，拼接逐字节等价 35154 B / `0x4fa1ebd3`）+ `[98]` 契约登记（新增 `CAMERA_ENGINE_SELECT_SLOTS`，参数面板零 UI 变化）+ 离线 `--verify-splice` 判据链。判据：**597 tests / 0 failed**、`validate-shaders` 66/66、离线 `film_d5` = `0x589f8364`（== 基线）。**⚠ 剩余缺口**：拍照链无 filmic 参数日志；HR/`uLocal*`/`kFsOut` 不在离线链内 ⇒ **成片零回归仍需真机同帧 A/B** | handoffs/引擎切换调研与插槽方案定案.memo.md + docs/engine-slots-design.md + docs/reports/stage0-engine-slot-BASELINE.md
- [2026-10-13] **v0.11.26 真机验收（白点定档）** | 待确认 | 内置 `[38]` 已 2.5~2.7 → 4.0（死白起点 3.55 EV）。**待验**：白色物体高光是否不再死白、亮部变暗（−15.9~−18.0 LSB）是否可接受 ⇒ 方案 A（4.0）/ B（回滚）定档 | docs/reports/r2-white-point-highlight-REPORT.md + r3-white-point-refit-REPORT.md
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；修正方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路，方向随之改变）| facts.md「品红/紫边与曝光」
- [2026-10-03] **亮部亮度降噪** | 挂起 | 对标 PC 唯一成像短板（亮部 σY 1.67×）；P0 全图只降 21~32%，亮部为何更弱待查 | facts.md「对标数据与测量口径」
- [2026-10-03] **超广畸变自校正** | 待拍板 | HAL 路线全关 ⇒ 引擎 GPU 自校正；完整 / 部分 / 不校正三选一 | facts.md「相机能力与标定（仍有效）」

## 下一步

1. **阶段 2（下一卡）**：加引擎 1 = sigmoid（GLSL 照 darktable `sigmoid.c` / PC `DarktableSigmoidToneAlgorithm.kt`；CPU 侧三锚点解析求解 `computeSigmoidParams`；注册表两项；零新增资产）；判据 = 方案 §7 五条（可分辨跨度 / 中灰漂移 ≤1.3 LSB / 线性域严格单调 / 真机 A/B / `engine_slot=0` 时 CRC32 不变）。**阶段 1 的判据链已就绪**（`--verify-splice` + `film_d5` 基线 + 真机 `engine.film_src` 自检）。
2. **待用户点头两项**：判据口径写死（「可分辨跨度」）；公共工作空间切 Rec.2020 **单独一期**（与 10bit HEIF 交付链配套，不可与插槽重构同期）。
3. **并行待办**：真机验 v0.11.26 白点（白杯子高光层次 + 亮部是否偏暗）；真机采"成片 CRC32"基线（本机无 adb）；补拍照链 `engine.still.filmic` 日志。

## 最近快照

- [2026-10-13 16:40] handoffs/引擎切换调研与插槽方案定案.memo.md — **内容已落地（阶段 0 提交 `6a11833`）**，进度以本文件 + project.md 为准
- [2026-10-13 11:00] handoffs/相册切图闪修与预设面板重做至v0.11.26.memo.md — absorbed
- [2026-10-08 16:05] handoffs/边缘彩边专项收尾与v0.11.0发布.memo.md — absorbed
- [2026-10-07 14:35] handoffs/品红带机理闭环与替换档转正.memo.md — absorbed
- [2026-10-06 14:20] handoffs/紫边排查翻案与色散定案.memo.md — absorbed

> 已归档 21 份（`archive/`）。**本次校准（阶段 1 收口）**：新增「kFsFilm 三段拆解 + 切片锚点 + 判据值」「两条运行期日志」「`[98]` 三集合分类」事实；3 条决策（单真源切片 / 第三种东西 / 零 UI 变化）；3 条教训（里程碑断言要升级为对拍 / 架构师推断可被实测推翻 / 验证工具假绿）。**上次校准（阶段 0 + 离线链）**：`kCount` 98→99、离线链覆盖改写为 P0→RCD→CCM→FILM（+PC3D）、黑白点两层口径。
