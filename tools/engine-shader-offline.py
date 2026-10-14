#!/usr/bin/env python3
"""引擎 shader 离线执行器（moderngl 真执行 FrameRenderer.cpp 里的 GLSL）。

为什么需要：排查淡紫线时，用「numpy 直译」复现引擎链路不可靠（漏 P0、漏 [58] 双 pass、
坐标系约定不同），推出的结论自相矛盾。本脚本直接**执行引擎自己的 shader 字符串**，
只做最小机械改写（#version 300 es → 330 core），保证域与逻辑与真机一致。

用法：
  python3 tools/engine-shader-offline.py --dng <path> --roi Y0,Y1,X0,X1 --out /tmp/chain
产物：每个 pass 的输出存为 .npy（float32，H×W×4），外加 steps.json 记录 uniform。

已接入的 pass（按引擎成片链顺序）：
  1. P0   kFsRawDenoise   RAW 域同色 bilateral（含 uMix 强度）
  2. RCD  kFsRcdDir / kFsRcdG / kFsRcdChroma（单 pass）或 kFsRcdChromaA+B（[58] 双 pass）
  3. CCM  kFsCcm          D3 → D4（uCcm）
  4. FILM kFsFilm         filmic + gamut + sat/look（uToneK/uGamut/uSat/uLook…）
                          ⇒ film_d5.npy（OETF 前的线性显示域，与真机 tex_work_ 同域）
                          CPU 侧 filmic 系数见 tools/filmic-spline-ref.py（逐行照搬
                          FrameRenderer::computeFilmicSpline）；参数与 uniform 全表
                          见 steps.json → steps.film；自检 tools/check-film-offline.py
  5. PC3D kFsPc3d（--pc3d 1）  ESD3D2 移植（Down→pc3d→guidedUp→pc3d）
                          ⚠ LSC = 恒等、sigA/sigB 非真机标定值 ⇒ 只作结构验证

未接入（需要额外纹理/CPU 侧数据，按需再加）：
  - P0 前置的 HR（高光重建，需要块级参考场）——**真机上 FILM 的输入是它的输出**，
    故本脚本的 FILM 基线与真机输入口径不同，**不能**用它判断成片观感
  - passOutput 的 OETF/颗粒/dither（本脚本用 srgb_encode 近似，色度不受影响）

阶段 1 判据（拆 kFsFilm 为模板 + 引擎注入段后的零回归）：
  film_d5.npy 的 CRC32 必须与 docs/reports/stage0-engine-slot-BASELINE.md §3.3 一致。

阶段 2（`--engine {0,1}`，默认 0）：
  · `--engine 0`：**一字不变**（仍是 `kFsFilm` 整份）⇒ 判据⑤的零回归守卫。
  · `--engine 1`：`head + kFsSigmoidDef + mid + kFsSigmoidMain + tail`（= C++ `filmSource(1)`
    的组装顺序）。head/mid/tail 走**锚点路径**（复刻 `filmSlices()`，非行号切分）；
    两个注入段按**字面量名**从 FrameRenderer.cpp 提取 ⇒ 卡 B 微调 GLSL 后重跑即可。
    7 个 `uSig*` 由 `tools/sigmoid-params-ref.py` 的 `compute_sigmoid_params()` 求解
    （本脚本不重写公式），CLI = `--sig-contrast/-skew/-white/-black/-hue`。
    输出 = `film_d5_sigmoid.npy`（**不覆盖** `film_d5.npy`）。
"""
import argparse
import json
import os
import re
import sys

import numpy as np

import moderngl
import glfw

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FR = os.path.join(REPO, 'core/engine/src/render/FrameRenderer.cpp')


# ────────────────────────────────────────────── shader 提取
def extract_shaders(path=FR):
    """从 FrameRenderer.cpp 提取 `const char* kFsXxx = R"(...)"` 形式的 shader。"""
    src = open(path, encoding='utf-8', errors='replace').read()
    out = {}
    for m in re.finditer(r'const char\*\s+(kFs\w+)\s*=\s*R"\((.*?)\)";', src, re.S):
        out[m.group(1)] = m.group(2)
    return out


def to_core(src):
    """GLSL ES 300 → GLSL 330 core 的最小机械改写。"""
    s = src.replace('#version 300 es', '#version 330 core')
    # 引擎 shader 用 layout(location=0) out 变量，330 core 需要显式绑定（保留即可）
    return s


