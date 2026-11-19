#!/usr/bin/env python3
"""Tone-only A/B on one shared DNG decode; camera JPEGs are context only."""

import argparse
import importlib.util
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageDraw
import rawpy


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


study = load_tool('filmkit-print-study')
sig = load_tool('sigmoid-params-ref')
DEFAULT = sig.compute_sigmoid_params(1.5, 0, 100, .0152, 100)


def sigmoid_tone(rgb):
    # Port the default (100% hue preservation) sigmoidTone from FrameRenderer.
    rgb = np.asarray(rgb, dtype=np.float32)
    avg = np.maximum(rgb.mean(axis=-1, keepdims=True), 0)
    lo = rgb.min(axis=-1, keepdims=True)
    scale = np.divide(avg, avg-lo, out=np.ones_like(avg), where=lo < 0)
    rgb = avg + scale*(rgb-avg)
    order = np.argsort(rgb, axis=-1, stable=True)
    lo, mid, hi = np.moveaxis(np.take_along_axis(rgb, order, axis=-1), -1, 0)
    p, q, r = np.moveaxis(sig.sigmoid_curve(np.stack((lo, mid, hi), axis=-1), DEFAULT), -1, 0)
    t = np.divide(mid-lo, hi-lo, out=np.zeros_like(lo), where=hi != lo)
    naive = p+(r-p)*t
    blend = np.divide(2*lo, lo+mid, out=np.zeros_like(lo), where=(lo+mid) != 0)
    energy = blend*(p+q+r)+(1-blend)*(p+naive+r)
    lower = naive <= q
    corrected = np.where(lower, (t*r+(1-t)*(energy-r))/(2-t),
                         (p*(1-t)+t*(energy-p))/(1+t))
    sorted_out = np.stack((np.where(lower, energy-r-corrected, p), corrected,
                           np.where(lower, r, energy-p-corrected)), axis=-1)
    return np.take_along_axis(sorted_out, np.argsort(order, axis=-1), axis=-1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    parser.add_argument('files', nargs='+', type=Path, help='alternating DNG and its camera JPEG')
    args = parser.parse_args()
    if len(args.files) % 2 or any(a.suffix.lower() != '.dng' or b.suffix.lower() not in ('.jpg', '.jpeg')
                                  for a, b in zip(args.files[::2], args.files[1::2])):
        parser.error('pass explicit DNG/JPEG pairs')
    args.out.mkdir()
    study.self_test()
    ramp = np.repeat(np.linspace(0, 1, 256)[:, None], 3, axis=1)
    np.testing.assert_allclose(sigmoid_tone(ramp), sig.sigmoid_curve(ramp, DEFAULT), atol=3e-7)
    srgb = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB'))
    report = []
    for dng, jpg in zip(args.files[::2], args.files[1::2]):
        print(dng.name, jpg.name, flush=True)
        with rawpy.imread(str(dng)) as raw:
            flip = raw.sizes.flip
            linear = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                                     use_camera_wb=True, no_auto_bright=True,
                                     adjust_maximum_thr=0.0, gamma=(1, 1),
                                     output_bps=16, output_color=rawpy.ColorSpace.Rec2020)
        h, w = linear.shape[:2]
        with Image.open(jpg) as camera:
            if camera.size != (w, h) or camera.getexif().get(274, 1) != 1:
                raise ValueError(f'camera JPEG orientation/dimensions differ: {jpg}')
            camera = camera.convert('RGB')
            profile = camera.info.get('icc_profile')
            if profile:
                camera = ImageCms.profileToProfile(camera, ImageCms.ImageCmsProfile(io.BytesIO(profile)), srgb,
                                                    outputMode='RGB')
        images = np.empty((2, h, w, 3), dtype=np.uint8)
        mean_y = np.zeros(2)
        high_y = np.zeros(2)
        high_count = 0
        at_white = 0
        for i in range(0, h, 64):
            sl = slice(i, i+64)
            block = linear[sl] / 65535.0
            at_white += int((linear[sl].max(axis=-1) == 65535).sum())
            high = block @ study.REC2020[1] >= .60
            high_count += int(high.sum())
            a = study.output_gamut(sigmoid_tone(block) @ study.TO_SRGB.T)
            b = study.output_gamut(study.paper_reflectance(study.film_density(block)) @ study.TO_SRGB.T)
            for k, view in enumerate((a, b)):
                y = view @ study.SRGB[1]
                mean_y[k] += y.sum()
                high_y[k] += y[high].sum()
                images[k, sl] = np.rint(sig.srgb_oetf_8bit(view)).astype(np.uint8)
        board = Image.new('RGB', (1468, 654 if h > w else 416), '#181a1c')
        for k, (view, label) in enumerate(zip((Image.fromarray(images[0]), Image.fromarray(images[1]), camera),
                ('Same DNG: sigmoid tone', 'Same DNG: FilmKit Print', 'Camera JPEG (reference)'))):
            view.thumbnail((476, 600), Image.Resampling.LANCZOS)
            x = 12 + 488*k
            ImageDraw.Draw(board).text((x, 12), label, fill='white', font_size=19)
            board.paste(view, (x+(476-view.width)//2, 40))
        board.save(args.out / (dng.stem + '.jpg'), quality=93, subsampling=0, icc_profile=srgb.tobytes())
        report.append(dict(dng=dng.name, camera_jpeg=jpg.name, dng_sha256=study.sha(dng),
                           jpeg_sha256=study.sha(jpg), size=[w, h], libraw_flip=flip,
                           jpeg_embedded_icc=bool(profile), decoded_pixels_with_max_channel_65535=at_white,
                           mean_display_y=(mean_y/(h*w)).tolist(), bright_input_pixels=high_count,
                           bright_input_mean_display_y=(high_y/high_count).tolist() if high_count else None))
    (args.out / 'manifest.json').write_text(json.dumps(dict(rawpy=rawpy.__version__,
        libraw=rawpy.libraw_version, comparator_order=['sigmoid', 'print'],
        decode='AHD, as-shot WB, no auto brighten, linear Rec.2020 uint16', pairs=report), indent=2) + '\n')


if __name__ == '__main__':
    main()
