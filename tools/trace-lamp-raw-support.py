#!/usr/bin/env python3
"""Trace actual positive merge taps using archived weights and RAW codes.

The production merge equations are retained; diagnostic MRTs describe clipped
support and signal, not an end-to-end demosaic Jacobian. Fine short flow/masks
are reconstructed production CPU outputs, NOT device captures. Validate the
merged mosaic against the captured stage before interpreting provenance.
"""
import argparse
from dataclasses import replace
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT/'tools'/filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def trace_source(original):
    def change(old,new):
        nonlocal original
        assert original.count(old)==1,old
        original = original.replace(old,new)
    change('struct FrameMerge {', '''
uniform ivec2 uTraceOrigin;
uniform vec4 uTraceClipLevels;
layout(location = 2) out vec4 oClippedSupport;
layout(location = 3) out vec4 oFrameSupport;
layout(location = 4) out vec4 oClippedSignal;
layout(location = 5) out vec4 oNativeMinimum;
layout(location = 6) out vec4 oNativeMaximum;
struct FrameMerge {''')
    change('    vec3 noise;\n};','    vec3 noise;\n    vec4 trace;\n};')
    change('    return FrameMerge(vec2(intensity, support),\n'
           '                      vec3(actualVariance, shotSlope, readVariance));', '''
    float clippedSupport = 0.0, clippedSignal = 0.0;
    float minCode = 65535.0, maxCode = 0.0;
    for (int i = 0; i < 18; ++i) {
        if (i >= count) break;
        if (taps[i].y <= 0.0) continue;
        ivec2 coordinate = coordinates[i];
        int tapPhase = (coordinate.x & 1) + 2 * (coordinate.y & 1);
        float code = float(texelFetch(raw, coordinate, 0).r);
        minCode = min(minCode, code);
        maxCode = max(maxCode, code);
        if (code >= ceil(uTraceClipLevels[tapPhase])) {
            clippedSupport += taps[i].y;
            clippedSignal += taps[i].x * taps[i].y;
        }
    }
    return FrameMerge(vec2(intensity, support),
                      vec3(actualVariance, shotSlope, readVariance),
                      vec4(clippedSupport, clippedSignal, minCode, maxCode));''')
    change('    ivec2 p = ivec2(gl_FragCoord.xy);',
           '    ivec2 p = ivec2(gl_FragCoord.xy) + uTraceOrigin;')
    change('    oNoise = vec4(noise * varianceScale, shortSupport / totalSupport);','''
    oNoise = vec4(noise * varianceScale, shortSupport / totalSupport);
    oClippedSupport = vec4(r0.trace.x,r1.trace.x,r2.trace.x,r3.trace.x) * frameWeight / totalSupport;
    oFrameSupport = vec4(r0.sum.y,r1.sum.y,r2.sum.y,r3.sum.y) * frameWeight / totalSupport;
    oClippedSignal = vec4(r0.trace.y,r1.trace.y,r2.trace.y,r3.trace.y) * frameWeight * wb / totalSupport;
    oNativeMinimum = vec4(r0.trace.z,r1.trace.z,r2.trace.z,r3.trace.z);
    oNativeMaximum = vec4(r0.trace.w,r1.trace.w,r2.trace.w,r3.trace.w);''')
    return original


