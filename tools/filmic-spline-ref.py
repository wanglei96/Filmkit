#!/usr/bin/env python3
"""`FrameRenderer::computeFilmicSpline` 的 Python 逐行对照实现（离线链 CPU 侧系数）。

为什么单独一个文件：`tools/engine-shader-offline.py` 的 FILM 段需要与真机**同一口径**
的 filmic spline 系数（15×2 分段多项式 + 中间线性段），而那份系数在真机上由 C++
`computeFilmicSpline`（`core/engine/src/render/FrameRenderer.cpp:13044`）算出。本文件
是它的**逐行照搬**（含 `solve5`），供离线链消费，也供人工对拍（`python3 tools/filmic-spline-ref.py`）。

公式出处链：darktable `src/iop/filmicrgb.c`（commit 92f6c28）的
`dt_iop_filmic_rgb_compute_spline` v3 分支 + `_compute_output_power`。
**本文件不是新算法**：每个表达式都能在 C++ 原文里找到同名同序的一行（对照见
`tools/film-offline-REPORT.md` §3）。

⚠ 只做数学，不碰 GL：shader 侧的三段求值（`filmicSplineR/G/B`）由 GPU 执行引擎自己的
GLSL 字符串，本文件不重复实现。
"""
import math
import struct

# ── computeFilmicSpline 内的 constexpr（FrameRenderer.cpp:13061-13063）────────
K_GREY = 0.1845                # darktable grey_source（18.45% 中灰）
K_SAFETY = 0.01                # darktable SAFETY_MARGIN
K_BLACK_TARGET_PCT = 0.01517634  # black_point_target 默认（%）


def f32(x):
    """C++ `static_cast<float>` / `0.01f` 字面量的位级等价（写入 uniform 前的截断）。"""
    return struct.unpack('<f', struct.pack('<f', x))[0]


def solve5(a, b):
    """逐行照搬 `FrameRenderer.cpp:12997 solve5`：列主元高斯消元 + 回代。

    `a` = 5×5 行主序（会被就地改写），`b` = 右端项（就地改写）。
    返回解 `x`（按 x⁴..x⁰ 排列）或 None（奇异，C++ 侧返回 false 时**不写系数**）。
    """
    a = [row[:] for row in a]
    b = list(b)
    x = [0.0] * 5
    for col in range(5):
        piv = col
        for r in range(col + 1, 5):
            if abs(a[r][col]) > abs(a[piv][col]):
                piv = r
        if abs(a[piv][col]) < 1e-12:
            return None
        if piv != col:
            for c in range(5):
                t = a[piv][c]
                a[piv][c] = a[col][c]
                a[col][c] = t
            tb = b[piv]
            b[piv] = b[col]
            b[col] = tb
        for r in range(col + 1, 5):
            f = a[r][col] / a[col][col]
            for c in range(col, 5):
                a[r][c] -= f * a[col][c]
            b[r] -= f * b[col]
    for r in range(4, -1, -1):
        s = b[r]
        for c in range(r + 1, 5):
            s -= a[r][c] * x[c]
        x[r] = s / a[r][r]
    return x


