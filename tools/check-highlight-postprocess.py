#!/usr/bin/env python3
"""Production postprocess GL link/draw/interface smoke test, NOT phone/Mali proof.

Run: python3 tools/check-highlight-postprocess.py [--json /tmp/postprocess.json]
Requires existing numpy/moderngl/glfw environment. No shader algorithm rewrites:
engine-shader-offline.extract_shaders/to_core extract production strings and only
translate ES300's version directive to desktop330. Float32 MRT is intentional;
RGB inputs exercise both float16/float32. Full adaptive Jacobian/LOWCOVFULL oracle
tests live elsewhere and are deliberately not repeated here. Host bindings are
replayed explicitly, not executed C++; Android FBO allocation is NOT validated.
All synthetic input pixels carry nonzero full-RGB PSD noise (no measured holes).
The whole executable, including driver compile/draw, has a 115 second deadline.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import signal
import sys
import time

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('engine_shader_offline', ROOT / 'tools/engine-shader-offline.py')
offline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offline)


class Check:
    def __init__(self):
        self.failures = []
        self.results = []

    def test(self, name, condition, **evidence):
        row = dict(test=name, ok=bool(condition), **evidence)
        self.results.append(row)
        if not condition:
            self.failures.append(row)
        print(('PASS ' if condition else 'FAIL ') + name + ' ' + json.dumps(evidence), flush=True)


class Draw:
    """Own MRTs (float32 by default); retain GPU outputs as next-pass inputs."""
    def __init__(self, gl, shaders, check, target_dtype='f4'):
        self.gl, self.ctx, self.shaders, self.check = gl, gl.ctx, shaders, check
        assert target_dtype in ('f2', 'f4')
        self.target_dtype = target_dtype
        self.resources, self.programs = [], {}
        self.calls, self.interfaces = 0, {}

    def texture(self, array, dtype='f4'):
        a = np.ascontiguousarray(array, dtype=np.float16 if dtype == 'f2' else np.float32)
        t = self.ctx.texture((a.shape[1], a.shape[0]), a.shape[2], a.tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t.repeat_x = t.repeat_y = False
        self.resources.append(t)
        return t

    def run(self, name, images, uniforms, size, outputs=1, output_dtypes=None):
        if name not in self.programs:
            p = self.ctx.program(vertex_shader=self.gl.vs_src, fragment_shader=offline.to_core(self.shaders[name]))
            vao = self.ctx.vertex_array(p, [])
            self.programs[name] = (p, vao)
        p, vao = self.programs[name]
        supplied = set(images) | set(uniforms)
        active = {key for key in p if isinstance(p[key], moderngl.Uniform)}
        missing = active - supplied
        if missing:
            raise AssertionError(f'{name}: unbound active uniforms/samplers {sorted(missing)}')
        self.interfaces[name] = dict(active=sorted(active), bound_samplers=sorted(images),
                                     optimized_out=sorted(supplied-active), mrt=outputs)
        for unit, (key, tex) in enumerate(images.items()):
            tex.use(unit)
            if key in p:
                p[key].value = unit
        for key, value in uniforms.items():
            if key in p:
                p[key].value = value
        dtypes = output_dtypes or [self.target_dtype] * outputs
        assert len(dtypes) == outputs
        targets = [self.ctx.texture(size, 4, dtype=dtype) for dtype in dtypes]
        self.resources.extend(targets)
        fbo = self.ctx.framebuffer(targets)
        fbo.use()
        self.ctx.viewport = (0, 0, *size)
        self.ctx.disable(moderngl.BLEND | moderngl.DEPTH_TEST | moderngl.CULL_FACE)
        # Sentinel clear catches untouched MRT attachments and incomplete coverage.
        fbo.clear(-123.0, -123.0, -123.0, -123.0)
        vao.render(moderngl.TRIANGLES, vertices=3)
        self.ctx.finish()
        arrays = [np.frombuffer(fbo.read(attachment=i, components=4, dtype='f4'), 'f4')
                  .reshape(size[1], size[0], 4).copy() for i in range(outputs)]
        fbo.release()
        self.calls += 1
        error = self.ctx.error
        if error != 'GL_NO_ERROR':
            raise RuntimeError(f'{name}: draw/read {error}')
        for i, a in enumerate(arrays):
            if not np.isfinite(a).all() or np.any(a == -123):
                raise AssertionError(f'{name} attachment {i}: nonfinite or untouched clear pixels')
        return targets, arrays

    def close(self):
        for p, vao in self.programs.values():
            vao.release()
            p.release()
        for r in reversed(self.resources):
            r.release()


def covariance(diag, off):
    c = np.zeros((*diag.shape[:2], 3, 3), np.float64)
    for i in range(3):
        c[..., i, i] = diag[..., i]
    for k, (i, j) in enumerate(((0, 1), (0, 2), (1, 2))):
        c[..., i, j] = c[..., j, i] = off[..., k]
    return c


def check_cov(check, label, diag, off):
    c = covariance(diag, off)
    eig = np.linalg.eigvalsh(c)
    tolerance = max(2e-8, float(np.abs(c).max()) * 2e-5)
    check.test(label + '/finitePSD+nonzero-full6', np.isfinite(c).all() and eig.min() >= -tolerance
               and np.all(diag[..., :3] > 0) and np.all(np.max(np.abs(off[..., :3]), axis=(0, 1)) > 1e-9),
               min_eigenvalue=float(eig.min()), tolerance=tolerance,
               diag_range=[float(diag[..., :3].min()), float(diag[..., :3].max())],
               off_absmax=np.max(np.abs(off[..., :3]), axis=(0, 1)).tolist())


def execute(draw, check, dtype, measured):
    w, h = 32, 24
    low_size = (w // 2, h // 2)
    yy, xx = np.mgrid[:h, :w]
    # Actual spatial RGB, not a constant image: fine chroma, ramp and hard edge.
    rgb = np.stack((.17+.11*xx/w+.025*np.sin(xx*1.7+yy*.4),
                    .23+.07*yy/h+.018*np.cos(xx*.6-yy*1.1),
                    .31+.05*(xx>w//2)+.024*np.sin(xx*.9+yy*1.3)), -1)
    base_cov = np.array([[.001, .00012, -.00008], [.00012, .002, .00015], [-.00008, .00015, .003]])
    spatial = .8 + .4*(xx+yy)/(w+h-2)
    c = spatial[..., None, None]*base_cov
    if measured:
        rng = np.random.default_rng(143)
        rgb += np.einsum('...ij,...j->...i', np.linalg.cholesky(c), rng.normal(size=(h, w, 3)))
        rgb = np.maximum(rgb, .001)
    image = np.concatenate((rgb, np.zeros((h, w, 1))), -1)
    tex = draw.texture(image, dtype)
    quantized = image.astype(np.float16).astype(np.float32) if dtype == 'f2' else image.astype('f4')
    # No mask bypass on the main measured path; separate tests exercise saturation.
    sat = draw.texture(np.zeros((h, w, 1)))
    sat_all = draw.texture(np.ones((h, w, 1)))
    lsc = draw.texture(np.array([[[1., 1.03, .98], [1.1, 1.12, 1.08]],
                                 [[1.15, 1.1, 1.18], [1.25, 1.3, 1.2]]]), dtype)
    label = f'{dtype}/' + ('noisy-measured' if measured else 'clear-spatial')
    down_u = dict(uSize=(w, h), uResize=2, uStride=1, uSigma=1.)
    up_u = dict(uLowSize=low_size, uHiSize=(w, h), uR=2)
    pc_u = dict(uK=3, uKernel=2.6, uSigA=1e-5, uSigB=1e-6, uLuma=.8,
                uLscSize=(2, 2), uLocalNoiseOn=1, uLocalNoiseScale=1., uFullSize=(w, h))
    # Production host supports [55] forced msize=7; bounded real kernel, not a stub.
    def pc(src, noise, size, first):
        return draw.run('kFsPc3d', dict(uTex=src, uLocalVariance=noise[0], uLocalCovariance=noise[1],
                        uLsc=lsc, uOrig=tex, uSatMask=sat),
                        dict(pc_u, uSize=size, uMoire=0. if first else 1.5,
                             uMaskOn=int(first), uSatMaskOn=1, uDown=2 if first else 1), size, 3)

    results = {}
    variants = [('full', 1., True)]
    if not measured:
        variants += [('low-noise', .2, True), ('high-noise', 4., True), ('no-cross', 1., False)]
    for tag, scale, cross in variants:
        diag = np.diagonal(c, axis1=-2, axis2=-1)*scale
        off = np.stack((c[..., 0, 1], c[..., 0, 2], c[..., 1, 2]), -1)*scale*cross
        variance, covar = draw.texture(diag), draw.texture(off)
        signal_down, _ = draw.run('kFsPc3dDown', {'uTex': tex}, down_u, low_size)
        noise_down, nd = draw.run('kFsHighlightNoiseDown', {'uVariance': variance, 'uCovariance': covar}, down_u, low_size, 2)
        first, a = pc(signal_down[0], noise_down, low_size, True)
        guided, _ = draw.run('kFsPc3dUp', {'uLow': first[0], 'uGuideHi': tex}, up_u, (w, h))
        up_noise, b = draw.run('kFsHighlightNoiseUp', dict(uLow=first[0], uGuideHi=tex,
                               uLowVariance=first[1], uLowCovariance=first[2],
                               uGuideVariance=variance, uGuideCovariance=covar), up_u, (w, h), 2)
        second, out = pc(guided[0], up_noise, (w, h), False)
        if cross:
            for stage, d, o in [('down', *nd), ('firstMRT', a[1], a[2]),
                                ('guidedUp', *b), ('secondMRT', out[1], out[2])]:
                check_cov(check, label+'/'+tag+'/'+stage, d, o)
        check.test(label+'/'+tag+'/image-finite-spatial', np.isfinite(out[0]).all()
                   and np.ptp(out[0][..., :3]) > .01,
                   rgb_range=[float(out[0][..., :3].min()), float(out[0][..., :3].max())])
        results[tag] = out
        # Actual P4 interface: bind every sampler even on disabled branches.
        p4_u = dict(uSize=(w, h), uNoiseA=(1e-5,)*3, uNoiseB=(1e-6,)*3, uMix=1.,
                    uLscSize=(2, 2), uMoire=1., uMoireK=2., uCgK=0., uCgScope=1.,
                    uLocalNoiseOn=1, uLocalNoiseScale=1., uSatMaskOn=1)
        p4_images = dict(uTex=tex, uLsc=lsc, uLocalVariance=variance, uLocalCovariance=covar, uSatMask=sat)
        _, p4 = draw.run('kFsChromaDenoise', p4_images, p4_u, (w, h))
        d1_u = {key: p4_u[key] for key in ('uSize', 'uNoiseA', 'uNoiseB', 'uLscSize', 'uLocalNoiseOn', 'uLocalNoiseScale')}
        _, d1 = draw.run('kFsChromaDown', {key: p4_images[key] for key in ('uTex', 'uLsc', 'uLocalVariance', 'uLocalCovariance')}, d1_u, (w, h), 3)
        check.test(label+'/'+tag+'/D1Down-sigma-nonzero', np.all(d1[1][..., :2] > 0),
                   sigma_range=[float(d1[1][..., :2].min()), float(d1[1][..., :2].max())])
        # Interface projection sanity, not a rerun of LOWCOVFULL reference tests.
        axes = np.array([[0, 0, 1], [1, 0, 0]]) - np.array([.2627, .6780, .0593])
        expected = np.sqrt(np.einsum('ki,...ij,kj->...k', axes, c*scale if cross else c*scale*np.eye(3), axes))
        err = float(np.max(np.abs(d1[1][..., :2]-expected)))
        check.test(label+'/'+tag+'/D1Down-local-fullcov-binding', err < 2e-7, max_abs_error=err)
        results[tag+'-p4'], results[tag+'-d1'] = p4[0][..., :3], d1[1][..., :2]
        if tag == 'full':
            _, bypass = draw.run('kFsChromaDenoise', dict(p4_images, uSatMask=sat_all), p4_u, (w, h))
            err = float(np.max(np.abs(bypass[0][..., :3]-quantized[..., :3])))
            check.test(label+'/P4-satmask-passthrough', err < 1e-7, max_abs_error=err)
    if not measured:
        for stage, index in [('image', 0), ('diag', 1), ('off', 2)]:
            delta = float(np.max(np.abs(results['high-noise'][index]-results['low-noise'][index])))
            check.test(label+'/noise-change/'+stage, delta > 1e-7, max_abs_delta=delta)
        for stage in ('p4', 'd1'):
            delta = float(np.max(np.abs(results['high-noise-'+stage]-results['low-noise-'+stage])))
            check.test(label+'/noise-change/'+stage, delta > 1e-7, max_abs_delta=delta)
        for stage, left, right in [('PC3D-fullRGB', results['full'][0], results['no-cross'][0]),
                                   ('P4', results['full-p4'], results['no-cross-p4']),
                                   ('D1Down', results['full-d1'], results['no-cross-d1'])]:
            delta = float(np.max(np.abs(left-right)))
            check.test(label+'/crosscov-change/'+stage, delta > 1e-7, max_abs_delta=delta)
    p0(draw, check, label, image, dtype, sat, sat_all)


def p0(draw, check, label, image, dtype, sat, sat_all):
    h, w = image.shape[:2]
    # RGGB LUT uses channel 1 at BOTH green sites; no historical G2 source patch.
    yy, xx = np.mgrid[:h, :w]
    channel = np.array([[0, 1], [1, 2]])[yy % 2, xx % 2]
    mosaic = np.take_along_axis(image[..., :3], channel[..., None], axis=-1)
    tex = draw.texture(mosaic, dtype)
    q = mosaic.astype(np.float16).astype(np.float32) if dtype == 'f2' else mosaic.astype('f4')
    expected = np.zeros((h, w, 3), 'f4')
    np.put_along_axis(expected, channel[..., None], q, axis=-1)
    # Every synthetic measured pixel has nonzero variance (no zero-map holes).
    variance = draw.texture(np.full((h, w, 1), .002))
    zero = draw.texture(np.zeros((h, w, 1)))
    uniforms = dict(uSize=(w, h), uCfaLut=148, uNoiseA=(0.,)*3, uNoiseB=(0.,)*3,
                    uMergedNoiseOn=1, uNoiseStrength=1., uMix=1., uWClamp=0., uSatMaskOn=0)
    images = dict(uTex=tex, uMergedNoise=variance, uSatMask=sat)
    for name, changes, maps in [
        ('noise-strength-zero', {'uNoiseStrength': 0.}, {}),
        ('noise-map-zero', {}, {'uMergedNoise': zero}),
        ('mix-zero', {'uMix': 0.}, {}),
        ('legacy-AB-zero', {'uMergedNoiseOn': 0}, {}),
        ('satmask', {'uSatMaskOn': 1}, {'uSatMask': sat_all}),
    ]:
        _, out = draw.run('kFsRawDenoiseFused', dict(images, **maps), dict(uniforms, **changes), (w, h))
        err = float(np.max(np.abs(out[0][..., :3]-expected)))
        check.test(label+'/P0-off/'+name, err < 1e-7, max_abs_error=err)
    _, measured = draw.run('kFsRawDenoiseFused', images, uniforms, (w, h))
    check.test(label+'/P0-noisy-measured-finite+active', np.isfinite(measured[0]).all()
               and np.max(np.abs(measured[0][..., :3]-expected)) > 1e-7,
               max_abs_change=float(np.max(np.abs(measured[0][..., :3]-expected))))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--json', type=Path, help='optional detailed machine-readable evidence')
    args = ap.parse_args()
    def timeout(_signum, _frame):
        raise TimeoutError('115s wall deadline exceeded (driver compile/draw included)')
    signal.signal(signal.SIGALRM, timeout)
    signal.alarm(115)
    started = time.monotonic()
    check, gl, draw = Check(), None, None
    report = dict(scope='desktop real production shader draw; NOT phone/Mali full draw or C++ host execution',
                  rewrite='only #version 300 es -> #version 330 core', mrt_dtype='RGBA32F',
                  scale=2, forced_msize=7, input_sizes=[32, 24])
    try:
        shaders = offline.extract_shaders()
        shaders.update(offline.extract_shaders(ROOT / 'core/engine/src/render/HighlightNoiseFilterShader.h'))
        names = ['kFsPc3d', 'kFsPc3dDown', 'kFsPc3dUp', 'kFsHighlightNoiseDown',
                 'kFsHighlightNoiseUp', 'kFsChromaDenoise', 'kFsChromaDown', 'kFsRawDenoiseFused']
        report['shader_sha256'] = {name: hashlib.sha256(shaders[name].encode()).hexdigest() for name in names}
        gl = offline.GL(32, 24)
        report['gpu'] = {key: gl.ctx.info.get(key) for key in ('GL_VENDOR', 'GL_RENDERER', 'GL_VERSION')}
        print(json.dumps(report, ensure_ascii=False), flush=True)
        renderer = str(report['gpu'].get('GL_RENDERER', '')).lower()
        if any(word in renderer for word in ('llvmpipe', 'softpipe', 'swrast', 'software rasterizer')):
            raise RuntimeError('software renderer is not a real desktop GPU')
        draw = Draw(gl, shaders, check)
        for dtype in ('f2', 'f4'):
            for measured in (False, True):
                try:
                    execute(draw, check, dtype, measured)
                except TimeoutError:
                    raise
                except Exception as e:
                    check.test(f'{dtype}/measured={measured}/link-draw', False, error=f'{type(e).__name__}: {e}')
    except Exception as e:
        check.test('GPU/environment/deadline', False, error=f'{type(e).__name__}: {e}')
    finally:
        signal.alarm(0)
        if draw:
            report['draw_count'], report['interfaces'] = draw.calls, draw.interfaces
            draw.close()
        if gl:
            gl.close()
    report.update(elapsed_seconds=round(time.monotonic()-started, 3), results=check.results,
                  failures=check.failures, verdict='FAIL' if check.failures else 'PASS')
    if args.json:
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False)+'\n')
    print(f"{report['verdict']} desktop production postprocess: {len(check.results)} checks, "
          f"{len(check.failures)} failures, {report.get('draw_count', 0)} draws, {report['elapsed_seconds']}s. "
          'NOT phone/Mali full draw; C++ host allocation/static flags not tested.', flush=True)
    return int(bool(check.failures))


if __name__ == '__main__':
    sys.exit(main())
