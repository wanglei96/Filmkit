# P-1 报告：预设持久化格式改为「语义名」（schema 2）+ 排除列表修正

- **卡号**：P-1（预设数据层；不动 UI / 引擎 / 契约）
- **依据**：`docs/reports/preset-DESIGN.md` §7 裁决 **A1**（语义名线格式 + 索引键内存）/ **A3**（排除 `[30][35][36]`）
- **代码状态**：留在工作区，**未 commit**（等架构师复核）
- **测试状态**：★ **`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew testDebugUnitTest --rerun-tasks` 退出码 0**
  （27 任务全部真跑、XML 本次新生成：**35 个测试类 / 378 用例 / 0 失败**）；其中预设相关 **67 用例**全绿（§5）
- **验收判据 1**：✅ 达成（运行期曾一度被另一个子代理的 `FrameRenderer.cpp` 颗粒 WIP 阻塞，已由其修复，过程见 §8）

---

## 1. 改了什么（文件:行号）

全部集中在 `app/src/main/java/com/filmkit/app/camera/EditorExport.kt` 的预设段（:1508 起），
`git diff --stat`：`EditorExport.kt +350/-…`、`EditorPresetTest.kt +241/-…`、`EditorPresetAmountTest.kt +10/-…`、
新增 `FilmParamKeysTest.kt`（120 行）。

| # | 位置 | 改动 |
|---|---|---|
| 1 | `EditorExport.kt:1539` | `PRESET_SCHEMA` **1 → 2**；KDoc 写清沿革与"schema 2 起槽位语义变更不再 +1" |
| 2 | `EditorExport.kt:1559-1562` | 新增 `PRESET_SOURCE_BUILTIN = "builtin"` / `PRESET_SOURCE_IMPORTED = "imported"`（**只定常量**，不实现功能） |
| 3 | `EditorExport.kt:1672-1789` | ★ 新增 **`FilmParamKeys`**（`bySlot` 95 项 + `byName` 派生）——语义名 ↔ 槽位**唯一维护点** |
| 4 | `EditorExport.kt:1608` | 新增 `PRESET_ENGINE_KNOB_SLOTS = setOf(30, 35, 36)`，KDoc **逐条**写判据与 `FilmParams.h` 行号证据 |
| 5 | `EditorExport.kt:1619` | 新增 `PRESET_RETIRED_SLOT = 29`（已退役的 `hl_recon`，**无语义名 ⇒ 不进预设**，见 §7.2） |
| 6 | `EditorExport.kt:1637` | `presetSlotIncluded()` 排除**八类**（原四类 + `[30][35][36]` + `[29]`） |
| 7 | `EditorExport.kt:1815` | `EditorPreset` 新增 `note: String = ""` |
| 8 | `EditorExport.kt:2095-2121` | `presetToJson`：`slots` → **`params`**，键 = 语义名，新增 `note` 字段；逗号改用独立标志（跳过无名槽时不能写出 `{,`） |
| 9 | `EditorExport.kt:2198-2230` | `parsePreset`：读 `params`；**未知语义名 ⇒ 整项拒绝**；已知名但被策略排除 ⇒ 逐槽跳过 |
| 10 | `EditorExport.kt:1848` | `presetSchemaUsable` KDoc：写明 schema=1 一律拒绝、**不写迁移函数**的理由 |

**内存模型零改动**（硬性纪律）：`EditorPreset.slots` 仍是 `Map<Int, Double>`，
`presetSnapshot` / `scalePresetSlots` / `applySnapshotParams` / `editorOpenParams` / `EditorScreen.kt` **一行未动**
（翻译只发生在 `presetToJson` / `parsePreset` 两个边界）。

`FilmParams.h` / `FrameRenderer.cpp` 只读用于核对名字，**未改**。

---

## 2. 线格式（**真实序列化输出**，非手写）

`EditorPresetTest.presetJsonSizeIsReasonable` 打印的原始行（stdout，见 `app/build/test-results/.../TEST-*.xml`）：

