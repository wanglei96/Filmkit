# U-4：拍照链路补齐 `[90..93]` 影调分区 + `[94]` 褪色 + `[78]` 清晰度（显示域）

> 用户 2026-10-11：「**不统一的要统一掉，缺的补上。**」方案 = `docs/reports/preset-DESIGN.md` **§9**
> （本卡 = §9.5 的 U-4）。前序：U-1（编辑器补 `[23..25]`，已提交）、U-3（拍照补 `[4]`/`[88][89]`，
> 提交 `cf684f9`）。**本卡 = 拍照侧最后 5 个风格参数** —— 它们在编辑器链路 `kFsStyle` 里
> **已有实现**，而编辑器链路的输入本身就是**已成片 sRGB（显示域）** ⇒ **照抄它的数学**是最稳的
> 效果对齐方式（U-1 的同一手法）。
> 交付后 §9.2 的 9 项缺口**只剩 U-2（编辑器侧胶片曲线 + filmic 黑白点）与 U-5（编辑器饱和度三档）**。

## 0. 结论速览

| # | 验收判据 | 结果 | 关键数字 / 证据 |
|---|---|---|---|
| 1 | 五项**默认值逐位零回归** | ✅ | `11×6×4 = 264` 个 half 分量**逐位相同**（含 U-4 vs 删掉 U-4 的 kFsOut）；删段后与 `git HEAD` 的 kFsOut **逐字相同**（298 行） |
| 2 | 五项**各自生效**且**方向/幅度与编辑器侧一致** | ✅ | 六项对照 `Δout ≡ Δstyle`：`[78]12.58 / [90]54.41 / [91]18.68 / [92]70.72 / [93]13.45 / [94]30.87` LSB，`|Δout−Δstyle| ≤ 0.249` LSB（=1 half-ULP）；`[78]` 在棋盘上 `Δ 56.03 LSB`，**平坦区零副作用**（0.12 LSB = 末位 ULP） |
| 3 | 与 `kFsStyle` 对应段的**逐字差异处数** | ✅ **0 处** | 影调分区段/褪色段/16 个环偏移/两段 CPU 下发行**逐字相同**；**唯一**差异 = clarity tap 的域预处理（§2 逐条列明） |
| 4 | `[39] sharpen` 未被改动 | ✅ | `sharpenLuma` 函数段 + 调用行 + 下发行 vs `HEAD` **全同**；删段法证明整个 kFsOut 除 U-4 三段外**零改动** |
| 5 | 四方同步 + 四个脚本退出码 0 | ✅ | `validate-shaders.sh` / `check-diag-fields.py` / `check-style-passthrough.py` / **`check-toeshoulder.py`（已修好）** 全部 0 |
| 6 | 构建 + 单测真跑 + mutation | ✅ | 见 §3.6（含 "X actionable tasks: X executed" 与用例数） |
| 7 | 未真机 | ⚠️ **未真机验证** | 真机步骤与 `[78]` 观感/性能的实测口径见 §5 |

---

## 1. 落点与实现

### 1.1 为什么落在 `passOutput` 的 `srgbEncode` **之后**

拍照链路 `kFsOut` 的顺序是：`texture → highlightRolloff → [39] sharpen → srgbEncode`（OETF）
`→ 暗角 → LUT → 颗粒 → dither → 斑马纹`。**OETF 之后就是显示域**纵向对应编辑器链路的输入域
（`kFsStyle` 的输入 = 已成片 sRGB）⇒ 四项影调/褪色与清晰度**同域同式**。

⚠️ 绝不能放在 `srgbEncode` 之前：那里是线性显示域，同一物理亮度下 luma 差 2.5 倍
（线性 0.18 ↔ 显示 0.46）⇒ 影调分区的权重曲线（`l²/s²/l³/s³`）形状完全不同。

**两链路的显示域先后序（照抄后完全一致）**：

