#!/usr/bin/env python3
import numpy as np
y,x=np.mgrid[-120:121,-120:121]; r2=x*x+y*y; valid=r2<=24*24
w=np.where(valid,1/(r2+1),0.)
source=np.exp(-r2/1000).astype(np.float32)
out=(w*source).sum()/max(w.sum(),1e-9)
radial=[]
for r in range(25):
 m=(np.sqrt(r2)>=r-.5)&(np.sqrt(r2)<r+.5)&valid
 radial.append(float(w[m].mean()) if m.any() else 0)
assert np.all(np.diff(radial)<=1e-9)
assert np.max(np.abs(np.diff(radial[2:])))<.1
assert out>.5
print("continuous kernel monotonic/smooth PASS",round(out,6))
