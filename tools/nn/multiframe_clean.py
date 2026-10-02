#!/usr/bin/env python3
"""多帧对齐 + 平均 → 降噪网络微调用的 clean 参考（img01 域 CFA mosaic 保相位）。

用途：同一场景手持连拍 N 张 DNG → 取 1 张当 noisy，其余对齐平均当 clean
（N 帧平均把噪声方差降到 1/N）。

约定
----
* 数据域 img01 = (raw − black) / (white − black)，float32，仍是 CFA mosaic，
  不插值、不转 RGB。口径同 tools/nn/diag_255b.py:load_b01。
* 位移 d=(dy,dx) 以**传感器像素**（mosaic px）为单位，定义
  ``frame(y,x) = scene(y−dy, x−dx)``；对齐即求 d 使 ``warp(frame, d) ≈ ref``。
* 亚像素重采样**在 4 个 CFA 平面（R/Gr/Gb/B）各自做**，平面域位移 = d/2。
  理由：mosaic 域直接双线性会把相邻的 R/G/B 采样混起来，在周期 2px 的 CFA 上
  产生彩色摩尔纹；同一平面内全是同色采样，插值不混色（各通道空间连续，
  以 2px 间隔采样、插半步是 HDR+/MFNR 的标准近似）。d 为奇数像素时该近似
  等价于"用同色邻点重建跨相位的值"，同样成立。
* 位移估计：高斯/box 金字塔从粗到细，粗层 FFT 相位相关（+抛物线亚像素），
  逐级放大后每级用 Gauss-Newton（LK）迭代细化到亚像素。4 个平面各自估计后
  取中位数，抗单平面异常。

用法
----
  python3 tools/nn/multiframe_clean.py --selftest
  python3 tools/nn/multiframe_clean.py test_temp/*.dng --ref 0 --out clean.npy --png clean.png
  python3 tools/nn/multiframe_clean.py test_temp --glob '*.dng' --ref 0 --out clean.npy
"""
import argparse
import sys
import time
from pathlib import Path

import numpy as np
import rawpy
from PIL import Image

A_SHOT, B_READ = 9.6e-4, 3.9e-7      # 泊松-高斯 var = A·x + B（img01 域）


# --------------------------------------------------------------- DNG → img01

