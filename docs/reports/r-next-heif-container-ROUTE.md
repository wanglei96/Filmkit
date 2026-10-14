# 实现卡 A0b · HEIF 容器真相与期 A 路线定案

> 卡片：**路线定案卡 A0b**（承接 `docs/reports/r-next-heif10-probe-REPORT.md` 的真机事实）。
> 日期：2026-10-14。
> 边界：**只出方案**，不改任何源码、不构建、不跑 App。
> 一手证据来源：AOSP `frameworks/av` main 分支源码（本机 `curl` 抓取，逐行给行号）、
> androidx `heifwriter` main 分支源码、libheif main 分支源码、Android SDK `android-36`。

---

## ⓪ 结论摘要

1. **AOSP 不写 HEIF 图片的原因**（一句话，供方案引用）：
   `MediaMuxer` 对 `MUXER_OUTPUT_HEIF` **只做一件事** —— `MediaMuxer.cpp:90-92` 把 `kKeyFileType` 设为 3；
   真正决定"写 MP4 还是写 HEIF"的是 `MPEG4Writer.cpp:2314`
   `mIsHeic = !strcasecmp(mime, "image/heic")`：**track 的 MIME 不是 `image/heic` 就一律走 MP4 分支**。
   我们的 `MediaCodec` 编码器输出格式是 `video/hevc` ⇒ `mIsHeif=false`
   ⇒ `mHasMoovBox=true` / `mHasFileLevelMeta=false`（`:766-768`）⇒ 写 `moov`、不写 `meta`、major brand 落到 `mp42`。
2. **androidx `HeifWriter`**：`useBitDepth10` / `highBitDepthEnabled` 硬编码 `false` ⇒ 公开 API 是 **8bit 专用**
   （内部 `EncoderBase.java:376-463` 的 10bit 链路完整存在但未暴露）。它的价值在于暴露了"AOSP 写 HEIF 的唯一钥匙"：
   `HeifEncoder.java:89-100` 在把编码器 `outputFormat` 交给 `addTrack()` 之前，**把 `KEY_MIME` 改写成 `"image/heic"`**。
3. **期 A 路线**：**自研 HEIF 静态图写入器**，**不引入 libheif**。
   可行性已由架构师端到端实测消除（`tools/heif10-container-ref.py` → macOS `sips` 强制解码成功）。
   §5.2~5.5 是给 Kotlin/Java 实现者的最小清单；§5.6 是兼容性风险清单。
4. **四个必须外显的结论（每个都有源码行号支撑）**：
   - **`pixi` 建议写**（§4.3）：`03 0A 0A 0A` = 3 通道 × 10bit，**别漏开头的通道数字节**（R9）。
   - **`colr` 第 4 字节是位域**（§3.3 / R8）：`full_range_flag` 在**最高位** ⇒ 全范围写 **`0x80`**，不是 `0x01`。
   - **`ipma` 索引是 1-based 且高 1 位是 essential**（§5.4 / R10）：`0x81` = "essential=1 + index=1"，**不是索引 129**。
   - **Android 常量 ≠ CICP，必须显式转换**（§3.3）：`BT2020=6 → primaries 9`、`LINEAR=1 → transfer 8`、
     `matrix 9`、`full 1`（**已由真机实测输出 9/8/9/1 逐项确认**）。

---

## ① Q1 · `MUXER_OUTPUT_HEIF`（=3）在 AOSP 里的真实行为

### 1.1 常量本身（一手）

| 事实 | 证据 |
|---|---|
| `MUXER_OUTPUT_HEIF = MUXER_OUTPUT_FIRST + 3 = 3` | `frameworks/base/media/java/android/media/MediaMuxer.java:268`（`MUXER_OUTPUT_FIRST = 0`，`MPEG_4=0 / WEBM=1 / 3GPP=2 / HEIF=3 / OGG=4`） |
| HEIF 与 MP4/3GPP 共用同一个 writer | `MediaMuxer.cpp:47-52` `isMp4Format()` 把 `OUTPUT_FORMAT_HEIF` 与 `MPEG_4`/`THREE_GPP` 并列返回 true |
| 构造时**唯一**的 HEIF 特化动作 | `MediaMuxer.cpp:88-93`：`if (format == OUTPUT_FORMAT_HEIF) mFileMeta->setInt32(kKeyFileType, output_format::OUTPUT_FORMAT_HEIF);` —— **只设了一个 metadata 键**，没有别的 |

即：`MUXER_OUTPUT_HEIF` 与 `MUXER_OUTPUT_MPEG_4` 的差别**仅仅是 `kKeyFileType` 这一个整数**。
这个事实本身就已经解释了为什么"选了 HEIF 却得到 MP4"。

### 1.2 `kKeyFileType` 的唯一作用点：ftyp 的 major brand

`MPEG4Writer.cpp:1578-1622` `writeFtypBox()`：

```cpp
int32_t fileType;
if (!param || !param->findInt32(kKeyFileType, &fileType)) {
    fileType = OUTPUT_FORMAT_MPEG_4;          // ← 没有这个键就默认 MP4
}
if (fileType != OUTPUT_FORMAT_MPEG_4 && fileType != OUTPUT_FORMAT_HEIF) {
    writeFourcc("3gp4"); ...                  // 3GPP 路径
} else {
    // Only write "heic"/"avif" as major brand if the client specified HEIF/AVIF
    // AND we indeed receive some image heic/avif tracks.
    if (fileType == OUTPUT_FORMAT_HEIF && mHasFileLevelMeta) {   // ← 关键与条件
        writeFourcc(mIsAvif ? "avif" : "heic");                  // major brand
    } else {
        writeFourcc("mp42");                                     // ← 我们看到的
    }
    writeInt32(0);
    if (mHasFileLevelMeta) { writeFourcc("mif1"); writeFourcc("heic"); ... }
    if (mHasMoovBox)       { writeFourcc("isom"); writeFourcc("mp42"); }
}
```

**决定性一行是注释里的 `AND we indeed receive some image heic/avif tracks`** ——
`kKeyFileType` 只决定"允许写 heic"，真伪由 `mHasFileLevelMeta` 决定。
`mHasFileLevelMeta` 只能由 `isHeif()` 的 track 置位（见下）。

### 1.3 真正的判定：track MIME 字符串，且**必须是 `image/heic`**

`MPEG4Writer.cpp:2311-2316`（`MPEG4Writer::Track` 构造函数）：

```cpp
mIsHevc = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_HEVC);            // "video/hevc"
...
mIsHeic = !strcasecmp(mime, MEDIA_MIMETYPE_IMAGE_ANDROID_HEIC);    // "image/heic"
mIsAvif = !strcasecmp(mime, MEDIA_MIMETYPE_IMAGE_AVIF);            // "image/avif"
mIsHeif = mIsHeic || mIsAvif;
```

`MPEG4Writer.cpp:766-768`（`startTrack()`）：

```cpp
mHasMoovBox       |= !track->isHeif();   // HEIF track ⇒ 不写 moov
mHasFileLevelMeta |=  track->isHeif();   // HEIF track ⇒ 写文件级 meta
mIsAvif           |= track->isAvif();
```

