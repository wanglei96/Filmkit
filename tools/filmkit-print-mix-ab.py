#!/usr/bin/env python3
"""Isolate the two Print mixing matrices with a FilmKit Filmic gray anchor."""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageDraw
import rawpy
from runpy import run_path


def tool(name):
    return run_path(str(Path(__file__).with_name(name + '.py')))


study = tool('filmkit-print-study')
filmic = tool('check-film-offline')
spline = tool('filmic-spline-ref')['compute_filmic_spline'](-7.65, 4.56, 1., [.01]*3, [.01]*3)
tone = filmic['cpu_filmic_tone']
identity = np.eye(3)
cases = [(identity, identity), (study['FILM'], identity),
         (identity, study['DYE']), (study['FILM'], study['DYE'])]
labels = ['FilmKit Filmic tone', 'Print: no mixing', 'Print: negative only',
          'Print: paper only', 'Print: both mixing stages']


def match_gray(rgb):
    # Invert the neutral Print response analytically at the Filmic target.
    y = tone(np.repeat(rgb[..., None], 3, axis=-1), spline)[..., 1]
    log_transmission = np.log1p(-y) - np.log1p(study['PAPER_PIVOT'] * y)
    return study['TOE'] * np.expm1(-log_transmission / (study['FILM_GAMMA'] * study['PAPER_GAMMA']))


def print_linear(rgb, film, paper):
    return study['paper_reflectance'](study['film_density'](rgb, film), paper)


def display(rgb):
    return study['output_gamut'](rgb @ study['TO_SRGB'].T)


def chroma(rgb):
    xyz = rgb @ study['SRGB'].T / study['SRGB'].sum(axis=1)
    delta = 6/29
    f = np.where(xyz > delta**3, np.cbrt(xyz), xyz/(3*delta**2)+4/29)
    return np.hypot(500*(f[..., 0]-f[..., 1]), 200*(f[..., 1]-f[..., 2]))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('out', type=Path)
    p.add_argument('dng', type=Path, nargs='+')
    args = p.parse_args()
    study['self_test']()
    grid = np.arange(65536)/65535.
    lookup = match_gray(grid)
    assert np.isfinite(lookup).all() and np.all(np.diff(lookup) >= -1e-12)
    neutral = np.repeat(grid[:, None], 3, axis=-1)
    target = display(tone(neutral, spline))
    err = max(float(np.max(np.abs(display(print_linear(np.repeat(lookup[:, None], 3, axis=-1), *pair)) - target)))
              for pair in cases)
    assert err < 1e-12
    print('neutral max error', err, flush=True)
    args.out.mkdir()
    report = []
    icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    for path in args.dng:
        with rawpy.imread(str(path)) as raw:
            source = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                use_camera_wb=True, no_auto_bright=True, adjust_maximum_thr=0.0,
                gamma=(1, 1), output_bps=16, output_color=rawpy.ColorSpace.Rec2020)
        h, w = source.shape[:2]
        images = np.empty((len(labels), h, w, 3), dtype=np.uint8)
        sums = np.zeros(len(labels))
        count = 0
        for i in range(0, h, 64):
            sl = slice(i, i+64)
            rgb = source[sl]
            calibrated = lookup[rgb]
            versions = [display(tone(rgb/65535., spline))]
            versions += [display(print_linear(calibrated, *pair)) for pair in cases]
            mask = chroma(versions[1]) >= 20
            count += int(mask.sum())
            for j, view in enumerate(versions):
                sums[j] += chroma(view[mask]).sum()
                images[j, sl] = np.rint(filmic_encode(view) * 255).astype(np.uint8)
        cell_w, cell_h = (324, 450) if h > w else (324, 270)
        board = Image.new('RGB', (cell_w*len(labels), cell_h), '#181a1c')
        draw = ImageDraw.Draw(board)
        for j, label in enumerate(labels):
            draw.text((j*cell_w+8, 9), label, fill='white', font_size=17)
            view = Image.fromarray(images[j])
            view.thumbnail((cell_w-16, cell_h-38), Image.Resampling.LANCZOS)
            board.paste(view, (j*cell_w+(cell_w-view.width)//2, 33))
        board.save(args.out / (path.stem + '.jpg'), quality=94, subsampling=0, icc_profile=icc)
        means = (sums/count).tolist() if count else None
        row = dict(dng=path.name, sha256=study['sha'](path), size=[w, h], mask_pixels=count,
                   mean_lab_chroma=means)
        report.append(row)
        print(json.dumps(row), flush=True)
    (args.out/'manifest.json').write_text(json.dumps(dict(labels=labels, neutral_max_error=err,
        chroma_mask='Print no mixing display CIELAB C*ab >= 20', pairs=report), indent=2)+'\n')


def filmic_encode(rgb):
    rgb = np.clip(rgb, 0, 1)
    return np.where(rgb <= .0031308, 12.92*rgb, 1.055*rgb**(1/2.4)-.055)


if __name__ == '__main__':
    main()
