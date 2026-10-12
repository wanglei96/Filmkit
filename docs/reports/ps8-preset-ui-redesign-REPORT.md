# PS-8 · 预设界面重设计（横向滚动 + 一级页精简 + 返回键分层 + 实时预览）

> 用户 2026-10-12/13 真机（v0.11.23）："**UI做的太丑了重新设计一下，预设改成横向滚动**。。
> **第一级页面不需要提供全部参数，点击查看完整参数才提供**。。。**一级页面系统返回按钮关闭
> 预设页面，不要直接退出app**。。**一级参数调节要能在预览看到实时效果**"
> 状态：代码 + 单测（全量 **572 tests / 0 failures**，54 个测试类）+ mutation **3 处**实测
> （红后已恢复，`grep -c MUTATION` = **0**）；**未上真机**（§6）。**未 commit**。

## 1. 五件事逐条（改了什么 → 落在哪个控件 → 判据）

| # | 要求 | 改法 | 落点 | 判据 |
|---|---|---|---|---|
| ① | **横向滚动** | 纵向 `LazyColumn`（`CameraPresetRow`）→ 横向 `LazyRow`（`PresetStrip`）；卡片等宽 124dp，条高 **64dp**（`PRESET_STRIP_HEIGHT_DP`，≤72 ✓） | `PresetStrip` / `PresetStripCard` / `PresetNewCard`；`presetStripItems()` / `presetStripBadge()` | `stripKeepsTheListContractAndMarksExactlyOneSelected`、`stripIsHorizontalBoundedInHeightAndStartsWithTheNewPresetEntry`、`stripBadgeCarriesBuiltinEditedAndGroupOnOneLine` |
| ① | 能力一个不丢 | 点卡片 = 套用、**长按 = 详情/编辑面板**、卡片右下 `⋯` = 既有菜单（`presetMenuItems`：套用/查看/编辑/重命名/复制/恢复默认/置灰删除）、删除二次确认 | `PresetStripCard`（`onTap/onLongTap/onMenu`）、`menuFor` / `pendingDelete` 原样 | `CameraPresetPanelUiTest` 18 条（菜单/内置只读/编辑按钮）全绿 |
| ② | **一级页不给全部参数** | **删**：`CameraPresetDetailCard`（「当前搭配」两列清单 / 展开收起 / 选中项滑杆槽 / 提示行 / `presetScopeNotice` 调用 / 行下"点不动"小字）、`PresetOverviewRowText`、纵向列表 | 一级页只剩名称行 + 一行副标题 + 常用参数 + 「完整参数 >」 | `firstLevelIsThreeBlocksPlusTheSaveRowAndNeverScrollsAsAWhole` + `Ps7PresetPanelLayoutTest.theOverviewChecklistAndItsExplanatoryNoiseAreGoneForGood`（21 个旧符号反向哨兵） |
| ② | 删掉的纯逻辑 | `presetOverviewRows` / `levelOverviewRow` / `presetOverviewText` / `PresetRowTap` / `presetRowTap` / `presetRowValueText` / `presetOverviewShownRows` / `presetOverviewMaxHeightDp` / `PRESET_OVERVIEW_*` / `presetPanelBudget` / `PresetPanelBudget` / `PRESET_PANEL_BUDGET_DP` / `PresetQuickPath` / `presetQuickPath` / `quickAdjustLabelOf` | `CameraPresetPanel.kt` **2043 → 1668 行**；整仓 diff = **+551 / −1157** | 编译 + 全量测试（引用点已同步删/改） |
| ③ | 常用参数视觉 | LUT 强度 = **一行** `标签(60dp) │ RelativeSlider(weight 1f, 30dp, 刻度+基准标记) │ 数值(46dp, Mono, End 对齐)`；四档 = **分段控件** `PresetLevelSegments`：外层 1dp `ChipBorder` 圆角 8dp、四段 `weight(1f)` 等宽、段间 1dp 结构线、**选中填充 `Accent` + `OnAccent` 文字**；块间距统一 `PRESET_BLOCK_GAP_DP` = 12dp | `PresetParamSliderRow` / `PresetLevelSegments` / `PresetQuickAdjustPanel`（一级页与编辑面板共用） | `quickAdjustKeepsTheFiveItemsAndRendersSegmentsAndAMonoValueRow`、`CameraPresetPanelUiTest.theQuickAdjustBlockRendersExactlyOneSliderAndEveryTapIsWired` |
| ③ | 功能一字不改 | `GrainLevelParam`（一条档位同写 `[26]`+`[27]`）、`QUICK_LEVEL_PARAMS`（`[95]/[62]/[66]`）、档位数值与依据注释、`[34]` 两处特判（`afterLutStrengthEdit` + `pushEdit` 写 prefs）**一位未动**；写值仍只有一条路 `EditorParam.write → afterEdit → onPreview` | `pushEdit` / `pickLevel` / `finishEdit`（原样） | `CameraPresetMainViewTest` 7 条全绿（含 LUT 强度记账与保存、档位闭环、内置纪律） |
| ④ | **返回键分层** | `CameraScreen`：`CameraBackTarget` + 纯函数 `cameraBackTarget(fullEditOpen, quickOpen, lutOpen)` + `BackHandler(enabled = …)`；优先级 = **完整参数页（二级）→ 快捷菜单 → LUT/预设面板**；全关 ⇒ `null` ⇒ 不拦（照旧退出 app）。`CameraPresetTab` 内：`BackHandler(enabled = editorOpen) { closeEditor() }`（子态，注册更晚 ⇒ 先接住） | `CameraScreen.kt`（+35 行）/ `CameraPresetPanel.kt` | `backKeyClosesTheInnermostLayerFirstAndNeverSwallowsTheLastBack`、`screenWiresTheBackHandlerAndThePanelClosesItsOwnSubState` |
| ④ | 不与既有浮层打架 | AlertDialog（分类 / 删除确认 / `⋯` 菜单）由 Dialog 自己的窗口消费 back；`LutSheet` 面板内滚动/横滑不拦 back | —— | 同上（优先级纯函数可判） |
| ⑤ | **实时预览** | 通路未改（`pushEdit → onPreview → previewCameraParams → controller.setFilmParams`）；一级页**没有任何 `verticalScroll`**（老病根"内容在滚动区屏幕外"在判据层面不存在）；拖动只重组滑杆那一行（`mutableFloatStateOf` 在组件内，上层 state 一位不写） | `PresetParamSliderRow` / `PresetQuickAdjustPanel` | `firstLevelIsThreeBlocksPlusTheSaveRowAndNeverScrollsAsAWhole`（禁 `verticalScroll`）+ `…RendersExactlyOneSliderAndEveryTapIsWired`（`params = params` 接线） |

