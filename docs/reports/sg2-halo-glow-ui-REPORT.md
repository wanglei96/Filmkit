# SG-2 报告：把光晕与柔光接进编辑器参数面板

> 卡号 **SG-2** ｜ 2026-10-11 ｜ 用户原话（2026-10-11）："**光晕、柔光要先做**"
> 上游：引擎侧 **E4-5**（光晕 halation `[62..65]`，`ceee83d`）+ **SG-1**（柔光 soft glow
> `[95..97]`，`306ab00`，契约 96 → 98）
> 本卡解决的是**引擎已实现、用户手动调不到**的缺口：光晕与柔光此前没有面板滑杆，
> 只能靠套用预设间接生效。
> **未 commit**（按任务要求留工作区）。

---

## 1. 一句话结论

编辑器参数面板新增 **7 条滑杆**（光晕 4 + 柔光 3），全部直通引擎槽位；
第三组「暗角」**扩组并改名为「质感」**（暗角 + 光晕 + 柔光 = 10 条）；
`PresetDetail` 详情文案、`preset-DESIGN.md`、既有单测口径**全部同步**；
构建 + 508 个单测真跑全绿；2 项 mutation 自检证明护栏有效（含 pivot 一项）。

---

## 2. 交付清单（改动文件 → 内容）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/camera/EditorExport.kt` | 新增 7 个 pivot 常量 + 1 个共用量程常量 `HALO_GLOW_UI_RANGE` + 2 个映射函数 `normalizedSlot` / `normalizedUi` + 纯函数 `resetFilmTextureParams`；`formatMono` 改名 `formatOff100`（它现在服务 3 条参数）；`PRESET_SLOT_CLAMP` 补 `[95..97]` |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | `VignetteParams` → **`TextureParams`（10 条）**；`EditorTab.VIGNETTE("暗角", Vignette)` → **`TEXTURE("质感", FilterVintage)`**；`resetVignette` → `resetTexture`（调纯函数）；组名注释 5 处同步 |
| `app/src/main/java/com/filmkit/app/ui/PresetDetail.kt` | `DETAIL_META` 补 7 条（[62..65] 改读数口径 + 新增 [95..97]）；文件头"名字从哪来"表更新 |
| `app/src/test/java/com/filmkit/app/camera/EditorHaloGlowUiTest.kt` | **新增**（11 个用例：往返 / pivot 双侧对拍 / 面板表解析 / 进预设 / 文案 / 重置 / 读数） |
| `app/src/test/java/com/filmkit/app/camera/EditorU6bParamUiTest.kt` | `formatMono` → `formatOff100` 机械改名（10 处，**断言未放宽**） |
| `app/src/test/java/com/filmkit/app/ui/PresetDetailTest.kt` | 文案护栏遍历上界 `0..95` → **`0..97`**（收紧：SG-1 后 [96]/[97] 也是合法槽，旧上界会漏检柔光半径/阈值） |
| `docs/reports/preset-DESIGN.md` | §9.2 第 16 项加"面板滑杆已进 UI" + 新增第 17 项柔光（层间效应顺延为 18）+ 表下说明；§1.6 表下加 SG-2 复核段 |

**未碰**（任务硬约束）：`FrameRenderer.cpp`（`git diff --stat` 里 `core/` 零改动）、
`CameraScreen.kt`、`CameraPresetPanel.kt`、`PhotoViewScreen.kt`、`assets/presets/`。

---

## 3. 验收判据 1：7 条滑杆都能改到引擎参数（数值链路证据）

### 3.1 写链路（"能不能调到引擎"由它决定）

