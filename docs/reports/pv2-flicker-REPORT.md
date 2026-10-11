# 修复报告：相册滑动"闪一下"（PV-2）

**日期**：2026-10-11 ｜ **范围**：`app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt`（实现）
+ `app/src/test/java/com/filmkit/app/ui/Pv2FlickerTest.kt`（新增 9 例）+ `PhotoViewPreviewTest.kt`（口径更新 1 例）

**用户上报**：「相册左右滑动还是会闪一下」（v0.11.18 真机，PV-1 之后仍有）

> ⚠️ **本卡运行在无 `adb` 设备的会话里** —— 真机观感、Compose 的 effect 调度时机、
> `LaunchedEffect` 的实际启动顺序、纹理上传属于 Android/Compose 运行时行为，JVM 单测
> **测不到**。逐条标注见 §6，真机验收步骤见 §7。
> JVM 侧证据（构建 + 517 用例 + 4 组 mutation）见 §5。

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 现象（用户两次澄清，本卡的最高优先级输入） | ①「**闪一下不是糊到清晰，是屏幕闪烁**」②「**左右滑动一定会显示一下转圈，所以观感上就是闪一下**」 |
| **PV-1 的根因判断** | **被推翻**。PV-1 认定闪 = "预览档清晰度阶跃（糊 → 清晰）"，用户明确否认。PV-1 的两道修（预览档提到容器一半、180ms 交叉淡入）**针对的不是用户抱怨的现象** —— 这也解释了为什么它修完用户仍报"还是闪" |
| 根因 1（主因，= 用户看到的转圈） | `bitmap` 被**两个独立协程**分别写：切页 effect 写 `null`、解码 effect 写 `previews.get(u)`。两者之间只要有一次调度/绘制，那一帧屏上就是 `CircularProgressIndicator` |
| 根因 2（转圈为什么"一定"出现） | 预载依赖"Pager 组合邻页时顺手解一张"，**时机不由我们决定** ⇒ `previews.get(u)` 未命中 ⇒ 现解预览档 15–30ms（1–2 帧）⇒ 这 1–2 帧就是用户看到的转圈 |
| 根因 3（缓存里可能根本没有可用内容） | `rememberPreview` 首帧拿到 `viewSize == 0` ⇒ `previewRequestFor(0,0) = (1,1)` ⇒ 解出一张 **1×1** 的位图写进缓存；此后 `bmp != null` 让 effect 永久早退 ⇒ 缓存里要么是 1×1 垃圾、要么一直空 |
| 根因 4（次要，"屏幕闪烁"的另一半） | PV-1 新增的 `fade` **缺了 E2-23 给 zoom/pan 加的那道闸**：`viewerLayer` 的调用点对**所有页**读 `fade.value` ⇒ 淡入期间相邻页也半透明；而相邻页没有下层兜底 ⇒ 半透明压在黑底上 ⇒ 变暗 |
| 根因 5（次要，让 4 变成"偶发"） | 停稳页的复位靠 `fade.snapTo(1f)`，它是 **suspend**：实测 0–24ms **不确定**（8 次采样 `[4, 0, 0, 1, 0, …]`）⇒ "切页那一帧 alpha 复位了没有"取决于当次调度 |
| 修法 | ① `bitmap` 的所有写入**收敛到一个协程的一段同步代码**；② **主动预载 `page±2`**（PC `preloadPreviews` 口径）+ 删掉会产出 1×1 的那条自解路径；③ alpha 收进同一道闸（[`pageAlphaAt`]）；④ 转圈**延迟 150ms**（短暂未命中保持纯黑底） |
| 改动量 | `PhotoViewScreen.kt` **+276 / −68**（净增 208 行，其中大半是 KDoc）；手势 / 缩放边界 / 页裁剪 / Pager 开关 **一行未改**（§4.5 有 diff 证据） |
| 测试 | 新增 **9** 例；全量 **48 类 / 517 例 / 0 失败 / EXIT=0**（隔离副本口径，§5.1） |
| 未覆盖 | 真机观感、effect 调度时机、纹理上传、淡入的 GPU 成本 —— §6 逐条标注 |

---

## 1. 现象拆解：用户说的"闪"到底是哪一种

"闪"在代码里有**两种互不相干的可能**，PV-1 只查了第一种：

| 可能 | 视觉特征 | PV-1 的处理 | 用户的裁决 |
|---|---|---|---|
| **A. 清晰度阶跃** | 图一直是**可见**的，只是从糊变清晰（像素密度跳变） | PV-1 修的就是这个（预览档口径 + 交叉淡入） | ❌「**不是**糊到清晰」 |
| **B. 整片区域瞬时变空/变暗** | 图**消失**（露黑底）或**整体变暗**一两帧，或出现一个高对比的转圈 | **没查** | ✅「是**屏幕闪烁**」「一定会显示一下**转圈**」 |

用户的两条澄清把范围锁死在 B。B 在代码里有**三个独立来源**（§2.1–2.3 + §2.4），
它们可以同时发生，也可以各自单独发生。

---

## 2. 根因（机制级，带行号）

> 行号分两套：**【PV-1】** = `git show HEAD:…`（`c4fb809`，用户实测的那个版本）；
> **【PV-2】** = 本卡改完后的工作区。

### 2.1 根因 1（主因）：`bitmap` 被两个协程分别写

**【PV-1】的代码：**

