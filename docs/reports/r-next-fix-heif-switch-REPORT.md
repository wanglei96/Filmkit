# FIX1 报告：MFNR burst 落盘恒走 JPEG ⇒ 10bit packed 缓冲被按 8bit 交付（P0 照片损坏）

> 卡 FIX1 / 2026-10-14。**只修这一个缺陷**：不改 `docs/rec2020-heif-design.md` 的分期结论
> （该文件在工作区有本卡之外的未提交改动，本卡一字未碰），不顺带重构其它东西。

## 0. 一句话因果链

`CameraController.kt:3005-3014` 的 MFNR burst 落盘回调**硬编码 `queueJpegSave`**、绕过了同一张在下发点
冻结的交付档（HEIF 档 = 10bit）⇒ native 已按 `GL_RGB10_A2` + `GL_UNSIGNED_INT_2_10_10_10_REV` 读回的
**10bit packed** 缓冲，被 `CameraController.kt:3688` 的 `Bitmap.createBitmap(…, ARGB_8888)` +
`copyPixelsFromBuffer` 按独立 RGB 通道错读 ⇒ 霓虹伪彩 + 边缘浮雕 + 熵爆炸（37MB / 43MB，同场景 6.4MB）。
**两种格式都是 4 B/px（3072×4080×4 = 50135040 B）⇒ 长度校验永远抓不到。**

## 1. 事故与一手证据

### 1.1 真机日志时间线（vivo X300 / 0.11.34，用户 2026-10-14）

| 时刻 | 事件 | 读法 |
|---|---|---|
| 17:24:56 | `settings.photo_format: heif` | 用户切 HEIF |
| **17:25:05** | `heif10.selfcheck ok:true` | **决定性**：`selfCheck10BitOnce` 只在 `ten_bit=true` 的提交路径被调用 ⇒ 这一张**下发时交付档就是 10bit** |
| 17:25:10 | `settings.photo_format: jpeg` | 用户在**渲染期间**切回（不影响已冻结的档） |
| 17:25:16 | `photo.save_queued bytes=50135040` | 走 **JPEG 路径**（否则会打 `photo.heif_queued`） |
| 17:25:20 | `photo.encode {"chroma":"444","q":100,"ms":2002,"bytes":37401113}` | 熵爆炸：2002ms（正常 397ms） |
| 17:25:21 | `photo.saved FILMKIT_...172518.jpg` | **废片 1** |
| 17:29:26 → 17:29:43 | 再切 HEIF ⇒ 同一条 JPEG 路径 | **废片 2**（`ms":1046,"bytes":43218754`；selfcheck 是进程级一次性 ⇒ 不再打印） |
| 17:31:32 → 17:31:44 | 切回 JPEG ⇒ `ms":397,"bytes":6391731` | 8bit 档 ⇒ 正常 6.4MB ✅ |

**全程没有 `photo.heif_queued` / `heif_saved` / `heif_failed` / `heif_fallback_jpeg`** ⇒ 整条 HEIF 交付链
与它的三级回退都被排除。

### 1.2 两张废片的离线取证（可判别的定量判据）

`GL_UNSIGNED_INT_2_10_10_10_REV` 小端字节布局：`byte0=R[7:0]`、`byte1=R[9:8]<<6|G[5:0]`、
`byte2=G[9:6]<<4|B[3:0]`、`byte3=B[9:4]<<2|A[1:0]`。被当独立 RGB 读 ⇒ **G 通道直方图 = 4 个同构副本
（每段 64 桶）**、**B 通道 = 16 个同构副本（每段 16 桶）**，R 通道无此结构。段间相关系数：

| 文件 | R 4 段 | G 4 段 | B 16 段 |
|---|---|---|---|
| FILMKIT_...172518.jpg（废片 1） | 0.058 | **0.961** | **0.9977** |
| FILMKIT_...172942.jpg（废片 2） | −0.186 | **0.945** | **0.9983** |
| FILMKIT_...173144.jpg（正常，同机同日） | −0.004 | −0.087 | —（零方差段） |
| FILMKIT_...152031_徕卡M9.jpg（对照） | −0.142 | −0.199 | −0.039 |

正常片全部 ~0（自然图像），废片在**正好对应位打包的那两个通道**上达到 0.95 / 0.998。
⇒ 交付缓冲确实是 10bit packed 且被按通道错读 —— **物理证据，不是推测**。
（恢复已证伪：第 4 字节含 B 高 6 位被 3 通道丢弃 + 位打包跨通道 + JPEG 有损 ⇒ 不可逆，**不救数据**。）

### 1.3 为什么"切回 JPEG 后拍的第一张仍然糊"

