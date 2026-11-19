#!/usr/bin/env python3
"""Isolated Portra grain workset experiment, not an app backend."""
import argparse
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
from time import perf_counter
from unittest.mock import patch

import numpy as np


def stream_layers(model, density, pixel_um, params, curves, layers, fast_stats):
    maxima = np.nanmax(layers, axis=0)
    fractions = maxima / maxima.sum(axis=0)
    floors = fractions * np.asarray(params.density_min)
    maxima += floors
    area = (params.particle_area_um2 * np.asarray(params.particle_scale)[None, :]
            * np.asarray(params.particle_scale_layers)[:, None])
    particles = pixel_um**2 * fractions / area
    out = np.zeros_like(density)
    # Keep one channel's three interpolation planes live, not a nine-plane frame.
    for ch in range(3):
        exposures = np.repeat(density[..., ch, None], 3, axis=-1)
        channel = model.interp_density_cmy_layers.__globals__['fast_interp'](
            exposures, curves[:, ch], layers[:, :, ch])
        del exposures
        channel += floors[:, ch]
        for layer in range(3):
            out[..., ch] += model.layer_particle_model(
                channel[..., layer], density_max=maxima[layer, ch],
                n_particles_per_pixel=particles[layer, ch],
                grain_uniformity=params.uniformity[ch], seed=ch + 10*layer,
                blur_particle=params.blur_dye_clouds_um, use_fast_stats=fast_stats)
        del channel
    model.add_micro_structure(out, params.micro_structure, pixel_um)
    out -= params.density_min
    if params.blur > 0:
        out = model.fast_gaussian_filter(out, params.blur)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument('--d0', type=Path, help='Run the full-resolution print pipeline A/B')
    inputs.add_argument('--dng', type=Path, nargs='+', help='Run paired full-resolution DNG replays')
    parser.add_argument('--out', type=Path, help='New directory for DNG comparison boards and results')
    args = parser.parse_args()
    if bool(args.out) != bool(args.dng):
        parser.error('--dng and --out must be supplied together')
    rev = subprocess.check_output(['git', '-C', args.upstream, 'rev-parse', 'HEAD'], text=True).strip()
    if (rev != '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'
            or subprocess.check_output(['git', '-C', args.upstream, 'status', '--porcelain'])):
        raise ValueError('expected a clean, pinned upstream checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.model import grain
    from spektrafilm.runtime.params_builder import digest_params, init_params

    p = digest_params(init_params(film_profile='kodak_portra_400'))
    params = p.film_render.grain
    if not (params.active and params.sublayers_active) or p.settings.use_fast_stats:
        raise ValueError('expected layered Portra grain with exact sampling')
    if args.dng:
        dng_chain(args.dng, args.out, rev)
        return
    if args.d0 is not None:
        root = Path(__file__).resolve().parent.parent
        replay = runpy.run_path(str(root / 'test_temp/spektrafilm-full-phone.py'))
        image, sha = replay['load_input']('print-fixed', args.d0)
        print(f'D0 SHA-256 {sha}; shape {image.shape}', flush=True)
        run_pair(image)
        return
    curves = np.asarray(p.film.data.density_curves)
    curves = curves - np.nanmin(curves, axis=0)
    layers = np.asarray(p.film.data.density_curves_layers)
    rng = np.random.default_rng(7142)
    image = rng.uniform(-0.01, 1.01, (128, 192, 3)) * np.nanmax(curves, axis=0)
    # Supply the same nonconstant random draws only at the microstructure RNG boundary.
    # The blur, multiplication, nine particle samplers and dyeblurs stay in the test.
    def shared_microstructure(mean, std):
        variance = np.log1p((std / mean)**2)
        return np.random.default_rng(63).lognormal(np.log(mean) - variance / 2, np.sqrt(variance))

    for pixel_um in (2.0, 8.8, 15.0):
        with patch.object(grain, 'fast_lognormal_from_mean_std', shared_microstructure):
            start = perf_counter()
            ref = grain.apply_grain(image, pixel_um, params, curves, layers, 'negative')
            split = perf_counter()
            result = stream_layers(grain, image, pixel_um, params, curves, layers, False)
            end = perf_counter()
        if not np.isfinite(ref).all() or not np.isfinite(result).all():
            raise ValueError('non-finite grain output')
        np.testing.assert_array_equal(ref, result)
        print(f'{pixel_um:g} um: {result.size} identical values; '
              f'reference {split-start:.3f}s, streamed {end-split:.3f}s (includes warmup)')


def run_pair(image, thumbnail=None):
    from spektrafilm.model import develop, glare, grain
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    from spektrafilm.runtime.stages import scanning

    root = Path(__file__).resolve().parent.parent
    stripe = runpy.run_path(str(root / 'tools/filmkit-print-upstream-ab.py'))['stripe']

    def fixed_draws(seed):
        def draw(mean, std):
            variance = np.log1p((std.flat[0] / mean.flat[0])**2)
            return np.random.default_rng(seed).lognormal(
                np.log(mean.flat[0]) - variance / 2, np.sqrt(variance), mean.shape)
        return draw

    def streamed(density, pixel_um, params, curves, layers, profile_type,
                 bypass_grain=False, use_fast_stats=False):
        if profile_type != 'negative' or bypass_grain or not params.sublayers_active:
            raise ValueError('expected the active layered negative-grain branch')
        return stream_layers(grain, density, pixel_um, params, curves, layers, use_fast_stats)

    expected = ('rgb_pre', 'log_e_film', 'cmy_film', 'log_e_print', 'cmy_print', 'rgb_out')

    def run(candidate):
        p = init_params(film_profile='kodak_portra_400')
        p.camera.auto_exposure = False
        p.io.input_color_space = 'ITU-R BT.2020'
        p.io.input_cctf_decoding = False
        p.io.scan_film = False
        p.settings.preview_mode = False
        p.debug.lut_mode = False
        p.settings.use_enlarger_lut = False
        p.settings.use_scanner_lut = False
        pipe = SimulationPipeline(digest_params(p))
        assert pipe.film_render.grain.active and pipe.print_render.glare.active
        assert pipe.io.output_color_space == 'sRGB' and pipe.io.output_cctf_encoding
        pr, sc = pipe._printing_stage, pipe._scanning_stage
        pr._film_cmy_to_print_log_raw = stripe(pr._film_cmy_to_print_log_raw, 16)
        sc.cmy_to_log_xyz = stripe(sc.cmy_to_log_xyz, 16)
        hashes, seconds = {}, {}
        with ExitStack() as stack:
            stack.enter_context(patch.object(grain, 'fast_lognormal_from_mean_std', fixed_draws(63)))
            stack.enter_context(patch.object(glare, 'fast_lognormal_from_mean_std', fixed_draws(64)))
            stack.enter_context(patch.object(scanning, 'compress_rgb', stripe(scanning.compress_rgb, 32)))
            if candidate:
                stack.enter_context(patch.object(develop, 'apply_grain', streamed))
            assert tuple(n.writes[0] for n in pipe._topology) == expected
            frame, tap = image, 'rgb_in'
            for node in pipe._topology:
                assert node.reads == (tap,) and len(node.writes) == 1
                tap = node.writes[0]
                tick = perf_counter()
                frame = node.run(frame)
                elapsed = perf_counter() - tick
                seconds[tap] = elapsed
                assert frame.shape == image.shape and frame.dtype == np.float64
                digest = hashlib.sha256()
                for i in range(0, len(frame), 32):
                    block = np.ascontiguousarray(frame[i:i+32])
                    assert np.isfinite(block).all()
                    digest.update(memoryview(block).cast('B'))
                hashes[tap] = digest.hexdigest()
                print(f'{"streamed" if candidate else "reference"} {tap}: '
                      f'{elapsed:.3f}s {hashes[tap]}', flush=True)
        return dict(hashes=hashes, seconds=seconds), thumbnail(frame) if thumbnail else None

    reference, ref_preview = run(False)
    candidate, cand_preview = run(True)
    different = [tap for tap in expected if reference['hashes'][tap] != candidate['hashes'][tap]]
    if different:
        raise AssertionError(f'different taps: {different}')
    print(f'PASS: all {len(expected)} FP64 taps byte-identical; no frame files written', flush=True)
    return reference, candidate, ref_preview, cand_preview


def dng_chain(inputs, out, revision):
    from PIL import Image, ImageCms, ImageDraw
    import rawpy

    out.mkdir()
    icc = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()

    def thumbnail(frame):
        encoded = np.empty(frame.shape, dtype=np.uint8)
        for i in range(0, len(frame), 32):
            rows = slice(i, i+32)
            encoded[rows] = np.rint(np.clip(frame[rows], 0, 1)*255).astype(np.uint8)
        preview = Image.fromarray(encoded)
        preview.thumbnail((590, 666), Image.Resampling.LANCZOS)
        return preview

    for path in inputs:
        print(path.name, flush=True)
        with rawpy.imread(str(path)) as raw:
            image = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                use_camera_wb=True, no_auto_bright=True, adjust_maximum_thr=0.,
                gamma=(1, 1), output_bps=16, output_color=rawpy.ColorSpace.Rec2020).astype(np.float64)
        image /= 65535.
        reference, candidate, ref_preview, cand_preview = run_pair(image, thumbnail)
        board = Image.new('RGB', (1216, 714), '#181a1c')
        labels = ('Candidate: streamed grain', 'Upstream: Portra 400 + Endura')
        draw = ImageDraw.Draw(board)
        for i, (preview, label) in enumerate(zip((cand_preview, ref_preview), labels)):
            x = i*608
            draw.text((x+12, 10), label, fill='white', font_size=22)
            board.paste(preview, (x+(608-preview.width)//2, 36))
        board.save(out / (path.stem+'.jpg'), quality=95, subsampling=0, icc_profile=icc)
        with path.open('rb') as source:
            input_sha = hashlib.file_digest(source, 'sha256').hexdigest()
        report = dict(input=path.name, input_sha256=input_sha, shape=image.shape, upstream=revision,
                      film='Portra 400', paper='Endura', shared_random_draws=True,
                      candidate_change='grain sublayer scheduling only',
                      reference=reference, candidate=candidate, identical_rgb_values=image.size)
        (out / (path.stem+'.json')).write_text(json.dumps(report, indent=2)+'\n')
        del image


if __name__ == '__main__':
    main()
