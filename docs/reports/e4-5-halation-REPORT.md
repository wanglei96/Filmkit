# E4-5 光晕（halation）实现报告

> 实现子代理，2026-10-11。规格 = `docs/reports/editor-E4-film-params-DESIGN.md` **§3.2**（唯一权威）
> + §2.3 槽位表（`[62..65]`）。参考实现 = `jie60517/emulsion`
> `src/render/effects/library/halation.ts`（一手，逐行对照）。
> 代码基线：`core/engine/src/render/FrameRenderer.cpp`（13734 行 → 13986 行）。
> **未 commit**（按任务卡要求留工作区）。

---

## 0. 结论摘要

1. **两条链路都实现了**（用户 2026-10-11 指令"不统一的要统一掉"）：拍照 = `passOutput` 内、
   OETF 之前；编辑器 = `passStyle` 之前。两侧**共用同一个 `runHalation`**，差异只有
   "RGBA16F 量化 ↔ OETF"的先后 ⇒ 实测输出差 **max 0.249/255、仅 72/589824 个分量非零**
   （8bit 输出位深内逐位相同，差异逐条列在 §2.4）。
2. **`[62] = 0` 逐位零回归 + 零 pass 零纹理**：静态门控在纹理分配之前；halo 关闭时
   `passOutput` / `passStyle` 连一次额外 GL 调用都不发（`glGetIntegerv` 也在分支内）。
3. **16 个 pass、5 级金字塔、3 个 fragment shader**（Thr 与 4 次 Down 共用一份 ——
   数学逐字相同，只有 `uThrOn` / `uDecodeIn` 两个门控不同）。移动 GPU 三禁**逐条规避**（§1.3）。
4. **点光源呈"白 → 橙 → 红"**：逐通道扩散半径 r̄_R = 0.0428 > r̄_G = 0.0260 > r̄_B = 0.0195
   （归一化），归一化色度 G/R 沿半径从 0.463 降到 0.003（**降 99.3%**）。
5. **四参数各自单调可控**（实测数字见 §2.3）。
6. ⚠️ **发现一处规格缺陷**：§3.2.5 声称"金字塔基于相对尺寸 ⇒ halo 半径随分辨率等比缩放 ⇒
   预览与成片比例一致"—— **实测不成立**（归一化半径 ∝ 1/短边；预览短边 ≈1080、成片 3072
   ⇒ 预览的 halo 相对半径约为成片的 **2.8×**）。首版**按规格字面实现**（emulsion 原样）。
   ★ **E4-5b（2026-10-11，架构师裁决）：R1 已实施** —— 差距从 2.84× 压到 1.17×，
   **成片逐位零变化**；详见 [`e4-5b-halo-resolution-REPORT.md`](e4-5b-halo-resolution-REPORT.md)。
7. ⚠️ **性能未真机实测**（本机无设备）：给出静态估算（**133.6M texel fetch ≈ 10.7 个
   全分辨率等效 pass**、峰值 **158.7MB** RGBA16F）+ **4 条降级选项** + 真机实测步骤与判据（§3）。
   **不替用户决定降级**。

---

## 1. 实现

### 1.1 落点（规格 §3.2.2 = "本设计最关键的架构决定"）

```
… → kFsFilm（filmic + gamut + sat/split/mono，输出**线性显示域** [0,1]）
   → 【新增】runHalation：kFsHaloThr → kFsHaloThr×4(Down) → kFsHaloBlur×10 → kFsHaloMix
   → kFsOut（highlightRolloff → sharpen → srgbEncode → … → LUT → 颗粒 → dither）
```

**四条理由逐条对上**：

| 规格要求 | 实现 |
|---|---|
| 高光判断必须在 3D LUT 之前 | ✅ 阈值提取在 `kFsHaloThr`，位于 `kFsOut` 的 `srgbEncode` 与 LUT 之前 |
| 不能进 3D LUT | ✅ 空间模糊在 pass 链里做（LUT 是逐像素映射，物理上装不下形态学效果） |
| 线性光里模糊（emulsion：blurring gamma-encoded values spreads the wrong amount of energy） | ✅ 高光提取后的金字塔全程在线性显示域；`uDecodeIn` 只在**入口**做一次 sRGB 解码（编辑器链路） |
| 加光不做过曝压缩（交给 kFsOut 的 `highlightRolloff`，knee 0.80） | ✅ `kFsHaloMix` 只做 `src + added`，**链内零 shoulder**（脚本 S3 判据守住） |

### 1.2 两条链路（用户指令"不统一的要统一掉"）

