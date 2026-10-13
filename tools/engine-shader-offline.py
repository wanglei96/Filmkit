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


    def run(self, fs_src, uniforms, textures, size=None):
        """执行一个 fragment shader。textures: {uniform名: moderngl.Texture}
        size=(w,h) 时用该尺寸的 framebuffer（同 context，可复用纹理）。"""
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
        for k, v in uniforms.items():
            if k in prog:
                prog[k].value = v
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


if __name__ == '__main__':
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


def load_spline_ref():
    """载入 `tools/filmic-spline-ref.py`（computeFilmicSpline 的逐行 Python 对照实现）。

    单独放一个文件是为了可独立运行自检（`python3 tools/filmic-spline-ref.py`），
    避免同一份数学在这里和那里各写一遍。
    """
    import importlib.util
    p = os.path.join(REPO, 'tools', 'filmic-spline-ref.py')
    spec = importlib.util.spec_from_file_location('filmic_spline_ref', p)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


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

    gl = GL(w, h)
    try:
        tex_d4 = gl.ctx.texture((w, h), 4, dtype='f2')
        tex_d4.write(d4.astype(np.float16).tobytes())   # 目标纹理是 RGBA16F，与上面各段同
        tex_d4.filter = (moderngl.NEAREST, moderngl.NEAREST)
        # uLocal 是 sampler2D：即使 uLocalStrength=0 整段短路，**也必须有合法绑定**
        # （v0.6.0-m4 全黑事故的成因；X300/Mali 上未绑定 sampler 是 UB）。
        tex_local = gl.ctx.texture((1, 1), 4, dtype='f2')
        tex_local.write(np.ones((1, 1, 4), np.float16).tobytes())
        tex_local.filter = (moderngl.NEAREST, moderngl.NEAREST)
        d5 = gl.run(sh['kFsFilm'], uniform_film,
                    {'uTex': tex_d4, 'uLocal': tex_local})
    finally:
        gl.close()
    np.save(os.path.join(a.out, 'film_d5.npy'), d5)

    info = {
        'shader': 'kFsFilm',
        'input': ('pc3d.npy (D4，PC3D 输出)' if a.film_input == 'pc3d'
                  else 'ccm_d4.npy (D4，PC3D 未接入 FILM 输入)'),
        'output': 'film_d5.npy (D5, display 域线性)',
        'domain': 'RAW 静拍链口径（uHalDomainMap=%d，uDomainRatio=%.4f）'
                  % (1 if a.film_hal_domain else 0, a.film_domain_ratio),
        'shader_rewrites': [r[0] + ' ← ' + r[1] for r in FILM_REWRITES]
                           + (['kFsFilm 内 G2 三目修正'] if g2_hits else []),
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
        'not_in_offline_chain': [
            'HR 高光重建（[45] hr_raw 真机默认 1.0）：输入是它的输出，本段直接吃 ccm_d4',
            'PC3D（[54] pc3d 真机默认 1.0）：不在本段内（见 --pc3d）',
            'defringe（[47]）/ 多尺度色度降噪（[52]）：不在本段内',
            'uLocal* 局部色调映射：依赖真机 32×24 低频图中位 ⇒ uLocalStrength=0 旁路',
            'OETF/颗粒/dither/LUT/锐化（kFsOut）：本段输出是 **OETF 前**的线性显示域',
        ],
        'digest': digest_f32(d5),
    }
    print('FILM 段：film_d5.npy %s' % info['digest'])
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

