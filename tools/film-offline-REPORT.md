# FILM 段离线链交付报告（引擎插槽 · 阶段 1 前置）

> 卡：把 `tools/engine-shader-offline.py` 补成"能跑到 `kFsFilm` 输出"的完整离线链（+ PC3D）。
> 上位方案：`docs/engine-slots-design.md` §8 阶段 1；基线：`docs/reports/stage0-engine-slot-BASELINE.md`。
> 本报告只描述**已经真跑过**的东西；未跑的一律写"未验证"。

---

## 1. 改动/新增文件清单

| 文件 | 状态 | 行数 | 说明 |
|---|---|---|---|
| `tools/engine-shader-offline.py` | 修改（**纯增量**） | 295 → 678（+383） | 尾部追加 FILM 段 + PC3D 段 + 独立函数；`git diff --stat` = `383 insertions(+), 0 deletions(-)` ⇒ **原 295 行一行未动** |
| `tools/filmic-spline-ref.py` | 新增 | 283 | `FrameRenderer::computeFilmicSpline`（含 `solve5`）的 Python 逐行对照实现 + 自检 |
| `tools/check-film-offline.py` | 新增 | 149 | 独立校验：solve5 对拍 numpy、spline 采样表、**CPU 复现 vs GPU 实跑** |

`core/`、`app/`、任何 shader 源、`docs/engine-slots-design.md`：**零改动**（§7 有 `git status` 原文）。

---

## 2. FILM 段：产物与摘要

**产物**：`<out>/film_d5.npy`（H×W×4，float32，**OETF 前的线性显示域**，与真机 `tex_work_` 同域）。

复现命令（默认口径 = 与阶段 1 判据对齐：FILM 吃 CCM 的 D4）：

```bash
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/stage0-film
```

| 产物 | CRC32 | SHA-256 |
|---|---|---|
| `film_d5.npy`（`--film-input ccm`，默认） | `0x589f8364` | `ce1c9a90c11dbb69278593cd20854970c7a99fc2bf0ee9da90fc10e3136e5391` |
| `film_d5.npy`（`--pc3d 1 --film-input pc3d`） | `0xd50a1a0d` | `2153b441882790754ba2edd6cc7843a71e827e083d9529812fceb91ee5b579ff` |
| `pc3d.npy` | `0x6742aacd` | `01c9d163ef59055262fade87d26de13cf54e531489b6f90f12e6962c6643b3c3` |

口径：`np.ascontiguousarray(a, dtype=np.float32).tobytes()`（与 BASELINE §3 完全同一口径）。
**可复现性**：同一命令独立跑两次（不同 `--out`），`p0/rcd_g/rcd_d3/ccm_d4/pc3d/film_d5` 六个产物字节**全部一致**（§6）。

### 2.1 喂给 `kFsFilm` 的 uniform（原文见 `steps.json` → `steps.film.uniforms`）

CPU 侧 filmic 系数（`computeFilmicSpline(-7.65, 4.56, 1.0, [0.01]×3, [0.01]×3)`）：

```
power=3.614815712  contrast=1.433801055  black_disp=0.087813407  grey_log=0.626535654
input_min=0.0009185798  input_max=4.3520427  dr=12.21  black_ev=-7.65  white_ev=4.56
lat_min=0.6228419542  lat_max=0.6290767193
toe m1..m5 =  0.087813407, 0.0,          1.3442118168,  0.8184227347, -1.2345184088
sh  m1..m5 = -0.0838498026, 1.1398952007, -1.4478890896, 3.8114919662, -2.4196481705
lin m1,m2  = -0.2717918456, 1.4338010550
```

（全精度原文见 `steps.json` → `steps.film.uniforms` 的 `uFilmicToeM*`/`uFilmicShM*`/`uFilmicLinM*`。）

**默认值回源码核实的结果（任务卡里的两个数字是错的）**：

| 参数 | 任务卡写的默认 | **源码实际默认** | 出处 |
|---|---|---|---|
| `--film-black-ev` | −8.0 | **−7.65** | `FilmParams.h:212` `filmic_black_ev = -7.65f; // 37` |
| `--film-white-ev` | 4.0 | **+4.56** | `FilmParams.h:213` `filmic_white_ev = 4.56f; // 38` |
| `--film-contrast` | 1.0 | 1.0 ✓ | `FilmParams.h:54` `contrast = 1.0f; // 4`（真机 = `cp × [4]`，`auto_tone=0` ⇒ `cp≡1.0`） |
| `--film-lat-sh/toe` | 0.01 | 0.01 ✓ | `FilmParams.h:84-85`（`0.01` 是 darktable 默认；**不是 0**，0 = 极端 S 曲线） |
| `--film-look-sat/chroma` | — | 1.0 | `FilmParams.h:123-124`；`SettingsScreen.kt:575` 注释确认真机运行时也是 1.0 |

