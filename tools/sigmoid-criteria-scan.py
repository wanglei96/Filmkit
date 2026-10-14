#!/usr/bin/env python3
"""阶段 2 · sigmoid 引擎的三条判据数值扫描（纯曲线口径）。

判据定义**逐字**取自 `docs/engine-slots-design.md` §7 + §10 第 1 条（用户 2026-10-13 拍板）：

  输入口径：`s = 0.1845 · 2^EV`（中灰 18.45% = 0 EV），EV 网格 −1.0 ~ +9.0、步长 0.05
  输出口径：线性显示值 → **真 sRGB OETF** → **8bit**（`c ≤ 0.0031308: 12.92c`；
            否则 `1.055·c^(1/2.4) − 0.055`）
  可分辨跨度 = 从**白纸亮度**（2.30 EV）起，每 0.1 EV 的输出差 **≥1 LSB** 能持续多远
  同白纸亮度前提：基准 = **204.0 LSB**（sigmoid c=1.2 在 `display_white_target = 99.4`
            时精确命中；默认 100 时 203.60）⇒ 两档并列报告
  ⚠ **2026-10-14 起引擎 1 默认 = contrast 1.5**（darktable/PC 官方默认，用户拍板"跟 PC
            一致"）⇒ 白纸 218.84 LSB，**同白纸口径作废**，本脚本的 c=1.2 各档降为历史对照。

引擎 1（sigmoid）用 `tools/sigmoid-params-ref.py`（**float32**，= darktable 实跑口径）。
引擎 0（filmic）用 `tools/filmic-spline-ref.py`（16 号报告时期的 `computeFilmicSpline`
逐行复刻；**double 纯曲线**口径）。

⚠ **口径不对称是刻意的、也是必须标注的**：darktable 的 sigmoid 实跑是 float32
（17 号 §1.3），而本项目 filmic 的 `computeFilmicSpline` 系数虽是 float32、shader 求值
在 GPU 上是 float32，但 `filmic-spline-ref.py` 的**求值**是 double。
本卡不混算两套口径：引擎 1 一律 float32，引擎 0 一律 double，并在报告里注明。
⚠ 引擎 0 的数值是**本脚本现场调用复刻件算出的纯曲线值**，不是真机 `kFsFilm` 实测
（真机含 gamut mapping / highlightRolloff / LUT）—— 报告已标注。

用法：python3 tools/sigmoid-criteria-scan.py
"""
import importlib.util
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sig = _load(os.path.join(HERE, 'sigmoid-params-ref.py'), 'sigref')
fil = _load(os.path.join(HERE, 'filmic-spline-ref.py'), 'filref')

# ── 判据口径常量（engine-slots-design.md §10，逐字）─────────────────────────
GREY = 0.1845
EV_MIN, EV_MAX, EV_STEP = -1.0, 9.0, 0.05
WHITE_PAPER_EV = 2.30          # 白纸（16/17 号报告的口径）
TARGET_WHITE_LSB = 204.0       # 同白纸亮度的对齐基准
RESOLVE_STEP_EV = 0.1          # 可分辨跨度的步长
RESOLVE_MIN_LSB = 1.0          # 每 0.1 EV 至少 1 LSB

# 引擎 0（filmic 白点 4.0）参数：17 号 §1.2 的 "R-2 口径 M9（旧配方只改白点）"
#   black=−4.24 / white=4.00 / contrast_param=0.9741
#   latitude 取 **`app/src/main/assets/presets/builtin.json` 里 M9 预设的真实值**
#   （shoulder=[0.5278,0.4253,0.5806] / toe=[0.9219,0.8216,0.9488]），**不是** darktable
#   默认 0.01 —— 这是本卡定位到的关键口径（17 号未在报告里写明 latitude）。
#   实测交叉验证（见 `engine0_selfcheck()`）：
#     · 本组配置         → 白纸 205.126 LSB（17 号 §1.2 = 205.13）
#     · R-3 新配方 M9    → 白纸 220.450 LSB（17 号 §1.2 = 220.45，逐位命中）
#   若误用 lat=0.01，白纸会变成 210.52 LSB（差 5.4 LSB）⇒ 段判据落到 shoulder 段。
ENGINE0 = {'black_ev': -4.24, 'white_ev': 4.00, 'contrast_param': 0.9741,
           'lat_sh': [0.5278, 0.4253, 0.5806], 'lat_toe': [0.9219, 0.8216, 0.9488]}
