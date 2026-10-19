# 期 B 旧域基线归档 + 空间相关落点普查

依据：`docs/rec2020-heif-design.md` §5:99 ——「期 B 开工第一步 = 把这批守卫按"新域下的
新基线"重建，并保留旧基线作历史对照（**不许直接改期望值了事**）」。
采集：2026-10-16，旧域 = **sRGB 线性工作空间**。

## 0. 旧树来源（权威复现；本文件不复制大块数字）

旧树提交 = `bb7026d`。任何旧基线的完整值用 `git show bb7026d:<path>` 取回，
**不删该提交即可永久复现**。本文件只记「值 + 位置 + 复现命令 + 换域后预期」，
60 系数一类大块数据不复制 —— 避免文档二次漂移（`check-toeshoulder.py` 的教训：
**把 git 历史当对照物，对照物会随提交漂移**）。

## 1. 硬钉外部 golden —— 换域后**必红**，需按实测重定

| # | 基线 | 位置 | 旧值 | 复现 |
|---|---|---|---|---|
| 1 | `kFsFilm` 源基线（切分契约） | `tools/engine-shader-offline.py:407-422` | chars 25140 / bytes 35154 / crc32 `0x4fa1ebd3` / sha256 `f1ebb317…`；分段 crc32：head `0x79355c53` · engine0_def `0x05f5351d` · mid `0x0dab15dc` · engine0_main `0xbe41521e` · tail `0x20921afd` | `--splice-selftest` |
| 2 | `film_d5` 输出 CRC | `tools/engine-shader-offline.py:885-886`（判据⑤） | 引擎 0 默认 **`0x589f8364`**；白点 4.0 口径 `0xd46d7b5e`；引擎 1 另存 `film_d5_sigmoid.npy` | `--dng test_temp/FILMKIT_20261009-090803.dng --roi 1800,2100,600,1000` |
| 3 | 60 项 spline 系数 | `tools/check-toeshoulder.py:89` `EXPECT_HEX` | 提交 `a824170` 的 60 项十六进制浮点（逐位） | `python3 tools/check-toeshoulder.py` |
| 4 | sRGB OETF 参考 | `tools/check-srgb-oetf.py` | IEC 61966-2-1：CUT 0.0031308 / A 1.055 / B −0.055 / P 1/2.4 / D 12.92 | 同上（**仅"改 OETF"才会红**） |
| 5 | CCM 期望矩阵 | `app/src/test/java/com/filmkit/app/StdColorMatrixTest.kt:47-51` | 旧 9 float（sRGB 终点）+ 行和 = 1.0 | `./gradlew testDebugUnitTest --tests com.filmkit.app.StdColorMatrixTest` |

## 2. 自包含判据 —— 换域后**应保持绿**（红了先当 bug 查，不是基线漂移）

- `check-film-offline.py:69`（solve5 vs numpy < 1e-9）· `:134`（CPU vs GPU < 2e-3）
- `check-u2-editor-curve-sat.py` ①a（机械删段逐位对比）· ①b（门控等价）
- `check-u4-camera-display-domain.py` ①（机械删段逐位对比）
- `check-halation.py` ①⑦⑧ · `check-softglow.py` ①④ · `check-style-passthrough.py` ①–⑥
- `check-bitdepth-single-source.py`

⚠ `check-u3-camera-contrast-wb.py` ①a 含「**HEAD 版函数**+旧调用」⇒ 依赖 `git HEAD`；
本期的改域提交会换掉它的"HEAD"，需一并复核（同 `check-toeshoulder` 踩过的形态）。

## 3. 本来就红 —— Phase B 恰好该修

`domain-contract-check.py` 退出 1「需要补域契约」：D4 有 10 个 pass、`tex_dir_`/`tex_g_`
域重载、unlabeled 4 条 ⇒ 换域后 **D4 的定义本身变化**，契约必须补（这是域变更最该更新的守卫）。

