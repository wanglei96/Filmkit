# P-3 报告：查看页「胶片」→ 选预设 → 直接进编辑器

- 卡号：**P-3**（用户 2026-10-11 拍板：点「胶片」→ 选一条预设 → **直接进编辑器**，不是保存副本）
- 改动留在工作区，**未 commit**（等架构师独立复核）
- 引擎零改动（只改 Kotlin）；`EditorExport.kt`（P-1 独占）与 `CameraScreen.kt`（P-2a 独占）**一行未动**

---

## 1. 验收判据逐条证据

### 判据 1：编译 + 单测退出码 0，给出用例数 ✅

```
$ rm -rf app/build/test-results                       # 先删，确保 XML 是本次新生成
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 1s
EXIT=0
```

- `app/build/test-results/testDebugUnitTest/` 本次新生成 **38 个 XML，共 404 个用例，失败 0 / 错误 0 / 跳过 0**
- 本卡新增 **7 个用例**（`PhotoViewPresetTest`，7/7 通过）
- 既有手势护栏同时全绿：`PhotoViewZoomTest` 18 个、`PhotoViewerModelTest` 26 个
- `app/build/outputs/apk/debug/app-debug.apk` 已产出（50,239,780 B）

> 注：第一次构建退出码 1，是我自己的编译错误（`(Uri, String) -> Unit` 的默认值写成 `{}`，应为 `{ _, _ -> }`），已修，非环境问题。

### 判据 2：「胶片」可点；空列表有明确提示 ✅（代码引用；无设备可截图）

- 置灰已去掉：`PhotoViewScreen.kt:578`
  ```kotlin
  BottomAction(Icons.Filled.PhotoFilter, "胶片", enabled) {   // 不再有 dimmed = true
      presets = PresetStore.load(prefs)
      showFilmPanel = true
  }
  ```
- 过时常量 `FILM_PRESET_LATER` 已删（全仓 grep 无其它引用）；随之无用的 `BottomAction.dimmed` 参数与 `DisabledInk` 常量也一并删除（胶片是唯一使用点）。
- 空列表提示：`PhotoViewScreen.kt:773`
  ```kotlin
  Text("还没有预设 · 在编辑器里保存", color = FilmTheme.CreamDim, fontSize = 13.sp, …)
  ```
  **不留点不动的按钮**：空列表时面板照常打开、给一句实话（ADR-12），单测 `空列表是合法状态而不是错误` 钉住 `null` / `""` / `"[]"` 三种来源都返回空列表而不是错误。
- 面板形态：自绘底部面板（`FilmTheme.Panel` + 22dp 圆角 + 名称/分组 + 「收起」），与 `LutSheet` 同一套视觉语言；**不用 M3 ModalBottomSheet**（真机返回要按两次才关，用户 2026-10-03 实测，见 `SettingsScreen` 的 `SettingsDrawer`）。
- 返回键分层：面板打开时 `BackHandler(enabled = showFilmPanel)`（`:385`）先关面板，关闭后交还 `MainActivity` 的导航层 ⇒ 行为与 P-3 之前一致。

### 判据 3：选预设 → 进编辑器且参数已套用 ✅（纯函数单测 + 代码路径；真机渲染未验，见 §4）

**数据流（三段，全部复用既有通路）**

```
查看页「胶片」→ PresetStore.load(prefs)（唯一真源）
   → 点一条 ⇒ onEditWithPreset(uri, p.id)                        PhotoViewScreen.kt:598-606
   → MainActivity: editorPresetId = presetId; screen = "editor"   MainActivity.kt:149-153
   → EditorScreen(initialPresetId = editorPresetId)               MainActivity.kt:160
   → 基线就绪之后：initialPresetOf(...) ?.let { applyPreset(it) }  EditorScreen.kt:1359-1360
```

**顺序铁律（本卡核心）**：`EditorScreen.kt:1359` 位于 `params = init.film`（= `editorOpenParams()` 全恒等基线）、`openParams = init.film.copyOf()`、`history.reset(基线)` **之后**，且 `applyPreset` 内部只做"稀疏覆盖"：

