"""Write a small FP64 development oracle from the pinned Spektrafilm checkout."""

import argparse
from dataclasses import replace
from pathlib import Path
import subprocess
import sys

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('negative_cmy', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()

    def git(*command):
        return subprocess.check_output(['git', '-C', args.upstream, *command], text=True).strip()

    if (git('rev-parse', 'HEAD') != '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'
            or git('status', '--porcelain')):
        parser.error('expected the pinned, unmodified reference checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.model.develop import develop_print_morph, develop_simple
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    p = init_params(film_profile='kodak_portra_400')
    p.io.scan_film = False
    p.settings.preview_mode = False
    p.settings.use_enlarger_lut = False
    p = SimulationPipeline(digest_params(p))
    film = np.load(args.negative_cmy, mmap_mode='r', allow_pickle=False)
    assert film.dtype == np.float64 and film.ndim == 3 and film.shape[-1] == 3
    assert p.print.info.stock == 'kodak_portra_endura' and not p.enlarger.diffusion_filter.active
    p._resize_service.crop_and_rescale(film)
    real = film.reshape(-1, 3)[np.linspace(0, film.size // 3 - 1, 8191, dtype=np.int64)]
    real = p._printing_stage.expose(real[None, :, :]).reshape(-1, 3)
    paper = p.print.data
    defaults = p.print_render.density_curves_morph
    custom = replace(defaults, active=True, gamma_factor=1.12, gamma_factor_fast=0.83,
                     gamma_factor_slow=1.21, gamma_factor_red=0.95,
                     gamma_factor_green=1.04, gamma_factor_blue=1.16, developer_exhaustion=0.65)
    stock = p.film.data
    gamma = np.array([0.95, 1.12, 1.23])

    def samples(axis, gamma):
        endpoints = np.r_[axis[0] - 2.0, axis, axis[-1] + 2.0, 0.0]
        x = endpoints[:, None] / np.broadcast_to(gamma, (3,))
        return np.concatenate((real, x, np.nextafter(x, -np.inf), np.nextafter(x, np.inf)))

    with args.output.open('x') as target:
        def line(*values):
            print(*values, file=target)

        line(5)
        for positive, morph in ((False, defaults), (False, custom),
                                (True, custom), (False, replace(custom, active=False))):
            axis = paper.log_exposure
            model = paper.density_curves_model
            x = samples(axis, 1.0)
            expected = develop_print_morph(x[None, :, :], axis, model, morph,
                                           profile_type='positive' if positive else 'negative')[0]
            line(1, len(axis), len(x), model.n_layers)
            line(int(positive), int(morph.active), morph.gamma_factor, morph.gamma_factor_fast,
                 morph.gamma_factor_slow, morph.gamma_factor_red, morph.gamma_factor_green,
                 morph.gamma_factor_blue, morph.developer_exhaustion)
            np.savetxt(target, np.stack((model.centers, model.amplitudes, model.sigmas), axis=-1).reshape(-1, 3))
            np.savetxt(target, axis)
            np.savetxt(target, np.column_stack((x, expected)))

        x = samples(stock.log_exposure, gamma)
        normalized = stock.density_curves - np.nanmin(stock.density_curves, axis=0)
        expected = develop_simple(x[None, :, :], stock.log_exposure, normalized, gamma)[0]
        line(0, len(stock.log_exposure), len(x), 0)
        line(*gamma)
        np.savetxt(target, stock.log_exposure)
        np.savetxt(target, stock.density_curves)
        np.savetxt(target, np.column_stack((x, expected)))


if __name__ == '__main__':
    main()
