# PS-10 · filmic 黑白点（`[37]` / `[38]`）做成可调

> 用户 2026-10-12 真机："**黑白点要可调**，你白点默认才 2.7？那杯子高光变白的 bug 不就又出来了！！！"
> 状态：代码 + 单测（全量 **595 tests / 0 failures**，57 个测试类）+ mutation **4 处**实测（红后已恢复，
> `grep -c MUTATION` = **0**）；**未上真机**（§5）。**未 commit**。
> ⚠ 白点 2.7 的成因（内置三条 2.7471 / 2.6138 / 2.5438）由另一张卡评估，本卡只做"可调"。

## 1. 改了什么

| # | 改法 | 落点 | 判据（单测） |
|---|---|---|---|
| ① | `[37]` 黑点 / `[38]` 白点各建一条 `EditorParam`，**接在胶片曲线两条之后**（同属"曲线端点"：肩/趾 = 过渡区宽度，黑白点 = 端点位置 ⇒ 四条构成一整段"曲线族"） | `ParamControls.kt` 的 `AdjustParams` | `EditorU6bParamUiTest.adjustGroupPutsFilmCurveRightAfterContrast`（全序列）+ `Ps10…fullEditGridPutsThemInTheAdjustGroupRightAfterTheCurve` |
| ② | 从 `CAMERA_NO_SLIDER_SLOTS` **移除** 37/38（既有欠账关闭）+ `CAMERA_TUNABLE_SLOT_PARAM` 加 `bind("filmicBlack", 37)` / `bind("filmicWhite", 38)` | 同上 | `CameraPresetEditTest.tunableSlotsAreExactlyTheCameraPathMinusTheSliderlessOnes`（集合相等，自动跟随）+ `CameraPresetFullEditTest`（29 → **31** 项；只读组 6 槽 → **4 槽**） |
| ③ | 读数**单一实现** `formatEvSlot`（槽位即 EV，`-7.6 EV` / `+4.6 EV`）；`PresetDetail.kt` 的 `evText` 改为委托它 | `EditorExport.kt` + `PresetDetail.kt` | `Ps10…readoutsMatchTheDetailTableCharacterForCharacter`（参数表滑杆读数 == 详情页读数，逐字；另一个槽设成不同哨兵值 ⇒ 读错槽必红）+ 既有 `PresetDetailTest.physicalUnitsAreAttached` |
| ④ | 二级「完整参数」页：自动进**「调整」组**（分组依据 = 参数表位置，不另立分类） | `CameraPresetFullEdit.kt` **未改**（数据驱动） | 同上 ① 的用例（组名 + 行序 + 不进「基础」档） |
| ⑤ | 一级页加**白点**一条滑杆（复用 `PresetParamSliderRow` + 参数表那一条 `EditorParam`），`QUICK_ADJUST_SLOTS` 加 `[38]`；数值列宽 **46 → 64dp**（`+2.7 EV` / `-12.0 EV` 是 7~8 字符 ≈58dp，46dp 会把单位裁掉） | `CameraPresetPanel.kt` | `Ps10…firstLevelGivesTheWhitePointASliderAndReusesTheSameParam`（源码哨兵：真的渲染 + 复用同一条 + 不许第二份 `key = "filmicWhite"` + label 与参数表逐字一致 + 列宽 ≥58dp） |
| ⑥ | 写值走**既有通路**：`EditorParam.write` → `afterEdit` → `onPreview`（实时下发）/ `cameraPresetSlotsFor`（保存）—— **没有新增任何 state 通路** | 同上 | `Ps10…editsGoThroughTheExistingAfterEditPathAndSparsifyBack`（含"拖回默认值 ⇒ 稀疏化丢弃"） |

## 2. 量程的依据（不是自造）

