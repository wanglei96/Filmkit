# U-1 编辑器链路补齐 `[25]` 单色 + `[23][24]` 分离色调 —— 交付报告

> 实现子代理：DSH 会话（U-1，2026-10-11）。工作目录 `/Users/wl/Downloads/胶片相机`。
> 用户指令（唯一依据）："**影调重建拍照侧和编辑器侧不一样？不统一的要统一掉，缺的补上。**"
> 规格：`docs/reports/preset-DESIGN.md` §9（参数统一方案）—— 本卡 = §9.5 分卡表的 **U-1** 行。
> **代码改动全部留在工作区，未 commit**（等架构师独立复核）。

> ⚠️ **工作区里还有并行 agent 的改动，不是本卡的**：`EditorScreen.kt`、`PhotoViewScreen.kt`、
> `CameraScreen.kt`、`CameraController.kt`、`CameraPresetApply.kt`、`EditorExport.kt`、
> `EditorPresetTest.kt`、`PhotoViewZoomTest.kt`、`CameraPresetApplyTest.kt`。
> 本卡按纪律**一律没碰** `EditorScreen.kt` / `PhotoViewScreen.kt`。本卡改到 **11 个文件**：
> `core/engine/src/render/FrameRenderer.cpp`、`core/engine/src/render/FrameRenderer.h`、
> `tools/check-style-passthrough.py`、`tools/check-diag-fields.py`、
> `app/.../camera/EditorEngineSession.kt`（KDoc）、`app/.../camera/PresetScope.kt`、
> `app/src/test/.../PresetScopeTest.kt`、`app/.../ui/PresetDetail.kt`（1 行注释一致性）、
> `app/src/test/.../ui/PresetDetailTest.kt`（护栏前提随 U-1 变更，见 §2.5 ⑦）、
> `docs/reports/preset-DESIGN.md`（§1.6 两行 + §9.2/§9.5 进度标记）、本报告。

---

## 0. 结论摘要

1. **`[25]` 单色在编辑器链路生效**：一条含 `[25]=1` 的"黑白预设"套用到编辑器里**现在会变黑白**
   （离线实测 `max|R−G| = max|G−B| = 0`，用显示域 Rec.709 luma 去色）—— 用户实测到的荒谬现象消除。
2. **`[23][24]` 分离色调在编辑器链路生效**：阴影暖 / 高光冷加法偏移，逐段灰阶实测
   `luma16 ΔRGB=(+10.5,+4.2,−8.5)` → `luma224 ΔRGB=(+0.8,+0.2,−0.8)`（阴影暖，暗部最重）；
   `luma160 ΔRGB=(−1.3,−0.1,+1.9)` → `luma224 ΔRGB=(−6.5,−0.1,+10.8)`（高光冷，亮部最重）。
3. **效果对齐 = 数学逐字相同**：`kFsFilm` 与 `kFsStyle` 的 `luma()` / `ws`·`wh` 权重 / split 加法 /
   mono 去色**四段文本逐字比对 0 处差异**（新护栏 ⑰d，与既有暗角 ⑨ / 颗粒 ⑨b 同手法）。
4. **默认零回归是逐位判据**（不是"≤1 LSB"）：三槽默认时，新 shader 与"把 U-1 段整段删掉的 shader"
   在 LUT/颗粒/对比度/影调分区/暗角**全开**下**逐位相同**（`np.array_equal = True`，非零像素 0）。
5. **域差异是唯一的差异，且已量化**：同一物理亮度（线性 0.18）下阴影权重 kFsFilm `0.583` vs
   kFsStyle `0.250`（43%）、高光分区在编辑器侧**更早起跳**（物理亮度 ≥0.214 即进入 `wh` 带）。
   理由与"不decode 回线性"的硬约束见 §3。
6. **护栏与构建全绿**：`validate-shaders.sh`（62 个 GLSL 全部 OK）/ `check-diag-fields.py` /
   `check-style-passthrough.py` 退出码 0；`assembleDebug` 退出 0（删 `.o`/`.so` 后**真编**，
   `.so` 时间戳更新）；`testDebugUnitTest --rerun-tasks` **27 actionable tasks: 27 executed、
   436 tests / 0 failures / 0 skipped、退出码 0** —— 见 §5.4。
