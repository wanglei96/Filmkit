#!/usr/bin/env python3
"""R-2 —— 内置预设白点（`filmic_white_ev`）2.5~2.7 是否导致"高光死白"：**离线实测**。

问题（用户 2026-10-12 真机）：「白点默认才 2.7？那杯子高光变白的 bug 不就又出来了！」
即：白色物体高光失去层次、糊成一片纯白。

本脚本用**引擎自己的代码**回答，不做 numpy 直译：
  ① 系数 = 引擎 `FrameRenderer::computeFilmicSpline`（`solve5` + 该函数体括号配平提取后
     `clang++` 真编译真跑），输入 (black_ev, white_ev, contrast, lat_sh[3], lat_toe[3])。
  ② 渲染 = 真跑引擎 GLSL `kFsFilm`（filmicTone + gamut v5 + sat/split）→ `kFsOut`
     （highlightRolloff → OETF → [90..94] → LUT → 颗粒），只做
     `#version 300 es → 330 core` 机械改写（GL 装置 import 自 `tools/engine-shader-offline.py`，
     shader 抽取复用 `tools/check-u2-editor-curve-sat.py`，预设读取/LUT 复用
     `tools/preset-exp/recipe-fit.py`）。

输入口径 = **场景线性反射率**，EV 相对 filmic 的中灰锚点 18.45%：`s = 0.1845 · 2^EV`。
（与 `tools/tuning/filmic_offline.py` / `recipe-fit.py` 同一口径，见后者头部 §口径说明。）
"EV" 与反射率的对应：0.1845 = 中灰（0EV）、0.9 = 白纸漫反射（+2.29EV）、
1.0 = 理想白（+2.44EV）、2.0 = 受强光白面/镜面高光（+3.44EV）。

口径说明（报告 §方法逐条复述）：
  · `uLocalStrength = 0`（局部提亮只作用于暗区，高光区强度为 0）、`uSharpen = 0`（空间项）、
    暗角/颗粒/清晰度 = 0、`[90..94]` 色阶 = 0、`uGamut = 1`（真机默认）。
  · 两条 LUT 口径：**无 LUT** = 纯 filmic→rolloff→OETF（白点直接作用处）；
    **预设 LUT** = 内置预设自带的 LUT + strength（真机成片口径）。
  · 每列取 16 行的中位数 ⇒ 去掉 `kFsOut` 结尾的无条件 dither（±1/255、逐像素确定）。

用法：
    python3 tools/preset-exp/r2-white-point.py                 # 全部表格
    python3 tools/preset-exp/r2-white-point.py --json out.json # 落盘
"""
import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
FR = os.path.join(REPO, "core/engine/src/render/FrameRenderer.cpp")
GREY = 0.1845          # darktable grey_source（= filmic 的中灰锚点）
LSB = 1.0 / 255.0
LUMA = np.array([0.2126, 0.7152, 0.0722])
ROWS = 64              # 去 dither：同列 64 行取中位数（dhash 依赖 gl_FragCoord.y）


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


U2 = _load(os.path.join(REPO, "tools", "check-u2-editor-curve-sat.py"), "u2h")
ESO = _load(os.path.join(REPO, "tools", "engine-shader-offline.py"), "eso")
RF = _load(os.path.join(HERE, "recipe-fit.py"), "rf")


def srgb_decode(c):
    """sRGB 显示值 → 线性反射率（真 sRGB 分段曲线，与 recipe-fit 同口径）。"""
    c = min(max(c, 0.0), 1.0)
    return ((c + 0.055) / 1.055) ** 2.4 if c > 0.04045 else c / 12.92


