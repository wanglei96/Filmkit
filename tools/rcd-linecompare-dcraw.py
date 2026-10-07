#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
原版 RCD（dcraw 宿主）越界写入的精确模拟 + 三方对照（dcraw / RawTherapee / 我方）。

背景（docs/reports/rcd-linecompare-REPORT.md §A）：
  LuisSR RCD 2.3 的 `rgb` 是 `float(*)[3]`（rcd_demosaicing.c:34），但初始化
  第 41 行写 `rgb[indx][FC(row,col)]`。GRBG 相位下 FC=3（第二绿位）⇒ 越界写到
  `rgb[indx+1][0]`（下一像素的 R），且 G2 位的 `rgb[·][1]` 永远保持 0。
  RawTherapee 的 `rcd_demosaic.cc` 把 rgb 改成 `[3][tile]` 且初始化
  `rgb[c0][i] = rgb[c1][i] = LIM01(v)`（G1/G2 都写实测值）——RT 已修正该 bug。

本脚本：
  1. `init_dcraw()` 精确复现 dcraw 的越界内存语义（顺序扫描 + 覆盖下一像素 R）；
  2. `rcd_ours()` / `rcd_rt()` 用同一套数学、不同「每步口径」跑对照；
  3. 输出各阶段中间量与最终输出的差异统计。

用法：
  python3 tools/rcd-linecompare-dcraw.py                     # 默认样片 + 合成
  python3 tools/rcd-linecompare-dcraw.py --json out.json
