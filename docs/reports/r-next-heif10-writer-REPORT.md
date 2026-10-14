# r-next · 10bit HEIF 容器写入器 + Main10/P010 编码器（实现卡 H1）报告

**日期** 2026-10-14 · **范围**：纯代码 + JVM 单测，**未出包、未跑真机**（遵守：不动 `app/build.gradle.kts`、不 `assembleDebug`、不碰 `core/engine/`）。
**路线前提**（架构师一手实测，本卡不重论证）：真机 vivo X300（Android 16/SDK 36）的 `c2.mtk.hevc.encoder` 支持 Main10+P010(54)，`hvcC` 106 B（`profile_idc=2`、`bitDepthLumaMinus8=2`），帧是 4 字节长度前缀 NAL（`lengthSizeMinusOne=3`）；`MediaMuxer(MUXER_OUTPUT_HEIF)` 只产出 **MP4**（`ftyp=mp42`）⇒ 必须自研容器。

---

## 1. 交付内容与落点

| 文件 | 行数 | 说明 |
|---|---|---|
| `app/src/main/java/com/filmkit/app/heif/HeifContainerWriter.kt` | 162 | 自研 HEIF 容器写入器（纯函数、零 Android 依赖） |
| `app/src/main/java/com/filmkit/app/heif/Hevc10Encoder.kt` | 248 | HEVC Main10 + P010 单帧编码器（`MediaCodec` 封装） |
| `app/src/test/java/com/filmkit/app/heif/HeifContainerWriterTest.kt` | 338 | 12 条 JVM 单测：逐字节 + 结构回读 + 边界 |
| `app/src/test/resources/heif/probe-main10-p010-1791965865303.heic` | 3518 B | golden：真机 muxer 产物（**内容是 MP4**），`sha256 ca2a6073…` |
| `app/src/test/resources/heif/selfmade-probe-main10-p010-1791965865303.heic` | 650 B | golden：原型产出的 HEIF，`sha256 6961c599…` |
| `app/src/main/java/com/filmkit/app/diag/Heif10BitProbe.kt` | +~70 行 | 追加第三臂 `muxer-image-heic` + 回读器补 `ftyp`/`pixi`/`iloc`（父代理追加要求，见 §7） |

### 1.1 写入器（`HeifContainerWriter.kt`）

| 行 | 内容 |
|---|---|
| 48 | `data class Cicp` —— CICP 四元组，**默认 = sRGB 显示域 `1/13/1/full`**（用户拍板选项 A）；线性档传 `(9,8,9,true)` |
| 56 / 68 | `class Image`（`hvcC`+4 字节长度前缀帧+宽高）/ `fun build(image, cicp, bitDepth)` 唯一公开入口（单帧） |
| 72–94 | `private fun build(items, …)` —— **多帧挂点**（item 数参数化）+ 两遍构造 + `check(meta.size 不变)` |
| 96–135 | `private fun meta(…)` —— `hdlr(pict)` + `pitm` + `iinf/infe(v2,hvc1)` + `iloc` + `iprp(ipco+ipma)`；`iloc` offset 按 `dataStart` 连续累加 |
| 137–162 | box 原语（big-endian，与原型 `struct.pack('>…')` 等价） |

结构 `ftyp(heic/mif1)` + `meta`(FullBox) + `mdat`；`ipco` 内顺序 = `hvcC`→`ispe`→`pixi`→`colr`；`ipma` 关联 `0x81..0x84`（**1-based**、全 essential）；**无** `MediaMuxer` 的 `free` box。

### 1.2 编码器（`Hevc10Encoder.kt`）

| 行 | 内容 |
|---|---|
| 40–46 | **RGB→YCbCr 挂点声明**（不在本卡范围，见 §5.4） |
| 51 / 62 / 82 | `DEFAULT_BITRATE`（探针口径；KDoc 注明交付按分辨率覆盖）/ `selectEncoder()`（只挑 Main10 或 Main10HDR10 **且** P010(54) **且** 硬件；选不到返回 `null`）/ `buildFormat()` |
| 111–176 / 178 | `encode()`：喂 1 帧 + EOS、drain、从 `outputFormat.csd-0` 取 `hvcC`、拼接输出样本 / `csd0()` 用 `duplicate()` 读（不改调用方 position） |
| 191 / 244 | `fillInput()`（`getInputImage()` 优先，P010 MSB-aligned 手填兜底）/ `putSample()` |