MFNR burst 要等 4 帧采集走完才在下发点（`:3004`）冻结交付档 ⇒ 冻结发生在 17:25:05（prefs 仍是 heif），
用户 17:25:10 的切换**改变不了已冻结的那一张**。这就是"切到 HEIF 后（哪怕随后切回）第一张会糊"。

## 2. 因果链（文件:行）

```
CameraController.kt:3004  stillTenBit = tenBitForCapture()        ← burst 下发点冻结 = true（prefs=heif）
CameraController.kt:3034  submitRawBurst(…, tenBit = stillTenBit)  （3053 为预览线程分支）
  → RenderClient.kt:384   nativeEngineSubmitRawBurst(…, ten_bit)
  → Bridge.cpp:869        r->submitRawBurst(…, ten_bit == JNI_TRUE)
  → FrameRenderer.cpp:12858  if (ten_bit && !selfCheck10BitOnce()) return false;   ← 17:25:05 的日志出自此
  → FrameRenderer.cpp:13379  c.ten_bit = ten_bit
  → FrameRenderer.cpp:11300  if (cap_w_ != dst_w || cap_h_ != dst_h || cap_ten_bit_ != c.ten_bit)
  → FrameRenderer.cpp:11307      makeTex(&tex_cap_, &fbo_cap_, outputInternalOf(c.ten_bit)=GL_RGB10_A2,…)
  → FrameRenderer.cpp:11323  passOutput(…, outputMaxOf(c.ten_bit) = 1023.0)        ← dither 按 10bit 重标
  → FrameRenderer.cpp:11327  glReadPixels(…, kOutput10GlReadFormat, GL_UNSIGNED_INT_2_10_10_10_REV, …)
                             ⇒ 交付缓冲 = 10bit packed uint32（50135040 B）
  → Bridge.cpp:876        rgbaToJavaArray(env, rgba)               ← 同一条日志记 "bits":10
★ → CameraController.kt:3014  if (bytes != null) queueJpegSave(bytes, w, h, snap)   ← 错配点：绕过交付档
  → CameraController.kt:3649  photo.save_queued bytes=50135040    ← 长度校验"通过"
  → CameraController.kt:3688  Bitmap.createBitmap(w, h, ARGB_8888)
  → CameraController.kt:3689  bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))      ← 静默重新解释
  → CameraController.kt:3727  nativeEncodeJpeg444(outBmp, q)
  → JpegEncoder.cpp:53        3 通道读取（第 4 字节含 B 高 6 位被丢弃）
```

### 2.1 排除项（已复核，勿再查）

· **`selfCheck10BitReadback`（`FrameRenderer.cpp:8219`）** 用自己的局部 `tex/fbo`
（`:8252`）且结束即删并恢复全部 GL 状态 ⇒ **不碰 `tex_cap_` / `cap_*`**；17:25:05 的 `ok:true`
只是"交付档可信"的旁证。
· **三条成片入口的附件记账本就同源**：`:8673`（YUV）/ `:11300`（RAW+burst）/ `:8803`（编辑器恒 8bit）
都把**交付档与尺寸一起**记账 ⇒ native 侧没有"10bit 附件配 8bit readback"的路径（新增守卫 ⑨ 守住）。
· `PhotoFormat.read/write` 读写同一个 `photo_format` 键，无类型错配。

### 2.2 为什么守卫与自检都没拦住

① **长度校验天然无效**（4 B/px 相同）；② **自检只做在 HEIF 路径**（`saveHeif` 里的
`HeifDeliverCheck`），而这次交付**根本没进 HEIF 路径**；③ **交付档散落在各条收尾里** ——
单帧走 `finishStillCapture` 按档分派，burst 自己写了一份 ⇒ 漏一份就是静默交付错文件。

## 3. 修复落点

准则：**任何格式切换序列下，8bit 交付路径逐字节不变**；交付档与缓冲格式收口到**唯一真源**。

### 3.1 唯一真源 = 交付档随帧传递（删掉可被覆盖的共享状态）

· **删除成员 `stillTenBit`**：它会被"渲染期间的第二张拍摄 / 下一次冻结"覆盖，而回调要等 1~4s ——
与 `stillRot`/`stillAspect` 已翻过车的形态同型。三处下发点改为各自的
`val tenBit = tenBitForCapture()`，**随回调传参**（YUV 单帧 / RAW 单帧 / MFNR burst）。
· **唯一分派点 `deliverStill(bytes, w, h, snap, tenBit)`**（`CameraController.kt:2618`）：
四条收尾全部经它分派，并新增 `photo.deliver {"bits":8|10,"path":"jpeg"|"heif"}` ⇒ 交付档无条件留痕。
· **`DeliverFormat` 显式格式**（`:3963`）：`RGBA8(8)` / `RGB10A2_PACKED(10)`，由
`DeliverFormat.of(tenBit)` 唯一派生；`jpegAccepts` / `heifAccepts` = 两个编码器的接受契约。

