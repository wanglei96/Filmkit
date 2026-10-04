#!/usr/bin/env python3
"""RAW 域高光重建「参考色度场」口径检查（v0.10.149 引入三通道掩码后固化）。

做什么：在真实 DNG 上离线复现 passHighlightRaw 的参考场与 apply 逻辑，
对比几种「参考样本选择口径 + 加权口径」下，G 饱和像素修复后的 WB 域色比
（品红的直接指标），用来判断参考比是否取偏、以及改动的收益。

用法：
    python3 tools/hl-ref-chroma-check.py <a.dng> [b.dng ...]

判据（G 饱和像素修复后的 WB 域 R/G、B/G）：
    · 目标 = 该片「完全未饱和且亮度接近饱和区」像素的自然色度（脚本自动估）。
    · 越接近目标越好；偏离目标 = 该片高光区残留的品红/偏色量。
    · 同时看「未修%」（最终 G 仍等于 1.0 的比例，越低越好，说明漏修少）。

口径说明（对应 FrameRenderer.cpp）：
    ① G-mask    ：参考样本只要求「该 2×2 单元的 G 未截断」——v0.10.149 之前的旧口径
    ② triple    ：要求 R、G1、G2、B 四个 photosite 全部未截断（v0.10.149）
    pool        ：参考比 = Σ(mR·w)/Σ(mG·w)（像素等权池化，v0.10.148）
    wratio      ：参考比 = Σ((mR/mG)·w)/Σw（块比值加权，旧口径）

依赖：numpy + rawpy（无 scipy）。
"""
import sys
import numpy as np
import rawpy

BLOCK = 32
GCAP = 1.5


def load(path):
    with rawpy.imread(path) as raw:
        cfa = raw.raw_image_visible.astype(np.float32)
        bl = float(np.mean(raw.black_level_per_channel))
        wl = float(raw.white_level)
        cw = raw.camera_whitebalance
        pat = raw.raw_pattern.tolist()
    if pat != [[1, 0], [2, 3]]:
        print(f"  [warn] 该机 CFA pattern = {pat}，本脚本按 [[1,0],[2,3]] 取相位，结果可能无效")
    gains = np.array([cw[0] / cw[1], 1.0, cw[2] / cw[1]], np.float32)
    nv = np.clip((cfa - bl) / (wl - bl), 0, None)
    return nv, gains


def phase_masks(nv, gains):
    """按 CFA 相位取四个子图（未旋转的传感器坐标）。"""
    g1 = nv[0::2, 0::2] * gains[1]
    r = nv[0::2, 1::2] * gains[0]
    b = nv[1::2, 0::2] * gains[2]
    g2 = nv[1::2, 1::2] * gains[1]
    return r, g1, b, g2


def build_ref(unit_ok, mos, chan, H, W, clips):
    """按 unit_ok（2×2 单元级布尔）统计 1/32 网格的加权分子场。"""
    ph, pw = (-H) % BLOCK, (-W) % BLOCK

    def pad(a, f=0):
        return np.pad(a, ((0, ph), (0, pw)), constant_values=f)

    gmap = np.repeat(np.repeat(unit_ok, 2, 0), 2, 1)
    ok = (~pad(mos >= clips[chan], False)) & pad(mos > 0, False) & pad(gmap, False)
    mosp, chanp = pad(mos), pad(chan, -1)
    bh, bw = mosp.shape[0] // BLOCK, mosp.shape[1] // BLOCK

    def blk(a):
        return a.reshape(bh, BLOCK, bw, BLOCK).sum(axis=(1, 3))

    cR, cG, cB = (blk(ok & (chanp == c)) for c in (0, 1, 2))
    sR, sG, sB = (blk(np.where(ok & (chanp == c), mosp, 0)) for c in (0, 1, 2))
    w = np.minimum(np.minimum(cR, cG), cB) / (BLOCK * BLOCK * 0.25)
    mR = np.where(cR > 0, sR / np.maximum(cR, 1), 0)
    mG = np.where(cG > 0, sG / np.maximum(cG, 1), 0)
    mB = np.where(cB > 0, sB / np.maximum(cB, 1), 0)
    return w, mR, mG, mB


