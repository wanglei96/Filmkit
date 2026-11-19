"""Independent original neutral-patch response for the HDR display reference."""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import colour


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    count = 65
    result = []
    for ev in (-2.0, 0.0, 2.0):
        p = init_params(film_profile='kodak_portra_400')
        p.camera.auto_exposure = False
        p.camera.exposure_compensation_ev = ev
        p.io.input_color_space = 'ITU-R BT.2020'
        p.io.input_cctf_decoding = False
        p.io.output_cctf_encoding = True
        p.io.scan_film = False
        p.settings.preview_mode = False
        p.settings.use_enlarger_lut = False
        p.settings.use_scanner_lut = False
        p.film_render.grain.active = False
        p.print_render.glare.roughness = 0.0
        pipe = SimulationPipeline(digest_params(p))
        pipe._resize_service.crop_and_rescale(np.zeros((1, 1, 3)))
        # A uniform patch keeps the real photograph's physical pixel pitch.
        pipe._resize_service.pixel_size_um = 35000.0 / 4080
        values = []
        for x in np.linspace(0.0, 1.0, count):
            frame = np.full((1, 1, 3), x)
            for node in pipe._topology[1:]:
                frame = node.run(frame)
            # Include the published matrix round-trip in the actual SDR
            # encoding path before decoding its display-linear response.
            values.append(colour.cctf_decoding(frame.reshape(3), function='sRGB'))
        result.append(values)
    with args.output.open('xb') as target:
        np.asarray([0x53484452, count, len(result)], dtype='<u4').tofile(target)
        np.asarray(result, dtype='<f8').tofile(target)
    print(json.dumps(dict(samples=count, exposure_ev=[-2, 0, 2], finite=bool(np.isfinite(result).all()))))


if __name__ == '__main__':
    main()