其余 uniform 逐个说明（全表在 `steps.json`）：

| uniform | 离线取值 | 来源 / 理由 |
|---|---|---|
| `uTex` | `ccm_d4`（或 `pc3d.npy`） | 真机 = `tex_scene_`（P5/HR 输出），同域 |
| `uExposure` | `2^[0]` = 1.0 | `passFilm`：`std::exp2(p.exposure_ev)` |
| `uGamut` | 1.0 | `FilmParams[40]` 默认 1.0（darktable v5 gamut 五步**在链内**） |
| `uHalDomainMap` / `uDomainRatio` | 0 / 2.0 | RAW 静拍链 = `hal_domain=false`（`FrameRenderer.cpp:10619`）；`uDomainRatio` 在该分支不可达，给在线 EMA 初值 |
| `uSat` | (1,1,1) | `FilmParams[20..22]` 默认全 1.0 ⇒ `sat≡1` ⇒ 恒等 |
| `uSplit` / `uMono` | (0,0) / 0 | `FilmParams[23..25]` 默认 0 ⇒ 恒等 |
| `uToneK` | 1.0 | `FilmParams[28]` 默认 1.0 |
| `uLook` / `uLookSat` / `uLookChroma` | 1.0 / 1.0 / 1.0 | `FilmParams[31..33]` 默认（后两者 1.0 ⇒ `1+(c−1)·sk ≡ 1` 恒等） |
| `uLocalStrength` | **0（旁路）** | 真机默认 `[36]=0.25`，但依赖真机统计 ⇒ 见 §4 |
| `uLocalKnee/KneeHi/Ref/Size` | 0.6 / 1.3 / 0.5 / (1,1) | `FrameRenderer.cpp:270` 的 `kLocalKnee/kLocalKneeHi` + `passFilm` 的 `local_ref` 兜底 |
| `uLocal`（sampler） | 1×1 全 1 纹理 | 即使 `uLocalStrength=0` 短路，sampler **也必须有合法绑定**（v0.6.0-m4 全黑事故成因） |

### 2.2 对 shader 源码的机械改写（逐字记录 —— 判据可审计性的关键）

**只有一处**，且是既有 `to_core()` 已经做的那一处：

```
'#version 300 es'  →  '#version 330 core'
```

- `kFsFilm` **没有**触发 G2（`c == 1 ? m.g : m.b`）修正（该模式只存在于 RCD 段）——`steps.json`
  的 `film.shader_rewrites` 里可核对只有一行。
- 除这一处外，**`kFsFilm` 在 GL 330 core 下零改写直接编译通过**（`precision highp float;`
  在桌面 GLSL 被接受；`texelFetch`/`texture`/`ivec2` 全部原生可用）。
- 上面 §2.1 的 uniform 名与 `passFilm` 的 `glGetUniformLocation` 一一对应（逐字同名）。

---

## 3. 与 C++ 的逐行对照（关键段）+ 手算一致性

实现文件：`tools/filmic-spline-ref.py`（`compute_filmic_spline` / `solve5`）。
**替代方式说明**：本机是 macOS、无 Android GPU 运行时，**无法编译运行 C++ 版**
（`FrameRenderer` 需要 GL 上下文 + Android 依赖）。因此用"逐行对照 + 独立算法对拍 + 约束反算"代替编译对拍。

### 3.1 关键行对照

