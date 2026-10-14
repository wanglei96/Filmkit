# FIX2 · 10bit HEIF 交付：csd-0 过强断言修复 + 8bit HEIF 回退（三级回退链）

> 卡号 FIX2（2026-10-14 派卡；同日追加 ①8bit HEIF 回退 ②「HEIF ⇄ 4:4:4」明示与自动关/恢复）
> 版本 **0.11.36（versionCode 385）**　产物 `filmkit-0.11.36-FIX-HEIF-CSD-8BIT.apk`
> 改动面：`app/…/heif/*`（新增 `HevcCsd0`/`Heif8Encoder`/`HeifDelivery`/`HeifJpeg444`，改 `Hevc10Encoder`/
> `HeifDeliverCheck`）、`CameraController.kt`（HEIF 落盘链 + `photo.encode` 字段）、`SettingsScreen.kt`（4:4:4 行）。
> **未碰** `core/engine/`，未动期 B（Rec.2020）。

## 0. 一句话结论

`Hevc10Encoder` 把 hvcC 的 `lengthSizeMinusOne` 硬要求成 `3`，而该字段是 **hvcC 自己声明的**
NAL 长度前缀宽度（ISO/IEC 14496-15 §8.3.3.1，值 0/1/3 ⇒ 1/2/4 字节都合法）⇒ 真机 X300 +
3072×4080 上 10bit 交付被自家断言打死两次；改为「只验是不是一条 hvcC + 原样搬运」后断言消失，
并补齐 **8bit HEIF** 一级 ⇒ 回退链 = `10bit 自研容器 → 8bit HEIF → JPEG`。

## 1. 因果链（真机一手证据，0.11.35 / vivo X300）

```
photo.deliver {"bits":10,"path":"heif","bytes":50135040}
photo.heif_queued {"bytes":50135040,"w":3072,"h":4080}
photo.heif_failed {"stage":"encode","ms":1818,
  "error":"hvcC.lengthSizeMinusOne != 3 ⇒ 帧数据不是 4 字节长度前缀，不能直接搬进 HEIF item data"}
photo.heif_fallback_jpeg {"reason":"encode","w":3072,"h":4080}
photo.encode {"chroma":"444","q":100,"ms":261,"bytes":6412196}   ← 回退成功，照片正常
```

1. 断言位置 `Hevc10Encoder.kt:171`：`check((h[21].toInt() and 3) == 3)`，`h` = `outputFormat` 的 `csd-0`。
2. 触发条件：X300 的量产 HEVC 编码器在 **3072×4080** 上给出的 hvcC，其 `byte21` 低 2 位不是 3。
   H1"真机实测 = 3"的证据来自探针样本（**1024×256 测试图**）—— 分辨率/码流一换就不成立。
3. 后果：**10bit 交付 100% 失败**（两次拍摄同样失败），JPEG 兜住了片子，但 HEIF 档形同虚设，
   且失败原因指向自家断言而非编码器。
4. 偏移读法本身没错（`h[21] & 3` 就是该字段）；错的是**把它当硬门槛**。

## 2. 修法与「为什么原断言是错的」

**标准依据**（ISO/IEC 14496-15 §8.3.3.1 表 1，`HEVCDecoderConfigurationRecord`）：
`byte21 = constantFrameRate(2) | numTemporalLayers(3) | temporalIdNested(1) | lengthSizeMinusOne(2)`；
`lengthSizeMinusOne = 0/1/3`（2 保留）分别表示 NAL 单元长度前缀 **1/2/4 字节**。HEIF 的 `hvc1`
item data 就是这条 record 描述的码流 ⇒ **解码端按该字段解析**，三种宽度都合法 ⇒ 硬要求 `==3`
是"用探针样本代替规范"的过强假设。

| 项 | 修前 | 修后 |
|---|---|---|
| 结构检查 | `check((h[21] and 3) == 3)` | `h.size >= 23 && h[0] == 1`（`configurationVersion`） |
| hvcC | 通过则原样搬 | **同样原样搬**（不改 `lengthSizeMinusOne`） |
| 帧数据 | 原样搬 | **同样原样搬**（不重打包 NAL） |
| 失败消息 | 只有断言文案 | 明确带 `len=… h[0]=0x…`（下一轮一轮定位） |

