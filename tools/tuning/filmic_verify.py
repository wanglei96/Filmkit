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
    # E4-4：latitude 是 **per-channel** 且 toe/shoulder 分开（[5..7] / [8..10]）。
    # 旧日志（E4-4 之前）没有 latToe/latSh ⇒ 回退 0.01（当时引擎恒取该默认值）。
    lat_toe = [float(v) for v in kv.get("latToe", [0.01, 0.01, 0.01])]
    lat_sh = [float(v) for v in kv.get("latSh", [0.01, 0.01, 0.01])]
    bad = 0
    print(f"自检对象: {ts}  blackEv {b:.4f}  whiteEv {w:.4f}  linMid {kv.get('linMid')}")
    print(f"  lat_toe = {lat_toe}  lat_sh = {lat_sh}（生效值，clamp 后）")
    print(f"{'项':18s} {'引擎':>14s} {'参考':>14s} {'误差':>10s}")
    # compute_spline 返回按 polyval 序 [x⁴..x⁰]；引擎日志是 Horner 序（m1 = x⁰ …）。
    # 日志的 fM1..fM5 是 **9 个数**（E4-4 起）：通道优先展平
    # [R.toe,R.sh,R.lin, G.toe,G.sh,G.lin, B.toe,B.sh,B.lin]。
    ref_ch = {}
    for c, ch in enumerate(("R", "G", "B")):
        sp_t = compute_spline(b, w, 1.0, lat_toe[c])   # toe 段用 lat_toe
        sp_s = compute_spline(b, w, 1.0, lat_sh[c])    # shoulder 段用 lat_sh
        ref_ch[ch] = {"toe": sp_t["m_toe"], "shldr": sp_s["m_sh"],
                      "linear": sp_t["m_mid"], "sp": (sp_t, sp_s)}
    for i in range(5):
        eng = [float(v) for v in kv["fM%d" % (i + 1)]]
        assert len(eng) == 9, ("fM%d" % (i + 1), eng)
        for c, ch in enumerate(("R", "G", "B")):
            for s, tag in enumerate(("toe", "shldr", "linear")):
                ref = ref_ch[ch][tag][4 - i]
                err = abs(eng[c * 3 + s] - ref)
                if err >= TOL:
                    bad += 1
                print(f"fM{i + 1}[{ch}.{tag:6s}] {eng[c * 3 + s]:14.8f} {ref:14.8f} "
                      f"{err:10.2e}{'' if err < TOL else '  ✗'}")
    lat6 = [float(v) for v in kv["fLat"]]   # [toeR,toeG,toeB, shR,shG,shB]
    assert len(lat6) == 6, lat6
    for c, ch in enumerate(("R", "G", "B")):
        sp_t, sp_s = ref_ch[ch]["sp"]
        for name, eng, ref in ((f"lat_min[{ch}]", lat6[c], sp_t["toe_log"]),
                               (f"lat_max[{ch}]", lat6[3 + c], sp_s["sh_log"])):
            err = abs(eng - ref)
            if err >= TOL:
                bad += 1
            print(f"{name:18s} {eng:14.8f} {ref:14.8f} {err:10.2e}"
                  f"{'' if err < TOL else '  ✗'}")
    sp_t, sp_s = ref_ch["G"]["sp"]   # 标量用 G（norm 分支的代表通道）复算
    for name, eng, ref in (("power", float(kv["fPow"]), sp_t["power"]),
                           ("contrast", float(kv["fCon"]), sp_t["contrast"])):
        err = abs(eng - ref)
        if err >= TOL:
            bad += 1
        print(f"{name:18s} {eng:14.8f} {ref:14.8f} {err:10.2e}"
              f"{'' if err < TOL else '  ✗'}")
    # 中间段单调性 + 端点：曲线必须过 (0, black_disp)、(grey_log, grey_disp)、(1, 1)
    print("\n端点核对（参考实现，G 通道）:")
    for e, tag in ((0.0, "x=0"), (sp_t["grey_log"], "grey"), (1.0, "x=1")):
        y = np.polyval(sp_t["m_toe"], e) if e < sp_t["toe_log"] else (
            np.polyval(sp_s["m_sh"], e) if e > sp_s["sh_log"]
            else sp_t["m_mid"][3] * e + sp_t["m_mid"][4])
        print(f"  {tag:6s} spline({e:.4f}) = {y:.6f}")
    print(f"\n{'✓ 一致（全部误差 < %.0e）' % TOL if bad == 0 else '✗ %d 项不一致' % bad}")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
