# U-2 交付报告：编辑器链路补齐 `[5..10]` 胶片曲线 + `[37][38]` 黑白点 + `[20..22]` 分区饱和三档

> 卡号 **U-2** ｜ 2026-10-11 ｜ 状态：**已交付**（**未真机验证**，验证步骤见 §10）
> 方案 = `docs/reports/preset-DESIGN.md` **§9**（用户 2026-10-11："**不统一的要统一掉，缺的补上**"）
> 前置（均在工作区、未提交）：U-1 `b1a295f`（`[25]`+`[23][24]`）、U-3（`[4]`+`[88][89]`）、
> U-4（`[78]`+`[90..94]`）。本卡是**参数统一方案的最后一卡**。
> 验证脚本：`tools/check-u2-editor-curve-sat.py`（**真跑引擎 shader + 真跑引擎 C++ 系数求解**）。

---

## 0. 一句话结论

编辑器链路（`kFsStyle`）补上了**显示域胶片曲线**（`[5..10]` latitude + `[37][38]` 端点）
与 **`[20..22]` 三档分区饱和**；默认值下**逐位零回归**（删段对比 `max|Δ| = 0.000 LSB`），
`[5..10]` per-channel 隔离、`[37][38]` 与 `[5..10]` 正交、三档分区全部实测通过；
与拍照链路的效果**方向一致、幅度同阶**（lat = 0.1 / 0.3 时与"用新 latitude 重拍"的逐点差
≤ 2.93/255）。**§9.2 的 9 项缺口至此全部关闭 ⇒ P-5 的作用域提示实例数 = 0**
（`PresetScope.kt` 的判据表清空，`presetScopeNotice` 恒返回 `null`）。

---

## 1. 交付物

| 文件 | 内容 |
|---|---|
| `core/engine/src/render/FrameRenderer.cpp` | 新增 `computeEditorCurve`（CPU 侧 12 个 5×5 系统 + 端点仿射）；`kFsStyle` 新增曲线 uniform/6 个逐通道求值函数/main 曲线段、`[20..22]` 三档段；`passStyle` 下发 |
| `core/engine/src/render/FrameRenderer.h` | `EditorCurve` 结构 + `computeEditorCurve` 声明 + `u_style_` 的 35 个新 uniform 槽位 |
| `core/engine/src/film/FilmParams.h` | `[5..10]`/`[20..22]`/`[37][38]` 的注释更新（"仅拍照"→"两侧都生效"） |
| `app/.../camera/PresetScope.kt` | 判据表 `SLOT_SCENES` **清空**（删 `[5..10]`/`[37][38]` 两条 + 删 `CAMERA_ONLY` 常量） |
| `app/.../camera/EditorEngineSession.kt` | 文件头 KDoc 槽位清单 34 → **44**（含数字与行） |
| `app/src/test/.../CameraStyleWhitelistTest.kt` | **新增护栏**（拍照链路读取集合三方对拍，§6） |
| `app/src/test/.../EditorStyleWhitelistTest.kt` | 抽取口径扩到**数组字段**（`shoulder[3]`/`toe[3]`） |
| `app/src/test/.../PresetScopeTest.kt`、`app/src/test/.../ui/PresetDetailTest.kt` | 随判据表清空更新断言（3 条作废用例 → 3 条"不可能再出现"用例） |
| `tools/check-style-passthrough.py` | 第 ④ 项白名单 + `style_uniforms` + `local_expect` 解析同步 |
| `tools/check-u2-editor-curve-sat.py` | **新增**离线验收脚本（判据 ⓪…⑥ + 4 项 mutation 自检） |
| `docs/reports/preset-DESIGN.md` | §1.6 三行改标 + 表下说明 + §9.2/§9.5 标记完成 |

---

## 2. 设计：显示域胶片曲线（`[5..10]` + `[37][38]`）

### 2.1 为什么是"相对默认 latitude 的**差值**"，而不是把 filmic 重跑一遍

拍照链路的 filmic 是 **"场景线性 → 显示域"** 的完整映射（默认参数下：线性 1.0 → 线性显示域
0.718（sRGB 0.83）、线性 4.35 → 1.0，中灰 0.1845 是不动点）。编辑器输入**已经是它的输出**
（成片 sRGB）⇒ 再跑一遍 = **二次影调**（整幅被再压一次、端点也不再是 0/1）。

用户改 `[5..10]` 的真实意图是"把这条成片按另一个 latitude 重新分布"，数学上就是
**C(lat) ∘ C(lat0)⁻¹**。本实现取它的一阶等价形式：