def down4(a):
    a2 = np.pad(a, ((0, (-a.shape[0]) % 4), (0, (-a.shape[1]) % 4)))
    return a2.reshape(a2.shape[0] // 4, 4, a2.shape[1] // 4, 4).sum(axis=(1, 3)) / 16.0


def bilerp(f, PY, PX, step):
    t = np.stack([PY / step - 0.5, PX / step - 0.5], axis=-1)
    i0 = np.floor(t)
    fr = t - i0
    i0 = i0.astype(np.int64)
    h_, w_ = f.shape

    def g(iy, ix):
        return f[np.clip(iy, 0, h_ - 1), np.clip(ix, 0, w_ - 1)]

    a = g(i0[..., 0], i0[..., 1])
    b = g(i0[..., 0], i0[..., 1] + 1)
    c = g(i0[..., 0] + 1, i0[..., 1])
    d = g(i0[..., 0] + 1, i0[..., 1] + 1)
    return (a * (1 - fr[..., 0]) + c * fr[..., 0]) * (1 - fr[..., 1]) + \
           (b * (1 - fr[..., 0]) + d * fr[..., 0]) * fr[..., 1]


def run(nv, gains, unit_mode, pool):
    H, W = nv.shape
    chan = np.empty((H, W), np.int8)
    chan[0::2, 0::2] = 1
    chan[0::2, 1::2] = 0
    chan[1::2, 0::2] = 2
    chan[1::2, 1::2] = 1
    mos = nv * gains[chan]
    clips = gains
    r, g1, b, g2 = phase_masks(nv, gains)

    if unit_mode == 'triple':
        unit = (r < clips[0]) & (g1 < clips[1]) & (g2 < clips[1]) & (b < clips[2])
    else:
        unit = (g1 < clips[1])
    w, mR, mG, mB = build_ref(unit, mos, chan, H, W, clips)
    with np.errstate(invalid='ignore', divide='ignore'):
        rgr = np.where(mG > 0, mR / np.maximum(mG, 1e-6), 0)
        bgr = np.where(mG > 0, mB / np.maximum(mG, 1e-6), 0)
    sel = (w > 0) & (mG > 0)
    if pool:
        rgG = (mR[sel] * w[sel]).sum() / (mG[sel] * w[sel]).sum()
        bgG = (mB[sel] * w[sel]).sum() / (mG[sel] * w[sel]).sum()
    else:
        rgG = (rgr[sel] * w[sel]).sum() / w[sel].sum()
        bgG = (bgr[sel] * w[sel]).sum() / w[sel].sum()

    fW, fR, fG, fB = down4(w), down4(mR * w), down4(mG * w), down4(mB * w)
    fRo, fBo = down4(rgr * w), down4(bgr * w)
    r_v, b_v = mos[0::2, 1::2], mos[1::2, 0::2]

    Gall, Rall, Ball, lvl, noclamp = [], [], [], [0, 0, 0], 0
    for nm, gv in (('G1', mos[0::2, 0::2]), ('G2', mos[1::2, 1::2])):
        py, px = np.nonzero(gv >= clips[1])
        if len(py) == 0:
            continue
        PY, PX = (py * 2).astype(np.float64), (px * 2).astype(np.float64)
        z1 = bilerp(w, PY, PX, BLOCK)
        z2 = bilerp(fW, PY, PX, BLOCK * 4)
        if pool:
            a1, gg1, b1 = (bilerp(f, PY, PX, BLOCK) for f in (mR * w, mG * w, mB * w))
            a2, gg2, b2 = (bilerp(f, PY, PX, BLOCK * 4) for f in (fR, fG, fB))
            rg1, bg1 = a1 / np.maximum(gg1, 1e-6), b1 / np.maximum(gg1, 1e-6)
            rg2, bg2 = a2 / np.maximum(gg2, 1e-6), b2 / np.maximum(gg2, 1e-6)
        else:
            a1, b1 = bilerp(rgr * w, PY, PX, BLOCK), bilerp(bgr * w, PY, PX, BLOCK)
            rg1, bg1 = a1 / np.maximum(z1, 1e-6), b1 / np.maximum(z1, 1e-6)
            a2, b2 = bilerp(fRo, PY, PX, BLOCK * 4), bilerp(fBo, PY, PX, BLOCK * 4)
            gg2 = bilerp(fW, PY, PX, BLOCK * 4)
            rg2, bg2 = a2 / np.maximum(gg2, 1e-6), b2 / np.maximum(gg2, 1e-6)
        u1 = z1 > 0.30
        u2 = (~u1) & (z2 > 0.05)
        lvl[0] += int(u1.sum())
        lvl[1] += int((~u1 & u2).sum())
        lvl[2] += int((~u1 & ~u2).sum())
        rg = np.maximum(np.where(u1, rg1, np.where(u2, rg2, rgG)), 1e-3)
        bg = np.maximum(np.where(u1, bg1, np.where(u2, bg2, bgG)), 1e-3)
        if nm == 'G1':
            Rv, Bv = r_v[py, px], b_v[py, px]
        else:   # G2 的 R 邻居在 +1 行、B 邻居在 +1 列（neighborOfChan 的 k 顺序）
            Rv = r_v[np.minimum(py + 1, r_v.shape[0] - 1), px]
            Bv = b_v[py, np.minimum(px + 1, b_v.shape[1] - 1)]
        v = gv[py, px]
        raw_gest = 0.5 * (Rv / rg + Bv / bg)
        noclamp += int((raw_gest < v).sum())
        gest = np.clip(raw_gest, v, GCAP)
        sf = 1.0 - np.clip(z1, 0, 1)
        xx = np.clip((sf - 0.05) / 0.25, 0, 1)
        gate = xx * xx * (3 - 2 * xx)
        Gall.append(v * (1 - gate) + gest * gate)
        Rall.append(Rv)
        Ball.append(Bv)
    Gv, Rv, Bv = np.concatenate(Gall), np.concatenate(Rall), np.concatenate(Ball)
    tot = sum(lvl)
    return dict(rg=rgG, bg=bgG, rgg=float(np.median(Rv / Gv)), bgg=float(np.median(Bv / Gv)),
                unr=100 * float((Gv <= 1.0001).mean()),
                lvl=[100 * x / max(tot, 1) for x in lvl],
                nocl=100 * noclamp / max(len(Gv), 1))


def target_of(nv, gains):
    """该片高光区未饱和邻域的自然色度（WB 域 R/G、B/G）。"""
    r, g1, b, g2 = phase_masks(nv, gains)
    g = 0.5 * (g1 + g2)
    rY = (r + 2 * g + b) / 4
    clean = (r < 0.99 * gains[0]) & (g < 0.99 * gains[1]) & (b < 0.99 * gains[2]) & (rY > 0.4)
    if clean.sum() < 500:
        return None
    return float(np.median(r[clean] / g[clean])), float(np.median(b[clean] / g[clean]))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    for path in sys.argv[1:]:
        nv, gains = load(path)
        print(f"\n=== {path}")
        print(f"    WB 增益 R={gains[0]:.4f} G=1.0 B={gains[2]:.4f}")
        tgt = target_of(nv, gains)
        if tgt:
            print(f"    目标（该片高光区未饱和邻域自然色度）  R/G {tgt[0]:.4f}  B/G {tgt[1]:.4f}")
        for label, um, pool in (("① G-mask + wratio (旧)", 'g', False),
                                ("② G-mask + pool  (v0.10.148)", 'g', True),
                                ("③ triple + pool  (v0.10.149)", 'triple', True),
                                ("④ triple + wratio", 'triple', False)):
            d = run(nv, gains, um, pool)
            dev = ""
            if tgt:
                dev = f"  偏差 {(d['rgg']/tgt[0]-1)*100:+.1f}%/{(d['bgg']/tgt[1]-1)*100:+.1f}%"
            print(f"    {label:32s} rg/bg {d['rg']:.3f}/{d['bg']:.3f}  "
                  f"三级 {'/'.join(f'{x:.1f}' for x in d['lvl'])}%  →  R/G {d['rgg']:.4f}  "
                  f"B/G {d['bgg']:.4f}  未修 {d['unr']:4.1f}%  下界挡 {d['nocl']:4.1f}%{dev}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
