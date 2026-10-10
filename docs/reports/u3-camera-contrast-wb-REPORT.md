# U-3 拍照链路补齐 `[4]` 对比度 + `[88][89]` 色温/色调 —— 交付报告

> 实现子代理：DSH 会话（U-3，2026-10-11）。工作目录 `/Users/wl/Downloads/胶片相机`。
> 用户指令（唯一依据）："**影调重建拍照侧和编辑器侧不一样？不统一的要统一掉，缺的补上。**"
> 架构师定案：`docs/reports/preset-DESIGN.md` **§9**（先读 §9.2 清单 + §9.3 实现方案）。
> 本卡 = §9.5 分卡表的 **U-3** 行 = §9.3"拍照链路补 6 项"中**代价最低、收益最直接的两项**。
> **代码改动全部留在工作区，未 commit**（等架构师独立复核）。
> 参考卡：U-1（编辑器侧 `[25]` + `[23][24]`，已提交 `b1a295f`）—— 本卡沿用它的证据口径
> （"删掉新增段的 shader"逐位对照 + 与既有 numpy 参考直译对拍 + mutation 负对照）。

## 0. 结论摘要

1. **`[4]` 对比度在拍照链路生效**，且语义正确：`darktable` filmic spline v3 的 `contrast_param`
   与**内部补偿 `cp` 相乘**（不是覆盖）。三档实测（0.5 / 1.0 / 1.5）在中灰**以下**的输出
   79.9 / 66.5 / 45.6（8bit），而**中灰锚点（18.45%）三档逐位相同 = 0.1844482** ——
   即"围绕中灰旋转"，不是整体提亮/压暗（§4.2）。
2. **`[88][89]` 色温/色调在拍照链路生效**，与编辑器链路**同一个 `tempTintGain`、同一次乘法**
   （ADR-14 裁决 1）：`mired=+20` ⇒ 增益 `(0.95216, 1.0, 1.03603)`、`B/R = 1.088`（画面**变冷**，
   与 `FilmParams.h` 的既有方向约定一致）；零额外 GPU 成本（并入已有的 WB 成对往返）。
3. **默认值零回归是逐位判据**（不是"≤1 LSB"）：`[4]=1.0` / `[88]=[89]=0` 时
   **594 次 hex 浮点比较（3 组 EV 标定 × 3 档 `cp` × 66 个系数/标量）0 处不同**，
   且 GPU 侧 `kFsFilm` 的 RGBA16F 读回**逐位相同**（`np.array_equal = True`，`max|Δ| = 0`）。
4. **正交性有解析依据 + 实测数字**：`[4]` 改的是 spline **线性段斜率**（`linm2`：1.010 / 1.434 /
   2.151），`[5..10]` 改的是**过渡区位置**（`lat_min`）；固定一侧改另一侧时
   `black_disp`（= toe 起点 `f(0)`）/ `input_min` / `input_max` / `power` / `dr` 逐位不变（§4.4）。
5. **三处消费点全覆盖**（`wbCompGNorm` = OES 预览 / YUV 静拍；`prepareRawCommon` 的两个调用点
   = RAW 单帧 / MFNR 成片）—— 漏一处的后果正是"预览有色偏、成片没有"。
6. **护栏与构建全绿**：`validate-shaders.sh`（62 个 GLSL）/ `check-diag-fields.py` /
   `check-style-passthrough.py` / 新增 `check-u3-camera-contrast-wb.py` 退出码全 0；
   `assembleDebug` + `testDebugUnitTest --rerun-tasks` = **46 actionable tasks: 46 executed**、
   **437 tests / 0 failures / 0 skipped**、退出码 0（§5）。
7. **mutation 自检 2 项**（真改源码跑，跑完精确还原）：去掉 `[4]` 接入 ⇒ **3 项红、exit 1**；
   去掉 `[88][89]` 增益乘法 ⇒ **2 项红、exit 1**（§4.6）。
8. **未真机验证**（本机无连接设备）—— 见 §7。

**需要架构师注意的 3 件事**（详见 §6）：
- §6.1 `preset-DESIGN.md` §1.6 的 `[4]` 行**在 U-3 之前就标 ✅ 是错的**（与 §9.2 自相矛盾）；
- §6.2 `[4]` 的实际 UI 量程是 **0.01..2.0**（`CONTRAST_UI_RANGE`），任务书写的 0.5..1.5 是常用档位；
- §6.3 `tools/check-toeshoulder.py`（E4-4 的脚本）**在 HEAD 上就已经红**，与本卡无关（§6.3）。

---

## 1. 改了什么（引擎侧）

### 1.1 `[4]` contrast → `computeFilmicSpline` 的 `contrast_param`

先读代码确认 `cp` 的**两个来源与语义**（架构师要求"先读代码确认，不是覆盖"）：

| 来源 | 位置 | 取值 | 语义 |
|---|---|---|---|
| **内部补偿** | `updateFilmicEV`：`cp` 初值 `1.0f`，仅当 `auto_on` 且白点上探（`w > w_old + 1e-3`）时改写 | `clamp(pow_old/pow_new, 1.0, 1.6)` | 自动白点上探把 `dr` 拉大 ⇒ `power` 派生回落 ⇒ "中灰以上提亮变少"的回退补偿（**只补不降**） |
| **用户/预设** | `FilmParams[4]`（本卡接入） | 规格 0.5..1.5，1.0 = 恒等 | 风格参数（darktable contrast 滑杆） |

