#!/usr/bin/env python3
"""darktable `src/iop/sigmoid.c` `commit_params` 的 Python 逐行复刻（float32 口径）。

**一手来源**：darktable master `src/iop/sigmoid.c`
    https://github.com/darktable-org/darktable/blob/master/src/iop/sigmoid.c
（2026-10-14 重新下载逐字核对，行号即该版本；与 `core/engine/src/film/SigmoidParams.cpp`
的 `sigmoid.c:NNN` 对照注释同源。）

**为什么是 float32 逐步运算**：darktable 的 `dt_iop_sigmoid_data_t` 字段（`:166-167`）与
`commit_params` 全部局部量都是 `float`、幂运算走 `powf` ⇒ 官方**实跑是 float32 全程**。
本文件每个中间量都用 `np.float32` 包裹，**不做 double 提升**（numpy 的 `float32 ** float32`
经实测与 libm `powf` **位级一致**，见 `--probe-powf`）。

⚠ 差分求解对舍入极敏感：`ref_slope` 是 `(f(x+δ) − f(x−δ)) / 2 / δ`，δ = 1e-6，
而 f(x±δ) ≈ 0.18 的 float32 ULP ≈ 1.5e-8 ⇒ 差值 1.6e-6 只剩约 1 位有效数字。
所以 `film_power` 在 float32 下是 **1.490909**（不是 1.5）。这不是 bug，是官方实跑值；
**任何一处 double 提升都会破坏与官方的一致性**。

用法：
    python3 tools/sigmoid-params-ref.py --dump        # 对拍用 CSV（与 dump.cpp 同格式）
    python3 tools/sigmoid-params-ref.py --probe-powf  # 证明 numpy float32 幂 == libm powf
"""
import argparse
import ctypes
import struct
import sys

import numpy as np

# ── sigmoid.c:37  #define MIDDLE_GREY 0.1845f ────────────────────────────────
MIDDLE_GREY = np.float32(0.1845)

# C++ 字面量的 float32 位级等价（写全，避免 numpy 版本差异导致的类型提升）
_F = np.float32
ZERO = _F(0.0)
ONE = _F(1.0)
TWO = _F(2.0)
FIVE = _F(5.0)
P01 = _F(0.01)
DELTA = _F(1e-6)


def f32(x):
    """C++ `static_cast<float>` 的位级等价。"""
    return _F(x)


# ── sigmoid.c:298-316  _generalized_loglogistic_sigmoid ──────────────────────
def generalized_loglogistic_sigmoid(value, magnitude, paper_exp, film_fog, film_power, paper_power):
    """逐行复刻 `_generalized_loglogistic_sigmoid`（标量；float32）。

    也接受 numpy 数组（向量化路径，见 `sigmoid_curve`）—— 运算序列完全相同。
    """
    # sigmoid.c:307
    clamped_value = np.maximum(value, ZERO)
    # sigmoid.c:308-310（官方注释：为什么用这个等价形式）
    #   magnitude * powf(1.0f + paper_exp * powf(film_fog + value, -film_power), -paper_power);
    #   Rewritten on a stable around zero form:
    # sigmoid.c:311
    film_response = np.power(film_fog + clamped_value, film_power)
    # sigmoid.c:312
    paper_response = magnitude * np.power(film_response / (paper_exp + film_response), paper_power)

    # sigmoid.c:314-315  // Safety check for very large floats that cause numerical errors
    #                    dt_isnan → np.isnan（标量输入返回 numpy bool）
    if np.ndim(paper_response) == 0:
        return magnitude if bool(np.isnan(paper_response)) else _F(paper_response)
    return np.where(np.isnan(paper_response), magnitude, paper_response)


