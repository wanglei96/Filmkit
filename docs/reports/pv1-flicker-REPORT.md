# 修复报告：相册滑动图片"闪一下"（PV-1）

**日期**：2026-10-11 ｜ **范围**：`PhotoViewScreen.kt`（实现）+ `PhotoViewPreviewTest.kt`（新增回归）
**用户上报**：「**还有相册滑动图片还是会闪一下**」（"还是"= E2-22 之后仍有）

> ⚠️ **绘制层未真机验证**（本卡运行在无 `adb` 设备的会话里）—— 淡入观感、真机解码耗时、内存峰值
> 属于 Compose 绘制层与设备行为，JVM 单测**测不到**，逐条标注见 §5，真机验收步骤见 §6。
> JVM 侧证据（构建 + 469 用例 + mutation 自检）见 §4。

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 根因 | **预览档被放大 2.12–3.29× 铺满显示区，与目标档之间有一次"清晰度阶跃"** —— E2-22 的预览档是固定 360px，`decodeFit` 把它落到 `inSampleSize = 8`（12MP 上实际只解 500×375），随后 Fit 档（2000×1500）替换上去 ⇒ 肉眼可见的糊→清晰突变 |
| **不是**什么 | 不是空白帧、不是位置/缩放跳变、不是颜色差异、不是上一张残留（四条排除依据见 §1.3） |
| 修法 | ① 预览请求从固定 360px 改为**容器的一半**（[previewRequestFor]，采样台阶必然比目标档低一档）⇒ 放大倍数降到 **1.08–1.92×**；② 换档时**交叉淡入 180ms**（下层预览不动，上层目标档 alpha 0→1） |
| 配套 | `PREVIEW_CACHE_BYTES` 4MB → **12MB**（不提会让相邻页预载静默塌成 1 张，比修之前更糟） |
| 改动量 | `PhotoViewScreen.kt` 净增 ~120 行（含 KDoc）；手势仲裁 / `clampZoom` / `clampPan` / `clipToBounds` / `userScrollEnabled` / `pageScaleAt` / `pageShiftAt` **一行未改**（§3 有 diff 证据） |
| 测试 | 新增 5 例（455 → **460**），`--rerun-tasks` 真跑；mutation 自检 3 条红（§4） |
| 未覆盖 | 淡入观感 / 真机解码耗时 / 内存峰值 / 双层绘制的 GPU 成本 —— 全部标注在 §5 |

---

## 1. 根因

### 1.1 机制：清晰度阶跃，不是"闪一下"的别的什么东西

链路上有四个环节，缺一不可：

| # | 位置 | 事实 |
|---|---|---|
| ① | `PhotoViewScreen.kt:114`（修复前） | `private const val PREVIEW_SIDE = 360` —— 预览档的**请求**尺寸写死 360px |
| ② | `ImageDecode.kt:91-96` | `decodeFit` = 先把请求尺寸按原图比例 Fit（`fitSizeFor`），再交给 `sampleSizeFor` 算 `inSampleSize` |
| ③ | `ImageDecode.kt:136-141` | `sampleSizeFor` **只能返回 2 的幂** ⇒ 真正决定清晰度的是请求尺寸落在哪个"采样台阶"上，**不是请求了多少像素** |
| ④ | `PhotoViewScreen.kt:569-586`（修复前） | 上屏用 `ContentScale.Fit` ⇒ 位图被**放大**到显示区大小，放大倍数直接等于肉眼可见的糊度 |

把 12MP 横图（4000×3000）代进 1080×1700 的图片区（真机口径）：

| 档位 | 请求 | Fit 后 | `sampleSizeFor` | **实际解码** | 显示区 | **放大倍数** | 内存 |
|---|---|---|---|---|---|---|---|
| 预览档（旧） | 360×360 | 360×270 | **8** | **500×375** | 1080×810 | **2.16×** | 0.75MB |
| 目标档 | 1080×1700 | 1080×810 | 2 | 2000×1500 | 1080×810 | 0.54×（缩小） | 12MB |

于是滑动切页时用户看到的序列是：

```
上一张 Fit 档（锐利）
  → 切页 → 新页预览档 500×375 被拉伸 2.16× 铺满（明显糊）
  → 约 60–150ms 后 Fit 档 2000×1500 替换上来（锐利）
```

