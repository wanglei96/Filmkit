#!/usr/bin/env python3
"""Execute production chroma shaders against reconstruction/noise invariants.

The CPU oracle reconstructs coarse + every shrunken detail (not the GPU's
removed-detail accumulation). Starlet conventions: scarlet2, generation=1.
"""
from pathlib import Path
import re
import sys

import glfw
import moderngl
import numpy as np


ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / 'core/engine/src/render/FrameRenderer.cpp').read_text()
B3 = np.array([1, 4, 6, 4, 1], dtype=float) / 16
LUMA = np.array([.2627, .6780, .0593])


def shader(name):
    return re.search(r'const char\*\s+' + name + r'\s*=\s*R"\((.*?)\)";', TEXT, re.S)[1]


def smooth(image, level):
    step = 1 << level
    result = image
    for axis in [0, 1]:
        index = np.arange(result.shape[axis])
        result = sum(weight * np.take(result, np.clip(index + (tap-2)*step,
                     0, result.shape[axis]-1), axis=axis)
                     for tap, weight in enumerate(B3))
    return result


def norms():
    impulse = np.zeros((513, 513))
    impulse[256, 256] = 1
    result = []
    for level in range(6):
        coarse = smooth(impulse, level)
        result.append(np.linalg.norm(impulse - coarse))
        impulse = coarse
    return np.array(result)


NORM = norms()
literal = re.search(r'band_sigma\[kChromaLevels\]\s*=\s*\{(.*?)\}', TEXT, re.S)[1]
actual_norm = np.array([float(x.rstrip('f')) for x in literal.replace('\n', '').split(',')])
np.testing.assert_allclose(actual_norm, NORM, rtol=1e-7)

assert glfw.init()
for key, value in [(glfw.VISIBLE, glfw.FALSE), (glfw.CONTEXT_VERSION_MAJOR, 4),
                   (glfw.CONTEXT_VERSION_MINOR, 1),
                   (glfw.OPENGL_PROFILE, glfw.OPENGL_CORE_PROFILE),
                   (glfw.OPENGL_FORWARD_COMPAT, glfw.TRUE)]:
    glfw.window_hint(key, value)
window = glfw.create_window(32, 32, 'chroma-wavelet-test', None, None)
assert window
glfw.make_context_current(window)
ctx = moderngl.create_context()
VS = '''#version 330 core
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}'''


def texture(array):
    a = np.ascontiguousarray(array, dtype='f4')
    t = ctx.texture((a.shape[1], a.shape[0]), a.shape[2], a.tobytes(), dtype='f4')
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
    return t


def run(name, inputs, uniforms, size, count=1):
    p = ctx.program(vertex_shader=VS, fragment_shader=shader(name).replace(
        '#version 300 es', '#version 330 core'))
    targets = [ctx.texture(size, 4, dtype='f4') for _ in range(count)]
    f = ctx.framebuffer(targets)
    f.use()
    ctx.viewport = (0, 0, *size)
    for unit, (key, tex) in enumerate(inputs.items()):
        tex.use(unit)
        if key in p:
            p[key].value = unit
    for key, value in uniforms.items():
        if key in p:
            p[key].value = value
    vao = ctx.vertex_array(p, [])
    vao.render(moderngl.TRIANGLES, vertices=3)
    vao.release()
    p.release()
    f.release()
    return targets


def read(t):
    return np.frombuffer(t.read(), dtype='f4').reshape(t.height, t.width, 4).copy()


def denoise(image, variance, mix=1, mask=None):
    h, w = image.shape[:2]
    rgb = texture(image)
    vd = texture(np.broadcast_to(variance, (h, w, 3)))
    vo = texture(np.zeros((h, w, 3)))
    lsc = texture(np.ones((1, 1, 3)))
    mask = texture(np.zeros((h, w, 1)) if mask is None else mask[..., None])
    coarse, sigma, removed = run('kFsChromaDown', dict(
        uTex=rgb, uLocalVariance=vd, uLocalCovariance=vo, uLsc=lsc), dict(
        uSize=(w, h), uLocalNoiseOn=1, uLocalNoiseScale=1., uLscSize=(1, 1)),
        (w, h), 3)
    for level in range(6):
        horizontal, = run('kFsChromaBlur', dict(uPrev=coarse), dict(
            uSize=(w,h), uStep=1 << level), (w,h))
        nc, nr = run('kFsChromaAtrous', dict(
            uPrev=coarse, uGuide=rgb, uHorizontal=horizontal, uSigma=sigma, uRemoved=removed), dict(
            uSize=(w, h), uStep=1 << level, uK=2.5, uRevert=.2,
            uBandSigma=float(actual_norm[level]), uCgK=0.), (w, h), 2)
        coarse.release()
        removed.release()
        horizontal.release()
        coarse, removed = nc, nr
    out, = run('kFsChromaApply', dict(uTex=rgb, uRemoved=removed, uSatMask=mask),
               dict(uMix=mix, uSatMaskOn=1), (w, h))
    result = read(out)[..., :3]
    for t in [rgb, vd, vo, lsc, mask, coarse, sigma, removed, out]:
        t.release()
    return result


