#!/usr/bin/env python3
"""Compare the frozen Print prototype with pinned upstream on shared DNG inputs."""
import argparse
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import time

import colour
import numpy as np
from PIL import Image, ImageCms, ImageDraw
import rawpy


TOOLS = Path(__file__).resolve().parent
study = runpy.run_path(str(TOOLS / 'filmkit-print-study.py'))
REV = runpy.run_path(str(TOOLS / 'spektrafilm-reference-smoke.py'))['UPSTREAM']


def stripe(fn, rows):
    def run(image, *args, **kw):
        if len(image) <= rows:
            return fn(image, *args, **kw)
        out = np.empty_like(image, dtype=np.float64)
        for i in range(0, len(image), rows):
            sl = slice(i, i+rows)
            out[sl] = fn(image[sl], *args, **kw)
        return out
    return run


def compare(reference, candidate):
    def lab(rgb):
        linear = colour.cctf_decoding(np.clip(rgb, 0, 1), function='sRGB')
        h, w = linear.shape[:2]
        pooled = linear.reshape(h//8, 8, w//8, 8, 3).mean(axis=(1, 3))
        return colour.XYZ_to_Lab(pooled @ study['SRGB'].T)

    pairs = [[], []]
    for i in range(0, len(reference), 64):
        for blocks, image in zip(pairs, (reference, candidate)):
            blocks.append(lab(image[i:i+64]))
    a, b = (np.concatenate(blocks).reshape(-1, 3) for blocks in pairs)
    ca, cb = (np.linalg.norm(x[:, 1:], axis=-1) for x in (a, b))
    de = colour.delta_E(a, b, method='CIE 2000')
    result = {}
    for name, mask in [('all', np.ones(len(a), bool)), ('shadow', a[:, 0]<25),
                       ('mid', (a[:, 0]>=25)&(a[:, 0]<75)), ('bright', a[:, 0]>=75),
                       ('colorful', ca>=20)]:
        if mask.any():
            result[name] = dict(blocks=int(mask.sum()), de00_p50_p95=np.percentile(de[mask], [50, 95]).tolist(),
                                delta_L_median=float(np.median((b-a)[mask, 0])),
                                delta_C_median=float(np.median((cb-ca)[mask])))
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('upstream', type=Path)
    p.add_argument('out', type=Path)
    p.add_argument('dng', type=Path, nargs='+')
    args = p.parse_args()
    def git(*a):
        return subprocess.check_output(['git', '-C', args.upstream, *a], text=True).strip()
    assert git('rev-parse', 'HEAD') == REV and not git('status', '--porcelain')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    from spektrafilm.runtime.stages import scanning

    scanning.compress_rgb = stripe(scanning.compress_rgb, 32)
    args.out.mkdir()
    icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()
    for path in args.dng:
        assert shutil.disk_usage(args.out).free >= 5 * 1024**3
        with rawpy.imread(str(path)) as raw:
            image = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD, use_camera_wb=True,
                no_auto_bright=True, adjust_maximum_thr=0., gamma=(1, 1), output_bps=16,
                output_color=rawpy.ColorSpace.Rec2020).astype(np.float64) / 65535.
        assert all(d % 8 == 0 for d in image.shape[:2])
        params = init_params(film_profile='kodak_portra_400')
        params.camera.auto_exposure = False
        params.io.input_color_space = 'ITU-R BT.2020'
        params.io.input_cctf_decoding = False
        params.io.scan_film = False
        params.settings.preview_mode = False
        params.debug.lut_mode = False
        params.settings.use_enlarger_lut = False
        params.settings.use_scanner_lut = False
        pipe = SimulationPipeline(digest_params(params))
        assert pipe.io.output_color_space == 'sRGB' and pipe.io.output_cctf_encoding
        assert pipe.film_render.grain.active and pipe.print_render.glare.active
        pr, sc = pipe._printing_stage, pipe._scanning_stage
        pr._film_cmy_to_print_log_raw = stripe(pr._film_cmy_to_print_log_raw, 16)
        sc.cmy_to_log_xyz = stripe(sc.cmy_to_log_xyz, 16)
        candidate = stripe(study['render'], 64)(image)
        tap, frame, elapsed = 'rgb_in', image, {}
        assert [n.writes[0] for n in pipe._topology] == [
            'rgb_pre', 'log_e_film', 'cmy_film', 'log_e_print', 'cmy_print', 'rgb_out']
        del image
        for node in pipe._topology:
            assert node.reads == (tap,) and len(node.writes) == 1
            tap = node.writes[0]
            print(path.stem, tap, flush=True)
            tick = time.monotonic()
            frame = node.run(frame)
            elapsed[tap] = time.monotonic()-tick
            assert np.isfinite(frame).all()
        assert frame.shape == candidate.shape
        result = dict(input_sha256=study['sha'](path), upstream=REV, paper=params.print.info.stock,
                      shape=list(frame.shape), seconds=elapsed, difference=compare(frame, candidate))
        board = Image.new('RGB', (1216, 702), '#181a1c')
        draw = ImageDraw.Draw(board)
        for i, (im, label) in enumerate(((candidate, 'FilmKit Print prototype'), (frame, 'Spektrafilm Portra 400 + Endura'))):
            photo = Image.fromarray(np.rint(np.clip(im, 0, 1)*255).astype(np.uint8))
            photo.thumbnail((590, 650), Image.Resampling.LANCZOS)
            x = i*608
            draw.text((x+12, 10), label, fill='white', font_size=21)
            board.paste(photo, (x+(608-photo.width)//2, 40))
        board.save(args.out / (path.stem+'.jpg'), quality=95, subsampling=0, icc_profile=icc)
        (args.out / (path.stem+'.json')).write_text(json.dumps(result, indent=2, allow_nan=False)+'\n')
        print(json.dumps(result), flush=True)
        del frame, candidate, pipe, pr, sc


if __name__ == '__main__':
    main()
