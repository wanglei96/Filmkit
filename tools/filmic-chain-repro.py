#!/usr/bin/env python3
"""filmic 链离线复现：验证「RCD 输出 → CCM → filmic → sRGB」是否复现成片色度。

用途（2026-10-07 淡紫线排查）：RCD 输出在紫线处 B/G=0.89（偏绿），成片 B/G=2.76（强紫）。
CCM 只会把 B/G 压到 0.74，所以嫌疑落在 filmic 的色度模式
（0.5×逐通道 + 0.5×max-RGB 归一）—— 暗部曲线斜率差异会放大通道比值。
本脚本按 FrameRenderer.cpp 的 kFsFilm 逐行复现该段，对比成片实测。
"""
import numpy as np

kGrey = 0.1845
kSafety = 0.01
kBlackTargetPct = 0.01517634
kNormMin = 1.52587890625e-5

def compute_spline(black_ev, white_ev, contrast_param=1.0, latitude=0.01):
    black = min(black_ev, white_ev - 0.2); white = max(white_ev, black + 0.2)
    dr = white - black
    power = np.log(kGrey) / np.log(-black / dr)
    black_disp = (kBlackTargetPct / 100.0) ** (1.0 / power)
    white_disp = 1.0
    grey_disp = kGrey ** (1.0 / power)
    grey_log = abs(black) / dr
    slope = contrast_param * dr / 8.0
    min_c = 1.0
    min_c = max(min_c, (white_disp - grey_disp) / (1.0 - grey_log))
    min_c = max(min_c, (grey_disp - black_disp) / grey_log)
    min_c += kSafety
    contrast = slope / (power * grey_disp ** (power - 1.0))
    contrast = min(max(contrast, min_c), 100.0)
    li = grey_disp - contrast * grey_log
    xmin = (black_disp + kSafety * (white_disp - black_disp) - li) / contrast
    xmax = (white_disp - kSafety * (white_disp - black_disp) - li) / contrast
    toe_log = (1.0 - latitude) * grey_log + latitude * xmin
    sh_log = (1.0 - latitude) * grey_log + latitude * xmax
    toe_d = toe_log * contrast + li
    sh_d = sh_log * contrast + li
    def solve5(a, b):
        try: return np.linalg.solve(np.array(a, float), np.array(b, float))
        except np.linalg.LinAlgError: return None
    t = toe_log
    a = [[0,0,0,0,1],[0,0,0,1,0],[t**4,t**3,t*t,t,1],[4*t**3,3*t*t,2*t,1,0],[12*t*t,6*t,2,0,0]]
    x = solve5(a, [black_disp,0.0,toe_d,contrast,0.0])
    m5t,m4t,m3t,m2t,m1t = x
    s = sh_log
    a = [[1,1,1,1,1],[4,3,2,1,0],[s**4,s**3,s*s,s,1],[4*s**3,3*s*s,2*s,1,0],[12*s*s,6*s,2,0,0]]
    x = solve5(a, [white_disp,0.0,sh_d,contrast,0.0])
    m5s,m4s,m3s,m2s,m1s = x
    return dict(power=power, black=black, white=white, dr=dr, lat_min=toe_log, lat_max=sh_log,
                m1=(m1t,m1s,toe_d-contrast*toe_log), m2=(m2t,m2s,contrast), m3=(m3t,m3s,0.0),
                m4=(m4t,m4s,0.0), m5=(m5t,m5s,0.0),
                input_min=max(2.0**black * kGrey, 1e-8), black_disp=black_disp)

def filmic_scalar(sp, v, lo=0.0):
    s = max(v, kNormMin)
    e = min(max((np.log2(s / kGrey) - sp['black']) / max(sp['dr'], 0.2), 0.0), 1.0)
    if e < sp['lat_min']:
        c = sp['m1'][0] + e*(sp['m2'][0] + e*(sp['m3'][0] + e*(sp['m4'][0] + e*sp['m5'][0])))
    elif e > sp['lat_max']:
        c = sp['m1'][1] + e*(sp['m2'][1] + e*(sp['m3'][1] + e*(sp['m4'][1] + e*sp['m5'][1])))
    else:
        c = sp['m1'][2] + e*sp['m2'][2]
    c = min(max(c, lo), 1.0)
    return max(c, 0.0) ** sp['power']

def filmic_tone(sp, c):
    p = np.maximum(np.asarray(c, float), 0.0)
    naive = np.array([filmic_scalar(sp, p[0]), filmic_scalar(sp, p[1]), filmic_scalar(sp, p[2])])
    mx = p.max()
    ratio_norm = max(mx, sp['input_min'])
    tone_norm = min(max(mx, sp['input_min']), 1.0)
    normed = (p / ratio_norm) * filmic_scalar(sp, tone_norm, sp['black_disp'])
    return 0.5*naive + 0.5*normed

def srgb_encode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, 12.92*c, 1.055*np.power(c, 1/2.4) - 0.055)
