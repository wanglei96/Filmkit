#!/usr/bin/env python3
"""离线核对：darktable highlights "inpaint opposed" 在本项目真实 RAW 上的行为。

用途 = 真机实测前的**可运行检查**：把引擎里 4 个 shader
（kFsHlMask / kFsHlDilate / kFsHlChroma / kFsHlApply）的同一套公式在 CPU 上重放
一遍，回答三个问题——
  ① 这张片子有多少 photosite 达裁剪阈值（= 高光重建的作用面）
  ② 全图色度校正量 chrominance 是多少（darktable 的 100 样本闸是否通过）
  ③ 被裁剪像素恢复后的值离邻域有多远（恢复是否合理，还是把高光改坏）

公式出处（逐行对照，勿凭记忆改）：
  darktable src/iop/highlights.c            clips[c] = d->clip × icoeffs[c]
  darktable src/iop/hlreconstruct/opposed.c ①mask ②dilate ③chrominance ④apply
  darktable src/iop/hlreconstruct/segbased.c:186  _calc_refavg（3×3 对立通道外推）

已知边界差异（不影响结论，验证用）：darktable 在图像边缘对 3×3 邻域做**截断**
（dymax = min(h-1, row+2)），本脚本用 zero-pad + 计数近似；引擎 shader 侧是忠实
截断实现。差异只出现在最外 1 圈像素。

用法：
  python3 tools/hl-opposed-check.py test_temp/filmkit_20261003-104800.dng
  python3 tools/hl-opposed-check.py <dng> --gain 1.7988,1.0,1.6797   # 默认从 rawpy 读
"""
import sys
import numpy as np
import rawpy

BLACK, WHITE = 64.0, 1023.0
# GRBG（日志 cfa:1）：k = (py<<1)|px → 通道 0=R 1=G 2=B
CFA_LUT_GRBG = np.array([1, 0, 2, 1], dtype=np.int32)


def box3_sum(a):
    """3×3 邻域和（居中，(row-1..row+1) × (col-1..col+1)），cumsum 实现。"""
    p = np.pad(a.astype(np.float32), 1, mode="constant")
    ii = p.cumsum(0).cumsum(1)
    ii = np.pad(ii, ((1, 0), (1, 0)))
    h, w = a.shape
    return ii[3:3 + h, 3:3 + w] - ii[0:h, 3:3 + w] - ii[3:3 + h, 0:w] + ii[0:h, 0:w]


def dilate_offsets():
    """_mask_dilated 的块偏移集合（块为单位；含中心）。"""
    offs = [(dx, dy) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]
    for s in (-1, 1):
        offs += [(dx, 3 * s) for dx in range(-2, 3)]
        offs += [(dx, 2 * s) for dx in range(-3, 4)]
        offs += [(dx, s) for dx in (-3, -2, 2, 3)]
    offs += [(dx, 0) for dx in (-3, -2, 2, 3)]
    return offs