**两者作用的是同一处物理量**：spline v3 里 `contrast_param` 只进一个地方 ——
线性段斜率 `slope = contrast_param · dr / 8`（`computeFilmicSpline` 内）。⇒ **相乘**是唯一
正确的合成方式；覆盖会让自动上探的回退补偿被用户值吃掉（"高光压不住 + 中灰变灰"同时出现）。

| 位置 | 改动 |
|---|---|
| `FrameRenderer.cpp` `updateFilmicEV` | 形参新增 `float user_contrast`（插在 `user_ev` 之后、`lat_sh` 之前）；函数尾新增 `contrast_user` 防呆 + 接入 |
| `FrameRenderer.cpp` `passFilm` | 调用点补 `p.contrast` 实参 |
| `FrameRenderer.h` | `updateFilmicEV` 声明同步（含 KDoc：相乘的语义 + 零回归依据） |
| `FrameRenderer.cpp` `finishStillChain` 的 `raw.render` 出口 | 新增诊断字段 `ct`（= `FilmParams[4]` 的用户值；与同出口既有的 `cp` 一起才能还原"实际进入求解的 `contrast_param = cp × ct`"） |

**接入点原文**：

```cpp
  const float contrast_user =
      (std::isfinite(user_contrast) && user_contrast > 0.0f) ? user_contrast : 1.0f;
  filmic_ = computeFilmicSpline(ev_black_, ev_white_, cp * contrast_user, lat_sh, lat_toe);
```

- **零回归的依据**：默认 `[4] = 1.0f` ⇒ `cp * 1.0f` 在 IEEE 754 下**逐位** `== cp`
  （`cp` 的取值域由初值 + clamp 限定为 `{1.0} ∪ [1.0, 1.6]`，全是有限规格化数）⇒
  5×5 系统的右端项逐位不变 ⇒ 全部系数逐位不变。
- **防呆（信任边界）**：非有限值或非正值 → 恒等 1.0。理由是本函数内 latitude 的既有纪律
  （`computeFilmicSpline` 注释："非有限值（NaN/±Inf 经 clamp 后仍可能为 NaN）回退 darktable
  默认"）—— NaN 会让 5×5 系统的右端项**全 NaN** ⇒ 整条 filmic 链输出 NaN（X300 同型事故）。
  非正值在 `FilmParams::fromDoubles` 已回退，此处覆盖"预设 JSON / 直连 JNI"的旁路。
- **刻意不加量程 clamp**：编辑器链路 `passStyle` 也不 clamp（`u_style_.contrast` 直接下发），
  **两侧语义必须一致**；越界值的兜底是 `computeFilmicSpline` 内的 `contrast = clamp(…, min_c, 100)`。

### 1.2 `[88][89]` 色温/色调 → 折成 RGB 增益并入 `[1..3]`

**复用同一份数学**（任务书硬要求"不要另写一套"）：`tempTintGain()`（`FrameRenderer.cpp:123`，
E2-11 为编辑器链路写的，含 `wbGainFromKelvin` 的黑体近似 + 参考点 6500K 差分）。本卡**新增的
唯一函数**是把它接到拍照链路的三个消费点：

```cpp
static void wbTempTint(const FilmParams& p, float out[3]) {
  out[0] = p.wb_r;
  out[1] = p.wb_g;
  out[2] = p.wb_b;
  if (p.temp_mired != 0.0f || p.tint != 0.0f) {
    float tt[3];
    tempTintGain(p.temp_mired, p.tint, tt);
    out[0] *= tt[0];
    out[1] *= tt[1];   // tt[1] 恒 1.0f —— 保留乘法是为了与 passStyle 的 wb_tot 逐字同式
    out[2] *= tt[2];
  }
}
```

| 位置 | 改动 |
|---|---|
| `FrameRenderer.cpp`（`wbCompGNorm` 之前） | 新增 `wbTempTint`（含 KDoc：三个消费点、恒等性依据） |
| `FrameRenderer.cpp` `wbCompGNorm` | 入口先 `wbTempTint(p, wb)`，再走既有的 G 归一（`out[0] = wb[0]/p.wb_g`） |
| `FrameRenderer.cpp` `submitRaw`（RAW 单帧成片链） | `prepareRawCommon(…, wb_tt[0], wb_tt[1], wb_tt[2], …)` |
| `FrameRenderer.cpp` `submitRawBurst`（MFNR 成片链） | 同上 |
| `FrameRenderer.cpp` `finishStillChain` 的 `raw.render` 出口 | 新增诊断字段 `wbTot`（= `[1..3] × [88][89]` 的**实际**增益；与编辑器侧 `render.style_diag` 的同名字段同义） |
| `core/engine/src/film/FilmParams.h` | `[88][89]` 的 KDoc 从"只被编辑器链路读取"改为"两侧都生效"；`[4]` 补接线说明 |

