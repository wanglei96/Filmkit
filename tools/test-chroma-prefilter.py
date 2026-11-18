#!/usr/bin/env python3
"""Production CS/CM MRTs against independent RGB/Jacobian/covariance oracles.

Desktop GL is not Android/Mali or final-photo acceptance. Bounds cover the local
linearization with discrete peak/probe/median-rank decisions held fixed, including
the smooth CS gradient gate; they do not bound nonlinear rank/gate switching.
"""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
L = np.array([.2627, .6780, .0593])
ONE = np.ones(3)
IDENTITY = np.eye(3)


def load_post():
    spec = importlib.util.spec_from_file_location(
        'chroma_post', ROOT / 'tools/check-highlight-postprocess.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def oracle(image, x, y, median):
    h, w = image.shape[:2]
    def pos(dx=0, dy=0):
        return min(w-1, max(0, x+dx)), min(h-1, max(0, y+dy))
    def rgb(q):
        return image[q[1], q[0], :3].astype('f8')
    def protected(q):
        return image[q[1], q[0], 3] > .5
    center = pos()
    c = rgb(center)
    jac = {}
    def add(q, j):
        jac[q] = jac.get(q, np.zeros((3, 3))) + j
    def bypass():
        return c, {center: IDENTITY}, 'bypass'
    if protected(center):
        return bypass()
    ch = abs(c[0]-c[1])+abs(c[2]-c[1])
    if ch <= .12*max(c@L, 1e-4):
        return bypass()
    if median:
        red, blue = [], []
        for dy in range(-1, 2):
            for dx in range(-1, 2):
                q = pos(dx, dy)
                if protected(q):
                    continue
                v = rgb(q)
                red.append((v[0]-v[1], q))
                blue.append((v[2]-v[1], q))
        if len(red) < 5:
            return bypass()
        # Same strict-less selection ordering, including ties and clamped taps.
        for values in (red, blue):
            for i in range(len(values)):
                for j in range(i+1, len(values)):
                    if values[j][0] < values[i][0]:
                        values[i], values[j] = values[j], values[i]
        lo, hi = (len(red)-1)//2, len(red)//2
        r, b = (red[lo][0]+red[hi][0])*.5, (blue[lo][0]+blue[hi][0])*.5
        result = np.array([c[1]+r, c[1], c[1]+b])
        result += c@L-result@L
        add(center, np.outer(ONE, L))
        jr = np.outer(IDENTITY[0]-L[0]*ONE, [1, -1, 0])
        jb = np.outer(IDENTITY[2]-L[2]*ONE, [0, -1, 1])
        for index in (lo, hi):
            add(red[index][1], .5*jr)
            add(blue[index][1], .5*jb)
        branch = 'median'+str(len(red))
    else:
        grad = np.array([(rgb(pos(1, 0))-rgb(pos(-1, 0)))@L,
                         (rgb(pos(0, 1))-rgb(pos(0, -1)))@L])
        norm = np.linalg.norm(grad)
        glen = norm*255
        t = np.clip((glen-6)/10, 0, 1)
        gate = t*t*(3-2*t)
        if gate <= 0 or glen <= 1e-6:
            return bypass()
        nd = grad/norm
        probes = []
        for scale in (3, 6, 12):
            delta = np.round(nd*scale).astype(int)
            for sign in (1, -1):
                q = pos(*(delta*sign))
                if protected(q):
                    continue
                a = rgb(q)
                probes.append(abs(a[0]-a[1])+abs(a[2]-a[1]))
        if not probes:
            return bypass()
        hi, lo, n = max(probes), min(probes), len(probes)
        if not ((hi < .5*ch and n >= 4) or (ch > 1.05*hi and lo < .5*ch and n >= 2)):
            return bypass()
        comp = .15*c+.85*(c@L)*ONE
        result = (1-gate)*c+gate*comp
        add(center, (1-.85*gate)*IDENTITY+.85*gate*np.outer(ONE, L))
        dg = (comp-c)*(6*t*(1-t)/10)*255
        add(pos(-1, 0), np.outer(-dg*nd[0], L))
        add(pos(1, 0), np.outer(dg*nd[0], L))
        add(pos(0, -1), np.outer(-dg*nd[1], L))
        add(pos(0, 1), np.outer(dg*nd[1], L))
        branch = 'smooth-gate' if 0 < t < 1 else 'full-gate'
    alive = np.diag(((result >= 0) & (result <= 64)).astype(float))
    return np.clip(result, 0, 64), {q: alive@j for q, j in jac.items()}, branch


def bound_for(jac, cov):
    if len(jac) == 1:
        (x, y), j = next(iter(jac.items()))
        return j@cov[y, x]@j.T
    total, mass = np.zeros((3, 3)), 0.
    for (x, y), j in jac.items():
        k = np.linalg.norm(j)
        if k > 0:
            total += j@cov[y, x]@j.T/k
            mass += k
    return total*mass


def draw_filter(draw, image, cov, median, half=False):
    h, w = image.shape[:2]
    diag = np.zeros((h, w, 4), 'f4')
    off = diag.copy()
    diag[..., :3] = np.diagonal(cov, axis1=-2, axis2=-1)
    off[..., :3] = cov[..., [0, 0, 1], [1, 2, 2]]
    name = 'kFsColorMedian' if median else 'kFsColorSmooth'
    uniforms = {'uSize': (w, h), 'uNoiseOn': 1}
    if median:
        uniforms['uMinChroma'] = .12
    else:
        uniforms.update(uGradLo=6., uGradHi=16., uSideK=.5, uPeakK=1.05, uChromaMin=.12)
    _, arrays = draw.run(name, {'uTex': draw.texture(image, 'f2' if half else 'f4'),
        'uVariance': draw.texture(diag), 'uCovariance': draw.texture(off)}, uniforms,
        (w, h), outputs=3, output_dtypes=['f2' if half else 'f4', 'f4', 'f4'])
    return arrays[0], draw_post.covariance(arrays[1], arrays[2])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--json', type=Path, required=True)
    args = ap.parse_args()
    global draw_post
    draw_post = load_post()
    gl = draw_post.offline.GL(67, 65)
    check = draw_post.Check()
    draw = draw_post.Draw(gl, draw_post.offline.extract_shaders(), check)
    rng = np.random.default_rng(10427)
    counts = {}
    finite_differences = 0
    try:
        images = {}
        image = np.zeros((65, 67, 4), 'f4')
        image[..., :3] = .06
        image[:, 34:, :3] = .10
        image[:, 33, :3] = [.18, .03, .12]
        images['smooth-gate'] = image.copy()
        image[:, 34:, :3] = .8
        images['full-gate'] = image.copy()
        images['grey-HDR'] = np.tile(np.array([32, 32, 32, 0], 'f4'), (7, 9, 1))
        images['colour-HDR'] = np.tile(np.array([50, 20, 40, 0], 'f4'), (7, 9, 1))
        image = np.zeros((11, 13, 4), 'f4')
        image[..., :3] = rng.uniform(.01, .9, image[..., :3].shape)
        images['random-colour'] = image.copy()
        image[4:7, 5:8, 3] = 1
        images['protected-centers'] = image.copy()
        image[..., 3] = 0
        image[4, 5, 3] = 1
        images['even-valid-support'] = image.copy()
        image[4:7, 5:8, 3] = 1
        image[5, 6, 3] = 0
        images['insufficient-support'] = image.copy()
        images['one-pixel'] = np.array([[[.3, .1, .6, 0]]], 'f4')
        images['single-row'] = images['random-colour'][5:6].copy()
        images['single-column'] = images['random-colour'][:, 5:6].copy()
        for label, image in images.items():
            h, w = image.shape[:2]
            a = rng.normal(size=(h, w, 3, 7))*1e-3
            cov = a@a.swapaxes(-1, -2)
            for median in (False, True):
                for half in (False, True):
                    source = image.astype('f2').astype('f4') if half else image
                    out, noise = draw_filter(draw, source, cov, median, half)
                    eigenvalues = np.linalg.eigvalsh(noise)
                    check.test(f'{label}/CM{median}/half{half}/PSD',
                        np.isfinite(noise).all() and eigenvalues.min() > -2e-8
                        and np.max(noise[..., [0, 0, 1], [1, 2, 2]]) != 0,
                        min_eigenvalue=float(eigenvalues.min()))
                    signal_error, noise_error, bound_min = 0., 0., 0.
                    half_ulp_error = 0.
                    jac_error = 0.
                    for y in range(h):
                        for x in range(w):
                            expected, jac, branch = oracle(source, x, y, median)
                            counts[branch] = counts.get(branch, 0)+1
                            if half:
                                expected = expected.astype('f2').astype('f4')
                                ulp = np.maximum(np.spacing(np.abs(expected).astype('f2')).astype('f4'), 1e-7)
                                half_ulp_error = max(half_ulp_error, np.max(np.abs(out[y, x, :3]-expected)/ulp))
                            signal_error = max(signal_error, np.max(np.abs(out[y, x, :3]-expected)))
                            bound = bound_for(jac, cov)
                            noise_error = max(noise_error, np.max(np.abs(noise[y, x]-bound)))
                            # All pixels share a latent vector: includes arbitrary
                            # RGB/spatial correlation, overlapping/clamped support.
                            actual_j = sum(j@a[qy, qx] for (qx, qy), j in jac.items())
                            bound_min = min(bound_min, np.linalg.eigvalsh(bound-actual_j@actual_j.T).min())
                            if not half and branch != 'bypass' and x == w//2 and y == h//2:
                                eps = 1e-6
                                source64 = source.astype('f8')
                                for (qx, qy), j in jac.items():
                                    for c in range(3):
                                        delta = np.zeros_like(source64)
                                        delta[qy, qx, c] = eps
                                        plus, jp, bp = oracle(source64+delta, x, y, median)
                                        minus, jm, bm = oracle(source64-delta, x, y, median)
                                        # Equal-valued ranks are nondifferentiable.
                                        # Test only the declared selected-rank region.
                                        if median and (jp.keys() != jac.keys() or jm.keys() != jac.keys()
                                            or any(not np.array_equal(jp[q], jac[q]) or
                                                   not np.array_equal(jm[q], jac[q]) for q in jac)):
                                            continue
                                        if bp == bm == branch:
                                            jac_error = max(jac_error, np.max(np.abs((plus-minus)/(2*eps)-j[:, c])))
                                            finite_differences += 1
                    check.test(f'{label}/CM{median}/half{half}/oracle',
                        (half_ulp_error <= 1.01 if half else signal_error < 2e-5) and noise_error < 2e-7
                        and bound_min > -1e-12 and jac_error < 2e-5
                        and np.array_equal(out[..., 3], source[..., 3]),
                        signal_max_abs=float(signal_error), covariance_max_abs=float(noise_error),
                        correlation_bound_min_eigenvalue=float(bound_min),
                        jacobian_finite_difference_max_abs=float(jac_error))
                    for resource in reversed(draw.resources):
                        resource.release()
                    draw.resources.clear()
        check.test('branch-coverage', all(counts.get(k, 0) for k in
                   ('bypass', 'full-gate', 'smooth-gate', 'median9', 'median8')), counts=counts)
        check.test('finite-difference-coverage', finite_differences >= 30,
                   verified=finite_differences)
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps({'scope': __doc__, 'gpu': gl.ctx.info.get('GL_RENDERER'),
            'draws': draw.calls, 'checks': check.results, 'failures': check.failures}, indent=2)+'\n')
        if check.failures:
            raise SystemExit(1)
    finally:
        draw.close()
        gl.close()


if __name__ == '__main__':
    main()