"""
import argparse
import importlib.util
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('rlc', os.path.join(HERE, 'rcd-linecompare.py'))
rlc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rlc)

EPS_REF = 1e-5
EPSSQ = 1e-10
EPS_G = 1e-6
EPS_P1 = 1e-9


# ────────────────────────────────────────────────── dcraw 越界内存语义
def init_dcraw(v, fc, scale_green=True):
    """复现 `rgb = calloc(w*h, 3*sizeof(float))` + `rgb[i][FC(i)] = v[i]/65535`。

    顺序扫描；FC=3 时写入 (i*3+3) = 下一像素的 [0]；G2 位的 [1] 保持 0。
    返回 (R, G, B) 三个 (H,W) 数组 —— 即原版后续所有 `rgb[·][c]` 读到的值。
    """
    H, W = v.shape
    flat = np.zeros(H * W * 3 + 3, dtype=np.float64)   # +3 = 末尾越界的落点
    vf = v.ravel()
    cf = fc.ravel()
    for i in range(H * W):
        c = int(cf[i])
        flat[i * 3 + c] = vf[i]          # c==3 时越界写到 (i+1)*3+0
    arr = flat[:H * W * 3].reshape(H, W, 3)
    R = arr[..., 0].copy()
    G = arr[..., 1].copy()
    B = arr[..., 2].copy()
    if scale_green:
        pass
    return R, G, B


def init_rt(v, fc):
    """RawTherapee 口径：rgb[c0][i] = rgb[c1][i] = LIM01(v)；G1/G2 都是实测值。"""
    R = np.where(fc == 0, v, 0.0)
    G = np.where((fc == 1) | (fc == 3), v, 0.0)
    B = np.where(fc == 2, v, 0.0)
    return R, G, B


def init_ours(v, fc):
    """我方口径：G 位（含 G2）保留实测值；R/B 位留 0 待填。"""
    return init_rt(v, fc)


# ────────────────────────────────────────────────── 各步数学（可切换口径）
def _lpf_ref(v, fc, mRB):
    """原版 lpf：0.25/0.125/0.0625，仅 R/B 位有值。"""
    g = rlc.Grid(*v.shape)
    g.put('cfa', v)
    A = g.win('cfa')
    out = (0.25 * A + 0.125 * (g.win('cfa', -1, 0) + g.win('cfa', 1, 0)
                               + g.win('cfa', 0, -1) + g.win('cfa', 0, 1))
           + 0.0625 * (g.win('cfa', -1, -1) + g.win('cfa', -1, 1)
                       + g.win('cfa', 1, -1) + g.win('cfa', 1, 1)))
    return np.where(mRB, out, 0.0)


def _lpf_ours(v, fc, mRB):
    """我方/RT lpf：1/0.5/0.25（= 原版 4×），全域有值。"""
    g = rlc.Grid(*v.shape)
    g.put('cfa', v)
    A = g.win('cfa')
    return (A + 0.5 * (g.win('cfa', 0, 1) + g.win('cfa', 0, -1)
                       + g.win('cfa', 1, 0) + g.win('cfa', -1, 0))
            + 0.25 * (g.win('cfa', 1, 1) + g.win('cfa', -1, -1)
                      + g.win('cfa', 1, -1) + g.win('cfa', -1, 1)))


def _dir_ref(v, fc):
    """原版 STEP1 二次型 VH_Dir（4..-4 外为 0）。"""
    H, W = v.shape
    g = rlc.Grid(H, W)
    g.put('cfa', v)
    V = rlc.quad_stat(g, 'cfa', 1, 0)
    Hs = rlc.quad_stat(g, 'cfa', 0, 1)
    inner = np.zeros((H, W), bool)
    inner[4:H - 4, 4:W - 4] = True
    return np.where(inner, V / (V + Hs), 0.0)


def _dir_ours(v, fc):
    """我方 P1：五点修正高通 + 3 邻行/列平方和（= RT STEP1.1/1.2）。"""
    H, W = v.shape
    g = rlc.Grid(H, W)
    g.put('cfa', v)

    def hp5(uy, ux):
        return (g.win('cfa', -3 * uy, -3 * ux) - g.win('cfa', -uy, -ux)
                - g.win('cfa', uy, ux) + g.win('cfa', 3 * uy, 3 * ux)
                - 3.0 * (g.win('cfa', 2 * uy, 2 * ux) + g.win('cfa', -2 * uy, -2 * ux))
                + 6.0 * g.win('cfa'))
    g.put('hv', hp5(1, 0))
    g.put('hh', hp5(0, 1))
    vs = sum(g.win('hv', 0, i) ** 2 for i in (-1, 0, 1))
    hs_ = sum(g.win('hh', i, 0) ** 2 for i in (-1, 0, 1))
    return vs / (vs + hs_ + EPS_P1)


def _disc_diag4(vh):
    """中心 vs 四对角均值，取离 0.5 更远者（原版/RT 口径）。"""
    g = rlc.Grid(*vh.shape)
    g.put('vh', vh)
    c = g.win('vh')
    nb = 0.25 * (g.win('vh', -1, -1) + g.win('vh', -1, 1)
                 + g.win('vh', 1, -1) + g.win('vh', 1, 1))
    return np.where(np.abs(0.5 - c) < np.abs(0.5 - nb), nb, c)


def _disc_9(vh):
    """我方 P2：中心 vs 3×3 均值。"""
    g = rlc.Grid(*vh.shape)
    g.put('vh', vh)
    c = g.win('vh')
    nb = sum(g.win('vh', dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)) / 9.0
    return np.where(np.abs(c - 0.5) > np.abs(nb - 0.5), c, nb)


def step3(v, fc, vh, lpf, grad='ref', disc='ref', clamp=True):
    """G@RB。grad='ref' 四项 / 'ours' 两项；disc='ref' 四对角 / 'ours' 3×3。"""
    H, W = v.shape
    g = rlc.Grid(H, W)
    g.put('cfa', v)
    g.put('lpf', lpf)
    A = g.win('cfa')
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    mG = ~mRB
    if disc == 'ref':
        d = _disc_diag4(vh)
    else:
        d = _disc_9(vh)
    l0 = g.win('lpf')
    cN, cS = g.win('cfa', -1, 0), g.win('cfa', 1, 0)
    cW, cE = g.win('cfa', 0, -1), g.win('cfa', 0, 1)
    if grad == 'ref':
        NG = EPS_REF + np.abs(cN - cS) + np.abs(A - g.win('cfa', -2, 0)) \
            + np.abs(cN - g.win('cfa', -3, 0)) + np.abs(g.win('cfa', -2, 0) - g.win('cfa', -4, 0))
        SG = EPS_REF + np.abs(cS - cN) + np.abs(A - g.win('cfa', 2, 0)) \
            + np.abs(cS - g.win('cfa', 3, 0)) + np.abs(g.win('cfa', 2, 0) - g.win('cfa', 4, 0))
        WG = EPS_REF + np.abs(cW - cE) + np.abs(A - g.win('cfa', 0, -2)) \
            + np.abs(cW - g.win('cfa', 0, -3)) + np.abs(g.win('cfa', 0, -2) - g.win('cfa', 0, -4))
        EG = EPS_REF + np.abs(cE - cW) + np.abs(A - g.win('cfa', 0, 2)) \
            + np.abs(cE - g.win('cfa', 0, 3)) + np.abs(g.win('cfa', 0, 2) - g.win('cfa', 0, 4))
    else:
        NG = np.abs(cN - cS) + np.abs(A - g.win('cfa', -2, 0)) + EPS_G
        SG = np.abs(cS - cN) + np.abs(A - g.win('cfa', 2, 0)) + EPS_G
        WG = np.abs(cW - cE) + np.abs(A - g.win('cfa', 0, -2)) + EPS_G
        EG = np.abs(cE - cW) + np.abs(A - g.win('cfa', 0, 2)) + EPS_G

    def rest(cadj, dy, dx, two_term):
        lN = g.win('lpf', dy, dx)
        if two_term:
            return cadj * 2.0 * l0 / (l0 + lN + EPS_G)
        return cadj * (1.0 + (l0 - lN) / (EPS_REF + l0 + lN))
    two = (grad == 'ours')
    vEst = (SG * rest(cN, -2, 0, two) + NG * rest(cS, 2, 0, two)) / (NG + SG)
    hEst = (WG * rest(cE, 0, 2, two) + EG * rest(cW, 0, -2, two)) / (EG + WG)
    gRB = d * hEst + (1.0 - d) * vEst
    if clamp:
        gRB = np.clip(gRB, 0.0, 1.0)
    return np.where(mG, A, np.where(mRB, gRB, 0.0)), dict(disc=d, NG=NG, SG=SG, WG=WG, EG=EG)


def step42(v, fc, R, G, B, pq, grad='ref', disc='ref', clamp=True):
    """R@B / B@R（对角段）。"""
    H, W = v.shape
    g = rlc.Grid(H, W)
    g.put('cfa', v)
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    opp = np.where(mRB, v, 0.0)
    g.put('opp', opp)
    g.put('G', G)
    g.put('pq', pq)
    if disc == 'ref':
        d = _disc_diag4(pq)
    else:
        g.put('pq2', pq)
        d = (g.win('pq2') + g.win('pq2', -1, 0) + g.win('pq2', 1, 0)
             + g.win('pq2', 0, -1) + g.win('pq2', 0, 1)) / 5.0
    g0 = g.win('G')
    cNW, cSE = g.win('opp', -1, -1), g.win('opp', 1, 1)
    cNE, cSW = g.win('opp', -1, 1), g.win('opp', 1, -1)
    if grad == 'ref':
        NWg = EPS_REF + np.abs(cNW - cSE) + np.abs(cNW - g.win('opp', -3, -3)) + np.abs(g0 - g.win('G', -2, -2))
        SEg = EPS_REF + np.abs(cSE - cNW) + np.abs(cSE - g.win('opp', 3, 3)) + np.abs(g0 - g.win('G', 2, 2))
        NEg = EPS_REF + np.abs(cNE - cSW) + np.abs(cNE - g.win('opp', -3, 3)) + np.abs(g0 - g.win('G', 2, -2))
        SWg = EPS_REF + np.abs(cSW - cNE) + np.abs(cSW - g.win('opp', 3, -3)) + np.abs(g0 - g.win('G', -2, 2))
    else:
        NWg = np.abs(cNW - cSE) + np.abs(cNW - g.win('cfa', -3, -3)) + np.abs(g0 - g.win('G', -2, -2)) + EPS_G
        SEg = np.abs(cSE - cNW) + np.abs(cSE - g.win('cfa', 3, 3)) + np.abs(g0 - g.win('G', 2, 2)) + EPS_G
        NEg = np.abs(cNE - cSW) + np.abs(cNE - g.win('cfa', -3, 3)) + np.abs(g0 - g.win('G', 2, -2)) + EPS_G
        SWg = np.abs(cSW - cNE) + np.abs(cSW - g.win('cfa', 3, -3)) + np.abs(g0 - g.win('G', -2, 2)) + EPS_G
    nwE, seE = cNW - g.win('G', -1, -1), cSE - g.win('G', 1, 1)
    neE, swE = cNE - g.win('G', -1, 1), cSW - g.win('G', 1, -1)
    pEst = (NWg * seE + SEg * nwE) / (NWg + SEg)
    qEst = (NEg * swE + SWg * neE) / (NEg + SWg)
    fill = g0 + (1.0 - d) * pEst + d * qEst
    if clamp:
        fill = np.clip(fill, 0.0, 1.0)
    fillR = np.where(mB, fill, 0.0)     # B 位的 R
    fillB = np.where(mR, fill, 0.0)     # R 位的 B
    return fillR, fillB


def step43(v, fc, G, fillR, fillB, vh, arr_mode='rt', clamp=True, third=True):
    """G 位 R/B 填充（轴向段）。

    arr_mode='rt'   ±3 项取「填充数组」（G 位 = G 值）——RT / 我方 3b 口径
    arr_mode='dcraw' ±3 项取「4.2 填充数组」（G 位 = 0）——原版口径
    """
    H, W = v.shape
    g = rlc.Grid(H, W)
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    mG = ~mRB
    g.put('G', G)
    if arr_mode == 'rt':
        arrR = np.where(mG, G, fillR)
        arrB = np.where(mG, G, fillB)
    else:
        arrR, arrB = fillR, fillB
    g.put('arrR', arrR)
    g.put('arrB', arrB)
    d = _disc_diag4(vh)
    g0 = g.win('G')
    outs = {}
    for c, key in ((0, 'arrR'), (2, 'arrB')):
        ch = np.where(mR if c == 0 else mB, v, g.win(key))
        g.put('ch', ch)
        cN1, cS1 = g.win('ch', -1, 0), g.win('ch', 1, 0)
        cW1, cE1 = g.win('ch', 0, -1), g.win('ch', 0, 1)
        NG = EPS_REF + np.abs(g0 - g.win('G', -2, 0)) + np.abs(cN1 - cS1)
        SG = EPS_REF + np.abs(g0 - g.win('G', 2, 0)) + np.abs(cS1 - cN1)
        WG = EPS_REF + np.abs(g0 - g.win('G', 0, -2)) + np.abs(cW1 - cE1)
        EG = EPS_REF + np.abs(g0 - g.win('G', 0, 2)) + np.abs(cE1 - cW1)
        if third:
            NG = NG + np.abs(cN1 - g.win('ch', -3, 0))
            SG = SG + np.abs(cS1 - g.win('ch', 3, 0))
            WG = WG + np.abs(cW1 - g.win('ch', 0, -3))
            EG = EG + np.abs(cE1 - g.win('ch', 0, 3))
        nE, sE = cN1 - g.win('G', -1, 0), cS1 - g.win('G', 1, 0)
        wE, eE = cW1 - g.win('G', 0, -1), cE1 - g.win('G', 0, 1)
        vEst = (NG * sE + SG * nE) / (NG + SG)
        hEst = (EG * wE + WG * eE) / (EG + WG)
        out = g0 + (1.0 - d) * vEst + d * hEst
        if clamp:
            out = np.clip(out, 0.0, 1.0)
        outs[c] = out
    R_out = np.where(mG, outs[0], np.where(mR, v, fillR))
    B_out = np.where(mG, outs[2], np.where(mB, v, fillB))
    return np.stack([R_out, G, B_out])


# ────────────────────────────────────────────────── 三条实现路线
def run_dcraw(v, fc):
    """原版（dcraw 宿主，含 G2 越界）。"""
    R0, G0, B0 = init_dcraw(v, fc)
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    vh = _dir_ref(v, fc)
    lpf = _lpf_ref(v, fc, mRB)
    G, _ = step3(v, fc, vh, lpf, grad='ref', disc='ref', clamp=True)
    # dcraw 的 G 位是实测（G1/G2 都有）；R/B 位是 STEP3 结果
    G = np.where(mRB, G, np.where((fc == 1) | (fc == 3), v, 0.0))
    P = rlc.quad_stat(_grid_of(v), 'cfa', 1, 1)
    Q = rlc.quad_stat(_grid_of(v), 'cfa', 1, -1)
    pq = np.where(mRB, P / (P + Q), 0.0)
    fillR, fillB = step42(v, fc, R0, G, B0, pq, grad='ref', disc='ref', clamp=True)
    rgb = step43(v, fc, G, fillR, fillB, vh, arr_mode='dcraw', clamp=True, third=True)
    return rgb


def _grid_of(v):
    g = rlc.Grid(*v.shape)
    g.put('cfa', v)
    return g


def run_rt(v, fc):
    """RawTherapee 口径（G2 修正 + P1 五点高通 + lpf 1/0.5/0.25）。"""
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    vh = _dir_ours(v, fc)
    lpf = _lpf_ours(v, fc, mRB)
    G, _ = step3(v, fc, vh, lpf, grad='ref', disc='ref', clamp=True)
    G = np.where(mRB, G, np.where((fc == 1) | (fc == 3), v, 0.0))
    P = rlc.quad_stat(_grid_of(v), 'cfa', 1, 1)
    Q = rlc.quad_stat(_grid_of(v), 'cfa', 1, -1)
    pq = np.where(mRB, P / (P + Q), 0.0)
    fillR, fillB = step42(v, fc, None, G, None, pq, grad='ref', disc='ref', clamp=True)
    rgb = step43(v, fc, G, fillR, fillB, vh, arr_mode='rt', clamp=True, third=True)
    return rgb


def run_ours(v, fc, p2grad='ours', p2disc='ours', p3disc='ours', p3clamp=False):
    """我方口径（P2 两项式梯度 / 3×3 判别；P3 十字 5-tap / 无限幅）。"""
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    vh = _dir_ours(v, fc)
    lpf = _lpf_ours(v, fc, mRB)
    G, _ = step3(v, fc, vh, lpf, grad=p2grad, disc=p2disc, clamp=p3clamp)
    G = np.where(mRB, G, np.where((fc == 1) | (fc == 3), v, 0.0))
    # P/Q：我方用 hp5 对角
    g = _grid_of(v)
    A = g.win('cfa')
    hpP = (g.win('cfa', -3, -3) - g.win('cfa', -1, -1) - g.win('cfa', 1, 1)
           + g.win('cfa', 3, 3) - 3.0 * (g.win('cfa', -2, -2) + g.win('cfa', 2, 2)) + 6.0 * A)
    hpQ = (g.win('cfa', 3, -3) - g.win('cfa', 1, -1) - g.win('cfa', -1, 1)
           + g.win('cfa', -3, 3) - 3.0 * (g.win('cfa', 2, -2) + g.win('cfa', -2, 2)) + 6.0 * A)
    g.put('hpP', hpP)
    g.put('hpQ', hpQ)
    ps = sum(g.win('hpP', i, i) ** 2 for i in (-1, 0, 1))
    qs = sum(g.win('hpQ', i, -i) ** 2 for i in (-1, 0, 1))
    pq = ps / (ps + qs + EPS_P1)
    fillR, fillB = step42(v, fc, None, G, None, pq, grad='ours', disc=p3disc, clamp=p3clamp)
    rgb = step43(v, fc, G, fillR, fillB, vh, arr_mode='rt', clamp=p3clamp, third=True)
    return rgb


def dstats(a, b, tag, scale=255.0):
    d = np.abs(a - b) * scale
    d = d[np.isfinite(d)]
    return dict(tag=tag, p50=float(np.percentile(d, 50)), p90=float(np.percentile(d, 90)),
                p99=float(np.percentile(d, 99)), p999=float(np.percentile(d, 99.9)),
                mx=float(d.max()), mean=float(d.mean()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', default='test_temp/FILMKIT_20261007-142415.dng')
    ap.add_argument('--y0', type=int, default=1900)
    ap.add_argument('--y1', type=int, default=2450)
    ap.add_argument('--x0', type=int, default=2000)
    ap.add_argument('--x1', type=int, default=2700)
    ap.add_argument('--json', default='')
    args = ap.parse_args()

    val, fc, meta = rlc.load_input(args.dng)
    vr = val[args.y0:args.y1, args.x0:args.x1]
    fr = fc[args.y0:args.y1, args.x0:args.x1]
    print(f"样片 {meta['dng']} ROI {vr.shape} gain={np.round(meta['gain'],4).tolist()}")

    # 越界证据
    R0, G0, B0 = init_dcraw(vr, fr)
    mG2 = fr == 3
    mG1 = fr == 1
    mR = fr == 0
    print('\n=== dcraw 越界内存语义复现 ===')
    print(f"  G2 位 rgb[1] 全 0: {bool(np.all(G0[mG2] == 0))}   (G2 占 {mG2.mean()*100:.1f}%)")
    print(f"  G1 位 rgb[1] == 实测: {bool(np.allclose(G0[mG1], vr[mG1]))}")
    print(f"  R 位 rgb[0] == 实测: {bool(np.allclose(R0[mR], vr[mR]))}")
    # 被覆盖的 R 位（左邻是 G2）
    left_g2 = np.zeros_like(mR)
    left_g2[:, 1:] = (fr[:, :-1] == 3)
    cov = mR & left_g2
    print(f"  R 位被 G2 越界覆盖比例: {cov.sum()/max(mR.sum(),1)*100:.1f}%"
          f"  (被覆盖处 rgb[0] == 实测: {bool(np.allclose(R0[cov], vr[cov]))})")

    print('\n=== 三方输出对照（/8bit 线性域）===')
    rgb_dc = run_dcraw(vr, fr)
    rgb_rt = run_rt(vr, fr)
    rgb_ou = run_ours(vr, fr)
    rows = []
    for nm, a, b in [('dcraw vs RT', rgb_dc, rgb_rt),
                     ('dcraw vs 我们', rgb_dc, rgb_ou),
                     ('RT vs 我们', rgb_rt, rgb_ou)]:
        for ci, cn in enumerate('RGB'):
            s = dstats(a[ci], b[ci], f'{nm} {cn}')
            rows.append(s)
    print(f"{'对照':<16}{'通道':>5}{'p50':>8}{'p90':>8}{'p99':>9}{'p99.9':>9}{'max':>8}")
    for s in rows:
        nm, cn = s['tag'].rsplit(' ', 1)
        print(f"{nm:<16}{cn:>5}{s['p50']:8.2f}{s['p90']:8.2f}{s['p99']:9.2f}{s['p999']:9.2f}{s['mx']:8.1f}")

    print('\n=== 单变量：把我方逐项换成 RT/原版口径，看 B−G 差怎么变 ===')
    base = rgb_ou
    cfgs = [
        ('我方（现状）', dict()),
        ('P2 梯度→四项', dict(p2grad='ref')),
        ('P2 判别→四对角', dict(p2disc='ref')),
        ('P2 两项都换', dict(p2grad='ref', p2disc='ref')),
        ('P2+P3 全换(≈RT)', dict(p2grad='ref', p2disc='ref', p3disc='ref', p3clamp=True)),
    ]
    print(f"{'配置':<20}{'vs 我方 p99(B-G)':>18}{'vs 我方 max':>13}")
    res = {}
    for nm, kw in cfgs:
        r = run_ours(vr, fr, **kw)
        s = dstats(r[2] - r[1], base[2] - base[1], nm)
        res[nm] = s
        print(f"{nm:<20}{s['p99']:18.2f}{s['mx']:13.1f}")
    if args.json:
        with open(args.json, 'w') as f:
            json.dump(dict(meta=meta, rows=rows, univar=res), f, ensure_ascii=False, indent=1)
        print(f'\n已写 {args.json}')


if __name__ == '__main__':
    main()
