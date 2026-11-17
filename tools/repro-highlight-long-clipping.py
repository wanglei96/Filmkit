#!/usr/bin/env python3
"""Regress clipped SHADOW_LONG leakage, without editing production shaders.

Runs CURRENT mismatch + MergeBayer GLSL on desktop GL. A required fixed local
PhotonCamera checkout supplies its complete aligned clipping-mask and weight
rewrite shaders for a separate reference experiment. This is not a replay of
the user's JPEGs, full-pipeline comparison, or device verification. The explicit
guard-bypass control retains the failure mechanism; production guards must fix it.
"""
from __future__ import annotations

import argparse
from dataclasses import replace
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import textwrap

import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def reference_shader(source, name):
    match = re.search(r'val ' + re.escape(name) + r'\s*=\s*"""(.*?)"""\.trimIndent\(\)', source, re.S)
    if not match:
        raise ValueError('Missing reference shader: ' + name)
    return textwrap.dedent(match.group(1)).strip()


def texture(gl, engine, array, dtype, linear=False):
    h, w = array.shape[:2]
    channels = array.shape[2] if array.ndim == 3 else 1
    result = gl.ctx.texture((w, h), channels, np.ascontiguousarray(array).tobytes(), dtype=dtype)
    result.filter = (engine.moderngl.LINEAR if linear else engine.moderngl.NEAREST,) * 2
    result.repeat_x = result.repeat_y = False
    return result


