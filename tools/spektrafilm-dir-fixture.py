"""Compare native DIR with the pinned reference using a real exposure tap."""

import argparse
from dataclasses import replace
import hashlib
from pathlib import Path
import subprocess
import sys

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('film_exposure', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('case', choices=('full', 'edges', 'iir', 'no-spatial', 'off', 'chain'))
    args = parser.parse_args()

    def git(*command):
        return subprocess.check_output(['git', '-C', args.upstream, *command], text=True).strip()

    if (git('rev-parse', 'HEAD') != '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'
            or git('status', '--porcelain')):
        parser.error('expected the pinned, unmodified reference checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.model.couplers import apply_density_correction_dir_couplers, compute_dir_couplers_matrix
    from spektrafilm.model.develop import develop_simple
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    p = SimulationPipeline(digest_params(init_params(film_profile='kodak_portra_400')))
    real = np.load(args.film_exposure, mmap_mode='r', allow_pickle=False)
    assert real.dtype == np.float64 and real.ndim == 3 and real.shape[-1] == 3
    assert args.case == 'chain' or real.shape == (4080, 3072, 3)
    pixel_um = 35000 / 4080
    direction = p.film_render.dir_couplers
    gamma = np.broadcast_to(p.film_render.density_curve_gamma, (3,))
    if args.case in ('full', 'chain'):
        exposure = real
    else:
        # A radius wider than the short dimension tests repeated reflection;
        # the 35-column image also exercises the non-full IIR column chunk.
        height, width = (5, 3) if args.case == 'edges' else (17, 35)
        exposure = np.array(real[::-147, ::89][:height, :width], copy=True)
        exposure[0, 0] = (-6., 1.0, 2.0)
        exposure[-1, -1] = (2.0, -5., -1.0)
        if args.case == 'iir':
            pixel_um = direction.diffusion_size_um / 3.0
        elif args.case == 'no-spatial':
            direction = replace(direction, diffusion_size_um=0.0)
            gamma = np.asarray([0.93, 1.07, 1.21])
        elif args.case == 'off':
            direction = replace(direction, active=False)

    axis = p.film.data.log_exposure
    stock = p.film.data.density_curves
    normalized = stock - np.nanmin(stock, axis=0)
    initial = develop_simple(exposure, axis, normalized, gamma)
    result = apply_density_correction_dir_couplers(
        initial, exposure, pixel_um, axis, normalized, direction, p.film.info.type, gamma_factor=gamma)
    matrix = compute_dir_couplers_matrix(direction) * direction.amount
    shape = [exposure.shape[1], exposure.shape[0], len(axis), int(direction.active)]
    with args.output.open('xb') as target:
        np.asarray(shape, dtype='<u4').tofile(target)
        np.r_[pixel_um, direction.diffusion_size_um, direction.diffusion_tail_um,
              direction.diffusion_tail_weight, gamma, matrix.ravel()].astype('<f8').tofile(target)
        for values in (axis, stock, exposure, result):
            for i in range(0, len(values), 64):
                part = values[i:i + 64]
                assert np.isfinite(part).all()
                part.astype('<f8', copy=False).tofile(target)
    with args.output.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    print(f'{args.case}: {shape[0]}x{shape[1]} pixel_um={pixel_um} sha256={digest}', flush=True)


if __name__ == '__main__':
    main()
