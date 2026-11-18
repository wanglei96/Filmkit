#!/usr/bin/env python3
"""Compare four calibrated same-RAW device replays, not the original preset JPEG."""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ROI = (1300, 1750, 2000, 2380)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    args = ap.parse_args()
    spec = importlib.util.spec_from_file_location('lamp', ROOT/'tools/analyze-lampshade-capture.py')
    lamp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lamp)
    report = {'scope': __doc__, 'roi_xyxy': ROI, 'variants': {}}
    stages, jpegs = {}, {}
    for variant in ('00', '10', '01', '11'):
        folder = args.directory/('real'+variant)
        token = next(folder.glob('edge_dump_*_D4-CCM.json')).name.split('_')[2]
        names = ['D4-CCM', 'film-src-pre-TEQ']
        if variant[0] == '1':
            names.append('D4-CS')
        if variant[1] == '1':
            names.append('D4-CM')
        stages[variant] = {}
        for name in names:
            raw, _ = lamp.stage(folder, token, name)
            x0, y0, x1, y1 = ROI
            stages[variant][name] = raw[::-1][y0:y1, x0:x1, :3].astype('f4')
        jpegs[variant] = Image.open(folder/'render.jpg').convert('RGB')
        assert jpegs[variant].size == (3072, 4080)
    columns, rows = lamp.edge_coordinates(stages['00']['D4-CCM'])
    # All variants use the baseline D4 arc; never re-detect a more favorable arc.
    for variant, images in stages.items():
        entry = {'stages': {}}
        for name, rgb in images.items():
            entry['stages'][name] = lamp.edge_metric(rgb, columns, rows)
        entry['d4_roi_exact_match'] = bool(np.array_equal(images['D4-CCM'], stages['00']['D4-CCM']))
        assert entry['d4_roi_exact_match'], 'Upstream input differs; comparison is not controlled'
        # Full-frame parity, in bounded row chunks, including alpha.
        base_folder = args.directory/'real00'
        base_token = next(base_folder.glob('edge_dump_*_D4-CCM.json')).name.split('_')[2]
        base, _ = lamp.stage(base_folder, base_token, 'D4-CCM')
        folder = args.directory/('real'+variant)
        token = next(folder.glob('edge_dump_*_D4-CCM.json')).name.split('_')[2]
        current, _ = lamp.stage(folder, token, 'D4-CCM')
        entry['d4_full_frame_exact_match'] = all(
            np.array_equal(base[y:y+128], current[y:y+128]) for y in range(0, base.shape[0], 128))
        assert entry['d4_full_frame_exact_match']
        jpeg = np.asarray(jpegs[variant].crop(ROI)).astype('f4')/255
        entry['jpeg_encoded_rgb_arc'] = lamp.edge_metric(jpeg, columns, rows)
        entry['jpeg_encoded_rgb_arc']['note'] = 'Encoded sRGB RGB surplus, not linear radiometry or perception score'
        final = images['film-src-pre-TEQ']
        ref = stages['00']['film-src-pre-TEQ']
        entry['final_linear_roi_change_fraction_gt_1e_minus4'] = float(np.mean(np.max(abs(final-ref), axis=-1) > 1e-4))
        entry['final_linear_roi_Y_abs_delta_p50_p95_max'] = np.quantile(
            abs((final-ref)@lamp.LUMA), [.5, .95, 1]).tolist()
        report['variants'][variant] = entry
    report['limitations'] = [
        'One static lamp scene, native default FilmParams, PC3D OFF and chroma_gate=0.',
        'No original private LUT/preset snapshot; this is not old-JPEG reproduction.',
        'Arc RGB surplus has no clean color truth and is not a perceptual quality score.',
        'Cannot establish fine-color safety, motion, broad scene coverage or steady-state performance.',
    ]
    args.directory.joinpath('comparison.json').write_text(json.dumps(report, indent=2)+'\n')
    labels = {'00': 'CS OFF / CM OFF', '10': 'CS ON / CM OFF',
              '01': 'CS OFF / CM ON', '11': 'CS ON / CM ON'}
    sheet = Image.new('RGB', (1400, 1320), '#222222')
    draw = ImageDraw.Draw(sheet)
    for i, variant in enumerate(labels):
        x, y = (i%2)*700, (i//2)*660
        draw.text((x+12, y+10), labels[variant]+' | final JPEG 1:1', fill='white')
        sheet.paste(jpegs[variant].crop(ROI), (x, y+30))
    sheet.save(args.directory/'lamp-jpeg-comparison.png')
    overview = Image.new('RGB', (1228, 1632), '#222222')
    for i, variant in enumerate(labels):
        thumb = jpegs[variant].resize((614, 816), Image.Resampling.LANCZOS)
        overview.paste(thumb, ((i%2)*614, (i//2)*816))
    overview.save(args.directory/'full-jpeg-comparison.jpg', quality=95)
    for variant, entry in report['variants'].items():
        print(variant, 'linear arc', entry['stages']['film-src-pre-TEQ']['magenta_positive_sum'],
              'JPEG arc', entry['jpeg_encoded_rgb_arc']['magenta_positive_sum'])


if __name__ == '__main__':
    main()
