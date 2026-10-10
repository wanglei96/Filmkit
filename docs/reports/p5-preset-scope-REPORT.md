# P-5 报告：预设的"字段作用域"在 UI 上显式化（含 P-5b 复核补丁）

**状态**：代码留在工作区（**未 commit**，等架构师复核后提交出包）。
**场景**：一条含 `[25]` 单色的黑白预设，在编辑器里套用**照片不会变黑白**（`passStyle` 零引用）；
反之含 `[78]` 清晰度的预设，在拍照界面套用**无效**。用户只会以为是 bug。

**本报告含两轮**：
- **P-5**：作用域提示（纯函数 + 三处 UI）；
- **P-5b**（架构师复核裁决）：把 `[28]` / `[31..33]` 加进 `presetSlotIncluded` 的排除列表
  （引擎色彩科学状态，不是用户风格）+ 术语"趾肩"→"胶片曲线"。

---

## 1. 改了什么（6 个文件）

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/camera/PresetScope.kt` | **新增**（唯一判据表 + 纯函数 `presetScopeNotice`） |
| `app/src/test/java/com/filmkit/app/camera/PresetScopeTest.kt` | **新增**（10 个用例，含 §1.6 文档对拍） |
| `app/src/main/java/com/filmkit/app/camera/EditorExport.kt` | **P-5b**：`PRESET_ENGINE_KNOB_SLOTS` 加 `[28]` / `[31..33]`（+ KDoc 逐条判据） |
| `app/src/test/java/com/filmkit/app/camera/EditorPresetTest.kt` | **P-5b**：排除断言 + 恒等值实测断言 |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | `LutSheet` 预设 tab：卡片列表下方一行提示（场景 = `CAMERA`） |
| `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt` | `FilmPresetRow`：分组小字下第三行（场景 = `EDITOR`） |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | `PresetRow`：副标题下第三行（场景 = `EDITOR`） |

纯函数（JVM 可测，无 Compose / prefs / 引擎依赖）：

```kotlin
internal enum class PresetScene { CAMERA, EDITOR }
internal fun presetScopeNotice(preset: EditorPreset, scene: PresetScene): String?
```

全部生效时返回 `null` ⇒ **UI 侧不渲染任何东西**（不挂"全部生效"这种每条都有的噪音）。

### 三处 UI 的实际接线（行号）

| 位置 | 代码 |
|---|---|
| 拍照「预设」 | `CameraScreen.kt:2147` `presetScopeNotice(selectedPreset, PresetScene.CAMERA)?.let { … }` |
| 查看页「胶片」 | `PhotoViewScreen.kt:833` `FilmPresetRow` 内 `presetScopeNotice(p, PresetScene.EDITOR)?.let { … }` |
| 编辑器「预设」 | `EditorScreen.kt:3563` `PresetRow` 内 `presetScopeNotice(p, PresetScene.EDITOR)?.let { … }` |

三处都是**既有的小字排版**（`FilmTheme.CreamDim` + 10/11sp + `maxLines = 1` + 省略号），
不新增组件、不加弹窗、不用警告色。

### ✅ 架构师裁决 1：批准"拍照界面提示不放卡片 sub"的偏离

任务书写"在**卡片副标题**补一句极短提示"。实测**放不下**：`LutCard` 的 `sub` 是 P-2a 为
"分组 · LUT"设计的**定宽 76dp / 9sp / 单行省略号**（`CameraScreen.kt:2665-2676`），
76dp ÷ 9sp ≈ **8 个中文字宽**，而 `"徕卡 · LUT"` 已占 ≈7 —— 拼上去会被截成
`"徕卡 · LUT · 仅…"`，**只剩半句话**。

⇒ 改为**卡片列表下方一行**（与既有 `"还没有预设 · 在编辑器里保存"` 同一排版、同一字号）。
裁决：**批准**，不为这个改公共组件。

---

## 2. 判据表来源（`PresetScope.kt`，唯一维护点）

表 = `docs/reports/preset-DESIGN.md` **§1.6 字段能力矩阵**，逐行注明依据：

```kotlin
private val SLOT_SCENES: Map<Int, Set<PresetScene>> = buildMap {
    for (s in 5..10) put(s, CAMERA_ONLY)   // [5..10] 趾肩 latitude（E4-4；编辑器无 filmic 落点）
    for (s in 23..25) put(s, CAMERA_ONLY)  // [23][24] 分离色调 / [25] 单色（passStyle 未接线）
    for (s in 37..38) put(s, CAMERA_ONLY)  // [37][38] filmic 黑点 / 白点
    put(78, EDITOR_ONLY)                   // [78] 清晰度（显示域 USM）
    for (s in 88..94) put(s, EDITOR_ONLY)  // [88..94] 色温/色调/影调分区/褪色
}
internal fun presetSlotScenes(slot: Int): Set<PresetScene> = SLOT_SCENES[slot] ?: BOTH
```

**不在表里的槽位 = 两条链路都生效**（`[0]`/`[1..3]`/`[4]`/`[20..22]`/`[26][27][60][61]`/`[66..68]` 等）——
表只记"有落点差异"的槽位，因为提示只在差异处出现。

### 2.1 P-5b：`[28]` / `[31..33]` 被排除出预设（架构师裁决 2）

`presetSlotIncluded()` 原来漏了这两组 —— 它们是**引擎色彩科学状态**，与已排除的
`[35] auto_tone` / `[36] local_tone` 同类，判据逐条写进 `PRESET_ENGINE_KNOB_SLOTS` 的 KDoc：

| 槽位 | 判据（原文引用） |
|---|---|
| `[28]` `tone_strength` | `CameraPresets.kt:59-61`："1.0 = 开（黑位 + 中灰锚定 S + 高光 rolloff，**非风格化**；治'发灰'）" + `CameraController.applyTonePref` 按 prefs（`base_tone`）**重写它** ⇒ 引擎开关，不是风格 |
| `[31..33]` `look_strength`/`look_sat`/`look_chroma` | `CameraPresets.kt:66-88`："色彩外观层（M3 P1）…… v0.7.7：1.35 → **1.0（恒等）**…… v0.7.9：设置页「色彩外观层」开关已删除" ⇒ 标定层，**UI 上根本没有入口能改它** |

```kotlin
private val PRESET_ENGINE_KNOB_SLOTS = setOf(28, 30, 31, 32, 33, 35, 36)
```

**实测确认（不是假设）**，两条都进了单测：

1. **恒等值都是 1.0**（`EditorPresetTest` 断言）：⇒ 排除它们**不会**改变"用户没改过的参数"
   的行为 —— 稀疏化本来就按恒等值丢弃（`presetSparseSlots`）。恒等值哪天变成别的数，测试会红。
2. **内置三条不含这些槽**（`PresetScopeTest` 断言 `p.slots.containsKey(28/31/32/33) == false`）：
   ⇒ 这次排除对 `builtin.json` **零影响**（`BuiltinPresetsTest` 的逐槽配方断言也早已钉住）。

⇒ `SLOT_SCENES` 随之删掉 `[28]` / `[31..33]`（不再进预设 ⇒ 作用域无从谈起），
并由新用例 `excludedSlotsHaveNoScope` 钉住"排除项与作用域表同步"。

### 2.2 `[34]` LUT 强度：**不参与提示**

`[34]` 由 `lut.strength` 承载，`presetSlotIncluded(34) == false` ⇒ 它进不了预设 `slots`；
坏数据里真出现了也不提示（不是"场景"问题）。单测 `lutStrengthSlotIsNeverReported` 钉住。
`[28]` / `[31..33]` 排除后同理。

---

## 3. 与 §1.6 的核对结果

**结论：无冲突。** 判据表逐槽等于 §1.6 的能力矩阵，由单测**正则解析文档表格对拍**钉住
（`PresetScopeTest.scopeTableMatchesDesignMatrix`，与 `EditorStyleWhitelistTest` 同一手法）。

解析口径：单元格以 `❌` 开头 = 不生效；`✅` / `⚠`（如 `[20..22]` 编辑器侧"只读 `[21]`"）
以及文字描述（`[30][35][36]` 的"工程旋钮"/"部分"）都算生效。解析得到 **55 个槽位**，其中：
- **单侧生效且进预设 = 19 个**（`[5..10]`+`[23..25]`+`[37][38]`+`[78]`+`[88..94]`）⇒ 逐个断言；
- **被 `presetSlotIncluded` 排除 = 8 个**（`[28]`+`[30]`+`[31..33]`+`[34]`+`[35][36]`）⇒ 跳过
  （它们进不了预设，作用域无意义），由 `excludedSlotsHaveNoScope` 单独钉住。

### ★ §1.6 是否需要架构师同步：**已经同步了，无需再改**

复核期间 `preset-DESIGN.md` 被架构师更新（`git diff` 确认，**本卡一行未动文档**），§1.6 表格：

| 行 | "进预设？"列的变化 |
|---|---|
| `[28]` | `⚠ 拍照侧被 prefs 覆盖` → **`❌ 已排除`（引擎色彩科学状态……P-5b）** |
| `[31..33]` | `⚠ 引擎标定层，不建议进预设` → **`❌ 已排除`（引擎标定层……P-5b）** |
| `[23][24]` / `[25]` / `[37][38]` | `⏳ 等接线` / `⏳ 仅拍照侧有效` → **`✅ 仅拍照侧有效`**（措辞澄清，作用域未变） |
| `[26][27][60][61]` | `（E4-3 重写中）` → `（E4-3 已重写）` |

**对代码零影响**：本卡的解析只读表格的**第 3/4 列**（拍照链路 / 编辑器链路），
第 5 列（"进预设？"）由 `presetSlotIncluded` 单独管 —— 两边**自动对齐**，
`scopeTableMatchesDesignMatrix` 在同步后的文档上依然全绿（最终全量跑的即同步后版本）。

### 三条口径说明（都不是冲突）

1. **`[20..22]` 编辑器侧只读 `[21]`**（§1.6 原文 `⚠ 只读 [21]`）。按"❌ 才算不生效"的口径
   判为**两侧生效、不提示**。裁决 3：**同意**，理由是它是**同一个 UI 概念（饱和度）的三个
   分量** —— 提示"含 2 项仅拍照参数（暗部饱和/高光饱和）"只会制造噪音。
   ★ **这是刻意的简化**（严格按 §1.6，编辑器只读 `[21]`）—— 若将来编辑器拆成三个独立滑杆，
   这行要重判（改 `SLOT_SCENES` 一处）。
2. **`[62..65]` 光晕 / `[69..77]` 层间效应**：§1.6 里两侧都是 ❌（未实现，等 E4-5 / E4-6）。
   本表按"两侧都生效"处理（⇒ 不提示）—— 本表的语义 = "**换个场景就会生效/失效**"，而这两组
   换到哪条链路都没有落点，属于"引擎未实现"，不是"作用域差异"。裁决 3：**同意**。
   ★ **待办**（架构师要求记入）：E4-5 / E4-6 接线后，本表要按 §1.6 的新状态更新
   —— 那时 `scopeTableMatchesDesignMatrix` 会红，提醒改代码（这正是对拍测试的用途）。
3. **`[30][35][36]` / `[39..59]` / `[79..87]` / `[29]` / `[28]` / `[31..33]`**：
   `presetSlotIncluded` 已排除 ⇒ 永远不出现在 `slots` 里，与本表无关。

另：§1 第 2 层表格（`preset-DESIGN.md:46-47`）给 `[5..10]` 标的是 `⚠ 引擎待做（E4-4）`，
早于 §1.6 的实测结论；§1.6 的 E4-4 段落（`:135-139`）已澄清为"**仅拍照**，不是待接线"。
**代码与测试按 §1.6**（任务书口径：以 §1.6 为准）。

---

## 4. 提示文案与名字来源

模板（`[presetScopeNotice]`，括号里最多 3 个名字，超出加 `…`）：

| 场景 | 文案 |
|---|---|
| `CAMERA` | `含 N 项仅后期参数（清晰度/色温/色调…），拍照时不生效` |
| `EDITOR` | `含 N 项仅拍照参数（分离色调/黑白），编辑器里不生效` |

**"N 项"= 去重后的名字数**（不是槽位数）：`[23]/[24]` 都是"分离色调"、`[5..10]` 都是"胶片曲线"
⇒ 括号里列几个名字，用户就能数出几项（与任务书给的两个例子口径一致）。

名字来源（**优先取既有 UI 文案，不新造译名**）：

| 槽位 | 名字 | 来源 |
|---|---|---|
| `[78]` | 清晰度 | `EditorScreen` 「调整」组滑杆 label（`:2248`） |
| `[88]/[89]` | 色温 / 色调 | `EditorScreen` 滑杆 label（`:2187` 等） |
| `[90]/[91]` | 高光 / 阴影 | `EditorScreen` 影调分区 label |
| `[92]/[93]` | 白色色阶 / 黑色色阶 | 同上 |
| `[94]` | 褪色 | `EditorScreen` 「滤镜」组 label（`:2291`） |
| `[37]/[38]` | 黑点 / 白点 | `SettingsScreen` 滑杆 label（`:535`「黑点（多暗算黑）」/「白点（多亮算白）」） |
| `[5..10]` | **胶片曲线** | ⚠ **UI 无既有文案**（编辑器没有这条滑杆）。P-5 原用工程术语"趾肩"，**裁决 2 改为"胶片曲线"**（普通用户不认"趾肩"）⇒ 现为"沿用 + 裁决改词" |
| `[23]/[24]` | 分离色调 | ⚠ **UI 无既有文案**（`passStyle` 未接线，编辑器没做滑杆）⇒ 沿用 Lightroom 中文官方译名（项目多份报告逐字使用） |
| `[25]` | 黑白 | ⚠ **UI 无既有文案**（引擎字段 `monochrome`；UI 只有预设名"徕卡黑白"）⇒ 沿用任务书示例用词 |

**找不到既有 UI 文案的 3 组**（胶片曲线 / 分离色调 / 黑白）已逐条标注来源；
`[28]`（基准影调）/ `[31..33]`（色彩外观层）随 P-5b 排除而**从名字表移除**，不再需要译名。
若架构师要改词，改 `PresetScope.kt` 的 `SLOT_LABELS` 一处即可（单测会跟着红）。

---

## 5. 验证

### 5.1 构建 + 单测（全量，`--rerun-tasks` 避开 build cache 假绿）

```
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

