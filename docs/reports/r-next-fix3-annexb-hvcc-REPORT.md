# FIX3 · Annex-B → HEVCDecoderConfigurationRecord 转换（10bit HEIF 自研容器最后一道关）

> 卡号 FIX3（2026-10-14 派卡）　版本 **0.11.37（versionCode 386）**
> 产物 `filmkit-0.11.37-FIX-ANNEXB-HVCC.apk`
> 改动面：`app/…/heif/*`（**新增 `HevcAnnexB`**，重写 `HevcCsd0` 入口，改 `Hevc10Encoder`
> 的 `MediaFormat`/规范化调用，订正 `HeifContainerWriter` 注释）、
> 测试（新增 `HevcAnnexBTest`，扩展 `HevcCsd0Test`）、`app/build.gradle.kts`（版本号）。
> **未碰** `core/engine/`，未碰期 B，**未动 8bit 档实现**（`Heif8Encoder` 一字未改）。

## 0. 一句话结论

编码器 `outputFormat` 的 `csd-0` 真机实测是 **Annex-B**（79 B，`00 00 00 01` + VPS/SPS/PPS），
而自研 HEIF 容器的 `ipco/hvcC` box 要的是 **`HEVCDecoderConfigurationRecord`**；走 `MediaMuxer`
的两条路由 muxer 免费做了这步转换，**自研容器没有这一步**。卡 FIX3 补上它：
`HevcAnnexB`（Annex-B 切分 / H.265 SPS 语法解析 / hvcC 组装 / 帧数据长度前缀化）+
`HevcCsd0` 统一规范化入口（两种形态都吃 + 无条件诊断）。

## 1. 认知修正：那个"106 B 的 hvcC"是 **muxer 的产物**，不是编码器给的

**H1 阶段的错误结论**：以为"编码器实测给 106 B 的 hvcC"。**它错在哪**：那 106 B 是从
`MediaMuxer` 产出的 **MP4** 里 `moov/trak/mdia/minf/stbl/stsd/hvc1/hvcC` box 提取的，
是 muxer 把编码器的 Annex-B `csd-0` **转换后**写进去的结果。证据链（本轮重新核过 golden）：

| 事实 | 数值 | 出处 |
|---|---|---|
| `csd-0` 原始字节（真机日志） | **79 B**，`head=0000000140010c01ffff0220…` | `photo.heif_csd` 0.11.36 真机 |
| MP4 里 `hvcC` box payload | **106 B**，`ver=1 profile=2 bdl=2 bdc=2 chroma=1 lenSize=3 arrays=3` | `probe-main10-p010-*.heic` 的 `stsd` |
| 两者差值 | 79 → 106 = 固定头 23 + 3 组 array 头 9 + 3 个长度字段 6 − 起始码 11 | 逐字节核对 |

⇒ **"编码器会给 hvcC" 这个前提不成立**。自研容器必须自己实现转换（本卡第 1、2 点）。
MP4 golden 反而成了最好的**往返判据素材**（第 4 节）。

## 2. 转换规格（ISO/IEC 14496-15 §8.3.3.1）与 SPS 解析要点

### 2.1 hvcC 组装（`HevcAnnexB.toHvcc`）

| 字段 | 取值来源 |
|---|---|
| `configurationVersion` | 固定 `1` |
| `general_profile_space/tier_flag/profile_idc` | SPS `profile_tier_level()` |
| `general_profile_compatibility_flags`(4 B) | 同上 |
| `general_constraint_indicator_flags`(6 B) | 同上 |
| `general_level_idc` | 同上 |
| `min_spatial_segmentation_idc` | `0xF000`（reserved `1111` + 值 0） |
| `parallelismType` | `0xFC`（reserved + 0） |
| `chromaFormat` | `0xFC or chroma_format_idc` |
| `bitDepthLumaMinus8` / `bitDepthChromaMinus8` | `0xF8 or bit_depth_*_minus8` |
| `avgFrameRate` | `0` |
| `constantFrameRate` / `numTemporalLayers` / `temporalIdNested` | `0` / 参数（默认 1）/ 参数（默认 true） |
| `lengthSizeMinusOne` | 参数，默认 `3`（4 字节前缀，与帧数据同步转换） |
| `numOfArrays` + 各组 | `array_completeness=1`，按实际存在的类型（VPS 32 / SPS 33 / PPS 34） |

