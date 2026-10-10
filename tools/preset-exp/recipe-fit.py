#!/usr/bin/env python3
"""R-1 —— 按「样片指纹」目标曲线重调内置三条预设：离线拟合 / 验收工具。

目标曲线来源 = `docs/reports/preset-sample-fingerprint-RESEARCH.md` §5（容差 **±4/255**）。
本工具跑**真实引擎的两条链路**（不是 numpy 直译）：

  ① **编辑器链路** = 真跑 `kFsStyle`（`FrameRenderer.cpp` 的 GLSL 源码逐字，只做
     `#version 300 es → 330 core` 机械改写；GL 装置复用 `tools/engine-shader-offline.py`，
     shader 抽取/执行方式复用 `tools/check-u2-editor-curve-sat.py`）。
     输入 = sRGB 灰阶 ⇒ **与目标曲线同域**（目标曲线就是从已成片 JPEG 的显示域测出来的），
     因此它是本卡的**主判据**。
  ② **拍照链路** = C++ 侧 `computeFilmicSpline`（引擎同一份函数，括号配平提取后 `clang++`
     真编译真跑，与 `check-u2-editor-curve-sat.py` / `check-toeshoulder.py` 同一手法）
     → 真跑 `kFsOut`（`highlightRolloff` → `srgbEncode` → `[90..94]` → 暗角 → **LUT** → 颗粒）。
     ⚠️ **口径**：目标曲线的"输入 sRGB v"解释为「该反射率在标准 sRGB 渲染下的显示值」
     ⇒ 拍照链路输入取 `s = srgbDecode(v/255)`（线性反射率），与 filmic 的输入域一致。
     ⚠️ **未验证的段**（报告里逐条写明）：RAW 解码 / CCM / 白平衡 / `kFsFilm` 的
     sat·split·mono（线性域三段）/ 光晕（引擎未实现）—— 本工具从 filmic 之后接起。
  ③ 两条链路都关闭**空间变化项**（暗角、颗粒、清晰度）与 `[39] sharpen`：它们让"同一条
     灰阶"在画面上不再同值，会污染曲线测量；本卡也不动这些参数。
  ④ 曲线取 **Rec.709 luma**（与 `sample-fingerprint.py` 同口径，目标曲线就是 luma）。

用法：
    python3 tools/preset-exp/recipe-fit.py                  # 三条都跑，打印实测 vs 目标
    python3 tools/preset-exp/recipe-fit.py --preset ricoh   # 单条（id / name 子串）
    python3 tools/preset-exp/recipe-fit.py --json out.json  # 落盘
    python3 tools/preset-exp/recipe-fit.py --fit            # 参数搜索（只调曲线维度）
    python3 tools/preset-exp/recipe-fit.py --fit --mode both --apply   # 两链路联合 + 写回

依赖：numpy + moderngl + glfw（同 check-u2-editor-curve-sat.py）+ clang++。
"""
import argparse
import importlib.util
import itertools
import json
import os
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
FR = os.path.join(REPO, "core/engine/src/render/FrameRenderer.cpp")
BUILTIN = os.path.join(REPO, "app/src/main/assets/presets/builtin.json")
LUT_DIR = os.path.join(REPO, "app/src/main/assets/luts")
LSB = 1.0 / 255.0

LUMA = np.array([0.2126, 0.7152, 0.0722])   # 与 sample-fingerprint.py::luma 同口径


def _load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


# 复用既有脚本的 shader 抽取 / GL 执行装置（任务卡要求：不另起一套）
U2 = _load(os.path.join(REPO, "tools", "check-u2-editor-curve-sat.py"), "u2h")
FP = _load(os.path.join(HERE, "sample-fingerprint.py"), "fkfp")

# ── 目标曲线（调研 §5.1 / §5.2 / §5.3，逐字）────────────────────────────────
TARGETS = {
    "builtin_leica_m9": dict(
        short="leica_m9",
        title="徕卡一瞬（Leica M9 直出）",
        pts=[(52, 28.6), (85, 71.8), (122, 122.1), (160, 169.2), (200, 205.9), (243, 233.4)],
        sat=1.178, bstar=None,
        evidence="实测：IR M9 still-life 机内 JPEG 灰阶行（调研 §3.1/§5.1）",
    ),
    "builtin_ricoh_negative": dict(
        short="ricoh_negative",
        title="理光负片（Ricoh GR Negative Film）",
        pts=[(40, 42.7), (60, 64.8), (80, 86.4), (100, 109.1), (120, 131.1), (145, 157.8),
             (170, 183.7), (195, 207.7), (215, 225.2), (235, 241.1), (250, 251.9)],
        sat=0.772, bstar=-1.7,
        evidence="实测：GR IIIx 同场景三联图 Negative÷Standard 净效应（调研 §3.2/§5.2）",
    ),
    "builtin_leica_bw": dict(
        short="leica_bw",
        title="徕卡黑白 HC（Leica B&W HC）",
        pts=[(52, 20.5), (85, 52.7), (122, 117.4), (160, 178.7), (200, 219.8), (243, 244.4)],
        sat=None, bstar=None,
        evidence="★**间接推断**：该模式没有样片，用 M9 Contrast +2 的实测曲线代替（调研 §4.5/§5.3）",
    ),
}

# ── 语义名 → 槽位（`EditorExport.kt::FilmParamKeys.bySlot` 的子集）───────────
KEY2SLOT = {
    "exposure_ev": 0, "wb_r": 1, "wb_g": 2, "wb_b": 3, "contrast": 4,
    "shoulder[0]": 5, "shoulder[1]": 6, "shoulder[2]": 7,
    "toe[0]": 8, "toe[1]": 9, "toe[2]": 10,
    "sat_shadow": 20, "sat_mid": 21, "sat_high": 22,
    "split_warm": 23, "split_cool": 24, "monochrome": 25,
    "grain_amount": 26, "grain_size": 27, "grain_midtone_bias": 60, "grain_pivot": 61,
    "lut_strength": 34, "filmic_black_ev": 37, "filmic_white_ev": 38,
    "halation_strength": 62, "halation_threshold": 63, "halation_radius": 64,
    "halation_warmth": 65,
    "vignette_strength": 66, "vignette_radius": 67, "vignette_feather": 68,
    "clarity": 78,
    "temp_mired": 88, "tint": 89,
    "highlights": 90, "shadows": 91, "whites": 92, "blacks": 93, "fade": 94,
}

# ── 恒等基线（只用本卡涉及的槽；值取自 `CameraPresets.neutral()`，逐条核对过）
IDENT = {
    0: 0.0, 1: 1.0, 2: 1.0, 3: 1.0, 4: 1.0,
    5: 0.01, 6: 0.01, 7: 0.01, 8: 0.01, 9: 0.01, 10: 0.01,
    20: 1.0, 21: 1.0, 22: 1.0, 23: 0.0, 24: 0.0, 25: 0.0,
    26: 0.0, 27: 400.0, 34: 1.0, 37: -7.65, 38: 4.56,
    62: 0.0, 63: 0.0, 64: 0.0, 65: 0.0,
    66: 0.0, 67: 85.0, 68: 55.0, 78: 0.0,
    88: 0.0, 89: 0.0, 90: 0.0, 91: 0.0, 92: 0.0, 93: 0.0, 94: 0.0,
}

# ── 本卡允许动的槽（任务卡白名单）：grain / vignette / LUT id 一律不动 ────────
TUNABLE = [4, 5, 6, 7, 8, 9, 10, 20, 21, 22, 23, 24, 34, 37, 38]


