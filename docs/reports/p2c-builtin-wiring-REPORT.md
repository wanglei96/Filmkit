# P-2c 报告：内置预设接入三处列表 + 执行 4 条架构裁决

- **基线 HEAD**：`814bbf7`（工作区干净，未 commit —— 按任务书交付方式，改动留在工作区等架构师复核）
- **范围**：UI 三处列表的数据源 + 编辑器预设面板的删除保护 + `CameraPresetApply.kt` 的 LUT 口径 + 死代码清理
- **没动**：引擎 / 契约（`FilmParams`）/ shader / `EditorExport.kt` 的预设数据层（逐条核对，见 §6.1）

## 0. 结论摘要

1. **三处列表全部接上内置包**（+ **第 4 处**：P-3 从查看页进编辑器的恢复链路 —— 不改这一处，
   查看页能列出内置三条、点进去却套不上，是本卡最容易漏的一环）。
2. **内置条目在编辑器里不可删**：删除按钮**根本不渲染**（不是"点了才报错"），位置换成不可点的
   「内置」标记。判据抽成纯函数 `presetDeletable`（唯一实现），两端都有单测。
3. **4 条裁决逐条落地**：① 标准清 LUT、② MISSING ⇒ 清空 + 提示、③ 按裁决**不改**、④ 死代码已删。
4. **实现中发现并修掉一个本卡引入的新坑**：编辑器的「保存 / 删除」把 `PresetStore` 的返回值
   直接赋给面板列表 —— 那个列表只含用户预设，会让内置三条当场消失（§1.5）。
5. **构建 + 单测真跑绿**：`BUILD SUCCESSFUL`，`46 actionable tasks: 46 executed`，
   **408 tests / 0 failures**（改前 404 ⇒ 本卡 +4）。
6. **Mutation 自检 ① ② 各一次**：故意改回旧行为 ⇒ 对应用例红（退出码 1）⇒ 已恢复（§4）。

## 1. 改了什么（文件:行号）

8 个文件，+308 / −82。

### 1.1 三处列表接入内置包（+ 第 4 处）

| # | 位置 | 改法 |
|---|---|---|
| 1 | `ui/CameraScreen.kt:230` | `PresetStore.load(prefs)` → `BuiltinPresets.all(context, prefs)`（`presets` state 初始化；`context`/`prefs` 都是该 composable 既有变量，未新造） |
| 2 | `ui/PhotoViewScreen.kt:581` | 同上（「胶片」按钮回调里打开面板时读一次，与 P-3 既有节奏一致） |
| 3 | `ui/EditorScreen.kt:725` | 同上（`togglePresets()` 打开预设面板时读一次） |
| 4 | `ui/EditorScreen.kt:1371` | **任务书未列，但必须改**：P-3 的初始预设恢复 `initialPresetOf(PresetStore.load(prefs), initialPresetId)`。查看页选内置预设 ⇒ 路由带 id 进编辑器 ⇒ 这一处按 id 找预设；不改则**永远找不到**（表现："点了内置预设、进编辑器毫无变化"） |

第 4 处的判据：三处列表的数据源必须与"按 id 反查预设"的数据源**是同一个** ——
列表能列出、反查却查不到，是本仓库 ADR-12「不留点不动的按钮」的同型缺陷。

### 1.2 内置预设的保护（不可删）

- **判据**：`camera/BuiltinPresets.kt:139`
  ```kotlin
  internal fun presetDeletable(p: EditorPreset): Boolean = p.source != PRESET_SOURCE_BUILTIN
  ```
  **唯一实现**（UI 不重写 `source` 比较，测试按它断言）。
- **UI**：`ui/EditorScreen.kt:3570` —— `if (presetDeletable(p)) { 「删除」按钮 } else { 「内置」标记 }`。
  内置条目的删除按钮**不渲染**，因此不存在"点了才报错"的路径。
- **勘察结论（只保护真正存在的操作）**：E3-1a 面板的条目级操作只有两个 —— **「套用」** 与
  **「删除」**（`PresetRow`，`EditorScreen.kt:3545-3590`）。重命名 / 更新为当前 / 长按菜单 /
  收藏都在 **E3-1b 才做**（面板 KDoc 明写），所以本卡**没有**为它们加保护（不存在的东西不保护）。
  内置条目的「套用」与强度滑杆（E3-1c）行为与用户预设**完全一致** —— 保护的是"删改数据"，
  不是"使用"。
