#!/usr/bin/env python3
"""T3 离线 A/B：DFT（kFsMfnrFft 镜像）vs 空间域 4 帧×3×3 加权融合。

为什么需要它（2026-11-12）：
  T3 提议用空间域加权融合替换 DFT（拍照链最大单项 1.2~1.5s）。要回答「是否影
  响画质」，必须让两种算法吃**同一组 4 帧**。真机 test_temp 里的 DNG 只是参考帧
  （CameraController 的 dngIdx=0），不是 burst，所以这里用单张 DNG 合成 burst：
    · 4 帧 = 同一张 DNG 的 4 个 CFA 平面图，各自加泊松-高斯噪声（σ²=A·x+B）；
    · 可选平面域整数位移（对齐由真机负责；离线只验证融合算法本身）。

判据（与真机日志同口径）：
  · 平坦区噪声 σ：越低越好；
  · 与 clean 的 PSNR：越高越好；
  · 边缘斜率（Sobel 高通能量）：越接近 clean 越好（融合不该把细节平均掉）。

用法：
  python3 tools/t3-offline-ab.py --dng test_temp/FILMKIT_20261112-091901.dng \
      --roi 512,512,1400,900 --noise-a 1.2e-4 --noise-b 7e-6 --shift 1,2,3
"""
import argparse
import importlib.util
import os
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_mirror():
    p = os.path.join(REPO, 'tools', 'fft-merge-mirror.py')
    spec = importlib.util.spec_from_file_location('fft_mirror', p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def read_raw_plane(path, meta_path, roi, black=64.0, white=1023.0):
    """读 0.13.14 导出的 .raw（R16UI plane，可能带 stride 填充）+ .meta。"""
    import json
    meta = json.load(open(meta_path))
    w, h, stride = meta['w'], meta['h'], meta['stridePx']
    data = np.fromfile(path, dtype=np.uint16)
    assert data.size == h * stride, (data.size, h, stride)
    m = data.reshape(h, stride)[:, :w].astype(np.float32)
    y0, y1, x0, x1 = roi
    m = m[y0:y1, x0:x1]
    bl = np.zeros_like(m)
    for oy in (0, 1):
        for ox in (0, 1):
            bl[oy::2, ox::2] = black
    return np.clip((m - bl) / (white - bl), 0.0, 1.0)


def read_plane(path, roi):
    """读 DNG → 指定 CFA 平面的 ROI（DNG CFA 是 RGBG/GRBG，按 mosaic 相位拆）。"""
    import rawpy
    with rawpy.imread(path) as r:
        m = r.raw_image_visible.astype(np.float32)
        black = np.array(r.black_level_per_channel, dtype=np.float32)  # R,G,B,G
        white = float(r.white_level)
    y0, y1, x0, x1 = roi
    m = m[y0:y1, x0:x1]
    # 黑阶/白阶归一 → [0,1]
    bl = np.zeros_like(m)
    for oy in (0, 1):
        for ox in (0, 1):
            # rawpy black_level_per_channel 顺序 = R, Gr, Gb, B（bayer 模式）
            bl[oy::2, ox::2] = black[(oy << 1) | ox] if (oy << 1 | ox) < 4 else black[1]
    return np.clip((m - bl) / (white - bl), 0.0, 1.0)


def split_planes(mosaic):
    """mosaic → 4 个平面（相位 (ox,oy) = (p&1, p>>1)），与 shader 的 plane 约定一致。"""
    return [mosaic[p >> 1::2, p & 1::2].copy() for p in range(4)]


def make_burst(clean, noise_a, noise_b, shifts, rng):
    """4 帧：整数平面域位移（坐标已知、无插值伪影）+ 泊松-高斯噪声。"""
    h, w = clean.shape
    frames = []
    for i, (dy, dx) in enumerate(shifts):
        base = np.roll(np.roll(clean, dy, axis=0), dx, axis=1)
        var = noise_a * base + noise_b
        frames.append(base + rng.standard_normal(base.shape) * np.sqrt(var))
    return frames


def spatial_fuse(frames, sigma2):
    """4 帧 × 3×3 同相位邻域 = 36 样本，双边加权（与 kFsRawDenoise 同构：
    w = exp(-0.5 d²/σ² - 0.5 r²/6.48)，d 相对参考帧局部均值）。"""
    h, w = frames[0].shape
    ref = frames[0]
    # 参考帧 3×3 同相位邻域均值（同相位 = 步长 1 的平面域邻域）
    acc = np.zeros_like(ref)
    wsum = np.zeros_like(ref)
    for f in frames:
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                shifted = np.roll(np.roll(f, dy, axis=0), dx, axis=1)
                d = (shifted - ref) / np.sqrt(np.maximum(sigma2, 1e-12))
                wgt = np.exp(-0.5 * d * d - 0.5 * (dy * dy + dx * dx) / 6.48)
                acc += wgt * shifted
                wsum += wgt
    return acc / wsum


def grad_mag(img):
    gx = np.zeros_like(img)
    gy = np.zeros_like(img)
    gx[:, 1:-1] = img[:, 2:] - img[:, :-2]
    gy[1:-1, :] = img[2:, :] - img[:-2, :]
    return np.abs(gx) + np.abs(gy)


def edge_energy(img):
    """Sobel 高通能量（细节保留的粗判据）。"""
    return float(np.mean(grad_mag(img)))


def flat_sigma(img, grad):
    """低梯度 20% 像素的局部 σ（用 8×8 块标准差的中位数）。"""
    h, w = img.shape
    bs = 8
    hs, ws = h // bs, w // bs
    blocks = img[:hs * bs, :ws * bs].reshape(hs, bs, ws, bs)
    sig = blocks.std(axis=(1, 3))
    gb = grad[:hs * bs, :ws * bs].reshape(hs, bs, ws, bs).mean(axis=(1, 3))
    thr = np.percentile(gb, 20)
    low = sig[gb <= thr]
    return float(np.median(low))


def phase_shift(a, b):
    """相位相关：b 相对 a 的整数位移（平面域）。"""
    a = a - a.mean()
    b = b - b.mean()
    win = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1]))
    A = np.fft.rfft2(a * win)
    B = np.fft.rfft2(b * win)
    R = A * np.conj(B)
    R /= (np.abs(R) + 1e-9)
    c = np.fft.irfft2(R, s=a.shape)
    dy, dx = np.unravel_index(np.argmax(c), c.shape)
    if dy > a.shape[0] // 2:
        dy -= a.shape[0]
    if dx > a.shape[1] // 2:
        dx -= a.shape[1]
    return int(dy), int(dx), float(c.max())