| 顺序 | kFsStyle（编辑器，显示域起点 = 输入） | kFsOut（拍照，显示域起点 = `srgbEncode` 之后） |
|---|---|---|
| 1 | 对比度 → 饱和度 | （无：`[4]`/`[20..22]` 在更早的 RAW/线性 pass 里） |
| 2 | **清晰度 `[78]`** | **清晰度 `[78]`（新增）** |
| 3 | **影调分区 `[90..93]`** | **影调分区（新增）** |
| 4 | **褪色 `[94]`** | **褪色（新增）** |
| 5 | 暗角 → 分离色调/单色 → LUT → 颗粒 → dither | 暗角 → LUT → 颗粒 → dither |

⇒ 新增三段插在 **OETF 之后、暗角之前**，相对顺序与 kFsStyle **逐段对应**。

### 1.2 shader 改动（`kFsOut`，`core/engine/src/render/FrameRenderer.cpp`）

1. **uniform 声明**（插在 `uFlipY` 之后）：`uClarity` / `uClarStep` / `uHighlights` / `uShadows` /
   `uWhites` / `uBlacks` / `uFade` / `uToneOn` —— 与 `kFsStyle` **同名同义**。
2. **`tapLuma` + `blurLuma16`**（插在 `srgbEncode` 之后、`main` 之前）：16-tap（2 环 × 8 方向）
   全手动展开，**偏移序列、0.0625 归一、Rec.601 权重与 `kFsStyle` 逐字一致**。
   `tapLuma` 是唯一的实现差异（域逼出来的，见 §2）。
3. **main 三段**（插在 `c = srgbEncode(c);` 之后、暗角之前）：
   清晰度（加性 luma USM，差值限幅 ±0.25）→ 影调分区（乘性 `l*g`）→ 褪色（`lift + c(1−lift)`，
   `lift = uFade × 0.25`）。**门控**：`uClarity > 0.0` / `uToneOn > 0.5` / `uFade > 0.0`，
   与 kFsStyle 逐字同判据 ⇒ 默认值下**三段整段跳过（零成本）**。
4. **未新增任何 pass / 纹理 / 显存**：全部落在既有 `passOutput` 内（`[78]` 的邻域采样用的是
   已有的 `uTex` = `tex_work_`，与输出同尺寸）。

### 1.3 CPU 侧下发（`FrameRenderer::passOutput`）

照 `passStyle` 的同名段逐字同式：`uClarity`、`uClarStep`（环半径）、`uToneOn`（四项任一非 0）、
四个色阶值、`uFade`。**清晰度环半径提取成唯一维护点** `clarityStepPx(w,h)`
（= `max(1px, 0.5% × 输出短边)`），三处共用：`passStyle` / `passOutput` / 成片诊断字段 ——
"日志里的半径"与"实际下发的半径"同源，不会各自漂移（这类静默失效正是
`tools/check-diag-fields.py` 存在的原因）。

### 1.4 诊断字段（`raw.render`，成片链路）

新增 8 个字段（`check-diag-fields.py` 白名单已同步）：`clar` / `clarR` / `toneGate` /
`toneHi` / `toneSh` / `toneWh` / `toneBl` / `toneFade`。

⚠️ **命名刻意带 `tone`/`clar` 前缀**：`submitDisplay` 的 `diag.stats` 快照已占用
`toneOn`/`hl`/`sh`/`wh`/`fade`，而 `check-diag-fields.py` 取 `hits[0]`（行号最小的匹配）⇒
同名会把核对指到 `submitDisplay` 上（U-1 的 `splitW`/`splitC`/`monoOn` 是同一条纪律，
本卡第一次跑就真的撞上了 `toneOn`/`hl`/`wh`/`fade` ⇒ 已改名）。

### 1.5 `PresetScope.kt`：`[78]` / `[90..94]` 的 `EDITOR_ONLY` 删除

- `SLOT_SCENES` 删掉 `put(78, EDITOR_ONLY)` 与 `for (s in 90..94) put(s, EDITOR_ONLY)`
  ⇒ **判据表里再没有"仅后期"槽位**（U-1 消 `[23..25]`、U-3 消 `[88][89]`、U-4 消 `[78]/[90..94]`）。
