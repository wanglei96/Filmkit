#!/usr/bin/env python3
"""色彩量化 + 外观层参数扫描（M3 P1 治"寡淡" / M4 P5 对标 harness 雏形）。

度量（8bit RGB，0..255）：
  C*      —— CIELAB chroma 均值（**与亮度解耦，跨图比较首选**）
  sat     —— HSV 饱和度均值（色度 / 峰值）；注意它随亮度漂移
  sep     —— 通道分离度均值（max−min）——**绝对量，跨图比较必须先统一亮度**
  neutral —— 近中性像素占比（max−min < 阈值），同样受亮度影响

口径教训（2026-09-21）：libraw 的 rawpy 默认 `auto_bright=on` 会把亮度自动拉到
~2.5×（本场景 37.6 → 94.4），拿它当参照会得出"通道分离 -48%"这类夸大结论。
跨实现比较要么 `no_auto_bright=True`，要么先把亮度统一再比。

用法：
  python3 tools/color-metrics.py a.jpg [b.jpg ...]             # 只看度量
  python3 tools/color-metrics.py --dng x.dng                   # 用 rawpy 完整链渲染后度量（libraw 参照）
  python3 tools/color-metrics.py --scan a.jpg                  # 扫描外观层饱和增益

参照口径来自 v0.5.42 调研：引擎成片 vs libraw 完整链 = sat −21% / sep −29% / neutral +10pp。
"""

import os
import sys

import numpy as np
from PIL import Image

LUM = np.array([0.2126, 0.7152, 0.0722])
NEUTRAL_T = 6.0  # max−min < 6/255 ≈ 2.4% 视为近中性


def load(path):
    # CLI 入口的路径消毒：realpath 归一化（消除 ../ 与符号链接），防误用穿越出工作区
    path = os.path.realpath(path)
    if path.lower().endswith(".dng"):
        import rawpy

        with rawpy.imread(path) as raw:
            return raw.postprocess(use_camera_wb=True, gamma=(2.222, 4.5)).astype(np.float64)
    return np.asarray(Image.open(path).convert("RGB")).astype(np.float64)


_XYZ_M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722],
                   [0.0193, 0.1192, 0.9505]])


def chroma(a):
    """CIELAB C* 均值（sRGB → XYZ(D65) → Lab）"""
    c = a / 255.0
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    xyz = (c @ _XYZ_M.T) / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    A = 500 * (f[..., 0] - f[..., 1])
    B = 200 * (f[..., 1] - f[..., 2])
    return float(np.sqrt(A * A + B * B).mean())


def metrics(a):
    mx, mn = a.max(axis=2), a.min(axis=2)
    sat = float(np.mean(np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0.0)))
    sep = float(np.mean(mx - mn))
    neutral = float(np.mean((mx - mn) < NEUTRAL_T))
    return sat, sep, neutral


def apply_look(a, gain, chroma=1.0, knee=64.0):
    """外观层数学（与 kFsFilm 外观层同式，显示域近似）：亮度保持 + 饱和增益
    + 色度 S 曲线（越浓的越浓，`chroma` = 端点增益，knee 为转角）。"""
    l = a @ LUM
    c = a - l[..., None]
    t = np.clip(np.abs(c).max(axis=2) / knee, 0.0, 1.0)
    s = t * t * (3.0 - 2.0 * t)
    chroma_f = (1.0 + (chroma - 1.0) * s)[..., None]
    return np.clip(l[..., None] + c * gain * chroma_f, 0.0, 255.0)


def show(tag, a):
    sat, sep, neutral = metrics(a)
    print(f"{tag:22} C* {chroma(a):5.2f}   sat {sat:.4f}   sep {sep:5.2f}   neutral {neutral*100:5.1f}%   luma {float((a @ LUM).mean()):5.1f}")
    return sat, sep, neutral


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    if args[0] == "--dng":
        show(args[1].split("/")[-1], load(args[1]))
    elif args[0] == "--scan":
        a = load(args[1])
        tag = args[1].split("/")[-1]
        base = show(f"{tag} (原样)", a)
        print("--- 外观层饱和增益扫描（chroma 1.0）---")
        for g in (1.05, 1.10, 1.15, 1.20, 1.25, 1.30, 1.40):
            s = show(f"  gain {g:.2f}", apply_look(a, g))
        print("--- 细分：chroma 端点 1.25（色度 S 曲线）---")
        for g in (1.05, 1.10, 1.15):
            show(f"  gain {g:.2f} chroma 1.25", apply_look(a, g, 1.25))
    else:
        for p in args:
            show(p.split("/")[-1], load(p))
