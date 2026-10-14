#!/usr/bin/env python3
"""E4-5 halation（光晕）—— 离线验收。

规格 = docs/reports/editor-E4-film-params-DESIGN.md §3.2（唯一权威）；参考实现 =
jie60517/emulsion `src/render/effects/library/halation.ts`（唯一有**通道扩散**的公开
实现；darktable `bloom.c` 是 Orton 效应、不含通道扩散，仅作对照）。

判据（任务卡 E4-5 验收判据 1..6）：
  ① **[62] = 0 逐位零回归 + 零 pass 零纹理**：
     · 静态：`runHalation` 的 `amount <= 0` 提前 return 必须**先于** `ensureHaloTargets`
       （⇒ 不分配任何纹理、不发任何 draw）；
     · 真跑：halo 链在 [62] = 0 时输出 == 输入（逐位，0 pass）。
     另附规格 A8 的验证：halo 开启但**无高光**时，输出与输入的差异 = 纯 RGBA16F 往返
     量化（给出 max/mean LSB，判"不可见"）。
  ② **点光源呈"白 → 橙 → 红"的径向渐变**：逐通道扩散半径 r̄_R > r̄_G > r̄_B（乳剂对
     红光散射最远），且归一化色度 G/R、B/R 随半径**递减**（近环白、中环橙、远环红）。
     输出"半径-色度曲线"表。
  ③ **[64] 半径单调**：r̄_R 随 [64] 单调增（3 档）。
  ④ **[63] 阈值单调**：halo 总能量随 [63] 单调减（3 档）。
  ⑤ **[65] 暖度单调**：added 的 (G+B)/R 随 [65] 单调增（tint 的绿蓝分量按 warmth 抬升）。
  ⑥ **分辨率无关（E4-5b / R1 修正后）**：同参数、两个分辨率（点光源 σ 按比例缩放）下
     **归一化** r̄ 一致（6a PASS）；并给"修前对照"（6b，证明不是恒真）、"实机等效解析"
     （6c，1080 vs 3072）、"成片逐位零变化"（6d）、"很小 [64] 的已知下限"（6e，WARN）。
  ⑦ **两条链路逐位相同**：`kFsOut(srgbDecode(D))` vs `kFsStyle(D)`（U-4 口径：
     `highlightRolloff` 恒等域内 = 线性 max ≤ 0.80）。**差异逐条列明**：两条链路都是
     RGBA16F 中间纹理，但量化与 OETF 的顺序不同（拍照 `encode(q(sum))`、编辑器
     `q(encode(sum))`）⇒ 判据 = 输出位深（8bit）内逐位相同。
  ⑧ **mutation 自检**（≥2）：脚本内直接篡改，必须能抓到（M1 去掉通道扩散 / M2 去掉
     通道扩散后的色度恒定 / M3 暖度映射反向）。

为什么真跑而不是 numpy 直译：ADR §1.4 / §6.5 —— 判据必须来自引擎源码本身。本脚本沿用
`tools/engine-shader-offline.py` 的 GL 执行器（与 check-toeshoulder /
check-u4-camera-display-domain / check-style-passthrough 同一套封装：只做
`#version 300 es → 330 core` 的机械改写），shader 源码**逐字取自 FrameRenderer.cpp**。

⚠ 离线能决策什么、不能决策什么（规格 §6.2/§6.3）：**形态 / 半径 / 阈值 / 色相可决策**
（halation 在 kFsFilm 之后，其输入在离线链上与真机同域）；**性能不可决策**（桌面 GL 与
Mali 无关）；**"目视像不像 halation"只能真机**（规格 §6.2 已说明）。

用法：python3 tools/check-halation.py [--dump]
依赖：moderngl + glfw + numpy（同 check-u4-camera-display-domain.py）。
"""
import argparse
import importlib.util
import os
import re
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FR = os.path.join(REPO, "core/engine/src/render/FrameRenderer.cpp")
ESO = os.path.join(REPO, "tools", "engine-shader-offline.py")
FAIL = []
WARN = []
DUMP = False
LEVELS = 5
SPREAD = np.array([1.0, 0.6, 0.32], dtype=np.float64)  # 与源码常量断言一致
SIGMA = 0.95


def load_eso():
    spec = importlib.util.spec_from_file_location("eso", ESO)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check(name, ok, detail):
    print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    if not ok:
        FAIL.append(name)


def warn(name, detail):
    """规格声称与实测不符 —— 记录 + 交用户拍板，不算实现失败（退出码仍为 0）。"""
    print(f"WARN {name}: {detail}")
    WARN.append(name)