| # | C++ 原文（`FrameRenderer.cpp`） | Python（`filmic-spline-ref.py`） |
|---|---|---|
| ① 端点求解 | `power = std::log(kGrey) / std::log(-black / dr)`<br>`black_disp = std::pow(kBlackTargetPct/100.0, 1.0/power)`<br>`grey_disp = std::pow(kGrey, 1.0/power)`<br>`grey_log = std::abs(black)/dr` | `power = math.log(K_GREY)/math.log(-black/dr)`<br>`black_disp = (K_BLACK_TARGET_PCT/100.0)**(1.0/power)`<br>`grey_disp = K_GREY**(1.0/power)`<br>`grey_log = abs(black)/dr` |
| ② `solve5` | `12997`：列主元（`std::abs(a[r][col]) > std::abs(a[piv][col])`）→ 消元（`for c=col..4`）→ 回代（`for r=4..0`）；奇异判据 `< 1e-12` | `solve5()`：同一循环边界、同一主元规则、同一回代顺序、同一 `1e-12` 判据（逐行同构） |
| ③ latitude→xmin/xmax | `xmin = (black_disp + kSafety*(white_disp-black_disp) - li)/contrast`<br>`xmax = (white_disp - kSafety*(white_disp-black_disp) - li)/contrast`<br>`toe_log = (1-kLatToe)*grey_log + kLatToe*xmin`<br>`sh_log = (1-kLatSh)*grey_log + kLatSh*xmax` | 同四式逐字相同（含 `li = grey_disp - contrast*grey_log`、`kSafety = 0.01`） |
| ④ output_power 推导 | `sp.output_power = static_cast<float>(power)`（`power` 由 ① 派生；C++ 注释：**不是自由参数**，`_compute_output_power` 同式） | `sp['output_power'] = f32(power)`（同式派生，同 float32 截断） |
| ⑤ per-channel 防呆 clamp | `raw_toe = std::isfinite(lat_toe[ch]) ? lat_toe[ch] : 0.01f;`<br>`kLatToe = (double)std::clamp(raw_toe, 0.0f, 1.0f);` | `raw_toe = lat_toe[ch] if math.isfinite(...) else f32(0.01)`<br>`k_lat_toe = min(max(f32(raw_toe), f32(0.0)), f32(1.0))`（同顺序：先 isfinite 再 clamp，clamp 在 float32 域） |
| ⑥ 5×5 系统 | toe：`{0,0,0,0,1}/{0,0,0,1,0}/{t⁴,t³,t²,t,1}/{4t³,3t²,2t,1,0}/{12t²,6t,2,0,0}`，`b={black_disp,0,toe_d,contrast,0}`<br>shoulder：首两行换 `{1,1,1,1,1}`/`{4,3,2,1,0}`，`b={white_disp,0,sh_d,contrast,0}` | 两段矩阵与右端项逐元素相同；解按 `x⁴..x⁰ → m5..m1` 同一映射写回 |

### 3.2 手算/独立验证数值（`python3 tools/check-film-offline.py --out /tmp/film-nogamut`）

1. **`solve5` vs `numpy.linalg.solve`（不同算法路径，3 组参数 × 2 段 × 3 通道）**：
   最大系数差 = **4.832e-12**。
2. **五条约束反算残差**（`f(0)=black_disp / f'(0)=0 / f(T)=toe_d / f'(T)=contrast / f''(T)=0`，
   shoulder 同理换 `f(1)=1`）：
   - double 系数（`solve5` 原始解）：上界 **4.55e-13** ⇒ 求解正确；
   - float32 存储后（C++ 也 `static_cast<float>` 存系数）：上界 **1.98e-05**（= 系数幅度 × 2⁻²⁴ × 导数放大，**位级一致的必然量级**，非实现偏差）。
3. **不动点**（darktable 语义的独立验证：中灰必须映射回中灰）：
   `spline(grey_log=0.626535654) = 0.626535636 → pow(·, 3.614815712) = 0.184500016`（目标 0.1845）✓
4. **外部交叉验证**：默认参数下 `power = 3.614815712`，与 C++ 注释里独立记录的
   "PC 固化 **3.6148** 就是该式在它那组参数下的值"吻合到 4 位。
5. **CPU 复现 vs GPU 实跑**（`--film-gamut 0` 时光路 = `mix(lin·2^ev, filmicTone(lin·2^ev), uToneK)`，
   其余段恒等）：最大绝对差 **2.441e-04**、最大相对差 **9.694e-04**、P99 = 2.313e-04
   —— 与 RGBA16F 输出量化步长同量级 ⇒ **GPU 确实按我喂的 15×2 系数执行**。
6. **A/B 恒等自证**：`--film-tone-k 0 --film-look 0` 时 `film_d5.npy` 与 `ccm_d4.npy`
   **逐位相同**（两者 CRC32 都是 `0x189e4126`，最大绝对差 0.0）⇒ ① shader 真在执行；
   ② uniform 真在生效（对比默认 `uToneK=1` 输出不同）；③ 恒等默认值确实恒等。

### 3.3 spline 采样点数值表（手算对照用）

`black=-7.65 / white=4.56 / contrast=1.0`（默认），G 通道：

