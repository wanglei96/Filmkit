# PS-1 预设管理 UI 显式化 —— 实现报告

> 卡号 PS-1 ｜ 用户反馈（2026-10-11）："**预设还是没有展示预设包含的配置面板。还是没办法新增、编辑、删除预设。**"
> 交付物：预设列表**每行「⋯」操作菜单** + **参数摘要** + **常驻「+ 新建预设」** + **预设编辑面板**
> 状态：代码完成、构建 + 单测真跑通过 ｜ ⚠ **未真机验证**（见 §7）
> 改动文件：`PresetDetail.kt`（+167 行纯逻辑）、`EditorScreen.kt`（UI）、`PresetPanelUiTest.kt`（新建）、本报告

---

## 0. 一句话

预设面板不再让用户猜哪里能点：**列表顶部常驻「+ 新建预设」**；**每行右侧一个「⋯」**打开
`套用 / 查看配置 / 编辑 / 重命名 / 复制 / 删除`（内置预设的后四项**置灰但可见** + 一句
"内置预设只读"）；**每行显示这条预设包含的参数摘要**；**「⋯」→「编辑」一步**进编辑面板
（名称 / 分组 + 完整参数清单 + 「从当前参数更新」/「保存名称 / 分组」/「删除」）。

---

## 1. 根因（架构师诊断的四条，逐条对应改法）

| # | 诊断 | 本卡改法 |
|---|---|---|
| 1 | 「保存当前」要先填名称才可点（`canCommit`）⇒ 用户不知道要先输入 | 顶部改成常驻「**+ 新建预设**」；点开进编辑面板并**预填默认名** `我的预设 N`（`defaultPresetName`，自动跳过已占用的编号）⇒ 保存按钮一路可点 |
| 2 | 进详情要点列表行的**名称区**，界面上没有任何提示 | ① 行尾加**显式「⋯」按钮**；② 菜单里加「**查看配置**」（对内置预设也恒可用 —— 只读详情是内置唯一能看的落点）；③ 顶部一行提示"点行尾「⋯」可套用 / 编辑 / 复制 / 删除" |
| 3 | **删除按钮对内置预设根本不渲染** ⇒ 用户手上只有内置三条 ⇒ 一个删除按钮都看不到 | 每行都渲染「⋯」；菜单里六项**全部渲染**，内置的四项**置灰**（`DisabledInk` + 不注册手势）+ 底部一句实话 `presetReadOnlyNote`。**不再隐藏任何操作** |
| 4 | 「更新为当前设置」在详情页底部 ⇒ 不点进去永远看不到 | 编辑面板一步可达（「⋯」→「编辑」），面板底部直接是「从当前参数更新」/「保存名称 / 分组」/「删除」 |

---

