# PS-6b 完整参数编辑页 —— 报告

用户 2026-10-12（v0.11.21 真机实测）："点击**完整参数**跳转类似**图1**页面提供**完整、专业调节**，
做好**参数分组提高屏幕利用率**。"

## 1. 改了什么

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/ui/CameraPresetFullEdit.kt` | **新建**（663 行）：纯逻辑（分组 / 网格 / 重置 / 保存口径）+ 全屏覆盖层 UI |
| `app/src/main/java/com/filmkit/app/ui/CameraScreen.kt` | 接线：`fullEditOpen` state、`onOpenFullEdit = { fullEditOpen = true }`、全屏页挂载（LUT sheet **之上**）、`saveCameraFullEdit` |
| `app/src/test/java/com/filmkit/app/ui/CameraPresetFullEditTest.kt` | **新建**（199 行）：11 条纯逻辑 + 源码哨兵用例 |

**没改** `CameraPresetPanel.kt`（PS-6a 的地盘）、**没改** `ParamControls.kt`（只复用）、
`PresetDetail.kt` 也**没改**（分组与名字直接复用它的 `presetDetailRowOf`，不需要动口径）。

页面形态（照"图 1"）：顶栏 `← 编辑拍摄预设` + 右侧「保存」→ 顶部卡片（预设名 + LUT 分类 pill +
LUT 横向缩略图）→「滤镜强度」百分比滑杆 → 参数卡片（`[基础|高级]` + `⟳ 重置` + **一行 4 格**网格）
→ 卡片底部**一次一项**的滑杆条（`● 曝光` + 刻度滑杆）。

## 2. 与 PS-6a 的接口（已对齐，双方各自可编译）

PS-6a 给 `CameraPresetTab(...)` 加了 `onOpenFullEdit: () -> Unit = {}`（默认空实现），
我在 `CameraScreen.kt` 传实际实现：

```kotlin
CameraPresetTab(..., onOpenFullEdit = { fullEditOpen = true })   // CameraScreen.kt
```

签名在 PS-6a 的文件里、实参在我的文件里 ⇒ **没有"必须同时改同一个调用点"的耦合**。
（我原先准备的 `LocalFullParamEdit` CompositionLocal 方案在看到 PS-6a 的参数回调后**已删除**，
不留两条并存的路。）

## 3. 参数分组依据（复用唯一维护点，不造第二份）

- **每一项都是 `ParamControls.kt` 的 `EditorParam`**：网格、滑杆、读数、量程、槽位读写全部引用同一份表。
  哨兵断言 `CameraPresetFullEdit.kt` 里**不出现** `EditorParam(` / `_UI_RANGE` / `Slot(`。
- **分组 = `ParamControls.kt` 的三张表**：`AdjustParams`→「调整」、`ColorParams`→「色彩」、
  `TextureParams`→「质感」；末尾加一组「只读」。
- **基础档** = LR Classic「Basic」面板对应的 10 条（曝光 / 色温 / 色调 / 对比度 / 高光 / 阴影 /
  白色色阶 / 黑色色阶 / 饱和度 / 清晰度，`BASIC_PARAM_KEYS`）；判据是"LrC Basic 面板存在的滑杆
  ∩ 本引擎已有的参数项"，不是拍脑袋挑的。
- **高级档** = 全部**拍照链路可调**的参数项（`CAMERA_TUNABLE_SLOT_PARAM` 去重 = 29 条）+「只读」组。
- **只读组**（灰显 + 原因，ADR-12 禁止假控件）= `CAMERA_NO_SLIDER_SLOTS`（颗粒 4 + filmic 黑白点 2，
  note「拍照生效 · 暂无滑杆」）∪ **这条预设包含的、拍照链路不读的槽**（如 `[69..77]` 层间效应，
  note「仅编辑器生效 · 拍照不变」，与 `cameraTreeSlots` 同型判据）；`[34]` 排除（由顶部 LUT 卡片
  + 滤镜强度滑杆承载）。点只读格 ⇒ 底部显示原因（既不是假滑杆，也不是没反应）。
- 名字：可调项 = `EditorParam.label`；只读槽 = `presetDetailRowOf(...).label`（颗粒显示 `ISO 400`
  那种既有格式化），不新造译名。

## 4. 两条裁决

**「⟳ 重置」= 回到这条预设的原值**（丢弃本页全部未保存改动），**不是**中性基线、**不是**恢复出厂。

- 理由：进本页时画面 = 这条预设的配方。做成中性基线 ⇒ 用户点一下就把整条预设的调色抹平（不可逆）；
  "放弃我刚才的修改"才是编辑语境里这个按钮的含义。
- 机器判据：重置后 `cameraPresetParams(preset, values, removed)` 与 `cameraPresetParams(preset)`
  **逐槽相等**，且**不等于** `CameraPresets.standard()`（测试钉住）。
- 「恢复默认」（内置 → 删覆盖层回出厂定义）是另一件事，入口在预设列表「⋯」；本页不做，避免两个
  "重置"让用户分不清哪个抹掉多少。内置预设在本页**可改可保存**，只禁删（沿用既有口径）。
- 重置**顺手收起滑杆**：`EditorSlider` 的拖动 state 在它内部（`key(param.key)` 不变不重读），
  只清 state 不收起 = 滑杆停在旧位置（看起来"点了没反应"）。

**「保存」= 参数 + LUT 两字段**：复用 `saveCameraPresetSlots`（唯一实现：写 slots、内置走覆盖层、
列表刷新），随后按 `fullEditLutFields` / `fullEditLutChanged` 把当前 LUT 与强度写进这条预设
（没选 LUT ⇒ 强度回落 1.0，与 `newCameraPresetFrom` / `updatedCameraPreset` 同口径）。理由：图 1 顶部
卡片的第一个主角就是 LUT，用户在这里选 LUT 的意图必然是"这条预设的 LUT"——只存参数不存 LUT 就是
"选了存不上"的坑。保存后清 `overrides` + Toast（保存按钮当场置灰 = 诚实）。

其余：点格子 = 选中（再点取消）⇒ 底部出滑杆；切「基础/高级」清选中；「←」返回**保留**未保存改动
（与 PS-3 详情面板一致，改动仍活在 `paramOverrides` 里，随时可再存）。

## 5. 证据

```
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests "com.filmkit.app.ui.CameraPresetFullEditTest"
→ BUILD SUCCESSFUL；TEST-...CameraPresetFullEditTest.xml: tests="11" failures="0" errors="0"
```

`:app:compileDebugKotlin` 亦通过（本页与接线零编译错）。

⚠ **并行卡的中间态**：PS-6a 重写 `CameraPresetPanel.kt` 期间删掉了一批 PS-5 纯逻辑符号，导致
`CameraPresetCheckTreeTest` / `CameraPresetEditTest` 一度编译失败，测试任务整体起不来；上述绿灯是在
它恢复之后跑出来的。**全量测试未跑**（由架构师统一跑）。

## 6. 注入自检（2 处，已恢复；代码里 `grep -c MUTATION` = 0）

| # | 注入的错误 | 实测（红） |
|---|---|---|
| 1 | `FULL_GRID_COLUMNS` 4 → 3 | `gridIsFourCellsPerRowAndKeepsEveryCell FAILED`：`AssertionError: expected:<4> but was:<5>`（15 项 / 3 列 = 5 行） |
| 2 | `fullEditSaveEnabled` 改成 `overrides != null` | `saveIsEnabledOnlyWithPendingEdits FAILED` + `resetReturnsToThePresetsOwnValuesNotToNeutralBaseline FAILED`（重置后按钮必须置灰那条也红了） |

## 7. 未验证 / 已知缺口

- **没有真机跑过**（本卡只做编译 + 单测）：一行 4 格的读数密度（`+1.0 EV` / `分离色调 · 暖` 这类
  长字面在窄格子里会不会被省略号截断）、全屏覆盖层的手势层级、LUT 缩略图在深色卡片上的观感 ——
  都要真机确认（见 §8）。
- **网格不虚拟化**（`verticalScroll` + 手写 `chunked(4)`）：高级档 33 格一次性布局，低端机滚动
  帧率未测。
- 本页**不提供**单格重置 / 参数搜索 / "收藏"分类（LUT 分类沿用 `LutLibrary` 既有分类，本项目没有
  "收藏"这一分类）。
- 「←」返回后未保存改动仍在（与 PS-3 一致），不做"是否保存"二次确认。

## 8. 真机验收步骤

1. 相机 → 底部 LUT/预设 → 「预设」tab → 长按一条预设（或「⋯」→ 编辑）→ 点「完整参数 >」。
2. 顶部卡片：切 LUT 分类 → 点另一张缩略图 ⇒ **取景画面立刻变**（高亮跟着走）。
3. 「滤镜强度」拖到 0 ⇒ 画面回无 LUT；拖 150 ⇒ 到顶（百分比读数跟手）。
4. 参数卡片：切「基础 / 高级」⇒ 项数变化（基础 10 格 / 高级 29 + 只读）；数一下**一行 4 格**。
5. 点「曝光」⇒ 底部出 `● 曝光` + 滑杆；拖它 ⇒ **取景实时变**（曝光偏移，松手不回弹）。
6. 点「颗粒 · 强度」（灰格）⇒ 底部出「拍照生效 · 暂无滑杆」；预设含 `[69..77]` 时点它 ⇒ 出
   「仅编辑器生效 · 拍照不变」。
7. 点「⟳ 重置」⇒ 画面回这条预设的原值（不是中性、也不是出厂），滑杆条收起，「保存」置灰。
8. 再改两项 → 「保存」⇒ Toast「已保存到预设「X」」；回列表长按同一条确认值已更新；重进本页确认
   LUT 也记上了。
9. 内置预设重复 6–8（应能保存）；列表「⋯」里「删除」仍置灰（唯一限制不变）。
10. 系统返回键 / 「←」：**一次**按下即关闭本页（这是不用 `ModalBottomSheet` 的原因）。
