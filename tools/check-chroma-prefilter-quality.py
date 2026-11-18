#!/usr/bin/env python3
"""Measure current optional prefilters against known noiseless colour controls.

This is not a noise-removal benchmark or a final-JPEG quality test. In particular,
a colour loss on a declared clean stripe cannot be credited as false-colour removal.
"""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
LUMA = np.array([.2627, .6780, .0593])


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def apply(draw, source, cs, cm, suffix=''):
    result = source.astype('f2').astype('f4')
    h, w = source.shape[:2]
    dummy = draw.texture(np.zeros_like(result))
    def images():
        return {'uTex': draw.texture(result, 'f2'), 'uVariance': dummy,
                'uCovariance': dummy}
    flags = {'uNoiseOn': 0}
    if cs:
        _, arrays = draw.run('kFsColorSmooth'+suffix, images(),
            {'uSize': (w, h), 'uGradLo': 6., 'uGradHi': 16., 'uSideK': .5,
             'uPeakK': 1.05, 'uChromaMin': .12, **flags}, (w, h))
        result = arrays[0].astype('f2').astype('f4')
    if cm:
        for _ in range(2):
            _, arrays = draw.run('kFsColorMedian'+suffix, images(),
                                {'uSize': (w, h), 'uMinChroma': .12, **flags}, (w, h))
            result = arrays[0].astype('f2').astype('f4')
    return result


def metric(source, result, subject):
    rgb = source[..., :3].astype('f2').astype('f4')
    out = result[..., :3]
    y0, y1 = rgb @ LUMA, out @ LUMA
    chroma0 = np.linalg.norm(rgb - y0[..., None], axis=-1)
    chroma1 = np.linalg.norm(out - y1[..., None], axis=-1)
    selected = subject & (chroma0 > .001)
    retention = chroma1[selected] / chroma0[selected]
    delta = np.max(np.abs(out - rgb), axis=-1)
    return {
        'subject_chroma_retention_min_p50_mean': [float(retention.min()),
            float(np.median(retention)), float(retention.mean())] if retention.size else None,
        'subject_colour_loss_over_10pct_fraction': float(np.mean(retention < .9)) if retention.size else 0.,
        'subject_bt2020_Y_abs_error_max': float(np.abs(y1 - y0)[subject].max()),
        'changed_fraction': float(np.mean(delta > .0001)),
        'alpha_preserved': bool(np.array_equal(source[..., 3].astype('f2'), result[..., 3])),
    }


def extended_controls(draw):
    """Separate stripe width, direction, luminance edge and HDR input range."""
    rows = []
    yy, xx = np.mgrid[:129, :129]
    interior = (xx >= 24) & (xx < 105) & (yy >= 24) & (yy < 105)
    colours = {'pink': (.8, .2, .65), 'red': (.8, .1, .08),
               'blue': (.06, .12, .8), 'green': (.1, .8, .12),
               'cyan': (.08, .6, .7)}
    for colour, rgb in colours.items():
        for angle in (0, 30, 45, 60, 90):
            theta = np.deg2rad(angle)
            distance = (xx - 64) * np.round(np.cos(theta), 12) + (yy - 64) * np.round(np.sin(theta), 12)
            for width in (1, 2, 4, 8, 16):
                stripe = (distance >= -width / 2) & (distance < width / 2)
                for background in ('flat', 'light-edge'):
                    image = np.zeros((129, 129, 4), 'f4')
                    base = np.where(distance >= 0, .9, .05) if background == 'light-edge' else np.full(distance.shape, .05)
                    image[..., :3] = base[..., None]
                    image[stripe, :3] = rgb
                    subject = stripe & interior
                    for cs, cm in ((True, False), (False, True), (True, True)):
                        result = apply(draw, image, cs, cm)
                        rows.append({'colour': colour, 'width': width, 'angle': angle,
                                     'background': background, 'cs': cs, 'cm': cm,
                                     **metric(image, result, subject)})
                        for resource in reversed(draw.resources):
                            resource.release()
                        draw.resources.clear()
    hdr = []
    for scale in (1., 8., 32., 64.):
        image = np.zeros((129, 129, 4), 'f4')
        image[..., :3] = .05 * scale
        image[:, 63:65, :3] = np.array(colours['pink']) * scale
        subject = interior & (xx >= 63) & (xx < 65)
        for cs, cm in ((True, False), (False, True), (True, True)):
            result = apply(draw, image, cs, cm)
            source = image.astype('f2').astype('f4')
            hdr.append({'scale': scale, 'cs': cs, 'cm': cm,
                        'input_rgb_max': float(source[..., :3].max()),
                        'output_rgb_max': float(result[..., :3].max()),
                        'subject_bt2020_Y_relative_error_max': float(np.max(np.abs(
                            (result[..., :3] @ LUMA)[subject] / (source[..., :3] @ LUMA)[subject] - 1))),
                        **metric(image, result, subject)})
            for resource in reversed(draw.resources):
                resource.release()
            draw.resources.clear()
    return rows, hdr