⇒ 传 `video/hevc` 时：`mIsHeif=false` ⇒ `mHasMoovBox=true`、`mHasFileLevelMeta=false`
⇒ 写 `moov`、不写 `meta`、major brand 落到 `mp42`。
**这与探针实测的 `ftyp,moov,free,mdat` + 无 `meta` 逐个 box 对齐，是同一个因果链。**

### 1.4 框架侧不会替你改写 MIME

`MediaMuxer.java:698-740` `addTrack()` 全文只有 `format.getMap()` → `nativeAddTrack()`，
**没有任何 mime 规范化 / HEIF 分支 / 键改写**。
所以"用 MUXER_OUTPUT_HEIF 就能得到 HEIF"是一个**错误预期**；
决定结构的是 `addTrack()` 传进去的那张 MediaFormat 的 `mime`。

### 1.5 结论：存在能产出真 HEIF image item 的配置，条件是 MIME

AOSP **确实**会写完整的 HEIF image item 结构，条件是 track mime == `image/heic`：

| 写入物 | 证据 |
|---|---|
| `meta` 文件级 box（`pitm`/`iinf`/`iloc`/`iprp`/`ipco`/`ipma`） | `MPEG4Writer.cpp:2773` `CHECK(mIsHeif)`、`:4522` `CHECK(mIsHeif)`（`getMetaSizeIncrease`）、`:2409`/`:2492`/`:2509`/`:2526` 等大量 `if (!mIsHeif)` 分叉 |
| item 属性 `hvcC` | `:2644` `if (mIsHeif && mCodecSpecificDataSize < 23) return;` + `:2647-2651` 推入 `FOURCC('h','v','c','C')` |
| item 属性 `ispe` | `:2653-2657` 推入 `ispe`（`width/height`） |
| item 属性 `irot`（旋转，可选） | `:2659-2666`，HEIF 角度是 CCW（`90→3 / 180→2 / 270→1`） |
| item 属性 `colr`(nclx) | `:2597-2604` / `:2699-2706` / `:2734-2741` —— **均带 `flags_camera::camera_heif_gainmap()` 前置条件** |
| item 属性 `pixi` | `:2605-2609` / `:2707-2711` / `:2742-2746` —— **同上带 flag 条件** |
| grid 分片（多 tile + `dimg`/`iref`） | `:2689-2725`（`itemType="grid"`）、`flushItemRefs()` `:2776-2790` |
| Exif item（`cdsc` 引用） | `:2554-2570` `isExifData()`（识别 `Exif\0\0` 或 APP1+`Exif`）、`:2783-2788` `cdsc` |
| gainmap item（`tmap`/`gmap` 前缀） | `:2492-2526` `isGainmapMetaData`/`isGainmapData` —— **同样带 flag 条件** |

**⚠ 两个必须知道的条件（一手，直接影响"能不能白嫖 AOSP"）**：

1. **`colr` / `pixi` 在 AOSP 主线里全部挂在 `flags_camera::camera_heif_gainmap()` 之后**（上表 6 处行号）。
   flag 关闭时，即使 `mIsHeif=true`，写入的属性只有 `hvcC` + `ispe` (+`irot`)
   ⇒ 文件**没有色彩标签**（解码端按默认 sRGB 解释）——正是方案 §2.2 担心的情形。
2. **`pixi` 的位深来源是编码器输出 buffer 的 metadata**，不是 `MediaFormat`：
   `:3883-3895` 从 `buffer->meta_data().findInt32(kKeyColorFormat, &val32)` 取值，
   且**只对 `COLOR_FormatYUV420Flexible/Planar/SemiPlanar` 填 `{8,8,8}`，`default: break;`**
   ⇒ 对 P010（10bit）**什么都不填**（`mBitsPerChannel` 保持空 ⇒ 不写 `pixi`）。
   `colr` 的取值同理来自 buffer metadata 的 `kKeyColorPrimaries/kKeyTransferFunction/kKeyColorMatrix/kKeyColorRange`（`:3858-3881`），
   **不是** `MediaFormat` 的 `color-standard` 等键。

> **本节结论**：`MUXER_OUTPUT_HEIF` 本身**只会**把 `kKeyFileType=3` 塞给同一个 MPEG4Writer。
> 想让它写真 HEIF，**必须让 `addTrack()` 的 MediaFormat `mime` 等于 `image/heic`**；
> 而 `MediaCodec` 的 HEVC 编码器输出格式是 `video/hevc`（探针实测回显），二者天然冲突
> ⇒ **这就是"为什么只有 androidX `HeifWriter` 能出 HEIF"的全部原因**，见 §2。

---

## ② Q2 · androidx `HeifWriter` 到底能不能 10bit

### 2.1 答案是：**能，但公开 API 把开关焊死在 false**

| 层 | 事实 | 证据 |
|---|---|---|
| `HeifWriter`（public） | 构造函数把 `highBitDepthEnabled` **硬编码 false** | `HeifWriter.java:410` `handler, /* highBitDepthEnabled */ false);` |
| `HeifEncoder`（internal） | 构造与收尾两次硬编码 `useBitDepth10=false` | `HeifEncoder.java:73` `/* useBitDepth10 */ false);`、`:75` `finishSettingUpEncoder(/* useBitDepth10 */ false);` |
| `HeifWriter.Builder` | 无任何色彩/位深/ICC setter（方案 §2.1 已记） | — |
| **`EncoderBase`（internal）** | **10bit 能力完整存在**，全部由 `useBitDepth10` 一个布尔驱动 | `EncoderBase.java:94` `private final boolean mUseBitDepth10;`、`:361` 赋值 |
| 输入色彩格式 | 10bit ⇒ **P010**；surface 模式另说 | `EncoderBase.java:376-378` `int colorFormat = useSurfaceInternally ? COLOR_FormatSurface : (useBitDepth10 ? COLOR_FormatYUVP010 : COLOR_FormatYUV420Flexible);` |
| 色彩键 | 10bit 时会**自动**写 `BT2020` + `RANGE_FULL` **并默认 ST2084(PQ)** | `EncoderBase.java:440-463`（`:446` `COLOR_STANDARD_BT2020`、`:448` `COLOR_RANGE_FULL`、`:450` `COLOR_TRANSFER_ST2084`；非 10bit 时才走 `BT601_NTSC`+`SDR_VIDEO`） |
| 10bit EGL surface | 有专用 10bit surface 路径 | `EncoderBase.java:536` `new EglWindowSurface(mEncoderSurface, useBitDepth10)` |
| 缓冲尺寸校验 | 10bit 期望 `w*h*3` 字节 | `EncoderBase.java:563` / `:676` |
| 位图/图像校验 | 10bit 明确要求 `ImageFormat.YCBCR_P010` | `EncoderBase.java:671-672` `if ((mUseBitDepth10 && format != ImageFormat.YCBCR_P010) \|\| (!mUseBitDepth10 && format != ImageFormat.YUV_420_888))` ⇒ 不匹配就报错 |
| P010 → EGL 上传 | 有显式 P010 分支，注释 `Assume pixel format is P010`，并按 `2 bytes per pixel` 调整 Y 平面偏移 | `EncoderBase.java:846-859` |

### 2.2 直接回答"`INPUT_MODE_SURFACE` 能不能接 10bit、是否仍强制 8bit"