| | 拍照链路 | 编辑器链路 |
|---|---|---|
| 调用点 | `passOutput`（`FrameRenderer.cpp:13872` 起）**在 OETF 之前** | `passStyle`（`13909` 起）**在 kFsStyle 之前** |
| 输入 | `tex_work_`（kFsFilm 输出，**线性**显示域，RGBA16F） | `edit_tex_`（已成片 JPEG，**显示域 sRGB**，RGBA8） |
| `uDecodeIn` | `false`（已是线性） | `true`（链内 `srgbDecode`） |
| 归一化尺寸 | `w × h`（= tex_work_ 尺寸；预览走 `rw × rh`） | `edit_w_ × edit_h_`（**输入位图**尺寸，**不是** `passStyle` 的 `out_w × out_h`） |
| 输出域 | 线性（`sum`） | 显示域（`srgbEncode(sum)`） |
| 下游 | `kFsOut` 的 `highlightRolloff` → `srgbEncode` → LUT… | `kFsStyle` 的 curve/sat/clarity/… → LUT… |

**"加法"的先后序两侧一致**：拍照链路里 halo 在 `kFsFilm` 的 split/mono **之后**、LUT
**之前**；编辑器链路里 halo 在 `kFsStyle` 的 split/mono **之后**、LUT **之前** ⇒ 同一序。

⚠️ **编辑器链路的尺寸选择是有意的**：`passStyle` 的 `w/h` 是**裁剪后输出尺寸**（E2-12），
而 halo 必须按**原画幅**归一化（规格 §3.2.5："必须按'原画幅'而非'输出画幅'归一化"）——
所以用 `edit_w_/edit_h_`。裁剪只是取景，halo 坐标系不跟着放大。

### 1.3 移动 GPU 三禁的逐条规避（规格 §3.2.6）

| 禁令 | 规避方式 | 验证 |
|---|---|---|
| **数组作为函数参数** | 5 级 halo = **5 个独立 `uniform sampler2D uHalo0..uHalo4`**；权重 = **5 个独立 `uniform vec3 uW0..uW4`** | `tools/check-halation.py` S7 正则扫 3 个 shader 源码：无 `float/vec/sampler2D x[` 声明、无数组参数 |
| **循环内动态索引** | **shader 内零循环、零数组**：5 个 sampler 的采样、5 个权重、5-tap 高斯、4-tap 盒式**全部手动展开** | S7：`\bfor\s*\(` 零命中 |
| **多行宏（`\` 续行）** | **零 `#define`** | S7：`#define` 零命中 |
| 附加：GLSL ES 无数组初始化器 | 无 `float[5](…)` / `vec3[5](…)` 写法；权重在 **CPU 侧**算好逐个 `glUniform3f` | S7：`\[[0-9]+\]\s*\(` 零命中 |

> ⚠️ **C++ 侧**的 `for (int i = 1; i < kHaloLevels; ++i)` 与 `tex_halo_[i-1]` 是**主机代码**，
> 不受 GLSL 三禁约束（三禁只针对设备端 shader）—— pass 编排必须用循环，否则 16 个 draw
> 要手写 16 遍。`glDrawArrays` 在 `runHalation` 内是 **5 个调用点**（2 个在循环内）⇒
> 实际执行 **1 + 4 + 10 + 1 = 16** 次（脚本 S2 判据）。

### 1.4 新增资源与 pass 编排（规格 §3.2.7）

| pass | shader | 输入 | 输出 | 尺寸 | 采样/px |
|---|---|---|---|---|---|
| `kFsHaloThr` ×1 | `kFsHaloThr` | `src`（线性或显示域） | `tex_halo_[0]` | 1/2 | 4（手写 2×2 盒式 —— X300 的 RGBA16F **无线性过滤**，不能像 emulsion 那样靠硬件 LINEAR 降采样） |
| `kFsHaloDown` ×4 | `kFsHaloThr`（`uThrOn=0`/`uDecodeIn=0`） | `tex_halo_[i-1]` | `tex_halo_[i]` | 1/4…1/32 | 4 |
| `kFsHaloBlur` ×10 | `kFsHaloBlur` | 各级 ↔ `tex_halo_tmp_` | 原地往返 | 各级 | 5（权重与偏移逐字照抄 emulsion：`0.227027 / 0.3162162 / 0.0702703`，偏移 `1.3846 / 3.2308`） |
| `kFsHaloMix` ×1 | `kFsHaloMix` | `src` + `tex_halo_[0..4]` | `tex_halo_out_` | 全分辨率 | 6 |

**新增纹理**：`tex_halo_[5]` + `fbo_halo_[5]` + `tex_halo_tmp_` + `tex_halo_out_`（全分辨率
RGBA16F）。**只在 `[62] > 0` 时分配**（`ensureHaloTargets` 是唯一分配点，7 次 `makeTex`）。

**为什么 tmp 只有一张（最大级尺寸）**：规格给的是单数 `tex_halo_tmp_` + 25.1MB。横向 blur
写进 tmp 的**左下角子矩形**（GL 的 FBO 原点在左下角），纵向 blur 用 `uScale` 把归一化 UV
缩到同一子矩形上 ⇒ 数学与"每级一张 tmp"**逐位等价**（省 5 张 = 33MB）。
逐级 tmp 的方案在 `tools/check-halation.py` 的 `run_halo` 里实现（作为等价性对照）。

