#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RCD 差异项合成定量：在原版 RCD 2.3 直译上逐项替换为我方实现，测每项对
「B 缓 G 陡错位」的贡献。

与 tools/rcd-linecompare.py 共用 rcd_reference（单变量 step 开关），
额外提供合成阶跃/细线/彩条场景（真实样片 RAW 域干净、无现成品红可测，
合成场景才能把「去马赛克段的 B−G 错位」从后链里隔离出来）。

用法：
  python3 tools/rcd-linecompare-synth.py                  # 全部场景 × 全部变体
  python3 tools/rcd-linecompare-synth.py --json out.json
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

# 场景：竖直阶跃（沿 x 由暗到亮）+ 细亮线 + 彩条
SCENES = {}


def make_step(H=200, W=300, dark=0.02, bright=0.9, edge=150, tilt=0.0, hue='neutral'):
    """竖直阶跃：x < edge 暗、x >= edge 亮。hue 控制亮侧通道比例。"""
    y = np.arange(H)[:, None]
    x = np.arange(W)[None, :]
    e = edge + tilt * (y - H / 2)
    v = np.where(x >= e, bright, dark)
    if hue == 'neutral':
        ch = np.array([1.0, 1.0, 1.0])
    elif hue == 'warm':
        ch = np.array([1.25, 1.0, 0.7])      # 亮侧偏暖（R>G>B）
    else:
        ch = np.array([0.8, 1.0, 1.3])
    return v, ch


def make_line(H=200, W=300, bg=0.05, line=0.85, w=3, tilt=0.0):
    y = np.arange(H)[:, None]
    x = np.arange(W)[None, :]
    c = 150 + tilt * (y - H / 2)
    v = np.where(np.abs(x - c) <= w, line, bg)
    return v, np.array([1.0, 1.0, 1.0])