# ─────────────────────────────────────────── C++ 侧：引擎同一份 computeFilmicSpline
CXX_SRC = r"""
#include <algorithm>
#include <cmath>
#include <cstdio>
struct FilmicSpline {
  float m1[3][3]; float m2[3][3]; float m3[3][3]; float m4[3][3]; float m5[3][3];
  float lat_min[3]; float lat_max[3]; float lat_sh[3]; float lat_toe[3];
  float black_ev, white_ev, dynamic_range, input_min, input_max;
  float output_power, black_disp, grey_log, contrast, contrast_param;
};
namespace impl {
__SOLVE5__
__SPLINE__
}  // namespace impl
int main() {
  double bev, wev, ctr, sh[3], toe[3];
  while (std::scanf("%lf %lf %lf %lf %lf %lf %lf %lf %lf", &bev, &wev, &ctr,
                    &sh[0], &sh[1], &sh[2], &toe[0], &toe[1], &toe[2]) == 9) {
    float fsh[3] = {(float)sh[0], (float)sh[1], (float)sh[2]};
    float ftoe[3] = {(float)toe[0], (float)toe[1], (float)toe[2]};
    FilmicSpline sp = impl::compute_spline((float)bev, (float)wev, (float)ctr, fsh, ftoe);
    for (int ch = 0; ch < 3; ++ch)
      for (int seg = 0; seg < 3; ++seg)
        std::printf("m %d %d %.9g %.9g %.9g %.9g %.9g\n", ch, seg,
                    sp.m1[ch][seg], sp.m2[ch][seg], sp.m3[ch][seg],
                    sp.m4[ch][seg], sp.m5[ch][seg]);
    for (int ch = 0; ch < 3; ++ch)
      std::printf("l %d %.9g %.9g\n", ch, sp.lat_min[ch], sp.lat_max[ch]);
    std::printf("s %.9g %.9g %.9g %.9g %.9g %.9g\n", sp.black_ev, sp.dynamic_range,
                sp.input_min, sp.input_max, sp.output_power, sp.black_disp);
    std::printf("-\n");
  }
  return 0;
}
"""


