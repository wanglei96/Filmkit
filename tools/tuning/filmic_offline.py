#!/usr/bin/env python3
"""离线验证 darktable filmic rgb（spline v3）在本项目 RAW 链上的表现。

目的：确认"暗部能不能真的落到黑"——这是旧中位锚定做不到的事。

公式来源（逐条对照，非逐行翻译）：
- darktable `src/iop/filmicrgb.c` commit 92f6c28：
  * `_compute_output_power()`            → output_power = log(grey) / log(-black/DR)
  * `dt_iop_filmic_rgb_compute_spline()` v3 分支（slope/min_contrast/xmin/xmax/toe/shoulder）
  * POLY_4 toe/shoulder 的 5×5 线性系统（f(0)/f'(0)/f(Tl)/f'(Tl)/f''(Tl)）
  * SAFETY_MARGIN 0.01、black_point_target 0.01517634%、grey 18.45%
- PhotonCamera `RawDemosaicProcessor.kt`（Apache-2.0）的 uniform 组织方式
  + `DarktableFilmicToneAlgorithm.kt` 的求值结构（log encode → spline → pow(power)）
  + 颜色模式 `0.5*per-channel + 0.5*max-RGB-norm`

用法: python3 tools/tuning/filmic_offline.py <dng> [我们的成片.jpg] [PC成片.jpg]
"""
import sys

import numpy as np
from PIL import Image

# ── DNG 侧标定（取自本次 raw.meta：neutral / ccm / black / white）──────────────
BLK, WHT = 64.0, 1023.0
NEUTRAL = (0.5146, 1.0, 0.6684)
CCM = np.array([[1.624, -0.557, -0.067],
                [-0.140, 1.344, -0.204],
                [0.009, -0.572, 1.563]])
LUM = np.array([0.2126, 0.7152, 0.0722])

# ── filmic 常数（darktable 默认）────────────────────────────────────────────
GREY = 0.1845
SAFETY = 0.01
BLACK_TARGET_PCT = 0.01517634
NORM_MIN = 2.0 ** -16


def compute_output_power(black_ev, white_ev, grey=GREY):
    """darktable _compute_output_power：hardness 由 black/white/grey 派生（不是自由参数）"""
    return np.log(grey) / np.log(-black_ev / (white_ev - black_ev))


def compute_spline(black_ev, white_ev, contrast_param=1.0, latitude=0.01, grey=GREY):
    """darktable dt_iop_filmic_rgb_compute_spline 的 v3 分支"""
    black = min(black_ev, white_ev - 0.2)
    white = max(white_ev, black + 0.2)
    dr = white - black
    power = compute_output_power(black, white, grey)

    black_disp = (BLACK_TARGET_PCT / 100.0) ** (1.0 / power)
    white_disp = 1.0
    grey_disp = grey ** (1.0 / power)
    grey_log = abs(black) / dr

    # v3：slope 只依赖 contrast，latitude 是显示范围的百分比
    slope = contrast_param * dr / 8.0
    min_c = 1.0
    min_c = max(min_c, (white_disp - grey_disp) / (1.0 - grey_log))
    min_c = max(min_c, (grey_disp - black_disp) / grey_log)
    min_c += SAFETY
    contrast = slope / (power * grey_disp ** (power - 1.0))
    contrast = float(np.clip(contrast, min_c, 100.0))

    linear_intercept = grey_disp - contrast * grey_log
    xmin = (black_disp + SAFETY * (white_disp - black_disp) - linear_intercept) / contrast
    xmax = (white_disp - SAFETY * (white_disp - black_disp) - linear_intercept) / contrast
    toe_log = (1.0 - latitude) * grey_log + latitude * xmin
    sh_log = (1.0 - latitude) * grey_log + latitude * xmax
    toe_d = toe_log * contrast + linear_intercept
    sh_d = sh_log * contrast + linear_intercept

    # POLY_4 toe：f(0)=black_disp, f'(0)=0, f(Tl)=toe_d, f'(Tl)=contrast, f''(Tl)=0
    Tl = toe_log
    a_toe = np.array([[0, 0, 0, 0, 1], [0, 0, 0, 1, 0],
                      [Tl**4, Tl**3, Tl**2, Tl, 1],
                      [4 * Tl**3, 3 * Tl**2, 2 * Tl, 1, 0],
                      [12 * Tl**2, 6 * Tl, 2, 0, 0]], float)
    m_toe = np.linalg.solve(a_toe, np.array([black_disp, 0.0, toe_d, contrast, 0.0]))
    # POLY_4 shoulder：f(1)=white_disp, f'(1)=0, f(Sl)=sh_d, f'(Sl)=contrast, f''(Sl)=0
    Sl = sh_log
    a_sh = np.array([[1, 1, 1, 1, 1], [4, 3, 2, 1, 0],
                     [Sl**4, Sl**3, Sl**2, Sl, 1],
                     [4 * Sl**3, 3 * Sl**2, 2 * Sl, 1, 0],
                     [12 * Sl**2, 6 * Sl, 2, 0, 0]], float)
    m_sh = np.linalg.solve(a_sh, np.array([white_disp, 0.0, sh_d, contrast, 0.0]))

    return dict(black=black, white=white, dr=dr, power=power, contrast=contrast,
                grey_log=grey_log, toe_log=toe_log, sh_log=sh_log,
                m_toe=m_toe, m_sh=m_sh,
                m_mid=np.array([0.0, 0.0, 0.0, contrast, linear_intercept]),
                input_min=max(2.0 ** black * grey, 1e-8),
                input_max=max(2.0 ** white * grey, 1e-8),
                black_disp=black_disp, grey_disp=grey_disp)