| 依据 | 内容 |
|---|---|
| ① **既有量程**（主要依据） | `SettingsScreen.kt` 的全局滑杆**同一个参数**：黑点 `-12f..-1f`、白点 `1f..8f` ⇒ 参数表逐位沿用（另定一套 = 同一物理量两个范围）。测试用**源码哨兵**读那两行 `range = …` 字面量 ⇒ 那边改了量程这边不同步就红 |
| ② **pivot = 引擎默认值** | `FilmParams.h` 的 `-7.65f` / `+4.56f`（= `CameraPresets.standard()` 基线 = 稀疏化的中性基准，三处对拍）。pivot 与 standard 差 9.5e-8（Float 精度噪声）≪ `PARAM_EPS = 1e-4` ⇒ "拖回默认值"撤销得掉 |
| ③ **引擎侧无绝对 clamp** | `FilmParams.h:550-551` 直通赋值；`computeFilmicSpline` 只有相对约束 `black ≤ white − 0.2` ⇒ 量程纯粹是 UI 决定 |

读数：一位小数 + `EV`（与 `DETAIL_META` 的 `evText` 同形）；`formatEv`（UI 域 ×3）**不能**用于这两槽（会把 −7.65 显示成 `-3.0 EV`），用例里有一条反向哨兵钉住这个区别。

## 3. 两端是否真生效（ADR-12：不做假控件）

| 链路 | 是否读 `[37][38]` | 证据 | 结论 |
|---|---|---|---|
| **拍照链路**（取景 / 成片） | **真读** | `FrameRenderer.cpp` 的 `updateFilmicEV(..., p.filmic_black_ev, p.filmic_white_ev, ...)`；`[37][38] ∈ CAMERA_PATH_SLOTS`（引擎实测白名单，`CameraStyleWhitelistTest` 绿） | `cameraParamAdjustability(37/38) = TUNABLE`、`note = null` ⇒ **不灰显**，一级页 / 二级页都给滑杆 |
| **编辑器链路**（显示域曲线） | **真读，但白点有物理上限** | `computeEditorCurve(p.shoulder, p.toe, p.filmic_black_ev, p.filmic_white_ev)`；其中 `wh = clamp(srgbEncode(grey·2^white_ev), grey_disp+0.05, 1.0)` | **如实标注（不藏）**：`white_ev ≥ log2(1/0.1845) ≈ 2.4377` 时 `wh` 恒为 1.0 ⇒ 编辑器里白点在 **2.44 以上与 2.44 逐位相同**（显示域没有比 1.0 更亮的像素）。默认 4.56 与内置 2.54~2.75 **都在该上限之上** ⇒ 编辑器里它们的白点效果相同。**未改引擎**（`core/` 禁改）：量程仍取 1..8，因为这两个槽的主战场是拍照链路，且 8 覆盖真机实测有效的 6.2 档 |

## 4. 一级页的判断（黑点为什么没上）

**白点进、黑点不进**（判断，依据三条，写在 `FILMIC_WHITE_EV_SLOT` 的 KDoc 里）：
① 用户报的是**高光**（白点），暗部没报过问题；② 一级页既有模式 = **每类只给主控一条**
（柔光只给强度、暗角只给强度、颗粒只给强度，半径 / 阈值 / 尺寸全在「完整参数 >」）；
③ 一级页高度是用户明确要求守住的（PS-8 / PS-9 量过 ~354dp）⇒ 一行 30dp + 8dp 间距是上限。
黑点仍在「完整参数 >」的「调整」组里与白点相邻 ⇒ "黑白点可调"整体成立。
**代价**：一级页 +1 行 ≈ **+38dp**（估），取景区相应少一点 —— 真机观感未验（§5）。
若用户要黑点也上一级页：加一行 `PresetParamSliderRow` 即可（同一组件、同一参数项）。

## 5. mutation 证据（4 处，红后已恢复）

| # | 注入 | 结果 |
|---|---|---|
| 1 | 白点 `read = { it[38]… }` → `it[37]`（读错槽） | `595 tests completed, 5 failed`：`Ps10…readouts…` / `Ps10…sliderRoundTrip…` / `Ps10…builtinEndpoints…` / `CameraPresetEditTest.everyTunableSlotIsBoundToItsOwnParam` / `PresetDetailTest.detailLabelsReuseEditorPanelWording` |
| 2 | `FILMIC_WHITE_EV_UI_RANGE = 1f..8f` → `0f..4f`（量程自造） | `2 failed`：`Ps10…rangesComeFromTheExistingGlobalSliders…` / `Ps10…pivotsAreTheEngineDefaults…` |
| 3 | `evText` 改回本地两位小数实现（两页读数漂移） | `4 failed`：`Ps10…readouts…` / `Ps10…builtinEndpoints…` / `PresetDetailTest.physicalUnitsAreAttached` / `PresetDetailTest.builtinLeicaMomentRendersHumanReadableRows` |
| 4 | 一级页白点行 label `"白点"` → `"白点X"`（哨兵失效验证） | `1 failed`：`Ps10…firstLevelGivesTheWhitePointASlider…` |
| 5 | `PRESET_VALUE_WIDTH_DP` 64 → 46（EV 读数被裁） | `1 failed`：`Ps10…firstLevelGivesTheWhitePointASlider…` |

