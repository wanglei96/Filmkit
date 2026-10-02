#!/usr/bin/env python3
"""成片周期伪影分析：找 tile 接缝（原图 992px 周期）、16px 点阵、CFA 2px 棋盘。

方法：灰度 → 去大核低频 → 逐列/逐行高通能量投影 → 峰位与 tile 网格对齐检查；
再用 2D FFT 径向谱看高频是否有窄带峰。
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def load_gray(p):
    im = Image.open(p).convert("L")
    return np.asarray(im, dtype=np.float32)


def hp(a, r=8):
    return a - np.asarray(Image.fromarray(a.clip(0, 255).astype(np.uint8))
                           .filter(ImageFilter.GaussianBlur(r)), dtype=np.float32)


def profile(a, axis):
    e = np.abs(a).mean(axis=axis)
    return e


def top_peaks(e, k=12, exclude=4):
    idx = np.argsort(e)[::-1]
    out = []
    for i in idx:
        if all(abs(i - j) > exclude for j in out):
            out.append(int(i))
        if len(out) >= k:
            break
    return sorted(out)


def analyze(path, tag):
    g = load_gray(path)
    h, w = g.shape
    print(f"\n=== {tag}  {w}x{h}  {Path(path).name}")
    a = hp(g, 8)
    col = profile(a, 0)
    row = profile(a, 1)
    med_c, med_r = float(np.median(col)), float(np.median(row))
    print(f"列能量 中位={med_c:.3f} p99={np.percentile(col,99):.3f} max={col.max():.3f} "
          f"max/med={col.max()/med_c:.2f}")
    print(f"行能量 中位={med_r:.3f} p99={np.percentile(row,99):.3f} max={row.max():.3f} "
          f"max/med={row.max()/med_r:.2f}")
    # tile 网格：packed 域步进 496 → 原图 992；tile 尺寸 512 → 原图 1024
    print("列峰(位置/是否≈992k):", [(p, round(p / 992.0, 2)) for p in top_peaks(col)])
    print("行峰(位置/是否≈992k):", [(p, round(p / 992.0, 2)) for p in top_peaks(row)])
    # 网格能量占比：把列能量按 992 折叠
    for period, name in ((992, "tile步进992"), (1024, "tile尺寸1024"), (16, "16px点阵"), (2, "CFA2px")):
        if w > 4 * period:
            fold = col[: (w // period) * period].reshape(-1, period).mean(axis=0)
            print(f"  列能量按 {name} 折叠: 起伏={fold.max()/max(fold.mean(),1e-9):.3f}")
    # 高频窄带：2D FFT 径向平均
    F = np.abs(np.fft.rfft2(a * np.hanning(h)[:, None] * np.hanning(w)[None, :]))
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.rfftfreq(w)[None, :]
    fr = np.sqrt(fy ** 2 + fx ** 2)
    band = (fr > 0.25) & (fr < 0.5)          # 周期 2-4 px
    print(f"  高频(周期2-4px)能量占比={F[band].mean()/F.mean():.3f}")
    return g


if __name__ == "__main__":
    for p, t in [(sys.argv[1], "sample")] if len(sys.argv) > 1 else [
        ("test_temp/FILMKIT_20261002-121341.jpg", "255(nnint3, 本帧)"),
        ("test_temp/FILMKIT_20261002-114115.jpg", "254(nnint2, 已知4x3网格)"),
    ]:
        analyze(p, t)