class Cxx:
    """提取引擎 solve5 + computeFilmicSpline → clang++ 真编译 → 批量求系数。"""

    def __init__(self, workdir):
        src = open(FR, encoding="utf-8").read()
        solve5 = U2.extract_body(src, r"bool solve5\(double a\[5\]\[5\][^{]*\)", "solve5")
        spline = U2.extract_body(
            src, r"FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline\([^{]*\)",
            "computeFilmicSpline")
        spline = spline.replace(
            "FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(",
            "FilmicSpline compute_spline(")
        body = (CXX_SRC.replace("__SOLVE5__", "namespace { " + solve5 + " }")
                .replace("__SPLINE__", spline))
        cpp = os.path.join(workdir, "r2_spline.cpp")
        self.exe = os.path.join(workdir, "r2_spline")
        open(cpp, "w").write(body)
        r = subprocess.run(["clang++", "-std=c++20", "-O2", "-o", self.exe, cpp],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("clang++ 编译失败：\n" + r.stderr[-3000:])

    def run(self, cases):
        """cases: [(tag, black, white, contrast, lat_sh[3], lat_toe[3])] → {tag: 系数}"""
        inp = "".join(
            f"{b!r} {w!r} {c!r} {sh[0]!r} {sh[1]!r} {sh[2]!r} {to[0]!r} {to[1]!r} {to[2]!r}\n"
            for _, b, w, c, sh, to in cases)
        out = subprocess.run([self.exe], input=inp, capture_output=True, text=True)
        if out.returncode != 0:
            raise RuntimeError("harness 运行失败：\n" + out.stderr[-2000:])
        res, cur, idx = {}, None, -1
        for ln in out.stdout.splitlines():
            p = ln.split()
            if not p:
                continue
            if p[0] == "-":
                cur = None
                continue
            if cur is None:
                idx += 1
                cur = res.setdefault(cases[idx][0], {"m": np.zeros((3, 3, 5)),
                                                     "lat": np.zeros((3, 2))})
            if p[0] == "m":
                cur["m"][int(p[1]), int(p[2])] = [float(v) for v in p[3:8]]
            elif p[0] == "l":
                cur["lat"][int(p[1])] = [float(p[2]), float(p[3])]
            elif p[0] == "s":
                cur.update(black_ev=float(p[1]), dr=float(p[2]), input_min=float(p[3]),
                           input_max=float(p[4]), power=float(p[5]), black_disp=float(p[6]))
        return res


# ─────────────────────────────────────────── GPU 侧：真跑 kFsFilm + kFsOut
class Rig:
    def __init__(self, gl):
        self.gl = gl
        src = open(FR, encoding="utf-8").read()
        self.sh = dict((m.group(1), m.group(2)) for m in
                       re.finditer(r'const char\*\s+(kFs\w+)\s*=\s*R"\((.*?)\)";', src, re.S))
        assert "kFsFilm" in self.sh and "kFsOut" in self.sh, "shader 提取失败"
        self.tex = None
        self.uni = {}
        self.local = gl.ctx.texture((1, 1), 4, dtype="f4")
        self.local.write(np.ones((1, 1, 4), "f4").tobytes())
        self.grain = gl.ctx.texture((1, 1), 1, np.ones((1,), "f2").tobytes(), dtype="f2")
        self.lut = gl.ctx.texture3d((2, 2, 2), 4, np.ones((8, 4), "f2").tobytes(), dtype="f2")

    def draw(self, name, img):
        """img: (H, W, 3) float32 → 真跑该 shader，返回 (H, W, 4)。"""
        h, w = img.shape[:2]
        if self.tex is None or tuple(self.tex.size) != (w, h):
            if self.tex is not None:
                self.tex.release()
            self.tex = self.gl.ctx.texture((w, h), 3, dtype="f4")
            self.tex.filter = (self.gl.ctx.NEAREST, self.gl.ctx.NEAREST)
        self.tex.write(np.ascontiguousarray(img, "f4").tobytes())
        return self.gl.run(self.sh[name], self.uni, {
            "uTex": self.tex, "uLocal": self.local, "uLut": self.lut,
            "uGrainLut": self.grain, "uGlow": self.local}, size=(w, h))


def film_uniforms(sp, sl):
    """computeFilmicSpline 结果 + 预设槽位 → kFsFilm 的 uniform（名称与 shader 逐一对应）。"""
    u = {
        "uSat": (RF.pv(sl, 20), RF.pv(sl, 21), RF.pv(sl, 22)),
        "uSplit": (RF.pv(sl, 23), RF.pv(sl, 24)),
        "uMono": RF.pv(sl, 25),
        "uToneK": 1.0, "uLook": 0.0, "uLookSat": 1.0, "uLookChroma": 1.0,
        "uHalDomainMap": 0.0, "uDomainRatio": 1.0,
        "uLocalStrength": 0.0, "uLocalKnee": 0.5, "uLocalKneeHi": 1.5,
        "uLocalRef": 0.5, "uLocalSize": (1.0, 1.0),
        "uGamut": 1.0,                      # 真机默认（gamut 收尾开）
        "uExposure": 2.0 ** RF.pv(sl, 0),   # [0] exposure_ev（预设未给 ⇒ 0）
        "uFilmicBlackEv": sp["black_ev"], "uFilmicDr": sp["dr"],
        "uFilmicInputMin": sp["input_min"], "uFilmicInputMax": sp["input_max"],
        "uFilmicPower": sp["power"], "uFilmicBlackDisp": sp["black_disp"],
        "uFilmicLatMin": tuple(float(v) for v in sp["lat"][:, 0]),
        "uFilmicLatMax": tuple(float(v) for v in sp["lat"][:, 1]),
    }
    for i in range(5):
        u[f"uFilmicToeM{i + 1}"] = tuple(float(sp["m"][c, 0, i]) for c in range(3))
        u[f"uFilmicShM{i + 1}"] = tuple(float(sp["m"][c, 1, i]) for c in range(3))
    u["uFilmicLinM1"] = tuple(float(sp["m"][c, 2, 0]) for c in range(3))
    u["uFilmicLinM2"] = tuple(float(sp["m"][c, 2, 1]) for c in range(3))
    return u


def render(rig, sp, sl, lin, lut_size, lut, lut_on):
    """线性反射率数组 → (kFsFilm 线性显示域输出, kFsOut 8bit luma)。"""
    w, h = len(lin), ROWS
    img = np.repeat(np.asarray(lin, np.float32)[None, :, None], h, 0).repeat(3, 2)
    rig.uni = film_uniforms(sp, sl)
    rig.lut = lut if lut is not None else rig.lut
    film = np.median(rig.draw("kFsFilm", img)[:, :, :3], axis=0)
    rig.uni = RF.out_uniforms(w, h, lut_on, RF.pv(sl, 34), lut_size)
    rig.uni["uSharpen"] = 0.0
    out = np.median(rig.draw("kFsOut", np.ascontiguousarray(film[None].repeat(h, 0), "f4")),
                    axis=0)[:, :3]
    return film, out @ LUMA * 255.0


# ─────────────────────────────────────────── 判据
def analyse(ev, out8, film_lin):
    """plateau = filmic 输出严格恒定的起点；sat8 = 8bit 整段极差 ≤1LSB 的起点；
    flat01 = 此后每 0.1EV 输出增量 ≤1LSB 的起点。"""
    step = float(ev[1] - ev[0])
    k = max(1, int(round(0.1 / step)))
    d = out8[k:] - out8[:-k]
    plateau = next((float(ev[i]) for i in range(len(ev))
                    if np.all(np.abs(film_lin[i:] - film_lin[i]) < 1e-6)), None)
    q = np.round(out8)                       # 8bit 量化（真机 JPEG 口径）
    dup = next((float(ev[i]) for i in range(1, len(q)) if q[i] == q[i - 1]), None)
    best, i, flat_len = (0, None), 0, 0      # 最长"完全相同输出"的连续段
    while i < len(q):
        j = i
        while j + 1 < len(q) and q[j + 1] == q[i]:
            j += 1
        if j - i + 1 > best[0]:
            best, flat_len = (j - i + 1, float(ev[i])), j - i + 1
        i = j + 1
    flat01 = next((float(ev[i]) for i in range(len(d)) if np.all(d[i:] <= 1.0)), None)
    sat8 = next((float(ev[i]) for i in range(len(ev))
                 if out8[i:].max() - out8[i:].min() <= 1.0), None)
    return dict(top=round(float(out8.max()), 2), plateau=plateau, flat01=flat01, sat8=sat8,
                first_dup=dup, flat_len=best[0], flat_at=best[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ev-min", type=float, default=-1.0)
    ap.add_argument("--ev-max", type=float, default=7.0)
    ap.add_argument("--ev-step", type=float, default=0.05)
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    ev = np.round(np.arange(args.ev_min, args.ev_max + 1e-9, args.ev_step), 4)
    lin = GREY * np.power(2.0, ev)
    recs = RF.load_builtin()
    cxx = Cxx(tempfile.mkdtemp(prefix="r2wp_"))
    gl = ESO.GL(256, ROWS)
    rig = Rig(gl)

    print(f"# R-2 白点-高光实测｜EV∈[{args.ev_min}, {args.ev_max}] step {args.ev_step}"
          f"（{len(ev)} 列）｜输入 s = 0.1845·2^EV｜kFsFilm+kFsOut 真跑｜{ROWS} 行中位数去 dither")
    report = {}
    for rec in recs:
        sl = RF.slots_of(rec)
        lut_size, lut = 0, None
        if rec.get("lut"):
            lut_size, lutd = RF.load_lut(rec["lut"]["id"])
            lut = RF.lut_tex(gl, lut_size, lutd)
        lut_on = 1 if (lut is not None and RF.pv(sl, 34) > 0) else 0
        cur = RF.pv(sl, 38)
        cands = sorted(set(round(c, 4) for c in [cur, 3.0, 3.5, 4.0, 4.56]))
        sps = cxx.run([(f"{rec['id']}|w={w}", RF.pv(sl, 37), w, RF.pv(sl, 4),
                        [RF.pv(sl, 5), RF.pv(sl, 6), RF.pv(sl, 7)],
                        [RF.pv(sl, 8), RF.pv(sl, 9), RF.pv(sl, 10)]) for w in cands])
        print(f"\n## {rec['id']}（{rec['name']}） black_ev={RF.pv(sl, 37)} "
              f"contrast={RF.pv(sl, 4)} lat_sh/toe={[RF.pv(sl, s) for s in (5, 6, 7)]}"
              f"/{[RF.pv(sl, s) for s in (8, 9, 10)]} lut={rec.get('lut')}")
        print("   表 1｜平顶判据（无 LUT 口径 = 纯 filmic→highlightRolloff→OETF）")
        print("   white_ev |   DR  | film平顶起点EV | 8bit上限 | 8bit死白起点 | ≤1LSB/0.1EV"
              " | 首个重复输出EV | 最长同值段")
        rows = {}
        for w in cands:
            tag = f"{rec['id']}|w={w}"
            f0, o0 = render(rig, sps[tag], sl, lin, lut_size, lut, 0)
            f1, o1 = render(rig, sps[tag], sl, lin, lut_size, lut, lut_on)
            a = analyse(ev, o0, f0[:, 0])
            rows[w] = dict(ev=ev.tolist(), no_lut=o0.tolist(), lut=o1.tolist(),
                           film=f0[:, 0].tolist(), a=a)
            print(f"   {w:8.4f} | {sps[tag]['dr']:5.2f} | {str(a['plateau']):>13} |"
                  f" {a['top']:8.2f} | {str(a['sat8']):>12} | {str(a['flat01']):>14} |"
                  f" {str(a['first_dup']):>14} | {a['flat_len']:>4}列@{a['flat_at']}")
        keys = [0.0, 1.0, 1.5, 2.0, 2.2, 2.4, 2.5, 2.6, 2.8, 3.0, 3.2, 3.5, 4.0, 4.56, 5.0, 6.0]
        for mode, lab in (("no_lut", "无 LUT"), ("lut", f"预设 LUT({rec.get('lut')})")):
            print(f"   表 2｜关键 EV 的成片 luma（{lab}）")
            print("      EV | 反射率 |" + "".join(f"{('w=' + format(w, '.4g')):>9}" for w in cands))
            for k in keys:
                i = int(np.argmin(np.abs(ev - k)))
                print(f"   {ev[i]:5.2f} | {lin[i]:6.3f} |"
                      + "".join(f"{rows[w][mode][i]:9.1f}" for w in cands))
        # 表 3：R-1 目标曲线残差（拍照链路口径：输入 = 目标点 sRGB v 解码的线性反射率）
        tgt = RF.TARGETS[rec["id"]]
        pts = np.array([p[0] for p in tgt["pts"]], float)
        want = np.array([p[1] for p in tgt["pts"]], float)
        lin_t = np.array([srgb_decode(v / 255.0) for v in pts])
        print(f"   表 3｜R-1 目标曲线残差（{tgt['title']}；目标点 = "
              f"{[(int(a), b) for a, b in zip(pts, want)]}）")
        print("   white_ev |" + "".join(f"{int(v):>7}" for v in pts) + " | max|Δ|")
        for w in cands:
            tag = f"{rec['id']}|w={w}"
            _, o = render(rig, sps[tag], sl, lin_t, lut_size, lut, lut_on)
            d = o - want
            print(f"   {w:8.4f} |" + "".join(f"{dd:+7.1f}" for dd in d)
                  + f" | {np.abs(d).max():6.2f}")
            rows[w]["target_resid"] = d.tolist()
        report[rec["id"]] = {str(w): rows[w] for w in cands}
        report[rec["id"]]["_meta"] = dict(
            name=rec["name"], black_ev=RF.pv(sl, 37), white_ev_cur=cur, contrast=RF.pv(sl, 4),
            lut=rec.get("lut"), lut_on=lut_on, target_pts=tgt["pts"], target_title=tgt["title"])
    gl.close()
    if args.json:
        json.dump(report, open(args.json, "w"), ensure_ascii=False)
        print(f"\n[json] {args.json}")


if __name__ == "__main__":
    main()