```json
{"schema":2,"id":"p_1_1","name":"CineStill 800T","group":"CineStill","favorite":false,"createdAt":1730000000,"source":"user","note":"","params":{"exposure_ev":0.35,"wb_r":1.04,"contrast":1.18,"sat_shadow":0.92,"sat_mid":1.06,"sat_high":1.1,"split_warm":0.08,"grain_amount":55.0,"grain_size":800.0,"grain_midtone_bias":60.0,"halation_strength":0.25,"halation_threshold":0.75,"halation_radius":0.5,"halation_warmth":0.3,"vignette_strength":-0.18,"vignette_radius":70.0,"vignette_feather":60.0,"clarity":0.15,"highlights":-0.12,"fade":0.06},"lut":{"id":"cinestill_800t","strength":0.9}}
```

形态要点（架构师裁决，逐条照做）：

| 项 | 实现 |
|---|---|
| `slots` → **`params`**，键 = 语义名 | ✅ 名字逐字取自 `FilmParams.h` 字段名；数组字段展开为 `shoulder[0]` / `cross[4]` / `interlayer_high[2]` |
| `lut` 保持**对象** `{id, strength}` | ✅ 未拆成 `lut` + `lut_strength` 平铺字段 |
| **不加** `base` | ✅ 恒等值恒取 `CameraPresets.standard()` |
| **不加** `category` | ✅ 内置包用 `group` 承载品牌名 |
| **不加** `ui` 段 | ✅ |
| **不加** `updatedAt` | ✅ |
| `note` 新增（缺省 = 空） | ✅ 读取侧缺失 ⇒ `""`；写出侧**恒写**（空串也写，字段集合稳定便于 diff） |
| `source` 新增 `builtin` / `imported` 常量 | ✅ 本卡不实现内置包 / 导入功能 |

槽位按**槽位号升序**写出（与 `FilmParams.h` 物理顺序一致），`lut` 为 null 时**省略该键**（沿用 E3-1a 语义）。

---

## 3. 体积实测（架构师要求：变大的代价要实测）

同一条 20 槽典型预设（`PRESET_JSON_LEN` 取样行，实测输出）：

```
PRESET_JSON_LEN=583 V2_PARAMS_LEN=393 V1_PARAMS_LEN=208 SLOTS=20
```

| 口径 | 实测 | 说明 |
|---|---|---|
| **整条预设 JSON（v2 语义名）** | **583 B** | 20 槽 + LUT + 全部元数据 |
| `params` 段（v2 语义名） | 393 B | 19.7 B/槽 |
| `params` 段（v1 索引键，同槽位同值） | 208 B | 10.4 B/槽 |
| **改语义名的净代价** | **+185 B（+89%）** | 单槽 ≈ +9.3 B |
| 外推 100 条 | ≈ **58 KB** | `PRESET_MAX = 100`，SharedPreferences 单键无压力 |

与预估对照：架构师估"500–900 B/条"——实测 **583 B 落在区间内**（偏低端，因为示例的名称 / 分组较短）。
原 E3-1a 注释里的"≈300–700 B/个"已按实测更新为"**≈750 B/个**"（`EditorExport.kt:1550`；750 B 是"元数据更长 +
25–30 槽"的典型值，本次取样 583 B 是 20 槽的下沿）。

测试里钉住的区间：`json.length in 500..1200`（只挡数量级错误），另断言 `V2_PARAMS_LEN > V1_PARAMS_LEN`
（语义名确实更长）与"往返后 JSON 逐字相同"。

---

## 4. 容错分档与**实际拒绝日志**

`presetsFromJson` 现在三档（KDoc 在 `EditorExport.kt:2141-2156`）：

| 档 | 情形 | 粒度 | 理由 |
|---|---|---|---|
| 1 | 结构字段坏（schema 未知 / 缺 id / 缺 name / `params` 非对象） | **跳整项** | 要么没法用，要么没法定位（删除按 id） |
| 2 | ★ **未知语义名** | **跳整项** | 不知道这个键代表什么参数，跳过它可能给出**另一种风格**且用户无从察觉；"少一条 + 一条可读诊断"是可解释、可恢复的失败（与"未知 schema 一律拒绝"同一逻辑） |
| 3 | 已知语义名但槽位被**策略**排除 / 值非法 / 等于恒等值 / `lut` 坏 | **逐槽降级** | 这是我们自己的策略或局部损坏，不该让用户丢掉整条预设 |