- **零回归的依据**：`[88]=[89]=0` 时 `wbTempTint` 的 `if` **整个分支不执行** ⇒ `out` 就是
  `[1..3]` 的原值。⚠️ 这一点比"乘 1.0 得 1.0"更强：`tempTintGain(0,0)` 的比值实测是
  `(0.999999762, 1.0, 0.999999762)`（`k_target = 1e6/(1e6/6500)` 有一次 float 往返舍入）
  —— **靠短路而不是靠浮点巧合**（§4.3 有实测）。
- **G 归一口径**：`tempTintGain` 的输出本身就是"R/B 相对 G 的增益"（G 恒 1）⇒ 与三条拍照链
  既有的 `wb/g_wb` 口径**天然一致**，不需要额外换算。

### 1.3 为什么"零额外 GPU 成本"

`[88][89]` 是**线性域增益**，与 `[1..3]` 同属一个乘性操作 ⇒ 并入**已有的**那一次 WB 往返
（RAW 链在 `prepareRawCommon` 折进 `rc.gains`；OES/YUV 链在 `wbCompGNorm` 折进 `uWb`）。
**shader 一行未改**（`validate-shaders.sh` 的 62 个 GLSL 输出与改前一致 —— 本卡对 shader
零改动，所以"逐字相同"类护栏天然不受影响）。

---

## 2. 同步的四处

| # | 位置 | 动作 | 依据 |
|---|---|---|---|
| ① | `tools/check-style-passthrough.py` 第 ④ 项白名单 | **不需要改**（实测确认） | 该项正则提取的是 `FrameRenderer::passStyle` 的函数体。本卡只在**拍照链路**新增读取（`wbTempTint` / `updateFilmicEV`），`passStyle` 的读取集合**一个字段都没变** ⇒ 白名单原样通过（§5.1 贴了 ④ 的实测输出） |
| ② | `tools/check-diag-fields.py` 白名单 | **不需要改** | 新增的两个诊断字段 `ct` / `wbTot` 都发在 `finishStillChain` 的 `raw.render` 出口，而 `EXPECT` 里**没有这两个名字**（脚本只核对 `EXPECT` 列出的字段）。同名的 `ct` / `wbTot` 也存在于 `submitDisplay` 出口（行号更小），若将来要把它加进 `EXPECT`，`hits[0]` 会指向 `submitDisplay` —— 与 E4-3 的 `grainMb` 同一情形（脚本注释已说明该口径） |
| ③ | `app/.../camera/PresetScope.kt` | `[88][89]` 从 `EDITOR_ONLY` **删除**（`for (s in 88..94)` → `for (s in 90..94)`）；`SLOT_LABELS` 的 88/89 两行删除（死条目）；类 KDoc 的实例句改写 | 改后两侧都生效 ⇒ 留表里就是"死条目"（与 U-1 删 `[23..25]` 同一判据） |
| ④ | `app/src/test/.../PresetScopeTest.kt` | 改 3 处断言 + **新增 1 条** `tempTintAreEffectiveInCamera()` | `editorOnlyParamsAreReportedInCamera`（3 项 → 1 项）、`longNameListIsTruncated`（8 项 → 6 项）、`scopeTableMatchesDesignMatrix` 的 `singleSide` 计数（16 → 14）；新增条目显式钉住"`[88][89]` 两侧生效、`[90]` 仍仅后期"的边界 |
| ⑤ | `docs/reports/preset-DESIGN.md` §1.6 | `[88..94]` 一行**拆成两行**（`[88][89]` 两侧生效 / `[90..94]` 仅后期）；`[4]` 行的"拍照链路"列加"U-3 接线前是错的"标注；表下加 U-3 说明段 | 表格结构未动（`PresetScopeTest.designMatrix()` 的正则 `\[\d+(?:\.\.\d+)?\]` 支持一个单元格多个槽位 —— 拆行后槽位总数不变，`matrix.size >= 55` 仍过） |
| ⑥ | §9.2 / §9.5 | 第 2、3 行 ❌→✅；U-3 行标"已完成" | 文档漂移点 |

---

## 3. 两条链路的效果对齐（[88][89]）

**结论：同一个物理量、同一个函数、同一次乘法；差异只有"作用位置所在的域"。**

| 维度 | 拍照链路 | 编辑器链路 | 是否影响本参数 |
|---|---|---|---|
| 实现函数 | `tempTintGain`（同一份源码） | `tempTintGain`（同一份） | **否** |
| 入口 | `wbTempTint` → `wbCompGNorm` / `prepareRawCommon` | `passStyle` 的 `wb_tot` | **否** |
| 乘法式 | `wb[i] * tt[i]`（i=0,1,2） | `wb[i] * tt[i]`（i=0,1,2） | **否**（⑤a 文本逐字比对 3/3） |
| 恒等短路判据 | `temp_mired != 0 \|\| tint != 0` | 同一判据 | **否** |
| 作用域 | 线性场景域（kFsOes / kFsStill / kFsRaw 的线性值） | 成片 sRGB **线性化后**（`srgbDecode` 之后） | **否 —— 两侧都在线性域，且都在各自的曲线/OETF 之前** |
| G 归一 | 除 `wb_g`（三条拍照链既有口径） | 不除（`uWbGain` 直接下发，shader 内 `chainLuma` 用 `dot(uWbGain, luma)`） | **否**（`tt[1] ≡ 1.0f` ⇒ 两种口径下 tt 部分完全相同） |

