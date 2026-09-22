#!/usr/bin/env python3
"""LUT 离线审查（M4 色彩专项）

把内置 .cube 应用到一张图上并输出对照图，用于离线判断风格幅度、亮部行为。

复现的链路与引擎现状一致（FrameRenderer.cpp）：
    输入(sRGB 编码) --EOTF--> 线性 --baseTone(基准影调)--> 线性
      --^(1/2.2) 编码--> [3D LUT × 强度] --> 输出编码值

用法：
    python3 tools/lut-audit.py                      # 内置 CC0 测试图 -> handoff/lut-audit/
    python3 tools/lut-audit.py --input 成片.jpg      # 换成真机成片（关 LUT 的）
    python3 tools/lut-audit.py --gamma22            # 输入已是引擎的 2.2 幂编码域（成片）
    python3 tools/lut-audit.py --strength 0.5 --scan ricoh_neg
"""
import argparse
import glob
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LUT_DIR = os.path.join(ROOT, "app/src/main/assets/luts")
DEFAULT_SRC = os.path.join(ROOT, "app/src/main/assets/lut-preview-src.png")
OUT_DIR = os.path.join(ROOT, "handoff/lut-audit")

FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"  # PingFang.ttc 在 PIL 下打不开


def parse_cube(path):
    size, vals = None, []
    dmin, dmax = np.zeros(3), np.ones(3)
    title = os.path.basename(path)
    for line in open(path, encoding="utf-8"):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if s.startswith("TITLE"):
            title = s[5:].strip().strip('"')
        elif s.startswith("LUT_3D_SIZE"):
            size = int(s.split()[1])
        elif s.startswith("DOMAIN_MIN"):
            dmin = np.array([float(x) for x in s.split()[1:4]])
        elif s.startswith("DOMAIN_MAX"):
            dmax = np.array([float(x) for x in s.split()[1:4]])
        elif s.startswith("LUT_"):
            continue
        else:
            vals.append([float(x) for x in s.split()])
    return title, size, np.array(vals).reshape(size, size, size, 3), dmin, dmax


def sampler(a, n, dmin, dmax):
    """向量化三线性采样；a 的轴序为 [b, g, r]（.cube 数据 r 变化最快）"""
    inv = 1.0 / (dmax - dmin)

    def f(x):
        t = np.clip((x - dmin) * inv, 0.0, 1.0) * (n - 1)
        i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
        fr = t - i0
        out = np.zeros(x.shape[:-1] + (3,))
        for db in (0, 1):
            for dg in (0, 1):
                for dr in (0, 1):
                    w = ((fr[..., 0] if dr else 1 - fr[..., 0])
                         * (fr[..., 1] if dg else 1 - fr[..., 1])
                         * (fr[..., 2] if db else 1 - fr[..., 2]))
                    out += w[..., None] * a[i0[..., 2] + db, i0[..., 1] + dg, i0[..., 0] + dr]
        return out
    return f


def srgb_eotf(d):
    return np.where(d <= 0.04045, d / 12.92, ((d + 0.055) / 1.055) ** 2.4)


def base_tone(x):
    """基准影调（FrameRenderer.cpp baseTone，norm-controlled）"""
    blk, mn, p, g = 0.0020, 0.2184, 1.20, 0.90
    xn = np.clip((x - blk) / (1.0 - blk), 0.0, 1.0)
    t = np.where(xn <= mn, xn / mn, (xn - mn) / (1 - mn))
    tp = t ** p
    s = tp / np.maximum(tp + (1 - t) ** p, 1e-6)
    return np.where(xn <= mn, mn * s, mn + (1 - mn) * s) ** g


def base_chain(lin):
    """基准影调 + 高光去饱和（kFsFilm，参数中性）"""
    n0 = lin.max(axis=-1, keepdims=True)
    out = lin * (base_tone(n0) / np.maximum(n0, 1e-6))
    w = np.clip((n0 - 0.85) / 0.45, 0.0, 1.0)
    w = w * w * (3 - 2 * w)
    return out * (1 - w) + out.max(axis=-1, keepdims=True) * w


M_XYZ = np.array([[0.4124564, 0.3575761, 0.1804375],
                  [0.2126729, 0.7151522, 0.0721750],
                  [0.0193339, 0.1191920, 0.9503041]])


def lab(rgb):
    t = (rgb @ M_XYZ.T) / np.array([0.95047, 1.0, 1.08883])
    f = np.where(t > 0.008856, np.cbrt(t), 7.787 * t + 16 / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]),
                     200 * (f[..., 1] - f[..., 2])], -1)


def to8(rgb):
    return (np.clip(rgb, 0, 1) * 255).round().astype(np.uint8)