```kotlin
// 既有实现（一行未改）：applyPreset → applyPresetAt → 
applySnapshotParams(film, presetSnapshot(base, scalePresetSlots(p.slots, 1.0)))
```

**实际参数值证据**（单测 `初始预设的参数等于基线加稀疏覆盖`，基线取真实 `editorOpenParams()`）：

| 槽 | 含义 | 预设 slots | 套用后 `params[i]` | 说明 |
|---|---|---|---|---|
| 0 | 曝光 | 0.5 | **0.5** | 预设记下的槽 = 预设值 |
| 4 | 对比度 | 1.2 | **1.2** | 恒等值 1.0，不是 0 |
| 66 | 暗角强度 | −0.4 | **−0.4** | |
| 5 | shoulder/latitude | 未记 | **= 基线 0.01** | 缺失 = 保留基线值 |
| 11 | cross | 未记 | **= 基线（单位阵）** | |
| 37 | filmic_black_ev | 未记 | **= 基线 −7.65** | |
| 34 | LUT 强度 | 未记（策略排除） | **= 基线 0.0** | 不会"进编辑器就凭空套一层 LUT" |

同时断言 `paramsUnchanged(film, base) == false` ⇒ 导出走渲染而非直通（E2-9 判据自动生效）；`assertNotEquals(base[0], film[0])` 是**顺序哨兵**——若顺序颠倒（先预设、再基线），结果会与基线逐位相同。

**LUT 降级 / 一步撤销 / 状态提示**：全部由既有 `applyPreset`（`EditorScreen.kt:787+`）承担——预设含 LUT 且资产在库 ⇒ 走 `applyLut` 在资产就绪后写参数；资产丢失 ⇒ 降级"无 LUT + 提示"；每个分支内部 `recordHistory()` 记**一步**撤销。本卡**没有重写**它，也没有新增渲染 / 撤销通路。

**id 查不到时**（面板打开期间被删 / 数据变了）：`initialPresetOf` 返回 null ⇒ 走原来的 `rerender()`，与普通「编辑」入口**逐位一致**（回归哨兵，2 个用例覆盖）。

### 判据 4：查看页左右滑动切图 / 双指缩放未受影响 ✅（代码等价性 + 纯逻辑单测；真机手势回归未做）

- **手势仲裁纯逻辑一行未改**：`viewerGesture` / `clampZoom` / `clampAxis` / `fitDisplaySize` / `clampIndex`（`PhotoViewScreen.kt:90-165` 区间）与 `HorizontalPager` 的 `state` / `beyondViewportPageCount` 均未出现在本次 diff 中。
- 只加了两处**面板打开时才生效**的开关：
  - `userScrollEnabled = !zoomed && !showFilmPanel`（`:464`）
  - 手势层首行 `if (showFilmPanel) return@awaitEachGesture`（`:477`）
  面板关闭时 `!showFilmPanel == true`、早退条件为 `false` ⇒ 运行路径与 P-3 之前**逐位等价**。
- 面板打开时的手势归属：整屏拦截层只吃 **tap**（点面板外收起），面板容器 `clickable` 吃掉面板内点击；图片区的 Pager 与缩放/平移被上面两道开关显式关掉 ⇒ 不存在"手势漏到图片上"的路径（也就不会"选着预设把图滑走 / 捏合缩放"）。
- 单测证据：`PhotoViewZoomTest` 18/18、`PhotoViewerModelTest` 26/26 全绿（本次 XML）。

### 判据 5：改动 / 未做 / 存疑 ✅ → 见 §2 / §3 / §4

---

## 2. 改了什么（文件:行号）

