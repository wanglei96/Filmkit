#!/usr/bin/env python3
"""P4 色度降噪参数评估（离线，从 DNG 重建 P4 输入域）。

为什么需要它：P4（kFsChromaDenoise）是"保 Y、只平滑色度"的 bilateral，它的两个
旋钮看起来都能降彩噪，实际只有一个有效 —— 而且另一个的**调大方向是错的**：

  ① 空间 σs 不是旋钮：循环半径锁死 r 时，σs 超过 r/2 就饱和。实测 r=2 下
     σs 1.6→2.2 只改善 6%，σs=3.0/4.0 同样停在 ~6%。要降彩噪必须**放半径**。
  ② 值域阈值 σc **调大 = 抹真实色度**。旧值 0.035 在暗部是"真实色度差 0.014"
     的 2.5 倍，真实色度被当噪声吞掉（"桌面颜色不如 PhotonCamera"的主因之一）。
     降噪职责应交给半径，σc 只负责保住真实色度边缘。

所以改 P4 参数前先跑这个脚本，看"彩噪残余 vs 真实色度边缘保留"的权衡点，
别凭感觉调 σs。

用法：
  python3 tools/chroma-denoise-eval.py <a.dng> [b.dng ...] [--crop 800]

口径与坑：
  noise —— 暗部色度高频残余（lag-1 差分 /√2），越小越好；相对输入百分比。
  edge  —— 真实色度边缘保留率。**两边都必须先平滑再算梯度**，否则输入侧的
           噪声会污染分母，把"降噪有效的变体"误判成"抹色严重"（踩过一次）。
  lfdev —— 低频色度偏差，衡量色彩的空间分布被改变的程度。

注意：本脚本用 libraw/AHD 重建输入域，引擎实际走 RCD + P0。**绝对数字不可照搬
真机，只有相对趋势可信**（半径↑→噪声↓边缘↓；σc↓→边缘↑噪声↑）。
"""

import argparse
import numpy as np
import rawpy

LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)

# 候选参数：(标签, ns, sig_s, radius, σc 地板, dn_k)
# 引擎侧对应：ns = noiseSigma、dn_k = sc_k（标准 2.75 / 强 3.25）、
# radius 与地板是 kFsChromaDenoise 里的常量。
CANDIDATES = [
    ("r2 σs1.6 σc.035（v0.6.31）", 0.015, 1.6, 2, 0.015, 2.75),
    ("r3 σs2.2 σc.035", 0.015, 2.2, 3, 0.015, 2.75),
    ("r3 σs2.2 σc.024（v0.6.32）", 0.012, 2.2, 3, 0.008, 2.75),
    ("r3 σs2.2 σc.022 紧", 0.012, 2.2, 3, 0.006, 2.75),
    ("r4 σs2.2 σc.024", 0.012, 2.2, 4, 0.008, 2.75),
]


def load_linear(path, crop):
    with rawpy.imread(path) as r:
        rgb = r.postprocess(use_camera_wb=True, no_auto_bright=True,
                            output_bps=16, gamma=(1, 1),
                            output_color=rawpy.ColorSpace.sRGB,
                            demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD)
    a = rgb.astype(np.float32) / 65535.0
    h, w = a.shape[:2]
    y0, x0 = (h - crop) // 2, (w - crop) // 2
    return np.ascontiguousarray(a[y0:y0 + crop, x0:x0 + crop])


def box_mean(a, r):
    k = 2 * r + 1
    p = np.pad(a, ((r, r), (r, r)) + ((0, 0),) * (a.ndim - 2), mode="reflect")
    c = p.cumsum(0).cumsum(1)
    c = np.pad(c, ((1, 0), (1, 0)) + ((0, 0),) * (a.ndim - 2))
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def bilateral_chroma(img, ns, dn_k, sig_s, radius, floor):
    """与 kFsChromaDenoise 同式（roll 代替 clamp；评估区已避开边界）。"""
    y = img @ LUMA
    cd = img - y[..., None]
    sc = np.maximum(ns, 0.004) * dn_k * np.sqrt(0.2 + 4.0 * y) + floor
    acc = cd.copy()
    wsum = np.ones_like(y)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx == 0 and dy == 0:
                continue
            cy = np.roll(np.roll(y, dy, 0), dx, 1)
            ccd = np.roll(np.roll(cd, dy, 0), dx, 1)
            wy = (cy - y) / (sc * 1.2)
            wc = np.linalg.norm(ccd - cd, axis=-1) / sc
            w = np.exp(-0.5 * (wy * wy + wc * wc)
                       - 0.5 * (dx * dx + dy * dy) / (2.0 * sig_s * sig_s))
            acc += w[..., None] * ccd
            wsum += w
    return y[..., None] + acc / wsum[..., None]


def chroma_edges(cd):
    h, w = cd.shape[:2]
    g = np.zeros((h, w), np.float32)
    gx = np.zeros((h, w - 1), np.float32)
    gy = np.zeros((h - 1, w), np.float32)
    for c in range(3):
        gx += (cd[:, 1:, c] - cd[:, :-1, c]) ** 2
        gy += (cd[1:, :, c] - cd[:-1, :, c]) ** 2
    g[:, :-1] += gx
    g[:-1, :] += gy
    g[:-1, :-1] *= 0.5  # 角点被两个方向各计一次
    return np.sqrt(g)


def hf_noise(cd, mask):
    ds = []
    for c in range(3):
        f = cd[..., c]
        m = mask[:, :-1] & mask[:, 1:]
        ds.append((f[:, 1:] - f[:, :-1])[m].std() / np.sqrt(2))
    return float(np.sqrt(np.mean(np.square(ds))))


def run(path, crop):
    img = load_linear(path, crop)
    y = img @ LUMA
    b = int(0.05 * min(y.shape))
    inner = np.zeros_like(y, bool)
    inner[b:-b, b:-b] = True
    dark = (y < 55 / 255.0) & inner
    m = dark.copy()
    m[1:, :] &= dark[:-1, :]; m[:-1, :] &= dark[1:, :]
    m[:, 1:] &= dark[:, :-1]; m[:, :-1] &= dark[:, 1:]
    if m.sum() < 2000:
        print(f"== {path}: 暗部像素太少，跳过")
        return

    cd0 = img - y[..., None]
    lf_in = box_mean(cd0, 3)
    g_smooth_in = chroma_edges(box_mean(cd0, 2))       # 平滑后才选真实色度边缘
    emask = (g_smooth_in >= np.percentile(g_smooth_in[inner], 95)) & inner
    n_in = hf_noise(cd0, m)
    print(f"== {path.split('/')[-1]}  输入噪声 {n_in:.6f}  "
          f"暗部 {m.sum()}px  真实边 {emask.sum()}px")
    for tag, ns, sig_s, radius, floor, dn_k in CANDIDATES:
        out = bilateral_chroma(img, ns, dn_k, sig_s, radius, floor)
        cdo = out - (out @ LUMA)[..., None]
        n = hf_noise(cdo, m)
        edge = float(chroma_edges(box_mean(cdo, 2))[emask].mean()
                     / g_smooth_in[emask].mean())
        lfdev = float(np.sqrt(np.mean(
            (box_mean(cdo, 3) - lf_in)[inner] ** 2)))
        print(f"  {tag:28s} noise={n:.6f} ({n / n_in * 100:5.1f}%)  "
              f"edge={edge:6.3f}  lfdev={lfdev:.6f}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("dng", nargs="+")
    ap.add_argument("--crop", type=int, default=800,
                    help="中心裁切边长（全分辨率太慢）")
    a = ap.parse_args()
    for p in a.dng:
        run(p, a.crop)
