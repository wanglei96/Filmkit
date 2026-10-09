# PhotonCamera 相册 / 图片查看的加载机制调研

> **本报告只读仓库、未修改任何现有文件**（唯一写入 = 本文件）。
> 所有结论带 `文件:行号` + 代码片段；联网资料带 URL；**拿不到证据的地方明确标注"没查到"**，不猜。
>
> ⚠️ **关于 `git status`**：调研结束时工作区有 6 个已修改文件（`EditorExport.kt` / `EditorScreen.kt` / 4 个 `Editor*Test.kt`），
> 它们由**并行的其他改动**产生，**不是本次调研所为**（本次全程只读仓库 + 写本文件；`/tmp` 下的 clone 不算仓库改动）。
> 其中 `EditorScreen.kt` 的行号因此发生了漂移，已在 §6 标注。
>
> 调研日期：2026-10-09 ｜ 触发问题：FilmKit 查看页切图慢（200–600ms），产品负责人要求"参考 PC，它相册切图非常丝滑，也没有缩略图的模糊感"。

---

## 0. 结论速览（先看这段）

**PC 的"丝滑"不是某个单点技巧，而是 4 个机制叠加**，其中只有第 1 条是"必须的"：

| # | 机制 | 作用 | 代码位置 |
|---|---|---|---|
| **1** | **按"显示尺寸"解码，不是全分辨率** —— 用第三方库 `SubsamplingScaleImageView`(SSIV) 的**瓦片金字塔**，base 层只解到全分辨率约 **1/16** 的像素量（≈ 屏幕像素的 3/4） | 直接消灭 48MB 全解码 | `ImageAdapter.java:411-423` + SSIV `calculateInSampleSize` |
| **2** | **360px 预览占位**（LruCache 3 张），大图瓦片没到之前先画预览 | 消灭"黑屏等待"的感知 | `ImageAdapter.java:79-81, 518-556` |
| **3** | **相邻页预载**（center ±2 的预览 + 尺寸） | 滑过去时已有内容 | `ImageAdapter.java:433-441` |
| **4** | **放大时按 scale 自动升档**（瓦片金字塔多级） | 这是"没有缩略图模糊感"的来源 | SSIV `refreshRequiredTiles` / `initialiseTileMap` |

**关键量化结论（推算，公式见 §2.2）**：PC 在 12MP 图 + 1260×2800 屏上，base 层实际解码 **1000×750 ≈ 0.75MP ≈ 3MB**；
而 FilmKit 现在解 **4000×3000 = 12MP ≈ 48MB**。**相差 16 倍像素量**。

**对我们的意义**：机制 1/2/3 **不需要引入 SSIV 也能拿到**（`decodeOriented` 本来就支持任意目标尺寸，
只是查看页传了 `(0,0)`；注意传参必须是 **Fit 后尺寸**而非屏幕尺寸，见 §7 方案 A 的陷阱表）。
真正需要 SSIV 的只有机制 4 的"瓦片化"，而它在 12MP 这个量级上**边际收益有限**（详见 §7 方案对比）。

---

## 1. 证据来源

| 来源 | 版本 / commit | 获取方式 |
|---|---|---|
| PhotonCamera 源码 | `4ee108e169496f429c0afa0cc33e57bb6b2ec724`（2026-09-20） | 本地 `/tmp/pc`（`origin = https://github.com/eszdman/PhotonCamera`，GPL-3.0）；本次调研前已存在，**本次直接复用，未重新 clone** |
| SubsamplingScaleImageView 源码 | tag `v3.10.0` = `b61c59eb6681f53a412db42aa62d401973c6abcc`（2018-03-15） | 本次 `git clone --depth 1 --branch v3.10.0` 到 `/tmp/ssiv`（**Apache-2.0**，见 §7 许可证） |
| Coil 3 源码 | `main` 分支快照（2026-10-09 抓取） | 本次下载 tarball 到 `/tmp/coil.tgz` 后 `tar tzf` 列文件（用于排除"Coil 能不能替代 SSIV"） |

> PC 仓库 URL：<https://github.com/eszdman/PhotonCamera>
> 下文代码引用的永久链接格式：`https://github.com/eszdman/PhotonCamera/blob/4ee108e169496f429c0afa0cc33e57bb6b2ec724/<path>#L<line>`

---

## 2. 【核心】查看页大图怎么加载

### 2.1 依赖：两个第三方库 + ViewPager2

`/tmp/pc/app/build.gradle:121-131`：

```gradle
implementation 'com.github.bumptech.glide:glide:5.0.5'
annotationProcessor 'com.github.bumptech.glide:compiler:5.0.5'
...
//Subsampling image view
implementation 'com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0'
// Gallery modernization: ViewPager2 for bounded tiling/HDR (thumbnails via Glide - single loader to keep baseline low on 4GB)
implementation 'androidx.viewpager2:viewpager2:1.1.0'
```

**分工（重要，别搞混）**：

- **JPEG 大图（查看页主路径）→ 完全不用 Glide**，走 SSIV 的瓦片解码（`ImageAdapter.java:362-378`）。
- **网格缩略图 → Glide**（`CustomBinding.loadImage`，`override(200,200)`）。
- **DNG → Glide 解成"视口尺寸"单张位图**（不走瓦片，`ImageAdapter.java:282-360`）。

