#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RCD 全量逐行对照实验（离线，纯 numpy，无 scipy）。

用途：把 LuisSR RCD Release 2.3 (`rcd_demosaicing.c`, 222 行) 与本项目
`core/engine/src/render/FrameRenderer.cpp` 的 kFsRcdDir(P1) / kFsRcdG(P2) /
kFsRcdChroma(P3) / kFsRcdChromaA(3a) / kFsRcdChromaB(3b) 逐段直译，在同一 mosaic
输入上做「单变量替换」实验，量化每一项差异对「B 缓 G 陡错位」（品红带）的影响。

产物：stdout 表格 + 可选 --json 落盘。脚本本身持久化（不放 /tmp）。

用法：
  python3 tools/rcd-linecompare.py                       # 默认样片 142415
  python3 tools/rcd-linecompare.py --dng <path> --y0 1600 --y1 2200 --x0 400 --x1 3000
  python3 tools/rcd-linecompare.py --variants base,p1ref,step43zero
"""
import argparse
import json
import os
import sys

import numpy as np
import rawpy

EPS_REF = 1e-5     # 原版 rcd_demosaicing.c:15  eps
EPSSQ = 1e-10      # 原版 rcd_demosaicing.c:15  epssq
EPS_G = 1e-6       # GLSL P2/P3/P3b  EPS
EPS_P1 = 1e-9      # GLSL P1  EPS


# ──────────────────────────────────────────────────────────── 输入
def load_input(dng, black_override=None, gain_override=None):
    """返回 (val, fc)：val = 线性化 + WB 的 mosaic（= 本项目 s() 域）。

    域口径（务必与 shader 一致）：
      val = max((raw - bl[phase]) / (white - bl[phase]), 0) * gain[channel]
      gain = DNG AsShotNeutral 倒数、G 归一（= uGain）。
    原版对照时**去掉 max(,0)**（原版 cfa 不裁负），由 --noclamp 控制。
    """
    raw = rawpy.imread(dng)
    C = raw.raw_image.astype(np.float64)
    H, W = C.shape
    pat = np.asarray(raw.raw_pattern, dtype=np.int64)      # [[1,0],[2,3]] GRBG
    fc = np.tile(pat, (H // 2 + 1, W // 2 + 1))[:H, :W]
    bl = np.asarray(raw.black_level_per_channel, dtype=np.float64)
    white = float(raw.white_level)
    if black_override is not None:
        bl = np.asarray(black_override, dtype=np.float64)
    if gain_override is not None:
        g = np.asarray(gain_override, dtype=np.float64)
    else:
        wb = np.asarray(raw.camera_whitebalance, dtype=np.float64)[:3]
        g = wb[1] / wb          # asShotNeutral 倒数、G 归一
    gain = np.array([g[0], g[1], g[2], g[1]])              # fc3 = G2 → G 增益
    val = (C - bl[fc]) / (white - bl[fc]) * gain[fc]
    meta = dict(dng=os.path.basename(dng), shape=[int(H), int(W)],
                pattern=pat.tolist(), black=bl.tolist(), white=white,
                gain=g.tolist(), black_per_phase_used=True)
    return val, fc, meta


# ──────────────────────────────────────────────────────────── 工具
class Grid:
    """ROI 数组 + edge-pad 缓存；win(key, dy, dx) = 每点取 (dy,dx) 偏移。"""

    def __init__(self, H, W, pad=12):
        self.H, self.W, self.pad = H, W, pad
        self._p = {}

    def put(self, key, arr):
        assert arr.shape == (self.H, self.W), (key, arr.shape)
        self._p[key] = np.pad(arr, self.pad, mode='edge')

    def win(self, key, dy=0, dx=0):
        p, pd = self._p[key], self.pad
        return p[pd + dy:pd + dy + self.H, pd + dx:pd + dx + self.W]


# 原版 STEP1/4.1 二次型 40 项：(coef, ka, kb)，k 为沿方向基的距离（0 = 中心）
QTERMS = [
    (-18., -1, 0), (-18., 1, 0), (-36., -2, 0), (-36., 2, 0), (18., -3, 0),
    (18., 3, 0), (-2., -4, 0), (-2., 4, 0), (38., 0, 0),
    (-70., -1, 1), (-12., -1, -2), (24., -1, 2), (-38., -1, -3), (16., -1, 3),
    (12., -1, -4), (-6., -1, 4), (46., -1, -1),
    (24., 1, -2), (-12., 1, 2), (16., 1, -3), (-38., 1, 3), (-6., 1, -4),
    (12., 1, 4), (46., 1, 1),
    (14., -2, 2), (-12., -2, 3), (-2., -2, -4), (2., -2, 4), (11., -2, -2),
    (-12., 2, -3), (2., 2, -4), (-2., 2, 4), (11., 2, 2),
    (2., -3, 3), (-6., -3, -4), (10., -3, -3), (-6., 3, 4), (10., 3, 3),
    (1., -4, -4), (1., 4, 4),
]


def quad_stat(g, key, uy, ux):
    """原版二次型统计量 MAX(Σ w_ij c_i c_j, epssq)。"""
    acc = None
    for coef, ka, kb in QTERMS:
        t = coef * g.win(key, ka * uy, ka * ux) * g.win(key, kb * uy, kb * ux)
        acc = t if acc is None else acc + t
    return np.maximum(acc, EPSSQ)


# ──────────────────────────────────────────────────────────── 原版（可分段关闭）
def rcd_reference(v, fc, step1='ref', step2='ref', step3='ref', step42='ref',
                  step43='ref', border='ref', return_parts=False):
    """LuisSR RCD 2.3 直译。每个 stepN 可换成 'ours' 以做单变量对照。

    step1='ref'  原版二次型 V_Stat/H_Stat（4..H-4 / 4..W-4 外为 0，VH_Dir=0）
    step1='ours' P1 五点修正高通 + 3 邻行/列平方和，全域（= 我方 vconf）
    step2='ref'  原版 lpf（仅 R/B 位有值，权重 0.25/0.125/0.0625）
    step2='ours' 我方 lpf（全域，权重 1/0.5/0.25，即 4× 原版）
    step3='ref'  原版四项梯度 + 4 对角置信度细化 + LIM(0,1)
    step3='ours' 我方两项式梯度 + 3×3 置信度细化 + 无限幅
    step42='ref' 原版三项对角梯度 + 4 对角细化 + LIM
    step42='ours' 我方 chromaDiag(uCdFix=1) + 十字 5-tap 细化 + 无限幅
    step43='ref' 原版四正邻双轴（交叉轴取 4.2 填充色；±3 项在 G 位读 **0**）
    step43='ours' 我方 3b（交叉轴取 3a 填充色；±3 项在 G 位读 **G 占位值**）
    step43='ours_no3' 我方 3b 但第三项剔除（只留两项梯度）——量化该项影响
    border='ref' 4px 边界置 0（近似 dcraw border_interpolate）
    border='edge' edge-pad（近似我方 clamp-to-edge）
    """
    H, W = v.shape
    g = Grid(H, W)
    g.put('cfa', v)
    mR, mB = fc == 0, fc == 2
    mRB = mR | mB
    mG = ~mRB
    inner = np.zeros((H, W), bool)
    inner[4:H - 4, 4:W - 4] = True          # 原版所有循环的公共范围

    # ── STEP 1 方向场
    if step1 == 'ref':
        V = quad_stat(g, 'cfa', 1, 0)
        Hs = quad_stat(g, 'cfa', 0, 1)
        vh = np.where(inner, V / (V + Hs), 0.0)
    else:
        def hp5(uy, ux):
            return (g.win('cfa', -3 * uy, -3 * ux) - g.win('cfa', -uy, -ux)
                    - g.win('cfa', uy, ux) + g.win('cfa', 3 * uy, 3 * ux)
                    - 3.0 * (g.win('cfa', 2 * uy, 2 * ux) + g.win('cfa', -2 * uy, -2 * ux))
                    + 6.0 * g.win('cfa'))
        hv, hh = hp5(1, 0), hp5(0, 1)
        g.put('hv', hv)
        g.put('hh', hh)
        vs = sum(g.win('hv', 0, i) ** 2 for i in (-1, 0, 1))
        hs_ = sum(g.win('hh', i, 0) ** 2 for i in (-1, 0, 1))
        vh = vs / (vs + hs_ + EPS_P1)
    g.put('vh', vh)

    # ── STEP 2 低通
    A = g.win('cfa')
    if step2 == 'ref':
        lpf_raw = (0.25 * A
                   + 0.125 * (g.win('cfa', -1, 0) + g.win('cfa', 1, 0)
                              + g.win('cfa', 0, -1) + g.win('cfa', 0, 1))
                   + 0.0625 * (g.win('cfa', -1, -1) + g.win('cfa', -1, 1)
                               + g.win('cfa', 1, -1) + g.win('cfa', 1, 1)))
        lpf = np.where(mRB, lpf_raw, 0.0)
    else:
        lpf = (A + 0.5 * (g.win('cfa', 0, 1) + g.win('cfa', 0, -1)
                          + g.win('cfa', 1, 0) + g.win('cfa', -1, 0))
               + 0.25 * (g.win('cfa', 1, 1) + g.win('cfa', -1, -1)
                         + g.win('cfa', 1, -1) + g.win('cfa', -1, 1)))
    g.put('lpf', lpf)

    # ── STEP 3 G@RB
    if step3 == 'ref':
        c = g.win('vh')
        nb = 0.25 * (g.win('vh', -1, -1) + g.win('vh', -1, 1)
                     + g.win('vh', 1, -1) + g.win('vh', 1, 1))
        disc = np.where(np.abs(0.5 - c) < np.abs(0.5 - nb), nb, c)
        l0 = g.win('lpf')
        cN, cS = g.win('cfa', -1, 0), g.win('cfa', 1, 0)
        cW, cE = g.win('cfa', 0, -1), g.win('cfa', 0, 1)
        NG = EPS_REF + np.abs(cN - cS) + np.abs(A - g.win('cfa', -2, 0)) \
            + np.abs(cN - g.win('cfa', -3, 0)) + np.abs(g.win('cfa', -2, 0) - g.win('cfa', -4, 0))
        SG = EPS_REF + np.abs(cS - cN) + np.abs(A - g.win('cfa', 2, 0)) \
            + np.abs(cS - g.win('cfa', 3, 0)) + np.abs(g.win('cfa', 2, 0) - g.win('cfa', 4, 0))
        WG = EPS_REF + np.abs(cW - cE) + np.abs(A - g.win('cfa', 0, -2)) \
            + np.abs(cW - g.win('cfa', 0, -3)) + np.abs(g.win('cfa', 0, -2) - g.win('cfa', 0, -4))
        EG = EPS_REF + np.abs(cE - cW) + np.abs(A - g.win('cfa', 0, 2)) \
            + np.abs(cE - g.win('cfa', 0, 3)) + np.abs(g.win('cfa', 0, 2) - g.win('cfa', 0, 4))

        def rest(cadj, dy, dx):
            lN = g.win('lpf', dy, dx)
            return cadj * (1.0 + (l0 - lN) / (EPS_REF + l0 + lN))
        vEst = (SG * rest(cN, -2, 0) + NG * rest(cS, 2, 0)) / (NG + SG)
        hEst = (WG * rest(cE, 0, 2) + EG * rest(cW, 0, -2)) / (EG + WG)
        gRB = np.clip(disc * hEst + (1.0 - disc) * vEst, 0.0, 1.0)
    else:
        c = g.win('vh')
        acc = sum(g.win('vh', dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1))
        nb9 = acc / 9.0
        disc = np.where(np.abs(c - 0.5) > np.abs(nb9 - 0.5), c, nb9)
        l0 = g.win('lpf')
        cN, cS = g.win('cfa', -1, 0), g.win('cfa', 1, 0)
        cW, cE = g.win('cfa', 0, -1), g.win('cfa', 0, 1)
        gn = np.abs(cN - cS) + np.abs(A - g.win('cfa', -2, 0)) + EPS_G
        gs = np.abs(cS - cN) + np.abs(A - g.win('cfa', 2, 0)) + EPS_G
        gw = np.abs(cW - cE) + np.abs(A - g.win('cfa', 0, -2)) + EPS_G
        ge = np.abs(cE - cW) + np.abs(A - g.win('cfa', 0, 2)) + EPS_G
        eN = cN * 2.0 * l0 / (l0 + g.win('lpf', -1, 0) + EPS_G)
        eS = cS * 2.0 * l0 / (l0 + g.win('lpf', 1, 0) + EPS_G)
        eW = cW * 2.0 * l0 / (l0 + g.win('lpf', 0, -1) + EPS_G)
        eE = cE * 2.0 * l0 / (l0 + g.win('lpf', 0, 1) + EPS_G)
        vEst = (gs * eN + gn * eS) / (gn + gs)
        hEst = (gw * eE + ge * eW) / (ge + gw)
        gRB = disc * hEst + (1.0 - disc) * vEst
    G = np.where(mG, A, np.where(mRB, gRB, 0.0))
    g.put('G', G)

    # ── STEP 4.1 P/Q 方向（原版口径；ours 用 hp5 对角）
    if step1 == 'ref':
        P = quad_stat(g, 'cfa', 1, 1)
        Q = quad_stat(g, 'cfa', 1, -1)
        pq = np.where(inner, P / (P + Q), 0.0)
    else:
        hpP = (g.win('cfa', -3, -3) - g.win('cfa', -1, -1) - g.win('cfa', 1, 1)
               + g.win('cfa', 3, 3) - 3.0 * (g.win('cfa', -2, -2) + g.win('cfa', 2, 2))
               + 6.0 * A)
        hpQ = (g.win('cfa', 3, -3) - g.win('cfa', 1, -1) - g.win('cfa', -1, 1)
               + g.win('cfa', -3, 3) - 3.0 * (g.win('cfa', 2, -2) + g.win('cfa', -2, 2))
               + 6.0 * A)
        g.put('hpP', hpP)
        g.put('hpQ', hpQ)
        ps = sum(g.win('hpP', i, i) ** 2 for i in (-1, 0, 1))
        qs = sum(g.win('hpQ', i, -i) ** 2 for i in (-1, 0, 1))
        pq = ps / (ps + qs + EPS_P1)
    g.put('pq', pq)

    # ── STEP 4.2 对角色差填充（R 位补 B / B 位补 R）
    if step42 == 'ref':
        c = g.win('pq')
        nb = 0.25 * (g.win('pq', -1, -1) + g.win('pq', -1, 1)
                     + g.win('pq', 1, -1) + g.win('pq', 1, 1))
        pdisc = np.where(np.abs(0.5 - c) < np.abs(0.5 - nb), nb, c)
    else:
        c = g.win('pq')
        d5 = (c + g.win('pq', -1, 0) + g.win('pq', 1, 0)
              + g.win('pq', 0, -1) + g.win('pq', 0, 1)) / 5.0
        pdisc = d5
    opp = np.where(mRB, A, 0.0)
    g.put('opp', opp)
    g0 = g.win('G')
    cNW, cSE = g.win('opp', -1, -1), g.win('opp', 1, 1)
    cNE, cSW = g.win('opp', -1, 1), g.win('opp', 1, -1)
    if step42 == 'ref':
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
    fill = np.clip(g0 + (1.0 - pdisc) * pEst + pdisc * qEst, 0.0, 1.0)
    # 只落在 R/B 位；G 位保持 0（= 原版未初始化通道；STEP 4.3 的 ±3 项读的就是它）
    fillR = np.where(mB, fill, 0.0)      # B 位的 R
    fillB = np.where(mR, fill, 0.0)      # R 位的 B
    # 我方 3a 语义：G 位占位为 G 值（3b 的 ±3 项读的就是它）
    fillR_ours = np.where(mB, fill, np.where(mG, G, 0.0))
    fillB_ours = np.where(mR, fill, np.where(mG, G, 0.0))

    # ── STEP 4.3 G 位 R/B 填充
    vhd = g.win('vh')
    nbv = 0.25 * (g.win('vh', -1, -1) + g.win('vh', -1, 1)
                  + g.win('vh', 1, -1) + g.win('vh', 1, 1))
    vh_disc = np.where(np.abs(0.5 - vhd) < np.abs(0.5 - nbv), nbv, vhd)
    # ±3 项取哪个数组：原版 = 4.2 数组（G 位 0）；ours = 3a 数组（G 位 = G）
    if step43 == 'ref':
        arrR, arrB = fillR, fillB
    else:
        arrR, arrB = fillR_ours, fillB_ours
    outs = {}
    for c in (0, 2):
        arr = arrR if c == 0 else arrB
        ch = np.where(mR if c == 0 else mB, A, arr)      # 实测位 + 填充位
        g.put('ch', ch)
        cN1, cS1 = g.win('ch', -1, 0), g.win('ch', 1, 0)
        cW1, cE1 = g.win('ch', 0, -1), g.win('ch', 0, 1)
        if step43 == 'ours_no3':
            NG = EPS_REF + np.abs(g0 - g.win('G', -2, 0)) + np.abs(cN1 - cS1)
            SG = EPS_REF + np.abs(g0 - g.win('G', 2, 0)) + np.abs(cS1 - cN1)
            WG = EPS_REF + np.abs(g0 - g.win('G', 0, -2)) + np.abs(cW1 - cE1)
            EG = EPS_REF + np.abs(g0 - g.win('G', 0, 2)) + np.abs(cE1 - cW1)
        else:
            NG = EPS_REF + np.abs(g0 - g.win('G', -2, 0)) + np.abs(cN1 - cS1) + np.abs(cN1 - g.win('ch', -3, 0))
            SG = EPS_REF + np.abs(g0 - g.win('G', 2, 0)) + np.abs(cS1 - cN1) + np.abs(cS1 - g.win('ch', 3, 0))
            WG = EPS_REF + np.abs(g0 - g.win('G', 0, -2)) + np.abs(cW1 - cE1) + np.abs(cW1 - g.win('ch', 0, -3))
            EG = EPS_REF + np.abs(g0 - g.win('G', 0, 2)) + np.abs(cE1 - cW1) + np.abs(cE1 - g.win('ch', 0, 3))
        nE, sE = cN1 - g.win('G', -1, 0), cS1 - g.win('G', 1, 0)
        wE, eE = cW1 - g.win('G', 0, -1), cE1 - g.win('G', 0, 1)
        vEst = (NG * sE + SG * nE) / (NG + SG)
        hEst = (EG * wE + WG * eE) / (EG + WG)
        outs[c] = np.clip(g0 + (1.0 - vh_disc) * vEst + vh_disc * hEst, 0.0, 1.0)
    R_out = np.where(mG, outs[0], np.where(mR, A, fillR))
    B_out = np.where(mG, outs[2], np.where(mB, A, fillB))
    G_out = G
    rgb = np.stack([R_out, G_out, B_out])
    if border == 'ref':
        rgb[:, ~inner] = 0.0
    parts = dict(vh=vh, pq=pq, G=G, fillR=fillR, fillB=fillB, vh_disc=vh_disc,
                 fillR_ours=fillR_ours, fillB_ours=fillB_ours)
    if return_parts:
        return rgb, parts
    return rgb


# ──────────────────────────────────────────────────────────── 测量
def measure(rgb, tag, meas_y0, meas_y1, y0, x0, x1, cols_step=2,
            half_d=20, half_b=80, quiet=False):
    """竖直边缘（沿 sensor x 的亮度跳变）的对齐平均剖面。

    本函数测的是**去马赛克段**在亮暗边缘处产生的色度结构：
      · G / B 的 10-90、20-80 过渡宽度（全分辨率 sensor px）
      · B−G 剖面峰（品红残留，/8bit 线性域）
    剖面方向统一为「暗在左、亮在右」（x 递增 = 暗→亮）；若窗口内边缘是
    亮→暗则自动反转，保证跨变体可比。
    """
    sub_all = rgb[:, meas_y0 - y0:meas_y1 - y0, :]           # (3, L, Nx)
    L, Nx = sub_all.shape[1], sub_all.shape[2]
    # 用 G 的行均值找边缘列（竖直边缘 = 沿 x 的跳变）。
    # 不取全局最大跳变（ROI 边界/暗角会伪造跳变），而取「两侧都有平台」的最强跳变。
    gmean = sub_all[1].mean(axis=0)                          # (Nx,)
    dg = np.diff(gmean)
    if len(dg) == 0:
        if not quiet:
            print(f'[{tag}] 窗口过窄')
        return None
    ipk, best = None, 0.0
    plat = half_d + 4
    for c in np.argsort(np.abs(dg))[::-1][:400]:
        c = int(c)
        if c < plat or c + plat + 2 >= Nx:
            continue
        left = np.median(gmean[max(0, c - plat - 8):c - plat + 1])
        right = np.median(gmean[c + plat:c + plat + 9])
        if abs(left - right) < 0.05:            # 两侧必须真的有台阶
            continue
        lc = np.median(gmean[max(0, c - 3):c + 1])
        rc = np.median(gmean[c + 1:c + 5])
        step = abs(left - right)
        if abs(lc - left) > 0.25 * step + 0.02 or abs(rc - right) > 0.25 * step + 0.02:
            continue
        if abs(dg[c]) > best:
            best, ipk = abs(dg[c]), c
    if ipk is None:
        if not quiet:
            print(f'[{tag}] 未找到两侧有平台的边缘')
        return None
    rising = dg[ipk] > 0
    if rising:
        gprof, bprof = sub_all[1], sub_all[2]
    else:
        gprof, bprof = sub_all[1][:, ::-1], sub_all[2][:, ::-1]
        ipk = Nx - 1 - ipk
        gmean = gmean[::-1]
    # 逐行细化：每行找 G 的最大上升梯度点（限 ±25px），做列对齐
    dgr = np.diff(gprof, axis=1)
    lo = max(0, ipk - 25)
    hi = min(dgr.shape[1], ipk + 26)
    ist = lo + np.argmax(dgr[:, lo:hi], axis=1)
    amp = dgr[np.arange(len(ist)), ist]
    # 每行取固定长窗口（边缘贴边时截断到边界，用 edge 复制补齐）
    Lw = half_d + half_b + 1
    starts = np.clip(ist - half_d, 0, max(0, Nx - Lw))
    win = np.stack([gprof[k, starts[k]:starts[k] + Lw] for k in range(len(ist))], 0)
    contrast = win.max(axis=1) - win.min(axis=1)
    ok = (amp > 0.01) & (contrast > 0.1)
    if ok.sum() < 10:
        if not quiet:
            print(f'[{tag}] 有效行不足（{int(ok.sum())}）')
        return None
    med = np.median(ist[ok])
    keep = ok & (np.abs(ist - med) <= 20) & (ist >= half_d) & (ist < Nx - half_b)
    krows = np.where(keep)[0]
    if len(krows) < 5:
        if not quiet:
            print(f'[{tag}] 无有效行（对齐过滤后 {len(krows)}，边缘中位 {med:.0f}/{Nx}）')
        return None
    sub = np.stack([np.stack([gprof[k, starts[k]:starts[k] + Lw],
                              bprof[k, starts[k]:starts[k] + Lw]], 0)
                    for k in krows], 0)                       # (K, 2, Lw)
    # 亮侧平台校验：G 在 [d0+28, Lw) 段均值 >= 85% 窗口 max
    d0 = half_d
    bright_ok = sub[:, 1, d0 + 28:].mean(axis=1) >= 0.85 * sub[:, 1].max(axis=1)
    sub = sub[bright_ok]
    K = sub.shape[0]
    if K < 5:
        if not quiet:
            print(f'[{tag}] 无有效行（亮侧过滤后 {K}）')
        return None
    # 逐行归一化 + 50% 交叉对齐
    mn = sub.min(axis=2, keepdims=True)
    mx = sub.max(axis=2, keepdims=True)
    with np.errstate(divide='ignore', invalid='ignore'):
        nsub = (sub - mn) / np.maximum(mx - mn, 1e-12) * 100.0
    Gs = nsub[:, 1]
    jc = np.full(K, np.nan)
    for k in range(K):
        p = Gs[k]
        idx = np.where(p >= 50)[0]
        if len(idx) == 0:
            continue
        j = idx[0]
        jc[k] = 0.0 if j == 0 else (j - 1) + (50 - p[j - 1]) / (p[j] - p[j - 1] + 1e-12)
    v = ~np.isnan(jc)
    sub, nsub, jc = sub[v], nsub[v], jc[v]
    if len(jc) < 5:
        if not quiet:
            print(f'[{tag}] 无有效行（50% 交叉失败）')
        return None
    jcm = np.median(jc)
    v2 = np.abs(jc - jcm) <= 6
    sub, nsub, jc = sub[v2], nsub[v2], jc[v2]
    K = len(jc)
    if K < 5:
        if not quiet:
            print(f'[{tag}] 无有效行（对齐过滤后 {K}）')
        return None
    rel = np.round(jc - np.median(jc)).astype(int)
    Na = Lw - (rel.max() - rel.min())
    jj = (rel[None, :] - rel.min()) + np.arange(Na)[:, None]
    kk = np.arange(K)[None, :]
    aligned = nsub[kk, :, jj].transpose(1, 0, 2)             # (2, Na, K)
    aligned_abs = sub[kk, :, jj].transpose(1, 0, 2)
    prof = np.nanmean(aligned, axis=2)
    prof_abs = np.nanmean(aligned_abs, axis=2)

    def cross(p, lv):
        idx = np.where(p >= lv)[0]
        if len(idx) == 0:
            return np.nan
        j = idx[0]
        return 0.0 if j == 0 else (j - 1) + (lv - p[j - 1]) / (p[j] - p[j - 1] + 1e-12)

    x10, x20 = cross(prof[0], 10), cross(prof[0], 20)
    x80, x90 = cross(prof[0], 80), cross(prof[0], 90)
    b10, b20 = cross(prof[1], 10), cross(prof[1], 20)
    b80, b90 = cross(prof[1], 80), cross(prof[1], 90)
    x50 = cross(prof[0], 50)
    bg = prof_abs[1] - prof_abs[0]
    jpk = int(np.argmax(bg))
    peak = bg[jpk] * 255.0
    lo2 = max(0, int(np.floor(x50)) - 8)
    hi2 = min(Na, int(np.ceil(x50)) + 48)
    bgcols = aligned_abs[1, lo2:hi2, :] - aligned_abs[0, lo2:hi2, :]
    k3 = np.ones(3) / 3.0
    bgcols_s = np.vstack([np.convolve(bgcols[:, k], k3, mode='same') for k in range(bgcols.shape[1])])
    with np.errstate(invalid='ignore'):
        percol = np.nanmax(bgcols_s, axis=0) * 255.0
        peak_med = np.nanmedian(percol)
    offs = np.array([-4, 0, 4, 8, 12, 16, 24, 32, 40, 56])
    jx = np.clip(np.round(x50).astype(int) + offs, 0, Na - 1)
    return dict(tag=tag, K=int(K), align_std=float(np.std(jc)),
                g1080=float(x90 - x10), g2080=float(x80 - x20),
                b1080=float(b90 - b10), b2080=float(b80 - b20),
                dbg1080=float((b90 - b10) - (x90 - x10)),
                dbg2080=float((b80 - b20) - (x80 - x20)),
                peak=float(peak), peak_med=float(peak_med),
                off_pk=float(jpk - x50),
                bg_prof=[float(t) for t in np.round(bg[jx] * 255.0, 1)],
                bg_offs=[int(t) for t in offs])


# ──────────────────────────────────────────────────────────── 主流程
VARIANTS = {
    'ref':        dict(step1='ref', step2='ref', step3='ref', step42='ref', step43='ref'),
    'p1ours':     dict(step1='ours'),
    'lpf4x':      dict(step2='ours'),
    'p2ours':     dict(step3='ours'),
    'p42ours':    dict(step42='ours'),
    'p43ours':    dict(step43='ours'),
    'p43no3':     dict(step43='ours_no3'),
    'allours':    dict(step1='ours', step2='ours', step3='ours', step42='ours', step43='ours'),
    'p1p2ours':   dict(step1='ours', step3='ours'),
    'p2p43ours':  dict(step3='ours', step43='ours'),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', default='test_temp/FILMKIT_20261007-142415.dng')
    ap.add_argument('--y0', type=int, default=1900)
    ap.add_argument('--y1', type=int, default=2450)
    ap.add_argument('--x0', type=int, default=2000)
    ap.add_argument('--x1', type=int, default=2700)
    ap.add_argument('--meas-y0', type=int, default=1980)
    ap.add_argument('--meas-y1', type=int, default=2380)
    ap.add_argument('--noclamp', action='store_true', help='原版 cfa 不裁负（默认裁）')
    ap.add_argument('--variants', default=','.join(VARIANTS.keys()))
    ap.add_argument('--json', default='')
    args = ap.parse_args()

    val, fc, meta = load_input(args.dng)
    if args.noclamp:
        raw = rawpy.imread(args.dng)
        C = raw.raw_image.astype(np.float64)
        bl = np.asarray(raw.black_level_per_channel, dtype=np.float64)
        white = float(raw.white_level)
        g = np.asarray(meta['gain'])
        gain = np.array([g[0], g[1], g[2], g[1]])
        val = (C - bl[fc]) / (white - bl[fc]) * gain[fc]
    vr = val[args.y0:args.y1, args.x0:args.x1]
    fr = fc[args.y0:args.y1, args.x0:args.x1]
    print(f"样片 {meta['dng']}  ROI {vr.shape}  输入域 [{vr.min():.3f}, {vr.max():.3f}]  "
          f"gain={np.round(meta['gain'], 4).tolist()}  black={meta['black']}  white={meta['white']}")
    mR, mB = fr == 0, fr == 2
    mG = ~(mR | mB)
    print(f"  满阱(WB 域) = gain[c]：R {meta['gain'][0]:.3f}  G 1.000  B {meta['gain'][2]:.3f}  "
          f"（原版归一域 65535 ⇒ 原版 LIM(0,1) 等价于 R 上限 {1/meta['gain'][0]:.3f}、B {1/meta['gain'][2]:.3f}）")

    rows = []
    keys = [k for k in args.variants.split(',') if k]
    for key in keys:
        kw = dict(VARIANTS[key])
        rgb = rcd_reference(vr, fr, **kw)
        ident = (np.allclose(rgb[1][mG], vr[mG]) and np.allclose(rgb[0][mR], vr[mR])
                 and np.allclose(rgb[2][mB], vr[mB]))
        m = measure(rgb, key, args.meas_y0, args.meas_y1, args.y0, args.x0, args.x1)
        if m is None:
            print(f'  [{key}] 测量失败');  continue
        m['ident'] = bool(ident)
        m['variant'] = key
        m['params'] = kw
        rows.append(m)
        print(f"  [{key:<10}] 有效列={m['K']:4d} 对齐std={m['align_std']:.2f}px  "
              f"G20-80={m['g2080']:6.2f}  B20-80={m['b2080']:6.2f}  B-G={m['dbg2080']:+7.2f}  "
              f"峰={m['peak']:+7.1f}  峰位={m['off_pk']:+6.2f}  每列峰中位={m['peak_med']:+7.1f}")

    hdr = (f"{'变体':<12}{'G10-90':>9}{'B10-90':>9}{'B-G':>8}"
           f"{'G20-80':>9}{'B20-80':>9}{'B-G':>8}{'品红峰':>9}{'峰位':>8}{'峰中位':>9}")
    print('\n=== 汇总（宽度 = 全分辨率 sensor px，成片 px = /2；峰 = /8bit 线性域）===')
    print(hdr)
    for m in rows:
        print(f"{m['variant']:<12}{m['g1080']:9.2f}{m['b1080']:9.2f}{m['dbg1080']:+8.2f}"
              f"{m['g2080']:9.2f}{m['b2080']:9.2f}{m['dbg2080']:+8.2f}"
              f"{m['peak']:+9.1f}{m['off_pk']:+8.2f}{m['peak_med']:+9.1f}")
    print('\n=== B-G 剖面（相对 G 的 50% 交叉点，单位 px；正值 = 亮侧）===')
    for m in rows:
        print(f"{m['variant']:<12} 偏移{m['bg_offs']}\n{'':<12} B-G  {m['bg_prof']}")
    if args.json:
        with open(args.json, 'w') as f:
            json.dump(dict(meta=meta, rows=rows), f, ensure_ascii=False, indent=1)
        print(f'\n已写 {args.json}')


if __name__ == '__main__':
    main()