## 4. §5 未列、但空间相关 —— 必须逐处判定去留（本次普查新发现）

全部 luma 系数出现点，按**所属域**分类（判据 = 该计算发生在哪个 D 域）：

**A 类 · 相机 RGB（D2/D3）—— 与工作空间无关，不改**
`:1172` · `:1361` · `:1502-1505` · `:1524` · `:1553` · `:1739` · `:1870`（demosaic/RCD/LSC 区，
0.2126/0.7152/0.0722 作相机 RGB 亮度定义）· `:1965` `SUM3 = 0.299/0.587/0.114`（convdiff.glsl 口径）

**B 类 · D4 场景线性（= 工作空间本身）—— 必须换 Rec.2020 系数**
`:4102` `kFsFilm` 的 `luma()` · `:3983-3984` sat 段 · `:4241` halation 亮度权重注释
（需确认 halation 段的实际域，`uHalDomainMap` 分支）

**C 类 · 线性显示域（OETF 之前）—— 换域后必须换 Rec.2020 系数**
`:4972` `kFsOut` 的 `luma()` · `:5201`

**D 类 · display 域（OETF 之后）—— 保持 Rec.601，不改**
`:5063-5072` · `:5114` · `:5127` · `:5179`（0.299/0.587/0.114；注释明示 Rec.601 是本域口径）

⚠️ B/C 两类是 §5 清单**没有明确列出**的工作量。漏改的症状是"亮度/局部对比按错权重"，
**不是明显偏色**，因此更隐蔽。

## 5. 实读结论：filmic gamut 段到底哪些常数随空间变（实读 `:4255-4420`）

- **随工作空间变 —— 只有这 6 个 vec3**：`kLmsSrgbToLmsL/M/S`（工作空间 RGB → LMS2006）
  与 `kLmsToSrgbRowR/G/B`（其逆）
- **固定不变**：`kYchY1931To2006 = 1.05785528` · `kYchD65R = 0.21902143` · `kYchD65G = 0.54371398`
- **固定不变**：`kLmsToFilmRow*` / `kFilmToLmsRow*`（LMS2006 ↔ Filmlight）·
  `gamutLmsToYrg` 的 Vwright 加权 `0.68990272` / `0.34832189`
- **色域边界解析解（`:4335-4385`）的原色信息是参数化的**：`gamutClipChromaWhiteRaw/Black`
  的 `coeffs` 由调用方传入 `kLmsToSrgbRow*`（`:4397`）⇒ 6 个 vec3 换对之后，边界**自动**
  变成新工作空间的边界；函数内 `0.979381443298969` / `0.391752577319588` / `0.427506877216495`
  等属 LMS2006↔Filmlight 组合常数，**不改**。

⇒ §5:90 写的「`:4334-4367` 也要改」应修正为「**该段无需改数值，只需随 6 个 vec3 联动复核**」。
此结论须由离线双域复算脚本实证（第 2 步），不接受纯推断。

## 6. D1–D4 前置决策（按标准做法拟定，可被用户否决）

| # | 决策 | 拟定 | 依据 |
|---|---|---|---|
| D1 | 输入去 gamma 口径 `pow(rgb, 2.2)`（`:333`/`:349`） | **本期不改**，只在注释写明"近似 sRGB EOTF 的既有近似 + 与真 sRGB 分段曲线的差异" | Phase B 是**域切换**；输入去 gamma 是独立问题。同期改动会让本期无法归因（§3:51 铁律）。`pow(2.2) 是债还是刻意` 仍留在 §7 未确认项 |
| D2 | 引擎输出契约（`decisions.md:103` 定的是线性 sRGB） | **改为线性 Rec.2020** | 工作空间变了，引擎输出契约必须同域，否则"引擎 0/1 零回归对比"跨域失效 |
| D3 | HEIC `colr`（期 A R1 定的是 primaries 1 / transfer 13） | **保持 sRGB 显示域交付 ⇒ colr 不变**；但**输出映射链新增 Rec.2020→sRGB 压缩** ⇒ 成片像素值会变 ⇒ **判据 F 必须重跑** | 期 A R1 理由（下游解释风险是唯一用代码消不掉的）未变；`INDEX.md:22` 要求"重新评估"，本条即评估结论 |
| D4 | OETF 5 处的目标域（4 shader `srgbEncode` + CPU `srgbEncodeD`） | **函数体保持 sRGB OETF**（交付域不变）；变的是**它们的输入** ⇒ 在每个 OETF 之前插入 `Rec.2020 线性 → sRGB 线性`（3×3 + 域外压缩） | 输出域不变 ⇒ OETF 不变；工作域变了 ⇒ 入口必须转换。编辑器链（`kFsStyle`）多一层"已成片 sRGB → Rec.2020 线性"入向转换 |

