#!/usr/bin/env python3
"""离线核对：filmic 域小波高光重建（kFsHr*）的裁剪 mask 触发面。

回答一个问题：`threshold` 取多少，mask 覆盖面才等于真 RAW 饱和面？

背景（真机数据驱动的怀疑）：日志 clipB（= mask 的 64×48 块级统计，与引擎
kFsHrStat 同一判据）在 0.10.120 上是 523~601 / 3072 块 = 17~20%，而
tools/hl-opposed-check.py 在同批 DNG 上量到的真 RAW 饱和只有 0.007~0.012%
（且只有 G 通道）—— 差三个数量级。若属实，17% 的画面被注入 20% 相对幅度
的彩色噪声再做小波外推，就是"杯口反光区色块"的来源。

公式出处（逐行对照 darktable src/iop/filmicrgb.c，勿凭记忆改）：
  mask_clipped_pixels: argument = -pix_max * normalize + feathering
                       weight   = 1 / (1 + 2^argument)
                       normalize = feather / threshold;  feather = 2^(12/3) = 16
  dt_iop_filmicrgb_init: threshold = 2^(white_source + reconstruct_threshold)
                                      * grey_source        (reconstruct_threshold 默认 0)
  pix_max = sqrt(inR² + inG² + inB²)   —— 在 WB 之后、去马赛克之后的线性域

用法：
  python3 tools/hr-mask-check.py test_temp/FILMKIT_20261003-143534.dng
  python3 tools/hr-mask-check.py <dng> --white-ev 4.0 --exposure-ev 0
"""
import argparse
import sys

import numpy as np
import rawpy

BLACK, WHITE = 64.0, 1023.0
GREY = 0.1845          # darktable filmic grey_source
FEATHER_STOPS = 3.0    # reconstruct_feather 默认 3.0
CFA_LUT_GRBG = np.array([1, 0, 2, 1], dtype=np.int32)  # 同 hl-opposed-check.py


def shift_sum9(a):
    """3×3 邻域和（边界 zero-pad）。"""
    p = np.pad(a, 1, mode="constant")
    s = np.zeros_like(a)
    for dy in range(3):
        for dx in range(3):
            s += p[dy:dy + a.shape[0], dx:dx + a.shape[1]]
    return s


def demosaic_bilinear(raw, ch):
    """双线性去马赛克（引擎走 RCD，这里只求 mask 判据的量级，不需同精度）。"""
    h, w = raw.shape
    out = np.empty((h, w, 3), np.float32)
    for c in range(3):
        known = np.where(ch == c, raw, 0.0).astype(np.float32)
        cnt = np.where(ch == c, 1.0, 0.0).astype(np.float32)
        out[..., c] = shift_sum9(known) / np.maximum(shift_sum9(cnt), 1.0)
    return out


def mask_weight(pix_max, threshold):
    normalize = (2.0 ** (12.0 / FEATHER_STOPS)) / threshold
    return 1.0 / (1.0 + np.exp2(-pix_max * normalize + 2.0 ** (12.0 / FEATHER_STOPS)))


