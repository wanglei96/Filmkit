#!/usr/bin/env python3
"""Measure clipped-photosite influence through complete pinned CPU RCD.

Symmetric perturbations are diagnostic counterfactuals, NOT restored measurements
or proposed highlight reconstruction. Each native frame has its own geometry and
exposure. No fusion/P0/P4/film/LUT replay or optical attribution is claimed.
"""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory',type=Path)
    ap.add_argument('--token',required=True)
    ap.add_argument('--output',type=Path,required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    lamp = load('influence_lamp','analyze-lampshade-capture.py')
    rcd = load('influence_rcd','check-rcd-reference.py')
    calibration = load('influence_calibration','analyze-highlight-diagnostic.py')
    binary = args.directory/'opposed-reference'/('rawtherapee-'+rcd.REVISION)/'reference'
    assert binary.is_file(), 'Run the complete pinned RCD reference first'
    neutral,lsc = calibration.dng_calibration(args.directory/'FILMKIT_20261117-182121.dng')
    gains = neutral[1]/neutral
    meta = lamp.capture_events(args.directory/'capture.log',args.token)[0]['kv']
    ccm = np.array(list(map(float,meta['ccmStd'].split(','))),'f4').reshape(3,3)
    black = np.array(list(map(float,meta['black'].split(','))),'f4')
    white = meta['white']
    levels = lamp.clipping_levels(black,white)
    assert (meta['cfa'],meta['in'])==(1,'4080x3072')
    roi = (1300,1750,2000,2380)
    origin,size = (1718,1040),(694,764)
    assert not any(v&1 for v in (*origin,*size)), 'Preserve native GRBG phases'
    xx,yy = origin
    w,h = size
    cy,cx = np.mgrid[:h,:w]
    phase = (cx&1)+2*(cy&1)
    channel = np.array([1,0,2,1])[phase]
    shading = lamp.shading_crop(lsc,(4080,3072),roi)
    rotated_origin = (3072-yy-h,xx)
    rx0,ry0 = roi[0]-rotated_origin[0],roi[1]-rotated_origin[1]
    rx1,ry1 = rx0+roi[2]-roi[0],ry0+roi[3]-roi[1]

    def render(mosaic):
        rgb = rcd.run(binary,mosaic,8.)
        crop = (np.rot90(rgb,-1)[ry0:ry1,rx0:rx1]*shading).astype('f2').astype('f4')
        return np.clip(np.minimum(crop,shading).astype('f2').astype('f4')@ccm.T,0,64).astype('f2').astype('f4')

    entries = []
    for frame in (0,3):
        fm = json.loads((args.directory/'diagnostic-burst'/f'f{frame}.meta').read_text())
        assert (fm['w'],fm['h'])==(4080,3072)
        raw = np.memmap(args.directory/'diagnostic-burst'/f'f{frame}.raw',
                        dtype='<u2',mode='r',shape=(3072,fm['stridePx']))[yy:yy+h,xx:xx+w]
        mosaic = np.maximum((raw.astype('f4')-black[phase])/(white-black[phase]),0)*gains[channel]
        clipped = raw>=levels[phase]
        baseline = render(mosaic)
        prior = np.load(args.directory/'opposed-reference'/f'f{frame}-original-CPU-baseline.npy')
        delta = np.abs(baseline-prior)
        # Full reference vs halo crop must agree before testing counterfactuals.
        # RT's internal tile origin can change summation, never the valid stencil.
        np.testing.assert_allclose(baseline,prior,atol=.00390625,rtol=0)
        cols,rows = lamp.edge_coordinates(prior)
        offsets = np.arange(-8,13)
        p = prior[rows[:,None]+offsets,cols[:,None]]
        peaks = rows+offsets[np.argmax(np.minimum(p[...,0]-p[...,1],p[...,2]-p[...,1]),axis=1)]
        native_px = roi[1]+peaks
        native_py = 3072-1-roi[0]-cols
        peak_clipped = raw[native_py-yy,native_px-xx]>=levels[
            (native_px&1)+2*(native_py&1)]
        assert not peak_clipped.any(), 'This test describes the known outside-edge peaks only'
        cases = []
        for group,active in (('clipped-G',clipped&(channel==1)),
                             ('clipped-RB',clipped&(channel!=1))):
            for code_step in (1,4,16):
                results = []
                for sign in (-1,1):
                    # Keep the originally observed clipping classification fixed.
                    # Values above white are hypothetical, not recovered truth.
                    changed = mosaic+active*(sign*code_step/(white-black[phase])*gains[channel])
                    image = render(changed.astype('f4'))
                    peak_delta = image[peaks,cols]-baseline[peaks,cols]
                    results.append({'sign':sign,
                                    'edge':lamp.edge_metric(image,cols,rows),
                                    'baseline_peak_RGB_max_abs_change':float(np.abs(peak_delta).max()),
                                    'baseline_peak_RGB_changed_fraction':float(np.mean(np.any(peak_delta!=0,axis=-1)))})
                cases.append({'group':group,'native_code_step':code_step,
                              'changed_photosites':int(active.sum()),'results':results})
        entry = {'frame':frame,'full_reference_vs_halo_mean_p99_max':
                 [float(delta.mean()),*np.quantile(delta,[.99,1]).tolist()],
                 'baseline_edge':lamp.edge_metric(baseline,cols,rows),
                 'baseline_peak_native_clipped_count':int(peak_clipped.sum()),
                 'baseline_peak_count':len(cols),'cases':cases}
        entries.append(entry)
        print('frame',frame,'baseline',entry['baseline_edge']['magenta_positive_sum'],
              'full/crop',entry['full_reference_vs_halo_mean_p99_max'],flush=True)
        for case in cases:
            print(case['group'],case['native_code_step'],
                  [(r['sign'],r['baseline_peak_RGB_changed_fraction'],
                    r['edge']['magenta_positive_sum']) for r in case['results']],flush=True)
    report = {'scope':__doc__,'reference_revision':rcd.REVISION,'origin_native':origin,
              'size_native':size,'frames':entries,
              'warning':'A finite response proves sensitivity to a controlled changed input, '
                        'not that its true unobserved value would neutralize this real lamp. '
                        'Zero half-output response is not proof of no mathematical dependency.'}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    main()