```
用户拖动滑杆 v（UI 0..1）
 └─ EditorSlider.onValueChange → EditorScreen.kt 的 commit(param, v)
     └─ EditorParam.write = { p, v -> p[62] = normalizedSlot(v) }      EditorScreen.kt:2511-2561
         └─ normalizedSlot(ui) = ui.coerceIn(0f, 1f).toDouble()        EditorExport.kt:271-272
             └─ FilmParams 数组（DoubleArray(98)，CameraPresets.COUNT）
                 └─ CameraController.setFilmParams（既有 98 槽下发通路，E4-1 契约）
                     └─ FilmParams::fromDoubles:
                          if (n > 62) halation_strength = (float)v[62];   FilmParams.h:582
                          … 63 / 64 / 65 …                               FilmParams.h:583-585
                          if (n > 95) glow_strength   = (float)v[95];    FilmParams.h:626
                          … 96 / 97 …                                    FilmParams.h:627-628
                         └─ shader runHalation / runGlow（passOutput 与 passStyle 两侧）
```

拖动中只写槽位 + `gate.onValueChange()`（不渲染），松手走 `onSliderRelease` 渲染一次
（E2-8 闸门，机制未改）；单条「重置」走 `resetParam` = `write(param, param.pivot)` + 渲染。

### 3.2 读链路（"重开面板时滑杆停在哪 / 读数是什么"）

```
槽位值 → EditorParam.read = { normalizedUi(it[62], HALO_UI_PIVOT) }   （坏数据回落该槽默认值）
       → EditorParam.format = { formatOff100(it) } / { formatDecimals2(it) }
       → 参数名行下方右侧的黄色读数（13sp Mono）
```

### 3.3 7 条一览（面板表 `EditorScreen.kt:2485-2562`，10 条）

| # | 槽位 | 面板 label | UI 量程 | pivot | 读数 | write | read |
|---|---|---|---|---|---|---|---|
| 1 | `[62]` | 光晕 · 强度 | `HALO_GLOW_UI_RANGE` (0…1) | `HALO_UI_PIVOT` = 0 | `关` / 0…100 | `p[62] = normalizedSlot(v)` | `normalizedUi(it[62], HALO_UI_PIVOT)` |
| 2 | `[63]` | 光晕 · 触发阈值 | 同上 | `HALO_THRESHOLD_UI_PIVOT` = 0.80 | 两位小数 | `p[63] = …` | `normalizedUi(it[63], HALO_THRESHOLD_UI_PIVOT)` |
| 3 | `[64]` | 光晕 · 半径 | 同上 | `HALO_RADIUS_UI_PIVOT` = 0.45 | 两位小数 | `p[64] = …` | `normalizedUi(it[64], HALO_RADIUS_UI_PIVOT)` |
| 4 | `[65]` | 光晕 · 暖度 | 同上 | `HALO_WARMTH_UI_PIVOT` = 0.40 | 两位小数 | `p[65] = …` | `normalizedUi(it[65], HALO_WARMTH_UI_PIVOT)` |
| 5 | `[95]` | 柔光 · 强度 | 同上 | `GLOW_UI_PIVOT` = 0 | `关` / 0…100 | `p[95] = …` | `normalizedUi(it[95], GLOW_UI_PIVOT)` |
| 6 | `[96]` | 柔光 · 半径 | 同上 | `GLOW_RADIUS_UI_PIVOT` = 0.35 | 两位小数 | `p[96] = …` | `normalizedUi(it[96], GLOW_RADIUS_UI_PIVOT)` |
| 7 | `[97]` | 柔光 · 触发阈值 | 同上 | `GLOW_THRESHOLD_UI_PIVOT` = 0.70 | 两位小数 | `p[97] = …` | `normalizedUi(it[97], GLOW_THRESHOLD_UI_PIVOT)` |

**为什么 UI 量程 = 槽位量程（直通）**：这七条的引擎契约量程本身就是 0..1 归一
（`FilmParams.h:419-428` / `:491-499`）⇒ 不需要换算，只需夹紧与坏数据兜底。
**刻意不给每条一对 `xxxSlot`/`xxxUi`**：七条量程与语义完全相同，七对同体函数是样板；
一个 `normalizedSlot`/`normalizedUi` + 七个 pivot 常量就够（量程与 pivot 仍与面板表**共用
同一份定义**，不存在"滑杆能拖到 clamp 之外"的假滑杆）。