**与 U-1 的关键差别（值得记录）**：U-1 的 `[23][24][25]` 是**显示域操作**（权重由 luma 决定），
两条链路的 luma 域不同 ⇒ 同一物理亮度下的权重差异是**本征的**（U-1 报告量化到 43%）。
本卡的 `[88][89]` 是**线性域纯乘性增益**，与 luma 无关 ⇒ **两侧除了浮点精度没有域差异**
（⑤b 实测 `max|Δ| = 2.9e-07`，纯 float32 vs float64）。

数值对照（`tools/check-u3-camera-contrast-wb.py` 判据 ③b 的**真跑输出**，`wb=(1.2, 1.0, 0.9)`；
拍照侧 = clang++ 真跑的 `wbTempTint`，编辑器侧 = 同一份 numpy 直译 × 同一 `wb`）：

```
   mired=  +20 tint=+0.0 ⇒ 拍照 (1.142590, 1.000000, 0.932429) | 编辑器 (1.142590, 1.000000, 0.932429) ⇒ 同值 = True
   mired=  -20 tint=+0.0 ⇒ 拍照 (1.228918, 1.000000, 0.816755) | 编辑器 (1.228918, 1.000000, 0.816755) ⇒ 同值 = True
   mired=   +0 tint=+0.1 ⇒ 拍照 (1.260000, 0.900000, 0.945000) | 编辑器 (1.260000, 0.900000, 0.945000) ⇒ 同值 = True
   mired=   +0 tint=-0.1 ⇒ 拍照 (1.140000, 1.100000, 0.855000) | 编辑器 (1.140000, 1.100000, 0.855000) ⇒ 同值 = True
   mired=   +0 tint=+0.0 ⇒ 拍照 (1.200000, 1.000000, 0.900000) | 编辑器 (1.200000, 1.000000, 0.900000) ⇒ 同值 = True   ← 逐位（无乘法）
```

（拍照侧额外的 `/wb_g` 在 `wb_g = 1.0` 下是恒等；`wb_g ≠ 1` 的情形见 §4.3 的 `wbCompGNorm` 实测行。）

---

## 4. 实测方法与证据

**工具**：`tools/check-u3-camera-contrast-wb.py`（本卡新增，退出码 0）。
手法全部**复用项目已验证的证据口径**（不重写数学）：
- shader = 从 `FrameRenderer.cpp` 的原始字面量提取，经 `tools/engine-shader-offline.py` **真跑**；
- C++ 函数 = 用**大括号配平**从源码原文抠出（`check-toeshoulder.py` 同款 `brace_block`），
  机械改名后 **clang++ 真编真跑**，打印 `%a` 十六进制浮点；
- **U-3 新接入的两个表达式**（`contrast_user` 的判定式 + `computeFilmicSpline` 的实参
  `cp * contrast_user`）也**从源码正则提取**后放进 harness ⇒ 源码结构一变，脚本直接报错
  而不是静默跳过（不是"手抄一份数学再测自己抄的那份"）。

### 4.1 零回归逐位（`[4]=1.0` / `[88]=[89]=0`）

```
OK   ①a HEAD 函数+旧调用 vs 工作区函数+旧调用: 逐位比较 66 个 hex 浮点，不同 = 0
OK   ①a 工作区函数+旧调用 vs 工作区函数+**新接入(uc=1.0)**: 逐位比较 66 个 hex 浮点，不同 = 0
OK   ①a 全维度×全 cp 交叉逐位（HEAD 原版 vs 工作区新接入 uc=1.0）:
     3 组 EV 标定 × 3 档 cp × 66 个 hex 浮点 = 594 次比较，不同的组合数 = 0
OK   ①a 防呆（[4] = NaN / 负数 ⇒ 恒等）: NaN 与 −1.0 两档 × 3 组 EV × 3 档 cp 全部与 uc=1.0 逐位相同
OK   ①b GPU 侧 kFsFilm 输出逐位（HEAD 原版系数 vs 新接入 uc=1.0 系数）:
     RGBA16F 读回 (4, 12, 4) 逐位相同 = True（max|Δ| = 0.000e+00）
```

- **66 个比较项** = 15 个多项式系数（`m1..m5` 的 toe/sh/lin）× 3 通道 + `lat_min/lat_max` × 3 +
  `black_ev` / `white_ev` / `dr` / `input_min` / `input_max` / `power` / `black_disp` / `contrast`。
- **3 组 EV 标定** = `(−7.65, 4.56)`（darktable 默认，固定曲线）/ `(−5.63, 2.44)`（暗光室内
  auto 分支的典型值）/ `(−4.50, 3.10)`（白天收紧）；**3 档 cp** = 1.0 / 1.2 / 1.6（`cp` 的实际域）。
- `[88][89]` 的对应判据：`③ 恒等判据` —— `wbTempTint` 在 `wb=(1.2,1,0.9)` + `mired=tint=0`
  时输出 `['0x1.333334p+0', '0x1p+0', '0x1.ccccccp-1']`，与 `[1..3]` 的 float32 字面量
  **逐位相同**。

