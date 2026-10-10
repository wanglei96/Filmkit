# P-2b 内置胶片预设包（首批 3 条）· 交付报告

> 执行：实现子代理 ｜ 2026-10-10 ｜ 工作目录 `/Users/wl/Downloads/胶片相机`
> 状态：**代码留在工作区，未 commit**（等架构师独立复核）
> 上游依据：`docs/reports/preset-DESIGN.md`（§1 配置清单 / §1.6 字段能力矩阵 / §7 裁决 A1·A3）
> 前置交付：P-1（`EditorExport.kt` 的 `presetsFromJson` / `FilmParamKeys` / `EditorPreset`，schema 2 + 语义名）

---

## 0. 一句话结论

三条内置预设（徕卡一瞬 / 理光负片 / 徕卡黑白）已落成 `assets/presets/builtin.json`，
经 `BuiltinPresets.parse()`（复用 P-1 的 `presetsFromJson`）**逐槽解析结果与架构师配方逐项一致**，
引用的三个 LUT 资产均存在且能被生产解析器整表解出，黑白预设确认**不含** `[25] monochrome`。
单测 10 条用例通过（全量 404 用例 0 失败，退出码 0），mutation 自检已证明配方断言**真的会红**。

★ **配方量值未经真机标定**（颗粒强度 / 尺寸 / 暗角只是"有物理依据的起点"），详见 §6。

---

## 1. 改了什么（文件 : 行号）

| # | 文件 | 行号 | 内容 |
|---|---|---|---|
| 1 | `app/src/main/assets/presets/builtin.json` | 1–57（新建） | 三条内置预设，schema 2 / 语义名参数键 / `source: "builtin"`；**数值逐字照抄架构师配方** |
| 2 | `app/src/main/java/com/filmkit/app/camera/BuiltinPresets.kt` | 1–108（新建） | `object BuiltinPresets`：`ASSET_PATH`(:42) / `parse`(:56) / `load`(:69) / `all`(:101) / `log`(:105) |
| 3 | `app/src/test/java/com/filmkit/app/camera/BuiltinPresetsTest.kt` | 1–287（新建） | 10 条护栏用例（6 类要求全覆盖） |

**未碰**（遵守文件独占纪律）：`CameraScreen.kt`（P-2a）、`PhotoViewScreen.kt` / `MainActivity.kt` / `EditorScreen.kt`（P-3）、
`EditorExport.kt`（P-1，工作区改动待架构师提交）。`git status` 中本卡新增的只有上表三个路径（`assets/presets/`、`BuiltinPresets.kt`、`BuiltinPresetsTest.kt`）。

### 1.1 设计要点（`BuiltinPresets.kt`）

- **复用同一条解析路径**：`parse()` 一行 `presetsFromJson(json).presets` —— schema 校验（只认 2）、
  **未知参数名整项拒绝**、逐槽三档容错（策略排除 / 非法值 / 等于恒等值）对内置包同样生效；
  没有为内置包写"更宽松"的第二套解析器（两套必然漂移，漂移的表现是"内置能加载、用户包不能"）。
- **失败绝不抛异常**：assets 缺失 / 读失败 ⇒ `preset.builtin_load_failed` WARN + 空列表；
  解析有跳过项 ⇒ `preset.builtin_skipped`（含条数与前 500 字原因）；
  解析出 0 条 ⇒ `preset.builtin_empty`。三者都返回空列表，`all()` 因此**永远能返回用户预设**
  （内置包坏掉不连累用户数据）。
- **不加缓存**：assets 读取约 1 KB，而静态缓存（`neutral()` 那类）会带来跨测试 / 跨版本状态污染。
- **`all()` = 用户预设（前）+ 内置预设（后）**：用户自己存的更常用；分组 / 收藏排序留给 UI 层
  （数据层给稳定顺序，UI 给呈现顺序，两处各排一次必然出现"同一列表两种顺序"）。