# ── sigmoid.c:318-384  commit_params（只保留与本卡 7 个标量相关的部分）───────
def compute_sigmoid_params(middle_grey_contrast, contrast_skewness,
                           display_white_target, display_black_target,
                           hue_preservation):
    """逐行复刻 `commit_params`（float32 全程）。

    入参语义与官方同名参数**完全一致**（`SigmoidParams.h` 契约）：
      middle_grey_contrast : 0.1..10，默认 1.5
      contrast_skewness    : -1..1，默认 0
      display_white_target : **百分数** 20..1600，默认 100
      display_black_target : **百分数** 0..15，默认 0.0152
      hue_preservation     : **百分数** 0..100，默认 100
    返回 7 个标量的 dict（值均为 np.float32）。
    """
    middle_grey_contrast = _F(middle_grey_contrast)
    contrast_skewness = _F(contrast_skewness)
    display_white_target = _F(display_white_target)
    display_black_target = _F(display_black_target)
    hue_preservation = _F(hue_preservation)

    # sigmoid.c:325-330（官方四条设计约束，逐字）：
    #   f(scene_zero) = display_black_target / f(scene_grey) = MIDDLE_GREY
    #   f(scene_inf)  = display_white_target / 中灰斜率只由 contrast 决定

    # sigmoid.c:332  // Calculate a reference slope for no skew and a normalized display
    ref_film_power = middle_grey_contrast                    # :333
    ref_paper_power = ONE                                    # :334
    ref_magnitude = ONE                                      # :335
    ref_film_fog = ZERO                                      # :336
    ref_paper_exposure = (np.power(ref_film_fog + MIDDLE_GREY, ref_film_power)
                          * ((ref_magnitude / MIDDLE_GREY) - ONE))   # :337-338
    delta = DELTA                                            # :339
    ref_slope = (generalized_loglogistic_sigmoid(
                     MIDDLE_GREY + delta, ref_magnitude, ref_paper_exposure,
                     ref_film_fog, ref_film_power, ref_paper_power)
                 - generalized_loglogistic_sigmoid(
                     MIDDLE_GREY - delta, ref_magnitude, ref_paper_exposure,
                     ref_film_fog, ref_film_power, ref_paper_power)
                 ) / TWO / delta                             # :340-346（除法顺序逐字保留）

    # sigmoid.c:347-348  // Add skew
    paper_power = np.power(FIVE, -contrast_skewness)

    # sigmoid.c:350  // Slope at low film power
    temp_film_power = ONE                                    # :351
    temp_white_target = P01 * display_white_target           # :352
    temp_white_grey_relation = (np.power(temp_white_target / MIDDLE_GREY, ONE / paper_power)
                                - ONE)                       # :353-354
    temp_paper_exposure = np.power(MIDDLE_GREY, temp_film_power) * temp_white_grey_relation  # :355
    temp_slope = (generalized_loglogistic_sigmoid(
                      MIDDLE_GREY + delta, temp_white_target, temp_paper_exposure,
                      ref_film_fog, temp_film_power, paper_power)
                  - generalized_loglogistic_sigmoid(
                      MIDDLE_GREY - delta, temp_white_target, temp_paper_exposure,
                      ref_film_fog, temp_film_power, paper_power)
                  ) / TWO / delta                            # :356-362

    # sigmoid.c:364-365
    #   // Figure out what film power fulfills the target slope
    #   // (linear when assuming display_black = 0.0)
    film_power = ref_slope / temp_slope

    # sigmoid.c:367  // Calculate the other parameters now that both film and paper power is known
    white_target = P01 * display_white_target                # :368
    black_target = P01 * display_black_target                # :369
    # :370-371（官方用 module_data->white_target 重算，而非复用 temp_white_target ——
    #          数值恒等但运算序列不同，逐字保留）
    white_grey_relation = np.power(white_target / MIDDLE_GREY, ONE / paper_power) - ONE
    # :372-373
    # ⚠ `display_black_target = 0` 时 `black_target = 0` ⇒ `0 ** (-1)` = inf（IEEE）。
    #   这是**官方同样的行为**（C++ 侧同样得 inf，随后 film_fog 的分母 inf 使 fog = 0），
    #   不是本实现的缺陷 ⇒ 只静音 numpy 警告，不改变运算。
    with np.errstate(divide='ignore', invalid='ignore'):
        white_black_relation = np.power(black_target / white_target, -ONE / paper_power) - ONE

    film_fog = (MIDDLE_GREY * np.power(white_grey_relation, ONE / film_power)
                / (np.power(white_black_relation, ONE / film_power)
                   - np.power(white_grey_relation, ONE / film_power)))   # :375-377
    paper_exposure = (np.power(film_fog + MIDDLE_GREY, film_power)
                      * white_grey_relation)                 # :378-379

    # sigmoid.c:381 color_processing —— 契约无此字段（见 .cpp 文件头"刻意省略"）
    hue_pres = np.minimum(np.maximum(P01 * hue_preservation, ZERO), ONE)  # :382

    return {
        'white_target': _F(white_target),
        'black_target': _F(black_target),
        'paper_exposure': _F(paper_exposure),
        'film_fog': _F(film_fog),
        'film_power': _F(film_power),
        'paper_power': _F(paper_power),
        'hue_preservation': _F(hue_pres),
    }