- `EDITOR_ONLY` 常量**删除**（失去最后一个使用者；留着会让人以为还有仅后期参数）。
- `SLOT_LABELS` 删掉 `78..78`、`90..90`…`94..94` 六行（死条目，U-1/U-3 的先例）。
- `MAX_NAMES` 截断**删除**：判据表剩下的单侧槽位只有 `[5..10]`/`[37][38]`，去重名恒 3 个
  ⇒ 截断分支恒不执行（死代码）。
- ⚠️ 本卡**没有**改 `presetSlotScenes()` 的语义（`不在表里 = 两侧都生效`），也没有为了
  "让测试过"而放水：改名/删除都是为了消除死条目，判据本身没动。

---

## 2. 逐字差异清单（判据 3：逐字差异处数 = **0**）

`tools/check-u4-camera-display-domain.py` 的 ③ 逐段比对（注释剔除、空白归一、`u_out_`/`u_style_` 归一）：

| 段 | 逐字？ | 说明 |
|---|---|---|
| 影调分区 `[90..93]`（`l`/`s`/`g` 权重公式 + `c *= max(g,0)`） | ✅ 相同 | 连 `if (uToneOn > 0.5)` 的判据都一样 |
| 褪色 `[94]`（`lift = uFade*0.25` + `lift + c*(1-lift)`） | ✅ 相同 | — |
| 清晰度环定义 `d1 = uClarStep; d2 = uClarStep*2.0` + **16 个偏移** + `return lb*0.0625` | ✅ 相同 | 16 个 `vec2(...)` 偏移逐项比对 |
| CPU 侧清晰度下发行 | ✅ 相同 | 含 `clarityStepPx` 调用与 `glUniform2f(clar_step, …)` |
| CPU 侧影调 + 褪色下发行 | ✅ 相同 | 含 `tone_on` 的四项公式 |

**唯一差异（1 处，域逼出来的，非自由发挥）**：

| 处 | kFsStyle | kFsOut | 理由（为什么必须不同） |
|---|---|---|---|
| 清晰度 tap 的取值 | `dot(texture(uTex, cropUV(uv+off)).rgb, w)`（**直接**取 luma） | `dot(srgbEncode(highlightRolloff(texture(uTex, uv+off).rgb)), w)`（`tapLuma`） | kFsStyle 的 `uTex` 已是**显示域成片** ⇒ tap 直接取 luma 就与中心同域，它只需 `chainLuma` 提升过"曝光/WB 往返 + 对比度 + 局部曝光"；本 shader 的 `uTex` 是**滚降前的线性显示域**（`tex_work_`）⇒ tap 必须过与中心**同一条链**（`highlightRolloff → srgbEncode`）才同域。否则 blur 基准偏低（线性 0.18 vs 显示 0.46）⇒ 清晰度退化为"整体提亮"（U-1 的 `chainLuma` 注释记的正是这类事故）。 |
| **无 `chainLuma`** | `chainLuma(blurLuma16(vUV0), m)` | `blurLuma16(uv)` | 它要提升的三项在拍照链路**没有落点**：曝光/WB/对比度在更早的 pass（RAW 线性域），局部调整 `[79..87]` 只服务编辑器 ⇒ 恒等。**刻意不写一个空的 `chainLuma`**：留着会让人以为拍照链路也有域提升，改天有人往里加一项就是上面那个事故（YAGNI）。 |
| 采样坐标 | `cropUV(vUV0)`（裁剪 + 旋转的仿射映射） | `uv`（`vUV0`，`uFlipY` 时翻 y） | 拍照链路的裁剪在**引擎输入侧**就做完了（`tex_work_` 已是裁剪后画面），`kFsOut` 全 shader 都没有 `cropUV`。环偏移**对称** ⇒ `uFlipY` 的 y 翻转不改变环形状。 |
| 权重载体位置 | `blurLuma16` 内 `vec3 w = vec3(0.299,0.587,0.114);` | `tapLuma` 内同一向量 | 只是"放哪一行"；③ 单独断言两侧都用 **Rec.601**（`kFsOut` 顶部另有一个 Rec.709 的 `luma()` 服务 `sharpenLuma`，两个权重**故意并存**，与 kFsStyle 内部的"601 局部对比 + 709 split/mono"同构）。 |