- 结果：`BUILD SUCCESSFUL`，**任务数 = 46 executed**（`46 actionable tasks: 46 executed`，零 up-to-date）
- 用例数：**418 个，0 失败 / 0 跳过**（39 个测试类）（改前基线 408；新增 `PresetScopeTest` 10 个）
- ⚠ **本仓库单跑会命中 build cache 给出假绿**（实测：改完代码单跑 `testDebugUnitTest` 得到
  `FROM-CACHE`，2 秒"成功"）⇒ 本卡所有验证都用 `--rerun-tasks` 强制重跑。
- 新增用例清单（`PresetScopeTest`，10 个）：

| # | 用例 | 钉住什么 |
|---|---|---|
| ① | `fullyEffectivePresetHasNoNotice` | 全生效 ⇒ `null`（两个场景 + 「标准」空 slots） |
| ② | `cameraOnlyParamsAreReportedInEditor` | `[25][23][24]` 在 EDITOR ⇒ `含 2 项仅拍照参数（分离色调/黑白），编辑器里不生效` |
| ③ | `editorOnlyParamsAreReportedInCamera` | `[78][88][89]` 在 CAMERA ⇒ `含 3 项仅后期参数（清晰度/色温/色调），拍照时不生效` |
| ④ | `scopeTableMatchesDesignMatrix` | **判据表 == §1.6 文档表格**（55 槽解析 / 19 个单侧进预设 / 8 个被排除） |
| ⑤ | `excludedSlotsHaveNoScope` | **排除项与作用域表同步**（P-5b：`[28]`/`[31..33]` 不在表里；共 39 个排除槽） |
| ⑥ | `builtinPresetsNoticesMatchTheirData` | 内置三条的**实际提示** + 不含新排除槽（见 5.3） |
| 附 | `filmCurveCountsAsOneItem` | `[5..10]` 算一项（"胶片曲线"） |
| 附 | `longNameListIsTruncated` | 名字超 3 个截断成一行（≤40 字） |
| 附 | `everySingleSideSlotHasAChineseLabel` | 19 个单侧槽位都有中文名（不得回落成语义名） |
| 附 | `lutStrengthSlotIsNeverReported` | `[34]` 不算进提示 |