**面板高度**：一级页 ≈ **336dp 估**（横向条 64 + 名称行 34 + 副标题 16 + 常用参数 12+5×30+4×12=210 + 保存行 40）＋ `LutSheet` 顶部 tab 区 ≈ 70 ⇒ **约 400dp**；PS-7 的预算是 620dp（详情 470 + 列表 150）⇒ 取景区随之变大。

## 2. 视觉条目（"太丑"落到可执行条目）

| 条目 | 落点 |
|---|---|
| 信息层次三段式 | 预设名 **18sp Serif SemiBold**（名称行）→ 参数标签 **12sp `CreamDim`**（每行左列）→ 数值 **12sp Mono**（右列）/ 段名 11sp |
| 留白统一 | 只有一个常量 `PRESET_BLOCK_GAP_DP = 12dp`（块间距 + 常用参数区 `Arrangement.spacedBy`），无第二档 |
| 对齐 | 标签列 60dp / 数值列 46dp 定宽 + `TextAlign.End`；段控四等分；卡片等宽 124dp；父层 16dp 内边距（`LutSheet`） |
| 克制（≤4 块） | 预设条 / 名称行（含副标题与「完整参数 >」）/ 常用参数（+ 有改动时的一行保存）；**无说明文字、无分割线、无装饰** |
| 颜色 | 只用既有 token：`Cream / CreamDim / Accent / OnAccent / ChipBg / ChipBorder / CamDisabledInk`（判据 `redesignIntroducesNoNewColors` 禁 `Color(0x`、`Color.Red/White/Black/Gray`） |
| 反馈 | 卡片 / 段控 / `⋯` / 按钮全走既有 `pressScale`（0.94–0.96）；选中态 = `Accent` 描边 + `ChipBg` 底（卡片）/ `Accent` 填充（段） |

## 3. 未动文件（硬约束）