---

## 3. 验收判据逐条证据

### 3.1 判据 1：五项默认值**逐位零回归**

三条互补证据（全部来自 `tools/check-u4-camera-display-domain.py`，**自包含**）：

1. **GPU 逐位（删段法）**：从工作区 `kFsOut` 源码里**机械删除** U-4 的三段（uniform 声明 /
   `tapLuma`+`blurLuma16` / main 三段，三块定位标记各命中一次，找不到即 assert）⇒ 得到"改前
   等价 shader"；两者各真跑一遍同一张 RGBA16F 输入（`RGBA16F` FBO + half 读回）：
   `264 个 half 分量，不同 = 0`。
   ⚠️ 刻意**不用 `git show HEAD`** 当对照物：`tools/check-toeshoulder.py` 刚因为"对照物随提交
   漂移"而自我失效（本卡顺手修好，见 §3.5），删段法没有这个问题。
2. **区分度（防死代码）**：同一对 shader 在**非默认值**下必须不同 —— 实测 `max|Δ| = 35.49 LSB`
   ⇒ 新段确实在起作用，"零回归"不是因为代码没接上。
3. **文本级**：把 U-4 三段从工作区 `kFsOut` 删掉后与 `git show HEAD` 的 `kFsOut`
   **逐字相同**（298 行 == 298 行）⇒ 改动是**纯增量**，既有段一字未动。

比对规模：`11 级灰阶 × 6 行 × RGBA`，即 **264 个 half 分量**；另在 ② 的对照里对
`8×6×4 = 192` 个分量、128² 棋盘 `65536` 个像素逐点比对（见下）。

### 3.2 判据 2：五项各自生效 + 与编辑器侧**方向/幅度一致**

**方法（关键）**：把两条链路放到**同一个显示域起点**上 ——
`kFsOut` 输入 = `srgbDecode(D)`（线性显示域，且灰阶上限 220/255 ⇒ 线性 ≤ 0.72 < `knee 0.80`
⇒ `highlightRolloff` 恒等返回），`kFsStyle` 输入 = `D`（显示域编码值）。两者输出都是显示域
⇒ 可直接逐点比。

**基线（五项全默认）**：`kFsOut(srgbDecode(D))` vs `kFsStyle(D)` —— **0 个分量不同**
（`8×6×4`），`max|Δ| = 0.0000 LSB` ⇒ 两条链路在显示域**逐位合流**（这是"同域"的直接证据，
也只有域真的相同才可能发生）。

**单项对照表**（Δ = 该参数 − 基线，逐点比较；单位 = 8bit 显示域 LSB）：

| 参数 | Δout max | Δstyle max | \|Δout−Δstyle\| max | 判定 |
|---|---|---|---|---|
| `[78] clarity = 1.0` | 12.58 | 12.58 | 0.0000 | ✅ |
| `[90] highlights = +0.5` | 54.41 | 54.41 | 0.0000 | ✅ |
| `[91] shadows = +0.5` | 18.68 | 18.68 | 0.0000 | ✅ |
| `[92] whites = −0.5` | 70.72 | 70.72 | 0.0000 | ✅ |
| `[93] blacks = −0.5` | 13.45 | 13.45 | 0.0000 | ✅ |
| `[94] fade = 0.5` | 30.87 | 30.87 | 0.0000 | ✅ |

方向性由权重定义保证，且与编辑器侧**逐点相同**：`highlights` 抬亮部（`l²` 权重）、
`shadows` 抬暗部（`s²`）、`whites`/`blacks` 集中在端点（`l³`/`s³`，故 `[92]` 的幅度最大）、
`fade` 抬黑场而不动白点（`c=1 ⇒ 1`）。

**`[78]` 清晰度用细节图另测**（平坦区 USM 恒等 ⇒ 必须给边缘）：
128² 棋盘（块 16px，40/200 两级）：

