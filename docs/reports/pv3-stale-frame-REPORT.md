# 修复报告：切页后上一张图闪现（PV-3）

**日期**：2026-10-11 ｜ **范围**：`app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt`（+85/−9）
+ `app/src/test/java/com/filmkit/app/ui/Pv3StaleFrameTest.kt`（新增 7 例）

**用户实测（v0.11.19 真机）**：「**没有转圈了，但是左右滑已经显示下一张图了，上一张图还是会闪现一下**」

---

## 0. 结论

| 项 | 结论 |
|---|---|
| 收到的根因假设 | **方向成立，但表述要修正**（§1.3）。不是"协程体不在 apply 阶段同步执行"这一个调度细节，而是 **effect 的 key 含 `page` ⇒ 位图的重置必然晚于 `page` 第一次可见；而 `Image` 的参数在组合期取值 ⇒ 必然有一帧"新页 + 旧图"**。即使协程体在同一帧同步执行，这一帧也不会消失 —— 所以修法不能是"把赋值提前"，只能是**绘制期校验归属** |
| 与 PV-2 的关系 | 同一处时序缺口的**另一个面**：PV-1/2 把"丢旧像素 + 上新像素"收敛进一段同步代码后，那一帧不再画 `null`（**转圈消失 ✓**），但画的是**旧值** ⇒ 变成旧图闪现。PV-2 的"原子替换"只保证不画 null，**不可能**保证不画旧值 |
| 修法 | 绘制期**归属校验**（纯函数 [`bitmapBelongsToPage`] / [`visibleBitmapFor`]，:343/:358）：停稳页**只画属于这一页的位图**；归属不符 ⇒ 画该页**自己的预载档**（主动预载 `page±2` 通常命中 ⇒ **那一帧直接是新图**），未命中 ⇒ 黑底（仍不画旧图）。`under` 层与淡入 alpha 共用同一判据（:927/:955） |
| 硬约束 | 手势 / 缩放 / 页边界裁剪**一行未改**（`viewerGesture` / `clampZoom` / `clampPan` / `pageScaleAt` / `pageShiftAt` / `clipToBounds` / `userScrollEnabled` 的代码行 diff 零命中）；转圈的修复未回退（PV-2 的 9 例全绿） |
| 测试 | 新建 `Pv3StaleFrameTest` **7 例**；定向 `--rerun-tasks` 跑 **6 类 / 74 例 / 0 失败 / 0 错误**（§3） |
| mutation | 3 组：2 组变红（**判据被钉死**），1 组**通过**（调用点被整体绕开时单测测不到 —— 如实标注，§4.1） |

---

## 1. 根因（机制级，证据 = Compose 1.11.4 的字节码，本地 aar 反编译）

> Compose 版本取自 `gradle/libs.versions.toml` 的 `composeBom = 2026.06.01` ⇒ BOM 里 `runtime/ui/foundation = 1.11.4`。

### 1.1 `settledPage` 在**滚动结束那一帧**才从旧页跳到新页 —— 与用户描述逐字吻合

```java
// androidx.compose.foundation.pager.PagerState（javap -c）
// settledPage 是 derivedStateOf 的 State：
private static final int settledPage_delegate$lambda$0(PagerState st) {
    return st.isScrollInProgress() ? st.getSettledPageState() : st.getCurrentPage();
}
// 而 settledPageState 只在**一次滚动开始时**写：
//   scroll$suspendImpl:  if (!isScrollInProgress()) setSettledPageState(currentPage)
```

⇒ 手指滑动期间 `page` **冻结在滑动开始时的页**；`isScrollInProgress` 变 false（scroll 协程结束 = 滑动/惯性结束）**那一帧**，`settledPage` 直接变成 `currentPage` = 新页。

用户说"**左右滑已经显示下一张图了**"= Pager 的位移早已把新页摆在视口正中（那与 `settledPage` 无关）；"**上一张图还是会闪现一下**"= `settledPage` 跳变那一帧 `p == page` 换到了新页的 Box 上，而共享的 `bitmap` 还是上一页的像素 ⇒ **画在视口正中的是上一张的图**。

### 1.2 `LaunchedEffect` 的重启无论如何都晚于"第一次读到新 `page`"