7. **未真机验证**（本机无连接设备）—— 见 §7。

**需要架构师裁决的 2 件事**（详见 §6）：§6.1 编辑器参数面板**没有** `[23][24][25]` 滑杆（U-6 才做，
本卡的生效路径只有"预设套用"）；§6.2 "套用原片风格"对已烘焙分离色调的成片会**二次叠加**一次偏移。

---

## 1. 改了什么（引擎侧）

### 1.1 `kFsStyle` 补三项槽位

| 位置 | 改动 |
|---|---|
| `FrameRenderer.cpp` kFsStyle uniform 区 | 新增 `float luma(vec3 c)`（**逐字拷自 kFsFilm**，Rec.709）+ `uniform vec2 uSplit` + `uniform float uMono` |
| `FrameRenderer.cpp` kFsStyle 主段 | 新增 U-1 段：**vignette 之后、3D LUT 之前**（= 拍照链路的相对位置：kFsFilm 末端 → kFsOut 的 OETF → LUT） |
| `FrameRenderer.cpp` `initPrograms` | `u_style_.split` / `u_style_.mono` 的 `glGetUniformLocation` |
| `FrameRenderer.h` `u_style_` 结构体 | 新增 `split = -1, mono = -1` 两个 `GLint` |
| `FrameRenderer.cpp` `passStyle` CPU 侧 | `glUniform2f(u_style_.split, p.split_warm, p.split_cool)` + `glUniform1f(u_style_.mono, p.monochrome)`（**裸值下发**，与 `passFilm` 同口径） |
| `FrameRenderer.cpp` `submitDisplay` 诊断 | `,"splitW":` / `,"splitC":` / `,"monoOn":`（与既有 `toneOn`/`locOn` 同出口、同风格） |

**主段原文**（`kFsStyle`）：

```glsl
    if (uSplit.x != 0.0 || uSplit.y != 0.0 || uMono > 0.0) {
        float l = luma(c);
        float ws = exp(-l * 3.0);
        float wh = smoothstep(0.5, 1.0, l);
        c += uSplit.x * ws * vec3(0.05, 0.02, -0.04)
           + uSplit.y * wh * vec3(-0.03, 0.0, 0.05);
        c = mix(c, vec3(luma(clamp(c, 0.0, 4.0))), uMono);
    }
```

`kFsFilm` 的对应三行（`FrameRenderer.cpp:4487-4493` / `:4539`）只差左值名（`out3` vs `c`）——
这正是 ⑰d 证明的"逐字相同"。**顺序也照抄**：split 先加偏移、mono 后去色
（mono 会把 split 的偏移按 709 权重转成亮度变化，这是拍照链路的既有行为，不"顺手改"）。

### 1.2 为什么新增 `luma()` 而不用本 shader 既有的 Rec.601 内联 dot

`kFsStyle` 其它段（清晰度 `blurLuma16` / 饱和度 / 影调分区 / 颗粒）用的是 **Rec.601**
（`0.299/0.587/0.114`），而 `kFsFilm` 的 `luma()` 是 **Rec.709**（`0.2126/0.7152/0.0722`）。
U-1 这三行的语义是"与拍照链路同一套分区权重"，换成 601 就是任务书点名的"**默默换一套**"
（`ws`/`wh` 的分界会跟着漂，效果对不上）。所以新增一个**专用**的 `luma()`（Rec.709），
并在 shader 注释里写明"这里故意不统一"（用户说的"统一"针对**参数**，不是链路内部权重）。

副作用是好的：三行数学变成**文本可对拍**的 —— 新增护栏 ⑰d 与既有暗角 ⑨ / 颗粒 ⑨b 完全同型
（改一处漏另一处立刻红）。

---

## 2. 验收判据逐条

判据全部由 `tools/check-style-passthrough.py` 的 **⑰a/⑰b/⑰c/⑰d** 离线真跑引擎 shader
（`tools/engine-shader-offline.py` 的执行器，读回的浮点输出再按 RGBA8 口径量化）实测。