def compute_filmic_spline(black_ev, white_ev, contrast_param_in, lat_sh, lat_toe):
    """照搬 `computeFilmicSpline`（FrameRenderer.cpp:13044-13173）。

    lat_sh / lat_toe：长度 3 的逐通道 latitude（R/G/B）。
    返回 dict；`m1..m5` 为 `[段][通道]`（段 0=toe 1=shoulder 2=线性），与 C++ 的
    `FilmicSpline::m1[ch][seg]` 转置存放（这里按段分组，便于 shader uniform 组装）。
    """
    k_contrast_param = float(contrast_param_in)

    # 端点与派生量（C++:13065-13074）
    black = min(float(black_ev), float(white_ev) - 0.2)
    white = max(float(white_ev), black + 0.2)
    dr = white - black
    power = math.log(K_GREY) / math.log(-black / dr)
    black_disp = (K_BLACK_TARGET_PCT / 100.0) ** (1.0 / power)
    white_disp = 1.0
    grey_disp = K_GREY ** (1.0 / power)
    grey_log = abs(black) / dr

    # 对比度下限与线性段斜率（C++:13076-13083）
    slope = k_contrast_param * dr / 8.0
    min_c = 1.0
    min_c = max(min_c, (white_disp - grey_disp) / (1.0 - grey_log))
    min_c = max(min_c, (grey_disp - black_disp) / grey_log)
    min_c += K_SAFETY
    contrast = slope / (power * grey_disp ** (power - 1.0))
    contrast = min(max(contrast, min_c), 100.0)

    li = grey_disp - contrast * grey_log
    xmin = (black_disp + K_SAFETY * (white_disp - black_disp) - li) / contrast
    xmax = (white_disp - K_SAFETY * (white_disp - black_disp) - li) / contrast

    sp = {'m1': [[0.0] * 3 for _ in range(3)], 'm2': [[0.0] * 3 for _ in range(3)],
          'm3': [[0.0] * 3 for _ in range(3)], 'm4': [[0.0] * 3 for _ in range(3)],
          'm5': [[0.0] * 3 for _ in range(3)],
          'lat_min': [0.0] * 3, 'lat_max': [0.0] * 3,
          'lat_sh': [0.0] * 3, 'lat_toe': [0.0] * 3,
          # 仅自检用：solve5 的 double 解（未经 static_cast<float> 截断），
          # 用于把"求解正确"与"float32 存储精度"两件事分开证明。
          '_raw_toe': [None] * 3, '_raw_sh': [None] * 3,
          # 仅自检用：double 精度下的端点/斜率（未经 float32 截断），
          # 用于在同一精度下验证 solve5 的解确实满足五条约束（~1e-12），
          # 把"求解正确"与"float32 存储精度（~2e-7）"两件事分开证明。
          '_dbg': {'toe_log': [0.0] * 3, 'sh_log': [0.0] * 3,
                   'contrast': contrast, 'li': li}}

    for ch in range(3):
        # per-channel 防呆（C++:13100-13106）：非有限值回退 darktable 默认 0.01，
        # 再 clamp 到 [0,1]（顺序与 C++ 一致：先 isfinite 再 clamp）。
        raw_toe = lat_toe[ch] if math.isfinite(lat_toe[ch]) else f32(0.01)
        raw_sh = lat_sh[ch] if math.isfinite(lat_sh[ch]) else f32(0.01)
        k_lat_toe = float(min(max(f32(raw_toe), f32(0.0)), f32(1.0)))
        k_lat_sh = float(min(max(f32(raw_sh), f32(0.0)), f32(1.0)))
        toe_log = (1.0 - k_lat_toe) * grey_log + k_lat_toe * xmin
        sh_log = (1.0 - k_lat_sh) * grey_log + k_lat_sh * xmax
        toe_d = toe_log * contrast + li
        sh_d = sh_log * contrast + li

        # POLY_4 toe（C++:13107-13123）：f(0)=black_disp / f'(0)=0 / f(Tl)=toe_d
        # / f'(Tl)=contrast / f''(Tl)=0
        t = toe_log
        a = [[0, 0, 0, 0, 1],
             [0, 0, 0, 1, 0],
             [t ** 4, t ** 3, t * t, t, 1],
             [4 * t ** 3, 3 * t * t, 2 * t, 1, 0],
             [12 * t * t, 6 * t, 2, 0, 0]]
        x = solve5(a, [black_disp, 0.0, toe_d, contrast, 0.0])
        if x is not None:                      # 解按 x⁴..x⁰ 排列 → m5..m1
            sp['_raw_toe'][ch] = list(reversed(x))     # 未截断的 double（仅自检用）
            sp['m5'][0][ch] = f32(x[0])
            sp['m4'][0][ch] = f32(x[1])
            sp['m3'][0][ch] = f32(x[2])
            sp['m2'][0][ch] = f32(x[3])
            sp['m1'][0][ch] = f32(x[4])

        # POLY_4 shoulder（C++:13125-13140）：f(1)=white_disp / f'(1)=0 / f'(Sl)=contrast
        s = sh_log
        a = [[1, 1, 1, 1, 1],
             [4, 3, 2, 1, 0],
             [s ** 4, s ** 3, s * s, s, 1],
             [4 * s ** 3, 3 * s * s, 2 * s, 1, 0],
             [12 * s * s, 6 * s, 2, 0, 0]]
        x = solve5(a, [white_disp, 0.0, sh_d, contrast, 0.0])
        if x is not None:
            sp['_raw_sh'][ch] = list(reversed(x))
            sp['m5'][1][ch] = f32(x[0])
            sp['m4'][1][ch] = f32(x[1])
            sp['m3'][1][ch] = f32(x[2])
            sp['m2'][1][ch] = f32(x[3])
            sp['m1'][1][ch] = f32(x[4])

        # 中间线性段（C++:13142-13147）：y = (toe_d − contrast·toe_log) + contrast·x
        sp['m1'][2][ch] = f32(toe_d - contrast * toe_log)
        sp['m2'][2][ch] = f32(contrast)
        sp['m3'][2][ch] = f32(0.0)
        sp['m4'][2][ch] = f32(0.0)
        sp['m5'][2][ch] = f32(0.0)

        sp['_dbg']['toe_log'][ch] = toe_log
        sp['_dbg']['sh_log'][ch] = sh_log
        sp['lat_min'][ch] = f32(toe_log)
        sp['lat_max'][ch] = f32(sh_log)
        sp['lat_toe'][ch] = f32(k_lat_toe)
        sp['lat_sh'][ch] = f32(k_lat_sh)

    # 派生标量（C++:13151-13161）
    sp['black_ev'] = f32(black)
    sp['white_ev'] = f32(white)
    sp['dynamic_range'] = f32(dr)
    sp['input_min'] = f32(max(2.0 ** black * K_GREY, 1e-8))
    sp['input_max'] = f32(max(2.0 ** white * K_GREY, sp['input_min'] + 1e-8))
    sp['output_power'] = f32(power)
    sp['black_disp'] = f32(black_disp)
    sp['grey_log'] = f32(grey_log)
    sp['contrast'] = f32(contrast)
    sp['contrast_param'] = contrast_param_in
    sp['xmin'] = xmin      # 诊断用（C++ 不导出，报告对照用）
    sp['xmax'] = xmax
    sp['li'] = li
    return sp