def run_trace(gpu, case, origin, size, image_dtype='f2'):
    ctx,program = gpu.ctx,gpu.program
    resources = []
    def bind(name,array,dtype,linear=False):
        components = array.shape[2] if array.ndim==3 else 1
        tex = ctx.texture((array.shape[1],array.shape[0]),components,
                          np.ascontiguousarray(array).tobytes(),dtype=dtype,alignment=1)
        tex.filter = (moderngl.LINEAR if linear else moderngl.NEAREST,)*2
        tex.repeat_x = tex.repeat_y = False
        tex.use(len(resources))
        program[name].value = len(resources)
        resources.append(tex)
    try:
        for i in range(4):
            bind(f'uRaw{i}',case.raw[i],'u2')
        for i in range(3):
            bind(f'uOff{i+1}',case.off[i],'f2' if case.flow_half else 'f4')
        bind('uW',case.weights,'f1',True)
        bind('uBentoFlow',case.flow,'f2' if case.flow_half else 'f4')
        bind('uBentoMask',case.mask,'f1',True)
        bind('uLinearMask',case.linear_mask,'f1',True)
        bind('uLongWeights',case.long_weights if case.long_weights is not None
             else np.zeros((1,1,3),'u1'),'f1',True)
        h,w = case.raw[0].shape
        levels = np.asarray(case.black,'f4')+(np.float32(case.white)-np.asarray(case.black,'f4'))*np.float32(250/255)
        uniforms = {'uSize':(w,h),'uTiles':tuple(map(float,case.off[0].shape[1::-1])),
                    'uCfaLut':case.cfa,'uBlack':case.black,'uWhite':case.white,'uGain':case.gain,
                    'uFrameScale':case.scales,'uShortIndex':case.short,'uKernelSigma':case.sigma,
                    'uGlobalWeight':case.global_weight,'uNoiseShot':case.noise_shot,
                    'uNoiseRead':case.noise_read,'uNoiseGainRatio':case.noise_gain_ratio,
                    'uLongFrames':case.long_frames,'uTraceOrigin':origin,'uTraceClipLevels':tuple(levels)}
        for key,value in uniforms.items():
            program[key].value = value
        targets = [ctx.texture(size,1 if i==0 else 4,dtype=image_dtype if i==0 else 'f4') for i in range(7)]
        resources.extend(targets)
        fbo = ctx.framebuffer(targets)
        resources.append(fbo)
        fbo.use()
        ctx.viewport = (0,0,*size)
        ctx.disable(moderngl.BLEND)
        gpu.vao.render(moderngl.TRIANGLES,vertices=3)
        ctx.finish()
        result = []
        for i in range(7):
            channels = 1 if i==0 else 4
            dtype = image_dtype if i==0 else 'f4'
            a = np.frombuffer(fbo.read(attachment=i,components=channels,dtype=dtype,alignment=1),dtype)
            a = a.reshape(size[1],size[0],channels).astype('f4')
            assert np.isfinite(a).all()
            result.append(a)
        assert ctx.error=='GL_NO_ERROR'
        np.testing.assert_allclose(result[3].sum(axis=-1),1,atol=3e-6,rtol=0)
        assert np.min(result[2])>=0 and np.max(result[2]-result[3])<3e-6
        return result
    finally:
        for resource in reversed(resources):
            resource.release()


def validate_instrumentation(merge):
    cases = []
    for lut in merge.CFA.values():
        for short in (-1,1,2,3):
            case = merge.case(f'trace-{lut}-{short}',64,48)
            cases.append(replace(case,cfa=lut,short=short,flow_half=True,
                                 off=[a.astype('f2') for a in case.off],flow=case.flow.astype('f2')))
    # Constant per-frame clipping gives an independent support oracle, including
    # both green lattices and each phase's inclusive native-code threshold.
    for clipped in ((False,)*4,(True,)*4,(False,True,False,True)):
        case = cases[-1]
        raw = [np.empty_like(a) for a in case.raw]
        levels = np.ceil(np.asarray(case.black)+(case.white-np.asarray(case.black))*np.float32(250/255))
        for frame in range(4):
            for phase in range(4):
                raw[frame][phase>>1::2,phase&1::2] = levels[phase]-(not clipped[frame])
        cases.append(replace(case,raw=raw,name='known-clipping-'+str(clipped)))
    expected = []
    baseline = merge.GPU(merge.source())
    try:
        for case in cases:
            expected.append((baseline.run(case,with_noise=True),baseline.run(case,target_dtype='f4')))
    finally:
        baseline.close()
    gpu = merge.GPU(trace_source(merge.source()))
    noise_error = 0.
    half_exact_cases = 0
    float_error = 0.
    half_steps = 0
    try:
        for index,(case,((image,noise),full_precision)) in enumerate(zip(cases,expected)):
            traced = run_trace(gpu,case,(0,0),(64,48))
            traced_float = run_trace(gpu,case,(0,0),(64,48),image_dtype='f4')[0][...,0]
            float_delta = np.abs(traced_float-full_precision)
            np.testing.assert_array_less(float_delta,8*np.finfo('f4').eps*np.maximum(np.abs(full_precision),1))
            float_error = max(float_error,float(float_delta.max()))
            # MRT instrumentation may change FP32 association before a half
            # rounding midpoint. Require the FP32 check above AND at most one
            # half storage step; report exactness instead of pretending parity.
            steps = np.abs(traced[0][...,0].astype('f2').view('u2').astype('i4')-
                           image.astype('f2').view('u2').astype('i4'))
            assert steps.max()<=1,(case.name,steps.max())
            half_steps = max(half_steps,int(steps.max()))
            half_exact_cases += int(not steps.any())
            np.testing.assert_allclose(traced[1],noise,atol=2e-7,rtol=1e-5)
            noise_error = max(noise_error,float(np.max(np.abs(traced[1]-noise))))
            if case.short in (1,2,3):
                np.testing.assert_array_equal(traced[3][...,case.short],traced[1][...,3])
            else:
                np.testing.assert_array_equal(traced[1][...,3],0.)
            crop = run_trace(gpu,case,(9,11),(38,24))
            for full,part in zip(traced,crop):
                np.testing.assert_array_equal(part,full[11:35,9:47])
            if index>=len(cases)-3:
                clipped = np.array(((False,)*4,(True,)*4,(False,True,False,True))[index-(len(cases)-3)])
                # Grouped mirror duplicates sum in a different FP32 order from
                # the original ungrouped support accumulator at image borders.
                np.testing.assert_allclose(traced[2],traced[3]*clipped,atol=0,
                                           rtol=4*np.finfo('f4').eps)
    finally:
        gpu.close()
    return {'cases':len(cases),'half_merge_bit_identical_cases':half_exact_cases,
            'half_merge_max_storage_steps':half_steps,'float_merge_max_abs_error':float_error,
            'noise_max_abs_error':noise_error,
            'short_support_matches_original_noise_alpha':True,'nonzero_origin_all_MRTs_bit_identical':True,
            'known_clipping_support_oracle':True}