```kotlin
// 协程 A —— LaunchedEffect(page, uris)          【PV-1 :405-420】
LaunchedEffect(page, uris) {
    zoom.floatValue = MIN_ZOOM
    pan.value = Offset.Zero
    bitmap = null                 // ← :408  「丢旧像素」
    under = null
    fade.snapTo(1f)               // ← :410  suspend（见 §2.5）
    error = ""
    status = ""
    val u = uris.getOrNull(page)
    if (u == null) { error = "照片已失效"; return@LaunchedEffect }
    meta = withContext(Dispatchers.IO) { queryMeta(context, Uri.parse(u)) }   // ← :419 挂起
}

// 协程 B —— LaunchedEffect(page, uris, hiRes, viewSize)   【PV-1 :425-480】
LaunchedEffect(page, uris, hiRes, viewSize) {
    ...
    if (shownUri != u) {
        under = null
        bitmap = previews.get(u)  // ← :442  「上新像素」
        shownUri = u
        shownTier = 0
    }
    if (bitmap == null) {         // ← :446  未命中 ⇒ 现解预览档（15–30ms）
        val preview = withContext(Dispatchers.IO) { decodeFit(context, parsed, pw, ph) }
        ...
    }
    ...
}
```

**两个 `LaunchedEffect` 是两个独立协程**（`LaunchedEffectImpl.onRemembered` 里 `scope.launch`）。
Compose 只保证它们在**同一帧被启动**，**不保证它们的第一段代码在同一批执行**——
`launch` 是 dispatch 的（`CoroutineStart.DEFAULT`），实际执行时机由 `AndroidUiDispatcher`
的帧回调队列决定。

**⇒ 只要 A 的 `bitmap = null`（`:408`）与 B 的 `bitmap = previews.get(u)`（`:442`）
之间隔了一次绘制，那一帧屏上就是转圈。** 而那一帧的条件恰好是
`p == page && bitmap == null && error.isEmpty()`：

```kotlin
p == page -> CircularProgressIndicator(color = FilmTheme.Accent)   // 【PV-1】:684
```

**PV-1 报告 §1.3 的推断（"两个 effect 在同一帧内同步执行到各自的第一个挂起点，
中间没有让出主线程，不会渲染出空帧"）是错的** —— 它假设了 `launch` 是同步执行的。
用户"**一定会**显示一下转圈"的实测就是这条推断的反例。

### 2.2 根因 2：转圈为什么"一定"出现 —— 未命中 ⇒ 现解 ⇒ 1–2 帧

即使 A/B 严格同批（无中间帧），**B 的 `previews.get(u)` 未命中**时
`bitmap` 仍然是 `null`，要等 `:446-455` 现解一张预览档（15–30ms）才有内容。

**⇒ 那 15–30ms（1–2 帧）里屏上就是转圈** —— 这就是用户说的"显示一下转圈"。
"一下"= 1–2 帧，与描述完全吻合（不是 60–150ms 的目标档解码时间）。

**为什么未命中是常态**：PV-1 的预载路径是"Pager 把邻页组合出来时顺手解一张"：

```kotlin
// 【PV-1】:647-654
val neighbor = if (p == page) null else uris.getOrNull(p)?.let {
    val (pw, ph) = previewRequestFor(viewSize.width, viewSize.height)
    rememberPreview(previews, it, context, pw, ph)   // ← 只有"被组合"才解
}
```

`beyondViewportPageCount = 1`（`:578`）只保证**组合**，不保证**什么时候解完**。
用户快速滑动时邻页刚被组合就要切过去 ⇒ 必未命中。

**PC 的做法完全不同**（用户点名"相册你可以参考 PC 的实现"）：
`ImageViewerFragment` 在**进入查看页时**与**每次翻页后**显式调用
`preloadPreviews(centerPos)`（半径 2），保证"任何时刻都有一张不透明的图垫底"
（`ImageAdapter.java:79` 的原注释：*small preview + native dimensions per position –
shown immediately under tiles so no black flash*）。PV-2 照抄这个口径（§4.2）。

### 2.3 根因 3：缓存里可能是一张 1×1 的垃圾位图

```kotlin
// 【PV-1】:860-879
@Composable
private fun rememberPreview(cache, uri, context, reqW, reqH): Bitmap? {
    var bmp by remember(uri) { mutableStateOf(cache.get(uri)) }   // ← key 只有 uri！
    LaunchedEffect(uri, reqW, reqH) {
        if (bmp != null || reqW <= 0 || reqH <= 0) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { decodeFit(context, Uri.parse(uri), reqW, reqH) }
        if (b != null) { cache.put(uri, b); bmp = b }
    }
    return bmp
}
```

**首帧 `viewSize == IntSize.Zero`**（`onSizeChanged` 还没跑）⇒
`previewRequestFor(0, 0)` = `(0/2).coerceAtLeast(1) to (0/2).coerceAtLeast(1)` = **`(1, 1)`**
（【PV-1】:141-142 —— `coerceAtLeast(1)` 让"未测量"退化成了"1 像素"）。

代进真实的解码链：`fitSizeFor(4000,3000,1,1) = (1,1)` ⇒ `sampleSizeFor(4000,3000,1,1) = 2048`
⇒ **解出一张 1×1 的位图**（成功，非 null）⇒ `cache.put(uri, 1×1)`、`bmp = 1×1`。

**此后 `viewSize` 测量完成、`LaunchedEffect(uri, 540, 850)` 的 key 变化重启 —— 但
`if (bmp != null …) return` 直接早退**（`remember(uri)` 的 key 不含 `reqW/reqH`，
`bmp` 不会重置）⇒ **永远不会解真正的预览档**。

**两种后果，都指向用户看到的现象：**

| 时序 | 缓存内容 | 切页时用户看到 |
|---|---|---|
| 1×1 解码在 `viewSize` 更新前完成 | 1×1 垃圾位图 | 一张纯色块铺满图片区（`ContentScale.Fit` 把 1×1 拉到 1080×1080）—— 也是"闪" |
| 1×1 解码被取消（`viewSize` 更新更快） | 空 | **转圈 1–2 帧**（§2.2） |

哪一条发生取决于设备/照片的解码速度 —— **JVM 测不到**（需要真机 `adb logcat` 打点）。
但两条都被 PV-2 的修法覆盖（§4.2 删掉了这条自解路径）。

### 2.4 根因 4：`fade` 缺了 E2-23 给 zoom/pan 加的那道闸

**【PV-1】:663-670 / :681：**