### 5.2 mutation 自检（判据表 / 排除列表改错 ⇒ 用例必须红 ⇒ 恢复）

| # | 注入的错误 | 结果 |
|---|---|---|
| 1 | `[25]` 从 `CAMERA_ONLY` 改成 `EDITOR_ONLY` | **2 个用例红**：`scopeTableMatchesDesignMatrix` + `cameraOnlyParamsAreReportedInEditor` |
| 2 | `[78]` 从 `EDITOR_ONLY` 改成 `CAMERA_ONLY` | **3 个用例红**：`scopeTableMatchesDesignMatrix` + `editorOnlyParamsAreReportedInCamera` + `longNameListIsTruncated` |
| 3 | **P-5b**：`[28]` 从 `PRESET_ENGINE_KNOB_SLOTS` 里拿掉 | **3 个用例红**：`EditorPresetTest.engineKnobsDecodeSlotsLutStrengthAndLocalAreNeverStored` + `scopeTableMatchesDesignMatrix` + `excludedSlotsHaveNoScope` |

三次都已恢复（`grep MUTATION` 无残留），恢复后全量测试重新跑绿（见 5.1）。

### 5.3 内置三条（真实数据，**不假设**）

`PresetScopeTest` 从 `src/main/assets/presets/builtin.json` 读真实数据（与 `BuiltinPresetsTest`
同一口径），实际结果（测试 stdout）：