## 2. 改了什么

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/ui/PresetDetail.kt` | 改（+167 行） | **纯逻辑**（无 Compose / 无 Context ⇒ JVM 单测直接驱动）：`PresetAction` / `PresetMenuItem` / `presetMenuItems(p, paramsReady)`、`presetReadOnlyNote(p)`、`PRESET_SUMMARY_MAX` / `presetSummaryParts` / `presetSummaryText`、`PresetEditorButtons` / `presetEditorButtons(...)`、`defaultPresetName(existing)` |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | 改 | ① 新增 `renamePreset` / `copyPreset` 两条写路径；② `PresetPanel` 重做（新建按钮 + 菜单 + 编辑面板分支 + 摘要）；③ `PresetRow` 重做（摘要 + 「⋯」+ 来源标记）；④ 新增 `PresetParamList`（详情与编辑面板**共用**的参数清单渲染）、`PresetActionButton`、`PresetEditorPanel`；⑤ `PresetDetailPanel` 改用 `PresetParamList` |
| `app/src/test/java/com/filmkit/app/ui/PresetPanelUiTest.kt` | **新建** | 13 个 JVM 用例（菜单可用性 / 摘要 / 编辑面板按钮 / 默认名 / 删除后内置仍在 + 源码哨兵） |
| `docs/reports/ps1-preset-ui-REPORT.md` | **新建** | 本报告 |

**没碰**（`git status` 逐条核对）：`FrameRenderer.cpp` / `FrameRenderer.h`（另一张卡的光晕，工作区里
有它的在途改动）、`PhotoViewScreen.kt`（另一张卡的切图闪烁）、`CameraScreen.kt`、`CameraController.kt`、
`CameraPresetApply.kt`、`EditorExport.kt`（预设数据层）、`BuiltinPresets.kt`、`assets/presets/`。

### 2.1 唯一维护点（本卡新增的两条纪律）

- **摘要不另写格式化**：`presetSummaryParts` 的数据源是 `presetDetailSections`（U-6a 的
  槽位 → 分组 / 中文名 / 单位表）—— 摘要只决定"挑哪几项"，读数与详情页**逐字相同**
  （单测 `summaryReadoutsAreIdenticalToDetailRows` 钉住）。
- **内置只读仍是 `presetDeletable` 一个实现**：菜单四项 + 编辑面板的删除 + 行内「内置」标记
  全部由它判（单测 `menuAvailabilityFollowsPresetDeletableForEveryPreset` 逐条对拍），
  本卡没有第二处 `source == "builtin"` 比较。

### 2.2 列表刷新：五条写路径全部 `mergePresets(..., BuiltinPresets.load(context))`

`savePreset` / `updatePreset` / `renamePreset` / `copyPreset` / `deletePreset` —— **P-2c 踩过的坑**
（只赋 `PresetStore` 的返回值会让内置三条当场从面板上消失）。本卡新增的两条写路径（改名 / 复制）
沿用同一条口径，并加了一条**源码哨兵**测试：`EditorScreen.kt` 里每一条 `presets = …` 赋值都必须
带内置包（`mergePresets(..., BuiltinPresets.load(context))` 或 `BuiltinPresets.all(...)`），
漏一条就红 —— 这个坑不编译失败、不崩，只在界面上少三条。

---

## 3. 验收判据逐条对照

| # | 判据 | 落点 | 单测 |
|---|---|---|---|
| 1 | 「+ 新建预设」常驻可见；点开能填名称并保存 | `EditorScreen.kt` `PresetPanel` 的顶部 Row（`"+ 新建预设"`，实心 accent，`onTap = { openNew() }`）；`openNew()` 预填 `defaultPresetName(presets)`；保存走既有 `savePreset(name, group)` | `defaultNameIsPrefilledAndNeverCollides`、`newStateOffersSaveNewOnly` |
| 2 | 每行有「⋯」；内置受限项**置灰可见** + 一句实话 | `PresetRow` 行尾 `"⋯"`（ChipBg + 描边，**所有行都渲染**）；`presetMenuItems` 六项全渲染、内置四项 `enabled=false`；`presetReadOnlyNote` = "内置预设只读：可套用 / 查看配置，不能编辑、重命名、复制或删除" | `builtinMenuShowsEveryItemButOnlyApplyAndViewAreEnabled`、`userMenuEnablesEveryItemWhenParamsReady`、`menuGatesOnlyTheActionsThatNeedCurrentParams` |
| 3 | 每行显示**参数摘要**（真实内置预设的实际输出） | `PresetRow` 第二行 = `presetSummaryText(p, lutLabel = LutLibrary.byId(...)?.label)` | `summaryOfLeicaMomentIsExact`（逐字，见 §4）、`summaryCoversEverySectionOfEveryBuiltin`、`ellipsisAppearsOnlyWhenTruncated` |
| 4 | 编辑面板**一步可达**（「⋯」→「编辑」），能改名称/分组 + 「从当前参数更新」 | 菜单 `PresetAction.EDIT` → `openEdit(p)` → `PresetEditorPanel`（名称 / 分组输入 + 完整参数清单 + 「从当前参数更新」`updatePreset` / 「保存名称 / 分组」`renamePreset` / 「删除」） | `editStateOffersUpdateRenameDelete` |
| 5 | 删除仍二次确认；内置不可删；删除后内置三条不消失 | 菜单 `DELETE` → `pendingDelete` → 既有 `AlertDialog`（"「X」将被删除，无法恢复。当前画面与参数不受影响。"）；`deletePreset` 走 `mergePresets(PresetStore.delete(...), BuiltinPresets.load(context))` | `deletingAUserPresetKeepsAllBuiltins`、`everyPresetListRefreshCarriesTheBuiltinPackage`、`builtinEditPanelIsReadOnlyWithTheSameNote` |
| 6 | 构建 + 单测真跑退出码 0 + 用例数；mutation 证据 | §5 / §6 | — |
| 7 | 本报告 | 本文件 | — |

### 3.1 保留的既有能力（逐条没回退）

- **E3-1a 套用**：菜单「套用」+ 行内实心「套用」按钮 → 同一个 `applyPreset`（未改一行）。
- **E3-1a 保存当前**：变成编辑面板新建态的「保存当前参数为新预设」→ 同一个 `savePreset`。
- **E3-1c 强度滑杆**：面板顶部原样保留（只在 `applied != null` 时出现）。
- **U-6a 只读详情**：`PresetDetailPanel` 保留（入口 = 点行内名称区 / 「⋯」→「查看配置」），
  参数清单的渲染抽成 `PresetParamList` 供详情与编辑面板共用（一份实现，两处不会漂移）。
- **P-2c 内置只读保护**：判据仍是 `presetDeletable`（唯一实现）。
- **删除二次确认**：同一个 `AlertDialog`（编辑面板里的「删除」也走它）。

---

## 4. 纯逻辑与单测（真实数据，不是手搓的"想象中的预设"）

### 4.1 「徕卡一瞬」的摘要逐字输出（`builtin.json` 实测）

```
对比度 -3 · 饱和度 · 中间调 +5 · 胶片曲线 · 肩部 红 0.34 · 颗粒 · 强度 20 · … · LUT leica_m9 30
```

- 17 项参数 ⇒ 必然带「…」；LUT 行在最后（强度 0.3 ⇒ 读数 30，与 LUT 强度滑杆同一口径）；
- 挑选策略 = **分组轮转**（每组先取第一项）：直接按槽位顺序取前 4 项会得到
  `对比度 / 饱和度 / 分离色调×2`（全挤在基础与色彩两组），用户最认的"颗粒 / 暗角"被截掉；
- UI 层查到中文名时 LUT 那项显示 `LUT 徕卡 M9 30`（纯逻辑层不碰 Context，label 由调用侧传）。

### 4.2 用例清单（`PresetPanelUiTest`，16 个）

| 分组 | 用例 | 钉住什么 |
|---|---|---|
| ① 菜单可用性 | `builtinMenuShowsEveryItemButOnlyApplyAndViewAreEnabled` | 内置六项全在（`size == 6`），只有套用 / 查看配置可点 + 只读实话 |
| | `userMenuEnablesEveryItemWhenParamsReady` | 用户预设六项全可用、无只读行 |
| | `menuGatesOnlyTheActionsThatNeedCurrentParams` | 首帧前只有 `套用 / 编辑 / 重命名` 灰（查看 / 复制 / 删除 不该跟着灰） |
| | `menuAvailabilityFollowsPresetDeletableForEveryPreset` | 内置三条 + 用户 + imported **逐条**对拍 `presetDeletable`（不许另写判据） |
| ② 摘要 | `summaryOfLeicaMomentIsExact` | 真实内置预设的**逐字**输出 + LUT 中文名替换 |
| | `summaryCoversEverySectionOfEveryBuiltin` | 三条内置的每个分组首项都出现在摘要里（轮转有效） |
| | `ellipsisAppearsOnlyWhenTruncated` | 「…」只在真截断时出现（2 / 4 / 5 项三个边界） |
| | `emptyPresetHasEmptySummary` | 空预设 ⇒ 空串（UI 显示"不含参数"） |
| | `summaryReadoutsAreIdenticalToDetailRows` | 摘要项与详情行**逐字相同**（格式漂移即红） |
| ③ 编辑面板按钮 | `newStateOffersSaveNewOnly` | 新建态只有「保存当前参数为新预设」（空名 / 参数未就绪 ⇒ 置灰） |
| | `editStateOffersUpdateRenameDelete` | 编辑态三个按钮 + 各自的置灰条件 |
| | `builtinEditPanelIsReadOnlyWithTheSameNote` | 内置面板删除置灰 + 与菜单同一份只读文案 |
| ④ 默认名 | `defaultNameIsPrefilledAndNeverCollides` | `我的预设 N` 跳过占用编号；预填名让保存可点 |
| ⑤ 回归哨兵 | `deletingAUserPresetKeepsAllBuiltins` | 删一条用户预设后内置三条仍在（顺序也钉住） |
| | `everyPresetListRefreshCarriesTheBuiltinPackage` | **源码哨兵**：每条 `presets = …` 都带内置包（≥6 条） |
| ⑥ 不回退 | `userPresetSourceSemanticsUnchanged` | 用户预设仍可删、六项全可用 |

---

## 5. 构建与单测（真跑）

命令（工作区根目录）：

```bash
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