# ─────────────────────────────────────────── C++ 侧（引擎同一份数学）
CXX_HARNESS = r"""
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
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
struct EditorCurve {
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

// kFsFilm 的 `filmicScalarR` 逐字转写（log 编码 → 三段求值 → 幂）
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
// 拍照链路的输入口径：显示域 sRGB → 线性反射率（真 sRGB 分段曲线，ADR-7 口径）
float srgb_decode(float c) {
  c = std::clamp(c, 0.0f, 1.0f);
  return c > 0.04045f ? std::pow((c + 0.055f) / 1.055f, 2.4f) : c / 12.92f;
}
}  // namespace impl

// 拍照链路的采样列：默认全 256（验收口径）；拟合阶段用 R1_XS 只算目标点
// ⇒ harness 输出量小 ~20 倍（批量评估时文本解析与 GL 回读是主导成本）。
static void print_cam(const FilmicSpline& sp, const char* tag, int v) {
  float s = impl::srgb_decode(v / 255.0f);
  std::printf("cam %s %d %.9g %.9g %.9g\n", tag, v,
              impl::filmic_scalar(sp, 0, s), impl::filmic_scalar(sp, 1, s),
              impl::filmic_scalar(sp, 2, s));
}

static void emit_cam(const FilmicSpline& sp, const char* tag) {
  const char* env = std::getenv("R1_XS");
  if (env == nullptr) {
    for (int v = 0; v < 256; ++v) print_cam(sp, tag, v);
    return;
  }
  char* end = nullptr;
  while (*env != '\0') {
    long v = std::strtol(env, &end, 10);
    if (end == env) break;
    if (v >= 0 && v < 256) print_cam(sp, tag, static_cast<int>(v));
    env = end;
  }
}

int main() {
  char tag[64];
  float bev, wev, ctr, sh[3], toe[3];
  while (std::scanf("%63s %f %f %f %f %f %f %f %f %f", tag, &bev, &wev, &ctr,
                    &sh[0], &sh[1], &sh[2], &toe[0], &toe[1], &toe[2]) == 10) {
    EditorCurve ec = impl::compute_curve(sh, toe, bev, wev);
    FilmicSpline sp = impl::compute_spline(bev, wev, ctr, sh, toe);
    printf("on %s %d\n", tag, ec.on ? 1 : 0);
    printf("lo %s %.9g %.9g\n", tag, ec.lo_a, ec.lo_b);
    printf("hi %s %.9g %.9g\n", tag, ec.hi_a, ec.hi_b);
    const char* seg[3] = {"toe", "sh", "lin"};
    for (int ch = 0; ch < 3; ++ch) {
      printf("cat %s %d %.9g %.9g\n", tag, ch, ec.at[ch][0], ec.at[ch][1]);
      printf("cbat %s %d %.9g %.9g\n", tag, ch, ec.b_at[ch][0], ec.b_at[ch][1]);
      for (int k = 0; k < 5; ++k) {
        printf("c%s%d %s %d %.9g\n", seg[0], k + 1, tag, ch, ec.toe[ch][k]);
        printf("c%s%d %s %d %.9g\n", seg[1], k + 1, tag, ch, ec.sh[ch][k]);
        printf("b%s%d %s %d %.9g\n", seg[0], k + 1, tag, ch, ec.b_toe[ch][k]);
        printf("b%s%d %s %d %.9g\n", seg[1], k + 1, tag, ch, ec.b_sh[ch][k]);
      }
      printf("clin1 %s %d %.9g\nclin2 %s %d %.9g\n", tag, ch, ec.lin[ch][0], tag, ch, ec.lin[ch][1]);
      printf("blin1 %s %d %.9g\nblin2 %s %d %.9g\n", tag, ch, ec.b_lin[ch][0], tag, ch, ec.b_lin[ch][1]);
    }
    if (std::getenv("R1_NO_CAM") != nullptr) continue;
    emit_cam(sp, tag);
  }
  return 0;
}
"""


class Cxx:
    """提取引擎源码 → 编译 → 批量求值（一次 subprocess 算多组参数）。"""

    def __init__(self, workdir, src_text=None, tag="r1fit"):
        src = src_text if src_text is not None else open(FR, encoding="utf-8").read()
        solve5 = U2.extract_body(src, r"bool solve5\(double a\[5\]\[5\][^{]*\)", "solve5")
        srgb = U2.extract_body(src, r"double srgbEncodeD\(double c\)", "srgbEncodeD")
        spline = U2.extract_body(
            src, r"FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline\([^{]*\)",
            "computeFilmicSpline")
        curve = U2.extract_body(
            src, r"FrameRenderer::EditorCurve FrameRenderer::computeEditorCurve\([^{]*\)",
            "computeEditorCurve")
        spline = spline.replace(
            "FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(",
            "FilmicSpline compute_spline(")
        curve = curve.replace(
            "FrameRenderer::EditorCurve FrameRenderer::computeEditorCurve(",
            "EditorCurve compute_curve(")
        curve = curve.replace("computeFilmicSpline(", "compute_spline(")
        body = (CXX_HARNESS.replace("__SOLVE5__", "namespace { " + solve5 + " }")
                .replace("__SRGB__", srgb).replace("__SPLINE__", spline)
                .replace("__CURVE__", curve))
        cpp = os.path.join(workdir, tag + ".cpp")
        exe = os.path.join(workdir, tag)
        open(cpp, "w").write(body)
        r = subprocess.run(["clang++", "-std=c++20", "-O2", "-o", exe, cpp],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError("clang++ 编译失败：\n" + r.stderr[-3000:])
        self.exe = exe
        self.no_cam = False
        self.xs = None       # None = harness 输出全 256 列；否则只输出这些输入 v

    def run(self, cases):
        """cases: [(tag, black_ev, white_ev, contrast, lat_sh[3], lat_toe[3])] →
        {tag: {"vals": EC 系数, "cam": [256][3] 线性显示域}}"""
        inp = "".join(
            f"{t} {b!r} {w!r} {c!r} {sh[0]!r} {sh[1]!r} {sh[2]!r} {to[0]!r} {to[1]!r} {to[2]!r}\n"
            for t, b, w, c, sh, to in cases)
        env = dict(os.environ)
        if self.no_cam:
            env["R1_NO_CAM"] = "1"
        elif self.xs is not None:
            env["R1_XS"] = " ".join(str(int(v)) for v in self.xs)
        out = subprocess.run([self.exe], input=inp, capture_output=True, text=True, env=env)
        if out.returncode != 0:
            raise RuntimeError("harness 运行失败：\n" + out.stderr[-2000:])
        res = {}
        for line in out.stdout.splitlines():
            p = line.split()
            if not p:
                continue
            r = res.setdefault(p[1], {"vals": {}, "cam": np.zeros((256, 3))})
            k = p[0]
            if k == "cam":
                r["cam"][int(p[2])] = [float(p[3]), float(p[4]), float(p[5])]
            elif k == "on":
                r["vals"]["on"] = float(p[2])
            elif k in ("lo", "hi"):
                r["vals"][k] = (float(p[2]), float(p[3]))
            elif k in ("cat", "cbat"):
                r["vals"][(k, int(p[2]))] = (float(p[3]), float(p[4]))
            else:
                r["vals"][(k, int(p[2]))] = float(p[3])
        return res


def ec_uniforms(vals):
    """harness 的 EC 输出 → kFsStyle 的 34 个曲线 uniform（名称与引擎逐一对应）。"""
    v = {"on": vals["on"], "lo": vals["lo"], "hi": vals["hi"]}
    for ch in range(3):
        v[("cat", ch)] = vals[("cat", ch)]
        v[("cbat", ch)] = vals[("cbat", ch)]
        for k in range(1, 6):
            for pre in ("ctoe", "csh", "btoe", "bsh"):
                v[(f"{pre}{k}", ch)] = vals[(f"{pre}{k}", ch)]
        for pre in ("clin", "blin"):
            v[(pre + "1", ch)] = vals[(pre + "1", ch)]
            v[(pre + "2", ch)] = vals[(pre + "2", ch)]
    return U2.curve_uniforms(v)


# ─────────────────────────────────────────── LUT（真资产，同一份解析口径）
def load_lut(lut_id):
    d = open(os.path.join(LUT_DIR, lut_id + ".lut"), "rb").read()
    size = d[0]
    i = 2 + d[1]
    i += 1 + d[i]
    data = np.frombuffer(d[i:], dtype=np.uint8)
    assert len(data) == size ** 3 * 3, f"{lut_id}: {len(data)} != {size**3*3}"
    # 文件数组维度 [B][G][R]，R 最快变化 = texture3d 的 x 轴 ⇒ C-order 展平即上传布局
    return size, data.reshape(size, size, size, 3).astype(np.float32) / 255.0


def lut_tex(gl, size, lut):
    return gl.ctx.texture3d((size, size, size), 3,
                            np.ascontiguousarray(lut, dtype="f2").tobytes(), dtype="f2")


def lut_gray(lut, x):
    """灰阶输入 x 的 LUT 输出 —— 四面体插值在对角线上**精确**退化为线性插值。"""
    n = lut.shape[0]
    t = np.clip(x, 0, 1) * (n - 1)
    i0 = np.clip(np.floor(t).astype(np.int64), 0, n - 2)
    f = (t - i0)[..., None]
    d = np.stack([lut[k, k, k] for k in range(n)])
    return d[i0] * (1 - f) + d[i0 + 1] * f


# ─────────────────────────────────────────── 色度指标（CIELAB）
_M_RGB2XYZ = np.array([[0.4124564, 0.3575761, 0.1804375],
                       [0.2126729, 0.7151522, 0.0721750],
                       [0.0193339, 0.1191920, 0.9503041]])
_WHITE = np.array([0.95047, 1.0, 1.08883])


def srgb_to_lab(rgb):
    rgb = np.asarray(rgb, np.float64)
    lin = np.where(rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055) ** 2.4)
    xyz = (lin @ _M_RGB2XYZ.T) / _WHITE
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16.0 / 116.0)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]),
                     200 * (f[..., 1] - f[..., 2])], -1)


