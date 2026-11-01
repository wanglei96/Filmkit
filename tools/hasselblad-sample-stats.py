#!/usr/bin/env python3
"""哈苏 HNCS 官方样片的色彩统计（灰轴 / 亮度 / 色相 / 饱和），用于「哈苏自然」预设标定。

## 为什么要有这个脚本（可复现性）

`docs/reports/hasselblad-natural-calibration-20261101.md` 里的每个数字都必须能被重跑出来。
样片不进仓库（13 张 1600px ≈ 5MB、版权归哈苏），所以脚本只吃**本地样片目录**，不负责下载。

样片来源：`https://www.hasselblad.com/learn/sample-images/x-system/`（84 张 X2D 机内直出 JPEG）。
取图方式见报告 §2：原图 20MB 级，下载后 `thumbnail(1600)` 再统计（统计量是大区域中位，缩放不影响结论）。

用法：
    python3 tools/hasselblad-sample-stats.py /tmp/hb_std
    python3 tools/hasselblad-sample-stats.py /tmp/hb_std --per-image   # 逐张灰轴
"""
import argparse
import glob
import os
import sys

import numpy as np
from PIL import Image

# ITU-R BT.709 线性 sRGB → XYZ（D65）
M_RGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
])
WHITE = np.array([0.95047, 1.0, 1.08883])
NEUTRAL_C = 8.0     # 中性像素判据：C* < 8
SAT_C = 12.0        # "有色彩"像素判据：C* ≥ 12


def srgb_decode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lab(path, size=1000):
    im = Image.open(path).convert("RGB")
    im.thumbnail((size, size))
    rgb = srgb_decode(np.asarray(im, dtype=np.float64) / 255.0)
    xyz = rgb @ M_RGB2XYZ.T / WHITE
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16.0 / 116.0)
    return (116.0 * f[..., 1] - 16.0,
            500.0 * (f[..., 0] - f[..., 1]),
            200.0 * (f[..., 1] - f[..., 2]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sample_dir")
    ap.add_argument("--per-image", action="store_true")
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.sample_dir, "*.jpg")))
    if not files:
        print(f"目录里没有 jpg：{args.sample_dir}", file=sys.stderr)
        return 1

    buckets = [(0, 10), (10, 20), (20, 30), (30, 40), (40, 50),
               (50, 60), (60, 70), (70, 80), (80, 90), (90, 100)]
    acc = {k: [] for k in buckets}
    hue = {k: [] for k in range(0, 360, 30)}
    c_med, l_med, dark, bright, gray_a, gray_b = [], [], [], [], [], []

    for p in files:
        L, A, B = lab(p)
        C = np.hypot(A, B)
        H = (np.degrees(np.arctan2(B, A)) + 360.0) % 360.0
        m = (C < NEUTRAL_C) & (L > 3) & (L < 97)
        for lo, hi in buckets:
            k = m & (L >= lo) & (L < hi)
            if k.sum() > 500:
                acc[(lo, hi)].append((np.median(A[k]), np.median(B[k]), k.sum()))
        c_med.append(np.median(C))
        l_med.append(np.median(L))
        dark.append((L < 5).mean() * 100)
        bright.append((L > 95).mean() * 100)
        if m.sum() > 3000:
            gray_a.append(np.median(A[m]))
            gray_b.append(np.median(B[m]))
        m2 = C >= SAT_C
        for lo in range(0, 360, 30):
            k = m2 & (H >= lo) & (H < lo + 30)
            if k.sum() > 800:
                hue[lo].append((np.median(H[k]), np.median(C[k]), k.sum()))
        if args.per_image:
            print(f"{os.path.basename(p)[:36]:36s} n={int(m.sum()):7d} "
                  f"a*{np.median(A[m]):+5.2f} b*{np.median(B[m]):+5.2f} "
                  f"L50 {np.median(L):5.1f} C50 {np.median(C):5.1f}")

    print(f"\n样本 {len(files)} 张 · C* p50 中位 {np.median(c_med):.1f} · "
          f"L* p50 中位 {np.median(l_med):.1f}")
    print(f"黑（L<5）{np.mean(dark):.2f}% · 白（L>95）{np.mean(bright):.2f}%")
    if gray_a:
        print(f"灰轴（逐张中位的跨图散布）：a* {np.min(gray_a):+.2f} ~ {np.max(gray_a):+.2f}，"
              f"b* {np.min(gray_b):+.2f} ~ {np.max(gray_b):+.2f}")

    print("\n灰轴（按亮度桶、像素数加权）")
    for k, v in acc.items():
        if v:
            w = np.array([x[2] for x in v], dtype=np.float64)
            a = np.array([x[0] for x in v])
            b = np.array([x[1] for x in v])
            print(f"  L {k[0]:3d}-{k[1]:3d}: a* {np.average(a, weights=w):+5.2f}  "
                  f"b* {np.average(b, weights=w):+5.2f}  n={int(w.sum())}")

    print("\n色相桶（C*≥12）")
    for lo, v in hue.items():
        if v:
            w = np.array([x[2] for x in v], dtype=np.float64)
            h = np.array([x[0] for x in v])
            c = np.array([x[1] for x in v])
            print(f"  h{lo:3d}-{lo+30:3d}: h {np.average(h, weights=w):6.1f}°  "
                  f"C* {np.average(c, weights=w):5.1f}  n={int(w.sum())}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