# ────────────────────────────────────────────── GL 环境
class GL:
    def __init__(self, w, h):
        if not glfw.init():
            raise RuntimeError('glfw.init failed')
        glfw.window_hint(glfw.VISIBLE, glfw.FALSE)
        glfw.window_hint(glfw.CONTEXT_VERSION_MAJOR, 4)
        glfw.window_hint(glfw.CONTEXT_VERSION_MINOR, 1)
        glfw.window_hint(glfw.OPENGL_PROFILE, glfw.OPENGL_CORE_PROFILE)
        glfw.window_hint(glfw.OPENGL_FORWARD_COMPAT, glfw.TRUE)
        self.win = glfw.create_window(64, 64, 'offline', None, None)
        if not self.win:
            glfw.terminate()
            raise RuntimeError('glfw.create_window failed')
        glfw.make_context_current(self.win)
        self.ctx = moderngl.create_context()
        self.w, self.h = w, h
        self._fbos = {}
        self.fbo = self.ctx.framebuffer(
            color_attachments=[self.ctx.texture((w, h), 4, dtype='f2')])
        self._fbos[(w, h)] = self.fbo
        # 全屏三角形顶点着色器：gl_FragCoord.xy 直接对应像素中心 (x+0.5, y+0.5)
        # 全屏三角形 + 插值出 vUV0（0..1，左下原点，与 GL 纹理坐标一致）
        self.vs_src = '''#version 330 core
out vec2 vUV0;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
    vUV0 = p;                        // (0,0) (2,0) (0,2) 插值后覆盖 0..1
}
'''


    def run(self, fs_src, uniforms, textures, size=None, report=None):
        """执行一个 fragment shader。textures: {uniform名: moderngl.Texture}
        size=(w,h) 时用该尺寸的 framebuffer（同 context，可复用纹理）。
        report: 传入 list 时，把**实际写入 program** 的 uniform 名追加进去
        （`if k in prog` 是安全跳过式：被编译器优化掉的 uniform 不在 program 里，
        给了无害但也没生效）—— 供 steps.json 留证，默认 None = 行为不变。"""
        tw, th = size if size else (self.w, self.h)
        if (tw, th) not in self._fbos:
            self._fbos[(tw, th)] = self.ctx.framebuffer(
                color_attachments=[self.ctx.texture((tw, th), 4, dtype='f2')])
        fbo = self._fbos[(tw, th)]
        prog = self.ctx.program(self.vs_src, to_core(fs_src))
        fbo.use()
        self.ctx.viewport = (0, 0, tw, th)
        for i, (name, tex) in enumerate(textures.items()):
            tex.use(i)
            if name in prog:
                prog[name].value = i
        _written = []
        for k, v in uniforms.items():
            if k in prog:
                prog[k].value = v
                _written.append(k)
        if report is not None:
            report.extend(sorted(_written))
        vao = self.ctx.vertex_array(prog, [])       # 必须用本次的 program
        vao.render(moderngl.TRIANGLES, vertices=3)
        vao.release()
        prog.release()
        data = fbo.read(components=4, dtype='f2')
        arr = np.frombuffer(data, dtype='float16').reshape(th, tw, 4)
        return arr.astype(np.float32)     # 保留 4 通道（纹理是 RGBA16F）

    def close(self):
        glfw.terminate()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', required=True)
    ap.add_argument('--roi', default='1800,2100,600,1000', help='Y0,Y1,X0,X1（sensor 域）')
    ap.add_argument('--out', default='/tmp/chain')
    ap.add_argument('--p3-split', type=int, default=1, help='1 = [58] G 位双 pass')
    ap.add_argument('--defringe', type=int, default=0, help='1 = 跑 defringe 三段并统计线性域量纲')
    ap.add_argument('--ccm', default='1.627,-0.561,-0.066,-0.139,1.347,-0.208,0.009,-0.564,1.554')
    ap.add_argument('--pc3d', type=int, default=0, help='1 = 执行 kFsPc3d（需 LSC，暂用恒等）')
    # ── FILM 段参数（新增行，不改上面任何既有行；执行在文件尾部的 film_stage）──────
    # 默认值一律回源码核实：FilmParams.h 的 [4]/[5..10]/[20..25]/[28]/[31..33]/[36]/[37]/[38]/[40]
    # + computeFilmicSpline 的默认形参。**不取自本注释**，取不到的一律在 steps.json 里标出来。
    ap.add_argument('--no-film', action='store_true', help='跳过 FILM（kFsFilm）段')
    ap.add_argument('--film-black-ev', type=float, default=-7.65, help='FilmParams[37] 默认 -7.65')
    ap.add_argument('--film-white-ev', type=float, default=4.56, help='FilmParams[38] 默认 4.56')
    ap.add_argument('--film-contrast', type=float, default=1.0, help='FilmParams[4] 默认 1.0（真机 = cp×[4]，auto_tone=0 ⇒ cp=1.0）')
    ap.add_argument('--film-lat-sh', type=float, default=0.01, help='FilmParams[5..7] shoulder latitude 默认 0.01')
    ap.add_argument('--film-lat-toe', type=float, default=0.01, help='FilmParams[8..10] toe latitude 默认 0.01')
    ap.add_argument('--film-tone-k', type=float, default=1.0, help='FilmParams[28] tone_strength 默认 1.0')
    ap.add_argument('--film-gamut', type=float, default=1.0, help='FilmParams[40] gamut 默认 1.0')
    ap.add_argument('--film-ev', type=float, default=0.0, help='FilmParams[0] exposure_ev 默认 0.0 ⇒ uExposure = 2^ev')
    ap.add_argument('--film-look', type=float, default=1.0, help='FilmParams[31] look_strength 默认 1.0')
    ap.add_argument('--film-look-sat', type=float, default=1.0, help='FilmParams[32] look_sat 默认 1.0')
    ap.add_argument('--film-look-chroma', type=float, default=1.0, help='FilmParams[33] look_chroma 默认 1.0')
    ap.add_argument('--film-sat', default='1,1,1', help='FilmParams[20,21,22] 阴影/中间/高光饱和')
    ap.add_argument('--film-split', default='0,0', help='FilmParams[23,24] 阴影暖/高光冷')
    ap.add_argument('--film-mono', type=float, default=0.0, help='FilmParams[25] 单色')
    ap.add_argument('--film-local', type=float, default=0.0,
                    help='uLocalStrength。**离线默认 0 = 旁路**（真机默认 FilmParams[36]=0.25，'
                         '依赖 32×24 局部亮度图中位 ⇒ 离线链无法复现，见报告"不在离线链内"清单）')
    ap.add_argument('--film-hal-domain', type=int, default=0,
                    help='1 = 输入是 HAL 显示域（预览/YUV 静拍链），先除 uDomainRatio；'
                         '0 = RAW 静拍链（本脚本的口径）')
    ap.add_argument('--film-input', choices=['ccm', 'pc3d'], default='ccm',
                    help='FILM 的输入：ccm = ccm_d4.npy（默认，阶段 1 基准口径）；'
                         'pc3d = pc3d.npy（更接近真机链：真机 kFsFilm 吃 PC3D/defringe 的输出）')
    ap.add_argument('--film-domain-ratio', type=float, default=2.0,
                    help='uDomainRatio（仅 --film-hal-domain 1 时生效；真机 = 在线 EMA，初值 2.0）')
    # ── 引擎插槽（阶段 2）───────────────────────────────────────────────────
    ap.add_argument('--engine', type=int, choices=[0, 1], default=0,
                    help='0 = 引擎 0 filmic（默认，行为与阶段 1 一字不变，输出 film_d5.npy）；'
                         '1 = 引擎 1 darktable sigmoid（输出 film_d5_sigmoid.npy，'
                         '**不覆盖** film_d5.npy ⇒ 判据⑤基线不被踩）')
    ap.add_argument('--sig-contrast', type=float, default=1.2, help='kSigmoidDefaultContrast')
    ap.add_argument('--sig-skew', type=float, default=0.0, help='kSigmoidDefaultSkew')
    ap.add_argument('--sig-white', type=float, default=100.0,
                    help='kSigmoidDefaultWhiteTarget（**百分数**）')
    ap.add_argument('--sig-black', type=float, default=0.0152,
                    help='kSigmoidDefaultBlackTarget（**百分数**）')
    ap.add_argument('--sig-hue', type=float, default=100.0,
                    help='kSigmoidDefaultHuePreservation（**百分数**）')
    # PC3D 段参数（新增行；执行在文件尾部 pc3d_stage）
    ap.add_argument('--pc3d-siga', type=float, default=1e-5, help='噪声模型斜率（非真机标定值）')
    ap.add_argument('--pc3d-sigb', type=float, default=1e-6, help='噪声模型截距（非真机标定值）')
    ap.add_argument('--pc3d-msize', type=float, default=0.0, help='FilmParams[55] 强制核尺寸，0=自动')
    args = ap.parse_args()

    Y0, Y1, X0, X1 = [int(v) for v in args.roi.split(',')]
    os.makedirs(args.out, exist_ok=True)
    import rawpy
    import io as _io
    raw = rawpy.imread(_io.BytesIO(open(args.dng, 'rb').read()))
    C = raw.raw_image_visible.astype(np.uint16)
    pat = np.asarray(raw.raw_pattern, dtype=np.int64)
    bl = np.asarray(raw.black_level_per_channel, dtype=np.float32)
    white = float(raw.white_level)
    wb = np.asarray(raw.camera_whitebalance, dtype=np.float64)[:3]
    g = wb / wb[1]        # 与 shader 同口径：gain = AsShotNeutral 倒数、G 归一
    # CFA LUT：k = (y&1)*2 + (x&1) → 2bit 通道号
    cfa_lut = 0
    for k in range(4):
        y, x = (k >> 1) & 1, k & 1
        cfa_lut |= (int(pat[y, x]) & 3) << (k << 1)

    roi = C[Y0:Y1, X0:X1].copy()
    h, w = roi.shape
    print(f'ROI {w}x{h}  pattern={pat.tolist()}  black={bl.tolist()}  white={white}  gain={np.round(g,4).tolist()}')

    sh = extract_shaders()
    # ⚠ 引擎 shader 的 `c == 1 ? m.g : m.b` 对 G2（ch==3）会读成 m.b —— 离线执行器统一修正
    for _k in list(sh):
        if 'c == 1 ? m.g : m.b' in sh[_k]:
            sh[_k] = sh[_k].replace('c == 1 ? m.g : m.b',
                                    '(c == 1 || c == 3) ? m.g : m.b')
    print('提取到 shader:', len(sh), '个 →', ', '.join(sorted(sh)[:12]), '...')

    gl = GL(w, h)
    tex_raw = gl.ctx.texture((w, h), 1, dtype='u2')
    tex_raw.write(roi.tobytes())
    tex_raw.filter = (moderngl.NEAREST, moderngl.NEAREST)

    steps = {}
    # ── P0：RAW 域同色 bilateral（uMix=1 全强度；σ 用真实模型，A/B 由 --noise 给）
    noiseA = [1e-5, 1e-5, 1e-5]
    noiseB = [1e-6, 1e-6, 1e-6]
    # ⚠ P0 shader 的 oColor 三目只判 ch==1，G2（ch==3）会写 0 —— 离线执行器修正它
    p0_src = sh['kFsRawDenoise'].replace(
        'oColor = vec4(ch == 0 ? v : 0.0, ch == 1 ? v : 0.0, ch == 2 ? v : 0.0, 1.0);',
        'oColor = vec4((ch == 0) ? v : 0.0, (ch == 1 || ch == 3) ? v : 0.0, (ch == 2) ? v : 0.0, 1.0);')
    assert 'ch == 3' in p0_src, 'P0 修正未生效'
    p0 = gl.run(p0_src, {
        'uSize': (w, h), 'uCfaLut': cfa_lut,
        'uBlack': tuple(float(bl[i]) for i in range(4)), 'uWhite': white,
        'uGain': tuple(float(v) for v in g),
        'uNoiseA': tuple(noiseA), 'uNoiseB': tuple(noiseB), 'uMix': 1.0,
    }, {'uRaw': tex_raw})
    np.save(os.path.join(args.out, 'p0.npy'), p0)
    steps['p0'] = 'kFsRawDenoise'

    # ── RCD：P1/P2/P3（[58] 时用 A/B 双 pass）
    tex_mos = gl.ctx.texture((w, h), 4, dtype='f2')
    tex_mos.write(p0.astype(np.float16).tobytes())
    tex_mos.filter = (moderngl.NEAREST, moderngl.NEAREST)
    d1 = gl.run(sh['kFsRcdDir'], {
        'uSize': (w, h), 'uCfaLut': cfa_lut,
        'uBlack': tuple(float(bl[i]) for i in range(4)), 'uWhite': white,
        'uGain': tuple(float(v) for v in g), 'uMosaic': 1.0,
    }, {'uRaw': tex_raw, 'uMos': tex_mos})
    np.save(os.path.join(args.out, 'rcd_p1.npy'), d1)
    tex_dir = gl.ctx.texture((w, h), 4, dtype='f2'); tex_dir.write(d1.astype(np.float16).tobytes())
    tex_dir.filter = (moderngl.NEAREST, moderngl.NEAREST)
    gg = gl.run(sh['kFsRcdG'], {
        'uSize': (w, h), 'uCfaLut': cfa_lut,
        'uBlack': tuple(float(bl[i]) for i in range(4)), 'uWhite': white,
        'uGain': tuple(float(v) for v in g), 'uMosaic': 1.0, 'uGOrig': 0.0,
    }, {'uRaw': tex_raw, 'uMos': tex_mos, 'uDir': tex_dir})
    np.save(os.path.join(args.out, 'rcd_g.npy'), gg)
    tex_g = gl.ctx.texture((w, h), 4, dtype='f2'); tex_g.write(gg.astype(np.float16).tobytes())
    tex_g.filter = (moderngl.NEAREST, moderngl.NEAREST)
    tex_lsc = gl.ctx.texture((1, 1), 3, dtype='f2'); tex_lsc.write(np.ones((1, 1, 3), np.float16).tobytes())
    common = {'uSize': (w, h), 'uCfaLut': cfa_lut,
              'uBlack': tuple(float(bl[i]) for i in range(4)), 'uWhite': white,
              'uGain': tuple(float(v) for v in g), 'uMosaic': 1.0,
              'uLscSize': (1, 1),
              'uSatClip': (float(g[0]), 1.0, float(g[2]))}
    if args.p3_split:
        a = gl.run(sh['kFsRcdChromaA'], {**common, 'uCdFix': 1.0},
                   {'uRaw': tex_raw, 'uMos': tex_mos, 'uDir': tex_dir, 'uG': tex_g, 'uLsc': tex_lsc})
        np.save(os.path.join(args.out, 'rcd_3a.npy'), a)
        tex_a = gl.ctx.texture((w, h), 4, dtype='f2'); tex_a.write(a.astype(np.float16).tobytes())
        tex_a.filter = (moderngl.NEAREST, moderngl.NEAREST)
        d3 = gl.run(sh['kFsRcdChromaB'], common,
                    {'uRcdA': tex_a, 'uDir': tex_dir, 'uG': tex_g, 'uLsc': tex_lsc})
        steps['rcd'] = 'kFsRcdDir+G+ChromaA+B ([58] 双 pass)'
    else:
        d3 = gl.run(sh['kFsRcdChroma'], {**common, 'uCdFix': 1.0},
                    {'uRaw': tex_raw, 'uMos': tex_mos, 'uDir': tex_dir, 'uG': tex_g, 'uLsc': tex_lsc})
        steps['rcd'] = 'kFsRcdDir+G+Chroma (单 pass)'
    np.save(os.path.join(args.out, 'rcd_d3.npy'), d3)

    # ── CCM：D3 → D4
    CCM = np.array([float(v) for v in args.ccm.split(',')], dtype=np.float32).reshape(3, 3)
    tex_d3 = gl.ctx.texture((w, h), 4, dtype='f2'); tex_d3.write(d3.astype(np.float16).tobytes())
    tex_d3.filter = (moderngl.NEAREST, moderngl.NEAREST)
    # moderngl 的 mat3 按**列主序**读入 → 传转置
    d4 = gl.run(sh['kFsCcm'], {'uSize': (w, h), 'uCcm': tuple(CCM.T.ravel().tolist())},
                {'uTex': tex_d3})
    tex_d4 = gl.ctx.texture((w, h), 4, dtype='f2'); tex_d4.write(d4.astype(np.float16).tobytes())
    tex_d4.filter = (moderngl.NEAREST, moderngl.NEAREST)
    np.save(os.path.join(args.out, 'ccm_d4.npy'), d4)
    steps['ccm'] = 'kFsCcm'

    # ── v0.10.194：defringe 三段在线性域真跑（定阈值的唯一可靠途径）──────────
    if args.defringe:
        dw, dh = w, h
        hw, hh = (dw + 1) // 2, (dh + 1) // 2
        # ① down：全分辨率 → 半分辨率 (L, br, b, gate)
        lab = gl.run(sh['kFsDefringeDown'], {'uSize': (dw, dh)}, {'uTex': tex_d4}, size=(hw, hh))
        np.save(os.path.join(args.out, 'df_lab.npy'), lab)
        tex_lab = gl.ctx.texture((hw, hh), 4, dtype='f2')
        tex_lab.write(lab.astype(np.float16).tobytes())
        tex_lab.filter = (moderngl.NEAREST, moderngl.NEAREST)
        # ② detect：11×11 base_std(B-R)
        det = gl.run(sh['kFsDefringeDetect'], {'uHalf': (hw, hh)}, {'uSrc': tex_lab}, size=(hw, hh))
        np.save(os.path.join(args.out, 'df_det.npy'), det)
        # ③ avg：16×16 网格 → 全图 |B-R| 均值（= uChromaAvg）
        aw, ah = (hw + 15) // 16, (hh + 15) // 16
        tex_det_h = gl.ctx.texture((hw, hh), 4, dtype='f2')
        tex_det_h.write(det.astype(np.float16).tobytes())
        tex_det_h.filter = (moderngl.NEAREST, moderngl.NEAREST)
        avg = gl.run(sh['kFsDefringeAvg'], {'uHalf': (hw, hh)}, {'uSrc': tex_det_h}, size=(aw, ah))
        cavg = float(avg[..., 0].mean())
        steps['defringe'] = {'chromaAvg(linear |B-R| mean)': cavg}
        print(f'uChromaAvg（线性域 |B-R| 全图均值）= {cavg:.6f}')
        # ④ 线性域统计：紫线像素的三个物理量分布
        d4a = d4[..., :3]
        br_full = d4a[..., 2] - d4a[..., 0]
        # 邻域 std（半分辨率，与 shader 同口径用 det.w）
        det_w = np.repeat(np.repeat(det[..., 3], 2, axis=0), 2, axis=1)[:dh, :dw]
        # gL：与 apply shader 同式（中心差分，未除 2，×255）
        lum = 0.2126 * d4a[..., 0] + 0.7152 * d4a[..., 1] + 0.0722 * d4a[..., 2]
        gx = np.zeros_like(lum); gy = np.zeros_like(lum)
        gx[:, 1:-1] = lum[:, 2:] - lum[:, :-2]
        gy[1:-1, :] = lum[2:, :] - lum[:-2, :]
        gL = np.sqrt(gx * gx + gy * gy) * 255.0
        # 紫线候选：|br| 高 + 亮度梯度高
        cand = (np.abs(br_full) > 1.5 * cavg) & (gL > 5)
        ys, xs2 = np.where(cand)
        stats = {'cavg': cavg, 'n_cand': int(len(ys))}
        if len(ys):
            abr = np.abs(br_full[ys, xs2]); astd = det_w[ys, xs2]; agl = gL[ys, xs2]
            for nm, arr in (('|br|', abr), ('base_std', astd), ('gL', agl)):
                stats[nm] = {'P10': float(np.percentile(arr, 10)),
                             'P50': float(np.percentile(arr, 50)),
                             'P90': float(np.percentile(arr, 90))}
                stats[nm + '_x_cavg'] = {'P10': float(np.percentile(arr, 10) / cavg),
                                          'P50': float(np.percentile(arr, 50) / cavg),
                                          'P90': float(np.percentile(arr, 90) / cavg)}
        steps['defringe_stats'] = stats
        print('紫线候选统计:', json.dumps(stats, indent=2)[:1200])
        d4 = d4  # noqa

    gl.close()
    json.dump({'steps': steps, 'roi': [Y0, Y1, X0, X1], 'dng': os.path.basename(args.dng),
               'gain': g.tolist(), 'black': bl.tolist(), 'white': white,
               'pattern': pat.tolist()},
              open(os.path.join(args.out, 'steps.json'), 'w'), indent=2)
    print('产物写入', args.out)
    for k, v in steps.items():
        print(' ', k, '=', v)