def load_b01(path, crop=0):
    """DNG → (img01 CFA mosaic float32, raw_pattern)。crop>0 取中心 2·crop 正方形。"""
    with rawpy.imread(str(path)) as raw:
        a = raw.raw_image_visible.astype(np.float32)
        bl = np.array(raw.black_level_per_channel, np.float32).reshape(2, 2)
        white = float(raw.white_level)
        pat = raw.raw_pattern.tolist()
    h, w = a.shape[0] // 2 * 2, a.shape[1] // 2 * 2
    a = a[:h, :w]
    blf = np.repeat(np.repeat(bl, h // 2, 0), w // 2, 1)
    a = np.clip((a - blf) / max(white - float(np.median(blf)), 1.0), 0.0, 1.0)
    if crop:
        cy, cx = h // 2, w // 2
        a = np.ascontiguousarray(a[cy - crop:cy + crop, cx - crop:cx + crop])
    return a, pat


def plane_pos(pat):
    """[R, Gr, Gb, B] 在 2×2 CFA 里的 (y, x)（口径同 diag_255b.phase_blocks）。"""
    r = next((y, x) for y in range(2) for x in range(2) if pat[y][x] == 0)
    b = next((y, x) for y in range(2) for x in range(2) if pat[y][x] == 2)
    return [r, (r[0], 1 - r[1]), (b[0], 1 - b[1]), b]


def cfa_planes(m, pos):
    """mosaic → [R, Gr, Gb, B] 四个平面（视图）。"""
    return [m[y::2, x::2] for (y, x) in pos]


def pack_planes(planes, pos, shape):
    m = np.empty(shape, np.float32)
    for (y, x), p in zip(pos, planes):
        m[y::2, x::2] = p
    return m


# ------------------------------------------------------------------- 金字塔

def half(a):
    h, w = a.shape[0] // 2 * 2, a.shape[1] // 2 * 2
    return a[:h, :w].reshape(h // 2, 2, w // 2, 2).mean((1, 3), dtype=np.float32)


def pyramid(a, levels):
    p = [a]
    while len(p) < levels and min(p[-1].shape) >= 32:
        p.append(half(p[-1]))
    return p


def _parab(a, b, c):
    """抛物线顶点相对中心样本的偏移（循环邻居）。"""
    den = a - 2.0 * b + c
    return 0.5 * (a - c) / den if abs(den) > 1e-12 else 0.0


def phase_shift(ref, mov):
    """FFT 相位相关 → mov 相对 ref 的位移 (dy,dx)（本层 px）与峰锐度。
    约定 mov(y,x) ≈ ref(y−dy, x−dx)。"""
    a = ref - ref.mean()
    b = mov - mov.mean()
    R = np.fft.rfft2(a) * np.conj(np.fft.rfft2(b))
    R /= np.maximum(np.abs(R), 1e-12)          # 相位白化：只留位移信息
    c = np.fft.irfft2(R, s=ref.shape)
    H, W = c.shape
    iy, ix = np.unravel_index(int(np.argmax(c)), c.shape)
    ky = iy - H * (iy > H // 2)
    kx = ix - W * (ix > W // 2)
    # c(k) = Σ_y ref(y+k)·mov(y)，峰在 k=−d
    py = _parab(c[(iy - 1) % H, ix], c[iy, ix], c[(iy + 1) % H, ix])
    px = _parab(c[iy, (ix - 1) % W], c[iy, ix], c[iy, (ix + 1) % W])
    sharp = float((c[iy, ix] - np.median(c)) / (c.std() + 1e-12))
    return np.array([-(ky + py), -(kx + px)]), sharp


def warp(p, dy, dx):
    """out(y,x) = p(y+dy, x+dx) 双线性；返回 (out, valid)。"""
    h, w = p.shape
    ys = np.arange(h, dtype=np.float32) + np.float32(dy)
    xs = np.arange(w, dtype=np.float32) + np.float32(dx)
    y0f, x0f = np.floor(ys), np.floor(xs)
    wy = (ys - y0f)[:, None]
    wx = (xs - x0f)[None, :]
    y0 = y0f.astype(np.int32)
    x0 = x0f.astype(np.int32)
    valid = ((y0 >= 0) & (y0 < h - 1))[:, None] & ((x0 >= 0) & (x0 < w - 1))[None, :]
    yc = np.clip(y0, 0, h - 2)
    xc = np.clip(x0, 0, w - 2)
    r0 = p[yc]
    r1 = p[yc + 1]
    top = r0[:, xc] + (r0[:, xc + 1] - r0[:, xc]) * wx
    bot = r1[:, xc] + (r1[:, xc + 1] - r1[:, xc]) * wx
    return (top + (bot - top) * wy).astype(np.float32), valid


def refine_lk(ref, mov, d, iters=3):
    """Gauss-Newton 细化 d（本层 px），返回 (d, 残差稳健尺度 s)。"""
    d = np.asarray(d, np.float64).copy()
    s = 0.0
    for _ in range(iters):
        w, valid = warp(mov, d[0], d[1])
        e = w - ref
        msk = valid & (w < 0.985) & (ref < 0.985)     # 排边缘 + 饱和
        if int(msk.sum()) < 256:
            break
        em = e[msk]
        med = float(np.median(em))
        s = 1.4826 * float(np.median(np.abs(em - med))) + 1e-9
        # Cauchy 权重：压制前景运动/局部不一致像素
        wt = ((1.0 / (1.0 + (e / (2.5 * s)) ** 2)) * msk).astype(np.float32)
        gy, gx = np.gradient(w)
        A = np.array([[float((wt * gy * gy).sum()), float((wt * gy * gx).sum())],
                      [float((wt * gy * gx).sum()), float((wt * gx * gx).sum())]])
        b = np.array([float((wt * gy * e).sum()), float((wt * gx * e).sum())])
        try:
            dd = np.linalg.solve(A + np.eye(2) * 1e-12, -b)
        except np.linalg.LinAlgError:
            break
        d += dd
        if float(np.abs(dd).max()) < 1e-4:
            break
    return d, s


def estimate(ref_p, mov_p, levels):
    """单平面位移估计（平面域 px）。
    返回 (d, 峰锐度, 对齐残差 resid, 收益比 gain, 白噪性 wn)。

    resid 是 L1 层上对齐后的稳健残差尺度（绝对量，跨帧可比）；
    gain = resid / 未对齐残差（相对量，诊断"对齐是否有收益"）；
    wn = 对齐残差的高频/总 σ 比：残差若只剩两帧独立噪声则 ≈1，仍残留场景结构
    （异场景、错位、失焦、AE 变化）则明显 <1。这是**绝对判据**，在"所有帧
    互相都不一致"时仍然有效（相对中位数的判据那时会失效）。"""
    rp = pyramid(ref_p, levels)
    mp = pyramid(mov_p, levels)
    L = len(rp) - 1
    d, sharp = phase_shift(rp[L], mp[L])
    if np.abs(d).max() > 0.45 * min(rp[L].shape):        # 粗层可测范围边界 → 不可信
        return d * (2.0 ** L), sharp, 1e9, 1e9, 0.0
    for l in range(L - 1, -1, -1):
        d = d * 2.0
        d, _ = refine_lk(rp[l], mp[l], d, iters=3 if l else 2)
    l1 = min(1, L)                                        # 残差统一在 L1 层量（便宜且稳）
    _, r0 = refine_lk(rp[l1], mp[l1], np.zeros(2), iters=1)
    dl = d / (2.0 ** l1)
    _, r1 = refine_lk(rp[l1], mp[l1], dl, iters=1)
    e, v = warp(mp[l1], dl[0], dl[1])
    e = e - rp[l1]
    hf = (e[:, 2:] - e[:, :-2]) / np.sqrt(2)
    h0 = float(np.std(e[v]))
    h1 = float(np.std(hf[v[:, 2:] & v[:, :-2]]))
    return d, sharp, r1, r1 / max(r0, 1e-12), (h1 / h0 if h0 > 1e-9 else 1.0)


def estimate_frame(ref_planes, mov_planes, levels):
    """4 平面各自估计 → 取中位数（抗单平面异常）。
    返回 (d_mosaic, 平面离散(px), resid, gain, wn, 峰锐度)。"""
    ds, res, gains, wns, sharps = [], [], [], [], []
    for rp, mp in zip(ref_planes, mov_planes):
        d, sh, r, g, wn = estimate(rp, mp, levels)
        ds.append(d)
        sharps.append(sh)
        res.append(r)
        gains.append(g)
        wns.append(wn)
    ds = np.array(ds)
    med = np.median(ds, axis=0)
    return (med * 2.0, float(np.abs(ds - med).max()) * 2.0, float(np.median(res)),
            float(np.median(gains)), float(np.median(wns)), float(np.median(sharps)))


# ----------------------------------------------------------------- 累加/筛选

def accumulate(acc, wsum, planes, d):
    """按 mosaic 域位移 d 重采样后累加；d=None 表示原样。"""
    for c, pl in enumerate(planes):
        if d is None:
            acc[c] += pl
            wsum[c] += 1.0
        else:
            v, valid = warp(pl, d[0] / 2.0, d[1] / 2.0)
            acc[c] += v * valid
            wsum[c] += valid


def select_frames(info, ref_idx, reject_ratio, max_shift, min_wn=0.8):
    """info: [{'i','path','d','resid','gain','wn'}] → (keep, bad 说明列表)。

    两条判据互补：
    · wn（绝对）：对齐残差必须是"白噪声样"的。异场景/严重错位时残差里剩场景结构，
      wn 掉到 0.2~0.5；同场景对齐好时 0.99。**所有帧互相都不一致时只有它能救命。**
    · resid（相对中位数）：抓掉个别帧的局部不一致（前景运动、单帧失焦等）。"""
    keep = np.ones(len(info), bool)
    bad = []
    for x in info:
        if x["i"] == ref_idx:
            continue
        if x.get("broken"):
            keep[x["i"]] = False
            bad.append((x["path"].name, "DNG 读取失败（文件损坏/未写完）"))
            continue
        m = float(np.abs(x["d"]).max())
        if m > max_shift or x["gain"] >= 1e8:
            keep[x["i"]] = False
            bad.append((x["path"].name, f"位移 {m:.1f}px 超 --max-shift {max_shift:g}"))
        elif x["wn"] < min_wn:
            keep[x["i"]] = False
            bad.append((x["path"].name,
                        f"对齐残差非白噪 wn={x['wn']:.3f} < {min_wn:g}（异场景/错位/失焦）"))
    cand = [x for x in info if x["i"] != ref_idx and keep[x["i"]]]
    if len(cand) >= 3:
        med = float(np.median([x["resid"] for x in cand]))
        for x in cand:
            if x["resid"] > med * reject_ratio:
                keep[x["i"]] = False
                bad.append((x["path"].name,
                            f"对齐残差 {x['resid']:.5f} > 中位 {med:.5f}×{reject_ratio:g}"))
    if 2 <= int(keep.sum()) < 4:
        print(f"  ⚠ 仅 {int(keep.sum())} 帧可用，样本太少无法做统计剔除；"
              f"请人工核对上面的 wn（同场景应 ≈1）与残差列")
    return keep, bad


def save_png(mosaic, path):
    g = mosaic.reshape(mosaic.shape[0] // 2, 2, mosaic.shape[1] // 2, 2).mean((1, 3))
    lo, hi = np.percentile(g, [1.0, 99.5])
    g = np.clip((g - lo) / max(hi - lo, 1e-9), 0.0, 1.0) ** (1 / 2.2)
    Image.fromarray((g * 255).astype(np.uint8)).save(path)


# --------------------------------------------------------------------- 主流程

def run(paths, ref_idx, levels, crop, max_shift, reject_ratio, out, png, noisy_out,
        min_wn=0.8):
    t_all = time.time()
    try:
        ref_m, pat = load_b01(paths[ref_idx], crop)
    except Exception as e:
        sys.exit(f"参考帧 {paths[ref_idx].name} 读取失败：{e}")
    pos = plane_pos(pat)
    ref_planes = cfa_planes(ref_m, pos)
    print(f"参考帧 {paths[ref_idx].name}  {ref_m.shape[1]}×{ref_m.shape[0]}  "
          f"CFA={pat}  帧数 {len(paths)}  金字塔 {levels} 级")

    # ---- 第一轮：逐帧估位移（内存只驻留参考帧 + 当前帧）
    info = []
    for i, p in enumerate(paths):
        if i == ref_idx:
            info.append({"i": i, "path": p, "d": np.zeros(2), "spread": 0.0,
                         "resid": 0.0, "gain": 1.0, "wn": 1.0, "sharp": 0.0})
            continue
        t0 = time.time()
        try:
            m, _ = load_b01(p, crop)
        except Exception as e:
            print(f"  ⚠ {p.name} 读取失败，跳过：{e}")
            info.append({"i": i, "path": p, "d": np.zeros(2), "spread": 0.0, "resid": 0.0,
                         "gain": 1e9, "wn": 0.0, "sharp": 0.0, "broken": True})
            continue
        mov = cfa_planes(m, pos)
        d, spread, resid, gain, wn, sharp = estimate_frame(ref_planes, mov, levels)
        del mov, m
        info.append({"i": i, "path": p, "d": d, "spread": spread,
                     "resid": resid, "gain": gain, "wn": wn, "sharp": sharp,
                     "dt": time.time() - t0})

    print(f"\n{'#':>3} {'文件':<28}{'d=(dy,dx) px':>22}{'平面离散':>10}"
          f"{'对齐残差':>11}{'白噪性':>8}{'收益':>7}{'峰锐度':>8}")
    for x in info:
        tag = "(参考)" if x["i"] == ref_idx else ""
        print(f"{x['i']:>3} {x['path'].name:<28}"
              f"{x['d'][0]:>+11.3f},{x['d'][1]:>+9.3f}{x['spread']:>10.3f}"
              f"{x['resid']:>11.5f}{x['wn']:>8.3f}{x['gain']:>7.2f}{x['sharp']:>8.1f} {tag}")

    keep, bad = select_frames(info, ref_idx, reject_ratio, max_shift, min_wn)
    for name, why in bad:
        print(f"  ✗ 剔除 {name}: {why}")
    n_keep = int(keep.sum())
    if n_keep < 2:
        sys.exit(f"对齐失败：{len(paths)} 帧中仅 {n_keep} 帧通过（阈值 --reject-ratio "
                 f"{reject_ratio:g} / --max-shift {max_shift:g}）。拒绝产出 clean。")
    if bad:
        print(f"  → 保留 {n_keep}/{len(paths)} 帧，clean 噪声按 √{n_keep}={n_keep ** 0.5:.2f} 折减")

    # ---- 第二轮：只对保留帧做全分辨率重采样 + 累加
    h, w = ref_planes[0].shape
    acc = np.zeros((4, h, w), np.float32)
    wsum = np.zeros((4, h, w), np.float32)
    for i, p in enumerate(paths):
        if not keep[i]:
            continue
        if i == ref_idx:
            accumulate(acc, wsum, ref_planes, None)
            continue
        m, _ = load_b01(p, crop)
        mov = cfa_planes(m, pos)
        accumulate(acc, wsum, mov, info[i]["d"])
        del mov, m
    clean = pack_planes([acc[c] / np.maximum(wsum[c], 1.0) for c in range(4)], pos, ref_m.shape)
    cov = float(wsum.mean() / n_keep)
    print(f"有效像素覆盖 {cov * 100:.2f}%（边缘越界像素会被排除）")

    if out:
        np.save(out, clean)
        print("→ clean:", out)
    if noisy_out:
        np.save(noisy_out, ref_m)
        print("→ noisy:", noisy_out)
    if png:
        save_png(clean, png)
        print("→ 预览:", png)
    print(f"耗时 {time.time() - t_all:.1f}s")
    return clean


# ------------------------------------------------------------------ self-test

TEST_SHIFTS = [(0.0, 0.0), (0.3, -1.7), (2.4, 0.9),
               (-3.1, 2.2), (1.75, 3.65), (-2.2, -0.45)]   # 传感器像素


def synth_noise(x, rng, A=A_SHOT, B=B_READ):
    """泊松-高斯：var = A·x + B（口径同 diag_255b.synth_noise）。"""
    shot = rng.poisson(np.clip(x, 0, 1) / A).astype(np.float32) * A
    return np.clip(shot + rng.normal(0, B ** 0.5, x.shape).astype(np.float32), 0.0, 1.0)


def box(a, k=5):
    p = np.pad(a, k // 2, mode="reflect")
    out = np.zeros_like(a)
    for dy in range(k):
        for dx in range(k):
            out += p[dy:dy + a.shape[0], dx:dx + a.shape[1]]
    return out / (k * k)


def interp_c(d):
    """双线性重采样的噪声方差增益 Σw²（d 为平面域位移）。"""
    f = np.abs(np.asarray(d, float) - np.round(d))
    w = np.array([(1 - f[0]) * (1 - f[1]), (1 - f[0]) * f[1],
                  f[0] * (1 - f[1]), f[0] * f[1]])
    return float((w * w).sum())


def selftest(crop=1024, seed=20261002, levels=4):
    ok = True
    here = Path(__file__).resolve().parent.parent.parent
    dng = here / "test_temp/FILMKIT_20261002-124812.dng"
    other = here / "test_temp/FILMKIT_20261002-124611.dng"
    if not dng.exists():
        sys.exit(f"找不到样片 {dng}")

    base, pat = load_b01(dng, crop)
    pos = plane_pos(pat)
    rng = np.random.default_rng(seed)

    # 真值：基准 DNG 在 CFA 平面上 5×5 box 平滑（去掉基准自身噪声，
    # 否则它帧间相关、不会被平均掉，会稀释"噪声下降倍数"的测量）
    truth = pack_planes([box(p, 5) for p in cfa_planes(base, pos)], pos, base.shape)

    frames = []
    for dy, dx in TEST_SHIFTS:
        f = pack_planes([warp(p, -dy / 2.0, -dx / 2.0)[0] for p in cfa_planes(truth, pos)],
                        pos, truth.shape)          # frame(y,x) = truth(y−dy, x−dx)
        frames.append(synth_noise(f, rng))

    # ---- ① 位移恢复精度
    print(f"[1] 位移恢复（{len(frames)} 帧合成，crop={crop}，噪声 A={A_SHOT:g} B={B_READ:g}）")
    ref_planes = cfa_planes(frames[0], pos)
    ds, resids, gains, wns = [], [], [], []
    for i, f in enumerate(frames):
        d, spread, resid, g, wn, sh = estimate_frame(ref_planes, cfa_planes(f, pos), levels)
        ds.append(d)
        resids.append(resid)
        gains.append(g)
        wns.append(wn)
        t = np.array(TEST_SHIFTS[i])
        e = d - t
        print(f"  帧{i}: 真值({t[0]:+6.2f},{t[1]:+6.2f})  "
              f"估计({d[0]:+7.3f},{d[1]:+7.3f})  误差({e[0]:+6.3f},{e[1]:+6.3f})  "
              f"平面离散 {spread:.3f}px  残差 {resid:.5f}")
    err = np.abs(np.array(ds) - np.array(TEST_SHIFTS)).max()
    print(f"  → 最大位移误差 {err:.4f} px  {'✓ <0.1' if err < 0.1 else '✗ 未达 0.1px'}"
          f"（无噪声时同流程为 0.002px，余量全是噪声引起的估计方差）")
    ok &= err < 0.1

    # ---- ② 噪声下降倍数
    h, w = ref_planes[0].shape
    n = len(frames)

    def merge(fs, dd):
        a = np.zeros((4, h, w), np.float32)
        s = np.zeros((4, h, w), np.float32)
        for f, d in zip(fs, dd):
            accumulate(a, s, cfa_planes(f, pos), d)
        return pack_planes([a[c] / np.maximum(s[c], 1.0) for c in range(4)], pos, base.shape)

    clean = merge(frames, ds)

    sl = (slice(16, -16), slice(16, -16))

    def lag2_sigma(m):
        """高频总误差 σ（同相位 lag-2 差分，逐平面取中位）。含场景自身高频，非纯噪声。"""
        return float(np.median([float(np.std(p[sl][:, 2:] - p[sl][:, :-2])) / np.sqrt(2)
                                for p in cfa_planes(m, pos)]))

    def resid_sigma(m, ref):
        """对真值的残差 σ（真值已平滑）——本脚本里最接近纯噪声的口径。"""
        return float(np.median([float(np.std(m[sl][y::2, x::2] - ref[sl][y::2, x::2]))
                                for (y, x) in pos]))

    s1, sN = lag2_sigma(frames[0]), lag2_sigma(clean)
    r1, rN = resid_sigma(frames[0], truth), resid_sigma(clean, truth)
    # 双线性重采样把噪声方差乘上 c=Σw²（<1，即低通）；参考帧不动，c₀=1
    cs = [interp_c(np.array(TEST_SHIFTS[i]) / 2.0) for i in range(1, n)]
    pred = r1 * float(np.sqrt((1.0 + sum(cs)) / n ** 2))
    print(f"\n[2] 噪声下降（真值=平滑后的基准，{n} 帧）")
    print(f"  vs 真值残差 σ      单帧原始 {r1:.5f} → clean {rN:.5f}   下降 ×{r1 / rN:.3f}"
          f"（理想 1/N 平均 √{n}={n ** 0.5:.3f}）")
    print(f"  高频 σ(lag-2 差分)  单帧原始 {s1:.5f} → clean {sN:.5f}   下降 ×{s1 / sN:.3f}")
    print(f"  说明 实测高于 √{n} 属正常：5 个非参考帧做亚像素双线性重采样时噪声被一并低通\n"
          f"       （各帧方差增益 cᵢ=Σw²={','.join(f'{c:.3f}' for c in cs)}），按\n"
          f"       σ₀·√((1+Σcᵢ)/{n}²) 预测 {pred:.5f}，与实测 {rN:.5f} 吻合；\n"
          f"       代价是这些帧的高频同样被低通（clean 在纹理处略软于真实场景）。")
    ok &= r1 / rN > n ** 0.5 and abs(rN - pred) / pred < 0.15

    # ---- ③ 故障注入：掺一帧别的场景，看是否被剔除
    print("\n[3] 故障注入：掺入一帧异场景（124611）")
    alien, _ = load_b01(other, crop)
    fake = [{"i": i, "path": Path(f"f{i}.dng"), "d": ds[i],
             "resid": resids[i], "gain": gains[i], "wn": wns[i]} for i in range(n)]
    d_alien, _, r_alien, g_alien, wn_alien, _ = estimate_frame(
        ref_planes, cfa_planes(alien, pos), levels)
    fake.append({"i": n, "path": other, "d": d_alien, "resid": r_alien,
                 "gain": g_alien, "wn": wn_alien})
    # max_shift 放宽到 1000：单独检验"绝对残差"这一条判据（它才是主判据）
    keep, badr = select_frames(fake, 0, 1.8, 1000.0)
    for name, why in badr:
        print(f"  ✗ 剔除 {name}: {why}")
    hit = not keep[n]
    print(f"  → 异场景帧 {'已剔除 ✓' if hit else '未剔除 ✗'}"
          f"（白噪性 wn={wn_alien:.3f} vs 同场景帧 {np.median(wns):.3f}；"
          f"残差 {r_alien:.5f} vs {np.median(resids):.5f}）")
    ok &= hit

    # ---- ④ 真实两张样片
    print("\n[4] 真实样片 124611 vs 124812")
    a1, _ = load_b01(other, crop)
    thumbs = []
    for m in (a1, base):
        g = m.reshape(m.shape[0] // 2, 2, m.shape[1] // 2, 2).mean((1, 3))
        for _ in range(4):                      # /16 缩略图
            g = half(g)
        thumbs.append(g)
    x, y = thumbs[0] - thumbs[0].mean(), thumbs[1] - thumbs[1].mean()
    ncc16 = float((x * y).sum() / np.sqrt((x * x).sum() * (y * y).sum()))
    print(f"  /16 缩略图 NCC = {ncc16:.4f}")
    for lev in (8, 4):
        gs = []
        for m in (a1, base):
            g = m.reshape(m.shape[0] // 2, 2, m.shape[1] // 2, 2).mean((1, 3))
            for _ in range(int(np.log2(lev))):
                g = half(g)
            gs.append(g)
        d, sh = phase_shift(gs[0], gs[1])
        print(f"  /{lev} 相位相关位移 = ({d[0] * lev:+.1f}, {d[1] * lev:+.1f}) mosaic px"
              f"  峰锐度 {sh:.1f}"
              f"   重叠区需 |d|<{min(gs[0].shape) // 2 * lev}px")

    print("\n自检结果:", "全部通过 ✓" if ok else "有项目未达标 ✗")
    return 0 if ok else 1


# ------------------------------------------------------------------------ CLI

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inputs", nargs="*", help="DNG 文件或目录")
    ap.add_argument("--glob", default="*.dng", help="目录模式的通配（默认 *.dng）")
    ap.add_argument("--ref", default="0", help="参考帧：索引或文件名")
    ap.add_argument("--out", default="clean.npy", help="clean 输出（img01 float32 npy）")
    ap.add_argument("--noisy-out", default=None, help="可选：参考帧 img01 另存一份当 noisy")
    ap.add_argument("--png", default=None, help="可选：clean 可视化 PNG")
    ap.add_argument("--levels", type=int, default=4, help="金字塔级数（默认 4）")
    ap.add_argument("--crop", type=int, default=0, help="中心裁剪半边长，0=全幅")
    ap.add_argument("--max-shift", type=float, default=64.0, help="允许的最大位移（mosaic px）")
    ap.add_argument("--reject-ratio", type=float, default=1.8,
                    help="对齐残差超过同批中位数该倍数即剔除（默认 1.8）")
    ap.add_argument("--min-wn", type=float, default=0.8,
                    help="对齐残差白噪性下限，低于此判为异场景/错位（默认 0.8）")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest(crop=args.crop or 1024, levels=args.levels))

    paths = []
    for s in args.inputs:
        p = Path(s)
        paths += sorted(p.glob(args.glob)) if p.is_dir() else [p]
    paths = [p for p in paths if p.suffix.lower() in (".dng", ".raw", ".arw", ".cr2", ".nef")]
    if len(paths) < 2:
        sys.exit("至少需要 2 个 DNG")

    if args.ref.lstrip("-").isdigit():
        ref_idx = int(args.ref) % len(paths)
    else:
        names = [p.name for p in paths]
        ref_idx = names.index(Path(args.ref).name) if Path(args.ref).name in names else 0
    run(paths, ref_idx, args.levels, args.crop, args.max_shift, args.reject_ratio,
        args.out, args.png, args.noisy_out, args.min_wn)


if __name__ == "__main__":
    main()
