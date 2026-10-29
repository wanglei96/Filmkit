#!/usr/bin/env python3
"""诊断统计（v0.10.166）静态核对：证明「统计链路不影响成片」。

背景：v0.10.163 的图像 dump 方案真机失败 —— ① 浮点 glReadPixels 读回不可用
（`diag.dump_fail reason=readpixels`）；② 失败后破坏渲染状态，后续成片全黑
（`photo.saved avgLuma=0`）。本脚本把当时的两条硬约束固化成可执行检查，
任何一条不满足即非零退出：

  A. 诊断代码里不存在「把**渲染纹理**挂到 FBO」（glFramebufferTexture2D 只允许挂
     诊断自有的 tex_diag_ds_*），唯一的 glReadPixels 必须是 8×2 的
     GL_RGBA + GL_UNSIGNED_BYTE（ES 3.0 唯一保证可用的组合）；
  B. 不存在任何落盘路径（fopen / ".pfm" / "PF\\n"）；
  C. diagStatsTex 对 FBO 绑定 / viewport / program / 纹理单元与绑定 / blend /
     scissor / depth 的保存与恢复成对出现；
  D. 5 个阶段的统计调用点齐全，且诊断 program 不在 init 的失败判据里
     （shader 编译失败只能少日志，不能把引擎拖成初始化失败）。

用法：python3 tools/check-diag-stats.py
"""

import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "core/engine/src/render/FrameRenderer.cpp"
STAGES = ["D3-camrgb", "D4-scene", "HR-filmsrc", "FILM-out", "D5-out-u8",
          # 0.12.109：降噪链逐段（PC3D → P4 → defringe → D-1）—— 灰块定位用。
          # 只在对应 pass 真的跑过时才有条目（c.dn 关时整段不存在）。
          "PC3D", "P4", "DEFRINGE", "D1"]

FAILS: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        FAILS.append(msg)


def body(src: str, sig: str) -> str:
    """取一个顶层定义的函数体（签名到行首的 `}`）。"""
    i = src.index(sig)
    j = src.index("\n}\n", i)
    return src[i:j]


def main() -> int:
    src = SRC.read_text()
    ensure = body(src, "bool FrameRenderer::ensureDiagTargets()")
    tex_fn = body(src, "void FrameRenderer::diagStatsTex(")
    u8_fn = body(src, "void FrameRenderer::diagStatsU8(")
    block = src[src.index("// ── 诊断统计（v0.10.166）"):src.index("}  // namespace")]
    diag_all = ensure + tex_fn + u8_fn + block

    # ── B：没有任何落盘路径（PFM 写盘是本版删掉的东西）────────────────────
    for pat in ["fopen", ".pfm", 'PF\\n', "diagWritePfm", "g_diag_dir"]:
        check(pat not in diag_all, f"B: 诊断代码里仍有落盘痕迹 {pat!r}")

    # ── A1：诊断只挂自己的纹理；diagStatsTex 里一次 glFramebufferTexture2D 都没有
    check("glFramebufferTexture2D" not in tex_fn,
          "A: diagStatsTex 里出现 glFramebufferTexture2D（挂渲染纹理 = 上一版被证伪的做法）")
    for m in re.finditer(r"glFramebufferTexture2D\([^;]*?\);", ensure, re.S):
        check("tex_diag_ds_[" in m.group(0),
              f"A: ensureDiagTargets 挂了非诊断纹理：{m.group(0)[:60]}")

    # ── A2：唯一的诊断 readback 是 8×2 的 RGBA8 ──────────────────────────
    reads = re.findall(r"glReadPixels\([^;]*?\);", tex_fn, re.S)
    check(len(reads) == 1, f"A: diagStatsTex 应只有 1 次 glReadPixels，实为 {len(reads)}")
    for r in reads:
        check("GL_RGBA" in r and "GL_UNSIGNED_BYTE" in r,
              f"A: 诊断 readback 不是 GL_RGBA+GL_UNSIGNED_BYTE：{r[:80]}")
        check("kStatPackW" in r and "kStatPackH" in r,
              "A: 诊断 readback 不是 8×2 的小纹理")
    check("glReadPixels" not in u8_fn, "A: diagStatsU8 不该有 GL 调用")
    # glReadPixels 读的是诊断自己的 FBO，不是渲染目标
    bounds = re.findall(r"glBindFramebuffer\(GL_FRAMEBUFFER, ([^;]*)\);", tex_fn)
    for b in bounds:
        check(b.strip() in ("fbo_diag_ds_", "fbo_diag_out_", "static_cast<GLuint>(prev_fbo)"),
              f"A: diagStatsTex 绑定了非诊断 FBO：{b}")

    # ── C：状态保存 / 恢复成对 ────────────────────────────────────────────
    pairs = [
        ("GL_FRAMEBUFFER_BINDING", r"glBindFramebuffer\(GL_FRAMEBUFFER, static_cast<GLuint>\(prev_fbo\)\)"),
        ("GL_VIEWPORT", r"glViewport\(prev_vp\[0\]"),
        ("GL_CURRENT_PROGRAM", r"glUseProgram\(static_cast<GLuint>\(prev_prog\)\)"),
        ("GL_ACTIVE_TEXTURE", r"glActiveTexture\(static_cast<GLenum>\(prev_unit\)\)"),
        ("GL_TEXTURE_BINDING_2D", r"glBindTexture\(GL_TEXTURE_2D, static_cast<GLuint>\(prev_tex\["),
        ("GL_BLEND", r"if \(prev_blend\) glEnable\(GL_BLEND\)"),
        ("GL_SCISSOR_TEST", r"if \(prev_scissor\) glEnable\(GL_SCISSOR_TEST\)"),
        ("GL_DEPTH_TEST", r"if \(prev_depth\) glEnable\(GL_DEPTH_TEST\)"),
    ]
    for saved, restorer in pairs:
        check(f"glGetIntegerv({saved}" in tex_fn or f"glIsEnabled({saved}" in tex_fn,
              f"C: diagStatsTex 没有保存 {saved}")
        check(re.search(restorer, tex_fn) is not None,
              f"C: diagStatsTex 没有恢复 {saved}")
    # 纹理单元循环次数必须一致（少恢复一个 unit = 把诊断纹理留在成片链的 unit 上）
    loops = re.findall(r"for \(int i = 0; i < (\d+); \+\+i\)", tex_fn)
    check(loops.count(loops[0]) == len(loops) and len(loops) >= 2,
          f"C: 纹理单元的保存/恢复循环次数不一致：{loops}")

    # ── D：调用点齐全 + 诊断 program 不进 init 失败判据 ────────────────────
    for st in STAGES:
        check(re.search(r'diagStats(?:Tex|U8)\("' + re.escape(st) + r'"', src) is not None,
              f"D: 缺少阶段 {st} 的统计调用点")
    init_gate = src[src.index("if (!prog_oes_ || !prog_still_"):]
    init_gate = init_gate[:init_gate.index("return false;")]
    check("prog_diag_" not in init_gate,
          "D: 诊断 program 进了 init 的失败判据（会让统计失败拖垮整个引擎）")

    if FAILS:
        for f in FAILS:
            print(f"FAIL {f}")
        return 1
    print(f"诊断统计静态核对通过：{len(STAGES)} 个阶段调用点、"
          f"{len(pairs)} 项状态保存/恢复、无挂 FBO、无图像读回、无落盘")
    return 0


if __name__ == "__main__":
    sys.exit(main())