### 2.1 判据 1 · 默认零回归（逐位）

**验证方法**：不看"与输入差 ≤1 LSB"（dither 是 ±1 LSB 的确定性噪声，它会掩盖"新段在默认值下
到底跑没跑"），而是构造一个**把 U-1 段整段删掉的同版 shader**（`shader_src` + `slice_from` 定位
删除），两者跑同一输入、同一组**非平凡**参数（LUT 开 0.8 + 颗粒 25 + contrast 1.15 + sat 1.1 +
影调分区 +3 + clarity 0.3 + 暗角 −0.6），比较浮点输出。

```
⑰a U-1 默认零回归（[23]=[24]=[25]=0，LUT/颗粒/对比度/影调分区/暗角全开）：
    与"删掉 U-1 段的 shader"逐位相同 = True（max|Δ| = 0 LSB，非零像素 = 0）
```

⇒ 门控短路 + 对链路其它段零影响，"删段 shader"就是"这段不存在"的可执行定义。
（既有 ①/⑩/⑭a 的"≤1 LSB"判据同时仍然通过。）

### 2.2 判据 2 · `[25]=1` 生效（变黑白）

**口径**：显示域 **Rec.709 luma** 去色（与 `kFsFilm` 的 `luma()` 同权重），位置在 LUT 之前。

```
⑰b [25]=1 单色：实测 max|R−G| = 0, max|G−B| = 0 LSB（判据 ≤1 ⇒ 真灰）；
    与解析期望（709 luma 去色）max|Δ| = 2.0 LSB（判据 ≤3 = dither±1 + 舍入模式差 ≤1 + 16F 余量）；
    平均 luma 输入 130.4 → 输出 130.3（保持）
```

⇒ 输入是含纯红/绿/蓝饱和块的合成图（`make_input`），输出**逐像素 R=G=B**，且亮度不变。
"2.0 LSB"与 ⑬b 的既有口径（`≤3 = dither±1 + 量化余量`）一致，不是实现偏差。

### 2.3 判据 3 · `[23][24]` 生效（分区 + 可测数值）

**输入** = 9 段横向灰阶（16/32/64/96/128/160/192/224/250），逐段给实测平均偏移：

```
⑰c [23]=+1 阴影暖：与解析期望 max|Δ| = 2.0 LSB（判据 ≤3）
   luma16: ΔRGB=(+10.5,+4.2,-8.5) | luma32: (+8.7,+3.5,-7.0) | luma64: (+5.9,+2.4,-4.8)
   luma96: (+4.1,+1.6,-3.3) | luma128: (+2.7,+1.0,-2.2) | luma160: (+1.8,+0.6,-1.6)
   luma192: (+1.2,+0.4,-1.1) | luma224: (+0.8,+0.2,-0.8) | luma250: (+0.7,+0.3,-0.5)
   方向：暗段(16/255) ΔR=+10.5 ΔB=-8.5；亮段(224/255) ΔR=+0.8 ΔB=-0.6 ⇒ 方向正确
⑰c [24]=+1 高光冷：与解析期望 max|Δ| = 1.0 LSB
   luma16: (-0.0,-0.0,-0.0) | luma32: (-0.0,-0.0,-0.0) | luma64: (-0.1,-0.1,-0.1)
   luma96: (-0.0,-0.0,-0.0) | luma128: (-0.0,-0.0,-0.0) | luma160: (-1.3,-0.1,+1.9)
   luma192: (-3.9,-0.2,+6.4) | luma224: (-6.5,-0.1,+10.8) | luma250: (-7.6,+0.0,+5.0)
   方向：暗段(16/255) ΔR=-0.0 ΔB=-0.0；亮段(224/255) ΔR=-7.2 ΔB=+7.3 ⇒ 方向正确
```

读数即"可测数值"：`[23]` 在 16/255 处 +10.5 R / −8.5 B（暖），到 224/255 衰减到 +0.8/−0.6；
`[24]` 在 128/255 以下**恒 0**，从 160/255 起跳（−1.3 R/+1.9 B），224/255 达 −6.5 R/+10.8 B（冷）。
`luma250` 段的 ΔB 偏小（+5.0 而不是 ~12）是 **8bit 白点 clamp** 的本征行为（0.98 + 0.05 > 1.0），
判据里已按 `rint+clamp` 口径对齐（见脚本 `expect8()` 的注释 —— 本卡第一次跑就在这里误判过一次）。