def compute_sigmoid_params_double(middle_grey_contrast, contrast_skewness,
                                  display_white_target, display_black_target,
                                  hue_preservation):
    """**仅供报告对照**：同一公式的 double 口径（= PhotonCamera 硬编码常量的口径）。

    不是"更正确"的实现 —— darktable 实跑是 float32（见模块 docstring）。
    """
    def sig(v, m, pe, ff, fp, pp):
        cv = max(v, 0.0)
        fr = (ff + cv) ** fp
        pr = m * (fr / (pe + fr)) ** pp
        return m if pr != pr else pr

    mg = 0.1845
    ref_film_power = middle_grey_contrast
    ref_paper_exposure = (mg ** ref_film_power) * ((1.0 / mg) - 1.0)
    delta = 1e-6
    ref_slope = (sig(mg + delta, 1.0, ref_paper_exposure, 0.0, ref_film_power, 1.0)
                 - sig(mg - delta, 1.0, ref_paper_exposure, 0.0, ref_film_power, 1.0)) / 2.0 / delta
    paper_power = 5.0 ** (-contrast_skewness)
    temp_white_target = 0.01 * display_white_target
    temp_white_grey_relation = (temp_white_target / mg) ** (1.0 / paper_power) - 1.0
    temp_paper_exposure = (mg ** 1.0) * temp_white_grey_relation
    temp_slope = (sig(mg + delta, temp_white_target, temp_paper_exposure, 0.0, 1.0, paper_power)
                  - sig(mg - delta, temp_white_target, temp_paper_exposure, 0.0, 1.0, paper_power)) / 2.0 / delta
    film_power = ref_slope / temp_slope
    white_target = 0.01 * display_white_target
    black_target = 0.01 * display_black_target
    white_grey_relation = (white_target / mg) ** (1.0 / paper_power) - 1.0
    white_black_relation = (black_target / white_target) ** (-1.0 / paper_power) - 1.0
    film_fog = (mg * white_grey_relation ** (1.0 / film_power)
                / (white_black_relation ** (1.0 / film_power) - white_grey_relation ** (1.0 / film_power)))
    paper_exposure = ((film_fog + mg) ** film_power) * white_grey_relation
    return {
        'white_target': white_target, 'black_target': black_target,
        'paper_exposure': paper_exposure, 'film_fog': film_fog,
        'film_power': film_power, 'paper_power': paper_power,
        'hue_preservation': min(max(0.01 * hue_preservation, 0.0), 1.0),
    }


# ── 曲线求值（离线链消费入口）────────────────────────────────────────────────
def sigmoid_curve(x, params):
    """`_generalized_loglogistic_sigmoid` 的向量化复刻（float32）。

    x : 场景线性值（中灰 0.1845 = 0 EV），标量或 ndarray
    params : `compute_sigmoid_params` 的返回（或同名字段的 dict）
    返回 float32（标量输入返回 np.float32）
    """
    x = np.asarray(x, dtype=np.float32)
    p = {k: _F(v) for k, v in params.items()}
    return generalized_loglogistic_sigmoid(x, p['white_target'], p['paper_exposure'],
                                           p['film_fog'], p['film_power'], p['paper_power'])