**参数映射**（规格 §3.2.3，逐条照做）：

| 槽位 | 映射 | 默认 | 实现位置 |
|---|---|---|---|
| `[62]` | `amount = clamp(v, 0, 1)`，乘在 `added` 上；`≤ 0` = 整链旁路 | 0.0 | `runHalation` 的提前 return |
| `[63]` | `thr_lin = srgbToLinear1(v)`（**显示域单位**）；`knee = max(0.02, thr_lin·0.55)` | 0.80 | CPU 侧算好（shader 内不做 `pow`） |
| `[64]` | `centre_c = v·4·SPREAD[c]`；`w_i,c = exp(−(i−centre_c)²/(2·0.95²))` 逐通道归一化 | 0.45 | `haloWeights`（CPU） |
| `[65]` | `tint = (1, 0.24+0.34·v, 0.44−0.22·v)` | 0.40 | `runHalation` |

**硬编码不给槽位的两个物理常数**（照规格）：`CHANNEL_SPREAD = {1.0, 0.6, 0.32}`（红散射最远）、
`SPREAD_SIGMA = 0.95`。脚本 S4/S5/S6 从源码正则提取并与 emulsion 同值断言。

### 1.5 诊断字段（任务要求 4）

| 字段 | 含义 | 出口 |
|---|---|---|
| `haloOn` | **本帧 halo 链真的执行了**（0 且 `[62]>0` ⇒ 静默失败：program 缺失 / 纹理分配失败 / FBO 不完整） | `render.style`（编辑器）+ `raw.render`（成片） |
| `haloMs` | CPU 侧**提交**耗时（与 `passMs`/`rcdAMs` 同口径；GPU 完成时间看 `totalMs`） | 同上 |
| `haloStrength` / `haloThr` / `haloRadius` / `haloWarmth` | 四参数的**实际下发值** | 同上 |

`tools/check-diag-fields.py` 白名单已同步（6 个字段 → `submitDisplay` + 前置 `passStyle(`），
脚本退出码 **0**（`haloOn` 等 6 行 `OK`）。

---

## 2. 验收判据逐条证据

全部证据来自 `python3 tools/check-halation.py`（**真跑** `FrameRenderer.cpp` 里的 GLSL，
moderngl 执行器，只做 `#version 300 es → 330 core` 机械改写）。**退出码 0**（1 项 WARN = §4）。

### 2.1 判据 1：`[62] = 0` 逐位零回归 + 零 pass 零纹理

| 项 | 证据 |
|---|---|
| **逐位零回归（真跑）** | 判据 1a/1b：`[62]=0` 时输出 == 输入，**589,824 个 float 分量逐位相同**（512×384×3；线性域与显示域各一次） |
| **零 pass 零纹理（静态）** | 判据 S1：`if (amount <= 0.0f \|\| src_tex == 0) return src_tex;` 在函数体偏移 **302**，`ensureHaloTargets(w, h)` 在 **439** ⇒ 提前 return **先于**唯一的纹理分配点 |
| **零额外 GL 调用（静态）** | `passOutput` 的 halo 分支内才 `glGetIntegerv` + `runHalation`；关闭时整段不执行 ⇒ 与改动前**调用序列一致** |
| **A8（规格 §3.2.4 要求验证）** | 判据 1c：halo 开启但全图无高光（阈值 0.9、输入峰值显示域 0.2）⇒ `added ≡ 0`，只剩 RGBA16F 往返量化：**max 0.0017/255、mean 0.000000/255**（不可见 ✓） |

### 2.2 判据 2：点光源"白 → 橙 → 红"

**逐通道扩散半径**（`added` 的径向质心，归一化半径）：

| 通道 | r̄ | 物理含义 |
|---|---|---|
| R | **0.04282** | 红光在片基里散射最远 |
| G | 0.02601 | 中 |
| B | 0.01948 | 蓝光最近 |

⇒ **r̄_R > r̄_G > r̄_B**（判据 2a OK）。

**半径-色度曲线**（16 个半径分箱，`added` 归一化到 R = 1）：

| 归一化半径 | R | G | B | G/R | B/R | 视觉 |
|---|---|---|---|---|---|---|
| 0.0414 | 1.000 | 0.463 | 0.473 | 0.463 | 0.473 | 橙黄（核心白来自 src 本身） |
| 0.0969 | 1.000 | 0.162 | 0.070 | 0.162 | 0.070 | 橙 |
| 0.1581 | 1.000 | 0.098 | 0.026 | 0.098 | 0.026 | 橙红 |
| 0.2200 | 1.000 | 0.063 | 0.010 | 0.063 | 0.010 | 红 |
| 0.2823 | 1.000 | 0.003 | 0.000 | 0.003 | 0.000 | 纯红 |

- G/R 与 B/R **单调递减**（各 0 处抖动，判据 2b/2c OK）；
- **G/R 沿半径降 99.3%**（判据 2d OK）—— tint 的常数比只有 0.376，剩下的下降**全部来自
  通道扩散 ⇒ 这正是"不是均匀红雾"的定量证据**。

