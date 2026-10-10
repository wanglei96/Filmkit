# 修复报告：查看页放大时"隔壁图片入侵视野"（E2-23）

**日期**：2026-10-10 ｜ **范围**：`PhotoViewScreen.kt`（实现）+ `PhotoViewZoomTest.kt`（回归测试）
**用户上报**：「相册放大图片隔壁图片会入侵视野。」

> ⚠️ **未真机验证**（本机 `adb devices` 空、SDK 无 emulator/system-images、无 AVD）——
> 详细原因与建议的真机验证步骤见 §6。JVM 侧证据（构建 + 420 用例 + mutation 自检）见 §5。

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 根因 | **相邻页跟着当前页一起被缩放/平移**（`zoom` / `pan` 是整页共享 state），邻页从视口外越界画进视口。**不是**"邻页在视口外被绘制"（那不可能产生可见现象，见 §2.3） |
| 修法 | ① 缩放/平移**只给停稳页**（根因，可单测）；② 每页根容器 `clipToBounds()`（页边界裁剪兜底） |
| 改动量 | `PhotoViewScreen.kt` 两处 + 2 个一行纯函数；手势仲裁表 / `clampZoom` / `clampAxis` / `fitDisplaySize` **一行未改** |
| 测试 | 新增 2 例（418 → **420**），`--rerun-tasks` 真跑绿；mutation 自检证明新用例能红 |
| 未覆盖 | `clipToBounds()` 属于 Compose 绘制层，JVM 单测**测不到**（本项目只有 junit，无 Robolectric / compose-ui-test）⇒ 必须真机确认 |

---

## 1. 现象与机制（用户描述 → 代码事实）

在查看页双指放大当前照片后，视口里出现**别张照片**的像素（倍率小时是两侧各一条，倍率大时可能盖住大半屏）。

## 2. 根因

### 2.1 代码事实（行号对应当前工作区）

| 位置 | 事实 |
|---|---|
| `PhotoViewScreen.kt:491` | `beyondViewportPageCount = 1` —— 相邻页**被组合并绘制**（E2-22 的预载） |
| `PhotoViewScreen.kt:560-568` | 相邻页的位图是 `rememberPreview(...)` 解出的 **360px 预览** —— 是**真实照片的像素**，不是占位色块 |
| `PhotoViewScreen.kt:285-286` | `zoom` / `pan` 是**页面级 state**（不是每页一份） |
| `PhotoViewScreen.kt:581-585` | `graphicsLayer { scaleX/scaleY = pageScaleAt(p == page, zoom) ... }` —— **修复前**这里是 `zoom.floatValue`，对**每一页一视同仁** |
| `PhotoViewScreen.kt:507` | 每页根 `Box`（修复前）**没有任何裁剪**；`graphicsLayer` 默认 `clip = false` |
| `PhotoViewScreen.kt:496` | `userScrollEnabled = !zoomed && !showFilmPanel` —— 放大态只关掉**滚动**，**不关组合/绘制** |

**旧注释的假设是错的**（修复前 `PhotoViewScreen.kt:549-550`）：

> "滑动时必然是 Fit 态（放大态 Pager 被禁用），所以下面那份 graphicsLayer 对相邻页等于无变换。"

放大态禁止的是 Pager 的**滚动**，不是相邻页的**组合/绘制**。相邻页照样被组合，照样拿到那份共享的
`zoom`/`pan` —— 于是它跟着放大。

**页宽 = 视口宽**（这一点也做了字节码验证）：`HorizontalPager` 未传 `pageSize` ⇒ 取默认
`PageSize.Fill`，`pageSpacing` 默认 0。证据：`foundation-android:1.11.4`（BOM 2026.06.01 锁定版本）
的 `androidx/compose/foundation/pager/PagerKt.class` 中 `HorizontalPager$default` 路径上
`getstatic androidx/compose/foundation/pager/PageSize$Fill.INSTANCE`。

### 2.2 几何推演（为什么恰好是"入侵"，而不是别的）

真机口径（沿用 `PhotoViewZoomTest` 的注释口径）：照片区 1080×1920，12MP 4000×3000 横版原片
Fit 后 = **1080×810，宽度正好撑满页宽**。相邻页的页心在视口外：

- 左邻 `p−1` 页心 `x = −540`
- 右邻 `p+1` 页心 `x = 1080 + 540 = 1620`
- `graphicsLayer` 默认 `transformOrigin = (0.5, 0.5)` ⇒ 以**自己的页心**为中心缩放

放大 `z` 倍后（邻页内容宽 = `1080z`）：

| z | 左邻右边界 = `540(z−1)` | 右邻左边界 = `1080 − 540(z−1)` | 视口里的现象 |
|---|---|---|---|
| 1.2× | 108 | 972 | **左右各 108px 是别张照片**（"入侵"） |
| 2× | 540 | 540 | 大半屏是邻页内容 |
| 3× | 1080 | 0 | 邻页盖满整个视口 |

