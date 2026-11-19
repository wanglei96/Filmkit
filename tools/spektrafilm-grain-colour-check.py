"""Compare original/native independent grain in full neutral-patch renders.

The large floating-point results are temporary; retain only JSON and PNGs.
"""

import argparse
import dataclasses
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw


LEVELS = (0.005, 0.02, 0.08, 0.18, 0.5)
REVISION = '3bb2c2d2801ff68b92019cf1dbcbb133d60832bc'


def statistics(on, off):
    residual = (on - off)[16:-16, 16:-16] * 255
    residual -= residual.mean(axis=(0, 1), keepdims=True)
    y = residual @ np.array([0.2126, 0.7152, 0.0722])
    cb = (residual[..., 2] - y) / 1.8556
    cr = (residual[..., 0] - y) / 1.5748
    return dict(rgb_sd=residual.std(axis=(0, 1)).tolist(),
                luma_sd=float(y.std()),
                chroma_rms=float(np.sqrt(np.mean(cb * cb + cr * cr))),
                rgb_correlation=np.corrcoef(residual.reshape(-1, 3).T).tolist())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('profile', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--native-results', type=Path,
                        help='optional output of the same checker on Android')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    revision = subprocess.check_output(
        ['git', '-C', args.upstream, 'rev-parse', 'HEAD'], text=True).strip()
    dirty = subprocess.check_output(
        ['git', '-C', args.upstream, 'status', '--porcelain'], text=True)
    if revision != REVISION or dirty:
        parser.error('expected the pinned, unmodified reference checkout')
    args.output.mkdir(exist_ok=False)
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    p = init_params(film_profile='kodak_portra_400')
    p.camera.auto_exposure = False
    p.io.input_color_space = 'ITU-R BT.2020'
    p.io.input_cctf_decoding = False
    p.io.scan_film = False
    p.settings.preview_mode = False
    p.settings.use_enlarger_lut = False
    p.settings.use_scanner_lut = False
    p = digest_params(p)
    p.print_render.glare.roughness = 0.0
    pipe = SimulationPipeline(p)
    pipe._resize_service.crop_and_rescale(np.zeros((1, 1, 3)))
    pipe._resize_service.pixel_size_um = 35000 / 4080

    with tempfile.TemporaryDirectory(prefix='filmkit-grain-colour-') as work:
        work = Path(work)
        native_file = args.native_results
        if native_file is None:
            native_file = work / 'native.rgb'
            sources = sorted((repo / 'core/engine/src/film/spektrafilm').glob('*.cpp'))
            subprocess.run(['clang++', '-std=c++20', '-O3', '-ffp-contract=off',
                            '-pthread', '-I' + str(repo / 'core/engine/src'),
                            str(Path(__file__).with_suffix('.cpp')), *map(str, sources),
                            '-o', str(work / 'check')], check=True)
            subprocess.run([str(work / 'check'), str(args.profile), str(native_file)], check=True)
        native = np.fromfile(native_file, dtype='<f8').reshape(len(LEVELS), 2, 256, 256, 3)
        results = []
        # Identical 100% crops, without saturation enhancement or residual amplification.
        panel = Image.new('RGB', (3 * 256, len(LEVELS) * 280), 'white')
        labels = ImageDraw.Draw(panel)
        for i, grey in enumerate(LEVELS):
            reference = []
            for enabled in (False, True):
                pipe.film_render.grain.active = enabled
                frame = np.full((256, 256, 3), grey)
                for node in pipe._topology[1:]:
                    frame = node.run(frame)
                reference.append(frame)
            off, on = reference
            actual_off, actual_on = native[i]
            if not all(np.isfinite(a).all() for a in (off, on, actual_off, actual_on)):
                raise AssertionError('nonfinite render')
            baseline_error = float(np.abs(off - actual_off).max())
            expected = statistics(on, off)
            actual = statistics(actual_on, actual_off)
            luma_ratio = actual['luma_sd'] / expected['luma_sd']
            chroma_ratio = actual['chroma_rms'] / expected['chroma_rms']
            passed = (baseline_error < 1e-9 and 0.9 < luma_ratio < 1.1
                      and 0.9 < chroma_ratio < 1.1)
            results.append(dict(input_grey=grey, baseline_max_error=baseline_error,
                                reference=expected, native=actual,
                                luma_sd_ratio=luma_ratio, chroma_rms_ratio=chroma_ratio,
                                passed=passed))
            for col, (label, array) in enumerate((('Grain off', off),
                                                  ('Original', on), ('Native', actual_on))):
                code = np.floor(np.clip(array, 0, 1) * 255 + 0.5).astype(np.uint8)
                panel.paste(Image.fromarray(code), (col * 256, i * 280 + 24))
                labels.text((col * 256 + 5, i * 280 + 5), f'{label} / input {grey}', fill='black')
            print(json.dumps(results[-1]), flush=True)
        report = dict(revision=revision,
                      profile_sha256=hashlib.sha256(args.profile.read_bytes()).hexdigest(),
                      native_origin=str(args.native_results) if args.native_results else 'host-source-build',
                      grain=dataclasses.asdict(p.film_render.grain),
                      pixel_um=35000 / 4080,
                      notes=['Independent random draws, not a per-pixel oracle.',
                             'Only diagnostic copies bypass grain / fix glare roughness.',
                             'Full negative-paper-scan chain; linear Rec.2020 input.',
                             'Metrics are encoded-sRGB residuals in 8-bit code units.',
                             '10% RMS envelope is a statistical smoke check, not a full distribution test.',
                             'No sensor noise, JPEG or HDR in these patches.'],
                      results=results, passed=all(r['passed'] for r in results))
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        panel.save(args.output / 'comparison.png')
        panel.crop((0, 3 * 280, 3 * 256, 4 * 280)).save(args.output / 'midgrey.png')
        if not report['passed']:
            raise SystemExit('neutral grain comparison failed; inspect report.json')


if __name__ == '__main__':
    main()