# R-3 新配方（= 当前 builtin.json 的 M9 值），只用于上面的交叉验证
ENGINE0_R3 = {'black_ev': -11.9726, 'white_ev': 4.00, 'contrast_param': 1.03,
              'lat_sh': ENGINE0['lat_sh'], 'lat_toe': ENGINE0['lat_toe']}

# darktable sigmoid 官方预设（`sigmoid.c:224-243` init_presets，逐字）
DT_PRESETS = [('neutral gray', 1.22, 0.65), ('ACES 100-nit like', 1.6, -0.2),
              ('smooth', 1.5, -0.2)]


def ev_grid(lo=EV_MIN, hi=EV_MAX, step=EV_STEP):
    n = int(round((hi - lo) / step))
    return np.array([lo + i * step for i in range(n + 1)], dtype=np.float64)


def scene(ev):
    """`s = 0.1845 · 2^EV`（中灰 0.1845 = 0 EV）。"""
    return GREY * np.power(2.0, np.asarray(ev, dtype=np.float64))


def lsb(y):
    """线性显示值 → 真 sRGB OETF → 8bit（未量化浮点）。"""
    return sig.srgb_oetf_8bit(y)


def resolvable_span(ev, y8, start_ev=WHITE_PAPER_EV):
    """从白纸起，每 0.1 EV 输出差 ≥1 LSB 能持续多远（单位 EV）。

    `≥1` 是**单向增量**（`y[k+2] − y[k] ≥ 1`），与 16/17 号报告的反推口径一致。
    """
    i0 = int(round((start_ev - ev[0]) / EV_STEP))
    step_idx = int(round(RESOLVE_STEP_EV / EV_STEP))
    k = i0
    while k + step_idx < len(y8) and (y8[k + step_idx] - y8[k]) >= RESOLVE_MIN_LSB:
        k += step_idx
    return ev[k] - ev[i0]


# ── 引擎 1（sigmoid，float32）────────────────────────────────────────────────
def engine1_curve(contrast, skew=0.0, white_pct=100.0, black_pct=0.0152, hue_pct=100.0):
    p = sig.compute_sigmoid_params(contrast, skew, white_pct, black_pct, hue_pct)
    ev = ev_grid()
    y8 = np.asarray(lsb(sig.sigmoid_curve(scene(ev), p)), dtype=np.float64)
    return p, ev, y8


# ── 引擎 0（filmic，double 纯曲线）───────────────────────────────────────────
def filmic_curve(black_ev, white_ev, contrast_param, lat_sh=None, lat_toe=None):
    """`kFsFilm` 的 filmic 段（纯曲线）：log 编码 + 三段 spline + pow。

    表达式序列照 `FrameRenderer.cpp:4198-4204 filmicScalarR` 与
    `tools/filmic-spline-ref.py`（`computeFilmicSpline` 的逐行复刻）。
    """
    lat_sh = lat_sh or ENGINE0['lat_sh']
    lat_toe = lat_toe or ENGINE0['lat_toe']
    sp = fil.compute_filmic_spline(black_ev, white_ev, contrast_param, lat_sh, lat_toe)
    ev = ev_grid()
    return sp, ev, lsb(filmic_raw(ev, sp))


def solve_engine0_white_ev(target_lsb=TARGET_WHITE_LSB):
    """扫 filmic 的 white_ev 使白纸亮度命中 target_lsb（同白纸亮度前提）。"""
    i_white = int(round((WHITE_PAPER_EV - EV_MIN) / EV_STEP))
    best, best_d = None, 1e9
    for w in np.arange(4.0, 24.0, 0.005):
        _, _, y8 = filmic_curve(ENGINE0['black_ev'], float(w), ENGINE0['contrast_param'])
        d = abs(y8[i_white] - target_lsb)
        if d < best_d:
            best, best_d = float(w), d
    return best, best_d