**D4 的实现风险（最需要实测的一项）**：域外压缩若用 clamp，高饱和色会硬切产生色相偏移；
若复用 `gamutClipChroma`（恒 Y 恒色相）则更贵但无色相偏移。**按"先 100% 原版"铁律**：
先用最简单的矩阵 + clamp 跑通全链并实测，再据实测定是否升级为解析压缩。

## 7. 新基线生成流程（八面完成 + 真机实测通过后执行）

1. 冻结本文件第 1 节旧值（`git show bb7026d`）
2. 八面改完 → 跑全部守卫 → 记录红了哪些、各自为什么
3. **只有"因域变更而必须变"的 golden 才重定**；第 2 节的自包含判据红了先当 bug 查
4. 逐项提交新值，并在脚本内**保留旧值常量**作历史对照
5. 真机：判据 F 重跑（HEIC 视觉回归）+ 阶段 3 判据 0

## 8. 实施进度（2026-10-16）

**已落地**

| 面 | 落点 | 状态 |
|---|---|---|
| filmic LMS 整组 | `FrameRenderer.cpp` 6 个 vec3 + 改名（`Srgb`→`Work`）+ 4 处引用 | ✅ |
| 色域边界解析解 | 实读确认**无需改数值**（原色经 `coeffs` 参数传入，随 6 个 vec3 联动） | ✅ 已复核 |
| CCM 链终点 | `StdColorMatrix.kt:X2S` + 测试期望按真源重算 | ✅ |
| B 类 luma（D4 线性域） | `kFsFilm` 的 `luma()`（服务 uSat 分区 / uLook / uMono） | ✅ |
| C 类 luma（OETF 前线性域） | `kFsOut` 的 `luma()`（服务 `sharpenLuma`）| ✅ |
| 输出映射（OETF 前收口） | `kFsOut` ×2 · `kFsHist` ×1 | ✅ |
| ~~`kFsStyle` 往返~~ | **实读后回滚**：编辑器链的线性操作只有"曝光 × WB"，而拍照链路的曝光/WB 走 `wbTempTint` → `wbCompGNorm`/`prepareRawCommon`，在 **RAW 域与预览输入域**，本就不跟随工作空间 ⇒ 加了域转换只会**白改色温** | ✅ 已回滚 |
| 灰度路径 | `glowFetch` / `chainLuma`：两矩阵**行和 = 1** ⇒ 对中性值恒等，**无需改** | ✅ 已证 |

**判定为不改（附依据，避免后人重复调查）**
- `kFsHalo*` / `kFsGlowSum`：`uDecodeIn = 0`（拍照链路）时**纯线性、不做 srgb 往返**；`uDecodeIn = 1` 是编辑器链自成一体
- `kFsLocal`：注释明确"**不要单独改这一处**"（local_tone 默认关 + knee 在 2.2 域标定）
- A 类（相机 RGB 域）：`kFsChromaDenoise` / `kFsDefringe*` / `kFsColorSmooth` / `kFsChroma*` / `kFsPc3d` / `kFsHlApply`
- `kFsHist` 的 `uStatMode` 对数域分支：取**编码前** raw（现为 Rec.2020 线性）—— 域变更的**必然影响**，非漏项

