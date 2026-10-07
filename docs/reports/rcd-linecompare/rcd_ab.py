#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
品红窄带成因 A/B 实验(纯离线,不改仓库;产物只打印到 stdout)。

A) 原版 RCD (rcd_demosaicing.c, LuisSR v2.3) 逐段直译 -> numpy 向量化
B) 本项目 GLSL (kFsRcdDir / kFsRcdG / kFsRcdChroma) 逐段直译, 两种 [56] 模式:
     [56]=0 (当前默认): P2 旧(四邻 G clamp) + axialFill 旧(near0.75+far0.25+clamp)
     [56]=1          : P2 新(无 clamp)        + axialFill 新(±1 近对三项梯度, 无限幅)
   chromaDiag 固定 uCdFix=1(原版三项梯度、无限幅; FilmParams.rcd_cdfix 默认 1.0)
C) 白区左边缘(y_full≈1884, y_s≈942)B/G 过渡测量。

坐标: 全分辨率 sensor。ROI y∈[1600,2200) x∈[400,3000)(=半分辨率 y_s 800..1100, x_s 200..1500)。
剖面沿 y 方向; y 减小 = 成片 x 增大 = 由暗(窗帘)到亮(白窗); 分析前反转成"暗在左、亮在右"。
输入域(两种实现同一 mosaic): val = (raw - bl[fc]) / (1023-64) * gain[fc],
gain=[1/0.4848, 1.0, 1/0.7072](fc3=G2 用 G 增益), bl 按 fc 0..3 = [64,63,64,64]。
GLSL 路径的 s() 带 max(,0)(忠实 shader 语义); 原版 cfa 不裁负(忠实 C 语义)。
"""
import numpy as np
import rawpy

import io, os, sys
DNG = os.environ.get('RCD_DNG', '/Users/wl/Downloads/胶片相机/test_temp/FILMKIT_20261007-142415.dng')
ROI = os.environ.get('RCD_ROI', '1600,2200,400,3000')
Y0, Y1, X0, X1 = [int(v) for v in ROI.split(',')]     # 全分辨率 ROI (Y0,Y1,X0,X1)
PAD = 16                                    # 覆盖链式最深需求(±4)
EPS_REF = 1e-5                              # 原版 eps
EPSSQ = 1e-10                               # 原版 epssq
EPS_G = 1e-6                                # GLSL P2/P3 EPS
EPS_P1 = 1e-9                               # GLSL P1 EPS


# ---------------------------------------------------------------- 输入
def load_input():
    raw = rawpy.imread(io.BytesIO(open(DNG, 'rb').read()))
    C = raw.raw_image.astype(np.float64)                    # (3072, 4080)
    H, W = C.shape
    pat = np.asarray(raw.raw_pattern, dtype=np.int64)       # [[1,0],[2,3]] GRBG
    fc = np.tile(pat, (H // 2 + 1, W // 2 + 1))[:H, :W]
    bl = np.asarray(raw.black_level_per_channel, dtype=np.float64)  # 按 fc 0..3
    white = float(raw.white_level)
    wbf = np.asarray(raw.camera_whitebalance, dtype=np.float64)[:3]
    wbf = wbf / wbf[1]
    gain = np.array([wbf[0], 1.0, wbf[2], 1.0])   # fc3=G2 -> G 增益；由 DNG camera_whitebalance 动态给出
    val = (C - bl[fc]) / (white - 64.0) * gain[fc]
    return val, fc


# ---------------------------------------------------------------- 工具
class Grid:
    """ROI 域数组 + edge-pad 缓存; win2(key,dy,dx) = ROI 每点取 (dy,dx) 偏移值。"""
    def __init__(self, H, W, pad=PAD):
        self.H, self.W, self.pad = H, W, pad
        self._p = {}

    def put(self, key, arr):
        assert arr.shape == (self.H, self.W)
        self._p[key] = np.pad(arr, self.pad, mode='edge')

    def win2(self, key, dy=0, dx=0):
        p = self._p[key]
        pd = self.pad
        return p[pd + dy:pd + dy + self.H, pd + dx:pd + dx + self.W]


# 原版 STEP1/4.1 二次型 40 项: (coef, ka, kb), k 为沿方向基的距离(0=中心)
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
    """V/H/P/Q 统计量: MAX(sum_k w_ij c_i c_j, epssq)。方向基 (uy,ux)。"""
    acc = None
    for coef, ka, kb in QTERMS:
        t = coef * g.win2(key, ka * uy, ka * ux) * g.win2(key, kb * uy, kb * ux)
        acc = t if acc is None else acc + t
    return np.maximum(acc, EPSSQ)


# ================================================================ A. 原版直译
def rcd_reference(val_roi, fc_roi, G_override=None, return_parts=False, axR=None, axB=None, chroma_diff=None):
    H, W = val_roi.shape
    g = Grid(H, W)
    g.put('cfa', val_roi)
    mR = fc_roi == 0
    mB = fc_roi == 2
    mRB = mR | mB
    mG = ~mRB

    # STEP 1: V_Stat/H_Stat + VH_Dir(全位)
    V = quad_stat(g, 'cfa', 1, 0)
    Hs = quad_stat(g, 'cfa', 0, 1)
    g.put('vh', V / (V + Hs))

    # STEP 2: lpf(仅 R/B 位; 0.25c + 0.125 四正邻 + 0.0625 四对角)
    A = g.win2('cfa')
    lpf = (0.25 * A
           + 0.125 * (g.win2('cfa', -1, 0) + g.win2('cfa', 1, 0)
                      + g.win2('cfa', 0, -1) + g.win2('cfa', 0, 1))
           + 0.0625 * (g.win2('cfa', -1, -1) + g.win2('cfa', -1, 1)
                       + g.win2('cfa', 1, -1) + g.win2('cfa', 1, 1)))
    g.put('lpf', np.where(mRB, lpf, 0.0))

    # STEP 3: G@RB(中心 vs 4 对角均值取更确定者; 四项梯度; lpf 隔 2 比值估计; LIM)
    vhc = g.win2('vh')
    vhnb = 0.25 * (g.win2('vh', -1, -1) + g.win2('vh', -1, 1)
                   + g.win2('vh', 1, -1) + g.win2('vh', 1, 1))
    vh_disc = np.where(np.abs(0.5 - vhc) < np.abs(0.5 - vhnb), vhnb, vhc)
    g.put('vh_disc', vh_disc)

    l0 = g.win2('lpf')
    cN1, cS1 = g.win2('cfa', -1, 0), g.win2('cfa', 1, 0)
    cW1, cE1 = g.win2('cfa', 0, -1), g.win2('cfa', 0, 1)
    N_Grad = EPS_REF + np.abs(cN1 - cS1) + np.abs(A - g.win2('cfa', -2, 0)) \
        + np.abs(cN1 - g.win2('cfa', -3, 0)) + np.abs(g.win2('cfa', -2, 0) - g.win2('cfa', -4, 0))
    S_Grad = EPS_REF + np.abs(cS1 - cN1) + np.abs(A - g.win2('cfa', 2, 0)) \
        + np.abs(cS1 - g.win2('cfa', 3, 0)) + np.abs(g.win2('cfa', 2, 0) - g.win2('cfa', 4, 0))
    W_Grad = EPS_REF + np.abs(cW1 - cE1) + np.abs(A - g.win2('cfa', 0, -2)) \
        + np.abs(cW1 - g.win2('cfa', 0, -3)) + np.abs(g.win2('cfa', 0, -2) - g.win2('cfa', 0, -4))
    E_Grad = EPS_REF + np.abs(cE1 - cW1) + np.abs(A - g.win2('cfa', 0, 2)) \
        + np.abs(cE1 - g.win2('cfa', 0, 3)) + np.abs(g.win2('cfa', 0, 2) - g.win2('cfa', 0, 4))

    def ratio_est(cadj, loff):
        lN = g.win2('lpf', loff[0], loff[1])
        return cadj * (1.0 + (l0 - lN) / (EPS_REF + l0 + lN))

    N_Est = ratio_est(cN1, (-2, 0))
    S_Est = ratio_est(cS1, (2, 0))
    W_Est = ratio_est(cW1, (0, -2))
    E_Est = ratio_est(cE1, (0, 2))
    V_Est = (S_Grad * N_Est + N_Grad * S_Est) / (N_Grad + S_Grad)
    H_Est = (W_Grad * E_Est + E_Grad * W_Est) / (E_Grad + W_Grad)
    G_RB = np.clip(vh_disc * H_Est + (1.0 - vh_disc) * V_Est, 0.0, 1.0)
    if G_override is not None:
        G_out_ref = G_override
    else:
        G_out_ref = np.where(mG, A, np.where(mRB, G_RB, 0.0))
    g.put('G', G_out_ref)

    # STEP 4.1: P/Q 方向(仅 R/B 位)
    P = quad_stat(g, 'cfa', 1, 1)
    Q = quad_stat(g, 'cfa', 1, -1)
    g.put('pq', P / (P + Q))

    # STEP 4.2: R@B / B@R(三项对角梯度; PQ_Disc 中心 vs 4 对角; LIM)
    pqc = g.win2('pq')
    pqnb = 0.25 * (g.win2('pq', -1, -1) + g.win2('pq', -1, 1)
                   + g.win2('pq', 1, -1) + g.win2('pq', 1, 1))
    pq_disc = np.where(np.abs(0.5 - pqc) < np.abs(0.5 - pqnb), pqnb, pqc)
    # 对侧色实测数组: R 位中心的四对角/隔 3 是 B 位(取 B 实测), B 位中心取 R 实测
    # —— win2 按"邻位掩码"采样, 故只需在 RB 位存放 A 本身
    opp = np.where(mRB, A, 0.0)
    g.put('opp', opp)
    g0 = g.win2('G')
    cNW, cSE = g.win2('opp', -1, -1), g.win2('opp', 1, 1)
    cNE, cSW = g.win2('opp', -1, 1), g.win2('opp', 1, -1)
    NW_Grad = EPS_REF + np.abs(cNW - cSE) + np.abs(cNW - g.win2('opp', -3, -3)) \
        + np.abs(g0 - g.win2('G', -3, -3))
    SE_Grad = EPS_REF + np.abs(cSE - cNW) + np.abs(cSE - g.win2('opp', 3, 3)) \
        + np.abs(g0 - g.win2('G', 3, 3))
    NE_Grad = EPS_REF + np.abs(cNE - cSW) + np.abs(cNE - g.win2('opp', -3, 3)) \
        + np.abs(g0 - g.win2('G', -3, 3))
    SW_Grad = EPS_REF + np.abs(cSW - cNE) + np.abs(cSW - g.win2('opp', 3, -3)) \
        + np.abs(g0 - g.win2('G', 3, -3))
    NW_Est = cNW - g.win2('G', -1, -1)
    SE_Est = cSE - g.win2('G', 1, 1)
    NE_Est = cNE - g.win2('G', -1, 1)
    SW_Est = cSW - g.win2('G', 1, -1)
    P_Est = (NW_Grad * SE_Est + SE_Grad * NW_Est) / (NW_Grad + SE_Grad)
    Q_Est = (NE_Grad * SW_Est + SW_Grad * NE_Est) / (NE_Grad + SW_Grad)
    if chroma_diff is not None:   # 交叉实验: 用我们 chromaDiag 的色差
        fill = np.clip(g0 + chroma_diff, 0.0, 1.0)
    else:
        fill = np.clip(g0 + (1.0 - pq_disc) * P_Est + pq_disc * Q_Est, 0.0, 1.0)
    fillR = np.where(mB, fill, 0.0)   # B 位的 R(STEP4.2 填充)
    fillB = np.where(mR, fill, 0.0)   # R 位的 B(STEP4.2 填充)

    # STEP 4.3: R@G / B@G(四正邻; 轴对=实测/填充, 交叉轴=STEP4.2 填充色; LIM)
    vhd = g.win2('vh_disc')
    outs = {}
    for c in (0, 2):
        ch = np.where(mR if c == 0 else mB, A, fillR if c == 0 else fillB)
        g.put(f'ch{c}', ch)
        gV1m, gV1p = g.win2('G', -1, 0), g.win2('G', 1, 0)
        gH1m, gH1p = g.win2('G', 0, -1), g.win2('G', 0, 1)
        chV1m, chV1p = g.win2(f'ch{c}', -1, 0), g.win2(f'ch{c}', 1, 0)
        chH1m, chH1p = g.win2(f'ch{c}', 0, -1), g.win2(f'ch{c}', 0, 1)
        N_Grad = EPS_REF + np.abs(g0 - g.win2('G', -2, 0)) + np.abs(chV1m - chV1p) \
            + np.abs(chV1m - g.win2(f'ch{c}', -3, 0))
        S_Grad = EPS_REF + np.abs(g0 - g.win2('G', 2, 0)) + np.abs(chV1p - chV1m) \
            + np.abs(chV1p - g.win2(f'ch{c}', 3, 0))
        W_Grad = EPS_REF + np.abs(g0 - g.win2('G', 0, -2)) + np.abs(chH1m - chH1p) \
            + np.abs(chH1m - g.win2(f'ch{c}', 0, -3))
        E_Grad = EPS_REF + np.abs(g0 - g.win2('G', 0, 2)) + np.abs(chH1p - chH1m) \
            + np.abs(chH1p - g.win2(f'ch{c}', 0, 3))
        N_Est = chV1m - gV1m
        S_Est = chV1p - gV1p
        W_Est = chH1m - gH1m
        E_Est = chH1p - gH1p
        V_Est = (N_Grad * S_Est + S_Grad * N_Est) / (N_Grad + S_Grad)
        H_Est = (E_Grad * W_Est + W_Grad * E_Est) / (E_Grad + W_Grad)
        est = np.clip(g0 + (1.0 - vhd) * V_Est + vhd * H_Est, 0.0, 1.0)
        if c == 0 and axR is not None:
            est = axR
        if c == 2 and axB is not None:
            est = axB
        outs[c] = est

    R_out = np.where(mG, outs[0], np.where(mR, A, fillR))
    G_out = g.win2('G')
    B_out = np.where(mG, outs[2], np.where(mB, A, fillB))
    rgb = np.stack([R_out, G_out, B_out])
    if return_parts:
        return rgb, dict(G=G_out, fillR=fillR, fillB=fillB, pq=pq_disc)
    return rgb


# ================================================================ B. GLSL 直译
def rcd_glsl(val_roi, fc_roi, g_orig, G_override=None, pq_override=None, chromaR=None, chromaB=None, return_chroma=False):
    """g_orig=0: [56]=0 旧行为(P2 clamp + axialFill 旧); =1: 新行为。chromaDiag 固定 uCdFix=1。"""
    H, W = val_roi.shape
    g = Grid(H, W)
    As = np.maximum(val_roi, 0.0)          # GLSL s(): max(...,0)
    g.put('s', As)
    A = g.win2('s')
    mR = fc_roi == 0
    mB = fc_roi == 2
    mRB = mR | mB
    mG = ~mRB

    # ---- P1: 五点修正高通 + 3 邻行/列平方和; lpf = c + 0.5 正邻 + 0.25 对角
    def hp5(uy, ux):
        return (g.win2('s', -3 * uy, -3 * ux) - g.win2('s', -uy, -ux)
                - g.win2('s', uy, ux) + g.win2('s', 3 * uy, 3 * ux)
                - 3.0 * (g.win2('s', 2 * uy, 2 * ux) + g.win2('s', -2 * uy, -2 * ux))
                + 6.0 * A)

    hpV, hpH = hp5(1, 0), hp5(0, 1)
    hpP, hpQ = hp5(1, 1), hp5(1, -1)
    g.put('hpV', hpV); g.put('hpH', hpH); g.put('hpP', hpP); g.put('hpQ', hpQ)
    # vs(p) = Σ_i hpV(p+(0,i))² 等 —— 对 hp 场再做 ±1 偏移求和
    def sqsum(key, offs):
        acc = None
        for dy, dx in offs:
            t = g.win2(key, dy, dx) ** 2
            acc = t if acc is None else acc + t
        return acc
    vs = sqsum('hpV', [(0, i) for i in (-1, 0, 1)])
    hs = sqsum('hpH', [(i, 0) for i in (-1, 0, 1)])
    ps = sqsum('hpP', [(i, i) for i in (-1, 0, 1)])
    qs = sqsum('hpQ', [(-i, i) for i in (-1, 0, 1)])   # hpQ(p+ivec2(i,-i)): dx=i, dy=-i
    g.put('vconf', vs / (vs + hs + EPS_P1))
    g.put('pconf', ps / (ps + qs + EPS_P1))
    g.put('lpf', A + 0.5 * (g.win2('s', 0, 1) + g.win2('s', 0, -1)
                            + g.win2('s', 1, 0) + g.win2('s', -1, 0))
          + 0.25 * (g.win2('s', 1, 1) + g.win2('s', -1, -1)
                    + g.win2('s', 1, -1) + g.win2('s', -1, 1)))

    # ---- P2: G@RB(两项式梯度 + lpf(±1) 比值估计; 中心 vs 3×3 均值取更确定者)
    l0 = g.win2('lpf')
    c0 = A
    cN, cS = g.win2('s', -1, 0), g.win2('s', 1, 0)
    cE, cW = g.win2('s', 0, 1), g.win2('s', 0, -1)
    gn = np.abs(cN - cS) + np.abs(c0 - g.win2('s', -2, 0)) + EPS_G
    gs = np.abs(cS - cN) + np.abs(c0 - g.win2('s', 2, 0)) + EPS_G
    eN = cN * 2.0 * l0 / (l0 + g.win2('lpf', -1, 0) + EPS_G)
    eS = cS * 2.0 * l0 / (l0 + g.win2('lpf', 1, 0) + EPS_G)
    vEst = (gs * eN + gn * eS) / (gn + gs)
    ge = np.abs(cE - cW) + np.abs(c0 - g.win2('s', 0, 2)) + EPS_G
    gw = np.abs(cW - cE) + np.abs(c0 - g.win2('s', 0, -2)) + EPS_G
    eE = cE * 2.0 * l0 / (l0 + g.win2('lpf', 0, 1) + EPS_G)
    eW = cW * 2.0 * l0 / (l0 + g.win2('lpf', 0, -1) + EPS_G)
    hEst = (gw * eE + ge * eW) / (ge + gw)
    vc = g.win2('vconf')
    nb9 = (g.win2('vconf', -1, -1) + g.win2('vconf', -1, 0) + g.win2('vconf', -1, 1)
           + g.win2('vconf', 0, -1) + vc + g.win2('vconf', 0, 1)
           + g.win2('vconf', 1, -1) + g.win2('vconf', 1, 0) + g.win2('vconf', 1, 1)) / 9.0
    dm = np.where(np.abs(vc - 0.5) > np.abs(nb9 - 0.5), vc, nb9)
    gRB = vEst * (1.0 - dm) + hEst * dm
    if g_orig < 0.5:   # 旧: clamp 到四邻实测 G 范围
        lo = np.minimum(np.minimum(cN, cS), np.minimum(cE, cW))
        hi = np.maximum(np.maximum(cN, cS), np.maximum(cE, cW))
        gRB = np.clip(gRB, lo, hi)
    G_out = np.where(mG, c0, gRB) if G_override is None else G_override
    g.put('G', G_out)
    g0 = g.win2('G')

    # ---- P3: chromaDiag(uCdFix=1 原版三项梯度、无限幅)
    cnw, cse = g.win2('s', -1, -1), g.win2('s', 1, 1)
    cne, csw = g.win2('s', -1, 1), g.win2('s', 1, -1)
    gnw = np.abs(cnw - cse) + np.abs(cnw - g.win2('s', -3, -3)) + np.abs(g0 - g.win2('G', -2, -2)) + EPS_G
    gse = np.abs(cse - cnw) + np.abs(cse - g.win2('s', 3, 3)) + np.abs(g0 - g.win2('G', 2, 2)) + EPS_G
    gne = np.abs(cne - csw) + np.abs(cne - g.win2('s', -3, 3)) + np.abs(g0 - g.win2('G', 2, -2)) + EPS_G
    gsw = np.abs(csw - cne) + np.abs(csw - g.win2('s', 3, -3)) + np.abs(g0 - g.win2('G', -2, 2)) + EPS_G
    pEst = (gnw * (cse - g.win2('G', 1, 1)) + gse * (cnw - g.win2('G', -1, -1))) / (gnw + gse)
    qEst = (gne * (csw - g.win2('G', 1, -1)) + gsw * (cne - g.win2('G', -1, 1))) / (gne + gsw)
    if pq_override is None:
        pc = g.win2('pconf')
        d5 = (pc + g.win2('pconf', -1, 0) + g.win2('pconf', 1, 0)
              + g.win2('pconf', 0, -1) + g.win2('pconf', 0, 1)) / 5.0
    else:
        d5 = pq_override                          # 交叉实验: 用原版 PQ_Disc
    dChroma = pEst * (1.0 - d5) + qEst * d5      # uCdFix=1: 无限幅

    # ---- P3: axialFill(G 位; 轴由 chanAt(p+(1,0))==c 判定)
    fcx1 = np.roll(fc_roi, -1, axis=1)   # fcx1[y,x] = fc[y,x+1]; 末列环绕值不影响测量区

    def axial(c):
        axisH = fcx1 == c                        # d=(1,0)(水平) iff chanAt(p+(1,0))==c
        c1 = np.where(axisH, g.win2('s', 0, 1), g.win2('s', 1, 0))
        c2 = np.where(axisH, g.win2('s', 0, -1), g.win2('s', -1, 0))
        d1 = c1 - np.where(axisH, g.win2('G', 0, 1), g.win2('G', 1, 0))
        d2 = c2 - np.where(axisH, g.win2('G', 0, -1), g.win2('G', -1, 0))
        c3 = np.where(axisH, g.win2('s', 0, 3), g.win2('s', 3, 0))
        c4 = np.where(axisH, g.win2('s', 0, -3), g.win2('s', -3, 0))
        if g_orig > 0.5:   # 新: ±1 近对, 三项梯度, 无限幅
            g2m = np.where(axisH, g.win2('G', 0, -2), g.win2('G', -2, 0))
            g2p = np.where(axisH, g.win2('G', 0, 2), g.win2('G', 2, 0))
            gN = EPS_G + np.abs(g0 - g2m) + np.abs(c2 - c1) + np.abs(c2 - c4)
            gS = EPS_G + np.abs(g0 - g2p) + np.abs(c1 - c2) + np.abs(c1 - c3)
            return (gN * d1 + gS * d2) / (gN + gS)
        # 旧: near 0.75 + far(±3 均值) 0.25, clamp 到 ±1/±3 色差范围
        d3 = c3 - np.where(axisH, g.win2('G', 0, 3), g.win2('G', 3, 0))
        d4 = c4 - np.where(axisH, g.win2('G', 0, -3), g.win2('G', -3, 0))
        g1 = np.abs(c1 - c2) + EPS_G
        g2 = np.abs(c3 - c4) + EPS_G
        near = (g2 * d1 + g1 * d2) / (g1 + g2)
        far = 0.5 * (d3 + d4)
        lo = np.minimum(np.minimum(d1, d2), np.minimum(d3, d4))
        hi = np.maximum(np.maximum(d1, d2), np.maximum(d3, d4))
        return np.clip(0.25 * far + 0.75 * near, lo, hi)

    fillR_o = g0 + dChroma if chromaR is None else chromaR   # R 位的 B
    fillB_o = g0 + dChroma if chromaB is None else chromaB   # B 位的 R
    R_out = np.where(mG, g0 + axial(0), np.where(mR, A, fillB_o))
    B_out = np.where(mG, g0 + axial(2), np.where(mB, A, fillR_o))
    if return_chroma:
        return None, dChroma
    G_final = G_out
    return np.stack([R_out, G_final, B_out])


# ================================================================ C. 测量
# 边缘几何(由成片域确认): 白区左边缘在成片上是斜线(成片 x_j≈1000..1330 段),
# sensor 域每列的边缘行不同 -> 剖面窗口放宽到 y_full [1742,2172), 依靠
# "每列归一 + 对齐 G 最大上升梯度点 + 跨列平均" 把斜边拉直(任务参照方法)。
# 剖面方向: seg 反转后 index 增大 = y_full 减小 = 成片 x_j 增大 = 暗(窗帘)->亮(白窗)。
_m = os.environ.get('RCD_MEAS', '1742,2172')
MEAS_Y0, MEAS_Y1 = [int(v) for v in _m.split(',')]   # 全分辨率行窗口


def measure(rgb, tag):
    """rgb: (3, 600, 2600) WB 域线性。白区左边缘剖面测量(两轮: 粗定位 + 子窗口 50% 交叉对齐)。"""
    cols = np.arange(600, 2800, 2) - X0            # x_s 300..1400 -> x_full 偶数列
    seg_full = rgb[:, MEAS_Y0 - Y0:MEAS_Y1 - Y0, cols][:, ::-1, :]   # 暗->亮
    Nw, Nc = seg_full.shape[1], seg_full.shape[2]
    with np.errstate(divide='ignore', invalid='ignore'):
        normF = (seg_full - seg_full.min(axis=1, keepdims=True)) / \
                np.maximum(seg_full.max(axis=1, keepdims=True) - seg_full.min(axis=1, keepdims=True), 1e-12) * 100.0
    dG = np.diff(normF[1], axis=0)
    ist = np.argmax(dG, axis=0)
    amp = np.max(dG, axis=0)
    rngF = seg_full.max(axis=1) - seg_full.min(axis=1)
    base_ok = (rngF[1] > 0.15) & (rngF.min(axis=0) > 1e-6) & (amp > 8.0)
    med = np.median(ist[base_ok])
    HALF_D, HALF_B = 20, 80                    # 子窗口: 暗侧/亮侧半宽(全分辨率 px)
    keep = base_ok & (np.abs(ist - med) <= 40) & (ist >= HALF_D) & (ist < Nw - HALF_B)
    kcols = np.where(keep)[0]
    if len(kcols) == 0:
        print(f'[{tag}] 无有效列');  return None

    # 第二轮: 每列以 ist 为中心取非对称子窗口(暗 HALF_D / 亮 HALF_B), 窗口内重新归一,
    # 50% 交叉点对齐; 品红带在边缘亮侧 ~±20px(全分辨率), 亮侧 44px 供 B 爬到 90%。
    sub = np.stack([seg_full[:, int(ist[c]) - HALF_D:int(ist[c]) + HALF_B + 1, c]
                    for c in kcols], axis=2)               # (3, L, K)
    L = sub.shape[1]
    d0 = HALF_D                                             # 粗定位边缘在子窗口内的索引
    rng0 = sub.max(axis=1) - sub.min(axis=1)
    good = (rng0[1] > 0.15) & (rng0.min(axis=0) > 1e-6)
    sub, kcols = sub[:, :, good], kcols[good]
    rng0 = rng0[:, good]
    # 亮侧仍在亮带: G 在 [d0+28, L) 段均值 >= 85% x 窗口 max(G)
    gseg = sub[1, d0 + 28:, :]
    bright_ok = gseg.mean(axis=0) >= 0.85 * sub[1].max(axis=0)
    sub, kcols, rng0 = sub[:, :, bright_ok], kcols[bright_ok], rng0[:, bright_ok]
    K = sub.shape[2]
    if K == 0:
        print(f'[{tag}] 无有效列');  return None
    mn = sub.min(axis=1, keepdims=True)
    mx = sub.max(axis=1, keepdims=True)
    rng = mx - mn
    with np.errstate(divide='ignore', invalid='ignore'):
        nsub = (sub - mn) / np.maximum(rng, 1e-12) * 100.0
    # 每列 G 的 50% 交叉(上升沿)
    jc = np.full(K, np.nan)
    Gs = nsub[1]
    for k in range(K):
        p = Gs[:, k]
        idx = np.where(p >= 50)[0]
        if len(idx) == 0:
            continue
        j = idx[0]
        if j == 0:
            jc[k] = 0.0
            continue
        j0 = j - 1
        jc[k] = j0 + (50 - p[j0]) / (p[j] - p[j0] + 1e-12)
    valid = ~np.isnan(jc)
    sub, nsub, jc, kcols = sub[:, :, valid], nsub[:, :, valid], jc[valid], kcols[valid]
    # 剔除 50% 交叉点离中位 >6px 的列(边缘检测失败)
    jcm0 = np.median(jc)
    v2 = np.abs(jc - jcm0) <= 6
    sub, nsub, jc, kcols = sub[:, :, v2], nsub[:, :, v2], jc[v2], kcols[v2]
    K = sub.shape[2]
    if K == 0:
        print(f'[{tag}] 无有效列');  return None
    # 对齐: 公共坐标 j 对应各列原索引 jc[k] − rel_min + j
    rel = np.round(jc - np.median(jc)).astype(int)
    Na = L - (rel.max() - rel.min())
    jj = (rel[None, :] - rel.min()) + np.arange(Na)[:, None]
    cc = np.arange(K)[None, :]
    aligned = nsub[:, jj, cc]
    aligned_abs = sub[:, jj, cc]
    prof = np.nanmean(aligned, axis=2)
    prof_abs = np.nanmean(aligned_abs, axis=2)

    def cross(p, lv):
        if np.all(np.isnan(p)):
            return np.nan
        j = int(np.argmax(p >= lv))
        if p[j] < lv:
            return np.nan
        if j == 0:
            return 0.0
        j0 = j - 1
        return j0 + (lv - p[j0]) / (p[j] - p[j0] + 1e-12)

    x20, x80 = cross(prof[1], 20), cross(prof[1], 80)
    x10, x90 = cross(prof[1], 10), cross(prof[1], 90)
    b20, b80 = cross(prof[2], 20), cross(prof[2], 80)
    b10, b90 = cross(prof[2], 10), cross(prof[2], 90)
    x50 = cross(prof[1], 50)

    bg = prof_abs[2] - prof_abs[1]
    jpk = int(np.argmax(bg))
    peak = bg[jpk] * 255.0
    off_pk = jpk - x50                             # 正 = 亮侧(成片 x 增大)
    trunc = jpk >= Na - 3                          # 峰贴窗口右端 = 未收敛
    lo = max(0, int(np.floor(x50)) - 6)
    hi = min(Na, int(np.ceil(x50)) + 42)
    bgcols = aligned_abs[2, lo:hi, :] - aligned_abs[1, lo:hi, :]
    k3 = np.ones(3) / 3.0    # 沿剖面 3px 平滑, 抑制孤立坏点
    bgcols_s = np.vstack([np.convolve(bgcols[:, k], k3, mode='same') for k in range(bgcols.shape[1])])
    with np.errstate(invalid='ignore'):
        percol = np.nanmax(bgcols_s, axis=0) * 255.0
        peak_med = np.nanmedian(percol)

    # 边缘位置确认: 保留列粗定位 ist -> y_full = MEAS_Y1-1-ist -> 成片 x_j = 3072-y_full
    yfull_med = np.median((MEAS_Y1 - 1) - ist[kcols])
    xj_med = 3072 - yfull_med
    # 9 点抽样(全分辨率 px, j50-4..j50+4; 半分辨率成片 px = /2)
    js = np.round(x50).astype(int) + np.arange(-4, 5)
    js = np.clip(js, 0, Na - 1)
    samp_g = np.round(prof[1][js], 1)
    samp_b = np.round(prof[2][js], 1)
    samp_bg = np.round(bg[js] * 255.0, 1)
    print(f'\n[{tag}] 有效列={K} 子窗长={L} 对齐残差(50%交叉)std={np.std(jc):.2f}px '
          f'边缘: y_full中位={yfull_med:.0f} 成片x_j中位={xj_med:.0f}(任务≈1188)')
    print(f'  G: 10-90={x90-x10:.2f}px 20-80={x80-x20:.2f}px (x50={x50:.2f}) [全分辨率; /2=成片px]')
    print(f'  B: 10-90={b90-b10:.2f}px 20-80={b80-b20:.2f}px')
    print(f'  B-G 宽度差: 10-90={(b90-b10)-(x90-x10):+.2f}px 20-80={(b80-b20)-(x80-x20):+.2f}px')
    print(f'  品红峰(B-G max)={peak:+.1f}/8bit  峰位偏移={off_pk:+.2f}px(正=亮侧){" [窗口截断!]" if trunc else ""}  每列峰中位={peak_med:+.1f}')
    offs = np.array([-8, -4, 0, 4, 8, 12, 16, 24, 32, 40, 56, 72])
    jx = np.clip(np.round(x50).astype(int) + offs, 0, Na - 1)
    print(f'  G 归一剖面 9 点(j50-4..+4): {samp_g}')
    print(f'  B 归一剖面 9 点            : {samp_b}')
    print(f'  B-G(8bit) 9 点            : {samp_bg}')
    print(f'  B-G 扩展剖面(偏移 {list(offs)}): {np.round(bg[jx] * 255.0, 1)}')
    return dict(g1080=x90 - x10, g2080=x80 - x20, b1080=b90 - b10, b2080=b80 - b20,
                dbg1080=(b90 - b10) - (x90 - x10), dbg2080=(b80 - b20) - (x80 - x20),
                peak=peak, off=off_pk, peak_med=peak_med)


# ================================================================ 主流程
def main():
    val, fc = load_input()
    vr = val[Y0:Y1, X0:X1]
    fr = fc[Y0:Y1, X0:X1]
    print(f'ROI {vr.shape}, 输入范围 [{vr.min():.3f}, {vr.max():.3f}]')

    print('\n=== A. 原版 RCD 直译 ===')
    ref = rcd_reference(vr, fr)
    # 恒等性检查
    mR, mB = fr == 0, fr == 2
    mG = ~(mR | mB)
    print('  恒等检查 G@G=%s R@R=%s B@B=%s' % (
        np.allclose(ref[1][mG], vr[mG]), np.allclose(ref[0][mR], vr[mR]),
        np.allclose(ref[2][mB], vr[mB])))
    print('  输出范围 [%.3f, %.3f]' % (ref.min(), ref.max()))
    m_ref = measure(ref, 'REF 原版 RCD')

    print('\n=== B. GLSL 直译 [56]=0 (P2旧+P3旧, 当前默认) ===')
    o0 = rcd_glsl(vr, fr, g_orig=0.0)
    print('  恒等检查 G@G=%s R@R=%s B@B=%s' % (
        np.allclose(o0[1][mG], np.maximum(vr, 0)[mG]),
        np.allclose(o0[0][mR], np.maximum(vr, 0)[mR]),
        np.allclose(o0[2][mB], np.maximum(vr, 0)[mB])))
    m_0 = measure(o0, 'OURS [56]=0')

    print('\n=== B. GLSL 直译 [56]=1 (P2新+P3新) ===')
    o1 = rcd_glsl(vr, fr, g_orig=1.0)
    m_1 = measure(o1, 'OURS [56]=1')

    print('\n=== 交叉组合: P2/P3 定位 ===')
    ref_rgb, ref_parts = rcd_reference(vr, fr, return_parts=True)
    mixA = rcd_glsl(vr, fr, g_orig=0.0, G_override=ref_parts['G'])
    m_A = measure(mixA, 'A: 原版G + 我们P3')
    ours_G = rcd_glsl(vr, fr, g_orig=0.0)[1]
    mixB = rcd_reference(vr, fr, G_override=ours_G)
    m_B = measure(mixB, 'B: 我们G + 原版P3')
    mixC = rcd_glsl(vr, fr, g_orig=0.0, G_override=ref_parts['G'], pq_override=ref_parts['pq'])
    m_C = measure(mixC, 'C: 原版G+我们P3但PQ用原版')
    mixD = rcd_glsl(vr, fr, g_orig=0.0, G_override=ref_parts['G'],
                    chromaR=ref_parts['fillB'], chromaB=ref_parts['fillR'])
    m_D = measure(mixD, 'D: 原版G+原版4.2+我们axial')
    _, dA = rcd_glsl(vr, fr, g_orig=0.0, G_override=ref_parts['G'], return_chroma=True)
    mixE = rcd_reference(vr, fr, G_override=ref_parts['G'], chroma_diff=dA)
    m_E = measure(mixE, 'E: 原版G+我们chromaDiag色差+原版4.3')

    hdr = (f"{'实现':<24}{'G20-80':>8}{'B20-80':>8}{'B-G差':>9}"
           f"{'G10-90':>8}{'B10-90':>8}{'B-G差':>9}{'品红峰':>8}{'峰偏移':>9}{'峰中位':>8}")
    print('\n=== 汇总(宽度单位: 全分辨率 sensor px, 半分辨率成片 px = /2; 峰/峰中位: /8bit 线性) ===')
    print(hdr)
    rows = (('原版 RCD', m_ref), ('我们 [56]=0', m_0), ('我们 [56]=1', m_1),
            ('A:原版G+我们P3', m_A), ('B:我们G+原版P3', m_B),
            ('C:同A但PQ原版', m_C), ('D:原版4.2+我们axial', m_D),
            ('E:我们chromaDiag+原版4.3', m_E))
    def f(v, w, fmt):
        return (fmt.format(v) if v == v else 'n/a'.rjust(w))
    for name, m in rows:
        print(f"{name:<24}"
              + f(m['g2080'], 8, '{:8.2f}') + f(m['b2080'], 8, '{:8.2f}') + f(m['dbg2080'], 9, '{:+9.2f}')
              + f(m['g1080'], 8, '{:8.2f}') + f(m['b1080'], 8, '{:8.2f}') + f(m['dbg1080'], 9, '{:+9.2f}')
              + f(m['peak'], 8, '{:+8.1f}') + f(m['off'], 9, '{:+9.2f}') + f(m['peak_med'], 8, '{:+8.1f}'))


if __name__ == '__main__':
    main()