| 量 | 值 |
|---|---|
| 基线两条链路 `max|Δ|` | 0.1245 LSB（= 1 half-ULP，见下注） |
| `Δout max`（clarity 0→1） | **56.03 LSB** |
| `Δstyle max` | 56.03 LSB |
| `|Δout−Δstyle| max` | 0.2490 LSB（非零像素占比 **0.195%**） |

> 注：这里允许 **≤1 half-ULP**（`0.2501 LSB`），而不是"逐位相同"。原因**不是**数学差异：
> `kFsOut` 的输入是 numpy float64 算出的 `srgbDecode(D)` 再经 GL 的 `srgbEncode` 回编码，
> 而 `kFsStyle` 的输入就是 `D` —— 两次 pow 的末位不同，落在 half 网格的相邻格上。
> 灰阶图（48 像素）恰好没踩到边界 ⇒ 逐位 0 不同；棋盘（16384 像素）有 0.195% 的像素差 1 ULP。
> 观感上等效：**0.25 LSB = 8bit 量化半步长的一半**，不构成可见差异。

**★ `[78]` 的域正确性哨兵 —— 平坦区零副作用**：棋盘块中心（距边缘 ≥4px > 2 环半径）
的 16 个 tap 全落在同色块内 ⇒ `l0 − lb = 0` ⇒ 清晰度在无细节处**不得有任何改变**：

| 量（块中心 4096 像素） | 值 |
|---|---|
| `Δout max`（clarity 0→1） | **0.1245 LSB**（非零分量 12 个 = 末位 ULP） |
| `Δstyle max` | 0.1245 LSB（同源） |
| 对照：若 tap 与中心**不同域** | 平坦区会偏 **~50 LSB**（显示 0.784 vs 线性 0.578）—— 差 3 个数量级 |

⇒ 这一条才是"清晰度不是整体提亮"的**直接证据**：如果 `tapLuma` 漏了
`highlightRolloff`/`srgbEncode` 中的任何一步，平坦区会整体被抬起来，本条立刻红。
（实测的 12 个末位 ULP 来自 GLSL `pow` 不保证跨调用点逐位确定，非实现差异。）

### 3.3 判据 3：逐字差异处数 = **0**

见 §2。脚本输出：`③ 逐字差异处数 = 0`，且单独断言"唯一差异 = tap 的域预处理"
（`tapLuma` 过 `highlightRolloff+srgbEncode` = True；两侧都用 Rec.601 = True/True；
kFsOut 无 `chainLuma` = True；kFsStyle 有 `chainLuma` = True）。

### 3.4 判据 4：`[39] sharpen` 未被改动

- **文本**：`sharpenLuma` 函数段 = 同、调用行 `if (uSharpen > 0.0) sharpenLuma(c, uv);` = 同、
  C++ 下发行 `glUniform1f(u_out_.sharpen, p.sharpen);` = 同（vs `git HEAD`）。
- **更强**：删掉 U-4 三段后整个 `kFsOut` 与 `HEAD` **逐字相同** ⇒ 除新增三段外零改动。
- **行为**：`uSharpen 0 → 0.5` 仍有 `20.05 LSB` 的差 ⇒ 没有被破坏。
- 语义分工写进了注释与 `preset-DESIGN.md` §9.3：`[39]` = OETF **前**的 3×3 小半径**乘法**
  luma USM（保色度、相对增益）；`[78]` = 显示域的 16-tap 中半径**加性** luma USM。**不复用**。

### 3.5 判据 5：四方同步 + 脚本退出码

| 同步点 | 状态 | 验证 |
|---|---|---|
| `tools/check-diag-fields.py` 白名单（8 个新字段） | ✅ | `python3 tools/check-diag-fields.py` → **exit 0**（8 条 OK） |
| `PresetScope.kt` + `PresetScopeTest.kt` | ✅ | 见 §1.5；`scopeTableMatchesDesignMatrix` 的单侧槽位数 14 → **8**；`editorOnlyParamsAreReportedInCamera` → `noEditorOnlySlotsRemain`；`longNameListIsTruncated` → `singleSideNoticeFitsInOneLine` |
| `docs/reports/preset-DESIGN.md` §1.6 两行 `❌ → ✅` | ✅ | **表格结构未动**（21 行、每行 ≥6 单元格，正则解析口径不变）；同步更新 §1.6 说明段、§9.2 三行、§9.5 的 U-4 行 |
| **`tools/check-toeshoulder.py`（E4-4 遗留，HEAD 上就红）** | ✅ **已改造** | 见下 |

