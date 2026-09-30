#!/usr/bin/env python3
"""同刻 100% 裁剪 A/B（验收规程硬要求：出包前先目视，指标只作辅证）。

为什么要低配准：跨图直接比高频会被 ±1px 局部错位污染（半像素就让边缘梯度掉
一半，lessons 09-30 ③）。两机视角/裁切不同（本地实测 0.98–1.03 局部尺度 + 非
刚体残差），所以**逐 ROI 用低频图做双尺度 NOC 配准**（低频对噪声/降噪差异鲁棒），
在全分辨率上裁同一片 100% 像素并排，供肉眼判读。

用法：
  python3 tools/ab-100pct.py ours.jpg=A pc.jpg=B [--rois x,y,w,h;...] [--out dir]
  ROI 坐标以"第一张图"的全分辨率像素为准。
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

LUMA = np.array([0.2126, 0.7152, 0.0722], np.float32)

# 默认 ROIs：桌面静物场景的纹理位（可按场景改）
# 默认 ROIs：桌面静物场景的纹理位（x, y, w, h，单位 = 第一张图全分辨率像素）
DEFAULT_ROIS = [
    (1950, 2820, 560, 440),   # 鼠标表面（白塑料 + logo）
    (2200, 1900, 560, 440),   # 桌面编织垫（细纹）
    (2050, 2350, 560, 440),   # 饼干（峰谷立体边缘）
    (2950, 1600, 420, 380),   # 桌面 / 垫边（低对比细节）
    (850, 1000, 500, 420),    # 风扇网罩（细线结构）
    (2500, 1350, 500, 420),   # 线材 / 鼠标侧
    (760, 1150, 460, 380),    # 墙面（噪声地板）
]


def load_rgb(p):
    return np.asarray(Image.open(p).convert("RGB"), np.float32)


def gray(a):
    return a @ LUMA


def downsample(a, f):
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    return np.asarray(im.resize((im.width // f, im.height // f), Image.BOX), np.float32)


def ncc_search(ref_small, tgt_small, cy, cx, half, search, step=1):
    """在 tgt_small 中找与 ref_small 同中心的块，返回 (best, dy, dx)。"""
    t = ref_small[cy - half:cy + half, cx - half:cx + half]
    if t.size == 0:
        return (-2.0, 0, 0)
    t = t - t.mean()
    tn = np.sqrt((t * t).sum()) + 1e-9
    best = (-2.0, 0, 0)
    for dy in range(-search, search + 1, step):
        for dx in range(-search, search + 1, step):
            y0, x0 = cy - half + dy, cx - half + dx
            if y0 < 0 or x0 < 0 or y0 + 2 * half > tgt_small.shape[0] or x0 + 2 * half > tgt_small.shape[1]:
                continue
            r = tgt_small[y0:y0 + 2 * half, x0:x0 + 2 * half]
            r = r - r.mean()
            v = float((t * r).sum() / (tn * np.sqrt((r * r).sum()) + 1e-9))
            if v > best[0]:
                best = (v, dy, dx)
    return best


def register(ga, gb, x, y, w, h, f=4, verbose=False):
    """返回 B 中与 A(x,y,w,h) 对应的左上角 (xb, yb, score)。

    粗配准：整图低频 NOC（允许 f 倍降采样下 ±26px 搜索）+ 尺度扫描
    （本地实测两机局部尺度 0.98–1.03，跨 560px 就是 ±10px 量级，必须一起搜索，
    否则"配准"会把尺度差吃成位移，ROI 边缘错位）。
    精配准：全分辨率小窗口 ±6px。
    """
    cxa, cya = (x + w // 2) // f, (y + h // 2) // f
    half = max(10, min(w, h) // (2 * f))
    sa, sb = downsample(ga, f), downsample(gb, f)
    best = (-2.0, 1.0, 0, 0)
    for s in (0.97, 0.98, 0.99, 1.0, 1.01, 1.02, 1.03):
        half_s = int(round(half * s))
        if half_s < 6:
            continue
        # 尺度变化用重采样 A 的窗口近似（低频域足够）
        win = sa[cya - half:cya + half, cxa - half:cxa + half]
        if win.size == 0:
            continue
        win_s = np.asarray(
            Image.fromarray(np.clip(win, 0, 255).astype(np.uint8)).resize((2 * half_s, 2 * half_s),
                                                                          Image.BILINEAR), np.float32)
        v, dy, dx = ncc_search(win_s, sb, cya, cxa, half_s, search=26)
        if v > best[0]:
            best = (v, s, dy, dx)
    v1, s, dy1, dx1 = best
    cxb = int(round((x + w / 2) * 1.0)) + dx1 * f
    cyb = int(round((y + h / 2) * 1.0)) + dy1 * f
    # 全分辨率精修（去掉尺度效应：用固定窗口 ±6px）
    half2 = max(16, min(w, h) // 2)
    v2, dy2, dx2 = ncc_search(ga, gb, cyb, cxb, half2, search=6)
    if verbose:
        print(f"   reg coarse ncc={v1:.2f} scale={s:.2f} d=({dy1*f},{dx1*f}) fine ncc={v2:.2f}")
    return x + dx1 * f + dx2, y + dy1 * f + dy2, max(v1, 0.0), v2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("imgs", nargs="+", help="path=TAG（第一张为参考/坐标基准）")
    ap.add_argument("--rois", default="", help="x,y,w,h;x,y,w,h;...")
    ap.add_argument("--out", default="test_temp/analysis")
    ap.add_argument("--f", type=int, default=4, help="配准降采样倍率")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    items = []
    for a in args.imgs:
        p, _, tag = a.rpartition("=")
        items.append((tag or Path(p).stem, p))
    ref_tag, ref_path = items[0]
    imgs = [(t, load_rgb(p)) for t, p in items]
    ref = imgs[0][1]
    grays = [(t, gray(im)) for t, im in imgs]

    rois = DEFAULT_ROIS
    if args.rois:
        rois = []
        for part in args.rois.split(";"):
            if not part.strip():
                continue
            x, y, w, h = (int(v) for v in part.split(","))
            rois.append((x, y, w, h))

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    pad, lab = 10, 34
    n = len(imgs)
    for i, (x, y, w, h) in enumerate(rois):
        crops = []
        info = []
        for t, im in imgs:
            if t == ref_tag:
                xb, yb, v1, v2 = x, y, 1.0, 1.0
            else:
                xb, yb, v1, v2 = register(grays[0][1], gray(im), x, y, w, h, f=args.f, verbose=args.verbose)
            xb = int(np.clip(xb, 0, im.shape[1] - w))
            yb = int(np.clip(yb, 0, im.shape[0] - h))
            crops.append(im[yb:yb + h, xb:xb + w])
            info.append(f"{t} @({xb},{yb}) ncc={v2:.2f}")
        canvas = Image.new("RGB", (w + 2 * pad, n * (h + lab) + pad), (24, 24, 24))
        d = ImageDraw.Draw(canvas)
        for k, c in enumerate(crops):
            canvas.paste(Image.fromarray(np.clip(c, 0, 255).astype(np.uint8)), (pad, pad + k * (h + lab)))
            d.text((pad + 4, pad + k * (h + lab) + h + 6), info[k], fill=(230, 230, 230))
        name = f"ab100_{i:02d}_{x}_{y}_{w}x{h}.jpg"
        canvas.save(out / name, quality=92)
        print(f"{name}  " + " | ".join(info))


if __name__ == "__main__":
    main()
