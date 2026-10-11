# PS-2b 相册侧预设"只读 + 套用 + 新增 + 删除" —— 实现报告

> 卡号 PS-2b ｜ 用户原话（2026-10-11，两条）：
> ① "预设的配置/新增/编辑/删除**我指的是拍照界面的预设**。这个**不能让用户在相册里面改这些配置**。"
> ② 澄清："**编辑器可以保存当前配置为预设，不能修改已保存的预设，可以删除**"
> 交付物：编辑器预设面板**撤除"修改已保存的预设"的全部路径**（编辑 / 重命名 / 复制 / 更新为当前设置），
> **保留** 看配置（只读详情）、套用 + 强度、新增（保存当前为新预设）、删除（二次确认 + 内置只读），
> 并加一句"改预设请到拍照界面"的引导。
> 状态：代码完成、构建 + 单测真跑通过（§7）｜ ⚠ **未真机验证**（§9）
> 改动文件：`EditorScreen.kt`（UI + 纯逻辑）、`PresetPanelUiTest.kt`（按新口径重写）、本报告
> **未碰**：`CameraScreen.kt`（PS-2a 在做）、`PhotoViewScreen.kt`（PV-1 在做）、`FrameRenderer.cpp/.h`（光晕卡在做）、
> `EditorExport.kt` / `BuiltinPresets.kt` / `assets/presets/`（预设数据层）、`PresetDetail.kt`（PS-1 的纯逻辑**原样保留**给 PS-2a 复用）

---

## 0. 一句话

编辑器（相册侧）预设面板的动作面**收窄到四项**：**看配置**（只读详情 + 行内参数摘要）、**套用**（含强度滑杆）、
**新增**（「+ 新建预设」→ 保存当前参数为**新 id** 的预设）、**删除**（二次确认，内置置灰）。
"改已保存的预设"这一类（`编辑` / `重命名` / `复制` / `更新为当前设置`）**在编辑器侧一条路径都不剩** ——
面板上常驻一句"新增 / 删除预设在这里；修改已保存的预设请到拍照界面"告诉用户去哪改。

---

## 1. 规则表（用户 2026-10-11 拍板，本卡的实现口径）

| 操作 | 拍照界面（PS-2a） | **编辑器（本卡）** | 本卡证据 |
|---|---|---|---|
| 看配置（只读详情 + 摘要） | ✅ | ✅ 保留 | `PresetDetailPanel`（`EditorScreen.kt:3957`）、`PresetParamList`（:4067）、`presetSummaryText`（`PresetDetail.kt`） |
| 套用（含 LUT 降级 + 强度滑杆） | ✅ | ✅ 保留 | `applyPreset`（:832）、`onPresetAmountRelease`（:911）、`PRESET_AMOUNT_UI_RANGE` |
| **新增**（保存当前为新预设） | ✅ | ✅ 保留 | `savePreset`（:778）、`PresetNewPanel`（:4201）、「+ 新建预设」 |
| **修改已保存的预设** | ✅ | ❌ **移除** | §2 逐项；`editorPresetActions`（:4565）里根本没有这些动作 |
| **删除** | ✅ | ✅ 保留（二次确认） | `deletePreset`（:935）、`pendingDelete` 对话框（:3731） |
| 底部导航「预设」入口 | — | ✅ 保留 | `EditorTabBar(onPreset = …)` / `togglePresets`（:741） |

---

## 2. 撤掉了什么（逐项 + 代码引用）