### 2.2 解码目标尺寸：**不是全分辨率，是"视口尺寸再压一档"**

**核心公式**在 SSIV `calculateInSampleSize`（`/tmp/ssiv/.../SubsamplingScaleImageView.java:1369-1404`）：

```java
private int calculateInSampleSize(float scale) {
    if (minimumTileDpi > 0) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float averageDpi = (metrics.xdpi + metrics.ydpi)/2;
        scale = (minimumTileDpi/averageDpi) * scale;   // ← PC 把它压到 160dpi 基线
    }
    int reqWidth = (int)(sWidth() * scale);
    int reqHeight = (int)(sHeight() * scale);
    int inSampleSize = 1;
    ...
    final int heightRatio = Math.round((float) sHeight() / (float) reqHeight);
    final int widthRatio = Math.round((float) sWidth() / (float) reqWidth);
    inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
    // We want the actual sample size that will be used, so round down to nearest power of 2.
    int power = 1;
    while (power * 2 < inSampleSize) { power = power * 2; }
    return power;
}
```

PC 侧的参数在 `/tmp/pc/.../gallery/views/CustomSSIV.java:31-40`：

```java
private void init() {
    setMinimumDpi(40);
    setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
    setQuickScaleEnabled(true);
    setEagerLoadingEnabled(false);
    setDoubleTapZoomDuration(DOUBLE_TAP_ZOOM_DURATION_MS);
    setPreferredBitmapConfig(Bitmap.Config.ARGB_8888);
    // Keep 160 dpi baseline (was 200) — smaller tiles => lower peak Graphics memory, always on to keep 4GB baseline low. Full-res on zoom still.
    setMinimumTileDpi(160);
}
```

**base 层还会再"翻倍一档"**（SSIV `initialiseBaseLayer:1249-1260`）：

```java
// Load double resolution - next level will be split into four tiles and at the center all four are required,
// so don't bother with tiling until the next level 16 tiles are needed.
fullImageSampleSize = calculateInSampleSize(satTemp.scale);
if (fullImageSampleSize > 1) {
    fullImageSampleSize /= 2;
}
```

> `satTemp.scale` 经 `fitToBounds` 夹紧，等于 **minScale（Fit 缩放）**（`fitToBounds(boolean, ScaleAndTranslate)` 末尾 `sat.scale = scale;`，行 1419-1460；`limitedScale` 把值夹到 `[minScale, maxScale]`，行 2283-2287）。

**代入真实数字推算**（源图 4000×3000 / 屏幕 1260×2800 / 平均 dpi ≈ 487，vivo X300 6.31" 1.5K 屏量级）：

| 步骤 | 计算 | 结果 |
|---|---|---|
| minScale | `min(1260/4000, 2800/3000)` | `0.315` |
| 160dpi 基线修正 | `0.315 × 160/487` | `0.1035` |
| reqWidth / reqHeight | `4000×0.1035` / `3000×0.1035` | `414` / `310` |
| inSampleSize | `round(4000/414)=10` → 向下取 2 的幂 | `8` |
| **base 层 sampleSize** | `8 / 2`（"load double resolution"） | **`4`** |
| **base 层位图** | `4000/4 × 3000/4` | **1000×750 ≈ 0.75MP ≈ 3MB** |
| 屏幕 Fit 显示尺寸 | `1260 × 945` | 位图被放大 **1.26×** |

**对比 FilmKit 现状**：`decodeOriented(context, parsed, 0, 0)` ⇒ `sampleSizeFor` 直接返回 1 ⇒ **4000×3000 = 12MP = 48MB**。

> ⚠️ 上表是**按公式推算**，不是真机实测。dpi 用的是量级估计（`DisplayMetrics.xdpi` 在部分机型上报的是逻辑密度而非物理 dpi）。
> 但结论对 dpi 不敏感：dpi 在 380–520 区间内，`inSampleSize` 都落在 8，`fullImageSampleSize` 都是 4。

### 2.3 用什么 API：`BitmapRegionDecoder.decodeRegion` + `inSampleSize`

SSIV 的默认区域解码器（`/tmp/ssiv/.../decoder/SkiaImageRegionDecoder.java:114-121`）：

```java
public Bitmap decodeRegion(@NonNull Rect sRect, int sampleSize) {
    ...
    options.inSampleSize = sampleSize;
    options.inPreferredConfig = bitmapConfig;
    Bitmap bitmap = decoder.decodeRegion(sRect, options);
```

解码器实例是 `BitmapRegionDecoder`（同文件 91-102 行 `BitmapRegionDecoder.newInstance(...)`）。
PC 显式选了**池化版**（`ImageAdapter.java:371`）：

```java
ssiv.setRegionDecoderClass(SkiaPooledImageRegionDecoder.class);
```

`SkiaPooledImageRegionDecoder` 的池化策略（`SkiaPooledImageRegionDecoder.java:47-51, 324`）：

```java
 * One decoder is initialised when the class is initialised. This is enough to decode base layer tiles.
 * Additional decoders are initialised when a subregion of the image is first requested, which indicates
...
 * No additional decoders allowed, reached hard limit (4)
```