- **KDoc 写明黑白不设 `[25]` 的理由**（object 级 KDoc 的专章 + `bwPresetHasNoMonochrome` 测试双保险）。

---

## 2. 验收结果

### 2.1 单测（退出码 0 + 用例数）

```
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew cleanTest testDebugUnitTest --no-build-cache
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL
EXIT=0
suites=38  tests=404  skipped=0  failures=0  errors=0
```

- `BuiltinPresetsTest`：**10 用例，0 失败**（XML 时间戳 `2026-10-10T03:03:45Z` = 本次生成）。
- 全量：**38 个 suite / 404 用例 / 0 失败**（含 P-1、P-2a、P-3 在工作区的既有测试）。

> ⚠ **工程发现（验收命令必须加两个开关）**：只跑 `./gradlew testDebugUnitTest`（即使先删了
> `app/build/test-results/`）会**命中 Gradle build cache**（输出 `1 from cache, 26 up-to-date`），
> XML 是**从缓存恢复的**、不是本次执行的结果 —— 拿它当验收证据会漏掉回归。
> 必须 `cleanTest --no-build-cache`（或 `--rerun-tasks`）才能确认真跑了。
> 本报告的三次结果（基线 / mutation / 恢复）都加了这两个开关，日志中 `> Task :app:testDebugUnitTest`
> 均无 `UP-TO-DATE` / `FROM-CACHE` 后缀。

### 2.2 mutation 自检（故意改配方 ⇒ 用例必须红）

| 步骤 | 操作 | 退出码 | 结果 |
|---|---|---|---|
| ① 基线 | — | **0** | 404 用例 / 0 失败 |
| ② mutation | `builtin.json`：`"grain_amount": 20.0` → `25.0`（徕卡一瞬） | **1** | `BuiltinPresetsTest > leicaM9RecipeMatchesSpec FAILED`<br>`java.lang.AssertionError at BuiltinPresetsTest.kt:51`（`assertRecipe` 的"套用后的有效值必须等于配方"断言）<br>`404 tests completed, 1 failed` |
| ③ 恢复 | 改回 `20.0` | **0** | 404 用例 / 0 失败；`md5 = 43accb47c3cd25f38b8ab52bd472f2fe`（与 mutation 前一致） |

⇒ 配方断言**不是恒真的**：有人"顺手调参"会被立刻抓住。

### 2.3 单测覆盖的 6 类要求 ↔ 用例映射

| 任务要求 | 用例 |
|---|---|
| ① 三条都能解析（数量 / id / name / group / source） | `parsesExactlyThreeBuiltinPresets`(:71)、`assetPathPointsAtAnExistingAsset`(:115) |
| ② 参数值逐项等于配方 | `leicaM9RecipeMatchesSpec`(:126)、`ricohNegativeRecipeMatchesSpec`(:143)、`leicaBwRecipeMatchesSpec`(:160) |
| ③ LUT id 真实存在（文件头能解出 title/category） | `everyReferencedLutAssetExistsAndIsReadable`(:183) |
| ④ 黑白不含 `monochrome` | `bwPresetHasNoMonochrome`(:222) |
| ⑤ 参数名都在 `FilmParamKeys.byName` 里 | `everyParamNameInJsonIsKnown`(:243)、`builtinPackageParsesWithNoSkips`(:260) |
| ⑥ 坏 JSON 降级（空列表 + 不抛） | `badJsonDegradesToEmptyListWithoutThrowing`(:269)，喂 10 种坏输入（空串 / 截断 / 非 JSON / 顶层非数组 / 缺 name / 旧 schema / 未知参数名 / `params` 非对象 / `[]` / `{}`） |

**★ 断言口径（本卡最容易做错的一处）**：`EditorPreset.slots` 是**稀疏**的（只含与恒等值不同的槽），
而配方里有两项**恰好等于恒等值** ⇒ 它们**本就不该**出现在 `slots` 里。测试因此分两条断言：

