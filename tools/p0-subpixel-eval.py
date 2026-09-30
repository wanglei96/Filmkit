#!/usr/bin/env python3
"""P0 亚像素采样收益评估（D-2 杠杆 B，2026-09-30）。

背景：kFsRawDenoiseFusedTs 取时域样本时把位移 **round 到最近偶数 mosaic px**
（`off = ivec2(floor(dz*0.5+0.5))*2`，保 CFA 相位），于是对齐精度提升到 0.27px
之后，采样仍带 ±1 mosaic px 量化。改成逐通道网格亚像素双线性（kFsMfnrFuse 里
的 linSub）能把量化再降一个量级，但每样本 1 次 texelFetch → 4 次插值采样。

本脚本在**同一批合成帧**上跑两种采样，隔离"量化"这一个变量：
  · 场景 = 真实 DNG 的 1/2 域线性灰度（2×2 Bayer 平均；该域上 +4/步长2 的
    5×5 同相位邻域正好等价于 mosaic 域 ±4/步长 2 的同相位网格）
  · 4 帧 = FFT 相移（精确亚像素位移，模拟手持）+ 独立高斯噪声
  · P0 权重与真机同式：w = exp(-0.5 d² - 0.5 r²/6.48)、d = (c-ref)/sr、
    sr = σ·√(neff+1)（neff = 4）
  · 方式 A（现状）= 位移 round 到整数采样；方式 B = 双线性亚像素采样
  · ref 各自用自己方式对齐后平均（公平）

口径：输出图在平坦块的**去信号噪声 σ**（同相位 lag-2 差分 std/√2，与真机
诊断 nzPost 同口径）+ 纹理块的局部对比度（P95−P5，防"降噪换纹理"）。

用法：python3 tools/p0-subpixel-eval.py [--dng test_temp/xxx.dng] [--sigma 0.004]
"""
import argparse
import pathlib

import numpy as np
import rawpy

BLOCK = 64          # 统计块
NEFF = 4.0


def load_lin_half(dng_path: str) -> np.ndarray:
    """DNG → 1/2 域线性灰度（与 align-bench-gen.py 同式）。"""
    with rawpy.imread(dng_path) as r:
        raw = r.raw_image.astype(np.float32)
        black_ch = np.array(r.black_level_per_channel, np.float32)  # R,Gr,Gb,B
        white = float(r.white_level)
        wb = np.array(r.camera_whitebalance[:3], np.float32)
        wb = wb / wb[1]
        bp = np.array([[black_ch[1], black_ch[0]], [black_ch[3], black_ch[2]]],
                      np.float32)
    h1, w1 = raw.shape[0] // 2, raw.shape[1] // 2
    blk = raw[: h1 * 2, : w1 * 2].reshape(h1, 2, w1, 2)
    b = bp.reshape(1, 2, 1, 2)
    gp = np.array([[wb[1], wb[0]], [wb[2], wb[1]]], np.float32).reshape(1, 2, 1, 2)
    lin = np.maximum((blk - b) / np.maximum(white - b, 1.0), 0.0) * gp
    return lin.mean(axis=(1, 3)).astype(np.float32)


def shift_fft(img, dx, dy, pad=64):
    p = np.pad(img, pad, mode="reflect")
    H, W = p.shape
    fy = np.fft.fftfreq(H)[:, None]
    fx = np.fft.fftfreq(W)[None, :]
    out = np.fft.ifft2(np.fft.fft2(p) * np.exp(-2j * np.pi * (fx * dx + fy * dy)))
    return out.real[pad:-pad, pad:-pad].astype(np.float32)


def sample_bilinear(img, ys, xs, dy, dx):
    """在 (y+dy, x+dx) 处双线性采样（dy/dx 可为亚像素；反射边界）。"""
    sy = ys + dy
    sx = xs + dx
    y0 = np.floor(sy).astype(np.int32)
    x0 = np.floor(sx).astype(np.int32)
    wy = (sy - y0).astype(np.float32)
    wx = (sx - x0).astype(np.float32)
    y0 = np.clip(y0, 0, img.shape[0] - 2)
    x0 = np.clip(x0, 0, img.shape[1] - 2)
    v = (img[y0, x0] * (1 - wy) * (1 - wx) + img[y0, x0 + 1] * wy * (1 - wx) +
         img[y0 + 1, x0] * (1 - wy) * wx + img[y0 + 1, x0 + 1] * wy * wx)
    return v.astype(np.float32)


def sample_nearest(img, ys, xs, dy, dx):
    y = np.clip(np.round(ys + dy).astype(np.int32), 0, img.shape[0] - 1)
    x = np.clip(np.round(xs + dx).astype(np.int32), 0, img.shape[1] - 1)
    return img[y, x]


def p0_fuse(frames, offs, ys, xs, sr, subpixel):
    """P0 的加权平均（真机同式）。off = (dy, dx) 该帧位移（1/2 域 px）。"""
    take = sample_bilinear if subpixel else sample_nearest
    ref = np.zeros_like(ys, np.float32)
    for z, (dy, dx) in enumerate(offs):
        ref += take(frames[z], ys, xs, dy, dx)
    ref /= len(offs)
    acc = ref.copy()
    wsum = np.ones_like(ref)
    for z, (dy, dx) in enumerate(offs):
        for ny in (-2, -1, 0, 1, 2):
            for nx in (-2, -1, 0, 1, 2):
                if nx == 0 and ny == 0:
                    continue
                c = take(frames[z], ys, xs, dy + ny, dx + nx)
                d = (c - ref) / sr
                w = np.exp(-0.5 * d * d - 0.5 * (nx * nx + ny * ny) / 6.48)
                acc += w * c
                wsum += w
    return acc / wsum, wsum