**中间那段"糊"就是用户说的"闪一下"** —— 它不是一帧的闪烁，是一次**两段式清晰度跳变**（糊 → 清晰）。
竖图更糟：3000×4000 在 1080×1700 上预览档落在 sample=8 ⇒ 375×500 放大 **2.88×**。
三种屏宽 × 四种画幅的完整台账（跑的是真实的 `fitSizeFor` / `sampleSizeFor`）见 §4.3。

### 1.2 为什么"预览档"这条设计本身没错

E2-22 的两段式（小图先上屏 → 大图后到）是对的：用户 2026-10-10 抱怨过"切图慢"，直接等 Fit 档
会有 60–150ms 的空屏。**错的只是预览档的尺寸口径**：它照抄了 PhotonCamera 的 `PREVIEW_SIDE = 360`，
而 360 在 12MP 原图上落到的是 sample=8 —— 那是"网格缩略图"的量级，不是"全屏占位"的量级。

### 1.3 排除的可能性（逐条给依据）

| 假设 | 结论 | 依据 |
|---|---|---|
| **位置 / 缩放跳变**（替换时图片挪位或改变大小） | **排除** | 两档是**同一张图、同一次 `decodeFit` 调用、同一个 EXIF 变换**（`PhotoViewScreen.kt:448` vs `:458`）。`ContentScale.Fit` 的显示框**只由位图宽高比决定**，与像素尺寸无关：500×375 与 2000×1500 的宽高比都是 4000:3000（`src/sample` 的整数除保持比例）⇒ 显示框逐像素相同。`Modifier.fillMaxSize()` + `graphicsLayer` 的 scale/pan 在两次替换之间也不变（`zoom`/`pan` 由切页 effect 复位一次（`:406-407`），不随 `bitmap` 变化）。**唯一变量是像素密度** |
| **颜色 / dither 差异**（两档解码参数不同） | **排除** | 两档都走同一个 `decodeOriented` → `BitmapFactory.decodeStream`，`ImageDecode.kt:50` 的 `Options` **只设了 `inSampleSize`** —— 没有 `inPreferredConfig` / `inDither` / 色彩空间分支 ⇒ 两档同为默认 ARGB_8888，同一套 EXIF 转正矩阵。不存在"一档 565 一档 8888"的可能 |
| **上一张残留 / 错图** | **排除** | `PhotoViewScreen.kt:408`（切页 effect）的 `bitmap = null` 保证换页时先清空；`shownUri` 跟着更新，不会出现"A 的像素画在 B 页上" |
| **空白 / 转圈帧** | **不是滑动主路径**（但确实存在，见下） | 滑动到下一张时 `previews.get(u)` **命中相邻页预载**（`:442`），`bitmap` 从 null 直接变预览档 —— 两个 effect 在同一帧内同步执行到各自的第一个挂起点，中间**没有让出主线程**，不会渲染出空帧。未命中时（首次从相册点开当前页、快速连滑、缓存被挤出）才会走 `:447-455` 的现解路径，此时 `bitmap == null` ⇒ `:684` 上屏 `CircularProgressIndicator`。**这是"点开时"的闪，不是"滑动"时的闪** —— 与用户描述的场景不同，故不作为本卡根因，也未改 |
| **手势层 / 绘制层干扰** | **排除** | `graphicsLayer` 的 zoom/pan 在切页时被复位成 1×/0（`:406-407`），与位图替换同一帧 ⇒ 不会出现"新图带着旧图的缩放" |

---

## 2. 修法

### 2.1 主修：预览请求 = 容器的一半（消除阶跃的来源）

```kotlin
// PhotoViewScreen.kt:141
internal fun previewRequestFor(viewW: Int, viewH: Int): Pair<Int, Int> =
    (viewW / 2).coerceAtLeast(1) to (viewH / 2).coerceAtLeast(1)
```

**为什么"一半"是结构性正确的**：`fitSizeFor` 对容器尺寸是**一次齐次**的（Fit 就是线性缩放），
所以"请求容器的一半" ≡ "请求目标档 Fit 尺寸的一半" ⇒ 采样台阶**必然比目标档低一档**（`sampleSizeFor`
的判据是"源/2 ≥ 目标"，目标减半恰好让采样率翻倍）⇒ 解码像素量恰好是目标档的 **1/4**。