（待填：实际输出 / 退出码 / 用例数 / actionable tasks）

### 5.1 隔离副本上的对照实验（证据：本卡的改动 = 零回归）

工作区里同时有**另一张卡**的在途改动（`FrameRenderer.cpp/.h` 的光晕、`PhotoViewScreen.kt` 的
切图闪烁 + 它的新测试 `PhotoViewPreviewTest`），而另一张卡正在并发跑 gradle —— 同一 `app/build`
并发构建会互相写坏（实测：`Failed to store cache entry … shrunk-classpath-snapshot.bin`）。
为了在不干扰对方的前提下拿到可解释的数据，本卡在 `/tmp/fk-ps1` 做了一份**源码副本**（rsync，
排除 `build/`），跑了两组对照：

| 组 | 内容 | 结果 |
|---|---|---|
| ① 副本 = 工作区（含另一张卡的在途改动 + 本卡改动） | `:app:testDebugUnitTest -x buildFilmEngine` | **476 tests, 3 failed, 2 skipped** —— 3 个失败全部来自另一张卡的在途文件：`CameraStyleWhitelistTest.engineCameraPathMatchesWhitelist` / `EditorStyleWhitelistTest.scriptWhitelistMatchesEngineReads`（都解析 `FrameRenderer.cpp`）+ `PhotoViewPreviewTest.previewTierSitsExactlyOneSamplingStepBelowTheTarget`（它自己的新测试） |
| ② 副本里把**另一张卡的文件还原成 HEAD**（`FrameRenderer.cpp/.h`、`PhotoViewScreen.kt`，并移除它新增的测试） | 同上 | **471 tests, 0 failed, 2 skipped（44 个测试类）** |