# ═══════════════════════════════════════════════════════════════════════════════
# 引擎插槽「阶段 1」判据 1：`kFsFilm` 切分/拼接验证（`--verify-splice`）
#
# 纯增量：不触碰上面的 P0/RCD/CCM/FILM 任何一行；只在下面的 `main()` **之前**插入
# 一个模式分派（`--verify-splice` 时直接走本段并退出，不进 GPU 链路）。
#
# 契约（`docs/engine-slots-design.md` §3.1 / §8 阶段 1；架构师 2026-10-13 定档）：
#   `kFsFilm` 体内 **496 行**，按 4 个切分点切 5 块（体内 1-based 行号）：
#     head 1..78 │ engine0_def 79..372 │ mid 373..432 │ engine0_main 433..439 │ tail 440..496
#   拼接顺序 = head + engine0_def + mid + engine0_main + tail，须逐字节等于原体内内容。
#
# ⚠ 口径（架构师更正 2026-10-13）：**字符 25140 ≠ 字节 35154**（含大量中文注释）。
#   · 切分按**字符偏移**（行首定位后取前缀长度）；不用 `join('\n')` 相加 —— 会丢换行。
#   · 摘要（CRC32 / SHA-256）按 `.encode('utf-8')` 的**字节**口径，字符数与字节数都打印。
#
# 判据 1 的证明力在哪（重要）：按偏移切片再拼回**恒等于原文**，这是平凡真。
#   非平凡的是另外两条，任一不满足即 FAIL：
#     ① 契约块拼接结果的摘要 == 阶段 1 前的 `kFsFilm` 源基线（下 `FILM_SRC_BASELINE`）
#        ⇒ 证明"被切的这坨 shader 真的没被改动"；
#     ② 切分点语义哨兵（head 首行 `#version`、mid 首行 `void main() {`、
#        engine0_def 含 filmicTone/gamutMapV5 定义…）⇒ 证明"切在了契约说的语义位置"，
#        而不是行号漂移后的随便一处。
# ═══════════════════════════════════════════════════════════════════════════════
import zlib as _zl        # noqa: E402  （本段自带；文件尾部的 _zlib/_hashlib 要等本段之后才执行）
import hashlib as _hl     # noqa: E402

FILM_SPLIT_POINTS = [79, 373, 433, 440]      # 各块起始行（体内 1-based）
FILM_BLOCK_NAMES = ['head', 'engine0_def', 'mid', 'engine0_main', 'tail']
FILM_BLOCK_LINES = ['1..78', '79..372', '373..432', '433..439', '440..496']
FILM_TEMPLATE_NAMES = ['kFilmTemplateHead', 'kFilmTemplateMid', 'kFilmTemplateTail']
# C++ 侧 `filmSlices()` 的四个锚点（FrameRenderer.cpp:4567-4571）——锚点被改坏必须报错，
# 不静默回退到行号切分（否则判据会退化成"永远通过"）。
FILM_ANCHOR_NAMES = ['kFilmSplitDefAnchor', 'kFilmSplitMidAnchor',
                     'kFilmSplitMainAnchor', 'kFilmSplitMainBlockEnd']

# 阶段 1 落地前的 `kFsFilm` 体内内容基线（**字节口径**，架构师 2026-10-13 实测更正）。
# 出处：架构师切分契约消息 + `docs/reports/stage0-engine-slot-BASELINE.md` §3.3 同批采集。
#   · 顶层 4 个数 = **整体**摘要（证明"被切的这坨 shader 没被改动"）；
#   · `blocks` = **逐块**摘要（证明"切分点没漂移"）—— 只锚整体是不够的：切分点整体
#     偏 1 行时，head/engine0_def 的边界行都是注释，语义哨兵与整体摘要**都抓不到**
#     （本脚本 `--splice-selftest` 的 ① 号 mutation 就是这么抓出来的）。
FILM_SRC_BASELINE = {
    'chars': 25140, 'bytes': 35154,
    'crc32': '0x4fa1ebd3',
    'sha256': 'f1ebb3178bfb985ed3e0ca19fef95341cfe3be401cb2cc73ea8ae3377c13563c',
    'blocks': {
        'head': {'chars': 3839, 'bytes': 6936, 'crc32': '0x79355c53',
                 'sha256': '5393c81c454a39326ea47608f5f53c8c5926c63a0e3cba220fcf3c7b3ef83ac5'},
        'engine0_def': {'chars': 14778, 'bytes': 18195, 'crc32': '0x05f5351d',
                        'sha256': '97972744125a123a8231a9ecc0cdb3e46325e84cc86ad0841bb161b0b1a58b44'},
        'mid': {'chars': 3188, 'bytes': 5131, 'crc32': '0x0dab15dc',
                'sha256': '5eaddd68828ecf7d3ebfdb19282b4d716f324c80851206f067831620d838fc3d'},
        'engine0_main': {'chars': 343, 'bytes': 407, 'crc32': '0xbe41521e',
                         'sha256': '3e16acd3b9789e882402b32623c30345d95573b37aaa66b351c504b03ab50e1d'},
        'tail': {'chars': 2992, 'bytes': 4485, 'crc32': '0x20921afd',
                 'sha256': 'bc8154c39f3e218e716be2e58cebc1aa0a46a5aed7c54fc61b7289d893edfd73'},
    },
}


def _src_digest(s):
    """字符串摘要：字符数 + 字节数 + 字节口径 CRC32/SHA-256。"""
    b = s.encode('utf-8')
    return {'chars': len(s), 'bytes': len(b),
            'crc32': '0x%08x' % (_zl.crc32(b) & 0xffffffff),
            'sha256': _hl.sha256(b).hexdigest()}


