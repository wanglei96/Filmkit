---
summary: 引擎 1（darktable sigmoid）CPU 侧参数求解的交付报告：逐行照抄 sigmoid.c 的 commit_params（float32 全程 = 官方实跑口径），C++↔Python 对拍 126 项最大相对误差 4.9e-09；锚点 contrast=1.5 得 film_power 1.4909091 / film_fog 0.00138432207 / paper_exposure 0.359695464（float32 组命中 17 号 §1.3），double 组逐位命中 PhotonCamera 硬编码常量（1.5 / 0.00142637086 / 0.354355423）⇒ 公式与口径双向交叉验证；三判据：①可分辨跨度 c=1.2 为 2.00 EV（white=100，白纸 203.601）> 引擎 0 filmic 1.70 EV（对齐 204.0）②中灰 0EV 偏差 0.000 LSB（判据 ≤1.3）③默认 c=1.2 严格单调，但 float32 下 contrast≥2.0 出现非单调段（double 全段严格单调 ⇒ 浮点边界非缺陷，已定位上限）。
created: 2026-10-14
status: draft
---

# 阶段 2 · sigmoid 参数求解（CPU 侧）交付报告

> 一手依据：darktable master `src/iop/sigmoid.c`（2026-10-14 重新 `curl` 逐字核对，**与任务卡副本逐字一致**；行号可 `grep -n` 复核）。
> 契约头 `core/engine/src/film/SigmoidParams.h` **未改动**。原始输出见 [stage2-sigmoid-params-EVIDENCE.txt](stage2-sigmoid-params-EVIDENCE.txt)（129 行，可复跑）。

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 锚点（float32 = 官方实跑） | `film_power` 1.4909091 · `film_fog` 0.00138432207 · `paper_exposure` 0.359695464 ✅ |
| 锚点（double = PC 硬编码） | 1.5 · 0.00142637086 · 0.354355423 —— **逐位命中 PC 常量** ✅ |
| C++ ↔ Python 对拍 | 126 项，最大相对误差 **4.901e-09**（判据 <1e-6）✅ |
| 判据① 可分辨跨度 | c=1.2 → **2.00 EV** > 引擎 0 filmic **1.70 EV** ✅ |
| 判据② 中灰 0EV 偏差 | **0.000 LSB**（判据 ≤1.3）✅ |
| 判据③ 线性域严格单调 | c=1.2 **成立**；float32 下 contrast ≥ **2.0** 出现非单调段（double 全段严格单调）⚠️ 边界已记录 |

## 1. 交付物

| # | 路径 | 行数 | 说明 |
|---|---|---|---|
| 1 | [core/engine/src/film/SigmoidParams.cpp](core/engine/src/film/SigmoidParams.cpp) | 197 | `commit_params` 逐行照抄；**逐行对照表**在文件头（官方行号 → 本文件行号），每行行尾标 `sigmoid.c:NNN` |
| 2 | [tools/sigmoid-params-ref.py](tools/sigmoid-params-ref.py) | 376 | numpy `float32` 逐步复刻 + 曲线求值导出 + `--check/--ab/--probe-powf` |
| 3 | [tools/sigmoid-params-dump.cpp](tools/sigmoid-params-dump.cpp) | 58 | 独立可编译 dump（只 include 契约头） |
| 4 | [tools/sigmoid-criteria-scan.py](tools/sigmoid-criteria-scan.py) | 372 | 三判据扫描（含引擎 0 filmic 对照） |
| 5 | [docs/reports/stage2-sigmoid-params-EVIDENCE.txt](stage2-sigmoid-params-EVIDENCE.txt) | 129 | 全部自检的原始输出 |

## 2. 锚点判据（硬）

`contrast=1.5, skew=0, white=100, black=0.0152, hue=100`：