- 用户预设（`source == "user"`）的渲染与行为**逐位不变**。

### 1.3 裁决 ① ②：LUT 清空口径（`camera/CameraPresetApply.kt`）

`PresetLutAction` 从三态扩成**四态**（`:63`），并带上"是否清空"的语义属性：

```kotlin
internal enum class PresetLutAction(val clearsCurrentLut: Boolean) {
    KEEP(false), APPLY(false), MISSING(true), CLEAR(true),
}
```

`presetLutAction`（`:97`）判定顺序 —— **标准在最前**，这一行是裁决 ① 的落地点：

```kotlin
if (preset.id == CAMERA_STANDARD_PRESET_ID) return PresetLutAction.CLEAR
val id = preset.lutId ?: return PresetLutAction.KEEP
return if (lutExists(id)) PresetLutAction.APPLY else PresetLutAction.MISSING
```

拍照侧落地（`ui/CameraScreen.kt:497-525`）：

```kotlin
if (action.clearsCurrentLut) clearCameraLut()      // :506 裁决 ①②
controller.setFilmParams(cameraPresetParams(p))
when (action) {
    PresetLutAction.APPLY -> lutEntry?.let { lutPending = it }
    PresetLutAction.MISSING -> Toast("…LUT 已不在库中 · 已清除当前 LUT")   // :512 裁决 ②
    PresetLutAction.CLEAR -> Unit                  // :518 裁决 ①（用户主动选"无风格"，不加提示）
    PresetLutAction.KEEP -> Unit
}
```

**清空动作抽成唯一实现** `clearCameraLut()`（`ui/CameraScreen.kt:466`）= 引擎旁路
`controller.setLut(null)` + 摘 prefs `lut_id` + 一条既有格式的 `settings.lut` 日志。
既有的两处同型逻辑（用户点「无 LUT」、删除当前选中的 LUT）也改为调用它 ——
原来三份复制粘贴里**有两份忘了清 `lutPending`**（在飞的 IO 加载会在几百毫秒后把 LUT 装回来，
把"清空"撤销掉），现在只有一处实现，不可能再漏。

### 1.4 裁决 ④：死代码清理

- 删除 `ui/CameraPresets.kt` 的 `names`（`listOf("标准")`）与 `byIndex(i)`。
- **清理前的全仓核对**（任务书要求）：
  - `grep -rn "CameraPresets\.names\|CameraPresets\.byIndex" app/src/` ⇒ **无输出**（改动前即无调用点）；
  - `grep -rn "\bnames\b|\bbyIndex\b" app/src/` 的其余命中全是无关的局部变量
    （`ui/SpikeUi.kt:124,251` 的 DFT 变体名数组、`BuiltinPresetsTest.kt:249` 的测试局部变量）。
  - 历史调用点只存在于文档（`docs/reports/editor-feasibility-REPORT.md`、`preset-DESIGN.md`、
    `p2a-camera-preset-REPORT.md`）—— 文档不改（历史记录），本报告即口径更新点。
- **保留**：`standard()`（69 处引用）、`COUNT`（24 处）、`neutral()`（4 处，`standard()` 内部实现）。
  本文件 KDoc 已更新：内置胶片预设**重新引入**了，但走的是 `BuiltinPresets` 那条只读通道，
  与"按索引取预设"的旧机制无关。
- **任务书提到"`CameraPresetsTest.kt` 里对应的用例"—— 实际不存在**：该文件只有 3 条用例
  （长度契约 / E2-11 槽位 / 新槽位默认值），没有任何 `names` / `byIndex` 断言（grep 证据同上）。
  所以测试侧**零改动**，不是"漏改"。

### 1.5 实现中发现并修掉的新坑（本卡引入，必须一起修）

`savePreset`（`EditorScreen.kt:766`）与 `deletePreset`（`:910`）原来把 `PresetStore.upsert/delete`
的返回值直接赋给面板列表 —— 而 `PresetStore` 只读写 **prefs**，返回的列表**只含用户预设**。
列表接上内置包之后，这两个动作会让**内置三条当场从面板上消失**（直到重开面板）：