def chroma(rgb):
    lab = srgb_to_lab(rgb)
    return np.hypot(lab[..., 1], lab[..., 2])


CC_SRGB = np.array(FP.CC_NOMINAL_SRGB, np.float64) / 255.0
CC_COLOR = np.arange(18)        # 前 18 = 彩色块（调研的"18 色中位数"口径）
CC_NEUTRAL = np.arange(19, 23)  # neutral 8 / 6.5 / 5 / 3.5（中高调灰阶）


# ─────────────────────────────────────────── 两条链路的求值
ROWS = 16    # 去 dither 用：同一列在不同行拿到的确定性 dither 不同 ⇒ 取列内中位数


def ramp_img(xs=None, h=ROWS):
    """灰阶输入：列 j 的值 = xs[j]/255（三通道相同）⇒ 曲线测量仪。

    拟合阶段只传目标点的 xs（6/11 列）⇒ GL 光栅化 + 回读量小 20+ 倍；验收传 None = 全 256。
    """
    lv = np.asarray(range(256) if xs is None else xs, dtype=np.float32) / 255.0
    return np.ascontiguousarray(np.repeat(lv[None, :, None], h, 0).repeat(3, 2), np.float32)


def patch_img(patches, h=ROWS):
    return np.ascontiguousarray(np.repeat(np.asarray(patches, np.float32)[None, :, :], h, 0))


def style_uniforms(w, h, curve, contrast, sat, split, mono, lut_on, lut_strength, lut_size):
    u = U2.base_style_uniforms(w, h, curve=curve, sat=sat, contrast=contrast)
    u["uSplit"] = (float(split[0]), float(split[1]))
    u["uMono"] = float(mono)
    u["uLutOn"] = float(lut_on)
    u["uLutStrength"] = float(lut_strength)
    u["uLutSize"] = float(lut_size)
    return u


def out_uniforms(w, h, lut_on, lut_strength, lut_size):
    """kFsOut 恒等基线：空间变化项（暗角/颗粒/清晰度）与 [39] sharpen 全关。"""
    return {
        "uPx": (1.0 / w, 1.0 / h), "uFlipY": 0.0,
        "uGrainAmount": 0.0, "uGrainSeed": 0.0, "uGrainZoom": 1.0,
        "uGrainNorm": 1.0 / float(min(w, h)),
        "uClarity": 0.0, "uClarStep": (1.0 / w, 1.0 / h),
        "uHighlights": 0.0, "uShadows": 0.0, "uWhites": 0.0, "uBlacks": 0.0,
        "uFade": 0.0, "uToneOn": 0.0, "uZebra": 0.0, "uSharpen": 0.0,
        "uLutOn": float(lut_on), "uLutStrength": float(lut_strength),
        "uLutDomainMin": (0.0, 0.0, 0.0), "uLutDomainInv": (1.0, 1.0, 1.0),
        "uLutSize": float(lut_size),
        "uVigScaleXY": (w / float(max(w, h)), h / float(max(w, h))),
        "uVigDscale": 0.8, "uVigFscale": 0.5, "uVigBrightness": 0.0,
    }


def cached_gl_class(eso):
    """`eso.GL` 的**缓存版**：program / VAO 按 shader 源码缓存，不再每次求值重编译。

    ⚠️ 性能优化（R-1b）：引擎 shader 很大，`GL.run` 原本每次调用都
    `ctx.program(...)`（编译+链接）+ `prog.release()` + `vao` 建销 —— 实测 13ms/draw，
    而拟合一轮要几万次求值 ⇒ 这就是"每轮 10 分钟"的主因。缓存后语义**完全等价**
    （同一个 program、同一份 GLSL 源码、同样的 uniform 与 draw 调用）。
    """
    class _CachedGL(eso.GL):
        def __init__(self, w, h):
            super().__init__(w, h)
            self._progs = {}

        def run(self, fs_src, uniforms, textures, size=None):
            tw, th = size if size else (self.w, self.h)
            fbo = self._fbos.get((tw, th))
            if fbo is None:
                fbo = self._fbos[(tw, th)] = self.ctx.framebuffer(
                    color_attachments=[self.ctx.texture((tw, th), 4, dtype="f2")])
            fs = eso.to_core(fs_src)
            hit = self._progs.get(fs)
            if hit is None:
                prog = self.ctx.program(self.vs_src, fs)
                hit = self._progs[fs] = (prog, self.ctx.vertex_array(prog, []))
            prog, vao = hit
            fbo.use()
            self.ctx.viewport = (0, 0, tw, th)
            for i, (name, tex) in enumerate(textures.items()):
                tex.use(i)
                if name in prog:
                    prog[name].value = i
            for k, v in uniforms.items():
                if k in prog:
                    prog[k].value = v
            vao.render(eso.moderngl.TRIANGLES, vertices=3)
            return np.frombuffer(fbo.read(components=4, dtype="f2"),
                                 dtype="float16").reshape(th, tw, 4).astype(np.float32)

    return _CachedGL


class Rig:
    """GL 装置：复用 u2.Rig 的 shader 抽取与执行，LUT 纹理换成真资产。

    在 `eso.GL` 之上再省两笔固定开销（程序缓存 + **输入纹理复用**），语义不变。
    """

    def __init__(self, eso, w=256, h=8):
        self.gl = cached_gl_class(eso)(w, h)
        self.rig = U2.Rig(self.gl, open(FR, encoding="utf-8").read())
        self.lut_size = 0
        self._tex = {}

    def use_lut(self, size, lut):
        self.lut_size = size
        self.rig.lut = lut_tex(self.gl, size, lut)

    def _draw(self, name, img, uni):
        h, w = img.shape[:2]
        tex = self._tex.get((w, h))
        if tex is None:
            tex = self._tex[(w, h)] = self.gl.ctx.texture((w, h), 3, dtype="f4")
            tex.filter = (self.gl.ctx.NEAREST, self.gl.ctx.NEAREST)
            tex.repeat_x = tex.repeat_y = False
        tex.write(np.ascontiguousarray(img, dtype="f4").tobytes())
        out = self.gl.run(self.rig.sh[name], uni, {
            "uTex": tex, "uLut": self.rig.lut, "uGrainLut": self.rig.grain}, size=(w, h))
        # 引擎结尾有一行**无条件 dither**（`c += (dhash(gl_FragCoord.xy)+dhash(+37,17)-1)/255`）：
        # 逐像素确定、只依赖像素坐标 ⇒ 同一列在不同行的偏置不同（±1/255）。
        # 本工具里所有空间项（暗角/颗粒/清晰度）都是 0 ⇒ 列内真值恒定，取中位数即得无 dither 值。
        return np.median(out[:, :, :3], axis=0) if h > 1 else out[0, :, :3]

    def style(self, img, cu, contrast, sat, split, mono, lut_on, ls):
        h, w = img.shape[:2]
        return self._draw("kFsStyle", img, style_uniforms(
            w, h, cu, contrast, sat, split, mono, lut_on, ls, self.lut_size))

    def out(self, lin, lut_on, ls):
        h, w = lin.shape[:2]
        return self._draw("kFsOut", np.ascontiguousarray(lin.astype(np.float32)),
                          out_uniforms(w, h, lut_on, ls, self.lut_size))

    def close(self):
        self.gl.close()