# ────────────────────────────────────────── 数学（与 shader 逐字同式）
def srgb_decode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_to_linear1(c):
    """[63] 的单位是显示域 sRGB 编码值 ⇒ 进 shader 前换算（C++ 的 srgbToLinear1）。"""
    c = min(max(c, 0.0), 1.0)
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def halo_weights(radius01, spread=None, level_shift=0.0):
    """emulsion `haloWeights`：逐通道高斯，每通道独立归一化（C++ 的同名函数）。

    `level_shift` = **E4-5b（R1）**：把峰值按 `log2(min(w,h)/refMin)` 平移，使 halo 的
    归一化半径与图像尺寸无关。0.0 = 修正前行为（固定像素尺度）。
    """
    sp = SPREAD if spread is None else np.asarray(spread, dtype=np.float64)
    out = np.zeros((LEVELS, 3), dtype=np.float64)
    for c in range(3):
        centre = radius01 * (LEVELS - 1) * sp[c] + level_shift
        raw = np.array([np.exp(-((i - centre) ** 2) / (2 * SIGMA ** 2))
                        for i in range(LEVELS)])
        s = raw.sum()
        if s <= 0.0:                       # 与 C++ 同式的防御（防 0/0）
            nearest = min(max(int(round(centre)), 0), LEVELS - 1)
            out[:, c] = [1.0 if i == nearest else 0.0 for i in range(LEVELS)]
            continue
        out[:, c] = raw / s
    return out


def level_shift_for(w, h, ref_min):
    """R1 的平移量；`ref_min is None` ⇒ 0.0（修正前行为）。"""
    return 0.0 if ref_min is None else float(np.log2(min(w, h) / float(ref_min)))


def sigma_eff_norm(radius01, w, h, ref_min, spread=None):
    """解析预测的**归一化**等效半径：Σ_i w_i,R · 1.637·2^(i+1) / min(w,h)。

    1.637 = 5-tap 高斯（权重 0.227027/0.3162162×2/0.0702703×2，偏移 1.3846/3.2308）
    在该级的等效 σ（方差 2.6793）；×2^(i+1) 换算到全分辨率像素。
    """
    wgt = halo_weights(radius01, spread, level_shift_for(w, h, ref_min))
    s = sum(wgt[i][0] * 1.637 * (2 ** (i + 1)) for i in range(LEVELS))
    return s / min(w, h)


def halo_tint(warmth):
    """emulsion：tint = (1, 0.24 + 0.34·w, 0.44 − 0.22·w)（C++ 的同式）。"""
    return np.array([1.0, 0.24 + 0.34 * warmth, 0.44 - 0.22 * warmth])


# ────────────────────────────────────────── GL 编排（与 runHalation 同序）
def make_gl(eso, w, h):
    """带 program 缓存的 GL 执行器（16 pass × 多次 run ⇒ 不缓存会重复编译 200+ 次）。"""
    import moderngl

    class HaloGL(eso.GL):
        def __init__(self, ww, hh):
            super().__init__(ww, hh)
            self._progs = {}

        def run(self, fs_src, uniforms, textures, size=None):
            tw, th = size if size else (self.w, self.h)
            if (tw, th) not in self._fbos:
                self._fbos[(tw, th)] = self.ctx.framebuffer(
                    color_attachments=[self.ctx.texture((tw, th), 4, dtype="f2")])
            fbo = self._fbos[(tw, th)]
            if fs_src not in self._progs:
                self._progs[fs_src] = self.ctx.program(self.vs_src, eso.to_core(fs_src))
            prog = self._progs[fs_src]
            fbo.use()
            self.ctx.viewport = (0, 0, tw, th)
            for i, (name, tex) in enumerate(textures.items()):
                tex.use(i)
                if name in prog:
                    prog[name].value = i
            for k, v in uniforms.items():
                if k in prog:
                    prog[k].value = v
            vao = self.ctx.vertex_array(prog, [])
            vao.render(moderngl.TRIANGLES, vertices=3)
            vao.release()
            data = fbo.read(components=4, dtype="f2")
            return np.frombuffer(data, dtype=np.float16).reshape(th, tw, 4).astype(np.float32)

    return HaloGL(w, h)


def mktex(gl, arr, pool=None):
    import moderngl
    h, w = arr.shape[:2]
    rgba = np.ones((h, w, 4), dtype=np.float16)
    rgba[:, :, :3] = arr.astype(np.float16)
    t = gl.ctx.texture((w, h), 4, rgba.tobytes(), dtype="f2")
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = False
    t.repeat_y = False
    if pool is not None:
        pool.append(t)
    return t


