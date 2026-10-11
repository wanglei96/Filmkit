# PS-2a 预设管理搬到【拍照界面】—— 实现报告

> 卡号 PS-2a ｜ 用户方向纠正（2026-10-11）："预设的配置 / 新增 / 编辑 / 删除**我指的是拍照界面的预设**。这个**不能让用户在相册里面改这些配置**。"
> 交付物：拍照界面 `LutSheet`「预设」tab = **管理场所**（看配置 / 新增 / 编辑 / 删除）；相册与编辑器只负责**套用**
> 状态：代码完成、构建 + 单测真跑通过（496 用例 / 0 失败）｜ ⚠ **未真机验证**（见 §7）
> 改动文件：`CameraScreen.kt`（宿主接线，+142/−57）、`CameraPresetPanel.kt`（**新建**，821 行）、`CameraPresetPanelUiTest.kt`（**新建**，17 用例）、本报告

---

## 0. 一句话

预设的**管理**从编辑器搬到了拍照界面：`LutSheet` 的「预设」tab 现在是**纵向列表** ——
顶部常驻「**+ 新建预设**」（预填默认名 ⇒ 一路可点），每行显示 **名称 + 来源/分组 + 参数摘要**、
行尾一枚「**⋯**」（`套用 / 查看配置 / 编辑 / 重命名 / 复制 / 删除`，**内置的四项置灰但可见** +
一句"内置预设只读"），点行 = 套用；「⋯」→「编辑」一步进编辑面板（名称/分组 + **完整参数清单** +
「从当前相机参数更新」/「保存名称 / 分组」/「删除」）。**点开即用当前相机参数保存**，
不需要用户先填名称、也不需要在编辑器里绕一圈。

---

## 1. 与 PS-1 的关系：宿主换了，纯逻辑一个字节没重写

架构师诊断（PS-1 把交互做进了编辑器 = 方向错，**组件是好的**）⇒ 本卡的动作是**换宿主**：

| 层 | 归属 | 本卡动作 |
|---|---|---|
| **纯逻辑**（中文名 / 单位 / 格式化 / 菜单可用性 / 摘要 / 按钮差异 / 默认名） | `PresetDetail.kt`（PS-1 留下的**唯一维护点**） | **直接复用**：`presetMenuItems` / `presetReadOnlyNote` / `presetSummaryText` / `presetSummaryParts` / `presetDetailSections` / `presetEditorButtons` / `defaultPresetName` —— 一行没改、一份没抄 |
| **数据层**（读写 / 稀疏化 / 合并 / 只读判据） | `EditorExport.kt` / `BuiltinPresets.kt` / `CameraPresetApply.kt` | **直接复用**：`presetSparseSlots` / `presetSnapshot` / `PresetStore` / `mergePresets` / `presetDeletable` / `presetId` |
| **Compose 渲染** | PS-1 写在 `EditorScreen.kt` 里、且是 **private** | **本卡自己写**（`CameraPresetPanel.kt`）：宿主尺寸与层级完全不同（编辑器是常驻面板；拍照界面是**底部弹出层**，与 LUT tab 共用一个高度预算），照抄会把"编辑器参数面板"的耦合带过来 |

新增的**纯逻辑**只有 5 个函数（全在 `CameraPresetPanel.kt`，JVM 可测）：
`cameraPresetSlots`（含 `[0]` 剔除）、`newCameraPreset`、`updatedCameraPreset`、`copiedCameraPreset`、
`cameraPresetEditorButtons`（内置只读修正）。

---