> ⚠️ 规格 §6.2 已声明："**观感**（是否像真颗粒/真 halation）❌ 只能真机"。本节的数字是
> **形态判据**，不能替代真机目视（§3.3 给真机步骤）。

### 2.3 判据 3/4/5：四参数各自单调可控

| 参数 | 3 档实测 | 判据 |
|---|---|---|
| `[64]` 半径 | r̄_R = **0.01973 < 0.04282 < 0.10216** @ [64] = 0.15 / 0.45 / 0.85 | 单调增 ✓ |
| `[63]` 阈值 | halo 总能量 = **7.9 > 0.3 > 0.0** @ [63] = 0.4 / 0.6 / 0.8 | 单调减 ✓ |
| `[65]` 暖度 | (G+B)/R = **0.7303 < 0.7890 < 0.8479** @ [65] = 0.0 / 0.5 / 1.0 | 单调增 ✓ |
| `[62]` 强度 | 0 ⇒ 逐位零回归（§2.1）；0.6/1.0 ⇒ `added` 线性放大（tint 与半径不变） | ✓ |

### 2.4 判据 4：两条链路逐位相同（或逐条解释差异）

**方法**（U-4 口径，`tools/check-u4-camera-display-domain.py` 同款）：同一个显示域图 D
（8bit 量化），左边喂 `kFsOut(srgbDecode(D))`、右边喂 `kFsStyle(D)`，两条链路都先过
`runHalation`（左 `decode_in=false`、右 `decode_in=true`），其余段全部取默认恒等值。

**结果**：**max |Δ| = 0.2490/255，不同分量 = 72/589824（0.012%）** ⇒ **在输出位深（8bit）内
逐位相同** ✓（判据 7b OK）。前置条件 7a：`max(halo_lin) = 0.2622 ≤ 0.80` ⇒ `highlightRolloff`
逐位恒等（否则两链路不可比）。

**差异逐条列明**（4 条，按影响从大到小）：

| # | 差异来源 | 影响 | 是否可消除 |
|---|---|---|---|
| 1 | **RGBA16F 量化与 OETF 的先后**：拍照链路 `tex_halo_out_` 存的是 `sum`（线性）⇒ `kFsOut` 做 `encode(q(sum))`；编辑器链路存的是 `srgbEncode(sum)` ⇒ `kFsStyle` 直接读 `q(encode(sum))`。`encode` 与 half 量化**不可交换** | **0.249/255**（72 个分量） | 除非把 halo 输出改成 RGBA32F（+100MB）或编辑器链路也存线性（则 `kFsStyle` 要改域约定） |
| 2 | **输入位深**：拍照 `tex_work_` 是 RGBA16F、编辑器 `edit_tex_` 是 RGBA8 | 判据里对 D 做 8bit 量化以对齐 ⇒ 本判据内为 0 | 编辑器输入由 JPEG 决定，不可改 |
| 3 | **`highlightRolloff`**：拍照链路有（knee 0.80），编辑器链路没有（输入已成片）⇒ `sum > 0.8`（线性）的像素上**刻意不同** | 本判据控制在恒等域内 ⇒ 0 | **规格 §3.2.1 ⑦ 的设计**（halo 加光交给 kFsOut 的 rolloff 压缩）。真机上 `[62]` 较大时可见：拍照链路的 halo 过曝区**趋白**、编辑器链路**硬截断**。**这是两侧唯一的设计性差异**，记录待真机 A/B |
| 4 | **kFsStyle 的 curve/sat/split/mono 段在 halo 之前**（拍照链路的同名段在 `kFsFilm` 里、halo 之后） | 默认全恒等 ⇒ 0；非默认值时两侧顺序不同 | 编辑器链路的固有结构（U-1/U-2 已确立），本卡不改 |

### 2.5 判据 5：分辨率无关性 → **规格声称不成立**（见 §4）

- **6a（实现行为）**：`r̄ × 短边` = 16.44 px（512×384）vs 17.24 px（1024×768）⇒ 相对差
  **4.83%**（≤8% 容差，差异来自 1/32 级 texel 数不同带来的离散化）⇒ **像素尺度恒定** ✓
- **6b（规格 §3.2.5 声称）**：归一化 r̄ = 0.04282 vs 0.02245 ⇒ 相对差 **47.6%** ⇒
  **"归一化半径一致"不成立** ⚠️ WARN（修正选项见 §4）

### 2.6 判据 6：三禁 + 三个脚本退出码

