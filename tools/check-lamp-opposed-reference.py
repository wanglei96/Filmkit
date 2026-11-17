#!/usr/bin/env python3
"""Test complete pinned darktable RAW opposed reconstruction before demosaic.

Native frames are evaluated separately, in their own exposure units. This is
not a fused RAW correction, device replay, optical calibration or quality pass.
Algorithm bodies are extracted unchanged; adapters provide headless storage,
Bayer lookup, identity late-WB correction and scalar execution without OpenMP.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
REVISION = '395aa7d5156b68a69d8f2d25a3e6fc66c525a3c2'
BASE = f'https://raw.githubusercontent.com/darktable-org/darktable/{REVISION}/src/iop/'

ADAPTER = r'''
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
using std::cbrtf;
using std::fmaxf;
#define TRUE 1
#define FALSE 0
#define MAX(a,b) std::max((a),(b))
#define MIN(a,b) std::min((a),(b))
#define CLAMP(a,b,c) std::clamp<size_t>((a),(b),(c))
#define DT_OMP_FOR(...)
#define for_three_channels(c) for(int c=0;c<3;++c)
#define for_each_channel(c) for(int c=0;c<4;++c)
#define STR_YESNO(a) ((a)?"yes":"no")
#define DT_DEBUG_PIPE 1
#define DT_DEBUG_VERBOSE 2
#define DT_DEBUG_ALWAYS 4
#define DT_DEVICE_CPU 0
#define dt_print_pipe(...) ((void)0)
using gboolean=int;
using dt_hash_t=uint64_t;
using dt_aligned_pixel_t=float[4];
inline float fcube(float x) { return x*x*x; }
inline int fcol(int row,int col,uint32_t filters,const uint8_t (*)[6]) {
 if(filters==9 || filters==0) throw std::runtime_error("Bayer only");
 return (filters >> ((((row<<1)&14)+(col&1))*2)) & 3;
}
struct dt_iop_roi_t { int width,height,x=0,y=0; };
struct dt_iop_buffer_dsc_t { struct { gboolean enabled=TRUE; float coeffs[4]={1,1,1,1}; } temperature; };
struct dt_dev_wb_t {};
struct dt_dev_pixelpipe_t { dt_iop_buffer_dsc_t dsc; dt_dev_wb_t wb; int devid=0; };
struct dt_dev_pixelpipe_iop_t {
 dt_dev_pixelpipe_t* pipe;
 uint8_t xtrans[6][6]={};
 uint32_t filters=0x61616161;
};
struct dt_iop_highlights_gui_data_t {
 dt_hash_t opphash=0; gboolean oppclipped=FALSE,oppresult=FALSE; float oppchroma[4]={};
};
struct dt_iop_module_t {
 dt_iop_highlights_gui_data_t* gui_data=nullptr;
 struct Dev { gboolean gui_attached=FALSE; } state;
 Dev* dev=&state;
};
inline dt_hash_t _opposed_hash(dt_dev_pixelpipe_iop_t*) { return 0; }
inline gboolean dt_pipe_is_full(dt_dev_pixelpipe_t*) { return TRUE; }
inline gboolean dt_dev_wb_late_correction(const dt_dev_wb_t*,const dt_iop_buffer_dsc_t*) { return FALSE; }
inline void dt_dev_wb_get_rgb_correction(const dt_dev_wb_t*,gboolean,float* v) {
 for(int c=0;c<4;++c) v[c]=1;
}
inline void dt_iop_gui_enter_critical_section(dt_iop_module_t*) {}
inline void dt_iop_gui_leave_critical_section(dt_iop_module_t*) {}
inline char* dt_calloc_aligned(size_t n) { return static_cast<char*>(calloc(n,1)); }
inline char* dt_alloc_aligned(size_t n) { return static_cast<char*>(malloc(n)); }
inline float* dt_alloc_align_float(size_t n) { return static_cast<float*>(malloc(n*sizeof(float))); }
inline void dt_free_align(void* p) { free(p); }
inline void dt_iop_copy_image_roi(float* out,const float* in,int channels,
                                const dt_iop_roi_t* ri,const dt_iop_roi_t* ro) {
 for(int y=0;y<ro->height;++y)
  std::copy_n(in+((y+ro->y)*ri->width+ro->x)*channels,ro->width*channels,out+y*ro->width*channels);
}
'''

MAIN = r'''
int main(int argc,char** argv) {
 try {
  if(argc!=8) throw std::runtime_error("input output w h gainR gainG gainB");
  int w=std::stoi(argv[3]),h=std::stoi(argv[4]);
  if(w<32 || h<32 || w>20000 || h>20000) throw std::runtime_error("dimensions");
  std::vector<float> in(static_cast<size_t>(w)*h),out(in.size());
  std::ifstream input(argv[1],std::ios::binary);
  if(!input.read(reinterpret_cast<char*>(in.data()),in.size()*sizeof(float))) throw std::runtime_error("input");
  for(float v:in) if(!std::isfinite(v)) throw std::runtime_error("nonfinite");
  dt_dev_pixelpipe_t pipe;
  for(int c=0;c<3;++c) {
   pipe.dsc.temperature.coeffs[c]=std::stof(argv[5+c]);
   if(!(pipe.dsc.temperature.coeffs[c]>0)) throw std::runtime_error("WB");
  }
  dt_dev_pixelpipe_iop_t piece; piece.pipe=&pipe;
  dt_iop_module_t self;
  dt_iop_roi_t roi{w,h};
  _process_opposed(&self,&piece,in.data(),out.data(),&roi,&roi,FALSE,kDefaultClip);
  std::ofstream output(argv[2],std::ios::binary);
  if(!output.write(reinterpret_cast<char*>(out.data()),out.size()*sizeof(float))) throw std::runtime_error("output");
 } catch(const std::exception& e) { std::cerr<<e.what()<<std::endl;return 1; }
}
'''


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT/'tools'/filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def build(folder):
    folder.mkdir(parents=True, exist_ok=True)
    rt = load('opposed_extract', 'check-rt-defringe-reference.py')
    source, manifest = {}, {}
    for name in ('hlreconstruct/opposed.c', 'hlreconstruct/segbased.c', 'highlights.c'):
        path = folder/Path(name).name
        if not path.exists():
            path.write_bytes(urllib.request.urlopen(BASE+name, timeout=60).read())
        data = path.read_bytes()
        source[name] = data.decode()
        manifest[name] = {'url':BASE+name, 'sha256':hashlib.sha256(data).hexdigest()}
    # Read the original method's default threshold rather than inventing one.
    highlights = source['highlights.c']
    assert 'DT_IOP_HIGHLIGHTS_OPPOSED = 5' in highlights
    values = re.search(r'highlights_clip_magics\[6\] = \{([^}]+)\}', highlights)[1]
    clip = float(values.split(',')[5].strip().removesuffix('f'))
    assert clip == .987
    body = '\n'.join(rt.block(source['hlreconstruct/opposed.c'], marker) for marker in (
        'static inline size_t _raw_to_cmap(', 'static inline char _mask_dilated('))
    body += '\n'+rt.block(source['hlreconstruct/segbased.c'], 'static inline float _calc_refavg(')
    body += '\n'+rt.block(source['hlreconstruct/opposed.c'], 'static float *_process_opposed(')
    code = (source['highlights.c'][:source['highlights.c'].index('#include')]
            +ADAPTER+f'\nconstexpr float kDefaultClip={clip}f;\n'+body+MAIN)
    cpp = folder/'reference.cpp'
    cpp.write_text(code)
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    binary = folder/'reference'
    subprocess.run(['c++','-O2','-std=c++17',str(cpp),'-o',str(binary)],check=True)
    return binary, clip


def run(binary, mosaic, gains):
    assert mosaic.ndim == 2 and np.isfinite(mosaic).all()
    h,w = mosaic.shape
    with tempfile.TemporaryDirectory(prefix='filmkit-opposed-') as folder:
        src,dst = Path(folder)/'input.f32',Path(folder)/'output.f32'
        np.asarray(mosaic,'<f4').tofile(src)
        subprocess.run([str(binary.resolve()),str(src),str(dst),str(w),str(h),
                        *map(str,map(float,gains))],check=True)
        result = np.fromfile(dst,'<f4').reshape(h,w)
    assert np.isfinite(result).all()
    return result


def gpu_demosaic(mosaic, gains, shading):
    offline = load('opposed_gl', 'engine-shader-offline.py')
    post = load('opposed_draw', 'check-highlight-postprocess.py')
    h,w = mosaic.shape
    gl = offline.GL(w,h)
    shaders = offline.extract_shaders()
    draw = post.Draw(gl,shaders,post.Check())
    try:
        sparse = np.zeros((h,w,3),'f2')
        for y,x,c in ((0,0,1),(0,1,0),(1,0,2),(1,1,1)):
            sparse[y::2,x::2,c] = mosaic[y::2,x::2]
        mos = draw.texture(sparse,'f2')
        raw = gl.ctx.texture((1,1),1,np.zeros(1,'u2').tobytes(),dtype='u2')
        draw.resources.append(raw)
        common = {'uSize':(w,h),'uCfaLut':97,'uBlack':(64.,)*4,'uWhite':1023.,
                  'uGain':tuple(gains),'uMosaic':1.,'uCdFix':1.,'uGOrig':0.}
        arrays = gl.run(shaders['kFsRcdDir'],common,{'uRaw':raw,'uMos':mos})
        direction = draw.texture(arrays.astype('f2'),'f2')
        arrays = gl.run(shaders['kFsRcdG'],common,{'uRaw':raw,'uMos':mos,'uDir':direction})
        green = draw.texture(arrays.astype('f2'),'f2')
        lsc = draw.texture(shading.astype('f2'),'f2')
        mask = draw.texture(np.zeros((1,1,1),'f2'),'f2')
        source = (ROOT/'core/engine/src/render/FrameRenderer.cpp').read_text()
        vertex = re.search(r'const char\* kVertRot\s*=\s*R"\((.*?)\)";',source,re.S)[1]
        gl.vs_src = offline.to_core(vertex)
        output = gl.run(shaders['kFsRcdChroma'],
                        {**common,'uRot':1,'uScale':(1.,1.),'uST':tuple(np.eye(4).ravel()),
                         'uLscSize':(shading.shape[1],shading.shape[0]),'uMaskOn':0},
                        {'uRaw':raw,'uMos':mos,'uDir':direction,'uG':green,'uLsc':lsc,'uHlMask':mask},
                        size=(h,w))[...,:3]
        # Match the capture's rotated output geometry and LSC-before-half boundary.
        return output[::-1].astype('f2').astype('f4')
    finally:
        draw.close()
        gl.close()


def synthetic_controls(binary, rcd_binary, gains, clip):
    rcd = load('opposed_synthetic_rcd','check-rcd-reference.py')
    # Known RGB colours, not a claim that any real lamp region is neutral.
    rgb = np.full((128,128,3),.1,'f4')
    rgb[:,63:65] = (.5,.2,.4)
    control = np.empty(rgb.shape[:2],'f4')
    for y,x,c in ((0,0,1),(0,1,0),(1,0,2),(1,1,1)):
        control[y::2,x::2] = rgb[y::2,x::2,c]
    assert np.array_equal(run(binary,control,gains),control)
    assert np.max(control)<clip*min(gains)
    results = []
    yy,xx = np.mgrid[:512,:512]
    radius = np.hypot(xx-255.5,yy-255.5)
    band = np.abs(radius-150)<12
    for width in (2,4,8):
        # Supersample pixel area, then sample the appropriate Bayer phase.
        edge = np.zeros(radius.shape,'f4')
        for dy in (np.arange(8)+.5)/8-.5:
            for dx in (np.arange(8)+.5)/8-.5:
                r = np.hypot(xx+dx-255.5,yy+dy-255.5)
                edge += np.clip((150+width/2-r)/width,0,1)/64
        for peak in (.8,4.):
            scene = .05+(peak-.05)*edge
            mosaic = np.empty(scene.shape,'f4')
            for y,x,c in ((0,0,1),(0,1,0),(1,0,2),(1,1,1)):
                mosaic[y::2,x::2] = np.minimum(scene[y::2,x::2]/gains[c],1)*gains[c]
            corrected = run(binary,mosaic,gains)
            if peak==.8:
                assert np.array_equal(corrected,mosaic)
            for mode, image in (('baseline',mosaic),('opposed',corrected)):
                rgb = np.minimum(rcd.run(rcd_binary,image,8.),1)
                mag = np.maximum(np.minimum(rgb[...,0]-rgb[...,1],rgb[...,2]-rgb[...,1]),0)
                results.append({'width':width,'scene_peak':peak,'mode':mode,
                                'band_magenta_sum':float(mag[band].sum()),
                                'band_magenta_max':float(mag[band].max())})
    return {'known_pink_2px_unclipped_RAW_bit_identical':True,'neutral_circles':results,
            'scope':'Known neutral truth, 8x8 pixel-area integration, complete original CPU RCD. '
                    'No noise, optical CA, fusion, P0, P4, wavelet, CCM or LUT. '
                    'Reconstruction and original output-white bound are separate operations.'}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory',type=Path)
    ap.add_argument('--token',required=True)
    ap.add_argument('--output',type=Path,required=True)
    args = ap.parse_args()
    binary, clip = build(args.output/('darktable-'+REVISION))
    lamp = load('opposed_lamp','analyze-lampshade-capture.py')
    calibration = load('opposed_calibration','analyze-highlight-diagnostic.py')
    rcd = load('opposed_rcd','check-rcd-reference.py')
    rcd_binary = rcd.build(args.output/('rawtherapee-'+rcd.REVISION))
    neutral,lsc = calibration.dng_calibration(args.directory/'FILMKIT_20261117-182121.dng')
    gains = (neutral[1]/neutral).astype('f4')
    assert np.isclose(neutral[1],neutral.max())
    kv = lamp.capture_events(args.directory/'capture.log',args.token)[0]['kv']
    assert kv['white']==1023 and list(map(float,kv['black'].split(',')))==[64]*4
    ccm = np.array(list(map(float,kv['ccmStd'].split(','))),'f4').reshape(3,3)
    roi = (1300,1750,2000,2380)
    x0,y0,x1,y1 = roi
    shading = lamp.shading_crop(lsc,(4080,3072),roi)
    entries = []
    for index in (0,3):
        meta = json.loads((args.directory/'diagnostic-burst'/f'f{index}.meta').read_text())
        assert (meta['w'],meta['h'])==(4080,3072)
        raw = np.memmap(args.directory/'diagnostic-burst'/f'f{index}.raw','<u2',mode='r',
                        shape=(meta['h'],meta['stridePx']))[:,:meta['w']]
        mosaic = np.maximum((raw.astype('f4')-64)/959,0)
        thresholds = np.empty(mosaic.shape,'f4')
        for y,x,c in ((0,0,1),(0,1,0),(1,0,2),(1,1,1)):
            mosaic[y::2,x::2] *= gains[c]
            thresholds[y::2,x::2] = clip*gains[c]
        corrected = run(binary,mosaic,gains)
        assert np.array_equal(corrected[mosaic<thresholds],mosaic[mosaic<thresholds])
        assert np.all(corrected>=mosaic)
        entry = {'index':index,'raw_changed_count':int(np.count_nonzero(corrected!=mosaic)),
                 'unclipped_samples_unchanged':True,'cases':[]}
        for engine in ('production','original-CPU'):
            coords = None
            for mode, image in (('baseline',mosaic),('opposed',corrected)):
                if engine=='production':
                    rgb = gpu_demosaic(image,gains,lsc)
                    crop = rgb[y0:y1,x0:x1]
                else:
                    rgb = rcd.run(rcd_binary,image,8.)
                    crop = (np.rot90(rgb,-1)[y0:y1,x0:x1]*shading).astype('f2').astype('f4')
                # Same original output-white ceiling for a native single exposure.
                d4 = np.clip(np.minimum(crop,shading).astype('f2').astype('f4')@ccm.T,0,64).astype('f2').astype('f4')
                if coords is None:
                    coords = lamp.edge_coordinates(d4)
                label = f'f{index}-{engine}-{mode}'
                np.save(args.output/(label+'.npy'),d4)
                Image.fromarray(np.uint8(np.clip(lamp.display(d4),0,1)*255)).save(args.output/(label+'.png'))
                metric = lamp.edge_metric(d4,*coords)
                entry['cases'].append({'label':label,'edge':metric})
                if engine=='production' and mode=='baseline':
                    name = 'native-reference' if index==0 else 'native-short'
                    prior = np.load(args.directory/'stage-analysis'/f'replay-{name}-single-frame-ceiling-D4.npy')
                    delta = np.abs(prior-d4)
                    entry['baseline_prior_replay_abs_error_mean_p99_max'] = [float(delta.mean()),*np.quantile(delta,[.99,1]).tolist()]
                    assert delta.max()==0, 'native baseline must match the previous full shader replay'
                print(label,metric['magenta_positive_sum'],metric['magenta_peak_p50_p95_max'],flush=True)
                del rgb
        entries.append(entry)
    controls = synthetic_controls(binary,rcd_binary,gains,clip)
    report = {'scope':__doc__,'revision':REVISION,'clipval':clip,'WB':gains.tolist(),
              'frames':entries,'synthetic_controls':controls,
              'warning':'Each frame has its own exposure, geometry and baseline edge coordinates. '
                        'Do not compare sums across f0/f3 as burst quality. '
                        'Late-WB correction identity; no DT D65 reference-WB stage. '
                        'No claim of final preset reconstruction or real-device quality acceptance.'}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    main()