**`numTemporalLayers`/`temporalIdNested` 的口径说明（实测 vs 任务书）**：任务书写 `1/1`，
而真机 muxer 产物写的是 **`0/0`**（golden `byte21 = 0x03`；Android/FFmpeg 同型实现也写 0）。
这两个字段是**容器级声明**（只影响时间层子码流提取，单帧静态图无子码流），不违反规范。
⇒ 代码默认 `1/1`（更正向的解释），**同时暴露参数**：往返判据按源 record 回填 `0/0` 得到
逐字节相等（第 4 节），两条口径都有测试锁住（`默认参数…差异仅限 byte21 的 4 位声明`）。

### 2.2 SPS 解析必须**按语法逐字段读**，不许扫字节

`profile_tier_level()` 之后的 `sps_seq_parameter_set_id`、`chroma_format_idc`、
`pic_width/height_in_luma_samples`、`bit_depth_luma/chroma_minus8` **全是 ue(v) 变长**。
任何"找固定偏移"的实现在换编码器/换 profile 时会**静默读错位**，症状是 hvcC 里声明错
profile/位深（解码端按错位深解释 ⇒ 颜色全错且不报错）。本实现按 H.265 7.3.2.2.1 完整解析：

- 必需段：`profile_tier_level(profile_present, max_sub_layers_minus1)`（含 sub-layer 分支）、
  `chroma_format_idc`（`==3` 时读 `separate_colour_plane_flag`）、`bit_depth_*_minus8`；
- **自检段（覆盖任务点名的可变长字段）**：`sps_sub_layer_ordering_info`、CB/TB 层级 6 个 ue(v)、
  `scaling_list_data()`（`sizeId 0..3`、`matrixId` 步长 1/3、`coefNum = min(64, 1<<(4+(sizeId<<1)))`、
  `se(v)` 的 dc/delta 系数）、`pcm_enabled` 块、`st_ref_pic_set` 循环（含 inter 预测的
  `NumDeltaPocs` 追踪）、`long_term_ref_pics`（`u(log2MaxPocLsb)`）、`vui_parameters()`
  （aspect_ratio / video_signal_type + colour_description / chroma_loc / default_display_window /
  timing_info / bitstream_restriction）；
- 收口判据 `fullyParsed`：整条 SPS 消费完必须停在合法 `rbsp_trailing_bits`（`1` + 全 `0`，≤8 位）。
  真机 golden SPS 实测消费 **241 位 + 7 位 trailing**（`…40`），`fullyParsed=true`。
  自检失败**不影响** hvcC 需要的字段（那些在 SPS 前段，已读到），只作为下一轮线索 —— 不允许
  "自检不过就整体失败"把片子打死（FIX2 的教训）。

### 2.3 帧数据同步转换（`HevcAnnexB.frameToLengthPrefixed`）

**判定不靠猜，靠链式自洽**（顺序刻意如此）：

1. **先试长度前缀链**：逐 NAL 读长度 → `len >= 2`（HEVC NAL 头固定 2 B）→ NAL 头
   `forbidden_zero_bit==0` 且 `nal_unit_type != 0` → **链尾必须精确等于 buffer 长度**。
   任一条不满足即否。
2. **再试 Annex-B**（能切出起始码）。
3. 都不成立 ⇒ **抛异常**（宁可不交付，也不把形态不明的字节写进 item data）。

⚠ **为什么不能"含 `00 00 01` 就判 Annex-B"**：真机 golden 的 IDR 帧**恰好以 `00 00 01 1a`
开头**（282 = `0x0000011a` 的 4 字节前缀），字节上与 3 字节起始码无法直接区分；而按 Annex-B
解读时 `0x1a` = RASL_N（不能作为图像首 NAL）。链式判据的 `len >= 2` 一条就否掉了 Annex-B 的
`00 00 00 01`（按 4 字节长度解读 = 1）。这条写成了独立回归测试
（`以 00 00 01 开头的 4 字节前缀帧不得被判成 AnnexB`）。

**双转防护（断言 + 测试）**：已是长度前缀 ⇒ **原样返回同一个实例**（`assertSame`）；
Annex-B 转换后再判定一次形态，必须变成长度前缀链，否则抛。测试里同时给出了"双转产物"
（长度 +4、首 4 字节变成 NAL 长度）作为反面对照。

**带内参数集的处理**：Annex-B 帧若含 VPS/SPS/PPS（`prepend-sps-pps-to-idr-frames` 默认开启时
常见），**全部原样保留**并加长度前缀 —— 不做过滤（过滤会引入"带内参数集与 hvcC 不一致"的
新风险；解码器优先用 hvcC，带内重复无害）。这是刻意的取舍，记录在此备查。

