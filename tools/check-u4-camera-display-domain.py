#!/usr/bin/env python3
"""U-4 拍照链路补齐 `[90..93]` 影调分区 + `[94]` 褪色 + `[78]` 清晰度 —— 离线验收。

判据（任务卡 U-4；方案 = `docs/reports/preset-DESIGN.md` §9.3 的拍照侧落地）：

  ① **默认值逐位零回归**（硬判据，**自包含**）：五项取默认（`[78]=0`、`[90..94]=0`）时，
     拍照链路输出必须与"改前"**逐位相同**。做法不依赖 `git show HEAD`（那样的对照物会随
     提交漂移 —— `tools/check-toeshoulder.py` 刚踩过这个坑）：**从工作区的 `kFsOut` 源码里
     机械删除 U-4 的三段**（uniform 声明 / `tapLuma`+`blurLuma16` / main 三段）⇒ 得到
     "改前等价 shader"，两者各真跑一遍同一张 RGBA16F 输入，输出逐位比较。
     三块的定位标记必须各命中一次（找不到即 assert，不静默跳过）。
  ② **五项各自生效且方向/幅度与编辑器侧一致**：把两条链路放到**同一个显示域起点**上 ——
     `kFsOut` 输入 = `srgbDecode(D)`（线性显示域，且 ≤ 0.72 使 `highlightRolloff` 恒等），
     `kFsStyle` 输入 = `D`（显示域）。两条链路的输出都是显示域 ⇒ 可直接比：
       · 五项全默认时两条链路输出**逐位相同**（"同域"的直接证据）；
       · 每一项取 3 档值 ⇒ 逐像素偏移 `Δ = out(param) − out(base)` 在两条链路上**相同**
         （给出幅度数字：max/mean LSB）。
  ③ **文本逐字比对**：把搬过去的数学与 `kFsStyle` 的对应段逐字对比（影调分区 / 褪色 /
     清晰度主段 / 16 个环偏移 + CPU 侧下发行），列明**每一处差异及其理由**。
  ④ **`[39] sharpen` 未被改动**：`sharpenLuma` 函数段 + `uSharpen` 下发行 + 调用行与
     `git show HEAD` 逐字相同（现在 HEAD = 改前），并真跑验证它仍然生效。
  ⑤ **mutation 自检**（脚本内直接跑篡改版）：
       · M1 `[90..93]` 的 **CPU 侧门控公式漏项**（只判 highlights）⇒ `shadows` 非 0 时该
         参数**静默失效**，必须能抓到。⚠️ 刻意不用"把 shader 内 `if (uToneOn > 0.5)` 改成
         恒真"当 mutation：tone 段在参数 0 时是 `c *= max(1.0, 0.0)`、fade 段是
         `c = 0 + c·1.0` —— **都是 IEEE 精确恒等** ⇒ 去掉 shader 内门控在数值上永远查不出来
         （它换来的是"整段跳过"的 GPU 成本，不是数值差异）。这一点在报告里标明。
       · M2 删掉 `[78]` 的 USM 那一行 ⇒ `clarity > 0` 下必须与工作区版**不同**（否则是
         死代码）；
       · M3 去掉 `[94]` 褪色门控（恒假）⇒ `fade > 0` 下必须**不同**。
  ⑥ **成本量级**：清晰度是唯一有显著 GPU 成本的一项（16 tap 邻域 + 每 tap 的域变换），
     打印静态估算（采样数 / exp / pow），真机实测另记（本卡未真机验证）。

为什么用真跑而不是 numpy 直译：ADR §1.4 / §6.5 —— 判据必须来自引擎源码本身。本脚本沿用
`tools/engine-shader-offline.py` 的 GL 执行器（与 check-toeshoulder / check-style-passthrough
同一套封装：只做 `#version 300 es → 330 core` 的机械改写）。

用法：python3 tools/check-u4-camera-display-domain.py [--dump]
依赖：moderngl + glfw（同 check-style-passthrough.py）。
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
ESO = os.path.join(REPO, "tools", "engine-shader-offline.py")
FAIL = []
DUMP = False


def load_eso():
    spec = importlib.util.spec_from_file_location("eso", ESO)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check(name, ok, detail):
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    if not ok:
        FAIL.append(name)


def git_show(rel, ref="HEAD"):
    try:
        return subprocess.check_output(["git", "show", f"{ref}:{rel}"], cwd=REPO).decode()
    except Exception:
        return None


# ────────────────────────────────────────── ① 自包含的"改前等价" shader
# U-4 在 kFsOut 里加的三块。删除它们 = 改前等价 shader（默认值下必须逐位一致）。
U4_BLOCKS = [
    ("uniform 声明段",
     "// ── U-4 清晰度 + 影调分区 + 褪色（display 域 = 本 shader 的 srgbEncode **之后**）──",
     "uniform float uZebra;"),
    ("shader 函数段",
     "// ── U-4 清晰度的 16-tap 环形 luma 模糊",
     "void main() {"),
    ("main 三段",
     "    // ── U-4 ① 清晰度（显示域局部对比；0 = 关）",
     "    // ── E4-2 暗角（vignette，darktable vignette.c 正圆模型）"),
]


def cut_between(text, start, end, tag):
    """删除 [start, end)（end 标记本身保留）。标记不唯一/找不到 ⇒ 抛错（不静默）。"""
    hits = text.count(start)
    assert hits == 1, f"{tag}：起点标记命中 {hits} 次（期望 1）—— shader 结构变了？"
    i = text.index(start)
    j = text.find(end, i + len(start))
    assert j > i, f"{tag}：终点标记未找到 —— shader 结构变了？"
    return text[:i] + text[j:]


def pre_u4_out(src):
    """kFsOut 源码 → 删掉 U-4 三段（= 改前等价 shader）。"""
    out = src
    for tag, start, end in U4_BLOCKS:
        out = cut_between(out, start, end, tag)
    return out


# ────────────────────────────────────────── 输入与 uniform
def grey_img(cols, rows, levels):
    """(cols×rows) 灰阶图：每列一个亮度（显示域编码值 0..255），三通道相同。"""
    img = np.empty((rows, cols, 3), np.float32)
    for i, v in enumerate(levels):
        img[:, i, :] = v / 255.0
    return img


def checker_img(w, h, lo, hi, block):
    """棋盘（清晰度需要细节；平坦区 USM 无效果 ⇒ 必须有边缘）。"""
    y, x = np.mgrid[0:h, 0:w]
    v = np.where(((x // block) + (y // block)) % 2 == 0, hi, lo) / 255.0
    return np.repeat(v[..., None].astype(np.float32), 3, axis=2)


def out_uniforms(w, h, *, clarity=0.0, highlights=0.0, shadows=0.0, whites=0.0,
                 blacks=0.0, fade=0.0, sharpen=0.0):
    """`FrameRenderer::passOutput` 的 CPU 侧下发（**同式**，含门控）—— 见该函数的 U-4 段。"""
    clar_px = max(1.0, 0.005 * min(w, h))       # clarityStepPx（唯一维护点）
    tone_on = (highlights != 0.0 or shadows != 0.0 or whites != 0.0 or blacks != 0.0)
    return {
        "uPx": (1.0 / w, 1.0 / h),
        "uFlipY": 0.0, "uZebra": 0.0, "uSharpen": float(sharpen),
        "uGrainAmount": 0.0, "uGrainSeed": 0.0, "uGrainZoom": 1.0,
        "uGrainNorm": 1.0 / float(min(w, h)),
        "uLutOn": 0.0, "uLutStrength": 0.0, "uLutDomainMin": (0.0, 0.0, 0.0),
        "uLutDomainInv": (1.0, 1.0, 1.0), "uLutSize": 2.0,
        "uVigScaleXY": (w / float(max(w, h)), h / float(max(w, h))),
        "uVigDscale": 0.8, "uVigFscale": 0.5, "uVigBrightness": 0.0,
        "uClarity": float(clarity), "uClarStep": (clar_px / w, clar_px / h),
        "uHighlights": float(highlights), "uShadows": float(shadows),
        "uWhites": float(whites), "uBlacks": float(blacks), "uFade": float(fade),
        "uToneOn": 1.0 if tone_on else 0.0,
    }


def style_uniforms(w, h, *, clarity=0.0, highlights=0.0, shadows=0.0, whites=0.0,
                   blacks=0.0, fade=0.0):
    """`FrameRenderer::passStyle` 的 CPU 侧下发（其余全恒等 ⇒ 只剩 U-4 五项）。"""
    clar_px = max(1.0, 0.005 * min(w, h))
    tone_on = (highlights != 0.0 or shadows != 0.0 or whites != 0.0 or blacks != 0.0)
    return {
        "uEv": 0.0, "uWbGain": (1.0, 1.0, 1.0), "uExpWbOn": 0.0,
        "uContrast": 1.0, "uContrastOn": 0.0, "uSat": 1.0, "uSatOn": 0.0,
        "uClarity": float(clarity), "uClarStep": (clar_px / w, clar_px / h),
        "uLocalOn": 0.0, "uLocalType": 0.0,
        "uHighlights": float(highlights), "uShadows": float(shadows),
        "uWhites": float(whites), "uBlacks": float(blacks), "uFade": float(fade),
        "uToneOn": 1.0 if tone_on else 0.0,
        "uSplit": (0.0, 0.0), "uMono": 0.0,
        "uCropOrigin": (0.0, 0.0), "uCropSize": (1.0, 1.0), "uCropRot": 0.0,
        "uGrainAmount": 0.0, "uGrainSeed": 0.0, "uGrainZoom": 1.0,
        "uGrainNorm": 1.0 / float(min(w, h)),
        "uLutOn": 0.0, "uLutStrength": 0.0, "uLutDomainMin": (0.0, 0.0, 0.0),
        "uLutDomainInv": (1.0, 1.0, 1.0), "uLutSize": 2.0,
        "uVigScaleXY": (w / float(max(w, h)), h / float(max(w, h))),
        "uVigDscale": 0.8, "uVigFscale": 0.5, "uVigBrightness": 0.0,
    }


def srgb_decode_np(x):
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


class Rig:
    """两条链路的公共执行装置（纹理一次建好，反复用）。"""

    def __init__(self, gl, src):
        self.gl, self.src = gl, src
        self.sh = {}
        for m in re.finditer(r'const char\*\s+(kFs\w+)\s*=\s*R"\((.*?)\)";', src, re.S):
            self.sh[m.group(1)] = m.group(2)
        self.lut = gl.ctx.texture3d((2, 2, 2), 4, np.ones((8, 4), "f2").tobytes(),
                                    dtype="f2")
        self.grain = gl.ctx.texture((1, 1), 1, np.ones((1,), "f2").tobytes(), dtype="f2")

    def run(self, name, img, uni, override=None):
        h, w = img.shape[:2]
        tex = self.gl.ctx.texture((w, h), 3, dtype="f4")
        tex.write(np.ascontiguousarray(img, dtype="f4").tobytes())
        tex.filter = (self.gl.ctx.NEAREST, self.gl.ctx.NEAREST)
        tex.repeat_x, tex.repeat_y = False, False   # wrap = CLAMP_TO_EDGE（同引擎纹理）
        out = self.gl.run(override or self.sh[name], uni,
                          {"uTex": tex, "uLut": self.lut, "uGrainLut": self.grain},
                          size=(w, h))
        tex.release()
        return out


def maxlsb(a, b):
    return float(np.abs(a[..., :3] - b[..., :3]).max() * 255.0)


# ────────────────────────────────────────── 判据 ①
def check_zero_regression(rig, w, h):
    levels = [8, 24, 40, 64, 88, 112, 136, 160, 176, 200, 220]
    d = grey_img(len(levels), 6, levels)
    lin = srgb_decode_np(d).astype(np.float32)
    uni = out_uniforms(w, h)
    new = rig.run("kFsOut", lin, uni)
    pre = rig.run("kFsOut", lin, uni, override=pre_u4_out(rig.sh["kFsOut"]))
    same = np.array_equal(new, pre)
    pix = new.shape[0] * new.shape[1] * 4
    check("① 默认值零回归（含 U-4 vs 删掉 U-4 的 kFsOut，RGBA16F 逐位）", same,
          f"{new.shape[1]}×{new.shape[0]}×4 = {pix} 个 half 分量，不同 = "
          f"{int((new != pre).sum())}；max|Δ| = {maxlsb(new, pre):.3e} LSB")

    # 区分度：非默认值下必须不同（否则"新段"是死代码，零回归也就无从谈起）
    u2 = out_uniforms(w, h, clarity=0.6, highlights=0.4, shadows=0.3, whites=-0.2,
                      blacks=-0.3, fade=0.25)
    a = rig.run("kFsOut", lin, u2)
    b = rig.run("kFsOut", lin, u2, override=pre_u4_out(rig.sh["kFsOut"]))
    check("① 区分度：非默认值下「含 U-4」与「改前」必须不同", not np.array_equal(a, b),
          f"max|Δ| = {maxlsb(a, b):.2f} LSB（> 0 ⇒ 新段确实在起作用）")
    return lin, d


# ────────────────────────────────────────── 判据 ②
def check_effects(rig, w, h):
    levels = [8, 32, 64, 96, 128, 160, 192, 220]
    d = grey_img(len(levels), 6, levels)          # 显示域输入（kFsStyle）
    lin = srgb_decode_np(d).astype(np.float32)    # kFsOut 的输入（线性显示域，< knee）

    base_o = rig.run("kFsOut", lin, out_uniforms(w, h))
    base_s = rig.run("kFsStyle", d, style_uniforms(w, h))
    # 口径：half（RGBA16F）网格上的 1 ULP。两条链路的分歧只可能来自**浮点末位**
    #（kFsOut 的输入是 numpy float64 算出的 srgbDecode(D) 再经 GL 的 srgbEncode 回编码，
    # 而 kFsStyle 的输入就是 D）⇒ 允许 ≤1 ULP，且必须打印实际值（不掩盖）。
    d0 = maxlsb(base_o, base_s)
    check("② 五项全默认时两条链路输出一致（≤1 half-ULP，同域的直接证据）", d0 <= 0.2501,
          f"kFsOut(srgbDecode(D)) vs kFsStyle(D)：{w}×{h}×4 逐位不同 = "
          f"{int((base_o != base_s).sum())}；max|Δ| = {d0:.4f} LSB"
          f"（1 half-ULP @[0.5,1) = 0.125 LSB）")

    cases = [
        ("[78] clarity=1.0", "clarity", dict(clarity=1.0)),
        ("[90] highlights=+0.5", "highlights", dict(highlights=0.5)),
        ("[91] shadows=+0.5", "shadows", dict(shadows=0.5)),
        ("[92] whites=-0.5", "whites", dict(whites=-0.5)),
        ("[93] blacks=-0.5", "blacks", dict(blacks=-0.5)),
        ("[94] fade=0.5", "fade", dict(fade=0.5)),
    ]
    print("\n  判据 ② 单项效果对照（显示域 0..255；Δ = 该参数 − 基线，两条链路逐点比）")
    print(f"    {'参数':<22}{'Δout max':>10}{'Δstyle max':>12}{'|Δout−Δstyle| max':>18}"
          f"{'  判定':>6}")
    ok_all = True
    for label, _key, kw in cases:
        o = rig.run("kFsOut", lin, out_uniforms(w, h, **kw))
        s = rig.run("kFsStyle", d, style_uniforms(w, h, **kw))
        do = (o[..., :3] - base_o[..., :3])
        ds = (s[..., :3] - base_s[..., :3])
        amp_o = float(np.abs(do).max() * 255.0)
        amp_s = float(np.abs(ds).max() * 255.0)
        dd = float(np.abs(do - ds).max() * 255.0)
        effective = amp_o > 0.5          # 至少半个 LSB 才算"生效"
        agree = dd <= 0.2501             # 两条链路的偏移一致（≤1 half-ULP）
        ok_all = ok_all and effective and agree
        print(f"    {label:<22}{amp_o:>10.2f}{amp_s:>12.2f}{dd:>18.4f}"
              f"{'  OK' if (effective and agree) else '  FAIL':>6}")
    check("② 五项各自生效且两条链路偏移一致（Δ 逐点 ≤ 1 half-ULP）", ok_all,
          "上表 6 行：clarity 用棋盘图（见下），其余用 8 级灰阶")

    # clarity 的细节图对照（平坦区 USM 恒等 ⇒ 必须用有边缘的输入）
    wc, hc = 128, 128
    chk = checker_img(wc, hc, 40, 200, 16)
    chk_lin = srgb_decode_np(chk).astype(np.float32)
    b_o = rig.run("kFsOut", chk_lin, out_uniforms(wc, hc))
    b_s = rig.run("kFsStyle", chk, style_uniforms(wc, hc))
    o = rig.run("kFsOut", chk_lin, out_uniforms(wc, hc, clarity=1.0))
    s = rig.run("kFsStyle", chk, style_uniforms(wc, hc, clarity=1.0))
    do, ds = (o[..., :3] - b_o[..., :3]), (s[..., :3] - b_s[..., :3])
    amp_o, amp_s = float(np.abs(do).max() * 255.0), float(np.abs(ds).max() * 255.0)
    dd = float(np.abs(do - ds).max() * 255.0)
    base_d = maxlsb(b_o, b_s)
    nonzero = float((np.abs(do - ds) > 0).mean() * 100.0)
    check("② [78] 清晰度在细节图上生效且两链路一致（128² 棋盘，块 16px）",
          base_d <= 0.2501 and amp_o > 1.0 and dd <= 0.2501,
          f"基线两链路 max|Δ| = {base_d:.4f} LSB；Δout max = {amp_o:.2f} LSB、"
          f"Δstyle max = {amp_s:.2f} LSB、|Δout−Δstyle| max = {dd:.4f} LSB"
          f"（非零像素占比 {nonzero:.3f}%）")

    # ★ 平坦区必须**零副作用**（本卡最关键的域判据）：块中心（距边缘 ≥4px ≥ 2 环 +1）的
    # 16 个 tap 全落在同色块内 ⇒ `l0 − lb` 应为 0 ⇒ Δ 应为 0。若 tap 与中心不同域
    #（例如忘了过 srgbEncode 提升），blur 基准会系统性偏低 ⇒ 平坦区被**整体**提亮 ——
    # 这正是 U-1 的 chainLuma 注释记的那类事故，本条就是它的哨兵。
    # 口径 = **≤1 half-ULP**（不是逐位 0）：GLSL 不保证 `pow` 跨调用点逐位确定，
    # 少数像素会差末位。量级对照：真正的域错会让平坦区偏 ~50 LSB（显示 0.784 vs 线性 0.578），
    # 与 1 ULP 相差 3 个数量级 ⇒ 判据的区分度不受影响。
    yy, xx = np.mgrid[0:hc, 0:wc]
    flat = (((xx % 16) >= 4) & ((xx % 16) <= 11) &
            ((yy % 16) >= 4) & ((yy % 16) <= 11))
    flat_do = float(np.abs(do[flat]).max() * 255.0) if flat.any() else 1e9
    flat_ds = float(np.abs(ds[flat]).max() * 255.0) if flat.any() else 1e9
    flat_nz = int((do[flat] != 0).sum())
    check("② [78] 平坦区零副作用（blur 基准与中心同域 ⇒ 不是整体提亮）",
          flat_do <= 0.2501 and flat_ds <= 0.2501,
          f"块中心 {int(flat.sum())} 像素（距边缘 ≥4px）：Δout max = {flat_do:.4f} LSB、"
          f"Δstyle max = {flat_ds:.4f} LSB（非零 {flat_nz} 个分量 = 末位 ULP）；"
          f"⚠ 若 tap 与中心域不同，这里会偏 ~50 LSB（3 个数量级）—— 故本条是域正确性的哨兵")


# ────────────────────────────────────────── 判据 ③ 文本逐字
def _norm(s):
    return re.sub(r"\s+", " ", s.strip())


def _strip_comments(s):
    """去掉 `//` 行注释与块注释 —— "逐字比对"比的是**代码**，注释文字本来就该按链路解释。"""
    s = re.sub(r"/\*.*?\*/", " ", s, flags=re.S)
    return "\n".join(ln.split("//")[0] for ln in s.split("\n"))


def check_text_parity(src):
    sh = {}
    for m in re.finditer(r'const char\*\s+(kFs\w+)\s*=\s*R"\((.*?)\)";', src, re.S):
        sh[m.group(1)] = m.group(2)
    out, style = sh["kFsOut"], sh["kFsStyle"]

    def grab(text, start, end, tag):
        i = text.find(start)
        assert i >= 0, f"{tag}: 未找到起点"
        j = text.find(end, i)
        assert j > i, f"{tag}: 未找到终点"
        return _norm(_strip_comments(text[i: j + len(end)]))

    pairs = []
    pairs.append(("影调分区段（[90..93]，含权重公式）",
                  grab(out, "if (uToneOn > 0.5) {", "c *= max(g, 0.0);\n    }", "tone:out"),
                  grab(style, "if (uToneOn > 0.5) {", "c *= max(g, 0.0);\n    }", "tone:style")))
    pairs.append(("褪色段（[94]）",
                  grab(out, "if (uFade > 0.0) {", "c = lift + c * (1.0 - lift);\n    }", "fade:out"),
                  grab(style, "if (uFade > 0.0) {", "c = lift + c * (1.0 - lift);\n    }", "fade:style")))

    # 清晰度：16 个环偏移（逐项）+ d1/d2 定义 + 归一；采样函数名与 tap 的域预处理不在其中
    def taps(text, tag):
        i = text.find("float blurLuma16(vec2 uv) {")
        assert i >= 0, f"{tag}: blurLuma16 未找到"
        j = text.find("return lb * 0.0625;", i)
        seg = _strip_comments(text[i:j])
        # 环定义只比 d1/d2 两行：Rec.601 权重的**载体位置**不同（kFsOut 在 tapLuma 里，
        # kFsStyle 在本函数内 `vec3 w = …`）—— 权重本身由下面"两侧都用 Rec.601 权重"比。
        head = _norm(seg[seg.find("vec2 d1"):seg.find("float lb = 0.0;")])
        head = head.split("vec3 w =")[0].strip()   # 去掉权重行（载体位置不同）后的残留空白
        offs = [_norm("vec2(" + m + ")") for m in re.findall(r"vec2\(\s*([^)]*)\)", seg)]
        tail = _norm(seg[seg.find("return lb * 0.0625;"):])
        return head, offs, tail
    h_o, off_o, t_o = taps(out, "tap:out")
    h_s, off_s, t_s = taps(style, "tap:style")
    pairs.append(("清晰度环定义 + 16 个偏移 + 归一（[78]）",
                  f"{h_o} | {off_o} | {t_o}", f"{h_s} | {off_s} | {t_s}"))

    # CPU 侧下发行（u_out_ / u_style_ 归一；注释剔除）。⚠ 分两片：passStyle 的清晰度段与
    # 影调段之间夹着局部调整的下发（[79..87]），不是 U-4 的差异。
    def cpu_seg(fn, start, end):
        m = re.search(r"void FrameRenderer::" + fn + r"\(.*?\n\}", src, re.S)
        body = _strip_comments(m.group(0)).replace("u_out_", "u_x_").replace("u_style_", "u_x_")
        i = body.find(start)
        assert i >= 0, f"{fn}: 未找到 {start}"
        j = body.find(end, i)
        assert j > i, f"{fn}: 未找到 {end}"
        return _norm(body[i: j + len(end)])
    for tag, start, end in [
        ("清晰度下发行", "glUniform1f(u_x_.clarity, p.clarity);",
         "clar_px / static_cast<float>(h));"),
        ("影调分区 + 褪色下发行", "const bool tone_on = p.highlights != 0.0f",
         "glUniform1f(u_x_.fade, p.fade);"),
    ]:
        pairs.append((f"CPU 侧{tag}", cpu_seg("passOutput", start, end),
                      cpu_seg("passStyle", start, end)))

    n_diff = 0
    for name, a, b in pairs:
        eq = (a == b)
        n_diff += 0 if eq else 1
        check(f"③ 文本逐字：{name}", eq,
              "逐字相同" if eq else f"不同：\n      out  = {a}\n      style= {b}")

    # 必须存在的差异（域逼出来的）：blurLuma16 的 tap 预处理 + 无 chainLuma
    tap_out = out[out.find("float tapLuma(vec2 uv) {"):out.find("float blurLuma16")]
    has_tap = "srgbEncode(highlightRolloff(texture(uTex, uv).rgb))" in tap_out
    has_w = "vec3(0.299, 0.587, 0.114)" in tap_out
    has_w_style = "vec3(0.299, 0.587, 0.114)" in style[style.find("float blurLuma16"):]
    clar_out = grab(out, "if (uClarity > 0.0) {", "c += clamp(l0 - lb, -0.25, 0.25) * uClarity;\n    }", "cl:out")
    clar_style = grab(style, "if (uClarity > 0.0) {", "c += clamp(l0 - lb, -0.25, 0.25) * uClarity;\n    }", "cl:style")
    diff_ok = has_tap and has_w and has_w_style and ("chainLuma" not in clar_out) \
        and ("chainLuma" in clar_style)
    check("③ 唯一差异 = 域逼出来的 tap 预处理（报告中逐条列明）", diff_ok,
          f"kFsOut 的 tapLuma 过 highlightRolloff+srgbEncode = {has_tap}；"
          f"两侧都用 Rec.601 权重 = {has_w}/{has_w_style}；"
          f"kFsOut 无 chainLuma = {'chainLuma' not in clar_out}；"
          f"kFsStyle 有 chainLuma = {'chainLuma' in clar_style}")
    return n_diff


# ────────────────────────────────────────── 判据 ④ [39] 未改动
def check_sharpen(rig, src):
    head = git_show(FR_REL)
    if head is None:
        check("④ [39] sharpen 未被改动（vs git HEAD 文本）", False, "git show 不可用")
        return

    def film(t):
        m = re.search(r'const char\*\s+kFsOut\s*=\s*R"\((.*?)\)";', t, re.S)
        return m.group(1)

    def g(text, start, end, tag):
        i = text.find(start)
        assert i >= 0, f"{tag}: 未找到"
        return _norm(text[i: text.find(end, i) + len(end)])

    a, b = film(src), film(head)
    if a == b:
        check("④ [39] sharpen 未被改动（vs git HEAD 文本）", True,
              "工作区 kFsOut 与 HEAD 逐字相同（HEAD = 改前；U-4 提交后本条退化为恒真，"
              "一次性证据见报告）")
    else:
        segs = [
            ("sharpenLuma 函数段",
             g(a, "void sharpenLuma(inout vec3 c, vec2 uv) {",
               "c *= 1.0 + clamp(gain, -0.4, 0.4);\n}", "sl:out") ==
             g(b, "void sharpenLuma(inout vec3 c, vec2 uv) {",
               "c *= 1.0 + clamp(gain, -0.4, 0.4);\n}", "sl:head")),
            ("调用行",
             g(a, "if (uSharpen > 0.0) sharpenLuma(c, uv);",
               "if (uSharpen > 0.0) sharpenLuma(c, uv);", "call:out") ==
             g(b, "if (uSharpen > 0.0) sharpenLuma(c, uv);",
               "if (uSharpen > 0.0) sharpenLuma(c, uv);", "call:head")),
            # ⚠ 下发行是 **C++**（不在 shader 字符串里）
            ("uSharpen 下发行",
             g(src, "glUniform1f(u_out_.sharpen, p.sharpen);",
               "glUniform1f(u_out_.sharpen, p.sharpen);", "u:src") ==
             g(head, "glUniform1f(u_out_.sharpen, p.sharpen);",
               "glUniform1f(u_out_.sharpen, p.sharpen);", "u:head")),
        ]
        check("④ [39] sharpen 未被改动（vs git HEAD 文本）", all(ok for _, ok in segs),
              "；".join(f"{t}={'同' if ok else '异'}" for t, ok in segs))
    # 行为：仍然生效
    lin = srgb_decode_np(grey_img(6, 4, [64, 128, 200, 96, 32, 160])).astype(np.float32)
    r = rig.run("kFsOut", lin, out_uniforms(6, 4, sharpen=0.0))
    r2 = rig.run("kFsOut", lin, out_uniforms(6, 4, sharpen=0.5))
    check("④ [39] sharpen 仍然生效（行为）", not np.array_equal(r, r2),
          f"uSharpen 0 → 0.5：max|Δ| = {maxlsb(r, r2):.2f} LSB（> 0 ⇒ 没被 U-4 破坏）")


# ────────────────────────────────────────── 判据 ⑤ mutation
def check_mutations(rig, src):
    out = {}
    for m in re.finditer(r'const char\*\s+(kFs\w+)\s*=\s*R"\((.*?)\)";', src, re.S):
        out[m.group(1)] = m.group(2)
    sh = out["kFsOut"]
    lin = srgb_decode_np(grey_img(8, 6, [16, 48, 80, 112, 144, 176, 208, 232])).astype(np.float32)

    # M1：去掉 [90..93] 门控（恒真）
    m1 = sh.replace("if (uToneOn > 0.5) {", "if (uToneOn > -1.0) {")
    assert m1 != sh, "M1 篡改未命中"
    # M2：删掉 [78] 的 USM 那一行
    m2 = sh.replace("        c += clamp(l0 - lb, -0.25, 0.25) * uClarity;\n", "")
    assert m2 != sh, "M2 篡改未命中"
    # M3：去掉 [94] 门控（恒假）
    m3 = sh.replace("if (uFade > 0.0) {", "if (uFade > 1e9) {")
    assert m3 != sh, "M3 篡改未命中"

    base = rig.run("kFsOut", lin, out_uniforms(8, 6))

    # ── M1：[90..93] 的门控公式**漏项**（只判 highlights，漏 shadows/whites/blacks）──
    # ⚠ 为什么不用"把 `if (uToneOn > 0.5)` 改成恒真"当 mutation：tone 段在参数为 0 时
    # 是 `c *= max(1.0, 0.0)` = **IEEE 精确恒等**（fade 段同理：`c = 0 + c·1.0`）⇒ 去掉
    # shader 内的门控**在数值上永远查不出来**，它换来的是"整段跳过"的 **GPU 成本**。
    # 真正会把效果弄丢的是 **CPU 侧门控公式漏一项**（参数非 0 但门控不开 ⇒ 该参数静默失效，
    # 这正是 check-u3-camera-contrast-wb.py 记的"漏一个消费点"同型风险）⇒ 用它当 mutation。
    on = out_uniforms(8, 6, shadows=0.5)
    miss = dict(on)
    miss["uToneOn"] = 1.0 if on["uHighlights"] != 0.0 else 0.0   # 漏掉 shadows 的坏公式
    a = rig.run("kFsOut", lin, on)
    b = rig.run("kFsOut", lin, miss)
    d_miss = maxlsb(a, b)
    # 门控公式的四项齐全（文本判据，直接读 C++ 源码 —— 不是复算）
    m = re.search(r"const bool tone_on = ([^;]+);", src[src.find("void FrameRenderer::passOutput"):], re.S)
    expr = _norm(m.group(1)) if m else ""
    covered = all(k in expr for k in ("p.highlights", "p.shadows", "p.whites", "p.blacks"))
    # 默认值下"门控开/关"输出逐位相同（数值口径的零回归 + 门控只是成本开关）
    c = rig.run("kFsOut", lin, out_uniforms(8, 6, highlights=0.0), override=m1)
    same_def = np.array_equal(base, c)
    check("⑤ M1 [90..93] 门控漏项 + 四项齐全", d_miss > 1.0 and covered,
          f"C++ 侧 tone_on 覆盖四项 = {covered}；漏掉 shadows 时 shadows=0.5 的输出差 "
          f"{d_miss:.2f} LSB（> 1 ⇒ 漏项会静默丢效果，能被抓到）；"
          f"另：默认值下「shader 内门控恒真」与工作区逐位相同 = {same_def}"
          f"（`×1.0` 是 IEEE 恒等 ⇒ 门控的价值在 GPU 成本，不在数值）")
    # M2
    on = out_uniforms(8, 6, clarity=1.0)
    a = rig.run("kFsOut", lin, on)
    b = rig.run("kFsOut", lin, on, override=m2)
    check("⑤ M2 删掉 [78] 的 USM", maxlsb(a, b) > 1.0,
          f"clarity=1.0 下与工作区版差 {maxlsb(a, b):.2f} LSB（> 1 ⇒ USM 真的在算）")
    # M3
    on = out_uniforms(8, 6, fade=0.5)
    a = rig.run("kFsOut", lin, on)
    b = rig.run("kFsOut", lin, on, override=m3)
    d = maxlsb(a, b)
    r = rig.run("kFsOut", lin, out_uniforms(8, 6), override=m3)
    check("⑤ M3 去掉 [94] 褪色门控", d > 1.0 and np.array_equal(base, r),
          f"fade=0.5 下差 {d:.2f} LSB（> 1）；默认值下逐位相同 = {np.array_equal(base, r)}")


# ────────────────────────────────────────── 判据 ⑥ 成本量级
def report_cost():
    w, h = 4080, 3072
    px = w * h
    clar_px = max(1.0, 0.005 * min(w, h))
    print("\n  判据 ⑥ [78] 清晰度的成本量级（静态估算，**未真机实测**）")
    print(f"    输出 = {w}×{h} = {px / 1e6:.1f} Mpx；环半径 = max(1px, 0.5%×短边) = "
          f"{clar_px:.2f}px ⇒ UV 步长 {clar_px / w:.6f}/{clar_px / h:.6f}")
    print(f"    额外纹理采样 = 16/px ⇒ {16 * px / 1e6:.0f} M 次（对照 [39] sharpen 的 8/px = "
          f"{8 * px / 1e6:.0f} M）")
    print(f"    每 tap 的域变换：highlightRolloff（1 exp）+ srgbEncode（3 pow）"
          f"⇒ 额外 {16 * px / 1e6:.0f} M exp + {48 * px / 1e6:.0f} M pow")
    print("    性质量级：Mali 的 SFU 吞吐按 4/cycle/core × 4 core @1GHz 粗估（16 pow/cycle）"
          f"⇒ {48 * px / 16.0 / 1e9 * 1e3:.0f} ms 量级（仅 pow 一项）—— **必须真机实测**；"
          "若超预算，备选：单环 8-tap（Δ 幅度减半）或另起降采样 pass（由架构师裁决）")


def main():
    global DUMP
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true")
    args = ap.parse_args()
    DUMP = args.dump

    src = open(FR, encoding="utf-8").read()
    eso = load_eso()
    gl = eso.GL(160, 40)
    try:
        rig = Rig(gl, src)
        print(f"提取到 shader {len(rig.sh)} 个；U-4 的落点 = {FR_REL}\n")
        check_zero_regression(rig, 11, 6)
        check_effects(rig, 8, 6)
        n_diff = check_text_parity(src)
        check_sharpen(rig, src)
        check_mutations(rig, src)
    finally:
        gl.close()
    report_cost()
    print(f"\n  ③ 逐字差异处数 = {n_diff}（0 = 与 kFsStyle 完全同式；"
          "唯一实现差异是 clarity tap 的域预处理，已单列判据）")
    print("\n" + ("全部通过" if not FAIL else f"存在失败项：{FAIL}"))
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