def phase_rgb(mosaic, cfa, roi):
    x0, y0, x1, y1 = roi
    channels = [[], [], []]
    for phase in range(4):
        channel = (cfa >> (phase * 2)) & 3
        channels[channel].append(mosaic[y0:y1, x0:x1][phase // 2::2, phase % 2::2].mean())
    # These are CFA channel means, not a substitute demosaic.
    return np.array([np.mean(values) for values in channels])


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--reference', type=Path, default=Path('/private/tmp/PhotonCamera'))
    ap.add_argument('--out', required=True, type=Path)
    args = ap.parse_args()
    sys.dont_write_bytecode = True
    engine = load('long_clip_engine', 'engine-shader-offline.py')
    temporal = load('long_clip_temporal', 'check-highlight-temporal.py')
    merge = load('long_clip_merge', 'check-highlight-merge.py')
    n = 128
    yy, xx = np.mgrid[:n, :n]
    cfa = 97
    gains = (1 / .4937, 1., 1 / .5447)
    phase = (xx & 1) + 2 * (yy & 1)
    colors = np.array([(cfa >> (2 * p)) & 3 for p in range(4)])[phase]
    scene = np.where((xx >= 54) & (xx < 70), .70, .08)
    scales = (1., 1., 2 ** -2.192, 2 ** 1.5)
    raw = [np.rint(64 + 959 * np.clip(scene / np.array(gains)[colors] / s, 0, 1)).astype('u2') for s in scales]
    gain_ratio = (1., 1., 79 / 72, 1.)
    u = temporal.uniforms(n, n, uGain=gains, uFrameScale=scales, uGainRatio=gain_ratio)
    fields = [np.zeros((1, 1, 3), 'f4') for _ in range(3)]
    case = {'frames': raw, 'fields': fields, 'uniforms': u}
    shader = engine.extract_shaders()['kFsMfnrMismatch']
    reference_path = args.reference / 'app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt'
    src = reference_path.read_text()
    clip_shader = reference_shader(src, 'alignedRawClippingMask')
    rewrite_shader = reference_shader(src, 'bentoRewriteWeight')
    gl = engine.GL(n, n)
    resources = []
    try:
        mismatch, written = temporal.gpu_case(engine, gl, shader, case)
        codes = np.rint(np.clip(mismatch, 0, 1) * 255).astype('u1')
        # All cells are below the real production full-acceptance boundary.
        # Dilation/median/zero-motion processing cannot change that decision.
        assert np.max(codes) < 25, codes
        weights = np.full((4, 4, 3), 255, 'u1')
        raw_tex = texture(gl, engine, raw[2], 'u2')
        flow_tex = texture(gl, engine, np.zeros((n // 2, n // 2, 2), 'f4'), 'f4')
        resources.extend([raw_tex, flow_tex])
        mask = gl.run(clip_shader, {
            'uRawSize': (n, n), 'uBayerSize': (n // 2, n // 2),
            'uOutputSize': (n // 4, n // 4), 'uBayerQuadsPerTexel': 2,
            'uFlowScaleOffset': (1., 1., 0., 0.),
            'uPhaseClippingLevels': (64 + 959 * 250 / 255,) * 4,
        }, {'uRaw': raw_tex, 'uFlow': flow_tex}, size=(n // 4, n // 4))[..., 0]
        mask_codes = np.rint(mask * 255).astype('u1')
        mask_tex = texture(gl, engine, mask_codes, 'f1', True)
        existing_tex = texture(gl, engine, np.full((4, 4), 255, 'u1'), 'f1', True)
        resources.extend([mask_tex, existing_tex])
        guarded_long = gl.run(rewrite_shader, {
            'uSize': (n // 4, n // 4), 'uHasExistingWeight': 1,
        }, {'uExistingWeight': existing_tex, 'uBentoMask': mask_tex}, size=(n // 4, n // 4))[..., 0]
        guarded_weights = np.full((n // 4, n // 4, 3), 255, 'u1')
        guarded_weights[..., 1] = np.rint(guarded_long * 255).astype('u1')
        renderer = gl.ctx.info['GL_RENDERER']
    finally:
        for resource in resources:
            resource.release()
        gl.close()
    global_weight = tuple(min(50., 1 / (g * s) ** 2) for g, s in zip(gain_ratio, scales))
    global_weight = (*global_weight[:3], 1.)
    sigma = tuple(merge.kernel_sigma(100., 4, w) for w in global_weight)
    c = merge.Case('static-neutral-reflection-clipped-long', raw,
        [np.zeros((1, 1, 2), 'f4') for _ in range(3)], weights,
        np.zeros((n // 16, n // 16, 2), 'f4'), np.zeros((n // 4, n // 4), 'u1'),
        np.zeros((n // 4, n // 4), 'u1'), cfa=cfa, black=(64.,) * 4,
        white=1023., gain=gains, scales=scales, short=3,
        sigma=sigma, global_weight=global_weight, noise_gain_ratio=gain_ratio)
    gpu = merge.GPU(merge.source())
    guard = load('long_clip_guard', 'check-highlight-long-guard.py')
    passes = guard.Passes(gpu.ctx)
    outputs = {}
    try:
        _, _, production_weights = guard.guard_weights(passes, c,
            np.zeros((n // 2, n // 2), 'u1'), 2)
        for name, candidate in (
            ('guard_bypass_control', c),
            ('production_guard', replace(c, long_frames=2, long_weights=production_weights)),
            ('reference_guard_experiment', replace(c, long_frames=2, long_weights=guarded_weights)),
            ('no_long_contribution_control', replace(c, global_weight=(1., 1., 0., 1.))),
            ('equal_exposure_control', replace(c, raw=[raw[0]] * 4,
                scales=(1.,) * 4, global_weight=(1.,) * 4, sigma=(sigma[0],) * 4)),
        ):
            image = gpu.run(candidate)
            expected = merge.cpu_reference(candidate)
            assert np.max(np.abs(image - expected)) < .001
            rgb = phase_rgb(image, cfa, (58, 8, 66, 120))
            outputs[name] = {'cfa_WB_channel_means': rgb.tolist(),
                'min_R_minus_G_B_minus_G': float(min(rgb[0] - rgb[1], rgb[2] - rgb[1]))}
    finally:
        passes.close()
        gpu.close()
    assert outputs['guard_bypass_control']['min_R_minus_G_B_minus_G'] > .05
    for name in ('production_guard', 'reference_guard_experiment', 'no_long_contribution_control', 'equal_exposure_control'):
        assert abs(outputs[name]['min_R_minus_G_B_minus_G']) < .005
    report = {
        'status': 'SYNTHETIC_LONG_CLIPPING_GUARD_PASS', 'test_modifies_production': False,
        'device_verified': False, 'user_photo_causality_proven': False,
        'renderer': renderer, 'size': [n, n], 'zero_flow': True,
        'input': 'static neutral WB signal .08 background, .70 narrow reflection; base/short fully observable',
        'scales': scales, 'global_weight': global_weight, 'sigma': sigma,
        'long_native_clipped_samples': int(np.count_nonzero(raw[2] == 1023)),
        'base_native_clipped_samples': int(np.count_nonzero(raw[0] == 1023)),
        'mismatch_u8_per_alt_min': codes.min(axis=(0, 1)).tolist(),
        'mismatch_u8_per_alt_max': codes.max(axis=(0, 1)).tolist(),
        'mismatch_uniforms_written': written, 'reference_mask_nonzero': int(np.count_nonzero(mask)),
        'mismatch_sha256': hashlib.sha256(shader.encode()).hexdigest(),
        'merge_sha256': hashlib.sha256(merge.source().encode()).hexdigest(),
        'reference_commit': subprocess.check_output(['git', '-C', str(args.reference), 'rev-parse', 'HEAD'], text=True).strip(),
        'reference_clip_shader_sha256': hashlib.sha256(clip_shader.encode()).hexdigest(),
        'outputs': outputs,
        'limits': ['Current mismatch and MergeBayer run, but not P0/RCD/resolve/CCM/P4/D1/film/LUT/JPEG.',
            'CFA channel means are not demosaiced RGB or final display color.',
            'Production guard runs in the harness; reference experiment remains independent.',
            'No full reference-highlight mask/admission replay: fixture base is entirely unclipped, M=0.',
            'Shared mismatch helper uses RGBA16F before explicit RGBA8 quantization; not Mali parity.',
            'Not the latest two captures RAW; cannot attribute their exact artifact solely from this result.'],
    }
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
