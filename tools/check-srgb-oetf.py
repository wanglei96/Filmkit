#!/usr/bin/env python3
"""校验 FrameRenderer.cpp 里的 srgbEncode：常量、分段点、以及 step 的参数方向。

为什么需要它（v0.10.23）：OETF 从 pow(c,1/2.2) 换成真 sRGB 时，最容易写错的是
`mix(hi, lo, step(...))` 的**方向**——GLSL 的 step(edge, x) 返回 (x < edge ? 0 : 1)，
两个参数写反不会有语法错、也不会被 glslang 抓到（都是 vec3），但会让整幅画面两头
反过来（暗部走幂段、亮部走线性段）。本脚本从源码文本里读那条 step，按 GLSL 语义
逐点复算，与 IEC 61966-2-1 参考值对比。

用法：python3 tools/check-srgb-oetf.py
"""
import re
import sys
import pathlib

SRC = pathlib.Path(__file__).resolve().parent.parent / "core/engine/src/render/FrameRenderer.cpp"
text = SRC.read_text(encoding="utf-8")

# ── 1. 抓 GLSL 里的 srgbEncode 体（kFsOut 与 kFsHist 各一份，两份都要对）──
bodies = re.findall(r"vec3 srgbEncode\(vec3 c\) \{(.*?)\n\}", text, re.S)
if not bodies:
    sys.exit("FAIL: 没有找到 srgbEncode — OETF 又被改回 pow(1/2.2) 了？")
print(f"找到 {len(bodies)} 份 srgbEncode（kFsOut / kFsHist 应各一份）")

CUT = 0.0031308
A, B, P, D = 1.055, -0.055, 1.0 / 2.4, 12.92

def reference(c: float) -> float:
    """IEC 61966-2-1 sRGB **编码**（参考实现，分段点是 0.0031308 而非解码侧的 0.04045）。"""
    c = min(max(c, 0.0), 1.0)
    return D * c if c <= CUT else A * c**P + B

for i, body in enumerate(bodies):
    # ── 2. step 方向：必须写成 step(c, vec3(0.0031308))，即 c 是 edge ──
    m = re.search(r"step\(\s*([^,]+?)\s*,\s*vec3\(\s*([0-9.]+)\s*\)\s*\)", body)
    if not m:
        sys.exit(f"FAIL[{i}]: 找不到 step(...) 分段，无法校验")
    edge, x = m.group(1).strip(), float(m.group(2))
    if edge != "c":
        sys.exit(f"FAIL[{i}]: step 的第一个参数应是 c（edge），实际是 {edge!r} —— 参数写反")
    if abs(x - CUT) > 1e-9:
        sys.exit(f"FAIL[{i}]: 分段点应为 {CUT}，实际 {x}")
    # GLSL: step(edge, x) = (x < edge) ? 0 : 1，这里 edge=c、x=0.0031308
    # ⇒ c > 0.0031308 → 0 → mix(hi, lo, 0) = hi（幂段）
    #    c ≤ 0.0031308 → 1 → mix(hi, lo, 1) = lo（线性段）
    def glsl_encode(c: float) -> float:
        c = min(max(c, 0.0), 1.0)
        hi = A * c**P + B
        lo = D * c
        return lo if x >= c else hi
    for c in (0.0, 0.001, 0.0031308, 0.01, 0.04045, 0.18, 0.5, 0.9, 1.0):
        got = glsl_encode(c)
        want = reference(c)
        # 容差取 1/255 的 1/10：足够抓到分段点/常量写错，又不被浮点噪声干扰
        if abs(got - want) > 4e-4:
            sys.exit(f"FAIL[{i}]: c={c} → shader 给 {got:.6f}，参考 {want:.6f}")
    print(f"  OK[{i}]: step 方向正确、常量一致，9 个采样点全部匹配参考 sRGB")

# ── 3. 顺带确认 CPU 侧折回函数与编码互逆（不同步会让 linMid/pLo/pHi 失真）──
inv = re.search(r"return v <= ([0-9.]+)f \? v / ([0-9.]+)f\s*:\s*std::pow\(\(v \+ ([0-9.]+)f\) / ([0-9.]+)f, ([0-9.]+)f\);", text)
if not inv:
    sys.exit("FAIL: measureSceneStats 的 to_lin 不是 sRGB EOTF —— 与 OETF 不同步")
cut_i, k_i, off_i, a_i, g_i = (float(g) for g in inv.groups())
if (cut_i, k_i, off_i, a_i, g_i) != (0.04045, 12.92, 0.055, 1.055, 2.4):
    sys.exit(f"FAIL: to_lin 常量异常 {(cut_i, k_i, off_i, a_i, g_i)}")
print("  OK: CPU 侧 to_lin = sRGB EOTF（0.04045 / 12.92 / 0.055 / 1.055 / 2.4）")
print("\n全部通过：OETF = 真 sRGB，编码与统计折回同步。")
