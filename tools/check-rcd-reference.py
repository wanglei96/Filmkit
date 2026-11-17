#!/usr/bin/env python3
"""Compile complete pinned RT RCD + border interpolation for same-input checks.

Only storage/progress/timer adapters are provided. Original algorithm bodies,
including all gradients, direction refinement, tiling and borders, are unchanged.
Positive global power-of-two HDR normalization avoids original LIM01 input loss;
two normalizations are compared, not claimed to be RT's full development pipeline.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request

import numpy as np
from PIL import Image

REVISION = '64c2d90a3248c7ad41842d9162368858b61aac6a'
BASE = f'https://raw.githubusercontent.com/RawTherapee/RawTherapee/{REVISION}/rtengine/'

PREFIX = r'''
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#define ALIGNED16 __attribute__((aligned(16)))
#define M(x) x
class StopWatch { public: explicit StopWatch(const char*) {} };
namespace Glib { struct ustring {
 static std::string compose(const char*,const char*) { return {}; }
}; }
namespace rtengine {
template<class T> inline T SQR(T x) { return x*x; }
inline float LIM01(float x) { return std::clamp(x,0.f,1.f); }
inline float intp(float a,float b,float c) { return a*(b-c)+c; }
template<class T> class array2D {
 std::vector<T> data;
 int w;
public:
 array2D(int width,int height):data(width*height),w(width) {}
 T* operator[](int y) { return data.data()+y*w; }
 const T* operator[](int y) const { return data.data()+y*w; }
};
struct Progress {
 void setProgressStr(const std::string&) {}
 void setProgress(double) {}
};
class RawImageSource {
public:
 int W,H,pattern[4];
 array2D<float> rawData,red,green,blue;
 Progress* plistener=nullptr;
 RawImageSource(int w,int h):W(w),H(h),rawData(w,h),red(w,h),green(w,h),blue(w,h) {}
 unsigned FC(int r,int c) const { return pattern[(r&1)*2+(c&1)]; }
 void rcd_demosaic(size_t,bool);
 void border_interpolate(int,int,int,const array2D<float>&,array2D<float>&,array2D<float>&,array2D<float>&);
 void igv_interpolate(int,int) { throw std::runtime_error("non-RGB CFA rejected"); }
};
}
'''

MAIN = r'''
int main(int argc,char** argv) {
 try {
  if(argc!=3) throw std::runtime_error("input output");
  std::ifstream input(argv[1],std::ios::binary);
  int header[6];
  if(!input.read(reinterpret_cast<char*>(header),sizeof(header))) throw std::runtime_error("header");
  int w=header[0],h=header[1];
  if(w<32 || h<32 || w>20000 || h>20000) throw std::runtime_error("dimensions");
  rtengine::RawImageSource source(w,h);
  int counts[3]={};
  for(int i=0;i<4;++i) {
   if(header[2+i]<0 || header[2+i]>2) throw std::runtime_error("CFA");
   source.pattern[i]=header[2+i];++counts[source.pattern[i]];
  }
  if(counts[0]!=1 || counts[1]!=2 || counts[2]!=1) throw std::runtime_error("Bayer");
  for(int y=0;y<h;++y) {
   if(!input.read(reinterpret_cast<char*>(source.rawData[y]),w*sizeof(float))) throw std::runtime_error("short input");
   for(int x=0;x<w;++x) {
    float& v=source.rawData[y][x];
    if(!std::isfinite(v) || v>1) throw std::runtime_error("normalization would lose positive HDR");
    v*=65536.f;
   }
  }
  source.rcd_demosaic(1,false);
  std::ofstream output(argv[2],std::ios::binary);
  for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
   float v[3]={source.red[y][x]/65536.f,source.green[y][x]/65536.f,source.blue[y][x]/65536.f};
   if(!output.write(reinterpret_cast<char*>(v),sizeof(v))) throw std::runtime_error("output");
  }
 } catch(const std::exception& e) { std::cerr<<e.what()<<std::endl;return 1; }
}
'''


def load(name,file):
    spec = importlib.util.spec_from_file_location(name,Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def build(folder):
    folder.mkdir(parents=True,exist_ok=True)
    source,manifest = {},{}
    for name in ('rcd_demosaic.cc','demosaic_algos.cc'):
        path=folder/name
        if not path.exists():
            path.write_bytes(urllib.request.urlopen(BASE+name,timeout=60).read())
        data=path.read_bytes()
        source[name]=data.decode()
        manifest[name]={'url':BASE+name,'sha256':hashlib.sha256(data).hexdigest()}
    rt=load('rcd_source_extract','check-rt-defringe-reference.py')
    algorithm=source['rcd_demosaic.cc']
    algorithm='\n'.join(line for line in algorithm.splitlines() if not line.startswith('#include'))
    border=rt.block(source['demosaic_algos.cc'],'void RawImageSource::border_interpolate(')
    code=PREFIX+algorithm+'\nnamespace rtengine {\n'+border+'\n}\n'+MAIN
    cpp=folder/'reference.cpp'
    cpp.write_text(code)
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    binary=folder/'reference'
    subprocess.run(['c++','-O2','-std=c++17',str(cpp),'-o',str(binary)],check=True)
    return binary


def run(binary,mosaic,normalization):
    h,w=mosaic.shape
    # Keep negative P0 samples: the unchanged original applies LIM01 internally.
    assert normalization>0 and np.isfinite(mosaic).all() and mosaic.max()<=normalization
    with tempfile.TemporaryDirectory(prefix='filmkit-rcd-reference-') as folder:
        src,dst=Path(folder)/'input.bin',Path(folder)/'output.bin'
        with src.open('wb') as stream:
            np.array([w,h,1,0,2,1],'<i4').tofile(stream)
            (mosaic/normalization).astype('<f4').tofile(stream)
        subprocess.run([str(binary.resolve()),str(src),str(dst)],check=True)
        result=np.fromfile(dst,'<f4').reshape(h,w,3)*normalization
    assert np.isfinite(result).all()
    return result


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('directory',type=Path)
    ap.add_argument('--token',required=True)
    ap.add_argument('--output',type=Path,required=True)
    args=ap.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    binary=build(args.output/('rawtherapee-'+REVISION))
    lamp=load('rcd_reference_lamp','analyze-lampshade-capture.py')
    calibration=load('rcd_reference_calibration','analyze-highlight-diagnostic.py')
    neutral,lsc=calibration.dng_calibration(args.directory/'FILMKIT_20261117-182121.dng')
    gains=neutral[1]/neutral
    assert np.isclose(neutral[1],neutral.max())
    p0,_=lamp.stage(args.directory,args.token,'mosaic-after-P0')
    h,w=p0.shape[:2]
    roi=(1300,1750,2000,2380)
    x0,y0,x1,y1=roi
    shading=lamp.shading_crop(lsc,(w,h),roi)
    metadata=json.loads((args.directory/'stage-analysis/upstream-replay/cpu/metadata.json').read_text())
    mask=np.fromfile(args.directory/'stage-analysis/upstream-replay/cpu/mask-quarter.r8','u1').reshape(metadata['mask_h'],metadata['mask_w'])/255
    share=lamp.raw_map_crop(mask,(w,h),roi)
    ceiling=shading*(1+share[...,None]*(metadata['scales'][3]-1))
    captured,_=lamp.stage(args.directory,args.token,'D4-CCM')
    captured=captured[::-1][y0:y1,x0:x1,:3].astype('f4')
    cols,rows=lamp.edge_coordinates(captured)
    event=lamp.capture_events(args.directory/'capture.log',args.token)[0]
    ccm=np.array(list(map(float,event['kv']['ccmStd'].split(','))),'f4').reshape(3,3)
    cases=[]
    mono=np.zeros((h,w),'f4')
    for py,px,c in ((0,0,1),(0,1,0),(1,0,2),(1,1,1)):
        mono[py::2,px::2]=p0[py::2,px::2,c]
    for normalization in (8.,16.):
        rgb=run(binary,mono,normalization)
        crop=(np.rot90(rgb,-1)[y0:y1,x0:x1]*shading).astype('f2').astype('f4')
        resolved=np.minimum(crop,ceiling).astype('f2').astype('f4')
        d4=np.clip(resolved@ccm.T,0,64).astype('f2').astype('f4')
        label=f'P0-reference-norm{normalization:g}'
        np.save(args.output/(label+'.npy'),d4)
        Image.fromarray(np.uint8(np.clip(lamp.display(d4),0,1)*255)).save(args.output/(label+'.png'))
        entry={'label':label,'edge':lamp.edge_metric(d4,cols,rows),'scope':'Full captured P0; rebuilt validated resolve mask; not fusion/preset replay'}
        cases.append(entry)
        print(label,entry['edge']['magenta_positive_sum'],entry['edge']['magenta_peak_p50_p95_max'],flush=True)
        del rgb
    report={'scope':__doc__,'revision':REVISION,'baseline':lamp.edge_metric(captured,cols,rows),
            'cases':cases,'input_WB_mosaic_max':float(mono.max()),
            'input_WB_mosaic_min':float(mono.min()),
            'input_negative_count':int(np.count_nonzero(mono<0)),
            'warning':'CPU original and GPU port differ in precision and epsilon/input normalization. '
                      'Original tile input LIM01 lower-clamps negative P0 samples; no external clamp added. '
                      'This comparison must precede attribution to an individual shader difference.'}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    main()