`CameraPresetFullEdit.kt`（PS-6b 二级页，一个字没改）、`ParamControls.kt`（只**复用**其 `EditorParam` / `RelativeSlider`，未编辑）、`PhotoViewScreen.kt`。

## 4. mutation 证据（3 处，红后已恢复）

| # | 注入 | 结果（`--tests "…Ps8PresetUiRedesignTest"`） |
|---|---|---|
| 1 | `presetStripItems` 首项不再补内置「标准」（`listOf(CAMERA_STANDARD_PRESET) + presets` → `presets`） | `stripKeepsTheListContractAndMarksExactlyOneSelected FAILED`（`java.lang.AssertionError at Ps8PresetUiRedesignTest.kt:53`） |
| 2 | `cameraBackTarget` 把 `lutOpen` 提到最前（一级面板抢在二级页前关） | `backKeyClosesTheInnermostLayerFirstAndNeverSwallowsTheLastBack FAILED`（`…Test.kt:165`） |
| 3 | `presetStripBadge` 去掉"已改只对内置"守卫 | `stripBadgeCarriesBuiltinEditedAndGroupOnOneLine FAILED`（`ComparisonFailure: expected:<街拍> but was:<已改 · 街拍>` at `…Test.kt:93`） |

三处同时注入：`9 tests completed, 3 failed` → `BUILD FAILED`；恢复后同命令 `BUILD SUCCESSFUL`。
`grep -c MUTATION CameraPresetPanel.kt CameraScreen.kt` = **0 / 0**（恢复后）。

## 5. 不许回退（逐条自检，全部仍绿）

`[34]` 两处特判（`afterLutStrengthEdit` + `pushEdit` 写 prefs / 保存显式带回 `lutStrength`）· 档位映射与依据
（颗粒 `[26]`+`[27]`、柔光 `[95]`、光晕 `[62]`、暗角 `[66]`）· 内置可改只禁删（`presetDeletable` 仍是唯一判据，
横向卡片的小标记也用它）· 恢复默认 / 保存到预设 / 另存为 / 从当前相机参数更新 / 保存名称分组 / 删除二次确认 ·
`[0]` 曝光口径（`exposureOffsetFollowsTheEditedValue`）· 每个能点的都有反应 · `LutSheet` 不透明底（`PanelSolid`）。

## 6. 未验证项（诚实声明）

1. **未上真机**：横向条的滑动手感；换预设时**卡片不会自动滚入视野**（选中项在条外时看不到高亮）；124dp 卡片下长名称 / 长 badge（`内置 · 已改 · 徕卡`）会被省略号截断的观感。
2. 段控四段在**窄屏（320dp）与系统大字号**下的实际显示（判据只钉"等宽 + 省略号"，未量像素）。
3. 一级页 ≈400dp 是按行高**估算**（段控 30 / 间距 12 / 名称行 34），未在真机量取景区剩余比例；真机若仍嫌高 ⇒ 只改 `PRESET_BLOCK_GAP_DP` 与段控 `height(30.dp)`。
4. LUT 强度滑杆行高 30dp（原 `EditorSlider` 是读数行 + 44dp 滑杆）⇒ **拖动热区变小**，跟手度未实测。
5. badge 同时有"已改 + 分组"时的取舍（是否只留其一）未与用户确认。
6. 未跑 lint / assembleRelease / instrumentation。

## 7. 真机验收步骤（逐条对应用户四句话）

1. 相机 → 底部「LUT/预设」→「预设」tab：预设是**一排横向卡片**，可左右滑；条首「+ 新建预设」；**当前生效那条有琥珀描边 + 底色**；点卡片 = 套用、长按 = 详情/编辑、右下 `⋯` = 既有菜单（①）。
2. 首屏**看不到**任何参数清单 / 展开按钮 / 行下小字 —— 只有：横向条 / 名称 / 一行副标题 / 常用参数 / 「完整参数 >」（②）。
3. 面板打开时按**系统返回** ⇒ 只关面板，**不退出 app**；编辑面板开着 ⇒ 先关它；「完整参数」页开着 ⇒ 先关它，再按才关面板（③④）。
4. 拖 LUT 强度、点颗粒 / 柔光 / 光晕 / 暗角四档 ⇒ **上方取景实时变化**；松手后出现「未保存的调整 [保存到预设]」（⑤）。
5. 「完整参数 >」⇒ PS-6b 那一页照旧（35 项逐项调节）。