# ─────────────────────────────────────────── 预设读取
def load_builtin():
    return json.load(open(BUILTIN, encoding="utf-8"))


def slots_of(rec):
    """预设 → 稀疏槽字典。未知语义名**直接报错**（与 Kotlin 解析器同口径）。"""
    out = {}
    for k, v in (rec.get("params") or {}).items():
        if k not in KEY2SLOT:
            raise SystemExit(f"[builtin.json] 未知参数名 {k!r} —— 与 FilmParamKeys 不同步？")
        out[KEY2SLOT[k]] = float(v)
    out[34] = float((rec.get("lut") or {}).get("strength", 1.0))
    return out


def pv(sl, slot):
    return float(sl.get(slot, IDENT[slot]))


def case_of(sl):
    g = lambda s: pv(sl, s)
    return (g(37), g(38), g(4), [g(5), g(6), g(7)], [g(8), g(9), g(10)])


# ─────────────────────────────────────────── 评估器
class Evaluator:
    def __init__(self, cxx, rig, rec, xs=None):
        self.cxx, self.rig, self.rec = cxx, rig, rec
        self.sl = slots_of(rec)
        # 采样列：None = 全 256（验收）；拟合传目标点 xs（性能）
        self.xs = list(range(256)) if xs is None else [int(v) for v in xs]
        self.xi = np.asarray(self.xs, dtype=np.int64)
        lid = (rec.get("lut") or {}).get("id")
        self.lut_size, self.lut = (0, None)
        if lid:
            self.lut_size, self.lut = load_lut(lid)
            rig.use_lut(self.lut_size, self.lut)

    def _slots(self, override):
        sl = dict(self.sl)
        sl.update(override or {})
        return sl

    def many(self, overrides, both=True, cc=True, tags=None):
        """批量求值：[override] → [{'sl','editor','camera','sat_gain','b_shift'}]"""
        self.cxx.no_cam = not both
        self.cxx.xs = self.xs
        sls = [self._slots(o) for o in overrides]
        tags = tags or [f"c{i}" for i in range(len(sls))]
        vals = self.cxx.run([(t,) + case_of(s) for t, s in zip(tags, sls)])
        out = []
        for t, sl in zip(tags, sls):
            ls = pv(sl, 34)
            lut_on = 1 if (self.lut is not None and ls > 0.0) else 0
            cu = ec_uniforms(vals[t]["vals"])
            sat = (pv(sl, 20), pv(sl, 21), pv(sl, 22))
            split = (pv(sl, 23), pv(sl, 24))
            ed = self.rig.style(ramp_img(self.xs), cu, pv(sl, 4), sat, split, pv(sl, 25),
                                lut_on, ls)
            cam = None
            if both:
                cam = self.rig.out(np.repeat(vals[t]["cam"][self.xi][None, :, :], ROWS, 0),
                                   lut_on, ls)
            sg, bs = None, None
            if cc:
                img_cc = self.rig.style(patch_img(CC_SRGB), cu, pv(sl, 4), sat, split,
                                        pv(sl, 25), lut_on, ls)
                cin, cout = chroma(CC_SRGB), chroma(img_cc)
                sg = float(np.median(cout[CC_COLOR] / np.maximum(cin[CC_COLOR], 1e-6)))
                bs = float(np.mean(srgb_to_lab(img_cc)[CC_NEUTRAL, 2]
                                   - srgb_to_lab(CC_SRGB)[CC_NEUTRAL, 2]))
            out.append(dict(sl=sl, editor=ed, camera=cam, sat_gain=sg, b_shift=bs,
                            ec=vals[t]["vals"]))
        return out

    def one(self, override=None, both=True):
        return self.many([override], both=both)[0]

    def cam_curve(self, override):
        """只要拍照链路曲线（fit 的 camera 模式用：省掉一次 kFsStyle）。"""
        self.cxx.no_cam = False
        self.cxx.xs = self.xs
        sl = self._slots(override)
        ls = pv(sl, 34)
        lut_on = 1 if (self.lut is not None and ls > 0.0) else 0
        v = self.cxx.run([("k",) + case_of(sl)])["k"]
        return self.rig.out(np.repeat(v["cam"][self.xi][None, :, :], ROWS, 0), lut_on, ls)


def luma_curve(arr, pts, xs=None):
    """实测输出（len(xs)×3，0..1）→ 目标点处的 luma（0..255）。

    `xs=None` 表示 arr 的列 = 全 256 采样（列索引即输入 v）；否则按 xs 位置索引。
    """
    a = np.asarray(arr, np.float64)
    if a.ndim == 1:
        a = np.repeat(a[:, None], 3, 1)
    if a.max() <= 1.5:
        a = a * 255.0
    lum = a @ LUMA
    if xs is None:
        return np.array([lum[int(x)] for x, _ in pts])
    pos = {int(v): i for i, v in enumerate(xs)}
    return np.array([lum[pos[int(x)]] for x, _ in pts])


def report_line(tag, got, want):
    d = got - want
    return (f"   {tag}" + "".join(f"{g:6.1f}/{w:5.1f}({dd:+5.1f})" for g, w, dd in zip(got, want, d))
            + f"   max|Δ| = {np.abs(d).max():5.2f}")


# ─────────────────────────────────────────── 拟合（坐标下降）
def _pack(p):
    """搜索向量 → 槽位覆盖。

    前 7 维（R-1 原有）：`[ls, contrast, lat_r, lat_g, lat_b, black_ev, white_ev]`；
    第 8/9 维（R-1b 新增，**只在目标带 b* 时启用**）：`[split_warm, split_cool]`。

    为什么需要 split：理光负片的目标 b* = −1.7（样片实测的**整体冷调**），而 [34] LUT 强度、
    filmic 曲线、分区饱和都只动亮度（灰阶上 sat 严格恒等、曲线逐通道同式）⇒ b* 在"LUT 强度 +
    曲线"的族里几乎不可达（R-1 实测停在 +0.11）。`[23][24]` 是引擎里唯一的**成对色向量**自由度，
    且 `kFsStyle:5493` 与 `kFsFilm:447` **逐字同式**：

        out += warm·ws·(0.05, 0.02, −0.04) + cool·wh·(−0.03, 0, 0.05)

    ⇒ 它是"把中性灰推冷"的正确旋钮，且在**两条链路同向生效**（工具只在编辑器链路上能测 b\*，
    但拍照链路用的是同一公式同方向的色向量，见报告 §缺口）。
    """
    ls, ctr, lr, lg, lb, bev, wev = p[:7]
    o = {34: ls, 4: ctr, 37: bev, 38: wev,
         5: lr, 6: lg, 7: lb, 8: lr, 9: lg, 10: lb}
    if len(p) > 7:
        o[23], o[24] = float(p[7]), float(p[8])
    return o


B_TOL = 0.15     # 色偏 b* 的约束带（超出即重罚）
B_PEN = 25.0
ED_TOL = 4.0     # 编辑器链路硬约束 = 调研 §5 的判据（BuiltinPresetCurveTest 就钉在这个容差上）
ED_PEN = 20.0    # 超差惩罚权重（远大于相机残差的 p-norm 量级 ⇒ 约束优先于目标）
# 注：曾试过对残差做截断（clip=6/255）"隔离结构性不可达点"，实测**反而更差**
#（camera-only 8.49 → 15.12：截断让"可达但暂未改善"的暗部点也被放弃）⇒ 不用截断，
# 结构性偏差靠报告显式标注，不靠代价函数掩盖。