def run_halo(gl, sh, src, strength, threshold, radius, warmth, decode_in,
             spread=None, knee_zero=False, ref_min=None):
    """16 pass 的 Python 侧编排，与 FrameRenderer::runHalation 逐序对应。

    src: (h, w, 3) float32。decode_in=False ⇒ src 是**线性显示域**；True ⇒ 显示域。
    返回同域同尺寸的结果（[62] = 0 时**原样返回**，与 C++ 的提前 return 一致）。

    ⚠ C++ 的 blur 用**一张最大级 tmp** + uScale 把更小级的归一化 UV 缩到已写入的
      左下角子矩形；这里用逐级独立纹理 + uScale = (1,1)。两者数学等价：GL 的 FBO
      原点在左下角，viewport(0,0,w_i,h_i) 写入 tmp 的 texel (x,y)，而
      `vUV0·(w_i/tmp_w)` 在输出像素 (x+0.5) 处正好采到 tmp 的 texel (x) 中心。
    """
    if strength <= 0.0:
        return src.copy()          # 整链旁路（零 pass 零纹理）
    h, w = src.shape[:2]
    lv = [(max(2, w >> (i + 1)), max(2, h >> (i + 1))) for i in range(LEVELS)]
    thr_lin = srgb_to_linear1(threshold)
    knee = 0.0 if knee_zero else max(0.02, thr_lin * 0.55)
    pool = []

    def thr_pass(texture, size, texel, decode, thr_on):
        return gl.run(sh["kFsHaloThr"], {
            "uTexel": texel, "uDecodeIn": 1.0 if decode else 0.0,
            "uThrOn": 1.0 if thr_on else 0.0, "uThrLin": thr_lin, "uKnee": knee,
        }, {"uSrc": texture}, size=size)[:, :, :3]

    try:
        # ① 高光提取 + 手写 2×2 盒式降到 1/2
        levels = [thr_pass(mktex(gl, src, pool), lv[0], (1.0 / w, 1.0 / h),
                           decode_in, True)]
        # ② 逐级降采样
        for i in range(1, LEVELS):
            pw, ph = lv[i - 1]
            levels.append(thr_pass(mktex(gl, levels[i - 1], pool), lv[i],
                                   (1.0 / pw, 1.0 / ph), False, False))
        # ③ 逐级可分离高斯（先横后纵）
        for i in range(LEVELS):
            wi, hi = lv[i]
            t = gl.run(sh["kFsHaloBlur"],
                       {"uDir": (1.0 / wi, 0.0), "uScale": (1.0, 1.0)},
                       {"uSrc": mktex(gl, levels[i], pool)}, size=(wi, hi))[:, :, :3]
            levels[i] = gl.run(sh["kFsHaloBlur"],
                               {"uDir": (0.0, 1.0 / hi), "uScale": (1.0, 1.0)},
                               {"uSrc": mktex(gl, t, pool)}, size=(wi, hi))[:, :, :3]
        # ④ 合成（全分辨率，6 采样/px）
        wgt = halo_weights(radius, spread, level_shift_for(w, h, ref_min))
        uni = {"uTint": tuple(halo_tint(warmth)), "uAmount": float(strength),
               "uDecodeIn": 1.0 if decode_in else 0.0}
        texs = {"uSrc": mktex(gl, src, pool)}
        for i in range(LEVELS):
            uni[f"uW{i}"] = tuple(wgt[i])
            texs[f"uHalo{i}"] = mktex(gl, levels[i], pool)
        return gl.run(sh["kFsHaloMix"], uni, texs, size=(w, h))[:, :, :3]
    finally:
        for t in pool:
            t.release()


# ────────────────────────────────────────── 测试图与径向分析
def point_source(w, h, sigma_px, peak=1.0):
    """黑底 + 中心高斯点光源（显示域值）。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    cx, cy = w // 2, h // 2
    r2 = (xx - cx) ** 2 + (yy - cy) ** 2
    img = peak * np.exp(-r2 / (2.0 * sigma_px ** 2))
    return np.repeat(img[:, :, None], 3, axis=2).astype(np.float32)


def radial_bins(w, h, nbin=40):
    """归一化半径（÷ min(w,h)）与分箱索引，中心 = 点光源中心。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    cx, cy = w // 2, h // 2
    r = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / min(w, h)
    idx = np.clip((r * nbin).astype(int), 0, nbin - 1)
    return r, idx, nbin


def channel_centroid_r(chan, r, idx, nbin, rmax=0.45):
    """逐通道扩散半径 = Σ a·r / Σ a（只看 r < rmax，排除中心直射光残余）。"""
    m = r < rmax
    a = np.clip(chan[m], 0.0, None)
    rr = r[m]
    s = a.sum()
    return 0.0 if s <= 1e-9 else float((a * rr).sum() / s)


def radial_table(added, w, h, nbin=16):
    """半径-色度曲线：每箱的归一化 (R,G,B)（÷ max）+ G/R + B/R。"""
    r, idx, nb = radial_bins(w, h, nbin)
    rows = []
    for b in range(nb):
        m = idx == b
        if not m.any():
            continue
        v = np.array([float(np.clip(added[:, :, c][m], 0, None).mean())
                      for c in range(3)])
        mx = v.max()
        if mx <= 1e-7:
            continue
        nv = v / mx
        rows.append((float(r[m].mean()), nv[0], nv[1], nv[2],
                     nv[1] / max(nv[0], 1e-6), nv[2] / max(nv[0], 1e-6)))
    return rows


def mono_nonincreasing(vals, tol=1e-6, allow=2):
    """是否随半径单调不增（允许 ≤ allow 处抖动）。"""
    bad = sum(1 for i in range(1, len(vals)) if vals[i] > vals[i - 1] + tol)
    return bad <= allow, bad


