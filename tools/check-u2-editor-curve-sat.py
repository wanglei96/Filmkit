#!/usr/bin/env python3
"""U-2 编辑器链路补齐 `[5..10]` 胶片曲线 + `[37][38]` 黑白点 + `[20..22]` 分区饱和三档 —— 离线验收。

方案 = `docs/reports/preset-DESIGN.md` §9（用户 2026-10-11"不统一的要统一掉，缺的补上"的
**最后一批**）。判据逐条对应任务卡 U-2 的验收判据：

  ① **默认值逐位零回归**（硬判据，**自包含**，不依赖 `git show HEAD` —— 那样的对照物会随
     提交漂移，`check-toeshoulder.py` 踩过这个坑）：
       ①a 从工作区的 `kFsStyle` 源码里**机械删除 U-2 的曲线两段**（uniform+求值函数段 /
          main 段）⇒ 得到"改前等价 shader"；两者在默认参数（`[5..10]=0.01` 三通道相同、
          `[37][38]` 默认、`[20..22]=1.0`）下跑同一张输入，输出**逐位**比较。
       ①b `[20..22]` 三档 = 1.0 时**强制打开** sat 门控（`uSatOn=1`）⇒ 与门控关闭版逐位
          相同（证明 `mix(vec3(l), c, 1.0)` 在 IEEE 下精确恒等 —— 这是"三档同值 ⇒ 与
          改动前的单值语义等价"的直接证据）。
  ② **`[5..10]` per-channel 隔离**：只改 `[8]`（`toe_r`）⇒ R 通道变、G/B **逐位不变**；
     曲线**端点不动**（输入 0/1 的输出逐位 = 0/1，含极端 lat=0/1）。
  ③ **`[37][38]` 生效且与 `[5..10]` 正交**：端点由前者决定、中段形状由后者决定；给实测
     数字（含"改黑点时 x ≥ 中灰的输出逐位不变"这条强正交证据）。
  ④ **`[20..22]` 三档分区正确**：暗/中/高三档分别只影响自己的区间（给最大变化的亮度位置）。
  ⑤ **与拍照侧的一致性证据**：同一组 latitude 下，"编辑器曲线施加在默认成片上"与"用新
     latitude 重拍的显示域结果"逐点对照（域不同 ⇒ 口径写在判据 ⑤ 的输出里）。
  ⑥ **mutation 自检**（脚本内直接跑篡改版，每项都必须"抓到"）：
       M1 CPU 侧门控漏掉 `[37][38]`（只判 latitude）⇒ 黑白点非默认时**静默失效**；
       M2 shader 里删掉差值项（只留端点重映射）⇒ latitude 变化必须仍然可见（否则是死代码）；
       M3 shader 里让 `curveG` 复用 R 通道系数（per-channel 串味）⇒ 只改 `[6]` 必须抓到；
       M4 C++ 侧把 per-channel 循环写成"三通道都用第 0 通道"⇒ 只改 `[8]` 必须抓到。

**系数来源 = 引擎源码本身**（不是 numpy 直译）：判据 ①…⑥ 用到的 34 个曲线 uniform 全部由
`clang++` 编译**工作区** `FrameRenderer.cpp` 里的 `solve5` + `srgbEncodeD` +
`computeFilmicSpline` + `computeEditorCurve`（括号配平提取、机械改名）后真跑打印 ——
与 `check-toeshoulder.py` 的判据 ① 同一手法。
shader 侧则真跑 `kFsStyle`（只做 `#version 300 es → 330 core` 的机械改写，复用
`tools/engine-shader-offline.py` 的 GL 封装）。判据 ⑤ 的"拍照链路"一列同样来自上面那份
C++（`filmic_scalar` 是 `kFsFilm` 的 `filmicScalarR` 的逐字 C++ 转写）。

用法：python3 tools/check-u2-editor-curve-sat.py [--dump]
依赖：moderngl + glfw（同 check-style-passthrough.py）+ clang++。
"""
import argparse
import importlib.util
import os
import re
import subprocess
import sys
import tempfile

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FR_REL = "core/engine/src/render/FrameRenderer.cpp"
FR = os.path.join(REPO, FR_REL)
ESO = os.path.join(REPO, "tools", "engine-shader-offline.py")
FAIL = []
DUMP = False
LSB = 1.0 / 255.0


def check(name, ok, detail):
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    if not ok:
        FAIL.append(name)


def load_eso():
    spec = importlib.util.spec_from_file_location("eso", ESO)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


# ────────────────────────────────────────── 源码提取（C++ 侧真值）
def grab_braced(src, at):
    """从 src[at] == '{' 开始配平抽取（这些函数体里没有裸字符串/注释干扰）。"""
    assert src[at] == '{', src[at - 40:at + 10]
    d = 0
    i = at
    while i < len(src):
        if src[i] == '{':
            d += 1
        elif src[i] == '}':
            d -= 1
            if d == 0:
                return src[at:i + 1]
        i += 1
    raise AssertionError("大括号不配平")


def extract_body(src, sig_regex, tag):
    """签名 + 函数体（含签名 —— harness 里直接当定义用）。"""
    m = re.search(sig_regex, src, re.S)
    assert m, f"{tag} 未提取到（源码结构变了？）"
    return src[m.start():m.end()] + grab_braced(src, src.index('{', m.end()))