def build(scene, H=200, W=300):
    """返回 (val, fc)：按 GRBG 相位把单通道亮度分配到 CFA 位（模拟中性场景）。"""
    if scene.startswith('step'):
        tilt = {'step': 0.0, 'step_tilt': 0.6, 'step_warm': 0.0, 'step_cool': 0.0}[scene]
        hue = {'step_warm': 'warm', 'step_cool': 'cool'}.get(scene, 'neutral')
        v, ch = make_step(H, W, tilt=tilt, hue=hue)
    else:
        v, ch = make_line(H, W, tilt=0.4)
    pat = np.array([[1, 0], [2, 3]])         # GRBG（与 X300 样片同相位）
    fc = np.tile(pat, (H // 2 + 1, W // 2 + 1))[:H, :W]
    # 通道权重：按 fc 把亮度乘上该通道的比例（模拟 WB 域）
    mult = np.zeros((H, W))
    for c in range(3):
        mult[fc == c] = ch[c]
    return v * mult, fc


def build_phase_sweep(scene, H=200, W=320, edges=(140, 141, 142, 143)):
    """同一边缘在多个列相位上的场景串接：消除「边缘恰好落在 G 位/非 G 位」
    带来的采样网格伪影（B 与 G 在 GRBG 下同为偶列，但相位不同）。"""
    vals, fcs = [], []
    pat = np.array([[1, 0], [2, 3]])
    fc = np.tile(pat, (H // 2 + 1, W // 2 + 1))[:H, :W]
    x = np.arange(W)[None, :]
    if scene.startswith('line'):
        base = np.where(np.abs(x - 150) <= 3, 0.85, 0.05)
    else:
        base = np.where(x >= 150, 0.9, 0.02)
    for e in edges:
        v2 = np.roll(base, e - 150, axis=1)
        vals.append(np.repeat(v2, H, axis=0))
        fcs.append(fc)
    return np.concatenate(vals, axis=0), np.concatenate(fcs, axis=0)


VARIANTS = {
    'ref':       dict(),
    'p1ours':    dict(step1='ours'),
    'p2ours':    dict(step3='ours'),
    'p42ours':   dict(step42='ours'),
    'p43ours':   dict(step43='ours'),
    'p43no3':    dict(step43='ours_no3'),
    'allours':   dict(step1='ours', step2='ours', step3='ours', step42='ours', step43='ours'),
}


def measure_edge(rgb, edge_col, half_d=24, half_b=56, integer_align=True):
    """合成场景的边缘测量（整数像素对齐，避免亚像素重采样制造伪影）。

    对齐方式：每行取 G 的最大上升梯度位置（整数），按**中位位置**整体对齐
    （不逐行亚像素重采样）——亚像素对齐会让 G/B 两个不同采样网格被重采样到
    同一相位，从而伪造/抹掉 B−G 错位（首版合成脚本踩过：0.03px 宽度差被
    放大成 +113.9 峰）。
    """
    g = rgb[1][:, edge_col - half_d:edge_col + half_b + 1]
    b = rgb[2][:, edge_col - half_d:edge_col + half_b + 1]
    dgr = np.diff(g, axis=1)
    ist = np.argmax(dgr, axis=1)
    if integer_align:
        shift = int(np.median(ist)) - half_d
        # 整体平移对齐（整数），超出部分丢弃
        if shift >= 0:
            g, b = g[:, shift:], b[:, shift:]
        else:
            g, b = g[:, :shift], b[:, :shift]
    else:
        pass
    # 边缘附近裁剪（去掉两侧远端平台，保证同一长度）
    Lw = min(g.shape[1], 64)
    g, b = g[:, :Lw], b[:, :Lw]
    # 用行均值（不是逐行归一化）—— 保留绝对色差
    prof_g = np.nanmean(g, axis=0)
    prof_b = np.nanmean(b, axis=0)
    bg = prof_b - prof_g
    # 归一化剖面（用于交叉点，用均值域自身的 min/max，全行统一）
    def nz(p):
        return (p - p.min()) / max(p.max() - p.min(), 1e-12) * 100.0
    ng, nb = nz(prof_g), nz(prof_b)

    def cross(p, lv):
        idx = np.where(p >= lv)[0]
        if len(idx) == 0:
            return np.nan
        j = idx[0]
        return 0.0 if j == 0 else (j - 1) + (lv - p[j - 1]) / (p[j] - p[j - 1] + 1e-12)
    x20, x80 = cross(ng, 20), cross(ng, 80)
    b20, b80 = cross(nb, 20), cross(nb, 80)
    x50 = cross(ng, 50)
    b50 = cross(nb, 50)
    jpk = int(np.argmax(bg))
    return dict(K=int(g.shape[0]), g2080=float(x80 - x20), b2080=float(b80 - b20),
                dbg2080=float((b80 - b20) - (x80 - x20)),
                d50=float(b50 - x50),
                peak=float(bg[jpk] * 255.0), off_pk=float(jpk - x50),
                bg_prof=[float(t) for t in np.round(bg * 255.0, 1)],
                prof_g=[float(t) for t in np.round(prof_g, 3)],
                prof_b=[float(t) for t in np.round(prof_b, 3)],
                x50=float(x50), half_d=half_d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--json', default='')
    ap.add_argument('--scenes', default=','.join(SCENES) if SCENES else
                    'step,step_tilt,step_warm,step_cool,line')
    ap.add_argument('--variants', default=','.join(VARIANTS))
    args = ap.parse_args()
    scenes = [s for s in args.scenes.split(',') if s]
    keys = [k for k in args.variants.split(',') if k]
    out = {}
    for sc in scenes:
        val, fc = build_phase_sweep(sc)
        H, W = val.shape
        H1 = H // 4
        edge_cols = [140, 141, 142, 143]
        print(f'\n=== 场景 {sc}  shape={val.shape}  边缘列相位扫描={edge_cols} ===')
        hdr = f"{'变体':<10}{'G20-80':>9}{'B20-80':>9}{'B-G差':>9}{'d50':>8}{'峰':>8}{'峰位':>8}"
        print(hdr)
        rows = {}
        for k in keys:
            rgb = rlc.rcd_reference(val, fc, **VARIANTS[k])
            ms = []
            for i, e in enumerate(edge_cols):
                sub = rgb[:, i * H1:(i + 1) * H1, :]
                ms.append(measure_edge(sub, e))
            agg = {}
            for f in ('g2080', 'b2080', 'dbg2080', 'd50', 'peak', 'off_pk'):
                agg[f] = float(np.mean([m[f] for m in ms]))
            agg['per_phase'] = {f: [float(m[f]) for m in ms] for f in
                                ('g2080', 'b2080', 'dbg2080', 'd50', 'peak')}
            agg['bg_prof'] = [float(np.mean([m['bg_prof'][j] for m in ms]))
                              for j in range(len(ms[0]['bg_prof']))]
            rows[k] = agg
            print(f"{k:<10}{agg['g2080']:9.2f}{agg['b2080']:9.2f}{agg['dbg2080']:+9.2f}"
                  f"{agg['d50']:+8.2f}{agg['peak']:+8.1f}{agg['off_pk']:+8.2f}")
        print('  逐相位 B-G 宽度差:', {k: [round(t, 2) for t in rows[k]['per_phase']['dbg2080']]
                                        for k in keys})
        print('  平均 B-G 剖面（列索引，0 = 窗口左端）:')
        for k in keys:
            print(f"    {k:<10}{[round(t, 1) for t in rows[k]['bg_prof']]}")
        out[sc] = rows
    if args.json:
        with open(args.json, 'w') as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        print(f'\n已写 {args.json}')


if __name__ == '__main__':
    main()