def engine0_selfcheck():
    """验证**工具自身**：本复刻件必须复现 17 号 §1.2 的两个独立复算值。

    这是"验证工具自身会假绿"的护栏 —— 引擎 0 是对照臂，它的复刻件若口径错，
    整张判据① 的表都不可信。
    """
    i_white = int(round((WHITE_PAPER_EV - EV_MIN) / EV_STEP))
    checks = [('R-2 口径 M9（本卡引擎 0 主口径）', ENGINE0, 205.13),
              ('R-3 新配方 M9（当前 builtin.json）', ENGINE0_R3, 220.45)]
    ok = True
    print('  · 引擎 0 复刻件交叉验证（对 17 号 §1.2 的独立复算）：')
    for name, cfg, ref in checks:
        _, _, y8 = filmic_curve(cfg['black_ev'], cfg['white_ev'], cfg['contrast_param'],
                                cfg['lat_sh'], cfg['lat_toe'])
        got = float(y8[i_white])
        hit = abs(got - ref) < 0.05
        ok &= hit
        print(f'    {name:<34} 白纸 = {got:8.3f} LSB   17 号 = {ref:8.2f}   '
              f'{"一致 ✓" if hit else "不一致 ✗"}')
    return ok


def _white_lsb(contrast, white_pct, skew=0.0):
    """单点：白纸（2.30 EV）的 8bit 输出（float32 口径）。"""
    p = sig.compute_sigmoid_params(contrast, skew, white_pct, 0.0152, 100.0)
    return float(lsb(sig.sigmoid_curve(np.float64(GREY * 2.0 ** WHITE_PAPER_EV), p)))


def solve_sigmoid_white_pct(contrast, target_lsb=TARGET_WHITE_LSB):
    """扫 `display_white_target`（官方全范围 20..1600）使白纸亮度命中 target_lsb。

    ⚠ **这是病态问题**：float32 下 `film_power` 对 `display_white_target` 是**阶梯常数**
    （差分求解的舍入噪声被放大，见报告 §4），而 `white_target` 又单调影响白纸
    ⇒ 白纸 LSB 对 white 强烈**非单调**，同一 contrast 可能有多个解或没有解。
    因此返回 (解, 残差, 解个数)；残差 > 0.05 LSB 即视为"该档不可精确对齐"。
    """
    def scan(lo, hi, step):
        best, bd, hits = None, 1e9, 0
        w = lo
        while w <= hi:
            d = _white_lsb(contrast, w) - target_lsb
            if abs(d) < 0.05:
                hits += 1
            if abs(d) < abs(bd):
                best, bd = w, d
            w += step
        return best, bd, hits

    w0, _, _ = scan(20.0, 1600.0, 1.0)              # 粗扫
    if w0 is None:
        return None, None, 0
    best, bd, _ = scan(max(20.0, w0 - 2.0), min(1600.0, w0 + 2.0), 0.005)   # 精扫
    # 解个数：在粗扫范围内统计满足 |残差| < 0.05 的**独立区间**数
    hits, prev = 0, False
    w = 20.0
    while w <= 1600.0:
        cur = abs(_white_lsb(contrast, w) - target_lsb) < 0.05
        if cur and not prev:
            hits += 1
        prev = cur
        w += 1.0
    return best, bd, hits


# ── 判据 3：线性域严格单调 ───────────────────────────────────────────────────
MONO_LO, MONO_HI, MONO_ST = -1.0, 11.0, 0.02     # 判据口径（engine-slots-design §7）


def sigmoid_curve_double(x, p):
    """同一曲线的 **double** 口径求值（仅用于把"曲线性质"与"float32 舍入"分开）。"""
    x = np.asarray(x, dtype=np.float64)
    cv = np.maximum(x, 0.0)
    fr = np.power(p['film_fog'] + cv, p['film_power'])
    return p['white_target'] * np.power(fr / (p['paper_exposure'] + fr), p['paper_power'])


