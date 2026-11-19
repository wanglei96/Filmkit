"""Prepare input and profile parameters, with an optional small full-chain oracle.

The normal benchmark file contains no reference frames or random draws.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

import colour
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('dng', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--oracle', action='store_true', help='151x129 region, fixed reference draws')
    parser.add_argument('--profile-only', action='store_true', help='export a resolution-independent APK profile, no pixels')
    parser.add_argument('--film', default='kodak_portra_400')
    parser.add_argument('--paper', default='kodak_portra_endura')
    parser.add_argument('--direct', action='store_true')
    parser.add_argument('--response-file', type=Path, help='v3 packaged recipe with separate film response')
    args = parser.parse_args()
    revision = '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'
    if (subprocess.check_output(['git', '-C', args.upstream, 'rev-parse', 'HEAD'], text=True).strip() != revision
            or subprocess.check_output(['git', '-C', args.upstream, 'status', '--porcelain'])):
        parser.error('expected the pinned, unmodified reference checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.config import STANDARD_OBSERVER_CMFS
    from spektrafilm.model import glare
    from spektrafilm.model.couplers import compute_dir_couplers_matrix
    from spektrafilm.model.illuminants import standard_illuminant
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    from spektrafilm.utils.spectral_upsampling import _illuminant_to_xy

    if args.profile_only:
        if args.oracle:
            parser.error('a packaged profile cannot contain oracle pixels')
        rgb = np.zeros((1, 1, 3), dtype=np.float64)
    else:
        import rawpy
        with rawpy.imread(str(args.dng)) as raw:
            rgb = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                use_camera_wb=True, no_auto_bright=True, adjust_maximum_thr=0., gamma=(1, 1),
                output_bps=16, output_color=rawpy.ColorSpace.Rec2020).astype(np.float64)
        rgb /= 65535.
    full_shape = rgb.shape
    p = init_params(film_profile=args.film, print_profile=args.paper)
    p.camera.auto_exposure = False
    p.io.input_color_space = 'ITU-R BT.2020'
    p.io.input_cctf_decoding = False
    p.io.scan_film = args.direct
    p.settings.preview_mode = False
    p.settings.use_enlarger_lut = False
    p.settings.use_scanner_lut = False
    pipe = SimulationPipeline(digest_params(p))
    pipe._resize_service.crop_and_rescale(rgb)
    pixel_um = pipe._resize_service.pixel_size_um
    if args.oracle:
        y, x = rgb.shape[0] // 2, rgb.shape[1] // 2
        rgb = rgb[y-64:y+65, x-75:x+76].copy()
    f, paper = pipe.film.data, pipe.print.data
    h = pipe.film_render.halation
    d = pipe.film_render.dir_couplers
    g = pipe.film_render.grain
    m = pipe.print_render.density_curves_morph
    spec = pipe.print_render.glare
    model = paper.density_curves_model
    assert not pipe.camera.diffusion_filter.active and not pipe.enlarger.diffusion_filter.active
    assert pipe.camera.filter_uv[0] <= 0 and pipe.camera.filter_ir[0] <= 0
    assert pipe.settings.rgb_to_raw_method == 'hanatos2025' and not pipe.settings.use_fast_stats
    assert g.sublayers_active and g.micro_structure[1] * 0.001 / pixel_um <= 0.05
    sensitivity = np.nan_to_num(10 ** f.log_sensitivity)
    if args.response_file and args.response_file.exists():
        packed = np.fromfile(args.response_file, dtype='<f8')
        side = int(round((packed.size / 3) ** 0.5))
        response = packed.reshape(side, side, 3)
    else:
        response = pipe._lut_service.get_filming_tc_lut(sensitivity)
        if args.response_file:
            np.asarray(response, dtype='<f8').tofile(args.response_file)
    matrix = colour.RGB_to_XYZ(np.eye(3), colourspace=p.io.input_color_space,
        illuminant=_illuminant_to_xy(pipe.film.info.reference_illuminant),
        chromatic_adaptation_transform='CAT16').T
    weights = np.nan_to_num(10 ** paper.log_sensitivity)
    source = standard_illuminant(pipe.enlarger.illuminant)
    light = pipe._enlarger_service.enlarger_filtered_illuminant(source)
    gain = pipe._printing_stage._compute_exposure_factor_midgray(weights, light).item()
    preflash = pipe._printing_stage._compute_raw_preflash(source, weights).reshape(3)
    scan_light = standard_illuminant(pipe.film.info.viewing_illuminant if args.direct else pipe.print.info.viewing_illuminant)
    observer = STANDARD_OBSERVER_CMFS[:]
    divisor = np.sum(scan_light * observer[:, 1])
    with args.output.open('xb') as target:
        def ints(*values):
            np.asarray(values, dtype='<u4').tofile(target)

        def doubles(values, allow_nan=False):
            a = np.asarray(values, dtype='<f8')
            assert allow_nan or np.isfinite(a).all()
            a.tofile(target)

        def projection(profile, illuminant, response_weights, scale, flash, normalizer):
            # Unmeasured spectral endpoints stay NaN, as in the oracle.
            doubles(np.column_stack((profile.channel_density, profile.base_density, illuminant, response_weights)), allow_nan=True)
            doubles(np.r_[np.broadcast_to(scale, (3,)), flash, normalizer])

        assert not args.response_file or args.profile_only
        ints(0x534b4231, 3 if args.response_file else (2 if args.profile_only else 1),
             0 if args.profile_only else rgb.shape[1], 0 if args.profile_only else rgb.shape[0], response.shape[0],
             len(f.log_exposure), len(paper.log_exposure), model.n_layers, args.oracle)
        ints(h.active, h.halation_n_bounces, h.halation_renormalize)
        doubles(np.r_[pipe.camera.film_format_mm * 1000 if args.profile_only else pixel_um,
            pipe.camera.exposure_compensation_ev, pipe.camera.lens_blur_um,
            h.boost_ev, h.boost_range, h.protect_ev, h.scatter_amount, h.scatter_spatial_scale,
            h.halation_amount, h.halation_spatial_scale, h.halation_bounce_decay, h.scatter_core_um,
            h.scatter_tail_um, h.scatter_tail_weight, h.halation_strength, h.halation_first_sigma_um,
            np.asarray(pipe._color_reference_service.black_white_filming_exposure_correction()).item()])
        doubles(matrix)
        if not args.response_file:
            doubles(response)
        ints(d.active)
        doubles(np.r_[d.diffusion_size_um, d.diffusion_tail_um, d.diffusion_tail_weight,
            np.broadcast_to(pipe.film_render.density_curve_gamma, (3,)),
            (compute_dir_couplers_matrix(d) * d.amount).ravel()])
        doubles(f.log_exposure)
        doubles(f.density_curves)
        ints(g.active, g.sublayers_active, g.n_sub_layers)
        doubles(np.r_[g.particle_area_um2, g.particle_scale, g.particle_scale_layers,
            g.density_min, g.uniformity, g.blur, g.blur_dye_clouds_um, g.micro_structure])
        doubles(f.density_curves_layers)
        ints(pipe.scanner.black_correction, pipe.scanner.white_correction, spec.active and not args.direct, m.active)
        doubles(np.r_[pipe.scanner.black_level, pipe.scanner.white_level, pipe.enlarger.print_exposure,
            spec.percent, spec.roughness, spec.blur, -np.asarray(g.density_min),
            np.nanmax(f.density_curves, axis=0), m.gamma_factor, m.gamma_factor_fast, m.gamma_factor_slow,
            m.gamma_factor_red, m.gamma_factor_green, m.gamma_factor_blue, m.developer_exhaustion,
            pipe.scanner.lens_blur, pipe.scanner.unsharp_mask])
        projection(f, light, weights, gain, preflash, 1.0)
        projection(f if args.direct else paper, scan_light, observer, 1.0, np.zeros(3), divisor)
        doubles(paper.log_exposure)
        doubles(paper.density_curves)
        doubles(np.stack((model.centers, model.amplitudes, model.sigmas), axis=-1))
        if args.response_file:
            ints(pipe.film.info.type == 'positive', args.direct,
                 pipe.enlarger.normalize_print_exposure and pipe.enlarger.print_exposure_compensation)
            doubles(pipe._filming_stage._rgb_to_film_raw(np.full((1, 1, 3), 0.184)))
        if not args.profile_only:
            doubles(rgb)
        if args.oracle:
            from scipy.stats import binom, poisson
            rng = np.random.default_rng(63)
            means, probabilities, counts = [], [], []
            poisson_rvs, binomial_rvs = poisson.rvs, binom.rvs

            def draw_poisson(mean):
                means.append(mean.copy())
                return poisson_rvs(mean, random_state=rng)

            def draw_binomial(population, probability):
                probabilities.append(probability.copy())
                result = binomial_rvs(population, probability, random_state=rng)
                counts.append(result.copy())
                return result

            variance = np.log1p(spec.roughness**2)
            field = np.random.default_rng(64).lognormal(np.log(spec.percent) - variance/2,
                np.sqrt(variance), rgb.shape[:2])
            with (patch.object(poisson, 'rvs', draw_poisson), patch.object(binom, 'rvs', draw_binomial),
                  patch.object(glare, 'fast_lognormal_from_mean_std', lambda *_: field)):
                frame = rgb.copy()
                # Preserve the full input's physical pixel pitch for the crop.
                for node in pipe._topology[1:]:
                    frame = node.run(frame)
            assert len(counts) == len(means) == len(probabilities) == 9
            doubles(means)
            doubles(probabilities)
            np.asarray(counts, dtype='<u8').tofile(target)
            doubles(field)
            doubles(frame)
    input_sha = None
    if not args.profile_only:
        with args.dng.open('rb') as source:
            input_sha = hashlib.file_digest(source, 'sha256').hexdigest()
    with args.output.open('rb') as source:
        output_sha = hashlib.file_digest(source, 'sha256').hexdigest()
    print(json.dumps(dict(input=args.dng.name, input_sha256=input_sha, fixture_sha256=output_sha,
        shape=rgb.shape, full_shape=full_shape, pixel_um=pixel_um, upstream=revision,
        film=args.film, paper=args.paper, direct=args.direct, oracle=args.oracle, profile_only=args.profile_only,
        rawpy=None if args.profile_only else rawpy.__version__,
        libraw=None if args.profile_only else rawpy.libraw_version), indent=2), flush=True)


if __name__ == '__main__':
    main()