### 4.2 `[4]` 生效：曲线采样（12 点）+ 单调 + 中灰/端点

```
② [4] contrast 曲线采样（black −7.65 / white +4.56 / cp 1.0；输入 = log 编码 e 均匀 11 点 + 中灰锚点，输出 = sRGB 8bit）
   e      输入线性      [4]=0.5   [4]=1.0   [4]=1.5
   0.0000    0.00046       0.4       0.4       0.4  ← 黑端
   0.1000    0.00214       1.4       0.9       0.4
   0.2000    0.00499       8.2       3.2       0.4
   0.3000    0.01164      26.9      13.6       1.1
   0.4000    0.02712      51.6      35.2      11.4
   0.5000    0.06323      79.9      66.5      45.6
   0.6000    0.14739     110.5     107.0     101.2
   0.6265    0.18450     119.0     119.0     119.0  ← 中灰锚点（旋转中心）
   0.7000    0.34357     144.1     153.8     170.5
   0.8000    0.80090     185.6     201.0     227.6
   0.9000    1.86696     230.8     239.1     253.1
   1.0000    8.70409     255.0     255.0     254.9  ← 白端
OK   ② [4] 三档生效 + 单调不减 + 中部斜率随档位递增:
     e=0.50 三档输出 = 79.9 / 66.5 / 45.6（0.5 与 1.5 相差 34.4 LSB）；单调不减 = 全 True；
     中部局部斜率（e 0.4→0.6，LSB）= 58.9 / 71.9 / 89.8
OK   ② 中灰锚点不动: e=0.6265（= −black/dr，线性 0.18450 = 18.45% 中灰）：
     [4]=0.5 输出 0.1844482 / 1.0 0.1844482 / 1.5 0.1844482 ⇒ **逐位相同 = True**
   e=0（黑端）：8bit 显示域 [4]=0.5/1.0/1.5 = 0.37 / 0.37 / 0.37 ⇒ 不动
   e=1（白端）：8bit 显示域 [4]=0.5/1.0/1.5 = 255.00 / 255.00 / 254.95 ⇒ 不动（≤1 LSB）
OK   ② [4] 端点不动
```

**读法**：
- 中灰**以下**（e < 0.6265）[4] 大 ⇒ 更暗；中灰**以上** ⇒ 更亮；**中灰本身逐位不动** ——
  这正是"改对比度"的正确定义（围绕中灰旋转）。解析依据：`li = grey_disp − contrast·grey_log`
  使 `line(grey_log) ≡ grey_disp`（与 `contrast` 无关）。
- **端点的判据为什么用 8bit 而不是 RGBA16F 逐位**：`e=1` 处 shoulder 五次多项式在 float 下
  求值有 ~4e-4 的抵消误差（系数随 `[4]` 变大而变大），落到 fp16 上是 1 ulp(16F) = 1/2048
  （实测 `0.9995117` vs `1.0`）；但**真机成片口径 = 8bit 显示域**，两者都是 255。
  端点由 `[37][38]` 决定而非 `[4]`，其 CPU 系数层面的逐位证据在 ④（`black_disp` /
  `input_min` / `input_max` / `power` 在 `[4]` 三档下逐位不变）。

### 4.3 `[88][89]` 生效 + 方向

```
③ [88][89] RGB 增益（真跑引擎 wbGainFromKelvin + tempTintGain；G 归一，G 恒 1）
   mired=  -20 K_target=   7471K tint=0     ⇒ (1.02410, 1.00000, 0.90751)  B/R=0.88615  ← 变暖
   mired=   +0 K_target=   6500K tint=0     ⇒ (1.00000, 1.00000, 1.00000)  B/R=1.00000
   mired=  +20 K_target=   5752K tint=0     ⇒ (0.95216, 1.00000, 1.03603)  B/R=1.08809  ← 变冷
   mired=    0 K_target=   6500K tint=-0.1 ⇒ (0.95000, 1.10000, 0.95000)  G>1  ← 绿
   mired=    0 K_target=   6500K tint=+0.1 ⇒ (1.05000, 0.90000, 1.05000)  G<1  ← 品红
   mired=    0 K_target=   6500K tint=+0.5 ⇒ (1.25000, 0.50000, 1.25000)  G<1  ← 品红（满量程）
OK   ③ 方向正确（正 mired ⇒ 变冷 / 正 tint ⇒ 品红）
OK   ③ [88][89] 与 [1..3] 相乘（不是覆盖）: wb=(1.2,1,0.9) + mired=+20
     ⇒ wbTempTint = (1.142590, 1.000000, 0.932429) == wb×tt
     ；wbCompGNorm（OES 预览 / YUV 静拍口径）= (1.142590, 1.000000, 0.932429)
OK   ③ 三个消费点全部经 wbTempTint: 出现 4 次 = 3 个消费点 + 1 处诊断；裸值调用 = 0 处
OK   ③b 两条链路同值对照（wb=(1.2,1,0.9)）: 5 组 (mired,tint) 全部"拍照 == 编辑器"（见 §3 表）
```