```kotlin
if (p == page) under?.let { u ->                                  // 下层：只有停稳页有
    Image(..., modifier = Modifier.viewerLayer(p == page, zoom, pan))          // 默认 alpha = 1
}
Image(
    bitmap = b.asImageBitmap(),
    ...
    modifier = Modifier.viewerLayer(p == page, zoom, pan) { fade.value },      // ← :681 所有页！
)
```

**【PV-1】:834-846 的 `viewerLayer`：**

```kotlin
private fun Modifier.viewerLayer(isSettledPage, zoom, pan, alpha: () -> Float = { 1f }) =
    fillMaxSize().graphicsLayer {
        val z = pageScaleAt(isSettledPage, zoom.floatValue)      // ← 有 isSettledPage 闸
        scaleX = z; scaleY = z
        translationX = pageShiftAt(isSettledPage, pan.value.x)   // ← 有 isSettledPage 闸
        translationY = pageShiftAt(isSettledPage, pan.value.y)
        this.alpha = alpha()                                     // ← 闸在调用点，而 :681 漏了
    }
```

**`pageScaleAt`（`:227`）/ `pageShiftAt`（`:231`）存在的唯一理由**就是"整页共享的 state
不能漏给相邻页"（E2-23 的根因）。alpha 是 PV-1 新加的第三个整页共享 state，
**它没有走同一道闸** —— 而 PV-1 的 KDoc（`:832`）自己写着"默认 alpha = 1（相邻页与下层都用默认值）"，
与 `:681` 的实现直接矛盾。

**后果**：`fade < 1` 的 180ms 里，相邻页的 `Image` 也是半透明。相邻页**没有下层兜底**
（`under` 只在 `p == page` 时绘制）⇒ 半透明的图直接压在 `Color.Black`（`:509`）上 ⇒ **整片变暗**。
用户滑动时邻页正从边缘滑进视口 ⇒ 变暗落在视口正中。

### 2.5 根因 5：复位靠一个挂起函数，而它的延迟不确定

**【PV-1】:410 的 `fade.snapTo(1f)`** 是 `suspend`。`Animatable` 用 `MutatorMutex` 串行化
`snapTo` / `animateTo`：若 `LaunchedEffect(bitmap)`（`:489-492`）里的 `animateTo` 正在跑，
`snapTo` 要等它响应取消并释放锁。

**实测（`Pv2FlickerTest.snapToOnRunningAnimationIsNotInstant`，本机 8 次采样）：**

```
PV-2 实验：动画运行时 snapTo 耗时样本 = [4, 0, 0, 1, 0, …] ms（一帧 = 16ms）
```

**⇒ 它有时同步（0ms）、有时挂起 1–4ms（上一次跑是 24ms）。**
这个**不确定性本身**就是问题：切页那一帧的 alpha 复位与否取决于当次调度 ⇒ **偶发的一两帧半透明**
⇒ 用户看到"闪一下"。

---

## 3. 候选清单 × 有/无 × 证据（穷举）

| # | 候选 | 判定 | 证据（行号 = 【PV-1】） |
|---|---|---|---|
| 1 | `fade` 对所有页生效 ⇒ 相邻页半透明露黑底 | **有**（真缺陷） | `:681` 无 `isSettledPage` 判据 vs `:227`/`:231` 有；`under` 只画 `p == page`（`:663`）；`viewerLayer` 自己的 KDoc（`:832`）与实现矛盾 |
| 2 | `fade.snapTo(1f)` 挂起 ⇒ 切页帧未复位 | **有**（真缺陷） | `:410` 是 suspend；实测样本 `[4,0,0,1,0,…]ms`（`Pv2FlickerTest`） |
| 3 | `under = null` 与 `fade.snapTo(1f)` 之间的窗口 | **有，但被 #2 覆盖** | `:409-410` 相邻；`under = null` 是同步的，窗口只来自 `snapTo` |
| 4 | **跨 effect 窗口**：`bitmap = null`（`:408`）与 `bitmap = previews.get(u)`（`:442`）在两个独立协程里 | **有（主因）** | `:405` 与 `:425` 是两个 `LaunchedEffect`；`launch` 是 dispatch 的，不保证同批；PV-1 §1.3 的"同帧完成"是**推断**，被用户实测否定 |
| 5 | 转圈出现/消失本身 | **有**（用户点名） | `:684` `p == page -> CircularProgressIndicator`；未命中 ⇒ `bitmap == null` 持续 15–30ms |
| 6 | `Modifier.background(Color.Black)`（`:509`）与图片层的绘制时序 | **无** | `:509` 是静态背景（不参与重组），图片层是它的子节点，同一次绘制里先背景后内容 |
| 7 | `b.asImageBitmap()`（`:672`）每次重组新建包装 ⇒ 纹理重传一帧空白 | **无**（是性能项，不是闪源） | `javap` 核过 `androidx.compose.ui.graphics.AndroidImageBitmap` 的**全部成员**：只有 `getWidth/getHeight/getConfig/getColorSpace/readPixels/getHasAlpha/prepareToDraw` —— **没有覆写 `equals`/`hashCode`** ⇒ `Image` 的 `remember(bitmap)` 会失效、重建 `BitmapPainter`（每次重组一次对象分配）；但底层是**同一个** `android.graphics.Bitmap` ⇒ 纹理不重传、不会有一帧空白 |
| 8 | Pager overscroll / 边缘效果 | **无（仅边界）** | `HorizontalPager`（`:574-584`）只传了 `state` / `beyondViewportPageCount` / `userScrollEnabled` / `modifier`，`overscrollEffect` 用默认 ⇒ 只在**第一张/最后一张**的边界显示 stretch/glow；用户"左右滑动"在中间不触发 |
| 9 | 系统栏 / 沉浸模式显隐造成整屏闪 | **无** | 全仓 grep `enableEdgeToEdge` / `WindowInsetsController` / `setDecorFitsSystemWindows` / `SYSTEM_UI_FLAG` / `WindowCompat` ⇒ **零命中**（`SettingsScreen.kt:24` 只有一个 `WindowInsets` import，与查看页无关）；`Theme.FilmKit` = `android:Theme.Material.Light.NoActionBar`（无全屏 flag）；查看页用 `statusBarsPadding()` + `navigationBarsPadding()` ⇒ 系统栏**常驻**，滑动不改变可见性 |
| 10 | 与相机 `SurfaceView` 共存（Surface 生命周期抖动） | **无** | `CameraScreen.kt:914` 的 `SurfaceView` 只在 `screen == "camera"` 时组合；`MainActivity` 的路由是 `when (screen)`（互斥分支，`"viewer"` 一支在 `MainActivity.kt:138`）⇒ 查看页与相机会话**不共存** |
| 11 | **1×1 垃圾位图**：首帧 `viewSize == 0` ⇒ `previewRequestFor(0,0) = (1,1)` | **有**（真缺陷） | `:141-142` 的 `coerceAtLeast(1)`；`:867` 的 `remember(uri)` 不随 `reqW/reqH` 重置 ⇒ `:869` 的 `bmp != null` 永久早退；`fitSizeFor(4000,3000,1,1)=(1,1)` ⇒ `sampleSizeFor=2048` ⇒ 1×1 |
| 12 | 位置/缩放跳变（替换时图片挪位） | **无** | 两档是同一张图、同一次 `decodeFit`、同一个 EXIF 变换；`ContentScale.Fit` 的显示框只由**宽高比**决定，而 `src/sample` 的整数除保持比例 ⇒ 显示框逐像素相同（PV-1 §1.3 已论证，本次复核成立） |
| 13 | 颜色/dither 差异 | **无** | 两档都走同一个 `decodeOriented` → `BitmapFactory.decodeStream`，`Options` 只设 `inSampleSize`（`ImageDecode.kt:50`），无 `inPreferredConfig`/`inDither`/色彩空间分支 |
| 14 | 上一张残留/错图 | **无**（但 PV-1 的写法会短暂产生"错图"风险，见 §4.1） | `:408` 的 `bitmap = null` 保证换页先清空；PV-2 进一步保证"位图只可能来自新页"（`frameAfterPageChange`） |