def reference(image, variance):
    y = image @ LUMA
    ch = image[..., [2, 0]] - y[..., None]
    axes = np.eye(3)[[2, 0]] - LUMA
    sigma = np.sqrt((axes * axes) @ variance)
    c = ch.copy()
    reconstructed = np.zeros_like(ch)
    for level in range(6):
        a = smooth(c, level)
        d = c - a
        v = (2.5 * NORM[level] * sigma)**2
        gain = .2 + .8 * d*d / np.maximum(d*d + v, 1e-30)
        reconstructed += d * gain
        c = a
    reconstructed += c
    out = np.stack([y + reconstructed[..., 1], y, y + reconstructed[..., 0]], -1)
    out[..., 1] = (y-LUMA[0]*out[..., 0]-LUMA[2]*out[..., 2])/LUMA[1]
    delta = out - image
    t = np.min(np.where(delta < 0, image / np.maximum(-delta, 1e-30), 1), axis=-1)
    return image + np.clip(t, 0, 1)[..., None]*delta


rng = np.random.default_rng(8117)
h, w = 97, 103  # odd dimensions, including all borders
yy, xx = np.indices((h, w))
image = np.full((h, w, 3), .5)
image[..., 0] += .05*((xx+yy) % 2*2-1)
image[..., 1] -= (image[..., 0]-.5)*LUMA[0]/LUMA[1]
np.testing.assert_allclose(denoise(image, np.zeros(3)), image, atol=8e-8, rtol=0)
print('PASS noiseless equal-luma chroma checker survives, including odd dimensions')

image = rng.uniform(.05, .95, (h, w, 3))
variance = np.array([.003, .001, .004])
got = denoise(image, variance)
expected = reference(image, variance)
np.testing.assert_allclose(got, expected, atol=7e-7, rtol=3e-6)
np.testing.assert_allclose(got @ LUMA, image @ LUMA, atol=1e-7)
assert got.min() >= -1e-7
print('PASS actual six-level GPU vs independent coarse-plus-detail reconstruction; luma/gamut')

zero = denoise(image, variance, mix=0)
np.testing.assert_array_equal(zero, image.astype('f4'))
tiny = denoise(image, variance, mix=.001)
assert np.max(np.abs(tiny-image)) < .001*np.max(np.abs(got-image))+2e-7
print('PASS zero strength is identity and positive strength converges continuously')

mask = (xx > w//2).astype(float)
masked = denoise(image, variance, mask=mask)
np.testing.assert_array_equal(masked[mask > .5], image.astype('f4')[mask > .5])
print('PASS saturation-core bypass survives D1')

# Execute P4 with signed CCM covariance, including the usually disabled gate.
cov=np.array([[.004,-.001,.0004],[-.001,.002,-.0003],[.0004,-.0003,.003]])
rgb=texture(image)
vd=texture(np.broadcast_to(np.diag(cov),(h,w,3)))
vo=texture(np.broadcast_to(cov[[0,0,1],[1,2,2]],(h,w,3)))
lsc=texture(np.ones((1,1,3)))
masktex=texture(mask[...,None])
for cg in [0.,1.]:
    out,=run('kFsChromaDenoise',dict(uTex=rgb,uLsc=lsc,uLocalVariance=vd,
        uLocalCovariance=vo,uSatMask=masktex),dict(uSize=(w,h),uLscSize=(1,1),
        uLocalNoiseOn=1,uLocalNoiseScale=1.,uMix=.67,uMoire=2.,uMoireK=2.,
        uCgK=cg,uCgScope=1.,uSatMaskOn=1),(w,h))
    result=read(out)[...,:3]
    np.testing.assert_allclose(result@LUMA,image@LUMA,atol=2e-7)
    np.testing.assert_array_equal(result[mask>.5],image.astype('f4')[mask>.5])
    assert np.all(np.isfinite(result)) and result.min()>=-1e-7
    out.release()
for t in [rgb,vd,vo,lsc,masktex]:t.release()
print('PASS P4 signed full covariance, Rec.2020 luminance, gamut and saturated-core bypass; gate off/on')

noise = rng.normal(size=(1024, 1024))
for level in range(6):
    c = smooth(noise, level)
    border = 2*((1 << (level+1))-1)
    measured = (noise-c)[border:-border, border:-border].std()
    assert abs(measured/NORM[level]-1) < .09, (level, measured, NORM[level])
    noise = c
print('PASS six per-band noise gains vs independent white-noise Monte Carlo:', NORM)

ctx.release()
glfw.destroy_window(window)
glfw.terminate()