- **`INPUT_MODE_SURFACE`（公开 API）仍强制 8bit**：走的是
  `HeifWriter.java:410 false → HeifEncoder.java:73/75 false → EncoderBase`，
  即 `useSurfaceInternally=true` 时 `colorFormat = COLOR_FormatSurface`（8bit 表面），
  **并且** `useBitDepth10=false` 会把色彩键写成 `BT601_NTSC + SDR_VIDEO`（`EncoderBase.java:453-463`）。
  ⇒ 公开 API 下无论哪种输入模式，**都不可能有 10bit**，`INPUT_MODE_SURFACE` 也不例外。
- **但内部实现已经具备 10bit 全链路**（P010 缓冲 + 10bit EGL surface + BT2020/PQ 标签 + `YCBCR_P010` 校验）。
  ⇒ "androidx 不能 10bit"这句话的**准确表述**是：
  **androidx 的 10bit 实现存在但未通过任何公开 API 暴露**，不是"没有实现"。

### 2.3 但 `HeifWriter` 给了我们最关键的**一行技巧**（本卡最有价值的发现）

`HeifEncoder.java:82-105`（`HevcEncoderCallback.onOutputFormatChanged`）：

```java
if (!MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC.equals(format.getString(MediaFormat.KEY_MIME))) {
    format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC); // "image/heic"
    format.setInteger(MediaFormat.KEY_WIDTH,  mWidth);
    format.setInteger(MediaFormat.KEY_HEIGHT, mHeight);
    if (mUseGrid) { format.setInteger(KEY_TILE_WIDTH, ...); /* tile/grid 四键 */ }
}
mCallback.onOutputFormatChanged(HeifEncoder.this, format);   // ← 改完再交给上层
```

再看 `HeifWriter.java:430-431`：

```java
mMuxer = (path != null) ? new MediaMuxer(path, MUXER_OUTPUT_HEIF)
                        : new MediaMuxer(fd,   MUXER_OUTPUT_HEIF);
```

⇒ **androidx 自己用的也是 `MediaMuxer + MUXER_OUTPUT_HEIF`**（与我们的探针完全相同），
它比我们多的**只有**：在把编码器 `outputFormat` 交给 `addTrack()` 之前，
**把 `KEY_MIME` 改写成 `image/heic`，并补齐 `KEY_WIDTH/KEY_HEIGHT`**。

**这一行就是 §1 那条因果链的钥匙**：`MPEG4Writer.cpp:2314` 读到的 mime 从 `video/hevc`
变成 `image/heic` ⇒ `mIsHeic=true` ⇒ `mIsHeif=true` ⇒ `mHasFileLevelMeta=true` ⇒ 写真 HEIF。

---

## ③ Q3 · HEIF 静态图最小 box 规格（自研写入器口径）

> **本章实测状态（一手）**：本章规格**不是纸面推导** —— 架构师已按此布局手拼容器，
> 被 **macOS 原生解码器强制解码成功**（`sips -s format png` → 12026 B PNG，
> PIL 读出 1024×256 真实像素、非全黑），原型脚本 `tools/heif10-container-ref.py`（147 行）可复跑。
> **下文每个 `:NN` 引用都是该原型里已跑通的字节布局**，实现者按行照抄即可。
>
> 本章另有两个用途：(a) 兜底自研的完整规格；(b) **验收解析器**的规格 ——
> 按同一套字段去核对产出（§5.7 单测 2 即用项目既有的 `parseBoxes()` 做回环）。

### 3.1 最小 box 树（单 item、无 grid、无缩略图）

```
ftyp                        (FileTypeBox)
meta                        (FullBox, version=0, flags=0)   ← 文件级，HEIF 的标志
├── hdlr                    (version=0, flags=0, handler_type='pict')
├── pitm                    (version=0) → item_ID = 1
├── iinf                    (version=0) entry_count = 1
│   └── infe                (version=2, flags=0) item_ID=1, item_protection_index=0,
│                           item_type='hvc1', item_name=""
├── iloc                    (version=0 建议；见 3.4) item_count=1
│   └── (item 1) base_offset + 1 extent{offset,length}  → 指向 mdat 内的 HEVC 码流
└── iprp                    (ItemPropertiesBox)
    ├── ipco                (ItemPropertyContainerBox，属性池，按 1-based 索引)
    │   ├── [1] hvcC        ← 直接复用编码器 csd-0
    │   ├── [2] ispe        width/height
    │   ├── [3] colr        'nclx' 四元组
    │   ├── [4] pixi        num_channels=3, bits={10,10,10}
    │   └── [5] irot        (可选；有旋转时)
    └── ipma                (ItemPropertyAssociationBox) item 1 → {1,2,3,4[,5]} essential 位
mdat                        ← HEVC 单帧码流（1 个 sample = 1 张静态图）
```

**必需 / 可选分级**：

| box | 必需性 | 依据 |
|---|---|---|
| `ftyp` | **必需**，且 major_brand 必须是 `heic`（或 `mif1`），兼容 brand 含 `mif1`/`heic` | AOSP `MPEG4Writer.cpp:1591-1610`（`writeFourcc("heic")` / `"mif1"`,`"heic"`）；缺 `heic` brand 的解码端会拒绝 |
| `meta` + `hdlr` + `pitm` + `iinf/infe` + `iloc` | **必需**（缺任一项不是合法 HEIF） | libheif `box.cc:500-528` 逐个注册 `pitm`/`iloc`/`iinf`/`infe`/`ipco`/`ipma`/`ispe`，并有 `Box_pitm::parse` `:1557`、`Box_iloc::parse` `:1615` 的强校验 |
| `iprp/ipco` + `hvcC` | **必需**（解码器要靠它建参） | `MPEG4Writer.cpp:2644-2651`（`hvcC` 长度 < 23 直接 return） |
| `ispe` | **必需**（尺寸自描述） | `MPEG4Writer.cpp:2653-2657` |
| `colr`(nclx) | **强烈建议**：缺省 ⇒ 解码端按 sRGB 猜（方案 §2.2 已记）；但 AOSP 只在 `camera_heif_gainmap` flag 打开时写 | `MPEG4Writer.cpp:2597`/`:2699`/`:2734`（三处均带 flag 前置） |
| `pixi` | **可选**（规范允许省；但它是位深的自描述，10bit 场景下建议写） | `MPEG4Writer.cpp:2605`/`:2707`/`:2742`（同样带 flag 前置） |
| `irot` | 可选，仅旋转非 0 时 | `MPEG4Writer.cpp:2659-2666`（HEIF 角度 CCW：90→3 / 180→2 / 270→1） |
| `mdat` | **必需**（码流载体） | — |
| `idat` | **不需要**（仅当 `iloc.construction_method=1` 时才用；我们用 method 0 直指 `mdat`） | libheif `box.cc:2286-2310`（`idat` 仅在 `construction_method == 1` 时写） |

### 3.2 字段来源：能不能直接复用编码器给的东西