| # | 撤除的东西 | 原来的位置 | 现状 |
|---|---|---|---|
| 1 | `updatePreset`（U-6a「更新为当前设置」的落点） | 原 `EditorScreen.kt:939-971` | 函数已删；`editorSrc` 里 `fun updatePreset(` / `"更新为当前设置"` 计数 **0**（源码哨兵 `editorSourceHasNoModifyPresetPath`） |
| 2 | `renamePreset`（PS-1 的「保存名称 / 分组」） | 原 `EditorScreen.kt:973-995` | 函数已删；`fun renamePreset(` / `"保存名称 / 分组"` 计数 **0** |
| 3 | `copyPreset`（PS-1 的「复制」） | 原 `EditorScreen.kt:997-1021` | 函数已删；`fun copyPreset(` / `"复制"` 计数 **0**；`PRESET_SOURCE_USER` 这个 import 也随之删掉（它只服务复制） |
| 4 | 「⋯」菜单里的 `编辑` / `重命名` / `复制` 三项 | 原 `presetMenuItems(p, canSave)` 六项 | 菜单改由**编辑器自己的**动作清单渲染：`editorPresetActions(p, canSave)`（:4565）恰好 `套用 / 查看配置 / 删除`；菜单里只画 `VIEW` / `DELETE`（:3675） |
| 5 | 预设**编辑面板**（编辑态：更新 / 改名 / 删除三个按钮） | 原 `PresetEditorPanel`（`p: EditorPreset?` 双态） | 换成**只服务新增**的 `PresetNewPanel`（:4201）：名称 / 分组 + 将要保存的参数清单 + 「保存当前参数为新预设」/「取消」 |
| 6 | 详情页底部的「更新为当前设置」按钮 | 原 `PresetDetailPanel` 的 `onUpdate` | `onUpdate` 参数与按钮已删；详情只剩「套用」+「删除（仅用户预设）」（:3957 起） |
| 7 | 编辑器的 `openEdit` / `closeEditor` / `editorNew` / `editorId` 状态机 | 原 `PresetPanel` 内 | 全部删除，只剩一个 `newOpen: Boolean`（新增态） |

**为什么"改已保存预设"要整类撤掉而不是只藏入口**：用户的原话是"**不能**让用户在相册里面改这些配置"——
留一条隐蔽路径（比如"另存为新预设 + 覆盖同名"）等于把产品决策交给实现细节。所以本卡撤的是**实现**，
不是按钮：写预设库的入口现在只剩 `PresetStore.upsert`（新增，:793）与 `PresetStore.delete`（删除，:937）两处
（`grep -n "PresetStore\." EditorScreen.kt` 的全部输出见 §7.2）。

---

## 3. 保留了什么（逐项 + 代码引用）

1. **套用（看效果的核心）**：`applyPreset`（:832）三个分支（无 LUT / LUT 资产缺失降级 / 正常）逐字未动；
   强度滑杆 `dragPresetAmount` / `onPresetAmountRelease`（:911）与 `appliedPreset` / `presetBase` / `presetAmount`
   状态一位没改。行内「套用」按钮的可用性改为从动作清单取（`PresetRow`:3827）——
   **不可用时置灰而不是隐藏**（PS-1 的纪律：藏起来才是投诉的根因）。
2. **只读详情（看配置）**：`PresetDetailPanel`（:3957）仍是"点行内名称区 / 「⋯」→「查看配置」"两个入口，
   参数清单渲染 `PresetParamList`（:4067）与新建面板**共用同一份实现**；行内**参数摘要**
   （`presetSummaryText`）也保留 —— "这条预设包含什么"在列表上就能看见。
3. **新增**：「+ 新建预设」常驻列表顶部（PS-1 的形态保留），点开进 `PresetNewPanel`（:4201）：
   预填默认名 `defaultPresetName`（`PresetDetail.kt`）⇒ 保存按钮一路可点；参数清单 = 当前参数的稀疏集
   `presetSparseSlots`（与 `savePreset` 同一个函数 ⇒ 清单与落库内容不可能不一致）。
   `savePreset` **永远生成新 id**（:778-795）⇒ 这是"新增"与"修改"的分界，注释里写死。