**所以 API 栈是**：`SubsamplingScaleImageView` → `SkiaPooledImageRegionDecoder`（池化 ≤4 个）→ `BitmapRegionDecoder.decodeRegion(sRect, opts)`。
**不是** `BitmapFactory` 整图，**也不是** `ImageDecoder.setTargetSize`（PC 只在 DNG 预览路径用了 `ImageDecoder`，见 `ImageAdapter.java:609-622`）。

**线程**：SSIV 用 `AsyncTask.THREAD_POOL_EXECUTOR`（`SubsamplingScaleImageView.java:179`），PC 未覆盖。
另外 PC 自己有一个 **2 线程**的池（`ImageAdapter.java:58-63`）：

```java
// C: single shared pool (2 threads) vs 2×2 pools before — saves ~2 thread stacks (~2 MB) baseline and caps concurrency.
private static final ExecutorService GALLERY_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
    Thread t = new Thread(r, "GalleryBg");
    t.setPriority(Thread.NORM_PRIORITY - 1);
    return t;
});
```

### 2.4 EXIF 方向：交给库，用 `ORIENTATION_USE_EXIF`

`CustomSSIV.java:33`：

```java
setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
```

库内部实现（`SubsamplingScaleImageView.java:1834-1878`）：`content://` 走 MediaStore 的 `MediaStore.Images.Media.ORIENTATION` 列，`file://` 走 `ExifInterface`：

```java
if (sourceUri.startsWith(ContentResolver.SCHEME_CONTENT)) {
    String[] columns = { MediaStore.Images.Media.ORIENTATION };
    cursor = context.getContentResolver().query(Uri.parse(sourceUri), columns, null, null, null);
    ...
} else if (sourceUri.startsWith(ImageSource.FILE_SCHEME) && !sourceUri.startsWith(ImageSource.ASSET_SCHEME)) {
    ExifInterface exifInterface = new ExifInterface(sourceUri.substring(ImageSource.FILE_SCHEME.length() - 1));
    int orientationAttr = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
```

> **注意**：SSIV 的 EXIF 处理**比 FilmKit 弱** —— 只认 `NORMAL/ROTATE_90/180/270` 四个值（行 1862-1871），
> **不处理镜像**（2/4/5/7 会被 `Log.w("Unsupported EXIF orientation")` 忽略）。
> FilmKit 的 `exifOrientationTransform`（`ImageDecode.kt:87-96`）8 个值全覆盖 —— **这一项我们比 PC 强，不要退化**。

**DNG 的 EXIF 是 PC 手写的**（因为 DNG 不走瓦片路径，`ImageAdapter.java:303-324`）：读 EXIF → 90/270 且位图是横的才 `Matrix.postRotate` 转正。
注意它的判据 `if ((rotation == 90 || rotation == 270) && out.getWidth() > out.getHeight())`（行 317）——**这是启发式**，依赖"Glide 已按 EXIF 转过一次"的前提，属于取巧写法。

### 2.5 放大时怎么"补清晰度"：瓦片金字塔（多级 sampleSize）

`initialiseTileMap`（`SubsamplingScaleImageView.java:1484-1529`）为**每一级 sampleSize（4 → 2 → 1）**都建一张瓦片网格：

```java
int sampleSize = fullImageSampleSize;
while (true) {
    ...
    while (subTileWidth + xTiles + 1 > maxTileDimensions.x || (subTileWidth > getWidth() * 1.25 && sampleSize < fullImageSampleSize)) {
        xTiles += 1;   // 每块瓦片显示宽度不超过视图宽 × 1.25
        ...
    }
    tileMap.put(sampleSize, tileGrid);
    if (sampleSize == 1) break; else sampleSize /= 2;
}
```

`refreshRequiredTiles`（行 1291-1330）在 scale 变化时切级，并**回收不需要的瓦片**：

```java
int sampleSize = Math.min(fullImageSampleSize, calculateInSampleSize(scale));
// Load tiles of the correct sample size that are on screen. Discard tiles off screen, and those that are higher
// resolution than required, or lower res than required but not the base layer, so the base layer is always present.
...
if (tile.sampleSize < sampleSize || (tile.sampleSize > sampleSize && tile.sampleSize != fullImageSampleSize)) {
    tile.visible = false;
    if (tile.bitmap != null) { tile.bitmap.recycle(); tile.bitmap = null; }
}
```

`tileVisible`（行 1332-1338）按**视口相交**判定：

```java
float sVisLeft = viewToSourceX(0), sVisRight = viewToSourceX(getWidth()), ...;
return !(sVisLeft > tile.sRect.right || tile.sRect.left > sVisRight || sVisTop > tile.sRect.bottom || tile.sRect.top > sVisBottom);
```

**这就是"没有缩略图模糊感"的机制**：不是一次性解一张糊图，而是**放大多少就加载多清晰的瓦片，只加载视口内的**。
内存上界 ≈ base 层（3MB）+ 视口内当前级的几块瓦片 —— PC 自己在注释里写的口径是
`O(viewport) ~10-12 MB per page at 160dpi`（`ImageViewerFragment.java:280`）。

