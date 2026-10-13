# PS-9 · 预设面板布局间距 + 暗角档位重定 + 颗粒改滑杆

> 用户 2026-10-12 真机截图 + 三条反馈："**截图位置挤到一起了，优化布局**。**暗角轻微和中等跟
> 没有一样，强烈顶多算轻微**。**颗粒改成滑杆吧**"
> 状态：代码 + 单测（全量 **585 tests / 0 failures**，56 个测试类）+ mutation **3 处**实测
> （红后已恢复，`grep -c MUTATION` = **0**）；**未上真机**（§4）。**未 commit**。

## 1. 三件事逐条

| # | 要求 | 改法 | 落点 | 判据 |
|---|---|---|---|---|
| ① | **统一间距阶** | 三档常量：`PRESET_GAP_SM_DP = 8`（块内）/ `PRESET_BLOCK_GAP_DP = **12→16**`（块间）/ `PRESET_GAP_LG_DP = 24`（面板顶）；卡片内边距 10→**12×10dp**（`PRESET_CARD_PAD_H/V_DP`）；常用参数区行距 12→**8**（行是块内元素，块间距留给块之间） | `CameraPresetPanel.kt` 常量段 + 一级页 `Column(padding(top = 24dp))` + 副标题 `top = 8dp` + `PresetStripCard.padding` + `PresetQuickAdjustPanel.spacedBy` | `spacingScaleIsThreeMonotonicStepsAndTheFirstLevelActuallyUsesIt`（三档值 + 严格递增 + 一级页真的引用 + 卡片内边距 ≥10dp） |
| ① | **标题去重** | `LutSheet` 顶行：预设 tab 不再复述预设名（`selectedPreset.name` → 字面 **「预设」**）；保留一级页那一行 **18sp 衬线半粗**的名称（"二选一"取详情里那个大的）。LUT tab 保持原样（它的名字没有第二个落点） | `CameraScreen.kt` LutSheet 顶行 | `theSheetHeaderStopsRepeatingThePresetNameThatTheDetailRowAlreadyShows`（顶行那段源码里不许有 `selectedPreset.name`） |
| ① | **tab 与预设条的距离** | `CameraPresetTab` 一级页根 `Column` 加 `top = 24dp`（它上方就是 LUT/预设 tab 行 —— 截图里最挤的一处）；LutSheet 标题↔tab 8→12dp | 同上 | 同上 |
| ② | **暗角档位** | `[66]`：`0 / 0.20 / 0.40 / 0.70` → **`0 / 0.35 / 0.65 / 1.0`（满量程）** | `VIGNETTE_LEVEL_PARAM`（`QuickLevelParam("暗角", 66, …)`） | `vignetteLevelsUseTheFullEngineRangeAndTheStrongStepHitsItsClamp` + `CameraPresetMainViewTest.levelChipsRoundTrip…` |
| ② | **核对引擎（不许只调数字）** | 读源码确认 `[66] = −1.0` 就是**引擎接受的最大暗角**，且没有第二处更严的钳制（见 §2） | 只读 `FrameRenderer.cpp`，**未改引擎** | `engineSourceConfirmsMinusOneIsTheStrongestVignetteThePipelineAccepts`（源码哨兵） |
| ③ | **颗粒改滑杆** | 四档分段控件 → **强度滑杆**（与 LUT 强度同一套视觉：`标签 │ RelativeSlider │ 等宽数值`），只写 `[26]`；读数 = `formatZero100`（0..100 整数）；`[27]` **一级页不再有控件、保持该预设原值** | `GrainStrengthParam` + `grainAmountSlot/Ui` + `GRAIN_UI_RANGE`；`PresetQuickAdjustPanel` 里换成 `PresetParamSliderRow` | `grainStrengthSliderUsesTheExistingSlotScaleAndNeverTouchesTheSizeSlot` + `grainIsASliderThatWritesOnlyTheAmountSlotAndTheOldLevelControlIsGone` |
| ③ | **口径来源（先读 ParamControls）** | `ParamControls.kt` **没有**颗粒滑杆（`[26][27]` 在 `CAMERA_NO_SLIDER_SLOTS` 里，是既有欠账）⇒ 按硬约束在 `CameraPresetPanel.kt` 自建，但**数值口径沿用 `PresetDetail.kt:180`** 对 `[26]` 的既有口径（槽位 0..100、UI 0..1、`formatZero100`）—— 二级页与一级页显示**同一个数** | 同上 | 同上（含 `PresetDetail.kt` 的 `meta(27, …)` 未被改动的哨兵） |

