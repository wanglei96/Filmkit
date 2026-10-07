#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RCD 差异项的直接数值对比（不依赖剖面/峰值这些间接指标）。

思路：在**同一输入**上跑原版 RCD 直译与我方 P1/P2/P3 直译，逐项替换，
直接统计输出差（WB 域 / 8bit 尺度）：
  · 各阶段中间量差（VH 方向场 / lpf / G 通道 / 4.2 填充 / 4.3 输出）
  · 沿高对比边缘的 B−G 剖面形状（绝对域，不做逐行归一化）
  · 差异的像素分布（p50/p90/p99/max）与「差异集中在哪类位置」

真实样片 RAW 域本身干净（无现成品红，见 docs/reports/rcd-linecompare/REPORT），
所以判据必须是「实现间差异」而不是「成片品红量」。

用法：
  python3 tools/rcd-linecompare-diff.py                       # 默认样片
  python3 tools/rcd-linecompare-diff.py --dng ... --json out.json
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


def stats(a, tag, ref_scale=255.0):
    a = np.asarray(a, dtype=np.float64).ravel()
    a = a[np.isfinite(a)]
    if a.size == 0:
        return dict(tag=tag, n=0)
    ab = np.abs(a)
    return dict(tag=tag, n=int(a.size),
                mean=float(a.mean() * ref_scale),
                abs_p50=float(np.percentile(ab, 50) * ref_scale),
                abs_p90=float(np.percentile(ab, 90) * ref_scale),
                abs_p99=float(np.percentile(ab, 99) * ref_scale),
                abs_p999=float(np.percentile(ab, 99.9) * ref_scale),
                abs_max=float(ab.max() * ref_scale))


