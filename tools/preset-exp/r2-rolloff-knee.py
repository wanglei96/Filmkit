#!/usr/bin/env python3
"""R-2 补充 ②：`highlightRolloff` 的 knee 对"高光层次预算"的影响（离线，不改产品代码）。

动机：R-2 主脚本实测到 —— 白点 2.75 时"白纸(0.9, 2.29EV)→显示上限(246.5)"之间只剩
~7 LSB，这就是高光层次的全部预算。除了提高白点（代价 = 白色发灰），还有第二个旋钮：
`kFsOut` 的 `highlightRolloff` knee（`core/engine/src/render/FrameRenderer.cpp:4754`，
当前 0.80）。本脚本**只在离线执行器里**把该常量机械替换成其他值，量化它对
"白纸亮度 / 高光层次预算"的影响 —— 产品代码一行不改。

用法：python3 tools/preset-exp/r2-rolloff-knee.py
"""
import importlib.util
import os
import re
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
KNEES = [0.80, 0.70, 0.60, 0.50]
PAPER_EV = 2.29


def main():
    ev = np.round(np.arange(-1.0, 7.0 + 1e-9, 0.05), 4)
    lin = R2.GREY * np.power(2.0, ev)
    ip = int(np.argmin(np.abs(ev - PAPER_EV)))
    cxx = R2.Cxx(tempfile.mkdtemp(prefix="r2knee_"))
    gl = R2.ESO.GL(256, R2.ROWS)
    rig = R2.Rig(gl)
    orig = rig.sh["kFsOut"]
    assert "const float knee = 0.80;" in orig, "knee 常量未找到（源码结构变了？）"
    print("# R-2 补充 ②：highlightRolloff knee 扫描（离线替换常量，产品代码不改）"
          f"｜白纸 = {ev[ip]:.2f}EV｜无 LUT 口径")
    for rec in R2.RF.load_builtin():
        sl = R2.RF.slots_of(rec)
        w = R2.RF.pv(sl, 38)
        sp = cxx.run([(rec["id"], R2.RF.pv(sl, 37), w, R2.RF.pv(sl, 4),
                       [R2.RF.pv(sl, s) for s in (5, 6, 7)],
                       [R2.RF.pv(sl, 8), R2.RF.pv(sl, 9), R2.RF.pv(sl, 10)])])[rec["id"]]
        print(f"\n## {rec['id']}（现状 white_ev={w}）")
        print("   knee | 白纸(2.29EV) | 2.0EV | 3.0EV | 4.0EV | 8bit上限 | 高光预算(白纸→上限)"
              " | 8bit死白起点")
        for k in KNEES:
            rig.sh["kFsOut"] = orig.replace(f"const float knee = 0.80;",
                                            f"const float knee = {k:.2f};")
            _, o = R2.render(rig, sp, sl, lin, 0, None, 0)
            a = R2.analyse(ev, o, np.zeros(len(ev)))
            g = lambda x: o[int(np.argmin(np.abs(ev - x)))]
            print(f"   {k:4.2f} | {o[ip]:12.1f} | {g(2.0):5.1f} | {g(3.0):5.1f} | {g(4.0):5.1f} |"
                  f" {a['top']:8.2f} | {a['top'] - o[ip]:19.1f} | {str(a['flat01']):>12}")
        rig.sh["kFsOut"] = orig
    gl.close()


if __name__ == "__main__":
    main()
