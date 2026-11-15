#!/usr/bin/env python3
"""Run production defringe GPU stages on achromatic/magenta edge controls."""
import importlib.util
from pathlib import Path
import numpy as np
s=importlib.util.spec_from_file_location('offline',Path(__file__).with_name('engine-shader-offline.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

def main():
 gl=m.GL(128,64);sh=m.extract_shaders();ctx=gl.ctx
 def texture(a):
  t=ctx.texture((a.shape[1],a.shape[0]),4,a.astype('f2').tobytes(),dtype='f2');t.filter=(m.moderngl.NEAREST,)*2;t.repeat_x=t.repeat_y=False;return t
 def stages(a,legacy=False):
  t=texture(a)
  down=gl.run(sh['kFsDefringeDown'],{'uSize':(128,64)},{'uTex':t},size=(64,32));dt=texture(down)
  detect=sh['kFsDefringeDetect']
  if legacy:
   detect=detect.replace('float mag_n = min(n.z, n.y + n.z);','float mag_n = n.y;')
   detect=detect.replace('abs(mag0), base_std','abs(c0.y), base_std')
  det=gl.run(detect,{'uHalf':(64,32)},{'uSrc':dt},size=(64,32));mt=texture(det)
  cavg=float(np.mean(det[:,:,2]))
  out=gl.run(sh['kFsDefringeApply'],{'uHalf':(64,32),'uChromaAvg':max(cavg,1e-8),'uThreshK':.8,'uThreshMin':.012,'uThreshMax':.06,'uStdK':1.2,'uStdMax':.10,'uGradMin':8.},{'uTex':t,'uDet':mt})
  for x in [t,dt,mt]:x.release()
  return out,det
 a=np.ones((64,128,4),np.float32);a[:,:,:3]=.03;a[:,64:,:3]=2.
 a[:,62:64,:3]=(.6,.15,.6)
 old,od=stages(a,True);new,nd=stages(a)
 oldmag=np.minimum(old[:,62:64,0]-old[:,62:64,1],old[:,62:64,2]-old[:,62:64,1]).mean()
 newmag=np.minimum(new[:,62:64,0]-new[:,62:64,1],new[:,62:64,2]-new[:,62:64,1]).mean()
 assert oldmag>.4 and newmag<.07,(oldmag,newmag)
 weights=np.array([.2627,.6780,.0593]);before=(a[:,:,:3].astype('f2').astype('f4')*weights).sum(-1);after=(new[:,:,:3]*weights).sum(-1)
 assert np.max(abs(before-after))<.001
 print(f'R=B>G edge: old mag={oldmag:.4f}, corrected mag={newmag:.4f}, luminance preserved PASS')
 for name,rgb in [('neutral',(.6,.6,.6)),('warm',(.6,.3,.1)),('blue',(.1,.3,.6)),('green',(.1,.6,.2))]:
  c=a.copy();c[:,62:64,:3]=rgb;out,_=stages(c)
  assert np.array_equal(out[:,:,:3],c[:,:,:3].astype('f2').astype('f4')),name
  print(name,'unchanged PASS')
 gl.close()
if __name__=='__main__':main()