**但要如实说明一个取舍**：`minimumTileDpi=160` 意味着瓦片分辨率按 **160dpi 基线**算，而不是屏幕 dpi。
屏幕 487dpi 时，等于**主动把瓦片分辨率降到屏幕所需的 ~1/3**。后果：
- Fit（1×）时：1000×750 铺 1260×945 → 放大 1.26×，肉眼几乎无感 ✅
- 双击放大（SSIV 默认 `doubleTapZoomScale = 1F`，行 190）时：`calculateInSampleSize(1.0)` → `1.0×160/487=0.329` → `reqW=1316` → `ratio=3` → `power=2` → 升到 sampleSize=2（2000×1500 级）→ 仍约 2× 插值，**有轻微软化** ⚠️

> PC 代码注释自称 `Full-res on zoom still`（`CustomSSIV.java:38`）。严格按公式，只有 scale ≥ ~3 才落到 sampleSize=1；
> 而 SSIV 默认 `maxScale = 2F`（`SubsamplingScaleImageView.java:159`）且 PC 未覆盖 `setMaxScale`，所以**实际最高只到 sampleSize=2 级**。
> 这是"内存/速度优先"的取舍，不是 bug —— 但它说明 **PC 并非"无损高清 + 丝滑"，而是"够清 + 极快"**。

---

## 3. 相册网格缩略图怎么加载

**用 Glide，不用平台 `loadThumbnail`。**

`/tmp/pc/.../gallery/binding/CustomBinding.java:58-75`：

```java
@BindingAdapter("loadImage")
public static void loadImage(ImageView imageView, GalleryItem galleryItem) {
    if (galleryItem != null && galleryItem.getFile() != null && galleryItem.getFile().getFileUri() != null) {
        Glide.with(imageView)
                .asBitmap()
                .load(galleryItem.getFile().getFileUri())
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .signature(new ObjectKey(galleryItem.getFile().getDisplayName() + galleryItem.getFile().getLastModified()))
                        .override(200, 200)
                        .centerCrop()
                )
                .into(imageView);
    } else {
        Glide.with(imageView).clear(imageView);
        imageView.setImageDrawable(null);
    }
}
```

绑定点在布局里（`/tmp/pc/app/src/main/res/layout/thumbnail_square_image_view.xml`）：

```xml
<com.particlesdevs.photoncamera.gallery.views.SquareImageView
        android:id="@+id/square_image_view"
        android:scaleType="centerCrop"
        app:loadImage="@{galleryitem}"
```

回收时取消加载（`ImageGridAdapter.java:55-66`）：

```java
public void onViewRecycled(@NonNull GridItemViewHolder holder) {
    // Cancel Glide thumbnail load and free drawable to avoid flash on fast scroll
    com.particlesdevs.photoncamera.gallery.binding.CustomBinding.clearImage(b.squareImageView);
    b.squareImageView.setImageDrawable(null);
}
```

**尺寸口径**：网格 `grid_columns = 4`（`/tmp/pc/app/src/main/res/values/integers.xml:4`），
Glide 目标 `override(200, 200)` —— 在 1080 宽屏上每格 270px，**即目标尺寸略低于格子显示尺寸**（故意的省内存取舍）。
缓存策略 `DiskCacheStrategy.RESOURCE`（缓存转换后的 200×200 结果）+ `signature(名字+修改时间)` 做失效键。

> **没查到**：PC 里**没有任何** `loadThumbnail` / `MediaStore.Images.Thumbnails` / `Thumbnails.getThumbnail` 调用
> （全仓 grep 无结果）。它完全放弃了平台缩略图缓存，自己用 Glide 解 —— 猜测是为了统一 DNG/JPEG 与方向口径（**这是推断，PC 代码里没写理由**）。

---

## 4. 缓存 / 预加载

### 4.1 内存缓存：全部是**小容量 LruCache**，没有大图常驻

`ImageAdapter.java:76-91`：

```java
// C: small preview + native dimensions per position – shown immediately under tiles so no black flash.
// Capped to 3 entries and 360px to keep baseline low on 4GB devices (was 6×480px ~9 MB -> now 3×360px ~1.5 MB).
private final LruCache<Integer, Bitmap> previewCache = new LruCache<>(3);
private final LruCache<Integer, Point> dimsCache = new LruCache<>(3);
private static final int PREVIEW_SIDE = 360;
...
private final LruCache<Integer, Bitmap> dngBitmapCache = new LruCache<Integer, Bitmap>(2) { ... };
```

- `previewCache`：3 张 360px 预览（≈1.5MB）
- `dimsCache`：3 个原始尺寸（给 SSIV 的 `ImageSource.dimensions()` 用，避免它自己再读一次文件头）
- `dngBitmapCache`：2 张视口尺寸 DNG 位图（**仅 DNG**；JPEG 大图不进这个缓存）
- **JPEG 大图没有整图内存 LRU** —— 靠 SSIV 的瓦片存活，切走即回收（`recycleIfNeeded`，`CustomSSIV.java:46-50`）

内存压力回收（`ImageAdapter.java:770-787`）：

```java
public void trimCaches(int level) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
        previewCache.evictAll(); dimsCache.evictAll(); dngBitmapCache.evictAll();
    } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) { ... }
}
```

### 4.2 相邻页预载：**center ± 2**

`ImageAdapter.java:433-441`：

```java
public void preloadPreviews(int centerPos) {
    if (appContext == null) return;
    int start = Math.max(0, centerPos - 2);
    int end = Math.min(galleryItemList.size() - 1, centerPos + 2);
    for (int i = start; i <= end; i++) {
        ensurePreview(i);
        preloadDngFull(i);
    }
}
```

