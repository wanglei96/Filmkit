# U-6b 风格参数接进编辑器参数面板 —— 实现报告

> 卡号 **U-6b** ｜ 用户需求（2026-10-11）："**预设应该展示全部可调参数，用户可编辑和新增预设。**"
> 交付物：把**引擎已支持、预设能承载、却没有滑杆**的 5 条风格参数接进编辑器参数面板
> ｜数据层 schema / 引擎（`FrameRenderer.*`）/ 拍照界面 / 查看页**零改动**
> 状态：代码完成、**构建 + 全量单测真跑通过**（`EXIT=0`，451 用例 0 失败）｜ ⚠ **未真机验证**（见 §8）

---

## 0. 一句话

参数面板补 5 条滑杆：**滤镜组**加 `黑白 [25]`、`分离色调 · 暖 [23]`、`分离色调 · 冷 [24]`；
**调整组**在 `对比度` 之后加 `胶片曲线 · 肩部 [5..7]`、`胶片曲线 · 趾部 [8..10]`。
映射函数与量程常量收在 `EditorExport.kt`（纯函数、JVM 可测）⇒ 用户手动调到这 5 组参数了，
不再只能靠预设碰运气。

---

## 1. 改了什么（5 个文件，全部在工作区，未 commit）

| 文件 | 性质 | 内容 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/camera/EditorExport.kt` | 改 | ① 新增映射段：`SPLIT_UI_RANGE` / `MONO_UI_RANGE` / `LATITUDE_UI_RANGE` / **`LATITUDE_UI_PIVOT = 0.01f`**、`splitSlot/splitUi`、`monoSlot/monoUi`、`formatMono`、`latitudeSlot/latitudeUi`、`writeLatitude`、`formatDecimals2`；② `resetBasicParams` 增加 `[5..10] = 0.01`；③ `resetColorParams` 增加 `[23][24][25] = 0`（KDoc 同步） |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | 改 | ① `AdjustParams` 在 `对比度` 之后插 2 条胶片曲线；② `ColorParams` 加 `黑白 / 分离色调·暖 / 分离色调·冷`（在 `褪色` 前）；③ 两段 KDoc 的**排序理由 + 读数口径**逐条补齐；④ imports（含 `Icons.AutoMirrored.Outlined.TrendingUp/Down`） |
| `app/src/main/java/com/filmkit/app/ui/PresetDetail.kt` | 改 | ① `[25]` 读数改**面板同口径** `formatMono(monoUi(it))`（旧 `>= 0.5 才算"开"` 在滑杆能连续调之后是**假读数**：0.35 会显示"关"）；② `[5..10]` 读数改用与面板**同一个函数** `formatDecimals2(latitudeUi(it))` |
| `app/src/test/java/com/filmkit/app/camera/EditorU6bParamUiTest.kt` | **新建** | 10 个 JVM 用例：面板参数表的分组/顺序/槽位（正则解析 `EditorScreen.kt`）、分离色调、黑白、latitude（含 **pivot 0.01 ≠ 0**）、三通道同写、两组 reset、五条滑杆的完整回路 |
| `docs/reports/preset-DESIGN.md` | 改 | §9.6 标记"展示全部可调参数"已完成（U-6a + U-6b），并记录 `[37][38]` 不进 UI 的裁决与拍照页结论 |
| `docs/reports/u6a-preset-detail-REPORT.md` | 改 | 1 行口径注记：`[25]` 的读数自 U-6b 起改为 `关` / 0..100（防文档腐烂） |

**没碰**：`FrameRenderer.cpp` / `FilmParams.h`（引擎）、`CameraScreen.kt`、`PhotoViewScreen.kt`、
`CameraController.kt`、`CameraPresetApply.kt`、`assets/presets/`、`PresetScope.kt`、
预设数据层（`EditorPreset` / `presetToJson` / `scalePresetSlots` / `PRESET_SLOT_CLAMP` 一行未动）。

---

## 2. 五条滑杆的数值链路（验收判据 1）

| # | 面板 label（组） | 槽位 | UI 量程 | pivot（reset 目标 / 中点标记） | 读数 | `read` | `write` |
|---|---|---|---|---|---|---|---|
| 1 | 分离色调 · 暖（滤镜） | `[23]` | `SPLIT_UI_RANGE` −1..+1 | `0` | `+50` | `splitUi(it[23])` | `p[23] = splitSlot(v)` |
| 2 | 分离色调 · 冷（滤镜） | `[24]` | 同上 | `0` | `+50` | `splitUi(it[24])` | `p[24] = splitSlot(v)` |
| 3 | 黑白（滤镜） | `[25]` | `MONO_UI_RANGE` 0..1 | `0` | `关` / `35` | `monoUi(it[25])` | `p[25] = monoSlot(v)` |
| 4 | 胶片曲线 · 肩部（调整） | `[5..7]` | `LATITUDE_UI_RANGE` 0..1 | **`0.01`** ⚠ | `0.30` | `latitudeUi(it[5])` | `writeLatitude(p, 5, v)` → 写 `[5][6][7]` 同值 |
| 5 | 胶片曲线 · 趾部（调整） | `[8..10]` | 同上 | **`0.01`** ⚠ | `0.30` | `latitudeUi(it[8])` | `writeLatitude(p, 8, v)` → 写 `[8][9][10]` 同值 |

**链路证据（JVM 单测逐条钉住，不需要设备）**——滑杆 UI 值 → 槽位 → 读数三步都是纯函数：

```
分离色调  0.5   --splitSlot-->  [23]=0.5    --splitUi--> 0.5   --formatOffset100--> "+50"
黑白      0.35  --monoSlot--->  [25]=0.35   --monoUi---> 0.35  --formatMono------->  "35"
黑白      0.0   --monoSlot--->  [25]=0.0    --monoUi---> 0.0   --formatMono------->  "关"
肩部      0.3   --latitudeSlot-> [5][6][7]=0.3 --latitudeUi-> 0.3 --formatDecimals2--> "0.30"
趾部      0.0   --latitudeSlot-> [8][9][10]=0.0 --latitudeUi-> 0.0 --formatDecimals2--> "0.00"
坏数据    NaN   --latitudeSlot-> 0.01（恒等，不是 0）
```

- **引擎侧落点**（本卡未改引擎，只引用既有接线）：`FrameRenderer.cpp` 的 `kFsFilm` 里
  `uSplit`（`[23][24]`）/ `uMono`（`[25]`）由 **U-1** 接上；`kFsStyle` 的 latitude 曲线段
  （`[5..10]`）由 **U-2** 接上。两条链路共享同一套槽位语义是本卡成立的前提（U-1…U-5 的成果）。
- **单测覆盖**：`EditorU6bParamUiTest.fiveNewSlidersWriteOnlyTheirOwnSlotsAndReadBack` 拖完 5 条后
  逐槽断言"**只有目标槽位变了**"（`[5..10]` / `[23..25]` 之外逐位相等），再断言 5 条读数。
- **无设备时的验证方式**：以上全部是 `EditorParam.read/write` 调用的纯函数，JVM 单测与真机跑的是
  **同一份代码**；真机需要验的是"滑杆排布 / 手感 / 拖到极端值时画面观感"（§8）。

---

## 3. 分组与顺序理由（任务卡的放置要求）

- **分离色调 · 暖 / 冷 → 滤镜组**，与 `褪色` 相邻：四者都是"胶片风格化调色"（暖 / 冷 / 洗白），
  挨着放便于一起比对；**暖 / 冷是一对必须相邻**（同一套分离色调的两半）。
- **黑白 → 滤镜组，放在分离色调之前**：概念顺序上"**整体去色**"先于"**分区调色**"——引擎里
  `uMono` 的 `mix(out3, vec3(luma), uMono)` 作用在分离色调之后，所以顺序反了会诱导用户先分区
  染色、再被去色抹掉（做了无用功）。KDoc 里写明了这条理由。
- **胶片曲线 → 调整组，紧跟 `对比度`**（在四条影调分区**之前**）：三者都是"**曲线形状**"——
  对比度 = 整体斜率、latitude 肩部/趾部 = 两端过渡区的宽度。用户发现"对比度不够精细"时，
  下一步就是来调端点；把两者中间塞进四条影调分区会把同一条曲线的两端拆散。
  排序理由写进了 `AdjustParams` 的 KDoc（E2-15 立的"按逻辑排但要说明理由"这条规矩继续遵守）。
- **为什么胶片曲线不进滤镜组**：latitude 不是"风格化调色"，它是影调曲线的形状参数
  （`preset-DESIGN.md` §1 的分组里它属于「影调曲线」层），与对比度同族。

---

## 4. ⚠ `[5..10]` 的恒等值是 `0.01` 而不是 `0`（本卡最容易踩的坑）

`CameraPresets.kt` 的 `[5..10]` 专章写明：latitude 语义下 **`0` = 过渡区紧贴中灰 = 极端 S 曲线**，
**不是"无效果"**。本卡按此落地三条：

1. **`LATITUDE_UI_PIVOT = 0.01f`** —— reset 目标与中点标记位置都在 0.01（`pivotFraction` 返回 0.01，
   标记画在滑杆 1% 处 ⇒ 用户看得见"默认在哪"）。
2. **滑杆允许拖到最左端 0** —— 那是用户**主动**要极端曲线，是能力不是 bug；`latitudeSlot(0f) = 0.0`
   有单测钉住（有意为之）。
3. **坏数据回落 0.01**（不是 0）—— 把"读不出来"变成"极端 S 曲线"是最容易造成画面突变的失败模式
   （与 `tempUi` 越界回落 6500K 同一条纪律）。

**reset 行为**：

| 入口 | 实现 | 结果 |
|---|---|---|
| 单条滑杆 reset（面板长按/复位按钮） | `EditorScreen.resetParam` = `p.write(it, p.pivot)` | 肩部/趾部 → `0.01` ✅；黑白/分离色调 → `0` ✅ |
| 「重置本组」调整组 | `resetBasicParams` | `[5..10] = LATITUDE_UI_PIVOT`（0.01）✅ |
| 「重置本组」滤镜组 | `resetColorParams` | `[23][24][25] = 0`（+ 既有 `[94]`）✅ |

判据不用字面量而用**标准基线**：单测断言 `resetBasicParams(q)` 后
`paramsUnchanged(q, CameraPresets.standard())` 为真 —— `CameraPresets.standard()` 的 `[5..10]` 就是
`0.01`，这就是"恒等"的定义（ADR-1 单一参数源）。**若把 pivot 写成 0，这条断言立刻红**（见 §7 的 M1）。

---

## 5. `[37][38]` filmic 黑白点（EV）的处理建议（**本卡不实现**）

**现状**：`[37] filmic_black_ev = −7.65` / `[38] filmic_white_ev = +4.56`（`CameraPresets.kt`：
"darktable 默认值，也是 PhotonCamera 的固化值"）；U-2 已把两者接到编辑器链路的曲线段，
预设能承载、详情会显示（`PresetDetail` 显示 `-7.6 EV` / `+4.6 EV`），**但面板没有滑杆**。

**建议（v1 不进 UI，需要时按下面的口径单独开卡）**：

| 议题 | 建议 | 理由 |
|---|---|---|
| 是否进 UI | **暂不进**（推荐） | 它是**场景动态范围端点**，不是风格旋钮：用户没有"这张场景有多少 EV"的参考，拖它等于盲调；而 −7.65/+4.56 是 darktable/PC 的对标值，随手改动会脱离"胶片"对标口径 |
| 若进 UI：量程 | `[37] ∈ [−14, −1]`、`[38] ∈ [+1, +8]`（**非对称**，不是 ±100 那套） | 物理单位是 EV，必须给真实区间；对称量程会让"默认 −7.65"落在奇怪位置，且正负号语义不对称（黑点必负、白点必正） |
| 若进 UI：pivot | `[37] = −7.65`、`[38] = +4.56`（**照标准基线**，中点标记画在默认处，与 latitude 同一条纪律） | reset 必须回对标默认值，不能回 0（回 0 等于把端点挪到中灰，画面直接爆掉/压死） |
| 若进 UI：读数 | `-7.65 EV` / `+4.56 EV`（**两位小数**） | 一位小数会把 −7.65 显示成 −7.6/−7.7，抹掉对标值的精度 |
| 若进 UI：分组 | 与 `胶片曲线 · 肩部/趾部` **相邻**（调整组，对比度那一族） | 三者是同一族：`[4]` = 斜率、`[5..10]` = 过渡区宽度、`[37][38]` = 端点位置 |
| 前置条件 | 先实测编辑器链路（**显示域**）改 `[37][38]` 的可见幅度与方向，再定是否给滑杆 | U-2 报告 §11.4 已标注"极值下端点仿射被防呆 clamp 到整段压死，属已知近似"——显示域没有 log 域余量，**盲给滑杆可能给出"拖了没反应/反应过猛"的假滑杆**（ADR-12 明确禁止） |
| 成本 | ≈ 半张卡（映射对 + 2 条滑杆 + 量程常量 + 4–6 条单测 + 1 条含 `[37][38]` 的预设做真机验证） | — |

---

## 6. 拍照界面是否需要参数面板（勘察结论：**不需要**）

**勘察事实**：`CameraScreen.kt` 的滑杆只有**拍摄控制**——EV / Tv / ISO / AF·MF / SEC / WB
（`RelativeSlider` 调用点）+ LUT 强度；**没有任何风格参数**，风格通过预设卡片行（42 张 LUT +
预设）选择。`PhotoViewScreen.kt` **没有参数表**（只有 U-1 的接线注释）。

**结论**：**不加**。三条理由：

1. **屏幕空间与使用场景冲突**：拍照页底部已有"顶行 + 分类行 + 卡片行 + 滑杆"，参数面板要再加
   15+ 条滑杆（调整组）——而拍照的使用场景是"举起就拍、不看屏幕"，面板越长越没人用。
2. **状态语义不同，复用成本不低**：编辑器参数是"对已成片的**后期**编辑"（基线 = `editorOpenParams`
   全恒等）；拍照参数是"**即将拍摄**"（基线 = `CameraPresets.standard()` + 高光保护判据
   `hlCompEv` + 预设 EV 偏移的叠加）。两套 gate/渲染语义不同，不能直接把 `EditorParam` 表搬过去
   —— 需要一份并行的状态机与即时预览链路（live GL），成本 ≈ 1–2 张卡，且要重做真机验证。
3. **产品分工已经成立**：拍前"选风格"（预设卡片）、拍后"精调"（编辑器）是当前设计，
   用户第 2 条需求针对的也是**预设**（展示/编辑/新增），不是取景页。

**若要做，推荐低成本折中（本卡未实现，供拍板）**：把 **U-6a 的预设详情（只读）** 接到拍照页的
预设卡片上（长按 → 详情浮层），复用 `presetDetailSections` ⇒ **零新滑杆、零引擎改动、零 gate 风险**，
约 0.5 天 + 真机验证；用户"拍前能看到这条预设会改什么"，真要调参再进编辑器。代价：只读 ≠ 可调。

---

## 7. 构建 / 单测 / mutation 证据（验收判据 4）

### 7.1 构建 + 全量单测（真跑，非 FROM-CACHE）

```
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 15s
46 actionable tasks: 46 executed          ← 全部 executed，无 FROM-CACHE
EXIT=0
```

- **用例数：451 个，0 失败、0 错误**（解析 `app/build/test-results/testDebugUnitTest/TEST-*.xml`
  共 42 个测试类累加；其中新增 `EditorU6bParamUiTest` = **10 个用例**）。
  基线 **441** ⇒ 本卡 **+10**，与新增用例数一致（没有靠删/改既有断言换绿）。
- 编译零新增警告（`Icons.Outlined.TrendingUp/Down` 的 deprecation 已改用
  `Icons.AutoMirrored.Outlined.*`）。
- **`PresetDetailTest` 全绿**（验收判据 3）：`detailLabelsReuseEditorPanelWording`
  （详情名必须包含面板 label）与 `detailLabelsMatchScopeNoticeWording` 都通过 —— 新参数的
  中文名与面板 label **逐字相同**（`分离色调 · 暖` / `分离色调 · 冷` / `黑白` /
  `胶片曲线 · 肩部` / `胶片曲线 · 趾部`）。**没有为了让它们绿而放宽任何断言**。

### 7.2 Mutation 自检（2 项，均真跑、均抓到、均已还原）

| # | 变异 | 结果 | 证据 |
|---|---|---|---|
| **M1** | `LATITUDE_UI_PIVOT = 0.01f` → **`0f`**（本卡指定必做的一项） | ✅ 抓到 | `EditorU6bParamUiTest`：`resetRestoresLatitudeToDefaultAndMonoSplitToZero`、`latitudeIdentityIsZeroPointZeroOneNotZero`、`fiveNewSlidersWriteOnlyTheirOwnSlotsAndReadBack` **3 条红**；`10 tests completed, 3 failed`；`EXIT=1` |
| **M2** | `writeLatitude` 只写 `p[offset]`（丢掉 `[offset+1]` / `[offset+2]`，= 只写 R 通道） | ✅ 抓到 | `latitudeWritesThreeChannelsAtOnceAndTouchesNothingElse`、`resetRestoresLatitudeToDefaultAndMonoSplitToZero` **2 条红**；`10 tests completed, 2 failed`；`EXIT=1` |

还原后重跑全量：`EXIT=0`、`BUILD SUCCESSFUL`、`46 actionable tasks: 46 executed`、451 用例 0 失败
（工作区代码 = 报告描述的版本，`EditorExport.kt` 与变异前逐字节 diff 为空）。

---

## 8. 诚实标注（未真机验证 + 遗留）

**⚠ 本卡全部验证都是 JVM 层**（纯函数 + 参数表解析）。**没有真机验证**，以下必须在真机上确认：

1. **5 条滑杆的排布与手感**：调整组变 15 条、滤镜组变 4 条 ⇒ 面板是否还能一屏/两屏容纳、
   滚动是否顺畅、`RelativeSlider` 的 44dp 行高在真机是否与既有条目一致。
2. **图标语义**：`TrendingUp`（肩部）/ `TrendingDown`（趾部）/ `MonochromePhotos`（黑白）/
   `WbSunny`（暖）/ `AcUnit`（冷）在真机的辨识度（图标只是消歧，不承载精确语义）。
3. **中点标记**：三条新滑杆的基准落在 0.01 / 0（左端）⇒ 真机确认"0.01 处那条标记看得见、
   0 处不画标记"与设计一致。
4. **极端值的观感**：把胶片曲线拖到最左端 0（= 极端 S 曲线）与 1.0（接近线性）时画面是否符合
   U-2 报告描述的方向；黑白 0.35 是否确实"部分去色"而不是硬开关。
5. **与预设的联动**：套一条含 `[23..25]` / `[5..10]` 的预设（如"徕卡一瞬"的分离色调 0.1/0.05）
   ⇒ 滑杆必须停在对应位置、读数与详情页一致；再点「重置本组」⇒ 回 0.01 / 0。

**遗留 / 待裁决（本卡按任务书范围未做）**：

| # | 事项 | 说明 |
|---|---|---|
| 1 | **`[23][24]` 详情读数口径** | 面板显示 `+10`（±100），详情仍显示 `+0.10`（原始量，U-6a 的样张断言）—— 本卡任务书只要求"**名字**一致"，故未改；若要统一到面板口径：`PresetDetail.kt` 1 行 + `PresetDetailTest` 1 条期望值（`+0.10` → `+10`） |
| 2 | `[37][38]` | 见 §5，需先确认量程/语义与显示域可见幅度 |
| 3 | 拍照界面参数面板 | 见 §6，建议只读详情折中方案，等架构师拍板 |
| 4 | `PRESET_SLOT_CLAMP` 未含 `[5..10]` / `[23..25]` | 该表只在预设强度 `amount > 1` 的**外推**路径上用；`[5..10]` 属 `presetSlotCurve`（**不缩放**，原值套用），`[23..25]` 未列 ⇒ 外推时不夹紧。本卡按纪律**未动预设数据层**；若将来要"外推也夹住风格参数"，是独立的一张卡（要连 `presetSlotCurve` 的口径一起看） |