| 文件 | 位置 | 改动 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt` | `:215` | 新增参数 `onEditWithPreset: (Uri, String) -> Unit = { _, _ -> }` |
| | `:243-247` | 新增状态 `showFilmPanel` / `presets`（打开面板时读一次 prefs） |
| | `:385` | 新增 `BackHandler(enabled = showFilmPanel)`（返回键分层，最内层优先） |
| | `:464` | `userScrollEnabled = !zoomed && !showFilmPanel` |
| | `:470,477` | 手势层 key 加 `showFilmPanel` + 面板打开时早退（图片区不吃手势） |
| | `:578-581` | 「胶片」去掉 `dimmed`，改为打开预设面板 |
| | `:598-606` | 面板挂载点：选中 ⇒ 关面板 + `onEditWithPreset(uri, p.id)` |
| | `:706-790` | 新增 `FilmPresetSheet`（自绘底部面板：标题 / 收起 / 列表 / 空列表实话 / 限高滚动） |
| | `:792-818` | 新增 `FilmPresetRow`（名称 + 分组） |
| | （删除，原 `:82-86`） | 删除 `FILM_PRESET_LATER` 常量与 `DisabledInk`；`BottomAction` 删除 `dimmed` 参数 |
| | `:170-206` | KDoc：新增 P-3 段（流程、手势归属、空列表口径）+ `@param onEditWithPreset` |
| `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | `:329-336` | KDoc：新增 P-3 段 + `@param initialPresetId` |
| | `:343` | 签名新增 `initialPresetId: String? = null` |
| | `:1359-1360` | 基线就绪后：`initialPresetOf(PresetStore.load(prefs), initialPresetId)` 非空 ⇒ `applyPreset(it)`；否则 `rerender()`（原逻辑） |
| `app/src/main/java/com/filmkit/app/MainActivity.kt` | `:79-83` | KDoc：新增 P-3 段 |
| | `:95` | 新增 `editorPresetId`（`rememberSaveable`，只存 id 字符串） |
| | `:146` | 普通「编辑」入口显式置 `editorPresetId = null` |
| | `:149-153` | 新增 `onEditWithPreset` 分支（与 `onEdit` 同一返回语义，只多带 id） |
| | `:160` | `EditorScreen(initialPresetId = editorPresetId, …)` |
| `app/src/main/java/com/filmkit/app/ui/PhotoViewerModel.kt` | `:206` | 新增纯函数 `initialPresetOf(presets, initialPresetId)`（+ 22 行 KDoc） |
| `app/src/test/java/com/filmkit/app/ui/PhotoViewPresetTest.kt` | 新文件（161 行） | 7 个用例：列表读取 / 空列表 / 两条回归哨兵 / 参数=基线+稀疏覆盖 / LUT 强度槽 / 端到端按 id 命中 |

**未触碰**：`EditorExport.kt`（P-1 独占，仅调用 `PresetStore` / `EditorPreset` / `presetSnapshot` / `scalePresetSlots` / `editorOpenParams` / `applySnapshotParams` / `paramsUnchanged` / `presetsToJson` / `presetsFromJson`）、`CameraScreen.kt`（P-2a 独占）、`core/engine/**`（引擎零改动）。

---

## 3. 明确不做（与卡面一致）

1. **不做内置胶片预设包**（P-2b）。
2. **不做预设强度滑杆**（编辑器侧 E3-1c 已有；从查看页进来后，用户可在编辑器里调强度）。
3. **不改编辑器预设面板 UI**（E3-1a/E3-1c 已交付）。
4. **不做"保存副本"**：查看页不写任何文件、不动原片字节（本卡只传一个 id）。
5. **面板不做选中态**：选中即离开本页（`onPick` 后立刻切到编辑器），面板不常驻，没有"当前选中"可言——做出来只会是一闪而过的假状态（ADR-12）。
6. **面板不加出入场动画**（`SettingsDrawer` 有 `AnimatedVisibility`，LutSheet 有整机层动画）：查看页这条路径点击后立即切屏，动画只会被中断；如果架构师要统一手感，可在 `FilmPresetSheet` 外层套 `AnimatedVisibility(FilmMotion.panelEnter/panelExit)`。

---

## 4. 存疑项 / 需要架构师裁决

