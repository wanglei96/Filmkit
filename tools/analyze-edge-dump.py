#!/usr/bin/env python3
"""Summarize native edge_dump JSON/raw stages without applying an image pipeline.

Usage: python3 tools/analyze-edge-dump.py <directory> [--row N] [--col N]
Coordinates refer to each texture's native A orientation (GL bottom row first).
SDR/gainmap B orientation is flipped back to A for matching stage profiles.
"""
import argparse,json
from pathlib import Path
import numpy as np

def main():
 p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--row',type=int);p.add_argument('--col',type=int);a=p.parse_args()
 for path in sorted(a.directory.glob('*.json')):
  meta=json.loads(path.read_text());w,h=meta['w'],meta['h'];n=meta.get('components',4)
  raw=Path(meta.get('raw_path',str(path.with_suffix('.raw'))));raw=a.directory/raw.name
  dtype={'float16':'<f2','uint8':'u1'}[meta['type']]
  data=np.fromfile(raw,dtype=dtype)
  assert data.size==w*h*n,(path,data.size,w*h*n)
  data=data.reshape(h,w,n).astype('f4')
  if meta['type']=='uint8':data/=255
  # Output/gain textures are flipped by kFsOut relative to the scene texture.
  if meta.get('stage') in ('sdr-final','gainmap'):data=data[::-1]
  rgb=np.repeat(data,3,axis=2) if n==1 else data[:,:,:3]
  print(path.name,'stage=',meta.get('stage'),'shape=',rgb.shape,'range=',float(np.nanmin(rgb)),float(np.nanmax(rgb)))
  y=(rgb*np.array([.2627,.6780,.0593])).sum(-1)
  print('  Y p1/p50/p99',np.percentile(y,[1,50,99]).round(6).tolist())
  if a.row is not None:
   row=min(h-1,max(0,a.row));np.savetxt(path.with_suffix('.row.csv'),np.c_[np.arange(w),rgb[row],y[row]],delimiter=',',header='x,r,g,b,y',comments='')
  if a.col is not None:
   col=min(w-1,max(0,a.col));np.savetxt(path.with_suffix('.col.csv'),np.c_[np.arange(h),rgb[:,col],y[:,col]],delimiter=',',header='y,r,g,b,y',comments='')
if __name__=='__main__':main()
