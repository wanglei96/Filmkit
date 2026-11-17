#!/usr/bin/env python3
"""Run production long-frame guard shaders with real RGBA8 intermediate targets.

Compare against an independent NumPy stencil and the pinned PC original
alignedRawClippingMask. Check two-stage weight quantization, padded alignment,
all native phases, threshold boundaries, and guarded merge MRT noise/support.
Desktop GL is not a real-device or full image-pipeline quality validation.
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

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
HEADER = ROOT / 'core/engine/src/render/HighlightLongShader.h'
REFERENCE_COMMIT = 'f3449a1a82b9f3ce0d757c5ea49002e33d123fca'
sys.dont_write_bytecode = True


def load_merge():
    spec = importlib.util.spec_from_file_location('long_guard_merge', ROOT / 'tools/check-highlight-merge.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def sources():
    return dict(re.findall(r'const char\* (\w+) = R"\((.*?)\)";', HEADER.read_text(), re.S))


def original_shader(checkout, name):
    path = checkout / 'app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt'
    match = re.search(r'val ' + re.escape(name) + r'\s*=\s*"""(.*?)"""\.trimIndent\(\)', path.read_text(), re.S)
    if not match:
        raise AssertionError('Missing original shader: ' + name)
    return textwrap.dedent(match.group(1)).strip()


class Passes:
    def __init__(self, ctx):
        self.ctx = ctx
        self.programs = {}

    def run(self, shader, uniforms, inputs, size, channels=4):
        if shader not in self.programs:
            program = self.ctx.program(vertex_shader='''#version 330 core
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}''', fragment_shader=shader.replace('#version 300 es', '#version 330 core'))
            self.programs[shader] = program, self.ctx.vertex_array(program, [])
        program, vao = self.programs[shader]
        resources = []
        try:
            for unit, (name, (data, dtype, filtered)) in enumerate(inputs.items()):
                h, w = data.shape[:2]
                components = data.shape[2] if data.ndim == 3 else 1
                texture = self.ctx.texture((w, h), components,
                    np.ascontiguousarray(data).tobytes(), dtype=dtype, alignment=1)
                resources.append(texture)
                texture.filter = (moderngl.LINEAR if filtered else moderngl.NEAREST,) * 2
                texture.repeat_x = texture.repeat_y = False
                texture.use(unit)
                program[name].value = unit
            for name, value in uniforms.items():
                program[name].value = value
            target = self.ctx.texture(size, channels, dtype='f1')
            resources.append(target)
            fbo = self.ctx.framebuffer([target])
            resources.append(fbo)
            fbo.use()
            self.ctx.viewport = (0, 0, *size)
            self.ctx.disable(moderngl.BLEND)
            vao.render(moderngl.TRIANGLES, vertices=3)
            return np.frombuffer(fbo.read(components=channels, dtype='f1', alignment=1),
                                 'u1').reshape(size[1], size[0], channels).copy()
        finally:
            for resource in reversed(resources):
                resource.release()

    def close(self):
        for program, vao in self.programs.values():
            vao.release()
            program.release()


def levels(c):
    black = np.array(c.black, dtype='f4')
    return black + (np.float32(c.white) - black) * np.float32(250 / 255)


def guard_weights(runner, c, reference, flags):
    shaders = sources()
    h, w = c.raw[0].shape
    size = (w // 4, h // 4)
    tiles = (float(c.off[0].shape[1]), float(c.off[0].shape[0]))
    inputs = {f'uRaw{i}': (c.raw[i], 'u2', False) for i in range(1, 4)}
    inputs.update({f'uOff{i}': (c.off[i-1], 'f2' if c.flow_half else 'f4', False) for i in range(1, 4)})
    clip = runner.run(shaders['kFsHighlightLongClip'],
        {'uRawSize': (w, h), 'uTiles': tiles, 'uPhaseClippingLevels': tuple(levels(c)),
         'uLongFrames': flags}, inputs, size)
    first = runner.run(shaders['kFsHighlightLongReferenceWeight'],
        {'uRawSize': (w, h), 'uOutputSize': size, 'uTiles': tiles},
        {'uW': (c.weights, 'f1', True), 'uReferenceHighlight': (reference, 'f1', False)}, size)
    final = runner.run(shaders['kFsHighlightLongSourceWeight'], {'uOutputSize': size},
        {'uExistingWeight': (first, 'f1', True), 'uClippingMask': (clip, 'f1', False)}, size)
    return clip[..., :3], first[..., :3], final[..., :3]


def cpu_clip(merge, c, frame):
    h, w = c.raw[0].shape
    yy, xx = np.mgrid[:h//4, :w//4]
    q = np.stack([np.minimum(xx*2+1, w//2-1), np.minimum(yy*2+1, h//2-1)], axis=-1)
    tiles = np.array([c.off[0].shape[1], c.off[0].shape[0]])
    uv = (2*q+1).astype('f4')/(tiles*128).astype('f4')
    flow = merge.linear(c.off[frame-1].astype(float), uv).astype('f4')
    anchor = np.rint((q.astype('f4')+flow*np.float32(.5)).astype('f4')).astype(int)
    limit = np.ceil(levels(c)).astype('u4')
    clipped = np.zeros((h//4, w//4), bool)
    for dy in range(-1, 2):
        for dx in range(-1, 2):
            sx = np.clip(anchor[..., 0]+dx, 0, w//2-1)*2
            sy = np.clip(anchor[..., 1]+dy, 0, h//2-1)*2
            for phase in range(4):
                clipped |= c.raw[frame][sy+(phase >> 1), sx+(phase & 1)] >= limit[phase]
    return clipped.astype('u1')*255


def original_clip(runner, merge, shader, c, frame):
    h, w = c.raw[0].shape
    yy, xx = np.mgrid[:h//2, :w//2]
    tiles = np.array([c.off[0].shape[1], c.off[0].shape[0]])
    uv = np.stack([2*xx+1, 2*yy+1], axis=-1)/(tiles*128)
    # Same local coarse alignment, expressed in the reference shader's native
    # normalized converted-flow interface, with no extra half-float rounding.
    flow = (merge.linear(c.off[frame-1].astype(float), uv)/np.array([w, h])).astype('f4')
    return runner.run(shader,
        {'uRawSize': (w, h), 'uBayerSize': (w//2, h//2), 'uOutputSize': (w//4, h//4),
         'uBayerQuadsPerTexel': 2, 'uFlowScaleOffset': (1., 1., 0., 0.),
         'uPhaseClippingLevels': tuple(levels(c))},
        {'uRaw': (c.raw[frame], 'u2', False), 'uFlow': (flow, 'f4', False)},
        (w//4, h//4), 1)[..., 0]


def cases(merge):
    out = []
    for white in (1023., 65535.):
        for phase in range(4):
            c = merge.case(f'threshold-{int(white)}-phase-{phase}', w=36, h=26)
            black = (64.5, 80.25, 96.75, 112.5) if white == 1023 else (1024.5, 2048.25, 1536.75, 3072.5)
            c = replace(c, white=white, black=black)
            raw = [np.full_like(a, int(black[0])+2) for a in c.raw]
            threshold = int(np.ceil(levels(c)[phase]))
            for i in range(1, 4):
                raw[i][phase//2::2, phase%2::2] = threshold-1
                raw[i][8+(phase//2):18:2, 12+(phase%2):24:2] = threshold
            off = [np.full_like(a, (3., -1.)) for a in c.off]  # quad half-ties
            out.append((replace(c, raw=raw, off=off), 7))
    for i, (w, h, flags) in enumerate(((160, 46, 2), (34, 22, 1), (48, 32, 4),
                                     (8, 8, 7), (130, 134, 0), (64, 48, 7))):
        c = merge.case(f'padded-flow-{w}x{h}-flags-{flags}', w=w, h=h, seed=41+i)
        out.append((c, flags))
    c = merge.case('large-flow-clamped-edge', 36, 26)
    out.append((replace(c, off=[np.full_like(a, (-200., 300.)) for a in c.off]), 7))
    c = merge.case('normalized-16F-coarse-flow', 36, 26)
    out.append((replace(c, off=[a.astype('f2') for a in c.off], flow_half=True,
                        flow=c.flow.astype('f2')), 2))
    for name, lut in merge.CFA.items():
        out.append((replace(merge.case('guarded-CFA-'+name, 38, 30), cfa=lut), 2))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--reference', type=Path, default=Path('/private/tmp/PhotonCamera'))
    ap.add_argument('--out', type=Path, required=True)
    args = ap.parse_args()
    commit = subprocess.check_output(['git', '-C', str(args.reference), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != REFERENCE_COMMIT:
        raise AssertionError('Reference checkout must match ' + REFERENCE_COMMIT)
    original = original_shader(args.reference, 'alignedRawClippingMask')
    merge = load_merge()
    for shader in sources().values():
        merge.static_check(shader, True)
    gpu = merge.GPU(merge.source())
    runner = Passes(gpu.ctx)
    rows = []
    try:
        for index, (c, flags) in enumerate(cases(merge)):
            h, w = c.raw[0].shape
            rng = np.random.default_rng(700+index)
            reference = rng.integers(0, 256, (h//2, w//2), dtype='u1')
            reference[:h//4] = 0
            reference[h//3:2*h//3, w//6:w//3] = 255
            clip, first, final = guard_weights(runner, c, reference, flags)
            for frame in range(1, 4):
                expected = cpu_clip(merge, c, frame) if flags & (1 << (frame-1)) else np.zeros_like(clip[..., 0])
                np.testing.assert_array_equal(clip[..., frame-1], expected)
                if flags & (1 << (frame-1)):
                    np.testing.assert_array_equal(clip[..., frame-1], original_clip(runner, merge, original, c, frame))
            yy, xx = np.mgrid[:h//4, :w//4]
            uv = np.stack([xx+.5, yy+.5], axis=-1)/np.array([w//4, h//4])
            temporal_uv = uv*np.array([w, h])/np.array([c.off[0].shape[1]*128, c.off[0].shape[0]*128])
            existing = merge.linear(c.weights.astype(float)/255., temporal_uv)
            sampled_ref = reference[np.minimum((uv[..., 1]*reference.shape[0]).astype(int), reference.shape[0]-1),
                                    np.minimum((uv[..., 0]*reference.shape[1]).astype(int), reference.shape[1]-1)]/255.
            expected_first = np.rint(existing*(1-sampled_ref[..., None])*255).astype('u1')
            first_error = int(np.abs(first.astype(int)-expected_first.astype(int)).max())
            assert first_error <= 1, (c.name, first_error)
            # Second pass samples the already-quantized first pass at centres;
            # a binary source mask must preserve or zero its byte exactly.
            np.testing.assert_array_equal(final, np.where(clip > 0, 0, first))
            sampled, sampling_error = gpu.sample_coefficients(replace(c, long_weights=final))
            for short in (-1, 1, 2, 3):
                guarded = replace(c, short=short, long_frames=flags, long_weights=final)
                actual, noise = gpu.run(guarded, target_dtype='f4', with_noise=True)
                expected, expected_noise = merge.cpu_reference(guarded, with_noise=True, **sampled)
                np.testing.assert_allclose(actual, expected, atol=.0015, rtol=.0002,
                                           err_msg=f'{c.name}, short={short}, flags={flags}')
                np.testing.assert_allclose(noise, expected_noise, atol=2e-7, rtol=.006,
                                           err_msg=f'{c.name}, short={short}, flags={flags}')
            rows.append({'name': c.name, 'flags': flags, 'clip_cpu_and_original_exact': True,
                         'first_weight_max_code_error': first_error,
                         'unorm_sampling_max_abs': sampling_error,
                         'second_weight_bytes_exact': True, 'guarded_merge_noise_short_modes': 4})
            print('PASS', c.name)
        report = {'status': 'PASS', 'device_verified': False,
                  'renderer': gpu.ctx.info['GL_RENDERER'], 'reference_commit': commit,
                  'production_shader_hashes': {name: hashlib.sha256(shader.encode()).hexdigest()
                                               for name, shader in sources().items()},
                  'cases': rows, 'limits': ['Desktop GL, not Mali driver parity.',
                  'Uses production guard + merge, not the complete RAW-to-JPEG renderer.',
                  'First-weight CPU comparison allows one UNORM code for hardware filtering/rounding.',
                  'UNORM sampling is independently bounded to one code, then measured coefficients feed the independent kernel/noise oracle.',
                  'Tile-anchor arithmetic is FP32 before the exact discontinuous 1.5px stencil boundary.']}
        args.out.write_text(json.dumps(report, indent=2)+'\n')
        print(f'PASS {len(rows)} guard fixtures, {len(rows)*4} guarded merge/noise comparisons')
    finally:
        runner.close()
        gpu.close()


if __name__ == '__main__':
    main()
