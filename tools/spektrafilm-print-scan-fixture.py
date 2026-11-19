"""Generate a pinned Portra/Endura oracle from developed negative CMY through linear XYZ."""

import argparse
import hashlib
from pathlib import Path
import runpy
import subprocess
import sys
from unittest.mock import patch

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('negative_cmy', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--chain', action='store_true')
    parser.add_argument('--full-rgb', type=Path)
    args = parser.parse_args()
    def git(*cmd):
        return subprocess.check_output(['git', '-C', args.upstream, *cmd], text=True).strip()

    if git('rev-parse', 'HEAD') != '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc' or git('status', '--porcelain'):
        parser.error('expected the pinned, unmodified reference checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.config import STANDARD_OBSERVER_CMFS
    from spektrafilm.model import glare
    from spektrafilm.model.illuminants import standard_illuminant
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    from spektrafilm.runtime.stages import scanning

    full = np.load(args.negative_cmy, mmap_mode='r', allow_pickle=False)
    film = full if args.chain else np.ascontiguousarray(full[21:1650:13, 17:1702:17])
    assert full.ndim == 3 and full.shape[-1] == 3 and full.dtype == np.float64
    assert args.chain or full.shape == (4080, 3072, 3)
    with args.output.open('xb') as target:
        def ints(*values):
            np.asarray(values, dtype='<u4').tofile(target)

        def doubles(values):
            np.asarray(values, dtype='<f8').tofile(target)

        def projection(profile, light, weights, gain, preflash, divisor):
            doubles(np.column_stack((profile.channel_density, profile.base_density, light, weights)))
            doubles(np.r_[np.broadcast_to(gain, (3,)), preflash, divisor])

        modes = ((False, False), (True, False), (False, True), (True, True))
        if args.full_rgb:
            assert args.chain and film.shape[0] * film.shape[1] > 1_000_000
            modes = modes[:1]
            stripe = runpy.run_path(str(Path(__file__).with_name('filmkit-print-upstream-ab.py')))['stripe']
        ints(len(modes), film.shape[1], film.shape[0])
        doubles(film)
        for i, (black, white) in enumerate(modes):
            p = init_params(film_profile='kodak_portra_400')
            p.io.scan_film = False
            p.settings.preview_mode = False
            p.settings.use_enlarger_lut = False
            p.settings.use_scanner_lut = False
            p.scanner.black_correction, p.scanner.white_correction = black, white
            p.enlarger.print_exposure = 1.0 if i == 0 else 1.18
            p.enlarger.preflash_exposure = 0.11 if i == 3 else 0.0
            pipe = SimulationPipeline(digest_params(p))
            printer = pipe._printing_stage
            scanner = pipe._scanning_stage
            if args.full_rgb:
                printer._film_cmy_to_print_log_raw = stripe(printer._film_cmy_to_print_log_raw, 16)
                scanner.cmy_to_log_xyz = stripe(scanner.cmy_to_log_xyz, 16)
            refs = pipe._color_reference_service
            assert not pipe.enlarger.diffusion_filter.active
            pipe._resize_service.crop_and_rescale(full)
            f, paper = pipe.film.data, pipe.print.data
            weights = np.nan_to_num(10 ** paper.log_sensitivity)
            source = standard_illuminant(pipe.enlarger.illuminant)
            light = pipe._enlarger_service.enlarger_filtered_illuminant(source)
            gain = printer._compute_exposure_factor_midgray(weights, light).item()
            preflash = printer._compute_raw_preflash(source, weights).reshape(3)
            scan_light = standard_illuminant(pipe.print.info.viewing_illuminant)
            observer = STANDARD_OBSERVER_CMFS[:]
            divisor = np.sum(scan_light * observer[:, 1])
            illuminant = scan_light @ observer / divisor
            log_e = printer.expose(film)
            correction = np.asarray(refs.black_white_printing_exposure_correction()).item()
            paper_cmy = printer.develop(log_e)
            log_xyz = scanner.cmy_to_log_xyz(paper_cmy)
            spec = pipe.print_render.glare
            rng = np.random.default_rng(64)
            variance = np.log1p(spec.roughness**2)
            field = rng.lognormal(np.log(spec.percent) - 0.5 * variance, np.sqrt(variance), film.shape[:2])
            with patch.object(glare, 'fast_lognormal_from_mean_std', lambda *_: field):
                xyz = glare.add_glare(refs.black_white_xyz_correction(10 ** log_xyz), illuminant, spec)
                if args.full_rgb:
                    with (patch.object(scanner, 'cmy_to_log_xyz', lambda _: log_xyz),
                          patch.object(scanning, 'compress_rgb', stripe(scanning.compress_rgb, 32))):
                        rgb = scanner.scan(paper_cmy)
                    np.save(args.full_rgb, rgb, allow_pickle=False)
                else:
                    rgb = scanner.scan(paper_cmy)
            morph = pipe.print_render.density_curves_morph
            model = paper.density_curves_model
            ints(black, white, spec.active, len(paper.log_exposure), model.n_layers, morph.active)
            doubles(np.r_[pipe.scanner.black_level, pipe.scanner.white_level, pipe.enlarger.print_exposure,
                         correction, spec.percent, spec.roughness, spec.blur,
                         -np.asarray(pipe.film_render.grain.density_min), np.nanmax(f.density_curves, axis=0),
                         morph.gamma_factor, morph.gamma_factor_fast, morph.gamma_factor_slow,
                         morph.gamma_factor_red, morph.gamma_factor_green, morph.gamma_factor_blue,
                         morph.developer_exhaustion, pipe.scanner.lens_blur, pipe.scanner.unsharp_mask])
            projection(f, light, weights, gain, preflash, 1.0)
            projection(paper, scan_light, observer, 1.0, np.zeros(3), divisor)
            doubles(paper.log_exposure)
            doubles(paper.density_curves)
            doubles(np.stack((model.centers, model.amplitudes, model.sigmas), axis=-1))
            doubles(field)
            doubles(log_e)
            doubles(paper_cmy)
            doubles(log_xyz)
            doubles(xyz)
            doubles(rgb)
    with args.output.open('rb') as source:
        print(f'{film.shape[1]}x{film.shape[0]}, sha256={hashlib.file_digest(source, "sha256").hexdigest()}')


if __name__ == '__main__':
    main()
