#!/usr/bin/env python3
"""E4-4 趾部/肩部（filmic `latitude` per-channel）离线验收 —— **真跑引擎自己的 shader**。

判据（任务卡 E4-4）：
  ① 默认零回归：`[5..10]` 全默认 0.01 时，工作区的 `computeFilmicSpline`（per-channel）
     求出的 **60 个系数**必须**逐位**等于脚本内固化的期望基线 `EXPECT_HEX`。
  ② 形状可控：`lat_toe` = 0.01 / 0.1 / 0.3 的影调曲线采样（11 个输入亮度，≥6）+
     过渡区随参数**单调**移动 + **端点不动**（f(0) / f(1) 逐位相同）。
  ③ per-channel 生效：只改 `toe_r`（[8]）⇒ **R 通道变、G/B 逐位不变**；
     暗部出现**可测色偏**而**黑位不抬**。
  ④ 正交性：固定 [37][38]（black/white EV）与 [4]（contrast），改 [5..10]
     ⇒ 端点值**真的不动**（给数字）。

★ **2026-10-11（U-4）判据 ① 的改造 —— 从"对比 git HEAD"改成"自包含基线"**：
  原判据 ① 拿 `git show HEAD:<FR>` 当"改前"，E4-4 提交后 HEAD 立刻变成"改后"⇒ 脚本
  自己失效（`uFilmicM1` 在"旧版"里再也提取不到，AssertionError: shader 提取对不上）。
  这是"把 git 历史当对照物"的固有缺陷：**对照物会随提交漂移**。
  现在 ① = 工作区系数 vs 脚本内 `EXPECT_HEX`（= E4-4 提交 `a824170` 的实测值，60 项）：
  与 git 状态无关、随时可跑，且仍然**逐位**（`%a` 十六进制浮点字符串相等）。
  基线的来源与一次性复现（`a824170^` 单套求解 vs `a824170` per-channel，60 项 0 处不同）
  写在 `EXPECT_HEX` 上方；完整零回归证据（GPU 逐位 + CPU 逐位 + 真机）见
  `docs/reports/e4-4-toeshoulder-REPORT.md`。
  判据 ②③④ **本来就是自包含的**（只用工作区 shader + 参考实现）⇒ 原样保留。

为什么必须真跑 shader（不是 numpy 直译）：ADR §1.4 / §6.5 —— 判据必须来自引擎的
shader 本身（历史事故：在显示域量的阈值 5.0 拿去比线性域，恒 false、三版全无效）。

方法：
  · shader 来源 = 工作区的 `core/engine/src/render/FrameRenderer.cpp`；只做
    `#version 300 es → 330 core` 的机械改写（与 `tools/engine-shader-offline.py`
    完全同一套 GL 封装，直接 import 复用）。
  · 系数来源 = `tools/tuning/filmic_offline.py` 的 `compute_spline`（项目已验证的
    参考实现，曾用于与真机日志 fM*/fLat 对拍）。
  · 曲线测量条件：uToneK=1 / uGamut=0 / uSat=(1,1,1) / uSplit=0 / uLook=0 /
    uLocalStrength=0 / uExposure=1 / uHalDomainMap=0 ⇒ 输出 = filmicTone(输入)。
    ⚠️ 这是**离线隔离口径**（真机 uGamut 默认 1）；各判据内部口径相同。
  · 判据 ① 走 **CPU 侧**（系数逐位）：把工作区的 `solve5` + `computeFilmicSpline` 从
    源码里括号配平提取出来，机械改名后编译真跑（clang++），打印全部系数的十六进制
    浮点与固化基线比较。**它不再依赖任何 git 历史对象**。

用法：python3 tools/check-toeshoulder.py            # 全部判据
      python3 tools/check-toeshoulder.py --dump     # 额外打印系数表
"""
import argparse
import importlib.util
import os
import re
import subprocess
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FR_REL = "core/engine/src/render/FrameRenderer.cpp"
FR = os.path.join(REPO, FR_REL)
sys.path.insert(0, os.path.join(REPO, "tools", "tuning"))
from filmic_offline import compute_spline  # noqa: E402