1. **套用后的有效值**（`presetSnapshot(标准基线, slots)[i]`）== 配方值 —— 这条是"配方正确"的真判据；
2. 配方值 ≠ 恒等值 ⇒ 必须真的落在 `slots` 里；配方值 == 恒等值 ⇒ 必须**不**在 `slots` 里
   （否则等于把"保持默认"写成冗余记录）。

只写第 1 条会漏掉"配方其实没写进去"，只写第 2 条会对 `[27]`/`[67]` 误报（"测试红了但画面是对的"）。

---

## 3. 三条预设的实际解析结果（逐槽打印，取自单测 `system-out`）

```
BUILTIN builtin_leica_m9      name=徕卡一瞬   group=徕卡 lut=leica_m9       slots=7
  :: [23]=0.1, [24]=0.05, [26]=20.0, [27]=160.0, [66]=-0.18, [67]=85.0, [68]=55.0
BUILTIN builtin_ricoh_negative name=理光负片  group=理光 lut=ricoh_negative slots=6
  :: [23]=-0.1, [24]=0.1, [26]=30.0, [66]=-0.28, [67]=75.0, [68]=48.0
BUILTIN builtin_leica_bw      name=徕卡黑白   group=徕卡 lut=leica_b_w_hc   slots=3
  :: [26]=40.0, [66]=-0.24, [68]=52.0
```

槽位名对照（`FilmParamKeys`）：`[23] split_warm`、`[24] split_cool`、`[26] grain_amount`、
`[27] grain_size`、`[66] vignette_strength`、`[67] vignette_radius`、`[68] vignette_feather`。

**与 JSON 原文的差异（两处，均为预期行为，不是丢数据）**：

| 槽 | 预设 | JSON 写的值 | 是否进 `slots` | 原因 |
|---|---|---|---|---|
| `[27] grain_size` | 理光负片 / 徕卡黑白 | `400.0` | ❌ 不进 | `400` 就是 `CameraPresets.standard()[27]`（恒等值）⇒ 稀疏化剔除；**套用后有效值仍 = 400** |
| `[67] vignette_radius` | 徕卡黑白 | `80.0` | ❌ 不进 | `80` 是 darktable `scale` 默认 = 恒等值 ⇒ 同上；**套用后有效值仍 = 80** |

⇒ 三条预设**套用后的 96 槽有效值**与架构师配方**逐项相同**（测试第 1 条断言覆盖）。

---

## 4. LUT 存在性验证输出

`everyReferencedLutAssetExistsAndIsReadable` 用**生产解析器** `CubeLutParser.parseBinary`
（`app/src/main/java/com/filmkit/app/lut/CubeLut.kt:194`）整表解析资产（不只是读头部 ⇒ 顺带覆盖
"资产被截断 / 数据不全"这种坏法），并按 `LutLibrary.headOf` 的同一口径
（`[size/magic][titleLen][title][catLen][category]`）解出 category：

```
LUT_OK id=leica_m9        title=徕卡 M9       category=徕卡 size=33 bytes=107829
LUT_OK id=ricoh_negative  title=理光 负片      category=理光 size=33 bytes=107833
LUT_OK id=leica_b_w_hc    title=徕卡 黑白 HC   category=徕卡 size=33 bytes=107836
```

三条资产的 `3 + titleLen + catLen + 33³×3 == 文件字节数`（107829 / 107833 / 107836）⇒ 完整无损。
这条用例堵的是**静默降级路径**：预设引用了不存在的 LUT 时，运行时只会显示"LUT 已不在库中"，
用户看到的是"效果不对"而不知道原因。

---

## 5. 配方依据复核（架构师实测 LUT 二进制数据的结论，随本卡一并归档）