**没有查到的（如实标注）**：设备厂商 ROM 的合成器行为、`RenderNode` 的 alpha 合成路径、
Choreographer 的帧回调顺序 —— 这些需要真机 systrace，JVM 侧无法触及。

---

## 4. 修法

### 4.1 主修 ①：`bitmap` 的所有写入收敛到一个协程的一段同步代码

**判据**：**任何时刻都不存在"新页已选中但位图为 null"的可见帧**（除了真正的"还没有内容"，
而那个状态由 ④ 的延迟转圈兜住）。

```kotlin
// 【PV-2】:541-556 —— 切页 effect 现在**只做文字**，一个字节的位图状态都不碰
LaunchedEffect(page, uris) {
    error = ""
    status = ""
    val u = uris.getOrNull(page) ?: run { error = "照片已失效"; return@LaunchedEffect }
    meta = withContext(Dispatchers.IO) { queryMeta(context, Uri.parse(u)) }
}

// 【PV-2】:612-640 —— 解码 effect：换页复位 + 丢旧 + 上新，**同一段没有挂起点的代码**
LaunchedEffect(page, uris, hiRes, viewSize) {
    ...
    if (shownUri != u) {
        zoom.floatValue = MIN_ZOOM          // ① 复位（与位图替换同一帧生效）
        pan.value = Offset.Zero
        under = null                        // ② alpha 判据立刻失效（见 ③）
        val frame = frameAfterPageChange(u, previews.get(u))   // ③ 纯函数，一次给全
        bitmap = frame.bitmap
        shownUri = frame.uri
        shownTier = frame.tier
    }
    ...
}
```

`frameAfterPageChange` 是**纯函数**（【PV-2】:205-220），判据写在它的 KDoc 里、断言钉在
`Pv2FlickerTest.pageChangeFrameNeverReusesThePreviousBitmap`：

```kotlin
internal fun <B> frameAfterPageChange(uri: String, cacheHit: B?): ViewerFrame<B> =
    ViewerFrame(uri = uri, tier = TIER_PREVIEW, bitmap = cacheHit)
```

两条判据：**(a) 位图只可能来自新页**（命中 = 新页预览档；未命中 = `null`，
**绝不沿用上一页** —— 那会让新页显示上一张的像素，错图比黑底更糟）；
**(b) uri / 档位 / 位图一次给全**（不允许"先清空、再由另一个协程填"的两步走）。

**`shownTier` 用命名常量**（`TIER_PREVIEW` / `TIER_FIT` / `TIER_HIRES`，【PV-2】:196-198）
而不是裸 `0/1/2` —— `shownTier >= target` 这种比较里的裸 `0` 读不出方向。

### 4.2 主修 ②：主动预载 `page±2`（PC 口径）+ 删掉 1×1 那条路径

```kotlin
// 【PV-2】:565-582
LaunchedEffect(page, uris, viewSize) {
    val vw = viewSize.width; val vh = viewSize.height
    if (vw <= 0 || vh <= 0) return@LaunchedEffect      // ← 未测量直接不解（根因 3 的守卫）
    val (pw, ph) = previewRequestFor(vw, vh)
    for (d in preloadOffsets(PRELOAD_RADIUS)) {        // = [-1, +1, -2, +2]，近的优先
        val u = uris.getOrNull(page + d) ?: continue
        if (previews.get(u) != null) continue          // 已有 ⇒ 零成本
        val b = withContext(Dispatchers.IO) { decodeFit(context, Uri.parse(u), pw, ph) }
        if (b != null) { previews.put(u, b); previewsVersion++ }
    }
}
```