```kotlin
// ★ P-2c：不能直接把 PresetStore 的返回值赋给 presets —— 那个列表只含用户预设。
presets = mergePresets(PresetStore.upsert(prefs, p), BuiltinPresets.load(context))
```

合并口径与 `BuiltinPresets.all` **完全一致**（同一个 `mergePresets`）。

### 1.6 `BuiltinPresets.kt`：合并语义（顺序 + 去重）

```kotlin
fun all(context: Context, prefs: SharedPreferences): List<EditorPreset> =     // :104
    mergePresets(PresetStore.load(prefs), load(context))

internal fun mergePresets(user: List<EditorPreset>, builtin: List<EditorPreset>) // :125
    : List<EditorPreset> {
    val seen = user.mapTo(HashSet(user.size * 2)) { it.id }
    return user + builtin.filter { seen.add(it.id) }
}
```

- `mergePresets` 是**纯内核**（`all` 只多一层 Android 的 assets 读）⇒ JVM 单测直接驱动；
- 原实现 `PresetStore.load(prefs) + load(context)` **没有去重**，而 id 是 UI 的唯一判据
  （`LazyColumn` 的 `key = { it.id }` 遇到重复 key **直接抛异常**；选中态比对 id；
  `initialPresetOf` 按 id 取第一条）。prefs 反序列化出来的 `id` 字段不受任何格式约束 ⇒
  撞车可达。现在**先到者胜**（用户那份留下），且内置包内部重复也一并收敛。

## 2. 4 条裁决逐条落地

| # | 裁决 | 落地 | 证据 |
|---|---|---|---|
| ① | 选「标准」要连 LUT 一起清 | `presetLutAction` 首行判标准 ⇒ `CLEAR`（`CameraPresetApply.kt:97`）；`clearsCurrentLut` 驱动 `clearCameraLut()`（`CameraScreen.kt:506`）；`CLEAR -> Unit` 不加提示（用户主动选"无风格"） | 单测 `standardClearsCurrentLut` + mutation ① |
| ② | LUT 缺失口径统一为"清空 + 明确提示" | 拍照侧 `MISSING(true)` ⇒ 清空 + Toast「…已不在库中 · **已清除当前 LUT**」（`CameraScreen.kt:512`）；编辑器侧 `applyPreset` 的 `entry == null` 分支本来就是清空（`film[34]=0; lutId=null; engine.setLut(null)`），**未改** | 单测 `missingLutClearsInsteadOfKeeping` + mutation ② |
| ② 附 | ⚠ **预设不含 LUT（`lutId == null`）仍然"不动当前 LUT"** | `presetLutAction` 第二行仍是 `?: return KEEP`，`clearsCurrentLut = false` | 回归哨兵单测 `lutWithoutReferenceKeepsCurrentLut`（含"资产查询函数不得被调用"） |
| ③ | 手动改 LUT 不清预设选中标记 | **按裁决不改**：拍照页手动选 / 关 LUT（`onPickLut`）不触碰 `selectedPreset` / `camera_preset_id`；与编辑器的 `appliedPreset` 行为一致 | 代码未改（本卡对该路径零 diff）；`onPickLut` 现在只是复用 `clearCameraLut()`，行为等价 |
| ④ | `CameraPresets.names` / `byIndex` 已成死代码 ⇒ 清理 | 两个成员已删；`standard()` / `neutral()` / `COUNT` 保留 | `grep` 无调用点（§1.4）；全量单测绿 |

## 3. 单测（用例数 + 真跑证据）

**构建命令**（任务书指定，自己看退出码）：