### 2.4 `prepend-sps-pps-to-idr-frames = 0` 低成本尝试（第 3 点）

`Hevc10Encoder.buildFormat` 已加 `runCatching { fmt.setInteger("prepend-sps-pps-to-idr-frames", 0) }`
（探针 `outputFormat` 里出现过该键 ⇒ 编码器认它）。**两种情形都在通用路径内被正确处理**：

| 真机表现 | `photo.heif_csd` 读法 | 走哪条路 |
|---|---|---|
| 无效（预期）：`csd-0` 仍是 Annex-B | `converted:true`、`csdInLen:79`、`csdOutLen:~106`、`frameMode:"annexb"` | `toHvcc` + 帧长度前缀化 |
| 有效：`csd-0` 变成 hvcC | `converted:false`、`csdInLen == csdOutLen`、`frameMode:"len4"` | 原样搬运（测试 `已是 hvcC 的 csd-0 直通`） |

⇒ **不依赖 vendor 行为**：两条路都有测试，真机无论走哪条都能交付 10bit HEIF。

## 3. 改动清单

| 文件 | 改动 |
|---|---|
| `HevcAnnexB.kt` | **新增**：`splitAnnexB` / `toAnnexB` / `parseSps`（完整语法）/ `scalingListData` / `vuiParameters` / `toHvcc` / `parseHvccArrays` / `frameMode` / `isLengthPrefixedChain` / `frameToLengthPrefixed` |
| `HevcCsd0.kt` | 入口改 `accept(csd, frame, emit) → Normalized(hvcc, frame)`；两种 csd 形态都吃、转换异常统一成 `IllegalStateException`；`fields()` 扩到 15 个键 |
| `Hevc10Encoder.kt` | 加 `prepend-sps-pps-to-idr-frames=0`；`csd-0`/帧数据改为**原样收集**后交给 `HevcCsd0` 规范化；注释订正 |
| `HeifContainerWriter.kt` | 仅订正注释（那条"编码器天然给 4 字节前缀"的结论是错的）。**代码零改动** |
| `HevcAnnexBTest.kt` | **新增** 27 个用例；`HevcCsd0Test.kt` 7 → 9 个用例 |
| `app/build.gradle.kts` | versionCode 385 → **386**，versionName 0.11.36 → **0.11.37** |

`Heif8Encoder` / `HeifDelivery` / `HeifJpeg444` / `HeifDeliverCheck` / `CameraController` **未改动**。

## 4. 往返测试结果（有牙的部分）

数据源全部是**真机 golden**：`app/src/test/resources/heif/probe-main10-p010-*.heic`
（其实是 MP4：`ftyp/moov/trak/stbl/stsd/hvc1/hvcC` + `mdat`）。

```
MP4 → hvcC box (106 B) → parseHvccArrays → [VPS 23 B, SPS 36 B, PPS 9 B]
    → toAnnexB (80 B = 3×4 起始码 + 68)  → toHvcc(…, lenSize=3, 0/0) → 106 B
```

| 判据 | 结果 |
|---|---|
| **hvcC 逐字节往返** | ✅ `assertArrayEquals(golden, roundTrip)` 106/106 字节相同 |
| SPS 解析字段 | ✅ `profile=2 / tier=0 / space=0 / compat=0x20000000 / constraint=0xB00000000000 / level=123 / chroma=1 / bdl=2 / bdc=2 / sml=0 / nesting=true / fullyParsed=true` |
| **两条独立路径互证** | ✅ 从 hvcC 固定头直接读的 profile/compat/level/chroma/位深 == 从 SPS 语法解析出的同一组值 |
| **帧数据逐字节往返** | ✅ 286 B（4 + 282）→ 拆成 Annex-B → `frameToLengthPrefixed` → **286 B 逐字节相同** |
| 自研容器 golden 交叉核对 | ✅ `selfmade-*.heic` 的 hvcC 与 MP4 提取的**逐字节相同**（同一个 106 B 来源可追溯） |
| 默认参数差异范围 | ✅ 与 golden 的唯一差异 = `byte21` 的 `0x03 ^ 0x0F = 0x0C`（仅 `numTemporalLayers`/`temporalIdNested` 4 位），其余 105 字节全等 |
| 边界：无 VPS | ✅ `numOfArrays=2`、`arrayTypes=[33,34]`、NAL 内容原样 |
| 边界：3 字节起始码 | ✅ `00 00 01` / `00 00 00 01` / 多前导零混用均可切分；4 字节起始码的 `zero_byte` 不留在上一 NAL 尾部 |
| 边界：trailing zeros | ✅ 尾部 8 个 `00` 不改变产出的 hvcC |
| 边界：`chroma_format_idc==3` | ✅ 构造样本覆盖 `separate_colour_plane_flag` 两种取值，均 `fullyParsed=true` |
| 边界：scaling_list + 完整 VUI | ✅ 构造样本（4 组 scaling list × 64 个 `se(v)` + colour_description + timing_info + bitstream_restriction）`fullyParsed=true` |
| 边界：8/10/12bit | ✅ `bitDepth=0/2/4` 均可解析回原值 |
| 双转防护 | ✅ `assertSame`（LEN_PREFIXED 分支返回同一实例）+ 反面对照（双转产物长度不同） |
| 非法输入 | ✅ 非 SPS（PPS）、无起始码、0 个/2 个 SPS、`len<23`、`csd=null`、形态不明帧 ⇒ 各自明确失败 |