```
y = t + C(lat, t) − C(lat0, t)          （差值法，t = 显示域输入）
```

三条性质（都是验收判据）：
* `lat = 默认` ⇒ 两项相消 ⇒ **逐位恒等**（配合门控 ⇒ 零回归）；
* 两条曲线在 `t = 0 / 1` 处同值 ⇒ **端点严格不动**；
* 不需要求逆（GPU 上逐像素求四阶多项式的逆不可行）。

> **为什么不省掉 `C(lat0)` 用线性近似** `y = t + k·(t − C(lat0, t))`：实测最大偏差
> **5.28/255**（lat = 0.5），超出可接受范围 ⇒ 用精确差值（多一组 15 个 vec3 uniform）。

### 2.2 曲线族 `C(lat, ·)`：**沿用拍照链路同一套** `latitude → 过渡区位置` 数学

| 元素 | 取值 | 依据 |
|---|---|---|
| 域 | **显示域** `[0, 1]`，端点 `f(0)=0` / `f(1)=1` | 编辑器输入是已成片 sRGB；端点取显示域 0/1 |
| 中灰锚点 | `g = srgbEncode(0.1845) = 0.4666961`，**中段直线过 (g, g)** | 与拍照链路 filmic 的"中灰永远映射到自己"同语义（`[37][38]` 只改两端） |
| 中段斜率 `c` | `computeFilmicSpline(默认黑白点, contrast_param = 1, 默认 lat).contrast ≈ 1.4338`（静态缓存） | ① 它就是拍照链路**同一条 spline** 的派生量（同一份求解代码，改 `computeFilmicSpline` 会自动跟随）；② 实测用它时两侧效果**同阶**（§5） |
| 过渡区位置 | `toe = (1−lat_toe)·g + lat_toe·xmin`，`sh = (1−lat_sh)·g + lat_sh·xmax`；`xmin/xmax = 中段直线到达 SAFETY_MARGIN(0.01) 的位置` | **逐字沿用** `computeFilmicSpline` 的公式（darktable v3 同源） |
| toe / shoulder 段 | 同款 5×5 系统：`f(0)=0 / f'(0)=0 / f(toe)=y_toe / f'(toe)=c / f''(toe)=0`（shoulder 对称） | 与拍照链路同一套约束，只把端点从 `black_disp/white_disp` 换成显示域 `0/1` |
| 自检性质 | **`c = 1` 时整条曲线族恒等于 `y = t`** | 5 条约束被恒等函数同时满足 |

**`[37][38]` 的落点 = 端点重映射**（分段仿射、以显示域中灰为界、**中灰不动**）：

```
bl = srgbEncode(grey·2^black_ev) − srgbEncode(grey·2^black_ev_default)   （默认恒 0）
wh = srgbEncode(grey·2^white_ev)                                        （默认恒 1.0）
x < g:  t = s_lo·x + g·(1−s_lo),  s_lo = g/(g−bl)
x ≥ g:  t = s_hi·x + g·(1−s_hi),  s_hi = (1−g)/(wh−g)
```

* `black_ev` 增大 ⇒ `bl` 抬高 ⇒ 暗部被压到黑（**方向与拍照侧一致**：实测 −7.65 → −4 时
  暗部 −24.58 LSB、中灰 −0.37 LSB）；
* `white_ev` 减小 ⇒ `wh` 下降 ⇒ 亮部被拉到白（实测 4.56 → 2 时亮部 +25.90 LSB、暗部 +0.00）；
* 默认值下 `(a,b) = (1,0)`，`1.0·x + 0.0` 在 IEEE 下**逐位** == x；
* 防呆（信任边界）：非有限值回默认；`bl ∈ [−0.5, g−0.05]`、`wh ∈ [g+0.05, 1]`
  （否则分段仿射斜率爆炸 —— 与 darktable `SAFETY_MARGIN` 同性质）。

### 2.3 与既有 `[4]` contrast 的关系 = **正交；先后序 = `[4]` 先、本曲线后**

* `[4]` = 显示域绕 0.5 的**线性拉伸**（E2-2 既有实现，本卡**一行未动**）；
* 本曲线 = "相对默认 latitude 的**形状差**"（非线性重分布）；
* **互不读取对方的参数** —— 本段的 `c` 恒取"默认黑白点 + `contrast_param = 1`"的派生值，
  **不含 `p.contrast`**。若把 `[4]` 折进 `c`，"调 `[4]` 会连带改变胶片曲线的形状" ——
  正是规格 §3.4.3 记的"多个影调旋钮互相打架"的事故形态（历史上出过一次）；
