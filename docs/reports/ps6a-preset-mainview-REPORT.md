# PS-6a · 预设主视图重做（图 2 形态 + 常用参数可调）

> 用户 2026-10-12 真机实测（v0.11.21）："预设参数勾你妈的勾选，丑死……**点击预设按图 2 那样展示**，
> 并提供几个常用参数调节（**lut强度，颗粒开、关、档位，柔光档位，光晕档位，暗角调节**）"。
> 状态：**代码 + 单测 + mutation 自检完成**；**未上真机**（§5 逐条声明）。未 commit。

## 1. 改了什么（形态对照）

| 位置 | PS-5（用户否决） | PS-6a（现在） |
|---|---|---|
| 参数区 | 分组勾选树（☑/☐ + 三态框 + 全选/全不选），首屏 0 滑杆 | **详情卡（图 2）**：名称大字 + 副标题（来源 · 分组）+ 可展开描述 + 「当前搭配」两列只读清单 + 右上「完整参数 >」 |
| 怎么调参数 | 勾选"包含哪些" + 点开一行出滑杆 | **常用参数区 5 项常驻**（LUT 强度滑杆 / 颗粒四档 / 柔光四档 / 光晕四档 / 暗角四档）＋ 点清单里"有滑杆的项"出**一条**滑杆 |
| 点清单一项 | — | **有反应**（判据 `presetQuickPath`）：常用项 ⇒ 提示去下面哪个档位调；有滑杆 ⇒ 出那一条滑杆；都没有 ⇒ 行下写明"为什么点不动"（ADR-12，不做假控件） |
| 逐项参数（35 条） | 树里逐项 | 「完整参数 >」→ **PS-6b 的完整参数页**（本卡只给回调 `onOpenFullEdit: () -> Unit = {}`） |
| 保存 | 「保存到预设」（含"移除项"） | 主视图里**有改动才出现**「保存到预设」；`[34]` LUT 强度显式带回 `lutStrength` |
| 列表 / ⋯菜单 / 删除确认 / 「+ 新建预设」/ 重命名 / 复制 | — | **一位未动**（`CameraPresetRow` 未改） |

## 2. 常用参数 5 项 + 档位依据表（用户要求"每个档位写明依据"）

| 项 | 控件 | 槽位 | 档位值 | 依据 |
|---|---|---|---|---|
| **LUT 强度** | 滑杆 0–150%（复用 `LutStrengthParam`；**这条预设含 LUT 时常驻**，不含时显示一句实话而不是假滑杆） | `[34]` | 直通量程 | 既有参数（E2-15"统一可调至 150"）；★ 用户投诉"滤镜强度都不可调" ⇒ 现在常驻详情卡与编辑面板 |
| **颗粒** | 四档按钮 | `[26]` + `[27]` | 关 0/400 · 细 20/160 · 中 30/400 · 粗 40/800 | 关 = 引擎中性值（`[26]=0` 整段旁路、`[27]=400` 标定起点）；细/中 = 内置「徕卡一瞬」20/160（note"细颗粒"）与「理光负片」30/400（note"中颗粒"）**逐字**；粗 = 内置「徕卡黑白」强度 40（note"明显颗粒"）+ 尺寸推到 400 的下一 ISO 标准档 800（`[27]` = ISO，越大越粗） |
| **柔光** | 四档按钮 | `[95]` | 关 0 · 轻 0.25 · 中 0.50 · 强 0.75 | 关 = 引擎中性值（整链旁路）；**内置三条都没开过柔光**（`builtin.json` 无 glow 字段）⇒ 轻/中/强只能按面板量程 0..1 四等分（**如实声明：无"参考内置"依据**） |
| **光晕** | 四档按钮 | `[62]` | 关 0 · 弱 0.25 · 中 0.50 · 强 0.75 | 同上（`[62]=0` 整链旁路；内置三条同样没有 halation 字段） |
| **暗角** | 四档按钮 | `[66]` | 关 0 · 轻 0.20 · 中 0.40 · 强 0.70 | 关 = 引擎中性值（`kFsOut` 整段跳过）；**有内置依据**：三条内置 `vignette_strength` = −0.18 / −0.28 / −0.24（= UI 0.18/0.28/0.24）⇒ 轻 0.20 = 内置最小档取整、中/强按 0.2 步进（0.40 / 0.70，明显但不糊） |

