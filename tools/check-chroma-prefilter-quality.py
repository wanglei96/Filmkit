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


def apply(draw, source, cs, cm):
    result = source.astype('f2').astype('f4')
    h, w = source.shape[:2]
    if cs:
        _, arrays = draw.run('kFsColorSmooth', {'uTex': draw.texture(result, 'f2')},
            {'uSize': (w, h), 'uGradLo': 6., 'uGradHi': 16., 'uSideK': .5,
             'uPeakK': 1.05, 'uChromaMin': .12}, (w, h))
        result = arrays[0].astype('f2').astype('f4')
    if cm:
        for _ in range(2):
            _, arrays = draw.run('kFsColorMedian', {'uTex': draw.texture(result, 'f2')},
                                {'uSize': (w, h), 'uMinChroma': .12}, (w, h))
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


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--stage-directory', type=Path)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    post = load('prefilter_post', 'check-highlight-postprocess.py')
    gl = post.offline.GL(257, 259)
    draw = post.Draw(gl, post.offline.extract_shaders(), post.Check())
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
                  'noiseless_controls': rows, 'real_frames': real}
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