- **`PRELOAD_RADIUS = 2`**（【PV-2】:169）—— 与 PC 的 `preloadPreviews(centerPos)` 一致。
  `preloadOffsets`（【PV-2】:226）是纯函数：近的优先（±1 在 ±2 之前），不含 0（当前页由主解码负责）。
- **顺序预载，不并发**：① 不与当前页的目标档解码抢 CPU；② 不让 4 张预览同时解码（瞬时内存 ×4）。
- **`rememberPreview` → `rememberNeighborPreview`**（【PV-2】:1083-1088）：**删掉自解逻辑**，
  只读缓存。职责单一化：预载负责**生产**，邻页负责**显示**。这同时消灭了根因 3（1×1 垃圾位图）
  和"同一个 key 被解两次"的重复解码。
- **`previewsVersion`**（【PV-2】:486）：`ThumbCache` 是普通对象不是 state，预载填进去后
  已组合的邻页不会自己重组 ⇒ 用一个计数器当 remember key，把"缓存有更新"告诉它们。

**缓存容量重算**（`PREVIEW_CACHE_COUNT` 3 → **4**，字节上限保持 12MB）：

| 项 | 值 | 依据 |
|---|---|---|
| 预载窗口张数 | **4**（`page±2`，不含当前页） | `preloadOffsets(2).size` |
| 单张字节（1080p/1440p/1644p，12MP 横竖） | **1000×750 ≈ 2.9MB** | `Pv2FlickerTest.previewCacheHoldsTheWholePreloadWindow` 用真实 `fitSizeFor`/`sampleSizeFor` 算 |
| 窗口总字节 | **≈ 11.4MB** | 4 × 2.9MB |
| 上限 | **12MB** | 留 0.6MB 余量 |
| 例外（1644p **竖**图） | 预览档落 sample=2 ⇒ **12MB/张** ⇒ 只装 1 张 | PV-1 §4.3 台账同一格；这种设备上预载退化，降级路径兜底（不会出错，只是回到 PV-1 的命中率） |

### 4.3 主修 ③：alpha 收进与 zoom/pan 同一道闸

```kotlin
// 【PV-2】:323 —— 与 pageScaleAt / pageShiftAt 同款判据
internal fun pageAlphaAt(isSettledPage: Boolean, fading: Boolean, fade: Float): Float =
    if (isSettledPage && fading) fade else 1f
```

- **相邻页恒 1**（修根因 4）；
- **停稳页在"没有淡入"时恒 1** —— 而 `fading` 由调用方传 `under != null`，
  `under` 在切页 effect 里是**同步**清掉的 ⇒ **切页那一帧 alpha 必然是 1，与 `fade` 停在哪个值、
  有没有垂死动画全都无关**（修根因 5）。

**为什么判据不是 `fade < 1`**：那等于把"这一帧透不透明"交给一个挂起函数的结果（§2.5 实测不确定）。

**为什么不用"把 `Animatable` 换成 `mutableFloatStateOf` + `animate()`、复位改成同步赋值"**
（这是我收到的建议方案，**否决**）：同步赋值本身没问题，但**还没被取消的动画协程会在下一帧
把它覆盖掉** —— `LaunchedEffect` 的取消发生在下一帧的组合，在那之前动画协程照样写值。
这条用 `Pv2FlickerTest.aDyingAnimationOverwritesASyncResetOnTheNextFrame` 钉住了
（实测：同步复位后立刻 = 1.0，**一帧后 = 0.11**）。
`pageAlphaAt` 用 `under != null` 门控是**零窗口**的：`under == null` 时 alpha **恒为 1**，
不管 `fade` 是多少。

**配套**（【PV-2】:589-593）：手指一动就结束淡入 ——

```kotlin
LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.isScrollInProgress }
        .collect { scrolling -> if (scrolling) under = null }
}
```

淡入（180ms）与下一次滑动重叠时，**停稳页**会在整段滑动里半透明（露黑底）。
清掉 `under` ⇒ 判据立刻失效 ⇒ alpha 回 1；正在跑的 `animateTo` 继续写 `fade` 也无所谓。
**淡入本身保留**（只在静止时可见，仍有抹平预览档→目标档清晰度过渡的价值）。

### 4.4 主修 ④：转圈延迟 150ms（兜底，不许"闪一下转圈"）

```kotlin
// 【PV-2】:597-603
LaunchedEffect(page, bitmap) {
    showSpinner = false
    if (bitmap != null) return@LaunchedEffect
    delay(SPINNER_DELAY_MS)          // = 150ms
    if (bitmap == null) showSpinner = true
}
```

```kotlin
p == page && showSpinner -> CircularProgressIndicator(color = FilmTheme.Accent)   // 【PV-2】:895
else -> Unit                                                                       // 纯黑底
```

**为什么选"延迟显示"而不是"干脆不画"**：用户给了两个选项，我选延迟 ——
① 未命中只持续 1–2 帧时**不画**（黑底与页面背景同色，看不出变化 ⇒ 消除"转圈闪"）；
② 真的等久了（首次点开一张没预载的图）仍然给反馈，**不静默**（ADR-12）。
**代价**：多一个 state + 一条 effect；极端情况下（>150ms 的未命中）转圈仍会出现 ——
但那时它出现是**正确的**（用户确实在等），而不是"闪一下"。

**没有选"从相册网格的 `ThumbCache` 拿缩略图当首帧"**（用户给的另一个选项）：
那条路要改路由层（把位图或缩略图路径传进查看页）并管它的生命周期，改动面远大于本卡；
而且 §4.2 的主动预载已经把"未命中"压缩到只剩"首次点开"这一种情况。
记录为后续项（PV-1 §7.3 已提过同一条）。

### 4.5 手势 / 缩放边界 / 页裁剪：一行未改