| 目标 box | 字段 | 来源 | 结论 |
|---|---|---|---|
| `hvcC` | 整块 | **编码器 `MediaFormat` 的 `csd-0`（ByteBuffer）原样拷贝** | ✅ **可 100% 复用**。探针实测 `csd-0` = 80 B > 23 B 门槛（`MPEG4Writer.cpp:2644`）。⚠ **不要自己解析再重编**：HEVCDecoderConfigurationRecord 里有 `numOfArrays`/NAL 长度字段，重编极易错；而且探针报告的探针已经能正确解析出 `general_profile_idc`/`bitDepthLumaMinus8`，说明它本来就是标准格式 |
| `ispe` | `width`,`height` | 编码器 `MediaFormat` 的 `KEY_WIDTH`/`KEY_HEIGHT` | ✅ 直接取。⚠ 注意 HEVC 编码器可能因对齐把尺寸**放大**（如 1024×256 → 1024×256 没问题，但 4032×3024 可能变 4032×3040）⇒ 必须用 `getOutputFormat()` 回读的尺寸，**不要用请求值** |
| `colr` | `colour_primaries`,`transfer_characteristics`,`matrix_coefficients`,`full_range_flag` | **必须显式做 Android→CICP 映射**（见 3.3） | ⚠ **不能直接抄 Android 常量值** |
| `pixi` | `num_channels=3`, `bits_per_channel={10,10,10}` | 由我们已知的位深决定 | ✅ 10bit P010 ⇒ `{10,10,10}`；8bit ⇒ `{8,8,8}`（AOSP `MPEG4Writer.cpp:3885-3891` 对 8bit YUV420 就是填 `{8,8,8}`） |
| `iloc` | `base_offset` + `extent_offset` + `extent_length` | 由写入顺序**自算**（见 3.4） | ✅ 见下 |
| `pitm` | `item_ID` | 我们自己分配（单图 = 1） | ✅ |
| `infe` | `item_type` | `'hvc1'`（HEVC 主图） | ✅ 与 AOSP 一致（`MPEG4Writer.cpp:2672`/`:2749` `itemType = mIsAvif ? "av01" : "hvc1"`） |
| `hdlr` | `handler_type` | `'pict'` | ✅ 图像文件约定 |

### 3.3 `colr/nclx` 的 CICP 值：**必须显式映射，不能抄 Android 常量**（本卡最容易错的一处）

AOSP 自己就是**转换**的，不是直传 —— `MPEG4Writer.cpp:2601-2603` 调的是：

```cpp
ColorUtils::convertCodecColorAspectsToIsoAspects(
        mColorAspects, &property.colorPrimaries, &property.colorTransfer,
        &property.colorMatrix, &property.colorRange);
```

且 `mColorAspects` 的四个分量来自**编码器输出 buffer 的 metadata**
（`MPEG4Writer.cpp:3858-3881`：`kKeyColorPrimaries`/`kKeyTransferFunction`/`kKeyColorMatrix`/`kKeyColorRange`），
**不是** `MediaFormat` 的 `color-standard` 等键 —— 但编码器会把它们桥接过去（见下方实测）。

**映射表（本卡的口径）**：

| 语义 | Android 常量（`MediaFormat`/`MediaCodecInfo`） | CICP（写进 `nclx`） | 依据 |
|---|---|---|---|
| BT.2020 原色 | `COLOR_STANDARD_BT2020` = **6** | `colour_primaries` = **9** | 探针真机实测（见下） |
| 线性传递 | `COLOR_TRANSFER_LINEAR` = **1** | `transfer_characteristics` = **8** | 同上 |
| NCL 矩阵（BT.2020 非恒定亮度） | （无 Android 键；由 standard 推导） | `matrix_coefficients` = **9** | 同上 |
| 全范围 | `COLOR_RANGE_FULL` = **1** | `full_range_flag` = **1** | 同上 |

> **一手实测（比源码更硬）**：A0 探针在 X300 上请求 `BT2020(6)+LINEAR(1)+FULL(1)`，
> 产出文件的 `colr/nclx` 解析结果为 **`primaries=9 / transfer=8 / matrix=9 / full=1`**
> —— **四项逐一对上**。这同时证明了两件事：
> ① Android→CICP 确实存在转换（6→9、1→8，不是直传）；
> ② **编码器确实把 `MediaFormat` 的 `color-*` 键传到了输出 buffer 的 `kKeyColor*` metadata**，
> 于是 **AOSP 的 HEIF 分支只要被激活（mime=`image/heic`）就有机会写出正确 `colr`**（受 §1.5 的 flag 条件约束）。

**自研写入器要写死的映射函数**（伪代码，值域按方案 §6 的待拍板项可换）：

```
func androidToCicp(std: Int, transfer: Int, range: Int) -> (primaries, transfer8, matrix, full):
    primaries = when std:  6 -> 9   // BT2020
                           1 -> 1   // BT709
                           2 -> 5   // BT601_PAL  -> CICP 5 (BT.470BG)
                           4 -> 6   // BT601_NTSC -> CICP 6 (SMPTE 170M)
                           else -> 2 // unspecified
    transfer8 = when transfer: 1 -> 8   // LINEAR    -> CICP 8 (linear)
                               3 -> 13  // SDR_VIDEO -> CICP 13 (IEC 61966-2-1 sRGB)
                               6 -> 16  // ST2084(PQ) -> CICP 16
                               7 -> 18  // HLG        -> CICP 18
                               else -> 2
    matrix    = when std: 6 -> 9        // BT2020 NCL
                          1 -> 1        // BT709
                          else -> 2
    full      = (range == FULL) ? 1 : 0
```

⚠ **`matrix_coefficients` 没有对应的 Android 公开键**：`MediaFormat` 只有 `color-standard`，
所以它是**由 standard 推导**的（AOSP 的 `mColorAspects.mMatrixCoeffs` 同样由平台侧推导）。
⇒ 我们自研时按 standard 推导，与 AOSP 行为一致。

### 3.4 `iloc` 的偏移与长度怎么算（唯一有"顺序陷阱"的地方）

> **落地口径以 §5.3 为准**（那是原型已跑通的写法：`base_offset_size=0` + **绝对文件偏移**）。
> 本小节只说明字段语义与"为什么必须两遍构造"。

**结构（version 0 口径）**：

```
iloc (FullBox) {
  version=0, flags=0
  offset_size      : 4 bits   // 原型口径 = 4
  length_size      : 4 bits   // 原型口径 = 4
  base_offset_size : 4 bits   // 原型口径 = 0（⇒ 不写 base_offset 字段）
  reserved         : 4 bits   // = 0
  item_count       : 16 bits  // version 0 = uint16（version 2 才是 uint32）
  per item {
    item_ID                : 16 bits
    data_reference_index   : 16 bits  // = 0（数据在本文件内）
    base_offset            : base_offset_size 字节   // size=0 时该字段不存在
    extent_count           : 16 bits  // = 1
    per extent {
      extent_offset : offset_size 字节   // 相对 base_offset；base_offset=0 ⇒ 即绝对文件偏移
      extent_length : length_size 字节   // = HEVC 帧字节数
    }
  }
}
```

**字段宽度的规范依据（libheif 一手）** —— `box.cc:2182-2280` `Box_iloc::derive_box_version()`：