```java
// androidx.compose.runtime.EffectsKt.LaunchedEffect(key1, block)（javap -c）
Composer.changed(key1); rememberedValue(); new LaunchedEffectImpl(getApplyCoroutineContext(), block); updateRememberedValue(…)
// ⇒ key 变化 = 旧实例 onForgotten（cancel）+ 新实例 onRemembered

// LaunchedEffectImpl.onRemembered（javap -c）
… putfield job = BuildersKt.launch$default(scope, null, null, task, 3, null)
//                                                        ↑ start/context 走默认 = CoroutineStart.DEFAULT ⇒ **dispatch**
```

⇒ 协程体**不在 apply 阶段同步执行**（这条假设成立）：`onRemembered` 只是 `launch`，队列在 `AndroidUiDispatcher`：

```java
// AndroidUiDispatcher.dispatch（javap -c）
toRunTrampolined.addLast(block);            // 入队
if (!scheduledTrampolineDispatch) { … handler.post(dispatchCallback); … }   // 只负责"安排一次 drain"
// performTrampolineDispatch() = 循环取 nextTask() 直到队列空（drain）
```

**但这不是关键**（§1.3）：`performTrampolineDispatch` 是个 drain 循环，若重组发生在 drain 期间，协程体**可能**就在同一帧被跑掉 —— 然而**没有用**。

### 1.3 关键：`Image` 的参数在**组合期**取值 ⇒ 位图的新值必须等**下一次重组**

`page`（组合期读 `pagerState.settledPage`）与 `bitmap`（由 key 含 `page` 的 effect 写）是**两个独立来源**。Effect 的重启只有在**重组观察到新 key** 之后才可能发生。于是：

| 帧 | 发生什么 | 屏上 |
|---|---|---|
| N | `settledPage` 跳新页 ⇒ 重组：`p == page` 换到新页 Box；该 effect 重启（`launch` 入队） | `Image(bitmap = **旧页的像素**)` ⇒ **旧图闪现**（用户报的） |
| N 内 | 协程体跑（同步或下一帧都无所谓）⇒ `bitmap = previews.get(新页)` | 只改了 state，**没有新的组合** |
| N+1 | 重组（`bitmap` 新值）⇒ `Image` 才拿到新图 | 新图 |

**⇒ 无论 effect 写得多原子、无论协程在哪一帧跑，帧 N 画的一定是旧值。** 这正是 PV-2 修好转圈之后仍然保留的那一帧：
PV-2 把"清空 + 填充"收敛成一次赋值，消掉了 `bitmap == null` 的帧（转圈 ✓），却让那一帧从"空"变成"旧"。

**第二条独立路径**：`under` 是**整页共享**的 state，清它的是同一个 effect（异步）⇒ 帧 N 时它可能还是上一页的，而绘制点原来只判 `if (p == page)` ⇒ 上一张的像素会被当"下层"画到新页上。alpha 同理（`pageAlphaAt(p == page, under != null, …)` 在帧 N 可能取到上一页垂死动画留下的中间值 ⇒ 新图半透明压在黑底上 = 变暗一闪）。

---

## 2. 修法（绘制期归属校验）

```kotlin
// PhotoViewScreen.kt:343 / :358 —— 唯二判据，纯函数（JVM 单测钉死）
internal fun bitmapBelongsToPage(isSettledPage: Boolean, pageUri: String?, shownUri: String?): Boolean =
    isSettledPage && pageUri != null && pageUri == shownUri

internal fun <B> visibleBitmapFor(
    isSettledPage: Boolean, pageUri: String?, shownUri: String?, bitmap: B?, cached: B?,
): B? = if (bitmapBelongsToPage(isSettledPage, pageUri, shownUri)) bitmap else cached
```

```kotlin
// :913-916 绘制点
val pageUri = uris.getOrNull(p)
val cached = pageUri?.let { rememberNeighborPreview(previews, it, previewsVersion) }  // 停稳页也读缓存
val owns = bitmapBelongsToPage(p == page, pageUri, shownUri)
val b = visibleBitmapFor(p == page, pageUri, shownUri, bitmap, cached)
…
if (owns) under?.let { u -> … }                                    // :927 原为 if (p == page)
Image(bitmap = b.asImageBitmap(), …,
    modifier = Modifier.viewerLayer(p == page, zoom, pan) {        // :953-955 几何判据未改
        pageAlphaAt(owns, under != null, fade.value)               //        alpha 闸门收紧成 owns
    })
```