```bash
$ cd /tmp/pv2-clean   # 隔离副本 = HEAD + 仅 PV-2 改动
$ git diff app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt | grep -E "^[+-]" | \
    grep -E "viewerGesture|clampZoom|clampPan|awaitEachGesture|awaitFirstDown|awaitPointerEvent|calculateZoom|calculatePan|clipToBounds|userScrollEnabled|MIN_ZOOM =|MAX_ZOOM|ZOOM_HIRES =|detectTapGestures|consume\(\)"
+ * （[viewerGesture] / [clampZoom] / [clampPan] / [pageScaleAt] / [pageShiftAt] / `clipToBounds` /
+ * `userScrollEnabled`）**一行未改**。
（两行都是 PV-2 类级 KDoc 里**列举这些名字的文字**，不是代码改动）
```

`viewerGesture` / `clampZoom` / `clampPan` / `pageScaleAt` / `pageShiftAt` / `fitDisplaySize` /
`clampAxis` / `viewerLayer` 的**变换语义**逐位未改（`viewerLayer` 只多了一个 `pageAlphaAt`
判据，缩放/平移那三行原样）。`PhotoViewZoomTest`（20 例）在 §5.1 的全量跑里 **0 失败**。

---

## 5. 构建 / 单测 / mutation

### 5.1 全量验证

```bash
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks
```

**跑了两个口径**（原因见下面的"为什么两个"）：

| 口径 | 工作树 | 任务数 | 测试类 | 用例数 | failures/errors/skipped | EXIT |
|---|---|---|---|---|---|---|
| **A. 隔离副本**（干净 `HEAD c4fb809` + 仅 PV-2 改动） | `git worktree` @ `/tmp/pv2-clean` | `27 actionable tasks: 27 executed` | **48** | **517** | **0 / 0 / 2** | **0** |
| **B. 主工作区**（`HEAD` + PV-2 + 另一张卡进行中的改动） | `/Users/wl/Downloads/胶片相机` | `27 actionable tasks: 27 executed` | **49** | **529** | **0 / 0 / 0** | **0** |

**为什么两个**：会话期间另一张卡正在改 `CameraScreen.kt` / `CameraPresetPanel.kt` /
`EditorScreen.kt` / `CameraPresetApply.kt`（新增 `ParamControls.kt`），过程中反复出现**瞬时**
编译错误（`when` 不穷尽、`DragHolder`/`cameraPresetEditorButtons` 未解析、参数类型不匹配…），
把测试跑打断了好几次。所以：

- **口径 A** 是本卡的**权威结果** —— 它把仓库隔离到干净的 `HEAD`，只叠加 PV-2 的改动，
  不受他卡影响（`git status --porcelain` 在 worktree 里只有 3 个文件：`PhotoViewScreen.kt` +
  `PhotoViewPreviewTest.kt` + 新增的 `Pv2FlickerTest.kt`）；
- **口径 B** 是"主工作区在他卡编译通过的窗口里"的结果，作为交叉印证（用例数多出的 12 例
  来自他卡新增/改动的测试类）。

**收尾时主工作区又跑了一次（同一条命令）：`529 tests completed, 2 failed`，EXIT=1 ——
两个失败都属于他卡**（他在同一时段继续改预设面板，测试还没跟上），**与 PV-2 无关**：

```
FAILED [PresetPanelUiTest] cameraMenuStillHasTheModifyActionsAndBuiltinOnesAreGreyedOut
  拍照界面的动作集与顺序（主操作在前、危险操作在后）
    expected:<[套用, 查看配置, 编辑, 重命名, 复制, 删除]>
    but was:<[套用, 查看配置, 编辑, 重命名, 复制, 恢复默认, 删除]>      ← 他卡新加的「恢复默认」动作

FAILED [CameraPresetPanelUiTest] cameraPanelRendersEveryCapabilityWithoutRewritingTheLogic
  CameraPresetPanel.kt 少了 presetDetailSections( ⇒ 某条能力被藏起来了   ← 他卡正在重构该文件
```

两个测试类都**不引用** `PhotoViewScreen` / `viewer`（grep 零命中），与查看页没有调用关系；
而同一个工作区在更早的窗口里（他卡那两处改动落地之前）跑出的是 **529 / 0 失败 / EXIT=0**。
**口径 A 的 517 例 0 失败才是本卡的交付证据。**

两个 skipped（两个口径里都是这 2 条，与 PV-2 无关）是需要真实样片文件的既有用例：
`EditorExportTest.realFilesConfirmTheMagicNumberVerdict`、
`ImageDecodeTest.realDngOrientationRotatesToPortraitLikeTheStill`。

**口径 A 的副本已清理**（不让临时验证环境留在仓库元数据里）。复现：

```bash
git worktree add /tmp/pv2-clean HEAD
cp local.properties /tmp/pv2-clean/
cp app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt /tmp/pv2-clean/app/src/main/java/com/filmkit/app/ui/
cp app/src/test/java/com/filmkit/app/ui/{Pv2FlickerTest,PhotoViewPreviewTest}.kt /tmp/pv2-clean/app/src/test/java/com/filmkit/app/ui/
cd /tmp/pv2-clean && JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks
```

**本卡未触碰**他卡正在编辑的任何文件。

PV-2 新增 **9** 例（`Pv2FlickerTest`，`tests="9" failures="0"`）：

```
ok neighborPagesNeverInheritTheFadeAlpha              ← 修法 ③（根因 4）
ok settledPageIsOpaqueWheneverNoFadeIsRunning         ← 修法 ③（根因 5）
ok settledPageUsesFadeOnlyWhileFading                 ← 防"把淡入整段删掉"的过度修正
ok pageChangeFrameNeverReusesThePreviousBitmap        ← 修法 ①（根因 1，主因）
ok pageChangeFrameIsIndependentOfWhatWasOnScreenBefore← 修法 ①（原子性）
ok preloadCoversBothSidesNearestFirstAndSkipsCurrentPage ← 修法 ②（PC 口径）
ok previewCacheHoldsTheWholePreloadWindow             ← 修法 ②（容量账）
ok snapToOnRunningAnimationIsNotInstant               ← 设计依据（根因 5 的证据）
ok aDyingAnimationOverwritesASyncResetOnTheNextFrame  ← 设计依据（否决候选方案的证据）
```