调用时机：**进入查看页时**（`ImageViewerFragment.java:223-224`）与**每次翻页后**（行 298-299）：

```java
// Eagerly preload previews for the window so neighbor pages show a placeholder (no black).
adapter.preloadPreviews(seek_position);
...
// Re-arm preview preload for the new window so neighbors remain instant.
if (adapter != null) adapter.preloadPreviews(position);
```

预览解码本身（`ImageAdapter.java:605-622`）—— **优先 `ImageDecoder.setTargetSize`，API 28 以下退 `BitmapFactory.inSampleSize`**：

```java
private Bitmap decodePreview(ContentResolver cr, Uri uri, Point dims) {
    int max = Math.max(dims.x, dims.y);
    int sample = 1;
    while (max / sample > PREVIEW_SIDE * 2) sample *= 2; // generous oversample then downscale
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            ...
            if (m > PREVIEW_SIDE) {
                float ratio = (float) PREVIEW_SIDE / m;
                decoder.setTargetSize(Math.round(w * ratio), Math.round(h * ratio));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
```

### 4.3 Pager 侧：`offscreenPageLimit = 1`，**没有** `beyondViewportPageCount` 这种东西

`ImageViewerFragment.java:279-290`（ViewPager2）：

```java
viewPager.setPageTransformer(null);
// D: O(viewport) ~10-12 MB per page at 160dpi; keep baseline low on 4GB: offscreen=1 and cache=1 (was 2/2).
// Still pre-warms ±1 tile via preview placeholder, without extra native tile retention.
viewPager.setOffscreenPageLimit(1);
viewPager.setUserInputEnabled(true);
RecyclerView rv = (RecyclerView) viewPager.getChildAt(0);
rv.setHasFixedSize(true);
rv.setItemViewCacheSize(1);
```

> `beyondViewportPageCount` 是 **Compose `HorizontalPager`** 的参数；ViewPager2 的对应物是 `setOffscreenPageLimit`。PC 用的是 ViewPager2，所以是 1。

### 4.4 手势中不加载：`setEagerLoadingEnabled(false)`

`CustomSSIV.java:35`。SSIV 语义（`SubsamplingScaleImageView.java:2832`）：

```java
* @param eagerLoadingEnabled true to enable loading during gestures, false to delay loading until gestures end
```

配合 `refreshRequiredTiles(eagerLoadingEnabled)`（行 783/837/875）—— **捏合/拖动过程中不解码新瓦片，手势结束才解**，避免滑动 jank。

---

## 5. 有没有"两段式"策略？

**有，而且是明确的"低清占位 → 高清补上"两段**，但形态不是"先糊后清同一张图"，而是：

**段 1（预览层）**：360px 位图 + 原始尺寸，通过 SSIV 的双 source API 立刻上屏
`ImageAdapter.java:411-423`：

```java
private void setImage(CustomSSIV ssiv, Uri uri, int position) {
    Bitmap preview = previewCache.get(position);
    Point dims = dimsCache.get(position);
    if (preview != null && dims != null && !preview.isRecycled()) {
        ssiv.setImage(
                ImageSource.uri(uri).dimensions(dims.x, dims.y),
                ImageSource.cachedBitmap(preview));      // ← 第二个参数 = 占位图，先画它
    } else {
        ssiv.setImage(ImageSource.uri(uri));
        ensurePreview(position);                          // ← 没有预览就后台补一个
    }
}
```

**段 2（瓦片层）**：SSIV 解出 base 瓦片后自动覆盖预览；放大时再升档到 sampleSize=2/1。

**没有**"下采样 + 二次清晰化（锐化/超分）"这种做法 —— **没查到**任何 sharpen/super-resolution 相关代码。

---

## 6. 对比 FilmKit：具体差距

| 维度 | PC | FilmKit 现状 | 差距性质 |
|---|---|---|---|
| **查看页解码尺寸** | base 1000×750（sampleSize=4，160dpi 基线） | **全分辨率 4000×3000**（`decodeOriented(ctx, uri, 0, 0)`，`PhotoViewScreen.kt:266`） | 🔴 **主因**，16× 像素量 |
| 解码 API | `BitmapRegionDecoder.decodeRegion(rect, opts)`（区域瓦片） | `BitmapFactory.decodeStream` 整图（`ImageDecode.kt:49-51`） | 🟡 瓦片是"放大也清晰"的前提 |
| 预览占位 | 360px LruCache(3) 先上屏（`ImageAdapter.java:411`） | **无** —— `bitmap = null` ⇒ 显示 `CircularProgressIndicator`（`PhotoViewScreen.kt:255` 置空、`:419` 转圈） | 🔴 直接造成"等待感" |
| 相邻页预载 | center ±2 预览 + 尺寸（`preloadPreviews`） | **无**（未设 `beyondViewportPageCount`，默认 0；且 `if (p == page) bitmap else null`，`PhotoViewScreen.kt:400`） | 🔴 滑过去时相邻页是空的 |
| 大图内存缓存 | 无整图 LRU（靠瓦片） | 无（`var bitmap` 单张，切页即置 null） | ⚪ 打平 |
| 放大补清晰度 | 瓦片金字塔自动升档 | 无（`graphicsLayer` 放大同一张位图，`PhotoViewScreen.kt:412-418`） | 🔴 **MAX_ZOOM=5 时必然糊** |
| EXIF 方向 | SSIV `ORIENTATION_USE_EXIF`（只认 4 个值，镜像不处理） | `decodeOriented` 8 个值全覆盖（`ImageDecode.kt:87-96`） | 🟢 **我们更强，别退化** |
| 网格缩略图 | Glide `override(200,200)` + 磁盘缓存 RESOURCE | `decodeOriented(px, px)`，px=屏宽/3（`GalleryScreen.kt:137`），内存 `ThumbCache` 24MB/300 张 | 🟢 基本对等（网格不是瓶颈） |
| 手势中加载 | `eagerLoadingEnabled=false`（手势结束才解） | Compose 手势 + 单张位图，无此问题 | ⚪ 不适用 |