def _line_offsets(body):
    """第 i 项 = 第 i+1 行（1-based）的首字符偏移。"""
    offs, pos = [], 0
    for ln in body.split('\n'):
        offs.append(pos)
        pos += len(ln) + 1
    return offs


def _n_lines(s):
    """块内实际行数（块尾的换行不算新起一行）。"""
    return s.count('\n') + (0 if s.endswith('\n') else 1)


def split_film_body(body, points=FILM_SPLIT_POINTS):
    """按**字符偏移**切成契约 5 块（行首字符串定位后取前缀长度）。"""
    offs = _line_offsets(body)
    if len(offs) < points[-1]:
        raise ValueError('kFsFilm 体内只有 %d 行，契约切分点需要 %d 行'
                         % (len(offs), points[-1]))
    cuts = [0] + [offs[p - 1] for p in points] + [len(body)]
    return [body[cuts[i]:cuts[i + 1]] for i in range(len(cuts) - 1)]


def _split_sentinels(blocks):
    """切分点语义哨兵 → 返回失败项描述（空 = 全过）。行号漂移时先红，避免判据变空头支票。"""
    b = dict(zip(FILM_BLOCK_NAMES, blocks))
    checks = [
        ('head 首行 == "#version 300 es"', b['head'].split('\n')[0] == '#version 300 es'),
        ('engine0_def 含 filmicTone 定义', 'filmicTone' in b['engine0_def']),
        ('engine0_def 含 gamutMapV5 定义', 'gamutMapV5' in b['engine0_def']),
        ('engine0_def 末行 == "}"', b['engine0_def'].rstrip('\n').split('\n')[-1] == '}'),
        ('mid 首行 == "void main() {"', b['mid'].split('\n')[0] == 'void main() {'),
        ('engine0_main 含 filmicTone(out3) 调用', 'filmicTone(out3)' in b['engine0_main']),
        ('engine0_main 含 gamutMapV5(out3, toned) 调用', 'gamutMapV5(out3, toned)' in b['engine0_main']),
        ('tail 末行 == "}"', b['tail'].rstrip('\n').split('\n')[-1] == '}'),
    ]
    return [name for name, ok in checks if not ok]


def _first_diff(a, b, ctx=40):
    """首个差异字符偏移 + 两侧各 ctx 字符上下文（共 80 字符窗口）。"""
    n = min(len(a), len(b))
    i = next((k for k in range(n) if a[k] != b[k]), n)
    return {'char_offset': i, 'lengths': [len(a), len(b)],
            'original_ctx': a[max(0, i - ctx):i + ctx],
            'spliced_ctx': b[max(0, i - ctx):i + ctx]}


def _collect_raw_strings(path=FR, text=None):
    """全部 `const char* <name> = R"(...)"`（名字不限 `kFs*`）。"""
    src = text if text is not None else open(path, encoding='utf-8', errors='replace').read()
    return [(m.group(1), m.group(2))
            for m in re.finditer(r'const char\*\s+(\w+)\s*=\s*R"\((.*?)\)";', src, re.S)]


def _cxx_unescape(s):
    """C++ 字符串字面量反转义（覆盖锚点用到的 \\n / \\t / \\" / \\\\）。"""
    out, i = [], 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\'}.get(s[i + 1], s[i + 1]))
            i += 2
        else:
            out.append(s[i])
            i += 1
    return ''.join(out)


def _anchor_path(body, src_text):
    """路径 B：**复刻产品代码的锚点切分**（`FrameRenderer.cpp` 的 `filmSlices()`）。

    C++ 侧实际形态（阶段 1 落盘，2026-10-13）：`kFsFilm` 是**单真源**，
    `const std::string_view kFilmTemplate{Head,Mid,Tail} = filmSlices().{head,mid,tail};`
    四个锚点字符串在体内 `find` 出 a/b/c/e，`d = e + len(kFilmSplitMainBlockEnd)`，
    五段 = [0,a) [a,b) [b,c) [c,d) [d,end) ⇒ 零拷贝、不可能与真源漂移。
    本路径照抄这套语义 ⇒ 证明的是"**产品代码切出来的东西**拼回去 == 原文"，
    比脚本自己按行号切更有证明力。

    ⚠ 本路径是 C++ 语义的 **Python 复刻**，不是执行 C++ 代码；真机侧另有
    `logFilmSourceSelfCheck`（日志 `engine.film_src`：bytes/chars/crc32/identical）独立佐证。
    """
    decls = dict(re.findall(
        r'const\s+std::string_view\s+(kFilmTemplate\w+)\s*=\s*([^;]+);', src_text))
    anchors = {}
    for n in FILM_ANCHOR_NAMES:
        m = re.search(n + r'\s*=\s*"((?:[^"\\]|\\.)*)"', src_text)
        if m:
            anchors[n] = _cxx_unescape(m.group(1))
    missing = [n for n in FILM_ANCHOR_NAMES if n not in anchors]
    info = {'mode': 'anchor-splice', 'template_decls': decls, 'anchors': anchors}
    if not decls and missing:
        info.update(state='not_landed',
                    reason='未发现 kFilmTemplate* 声明，也未发现 %s ⇒ C++ 侧尚未落盘'
                          '（本路径判 n/a，不影响判据 1 结论）' % ' / '.join(missing))
        return info
    if missing:
        info.update(state='broken',
                    reason='模板声明在，但缺锚点 %s ⇒ 报错，不回退到行号切分' % ' / '.join(missing))
        return info
    if body is None:
        info.update(state='broken',
                    reason='kFsFilm 字面量已不在 FrameRenderer.cpp ⇒ 锚点切分无真源可比对')
        return info
    off = {n: body.find(anchors[n]) for n in FILM_ANCHOR_NAMES}
    a, b, c, e = (off[n] for n in FILM_ANCHOR_NAMES)
    info['offsets'] = {'a': a, 'b': b, 'c': c, 'e': e}
    if -1 in (a, b, c, e) or not (a < b < c < e):
        info.update(state='broken',
                    reason='四锚点在 kFsFilm 体内未全部命中或非严格递增（a<b<c<e 不成立）'
                          '⇒ 模板结构被改坏（有人重写了注释/结构）')
        return info
    d = e + len(anchors['kFilmSplitMainBlockEnd'])
    blk = [body[0:a], body[a:b], body[b:c], body[c:d], body[d:]]
    spliced = ''.join(blk)
    sd = _src_digest(spliced)
    info.update({
        'state': 'ok', 'd': d, 'anchors_ordered': True,
        'blocks': [dict(name=nm, lines=ln, n_lines=_n_lines(ch), **_src_digest(ch))
                   for nm, ln, ch in zip(FILM_BLOCK_NAMES, FILM_BLOCK_LINES, blk)],
        'spliced': sd, 'equals_kFsFilm': spliced == body,
        'equals_baseline_anchor': _anchor_ok(sd),
        'reason': '模板三段 = kFsFilm 单真源的锚点切片（C++ filmSlices() 语义复刻）',
        'vs_contract_note': 'C++ 的 engine_main 含收尾 "    }"（d = e + len(mainBlockEnd)）'
                            '⇒ 与契约表的行号口径（engine0_main 433..439 / tail 440..496）'
                            '差 5 字符，但**拼接结果一致**（两条路径都判 PASS 的依据）'})
    return info


def anchor_film_blocks(src_text=None):
    """按 C++ `filmSlices()` 的锚点语义切出 5 块**文本**（head/engine0_def/mid/engine0_main/tail）。

    复用 `_anchor_path()`（判据 1 的路径 B）⇒ 引擎 1 的组装与判据 1 走**同一份锚点解析**，
    不可能"两处各写一遍再漂移"。锚点缺失/错序时**抛错**（不静默回退到行号切分 ——
    任务卡明确：拿 head/mid/tail 必须用锚点路径，行号切分与真机不一致）。
    """
    src_text = (src_text if src_text is not None
                else open(FR, encoding='utf-8', errors='replace').read())
    body = dict(_collect_raw_strings(text=src_text)).get('kFsFilm')
    info = _anchor_path(body, src_text)
    if info.get('state') != 'ok':
        raise RuntimeError('锚点切分不可用（state=%s）：%s'
                           % (info.get('state'), info.get('reason')))
    o = info['offsets']
    cuts = [0, o['a'], o['b'], o['c'], info['d'], len(body)]
    return [body[cuts[i]:cuts[i + 1]] for i in range(5)], info


def _anchor_ok(d):
    return all(d[k] == FILM_SRC_BASELINE[k] for k in ('chars', 'bytes', 'crc32', 'sha256'))


def _blocks_anchor_ok(info):
    """逐块摘要比对 → 返回漂移的块名（切分点漂移检测；空 = 5 块全部对上）。"""
    base = FILM_SRC_BASELINE.get('blocks', {})
    return [b['name'] for b in info
            if b['name'] in base
            and any(b[k] != base[b['name']][k] for k in ('chars', 'bytes', 'crc32', 'sha256'))]