`buildFormat()` 键：`KEY_COLOR_FORMAT=P010(54)`、码率/帧率/GOP、`KEY_COLOR_STANDARD=6`/`KEY_COLOR_TRANSFER=1`/`KEY_COLOR_RANGE=1`（**Android 枚举，不是 CICP**）、`STRIDE/SLICE_HEIGHT/MAX_INPUT_SIZE`、`KEY_PROFILE=Main10`+`KEY_LEVEL=4.1`。
**信任边界（不省）**：`require(pattern.size == w*h)`；`csd-0` 缺失 → 明确失败；`(hvcC[21] and 3) != 3` → 明确失败（容器假设 4 字节前缀，绝不静默产出坏文件）；输出帧为空 → 明确失败。

---

## 2. 与原型逐字节比对：**零差异**

```
真机输入：hvcC 106 B（首字节 01022000…）+ 帧 286 B（4 字节长度前缀 NAL，首 4 字节 0000011a）
原型 golden：selfmade-probe-main10-p010-1791965865303.heic · 650 B
本卡产出  ：650 B · assertArrayEquals(golden, out) 通过 ⇒ 逐字节完全相同
```

**差异清单：空**。能做到零差异是因为原型每个结构选择都照抄：`ftyp` minor=0、`hdlr` = `pre_defined=0 + 'pict' + 13 字节 0`、`infe` **v2**+`hvc1`+空 name、`iloc` `0x44/0x00`、`pixi` = `03 0a 0a 0a`、`colr` = `nclx + 9/8/9/0x80`（线性档）、`ipma` = entry_count 1 + item_ID 1 + `04 81 82 83 84`。

> 该比对**显式传** `Cicp(9,8,9,true)`（原型口径），没绑在默认值上 —— 交付口径已改成 sRGB 显示域，绑默认值会让这条锚在换档时假红。

---

## 3. 结构正确性锚点（真机 golden 实测字节，全部被单测断言）

**① 真机 muxer 产物 = MP4**：`ftyp` = major_brand **`mp42`** + compatible `isommp42`；顶层 = `ftyp, moov, free, mdat`，**无 `meta`**；样本 `stsz` 1×286 B @ `co64` offset 3232（`0xca0` = `mdat` payload 起点，`mdat` 是 **largesize** box）。

**② 原型 HEIF（本卡输出与之全等）**

| box | 大小 | 关键内容 |
|---|---|---|
| `ftyp` / `meta` | 24 / 332 | `heic`/0/`mif1`+`heic`；`meta` 子序 `hdlr(33) pitm(14) iinf(35) iloc(30) iprp(208)` |
| `ipco` | 177 | `hvcC(114=8+106) ispe(20) pixi(16) colr(19)` |
| `iloc` | 30 | `0x44 0x00` + item_count=1 + (ID=1, dref=0, extents=1, **offset=364**, length=286) |
| `mdat` | 294 | payload 286 B = 帧；**364 = mdat 起点 356 + 8** ⇒ 绝对偏移正确 |

`ispe` 1024×256；`pixi` `03 0a 0a 0a`；`colr` `6e636c78 0009 0008 0009 80`；`infe` v2/`hvc1`；
`hvcC` 自检 `[0]=1`、`[1]&0x1f=2`(Main10)、`[17]&7=2`/`[18]&7=2`(10bit)、`[21]&3=3`(4 字节前缀)、`[22]=3`。

**③ CICP 参数化**：默认（交付口径）`1/13/1/full`；线性档 `Cicp(9,8,9,true)`。单测断言两档产物**只差 `colr` 的 CICP 字节**（其余全等）⇒ "换档 = 换参数"，无隐藏耦合。`matrix_coefficients` 必须与编码侧 RGB→YCbCr 矩阵同档（默认 `1`=BT.709）。

---