结果（1080p 容器，12MP 横图）：预览档 sample 8 → **4**，实际解码 500×375 → **1000×750**，
放大倍数 **2.16× → 1.08×** —— 与目标档肉眼无差。

这不是"把 360 调大一点"的拍脑袋调参：换一台屏（1440p / 1644p）或换一种画幅（横 / 竖 / 全景），
"低一档"这个关系**自动成立**（§4.3 的台账逐格验证）。写死任何一个数字都做不到这一点。

### 2.2 辅修：换档时交叉淡入 180ms（抹平残余差）

1080p 上预览档与目标档已经肉眼无差，但**1440p 竖图仍有 1.92×** 的残余放大（§4.3 最差一格）。
所以加第二道：

```kotlin
// PhotoViewScreen.kt:468-471 —— 三步顺序不能换
val prev = bitmap
under = prev                        // ① 先摆下层（prev 就是此刻屏上那张，立刻渲染也看不出变化）
if (prev != null) fade.snapTo(0f)   // ② 再归零上层（屏上剩下的是 prev，与①之前逐像素相同）
bitmap = b                          // ③ 最后换上目标档（从 alpha 0 起）
```

```kotlin
// PhotoViewScreen.kt:489-492
LaunchedEffect(bitmap) {
    if (under != null) fade.animateTo(1f, tween(PREVIEW_FADE_MS)) else fade.snapTo(1f)
    under = null
}
```

`fade.value` 只在 `graphicsLayer` 的**绘制 block** 里读（`:834-846` 的 `viewerLayer`）⇒ 动画期间
**零重组**，与 `zoom`/`pan` 同一套做法。

**为什么顺序是 ①②③ 而不是"先归零再摆下层"**：`snapTo` 是挂起函数，它让不让出主线程不由这里
决定。若先归零，中间任何一次让出都会渲染出"上层 alpha=0 且下层还是 null" ⇒ **一帧纯黑**，比原来
的阶跃更刺眼。①②③ 的顺序保证**在任意中断点都自洽**（每个中间态的屏上内容都等于 `prev`）。

### 2.3 配套：预载缓存上限 4MB → 12MB

预览档从 0.75MB/张 提到 3MB/张后，原来的 4MB 上限**只装得下 1 张** ⇒ 相邻页预载静默失效
（滑动时邻页没有现成预览 ⇒ 又要现解 ⇒ 空白帧，比修之前更糟）。12MB 刚好容下 3 张（9MB）。
这条不是可选项，是主修的**必要条件**（`PhotoViewPreviewTest.previewCacheHoldsThreePreviewsOfTheNewTier` 钉死）。

### 2.4 为什么没选另外三个候选

| 候选 | 判断 |
|---|---|
| **提高预览边长到屏幕尺寸** | ❌ 请求 = 容器尺寸时预览档**就是**目标档（`decodeFit` 两次调用参数完全相同）⇒ 阶跃确实归零，但第一段就要付完整解码时间（60–150ms）⇒ **退回 E2-22 之前的长等待**，正是用户 2026-10-10 抱怨过的"切图慢"。且该档 12MB/张，3 张预载 36MB |
| **缓存命中时跳过预览** | ❌ 当前**没有 Fit 档缓存**（`previews` 只存预览档）。要让"命中"有意义就得新增一份 Fit 档缓存：12MB/张 × 3 = 36MB，且切页后要预载两个 12MB 解码（120–300ms CPU/IO）—— 会与当前页解码**争抢**，反而更容易卡。收益（省一次 15–30ms 的预览解码）远小于代价 |
| **避免空白帧** | ⚠️ 切页时 `bitmap = null`（`:408`）是**必须**的（不能把上一张的像素留在新页）。空白帧只在预载未命中时出现，**不是滑动主路径**（§1.3）⇒ 不是本卡的根因。已单独记录为后续项（§7） |
| **"等高清再显示"** | 🚫 任务明确禁止，且与用户"切图慢"的诉求直接冲突 |

---

## 3. 手势（E2-20）与缩放边界（E2-23）未被破坏的证据

### 3.1 diff 级：这些代码一行未动

```
$ git diff PhotoViewScreen.kt | grep -E "^[+-]" | grep -E "viewerGesture|clampZoom|clampPan|awaitEachGesture|
    awaitFirstDown|awaitPointerEvent|calculateZoom|calculatePan|clipToBounds|userScrollEnabled|
    MIN_ZOOM|MAX_ZOOM|ZOOM_HIRES|detectTapGestures|consume\(\)"
（无匹配 —— 手势仲裁 / 缩放边界 / 页裁剪 / Pager 开关全部零改动）
```