def splice_report():
    """跑完两条路径，返回 (报告 dict, 是否通过)。"""
    src_text = open(FR, encoding='utf-8', errors='replace').read()
    src_sha = _hl.sha256(src_text.encode('utf-8')).hexdigest()
    consts = _collect_raw_strings(text=src_text)
    body = dict(consts).get('kFsFilm')
    names = [n for n, _ in consts]
    rep = {
        'source': os.path.relpath(FR, REPO), 'source_sha256': src_sha,
        'shader_count': len(consts),
        'contract': {'split_points_1based': FILM_SPLIT_POINTS,
                     'block_lines': dict(zip(FILM_BLOCK_NAMES, FILM_BLOCK_LINES)),
                     'splice_order': FILM_BLOCK_NAMES,
                     'authoritative': 'anchor-splice（C++ filmSlices() 的实际行为 = 判据基准）',
                     'line_splice_role': 'line-splice（按行号切）仅作交叉验证；'
                                         '唯一差异 = engine_main 是否含收尾 "    }"（5 字符）',
                     'splitting': '字符偏移（行首定位后取前缀长度），非 join 相加',
                     'digest_domain': 'utf-8 字节'},
        'baseline_anchor': FILM_SRC_BASELINE,
        'units_note': '字符 %d / 字节 %d（25140 是**字符**数，不是字节数）'
                      % (FILM_SRC_BASELINE['chars'], FILM_SRC_BASELINE['bytes']),
    }
    ok = True
    blocks = None
    if 'kFsFilm' not in names:
        rep['path_a_line_split'] = {'available': False,
                                    'reason': 'FrameRenderer.cpp 中未找到 kFsFilm 原始字符串'}
        ok = False
    else:
        blocks = split_film_body(body)
        info = [dict(name=nm, lines=ln, n_lines=_n_lines(ch),
                     first_line=ch.split('\n')[0][:80],
                     last_line=ch.rstrip('\n').split('\n')[-1][:80], **_src_digest(ch))
                for nm, ln, ch in zip(FILM_BLOCK_NAMES, FILM_BLOCK_LINES, blocks)]
        spliced = ''.join(blocks)
        orig, sp = _src_digest(body), _src_digest(spliced)
        sent = _split_sentinels(blocks)
        anchor = _anchor_ok(orig)
        blk_drift = _blocks_anchor_ok(info)
        rep['path_a_line_split'] = {
            'available': True, 'original': orig, 'blocks': info, 'spliced': sp,
            'original_n_lines': _n_lines(body), 'spliced_n_lines': _n_lines(spliced),
            'spliced_bytes_sum': sum(b['bytes'] for b in info),
            'byte_identical': spliced == body, 'equals_baseline_anchor': anchor,
            'blocks_anchor_drifted': blk_drift,
            'split_point_sentinels_failed': sent,
            'first_diff': None if spliced == body else _first_diff(body, spliced)}
        if spliced != body or sent or not anchor or blk_drift:
            ok = False
    try:
        path_b = _anchor_path(body, src_text)
    except Exception as e:                                   # noqa: BLE001
        path_b = {'state': 'broken', 'reason': '路径 B 异常：%r' % (e,)}
    rep['path_b_anchor_splice'] = path_b
    # 两条路径都跑、都判 PASS 才算通过（架构师 2026-10-13）；'not_landed' = n/a 不判负。
    if path_b.get('state') == 'broken' or (
            path_b.get('state') == 'ok' and
            not (path_b.get('equals_kFsFilm') and path_b.get('equals_baseline_anchor'))):
        ok = False
    rep['verdict'] = 'PASS' if ok else 'FAIL'
    return rep, ok


def _merge_steps_splice(out, key, value):
    """把 splice 摘要并入 `<out>/steps.json`（前段的键原样保留）。"""
    p = os.path.join(out, 'steps.json')
    doc = json.load(open(p)) if os.path.exists(p) else {}
    doc.setdefault('steps', {})[key] = value
    json.dump(doc, open(p, 'w'), indent=2)


def _splice_selftest():
    """负向自检（mutation）：切分点、丢换行、锚点三处坏掉都必须被检出。

    验证工具自身出错 = **假绿**（比不验证更危险），所以留一条可复跑的检查。
    用法：`python3 tools/engine-shader-offline.py --verify-splice --splice-selftest`
    """
    body = dict(_collect_raw_strings()).get('kFsFilm')
    if body is None:
        print('自检跳过：kFsFilm 不在（C++ 侧已拆）'); return 1
    blocks = split_film_body(body)
    fails = []
    drift = _blocks_anchor_ok([dict(name=nm, **_src_digest(ch)) for nm, ch in zip(
        FILM_BLOCK_NAMES, split_film_body(body, [80, 373, 433, 440]))])
    if not drift:
        fails.append('① 切分点整体 +1 行：块级锚点未检出漂移')
    if ''.join(b.rstrip('\n') for b in blocks) == body:
        fails.append('② 丢换行（join 相加口径）：未被检出')
    keep = dict(FILM_SRC_BASELINE)
    FILM_SRC_BASELINE['crc32'] = '0xdeadbeef'
    try:
        rep, ok = splice_report()
    finally:
        FILM_SRC_BASELINE.update(keep)
    if ok or rep.get('verdict') != 'FAIL' or rep['path_a_line_split'].get('equals_baseline_anchor'):
        fails.append('③ 篡改基线锚点：verdict 未变 FAIL')
    src_text = open(FR, encoding='utf-8', errors='replace').read()
    bad_src = src_text.replace('kFilmSplitMidAnchor = "void main() {"',
                               'kFilmSplitMidAnchor = "void main()  {"')
    if bad_src == src_text:
        fails.append('④ 自检自身失效：未能构造锚点 mutation')
    elif _anchor_path(body, bad_src).get('state') != 'broken':
        fails.append('④ 锚点被改坏：路径 B 未报 BROKEN（会静默回退到行号切分）')
    fd = _first_diff(body, ''.join(b.rstrip('\n') for b in blocks))   # 人为丢换行
    if not (0 <= fd['char_offset'] < len(body)) or not fd['original_ctx'] or not fd['spliced_ctx']:
        fails.append('⑤ 不等路径：未给出首个差异偏移 + 80 字符上下文')
    FILM_SRC_BASELINE['crc32'] = '0xdeadbeef'
    _saved, sys.stdout = sys.stdout, open(os.devnull, 'w')
    try:
        rc = verify_splice_cli(['--verify-splice', '--out', '/tmp/stage1-selftest'])
    finally:
        sys.stdout.close()
        sys.stdout = _saved
        FILM_SRC_BASELINE.update(keep)
    if rc != 1:
        fails.append('⑥ CLI 退出码：不等/坏锚点时应为 1，实测 %r' % (rc,))
    for f in fails:
        print('FAIL ' + f)
    print('splice 负向自检：%s（6 项 mutation）' % ('全部检出' if not fails else '有漏检'))
    return 1 if fails else 0


def verify_splice_cli(argv):
    ap = argparse.ArgumentParser(prog='engine-shader-offline.py --verify-splice')
    ap.add_argument('--verify-splice', action='store_true', help=argparse.SUPPRESS)
    ap.add_argument('--splice-selftest', action='store_true', help=argparse.SUPPRESS)
    ap.add_argument('--out', default='/tmp/stage1-splice', help='steps.json 落盘目录')
    a, _ = ap.parse_known_args(argv)
    if a.splice_selftest:
        return _splice_selftest()
    os.makedirs(a.out, exist_ok=True)
    rep, ok = splice_report()
    _merge_steps_splice(a.out, 'splice', rep)

    print('── kFsFilm 切分/拼接验证（引擎插槽阶段 1 · 判据 1）──')
    print('源 %s  sha256=%s…' % (rep['source'], rep['source_sha256'][:16]))
    print('拼接顺序 %s' % ' + '.join(FILM_BLOCK_NAMES))
    print('口径基准 **anchor-splice**（C++ filmSlices() 的实际行为 = 产品代码行为）；'
          'line-splice（按行号 %s 切）仅作交叉验证 —— 两者唯一差异 = engine_main 是否含'
          '收尾 "    }"（5 字符）'
          % ' / '.join(str(p) for p in FILM_SPLIT_POINTS))
    a_ = rep['path_a_line_split']
    if a_.get('available'):
        print('路径 A（line-splice：按行号字符偏移切分 · 交叉验证）:')
        for b in a_['blocks']:
            print('  %-12s %-9s %3d 行  字符 %6d  字节 %6d  %s  %s…'
                  % (b['name'], b['lines'], b['n_lines'], b['chars'], b['bytes'],
                     b['crc32'], b['sha256'][:12]))
        o, s = a_['original'], a_['spliced']
        print('  %-12s %-9s %3d 行  字符 %6d  字节 %6d  %s  %s…'
              % ('原文', '1..496', a_['original_n_lines'], o['chars'], o['bytes'], o['crc32'],
                 o['sha256'][:12]))
        print('  %-12s %-9s %3d 行  字符 %6d  字节 %6d  %s  %s…'
              % ('拼接结果', '(5 块)', a_['spliced_n_lines'], s['chars'], s['bytes'],
                 s['crc32'], s['sha256'][:12]))
        print('  5 块字节和 = %d ；拼接结果字节数 = %d' % (a_['spliced_bytes_sum'], s['bytes']))
        print('  拼接结果 == 原文        : %s' % a_['byte_identical'])
        print('  原文摘要 == 源基线锚点  : %s（锚点 %s / %s）'
              % (a_['equals_baseline_anchor'], FILM_SRC_BASELINE['crc32'],
                 FILM_SRC_BASELINE['sha256'][:12] + '…'))
        print('  逐块摘要 == 块级锚点    : %s'
              % ('5/5 通过（切分点未漂移）' if not a_['blocks_anchor_drifted']
                 else '漂移 → ' + str(a_['blocks_anchor_drifted'])))
        print('  切分点语义哨兵          : %s'
              % ('8/8 通过' if not a_['split_point_sentinels_failed']
                 else '失败 → ' + str(a_['split_point_sentinels_failed'])))
        if a_['first_diff']:
            fd = a_['first_diff']
            print('  ！首个差异偏移 %d；原文…%s… / 拼接…%s…'
                  % (fd['char_offset'], fd['original_ctx'], fd['spliced_ctx']))
    else:
        print('路径 A（line-splice）: 不可用 — %s' % a_['reason'])
    pb = rep['path_b_anchor_splice']
    if pb.get('state') == 'ok':
        print('路径 B（C++ 锚点切分 · %s）: %s' % (pb['mode'], pb['reason']))
        print('  模板声明: %s' % json.dumps(pb['template_decls'], ensure_ascii=False))
        for b in pb['blocks']:
            print('  %-12s %-9s %3d 行  字符 %6d  字节 %6d  %s  %s…'
                  % (b['name'], b['lines'], b['n_lines'], b['chars'], b['bytes'],
                     b['crc32'], b['sha256'][:12]))
        o = pb['offsets']
        print('  锚点偏移 a=%d b=%d c=%d e=%d d=%d（a<b<c<e 严格递增: True）'
              % (o['a'], o['b'], o['c'], o['e'], pb['d']))
        print('  == kFsFilm 原文: %s ；== 基线锚点: %s' %
              (pb['equals_kFsFilm'], pb['equals_baseline_anchor']))
        print('  %s' % pb['vs_contract_note'])
    elif pb.get('state') == 'not_landed':
        print('路径 B（C++ 锚点切分）: n/a — %s' % pb['reason'])
    else:
        print('路径 B（C++ 锚点切分）: BROKEN — %s' % pb['reason'])
    print('判据 1 结论: %s   （steps.json → %s/splice）' % (rep['verdict'], a.out))
    return 0 if ok else 1