```cpp
m_offset_size = 4;   m_length_size = 4;   m_index_size = 0;
uint64_t maximum_meta_box_size_guess = 0x10000000; // 256 MB
if (total_data_size + maximum_meta_box_size_guess > 0xFFFFFFFF) m_base_offset_size = 8;
else                                                           m_base_offset_size = 4;
// extent.offset within an item equals the cumulative length of all preceding extents
// (they are written sequentially). base_offset absorbs the absolute file position.
```

⇒ 规范允许 `offset_size`/`length_size` 取 4，**且 `base_offset` 的语义就是"吸收绝对文件位置"**
⇒ **`base_offset_size=0` + 绝对 `extent_offset` 是等价且合法的**（原型实测被 macOS 接受）。
**单张图 < 4 GB，`offset_size=length_size=4` 永远够用。**

**两步值**：
```
extent_offset = mdat 内数据起始的绝对文件偏移
              = len(ftyp) + len(meta) + 8      // +8 = mdat 的 size(4) + type(4)
extent_length = HEVC 帧字节数
```

**顺序陷阱（本卡最容易翻车的一点）**：`meta` 里含 `iloc`，而 `iloc` 需要知道 `mdat` 的位置，
但 `mdat` 在 `meta` 之后 ⇒ **必须两遍构造**：
先以 `offset=0` 量出 `meta` 长度 → 算出真实偏移 → 重建 `meta` → 断言两次长度相等。
**具体算法与断言见 §5.3**（原型的 `:102-106`，已验证）。
⚠ 不要用"先写占位再 `seek` 回填"的流式写法：虽然也能work，但会引入"回填长度不一致"的隐性 bug；
**两遍 + `check()` 长度相等**才把这类 bug 变成崩溃而不是静默损坏。
（AOSP 走的是第三条路：预估算 meta 大小，`MPEG4Writer.cpp:4522 getMetaSizeIncrease`。
**我们不必学它** —— 两遍更简单。）

**`pitm`/`ipma` 的索引语义**：`ipma` 里的属性索引是 **`ipco` 内的 1-based 顺序号**（**不是** item_ID）；
`essential` 位对 `hvcC` 建议置 1（解码器不认识就必须放弃 —— 这是规范意图）。
AOSP 用 `mOwner->addProperty_l(property)` 返回的索引做关联，我们照做即可。
⚠ 细节与陷阱见 §5.4（**`0x81` 不等于索引 129**）。

### 3.5 有没有现成实现可移植？—— libheif 评估（一手）

| 维度 | 事实 | 证据 |
|---|---|---|
| 许可证 | **LGPL-3.0-or-later**（`nclx.cc` 文件头逐字："libheif is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License ... either version 3 of the License, or (at your option) any later version"） | `libheif/nclx.cc:1-16` |
| box 层完整度 | 完整：`box.cc` 达 **145,095 B**，注册并实现 `pitm`/`iloc`/`iinf`/`infe`/`ipco`/`ipma`/`ispe`/`hvcC`/`colr`/`pixi` 全部读写 | `box.cc:500-528`, `:560`, `:616`, `:620` |
| Android NDK 可编译性 | **理论可行但体量大**：需要整条 `heif_context`/`heif_image`/encoder plugin 栈；且**它自带的 HEVC 编码器是 `libx265`（GPL 或 commercial）**，与我们"复用系统 `MediaCodec` 硬编"的目标冲突 | 推断（未逐文件审计，标 **[推断]**） |
| LGPL 对本项目的影响 | LGPL-3 的**动态链接**（`.so`）通常可接受，但：① 需提供 relink 可能（Android 上实践争议大）；② **GPL 的 x265 不能链**；③ 本项目是自有 App，引入 LGPL 三方库会带来合规与体积双重成本 | **[推断]**，需法务/用户拍板 |
| 纯"借鉴算法"移植 | ✅ **推荐口径**：只把 `Box_iloc::derive_box_version()`（字段宽度决策）与 `box.cc` 的序列化顺序当**规范参考**，**不引入代码** ⇒ 无许可证问题 | 本卡即采此口径 |

> **本节结论**：**不建议移植 libheif**。它的价值是"规格字典"。
> 一个"单 item、无 grid、无缩略图、无 Exif"的 HEIF 写入器只有
> `ftyp(≈24B) + meta(≈200~260B) + mdat`，**核心逻辑 < 400 行 Kotlin**（详见 §5 骨架），
> 比引入 LGPL + x265 的整条 C++ 栈便宜一个数量级。

---

## ④ Q4 · 10bit 输入数据格式（P010 对齐与布局）

> **状态：本卡未取得 P010 位对齐的一手规范原文**（见 §6.2-3）。
> 下面**区分**「已由实测支持」与「待实测」两段，实现者按后者做一次真机回归。

### 4.1 已由真机实测支持的部分（一手）

探针在 X300 上通过 `MediaCodec.getInputImage()` 读回的 `Image` 报告：

| 字段 | 实测值 | 含义 |
|---|---|---|
| `yRow`（Y 平面 row stride） | **2048** | 宽度 1024 ⇒ **每样本 2 字节**（1024×2 = 2048） |
| `yPx`（Y 像素步长） | **2** | 每样本 2 字节 |
| `uPx` / `vPx`（色度像素步长） | **4** | U/V **交错**（半平面内每 2 个色度样本一组），符合 P010/P210 的 4:2:0 交错色度布局 |

⇒ **结论（实测支撑）**：`COLOR_FormatYUVP010`(=54) 的缓冲是 **16 bit/样本**、
Y 平面独立、**Cb/Cr 交错**（一个平面内 `Cb0 Cr0 Cb1 Cr1 …`）。
这正是 `uPx = vPx = 4` 的原因（步长 4 字节 = 2 个 16bit 样本）。

**`getInputImage()` 是安全路径**：它用编码器自报的 stride / pixelStride，
因此**不受"位对齐假设"影响**（无论如何都按同一约定被解释）。
⇒ **实现建议：优先走 `getInputImage()`**，手填 stride 只作兜底（探针已按此设计，A0 报告 §⑧-3）。

### 4.2 待实测的部分（**必须真机验证，不许假设**）

| 项 | 现状 | 验证方法 |
|---|---|---|
| **位对齐：MSB-aligned（`v10 << 6`）还是 LSB-aligned（`v10`）** | A0 探针按 **MSB-aligned** 写（`Heif10BitProbe.kt:569`），**未解码回像素验证**（A0 报告 §⑧-1 已自认） | 本轮架构师原型给出**间接**证据：手拼 HEIF 被 `sips` 解出 1024×256 **非全黑**。但这只证明"有数据"，**不证明灰阶正确**（错位也仍非全黑）⇒ 仍需一次灰阶回归（见下） |
| **Y/Cb/Cr 平面顺序（U 在前还是 V 在前）** | 未验证 | 同下 |
| 手填路径的 slice-height / 平面偏移 | 未验证 | 同下 |

**唯一可信的验证方法（建议直接作为期 A 的一个判据）**：
用探针那种"上半天 1024 级灰阶 + 下半 8 饱和色块"图，走完**编码 → 自研容器 → 解码**整链，
**比对解码出的灰阶是否严格线性（第 x 列码值 == x）**：
- 灰阶恰为 `x` 的线性序列 ⇒ MSB-aligned 假设**成立**；
- 灰阶呈 `x*64` 量级或分段错位 ⇒ 是 **LSB-aligned**（或平面顺序错），把 `<<6` 改成直存即可。

