# PS-3 拍照界面预设【可查看 + 可逐项修改 + 内置可改】—— 实现报告

> 卡号 PS-3 ｜ 用户 2026-10-11 v0.11.18 真机实测后（**两轮**）：
> ① "当前预设管理做的很差。参考截图——Filmix 相机，**可直接在拍照界面查看、修改预设**。或参考 PC **长按跳转预设配置详情**。"
> ② "预设还有 2 个奇怪的地方：**内置预设为什么不让修改？我觉得没有必要，最多限制不能删除就行**。还有**新建预设时目前参数居然不可配置？？（参数点了没反应）**好好参考开源项目是怎么做预设的。**不要做个半成品给我**"
> ③ 补充："**预设应该展示全部可调参数，用户可编辑和新增预设**"（P0 = 能调 + 内置可改 + 恢复默认 + 保存口径；P1 = 全量参数表 + 未设置态 + 移除该项 + 分组折叠）
> 状态：**P0 + P1 全部落地**、构建 + 单测真跑通过（见 §9）｜ ⚠ **未真机验证**（Compose 绘制层与拖动手感 JVM 测不到，见 §11）
> 改动文件：`ParamControls.kt`（**新建**）、`CameraPresetPanel.kt`、`CameraPresetApply.kt`、`BuiltinPresets.kt`、`PresetDetail.kt`、`CameraScreen.kt`、`EditorScreen.kt`（**只删搬走的定义**）、6 个既有测试、`CameraPresetEditTest.kt`（**新建**）、本报告

---

## 0. 一句话

拍照界面的预设详情从"**只能看**"变成"**能改**"：参数表是**全量**的（拍照链路可调的 35 项一个不少，按四组**可折叠**），每一项**点开就是滑杆**（拖动**取景实时生效**），已包含的项可「移除该项」、未包含的项显示「**未设置**」（**绝不用当前相机值冒充**），改完「保存到预设」；**新建预设同样能逐项调**（用户实测的"点了没反应"就是这块）；**内置预设也能改**（唯一限制 = 不能删除），改过之后有「**恢复默认**」回到出厂值；预设行**长按**直接进详情。滑杆与参数表**不是照抄** —— `EditorParam` / 三组参数表 / `EditorSlider` 已从 `EditorScreen.kt` **逐字搬移**到 `ParamControls.kt`，两页引用同一个对象。

---