**关于任务书的 `tint = ±10`**：`[89]` 的槽位量程是 **−0.5..+0.5**（`TINT_UI_RANGE`），
UI 显示值是槽位 × 100（`formatOffset100`）⇒ **UI 上的"±10"= 槽位 ±0.1**（上表第 4/5 行）。
本表同时给了满量程 ±0.5，量程问题见 §6.2。

### 4.4 正交性（`[4]` vs `[5..10]` latitude）

```
④ 正交性：[4] contrast 与 [5..10] latitude 都改曲线中部 ⇒ 端点/黑位不动
   latitude=0.01：[4]=0.50 ⇒ 黑端 8.781e-02 / 白端 -7.6500EV / dr 12.2100
                  [4]=1.00 ⇒ 黑端 8.781e-02 / 白端 -7.6500EV / dr 12.2100
                  [4]=1.50 ⇒ 黑端 8.781e-02 / 白端 -7.6500EV / dr 12.2100
   latitude=0.50：三档同上，逐位相同
OK   ④ 固定 latitude 改 [4] ⇒ black_disp（= toe 起点 f(0)）/输入端点/power 逐位不变 = True；
     latitude 0.01→0.50 使 lat_min 移动 = True；
     [4] 三档使 linm2（线性段斜率）三值互异 = True（1.010000, 1.433801, 2.150702）
```

**分工表**（"多旋钮互相干扰"的正面回答）：

| 旋钮 | 改的是 | 实测变化的量 | 实测**不动**的量 |
|---|---|---|---|
| `[4]` contrast | 线性段斜率 | `linm2` = 1.010 / 1.434 / 2.151（三档互异）；中部局部斜率 58.9 / 71.9 / 89.8 LSB | `black_disp` / `input_min` / `input_max` / `power` / `dr` / `black_ev` / `white_ev`（逐位） |
| `[5..10]` latitude | toe/shoulder **过渡区位置** | `lat_min` / `lat_max`（0.01→0.50 移动） | 同上（E4-4 已证端点不动） |
| `[37][38]` filmic 黑白点 | 曲线**端点** | `black_ev` / `white_ev` / `dr` / `power` | 两者都不动它们 |

### 4.5 两侧同一算法（数值）

```
OK   ⑤a 两侧同一算法（文本逐字同式）: passStyle: `wb_tot = {p.wb_r * tt_gain[0], p.wb_g * tt_gain[1],
     p.wb_b * tt_gain[2]}`；wbTempTint: `out[i] *= tt[i]` 三行齐备（实测 3/3）
OK   ⑤b 两侧同一算法（数值：真跑 C++ float32 vs 编辑器侧 numpy float64 直译）:
     15 组 (mired,tint) × 3 通道 = 45 次比较，max|Δ| = 2.90e-07（float32 精度极限，非算法差异）
```

### 4.6 mutation 自检（真改源码 ⇒ 红，跑完精确还原）

| # | mutation | 结果 | 证据 |
|---|---|---|---|
| 1 | `computeFilmicSpline(…, cp * contrast_user, …)` → `cp`（去掉 `[4]` 接入） | **exit 1**，3 项 FAIL | `② [4] 三档生效`：三档输出 66.5 / 66.5 / 66.5（差 0.0 LSB）；`④`：`linm2` 三档同值（1.433801 ×3）；`⑥ mutation-1`：未接入版 vs `[4]=1.5` 最大差 **0.0 LSB**（判据 > 3） |
| 2 | `wbTempTint` 的三行 `out[i] *= tt[i]` 删掉 | **exit 1**，2 项 FAIL | `③ 与 [1..3] 相乘`：`wbTempTint` 输出 (1.200000, 1.0, 0.900000) ≠ `wb×tt` (1.142590, 1.0, 0.932429)；`⑤a`：`out[i] *= tt[i]` 实测 0/3 行 |

还原核对：两次 mutation 后 `cp /tmp/FR.bak.cpp` 覆盖回原位，`md5 = 53c5d09c9d0391014b592d37efd0bf45`
与 mutation 前一致，重跑脚本 **exit 0 / 全部通过**。

脚本内还有两条**常驻负对照**（不依赖手工 mutation）：
`⑥ mutation-1`（用未接入的 `cp` 系数当"删掉本卡新增段"的可执行定义）与
`⑥ mutation-2`（用"不乘 tt"的增益），二者都要求"默认值下逐位相同 + 非默认值下显著不同"
—— 这是"死代码"与"真接线"的区分度证明。

---

## 5. 护栏 + 构建 + 单测

### 5.1 护栏（全部退出码 0）

| 工具 | 退出码 | 关键输出 |
|---|---|---|
| `tools/validate-shaders.sh` | 0 | **62 个 GLSL 全部 OK**，`全部 shader 校验通过`（本卡对 shader 零改动） |
| `tools/check-diag-fields.py` | 0 | `全部通过`（含 `latToe` / `latSh` / `hrWhiteEv` / `clipB` / `hrMs` 等 `finishStillChain` 组 —— 新增字段未扰动既有字段的"所属函数 + 前置调用"核对） |
| `tools/check-style-passthrough.py` | 0 | `全部通过`；④ 输出：`passStyle 读到的 FilmParams 槽位 = [... 'temp_mired', 'tint' ...]`（**与改前逐字相同** ⇒ 白名单不需要改，任务书的"可能不需要改"得到实测确认） |
| `tools/check-u3-camera-contrast-wb.py`（新增） | 0 | `全部通过`（15 项 OK，见 §4） |
| `tools/check-toeshoulder.py`（E4-4，**非本卡护栏**） | **1** | `AssertionError: shader 提取对不上` —— **HEAD 上就已红**，与本卡无关，见 §6.3 |