if __name__ == '__main__':
    # ↓ 新增 3 行：--verify-splice 独立模式（不走 GPU 链路）；下面的 main() 一字未动
    if '--verify-splice' in sys.argv:
        sys.exit(verify_splice_cli(sys.argv[1:]))
    main()


# ═══════════════════════════════════════════════════════════════════════════════
# FILM 段（`kFsFilm`，D4 → D5）— 2026-10-12「引擎插槽阶段 1」前置补齐
#
# 为什么写在文件尾部、且用**独立的 GL 上下文**：上面的 P0/RCD/CCM 三段是**零回归基线**
# （`docs/reports/stage0-engine-slot-BASELINE.md` §3 的四个字节摘要 = 阶段 1 的硬判据）。
# 改它们一行都会让基线失效。所以本段**不碰上面任何一行**，只读它们的落盘产物
# `ccm_d4.npy`；上面的 `main()`（含 `gl.close()`）跑完之后，这里重开一次上下文。
#
# 与真机的差异（**必读**，逐条写进 `steps.json` 的 `film.uniforms` 与报告"不在离线链内"）：
#   · 输入域：真机 RAW 静拍链是 `tex_scene_`（P5 多尺度色度降噪 + **HR 高光重建**的输出），
#     本段是 CCM 的 D4 直出 ⇒ **HR 不在离线链内**（[45] hr_raw 真机默认 1.0 开）。
#   · `uLocal*`：真机喂 32×24 级低频亮度图 + 其中位（读回 8bit），**依赖真机统计** ⇒
#     离线取旁路值 `uLocalStrength = 0`（shader 里 `if (uLocalStrength > 0.0)` 整段短路），
#     其余 uLocal* 给源码常量/兜底值仅为满足 sampler 绑定。
#   · PC3D（[54]）、defringe（[47]）、MFNR、OETF/颗粒/LUT/锐化：都不在本段内。
# ═══════════════════════════════════════════════════════════════════════════════
import zlib as _zlib          # noqa: E402  （新增，尾部追加；上面的 import 块不动）
import hashlib as _hashlib    # noqa: E402


def digest_f32(arr):
    """float32 原始字节摘要（与 BASELINE 报告 §3 完全同一口径：`a.tobytes()`）。"""
    b = np.ascontiguousarray(arr, dtype=np.float32).tobytes()
    return {'shape': list(arr.shape), 'bytes': len(b),
            'crc32': '0x%08x' % (_zlib.crc32(b) & 0xffffffff),
            'sha256': _hashlib.sha256(b).hexdigest()}


