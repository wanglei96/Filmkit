#!/usr/bin/env python3
"""Inspect this GRBG/rotation=1 lamp capture; optionally replay production shaders.

Desktop replay is not device bit parity or an exact final-preset/JPEG replay.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile

import numpy as np
from PIL import Image, ImageDraw

LUMA = np.array([.2627, .6780, .0593])
ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def stage(folder, token, name):
    base = folder / f"edge_dump_{token}_{name}"
    meta = json.loads(base.with_suffix('.json').read_text())
    assert meta['complete'] and meta['token'] == token
    assert meta['stage']==name and meta['orientation']=='gl_bottom_up'
    assert meta['type'] in ('float16','uint8')
    dtype = '<f2' if meta['type'] == 'float16' else 'u1'
    path = folder / Path(meta['raw_path']).name
    expected_bytes = meta['h']*meta['w']*meta['components']*np.dtype(dtype).itemsize
    assert path.stat().st_size==expected_bytes, (name,'stage byte count differs',path.stat().st_size,expected_bytes)
    image = np.memmap(path, dtype=dtype,
                      mode='r', shape=(meta['h'], meta['w'], meta['components']))
    return image, meta


def capture_events(path, token):
    events = []
    for line in path.read_text().splitlines():
        try:
            events.append(json.loads(line))
        except ValueError:
            continue
    marker = f'edge_dump_{token}_'
    first = next(i for i, event in enumerate(events)
                 if event.get('event') == 'edge_dump.stage'
                 and marker in event.get('kv', {}).get('path', ''))
    start = max(i for i in range(first) if events[i].get('event') == 'raw.meta')
    stop = next((i for i in range(first + 1, len(events))
                 if events[i].get('event') == 'raw.meta'), len(events))
    return events[start:stop]


def clipping_levels(black, white):
    # HighlightRecovery.cpp::clippingLevels, inclusive native-code comparison.
    return np.ceil(np.asarray(black) + (white - np.asarray(black)) * (250 / 255)).astype(int)


def display(rgb):
    rgb = np.maximum(rgb, 0)
    rgb = rgb / (1 + rgb)
    return np.where(rgb <= .0031308, 12.92 * rgb,
                    1.055 * rgb ** (1 / 2.4) - .055)


def edge_coordinates(rgb):
    y = rgb @ LUMA
    # The top arc is isolated from curtains, sensor line defects and the lower arc.
    columns = np.arange(150, min(rgb.shape[1] - 150, 550))
    rows = np.argmax(np.diff(y[:300, columns], axis=0), axis=0)
    return columns, rows


def edge_metric(rgb, columns, rows):
    offsets = np.arange(-8, 13)
    samples = rgb[rows[:, None] + offsets, columns[:, None], :3]
    y = samples @ LUMA
    mag = np.minimum(samples[..., 0] - samples[..., 1],
                     samples[..., 2] - samples[..., 1])
    return {'magenta_peak_p50_p95_max': np.quantile(mag.max(axis=1), [.5, .95, 1]).tolist(),
            'magenta_positive_sum': float(np.maximum(mag, 0).sum()),
            'magenta_over_luma_p95': float(np.quantile(mag / np.maximum(y, .01), .95)),
            'profile_offsets': offsets.tolist(), 'mean_rgb_profile': samples.mean(axis=0).tolist()}


def reference_color_median(rgb, passes):
    """darktable basics.c::color_smoothing; frozen G, 3x3 median of R-G/B-G."""
    result = rgb.copy()
    for _ in range(passes):
        for channel in (0, 2):
            diff = result[..., channel] - result[..., 1]
            windows = np.lib.stride_tricks.sliding_window_view(diff, (3, 3))
            result[1:-1, 1:-1, channel] = np.maximum(
                np.median(windows, axis=(-2, -1)) + result[1:-1, 1:-1, 1], 0)
    return result


def shading_crop(lsc, raw_size, roi):
    """Captured rotation=1: RAW x=JPEG y, RAW y=raw_height-1-JPEG x."""
    w, h = raw_size
    x0, y0, x1, y1 = roi
    x, y = np.meshgrid(np.arange(x0, x1), np.arange(y0, y1))
    gx = ((y + .5) / w) * lsc.shape[1] - .5
    gy = ((h - x - .5) / h) * lsc.shape[0] - .5
    bx, by = np.floor(gx).astype(int), np.floor(gy).astype(int)
    tx, ty = (gx - bx)[..., None], (gy - by)[..., None]
    xlo, xhi = np.clip(bx, 0, lsc.shape[1] - 1), np.clip(bx + 1, 0, lsc.shape[1] - 1)
    ylo, yhi = np.clip(by, 0, lsc.shape[0] - 1), np.clip(by + 1, 0, lsc.shape[0] - 1)
    return ((lsc[ylo, xlo] * (1 - tx) + lsc[ylo, xhi] * tx) * (1 - ty) +
            (lsc[yhi, xlo] * (1 - tx) + lsc[yhi, xhi] * tx) * ty).astype('f4')


def raw_map_crop(array, raw_size, roi):
    """GL_LINEAR/CLAMP sampling at the corresponding rotation=1 RAW centres."""
    w, h = raw_size
    x0, y0, x1, y1 = roi
    x, y = np.meshgrid(np.arange(x0, x1), np.arange(y0, y1))
    gx = (y + .5) / w * array.shape[1] - .5
    gy = (h - x - .5) / h * array.shape[0] - .5
    bx, by = np.floor(gx).astype(int), np.floor(gy).astype(int)
    tx, ty = gx - bx, gy - by
    xlo, xhi = np.clip(bx, 0, array.shape[1] - 1), np.clip(bx + 1, 0, array.shape[1] - 1)
    ylo, yhi = np.clip(by, 0, array.shape[0] - 1), np.clip(by + 1, 0, array.shape[0] - 1)
    return ((array[ylo, xlo] * (1 - tx) + array[ylo, xhi] * tx) * (1 - ty) +
            (array[yhi, xlo] * (1 - tx) + array[yhi, xhi] * tx) * ty).astype('f4')


def replay(args, report, out, roi, columns, rows):
    """Run the captured P0 through unchanged production RCD on desktop GL."""
    offline = module('lamp_offline', 'engine-shader-offline.py')
    diagnostic = module('lamp_calibration', 'analyze-highlight-diagnostic.py')
    post = module('lamp_post', 'check-highlight-postprocess.py')
    import moderngl
    neutral, lsc = diagnostic.dng_calibration(args.directory / args.dng)
    p0, _ = stage(args.directory, args.token, 'mosaic-after-P0')
    captured, _ = stage(args.directory, args.token, 'D3-before-source-white')
    h, w = p0.shape[:2]
    dh, dw = captured.shape[:2]
    gl = offline.GL(w, h)
    shaders = offline.extract_shaders()
    stride2 = shaders['kFsRcdG']
    for dx, dy in ((0,-1),(0,1),(1,0),(-1,0)):
        old = f'lpfAt(p + ivec2({dx}, {dy}))'
        new = f'lpfAt(p + ivec2({dx*2}, {dy*2}))'
        assert stride2.count(old)==1, old
        stride2 = stride2.replace(old,new)
    shaders['kFsRcdGStride2'] = stride2
    source = (ROOT / 'core/engine/src/render/FrameRenderer.cpp').read_text()
    rot_vertex = re.search(r'const char\* kVertRot\s*=\s*R"\((.*?)\)";', source, re.S)[1]
    plain_vertex = gl.vs_src
    textures = []

    def texture(array, dtype='f2'):
        t = gl.ctx.texture((array.shape[1], array.shape[0]), array.shape[2],
                           np.ascontiguousarray(array).astype(dtype).tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST,) * 2
        t.repeat_x = t.repeat_y = False
        textures.append(t)
        return t

    def run(name, uniforms, inputs, size=None):
        program = gl.ctx.program(vertex_shader=gl.vs_src,
                                 fragment_shader=offline.to_core(shaders[name]))
        active = {key for key in program if isinstance(program[key], moderngl.Uniform)}
        program.release()
        assert not active - (set(uniforms) | set(inputs)), (name, active - (set(uniforms) | set(inputs)))
        return gl.run(shaders[name], uniforms, inputs, size=size)

    x0, y0, x1, y1 = roi
    results = {}
    gains = neutral[1] / neutral
    # This capture's neutral camera white times applied WB is exactly (1,1,1).
    # Do not silently apply that ceiling to another WB/camera-white contract.
    assert np.isclose(neutral[1], np.max(neutral)), 'non-neutral ceiling needs explicit CameraWhite'
    shading_roi = shading_crop(lsc, (w, h), roi)
    before = captured[::-1][y0:y1, x0:x1, :3].astype('f4')
    after, _ = stage(args.directory, args.token, 'D3-after-source-white')
    after = after[::-1][y0:y1, x0:x1, :3].astype('f4')
    # Only changed pixels identify the resolve ceiling. Unchanged pixels do NOT
    # reveal the selected-short mask; comparisons there are intentionally unbounded.
    known = np.any(before > after, axis=-1)
    ceiling = np.max(after / shading_roi, axis=-1, keepdims=True) * shading_roi
    ceiling[~known] = np.inf
    resolve_name = 'partially_inferred_resolve_D4'
    if args.cpu_replay:
        metadata = json.loads((args.cpu_replay / 'metadata.json').read_text())
        assert (metadata['w'], metadata['h'], metadata['cfa_lut']) == (w, h, 97)
        assert metadata['accepted'] and metadata['short_index'] == 3
        assert np.max(np.abs(np.array(metadata['wb']) - gains)) < .0002
        mask = np.fromfile(args.cpu_replay / 'mask-quarter.r8', 'u1').reshape(
            metadata['mask_h'], metadata['mask_w']).astype('f4') / 255
        share = raw_map_crop(mask, (w, h), roi)
        scale = metadata['scales'][0] + share * (metadata['scales'][3] - metadata['scales'][0])
        ceiling = shading_roi * scale[..., None]
        resolved = np.minimum(before, ceiling).astype('f2').astype('f4')
        delta = np.abs(resolved - after)
        report['reconstructed_mask_resolve'] = {
            'D3_abs_error_mean_p99_max': [float(delta.mean()), *np.quantile(delta, [.99, 1]).tolist()],
            'cpu_metadata': metadata,
            'scope': 'Full production CPU guides/geometry/mask from desktop production GPU pyramids. '
                     'Not captured device mask; validate D3 error, not device bit parity.'}
        resolve_name = 'reconstructed_mask_resolve_D4'
        np.save(out / 'reconstructed-short-mask-roi.npy', share)
    ccm = np.asarray(report['ccm'], 'f4')

    def record(name, crop, native=None):
        local_columns, local_rows = (columns, rows) if native is None else edge_coordinates(crop)
        bound = ceiling if native is None else shading_roi
        inferred = np.minimum(crop, bound).astype('f2').astype('f4')
        d4 = np.clip(inferred @ ccm.T, 0, 64).astype('f2').astype('f4')
        scope = resolve_name if native is None else 'single_frame_neutral_ceiling_D4'
        entry = {'edge': edge_metric(crop, local_columns, local_rows),
                 scope: edge_metric(d4, local_columns, local_rows)}
        if native is not None:
            raw_x = y0 + local_rows[:, None] + np.arange(-8, 13)
            raw_y = np.broadcast_to((h - 1 - x0 - local_columns)[:, None], raw_x.shape)
            values = native[raw_y, raw_x]
            phase = (raw_y & 1) * 2 + (raw_x & 1)
            entry['native_clipping_by_phase'] = {
                str(i): [float(np.mean(values[:, j][phase[:, j] == i] >= report['native_clip_levels'][i]))
                         for j in range(values.shape[1])] for i in range(4)}
            entry['scope'] = ('Unaligned single RAW, no fusion/P0; its own strongest top-arc edge. '
                              'Own exposure units, NOT an exact burst/output comparison.')
            np.save(out / f'replay-{name}-edge-rows.npy', local_rows)
        suffix = '-reconstructed-resolve-D4' if args.cpu_replay and native is None else '-partial-resolve-D4'
        if native is not None:
            suffix = '-single-frame-ceiling-D4'
        for suffix, array in (('', crop), (suffix, d4)):
            np.save(out / f'replay-{name}{suffix}.npy', array)
            Image.fromarray(np.uint8(np.clip(display(array), 0, 1) * 255)).save(
                out / f'replay-{name}{suffix}.png')
        results[name] = entry
        return entry

    offsets = np.arange(-8, 13)
    if not args.cpu_replay:
        report['partial_resolve_contract'] = {
        'scope': 'Ceiling inferred only where captured resolve changed a pixel. '
                 'No bound at unchanged pixels; NOT an exact alternate-branch resolve replay.',
        'known_pixel_fraction_by_edge_offset': known[rows[:, None] + offsets, columns[:, None]].mean(axis=0).tolist(),
        'captured_before_to_after_abs_error': float(np.abs(np.minimum(before, ceiling) - after).max()),
        }
    try:
        mos = texture(p0)
        shading = texture(lsc)
        raw_dummy = texture(np.zeros((1, 1, 1), 'u2'), 'u2')
        dummy = texture(np.zeros((1, 1, 1), 'f4'))
        common = {'uSize': (w, h), 'uCfaLut': 97, 'uBlack': (64.,) * 4,
                  'uWhite': 1023., 'uGain': tuple(neutral[1] / neutral),
                  'uMosaic': 1., 'uCdFix': 1.}
        direction = run('kFsRcdDir', common, {'uRaw': raw_dummy, 'uMos': mos})
        direct = texture(direction)
        for name, original, split, clip, pre_p0 in (
                ('captured-settings', 0., False, gains, None),
                ('original-green', 1., False, gains, None),
                ('green-lpf-stride2', 0., False, gains, None),
                ('original-green-lpf-stride2', 1., False, gains, None),
                ('split-chroma', 1., True, gains, None),
                ('split-chroma-unbounded', 1., True, (65504.,) * 3, None),
                ('before-P0', 0., False, gains, 'before'),
                ('native-reference', 0., False, gains, 0),
                ('native-short', 0., False, gains, 3)):
            gl.vs_src = plain_vertex
            start = len(textures)
            mosaic, field = mos, direct
            native = None
            if pre_p0 is not None:
                if pre_p0 == 'before':
                    mono, _ = stage(args.directory, args.token, 'mosaic-before-P0')
                else:
                    meta = json.loads((args.directory / 'diagnostic-burst' / f'f{pre_p0}.meta').read_text())
                    assert meta['w'] == w and meta['h'] == h
                    native = np.memmap(args.directory / 'diagnostic-burst' / f'f{pre_p0}.raw',
                                       dtype='<u2', mode='r', shape=(h, meta['stridePx']))[:, :w]
                    mono = native[..., None]
                sparse = np.zeros(p0.shape, 'f2')
                for py, px, channel in ((0, 0, 1), (0, 1, 0), (1, 0, 2), (1, 1, 1)):
                    values = mono[py::2, px::2, 0]
                    if native is not None:
                        values = np.maximum(values.astype('f4') - 64, 0) / 959 * gains[channel]
                    sparse[py::2, px::2, channel] = values
                mosaic = texture(sparse)
                del sparse
                field = texture(run('kFsRcdDir', common, {'uRaw': raw_dummy, 'uMos': mosaic}))
            uniforms = {**common, 'uGOrig': original, 'uSatClip': tuple(clip)}
            green_shader = 'kFsRcdGStride2' if 'lpf-stride2' in name else 'kFsRcdG'
            green = run(green_shader, uniforms,
                           {'uRaw': raw_dummy, 'uMos': mosaic, 'uDir': field})
            green_tex = texture(green)
            inputs = {'uRaw': raw_dummy, 'uMos': mosaic, 'uDir': field, 'uG': green_tex,
                      'uLsc': shading, 'uHlMask': dummy}
            if split:
                intermediate = run('kFsRcdChromaA', uniforms, inputs)
                inputs['uRcdA'] = texture(intermediate)
            gl.vs_src = offline.to_core(rot_vertex)
            rgb = run('kFsRcdChromaB' if split else 'kFsRcdChroma', {
                **uniforms, 'uRot': 1, 'uScale': (1., 1.), 'uST': tuple(np.eye(4).ravel()),
                'uLscSize': (lsc.shape[1], lsc.shape[0]), 'uMaskOn': 0,
            }, inputs, size=(dw, dh))
            crop = rgb[::-1, :][y0:y1, x0:x1, :3]
            entry = record(name, crop, native)
            if name == 'captured-settings':
                expected = captured[::-1][y0:y1, x0:x1, :3].astype('f4')
                delta = np.abs(crop - expected)
                entry['captured_D3_error'] = {'mean': float(delta.mean()),
                                             'p99_max': np.quantile(delta, [.99, 1]).tolist()}
            print('completed', name, entry.get('captured_D3_error', ''), flush=True)
            for t in textures[start:]:
                t.release()
            del textures[start:]
        if args.amaze_binary:
            # Match runAmazeFromMosaic: undo WB, complete CPU AMaZE at clip=1,
            # restore WB and LSC, half upload. Not the older max(WB) interface.
            with tempfile.TemporaryDirectory(prefix='lamp-amaze-') as directory:
                input_path, output_path = Path(directory) / 'cfa.bin', Path(directory) / 'rgb.f32'
                mono = np.zeros((h, w), 'f4')
                for py, px, channel in ((0, 0, 1), (0, 1, 0), (1, 0, 2), (1, 1, 1)):
                    mono[py::2, px::2] = p0[py::2, px::2, channel].astype('f4') / gains[channel]
                with input_path.open('wb') as stream:
                    stream.write(struct.pack('<6if', w, h, 1, 0, 2, 1, 1.))
                    mono.tofile(stream)
                del mono
                subprocess.run([str(args.amaze_binary.resolve()), str(input_path), str(output_path)], check=True)
                result = np.memmap(output_path, dtype='<f4', mode='r', shape=(h, w, 3))
                crop = np.rot90(result, -1)[y0:y1, x0:x1].copy()
                crop = (crop * gains * shading_roi).astype('f2').astype('f4')
                record('amaze-current', crop)
                del result
        report['rcd_replay'] = {'gpu': gl.ctx.info.get('GL_RENDERER'), 'results': results,
                                'scope': 'Captured mosaic -> demosaic; no RAW fusion or film replay. '
                                         'Resolve uses validated reconstructed CPU mask when supplied; '
                                         'otherwise only the explicitly bounded partial inference.'}
        # Independent prefilter combinations on the actual captured D4 input.
        gl.vs_src = plain_vertex
        draw = post.Draw(gl, shaders, post.Check())
        raw, _ = stage(args.directory, args.token, 'D4-CCM')
        # All stencil supports fit the crop; only the interior top arc is measured.
        image = raw[::-1][y0:y1, x0:x1].astype('f4')
        pre = {}
        try:
            for cs, cm in ((False, False), (True, False), (False, True), (True, True)):
                result = image.copy()
                hh, ww = result.shape[:2]
                if cs:
                    _, arrays = draw.run('kFsColorSmooth', {'uTex': draw.texture(result, 'f2')},
                        {'uSize': (ww, hh), 'uGradLo': 6., 'uGradHi': 16., 'uSideK': .5,
                         'uPeakK': 1.05, 'uChromaMin': .12}, (ww, hh))
                    result = arrays[0].astype('f2').astype('f4')
                if cm:
                    for _ in range(2):
                        _, arrays = draw.run('kFsColorMedian', {'uTex': draw.texture(result, 'f2')},
                            {'uSize': (ww, hh), 'uMinChroma': .12}, (ww, hh))
                        result = arrays[0].astype('f2').astype('f4')
                name = f'cs{int(cs)}-cm{int(cm)}'
                pre[name] = edge_metric(result, columns, rows)
                np.save(out / f'{name}.npy', result)
                Image.fromarray(np.uint8(np.clip(display(result[..., :3]), 0, 1) * 255)).save(
                    out / f'{name}.png')
                for t in reversed(draw.resources):
                    t.release()
                draw.resources.clear()
            report['captured_D4_prefilters'] = pre
        finally:
            draw.close()
    finally:
        for t in textures:
            t.release()
        gl.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('--token', required=True)
    p.add_argument('--jpg', required=True)
    p.add_argument('--dng')
    p.add_argument('--replay', action='store_true')
    p.add_argument('--amaze-binary', type=Path)
    p.add_argument('--cpu-replay', type=Path, help='Production CPU replay directory containing mask-quarter.r8')
    p.add_argument('--roi', default='1300,1750,2000,2380', help='JPEG x0,y0,x1,y1')
    args = p.parse_args()
    if args.replay and not args.dng:
        p.error('--replay requires --dng')
    if (args.amaze_binary or args.cpu_replay) and not args.replay:
        p.error('--amaze-binary/--cpu-replay require --replay')
    out = args.directory / 'stage-analysis'
    out.mkdir(exist_ok=True)
    x0, y0, x1, y1 = map(int, args.roi.split(','))
    jpeg = np.asarray(Image.open(args.directory / args.jpg).convert('RGB'))
    assert 0 <= x0 < x1 <= jpeg.shape[1] and 0 <= y0 < y1 <= jpeg.shape[0], 'ROI outside JPEG'
    sdr, _ = stage(args.directory, args.token, 'sdr-final')
    # Pick the actual output orientation by pixel error, not an assumed GL flip.
    transforms = {'identity': lambda a: a, 'flip-y': lambda a: a[::-1],
                  'flip-x': lambda a: a[:, ::-1], 'flip-xy': lambda a: a[::-1, ::-1]}
    errors = {k: float(np.mean(np.abs(f(sdr)[::8, ::8, :3].astype('f4') -
                                            jpeg[::8, ::8].astype('f4'))))
              for k, f in transforms.items()}
    sdr_transform = min(errors, key=errors.get)
    # passOutput flips Y relative to the D4/film working texture (source contract).
    a_transform = transforms['flip-y'] if sdr_transform == 'identity' else transforms['identity']
    assert sdr_transform in ('identity', 'flip-y'), errors
    names = ['D3-before-source-white', 'D3-after-source-white', 'D4-CCM',
             'film-src-pre-TEQ', 'film-out']
    report = {'orientation_errors_u8': errors, 'sdr_transform': sdr_transform,
              'roi_jpeg': [x0, y0, x1, y1], 'stages': {}}
    d4, _ = stage(args.directory, args.token, 'D4-CCM')
    columns, rows = edge_coordinates(a_transform(d4)[y0:y1, x0:x1, :3].astype('f4'))
    events = capture_events(args.directory / 'capture.log', args.token)
    meta = next(e['kv'] for e in events if e['event'] == 'raw.meta')
    assert meta['cfa'] == 1 and meta['in'] == '4080x3072', 'capture-specific GRBG geometry'
    report['capture_timestamp'] = events[0]['ts']
    report['native_clip_levels'] = clipping_levels(
        list(map(float, meta['black'].split(','))), meta['white']).tolist()
    ccm = np.array(list(map(float, meta['ccmStd'].split(','))), 'f4').reshape(3, 3)
    report['ccm'] = ccm.tolist()
    camera, _ = stage(args.directory, args.token, 'D3-after-source-white')
    camera_crop = a_transform(camera)[y0:y1, x0:x1, :3].astype('f4')
    reference = {}
    for count in (0, 1, 2):
        corrected = reference_color_median(camera_crop, count)
        output = np.clip(corrected.astype('f2').astype('f4') @ ccm.T, 0, 64)
        output = output.astype('f2').astype('f4')
        reference[str(count)] = edge_metric(output, columns, rows)
        np.save(out / f'reference-camera-median-{count}.npy', output)
        Image.fromarray(np.uint8(np.clip(display(output), 0, 1) * 255)).save(
            out / f'reference-camera-median-{count}.png')
    report['reference_camera_median'] = reference
    images = []
    for name in names:
        raw, _ = stage(args.directory, args.token, name)
        rgb = a_transform(raw)[y0:y1, x0:x1, :3].astype('f4')
        np.save(out / f'{name}-roi.npy', rgb)
        y = rgb @ LUMA
        magenta = np.minimum(rgb[..., 0] - rgb[..., 1], rgb[..., 2] - rgb[..., 1])
        report['stages'][name] = {'range': [float(rgb.min()), float(rgb.max())],
                                 'luma_quantiles': np.quantile(y, [.01, .5, .99]).tolist(),
                                 'magenta_quantiles': np.quantile(magenta, [.01, .5, .99, 1]).tolist()}
        report['stages'][name]['edge'] = edge_metric(rgb, columns, rows)
        # These are fixed diagnostic tone views, not the production film output.
        view = Image.fromarray(np.uint8(np.clip(display(rgb), 0, 1) * 255))
        view.save(out / f'{name}-roi.png')
        images.append((name, view))
    jpeg_roi = jpeg[y0:y1, x0:x1]
    Image.fromarray(jpeg_roi).save(out / 'jpeg-roi.png')
    images.append(('JPEG', Image.fromarray(jpeg_roi)))
    width, height = x1 - x0, y1 - y0
    montage = Image.new('RGB', (width * 3, (height + 28) * 2), '#222222')
    draw = ImageDraw.Draw(montage)
    for index, (name, view) in enumerate(images):
        x, y = (index % 3) * width, (index // 3) * (height + 28)
        montage.paste(view, (x, y + 28))
        draw.text((x + 5, y + 5), name, fill='white')
    montage.save(out / 'stage-montage.png')
    if args.replay:
        assert args.dng, '--replay needs --dng for captured LSC'
        replay(args, report, out, (x0, y0, x1, y1), columns, rows)
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in (
        'stages', 'rcd_replay', 'captured_D4_prefilters', 'reference_camera_median',
        'reconstructed_mask_resolve')}, indent=2))
    if 'reconstructed_mask_resolve' in report:
        print('reconstructed_mask_resolve', report['reconstructed_mask_resolve']['D3_abs_error_mean_p99_max'])
    for group in ('stages', 'captured_D4_prefilters', 'reference_camera_median'):
        for name, entry in report.get(group, {}).items():
            metric = entry.get('edge', entry)
            print(group, name, json.dumps({k: v for k, v in metric.items() if not k.startswith(('profile_', 'mean_rgb'))}))


if __name__ == '__main__':
    main()