| 检查 | 结果 |
|---|---|
| `bash tools/validate-shaders.sh` | **退出码 0**（"全部 shader 校验通过"；含新增的 3 个 halo shader） |
| `python3 tools/check-diag-fields.py` | **退出码 0**（"全部通过"；`haloOn`/`haloMs`/`haloStrength`/`haloThr`/`haloRadius`/`haloWarmth` 6 行 OK） |
| `python3 tools/check-style-passthrough.py` | **退出码 0**（"全部通过"；判据 ④ 白名单加 `halation_strength`） |
| `python3 tools/check-u4-camera-display-domain.py` | **退出码 0**（"全部通过" ⇒ 本卡未破坏 U-4） |
| `python3 tools/check-halation.py` | **退出码 0**（**28 个 OK 判据** + 1 项 WARN = §4） |
| `python3 tools/domain-contract-check.py` | 退出码 **1** —— **既有失败，与本卡无关**：改动前（`git show HEAD:…FrameRenderer.cpp`）与改动后**都是** `unlabeled 3` + 同样的两个域重载嫌疑（`tex_dir_` A2×4 pass / `tex_g_` D2×3 pass）。本卡的 7 张 halo 纹理已全部标 `@domain: D5`（D5 = "filmic 输出，白点 1.0，[0,1]，允许锐化/OETF"），**未新增任何 unlabeled** |
| 三禁 | 脚本 S7 × 3 个 shader 全 OK（§1.3） |

### 2.7 判据 7：性能静态估算（**未真机实测**）→ 见 §3

### 2.8 判据 8：构建 + 单测 + mutation

- **强制真编**：删 `app/build/**/FrameRenderer.cpp.o` 与 `app/build/**/libfilmengine.so`
  （删除后 `find | wc -l` = **0**）⇒ `assembleDebug testDebugUnitTest --rerun-tasks`。
- 构建结果见 §5（最终命令输出）。
- **mutation 自检 3 项**（脚本内直接篡改，全部按预期变红）：
  - **M1**：把 `SPREAD` 改成 `{1,1,1}`（去掉通道扩散）⇒ r̄ = 0.04282 / 0.04254 / 0.04265
    （三通道相同）⇒ 判据 2a **变红** ✓
  - **M2**：同上 ⇒ G/R 只降 **27.1%**（正常 99.3%）⇒ 判据 2d **变红** ✓（"均匀红雾"）
  - **M3**：把暖度映射反向（`G = 0.24 − 0.34·w`）⇒ (G+B)/R = 0.7303 > 0.6724 > 0.6092
    （递减）⇒ 判据 5 **变红** ✓

---

## 3. 性能：静态估算 + 降级选项 + 真机实测（⚠️ **未实测**）

### 3.1 静态估算（全分辨率 4080×3072 = 12.53 Mpx）

| 段 | pass 数 | texel fetch |
|---|---|---|
| 阈值提取（含手写 2×2 盒式降采样） | 1 | 4 × 3.13M = **12.53M** |
| 4 次降采样 | 4 | 4 × 1.04M = **4.16M** |
| 10 次可分离高斯（5 tap × 横纵 × 5 级） | 10 | 10 × 4.17M = **41.74M** |
| 合成（6 采样/px 全分辨率） | 1 | 6 × 12.53M = **75.20M** |
| **合计** | **16** | **≈ 133.6M** |

⇒ **≈ 10.7 个"全分辨率等效 pass"**（与规格 §3.2.8 的 133.6M 逐位一致）。

**带宽下限**：读 `133.6M × 8B = 1.07GB` + 写 `25.05M texel × 8B = 0.20GB` ≈ **1.27GB**。
在共享 LPDDR4X 上（有效带宽按 25GB/s 粗估）⇒ **≥ 50ms 量级**（规格给"≥60ms 量级"，
同一量级）。

**内存峰值**（独立纹理方案，规格 §3.2.7）：

| 项 | 尺寸 | px |
|---|---|---|
| `tex_halo_[0..4]` | 2040×1536 … 127×96 | 4.17M |
| `tex_halo_tmp_`（最大级） | 2040×1536 | 3.13M |
| `tex_halo_out_`（全分辨率） | 4080×3072 | 12.53M |
| **合计** | | **19.84M px × 8B = 158.7MB** |

**预览量级**（假设 SurfaceView 1080×2340 ≈ 2.53 Mpx，`previewScale` 平时 = 1.0）：
texel fetch ≈ **27.0M**（≈ 2.15 个等效 pass @ 1080p）、峰值显存 ≈ **32MB**。

⚠️ **以上全是静态估算，本机无设备 ⇒ 未实测**。驱动开销（16 次 pass 切换 + 7 张 FBO 绑定）
在 Mali 上是**真实成本**，静态估算覆盖不到。

### 3.2 降级选项（**备好但默认不启用**，按铁律交用户拍板）