LSB = 1.0 / 255.0  # 8bit 显示域 1 LSB（判据 ② 的"可见差异"阈值）
FAIL = []


def load_offline_gl():
    """复用 tools/engine-shader-offline.py 的 GL 封装（全屏三角形 + float16 读回）。"""
    spec = importlib.util.spec_from_file_location(
        "engine_shader_offline", os.path.join(REPO, "tools", "engine-shader-offline.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.GL, mod.to_core


def extract_film(src_text):
    m = re.search(r'const char\*\s+kFsFilm\s*=\s*R"\((.*?)\)";', src_text, re.S)
    assert m, "kFsFilm 未提取到"
    return m.group(1)


# ────────────────────────────────────────── 判据 ① 的固化基线（60 个 hex）
# 来源 = **E4-4 提交 a824170** 的 `computeFilmicSpline(black=-7.65, white=4.56, cp=1.0,
#        lat_sh=lat_toe=[0.01,0.01,0.01])` 的全部系数（`printf("%a")`）。
# 生成/复现（一次性，2026-10-11 由 U-4 跑过）：
#   编 `git show a824170^:…/FrameRenderer.cpp`（改前：单套求解）与
#   `git show a824170:…`（改后：per-channel）两版 `solve5` + `computeFilmicSpline`，
#   逐项比较 ⇒ **60 个 hex 浮点 0 处不同**（原判据 ① 的结论，与
#   docs/reports/e4-4-toeshoulder-REPORT.md 记录一致）。
# ⚠️ 这 60 个值是**逐位**基线：改了 computeFilmicSpline 的数学（或默认 latitude 的
#    处理）就会红 —— 那正是"零回归"该有的反应。若某个刻意的改动更新了它，必须同时
#    更新本常量 + 报告，不许"顺手重跑覆盖"。
EXPECT_HEX = """\
m1 0 0 0x1.67af0ap-4
m1 0 1 -0x1.577304p-4
m1 0 2 -0x1.16509ap-2
m1 1 0 0x1.67af0ap-4
m1 1 1 -0x1.577304p-4
m1 1 2 -0x1.16509ap-2
m1 2 0 0x1.67af0ap-4
m1 2 1 -0x1.577304p-4
m1 2 2 -0x1.16509ap-2
m2 0 0 0x0p+0
m2 0 1 0x1.23d038p+0
m2 0 2 0x1.6f0d96p+0
m2 1 0 0x0p+0
m2 1 1 0x1.23d038p+0
m2 1 2 0x1.6f0d96p+0
m2 2 0 0x0p+0
m2 2 1 0x1.23d038p+0
m2 2 2 0x1.6f0d96p+0
m3 0 0 0x1.581e44p+0
m3 0 1 -0x1.72a8f8p+0
m3 0 2 0x0p+0
m3 1 0 0x1.581e44p+0
m3 1 1 -0x1.72a8f8p+0
m3 1 2 0x0p+0
m3 2 0 0x1.581e44p+0
m3 2 1 -0x1.72a8f8p+0
m3 2 2 0x0p+0
m4 0 0 0x1.a3084ep-1
m4 0 1 0x1.e7df06p+1
m4 0 2 0x0p+0
m4 1 0 0x1.a3084ep-1
m4 1 1 0x1.e7df06p+1
m4 1 2 0x0p+0
m4 2 0 0x1.a3084ep-1
m4 2 1 0x1.e7df06p+1
m4 2 2 0x0p+0
m5 0 0 -0x1.3c0966p+0
m5 0 1 -0x1.35b70ep+1
m5 0 2 0x0p+0
m5 1 0 -0x1.3c0966p+0
m5 1 1 -0x1.35b70ep+1
m5 1 2 0x0p+0
m5 2 0 -0x1.3c0966p+0
m5 2 1 -0x1.35b70ep+1
m5 2 2 0x0p+0
lat 0 0x1.3ee524p-1 0x1.421658p-1
lat 1 0x1.3ee524p-1 0x1.421658p-1
lat 2 0x1.3ee524p-1 0x1.421658p-1
scalar -0x1.e9999ap+2 0x1.23d70ap+2 0x1.86b852p+3 0x1.e199bp-11 0x1.1687dep+2 0x1.ceb24ap+1
scalar2 0x1.67af0ap-4 0x1.40c948p-1 0x1.6f0d96p+0"""


def hex_pairs(lines):
    """把 `%a` 打印行解析成 {(类别, 索引…) → hex}（60 项）。"""
    out = {}
    for ln in lines:
        p = ln.split()
        if not p:
            continue
        if p[0].startswith("m"):
            out[(p[0], p[1], p[2])] = p[3]
        elif p[0] == "lat":
            out[("lat", p[1], "min")] = p[2]
            out[("lat", p[1], "max")] = p[3]
        elif p[0] in ("scalar", "scalar2"):
            for i, v in enumerate(p[1:]):
                out[(p[0], str(i))] = v
    return out


# ────────────────────────────────────────── 系数（参考实现 → uniform）
STYLE_IDENTITY = {
    "uExposure": 1.0, "uHalDomainMap": 0.0, "uDomainRatio": 1.0, "uGamut": 0.0,
    "uSat": (1.0, 1.0, 1.0), "uSplit": (0.0, 0.0), "uMono": 0.0, "uToneK": 1.0,
    "uLook": 0.0, "uLookSat": 1.0, "uLookChroma": 1.0,
    "uLocalStrength": 0.0, "uLocalKnee": 0.5, "uLocalKneeHi": 1.5,
    "uLocalRef": 0.5, "uLocalSize": (1.0, 1.0),
}


def common_uni(sp):
    """与 latitude 无关的 filmic 标量（两版同名）。sp = 任一套 compute_spline 结果。"""
    return {
        "uFilmicBlackEv": float(sp["black"]), "uFilmicDr": float(sp["dr"]),
        "uFilmicInputMin": float(sp["input_min"]),
        "uFilmicInputMax": float(sp["input_max"]),
        "uFilmicPower": float(sp["power"]),
        "uFilmicBlackDisp": float(sp["black_disp"]),
        **STYLE_IDENTITY,
    }


def uni_new(black, white, cp, lat_sh, lat_toe):
    cols = []
    for c in range(3):
        sp_t = compute_spline(black, white, cp, float(lat_toe[c]))
        sp_s = compute_spline(black, white, cp, float(lat_sh[c]))
        cols.append((sp_t, sp_s))
    u = {}
    for i in range(5):
        u[f"uFilmicToeM{i + 1}"] = tuple(float(cols[c][0]["m_toe"][4 - i]) for c in range(3))
        u[f"uFilmicShM{i + 1}"] = tuple(float(cols[c][1]["m_sh"][4 - i]) for c in range(3))
    u["uFilmicLinM1"] = tuple(float(cols[c][0]["m_mid"][4]) for c in range(3))
    u["uFilmicLinM2"] = tuple(float(cols[c][0]["m_mid"][3]) for c in range(3))
    u["uFilmicLatMin"] = tuple(float(cols[c][0]["toe_log"]) for c in range(3))
    u["uFilmicLatMax"] = tuple(float(cols[c][1]["sh_log"]) for c in range(3))
    return {**common_uni(cols[1][0]), **u}, cols


# ────────────────────────────────────────── 渲染
class Bench:
    def __init__(self, gl, to_core, src, w, h):
        self.gl, self.src, self.w, self.h = gl, to_core(src), w, h
        self.tex = gl.ctx.texture((w, h), 4, dtype="f4")
        self.tex.filter = (gl.ctx.NEAREST, gl.ctx.NEAREST)
        self.local = gl.ctx.texture((1, 1), 4, dtype="f4")
        self.local.write(np.ones((1, 1, 4), "f4").tobytes())

    def run(self, img, uni):
        """img: (H, W) 单通道线性亮度（三通道相同）→ 输出 (H, W, 4) float32。"""
        h, w = img.shape
        if (w, h) != (self.w, self.h):   # 端点测试用小图 ⇒ 按需重建纹理
            self.w, self.h = w, h
            self.tex = self.gl.ctx.texture((w, h), 4, dtype="f4")
            self.tex.filter = (self.gl.ctx.NEAREST, self.gl.ctx.NEAREST)
        rgba = np.empty((h, w, 4), "f4")
        rgba[..., :3] = img[..., None]
        rgba[..., 3] = 1.0
        self.tex.write(rgba.tobytes())
        return self.gl.run(self.src, uni, {"uTex": self.tex, "uLocal": self.local},
                           size=(w, h))


def srgb8(x):
    """线性显示域 → 8bit sRGB 编码值（与 kFsOut 的 OETF 同式，分段 sRGB）。

    为什么用它当"可见差异"量纲：filmic 的输出是**线性显示域**，暗部数值极小
    （1e-4 级），在线性域比 1/255 会得出"不可见"的假结论；真机 kFsOut 会做 OETF，
    8bit 观感差才是判据（`tools/check-srgb-oetf.py` 同一口径）。"""
    x = np.clip(x, 0.0, 1.0)
    s = np.where(x <= 0.0031308, x * 12.92, 1.055 * np.power(x, 1.0 / 2.4) - 0.055)
    return s * 255.0


def mk_img(levels):
    """levels: (N,) 亮度 → (4, N) 图（每列同一亮度、4 行重复 ⇒ 顺带验通道内一致性）。"""
    return np.repeat(np.asarray(levels, "f4")[None, :], 4, axis=0)


def e_grid(sp, n=11):
    """按 log 编码 e 均匀取样（e=0 与 e=1 用 0.5×/2× 保证 clamp 到端点）。"""
    e = np.linspace(0.0, 1.0, n)
    v = 0.1845 * np.exp2(sp["black"] + e * sp["dr"])
    v[0] *= 0.5
    v[-1] *= 2.0
    return e, v.astype("f4")


def check(name, ok, detail):
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    if not ok:
        FAIL.append(name)


# ────────────────────────────────────────── CPU 侧系数逐位对拍
def brace_block(text, marker):
    """从 marker 起按大括号配平截取一个完整函数定义（validate-shaders.sh 同思路）。"""
    i = text.index(marker)
    depth = 0
    for k in range(text.index("{", i), len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[i:k + 1]
    raise ValueError(marker)


def struct_body(hdr, name="FilmicSpline"):
    m = re.search(r"struct %s \{\n(.*?)\n  \};" % name, hdr, re.S)
    assert m, f"结构体 {name} 未提取到"
    return m.group(1)


def cpu_baseline_check():
    """判据 ①（CPU 侧，**自包含**）：工作区 `computeFilmicSpline` 的 60 个系数 vs `EXPECT_HEX`。

    为什么必要：GPU 侧（判据 ②③④）用的是**参考实现** `filmic_offline.compute_spline` 算出的
    系数 ⇒ 它检验的是 shader 的求值，检验不了"C++ 侧求解在默认 latitude 下是否还等于
    E4-4 交付时的结果"。本步骤把工作区的 `solve5` + `computeFilmicSpline` 抠出来真编真跑，
    打印全部系数的十六进制浮点（`%a`）与**固化基线**逐位比较 ⇒ 与 git 状态无关、随时可跑。

    ⚠️ 这里**不再**与 `git show HEAD` 对比（2026-10-11 U-4 改造）：HEAD 会随提交漂移，
    E4-4 一提交就使原判据自我失效（`uFilmicM1` 在"旧版"里不存在）。基线来源与一次性
    复现见 `EXPECT_HEX` 上方注释。
    """
    import tempfile
    new_cpp = open(FR, encoding="utf-8").read()
    new_h = open(os.path.join(REPO, "core/engine/src/render/FrameRenderer.h"),
                 encoding="utf-8").read()

    solve = brace_block(new_cpp, "bool solve5(double a[5][5]")
    fn = brace_block(new_cpp, "FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(")
    fn = fn.replace("FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(",
                    "NewSp compute_spline(").replace("FilmicSpline sp;", "NewSp sp;")
    src = f"""#include <algorithm>
#include <cmath>
#include <cstdio>
struct NewSp {{
{struct_body(new_h)}
}};
namespace impl {{
{solve}
{fn}
}}
int main() {{
  const float lat[3] = {{0.01f, 0.01f, 0.01f}};
  NewSp n = impl::compute_spline(-7.65f, 4.56f, 1.0f, lat, lat);
  const float (*nm[5])[3] = {{n.m1, n.m2, n.m3, n.m4, n.m5}};
  for (int k = 0; k < 5; ++k)
    for (int ch = 0; ch < 3; ++ch)
      for (int seg = 0; seg < 3; ++seg)
        printf("m%d %d %d %a\\n", k + 1, ch, seg, nm[k][ch][seg]);
  for (int ch = 0; ch < 3; ++ch)
    printf("lat %d %a %a\\n", ch, n.lat_min[ch], n.lat_max[ch]);
  printf("scalar %a %a %a %a %a %a\\n",
         n.black_ev, n.white_ev, n.dynamic_range,
         n.input_min, n.input_max, n.output_power);
  printf("scalar2 %a %a %a\\n", n.black_disp, n.grey_log, n.contrast);
  return 0;
}}
"""
    with tempfile.TemporaryDirectory() as td:
        cpp = os.path.join(td, "basecheck.cpp")
        exe = os.path.join(td, "basecheck")
        open(cpp, "w").write(src)
        r = subprocess.run(["clang++", "-std=c++20", "-O2", "-o", exe, cpp],
                           capture_output=True, text=True)
        if r.returncode != 0:
            check("① 默认零回归（工作区系数 vs 固化基线，逐位）", False,
                  "clang++ 编译失败：\n" + r.stderr[-2000:])
            return
        out = subprocess.run([exe], capture_output=True, text=True).stdout

    want = hex_pairs(EXPECT_HEX.strip().split("\n"))
    got = hex_pairs(out.strip().split("\n"))
    bad = [f"{k}: {got.get(k)} != {want[k]}" for k in want if got.get(k) != want[k]]
    extra = [k for k in got if k not in want]
    detail = (f"{len(want)} 个 hex 浮点（15 个多项式系数 × 3 通道 + lat_min/max × 3 + "
              f"9 个标量）；不同 = {len(bad)}")
    if extra:
        detail += f"；基线未覆盖的新项 = {len(extra)}（例：{extra[0]}）"
    if bad:
        detail += "；例：" + bad[0]
    check("① 默认零回归（工作区系数 vs 固化基线，逐位）", not bad and not extra, detail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true", help="打印系数表")
    args = ap.parse_args()

    gl_cls, to_core = load_offline_gl()
    new_src = extract_film(open(FR, encoding="utf-8").read())
    assert "uFilmicToeM1" in new_src, \
        "kFsFilm 里找不到 per-channel 的 uFilmicToeM1（E4-4 的 shader 提取对不上）"

    # [37][38] / [4] 固定（判据 ④ 的前提）：darktable 默认 EV + 中性 contrast
    B, W, CP = -7.65, 4.56, 1.0
    u_def, cols_def = uni_new(B, W, CP, [0.01] * 3, [0.01] * 3)
    e, v = e_grid(cols_def[1][0])
    img = mk_img(v)
    W_, H_ = img.shape[1], img.shape[0]

    gl = gl_cls(W_, H_)
    try:
        b_new = Bench(gl, to_core, new_src, W_, H_)
        out_def = b_new.run(img, u_def)

        # ── 判据 ①（GPU 侧补充，自包含）：默认 latitude ⇒ per-channel 结构必须退化为
        # "三通道同一套"（= 与 E4-4 之前单套求解等价的必要条件）──────────────────
        # 原判据 ① 的 GPU 部分（"新 shader vs 改前 shader 逐位相同"）依赖 `git show HEAD`，
        # E4-4 提交后自我失效 ⇒ 由 ①（CPU 侧 vs 固化基线）+ 本条取代。
        rgb_same = (np.array_equal(out_def[..., 0], out_def[..., 1]) and
                    np.array_equal(out_def[..., 0], out_def[..., 2]))
        check("① 默认 latitude 下 per-channel 退化为单套（GPU 输出 R=G=B 逐位）", rgb_same,
              f"三通道全 0.01 ⇒ 系数逐通道相同 ⇒ {W_}×{H_} 上 R/G/B 逐位相同 = {rgb_same}")

        # ── 判据 ② 形状可控：lat_toe 0.01 / 0.1 / 0.3 ──────────────────
        lats = [0.01, 0.1, 0.3]
        outs, toe_logs = [], []
        for lt in lats:
            u, cols = uni_new(B, W, CP, [0.01] * 3, [lt] * 3)
            outs.append(b_new.run(img, u))
            toe_logs.append([cols[c][0]["toe_log"] for c in range(3)])
        print("\n  判据 ② 影调曲线采样（输出，R=G=B；输入亮度 = 0.1845·2^(blackEv+e·dr)）")
        hdr = "    e      v_in      " + "".join(f"  lat={lt:<5}" for lt in lats)
        print(hdr)
        for i in range(len(e)):
            print(f"  {e[i]:.2f}  {v[i]:9.6f}   " +
                  "".join(f"  {outs[k][0, i, 0]:.6f}" for k in range(3)))
        # 过渡区位置（CPU 侧 toe_log）随 lat 单调
        mono_toe = all(toe_logs[k][0] > toe_logs[k + 1][0] for k in range(len(lats) - 1))
        # 输出在暗部随 lat 单调不增（toe 段变短 ⇒ 同斜率下更凹 ⇒ 压得更暗）
        mono_out = all(np.all(outs[k][0, :, 0] >= outs[k + 1][0, :, 0] - 1e-9)
                       for k in range(len(lats) - 1))
        # 可见差异（8bit sRGB > 1 LSB）的最早采样点：随 lat 增大向暗端移动
        starts = []
        for k in range(1, len(lats)):
            d = np.abs(srgb8(outs[k][0, :, 0]) - srgb8(outs[0][0, :, 0]))
            idx = int(np.argmax(d > 1.0)) if np.any(d > 1.0) else -1
            starts.append(idx)
        check("② 过渡区随 lat_toe 单调移动", mono_toe and mono_out and
              all(starts[i] > starts[i + 1] for i in range(len(starts) - 1)),
              f"toe_log(R) = {[round(float(t[0]), 6) for t in toe_logs]}（lat 增大 ⇒ 向暗端移动）；"
              f"暗部输出逐点不增 = {mono_out}；8bit 可见差异(>1LSB)最早采样序号 = {starts}"
              f"（e = {[round(float(e[i]), 2) if i >= 0 else None for i in starts]}）")
        # 端点不动：极端输入（0 与 1e4）
        ext = mk_img([0.0, 1e4])
        ends = [b_new.run(ext, uni_new(B, W, CP, [0.01] * 3, [lt] * 3)[0]) for lt in lats]
        end_same = all(np.array_equal(ends[0], ends[k]) for k in range(1, len(lats)))
        check("② 端点不动 f(0)/f(1)", end_same,
              f"f(0) = {ends[0][0, 0, 0]:.8f}（三档逐位相同 = {end_same}）；"
              f"f(1) = {ends[0][0, 1, 0]:.8f}")

        # ── 判据 ③ per-channel：只改 toe_r ────────────────────────────
        u_r, cols_r = uni_new(B, W, CP, [0.01] * 3, [0.3, 0.01, 0.01])
        out_r = b_new.run(img, u_r)
        r_changed = not np.array_equal(out_r[..., 0], out_def[..., 0])
        g_same = np.array_equal(out_r[..., 1], out_def[..., 1])
        b_same = np.array_equal(out_r[..., 2], out_def[..., 2])
        a_same = np.array_equal(out_r[..., 3], out_def[..., 3])
        # 暗部色偏（8bit sRGB 的 G−R）+ 黑位不抬
        dark = [i for i in range(len(e)) if 0.1 <= e[i] <= 0.5]
        d8 = [float(srgb8(out_r[0, i, 1]) - srgb8(out_r[0, i, 0])) for i in dark]
        rg_ratio = [float(out_r[0, i, 0]) / max(float(out_r[0, i, 1]), 1e-12) for i in dark]
        bias8 = max(abs(x) for x in d8)
        ext_r = b_new.run(ext, u_r)
        black_same = bool(np.array_equal(ext_r[0, 0], ends[0][0, 0]))
        check("③ per-channel：只改 [8] toe_r", r_changed and g_same and b_same and a_same,
              f"R 变 = {r_changed}；G 逐位不变 = {g_same}；B 逐位不变 = {b_same}；"
              f"A 不变 = {a_same}")
        check("③ 暗部色偏可测 + 黑位不抬", bias8 > 1.0 and black_same,
              f"暗部(e∈[0.1,0.5]) R/G = {[round(x, 5) for x in rg_ratio]}；"
              f"8bit sRGB 的 G−R = {[round(x, 2) for x in d8]}（最大 {bias8:.2f} LSB > 1）；"
              f"黑位 f(0) 逐位相同 = {black_same}")

        # ── 判据 ④ 正交性：固定 [37][38]/[4]，改 [5..10] ⇒ 端点不动 ──────
        cases = {"默认 0.01": ([0.01] * 3, [0.01] * 3),
                 "toe=0.3": ([0.01] * 3, [0.3] * 3),
                 "sh=0.3": ([0.3] * 3, [0.01] * 3),
                 "极端 0/1": ([0.0, 0.5, 1.0], [1.0, 0.5, 0.0])}
        rows, ok4 = [], True
        for name, (ls, lt) in cases.items():
            u = uni_new(B, W, CP, ls, lt)[0]
            o = b_new.run(ext, u)
            rows.append((name, float(o[0, 0, 0]), float(o[0, 1, 0])))
            ok4 = ok4 and np.array_equal(o, ends[0])
        print("\n  判据 ④ 端点（固定 [37][38]=-7.65/4.56、[4]=1.0，只改 [5..10]）")
        for name, f0, f1 in rows:
            print(f"    {name:10s} f(0) = {f0:.10f}   f(1) = {f1:.10f}")
        check("④ 端点不随 [5..10] 变化（含极端 0/1）", ok4,
              f"4 组参数下 f(0)/f(1) 与默认逐位相同 = {ok4}")

        if args.dump:
            print("\n  系数（新布局）：")
            for k in ("uFilmicToeM1", "uFilmicToeM2", "uFilmicToeM3", "uFilmicToeM4",
                      "uFilmicToeM5", "uFilmicShM1", "uFilmicLinM1", "uFilmicLinM2",
                      "uFilmicLatMin", "uFilmicLatMax"):
                print(f"    {k:16s} = {u_def[k]}")
    finally:
        gl.close()

    # 判据 ① 的 CPU 侧主体：工作区系数 vs **固化基线**（自包含，不依赖 git 历史）
    cpu_baseline_check()

    print("\n" + ("全部通过" if not FAIL else f"存在失败项：{FAIL}"))
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