### 2.4 判据 4 · 效果对齐证据（与 `kFsFilm` 对照）

**（a）公式层：逐字相同**

```
⑰d kFsFilm 与 kFsStyle 的 U-1 数学逐字比对：差异段 = 无（0 处）
```
比对四段：`luma 定义` / `ws`·`wh 权重` / `split 加法` / `mono 去色`（左值变量名归一化后逐字比）。

**（b）数值层：同一物理亮度下的方向与幅度**

| 线性域 `l`（kFsFilm 的输入域） | 显示域 `srgbEncode(l)`（kFsStyle 的输入域） | kFsFilm `ws` / `wh` | kFsFilm ΔR(阴影暖) / ΔB(高光冷) | kFsStyle `ws` / `wh` | kFsStyle ΔR / ΔB |
|---|---|---|---|---|---|
| 0.02 | 0.1517 | 0.9418 / 0.0000 | +12.01 / +0.00 | 0.6344 / 0.0000 | +8.09 / +0.00 |
| 0.05 | 0.2478 | 0.8607 / 0.0000 | +10.97 / +0.00 | 0.4755 / 0.0000 | +6.06 / +0.00 |
| 0.18 | 0.4614 | 0.5827 / 0.0000 | +7.43 / +0.00 | 0.2506 / 0.0000 | +3.19 / +0.00 |
| 0.50 | 0.7354 | 0.2231 / 0.0000 | +2.84 / +0.00 | 0.1101 / 0.4561 | +1.40 / +5.82 |
| 0.90 | 0.9547 | 0.0672 / 0.8960 | +0.86 / +11.42 | 0.0570 / 0.9768 | +0.73 / +12.45 |

**方向完全一致**（阴影暖 = +R+G−B、高光冷 = −R+B，两侧同一组系数 ⇒ 同号）；
**幅度同一量级**（暗端 8–12 LSB，亮端 6–12 LSB），但**分区边界不同**：
同一物理亮度下编辑器侧 `ws` 更小（中灰 0.583 → 0.250 = **43%**）、`wh` 更早起跳
（`l_disp ≥ 0.5` ⟺ 物理亮度 ≥ **0.214**，而 kFsFilm 要 0.5 才起跳）。

**这是域差异，不是实现差异** —— 证据有三条：
1. 公式逐字相同（⑰d = 0 差异）；
2. 差异的方向与大小**完全由 `l` 的域**预测：`ws = exp(−3l)` 是 `l` 的单调函数，
   `l_disp = srgbEncode(l_lin) ≥ l_lin` 对一切 `l_lin ∈ (0,1)` 成立 ⇒ 编辑器侧必然更小；
3. ⑰a 的"删段 = 逐位相同"排除了"新段偷偷改了别的东西"。

### 2.5 判据 5 · 同步完成（任务书 4 处 + 3 处必做联动）

