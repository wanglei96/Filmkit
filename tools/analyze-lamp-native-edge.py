#!/usr/bin/env python3
"""Measure native Bayer edge spreads without demosaicing or RAW fusion.

Capture-specific GRBG/rotation=1, fixed isolated lamp arc. Subpixel profiles pool
real photosites along a smooth green-edge geometry; they are not reconstructed
RGB pixels, an optical calibration or a ground-truth neutral-light measurement.
"""
import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def crossing(x, y, level):
    finite = np.isfinite(y)
    x, y = x[finite], y[finite]
    hit = np.flatnonzero((y[:-1] < level) & (y[1:] >= level))
    if not hit.size:
        return None
    i = hit[0]
    return float(x[i] + (level-y[i])/(y[i+1]-y[i])*(x[i+1]-x[i]))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    ap.add_argument('--token', required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    lamp = load('native_lamp', 'analyze-lampshade-capture.py')
    calibration = load('native_calibration', 'analyze-highlight-diagnostic.py')
    neutral, lsc = calibration.dng_calibration(args.directory/'FILMKIT_20261117-182121.dng')
    gains = neutral[1]/neutral
    event = lamp.capture_events(args.directory/'capture.log',args.token)[0]
    black = np.array(list(map(float,event['kv']['black'].split(','))))
    white = event['kv']['white']
    levels = lamp.clipping_levels(black,white)
    assert np.array_equal(black, np.full(4,64)) and white==1023
    roi = (1300,1750,2000,2380)
    x0,y0,x1,y1 = roi
    yy, xx = np.mgrid[y0:y1,x0:x1]
    phase = ((3072-1-xx)&1)*2+(yy&1)
    channel = np.array([1,0,2,1])[phase]
    shading = lamp.shading_crop(lsc,(4080,3072),roi)
    bins = np.arange(-12,12.25,.25)
    centres = (bins[:-1]+bins[1:])/2
    report = {'scope':__doc__,'native_clip_levels':levels.tolist(),'gains':gains.tolist(),'frames':[]}
    for index, name in ((0,'native-reference'),(3,'native-short')):
        meta = json.loads((args.directory/'diagnostic-burst'/f'f{index}.meta').read_text())
        assert (meta['w'],meta['h'])==(4080,3072)
        raw = np.memmap(args.directory/'diagnostic-burst'/f'f{index}.raw','<u2',mode='r',
                        shape=(meta['h'],meta['stridePx']))[:,:meta['w']]
        cropped = np.rot90(raw,-1)[y0:y1,x0:x1]
        replay = np.load(args.directory/'stage-analysis'/f'replay-{name}-single-frame-ceiling-D4.npy')
        columns, seed = lamp.edge_coordinates(replay)
        offsets = np.arange(-8,13)
        rgb = replay[seed[:,None]+offsets,columns[:,None]]
        magenta = np.minimum(rgb[...,0]-rgb[...,1],rgb[...,2]-rgb[...,1])
        peak = seed+offsets[np.argmax(magenta,axis=1)]
        native_x = y0+peak
        native_y = 3072-1-x0-columns
        peak_phase = (native_x&1)+2*(native_y&1)
        native_codes = raw[native_y,native_x]
        neighborhoods = {}
        for radius in (0,1,2,4,8):
            delta = np.arange(-radius,radius+1)
            nx = native_x[:,None,None]+delta[None,None,:]
            ny = native_y[:,None,None]+delta[None,:,None]
            sample_phase = (nx&1)+2*(ny&1)
            clipped = raw[ny,nx]>=levels[sample_phase]
            neighborhoods[str(radius)] = {
                'any_clipped_fraction':float(np.mean(clipped.any(axis=(1,2)))),
                'clipped_photosite_fraction':float(clipped.mean())}
        peak_observations = {
            'count':len(columns),'positive_magenta_fraction':float(np.mean(magenta.max(axis=1)>0)),
            'native_code_p0_p5_p50_p95_p100':np.quantile(native_codes,[0,.05,.5,.95,1]).tolist(),
            'native_phase_counts':np.bincount(peak_phase,minlength=4).tolist(),
            'neighborhoods_radius_native_pixels':neighborhoods,
            'scope':'Each frame own baseline D4 peak. Square neighborhoods are NOT actual '
                    'demosaic tap provenance or a causal clipping attribution. Radius 0 is '
                    'the measured CFA photosite only, not an observed full RGB triplet.'}
        measured = []
        for col, row in zip(columns,seed):
            rows = np.arange(max(0,row-12),min(cropped.shape[0],row+13))
            rows = rows[channel[rows,col]==1]
            values = (cropped[rows,col].astype('f8')-64)/959
            pos = crossing(rows,values,.5)
            if pos is not None:
                measured.append((col,pos))
        measured = np.array(measured)
        assert measured.shape[0]>=350, 'insufficient native green edge support'
        fit = np.polynomial.Polynomial.fit(measured[:,0],measured[:,1],4)
        # Geometry-only common reference for all colours; no per-colour shifts.
        residual = measured[:,1]-fit(measured[:,0])
        distance = (yy-y0-fit(xx-x0))/np.sqrt(1+fit.deriv()(xx-x0)**2)
        selected = (xx-x0>=columns.min()) & (xx-x0<=columns.max())
        signal = (cropped.astype('f8')-64)/959*gains[channel]
        clipped = cropped>=levels[phase]
        frame = {'index':index,'geometry_green_crossing_residual_p50_p95_max':
                 np.quantile(np.abs(residual),[.5,.95,1]).tolist(),
                 'geometry_fit_domain':fit.domain.tolist(),'geometry_fit_coefficients':fit.coef.tolist(),
                 'baseline_fringe_peak_observations':peak_observations,
                 'distance_centres':centres.tolist(),'profiles':{}}
        for c,label in enumerate(('R','G','B')):
            valid = selected & (channel==c)
            profile, shade_profile, clip_profile, counts = [],[],[],[]
            for lo,hi in zip(bins[:-1],bins[1:]):
                pick = valid & (distance>=lo) & (distance<hi)
                counts.append(int(pick.sum()))
                profile.append(float(np.median(signal[pick])) if pick.any() else None)
                shade_profile.append(float(np.median(signal[pick]*shading[...,c][pick])) if pick.any() else None)
                clip_profile.append(float(np.mean(clipped[pick])) if pick.any() else None)
            p = np.array([np.nan if v is None else v for v in profile])
            frame['profiles'][label] = {'count':counts,'WB_native_median':profile,
                'WB_LSC_native_median':shade_profile,'native_clipped_fraction':clip_profile,
                'WB_native_level_crossing':{str(t):crossing(centres,p,t) for t in (.25,.5,.75,.9)},
                'note':'Native per-colour photosites pooled; NOT a demosaiced pixel triplet. '
                       'Equal WB levels presume neutral light, not established for this lamp.'}
        report['frames'].append(frame)
        print('frame',index,'green geometry error',frame['geometry_green_crossing_residual_p50_p95_max'])
        print('peak clipping neighborhoods',neighborhoods)
        for label, entry in frame['profiles'].items():
            print(label,entry['WB_native_level_crossing'])
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print('saved',args.output/'report.json')


if __name__=='__main__':
    main()
