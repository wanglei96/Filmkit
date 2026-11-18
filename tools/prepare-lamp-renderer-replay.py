#!/usr/bin/env python3
"""Prepare calibrated device replay, without changing archived RAW/evidence."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    ap.add_argument('--token', required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    lamp = module('lamp', ROOT/'tools/analyze-lampshade-capture.py')
    diagnostic = module('diagnostic', ROOT/'tools/analyze-highlight-diagnostic.py')
    events = lamp.capture_events(args.directory/'capture.log', args.token)
    meta = next(e['kv'] for e in events if e['event'] == 'raw.meta')
    render = next(e['kv'] for e in events if e['event'] == 'raw.render')
    upstream = json.loads((args.directory/'stage-analysis/upstream-replay/cpu/metadata.json').read_text())
    dng = next(args.directory.glob('*.dng'))
    neutral, lsc = diagnostic.dng_calibration(dng)
    frame_meta = [json.loads((args.directory/'diagnostic-burst'/f'f{i}.meta').read_text()) for i in range(4)]
    assert meta['cfa'] == 1 and render['rcdSplit'] == 0 and render['caApplied'] == 0
    assert np.allclose(neutral[1]/neutral, upstream['wb'], rtol=1e-6)
    assert all((m['w'], m['h']) == (render['w'], render['h']) for m in frame_meta)
    # Native device redoes geometry/admission. These are capture calibrations,
    # not the earlier desktop-reconstructed flow/mask.
    rows = [
        'FILMKIT_RAW_REPLAY_1',
        [render['w'], render['h'], meta['cfa'], meta['white'], 0, render['dn']],
        [m['stridePx'] for m in frame_meta], upstream['black'], neutral,
        [float(x) for x in meta['ccmStd'].split(',')],
        upstream['noise_shot_rgb']+upstream['noise_read_rgb'],
        -np.log2(upstream['scales']), upstream['gain_ratios'],
        [lsc.shape[1], lsc.shape[0]], lsc.ravel(),
    ]
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'replay.txt').write_text('\n'.join(
        r if isinstance(r, str) else ' '.join(format(float(v), '.10g') for v in r)
        for r in rows)+'\n')
    manifest = {'token': args.token, 'scope': __doc__, 'frames': [],
                'limitations': ['Fixed native FilmParams, no original private preset/LUT snapshot.',
                                'Device recomputes alignment, masks and fusion. Not old-JPEG byte reproduction.']}
    for i, m in enumerate(frame_meta):
        p = args.directory/'diagnostic-burst'/f'f{i}.raw'
        assert p.stat().st_size == m['stridePx']*m['h']*2
        manifest['frames'].append({'path': str(p.resolve()), 'size': p.stat().st_size,
                                  'sha256': hashlib.sha256(p.read_bytes()).hexdigest()})
    (args.output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(args.output/'replay.txt')


if __name__ == '__main__':
    main()