# ── 8bit + 真 sRGB OETF 口径（docs/engine-slots-design.md §10 判据口径，逐字）──
def srgb_oetf_8bit(c):
    """线性显示值 → 真 sRGB OETF → 8bit（**未量化浮点**，与 16/17 号报告口径一致）。

    `c ≤ 0.0031308: 12.92c`；否则 `1.055·c^(1/2.4) − 0.055`。
    """
    c = np.asarray(c, dtype=np.float64)
    lin = 12.92 * c
    nonlin = 1.055 * np.power(np.maximum(c, 0.0), 1.0 / 2.4) - 0.055
    out = np.where(c <= 0.0031308, lin, nonlin)
    return np.clip(out, 0.0, 1.0) * 255.0


# ── 对拍 / 自检 ─────────────────────────────────────────────────────────────
# 任务卡要求的参数组合（(contrast, skew) 对；white=100 / black=0.0152 / hue=100）
CASES = [(1.2, 0.0), (1.5, 0.0), (1.0, 0.0), (2.2, 0.0),
         (1.5, -0.3), (1.5, 0.65), (1.22, 0.65)]
WHITE, BLACK, HUE = 100.0, 0.0152, 100.0
FIELDS = ['white_target', 'black_target', 'paper_exposure', 'film_fog',
          'film_power', 'paper_power', 'hue_preservation']


def _row(c, s, w, bl, h):
    """一行 CSV（与 `tools/sigmoid-params-dump.cpp` 的 `%.9g` 逐字同格式）。

    ⚠ 入参也要先过 float32：C++ 侧字面量是 `1.2f`，`%.9g` 打出 `1.20000005`；
    Python 若直接格式化 double 1.2 会打出 `1.2` ⇒ 首五列对不上（不是数值差异，
    是格式化口径差异）。这里统一按 float32 往返。
    """
    p = compute_sigmoid_params(c, s, w, bl, h)
    row = [f'{float(_F(v)):.9g}' for v in (c, s, w, bl, h)]
    row += [f'{float(p[k]):.9g}' for k in FIELDS]
    return ','.join(row)


def dump_csv():
    """与 `tools/sigmoid-params-dump.cpp` 逐字同格式的 CSV（对拍用）。"""
    print('contrast,skew,white,black,hue,' + ','.join(FIELDS))
    for c, s in CASES:
        print(_row(c, s, WHITE, BLACK, HUE))


def ab(cpp_csv_path):
    """对拍：读 C++ dump 的 CSV，逐行用 Python 重算并算相对误差（判据 < 1e-6）。"""
    worst, worst_where, n, bad = 0.0, None, 0, 0
    with open(cpp_csv_path, encoding='utf-8') as f:
        lines = [ln.strip() for ln in f if ln.strip()]
    assert lines[0].startswith('contrast,'), f'表头不符: {lines[0]}'
    for ln in lines[1:]:
        v = ln.split(',')
        c, s, w, bl, h = (float(x) for x in v[:5])
        got = compute_sigmoid_params(c, s, w, bl, h)
        for i, k in enumerate(FIELDS):
            a, b = float(got[k]), float(v[5 + i])          # a = Python, b = C++
            den = max(abs(a), abs(b))
            rel = abs(a - b) / den if den > 0.0 else 0.0
            n += 1
            if rel > 1e-6:
                bad += 1
                print(f'  ✗ contrast={c:g} skew={s:g} white={w:g} black={bl:g} hue={h:g} '
                      f'{k}: py={a:.9g} cpp={b:.9g} rel={rel:.3e}')
            if rel > worst:
                worst, worst_where = rel, (c, s, w, bl, h, k, a, b)
    print(f'对拍：{n} 项（{len(lines) - 1} 行 × 7 标量），最大相对误差 {worst:.3e}'
          f'（阈值 1e-6），超限 {bad} 项')
    if worst_where:
        c, s, w, bl, h, k, a, b = worst_where
        print(f'  最差项：contrast={c:g} skew={s:g} white={w:g} black={bl:g} hue={h:g} '
              f'{k}: py={a:.9g} cpp={b:.9g}')
    return bad == 0


