#!/usr/bin/env python3
"""Execute the actual HighlightMergeShader.h on OpenGL; compare NumPy PC kernel.

  python3 tools/check-highlight-merge.py
  python3 tools/check-highlight-merge.py --require-static

Dependencies: numpy, moderngl, glfw (macOS); Linux can use standalone EGL.
No engine/tool changes and no synthetic brightness/inpaint patch. The only GLSL
translation is '#version 300 es' -> '330 core' for desktop execution. Textures
really are R16UI inputs, R8 masks/weights, RG32F or RG16F flow, and R16F output.
CPU reference independently implements PC's unnormalized additive dual-green
kernel then resolve. It includes the explicitly documented project adaptations:
coarse temporal solver and final WB. CameraWhite is intentionally NOT applied
before demosaic. MRT RGBA32F noise is checked against independent pairwise
covariance propagation, including border taps aliased by mirror/clamp.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, replace

import numpy as np
import moderngl

ROOT = pathlib.Path(__file__).resolve().parents[1]
HEADER = ROOT / 'core/engine/src/render/HighlightMergeShader.h'
CFA = {'RGGB': 0x94, 'GRBG': 0x61, 'GBRG': 0x49, 'BGGR': 0x16}


def source():
    text = HEADER.read_text(encoding='utf-8')
    match = re.search(r'inline constexpr const char\* kFsHighlightMerge\s*=\s*R"\((.*?)\)";', text, re.S)
    if not match:
        raise AssertionError('complete inline constexpr GLSL literal missing')
    return match.group(1)


def linear(texture, uv):
    """GL_LINEAR + CLAMP_TO_EDGE, normalized UV, no implicit axis flips."""
    h, w = texture.shape[:2]
    p = uv * np.array([w, h]) - .5
    i = np.floor(p).astype(np.int64)
    f = p - i
    x0, y0 = np.clip(i[..., 0], 0, w-1), np.clip(i[..., 1], 0, h-1)
    x1, y1 = np.clip(i[..., 0]+1, 0, w-1), np.clip(i[..., 1]+1, 0, h-1)
    if texture.ndim == 3:
        f = f[..., None, :]
    a = texture[y0, x0] * (1-f[..., 0]) + texture[y0, x1] * f[..., 0]
    b = texture[y1, x0] * (1-f[..., 0]) + texture[y1, x1] * f[..., 0]
    return a * (1-f[..., 1]) + b * f[..., 1]


def mirror(p, extent):
    # Deliberately PC's single mirror + clamp, NOT repeating reflect modulo.
    p = np.where(p < 0, -p-1, p)
    p = np.where(p >= extent, 2*extent-p-1, p)
    return np.clip(p, 0, extent-1)


def linear_kernel_mask(rejection):
    """PC UpdateLinearKernelMask: strict 3x3 equality in RAW/4 domain."""
    pad = np.pad(rejection, 1, mode='edge')
    h, w = rejection.shape
    taps = np.stack([pad[y:y+h, x:x+w] for y in range(3) for x in range(3)])
    return (np.max(taps, axis=0) != np.min(taps, axis=0)).astype(np.uint8)*255


def maximum_merge_weight(base_read, alternate_read, scale):
    return min(50., base_read/(alternate_read*scale*scale))


def kernel_sigma(reference_snr, count, weight):
    base = np.interp(reference_snr*math.sqrt(count), [14.5, 29.5, 44.], [.6, .42, .35])
    multiplier = np.interp(weight, [10., 30.], [1., 1.414000034332275])
    return 1./(base*multiplier)


@dataclass
class Case:
    name: str
    raw: list
    off: list
    weights: np.ndarray
    flow: np.ndarray
    mask: np.ndarray
    linear_mask: np.ndarray
    cfa: int = CFA['RGGB']
    black: tuple = (64., 80., 96., 112.)
    white: float = 4095.
    gain: tuple = (2., 1., 1.5)
    scales: tuple = (1., 1., 4., 1.)
    short: int = 2
    sigma: tuple = (2., 2.3, 1.7, 2.8)
    global_weight: tuple = (1., .9, .03125, 1.2)
    noise_shot: tuple = (.0007, .0011, .0017)
    noise_read: tuple = (2.e-6, 3.e-6, 5.e-6)
    noise_gain_ratio: tuple = (1., .7, 1.5, 2.)
    flow_half: bool = False


def cpu_reference(c, with_noise=False):
    h, w = c.raw[0].shape
    yy, xx = np.mgrid[:h, :w]
    phase = (xx & 1) + 2*(yy & 1)
    qx, qy = xx//2, yy//2
    tile_x, tile_y = qx//8, qy//8
    wx, wy = qx % 8, qy % 8
    colors = np.array([(c.cfa >> (2*k)) & 3 for k in range(4)])
    black = np.array(c.black)
    uv = np.stack([xx+.5, yy+.5], axis=-1)/np.array([w, h])
    quad_uv = np.stack([2*qx+1., 2*qy+1.], axis=-1)/np.array([w, h])
    coarse_h, coarse_w = c.off[0].shape[:2]
    temporal_uv = np.stack([xx+.5, yy+.5], axis=-1)/np.array([coarse_w*128, coarse_h*128])
    accepted = c.short in (1, 2, 3)
    m = linear(c.mask.astype(float)/255., uv) if accepted else np.zeros((h, w))
    lm = linear(c.linear_mask.astype(float)/255., quad_uv) if accepted else np.zeros((h, w))
    temporal = linear(c.weights.astype(float)/255., temporal_uv)
    total = np.zeros((h, w))
    support = np.zeros((h, w))
    noise_total = np.zeros((h, w, 3))
    short_support = np.zeros((h, w))
    for frame in range(4):
        if frame == 0:
            flow = np.zeros((h, w, 2))
        elif frame == c.short:
            flow = c.flow[np.minimum(tile_y, c.flow.shape[0]-1),
                          np.minimum(tile_x, c.flow.shape[1]-1)].astype(float)
        else:
            flow = linear(c.off[frame-1].astype(float), temporal_uv)
        tile_px = tile_x*8 + flow[..., 0]*.5
        tile_py = tile_y*8 + flow[..., 1]*.5
        anchor_x, anchor_y = np.rint(tile_px).astype(int), np.rint(tile_py).astype(int)
        residual_x, residual_y = 2*(anchor_x-tile_px), 2*(anchor_y-tile_py)
        anchor_x, anchor_y = anchor_x+wx, anchor_y+wy
        intensity, sw = np.zeros((h, w)), np.zeros((h, w))
        frame_noise = np.zeros((h, w, 3))
        previous_taps = []
        shot = np.array(c.noise_shot)[colors[phase]]*c.noise_gain_ratio[frame]
        read = np.array(c.noise_read)[colors[phase]]*c.noise_gain_ratio[frame]**2
        scale = c.scales[frame]
        for y in range(-1, 2):
            dy = residual_y+2*y
            for x in range(-1, 2):
                dx = residual_x+2*x
                sx, sy = mirror(anchor_x+x, w//2), mirror(anchor_y+y, h//2)
                for dual in (False, True):
                    sample_phase = 3-phase if dual else phase
                    sxoff = (sample_phase & 1)-(phase & 1) if dual else 0
                    syoff = (sample_phase >> 1)-(phase >> 1) if dual else 0
                    ox, oy = dx+sxoff, dy+syoff
                    valid = (np.abs(dy) <= 2.5) & (np.abs(ox) <= 1.5) & (np.abs(oy) <= 1.5)
                    if dual:
                        valid &= colors[phase] == 1
                    gaussian = np.exp(-.5*(ox*ox+oy*oy)*c.sigma[frame]**2)
                    tent = np.maximum(1-np.sqrt(ox*ox+oy*oy)*.4, 0.)
                    weight = ((1-lm)*gaussian+lm*tent)*valid
                    code = c.raw[frame][sy*2+(sample_phase >> 1), sx*2+(sample_phase & 1)].astype(float)
                    gain = c.scales[frame]/np.maximum(c.white-black[sample_phase], 1.)
                    normalized = code*gain-black[sample_phase]*gain
                    intensity += normalized*weight
                    sw += weight
                    if with_noise:
                        key = (sy*2+(sample_phase >> 1))*w+sx*2+(sample_phase & 1)
                        # Independent oracle: sum diagonal variance PLUS all
                        # pairwise covariance terms for identical sensor pixels.
                        w2 = weight*weight
                        for old_key, old_weight in previous_taps:
                            w2 += 2*weight*old_weight*(key == old_key)
                        previous_taps.append((key, weight))
                        actual_var = (shot*np.maximum(normalized/scale, 0)+read)*scale**2
                        frame_noise[..., 0] += w2*actual_var
                        frame_noise[..., 1] += w2*shot*scale
                        frame_noise[..., 2] += w2*read*scale**2
        if frame == c.short:
            fw = m  # short global ALWAYS 1; deliberately ignore global_weight[short]
        else:
            fw = (1-m)*c.global_weight[frame]
            if frame:
                fw = fw*temporal[..., frame-1]
        total += intensity*fw
        support += sw*fw
        if with_noise:
            noise_total += frame_noise*(fw*fw)[..., None]
            if frame == c.short:
                short_support = sw*fw
    denominator = np.maximum(support, 1e-8)
    wb = np.array(c.gain)[colors[phase]]
    image = (total/denominator*wb).astype(np.float32)
    if not with_noise:
        return image
    noise_total *= np.stack([wb*wb, wb, wb*wb], axis=-1)/(denominator**2)[..., None]
    noise = np.concatenate([noise_total, (short_support/denominator)[..., None]], axis=-1)
    return image, noise.astype(np.float32)


class GPU:
    def __init__(self, shader):
        self.window = None
        if sys.platform == 'darwin':
            import glfw
            if not glfw.init():
                raise RuntimeError('glfw.init failed')
            glfw.window_hint(glfw.VISIBLE, glfw.FALSE)
            glfw.window_hint(glfw.CONTEXT_VERSION_MAJOR, 4)
            glfw.window_hint(glfw.CONTEXT_VERSION_MINOR, 1)
            glfw.window_hint(glfw.OPENGL_PROFILE, glfw.OPENGL_CORE_PROFILE)
            glfw.window_hint(glfw.OPENGL_FORWARD_COMPAT, glfw.TRUE)
            self.window = glfw.create_window(32, 32, 'highlight-merge-regression', None, None)
            if not self.window:
                raise RuntimeError('glfw.create_window failed')
            glfw.make_context_current(self.window)
            self.ctx = moderngl.create_context()
        else:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=330)
        vs = '''#version 330 core
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}'''
        self.program = self.ctx.program(vertex_shader=vs,
            fragment_shader=shader.replace('#version 300 es', '#version 330 core'))
        self.vao = self.ctx.vertex_array(self.program, [])

    def run(self, c, target_dtype='f2', with_noise=False):
        h, w = c.raw[0].shape
        textures = []
        def bind(name, array, dtype, filtered=False):
            channels = array.shape[2] if array.ndim == 3 else 1
            tex = self.ctx.texture((array.shape[1], array.shape[0]), channels,
                                   np.ascontiguousarray(array).tobytes(), dtype=dtype,
                                   alignment=1)
            tex.filter = (moderngl.LINEAR, moderngl.LINEAR) if filtered else (moderngl.NEAREST, moderngl.NEAREST)
            tex.repeat_x = tex.repeat_y = False
            tex.use(len(textures))
            self.program[name].value = len(textures)
            textures.append(tex)
        for i in range(4):
            bind(f'uRaw{i}', c.raw[i], 'u2')
        for i in range(3):
            bind(f'uOff{i+1}', c.off[i], 'f2' if c.flow_half else 'f4')
        bind('uW', c.weights, 'f1', True)
        bind('uBentoFlow', c.flow, 'f2' if c.flow_half else 'f4')
        bind('uBentoMask', c.mask, 'f1', True)
        bind('uLinearMask', c.linear_mask, 'f1', True)
        uniforms = {'uSize': (w, h), 'uTiles': (float(c.off[0].shape[1]), float(c.off[0].shape[0])),
                    'uCfaLut': c.cfa, 'uBlack': c.black, 'uWhite': c.white,
                    'uGain': c.gain, 'uFrameScale': c.scales, 'uShortIndex': c.short,
                    'uKernelSigma': c.sigma, 'uGlobalWeight': c.global_weight,
                    'uNoiseShot': c.noise_shot, 'uNoiseRead': c.noise_read,
                    'uNoiseGainRatio': c.noise_gain_ratio}
        for name, value in uniforms.items():
            self.program[name].value = value
        target = self.ctx.texture((w, h), 1, dtype=target_dtype)
        noise_target = self.ctx.texture((w, h), 4, dtype='f4') if with_noise else None
        fbo = self.ctx.framebuffer([target, noise_target] if with_noise else [target])
        fbo.use()
        self.ctx.viewport = (0, 0, w, h)
        self.ctx.disable(moderngl.BLEND)
        self.vao.render(moderngl.TRIANGLES, vertices=3)
        result = np.frombuffer(fbo.read(components=1, dtype=target_dtype, alignment=1),
                               np.float16 if target_dtype == 'f2' else np.float32).reshape(h, w).astype(np.float32)
        noise = None
        if with_noise:
            noise = np.frombuffer(fbo.read(attachment=1, components=4, dtype='f4', alignment=1),
                                  np.float32).reshape(h, w, 4).copy()
        fbo.release()
        target.release()
        if noise_target:
            noise_target.release()
        for tex in textures:
            tex.release()
        return (result, noise) if with_noise else result

    def close(self):
        self.vao.release()
        self.program.release()
        self.ctx.release()
        if self.window:
            import glfw
            glfw.destroy_window(self.window)
            glfw.terminate()


def case(name, w=48, h=32, seed=871):
    rng = np.random.default_rng(seed)
    raw = [rng.integers(0, 3200, (h, w), dtype=np.uint16) for _ in range(4)]
    ch, cw = math.ceil(h/128), math.ceil(w/128)
    off = [rng.uniform(-4., 4., (ch, cw, 2)).astype('f4') for _ in range(3)]
    flow = rng.uniform(-3., 3., (math.ceil(h/16), math.ceil(w/16), 2)).astype('f4')
    weights = rng.integers(0, 256, (ch*4, cw*4, 3), dtype=np.uint8)
    mask = rng.integers(0, 256, (h//4, w//4), dtype=np.uint8)
    rejection = rng.integers(0, 256, mask.shape, dtype=np.uint8)
    return Case(name, raw, off, weights, flow, mask, linear_kernel_mask(rejection))


def uniform_raw(c, normalized):
    """Per-frame, per-native-phase normalized sensor constants, uint16 quantized."""
    raw = []
    for frame in range(4):
        a = np.empty_like(c.raw[0])
        for p in range(4):
            code = np.rint(c.black[p]+normalized[frame][p]*(c.white-c.black[p]))
            a[p//2::2, p%2::2] = np.uint16(np.clip(code, 0, c.white))
        raw.append(a)
    return replace(c, raw=raw)


def cases():
    out = []
    for name, lut in CFA.items():
        c = replace(case('random-'+name), cfa=lut)
        out.append(c)
        out.append(replace(c, name='gaussian-'+name, linear_mask=np.zeros_like(c.mask)))
        # Distinct phase blacks + pure R/B/dual greens catch canonical/native mixups.
        vals = [[.08, .12, .16, .20]]*4
        out.append(uniform_raw(replace(c, name='constant-native-black-'+name), vals))
        # Smooth scene field sampled at the actual mosaic pixel positions;
        # exposure normalization is applied independently to every frame.
        yy, xx = np.mgrid[:c.raw[0].shape[0], :c.raw[0].shape[1]]
        ph = (xx & 1)+2*(yy & 1)
        blacks = np.array(c.black)[ph]
        ramp = .08+.003*xx+.002*yy
        ramp_raw = [np.rint(blacks+ramp/scale*(c.white-blacks)).astype('u2') for scale in c.scales]
        out.append(replace(c, name='linear-field-negative-flow-'+name, raw=ramp_raw,
                           flow=np.full_like(c.flow, -1.25)))
        for short in (1, 2, 3):
            out.append(replace(c, name=f'short-{short}-{name}', short=short,
                               scales=tuple(4. if i == short else 1. for i in range(4))))
    c = case('negative-flow-tie-even')
    flow = np.empty_like(c.flow)
    flow[..., 0], flow[..., 1] = -3., -1.
    # odd mosaic shifts => .5 quad ties, including negative tile origins.
    out.append(replace(c, flow=flow, off=[np.full_like(x, -3.) for x in c.off]))
    boundary_flow = np.empty_like(c.flow)
    boundary_flow[..., 0], boundary_flow[..., 1] = -.5, 1.5
    out.append(replace(c, name='exact-1.5-tap-support-boundary', flow=boundary_flow))
    flow = np.empty_like(c.flow)
    flow[..., 0], flow[..., 1] = -200., 300.
    out.append(replace(c, name='large-flow-single-mirror-clamp', flow=flow))
    flow = np.zeros_like(c.flow)
    flow[:, 1:, 0] = 5.25
    out.append(replace(c, name='fine-nearest-tile-boundary', flow=flow))
    c = case('coarse-bilinear-padded-edge', 160, 48)
    out.append(c)
    out.append(replace(c, name='RG16F-flow-exposure-scale-f2', flow=c.flow.astype('f2'),
                       off=[x.astype('f2') for x in c.off], flow_half=True,
                       scales=(1., .5, 8., 1.5)))
    c = case('native-16bit-unsigned')
    rng = np.random.default_rng(165535)
    out.append(replace(c, white=65535., black=(1024.5, 1088.5, 1152.5, 1216.5),
                       raw=[rng.integers(0, 65536, a.shape, dtype='u2') for a in c.raw]))
    c = case('M0')
    out.append(replace(c, mask=np.zeros_like(c.mask)))
    out.append(replace(c, name='M1', mask=np.full_like(c.mask, 255)))
    transition = np.tile(np.linspace(0, 255, c.mask.shape[1]).astype('u1'), (c.mask.shape[0], 1))
    out.append(replace(c, name='M-linear-transition', mask=transition,
                       linear_mask=linear_kernel_mask(transition)))
    out.append(replace(c, name='whole-rejected-ignores-nonzero-M', short=-1))
    out.append(replace(c, name='global-cap50-short-override', global_weight=(1., 50., 0., .001)))
    # Different shape/kernel support: per-frame normalization before merge is WRONG.
    out.append(replace(c, name='unequal-kernel-support', sigma=(.3, 1., 4., 8.),
                       linear_mask=np.zeros_like(c.mask)))
    out.append(replace(c, name='linear-kernel-amplitude', linear_mask=np.full_like(c.mask, 127)))
    # Accepted short is itself clipped. It still contributes M, and native output
    # remains unclamped RAW, not discarded or replaced/inpainted.
    bright = uniform_raw(replace(c, name='short-clipped-M1', mask=np.full_like(c.mask, 255)), [[.1]*4, [.1]*4, [1.]*4, [.1]*4])
    out.append(bright)
    out.append(replace(bright, name='short-clipped-transition', mask=transition))
    out.append(uniform_raw(replace(c, name='all-clipped-rejected', short=-1), [[1.]*4]*4))
    out.append(uniform_raw(replace(c, name='all-clipped-M0', mask=np.zeros_like(c.mask)), [[1.]*4]*4))
    # Neutral highlight at reference 2: short own exposure sensor <= .5, no
    # native clip; old satW would incorrectly erase reference-domain G > 1.
    colors = [(c.cfa >> (2*p)) & 3 for p in range(4)]
    normalized = [[.1]*4 for _ in range(4)]
    normalized[2] = [2./(4.*c.gain[col]) for col in colors]
    out.append(uniform_raw(replace(c, name='neutral-short-G-over1', mask=np.full_like(c.mask, 255)), normalized))
    c2 = replace(c, name='creative-WB-unclamped', gain=(1.3, 1., .7), short=-1,
                 global_weight=(1., 1., 0., 1.))
    out.append(uniform_raw(c2, [[1.]*4]*4))
    c3 = replace(c, name='blue-gain-below-one-unclamped', gain=(1.4, 1., .7), short=-1,
                 global_weight=(1., 1., 0., 1.))
    out.append(uniform_raw(c3, [[1.]*4]*4))
    # Entire dark/noisy native values below black remain negative (PC exact).
    out.append(replace(c, name='below-black-negative', raw=[np.zeros_like(x) for x in c.raw], short=-1))
    # Isolate a single temporal frame to prove all four can contribute independently.
    for frame in range(4):
        out.append(replace(c, name=f'isolate-frame-{frame}', short=-1,
                           global_weight=tuple(1. if i == frame else 0. for i in range(4))))
    for size in (2, 4):
        tiny = case(f'mirror-correlated-{size}x{size}', max(size, 4), max(size, 4))
        tiny = replace(tiny, raw=[x[:size, :size].copy() for x in tiny.raw],
                       mask=np.full((1, 1), 255, dtype='u1'),
                       linear_mask=np.zeros((1, 1), dtype='u1'),
                       flow=np.full_like(tiny.flow, 200.))
        out.append(tiny)
    out.append(replace(c, name='noise-shot-only', noise_read=(0.,)*3,
                       noise_gain_ratio=(1., .5, 4., 8.)))
    out.append(replace(c, name='noise-read-only', noise_shot=(0.,)*3))
    out.append(replace(c, name='noise-zero', noise_shot=(0.,)*3, noise_read=(0.,)*3))
    return out


def semantic_checks(gpu):
    c = case('semantics')
    # Strict equality: amplitude 1/255 is enough, constant nonzero is NOT linear.
    r = np.full((7, 9), 71, np.uint8)
    assert not linear_kernel_mask(r).any()
    r[3, 4] += 1
    assert np.count_nonzero(linear_kernel_mask(r)) == 9
    assert maximum_merge_weight(1., .001, 2.) == 50.
    assert maximum_merge_weight(1., 1., 4.) == .0625
    assert abs(kernel_sigma(14.75, 4, 30.)-1/(.42*1.414000034332275)) < 1e-12
    # M=0 exactly equals whole rejection when all kernels are same Gaussian.
    zero = replace(c, mask=np.zeros_like(c.mask), linear_mask=np.zeros_like(c.mask))
    rejected = replace(zero, short=-1)
    # The selected frame's alignment switches from fine to ordinary temporal
    # on rejection, but its weight is 0 with accepted M0, hence explicitly zero
    # temporal weight for that slice for a like-for-like identity comparison.
    weights = zero.weights.copy()
    weights[..., 1] = 0
    zero, rejected = replace(zero, weights=weights), replace(rejected, weights=weights)
    np.testing.assert_array_equal(gpu.run(zero), gpu.run(rejected))
    rejected = replace(rejected, global_weight=(1., .9, 0., 1.2))
    before, before_noise = gpu.run(rejected, with_noise=True)
    changed = [x.copy() for x in rejected.raw]
    changed[2].fill(65535)
    after, after_noise = gpu.run(replace(rejected, raw=changed), with_noise=True)
    np.testing.assert_array_equal(before, after)
    np.testing.assert_array_equal(before_noise, after_noise)
    assert not before_noise[..., 3].any()
    # R/B MUST NOT read each other's or green lattices; green ALWAYS reads both.
    for layout, lut in CFA.items():
        c = replace(case('dualgreen-'+layout), cfa=lut, mask=np.full_like(c.mask, 255),
                    flow=np.zeros_like(c.flow), gain=(1., 1., 1.),
                    scales=(1., 1., 1., 1.), sigma=(1., 1., 1., 1.))
        colors = [(lut >> (2*p)) & 3 for p in range(4)]
        green_phases = [p for p in range(4) if colors[p] == 1]
        vals = [.12 if col == 0 else .32 if col == 2 else .2 for col in colors]
        c = uniform_raw(c, [vals]*4)
        before = gpu.run(c)
        raw = [x.copy() for x in c.raw]
        gp = green_phases[0]
        raw[2][gp//2::2, gp%2::2] += 300
        after = gpu.run(replace(c, raw=raw))
        for p in range(4):
            change = after[p//2::2, p%2::2]-before[p//2::2, p%2::2]
            if colors[p] == 1:
                assert np.min(change) > .001, (layout, p, 'dual green missing')
            else:
                assert np.max(np.abs(change)) == 0, (layout, p, 'R/B phase mixing')
        # Perturb only R: B and both greens must stay bit-identical.
        raw = [x.copy() for x in c.raw]
        red_phase = colors.index(0)
        raw[2][red_phase//2::2, red_phase%2::2] += 200
        red_after = gpu.run(replace(c, raw=raw))
        for p in range(4):
            change = red_after[p//2::2, p%2::2]-before[p//2::2, p%2::2]
            if colors[p] == 0:
                assert np.min(change) > .001
            else:
                assert np.max(np.abs(change)) == 0, (layout, p, 'R-to-B/G mixing')
    # Analytic two-green support: at integer alignment the same-phase centre
    # has weight 1 and four opposite-green taps each have exp(-sigma^2).
    # This independently checks the CPU/GPU kernel, rather than only comparing
    # two ports which might share the same stencil mistake.
    for layout, lut in CFA.items():
        c = replace(case('analytic-'+layout), cfa=lut, mask=np.full_like(c.mask, 255),
                    flow=np.zeros_like(c.flow), gain=(1., 1., 1.),
                    scales=(1., 1., 1., 1.), sigma=(1., 1., 1., 1.),
                    linear_mask=np.zeros_like(c.mask))
        colors = [(lut >> (2*p)) & 3 for p in range(4)]
        greens = [p for p in range(4) if colors[p] == 1]
        vals = [.1, .1, .1, .1]
        vals[greens[1]] = .3
        c = uniform_raw(c, [vals]*4)
        result = gpu.run(c)
        actual_phase = []
        for p in range(4):
            code = float(c.raw[2][p//2, p%2])
            actual_phase.append((code-c.black[p])/(c.white-c.black[p]))
        for p in range(4):
            expected = actual_phase[p]
            if p in greens:
                expected = (actual_phase[p]+4*math.exp(-1)*actual_phase[3-p])/(1+4*math.exp(-1))
            np.testing.assert_allclose(result[p//2::2, p%2::2], expected, atol=.0003)
        # A common scene field, different exposures and four unequal black levels
        # must resolve to exactly the same reference-domain value in every CFA.
        c = replace(c, scales=(1., .5, 4., 1.5))
        normalized = [[.25/scale]*4 for scale in c.scales]
        constant = uniform_raw(replace(c, mask=np.full_like(c.mask, 113)), normalized)
        np.testing.assert_allclose(gpu.run(constant), .25, atol=.0005)
    # Extreme mirrored flow aliases every same-phase tap onto ONE physical
    # pixel. Squaring individual tap weights would under-estimate variance.
    # Analytic expectation groups the four opposite-green taps BEFORE square.
    c = case('analytic-mirror-covariance', 4, 4)
    c = replace(c, flow=np.full_like(c.flow, 200.), mask=np.full_like(c.mask, 255),
                linear_mask=np.zeros_like(c.mask), gain=(1., 1., 1.),
                sigma=(1., 1., 1., 1.), noise_shot=(0., 0., 0.),
                noise_read=(.001, .002, .003), noise_gain_ratio=(1., 1., 1., 1.))
    c = uniform_raw(c, [[.2]*4]*4)
    image, noise = gpu.run(c, with_noise=True)
    for p in range(4):
        color = (c.cfa >> (2*p)) & 3
        expected = c.noise_read[color]*c.scales[2]**2
        if color == 1:
            k = 4*math.exp(-1.)
            expected *= (1+k*k)/(1+k)**2
        np.testing.assert_allclose(noise[p//2::2, p%2::2, 0], expected, rtol=2e-5)
        np.testing.assert_allclose(noise[p//2::2, p%2::2, 2], expected, rtol=2e-5)
    np.testing.assert_allclose(noise[..., 1], 0, atol=1e-9)
    np.testing.assert_allclose(noise[..., 3], 1, atol=1e-6)
    # On an exactly constant, nonnegative scene the full variance reduces to
    # Aeffective*output+Beffective; on textured scenes it generally must not.
    c = case('affine-noise-constant')
    c = uniform_raw(replace(c, black=(0.,)*4, white=4096.,
                    scales=(1., .5, 4., 2.), noise_gain_ratio=(1., .5, 2., 3.)),
                    [[.25/s]*4 for s in (1., .5, 4., 2.)])
    image, noise = gpu.run(c, target_dtype='f4', with_noise=True)
    np.testing.assert_allclose(noise[..., 0], noise[..., 1]*image+noise[..., 2], rtol=2e-5, atol=1e-8)
    # Raw-noise zero is exact; with no accepted short, provenance is exactly 0.
    c = replace(c, short=-1, noise_shot=(0.,)*3, noise_read=(0.,)*3)
    _, noise = gpu.run(c, with_noise=True)
    np.testing.assert_array_equal(noise, np.zeros_like(noise))
    # RAW resolve does NOT neutralize clipped WB chroma; source CameraWhite
    # belongs after real demosaic/unshade, which is deliberately outside this test.
    for c in cases():
        if c.name == 'short-clipped-M1':
            result = gpu.run(c)
            for p in range(4):
                color = (c.cfa >> (2*p)) & 3
                np.testing.assert_allclose(result[p//2::2, p%2::2], 4*c.gain[color], atol=.008)
        if c.name in ('creative-WB-unclamped', 'blue-gain-below-one-unclamped'):
            result = gpu.run(c)
            for p in range(4):
                color = (c.cfa >> (2*p)) & 3
                np.testing.assert_allclose(result[p//2::2, p%2::2], c.gain[color], atol=.002)
        if c.name == 'neutral-short-G-over1':
            result = gpu.run(c)
            np.testing.assert_allclose(result, 2., atol=.003)
            assert result[0::2, 1::2].min() > 1.9
        if c.name == 'below-black-negative':
            assert np.max(gpu.run(c)) < 0


def static_check(shader, required):
    validator = shutil.which('glslangValidator') or shutil.which('glslang')
    if not validator:
        local = ROOT/'tools/bin/glslang'
        validator = str(local) if local.is_file() else None
    if not validator:
        if required:
            raise RuntimeError('glslang not installed')
        return 'unavailable (use --require-static to require)'
    with tempfile.TemporaryDirectory(prefix='highlight-merge-') as d:
        path = pathlib.Path(d)/'highlight.frag'
        path.write_text(shader)
        result = subprocess.run([validator, '-S', 'frag', str(path)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stdout+result.stderr)
    return 'PASS GLSL ES 300'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--require-static', action='store_true')
    parser.add_argument('--json', type=pathlib.Path, help='optional machine-readable results')
    args = parser.parse_args()
    shader = source()
    static = static_check(shader, args.require_static)
    gpu = GPU(shader)
    rows = []
    try:
        for c in cases():
            expected, expected_noise = cpu_reference(c, with_noise=True)
            actual, actual_noise = gpu.run(c, with_noise=True)
            full_precision = gpu.run(c, 'f4')
            noise_error = np.abs(actual_noise-expected_noise)
            # R8 hardware sampling contributes small coefficient error, but the
            # propagated variances must agree channelwise, not just a scalar neff.
            noise_tolerance = np.maximum(2e-7, .006*np.abs(expected_noise))
            if not np.isfinite(actual_noise).all() or np.any(noise_error > noise_tolerance):
                pos = np.unravel_index(np.argmax(noise_error/noise_tolerance), noise_error.shape)
                raise AssertionError(f'{c.name}: MRT noise mismatch {pos}, GPU={actual_noise[pos]}, CPU={expected_noise[pos]}')
            # Quantify storage separately from R8 filtering / FP32 arithmetic.
            # GL may round an R16F target toward zero: allow one half-ULP, not
            # an arbitrary image-wide relative epsilon. UNORM texture filtering
            # has hardware subtexel precision, measured separately against CPU.
            exponent = np.floor(np.log2(np.maximum(np.abs(full_precision), 2.**-14)))
            half_ulp = np.maximum(2.**(exponent-10), 2.**-24)
            storage_error = np.abs(actual-full_precision)
            if np.any(storage_error > half_ulp+1e-6):
                raise AssertionError(f'{c.name}: R16F storage exceeds one ULP')
            arithmetic_error = np.abs(full_precision-expected)
            if np.any(arithmetic_error > .0015+.0002*np.abs(expected)):
                pos = np.unravel_index(np.argmax(arithmetic_error), arithmetic_error.shape)
                raise AssertionError(f'{c.name}: R32F vs CPU mismatch {pos}: {arithmetic_error[pos]}')
            error = np.abs(actual-expected)
            tolerance = half_ulp+.0015+.0002*np.abs(expected)
            if not np.isfinite(actual).all() or np.any(error > tolerance):
                pos = np.unravel_index(np.argmax(error/tolerance), error.shape)
                raise AssertionError(f'{c.name}: mismatch {pos} GPU={actual[pos]} CPU={expected[pos]}, abs={error[pos]}, tol={tolerance[pos]}')
            row = {'name': c.name, 'max_abs': float(error.max()),
                   'r32f_max_abs': float(arithmetic_error.max()),
                   'storage_max_abs': float(storage_error.max()),
                   'noise_max_abs': [float(x) for x in noise_error.max(axis=(0, 1))],
                   'rms': float(np.sqrt(np.mean(error**2))),
                   'range': [float(actual.min()), float(actual.max())]}
            rows.append(row)
            print(f'PASS {c.name:42} max={row["max_abs"]:.7f} rms={row["rms"]:.7f} range={row["range"]}')
        semantic_checks(gpu)
        report = {'shader': str(HEADER.relative_to(ROOT)), 'static': static,
                  'renderer': gpu.ctx.info.get('GL_RENDERER'), 'cases': rows,
                  'semantic_checks': 'PASS', 'max_abs': max(x['max_abs'] for x in rows),
                  'r32f_max_abs': max(x['r32f_max_abs'] for x in rows),
                  'storage_max_abs': max(x['storage_max_abs'] for x in rows),
                  'noise_max_abs': np.max([x['noise_max_abs'] for x in rows], axis=0).tolist()}
        print(json.dumps({k: v for k, v in report.items() if k != 'cases'}, indent=2))
        print(f'PASS {len(rows)} GPU/NumPy cases + strict mask/tuning/dual-green/admission + MRT covariance/noise semantics')
        if args.json:
            args.json.write_text(json.dumps(report, indent=2)+'\n')
    finally:
        gpu.close()


if __name__ == '__main__':
    main()