| # | 方案 | 采样降至 | 显存 | 副作用 |
|---|---|---|---|---|
| **D1** | 金字塔 5 → 4 级（去掉 1/32） | ≈ 88M（−34%） | −8.5MB | 最大半径 52px → 26px ⇒ **大面积泛光消失** |
| **D2** | 合成降到 1/2 分辨率（halo 是低频信号） | ≈ 90M（−33%） | −75MB（`tex_halo_out_` 变 1/4 大） | 全分辨率细节处的 halo 边缘略糊（**需目视判**） |
| **D3** | 预览用 3 级、成片仍 5 级 | 预览 −40% | 预览 −6MB | **预览与成片观感不一致**（WYSIWYG 破绽，**需用户拍板是否接受**） |
| **D4** | `tex_halo_out_` 与 `tex_work_` 乒乓复用（规格 §3.2.7 的"优化"） | 0 | **−100MB**（158.7 → 58.5MB） | "一帧内多域复用"的标签混乱（`6254-6258` 已记录同类问题） |
| **D5** | 仅成片启用（预览整链旁路） | 预览 = 0 | 预览 = 0 | 取景看不到光晕 ⇒ WYSIWYG 破绽（与 D3 同型） |

**本卡不预先套用任何一条**（规格 §3.2.8 的铁律提醒 + 项目 AGENTS.md"性能取舍必须实测"）。

### 3.3 真机实测步骤与判据（**待执行 —— 本报告明确标注"未实测"**）

**前置**：`bash tools/validate-shaders.sh` 过 → `python3 tools/check-diag-fields.py` 过 →
出包 → `aapt dump badging` 版本核对。

| # | 步骤 | 判据（通过） | 失败 ⇒ 立刻回退 |
|---|---|---|---|
| 1 | 编辑器套用含 `[62]=0.6` 的预设，`adb logcat \| grep render.style` | `haloOn=1`；`haloStrength=0.6`、`haloThr=0.80`、`haloRadius=0.45`、`haloWarmth=0.40` 与预设一致 | `haloOn=0` 而 `[62]>0` ⇒ **静默失败**（shader 编译 / 纹理分配 / FBO 不完整），查 `halo.fbo_incomplete` 日志 |
| 2 | 拍照，`grep raw.render` | 同名字段（成片链路）齐备且 `haloOn=1` | 同上 |
| 3 | **成片耗时**：同一场景各拍 5 张，`[62]=0` 与 `[62]=0.6` 两组比 `totalMs`（含 `glFinish`）的 P50 | 差值 = halo 的真实 GPU 成本；**是否可接受由用户拍板** | 若 > 150ms ⇒ 从 §3.2 的 D1/D2/D4 里选（**不自行降级**） |
| 4 | **预览帧率**：`adb shell dumpsys gfxinfo <pkg>` 或引擎 `preview.filmic` 节流日志，对照 `[62]=0` | 帧率降幅可接受（**阈值由用户定**） | 若掉帧明显 ⇒ D3 或 D5（**需用户拍板**） |
| 5 | **内存峰值**：`adb shell dumpsys meminfo <pkg> \| grep -E "TOTAL\|Graphics"`，`[62]=0` vs `=0.6` | 增量 ≈ 158.7MB（成片首次）/ 32MB（预览），无 OOM | 增量 > 200MB 或 OOM ⇒ D4 |
| 6 | **目视（唯一能判"像不像"的一步）**：夜景点光源（路灯 / LED / 车灯） | 光源周围出现**"白 → 橙 → 红"径向渐变**（不是均匀红雾）；`[64]` 增大时晕圈扩大；`[65]` 增大时转橙黄 | 全图泛红（阈值过低）／光晕发灰发紫（tint 或通道扩散错） |
| 7 | **A/B 零回归**：`[62]=0` 出片与改动前逐位对比 | 逐位相同 | 不同 ⇒ 立刻回退（历史多次"注释说关了其实没关"） |
| 8 | **分辨率一致性**（§4 的 WARN）：同一场景，预览取景 vs 成片裁图对比 halo 相对大小 | 若接受"预览 halo 相对更大 2.8×" ⇒ 通过；否则启用 §4 的 R1 | — |

**最佳 A/B 素材**（`preset-DESIGN.md` §4）：CineStill 800T（去 remjet ⇒ 强光晕）vs
Kodak Vision3 500T（同乳剂 + 有 remjet ⇒ 光晕被抑制）；Ektachrome E200（正片有防光晕层
⇒ 光晕**必须为 0**）是物理正确性的检查点。

---

## 4. ⚠️ 规格缺陷：§3.2.5 的"分辨率无关性"实测不成立

**规格原文**："金字塔基于**相对尺寸**（`w >> i`）⇒ halo 半径随输出分辨率**等比缩放**。
预览（`g_preview_scale` 0.25–1.0）走同一相对归一化 ⇒ halo 比例与成片一致 ✓"

**实测**（`tools/check-halation.py` 判据 6）：

| 输出尺寸 | 短边 | 归一化 r̄_R | r̄_R × 短边 |
|---|---|---|---|
| 512×384 | 384 | 0.04282 | 16.44 px |
| 1024×768 | 768 | 0.02245 | 17.24 px |

⇒ **像素半径恒定（≈17px），归一化半径 ∝ 1/短边** ⇒ 规格的"等比缩放"**不成立**。

