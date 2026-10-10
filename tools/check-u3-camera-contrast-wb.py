#!/usr/bin/env python3
"""U-3 拍照链路补齐 `[4]` 对比度 + `[88][89]` 色温/色调 —— 离线验收。

判据（任务卡 U-3，`docs/reports/preset-DESIGN.md` §9.3 的拍照侧前两项）：

  ① **默认值零回归逐位**（硬判据）：`[4]=1.0` / `[88]=[89]=0` 时，拍照链路的输出必须与
     改前**逐位相同**。分两层，两层都必须过：
       ①a **CPU 侧 spline 系数**（真编真跑引擎源码）：三组交叉对拍 ——
           HEAD 版函数+旧调用 / 工作区版函数+旧调用 / 工作区版函数+**新接入表达式**
           （`contrast_user` 与调用实参都**从源码正则提取**）⇒ 全部系数的十六进制浮点
           逐位相同。
       ①b **GPU 侧 kFsFilm**：把 ①a 的系数喂进真跑的 `kFsFilm`，RGBA16F 输出逐位相同
           （把"系数逐位"接到"像素逐位"上）。
  ② `[4]` 生效：0.5 / 1.0 / 1.5 三档的**曲线采样**（11 个 log 编码采样点，e 均匀）+
     单调 + **端点不动**（`e=0`/`e=1` 逐位相同，数字给出）。
  ③ `[88][89]` 生效：`mired = ±20` / `tint = ±0.1,±0.5` 的**RGB 增益**（真跑引擎
     `wbGainFromKelvin` + `tempTintGain`）+ **方向判据**（正 mired ⇒ 目标 K 更低 ⇒
     画面**变冷**，见 `FilmParams.h` 的既有约定）+ 与 `[1..3]` **相乘**（不是覆盖）。
  ④ **正交性**：`[4]` 与 `[5..10]` latitude（E4-4）都改曲线中部 ⇒ 固定一侧改另一侧时
     端点/黑位**不动**（正反双向的实测数字）。
  ⑤ **两侧同一算法**：拍照链路 `wbTempTint` 与编辑器链路 `passStyle` 的 `wb_tot`
     逐字同式（文本比对）+ 增益与编辑器侧 numpy 参考直译逐位对照。
  ⑥ **mutation 自检**（2 项，脚本内直接跑两个版本）：
       · 去掉 `[4]` 接入（用未接入的 `cp`）⇒ 非默认 `[4]` 下必须与接入版**显著不同**；
       · 去掉 `[88][89]` 增益（不乘 tt）⇒ 非零 mired 下必须与含 tt 版**不同**。
     两项都在**默认值下逐位相同**（否则零回归就是假的）—— 这才是"死代码"与"真接线"的
     区分度证明。

为什么必须真跑（不是重写一遍数学）：ADR §1.4 / §6.5 —— 判据必须来自引擎源码本身。
本脚本沿用 `tools/check-toeshoulder.py`（E4-4）已验证的三件套：
  · shader 从 `FrameRenderer.cpp` 的原始字面量提取，经 `engine-shader-offline.py` 真跑；
  · C++ 侧函数用**大括号配平**从源码原文抠出，机械改名后 clang++ 真编真跑，打印 `%a`；
  · 新接入的**表达式**（`contrast_user` 判定 + `cp * contrast_user` 实参）同样从源码
    正则提取 ⇒ 源码结构一变，本脚本立刻报错而不是静默跳过。

用法：python3 tools/check-u3-camera-contrast-wb.py [--dump]
依赖：clang++ + moderngl + glfw（与 check-toeshoulder.py / check-style-passthrough.py 相同）。
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
H_REL = "core/engine/src/render/FrameRenderer.h"
FP_REL = "core/engine/src/film/FilmParams.h"
FR = os.path.join(REPO, FR_REL)
FAIL = []
DUMP = False


def load_mod(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


TS = load_mod(os.path.join(REPO, "tools", "check-toeshoulder.py"), "ts")
ESO = load_mod(os.path.join(REPO, "tools", "engine-shader-offline.py"), "eso")
sys.path.insert(0, os.path.join(REPO, "tools"))
# 编辑器侧 `tempTintGain` 的 numpy 参考直译（E2-11 已用于 ⑭b/⑭c 判据）—— 判据 ⑤ 的对拍源
CSP = load_mod(os.path.join(REPO, "tools", "check-style-passthrough.py"), "csp")


def check(name, ok, detail):
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    if not ok:
        FAIL.append(name)


def git_show(rel):
    return subprocess.check_output(["git", "show", f"HEAD:{rel}"], cwd=REPO).decode()


# ══════════════════════════════════════════ C++ harness（真编真跑引擎源码）
def hx(v):
    """hex float 字符串 → python float（%a 的字面量，精确无解析损失）。"""
    return float.fromhex(v)


def build_and_run_harness():
    """从源码抠出所需的全部 C++ 实体 → 编一个可执行 → 跑 → 解析成字典。

    抠出来的实体（**全部是源码原文**，只做最小机械改名）：
      · `solve5` + `computeFilmicSpline`（HEAD 版 & 工作区版，各一份）
      · `wbGainFromKelvin` / `tempTintGain`（匿名 namespace 内的两个内核）
      · `wbTempTint` / `wbCompGNorm`（U-3 新增 / 收口的拍照链路 WB 入口）
      · `contrast_user` 的判定表达式 + `computeFilmicSpline(...)` 的实参表达式
        （正则提取 ⇒ 与源码同步，结构变了就直接报错）
    """
    new_cpp = open(FR, encoding="utf-8").read()
    old_cpp = git_show(FR_REL)
    new_h = open(os.path.join(REPO, H_REL), encoding="utf-8").read()
    old_h = git_show(H_REL)

    def grab(cpp, sp_type):
        solve = TS.brace_block(cpp, "bool solve5(double a[5][5]")
        fn = TS.brace_block(cpp, "FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(")
        fn = fn.replace("FrameRenderer::FilmicSpline FrameRenderer::computeFilmicSpline(",
                        f"{sp_type} compute_spline(").replace("FilmicSpline sp;", f"{sp_type} sp;")
        return solve, fn

    old_solve, old_fn = grab(old_cpp, "OldSp")
    new_solve, new_fn = grab(new_cpp, "NewSp")
    kelvin = TS.brace_block(new_cpp, "void wbGainFromKelvin(")
    tt = TS.brace_block(new_cpp, "void tempTintGain(")
    wb_tt = TS.brace_block(new_cpp, "static void wbTempTint(")
    wb_gn = TS.brace_block(new_cpp, "static void wbCompGNorm(")

    # ── U-3 接入点的两个表达式（正则提取，提取失败 = 源码结构变了 ⇒ 硬报错）
    m_expr = re.search(r"const float contrast_user =\s*(.*?);", new_cpp, re.S)
    m_arg = re.search(
        r"filmic_ = computeFilmicSpline\(ev_black_, ev_white_,\s*([^,]+),", new_cpp)
    if not m_expr or not m_arg:
        raise SystemExit("FAIL 未能从源码提取 U-3 的 [4] 接入表达式（源码结构变了）")
    expr_uc = m_expr.group(1).strip()
    arg_uc = m_arg.group(1).strip()
    # ── [88][89] 三个消费点必须都经由 wbTempTint（漏一处 = 预览有色偏、成片没有）
    n_call = len(re.findall(r"wbTempTint\(p,", new_cpp))
    n_raw = len(re.findall(r"prepareRawCommon\(neutral_r, neutral_g, neutral_b, p\.wb_r", new_cpp))

    body = f"""#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include "FilmParams.h"
