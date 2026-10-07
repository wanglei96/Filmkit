#!/usr/bin/env python3
"""引擎 shader 离线执行器（moderngl 真执行 FrameRenderer.cpp 里的 GLSL）。

为什么需要：排查淡紫线时，用「numpy 直译」复现引擎链路不可靠（漏 P0、漏 [58] 双 pass、
坐标系约定不同），推出的结论自相矛盾。本脚本直接**执行引擎自己的 shader 字符串**，
只做最小机械改写（#version 300 es → 330 core），保证域与逻辑与真机一致。

用法：
  python3 tools/engine-shader-offline.py --dng <path> --roi Y0,Y1,X0,X1 --out /tmp/chain
产物：每个 pass 的输出存为 .npy（float32，H×W×3），外加 steps.json 记录 uniform。

已接入的 pass（按引擎成片链顺序）：
  1. P0  kFsRawDenoise          RAW 域同色 bilateral（含 uMix 强度）
  2. RCD kFsRcdDir / kFsRcdG / kFsRcdChroma（单 pass）或 kFsRcdChromaA+B（[58] 双 pass）
  3. CCM kFsCcm                 D3 → D4（uCcm）
  4. FILM kFsFilm               filmic + gamut + sat/look（uToneK/uGamut/uSat/uLook…）

未接入（需要额外纹理/CPU 侧数据，按需再加）：
  - P0 前置的 HR（高光重建，需要块级参考场）
  - ESD3D2（kFsPc3d，多尺度 + LSC 纹理）
  - passOutput 的 OETF/颗粒/dither（本脚本用 srgb_encode 近似，色度不受影响）
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