实际诊断串（测试 stdout 原始输出，会经 `PresetStore.load` 落 `preset.load_skipped` 日志，
字段 `count` + `why`，见 `EditorExport.kt:2277-2284`）：

```
SKIP_LEGACY=[item#0：schema=1.0 不是本版本支持的 2]
SKIP_UNKNOWN=[item#0：未知参数名 "nonexistent_key"（本版本没有这个语义）⇒ 整项拒绝]
```

**schema=1（旧索引键格式）一律拒绝，不写迁移函数**：v1 的键是槽位号，而 `[5..10]`（shoulder→latitude）
与 `[26][27]`（颗粒）语义都漂移过 ⇒ `"5":0.0` 到底是旧 shoulder 还是新 latitude **无法分辨**；
且真实设备上不存在 v1 数据（预设首发 v0.11.11 / versionCode 360，用户明确未安装测试）。

---

## 5. 单测（用例数 + 运行结果）

★ **验收判据 1 的命令**（`JAVA_HOME=/opt/homebrew/opt/openjdk@23`，跑前 `rm -rf app/build/test-results`）：

```bash
./gradlew testDebugUnitTest --rerun-tasks
# → BUILD SUCCESSFUL, EXIT=0
#   27 actionable tasks: 27 executed（真跑，不是 UP-TO-DATE / FROM-CACHE）
#   35 个测试类 / 378 用例 / 0 失败 / 0 跳过
#   XML 时间戳 = 本次运行（earliest 02:56:10Z = 本地 10:56:10）
```

预设相关子集（同一个 task，加 `--tests` 过滤）：

```bash
./gradlew testDebugUnitTest -x buildFilmEngine \
  --tests "com.filmkit.app.camera.EditorPresetTest" \
  --tests "com.filmkit.app.camera.FilmParamKeysTest" \
  --tests "com.filmkit.app.camera.EditorPresetAmountTest"
# → BUILD SUCCESSFUL, EXIT=0
```

| 测试类 | 用例数 | 结果 |
|---|---|---|
| `EditorPresetTest` | **35** | 全绿 |
| `EditorPresetAmountTest`（仅 1 条断言随格式更新） | **27** | 全绿 |
| `FilmParamKeysTest`（**新增**） | **5** | 全绿 |
| **合计** | **67** | **0 fail / 0 skip** |

任务书要求的 6 类覆盖 → 用例对应：

| 要求 | 用例 |
|---|---|
| ① 往返（含 LUT 与元数据） | `jsonRoundTripPreservesEveryField`（含 note / source=builtin / 转义字符 / Double 精度）、`roundTripThenApplyEqualsOriginalParams` |
| ② schema=1 旧数据被拒绝 | `legacySchemaOneIsRejected`（喂**真实 v1 形态** `"slots":{"26":55.0}` ⇒ 0 预设 + 诊断）、`unknownSchemaIsRejected` |
| ③ 未知语义名 ⇒ 整项拒绝 | `unknownParamNameRejectsWholeItem`（4 种：`nonexistent_key` / 拼错 `grain_amout` / v1 索引键 `"26"` / 缺下标的 `shoulder`，并带**反证**：同槽位用 `grain_amount` 能读出） |
| ④ 排除槽位 `[30][35][36][34][39..59][79..87]` | `engineKnobsDecodeSlotsLutStrengthAndLocalAreNeverStored`（稀疏化侧，**全部用非恒等值**）、`badValuesAreDroppedOneByOne`（读取侧，`zebra_thr:0.42` 等） |
| ⑤ 语义名与 `FilmParams.h` 一致 | `FilmParamKeysTest.slotsMatchFilmParamsHeader`（正则抽 `float <名> = …; // <槽>` + 数组 `float x[N] = {…}; // <起>..<末>` 展开后**整表对拍**）、`mappingIsBijective`、`everySlotHasANameExceptTheRetiredOne`、`everyStorableSlotHasASemanticName`、`arrayFieldsExpandByIndex` |
| ⑥ 体积 | `presetJsonSizeIsReasonable`（区间断言 + stdout 实测数字，见 §3） |