def _eval(x, sp):
    """log encode → spline → pow(power)，返回线性显示域（darktable 输出即此域）"""
    x = np.maximum(x, NORM_MIN)
    enc = np.clip((np.log2(x / GREY) - sp['black']) / max(sp['dr'], 0.2), 0.0, 1.0)
    toe = np.polyval(sp['m_toe'], enc)
    sh = np.polyval(sp['m_sh'], enc)
    mid = sp['m_mid'][3] * enc + sp['m_mid'][4]
    curved = np.where(enc < sp['toe_log'], toe, np.where(enc > sp['sh_log'], sh, mid))
    return np.maximum(curved, 0.0) ** sp['power']


def apply_filmic(rgb, sp):
    """PC 的颜色模式：0.5 × 逐通道 + 0.5 × max-RGB 归一（darktable preserve_color 的折中）"""
    naive = _eval(np.maximum(rgb, NORM_MIN), sp)
    pos = np.maximum(rgb, 0.0)
    mx = pos.max(-1)
    ratios = pos / np.maximum(mx, sp['input_min'])[..., None]
    tone_norm = np.clip(mx, sp['input_min'], sp['input_max'])
    normed = ratios * _eval(tone_norm, sp)[..., None]
    return 0.5 * naive + 0.5 * normed


def rolloff(c, knee=0.80):
    """引擎 pass3 的 highlightRolloff（线性域，含"过曝趋白"）。

    ⚠️ 必须保留 `l <= knee → 原值` 的短路：漏掉它等于给所有像素乘 knee/l，
    暗部会被顶亮到 0.8（2026-09-24 曾因此产出错误统计；lessons.md 记过同型坑）。
    """
    l = c.max(-1)
    u = np.maximum(l - knee, 0.0) / (1.0 - knee)
    lc = np.where(l > knee, knee + (1.0 - knee) * (1.0 - np.exp(-u)), l)
    sw = np.clip((l - knee) / (1.6 - knee), 0.0, 1.0)
    sw = sw * sw * (3 - 2 * sw)
    scale = (lc / np.maximum(l, 1e-9))[..., None]
    return c * scale * (1 - sw)[..., None] + lc[..., None] * sw[..., None]


def encode(lin):
    """引擎 OETF：pow(1/2.2) → 8bit 显示域"""
    return np.clip(lin, 0, 1) ** (1 / 2.2) * 255.0