| 量 | **float32（= darktable 实跑，本实现主口径）** | 参考（17 号 §1.3） | **double（= PC 硬编码口径）** | PC 常量 |
|---|---|---|---|---|
| `film_power` | **1.4909091** | 1.490909 ✅ | 1.5 | `DT_SIGMOID_FILM_POWER = 1.5` ✅ |
| `film_fog` | **0.00138432207** | 0.001384 ✅ | 0.00142637086 | `DT_SIGMOID_FILM_FOG = 0.00142637086` ✅ |
| `paper_exposure` | **0.359695464** | 0.359695 ✅ | 0.354355423 | `DT_SIGMOID_PAPER_EXPOSURE = 0.354355423` ✅ |

- `contrast=1.2` 的 float32 三值：`1.19090915 / 0.000400747434 / 0.592131495`，命中架构师修正表的 `1.190909 / 0.000401 / 0.592131` ✅
- **两条独立证据链**：double 组逐位命中 PC 硬编码常量 ⇒ **公式实现正确**；float32 组命中 17 号 §1.3 独立复算 ⇒ **浮点口径正确**。
- **结论**：PC 用的是 double 预算常量，与 darktable 实跑差 ~0.6%，**不是"PC 更正确"，只是口径不同**；本实现选 float32（= 官方实跑）。
- 判据说明：参考值只给 4~7 位有效数字，其自身舍入误差上限（`0.001384` ⇒ ~3.6e-4）**大于** 1e-5 阈值 ⇒ 判据改为"按参考值有效数字舍入后逐位一致"（`--check` 内实现），否则会把正确实现判成 FAIL。

## 3. C++ ↔ Python 对拍（硬）

- 编译：`g++ -std=c++20 -O2 -Wall -Wextra`（**零 warning**）
- 前 7 组判据组合 + 表头：`diff` **逐字节无差异**
- 全量 18 行 × 7 标量 = **126 项**，最大相对误差 **4.901e-09**，超限 **0** 项（判据 <1e-6）
- 口径前提已验证：`--probe-powf` 证明 numpy `float32 ** float32` 与 libm `powf` **位级一致**（10/10）

## 4. 三判据数值（纯曲线口径，8bit + 真 sRGB OETF）

**判据① 可分辨跨度**（从白纸 2.30 EV 起每 0.1 EV ≥1 LSB 能持续多远）：

| 引擎 / 参数 | 白纸 LSB | 中灰 LSB | **可分辨跨度** | 到 EV |
|---|---|---|---|---|
| 引擎 0 filmic（白点 4.0 原生） | 205.126 | 119.008 | 1.50 EV | 3.80 |
| 引擎 0 filmic（**对齐 204.0**，`white_ev=4.180`） | 204.007 | 119.008 | **1.70 EV** | 4.00 |
| 引擎 1 sigmoid c=1.0 `white=100` | 191.959 | 119.008 | 2.50 EV | 4.80 |
| 引擎 1 sigmoid c=1.0 `white=99.4` | 192.925 | 119.008 | 2.40 EV | 4.70 |
| 引擎 1 sigmoid **c=1.2** `white=100`（官方默认） | 203.601 | 119.008 | **2.00 EV** | 4.30 |
| 引擎 1 sigmoid **c=1.2** `white=99.4`（设计文档对齐档） | 204.567 | 119.008 | **1.90 EV** | 4.20 |
| 引擎 1 sigmoid c=1.5 `white=100` | 218.839 | 119.008 | 1.40 EV | 3.70 |
| 引擎 1 sigmoid c=1.5 `white=99.4` | 219.663 | 119.008 | 1.30 EV | 3.60 |

- 17 号 §1.6 基准（白纸对齐 204，**非本次实测**）：filmic **1.65** / sigmoid c=1.2 **1.95** / ACES2 1.90 ⇒ 本卡复现 1.70 / 1.90~2.00，差 ≤0.05 EV，**一致**。
- **判据① 成立**：c=1.2（1.90~2.00 EV）> 引擎 0（1.70 EV），优势 **0.20~0.30 EV**。
- 官方预设（`sigmoid.c:224-243`，未对齐，仅供参考）：`neutral gray`(1.22/0.65) 223.509 / 1.10 EV；`ACES 100-nit like`(1.6/−0.2) 218.161 / 1.40 EV；`smooth`(1.5/−0.2) 213.762 / 1.60 EV。

