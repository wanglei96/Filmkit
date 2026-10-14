# 实现卡 H2：10bit HEIF 接入真实交付链 + 出包 —— 报告

> 状态：**已完成**（2026-10-14）· 包 = `filmkit-0.11.34-HEIF10.apk`（versionCode 383 / 0.11.34）
> 上位：`docs/rec2020-heif-design.md` §1b 拍板（P1/P2/P3/**R1 选项 A**）· §4 期 A 路线 · §6 R1 落地含义
> 前置：H1 `docs/reports/r-next-heif10-writer-REPORT.md`（容器逐字节零差异：650 B == 原型 golden）
> 交付域铁律（本卡不许改，改了就是返工）：**sRGB 显示域 10bit**，取数点 = 现有 `tex_cap_`
> （OETF 后、LUT/颗粒/锐化之后）；`colr/nclx` = **1/13/1/1**、`pixi` = 3×10bit；
> 默认 JPEG（现有链一字不动）、HEIF 可选；**HEIF 失败 ⇒ 回退 JPEG**（红线：不许因新格式丢照片）。

## 0. 结论速览

| 项 | 结果 | 关键证据 |
|---|---|---|
| ① native 10bit 输出路径 | **落地**：成片 FBO `GL_RGB10_A2` + readback `GL_RGBA/GL_UNSIGNED_INT_2_10_10_10_REV`；JPEG 链逐位不变 | `OutputBitDepth.h:58-100` · 判据 2 两个 CRC 全中 |
| ② RGB10→YCbCr 4:2:0 P010 | **CPU 实现**（BT.709 全范围 + 16 位定点），**12MP 实测 66 ms**；GPU 化选项交用户拍板 | §2.2 · `Heif10Pipeline.kt:84-135` |
| ③ 保存分派 + 设置项 | 设置页「照片格式」两档；prefs `photo_format`；HEIF 失败三级回退到 JPEG | §2.3 · `SettingsScreen.kt:157-176` |
| ④ 运行时读头自检 | 三判据（`meta` / `colr` 1-13-1-1 / `hvcC` 真 10bit）+ `pixi`；不合格**删文件**再回退 | §2.4 · `HeifDeliverCheck.kt` |
| ⑤ 探针第三臂保留 + `iinf` 修复（父代理追加） | 臂逻辑未动；`iinf` 进白名单 + 跳 FullBox+entry_count，`infe` 可见 ⇒ 新增 `heif10.iinf` 事件 | §2.5 · `Heif10BitProbe.kt:625,794-816` |
| 硬判据 1（684→**704** 条全绿） | ✅ 704 tests / 0 failures / 0 errors / 0 skipped | §3.1 |
| 硬判据 2（JPEG 零回归） | ✅ `0x589f8364` / `0x4fa1ebd3` / `0x7c2646ef` 全中 | §3.2 |
| 硬判据 3（四个守卫） | ✅ 全 PASS（含 `--selftest` 16 变异体全抓） | §3.3 |
| 硬判据 4（解包正确性） | ✅ 离线全覆盖（位序/灰阶/平面顺序/容器/自检）；GL 侧待真机自检 | §3.4 |
| 硬判据 5（第三臂可用） | ✅ 结论见 §1 | §1 |

## 1. 真机探针三臂结论（报告顶部要求；数据来自 2026-10-14 vivo X300 / Android 16 / SDK 36 实测）

| 臂 | box 结构 | `colr/nclx` | `hvcC` 位深 | 判定 |
|---|---|---|---|---|
| `main10-p010`（自研容器，探针口径） | `ftyp(heic/mif1) meta(hdlr/pitm/iinf/iloc/iprp) mdat` | 探针按**自己的**线性档请求写 9/8/9/1（与交付域无关，见下方注） | `profile_idc=2` / `bitDepthLumaMinus8=2` / `chromaFormat=1`，106 B | ✅ 真 10bit，容器被 macOS 原生解码器解出 1024×256 正确像素 |
| `ctrl-8bit`（对照臂） | 同上 | 同上（平台映射口径） | `bitDepthLumaMinus8=0` | ✅ 对照组成立 |
| `muxer-image-heic`（官方 muxer 臂） | `ftyp(**) moov free mdat` —— **无顶层 `meta`** | 无 `colr`（写进 `moov` 轨道侧） | 无 `hvcC` 落盘 | ❌ 产出的是 **MP4**，系统相册不认 ⇒ 10bit 必须走自研容器 |

> **口径澄清（易混）**：探针是"编码器/平台能力"诊断，用它**自己的**线性档请求值（9/8/9/1）证明
> `MediaFormat` 的 `color-*` 键真的落到 `colr`；**交付链不用探针的 CICP** —— 交付口径是
> `HeifContainerWriter.Cicp()` 默认值 **1/13/1/1**（R1 选项 A：sRGB 显示域），由
> `HeifDeliverCheck.expected()` 从同一处真源读取（不是第二份常量）。

## 2. 改动落点与四块实现

### 2.1 块 1：native 10bit 输出路径

**真源扩展**（`core/engine/src/render/OutputBitDepth.h`）—— 8bit 档（JPEG 默认链）**一字未动**，
新增第二档 + 唯一分支点：

| 落点 | 行 | 内容 |
|---|---|---|
| `kOutput10BitDepth/Max/InvMax` | `:58-60` | 10 / 1023 / 1/1023（由 bit 派生，与 8bit 同纪律） |
| `kOutput10GlInternalFormat/ReadFormat/ReadType` | `:61-63` | `GL_RGB10_A2` / `GL_RGBA` / `GL_UNSIGNED_INT_2_10_10_10_REV` |
| `kOutputMaxUniform` | `:98` | `"uOutMax"`（dither 除数 uniform 名，CPU 按档下发） |
| `outputInternalOf/outputReadFormatOf/outputReadTypeOf/outputMaxOf` | `:71-82` | **唯一分支点**：一个 `ten_bit` bool 决定三处口径 ⇒ 不会"target 切了、readback 忘了切" |
| `outputUploadTypeOf/isOutputInternal` | `:85-96` | 纹理上传类型的收口（`makeTex` 用），10bit 档 = `2_10_10_10_REV` |

**引擎侧**：

| 落点 | 行 | 内容 |
|---|---|---|
| `FrameRenderer.h` 三入口 | `:172 / :216 / :231` | `submitStill / submitRaw / submitRawBurst` 各加 `bool ten_bit = false`（默认 = 现有 8bit 行为） |
| `ChainCtx.ten_bit` | `:380` | RAW 两条成片路径经 `finishStillChain` 传递（避免改 void 函数的返回契约） |
| `cap_ten_bit_` | `:1190` | **交付档记账**：与 `cap_w_/cap_h_` 同源。只按尺寸记账会让"同尺寸的 JPEG 与 HEIF 连拍"复用错档附件（读回口径不匹配 ⇒ 整幅错色且不报错） |
| `makeTex` | `FrameRenderer.cpp:8160` | `type = outputUploadTypeOf(internal)`（原来只有 8bit/16F 两态，10bit 会落到 `GL_HALF_FLOAT` = 非法组合） |
| `logPassDiag` | `:8175-8215` | 参数 `bool half_float` → `GLenum internal`；10bit 档按 `2_10_10_10_REV` 读并解包打印 `bits:10`（用 8bit 口径读 10bit 附件会被驱动拒绝，把 `GL_INVALID_OPERATION` 误报成"渲染失败"） |
| 成片 target / readback | `:8680 / :8700`（YUV 静拍）、`:11307 / :11327`（RAW 静拍） | `outputInternalOf(ten_bit)` / `outputReadFormatOf(ten_bit), outputReadTypeOf(ten_bit)` |
| JPEG 零回归 | `:8681 / :11334` | `outputInternalOf(false)` = `kOutputGlInternalFormat`、readback = 原 8bit 对；编辑器路径恒 8bit 且能把 10bit 附件换回来 |
| `diagStatsU8` 口径 | `:11334` | 10bit 档**跳过** `D5-out-u8`（该统计逐字节 `/255`，对打包的 2_10_10_10_REV 无意义）；8bit 档不变 |

**dither 按位深重标**（设计 §2.5「本期最易漏项」）：`kFsOut` 的 dither 除数改为 CPU 下发的
`uOutMax`（`FrameRenderer.cpp:4759 / 5195`），8bit 传 255、10bit 传 1023。
**零回归的关键**是 shader 里的**兜底** `uOutMax > 0.0 ? uOutMax : 255.0` —— 离线校验脚本
（`check-halation` / `check-softglow` / `engine-shader-offline`）**不传**该 uniform，靠兜底得到
与改动前**逐位相同**的结果（判据：halation 的 7b「kFsOut vs kFsStyle 8bit 逐位相同」通过，
差异 72/589824 与改动前一致）。若不做这一步，10bit 会沿用 8bit 幅度（±1/255 = ±4 LSB@10bit），
把 10bit 抖回 ~8.6bit 有效精度 = **静默降位**。

**GL 侧绝对自检**（`selfCheck10BitReadback`，`FrameRenderer.cpp:8219-8318`）：
建 1×6 的 10bit 交付 FBO → 分段 `glClear` 写**已知浮点值** → 按交付口径 readback → 与
"浮点 → 10bit 定点四舍五入"的规范期望逐点比对（容差 1 LSB）：

| case | clear 值 | 期望 (R,G,B,A) | 抓什么 |
|---|---|---|---|
| black | 0,0,0 | 0,0,0,3 | 黑电平 |
| R-full | 1,0,0 | 1023,0,0,3 | **R 在低位段**（LSB-first） |
| G-full | 0,1,0 | 0,1023,0,3 | G 在 bit10..19 |
| B-full | 0,0,1 | 0,0,1023,3 | B 在 bit20..29 |
| ramp | .25,.5,.75 | 256,512,767,3 | 线性 |
| white | 1,1,1 | 1023,1023,1023,3 | 满量程 |

**为什么不用上传→下载往返**：同一种错误布局在往返里会互相抵消（写进去按错布局解释、读出来又按
同一错布局还原）⇒ 往返"通过"而实际交付错色。所以必须用已知值做**绝对**比对。
自检结果进日志 `heif10.selfcheck`（与探针 `heif10.*` 同命名空间，用户 grep 一条即可）；
**失败 ⇒ 三条成片入口直接拒绝本次 10bit 交付**（`selfCheck10BitOnce()`，`:8594 / :10679 / :12858`）
⇒ 调用方按回退链降级（fail-closed，绝不静默交付错位数据）。

**JNI / Kotlin 桥**：`Bridge.cpp:622 / 640 / 718 / 773 / 800 / 869`（三个入口加 `jboolean ten_bit`
+ `render.single_done` / `render.burst_done` 各加 `bits` 字段）；`EngineBridge.kt` 三个外部声明
各加 `tenBit: Boolean = false`。

### 2.2 块 2：RGB10 → YCbCr 4:2:0 P010

| 落点 | 行 | 内容 |
|---|---|---|
| `unpackRgb10` | `Heif10Pipeline.kt:53-77` | 小端 uint32 → `r\|g<<10\|b<<20`；位序 = `2_10_10_10_REV` 的 **LSB-first**（与 P010 的 MSB 对齐是两套约定） |
| `toP010` | `:84-135` | BT.709 **全范围** + 4:2:0 盒式平均 + P010 图案 `y\|cb<<10\|cr<<20` |
| 系数 | `:37-46` | KR/KG/KB = 13933/46871/4732（**和 = 65536** ⇒ 灰阶恒等）；KCB/KCR = 35319/41615（16 位定点） |
| 交付组装 | `:150-166` | `toHeifFile` = 解包 → P010 → `Hevc10Encoder.encode` → `HeifContainerWriter.build(Cicp(), 10)` **单一组装点** |
| 回退转换 | `:168-181` | `toRgba8`：`round(v×255/1023)`（不用 `>>2` 截断，避免暗部系统性偏暗） |
| 码率 | `:137-138` | `w·h·30·2` 夹到 [8 Mbps, 240 Mbps] —— **待真机标定**（见 §5） |

**矩阵一致性（改一处必须改另一处）**：`Heif10Pipeline` 的 KR/KG/KB ⇄
`HeifContainerWriter.Cicp.matrix = 1`(BT.709) ⇄ `Hevc10Encoder.putSample` 的 P010 打包。
三处的交叉引用写在 `Hevc10Encoder.kt:40-55`（本卡补的注释）与 `Heif10Pipeline` 文件头。

**为什么是 CPU 而不是 GPU pass**（判定 + 数据 + 选项交用户拍板）：
1. **实测成本**：12MP（4000×3000）解包 25 ms + RGB→YCbCr/P010 40 ms = **66 ms**（JVM 实测，
   `Heif10PipelineTest` 的 `[H2-bench]` 行；ART 待真机复核）。对照现有链：`Bitmap.compress`
   JPEG q100 是 340–400 ms（`CameraController.kt:279` 注释）⇒ CPU 段不是瓶颈。
2. **GPU 方案收益有限**：`glReadPixels` 不支持 P010 输出，GPU 做完色彩转换后仍要 CPU 侧做
   16 bit MSB 对齐打包；且需要新增 shader ⇒ 触碰 shader 冻结契约
   （`validate-shaders.sh` 逐字提取 + `FILM_SRC_BASELINE` 锚定）与新的平面纹理/两次 readback。
3. **可验证性**：位对齐与平面顺序这两项最容易错的东西，CPU 版能在 JVM 离线 100% 覆盖
   （硬判据 4），GPU 版只能真机试。
⇒ **本卡取 CPU 版**；GPU 化选项（成本：新 shader + 2 张平面纹理 + 2 次 readback + 仍需 CPU 打包；
收益：预计省 ~40 ms/张）**交用户拍板**，不在本卡擅自实施。

### 2.3 块 3：保存分派 + 设置项

| 落点 | 行 | 内容 |
|---|---|---|
| `PhotoFormat` | `PhotoFormat.kt:18-39` | 枚举 + prefs 键 `photo_format`（值 `jpeg`/`heif`）；**缺失/脏值 = JPEG**（老用户行为不变） |
| 设置页 | `SettingsScreen.kt:157-176` | 「照片格式」两档循环（沿用同文件既有 `SettingValueRow` 形态，不新造控件）+ `settings.photo_format` 日志 |
| 交付档快照 | `CameraController.kt:492-496` | `stillTenBit` + `tenBitForCapture()`；**在下发点冻结**（与 `stillRot/stillAspect` 同型）——渲染要 3~4 s，期间用户改设置不得改变**这一张** |
| 下发点冻结 | `:2620 / :2773 / :3003` | `submitStill` / `tryProcessRaw` / `tryProcessBurst` 三处各一行 |
| 透传 | `:2661 / :2674 / :2681 / :2813 / :2826 / :2833 / (burst 3 处)` | 9 处 render client 调用补 `tenBit = stillTenBit` |
| 保存分派 | `:2614` | `finishStillCapture`：10bit 流 → `queueHeifSave`；8bit 流 → **原 `queueJpegSave`（一字不动）** |
| HEIF 落盘 | `:3499-3616` | 编码 → 写 MediaStore(`image/heic`)/File → **读头自检** → 快照 |
| 回退链 | `:3617-3640` | ①编码失败 ②落盘失败 ③读头自检不合格 ⇒ **删掉刚写的文件** + 用同一次 readback 降位走 JPEG（**不重渲染**） |

**回退链的三条落点都被日志钉住**（用户导出日志即可看到走了哪条）：
`photo.heif_queued` → `photo.heif_encoded` → `photo.heif_saved`；
失败侧 `photo.heif_failed`（`stage=encode|write`）/ `photo.heif_head_check_failed` +
`photo.heif_discarded` + `photo.heif_fallback_jpeg`。

**8bit HEIF 回退本卡不做**（按任务书允许条款明写）：androidx `HeifWriter` 是另一条 API、
只能写 8bit（设计 §2.1），在"默认 JPEG"前提下没有收益 ⇒ **回退链只保留"回退 JPEG"**。

### 2.4 块 4：运行时读头自检（防"静默交付错文件"的唯一设施）

`HeifDeliverCheck.kt`（只读文件**前 64 KiB**，`ftyp`+`meta` 恒在其中；自检 < 1 ms）：

| 判据 | 期望 | 不合规的后果（都**不报错**） |
|---|---|---|
| 顶层 `meta` | 存在 | muxer 走 MP4 老路 ⇒ 相册不认（设计 §2.7 实测） |
| `colr/nclx` | **1/13/1/1** | 下游按错误色域/传输函数解释 ⇒ 偏色 |
| `hvcC.bitDepthLuma/ChromaMinus8` | 2 | 静默 8bit 交付 |
| `hvcC.profile_idc` | 2（Main10） | 不是 Main10 ⇒ 语义与声明不符 |
| `pixi` | 3×10bit | 部分解码端读不到 10bit（探针里作为独立字段观测） |

期望值**不抄常量**：`expected()` 直接取 `HeifContainerWriter.Cicp()` 默认值（改交付口径只改一处）。
解析器**复用**真机探针的 box 遍历器（`Heif10BitProbe.parseBoxes`）—— 再写一份就是第二个真源。

### 2.5 父代理追加：探针 `iinf` 修复（第三臂随本包给用户跑）

| 落点 | 行 | 内容 |
|---|---|---|
| 容器白名单 | `Heif10BitProbe.kt:625-626` | `iinf` 加入 `CONTAINERS` **且**入 `FULLBOX_CONTAINERS`（此前根本进不去 ⇒ `infe` 永远读不到） |
| entry_count 跳过 | `:810-817` | `iinf` 的 FullBox(4) 之后还有 **entry_count**（v0 = 2 B / v1+ = 4 B）；不跳它，遍历器把 `0x0001` 当 size ⇒ `break`，整棵子树静默丢失 |
| `infe` 解析 | `:794-808` | v2：`item_type(4)` + `item_name`(UTF-8 NUL)；v0/v1：无 item_type（记占位 + name） |
| `cstr` | `:824-828` | NUL 结尾 UTF-8 读取 |
| 新事件 | `:707-714` | `heif10.iinf`（`itemCount/itemTypes/itemNames/ilocItems`）—— **只在解析到 item 时输出** |

**既有两臂的日志字段逐字未变**：本次对探针的全部改动都落在 iinf/infe 相关代码 + 一条**新增**的
独立事件；`heif10.nclx` / `heif10.probe` 的 `kv(...)` 一个字段都没碰（这两条事件的字段清单与
`Heif10BitProbeTest` 的 4 条**原有**解析器测试一一对应，那 4 条在本卡仍全绿 ⇒ 解析器行为未变）。
`iinf` 修复的回归守卫 = 新增的 3 条测试（v2 类型+名字 / v0 与 v1 两种 entry_count 宽度 /
无 iinf 时不误报）。

## 3. 五条硬判据实测值

### 3.1 判据 1：构建 + 单测

```
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
⇒ BUILD SUCCESSFUL
```
实测：**tests = 704 / failures = 0 / errors = 0 / skipped = 0**（67 个测试类）。
基线 684 条**全绿**；新增 20 条 = `Heif10PipelineTest` 8 + `HeifDeliverCheckTest` 8 +
`Heif10BitProbeTest` 的 iinf 4 条；H1 的 12 条容器测试仍全绿。
（`filmkit-0.11.34-HEIF10.apk` 由 `assembleDebug` 产出，42,341,164 B。）

### 3.2 判据 2：JPEG 路径零回归

```
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
    --roi 1800,2100,600,1000 --out /tmp/h2-e0 --engine 0
python3 tools/engine-shader-offline.py ... --out /tmp/h2-e1 --engine 1
```

| 产物 | 期望 | 实测 | 结论 |
|---|---|---|---|
| `film_d5.npy` crc32（engine 0） | `0x589f8364` | **`0x589f8364`** | ✅ 逐字节零回归 |
| shader crc32（=`kFsFilm` 整体） | `0x4fa1ebd3` | **`0x4fa1ebd3`** | ✅ 冻结契约未动 |
| `film_d5_sigmoid.npy` crc32（engine 1） | `0x7c2646ef` | **`0x7c2646ef`** | ✅ 默认档（P2）基线未动 |

（附带 sha256：`ce1c9a90…e5391` / `c166153c…935ce`。`kFsOut` 的 dither 改动**不影响**本判据 ——
离线脚本只跑 `kFsFilm`（D4→D5），不执行 `kFsOut`。）

### 3.3 判据 3：四个守卫（全部 PASS）

| 命令 | 结果 |
|---|---|
| `python3 tools/check-bitdepth-single-source.py` | **PASS**：8bit 档自洽（bit=8/255/`GL_RGBA8`）+ 10bit 档登记（bit=10/1023.0/`GL_RGB10_A2`,`2_10_10_10_REV`/`uOutMax`）+ 落点全引用真源 + 冻结计数 8 未变 + 10bit 自检与 3 处 fail-closed 在位 |
| `python3 tools/check-bitdepth-single-source.py --selftest` | **自检通过**：16 个变异体（含 4 个**本卡新增**：kFsOut 除数写死/兜底常量被改/uniform 声明被删/10bit 整档被删/自检被删/fail-closed 被摘）**各自被抓**，且未变异体全绿（不是"永远红"的假守卫） |
| `bash tools/validate-shaders.sh` | **全部 shader 校验通过（含引擎 1 sigmoid）** |
| `python3 tools/check-diag-fields.py` | **全部通过**（`logPassDiag` 签名改动未破坏任何字段位置判据） |
| `python3 tools/check-filmparams-contract.py` | **PASS**（98/99 槽四处一致 —— 本卡未动 `FilmParams`） |

**纪律附加（本卡必须自证的"没破坏既有诊断"）**：

| 命令 | 结果 |
|---|---|
| `python3 tools/check-halation.py` | **全部通过**（含判据 7b：`kFsOut(srgbDecode(D))` vs `kFsStyle(D)` 8bit 内逐位相同，差异 72/589824 —— 与改动前**同一数值** ⇒ dither uniform 化零回归）；其 S10 静态锚点已同步为新调用形态 `..., true, false, outputMaxOf(` |
| `python3 tools/check-softglow.py` | **全部通过**（4 条变异体自检全抓） |
| `python3 tools/domain-contract-check.py` | RC=1「需要补域契约」—— **既有状态，非本卡回归**：在 `git worktree` 的 **HEAD（未改动）** 快照上同样 RC=1 且结论相同（pass 数 86→88 = 本卡新增的 2 个自检函数） |

### 3.4 判据 4：解包正确性（你自己造可验证的用例）

**离线（JVM）已覆盖**（`app/src/test/java/com/filmkit/app/heif/Heif10PipelineTest.kt` 8 条 +
`HeifDeliverCheckTest.kt` 8 条）：

| 判据 | 测试 | 抓什么 |
|---|---|---|
| 位序（LSB-first） | `解包 2_10_10_10_REV 位序：R 在低位段` | 单通道拉满的 6 个已知值 + 逐通道断言（R/B 互换会红） |
| 字节序 | `解包按小端 uint32 读` | bit30 + bit0 的极值 |
| 长度防护 | `长度不符必须抛` | 静默截断 |
| **灰阶严格单调线性** | `灰阶严格单调线性且色度恒为 512` | **一次测掉位对齐 + 平面顺序**：Y 逐点恒等于输入 v 且严格递增；块左上 Cb=Cr=512；非左上高位为 0 |
| 色度方向 | `色度方向正确：R 强则 Cr 高 Cb 低 / B 强反之` | BT.709 的 Cb/Cr 对映（写反 = 整幅串色） |
| 端点 | `白点与黑点落在全范围端点上` | 全范围 0 / 1023 / 零偏 512 |
| 容器 + 读头联动 | `HeifDeliverCheckTest` 8 条 | 本仓写入器产物**通过**；线性档(9/8/9)、limited range、8bit 编码、非 Main10、MP4 结构、缺 pixi **各自被拒** |
| **12MP 实测** | `12MP 耗时实测` | `[H2-bench] 12MP(4000x3000): unpack=25ms rgb2yuv420p010=40ms total=66ms` |

**真机才能做的一步**（已给出自动设施，不是"待人工判断"）：
`GL_RGB10_A2` 的 readback **绝对**验证 = native 自检 `selfCheck10BitReadback`（§2.1 的六个已知 case），
在**首次** 10bit 拍摄时自动执行一次并写 `heif10.selfcheck`；不通过就拒绝 10bit 交付（回退 JPEG）。
⇒ 用户只要拍一张 HEIF，日志里就有"这台机器的 10bit 位序对不对"的**定论**（见 §5 清单第 1 条）。

### 3.5 判据 5：探针第三臂保留可用

- 臂逻辑未动（`arms = main10-p010 / ctrl-8bit / muxer-image-heic`，`Heif10BitProbe.kt:134-140`）；
- 第三臂随本包一起给用户跑；结论口径 = `heif10.nclx`（`majorBrand/topLevel/colr/hvcC`）+
  **新增** `heif10.iinf`（官方 muxer 到底写了哪些 item —— 这正是第三臂此前答不了的那一问）；
- box / `colr` / `bitDepth` 的三臂结论见 **§1**。

## 4. 用户验证步骤

1. **装包**：`filmkit-0.11.34-HEIF10.apk`（覆盖安装即可，prefs 保留；默认仍是 JPEG ⇒ 不装不改也不影响）。
2. **设置**：设置 → 「拍摄」区 → **「照片格式」点一下 → 显示「HEIF（10bit）」**；
   顺手在该页「实验与诊断」里点一次 **10bit HEIF 探针**（三条臂都会跑，几秒后出结论）。
3. **拍照**：回相机拍一张（M 档 RAW 或普通直拍都行）；成功后相册里应出现 `.heic`
   （`Pictures/FilmKit/FILMKIT_*.heic`），再建议拍一张 JPEG 做对照。
4. **导出**：设置 → 「日志」 → **导出 JSONL**，把 `filmkit-logs-*.jsonl` 与那张 `.heic` 一起回传
   （日志里关键事件：`heif10.selfcheck` / `photo.heif_encoded` / `photo.heif_saved` /
   `photo.heif_head_check_failed` / `photo.heif_fallback_jpeg` / `heif10.iinf`）。

## 5. 未覆盖项与待真机验证清单

**未覆盖 / 已知取舍（本卡明写，不藏）**：

1. **GPU 版 RGB→YCbCr 未做**：CPU 实测 66 ms/12MP（§2.2 第 3 点给了成本/收益与选项）⇒ **待用户拍板**。
2. **8bit HEIF 回退未做**：回退链只到 JPEG（任务书允许条款，理由见 §2.3）。
3. **HEIF 不写 EXIF**：自研容器没有 Exif item，而 `ExifInterface` 对 HEIC 的写入会**重写文件** ——
   在"照片优先"红线面前不为元数据冒这个险（编辑器仍能靠 `StillSnapshotStore` 找到拍摄参数快照）。
4. **10bit 档跳过 `D5-out-u8` 统计**：该统计是逐字节 8bit 口径，对打包的 2_10_10_10_REV 无意义
   （8bit 档一字未动；10bit 档的对应诊断由 `heif10.selfcheck` 与 `render.diag` 的 `bits:10` 承担）。
5. **码率未标定**：`defaultBitrate = w·h·30·2`（夹 [8, 240] Mbps）是**估算值**；单帧 I 帧的实际质量
   由编码器 QP 决定，必须用真机成片与 JPEG q100 对照后才能定（清单第 3 条）。
6. **`domain-contract-check.py` 仍是 RC=1**：既有红（HEAD 同样红，§3.3），不是本卡引入。

**待真机验证清单（每条都给出"看哪个日志/怎么判"，不需用户手工分析）**：

| # | 待验证 | 怎么看 | 不通过会怎样 |
|---|---|---|---|
| 1 | `GL_RGB10_A2` + `2_10_10_10_REV` 在本机驱动上的**绝对**位序 | `heif10.selfcheck` 的 `ok:true` + 六个 case 的 `got/want` | `ok:false` ⇒ 三条成片入口**拒绝 10bit**，自动回退 JPEG（不会交付错色片） |
| 2 | 10bit 档的 pass 链是否正常（无 FBO 不完整/驱动拒绝） | `render.diag` 的 `stage:"pass3_still","bits":10` | 该行缺失/`glErr` 非 0 ⇒ 报告回传 |
| 3 | 12MP 单帧 HEVC Main10 的**耗时与成片质量**（码率标定） | `photo.heif_encoded` 的 `ms/bytes` + 与 JPEG q100 同场景目视/直方图对照 | 体积过小/高光肩部出现台阶 ⇒ 调 `defaultBitrate` |
| 4 | 交付链落盘文件的读头三判据 | `photo.heif_saved` 的 `detail`（含 `nclx:[1,13,1,1]` / `profileIdc:2` / `bitDepthLumaMinus8:2`） | 不合格 ⇒ 自动删文件 + 回退 JPEG（`photo.heif_discarded`） |
| 5 | **下游解释**（判据 F）：系统相册 / 微信 / PC 各看一次 | 是否偏色/变暗（人工，设计 §4.5 F） | 若偏色 ⇒ 说明 CICP 被下游忽略，需回设计拍板（换档或补 SDR 备份） |
| 6 | 第三臂 `muxer-image-heic` 现在能看到哪些 item | `heif10.iinf` 的 `itemTypes/itemNames`（本卡修好 `iinf` 后**首次**可见） | 若为空/仅 MP4 结构 ⇒ 坐实"官方 muxer 不产 HEIF 图片 item" |
| 7 | ART 下 CPU 段耗时（JVM 66 ms 的对照） | `photo.heif_encoded` 的 `ms` 与 `render.single_done` 的时间差 | 显著劣化 ⇒ 触发 §5 第 1 条（GPU 化）的拍板 |

## 6. 出包核对

| 项 | 值 |
|---|---|
| 包路径 | `/Users/wl/Downloads/胶片相机/filmkit-0.11.34-HEIF10.apk` |
| 大小 / 版本 | 42,341,164 B · versionCode **383** · versionName **0.11.34**（`app/build.gradle.kts:87-88`，+1 已落） |
| native 进包核对 | APK 内 `lib/arm64-v8a/libfilmengine.so` md5 = `694c5132828f20b572f440526069513f` **==** 构建产物 `app/build/filmengine/jniLibs/arm64-v8a/libfilmengine.so` md5（含 `uOutMax` / `heif10.selfcheck` 字符串 ⇒ 本卡 native 改动**确实进包**） |
| Kotlin 进包核对 | APK 的 dex 内含 `Lcom/filmkit/app/heif/Heif10Pipeline;` / `HeifDeliverCheck;` / `PhotoFormat;` 与 `Heif10Pipeline.kt` / `HeifDeliverCheck.kt` 源文件信息 |
| 出包命令 | `rm -rf app/build/outputs/apk && JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest`（同一命令内复跑判据 1；出包后再次核对 so md5 + 704 条测试） |

## 7. 交付纪律自证

- **先落盘骨架**：本报告在各块开工前落盘，逐块回填（无"预算耗尽零落盘"）。
- **小步**：块 1（native）→ 编译 + 位深守卫 + 两个 shader 逐位守卫 + 判据 2 → 块 2/4（CPU 段 +
  自检，JVM 测试）→ 块 3（设置/分派/回退）→ 出包。每块自带判据。
- **改动不破坏既有诊断**：探针两臂日志字段未变（§2.5）；`check-diag-fields` / `@domain` 台账 /
  halation / softglow / filmparams 全部复跑（§3.3）。
- **未擅自降级**：dither 未沿用 8bit 幅度（做了重标 + 守卫锚定）；未把 10bit 静默替换成 8bit；
  8bit HEIF 回退与 EXIF 的取舍**写进报告**而不是悄悄省掉。