档位名 = 图 2 字面（`presetLevelLabel` 已删，字面由 `QuickLevelParam.names` 逐项给定）：
`关闭 / 轻柔光 / 中等柔光 / 强烈柔光` · `关闭 / 轻微光晕 / 中等光晕 / 强烈光晕` · `关闭 / 轻微暗角 / 中等暗角 / 强烈暗角` · `关闭 / 细颗粒 / 中颗粒 / 粗颗粒`。

## 3. 写值口径（只有一条路）

- 滑杆与档位**都经 `EditorParam.write`** → `afterEdit` 记账 → `onPreview`（`previewCameraParams`）实时下发；
  松手 / 点档位 `onOverrides` 一次提交。拖动期间**不写 Compose state**（`DragHolder` 原样保留）。
- **颗粒没有既有滑杆** ⇒ 本文件私有 `GrainLevelParam`：一条档位**同写 `[26]`+`[27]`**（与饱和度"一滑杆同写三槽"同型），
  写进数组的就是 `GrainLevel` 自己的两个真实值 —— 不是第二份参数表。
- ★ **LUT 强度 `[34]` 的两处特判**（都在源码注释里写明理由，否则就是"拖了没反应"）：
  1. `afterLutStrengthEdit`：`[34]` 不进 `slots`（`presetSlotIncluded` 排除）⇒ 展开数组里恒为基线 1.0，
     "拖到 100%"会被 `afterEdit` 的 recipe 比对当"没改"丢掉 ⇒ 与**预设自己的** `lutStrength` 比；
  2. `pushEdit` 同时写 prefs 的 `lut_strength`：`CameraController.applyLutStrengthPref()` 会用 prefs
     **覆盖** `filmParams[34]` ⇒ 只把 `[34]` 放进下发数组是无效的（LUT tab 的强度滑杆走的是同一条事实源）；
  3. 保存经 `saveSlotsWithLutStrength` 写回 `preset.lutStrength`（借助 CameraScreen 的 `p.copy(slots=…)` 通路，未改其代码）。
- `[0]` 曝光偏移口径不变；`CameraParamOverrides.removed` 的数据通路保留（`CameraScreen.previewCameraParams`
  仍传 `removed`，PS-5 修的 `effectivePresetEvOffset(..., removed)` 仍在），但**本卡的 UI 不再产生 `removed`**。

## 4. 判据（机器可判）

**新增单测** `app/src/test/java/com/filmkit/app/ui/CameraPresetMainViewTest.kt`（9 条）：

| 用例 | 钉住什么 |
|---|---|
| `grainLevelsFollowTheBuiltinPresetsAndRoundTripThroughBothSlots` | 三条内置 20/30/40 的档位、关/中档的引擎中性值、四档 write→read 闭环、两槽同进改动集 |
| `levelChipsRoundTripAndTheirNamesMatchTheSketch` | 柔光/光晕/暗角四档闭环 + 档位名字面 + 内置暗角值落在"轻"档 |
| `quickAdjustCoversExactlyTheFiveItemsTheUserNamed` | 常用项 = {34,26,27,95,62,66}；`presetQuickPath` 三分支（QUICK/SLIDER/NONE + NONE 必须有原因） |
| `overviewRowsFoldLevelsAndNeverShowUnsetSlots` | 滤镜行在前、`[27]` 并入颗粒行、档位名 + 数值、空预设无行、LUT 名回落 id |
| `overviewTextIsBuiltFromRealData` | 描述段落的来源/分组/项数/LUT/套用语义（不编造） |
| `levelWriteGoesThroughTheEditorParamAndSavesBackSparsely` | 中颗粒 ⇒ `[26]=30` 进预设、`[27]=400` 被稀疏化丢弃；关闭档 = 空改动集 |
| `lutStrengthEditIsRecordedEvenAtTheBaselineValueAndSavedBack` | `[34]` 拖到 100% 也记账（recipe 比对会丢）、拖回原值撤销、保存带回 `lutStrength` |
| `exposureOffsetFollowsTheEditedValue` | 曝光改值 ⇒ 数组与 `evOffset` 两条口径同时跟上；拖回 0 = 不改变曝光 |
| `builtinsStayEditableRestorableAndUndeletable` | 内置可改（覆盖层）· 只禁删 · 恢复默认回出厂 |

