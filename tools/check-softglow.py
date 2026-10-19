#!/usr/bin/env python3
"""SG-1 柔光（soft glow / Orton bloom）—— 离线验收。

规格 = `docs/reports/soft-glow-SPEC.md`（唯一权威）；参考实现 = darktable
`src/iop/bloom.c` 的 Orton 支。算法（规格 §2）：
    ① h = max(0, luma − threshold)/(1 − threshold)   ← kFsHaloThr 的 glow 模式
    ② b = gaussian_blur(h, radius)（**通道无关、单半径**）← 共享 halation 的金字塔
    ③ out = 1 − (1 − c)(1 − b·strength)（**Screen**）  ← kFsOut / kFsStyle 的显示域段

判据（任务卡 SG-1 验收判据 1..7）：
  ① **[95] = 0 逐位零回归 + 零 pass 零纹理**：静态（runGlow 的 `amount <= 0` 提前
     return 必须**先于** `ensureHaloTargets`）+ 真跑（[95] = 0 时完整链路输出 == 不跑
     柔光的输出，逐位；链本身返回 0 张纹理、0 次 draw）。
  ② **三通道同半径（通道差 = 0）+ 高光"发白外扩"**：逐通道等效半径 r̄_R = r̄_G = r̄_B
     （**与 halation 判据 2a 相反** —— 那里要求 r̄_R > r̄_G > r̄_B），并给"半径-亮度
     曲线"（外扩 = 高光半径外仍高于无柔光基线），以及 Screen 保高光不过曝的对照。
  ③ **三参数各自单调**：[95] 强度 → 增量能量递增；[96] 半径 → r̄ 递增；[97] 阈值 →
     总能量递减。
  ④ **两条链路逐位相同**：`kFsOut(srgbDecode(D))` vs `kFsStyle(D)`（U-4 口径，8bit）。
  ⑤ **正交性（规格 §4）**：与 [78] 清晰度同时开不打架（两个方向的增量都还在）；
     与 halation 同时开可分辨（红色外晕与白色柔光各自仍在，且关掉任一项另一项仍在）。
  ⑥ **共享金字塔**（规格 §5）：柔光**不新分配任何纹理**（复用 tex_halo_[0..4] 与
     tex_halo_tmp_），只多 1 个合成 pass ⇒ 16 个 pass = 15 共享 + 1 独有。
  ⑦ **mutation 自检**（4 项）：篡改 shader 源码文本后判据必须变红（证明判据非恒真）。

为什么真跑而不是 numpy 直译：判据必须来自引擎源码本身（ADR §1.4 / §6.5）。本脚本沿用
`tools/engine-shader-offline.py` 的 GL 执行器（与 check-halation / check-toeshoulder /
check-u4-camera-display-domain / check-style-passthrough 同一套封装：只做
`#version 300 es → 330 core` 的机械改写），shader 源码**逐字取自 FrameRenderer.cpp**。

⚠ 离线能决策什么、不能决策什么（规格 §6.2/§6.3）：**形态 / 半径 / 阈值 / 通道无关性
可决策**（柔光的 Screen 落点在显示域，离线链与真机同域）；**性能不可决策**（桌面 GL 与
Mali 无关）；**"目视像不像柔光"只能真机**（规格 §6.7）。

用法：python3 tools/check-softglow.py [--dump]
依赖：moderngl + glfw + numpy（同 check-halation.py）。
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
SIGMA = 0.95
SPREAD_HALO = np.array([1.0, 0.6, 0.32], dtype=np.float64)   # 乳剂（halation 用）


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
    print(f"WARN {name}: {detail}")
    WARN.append(name)


# ────────────────────────────────────────── 数学（与 C++ / shader 逐字同式）
def srgb_decode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def srgb_encode(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, 12.92 * c, 1.055 * c ** (1.0 / 2.4) - 0.055)


def srgb_to_linear1(c):
    """[97] 的单位是显示域 sRGB 编码值 ⇒ 进 shader 前换算（C++ 的 srgbToLinear1）。"""
    c = min(max(c, 0.0), 1.0)
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def glow_weights(radius01, level_shift=0.0, spread=None):
    """`FrameRenderer::haloWeights`（SG-1 起 spread 是参数）。

    柔光传 spread = {1,1,1} ⇒ **三通道同一个中心** ⇒ 单半径（规格 §1）。
    halation 传 SPREAD_HALO ⇒ 逐通道不同中心（对照 / mutation 用）。
    """
    sp = np.ones(3) if spread is None else np.asarray(spread, dtype=np.float64)
    out = np.zeros((LEVELS, 3), dtype=np.float64)
    for c in range(3):
        centre = radius01 * (LEVELS - 1) * sp[c] + level_shift
        raw = np.array([np.exp(-((i - centre) ** 2) / (2 * SIGMA ** 2))
                        for i in range(LEVELS)])
        s = raw.sum()
        if s <= 0.0:
            nearest = min(max(int(round(centre)), 0), LEVELS - 1)
            out[:, c] = [1.0 if i == nearest else 0.0 for i in range(LEVELS)]
            continue
        out[:, c] = raw / s
    return out


def halo_tint(warmth):
    return np.array([1.0, 0.24 + 0.34 * warmth, 0.44 - 0.22 * warmth])


def level_shift_for(w, h, ref_min):
    return 0.0 if ref_min is None else float(np.log2(min(w, h) / float(ref_min)))


# ────────────────────────────────────────── GL 编排（与 C++ 同序）
def make_gl(eso, w, h):
    import moderngl

    class GlowGL(eso.GL):
        def __init__(self, ww, hh):
            super().__init__(ww, hh)
            self._progs = {}
            self.ndraw = 0          # 判据 ①/⑥：实际执行的 draw 次数

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
            self.ndraw += 1
            data = fbo.read(components=4, dtype="f2")
            return np.frombuffer(data, dtype=np.float16).reshape(th, tw, 4).astype(np.float32)

    return GlowGL(w, h)


def mktex(gl, arr, pool=None):
    import moderngl
    h, w = arr.shape[:2]
    rgba = np.ones((h, w, 4), dtype=np.float16)
    if arr.ndim == 2:
        rgba[:, :, 0] = arr.astype(np.float16)
    else:
        rgba[:, :, :3] = arr.astype(np.float16)
    t = gl.ctx.texture((w, h), 4, rgba.tobytes(), dtype="f2")
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)   # 与 C++ makeTex 一致
    t.repeat_x = False
    t.repeat_y = False
    if pool is not None:
        pool.append(t)
    return t


def shader_src(src, name):
    m = re.search(r'const char\* ' + name + r' = R"\((.*?)\)";', src, re.S)
    return m.group(1) if m else ""


def run_glow_chain(gl, sh, src, strength, radius, threshold, decode_in,
                   ref_min=None, weights_override=None):
    """与 C++ `runHaloPyramid(glow_mode=1)` + `runGlow` 逐序对应（16 个 pass）。

    src: (h, w, 3) float32。decode_in=False ⇒ 线性显示域；True ⇒ 显示域 sRGB。
    返回 (b, ntex)：b = 1/2 分辨率**标量**柔光图（线性显示域）；[95] = 0 时 (None, 0)。

    ⚠ C++ 的 blur 用**一张最大级 tmp** + uScale 把更小级的归一化 UV 缩到已写入的左下
      角子矩形；这里用逐级独立纹理 + uScale = (1,1)（check-halation.py 同款，数学等价）。
    """
    if strength <= 0.0:
        return None, 0                      # 整链旁路：零 pass 零纹理
    h, w = src.shape[:2]
    pool = []
    thr_lin = srgb_to_linear1(min(max(threshold, 0.0), 0.999))
    lv = [(max(2, w >> (i + 1)), max(2, h >> (i + 1))) for i in range(LEVELS)]
    try:
        def thr_pass(texture, size, texel, decode, thr_on, glow_on):
            return gl.run(sh["kFsHaloThr"], {
                "uTexel": texel, "uDecodeIn": 1.0 if decode else 0.0,
                "uThrOn": 1.0 if thr_on else 0.0, "uThrLin": thr_lin,
                "uKnee": max(0.02, thr_lin * 0.55), "uGlowOn": glow_on,
            }, {"uSrc": texture}, size=size)[:, :, :3]

        # ① 柔光提取（标量）+ 手写 2×2 盒式降到 1/2
        levels = [thr_pass(mktex(gl, src, pool), lv[0], (1.0 / w, 1.0 / h),
                           decode_in, True, 1.0)]
        # ② 逐级降采样（同一 program，三个门控全关）
        for i in range(1, LEVELS):
            pw, ph = lv[i - 1]
            levels.append(thr_pass(mktex(gl, levels[i - 1], pool), lv[i],
                                   (1.0 / pw, 1.0 / ph), False, False, 0.0))
        # ③ 逐级可分离高斯（先横后纵）
        for i in range(LEVELS):
            wi, hi = lv[i]
            t = gl.run(sh["kFsHaloBlur"],
                       {"uDir": (1.0 / wi, 0.0), "uScale": (1.0, 1.0)},
                       {"uSrc": mktex(gl, levels[i], pool)}, size=(wi, hi))[:, :, :3]
            levels[i] = gl.run(sh["kFsHaloBlur"],
                               {"uDir": (0.0, 1.0 / hi), "uScale": (1.0, 1.0)},
                               {"uSrc": mktex(gl, t, pool)}, size=(wi, hi))[:, :, :3]
        # ④ 单半径加权合成（1/2 分辨率；C++ 写 tex_halo_tmp_ ⇒ 不新增纹理）
        wgt = glow_weights(radius, level_shift_for(w, h, ref_min))
        if weights_override is not None:      # mutation M2：半径失效
            wgt = np.repeat(np.asarray(weights_override, dtype=np.float64)[:, None],
                            3, axis=1)
        uni = {f"uS{i}": float(wgt[i][0]) for i in range(LEVELS)}
        texs = {f"uG{i}": mktex(gl, levels[i], pool) for i in range(LEVELS)}
        b = gl.run(sh["kFsGlowSum"], uni, texs, size=lv[0])
        return b[:, :, 0], len(pool)
    finally:
        for t in pool:
            t.release()


def run_halo_chain(gl, sh, src, strength, threshold, radius, warmth, decode_in):
    """E4-5 halation 链（Python 侧编排，与 `runHalation` 逐序对应）—— 判据 ⑤b 用。"""
    if strength <= 0.0:
        return src.copy()
    h, w = src.shape[:2]
    lv = [(max(2, w >> (i + 1)), max(2, h >> (i + 1))) for i in range(LEVELS)]
    thr_lin = srgb_to_linear1(threshold)
    knee = max(0.02, thr_lin * 0.55)
    pool = []
    try:
        def thr_pass(texture, size, texel, decode, thr_on):
            return gl.run(sh["kFsHaloThr"], {
                "uTexel": texel, "uDecodeIn": 1.0 if decode else 0.0,
                "uThrOn": 1.0 if thr_on else 0.0, "uThrLin": thr_lin,
                "uKnee": knee, "uGlowOn": 0.0,
            }, {"uSrc": texture}, size=size)[:, :, :3]

        levels = [thr_pass(mktex(gl, src, pool), lv[0], (1.0 / w, 1.0 / h),
                           decode_in, True)]
        for i in range(1, LEVELS):
            pw, ph = lv[i - 1]
            levels.append(thr_pass(mktex(gl, levels[i - 1], pool), lv[i],
                                   (1.0 / pw, 1.0 / ph), False, False))
        for i in range(LEVELS):
            wi, hi = lv[i]
            t = gl.run(sh["kFsHaloBlur"],
                       {"uDir": (1.0 / wi, 0.0), "uScale": (1.0, 1.0)},
                       {"uSrc": mktex(gl, levels[i], pool)}, size=(wi, hi))[:, :, :3]
            levels[i] = gl.run(sh["kFsHaloBlur"],
                               {"uDir": (0.0, 1.0 / hi), "uScale": (1.0, 1.0)},
                               {"uSrc": mktex(gl, t, pool)}, size=(wi, hi))[:, :, :3]
        wgt = glow_weights(radius, 0.0, SPREAD_HALO)
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


def run_out(gl, sh, lin, b, strength, texel, clarity=0.0):
    """完整 kFsOut（拍照链路）。lin = 线性显示域输入；b = 柔光图（或 None）。"""
    h, w = lin.shape[:2]
    pool = []
    dummy = mktex(gl, np.zeros((1, 1, 3), dtype=np.float32), pool)
    uni = {"uPx": (1.0 / w, 1.0 / h), "uFlipY": 0.0, "uZebra": 0.0,
           "uSharpen": 0.0, "uGrainAmount": 0.0, "uGrainSeed": 0.0,
           "uGrainZoom": 1.0, "uGrainNorm": 1.0 / min(w, h),
           "uLutOn": 0.0, "uLutStrength": 0.0, "uVigBrightness": 0.0,
           "uClarity": clarity, "uClarStep": (1.0 / w, 1.0 / h),
           "uToneOn": 0.0, "uFade": 0.0,
           "uGlowOn": 1.0 if b is not None else 0.0,
           "uGlowStrength": float(strength), "uGlowTexel": texel}
    try:
        out = gl.run(sh["kFsOut"], uni, {
            "uTex": mktex(gl, lin, pool), "uGrainLut": dummy, "uLut": dummy,
            "uGlow": mktex(gl, b if b is not None else np.zeros((2, 2), np.float32),
                           pool)}, size=(w, h))
        return out[:, :, :3]
    finally:
        for t in pool:
            t.release()


def run_style(gl, sh, disp, b, strength, texel, clarity=0.0):
    """完整 kFsStyle（编辑器链路）。disp = 显示域输入。"""
    h, w = disp.shape[:2]
    pool = []
    dummy = mktex(gl, np.zeros((1, 1, 3), dtype=np.float32), pool)
    uni = {"uEv": 0.0, "uWbGain": (1.0, 1.0, 1.0), "uExpWbOn": 0.0,
           "uContrast": 1.0, "uContrastOn": 0.0, "uSatOn": 0.0, "uCurveOn": 0.0,
           "uLocalOn": 0.0, "uSplit": (0.0, 0.0), "uMono": 0.0,
           "uCropOrigin": (0.0, 0.0), "uCropSize": (1.0, 1.0), "uCropRot": 0.0,
           "uGrainAmount": 0.0, "uGrainSeed": 0.0, "uGrainZoom": 1.0,
           "uGrainNorm": 1.0 / min(w, h), "uLutOn": 0.0, "uLutStrength": 0.0,
           "uVigBrightness": 0.0, "uClarity": clarity,
           "uClarStep": (1.0 / w, 1.0 / h), "uToneOn": 0.0, "uFade": 0.0,
           "uGlowOn": 1.0 if b is not None else 0.0,
           "uGlowStrength": float(strength), "uGlowTexel": texel}
    try:
        out = gl.run(sh["kFsStyle"], uni, {
            "uTex": mktex(gl, disp, pool), "uGrainLut": dummy, "uLut": dummy,
            "uGlow": mktex(gl, b if b is not None else np.zeros((2, 2), np.float32),
                           pool)}, size=(w, h))
        return out[:, :, :3]
    finally:
        for t in pool:
            t.release()


# ────────────────────────────────────────── 场景与分析
def scene_point(w, h, bg=0.02, peak=0.88, r=12.0):
    """夜景点光源：暗背景 + 中心亮斑（**显示域** sRGB 编码值，三通道相同）。

    ⚠ 参数是**判据的前提**，三个都不能随手改（本脚本第一版在这里踩了两次坑）：
      · peak = 0.88（不是 1.0）：Screen 在 c = 1 处恒等（`1-(1-1)(1-b) = 1` —— 这正是
        它"保高光不过曝"的定义）⇒ 用**饱和**点光源测外扩会得到"零增量"的假阴性。
      · peak 还要让**线性**峰值 ≤ 0.80：kFsOut 的 highlightRolloff 在 0.80 以上不再
        恒等，而 kFsStyle 没有滚降 ⇒ 判据 4（两条链路逐位相同）会测到那条**已知差异**
        而不是柔光本身（E4-5 判据 7a 用同一条前提）。0.88 显示域 ⇒ 线性 0.557 ✓
      · peak **必须高于 [97] 阈值**（否则提取恒 0、判据恒真）。
      · r = 12px（不是 3px）：金字塔第一级是**手写 2×2 盒式降采样**，半径 3px 的斑
        在 4-tap 平均后峰值被摊到阈值以下 ⇒ 同样得到"零增量"的假阴性。
    """
    xs = np.arange(w) + 0.5 - w / 2.0
    ys = np.arange(h) + 0.5 - h / 2.0
    X, Y = np.meshgrid(xs, ys)
    d = np.sqrt(X ** 2 + Y ** 2)
    spot = np.clip(1.0 - d / r, 0.0, 1.0) ** 0.5
    return np.clip(bg + (peak - bg) * spot, 0.0, 1.0)[:, :, None] * np.ones((1, 1, 3))


def scene_detail(w, h, seed=7):
    """含局部细节的图（判据 ⑤a：与 [78] 清晰度同时开不打架）。"""
    rng = np.random.default_rng(seed)
    base = 0.35 + 0.25 * np.sin(np.arange(w)[None, :] / 5.0) * \
        np.cos(np.arange(h)[:, None] / 7.0)
    base = base + 0.03 * rng.standard_normal((h, w))
    return np.clip(base, 0.02, 0.98)[:, :, None] * np.ones((1, 1, 3))


def radial_profile(img, w, h, nbin=18, base=None):
    """半径-亮度曲线。返回 [(归一化 r, 值, 基线值)]。"""
    xs = np.arange(w) + 0.5 - w / 2.0
    ys = np.arange(h) + 0.5 - h / 2.0
    X, Y = np.meshgrid(xs, ys)
    d = np.sqrt(X ** 2 + Y ** 2) / min(w, h)
    wl = np.array([0.2126, 0.7152, 0.0722])
    luma = img @ wl
    lb = None if base is None else base @ wl
    edges = np.linspace(0.0, d.max(), nbin + 1)
    rows = []
    for i in range(nbin):
        m = (d >= edges[i]) & (d < edges[i + 1])
        if not m.any():
            continue
        rows.append((float(d[m].mean()), float(luma[m].mean()),
                     None if lb is None else float(lb[m].mean())))
    return rows


def chan_radius(img, w, h, base):
    """逐通道**等效扩散半径** r̄_c = Σ r·|Δ_c| / Σ|Δ_c|（与 check-halation 判据 2a 同式）。

    柔光的判据是 r̄_R = r̄_G = r̄_B（通道无关）；halation 是 r̄_R > r̄_G > r̄_B。
    """
    xs = np.arange(w) + 0.5 - w / 2.0
    ys = np.arange(h) + 0.5 - h / 2.0
    X, Y = np.meshgrid(xs, ys)
    d = np.sqrt(X ** 2 + Y ** 2) / min(w, h)
    out = []
    for c in range(3):
        delta = np.abs(img[:, :, c] - base[:, :, c])
        tot = delta.sum()
        out.append(float((d * delta).sum() / tot) if tot > 1e-12 else 0.0)
    return np.array(out)


def local_contrast(img):
    """局部对比 = 高频能量（与 3×3 盒式均值的差）。"""
    k = np.zeros_like(img)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            k += np.roll(np.roll(img, dy, 0), dx, 1)
    return float(np.abs(img - k / 9.0).mean())


def mutate(sh, name, old, new):
    """mutation 自检：篡改 shader 源码文本（引擎源码的机械改写，只用于证明判据非恒真）。"""
    m = dict(sh)
    if old not in m[name]:
        raise SystemExit(f"mutation 目标文本不在 {name} 里：{old!r}")
    m[name] = m[name].replace(old, new)
    return m


# ────────────────────────────────────────── 静态检查（源码文本）
def check_static(src):
    m = re.search(r"GLuint FrameRenderer::runGlow\(.*?\n}", src, re.S)
    check("S1 runGlow 存在", m is not None, "找到函数体" if m else "未找到")
    body = m.group(0) if m else ""
    if m:
        i_ret = body.find("if (amount <= 0.0f || src_tex == 0) return 0;")
        i_ens = body.find("ensureHaloTargets(")
        check("S1 [95]=0 提前 return 先于纹理分配（零 pass 零纹理）",
              i_ret >= 0 and i_ens > i_ret,
              f"return@{i_ret} < ensureHaloTargets@{i_ens}")
        check("S1 柔光图复用 tex_halo_tmp_（**不新分配任何纹理**，规格 §5）",
              "tex_halo_tmp_" in body and "makeTex(" not in body
              and "fbo_halo_tmp_" in body,
              "runGlow 内零 makeTex；输出写 fbo_halo_tmp_")
        check("S2 柔光独有 pass = 1（合成 kFsGlowSum）",
              body.count("glDrawArrays(GL_TRIANGLES, 0, 3);") == 1
              and "prog_glow_sum_" in body,
              "runGlow 内 1 次 draw；其余 15 次由共享金字塔 runHaloPyramid 发出")
    mp = re.search(r"bool FrameRenderer::runHaloPyramid\(.*?\n}", src, re.S)
    pyr = mp.group(0) if mp else ""
    check("S2 共享金字塔 = 15 个 pass（1 提取 + 4 降采样 + 10 模糊）",
          pyr.count("glDrawArrays(GL_TRIANGLES, 0, 3);") == 4
          and "for (int i = 1; i < kHaloLevels; ++i)" in pyr
          and pyr.count("for (int i = 0; i < kHaloLevels; ++i)") == 1,
          "4 个 draw 语句（提取 1 + 降采样循环 ×4 + 模糊循环 ×2 层 ×5 = 15 次执行）")
    check("S3 柔光提取是**标量**（三通道同值 ⇒ 通道无关，规格 §1）",
          "float h = max(0.0, luma(lin) - uThrLin)" in src
          and "oColor = vec4(h, h, h, 1.0);" in src,
          "kFsHaloThr 的 glow 分支 = vec4(h,h,h,1)")
    check("S3 halation 提取仍是**彩色**（lin × w ⇒ 通道相关半径的载体）",
          "oColor = vec4(lin * w, 1.0);" in src,
          "kFsHaloThr 的 halation 分支 = vec4(lin*w, 1)")
    check("S4 合成权重是**标量**（uS0..uS4；halation 是 vec3 的 uW0..uW4）",
          all(f"uniform float uS{i};" in src for i in range(5))
          and all(f"uniform vec3 uW{i};" in src for i in range(5)),
          "kFsGlowSum 用 5 个 float，kFsHaloMix 用 5 个 vec3")
    n_screen = src.count("c = 1.0 - (1.0 - c) * (1.0 - gb);")
    check("S5 Screen 混合在两条链路的**显示域段**（褪色之后、暗角之前）",
          n_screen == 2, f"kFsOut / kFsStyle 各 1 处（实测 {n_screen}）")
    check("S6 柔光链不做 shoulder / 不 tint（Screen 保高光不过曝）",
          "highlightRolloff" not in body and "uTint" not in body,
          "runGlow 内无 highlightRolloff、无 tint")
    gs = shader_src(src, "kFsGlowSum")
    check("S6 GLSL 三禁（kFsGlowSum）",
          "float[" not in gs and "for (" not in gs and "#define" not in gs,
          "kFsGlowSum 无数组初始化器 / 无循环 / 无宏")
    return pyr


# ────────────────────────────────────────── 主流程
def main():
    global DUMP
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true", help="打印中间数组统计")
    args = ap.parse_args()
    DUMP = args.dump

    eso = load_eso()
    src = open(FR, encoding="utf-8", errors="replace").read()
    names = ("kFsHaloThr", "kFsHaloBlur", "kFsGlowSum", "kFsHaloMix",
             "kFsOut", "kFsStyle")
    sh = {n: shader_src(src, n) for n in names}
    miss = [n for n, v in sh.items() if not v]
    check("S0 shader 提取", not miss, f"缺 {miss}" if miss else "6 个 shader 全部找到")
    if miss:
        return 1
    check_static(src)

    W, H = 192, 128
    THR = 0.45          # 点光源场景用的 [97]（见 scene_point 的说明）
    gl = make_gl(eso, W, H)
    D = scene_point(W, H)                       # 显示域点光源
    lin = srgb_decode(D)                        # 线性显示域
    texel = (1.0 / (W // 2), 1.0 / (H // 2))    # 柔光图 = 1/2 分辨率

    def build_b(strength=0.6, radius=0.35, threshold=0.70, decode_in=False,
                src_img=None, sh_override=None):
        return run_glow_chain(gl, sh_override or sh, lin if src_img is None else src_img,
                              strength, radius, threshold, decode_in)

    # ══ 判据 ① 零回归 + 零 pass 零纹理 ══════════════════════════════════════
    gl.ndraw = 0
    b_off, ntex_off = build_b(strength=0.0)
    check("1a [95]=0 ⇒ 整链旁路：零 pass、零纹理",
          b_off is None and ntex_off == 0 and gl.ndraw == 0,
          f"返回 {b_off}、纹理 {ntex_off} 张、draw {gl.ndraw} 次")
    out_ref = run_out(gl, sh, lin, None, 0.0, texel)
    out_off = run_out(gl, sh, lin, None, 0.0, texel)
    d = np.abs(out_ref - out_off)
    check("1b [95]=0 逐位零回归（完整 kFsOut 链路）",
          float(d.max()) == 0.0,
          f"max|Δ| = {d.max():.6f}（{d.size} 个分量）")
    gl.ndraw = 0
    b_on, ntex_on = build_b(strength=0.6, threshold=THR)
    # ⑥ 共享金字塔：**pass 数**是判据（15 共享 + 1 独有）；**纹理复用**由静态检查
    #    （runGlow 内零 makeTex + 写 fbo_halo_tmp_）与 C++ 的 ensureHaloTargets 保证。
    #    Python 侧每次 gl.run 都新建一张中间纹理 ⇒ 计数 20 只是编排产物，不是引擎行为。
    check("1c 共享金字塔的 pass 数 = 16（15 共享 + 1 柔光独有合成）",
          gl.ndraw == 16,
          f"draw {gl.ndraw} 次 = 提取 1 + 降采样 4 + 模糊 10 + 合成 1"
          f"（Python 编排另建 {ntex_on} 张中间纹理；引擎侧 = 5 级 + tmp 共 6 张，零新增）")

    # ══ 判据 ② 三通道同半径 + 发白外扩 ══════════════════════════════════════
    out_glow = run_out(gl, sh, lin, b_on, 0.6, texel)
    base = out_ref
    r_ch = chan_radius(out_glow, W, H, base)
    check("2a **三通道同半径**（通道差 = 0；与 halation 判据 2a 相反）",
          # 期 B：kFsOut 编码前引入 3×3 域收口矩阵（workToSrgb）后，中性输入的三通道
          # 产生 ~1e-6 的浮点微差（矩阵行和 = 1 ⇒ 理论恒等；实测极差 2.30e-06，折算
          # 8bit 下 0.0006 LSB，不可见）。阈值放到 1e-4 —— 仍比"真通道相关"的 M1
          # 对照值（0.319）小 3 个数量级 ⇒ 检出能力不变（理由见期 B 归档文档 §8）。
          float(np.ptp(r_ch)) < 1e-4,
          f"r̄ = R {r_ch[0]:.5f} / G {r_ch[1]:.5f} / B {r_ch[2]:.5f}，"
          f"极差 = {np.ptp(r_ch):.2e}")
    w_halo = glow_weights(0.45, 0.0, SPREAD_HALO)
    peaks = [int(w_halo[:, c].argmax()) for c in range(3)]
    check("2a-对照 halation 的 spread 给出**不同**峰值级（判据非恒真）",
          len(set(peaks)) >= 2,
          f"峰值级 R/G/B = {peaks}（柔光用 spread={1,1,1} ⇒ 三级同峰值）")
    prof = radial_profile(out_glow, W, H, base=base)
    print("     半径-亮度曲线（归一化半径 | 柔光输出 | 无柔光基线 | Δ）：")
    for r, v, vb in prof:
        print(f"       {r:.4f} | {v:.5f} | {vb:.5f} | {v - vb:+.5f}")
    outer = [(r, v - vb) for r, v, vb in prof if r > 0.05]
    n_pos = sum(1 for _, dv in outer if dv > 0.002)
    check("2b 高光**外扩**（斑外 ≥3 个半径箱仍高于无柔光基线）",
          n_pos >= 3,
          f"斑外箱数 {len(outer)}，Δ > 0.002 的有 {n_pos} 个；最远正增量 @r = "
          f"{max([r for r, dv in outer if dv > 0.002], default=float('nan')):.4f}")
    inc = out_glow - base
    msk = np.abs(inc).max(axis=2) > 0.01
    ratio = inc[msk].mean(axis=0) if msk.any() else np.zeros(3)
    check("2c 增量的通道比 ≈ 1（**发白**而非染色：Screen 逐通道同式 + 标量 b）",
          msk.any() and float(ratio.min() / max(ratio.max(), 1e-9)) > 0.85,
          f"平均增量 R/G/B = {ratio[0]:.5f}/{ratio[1]:.5f}/{ratio[2]:.5f}"
          f"（min/max = {ratio.min() / max(ratio.max(), 1e-9):.4f}）")
    c_hi = np.array([1.0, 0.90, 0.80], dtype=np.float64)
    bb = 0.30
    scr = 1.0 - (1.0 - c_hi) * (1.0 - bb)
    adv = np.clip(c_hi + bb, 0.0, 1.0)
    check("2d Screen 保高光不过曝（加法会把通道差抹平 = 高光死白）",
          float(np.ptp(scr)) > 0.05 and float(np.ptp(adv)) < 1e-9,
          f"c = {c_hi.tolist()}, b·s = {bb} ⇒ Screen 通道差 = {np.ptp(scr):.4f}、"
          f"加法 = {np.ptp(adv):.4f}（截断成全白）")

    # ══ 判据 ③ 三参数单调 ══════════════════════════════════════════════════
    e_str, r_rad, e_thr = [], [], []
    for s in (0.2, 0.5, 0.9):
        bi, _ = build_b(strength=s)
        e_str.append(float(np.abs(run_out(gl, sh, lin, bi, s, texel) - base).sum()))
    check("3a [95] 强度单调（增量能量递增）",
          e_str[0] < e_str[1] < e_str[2],
          f"{e_str[0]:.2f} < {e_str[1]:.2f} < {e_str[2]:.2f}"
          f"  @ [95] = [0.2, 0.5, 0.9]")
    for r in (0.15, 0.45, 0.85):
        bi, _ = build_b(radius=r, threshold=THR)
        r_rad.append(float(chan_radius(run_out(gl, sh, lin, bi, 0.6, texel),
                                       W, H, base).mean()))
    check("3b [96] 半径单调（等效扩散半径递增）",
          r_rad[0] < r_rad[1] < r_rad[2],
          f"{r_rad[0]:.5f} < {r_rad[1]:.5f} < {r_rad[2]:.5f}"
          f"  @ [96] = [0.15, 0.45, 0.85]")
    for t in (0.30, 0.60, 0.85):
        bi, _ = build_b(threshold=t)
        e_thr.append(float(np.abs(run_out(gl, sh, lin, bi, 0.6, texel) - base).sum()))
    check("3c [97] 阈值单调（总能量递减）",
          e_thr[0] > e_thr[1] > e_thr[2],
          f"{e_thr[0]:.2f} > {e_thr[1]:.2f} > {e_thr[2]:.2f}"
          f"  @ [97] = [0.30, 0.60, 0.85]")

    # ══ 判据 ④ 两条链路逐位相同 ════════════════════════════════════════════
    check("4-前置 rolloff 恒等域（线性 max ≤ 0.80 ⇒ kFsOut 不压缩、kFsStyle 无滚降）",
          float(lin.max()) <= 0.80,
          f"lin max = {lin.max():.4f}（点光源 peak = 0.88 显示域）")
    b_style, _ = build_b(strength=0.6, threshold=THR, decode_in=True, src_img=D)
    o_out = run_out(gl, sh, lin, b_on, 0.6, texel)
    o_sty = run_style(gl, sh, D, b_style, 0.6, texel)
    q_out = np.clip(np.round(o_out * 255.0), 0, 255)
    q_sty = np.clip(np.round(o_sty * 255.0), 0, 255)
    dq = np.abs(q_out - q_sty)
    df = np.abs(o_out - o_sty)
    check("4 kFsOut(srgbDecode(D)) vs kFsStyle(D)：输出位深（8bit）内逐位相同",
          float(dq.max()) <= 1.0,
          f"max|Δ| = {dq.max():.0f}/255（浮点 {df.max() * 255:.3f}/255），"
          f"差 1 LSB 的分量 = {int((dq > 0).sum())}/{dq.size}；"
          f"来源 = 两条链路的柔光图 b 各自经一次 RGBA16F 往返"
          f"（拍照 q(srgbDecode(D)) vs 编辑器 srgbDecode(q(D))）—— 与 E4-5 halation "
          f"判据 7b 同型（那里 max|Δ| = 0.249/255）")

    # ══ 判据 ⑤ 正交性 ══════════════════════════════════════════════════════
    Dd = scene_detail(W, H)
    lind = srgb_decode(Dd)
    # ⚠ [97] = 0.30（不是默认 0.70）：detail 场景亮度 ~0.35 ⇒ 默认阈值下提取恒 0，
    #    判据会变成"恒真"。正交性判据只需两者都**真的生效**，阈值的绝对取值无关。
    b_d, _ = build_b(strength=0.6, threshold=0.30, src_img=lind)
    o0 = run_out(gl, sh, lind, None, 0.0, texel, clarity=0.0)
    o_cl = run_out(gl, sh, lind, None, 0.0, texel, clarity=0.6)
    o_gl = run_out(gl, sh, lind, b_d, 0.6, texel, clarity=0.0)
    o_both = run_out(gl, sh, lind, b_d, 0.6, texel, clarity=0.6)
    lc = [local_contrast(x) for x in (o0, o_cl, o_gl, o_both)]
    gain_cl = lc[1] - lc[0]                 # 清晰度单独的局部对比增益
    gain_cl_g = lc[3] - lc[2]               # 柔光同时开时清晰度的增益
    amp_g = float(np.abs(o_gl - o0).mean())
    amp_g_cl = float(np.abs(o_both - o_cl).mean())
    check("5a 与 [78] 清晰度同时开不打架（两个方向的增量都还在）",
          gain_cl > 0 and gain_cl_g > 0 and amp_g > 0 and amp_g_cl > 0,
          f"清晰度局部对比增益：单独 {gain_cl:+.6f} / 与柔光同开 {gain_cl_g:+.6f}；"
          f"柔光平均增量：单独 {amp_g:.6f} / 与清晰度同开 {amp_g_cl:.6f}")

    # 与 halation 可分辨（规格 §1 的判据）：只 halo / 只 glow / 都开
    halo_lin = run_halo_chain(gl, sh, lin, 0.8, 0.70, 0.45, 0.40, False)
    o_h = run_out(gl, sh, halo_lin, None, 0.0, texel)
    o_b = run_out(gl, sh, halo_lin, b_on, 0.6, texel)
    inc_h = o_h - base
    inc_g = o_gl - base
    inc_b_h = o_b - o_gl                # 都开 − 只 glow = halation 的贡献
    inc_b_g = o_b - o_h                 # 都开 − 只 halo = 柔光的贡献
    mh = np.abs(inc_h).max(axis=2) > 0.01
    rh = inc_h[mh].mean(axis=0) if mh.any() else np.zeros(3)
    mg = np.abs(inc_g).max(axis=2) > 0.01
    rg = inc_g[mg].mean(axis=0) if mg.any() else np.zeros(3)
    r_halo = chan_radius(o_h, W, H, base)
    check("5b-① 只开 halation ⇒ **红扩散最远**（r̄_R > r̄_G > r̄_B = 红色外晕）",
          mh.any() and r_halo[0] > r_halo[1] > r_halo[2],
          f"r̄ = R {r_halo[0]:.5f} > G {r_halo[1]:.5f} > B {r_halo[2]:.5f}"
          f"（**与柔光判据 2a 的 r̄_R = r̄_G = r̄_B 相反** ⇒ 两者可分辨）")
    check("5b-② 只开柔光的增量**发白**（三通道同向 ⇒ 白色柔光）",
          mg.any() and float(rg.min() / max(rg.max(), 1e-9)) > 0.85,
          f"增量 R/G/B = {rg[0]:.5f}/{rg[1]:.5f}/{rg[2]:.5f}（min/max = "
          f"{rg.min() / max(rg.max(), 1e-9):.3f}）")
    check("5b-③ 关掉任一项另一项仍在（两个差值都显著非零）",
          float(np.abs(inc_b_h).mean()) > 1e-4 and float(np.abs(inc_b_g).mean()) > 1e-4,
          f"|都开 − 只柔光| 均值 = {np.abs(inc_b_h).mean():.5f}（halation 仍在）；"
          f"|都开 − 只光晕| 均值 = {np.abs(inc_b_g).mean():.5f}（柔光仍在）")

    # ══ 判据 ⑦ mutation 自检（篡改 shader 源码文本，判据必须变红）═════════
    # M1：让柔光图**通道相关**（G/B 用"移一级"的权重 = 不同半径）+ 混合段逐通道使用
    # ⇒ 判据 2a（通道差 = 0）必须变红。这正是"如果柔光做了通道扩散"（halation 的行为）。
    sh_m1 = mutate(sh, "kFsGlowSum",
                   "    oColor = vec4(b, b, b, 1.0);",
                   "    float bg = texture(uG1, vUV0).r * uS0"
                   " + texture(uG2, vUV0).r * uS1 + texture(uG3, vUV0).r * uS2"
                   " + texture(uG4, vUV0).r * uS3 + texture(uG0, vUV0).r * uS4;\n"
                   "    oColor = vec4(b, bg, bg, 1.0);")
    sh_m1 = mutate(sh_m1, "kFsOut",
                   "        float gb = glowFetch(uv) * uGlowStrength;\n"
                   "        c = 1.0 - (1.0 - c) * (1.0 - gb);",
                   "        vec3 gb = vec3(glowFetch(uv), texture(uGlow, uv).g,"
                   " texture(uGlow, uv).g) * uGlowStrength;\n"
                   "        c = 1.0 - (1.0 - c) * (1.0 - gb);")
    b_m1, _ = run_glow_chain(gl, sh_m1, lin, 0.6, 0.35, THR, False)
    r_m1 = chan_radius(run_out(gl, sh_m1, lin, b_m1, 0.6, texel), W, H, base)
    check("M1 柔光图改成通道相关（G/B 用移一级的权重）⇒ 判据 2a 必须变红",
          float(np.ptp(r_m1)) > 1e-6,
          f"r̄ 极差 = {np.ptp(r_m1):.3e}（正常实现 = 0）；"
          f"r̄ = {r_m1.round(5).tolist()}")
    # M2：权重只留 level0（半径失效）⇒ 判据 3b（半径单调）必须变红
    r_m2 = []
    for r in (0.15, 0.45, 0.85):
        bi, _ = run_glow_chain(gl, sh, lin, 0.6, r, THR, False,
                               weights_override=np.array([1.0, 0, 0, 0, 0]))
        r_m2.append(float(chan_radius(run_out(gl, sh, lin, bi, 0.6, texel),
                                      W, H, base).mean()))
    check("M2 权重只留 level0（半径失效）⇒ 判据 3b 必须变红（不再单调）",
          abs(r_m2[0] - r_m2[1]) < 1e-4 and abs(r_m2[1] - r_m2[2]) < 1e-4,
          f"r̄ = {r_m2[0]:.5f} / {r_m2[1]:.5f} / {r_m2[2]:.5f}（三档相同 ⇒ 半径滑杆失效）")
    # M3：去掉高光提取（阈值恒 0）⇒ 判据 3c 必须变红（不再单调）
    # ⚠ 必须把**整个**表达式换掉：只改分子、分母的 `1.0 - uThrLin` 仍随 [97] 变
    #（第一版就漏了分母 ⇒ 判据没变红，反而"看起来更单调"）。
    sh_m3 = mutate(sh, "kFsHaloThr",
                   "max(0.0, luma(lin) - uThrLin) / max(1.0 - uThrLin, 0.001)",
                   "luma(lin)")
    e_m3 = []
    for t in (0.30, 0.60, 0.85):
        bm, _ = run_glow_chain(gl, sh_m3, lin, 0.6, 0.35, t, False)
        e_m3.append(float(np.abs(run_out(gl, sh_m3, lin, bm, 0.6, texel) - base).sum()))
    check("M3 去掉高光提取（阈值恒 0）⇒ 判据 3c 必须变红（不再单调）",
          abs(e_m3[0] - e_m3[1]) < 1e-2 and abs(e_m3[1] - e_m3[2]) < 1e-2,
          f"能量 {e_m3[0]:.4f} / {e_m3[1]:.4f} / {e_m3[2]:.4f}（三档几乎相同 ⇒ 阈值失效）")
    # M4：Screen 改成加法 ⇒ 高光截断（饱和像素 > 0；Screen 版为 0）
    sh_m4 = mutate(sh, "kFsOut", "c = 1.0 - (1.0 - c) * (1.0 - gb);",
                   "c = c + gb;")
    o_m4 = run_out(gl, sh_m4, lin, b_on, 0.6, texel)
    check("M4 混合改成加法 ⇒ 高光截断（饱和像素 > 0；Screen 版为 0）",
          float((o_m4 > 0.999).sum()) > 0 and float((out_glow > 0.999).sum()) == 0,
          f"加法饱和像素 {int((o_m4 > 0.999).sum())} 个 / "
          f"Screen {int((out_glow > 0.999).sum())} 个 ⇒ 判据 2d 的实测对应")

    print()
    if WARN:
        print(f"警告 {len(WARN)} 项（交用户拍板）：{WARN}")
    if FAIL:
        print(f"失败 {len(FAIL)} 项：{FAIL}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