**根因**：金字塔各级确是相对尺寸（`w >> (i+1)`），但 blur 的 σ = **1.637 texel @ 该级**
⇒ 等效全分辨率 σ_px = `1.637 × 2^(i+1)`，**与图像尺寸无关**（level0 永远是 3.27px、
level4 永远是 52.4px）。所以 halo 的尺度阶梯是**绝对像素**的。
（emulsion 是屏幕空间效果、视口固定 ⇒ 无此问题；我们的预览与成片分辨率不同。）

**实机影响**：预览短边 ≈1080、成片 3072 ⇒ **预览的 halo 相对半径约为成片的 2.8×**
（取景时光晕看着更"大"），是 WYSIWYG 破绽。

**★ E4-5b（2026-10-11）架构师裁决：实施 R1。** 已完成，见
[`e4-5b-halo-resolution-REPORT.md`](e4-5b-halo-resolution-REPORT.md)：
`centre_c = radius01·(LEVELS−1)·SPREAD[c] + log2(min(w,h)/refMin)`，`refMin` = 成片短边
（⇒ 成片 `shift = 0`，**逐位等于修正前**）；预览/成片的差距 **2.84× → 1.17×**（改善 11×），
`check-halation.py` 判据 ⑥ **从 WARN 变 PASS**；GPU 侧零变化（16 pass / 7 纹理 / 133.6M fetch）。
**残留 16.8% 的已知残差**（level0 截断，R1 是近似而非精确解）在 E4-5b §5 记录并给了精确解选项。

**（历史）当时的修正选项（E4-5 阶段，未实施）**：

| # | 方案 | 改动 | 代价 |
|---|---|---|---|
| **R1（推荐）** | 权重峰值按分辨率平移：`centre_c = radius01·(LEVELS−1)·SPREAD[c] − log2(refMin / min(w,h))`（`refMin` = 成片短边） | CPU 侧一行；**零 GPU 成本** | 预览只用低级别（小图时 level4 权重≈0）⇒ 预览的"大面积泛光"略弱，但**半径比例正确** |
| **R2** | 不修，接受"预览 halo 相对更大" | 零 | WYSIWYG 破绽（需用户接受） |
| **R3** | blur 的 σ 按 `min(w,h)/refMin` 缩放 | 一行 | **不可行**：预览下 σ_texel < 1 = 亚像素，而 RGBA16F 在 X300/Mali **无线性过滤**（项目多处手写）⇒ 模糊退化 |
| **R4** | 预览强制跑成片分辨率的 halo | — | 成本爆炸（预览每帧 12.5Mpx）⇒ 不可行 |

> ⚠️ 本卡按项目铁律"**先 100% 按原版完整实现并跑通 → 真机实测 → 用实测数据评估是否偏离**"
> 选择了"规格字面实现 + 报告偏差 + 给选项"。**R1 未实施**。

---

## 5. 构建与测试（真跑证据）

```
命令：rm -rf app/build/test-results
      find app/build -name "FrameRenderer.cpp.o" -delete
      find app/build -name "libfilmengine.so" -delete      # 删除后 find | wc -l = 0
      JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
```

**最终命令输出**（强制真编：`FrameRenderer.cpp.o` 与 `libfilmengine.so` 先删除，残留计数 = 0）：

```
$ rm -rf app/build/test-results
$ find app/build -name "FrameRenderer.cpp.o" -delete
$ find app/build -name "libfilmengine.so" -delete
残留 = 0
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 25s
46 actionable tasks: 46 executed
⇒ 496 个用例，0 个失败
⇒ app/build/outputs/apk/debug/app-debug.apk 产出；libfilmengine.so（arm64-v8a + x86_64）重新生成
```

**用例数**：基线 **455** → 现在 **496**（**0 失败**）。增量 **+41 全部来自并行 UI 卡**新增的
测试（`CameraPresetPanelUiTest` / `PresetPanelUiTest` / `PhotoViewPreviewTest` 等）；
**本卡未新增 Kotlin 单测** —— 本卡的离线验证走 Python 真跑脚本 `tools/check-halation.py`
（28 个判据），与 E4-3/U-4 同口径。

**本卡相关范围（camera 包）单独复跑**（在 UI 卡半成品导致过一次外部阻塞时取的证据）：

```
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew testDebugUnitTest --rerun-tasks \
      --tests "com.filmkit.app.camera.*"
BUILD SUCCESSFUL in 15s
27 actionable tasks: 27 executed
⇒ 293 个用例，0 个失败
```

（含 `CameraStyleWhitelistTest`（引擎拍照链路读取集合）、`EditorStyleWhitelistTest`
（编辑器链路三方一致）、`PresetScopeTest`（§1.6 矩阵 == 作用域表）、`BuiltinPresetsTest` 等。）

**过程记录（诚实标注）**：
1. 首次运行（未同步白名单）：`assembleDebug` ✓、`testDebugUnitTest` **476 tests / 2 failed**
   （`CameraStyleWhitelistTest` / `EditorStyleWhitelistTest`）—— 两者都是"**新增引擎读取必须
   同步三方白名单**"的**设计内护栏**（不是缺陷），本卡按它们的提示同步了
   `cameraPathSlots`、`preset-DESIGN.md` §1.6、`EditorEngineSession.kt` 的 KDoc 清单与
   `check-style-passthrough.py` 的 `want` ⇒ 复跑全绿。