| # | 位置 | 状态 |
|---|---|---|
| ① | `tools/check-style-passthrough.py` 第 ④ 项 `want` | ✅ 加 `monochrome` / `split_warm` / `split_cool`（34 个字段，脚本输出见下） |
| ② | `EditorEngineSession.kt` 文件头 KDoc | ✅ "31 个槽位" → "**34 个槽位**"，清单加 `[23] split_warm [24] split_cool [25] monochrome` |
| ③ | `PresetScope.kt` | ✅ 删 `for (s in 23..25) put(s, CAMERA_ONLY)`（⇒ 两侧生效）+ 删 `SLOT_LABELS` 两条死标签；`PresetScopeTest` 按新口径改（新增 `splitToneAndMonochromeAreEffectiveInEditor` / `stillCameraOnlyParamsAreReportedInEditor`，`singleSide` 断言 19 → **16**，内置两条的"必须提示"→"不该提示"） |
| ④ | `preset-DESIGN.md` §1.6 两行 | ✅ 编辑器链路列 `❌` → `✅`（表格结构未动，仍是 `\| 槽位 \| 参数 \| 拍照 \| 编辑器 \| 进预设？ \|`）；另更新了表格下方那段用它举例的说明 + §9.2/§9.5 的进度标记 |
| ⑤ | `tools/check-diag-fields.py` | ✅ 加 `splitW` / `splitC` / `monoOn`（`submitDisplay` + 前置 `passStyle(`） |
| ⑥ | `ui/PresetDetail.kt` 1 行注释 | ✅ 原文说 `[23..25]` 的名字来源是 `SLOT_LABELS`；删标签后改成"历史术语，未新造译名" |
| ⑦ | `ui/PresetDetailTest.kt`（并行卡 U-6a 新建的护栏） | ⚠️ **本卡改动的必然后果**：`detailLabelsMatchScopeNoticeWording` 的循环里含 `[23..25]` 并断言"应在某条链路上被标为不生效" —— U-1 后前提失效（首跑实测红）。已按新口径改：循环去掉这三槽，另加三条独立断言钉"详情页仍用 P-5 的术语（分离色调 / 黑白），不是工程名" |

`PresetScopeTest` 的 §1.6 正则对拍（`designMatrix()`）现在读到 `[23][24]`/`[25]` 两侧 ✅ ⇒ 期望
`BOTH` == `presetSlotScenes()` 的 `BOTH`（不在 `SLOT_SCENES` 里）—— 文档与代码不可能各自漂移。

---

## 3. 域差异的说明与理由（任务书要求"给理由，不要默默换一套"）

**事实**：`kFsFilm` 的 `l` 是**线性域** luma（ACEScg 工作空间、filmic 之后）；`kFsStyle` 的 `c`
是**已成片的 sRGB 显示域**编码值，`l` 是显示域 luma。⇒ 同一组 `[23][24]` 在本编辑器里
"阴影更紧、高光更早"（§2.4 的数字）。

**为什么不做"先把 c decode 回线性、算完再 encode"的域对齐**（这是唯一能让两侧数值也贴近的做法）：

1. **ADR-7 是本 shader 的硬约束**：`kFsStyle` 的注释原文 —— "本 shader 内**任何地方都不得插入
   OETF/EOTF 转换**"，理由是输入已是编码值，插一次 = 二次编码/解码，且引入一次量化往返。
   护栏 ⑬f 更直接：`kFsStyle` 内 `srgbDecode(` 的出现次数必须恰为 **3**（定义 + `chainLuma` +
   主路径），为 U-1 再加一次就是 **4 ⇒ 立刻红**。
2. **"域内实现，语义对齐"就是 §9.3 的方案**：架构师对编辑器侧 4 项补口的规格原文是
   "**显示域实现**，注意与拍照侧**效果对齐**"，并对 `[23][24]` 写明"照 `kFsFilm` 的 `uSplit`
   三行**同式**"。⇒ 公式同式 + 域内实现 = 规格要求的做法；把域也掰成一样反而不是本卡规格。
3. **代价/收益**：为了把"中灰权重 0.583 vs 0.250"对齐，要在**每个像素**上多一次
   `srgbDecode`（≥6 次 pow）—— 而这两项本身是"代价极低、见效最快"的卡（§9.3 原文）。
4. **验收口径也允许**：§9.3 的"一致性怎么证明"写的是"**不要求逐位相同**（域不同），
   要求**观感一致**"（同一条款里给的是 ±4/255 的目标带，本卡实测暗端 / 亮端偏移都在 6–12 LSB）。

⇒ **本卡按"同式 + 各自域"实现，并把差异量化在 §2.4**。若架构师/用户实测后认为"编辑器侧
阴影太弱/高光太宽"，正确的下一步不是改公式，而是（a）把 `ws` 的系数 3.0 / `wh` 的 0.5 起跳
点作为**标定旋钮**分链路标定，或（b）在 U-2 做显示域曲线时一并讨论域口径 —— 我**没有**擅自
改系数（那会让两侧从"公式同式"退化成"两套数学"，正是用户要消除的东西）。

---

## 4. 没改的东西（避免顺手改坏）