```
BUILTIN_SCOPE builtin_leica_m9       editor="含 1 项仅拍照参数（分离色调），编辑器里不生效"
BUILTIN_SCOPE builtin_ricoh_negative editor="含 1 项仅拍照参数（分离色调），编辑器里不生效"
```

- 徕卡一瞬 / 理光负片含 `[23][24]` ⇒ **编辑器里必须提示** ✅（P-2b 报告已记载的已知后果）
- **徕卡黑白不含 `[25]`** —— 实测数据：黑白靠 `leica_b_w_hc` LUT 的**通道混合**实现，
  先 `[25]` 去色会把 LUT 的黄绿滤镜响应抹平（`BuiltinPresetsTest.bwPresetHasNoMonochrome`
  已钉住这条反直觉决定）⇒ 编辑器里**全部生效，不提示** ✅
- 三条都不含 `[78]`/`[88..94]` ⇒ **拍照界面一条提示都没有** ✅
- 三条都**不含** P-5b 新排除的 `[28]`/`[31..33]` ⇒ 排除对内置包零影响 ✅（实测断言）

### 5.4 三处 UI 的验证方式

- **代码引用**：见 §1 的行号表；三处调用的是同一个纯函数，场景参数分别是 `CAMERA` /
  `EDITOR` / `EDITOR`。