| 预设 | LUT 实测特性 | 为什么这样配 |
|---|---|---|
| 徕卡一瞬（`leica_m9`） | 中性灰阶对比度斜率 **1.318**（高对比）；整体压暗 R−6.1/G−9.7/B−4.9（**减绿 = 品红倾向**）；纯色响应强（纯红→纯红） | M9 = Kodak CCD ⇒ 类比**正片/反转片**：高对比、色彩浓。颗粒取 **ISO 160**（M9 基础感光度，CCD 低 ISO 噪点少）⇒ 强度 20% 走"细颗粒"；M 系镜头暗角小 ⇒ 暗角 −0.18；正片的"暖阴影 + 冷高光"分离 ⇒ split 0.10/0.05 |
| 理光负片（`ricoh_negative`） | 对比度斜率 **0.858**（低对比）；**白场只到 237**（高光压缩）；整体提亮且 **G 最高（+15.7）= 黄绿调**；纯绿→[0,208,143] 青绿 | 彩色负片特征 = 低对比 + 柔和高光 + 黄绿。颗粒取 **ISO 400**（负片典型）⇒ 30%；GR 镜头暗角明显 ⇒ −0.28；负片的冷调阴影 ⇒ split_warm −0.10、高光微冷 0.10 |
| 徕卡黑白（`leica_b_w_hc`） | 对比度斜率 **1.161**；**真黑白**（通道差 0）；**通道响应跨度 233/255**（纯绿→233 最亮、**纯蓝→0 全黑**、纯红→154） | 黑白胶片 + 黄绿滤镜的响应 ⇒ 保留通道混合。颗粒取 **ISO 400**（Tri-X/HP5 的银盐颗粒是标志）⇒ 40% 最明显；暗角 −0.24 |

### ★ 黑白预设**故意不加** `[25] monochrome`（反直觉，已用测试钉死）

`leica_b_w_hc` LUT **自带通道混合**（蓝压黑、绿提亮 = 黑白胶片配黄绿滤镜的响应）。
若先 `[25]` 去色：三通道被抹成同一条灰阶 ⇒ LUT 的通道混合**输入已经是灰的**、作用在灰上仍是灰
⇒ 黄绿滤镜的响应被**整段抹平**，出来的是一张平淡灰照片。
所以 JSON 里**没有** `monochrome` 键（测试同时断言 JSON 原文与解析结果两处都没有），
理由写在 `BuiltinPresets` 的 object 级 KDoc 里（防后人"顺手补上"）。

---

## 6. 未做 / 存疑项（★ 必读）

### 6.1 ★ 未真机标定（最重要的一条）

**以上颗粒强度 / 颗粒尺寸 / 暗角量值是基于 LUT 实测 + 胶片物理常识的起点，未经任何真机标定。**
本卡只证明了"配方被正确写入并正确解析"，**没有**证明"成片看起来像徕卡 M9 / 理光负片 / 徕卡黑白"。
参照口径：E4-3 的颗粒标定记录 `[26] = 25` 时中间调颗粒 RMS ≈ 4.9/255 —— 本批 20 / 30 / 40
是围绕该点的相对排序（细 / 中 / 明显），绝对值等真机样片回来再定。
**请勿把本卡当作"已还原真实胶片"**：真机 A/B（同场景、同曝光，三条预设各一张）是下一步。

### 6.2 `[27] grain_size` 的语义依赖 E4-3（工作区已落地，未提交）

配方按**新语义**配（`[27]` = ISO，**越大越粗**）：M9 = 160（细）、理光 / 黑白 = 400。
该语义由工作区未提交的 `core/engine/src/render/GrainLut.h:95-101`（`grainZoom(iso)`，
"ISO 越大 ⇒ zoom 越大 ⇒ 空间频率越低 ⇒ 颗粒越粗"）与 `FrameRenderer.cpp` 的 `uGrainZoom` 接线提供。
⚠ **若内置包随没有 E4-3 的构建发版**，`[27]` 是旧行为（数字越大越细）⇒ **M9 的 160 会变成最粗的一条**，
与配方意图相反。建议内置包与 E4-3 **同版发布**（或至少在 E4-3 落地后再做真机标定）。