def grid(images, labels, cols, path, scale=2):
    cw = images[0].shape[1] * scale
    ch = images[0].shape[0] * scale
    bar = 30
    rows = (len(images) + cols - 1) // cols
    W, H = cols * cw, rows * (ch + bar)
    canvas = Image.new("RGB", (W, H), (24, 24, 24))
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype(FONT_PATH, 20)
    except OSError:
        font = ImageFont.load_default()
    for i, (im, lb) in enumerate(zip(images, labels)):
        r, c = divmod(i, cols)
        x, y = c * cw, r * (ch + bar)
        canvas.paste(Image.fromarray(im).resize((cw, ch), Image.LANCZOS), (x, y))
        draw.text((x + 8, y + ch + 5), lb, fill=(235, 235, 235), font=font)
    canvas.save(path)
    print(f"  写出 {os.path.relpath(path, ROOT)}  ({canvas.width}×{canvas.height})")


def grey_ramp(lut_fn, levels=16):
    """一行灰阶块：暴露白点不足（白块变灰）与暗部抬升"""
    v = np.linspace(0.0, 1.0, levels)
    row = np.repeat(v, 40)
    rgb = np.stack([row, row, row], -1)
    out = lut_fn(rgb)
    return to8(np.clip(out, 0, 1)).reshape(1, -1, 3).repeat(80, axis=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default=DEFAULT_SRC)
    ap.add_argument("--out", default=OUT_DIR)
    ap.add_argument("--strength", type=float, default=1.0)
    ap.add_argument("--gamma22", action="store_true",
                    help="输入已是引擎的 2.2 幂编码域（真机成片）")
    ap.add_argument("--scan", default="ricoh_neg", help="强度扫描用的 LUT 关键字")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    img = np.asarray(Image.open(args.input).convert("RGB"), dtype=np.float64) / 255.0
    lin = img ** 2.2 if args.gamma22 else srgb_eotf(img)
    base = base_chain(lin)
    enc_base = base ** (1 / 2.2)  # 引擎现状：LUT 的输入（display 域）

    luts = []
    for p in sorted(glob.glob(os.path.join(LUT_DIR, "*.cube"))):
        title, n, a, dmin, dmax = parse_cube(p)
        luts.append((os.path.basename(p)[:-5], title, sampler(a, n, dmin, dmax)))

    print(f"输入 {os.path.relpath(args.input, ROOT)}  强度 {args.strength:.2f}  "
          f"{'（2.2 幂编码域）' if args.gamma22 else '（sRGB 编码）'}")
    print(f"{'LUT':12s} {'ΔE 均值':>8s} {'P95':>7s} {'≥250 占比 前->后':>18s} {'clip%':>7s}")

    tiles, labels = [to8(img), to8(base ** (1 / 2.2))], ["原图（无基准链）", "基准链（baseTone）"]
    results = {}
    for key, title, f in luts:
        out_enc = (1 - args.strength) * enc_base + args.strength * f(enc_base)
        out_lin = out_enc ** 2.2
        e = np.linalg.norm(lab(out_lin) - lab(base), axis=-1)
        before = (img >= 250 / 255).any(axis=-1).mean() * 100
        after = (out_enc >= 250 / 255).any(axis=-1).mean() * 100
        clip = (out_enc >= 1.0).any(axis=-1).mean() * 100
        print(f"{title:12s} {e.mean():8.2f} {np.percentile(e, 95):7.2f} "
              f"{before:8.2f}% ->{after:7.2f}% {clip:6.2f}%")
        results[key] = (title, f, out_enc)
        tiles.append(to8(out_enc))
        labels.append(f"{title} 100%")

    grid(tiles, labels, 3, os.path.join(args.out, "01-基准链与6LUT.png"))

    # 强度扫描：对指定的 LUT 出 0/25/50/75/100%
    hit = [k for k in results if args.scan in k] or list(results)
    key = hit[0]
    title, f, _ = results[key]
    s_tiles, s_labels = [], []
    for s in (0.0, 0.25, 0.5, 0.75, 1.0):
        o = (1 - s) * enc_base + s * f(enc_base)
        s_tiles.append(to8(o))
        s_labels.append(f"{title} 强度 {int(s * 100)}%")
    grid(s_tiles, s_labels, 5, os.path.join(args.out, f"02-强度扫描-{key}.png"), scale=1)

    # 灰阶：暴露白点不足 / 暗部抬升
    lv = np.linspace(0, 1, 16)
    ramp = [to8(np.repeat(np.stack([lv] * 3, -1), 40, 0).reshape(1, -1, 3).repeat(80, 0))]
    ramp_labels = ["灰阶输入 0→255"]
    for key, title, f in luts:
        ramp.append(grey_ramp(f))
        ramp_labels.append(f"{title} 输出")
    grid(ramp, ramp_labels, 1, os.path.join(args.out, "03-灰阶与高光.png"), scale=1)

    # 灰阶数值表（判断白点是否够白）
    print("\n灰阶输出（输入 0/64/128/192/255）:")
    for key, title, f in luts:
        v = np.array([0, 64, 128, 192, 255]) / 255.0
        o = f(np.stack([v] * 3, -1)) * 255
        print(f"  {title:8s} " + "  ".join(f"{x:6.1f}" for x in o.mean(axis=-1)))


if __name__ == "__main__":
    main()