- **提示内容可推导**：文案由 `presetScopeNotice` 逐字生成，而该函数被 10 个 JVM 用例覆盖
  （含内置三条真实数据）⇒ "UI 会显示什么"不必靠肉眼。逐条对照（"无"= 该处不渲染任何东西）：

| 预设 | 拍照界面「预设」（`CAMERA`） | 查看页「胶片」/ 编辑器「预设」（`EDITOR`） |
|---|---|---|
| 内置「标准」（空 slots） | 无 | 无 |
| 徕卡一瞬（含 `[23][24]`） | 无（这两项在拍照侧**生效**） | 含 1 项仅拍照参数（分离色调），编辑器里不生效 |
| 理光负片（含 `[23][24]`） | 无 | 同上 |
| 徕卡黑白（**不含** `[25]`） | 无 | 无 |
| 用户在编辑器调了清晰度 + 色温 + 色调后保存的预设 | 含 3 项仅后期参数（清晰度/色温/色调），拍照时不生效 | 无 |

- ⚠ **未真机验证**（本次无设备）：三处 `Text` 的排版（一行放得下、省略号不吞掉结论）只在
  代码层面确认（`maxLines = 1` + 11/10sp + 全宽/weight(1f)），**没有截图证据**。
  建议出包后按 `docs/reports/editor-device-test-CHECKLIST.md` 的方式各看一眼。