**测试改动（口径变更逐条）**：
1. `CameraPresetCheckTreeTest.kt` **删除**（勾选树被用户否决）。其中仍成立的能力迁入新文件：写值口径、
   内置可改/只禁删/恢复默认、曝光偏移、稀疏化；被否决的（三态框、全选/全不选、勾选=加入）不再保留。
2. `CameraPresetPanelUiTest.kt` 两条哨兵改写：能力清单换成新落点（`CameraPresetDetailCard(` /
   `PresetQuickAdjustPanel(` / `QuickLevelChips(` / `presetOverviewRows(` / `presetQuickPath(` /
   `saveSlotsWithLutStrength(` / `onOpenFullEdit`），并新增 **反向哨兵**：勾选树符号
   （`CamParamCheckTree(` / `checkSlots(` / `☑` / `不包含此项` / `全不选` …）**一个都不许回来**；
   滑杆渲染点钉死为 **2 处**（LUT 强度 + 选中项的滑杆），并钉住档位 `onPick`、「完整参数 >」、
   `params = params` 三处接线。
3. `CameraPresetEditTest.kt`：「移除该项」两条改成新口径 —— `draggingASlotBackToNeutralDropsItFromTheSavedRecipe`
   （调回中性值 ⇒ 保存后被稀疏化丢弃 = 等效的"不再包含"）+ `theRemovalPathStillWorksAtTheDataLayerWithoutAUiEntry`
   （`removed` 数据通路仍在，供完整参数页将来使用）；接线哨兵 `val target = p ?: selected` → `val target = editor ?: selected`（同一条不变量，表达式随重构改名）。
4. `PresetDetail.kt` / `PresetDetailTest` / `cameraParamSections` / `presetSummaryText` **一位未动**。

**mutation 自检（3 处，贴红后已恢复，`grep -c MUTATION` = 0）**

| # | 注入 | 实得 |
|---|---|---|
| 1 | `grainLevelOf` 的细档分界 `25.0` → `5.0` | ✅ 红：`grainLevelsFollowTheBuiltinPresets… FAILED` + `overviewRowsFoldLevels… FAILED`（档位与清单同时红） |
| 2 | `levelOverviewRow` 的档位名折叠 `"${quick.names[idx]} · $detail"` → `detail` | ✅ 红：`overviewRowsFoldLevelsAndNeverShowUnsetSlots FAILED: expected:<轻[微暗角 · 18]> but was:<[18]>` |
| 3 | `saveSlotsWithLutStrength` 直接返回 `preset`（忽略 `[34]`） | ✅ 红：`lutStrengthEditIsRecordedEvenAtTheBaselineValueAndSavedBack FAILED` |

**编译 / 测试实测**（工作目录 = 仓库根，`JAVA_HOME=/opt/homebrew/opt/openjdk@23`，全部 `--rerun-tasks`）：

| 命令 | 结果 |
|---|---|
| `./gradlew :app:compileDebugUnitTestKotlin --rerun-tasks` | **BUILD SUCCESSFUL**（PS-6b 提醒的测试源集编译失败已修：旧勾选树测试已删/改写） |
| `./gradlew :app:testDebugUnitTest --rerun-tasks --tests "…CameraPresetMainViewTest" --tests "…CameraPresetPanelUiTest" --tests "…CameraPresetEditTest" --tests "…PresetDetailTest" --tests "…PresetPanelUiTest"` | **BUILD SUCCESSFUL**，5 类 **74 条 0 失败**（本卡新增 9 条 / 面板哨兵 18 / 编辑 17） |

