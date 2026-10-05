#!/usr/bin/env python3
"""HR 前置（v0.10.167）的结构不变量自检 —— 把"改动真的会被执行吗"变成断言。

为什么需要这个脚本（用户已多次因"改完没效果"返工）：历史上出过
  · uniform 只声明、从不读取（uStat 死参数 ⇒ exp21 的统计口径与判定口径无法分离）；
  · 判定分支的条件在真机上永不成立；
  · 纹理在真机路径上根本不存在（拿 0 纹理去 texelFetch ⇒ 全 0 / 全黑）。
本脚本静态核对"接线 + 顺序 + 布局"三件事，任何一条不成立即非零退出。

用法：python3 tools/check-hr-pre.py
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "core/engine/src/render/FrameRenderer.cpp"
HDR = ROOT / "core/engine/src/render/FrameRenderer.h"
text = SRC.read_text()
hdr = HDR.read_text()

fails: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    print(f"{'OK  ' if cond else 'FAIL'} {name}" + (f"  —— {detail}" if detail and not cond else ""))
    if not cond:
        fails.append(name)


def shader(name: str) -> str:
    """取出 const char* <name> = R\"( ... )\"; 的 GLSL 正文。"""
    m = re.search(r'const char\* ' + re.escape(name) + r' = R"\((.*?)\)";', text, re.DOTALL)
    if not m:
        raise SystemExit(f"找不到 shader {name}")
    return m.group(1)


def reads(src: str, sampler: str) -> bool:
    return f"texelFetch({sampler}" in src


print("── A. uStat 真正被读取（第 0 步接线的唯一硬证据）──")
# 统计路径（mask + chrominance）必须读 uStat；这是"统计 vs 判定"独立旋钮的前提。
check("kFsHlMask 读 uStat", reads(shader("kFsHlMask"), "uStat"))
check("kFsHlChroma 读 uStat", reads(shader("kFsHlChroma"), "uStat"))
# 接线前全文件命中 0（注释里那处不算：'texelFetch(uStat' 后必须跟 ', p, 0)'）
raw_hits = len(re.findall(r"texelFetch\(uStat, p, 0\)", text))
check("全文件 texelFetch(uStat, p, 0) 命中 ≥ 2", raw_hits >= 2, f"实际 {raw_hits}")

print("\n── B. 判定口径未换源（apply 仍读 uMos）──")
apply_src = shader("kFsHlApply")
check("kFsHlApply 读 uMos", reads(apply_src, "uMos"))
check("kFsHlApply 不读 uStat", not reads(apply_src, "uStat"))
check("kFsHlRefChroma 读 uMos", reads(shader("kFsHlRefChroma"), "uMos"))
# 判据常量：前置不得改动判定阈值
check("apply 判据 uHlClip 仍为 0.987", "uniform float uHlClip;" in apply_src)
check("CPU 侧 kHlClipThr = 0.987f", "const float kHlClipThr = 0.987f;" in text)
check("apply 的 uGCap 下发仍为 2.0", "u_hl_apply_.gcap, 2.0f" in text)
check("apply 的 chroma 仍置 0", "kHlChromaOff" in text)

print("\n── C. uMono 在四个 shader 里都被读取（只会声明不读 = 会被驱动优化掉）──")
for nm in ("kFsHlMask", "kFsHlChroma", "kFsHlRefChroma", "kFsHlApply"):
    s = shader(nm)
    check(f"{nm} 声明并读取 uMono", "uniform int uMono;" in s and "uMono > 0" in s)

print("\n── D. uWClamp（值域保护）在 P0 两个 shader 里被读取 ──")
for nm in ("kFsRawDenoiseFused", "kFsRawDenoiseFusedTs"):
    s = shader(nm)
    check(f"{nm} 声明并读取 uWClamp",
          "uniform float uWClamp;" in s and "uWClamp > 0.5" in s)