4. **删除**：`deletePreset`（:935）+ 二次确认 `pendingDelete`（:3731）；内置预设的删除项**置灰**
   （判据仍是 `presetDeletable` —— P-2c 立的"内置只读唯一实现"，没有第二处 `source == "builtin"` 比较）
   + 菜单底部一句实话"内置预设只读：可套用 / 查看配置，不能删除"（:3705-3714）。
5. **底部导航「预设」入口**：`togglePresets`（:741）与 `EditorTabBar(presetActive = presetMode)` 未动。

---

## 4. 引导文案（唯一维护点）

`editorPresetManageHint()`（`EditorScreen.kt:4579`）：

```
新增 / 删除预设在这里；修改已保存的预设请到拍照界面
```

渲染两处（同一份字符串，不可能一处改了另一处没改）：
- 列表顶部「+ 新建预设」右侧常驻一行（:3557）；
- 「⋯」菜单底部恒定一行（:3716）—— 菜单正是用户找"编辑"的地方，把"去哪改"放在他正在找的位置。

单测 `manageHintPointsToCameraScreen` 钉住三件事：指到**拍照界面**、点明是**修改**这一类在那边、
同时说清**新增 / 删除在这里**能做。

---

## 5. 查看页「胶片」面板核查（任务第 2 节：**确认，未改动**）

结论：**`PhotoViewScreen.kt` 的 `FilmPresetSheet`（P-3）本来就没有"改预设库"的操作，无需改动，本卡一行未碰。**

证据（代码引用）：

| 位置 | 内容 |
|---|---|
| `PhotoViewScreen.kt:739-746` | 打开面板只传 `presets` / `onPick` / `onDismiss`；`onPick` 的动作是 `onEditWithPreset(it, p.id)`（**带 id 进编辑器**，不写库） |
| `PhotoViewScreen.kt:901-905` | `FilmPresetSheet(presets, onPick, onDismiss)` —— **签名里没有任何写操作回调**（没有 onDelete / onSave / onRename 之类） |
| `PhotoViewScreen.kt:961` | 列表项只有 `FilmPresetRow(p) { onPick(p) }` |
| `PhotoViewScreen.kt:977-982` | 行本身只有 `clickable(onClick = onPick)` + 名称/分组/作用域提示文本 |
| `grep -n "PresetStore" PhotoViewScreen.kt` | 只命中 **717 行的注释**（"直接用 PresetStore.load 会让内置包在界面上消失"）；**无 `PresetStore.upsert` / `PresetStore.delete` 调用** |

⇒ 查看页「胶片」= **只读列表 + 选一条直接进编辑器**，与用户口径一致（**未发现需要另派卡的问题**）。

> 行号基于 2026-10-11 的工作区版本 —— `PhotoViewScreen.kt` 当时正被 **PV-1（切图闪烁）**改动中，
> 本卡没有碰它（`git diff` 里它的改动全部属于 PV-1）。

---

## 6. 测试（`PresetPanelUiTest.kt` 按新口径重写）

PS-1 的用例没有"删掉了事"，而是**逐条翻译成新口径的护栏**：

