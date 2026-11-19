"""Generate paired Hanatos and film exposure data from the pinned reference."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import numpy as np
import colour


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('sidecar', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--chain-log', type=Path)
    args = parser.parse_args()

    def git(*cmd):
        return subprocess.check_output(['git', '-C', args.upstream, *cmd], text=True).strip()

    if git('rev-parse', 'HEAD') != '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc' or git('status', '--porcelain'):
        parser.error('expected the pinned, unmodified reference checkout')
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    from spektrafilm.utils.spectral_upsampling import _illuminant_to_xy

    is_dng = args.sidecar.suffix.lower() == '.dng'
    if is_dng:
        import rawpy
        if not args.chain_log:
            parser.error('full DNG mode requires --chain-log')
        with rawpy.imread(str(args.sidecar)) as raw:
            rgb = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                use_camera_wb=True, no_auto_bright=True, adjust_maximum_thr=0.,
                gamma=(1, 1), output_bps=16, output_color=rawpy.ColorSpace.Rec2020).astype(np.float64)
        rgb /= 65535.
        d0 = rgb
        height = max(rgb.shape[:2])
        assert height == 4080
    else:
        meta = json.loads(args.sidecar.read_text())
        width, height = meta['w'], meta['h']
        assert meta['orientation'] == 'gl_bottom_up' and meta['space'] == 'scene-linear Rec.2020 D65'
        assert meta['type'] == 'float32_le' and meta['stride_bytes'] == width * 16 and meta['complete']
        rgba = np.memmap(args.sidecar.with_suffix('.rgba32f'), dtype='<f4', mode='r', shape=(height, width, 4))
        d0 = rgba[..., :3]
        rgb = np.array(d0[::-1][1900:2029, 1400:1551], dtype=np.float64, order='C')
    if not args.chain_log:
        rgb[0, :6] = [[0, 0, 0], [8, 0, 0], [0, 8, 0], [0, 0, 8], [8, 8, 8], [0.184] * 3]
    pixel_um = 35000 / height
    cases = ((0., 0., 0., True, True), (0.3, 2., 6., True, False), (-0.7, 0., 0., False, True))
    if args.chain_log:
        cases = cases[:1] if is_dng else cases[1:2]
    with args.output.open('xb') as target:
        def ints(*values):
            np.asarray(values, dtype='<u4').tofile(target)

        def doubles(values):
            arr = np.asarray(values, dtype='<f8')
            assert np.isfinite(arr).all()
            arr.tofile(target)

        ints(len(cases), rgb.shape[1], rgb.shape[0])
        doubles(rgb)
        for ev, boost, lens, active, renorm in cases:
            p = init_params(film_profile='kodak_portra_400')
            p.camera.auto_exposure = False
            p.camera.exposure_compensation_ev = ev
            p.camera.lens_blur_um = lens
            p.io.input_color_space = 'ITU-R BT.2020'
            p.io.input_cctf_decoding = False
            p.film_render.halation.boost_ev = boost
            p.film_render.halation.protect_ev = 1.0
            p.film_render.halation.active = active
            p.film_render.halation.halation_renormalize = renorm
            pipe = SimulationPipeline(digest_params(p))
            pipe._resize_service.crop_and_rescale(d0)
            assert pipe._resize_service.pixel_size_um == pixel_um
            assert not pipe.camera.diffusion_filter.active
            illuminant = _illuminant_to_xy(pipe.film.info.reference_illuminant)
            matrix = colour.RGB_to_XYZ(np.eye(3), colourspace=p.io.input_color_space,
                                      illuminant=illuminant, chromatic_adaptation_transform='CAT16').T
            sensitivity = np.nan_to_num(10 ** pipe.film.data.log_sensitivity)
            assert pipe.camera.filter_uv[0] <= 0 and pipe.camera.filter_ir[0] <= 0
            response = pipe._lut_service.get_filming_tc_lut(sensitivity)
            raw = pipe._filming_stage._rgb_to_film_raw(rgb, color_space=p.io.input_color_space)
            log_raw = pipe._filming_stage.expose(rgb)
            if args.chain_log:
                np.save(args.chain_log, log_raw, allow_pickle=False)
            spec = pipe.film_render.halation
            ints(response.shape[0], spec.active, spec.halation_n_bounces, spec.halation_renormalize)
            doubles(np.r_[pixel_um, ev, lens, spec.boost_ev, spec.boost_range, spec.protect_ev,
                         spec.scatter_amount, spec.scatter_spatial_scale,
                         spec.halation_amount, spec.halation_spatial_scale, spec.halation_bounce_decay,
                         spec.scatter_core_um, spec.scatter_tail_um, spec.scatter_tail_weight,
                         spec.halation_strength, spec.halation_first_sigma_um])
            doubles(matrix)
            doubles(response)
            doubles(raw)
            doubles(log_raw)
    with args.output.open('rb') as source:
        print(f'{rgb.shape[1]}x{rgb.shape[0]}, sha256={hashlib.file_digest(source, "sha256").hexdigest()}')


if __name__ == '__main__':
    main()