- **没有退化成"什么都接受"**：`h[0] != 1` / `len < 23` / `h == null` 一律抛 ⇒ 走回退链（红线保持）。
- **诊断设施** `photo.heif_csd`（无条件发）：`len / ver / profile / bitDepthLumaMinus8 /
  bitDepthChromaMinus8 / chromaFormat / lengthSizeMinusOne / arrays / ok / head(前 32 B hex)`。
  `emit` **在结构检查之前**调用，`encode` 的 `finally` 再对"连 outputFormat 都没到"的早期失败补一条
  `len=0` ⇒ 成功/失败两条路径都有这条日志。

## 3. 8bit HEIF 回退（三级回退链）

**路线**：探针第三臂 `muxer-image-heic`（真机实测过）—— 8bit HEVC（`COLOR_FormatYUV420Flexible`）
+ `MediaMuxer(MUXER_OUTPUT_HEIF)` + `outputFormat.KEY_MIME = MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC`
+ 补 W/H。真机实测该路产出**真 HEIF 图片**（`topLevel: ftyp,meta,free,mdat`、`majorBrand: heic`、
`ilocItems:1`、`itemTypes: hvc1`、`bitDepthLumaMinus8=0`）。AOSP `MPEG4Writer.cpp` 的 `mIsHeic`
只在 mime 命中图片 mime 时为真，否则只写 `moov`（brand 落 `mp42` —— 正是"相册不认"老路）。
备选 androidx `HeifWriter` 被否：**要新增 Gradle 依赖**，而首选零新依赖且已有真机证据。

**缺 `colr` 为何对 8bit 档无害（论证）**：产物无 `colr`（AOSP 里被 `camera_heif_gainmap()` flag 门控），
但 8bit 档装的就是 **sRGB 显示域**数据（`toRgba8` 的降位结果）⇒ 下游按"无 colr 时默认 sRGB"
解释**恰好正确**；10bit 档必须显式写 `colr/nclx=1/13/1/1` 才不歧义（那条链真有"线性 vs 显示域"歧义）。
故 8bit 有**独立自检口径**（`HeifDeliverCheck.Spec.EIGHT_BIT`）：硬性要求 `meta` + `majorBrand=heic`
+ `hvc1` item + `iloc` + hvcC 真 8bit，**不**要求 `colr`/`pixi`；且同一份字节在 10bit 口径下必须红。

**顺序与留痕**（`HeifDelivery.run`，纯 JVM 可注入）：`photo.heif_failed` → `photo.heif8_queued`
（`from:"10bit"`）→ `photo.heif8_failed`（`from:"8bit"`）→ 调用方 `photo.heif_fallback_jpeg`
（**带 `from`**）。"抛异常 / 返回空数组"一律归一为失败；8bit 落盘后再自检，不合格 ⇒ 删文件 ⇒ JPEG。
8bit 输入复用**同一次** readback 的 `toRgba8`（不重渲染、不重新 readback），色彩数学复用
`Heif10Pipeline.toP010`（分块，12MP 峰值 ~4 MB）后右移 2 位写 8bit 平面 ⇒ 两档共用同一组 BT.709 系数。
API < 28 时直接给明确错误（`MediaMuxer`/mime 常量需 API 28+），由状态机落 JPEG。

**`photo.encode{chroma:"420",ms:0,bytes:0}` 可疑点（结论：日志字段缺陷，不是没编码）**：
`saveJpeg` 在 `nativeEncodeJpeg444` 返回 null 时由 `outBmp.compress` 兜底，而 `photo.encode` 是在
compress **之前**发的、且只统计 native 分支 ⇒ `bytes=0` 只说明"native 那支没产出"；`ms=0` 说明
native 是**立即**返回 null（快速失败，非超时）。本轮把 compress 提到日志之前并补字段
`encoder`(`native444`/`bitmap420`)、`jpeg444`、`nativeFail`、真实 `bytes`/`ms`。

## 4. 硬判据实测

**① 全量单测全绿：基线 717 → 740（新增 23），failed 0 / skipped 0**
```
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew testDebugUnitTest
classes 71  tests 740  failed 0  skipped 0
```
新增覆盖（任务点名的三条在内）：`HevcCsd0Test`(7) 三档 0/1/3 通过并**逐字节原样**进容器 / 非法
（`h[0]!=1`、`len<23`、null）必失败且消息带 `len` 与 `h[0]` / **失败分支也发 `photo.heif_csd`**；
`HeifDeliveryTest`(7) 三条路径 + 空数组算失败 + 异常文本进日志 + 色度守卫；
`HeifDeliverCheckTest`(+3) 8bit 口径正/负例；`HeifJpeg444Test`(6) 三态与边界。