## 5. 未验证 / 已知不完美（如实声明）

1. **未上真机**：详情卡 262dp 限高 + 列表 200dp 在窄屏 / 大字号下的观感；档位 chip 热区（10sp + 8×4dp padding）；
   两列清单里长档位名的换行；LUT 强度滑杆的拖动跟手度 —— 全待 §6。
2. **写 prefs 的副作用**：在预设详情拖 LUT 强度会**立即**改 `filmkit` 的 `lut_strength`（引擎侧唯一事实源）。
   没点「保存到预设」时它也不会自己回退 ⇒ 要还原就把滑杆拖回预设的强度值。
3. **中颗粒的尺寸 `[27] = 400` 恰好等于引擎恒等值** ⇒ 保存时被稀疏化丢弃（既有全局口径）：
   套用时该项保持当前尺寸，而不是强制 400。
4. **「关闭」档 = 回到中性值** ⇒ 保存时同样被稀疏化丢弃：语义是"这条预设不再覆盖它"，**不是**"强制关闭"
   （若相机上另一条预设开着颗粒，套用后仍会看到颗粒）。这是稀疏子集模型的既有语义，不是本卡引入。
5. **没有「撤销」按钮**（未做，YAGNI）：把档位/滑杆调回预设原值即自动撤销（改动集只记"与预设不同"的槽）。
6. 「完整参数 >」的**实际跳转**由 PS-6b 接线（已在 `CameraScreen.kt` 见到 `onOpenFullEdit = { fullEditOpen = true }`）；
   本卡的哨兵只钉"按钮调用了回调"。完整参数页本身**不是本卡交付**。
7. 勾选树的 `[26]/[27]` 语义变化：它们从"能勾进/勾出预设的灰显项"变成"颗粒档位直接写"；
   `cameraParamAdjustabilityNote(26)`（"拍照生效 · 暂无滑杆"）在本卡的清单里**不再显示**（因为它现在有档位可调）。

## 6. 真机验收步骤（8 步）

1. 拍照界面 → 底部「预设」tab：上方应出现**详情卡**（当前生效预设的名称大字 / 来源 · 分组 / 一行描述 + 「展开」）。
2. 点「展开」→ 描述变多行 + 按钮变「收起」；点「收起」回到一行。
3. 「当前搭配」下方是**两列只读清单**：第一行是「滤镜 <LUT 名> · <强度>」；有颗粒的预设显示「颗粒  中颗粒 · 数量 30」这类；
   空预设显示"不含参数（套用时其余参数保持不动）"。
4. **点清单里任意一项**：提示行从「选择一项开始调整」变成"已选「X」· …"（常用项会指到下面哪个档位；胶片曲线/对比度等
   会在下方出**一条**滑杆；"颗粒 · 尺寸"这类没有快捷项的会提示去「完整参数 >」）。
5. 「常用参数」区：拖 **LUT 强度**滑杆 → 取景画面实时变；点 **颗粒** 的「细/中/粗」→ 取景出现颗粒；点「关闭」→ 消失。
6. 依次点 **柔光 / 光晕 / 暗角** 的四个档位 → 每一下取景都应有可见变化（光晕在夜景点光源旁最明显）。
7. 有任意改动后，详情卡底部出现「未保存的调整 [保存到预设]」→ 点它；回列表再进这条预设：清单里的档位与数值应为保存后的值
   （尤其 **LUT 强度**：拖到 120% 保存后重新打开应仍是 120%）。
8. 右上「**完整参数 >**」→ 应打开 PS-6b 的完整参数页（若该页尚未接线，按钮点了无反应属 PS-6b 未完成，本卡只负责调用回调）。
   另验：长按列表行 → 编辑面板（名称 / 分组 / 常用参数 / 「完整参数 >」/ 保存 / 删除），内置预设的「删除」仍置灰。
