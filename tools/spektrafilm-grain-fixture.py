"""Capture reference grain draws and density from the pinned Spektrafilm checkout."""

import argparse
from dataclasses import replace
import hashlib
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

import numpy as np
from scipy.stats import binom, poisson


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('exposure', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('case', choices=('portra', 'micro', 'provia', 'single', 'off', 'full', 'flat'))
    parser.add_argument('--chain-cmy', type=Path)
    args = parser.parse_args()

    def git(*command):
        return subprocess.check_output(['git', '-C', args.upstream, *command], text=True).strip()

    if (git('rev-parse', 'HEAD') != '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'
            or git('status', '--porcelain')):
        parser.error('expected the pinned, unmodified reference checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.model import grain
    from spektrafilm.model.couplers import apply_density_correction_dir_couplers
    from spektrafilm.model.develop import develop_simple
    from spektrafilm.runtime.params_builder import digest_params, init_params

    positive = args.case == 'provia'
    p = digest_params(init_params(film_profile='fujifilm_provia_100f' if positive else 'kodak_portra_400'))
    assert (p.film.info.type == 'positive') == positive and not p.settings.use_fast_stats
    pixel_um = 35000 / 4080
    params = p.film_render.grain
    if args.case == 'micro':
        pixel_um = 0.24
        params = replace(params, micro_structure=(0.96, 30))
    elif args.case == 'single':
        params = replace(params, sublayers_active=False, n_sub_layers=4)
    elif args.case == 'off':
        params = replace(params, active=False)

    source = np.load(args.exposure, mmap_mode='r', allow_pickle=False)
    full = args.case == 'full'
    counts_only = bool(args.chain_cmy and source.shape[0] * source.shape[1] > 1_000_000)
    assert not counts_only or args.case == 'portra'
    exposure = source if full or args.chain_cmy else np.array(source[23:1180:13, 43:1630:17], copy=True)
    stock = p.film.data.density_curves
    total = stock - np.nanmin(stock, axis=0)
    layers = p.film.data.density_curves_layers
    axis = p.film.data.log_exposure
    gamma = p.film_render.density_curve_gamma
    initial = develop_simple(exposure, axis, total, gamma)
    density = apply_density_correction_dir_couplers(
        initial, exposure, 35000 / 4080, axis, total, p.film_render.dir_couplers,
        p.film.info.type, gamma_factor=gamma)
    flat = args.case == 'flat'
    if flat:
        density = np.empty((512, 1536, 3), dtype=np.float64)
        for i, fraction in enumerate((0.05, 0.5, 0.95)):
            density[:, i * 512:(i + 1) * 512] = fraction * np.nanmax(total, axis=0)
        result = grain.apply_grain(density.copy(), pixel_um, params, total, layers, p.film.info.type)
    elif not full and not args.chain_cmy:
        density[0, 0] = (-0.1, np.nanmax(total[:, 1]), 9.0)
        density[-1, -1] = (0.0, 0.8, -0.06)
    means, probabilities, counts, clumps = [], [], [], []
    poisson_rvs, binom_rvs = poisson.rvs, binom.rvs
    rng = np.random.default_rng(63)

    def record_poisson(mean):
        if not counts_only:
            means.append(mean.copy())
        return poisson_rvs(mean, random_state=rng)

    def record_binomial(population, probability):
        if not counts_only:
            probabilities.append(probability.copy())
        developed = binom_rvs(population, probability, random_state=rng)
        if counts_only:
            assert developed.min() >= 0 and developed.max() <= np.iinfo(np.uint32).max
        counts.append(developed.astype('<u4') if counts_only else developed)
        return developed

    def record_clumping(mean, deviation):
        variance = np.log1p((deviation / mean)**2)
        field = rng.lognormal(np.log(mean) - 0.5 * variance, np.sqrt(variance))
        clumps.append(field.copy())
        return field

    if not (full or flat):
        with (patch.object(poisson, 'rvs', record_poisson),
              patch.object(binom, 'rvs', record_binomial),
              patch.object(grain, 'fast_lognormal_from_mean_std', record_clumping)):
            result = grain.apply_grain(density.copy(), pixel_um, params, total, layers, p.film.info.type)

        count = 3 if params.sublayers_active else params.n_sub_layers
        assert len(counts) == (3 * count if params.active else 0)
        assert counts_only or len(means) == len(probabilities) == len(counts)
        assert len(clumps) == int(params.active and params.sublayers_active and
                                 params.micro_structure[1] * 0.001 / pixel_um > 0.05)
        if args.chain_cmy:
            np.save(args.chain_cmy, result, allow_pickle=False)
    with args.output.open('xb') as output:
        np.asarray([density.shape[1] | (int(full) << 31) | (int(flat) << 30) | (int(counts_only) << 29),
                    density.shape[0], len(total), positive, params.active,
                    params.sublayers_active, params.n_sub_layers, len(clumps)], dtype='<u4').tofile(output)
        np.r_[pixel_um, params.particle_area_um2, params.particle_scale, params.particle_scale_layers,
              params.density_min, params.uniformity, params.blur, params.blur_dye_clouds_um,
              params.micro_structure].astype('<f8').tofile(output)
        for values in (total, layers, density):
            np.asarray(values, dtype='<f8').tofile(output)
        if not full:
            for values in (result, means, probabilities, *clumps):
                np.asarray(values, dtype='<f8').tofile(output)
            for draw in counts:
                np.asarray(draw, dtype='<u4' if counts_only else '<u8').tofile(output)
    with args.output.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    print(f'{args.case}: {density.shape[1]}x{density.shape[0]}, pixel_um={pixel_um}, sha256={digest}', flush=True)


if __name__ == '__main__':
    main()