## 5. 五条硬判据实测

### 判据 1 · 全量单测全绿

```
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL
tests=769 failures=0 errors=0        # 基线 740 + 新增 29（HevcAnnexBTest 27 + HevcCsd0Test +2）
```

### 判据 2 · JPEG 零回归

`--engine 0` ⇒ `film_d5.npy` crc32 = **`0x589f8364`** ✅、`shader` crc32 = **`0x4fa1ebd3`** ✅；
`--engine 1`（`--out /tmp/fix3-e1`）⇒ `film_d5_sigmoid.npy` crc32 = **`0x7c2646ef`** ✅。
命令即任务书给出的 `python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng
--roi 1800,2100,600,1000 --out /tmp/fix3-eN --engine N`。

### 判据 3 · 守卫全 PASS

| 守卫 | 结果 |
|---|---|
| `python3 tools/check-bitdepth-single-source.py` | `PASS：两档真源自洽 · shader dither 锚定 · 输出落点全部引用真源 · Kotlin 同源 · 冻结计数未变 · 10bit 自检与 fail-closed 在位` |
| `python3 tools/check-bitdepth-single-source.py --selftest` | `自检通过`（18 个变异体全部各自报红 + 未变异体全绿） |
| `bash tools/validate-shaders.sh` | `全部 shader 校验通过（含引擎 1 sigmoid）` |
| `python3 tools/check-diag-fields.py` | `全部通过` |
| `python3 tools/check-filmparams-contract.py` | `PASS: kCount / 字段声明覆盖 / Kotlin COUNT / 语义名表 四处一致` |

### 判据 4 · 8bit 回退不得回归

`Heif8Encoder.kt` **一字未改**（`git status` 无该文件），8bit 档行为完全由 `MediaMuxer` 决定；
本轮改动只落在 10bit 自研容器链路上。`HeifDeliveryTest`(7) / `HeifJpeg444Test`(6) /
`HeifDeliverCheckTest`(11) 全绿。

### 判据 5 · 出包与 so 一致性

```
$ rm -rf app/build/outputs/apk && ./gradlew assembleDebug
$ cp app/build/outputs/apk/debug/app-debug.apk filmkit-0.11.37-FIX-ANNEXB-HVCC.apk
$ aapt dump badging filmkit-0.11.37-FIX-ANNEXB-HVCC.apk | head -1
  package: name='com.filmkit.app' versionCode='386' versionName='0.11.37' …
$ unzip -p … 'lib/arm64-v8a/libfilmengine.so' | md5   → 694c5132828f20b572f440526069513f
$ md5 app/build/filmengine/jniLibs/arm64-v8a/libfilmengine.so → 694c5132828f20b572f440526069513f
```

## 6. 变异检验（每条都必须让对应测试变红）

脚本 `tools/fix3-mutation-check.py`（已入库）：备份 → 单点变异 → 跑
`--tests com.filmkit.app.heif.*` → 收红 → **自动恢复**（手工改容易忘记恢复）。

