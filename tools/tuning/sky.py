#!/usr/bin/env python3
"""天空区域色度对比:filmkit 成片 vs PhotonCamera 同机位成片。

用法: python3 tools/tuning/sky.py a.jpg b.jpg ...
口径: 画面上部 top 比例内、Lab 色相落在蓝带、亮度达标且有色的像素;
打印其 C*/L/h 中位与按 L 分箱的 C* 中位(判断"越亮越浓"还是整体偏浓)。
跨图比 C* 前先看 L 是否对齐,未对齐要看分箱行。
"""

import sys

import numpy as np
from PIL import Image

M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722],
              [0.0193, 0.1192, 0.9505]])
WP = np.array([0.95047, 1.0, 1.08883])


def lab(a):
    c = a / 255.0
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    xyz = (c @ M.T) / WP
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    L = 116 * f[..., 1] - 16
    A = 500 * (f[..., 0] - f[..., 1])
    B = 200 * (f[..., 1] - f[..., 2])
    return L, A, B


def report(path, top=0.45, hue=(200, 310), lmin=45):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    a = np.asarray(im.crop((0, 0, W, int(H * top)))).astype(np.float64)
    L, A, B = lab(a)
    C = np.hypot(A, B)
    h = np.degrees(np.arctan2(B, A)) % 360
    m = (h > hue[0]) & (h < hue[1]) & (L > lmin)
    name = path.split("/")[-1]
    n = int(m.sum())
    print(f"\n{name}  [{W}x{H}]  天空像素 {n} ({100.0 * n / m.size:.1f}%)")
    if n < 1000:
        print("  天空像素太少,阈值需调")
        return
    print(f"  C* 中位 {np.median(C[m]):6.2f}  均值 {C[m].mean():6.2f}")
    print(f"  L  中位 {np.median(L[m]):6.1f}  h 中位 {np.median(h[m]):6.1f}°")
    print("  按 L 分箱的 C* 中位(样本数):")
    for lo in range(40, 100, 10):
        b = m & (L >= lo) & (L < lo + 10)
        if b.sum() > 500:
            print(f"    L {lo:3d}-{lo + 10:3d}: C* {np.median(C[b]):6.2f}  n={int(b.sum()):7d}")


for p in sys.argv[1:]:
    report(p)
