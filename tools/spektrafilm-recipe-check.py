"""Full original/native comparison of every selectable film/print recipe."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import re

import numpy as np
import colour


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('assets', type=Path)
    parser.add_argument('executable', type=Path)
    parser.add_argument('report', type=Path)
    parser.add_argument('--neutral', action='store_true')
    parser.add_argument('--capture-exposure', type=float)
    parser.add_argument('--adb', type=Path)
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream.resolve() / 'src'))
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline

    entries = json.loads((args.assets / 'catalog.json').read_text())['profiles']
    y, x = np.indices((13, 17))
    rgb = np.stack((x / 8, y / 6, ((x * 7 + y * 3) % 23) / 11), axis=-1)
    cases = []
    for recipe in entries:
        if args.capture_exposure is not None:
            if recipe['positive'] or recipe['id'] in {
                'kodak_gold_200--kodak_portra_endura', 'kodak_gold_200--kodak_2383',
                'fujifilm_pro_400h--kodak_2383',
            }:
                cases.extend((recipe, ev, False) for ev in (0, .75))
            continue
        if args.neutral:
            if recipe['positive'] or recipe['id'] == 'kodak_portra_400--kodak_portra_endura':
                cases.extend((recipe, ev, False) for ev in (-2, 0, 2))
            continue
        cases.append((recipe, 0, False))
        if recipe['positive'] or recipe['id'] == 'kodak_portra_400--kodak_portra_endura':
            cases.extend((recipe, ev, altered) for ev, altered in [(-2, False), (2, False), (.75, True)])
    results = []
    remote = None
    if args.adb:
        remote = subprocess.check_output([str(args.adb), 'shell', 'mktemp', '-d',
            '/data/local/tmp/filmkit-recipes.XXXXXXXX'], text=True).strip()
        if not re.fullmatch(r'/data/local/tmp/filmkit-recipes\.[A-Za-z0-9]{8}', remote):
            raise SystemExit('unsafe remote temporary directory')
        subprocess.run([str(args.adb), 'push', str(args.executable), remote + '/check'], check=True)
        subprocess.run([str(args.adb), 'push', str(args.assets), remote + '/assets'], check=True)
        subprocess.run([str(args.adb), 'shell', 'chmod', '700', remote + '/check'], check=True)
    try:
        run_cases(args, cases, rgb, results, remote)
    finally:
        if remote:
            subprocess.run([str(args.adb), 'shell', 'rm', '-rf', remote], check=True)
    passed = max(r['max_abs'] for r in results) < 2e-6
    args.report.write_text(json.dumps(dict(passed=passed, android=bool(args.adb), cases=results), indent=2) + '\n')
    if not passed:
        raise SystemExit('original/native recipe mismatch')


def run_cases(args, cases, rgb, results, remote):
    from spektrafilm.runtime.params_builder import digest_params, init_params
    from spektrafilm.runtime.pipeline import SimulationPipeline
    with tempfile.TemporaryDirectory(prefix='filmkit-recipe-check-') as tmp:
        output = Path(tmp) / 'native.bin'
        for recipe, ev, altered in cases:
            p = init_params(film_profile=recipe['film'], print_profile=recipe['paper'] or 'kodak_portra_endura')
            p.camera.auto_exposure = False
            p.camera.exposure_compensation_ev = ev
            p.io.input_color_space = 'ITU-R BT.2020'
            p.io.input_cctf_decoding = False
            p.io.scan_film = recipe['positive']
            p.settings.preview_mode = False
            p.settings.use_enlarger_lut = False
            p.settings.use_scanner_lut = False
            p = digest_params(p)
            p.film_render.grain.active = False
            p.print_render.glare.roughness = 0
            if altered:
                p.film_render.halation.halation_amount *= .4
                p.film_render.halation.scatter_amount *= 1.3
                p.film_render.dir_couplers.amount *= .6
                p.enlarger.print_exposure *= 2 ** .8
                p.scanner.lens_blur = .25
                p.scanner.unsharp_mask = (.9, .4)
                p.camera.film_format_mm = 50
            pipe = SimulationPipeline(p)
            pipe._resize_service.crop_and_rescale(rgb)
            pipe._resize_service.pixel_size_um = (50000 if altered else 35000) / 4080
            if args.neutral:
                values = []
                for grey in np.linspace(0, 1, 65):
                    original = np.full((1, 1, 3), grey)
                    if args.capture_exposure is not None:
                        original *= 2 ** args.capture_exposure
                    for node in pipe._topology[1:]:
                        original = node.run(original)
                    values.append(colour.cctf_decoding(original.reshape(3), function='sRGB'))
                original = np.array(values)
            else:
                original = rgb.copy()
                if args.capture_exposure is not None:
                    original *= 2 ** args.capture_exposure
                for node in pipe._topology[1:]:
                    original = node.run(original)
            command = ([str(args.adb), 'shell', remote + '/check', remote + '/assets/' + recipe['asset'],
                        remote + '/assets/' + recipe['response'], remote + '/native.bin', str(ev), str(int(altered))]
                       if remote else [str(args.executable.resolve()), str(args.assets / recipe['asset']),
                        str(args.assets / recipe['response']), str(output), str(ev), str(int(altered))])
            if args.capture_exposure is not None:
                command.extend(('neutral' if args.neutral else 'image', str(args.capture_exposure)))
            elif args.neutral:
                command.append('neutral')
            subprocess.run(command, check=True)
            native = (np.frombuffer(subprocess.check_output([str(args.adb), 'exec-out', 'cat', remote + '/native.bin']), dtype='<f8')
                      if remote else np.fromfile(output, dtype='<f8')).reshape(original.shape)
            difference = np.abs(native - original)
            maximum = float(difference.max())
            results.append(dict(recipe=recipe['id'], ev=ev, altered=altered,
                                max_abs=maximum, mean_abs=float(difference.mean())))
            print(json.dumps(results[-1]), flush=True)


if __name__ == '__main__':
    main()