def _cost(ev, overrides, pts, want, mode, bstar=None, bweight=0.5):
    """搜索代价：**约束优化**（R-1b 起 `both` 的语义）。

    为什么不是"两条残差加权和"：拍照链路的高光端有**硬天花板** —— `kFsOut` 的
    `highlightRolloff`（knee=0.80 + 指数肩）把线性显示域封在 `0.80+0.20·(1−1/e)=0.9264`
    ⇒ `srgbEncode` 后最高 **246.53/255**（实测 cam=1.0），而理光目标在输入 250 处要
    **251.9**。再叠上 LUT（`ricoh_negative` 灰阶对角线 250→234，高端是**压暗**的），
    偏差只会更大。⇒ "拍照链路 max|Δ| ≤ 4"在这套参数族里**不可达**（差 ≥5.4，可证明）。

    两条链路的**最优解互斥**（实测：M9 拍照 0.39 的解在编辑器上是 21.55；编辑器 5.59 的解在
    拍照上是 65.77 —— 因为 [37]black_ev 直接决定拍照链路动态范围 `dr = white_ev − black_ev`，
    编辑器最优把它压到 2.8 EV ⇒ 拍照暗部整段 clamp）。所以 `both` 的目标 = **两条链路一起
    最小化最大偏差**（残差拼接后取 p-norm），b* 用约束形式。
    """
    if mode == "camera":
        return [_pnorm(luma_curve(ev.cam_curve(o), pts, ev.xs) - want) for o in overrides]
    rep = ev.many(overrides, both=(mode == "both"), cc=(bstar is not None))
    out = []
    for r in rep:
        d = luma_curve(r["editor"], pts, ev.xs) - want
        if mode == "both":
            dc = luma_curve(r["camera"], pts, ev.xs) - want
            # **等权最小化最大偏差**（minimax）：两条链路的残差拼成一个向量。
            # 曾试过"editor ≤ 4 硬约束 + camera 最小化"，实测在 minimax 意义下更差
            #（理光 max 13.02 vs 拼接的 9.6）—— 因为约束一满足，搜索就放任 camera 变坏。
            c = _pnorm(np.concatenate([d, dc]))
        else:
            c = _pnorm(d)
        if bstar is not None and r["b_shift"] is not None:
            # b* 也用**约束**形式：偏差 ≤ B_TOL 视为达标，超出重罚（R-1b 起）
            c += B_PEN * max(0.0, abs(r["b_shift"] - bstar) - B_TOL)
        out.append(c)
    return out


LAT_GRID = np.array([0.01, 0.05, 0.10, 0.20, 0.32, 0.45, 0.60, 0.80, 1.0])
SPLIT_GRID = np.round(np.arange(-0.60, 0.601, 0.05), 3)
GRIDS = [
    ("lut_strength", np.round(np.arange(0.0, 1.001, 0.05), 3)),
    ("contrast",     np.round(np.arange(0.25, 3.001, 0.05), 3)),
    ("lat_r",        LAT_GRID),
    ("lat_g",        LAT_GRID),
    ("lat_b",        LAT_GRID),
    ("filmic_black_ev", np.round(np.arange(-12.0, -0.49, 0.25), 3)),
    ("filmic_white_ev", np.round(np.arange(0.0, 12.01, 0.25), 3)),
    ("split_warm",   SPLIT_GRID),
    ("split_cool",   SPLIT_GRID),
]

STARTS = [
    [1.00, 1.00, 0.01, 0.01, 0.01, -7.65, 4.56],
    [0.40, 1.20, 0.30, 0.30, 0.30, -7.65, 4.56],
    [0.30, 0.80, 0.42, 0.42, 0.42, -4.50, 2.00],
    [0.00, 1.00, 0.40, 0.40, 0.40, -3.70, 8.00],
    [0.60, 1.00, 0.50, 0.50, 0.50, -9.00, 5.00],
    [0.30, 1.40, 0.20, 0.20, 0.20, -8.00, 5.50],
    [0.50, 0.90, 0.60, 0.60, 0.60, -10.0, 6.00],
    [0.90, 1.10, 0.70, 0.70, 0.70, -12.0, 6.50],
    [0.35, 1.75, 0.25, 0.25, 0.25, -9.50, 3.50],
    [0.90, 1.45, 0.45, 0.45, 0.45, -13.0, 7.50],
]

SHORT_ROUNDS = 8      # 起点筛选的坐标下降轮数
LONG_ROUNDS = 30      # 精调的坐标下降轮数
TOP_K = 5             # 进入精调的起点数


def compass(ev, p0, pts, want, mode, rounds=LONG_ROUNDS, bstar=None, bweight=0.5,
            tol=0.02, verbose=False):
    """**批量坐标下降**（compass search）：每一维一次把 ±1/±2 步长的候选**整批**送进引擎。

    为什么换掉 Nelder-Mead：NM 是串行的（每步依赖上一步）⇒ 每次求值都要单独付一次
    `clang++` 产物的进程启动 + 每链路一次 GL draw（实测 both 4.3ms/次）。坐标下降一次评估
    一整维的候选（4 个），批量后摊薄到 ~1.6ms/次，**同样的预算能多搜 3–4 倍**。
    """
    n = len(p0)
    p = np.clip(np.asarray(p0, float), PBOUND[:n, 0], PBOUND[:n, 1])
    step = PSTEP[:n].copy()
    c = float(_cost(ev, [_pack(p)], pts, want, mode, bstar, bweight)[0])
    for _ in range(rounds):
        improved = False
        for i in range(n):
            cands = []
            for k in (-2.0, -1.0, 1.0, 2.0):
                q = p.copy()
                q[i] = min(max(p[i] + k * step[i], PBOUND[i, 0]), PBOUND[i, 1])
                cands.append(q)
            cs = _cost(ev, [_pack(q) for q in cands], pts, want, mode, bstar, bweight)
            j = int(np.argmin(cs))
            if cs[j] < c - 1e-9:
                p, c, improved = cands[j], float(cs[j]), True
        if not improved:
            step = step * 0.5
            if (step <= tol * PSTEP[:n]).all():
                break
    return p, c


def grid_sweep(ev, p, pts, want, mode, bstar, bweight, rounds=6):
    """收尾：逐维在固定网格上细扫（批量）—— 非光滑目标上比分形步长更稳。"""
    n = len(p)
    c = float(_cost(ev, [_pack(p)], pts, want, mode, bstar, bweight)[0])
    for _ in range(rounds):
        improved = False
        for i in range(n):
            vals = [float(v) for v in GRIDS[i][1] if abs(float(v) - p[i]) > 1e-9]
            if not vals:
                continue
            cs = _cost(ev, [_pack(np.concatenate([p[:i], [v], p[i + 1:]])) for v in vals],
                       pts, want, mode, bstar, bweight)
            j = int(np.argmin(cs))
            if cs[j] < c - 1e-9:
                p = np.concatenate([p[:i], [vals[j]], p[i + 1:]])
                c, improved = float(cs[j]), True
        if not improved:
            break
    return p, c


