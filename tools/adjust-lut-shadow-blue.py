#!/usr/bin/env python3
"""给 LUT 的暗部加深蓝度（只动 b*，不动 L*），用于对齐电影负片的"蓝青阴影"。

## 为什么需要它

`kodak_5219` 原资产的暗部 b* 是 −4.8（10% 输入），但**成片只剩 −2.5**：
filmic 把中调抬起来后，暗部落在了 LUT 的"浅蓝段"（30~45% 输入处 b* 只有 −2.4~−4.3）。
`[23] split_warm` 取负值虽然也能加冷，但它的位移量按通道权重给（vec3(0.05,0.02,−0.04)），
算下来要到 b* −4.8 需要 split ≈ −1.0，UI/参数范围都不合适，且会连带改动 a*。

所以直接改 LUT：按输出 L* 给一条**高斯权重**的 b* 下压（中心 30、σ 30），
L*>60 完全不碰（高光暖调保留），L*→0 处权重自然衰减（纯黑不受影响）。

判据：
  ① 灰阶 L* 不变（max|ΔL*| ≤ 0.5）；
  ② 暗部实测加深：L*≈25 的输入，b* 变化 = amount ±0.4；
  ③ 高光不变：L*≈75 的输入，b* 变化 ≤0.3；
  ④ 格点跳变不显著变差（≤ 源 + 3 LSB）。

用法：
    python3 tools/adjust-lut-shadow-blue.py --src …/kodak_5219.lut --out …/kodak_5219_v2.lut --amount 2.0
"""
import argparse
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZE = 64

M_RGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
])
M_XYZ2RGB = np.linalg.inv(M_RGB2XYZ)
WHITE = np.array([0.95047, 1.0, 1.08883])


def srgb_decode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_encode(l):
    l = np.clip(l, 0.0, 1.0)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * l ** (1.0 / 2.4) - 0.055)


def rgb2lab(rgb):
    xyz = rgb @ M_RGB2XYZ.T / WHITE
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16.0 / 116.0)
    return np.stack([116.0 * f[..., 1] - 16.0,
                     500.0 * (f[..., 0] - f[..., 1]),
                     200.0 * (f[..., 1] - f[..., 2])], axis=-1)


def lab2rgb(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx = fy + a / 500.0
    fz = fy - b / 200.0
    f = np.stack([fx, fy, fz], axis=-1)
    xyz = np.where(f ** 3 > 0.008856, f ** 3, (f - 16.0 / 116.0) / 7.787)
    return (xyz * WHITE) @ M_XYZ2RGB.T


def load(path):
    b = open(path, "rb").read()
    n, tl = b[0], b[1]
    cl = b[2 + tl]
    off = 3 + tl + cl
    return np.frombuffer(b[off:], dtype=np.uint8).astype(np.float64).reshape(n, n, n, 3) / 255.0, n


def trilinear(lut, coords):
    n = lut.shape[0]
    t = np.clip(coords, 0.0, 1.0) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    fr = t - i0
    out = np.zeros(coords.shape[:-1] + (3,))
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = ((fr[..., 0] if dr else 1 - fr[..., 0])
                     * (fr[..., 1] if dg else 1 - fr[..., 1])
                     * (fr[..., 2] if db else 1 - fr[..., 2]))
                out += w[..., None] * lut[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
    return out


def grid(size):
    v = np.linspace(0.0, 1.0, size)
    B, G, R = np.meshgrid(v, v, v, indexing="ij")
    return np.stack([R, G, B], axis=-1)


def shift_blue(lut, amount, center=30.0, sigma=30.0, size=SIZE):
    enc = grid(size)
    out = trilinear(lut, enc.reshape(-1, 3))
    lab = rgb2lab(srgb_decode(out))
    w = np.exp(-(((lab[:, 0] - center) / sigma) ** 2))
    w[lab[:, 0] > 60.0] = 0.0        # 高光不动
    w *= np.clip(lab[:, 0] / 8.0, 0.0, 1.0)  # 纯黑端点保护
    lab[:, 2] -= amount * w
    lin = np.clip(lab2rgb(lab), 0.0, 1.0)
    return np.clip(srgb_encode(lin), 0.0, 1.0).reshape(size, size, size, 3)


def probe(lut, x):
    p = np.array([[x, x, x]], dtype=np.float64)
    o = trilinear(lut, p)[0]
    lab = rgb2lab(srgb_decode(o[None]))[0]
    return lab


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--amount", type=float, default=2.0, help="暗部 b* 下压量（正值 = 更蓝）")
    ap.add_argument("--title", default="柯达 5219 · 深蓝")
    ap.add_argument("--category", default="柯达")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    src, _ = load(args.src)
    out = shift_blue(src, args.amount)
    ok = True

    # 判据 ①：L* 不变 —— 比的是"加蓝前后"（不是"输入 vs 输出"）。
    # ⚠️ 源 LUT 本身就不是恒等的（kodak_5219 把 50% ⇒ 56.5%），拿输入灰阶当基线会把它的
    #    提亮误判成我们的改动（首版就是这么红的）。
    g = np.linspace(0.0, 1.0, 21)
    ax = np.stack([g, g, g], -1)
    l_before = rgb2lab(srgb_decode(trilinear(src, ax)))[:, 0]
    l_after = rgb2lab(srgb_decode(trilinear(out, ax)))[:, 0]
    dl = np.abs(l_before - l_after).max()
    ok &= dl <= 0.5
    print(f"判据 1 灰阶 L* 不变: max|ΔL*| = {dl:.2f}（≤0.5）{'PASS' if dl <= 0.5 else 'FAIL'}")

    # 判据 ②③：暗部加深、高光不动
    for x, want, tol, name in ((0.18, args.amount, 0.6, "暗部"),
                               (0.32, args.amount * 0.9, 0.8, "中暗"),
                               (0.80, 0.0, 0.3, "高光")):
        b0 = probe(src, x)[2]
        b1 = probe(out, x)[2]
        d = b0 - b1
        good = abs(d - want) <= tol
        ok &= good
        print(f"判据 2 {name} b*: {b0:+.2f} → {b1:+.2f}（Δ{d:+.2f}，期望 ≈{want:.2f}±{tol}）"
              f"{'PASS' if good else 'FAIL'}")

    # 判据 ④：格点跳变
    ds = max(np.abs(np.diff(src, axis=i)).max() for i in (0, 1, 2)) * 255
    do = max(np.abs(np.diff(out, axis=i)).max() for i in (0, 1, 2)) * 255
    ok &= do <= ds + 3.0
    print(f"判据 3 格点跳变: 源 {ds:.1f} → 新 {do:.1f} LSB（≤源+3）{'PASS' if do <= ds + 3.0 else 'FAIL'}")

    if not args.check:
        lut8 = np.clip(out * 255.0 + 0.5, 0, 255).astype(np.uint8)
        tb, cb = args.title.encode(), args.category.encode()
        blob = bytes([SIZE, len(tb)]) + tb + bytes([len(cb)]) + cb + lut8.tobytes()
        with open(args.out, "wb") as f:
            f.write(blob)
        print(f"写出 {args.out}（{len(blob)} 字节，{SIZE}³ uint8）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