def poly_eval(coeffs, e):
    """Horner：y = m1 + e·(m2 + e·(m3 + e·(m4 + e·m5)))（shader `filmicSpline*` 同式）。"""
    m1, m2, m3, m4, m5 = coeffs
    return m1 + e * (m2 + e * (m3 + e * (m4 + e * m5)))


def spline_at(sp, e, ch=1):
    """复现 shader 的三段选择（kFsFilm 的 `filmicSplineG`）。ch: 0=R 1=G 2=B。"""
    if e < sp['lat_min'][ch]:
        return poly_eval([sp[k][0][ch] for k in ('m1', 'm2', 'm3', 'm4', 'm5')], e)
    if e > sp['lat_max'][ch]:
        return poly_eval([sp[k][1][ch] for k in ('m1', 'm2', 'm3', 'm4', 'm5')], e)
    return sp['m1'][2][ch] + e * sp['m2'][2][ch]


def _check():
    """自检：五条约束残差（与 C++ 源码里写的约束同式，独立于 solve5 的实现）。"""
    cases = [
        # (black_ev, white_ev, contrast_param, lat_sh, lat_toe, 说明)
        (-7.65, 4.56, 1.0, [0.01] * 3, [0.01] * 3, 'FilmParams[37]/[38] 默认'),
        (-6.0, 3.0, 1.0, [0.01] * 3, [0.01] * 3, '收窄 dr'),
        (-8.0, 4.0, 1.2, [0.10, 0.05, 0.30], [0.20, 0.01, 0.05], 'contrast + per-channel lat'),
        (-5.0, 1.0, 1.5, [0.01] * 3, [0.01] * 3, '窄 dr (6EV) + 高 contrast'),
    ]
    worst = 0.0
    worst_raw = 0.0
    for bl, wh, cp, lsh, ltoe, note in cases:
        sp = compute_filmic_spline(bl, wh, cp, lsh, ltoe)
        black = min(bl, wh - 0.2)
        white = max(wh, black + 0.2)
        dr = white - black
        power = math.log(K_GREY) / math.log(-black / dr)
        black_disp = (K_BLACK_TARGET_PCT / 100.0) ** (1.0 / power)
        grey_disp = K_GREY ** (1.0 / power)
        grey_log = abs(black) / dr
        li = grey_disp - sp['contrast'] * grey_log
        res = []
        raw = []
        dbg = sp['_dbg']
        for ch in range(3):
            tl, sl = sp['lat_min'][ch], sp['lat_max'][ch]
            tl_d, sl_d = dbg['toe_log'][ch], dbg['sh_log'][ch]   # double 端点（自检用）
            toe = [sp[k][0][ch] for k in ('m1', 'm2', 'm3', 'm4', 'm5')]
            sh = [sp[k][1][ch] for k in ('m1', 'm2', 'm3', 'm4', 'm5')]
            # 同五条约束，但用 solve5 的 double 解（未经 static_cast<float>）
            rtoe, rsh = sp['_raw_toe'][ch], sp['_raw_sh'][ch]
            cons = lambda p, T, d, c, is_toe: [    # noqa: E731  五条约束的残差
                # f(0)=black_disp（toe）/ f(1)=white_disp（shoulder）
                poly_eval(p, 0.0 if is_toe else 1.0) - (black_disp if is_toe else 1.0),
                # f'(0)=0（toe）/ f'(1)=0（shoulder）
                (p[1] if is_toe else p[1] + 2 * p[2] + 3 * p[3] + 4 * p[4]),
                poly_eval(p, T) - d,               # f(T)=toe_d / sh_d
                p[1] + T * (2 * p[2] + T * (3 * p[3] + T * 4 * p[4])) - c,  # f'(T)=contrast
                2 * p[2] + T * (6 * p[3] + T * 12 * p[4]),                  # f''(T)=0
            ]
            toe_d = tl * sp['contrast'] + li
            sh_d = sl * sp['contrast'] + li
            res += (cons(toe, tl, toe_d, sp['contrast'], True)
                    + cons(sh, sl, sh_d, sp['contrast'], False))
            # raw：double 系数 + **double 端点/斜率** ⇒ 应在 1e-12 量级（证明 solve5 解正确）
            raw += (cons(rtoe, tl_d, tl_d * dbg['contrast'] + dbg['li'], dbg['contrast'], True)
                    + cons(rsh, sl_d, sl_d * dbg['contrast'] + dbg['li'], dbg['contrast'], False))
        w = max(abs(r) for r in res)
        wr = max(abs(r) for r in raw)
        worst = max(worst, w)
        worst_raw = max(worst_raw, wr)
        # float32 存储后残差量级 = 系数幅度 × 2^-24 × 导数放大(≤4T) —— C++ 同样
        # static_cast<float> 存系数，故真机系数也有同量级偏差，这是**位级一致**而非实现偏差。
        assert w < 1e-4, f'{note}: float32 系数约束残差 {w:.3e} 超限'
        assert wr < 1e-10, f'{note}: double 系数约束残差 {wr:.3e} 超限'
        # 中间线性段与 toe 段在 T 处相接（C++ 的 li 定义）
        for ch in range(3):
            tl = sp['lat_min'][ch]
            lin = sp['m1'][2][ch] + tl * sp['m2'][2][ch]
            # 接续残差与上面的约束残差同源（float32 存储 + contrast 量级放大）
            assert abs(lin - poly_eval([sp[k][0][ch] for k in ('m1', 'm2', 'm3', 'm4', 'm5')], tl)) < 1e-4
        print(f'  {note:28s} black={sp["black_ev"]:+.2f} white={sp["white_ev"]:+.2f} '
              f'dr={sp["dynamic_range"]:.2f} power={sp["output_power"]:.6f} '
              f'contrast={sp["contrast"]:.6f} black_disp={sp["black_disp"]:.6e} '
              f'残差 double={wr:.1e} float32={w:.1e}')
    print(f'约束残差上界：double={worst_raw:.2e}（= 求解正确）'
          f' · float32 存储后={worst:.2e}（= C++ static_cast<float> 的必然量级）')

    # 逐通道默认 ⇒ 三通道系数必须**逐位相同**（A/B 通路纪律）
    sp = compute_filmic_spline(-7.65, 4.56, 1.0, [0.01] * 3, [0.01] * 3)
    for seg in range(3):
        for k in ('m1', 'm2', 'm3', 'm4', 'm5'):
            assert sp[k][seg][0] == sp[k][seg][1] == sp[k][seg][2], f'{k}[{seg}] 三通道不一致'
    print('默认 latitude 下三通道系数逐位相同 ✓（与 C++ 的 A/B 通路纪律一致）')


if __name__ == '__main__':
    print('computeFilmicSpline Python 对照实现 · 自检')
    _check()