- **`kFsFilm` / `kFsOut` 一行未动**（任务纪律）。
- **`kFsStyle` 既有段的数学一行未动**：新增段插在 vignette 与 LUT 之间，不改任何既有段的输入/输出口径。
- **[20..22] 分区饱和的"编辑器只读 [21]"** 与 **[5..10]/[37][38] 的编辑器无落点**：属 U-2/U-5，不在本卡。
- **`EditorScreen.kt` / `PhotoViewScreen.kt` 未碰**（并行卡在改那两个文件）。

---

## 5. 护栏与构建

### 5.1 三个护栏脚本（退出码 0）

```
$ bash tools/validate-shaders.sh                      # 62 个 GLSL 字符串全部 OK，退出 0
$ python3 tools/check-diag-fields.py                  # splitW/splitC/monoOn OK（行 7616-7618，submitDisplay），退出 0
$ python3 tools/check-style-passthrough.py            # 全部通过，退出 0
```

`check-style-passthrough.py` 第 ④ 项的实测输出（**34 个槽位**，精确匹配）：

```
④ passStyle 读到的 FilmParams 槽位 = ['blacks', 'clarity', 'contrast', 'exposure_ev', 'fade',
   'grain_amount', 'grain_midtone_bias', 'grain_pivot', 'grain_size', 'highlights', 'local_angle',
   'local_center_x', 'local_center_y', 'local_ev', 'local_feather', 'local_radius', 'local_sat',
   'local_type', 'local_width', 'lut_strength', 'monochrome', 'sat_mid', 'shadows', 'split_cool',
   'split_warm', 'temp_mired', 'tint', 'vignette_feather', 'vignette_radius', 'vignette_strength',
   'wb_b', 'wb_g', 'wb_r', 'whites']
```

### 5.2 新增的护栏（U-1 段，⑰a–⑰d）

写进 `tools/check-style-passthrough.py`（不新起脚本：GL 执行器 / 解析 helper / 逐字比对手法
那里都有）。四条判据与 §2 一一对应，全部退出码 0。⑰d 是**永久护栏**：以后谁把
`kFsFilm` 的 split/mono 数学改了而忘了 `kFsStyle`（或反过来），立刻红。

### 5.3 真机之外的判据上限（诚实标注）

- ⑰a–⑰d 是**真跑引擎 shader 源码**（从 `FrameRenderer.cpp` 抠 `R"(...)"` 原文字符串编译）+
  glslang 静态校验 ⇒ 覆盖"GLSL 合法性 / 数学 / 门控 / 与 kFsFilm 同式"；
- **不覆盖**：Mali 驱动上的实际编译与预测执行、真机 8bit 读回的 dither 交互、
  编辑器 UI 侧"套预设 → 下发 FilmParams"的整链（属 P-5 卡与 U-6 的判据）。

### 5.4 构建 + 单测（真跑）

**前置（强制真编）**：删掉 `FrameRenderer.cpp.o`（两个 ABI）与 `jniLibs/**/libfilmengine.so`：

```
=== .o/.so removed ==="
$ ls app/build/filmengine/jniLibs/*/libfilmengine.so
ls: app/build/filmengine/jniLibs/*/libfilmengine.so: No such file or directory      # 确已删除
```

**⚠️ 环境坑（写下来给下一张卡）**：本机默认 `JAVA_HOME` = **JDK 8**，直接跑 gradlew 会
在配置阶段就失败（`Dependency requires at least JVM runtime version 11`）。必须显式：

```
$ export JAVA_HOME=/opt/homebrew/opt/openjdk@23/libexec/openjdk.jdk/Contents/Home
```

**① 真编（`assembleDebug`）**：

```
> Task :app:packageDebug
> Task :app:assembleDebug
BUILD SUCCESSFUL in 9s
40 actionable tasks: 5 executed, 35 up-to-date
=== ASSEMBLE_EXIT=0 ===
$ stat -f "%Sm %N" -t "%H:%M" app/build/filmengine/jniLibs/*/libfilmengine.so
15:16 app/build/filmengine/jniLibs/arm64-v8a/libfilmengine.so     # .so 重新生成（非旧产物）
15:16 app/build/filmengine/jniLibs/x86_64/libfilmengine.so
```

