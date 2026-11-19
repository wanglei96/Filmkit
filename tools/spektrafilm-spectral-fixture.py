"""Generate native-kernel test vectors using the pinned upstream stage functions."""

import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

import numpy as np


REVISION = '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('negative_cmy', type=Path, help='Developed film CMY, H x W x 3 float64 NPY')
    parser.add_argument('output', type=Path)
    parser.add_argument('case', choices=('print', 'scan', 'preflash-odd'))
    args = parser.parse_args()

    def git(*command):
        return subprocess.check_output(['git', '-C', args.upstream, *command], text=True).strip()

    if git('rev-parse', 'HEAD') != REVISION or git('status', '--porcelain'):
        parser.error(f'expected an unmodified checkout of {REVISION}')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.config import STANDARD_OBSERVER_CMFS
    from spektrafilm.model.illuminants import standard_illuminant
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    p = init_params(film_profile='kodak_portra_400')
    p.camera.auto_exposure = False
    p.io.input_color_space = 'ITU-R BT.2020'
    p.io.input_cctf_decoding = False
    p.io.scan_film = False
    p.settings.preview_mode = False
    p.debug.lut_mode = False
    p.settings.use_enlarger_lut = False
    p.settings.use_scanner_lut = False
    if args.case == 'preflash-odd':
        p.enlarger.preflash_exposure = 0.125
        p.enlarger.preflash_m_filter_shift = -12
        p.enlarger.preflash_y_filter_shift = 9
    pipeline = SimulationPipeline(digest_params(p))
    film = np.load(args.negative_cmy, mmap_mode='r', allow_pickle=False)
    assert film.ndim == 3 and film.shape[2] == 3 and film.dtype == np.float64
    assert pipeline.print.info.stock == 'kodak_portra_endura'
    printer = pipeline._printing_stage
    if args.case == 'scan':
        profile = pipeline.print.data
        light = standard_illuminant(pipeline.print.info.viewing_illuminant)
        weights = STANDARD_OBSERVER_CMFS[:]
        gain, offset, divisor = 1.0, np.zeros(3), np.sum(light * weights[:, 1])
        reference = pipeline._scanning_stage.cmy_to_log_xyz
        pipeline._resize_service.crop_and_rescale(film)
        assert not pipeline.enlarger.diffusion_filter.active

        def make_input(stripe):
            # Partition only the pointwise stages; pixel scale is from the full frame.
            return printer.develop(printer.expose(stripe))
    else:
        profile = pipeline.film.data
        weights = np.nan_to_num(10 ** pipeline.print.data.log_sensitivity)
        source = standard_illuminant(pipeline.enlarger.illuminant)
        light = pipeline._enlarger_service.enlarger_filtered_illuminant(source)
        gain = np.asarray(printer._compute_exposure_factor_midgray(weights, light)).item()
        offset = printer._compute_raw_preflash(source, weights).reshape(3)
        divisor = 1.0
        reference = printer._film_cmy_to_print_log_raw

        def make_input(stripe):
            return stripe

    if args.case == 'preflash-odd':
        assert np.all(offset > 0)
        low = -np.asarray(pipeline.film_render.grain.density_min)
        high = np.nanmax(profile.density_curves, axis=0)
        corners = np.array(np.meshgrid(*zip(low, high))).reshape(3, -1).T
        real = film.reshape(-1, 3)[np.linspace(0, film.size // 3 - 1, 4097, dtype=np.int64)]
        film = np.concatenate((corners, real, [[np.nan, 0., 0.], [np.inf, 0., 0.]]))[None, :, :]

    bands = np.column_stack((profile.channel_density, profile.base_density, light, weights))
    assert bands.shape == (81, 8)
    with args.output.open('xb') as target, np.errstate(invalid='ignore'):
        np.array([*film.shape[:2], len(bands)], dtype='<u4').tofile(target)
        bands.astype('<f8', copy=False).tofile(target)
        np.r_[np.broadcast_to(gain, (3,)), offset, divisor].astype('<f8').tofile(target)
        for i in range(0, len(film), 32):
            density = make_input(film[i:i + 32])
            expected = reference(density)
            np.concatenate((density, expected), axis=-1).astype('<f8', copy=False).tofile(target)
    with args.output.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    print(f'{args.case}: {film.shape[0]}x{film.shape[1]} sha256={digest}')


if __name__ == '__main__':
    main()