def block_count(w_map, out_w=64, out_h=48):
    """引擎 kFsHrStat：64×48 块内采样 max ≥ 16/255 ⇒ 该块计为 clipped。"""
    h, w = w_map.shape
    bx, by = -(-w // out_w), -(-h // out_h)
    n = 0
    for j in range(out_h):
        for i in range(out_w):
            blk = w_map[j * by:(j + 1) * by:2, i * bx:(i + 1) * bx:2]
            if blk.size and blk.max() >= 16.0 / 255.0:
                n += 1
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dng")
    ap.add_argument("--white-ev", type=float, default=4.0,
                    help="filmic 白点 EV（日志 whiteEv，含用户 EV 平移）")
    ap.add_argument("--exposure-ev", type=float, default=0.0,
                    help="用户 EV 滑杆（引擎在 filmic 内乘 2^ev；HR 跑在乘之前）")
    ap.add_argument("--gain", default=None, help="R,G,B（默认取 DNG 的 camera_whitebalance）")
    ap.add_argument("--lsc-gain", type=float, default=1.41,
                    help="离线(无 LSC)→引擎域的增益标定；1.41 = 引擎日志 linMid 0.2568 / 离线中位 0.182")
    ap.add_argument("--overlay-jpg", default=None, help="把 mask 叠到成片上，看高权重区落在哪")
    args = ap.parse_args()

    with rawpy.imread(args.dng) as r:
        raw = r.raw_image_visible.astype(np.float32)
        wb = r.camera_whitebalance
    gain = (np.array([float(x) for x in args.gain.split(",")], np.float32)
            if args.gain else np.array([wb[0], wb[1], wb[2]], np.float32))
    h, w = raw.shape
    yy, xx = np.mgrid[0:h, 0:w]
    ch = CFA_LUT_GRBG[((yy & 1) << 1) | (xx & 1)]

    lin = np.maximum((raw - BLACK) / (WHITE - BLACK), 0.0) * gain[ch]  # 同 hl-opposed-check
    sat = lin >= gain[ch]                                              # 真 RAW 饱和
    rgb = demosaic_bilinear(lin, ch)
    pix_max = np.sqrt((rgb ** 2).sum(-1)) * args.lsc_gain  # → 引擎域（含 LSC）

    n_sat = int(sat.sum())
    print(f"输入 {args.dng}")
    print(f"  尺寸 {w}×{h}  WB增益 {gain[0]:.4f}/{gain[1]:.4f}/{gain[2]:.4f}"
          f"  whiteEv {args.white_ev}  exposureEv {args.exposure_ev}  lscGain {args.lsc_gain}")
    print(f"  真 RAW 饱和 photosite: {n_sat} ({100.0 * n_sat / sat.size:.4f}%)")
    print(f"  去马赛克后 pix_max: 中位 {np.median(pix_max):.3f} "
          f"P99 {np.percentile(pix_max, 99):.3f} P99.9 {np.percentile(pix_max, 99.9):.3f} "
          f"max {pix_max.max():.3f}")

    cands = [("现状 threshold=1.0", 1.0),
             ("原版语义 = 白点（曝光前域）",
              (2.0 ** (args.white_ev - args.exposure_ev)) * GREY),
             ("数据饱和范数 sqrt(Σgain²)",
              float(np.sqrt((gain.astype(np.float64) ** 2).sum())))]
    for name, thr in cands:
        wmap = mask_weight(pix_max, thr)
        sig = 100.0 * float((wmap > 0.5).mean())
        any_ = 100.0 * float((wmap > 1.0 / 17.0).mean())
        blocks = block_count(wmap)
        # 真饱和像素在新阈值下是否仍被覆盖
        if n_sat:
            sw = wmap[sat]
            cover = f"真饱和 w: 中位 {np.median(sw):.3f} p10 {np.percentile(sw, 10):.3f}"
        else:
            cover = "真饱和 w: —（本图无）"
        print(f"  {name:>26s} threshold={thr:6.3f} | "
              f"w>1/17 {any_:5.2f}%  w>0.5 {sig:5.2f}%  块 {blocks:4d}/3072 | {cover}")

    if args.overlay_jpg:
        from PIL import Image
        base = Image.open(args.overlay_jpg).convert("RGB")
        base.thumbnail((1360, 1360))
        arr = np.asarray(base).astype(np.float32)
        for name, thr in cands:
            wmap = mask_weight(pix_max, thr)
            heat = np.asarray(
                Image.fromarray((np.clip(wmap, 0, 1) * 255).astype(np.uint8)).resize(base.size)
            ).astype(np.float32)[..., None] / 255.0
            out = arr * (1.0 - 0.75 * heat) + np.array([255.0, 0.0, 0.0], np.float32) * (0.75 * heat)
            tag = f"thr{thr:.2f}".replace(".", "p")
            path = args.overlay_jpg.rsplit(".", 1)[0] + f".mask-{tag}.png"
            Image.fromarray(out.astype(np.uint8)).save(path)
            print(f"  叠加图 → {path}")


if __name__ == "__main__":
    sys.exit(main())