def fit_curve(ev, tgt, mode="editor", starts=None, rounds=6, verbose=True, probe_n=0):
    """三段式：① 批量随机探测（真 shader）→ ② 每个候选短坐标下降筛选 → ③ 最优几个长精调 + 细扫。

    R-1b 把 NM 换成批量坐标下降后，同样的时间预算可以多搜 3–4 倍 ⇒ 阶段数从 2 提到 3
    （探测样本 400 → 1500），且**质量优先**：不减少搜索，只提高单位时间内的求值次数。
    """
    pts = tgt["pts"]
    want = np.array([w for _, w in pts])
    dims = 9 if tgt.get("bstar") is not None else 7
    bstar = tgt.get("bstar")
    cands = [(list(x) + [0.0] * dims)[:dims] for x in (starts or STARTS)]
    if probe_n:
        rng = np.random.default_rng(7)
        P = np.stack([rng.uniform(PBOUND[i, 0], PBOUND[i, 1], probe_n) for i in range(dims)], 1)
        if dims > 7:      # split 的随机初值收窄：避免一上来就 ±0.6 的重色偏（离样片太远）
            P[:, 7] = rng.uniform(-0.30, 0.30, probe_n)
            P[:, 8] = rng.uniform(-0.30, 0.30, probe_n)
        cs = []
        for i in range(0, len(P), 300):
            cs += _cost(ev, [_pack(q) for q in P[i:i + 300]], pts, want, mode, bstar)
        for j in np.argsort(cs)[:8]:
            cands.append([float(v) for v in P[j]])
    scored = []
    for p0 in cands:
        p, c = compass(ev, p0, pts, want, mode, rounds=SHORT_ROUNDS, bstar=bstar)
        scored.append((c, p))
    scored.sort(key=lambda t: t[0])
    best_p, best_c = None, 1e9
    f = lambda q: float(_cost(ev, [_pack(list(q))], pts, want, mode, bstar)[0])
    for c0, p0 in scored[:TOP_K]:
        p, c = compass(ev, p0, pts, want, mode, rounds=LONG_ROUNDS, bstar=bstar)
        # 再用 Nelder-Mead 收一次：坐标下降沿轴走，遇到"斜沟"会 zigzag 停住，
        # 而 NM 的反射/扩张能斜着绕过去（实测 B&W editor 7.86 → 更优）。
        p2, c2 = nelder_mead(f, np.array(p, float), maxiter=500)
        if c2 < c:
            p, c = p2, c2
        p, c = grid_sweep(ev, p, pts, want, mode, bstar, bweight=0.5, rounds=rounds)
        p2, c2 = nelder_mead(f, np.array(p, float), maxiter=300)
        if c2 < c:
            p, c = p2, c2
        if c < best_c:
            best_p, best_c = [float(x) for x in p], float(c)
    r = ev.one(_pack(best_p), both=(mode != "editor"))
    ed = luma_curve(r["editor"], pts, ev.xs)
    cam = luma_curve(r["camera"], pts, ev.xs) if r["camera"] is not None else None
    if verbose:
        print(f"   \u25b8 拟合（mode={mode}）lut={best_p[0]:.3f} contrast={best_p[1]:.3f}"
              f" lat R/G/B={best_p[2]:.4f}/{best_p[3]:.4f}/{best_p[4]:.4f}"
              f" black_ev={best_p[5]:.3f} white_ev={best_p[6]:.3f}"
              + (f" split W/C={best_p[7]:+.3f}/{best_p[8]:+.3f}" if dims > 7 else ""))
        print(report_line("  编辑器 ", ed, want))
        if cam is not None:
            print(report_line("  拍照   ", cam, want))
        if r["b_shift"] is not None:
            print(f"  b*={r['b_shift']:+.2f}" + (f"（目标 {bstar:+.1f}）" if bstar is not None else ""))
    return best_p, dict(editor=ed, camera=cam, rep=r)


def fit_sat(ev, base_override, tgt):
    """曲线定完后独立拟合分区饱和（灰阶上 sat 恒等 ⇒ 与曲线互不干扰）。"""
    if tgt["sat"] is None:
        return None
    best, bc = None, 1e9
    for mid in np.arange(0.6, 2.01, 0.05):
        for sh in (0.7, 0.85, 1.0, 1.15):
            for hi in (0.7, 0.85, 1.0, 1.15):
                o = dict(base_override)
                o[20], o[21], o[22] = float(sh), float(mid), float(hi)
                r = ev.many([o], both=False)[0]
                c = abs(r["sat_gain"] - tgt["sat"])
                if tgt["bstar"] is not None:
                    c += 0.02 * abs(r["b_shift"] - tgt["bstar"])
                if c < bc:
                    best, bc = (float(sh), float(mid), float(hi), r["sat_gain"], r["b_shift"]), c
    return best


PBOUND = np.array([
    [0.0, 1.0],     # [34] lut_strength
    [0.2, 3.0],     # [4]  contrast（R-1b 放宽：editor-only 解撞过 0.5 下界）
    [0.0, 1.0],     # [5]  lat R
    [0.0, 1.0],     # [6]  lat G
    [0.0, 1.0],     # [7]  lat B
    [-12.0, -0.5],  # [37] filmic_black_ev（与 SettingsScreen 滑杆的 −12..−1 一致）
    [0.0, 12.0],    # [38] filmic_white_ev
    [-0.60, 0.60],  # [23] split_warm（仅 9 维模式）
    [-0.60, 0.60],  # [24] split_cool
])
# 判据 max|Δ| 对单纯形法不光滑 ⇒ 用 p-norm（max 的平滑近似）驱动搜索，
# 收敛后再用**真 max** 复核（报告里的数字一律是真 max）。
PNORM_P = 8


def _pnorm(d):
    d = np.abs(np.asarray(d, float))
    return float((d ** PNORM_P).sum() ** (1.0 / PNORM_P)) + 0.05 * float(d.mean())
PSTEP = np.array([0.15, 0.10, 0.10, 0.10, 0.10, 1.0, 0.8, 0.08, 0.08])


def _clamp_p(p):
    p = np.asarray(p, float)
    return np.clip(p, PBOUND[:len(p), 0], PBOUND[:len(p), 1])


def nelder_mead(f, x0, maxiter=600, tol=2e-4):
    """标准 Nelder-Mead（无依赖实现）；f 收 5 维向量，返回标量。"""
    n = len(x0)
    x0 = _clamp_p(x0)
    sim = [x0] + [_clamp_p(x0 + np.eye(n)[i] * PSTEP[i]) for i in range(n)]
    sim = [np.asarray(p, float) for p in sim]
    fs = [float(f(p)) for p in sim]
    for _ in range(maxiter):
        o = np.argsort(fs)
        sim = [sim[i] for i in o]
        fs = [fs[i] for i in o]
        if fs[-1] - fs[0] < tol:
            break
        cen = np.mean(sim[:-1], 0)
        xr = _clamp_p(cen + (cen - sim[-1]))
        fr = float(f(xr))
        if fr < fs[0]:
            xe = _clamp_p(cen + 2.0 * (cen - sim[-1]))
            fe = float(f(xe))
            sim[-1], fs[-1] = (xe, fe) if fe < fr else (xr, fr)
        elif fr < fs[-2]:
            sim[-1], fs[-1] = xr, fr
        else:
            xc = _clamp_p(cen + 0.5 * (sim[-1] - cen))
            fc = float(f(xc))
            if fc < fs[-1]:
                sim[-1], fs[-1] = xc, fc
            else:
                for i in range(1, n + 1):
                    sim[i] = _clamp_p(sim[0] + 0.5 * (sim[i] - sim[0]))
                    fs[i] = float(f(sim[i]))
    j = int(np.argmin(fs))
    return sim[j], fs[j]


def _around(v, lo, hi, frac):
    d = (hi - lo) * frac
    return [min(max(v + k * d, lo), hi) for k in (-2, -1, 0, 1, 2)]


def set_lut_min(v):
    """★ R-1c 裁决 1：把 `[34]` LUT 强度的**搜索下界**抬到 `v`（默认 0.30）。

    为什么不允许 0：`lut_strength = 0` 意味着这条预设只剩**影调**（曲线 + 对比度 + 饱和 +
    黑白点），而 M9 的色彩特征（红色浓、蓝绿偏青、品红倾向）是**色相选择性**的偏移 ——
    只有 3D LUT 能表达，`saturation` / `split` / `tint` / WB 增益都做不到（它们要么只动
    亮度、要么是全局/成对的色向量）。把 LUT 归零 ⇒ **预设名不副实**（用户点"徕卡一瞬"
    拿不到徕卡的色彩）。接受因此增加的影调残差（实测约 +1.5，见 r1c 报告的三档权衡表）。

    黑白那条 `lut.strength = 1.0` 本来就在下界之上 ⇒ 不受影响。

    改的三处（**只改下界，不动既有维度顺序/量程**）：
      * `PBOUND[0,0]` —— compass / NM / 随机探测的钳位界
      * `GRIDS[0]`    —— `grid_sweep` 的逐维网格（它不钳位，必须在这里剔除）
      * `R1_LEVELS[0]`/`STARTS` —— `--grid` 的候选池与起点（同样不钳位）
    """
    v = float(min(max(v, 0.0), 1.0))
    global STARTS
    PBOUND[0, 0] = v
    GRIDS[0] = ("lut_strength", np.round(np.arange(v, 1.001, 0.05), 3))
    R1_LEVELS[0] = [x for x in R1_LEVELS[0] if x >= v]
    STARTS = [[max(s[0], v)] + list(s[1:]) for s in STARTS]
    return v


R1_LEVELS = [
    [0.0, 0.35, 0.70, 1.00],                                  # [34] LUT 强度
    [0.80, 0.95, 1.10, 1.25, 1.40],                           # [4] 对比度
    [0.05, 0.20, 0.40, 0.60, 0.85],                           # [5..10] latitude（三通道同值）
    [-3.0, -4.5, -6.0, -7.65, -9.5, -12.0],                  # [37] filmic 黑点
    [1.0, 2.0, 3.5, 5.0, 6.5, 8.0],                           # [38] filmic 白点
]