def run(dng, gain=None):
    with rawpy.imread(dng) as r:
        raw = r.raw_image_visible.astype(np.float32)
        wb = r.camera_whitebalance
    h, w = raw.shape
    if gain is None:
        gain = np.array([wb[0], wb[1], wb[2]], dtype=np.float32)  # 已 G=1 归一
    else:
        gain = np.array(gain, dtype=np.float32)

    yy, xx = np.mgrid[0:h, 0:w]
    ch = CFA_LUT_GRBG[((yy & 1) << 1) | (xx & 1)]
    val = np.maximum((raw - BLACK) / (WHITE - BLACK), 0.0) * gain[ch]  # WB 域
    clips = gain[ch]
    clipped = val >= clips
    print(f"输入 {dng}")
    print(f"  尺寸 {w}×{h}  WB增益 {gain[0]:.4f}/{gain[1]:.4f}/{gain[2]:.4f}")
    n_clip = int(clipped.sum())
    print(f"  ① 达裁剪阈值的 photosite: {n_clip}  ({100.0 * n_clip / clipped.size:.4f}%)")
    for c, name in ((0, "R"), (1, "G"), (2, "B")):
        m = clipped & (ch == c)
        print(f"       {name}: {int(m.sum())}")

    # ② 块级 mask（3×3 photosite = 一块）+ 膨胀
    mh, mw = h // 3, w // 3
    if mh < 8 or mw < 8:
        print("  图像过小，跳过")
        return
    sl = (slice(0, mh * 3), slice(0, mw * 3))
    v3 = val[sl].reshape(mh, 3, mw, 3)
    c3 = ch[sl].reshape(mh, 3, mw, 3)
    k3 = clipped[sl].reshape(mh, 3, mw, 3)
    mb = np.stack([(k3 & (c3 == c)).any(axis=(1, 3)) for c in range(3)], axis=2)
    pad = np.zeros((mh + 8, mw + 8, 3), bool)
    pad[4:4 + mh, 4:4 + mw] = mb
    dil = np.zeros_like(mb)
    for dx, dy in dilate_offsets():
        dil |= pad[4 + dy:4 + dy + mh, 4 + dx:4 + dx + mw]
    sy, sx = np.mgrid[0:mh, 0:mw]
    safe = ((sx >= 3) & (sy >= 3) & (sx < mw - 3) & (sy < mh - 3))[..., None]
    dil = np.where(safe, dil, mb)
    n_blocks = int(mb.any(axis=2).sum())
    print(f"  ② 裁剪块 {n_blocks} / {mh * mw}；膨胀后 {int(dil.any(axis=2).sum())}")

    # ③ chrominance：_calc_refavg 向量化（3×3 按通道均值 → 各开立方 → 对立均值 → 回立方）
    mean = np.zeros((h, w, 3), np.float32)
    cnt = np.zeros((h, w, 3), np.float32)
    for c in range(3):
        m = (ch == c).astype(np.float32)
        mean[..., c] = box3_sum(np.where(ch == c, val, 0.0))
        cnt[..., c] = box3_sum(m)
    mr = np.zeros_like(mean)
    nz = cnt > 0
    mr[nz] = np.cbrt(mean[nz] / cnt[nz])
    opp = np.stack([0.5 * (mr[..., 1] + mr[..., 2]),
                    0.5 * (mr[..., 0] + mr[..., 2]),
                    0.5 * (mr[..., 0] + mr[..., 1])], axis=2)
    refavg = opp[np.arange(h)[:, None], np.arange(w)[None, :], ch] ** 3

    dilmap = np.zeros((h, w, 3), bool)
    dil_full = np.repeat(np.repeat(dil, 3, axis=0), 3, axis=1)[:h, :w]
    for c in range(3):
        dilmap[..., c] = dil_full[..., c]
    near = dilmap[np.arange(h)[:, None], np.arange(w)[None, :], ch]
    cand = (~clipped) & (val > 0.2 * clips) & near
    sums = np.array([float((val - refavg)[cand & (ch == c)].sum()) for c in range(3)])
    cnts = np.array([float((cand & (ch == c)).sum()) for c in range(3)])
    chroma = np.where(cnts > 100.0, sums / np.maximum(cnts, 1.0), 0.0)
    print(f"  ③ chrominance: R {chroma[0]:+.5f}  G {chroma[1]:+.5f}  B {chroma[2]:+.5f}"
          f"   (样本 R/G/B = {int(cnts[0])}/{int(cnts[1])}/{int(cnts[2])}，"
          f"darktable 闸 = 100)")

    # ④ 应用
    out = val.copy()
    fix = clipped
    out[fix] = np.maximum(val[fix], (refavg + chroma[ch])[fix])
    d = out - val
    dv = d[clipped]
    if dv.size:
        print(f"  ④ 被裁剪像素恢复幅度: 中位 {np.median(dv):+.4f}  p90 "
              f"{np.percentile(dv, 90):+.4f}  最大 {dv.max():+.4f}"
              f"  未变化 {100.0 * (dv == 0).mean():.1f}%")
        print(f"      恢复后仍 == clips 的比例 {100.0 * (out[clipped] >= clips[clipped] - 1e-6).mean():.1f}%"
              f"（这些是 refavg 也到顶的点）")
    print(f"  全图改动像素 {int((d != 0).sum())} ({100.0 * (d != 0).mean():.4f}%)")

    # ⑤ 品红溯源：G 通道最先饱和（gain=1），R/B 还有余量 ⇒ 这些像素在 RAW 域就偏品红。
    #    中性渲染可直接复现（双线性+WB 就能看到亮天空发粉）。这一节量化 opposed 修到哪：
    #    参考值 = 3×3 邻域的 R/B 线性均值（未裁剪，是可信的真实值）。
    gsat = clipped & (ch == 1)
    ng = int(gsat.sum())
    if ng:
        mbar = mr ** 3   # 3×3 邻域的线性域通道均值
        rb = np.stack([mbar[..., 0][gsat], mbar[..., 1][gsat], mbar[..., 2][gsat]], 1)
        ref_g = refavg[gsat]
        g_after = np.maximum(val[gsat], ref_g + chroma[1])   # ch==1 ⇒ clips[1]=gain_G=1
        mag_before = np.minimum(rb[:, 0] - rb[:, 1], rb[:, 2] - rb[:, 1])
        mag_after = np.minimum(rb[:, 0] - g_after, rb[:, 2] - g_after)
        print(f"  ⑤ G 饱和 photosite {ng}（占 {100.0 * ng / clipped.size:.3f}%）"
              f"，邻域线性均值 R {rb[:,0].mean():.3f} / G {rb[:,1].mean():.3f} / B {rb[:,2].mean():.3f}")
        print(f"      opposed 修后 G: 中位 {np.median(g_after):.3f}（ref 中位 {np.median(ref_g):.3f}"
              f"，chroma_G {chroma[1]:+.3f}）")
        print(f"      品红程度 min(R−G,B−G): 修前中位 {np.median(mag_before):+.3f}"
              f" → 修后中位 {np.median(mag_after):+.3f}")
        print(f"      修后仍偏品红（>0.05）的占比 {100.0 * (mag_after > 0.05).mean():.1f}%")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    g = None
    if "--gain" in sys.argv:
        g = [float(x) for x in sys.argv[sys.argv.index("--gain") + 1].split(",")]
    run(sys.argv[1], g)