* shader 里 `[4]` 在前、本曲线在后，**各自独立门控**（关掉任一个另一个照常工作）。

### 2.4 `[20..22]` 三档分区饱和：与 `kFsFilm` **同口径**

```glsl
float l = luma(c);                       // Rec.709（U-2 起从 Rec.601 改为 709，与 kFsFilm 同源）
float ws = exp(-l*3.0), wh = smoothstep(0.5,1.0,l), wm = max(0.0, 1.0-ws-wh);
float s = clamp(uSatShadow*ws + uSatMid*wm + uSatHigh*wh, 0.0, 2.0);
```
* 此前编辑器**只读 `[21]`**（UI 一个滑杆同写三槽）⇒ 预设里只含 `[22]` 时被静默忽略；
  现在三档都生效；
* **默认三槽 = 1.0 ⇒ `s = 1` ⇒ `mix(vec3(l), c, 1.0)` 在 IEEE 下精确恒等**（与 luma 定义无关）
  ⇒ 逐位零回归（脚本判据 ①b 实测 `max|Δ| = 0.000 LSB`）。

---

## 3. 验收判据逐条证据

> 全部来自 `python3 tools/check-u2-editor-curve-sat.py`（真跑引擎 shader + `clang++` 编译
> **工作区** `computeEditorCurve` 打印系数）。退出码 0、`全部通过`。

| 判据 | 结果 | 实测数字 |
|---|---|---|
| **①a 默认值逐位零回归**（删段对比） | ✅ | `max|Δ| = 0.000 LSB`，非零像素 `0/768`（`[5..10]=0.01` 三通道相同 + `[37][38]` 默认 ⇒ `uCurveOn=0` ⇒ 整段不进） |
| **①b `[20..22]=1.0` 逐位零回归**（强制 `uSatOn=1`） | ✅ | `max|Δ| = 0.000 LSB`（`mix(vec3(l), c, 1.0)` 精确恒等） |
| **② `[5..10]` per-channel 隔离**（只改 `[8]` toe_r） | ✅ | `max|ΔR| = 3.52 LSB`；`max|ΔG| = 0.00`、`max|ΔB| = 0.00`（**逐位不变**） |
| **② 曲线端点不动**（极端 lat = 0/1） | ✅ | 输入 0/255 的输出 = 0.00 / 255.00，与默认**逐位**相同 |
| **③ `[37]` 生效** | ✅ | `black_ev −7.65 → −4`：暗部(0.1) **−24.58 LSB**、中灰(0.467) −0.37 LSB |
| **③ `[38]` 生效** | ✅ | `white_ev 4.56 → 2`：亮部(0.9) **+25.90 LSB**、暗部(0.1) +0.00 LSB |
| **③ `[37][38]` 与 `[5..10]` 正交** | ✅ | 改黑点时 `x ≥ 中灰` 的 **34 列逐位相同**；改白点时 `x < 中灰` 的 **29 列逐位相同** |
| **④ `[20..22]` 分区正确** | ✅ | 权重峰值：`[20]` @luma 0.039 → `[21]` @0.633 → `[22]` @0.992（**严格单调**）；区间外抑制 < 25% 峰值；绝对响应峰值 8.6 / 30.9 / 41.0 LSB |
| **⑤ 与拍照侧一致性** | ✅ | 见 §5 |
| **⑥ mutation 自检 ×4** | ✅ | 见 §7 |
| ⓪ 系数真值来自引擎源码 | ✅ | `solve5` + `srgbEncodeD` + `computeFilmicSpline` + `computeEditorCurve` 括号配平提取 + `clang++` 真跑 |

---

## 4. 正交性实测（判据 3 的补充：`[4]` × `[5..10]`）

* **参数层**：`computeEditorCurve` 的入参只有 `lat_sh[3] / lat_toe[3] / black_ev / white_ev`
  —— 签名里**没有** `contrast`，代码里也不读 `p.contrast`（`check-style-passthrough.py` ④
  的抽取结果可证：`passStyle` 读 `contrast` 只为既有的 `uContrast` 段）。
* **数学层**：`[4]` 是"绕 0.5 的线性拉伸"（`(c−0.5)·k+0.5`），本曲线是"以中灰为锚的形状差"
  —— 前者是仿射、后者在中灰处一阶恒等，两者复合的顺序在 shader 里固定（`[4]` 先）。