### 3.4 验证方式（**本卡未真机验证**）

| 层次 | 证据 |
|---|---|
| 面板表**真的接上了** 7 条 | `EditorHaloGlowUiTest.allSevenSlidersAreWiredIntoThePanelTableWithCorrectSlots`：正则解析 `EditorScreen.kt` 的 `EditorParam` 表，逐条断言 ① 槽位在表里 ② `read` 与 `write` 落在**同一个槽位**（复制粘贴经典错）③ `range` 用的是共用常量 ④ `pivot` 写的是常量名 ⑤ 质感组恰好 10 条 `[62..68]+[95..97]` |
| **能进预设**（§1.6 的"进预设？✅"） | `allSevenSlotsCanBeStoredInPresets`：`presetSlotIncluded(62..65)` / `(95..97)` 全为 true ⇒ 面板调的这七个槽，预设存得下 |
| 数值往返不丢 | `everySlotRoundTripsAcrossFullRange`：7 槽 × 101 档 UI→槽位→UI 全等（1e-6） |
| 越界与坏数据 | `writeClampsToUnitRangeAndReadClampsOutOfRangeSlots` / `badSlotDataFallsBackToEachSlotsOwnEngineDefault` |
| 引擎侧本身可用 | E4-5 / SG-1 已各自离线验收（`e4-5-halation-REPORT.md`、`sg1-softglow-REPORT.md`：两侧链路逐位/≤1 LSB 一致、[62]/[95] = 0 时整链旁路） |
| **未验证** | 真机拖动滑杆后的**画面观感**（"像不像光晕/柔光"）—— 见 §8 |

> 说明：面板→槽位这一段是 **E2-10 既有的数据驱动机制**（19 条参数已在真机验证过），
> 本卡**没有改机制**，只往表里加了 7 行数据 ⇒ 机制本身不需要重新验证；
> 本卡新增的是"这 7 行数据对不对"，全部由 JVM 单测覆盖。

---

## 4. 验收判据 2：7 条 pivot 与引擎中性值逐一核对

pivot = 「重置」回到的值 = **不变点 / 默认值**（`EditorParam.pivot`）。写错的表现是
"用户一 reset 画面就变"，所以本卡把它**双侧机械对拍**（不是人工核对）：

- **Kotlin 侧**：`CameraPresets.standard()` —— 引擎下发与预设缩放的 `neutral` 基线
- **C++ 侧**：解析 `core/engine/src/film/FilmParams.h` 的 `float <字段> = <值>f;  // <槽位>`

| # | 槽位 | 引擎字段 | pivot 常量 | 值 | `CameraPresets.standard()` | `FilmParams.h` | 一致 |
|---|---|---|---|---|---|---|---|
| 1 | `[62]` | `halation_strength` | `HALO_UI_PIVOT` | **0**（关） | `0.0`（`CameraPresets.kt:257`） | `0.0f`（`:425`） | ✅ |
| 2 | `[63]` | `halation_threshold` | `HALO_THRESHOLD_UI_PIVOT` | 0.80 | `0.80`（`:258`） | `0.80f`（`:426`） | ✅ |
| 3 | `[64]` | `halation_radius` | `HALO_RADIUS_UI_PIVOT` | 0.45 | `0.45`（`:260`） | `0.45f`（`:427`） | ✅ |
| 4 | `[65]` | `halation_warmth` | `HALO_WARMTH_UI_PIVOT` | 0.40 | `0.40`（`:261`） | `0.40f`（`:428`） | ✅ |
| 5 | `[95]` | `glow_strength` | `GLOW_UI_PIVOT` | **0**（关） | `0.0`（`:304`） | `0.0f`（`:497`） | ✅ |
| 6 | `[96]` | `glow_radius` | `GLOW_RADIUS_UI_PIVOT` | 0.35 | `0.35`（`:305`） | `0.35f`（`:498`） | ✅ |
| 7 | `[97]` | `glow_threshold` | `GLOW_THRESHOLD_UI_PIVOT` | 0.70 | `0.70`（`:307`） | `0.70f`（`:499`） | ✅ |