唯一被 diff 命中的 `pageScaleAt` / `pageShiftAt` 出现在**同一段 `graphicsLayer` 代码被原样搬进
`viewerLayer()` 函数**（`p == page` → 形参 `isSettledPage`，`:834-846`），表达式逐位相同：

| 修复前（内联在 `Image` 的 modifier 里） | 修复后（`viewerLayer` 内） |
|---|---|
| `val z = pageScaleAt(p == page, zoom.floatValue)` | `val z = pageScaleAt(isSettledPage, zoom.floatValue)` |
| `scaleX = z; scaleY = z` | 同 |
| `translationX = pageShiftAt(p == page, pan.value.x)` | `translationX = pageShiftAt(isSettledPage, pan.value.x)` |
| `translationY = pageShiftAt(p == page, pan.value.y)` | 同 |
| —（无） | `this.alpha = alpha()` ← **新增**，默认 `{ 1f }` |

抽函数是**必要的**：交叉淡入要同时画两层（下层 `under` + 上层 `bitmap`），两层各抄一遍变换代码
迟早会抄歪 —— 缩放态下两层错位就是重影，比它要修的"闪"更难查。两处调用（`:668` / `:681`）都用
同一个 `viewerLayer(p == page, zoom, pan)`。

### 3.2 测试级：E2-20 / E2-23 的 20 个用例全绿

`PhotoViewZoomTest`（20 例：缩放上下限、乘性夹紧、NaN/0/负 兜底、`ZOOM_HIRES` 可达性、
`viewerGesture` 仲裁表、`fitDisplaySize`、`clampAxis`、`pageScaleAt` / `pageShiftAt`）在本次
全量跑中 **0 失败**（§4.2）。

### 3.3 新增的两层绘制没有绕过边界

- 下层 `under` 只在 `p == page` 时绘制（`:663`）⇒ 相邻页不会被多画一层；
- 两层的 `graphicsLayer` 都用 `pageScaleAt(p == page, …)` ⇒ 放大态下相邻页仍恒等变换（E2-23 根因修复未被削弱）；
- 每页根容器的 `clipToBounds()`（`:594`）**未被触碰** ⇒ 页边界裁剪兜底仍在；
- 下层的 `contentDescription = null`（`:665`）—— 它是同一张图的低清版本，是装饰层，读屏不应重复播报。

---

## 4. 构建 / 单测 / mutation

### 4.1 命令与结果