def probe_powf():
    """证明 numpy 的 float32 幂 == libm `powf`（位级）—— 这是 float32 口径成立的前提。"""
    libm = ctypes.CDLL(None)
    libm.powf.argtypes = [ctypes.c_float, ctypes.c_float]
    libm.powf.restype = ctypes.c_float
    cases = [(0.1845, 1.5), (0.1845, 1.2), (0.1845, 1.0), (0.18450001, 1.5), (0.18449999, 1.5),
             (5.0, 0.0), (5.0, 0.3), (0.000152, -1.0), (0.1, 0.5), (1e-6, 2.2)]
    bad = []
    for a, b in cases:
        c = libm.powf(ctypes.c_float(a), ctypes.c_float(b))
        n = _F(np.power(_F(a), _F(b)))
        if struct.pack('<f', float(c)) != struct.pack('<f', float(n)):
            bad.append((a, b, float(c), float(n)))
    print(f'numpy={np.__version__}  libm powf 对照：{len(cases) - len(bad)}/{len(cases)} 位级一致')
    for a, b, c, n in bad:
        print(f'  DIFF powf({a},{b}) libm={c!r} numpy={n!r}')
    return not bad


def _digits_of(ref):
    """参考值字符串的有效数字位数（`0.001384` → 4，`1.490909` → 7）。"""
    return len(ref.lstrip('-').replace('.', '').lstrip('0'))


def _sig_round(x, digits):
    """按有效数字位数舍入 —— "逐位对齐"的正确判据。

    ⚠ 不能用"相对误差 < 1e-5"直接卡：参考值只给了 N 位有效数字时，它自身的
    舍入误差上限就有 ~0.5·10^(1−N)/|v|（`0.001384` 只有 4 位 ⇒ 上限 ~3.6e-4），
    比阈值大一个量级 ⇒ 会把**正确的**实现判成 FAIL。
    """
    return float(f'{x:.{digits}g}')


def _check():
    """锚点判据自检（float32 官方实跑口径 + double = PC 硬编码口径）。

    两条独立证据链：
      ① double 口径 == PhotonCamera 硬编码常量（`DT_SIGMOID_FILM_POWER` 等）
         ⇒ 证明**公式实现正确**（第三方独立产物交叉验证）；
      ② float32 口径 == 17 号报告 §1.3 的独立复算（1.490909 / 0.001384 / 0.359695）
         ⇒ 证明**浮点口径正确**（= darktable 实跑）。
    """
    def chain(title, got, refs):
        print(f'  {title}')
        ok = True
        for k, ref in refs.items():
            v = float(got[k])
            d = _digits_of(ref)
            rel = abs(v - float(ref)) / abs(float(ref))
            hit = _sig_round(v, d) == float(ref)
            ok &= hit
            print(f'    {k:16s} = {v:.9g}   参考 {ref}（{d} 位有效数字）'
                  f'   舍入后{"一致 ✓" if hit else "不一致 ✗"}   相对误差 {rel:.2e}')
        return ok

    print('锚点（contrast=1.5, skew=0, white=100, black=0.0152, hue=100）')
    ok1 = chain('float32（= darktable 实跑，本实现的主口径）:',
                compute_sigmoid_params(1.5, 0.0, 100.0, 0.0152, 100.0),
                {'film_power': '1.490909', 'film_fog': '0.001384', 'paper_exposure': '0.359695'})
    ok2 = chain('double（= PhotonCamera 硬编码常量口径，仅作公式正确性交叉验证）:',
                compute_sigmoid_params_double(1.5, 0.0, 100.0, 0.0152, 100.0),
                {'film_power': '1.5', 'film_fog': '0.00142637086', 'paper_exposure': '0.354355423'})
    print('  判据:', 'PASS' if (ok1 and ok2) else 'FAIL')
    return ok1 and ok2


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--dump', action='store_true', help='打印对拍 CSV')
    ap.add_argument('--ab', metavar='CPP_CSV', help='与 C++ dump 的 CSV 对拍（判据 rel < 1e-6）')
    ap.add_argument('--probe-powf', action='store_true', help='证明 numpy float32 幂 == libm powf')
    ap.add_argument('--check', action='store_true', help='锚点自检')
    a = ap.parse_args()
    if a.probe_powf:
        sys.exit(0 if probe_powf() else 1)
    if a.check:
        sys.exit(0 if _check() else 1)
    if a.ab:
        sys.exit(0 if ab(a.ab) else 1)
    if a.dump:
        dump_csv()
    else:
        ap.print_help()