**② 单测（`--rerun-tasks` + 先删 `test-results`，防 `FROM-CACHE` 假绿）**：

```
$ rm -rf app/build/test-results
$ ./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 9s
27 actionable tasks: 27 executed
=== TEST_EXIT=0 ===
TOTAL_TESTS=436 FAILURES=0 SKIPPED=0 XML_FILES=40
```

- **用例数 436 / 0 失败 / 0 跳过**（基线 418）。增量 18 **不全是本卡的**：本卡 `PresetScopeTest`
  净增 2（+2 新用例、替换 1 个旧断言）、其余是工作区里并行卡新增的测试类（`PresetDetailTest` 等）。
- **定向真跑**本卡碰过的 4 个护栏类（`--rerun-tasks --tests …` ×4）：
  `TARGETED_EXIT=0`，`TARGETED_TESTS=37 FAILURES=0`（`PresetScopeTest` /
  `EditorStyleWhitelistTest` / `PresetDetailTest` / `BuiltinPresetsTest`）。
- ⚠️ **首跑（15:16）有 2 个红**，都记录了处理：
  1. `PresetDetailTest.detailLabelsMatchScopeNoticeWording` —— **本卡的必然后果**
     （循环含 `[23..25]` 且断言"应被标为不生效"），已按新口径改（见 §2.5 ⑦）；
  2. `CameraPresetApplyTest.userEvAddsOnTopOfPresetOffset` —— 并行卡 **U-3** 的在途 TDD 红
     （`effectiveExposureEv(0.7, -0.3, 1.0)` 期望叠加 1.4、当时实现是覆盖 0.4），
     与本卡改的槽位 / 作用域**无关**；几分钟后 U-3 的实现落地，最终全量 0 失败。

> **并发纪律**：两次构建都由脚本先轮询"无 `GradleWrapperMain` 客户端"（连续 20–30 s 空闲）
> 才启动，避免与并行卡互相破坏 build 目录（任务书点名的实测事故）。

### 5.5 护栏脚本原始输出

```
$ bash tools/validate-shaders.sh
提取 62 个 GLSL 字符串
OK   shader_0.vert … OK shader_61.frag
全部 shader 校验通过                                        # 退出 0

$ python3 tools/check-diag-fields.py
OK   splitW 行 7616 函数 submitDisplay（定义 7387），前置调用齐备且更早
OK   splitC 行 7617 函数 submitDisplay（定义 7387），前置调用齐备且更早
OK   monoOn 行 7618 函数 submitDisplay（定义 7387），前置调用齐备且更早
全部通过                                                    # 退出 0（其余字段同样 OK）

$ python3 tools/check-style-passthrough.py
（①–⑯c 全部通过，④ = 34 个槽位，⑰a–⑰d 见 §2）
全部通过                                                    # 退出 0
```

---

## 6. 需要架构师裁决 / 已知风险

### 6.1 编辑器参数面板**没有** `[23][24][25]` 滑杆（本卡的生效路径只有"套预设"）

`EditorScreen.kt` 的 `EditorParam` 表里**没有**单色 / 分离色调两项（grep 全文只有注释提及），
`EditorEngineSession` 传来的 FilmParams 里这三槽的值来源 = **预设 `slots` 覆盖**
（`applyPreset` → `presetSnapshot` → `applySnapshotParams`）。⇒ 本卡验收的是"**预设套用在编辑器
里真的生效**"（正是用户实测到的那条链路），不是"编辑器里有滑杆可调"（那是 §9.5 的 **U-6**）。
**我没有加滑杆**（规格把"参数面板统一"放在 U-6，且 `EditorScreen.kt` 正被并行卡修改）。

### 6.2 "套用原片风格"会**二次叠加**一次分离色调（对已烘焙的成片）

