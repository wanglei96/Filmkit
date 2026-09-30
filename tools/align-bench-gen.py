#!/usr/bin/env python3
"""对齐离线基准 · 输入生成（D-2 对齐精度专项，2026-09-30）。

用真实 DNG 生成与 GPU 侧**逐式一致**的对齐金字塔，再施加**精确已知**的
亚像素位移场，交给 tools/align-bench.cpp（链接真实 Align.cpp）测位移估计误差。

复刻的 GPU 侧算式（core/engine/src/render/FrameRenderer.cpp）：
  kFsMfnrPyr  : lin = max((raw-bl)/(white-bl),0)*gain[chan]
                2×2 平均 → clamp[0,1] → sqrt → RGBA8（round(enc*255)）
  kFsMfnrDown : v = sqrt(mean(a.r², b.r², c.r², d.r²))（sqrt 域进、线性域平均、
                sqrt 域出，输入是已量化的 u8 归一值）

位移施加在**线性 1/2 域灰度**上（FFT 相移，无插值模糊），因此真值精确已知；
噪声也在此域加（等效 raw 域 σ_raw = 2·σ_half，2×2 平均降 √4）。

输出 .alb 二进制（小端）：
  magic "ALB1" | int32 nframes(=2)
  每帧: int32 w1,h1,w2,h2,w3,h3,w4,h4 | u8 l1 | u8 l2 | u8 l3 | u8 l4
  int32 tiles_x,tiles_y | float32 truth[ty*tx*2]（mosaic 域 = 1/2 域 ×2）
"""
import argparse
import os
import struct

import numpy as np
import rawpy


def load_lin_half(dng_path):
    """DNG → 1/2 域线性灰度（2×2 平均）+ 量化前的精确线性图。"""
    with rawpy.imread(dng_path) as r:
        raw = r.raw_image.astype(np.float32)
        black_ch = np.array(r.black_level_per_channel, dtype=np.float32)  # R,Gr,Gb,B
        white = float(r.white_level)
        wb = np.array(r.camera_whitebalance[:3], dtype=np.float32)  # R,G,B
        wb = wb / wb[1]
        # GPU 的 uBlack 索引 = (y&1)*2 + (x&1)；rawpy 通道序 R,Gr,Gb,B
        black_phase = np.array(
            [[black_ch[1], black_ch[0]], [black_ch[3], black_ch[2]]], dtype=np.float32
        )
    H, W = raw.shape
    h1, w1 = H // 2, W // 2
    blk = raw[: h1 * 2, : w1 * 2].reshape(h1, 2, w1, 2)
    bp = black_phase.reshape(1, 2, 1, 2)  # 广播到 (h1,2,w1,2)
    # 通道增益：与 GPU gainAt(chanAt(p)) 同序（R/G/B 三值）
    gp = np.empty((2, 2), dtype=np.float32)
    gp[0, 0] = wb[1]  # Gr
    gp[0, 1] = wb[0]  # R
    gp[1, 0] = wb[2]  # B
    gp[1, 1] = wb[1]  # Gb
    gain = gp.reshape(1, 2, 1, 2)
    lin = np.maximum((blk - bp) / np.maximum(white - bp, 1.0), 0.0) * gain
    return lin.mean(axis=(1, 3)).astype(np.float32)  # (h1, w1) 线性


def shift_lin(img, dx, dy, pad=96):
    """FFT 相移：返回 f(x-dx, y-dy)（无插值模糊，reflect pad 抑制 wrap）。"""
    p = np.pad(img, pad, mode="reflect")
    H, W = p.shape
    fy = np.fft.fftfreq(H)[:, None]
    fx = np.fft.fftfreq(W)[None, :]
    ph = np.exp(-2j * np.pi * (fx * dx + fy * dy))
    out = np.fft.ifft2(np.fft.fft2(p) * ph).real
    return out[pad:-pad, pad:-pad].astype(np.float32)


def rot_shift_field(h1, w1, ang_deg, tx, ty):
    """绕图心的旋转 + 平移，返回每像素位移场 (ux, uy)（1/2 域 px）。"""
    a = np.deg2rad(ang_deg)
    yy, xx = np.mgrid[0:h1, 0:w1].astype(np.float32)
    cy, cx = (h1 - 1) / 2.0, (w1 - 1) / 2.0
    x = xx - cx
    y = yy - cy
    ux = (np.cos(a) - 1.0) * x - np.sin(a) * y + tx
    uy = np.sin(a) * x + (np.cos(a) - 1.0) * y + ty
    return ux, uy


def warp_lin(img, ux, uy, pad=96):
    """按位移场做一次双线性 warp（局部形变用；全局平移走 FFT 相移更准）。"""
    h, w = img.shape
    p = np.pad(img, pad, mode="reflect")
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    sx = xx - ux + pad
    sy = yy - uy + pad
    x0 = np.floor(sx).astype(np.int32)
    y0 = np.floor(sy).astype(np.int32)
    wx = (sx - x0).astype(np.float32)
    wy = (sy - y0).astype(np.float32)
    x0 = np.clip(x0, 0, p.shape[1] - 2)
    y0 = np.clip(y0, 0, p.shape[0] - 2)
    v = (
        p[y0, x0] * (1 - wx) * (1 - wy)
        + p[y0, x0 + 1] * wx * (1 - wy)
        + p[y0 + 1, x0] * (1 - wx) * wy
        + p[y0 + 1, x0 + 1] * wx * wy
    )
    return v.astype(np.float32)


