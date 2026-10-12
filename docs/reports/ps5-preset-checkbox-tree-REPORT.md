# PS-5 · 预设参数区重做为「分组勾选树」

> 卡号 PS-5 ｜ 用户口径（2026-10-11，v0.11.19 真机）："预设面板交互逻辑太差，**全是滑杆**"。
> 规格来源 = `docs/reports/preset-panel-interaction-RESEARCH.md`（照它实现，本卡**不做新设计决策**）。
> 状态：**代码 + 单测 + 源码哨兵完成**；**未上真机**（§6 逐条声明）。

## 1. 改了什么（交互前后对比，文字）

| 位置 | 之前（PS-3） | 现在（PS-5） |
|---|---|---|
| 参数区形态 | 全量清单，**每一项点开都是一条滑杆**（行的唯一交互 = 点行展开滑杆） | **分组勾选树**：行 = `☑/☐ + 参数名 + 值/「—」`。**滑杆只出现在"点开的那一行"里**（一次一项、默认全收起） |
| 未包含的项 | 显示"未设置"，点开照样出滑杆 | 显示 **「—」**，**照样在树上**（不藏），点勾选框才加入 |
| 增删字段 | 只能靠展开区里的「移除该项」删；加入必须"点开滑杆再拖" | **勾选框 = 加入 / 移除**（唯一手段，四家一致）；「不包含此项」保留为同义冗余 |
| 组头 | `▾ 组名 4/9`（点了只能折叠） | `▾ 组名 ▣ 4/9`：**三态框**（☑/▣/☐，I5）+ 点它 = **整组勾选**（半勾点一下 = 全勾） |
| 批量 | 无 | `已包含 12 · 未包含 29    [全选] [全不选]`（= LrC `Check All / Check None`） |
| 拍照不生效项 | 灰显 + 原因，**不可选** | 灰显 + 原因，**可勾选**（能进/能出预设，只是值不可调；= LrC 灰显 `Masking` 同型，I6） |
| 说明文案 | "点一项开始调整 · 拖动即时生效（未保存不写入预设）" | 追加一句实话："保存时值等于基准的项自动不写入 · 未勾选的项套用时保持不动" |

- **列表页 0 改动**（点行套用 / 选中态 / 摘要 / ⋯ 菜单 / 长按进详情 —— 调研判定已符合业界）。
- **LUT 强度仍留在 LUT tab**（调研 §1 表 2 记录的有意偏离：列表页"再点展开强度"与 LUT tab 重复）。
- 树的展示范围 = **拍照链路读的全部 41 槽**（含 6 个没有滑杆的）+ 这条预设包含的其它槽。取消勾选**不会让行消失**（否则 `[全不选]` 一次就把树清空，用户把自己锁在门外）。

**顺手修掉一个 PS-3 遗留的假控件**（本卡实现过程中被"取消勾选 = 移除"这条路径逼出来的）：
取消勾选**曝光**行 / 点 `[全不选]` 时，`cameraPresetParams` 已把数组 `[0]` 打回基线，但下发的
`evOffset` 仍取预设原值 ⇒ `setFilmParams` 会把 `[0]` 重新写成 `base + 用户 EV + 预设偏移` ⇒
**画面上曝光根本不变**（= 点了没反应，违反调研 §2.4 #2/#11）。现已让 `effectivePresetEvOffset`
接受 `removed`（默认空集 ⇒ 其它调用点一位不变），`previewCameraParams` 传进去 ⇒ 两条口径同时回
基线。单测 `uncheckingTheExposureRowBringsTheOffsetBackToZero` + 接线哨兵
`previewPathPassesRemovalToTheEvOffset` 钉住。