另新增：`unmappedSlotIsNotWrittenToJson`（`[29]` 不发明语义名；同时钉住"第一项被跳过时不能写出 `{,`"）、
`jsonShapeIsStable`（schema=2 / `params` 语义名 / `note` 恒写）。

**既有断言逐条核对后更新，未放宽任何判据**：所有 `schema:1` 字面量 → `2`、`slots` → `params` + 语义名；
`presetSchemaUsable(1)` 由 `true` 改 `false`（策略变更本身）；`EditorPresetAmountTest` 的
`json.contains("amount")` → `json.contains("\"amount\":")`（**收紧**：`grain_amount` 这个合法参数名含
"amount" 子串，裸断言已失去意义）。

---

## 6. Mutation 自检（证明护栏不是恒真断言）

| # | 变异 | 期望 | **实测** | 恢复 |
|---|---|---|---|---|
| 1 | `PRESET_ENGINE_KNOB_SLOTS = setOf(30, 35, 36)` → `setOf(35, 36)`（把 `[30]` 移出排除列表） | 对应用例红 | `67 tests completed, 2 failed`：`engineKnobsDecodeSlotsLutStrengthAndLocalAreNeverStored` + `badValuesAreDroppedOneByOne`，`EXIT=1` | ✅ 恢复后 `EXIT=0` |
| 2 | `4 to "contrast"` → `4 to "contrast_x"`（映射表名字错一个字符） | `FilmParamKeysTest` 红 | `5 tests completed, 1 failed`：`slotsMatchFilmParamsHeader`，`EXIT=1` | ✅ 恢复后 `EXIT=0`（`diff` 与备份逐字相同） |

**这次自检真的抓到了东西**：第一次变异只红了 1 个用例 —— `badValuesAreDroppedOneByOne` 里写的是
`"zebra_thr":0.95`，而 `[30]` 的恒等值**恰好就是 0.95** ⇒ 即使排除列表失效，恒等值洗白也会让它通过
（**恒真断言**）。已把该用例与稀疏化用例里的 `[30]/[34]/[36]/[39]/[86]` 全部改成**非恒等值**
（`zebra_thr:0.42` 等），第二次变异才如预期红 2 个用例。

---

## 7. 未做 / 存疑项（请架构师复核）

### 7.1 与任务书的两处偏差（都是"事实前提"问题，不是设计改动）

1. **`[95] reserved_95` 进了映射表**。任务书说"`[95]`（预留）等**无字段名**的槽位不映射"，但
   `FilmParams.h:440` 里它**确实有字段名**：`float reserved_95 = 0.0f;  // 95 预留（恒等；kFsStyle 零引用）`。
   按任务书第一条规则"覆盖所有在 `FilmParams.h` 里有名字的槽位"，它进表；好处是"表 == 有名字的槽位"
   **无例外**、可机械对拍（`slotsMatchFilmParamsHeader` 因此不需要例外清单）。它恒等值 0 + 引擎零引用
   ⇒ 稀疏化时被恒等值丢弃，**正常路径永远不会出现在任何预设里**。若不希望它进表，删一行即可（测试同步）。
2. **排除列表从"7 类"变成"8 类"：额外排除 `[29]`**。任务书只要求新增 `[30][35][36]`。`[29]` 是已删除的
   `hl_recon`（`FilmParams.h:68-71`："索引 29 留空不复用"），**无语义名**。而
   `CameraPresets.standard()[29]` 仍是历史遗留的 **1.0**、且 `presetSlotIncluded(29)` 原本为真 ⇒
   "**可入库但写不出去**"（序列化只能跳过 = 静默丢数据）。这是本卡新增的护栏用例
   `FilmParamKeysTest.everyStorableSlotHasASemanticName` **抓出来的**（不是我主动扩范围）。
   处理方式是把三层口径对齐：`FilmParams.h` 无名 → `FilmParamKeys` 不映射 → **不进预设**。

