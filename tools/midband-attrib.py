#!/usr/bin/env python3
"""中频损失归因评估：分段开关 A/B 成片的带通对比。

口径（2026-10-01 三 APP 对比实测确立）：
  1. 相位相关配准（全图 1/4 下采样粗配 + ROI 原尺寸精配）；
  2. 归一带通（DoG 2/4/8/16px RMS，除以该 ROI meanY 再乘公共亮度 137）——
     消除各组成片亮度差异后比较频率响应；
  3. 平坦块分尺度噪声（自动选 std 最低 64px 块，去线性梯度后按尺度分解）；
  4. CIELAB L*/C*（同亮度口径看饱和与影调）。

分组：按 EXIF DateTimeOriginal 聚类，组内间隔小、组间 ≥ --gap 秒。
      拍摄规程：每组连拍 3 张（间隔 2~4s），组间改设置后 ≥25s 再拍。

用法：
  python3 tools/midband-attrib.py A.jpg B.jpg ... [--gap 12] [--roi name=x,y,size ...]
      [--pc pc_ref.jpg] [--out outdir]

ROI 默认床单/地板两块（本次实验场景）；换场景用 --roi 覆盖，坐标以第一张为准。
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

DEFAULT_ROIS = {"bed": (550, 3130, 800), "floor": (2100, 3200, 800)}
REF_Y = 137.0


def exif_time(path):
    im = Image.open(path)
    ex = im._getexif() or {}
    for tag, val in ex.items():
        if tag == 36867:  # DateTimeOriginal
            import datetime
            return datetime.datetime.strptime(val, "%Y:%m:%d %H:%M:%S")
    return None


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32)


def blur(a, r):
    return np.asarray(
        Image.fromarray(a.clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(r)),
        dtype=np.float32)


def phase_corr(ref, mov):
    f1 = np.fft.rfft2(ref)
    f2 = np.fft.rfft2(mov)
    r = f1 * np.conj(f2)
    r /= (np.abs(r) + 1e-9)
    corr = np.fft.irfft2(r, s=ref.shape)
    pk = np.unravel_index(np.argmax(corr), corr.shape)
    dy, dx = pk
    if dy > ref.shape[0] // 2:
        dy -= ref.shape[0]
    if dx > ref.shape[1] // 2:
        dx -= ref.shape[1]
    return dy, dx


def align_shift(ref_img, mov_img, roi):
    """ROI 中心区域精配准（外扩 25% 供平移余量）。"""
    x, y, s = roi
    m = int(s * 0.25)
    x0, y0 = max(0, x - m), max(0, y - m)
    x1 = min(ref_img.shape[1], mov_img.shape[1], x + s + m)
    y1 = min(ref_img.shape[0], mov_img.shape[0], y + s + m)
    ref = ref_img[y0:y1, x0:x1].mean(axis=2)
    mov = mov_img[y0:y1, x0:x1].mean(axis=2)
    h = min(ref.shape[0], mov.shape[0])
    w = min(ref.shape[1], mov.shape[1])
    return phase_corr(ref[:h, :w], mov[:h, :w])


def band_energy(Y, norm):
    out = {}
    for r1, r2, name in [(0, 1, "2px"), (1, 2, "4px"), (2, 4, "8px"), (4, 8, "16px")]:
        b = (Y - blur(Y, r2)) if r1 == 0 else (blur(Y, r1) - blur(Y, r2))
        out[name] = b.std() * norm
    return out


def flat_patches(c, n=6, bs=64):
    Y = c.mean(axis=2)
    yy, xx = np.mgrid[0:bs, 0:bs]
    A = np.stack([xx.ravel(), yy.ravel(), np.ones(bs * bs)], 1)
    best = []
    for by in range(0, Y.shape[0] - bs, 24):
        for bx in range(0, Y.shape[1] - bs, 24):
            b = Y[by:by + bs, bx:bx + bs]
            coef, *_ = np.linalg.lstsq(A, b.ravel(), rcond=None)
            best.append(((b.ravel() - A @ coef).std(), by, bx, b.mean()))
    best.sort()
    return [(by, bx) for _, by, bx, _ in best[:n]]


def noise_scales(c, norm):
    """平坦块去趋势后分尺度高通 RMS（2/4/8px）。"""
    patches = flat_patches(c)
    acc = {k: [] for k in ("2px", "4px", "8px", "sigY")}
    for by, bx in patches:
        Y = c[by:by + 64, bx:bx + 64].mean(axis=2)
        mY = Y.mean()
        Y = Y - Y.mean()
        acc["2px"].append((Y - blur(Y + 128, 1) + 128).std() * norm)
        acc["4px"].append((blur(Y + 128, 1) - blur(Y + 128, 2) + 128).std() * norm)
        acc["8px"].append((blur(Y + 128, 2) - blur(Y + 128, 4) + 128).std() * norm)
        acc["sigY"].append(Y.std() * norm)  # 去均值总波动（含低频残差）
    return {k: float(np.median(v)) for k, v in acc.items()}, patches


def cielab(c):
    b = c / 255.0
    lin = np.where(b <= 0.04045, b / 12.92, ((b + 0.055) / 1.055) ** 2.4)
    X = 0.4124 * lin[:, :, 0] + 0.3576 * lin[:, :, 1] + 0.1805 * lin[:, :, 2]
    Y = 0.2126 * lin[:, :, 0] + 0.7152 * lin[:, :, 1] + 0.0722 * lin[:, :, 2]
    Z = 0.0193 * lin[:, :, 0] + 0.1192 * lin[:, :, 1] + 0.9505 * lin[:, :, 2]
    d = 6 / 29

    def f(t):
        return np.where(t > d ** 3, np.cbrt(t), t / (3 * d * d) + 4 / 29)

    fy = f(Y)
    a = 500 * (f(X / 0.9505) - fy)
    bb = 200 * (fy - f(Z / 1.089))
    L = 116 * fy - 16
    C = np.hypot(a, bb)
    return float(np.percentile(L, 50)), float(np.percentile(C, 50)), float(a.mean()), float(bb.mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jpgs", nargs="+")
    ap.add_argument("--gap", type=float, default=12.0, help="组间切分秒数")
    ap.add_argument("--roi", action="append", default=[],
                    help="name=x,y,size（可多次；默认 bed/floor）")
    ap.add_argument("--pc", help="跨 APP 参照图（只算不分组）")
    ap.add_argument("--out", default="test_temp/analysis3")
    args = ap.parse_args()

    rois = dict(DEFAULT_ROIS)
    for spec in args.roi:
        name, xy = spec.split("=", 1)
        x, y, s = (int(v) for v in xy.split(","))
        rois[name] = (x, y, s)

    files = sorted(args.jpgs)
    times = [exif_time(f) for f in files]
    if any(t is None for t in times):
        sys.exit("缺 EXIF DateTimeOriginal: " + ", ".join(f for f, t in zip(files, times) if t is None))

    # 按时间聚类（files 顺序 = 用户给的顺序，这里统一按时间排）
    order = sorted(range(len(files)), key=lambda i: times[i])
    groups = []  # [(label, [file...])]
    cur = [order[0]]
    for prev, i in zip(order, order[1:]):
        if (times[i] - times[prev]).total_seconds() >= args.gap:
            groups.append(cur)
            cur = []
        cur.append(i)
    groups.append(cur)
    labels = [chr(ord("A") + k) for k in range(len(groups))]

    print(f"分组（--gap {args.gap}s）:")
    for lab, idx in zip(labels, groups):
        ts = ", ".join(times[i].strftime("%H:%M:%S") for i in idx)
        print(f"  组{lab}: {[os.path.basename(files[i]) for i in idx]}  [{ts}]")

    imgs = {i: load(files[i]) for i in order}
    ref_idx = order[0]
    ref_img = imgs[ref_idx]

    # 每 ROI 求各组相对参考的平移（组内各张独立配准）
    shifts = {}
    for rname, roi in rois.items():
        for i in order:
            dy, dx = align_shift(ref_img, imgs[i], roi)
            shifts[(rname, i)] = (dy, dx)

    os.makedirs(args.out, exist_ok=True)
    rows = []  # (label, rname, metrics)
    for lab, idx in zip(labels, groups):
        for rname, (x, y, s) in rois.items():
            per = []
            for i in idx:
                dy, dx = shifts[(rname, i)]
                yy, xx = y + dy, x + dx
                c = imgs[i][yy:yy + s, xx:xx + s]
                Y = c.mean(axis=2)
                norm = REF_Y / max(Y.mean(), 1.0)
                be = band_energy(Y, norm)
                ns, _ = noise_scales(c, norm)
                L50, C50, am, bm = cielab(c)
                per.append(dict(f=os.path.basename(files[i]), be=be, ns=ns,
                                meanY=Y.mean(), L50=L50, C50=C50, a=am, b=bm))
            rows.append((lab, rname, per))

    if args.pc:
        pc = load(args.pc)
        for rname, (x, y, s) in rois.items():
            dy, dx = align_shift(ref_img, pc, rois[rname])
            c = pc[y + dy:y + dy + s, x + dx:x + dx + s]
            Y = c.mean(axis=2)
            norm = REF_Y / max(Y.mean(), 1.0)
            be = band_energy(Y, norm)
            ns, _ = noise_scales(c, norm)
            L50, C50, am, bm = cielab(c)
            rows.append(("PC", rname, [dict(f=os.path.basename(args.pc), be=be, ns=ns,
                                            meanY=Y.mean(), L50=L50, C50=C50, a=am, b=bm)]))

    # 汇总表：组内取中位
    def med(vals):
        return float(np.median(vals))

    print(f"\n=== 归一带通（ROI 亮度归一到 {REF_Y:.0f}；组内中位） ===")
    header = f"{'组':<4}{'ROI':<7}{'meanY':>6} | {'2px':>6}{'4px':>6}{'8px':>6}{'16px':>6} | " \
             f"{'σ2':>5}{'σ4':>5}{'σ8':>5} | {'L50':>5}{'C50':>5}{'a*':>6}"
    print(header)
    base = {}  # (rname) -> 基线组指标
    for lab, rname, per in rows:
        be = {k: med([p["be"][k] for p in per]) for k in ("2px", "4px", "8px", "16px")}
        ns = {k: med([p["ns"][k] for p in per]) for k in ("2px", "4px", "8px", "sigY")}
        my = med([p["meanY"] for p in per])
        L50 = med([p["L50"] for p in per])
        C50 = med([p["C50"] for p in per])
        am = med([p["a"] for p in per])
        if lab == labels[0]:
            base[rname] = be
        d4 = f"({(be['4px'] / base[rname]['4px'] - 1) * 100:+.0f}%)" if rname in base else ""
        print(f"{lab:<4}{rname:<7}{my:>6.0f} | {be['2px']:>6.2f}{be['4px']:>5.2f}{d4:>6}"
              f"{be['8px']:>6.2f}{be['16px']:>6.2f} | "
              f"{ns['2px']:>5.2f}{ns['4px']:>5.2f}{ns['8px']:>5.2f} | {L50:>5.1f}{C50:>5.1f}{am:>+6.1f}")

    print("\n判读：4px/8px 行 = 布纹/木纹频段；σ2/σ4/σ8 = 平坦区残留噪声分尺度。")
    print("  组B(关P0) 若 4px 显著回升 → P0 空间核是中频损失主源；")
    print("  组D(关锐化) 若仅 2px 下降 → 锐化只补最细尺度，坐实 3×3 核够不着中频。")


if __name__ == "__main__":
    main()