* **实测层**（判据 ③ 的两条"逐位不变"）：改 `[37][38]` 时另一侧区间**逐位不变**，
  说明端点重映射与形状分量在实现上完全解耦。

---

## 5. 与拍照链路的一致性对照（判据 ⑤）

**对照口径**（域不同 ⇒ 只能比"相对默认的变化"，不能比绝对值）：

1. 取线性灰阶 `S`（拍照链路的**场景输入**），跑拍照链路的 filmic + `srgbEncode`
   ⇒ `cam0 = 用默认 latitude(0.01) 的成片`（显示域）、`ideal = 用目标 latitude 重拍`；
2. 把 `cam0` 送进**编辑器曲线**（真跑 `kFsStyle`）⇒ `ed`；
3. 比 `|ed − ideal|`（编辑器能否把"默认成片"改造成"用新 latitude 重拍"的样子）。

| latitude | 编辑器 `max|ed − ideal|` | 拍照重拍 `max|ideal − cam0|` | 结论 |
|---|---|---|---|
| 0.1 | **1.19/255** | 1.16/255 | 几乎一致 |
| 0.3 | **2.93/255** | 4.06/255 | 同阶 |
| 0.5 | 4.80/255 | 7.19/255 | 同阶（编辑器略保守） |

**方向一致性**（lat 0.01 → 0.5 的暗部）：拍照侧 `Δ = −2.60/255`、编辑器侧 `Δ = −0.62/255`
—— **同号**（都是压暗暗部 = 对比增强），与"lat 大 ⇒ 中段线性区更宽 ⇒ 对比更强"的语义一致。

代表性逐点（lat = 0.3，单位 /255）：

| cam0 | ed | ideal | ed−ideal |
|---|---|---|---|
| 10.8 | 10.1 | 7.2 | +2.93 |
| 23.2 | 21.3 | 19.3 | +2.06 |
| 40.1 | 37.4 | 37.1 | +0.29 |
| 74.0 | 71.8 | 73.4 | −1.52 |
| 87.5 | 85.7 | 87.3 | −1.59 |

**诚实标注**：`c`（中段斜率）是 **log 域**（spline 的 e 轴）的派生量，本曲线把它当**显示域**
斜率用 ⇒ 只保证"**方向一致 + 幅度同阶**"，**不宣称逐点等价**（域不同是硬约束，见 §9.1）。

---

## 6. 新护栏：拍照链路读取集合（架构师交办）

### 6.1 补的是哪一环

| 已有的护栏 | 保证的关系 |
|---|---|
| `PresetScopeTest` | **代码表（`SLOT_SCENES`） == 文档表（§1.6）** |
| `EditorStyleWhitelistTest` | **脚本白名单 == `passStyle` 实际读的 `p.<字段>` == KDoc**（编辑器链路三方） |
| ★ **`CameraStyleWhitelistTest`（本卡新增）** | **§1.6 的"拍照链路"列 == `FrameRenderer.cpp` 拍照链路实际读的槽位 == 代码常量** |

U-3 发现的真实漏洞正落在最后一环：§1.6 的 `[4]` 行**改前就错标 ✅**（`passFilm` 对
`p.contrast` 零引用），而三张表各自"内部一致"、测试全绿 ⇒ 补上这一环。

### 6.2 抽取口径（写进测试 KDoc，含覆盖不到的部分）

* 源 = `FrameRenderer.cpp`（唯一文件）；**剥离字符串字面量与注释**（`R"(...)"` 整段 shader、
  普通字符串、`//`、`/* */`）—— 必须剥离，否则注释里写着的 `p.contrast` 会被当成实际引用；
* 半径 = 全文件每个函数体内的 `p.<字段>`（间接读取自动覆盖：把 `p` 整体传给别的函数时，
  被调函数也在同一文件里被扫描）；
* **排除编辑器链路的两个函数** `passStyle` / `submitDisplay`，且必须是**位置级**排除
  （把函数体替换成 `{}` 后再抽）—— 字段级差集（`allRefs − editorRefs`）会连带抹掉两条链路
  都读的字段，实测那样得到 27 个字段、∩ 进预设 = **空集**（第一版就是这么写的，被本测试
  自己抓住：`expected 34 slots but was []`）；
* 过滤 `presetSlotIncluded`（只比"进预设"的槽位）；槽位号来自 `FilmParams.h` 的声明注释，
  **数组字段**（`shoulder[3]`/`toe[3]`）展开成 3 个槽位；
