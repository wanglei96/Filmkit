#!/usr/bin/env python3
"""Measure clipped-core influence through actual P4 and all six D1 bands.

This is a diagnostic experiment, NOT a regression proving the phone bug fixed.
Only masked pixels differ between paired inputs. Desktop shaders are extracted
unchanged except ES300 -> core330. Production half-float signal boundaries and
FP32 starlet buffers are retained. No RAW/demosaic/film/JPEG or phone execution.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    'postprocess', ROOT / 'tools/check-highlight-postprocess.py')
post = importlib.util.module_from_spec(spec)
spec.loader.exec_module(post)
LUMA = np.array([.2627, .6780, .0593])


def p4(draw, image, mask, covariance):
    h, w = image.shape[:2]
    inputs = dict(
        uTex=draw.texture(image, 'f2'), uSatMask=draw.texture(mask[..., None]),
        uLsc=draw.texture(np.ones((1, 1, 3))),
        uLocalVariance=draw.texture(np.broadcast_to(np.diag(covariance), (h, w, 3))),
        uLocalCovariance=draw.texture(np.broadcast_to(
            covariance[[0, 0, 1], [1, 2, 2]], (h, w, 3))))
    _, arrays = draw.run('kFsChromaDenoise', inputs, dict(
        uSize=(w, h), uLscSize=(1, 1), uNoiseA=(0.,)*3, uNoiseB=(0.,)*3, uLocalNoiseOn=1,
        uLocalNoiseScale=1., uMix=.75, uMoire=2., uMoireK=2.,
        uCgK=0., uCgScope=1., uSatMaskOn=1), (w, h))
    return arrays[0].astype('f2').astype('f4')


def d1(draw, image, mask, covariance, band_sigma):
    h, w = image.shape[:2]
    signal = draw.texture(image, 'f2')
    inputs = dict(uTex=signal, uLsc=draw.texture(np.ones((1, 1, 3))),
        uLocalVariance=draw.texture(np.broadcast_to(np.diag(covariance), (h, w, 3))),
        uLocalCovariance=draw.texture(np.broadcast_to(
            covariance[[0, 0, 1], [1, 2, 2]], (h, w, 3))))
    (coarse, sigma, removed), _ = draw.run('kFsChromaDown', inputs, dict(
        uSize=(w, h), uLscSize=(1, 1), uNoiseA=(0.,)*3, uNoiseB=(0.,)*3, uLocalNoiseOn=1,
        uLocalNoiseScale=1.), (w, h), 3)
    for level, norm in enumerate(band_sigma):
        (horizontal,), _ = draw.run('kFsChromaBlur', dict(uPrev=coarse),
            dict(uSize=(w, h), uStep=1 << level), (w, h))
        (coarse, removed), _ = draw.run('kFsChromaAtrous', dict(
            uPrev=coarse, uHorizontal=horizontal, uGuide=signal,
            uSigma=sigma, uRemoved=removed), dict(uSize=(w, h),
            uStep=1 << level, uK=2.5, uRevert=.2,
            uBandSigma=float(norm), uCgK=0.), (w, h), 2)
    _, arrays = draw.run('kFsChromaApply', dict(uTex=signal,
        uRemoved=removed, uSatMask=draw.texture(mask[..., None])),
        dict(uMix=.75, uSatMaskOn=1), (w, h))
    return arrays[0].astype('f2').astype('f4')


def metric(a, b, outside):
    delta = b[..., :3] - a[..., :3]
    chroma = delta - (delta @ LUMA)[..., None]
    return dict(rgb_absmax=float(np.abs(delta[outside]).max()),
        chroma_absmax=float(np.abs(chroma[outside]).max()),
        magenta_max=float(np.minimum(delta[..., 0]-delta[..., 1],
                                    delta[..., 2]-delta[..., 1])[outside].max()),
        changed_pixels=int(np.count_nonzero(np.max(np.abs(delta), axis=-1)[outside] > 1e-6)))


def preprocess(draw, image):
    h, w = image.shape[:2]
    _, arrays = draw.run('kFsColorSmooth', dict(uTex=draw.texture(image, 'f2')),
        dict(uSize=(w, h), uGradLo=6., uGradHi=16., uSideK=.5,
             uPeakK=1.05, uChromaMin=.12), (w, h))
    result = arrays[0].astype('f2').astype('f4')
    for _ in range(2):
        _, arrays = draw.run('kFsColorMedian', dict(uTex=draw.texture(result, 'f2')),
            dict(uSize=(w, h), uMinChroma=.12), (w, h))
        result = arrays[0].astype('f2').astype('f4')
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--json', type=Path, required=True)
    args = ap.parse_args()
    import re
    text = (ROOT / 'core/engine/src/render/FrameRenderer.cpp').read_text()
    literal = re.search(r'band_sigma\[kChromaLevels\]\s*=\s*\{(.*?)\}', text, re.S)[1]
    bands = [float(x.strip().rstrip('f')) for x in literal.split(',')]
    assert len(bands) == 6
    gl = post.offline.GL(129, 131)
    check = post.Check()
    shaders = post.offline.extract_shaders()
    draw = post.Draw(gl, shaders, check)
    names = ['kFsChromaDenoise', 'kFsChromaDown', 'kFsChromaBlur',
             'kFsChromaAtrous', 'kFsChromaApply', 'kFsColorSmooth', 'kFsColorMedian']
    report = dict(scope=__doc__, shader_sha256={n: hashlib.sha256(
        shaders[n].encode()).hexdigest() for n in names}, rows=[])
    try:
        yy, xx = np.indices((131, 129))
        radius = np.sqrt((xx-64.3)**2 + (yy-65.2)**2)
        for shape in ('vertical', 'curved'):
            mask = (xx >= 65 if shape == 'vertical' else radius < 39).astype('f4')
            outside = mask == 0
            for core, background in ((.51, .5), (.55, .5), (3., .5)):
                for variance in (1e-6, 1e-5, 1e-4):
                    covariance = variance * np.array([[2., -.2, .1], [-.2, 1., -.1], [.1, -.1, 1.5]])
                    a = np.ones((131, 129, 4), 'f4')
                    a[..., :3] = np.where(mask[..., None] > .5, core, background)
                    b = a.copy()
                    b[..., 0] += mask * .03
                    b[..., 2] += mask * .03
                    np.testing.assert_array_equal(a[outside], b[outside])
                    pa, pb = (p4(draw, x, mask, covariance) for x in (a, b))
                    da, db = (d1(draw, x, mask, covariance, bands) for x in (a, b))
                    ca, cb = (d1(draw, x, mask, covariance, bands) for x in (pa, pb))
                    for source, output in ((a, pa), (b, pb), (a, da), (b, db), (a, ca), (b, cb)):
                        np.testing.assert_array_equal(output[~outside, :3],
                            source.astype('f2').astype('f4')[~outside, :3])
                    # Noiseless achromatic step must stay achromatic at unmasked locations.
                    for output in (pa, da, ca):
                        assert np.max(np.ptp(output[outside, :3], axis=-1)) < 1e-6
                    row = dict(shape=shape, core=core, background=background,
                        variance=variance, p4=metric(pa, pb, outside),
                        d1=metric(da, db, outside), chain=metric(ca, cb, outside))
                    report['rows'].append(row)
                    print(json.dumps(row), flush=True)
                    # Bound peak temporary GL storage independently of fixture count.
                    for resource in reversed(draw.resources):
                        resource.release()
                    draw.resources.clear()
        # Shared prefilters are actually consumed only by the PC3D branch.
        # A fabricated narrow fringe can disappear here BEFORE PC3D executes.
        mask = (radius < 39).astype('f4')
        ring = (radius >= 39) & (radius < 40)
        source = np.zeros((131, 129, 4), 'f4')
        source[..., :3] = np.where(mask[..., None] > .5, 3., .5)
        source[..., 3] = mask
        source[ring, 0] += .1
        source[ring, 2] += .1
        prepared = preprocess(draw, source)
        covariance = 1e-5 * np.array([[2., -.2, .1], [-.2, 1., -.1], [.1, -.1, 1.5]])
        baseline = d1(draw, p4(draw, source, mask, covariance), mask, covariance, bands)
        consumed = d1(draw, p4(draw, prepared, mask, covariance), mask, covariance, bands)
        def fringe_peak(x):
            return float(np.minimum(x[..., 0]-x[..., 1], x[..., 2]-x[..., 1])[ring].max())
        report['prefilter_experiment'] = dict(
            scope='Synthetic thin ring; consumed variant is NOT current production PC3D-off routing',
            source_peak=fringe_peak(source), prefilter_peak=fringe_peak(prepared),
            current_p4_d1_peak=fringe_peak(baseline),
            prefilter_plus_p4_d1_peak=fringe_peak(consumed))
        print(json.dumps(report['prefilter_experiment']), flush=True)
        report['verdict'] = 'DIAGNOSTIC_COMPLETED_NOT_A_FIX'
        report['draw_count'] = draw.calls
        report['gpu'] = gl.ctx.info.get('GL_RENDERER')
        report['interpretation'] = (
            'Outside input is identical; nonzero outside delta proves influence from '
            'masked core through the indicated stage, not the cause of the phone JPEG. '
            'Neutral controls and masked centers are invariant. No PC3D full-chain '
            'or final-image comparison is made.')
        args.json.write_text(json.dumps(report, indent=2) + '\n')
    finally:
        draw.close()
        gl.close()


if __name__ == '__main__':
    main()