1. **面板是"根级兄弟节点"**：`PhotoViewScreen` 现在 emit `Column`（整页）+ `if (showFilmPanel) FilmPresetSheet(...)`（`Box(fillMaxSize)`）两个根节点，依赖 Compose 根布局"所有子节点按最大尺寸叠放、后 emit 在上"的行为。**代码上是对的**（`AlertDialog` 也一直是这个位置），但真机上"面板是否确实盖住底栏与图片区"未验证。若发现异常，改法是把整页包进 `Box`（diff 会大 ~500 行缩进）。
2. **套用提示可能一闪而过**：`applyPreset` 的 `status = "已套用预设：xxx"` 会被随后 `rerender()` 回调里的 `status = ""` 覆盖——这是 **E3-1a 的既有行为**（编辑器里手动套用也一样），本卡未改。从查看页进来时用户可能看不到这条提示。是否需要"打开时套用"给一条更持久的提示（例如带预设名的首帧提示条），请裁决。
3. **进程重建后会再套用一次**：`editorPresetId` 是 `rememberSaveable`，进程被杀重建后编辑器会重新套用同一条预设。这与"编辑器所有状态（params / 历史）在重建后本来就重置"一致，但**"重建 = 回到带该预设的初始状态"**是否符合预期，请确认。
4. **`initialPresetOf` 放在 `PhotoViewerModel.kt`** 而不是 `EditorScreen.kt`：理由是保证 JVM 可测（`EditorScreen.kt` 是 Compose 文件，加载其顶层类有触碰 android 类的风险；`PhotoViewerModel.kt` 是"无 android import"的纯逻辑文件）。若偏好内聚可搬回，但需先确认测试仍能加载 `EditorScreenKt`。
5. **删除了 `BottomAction.dimmed` 与 `DisabledInk`**：胶片是唯一使用点（全仓 grep 确认）。后续若还有"置灰留位"需求需重新引入——`EditorScreen` 侧有自己的禁用样式，未受影响。
6. **`PresetStore.load` 在 UI 线程**：面板打开时与编辑器打开时各读一次（`SharedPreferences` 内存读，与既有 `EditorScreen.togglePresets` 同一口径）。若要挪进 `withContext(Dispatchers.IO)`，请一并裁决（既有代码也没挪）。
7. **真机 / 模拟器验证缺失**：本机既无 AVD 也无连接设备（`adb devices` 为空、无 emulator 目录），所以以下三条**只有代码与纯逻辑证据，没有真机证据**：① 面板实际外观与"点外部收起"手感；② 从查看页切到编辑器的转场与"套用后画面确实变了"（需要 GPU 渲染）；③ 左右滑动 / 双指缩放的真机手势回归。
8. **本卡未做真机截图**（判据 2 允许"截图或代码引用"，已给代码引用）。若架构师要求截图，需先提供可用设备/模拟器。

---

## 5. 附：本卡用到的既有接口（只调用，未修改）

| 接口 | 位置 | 用途 |
|---|---|---|
| `PresetStore.load(prefs)` | `EditorExport.kt:2277` | 查看页面板列出的全部预设（唯一真源） |
| `EditorPreset` | `EditorExport.kt:1808` | `id` / `name` / `group` / `slots` / `lutId` / `lutStrength` |
| `presetSnapshot(base, slots)` | `EditorExport.kt:1889` | 稀疏覆盖（缺失 = 保留） |
| `scalePresetSlots(slots, amount)` | `EditorExport.kt:2032` | 强度 1.0 = 原样 |
| `editorOpenParams()` | `EditorExport.kt:76` | 打开编辑器的全恒等基线（顺序铁律的"先"） |
| `applyPreset(p)` | `EditorScreen.kt:787` | 套用一次（LUT 降级 / 一步撤销 / 提示），本卡直接复用 |
| `presetsToJson` / `presetsFromJson` | `EditorExport.kt:2124 / 2157` | 单测里走真实线格式构造预设 |