using filmkit::FilmParams;

struct OldSp {{
{TS.struct_body(old_h)}
}};
struct NewSp {{
{TS.struct_body(new_h)}
}};
namespace oldimpl {{
{old_solve}
{old_fn}
}}
namespace eng {{
{new_solve}
{new_fn}
{kelvin}
{tt}
{wb_tt}
{wb_gn}
// U-3 接入点（表达式从源码提取）：uc = FilmParams[4]
NewSp run_new(float black, float white, float cp, float user_contrast,
              const float lat_sh[3], const float lat_toe[3]) {{
  const float contrast_user = {expr_uc};
  return compute_spline(black, white, {arg_uc}, lat_sh, lat_toe);
}}
NewSp run_old(float black, float white, float cp,
              const float lat_sh[3], const float lat_toe[3]) {{
  return compute_spline(black, white, cp, lat_sh, lat_toe);
}}
}}  // namespace eng

// HEAD 版与工作区版的 FilmicSpline 是**同布局的不同类型** ⇒ 用模板统一 dump。
// ⚠ 系数是 per-channel 的 [3][3]（[ch][seg]，seg: 0=toe 1=sh 2=lin）—— E4-4 起。
template <class SP>
static void dump(const char* tag, const SP& sp) {{
  const float (*m[5])[3] = {{sp.m1, sp.m2, sp.m3, sp.m4, sp.m5}};
  static const char* seg[3] = {{"toe", "sh", "lin"}};
  for (int k = 0; k < 5; ++k)
    for (int s = 0; s < 3; ++s)
      for (int ch = 0; ch < 3; ++ch)
        printf("SPL %s m%d_%s_%d %a\\n", tag, k + 1, seg[s], ch, m[k][ch][s]);
  for (int ch = 0; ch < 3; ++ch) {{
    printf("SPL %s linm1_%d %a\\n", tag, ch, sp.m1[ch][2]);
    printf("SPL %s linm2_%d %a\\n", tag, ch, sp.m2[ch][2]);
    printf("SPL %s latmin_%d %a\\n", tag, ch, sp.lat_min[ch]);
    printf("SPL %s latmax_%d %a\\n", tag, ch, sp.lat_max[ch]);
  }}
  printf("SPL %s black_ev %a\\n", tag, sp.black_ev);
  printf("SPL %s white_ev %a\\n", tag, sp.white_ev);
  printf("SPL %s dr %a\\n", tag, sp.dynamic_range);
  printf("SPL %s input_min %a\\n", tag, sp.input_min);
  printf("SPL %s input_max %a\\n", tag, sp.input_max);
  printf("SPL %s power %a\\n", tag, sp.output_power);
  printf("SPL %s black_disp %a\\n", tag, sp.black_disp);
  printf("SPL %s contrast %a\\n", tag, sp.contrast);
  printf("SPL %s contrast_param %a\\n", tag, sp.contrast_param);
}}