**关键旁证：`decodeOriented` 本来就支持按尺寸解码** —— 同一个函数在
`EditorScreen.kt` 的 `loadPreview` 里被以 `(reqW, reqH)` 调用、在 `CameraScreen.kt:557` 被以 `(128,128)` 调用、在 `GalleryScreen.kt:283` 被以 `(px,px)` 调用。

> 📌 **行号时效提示**：`EditorScreen.kt` 在本次调研期间被**并行改动**（`decodeOriented` 调用点从 `:2758` 漂到 `:2768`）。
> 本报告其余三个 FilmKit 核心文件（`PhotoViewScreen.kt` / `GalleryScreen.kt` / `ImageDecode.kt`）在调研时**工作区干净、未被改动**，
> 引用行号对当时快照有效；若后续有改动，请以**函数名/调用点**为准而非行号。
**只有查看页传了 `(0,0)`** —— 这不是能力缺失，是调用点参数问题。

---

## 7. 建议方案

### 前置：许可证事实（决定能不能"照抄 PC"）

| 库 | 许可证 | 能否用于 FilmKit |
|---|---|---|
| **SubsamplingScaleImageView** | **Apache-2.0**（`/tmp/ssiv/LICENSE` 首行 `Apache License Version 2.0`） | ✅ 可闭源商用（保留 NOTICE） |
| Glide | Apache-2.0 | ✅ |
| **PhotonCamera 自身代码** | **GPL-3.0**（`/tmp/pc/LICENSE`；README 徽章 GPLv3） | ❌ **代码不能抄进闭源 App**；但**做法/公式/参数不受版权保护**，可以照做 |

> 这一条很关键：**PC 的 GPL 代码不能复制**，但"按显示尺寸解码 + 预览占位 + 预载"是通用工程做法，自己写没有法律问题。

### 方案 A —— 按 **Fit 尺寸**解码（核心改动就这一处）

> ⚠️ **先看这个陷阱**：直接传"屏幕尺寸"会让改动**完全失效**（本次用脚本按 `sampleSizeFor` 的真实判据验证过）。

`sampleSizeFor` 的判据是**两个维度同时满足**才降档（`ImageDecode.kt:106`：`srcW/(s*2) >= reqW && srcH/(s*2) >= reqH`）。
屏幕高度 2800 > 源图高度的一半，所以竖着传必然卡在 `s=1`：

| 源图（12MP 级） | Fit 进 1260×2800 后的显示尺寸 | 传 **Fit 尺寸** → sample | 传 **屏幕 (1260,2800)** → sample |
|---|---|---|---|
| 4000×3000（横） | 1260×945 | **2** → 2000×1500（3.0MP） | **1** → 4000×3000（**12MP，全分辨率**） |
| 3000×4000（竖） | 1260×1680 | **2** → 1500×2000（3.0MP） | **1** → 3000×4000（**12MP，全分辨率**） |
| 8000×1000（全景） | 1260×158 | **4** → 2000×250（0.5MP） | **1** → 8000×1000（8MP） |

**正确做法**：新增一个薄封装（复用 `decodeOriented`，约 15 行），语义 = "缩到能完整放进容器"：

```kotlin
// ImageDecode.kt 新增
/** 按"完整放进 maxW×maxH"算目标尺寸再解码（查看页 Fit 视图不需要超出容器的像素）。 */
internal fun decodeFit(context: Context, uri: Uri, maxW: Int, maxH: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val ok = try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        o.outWidth > 0 && o.outHeight > 0
    } catch (_: Throwable) { false }
    if (!ok || maxW <= 0 || maxH <= 0) return decodeOriented(context, uri, 0, 0) // 兜底：退回现状
    val k = minOf(maxW.toFloat() / o.outWidth, maxH.toFloat() / o.outHeight)
    return decodeOriented(context, uri, ceil(o.outWidth * k).toInt(), ceil(o.outHeight * k).toInt())
}
```

调用点（`PhotoViewScreen.kt:266`；`viewSize` 已存在，来自照片区 `Box` 的 `onSizeChanged`）：

```kotlin
- val b = withContext(Dispatchers.IO) { decodeOriented(context, parsed, 0, 0) }
+ val b = withContext(Dispatchers.IO) { decodeFit(context, parsed, viewSize.width, viewSize.height) }
```