def _mono_row(contrast, use_double):
    """一条曲线在 −1~+11 EV / 0.02 EV 下的（非正差个数, 最小相邻差, 位置 EV, 末端值）。"""
    n = int(round((MONO_HI - MONO_LO) / MONO_ST))
    ev = np.array([MONO_LO + i * MONO_ST for i in range(n + 1)])
    s = scene(ev)
    if use_double:
        p = sig.compute_sigmoid_params_double(contrast, 0.0, 100.0, 0.0152, 100.0)
        y = sigmoid_curve_double(s, p)
    else:
        p = sig.compute_sigmoid_params(contrast, 0.0, 100.0, 0.0152, 100.0)
        y = np.asarray(sig.sigmoid_curve(s, p), dtype=np.float64)
    d = np.diff(y)
    i = int(np.argmin(d))
    return int(np.sum(d <= 0.0)), float(d.min()), float(ev[i + 1]), float(y[-1])


def monotonic_check():
    """判据 3 主表：contrast 扫描 × {float32, double}；外加 +12 / +20 EV 渐近行为。"""
    rows = []
    for c in (1.0, 1.2, 1.5, 1.8, 2.0, 2.2, 2.5):
        rows.append((c,) + _mono_row(c, False) + _mono_row(c, True))

    # 渐近行为（+12 / +20 EV）
    asym = []
    for c in (1.0, 1.2, 1.5, 2.2):
        p = sig.compute_sigmoid_params(c, 0.0, 100.0, 0.0152, 100.0)
        y12 = float(sig.sigmoid_curve(np.float64(GREY * 2.0 ** 12), p))
        y20 = float(sig.sigmoid_curve(np.float64(GREY * 2.0 ** 20), p))
        asym.append((c, y12, y20))

    # 引擎 0 对照（硬平顶）
    sp, ev0, _ = filmic_curve(ENGINE0['black_ev'], ENGINE0['white_ev'], ENGINE0['contrast_param'])
    n = int(round((MONO_HI - MONO_LO) / MONO_ST))
    ev = np.array([MONO_LO + i * MONO_ST for i in range(n + 1)])
    ys = filmic_raw(ev, sp)
    d0 = np.diff(ys)
    flat_at = None
    for i, v in enumerate(d0):
        if v == 0.0:
            flat_at = float(ev[i + 1])
            break
    return rows, int(np.sum(d0 <= 0.0)), float(d0.min()), flat_at, asym


def filmic_raw(ev, sp):
    """filmic 纯曲线求值（未过 OETF），表达式序列照 `filmicScalarR`（FrameRenderer.cpp:4198-4204）。"""
    out = np.empty(len(ev))
    for i, s in enumerate(scene(ev)):
        s = max(s, 1.52587890625e-5)          # kFilmicNormMin = 2^-16
        e = min(max((math.log2(s / GREY) - sp['black_ev']) / max(sp['dynamic_range'], 0.2), 0.0), 1.0)
        c = min(max(fil.spline_at(sp, e), sp['black_disp']), 1.0)
        out[i] = max(c, 0.0) ** sp['output_power']
    return out