| # | 变异（单点） | 变红用例数 | 代表红用例 |
|---|---|---|---|
| M1 | `toHvcc` 里 `lengthSizeMinusOne` 写死 `0`（声明与实际打包不一致） | **5** | `golden hvcC …逐字节相同`、`长度前缀宽度 0 与 1 也按 hvcC 声明正确打包`、`真机形态的 Annex-B csd-0 被转换且诊断字段齐全` |
| M2 | `parseSps` 里 `bit_depth_chroma_minus8` **少读一位**（不消费该 `ue(v)`） | **7** | `golden SPS 解析出 Main10 的正确字段且语法自检通过`、`hvcC 固定头字段与 SPS 解析结果一致`、`构造 SPS 覆盖 chroma_format_idc 等于 3…` |
| M3 | `scaling_list_data` 的 `coefNum = min(64, 1<<(4+(sizeId<<1)))` 改成 `1<<(4+sizeId)`（跳过长度少一半） | **1** | `构造 SPS 带 scaling_list_data 与完整 VUI 时仍能完整消费位流` |

复现命令（在原实现上手工改一处即可）：

```bash
python3 tools/fix3-mutation-check.py   # 依次施加 M1/M2/M3 并自动恢复
# 或手工：
#   M1  HevcAnnexB.kt: `… or (lengthSizeMinusOne and 3),`  → `… or 0,`
#   M2  HevcAnnexB.kt: `val bdc = br.ue()`                 → `val bdc = if (true) 0 else br.ue()`
#   M3  HevcAnnexB.kt: `1 shl (4 + (sizeId shl 1))`        → `1 shl (4 + sizeId)`
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:testDebugUnitTest \
    --console=plain --tests "com.filmkit.app.heif.*"
```

M3 只红 1 条是**预期**的：真机 golden 的 SPS 不带 scaling list，只有构造样本能覆盖该路径 ⇒
这条构造样本的存在是必要的（否则该分支零覆盖）。

## 7. 真机验证步骤（本轮无真机，留给下一轮）

1. 安装 `filmkit-0.11.37-FIX-ANNEXB-HVCC.apk`（versionCode **386**），拍摄一张并抓
   `photo.*` 日志（`EngineBridge` 的 sessionId 过滤）。
2. **判读 `photo.heif_csd`**（本轮的核心观测点，字段齐全 ⇒ 一轮定位）：
   - 期望值（真机 Annex-B 输入）：`{"len":79,"csdInLen":79,"csdOutLen":106,"converted":true,"ver":1,
     "profile":2,"bitDepthLumaMinus8":2,"bitDepthChromaMinus8":2,"chromaFormat":1,
     "lengthSizeMinusOne":3,"arrays":3,"arrayTypes":[32,33,34],"frameMode":"annexb","ok":true,…}`
   - 若 `converted:false` 且 `csdInLen==csdOutLen` ⇒ §2.4 的尝试**生效了**（vendor 直给 hvcC），
     `frameMode` 应为 `len4`；两种情形都必须 `ok:true`。
   - `bitDepthLumaMinus8 != 2` 或 `profile != 2` ⇒ SPS 解析读错位（`head` 是输出 hvcC 前 32 B，可核对）。
   - `ok:false` + `photo.heif_failed` ⇒ 转换失败，`head` 会回落到**输入** csd-0 的前 32 B
     （诊断不会因为失败而失去线索）。
3. **成功判据**：`photo.heif10_saved`（或 10bit 档等价事件）出现且**不出现**
   `photo.heif_failed`；`photo.heif8_encoded` / `photo.heif_fallback_jpeg` **不应出现**
   （出现即说明 10bit 仍失败、回退链兜住了片子）。把 HEIF 拷到 macOS 用
   `sips -g pixelWidth -g pixelHeight` 或预览核对：期望 `3072×4080` / `heic` / 10bit。
4. 若 10bit 仍失败：`photo.heif_csd` 的 `converted`/`csdInLen`/`csdOutLen`/`frameMode` + 失败
   消息（含 `csdInLen`、`csdOutLen`、`nals`、`lenSize`）足以定位到"哪一步"。
5. **回归确认（8bit 档不动）**：把设置里的 HEIF 档切回 8bit 走一次，`photo.heif8_saved` 的
   `boxes:"ftyp,meta,free,mdat"` / `ispe:"3072x4080"` 应与 0.11.36 完全一致。

## 8. 遗留与后续

- **真机 `numTemporalLayers` 口径**：默认 1/1（任务书口径），真机 muxer 写 0/0；两者都被解码端
  接受。若真机 10bit 成功但某解码器报时间层问题，把 `toHvcc` 的该两参调成 `0/false`（一处改动）。
- **`fullyParsed=false` 若在真机出现**：说明 SPS 还有未覆盖分支（`sps_extension_*` /
  `vui_hrd_parameters`）—— 不影响 hvcC 字段（在 SPS 前段），下一轮用 `head` 定位补覆盖。