2. 期间撞上一次**外部阻塞**：`EditorScreen.kt:3702 Unresolved reference 'editorPresetActions'`
   （并行 PS-1 卡改到一半的中间态，`compileDebugKotlin` 失败）⇒ 按任务卡纪律**不碰该文件**，
   等该卡补齐后重跑（第二次即通过）。
3. 期间还出现过并行 UI 卡的 `CameraPresetPanelUiTest` 4 项失败（"参数清单 12 项 vs 期望 14 项"
   这类半成品状态；该测试只读 `builtin.json`/`CameraScreen.kt`/`CameraPresetPanel.kt`，
   与本卡改动零交集）⇒ 该卡补齐后**最终运行 496/0**。

**新增/修改的测试与脚本**（都不与其它卡的文件重叠）：
`tools/check-halation.py`（新）、`tools/check-diag-fields.py`（白名单 +6 字段）、
`tools/check-style-passthrough.py`（`want` +1 字段）、
`app/src/test/.../CameraStyleWhitelistTest.kt`（`cameraPathSlots` +4 槽位）、
`app/src/main/.../EditorEngineSession.kt`（KDoc 清单 +1 行）、
`docs/reports/preset-DESIGN.md`（§1.6 矩阵 + 状态行）。

---

## 6. 已知约束与未做项（明确边界）

1. **直方图不含 halo**：`histogram()` / `renderLuma()` 读 `tex_work_`（kFsFilm 输出），
   halo 开时直方图略偏低。`[62]` 默认 0 ⇒ 零影响；记录待后续（若需要，改成读
   `tex_halo_out_`，但那是 kFsFilm 域，需先过 OETF —— 超出本卡范围）。
2. **拍照链路的裁剪归一化**：拍照链路的 `tex_work_` 已是"裁剪后画面"（引擎输入侧裁剪），
   halo 按它归一化。规格 §3.2.5 记录"本版无裁切，记录为已知约束"。**编辑器链路已正确**
   （按 `edit_w_/edit_h_` = 原画幅归一化）。
3. **`uTime` / 种子**：halo 无随机性（纯确定性滤波）⇒ 不需要种子策略。
4. **未做**：规格 §3.2.7 的 D4 乒乓复用、§3.2.8 的 D1–D3 降级、§4 的 R1 修正 ——
   **全部等真机数据 + 用户拍板**（铁律）。
5. **未做**：`[62..65]` 的 UI 滑杆 / 预设 JSON 语义名 / `PresetScope`（本卡只做引擎；
   预设数据层由其它卡负责）。
6. **`added = 0` 像素的 RGBA16F 往返**：实测 max 0.0017/255（§2.1 判据 1c）⇒ 不可见；
   `[62] = 0` 时整链旁路 ⇒ 逐位零回归不受影响。

---

## 7. 文件清单（工作区改动，**未 commit**）

| 文件 | 改动 |
|---|---|
| `core/engine/src/render/FrameRenderer.cpp` | +3 shader（`kFsHaloThr`/`kFsHaloBlur`/`kFsHaloMix`）、+`runHalation`/`ensureHaloTargets`/`releaseHaloTargets`/`haloWeights`/`srgbToLinear1`、`init` 编译+链接+硬失败检查+uniform、`release` 清理、`passOutput`/`passStyle` 接线、两处诊断字段 |
| `core/engine/src/render/FrameRenderer.h` | 成员（纹理/FBO/尺寸/program/uniform 结构体/`halo_on_`/`halo_ms_`）+ 4 个方法声明 |
| `tools/check-halation.py` | **新增**：判据 1–6 + A8 + mutation ×3（真跑 GLSL，退出码 0） |
| `tools/check-diag-fields.py` | 白名单 +6 字段（`haloOn`/`haloMs`/`haloStrength`/`haloThr`/`haloRadius`/`haloWarmth`） |
| `tools/check-style-passthrough.py` | `want` +`halation_strength` |
| `app/src/test/java/com/filmkit/app/camera/CameraStyleWhitelistTest.kt` | `cameraPathSlots` +`62..65` + KDoc |
| `app/src/main/java/com/filmkit/app/camera/EditorEngineSession.kt` | KDoc 槽位清单 +`[62] halation_strength`（44 → 45） |
| `docs/reports/preset-DESIGN.md` | §1.6 矩阵 `[62..65]` 行 → ✅/✅ + 状态行同步 |
| `docs/reports/e4-5-halation-REPORT.md` | 本报告 |

**未改动**（按任务卡纪律）：`EditorExport.kt` 预设数据层、`assets/presets/`、
`CameraScreen.kt`、`CameraController.kt`、`PresetScope.kt`、`EditorScreen.kt`、
`PresetDetail.kt`、`PhotoViewScreen.kt`。
