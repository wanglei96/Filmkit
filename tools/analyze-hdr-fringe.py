#!/usr/bin/env python3
"""Locate SDR magenta/purple edge pixels and compare Ultra HDR gain."""
import io, math, sys
import numpy as np
from PIL import Image
# reuse parser
import importlib.util
s=importlib.util.spec_from_file_location('eval','tools/hdr-baseline-eval.py'); m=importlib.util.module_from_spec(s); s.loader.exec_module(m)

def run(path):
 data=open(path,'rb').read(); images,segs=m.scan_jpeg(data); sec=m.parse_mpf_secondary(data,segs,images)
 base=np.asarray(Image.open(io.BytesIO(data[images[0][0]:images[0][1]])).convert('RGB'),np.float32)/255
 gm=np.asarray(Image.open(io.BytesIO(data[sec[0]:sec[1]])).convert('L'),np.float32)/255
 gm=np.asarray(Image.fromarray((gm*255).astype('uint8')).resize((base.shape[1],base.shape[0]),Image.BILINEAR),np.float32)/255
 boost=2**(-2+4*gm)
 # SDR linear RGB and magenta score; use display RGB directly for locating visible color
 lin=np.where(base<=.04045,base/12.92,((base+.055)/1.055)**2.4)
 mag=np.minimum(base[...,0]-base[...,1],base[...,2]-base[...,1])
 y=.2126*lin[...,0]+.7152*lin[...,1]+.0722*lin[...,2]
 # high brightness, magenta, edge via gradient
 gx=np.abs(np.diff(y,axis=1,prepend=y[:,:1])); gy=np.abs(np.diff(y,axis=0,prepend=y[:1,:])); edge=np.hypot(gx,gy)
 mask=(mag>.04)&(y>.12)&(edge>.015)
 print(path, 'shape',base.shape,'candidate',mask.sum(),'of',mask.size)
 for name,arr in [('base8',base[...,0]*255),('mag',mag),('y',y),('boost',boost),('edge',edge)]:
  v=arr[mask]
  print(' ',name,'p50/p90/p99/max',*[round(float(x),4) for x in np.percentile(v,[50,90,99,100])] if v.size else '-')
 # matched non-mag bright edges
 other=(y>.12)&(edge>.015)&~mask
 print(' compare boost mag/nonmag p50 p90 p99',*[round(float(x),3) for x in np.percentile(boost[mask],[50,90,99])] if mask.any() else '-',*[round(float(x),3) for x in np.percentile(boost[other],[50,90,99])] if other.any() else '-')
 print(' top coords', [(int(y0),int(x0),round(float(base[y0,x0,0]*255),1),round(float(base[y0,x0,1]*255),1),round(float(base[y0,x0,2]*255),1),round(float(mag[y0,x0]),3),round(float(boost[y0,x0]),2)) for y0,x0 in zip(*np.where(mask)[0:])][:5])

for p in sys.argv[1:]: run(p)