**依据（两条"关"槽位尤其重要）**：

- `[62]` / `[95]` 的 0 **不是**"很小的效果"，而是**整链旁路**：引擎侧
  `halation_strength = 0` ⇒ 零 pass；`glow_strength = 0` ⇒ 零 pass **零纹理分配**
  （`FilmParams.h:420` / `:497`，SG-1 规格 §3）。所以 pivot 必须是 0，写 0.1 会让
  "重置"后画面多出一层光晕/柔光。
- 其余五条是各自的**设计默认值**（不可达但必须显式给确定起点，`FilmParams.h:406-410`
  的"默认值一律取无效果值"纪律）⇒ pivot 取它们，用户一 reset 就回到"引擎出厂形态"。
- 三条 `strength = 0` 时各自整链旁路 ⇒ **组重置只需归三条强度**，其余七条保持原值
  （画面已恒等，用户调好的阈值/半径不必重调）。纯函数 `resetFilmTextureParams`
  （`EditorExport.kt:299-303`）+ 单测 `groupResetZeroesTheThreeStrengthsAndKeepsTheRest`。

---

## 5. 验收判据 3：`PresetDetail` 文案护栏

- **既有护栏**（`PresetDetailTest.detailLabelsReuseEditorPanelWording`）：解析面板参数表，
  逐槽断言"详情名**包含**面板 label" —— 本卡 7 条全部命中，绿。
- **本卡加严**（`EditorHaloGlowUiTest.textureDetailLabelsMatchPanelLabelsExactly`）：质感组
  10 条断言**逐字相等**。理由：扩组后组内有 **3 条"强度"、3 条"半径"、2 条"阈值"**，
  只靠"包含"挡不住"光晕 · 强度"被写成"柔光 · 强度"这类错位。
- **收紧的旧口径**：`PresetDetailTest` 的两处遍历上界 `0..95` → `0..97`
  （SG-1 把 kCount 扩到 98 后，[96]/[97] 是合法槽，旧上界会让柔光半径/阈值的文案**漏检**）。
- 读数口径（详情 = 面板，同一份函数）：强度 `formatOff100`（0 → `关`）、
  阈值/半径/暖度 `formatDecimals2`（两位小数）。**SG-2 前** [62..65] 在详情里用
  `formatZero100`（整数）⇒ 0.45 与 0.50 会显示成同一个数，本卡一并改成两位小数。

---

## 6. 验收判据 4：分组决策 + 理由

### 决定：第三组「暗角」→「质感」（枚举 `VIGNETTE` → `TEXTURE`，图标 `Vignette` → `FilterVintage`）

| 方案 | 取舍 |
|---|---|
| **A（采纳）扩组改名「质感」** | 组里三类都是胶片质感（光学渐晕 / 乳剂背反射光晕 / 柔光镜 bloom）；名字与内容一致，用户能找到 |
| B 放进「滤镜」组 | 「滤镜」组是**风格化调色**（黑白 / 分离色调 / 褪色）+ LUT 强度（选了 LUT 才进表）；把光学质感塞进去会让"调色"与"质感"混在一起，且该组条目会从 5 条涨到 12 条 |
| C 保持「暗角」组名，只往里加 7 条 | **用户根本不会点进来找光晕** —— 与"引擎做了但用户调不到"是同一个失败模式（ADR-12：界面要自己说清楚，不靠用户猜） |

**肌肉记忆影响（已尽量压到最小）**：

- 组的位置（第三格）**没变**，组内第一条**仍是「暗角 · 强度」** ⇒ 老用户"点第三格 →
  拖第一条"的动作路径逐字不变，只有格子上的两个字与图标变了。
