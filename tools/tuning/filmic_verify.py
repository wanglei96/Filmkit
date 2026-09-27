#!/usr/bin/env python3
"""filmic 上引擎后的自检：用真机日志里的 EV 复算 spline 系数，与引擎打进日志的
系数逐项对比。**系数不一致就说明 C++ 实现与参考实现（darktable v3 公式）有偏差。**

用法: python3 tools/tuning/filmic_verify.py <日志.jsonl.txt|.jsonl>
判据: 每一项最大绝对误差 < 1e-4（float 日志精度 6 位小数，留一个量级余量）。
"""
import json
import os
import re
import sys

import numpy as np

sys.path.insert(0, "tools/tuning")
from filmic_offline import compute_spline  # noqa: E402

TOL = 1e-4


def parse_row(path):
    """取最后一条带 filmic 系数的 raw.render"""
    # CLI 入口路径消毒：realpath 归一化（消除 ../ 与符号链接）
    path = os.path.realpath(path)
    rows = []
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            o = json.loads(line)
        except Exception:
            continue
        kv = o.get("kv") or {}
        if "fM1" in kv:
            rows.append((o.get("ts", ""), kv))
    return rows


def main(path):
    rows = parse_row(path)
    if not rows:
        print("✗ 日志里没有 filmic 系数（fM1）—— 确认包是 v0.7.3+ 且拍过一张静拍")
        return 1
    ts, kv = rows[-1]
    b, w = float(kv["blackEv"]), float(kv["whiteEv"])
    sp = compute_spline(b, w)          # 参考实现（与引擎同一组公式）
    bad = 0
    print(f"自检对象: {ts}  blackEv {b:.4f}  whiteEv {w:.4f}  linMid {kv.get('linMid')}")
    print(f"{'项':10s} {'引擎':>14s} {'参考':>14s} {'误差':>10s}")
    # compute_spline 返回按 polyval 序 [x⁴..x⁰]；引擎日志是 Horner 序（m1 = x⁰ …）
    ref_m = {"m%d" % (i + 1): [sp["m_toe"][4 - i], sp["m_sh"][4 - i], sp["m_mid"][4 - i]]
             for i in range(5)}
    # 日志的 kv 已是 JSON 对象：fM1..fM5 / fLat 是数组，fPow / fCon 是标量
    for name, ref in ref_m.items():
        eng = [float(v) for v in kv["f" + name.upper()]]
        assert len(eng) == 3, (name, eng)
        for i, tag in enumerate(("toe", "shldr", "linear")):
            err = abs(eng[i] - ref[i])
            if err >= TOL:
                bad += 1
            print(f"{name}[{tag:6s}] {eng[i]:14.8f} {ref[i]:14.8f} {err:10.2e}"
                  f"{'' if err < TOL else '  ✗'}")
    lat = [float(v) for v in kv["fLat"]]
    for name, eng, ref in (("lat_min", lat[0], sp["toe_log"]),
                           ("lat_max", lat[1], sp["sh_log"]),
                           ("power", float(kv["fPow"]), sp["power"]),
                           ("contrast", float(kv["fCon"]), sp["contrast"])):
        err = abs(eng - ref)
        if err >= TOL:
            bad += 1
        print(f"{name:10s} {eng:14.8f} {ref:14.8f} {err:10.2e}"
              f"{'' if err < TOL else '  ✗'}")
    # 中间段单调性 + 端点：曲线必须过 (0, black_disp)、(grey_log, grey_disp)、(1, 1)
    print("\n端点核对（参考实现）:")
    for e, tag in ((0.0, "x=0"), (sp["grey_log"], "grey"), (1.0, "x=1")):
        y = np.polyval(sp["m_toe"], e) if e < sp["toe_log"] else (
            np.polyval(sp["m_sh"], e) if e > sp["sh_log"] else sp["m_mid"][3] * e + sp["m_mid"][4])
        print(f"  {tag:6s} spline({e:.4f}) = {y:.6f}")
    print(f"\n{'✓ 一致（全部误差 < %.0e）' % TOL if bad == 0 else '✗ %d 项不一致' % bad}")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