### 5.2 构建（真编）

删 `FrameRenderer.cpp.o`（arm64-v8a + x86_64）与全部 `libfilmengine.so`（`find -delete`，
清理后计数均 0）后 `assembleDebug`。

```
$ rm -f app/build/filmengine/{arm64-v8a,x86_64}/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
$ find . -name "libfilmengine.so" -not -path "./.git/*" -delete
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
...
BUILD SUCCESSFUL in 25s
46 actionable tasks: 46 executed
EXIT=0
```

**真编核对**（不是 `UP-TO-DATE` 假绿）：

| 证据 | 值 |
|---|---|
| 自定义 native task | `> Task :app:buildFilmEngine`（首个 task，**executed**） |
| `FrameRenderer.cpp.o`（arm64-v8a） | `15:43:14`（删除后重建） |
| `libfilmengine.so` | 全仓 **10 个**全部在本次构建内重建（`-newermt` 计数 = 10） |
| `app-debug.apk` | `15:43:33` |
| gradle 汇总 | **46 actionable tasks: 46 executed**（`--rerun-tasks` ⇒ 无 FROM-CACHE） |

### 5.3 单测

`rm -rf app/build/test-results` + `JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks`（单跑会 `FROM-CACHE` 假绿，项目实测过）。

**结果：`437 tests / 0 failures / 0 errors / 0 skipped`**（40 个 XML 结果文件），退出码 0。

- **基线 436 → 437**：+1 = 本卡新增的 `PresetScopeTest.tempTintAreEffectiveInCamera()`
  （另 3 条既有断言是**改口径**而不是新增）⇒ 数字对得上，既没有"测试被吃掉"也没有意外新增。
- 跑法：`--rerun-tasks` 且 `rm -rf app/build/test-results`（单跑会 `FROM-CACHE` 假绿，项目实测过）。

### 5.4 未做的验证（如实标注）

- **未真机验证**（本机无连接设备）—— 见 §7。
- **未跑** `grain-exp` / 真机日志对拍类脚本（与本卡无关）。

---

## 6. 需要架构师注意的 3 件事

### 6.1 §1.6 的 `[4]` 行**在 U-3 之前就标 ✅ 是错的**

`preset-DESIGN.md` §1.6 原文（U-3 之前）是 `| \`[4]\` | 对比度 | ✅ | ✅ | ✅ |`，
而 §9.2 的清单同一格写 `❌ **无效**（cp 硬编码 1.0，仅 gamma 回退补偿用）`。**两张表自相矛盾**，
代码实测支持 §9.2：`passFilm` 链路里 `p.contrast` 在 U-3 之前**零引用**（`cp` 初值 1.0 且只被
自动白点上探改写）。

**本卡的处理**：不动表格结构（`PresetScopeTest` 会正则解析它），只在 `[4]` 行的"拍照链路"格
加标注 `✅ **U-3 接线前是错的**（见表下说明）`，并在表下写明"U-3 后 ✅ 才成立"。因 `[4]` 原本
就是 ✅/✅ ⇒ `presetSlotScenes(4)` 一直是两侧生效、测试一直绿 —— **文档错了但护栏抓不到**
（护栏只保证"代码表 == 文档表"，不保证"文档表 == 引擎实际"）。教训值得记：§1.6 是**实测矩阵**，
它的可信度依赖抽取脚本；本卡是第一次发现它有错行。

### 6.2 `[4]` 的实际量程是 0.01..2.0，不是 0.5..1.5

任务书写 `contrast` 0.5..1.5，代码实测：

- `app/.../camera/EditorExport.kt`：`CONTRAST_SLOT_MIN = 0.01`、`CONTRAST_UI_RANGE = 0.01f..2f`、
  `contrastSlot(ui) = ui.coerceIn(0.01, 2.0)`；
- `presetSparseSlots` 的历史量程：`put(4, CONTRAST_SLOT_MIN..2.0)`。

**本卡不改量程**（不在范围内，且会动 UI 行为）。接入点按"用户值直通"处理（与编辑器侧一致，
只做 NaN/非正的信任边界防呆）。若规格 §3.4.3 确实要求 0.5..1.5，那是**UI 卡（U-6）**的事。

### 6.3 `tools/check-toeshoulder.py` 在 HEAD 上就已红（预先存在）

E4-4 的验收脚本设计是"工作区 vs `git show HEAD`"的 kFsFilm 对照，断言
`"uFilmicM1" in old_src`（旧版标量 uniform）。但 **E4-4 已提交**（`a824170`）⇒ HEAD 的
kFsFilm 已经是 per-channel 版（`uFilmicToeM1`），旧名不存在 ⇒ 脚本自我失效。