数值与用户"隔壁图片会入侵视野"逐字对应：**一放大就入侵，倍率越大入侵越多**。
（竖版 3:4 原片 Fit 后同样是宽度撑满页宽 ⇒ 同结论；照片区更矮的横屏机型下单页内容宽可能
小于页宽，此时阈值是 `z > 页宽 / 内容宽`，量级仍是刚过 1× 就入侵。）

### 2.3 排除其它可能（含为什么"邻页越界绘制"这个常见猜想不成立）

1. **"相邻页在视口外仍被绘制 ⇒ 入侵" —— 不可能产生可见现象**：屏幕不显示屏幕外像素，
   视口外画得再多也看不到。唯一能让邻页像素进入视野的路径，是它被**变换或平移进了视口**。
   这条推理不依赖 Compose 内部实现，直接把该猜想排除 ⇒ 剩下唯一机制就是 §2.1/§2.2。
2. **不是"当前页放大后绘制越界"**：当前页 = 整屏宽，它越界只能画到**屏幕外**（同样不可见）。
   当前页溢出的像素顶多盖住邻页在视口外的那部分。所以"缺 clipToBounds"是**放大现象的必要条件**，
   但**唯一可见后果来自邻页那一侧**（这一点决定了修法必须包含 §3.1，光加裁剪只是兜底）。
3. **不是平移（pan）泄漏**：`clampAxis` 的夹紧规则保证——只要某轴内容 ≥ 视口，图必然覆盖整个
   视口（左边缘 ∈ `[v−c, 0]`）；内容 < 视口时上限为 0（居中）。所以放大态平移**永远露不出邻页**，
   也露不出黑边。若 bug 出在平移，用户会报"拖出黑边"而不是"隔壁图片"。
4. **不是 `beyondViewportPageCount = 1` 配错**：它的语义就是"视口外额外组合的页数"（E2-22 预载），
   去掉它会让 E2-22 的"滑过去不空白"退化。本 bug 与该参数正确性无关，**不需要动它**（也就没动）。
5. **不是 P-3 引入的**：P-3 只加了两个"面板打开才生效"的开关（`userScrollEnabled` 多一个
   `&& !showFilmPanel`、手势层早退），`viewerGesture` / `clampZoom` / `clampAxis` /
   `fitDisplaySize` 一行未改。面板关掉时本 bug 照样复现。
6. **不是 E2-20 的手势代码写错**：三条手势的行为本身是符合验收的（见 §4），bug 在**绘制变换的
   作用域**（哪一页该跟随变换），与手势仲裁无关。

## 3. 修法（最小改动）

### 3.1 根因修正：缩放/平移只给"停稳页"

新增两个一行纯函数（`PhotoViewScreen.kt:185-190`，与 `clampZoom` 等同规矩：不 import 任何
`android.*`，可在 JVM 真跑）：

```kotlin
internal fun pageScaleAt(isSettledPage: Boolean, zoom: Float): Float =
    if (isSettledPage) zoom else 1f

internal fun pageShiftAt(isSettledPage: Boolean, shift: Float): Float =
    if (isSettledPage) shift else 0f
```

接线（`PhotoViewScreen.kt:581-585`）：

```kotlin
.graphicsLayer {
    val z = pageScaleAt(p == page, zoom.floatValue)   // p == page ⇒ settledPage
    scaleX = z; scaleY = z
    translationX = pageShiftAt(p == page, pan.value.x)
    translationY = pageShiftAt(p == page, pan.value.y)
}
```

**为什么能解决**：相邻页恢复恒等变换后，它就以自己的页心（视口外 ±1 页宽）为中心、按 Fit 尺寸
绘制，**整个待在视口之外**——视口里只剩当前页的像素（§2.2 的三行数值全部失效）。