| 组 | 用例 | 盯什么 |
|---|---|---|
| ① 编辑器动作面 | `editorActionsAreExactlyApplyViewDelete` | 清单**恰好** `套用 / 查看配置 / 删除`（加回任何一项 ⇒ 红） |
| | `editorActionsHaveNoModifyActionForAnyPreset` | `EDIT / RENAME / COPY` 对**内置 / 用户 / 导入**三种来源、参数就绪与未就绪两种状态**都不存在**；动作面恒为 3 项 |
| | `editorApplyIsGatedByParamsReadyButViewAndDeleteAreNot` | 只有套用跟参数就绪联动 |
| | `editorDeleteFollowsPresetDeletable` | 删除可用性逐条对拍 `presetDeletable` |
| ② 源码哨兵 | `editorSourceHasNoModifyPresetPath` | `fun updatePreset(` / `fun renamePreset(` / `fun copyPreset(` / `PresetEditorPanel(` / `onUpdate =` / `onRename =` / `onCopy =` / `"更新为当前设置"` / `"从当前参数更新"` / `"保存名称 / 分组"` / `"编辑预设"` / `"重命名"` / `"复制"` 一律**不许出现** |
| | `editorSourceStillHasCreateDeleteApplyAndReadOnlyDetail` | **正向**：新增（`savePreset` + `PresetStore.upsert` + `PresetNewPanel` + 「+ 新建预设」）、删除（`deletePreset` + `PresetStore.delete` + 确认框）、套用（`applyPreset` + 强度）、只读详情（`PresetDetailPanel` + `PresetParamList`）**都必须在** |
| | `everyPresetListRefreshCarriesTheBuiltinPackage` | P-2c 的坑：每条 `presets = …` 必须带内置包（写路径从 5 条收窄到 2 条 ⇒ 下限从 6 改成 3） |
| ③ 新增 | `newPresetPanelOffersSaveNewOnly` / `defaultNameIsPrefilledAndNeverCollides` | 新建态只有"保存"，默认名预填且不撞名 |
| ④ 删除 | `deletingAUserPresetKeepsAllBuiltins` / `userPresetSourceSemanticsUnchanged` | 删一条用户预设后内置三条仍在；用户侧语义未回退 |
| ⑤ 看配置 | `summaryOfLeicaMomentIsExact` / `summaryCoversEverySectionOfEveryBuiltin` / `ellipsisAppearsOnlyWhenTruncated` / `emptyPresetHasEmptySummary` / `summaryReadoutsAreIdenticalToDetailRows` | 摘要与详情读数逐字一致（唯一维护点 `presetDetailSections`） |
| ⑥ 引导 | `manageHintPointsToCameraScreen` | 文案指到拍照界面 + 说清新增/删除在这里 + 界面真的渲染了它 |
| ⑦ 拍照界面复用 | `cameraMenuStillHasTheModifyActionsAndBuiltinOnesAreGreyedOut` / `cameraEditPanelButtonsCoverUpdateRenameDelete` | `presetMenuItems` / `presetEditorButtons` / `presetReadOnlyNote` 是**拍照界面**（PS-2a）要复用的纯逻辑（PS-1 加的），原样保留覆盖；注释里写明"不属于编辑器侧" |

---

## 7. 构建 + 单测（真跑）

### 7.1 命令与结果

```
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

真跑输出（本卡实测，`--rerun-tasks` ⇒ 没有 FROM-CACHE 假绿）：

```
BUILD SUCCESSFUL in 20s
46 actionable tasks: 46 executed
TESTSUITES=46  TESTS=496  FAILURES=0  ERRORS=0  SKIPPED=0
```

- **退出码 0**（`assembleDebug` + `testDebugUnitTest` 都过），**0 失败 / 0 错误 / 0 跳过**；
- 用例数 496：架构师给的基线是 455；本卡交付的 `PresetPanelUiTest` 现在 **19 条**（PS-1 的 13 条按新口径重写 / 合并 + 新增 6 条编辑器侧护栏），其余增量来自**同一工作区**里其它在途卡（PS-1 / PS-2a / PV-1）新增的用例 —— 工作区共享，数字含它们的贡献；
- 注意：本次运行的工作区快照里还有 PS-1 / PS-2a / PV-1 / 光晕卡的在途改动（`git status` 见 §7.4），全仓编译 + 全部单测一起过的。

### 7.2 写预设库的入口清单（`grep -n "PresetStore\." EditorScreen.kt`）

```
746:            presets = BuiltinPresets.all(context, prefs)                       ← 读（打开面板）
793:        presets = mergePresets(PresetStore.upsert(prefs, p), BuiltinPresets.load(context))   ← 新增
937:        presets = mergePresets(PresetStore.delete(prefs, p.id), BuiltinPresets.load(context)) ← 删除
```

（其余命中全是注释。）⇒ 编辑器侧**没有第三条**写路径。

### 7.3 mutation 自检（哨兵必须能红）

做法：把"改已保存预设"的入口**加回去**，看哨兵是否当场红；跑完把文件按 md5 原样恢复。

注入的两处（`EditorScreen.kt`）：

```kotlin
// M1：动作清单里加回一项 —— 等价于「⋯」菜单里多出一个"更新为当前设置"
PresetMenuItem(PresetAction.EDIT, "更新为当前设置", true),