- **`shownUri` 是位图的归属**（"此刻这张位图是哪张照片的像素"），它与 `bitmap` 在解码 effect 的换页分支里**成对赋值**（`frameAfterPageChange`）⇒ 校验不会错杀。
- 归属不符 ⇒ `cached` = **新页自己的预载档**（主动预载 `page±2` 通常命中）⇒ 帧 N 直接是新图；下一帧 effect 把 `bitmap` 换成**同一个对象**（`previews.get(u)`）⇒ 前后两帧逐像素相同，用户看到"新图直接接上新图"。
- 未命中（快速连滑超出 ±2）⇒ `null` ⇒ 黑底（配合既有的 `SPINNER_DELAY_MS` 连转圈都不画）—— **仍不画旧图**。
- 停稳页也要读缓存（原代码对停稳页跳过）：职责上"预载负责生产、这里负责显示"，这里**始终是零解码零 IO 的纯读取**（`:1083` 的 KDoc 已更新）。
- **几何判据（`pageScaleAt` / `pageShiftAt` 的入参 `p == page`）逐位未改** —— 归属判据只用于"画什么"，不用于"怎么变换"（硬约束 1）。
- 停稳页归属相符时仍画 `bitmap` 本身（不许一律降级成预载档，否则 E2-22 的两段式与放大升档全废）。

---

## 3. 测试 / mutation

### 3.1 定向跑（只跑相关类，未跑全量）

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests "com.filmkit.app.ui.Pv3StaleFrameTest" --tests "com.filmkit.app.ui.Pv2FlickerTest" \
  --tests "com.filmkit.app.ui.PhotoViewPreviewTest" --tests "com.filmkit.app.ui.PhotoViewZoomTest" \
  --tests "com.filmkit.app.ui.PhotoViewerModelTest" --tests "com.filmkit.app.ui.PhotoViewPresetTest"
```

`BUILD SUCCESSFUL`，**6 类 / 74 例 / failures=0 / errors=0 / skipped=0**：

| 类 | 用例 |
|---|---|
| `Pv3StaleFrameTest`（新增） | 7 |
| `Pv2FlickerTest`（PV-2，**未回退**） | 9 |
| `PhotoViewZoomTest`（手势/缩放边界） | 20 |
| `PhotoViewerModelTest` | 26 |
| `PhotoViewPreviewTest` | 5 |
| `PhotoViewPresetTest` | 7 |

新增 7 例钉住的判据：归属不符 ⇒ **绝不返回那个位图**（`assertSame` 缓存档 + `!==` 旧图）／未命中预载也**不许退回旧图**（null）／归属相符 ⇒ 仍画 `bitmap` 本身（防过度修正）／邻页恒走缓存档／归属未知（`shownUri == null`）不画／`under` 层同判据／归属不符时 alpha 恒 1。

### 3.2 mutation 自检（3 组，跑完即恢复）

| # | mutation（改回旧口径） | 结果 |
|---|---|---|
| **M1** | `visibleBitmapFor` 判据回退成 PV-2 口径 `if (isSettledPage) bitmap else cached` | **3 例红** |
| **M2** | `bitmapBelongsToPage` 去掉 uri 归属（只判 `isSettledPage && pageUri != null`） | **5 例红** |
| **M3** | 调用点整体回退成 `val b = if (p == page) bitmap else cached`（**判据函数没被调用**） | **未变红**（覆盖缺口，见 §4.1） |

```
════ M1 去掉归属校验（停稳页直接画共享位图）════
Pv3StaleFrameTest > settledPageNeverDrawsABitmapOwnedByAnotherPage FAILED   (Pv3StaleFrameTest.kt:47)
Pv3StaleFrameTest > aBitmapWithoutAnOwnerIsNeverDrawn FAILED               (Pv3StaleFrameTest.kt:95)
Pv3StaleFrameTest > aStaleBitmapIsNeverDrawnEvenWhenTheCacheMisses FAILED   (Pv3StaleFrameTest.kt:55)
7 tests completed, 3 failed          BUILD FAILED in 11s

════ M2 归属判据只剩"是不是停稳页"（丢了 uri 归属）════
上述 3 例 + underLayerFollowsTheSameOwnershipGate (:113) + staleSettledPageIsFullyOpaque (:127)
7 tests completed, 5 failed          BUILD FAILED in 12s