---

## 6. 未做与存疑

**明确不做**（任务书要求 + 架构师裁决）：

- 不做参数级灰显滑杆（预设面板现在只列预设，没有逐参数滑杆 —— 那是将来的"预设详情/编辑"）；
- **不改预设数据层**：`builtin.json` 零改动；`EditorExport.kt` 只动了 `presetSlotIncluded`
  的**策略层**（P-5b 裁决要求），`EditorPreset` 结构 / 线格式 / 解析 / 序列化**零改动**；
- **不改引擎**（`FrameRenderer.cpp` / `FilmParams.h`）—— 零改动；
- **不改设计文档**（`preset-DESIGN.md`）—— 零改动（§1.6 的同步由架构师完成，见 §3）；
- 不新增弹窗、不打断操作（提示是列表里的一行说明性文字，不是错误/警告色）。

**存疑 / 留给架构师**：

1. **`[20..22]` 的判据**：裁决 3 同意"两侧生效、不提示"，理由与"刻意简化"的口径已记入 §3。
2. **拍照界面提示的落点**：裁决 1 批准（见 §1）。若将来要卡片上有标记，可选方案 = 给
   `LutCard` 加一枚极短角标（如 `仅后期`），代价是改公共组件 + 卡片高度对齐；本卡没做。
3. **术语**：`[5..10]` 已按裁决改为"胶片曲线"；`分离色调` / `黑白` 保留（裁决 2）。
   名字表只剩这 3 组"无既有 UI 文案"，来源见 §4。
4. **`[62..65]` / `[69..77]`（两侧都未实现）不在提示范围**：裁决 3 同意，**待办**已记入 §3
   （E4-5 / E4-6 接线后对拍测试会红，提醒更新本表）。
5. **拍照界面的提示只在"选中项"上出现**（面板级一行），不在每张卡片上 —— 用户要先点卡片
   （= 套用）才看到。这是 76dp 卡片宽度约束下的取舍（裁决 1 已批准该偏离）。
6. **P-5b 的连带影响面**：`presetSlotIncluded` 是**保存侧**的判据 ⇒ 用户在编辑器里调不到
   `[28]`/`[31..33]`（设置页的「基准影调」开关除外，那本来就走 prefs 覆盖层），
   所以这次排除**只影响"将来会不会有预设带这些槽"**，不影响任何现存数据（实测：内置三条
   不含、恒等值 1.0）。若 E4-x 将来要把 `[28]`/`[31..33]` 做成用户可调的风格参数，
   要连 KDoc 判据一起重审（`EditorPresetTest` 的断言会红）。