**判据② 中灰 0 EV 偏差**：引擎 1 的 c=1.0/1.2/1.5 与引擎 0 均为 **119.008 LSB**，偏差 **0.000 LSB**（判据 ≤1.3）✅

**判据③ 线性域严格单调**（−1~+11 EV，步长 0.02 EV）：

| contrast | 非正差(f32) | 最小相邻差(f32) | 严格单调(f32) | 最小相邻差(double) | 严格单调(double) |
|---|---|---|---|---|---|
| 1.0 | 0 | 3.004e-05 | 是 | 3.002e-05 | 是 |
| **1.2** | **0** | **8.404e-06** | **是** | 7.893e-06 | 是 |
| 1.5 | 0 | 1.073e-06 | 是 | 1.014e-06 | 是 |
| 1.8 | 0 | 1.192e-07 | 是 | 1.262e-07 | 是 |
| 2.0 | 4 | 0.000e+00 | **否** | 3.122e-08 | 是 |
| 2.2 | 35 | −5.960e-08 | **否** | 7.701e-09 | 是 |
| 2.5 | 88 | −5.960e-08 | **否** | 9.429e-10 | 是 |

- **结论**：**判据③ 在默认参数 contrast=1.2 下成立**；高 contrast（**≥2.0**）在 float32 下出现非单调段，**属浮点边界而非缺陷**，已记录上限（边界在 1.8~2.0 之间）。
- 机理解释（可审计）：c=2.2 时 double 口径的最小相邻差仅 **7.7e-09**，而 0.999+ 处 float32 的 ULP ≈ **6e-08** ⇒ **比 ULP 小 8 倍**，float32 下必然量化为 0 或负。darktable 自身也只做 `dt_isnan` 保护、不保证单调性。
- 渐近行为（线性域，渐近值 = `white_target` = 1.0，**不发散**）：c=1.0 → f(+12EV)=0.998921、f(+20EV)=0.999996；c=1.2 → 0.999779 / 1.000000；c=1.5 → 0.999982 / 1.000000。
- 引擎 0 对照：filmic 在 −1~+11 EV 有 **350 个非正差**、最小差 **0.000e+00**，**首次零差 @ 4.02 EV**（白点 4.0 起 `e` 被 clamp 到 1）⇒ 高光端**硬平顶**，与 sigmoid 的无平台段形成对照。

## 5. 口径说明（必读）