════ M3 调用点整体绕开判据函数 ════
BUILD SUCCESSFUL in 10s              ← 单测测不到（§4.1）
```

**恢复已核实**：`grep -c MUTATION PhotoViewScreen.kt` = **0**，且与备份 `diff` = 0，恢复后再跑一次定向测试 `BUILD SUCCESSFUL`。

---

## 4. 未验证项（逐条，不许当成已验证）

### 4.1 ⚠ **"调用点真的用了这道闸"单测钉不住**（M3 的实测结论）

M3 把绘制点的 `visibleBitmapFor(…)` 换回 `if (p == page) bitmap else cached` —— **7 例全绿**。单测钉的是**判据本身**（纯函数），钉不住"组合产物里传的是哪一个值"：本项目只有 junit，没有 Robolectric / compose-ui-test，跑不了真实的组合与帧调度。
⇒ 需要**代码审查 + 真机判据**（§5）。这也是 PV-2 报告 §6 已承认的同一类边界。

### 4.2 其他

| 项 | 为什么测不到 | 需要什么 |
|---|---|---|
| 闪现到底持续 1 帧还是 2 帧 | 需要真实 `Recomposer` + Choreographer | 真机 120/240fps 慢动作录屏（§5 判据 A） |
| "新页预载档那一帧"是否真的命中缓存 | 需要真机 IO/解码耗时 | 真机打点：切页帧 `previews.get(newUri) == null` 的次数 |
| 未命中时的 1 帧黑底是否可见 | 绘制层观感 | 真机目视（黑底与页面背景同色，理论上看不出） |
| `error` / `meta` 文本仍是"整页共享"（切页那一帧可能显示上一页的错误文案） | 同一类时序缺口，**本卡未处理**（不在"图闪现"范围内，且没有像 `shownUri` 那样干净的归属判据） | 后续卡；真机观察是否可见 |
| `rememberNeighborPreview` 现在被停稳页也调用（多一个 `previewsVersion` 依赖 ⇒ 每次预载多一次重组） | 需要真机 Profiler | 真机 GPU/Compose 重组计数（预期：每次预载 1 次，不是每帧） |

---

## 5. 真机验收判据（怎么判断"不闪了"）

1. **判据 A（主判据 —— 旧图闪现）**：相册 → 点开一张 → **左右各连续滑 ≥20 张**（含 12MP 横图 + 竖图）。
   - ✅ 通过 = **任何一次都看不到"上一张的图出现在新页位置上"**；切页处只可能是"新图（或它的预览档）直接接上新图"。
   - ❌ 不通过 = 仍能指认出旧图 ⇒ 说明帧 N 画了不属于本页的位图（查调用点是否真的走了 `visibleBitmapFor`）。
2. **判据 B（转圈不许回来）**：同一轮滑动里**一次转圈都不该出现**（判据 A 通过但出现转圈 ⇒ 预载没赶上，与 PV-2 同一条）。
3. **判据 C（无变暗）**：滑动全程图片区**不出现整体发暗/发灰**（alpha 闸门若漏 ⇒ 变暗）。
4. **判据 D（E2-20 / E2-23 回归，一条都不许退化）**：Fit 态单指横滑**仍能切图**；双指捏合到 5×、下限停在 Fit；放大后单指平移不越界；放大后左右滑**不切图**；放大态**看不到隔壁照片的像素**；放大到 3–5× 时切页/升档**无重影**。
5. **判据 E（量化定位）**：240fps 慢动作录"滑动 → 停下"1 秒，逐帧看换页处。
   - 旧版：能看到 1–2 帧**上一张的图**充满图片区。
   - 本版：应是"新图（预览档，略糊）→ 新图（清晰）"的直接切换，或最多一帧纯黑。
6. **判据 F（代价）**：首次从相册点开一张的等待**不应比 v0.11.19 变长**（本卡没有新增解码，只是在切页帧多读一次缓存）。

---

## 6. 改动清单

| 文件 | 改动 |
|---|---|
| `PhotoViewScreen.kt` | 新增纯函数 `bitmapBelongsToPage`（:343）/ `visibleBitmapFor`（:358）；绘制点改为"先取本页 uri 与缓存档 ⇒ 归属校验 ⇒ 画归属相符的位图，否则画本页预载档"（:913-916）；`under` 层判据 `p == page` → `owns`（:927）；alpha 闸门参数 `p == page` → `owns`（:955）；类级 KDoc 加 ★ PV-3 段；`rememberNeighborPreview` 的 KDoc 更新（停稳页也会调用它）。**几何/手势/裁剪一行未改**；未新增 state、未新增 effect、未改缓存口径 |
| `Pv3StaleFrameTest.kt` | **新增**，7 例 |

**未 commit**（按要求留工作区）。**未触碰** `CameraScreen.kt` / `CameraPresetPanel.kt` 等并行卡的文件。
