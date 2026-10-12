# PS-7 · 预设面板显示崩坏修复（不透明底 + 常用参数首屏 + **每一项都点得动**）

> 用户 2026-10-12 真机截图两轮：v0.11.22"**你做这一坨屎是什么**" + 追加"**这个界面的参数都动不了，
> 要进完整参数才能调节？那我要这个界面干嘛？**"
> 状态：代码 + 单测（全量 **571 tests / 0 failures**）+ mutation **4 处**自检完成；**未上真机**（§4）。未 commit。

## 1. 逐条对应（截图缺陷 → 修法 → 钉住它的判据）

| # | 截图读出的缺陷 | 根因 | 修法 | 判据（单测） |
|---|---|---|---|---|
| 1 | 面板背景半透明，取景界面的 `1/60 1539 29cm 290`、`EV Tv ISO AF WB` 与预设内容**混叠** | `LutSheet` 用 `FilmTheme.Panel`（`0xE6141414`，90% alpha） | `CameraScreen.kt` 的 `LutSheet` 改 `FilmTheme.PanelSolid`（`0xFF141414`）；详情卡继承该不透明底，`CameraPresetPanel.kt` 不再引用半透明 `Panel` | `Ps7…lutSheetUsesTheOpaquePanelSoTheViewfinderTextCannotBleedThrough`（正向 + 两条反向哨兵；mutation-3 会红） |
| 2 | 点名的**常用参数区根本看不到**（`heightIn(262.dp)` + `verticalScroll`，内容远超出） | 详情卡整卡可滚，常用参数排在清单**之后** ⇒ 落在屏幕外 | 详情卡**去掉整卡滚动**，顺序改为：名称 → **一行**副标题 → 一行描述 → **常用参数（LUT 强度 + 颗粒/柔光/光晕/暗角四档）** → 「当前搭配」标题行 → **选中项滑杆（固定槽）** → 清单（限高可滚）→ 提示行 → 「保存到预设」 | `detailCardRendersQuickAdjustBeforeTheOverviewListAndNeverScrollsAsAWhole`（区块 index 单调递增 + 唯一 `verticalScroll` 挂在清单区；mutation-4 会红） |
| 3 | 副标题与描述**重复**（`内置预设 · 分组「徕卡」` 出现两次） | 描述段把来源 / 分组又拼了一遍 | `presetOverviewText` 只留副标题**没有**的信息（项数 · 滤镜名） | `overviewTextIsOneRealLineAndDisappearsWhenThereIsNothingToSay`（还断言描述里不许再出现"内置预设"/"分组"） |
| 4 | 描述是**机器拼接**（`包含 17 项参数（基础调整 / 色彩 / …`，还被截断） | 用分组名清单拼长句 | 改成 `17 项参数 · 滤镜 徕卡 M9`；**没有真实信息就返回 `null` ⇒ 整行不渲染**（不拼字符串凑） | 同上（空预设 ⇒ `null`；只有一项真实信息就只显示一项） |
| 5 | 「当前搭配」的**值位混入内部标注**（`分离色调 · 暖 → 拍照生效 · 暂无需滑杆`） | "为什么点不动"的说明占了值位 | 值位只走 `presetRowValueText`（空值 ⇒ 「—」）；说明只在**行下小字**、且仅 `PresetRowTap.NOTE` 态渲染 | `overviewRowValueNeverCarriesTheWhyItCannotMoveNote` + `overviewRowValueSlotOnlyShowsRealValuesAndTheNoteMovesBelowTheRow` |
| ★ | **"参数都动不了"**（点一项后界面无变化：滑杆插在滚动区的屏幕外） | 同上（整卡滚动 + 滑杆渲染在滚动内容里） | `presetRowTap` 三态判据（**没有第四态**）：`LEVEL` ⇒ 常用参数区对应档位行**点亮**；`SLIDER` ⇒ 清单**上方固定槽**出「选中项：名字 · 值」+ 那一条滑杆；`NOTE` ⇒ 行下小字写明原因 + 提示行给「完整参数 >」指引。三态各有渲染落点，源码哨兵钉住滑杆在**滚动区之外** | `everyOverviewRowTapHasAVisibleOutcome`（遍历 `CAMERA_PATH_SLOTS`，NOTE 必须有原因文案；常用 5 项一个都不许落 NOTE）+ `pickedSliderLivesInTheFixedAreaAboveTheList` |
| ★ | 面板总限高（详情 + 列表同屏，谁也别被挤没） | 旧值硬编码 262 / 200dp | `presetPanelBudget()`：详情 + 列表 = **恒定 620dp**（收起 470 / 150，展开清单 510 / 110）；清单高度 `presetOverviewMaxHeightDp()` = 3 行 ↔ 8 行（24dp/行） | `detailAndListShareOneBudgetSoNeitherIsSqueezedOut` + `overviewListIsHeightCappedAndExpandable`（mutation-1 会红） |

新增纯逻辑（全部 `internal` + 单测）：`PresetRowTap` / `presetRowTap` / `presetRowValueText`、
`presetOverviewShownRows` / `presetOverviewMaxHeightDp` / `presetPanelBudget` / `PRESET_PANEL_BUDGET_DP`；
`presetOverviewText` 改为 `String?`。

