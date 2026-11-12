#!/usr/bin/env python3
"""T3 运动边界模拟：现有 mismatch 权重 vs 逐像素 rejection。

背景（2026-11-12）：
  现有空间域链路 = passMfnrMismatch（32px cell 残差）+ 5×5 膨胀（64px 带）
  + LINEAR 上采样 + kFsMfnrFuse。运动物体边缘因此有 32~64px 的「回退参考帧」
  过渡带；PC 的逐像素 rejection 是 2~3px。本脚本用真实静止 burst 注入已知
  局部平移（模拟一个正在移动的物体），量化两条权重路径的过渡带宽度与重影。

用法：
  python3 tools/t3-motion-sim.py --burst-raw /tmp/fk_burst --roi 1200,1712,1500,2012 \
      --move 128,128,30 && open /tmp/t3motion/edge_compare.png
"""
import argparse
import json
import os

import numpy as np
from PIL import Image, ImageDraw

CELL = 32          # mismatch 权重 cell（tiles_w_*4，mosaic 域）
DILATE = 2         # 5×5 膨胀半径（cell）


def load_gray(raw, meta, roi):
    m = json.load(open(meta))
    w, h, stride = m['w'], m['h'], m['stridePx']
    d = np.fromfile(raw, dtype=np.uint16).reshape(h, stride)[:, :w].astype(np.float32)
    y0, y1, x0, x1 = roi
    d = d[y0:y1, x0:x1]
    return (d - 64.0) / (1023.0 - 64.0)


def dilate_max(a, r):
    """(2r+1)² 最大池化（cell 级）。"""
    out = a.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            sh = np.full_like(a, -1.0)
            ys = slice(max(0, dy), a.shape[0] + min(0, dy))
            xs = slice(max(0, dx), a.shape[1] + min(0, dx))
            yd = slice(max(0, -dy), a.shape[0] + min(0, -dy))
            xd = slice(max(0, -dx), a.shape[1] + min(0, -dx))
            sh[yd, xd] = a[ys, xs]
            out = np.maximum(out, sh)
    return out


def bilinear_upsample(cells, h, w):
    """cell 级 → 像素级双线性（与 GL LINEAR 采样同构，cell 中心对齐）。"""
    ch, cw = cells.shape
    ys = (np.arange(h) + 0.5) * ch / h - 0.5
    xs = (np.arange(w) + 0.5) * cw / w - 0.5
    y0 = np.clip(np.floor(ys).astype(int), 0, ch - 1)
    x0 = np.clip(np.floor(xs).astype(int), 0, cw - 1)
    y1 = np.clip(y0 + 1, 0, ch - 1)
    x1 = np.clip(x0 + 1, 0, cw - 1)
    fy = np.clip(ys - y0, 0, 1)[:, None]
    fx = np.clip(xs - x0, 0, 1)[None, :]
    return (cells[np.ix_(y0, x0)] * (1 - fy) * (1 - fx) +
            cells[np.ix_(y1, x0)] * fy * (1 - fx) +
            cells[np.ix_(y0, x1)] * (1 - fy) * fx +
            cells[np.ix_(y1, x1)] * fy * fx)


def cell_residual(ref, alts, cell=CELL):
    """现有 mismatch：每 cell 内 4 帧残差（|alt-ref| 的 cell 均值），先膨胀后双线性。"""
    h, w = ref.shape
    ch, cw = h // cell, w // cell
    res = np.zeros((ch, cw), np.float32)
    for a in alts:
        d = np.abs(a - ref)
        dd = d[:ch * cell, :cw * cell].reshape(ch, cell, cw, cell).mean(axis=(1, 3))
        res = np.maximum(res, dd)
    return res


def fuse_weighted(ref, alts, wmap):
    """kFsMfnrFuse 的加权：w0=1、alt 权重 = 1 - wmap（wmap=1 表示运动 → 回退参考）。"""
    acc = ref.copy()
    wsum = np.ones_like(ref)
    for a in alts:
        w = 1.0 - wmap
        acc += w * a
        wsum += w
    return acc / wsum


def per_pixel_reject(ref, alts, sigma, k=1.0):
    """逐像素 rejection：w = exp(-0.5 (d/σ)²)（PC 式软拒绝的简化）。"""
    acc = ref.copy()
    wsum = np.ones_like(ref)
    for a in alts:
        d = (a - ref) / sigma
        w = np.exp(-0.5 * np.minimum(d * d, 400.0))
        acc += w * a
        wsum += w
    return acc / wsum


def profile(img, axis, x):
    """沿 axis 过 x 的亮度剖面（用于量过渡带宽度）。"""
    return img[:, x] if axis == 0 else img[x, :]