**删除的旧符号**：`GrainLevel`（枚举）/ `GrainLevelParam` / `grainLevelOf`（两个重载）——
"一条档位同写 `[26]`+`[27]`"的写入器随档位控件一并删除；`QUICK_ADJUST_SLOTS` 从
`{34,26,27,95,62,66}` 改为 **`{34,26,95,62,66}`**（口径 = "一级页真的能调的槽"）。
`CameraPresetPanel.kt` diff = **+146 / −74**（另 `CameraScreen.kt` +9/−2）。

## 2. 暗角新值的依据（引擎量程 + 用户实测）

**引擎侧（三处证据，都在 `core/engine/src/render/FrameRenderer.cpp`）**：
1. CPU 下发：`std::clamp(p.vignette_strength, -1.0f, 0.0f)`（**两处**，`passStyle` 14299-14300 /
   `passOutput` 14395-14396）⇒ 引擎**接受 −1**，且**不接受更负**；
2. shader 门控：`if (uVigBrightness < 0.0)`（4945-4951 / 5559-5565）⇒ `[66] = 0` 整段跳过（关 = 零成本）；
3. shader 公式：`c *= (1.0 + vw * uVigBrightness);`（4956 / 5570），`vw = clamp((cplen − radius/100) / feather, 0, 1)`
   ⇒ 角落权重 1 处增益 = `1 + (−1) = 0`（**压到全黑**）—— 量程内没有更暗的写法。
   **结论：`[66] = −1.0` 确实是最大暗角，引擎端没有别的钳制把它压回去（如实报告，无需改引擎）。**

**为什么旧档位"跟没有一样"（两个叠加原因）**：
- 旧值只用到量程的 70%（强 0.70 → 角落 −70%，但角落本身几乎不在画面主体）；
- **更关键**：三条内置的 `vignette_radius` = 85 / 75 / 80（`builtin.json`）⇒ 衰减从 75%~85% 半径
  才开始，`vw` 在画面主体（cplen ≲ 0.6）恒为 0 ⇒ 旧轻档 0.20 / 中档 0.40 在主体区**逐像素无变化**，
  只有最外缘一条窄带变暗（用户"跟没有一样"的实测与公式一致）。

**新值（角落增益 = `1 − ui`，与 shader 同式）**：关 1.00（不变）/ 轻 0.35 → **0.65**（角落 −35%，
是内置 0.18 的 ~2 倍）/ 中 0.65 → **0.35**（−65%）/ 强 1.0 → **0.00**（角落归零，满量程）。
内置三条（0.18 / 0.24 / 0.28）仍落在**轻档**（新轻档 0.35 的最近档）⇒ 内置观感不被本次改动打乱
（单测仍断言这一条）。

## 3. mutation 证据（3 处，红后已恢复）

| # | 注入 | 结果 |
|---|---|---|
| 1 | `VIGNETTE_LEVEL_PARAM` 强档 `1.0f` → `0.7f`（退回用户骂的那组值） | `vignetteLevelsUseTheFullEngineRangeAndTheStrongStepHitsItsClamp FAILED` + `CameraPresetMainViewTest.levelChipsRoundTrip… FAILED` |
| 2 | `GrainStrengthParam.write` 多写一行 `p[GRAIN_SIZE_SLOT] = 800.0`（"顺手把尺寸也改了"） | `grainIsASliderThatWritesOnlyTheAmountSlot… FAILED` + `CameraPresetMainViewTest` 的 3 条（`grainStrengthSlider…` / `levelWrite…` / `builtinsStay…`）FAILED |
| 3 | `PRESET_BLOCK_GAP_DP` `16f` → `8f`（块间距退回块内间距 = 用户骂的"挤到一起"） | `spacingScaleIsThreeMonotonicStepsAndTheFirstLevelActuallyUsesIt FAILED` + `Ps8PresetUiRedesignTest.firstLevelIsThreeBlocks… FAILED` |