```
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

**真跑纪律**：单跑 `testDebugUnitTest` 会命中 build cache 给假绿 ⇒ 每次先
`rm -rf app/build/test-results` 且加 `--rerun-tasks`。最终一次的输出：

```
BUILD SUCCESSFUL in 28s
46 actionable tasks: 46 executed          ← 任务数 = 46 executed（无 UP-TO-DATE）
tests=408 failures=0 errors=0 skipped=0   ← 从 app/build/test-results/testDebugUnitTest/*.xml 汇总
app/build/outputs/apk/debug/app-debug.apk 已产出
```

**退出码 0**（`BUILD SUCCESSFUL`）。**用例数 408**（改前 404 ⇒ 本卡 **+4**）：

| 测试类 | 改前 | 现在 | 新增/改动 |
|---|---|---|---|
| `camera/CameraPresetApplyTest` | 9 | **11** | 新增 `standardClearsCurrentLut`（裁决 ①）、`onlyClearingActionsReportClearing`（四态语义一次钉死）；`missingLutDegradesToKeepInsteadOfClearing` **改名 + 改断言**为 `missingLutClearsInsteadOfKeeping`（裁决 ②）；`lutWithoutReferenceKeepsCurrentLut` / `lutInLibraryIsApplied` 补 `clearsCurrentLut` 断言 |
| `camera/BuiltinPresetsTest` | 10 | **12** | 新增 `mergePutsUserFirstAndKeepsIdsUnique`（顺序 + 去重 + 内置坏掉不连累用户）、`builtinPresetsAreNotDeletable`（保护判据两端） |
| 其余 38 个类 | 385 | 385 | 未改 |

任务书点名的 5 条测试要求，逐条对应：

1. **`BuiltinPresets.all` 的顺序与去重** ⇒ `mergePutsUserFirstAndKeepsIdsUnique`
   （`all` 的纯内核；`all` 自己只多一层 assets 读，JVM 测不了 Android `Context`）。
   覆盖：用户在前 + 内置在后、同 id 只留用户那份且总数不增、内置包内部重复收敛、内置包坏掉时用户预设一条不少。
2. **内置条目 `source == "builtin"`** ⇒ `builtinPresetsAreNotDeletable`（并顺带断言
   `presetDeletable` 的"用户可删 / 内置不可删 / imported 可删"三端）。
3. **①「标准」清 LUT** ⇒ 抽进纯函数 `presetLutAction` 的 `CLEAR` 态，`standardClearsCurrentLut` 直接驱动。
4. **② `MISSING ⇒ CLEAR`** ⇒ `missingLutClearsInsteadOfKeeping`（原用例按新裁决改名 + 改断言，
   **没有为了让测试绿而放宽** —— 断言从"KEEP"改成了"MISSING 且 `clearsCurrentLut`"）。
5. **回归哨兵（不含 LUT 仍不动）** ⇒ `lutWithoutReferenceKeepsCurrentLut`。

## 4. Mutation 自检（证明两条裁决的用例不是恒真断言）

两次都是**故意改回旧行为 ⇒ 跑用例 ⇒ 必须红 ⇒ 恢复**（`--tests` 过滤该类，仍带
`rm -rf app/build/test-results` + `--rerun-tasks`）：

**① 标准清 LUT** —— 删掉 `presetLutAction` 里"标准 ⇒ CLEAR"那一行（标准因此落到
`lutId == null ⇒ KEEP`，即裁决前的行为）：

```
CameraPresetApplyTest > standardClearsCurrentLut FAILED
    java.lang.AssertionError at CameraPresetApplyTest.kt:125
11 tests completed, 1 failed
BUILD FAILED in 13s     EXIT=1
```

**② MISSING ⇒ 清空** —— 把枚举的 `MISSING(true)` 改成 `MISSING(false)`（即"降级为保留当前 LUT"
的旧口径）：

```
CameraPresetApplyTest > onlyClearingActionsReportClearing FAILED  (kt:137)
CameraPresetApplyTest > missingLutClearsInsteadOfKeeping FAILED   (kt:113)
11 tests completed, 2 failed
BUILD FAILED in 13s     EXIT=1
```

两次均已恢复（`grep -n "MUTATION" app/src/` 无输出），并在恢复后**重跑全量**：
`46 executed / 408 tests / 0 failures / BUILD SUCCESSFUL`。

## 5. 验收判据逐条证据

| # | 判据 | 证据 |
|---|---|---|
| 1 | 三处列表都能看到内置三条 | 代码引用：`CameraScreen.kt:230`、`PhotoViewScreen.kt:581`、`EditorScreen.kt:725`（+ P-3 恢复链路 `:1371`）；数据层 `BuiltinPresets.all`（`:104`）= 用户 + 内置；单测 `mergePutsUserFirstAndKeepsIdsUnique` 钉住顺序与内容。**未真机验证**（见 §6.2） |
| 2 | 内置条目在编辑器里不可删 | 判据 `presetDeletable`（`BuiltinPresets.kt:139`）+ UI 分支 `EditorScreen.kt:3570`（不渲染删除按钮）；单测 `builtinPresetsAreNotDeletable` 断言"内置包每条都标 builtin ⇒ 必走不可删分支"。**验证方式 = 判据单测 + 代码分支复核**（Compose UI 层不可 JVM 测；真机确认项见 §6.2） |
| 3 | 4 条裁决逐条落地 | §2 表格（③ 明确写"按裁决不改"） |
| 4 | 构建 + 单测真跑退出码 0 + 用例数 | §3：`46 executed`、`408 tests / 0 failures`、`BUILD SUCCESSFUL`（改前 404） |
| 5 | mutation 自检 ① ② 各一次 | §4（两次均红 + 已恢复 + 恢复后全量绿） |
| 6 | 报告 | 本文件 |

## 6. 未做 / 存疑项

### 6.1 明确未做（范围外，逐条核对过）

- **引擎 / 契约 / shader / `EditorExport.kt` 的预设数据层**：零改动
  （`git diff --stat` 的 8 个文件里没有 `EditorExport.kt`、没有任何 C++/GLSL）。
- **重命名 / 更新为当前 / 收藏 / 长按菜单**：E3-1b 的功能，本卡不存在 ⇒ 无需保护（§1.2）。
- **`.fkpreset` 导入**（E3-2）：`PRESET_SOURCE_IMPORTED` 已存在但无入口；`presetDeletable` 对它是
  "可删"，单测里已提前钉住这条口径。
- **拍照页预设列表的实时刷新**：`CameraScreen` 的 `presets` 仍是 `remember` 初始化时读一次
  （P-2a 既有行为）—— 用户在编辑器里新存一条预设、返回拍照页（Activity 未重建）时列表不会更新，
  需要重进相机页。**这是既有缺陷，不在本卡范围**，但它现在也影响"内置三条的可见性"之外的
  用户体验，建议单开一卡（修法：`LaunchedEffect` 在页面恢复时重读，或监听 `onResume`）。

### 6.2 存疑项（请架构师复核）

1. **`clearCameraLut()` 里顺手加了 `lutPending = null`**（`CameraScreen.kt:466`）。
   这是裁决 ①/② 能**真正生效**的必要条件：在飞的 `LaunchedEffect(lutPending)` 会在 IO 解析完成后
   `setLut` + 写回 `lut_id`，把刚清掉的 LUT 装回来（几百毫秒的窗口）。既有的"用户点「无 LUT」"
   与"删除当前 LUT"两条路径原来都有这个 race，现在三处共用一处实现后一并修掉。
   若架构师认为"修 race"超出本卡范围，可以回退 —— 但那样 ① 的"清 LUT"在竞态窗口内不可靠。
2. **「内置」标记的视觉**：删除按钮的位置换成不可点的 `DisabledInk` 描边文字「内置」。
   未在拍照页 / 查看页的卡片上加"内置"标记（那两处只显示 `分组 · LUT`）—— 判断是"不可删"
   只需要在**有删除动作的地方**表达。若产品上希望三处都能一眼分辨内置，需要另开小卡。
3. **`MISSING` 的提示文案**：拍照侧改为「预设「X」的 LUT 已不在库中 · 已清除当前 LUT」；
   编辑器侧仍是「预设「X」的 LUT 已不在库中」（它没有"清除"这个词，因为编辑器本来就没有
   当前 LUT 的概念可清）。文案未统一措辞，语义已统一（都清空）。
4. **`EditorPreset.source` 的可信度**：`presetDeletable` 依赖 `source` 字段，而它是从 prefs /
   assets 反序列化来的。用户可以手工改 prefs 把内置条目标成 `user` —— 但那需要 root 且只影响
   他自己的设备，且内置包**不在 prefs 里**（`PresetStore.delete` 对内置 id 是 no-op），
   所以即使绕开 UI 也删不掉内置包。**结论：不需要第二道防线**（`deletePreset` 未加 guard）。
5. **未真机验证的三项**（本卡只有 JVM 证据）：① 三处列表真机可见内置三条；
   ② 编辑器里内置条目的「内置」标记与用户条目的「删除」按钮；③ 选「标准」后 LUT 卡片回到
   「无」、选失效 LUT 的预设后 Toast 与画面同时变化。建议并入 E4 的真机 checklist。