`PhotoViewPreviewTest` 更新 **1** 例（`previewCacheHoldsThreePreviewsOfTheNewTier`：
`PREVIEW_CACHE_COUNT` 3 → 4，消息改为 PV-2 口径）—— 它的另外 4 例钉的是
`previewRequestFor` 的预览档口径，**PV-2 没有改那个口径**，原样通过。
`PhotoViewZoomTest`（20 例，E2-20 手势仲裁 + E2-23 缩放边界）**0 失败**。

### 5.2 mutation 自检（四组，全部变红）

在隔离副本（`/tmp/pv2-clean`）里逐组把主修改回旧口径，每组跑完立即恢复：

| # | 对应主修 | mutation（改回旧口径） | 结果 |
|---|---|---|---|
| **M1** | ① 切页原子替换 | `frameAfterPageChange` 的 `bitmap = cacheHit` → `bitmap = null`（模拟 PV-1「清空后没人填」） | **2 例红** |
| **M2** | ③ alpha 门控 | `pageAlphaAt` 的 `if (isSettledPage && fading) fade else 1f` → `if (isSettledPage) fade else 1f`（去掉 `fading`，回到"看 `fade` 的值"） | **1 例红** |
| **M3** | ③ alpha 同闸 | `pageAlphaAt` → `fade`（回到 PV-1 原样：所有页都吃 `fade`） | **2 例红** |
| **M4** | ② 主动预载范围 | `PRELOAD_RADIUS = 2` → `1`（PC 口径退化） | **1 例红** |

**原始输出：**

```
════════ M1 主修①：切页原子帧永远为空（模拟 PV-1「清空后没人填」）════════
> Task :app:testDebugUnitTest FAILED
Pv2FlickerTest > pageChangeFrameNeverReusesThePreviousBitmap FAILED
Pv2FlickerTest > pageChangeFrameIsIndependentOfWhatWasOnScreenBefore FAILED
9 tests completed, 2 failed

════════ M2 主修③：去掉 fading 门控（回到「看 fade 的值」）════════
> Task :app:testDebugUnitTest FAILED
Pv2FlickerTest > settledPageIsOpaqueWheneverNoFadeIsRunning FAILED
9 tests completed, 1 failed

════════ M3 主修③：回到 PV-1 原样（所有页都吃 fade）════════
Pv2FlickerTest > settledPageIsOpaqueWheneverNoFadeIsRunning FAILED
Pv2FlickerTest > neighborPagesNeverInheritTheFadeAlpha FAILED
9 tests completed, 2 failed

════════ M4 主修②：预载半径 2 → 1（PC 口径退化）════════
Pv2FlickerTest > preloadCoversBothSidesNearestFirstAndSkipsCurrentPage FAILED
9 tests completed, 1 failed
```

**M1 的断言消息（最核心的一条，直接对应主因）：**

```
FAILED pageChangeFrameNeverReusesThePreviousBitmap
  java.lang.AssertionError: expected:<preview-of-2> but was:<null>

FAILED pageChangeFrameIsIndependentOfWhatWasOnScreenBefore
  java.lang.AssertionError: expected:<preview-of-2> but was:<null>
```

**已恢复**：四组跑完立即 `cp` 回原文件，`grep -c MUTATION PhotoViewScreen.kt` = **0**
（工作区与隔离副本都验过）；恢复后口径 A 的全量跑再次全绿（§5.1）。

### 5.3 量化台账（测试 stdout，跑的是真实的 `fitSizeFor` / `sampleSizeFor`）

```
PV-2 缓存台账：1080p/12MP 预览档 = 1000×750(sample=4) = 2929KB/张
PV-2 实验：动画运行时 snapTo 耗时样本 = [4, 0, 0, 1, 0, …] ms（一帧 = 16ms）
PV-2 实验：同步复位后立刻=1.0，一帧后=0.11
```

### 5.4 内存账（最坏情况，设死的上限）

| 项 | 1080p/1440p/1644p 横竖（12MP） | 1644p **竖**图（例外格） |
|---|---|---|
| 预载缓存常驻（`PREVIEW_CACHE_BYTES`） | **12MB**（4 × 2.9MB ≈ 11.4MB） | 12MB（1 × 12MB，其余被 LRU 淘汰） |
| 当前页目标档（`bitmap`） | **12MB**（Fit 档 2000×1500） | 12MB |
| 淡入下层（`under`，仅 180ms） | **3MB**（预览档） | 12MB |
| 解码瞬时（`BitmapFactory` 内部） | 峰值 +12MB | 峰值 +12MB |
| **常驻上界** | **≈ 24MB** | **≈ 24MB** |
| **瞬时峰值上界** | **≈ 39MB**（解码中 + `under`） | **≈ 48MB** |
| 放大到 2× 时（`ZOOM_HIRES`，用户主动） | +48MB（全分辨率档）+ 12MB（`under` = Fit 档） | 同 |

**上限是设死的**：`ThumbCache.put` 的 `while (bytes > maxBytes || size > maxCount)` 保证
**常驻**永不超 12MB（单张超过上限时它插入后立刻被淘汰 ⇒ 缓存为空，而不是无限增长）。
极端容器（图片区宽 ≥ 2000px ⇒ 目标档落 sample=1 ⇒ 48MB/张）下缓存装 0 张 ——
**预载白解一张，但不常驻**，这是 PV-1 就存在的边界（PV-1 是 12MB 上限装 1 张），PV-2 未恶化常驻上界。

---

## 6. ⚠️ 未验证部分（逐条标注）