- 图标一并换：图标比文字更早被识别，留着暗角图标会继续暗示"这组只有暗角"。
- 影响面可枚举：全仓 `"暗角"` 作为**组名**只出现在 `EditorScreen.kt` 的枚举定义一处
  （已改），**没有任何单测断言组名**（grep 证据见下）⇒ 改动不会连带别处。

```
$ grep -rn '"暗角"' app/src/test app/src/main --include=*.kt
app/src/main/java/com/filmkit/app/ui/EditorScreen.kt:2157:    VIGNETTE("暗角", Icons.Outlined.Vignette)   ← 唯一一处（组名；行号 = 改动前，
                                                        改动后是 :2187 的 TEXTURE("质感", FilterVintage)）
```

### 组内排序与 label

- **顺序：暗角（3）→ 光晕（4）→ 柔光（3）**。暗角三条保持原位（历史主体 + 肌肉记忆）；
  光晕与柔光相邻放后面 —— 两者共享同一套 5 级模糊金字塔（`soft-glow-SPEC.md` §5），
  是同一族"高光扩散"，相邻才方便 A/B 对比（规格 §1 的判据："红色外晕 + 白色柔光同时可见"）。
- **10 条 label 全部带组内前缀**（"暗角 · 强度" / "光晕 · 强度" / "柔光 · 强度"…）：
  扩组后 3 条"强度"、3 条"半径"、2 条"阈值"并列，不带前缀时参数名行（居中大字）
  会显示成三条一模一样的"强度"。前缀就是子类名，与 `DETAIL_META` **逐字相同**（护栏钉死）。

---

## 7. 构建与单测（真跑，非 `FROM-CACHE`）

