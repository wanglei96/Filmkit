#!/usr/bin/env python3
"""Isolate known port departures inside the complete pinned CPU RCD reference.

These deliberate experimental departures are NOT production candidates or exact
GPU emulations. The untouched complete reference runs first. All cases retain
its tiling, borders, sequential chroma filling and HDR normalization contract.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

import numpy as np

from importlib.util import module_from_spec, spec_from_file_location


def load(name, file):
    spec = spec_from_file_location(name, Path(__file__).with_name(file))
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def replace_exact(source, old, new, count):
    assert source.count(old) == count, (old, source.count(old), count)
    return source.replace(old, new)


def variant(source, features):
    if 'short-gradients' in features:
        for near, far in (('- w1', '- w3'), ('+ w1', '+ w3'), ('-  1', '-  3'), ('+  1', '+  3')):
            offset = '- w2' if near.startswith('- w') else '+ w2' if near.startswith('+ w') else '-  2' if near.startswith('-') else '+  2'
            last = offset.replace('2', '4')
            term = f' + (std::fabs(cfa[indx {near}] - cfa[indx {far}]) + std::fabs(cfa[indx {offset}] - cfa[indx {last}]))'
            source = replace_exact(source, term, '', 1)
    if 'green-clamp' in features:
        old = 'rgb[1][indx] = intp(VH_Disc, H_Est, V_Est);'
        new = old + '''
                    rgb[1][indx] = std::clamp(rgb[1][indx],
                        std::min(std::min(cfa[indx-w1],cfa[indx+w1]),std::min(cfa[indx-1],cfa[indx+1])),
                        std::max(std::max(cfa[indx-w1],cfa[indx+w1]),std::max(cfa[indx-1],cfa[indx+1])));'''
        source = replace_exact(source, old, new, 1)
    if 'vh-nine-neighbours' in features:
        old = '0.25f * ((VH_Dir[indx - w1 - 1] + VH_Dir[indx - w1 + 1]) + (VH_Dir[indx + w1 - 1] + VH_Dir[indx + w1 + 1]))'
        new = '''(VH_Dir[indx-w1-1]+VH_Dir[indx-w1]+VH_Dir[indx-w1+1]
                        +VH_Dir[indx-1]+VH_Dir[indx]+VH_Dir[indx+1]
                        +VH_Dir[indx+w1-1]+VH_Dir[indx+w1]+VH_Dir[indx+w1+1])/9.f'''
        source = replace_exact(source, old, new, 2)
    return source


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    ap.add_argument('--token', required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    lamp = load('ablation_lamp', 'analyze-lampshade-capture.py')
    rcd = load('ablation_rcd', 'check-rcd-reference.py')
    calibration = load('ablation_calibration', 'analyze-highlight-diagnostic.py')
    folder = args.directory/'rcd-reference'/('rawtherapee-'+rcd.REVISION)
    original_code = (folder/'reference.cpp').read_text()
    binary = folder/'reference'
    assert binary.is_file()
    neutral, lsc = calibration.dng_calibration(args.directory/'FILMKIT_20261117-182121.dng')
    assert np.isclose(neutral[1], neutral.max())
    p0, _ = lamp.stage(args.directory, args.token, 'mosaic-after-P0')
    full_h, full_w = p0.shape[:2]
    origin, size = (1718, 1040), (694, 764)
    ox, oy = origin
    w, h = size
    assert not any(v & 1 for v in (*origin, *size))
    sparse = p0[oy:oy+h, ox:ox+w]
    mono = np.empty((h, w), 'f4')
    for py, px, c in ((0, 0, 1), (0, 1, 0), (1, 0, 2), (1, 1, 1)):
        mono[py::2, px::2] = sparse[py::2, px::2, c]
    roi = (1300, 1750, 2000, 2380)
    x0, y0, x1, y1 = roi
    rx = x0-(full_h-oy-h)
    ry = y0-ox
    assert min(rx, ry, h-rx-(x1-x0), w-ry-(y1-y0)) >= 32
    shading = lamp.shading_crop(lsc, (full_w, full_h), roi)
    metadata = json.loads((args.directory/'stage-analysis/upstream-replay/cpu/metadata.json').read_text())
    mask = np.fromfile(args.directory/'stage-analysis/upstream-replay/cpu/mask-quarter.r8', 'u1').reshape(
        metadata['mask_h'], metadata['mask_w'])/255
    share = lamp.raw_map_crop(mask, (full_w, full_h), roi)
    ceiling = shading*(1+share[..., None]*(metadata['scales'][3]-1))
    event = lamp.capture_events(args.directory/'capture.log', args.token)[0]
    ccm = np.array(list(map(float, event['kv']['ccmStd'].split(','))), 'f4').reshape(3, 3)
    captured, _ = lamp.stage(args.directory, args.token, 'D4-CCM')
    captured = captured[::-1][y0:y1, x0:x1, :3].astype('f4')
    cols, rows = lamp.edge_coordinates(captured)

    def render(executable, normalization=8.):
        rgb = rcd.run(executable, mono, normalization)
        crop = (np.rot90(rgb, -1)[ry:ry+y1-y0, rx:rx+x1-x0]*shading).astype('f2').astype('f4')
        return np.clip(np.minimum(crop, ceiling).astype('f2').astype('f4')@ccm.T, 0, 64).astype('f2').astype('f4')

    baseline = render(binary)
    prior = np.load(args.directory/'rcd-reference/P0-reference-norm8.npy')
    delta = np.abs(prior-baseline)
    np.testing.assert_allclose(baseline, prior, atol=.00390625, rtol=0)
    normalization_check = np.abs(render(binary, 16.)-baseline)
    cases = []
    for features in ((), ('short-gradients',), ('green-clamp',), ('vh-nine-neighbours',),
                     ('short-gradients', 'green-clamp', 'vh-nine-neighbours')):
        label = '+'.join(features) or 'complete-original'
        executable = binary
        code = variant(original_code, features)
        if features:
            cpp = args.output/(label+'.cpp')
            cpp.write_text(code)
            executable = args.output/label
            subprocess.run(['c++', '-O2', '-std=c++17', str(cpp), '-o', str(executable)], check=True)
        image = baseline if not features else render(executable)
        np.save(args.output/(label+'.npy'), image)
        entry = {'label': label, 'code_sha256': hashlib.sha256(code.encode()).hexdigest(),
                 'edge': lamp.edge_metric(image, cols, rows),
                 'RGB_abs_delta_from_original_mean_p99_max': [float(np.abs(image-baseline).mean()),
                     *np.quantile(np.abs(image-baseline), [.99, 1]).tolist()]}
        cases.append(entry)
        print(label, entry['edge']['magenta_positive_sum'], flush=True)
    report = {'scope': __doc__, 'revision': rcd.REVISION, 'cases': cases,
              'native_origin': origin, 'native_size': size,
              'original_full_vs_halo_RGB_mean_p99_max': [float(delta.mean()), *np.quantile(delta, [.99, 1]).tolist()],
              'original_norm8_vs_norm16_RGB_mean_p99_max': [float(normalization_check.mean()),
                  *np.quantile(normalization_check, [.99, 1]).tolist()],
              'warning': 'Individual departures interact; effects cannot be added. Not an exact production GPU emulation. '
                         'Resolve uses the previously validated reconstructed mask, not captured device provenance. '
                         'No clean scene truth, full pipeline quality verdict or new production fix is claimed.'}
    (args.output/'report.json').write_text(json.dumps(report, indent=2)+'\n')


if __name__ == '__main__':
    main()
