#!/usr/bin/env python3
"""基准影调 baseTone 离线扫描（M4-6「闷」专项）。

为什么用「反推」而不是复现整条链：**标准预设 + 无 LUT 时整条链 = baseTone + OETF**
（filmCurve 在中性参数下数学恒等、uSat=1、uSplit=0、外观层 uLook×uLookSat=1 恒等，
见 FrameRenderer.cpp kFsFilm）。于是

    成片 d  →  lin = (d/255)^2.2 = baseTone(x)

反解 x 就是 baseTone 的**真实输入**，已含 WB/CCM/去马赛克/LSC 全部前级效果 ——
不需要复现前级，也就不会踩 2026-09-21「离线参照与实现不同式」的坑。

⚠ 反推必须用**生成该成片的那个参数版本**（--legacy 用于 ≤v0.6.13 的旧归一化公式）。

用法：
  python3 tools/tone-scan.py --invert a.jpg [--legacy]
  python3 tools/tone-scan.py --scan a.jpg [--legacy]
  python3 tools/tone-scan.py --grid a.jpg out.png [--legacy]   # 出对照图
"""

import sys

import numpy as np
from PIL import Image, ImageDraw

# ── 参数版本 ────────────────────────────────────────────────────────────
# v0.6.16（现行）：不动点归一化 —— t 以 piv 为 0.5 展开，S 曲线不动点恒为 piv
CUR = dict(blk=0.0080, piv=0.2184, p=1.20, g=0.82)
# ≤v0.6.13（旧包）：t = xn/piv 或 (xn-piv)/(1-piv) —— 右半段不动点跑到 xn≈0.609
LEG = dict(blk=0.0020, piv=0.2184, p=1.20, g=0.90)
LUM = np.array([0.2126, 0.7152, 0.0722])


def base_tone(x, blk, piv, p, g, ev=0.0, legacy=False):
    """与 GLSL baseTone 同式（legacy=旧归一化）。ev = 线性域曝光补偿（PhotonCamera 约定）。"""
    x = np.asarray(x, dtype=np.float64) * (2.0 ** ev)
    xn = np.clip((x - blk) / (1.0 - blk), 0.0, 1.0)
    if legacy:
        t = np.where(xn <= piv, xn / piv, (xn - piv) / (1.0 - piv))
    else:
        t = np.where(xn <= piv, 0.5 * xn / piv, 0.5 + 0.5 * (xn - piv) / (1.0 - piv))
    tp = np.power(t, p)
    s = tp / np.maximum(tp + np.power(1.0 - t, p), 1e-6)
    x2 = (np.where(xn <= piv, piv * s, piv + (1.0 - piv) * s) if legacy
          else np.where(s <= 0.5, piv * (s / 0.5), piv + (1.0 - piv) * ((s - 0.5) / 0.5)))
    return np.power(np.maximum(x2, 0.0), g)


def invert(lin, cfg, legacy=False):
    """单调函数查表反解：lin → x。"""
    xs = np.linspace(0.0, 1.0, 16384)
    ys = np.maximum.accumulate(base_tone(xs, **cfg, legacy=legacy))
    idx = np.clip(np.searchsorted(ys, lin), 1, len(xs) - 1)
    return xs[idx]


def lstar(lin):
    e, k = 216 / 24389, 24389 / 27
    f = np.where(lin > e, np.cbrt(lin), (k * lin + 16) / 116)
    return 116 * f - 16


def metrics(rgb8):
    lin = (rgb8 / 255.0) ** 2.2
    L = lstar(np.clip(lin @ LUM, 0, 1))
    xyz = lin @ np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722],
                          [0.0193, 0.1192, 0.9505]]).T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    c = np.sqrt((500 * (f[..., 0] - f[..., 1])) ** 2 + (200 * (f[..., 1] - f[..., 2])) ** 2)
    p5, p95 = np.percentile(L, 5), np.percentile(L, 95)
    return float(L.mean()), float(p5), float(p95), float(p95 - p5), float(c.mean())


def render(x, **kw):
    legacy = kw.pop("legacy", False)
    return np.clip(base_tone(x, legacy=legacy, **kw), 0, 1) ** (1 / 2.2) * 255.0


def show(tag, a):
    L, p5, p95, ct, c = metrics(a)
    print(f"{tag:30} L* {L:5.1f}  P5 {p5:5.1f}  P95 {p95:5.1f}  对比 {ct:5.1f}  C* {c:5.2f}")


def load(p):
    return np.asarray(Image.open(p).convert("RGB")).astype(np.float64)


def thumb(a, w=380):
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    return im.resize((w, int(im.height * w / im.width)), Image.LANCZOS)


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    mode = args[0]
    legacy = "--legacy" in args
    files = [a for a in args[1:] if not a.startswith("--")]
    cfg = LEG if legacy else CUR

    if mode == "--invert":
        for f in files:
            a = load(f)
            x = invert((a / 255.0) ** 2.2, cfg, legacy)
            print(f"\n{f.split('/')[-1]}  ({'旧包' if legacy else '现行'}参数反推)")
            show("  成片（实测）", a)
            show("  反推→重渲染（自洽校验）", render(x, **cfg, legacy=legacy))
            for q in (1, 5, 25, 50, 75, 95, 99):
                print(f"    x P{q:<2} = {np.percentile(x, q):.4f}")
    elif mode == "--scan":
        for f in files:
            a = load(f)
            x = invert((a / 255.0) ** 2.2, cfg, legacy)
            print(f"\n=== {f.split('/')[-1]} ===")
            show("  实测（基线）", a)
            print("  --- 修正公式 + 提高 p（不动点不动 = 暗部压/高光提）---")
            for p in (1.20, 1.60, 2.00, 2.40):
                for g in (1.0, 0.95, 0.90):
                    show(f"  p {p:.2f} g {g:.2f}", render(x, blk=0.0080, piv=0.2184, p=p, g=g))
    elif mode == "--grid":
        f, out = files[0], files[1]
        a = load(f)
        x = invert((a / 255.0) ** 2.2, cfg, legacy)
        cands = [("实测", None)] + [
            (f"ev+{ev:.2f} p{p:.1f}", dict(blk=0.0080, piv=0.2184, p=p, g=1.0, ev=ev))
            for ev, p in ((0.35, 1.60), (0.35, 2.00), (0.70, 2.00))]
        ths = []
        for tag, kw in cands:
            img = a if kw is None else render(x, **kw)
            t = thumb(img)
            canvas = Image.new("RGB", (t.width, t.height + 22), (18, 18, 18))
            canvas.paste(t, (0, 22))
            ImageDraw.Draw(canvas).text((6, 6), tag, fill=(235, 235, 235))
            ths.append(canvas)
            if kw is not None:
                show("  " + tag, img)
        W = sum(t.width for t in ths) + 8 * (len(ths) - 1)
        H = max(t.height for t in ths)
        grid = Image.new("RGB", (W, H), (18, 18, 18))
        ox = 0
        for t in ths:
            grid.paste(t, (ox, 0))
            ox += t.width + 8
        grid.save(out, quality=92)
        print(f"\n已出图：{out}")
    else:
        print(__doc__)
        sys.exit(2)