def domain_controls(draw):
    """Retain the historical domain-control label; repair is now production."""
    for name in ('kFsColorSmooth', 'kFsColorMedian'):
        original = draw.shaders[name]
        assert 'const vec3 LW = vec3(0.2627, 0.6780, 0.0593)' in original
        draw.shaders[name+'DomainCheck'] = original
    rows = []
    for width in (1, 2, 4, 8, 16):
        for scale in (1., 8., 32., 64.):
            image = np.zeros((129, 129, 4), 'f4')
            image[..., :3] = .05 * scale
            left = (129-width)//2
            image[:, left:left+width, :3] = np.array([.8, .2, .65])*scale
            subject = np.zeros((129, 129), bool)
            subject[24:-24, left:left+width] = True
            for cs, cm in ((True, False), (False, True), (True, True)):
                result = apply(draw, image, cs, cm, 'DomainCheck')
                source = image.astype('f2').astype('f4')
                rows.append({'width': width, 'scale': scale, 'cs': cs, 'cm': cm,
                             'subject_bt2020_Y_relative_error_max': float(np.max(np.abs(
                                 (result[..., :3]@LUMA)[subject]/(source[..., :3]@LUMA)[subject]-1))),
                             **metric(image, result, subject)})
                for resource in reversed(draw.resources):
                    resource.release()
                draw.resources.clear()
    return rows