| 代价 | 评估 |
|---|---|
| 内存 | 48MB → **12MB**（2000×1500 级），↓4× |
| 速度 | 解码时间约 ↓2–3×（**非线性**：JPEG 熵解码仍要全量，`inSampleSize` 只省 IDCT/上采样）—— 需实测 |
| 复杂度 | 小（+15 行，无新依赖） |
| 缺点 | Fit 时够清晰；**放大到 5× 必糊**（2000px 铺 5×1260=6300 屏幕像素）→ 由方案 B 解决 |

### 方案 B —— 两段式 + 放大补解码（**推荐**）

在 A 的基础上加两件事：

1. **预览占位**：进页先解一张 ~360px（`decodeOriented(ctx, uri, 360, 360)`）立刻上屏，再解 Fit 尺寸替换。
   —— 复用现有函数，**不引入任何依赖**，直接消灭"转圈等待"。
2. **放大补解码**：`zoom` 跨过阈值（如 ≥2×）时，用**放大后的容器尺寸**重解一次（不是 `screenW × zoom`，理由同上表的陷阱），旧位图继续显示直到新图就绪。

```kotlin
// 伪代码：与现有 LaunchedEffect(page) 同构，多一个 key = 档位
val tier = if (zoom < 2f) 1 else 2   // 两档足够：12MP 只有 4000px，5× 已超原图
LaunchedEffect(page, tier) {
    val b = decodeFit(                      // ← 传容器尺寸 × 档位，由 decodeFit 内部算 Fit
        context, parsed,
        viewSize.width * tier,
        viewSize.height * tier,
    )   // 旧 bitmap 保留到新图就绪，避免闪回占位
    ...
}
```

| 代价 | 评估 |
|---|---|
| 内存 | 常态 12MB；放大时 12MB + 48MB 短暂共存（新图到后 `recycle` 旧的） |
| 速度 | 切图常态 ~100–250ms（推算）+ **预览 0 等待**；放大时一次性 200–600ms（用户主动操作，可接受） |
| 复杂度 | 小（+30 行左右，无新依赖、无新架构） |
| 收益 | 覆盖用户抱怨的**两点**：切图快 + 放大不糊 |

**为什么推荐 B 而不是 C**（按 ponytail 阶梯：仓库已有 > 已装依赖 > 新依赖）：
- 解码能力**仓库里已经有**（`decodeOriented` 支持任意 `reqW/reqH`，且已被 3 处调用验证）
- **不引入依赖**：Coil 经核实**没有** subsampling 能力（见下），引它只为一个 `size()` 不值；SSIV 是 View 体系，与 Compose 手势/状态机互操作成本高
- 我们的**唯一真实瓶颈是"解了 48MB"**，不是"没有瓦片"

### 方案 C —— 完整 PC 式瓦片（SSIV + `AndroidView` 互操作）

引入 `com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0`（Apache-2.0），用 `AndroidView` 包 `CustomSSIV`。

| 代价 | 评估 |
|---|---|
| 内存 | 最好（O(viewport) ≈ 3–12MB/页） |
| 速度 | 最好（只解视口瓦片） |
| 复杂度 | **高**：① 现有 Compose 手势仲裁（`viewerGesture`，`PhotoViewScreen.kt:111-117`）要整个让位给 SSIV；② `ORIENTATION_USE_EXIF` **只认 4 个值、不处理镜像** ⇒ 会**退化**我们现在的 8 值支持，DNG 方向可能出问题；③ 与 `settledPage`/编辑器跳转的状态同步要重做；④ 新增依赖 + 需处理 `recycle` 生命周期 |
| 适用 | 仅当 B 真机实测不达标（如 50MP 图）时再上 |

> **Coil 不能替代 SSIV**（已核实）：下载 Coil `main` 分支源码后列目录，解码器只有
> `BitmapFactoryDecoder.kt` / `StaticImageDecoder.kt` / `ExifOrientationStrategy.kt` / `ExifUtils.kt`（`coil-core/src/androidMain/kotlin/coil3/decode/`），
> **不存在 `SubSamplingImageDecoder`**；CHANGELOG（95KB）grep `subsampl` **零命中**。
> Coil 能做的只是"按目标尺寸下采样"，与方案 B 第 1 步等价，且要多背一个依赖。

### 方案 D（可选，独立于 A/B/C）—— 相邻页预载

`HorizontalPager(beyondViewportPageCount = 1)`，配合相邻页只解 360px 预览。
收益：滑动过程中相邻页不再是空白（PC 的 `preloadPreviews` 等价物）。
代价：多 1 张预览位图（≈0.5MB），几乎免费。**建议与 B 一起做**。

### 推荐落地顺序

1. **A（+15 行 `decodeFit` + 改 1 个调用点）先上真机实测** —— 拿到"Fit 尺寸解码"的真实耗时/内存，作为后续判断的基线
2. **加 B 的预览占位 + D 的预载** —— 消除等待感（用户最直接的体感）
3. **加 B 的放大补解码** —— 解决 MAX_ZOOM=5 的糊
4. 只有当 1–3 实测仍不达标（例如未来 50MP 图）才考虑 C