**② JPEG 零回归（离线引擎链）**
```
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
        --roi 1800,2100,600,1000 --out /tmp/fix2-e0 --engine 0
  FILM film_d5.npy         crc32 = 0x589f8364  ✅（sha256 ce1c9a90…）
  shader (kFsFilm)         crc32 = 0x4fa1ebd3  ✅
… --out /tmp/fix2-e1 --engine 1
  FILM film_d5_sigmoid.npy crc32 = 0x7c2646ef  ✅
```

**③ 守卫全 PASS**
```
python3 tools/check-bitdepth-single-source.py --selftest  → 自检通过（20 个变异体各自报红）RC=0
python3 tools/check-bitdepth-single-source.py             → PASS（两档真源自洽…）RC=0
bash tools/validate-shaders.sh                            → 全部 shader 校验通过（含引擎 1 sigmoid）RC=0
python3 tools/check-diag-fields.py                        → 全部通过（68 项行号/前置调用核对）RC=0
python3 tools/check-filmparams-contract.py                → PASS（kCount=99 四处一致）RC=0
```

**④ 变异检验**：4 处全部让**相应单测变红**，还原后 heif 全组复跑 BUILD SUCCESSFUL（见 §5）。

## 5. 变异检验（`bash /tmp/fix2-mutation.sh`：注入 → 跑 → 还原，逐条）

| # | 变异 | 预期红的测试 | 实测 |
|---|---|---|---|
| ① | `isConfigurationRecord` 改回 `(h[21] and 3) == 3` | `HevcCsd0Test` 三档 | 红 ✅ |
| ② | 失败分支不再发 `photo.heif_csd`（emit 挪到 check 之后） | `HevcCsd0Test` 失败分支日志 | 红 ✅ |
| ③ | `HeifChroma.honorsJpeg444` 改成 `= jpeg444` | `HeifDeliveryTest` 色度守卫 | 红 ✅ |
| ④ | `HeifJpeg444.onJpeg` 恢复改成写死默认值 | `HeifJpeg444Test` 精确恢复 | 红 ✅ |

原始输出（gradle rc=1 = 测试红，不是编译错）：
```
变异① → HevcCsd0Test > lengthSizeMinusOne 三档 0 1 3 都通过结构检查并产出容器 FAILED
         HevcCsd0Test > configurationVersion 不等于 1 必须失败 FAILED
         HevcCsd0Test > 失败分支无条件发 photo_heif_csd FAILED          BUILD FAILED
变异② → HevcCsd0Test > 失败分支无条件发 photo_heif_csd FAILED             BUILD FAILED
变异③ → HeifDeliveryTest > HEIF 色度恒 4 2 0 且不受 jpeg_444 影响 FAILED   BUILD FAILED
变异④ → HeifJpeg444Test > 切回 JPEG 精确恢复——原值本来是 false FAILED     BUILD FAILED
还原后 → --tests 'com.filmkit.app.heif.*'  RC=0  BUILD SUCCESSFUL
```
单条等价形态（例：变异①）：
```bash
cp app/src/main/java/com/filmkit/app/heif/HevcCsd0.kt /tmp/csd.bak
python3 -c "import pathlib;p=pathlib.Path('app/src/main/java/com/filmkit/app/heif/HevcCsd0.kt');s=p.read_text();p.write_text(s.replace('h != null && h.size >= MIN_SIZE && (h[0].toInt() and 0xFF) == 1','h != null && h.size >= MIN_SIZE && (h[21].toInt() and 3) == 3'))"
JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew testDebugUnitTest --tests com.filmkit.app.heif.HevcCsd0Test
cp /tmp/csd.bak app/src/main/java/com/filmkit/app/heif/HevcCsd0.kt    # 还原
```

## 6. 「HEIF ⇄ 4:4:4」明示与自动关/恢复（追加块）

- **事实**：HEIF 恒 4:2:0（10bit 档 = 硬件 P010，探针实测 `p210:false`；8bit 档 = YUV420Flexible），
  `jpeg_444` 只作用于 JPEG。唯一判定入口 `HeifChroma.honorsJpeg444(jpeg444) = false`（有单测守）。
- **UI**：HEIF 档下「成片 4:4:4 色度」**灰显不可点**（`SettingSwitchRow` 新增 `enabled`，标题/说明/
  滑块一起降色），说明措辞 = "HEIF 走硬件编码器的 10bit P010（8bit 档走 YUV420），色度固定 4:2:0，
  故此项仅对 JPEG 生效"。