## 4. 单测清单与实测

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew testDebugUnitTest          # RC=0 · 684 tests, 0 failures, 0 errors, 0 skipped
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew :app:compileDebugKotlin    # RC=0 · BUILD SUCCESSFUL
```

**本卡新增 12 条（`HeifContainerWriterTest`，全绿，耗时 0.042 s）**

| # | 测试 | 断言要点 | 实测 |
|---|---|---|---|
| 1 | 真机 muxer 产物是 MP4 而不是 HEIF | brand `mp42`、顶层 `ftyp,moov,free,mdat`、无 `meta` | PASS |
| 2 | **真机输入重建出的 HEIF 与原型 golden 逐字节相同** | 106 B hvcC + 286 B 帧 → 650 B，`assertArrayEquals` | PASS |
| 3 | 默认 CICP 是 sRGB 显示域 | `colr` = `1/13/1/0x80` | PASS |
| 4 | 两档 CICP 只差 colr 的四元组字节 | 同长；差异全落在 `colr` 内容内 | PASS |
| 5 | box 嵌套与顺序 | 顶层 + `meta` 子 + `iprp` 子 + `ipco` 四 property 顺序；box 严格铺满文件 | PASS |
| 6 | iloc 的绝对偏移确实指向 mdat 内的数据 | `0x44/0x00`、计数、offset == `mdat.body`、payload == 帧 | PASS |
| 7 | colr pixi ispe infe 与 hvcC 位深 | `nclx` 四元组、`pixi` 3×10、`ispe`、`infe` v2/`hvc1`、`pitm`=1、`hdlr`=`pict`、`ipma`、hvcC 位深自检 | PASS |
| 8 | 空帧与非法尺寸被拒绝 | 空帧/空 hvcC/尺寸 0 或负/位深 0 → `IllegalArgumentException` | PASS |
| 9 | 最小尺寸 1x1 | `ispe`=1,1；`mdat` = 4 B 帧 | PASS |
| 10 | 大偏移与大长度时 iloc 的 4 字节字段不溢出 | 70 KB hvcC ⇒ offset > 0xFFFF；200 KB 帧 ⇒ length=200000；`offset+length == 文件长` | PASS |
| 11 | 自定义 CICP 与位深生效 | `(12,16,6,limited)` + `pixi`=12 | PASS |
| 12 | golden HEIF 自身可被解析器完整走通 | 自证测试解析器（防"解析器坏了当成写入器坏了"） | PASS |

覆盖任务要求的 ①（测试内**最小 MP4 解析器**从 golden 提取，未用固定字节常量）②（逐字节）③（嵌套/`colr`/`iloc` 指向/`pixi`/`ispe`）④（空帧/超小/大尺寸的 4 字节长度字段）。
**全量口径**：684 tests / 0 failures；其中本卡新增 12 条，工作区另有其它卡对 `app/src/test/**` 的并行改动，故 684 ≠ "668+12"，基线以本卡开始前的 668 条另计。

---

## 5. 未覆盖项

### 5.1 真机相关（**一律未跑，不是"已验证"**）

1. **`Hevc10Encoder.encode()` 从未真机执行**：`MediaCodec` 是 Android API，JVM 单测（无 Robolectric）覆盖不到；它的每段都对应探针里已跑通的代码（§6），但"提炼后的这一份"本身没有真机证据。首次真机验证由相机管线接入时做。
2. **探针第三臂 `muxer-image-heic` 未真机运行**（需装包，本卡禁止出包）⇒ "改 MIME 后到底写 `meta` 还是 `moov`"、"`colr`/`pixi` 是否被 AOSP `camera_heif_gainmap` flag 门控掉"本卡**无实测数据**；代码已就绪，跑一次探针即得答案。
3. **`sips` 解码未复跑**：原型已用同一份输出验证"macOS 原生解码器解出正确像素"，本卡产出与之**逐字节相同**（§2），结论可传递；将来改结构必须重跑。
4. `KEY_COLOR_*` 在非 X300 机型上的映射未验证（写入器不读这些键，容器侧不受影响；编码侧实际变换矩阵仍依赖机型）。

### 5.2 多帧 / burst

`iinf`/`iloc`/`ipma` 已按 item 数参数化（`:72-135`），但**未实现也未测试**：缺 `iref`(`dimg`/`thmb`) ⇒ burst 语义不成立；所有 item 目前共享 `ipco` 的 4 个 property（尺寸须一致），不同尺寸需要 per-item 关联。公开入口只接受单帧，多帧是 `private` 函数，**没有死代码式的多帧实现**。

### 5.3 选型与回退

选不到 `main10 && p010 && 硬件` 时 `selectEncoder()` 返回 `null`、`encode()` 抛 `IllegalStateException`；**调用方回退策略（8bit HEIF？JPEG？）不在本卡范围**，未实现。

### 5.4 RGB10 → YCbCr 4:2:0（判定**不属于本卡**，挂点已写死）

- **谁做**：相机管线；**在哪做**：`Hevc10Encoder.encode()` 之前的调用方代码（本类只吃已打包的 `y10 | (u10 shl 10) | (v10 shl 20)`，各 0..1023 全范围）。
- **约束**：矩阵必须是 **BT.709**，且与容器 `colr.matrix_coefficients`（默认 `1`）同档；改一处必须改另一处，否则解码端串色。该约束写在 `Hevc10Encoder.kt:40-46` 与 `:106-108`。
- **未做**：`tex_cap_` 子树抽取 → P010 缓冲、以及 4:4:4→4:2:0 下采样都没实现；`getInputImage()` 路径按编码器给的 `rowStride/pixelStride` 写入。

### 5.5 其它

- `Hevc10Encoder` **无任何调用方**（未接线）；写入器已可用于任何 `hvcC + 帧` 输入。
- 码率默认 `4_000_000` 是探针口径（1024×256），对真实分辨率偏小，交付时必须按分辨率/质量目标覆盖。
- `pixi` 只写 3 通道同位深；`colr` 只写 `nclx`（未支持 `prof`/`rICC`）。

---

## 6. 与探针的关系（受控重复，已说明）

硬约束写"**不要动 `Heif10BitProbe.kt`**（诊断入口）"，同时要求"复用探针已跑通的逻辑、不要重写一套"。处理方式：**把逻辑提炼进 `Hevc10Encoder`，探针对应函数保持原样**（未做"探针调用新类"的改造，因为那要动探针的 `encodeHeif`/`fillInput`，会改变其诊断产出）。逐段对应（可核对）：

| `Hevc10Encoder.kt` | `Heif10BitProbe.kt` |
|---|---|
| `selectEncoder()` `:62` | `probeEncoders()` 的枚举过滤 + `runArm()` 选型 `:300-307` |
| `buildFormat()` `:82` | `encodeHeif()` 的 `MediaFormat` 键 `:347-365` |
| `encode()` drain `:111` | `encodeHeif()` 的 EOS/drain 循环 `:397-452` |
| `fillInput()`/`putSample()` `:191`/`:244` | 同名函数 `:484`/`:569` |

⇒ 消除这层重复需要允许改探针；建议放在"探针退役/改造"卡（那时让探针直接调 `Hevc10Encoder`，保留它自己的枚举与回读日志）。

## 7. 探针追加改动（父代理追加要求）

`Heif10BitProbe.kt` —— **只加臂，不改已有臂的行为**：

- **第三臂 `muxer-image-heic`**（`:134-141`）：编码参数与 `main10-p010` 完全相同，唯一区别 = `addTrack` 前 `of.setString(KEY_MIME, "image/heic")` + 补 `KEY_WIDTH/HEIGHT`（`encodeHeif()` `:440-468`）。该臂的 `addTrack/start` 异常被单独 try/catch 记 `phase=heicMime, ok=false, err=…` 后**只废掉这一条臂**；已有臂走 `if (!heicMime) throw t`，保持原异常路径与日志逐字不变。
- **回读器补三项**（原来读不到）：`ftyp.major_brand`、`pixi`（是否落盘 + 位深）、`iloc` item 数（`walkBoxes()` `:761-770`）；`heif10.nclx` 日志新增 `majorBrand/pixi/pixiBitDepth/ilocItems`。
- 新增 `phase=heifStructure` WARN：顶层没有 `meta` ⇒ 直接判定"muxer 写成了 MP4"（`:694-698`）。
- 位深期望口径改为 `tag.contains("8bit") ⇒ 0，其余 ⇒ 2`（`:702`；对已有两臂语义等价，避免新臂误报）。
- **已知残留**：`iinf` 不在 `CONTAINERS` 白名单且比普通 FullBox 多 2 字节 `item_count` ⇒ 探针**读不到 `infe`**（item 数/`item_type`）。本卡未改它（避免动 `walkBoxes` 的容器分派结构）；单测侧解析器已按 `body+6` 正确处理。