def grid_fit(ev, tgt, mode="editor", rounds=3, verbose=True):
    """多分辨率网格搜索：判据 max|Δ| 非光滑 ⇒ 网格比单纯形法更可靠；末轮接一次 NM 精调。"""
    pts = tgt["pts"]
    want = np.array([w for _, w in pts])
    best, best_c = list(STARTS[1])[:2] + [0.3, 0.3, 0.3, -7.65, 4.56], 1e9
    for rnd in range(rounds):
        if rnd == 0:
            levels = R1_LEVELS
        else:
            levels = [_around(v, PBOUND[i, 0], PBOUND[i, 1], 0.06 if rnd == 1 else 0.02)
                      for i, v in enumerate(best[:5])]
        P = np.array(list(itertools.product(*levels)), float)
        P7 = np.column_stack([P[:, 0], P[:, 1], P[:, 2], P[:, 2], P[:, 2], P[:, 3], P[:, 4]])
        cs = []
        for i in range(0, len(P7), 500):
            cs += _cost(ev, [_pack(q) for q in P7[i:i + 500]], pts, want, mode)
        j = int(np.argmin(cs))
        if cs[j] < best_c:
            best, best_c = [float(x) for x in P[j]], float(cs[j])
        if verbose:
            print(f"   \u25b8 网格第 {rnd + 1} 轮：{len(P)} 组，最优 ls={best[0]:.3f}"
                  f" ctr={best[1]:.3f} lat={best[2]:.3f} bev={best[3]:.2f} wev={best[4]:.2f}"
                  f" cost={best_c:.3f}")
    # 末轮：per-channel latitude + NM 精调（三通道同值起步）
    p0 = np.array([best[0], best[1], best[2], best[2], best[2], best[3], best[4]])
    f = lambda p: float(_cost(ev, [_pack(list(p))], pts, want, mode)[0])
    p, c = nelder_mead(f, p0, maxiter=900)
    if c < best_c:
        best = [float(p[0]), float(p[1]), float(p[2]), float(p[5]), float(p[6])]
        pfull, best_c = [float(x) for x in p], c
    else:
        pfull = list(p0)
    r = ev.one(_pack(pfull), both=(mode != "editor"))
    ed = luma_curve(r["editor"], pts, ev.xs)
    cam = luma_curve(r["camera"], pts, ev.xs) if r["camera"] is not None else None
    if verbose:
        print(f"   \u25b8 拟合（grid, mode={mode}）lut={pfull[0]:.3f} contrast={pfull[1]:.3f}"
              f" lat R/G/B={pfull[2]:.4f}/{pfull[3]:.4f}/{pfull[4]:.4f}"
              f" black_ev={pfull[5]:.3f} white_ev={pfull[6]:.3f}")
        print(report_line("  编辑器 ", ed, want))
        if cam is not None:
            print(report_line("  拍照   ", cam, want))
    return pfull, dict(editor=ed, camera=cam, rep=r)


def probe(ev, tgt, n, mode, seed=0, topk=8):
    """随机采样 n 组曲线参数，报告最优 —— 判断"没达标是搜索问题还是表达能力问题"。"""
    rng = np.random.default_rng(seed)
    pts = tgt["pts"]
    want = np.array([w for _, w in pts])
    dims = 9 if tgt.get("bstar") is not None else 7
    P = np.stack([rng.uniform(PBOUND[i, 0], PBOUND[i, 1], n) for i in range(dims)], 1)
    P = np.vstack([P, np.array([(list(s) + [0.0] * dims)[:dims] for s in STARTS[:4]])])
    cs = []
    for i in range(0, len(P), 400):
        cs += _cost(ev, [_pack(q) for q in P[i:i + 400]], pts, want, mode)
    cs = np.array(cs)
    order = np.argsort(cs)[:topk]
    out = []
    for j in order:
        r = ev.one(_pack(P[j]), both=False)
        ed = luma_curve(r["editor"], pts, ev.xs)
        out.append((float(cs[j]), [round(float(x), 4) for x in P[j]],
                    [round(float(x), 2) for x in ed],
                    round(float(np.abs(ed - want).max()), 3)))
    return out


def _ec_json(vals):
    """EC 系数 → JSON 结构（Kotlin 单测按同一套 shader 公式求值）。"""
    out = {"on": vals["on"], "lo": list(vals["lo"]), "hi": list(vals["hi"]),
           "cat": [list(vals[("cat", c)]) for c in range(3)],
           "cbat": [list(vals[("cbat", c)]) for c in range(3)],
           "clin": [[vals[("clin1", c)], vals[("clin2", c)]] for c in range(3)],
           "blin": [[vals[("blin1", c)], vals[("blin2", c)]] for c in range(3)]}
    for pre in ("ctoe", "csh", "btoe", "bsh"):
        out[pre] = [[vals[(f"{pre}{k}", c)] for k in range(1, 6)] for c in range(3)]
    return out


# ─────────────────────────────────────────── 输出
def fmt(v):
    return repr(float(v))


def dump_builtin(recs, path=BUILTIN):
    lines = ["["]
    for i, r in enumerate(recs):
        lines.append("  {")
        head = ["schema", "id", "name", "group", "favorite", "source", "note"]
        for k in head:
            if k in r:
                lines.append(f"    {json.dumps(k)}: {json.dumps(r[k], ensure_ascii=False)},")
        lines.append('    "params": {')
        ps = list((r.get("params") or {}).items())
        for j, (k, v) in enumerate(ps):
            lines.append(f'      {json.dumps(k)}: {fmt(v)}' + ("," if j < len(ps) - 1 else ""))
        lines.append("    },")
        lut = r.get("lut")
        lines.append('    "lut": { "id": %s, "strength": %s }'
                     % (json.dumps(lut["id"]), fmt(lut["strength"])))
        lines.append("  }" + ("," if i < len(recs) - 1 else ""))
    lines.append("]")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


def export_curves(path, cxx, rig, recs):
    """导出 Kotlin 回归测试读的 `fit/builtin-curves.json` —— **读 builtin.json 的当前状态**。

    每次改完配方都要重跑（`BuiltinPresetCurveTest` 的第一层断言就是"配方与这份报告逐位一致"）。
    `editor` = 引擎 shader（kFsStyle）在目标点上的**实测**逐点值；`ec` = 引擎解出的曲线系数。
    """
    WHITE = (4, 5, 6, 7, 8, 9, 10, 20, 21, 22, 23, 24, 25, 37, 38)
    out = {"generated_by": "tools/preset-exp/recipe-fit.py --export", "presets": {}}
    for rec in recs:
        tgt = TARGETS.get(rec["id"])
        if tgt is None:
            continue
        pts = tgt["pts"]
        want = np.array([w for _, w in pts])
        ev = Evaluator(cxx, rig, rec, xs=[x for x, _ in pts])
        r = ev.one(both=True)
        sl = r["sl"]
        ed = luma_curve(r["editor"], pts, ev.xs)
        cam = luma_curve(r["camera"], pts, ev.xs)
        out["presets"][rec["id"]] = dict(
            title=tgt["title"], evidence=tgt["evidence"],
            target=[[int(x), float(y)] for x, y in pts],
            slots={str(s): float(pv(sl, s)) for s in WHITE},
            lut_id=(rec.get("lut") or {}).get("id"), lut_strength=float(pv(sl, 34)),
            ec=_ec_json(r["ec"]),
            editor=[round(float(v), 4) for v in ed],
            camera=[round(float(v), 4) for v in cam],
            max_editor=round(float(np.abs(ed - want).max()), 3),
            max_camera=round(float(np.abs(cam - want).max()), 3),
            sat_gain=(None if r["sat_gain"] is None else round(r["sat_gain"], 4)),
            b_shift=(None if r["b_shift"] is None else round(r["b_shift"], 3)),
        )
        print(report_line(f"  export·{tgt['short']:<15}", ed, want)
              + f"   [camera max|Δ| = {np.abs(cam - want).max():.2f}]")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"→ {path}")