| e | spline(e) | pow(spline, power) | 对应输入线性值 |
|---|---|---|---|
| 0.0 | 0.087813407 | 0.000151763 | 9.185798e-04 |
| 0.2 | 0.146154032 | 0.000957073 | 4.991512e-03 |
| 0.4 | 0.323662682 | 0.016946310 | 2.712360e-02 |
| 0.626536 | 0.626535636 | **0.184500016** | 1.845000e-01 |
| 0.8 | 0.861813337 | 0.584158444 | 8.008992e-01 |
| 1.0 | 1.000000104 | 1.000000377 | 4.352043e+00 |

---

## 4. 离线链**不在内**的部分（逐条：哪一段、为什么、对判据的影响）

| # | 不在内 | 为什么 | 对阶段 1 判据的影响 |
|---|---|---|---|
| 1 | **HR 高光重建**（`[45] hr_raw` 真机默认 **1.0**，仅成片路径） | 需要块级参考场 + `white_ev` 标定；`passFilm` 内用它**替换 filmic 的输入** | FILM 的**输入**与真机不同（离线 = CCM D4）。kFsFilm 重构的**等价性**仍可判（同输入下逐字节等价），但"同帧成片 CRC32"仍须真机 |
| 2 | **`uLocal*` 局部色调映射**（`[36] local_tone` 真机默认 **0.25**） | 依赖真机渲染的 32×24（随分辨率 `clamp(w/128,4,64)`）低频亮度图 + 读回 8bit 中位 | 离线取**旁路值 `uLocalStrength=0`**（shader 内整段短路）。⇒ 判据只覆盖 `uLocalStrength=0` 这一档；`>0` 档**未验证** |
| 3 | **PC3D 的噪声标定**（`[54]` 真机默认 1.0） | 已接入（§6），但 `sigA/sigB` = 命令行参数（**不是真机 `NoiseCoef p4a/p4b` 标定值**），且 **LSC = 恒等** | PC3D 输出与真机有偏差；但它位于 kFsFilm **之前**，不影响 kFsFilm 自身的等价性判据 |
| 4 | defringe（`[47]`）/ 多尺度色度降噪（`[52]`）/ 色度平滑（`tex_cs_`） | 需要门控统计与多尺度缓冲；真机 PC3D 的输入是 `tex_cs_` | 同上：影响 FILM 的输入真实性，不影响 kFsFilm 内部等价性 |
| 5 | **`kFsOut`**（OETF/颗粒/dither/LUT/锐化/暗角/柔光/halation） | 本脚本不在内（原有缺口） | 离线产物是 **OETF 前**线性显示域；"同帧成片 CRC32"必须在真机 |
| 6 | MFNR 融合 / P5 / 3D LUT 资产 | 需要多帧与资产 | 同上 |
| 7 | `uDomainRatio` 在线 EMA（仅 `--film-hal-domain 1`） | 需要预览/静拍两路中位统计 | RAW 静拍链不可达（`hal_domain=false`）；预览链**未验证** |
| 8 | auto 黑/白点（`[35] auto_tone` 真机默认 **0**） | 需要场景分位统计 | 默认关 ⇒ 曲线固定，与离线**一致**；`auto_tone=1` 档**未验证** |

> 教训对齐（`lessons.md` 2026-10-06 "离线好看/真机判死"）：以上 8 条里 **1/2/3/4** 是"离线好看"的主要来源
> ——离线链**不能**用来判断成片观感，只能用来判断"同输入下 kFsFilm 的逐字节等价性"。

---

## 5. P0/RCD/CCM 回归结果

复现命令同 §2。四个产物与 `docs/reports/stage0-engine-slot-BASELINE.md` §3 逐项对照：

| 产物 | 基线 CRC32 | 实测 CRC32 | 基线 SHA-256（前 16） | 实测 SHA-256（前 16） | 结论 |
|---|---|---|---|---|---|
| `p0.npy` | `0x0fb730bb` | `0x0fb730bb` | `b8a5b348f02e2121` | `b8a5b348f02e2121` | **一致** |
| `rcd_g.npy` | `0x6f85ff96` | `0x6f85ff96` | `a8338b467478f508` | `a8338b467478f508` | **一致** |
| `rcd_d3.npy` | `0x594e2a05` | `0x594e2a05` | `e4dd5c7265962f5e` | `e4dd5c7265962f5e` | **一致** |
| `ccm_d4.npy` | `0x189e4126` | `0x189e4126` | `bf7388d97217aa19` | `bf7388d97217aa19` | **一致** |