def _load_tool_module(fname, modname):
    """载入 `tools/` 下的独立复刻件（数学只写一处，避免同一份公式两处各写一遍）。"""
    import importlib.util
    p = os.path.join(REPO, 'tools', fname)
    spec = importlib.util.spec_from_file_location(modname, p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_spline_ref():
    """载入 `tools/filmic-spline-ref.py`（computeFilmicSpline 的逐行 Python 对照实现）。

    单独放一个文件是为了可独立运行自检（`python3 tools/filmic-spline-ref.py`），
    避免同一份数学在这里和那里各写一遍。
    """
    return _load_tool_module('filmic-spline-ref.py', 'filmic_spline_ref')


def load_sigmoid_ref():
    """载入 `tools/sigmoid-params-ref.py`（darktable `commit_params` 的逐行 float32 复刻）。

    引擎 1 的 7 个 uniform **只做名字映射**，公式不在这里重写。
    """
    return _load_tool_module('sigmoid-params-ref.py', 'sigmoid_params_ref')


# 机械改写记录（判据可审计性：每一处改写都要能逐字回溯）
FILM_REWRITES = [
    ('#version 300 es → #version 330 core', '现有 to_core() 同一处改写（GLES3 → GL 330 core）'),
]


def film_stage(a):
    """执行 `kFsFilm`：读 `<out>/ccm_d4.npy`（D4），写 `<out>/film_d5.npy`（D5）。

    参数全部来自命令行（默认值 = FilmParams.h 的默认，逐槽在 add_argument 里标注），
    并把**每一个 uniform 喂了什么**写进 steps.json。
    """
    d4p = os.path.join(a.out, 'pc3d.npy' if a.film_input == 'pc3d' else 'ccm_d4.npy')
    if not os.path.exists(d4p):
        print('⚠ FILM 段跳过：%s 不存在（请先跑 P0/RCD/CCM 前段%s）'
              % (d4p, '与 --pc3d 1' if a.film_input == 'pc3d' else ''))
        return None
    ref = load_spline_ref()
    d4 = np.load(d4p)
    h, w = int(d4.shape[0]), int(d4.shape[1])

    sh = extract_shaders()
    # 引擎 shader 的 G2（ch==3）三目修正：kFsFilm 不含该模式（机械改写**未触发**），
    # 保留同一处修正以保证与上面各段同口径（幂等）。
    g2_hits = 0
    for _k in list(sh):
        if 'c == 1 ? m.g : m.b' in sh[_k]:
            sh[_k] = sh[_k].replace('c == 1 ? m.g : m.b',
                                    '(c == 1 || c == 3) ? m.g : m.b')
            if _k == 'kFsFilm':
                g2_hits += 1
    if 'kFsFilm' not in sh:
        raise RuntimeError('未能从 FrameRenderer.cpp 提取 kFsFilm')

    # ── 引擎选择（阶段 2）────────────────────────────────────────────────────
    #   --engine 0（默认）：行为与阶段 1 **一字不变**（仍是 `kFsFilm` 整份）⇒ 判据⑤
    #     （film_d5.npy CRC32 = 0x589f8364）是零回归守卫，不许被引擎 1 踩到。
    #   --engine 1：`head + kFsSigmoidDef + mid + kFsSigmoidMain + tail`，即 C++
    #     `filmSource(1)` 的组装顺序（head/mid/tail 走**锚点路径** = `filmSlices()`
    #     的真实语义；两个注入段按**字面量名**从 FrameRenderer.cpp 提取）。
    #     输出另存 `film_d5_sigmoid.npy`，**不覆盖** `film_d5.npy`。
    engine = int(getattr(a, 'engine', 0))
    anchor_info = None
    if engine == 0:
        fs_src, out_name = sh['kFsFilm'], 'film_d5.npy'
    elif engine == 1:
        for _nm in ('kFsSigmoidDef', 'kFsSigmoidMain'):
            if _nm not in sh:
                raise RuntimeError('引擎 1 注入段 %s 未从 FrameRenderer.cpp 提取到' % _nm)
        _blocks, anchor_info = anchor_film_blocks()
        _head, _def0, _mid, _main0, _tail = _blocks
        fs_src = _head + sh['kFsSigmoidDef'] + _mid + sh['kFsSigmoidMain'] + _tail
        # 与上面各段同口径的 G2 三目修正（幂等；sigmoid 段实测不含该模式）
        fs_src = fs_src.replace('c == 1 ? m.g : m.b', '(c == 1 || c == 3) ? m.g : m.b')
        out_name = 'film_d5_sigmoid.npy'
    else:
        raise SystemExit('--engine 只支持 0（filmic）/ 1（sigmoid）')

    # ── CPU 侧 filmic 系数（与真机 computeFilmicSpline 同口径）────────────────
    lat_sh = [float(a.film_lat_sh)] * 3
    lat_toe = [float(a.film_lat_toe)] * 3
    sp = ref.compute_filmic_spline(a.film_black_ev, a.film_white_ev,
                                   a.film_contrast, lat_sh, lat_toe)
    sat = [float(v) for v in a.film_sat.split(',')]
    split = [float(v) for v in a.film_split.split(',')]
    assert len(sat) == 3 and len(split) == 2, '--film-sat 需 3 值、--film-split 需 2 值'

    uniform_film = {}
    # 逐通道手写（与真机 passFilm 的 glUniform3f 一一对应：vec3 的 .r/.g/.b = R/G/B）
    for nm, seg in (('uFilmicToeM', 0), ('uFilmicShM', 1)):
        for i in range(1, 6):
            uniform_film['%s%d' % (nm, i)] = tuple(float(sp['m%d' % i][seg][ch]) for ch in range(3))
    uniform_film['uFilmicLinM1'] = tuple(float(sp['m1'][2][ch]) for ch in range(3))
    uniform_film['uFilmicLinM2'] = tuple(float(sp['m2'][2][ch]) for ch in range(3))
    uniform_film['uFilmicLatMin'] = tuple(float(v) for v in sp['lat_min'])
    uniform_film['uFilmicLatMax'] = tuple(float(v) for v in sp['lat_max'])
    uniform_film['uFilmicBlackEv'] = float(sp['black_ev'])
    uniform_film['uFilmicDr'] = float(sp['dynamic_range'])
    uniform_film['uFilmicInputMin'] = float(sp['input_min'])
    uniform_film['uFilmicInputMax'] = float(sp['input_max'])
    uniform_film['uFilmicPower'] = float(sp['output_power'])
    uniform_film['uFilmicBlackDisp'] = float(sp['black_disp'])
    # ── 其余 uniform（逐条来源见 steps.json 的 film.uniforms）──────────────────
    uniform_film.update({
        'uExposure': float(2.0 ** a.film_ev),
        'uGamut': float(a.film_gamut),
        'uHalDomainMap': float(1.0 if a.film_hal_domain else 0.0),
        'uDomainRatio': float(a.film_domain_ratio),
        'uSat': tuple(sat),
        'uSplit': tuple(split),
        'uMono': float(a.film_mono),
        'uToneK': float(a.film_tone_k),
        'uLook': float(a.film_look),
        'uLookSat': float(a.film_look_sat),
        'uLookChroma': float(a.film_look_chroma),
        # ↓↓ 旁路段：真机喂在线统计量，离线给恒等/兜底值（见文件头注释）
        'uLocalStrength': float(a.film_local),
        'uLocalKnee': 0.6,      # FrameRenderer.cpp:270 kLocalKnee
        'uLocalKneeHi': 1.3,    # FrameRenderer.cpp:270 kLocalKneeHi
        'uLocalRef': 0.5,       # passFilm 内 local_ref 兜底（局部图未生成时的中灰）
        'uLocalSize': (1.0, 1.0),
    })
    # ── 引擎 1（sigmoid）的 7 个私有 uniform ─────────────────────────────────
    # 数值来源 = `tools/sigmoid-params-ref.py` 的 `compute_sigmoid_params()`
    # （darktable `commit_params` 的逐行 float32 复刻）—— **本脚本不重写公式**，
    # 只做「契约字段名 → uniform 名」的映射。默认参数 = `SigmoidParams.h` 的
    # kSigmoidDefault*（contrast=1.2 / skew=0 / white=100 / black=0.0152 / hue=100）。
    sig_params = None
    if engine == 1:
        sref = load_sigmoid_ref()
        sig_params = sref.compute_sigmoid_params(
            a.sig_contrast, a.sig_skew, a.sig_white, a.sig_black, a.sig_hue)
        uniform_film.update({
            'uSigWhiteTarget': float(sig_params['white_target']),
            'uSigBlackTarget': float(sig_params['black_target']),
            'uSigPaperExposure': float(sig_params['paper_exposure']),
            'uSigFilmFog': float(sig_params['film_fog']),
            'uSigFilmPower': float(sig_params['film_power']),
            'uSigPaperPower': float(sig_params['paper_power']),
            'uSigHuePreservation': float(sig_params['hue_preservation']),
        })

    gl = GL(w, h)
    written = []
    try:
        tex_d4 = gl.ctx.texture((w, h), 4, dtype='f2')
        tex_d4.write(d4.astype(np.float16).tobytes())   # 目标纹理是 RGBA16F，与上面各段同
        tex_d4.filter = (moderngl.NEAREST, moderngl.NEAREST)
        # uLocal 是 sampler2D：即使 uLocalStrength=0 整段短路，**也必须有合法绑定**
        # （v0.6.0-m4 全黑事故的成因；X300/Mali 上未绑定 sampler 是 UB）。
        tex_local = gl.ctx.texture((1, 1), 4, dtype='f2')
        tex_local.write(np.ones((1, 1, 4), np.float16).tobytes())
        tex_local.filter = (moderngl.NEAREST, moderngl.NEAREST)
        d5 = gl.run(fs_src, uniform_film,
                    {'uTex': tex_d4, 'uLocal': tex_local}, report=written)
    finally:
        gl.close()
    np.save(os.path.join(a.out, out_name), d5)

    info = {
        'engine': {
            'slot': engine,
            'name': 'filmic' if engine == 0 else 'sigmoid',
            'assembly': ('kFsFilm（整份字面量，与阶段 1 一字不变）' if engine == 0 else
                         'head + kFsSigmoidDef + mid + kFsSigmoidMain + tail'
                         '（= C++ filmSource(1) 的组装顺序）'),
            'template_slicing': ('n/a（引擎 0 不走锚点路径）' if engine == 0 else
                                 'anchor-splice（复刻 C++ filmSlices()：a=%d b=%d c=%d d=%d）'
                                 % (anchor_info['offsets']['a'], anchor_info['offsets']['b'],
                                    anchor_info['offsets']['c'], anchor_info['d'])),
            'injection_sources': ({'kFsSigmoidDef': 'extract_shaders()["kFsSigmoidDef"]',
                                   'kFsSigmoidMain': 'extract_shaders()["kFsSigmoidMain"]'}
                                  if engine == 1 else {}),
            'shader_digest': _src_digest(fs_src),
        },
        'shader': 'kFsFilm' if engine == 0 else 'kFsSigmoidDef + kFsSigmoidMain（注入段）',
        'input': ('pc3d.npy (D4，PC3D 输出)' if a.film_input == 'pc3d'
                  else 'ccm_d4.npy (D4，PC3D 未接入 FILM 输入)'),
        'output': '%s (D5, display 域线性)' % out_name,
        'domain': 'RAW 静拍链口径（uHalDomainMap=%d，uDomainRatio=%.4f）'
                  % (1 if a.film_hal_domain else 0, a.film_domain_ratio),
        'shader_rewrites': [r[0] + ' ← ' + r[1] for r in FILM_REWRITES]
                           + (['kFsFilm 内 G2 三目修正'] if g2_hits else []),
        'sigmoid_params': ({k: float(v) for k, v in sig_params.items()} if sig_params else None),
        'sigmoid_cli': ({'contrast': a.sig_contrast, 'skew': a.sig_skew, 'white': a.sig_white,
                         'black': a.sig_black, 'hue': a.sig_hue,
                         'defaults': 'SigmoidParams.h kSigmoidDefault*（1.2 / 0 / 100 / 0.0152 / 100）'}
                        if engine == 1 else None),
        'filmic_spline': {
            'source': 'FrameRenderer::computeFilmicSpline (FrameRenderer.cpp:13044) '
                      '的 Python 逐行对照实现 tools/filmic-spline-ref.py',
            'black_ev': sp['black_ev'], 'white_ev': sp['white_ev'],
            'dynamic_range': sp['dynamic_range'], 'output_power': sp['output_power'],
            'black_disp': sp['black_disp'], 'grey_log': sp['grey_log'],
            'contrast': sp['contrast'], 'contrast_param': sp['contrast_param'],
            'input_min': sp['input_min'], 'input_max': sp['input_max'],
            'lat_sh': sp['lat_sh'], 'lat_toe': sp['lat_toe'],
            'lat_min': sp['lat_min'], 'lat_max': sp['lat_max'],
        },
        'uniforms': {k: (list(v) if isinstance(v, tuple) else v)
                     for k, v in sorted(uniform_film.items())},
        # 每个 uniform 的**来源**（报告里要能逐条回溯）
        'uniform_sources': {
            **{k: 'computeFilmicSpline 逐行复刻（tools/filmic-spline-ref.py）'
               for k in uniform_film if k.startswith('uFilmic')},
            **{k: 'compute_sigmoid_params（tools/sigmoid-params-ref.py，逐行复刻 darktable '
                  'commit_params；入参 = --sig-* CLI）'
               for k in uniform_film if k.startswith('uSig')},
            'uExposure': '--film-ev %g ⇒ uExposure = 2^ev' % a.film_ev,
            'uGamut': '--film-gamut %g（FilmParams[40]；**引擎 1 无此 uniform ⇒ 安全跳过**）'
                      % a.film_gamut,
            'uHalDomainMap': '--film-hal-domain %d' % a.film_hal_domain,
            'uDomainRatio': '--film-domain-ratio %g' % a.film_domain_ratio,
            'uSat': '--film-sat %s（FilmParams[20,21,22]）' % a.film_sat,
            'uSplit': '--film-split %s（FilmParams[23,24]）' % a.film_split,
            'uMono': '--film-mono %g（FilmParams[25]）' % a.film_mono,
            'uToneK': '--film-tone-k %g（FilmParams[28]）' % a.film_tone_k,
            'uLook': '--film-look %g（FilmParams[31]）' % a.film_look,
            'uLookSat': '--film-look-sat %g（FilmParams[32]）' % a.film_look_sat,
            'uLookChroma': '--film-look-chroma %g（FilmParams[33]）' % a.film_look_chroma,
            'uLocalStrength': '--film-local %g（**离线默认 0 = 整段旁路**）' % a.film_local,
            'uLocalKnee': 'FrameRenderer.cpp kLocalKnee 常量 0.6',
            'uLocalKneeHi': 'FrameRenderer.cpp kLocalKneeHi 常量 1.3',
            'uLocalRef': 'passFilm 内 local_ref 兜底 0.5（离线无真机统计量）',
            'uLocalSize': '1×1 占位（uLocal sampler 必须有合法绑定，否则 UB）',
        },
        # 实际写入 program 的 uniform（`if k in prog` 是安全跳过式）—— 直接证明
        # "uSig* 7 个全给了"以及"引擎 1 里不存在的 uGamut/uFilmic* 被跳过"
        'uniforms_written': written,
        'uniforms_skipped': sorted(set(uniform_film) - set(written)),
        'not_in_offline_chain': [
            'HR 高光重建（[45] hr_raw 真机默认 1.0）：输入是它的输出，本段直接吃 ccm_d4',
            'PC3D（[54] pc3d 真机默认 1.0）：不在本段内（见 --pc3d）',
            'defringe（[47]）/ 多尺度色度降噪（[52]）：不在本段内',
            'uLocal* 局部色调映射：依赖真机 32×24 低频图中位 ⇒ uLocalStrength=0 旁路',
            'OETF/颗粒/dither/LUT/锐化（kFsOut）：本段输出是 **OETF 前**的线性显示域',
        ],
        'digest': digest_f32(d5),
    }
    print('FILM 段（引擎 %d / %s）：%s %s'
          % (engine, info['engine']['name'], out_name, info['digest']))
    print('  shader %s' % info['engine']['shader_digest'])
    print('  uniform 写入 %d 个；跳过 %d 个%s'
          % (len(written), len(info['uniforms_skipped']),
             ('（%s）' % ', '.join(info['uniforms_skipped']) if info['uniforms_skipped'] else '')))
    _merge_steps(a.out, 'film', info)
    return info


def _merge_steps(out, key, value):
    """把新段信息并入已有 steps.json（前段写的那份原样保留）。"""
    p = os.path.join(out, 'steps.json')
    doc = json.load(open(p)) if os.path.exists(p) else {}
    doc.setdefault('steps', {})[key] = value
    json.dump(doc, open(p, 'w'), indent=2)


# ═══════════════════════════════════════════════════════════════════════════════
# PC3D 段（`kFsPc3d` + Down/Up，D4 → D4）— `--pc3d 1`
#
# 真机位置：P4 之后、kFsFilm **之前**（`[54] pc3d` 真机默认 1.0 = 替换档），
# 与 kFsFilm 的输入等价性判据**无关**（阶段 1 两边都不变即等价），但它决定
# `film_d5` 的真实性 ⇒ 一并接入。
#
# 与真机的差异（写进 steps.json）：
#   · **LSC = 恒等**（1×1 全 1 纹理，与 RCD 段的 `tex_lsc` 同做法）：真机是标定好的
#     逐通道镜头阴影增益图 ⇒ 本段在边缘/暗角的噪声权重与真机**有偏差**。
#   · 输入 = `ccm_d4`（跳过 P5 多尺度色度降噪 / 色度平滑 tex_cs_）。
#   · `sigA/sigB`（噪声模型 p4a/p4b）= 命令行参数；**默认值不是真机标定值**。
# ═══════════════════════════════════════════════════════════════════════════════
K_PC3D_NOISE_TO_KERNEL = 24.0     # FrameRenderer.cpp:9214
K_PC3D_NOISE_TARGET = 1.0 / 256.0  # :9215
K_PC3D_MIN_SIZE = 7               # :9216
K_PC3D_MAX_SIZE = 21              # :9217
K_PC3D_MOIRE = 1.5                # :9218
K_PC3D_LUMA = 0.8                 # :9219


def pc3d_stage(a):
    d4p = os.path.join(a.out, 'ccm_d4.npy')
    if not os.path.exists(d4p):
        print('⚠ PC3D 段跳过：%s 不存在' % d4p)
        return None
    d4 = np.load(d4p)
    h, w = int(d4.shape[0]), int(d4.shape[1])
    sh = extract_shaders()
    for k in list(sh):
        if 'c == 1 ? m.g : m.b' in sh[k]:
            sh[k] = sh[k].replace('c == 1 ? m.g : m.b',
                                  '(c == 1 || c == 3) ? m.g : m.b')

    sigA, sigB = float(a.pc3d_siga), float(a.pc3d_sigb)
    # ② 核尺寸（照抄 ESD3D2.java:89-94，见 passPc3d 内注释）
    noise_mpy = max((sigA + sigB) / K_PC3D_NOISE_TARGET, 1e-7)
    kernel_size = 1.0 + np.sqrt(noise_mpy) * K_PC3D_NOISE_TO_KERNEL
    msize = min(K_PC3D_MIN_SIZE + int(kernel_size) - int(kernel_size) % 2,
                K_PC3D_MAX_SIZE)
    if a.pc3d_msize > 0:                       # FilmParams[55] 强制核尺寸
        forced = int(a.pc3d_msize + 0.5)
        forced += (forced % 2 == 0)
        msize = min(max(forced, K_PC3D_MIN_SIZE), K_PC3D_MAX_SIZE)
    # ③ 尺度（照抄 ESD3D2.java:126-129）
    n_sqrt = np.sqrt(0.5 * sigA + sigB)
    scale_f = float(min(max(n_sqrt / K_PC3D_NOISE_TARGET, 1.0), 4.0))
    scale = int(min(max(int(scale_f + 0.5), 1), 4))
    lw, lh = max(1, w // scale), max(1, h // scale)

    gl = GL(w, h)
    try:
        tex_src = gl.ctx.texture((w, h), 4, dtype='f2')
        tex_src.write(d4.astype(np.float16).tobytes())
        tex_src.filter = (moderngl.NEAREST, moderngl.NEAREST)
        # LSC = 恒等（1×1 全 1，3 通道 —— 与上面 RCD 段的 tex_lsc 同做法）
        tex_lsc = gl.ctx.texture((1, 1), 3, dtype='f2')
        tex_lsc.write(np.ones((1, 1, 3), np.float16).tobytes())
        tex_lsc.filter = (moderngl.NEAREST, moderngl.NEAREST)

        def as_tex(arr):
            t = gl.ctx.texture((arr.shape[1], arr.shape[0]), 4, dtype='f2')
            t.write(arr.astype(np.float16).tobytes())
            t.filter = (moderngl.NEAREST, moderngl.NEAREST)
            return t

        def run_pc3d(src_tex, tw, th, ms, moire, sA, sB):
            return gl.run(sh['kFsPc3d'], {
                'uSize': (tw, th), 'uK': (ms - 1) // 2,
                'uKernel': float(kernel_size), 'uSigA': sA, 'uSigB': sB,
                'uMoire': moire, 'uLuma': K_PC3D_LUMA, 'uLscSize': (1, 1),
            }, {'uTex': src_tex, 'uLsc': tex_lsc}, size=(tw, th))

        trace = []
        if scale > 1:
            sc = max(scale_f * 0.75, 1e-3)
            sig_a1, sig_b1 = sigA / sc, sigB / sc
            stride = max(1, scale // 2)
            blur = scale * 0.5
            lo0 = gl.run(sh['kFsPc3dDown'], {
                'uSize': (w, h), 'uResize': scale, 'uStride': stride,
                'uSigma': blur / stride,
            }, {'uTex': tex_src}, size=(lw, lh))
            mp1 = max((sig_a1 + sig_b1) / K_PC3D_NOISE_TARGET, 1e-7)
            ks1 = 1.0 + np.sqrt(mp1) * K_PC3D_NOISE_TO_KERNEL
            ms1 = min(K_PC3D_MIN_SIZE + int(ks1) - int(ks1) % 2, K_PC3D_MAX_SIZE)
            if a.pc3d_msize > 0:
                ms1 = msize
            lo1 = run_pc3d(as_tex(lo0), lw, lh, ms1, 0.0, sig_a1, sig_b1)
            t1 = gl.run(sh['kFsPc3dUp'], {
                'uLowSize': (lw, lh), 'uHiSize': (w, h), 'uR': scale,
            }, {'uLow': as_tex(lo1), 'uGuideHi': tex_src}, size=(w, h))
            trace.append('down(%dx%d)+pc3d(ms=%d)+guidedUp(r=%d)' % (lw, lh, ms1, scale))
        else:
            t1 = run_pc3d(tex_src, w, h, msize, 0.0, sigA, sigB)
            trace.append('全分辨率 pc3d(ms=%d) 单趟（scale=1）' % msize)
        out = run_pc3d(as_tex(t1), w, h, msize, K_PC3D_MOIRE, sigA, sigB)
        trace.append('pc3d(ms=%d, moire=%.1f) 第二趟' % (msize, K_PC3D_MOIRE))
    finally:
        gl.close()
    np.save(os.path.join(a.out, 'pc3d.npy'), out)

    info = {
        'shader': 'kFsPc3d (+kFsPc3dDown/+kFsPc3dUp)',
        'input': 'ccm_d4.npy (D4)', 'output': 'pc3d.npy (D4，与输入同域)',
        'pipeline': trace,
        'params': {'sigA': sigA, 'sigB': sigB, 'noiseTarget': K_PC3D_NOISE_TARGET,
                   'noiseToKernel': K_PC3D_NOISE_TO_KERNEL, 'kernelSize': float(kernel_size),
                   'msize': msize, 'scaleF': scale_f, 'scale': scale, 'luma': K_PC3D_LUMA,
                   'moire_pass2': K_PC3D_MOIRE},
        'lsc': '**恒等**（1×1 全 1）⇒ 与真机（标定 LSC 增益图）有偏差：'
               '边缘/暗角的噪声权重与真机不同',
        'sig_source': '**命令行参数，不是真机噪声标定值**（真机 = NoiseCoef p4a/p4b）',
        'not_in_offline_chain': ['P5 多尺度色度降噪 / 色度平滑（真机 PC3D 的输入是 tex_cs_）'],
        'digest': digest_f32(out),
    }
    print('PC3D 段：pc3d.npy %s' % info['digest'])
    _merge_steps(a.out, 'pc3d', info)
    return info


def _film_cli(argv):
    """只解析本段自己的参数（用 parse_known_args ⇒ 前段参数不认识也不报错）。"""
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument('--out', default='/tmp/chain')
    ap.add_argument('--no-film', action='store_true')
    ap.add_argument('--film-black-ev', type=float, default=-7.65)
    ap.add_argument('--film-white-ev', type=float, default=4.56)
    ap.add_argument('--film-contrast', type=float, default=1.0)
    ap.add_argument('--film-lat-sh', type=float, default=0.01)
    ap.add_argument('--film-lat-toe', type=float, default=0.01)
    ap.add_argument('--film-tone-k', type=float, default=1.0)
    ap.add_argument('--film-gamut', type=float, default=1.0)
    ap.add_argument('--film-ev', type=float, default=0.0)
    ap.add_argument('--film-look', type=float, default=1.0)
    ap.add_argument('--film-look-sat', type=float, default=1.0)
    ap.add_argument('--film-look-chroma', type=float, default=1.0)
    ap.add_argument('--film-sat', default='1,1,1')
    ap.add_argument('--film-split', default='0,0')
    ap.add_argument('--film-mono', type=float, default=0.0)
    ap.add_argument('--film-local', type=float, default=0.0)
    ap.add_argument('--film-hal-domain', type=int, default=0)
    ap.add_argument('--film-domain-ratio', type=float, default=2.0)
    ap.add_argument('--film-input', choices=['ccm', 'pc3d'], default='ccm')
    # ── 引擎插槽（阶段 2）：与 main() 的声明同款（两处都声明 = 现有模式）────────
    ap.add_argument('--engine', type=int, choices=[0, 1], default=0)
    ap.add_argument('--sig-contrast', type=float, default=1.2)
    ap.add_argument('--sig-skew', type=float, default=0.0)
    ap.add_argument('--sig-white', type=float, default=100.0)
    ap.add_argument('--sig-black', type=float, default=0.0152)
    ap.add_argument('--sig-hue', type=float, default=100.0)
    # ── PC3D 段（--pc3d 1）───────────────────────────────────────────────
    ap.add_argument('--pc3d', type=int, default=0)
    ap.add_argument('--pc3d-siga', type=float, default=1e-5,
                    help='噪声模型斜率（真机 = NoiseCoef p4a 的 (0.25,0.5,0.25) 加权）；'
                         '默认 1e-5 与 P0 段占位值同，**不是真机标定值**')
    ap.add_argument('--pc3d-sigb', type=float, default=1e-6,
                    help='噪声模型截距（真机 = p4b 同款加权）；默认 1e-6 同上')
    ap.add_argument('--pc3d-msize', type=float, default=0.0,
                    help='FilmParams[55] 强制核尺寸（奇数 7..21）；0 = 自动（PC 公式）')
    a, _ = ap.parse_known_args(argv)
    return a


if __name__ == '__main__':
    # 走到这里时上面的 main() 已经跑完（P0/RCD/CCM 产物已落盘、基线摘要不受影响）。
    _a = _film_cli(sys.argv[1:])
    if _a.pc3d:
        pc3d_stage(_a)
    if _a.no_film:
        print('（--no-film：跳过 FILM 段）')
    else:
        film_stage(_a)