def _dump_json(obj, path):
    d = os.path.dirname(os.path.abspath(path))
    if d:
        os.makedirs(d, exist_ok=True)
    json.dump(obj, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preset", default=None)
    ap.add_argument("--json", default=None, help="拟合报告落盘（每完成一条就写一次 ⇒ 可中断续跑）")
    ap.add_argument("--ckpt", default=None, help="断点续跑：读入已有 --json 报告，跳过已完成的预设")
    ap.add_argument("--export", default=None, help="导出 Kotlin 测试用的 fit/builtin-curves.json")
    ap.add_argument("--fit", action="store_true")
    ap.add_argument("--mode", default="editor", choices=["editor", "camera", "both"])
    ap.add_argument("--apply", action="store_true", help="把拟合结果写回 builtin.json")
    ap.add_argument("--probe", type=int, default=0, help="随机采样 N 组参数报告最优（诊断）")
    ap.add_argument("--probe-fit", type=int, default=400, help="拟合前随机采样挑起点的样本数")
    ap.add_argument("--grid", action="store_true", help="多分辨率网格搜索（默认关：用单纯形法）")
    ap.add_argument("--lut-min", type=float, default=0.30,
                    help="[34] LUT 强度的搜索下界（R-1c 裁决 1；默认 0.30，设 0 恢复 R-1b 行为）")
    a = ap.parse_args()
    lut_min = set_lut_min(a.lut_min)
    print(f"[lut-min] [34] LUT 强度下界 = {lut_min:.3f}"
          + ("（★ 裁决 1：不许把 LUT 归零 —— 色相选择性只有 3D LUT 能表达）" if lut_min > 0 else ""))

    eso = U2.load_eso()
    recs = [r for r in load_builtin()
            if a.preset is None or a.preset in r["id"] or a.preset in r.get("name", "")]
    if not recs:
        raise SystemExit("没有匹配的预设")

    done = {}
    if a.ckpt and os.path.isfile(a.ckpt):
        done = json.load(open(a.ckpt, encoding="utf-8"))
        print(f"[ckpt] 复用 {a.ckpt}：已完成 {sorted(done)}")

    with tempfile.TemporaryDirectory() as td:
        cxx = Cxx(td)
        rig = Rig(eso)
        try:
            if a.export:
                export_curves(a.export, cxx, rig, [r for r in load_builtin()
                                                   if a.preset is None or a.preset in r["id"]])
                return 0
            allrep = dict(done)
            for rec in recs:
                tgt = TARGETS.get(rec["id"])
                if tgt is None:
                    print(f"[skip] {rec['id']} 没有目标曲线")
                    continue
                pts = tgt["pts"]
                want = np.array([w for _, w in pts])
                # 拟合/验收都在目标点上采样（列 j 的输入 = pts[j][0]）
                ev = Evaluator(cxx, rig, rec, xs=[x for x, _ in pts])
                print(f"\n══ {rec['id']} · {tgt['title']}")
                print(f"   依据：{tgt['evidence']}")
                print(f"   输入      " + "".join(f"{x:>11}" for x, _ in pts))
                print(f"   目标      " + "".join(f"{w:>11.1f}" for w in want))
                if rec["id"] in done:
                    rep = done[rec["id"]]
                    if a.apply and "after" in rep:
                        rec["params"] = _merge_params(rec.get("params") or {},
                                                      rep["after"]["params"])
                        rec["lut"]["strength"] = float(rep["after"]["params"]["34"])
                    print("   （ckpt 命中，跳过拟合）")
                    continue
                r = ev.one(both=True)
                ed, cam = (luma_curve(r["editor"], pts, ev.xs),
                           luma_curve(r["camera"], pts, ev.xs))
                print(report_line("  before·编辑器", ed, want))
                print(report_line("  before·拍照  ", cam, want))
                rep = dict(lut_id=(rec.get("lut") or {}).get("id"), target=pts, before=dict(
                    editor=[round(float(x), 2) for x in ed],
                    camera=[round(float(x), 2) for x in cam],
                    max_editor=round(float(np.abs(ed - want).max()), 3),
                    max_camera=round(float(np.abs(cam - want).max()), 3),
                    sat_gain=round(r["sat_gain"], 4), b_shift=round(r["b_shift"], 3),
                    slots={str(k): round(float(v), 4) for k, v in sorted(r["sl"].items())}))
                if a.probe:
                    print(f"   ▸ 随机探测 {a.probe} 组（mode={a.mode}）—— top:")
                    for c, prm, cv, mx in probe(ev, tgt, a.probe, a.mode):
                        print(f"      cost={c:6.2f} max|Δ|={mx:5.2f}  ls={prm[0]:.3f} ctr={prm[1]:.3f}"
                              f" latR/G/B={prm[2]:.3f}/{prm[3]:.3f}/{prm[4]:.3f}"
                              f" bev={prm[5]:.2f} wev={prm[6]:.2f}  {cv}")
                if a.fit:
                    p, res = ((grid_fit if a.grid else fit_curve))(
                        ev, tgt, mode=a.mode, **({} if a.grid else {"probe_n": a.probe_fit}))
                    sat = fit_sat(ev, _pack(p), tgt)
                    after = {str(k): round(float(v), 4) for k, v in sorted(res["rep"]["sl"].items())}
                    if sat:
                        after["20"], after["21"], after["22"] = (
                            round(sat[0], 4), round(sat[1], 4), round(sat[2], 4))
                        print(f"   ▸ 饱和 sat_shadow/mid/high = {sat[0]:.2f}/{sat[1]:.2f}/{sat[2]:.2f}"
                              f" ⇒ 实测 {sat[3]:.4f}（目标 {tgt['sat']:.3f}）"
                              f"  b*={sat[4]:+.2f}" + (f"（目标 {tgt['bstar']:+.1f}）" if tgt["bstar"] is not None else ""))
                    rep["after"] = dict(
                        params=after, ec=_ec_json(res["rep"]["ec"]),
                        editor=[round(float(x), 2) for x in res["editor"]],
                        camera=([round(float(x), 2) for x in res["camera"]]
                                if res["camera"] is not None else None),
                        max_editor=round(float(np.abs(res["editor"] - want).max()), 3),
                        max_camera=(round(float(np.abs(res["camera"] - want).max()), 3)
                                    if res["camera"] is not None else None))
                    if a.apply:
                        rec["params"] = _merge_params(rec.get("params") or {}, after)
                        rec["lut"]["strength"] = float(after["34"])
                allrep[rec["id"]] = rep
                if a.json:      # 每完成一条就落盘 ⇒ 中断不丢已完成的结果（配合 --ckpt 续跑）
                    _dump_json(allrep, a.json)
                    print(f"   → 已落盘 {a.json}")
            if a.json:
                _dump_json(allrep, a.json)
                print(f"\n→ {a.json}")
            if a.apply:
                dump_builtin(load_builtin_with_overrides(recs))
                print(f"→ 已写回 {BUILTIN}")
        finally:
            rig.close()
    return 0


# 写回时只改本卡白名单内的槽（`[34]` 由 lut.strength 承载）—— 其余 params 原样保留
_SLOT2KEY = {}
for _k, _s in KEY2SLOT.items():
    _SLOT2KEY.setdefault(_s, _k)
_APPLY_KEYS = {s: _SLOT2KEY[s] for s in TUNABLE if s != 34}


def _merge_params(orig, after):
    """白名单槽按槽位升序写在前面，其余（grain_* / vignette_* …）逐字保留。"""
    out = {}
    for k in sorted(_APPLY_KEYS):
        v = after.get(str(k))
        if v is None:
            continue
        if abs(float(v) - float(IDENT[k])) < 1e-9:
            continue      # 等于恒等值 ⇒ 不进稀疏 params（与 presetSparseSlots 同口径）
        out[_APPLY_KEYS[k]] = float(v)
    for k, v in orig.items():
        if k not in _APPLY_KEYS.values():
            out[k] = v
    return out


def load_builtin_with_overrides(recs):
    """把拟合出来的 params/lut.strength 合并回**原始 JSON**（只动白名单键）。"""
    raw = {r["id"]: r for r in load_builtin()}
    for r in recs:
        if r["id"] in raw and "params" in r:
            raw[r["id"]]["params"] = r["params"]
            raw[r["id"]]["lut"]["strength"] = r["lut"]["strength"]
    return [raw[r["id"]] for r in load_builtin()]


if __name__ == "__main__":
    sys.exit(main())