### 3.2 交付边界断言：失败而非静默，但不丢片

· `queueJpegSave(…, fmt, extraTag)` / `queueHeifSave(…, fmt, …)` 的 `fmt` **故意不给默认值**
⇒ 编译器强制每个调用点显式声明格式（事故成因正是"可以忘掉交付档"）。
· 进 `Bitmap.createBitmap`（"静默重新解释"那一跳）**之前**断言：
`if (!DeliverFormat.jpegAccepts(fmt))` ⇒ 记 `photo.deliver_format_mismatch`（ERROR）并先
`Heif10Pipeline.toRgba8` 降位再落盘；HEIF 侧对称（`if (!heifAccepts(fmt))` ⇒ 记同一条日志并改走 8bit）。
· ⇒ 即使未来某条收尾又漏了分派，"交付档 ≠ 缓冲格式"**必定留 ERROR 日志**，且**绝不产出错数据文件**。

### 3.3 native 侧：代码不改（本就同源），改为把判据机械化

三条入口本就按同一个 `ten_bit` 决定 target / readback / dither（`OutputBitDepth.h` 唯一分支点）——
**本次事故不在 native**。架构师第 3 条中"交付缓冲必须带显式格式"落在 Kotlin 边界（3.2 的断言 +
`photo.deliver` 日志）：native 返回值是裸 `jbyteArray`，改 JNI 签名要同时动 3 个入口 + 3 个 Kotlin
声明 + 2 个 RenderClient，风险大于收益（YAGNI）。

**新增 native 侧离线判据（守卫 ⑨，`tools/check-bitdepth-single-source.py`）**：
⑨a 三条成片入口的**附件重建条件必须含交付档比较**、三条 `cap_ten_bit_ = …` 记账必须在位（钉死
"同尺寸切档复用错档附件"）；⑨b 每个入口内 `outputInternalOf` / `outputReadFormatOf` /
`outputReadTypeOf` / `outputMaxOf` 的**实参必须是同一个交付档表达式**（`submitStill`=`ten_bit`、
`finishStillChain`=`c.ten_bit`）⇒ 这是"**切档后同帧 readback 格式正确**"的机械等价判据。

### 3.4 8bit 路径逐字节不变

三处下发点值等价；`deliverStill(…, false)` 只多一条日志后走 `queueJpegSave(…, RGBA8)`；
`queueJpegSave` 在 `fmt == RGBA8` 时**整段跳过**断言分支，其余语句与改动前逐字相同；
burst `done` 在 `tenBit=false` 时与原调用完全等价；断言判定只在 `DeliverFormat` 一处。

### 3.5 "ByteArray → Bitmap"全部落点审计（架构师第 3 条）

| 落点 | 缓冲来源 | 交付档 | 结论 |
|---|---|---|---|
| `CameraController.kt:3735` `saveJpeg` | native 成片 readback（三条入口） | **随帧 tenBit** | **事故点，已由 §3.2 断言收口** |
| `EditorEngineSession.kt:284` | `nativeEngineSubmitDisplay` | 恒 8bit（JNI **无** `ten_bit` 参数；`FrameRenderer.cpp:8803` 强制重建 8bit、`:8833` 恒 8bit readback） | 结构上不可能拿到 10bit ⇒ 无需改 |
| `EditorScreen.kt:4475` `displayBitmap` | 用户**原图**解码缓冲（编辑器输入），非引擎输出 | 无关 | 与交付档无关 |
| `EngineBridge.kt:105` / `LutIO.kt` / `LutPreview.kt` | 上传方向（Bitmap→native）/ LUT 资产 | 无关 | 与交付档无关 |

⇒ 全仓只有**一处** native 成片缓冲按 `ARGB_8888` 解释，即本次事故点；它现在是唯一带格式断言的边界。

## 4. 回归与变异检验

### 4.1 新增测试（`app/src/test/java/com/filmkit/app/camera/StillDeliverFormatTest.kt`，13 条，全绿）