这一步能把 §4.2 三项**一次性全测掉**，成本极低。

> **参考（二手，标注 [推断]）**：P010 在业界惯例上以 **MSB-aligned**（有效 10 bit 放 16 bit 的高 10 位、低 6 位为 0）为常见约定。
> 但 **Android `COLOR_FormatYUVP010` 的规范原文本卡未取得**，故不把惯例当结论。
> **给实现者最重要的一条工程建议**：把 `<<6` 写成**单一函数/常量**（如 `fun p010(v10: Int) = v10 shl 6`），
> 一旦灰阶判据证伪，只改一处。

### 4.3 `pixi` 该不该写 —— **建议写**（本卡结论）

| 理由 | 依据 |
|---|---|
| 它是**位深的自描述**；10bit 是本期全部意义所在，不写等于让解码端猜 | — |
| **macOS 原生解码器实测会读它**（写了 `pixi{3,10,10,10}` ⇒ macOS 报 `bitsPerSample = 10`） | `tools/heif10-container-ref.py:90` + 该文件 `:28` 的验证记录 |
| 规范允许省（省了不算非法），但**省掉就失去"10bit"声明**，下游可能按 8bit 解释 | — |
| AOSP 自己也写 `pixi`（只是被 `camera_heif_gainmap` flag 门控） | `MPEG4Writer.cpp:2605`/`:2707`/`:2742` |

**写法**：`pixi` FullBox，payload = `num_channels`(1B) + `bits_per_channel`(num_channels × 1B)
⇒ 10bit 三通道 = **`03 0A 0A 0A`**。
架构师原型 `bytes([3, 10, 10, 10])` 与之逐字一致；libheif `box.cc:2817-2820` 同构
（先写 `m_bits_per_channel.size()`，再逐个写位深）。
⚠ **R9**：别漏掉开头的通道数。



---

## ⑤ 期 A 路线定案 + 给实现者的最小清单

### 5.1 路线定案（一句话）

**路线①「自研 HEIF 静态图写入器」，且不引入 libheif** ——
已由架构师端到端实测打通（macOS 原生解码器强制解码成功），
可行性不再是风险，剩下的只是**把已验证的原型整理成 Kotlin 实现 + 兼容性回归**。

| 路线 | 判定 | 可行性 | 工作量 | 风险 |
|---|---|---|---|---|
| **① 自研 HEIF 写入器** | ✅ **采用** | **已实测打通**（原型 `tools/heif10-container-ref.py`，147 行，`sips` 解出 12026B PNG / PIL 读出 1024×256） | Kotlin 侧 **1~2 个文件 / 约 400~500 行**（含 box writer + `hvcC`/尺寸搬运 + 两遍 `iloc`）+ 单测（按原型字节逐字段断言） | 见 §5.6 兼容性清单 |
| ② 官方 API 白嫖（复用 AOSP HEIF 分支） | ❌ **不采用，但记为备选** | 理论可行：把编码器 `outputFormat` 的 `KEY_MIME` 改写为 `"image/heic"` + 补 `KEY_WIDTH/KEY_HEIGHT`，即可让 `MPEG4Writer` 写 `meta/iinf/iloc/iprp`（`MPEG4Writer.cpp:2314` + `:766-768`） | **比①小**：约 50~100 行（照抄 `HeifEncoder.java:89-100` 那 12 行 + 一个 muxer 封装） | ⚠ **AOSP 主线的 `colr`/`pixi` 全部被 `camera_heif_gainmap()` flag 门控**（§1.5 六处行号）。flag 关闭时拿不到色彩标签；且 vivo 是否开了这个 flag、其 `MPEG4Writer` 是否改过此处**都未验证** ⇒ **产出不可保证**。作为"自研写入器的对照组/复用候选"保留 |
| ③ 放弃 10bit HEIF，改走 Ultra HDR gainmap | 不采用 | — | — | 与用户 2026-10-13「交付位上 10bit HEIF」拍板冲突；且 gainmap 是"8bit base + 增益图"，不是真 10bit 容器 |

**为什么②不取代①**：②的核心不确定性（vendor flag、mime 常量值）**无法通过我们自己的代码消除**；
而①的核心不确定性（容器是否合法）**已经被实测消除**。
按项目"质量 > 成本 / 工期"的大前提，选**确定性高**的那条。

> **`HeifWriter` 线（一句话确认）**：`HeifWriter.java:410` 把 `highBitDepthEnabled` 硬编码 `false`，
> `HeifEncoder.java:73/75` 把 `useBitDepth10` 硬编码 `false` ⇒ **公开 API 是 8bit 专用**
> （虽然内部 `EncoderBase.java:376-463` 的 10bit 链路完整存在）。**只把它当 8bit 回退档。**

### 5.2 实现清单（Kotlin/Java 侧，照抄 `tools/heif10-container-ref.py` 的已验证布局）

**输出顺序（字节级，与原型 `:106` 一致）**：`ftyp | meta | mdat`
（**不需要 `free` box** —— 那只是 `MediaMuxer` 的预留空间，原型 `:29` 已注明。）

**① `ftyp`**（必需，共 24 字节，原型 `:82`）
```
size(4) 'ftyp' | major_brand='heic' | minor_version=0 | compatible: 'mif1' 'heic'
```
⚠ major brand **必须是 `heic`**（不是 `mif1`），否则部分解码端不认 HEVC item。

**② `meta`**（FullBox，version=0 / flags=0）—— 子 box 顺序照原型 `:102`：

| 顺序 | box | 必需 | 字段来源 / 写法 |
|---|---|---|---|
| 1 | `hdlr` | ✅ | v0 flags0；`pre_defined=0` + **`handler_type='pict'`** + `reserved[3]=0` + `name=""`（原型 `:83`，共 33B） |
| 2 | `pitm` | ✅ | v0；`item_ID = 1`（u16） |
| 3 | `iinf` | ✅ | v0；`entry_count=1`(u16) 后紧跟一个 `infe` |
| 4 | └ `infe` | ✅ | **version=2**；`item_ID=1`(u16) + `item_protection_index=0`(u16) + **`item_type='hvc1'`** + `item_name=""`（**可为空**，原型写 `b'\0'`；若要写名字须 **null 结尾**） |
| 5 | `iloc` | ✅ | 见 §5.3（**两遍构造**） |
| 6 | `iprp` | ✅ | 内含 `ipco` + `ipma` |
| 6.1 | └ `ipco` | ✅ | 属性池，**顺序即索引**（1-based）：`[1]` `hvcC` → `[2]` `ispe` → `[3]` `pixi` → `[4]` `colr` |
| 6.2 | └ `ipma` | ✅ | 见 §5.4 |

**`ipco` 内四个属性（顺序务必与 `ipma` 一致）**：