**`check-toeshoulder.py` 的改造**（架构师交办）：原判据 ① 拿 `git show HEAD` 当"改前"，
E4-4 一提交 HEAD 就变成"改后" ⇒ `AssertionError: shader 提取对不上`（本卡实测复现）。
根因 = **把 git 历史当对照物，对照物会随提交漂移**。改法：

- ① 改为**自包含**：把 E4-4 交付时（提交 `a824170`）实测的 **60 个 hex 系数**固化进脚本
  （`EXPECT_HEX`），工作区的 `computeFilmicSpline` 真编真跑后逐位比对 ⇒ 与 git 状态无关。
  基线的一次性复现（本卡实跑）：`a824170^`（单套求解）vs `a824170`（per-channel）
  **60 个 hex 逐位 0 处不同** ⇒ 固化值就是"零回归通过时的值"。
- 原 ① 的 GPU 部分（"新 shader vs 改前 shader 输出逐位"）由一条自包含判据取代：
  **默认 latitude 下 per-channel 必须退化为单套**（GPU 输出 R=G=B 逐位）。
- ②③④ **本来就是自包含的，原样保留并确认能跑**。
- 结果：`python3 tools/check-toeshoulder.py` → **exit 0**，8 条判据全 OK。

| 脚本 | 退出码 |
|---|---|
| `bash tools/validate-shaders.sh` | **0**（全部 shader 校验通过） |
| `python3 tools/check-diag-fields.py` | **0** |
| `python3 tools/check-style-passthrough.py` | **0**（全部通过） |
| `python3 tools/check-toeshoulder.py` | **0**（全部通过） |
| `python3 tools/check-u4-camera-display-domain.py`（本卡新增） | **0** |

### 3.6 判据 6：构建 + 单测（真跑）+ mutation

见下方 §6（构建输出）。

**mutation 自检（3 项，脚本内直接跑篡改版）**：

| # | 篡改 | 期望 | 实测 |
|---|---|---|---|
| M1 | `[90..93]` 的 **CPU 侧门控公式漏项**（只判 `highlights`） | `shadows` 非 0 时该参数静默失效 ⇒ 必须能抓到 | 差 **18.86 LSB** ✅（另断言 C++ 侧 `tone_on` 公式覆盖四项） |
| M2 | 删掉 `[78]` 的 USM 那一行（`c += clamp(l0-lb,…)*uClarity`） | `clarity>0` 下必须不同 | 差 **14.00 LSB** ✅ |
| M3 | `[94]` 门控恒假（`uFade > 1e9`） | `fade>0` 下必须不同 | 差 **29.88 LSB** ✅ |

> ⚠️ **一个必须写明的技术事实**（否则 M1 会写成假判据）：把 shader 内的
> `if (uToneOn > 0.5)` 改成**恒真**，在默认值下**数值上查不出来** —— tone 段在参数为 0 时是
> `c *= max(1.0, 0.0)`、fade 段是 `c = 0 + c·1.0`，**都是 IEEE 精确恒等**（本卡脚本实测：
> 恒真版与工作区版默认值下逐位相同 = True）。⇒ 这两个门控换来的是"**整段跳过**"的 GPU 成本，
> 不是数值差异；真正会把效果弄丢的失效模式是 **CPU 侧门控公式漏项**（M1）或**整段被删**（M2/M3）。
> 这个结论对 `[78]` 同样成立（`uClarity=0` 时 `c += …*0` 也恒等），所以 `[78]=0` 的"零成本"
> 靠的是门控**整段跳过 16 次采样**，而不是数值恒等。

---

## 4. `[78]` 清晰度的采样与性能量级（**未真机实测**）