- **行为（对齐用户实机，PC 也这么做）**：切 HEIF 时把当前值备份到 `jpeg_444_before_heif` 并把
  `jpeg_444` 置 false；切回 JPEG **精确恢复**（备份缺失/类型非法 ⇒ 回落默认 `true`，不抛）。
  只在无备份时写备份键 ⇒ 反复切档不覆盖用户原值。
- **日志**：`settings.photo_format{…,"chroma":"420","jpeg444":<新值>,"jpeg444Ignored":true,
  "jpeg444AutoOff":…,"jpeg444Restored":…}`；`photo.heif_queued` / `photo.heif_encoded` /
  `photo.heif8_encoded` / `photo.heif_saved` / `photo.heif8_saved` 均带 `chroma`（+`bits`）。

## 7. 出包与产物核对

```
rm -rf app/build/outputs/apk && JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk filmkit-0.11.36-FIX-HEIF-CSD-8BIT.apk   （42,357,548 B）
aapt2 dump badging → versionCode='385' versionName='0.11.36' minSdk 26 targetSdk 36
lib/arm64-v8a/libfilmengine.so  md5（APK 内）= 694c5132828f20b572f440526069513f
                                md5（构建产物 app/build/filmengine/jniLibs/…）= 694c5132828f20b572f440526069513f  ✅一致
dex 内含新代码：photo.heif_csd / photo.heif8_queued / Heif8Encoder / HeifJpeg444
```

## 8. 用户验证步骤（真机）

1. 覆盖安装 `filmkit-0.11.36-FIX-HEIF-CSD-8BIT.apk`（prefs 保留）。
2. 设置页点「照片格式」→ **HEIF（10bit）**：该行下方「成片 4:4:4 色度」应变**灰不可点**，说明变为
   "…色度固定 4:2:0，故此项仅对 JPEG 生效"；日志出现
   `settings.photo_format{"photo_format":"heif","chroma":"420","jpeg444":false,"jpeg444Ignored":true,"jpeg444AutoOff":true}`。
3. **连拍两张**（本次事故两次都失败，需两次都验）：
   - 期望成功：`photo.deliver{bits:10,path:"heif"}` → `photo.heif_queued` →
     **`photo.heif_csd{len,ver,profile,bitDepthLumaMinus8,lengthSizeMinusOne,head,…}`** →
     `photo.heif_encoded` → `photo.heif_saved{bits:10,chroma:"420",detail:{…"spec":"10bit"…}}`，得 `.heic`。
   - 若 10bit 仍失败：必须看到 `photo.heif_failed` → **`photo.heif8_queued`** → 二选一：
     `photo.heif8_encoded{mime,codec,…}` + `photo.heif8_saved{bits:8}`（得 8bit `.heic`），
     或 `photo.heif8_failed` + `photo.heif_fallback_jpeg{from:"8bit"}`（得 `.jpg`）。
     **不允许**"10bit 失败后只有 JPEG 而没有任何 8bit 尝试"。
   - 把 `photo.heif_csd`（含 `head` 32 B hex）连同 `photo.heif_failed` 回传即可一轮定位。
4. 切回 JPEG：`settings.photo_format{…,"jpeg444":<恢复值>,"jpeg444Restored":true}`，该行恢复可点且
   回到你原来的设置（原本关着就还是关着）。
5. JPEG 无回归抽查：`photo.encode{chroma:"444",encoder:"native444",q:100,bytes:<非0>}` 或
   `{chroma:"420",encoder:"bitmap420",bytes:<非0>}`（后者现在也能看到真实字节数）。

## 9. 遗留 / 待办（如实记录）

- 8bit HEIF 那一级**只能真机验证**（`MediaCodec`/`MediaMuxer`）；离线覆盖的是顺序、日志与读头口径。
  MIME 用探针实测过的 `MIMETYPE_IMAGE_ANDROID_HEIC`（常量值实为 `image/vnd.android.heic`，AOSP 比较的
  是字面量 `image/heic`）—— 若真机仍落 `mp42`，8bit 自检会拒收并落 JPEG，日志 `mime` 字段即证据。
- 8bit 档码率沿用 10bit 档（质量优先）；"小体积"未专门调参，待真机对照体积/质量后再定。
- HEIF 仍不写 EXIF（自研容器无 Exif item；androidx ExifInterface 重写 HEIC 有风险），与 H2 卡一致。
- 内存：8bit 级在 `toRgba8`（48 MB/12MP）之外只多 ~4 MB 分块缓冲，但两级同时存活的峰值需真机观察。