* **覆盖不到**：`Bridge.cpp`/Kotlin 侧、经 FilmParams 之外通道进入引擎的值、以及"读取后
  是否真有视觉效果"（那一层由离线数值脚本守）。

### 6.3 四个用例

1. `engineCameraPathMatchesWhitelist`：引擎实际 == 常量（**34 个槽位**）；
2. `designMatrixCameraColumnIsBackedByEngine`：§1.6 标 ✅ 的必须真读（**抓 U-3 那类漏洞**）；
3. `whitelistIsCoveredByDesignMatrix`：常量里的每个槽位 §1.6 必须标非 ❌，且**反向集合相等**；
4. `whitelistOnlyContainsPresetSlots`：定义域健全性。

---

## 7. mutation 自检（6 项，全部"能红"）

| # | 篡改 | 结果 |
|---|---|---|
| M1 | CPU 侧门控漏掉 `[37][38]`（只判 latitude）⇒ 黑白点静默失效 | ✅ 抓到：门控 = 0 时改黑白点 `max|Δ| = 0.00 LSB`（与基线相同），正确版 `24.58 LSB` |
| M2 | shader 删掉差值项（只留端点重映射） | ✅ 抓到：篡改版 vs 正确版 `max|Δ| = 3.52 LSB`（差值项不是死代码） |
| M3 | `curveG` 复用 R 的系数（per-channel 串味） | ✅ 抓到：只改 `[6]` shoulder_g 时正确版 G 变 `5.85 LSB`、篡改版 `0.00 LSB` |
| M4 | C++ 侧三通道共用第 0 通道（per-channel 串味） | ✅ 抓到：只改 `[8]` toe_r 时篡改版 G 变 `3.52 LSB`（正确版 = 0） |
| **G1** | §1.6 的 `[37][38]` 行"拍照链路"列 ✅ → ❌ | ✅ 红：`whitelistIsCoveredByDesignMatrix FAILED`（4 tests, 1 failed） |
| **G2** | 新护栏常量里删掉 `add(78)` | ✅ 红：`engineCameraPathMatchesWhitelist` + `whitelistIsCoveredByDesignMatrix`（4 tests, 2 failed） |
| **G3** | `EditorEngineSession.kt` KDoc 删掉 `[78] clarity` 一行 | ✅ 红：`kdocListMatchesScriptWhitelist FAILED`（3 tests, 1 failed） |

（M1–M4 在脚本内自动跑；G1–G3 用临时篡改 + `./gradlew testDebugUnitTest --tests ...` 实测，
篡改后已全部还原 —— `git status` 与文件内容已核对。）

---

## 8. 同步的地方 / 未动的地方

**已同步**：
1. `tools/check-style-passthrough.py` 第 ④ 项白名单（新增 `shoulder`/`toe`/`filmic_black_ev`/
   `filmic_white_ev`/`sat_shadow`/`sat_high`；`sat_mid` 保留）—— 实测 40 个字段通过；
   另同步了 `style_uniforms`（`uSat` → 三档 + `uCurveOn`）与 `local_expect` 的解析
   （Rec.601 → 709 + 三档加权），否则 ⑬e/⑬g 会假红；
2. `EditorEngineSession.kt` 文件头 KDoc（34 → 44 槽位）；
3. `PresetScope.kt`（删 `[5..10]`/`[37][38]` 两条 + 删 `CAMERA_ONLY` 常量）；
4. `docs/reports/preset-DESIGN.md` §1.6（三行改标，**表格结构未动**）+ 表下说明 + §9.2/§9.5；
5. `FilmParams.h` 的三处注释（"仅拍照"→"两侧都生效"，防注释腐烂）。

**未动**（任务明令）：`EditorScreen.kt` / `PhotoViewScreen.kt` / `CameraScreen.kt` /
`CameraController.kt` / `EditorExport.kt` 预设数据层；**未 commit**。

**关于 `SLOT_SCENES` 变空**（任务要求说明）：判据表清空 ⇒ `presetSlotScenes` 恒返回
"两侧都生效" ⇒ `presetScopeNotice` **恒 `null`** ⇒ 三处入口不再显示"仅拍照/仅后期"提示
（**用户要的终局**）。**保留函数与机制不删**：① 三处入口的调用点在禁改文件里；
② 它是**回归护栏** —— 将来若又出现单侧落点，往表里加条即自动恢复提示，且 `PresetScopeTest`
会立刻要求 §1.6 同步。`SLOT_LABELS`（中文名表）同样保留（"名字从哪来"的知识）。