| 组 | 用例（牙） |
|---|---|
| §1 可判别假数据 | `…MisreadAsRgba8_isDetectablyWrong`（packed 直通读成 `(255,3,0,0)`/`(0,252,15,0)` ⇒ 伪彩可判别）· `…ViaUnpackRgb10_roundTripsExactly`（逐像素还原 10bit 真值）· `eightBitBufferThroughUnpackRgb10_…`（8bit 喂 10bit 解包会**静默跑通但结果错**） |
| §2 格式切换序列 | `jpegToHeifToJpeg_…` / `heifToJpegToHeif_…`（逐张：交付档→缓冲格式→编码器接受性三者一致）· `lengthIsNeverAFormatJudge`（50135040 B 钉死"长度不是判据"） |
| §3 源码审计 | `stillTenBit_memberMustNotComeBack` · `finishStillCapture_mustCarryFrozenTenBit` · `deliverDispatch_mustBeSingleSourced` · `burstDone_mustNotHardcodeJpegSave`（**事故现场复活即红**）· `queueJpegSave_callSites_areWhitelisted`（4 处白名单）· `queueEntryPoints_mustAssertFormat_withoutDefault` |
| §4 零回归 | `eightBitDeliveryPath_isByteIdentical` |

### 4.2 变异检验（故意改错 ⇒ 必须变红；实测）

| 变异 | 做法 | 实测 |
|---|---|---|
| **M1 复现事故** | burst `done` 改回 `queueJpegSave(bytes, w, h, snap, DeliverFormat.RGBA8)` | 13 跑 / **2 红**：`burstDone_mustNotHardcodeJpegSave`、`queueJpegSave_callSites_areWhitelisted` |
| **M3 近因复活** | 加回 `@Volatile private var stillTenBit = false` | 13 跑 / **1 红**：`stillTenBit_memberMustNotComeBack` |
| **M4 逻辑反转** | `DeliverFormat.of` 改成 `if (tenBit) RGBA8 else RGB10A2_PACKED` | 13 跑 / **3 红**：两条序列用例 + `eightBitDeliveryPath_isByteIdentical` |
| 还原 | 三处全部还原 | 13 跑 / **0 红** |
| **⑨-a1** | `submitStill` 重建条件删 `\|\| cap_ten_bit_ != ten_bit` | 守卫**红** |
| **⑨-b1** | `finishStillChain` 的 `outputReadTypeOf(c.ten_bit)` → `(false)` | 守卫**红**：实参 `['false'] ≠ {c.ten_bit}` |
| **⑨-a2** | 编辑器链路重建条件删 `\|\| cap_ten_bit_` | 守卫**红** |

```bash
# Kotlin 变异（改完跑、跑完还原）
CC=app/src/main/java/com/filmkit/app/camera/CameraController.kt; cp "$CC" /tmp/CC.bak
#   M1：burst done 的 deliverStill → queueJpegSave(bytes, w, h, snap, DeliverFormat.RGBA8)
#   M3：在 tenBitForCapture 前加回 @Volatile private var stillTenBit = false
#   M4：DeliverFormat.of 的 then/else 互换
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest \
  --tests "com.filmkit.app.camera.StillDeliverFormatTest" --console=plain
cp /tmp/CC.bak "$CC"
python3 tools/check-bitdepth-single-source.py --selftest   # native 侧三条变异体内建
```

## 5. 四条硬判据实测

1. **全量单测全绿**：`assembleDebug testDebugUnitTest` ⇒ **717 条 / 0 失败 / 0 错误 / 0 跳过**
   （68 个测试类；704 基线 + 本卡新增 13 条）。
2. **JPEG 零回归**（`engine-shader-offline.py`，ROI 1800,2100,600,1000，两次 `RC=0`）：
   · `--engine 0` ⇒ `film_d5.npy` crc32 **0x589f8364** ✅、shader crc32 **0x4fa1ebd3** ✅
     （25140 chars / 35154 B，uniform 写入 36、跳过 0）
   · `--engine 1` ⇒ `film_d5_sigmoid.npy` crc32 **0x7c2646ef** ✅（15781 chars / 23211 B，写入 21、跳过 22）
3. **守卫**：`check-bitdepth-single-source.py` ⇒ **PASS**（含新增 ⑨）；`… --selftest` ⇒ **自检通过**
   （16 条既有变异体 + 3 条 ⑨ 变异体全抓到，未变异体全绿 ⇒ 不是假守卫）；`validate-shaders.sh` ⇒
   **全部 shader 校验通过**（RC=0）；`check-diag-fields.py` ⇒ **全部通过**（RC=0）；
   `check-filmparams-contract.py` ⇒ **PASS**（kCount / 字段声明 / Kotlin COUNT / 语义名表四处一致，RC=0）。