def edge_profile(rgb, x_center, y0, y1, half=40, tag=''):
    """沿 sensor x 的绝对域剖面（不做逐行归一化）：返回 R/G/B 与 B−G。"""
    sl = slice(x_center - half, x_center + half + 1)
    pR = np.nanmean(rgb[0][y0:y1, sl], axis=0)
    pG = np.nanmean(rgb[1][y0:y1, sl], axis=0)
    pB = np.nanmean(rgb[2][y0:y1, sl], axis=0)
    return dict(tag=tag, x=list(range(x_center - half, x_center + half + 1)),
                R=[float(t) for t in np.round(pR, 4)],
                G=[float(t) for t in np.round(pG, 4)],
                B=[float(t) for t in np.round(pB, 4)],
                BmG=[float(t) for t in np.round((pB - pG) * 255.0, 2)])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', default='test_temp/FILMKIT_20261007-142415.dng')
    ap.add_argument('--y0', type=int, default=1900)
    ap.add_argument('--y1', type=int, default=2450)
    ap.add_argument('--x0', type=int, default=2000)
    ap.add_argument('--x1', type=int, default=2700)
    ap.add_argument('--edge', type=int, default=2345, help='边缘 sensor x（绝对坐标）')
    ap.add_argument('--json', default='')
    args = ap.parse_args()

    val, fc, meta = rlc.load_input(args.dng)
    vr = val[args.y0:args.y1, args.x0:args.x1]
    fr = fc[args.y0:args.y1, args.x0:args.x1]
    print(f"样片 {meta['dng']}  ROI {vr.shape}  gain={np.round(meta['gain'], 4).tolist()}")

    # 基准 = 原版
    ref, ref_parts = rlc.rcd_reference(vr, fr, return_parts=True)
    variants = {
        'p1ours': dict(step1='ours'),
        'p2ours': dict(step3='ours'),
        'p42ours': dict(step42='ours'),
        'p43ours': dict(step43='ours'),
        'p43no3': dict(step43='ours_no3'),
        'allours': dict(step1='ours', step2='ours', step3='ours', step42='ours', step43='ours'),
    }
    out = {'meta': meta, 'stats': {}, 'profiles': {}, 'parts': {}}
    print('\n=== 中间量差（相对原版；/8bit 线性尺度 255）===')
    print(f"{'变体':<10}{'VH场p99':>10}{'lpf p99':>10}{'G p99':>9}{'4.2填充p99':>12}"
          f"{'最终R p99':>11}{'最终G p99':>11}{'最终B p99':>11}{'B-G p99':>10}")
    for k, kw in variants.items():
        rgb, parts = rlc.rcd_reference(vr, fr, return_parts=True, **kw)
        rows = []
        dvh = stats(parts['vh'] - ref_parts['vh'], 'vh')
        dg = stats(rgb[1] - ref[1], 'G')
        d42 = stats((parts['fillR'] + parts['fillB']) - (ref_parts['fillR'] + ref_parts['fillB']), '42')
        dR = stats(rgb[0] - ref[0], 'R')
        dG = stats(rgb[1] - ref[1], 'G2')
        dB = stats(rgb[2] - ref[2], 'B')
        dBG = stats((rgb[2] - rgb[1]) - (ref[2] - ref[1]), 'BG')
        out['stats'][k] = dict(vh=dvh, G=dg, fill42=d42, R=dR, Gout=dG, B=dB, BG=dBG)
        print(f"{k:<10}{dvh['abs_p99']:10.2f}{0.0:10.2f}{dg['abs_p99']:9.2f}{d42['abs_p99']:12.2f}"
              f"{dR['abs_p99']:11.2f}{dG['abs_p99']:11.2f}{dB['abs_p99']:11.2f}{dBG['abs_p99']:10.2f}")

    print('\n=== 边缘绝对剖面（sensor x 附近，B−G /8bit）===')
    print(f"{'变体':<10}  边缘 x={args.edge}")
    for name, rgb in [('ref', ref)] + [(k, rlc.rcd_reference(vr, fr, **variants[k]))
                                       for k in variants]:
        prof = edge_profile(rgb, args.edge - args.x0, 80, 480)
        out['profiles'][name] = prof
        print(f"{name:<10}{[round(t, 1) for t in prof['BmG']]}")

    # 差异热点：p43ours vs ref 的 B−G 差最大位置（全 ROI）
    rgb43 = rlc.rcd_reference(vr, fr, step43='ours')
    dbg = (rgb43[2] - rgb43[1]) - (ref[2] - ref[1])
    iy, ix = np.unravel_index(np.argmax(np.abs(dbg)), dbg.shape)
    print(f"\n4.3 项差异最大点: ROI 内 (y={iy}, x={ix}) → sensor (y={args.y0+iy}, x={args.x0+ix})"
          f"  Δ(B-G)={dbg[iy, ix]*255:+.1f}/8bit  |Δ| p99.9={np.percentile(np.abs(dbg),99.9)*255:.1f}")
    # 与局部对比度的相关：差异是否集中在高梯度处
    gx = np.abs(np.diff(ref[1], axis=1, prepend=ref[1][:, :1]))
    gy = np.abs(np.diff(ref[1], axis=0, prepend=ref[1][:1, :]))
    grad = gx + gy
    hi = grad > np.percentile(grad, 95)
    print(f"  |Δ(B-G)| 均值: 高梯度区 {np.abs(dbg)[hi].mean()*255:.2f} vs 其余 "
          f"{np.abs(dbg)[~hi].mean()*255:.2f} /8bit")
    out['hotspot'] = dict(roi_y=int(iy), roi_x=int(ix), sensor_y=int(args.y0 + iy),
                          sensor_x=int(args.x0 + ix), dbg=float(dbg[iy, ix] * 255),
                          p999=float(np.percentile(np.abs(dbg), 99.9) * 255),
                          hi_mean=float(np.abs(dbg)[hi].mean() * 255),
                          lo_mean=float(np.abs(dbg)[~hi].mean() * 255))
    if args.json:
        with open(args.json, 'w') as f:
            json.dump(out, f, ensure_ascii=False, indent=1)
        print(f'\n已写 {args.json}')


if __name__ == '__main__':
    main()