### 3.2 兜底：每页根容器 `clipToBounds()`（`PhotoViewScreen.kt:507`）

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .clipToBounds()          // ★ E2-23：像素出不去本页
        .pointerInput(p, showFilmPanel) { ... },
    contentAlignment = Alignment.Center,
)
```

**为什么不是给 `Image` 加裁剪**（这是本卡最容易做错的一步）：Modifier 链 `a.b` 中 `a` 在外、
`b` 在内。若写成 `Modifier.fillMaxSize().graphicsLayer{scale}.clipToBounds()`，裁剪层在变换层的
**内部** ⇒ 先按原始尺寸裁、**再放大**，溢出照旧（Compose 的层裁剪发生在该层自身的局部坐标空间，
缩放作用于裁剪结果之后）。必须让裁剪成为变换层的**祖先** —— 所以加在页根 Box 上。

**为什么这不会削弱已验收的行为**（数学论证，与 `clampAxis` 的一致性）：

- 内容 ≥ 视口（某一轴）时：`clampAxis` 把图的左边缘钉在 `[v−c, 0]` ⇒ 图**始终覆盖整个视口**，
  裁剪掉的只是屏幕外/页外那部分，用户看不到任何本该看到的内容被切掉。
- 内容 < 视口时：上限 0 ⇒ 图居中，本来就不越界，裁剪无作用。
- Fit 态（`zoom = 1`）：内容尺寸 ≤ 视口，两张图都不越界 ⇒ 裁剪是恒等操作，滑动视觉**逐位不变**。
- 裁剪不参与手势命中测试（手势用的是布局边界），`pointerInput` 的位置与顺序未动。

顺带说明：`Modifier.clipToBounds()` 在现代 Compose 里就是 `graphicsLayer(clip = true)` 的语法糖
（字节码验证：`androidx/compose/ui/ClipKt.clipToBounds` 直接转发到
`GraphicsLayerModifierKt.graphicsLayer-_6ThJ44$default`，clip 位为 1）——即它给每页加了一个
**裁剪层**，成本是每页一个 RenderNode，可接受（且它是 GPU 层裁剪，不参与 CPU 重绘）。

## 4. 手势三条行为未被破坏的证据（逐条）

| 验收行为 | 代码路径（未改） | 本次改动为何碰不到 | 单测 |
|---|---|---|---|
| **① Fit 时单指左右滑 = 切图** | `PhotoViewScreen.kt:549` `ViewerGesture.Swipe -> Unit`（**不消费任何事件**）；`496` `userScrollEnabled = !zoomed && !showFilmPanel` | 手势分支、消费策略、Pager 开关**一行未改**；`clipToBounds` 只影响绘制，不影响事件分发 | `singleFingerAtFitSwipesInsteadOfPanning` 绿 |
| **② 放大后单指拖 = 平移（不切图）** | `PhotoViewScreen.kt:537-547` `ViewerGesture.Pan`（`consume()` 掉事件）+ `496` `!zoomed` 关掉 Pager 滚动 | 同上；`pan` 仍照旧累加 + `clampPan` 夹紧（`clampPan`/`clampAxis` 未改），只是**不再被邻页复用**——当前页拿到的平移量与原实现**完全相同** | `singleFingerWhenZoomedPans` / `panIsClampedToContentOverflow` 绿 |
| **③ 双指捏合，下限 = 默认展示尺寸（Fit）** | `PhotoViewScreen.kt:523-536` `ViewerGesture.Zoom` + `clampZoom`（`MIN_ZOOM = 1f`，未改） | `clampZoom` / `MIN_ZOOM` / `MAX_ZOOM` / `ZOOM_HIRES` 全未改；新函数只决定"哪一页套用这个结果" | `zoomLowerBoundIsFitExactly` 等 5 例绿 |

**新增的"防修过头"用例**：`settledPageStillFollowsUserTransform` 明确断言停稳页必须**原样透传**
用户的缩放量和平移量（3× 就是 3×），防止这次修复把① 的缩放顺手削弱成恒等变换。

另外：切页时 `zoom` 复位（`PhotoViewScreen.kt:347`）+ 放大态 Pager 不滚 ⇒ **滑动过程中 `zoom` 恒为
MIN_ZOOM**，此时"邻页恒等变换"与旧代码的"邻页共享 1× 变换"**逐位相同** ⇒ 滑动切图的视觉零变化。

## 5. 构建 + 单测（真跑）

```bash
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

```
BUILD SUCCESSFUL in 18s
46 actionable tasks: 46 executed          ← 无 FROM-CACHE（唯一 UP-TO-DATE 是 generateDebugAssets，与测试无关）
EXIT=0
tests=420 failures/errors=0 skipped=0     ← 基线 418 + 本卡新增 2
apk: app/build/outputs/apk/debug/app-debug.apk (41,652,729 B, 14:57)
```

**新增用例（`PhotoViewZoomTest.kt` §⑥）**

- `neighborPageKeepsIdentityTransformWhileZoomed` —— 邻页在放大态必须是恒等变换（不缩放、不平移）
- `settledPageStillFollowsUserTransform` —— 停稳页必须原样透传缩放/平移

**Mutation 自检**（证明新用例不是恒真断言）：把 `pageScaleAt` 临时改回 `= zoom`（即恢复"共享变换"
的旧行为）后重跑 ⇒ `PhotoViewZoomTest > neighborPageKeepsIdentityTransformWhileZoomed FAILED`，
`EXIT=1`；恢复后重跑全量 ⇒ 420 绿（上面那份结果就是恢复后跑的）。这就是"改回去就红、改对了才绿"。