**采样数与来源**：**16 tap**（2 环 × 8 方向，与 kFsStyle 同偏移），来源 = **`passOutput` 现有
纹理 `uTex`（= `tex_work_`，与输出同尺寸 RGBA16F）** ⇒ **不需要新纹理、不需要新 pass、不增加
显存**。每 tap 需要过 `highlightRolloff`（1 次 `exp`）与 `srgbEncode`（3 次 `pow`）才与中心同域。

**静态量级估算**（输出 4080×3072 = 12.5 Mpx，脚本 `判据 ⑥` 直接打印）：

| 项 | 量 |
|---|---|
| 额外纹理采样 | 16/px ⇒ **201 M 次**（对照 `[39] sharpen` 的 8/px = 100 M） |
| 额外 `exp` | 16/px ⇒ **201 M** |
| 额外 `pow` | 48/px ⇒ **602 M** |
| 粗估时间（Mali SFU 16 pow/cycle @1GHz） | **≈38 ms**（仅 pow 一项；未计采样与带宽） |

⚠️ **这是纸面估算，不是实测**（用户项目铁律：性能取舍必须实测）。要点：
- **默认 `[78]=0` ⇒ 整段跳过 ⇒ 成本为 0**（预览与成片都是）。只有用户把清晰度调上去才付这份钱。
- **预览路径每帧都跑 `passOutput`**（`submitFrame` 的预览出口）⇒ 清晰度非 0 时预览帧率会被它影响，
  这是真机实测**第一优先**的观察项（成片是单次读回，38 ms 量级可接受；预览 30/60fps 则可能不够）。
- 若真机超预算，备选（**由架构师按实测数据裁决，本卡不擅自降级**）：
  ① 单环 **8-tap**（偏移与权重按同式减半，Δ 幅度约减半）；
  ② 另起**降采样 blur pass**（1/4 分辨率预计算 blur 图，清晰度段只采 1 次）—— 成本从 16 tap
     降到 1 tap + 一次全屏降采样，代价是 halo 半径的精度；
  ③ 只在**成片路径**启用 `[78]`（预览用低档）—— 会造成"所见非所得"，需用户拍板。

---

## 5. 真机验证步骤（**本卡未真机验证**）

> 本卡是纯 GPU/CPU 离线验收（真跑引擎自己的 GLSL + 真编真跑 C++ 系数），
> **没有在真机上跑过**。以下步骤供真机验收执行。

1. **零回归（默认值）**：不套任何预设（五项全默认）拍一张 → 与 `0.11.13`（U-3 版）同机位同参
   数的一张做像素 diff ⇒ 应为 0（`[78]=0`、`[90..94]=0` 时三段整段跳过）。
2. **五项生效**：套一条含 `[78]/[90..94]` 的预设（或手动拉滑杆）→ 取景预览应立刻可见：
   `[90]` 抬亮部、`[91]` 抬暗部、`[92]/[93]` 只动端点、`[94]` 黑场发灰、`[78]` 局部对比上升。
   **日志核对**：`adb logcat | grep "raw.render"` 里应有
   `"clar":…,"clarR":…,"toneGate":1,"toneHi":…,"toneSh":…,"toneWh":…,"toneBl":…,"toneFade":…`，
   **值必须与预设/滑杆一致**（`clarR` = 输出短边的 0.5%，如 4080×3072 ⇒ 15.36）。
3. **`[78]` 观感**（重点）：中半径 USM 的观感要"细节提起来但不脏"——
   对比 `[78]=0/0.5/1.0` 三档，检查 ① 平坦区（天空/墙面）是否**没有**整体提亮（那是 blur 基准
   不同域的典型事故，本卡已从域上排除）；② 高反差边缘是否有明显白边（差值限幅 ±0.25 应压住）；
   ③ 暗部噪声是否被放大（加性 USM 在暗部会放大噪声 —— 这是**已知取舍**，[39] 的"暗部少拿增益"
   特性在显示域 USM 上不存在；若观感不可接受，需用户拍板是否给 `[78]` 加暗部衰减）。