```bash
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

| 跑法 | 结果 |
|---|---|
| **任务要求的原始命令**（无任何排除） | `46 actionable tasks: 46 executed` ｜ **`476 tests completed, 2 failed`** ｜ 退出码 1 |
| 同上 + 执行期排除他卡的 2 个失败类（`-I` init script，**不改仓库任何文件**） | `46 actionable tasks: 46 executed` ｜ **`469 tests completed, 0 failed`** ｜ **退出码 0** |

**那 2 个失败与 PV-1 无关**，属于另一张卡（E4-5 halation，正在改 `FrameRenderer.cpp`）：

| 失败用例 | 断言消息（节选） | 归属 |
|---|---|---|
| `CameraStyleWhitelistTest.engineCameraPathMatchesWhitelist` | "拍照链路实际读到的**预设槽位**必须逐槽等于 `cameraPathSlots`…expected `[0,1,2,…,94]` but was `[1,2,3,88,89,0,4,…]`" | 引擎槽位白名单（光晕卡给 shader 加了新槽位读取） |
| `EditorStyleWhitelistTest.scriptWhitelistMatchesEngineReads` | "脚本 want 必须 == passStyle 实际读的 `p.*` 字段…`halation_strength, grain_amount, …`" | 同上，断言消息里直接点名 `halation_strength` |

> 复跑一次原始命令，这 2 条仍然红，且其中一条的**方法名在两次跑之间就变了**
> （`scriptWhitelistMatchesEngineReads` → `kdocListMatchesScriptWhitelist`）—— 进一步证明那是
> 另一张卡**正在进行中**的工作，不是 PV-1 引入的回归。

两者都只校验**引擎/shader 的参数槽位白名单**，与 `PhotoViewScreen.kt`（照片查看页的 Compose UI）
没有任何调用关系。基线 455 + PV-1 新增 5 = 460，另 16 例来自他卡新增的 `PresetPanelUiTest`（476 = 460 + 16 ✓）。

> 过程中还遇到过两次**他卡编辑中的瞬时状态**（`FrameRenderer.cpp` 缺 `releaseHaloTargets()` 定义的
> 链接错误、`PresetPanelUiTest.kt` 的 `PresetAction` 重构半途），都在几分钟内被对方自行修好，
> 本卡未触碰那两个文件（`git status` 可证）。

### 4.2 用例数

```
测试类 43 个，用例 469 个，failures=0 errors=0
```

其中 PV-1 新增 **5** 例（`PhotoViewPreviewTest`，`tests="5" failures="0" errors="0"`）：

```
ok previewRequestIsHalfTheViewport
ok degenerateViewportNeverYieldsZeroRequest
ok previewTierIsNeverUpscaledBeyondTwoTimes
ok previewTierSitsExactlyOneSamplingStepBelowTheTarget
ok previewCacheHoldsThreePreviewsOfTheNewTier
```

### 4.3 量化台账（测试 stdout，跑的是真实的 `fitSizeFor` / `sampleSizeFor`）

```
1080p / 横 12MP : 显示宽 1080 | 预览 1000×750(sample=4) 放大 1.08×
1080p / 竖 12MP : 显示宽 1080 | 预览  750×1000(sample=4) 放大 1.44×
1080p / 全景    : 显示宽 1080 | 预览 1000×125 (sample=8) 放大 1.08×
1080p / 小图    : 显示宽 1080 | 预览 = 目标档 800×600(sample=1) 跳过
1440p / 横 12MP : 显示宽 1440 | 预览 1000×750(sample=4) 放大 1.44×
1440p / 竖 12MP : 显示宽 1440 | 预览  750×1000(sample=4) 放大 1.92×   ← 最差一格
1440p / 全景    : 显示宽 1440 | 预览 1000×125 (sample=8) 放大 1.44×
1440p / 小图    : 显示宽 1440 | 预览 = 目标档 800×600(sample=1) 跳过
1644p / 横 12MP : 显示宽 1644 | 预览 1000×750(sample=4) 放大 1.64×
1644p / 竖 12MP : 显示宽 1644 | 预览 1500×2000(sample=2) 放大 1.10×
1644p / 全景    : 显示宽 1644 | 预览 1000×125 (sample=8) 放大 1.64×
1644p / 小图    : 显示宽 1644 | 预览 = 目标档 800×600(sample=1) 跳过
```

对照（修复前，同一批条件）：**2.12 – 3.29×**。修复后上限 **1.92×**（1440p 竖图那一格 —— 这正是
§2.2 交叉淡入存在的理由）。

"小图"一行是退化档：800×600 的原图在两档都落到 sample=1 ⇒ 预览**就是**目标档 ⇒ 零阶跃（那种糊是
原图本身的糊，不是闪）。

### 4.4 mutation 自检

把主修一行改回旧口径：

```kotlin
internal fun previewRequestFor(viewW: Int, viewH: Int): Pair<Int, Int> =
    360 to 360   // MUTATION-PV-1
```

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks --tests "com.filmkit.app.ui.PhotoViewPreviewTest"
```

```
PhotoViewPreviewTest > previewRequestIsHalfTheViewport FAILED
PhotoViewPreviewTest > previewTierIsNeverUpscaledBeyondTwoTimes FAILED
PhotoViewPreviewTest > previewTierSitsExactlyOneSamplingStepBelowTheTarget FAILED
5 tests completed, 3 failed          ← 断言有牙齿
```

（另 2 例按设计不受这条 mutation 影响：`degenerateViewportNeverYieldsZeroRequest` 只要求请求 ≥ 1；
`previewCacheHoldsThreePreviewsOfTheNewTier` 的牙齿在 `PREVIEW_CACHE_BYTES` 上 —— 把它改回 4MB 就会红。）

**已恢复**：`grep -n "MUTATION" PhotoViewScreen.kt` → 无残留；恢复后 §4.1 的全量跑再次全绿。