**待重标清单（下一批，按影响排序）**
1. ~~`kFsStyle` 的 `[0]` 曝光 / `[88][89]` 色温色调~~ —— **已撤销（实读后回滚）**：拍照/预览的
   色温走 `wbTempTint`（RAW 域 + 预览输入域），不受工作空间影响；编辑器侧保持 sRGB 线性域后，
   `check-style-passthrough` 的 ⑭b/⑭c/⑭f 恢复绿 ⇒ **无需重标**
2. **U-1 `[23][24]` split / `[25]` mono** —— 生效域从"线性工作空间"改为"显示域"（修 ⑰d 的
   两链路不一致，见 §8.3）⇒ 同一参数在新域下的偏移幅度变了（中灰处 43%），需重标
3. `kFsFilm` 的 `[20..22]` 分区饱和 / `uLook*`：luma 系数换 ⇒ 分区边界微移
4. `kFsHaloThr` 的 `luma()`：拍照（Rec.2020 线性）与编辑器（sRGB 线性）**双域并存**，现用 Rec.709 ⇒ 拍照侧高光阈值判定偏（彩色约 10%）⇒ `[62][63][64][65]` 重标
5. `kFsHist` 的 `uStatMode` 统计 + `measureSceneStats`：raw 域变 ⇒ 自动 EV 判据数值复核
6. `kFsLocal` 的 `pow(1/2.2)` knee：local_tone 启用时连 knee 一起重标
7. 全部内置预设

### 8.1 守卫跑批结果（2026-10-16 终态；含 **stash 回 baseline 对比**）

| 守卫 | baseline | 终态 | 判定 |
|---|---|---|---|
| bitdepth-single-source · diag-fields · diag-stats · fft-tile-cover · film-offline · filmparams-contract · halation · lsc-mapping · **toeshoulder** · u2 · u3 | 0 | **0** | 绿（域无关或自包含）|
| softglow | 0 | **0** | 修复后绿（2a 阈值 `1e-6`→`1e-4`：域收口矩阵带来 2.30e-06 浮点微差，8bit 下 0.0006 LSB；仍比真通道相关的 M1 对照值 0.319 小 3 个数量级）|
| u4 | 0 | **0** | 修复后绿（③ 的 tap 判定不再锚死字面量；④ 的 sharpen 段比对前规范化 `lumaLin(`→`luma(` —— 纯改名、功能未变）|
| **style-passthrough** | 0 | **0** | **已修**：⑰d 从"两处域不同"变成"**差异段 = 无（0 处）**"（U-1 挪到 kFsOut 显示域段，见 §8.3）|
| srgb-oetf | 1 | 1 | **预先就红**（`measureSceneStats` 的 `to_lin` 与 OETF 不同步，与本期无关）|
| hr-pre | 1 | 1 | **预先就红**（mono 实参下发；HR 域，本期未碰）|
| log-json | 2 | 2 | 用法错误（需日志文件参数），非红 |
| domain-contract-check | 1 | 1 | **预先就红**（需要补域契约 —— 本期恰好是该补的时候）|

⇒ **Phase B 引入的红已清零**（15 绿；其余 4 项均与本期无关且已用 baseline 对比证明）。

### 8.3 U-1（split/mono）跨链路不一致的修复（bug，2026-10-16）

**问题**：同一 `[23][24][25]`，`kFsFilm`（**线性工作空间域**）与 `kFsStyle`（**显示域**）各算一遍
`ws/wh` 权重 ⇒ 同一物理亮度下权重差 43%（中灰 0.18：0.583 vs 0.250）⇒ **编辑器调出来的效果
与拍照对不上**。判据 ⑰d 本来就要求两处逐字相同 ⇒ 这是 bug，不是"可接受的域差异"。