def nz_sigma(img):
    """去信号噪声 σ：同相位 lag-2 差分 std/√2（与真机 nzPost 同口径）。"""
    d = img[:, 2:] - img[:, :-2]
    return float(np.std(d) / np.sqrt(2.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dng", default="test_temp/FILMKIT_20260930-201634.dng")
    ap.add_argument("--sigma", type=float, default=0.004,
                    help="1/2 域线性噪声 σ（真机 nzPre≈0.004 即此档）")
    ap.add_argument("--roi", type=int, default=384)
    ap.add_argument("--seed", type=int, default=11)
    args = ap.parse_args()

    scene = load_lin_half(args.dng)
    # 干净真值：box 平滑去掉 DNG 自身的单帧噪声。帧 = 真值平移 + 独立噪声，
    # 于是"输出 − 真值"就是残余噪声 + 错位偏差，不再受信号泄漏干扰。
    k = 5
    pad = np.pad(scene, k // 2, mode="reflect")
    clean = np.zeros_like(scene)
    for dy in range(k):
        for dx in range(k):
            clean += pad[dy:dy + scene.shape[0], dx:dx + scene.shape[1]]
    clean /= k * k
    h, w = scene.shape
    y0 = h // 2 - args.roi // 2
    x0 = w // 2 - args.roi // 2

    # 4 帧：不同亚像素位移（模拟手持，含整像素与半像素成分）
    offs = [(1.30, -2.70), (-0.60, 1.90), (2.40, 0.80), (-1.10, -1.40)]
    rng = np.random.default_rng(args.seed)
    frames = [shift_fft(clean, dx, dy) for (dy, dx) in offs]
    frames = [f + rng.standard_normal(f.shape).astype(np.float32) * args.sigma
              for f in frames]

    ys, xs = np.mgrid[y0:y0 + args.roi, x0:x0 + args.roi].astype(np.float32)
    # 噪声模型用固定 sr（与真机 sr = σ√(neff+1) 一致；σ 用单帧估计）
    sr = args.sigma * float(np.sqrt(NEFF + 1))

    out_a, w_a = p0_fuse(frames, offs, ys, xs, sr, subpixel=False)
    out_b, w_b = p0_fuse(frames, offs, ys, xs, sr, subpixel=True)

    # 参考噪声底：不做任何邻域平均（只按位移取中心值）
    centro = np.zeros_like(ys)
    for z, (dy, dx) in enumerate(offs):
        centro += sample_bilinear(frames[z], ys, xs, dy, dx)
    centro /= len(offs)

    # 平坦/纹理块划分：用场景低频梯度（块内 std 低 = 平坦）
    lin = clean[y0:y0 + args.roi, x0:x0 + args.roi]  # 真值
    gy, gx = np.gradient(lin)
    grad = np.abs(gx) + np.abs(gy)
    bh = args.roi // BLOCK
    blocks = []
    for by in range(bh):
        for bx in range(bh):
            sl = (slice(by * BLOCK, (by + 1) * BLOCK), slice(bx * BLOCK, (bx + 1) * BLOCK))
            blocks.append((float(grad[sl].mean()), sl))
    # 按梯度分位数选块（绝对阈值在不同场景不可移植：桌面静物有墙，绿植场景没有）
    blocks.sort(key=lambda t: t[0])
    k = max(1, len(blocks) // 4)
    flat_ids = [sl for _, sl in blocks[:k]]
    tex_ids = [sl for _, sl in blocks[-k:]]
    print(f"ROI {args.roi}², σ={args.sigma}, 位移 {offs}")
    print(f"块数: 平坦 {len(flat_ids)}, 纹理 {len(tex_ids)}")

    def stat(outs, tag):
        errs, biases, cons = [], [], []
        for sl in flat_ids:
            e = outs[sl] - lin[sl]
            errs.append(float(np.std(e)))
            biases.append(float(np.mean(e)))
        for sl in tex_ids:
            cons.append(float(np.percentile(outs[sl], 95) - np.percentile(outs[sl], 5)))
        ct = [float(np.percentile(lin[sl], 95) - np.percentile(lin[sl], 5)) for sl in tex_ids]
        print(f"  {tag}: 平坦块残余 σ 中位 {np.median(errs):.5f} | 偏差 {np.median(biases):+.5f}"
              f" | 纹理对比度 {np.median(cons):.5f} (真值 {np.median(ct):.5f},"
              f" 保留 {np.median(cons)/max(np.median(ct),1e-9):.3f})")
    print("结果:")
    stat(centro, "仅4帧中心(无邻域)")
    stat(out_a, "整数采样(A, 现状)")
    stat(out_b, "亚像素采样(B, 候选)")

    def wsum_med(ws):
        return float(np.median([float(np.median(ws[sl])) for sl in flat_ids])) if flat_ids else -1
    print(f"  平坦块权重和 wsum 中位: A {wsum_med(w_a):.2f} / B {wsum_med(w_b):.2f} (上限 97)")


if __name__ == "__main__":
    main()