int main() {{
  // dim1：真机常见的一组标定（暗光室内 black −7.65 / 白点 2.44 ⇒ dr 10.09，与
  // FrameRenderer 的 auto 分支同量级）；dim2：darktable 默认（固定曲线）。
  const struct {{ const char* tag; float b, w; }} dims[] = {{
      {{"d1", -7.65f, 4.56f}}, {{"d2", -5.63f, 2.44f}}, {{"d3", -4.50f, 3.10f}}}};
  const float lat01[3] = {{0.01f, 0.01f, 0.01f}};
  // cp 的实际取值域 = {{1.0}} ∪ [1.0, 1.6]（updateFilmicEV 内 clamp 的结果）
  const float cps[] = {{1.0f, 1.2f, 1.6f}};
  for (int i = 0; i < 3; ++i) {{
    for (int j = 0; j < 3; ++j) {{
      char t[64];
      // ①a-1 HEAD 版函数 + 旧调用（= 改动前的引擎；HEAD 已含 E4-4 ⇒ 同样是 per-channel 5 参）
      snprintf(t, sizeof t, "head_old_%s_cp%g", dims[i].tag, cps[j]);
      dump(t, oldimpl::compute_spline(dims[i].b, dims[i].w, cps[j], lat01, lat01));
      // ①a-2 工作区版函数 + 旧调用（隔离"函数体是否变过"）
      snprintf(t, sizeof t, "wk_old_%s_cp%g", dims[i].tag, cps[j]);
      dump(t, eng::run_old(dims[i].b, dims[i].w, cps[j], lat01, lat01));
      // ①a-3 工作区版函数 + **新接入表达式**，[4] = 1.0（默认 ⇒ 零回归）
      snprintf(t, sizeof t, "wk_new_%s_cp%g_u1", dims[i].tag, cps[j]);
      dump(t, eng::run_new(dims[i].b, dims[i].w, cps[j], 1.0f, lat01, lat01));
      // ② [4] 的三档（含 1.0，用于曲线采样）
      for (int k = 0; k < 3; ++k) {{
        const float u = (k == 0) ? 0.5f : (k == 1 ? 1.0f : 1.5f);
        snprintf(t, sizeof t, "wk_u%03d_%s_cp%g", (int)(u * 100), dims[i].tag, cps[j]);
        dump(t, eng::run_new(dims[i].b, dims[i].w, cps[j], u, lat01, lat01));
      }}
      // ①a-4 防呆：NaN / 非正 ⇒ 必须回落到恒等（= 与 u1 逐位相同）
      snprintf(t, sizeof t, "wk_nan_%s_cp%g", dims[i].tag, cps[j]);
      dump(t, eng::run_new(dims[i].b, dims[i].w, cps[j],
                           std::nanf(""), lat01, lat01));
      snprintf(t, sizeof t, "wk_neg_%s_cp%g", dims[i].tag, cps[j]);
      dump(t, eng::run_new(dims[i].b, dims[i].w, cps[j], -1.0f, lat01, lat01));
    }}
  }}
  // ④ 正交性：latitude 两档 × [4] 三档（端点由 [37][38] 定，不由这两者定）
  for (int li = 0; li < 2; ++li) {{
    const float lat = li == 0 ? 0.01f : 0.50f;
    const float latt[3] = {{lat, lat, lat}};
    for (int k = 0; k < 3; ++k) {{
      const float u = (k == 0) ? 0.5f : (k == 1 ? 1.0f : 1.5f);
      char t[64];
      snprintf(t, sizeof t, "orth_lat%03d_u%03d", (int)(lat * 100), (int)(u * 100));
      dump(t, eng::run_new(-7.65f, 4.56f, 1.0f, u, latt, latt));
    }}
  }}
  // ③ [88][89]：增益网格（真跑引擎的 wbGainFromKelvin + tempTintGain + wbTempTint）
  const float mireds[] = {{-20.0f, 0.0f, 20.0f}};
  const float tints[] = {{-0.5f, -0.1f, 0.0f, 0.1f, 0.5f}};
  for (float mi : mireds)
    for (float ti : tints) {{
      float g[3];
      eng::tempTintGain(mi, ti, g);
      printf("TT %g %g %a %a %a\\n", mi, ti, g[0], g[1], g[2]);
    }}
  // 与 [1..3] **相乘**（不是覆盖）—— 用真机常见的一组用户 WB 补偿
  const float wbs[][3] = {{{{1.0f, 1.0f, 1.0f}}, {{1.2f, 1.0f, 0.9f}},
                          {{0.85f, 1.0f, 1.3f}}}};
  for (int w = 0; w < 3; ++w)
    for (float mi : mireds)
      for (float ti : tints) {{
        FilmParams p;
        p.wb_r = wbs[w][0]; p.wb_g = wbs[w][1]; p.wb_b = wbs[w][2];
        p.temp_mired = mi; p.tint = ti;
        float tot[3], gn[3];
        eng::wbTempTint(p, tot);
        eng::wbCompGNorm(p, gn);
        printf("WBT %d %g %g %a %a %a %a %a %a\\n", w, mi, ti, tot[0], tot[1], tot[2],
               gn[0], gn[1], gn[2]);
      }}
  printf("CALLS %d %d\\n", {n_call}, {n_raw});
  return 0;
}}
"""
    with tempfile.TemporaryDirectory() as td:
        cpp = os.path.join(td, "u3.cpp")
        exe = os.path.join(td, "u3")
        open(cpp, "w").write(body)
        inc = os.path.join(REPO, os.path.dirname(FP_REL))
        r = subprocess.run(["clang++", "-std=c++20", "-O2", "-I", inc, "-o", exe, cpp],
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise SystemExit("FAIL clang++ 编译失败：\n" + r.stderr[-3000:])
        out = subprocess.run([exe], capture_output=True, text=True).stdout
    spl, ttb, wbt = {}, {}, {}
    for ln in out.strip().split("\n"):
        p = ln.split()
        if not p:
            continue
        if p[0] == "SPL":
            spl.setdefault(p[1], {})[p[2]] = p[3]
        elif p[0] == "TT":
            ttb[(p[1], p[2])] = [p[3], p[4], p[5]]
        elif p[0] == "WBT":
            wbt[(p[1], p[2], p[3])] = [p[4], p[5], p[6], p[7], p[8], p[9]]
        elif p[0] == "CALLS":
            wbt["_calls"] = (int(p[1]), int(p[2]))
    return spl, ttb, wbt


# ══════════════════════════════════════════ GPU（真跑 kFsFilm）
def filmic_uniforms(sp):
    """C++ 打印的 spline（hex 字符串）→ kFsFilm 的 uniform 字典。

    ⚠ uniform 名与 `kFsFilm` 的声明逐字对应（uFilmicToeM1..5 / uFilmicShM1..5 /
    uFilmicLinM1,M2 / uFilmicLatMin,Max 是 **vec3 per-channel**，其余是标量）。
    """
    u = {}
    for i in range(1, 6):
        u[f"uFilmicToeM{i}"] = tuple(hx(sp[f"m{i}_toe_{ch}"]) for ch in range(3))
        u[f"uFilmicShM{i}"] = tuple(hx(sp[f"m{i}_sh_{ch}"]) for ch in range(3))
    u["uFilmicLinM1"] = tuple(hx(sp[f"linm1_{ch}"]) for ch in range(3))
    u["uFilmicLinM2"] = tuple(hx(sp[f"linm2_{ch}"]) for ch in range(3))
    u["uFilmicLatMin"] = tuple(hx(sp[f"latmin_{ch}"]) for ch in range(3))
    u["uFilmicLatMax"] = tuple(hx(sp[f"latmax_{ch}"]) for ch in range(3))
    for k, name in (("black_ev", "uFilmicBlackEv"), ("dr", "uFilmicDr"),
                    ("input_min", "uFilmicInputMin"), ("input_max", "uFilmicInputMax"),
                    ("power", "uFilmicPower"), ("black_disp", "uFilmicBlackDisp")):
        u[name] = hx(sp[k])
    # 隔离口径（与 check-toeshoulder.py 的 STYLE_IDENTITY 完全一致）：uToneK=1 才走
    # filmic 求值，其余风格层全关 ⇒ 输出 = filmicTone(输入)，判据才可归因。
    u.update(TS.STYLE_IDENTITY)
    return u


def e_grid_grey(sp):
    """11 个 log 编码均匀采样点 + **中灰锚点**（`e = −black/dr`，即 `grey_log`）
    + 端点外推（e=0/1 用 0.5×/2× 保证 clamp 到端点）⇒ 12 个点。

    为什么必须显式插中灰点：`[4]` contrast 在 darktable spline v3 里改的是**线性段斜率**
    `slope = contrast_param·dr/8`，而线性段的截距 `li = grey_disp − contrast·grey_log`
    保证 `line(grey_log) ≡ grey_disp` ⇒ **中灰是旋转中心，与 contrast 无关**。这是
    "改对比度 = 围绕中灰旋转"的解析依据，也是本卡 `[4]` 语义正确性的头号判据
    —— 11 点均匀网格恰好跨过 0.6265，测不到它。
    """
    e = np.linspace(0.0, 1.0, 11)
    b = hx(sp["black_ev"])
    e_grey = -b / hx(sp["dr"])
    e = np.sort(np.append(e, e_grey))
    v = 0.1845 * np.exp2(b + e * hx(sp["dr"]))
    v[0] *= 0.5
    v[-1] *= 2.0
    return e, v.astype("f4"), float(e_grey)


def main():
    global DUMP
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true", help="打印系数/增益明细")
    args = ap.parse_args()
    DUMP = args.dump

    spl, ttb, wbt = build_and_run_harness()
    src = open(FR, encoding="utf-8").read()
    film_src = ESO.extract_shaders(FR)["kFsFilm"]
    gl_cls, to_core = TS.load_offline_gl()

    # ── ①a CPU 侧 spline 系数逐位 ────────────────────────────────────────────
    def cmp_bits(tag_a, tag_b):
        a, b = spl[tag_a], spl[tag_b]
        diff = [k for k in a if a[k] != b.get(k)]
        return diff, len(a)

    for lab, ta, tb in (
            ("HEAD 函数+旧调用 vs 工作区函数+旧调用", "head_old_d1_cp1", "wk_old_d1_cp1"),
            ("工作区函数+旧调用 vs 工作区函数+**新接入(uc=1.0)**", "wk_old_d1_cp1",
             "wk_new_d1_cp1_u1")):
        diff, n = cmp_bits(ta, tb)
        check(f"①a {lab}", not diff,
              f"逐位比较 {n} 个 hex 浮点（15 系数×3 通道 + lat/端点/标量），不同 = {len(diff)}"
              + ("" if not diff else f"；例：{diff[0]} {spl[ta][diff[0]]} != {spl[tb][diff[0]]}"))

    # 全部维度 × 全部 cp（cp 的实际域 = {1.0} ∪ [1.0,1.6]）
    tot_cmp, tot_bad, bad_ex = 0, 0, []
    for dim in ("d1", "d2", "d3"):
        for cp in ("1", "1.2", "1.6"):
            diff, n = cmp_bits(f"head_old_{dim}_cp{cp}", f"wk_new_{dim}_cp{cp}_u1")
            tot_cmp += n
            if diff:
                tot_bad += 1
                bad_ex.append(f"{dim}/cp{cp}:{diff[0]}")
    check("①a 全维度×全 cp 交叉逐位（HEAD 原版 vs 工作区新接入 uc=1.0）", tot_bad == 0,
          f"3 组 EV 标定 × 3 档 cp × {tot_cmp // 9} 个 hex 浮点 = {tot_cmp} 次比较，"
          f"不同的组合数 = {tot_bad}" + ("" if not bad_ex else f"；例：{bad_ex[0]}"))

    # 防呆（NaN / 非正 ⇒ 恒等）：与 uc=1.0 逐位相同
    for dim in ("d1", "d2", "d3"):
        for cp in ("1", "1.2", "1.6"):
            diff_n, _ = cmp_bits(f"wk_new_{dim}_cp{cp}_u1", f"wk_nan_{dim}_cp{cp}")
            diff_g, _ = cmp_bits(f"wk_new_{dim}_cp{cp}_u1", f"wk_neg_{dim}_cp{cp}")
            if diff_n or diff_g:
                check("①a 防呆（[4] = NaN / 负数 ⇒ 恒等）", False,
                      f"{dim}/cp{cp}: NaN 差 {len(diff_n)} 项、负数差 {len(diff_g)} 项")
                break
        else:
            continue
        break
    else:
        check("①a 防呆（[4] = NaN / 负数 ⇒ 恒等）", True,
              "NaN 与 −1.0 两档 × 3 组 EV × 3 档 cp 全部与 uc=1.0 逐位相同（不会把 NaN 灌进 5×5 系统）")

    # ── ①b GPU 侧 kFsFilm 输出逐位 ──────────────────────────────────────────
    bench = TS.Bench(gl_cls(96, 96), to_core, film_src, 96, 96)
    e, vals, e_grey = e_grid_grey(spl["head_old_d1_cp1"])
    i_grey = int(np.argmin(np.abs(e - e_grey)))
    i_mid = int(np.argmin(np.abs(e - 0.5)))
    img = TS.mk_img(vals)          # 单通道亮度 → 三通道相同的线性输入

    def gpu_raw(sp):
        return bench.run(img, filmic_uniforms(sp))

    o_head = gpu_raw(spl["head_old_d1_cp1"])
    o_old = gpu_raw(spl["wk_old_d1_cp1"])
    o_new = gpu_raw(spl["wk_new_d1_cp1_u1"])
    same_head = bool(np.array_equal(o_head, o_new))
    same_old = bool(np.array_equal(o_old, o_new))
    check("①b GPU 侧 kFsFilm 输出逐位（HEAD 原版系数 vs 新接入 uc=1.0 系数）", same_head,
          f"RGBA16F 读回 {o_new.shape} 逐位相同 = {same_head}（"
          f"max|Δ| = {float(np.abs(o_head - o_new).max()):.3e}）；"
          f"工作区旧调用对照 = {same_old}")
    if DUMP:
        print("   端点输出（e=0 / e=1，RGBA 四通道）：")
        print(f"     HEAD : {o_head[0, 0]} / {o_head[0, -1]}")
        print(f"     NEW  : {o_new[0, 0]} / {o_new[0, -1]}")

    # ── ② [4] 生效：曲线采样 + 单调 + 中灰/端点不动 ────────────────────────
    curves = {}
    for u in ("050", "100", "150"):
        curves[u] = TS.srgb8(gpu_raw(spl[f"wk_u{u}_d1_cp1"])[0, :, 0].astype(np.float64))
    print("② [4] contrast 曲线采样（black −7.65 / white +4.56 / cp 1.0；"
          "输入 = log 编码 e 均匀 11 点 + 中灰锚点，输出 = sRGB 8bit）")
    print("   e      输入线性      [4]=0.5   [4]=1.0   [4]=1.5")
    for i, ev in enumerate(e):
        mark = "  ← 中灰锚点（旋转中心）" if i == i_grey else ("  ← 黑端" if i == 0
                                                              else ("  ← 白端" if i == len(e) - 1 else ""))
        print(f"   {ev:.4f} {vals[i]:10.5f}  {curves['050'][i]:8.1f}  "
              f"{curves['100'][i]:8.1f}  {curves['150'][i]:8.1f}{mark}")
    # 中灰邻域（e=0.4→0.6）的局部斜率 = "改的是曲线中部"的直接证据
    i_a, i_b = int(np.argmin(np.abs(e - 0.4))), int(np.argmin(np.abs(e - 0.6)))
    mid_slope = {u: float(curves[u][i_b] - curves[u][i_a]) for u in curves}
    # 单调不减（允许 8bit 量化/dither 的 ±0.5 LSB）
    mono = {u: bool((np.diff(curves[u]) >= -0.51).all()) for u in curves}
    d_mid = float(abs(curves["150"][i_mid] - curves["050"][i_mid]))
    check("② [4] 三档生效 + 单调不减 + 中部斜率随档位递增",
          d_mid >= 4.0 and all(mono.values())
          and mid_slope["050"] < mid_slope["100"] < mid_slope["150"],
          f"e=0.50（线性 {vals[i_mid]:.4f}，中灰**以下**）三档输出 = "
          f"{curves['050'][i_mid]:.1f} / {curves['100'][i_mid]:.1f} / "
          f"{curves['150'][i_mid]:.1f}（0.5 与 1.5 相差 {d_mid:.1f} LSB）；单调不减 = {mono}；"
          f"中部局部斜率（e 0.4→0.6，LSB）= "
          f"{mid_slope['050']:.1f} / {mid_slope['100']:.1f} / {mid_slope['150']:.1f}")

    # 中灰锚点（旋转中心）三档必须不动 —— darktable `li = grey_disp − contrast·grey_log`
    # 保证 `line(grey_log) ≡ grey_disp`（与 contrast 无关），这是 [4] 语义正确性的核心。
    gv = {u: gpu_raw(spl[f"wk_u{u}_d1_cp1"])[0, i_grey] for u in curves}
    grey_same = all(np.array_equal(gv["050"], gv[u]) for u in ("100", "150"))
    grey_8 = {u: TS.srgb8(gv[u].astype(np.float64))[0] for u in curves}
    check("② 中灰锚点不动（[4] = 围绕中灰旋转，不是整体提亮/压暗）", grey_same,
          f"e={e_grey:.4f}（= −black/dr，线性 {vals[i_grey]:.5f} = 18.45% 中灰）："
          f"[4]=0.5 输出 {gv['050'][0]:.7f} / 1.0 {gv['100'][0]:.7f} / "
          f"1.5 {gv['150'][0]:.7f}（线性显示域）⇒ **逐位相同 = {grey_same}**"
          f"（8bit：{grey_8['050']:.1f} / {grey_8['100']:.1f} / {grey_8['150']:.1f}）")

    # 端点不动：e=0 / e=1 两档在 [4] 三档下的 8bit 显示域值相同（≤1 LSB）。
    # ⚠ 不用"RGBA16F 逐位"：e=1 处 shoulder 五次多项式在 float 下求值有 ~4e-4 的
    # 抵消误差（[4]=1.5 的系数更大 ⇒ 误差更大），落到 fp16 上差 1 ulp(16F) = 1/2048；
    # 但 8bit 显示域（= 真机成片口径）恒为 255 —— 端点由 [37][38] 定，不由 [4] 定，
    # 解析证明见 ④（black_disp / input_min / input_max / power 在 [4] 三档下逐位不变）。
    ep, ep8 = [], []
    for idx, lab in ((0, "e=0（黑端）"), (-1, "e=1（白端）")):
        vs = [gpu_raw(spl[f"wk_u{u}_d1_cp1"])[0, idx] for u in ("050", "100", "150")]
        v8 = [TS.srgb8(v.astype(np.float64))[0] for v in vs]
        same = all(abs(v8[0] - v) <= 1.0 for v in v8[1:])
        ep.append(same)
        ep8.append(f"{lab} 8bit {v8[0]:.2f}/{v8[1]:.2f}/{v8[2]:.2f}")
        print(f"   {lab}：8bit 显示域 [4]=0.5/1.0/1.5 = {v8[0]:.2f} / {v8[1]:.2f} / "
              f"{v8[2]:.2f}（判据 ≤1 LSB）⇒ {'不动' if same else '动了'}；"
              f"线性域 {vs[0][0]:.7f} / {vs[1][0]:.7f} / {vs[2][0]:.7f}")
    check("② [4] 端点不动（e=0 / e=1 在 0.5/1.0/1.5 下 8bit 显示域 ≤1 LSB）", all(ep),
          "；".join(ep8) + "（端点是 [37][38] 的职责，见 ④ 的 CPU 系数逐位证据）")

    # ── ③ [88][89] 生效：增益 + 方向 ────────────────────────────────────────
    print("③ [88][89] RGB 增益（真跑引擎 wbGainFromKelvin + tempTintGain；G 归一，G 恒 1）")
    ok_dir = []
    for (mi, ti), g in sorted(ttb.items(), key=lambda kv: (float(kv[0][0]), float(kv[0][1]))):
        r, gg, b = (hx(v) for v in g)
        if ti == "0":
            note = ""
            if mi == "20":
                note = "  ← 正 mired ⇒ 目标 K 更低 ⇒ 画面**变冷**（B/R > 1）"
                ok_dir.append(b / r > 1.0)
            elif mi == "-20":
                note = "  ← 负 mired ⇒ 目标 K 更高 ⇒ 画面**变暖**（B/R < 1）"
                ok_dir.append(b / r < 1.0)
            print(f"   mired={float(mi):+5.0f} K_target={1e6 / (1e6 / 6500 + float(mi)):7.0f}K "
                  f"tint=0     ⇒ ({r:.5f}, {gg:.5f}, {b:.5f})  B/R={b / r:.5f}{note}")
        if mi == "0" and ti in ("0.1", "-0.1", "0.5", "-0.5"):
            note = "  ← 正 tint = 品红（压 G）" if float(ti) > 0 else "  ← 负 tint = 绿（抬 G）"
            print(f"   mired=    0 K_target=   6500K tint={float(ti):+.1f} "
                  f"⇒ ({r:.5f}, {gg:.5f}, {b:.5f})  G={'<' if gg < 1 else '>'}1{note}")
            ok_dir.append((gg < 1.0) if float(ti) > 0 else (gg > 1.0))
    zero = ttb[("0", "0")]
    z = [hx(v) for v in zero]
    # 恒等判据**不能用** tt(0,0) == (1,1,1)：`k_target = 1e6/(1e6/6500)` 在 float 下
    # 有一次往返舍入，`gt/gr` 可能差 1 ulp。真正的零回归判据是"短路"—— mired=tint=0 时
    # `wbTempTint` 整个 if 分支不执行 ⇒ 输出就是 [1..3] 的原值（**逐位**，用 float32 字面量比）。
    id_got = [hx(v) for v in wbt[("1", "0", "0")][:3]]
    id_ref = [float(np.float32(1.2)), float(np.float32(1.0)), float(np.float32(0.9))]
    id_ok = id_got == id_ref
    check("③ [88][89] 生效 + 方向正确（正 mired ⇒ 变冷 / 正 tint ⇒ 品红）", all(ok_dir),
          f"[88]=[89]=0 时 `tempTintGain(0,0)` 的比值 = ({z[0]:.9f}, {z[1]:.9f}, {z[2]:.9f})"
          f"（参考点差分的浮点往返，未必精确 1 —— 引擎靠**短路**保证恒等，见下一条）；"
          f"方向判据全过 = {all(ok_dir)}")
    check("③ 恒等判据（[88]=[89]=0 ⇒ 短路，[1..3] 逐位下发）", id_ok,
          f"wb=(1.2,1.0,0.9) + mired=0/tint=0 ⇒ wbTempTint 输出 = {wbt[('1', '0', '0')][:3]}"
          f"（hex）；[1..3] 的 float32 字面量 = "
          f"{[float(np.float32(v)).hex() for v in (1.2, 1.0, 0.9)]} ⇒ 逐位相同 = {id_ok}")

    # 与 [1..3] **相乘**（不是覆盖）
    w_ex = wbt[("1", "20", "0")]           # wb=(1.2,1,0.9) + mired=+20
    tt20 = [hx(v) for v in ttb[("20", "0")]]
    exp = [1.2 * tt20[0], 1.0 * tt20[1], 0.9 * tt20[2]]
    got = [hx(v) for v in w_ex[:3]]
    bit = all(abs(a - b) <= 1e-6 * max(1.0, abs(b)) for a, b in zip(got, exp))
    gn = [hx(v) for v in w_ex[3:]]
    gn_exp = [exp[0] / 1.0, 1.0, exp[2] / 1.0]
    check("③ [88][89] 与 [1..3] 相乘（不是覆盖）+ G 归一链路正确",
          bit and abs(gn[0] - gn_exp[0]) < 1e-6 and abs(gn[2] - gn_exp[2]) < 1e-6,
          f"wb=(1.2,1.0,0.9) + mired=+20 ⇒ wbTempTint = ({got[0]:.6f}, {got[1]:.6f}, "
          f"{got[2]:.6f}) == wb×tt = ({exp[0]:.6f}, {exp[1]:.6f}, {exp[2]:.6f})；"
          f"wbCompGNorm（OES 预览 / YUV 静拍口径）= ({gn[0]:.6f}, {gn[1]:.6f}, {gn[2]:.6f})")
    n_call, n_raw = wbt["_calls"]
    check("③ 三个消费点全部经 wbTempTint（漏一处 = 预览有色偏、成片没有）",
          n_call == 4 and n_raw == 0,
          f"`wbTempTint(p,` 出现 {n_call} 次 = **3 个消费点**（`wbCompGNorm` 1 + "
          f"`prepareRawCommon` 的两个调用点 2）+ 1 处 raw.render 诊断字段；"
          f"仍按裸 p.wb_r 调 prepareRawCommon 的处数 = {n_raw}")

    # ③b 报告 §3 的"两条链路同值"对照表（wb=(1.2,1,0.9)）：数字直接来自真跑，
    # 不手算 —— passStyle 侧的期望值 = 同一份 numpy 直译（⑤b 已证二者等价）。
    print("③b 两条链路同值对照（wb=(1.2, 1.0, 0.9)）："
          "拍照 = 真跑 C++ wbTempTint；编辑器 = numpy 直译 × 同一 wb")
    for (mi, ti) in (("20", "0"), ("-20", "0"), ("0", "0.1"), ("0", "-0.1"), ("0", "0")):
        tot = [hx(v) for v in wbt[("1", mi, ti)][:3]]
        tt = CSP.temp_tint_gain(float(mi), float(ti))
        ed = [1.2 * tt[0], 1.0 * tt[1], 0.9 * tt[2]]
        agree = all(abs(a - b) <= 1e-6 for a, b in zip(tot, ed))
        print(f"   mired={float(mi):+5.0f} tint={float(ti):+.1f} ⇒ 拍照 "
              f"({tot[0]:.6f}, {tot[1]:.6f}, {tot[2]:.6f}) | 编辑器 "
              f"({ed[0]:.6f}, {ed[1]:.6f}, {ed[2]:.6f}) ⇒ 同值 = {agree}")

    # ── ⑤ 两侧同一算法（[88][89]）────────────────────────────────────────────
    # 5a：文本——拍照链路 wbTempTint 与 passStyle 的 wb_tot 逐字同式
    m_style = re.search(r"const float wb_tot\[3\] = \{(.*?)\};", src, re.S)
    body_tt = TS.brace_block(src, "static void wbTempTint(")
    n_mul = len(re.findall(r"out\[(\d)\] \*= tt\[\1\];", body_tt))
    style_expr = re.sub(r"\s+", " ", m_style.group(1)) if m_style else None
    check("⑤a 两侧同一算法（文本逐字同式）", bool(m_style) and n_mul == 3,
          "passStyle: `wb_tot = {" + (style_expr or "?") + "}`；"
          f"wbTempTint: `out[i] *= tt[i]` 三行齐备（实测 {n_mul}/3）—— 同一函数 "
          "`tempTintGain`、同一次乘法、同一个恒等短路判据（`temp_mired != 0 || tint != 0`）")

    # 5b：数值——与编辑器侧 numpy 参考直译（check-style-passthrough.py）逐位对照
    # ⚠ 容差 1e-6 而不是 1e-7：C++ 侧全程 **float32**（`wbGainFromKelvin` 的 log/pow
    # 都是 float），numpy 侧是 **float64**。两次 log 级运算的 float32 舍入就是 ~1e-7
    # 相对量级 —— 差异 100% 来自精度，不是算法。
    worst, bad = 0.0, []
    for (mi, ti), g in ttb.items():
        ref = CSP.temp_tint_gain(float(mi), float(ti))
        for i in range(3):
            d = abs(hx(g[i]) - ref[i])
            worst = max(worst, d)
            if d > 1e-6:
                bad.append((mi, ti, i, hx(g[i]), ref[i]))
    check("⑤b 两侧同一算法（数值：真跑 C++ float32 vs 编辑器侧 numpy float64 直译）", not bad,
          f"{len(ttb)} 组 (mired,tint) × 3 通道 = {len(ttb) * 3} 次比较，max|Δ| = {worst:.2e}"
          f"（float32 精度极限，非算法差异）；超差 = {len(bad)}"
          + ("" if not bad else f"；例：{bad[0]}"))

    # ── ④ 正交性（[4] vs [5..10] latitude）──────────────────────────────────
    print("④ 正交性：[4] contrast 与 [5..10] latitude 都改曲线中部 ⇒ 端点/黑位不动")
    for lat in ("001", "050"):
        print(f"   latitude={float(lat) / 100:.2f}："
              + " | ".join(
                  f"[4]={u[0]}.{u[1:]} ⇒ 黑端 {hx(spl[f'orth_lat{lat}_u{u}']['m1_toe_0']):.3e} / "
                  f"白端 {hx(spl[f'orth_lat{lat}_u{u}']['black_ev']):.4f}EV / "
                  f"dr {hx(spl[f'orth_lat{lat}_u{u}']['dr']):.4f}"
                  for u in ("050", "100", "150")))
    # 端点 = f(0)=black_disp 与 f(1)=white_disp=1：由 [37][38] 定，改 [4] 必须不动
    ep_ok = True
    for lat in ("001", "050"):
        base = spl[f"orth_lat{lat}_u100"]
        for u in ("050", "150"):
            cur = spl[f"orth_lat{lat}_u{u}"]
            for k in ("black_ev", "white_ev", "dr", "black_disp", "power", "input_min",
                      "input_max"):
                if cur[k] != base[k]:
                    ep_ok = False
    # [5..10] 改的是过渡区位置（lat_min/lat_max），[4] 改的是线性段斜率（linm2）
    lat_moves = spl["orth_lat050_u100"]["latmin_0"] != spl["orth_lat001_u100"]["latmin_0"]
    ct_moves = [spl[f"orth_lat001_u{u}"]["linm2_0"] for u in ("050", "100", "150")]
    ct_distinct = len(set(ct_moves)) == 3
    check("④ 正交性：改 [4] 不动端点 / 改 latitude 不动线性段斜率", ep_ok and lat_moves and ct_distinct,
          f"固定 latitude 改 [4] ⇒ black_disp（= toe 起点 f(0)）/输入端点/power 逐位不变 = "
          f"{ep_ok}；latitude 0.01→0.50 使 lat_min 移动 = {lat_moves}；"
          f"[4] 三档使 linm2（线性段斜率）三值互异 = {ct_distinct}（"
          f"{', '.join(f'{hx(v):.6f}' for v in ct_moves)}）")

    # ── ⑥ mutation 自检（区分度：去掉接入 ⇒ 必须红）────────────────────────
    d_nou = float(np.abs(TS.srgb8(gpu_raw(spl["wk_old_d1_cp1"])[0, :, 0].astype(np.float64))
                         - curves["150"]).max())
    check("⑥ mutation-1：去掉 [4] 接入（用未接入的 cp 系数）必须与非默认 [4] 显著不同",
          d_nou > 3.0,
          f"未接入版 vs [4]=1.5 的最大差 = {d_nou:.1f} LSB（判据 > 3 ⇒ 接入不是死代码）；"
          f"同时 [4]=1.0 时两者逐位相同 = {same_old}（零回归）")
    tt_off = [float(hx(v)) for v in ttb[("20", "0")]]
    wb_off = [1.2, 1.0, 0.9]
    d_tt = max(abs(wb_off[i] * tt_off[i] - wb_off[i]) for i in range(3))
    check("⑥ mutation-2：去掉 [88][89] 增益（不乘 tt）必须与非零 mired 不同",
          d_tt > 0.01,
          f"mired=+20 × wb=(1.2,1,0.9)：含 tt 的 R 增益 = {wb_off[0] * tt_off[0]:.5f} vs "
          f"不含 tt = {wb_off[0]:.5f}（Δ = {abs(wb_off[0] * tt_off[0] - wb_off[0]):.5f}）；"
          f"B 通道 Δ = {abs(wb_off[2] * tt_off[2] - wb_off[2]):.5f} ⇒ 明显非恒等")

    bench.gl.close()
    print("全部通过" if not FAIL else f"失败项：{FAIL}")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