> ⚠️ **符合项目铁律**：上面所有"↓4×/↓2–3×"都是**按公式推算**，不是实测。
> 关键待测指标：① `decodeOriented` 在 sampleSize=1 vs 2 的真机耗时；② 解码期间 Java 堆峰值；③ 预览占位后用户感知的"首帧时间"。
> 建议在真机上用 `System.nanoTime()` 打点，不要用本报告的推算数字做决策。

---

## 8. 没查到的 / 不确定的（如实声明）

1. **PC 为什么放弃平台缩略图 API**（`loadThumbnail`/`Thumbnails`）—— 代码里**没有注释说明理由**，我的"统一 DNG/方向口径"是**推断**，无证据。
2. **PC 的 `ImageDecoder` 预览路径是否在所有机型生效** —— `decodeBounds`/`decodePreview` 里 `catch (Exception ignored)` 静默吞异常（`ImageAdapter.java:568, 581, 600, 623`），**失败时无日志**，无法从代码判断实际命中率。
3. **PC 的实机性能数字** —— 代码注释里的 `~10-12 MB per page`、`saves ~48ms` 等是 PC 作者自报，**本次没有实机复现**。
4. **PC 是否在别的入口（相机内"最近照片"缩略图）用了不同机制** —— 本次时间盒优先查查看页，未穷尽全仓。
5. **`DisplayMetrics.xdpi` 在 vivo X300 上的真实值** —— 用了量级估计（487）。§2.2 的结论对 dpi 不敏感（380–520 都落到 sampleSize=4），但**精确数字需真机读取**。
6. **FilmKit 查看页对 DNG 的实际表现** —— `GalleryScreen.kt:260` 的注释暗示 DNG 在网格里"解不开"（显示占位）；
   `PhotoViewScreen.kt:266` 同样走 `decodeOriented`（`BitmapFactory`），**推测 DNG 在查看页也会失败** —— 但**本次未实测验证**，这是独立于本报告的另一个问题，建议单独排查。

---

## 9. 证据索引（行号速查）

| 结论 | 文件 | 行号 |
|---|---|---|
| 依赖：Glide 5.0.5 / SSIV 3.10.0 / ViewPager2 | `app/build.gradle` | 121-131 |
| JPEG 大图走瓦片（不用 Glide） | `.../gallery/adapters/ImageAdapter.java` | 362-378 |
| 预览占位 + 尺寸双 source | 同上 | 411-423 |
| 预载 center ±2 | 同上 | 433-441 |
| 预览解码（ImageDecoder.setTargetSize） | 同上 | 605-637 |
| 缓存：previewCache(3) / dimsCache(3) / PREVIEW_SIDE=360 | 同上 | 76-91 |
| DNG 手写 EXIF 转正 | 同上 | 303-324 |
| 2 线程 GALLERY_EXECUTOR | 同上 | 58-63 |
| trimCaches | 同上 | 770-787 |
| 网格 Glide override(200,200) | `.../gallery/binding/CustomBinding.java` | 58-75 |
| 网格列数 = 4 | `app/src/main/res/values/integers.xml` | 4 |
| SSIV 参数（minimumTileDpi=160 / eagerLoading=false / USE_EXIF） | `.../gallery/views/CustomSSIV.java` | 31-40 |
| ViewPager2 offscreenPageLimit=1 | `.../gallery/ui/fragments/ImageViewerFragment.java` | 279-290 |
| 进页预载 + 翻页预载 | 同上 | 223-224, 298-299 |
| **SSIV：采样公式** | `/tmp/ssiv/.../SubsamplingScaleImageView.java` | **1369-1404** |
| SSIV：base 层"double resolution" | 同上 | 1249-1260 |
| SSIV：瓦片金字塔 | 同上 | 1484-1529 |
| SSIV：按 scale 切级 + 回收 | 同上 | 1291-1330 |
| SSIV：视口相交判定 | 同上 | 1332-1338 |
| SSIV：EXIF（仅 4 值，无镜像） | 同上 | 1834-1878 |
| SSIV：默认 maxScale=2 / doubleTapZoomScale=1 | 同上 | 159, 190 |
| SSIV：区域解码 = BitmapRegionDecoder | `/tmp/ssiv/.../decoder/SkiaImageRegionDecoder.java` | 114-121 |
| SSIV：解码器池上限 4 | `/tmp/ssiv/.../decoder/SkiaPooledImageRegionDecoder.java` | 47-51, 324 |
| **FilmKit：查看页全分辨率解码** | `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt` | **266** |
| FilmKit：切页置空 bitmap（→ 转圈） | 同上 | 255, 400, 419 |
| FilmKit：MAX_ZOOM = 5 | 同上 | 95 |
| FilmKit：graphicsLayer 缩放 | 同上 | 412-418 |
| FilmKit：`sampleSizeFor`（结果 ≥ 目标） | `app/src/main/java/com/filmkit/app/ui/ImageDecode.kt` | 103-108 |
| FilmKit：EXIF 8 值全覆盖 | 同上 | 87-96 |
| FilmKit：网格缩略图 decodeOriented(px,px) | `app/src/main/java/com/filmkit/app/ui/GalleryScreen.kt` | 283 |
| FilmKit：ThumbCache 24MB/300 张 | 同上 | 65-66, 122-127 |
| FilmKit：编辑器已按尺寸解码（先例） | `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt` | `loadPreview` 内（调研时 :2758，并行改动后 :2768） |