四个摘要**逐项一致** ⇒ 既有段零回归。机械依据：`git diff --stat` = **383 insertions(+), 0 deletions(-)**
（原 295 行一行未动；新增的 CLI 参数是**插入**行，不改变 P0/RCD/CCM 的任何逻辑或 uniform）。

**新增段可复现性**：带 `--pc3d 1 --film-input pc3d` 的完整命令独立跑两次（`/tmp/stage0-film-full`
与 `-r2`），六个产物字节全部一致：`p0 0x0fb730bb` / `rcd_g 0x6f85ff96` / `rcd_d3 0x594e2a05` /
`ccm_d4 0x189e4126` / `pc3d 0x6742aacd` / `film_d5 0xd50a1a0d`。
浮点确定性说明：所有 pass 都是**单线程、逐像素、无原子操作**的 GL 执行；唯一非确定性来源是
GPU 驱动内部调度（同一驱动同一输入下实测稳定，两次运行字节一致）。**跨机器/跨驱动不保证逐位一致**
（float16 纹理与 `pow/exp` 的实现细节可能不同）—— 判据应在**同一台机器**上采。

---

## 6. PC3D：**已完成**（含明确缺口）

- `--pc3d` 之前是**死参数**（只出现在 `argparse` 里，代码零引用 —— 已核实），现已真正接入：
  `kFsPc3dDown → kFsPc3d(ms1, moire=0) → kFsPc3dUp(guided) → kFsPc3d(msize, moire=1.5)`，
  完全照搬 `passPc3d`（`FrameRenderer.cpp:9221`）的尺度/核尺寸公式（`ESD3D2.java:89-94/126-129` 同式）。
- 产物 `pc3d.npy`（D4 域，与输入同域）；`--film-input pc3d` 可把它接到 FILM 输入（更接近真机链）。
- 实测（默认 `sigA=1e-5/sigB=1e-6`）：`kernelSize=2.2667`、`msize=9`、`scaleF=0.6266 → scale=1`
  ⇒ 走全分辨率两趟（不含 down/up 分支）。luma P50 `0.21541 → 0.21513`，最大像素差 0.0411，
  RMSE 4.8e-3 ⇒ 确实在降噪且未破坏图像。
- **缺口（如实标注，已写进 `steps.json`）**：
  1. **LSC = 恒等**（1×1 全 1，与 RCD 段的 `tex_lsc` 同做法）⇒ 边缘/暗角的噪声权重与真机**有偏差**；
  2. `sigA/sigB` 是命令行参数，**不是真机噪声标定值**（真机 = `NoiseCoef p4a/p4b`）；
  3. 输入是 `ccm_d4`，跳过了真机的 P5 色度降噪 / `tex_cs_`；
  4. `scale>1`（强噪声）分支**未实测**（默认参数下 `scale=1`）—— 若要在强噪声档验证，
     需给一个能推出 `scale>1` 的 `sigA/sigB`（`nSqrt/noiseTarget ≥ 1.5`，例如 `sigA≈1e-2`）。
- 工作量估计：接入本体 ≈ 60 行（已完成）；要把缺口 1/2 补齐需要**真机标定数据**（LSC 增益图、
  噪声模型系数），不是纯离线能补的。

---

## 7. `git status --short` 原文（自证没碰产品代码）

```
 M tools/engine-shader-offline.py
?? tools/check-film-offline.py
?? tools/filmic-spline-ref.py
```

`git status --short -- core app docs` ⇒ **0 行**（`core/`、`app/`、`docs/` 全部干净）。
`git diff --stat` ⇒ `tools/engine-shader-offline.py | 383 +++… | 1 file changed, 383 insertions(+)`。

---

## 8. 一句话结论

阶段 1 现在有了可执行的离线零回归判据：**`film_d5.npy` 的 CRC32/SHA-256**
（默认口径 `0x589f8364` / `ce1c9a90…`），在 P0/RCD/CCM 四个基线摘要一字不变的前提下取得，
且 shader 源码零改写（除 `#version` 那一处既有机械改写）、`kFsFilm` 系数与 C++
`computeFilmicSpline` 逐行同构（约束残差 double 4.6e-13、CPU↔GPU 2.4e-04）。
**未验证**：`uLocalStrength>0`、`auto_tone=1`、`hal_domain=1`、PC3D `scale>1`、
真机噪声标定与 LSC —— 这些都必须上真机。