# ────────────────────────────────────────── 完整链路（判据 7）
def run_out_style(gl, sh, Dq, halo_lin, halo_disp):
    """kFsOut(halo_lin) vs kFsStyle(halo_disp)：U-4 口径的同域对拍。

    两条链路的其余段全部取默认（恒等）值 ⇒ 只剩 srgbEncode + TPDF dither。
    """
    h, w = Dq.shape[:2]
    pool = []
    dummy = mktex(gl, np.zeros((1, 1, 3), dtype=np.float32), pool)
    common = {"uGrainAmount": 0.0, "uGrainSeed": 0.0, "uGrainZoom": 1.0,
              "uGrainNorm": 1.0 / min(w, h), "uLutOn": 0.0, "uLutStrength": 0.0,
              "uVigBrightness": 0.0, "uClarity": 0.0,
              "uClarStep": (1.0 / w, 1.0 / h), "uToneOn": 0.0, "uFade": 0.0}
    try:
        out = gl.run(sh["kFsOut"], dict(common, **{
            "uPx": (1.0 / w, 1.0 / h), "uFlipY": 0.0, "uZebra": 0.0,
            "uSharpen": 0.0}),
            {"uTex": mktex(gl, halo_lin, pool), "uGrainLut": dummy, "uLut": dummy},
            size=(w, h))[:, :, :3]
        sty = gl.run(sh["kFsStyle"], dict(common, **{
            "uEv": 0.0, "uWbGain": (1.0, 1.0, 1.0), "uExpWbOn": 0.0,
            "uContrast": 1.0, "uContrastOn": 0.0, "uSatOn": 0.0, "uCurveOn": 0.0,
            "uLocalOn": 0.0, "uSplit": (0.0, 0.0), "uMono": 0.0,
            "uCropOrigin": (0.0, 0.0), "uCropSize": (1.0, 1.0), "uCropRot": 0.0}),
            {"uTex": mktex(gl, halo_disp, pool), "uGrainLut": dummy, "uLut": dummy},
            size=(w, h))[:, :, :3]
    finally:
        for t in pool:
            t.release()
    return out, sty


# ────────────────────────────────────────── 静态检查（源码文本）
def check_static(src):
    m = re.search(r"GLuint FrameRenderer::runHalation\(.*?\n}", src, re.S)
    check("S1 runHalation 存在", m is not None, "找到函数体" if m else "未找到")
    if m:
        body = m.group(0)
        # ★ SG-1 重构：①②③（提取 / 降采样 / 模糊）搬到 `runHaloPyramid` —— 那是
        # **halation 与柔光共享**的一层（规格 §5）。⇒ 静态检查必须**跨两个函数**看：
        # 提前 return 仍在 runHalation（⇒ 零 pass 零纹理的语义不变），纹理分配在
        # runHaloPyramid。halation 的行为由下面的真跑判据（1a/1b/2a..7b）逐位守住。
        mp = re.search(r"bool FrameRenderer::runHaloPyramid\(.*?\n}", src, re.S)
        pyr = mp.group(0) if mp else ""
        i_ret = body.find("if (amount <= 0.0f || src_tex == 0) return src_tex;")
        i_ens = body.find("ensureHaloTargets(")
        check("S1 [62]=0 提前 return 先于纹理分配（零 pass 零纹理）",
              i_ret >= 0 and i_ens > i_ret
              and "ensureHaloTargets(w, h, /*need_out=*/true)" in body
              and "if (!ensureHaloTargets(" in pyr,
              f"return@{i_ret} < ensureHaloTargets@{i_ens}；金字塔侧先 ensure 再 draw"
              f"（pyr={'找到' if pyr else '未找到'}）")
        ndraw = body.count("glDrawArrays(GL_TRIANGLES, 0, 3);") + \
            pyr.count("glDrawArrays(GL_TRIANGLES, 0, 3);")
        check("S2 pass 编排 = 16 次执行（1 Thr + 4 Down + 10 Blur + 1 Mix）",
              ndraw == 5 and "for (int i = 1; i < kHaloLevels; ++i)" in pyr
              and (pyr.count("for (int i = 0; i < kHaloLevels; ++i)") +
                   body.count("for (int i = 0; i < kHaloLevels; ++i)")) == 2
              and body.count("glDrawArrays(GL_TRIANGLES, 0, 3);") == 1,
              f"5 个调用点（金字塔内 2 个 for：Down ×4、Blur ×2 层 ×5，+ mix 1）"
              f" ⇒ 执行 = 1 + 4 + 10 + 1 = 16（SG-1 起跨 runHalation + runHaloPyramid）")
        check("S3 halo 链不做 shoulder（规格 §3.2.1 ⑦：交给 kFsOut 的 rolloff）",
              "highlightRolloff" not in body and "highlightRolloff" not in pyr,
              "runHalation / runHaloPyramid 内均无 highlightRolloff 调用")
    sp = re.search(r"kHaloSpread\[3\] = \{([^}]*)\}", src)
    sg = re.search(r"kHaloSigma = ([0-9.]+)f", src)
    lv = re.search(r"kHaloLevels = (\d+)", src)
    got = np.array([float(x.replace("f", "")) for x in sp.group(1).split(",")]) \
        if sp else None
    check("S4 CHANNEL_SPREAD = {1.0, 0.6, 0.32}（emulsion 同值）",
          got is not None and np.allclose(got, SPREAD),
          f"源码 = {list(got) if got is not None else None}")
    check("S5 SPREAD_SIGMA = 0.95（emulsion 同值）",
          sg is not None and abs(float(sg.group(1)) - SIGMA) < 1e-9,
          f"源码 = {sg.group(1) if sg else None}")
    check("S6 LEVELS = 5（emulsion 同值）",
          lv is not None and int(lv.group(1)) == LEVELS,
          f"源码 = {lv.group(1) if lv else None}")
    for name in ("kFsHaloThr", "kFsHaloBlur", "kFsHaloMix"):
        mm = re.search(r'const char\* ' + name + r' = R"\((.*?)\)";', src, re.S)
        body = mm.group(1) if mm else ""
        bad = []
        if re.search(r"\bfor\s*\(", body):
            bad.append("循环（动态索引风险）")
        if re.search(r"\b(float|vec[234]|sampler2D)\s+\w+\s*\[", body):
            bad.append("数组声明")
        if re.search(r"\[[0-9]+\]\s*\(", body):
            bad.append("数组初始化器")
        if "#define" in body:
            bad.append("宏（多行宏禁令）")
        check(f"S7 {name} 三禁", not bad, "无循环/数组/宏" if not bad else f"命中 {bad}")
    check("S8 passOutput 接线（拍照链路）",
          "runHalation(w, h, tex_work_, p, /*decode_in=*/false)" in src,
          "passOutput 内以 tex_work_ 为源（线性显示域）")
    check("S9 passStyle 接线（编辑器链路）",
          "runHalation(edit_w_, edit_h_, edit_tex_, p, /*decode_in=*/true)" in src,
          "passStyle 内以 edit_tex_ 为源、尺寸 = 输入位图（按原画幅归一化）")
    # E4-5b（R1）：成片基准短边必须在 passOutput（内部调 runHalation）**之前**设置，
    # 否则成片的 shift ≠ 0 ⇒ 会改变成片观感（本卡承诺"成片逐位不变"）。
    # ⚠ 成片路径有**两条**：YUV 静拍 `submitStill` / RAW 静拍 `finishStillChain`
    #   ⇒ 两处都要设（E4-5b 首版只设了后者，本判据把它抓了出来）。
    REF_ASSIGN = "halo_ref_min_ = static_cast<float>(std::min(dst_w, dst_h));"
    # 卡 H2：passOutput 增加第 6 参 = 交付档 dither 上限（8bit 255 / 10bit 1023）⇒ 锚点
    # 从 `..., true, false);` 改为 `..., true, false, outputMaxOf(`（前缀匹配，数量仍须 = 2：
    # 多一处 = 多了成片入口，少一处 = 有成片路径没走交付档口径）。
    OUT_CALL = "passOutput(dst_w, dst_h, p, true, false, outputMaxOf("
    ok10, n_out, det10, pos = True, 0, [], 0
    while True:
        i_out = src.find(OUT_CALL, pos)
        if i_out < 0:
            break
        n_out += 1
        i_r = src.rfind(REF_ASSIGN, 0, i_out)
        near = i_r >= 0 and (i_out - i_r) < 400      # 同一个函数内紧邻
        ok10 = ok10 and near
        det10.append(f"#{n_out}@{i_out} ref@{i_r if i_r >= 0 else -1}"
                     f"{'OK' if near else 'MISSING'}")
        pos = i_out + 1
    check("S10 R1：两条成片路径（submitStill + finishStillChain）都在 passOutput 前设基准短边",
          ok10 and n_out == 2, f"成片调用 {n_out} 处；" + " ".join(det10))
    # R1 的平移量公式（文本判据：确认用的是 log2(min/ref) 而不是别的方向）
    check("S11 R1：level_shift = log2(min(w,h)/halo_ref_min_)（方向正确）",
          "std::log2(static_cast<float>(std::min(w, h)) / halo_ref_min_)" in src,
          "runHalation 内按 log2(min/ref) 平移权重峰值")