**实测确认与本卡无关**：`git stash` 掉本卡对三个引擎文件的改动后重跑，**baseline 同样
exit 1 + 同一 AssertionError**。

**建议**（不属本卡，留给架构师）：E4-4 这类"新旧对照"脚本应钉一个**明确的基线 commit**
（而不是浮动 `HEAD`），否则提交那一刻脚本就失效。本卡的
`check-u3-camera-contrast-wb.py` 因此**刻意不依赖 HEAD 的新旧差异**
（①a 的对照是"同一份参考实现的旧调用 vs 源码里的新接入表达式"，HEAD 版只用于
证明"函数体没被本卡改过"）。

---

## 7. 未真机验证 + 真机验证步骤

### 7.1 如实标注

**本卡未真机验证**（本机 `adb devices` 无连接设备）。所有判据来自**离线真跑引擎 shader
（moderngl/GLFW 真 GL 上下文）+ 真编真跑引擎 C++ 源码（clang++）**。真机特有的风险点：

1. `passOES`（HAL OES 域）与 `passStill`（YUV 域）的**域比归一**（`uHalDomainMap`）与
   `[88][89]` 的线性增益叠加后的观感 —— 离线测不到 HAL 域的输入；
2. 预设套用路径（`CameraPresetApply` → `CameraController.setFilmParams` → JNI）是否真的把
   `[4]` / `[88]` / `[89]` 送到引擎（离线只测了引擎内部；Kotlin 侧 `cameraPresetParams` 是
   完整 96 槽展开，理论上通，但**未真机确认**）；
3. 高光上探场景（`cp > 1`）下 `[4]` 的用户值是否造成观感突变（离线只覆盖了固定曲线的
   `cp=1.0` 采样 + 系数层面的 `cp=1.2/1.6` 回归）。

### 7.2 真机验证步骤（建议）

**准备**：装 `assembleDebug` 出的 APK；开日志抓 `raw.render` / `render.style_diag`。

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 拍照界面选「标准」（中性基线），拍一张 | `raw.render` 的 `ct=1.0`、`wb=[1,1,1]`、`wbTot=[1,1,1]`；画面与升级前**无差别**（默认零回归的真机侧） |
| 2 | 编辑器里把「对比度」拉到 −50 / +50，各拍一张（或用一条含 `[4]` 的预设） | `raw.render` 的 `ct` = 0.5 / 1.5；同一场景下中灰**不变**、暗部/亮部按方向拉开（与 §4.2 的曲线同向） |
| 3 | 开自动影调（`auto_tone=1`），对准有强光源的场景 | `cp > 1` 且 `ct` 非 1.0 时：`实际 contrast_param = cp × ct`（日志里 `cp` 与 `ct` 两个字段相乘），高光**没有**比只开 `cp` 时更"压不住"（覆盖写法的症状） |
| 4 | 编辑器里把「色温」拉到 2500K（≈ +109 mired，画面**变暖**），记下增益 | `render.style_diag` 的 `wbTot` B/R < 1 |
| 5 | **同一条预设**（含 `[88]`）套到拍照界面，对准同一场景 | `raw.render` 的 `wbTot` 与步骤 4 的 `render.style_diag` 的 `wbTot` **同一量级同方向**（两侧同一算法）；画面偏色方向一致 |
| 6 | `[89]` 色调拉到 +50（品红），拍照 | `raw.render` 的 `wbTot[1] < 1`（压 G）；画面偏品红 |
| 7 | 对着**同一场景**交替看取景预览 / 拍成片 | 预览与成片的偏色**方向与幅度一致**（三处消费点全覆盖的判据 —— 只有 `wbCompGNorm` 接上而 `prepareRawCommon` 没接时，这里会露馅） |

---

## 8. 交付清单

| 文件 | 性质 |
|---|---|
| `core/engine/src/render/FrameRenderer.cpp` | 改（`updateFilmicEV` 形参 + 接入点 + `wbTempTint` + 3 个消费点 + 2 个诊断字段） |
| `core/engine/src/render/FrameRenderer.h` | 改（`updateFilmicEV` 声明 + KDoc） |
| `core/engine/src/film/FilmParams.h` | 改（`[4]` / `[88][89]` 的 KDoc：两侧都生效） |
| `app/src/main/java/com/filmkit/app/camera/PresetScope.kt` | 改（`[88][89]` 移出 `EDITOR_ONLY`；删死条目；KDoc） |
| `app/src/test/java/com/filmkit/app/camera/PresetScopeTest.kt` | 改（3 处断言 + 新增 1 条） |
| `docs/reports/preset-DESIGN.md` | 改（§1.6 拆行 + `[4]` 行标注 + U-3 说明段；§9.2 两行 ✅；§9.5 U-3 完成） |
| `tools/check-u3-camera-contrast-wb.py` | **新增**（离线验收，退出码 0） |
| `docs/reports/u3-camera-contrast-wb-REPORT.md` | 本报告 |

**未碰**（任务书禁改清单）：`EditorScreen.kt` / `PhotoViewScreen.kt` / `CameraScreen.kt` /
`CameraController.kt` / `EditorExport.kt`（只读它确认量程与方向约定）。
**未 commit**（等架构师独立复核）。