| # | box | 必需 | 写法（照原型 `:88-92`） |
|---|---|---|---|
| 1 | `hvcC` | ✅ | **普通 box（非 FullBox）**，payload = 编码器 `csd-0` **原样搬运**（实测 106B）。⚠ 长度须 ≥ 23B（`MPEG4Writer.cpp:2644` 的规范依据） |
| 2 | `ispe` | ✅ | FullBox v0；`width`(u32) + `height`(u32)。**取 `getOutputFormat()` 回读值**，勿用请求值（编码器可能对齐放大） |
| 3 | `pixi` | 建议✅ | FullBox v0；`03 0A 0A 0A`（3 通道 × 10bit）—— 见 §4.3 |
| 4 | `colr` | ✅ | **普通 box**；`'nclx'` + `colour_primaries`(u16) + `transfer_characteristics`(u16) + `matrix_coefficients`(u16) + **1 字节位域**：`0x80`=full range / `0x00`=limited。⚠ `full_range_flag` 在**最高位** ⇒ 全范围写 `0x80`（原型 `:91-92` 正确） |

**③ `mdat`**（必需，原型 `:106`）
```
size(4) 'mdat' | <HEVC 帧数据原样搬运>
```
⇒ **帧数据格式天然一致**：`hvcC.lengthSizeMinusOne=3` ⇒ mdat 内已是 **4 字节长度前缀的 NAL**
（实测帧 286B），**HEIF item data 直接搬运，无需重打包**。这是本路线最大的省事点。

### 5.3 `iloc` 的两遍构造（**必须**，唯一有顺序依赖的地方）

**采用原型已验证的口径：`offset_size=4 / length_size=4 / base_offset_size=0`，offset 放绝对文件偏移**：

```
iloc (FullBox v0, flags=0)
  [0] offset_size(4bit) | length_size(4bit)   = 0x44
  [1] base_offset_size(4bit) | reserved(4bit) = 0x00
  item_count (u16)          = 1
  item_ID (u16)             = 1
  data_reference_index (u16)= 0
  extent_count (u16)        = 1
  extent_offset (u32)       = <mdat 内数据起始的绝对文件偏移>
  extent_length (u32)       = <帧字节数>
```

**两遍构造（原型 `:102-106` 的做法，照抄）**：
```
1. 先用 offset=0 构造一次 meta（meta_empty），量出它的长度 L。
2. mdatDataOffset = len(ftyp) + L + 8      // +8 = mdat 的 size(4) + type(4)
3. 用 mdatDataOffset 重新构造 meta，断言 len(meta) == L。
4. 输出 ftyp + meta + mdat
```
**为什么必须断言长度相等**：`iloc` 的 offset/length 是**定长 u32**，回填**不改变 meta 长度**；
一旦有人把字段改成变长编码（如 `offset_size=8`、或 `version=1/2`），第 3 步断言会立刻炸
——**这个 `assert` 是原型里最重要的防错设施，Kotlin 版必须保留**（换成 `check()`）。

> 与 AOSP 的差异（不必学）：AOSP 走"预估算 meta 大小再写"（`MPEG4Writer.cpp:4522 getMetaSizeIncrease`）以避免回填。
> **两遍 + 断言更简单且不易错**，是本卡推荐口径。
> 若实现者偏好 `base_offset` 方案（`base_offset=mdat 头位置`、`extent_offset=0`），
> 须把 `[1]` 改成 `0x40`（`base_offset_size=4`）并多写一个 u32 —— 两者等价，但**只选一种，别混**。

### 5.4 `ipma` 的 1-based 关联（**最易错的静默错误点**）

```
ipma (FullBox v0, flags=0)
  entry_count (u32)      = 1
  item_ID (u16)          = 1
  association_count (u8) = 4
  per association (u8):
      索引 <= 127: [essential(1bit)=0][index(7bit)]
      索引 >= 128: 2 字节形式（本卡不需要）
```

**原型的写法（`:94`）**：`bytes([4, 0x81, 0x82, 0x83, 0x84])`
⇒ `0x81` = `0x80 | 0x01`，即 **"essential 标志置位 + 属性索引 1"**。
⚠ **字节布局必须是：高 1 位是 essential，低 7 位是 index**。
`0x81` **不是"索引 129"**，而是"essential=1, index=1"——这是本规格最容易读错的一处。
原型把 **4 个属性全部标 essential**（含 `ispe`/`pixi`/`colr`），这是**保守做法**
（解码器不认识任何一项就应放弃，而不是误解释）。
若想让 `colr`/`pixi` 变为"可忽略"，把对应字节的 `0x80` 去掉（`0x83`→`0x03`）；
但**`hvcC` 建议保持 essential**（没有它无法解码）。

**关联顺序必须与 `ipco` 内属性顺序一致**：`{1=hvcC, 2=ispe, 3=pixi, 4=colr}`。
不一致的后果是"尺寸被当位深"这类**静默错误**，比崩溃更难查（⇒ R10，务必单测）。

### 5.5 单帧 → 多帧/burst 的扩展（期 A 之外的预留，规格级）

当前规格是**单 item**（`iinf.entry_count=1`、`iloc.item_count=1`、`pitm` → 1）。若将来做 burst / 多图：

- **`iinf`**：`entry_count = N`，每个 `infe`(v2) 一个 `item_ID`（建议 1..N；**ID 只需唯一且 ≥1，不必连续**）。
- **`iloc`**：`item_count = N`，每 item 一条记录（`item_ID` + `base_offset` + `extent_count` + extents）。
- **`pitm`**：指向**主图**的 `item_ID`（HEIF 允许主图不是 1）。
- **`ipco` 可共享（HEIF 的体积优势正来自这里）**：属性池是**文件级**的，
  同尺寸同参数的 burst 图**只需一套 `hvcC`/`ispe`/`pixi`/`colr`**，每个 item 在 `ipma` 里加一条 entry 即可。
- ⚠ **不同尺寸的图必须各自一组 `ispe`**（在 `ipco` 里增 `[5] ispe`，由对应 item 的 `ipma` 指向它）。
  ⇒ 一旦多尺寸，"属性顺序 = 索引"的约束就从"全局固定"变成"按 item 选择"，是最易出错的扩展点。
- **本卡不建议**在期 A 做多帧：单帧已足够交付，多帧引入 `iinf`/`iloc`/`ipma` **三处同步**错误面。

### 5.6 兼容性风险清单（**本卡要求实现者重点评估的部分**）