// M2：把 updatePreset 的实现加回来（写回已有预设）
fun updatePreset(p: EditorPreset) {
    presets = mergePresets(PresetStore.upsert(prefs, p.copy()), BuiltinPresets.load(context))
}
```

结果：

```
MUT_EXIT=1
BUILD FAILED in 13s
MUTATION-RUN tests=19 failures=3 errors=0
  RED: editorSourceHasNoModifyPresetPath        | 编辑器侧不许出现「fun updatePreset(」
  RED: editorActionsAreExactlyApplyViewDelete   | expected:<[APPLY, VIEW, DELETE]> but was:<[EDIT, APPLY, VIEW, DELETE]>
  RED: editorActionsHaveNoModifyActionForAnyPreset | 编辑器侧不许有「EDIT」这类「改已保存预设」的动作：徕卡一瞬
restored md5=eea9bb7ec096b2c1dcf3b7e39746cfe8 (before=eea9bb7ec096b2c1dcf3b7e39746cfe8)
AFTER_EXIT=0
AFTER-RESTORE tests=19 failures=0 errors=0
```

⇒ 三条哨兵**各自独立命中**（清单精确匹配 / 清单禁止项 / 源码标识符），恢复后 md5 一致、复跑全绿。
**未 commit，改动留在工作区。**

### 7.4 本卡改动的文件（`git status` 核对）

```
 M app/src/main/java/com/filmkit/app/ui/EditorScreen.kt      ← 本卡（叠加在 PS-1 的在途改动上）
 M app/src/test/java/com/filmkit/app/ui/PresetPanelUiTest.kt ← 本卡（PS-1 新建的文件，按新口径重写）
?? docs/reports/ps2b-viewer-readonly-REPORT.md               ← 本卡
```

其余在途改动（`CameraScreen.kt` / `PhotoViewScreen.kt` / `EditorEngineSession.kt` / `FrameRenderer.cpp/.h` /
`PresetDetail.kt` / `tools/*.py` / `docs/reports/ps1-*.md` 等）**是别的卡的，本卡一行未碰**
（`PresetDetail.kt` 保持 PS-1 原样 —— 它加的纯逻辑留给 PS-2a 复用）。

---

## 8. 遗留 / 风险 / 给架构师的提案

### 8.1 提案（用户明确决策，本卡不自行保留）：编辑器里调好的后期参数想"存进已有预设"怎么办？

**现状缺口**：用户在编辑器里调出一套满意的曲线 / 色彩，想把它**固化到某条已有预设**上 —— 现在做不到
（这正是 U-6a「更新为当前设置」原来的用途，用户 2026-10-11 明确要求撤掉）。三个可选方案：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A（本卡现状，推荐维持）** | 编辑器「+ 新建预设」存成**新**预设；旧的那条自己去删 | 两步操作；预设库中途会短暂多一条；命名负担 |
| B（跨页通道） | 把编辑器参数传给拍照界面，在那边"更新为当前设置" | 语义错位：编辑器参数是"这张照片的后期"，拍照界面参数是"取景的起点"；要新增跨页状态通道（路由 + 序列化），且用户仍要跳页 |
| C（覆盖式另存） | 编辑器允许"另存并覆盖同名预设" | 等于把"修改已保存预设"换个名字放回编辑器 ⇒ **违背用户口径**，不做 |

**本卡的判断**：维持 A。理由：编辑器是"单张照片的后期"，把"这一张的修补"直接写成"以后每张的起点"
风险更高（误覆盖不可撤销）；"另存为新预设 + 删旧的"是显式两步，语义清楚。若用户实际用起来觉得烦，
再按 B 立项（代价如上）。

### 8.2 PS-1 报告已过时的地方（**未改它的文件**，供架构师处理）

- `docs/reports/ps1-preset-ui-REPORT.md` §0 / §2.2 描述的"五条写路径（保存 / 更新 / 改名 / 复制 / 删除）"、
  "编辑面板（名称 / 分组 + 从当前参数更新 / 保存名称 / 分组 / 删除）"**已不成立**：现在编辑器侧只剩
  **新增 + 删除**两条写路径，编辑面板只剩**新建态**。
- 它的验收结论（"新增 / 编辑 / 删除都显式可见"）与用户 2026-10-11 的第二条澄清冲突 —— 冲突以用户口径为准。

### 8.3 观察（PS-2a 需要注意，本卡未动）

`presetEditorButtons(isNew = false, preset = 内置预设, paramsReady = true, name = "x")` 的纯逻辑返回值是
`updateFromCurrent = true` / `saveMeta = true`（只看 `paramsReady` / `name`），**没有**跟 `presetDeletable` 对齐
（只有 `delete` 对齐了）。PS-1 的 UI 靠别处兜住这个差异。PS-2a 若复用这个函数做拍照界面的编辑面板，
需要自己决定"内置预设能不能改配方"（本卡不改这个函数 —— 它是 PS-1 的纯逻辑，且不在本卡范围内）。

### 8.4 未做（本卡边界）

- **不做**"编辑器 → 拍照界面"的跳转按钮（只给文案；跳转涉及路由，是别的卡的领域）。
- **不做**逐项编辑预设参数（架构师 2026-10-11 已划定：调参是参数面板的职责）。
- **不改** `PresetDetail.kt`：PS-1 加的纯逻辑（`presetMenuItems` / `presetEditorButtons` / `defaultPresetName` /
  `presetSummaryParts` / `presetReadOnlyNote`）**原样保留**给 PS-2a 复用；编辑器侧改用自己那份
  `editorPresetActions`，两套清单职责在注释里写清（拍照界面 = 完整动作集，编辑器 = 只读 + 套用 + 删除）。
  **交叉验证**：PS-2a 确实复用了它们（`CameraPresetPanel.kt:62` 的注释 + `:388` 的
  `presetMenuItems(p, paramsReady = true)`）⇒ "保留而不是删掉"这个决定是对的。

---

## 9. ⚠ 未真机验证（明确声明）

本卡的验证到**构建 + JVM 单测**为止：

- ✅ 编译通过（`assembleDebug`）、单测真跑 0 失败（§7）；
- ✅ 纯逻辑护栏 + 源码哨兵覆盖了"编辑器侧没有改预设路径"；
- ❌ **没有在真机 / 模拟器上点过**：没有验证"面板在窄屏上的换行 / 引导文案是否被截断 /
  新建面板与详情面板的返回路径 / 删除二次确认的观感"这类只有真机能看出来的问题。
- ❌ **没有验证**"编辑器新增的预设能被拍照界面看到"这类跨页行为（数据层未动，理论上成立，但未实测）。

真机验证建议清单（给架构师排卡）：
1. 编辑器 → 底栏「预设」→ 确认面板上没有 `编辑 / 重命名 / 复制 / 更新为当前设置` 任何字样；
2. 列表顶部引导文案在窄屏上是否完整（`maxLines = 2`）；
3. 「+ 新建预设」→ 保存 → 列表出现新预设 → 套用 → 强度滑杆生效；
4. 「⋯」→ 内置预设的「删除」是否置灰 + 那句实话是否显示；
5. 「⋯」→「查看配置」→ 参数清单与列表摘要读数一致；
6. 拍照界面（PS-2a 完成后）能看到编辑器新增的这条预设。