| 项 | 为什么 JVM 测不到 | 需要什么 |
|---|---|---|
| **"转圈不再出现"本身** | `CircularProgressIndicator` / `LaunchedEffect` 调度 / 帧回调全在 Compose+Android 运行时；本项目只有 junit，无 Robolectric / compose-ui-test | **真机 + 慢动作录屏**（§7 判据 A） |
| **两个 effect 是否真的不同批** | 需要真实的 `AndroidUiDispatcher` + Choreographer；JVM 里没有帧循环 | 真机 `adb shell atrace` / 在 `bitmap = null` 与 `bitmap = …` 两侧打日志看时间戳 |
| **未命中率**（主动预载是否真的赶上） | 需要真机 IO/解码耗时 | 真机打点统计 `previews.get(u) == null` 的次数 |
| **1×1 垃圾位图那条路**（根因 3 的两种后果哪种发生） | 需要真机首帧时序 | 真机打点：缓存里第一张的 `bitmap.width` |
| **淡入的观感**（180ms 是否平滑、是否拖沓） | 绘制层 | 真机目视 + 慢动作录屏 |
| **`asImageBitmap()` 每次重组重建 `BitmapPainter` 的代价** | 绘制层（已确认 `AndroidImageBitmap` 无 `equals`，但"重建 painter 是否掉帧"要真机） | 真机 GPU 渲染条 / Profiler |
| **内存峰值** | 账算得出来（§5.4），峰值测不到 | 真机 Android Studio Profiler（Memory → Bitmap） |
| **淡入期间滑动的残余**（`isScrollInProgress` 那条 effect 的响应延迟） | 需要真机手势 + 帧调度 | 真机：快速连滑时盯图片区有无变暗 |
| **设备厂商 ROM 的合成器行为** | 完全在设备侧 | 真机对照实验（同版本不同机型） |

---

## 7. 真机验收判据（用户怎么判断"不闪了"）

**准备**：同一台机器并排装 v0.11.18（PV-1，有转圈）与本版，同一批照片（至少含 12MP 横图 + 竖图各 3 张）。

1. **判据 A（主判据 —— 转圈，用户点名的现象）**：相册 → 点开一张 → **左右连续滑动 ≥20 张**。
   - ✅ 通过 = **一次转圈都看不到**（图片区在切换过程中始终有内容，或最多是纯黑一瞬）。
   - ❌ 不通过 = 仍能指认出"转圈出现又消失"。→ 用判据 E 定位。
2. **判据 B（无变暗）**：滑动时盯住图片区**整体亮度**。
   - ✅ 通过 = 图片区在滑动全程**不出现整体发暗/发灰**。
   - ❌ 不通过 = 有一次整体变暗 ⇒ 说明 alpha 那道闸又漏了（查 `pageAlphaAt` 的调用点）。
3. **判据 C（无黑帧）**：盯住换档瞬间，**不应出现纯黑帧**。
4. **判据 D（E2-20 / E2-23 回归 —— 一条都不许退化）**：
   - Fit 态单指横滑**仍能切图**（手势层一个事件都不消费）；
   - 双指捏合能到 5×，下限**停在 Fit**；
   - 放大后单指拖动平移，松手不越界、不留黑边；
   - 放大后左右滑**不切图**；
   - 放大态下**看不到隔壁照片的像素**（E2-23）；
   - 放大到 3–5× 时切页/升档，**下层与上层无重影**。
5. **判据 E（量化定位，判据 A 不通过时用）**：系统录屏开**慢动作 120/240fps**，录"滑动 → 停下"1 秒，逐帧看换页处。
   - 旧版：能看到 1–2 帧的转圈（圆环）。
   - 本版：应是"上一张 → 下一张"直接切换（命中预载），或最多一帧纯黑（未命中且 <150ms）。
   - 若仍有转圈 ⇒ 说明未命中且超过 150ms ⇒ 查预载是否真的跑了（`preloadOffsets` 的半径、缓存是否被挤爆）。
6. **判据 F（代价确认）**：首次从相册点开一张（无预载）的等待**不应比 v0.11.18 明显变长**。
   本版首帧仍是"现解预览档"（15–30ms），与 PV-1 同量级；但**滑动**时应明显更快（命中预载 ⇒ 零等待）。
7. **判据 G（内存）**：连续滑动 100 张以上，**不应出现 `OutOfMemoryError` 或明显的 GC 卡顿**。
   账见 §5.4（常驻 ≈24MB）。

---

## 8. 改动清单

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt` | **切页 effect**（:541）只留文字（`error`/`status`/`meta`），**删掉 `bitmap = null` / `under = null` / `fade.snapTo(1f)`**；**新增主动预载 effect**（:565）；**新增滑动结束淡入 effect**（:589）；**新增转圈延迟 effect**（:597）；**解码 effect**（:612）接管换页复位 + 原子替换（:628-637）；**新增纯函数** `pageAlphaAt`（:323）/ `frameAfterPageChange` + `ViewerFrame`（:205-220）/ `preloadOffsets`（:226）/ 档位常量（:196-198）/ `PRELOAD_RADIUS`（:169）/ `SPINNER_DELAY_MS`（:181）；`PREVIEW_CACHE_COUNT` 3 → 4（:160）；`viewerLayer` 调用点改用 `pageAlphaAt`（:885）；转圈改 `showSpinner`（:895）；`rememberPreview` → `rememberNeighborPreview`（:1083，删掉自解逻辑）；新增 `previewsVersion`（:486）/ `showSpinner`（:488）两个 state |
| `app/src/test/java/com/filmkit/app/ui/Pv2FlickerTest.kt` | **新增**，9 例（§5.1 清单） |
| `app/src/test/java/com/filmkit/app/ui/PhotoViewPreviewTest.kt` | 更新 1 例（缓存张数口径 3 → 4） |

**未触碰**（`git status` 可证）：`CameraScreen.kt` / `CameraPresetPanel.kt` / `CameraPresetApply.kt` /
`EditorScreen.kt` / `ParamControls.kt` / `GalleryScreen.kt` / `ImageDecode.kt` / `GalleryModel.kt` /
`FrameRenderer.cpp` / `assets/presets/`（其中前五个正被另一张卡编辑）。

**未 commit**（按要求留工作区）。