| # | 风险 | 严重度 | 现状 / 证据 | 建议对策 |
|---|---|---|---|---|
| **R1** | **`transfer_characteristics=8`（线性）下游不认** ⇒ 相册/微信显示发灰或过亮 | **高** | 架构师原型已被 **macOS 原生解码器**正确解出（说明 Apple 认 CICP）；**Android 相册 / 微信 / Windows 未验证**（A0 报告 §⑧-9 判据 F 仍未做） | ①保留 JPEG 回退（项目红线）；②按方案 §6 待拍板项，考虑 **PQ(16) / HLG(18) 或"线性 + SDR 备份"双出**；③判据 F 逐端人工看一次 |
| **R2** | **`pixi` 被某解码器不认**（严格实现可能因未知属性报错） | 中 | 已实测 **macOS 认**（读到位深 10）；Android 侧未验 | 把 `pixi` 的 essential 位**置 0**（`0x83`→`0x03`）即可让不认识它的解码器忽略；但会失去位深声明 ⇒ **建议先按原型（essential=1）跑通，某端报错再降级** |
| **R3** | `iloc` **绝对偏移 vs `base_offset`** 的兼容性 | 低 | 原型用 `base_offset_size=0` + 绝对 offset，macOS 接受；规范两种都合法 | 保持不变；实现者若中途改口径，**两遍断言的 `check()` 会第一时间抓到** |
| **R4** | 是否建议写 **`irot`/`imir`** | 中 | AOSP 只在旋转非 0 时写 `irot`（`MPEG4Writer.cpp:2659-2666`，**HEIF 角度是 CCW**：90→3 / 180→2 / 270→1） | **有旋转才写 `irot`**（加进 `ipco` 并**同步更新 `ipma` 的 1-based 索引与 essential 位**）。**`imir` 本期不需要**（除非做镜像自拍）。⚠ 旋转的 **CW/CCW 方向务必真机验证**（AOSP 明确注释"framework 角度是 CW、HEIF 是 CCW"，**有 π/2 反向风险**） |
| **R5** | **`mdat` 超过 4GB** | 极低 | 单张 10bit HEVC 静态图 2~15MB | 无需处理；若真要，用 64-bit `largesize`（size 字段写 1 + 后跟 u64），但那把 `mdat` 头从 8B 变 16B ⇒ **`mdatDataOffset` 的 `+8` 必须同步改** |
| **R6** | **`hvcC` 长度 < 23B** ⇒ 容器非法 | 低 | 实测 106B，远超门槛 | 编码后 `check(csd0.size >= 23)`，不满足则**回落 JPEG** 并记日志 |
| **R7** | **`ispe` 用错尺寸**（用请求值而非回读值） | 中 | 编码器可能对齐放大尺寸 | 只用 `getOutputFormat()` 的 `KEY_WIDTH`/`KEY_HEIGHT` |
| **R8** | **`colr` 第 4 字节写成 `0x01` 而非 `0x80`** | 中 | 全范围标志在**最高位** | 单测按字节断言 `0x80`（原型 `:92` 口径） |
| **R9** | **`pixi` 写成 `[10,10,10]`、漏了通道数** | 中 | payload 是 **`num_channels` + N 个位深** = `03 0A 0A 0A` 共 4B | 单测断言 4 字节 |
| **R10** | **`ipma` 索引与 `ipco` 顺序不一致** | **高**（静默错） | — | 单测：解析自己产出的 `ipma`，反查 `ipco` 第 N 个属性的 type，断言与预期序列一致（**最有价值的一条单测**） |

### 5.7 建议的单测 / 回归（把风险变成可自动化判据）

本路线**最大化利用了"容器可 100% 自动化"**（方案 §4.5 判据 A）：

1. **字节级 box 断言**：按原型布局构造最小 HEIF，逐字段断言
   （`ftyp` brand / `iloc` 的 `0x44` 与偏移 / `pixi` 4 字节 / `colr` 的 `0x80` / `ipma` 的 `0x81..0x84`）。
2. **自解析回环**：用项目既有的 `Heif10BitProbe.parseBoxes()`（**已是纯函数 + 已有 4 条单测**，
   A0 报告 §④:603）解析自己产出的文件，断言 `meta` 存在、`colr` = 9/8/9/1、`hvcC.bitDepthLumaMinus8 = 2`。
3. **长度不变断言**：`iloc` 回填前后 `meta` 长度相等（§5.3）。
4. **真机灰阶回归**（§4.2）：一次跑通同时验证 P010 对齐与整链正确性。
5. **PC 端外部复核**：`sips -s format png`（macOS，已成功）/ `heif-info`（libheif 工具）/ `exiftool`。

---

## ⑥ 证据分级与未验证项

### 6.1 证据分级

| 级别 | 内容 |
|---|---|
| **一手 · 源码** | AOSP `frameworks/av` main：`MPEG4Writer.cpp`（行号见 §1）、`MediaMuxer.cpp:47-93`、`MediaMuxer.java:268`/`:698-740`、`MediaFormat.java:301`/`:1508`/`:1539`/`:1570`、`android_media_MediaMuxer.cpp:46-60`；androidx `heifwriter` main：`HeifWriter.java:410`/`:430-431`、`HeifEncoder.java:73/75/89-100`、`EncoderBase.java:94/361/376-378/440-463/536/563/671-672/846-859`；libheif main：`box.cc:500-528`/`:560`/`:616`/`:620`/`:2182-2280`/`:2284-2348`/`:2807-2825`、`nclx.cc:1-16`（LGPL 头） |
| **一手 · SDK** | `javap -constants` on `android-36/android.jar`：`MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC = "image/vnd.android.heic"`、`MIMETYPE_IMAGE_AVIF = "image/avif"` |
| **一手 · 真机实测（架构师本轮）** | `hvcC`=106B / `profile_idc=2` / `bitDepthLumaMinus8=2`；`colr/nclx` = 9/8/9/1；帧 286B 为 4 字节长度前缀 NAL；手拼 HEIF → macOS `sips` 解出 12026B PNG、PIL 读出 1024×256 真实像素（非全黑）；原型脚本 `tools/heif10-container-ref.py`（147 行，可复跑） |
| **一手 · 真机实测（A0 探针）** | 顶层 box = `ftyp,moov,free,mdat`；无 `meta`/`iinf`/`iloc`/`iprp`；`colr`/`hvcC` 各命中 1 次（在 `moov` 视频轨 `stsd` 下）；P010 `yRow=2048 / yPx=2 / uPx=4 / vPx=4` |
| **推断（已标注）** | libheif 的 NDK 可编译性与体量、LGPL-3 对本项目的合规影响（§3.5）；P010 MSB-aligned 的"业界惯例"（§4.2） |

### 6.2 本卡未验证项（**诚实清单**）

1. **`MEDIA_MIMETYPE_IMAGE_ANDROID_HEIC` 在 `frameworks/av` 侧的字符串值本卡未取到**
   （`MediaDefs.h` 的最终路径多次探测返回空，联网预算已用尽）。
   已知 **Java 侧** `MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC = "image/vnd.android.heic"`（jar 实测），
   而 `MPEG4Writer.cpp:2314` 比较的是**同名的 native 常量**。
   **这与"必须让 muxer 收到 `image/heic`"并不矛盾**（两边可能各自独立定义），
   但**路线②若实施，请显式写字符串 `"image/heic"` 并真机回读验证 `meta` 是否存在**，不要依赖该常量。
   ⚠ 这条也是**路线②不被推荐**的一个具体理由。
2. **`colorconversion/ColorUtils::convertCodecColorAspectsToIsoAspects` 的源码原文未取到**
   （`ColorUtils.h`/`.cpp` 在 av 树中的最终路径未定位；仅取得**调用点** `MPEG4Writer.cpp:2601-2603`）。
   但 §3.3 的映射表**已被真机实测输出（9/8/9/1）逐项确认**，实现不依赖该源码。
3. **P010 位对齐与平面顺序**（§4.2）——仍待"灰阶回归"闭环。
4. **`camera_heif_gainmap` flag 在 vivo X300 上的实际状态**（决定路线②能否白嫖 `colr`/`pixi`）。
5. **下游解释**（Android 相册 / 微信 / Windows 对 `transfer=8` 与 `pixi` 的反应）——判据 F，人工。
6. **`irot` 的 CW/CCW 方向**（R4）真机未验。

