#!/usr/bin/env python3
"""R-2 补充：提高白点 + 提高 contrast 能否"既保白色够白、又保高光有层次"？

动机：R-2 主脚本实测到 —— 单纯提高 `filmic_white_ev` 会**延后硬平顶**（好事）但
**压暗整条亮部**（坏事：m9 在 2EV 处 -31 LSB）。本脚本量化"白点 + contrast"这一对
旋钮的联合效果，给"建议值"提供依据。链路与主脚本完全一致（同 Cxx / 同 Rig / 同判据）。

用法：python3 tools/preset-exp/r2-white-point-sweep.py
"""
import importlib.util
import os
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


R2 = _load(os.path.join(HERE, "r2-white-point.py"), "r2wp")

PRESETS = {
    "builtin_leica_m9": [0.9741, 1.2, 1.4, 1.6],
    "builtin_ricoh_negative": [1.057, 1.3, 1.5, 1.7],
    "builtin_leica_bw": [1.0926, 1.3, 1.5, 1.7],
}
WHITES = [None, 3.5, 4.0]      # None = 预设现状
PAPER_EV = 2.29                # 反射率 0.9（白纸/白瓷漫反射）相对中灰的 EV


def main():
    ev = np.round(np.arange(-1.0, 7.0 + 1e-9, 0.05), 4)
    lin = R2.GREY * np.power(2.0, ev)
    ip = int(np.argmin(np.abs(ev - PAPER_EV)))
    cxx = R2.Cxx(tempfile.mkdtemp(prefix="r2sw_"))
    gl = R2.ESO.GL(256, R2.ROWS)
    rig = R2.Rig(gl)
    print(f"# R-2 补充：白点 × contrast 联合扫描（无 LUT 口径）｜白纸 = 反射率 0.9"
          f" = {ev[ip]:.2f}EV｜输入 s = 0.1845·2^EV")
    for rec in R2.RF.load_builtin():
        sl = R2.RF.slots_of(rec)
        black = R2.RF.pv(sl, 37)
        lat_sh = [R2.RF.pv(sl, s) for s in (5, 6, 7)]
        lat_toe = [R2.RF.pv(sl, 8), R2.RF.pv(sl, 9), R2.RF.pv(sl, 10)]
        whites = [R2.RF.pv(sl, 38)] + WHITES[1:]
        cases, tags = [], []
        for w in whites:
            for c in PRESETS[rec["id"]]:
                t = f"{rec['id']}|w={w}|c={c}"
                tags.append((w, c, t))
                cases.append((t, black, w, c, lat_sh, lat_toe))
        sps = cxx.run(cases)
        print(f"\n## {rec['id']}（black_ev={black}，lat_sh/toe={lat_sh}/{lat_toe}，"
              f"现状 w={R2.RF.pv(sl, 38)} c={R2.RF.pv(sl, 4)}）")
        print("   white_ev | contrast | 白纸(2.29EV) | 2.0EV | 3.0EV | 4.0EV | 8bit平顶起点"
              " | 8bit死白起点")
        for w, c, t in tags:
            _, o = R2.render(rig, sps[t], sl, lin, 0, None, 0)
            a = R2.analyse(ev, o, np.zeros(len(ev)))
            g = lambda x: o[int(np.argmin(np.abs(ev - x)))]
            mark = " ←现状" if (w == R2.RF.pv(sl, 38) and c == R2.RF.pv(sl, 4)) else ""
            print(f"   {w:8.4f} | {c:8.4f} | {o[ip]:12.1f} | {g(2.0):5.1f} | {g(3.0):5.1f} |"
                  f" {g(4.0):5.1f} | {str(a['sat8']):>12} | {str(a['flat01']):>12}{mark}")
    gl.close()


if __name__ == "__main__":
    main()