> 顺带一条：本次 mutation 之前，`previewTierSitsExactlyOneSamplingStepBelowTheTarget` 曾因**断言
> 方向写反**（"低一档"= 采样率 ×2，我写成了目标档 ×2）红过一次，台账数据显示实际是
> `expected:<2> but was:<8>`。这反过来证明这条断言不是恒真断言 —— 它抓得住实现，也抓得住我自己写错。

---

## 5. ⚠️ 未验证部分（逐条标注）

| 项 | 为什么 JVM 测不到 | 需要什么 |
|---|---|---|
| **淡入的观感**（180ms 是否真的"看不出跳变"、是否显得拖沓） | `Animatable` / `graphicsLayer` / `Image` 全是 Compose 绘制层；本项目只有 junit，无 Robolectric / compose-ui-test（`PhotoViewZoomTest` 的 KDoc 里也写明了这个边界） | **真机 + 慢动作录屏**（§6 判据 A/B） |
| **"不闪了"本身** | 同上 —— "闪"是主观视觉现象 | 真机目视 + 与 v0.11.15 对照 |
| **预览档解码耗时 +15–25ms** | 量级估算（按像素量 ×4 从 sample=8 的 5–10ms 外推），**不是实测** | 真机 `adb shell` 打点或 systrace |
| **内存峰值** | 预览档 0.75MB → 3MB/张、预载缓存上限 4MB → 12MB、淡入期间同时持有两层（预览 3MB + 目标档 12MB ≈ 15MB 峰值 180ms）。**账算得出来，峰值测不到** | 真机 Android Studio Profiler（Memory → Bitmap） |
| **双层绘制的 GPU 成本** | 淡入的 180ms 内每帧多一次全屏 alpha blend | 真机 GPU 渲染条（开发者选项） |
| **`under` 层在缩放态下的对齐** | `viewerLayer` 两层用同一份变换（§3.1 逐行等价），但"看起来没有重影"只能真机看 | 真机：放大到 3–5× 时切页/升档，看有无重影 |
| **`previewTierIsNeverUpscaledBeyondTwoTimes` 的 2.0× 阈值** | 阈值取自"旧口径最小 2.12×"这个观测值，留了 4% 余量（最差一格 1.92×）。**它是回归护栏，不是画质判据** | 若真机上 1440p 竖图仍可察觉阶跃，说明阈值该收紧 ⇒ 需要提高预览档（代价见 §2.4）或延长淡入 |

---

## 6. 真机验收步骤（怎么判断"不闪了"）

**准备**：同一台机器上并排装 v0.11.15（PV-1 前）与 PV-1 版，同一批照片（至少含 12MP 横图 + 竖图各 3 张）。

1. **主判据 A（不闪）**：相册 → 点开一张 → 左右连续滑动 ≥10 张。
   - ✅ 通过 = 每次停下后**看不到"先糊一下、再啪地变清楚"的两段式跳变**；清晰度的变化（若有）是 180ms 内看不出一帧突变的**渐变**。
   - ❌ 不通过 = 仍能指认出"糊 → 清晰"的那一帧。→ 用判据 E 定位。
2. **判据 B（无黑帧）**：盯住换档瞬间，**不应出现纯黑帧**。§2.2 的 ①②③ 顺序就是专防这个；看到黑闪说明顺序被改坏。
3. **判据 C（无转圈）**：滑动停下后**不应出现 `CircularProgressIndicator`**。出现 = 相邻页预载没命中（缓存上限不够 / 预载来不及），回去查 `PREVIEW_CACHE_BYTES` 与预载耗时。
4. **判据 D（E2-20 / E2-23 回归）**：
   - Fit 态单指横滑**仍能切图**（手势层一个事件都不消费）；
   - 双指捏合能到 5×，下限**停在 Fit**（不能缩得比原片默认展示还小）；
   - 放大后单指拖动平移，松手不越界、不留黑边；
   - 放大后左右滑**不切图**；
   - 放大态下**看不到隔壁照片的像素**（E2-23）；
   - 放大到 3–5× 时切页/升档，**下层与上层无重影**（§5 最后一行）。
5. **判据 E（量化定位，判据 A 不通过时用）**：系统录屏开**慢动作 120/240fps**，录"滑动 → 停下"这 1 秒，逐帧看换档处。
   - 旧版：1–2 帧内锐度突变（糊 → 清晰）。
   - PV-1：应是 10–20 帧的渐变。
   - 若仍是突变 ⇒ 淡入没生效（查 `under` 是否被提前置 null / `fade` 是否被 `snapTo(1f)` 复位）或放大倍数仍偏高（查该机型的 `viewSize` 落到了哪个采样台阶，§4.3 的表可以现算）。