## 2. mutation 证据（4 处，红后已恢复，`grep -c MUTATION` = **0**）

| # | mutation | 结果（`./gradlew :app:testDebugUnitTest --rerun-tasks --tests …Ps7…`） |
|---|---|---|
| 1 | `PRESET_OVERVIEW_ROWS_COLLAPSED = 3` → `7` | `overviewListIsHeightCappedAndExpandable FAILED`（`java.lang.AssertionError at Ps7…Test.kt:111`） |
| 2 | `presetRowTap` 的 `NONE -> NOTE` 改成 `LEVEL`（= 让"点不动"的项假装能调） | `everyOverviewRowTapHasAVisibleOutcome FAILED`（`…Test.kt:200`） |
| 3 | `LutSheet` 的 `PanelSolid` 改回 `Panel`（= 恢复透视） | `lutSheetUsesTheOpaquePanelSoTheViewfinderTextCannotBleedThrough FAILED`（`…Test.kt:50`） |
| 4 | 把**常用参数整块搬到清单之后**（= 复现"藏在滚动里"的旧布局） | `detailCardRendersQuickAdjustBeforeTheOverviewListAndNeverScrollsAsAWhole FAILED`（`…Test.kt:88`） |

三处同时注入时的输出：`7 tests completed, 3 failed / BUILD FAILED`；mutation-4 单独：`7 tests completed, 1 failed / BUILD FAILED`。
恢复后：`:app:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL`，全量 **571 tests / 0 failures**。
命令口径：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks`（默认 java 是 8）。

## 3. 未动（"不许回退"清单逐条）

- **常用参数功能与口径一字未改**：LUT 强度 `[34]` 的两处特判（`afterLutStrengthEdit` 与预设自己的 `lutStrength` 比；`pushEdit` 同时写 prefs）、档位映射（颗粒 `[26]`+`[27]`、柔光 `[95]`、光晕 `[62]`、暗角 `[66]`）、档位依据 —— 一行未动。
- **`CameraPresetFullEdit.kt`（PS-6b 的页面）未改**；`ParamControls.kt` 未改（`EditorSlider` 原样复用）。
- 列表 / ⋯ 菜单 / 新建 / 重命名 / 复制 / 删除 / 恢复默认 / 内置可改只禁删：逻辑未动（`CameraPresetRow`、`presetMenuItems`、`presetEditorButtons` 全未触碰）。
- 「完整参数 >」仍是 PS-6b 页面的入口（`onOpenFullEdit`）。

## 4. 未验证项（诚实声明）

- **真机未验证**：本机没有设备，也没有 instrumentation 测试；改的是 Compose 布局，只能靠源码哨兵 + 纯函数判据。
- 高度预算 `470 / 510 / 150 / 110dp` 是按现有控件行高（滑杆 ≈ 62dp、档位行 ≈ 27dp、清单行 ≈ 24dp）**估算**的分配，未在真机测窄屏（≈640dp 可用高）与系统大字号下的实际露行数。真机若仍紧 ⇒ 只改 `presetPanelBudget()` 两个数（判据测试会跟着约束走）。
- 档位行"点亮"用的是既有 `FilmTheme.ChipBg` 底，真机上是否足够显眼未验证。
- 清单区收起时仍可小幅滚动（3 行高 + 内部滚动）——"收起时是否该完全不可滚"未与用户确认。
- 未跑 lint / assembleRelease / instrumentation。

## 5. 真机验收步骤（逐条对应缺陷）

1. 相机 → 底部「LUT/预设」→「预设」tab：**面板底必须是纯炭黑**，上方取景区的 `1/60 1539 29cm 290` 与 `EV Tv ISO AF WB` **不得透过来**（缺陷 1）。
2. **不滚动**的首屏必须看到：预设名 / 一行副标题 / 一行描述 / **常用参数**（LUT 强度滑杆 + 颗粒·柔光·光晕·暗角四行档位）（缺陷 2）。
3. 拖 LUT 强度、点颗粒四档、点柔光/光晕/暗角档位 ⇒ 画面即时变化，并出现「未保存的调整 / 保存到预设」（口径未回退）。
4. 点清单里**任意一项**，每一步都必须有肉眼可见的变化（追加诉求）：
   - 有滑杆的项（如对比度）⇒ 清单**上方**出现「选中项：… · 值」+ 滑杆，拖动即时生效；
   - 常用项（如柔光）⇒ 常用参数区对应档位行**点亮**；
   - 没有滑杆的项（如分离色调）⇒ 行下小字写明原因，提示行指「完整参数 >」。
5. 「展开 N 项 / 收起」⇒ 清单行数 3 ↔ 8 变化，下方预设列表仍在屏内（≥2 行可见、可滚）（缺陷 2 + 硬约束）。
6. 「完整参数 >」仍能进 PS-6b 页面；列表点行套用 / 长按编辑 / ⋯ / 新建 / 删除仍正常。