### 6.3 `[23][24] split_warm / split_cool` 只在拍照链路生效

实测 `FrameRenderer.cpp` 里 `split_warm/split_cool` 仅在 `:12506`（`passFilm` = 拍照链路）下发，
编辑器链路 `passStyle` 无引用（与 `preset-DESIGN.md` §1.6 能力矩阵一致）。
⇒ 徕卡一瞬 / 理光负片的分离色调**在拍照侧有效，在查看页 / 编辑器套用时无效**（只有 LUT + 颗粒 + 暗角生效）。
这不是本卡的缺陷，但 UI 侧（P-2a / P-3）应按 §1.6 灰显标注"仅拍照"，否则用户会以为"套了预设没反应"。

### 6.4 `[25]` 在编辑器链路同样未接线

`monochrome` 只在 `FrameRenderer.cpp:12507`（`passFilm`）下发 ⇒ 即使当初"顺手补上" `[25]`，
在编辑器里也**不产生任何效果**（还会在拍照侧把 LUT 的通道混合抹平）。两条理由同向 ⇒ 不设 `[25]` 是双重正确。

### 6.5 提出但**未改**的配方细节（按任务要求"只在报告里提出"）

1. **`[27]=400` / `[67]=80` 等于恒等值** ⇒ 线格式写了、稀疏 `slots` 里读不到（行为等价）。
   好处是"配方自解释"（人工核对时能看到完整意图）；代价是导入导出 / diff 时容易误以为丢数据。
   本卡选择**保留 JSON 原文不改**，由测试显式记录这两处（§3 表）。
2. **理光负片 / 徕卡黑白的 `grain_size` 都是 400**：两者恰好等于恒等值 ⇒ 即使删掉这两行，
   成片行为完全不变。若希望"配方在 JSON 里也能看出颗粒尺寸差异"，需要把恒等值改成别的值
   （属引擎契约改动，不在本卡范围）。
3. **徕卡黑白没有 split 参数**：物理上黑白胶片不存在"暖阴影 / 冷高光"的分离色调（那是彩色负片
   的层间效应），所以空着是对的 —— 记录在此以免后人以为漏配。

### 6.6 未做的部分（明确不在本卡范围）

- **UI 接线**：拍照界面预设列表（P-2a）、查看页「胶片」（P-3）由架构师在并行卡交付后统一派工；
  本卡只保证 `BuiltinPresets.all(context, prefs)` 能返回"用户 + 内置"的完整列表。
- **`load()` / `all()` 的 JVM 单测**：需要 Android `Context` + `AssetManager`，JVM 单测环境不可用
  ⇒ 只测纯函数 `parse()`（与 `LutLibrary` / `PresetStore` 同一测试口径：Android 依赖层薄到无逻辑）。
  `load()` 的失败分支（读不到 assets）依赖代码评审 + 真机诊断日志（`preset.builtin_*`）。
- **未做缓存**（设计决定，见 §1.1）：若将来 `all()` 被高频调用（如每帧），再加缓存并配失效策略。
- **未 commit**：按要求改动留在工作区。

---

## 7. 复核建议（架构师）

1. `git status` 确认本卡只新增 3 个路径（§1 表），`EditorExport.kt` 等 P-1/P-2a/P-3 改动未被本卡触碰。
2. 复跑验收命令时**必须带 `cleanTest --no-build-cache`**（§2.1 的工程发现），否则会看到缓存的假绿。
3. 逐条核对 §3 的解析结果与架构师配方原文是否一致（这是本卡唯一需要人工比对的部分）。
4. 若认可 §6.5 的第 1/2 条（恒等值项写不写进 JSON），可在提交前一并调整；本卡未擅自改动数值。