# ────────────────────────────────────────── 主流程
def main():
    global DUMP
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true", help="打印半径-色度曲线")
    args = ap.parse_args()
    DUMP = args.dump

    eso = load_eso()
    src_text = open(FR, encoding="utf-8", errors="replace").read()
    sh = eso.extract_shaders()
    for k in ("kFsHaloThr", "kFsHaloBlur", "kFsHaloMix"):
        if k not in sh:
            print(f"FAIL 未提取到 {k}")
            return 1
    print("── 静态检查（源码文本）")
    check_static(src_text)

    W, H = 512, 384
    gl = make_gl(eso, W, H)
    try:
        print("\n── 真跑（moderngl 执行 FrameRenderer.cpp 里的 GLSL）")
        D = point_source(W, H, sigma_px=1.5)             # 显示域点光源
        LIN = srgb_decode(D).astype(np.float32)          # 线性显示域

        # ── 判据 ①：[62] = 0 逐位零回归 ──────────────────────────────────
        out0 = run_halo(gl, sh, LIN, 0.0, 0.5, 0.45, 0.4, decode_in=False)
        check("1a [62]=0 逐位零回归（线性域）",
              np.array_equal(out0, LIN), f"{out0.size} 个 float 分量逐位相同")
        D0 = run_halo(gl, sh, D, 0.0, 0.5, 0.45, 0.4, decode_in=True)
        check("1b [62]=0 逐位零回归（显示域）",
              np.array_equal(D0, D), f"{D0.size} 个 float 分量逐位相同")

        # 规格 A8：halo 开但**无高光** ⇒ 只剩 RGBA16F 往返量化。
        # 阈值 0.9（线性 0.7874）⇒ knee = 0.4331 ⇒ luma < 0.3543 处权重恒 0；
        # 全暗图（显示域峰值 0.2 ⇒ 线性 0.0331）全图 w = 0 ⇒ added ≡ 0。
        DARK = srgb_decode(point_source(W, H, sigma_px=1.5, peak=0.2)).astype(np.float32)
        a8 = run_halo(gl, sh, DARK, 1.0, 0.9, 0.45, 0.4, decode_in=False)
        q = np.abs(a8 - DARK) * 255.0
        check("1c A8 无高光时 added≡0（只剩 RGBA16F 往返量化）",
              float(q.max()) <= 1.0,
              f"max = {q.max():.4f}/255, mean = {q.mean():.6f}/255")

        # ── 判据 ②：白 → 橙 → 红 ────────────────────────────────────────
        ADD = run_halo(gl, sh, LIN, 1.0, 0.5, 0.45, 0.4, decode_in=False) - LIN
        r, idx, nb = radial_bins(W, H, 40)
        cr = [channel_centroid_r(ADD[:, :, c], r, idx, nb, rmax=0.45)
              for c in range(3)]
        check("2a 通道扩散半径 R > G > B（乳剂对红光散射最远）",
              cr[0] > cr[1] > cr[2],
              f"r̄_R = {cr[0]:.5f}, r̄_G = {cr[1]:.5f}, r̄_B = {cr[2]:.5f}（归一化半径）")
        rows = radial_table(ADD, W, H, nbin=16)
        vg = [row[4] for row in rows]
        vb = [row[5] for row in rows]
        ok_g, bad_g = mono_nonincreasing(vg)
        ok_b, bad_b = mono_nonincreasing(vb)
        check("2b G/R 随半径递减（中环偏橙）", ok_g,
              f"G/R: {vg[0]:.3f} → {vg[len(vg)//2]:.3f} → {vg[-1]:.3f}"
              f"（{bad_g} 处抖动，容差 2）")
        check("2c B/R 随半径递减（外环偏红）", ok_b,
              f"B/R: {vb[0]:.3f} → {vb[len(vb)//2]:.3f} → {vb[-1]:.3f}"
              f"（{bad_b} 处抖动，容差 2）")
        check("2d G/R 沿半径下降 ≥ 90%（近环偏白/橙 → 远环纯红）",
              (vg[0] - vg[-1]) / max(vg[0], 1e-9) >= 0.90,
              f"G/R {vg[0]:.3f} → {vg[-1]:.3f}（下降 "
              f"{(vg[0]-vg[-1])/max(vg[0],1e-9)*100:.1f}%；tint 常数比 = 0.376 ⇒ "
              f"剩下的下降全部来自通道扩散）")
        if DUMP:
            print("     半径       R      G      B     G/R    B/R")
            for row in rows:
                print(f"     {row[0]:.4f}  {row[1]:.3f}  {row[2]:.3f}  "
                      f"{row[3]:.3f}  {row[4]:.3f}  {row[5]:.3f}")

        # ── 判据 ③：[64] 半径单调 ───────────────────────────────────────
        rads = [0.15, 0.45, 0.85]
        cents = []
        for rv in rads:
            a = run_halo(gl, sh, LIN, 1.0, 0.5, rv, 0.4, decode_in=False) - LIN
            cents.append(channel_centroid_r(a[:, :, 0], r, idx, nb, rmax=0.45))
        check("3 [64] 半径单调（R 通道扩散半径递增）",
              all(cents[i] < cents[i + 1] for i in range(len(cents) - 1)),
              " < ".join(f"{v:.5f}" for v in cents) + f"  @ [64] = {rads}")

        # ── 判据 ④：[63] 阈值单调 ───────────────────────────────────────
        thr = [0.4, 0.6, 0.8]
        energy = []
        for tv in thr:
            a = run_halo(gl, sh, LIN, 1.0, tv, 0.45, 0.4, decode_in=False) - LIN
            energy.append(float(np.clip(a, 0, None).sum()))
        check("4 [63] 阈值单调（halo 总能量递减）",
              all(energy[i] > energy[i + 1] for i in range(len(energy) - 1)),
              " > ".join(f"{v:.1f}" for v in energy) + f"  @ [63] = {thr}")

        # ── 判据 ⑤：[65] 暖度单调 ───────────────────────────────────────
        warms = [0.0, 0.5, 1.0]
        ratios = []
        for wv in warms:
            a = run_halo(gl, sh, LIN, 1.0, 0.5, 0.45, wv, decode_in=False) - LIN
            s = np.clip(a, 0, None).sum(axis=(0, 1))
            ratios.append(float((s[1] + s[2]) / max(s[0], 1e-9)))
        check("5 [65] 暖度单调（(G+B)/R 递增 ⇒ tint 按 warmth 抬绿蓝）",
              all(ratios[i] < ratios[i + 1] for i in range(len(ratios) - 1)),
              " < ".join(f"{v:.4f}" for v in ratios) + f"  @ [65] = {warms}")

        # ── 判据 ⑥：分辨率无关性（§3.2.5）—— **R1 修正后** ───────────────
        # 背景（E4-5 实测发现）：金字塔各级是相对尺寸，但 blur 的 σ = 1.637 **texel
        # @ 该级** ⇒ 等效全分辨率 σ_px = 1.637·2^(i+1) 与图像尺寸**无关** ⇒ 原实现是
        # **固定像素尺度**（归一化半径 ∝ 1/短边；预览短边 1080 vs 成片 3072 ⇒ 差 2.8×）。
        # **R1（E4-5b，架构师裁决）**：权重峰值按 `level_shift = log2(min(w,h)/refMin)`
        # 平移 ⇒ 归一化半径 = 1.637·2^(centre+1)/refMin，**与 min(w,h) 无关** ✓。
        #
        # 测试对的选择（重要）：`refMin = 768`，测 768×1024（shift = 0，扮演"成片"）
        # 与 384×512（shift = −1，扮演"预览"）。⚠ **不能**用 512×384 配 refMin = 3072
        # —— 那给出 shift = −3 ⇒ R 通道 centre = 1.8 − 3 = **−1.2 < 0** ⇒ 权重被 level0
        # 截断（金字塔没有更小的级）⇒ 那是 R1 的**已知下限**（很小的 [64] 才触发），
        # 不是"归一化失败"。下限本身在 6c 里单独量化。
        REF = 768
        pair = ((REF, REF * 4 // 3, 1.5 * REF / W), (REF // 2, REF * 2 // 3, 1.5 * REF / W / 2))
        cents_r1, cents_old, px_r1 = [], [], []
        for (w2, h2, sg) in pair:
            lin2 = srgb_decode(point_source(w2, h2, sigma_px=sg)).astype(np.float32)
            a_r1 = run_halo(gl, sh, lin2, 1.0, 0.5, 0.45, 0.4, False, ref_min=REF) - lin2
            a_old = run_halo(gl, sh, lin2, 1.0, 0.5, 0.45, 0.4, False, ref_min=None) - lin2
            r2, i2, n2 = radial_bins(w2, h2, 40)
            cents_r1.append(channel_centroid_r(a_r1[:, :, 0], r2, i2, n2, rmax=0.45))
            cents_old.append(channel_centroid_r(a_old[:, :, 0], r2, i2, n2, rmax=0.45))
            px_r1.append(cents_old[-1] * min(w2, h2))
        rel_r1 = abs(cents_r1[0] - cents_r1[1]) / max(cents_r1[0], 1e-9)
        check("6a **R1 后归一化半径一致**（短边 768 vs 384，相对差 ≤8%）",
              rel_r1 <= 0.08,
              f"归一化 r̄_R = {cents_r1[0]:.5f} vs {cents_r1[1]:.5f}"
              f"（相对差 {rel_r1*100:.2f}%）")
        rel_old = abs(cents_old[0] - cents_old[1]) / max(cents_old[0], 1e-9)
        check("6b 修前对照：R1 关闭时归一化 r̄ 按 1/短边 翻倍（证明 6a 不是恒真）",
              rel_old >= 0.40,
              f"归一化 r̄_R = {cents_old[0]:.5f} vs {cents_old[1]:.5f}"
              f"（相对差 {rel_old*100:.1f}%；像素尺度 {px_r1[0]:.2f} vs {px_r1[1]:.2f} px 恒定）")
        # 6c：**实机等效**（预览短边 1080 vs 成片 3072）的解析预测 + 已知下限量化
        real_prev = sigma_eff_norm(0.45, 1080, 1440, 3072)
        real_still = sigma_eff_norm(0.45, 3072, 4096, 3072)
        real_prev_old = sigma_eff_norm(0.45, 1080, 1440, None)
        resid = abs(real_prev - real_still) / real_still
        gain = abs(real_prev_old - real_still) / real_still
        check("6c 实机等效解析（1080 vs 3072，[64]=0.45）：R1 把差距压到修前的 ≤1/5",
              resid <= gain / 5.0,
              f"修前 预览 {real_prev_old:.5f} = 成片的 {real_prev_old/real_still:.2f}×；"
              f"R1 后 预览 {real_prev:.5f} vs 成片 {real_still:.5f}"
              f"（残差 {resid*100:.1f}%，改善 {gain/max(resid,1e-9):.1f}×）")
        # 6d：成片路径**逐位零变化**（min == refMin ⇒ shift = log2(1) = 0）
        a_still_r1 = run_halo(gl, sh, LIN, 1.0, 0.5, 0.45, 0.4, False,
                              ref_min=float(min(W, H)))
        a_still_old = run_halo(gl, sh, LIN, 1.0, 0.5, 0.45, 0.4, False, ref_min=None)
        check("6d 成片路径逐位零变化（min == refMin ⇒ shift = 0）",
              np.array_equal(a_still_r1, a_still_old),
              f"{a_still_r1.size} 个 float 分量逐位相同（成片观感不变）")
        # 6e：已知下限（很小的 [64] 时预览 centre < 0 ⇒ 被 level0 截断）
        lo = sigma_eff_norm(0.15, 1080, 1440, 3072)
        lo_still = sigma_eff_norm(0.15, 3072, 4096, 3072)
        warn("6e R1 的已知残差（level0 截断；[64] 越小越明显）",
             f"R1 是**近似**：它平移高斯**中心**，而金字塔的尺度是离散的（2 倍步进）⇒ "
             f"中心靠近 level0 时左侧权重被截断、`E[2^i]` 偏大 ⇒ 残差。实测 @1080/3072："
             f"[64]=0.45（默认，centre_R={0.45*4*1.0 + level_shift_for(1080,1440,3072):.3f}）"
             f"残差 {abs(real_prev-real_still)/real_still*100:.1f}%；"
             f"[64]=0.15（centre_R={0.15*4*1.0 + level_shift_for(1080,1440,3072):.3f} < 0）"
             f"残差 {abs(lo-lo_still)/lo_still*100:.0f}%。"
             f"精确解 = 对 centre 求根使 `E[2^i]` 精确匹配（纯 CPU、零 GPU 成本，但会改变"
             f"预览的权重形状；成片可保持逐位不变）—— 超出架构师指定的 R1 范围，记录待拍板")

        # ── 判据 ⑦：两条链路（kFsOut vs kFsStyle）────────────────────────
        # 点光源峰值压到显示域 0.55（线性 0.267）⇒ halo 加光后线性 max 仍 ≤ 0.80，
        # 使 highlightRolloff **逐位恒等** —— 这是 U-4 口径的可比前提。
        D7 = point_source(W, H, sigma_px=1.5, peak=0.55)
        Dq = (np.round(D7 * 255.0) / 255.0).astype(np.float32)   # 8bit（编辑器输入）
        lin_q = srgb_decode(Dq).astype(np.float32)
        P = dict(strength=0.6, threshold=0.5, radius=0.45, warmth=0.4)
        halo_lin = run_halo(gl, sh, lin_q, decode_in=False, **P)   # 线性（拍照链路）
        halo_disp = run_halo(gl, sh, Dq, decode_in=True, **P)      # 显示域（编辑器链路）
        check("7a 前置：rolloff 恒等域（线性 max ≤ 0.80）",
              float(halo_lin.max()) <= 0.80,
              f"max(halo_lin) = {float(halo_lin.max()):.4f}（knee = 0.80 ⇒ "
              f"highlightRolloff 逐位恒等）")
        out_cam, out_ed = run_out_style(gl, sh, Dq, halo_lin, halo_disp)
        d7 = np.abs(out_cam.astype(np.float64) - out_ed.astype(np.float64)) * 255.0
        check("7b kFsOut(srgbDecode(D)) vs kFsStyle(D)：输出位深（8bit）内逐位相同",
              float(d7.max()) <= 1.0,
              f"max |Δ| = {d7.max():.4f}/255, 不同分量 = {int((d7 > 0).sum())}"
              f"/{d7.size}（差异来源 = halo 输出的 RGBA16F 量化与 OETF 的先后："
              f"拍照 encode(q(sum)) vs 编辑器 q(encode(sum))）")

        # ── 判据 ⑧：mutation 自检 ───────────────────────────────────────
        mut = run_halo(gl, sh, LIN, 1.0, 0.5, 0.45, 0.4, decode_in=False,
                       spread=[1.0, 1.0, 1.0]) - LIN
        mc = [channel_centroid_r(mut[:, :, c], r, idx, nb, rmax=0.45) for c in range(3)]
        check("M1 去掉通道扩散 ⇒ 判据 2a 必须变红（R = G = B）",
              not (mc[0] > mc[1] > mc[2]),
              f"r̄ = {mc[0]:.5f}/{mc[1]:.5f}/{mc[2]:.5f}（三通道相同 ⇒ 无白→橙→红）")
        mvg = [row[4] for row in radial_table(mut, W, H, nbin=16)]
        drop = (mvg[0] - mvg[-1]) / max(mvg[0], 1e-9)
        check("M2 去掉通道扩散 ⇒ 判据 2d 必须变红（G/R 几乎不随半径变）",
              drop < 0.50,
              f"G/R {mvg[0]:.4f} → {mvg[-1]:.4f}（只降 {drop*100:.1f}%，"
              f"正常通道扩散时降 99%+ ⇒ 均匀红雾）")
        rev = []
        for wv in warms:
            t = np.array([1.0, 0.24 - 0.34 * wv, 0.44 + 0.22 * wv])   # 反向映射
            a = run_halo(gl, sh, LIN, 1.0, 0.5, 0.45, wv, decode_in=False) - LIN
            s = np.clip(a, 0, None).sum(axis=(0, 1))
            s = s / np.maximum(halo_tint(wv), 1e-9) * t
            rev.append(float((s[1] + s[2]) / max(s[0], 1e-9)))
        check("M3 暖度映射反向 ⇒ 判据 5 必须变红（(G+B)/R 递减）",
              not all(rev[i] < rev[i + 1] for i in range(len(rev) - 1)),
              " > ".join(f"{v:.4f}" for v in rev) + "（反向）")
    finally:
        gl.close()

    print()
    if WARN:
        print(f"警告 {len(WARN)} 项（规格声称与实测不符，交用户拍板）：{WARN}")
    if FAIL:
        print(f"失败 {len(FAIL)} 项：{FAIL}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