②的 471 = **基线 455 + 本卡新增 16** ⇒ 本卡对既有用例**零回归**；①的 3 个失败与本卡改动无关
（都指向另一张卡正在写的文件）。

> `-x buildFilmEngine` 的理由：JVM 单测不需要 native 产物（`.so` 已在 `app/build` 里），
> 而另一张卡当时正把 `FrameRenderer.cpp` 改到**链接不过**（`undefined symbol:
> filmkit::FrameRenderer::releaseHaloTargets()`）—— 那与预设 UI 无关，却会挡住整条构建链。

### 5.2 工作区完整命令（`assembleDebug` + 全量单测）

（待填）

---

## 6. mutation 自检（3 项，全部在副本里做，改完即还原）

每一项都是"把本卡修掉的那个毛病**改回去**"，看测试是否真的会喊 —— 在 `/tmp/fk-ps1` 上执行
（跑完 `diff -q` 确认副本与工作区逐字节一致，且回绿）。

| # | mutation（改回旧行为） | 结果 | 说明 |
|---|---|---|---|
| A | `presetMenuItems` 末尾加 `.filter { it.enabled }` = **把置灰项藏起来**（正是本次投诉的根因） | `builtinMenuShowsEveryItemButOnlyApplyAndViewAreEnabled` + `menuAvailabilityFollowsPresetDeletableForEveryPreset` + `menuGatesOnlyTheActionsThatNeedCurrentParams` **3 条红** | "隐藏"这条路被堵死：谁再把不可用项过滤掉，测试立刻报出来 |
| B | `presetSummaryParts` 的**分组轮转**换成"按槽位顺序取前 N 项" | `summaryOfLeicaMomentIsExact` + `summaryCoversEverySectionOfEveryBuiltin` **2 条红** | 摘要必须覆盖到各分组（否则"颗粒 / 暗角"永远进不了那 4 个位置） |
| C | `deletePreset` 里 `mergePresets(PresetStore.delete(...), BuiltinPresets.load(context))` → 只赋 `PresetStore.delete(...)`（**P-2c 原坑**） | `everyPresetListRefreshCarriesTheBuiltinPackage` **1 条红** | ⚠ 注意：`deletingAUserPresetKeepsAllBuiltins`（纯逻辑那条）**不会红** —— 它测的是 `mergePresets` 的语义，抓不到"调用侧忘了 merge"。这就是源码哨兵必须存在的理由 |