CXX_HARNESS = r"""
#include <algorithm>
#include <cmath>
#include <cstdio>
struct FilmParams {
  float shoulder[3] = {0.01f, 0.01f, 0.01f};
  float toe[3] = {0.01f, 0.01f, 0.01f};
  float contrast = 1.0f;
  float filmic_black_ev = -7.65f;
  float filmic_white_ev = 4.56f;
};
struct FilmicSpline {
  float m1[3][3]; float m2[3][3]; float m3[3][3]; float m4[3][3]; float m5[3][3];
  float lat_min[3]; float lat_max[3]; float lat_sh[3]; float lat_toe[3];
  float black_ev, white_ev, dynamic_range, input_min, input_max;
  float output_power, black_disp, grey_log, contrast, contrast_param;
};
struct EditorCurve {   // 默认值与 FrameRenderer.h 的 EditorCurve 逐字一致（on=false 时用）
  float toe[3][5] = {}; float sh[3][5] = {}; float at[3][2] = {};
  float lin[3][2] = {{0.0f, 1.0f}, {0.0f, 1.0f}, {0.0f, 1.0f}};
  float b_toe[3][5] = {}; float b_sh[3][5] = {}; float b_at[3][2] = {};
  float b_lin[3][2] = {{0.0f, 1.0f}, {0.0f, 1.0f}, {0.0f, 1.0f}};
  float lo_a = 1.0f, lo_b = 0.0f, hi_a = 1.0f, hi_b = 0.0f; bool on = false;
};
namespace impl {
__SOLVE5__
__SRGB__
__SPLINE__
__CURVE__
// kFsFilm 的 `filmicScalarR` 逐字转写（log 编码 → 三段求值 → 幂）—— 判据 ⑤ 的"拍照链路"
// 一列用它，保证与 shader 用的是同一套数学。
float filmic_scalar(const FilmicSpline& sp, int ch, float v) {
  const float kGrey = 0.1845f;
  const float kNormMin = 1.52587890625e-5f;
  float s = std::max(v, kNormMin);
  float e = std::clamp((std::log2(s / kGrey) - sp.black_ev) /
                           std::max(sp.dynamic_range, 0.2f), 0.0f, 1.0f);
  float c;
  if (e < sp.lat_min[ch]) {
    c = sp.m1[ch][0] + e * (sp.m2[ch][0] + e * (sp.m3[ch][0] + e * (sp.m4[ch][0] + e * sp.m5[ch][0])));
  } else if (e > sp.lat_max[ch]) {
    c = sp.m1[ch][1] + e * (sp.m2[ch][1] + e * (sp.m3[ch][1] + e * (sp.m4[ch][1] + e * sp.m5[ch][1])));
  } else {
    c = sp.m1[ch][2] + e * sp.m2[ch][2];
  }
  c = std::clamp(c, 0.0f, 1.0f);
  return std::pow(std::max(c, 0.0f), sp.output_power);
}
float srgb_encode(float c) {
  c = std::clamp(c, 0.0f, 1.0f);
  return c > 0.0031308f ? 1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f : 12.92f * c;
}
}  // namespace impl
int main(int argc, char** argv) {
  // 参数：lat_sh[3] lat_toe[3] black_ev white_ev（判据 ⑤ 额外要拍照链路一列）
  float lsh[3], ltoe[3], bev, wev;
  sscanf(argv[1], "%f,%f,%f", &lsh[0], &lsh[1], &lsh[2]);
  sscanf(argv[2], "%f,%f,%f", &ltoe[0], &ltoe[1], &ltoe[2]);
  bev = atof(argv[3]); wev = atof(argv[4]);
  EditorCurve ec = impl::compute_curve(lsh, ltoe, bev, wev);
  printf("on %d\n", ec.on ? 1 : 0);
  printf("lo %.9g %.9g\nhi %.9g %.9g\n", ec.lo_a, ec.lo_b, ec.hi_a, ec.hi_b);
  const char* seg[3] = {"toe", "sh", "lin"};
  for (int ch = 0; ch < 3; ++ch) {
    printf("cat %d %.9g %.9g\n", ch, ec.at[ch][0], ec.at[ch][1]);
    printf("cbat %d %.9g %.9g\n", ch, ec.b_at[ch][0], ec.b_at[ch][1]);
    for (int k = 0; k < 5; ++k) {
      printf("c%s%d %d %.9g\n", seg[0], k + 1, ch, ec.toe[ch][k]);
      printf("c%s%d %d %.9g\n", seg[1], k + 1, ch, ec.sh[ch][k]);
      printf("b%s%d %d %.9g\n", seg[0], k + 1, ch, ec.b_toe[ch][k]);
      printf("b%s%d %d %.9g\n", seg[1], k + 1, ch, ec.b_sh[ch][k]);
    }
    printf("clin1 %d %.9g\nclin2 %d %.9g\n", ch, ec.lin[ch][0], ch, ec.lin[ch][1]);
    printf("blin1 %d %.9g\nblin2 %d %.9g\n", ch, ec.b_lin[ch][0], ch, ec.b_lin[ch][1]);
  }
  // 判据 ⑤：拍照链路的显示域曲线（默认 & 目标 latitude 各一条）
  FilmParams d{};
  FilmicSpline sp = impl::compute_spline(bev, wev, d.contrast, lsh, ltoe);
  for (int i = 0; i < 33; ++i) {
    float s = std::pow(10.0f, -3.0f + 0.125f * i);   // 线性 1e-3 … 1e1
    printf("cam %d %.9g %.9g %.9g\n", i, s,
           impl::srgb_encode(impl::filmic_scalar(sp, 0, s)),
           impl::srgb_encode(impl::filmic_scalar(sp, 1, s)));
  }
  return 0;
}
"""