## 2. 代码落点

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt` | 新增纯逻辑：`CheckState` / `sectionCheckState` / `checkBoxText` / `triCheckText` / `cameraTreeSlots` / `showsSliderRow` / `checkSlots` / `uncheckSlots` / 私有 `checkedSlots`；`removeSlot` 收缩为 `uncheckSlots` 的单槽特例（**实现只有一份**）；`CamPresetParamList` **删除** → `CamParamCheckTree` / `CheckAllRow` / `SectionTriCheck` / `CamParamRow` / `CamParamExpand`；`CameraPresetEditorPanel` 的回调 `onRemoveSlot: (Int) -> Unit` → `onChecked: (List<Int>, Boolean) -> Unit`（单项/整组/全选三条路径共用 ⇒ **一次提交**） |
| `app/src/main/java/com/filmkit/app/ui/PresetDetail.kt` | `LUT_STRENGTH_SLOT` 由 `private` 提为 `internal`（勾选树也要用它排除 `[34]` —— 一份常量两处用，不是第二个 34） |
| `app/src/main/java/com/filmkit/app/camera/CameraPresetApply.kt` | `effectivePresetEvOffset` 增加第三个参数 `removed: Set<Int> = emptySet()`（默认值 ⇒ 既有调用点行为不变）：用户移除曝光项 ⇒ 偏移 = 0（见 §1 末尾那段） |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | `previewCameraParams` 把 `removed` 一起交给偏移计算（一行；否则取消勾选曝光 = 假控件） |
| **复用不改** | `ParamControls.kt` 的 `EditorSlider` / `CAMERA_TUNABLE_SLOT_PARAM`；`PresetDetail.kt` 的 `cameraParamSections` / `CameraParamRow`；`afterEdit` / `forPreset` / `cameraPresetSlotsFor`；`CameraPresetTab` 的 `DragHolder`（拖动不写 state） |

## 3. 没回退的既有能力

- **拖动实时下发取景**：`onEditParam` → `afterEdit` → `drag.value`（普通对象，**不写 state**）→ `onPreview` 直通；松手 `onEditFinished` → `onOverrides` 一次提交。代码一位未动。
- **内置可改 / 只禁删 / 恢复默认**：`presetEditorButtons` / `presetDeletable` / `hasOverride` / `onRestore` 一位未动。
- **「保存到预设」/「另存为新预设」/「从当前相机参数更新」/「保存名称·分组」**：一位未动。
- **`[0]` 曝光偏移口径**：勾选写的值来自 `cameraPresetParams(...)[0]`（= 预设自己的偏移量），保存仍走 `cameraPresetSlotsFor` ⇒ 用户 EV / 机型基准曝光仍进不了预设。
- **不做假控件**：没有滑杆的项行体不注册手势（`enabled = param != null`），但**勾选框照样可点**（点了有反应：加入/移除）。

## 4. 判据（机器可判）

### 4.1 两条核心判据（本卡要求）

| # | 判据 | 逻辑侧（JVM 单测） | 源码侧（哨兵） |
|---|---|---|---|
| ① | **进入详情首屏可见滑杆数 = 0** | `CameraPresetCheckTreeTest.firstScreenOfTheDetailPanelShowsZeroSliders`：3 条内置 + 1 条用户预设 × 树上每一个槽，`showsSliderRow(..., expanded = false)` 的计数**恒为 0**；反面断言"点开一项就出现" | `CameraPresetPanelUiTest.firstScreenOfTheDetailPanelHasNoSlider`：正则钉住 `var openKey by remember(presetId) { mutableStateOf<String?>(null) }` |
| ② | **未勾选的行不渲染 `EditorSlider`** | `uncheckedRowsNeverRenderASlider`：树上每个槽 × {展开,收起} 断言 `checked = false ⇒ false`；没有滑杆的项恒 false | 全文件 `EditorSlider(` **只出现 1 次**，且它在 `CamParamExpand` 内；`CamParamRow` 里必须 `if (showsSliderRow(` 守卫、守卫体内才调 `CamParamExpand(` |

### 4.2 其余单测（`app/src/test/java/com/filmkit/app/ui/CameraPresetCheckTreeTest.kt`，共 16 条）

`sectionCheckStateIsThreeStateAndIgnoresForeignSlots`（0/n·k/n·n/n + 组外槽不计数 + 三态字面）/
`tappingASectionTriStateNeverStaysInconsistent`（I5）/
`checkingASlotAddsItWithoutTouchingThePicture`（§2.4 #1：**画面一位不动**）/
`uncheckingASlotBringsThePictureBackToBaseline`（#2）/
`recheckingASlotCancelsTheRemoval`/
`slotsWithoutASliderCanStillBeCheckedIn`（I6）/
`checkingAWholeSectionYieldsOneOverrideSet`（#8：一次提交）/
`checkNoneBringsEverySlotBackToBaseline`（§2.7 判据 2：画面 == **标准基线**）/
`checkAllIncludesEveryRowOnTheTree`（#10）/
`valuesEqualToBaselineAreSparsifiedAwayOnSave`（§2.7 判据 3 / I3）/
`uncheckingTheExposureRowBringsTheOffsetBackToZero`（取消勾选曝光 ⇒ 数组与偏移同时回 0）/
`previewPathPassesRemovalToTheEvOffset`（上一条的接线哨兵）/
`builtinCanBeEmptiedAndThenRestoredToFactory`（§2.7 判据 4）/
`uncheckedRowsNeverRenderASlider`（判据②）/
`firstScreenOfTheDetailPanelShowsZeroSliders`（判据①）/
`theTreeKeepsEveryRowAndNeverFakesUnsetValues`（全量 + 「—」不冒充当前相机值）

### 4.3 mutation 自检（4 处，红了之后已恢复；源码里 `MUTATION` 计数 = **0**）

| # | 注入 | 实得 |
|---|---|---|
| 1 | `showsSliderRow` 去掉 `checked &&` | ✅ 红：`uncheckedRowsNeverRenderASlider FAILED: [0] 未勾选却渲染了滑杆（= 又回到「全是滑杆」）` |
| 2 | `sectionCheckState` 砍掉 `SOME` 分支 | ✅ 红：`sectionCheckStateIsThreeStateAndIgnoresForeignSlots FAILED: 勾了一部分 ⇒ ▣ expected:<SOME> but was:<ALL>` |
| 3 | `openKey` 初值 `null` → `"ev"` | ⚠ 第一次**没红**（哨兵当时用 `contains("mutableStateOf<String?>(null)")`，被同文件的 `editorId` 命中 = **假绿**）⇒ 已把哨兵改成钉 `openKey` 那一行，重跑 ✅ 红：`firstScreenOfTheDetailPanelHasNoSlider FAILED` |
| 4 | `effectivePresetEvOffset` 忽略 `removed` | ✅ 红：`uncheckingTheExposureRowBringsTheOffsetBackToZero FAILED: 下发的偏移也必须回 0 expected:<0.0> but was:<0.5>` |

## 5. 口径变更（`CameraPresetPanelUiTest` 哨兵函数名同步 —— 逐条理由）

| 名字 | 变更 | 理由 |
|---|---|---|
| `cameraParamSections(` | **保留** | 勾选树的数据源没换（槽位级 + 分组 + 中文名/格式化的唯一维护点仍是 `DETAIL_META`）；换的是**控件形态**，不是数据 |
| `cameraParamAdjustabilityNote(` | **保留** | 不可调项的"一句实话"仍在树上渲染（I6 / ADR-12） |
| `CamParamCheckTree(` | 新增 | 勾选树本体（替换 `CamPresetParamList`） |
| `CamParamRow(` | 新增 | `☑/☐ + 名称 + 值/「—」`一行（PS-3 已有的 `CamPresetField` 是**文本输入框**，前缀同族易混，故用调研 §2.3 的命名） |
| `CamParamExpand(` | 新增 | 展开区；**全页唯一出现滑杆的地方**（判据②的源码锚点） |
| `SectionTriCheck(` | 新增 | 组头三态框（I5） |
| `CheckAllRow(` | 新增 | `[全选]/[全不选]`（= LrC `Check All/None`） |
| `cameraTreeSlots(` | 新增 | 展示哪些槽（全量 41 + 预设包含的其它；排除 `[34]`） |
| `sectionCheckState(` / `checkSlots(` / `uncheckSlots(` / `showsSliderRow(` | 新增 | 勾选口径的**纯逻辑**（JVM 可测） |
| `CamPresetParamList` | **移除** | 被 `CamParamCheckTree` 取代。这不是"藏能力"：新哨兵列表**比旧的更长**（18 个名字 vs 7 个） |

## 6. 未验证项 / 已知不完美（如实声明）

1. **没上真机**（本卡只有 JVM 单测 + 源码哨兵）：滑杆跟手度、勾选框热区（`13sp` 字 + 4dp 内边距 ≈ 24dp 触摸区）是否好点、41 行树的滚动高度、150dp 限高在窄屏上的观感 —— 全部待真机确认（§7）。
2. **I3 的可见后果**（有意与 LrC 不同，调研 §2.5 裁决）：勾一个"当前值恰好等于基准"的项 ⇒ 立刻 `☑`（**有反应**），但**保存后**该槽被稀疏化丢弃 ⇒ 再打开这条预设时它显示 `☐`。相机链路里"包含 + 值 = 基线"与"不包含"没有任何可观察差异，所以不是丢数据；但用户可能觉得"我勾的怎么没了" —— 已在界面上写了一句实话（"保存时值等于基准的项自动不写入"）。
3. **`[全选]` 的同一后果**：只有"当前与基准**有差异**的项"真正写进预设，其余保存时被稀疏化丢 —— 与 LrC `Check All` 的结果不同（LrC 存显式值快照）。这是稀疏子集模型的必然，不是 bug。
4. **`[全不选]` 会把内存态 `removed` 撑到 41 个槽**：保存时 `cameraPresetSlotsFor` 重新稀疏化 ⇒ **落盘结果正确**（单测 `checkNoneBringsEverySlotBackToBaseline` 钉住）；41 个槽的集合运算没有性能问题，但真机未实测。
5. **拍照链路不可调的项仍是灰显 + 没有滑杆**（"拍照生效 · 暂无滑杆" / "仅编辑器生效 · 拍照不变"）：给它们补滑杆是**既有欠账**（不是本卡引入），本卡只是让它们**能进/能出预设**。
6. **未做**（调研明确不做，逐条理由见调研 §1/§3）：参数树搜索、收藏、预设缩略图、darktable 的第二列 `reset` 复选框、列表页"再点一次出强度"、全局撤销。
7. **§1 末尾那条曝光修复只在 JVM 单测层面验证过**：`effectivePresetEvOffset(..., removed)` 是纯函数（单测钉住），接线由源码哨兵钉住；但"真机上取消勾选曝光行之后**取景里的曝光数值确实回 0**"没实测（`CameraController.setFilmParams` 内部还会被 prefs 覆盖层重写 `[0]`，本卡没有真机确认这条链路）—— 见 §7 第 9 步。

## 7. 真机验收步骤（10 步）

1. 拍照界面 → 底部「预设」tab → 长按任意一行（或 `⋯` → 编辑）进入详情。
2. **首屏**：一条滑杆都看不到；每行是 `☑/☐ + 参数名 + 值/「—」`。
3. 点「对比度」的**勾选框**（☐→☑）：值位从「—」变读数；画面**对比度立刻生效**（值 = 当前值）。
4. 点「对比度」**行体**：只这一行展开出滑杆；拖动 → 取景实时变；松手后**别处**（列表摘要）不变（未保存）。
5. 把滑杆**拖回基准**（+0）：该行自动变 `☐`、值位回「—」（I3 的可见反馈）。
6. 点「不包含此项」：行收起 + 变 `☐`（与勾选框同义）。
7. 点组头 `▣ 4/9` 的**三态框**：整组变 ☑（一次加完），画面按各行读数生效；再点一下 → 整组变 ☐（全部回基线）。
8. 点组头**标题 / 箭头**：只折叠展开该组（**不许**误删参数）。
9. 点 `[全不选]` → 全 `☐`、值位全「—」、画面回到"无预设覆盖"的相机参数；再点 `[全选]` → 全 `☑`；**树不会消失**（勾得回来）。
   - **顺带验曝光那条修复**：先把曝光行拖到 +0.5 并「保存到预设」，再取消勾选**曝光**行 ⇒ 取景曝光必须回 0（不是"看着没变"）。
10. 内置（「徕卡一瞬」）：改「基础调整」→「恢复默认」变可点 → 点它回出厂；「保存到预设」后回列表、重新进详情，勾选态 = 保存下来的稀疏集合；`⋯` 菜单里「删除」仍**置灰**（内置唯一限制）。

## 8. 全量测试结果

命令（工作目录 = 仓库根；`java` 默认是 8 ⇒ 必须给 `JAVA_HOME`；`--rerun-tasks` 防 build cache 假绿）：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks
```

| 项 | 值 |
|---|---|
| 任务数 | **27 actionable tasks: 27 executed**（全部重跑，无 UP-TO-DATE / FROM-CACHE 复用） |
| 测试类 | **51** |
| 用例数 | **559** |
| 失败 | **0** |
| 错误 | **0** |
| 跳过 | **0** |
| EXIT | **0**（`BUILD SUCCESSFUL in 12s`） |
| 本卡新增/改动的三个类 | `CameraPresetCheckTreeTest` 16 / `CameraPresetPanelUiTest` 18 / `CameraPresetEditTest` 17 —— 全绿 |