def edge_width(prof, lo, hi, frac=0.9):
    """10%→90% 过渡宽度（像素）。"""
    p = prof.astype(np.float64)
    lo_v, hi_v = np.percentile(p, 5), np.percentile(p, 95)
    a = lo_v + (hi_v - lo_v) * 0.1
    b = lo_v + (hi_v - lo_v) * frac
    idx = np.where((p >= a) & (p <= b))[0]
    return int(idx[-1] - idx[0]) if len(idx) > 1 else -1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--burst-raw', required=True)
    ap.add_argument('--roi', default='', help='mosaic y0,y1,x0,x1（空 = 自动选纹理最丰富的 512 窗口）')
    ap.add_argument('--move', default='128,128,30', help='物体 w,h,位移px')
    ap.add_argument('--out', default='/tmp/t3motion')
    args = ap.parse_args()
    if args.roi:
        roi = tuple(int(v) for v in args.roi.split(','))
    else:
        # 自动选：全图 512×512 窗口里梯度能量最高的（避免落在灰面）
        base = load_gray(os.path.join(args.burst_raw, 'f0.raw'),
                         os.path.join(args.burst_raw, 'f0.meta'), (0, 3072, 0, 4080))
        bs = 512
        best, best_roi = -1.0, (0, bs, 0, bs)
        for y in range(0, base.shape[0] - bs, 256):
            for x in range(0, base.shape[1] - bs, 256):
                e = np.abs(np.diff(base[y:y+bs, x:x+bs], axis=1)).mean()
                if e > best:
                    best, best_roi = e, (y, y + bs, x, x + bs)
        roi = best_roi
        print(f'自动 ROI {roi} 梯度 {best:.4f}')
    mw, mh, mshift = (int(v) for v in args.move.split(','))
    os.makedirs(args.out, exist_ok=True)

    frames = [load_gray(os.path.join(args.burst_raw, f'f{i}.raw'),
                        os.path.join(args.burst_raw, f'f{i}.meta'), roi)
              for i in range(4)]
    h, w = frames[0].shape
    ref = frames[0]
    # 注入运动：ref 的一个矩形区域「移动」，等价于 alt 帧里同一块内容反向平移。
    # 做法：把 alt 帧中央区域裁掉并贴到偏移位置（边界用原内容填充），保持噪声。
    y0, x0 = (h - mh) // 2, (w - mw) // 2
    alts = []
    for f in frames[1:]:
        g = f.copy()
        patch = f[y0:y0 + mh, x0:x0 + mw].copy()
        g[y0:y0 + mh, x0:x0 + mw] = f[y0:y0 + mh, x0 + mshift:x0 + mshift + mw]
        alts.append(g)

    # 噪声 σ：帧差估计
    sigma = float(np.std(frames[1] - frames[0])) or 0.004
    res_cell = dilate_max(cell_residual(ref, alts), DILATE)
    wmap = bilinear_upsample(res_cell, h, w)
    wmap = np.clip(wmap / (sigma * 2.0), 0, 1)   # 残差 → 0..1 权重
    out_mis = fuse_weighted(ref, alts, wmap)
    out_pp = per_pixel_reject(ref, alts, sigma)

    # 过渡带：过运动块中心行，量 10%→90% 宽度
    row = h // 2
    ew_ref = edge_width(ref[row], 0, 1)
    ew_mis = edge_width(out_mis[row], 0, 1)
    ew_pp = edge_width(out_pp[row], 0, 1)
    # 重影强度：输出与参考帧在运动块内的差异
    box = (slice(y0, y0 + mh), slice(x0, x0 + mw))
    ghost_mis = float(np.abs(out_mis[box] - ref[box]).mean())
    ghost_pp = float(np.abs(out_pp[box] - ref[box]).mean())
    ghost_avg = float(np.abs(np.mean(np.stack(alts, 0), axis=0)[box] - ref[box]).mean())
    print(f'ROI {roi} 物体 {mw}x{mh} 位移 {mshift}px  σ={sigma*1024:.2f} LSB')
    print(f'过渡带宽度: ref={ew_ref}px  mismatch={ew_mis}px  per-pixel={ew_pp}px')
    print(f'运动块内 |out-ref|: mismatch={ghost_mis*1024:.2f}  per-pixel={ghost_pp*1024:.2f} '
          f'朴素平均={ghost_avg*1024:.2f} LSB')

    # 对比图：ref / mismatch / per-pixel / diff×8
    def img(x):
        return Image.fromarray((np.clip(x, 0, 1) ** 0.45 * 255).astype(np.uint8)).convert('RGB')
    d1 = np.clip(np.abs(out_mis - out_pp) * 8, 0, 1)
    canvas = Image.new('RGB', (w * 2 + 12, h * 2 + 12), (0, 0, 0))
    canvas.paste(img(ref), (0, 0))
    canvas.paste(img(out_mis), (w + 12, 0))
    canvas.paste(img(out_pp), (0, h + 12))
    canvas.paste(img(d1), (w + 12, h + 12))
    dr = ImageDraw.Draw(canvas)
    dr.text((4, 4), 'REF (mosaic, gamma)', fill=(255, 255, 255))
    dr.text((w + 16, 4), 'mismatch 32px+64px dilate', fill=(255, 255, 255))
    dr.text((4, h + 16), 'per-pixel rejection', fill=(255, 255, 255))
    dr.text((w + 16, h + 16), 'DIFF x8', fill=(255, 255, 255))
    p = os.path.join(args.out, 'edge_compare.png')
    canvas.save(p)
    print('saved', p)


if __name__ == '__main__':
    main()