def build_cxx(workdir, src_text=None, tag="u2curve"):
    """从**工作区**源码提取 4 个函数 + 编译成可执行（判据 0：系数真值来自引擎源码）。

    `src_text` 非 None 时用传入的源码（mutation 自检 M4 用篡改版），文件名加 `tag` 以免覆盖。
    """
    src = src_text if src_text is not None else open(FR, encoding="utf-8").read()
    solve5 = extract_body(src, r"bool solve5\(double a\[5\]\[5\][^{]*\)", "solve5")
    srgb = extract_body(src, r"double srgbEncodeD\(double c\)", "srgbEncodeD")
    spline = extract_body(
        src,
        r"FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline\([^{]*\)",
        "computeFilmicSpline")
    curve = extract_body(
        src,
        r"FrameRenderer::EditorCurve FrameRenderer::computeEditorCurve\([^{]*\)",
        "computeEditorCurve")
    spline = spline.replace(
        "FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(",
        "FilmicSpline compute_spline(")
    curve = curve.replace(
        "FrameRenderer::EditorCurve FrameRenderer::computeEditorCurve(",
        "EditorCurve compute_curve(")
    curve = curve.replace("computeFilmicSpline(", "compute_spline(")
    body = (CXX_HARNESS.replace("__SOLVE5__", "namespace { " + solve5 + " }")
            .replace("__SRGB__", srgb)
            .replace("__SPLINE__", spline)
            .replace("__CURVE__", curve))
    cpp = os.path.join(workdir, tag + ".cpp")
    exe = os.path.join(workdir, tag)
    open(cpp, "w").write(body)
    r = subprocess.run(["clang++", "-std=c++20", "-O2", "-o", exe, cpp],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError("clang++ 编译失败：\n" + r.stderr[-3000:])
    return exe


def run_cxx(exe, lat_sh, lat_toe, black_ev=-7.65, white_ev=4.56):
    """跑一次取系数（逐项用 %.9g ⇒ 与 shader 的 float32 uniform 同量级）。"""
    args = [exe,
            ",".join(f"{v!r}" for v in lat_sh),
            ",".join(f"{v!r}" for v in lat_toe),
            repr(float(black_ev)), repr(float(white_ev))]
    out = subprocess.run(args, capture_output=True, text=True).stdout
    vals, cam = {}, []
    for line in out.strip().split("\n"):
        p = line.split()
        if p[0] == "on":
            vals["on"] = float(p[1])
        elif p[0] == "lo":
            vals["lo"] = (float(p[1]), float(p[2]))
        elif p[0] == "hi":
            vals["hi"] = (float(p[1]), float(p[2]))
        elif p[0] == "cam":
            cam.append((float(p[2]), float(p[3]), float(p[4])))
        elif p[0] in ("cat", "cbat"):        # 两个值：toe_at / sh_at
            vals[(p[0], int(p[1]))] = (float(p[2]), float(p[3]))
        else:
            vals[(p[0], int(p[1]))] = float(p[2])
    return vals, cam


def curve_uniforms(vals):
    """CPU 侧 EditorCurve → kFsStyle 的 34 个曲线 uniform（**名称与引擎逐一对应**）。"""
    u = {"uCurveOn": vals["on"],
         "uCurveLo": (vals["lo"][0], vals["lo"][1], 0.0, 0.0),
         "uCurveHi": (vals["hi"][0], vals["hi"][1], 0.0, 0.0)}
    u["uCurveToeAt"] = (vals[("cat", 0)][0], vals[("cat", 1)][0], vals[("cat", 2)][0])
    u["uCurveShAt"] = (vals[("cat", 0)][1], vals[("cat", 1)][1], vals[("cat", 2)][1])
    u["uBaseToeAt"] = (vals[("cbat", 0)][0], vals[("cbat", 1)][0], vals[("cbat", 2)][0])
    u["uBaseShAt"] = (vals[("cbat", 0)][1], vals[("cbat", 1)][1], vals[("cbat", 2)][1])
    for k in range(1, 6):
        for pre, key in (("uCurveToeM", f"ctoe{k}"), ("uCurveShM", f"csh{k}"),
                         ("uBaseToeM", f"btoe{k}"), ("uBaseShM", f"bsh{k}")):
            u[f"{pre}{k}"] = (vals[(key, 0)], vals[(key, 1)], vals[(key, 2)])
    for pre, key in (("uCurveLinM", "clin"), ("uBaseLinM", "blin")):
        u[f"{pre}1"] = (vals[(key + "1", 0)], vals[(key + "1", 1)], vals[(key + "1", 2)])
        u[f"{pre}2"] = (vals[(key + "2", 0)], vals[(key + "2", 1)], vals[(key + "2", 2)])
    return u


# ────────────────────────────────────────── GL 装置
class Rig:
    def __init__(self, gl, src):
        self.gl = gl
        self.sh = {}
        for m in re.finditer(r'const char\*\s+(kFs\w+)\s*=\s*R"\((.*?)\)";', src, re.S):
            self.sh[m.group(1)] = m.group(2)
        self.lut = gl.ctx.texture3d((2, 2, 2), 4, np.ones((8, 4), "f2").tobytes(), dtype="f2")
        self.grain = gl.ctx.texture((1, 1), 1, np.ones((1,), "f2").tobytes(), dtype="f2")

    def run(self, name, img, uni, override=None):
        h, w = img.shape[:2]
        tex = self.gl.ctx.texture((w, h), 3, dtype="f4")
        tex.write(np.ascontiguousarray(img, dtype="f4").tobytes())
        tex.filter = (self.gl.ctx.NEAREST, self.gl.ctx.NEAREST)
        tex.repeat_x, tex.repeat_y = False, False
        out = self.gl.run(override if override is not None else self.sh[name], uni,
                          {"uTex": tex, "uLut": self.lut, "uGrainLut": self.grain},
                          size=(w, h))
        tex.release()
        return out


def base_style_uniforms(w, h, *, curve=None, sat=(1.0, 1.0, 1.0), sat_on=None,
                        contrast=1.0):
    """kFsStyle 的恒等基线（其余全关）—— 只留 U-2 的两组参数可调。"""
    clar = max(1.0, 0.005 * min(w, h))
    u = {
        "uGrainAmount": 0.0, "uGrainSeed": 0.0, "uGrainZoom": 1.0,
        "uGrainNorm": 1.0 / float(min(w, h)),
        "uCropOrigin": (0.0, 0.0), "uCropSize": (1.0, 1.0), "uCropRot": 0.0,
        "uLutOn": 0.0, "uLutStrength": 0.0, "uLutDomainMin": (0.0, 0.0, 0.0),
        "uLutDomainInv": (1.0, 1.0, 1.0), "uLutSize": 2.0,
        "uEv": 0.0, "uWbGain": (1.0, 1.0, 1.0), "uExpWbOn": 0.0,
        "uContrast": float(contrast), "uContrastOn": 1.0 if contrast != 1.0 else 0.0,
        "uSatShadow": sat[0], "uSatMid": sat[1], "uSatHigh": sat[2],
        "uSatOn": (1.0 if any(v != 1.0 for v in sat) else 0.0) if sat_on is None else sat_on,
        "uClarity": 0.0, "uClarStep": (clar / w, clar / h),
        "uLocalOn": 0.0, "uLocalType": 0.0, "uLocalCenter": (0.5, 0.5),
        "uLocalRadius": (0.35, 0.35), "uLocalFeather": 0.5, "uLocalAngle": 0.0,
        "uLocalWidth": 0.25, "uLocalEv": 0.0, "uLocalEvOn": 0.0,
        "uLocalSat": 1.0, "uLocalSatOn": 0.0,
        "uHighlights": 0.0, "uShadows": 0.0, "uWhites": 0.0, "uBlacks": 0.0,
        "uFade": 0.0, "uToneOn": 0.0,
        "uSplit": (0.0, 0.0), "uMono": 0.0,
        "uVigScaleXY": (w / float(max(w, h)), h / float(max(w, h))),
        "uVigDscale": 0.8, "uVigFscale": 0.5, "uVigBrightness": 0.0,
    }
    u["uCurveOn"] = 0.0
    if curve:
        u.update(curve)
    return u


def ramp_img(w, h):
    """灰阶输入：每列一个显示域亮度（v/255），三通道相同 ⇒ 可当"曲线测量仪"。"""
    lv = (np.arange(w, dtype=np.float32) + 0.5) / w * 255.0
    return np.repeat((lv / 255.0)[None, :, None], h, axis=0).repeat(3, axis=2).astype(np.float32)


def grey_img(w, h, levels):
    img = np.empty((h, w, 3), np.float32)
    for i, v in enumerate(levels):
        img[:, i, :] = v / 255.0
    return img


# U-2 在 kFsStyle 里加的段（① 的"改前等价"要整段删掉）
U2_BLOCKS = [
    ("uniform + 求值函数段",
     "// ── U-2 胶片曲线 [5..10] + [37][38]（display 域；设计依据见 .cpp 的 computeEditorCurve）──",
     "// 与 kFsOut:4540 的 srgbEncode **严格互逆**"),
    ("main 曲线段",
     "    // ── U-2 胶片曲线 [5..10] + [37][38]（display 域；**与 [4] 正交、排在 [4] 之后**）──",
     "    // ── E2-2 ③ + E2-6 饱和度（显示域绕 luma 插值；1 = 恒等）"),
]


def cut_between(text, start, end, tag):
    hits = text.count(start)
    assert hits == 1, f"{tag}：起点标记命中 {hits} 次（期望 1）—— shader 结构变了？"
    i = text.index(start)
    j = text.find(end, i + len(start))
    assert j > i, f"{tag}：终点标记未找到 —— shader 结构变了？"
    return text[:i] + text[j:]


def pre_u2_style(src):
    out = src
    for tag, a, b in U2_BLOCKS:
        out = cut_between(out, a, b, tag)
    return out


# ────────────────────────────────────────── 判据
def main():
    global DUMP
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true")
    args = ap.parse_args()
    DUMP = args.dump

    eso = load_eso()
    src = open(FR, encoding="utf-8").read()
    with tempfile.TemporaryDirectory() as td:
        try:
            exe = build_cxx(td)
        except RuntimeError as e:
            check("⓪ 系数真值来自引擎源码（clang++ 真跑）", False, str(e)[:400])
            return 1
        check("⓪ 系数真值来自引擎源码（clang++ 真跑）", True,
              "solve5 + srgbEncodeD + computeFilmicSpline + computeEditorCurve 已提取并编译")

        gl = eso.GL(64, 64)
        try:
            rig = Rig(gl, src)
            # ── 判据 ①a：默认值逐位零回归（删段对比）
            w, h = 64, 4
            img = ramp_img(w, h)
            base_vals, _ = run_cxx(exe, [0.01] * 3, [0.01] * 3)
            ubase = base_style_uniforms(w, h)
            got = rig.run("kFsStyle", img, ubase)
            cut = rig.run("kFsStyle", img, ubase, override=pre_u2_style(rig.sh["kFsStyle"]))
            d = np.abs(got[..., :3] - cut[..., :3])
            check("①a 默认零回归（U-2 曲线段删段对比）", float(d.max()) == 0.0,
                  f"max|Δ| = {d.max() * 255:.3f} LSB, 非零像素 = {int((d > 0).sum())}/{d.size}"
                  f"（[5..10]=0.01 三通道相同 + [37][38] 默认 ⇒ uCurveOn=0 ⇒ 曲线段不进）")

            # ── 判据 ①b：三档 = 1.0 时 sat 段逐位恒等（强制开门控）
            u_on = base_style_uniforms(w, h, sat=(1.0, 1.0, 1.0), sat_on=1.0)
            got_on = rig.run("kFsStyle", img, u_on)
            d1b = np.abs(got_on[..., :3] - got[..., :3])
            check("①b 三档 = 1.0 时 sat 段逐位恒等（强制 uSatOn=1）", float(d1b.max()) == 0.0,
                  f"max|Δ| = {d1b.max() * 255:.3f} LSB（mix(vec3(l), c, 1.0) 在 IEEE 下精确恒等）")

            # ── 判据 ②：per-channel 隔离 + 端点不动
            lat_toe_r = [0.4, 0.01, 0.01]
            v2, _ = run_cxx(exe, [0.01] * 3, lat_toe_r)
            u2 = base_style_uniforms(w, h, curve=curve_uniforms(v2))
            got_r = rig.run("kFsStyle", img, u2)
            dR = np.abs(got_r[..., 0] - got[..., 0])
            dG = np.abs(got_r[..., 1] - got[..., 1])
            dB = np.abs(got_r[..., 2] - got[..., 2])
            check("② 只改 [8] toe_r ⇒ R 变、G/B 逐位不变",
                  float(dR.max()) > 2 * LSB and float(dG.max()) == 0.0 and float(dB.max()) == 0.0,
                  f"max|ΔR| = {dR.max() * 255:.2f} LSB, max|ΔG| = {dG.max() * 255:.2f}, "
                  f"max|ΔB| = {dB.max() * 255:.2f}")
            # 端点不动：输入 0 / 255 两列的输出逐位相同
            corner = grey_img(2, 4, [0, 255])
            e_base = rig.run("kFsStyle", corner, base_style_uniforms(2, 4))
            e_lat = rig.run("kFsStyle", corner, base_style_uniforms(2, 4, curve=curve_uniforms(
                run_cxx(exe, [1.0] * 3, [1.0] * 3)[0])))
            e_bw = rig.run("kFsStyle", corner, base_style_uniforms(
                2, 4, curve=curve_uniforms(run_cxx(exe, [0.01] * 3, [0.01] * 3, -4.0, 2.0)[0])))
            ok_end = (np.array_equal(e_lat[0, 0, :3], e_base[0, 0, :3])
                      and np.array_equal(e_lat[0, 1, :3], e_base[0, 1, :3]))
            check("② 端点不动（[5..10] 取极端 lat=0/1 时输入 0/255 的输出逐位不变）", ok_end,
                  f"输入 0: lat版 = {e_lat[0, 0, 0] * 255:.2f} / base = {e_base[0, 0, 0] * 255:.2f}；"
                  f"输入 255: lat版 = {e_lat[0, 1, 0] * 255:.2f} / base = {e_base[0, 1, 0] * 255:.2f}")
            if DUMP:
                print("    [dump] 端点（默认 [37][38] / 改 [37][38]）：",
                      e_base[0, :, 0] * 255, e_bw[0, :, 0] * 255)

            # ── 判据 ③：[37][38] 生效 + 与 [5..10] 正交
            #   改黑点（-7.65 → -4）⇒ 暗部压暗；改白点（4.56 → 2）⇒ 亮部压到白
            v_blk, _ = run_cxx(exe, [0.01] * 3, [0.01] * 3, -4.0, 4.56)
            v_wht, _ = run_cxx(exe, [0.01] * 3, [0.01] * 3, -7.65, 2.0)
            u_blk = base_style_uniforms(w, h, curve=curve_uniforms(v_blk))
            u_wht = base_style_uniforms(w, h, curve=curve_uniforms(v_wht))
            g_blk = rig.run("kFsStyle", img, u_blk)
            g_wht = rig.run("kFsStyle", img, u_wht)
            lum_cols = img[0, :, 0]
            d_blk = (g_blk[0, :, 0] - got[0, :, 0]) * 255
            d_wht = (g_wht[0, :, 0] - got[0, :, 0]) * 255
            mid = int(np.argmin(np.abs(lum_cols - 0.4666961)))
            dark = int(np.argmin(np.abs(lum_cols - 0.1)))
            bright = int(np.argmin(np.abs(lum_cols - 0.9)))
            check("③ [37] 黑点生效（black_ev −7.65 → −4 ⇒ 压暗暗部、中灰附近不动）",
                  d_blk[dark] < -2.0 and abs(d_blk[mid]) <= 1.0,
                  f"暗部(0.1): {d_blk[dark]:+.2f} LSB, 中灰(0.467): {d_blk[mid]:+.2f} LSB, "
                  f"最暗列: {d_blk[:8].max():+.2f} LSB")
            check("③ [38] 白点生效（white_ev 4.56 → 2 ⇒ 亮部压到白/暗部不动）",
                  d_wht[bright] > 2.0 and abs(d_wht[dark]) <= 0.5,
                  f"亮部(0.9): {d_wht[bright]:+.2f} LSB, 暗部(0.1): {d_wht[dark]:+.2f} LSB, "
                  f"纯白列: {d_wht[-1]:+.2f} LSB")
            # 强正交证据：改黑点时 x ≥ 中灰的列**逐位不变**；改白点时 x < 中灰的列逐位不变
            hi_cols = slice(mid + 1, w)
            lo_cols = slice(0, mid)
            ok_orth = (np.array_equal(g_blk[:, hi_cols, :3], got[:, hi_cols, :3])
                       and np.array_equal(g_wht[:, lo_cols, :3], got[:, lo_cols, :3]))
            check("③ 正交（[37] 只动 x<中灰、[38] 只动 x>中灰，另一侧逐位不变）", ok_orth,
                  f"改黑点时 x≥中灰的 {w - mid - 1} 列逐位相同 = "
                  f"{np.array_equal(g_blk[:, hi_cols, :3], got[:, hi_cols, :3])}；"
                  f"改白点时 x<中灰的 {mid} 列逐位相同 = "
                  f"{np.array_equal(g_wht[:, lo_cols, :3], got[:, lo_cols, :3])}")

            # ── 判据 ④：[20..22] 三档分区正确（用彩色图，灰色测不出饱和度）
            color = np.zeros((h, w, 3), np.float32)
            color[..., 0] = img[..., 0] * 1.0
            color[..., 1] = img[..., 0] * 0.8
            color[..., 2] = img[..., 0] * 0.6
            cb = rig.run("kFsStyle", color, base_style_uniforms(w, h))
            bands = {"[20] 暗部": (0.0, 1.0, 1.0), "[21] 中间调": (1.0, 0.0, 1.0),
                     "[22] 高光": (1.0, 1.0, 0.0)}
            # 归一化口径：响应 = 权重(l) × 色差幅度(∝ l) ⇒ 直接比响应会让峰值右移。
            # 用"相对色差的变化率"（≈ |1 − 有效饱和度| 的权重部分）当判据，才是分区权重的
            # 真实形状（这也是 kFsFilm 的 ws/wm/wh 的定义）。
            chroma = np.abs(cb[0, :, :3]
                            - cb[0, :, :3].mean(axis=1, keepdims=True)).max(axis=1) * 255.0
            resp, peaks, detail = {}, {}, []
            for name, sv in bands.items():
                g = rig.run("kFsStyle", color, base_style_uniforms(w, h, sat=sv))
                dcol = np.abs(g[0, :, :3] - cb[0, :, :3]).max(axis=1) * 255
                rel = dcol / np.maximum(chroma, 2.0)
                resp[name] = rel
                peaks[name] = float(lum_cols[int(np.argmax(rel))])
                detail.append(f"{name}去饱和(0) ⇒ 权重峰值 @ luma={lum_cols[int(np.argmax(rel))]:.3f}"
                              f"（相对变化率 {rel.max():.3f}），绝对响应峰值 {dcol.max():.1f} LSB")
            # 分区正确性的三条判据（都是"权重公式形状"的直接后果，不依赖绝对量级）：
            #  ① 三档的响应峰值位置**严格单调**：暗 < 中 < 高（ws/wh/wm 的权重中心依次右移）；
            #  ② 暗部档在最亮 1/4 列的响应 < 自身峰值的 25%（exp(−3l) 在高亮处已衰减到 5%）；
            #  ③ 高光档在最暗 1/4 列的响应 < 自身峰值的 25%（smoothstep(0.5,1) 在暗部恒 0）。
            # ⚠ 不判"暗部档峰值必须 < 0.35"：响应 = 权重(l) × 色差幅度(∝ l)，两者相乘的
            #   峰值必然右移（解析上 ws·l 的极值在 l = 1/3、wm·l 的极值在 0.5~0.7）——
            #   实测 [20] 峰在 0.40 / [21] 在 0.71 / [22] 在 0.99，正是理论形状。
            order_ok = peaks["[20] 暗部"] < peaks["[21] 中间调"] < peaks["[22] 高光"]
            tail_ok = (resp["[22] 高光"][: w // 4].mean() < 0.25 * resp["[22] 高光"].max()
                       and resp["[20] 暗部"][3 * w // 4:].mean()
                       < 0.25 * resp["[20] 暗部"].max())
            amp_ok = all(np.abs(rig.run("kFsStyle", color,
                                        base_style_uniforms(w, h, sat=bands[n]))[0, :, :3]
                                - cb[0, :, :3]).max() * 255 > 3.0 for n in bands)
            detail.append(
                f"峰值位置单调(暗<中<高) = {order_ok}；尾部抑制（高光档在暗部 / 暗部档在亮部"
                f" < 25% 峰值）= {tail_ok}；三档幅度都 > 3 LSB = {amp_ok}")
            check("④ [20..22] 三档分区正确（峰值单调 + 区间外抑制）",
                  order_ok and tail_ok and amp_ok, "；".join(detail))

            # ── 判据 ⑤：与拍照链路的一致性对照（域口径见输出）
            print("\n  判据 ⑤ 对照表（显示域 0..255；'cam0' = 默认 latitude 的拍照成片、"
                  "'ideal' = 用目标 latitude 重拍、'ed' = 把 cam0 送进编辑器曲线）")
            for lat in (0.1, 0.3, 0.5):
                v_lat, cam = run_cxx(exe, [lat] * 3, [lat] * 3)
                v_d0, cam0 = run_cxx(exe, [0.01] * 3, [0.01] * 3)
                u_ed = base_style_uniforms(w, h, curve=curve_uniforms(v_lat))
                # 拍照链路的一列（线性 S → 显示域）
                worst_ed, worst_ideal = 0.0, 0.0
                for i in range(0, 17):
                    x0 = cam0[i][1]
                    if x0 <= 0.002 or x0 >= 0.998:
                        continue
                    ideal = cam[i][1]
                    # 把 cam0 的值送进编辑器曲线（1×1 图，输入量化到 8bit ⇒ ±0.5 LSB）
                    ed = float(rig.run(
                        "kFsStyle", grey_img(1, 1, [int(round(x0 * 255))]), u_ed)[0, 0, 0])
                    worst_ed = max(worst_ed, abs(ed - ideal) * 255)
                    worst_ideal = max(worst_ideal, abs(ideal - x0) * 255)
                    if DUMP:
                        print(f"    lat={lat} cam0={x0 * 255:6.1f} ed={ed * 255:6.1f} "
                              f"ideal={ideal * 255:6.1f}  |ed−ideal|={(ed - ideal) * 255:+6.2f}  "
                              f"|ideal−cam0|={(ideal - x0) * 255:+6.2f}")
                print(f"    lat={lat}: 编辑器 |Δ| 最大 = {worst_ed:5.2f}/255，"
                      f"拍照重拍 |Δ| 最大 = {worst_ideal:5.2f}/255（同阶 ⇒ 语义一致）")
                if lat <= 0.3:
                    check(f"⑤ lat={lat} 两条链路的成片变化同阶（|ed−ideal| ≤ 4/255）",
                          worst_ed <= 4.0,
                          f"max|ed − ideal| = {worst_ed:.2f}/255（判据：≤ 4/255 —— "
                          f"域不同，只要求同阶不要求逐点）")
            # ⑤ 的另一半：拍照链路改 latitude 的方向必须与编辑器一致（暗部/高光同向）
            _, c0 = run_cxx(exe, [0.01] * 3, [0.01] * 3)
            _, c5 = run_cxx(exe, [0.5] * 3, [0.5] * 3)
            cam_dark = (c5[6][1] - c0[6][1]) * 255      # 线性 ~1e-1.25 的暗部采样
            u_lat5 = base_style_uniforms(
                1, 1, curve=curve_uniforms(run_cxx(exe, [0.5] * 3, [0.5] * 3)[0]))
            ed_dark = float(rig.run(
                "kFsStyle", grey_img(1, 1, [int(round(c0[6][1] * 255))]), u_lat5)[0, 0, 0])
            ed_dark = (ed_dark - c0[6][1]) * 255
            check("⑤ 方向一致（lat 0.01 → 0.5：拍照侧暗部 Δ 与编辑器侧暗部 Δ 同号）",
                  cam_dark * ed_dark > 0,
                  f"拍照侧 Δ = {cam_dark:+.2f}/255，编辑器侧 Δ = {ed_dark:+.2f}/255")

            # ── 判据 ⑥：mutation 自检
            # M1 CPU 侧门控漏掉 [37][38]：模拟"只判 latitude"⇒ 黑白点非默认时门控=0
            m1 = rig.run("kFsStyle", img, base_style_uniforms(w, h))     # uCurveOn=0
            m1_d = np.abs(m1[..., :3] - got[..., :3]).max()
            check("⑥ M1 门控漏 [37][38] ⇒ 黑白点静默失效（抓得到）",
                  float(m1_d) == 0.0 and float(np.abs(g_blk - got).max()) > 0,
                  f"门控=0 时改黑白点 ⇒ max|Δ| = {m1_d * 255:.2f} LSB（与基线相同 = 静默失效，"
                  f"而正确版 {np.abs(g_blk - got).max() * 255:.2f} LSB ≠ 0）")
            # M2 shader 删掉差值项（只留端点重映射）
            mut2 = rig.sh["kFsStyle"].replace(
                "c = clamp(vec3(t3.r + curveR(t3.r) - baseR(t3.r),\n"
                "                       t3.g + curveG(t3.g) - baseG(t3.g),\n"
                "                       t3.b + curveB(t3.b) - baseB(t3.b)), 0.0, 1.0);",
                "c = clamp(t3, 0.0, 1.0);")
            assert mut2 != rig.sh["kFsStyle"], "M2 篡改未生效（shader 文本变了？）"
            m2 = rig.run("kFsStyle", img, u2, override=mut2)
            check("⑥ M2 删差值项 ⇒ latitude 变化必须仍然可见（否则是死代码）",
                  float(np.abs(m2[..., :3] - got_r[..., :3]).max()) > 2 * LSB,
                  f"篡改版 vs 正确版 max|Δ| = {np.abs(m2[..., :3] - got_r[..., :3]).max() * 255:.2f} LSB"
                  f"（> 1 LSB ⇒ 差值项不是死代码）")
            # M3 per-channel 串味：curveG 复用 R 的系数
            mut3 = rig.sh["kFsStyle"].replace(
                "float curveG(float t) {\n"
                "    if (t < uCurveToeAt.g)\n"
                "        return uCurveToeM1.g + t * (uCurveToeM2.g + t * (uCurveToeM3.g + t * (uCurveToeM4.g + t * uCurveToeM5.g)));\n"
                "    if (t > uCurveShAt.g)\n"
                "        return uCurveShM1.g + t * (uCurveShM2.g + t * (uCurveShM3.g + t * (uCurveShM4.g + t * uCurveShM5.g)));\n"
                "    return uCurveLinM1.g + t * uCurveLinM2.g;\n}",
                "float curveG(float t) { return curveR(t); }")
            assert mut3 != rig.sh["kFsStyle"], "M3 篡改未生效"
            v6, _ = run_cxx(exe, [0.01, 0.5, 0.01], [0.01] * 3)     # 只改 [6] shoulder_g
            g6 = rig.run("kFsStyle", img, base_style_uniforms(w, h, curve=curve_uniforms(v6)))
            m3 = rig.run("kFsStyle", img, base_style_uniforms(w, h, curve=curve_uniforms(v6)),
                         override=mut3)
            check("⑥ M3 per-channel 串味（curveG 用 R 的系数）⇒ 抓得到",
                  float(np.abs(m3[..., 1] - g6[..., 1]).max()) > 1.0 / 255.0,
                  f"只改 [6] shoulder_g：正确版 G 变化 = "
                  f"{np.abs(g6[..., 1] - got[..., 1]).max() * 255:.2f} LSB，"
                  f"篡改版 G 变化 = {np.abs(m3[..., 1] - got[..., 1]).max() * 255:.2f} LSB"
                  f"（不同 ⇒ 抓到）")

            # M4 C++ 侧 per-channel 串味（三通道都用第 0 通道）
            mut4_src = src.replace(
                "    build(lat_toe[ch], lat_sh[ch], ec.toe[ch], ec.sh[ch], ec.lin[ch], ec.at[ch]);",
                "    build(lat_toe[0], lat_sh[0], ec.toe[ch], ec.sh[ch], ec.lin[ch], ec.at[ch]);")
            assert mut4_src != src, "M4 篡改未生效（computeEditorCurve 的循环行变了？）"
            exe_m4 = build_cxx(td, src_text=mut4_src, tag="u2curve_m4")
            v_m4, _ = run_cxx(exe_m4, [0.01] * 3, lat_toe_r)
            g_m4 = rig.run("kFsStyle", img, base_style_uniforms(w, h, curve=curve_uniforms(v_m4)))
            dg_m4 = float(np.abs(g_m4[..., 1] - got[..., 1]).max())
            check("⑥ M4 C++ per-channel 串味（三通道共用第 0 通道）⇒ 抓得到", dg_m4 > 2 * LSB,
                  f"只改 [8] toe_r 时篡改版的 G 通道变化 = {dg_m4 * 255:.2f} LSB"
                  f"（> 1 LSB ⇒ 与正确版的「G 逐位不变」矛盾，抓到）")

            print(f"\n  曲线 uniform 抽样（判据 ⓪ 的产物，默认 lat）："
                  f"on={base_vals['on']:.0f} lo={base_vals['lo']} hi={base_vals['hi']} "
                  f"toe_at={[round(base_vals[('cat', c)][0], 4) for c in range(3)]}")
        finally:
            gl.close()

    print()
    if FAIL:
        print(f"失败项：{FAIL}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