1. **float32 全程**：darktable `dt_iop_sigmoid_data_t` 字段（`sigmoid.c:166-167`）与 `commit_params` 局部量全是 `float`、幂运算走 `powf` ⇒ 官方实跑是 float32。差分求解 `(f(x+δ)−f(x−δ))/2/δ`（δ=1e-6）对舍入极敏感（f≈0.18 的 ULP≈1.5e-8，差值仅 1.6e-6）⇒ **`film_power` 在 float32 下是 1.490909 而非 1.5**，这是官方实跑值。**任何一处 double 提升都会破坏逐位一致性**。两引擎口径刻意不对称：引擎 1 一律 float32，引擎 0 一律 double（复刻件求值），不混算。
2. **白纸对齐口径**：float32 下 `display_white_target=99.4` 给 **204.567 LSB**（不是 17 号 double 口径的 204.0）；官方默认 100 给 **203.601**。两档并列报告（架构师要求）。float32 下精确命中 204.0 的解是 `99.525`（c=1.2）/ `215.065`（c=1.0）/ `74.305`（c=1.5）。
3. **白纸对 `display_white_target` 非单调**（重要工程风险）：float32 下 `film_power` 对 `display_white_target` 是**阶梯常数**（差分噪声被量化），而 `white_target` 单调抬升白纸 ⇒ 净效果非单调。**阶段 3 预设标定不能用二分法调白纸**，必须查表。
4. **引擎 0 数值来源**：由本卡扫描脚本调用 `tools/filmic-spline-ref.py`（`computeFilmicSpline` 的逐行复刻件）**现场计算**，**不是真机 `kFsFilm` 实测**（真机含 gamut mapping / highlightRolloff / LUT；本扫描是纯曲线口径）。
5. **引擎 0 的 latitude 口径（本卡定位到的关键点）**：17 号报告未写明 filmic latitude。实测：必须用 `app/src/main/assets/presets/builtin.json` 里 M9 预设的真实值（`shoulder=[0.5278,0.4253,0.5806]` / `toe=[0.9219,0.8216,0.9488]`），**不是 darktable 默认 0.01**。用该值本复刻件**逐位命中** 17 号 §1.2 的两个数：R-2 口径 M9 → **205.126**（17 号 205.13）、R-3 新配方 M9 → **220.450**（17 号 220.45）。误用 lat=0.01 会得 210.52（差 5.4 LSB）⇒ 段判据落到 shoulder 段。此自检已内置为 `engine0_selfcheck()`，失败会显式报警。
6. **引擎 0 与 sigmoid 的口径不对称**（刻意，已标注）：引擎 1 一律 float32（官方实跑），引擎 0 一律 double（复刻件求值）。不混算。

## 6. 未验证项 / 存疑项（诚实标注）

1. **未真机实测**：本卡全部数值是**纯曲线口径**，不含 gamut mapping / primaries inset / highlightRolloff / LUT；真机成片需阶段 3 的 A/B 六件套。**GLSL 侧未实现**：本卡只交付 CPU 侧 7 个标量；GPU 上的 float32 求值行为（含 hue preservation / 负值去饱和）未验证。
2. **引擎 0 的数值不是真机实测**（见 §5.4），且 `filmic-spline-ref.py` 的求值是 double（真机 shader 是 float32）⇒ 引擎 0 的绝对值可能差 <1 LSB。
3. **`white=99.4` 的对齐声明与 float32 实测不符**：设计文档 §10 说"99.4 时精确命中 204.0"，float32 实测为 **204.567**（double 口径 203.93 才接近 204）⇒ 该档建议改用 `99.525`。已按架构师要求两档并列报告，未擅自改文档。
4. **contrast ≥ 2.0 的非单调未做真机确认**：本卡只证明它是 float32 舍入的数学必然（§4），未验证真机 GPU 是否同现象（GPU 的 `pow` 精度实现相关）。
5. **R-2 口径 M9 的历史 latitude 未考证**：本卡用当前 `builtin.json` 的 M9 latitude 恰好复现 17 号数值，但 R-2 时期的 latitude 实际值未核实（超出本卡边界）。**未与 darktable 官方产物逐像素对拍**：只对拍了 17 号报告的独立复算值 + PhotonCamera 硬编码常量（两个独立第三方）。
6. **官方源码的其余部分未移植**（`color_processing` / `purity` / `inset` / `rotation` / `base_primaries`）：契约结构体无这些字段，属别的卡范围（`docs/engine-slots-design.md` §7 由 GLSL 注入段决定）。

## 7. 复跑命令

```bash
g++ -std=c++20 -O2 -Wall -Wextra -o /tmp/sig-dump tools/sigmoid-params-dump.cpp core/engine/src/film/SigmoidParams.cpp
/tmp/sig-dump > /tmp/sig-cpp.csv
python3 tools/sigmoid-params-ref.py --check        # 锚点判据（--probe-powf 另验 float32 口径前提）
python3 tools/sigmoid-params-ref.py --ab /tmp/sig-cpp.csv   # 对拍（判据 rel < 1e-6）
python3 tools/sigmoid-criteria-scan.py             # 三判据 + 引擎 0 对照
```