```bash
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

- 退出码 **0**，`BUILD SUCCESSFUL`
- **`46 actionable tasks: 46 executed`**（全 executed ⇒ 无 `FROM-CACHE` 假绿）
- **用例总数 508 / 失败 0 / 错误 0 / 跳过 0**（47 个测试类）
  - 基线 **497**（`sg1-softglow-REPORT.md`）+ 本卡新增 **11**（`EditorHaloGlowUiTest`）= 508
  - 关键类：`EditorHaloGlowUiTest` 11、`PresetDetailTest` 11、`EditorU6bParamUiTest` 10

> 中间有一次**失败**（第一次跑）：`PresetDetail.kt:159 Unresolved reference 'formatMono'`
> —— 改名 `formatMono` → `formatOff100` 时漏改黑白 [25] 的调用点。已修，随后**三次**全量全绿（最后一次 = 交付状态，含注释改动与新增用例）。

---

## 8. Mutation 自检（2 项，其中 1 项针对 pivot）

| # | 注入的缺陷 | 期望红 | 实测 |
|---|---|---|---|
| **M1** | `HALO_THRESHOLD_UI_PIVOT` 由 `0.80f` 改成 `0.70f`（**pivot 与引擎默认值不一致**） | pivot 双侧对拍 | ✅ **4 红**：`everyPivotEqualsTheNeutralValueInCameraPresets`、`everyPivotEqualsTheDefaultDeclaredInFilmParamsHeader`、`badSlotDataFallsBackToEachSlotsOwnEngineDefault`、`readoutsFollowTheTwoDeclaredFormats`（`10 tests completed, 4 failed`，EXIT=1） |
| **M2** | 面板 label `"光晕 · 触发阈值"` 改成 `"光晕 · 阈值"`（**面板与详情文案漂移**） | 文案护栏 | ✅ **2 红**：`EditorHaloGlowUiTest.textureDetailLabelsMatchPanelLabelsExactly`（逐字相等）+ `PresetDetailTest.detailLabelsReuseEditorPanelWording`（既有"包含"护栏）（`21 tests completed, 2 failed`，EXIT=1） |

两次注入均已**还原**（`git diff` 复核：`HALO_THRESHOLD_UI_PIVOT = 0.80f`、
`label = "光晕 · 触发阈值"` 都在位），还原后重跑全量全绿（§7）。

---

## 9. 未做 / 未验证 / 与任务表的偏差

### 9.1 明确不做（按任务）

- **不改引擎**：`core/engine/**` 零改动（`git diff --stat` 可证）。
- **不做"拍照界面也加这些滑杆"**：拍照界面只有快捷控件，加完整参数面板是产品决策
  （架构师未拍板）。
- **不改** `[62..65]`/`[95..97]` 的默认值或量程语义：pivot 只**引用**引擎默认值，未修改它。

### 9.2 ⚠ 未真机验证（本卡最重要的限制）

| 需要真机确认的 | 为什么单测覆盖不了 |
|---|---|
| 拖动 7 条滑杆后的**画面观感**（"像不像光晕/柔光"、"两条同时开是否可分辨"） | 观感问题，`soft-glow-SPEC.md` §6 判据 7 明确"不接受数据好看代替目视" |
| 质感组 10 条在底栏/参数行的**横向滚动与可达性** | Compose 布局 + 手势 |
| 新组名「质感」+ 新图标 `FilterVintage` 的**观感与辨识度** | 需要人眼 |
| 光晕/柔光与清晰度 `[78]` 同时开是否"打架" | SG-1 规格 §4 列为待实测项 |

> 引擎侧本身（两条链路的数值一致性、`[62]`/`[95]` = 0 的逐位零回归与零 pass）
> 已由 E4-5 / SG-1 离线验收，本卡未重复。

### 9.3 与任务表的偏差（5 处，均有理由）

1. **label 用「光晕 · 触发阈值」/「柔光 · 触发阈值」**（任务表写的是"光晕·阈值"）：
   `DETAIL_META` 与 `preset-DESIGN.md` §1 第 3 层表**已确立"触发阈值"这个术语**
   （E4 设计文档 §3.2.3 亦称"高光触发阈值"）；改用"阈值"会让面板/详情/文档三处一起漂，
   且"阈值"单独出现不说明是**什么**的阈值。护栏要求"详情名包含面板 label" ⇒ 两处必须
   一致，我选择保留更精确的既有术语（**面板 label 与详情文案逐字相同**，见 §5）。
2. **暗角三条 label 加了「暗角 ·」前缀**（任务表未提）：扩组后组内 3 条"强度"必须消歧；
   改后的文案与 `DETAIL_META` 既有文案**逐字相同**（改前是"详情带前缀、面板不带"）。
3. **`PRESET_SLOT_CLAMP` 补了 `[95..97] = 0..1`**：它属于"量程常量"表（任务允许的范围）。
   不补的话，预设强度 > 1 外推时柔光三条会越出 UI 量程，与 `[62..65]` 的处理不一致。
4. **抽了纯函数 `resetFilmTextureParams`**（`EditorExport.kt`）：任务要求"纯逻辑优先抽函数
   ⇒ JVM 可测"；组重置从"写 1 个槽"变成"写 3 个槽"，抽出来才能测（原 `resetVignette`
   是 UI 层 3 行，JVM 测不到）。
5. **`formatMono` 改名 `formatOff100`**：它现在服务黑白 / 光晕强度 / 柔光强度三条，
   "mono"这个名字已经不准（名字必须说真话）。改名触及 `EditorU6bParamUiTest` 的 10 处
   调用 —— **纯机械改名，断言一条未放宽**。

---

## 10. 参考

- `docs/reports/soft-glow-SPEC.md`（柔光规格，槽位与默认值 §3）
- `docs/reports/editor-E4-film-params-DESIGN.md` §3.2（光晕规格，参数映射 §3.2.3）
- `docs/reports/e4-5-halation-REPORT.md` / `docs/reports/sg1-softglow-REPORT.md`（引擎侧验收）
- `docs/reports/preset-DESIGN.md` §1.6（能力矩阵）/ §9.2（完整参数清单）