### 7.2 本卡明确不做（按任务书）

- 不实现内置预设包（E3-4）、不实现导入导出（E3-2，`.fkpreset`）；`source` 只落常量。
- **不写任何迁移函数**（schema=1 一律拒绝，理由见 §4）。
- 不改 UI（`EditorScreen.kt` 一行未动）、不改引擎 / 契约 / shader。
- `PRESET_KEY` 仍叫 `presets_v1`（键名不跟着 schema 改：版本判定由每项的 `schema` 承担，改名 = 主动丢数据 + 零收益）。

### 7.3 存疑 / 待架构师确认

1. `[30]` 在**编辑器**链路被 `passStyle` 读取这一条，我按设计文档 §7 裁决 A3 的表述采信，**未独立复核**
   `FrameRenderer.cpp` 的 `passStyle`（本卡纪律：引擎只读且不扩范围）。
2. 数组字段的键形 `shoulder[0]` 是"可机械校验"优先的选择；若将来要让 JSON 更好读（`shoulder_r`），
   需要 +1 版本并写迁移（因为名字含义变了）。**现在改成本最低，越晚越贵。**
3. `note` 字段写出侧恒写空串（+11 B/条）：换来"字段集合稳定、diff 友好"。若要省这点体积，改成
   `note.isNotEmpty()` 时才写即可（读取侧本就兼容缺失）。

---

## 8. 外部阻塞（运行期一度存在，**已由对方修复**；记录备查）

> 本卡实施期间，工作区里另一个子代理（E4-3 颗粒卡）的 WIP 曾让验收命令红过两次。
> 两者**都不是本卡引入的**，且在本报告定稿前**均已由其修复** ⇒ 最终 §5 的完整命令为 EXIT=0。
> 保留这段是因为它说明了"为什么中间某次全量是红的"，也留给复核者一条核对线索。

### 8.1 `:app:buildFilmEngine` 链接失败（当时）—— 颗粒卡 WIP

```
ld.lld: error: undefined symbol: filmkit::(anonymous namespace)::grainSeedFrom(unsigned int, int, int)
>>> referenced by FrameRenderer.cpp:7247 / 9821
```

当时 `FrameRenderer.cpp:31` 在匿名 namespace 内**前向声明** `grainSeedFrom`，而定义落在该 namespace
**之外** ⇒ 链接期找不到符号。该文件不在本卡范围内（硬性纪律：不碰引擎），**未修改**；现已修复。

### 8.2 `EditorStyleWhitelistTest > kdocListMatchesScriptWhitelist` 失败（当时）—— 同一波 WIP

```
（摘录，`…` 处省略）
expected:<[grain_amount, grain_size, lut_strength, grain_midtone_bias, grain_pivot, …]>
 but was:<[exposure_ev, wb_r, wb_g, wb_b, contrast, sat_mid, grain_amount, grain_size, lut_strength, …]>
```

`tools/check-style-passthrough.py` 的 `want` 白名单被**另一个子代理**加了 `grain_midtone_bias` / `grain_pivot`
（`git diff` 显示该文件 +125/-6，**不是本卡改的**），当时 `EditorEngineSession.kt` 的 KDoc 清单尚未同步。
**断言的两侧文件都不在本卡改动清单里**（本卡只改 `EditorExport.kt` + 3 个测试文件 + 新增报告），与预设段零交集；
现已同步，该用例转绿。

### 8.3 证据口径（当时 vs 最终）

| 命令 | 当时的退出码 | **最终退出码** |
|---|---|---|
| `./gradlew testDebugUnitTest`（验收判据 1 原文） | 1（卡在 `:app:buildFilmEngine`，测试根本没跑） | **0**（378 用例 0 失败） |
| `./gradlew testDebugUnitTest -x buildFilmEngine` | 1（378 用例，1 红 = §8.2，非预设） | **0** |
| `./gradlew testDebugUnitTest -x buildFilmEngine --tests <3 个预设类>` | 0（**67 用例全绿**） | **0** |

⇒ 本卡改动**从未引入任何红灯**（两次红都可归因到同一个外部 WIP 的文件）。