---

## 9. 构建与测试

```
rm -f app/build/filmengine/**/FrameRenderer.cpp.o  app/build/filmengine/jniLibs/**/libfilmengine.so
rm -rf app/build/test-results
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

* **BUILD SUCCESSFUL in 23s**；**`46 actionable tasks: 46 executed`**；
* **441 tests completed, 0 failed**（基线 437；+4 = 新增的 `CameraStyleWhitelistTest`；
  `PresetScopeTest` 删 3 加 3 净 0）；
* native **真编**已核对：`FrameRenderer.cpp.o` 与 `libfilmengine.so` 的时间戳 = 本次构建时间；
* 工具链退出码全 0：`validate-shaders.sh`（全部 shader 校验通过）、`check-diag-fields.py`、
  `check-toeshoulder.py`、`check-style-passthrough.py`、`check-u2-editor-curve-sat.py`。

---

## 10. 未真机验证 + 真机验证步骤

**本卡未做真机验证**（离线判据全部来自"真跑引擎 shader + 真跑引擎 C++ 系数求解"，
但不是设备端）。

真机步骤（建议顺序）：
1. 装机后**先验零回归**：不套任何预设，打开编辑器改一个无关参数（如曝光）再改回 ⇒
   出图应与改前版本**逐位相同**（`[5..10]=0.01`/`[37][38]` 默认/`[20..22]=1.0`）；
2. **`[5..10]` 生效**：设置页/预设里把某条含 `toe`/`shoulder` 的配方套到一张人像上
   ⇒ 应看到**暗部/高光色偏**（per-channel ⇒ 曲线分通道移动），且**黑白点不动**；
3. **`[37][38]` 生效**：把黑点从 −7.65 调到 −4 ⇒ 画面暗部明显压暗、中灰几乎不动；
   白点 4.56 → 2 ⇒ 高光更早溢出；
4. **`[20..22]` 三档**：编辑器饱和度滑杆（同写三槽）拖动 ⇒ 与旧版观感一致（默认口径不变）；
   用只含 `[22]` 的预设验证"高光去饱和、暗部不动"；
5. **与拍照侧对照**：同一场景，拍照侧把 `[5..10]` 调成某值拍一张，再用编辑器对
   "默认参数拍的同场景"施加同一 `[5..10]` ⇒ 两张应**观感接近**（域不同，不要求逐像素）；
6. 记录 `diag` 日志里的 `sat` 字段与出图观感，回报偏差。

---

## 11. 诚实标注（局限与已知近似）

1. **域不同是硬约束**：拍照链路是 log 域（RAW 线性 → log 编码 → spline → OETF），编辑器是
   显示域。本卡只承诺"**参数语义一致 + 相对默认的效果方向一致、幅度同阶**"，
   **不承诺逐点等价**（实测 ≤ 2.93/255 @ lat = 0.3）。
2. **`c` 的量纲**：中段斜率取自 log 域的派生 `contrast`（≈1.4338），当作显示域斜率使用
   —— 这是**有意的近似**（有依据：同一条 spline 的派生量，且实测幅度匹配）；
   若将来发现效果偏强/偏弱，`c` 是唯一的标定旋钮（改一处即可，不影响端点）。
3. **lat 的可见区间偏一侧**：默认 0.01 已接近"过渡区紧贴中灰"端，所以滑杆大部分行程是
   "对比减弱"方向（lat 大 ⇒ 更接近线性）。这与拍照链路**同向**，不是本卡的偏差。
4. **极端值的退化**：`black_ev` 极大 / `white_ev` 极小时，端点仿射被防呆 clamp 到
   "暗部/亮部整段压死"（不会出 NaN）；这与拍照链路的平滑 rolloff 不同（显示域没有
   log 域那种余量），属已知近似。
5. **`[20..22]` 的 luma 从 Rec.601 改为 Rec.709**：这是本卡对编辑器链路唯一的"非新增"
   行为变更。默认值（三槽 = 1.0）下 `sat = 1` ⇒ mix 恒等 ⇒ **零回归**；非默认值下的输出
   变化 = 消除两侧 luma 口径差异（改前中心 luma 与分区权重用的是两套 luma）。
6. **`[62..65]` 光晕 / `[69..77]` 层间效应**仍两侧都未实现（E4-5/E4-6），且**进不了预设**
   ⇒ 不在本卡范围，P-5 的提示口径对它们本就无实例。