fused = shader("kFsRawDenoiseFused")
# 保护的两条腿必须都在：权重用钳位值 + 信号用原值
check("Fused: d 的分子用 wAt（钳位）", "float d = (wAt(c) - w0) / sr;" in fused)
check("Fused: σ 用 w0（钳位）", "float sr2 = av * w0 + bv;" in fused)
check("Fused: 信号累加仍是原值 c", "acc += w * c;" in fused)
check("Fused: 权重域取自原值 c0", "float c0 = fused0(p);" in fused)

print("\n── E. C++ 侧 uniform location 获取 ──")
# 注意 "uMono" 这个名字 film shader 也在用（u_film_.mono = 单色），故必须限定 prog_hl_
n_hl_mono = len(re.findall(r'glGetUniformLocation\(prog_hl_\w+_, "uMono"\)', text))
check('glGetUniformLocation(prog_hl_*,"uMono") 命中 = 4', n_hl_mono == 4, f"实际 {n_hl_mono}")
n_wc = len(re.findall(r'glGetUniformLocation\([^)]*"uWClamp"\)', text))
check('glGetUniformLocation "uWClamp" 命中 = 2', n_wc == 2, f"实际 {n_wc}")

print("\n── F. 执行顺序：HR 在 P0 之前（前置的定义）──")
# 只看 finishStillChain 体内
body_start = text.index("void FrameRenderer::finishStillChain")
body = text[body_start:]
hr_call = body.index("passHighlightRaw(w, h, c.cfa, c.gains, p0_pre, p0_pre")
# MFNR 分支的真 P0（非 NN）
p0_call = body.index("c.gains, c.dn ? c.nc : kNoDn, nullptr, c.p0_sub, p0_in,")
check("passHighlightRaw 调用点早于 P0(passRawDenoiseFused) 调用点",
      hr_call < p0_call, f"hr@{hr_call} p0@{p0_call}")
check("HR 的 src/stat_src 实参都是 p0_pre（P0 前的 mosaic）",
      "c.gains, p0_pre, p0_pre," in body)
# 单帧展开（kNoDn）必须在 HR 之前
expand = body.index("passRawDenoise(w, h, c.cfa, c.black_phase, c.white_level, c.gains, kNoDn);")
check("单帧展开分支早于 HR 调用点", expand < hr_call)

print("\n── G. 布局配对：HR 输出单通道 .r ⇄ P0 读 .r ──")
check("apply 输出 vec4(outv, 0.0, 0.0, 1.0)", "oColor = vec4(outv, 0.0, 0.0, 1.0);" in apply_src)
check("P0 fused0 读 .r", "return texelFetch(uTex, p, 0).r;" in fused)

print("\n── H. 前置真的被下发（不是死分支）──")
check("finishStillChain 用 hr_out 承接 HR 输出", "if (hl.ok) hr_out = tex_hl_;" in body)
check("P0 输入取 hr_out", "hr_out != 0 ? hr_out" in body)
check("wclamp 只在吃 HR 输出时为真", "const bool p0_wclamp = (!nn_ok && hr_out != 0);" in body)
check("mono 实参下发到 passHighlightRaw", "0, p0_pre_mono);" in body)
check("单帧前置分支存在", "} else if (hr_on) {" in body)
check("NN 输入可换成 HR 输出", "nsrc.fused_tex = hr_out;" in body)

print("\n── I. 诊断字段（真机自证的入口）──")
for f in ("hlPre", "hlStatSrc", "hlDecideSrc", "hlWClamp"):
    check(f'日志字段 "{f}" 存在', f'\\"{f}\\"' in text or f'"{f}"' in text)
check("dbg_hl_stat_src_ 已声明", "dbg_hl_stat_src_" in hdr)
check("dbg_hl_decide_src_ 已声明", "dbg_hl_decide_src_" in hdr)

print("\n── J. 未越界（不该动的东西还在）──")
for marker in ("diagBeginFrame", "scEff", "moGain", "kFsHlRefDown", "hr_wavelet"):
    check(f"{marker} 仍在", marker in text or marker in hdr)

if fails:
    print(f"\n❌ {len(fails)} 项未通过：" + ", ".join(fails))
    sys.exit(1)
print(f"\n✅ 全部结构不变量通过（HR 已前置，统计/判定已解耦）")