恢复后同一命令（`--rerun-tasks` 全量）：**595 tests completed, 0 failed** → `BUILD SUCCESSFUL`；
5 个改动文件 + 新测试逐个 `grep -c MUTATION` = **0**。
命令：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks`

## 6. 未验证项（诚实声明）

1. **未上真机**：滑杆跟手度、读数在 30dp 行里的可读性（数值列 46 → 64dp 后滑杆短了 18dp）、
   一级页 +38dp 后取景区剩多少 —— 都没量。
2. **编辑器链路白点 >2.44 无效**（§3）：这是显示域曲线的物理上限，**不是本卡引入**，但用户在编辑器里
   拖到 2.44 以上会看到"画面不动"。要不要在编辑器侧也加提示 / 收窄量程 —— **未与用户确认**（收窄会砍掉
   拍照链路的 6.2 档能力，故本卡取"如实标注 + 报告"）。
3. **编辑面板共用** `PresetQuickAdjustPanel` ⇒ 编辑预设面板里也多了白点滑杆（与 PS-9 的颗粒滑杆同型），
   未单独做视觉验收。
4. **内置三条的白点值由另一张卡决定**：本卡测试**不硬编码**它们（只断言"在量程内 + 两页读数一致"）。
   若那张卡把白点调到 **>8 EV**，`Ps10…builtinEndpointsSitInsideTheRange…` 会红 —— 那时必须同步扩量程
   （或由那张卡负责）。
5. 图标 `ExposureNeg2` / `ExposurePlus2`（黑点 / 白点）在真机深色底上的辨识度未验。
6. 未跑 lint / assembleRelease / instrumentation。

## 7. 不许回退（逐条自检，全部仍绿）

一级页四块结构（名称 + 副标题 + 常用参数 + 「完整参数 >」）· 横向滚动预设条 · 返回键分层 ·
实时预览通路（`pushEdit → onPreview → previewCameraParams` 一字未改）· `[34]` 两处特判 ·
档位（柔光 `[95]` / 光晕 `[62]` / 暗角 `[66]` 值与档位名一位未动）· 颗粒滑杆（只写 `[26]`）·
内置可改只禁删 · `[0]` 曝光口径 · 每个能点的都有反应。

## 8. 真机验收步骤

1. 相机 → 「LUT/预设」→「预设」tab → 常用参数区：**多出「白点」一行滑杆**，读数一位小数 + `EV`
   （内置三条应显示 `+2.7 EV` 一类的值）；拖动 ⇒ **取景画面高光实时变化**（往右 = 白点更远 =
   高光更不容易死白），松手出现「保存到预设」。
2. 保存后重进该预设：白点读数保持；点「完整参数 >」→「高级」→「调整」组：**「黑点」「白点」两格
   紧跟「胶片曲线 · 趾部」**，读数与一级页**同一个数**；点「白点」⇒ 底部滑杆拖动 ⇒ 画面实时变化。
3. 把白点拖到 8（量程最右）与拖到 1（最左）：取景画面高光应**明显不同**（8 = 高光压得住，1 = 高光死白）。
4. 「⟳ 重置」⇒ 白点回到 `+4.6 EV`（引擎默认 4.56），改动集清空（保存按钮置灰）。
5. 编辑器（打开一张照片）→「调整」组：图标行多了「黑点」「白点」两个 chip；拖白点 **1 → 2.4** 画面
   高光变硬；拖 **2.44 → 8** 画面**不变**（§3 的显示域上限，预期行为，不是卡死）。