### ⚠️ 无法用 JVM 单测覆盖的部分（明确标注）

`clipToBounds()` 那一层（§3.2）**测不到**：它属于 Compose 的**绘制**行为，验证它需要真实渲染
（截图 / 像素级对比 / `compose-ui-test` 的 `captureToImage`）。本项目单测依赖只有
`testImplementation(libs.junit)`（`app/build.gradle.kts`），**没有 Robolectric，也没有
compose-ui-test**，JVM 里没有渲染管线 —— 所以这一条**必须真机或 Android Studio Preview 确认**。

本 bug 的**根因部分（§3.1）已被单测钉死**（可失败、可复现），裁剪只是"再也不会越界"的兜底。
我没有为裁剪层写任何"恒真断言"充数。

## 6. ⚠️ 未真机验证（明确标注）+ 建议的真机验证步骤

**未真机验证**：本机 `adb devices` 输出为空（"List of devices attached" 后无设备）；
SDK 目录 `/Users/wl/Library/Android/sdk` 下**没有 `emulator/emulator`、没有 `system-images/`**，
`~/.android/avd` 不存在 ⇒ 既无真机也无 AVD，**本轮修复的正确性只经代码推演 + 字节码/构建证据**，
上屏效果未经任何设备确认。

**建议的真机验证步骤**（建议架构师出包后执行，重点看①③⑤）：

1. 装 `app-debug.apk`，相册里准备**≥3 张能区分开的照片**（比如一张红、一张蓝，便于肉眼判断入侵）。
2. 打开中间那张 ⇒ **双指放大到 ~1.2×** ⇒ 观察视口**左右边缘**：修复前能看到别张照片的像素条带，
   修复后应只有黑底。
3. 继续放大到 2× / 3× / 5×，并在放大态**单指拖动**到四边极限 ⇒ 全程不得出现别张照片的像素，
   也不得露黑边（露黑边说明平移夹紧被破坏）。
4. **放大态末尾**：确认单指左右拖**不切图**（②），缩回 Fit 后单指左右滑**能切图**（①）。
5. 缩回 Fit（下限）⇒ 图应回到默认展示尺寸、居中（③）；再滑到第一张 / 最后一张放大（边界页只有
   一侧有邻页）⇒ 两侧都不应出现异常内容。
6. 打开底栏「胶片」预设面板 ⇒ 面板区域滑动/捏合不影响图片，返回键先关面板（P-3 回归）。
7. `adb logcat` 无需额外关注（本次改动不引入日志/异常路径）。

## 7. 改动清单

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt` | 新增 `pageScaleAt` / `pageShiftAt`（185-190）；类 KDoc 增 E2-23 段（207-214）；更正旧注释（557-559）；页根 Box 加 `clipToBounds()`（507）；`graphicsLayer` 接线改为只给停稳页（581-585）；新增 `androidx.compose.ui.draw.clipToBounds` import |
| `app/src/test/java/com/filmkit/app/ui/PhotoViewZoomTest.kt` | §⑥ 新增 2 例（`neighborPageKeepsIdentityTransformWhileZoomed`、`settledPageStillFollowsUserTransform`） |
| `docs/reports/fix-viewer-zoom-overflow-REPORT.md` | 本报告 |

**未动**：`CameraScreen.kt` / `EditorScreen.kt` / `MainActivity.kt` / `PhotoViewerModel.kt` / 引擎 /
预设数据层；`viewerGesture`、`clampZoom`、`clampAxis`、`fitDisplaySize`、`clampPan`、
`userScrollEnabled`、`beyondViewportPageCount`、手势消费策略**一行未改**。**未 commit**（留工作区待复核）。

## 8. 附：本轮用到的验证记录

- Compose 版本：BOM `2026.06.01` ⇒ `foundation`/`ui` **1.11.4**（`gradle/libs.versions.toml` + BOM pom）。
- `HorizontalPager` 默认 `pageSize = PageSize.Fill`：`javap -p -c` 解 `foundation-android:1.11.4` 的
  `androidx/compose/foundation/pager/PagerKt.class`，`HorizontalPager-oI3XNZo` 的默认路径上出现
  `PageSize$Fill.INSTANCE`（共 4 处，对应 4 个重载）。
- `Modifier.clipToBounds()` = `graphicsLayer(clip = true)`：`javap -p -c` 解 `ui-android:1.11.4` 的
  `androidx/compose/ui/draw/ClipKt.class`，`clipToBounds` 直接转发
  `GraphicsLayerModifierKt.graphicsLayer-_6ThJ44$default`（`iconst_1` + 掩码 `520191`）。
- mutation 自检日志：`/tmp/fk_mut.log`；最终全量日志：`/tmp/fk_e223_final.log`（临时文件，重启即失，
  结论已抄录在 §5）。