6. **判据 F（代价确认）**：首次从相册点开一张（无预载）时的等待**不应比 v0.11.15 明显变长**（预览档解码像素 ×4，预计 +15–25ms）。若肉眼可辨，说明 §2.4 里"提高预览档"的代价估算偏乐观，需要重新权衡。

---

## 7. 顺带确认：相邻页预载（`previews` / `PREVIEW_CACHE_COUNT = 3`）会不会引入延迟或闪烁

**结论：不会引入闪烁；有量级很小的 IO/CPU 争抢，但不需要改。**

1. **不引入闪烁（依据）**：预载只决定"预览档是否已经在内存里"，两条路径都不产生新的视觉突变 ——
   命中 ⇒ `previews.get(u)` 零解码直接上屏（`:442`，与切页在同一帧内完成，无中间帧）；未命中 ⇒
   现解一张，此时 `bitmap` 为 null ⇒ `:684` 的转圈。**"闪"的来源是预览档的清晰度，与预载无关** ——
   这也解释了为什么 E2-22 加了预载之后用户仍然报"闪"。
2. **争抢确实存在，量级小**：`rememberPreview`（`:860-879`）对左右邻页各起一个 `Dispatchers.IO`
   解码，切页后新的邻页立刻启动，与当前页的目标档解码**并发**（`Dispatchers.IO` 的并发度是
   `max(64, 核数)`，真的并行）。旧口径每张 sample=8（500×375，约 5–10ms），PV-1 后 sample=4
   （1000×750，约 15–30ms）—— 像素量 ×4。影响是"当前页目标档晚到 10–20ms"⇒ **预览档在屏上的
   窗口变长一点**。有了交叉淡入后这个窗口的终点是渐变而非突变，感知上不再是"闪"。
3. **一个真实的时序事实（不是 bug，但值得记）**：`rememberPreview` **只在 `p != page` 时调用**
   ⇒ **当前页从不预载**。首次从相册点开第 N 张时，第 N 张的预览档必然不在缓存里 ⇒ 走现解路径
   ⇒ 转圈约 15–30ms（PV-1 后）。这是"点开时"的闪，不是"滑动"时的闪。
   **方案（本卡不实现，先等真机数据）**：相册网格点开时把该张的缩略图一起传进查看页当首帧（零解码）。
   代价是路由层要传一份位图或缩略图路径，且要管它的生命周期 —— 只有当判据 C/F 在真机上确认这
   15–30ms 可见时才值得加。
4. **不建议改的地方**：给邻页预载加"当前页优先"的信号量、或 `delay()` 让当前页先解 —— 都会让
   **快速连滑**时邻页来不及准备，反而制造判据 C 要防的转圈。当前顺序（谁先启动谁先跑）在
   "稳态滑动"下已经够好。

---

## 8. 改动清单

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt` | 删 `PREVIEW_SIDE = 360`，加 `previewRequestFor()`（`:141`）/ `PREVIEW_FADE_MS`（`:160`）；`PREVIEW_CACHE_BYTES` 4MB → 12MB（`:153`）；加 `under` / `fade` 两个 state（`:318` / `:322`）；切页 effect 加 `under = null` + `fade.snapTo(1f)`（`:405-410`）；解码 effect 的预览请求改 `previewRequestFor`（`:448`）并在换档处加 ①②③ 三步（`:468-471`）；新增淡入驱动 effect（`:489-492`）；绘制处加下层 `Image` 并抽出 `viewerLayer()`（`:663-681` / `:834-846`）；`rememberPreview` 加尺寸参数与 0 守卫（`:860-879`） |
| `app/src/test/java/com/filmkit/app/ui/PhotoViewPreviewTest.kt` | **新增**，5 例：预览请求口径、退化容器不为 0、放大倍数 ≤2.0×、采样台阶恰好低一档、预载缓存装得下 3 张 |

**未触碰**（`git status` 可证）：`EditorScreen.kt` / `PresetDetail.kt` / `FrameRenderer.cpp` / `FrameRenderer.h` /
`CameraScreen.kt` / `CameraController.kt` / `EditorExport.kt` / `assets/presets/`。

**未 commit**（按要求留工作区）。
