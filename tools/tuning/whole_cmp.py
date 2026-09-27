#!/usr/bin/env python3
"""成片整体对比:filmkit vs PhotonCamera 同机位成片(全图口径,不只看天空)。

用法: python3 tools/tuning/whole_cmp.py <我们.jpg> <PC.jpg> [场景名]
输出:亮度分位/过曝死黑/色度/色相带偏移/分亮度段色度剖面。
口径:C* = CIELAB(与亮度解耦);跨图比色度先看亮度是否对齐,再看分箱。
"""

import os
import sys

import numpy as np
from PIL import Image

LMN = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722],
                [0.0193, 0.1192, 0.9505]])
WP = np.array([0.95047, 1.0, 1.08883])
LUM = np.array([0.2126, 0.7152, 0.0722])
BANDS = [("红", 330, 30), ("橙黄", 30, 90), ("绿", 90, 160),
         ("青", 160, 220), ("蓝", 220, 290), ("紫", 290, 330)]


def load(path, k=4):
    # CLI 入口路径消毒：realpath 归一化（消除 ../ 与符号链接）
    path = os.path.realpath(path)
    im = Image.open(path).convert("RGB")
    return np.asarray(im.resize((im.width // k, im.height // k), Image.BOX)).astype(np.float64) / 255.0


def lab(rgb):
    c = np.clip(rgb, 0, 1)
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    xyz = (c @ LMN.T) / WP
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return 116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])


def stats(rgb):
    L, A, B = lab(rgb)
    C = np.hypot(A, B)
    h = np.degrees(np.arctan2(B, A)) % 360
    l8 = rgb @ LUM * 255
    col = C > 8
    d = {
        "L": L, "C": C, "h": h, "col": col,
        "p": [float(np.percentile(L, q)) for q in (5, 25, 50, 75, 95)],
        "mean": float(L.mean()),
        "clip": float((l8 >= 250).mean() * 100),
        "black": float((l8 <= 5).mean() * 100),
        "Cmed": float(np.median(C)),
        "Ccol": float(np.median(C[col])) if col.sum() else 0.0,
        "lumaP50": float(np.percentile(l8, 50)),
        "rel": float(np.median(np.abs(rgb - (rgb @ LUM)[..., None]).max(-1) / np.maximum(rgb @ LUM, 0.02))),
    }
    d["band"] = {nm: float(np.median(h[(h >= lo) & (h < hi) & col])) if ((h >= lo) & (h < hi) & col).sum() > 500 else float("nan")
                 for nm, lo, hi in BANDS}
    d["bandc"] = {nm: float(np.median(C[(h >= lo) & (h < hi) & col])) if ((h >= lo) & (h < hi) & col).sum() > 500 else float("nan")
                  for nm, lo, hi in BANDS}
    d["bandn"] = {nm: float(((h >= lo) & (h < hi) & col).mean() * 100) for nm, lo, hi in BANDS}
    return d


def main(mine, pc, tag=""):
    a, b = load(mine), load(pc)
    sa, sb = stats(a), stats(b)
    print(f"\n{'=' * 74}\n场景 {tag}  {mine.split('/')[-1]}  vs  {pc.split('/')[-1]}   [{a.shape[1]}x{a.shape[0]}]")
    rows = [
        ("亮度 L* P5/P25/P50/P75/P95", "  ".join(f"{v:5.1f}" for v in sa["p"]), "  ".join(f"{v:5.1f}" for v in sb["p"])),
        ("L* 均值", f"{sa['mean']:5.2f}", f"{sb['mean']:5.2f}"),
        ("显示域 luma P50", f"{sa['lumaP50']:5.1f}", f"{sb['lumaP50']:5.1f}"),
        ("过曝 ≥250 占比 %", f"{sa['clip']:5.2f}", f"{sb['clip']:5.2f}"),
        ("死黑 ≤5 占比 %", f"{sa['black']:5.2f}", f"{sb['black']:5.2f}"),
        ("CIELAB C* 中位(全图)", f"{sa['Cmed']:5.2f}", f"{sb['Cmed']:5.2f}"),
        ("CIELAB C* 中位(彩色)", f"{sa['Ccol']:5.2f}", f"{sb['Ccol']:5.2f}"),
        ("rel 中位", f"{sa['rel']:5.3f}", f"{sb['rel']:5.3f}"),
    ]
    print(f"  {'指标':26s} {'我们':>22s} {'PhotonCamera':>22s}")
    for n, x, y in rows:
        print(f"  {n:26s} {x:>22s} {y:>22s}")
    print(f"\n  分亮度段 C* 中位(样本占比%) —— 看色度沿亮度的剖面:")
    print(f"    {'L* 段':10s} {'我们 C*':>9s} {'PC C*':>9s} {'我们占比':>9s} {'PC占比':>9s}")
    for lo in range(0, 100, 20):
        ma = (sa["L"] >= lo) & (sa["L"] < lo + 20)
        mb = (sb["L"] >= lo) & (sb["L"] < lo + 20)
        print(f"    {lo:3d}-{lo + 20:3d}   {np.median(sa['C'][ma]):9.2f} {np.median(sb['C'][mb]):9.2f} "
              f"{ma.mean() * 100:8.1f}% {mb.mean() * 100:8.1f}%")
    print(f"\n  色相带(彩色像素 C*>8):中位 h 与 C*,占比")
    for nm, _, _ in BANDS:
        print(f"    {nm:4s}  h 我们 {sa['band'][nm]:6.1f} / PC {sb['band'][nm]:6.1f}   "
              f"C* 我们 {sa['bandc'][nm]:5.2f} / PC {sb['bandc'][nm]:5.2f}   "
              f"占比 我们 {sa['bandn'][nm]:5.2f}% / PC {sb['bandn'][nm]:5.2f}%")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "")