4. **新增"格式切换后 8bit 交付缓冲格式正确"判据 + 变异检验**：见 §4 —— 13 条新测试全绿；
   M1/M3/M4 分别使 2/1/3 条变红；守卫 ⑨ 的三条变异体全部被抓。

**包**：`filmkit-0.11.35-FIX-HEIF-SWITCH.apk`（仓库根，42341164 B，md5 `cfccd1faf59ca994d6822f7dc098fe58`；
aapt2 读出 `versionCode=384 versionName=0.11.35`）。`lib/arm64-v8a/libfilmengine.so` md5
**`694c5132828f20b572f440526069513f`**，与 APK 内、`stripped_native_libs`、`merged_native_libs`、
`app/build/filmengine/jniLibs` 四处**逐一相同**（本卡未改 C++，native 零回归）。

## 6. 止损

本次根因**不是**附件缓存/重建（§2.1 已证三条入口本就按交付档重建记账），所以"切档时丢弃成片附件"
治不了这个病（附件重建了，缓冲仍会按 10bit 档读回）。**真正的止损落在交付边界**（已随修复落地、
并被测试守着）：`queueJpegSave` 收到非 8bit 格式时**拒绝直通** —— 记 `photo.deliver_format_mismatch`
（ERROR）并先降位（`Heif10Pipeline.toRgba8`）再落盘。只加一个 `if` + 一次降位调用，不碰渲染/GL/附件；
即使未来某条收尾再漏分派，产出的也只会是**正确颜色的 8bit JPEG**，而不是 37MB 霓虹伪彩。
守住它的测试：`queueEntryPoints_mustAssertFormat_withoutDefault` +
`tenBitBufferMisreadAsRgba8_isDetectablyWrong` + 变异体 M1。

## 7. 待真机确认项（用户操作步骤）

**A. 本次事故的正判**：① 设置页切 **HEIF（10bit）**；② 打开「多帧融合」（事故路径，**务必开**）拍 1 张；
③ 立刻切回 **JPEG**，同场景同参数再拍 1 张。期望：第 1 张 `.heic` 颜色正常；第 2 张 `.jpg` 体积回到
**~6 MB 量级**（**再出现 30 MB+ 即未修好**）。

**B. 日志判据（不靠肉眼）**：
```bash
grep -E "photo\.deliver|render\.(single|raw|burst)_done|photo\.(save_queued|encode|heif_queued|heif_saved|deliver_format_mismatch)" <日志>
```
· 每张照片**恰好一条** `photo.deliver`，其 `bits` 必须与同一张的 `render.*_done` 的 `"bits"` **相同**；
· `path:"heif"` ⇒ 随后必须有 `photo.heif_queued`；`path:"jpeg"` ⇒ 必须有 `photo.save_queued`，
  且 `photo.encode` 的 `bytes` 与"从未切过 HEIF 的 JPEG"同量级；
· **不得出现 `photo.deliver_format_mismatch`**（出现即还有收尾漏分派 —— 照片仍是正确的，已被降位兜住，
  但必须立刻查分派点）。

**C. 等价性**：三种序列各拍 1 张同场景（①从未切过 HEIF 的 JPEG；②`heif→jpeg` 之后的 JPEG；
③`jpeg→heif→jpeg` 之后的 JPEG），三张应逐字节等价（场景/曝光有变则比较解码后像素均值与 CRC，
量级差异 > 1 LSB 即异常）。

**D. 10bit 档本身无回归**：HEIF 档拍 1 张 ⇒ `photo.heif_queued → heif_encoded → heif_saved`，
不得出现 `heif_*failed` / `heif_discarded`，相册里颜色正常。

> native 侧"切档后同帧 readback 格式正确"无 GL 上下文无法离线验证，已用守卫 ⑨（target/readback/dither
> 实参同源 + 三条入口按交付档记账）做成**静态等价判据**；真机由判据 B 的 `bits` 一致性直接判定。

## 8. 未做与理由（YAGNI / 不在本卡范围）

· **不改 JNI 签名把交付格式随缓冲回传**：native 已在 `render.*_done` 记 `bits`，Kotlin 本就持有交付档
  （它自己传下去的）；改签名要动 3 个 JNI 入口 + 3 个 Kotlin 声明 + 2 个 RenderClient，收益仅是"少一处真源"。
· **不做"切档时失效成片附件"的 native 机制**：解决不了 burst 恒走 JPEG 的问题（见 §6）。
· **不尝试恢复两张废片**：架构师已用两种通道序反解证伪（不可逆）。
· **不动 `docs/rec2020-heif-design.md`**（该文件有本卡之外的未提交改动，mtime 17:40）与 HEIF 交付链
  （本次缺陷与它无关 —— 它从未被走到）。