**PC 的做法（一手源码 `/tmp/pc-tone`）**：这类"感知分区调整"在 PC 里**只有一处实现**，位于
RAW 引擎**之后**的 LUT 阶段（拍照/编辑/视频共用），且在 **Lab 域**算（`shRgbToLabScaled`）。
⚠ 但 PC 的**实时预览**用另一套完全不同的简化实现（`PreviewShadowsHighlightsShader`，纯 RGB+luma）
⇒ PC 在"预览 vs 成片"上比我们更不一致（有意的性能妥协），而在"拍照 vs 编辑"上统一。

**修法**（与 PC 同方向、且与 U-4 的既有做法一致）：
- `kFsFilm` 删掉 split 加法与 mono 行（`ws/wh` 保留 —— uSat 分区仍用它）
- `kFsOut` 在**显示域**（暗角之后、LUT 之前，与 kFsStyle **完全同序**）新增 U-1 段，
  数学**逐字照抄 kFsStyle**；为此把 kFsOut 原有的线性域 luma 改名 `lumaLin`，并新增
  显示域 `luma()`（Rec.709，与 kFsStyle 同名同式）
- C++：`u_out_` 新增 `split`/`mono` 的 location 查询与下发（`p.split_warm`/`p.split_cool`/`p.monochrome`）
- ⚠ `uSplit`/`uMono` 的 uniform **刻意放在 U-4 uniform 段（uClarity..uToneOn）之外**：
  `check-u4` 判据①以"机械删除 U-4 三段"构造对照 shader，其删除终点哨兵是 `uZebra` ——
  插在它之前会被一并删除，导致对照 shader 编译失败。

**零回归证据**：`film_d5.npy` / `film_d5_sigmoid.npy` 的 CRC32 与挪动前**完全相同**
（`0xf891c297` / `0x7c2646ef`）—— 默认参数（split=0 / mono=0）下该段恒等，输出逐位不变。

**意外收获**：`check-toeshoulder` **绿** —— 它的 60 项 spline 系数**不依赖工作空间**（纯 CPU spline 数学），此前"必红"的预判是错的。

✅ **色温/色调不影响拍照与预览**（实读结论，`FrameRenderer.cpp:8550-8590`）：`[88]`/`[89]` 经
`wbTempTint()` 折成 WB 增益，走**三个消费点** —— `wbCompGNorm`（OES 预览 / YUV 静拍）与
`prepareRawCommon` 的两个调用点（RAW 单帧 / MFNR 成片）⇒ 全部落在 **RAW 域与预览输入域**，
**根本不在工作空间里**。自动白平衡（`CONTROL_AWB_MODE_AUTO` → HAL → `asShotNeutral`）同理。
⇒ Phase B 碰不到拍照/预览的色温；唯一曾被影响的是编辑器链，已回滚（见下）。

### 8.2 新基线（离线实测，2026-10-16）

命令：`python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng --roi 1800,2100,600,1000 [--engine 1]`

| 项 | 旧（`bb7026d`） | 新（Phase B 后） |
|---|---|---|
| `film_d5.npy` CRC32（引擎 0） | `0x589f8364` | **`0xf891c297`** |
| `film_d5_sigmoid.npy` CRC32（引擎 1） | 未记录 | **`0x7c2646ef`** |
| `kFsFilm` 源（整份） | chars 25140 / bytes 35154 / crc32 `0x4fa1ebd3` | chars 25887 / bytes 36587 / crc32 **`0x235cf3f5`** |
| `kFsSigmoid` 拼装源 | 未记录 | chars 16175 / bytes 24028 / crc32 **`0xfbab6a8c`** |

⚠ **尚未写入守卫脚本**：`engine-shader-offline.py` 的 `FILM_SRC_BASELINE`（含 5 段逐块摘要）仍钉着旧值 ⇒ 下次跑 `--splice-selftest` 会红。按 §5:99 要求以"**旧值保留 + 新值填入**"的结构更新，列为下一轮第一件事。

**未做**：真机判据 F（HEIC 视觉回归）+ 阶段 3 判据 0。