1+2 同时注入：`12 tests completed, 6 failed` → `BUILD FAILED`；3 单独注入：`14 tests completed, 2 failed`。
恢复后同一命令（`--rerun-tasks` 全量）：**585 tests completed, 0 failed** → `BUILD SUCCESSFUL`；
`grep -c MUTATION` = **0**（6 个改动文件逐个查过）。

## 4. 未验证项（诚实声明）

1. **未上真机**：间距在真机上"松不松"、暗角四档的实际观感、颗粒滑杆的跟手度**都没有实测**
   （判据只钉常量与纯逻辑，未量像素）。
2. **暗角"强烈"是否够**：新强档 = 引擎满量程（角落归零），但**衰减带宽度由 `[67]` 半径 / `[68]`
   羽化决定**，而一级页**不提供这两条**（它们仍走「完整参数 >」）。若真机上"强烈"仍不够"重"，
   下一步应是**同时调半径**（如内置的 75~85 → 55~65），而不是继续加 `[66]`（已无空间）——
   这一条**未与用户确认**。
3. 一级页高度 ≈ **354dp 估**（条 64 + 16 + 名称行 30 + 8 + 副标题 14 + 16 + 参数 6×30+5×8 = 220），
   比 PS-8 的 ~336dp 高约 18dp（间距阶的代价）⇒ 取景区相应少一点，未在真机量取景区剩余比例。
4. 颗粒滑杆读数 = `0..100` 整数（与二级页同一个数），**没有"关 / 细 / 中 / 粗"的字面**了
   （档位名随档位控件删除）；`[27]` 尺寸在一级页**不可见**（只能去「完整参数 >」看）。
5. 编辑面板（一级页子态）**共用** `PresetQuickAdjustPanel` ⇒ 它里面也出现了颗粒滑杆、
   行距同步变 8dp —— 这是共用组件的必然结果，**未单独为编辑面板做视觉验收**。
6. 未跑 lint / assembleRelease / instrumentation。另一张卡正在改 `PhotoViewScreen.kt`（非本卡文件）。

## 5. 不许回退（逐条自检，全部仍绿）

横向滚动预设条（`LazyRow` 1 处、`LazyColumn` 0）· 一级页四块结构（名称 + 一行副标题 + 常用参数
+ 「完整参数 >」）· 返回键分层（`cameraBackTarget` + `BackHandler(enabled = editorOpen)`）·
实时预览通路（`pushEdit → onPreview → previewCameraParams`，一字未改）· `[34]` 两处特判
（`afterLutStrengthEdit` + `pushEdit` 写 prefs + `saveSlotsWithLutStrength`）· 柔光 `[95]` /
光晕 `[62]` 档位值与档位名一位未动 · 内置可改只禁删（`presetDeletable` 仍是唯一判据）·
恢复默认 / 保存与另存为 / `[0]` 曝光口径 / 每个能点的都有反应（`pressScale` 7 处）。

## 6. 真机验收步骤

1. 相机 → 底部「LUT/预设」→「预设」tab：**顶行只写「预设」**（不再重复预设名）；tab 与横向条
   之间有可见留白；名称行下方副标题与滑杆不再贴着（①）。
2. 横向卡片：文字与边框四周有均匀内边距；块与块之间松紧一致（①）。
3. 点暗角「轻微 / 中等 / 强烈」⇒ 取景画面**外缘明显变暗**（强烈 = 最外缘压到全黑）；关 = 完全不变（②）。
4. 拖「颗粒」滑杆 ⇒ 读数 0..100 变化、画面颗粒实时变化；松手出现「保存到预设」；保存后重进该预设
   读数保持；进「完整参数 >」看「颗粒 · 强度」与滑杆读数**是同一个数**、尺寸行保持原值（③）。