def quantiles(a):
    return np.quantile(a,[0,.05,.5,.95,1]).tolist() if np.size(a) else None


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory',type=Path)
    ap.add_argument('--token',required=True)
    ap.add_argument('--output',type=Path,required=True)
    args = ap.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    lamp = load('trace_lamp','analyze-lampshade-capture.py')
    merge = load('trace_merge','check-highlight-merge.py')
    guard = load('trace_guard','check-highlight-long-guard.py')
    validation = validate_instrumentation(merge)
    cpu = args.directory/'stage-analysis/upstream-replay/cpu'
    data = json.loads((cpu/'metadata.json').read_text())
    h,w = data['h'],data['w']
    assert (w,h,data['cfa_lut'])==(4080,3072,97)
    assert data['accepted'] and data['short_index']==3
    raw = []
    for i in range(4):
        meta = json.loads((args.directory/'diagnostic-burst'/f'f{i}.meta').read_text())
        assert (meta['w'],meta['h'])==(w,h)
        raw.append(np.memmap(args.directory/'diagnostic-burst'/f'f{i}.raw','<u2',mode='r',
                             shape=(h,meta['stridePx']))[:,:w])
    off = [lamp.stage(args.directory,args.token,f'mfnr-offset-{i}')[0] for i in range(1,4)]
    weights,_ = lamp.stage(args.directory,args.token,'mfnr-weight')
    def read(name,shape,dtype):
        return np.fromfile(cpu/name,dtype).reshape(shape)
    flow = read('fine-flow.rgba32f',(data['fine_h'],data['fine_w'],4),'<f4')
    mask = read('mask-quarter.r8',(data['mask_h'],data['mask_w']),'u1')
    linear = read('mask-quarter-linear.r8',mask.shape,'u1')
    case = merge.Case('real-lamp',raw,off,weights,flow,mask,linear,cfa=data['cfa_lut'],
                      black=tuple(data['black']),white=data['white'],gain=tuple(data['wb']),
                      scales=tuple(data['scales']),short=data['short_index'],sigma=tuple(data['kernel_sigma']),
                      global_weight=tuple(data['global_weight']),noise_shot=tuple(data['noise_shot_rgb']),
                      noise_read=tuple(data['noise_read_rgb']),noise_gain_ratio=tuple(data['gain_ratios']))
    # Coarse offsets are archived half-float; accepted short field is native FP32.
    # Keep those storage types independent rather than rounding the fine field.
    case = replace(case,off=[a.astype('f4') for a in off])
    flags = sum(1<<(i-1) for i in range(1,4) if case.scales[i]<case.scales[0] and i!=case.short)
    reference = read('highlight-half.r8',(data['guide_h'],data['guide_w']),'u1')
    gpu = merge.GPU(trace_source(merge.source()))
    runner = guard.Passes(gpu.ctx)
    try:
        _,_,long_weights = guard.guard_weights(runner,case,reference,flags)
        case = replace(case,long_frames=flags,long_weights=long_weights)
        # Native crop encloses JPEG ROI and a 32-pixel halo. All texture fetches
        # still address full native RAW/weight/flow coordinates.
        origin = (1718,1040)
        size = (694,764)
        traced = run_trace(gpu,case,origin,size)
        renderer = gpu.ctx.info['GL_RENDERER']
    finally:
        runner.close()
        gpu.close()
    names = ('mosaic','noise','clipped-support','frame-support','clipped-signal','min-code','max-code')
    for name,a in zip(names,traced):
        np.save(args.output/(name+'.npy'),a)
    captured,_ = lamp.stage(args.directory,args.token,'mosaic-fused-before-HL')
    x0,y0 = origin
    captured = captured[y0:y0+size[1],x0:x0+size[0],0].astype('f4')
    error = np.abs(traced[0][...,0]-captured)
    # A bound in units of the archived target precision is a numeric agreement
    # check, NOT proof of unique source taps or matching device fine flow.
    half_ulp = np.abs(np.spacing(captured.astype('f2')).astype('f4'))
    agreement = error<=2*half_ulp
    d4,_ = lamp.stage(args.directory,args.token,'D4-CCM')
    roi = d4[::-1][1750:2380,1300:2000,:3].astype('f4')
    cols,rows = lamp.edge_coordinates(roi)
    samples = roi[rows[:,None]+np.arange(-8,13),cols[:,None]]
    mag = np.minimum(samples[...,0]-samples[...,1],samples[...,2]-samples[...,1])
    peaks = np.argmax(mag,axis=1)-8
    raw_x = 1750+rows+peaks
    raw_y = h-1-1300-cols
    # For every final peak examine each native phase in its containing quad.
    # This is exact merge provenance at that quad, NOT its full RCD influence.
    phases = []
    for phase in range(4):
        xx = (raw_x//2)*2+(phase&1)-x0
        yy = (raw_y//2)*2+(phase>>1)-y0
        clipping = traced[2][yy,xx]
        support = traced[3][yy,xx]
        phase_error = error[yy,xx]
        phases.append({'phase':phase,'channel':(1,0,2,1)[phase],
                       'clipped_support_share_quantiles':quantiles(clipping.sum(axis=-1)),
                       'short_support_share_quantiles':quantiles(support[:,3]),
                       'clipped_support_share_per_frame_mean':clipping.mean(axis=0).tolist(),
                       'positive_frame_support_count':[int(np.count_nonzero(support[:,i]>0)) for i in range(4)],
                       'active_frame_native_min_code_quantiles':[quantiles(traced[5][yy,xx,i][support[:,i]>0]) for i in range(4)],
                       'active_frame_native_max_code_quantiles':[quantiles(traced[6][yy,xx,i][support[:,i]>0]) for i in range(4)],
                       'captured_mosaic_abs_error_quantiles':quantiles(phase_error),
                       'within_two_captured_half_ULPs_fraction':float(np.mean(agreement[yy,xx])),
                       'scope':'Containing Bayer quad; exact positive merge taps, not all contributing demosaic taps.'})
    # Retain full maps so the demosaic dependency trace can query its real inputs.
    report = {'scope':__doc__,'renderer':renderer,'instrumentation':validation,
              'origin_native':origin,'size_native':size,'long_flags':flags,
              'production_merge_sha256':hashlib.sha256(merge.source().encode()).hexdigest(),
              'captured_mosaic_error':{'mean':float(error.mean()),'quantiles':quantiles(error),
                                       'exact_fraction':float(np.mean(error==0)),
                                       'within_two_captured_half_ULPs_fraction':float(agreement.mean())},
              'interpretation_status':('REPLAY_NUMERICALLY_AGREES_NOT_UNIQUE_DEVICE_PROVENANCE' if agreement.all()
                                       else 'REPLAY_DIVERGES_NOT_DEVICE_PROVENANCE'),
              'peak_quads':phases,'peak_count':len(cols),
              'input_sources':{'RAW':'device archived','temporal_weights':'device archived RGBA8',
                               'coarse_offsets':'device archived RGBA16F',
                               'short_fine_flow_and_masks':'production CPU reconstruction, not device capture'},
              'warning':'Support fractions measure actual merge coefficients, not signed nonlinear RCD/CCM contribution. '
                        'Saved native min/max maps contain spatial taps even in zero-weight frames; consult frame-support. '
                        'A clipped tap in support does not alone prove the final fringe is caused by that tap. '
                        'All physical attribution must be bounded by captured-mosaic replay error.'}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
