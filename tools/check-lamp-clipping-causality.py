#!/usr/bin/env python3
"""Measure sensor clipping and demosaic aliasing separately using neutral truth.

No fusion, P0, P4, wavelet, LUT, lens aberration or noise is present in the
synthetic experiment. The balanced-clipping case is a counterfactual, NOT a
proposed RAW clamp or highlight reconstruction algorithm.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / file)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory', type=Path)
    ap.add_argument('--token', required=True)
    ap.add_argument('--output', type=Path, required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    lamp = load('lamp_causality', 'analyze-lampshade-capture.py')
    offline = load('lamp_causality_gl', 'engine-shader-offline.py')
    post = load('lamp_causality_post', 'check-highlight-postprocess.py')
    diagnostic = load('lamp_causality_dng', 'analyze-highlight-diagnostic.py')
    neutral, _ = diagnostic.dng_calibration(args.directory / 'FILMKIT_20261117-182121.dng')
    gains = neutral[1] / neutral
    assert np.isclose(neutral[1], neutral.max())
    size = 512
    yy, xx = np.mgrid[:size, :size]
    radius = np.hypot(xx-(size-1)/2, yy-(size-1)/2)
    band = np.abs(radius-150) < 12
    source = (ROOT / 'core/engine/src/render/FrameRenderer.cpp').read_text()
    vertex = re.search(r'const char\* kVertRot\s*=\s*R"\((.*?)\)";', source, re.S)[1]
    gl = offline.GL(size, size)
    shaders = offline.extract_shaders()
    draw = post.Draw(gl, shaders, post.Check())
    results = []
    try:
        raw_dummy = gl.ctx.texture((1,1),1,np.zeros((1,1,1),'u2').tobytes(),dtype='u2')
        draw.resources.append(raw_dummy)
        lsc = draw.texture(np.ones((2,2,3), 'f2'), 'f2')
        mask = draw.texture(np.zeros((1,1,1), 'f2'), 'f2')
        baseline_resources = len(draw.resources)
        cases = [(label, peak, clipping, width, original, split)
                 for width in (2,4,8)
                 for label, peak, clipping in (
                     ('neutral-unclipped', .8, False),
                     ('neutral-physical-clipping', 4., True),
                     ('neutral-balanced-counterfactual', 4., False))
                 for original, split in ((0.,False),(1.,False),(1.,True))]
        for label, peak, clipping, width, original, split in cases:
            edge = np.clip((150+width/2-radius)/width, 0, 1)
            scene = .05 + (peak-.05)*edge
            sparse = np.zeros((size,size,3), 'f4')
            for py, px, channel in ((0,0,1),(0,1,0),(1,0,2),(1,1,1)):
                values = scene[py::2, px::2]
                if clipping:
                    values = np.minimum(values / gains[channel], 1)*gains[channel]
                else:
                    values = np.minimum(values, 1)
                sparse[py::2,px::2,channel] = values
            mos = draw.texture(sparse, 'f2')
            common = {'uSize':(size,size),'uCfaLut':97,'uBlack':(64.,)*4,
                      'uWhite':1023.,'uGain':tuple(gains),'uMosaic':1.,'uCdFix':1.,
                      'uGOrig':original,'uSatClip':tuple(gains)}
            _, arrays = draw.run('kFsRcdDir', {'uRaw':raw_dummy,'uMos':mos}, common, (size,size))
            direction = draw.texture(arrays[0].astype('f2'), 'f2')
            _, arrays = draw.run('kFsRcdG', {'uRaw':raw_dummy,'uMos':mos,'uDir':direction}, common, (size,size))
            green = draw.texture(arrays[0].astype('f2'), 'f2')
            inputs = {'uRaw':raw_dummy,'uMos':mos,'uDir':direction,'uG':green,
                      'uLsc':lsc,'uHlMask':mask}
            if split:
                _, arrays = draw.run('kFsRcdChromaA',inputs,common,(size,size))
                inputs['uRcdA'] = draw.texture(arrays[0].astype('f2'),'f2')
            # kVertRot keeps the actual production demosaic output convention.
            old_vertex = gl.vs_src
            gl.vs_src = offline.to_core(vertex)
            rgba = gl.run(shaders['kFsRcdChromaB' if split else 'kFsRcdChroma'],
                          {**common,'uRot':0,'uScale':(1.,1.),'uST':tuple(np.eye(4).ravel()),
                           'uLscSize':(2,2),'uMaskOn':0},
                          inputs, size=(size,size))
            gl.vs_src = old_vertex
            rgb = rgba[...,:3].astype('f2').astype('f4')
            # Same native-exposure neutral output-white ceiling; no inference.
            resolved = np.minimum(rgb, 1).astype('f2').astype('f4')
            mag = np.minimum(resolved[...,0]-resolved[...,1],resolved[...,2]-resolved[...,1])
            label += f'-w{width}-g{int(original)}-split{int(split)}'
            results.append({'label':label,'band_magenta_positive_sum':float(np.maximum(mag[band],0).sum()),
                            'band_magenta_max':float(mag[band].max()),
                            'band_max_abs_channel_difference':float(np.ptp(resolved[band],axis=-1).max()),
                            'below_ceiling_magenta_pixels':int(np.count_nonzero(
                                band & (mag>.01) & (resolved[...,1]<.99)))})
            np.save(args.output/(label+'.npy'),resolved)
            Image.fromarray(np.uint8(np.clip(lamp.display(resolved),0,1)*255)).save(args.output/(label+'.png'))
            for resource in reversed(draw.resources[baseline_resources:]):
                resource.release()
            del draw.resources[baseline_resources:]
        assert len(results)==27 and all(np.isfinite(r['band_magenta_max']) for r in results)
    finally:
        draw.close()
        gl.close()
    # Probe actual native photosites in a full 13x13 support, phase by phase.
    # This is evidence about source support, not a claim about every kernel tap.
    d4, _ = lamp.stage(args.directory,args.token,'D4-CCM')
    roi = d4[::-1][1750:2380,1300:2000,:3].astype('f4')
    events = lamp.capture_events(args.directory/'capture.log',args.token)
    meta = events[0]['kv']
    thresholds = lamp.clipping_levels(list(map(float,meta['black'].split(','))),meta['white'])
    frames = []
    for index in (0,3):
        name = 'native-reference' if index==0 else 'native-short'
        native_rgb = np.load(args.directory/'stage-analysis'/f'replay-{name}-single-frame-ceiling-D4.npy')
        columns, rows = lamp.edge_coordinates(native_rgb)
        samples = native_rgb[rows[:,None]+np.arange(-8,13),columns[:,None]]
        mag = np.minimum(samples[...,0]-samples[...,1],samples[...,2]-samples[...,1])
        peak = np.argmax(mag,axis=1)-8
        m = json.loads((args.directory/'diagnostic-burst'/f'f{index}.meta').read_text())
        raw = np.memmap(args.directory/'diagnostic-burst'/f'f{index}.raw','<u2',mode='r',
                        shape=(m['h'],m['stridePx']))[:,:m['w']]
        x = 1750+rows+peak
        y = m['h']-1-1300-columns
        phases = []
        for phase in range(4):
            values = []
            for dy in range(-6,7):
                for dx in range(-6,7):
                    selected = ((((y+dy)&1)*2+((x+dx)&1))==phase)
                    values.extend(raw[y[selected]+dy,x[selected]+dx].tolist())
            values = np.asarray(values)
            phases.append({'phase':phase,'channel':(1,0,2,1)[phase],
                           'clipped_support_fraction':float(np.mean(values>=thresholds[phase])),
                           'native_code_quantiles':np.quantile(values,[0,.1,.5,.9,1]).tolist()})
        frames.append({'index':index,'alignment':'native own edge/peak from single-frame replay; '
                       '13x13 footprint, NOT exact fusion/demosaic tap validity',
                       'phases':phases})
    report = {'scope':__doc__,'gains':gains.tolist(),'synthetic':results,'native_clip_levels':thresholds.tolist(),
              'real_native_support':frames,
              'interpretation':'Report both unclipped sampling/demosaic aliasing and native clipping. '
              'A positive neutral control is false colour, not a real coloured object. '
              'Synthetic sufficiency is NOT unique attribution for the real capture. '
              'Counterfactual is not a production fix; no quality acceptance asserted.'}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