def encode_l1(lin):
    return np.round(np.sqrt(np.clip(lin, 0.0, 1.0)) * 255.0).astype(np.uint8)


def down_u8(a):
    """kFsMfnrDown：sqrt 域进、线性域 2×2 平均、sqrt 域出（含 u8 量化）。"""
    h, w = a.shape
    p = np.pad(a, ((0, h % 2), (0, w % 2)), mode="edge").astype(np.float32) / 255.0
    hh, ww = p.shape[0] // 2, p.shape[1] // 2
    q = p[: hh * 2, : ww * 2].reshape(hh, 2, ww, 2)
    v = np.sqrt((q * q).mean(axis=(1, 3)))
    return np.round(np.clip(v, 0.0, 1.0) * 255.0).astype(np.uint8)


def pyramid_lin(lin, smooth=0):
    """线性图 → 完整金字塔 u8（L1..L4），与 GPU 侧链一致。

    smooth>0 时在 L1（sqrt 域 u8）上做 N×N 盒式平滑后再下采样 —— 模拟"金字塔
    层预平滑"（HDR+ 的高斯金字塔本身带平滑；我们的 kFsMfnrDown 是纯 2×2 盒式，
    层上残留高频噪声会喂给 SAD）。该 pass 在 GPU 上是一次全屏读写，SAD 成本不变。
    """
    l1 = encode_l1(lin)
    if smooth > 1:
        p = smooth // 2
        a = np.pad(l1.astype(np.float32), p, mode="edge")
        acc = np.zeros_like(l1, dtype=np.float32)
        for dy in range(smooth):
            for dx in range(smooth):
                acc += a[dy : dy + l1.shape[0], dx : dx + l1.shape[1]]
        l1 = np.round(acc / (smooth * smooth)).astype(np.uint8)
    l2 = down_u8(l1)
    l3 = down_u8(l2)
    l4 = down_u8(l3)
    return l1, l2, l3, l4


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--shift", nargs=2, type=float, default=[2.4, -3.6],
                    help="全局平移（1/2 域 px）")
    ap.add_argument("--rot", type=float, default=0.0, help="旋转角（度，绕图心）")
    ap.add_argument("--noise", nargs="+", type=float, default=[0.0, 0.002, 0.008],
                    help="1/2 域线性域噪声 σ（多档，各写一个 .alb）")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--tag", default="bench")
    ap.add_argument("--tile", type=int, default=64, help="1/2 域 tile（= kMfnrTile）")
    ap.add_argument("--smooth", type=int, default=0,
                    help="L1 层 N×N 盒式预平滑（0=不平滑，模拟 GPU 侧新 pass）")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    rng = np.random.default_rng(args.seed)
    ref_lin = load_lin_half(args.dng)
    h1, w1 = ref_lin.shape
    tx, ty = (w1 + args.tile - 1) // args.tile, (h1 + args.tile - 1) // args.tile

    if args.rot:
        ux, uy = rot_shift_field(h1, w1, args.rot, args.shift[0], args.shift[1])
        cur_lin = warp_lin(ref_lin, ux, uy)
    else:
        ux = np.full((h1, w1), args.shift[0], dtype=np.float32)
        uy = np.full((h1, w1), args.shift[1], dtype=np.float32)
        cur_lin = shift_lin(ref_lin, args.shift[0], args.shift[1])

    # tile 中心真值（mosaic 域 = 1/2 域 ×2）——对齐输出的 off 即"cur→ref"位移
    truth = np.zeros((ty, tx, 2), dtype=np.float32)
    for j in range(ty):
        for i in range(tx):
            x = min(i * args.tile + args.tile // 2, w1 - 1)
            y = min(j * args.tile + args.tile // 2, h1 - 1)
            truth[j, i, 0] = ux[y, x] * 2.0
            truth[j, i, 1] = uy[y, x] * 2.0

    for sig in args.noise:
        ref_n = ref_lin + (rng.standard_normal(ref_lin.shape).astype(np.float32) * sig
                           if sig > 0 else 0.0)
        cur_n = cur_lin + (rng.standard_normal(cur_lin.shape).astype(np.float32) * sig
                           if sig > 0 else 0.0)
        rr = pyramid_lin(ref_n, args.smooth)
        cc = pyramid_lin(cur_n, args.smooth)
        name = f"{args.tag}_s{sig:g}.alb"
        path = os.path.join(args.out, name)
        with open(path, "wb") as f:
            f.write(b"ALB1")
            f.write(struct.pack("<i", 2))
            for pyr in (rr, cc):
                dims = []
                for layer in pyr:
                    dims += [layer.shape[1], layer.shape[0]]
                f.write(struct.pack("<8i", *dims))
                for layer in pyr:
                    f.write(layer.tobytes())
            f.write(struct.pack("<2i", tx, ty))
            f.write(truth.astype("<f4").tobytes())
        print(f"wrote {path}  ref={w1}x{h1} tiles={tx}x{ty} "
              f"shift=({args.shift[0]},{args.shift[1]}) rot={args.rot} sigma={sig:g}")


if __name__ == "__main__":
    main()