def load_raw(dng):
    """DNG → 引擎 pass2 输入（线性 sRGB）。2×2 合并 + WB + CCM"""
    import rawpy
    with rawpy.imread(dng) as raw:
        a = raw.raw_image_visible.astype(np.float64)
    h, w = a.shape[0] // 2 * 2, a.shape[1] // 2 * 2
    a = (a[:h, :w] - BLK) / (WHT - BLK)
    R = a[0::2, 1::2]                       # GRBG
    G = 0.5 * (a[0::2, 0::2] + a[1::2, 1::2])
    B = a[1::2, 0::2]
    lin = np.stack([R, G, B], -1)
    gain = np.array([NEUTRAL[1] / NEUTRAL[0], 1.0, NEUTRAL[1] / NEUTRAL[2]])
    return (lin * gain) @ CCM.T


def stats(luma8):
    q = np.percentile(luma8, [1, 5, 25, 50, 75, 95])
    return (f"mean {luma8.mean():6.1f}  P1 {q[0]:5.1f}  P5 {q[1]:5.1f}  P25 {q[2]:5.1f}  "
            f"P50 {q[3]:5.1f}  P75 {q[4]:5.1f}  P95 {q[5]:5.1f}  "
            f"死黑(<10) {(luma8 < 10).mean() * 100:5.1f}%")


def auto_ev(out3, lo_q, hi_q):
    """自动 EV：用分位数定 black/white relative exposure（手机上没有取色器，这是替代方案）"""
    m = out3.max(-1)
    white = float(np.percentile(m, hi_q))
    black = float(np.percentile(m, lo_q))
    ev_w = float(np.clip(np.log2(max(white, 1e-6) / GREY), 1.0, 16.0))
    ev_b = float(np.clip(np.log2(max(black, 1e-6) / GREY), -16.0, -1.0))
    return ev_b, ev_w


def main():
    dng = sys.argv[1]
    jpg_ours = sys.argv[2] if len(sys.argv) > 2 else None
    jpg_pc = sys.argv[3] if len(sys.argv) > 3 else None

    out3 = load_raw(dng)
    m = out3.max(-1)
    print(f"\n=== {dng.split('/')[-1]}  渲染输入（pass2 前）线性 max 通道："
          f"P0.1 {np.percentile(m, 0.1):.5f}  P50 {np.percentile(m, 50):.5f}  P99.9 {np.percentile(m, 99.9):.5f}")

    print("\n── 曲线采样（线性入 → 线性显示 → 8bit）──")
    probes = [0.002, 0.005, 0.01, 0.028, 0.05, 0.08, 0.18, 0.5, 1.0]

    cases = []
    cases.append(("PC 参数 (-7.65/+4.56)", -7.65, 4.56))
    for lo_q, hi_q in [(0.1, 99.9), (0.5, 99.5), (1.0, 99.0)]:
        ev_b, ev_w = auto_ev(out3, lo_q, hi_q)
        cases.append((f"自动 EV P{lo_q}/P{hi_q} ({ev_b:.2f}/{ev_w:.2f})", ev_b, ev_w))

    hdr = f"{'方案':34s} " + " ".join(f"{p:>7.3f}" for p in probes)
    print(hdr)
    for name, b, w in cases:
        sp = compute_spline(b, w)
        row = " ".join(f"{encode(_eval(np.array(p), sp)):7.1f}" for p in probes)
        print(f"{name:34s} {row}   [power {sp['power']:.3f} contrast {sp['contrast']:.3f} "
              f"grey_log {sp['grey_log']:.3f}]")

    print("\n── 全图结果（8bit 显示域；上=直出，下=再经引擎 pass3 的 highlightRolloff）──")
    for name, b, w in cases:
        sp = compute_spline(b, w)
        fo = apply_filmic(out3, sp)
        print(f"{name:34s} {stats(encode(fo) @ LUM)}")
        print(f"{'  └ + highlightRolloff':34s} {stats(encode(rolloff(np.clip(fo, 0, 1))) @ LUM)}")

    for label, path in (("我们成片", jpg_ours), ("PC 成片", jpg_pc)):
        if not path:
            continue
        a = np.asarray(Image.open(path).convert("RGB")).astype(np.float64) / 255.0
        k = max(1, a.shape[1] // 1200)
        a = a[::k, ::k]
        print(f"{label + '（实拍参照）':34s} {stats((a @ LUM) * 255)}")


if __name__ == "__main__":
    main()