4. **`[78]` 性能**（重点）：`clarR` 非 0 时测预览帧率（`adb shell dumpsys gfxinfo` 或引擎自带
   计时）与单张成片 `totalMs`（`raw.render` 的 `totalMs`/`passMs`）——与 `[78]=0` 同场景对比，
   **给出实测差值**，按 §4 的备选裁决。
5. **两侧一致**：同一张成片在编辑器里把 `[78]/[90..94]` 调到与拍照时相同的值 ⇒ 观感应一致
   （离线已证 Δ 逐点相同；真机确认 UI 量程/文案一致，这属 U-6）。

---

## 6. 构建 / 单测 / 校验的实测输出

（见 §3.5 的脚本表；以下为 gradle 真跑结果）

**强制真编**：构建前删除 `app/build/filmengine/*/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o`
与 `app/build/filmengine/jniLibs/*/libfilmengine.so`（+ `rm -rf app/build/test-results`），命令：

```
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

- 构建日志确认**两个 ABI 都真的重编了** `FrameRenderer.cpp.o`
  （`[ 15%] Building CXX object CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o` ×2），
  产物 mtime = 本次构建（16:04），`libfilmengine.so` arm64-v8a / x86_64 均重新生成。
- 结果：**`BUILD SUCCESSFUL`**，**`46 actionable tasks: 46 executed`**，退出码 **0**
  （`--rerun-tasks` ⇒ 无 `FROM-CACHE` 假绿）。
- 单测：`app/build/test-results/testDebugUnitTest/`（40 个 XML）合计
  **`tests = 437, failures = 0, errors = 0, skipped = 0`** —— 与 U-3 基线 **437 用例**一致、0 失败；
  `PresetScopeTest` **12/12** 通过（含新写的 `noEditorOnlySlotsRemain` 与 `singleSideNoticeFitsInOneLine`）。

---

## 7. 纪律与边界

- **独占引擎文件**：开工时 `git status` 显示 U-3 的改动仍在工作区（`FrameRenderer.cpp/.h`、
  `PresetScope.kt`、`PresetScopeTest.kt`、`preset-DESIGN.md`、`u3-…REPORT.md`）；
  **开工后 HEAD 前移到 `cf684f9`（U-3 被提交）** ⇒ 本卡的全部改动都建立在该提交之上，
  **没有回退任何 U-3 内容**（`tempTintGain`/`wbTempTint`/`contrast_param` 接线逐字仍在）。
- **未碰**：`EditorScreen.kt` / `PhotoViewScreen.kt` / `CameraScreen.kt` / `CameraController.kt` /
  `EditorExport.kt` / 预设数据层；`kFsStyle` **一行未改**（本卡只往 `kFsOut` 加）。
- **未 commit**（按纪律）。
- 本卡改动的文件（`git status`）：
  `core/engine/src/render/FrameRenderer.cpp`（+171）、
  `core/engine/src/render/FrameRenderer.h`（+9）、
  `app/src/main/java/com/filmkit/app/camera/PresetScope.kt`、
  `app/src/test/java/com/filmkit/app/camera/PresetScopeTest.kt`、
  `docs/reports/preset-DESIGN.md`、`tools/check-diag-fields.py`、`tools/check-toeshoulder.py`、
  新增 `tools/check-u4-camera-display-domain.py` 与本报告。
  ⚠️ 工作区里 `docs/reports/editor-ADR.md` 的改动**不是本卡的**（并发会话所写，未触碰）。

## 8. 遗留与风险（诚实标注）

1. **`[78]` 性能未实测**（§4/§5）—— 这是本卡最大的未知项；离线只能给量级。
2. **清晰度在暗部会放大噪声**：显示域加性 USM 的固有行为（`[39]` 的"暗部少拿增益"不适用）；
   真机观感若不可接受，需要新增旋钮（用户拍板，本卡不擅自加）。
3. **两条链路的域差仍存在于别处**（`[23..25]` 的 luma 域差是 U-1 记录的已知项），
   本卡的五项**没有**域差（都在显示域，实测基线逐位合流）。
4. `[5..10]`/`[37][38]` 仍是"仅拍照"（U-2 待补）⇒ P-5 的"仅拍照"提示**尚未**全部消失。