## 2. 改了什么

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt` | **新建** | 5 个纯逻辑函数 + `CameraPresetTab`（列表 / 新建入口 / 编辑面板分支 / 菜单 / 删除确认）+ `CameraPresetRow` / `CamPresetParamList` / `CamPresetField` / `CamPresetActionButton` / `CameraPresetEditorPanel` |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | 改（+142/−57） | ① `LutSheet` 的「预设」tab 内容整块改为 `presetTab()`（宿主不再认识预设的数据结构；删掉 `presets` / `onPick` 两个参数与旧的横向卡片 + 空列表提示）；② 新增五条写路径 `saveCameraPreset` / `updateCameraPreset` / `renameCameraPreset` / `copyCameraPreset` / `deleteCameraPreset`（全部 `mergePresets(..., BuiltinPresets.load(context))`）；③ 调用点接 `CameraPresetTab(...)` |
| `app/src/test/java/com/filmkit/app/ui/CameraPresetPanelUiTest.kt` | **新建** | 17 个 JVM 用例（菜单可用性 / 真实内置预设的摘要逐字 / `[0]` 纪律 / 三条构造路径的身份 / 编辑面板按钮 / 删除后内置仍在 + 两条源码哨兵） |
| `docs/reports/ps2a-camera-preset-ui-REPORT.md` | **新建** | 本报告 |

**没碰**（`git status` 逐条核对）：`EditorScreen.kt`（PS-2b 在改）、`PhotoViewScreen.kt`（PV-1 在改）、
`PresetDetail.kt`、`FrameRenderer.cpp` / `.h`（光晕卡）、`EditorExport.kt`（预设数据层）、
`BuiltinPresets.kt`、`CameraPresetApply.kt`、`CameraController.kt`、`assets/presets/`。

---

## 3. 验收判据逐条对照

| # | 判据 | 落点（代码引用） | 单测 |
|---|---|---|---|
| 1 | 「+ 新建预设」常驻可见；能填名称并用**当前相机参数**保存 | `CameraPresetPanel.kt` 的 `CameraPresetTab` 顶部 Row（`"+ 新建预设"`，实心 accent，`onTap = { openNew() }`；`openNew()` 预填 `defaultPresetName(presets)`）；保存走 `CameraScreen.kt:saveCameraPreset` → `newCameraPreset(name, group, film = cameraPresetParams(selectedPreset), …)` + `PresetStore.upsert` + `mergePresets` | `newPresetIdentityAndLutRules`、`newStateOffersSaveOnlyAndDefaultNameIsUsable` |
| 2 | 每行「⋯」菜单；内置受限项**置灰可见** + 一句实话 | `CameraPresetRow` 行尾 `"⋯"`（ChipBg + 描边，**所有行都渲染**）；菜单 = `presetMenuItems(p, paramsReady = true)` 六项全渲染、内置四项 `enabled = false`（`CamDisabledInk` + 不注册手势）；`presetReadOnlyNote(p)` = "内置预设只读：可套用 / 查看配置，不能编辑、重命名、复制或删除" | `builtinMenuKeepsDisabledItemsVisible`、`userMenuIsFullyEnabled` |
| 3 | 每行**参数摘要**（真实内置预设的实际输出） | `CameraPresetRow` 第二行 = `presetSummaryText(p, lutLabel = LutLibrary.byId(context, id)?.label)` | `summaryOfLeicaMomentIsExact` / `summaryOfRicohNegativeIsExact` / `summaryOfLeicaBwIsExact`（逐字，见 §4）、`summaryReadoutsMatchDetailRowsForEveryBuiltin` |
| 4 | 编辑面板**一步可达**，能改名称/分组 + 「从当前相机参数更新」 | 菜单 `EDIT` / `RENAME` / `VIEW` → `openEdit(p)` → `CameraPresetEditorPanel`（名称 / 分组输入 + `CamPresetParamList` 完整清单 + 「从当前相机参数更新」`updateCameraPreset` / 「保存名称 / 分组」`renameCameraPreset` / 「删除」） | `userEditorEnablesEveryAction`、`builtinEditorStillShowsItsFullParamList` |
| 5 | 删除二次确认；内置不可删；删除后内置三条不消失 | 菜单 `DELETE` → `pendingDelete` → `AlertDialog`（"「X」将被删除，无法恢复。\n当前画面与相机参数不受影响。"）；`deleteCameraPreset` 走 `mergePresets(PresetStore.delete(...), BuiltinPresets.load(context))` | `deletingAUserPresetKeepsAllBuiltins`、`everyPresetListRefreshInCameraScreenCarriesTheBuiltinPackage`、`builtinEditorDisablesUpdateFromCurrent` |
| 6 | 构建 + 单测真跑退出码 0 + 用例数；mutation 证据 | §6 | — |
| 7 | 本报告 | 本文件 | — |

### 3.1 保留的既有能力（逐条没回退）

- **P-2a 套用（含 LUT 四态）**：`onApply` → `applyCameraPreset`（`CameraScreen.kt`）**一个字没改** ——
  不含 LUT ⇒ `KEEP` / 在库 ⇒ `APPLY` / 缺失 ⇒ **清空 + 提示** / 「标准」⇒ 清空。
  本卡只是把"点卡片"换成"点整行"，入口变多（行 + 菜单「套用」），语义不变。
- **U-7 曝光偏移**：套用路径仍走 `presetEvOffset(p.slots)`（未改）；**新建 / 更新永不写 `[0]`**
  （`cameraPresetSlots` = `presetSparseSlots(film) - 0`）。
- **P-2c 内置只读**：判据仍是 `presetDeletable`（`presetMenuItems` / `presetReadOnlyNote` /
  `cameraPresetEditorButtons` / 五条写路径的守卫全部读它），本卡**没有第二处** `source == "builtin"` 比较。
- **P-5 字段作用域提示**：`presetScopeNotice(selected, PresetScene.CAMERA)` 原样保留（列表下方一行）。
- **P-2c 列表刷新纪律**：五条写路径**一律** `mergePresets(PresetStore.xxx(...), BuiltinPresets.load(context))`
  —— 只赋 `PresetStore` 的返回值会让**内置三条当场消失**（P-2c 踩过的坑）。

---

## 4. 实际输出（真实 `assets/presets/builtin.json`，逐字）

列表行第二行显示的就是这些字符串（`presetSummaryText`，摘要与编辑面板的清单读数**同源**）：

| 预设 | `slots.size` | 列表行摘要（逐字） |
|---|---|---|
| 徕卡一瞬 | 17 | `对比度 -3 · 饱和度 · 中间调 +5 · 胶片曲线 · 肩部 红 0.34 · 颗粒 · 强度 20 · … · LUT leica_m9 30` |
| 理光负片 | 14 | `对比度 +6 · 饱和度 · 暗部 +15 · 胶片曲线 · 肩部 绿 0.00 · 颗粒 · 强度 30 · … · LUT ricoh_negative 30` |
| 徕卡黑白 | 12 | `对比度 +9 · 胶片曲线 · 肩部 红 0.66 · 颗粒 · 强度 40 · 胶片曲线 · 肩部 绿 0.00 · … · LUT leica_b_w_hc 100` |

两条口径说明（都是**实测**，不是推导）：

1. **`slots.size` 比 JSON 的键数少**：`grain_size: 400` 与 `vignette_radius: 80` **恰好等于恒等值**
   （`CameraPresets.standard()[27] = 400`、`[67] = 80`）⇒ 被解析器的"恒等值不入库"洗掉
   （`parsePreset`：`abs(d - neutral[idx]) <= PARAM_EPS`）。这不是丢数据 —— 等于恒等值 = 套用时保持当前值，
   与"记下来"逐位等价。理光负片少 1 个（`grain_size`）、徕卡黑白少 2 个（`grain_size` + `vignette_radius`）。
2. **摘要是"分组轮转"挑的 4 项**（每组先取第一项）⇒ 四个分组都有代表，用户最认的"颗粒 · 强度"不会被
   槽位顺序挤掉；LUT 行不占这 4 个额度。读数与编辑面板清单**逐字相同**（唯一维护点 = `presetDetailSections`，
   单测 `summaryReadoutsMatchDetailRowsForEveryBuiltin` 钉住）。

---

## 5. 两处本卡新增的纪律（都留了护栏）

### 5.1 `[0]` 曝光**永不进预设**（U-7 的"拍摄态"纪律）

`cameraPresetSlots(film) = presetSparseSlots(film) - CAMERA_PRESET_EV_SLOT`。

拍照界面的"当前曝光" = 用户基准 + 用户 EV 补偿 + 预设偏移（`effectiveExposureEv`），其中**用户的 EV 补偿是
拍摄态不是风格**（`preset-DESIGN.md` §9.4）—— 写进预设的后果是"用户下次套用这条预设莫名多出一次曝光"，
而且他调 EV 的当下就污染了预设库。

**代价（如实记录）**：预设**自己**的曝光偏移也一并不会被保存 —— 拍照界面这一层拿到的是"已展开的当前生效
参数"（`cameraPresetParams`），无法从中分辨"这一槽是预设自带"还是"用户刚调的"。宁可少存一个槽，
也不把拍摄态写进风格；想保留曝光偏移的配方在编辑器侧维护（那边 `[0]` 是**后期曝光**，语义不同）。

### 5.2 内置预设的「从当前相机参数更新」/「保存名称 / 分组」必须**置灰**

`presetEditorButtons` 在**编辑态**只按 `paramsReady` / 名称非空决定这两项，**没查可删性** ⇒ 内置预设的
两个按钮会是**亮的**（`updatePreset` / `renamePreset` 内部虽有 `presetDeletable` 守卫，用户看到的是两个
点了没反应的按钮 —— ADR-12 明令禁止的假按钮）。本卡用 `cameraPresetEditorButtons` 按**同一条判据**修正，
内置预设仍可用的两件事：**套用**、**看配置**（菜单「查看配置」进同一个面板，清单照常渲染 12 项）。

### 5.3 不做"面板里逐项拖滑杆"（按架构师划定；若将来必须，方案与代价如下）

**不做的理由**：调参是参数面板的职责；拍照界面这一层**根本没有风格参数滑杆**（`cameraPresetParams` 的
KDoc 已定这条口径：参数数组的唯一来源就是预设）。面板空间也只够一件事 —— 编辑面板已经占掉
标题 + 输入行 + 清单（限高 150dp）+ 按钮行 ≈ 330dp，再塞 20+ 条滑杆会把底部弹出层变成全屏浮层。

**若必须做**（例如用户后续要求"在预设面板里微调颗粒"）：
- 方案 A（推荐）：**在编辑面板里放"滑杆子页"** —— 点清单某一行 → 进一个只含该参数的行内滑杆（复用
  `RelativeSlider`），松手写回 `slots` 的对应槽 + `PresetStore.upsert`。代价：多一层导航（约 +120 行 UI），
  且"预设里的值"与"当前相机参数"必须明确谁是事实源（否则用户调完滑杆不知道改的是预设还是画面）。
- 方案 B：把参数面板（编辑器那套 20+ 滑杆）整块搬进拍照界面。代价：底部弹出层 → 全屏浮层（违背
  LutSheet 的既有层级语言），且拍照界面当前**没有**把参数下发给引擎的通路（`applyCameraPreset` 是
  "整条预设 → 参数数组"的单向路径，需要新增"单槽 → 引擎"的实时通道），风险与工作量都在 1 张卡以上。

---

## 6. 构建 / 单测 / mutation 证据

### 6.1 真跑（非 `FROM-CACHE`）

```
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
...
> Task :app:assembleDebug
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 24s
46 actionable tasks: 46 executed          ← 全部 executed（无 FROM-CACHE）
```

- **退出码 0**；`app/build/test-results/testDebugUnitTest/*.xml` 汇总：**46 个套件 · 496 个用例 · 0 失败 · 0 错误 · 0 跳过**
  （基线 **455**；本卡新增 17 个 → 工作区另有 PS-1（13）/ PV-1 等卡的在途用例）。
- 本卡新用例全绿：`CameraPresetPanelUiTest`（17 个，见 §3 表）。
- Kotlin 编译只有既有警告（`CameraController.kt` 的 deprecated / `CameraScreen.kt` 的 `Icons.Filled.VolumeUp` 等），
  本卡新增文件 **0 警告**。

### 6.2 mutation 自检（3 项，各自被捕获）

| # | 施加的破坏 | 期望变红的用例 | 实测 |
|---|---|---|---|
| M1 | `cameraPresetSlots` 去掉 `- CAMERA_PRESET_EV_SLOT`（`[0]` 重新入库） | `newPresetNeverCarriesTheExposureSlot` | ✅ FAILED（连带 `updateReplacesRecipeButKeepsIdentity` 也红 —— 更新路径共用同一个稀疏化） |
| M2 | `cameraPresetEditorButtons` 去掉内置只读修正（`return b`） | `builtinEditorDisablesUpdateFromCurrent` | ✅ FAILED |
| M3 | `deleteCameraPreset` 改成只赋 `PresetStore.delete(...)`（丢掉内置包合并） | `everyPresetListRefreshInCameraScreenCarriesTheBuiltinPackage` | ✅ FAILED |

三项同时施加时：`496 tests completed, 4 failed`（M1 命中 2 条）⇒ **每条护栏都真的会红**，不是摆设。
施加后已恢复（`grep -c MUTATION-M` = 0），随后重跑全量得到 §6.1 的绿。

---

## 7. 未做 / 存疑项（⚠ 未真机验证）

**明确未做**：
- **面板内逐项拖滑杆**（架构师划定；方案与代价见 §5.3）；
- **导入 / 导出预设**（E3-2，不在本卡范围）；
- **编辑器侧的降级**（PS-2b 在做：相册与编辑器只保留"套用"）。

**未真机验证（本卡只保证逻辑与编译，观感与手感必须在真机上过一遍）**：
1. **底部弹出层的高度预算** —— 列表限高 224dp、编辑面板清单限高 150dp，是按"与 LUT tab 共用一个高度
   预算"估的；真机上要确认：长预设名 / 长分组名换行后行高不跳、`LUT` tab 与「预设」tab 来回切时
   面板高度不剧烈变化。
2. **「⋯」的触摸热区** —— 现在约 12dp 横向 padding + 15sp 字形（≈44×28dp）；真机单手拇指要能稳定点中，
   若偏小就加 `minimumInteractiveComponentSize` 或加大 padding。
3. **深色底对比度** —— `CamDisabledInk`（`CreamDim` × 0.35）在 `FilmTheme.Panel` 上的灰项是否**仍可读**
   （这是"置灰但可见"的关键：看不清 = 变相隐藏）。
4. **编辑面板在窄屏 / 大字体下**：三个中文按钮走 `horizontalScroll`，确认不会被裁；名称 + 分组两个输入框
   各占一半宽，中文分组名是否够用。
5. **删除当前选中预设后的状态**：本卡的口径是"**只动 prefs 与列表**，当前画面 / 参数 / 选中态一位不动"
   —— 删掉的若是当前选中项，顶栏仍显示它的名字（画面与它一致），冷启动 `resolveCameraPreset` 找不到 id
   会回退「标准」并清掉脏 id（既有机制）。真机上要确认用户不会把"顶栏还写着它"误读成"没删掉"；
   若误读明显，改法是删除后顺手 `applyCameraPreset(CAMERA_STANDARD_PRESET)`（但那会**动画面**，
   与架构师口径冲突 ⇒ 需架构师裁决）。
6. **`mergePresets` 刷新后的列表滚动位置**：`LazyColumn` 用 `key = { it.id }`，保存新预设后它会插到
   **列表最前**（`PresetStore.upsert` 的语义），确认滚动位置与"新条目在顶部"的预期一致。

**存疑 / 待架构师确认**：
- **`[0]` 剔除的代价**（§5.1）：如果将来内置预设要带曝光偏移（现在三条都不带），拍照界面这一层
  保存出来的副本会**丢掉那个偏移** —— 届时应改成"从 `selectedPreset.slots[0]` 取预设自带的偏移、
  只剔除用户 EV 部分"，而不是继续整槽剔除。

**与本卡无关但同批出现的红（已确认非本卡引起）**：
- 中途一次运行里 `PresetPanelUiTest.everyPresetListRefreshCarriesTheBuiltinPackage` 曾失败
  （它扫 `EditorScreen.kt` 的 `presets = …` 赋值要求 ≥ 6 条，而 PS-2b 把编辑器侧的写路径降级后只剩 3 条）。
  该文件不在本卡改动范围（PS-2b 在改）；**在最终那次全量运行里它已经绿了**（PS-2b 已同步更新该哨兵阈值）。

---

## 8. 真机验收清单（建议按此顺序过一遍）

1. 打开拍照界面 → 底部面板 → 「预设」tab ⇒ 看到 **「+ 新建预设」**、5 行列表（标准 + 用户 + 内置三条）、
   每行**参数摘要**（§4 的逐字文案）。
2. 点「+ 新建预设」⇒ 面板变成"新建预设"，名称框**已预填**「我的预设 1」、清单显示**当前相机参数的稀疏集**
   ⇒ 直接点「保存当前相机参数为新预设」⇒ 列表顶部出现新条目（内置三条仍在）。
3. 点新条目的「⋯」⇒ 六项全亮；点「编辑」⇒ 改名 + 改分组 ⇒ 「保存名称 / 分组」⇒ 列表行跟着变。
4. 套一个预设（点行）⇒ 画面变化 + 该行高亮；再点「⋯」→「从当前相机参数更新」⇒ 清单内容变成当前参数
   （**注意：画面不变** —— 改的是预设库）。
5. 内置预设（如「徕卡一瞬」）点「⋯」⇒ `编辑 / 重命名 / 复制 / 删除` **灰但可见** + 底部一句实话；
   点「查看配置」⇒ 能看完整 17 项清单（只读）。
6. 用户预设点「⋯」→「删除」⇒ 二次确认 ⇒ 确认后列表少一条、**内置三条仍在**、**画面不变**。
7. 切到「LUT」tab 再切回「预设」⇒ 面板正常（编辑面板回到列表态）。
8. 冷启动 App ⇒ 上次选中的预设仍生效（`camera_preset_id`），删掉过的那条不会残留。