def main():
    i_white = int(round((WHITE_PAPER_EV - EV_MIN) / EV_STEP))
    i_grey = int(round((0.0 - EV_MIN) / EV_STEP))

    print('=' * 100)
    print('阶段 2 · sigmoid 引擎三判据扫描（纯曲线口径 · 8bit + 真 sRGB OETF）')
    print(f'输入 s = 0.1845·2^EV；EV 网格 {EV_MIN}~{EV_MAX} 步长 {EV_STEP}；'
          f'白纸 = {WHITE_PAPER_EV} EV；对齐基准 = {TARGET_WHITE_LSB} LSB')
    print('=' * 100)

    # ── 引擎 0（filmic 白点 4.0）────────────────────────────────────────────
    print('\n【引擎 0 对照 · filmic】参数 = 17 号 §1.2 "R-2 口径 M9"'
          f'（black={ENGINE0["black_ev"]}, white={ENGINE0["white_ev"]}, '
          f'cp={ENGINE0["contrast_param"]}）+ builtin.json M9 的 latitude')
    if not engine0_selfcheck():
        print('  ⚠ 引擎 0 复刻件未通过交叉验证 ⇒ 下表对照值不可信')
    sp0, ev0, y0 = filmic_curve(ENGINE0['black_ev'], ENGINE0['white_ev'], ENGINE0['contrast_param'])
    print(f'  派生：dr={sp0["dynamic_range"]:.4f}  power={sp0["output_power"]:.6f}  '
          f'black_disp={sp0["black_disp"]:.6e}  contrast={sp0["contrast"]:.6f}')
    print(f'  白纸 {WHITE_PAPER_EV} EV = {y0[i_white]:.3f} LSB（未量化）'
          f' / {round(y0[i_white]):.0f} LSB（量化）'
          f'   中灰 0 EV = {y0[i_grey]:.3f} LSB'
          f'   可分辨跨度 = {resolvable_span(ev0, y0):.2f} EV')
    wev, werr = solve_engine0_white_ev()
    sp0b, ev0b, y0b = filmic_curve(ENGINE0['black_ev'], wev, ENGINE0['contrast_param'])
    print(f'  ★ 对齐 {TARGET_WHITE_LSB} LSB：white_ev = {wev:.3f}（残差 {werr:.4f} LSB）⇒ '
          f'白纸 {y0b[i_white]:.3f} LSB  中灰 {y0b[i_grey]:.3f} LSB  '
          f'可分辨跨度 = {resolvable_span(ev0b, y0b):.2f} EV')

    # ── 判据 1：可分辨跨度 ──────────────────────────────────────────────────
    print('\n【判据 1 · 可分辨跨度】从白纸（2.30 EV）起每 0.1 EV ≥1 LSB 能持续多远')
    print(f'{"引擎":<34}{"白纸LSB":>10}{"中灰LSB":>10}{"可分辨跨度":>12}{"到( EV )":>10}')
    print('-' * 100)
    print(f'{"引擎0 filmic(白点4.0, 原生)":<34}{y0[i_white]:>10.3f}{y0[i_grey]:>10.3f}'
          f'{resolvable_span(ev0, y0):>10.2f} EV'
          f'{WHITE_PAPER_EV + resolvable_span(ev0, y0):>10.2f}')
    print(f'{"引擎0 filmic(对齐204.0)":<34}{y0b[i_white]:>10.3f}{y0b[i_grey]:>10.3f}'
          f'{resolvable_span(ev0b, y0b):>10.2f} EV'
          f'{WHITE_PAPER_EV + resolvable_span(ev0b, y0b):>10.2f}')
    for c in (1.0, 1.2, 1.5):
        for w in (100.0, 99.4):
            p, ev, y8 = engine1_curve(c, 0.0, w)
            sp_ = resolvable_span(ev, y8)
            tag = f'引擎1 sigmoid(c={c}, white={w:g})'
            print(f'{tag:<34}{y8[i_white]:>10.3f}{y8[i_grey]:>10.3f}'
                  f'{sp_:>10.2f} EV{WHITE_PAPER_EV + sp_:>10.2f}')
    print('\n  · **当前默认 = `c=1.5, white=100`**（`SigmoidParams.h` `kSigmoidDefaultContrast`，'
          '2026-10-14 与 PC/darktable 官方一致）⇒ 判据 ① 跨度 1.40 EV，'
          '不再与引擎 0 同白纸口径。')
    print('\n  · darktable 官方预设（`sigmoid.c:224-243`）—— 白纸/跨度仅供参考（未对齐）：')
    for nm, c, s in DT_PRESETS:
        p, ev, y8 = engine1_curve(c, s, 100.0)
        print(f'    {nm:<22}(c={c}, skew={s})  白纸 {y8[i_white]:.3f} LSB  '
              f'中灰 {y8[i_grey]:.3f} LSB  跨度 {resolvable_span(ev, y8):.2f} EV')

    # ── 判据 2：中灰 0EV 偏差 ───────────────────────────────────────────────
    print('\n【判据 2 · 中灰 0 EV 偏差】引擎 1 vs 引擎 0（判据 ≤ 1.3 LSB）')
    base = y0[i_grey]
    base_al = y0b[i_grey]
    print(f'{"对比":<40}{"引擎1":>10}{"引擎0":>10}{"偏差":>10}{"判定":>8}')
    print('-' * 100)
    for c in (1.0, 1.2, 1.5):
        p, ev, y8 = engine1_curve(c, 0.0, 100.0)
        d = y8[i_grey] - base
        print(f'{"c=%.1f white=100 vs 引擎0(原生白点4.0)" % c:<40}'
              f'{y8[i_grey]:>10.3f}{base:>10.3f}{d:>+10.3f}{"PASS" if abs(d) <= 1.3 else "FAIL":>8}')
    p, ev, y8 = engine1_curve(1.2, 0.0, 99.4)
    d = y8[i_grey] - base_al
    print(f'{"c=1.2 white=99.4 vs 引擎0(对齐204.0)":<40}'
          f'{y8[i_grey]:>10.3f}{base_al:>10.3f}{d:>+10.3f}{"PASS" if abs(d) <= 1.3 else "FAIL":>8}')
    print(f'  · 引擎 0 自身两口径之差（原生 vs 对齐）：{base - base_al:+.3f} LSB')

    # ── 判据 3：线性域严格单调 ──────────────────────────────────────────────
    print(f'\n【判据 3 · 线性域严格单调】{MONO_LO} ~ {MONO_HI} EV、步长 {MONO_ST} EV，'
          f'相邻输出差恒 > 0（判据口径）')
    rows, neg0, min0, flat_at, asym = monotonic_check()
    print(f'{"contrast":>9}{"非正差(f32)":>12}{"最小相邻差(f32)":>18}{"位置EV":>9}'
          f'{"严格单调(f32)":>14}{"非正差(dbl)":>12}{"最小相邻差(dbl)":>18}{"严格单调(dbl)":>14}')
    print('-' * 110)
    for c, n32, d32, p32, _, n64, d64, p64, _ in rows:
        print(f'{c:>9.1f}{n32:>12d}{d32:>18.3e}{p32:>9.2f}{("是" if n32 == 0 else "否"):>14}'
              f'{n64:>12d}{d64:>18.3e}{("是" if n64 == 0 else "否"):>14}')
    print(f'{"filmic(引擎0)":>9}{neg0:>12d}{min0:>18.3e}{"":>9}{"否":>14}'
          f'{"—":>12}{"—":>18}{"—":>14}   ← 高光端硬平顶，首次零差 @ {flat_at} EV')
    print('\n  · 渐近行为（线性域，渐近值 = white_target = 1.0，不发散）：')
    print(f'{"contrast":>10}{"f(+12EV)":>14}{"f(+20EV)":>14}{"1 − f(+20EV)":>16}')
    for c, y12, y20 in asym:
        print(f'{c:>10.1f}{y12:>14.6f}{y20:>14.6f}{1.0 - y20:>16.3e}')

    # ── 附加：白纸 204 LSB 的严格对齐解 ─────────────────────────────────────
    print('\n【附】严格对齐 204.0 LSB 的自由度解（供阶段 3 预设标定用）')
    print('  ⚠ float32 下白纸 LSB 对 display_white_target **非单调**（film_power 是阶梯常数）'
          '⇒ 可能多解或无解')
    for c in (1.0, 1.2, 1.5):
        w, err, hits = solve_sigmoid_white_pct(c)
        if w is None:
            print(f'  sigmoid c={c}: [20,1600] 内无解')
        else:
            tag = '精确对齐' if abs(err) < 0.05 else f'最佳近似（残差 {err:+.3f} LSB）'
            print(f'  sigmoid c={c}: display_white_target = {w:.3f}  '
                  f'白纸 {_white_lsb(c, w):.3f} LSB  解区间数 {hits}  ⇒ {tag}')
    print(f'  filmic 白点4.0: white_ev = {wev:.3f}（残差 {werr:.4f} LSB）'
          f'  白纸 {y0b[i_white]:.3f} LSB')
    print('\n完成。')


if __name__ == '__main__':
    main()
