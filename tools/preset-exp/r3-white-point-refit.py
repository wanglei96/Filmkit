#!/usr/bin/env python3
"""R-3 —— 白点提高后的量化验收：{旧配方, 新配方, 各候选白点档} 的
**死白起点 / 白纸值 / 高光层次预算 / R-1 目标残差**。

装置全部复用，一行数学都不重写：
  · 死白起点 / 白纸 / 预算 = R-2 的装置（`r2-white-point.py`：真编译 `computeFilmicSpline`
    + 真跑 `kFsFilm` → `kFsOut`，判据函数 `analyse()` 逐字沿用）；
  · R-1 目标残差（编辑器 `kFsStyle` + 拍照 `kFsFilm`→`kFsOut` 两条链路、目标点、`luma_curve`
    口径）= R-1 的装置（`recipe-fit.py` 的 `Evaluator`）。

用法：
    python3 tools/preset-exp/r3-white-point-refit.py \
        --before tools/preset-exp/fit/builtin-before-r3.json \
        --fit tools/preset-exp/fit/r3-wev3.5.json \
              tools/preset-exp/fit/r3-wev4.0.json \
              tools/preset-exp/fit/r3-wev4.56.json \
        --json tools/preset-exp/fit/r3-verify.json
"""
import argparse
import importlib.util
import json
import os
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
BUILTIN = os.path.join(REPO, "app/src/main/assets/presets/builtin.json")


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


R2 = _load(os.path.join(HERE, "r2-white-point.py"), "r2wp")
RF = R2.RF                      # r2 已经 load 了 recipe-fit，直接复用同一份
PAPER_EV = 2.30                 # 白纸漫反射 0.9 ⇒ +2.29EV（R-2 口径）


def load_json(p):
    return json.load(open(p, encoding="utf-8"))


def virtual_rec(base, slot_params):
    """把拟合结果里的**槽位字典**（`{"38": 4.0, …}`）套回一份预设记录（不改原文件）。"""
    rec = json.loads(json.dumps(base))
    rec["params"] = RF._merge_params(base.get("params") or {}, slot_params)
    rec["lut"] = dict(rec.get("lut") or {})
    rec["lut"]["strength"] = float(slot_params["34"])
    return rec


# ─────────────────────────────── R-2 装置：死白起点 / 白纸 / 预算
def measure(r2cxx, r2rig, rec, ev, lin):
    sl = RF.slots_of(rec)
    lut_size, lut = 0, None
    if rec.get("lut"):
        lut_size, lutd = RF.load_lut(rec["lut"]["id"])
        lut = RF.lut_tex(r2rig.gl, lut_size, lutd)
    lut_on = 1 if (lut is not None and RF.pv(sl, 34) > 0) else 0
    sp = r2cxx.run([(rec["id"],) + RF.case_of(sl)])[rec["id"]]
    f0, o0 = R2.render(r2rig, sp, sl, lin, lut_size, lut, 0)        # 无 LUT 口径
    f1, o1 = R2.render(r2rig, sp, sl, lin, lut_size, lut, lut_on)   # 真机口径（含预设 LUT）
    a = R2.analyse(ev, o0, f0[:, 0])
    i = int(np.argmin(np.abs(ev - PAPER_EV)))
    return dict(white_ev=RF.pv(sl, 38), black_ev=RF.pv(sl, 37), dr=round(float(sp["dr"]), 3),
                plateau=a["plateau"], sat8=a["sat8"], flat01=a["flat01"], top=a["top"],
                top_lut=round(float(np.max(o1)), 2),
                paper_nolut=round(float(o0[i]), 2), paper_lut=round(float(o1[i]), 2),
                budget_nolut=round(float(a["top"] - o0[i]), 2),
                budget_lut=round(float(np.max(o1) - o1[i]), 2),
                ev=[float(v) for v in ev], o_nolut=[float(v) for v in o0],
                o_lut=[float(v) for v in o1])