## 1. 改了什么

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/ui/ParamControls.kt` | **新建**（577 行） | ★ 从 `EditorScreen.kt` **逐字搬移**：`TextShadow` / `EditorParam` / `AdjustParams` / `ColorParams` / `LutStrengthParam` / `TextureParams` / `EditorSlider`（**只改可见性 `private`→`internal` 与文件位置**，数值 / 顺序 / label / format / 注释一行未动）；新增 `CAMERA_NO_SLIDER_SLOTS` + `CAMERA_TUNABLE_SLOT_PARAM`（槽位 → 滑杆的索引，**只有归属关系，没有第二份数值**） |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | 修改（−429 行） | **只删**搬走的那三段定义（`git diff` = 429 行纯删除，无一行新增）；其余引用点逐字未动 |
| `app/src/main/java/com/filmkit/app/camera/CameraPresetApply.kt` | 修改 | `CAMERA_PATH_SLOTS`（拍照链路 41 槽，**唯一维护点**，原在 `CameraStyleWhitelistTest` 里）；`cameraPresetParams(preset, overrides, removed)`（先摘移除项、再叠加改值）；`effectivePresetEvOffset(slots, overrides)`（`[0]` 的生效偏移） |
| `app/src/main/java/com/filmkit/app/camera/BuiltinPresets.kt` | 修改 | ★ **内置覆盖层**：`BuiltinOverrides`（prefs `builtin_overrides` 的 load / save / clear）+ 纯内核 `applyBuiltinOverrides`（只取用户可改字段，**身份取出厂**）；`presetDeletable` 的口径收窄为"只能不能删" |
| `app/src/main/java/com/filmkit/app/ui/PresetDetail.kt` | 修改 | ★ 口径：`presetMenuItems` 新增 `RESTORE`、编辑/重命名/复制放开；`presetReadOnlyNote` 文案改写；`presetEditorButtons` 新增 `restore` 字段、内置不再置灰更新/改名；**新增** `presetDetailRowOf` + `cameraParamSections`（全量参数表，复用 `DETAIL_META` / `SECTION_ORDER`） |
| `app/src/main/java/com/filmkit/app/ui/CameraPresetPanel.kt` | 修改 | 纯逻辑：`CameraParamOverrides`（带归属 id + `values` + `removed`）/ `forPreset` / `afterEdit` / `removeSlot` / `cameraPresetSlotsFor` / `newCameraPresetFrom` / `cameraParamAdjustability`；UI：**全量参数表**（分组折叠 + 未设置态 + 移除该项 + 展开滑杆）+ 行**长按进详情** + 「恢复默认」按钮；**删除** `cameraPresetEditorButtons`（直通 wrapper，口径变更后无价值） |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | 修改 | `paramOverrides` + `builtinOverrides` 两个状态；`previewCameraParams`（拖动实时下发，**不写 state**）；`persistPreset`（**唯一写出口**：用户 → `PresetStore`，内置 → 覆盖层）；`saveCameraPreset` / `saveCameraPresetSlots` / `updateCameraPreset` / `renameCameraPreset` / `restoreBuiltinPreset`；`applyCameraPreset` 里**切预设清空改动** |
| `app/src/test/.../CameraPresetEditTest.kt` | **新建** | **17 用例**（§8） |
| `CameraStyleWhitelistTest` / `EditorU6bParamUiTest` / `EditorHaloGlowUiTest` / `PresetDetailTest` / `PresetPanelUiTest` / `CameraPresetPanelUiTest` | 修改 | 抽取源改到 `ParamControls.kt`（判据未变）；菜单/按钮口径断言按新裁决更新（**逐条理由见 §8.2**）；写路径哨兵 5→7→3（§8.2） |

### 1.1 五条关键设计决策

1. **改动集带归属 id**（`CameraParamOverrides(presetId, values, removed)`）：生效值一律经 `forPreset(preset)` 取，**id 不匹配 ⇒ null** ⇒ "切预设必须清空"是**结构性保证**（`applyCameraPreset` 里另有显式清空，双保险）。
2. **拖动不写 Compose state**：拖动每帧只调 `previewCameraParams`（直通 `controller.setFilmParams`），松手才由面板把最终值提交进 `paramOverrides`（一次）。写 `CameraScreen` 的 state 会把近 3000 行的函数体每帧重组一遍（v0.10.96 的 LUT 强度滑杆踩过同型坑）。拖动中的中间值放在**普通对象** `DragHolder`（不参与快照）。
3. **保存口径分两条**：旧的 `cameraPresetSlots(film)`（从"已展开的相机数组"构造）**仍然丢弃 `[0]`**（U-7 纪律不变）；新的 `cameraPresetSlotsFor(preset, overrides, removed)`（从"预设记录 + 用户明确的改动"构造）**保留 `[0]`**。
4. **编辑对象 == 生效对象**：进详情（长按 / 「查看配置」/「编辑」）时若该预设不是当前生效的那条，**先套用它**（`openEdit` → `onApply`）。画面只有一份，"实时效果"必须有明确归属；否则会出现"面板在编辑 A、画面是 B、保存写回 A"的三方不一致。副作用：**长按查看配置会改变当前画面**（= 套用该预设，含 LUT 四态）。
5. **内置改动走覆盖层**（不写进 `PresetStore`，也不改 assets 常量）：出厂定义保持"随包分发、可升级"，用户的修改按 id 存进 `prefs("builtin_overrides")`，加载时叠加 ⇒ ① 版本升级后**未被改过的内置跟着更新**；② **改过的保留用户版本**；③ 「恢复默认」= 删掉覆盖 ⇒ 立刻回到出厂。身份字段（id / source / createdAt）**永远取出厂值** ⇒ 手改 prefs 也绕不过删除保护。

---

## 2. 详情面板的形态（对齐参考截图 + 用户第 ③ 轮补充）

```
‹ 返回  编辑预设                     内置 · —
[预设名称]        [分组（可空）]
点一项开始调整 · 拖动即时生效（未保存不写入预设）      ← 截图「选择一项开始调整」+「实时效果 开」
┌───────────────────────────────────────────────┐
│ ▾ 基础调整                            4/9      │  ← 分组可折叠 + "这组包含了几项"
│   曝光                        +0.30 EV     ▾  │  ← 点开（一次只展开一项）
│        +0.30 EV                                │
│        ──────────●──────────                   │  ← EditorSlider（与编辑器同一条控件）
│        [移除该项]                              │  ← 已包含的字段才谈得上移除
│   对比度                          -3       ▸  │
│   色温                        6500K        ▸  │
│   …                                            │
│ ▸ 色彩                                0/4      │
│ ▸ 影调曲线                            3/8      │
│ ▸ 胶片质感                            4/10     │
│   颗粒 · 强度                      20          │  ← 灰显（拍照生效但无滑杆）
│     拍照生效 · 暂无滑杆                        │  ← 原因（ADR-12：不做假滑杆）
└───────────────────────────────────────────────┘
[保存到预设] [从当前相机参数更新] [保存名称 / 分组] [恢复默认] [删除]
```

- **全量参数表**：数据源 = `cameraParamSections(slots, tunable + 预设里实际包含的不可调项)` ——
  可调的 35 项一个不少（**未包含的显示「未设置」**），另外把"这条预设里实际存着的不可调项"也列出来（灰显 + 原因），用户不会遇到"预设里有东西、界面上找不到"。
- **未设置 ≠ 当前值**：`CameraParamRow.value == null` ⇒ 显示「未设置」。套用这条预设时该字段**保持不动**，显示当前相机值会让用户以为预设里存着它（那是在骗用户）。
- **分组折叠**：默认展开"这条预设已包含项所在的分组"，其余收起（面板限高 150dp，全展开放不下）；点分组标题随时切换。
- **一次只展开一项**：`openKey` 记 `param.key`（不是槽号 —— 一条滑杆覆盖多槽时点哪一行都是同一条滑杆）。
- **读数与量程与编辑器同一套**：滑杆就是 `EditorSlider(param, …)`，`param.read` / `param.write` 直接操作 96 槽数组。
- **LUT 强度不在这里**：`[34]` 由 `lut.strength` 承载，仍走 LUT tab 的强度滑杆（既有分工，未改）。

---

## 3. 参数表提取的**零回归证据**

搬移纪律：**只改可见性与文件位置**。证据四层：

1. **diff 形状**：`EditorScreen.kt` 的改动 = **429 行纯删除**（`git diff --stat` 显示 `429 -----`），没有一行改写。
2. **搬移内容逐字**：`ParamControls.kt` 的三段由脚本按行区间从原文件提取 + 仅替换 7 处 `private ` 关键字生成（非手抄）。
3. **编辑器侧参数表哨兵全绿**（它们**正则解析参数表源码**，不是副本）：
   - `EditorU6bParamUiTest`：`AdjustParams` 的**完整顺序**（15 条）、`ColorParams` 顺序、5 条滑杆读的槽位；
   - `EditorHaloGlowUiTest`：质感组 10 条的 `key` / `label` / `range` 常量名 / `pivot` 常量名 / `read` 槽 / `write` 槽逐条对拍；
   - `PresetDetailTest.detailLabelsReuseEditorPanelWording`：详情页中文名必须复用面板 label；
   - `EditorReadoutTest`：读数口径总表（硬编码副本，未受影响，仍绿）。
4. **搬移的必要性验证**：修复前这三个哨兵**红了 6 条**（在 `EditorScreen.kt` 里再也找不到参数表）—— 证明它们是"参数表还在不在"的机器判据，不是摆设。

> ⚠ 诚实标注：第 3 条里三个测试**改了抽取源路径**（搬移的连带改动，判据与断言值未变）。不改路径必然失败（原始失败输出见 §9.1）。

---

## 4. 保存口径

### 4.1 `[0]` 曝光（那个必须处理的坑）

| 场景 | 函数 | `[0]` |
|---|---|---|
| 改动合成（画面） | `cameraPresetParams(preset, overrides, removed)` | `overrides[0]` 优先于 `slots[0]` |
| 下发（引擎） | `effectivePresetEvOffset(slots, overrides)` → `setFilmParams(p, evOffset)` | `overrides[0] ?: slots[0] ?: 0.0` |
| 保存回预设 | `cameraPresetSlotsFor(preset, overrides, removed)` | **保留**，值是**偏移量本身**（不是 `base + userEv + 偏移`） |
| 旧路径（无预设上下文） | `cameraPresetSlots(film)` | **仍丢弃**（U-7 纪律不变） |
| 拖回 0 | 稀疏化 | `[0]` 从 slots 消失（= 不改变曝光，不是"打到 0"） |

三条纪律同时成立：**调整能存上**、**拍摄态进不了预设**（用户 EV / 机型基准曝光活在 `CameraController` 内部，本层看不到）、**缺失 = 不改变**。

### 4.2 「移除该项」与稀疏子集

`CameraParamOverrides.removed` 是"这条预设不再包含某字段"的载体：
- **画面**：`cameraPresetParams` 先 `slots - removed` ⇒ 该槽回基线（用户立刻看得到"这项没了"）；
- **保存**：稀疏化自动丢掉它 ⇒ 该预设不含这个字段（套用时保持不动）；
- **撤销**：给同一个槽设值 ⇒ `afterEdit` 把它从 `removed` 划掉（用户刚设了值，预设显然又包含它）。

---

## 5. 内置预设：可改、不可删、可恢复（★ 变更 1）

| 操作 | 内置预设 | 用户预设 | 判据 |
|---|---|---|---|
| 套用 | ✅ | ✅ | `paramsReady` |
| 查看配置 | ✅ | ✅ | 恒 true |
| 编辑 / 重命名 | ✅ **（旧口径：灰）** | ✅ | `paramsReady` |
| 复制 | ✅ **（旧口径：灰）** | ✅ | 恒 true |
| **恢复默认** | ✅ **（新增）** | ❌ 置灰（无出厂定义） | `!presetDeletable(p)`（UI 再叠加"改过才可点"） |
| **删除** | ❌ **（唯一限制）** | ✅ | `presetDeletable` |

- **写路径分流**（`CameraScreen.persistPreset`）：用户预设 → `PresetStore.upsert`；内置 → `BuiltinOverrides.save`；刷新一律 `BuiltinPresets.all(context, prefs)`（用户 + 出厂 + 覆盖）。
- **列表标记**：改过的内置在行上带「**已改**」小标（否则用户不知道"这条已不是出厂值"）。
- **「恢复默认」**：删掉覆盖 ⇒ 重新加载 ⇒ 若是当前生效那条则走完整套用路径（参数 + LUT 四态 + 清空未保存改动），不会出现"界面显示出厂值、画面还是改过的"。
- **被推翻的旧口径**（PS-1 / P-2c）："内置整条路径只读"（`EDIT` / `RENAME` / `COPY` 全灰、`cameraPresetEditorButtons` 强制把更新/改名置灰）。用户真机实测后明确否掉 —— 多绕两步不是保护，是障碍。

---

## 6. 哪些参数在拍照界面**灰显**及原因（ADR-12：不做假滑杆）

判据 = `cameraParamAdjustability(slot)`：**先看拍照链路读不读**（`CAMERA_PATH_SLOTS`，引擎实测 41 槽），再看有没有滑杆。

| 类别 | 槽位 | 界面表现 | 原因 |
|---|---|---|---|
| **可调**（35 槽） | `0` `1..3` `4` `5..10` `20..22` `23..25` `62..65` `66..68` `78` `88..94` `95..97` | 可点 + 「▸」展开滑杆 | 引擎读 + 参数面板有滑杆 |
| **拍照生效 · 暂无滑杆**（6 槽） | `26` 颗粒·强度 / `27` 颗粒·尺寸 / `60` 颗粒·中间调偏置 / `61` 颗粒·枢轴 / `37` 黑点 / `38` 白点 | 灰显 + 「拍照生效 · 暂无滑杆」 | 引擎**读**它们（E4-3 / U-2），但**参数面板从未给过滑杆**（编辑器也调不到）—— 既有欠账，如实标注而不是造一条没有引擎语义对应的滑杆 |
| **仅编辑器生效 · 拍照不变**（18 槽） | `11..19`（`cross[9]` log 域染料串扰）、`69..77`（`interlayer_*[3]` 层间效应 R/G/B） | 灰显 + 「仅编辑器生效 · 拍照不变」 | 能进预设（`presetSlotIncluded`）但**不在拍照链路白名单里**（`passFilm` / `passOutput` 零引用）⇒ 拖了取景画面不会有任何变化 |

> 影响面（如实）：随包分发的内置三条（`builtin.json`）**不含**这 18 槽里的任何一个 —— 只有导入的预设或手改 JSON 才可能带上它们。标注仍然要做：用户看到一行值却调不动，必须知道为什么。

> ⚠ **发现一处既有判据表的欠账（本卡未改，如实记录）**：`PresetScope.kt` 的 `SLOT_SCENES` 是空表 ⇒ `presetScopeNotice` 恒返回 `null`（"两侧都生效"）。但引擎实测显示 `11..19` 与 `69..77` 共 18 槽**只有编辑器链路读**。本卡用 `CAMERA_PATH_SLOTS` 在拍照界面逐项标注了这件事，**没有**去动 `PresetScope`（那会牵动三个入口与文档 §1.6 的对拍）。建议另开一张卡补齐。

---

## 7. 用户流程逐步骤自检（★ 变更 3：不许半成品）

> 要求："把下面这条流程**逐步骤在报告里写明「能走通 / 没做完」**"。

| # | 步骤 | 状态 | 依据 |
|---|---|---|---|
| 1 | 点「+ 新建预设」 | ✅ 能走通 | `openNew()`：预填默认名（`defaultPresetName`），保存按钮一路可点 |
| 2 | 改名称 / 分组 | ✅ 能走通 | `CamPresetField`（既有）；改名后 `renameCameraPreset` 走 `persistPreset` |
| 3 | **点任意一项参数 ⇒ 滑杆出现** | ✅ 能走通 | 新建态 `target = selected` ⇒ `params = cameraPresetParams(target, values, removed)` 恒非 null（源码哨兵钉住，见 §8） |
| 4 | **拖动 ⇒ 取景画面跟着变** | ✅ 接线完成（⚠ 真机手感未验） | `onEditParam` → `afterEdit` → `onPreview` → `controller.setFilmParams`（不写 state，松手才提交） |
| 5 | 保存 ⇒ 列表出现新预设 | ✅ 能走通 | `saveCameraPreset` → `newCameraPresetFrom`（含 `[0]` 偏移）→ `persistPreset` → `PresetStore` |
| 6 | 套用它 ⇒ 参数与刚才一致 | ✅ 能走通 | `applyCameraPreset`（既有路径）+ 单测 `savingKeepsTheExposureOffsetTheUserDialedIn` |
| 7 | 再点开编辑 ⇒ 改一项 ⇒ 保存 ⇒ 生效 | ✅ 能走通 | `openEdit`（长按/菜单）→ 滑杆 → 「保存到预设」→ `saveCameraPresetSlots` |
| 8 | 对内置预设做同样操作 ⇒ **能改** | ✅ 能走通 | `persistPreset` 分流到 `BuiltinOverrides`；菜单/按钮全部放开（`presetMenuItems` / `presetEditorButtons` 的新口径 + 单测） |
| 9 | 点「恢复默认」⇒ 回到出厂值 | ✅ 能走通 | `restoreBuiltinPreset`：清覆盖 + 重新加载 + 若为当前生效条则完整套用；按钮可用性 = 内置 && 改过 |
| 10 | 删除按钮：内置**置灰且有说明**、用户可用 | ✅ 能走通 | `presetDeletable`（唯一判据）+ `presetReadOnlyNote`（"内置预设不能删除（可改参数 / 名称 / 分组；改过之后可「恢复默认」）"） |
| 11 | （P1）展示全部可调参数 + 「未设置」+ 「移除该项」+ 分组折叠 | ✅ 已做 | `cameraParamSections` + `CamPresetParamList`；单测 `paramTableShowsEveryTunableSlotAndNeverFakesUnsetOnes` / `removingASlotDropsItFromBothThePreviewAndTheSavedRecipe` |
| 12 | （P2）保存时按类别勾选 / 列表项用当前照片渲染小图 | ❌ **未做**（规格明确"有余力再说，别为它拖时间"） | 调研 §5.4 的这两条不在本卡范围 |

---

## 8. 单测

### 8.1 新增（`CameraPresetEditTest`，**17 用例**，纯逻辑）

| # | 用例 | 钉住的口径 |
|---|---|---|
| 1 | `overridesComposeOnTopOfThePresetRecipe` | overrides 优先 + 未拖的槽保持预设值 + 预设没记的槽保持基线 |
| 2 | `draggingBackToTheRecipeValueDropsTheOverride` | 拖回原值 = 撤销 |
| 3 | `multiSlotParamsRecordEverySlotTheyWrite` | 一条滑杆写几槽就记几槽（饱和度 `[20..22]`） |
| 4 | `overridesDieWithThePresetTheyBelongTo` | **切预设 = 清空**（结构性） |
| 5 | `savingKeepsTheExposureOffsetTheUserDialedIn` | **`[0]` 那个坑**：能存上 + 存的是偏移不是拍摄态 + 拖回 0 则消失 |
| 6 | `legacySlotsStillDropTheExposureSlot` | 旧口径 `cameraPresetSlots` **仍丢 `[0]`** |
| 7 | `saveAsCopiesTheRecipePlusTheOverrides` | `newCameraPresetFrom` = 新 id + 用户来源 + 配方 = 底 + 改动 + LUT 跟随 |
| 8 | `tunableSlotsAreExactlyTheCameraPathMinusTheSliderlessOnes` | **白名单过滤判据** |
| 9 | `everyTunableSlotIsBoundToItsOwnParam` | 每个可调槽与它的滑杆**真的绑定**（抓"键集合对但绑错槽"） |
| 10 | `everyNonTunableRowCarriesAReason` | 灰显项必须有原因；`69..77` 一律不可调 |
| 11 | `effectiveEvOffsetFallsBackToThePresetRecord` | 生效偏移回落链 |
| 12 | `cameraScreenWiresClearingAndLivePreview` | 源码哨兵：套用清空 + `previewCameraParams` 不写 state + 传 `evOffset` + 松手提交 + **编辑对象==生效对象** + **新建态可调** |
| 13 | `builtinOverridesKeepFactoryIdentitySoTheyStayUndeletable` | ★ **内置可改但不可删**：覆盖只改用户字段，id / source / createdAt 取出厂 |
| 14 | `unmodifiedBuiltinsFollowTheFactoryDefinition` | ★ **升级性质**：没改过的跟着出厂、改过的保留用户版本、野 id 忽略 |
| 15 | `removingASlotDropsItFromBothThePreviewAndTheSavedRecipe` | ★ P1：移除 ⇒ 画面回基线 + 保存后不含该字段 |
| 16 | `settingAValueAfterRemovalCancelsTheRemoval` | ★ P1：设值撤销移除 |
| 17 | `paramTableShowsEveryTunableSlotAndNeverFakesUnsetOnes` | ★ P1：**全量 35 项** + 分组顺序 + 未包含必须 `null`（不许冒充） |

### 8.2 既有测试的更新（**逐条理由**，不静默改）

| 测试 | 旧断言 | 新断言 | 理由 |
|---|---|---|---|
| `EditorU6bParamUiTest` / `EditorHaloGlowUiTest` / `PresetDetailTest` | 正则解析 `EditorScreen.kt` 的参数表 | 解析 `ParamControls.kt`（`private val` → `val` 前缀放宽） | **参数表逐字搬移**的连带改动；判据（顺序 / label / 槽位 / 量程 / pivot）与断言值**一字未改** |
| `CameraPresetPanelUiTest.builtinMenuKeepsDisabledItemsVisible` | 6 项，内置只有套用/查看可点；实话含"只读" | 7 项（+「恢复默认」），内置**只有删除**置灰；实话含"不能删除"+"恢复默认" | ★ 用户拍板"内置最多限制不能删除"（变更 1）—— 旧断言正是被推翻的那条口径 |
| `CameraPresetPanelUiTest.userMenuIsFullyEnabled` | "用户预设六项全可点" | 除「恢复默认」外全可点（用户预设没有出厂定义） | 同上：新增的「恢复默认」对用户预设无意义，按"置灰但可见"的既有风格处理 |
| `CameraPresetPanelUiTest.builtinEditorDisablesUpdateFromCurrent` → `builtinEditorIsEditableAndOnlyDeleteIsBlocked` | 内置的更新/改名必须置灰 | 内置的更新/改名**可点**、`restore` 可用、只有删除置灰 | ★ 同上；被推翻的 `cameraPresetEditorButtons` wrapper 一并删除 |
| `CameraPresetPanelUiTest.everyPresetListRefreshInCameraScreenCarriesTheBuiltinPackage` | 5 条写路径 →（PS-3 第一版）7 条 | **3 条**（`persistPreset` / `restoreBuiltinPreset` / 删除）+ 新增"分流 + 恢复默认"哨兵 | PS-3 起写路径统一到 `persistPreset`（用户 → `PresetStore`，内置 → 覆盖层），直接赋值点自然减少；哨兵的**意图**（每次刷新都带内置包）不变，并**加强**为"必须分流到覆盖层" |
| `CameraPresetPanelUiTest.cameraPanelRendersEveryCapabilityWithoutRewritingTheLogic` | 要求含 `presetDetailSections(` | 改为含 `cameraParamSections(` + `cameraParamAdjustabilityNote(` | 详情面板的参数表从"稀疏只读清单"升级为"全量可调表"（P1） |
| `PresetPanelUiTest.cameraMenuStillHasTheModifyActionsAndBuiltinOnesAreGreyedOut` | 6 项 + 内置只有两项可点 | 7 项 + 内置只有删除置灰 + 用户预设的「恢复默认」置灰 | 同 `builtinMenuKeepsDisabledItemsVisible` |
| `CameraStyleWhitelistTest` | 白名单常量写在测试里 | 引用生产常量 `CAMERA_PATH_SLOTS` | 拍照界面要按它决定灰显（判据不能只活在测试里）；三方对拍（引擎实际 == 常量 == §1.6）**不变** |

---

## 9. 验证证据

### 9.1 修复过程中的失败（真实输出，非编造）

| 失败 | 原因 | 处理 |
|---|---|---|
| `EditorHaloGlowUiTest` ×2、`EditorU6bParamUiTest` ×3、`PresetDetailTest.detailLabelsReuseEditorPanelWording` | 三个哨兵正则解析 `EditorScreen.kt` 的参数表 ⇒ 搬移后解析到 0 条 | 抽取源改 `ParamControls.kt`（判据未变） |
| `CameraPresetPanelUiTest.everyPresetListRefreshInCameraScreenCarriesTheBuiltinPackage` | 写路径条数变化 | 期望值同步（§8.2） |
| `PresetPanelUiTest.cameraMenuStillHasTheModifyActionsAndBuiltinOnesAreGreyedOut` | 菜单 6 → 7 项 | 期望值同步（§8.2） |
| `CameraPresetPanelUiTest.cameraPanelRendersEveryCapabilityWithoutRewritingTheLogic` | `presetDetailSections(` 不再出现在面板里（改用 `cameraParamSections`） | 哨兵列表同步（§8.2） |

### 9.2 mutation 自检（**5 处**，各自被捕获后已恢复）

见 §9.4 的原始输出。前两处针对"逐项调整"的核心口径，后三处针对本轮规格变更。

| # | 改坏什么 | 期望红的用例 | 实得 |
|---|---|---|---|
| M1 | `cameraPresetParams` 忽略 overrides | 合成 / 多槽 / 另存 / `[0]` | **4 条红** |
| M2 | `cameraPresetSlotsFor` 丢 `[0]` | `savingKeepsTheExposureOffsetTheUserDialedIn` | **1 条红**（精准） |
| M3 | `forPreset` 不按 `presetId` 判定 | `overridesDieWithThePresetTheyBelongTo` | **1 条红**（精准） |
| M4 | `applyBuiltinOverrides` 直接用覆盖对象（不保出厂身份） | `builtinOverridesKeepFactoryIdentitySoTheyStayUndeletable` | **1 条红**（精准） |
| M5 | `removeSlot` 不清理 `values` | `removingASlotDropsItFromBothThePreviewAndTheSavedRecipe` | **第一轮没红** ⇒ 断言太弱，已强化后重测：**1 条红**（见下） |

> **M5 的价值（如实记录）**：第一轮 mutation **没被抓住** —— 原用例从"没有改动"的状态开始移除，
> `values` 本来就是空的，测不出"移除要清掉那条改值"。这正是 mutation 自检的用途：暴露
> **断言不够强**。强化后的用例先 `afterEdit` 改值再 `removeSlot`，M5 立刻变红。

恢复后 `grep -rn MUTATION app/src/` → **无残留**。

### 9.3 全量结果

```
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 14s
27 actionable tasks: 27 executed        ← 非 FROM-CACHE，27 个任务全部真跑
EXIT=0
```

**测试报告统计**（`app/build/test-results/testDebugUnitTest/*.xml` 全量解析）：

| 指标 | 值 |
|---|---|
| 测试类 | **49** |
| 用例数 | **534** |
| 失败 | **0** |
| 错误 | **0** |
| 跳过 | **0** |

其中本卡新增的 `CameraPresetEditTest` = **17 用例**（12 条首版 + 5 条规格变更后新增）。

### 9.4 mutation 原始输出

五处改动**逐个施加 → 跑 `CameraPresetEditTest` → 记录 → 恢复**（恢复后 `grep -rn MUTATION app/src/` 无输出）。

**M1 —— `cameraPresetParams` 忽略 overrides**（`preset.slots + overrides` → `preset.slots`）：

```
> Task :app:testDebugUnitTest FAILED
CameraPresetEditTest > saveAsCopiesTheRecipePlusTheOverrides FAILED
CameraPresetEditTest > multiSlotParamsRecordEverySlotTheyWrite FAILED
CameraPresetEditTest > savingKeepsTheExposureOffsetTheUserDialedIn FAILED
CameraPresetEditTest > overridesComposeOnTopOfThePresetRecipe FAILED
12 tests completed, 4 failed
BUILD FAILED
```
```
saveAsCopiesTheRecipePlusTheOverrides :: java.lang.AssertionError: 微调确实进了新预设. Actual: 0.9741
multiSlotParamsRecordEverySlotTheyWrite :: java.lang.AssertionError: 确实改了. Actual: 1.05
savingKeepsTheExposureOffsetTheUserDialedIn :: java.util.NoSuchElementException: Key 0 is missing in the map.
overridesComposeOnTopOfThePresetRecipe :: java.lang.AssertionError: overrides 优先于预设的配方 expected:<0.800000011920929> but was:<1.2>
```

**M2 —— 保存时丢弃 `[0]`**（`cameraPresetSlotsFor` 末尾加 `- CAMERA_PRESET_EV_SLOT`）：

```
> Task :app:testDebugUnitTest FAILED
CameraPresetEditTest > savingKeepsTheExposureOffsetTheUserDialedIn FAILED
12 tests completed, 1 failed
BUILD FAILED
```
```
savingKeepsTheExposureOffsetTheUserDialedIn :: java.util.NoSuchElementException: Key 0 is missing in the map.
```

**M3 —— 改动不再按 `presetId` 归属**（`forPreset` 去掉 id 判定）：

```
> Task :app:testDebugUnitTest FAILED
CameraPresetEditTest > overridesDieWithThePresetTheyBelongTo FAILED
12 tests completed, 1 failed
BUILD FAILED
```
```
overridesDieWithThePresetTheyBelongTo :: java.lang.AssertionError: 换一条预设 ⇒ 空表（结构性清空）
```

**M4 —— 覆盖层不保出厂身份**（`applyBuiltinOverrides` 直接 `return@map o`）：

```
> Task :app:testDebugUnitTest FAILED
CameraPresetEditTest > builtinOverridesKeepFactoryIdentitySoTheyStayUndeletable FAILED
17 tests completed, 1 failed
BUILD FAILED
```
```
builtinOverridesKeepFactoryIdentitySoTheyStayUndeletable :: java.lang.AssertionError: ② createdAt 取出厂（内置没有「创建时刻」这个语义） expected:<0> but was:<1>
```

**M5 —— 「移除该项」不清理改值**（`removeSlot` 的 `values` 不清 `slot`）：

```
# 第一轮（断言太弱，没抓住 —— 如实记录）：
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL
# 强化用例（先 afterEdit 改值、再 removeSlot）后重测：
> Task :app:testDebugUnitTest FAILED
CameraPresetEditTest > removingASlotDropsItFromBothThePreviewAndTheSavedRecipe FAILED
17 tests completed, 1 failed
BUILD FAILED
```
```
removingASlotDropsItFromBothThePreviewAndTheSavedRecipe :: java.lang.AssertionError: 移除必须同时清掉那条改值（否则移除会被它覆盖回去 = 假控件）
```

> M2 / M3 / M4 各只打红**一条**用例 ⇒ 断言精准；M1 打红 4 条 ⇒ 覆盖合成 / 多槽 / 另存 / `[0]`
> 四条下游路径；M5 抓出了**一条不够强的断言**（已强化）—— 这就是 mutation 自检的意义。

---

## 10. 真机验收清单（建议按此顺序过一遍）

1. **新建流程（用户投诉的"点了没反应"）**：底部「预设」→「+ 新建预设」→ 参数表**每一项都能点开滑杆** → 拖动 ⇒ **取景立刻变** → 改名称 →「保存当前相机参数为新预设」→ 列表出现 → 点行套用 ⇒ 参数与刚才一致。
2. **长按进详情**：长按任意一行 ⇒ 进详情（点行仍是**套用**）。⚠ 长按一条**未选中**的预设时它会**先被套用**（画面 + 顶栏名字切过去，见 §1.1 第 4 条）—— 确认这个副作用不让人意外。
3. **编辑态逐项改 + 保存**：进详情 → 改一项 ⇒ 画面变 →「保存到预设」→ 返回再进来 ⇒ 值还在。
4. **「未设置」态**：找一个这条预设没包含的项（例如「褪色」）⇒ 显示**未设置**（不是当前值）⇒ 点开设值 ⇒ 它变成"已包含" ⇒ 「保存到预设」⇒ 该字段进了预设。
5. **「移除该项」**：对已包含的项点「移除该项」⇒ 画面回到该项的默认状态 ⇒ 保存 ⇒ 该项变回"未设置"。
6. **分组折叠**：点分组标题 ⇒ 收起 / 展开；默认只有"含已包含项"的组是展开的。
7. **内置可改（★ 用户第 2 点）**：长按内置「徕卡一瞬」→ 改对比度 →「保存到预设」⇒ 列表该行出现「**已改**」标；**套用它 ⇒ 画面是改过的**；退出 App 重进 ⇒ 改动**还在**（覆盖层持久化）。
8. **恢复默认**：上一步之后回详情 →「恢复默认」⇒ 回到出厂值（画面 + 读数都回去）；再重进 ⇒ 仍是出厂值。
9. **内置不可删**：内置详情的「删除」**置灰** + 一句实话；「⋯」菜单里「删除」也置灰（其余全亮）。
10. **切预设清空改动**：在 A 里改几项 ⇒ 返回 ⇒ 套用 B ⇒ 画面 = B 的配方（**不带 A 的改动**）；再进 A 的详情 ⇒ 改动**不在了**。
11. **曝光偏移那个坑**：详情里把「曝光」拖到 `+1.0 EV` ⇒ 保存 ⇒ 重新套用 ⇒ **画面确实亮了约 1 EV**，且用户自己的 EV 滑杆值**没被改写**（两者叠加）；拖回 `0` 保存 ⇒ 该预设不再改变曝光。
12. **灰显项**：`颗粒 · 强度` / `黑点` 等行**点不动**且带原因小字；导入一条含 `[69..77]` 的预设时那几行标「仅编辑器生效 · 拍照不变」。
13. **拖动性能**：连续快速拖动一条滑杆 ⇒ 读数不断档、取景无明显卡顿（本卡唯一无法在 JVM 验证的部分，见 §11）。
14. **不回归**：套用 / 重命名 / 复制 / 删除 / LUT 四态 / 冷启动恢复选中预设 —— 与 v0.11.18 逐条一致。

---

## 11. 未验证部分（⚠ 如实标注）

| 项 | 为什么 JVM 测不到 | 风险与兜底 |
|---|---|---|
| **拖动手感 / 取景延迟** | Compose 手势 + GL 渲染在真机 | 拖动每帧走 `controller.setFilmParams`（内部有 4 次 prefs 读 + 写 `[0]/[28]/[30]/[34]/[35]`）—— 与"套用预设"同一条路径，**未做过帧率实测**；若真机卡顿，下一张卡可加"拖动中只下发变化槽"的轻通道 |
| **面板布局 / 高度预算** | Compose 绘制层 | 参数表现在是**全量 35 项 + 分组折叠**，`maxHeight = 150.dp` 下同屏约 5–6 行；滑杆展开、移除按钮、分组计数是否会挤，需真机看 |
| **长按 vs 点行的手势冲突** | 真机触摸 | 用既有 `pressScale(onTap, onLongTap)`（`combinedClickable`），与 `SheetPill` 的长按同一实现；长按误触发套用需真机确认 |
| **`[0]` 在真机链路上的叠加** | 需要相机 HAL | 单测只覆盖"保存的值 = 偏移量"；"保存后套用画面确实亮 1 EV"要靠 §10 第 11 条真机确认 |
| **覆盖层的跨版本行为** | 需要"装旧版 → 改内置 → 装新版" | 纯逻辑已测（`unmodifiedBuiltinsFollowTheFactoryDefinition`）；真实的"升级后出厂配方变了、未改过的内置跟着变"需真机跨版本验证 |
| **改动在关面板后是否保留** | — | 设计上**保留**（改动活在 `CameraScreen` 的 state 里），直到切预设或保存 —— 真机需确认"调完直接按快门"拍出来的就是改动后的效果 |

---

## 12. 代价与遗留

1. **参数表搬移是一次"大 diff、零行为"改动**：`EditorScreen.kt` −429 行、`ParamControls.kt` +577 行。收益是拍照界面复用同一套滑杆（否则必然出现"两页读数不一致"）；代价是三个参数表哨兵要改抽取源（已改，判据未变）。
2. **「保存到预设」与「从当前相机参数更新」语义重叠**：两者在有改动时结果几乎相同（差别只有 `[0]` 与"是否整包重写 LUT"）。本卡保留两者（既有能力不回退），面板上写了一句话说明差别。若真机反馈"分不清"，下一张卡可把后者收进菜单。
3. **`CAMERA_PATH_SLOTS` 从测试搬到了生产代码**：UI 要按它决定灰显 ⇒ 必要；`CameraStyleWhitelistTest` 改为引用它，常量漂移仍被引擎/文档对拍抓住。
4. **`PresetScope` 的 18 槽欠账**（§6 末尾）未修 —— 本卡只在拍照界面逐项标注。
5. **P2 未做**：保存时按类别勾选（"选择哪些参数进预设"）、预设列表项用当前照片渲染小图 —— 规格明确"有余力再说"，本卡没做。
6. **`newCameraPreset` 在拍照界面不再被调用**（改走 `newCameraPresetFrom`），保留其契约与单测（旧口径的回归哨兵）。
7. **`cameraPresetEditorButtons` 已删除**：口径变更后它退化成直通 wrapper（无价值抽象）；`CameraPresetPanelUiTest` 的对应用例改为直接测 `presetEditorButtons` 的新口径。