回滚后副本复跑：`BUILD SUCCESSFUL`（16/16 通过）。

---

## 7. 未做 / 未验证 / 取舍

1. **不做"在预设面板里逐项拖滑杆"**（架构师已划定边界，本卡遵守）。理由与代价：预设面板嵌在
   预览区与底栏之间（限高 168~208dp），要塞下 20+ 条滑杆只有两条路 —— ① 让面板吃掉整屏预览
   （违背 E1-4 起"面板不是覆盖式浮层"的约束：暗角最明显的地方是四角，浮层挡住底边等于让用户
   盲调）；② 给每条滑杆做"迷你版"读数与量程（ADR-12 明确禁止的假滑杆）。正确做法就是本卡
   实现的：在**参数面板**调好 → 回预设面板点「从当前参数更新」。
2. **「编辑」与「重命名」是同一个落点**（都打开编辑面板）：改名的实现只该有一处，
   不另做一个"只改名的弹窗"（那是第二份改名逻辑 + 第二个维护点）。用户在菜单里点哪个都会
   看到名称输入框。
3. **复制内置预设被置灰**（架构师口径：内置整条路径只读）。技术上可行（产出一条用户预设副本），
   若后续要放开，只需把 `presetMenuItems` 里 `COPY` 的判据从 `presetDeletable(p)` 改成 `true`
   —— 一行，且单测会立刻指出该改哪。
4. **未真机验证**：本次只做了 JVM 单测 + 编译。UI 层面的下列事项需真机确认：
   ① 「⋯」菜单在 6 项 + 只读说明下的高度是否溢出（`AlertDialog` 的 `text` 区可滚动，但视觉密度未知）；
   ② 行内摘要 2 行 + 名称行 + 分组在窄屏（360dp）上的换行 / 省略表现；
   ③ 编辑面板底部三个中文按钮的横向滚动是否顺手；
   ④ 列表行从 2 行变 3 行后，限高 168dp 内可见的行数是否够用。
5. **本卡的 UI 交互（点「⋯」→ 选「编辑」）没有自动化测试**：Compose UI 测试未引入本项目，
   本卡不为它引入新依赖（ponytail：一行能做的事不引框架）。可测的部分（菜单可用性 / 摘要文案 /
   按钮差异 / 列表刷新口径）全部抽成纯逻辑并由 JVM 单测覆盖 —— 这也是本卡把这三件事抽函数的
   直接原因。
