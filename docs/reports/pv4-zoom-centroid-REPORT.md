# PV-4 报告：查看页缩放改为**以两指中心为锚点**

用户（2026-10-12 真机）："相册缩放要从两指中心缩，现在都是从画面中心缩放，很奇怪。"

## §1 根因

绘制层 `viewerLayer`（`:1182`）的 `graphicsLayer` 用默认 pivot（`TransformOrigin.Center`）；手势层 `Zoom` 分支
此前只做 `zoom = clampZoom(zoom, factor)` + `pan += calculatePan()` —— 只改倍率、不补偿平移 ⇒ 缩放必然绕**画面中心**。

## §2 公式与推导

居中坐标系下内容点 `x` 的屏幕位置（pivot = 容器中心 `ctr`）为 `screen(x) = ctr + z·(x − ctr) + pan`。
要求两指中心 `c` 处的内容点 `x_c` 在缩放前后**都落在 `c`**：

```
c = ctr + z₁·(x_c − ctr) + pan₁  ⇒  x_c − ctr = (c − ctr − pan₁)/z₁
c = ctr + z₂·(x_c − ctr) + pan₂  ⇒  pan₂ = pan₁ + (c − ctr − pan₁)·(1 − z₂/z₁)
```

落地为纯函数 `zoomAboutCentroid(pan, centroid, center, z1, z2)`（`:283`）。

**为什么 `useCurrent = true`**：`calculateZoom()` / `calculatePan()` 都是"current 相对 previous"的增量，
本帧几何状态就是 current ⇒ 锚点必须取 current 帧质心（用 previous 会让缩放中心慢一帧）。"先叠加
`calculatePan()` 再以 current 质心补偿"**恰好等价于**"previous 质心处的点跟随到 current 质心"：两者解出的
`pan₂` 逐位相同（`pan₂ = c − ctr − r·(c − Δ − ctr − pan₁)`，`r = z₂/z₁`、`Δ = calculatePan()`）⇒ 非近似。

## §3 改动（`PhotoViewScreen.kt` 一处 + 一个新测试文件；未 commit）

- `:283` 新增 `zoomAboutCentroid` + 私有 `Offset.finite()`（非有限输入守卫：`calculateCentroid()` 在指针被
  消费 / 无按下指针时返回 `Offset.Unspecified`=NaN，NaN 进 `graphicsLayer` 会让整张图消失）。
- `:907` `Zoom` 分支：`z2 = clampZoom(z1, …)`（**先夹紧再补偿** —— 顶到 `MAX_ZOOM` 后 `z₁ == z₂` ⇒ 补偿为 0，
  不漂移）→ `moved = pan + calculatePan()` → `clampPan(zoomAboutCentroid(moved, calculateCentroid(
  useCurrent = true), Offset(size/2), z1, z2), z2, viewSize, bitmap)`；结果**必经 `clampPan` 夹紧**（`:922`）。
- `:939` `Pan`、`:951` `Swipe` 分支**一位未改**（Fit 单指仍不消费 ⇒ Pager 切图；放大态单指仍消费，消费逻辑未改）。
- 语义未改：`clampZoom` / `clampPan` / `clampAxis` / `pageScaleAt` / `pageShiftAt` / `viewerGesture` 全部原样；
  唯一调用点调整是 `clampPan` 的 `scale` 实参由 `zoom.floatValue`（刚写入的新值）换成局部 `z2`（同一个数）。
  pivot 中心取 `AwaitPointerEventScope.size/2`（手势节点 = 页 Box = Image，均 `fillMaxSize`，与 pivot 同源）。

## §4 证据（`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest --rerun-tasks`）

新增 `Pv4ZoomCentroidTest.kt`（8 例，容差 1e-3）：不动点性质（7 组参数：Fit 起步 / 放大态 / 捏回 / 顶到 5× /
质心在中心 / pan 已到边界）、反向守卫（旧行为必然漂移）、方向、退化（`z₁ == z₂` 恒等）、NaN 守卫。

```
恢复态：BUILD SUCCESSFUL
  PhotoViewZoomTest   tests=20 failures=0 errors=0   ← 硬约束 ③
  Pv2FlickerTest      tests=9  failures=0 errors=0
  Pv3StaleFrameTest   tests=8  failures=0 errors=0
  Pv4ZoomCentroidTest tests=8  failures=0 errors=0   ← 本卡新增
```

**mutation**：把 `:292` 改成 `return pan // MUTATION`（去掉锚点补偿 = 回到绕画面中心缩放），重跑：

```
> Task :app:testDebugUnitTest FAILED
Pv4ZoomCentroidTest > pinchAnchorsTheContentPointUnderTheFingers FAILED        ← 不动点断言（主）
Pv4ZoomCentroidTest > withoutCompensationTheAnchorPointDrifts FAILED
Pv4ZoomCentroidTest > existingPanIsRescaledEvenWhenTheCentroidIsTheViewportCenter FAILED
Pv4ZoomCentroidTest > zoomingInAroundTheRightHalfPushesContentLeft FAILED
Pv4ZoomCentroidTest > zoomingOutAroundTheLeftHalfPullsContentLeft FAILED
28 tests completed, 5 failed / BUILD FAILED in 21s
```

`PhotoViewZoomTest` 20 例在 mutation 下**全绿**（既有护栏盖不到这条性质）；已恢复代码并重跑全绿，
`grep -rn MUTATION app/src/ | wc -l` = **0**。

## §5 未验证项（如实标注）

1. **真机手感**：无 Robolectric / compose-ui-test —— `calculateCentroid` / `calculateZoom` 的真机取值、每帧
   state 写入的重绘节奏、锚点是否"跟手"（尤其两指异步落下/抬起那一帧的质心跳变）**一律没测**。
2. **边界处观感**：补偿后再过 `clampPan`，图已到边时不动点会被夹紧**打破**（想跟手也移不动）—— 既有约束
   的必然结果；`size` 与 `viewSize` 同尺寸的前提（当前 `fillMaxSize` 保证）若被破坏，锚点中心会偏。

## §6 验收步骤（真机）

1. 两指落在**右下角**捏开放大：被夹住的内容停在指间，画面不再"从正中撑开"；左上角、捏回同理。
2. 捏到 1× 后横滑仍能切图；放大后单指拖动仍平移且不被切走；顶到 5× 继续捏**不漂移**，拖到边界不露黑边。
3. 预设面板打开时捏合：图片区仍完全无响应（P-3 行为未受影响）。