def local_changes(source, result, selected):
    rgb = source[..., :3].astype('f2').astype('f4')
    out = result[..., :3]
    changed = np.max(np.abs(out-rgb), axis=-1) > .0001
    subject = selected & changed
    y0, y1 = rgb@LUMA, out@LUMA
    c0 = np.linalg.norm(rgb-y0[..., None], axis=-1)
    c1 = np.linalg.norm(out-y1[..., None], axis=-1)
    colored = subject & (c0 > .001)
    ret = c1[colored]/c0[colored]
    # Exhaustive fully interior windows; selected by changes, not a semantic ROI.
    inner = changed[14:-14, 14:-14]
    integral = np.pad(inner.astype('i4'), ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    sums = integral[32:, 32:]-integral[:-32, 32:]-integral[32:, :-32]+integral[:-32, :-32]
    top, left = np.unravel_index(np.argmax(sums), sums.shape)
    return {'selected_pixels': int(selected.sum()),
            'changed_fraction': float(changed[selected].mean()),
            'changed_colored_pixels': int(colored.sum()),
            'changed_chroma_retention_p05_p50_p95': np.quantile(ret, [.05, .5, .95]).tolist() if ret.size else None,
            'changed_bt2020_Y_abs_error_p50_p95_max': np.quantile(np.abs(y1-y0)[subject], [.5, .95, 1]).tolist() if subject.any() else None,
            'worst32_scan_ROI_local_xywh': [int(left+14), int(top+14), 32, 32],
            'worst32_scan_changed_fraction': float(sums[top, left]/1024),
            'warning': 'No clean truth; changes are not necessarily damage. Hotspot was selected post hoc.'}


def lamp_controls(draw, folder, token):
    lamp = load('prefilter_lamp', 'analyze-lampshade-capture.py')
    raw, _ = lamp.stage(folder, token, 'D4-CCM')
    # Same fixed coordinates/halo as the existing capture analysis, no resized stencils.
    image = raw[::-1][1750:2380, 1300:2000].astype('f4')
    cols, edges = lamp.edge_coordinates(image[..., :3])
    subject = np.zeros(image.shape[:2], bool)
    subject[edges[:, None]+np.arange(-8, 13), cols[:, None]] = True
    interior = np.zeros(image.shape[:2], bool)
    interior[14:-14, 14:-14] = True
    entries = []
    for suffix in ('', 'DomainCheck'):
        for cs, cm in ((True, False), (False, True), (True, True)):
            result = apply(draw, image, cs, cm, suffix)
            entries.append({'variant': suffix or 'production', 'cs': cs, 'cm': cm,
                            'edge': lamp.edge_metric(result, cols, edges),
                            'change_without_clean_truth': metric(image, result, subject),
                            'interior_changes': local_changes(image, result, interior),
                            'arc_changes': local_changes(image, result, subject)})
            for resource in reversed(draw.resources):
                resource.release()
            draw.resources.clear()
    return {'baseline': lamp.edge_metric(image, cols, edges), 'cases': entries,
            'input_alpha_nonzero_fraction': float(np.mean(image[..., 3] != 0)),
            'input_rgb_max': float(image[..., :3].max()),
            'warning': 'Actual captured D4 input, ROI only; no noise propagation, P4/D1, film, JPEG or device acceptance. '
                       'DomainCheck now aliases production. No independent saturation mask in this ROI test.'}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--stage-directory', type=Path)
    ap.add_argument('--extended', action='store_true', help='Also measure direction/edge controls and HDR range')
    ap.add_argument('--lamp-directory', type=Path, help='Captured lamp stages for offline domain comparison')
    ap.add_argument('--lamp-token')
    args = ap.parse_args()
    if bool(args.lamp_directory) != bool(args.lamp_token):
        ap.error('--lamp-directory and --lamp-token must be supplied together')
    args.output.mkdir(parents=True, exist_ok=True)
    post = load('prefilter_post', 'check-highlight-postprocess.py')
    gl = post.offline.GL(257, 259)
    draw = post.Draw(gl, post.offline.extract_shaders(), post.Check(), target_dtype='f2')
    rows = []
    try:
        for colour, rgb in {'pink': (.8, .2, .65), 'red': (.8, .1, .08),
                            'blue': (.06, .12, .8), 'green': (.1, .8, .12),
                            'cyan': (.08, .6, .7)}.items():
            for width in (1, 2, 4, 8, 16, 32):
                image = np.full((259, 257, 4), .05, 'f4')
                image[..., 3] = 0
                left = (257 - width) // 2
                image[:, left:left + width, :3] = rgb
                subject = np.zeros(image.shape[:2], bool)
                subject[24:-24, left:left + width] = True
                for cs, cm in ((True, False), (False, True), (True, True)):
                    result = apply(draw, image, cs, cm)
                    rows.append({'fixture': f'{colour}-{width}px', 'cs': cs, 'cm': cm,
                                 **metric(image, result, subject)})
                    for resource in reversed(draw.resources):
                        resource.release()
                    draw.resources.clear()
        extended, hdr = extended_controls(draw) if args.extended else ([], [])
        domain = domain_controls(draw) if args.extended or args.lamp_directory else []
        lamp = lamp_controls(draw, args.lamp_directory, args.lamp_token) if args.lamp_directory else None
        real = []
        if args.stage_directory:
            for path in sorted(args.stage_directory.glob('edge_dump_*_D4-CCM.json')):
                meta = json.loads(path.read_text())
                if not meta['complete']:
                    continue
                source = np.memmap(path.parent / Path(meta['raw_path']).name, dtype='<f2', mode='r',
                                   shape=(meta['h'], meta['w'], meta['components']))
                # Full-resolution filters on the full frame, not downscaled stencils.
                output = apply(draw, source, True, False)
                rgb = source[..., :3].astype('f4')
                y = rgb @ LUMA
                chroma = np.linalg.norm(rgb - y[..., None], axis=-1)
                subject = (chroma > .1 * np.maximum(y, .01)) & (y > .02)
                real.append({'token': meta['token'], 'scope': 'No clean truth; changes are not automatically damage or benefit.',
                             **metric(source, output, subject)})
                for label, image in (('source', rgb), ('cs', output[..., :3])):
                    mapped = np.maximum(image[::-1], 0)
                    mapped /= 1 + mapped
                    mapped = np.where(mapped <= .0031308, mapped * 12.92,
                                      1.055 * mapped ** (1 / 2.4) - .055)
                    thumb = Image.fromarray(np.uint8(np.clip(mapped, 0, 1) * 255))
                    thumb.thumbnail((600, 800))
                    thumb.save(args.output / f'{meta["token"]}-{label}.png')
                for resource in reversed(draw.resources):
                    resource.release()
                draw.resources.clear()
                print('full-frame', meta['token'], flush=True)
        report = {'gpu': gl.ctx.info.get('GL_RENDERER'), 'scope': __doc__,
                  'render_target': 'RGBA16F; f32 readback of actual half outputs',
                  'noiseless_controls': rows, 'real_frames': real,
                  'direction_edge_controls': extended, 'hdr_controls': hdr,
                  'offline_domain_controls': domain, 'captured_lamp_prefilters': lamp}
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        for row in rows:
            if row['cs'] and not row['cm']:
                print(row['fixture'], row['subject_chroma_retention_min_p50_mean'],
                      row['subject_bt2020_Y_abs_error_max'])
    finally:
        draw.close()
        gl.close()


if __name__ == '__main__':
    main()