`applySnapshotStyle()`（E2-21 起）会把成片快照里的参数**整槽写回**再渲染。对一张拍摄时用了
`[23][24]` 的成片，"套用原片风格"会在**已烘焙的偏移上再加一次**（mono 无碍：已经灰了，
再去色是幂等的）。这是**既有语义**（E2-21 明确选了"整槽写回"），不是 U-1 引入的；
但 U-1 之前 `[23][24]` 在编辑器侧不生效 ⇒ 现在这条路径**第一次**有了可见效果。
**建议**：① 接受（用户显式点了"套用原片风格"）；或 ② 后续卡把"已烘焙的 `[23][24][25]`"
加进"不写回"清单（与 `[0]/[4]` 等的处理口径统一）。**本卡不动**（改的是 `EditorScreen.kt`，
被并行卡占用）。

### 6.3 两处并行文件里的注释现在过时（**禁改文件，交回架构师**）

- `app/src/main/java/com/filmkit/app/ui/EditorScreen.kt:3590`：
  "含 `[23][24]` 分离色调 / `[25]` 单色 / `[5..10]` 趾肩 的预设在本页套用**没有视觉效果**"
  ⇒ U-1 起前两项**有**视觉效果，只剩 `[5..10]`（等 U-2）。
- `app/src/main/java/com/filmkit/app/ui/PhotoViewScreen.kt:870`：同型表述（查看页「胶片」走同一条
  `passStyle` 链路）⇒ 同上。

（任务纪律明令本卡不碰这两个文件；改法就是各删掉 `[23][24]`/`[25]` 两个例子。）

---

## 7. 未真机验证（明确声明）

**未真机验证**：本机没有连接的 Android 设备（无 `adb devices` 输出），也没有可用的模拟器组件
⇒ 以下三件事**没有**在真机上确认，只做到离线真跑 shader + 静态校验：
1. 设备端 GLSL 编译（Mali 的"数组作函数参数 / 循环内动态索引 / 多行宏"三禁本段都没用到，
   且 glslang 通过 —— 但 Mali 的实际编译只能在设备上确认）；
2. 编辑器 UI 里"套一条黑白预设 → 画面变黑白"的**端到端**观感（需要真机 + 一条含 `[25]` 的预设；
   内置三条**都不含** `[25]`，见 `BuiltinPresetsTest.bwPresetHasNoMonochrome`）；
3. 分离色调在真机 8bit 输出上的观感（离线数字见 §2.3）。

---

## 8. 工作区状态（未 commit）

```
$ git status --short          # 本卡改动与并行卡改动混在同一工作区（未 commit）
 M core/engine/src/render/FrameRenderer.cpp        ← 本卡
 M core/engine/src/render/FrameRenderer.h          ← 本卡
 M tools/check-style-passthrough.py                ← 本卡
 M tools/check-diag-fields.py                      ← 本卡
 M app/src/main/java/com/filmkit/app/camera/EditorEngineSession.kt   ← 本卡（仅 KDoc）
 M docs/reports/preset-DESIGN.md                   ← 本卡（§1.6 两行 + §9.2/§9.5 进度）
?? app/src/main/java/com/filmkit/app/camera/PresetScope.kt           ← 本卡改（P-5 新建，未 add）
?? app/src/test/java/com/filmkit/app/camera/PresetScopeTest.kt       ← 本卡改（P-5 新建，未 add）
?? app/src/main/java/com/filmkit/app/ui/PresetDetail.kt              ← 本卡改 1 行注释（U-6a 新建）
?? app/src/test/java/com/filmkit/app/ui/PresetDetailTest.kt          ← 本卡改（U-6a 新建）
?? docs/reports/u1-editor-mono-split-REPORT.md     ← 本报告
（其余 M 条目 = 并行卡：CameraController.kt / CameraPresetApply.kt / CameraScreen.kt /
  EditorScreen.kt / PhotoViewScreen.kt / EditorExport.kt / 若干测试 —— 本卡一律没碰）
```

**没有 commit**（等架构师独立复核）。复核入口建议按这个顺序读：
`FrameRenderer.cpp` 的 `kFsStyle`（4981 uniform 区 / 5269 主段）→ `passStyle`（12867 下发）
→ `submitDisplay`（7613 诊断）→ `tools/check-style-passthrough.py` 的 ⑰ 段（判据）→ 本报告 §2/§3。