def psnr(a, b):
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    return 100.0 if mse <= 0 else 10.0 * np.log10(1.0 / mse)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', default='')
    ap.add_argument('--burst', default='', help='4 个 DNG 路径（逗号分隔，_f0.._f3）')
    ap.add_argument('--burst-raw', default='', help='0.13.14 导出的 .raw 目录（含 f0..f3.raw/.meta）')
    ap.add_argument('--hdr-fs', default='', help='逐帧曝光归一（日志 hdrFs，逗号分隔；帧 2/3 是 bracket 帧）')
    ap.add_argument('--roi', default='', help='y0,y1,x0,x1（mosaic 像素）')
    ap.add_argument('--noise-a', type=float, default=1.2e-4)
    ap.add_argument('--noise-b', type=float, default=7e-6)
    ap.add_argument('--shift', default='0,1,2,3', help='各帧平面域位移半径（px）')
    ap.add_argument('--seed', type=int, default=7)
    args = ap.parse_args()

    if args.roi:
        roi = tuple(int(v) for v in args.roi.split(','))
    else:
        roi = (1200, 1200 + 512, 1600, 1600 + 512)

    if args.burst_raw:
        import glob
        d = args.burst_raw
        paths = sorted(glob.glob(os.path.join(d, 'f*.raw')))
        assert len(paths) == 4, paths
        mosaics = [read_raw_plane(p, p.replace('.raw', '.meta'), roi) for p in paths]
        planes = [split_planes(m) for m in mosaics]
        mirror = load_mirror()
        fs = [float(v) for v in args.hdr_fs.split(',')] if args.hdr_fs else [1.0] * 4
        assert len(fs) == 4, '--hdr-fs 需要 4 个值'
        print('RAW burst：' + ', '.join(os.path.basename(x) for x in paths) + f' hdrFs={fs}')
        for p in range(4):
            ch = [pl[p] * fs[i] for i, pl in enumerate(planes)]
            shifts = [(0, 0)]
            aligned = [ch[0]]
            for f in ch[1:]:
                dy, dx, peak = phase_shift(ch[0], f)
                shifts.append((dy, dx))
                aligned.append(np.roll(np.roll(f, -dy, axis=0), -dx, axis=1))
            sigma2 = float(args.noise_a * ch[0].mean() + args.noise_b)
            dft, _ = mirror.merge_plane(aligned[0], aligned[1:], sigma2)
            sp = spatial_fuse(aligned, sigma2)
            avg = np.mean(np.stack(aligned, 0), axis=0)
            ref = aligned[0]
            gr = grad_mag(ref)
            e = edge_energy(ref)
            print(f'plane {p}: shifts={shifts}')
            print(f'  σ_flat ref={flat_sigma(ref, gr):.5f} dft={flat_sigma(dft, gr):.5f} '
                  f'spatial={flat_sigma(sp, gr):.5f} 4avg={flat_sigma(avg, gr):.5f}; '
                  f'edge ref={e:.5f} dft={edge_energy(dft):.5f} '
                  f'spatial={edge_energy(sp):.5f} 4avg={edge_energy(avg):.5f}')
            diff = np.abs(dft - sp)
            print(f'  |dft-spatial| mean={diff.mean():.5f} p99={np.percentile(diff, 99):.5f} '
                  f'max={diff.max():.5f}')
        return

    if args.burst:
        paths = args.burst.split(',')
        assert len(paths) == 4, '真实 burst 需要 4 个 DNG'
        mosaics = [read_plane(x, roi) for x in paths]
        planes = [split_planes(m) for m in mosaics]
        mirror = load_mirror()
        print('真实 burst：' + ', '.join(os.path.basename(x) for x in paths))
        for p in range(4):
            ch = [pl[p] for pl in planes]
            shifts = [(0, 0)]
            aligned = [ch[0]]
            for f in ch[1:]:
                dy, dx, peak = phase_shift(ch[0], f)
                shifts.append((dy, dx))
                aligned.append(np.roll(np.roll(f, -dy, axis=0), -dx, axis=1))
            sigma2 = float(args.noise_a * ch[0].mean() + args.noise_b)
            dft, _ = mirror.merge_plane(aligned[0], aligned[1:], sigma2)
            sp = spatial_fuse(aligned, sigma2)
            avg = np.mean(np.stack(aligned, 0), axis=0)
            ref = aligned[0]
            gr = grad_mag(ref)
            e = edge_energy(ref)
            print(f'plane {p}: shifts={shifts}')
            print(f'  σ_flat ref={flat_sigma(ref, gr):.5f} dft={flat_sigma(dft, gr):.5f} '
                  f'spatial={flat_sigma(sp, gr):.5f} 4avg={flat_sigma(avg, gr):.5f}; '
                  f'edge ref={e:.5f} dft={edge_energy(dft):.5f} '
                  f'spatial={edge_energy(sp):.5f} 4avg={edge_energy(avg):.5f}')
            diff = np.abs(dft - sp)
            print(f'  |dft-spatial| mean={diff.mean():.5f} p99={np.percentile(diff, 99):.5f} '
                  f'max={diff.max():.5f}')
        return

    mosaic = read_plane(args.dng, roi)
    planes = split_planes(mosaic)
    mirror = load_mirror()
    rng = np.random.default_rng(args.seed)
    shifts = []
    for s in [int(v) for v in args.shift.split(',')][:4]:
        shifts.append((s, s // 2))

    print(f'DNG {os.path.basename(args.dng)} ROI {roi} → 平面 {planes[0].shape}')
    for p, clean in enumerate(planes):
        h, w = clean.shape
        frames = make_burst(clean, args.noise_a, args.noise_b, shifts, rng)
        ref, alts = frames[0], frames[1:]
        sigma2 = float(args.noise_a * clean.mean() + args.noise_b)
        dft, _ = mirror.merge_plane(ref, alts, sigma2)
        # 对齐：平移回来的真值（merge 在位移后的坐标系；这里只比融合质量，
        # 位移帧与 clean 的错位由下方 roll 复原，避免把位移当成误差）
        # 逐帧回移以对齐到参考帧坐标系后再做空间域融合
        aligned = [ref]
        for (dy, dx), f in zip(shifts[1:], alts):
            aligned.append(np.roll(np.roll(f, -dy, axis=0), -dx, axis=1))
        sp = spatial_fuse(aligned, sigma2)
        dft_aligned = np.roll(np.roll(dft, -shifts[0][0], axis=0), -shifts[0][1], axis=1)
        # 参照 clean：把 clean 也按参考帧位移（shifts[0]）
        c = np.roll(np.roll(clean, shifts[0][0], axis=0), shifts[0][1], axis=1)
        gr = grad_mag(c)
        e = edge_energy(c)
        print(f'plane {p}: σ_flat(clean)={flat_sigma(c, gr):.5f} '
              f'σ_flat(ref)={flat_sigma(ref, gr):.5f} '
              f'σ_flat(dft)={flat_sigma(dft, gr):.5f} σ_flat(spatial)={flat_sigma(sp, gr):.5f}')
        print(f'         PSNR dft={psnr(dft, c):.2f}dB spatial={psnr(sp, c):.2f}dB '
              f'edge clean={e:.5f} dft={edge_energy(dft):.5f} spatial={edge_energy(sp):.5f}')


if __name__ == '__main__':
    main()