# ─────────────────────────────── R-1 装置：目标曲线残差（两条链路）
def resid(rfcxx, rfrig, rec):
    tgt = RF.TARGETS[rec["id"]]
    pts = tgt["pts"]
    want = np.array([w for _, w in pts])
    e = RF.Evaluator(rfcxx, rfrig, rec, xs=[x for x, _ in pts])
    r = e.one(both=True)
    ed = RF.luma_curve(r["editor"], pts, e.xs)
    cam = RF.luma_curve(r["camera"], pts, e.xs)
    return dict(points=[int(x) for x, _ in pts], want=want.tolist(),
                editor=[round(float(v), 2) for v in ed],
                camera=[round(float(v), 2) for v in cam],
                max_editor=round(float(np.abs(ed - want).max()), 2),
                max_camera=round(float(np.abs(cam - want).max()), 2),
                sat_gain=round(float(r["sat_gain"]), 4),
                b_shift=round(float(r["b_shift"]), 3))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", default=os.path.join(HERE, "fit/builtin-before-r3.json"))
    ap.add_argument("--after", default=BUILTIN)
    ap.add_argument("--fit", nargs="*", default=[], help="三档拟合 json（取舍曲线）")
    ap.add_argument("--ev-max", type=float, default=7.0)
    ap.add_argument("--ev-step", type=float, default=0.05)
    ap.add_argument("--json", default=None)
    a = ap.parse_args()

    ev = np.round(np.arange(-1.0, a.ev_max + 1e-9, a.ev_step), 4)
    lin = R2.GREY * np.power(2.0, ev)
    before, after = load_json(a.before), load_json(a.after)
    by_id = {r["id"]: r for r in before}

    # 候选表：(label, 档位, 记录)
    cases = []
    for b, n in zip(before, after):
        cases.append((b["id"], "旧配方", "old", b))
        cases.append((b["id"], "新配方", "new", n))
    for fj in a.fit:
        d = load_json(fj)
        wev = d.get("_meta", {}).get("white_ev")
        for b in before:
            rep = d.get(b["id"])
            if not rep or "after" not in rep:
                continue
            wev = rep["after"]["params"].get("38", wev)
            cases.append((b["id"], f"w={wev}", f"w{wev}", virtual_rec(b, rep["after"]["params"])))

    td = tempfile.mkdtemp(prefix="r3wp_")
    os.makedirs(os.path.join(td, "r1"), exist_ok=True)
    os.makedirs(os.path.join(td, "r2"), exist_ok=True)
    r2cxx, r2rig = R2.Cxx(os.path.join(td, "r2")), R2.Rig(R2.ESO.GL(256, R2.ROWS))
    rfcxx, rfrig = RF.Cxx(os.path.join(td, "r1")), RF.Rig(R2.ESO)
    out = {}
    print(f"# R-3 验收｜EV∈[-1, {a.ev_max}] step {a.ev_step}｜白纸 = {PAPER_EV}EV"
          f"｜死白起点 = 8bit 整段极差 ≤1LSB 的起点（R-2 判据）")
    print(f"# 上限 = 最高输出（8bit，无 LUT / 含 LUT 两列）；预算 = 上限 − 白纸（同口径配对）")
    print("\n| 预设 | 档位 | 白点[38] | DR | 死白起点 | 白纸(无LUT) | 白纸(含LUT) | 上限(无LUT) |"
          " 上限(含LUT) | 预算(无LUT) | 预算(含LUT) | R-1 编辑 | R-1 拍照 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for pid, label, tag, rec in cases:
        m = measure(r2cxx, r2rig, rec, ev, lin)
        r1 = resid(rfcxx, rfrig, rec)
        out.setdefault(pid, {})[tag] = dict(label=label, r2=m, r1=r1)
        print(f"| {pid.replace('builtin_', '')} | {label} | {m['white_ev']:.4f} | {m['dr']:.2f} |"
              f" {m['sat8']} | {m['paper_nolut']:.1f} | {m['paper_lut']:.1f} | {m['top']:.2f} |"
              f" {m['top_lut']:.2f} | {m['budget_nolut']:.1f} | {m['budget_lut']:.1f} |"
              f" {r1['max_editor']:.2f} | {r1['max_camera']:.2f} |")

    # 三档取舍曲线（残差 + 死白起点 + 白纸，同表）
    print("\n## 取舍曲线（白点 3.5 / 4.0 / 4.56 三档，各自独立重拟合后的最优解）")
    print("\n| 预设 | 白点档 | 死白起点 | 白纸(含LUT) | 预算(含LUT) | R-1 编辑 | R-1 拍照 |")
    print("|---|---|---|---|---|---|---|")
    for pid in out:
        for tag in sorted(out[pid], key=lambda t: out[pid][t]["r2"]["white_ev"]):
            if not tag.startswith("w"):
                continue
            m, r1 = out[pid][tag]["r2"], out[pid][tag]["r1"]
            print(f"| {pid.replace('builtin_', '')} | {m['white_ev']:.2f} | {m['sat8']} |"
                  f" {m['paper_lut']:.1f} | {m['budget_lut']:.1f} | {r1['max_editor']:.2f} |"
                  f" {r1['max_camera']:.2f} |")

    # 目标点逐点残差（分目标列出）
    for pid in out:
        for tag, v in out[pid].items():
            r1 = v["r1"]
            print(f"\n[逐点] {pid} · {v['label']}：目标 "
                  + " ".join(f"{x}:{w:.1f}" for x, w in zip(r1["points"], r1["want"])))
            print(f"        编辑 " + " ".join(f"{x:.1f}" for x in r1["editor"])
                  + f"  max|Δ|={r1['max_editor']:.2f}")
            print(f"        拍照 " + " ".join(f"{x:.1f}" for x in r1["camera"])
                  + f"  max|Δ|={r1['max_camera']:.2f}")

    r2rig.gl.close()
    rfrig.close()
    if a.json:
        json.dump(out, open(a.json, "w"), ensure_ascii=False)
        print(f"\n[json] {a.json}")


if __name__ == "__main__":
    sys.exit(main())
