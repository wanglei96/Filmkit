#!/usr/bin/env python3
"""Replay stored RAW bursts through production GLSL pyramids and highlight merge.

CPU admission is compiled from the actual engine sources by the companion CLI.
Desktop replay verifies equations on archived data, not Mali/daylight appearance.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


def csv(value):
    return tuple(float(x) for x in value.split(','))


def resolve_color(args, raw, data, merged, noise, mask):
    import re
    engine = module('highlight_color_offline', ROOT/'tools/engine-shader-offline.py')
    h,w=merged.shape
    gl=engine.GL(w,h);sh=engine.extract_shaders();src=(ROOT/'core/engine/src/render/FrameRenderer.cpp').read_text()
    vertices=re.search(r'const char\* kVertRot\s*=\s*R"\((.*?)\)";',src,re.S).group(1)
    textures=[]
    def tex(a,dtype='f2'):
        if a.ndim==2:a=a[...,None]
        t=gl.ctx.texture((a.shape[1],a.shape[0]),a.shape[2],a.astype({'f2':'f2','f4':'f4','u2':'u2','f1':'u1'}[dtype]).tobytes(),dtype=dtype)
        t.filter=(moderngl.NEAREST,)*2;t.repeat_x=t.repeat_y=False;textures.append(t);return t
    try:
        rt=tex(raw[0],'u2');mt=tex(merged);nt=tex(noise,'f4')
        # Reference clipping mask is available in the RAW guide model. Upsample
        # its actual native-clipping footprint (not a fabricated highlight ROI).
        hm=np.fromfile(args.output/'cpu/highlight-half.r8',np.uint8).reshape(data['guide_h'],data['guide_w'])
        sm=tex(np.repeat(np.repeat(hm,2,axis=0),2,axis=1)[:h,:w],'f1')
        common={'uSize':(w,h),'uCfaLut':data['cfa_lut'],'uBlack':tuple(data['black']),
                'uWhite':data['white'],'uGain':tuple(data['wb']),'uMosaic':1.,'uGOrig':0.,'uCdFix':1.}
        p0=gl.run(sh['kFsRawDenoiseFused'],{'uSize':(w,h),'uCfaLut':data['cfa_lut'],
            'uNoiseA':(0.,)*3,'uNoiseB':(0.,)*3,'uMergedNoiseOn':1,'uNoiseStrength':1.,
            'uMix':.35,'uWClamp':0.,'uSatMaskOn':1}, {'uTex':mt,'uMergedNoise':nt,'uSatMask':sm})
        p0t=tex(p0)
        direct=gl.run(sh['kFsRcdDir'],common,{'uRaw':rt,'uMos':p0t});dt=tex(direct)
        green=gl.run(sh['kFsRcdG'],common,{'uRaw':rt,'uMos':p0t,'uDir':dt});gt=tex(green)
        # LSC opcode is not archived with these bursts. Unit map is explicit;
        # this replay proves color-chain equations, not exact captured lens shading.
        lsc=tex(np.ones((1,1,3),np.float32));dummy=tex(np.zeros((1,1),np.float32))
        gl.vs_src=engine.to_core(vertices)
        rot=1;dw,dh=h,w
        uniforms={**common,'uRot':rot,'uScale':(1.,1.),'uST':tuple(np.eye(4).ravel()),
                  'uLscSize':(1,1),'uMaskOn':0}
        rgb=gl.run(sh['kFsRcdChroma'],uniforms,{'uRaw':rt,'uMos':p0t,'uDir':dt,'uG':gt,'uLsc':lsc,'uHlMask':dummy},size=(dw,dh))
        rgbt=tex(rgb); maskt=tex(mask,'f1');maskt.filter=(moderngl.LINEAR,)*2
        resolve_src=re.search(r'kFsHighlightResolve\s*=\s*R"\((.*?)\)";', (ROOT/'core/engine/src/render/HighlightResolveShader.h').read_text(),re.S).group(1)
        resolved=gl.run(resolve_src,{'uDstSize':(dw,dh),'uRawSize':(w,h),'uRot':rot,'uLscSize':(1,1),
            'uCameraWhite':tuple(data['camera_white']),'uGain':tuple(data['wb']),
            'uReferenceScale':data['scales'][0],'uShortScale':data['scales'][3],
            'uHasShort':int(data['accepted'])},{'uTex':rgbt,'uBentoMask':maskt,'uLsc':lsc},size=(dw,dh))
        # Matrices are capture log camera->working CCM, not DNG XYZ->camera.
        ccm=np.array([[.9707,.0702,-.0409],[-.0179,1.1887,-.1708],[.0211,-.4105,1.3894]])
        if w==4080:ccm=np.array([[.978,.0653,-.0434],[-.0138,1.1957,-.1819],[.0237,-.3884,1.3648]])
        out=gl.run(sh['kFsCcm'],{'uSize':(dw,dh),'uCcm':tuple(ccm.T.ravel())},{'uTex':tex(resolved)},size=(dw,dh))
        out.astype('<f2').tofile(args.output/'resolved-d4.rgba16f')
        rgb0=rgb[...,:3]@ccm.T
        stats={}
        for label,a in [('before_rgb_white_policy',rgb0),('after_rgb_white_policy',out[...,:3])]:
            y=a@np.array([.2627,.678,.0593]);mag=np.minimum(a[...,0]-a[...,1],a[...,2]-a[...,1]);active=(y>.8)
            stats[label]={'highmag_pixels':int(np.sum(active&(mag>.1))),
                          'highmag_fraction':float(np.mean(active&(mag>.1))),
                          'highluma_pixels':int(active.sum()),
                          'luma_quantiles':np.quantile(y,[.5,.9,.99]).tolist()}
        stats['limitations']=['Production P0/RCD/RGB resolve/CCM replay; unit LSC because gain grid not archived.',
                              'Reference native clipping proxy used for P0 protection; device CPU mask sigma/dilate differs.',
                              'No PC3D/film/LUT/phone display parity claimed here.']
        (args.output/'color-report.json').write_text(json.dumps(stats,indent=2));print('Color replay:',stats)
    finally:
        for t in textures:t.release()
        gl.close()


def temporal_weights(mismatch, offsets, noise_shot, noise_read):
    """Mirror the production dilation/motion/median confidence chain, small grids only."""
    h, w = mismatch.shape[:2]
    th, tw = offsets[0].shape[:2]
    mis = np.rint(np.clip(mismatch[..., :3], 0, 1) * 255).astype(np.uint8)
    dil = np.zeros_like(mis)
    for y in range(h):
        for x in range(w):
            dil[y, x] = np.max(mis[max(y-2, 0):min(y+3, h), max(x-2, 0):min(x+3, w)], axis=(0, 1))
    fields = [a[..., :2] for a in offsets]
    mags = np.sort(np.concatenate([np.linalg.norm(a, axis=2).ravel() for a in fields]))
    baseline = float(mags[len(mags)//4])
    mot = np.zeros((th, tw, 3), np.float32)
    for z, a in enumerate(fields):
        for y in range(th):
            for x in range(tw):
                ids_y = np.clip(np.arange(y-1, y+2), 0, th-1)
                ids_x = np.clip(np.arange(x-1, x+2), 0, tw-1)
                median = np.median(a[ids_y[:, None], ids_x[None, :]], axis=(0, 1))
                dev = np.linalg.norm(a[y, x]-median)
                if dev <= 6.0:
                    excess = np.linalg.norm(a[y, x])-baseline
                    mot[y, x, z] = np.clip((excess-8.0)/12.0, 0, 1)
    # denoiseParams ns_typ uses sensor-domain variance at representative signal;
    # use the exact profile input and log this transport rather than user strength.
    ns = float(np.sqrt(max(float(noise_shot[1]) * .18 + float(noise_read[1]), 0)))
    fade = 1.0 if ns <= .02 else max(.3, 1-(ns-.02)/(.04-.02)*.7)
    mfield = dil.astype(np.float32)/255*fade
    for y in range(h):
        for x in range(w):
            ty, tx = y//4, x//4
            mfield[y, x] = np.maximum(mfield[y, x], mot[max(0,ty-1):min(th,ty+2), max(0,tx-1):min(tw,tx+2)].max((0,1)))
    smooth = np.zeros_like(mfield)
    for y in range(h):
        for x in range(w):
            values = mfield[max(0,y-1):min(h,y+2), max(0,x-1):min(w,x+2)].reshape(-1,3)
            smooth[y,x] = np.sort(values, axis=0)[len(values)//2]
    result = np.full((h,w,4),255,np.uint8)
    result[...,:3] = np.rint(np.clip((.35-smooth)/.25,0,1)*255).astype(np.uint8)
    return result, {'motion_baseline_raw_pixels':baseline,'residual_fade':fade,
                    'ordinary_unknown_rejected':True,'selected_short_uses_admission_not_mismatch':True}


def merge_replay(args, raw):
    folder = args.output / 'cpu'
    data = json.loads((folder / 'metadata.json').read_text())
    engine = module('highlight_temporal_offline', ROOT / 'tools/engine-shader-offline.py')
    merger = module('highlight_merge_regression', ROOT / 'tools/check-highlight-merge.py')
    h,w = raw[0].shape
    assert (data['w'],data['h']) == (w,h)
    assert data['cfa_lut'] == args.cfa, 'CFA input and CPU admission metadata differ'
    def read(name, shape, dtype):
        return np.fromfile(folder/name,dtype=dtype).reshape(shape)
    tw,th = data['coarse_w'],data['coarse_h']
    offsets = [read(f'coarse-flow-{i}.rgba32f',(th,tw,4),'<f4') for i in range(1,4)]
    gl = engine.GL(w,h)
    textures = {}
    try:
        for i,a in enumerate(raw):
            t=gl.ctx.texture((w,h),1,a.tobytes(),dtype='u2');t.filter=(moderngl.NEAREST,)*2;textures[f'uRaw{i}']=t
        for i,a in enumerate(offsets,1):
            # Production temporal fields are RGBA16F.
            t=gl.ctx.texture((tw,th),4,a.astype('f2').tobytes(),dtype='f2');t.filter=(moderngl.NEAREST,)*2;textures[f'uOff{i}']=t
        wb=np.array(data['wb']); shot=np.array(data['noise_shot_rgb'])*wb
        read_noise=np.array(data['noise_read_rgb'])*wb*wb
        uniforms={'uSize':(w,h),'uTiles':(tw*4,th*4),'uOffTiles':(tw,th),
                  'uTileM':32.,'uCfaLut':data['cfa_lut'],'uBlack':tuple(data['black']),
                  'uWhite':data['white'],'uGain':tuple(data['wb']),'uFrameScale':tuple(data['scales']),
                  'uGainRatio':tuple(data['gain_ratios']),'uNoiseA':tuple(shot),
                  'uNoiseB':tuple(read_noise),'uS':16.}
        mismatch=gl.run(engine.extract_shaders()['kFsMfnrMismatch'],uniforms,textures,size=(tw*4,th*4))
        weights, confidence_meta=temporal_weights(mismatch,offsets,data['noise_shot_rgb'],data['noise_read_rgb'])
        (args.output/'temporal-weight.rgba8').write_bytes(weights.tobytes())
        (args.output/'temporal-mismatch.rgba16f').write_bytes(mismatch.astype('f2').tobytes())
    finally:
        for t in textures.values():t.release()
        gl.close()
    flow=read('fine-flow.rgba32f',(data['fine_h'],data['fine_w'],4),'<f4')
    mask=read('mask-quarter.r8',(data['mask_h'],data['mask_w']),'u1')
    linear=read('mask-quarter-linear.r8',(data['mask_h'],data['mask_w']),'u1')
    case=merger.Case('archived-burst',raw,offsets,weights,flow,mask,linear,
        cfa=data['cfa_lut'],black=tuple(data['black']),white=data['white'],gain=tuple(data['wb']),
        scales=tuple(data['scales']),short=data['short_index'],sigma=tuple(data['kernel_sigma']),
        global_weight=tuple(data['global_weight']), noise_shot=tuple(data['noise_shot_rgb']),
        noise_read=tuple(data['noise_read_rgb']), noise_gain_ratio=tuple(data['gain_ratios']))
    gpu=merger.GPU(merger.source())
    try:
        output, merged_noise=gpu.run(case, with_noise=True)
        gpu_name=gpu.ctx.info.get('GL_RENDERER')
    finally:gpu.close()
    assert np.isfinite(output).all(), 'Nonfinite merged RAW'
    output.astype('<f2').tofile(args.output/'merged-reference.r16f')
    merged_noise.astype('<f4').tofile(args.output/'merged-noise.rgba32f')
    resolve_color(args, raw, data, output, merged_noise, mask)
    channels=[]
    for phase in range(4):
        color=(args.cfa>>(phase*2))&3
        a=output[phase>>1::2,phase&1::2]
        ref=raw[0][phase>>1::2,phase&1::2]
        clipped=ref>=np.ceil(data['black'][phase]+(data['white']-data['black'][phase])*.98)
        channels.append({'phase':phase,'color':color,'quantiles':np.quantile(a,[.01,.5,.9,.99,.999]).tolist(),
                         'reference_clipped_pixels':int(clipped.sum()),
                         'clipped_recovered_above_reference_white':float(np.mean(a[clipped]>1.02)) if clipped.any() else 0.})
    report={'renderer':gpu_name,'device_verified':False,'accepted':data['accepted'],'admission':data['admission'],
            'short_index':data['short_index'],'phases':channels,'confidence':confidence_meta,
            'output_domain':'WB linear reference radiance','finite':True,
            'scope':'Production GPU pyramids + production CPU guide/FB/collar/masks + production GLSL residual/merge. Not phone-display/daylight validation.'}
    (args.output/'merge-report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--wb', default='2.09375,1,1.787109375')
    p.add_argument('--black', default='64,64,64,64')
    p.add_argument('--white', type=float, default=1023)
    p.add_argument('--cfa', type=lambda x: int(x, 0), default=0x61)
    p.add_argument('--scales', default='1,1,0.21,2.82')
    p.add_argument('--shot', default='6.752e-5,6.9818e-5,4.9453e-5')
    p.add_argument('--read', default='4.8842e-7,2.9961e-7,2.9363e-7')
    p.add_argument('--gain-ratios', default='1,1,1.3888888889,1')
    p.add_argument('--pyramids-only', action='store_true')
    p.add_argument('--merge-only', action='store_true', help='Consume existing cpu/ maps and run production GLSL merge')
    p.add_argument('--cli', type=Path, default=Path('/tmp/replay-highlight-recovery'))
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    py_dir = args.output / 'pyramids'
    py_dir.mkdir(exist_ok=True)
    raw = []
    for i in range(4):
        meta = json.loads((args.input / 'burst' / f'f{i}.meta').read_text())
        a = np.fromfile(args.input / 'burst' / f'f{i}.raw', dtype='<u2')
        a = a.reshape(meta['h'], meta['stridePx'])[:, :meta['w']].copy()
        raw.append(a)
    h, w = raw[0].shape
    assert all(a.shape == (h, w) for a in raw)
    if args.merge_only:
        merge_replay(args,raw)
        return
    engine = module('highlight_offline', ROOT / 'tools/engine-shader-offline.py')
    gl = engine.GL(w, h)
    sh = engine.extract_shaders()
    vs = gl.vs_src
    prog_pyr = gl.ctx.program(vertex_shader=vs, fragment_shader=engine.to_core(sh['kFsMfnrPyr']))
    prog_down = gl.ctx.program(vertex_shader=vs, fragment_shader=engine.to_core(sh['kFsMfnrDown']))
    resources = [prog_pyr, prog_down]
    scales = csv(args.scales)
    try:
        for i, a in enumerate(raw):
            texture = gl.ctx.texture((w, h), 1, a.tobytes(), dtype='u2')
            texture.filter = (moderngl.NEAREST,) * 2
            frame_textures = [texture]
            prev_w, prev_h = w, h
            for level in range(1, 5):
                ww, hh = (prev_w + 1) // 2, (prev_h + 1) // 2
                target = gl.ctx.texture((ww, hh), 4, dtype='f1')
                target.filter = (moderngl.NEAREST,) * 2
                fbo = gl.ctx.framebuffer([target])
                fbo.use()
                gl.ctx.viewport = (0, 0, ww, hh)
                texture.use(0)
                program = prog_pyr if level == 1 else prog_down
                program['uRaw' if level == 1 else 'uTex'].value = 0
                program['uSize'].value = (prev_w, prev_h)
                if level == 1:
                    for k, v in {'uCfaLut': args.cfa, 'uBlack': csv(args.black),
                                 'uWhite': args.white, 'uGain': csv(args.wb),
                                 'uScale': scales[i]}.items():
                        program[k].value = v
                vao = gl.ctx.vertex_array(program, [])
                vao.render(moderngl.TRIANGLES, vertices=3)
                values = np.frombuffer(fbo.read(components=4, dtype='f1'), np.uint8).reshape(hh, ww, 4)
                (py_dir / f'f{i}-l{level}.u8').write_bytes(values[..., 0].copy().tobytes())
                vao.release()
                fbo.release()
                frame_textures.append(target)
                texture = target
                prev_w, prev_h = ww, hh
            for t in frame_textures:
                t.release()
        info = {'renderer': gl.ctx.info.get('GL_RENDERER'), 'raw_size': [w, h],
                'pyramid_storage': 'production RGBA8 shaders, R readback, no axis flips',
                'device_verified': False}
        (args.output / 'pyramid-replay.json').write_text(json.dumps(info, indent=2))
    finally:
        for r in resources:
            r.release()
        gl.close()
    print('Production GPU pyramids saved:', py_dir)
    if args.pyramids_only:
        return
    command = [str(args.cli), str(args.input), str(args.output/'cpu'), '--pyramids', str(py_dir),
               '--wb', args.wb, '--black', args.black, '--white', str(args.white),
               '--cfa', str(args.cfa), '--scales', args.scales, '--shot', args.shot,
               '--read', args.read, '--gain-ratios', args.gain_ratios]
    subprocess.run(command, check=True)
    merge_replay(args,raw)


if __name__ == '__main__':
    main()
