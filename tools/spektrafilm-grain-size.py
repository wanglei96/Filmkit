"""Same-DNG grain-only JPEG-size A/B; large intermediates auto-delete."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

import numpy as np
import rawpy
from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dng', type=Path)
    parser.add_argument('assets', type=Path)
    parser.add_argument('executable', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='filmkit-grain-size-') as tmp:
        tmp = Path(tmp)
        with rawpy.imread(str(args.dng)) as raw:
            rgb = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                use_camera_wb=True, no_auto_bright=True, adjust_maximum_thr=0, gamma=(1, 1),
                output_bps=16, output_color=rawpy.ColorSpace.Rec2020).astype('<f8') / 65535
        with (tmp / 'input.bin').open('xb') as f:
            np.array([rgb.shape[1], rgb.shape[0]], dtype='<u4').tofile(f)
            rgb.tofile(f)
        del rgb
        log = subprocess.check_output([str(args.executable.resolve()),
            str(args.assets / 'kodak_portra_400--kodak_portra_endura.skp'),
            str(args.assets / 'kodak_portra_400.response'), str(tmp / 'input.bin'), str(tmp)], text=True)
        sizes = {key: (tmp / (key + '.jpg')).stat().st_size for key in ('off', 'on')}
        crop = Image.new('RGB', (640, 320))
        for i, key in enumerate(('off', 'on')):
            with Image.open(tmp / (key + '.jpg')) as image:
                x, y = image.width // 2, image.height // 2
                crop.paste(image.crop((x - 160, y - 160, x + 160, y + 160)), (i * 320, 0))
        crop.save(args.report.with_suffix('.png'))
        report = dict(input=str(args.dng), input_sha256=hashlib.sha256(args.dng.read_bytes()).hexdigest(),
            jpeg_quality=100, chroma='444', encoder='production JpegEncode.cpp / host libjpeg-turbo',
            bytes=sizes, grain_added_bytes=sizes['on'] - sizes['off'], ratio=sizes['on'] / sizes['off'],
            scope='Same DNG decode, full spectral chain, same glare/seed. Not the APK MFNR input or JPEG/R gainmap.',
            timings='Host only; not a mobile performance measurement.', log=log)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
