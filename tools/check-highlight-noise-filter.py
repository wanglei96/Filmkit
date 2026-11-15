#!/usr/bin/env python3
"""Actual GPU six-cov down/guided/PC3D helpers vs independent matrix oracles.
Guided Jacobian oracle uses central finite differences, not shader derivatives.
Frozen PC3D weights and first-order nonlinear propagation are explicit bounds.
"""
from pathlib import Path
import re
import sys
import numpy as np
import moderngl

text=(Path(__file__).resolve().parents[1]/'core/engine/src/render/HighlightNoiseFilterShader.h').read_text()
def source(name):
    return re.search(name+r'\s*=\s*R"\((.*?)\)";',text,re.S).group(1)
window=None
if sys.platform=='darwin':
    import glfw
    assert glfw.init()
    for key,value in [(glfw.VISIBLE,glfw.FALSE),(glfw.CONTEXT_VERSION_MAJOR,4),(glfw.CONTEXT_VERSION_MINOR,1),(glfw.OPENGL_PROFILE,glfw.OPENGL_CORE_PROFILE),(glfw.OPENGL_FORWARD_COMPAT,glfw.TRUE)]:
        glfw.window_hint(key,value)
    window=glfw.create_window(32,32,'noise-filter-test',None,None)
    assert window
    glfw.make_context_current(window)
    ctx=moderngl.create_context()
else: ctx=moderngl.create_standalone_context(backend='egl',require=330)
vs='''#version 330 core
void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}'''
rng=np.random.default_rng(975)
def covs(h,w):
    a=rng.normal(0,.01,(h,w,3,3))
    return a@np.swapaxes(a,-1,-2)+np.eye(3)*1e-5

def pack(c):
    return np.stack([c[...,0,0],c[...,1,1],c[...,2,2]],-1),np.stack([c[...,0,1],c[...,0,2],c[...,1,2]],-1)
def gpu(shader,images,uniforms,size):
    prog=ctx.program(vertex_shader=vs,fragment_shader=shader.replace('#version 300 es','#version 330 core'))
    vao=ctx.vertex_array(prog,[]);textures=[]
    for unit,(name,array) in enumerate(images.items()):
        a=np.ascontiguousarray(array,dtype='f4')
        t=ctx.texture((a.shape[1],a.shape[0]),a.shape[2],a.tobytes(),dtype='f4')
        t.filter=(moderngl.NEAREST,moderngl.NEAREST);t.repeat_x=t.repeat_y=False
        t.use(unit);prog[name].value=unit;textures.append(t)
    for key,value in uniforms.items():prog[key].value=value
    targets=[ctx.texture(size,4,dtype='f4') for _ in range(2)]
    f=ctx.framebuffer(targets);f.use();ctx.viewport=(0,0,*size)
    ctx.disable(moderngl.BLEND);vao.render(moderngl.TRIANGLES,vertices=3)
    d,o=[np.frombuffer(f.read(attachment=i,components=4,dtype='f4'),dtype='f4').reshape(size[1],size[0],4)[...,:3] for i in range(2)]
    result=np.zeros((*d.shape[:2],3,3))
    for k in range(3):result[...,k,k]=d[...,k]
    for k,(i,j) in enumerate([(0,1),(0,2),(1,2)]):result[...,i,j]=result[...,j,i]=o[...,k]
    f.release();vao.release();prog.release()
    for t in textures+targets:t.release()
    return result

# Actual gaussdown pdf, including seed normalization, corners/duplicate clamp.
h,w=8,10
c=covs(h,w);d,o=pack(c)
cases=0
for scale in [2,3,4]:
    stride=max(1,scale//2);sigma=scale*.5/stride
    got=gpu(source('kFsHighlightNoiseDown'),{'uVariance':d,'uCovariance':o},{'uSize':(w,h),'uResize':scale,'uStride':stride,'uSigma':sigma},(w//scale,h//scale))
    expected=np.zeros_like(got)
    for y in range(h//scale):
        for x in range(w//scale):
            taps=[]
            for i in range(-2,3):
                for j in range(-2,3):
                    weight=(.39894/sigma)**2*np.exp(-.5*(i*i+j*j)/sigma**2)
                    taps.append((weight,c[np.clip(y*scale+j*stride,0,h-1),np.clip(x*scale+i*stride,0,w-1)]))
            ws=sum(v[0] for v in taps)+1e-5
            expected[y,x]=sum(v[0]*v[1] for v in taps)*(ws-1e-5)/ws**2
    np.testing.assert_allclose(got,expected,rtol=2e-5,atol=2e-9);cases+=1

# Guided fit independent finite-difference Jacobian for EACH stencil occurrence.
# Duplicates remain separate but arbitrarily correlated; Cauchy bound covers them.
h,w=5,6
L=np.ones(3)/3
for radius in [1,2,3,4]:
    lh,lw=max(1,h//radius),max(1,w//radius)
    low=rng.uniform(.05,.8,(lh,lw,3)).astype('f4')
    high=rng.uniform(.05,.8,(h,w,3)).astype('f4')
    cl=covs(lh,lw);ch=covs(h,w);dl,ol=pack(cl);dh,oh=pack(ch)
    got=gpu(source('kFsHighlightNoiseUp'),{'uLow':low,'uGuideHi':high,'uLowVariance':dl,'uLowCovariance':ol,'uGuideVariance':dh,'uGuideCovariance':oh},{'uLowSize':(lw,lh),'uHiSize':(w,h),'uR':radius},(w,h))
    for cy,cx in [(0,0),(h//2,w//2),(h-1,w-1)]:
        samples=[]
        for i in range(-radius,radius+1):
            for j in range(-radius,radius+1):
                px,py=np.clip([cx+i,cy+j],[0,0],[w-1,h-1])
                lx,ly=min(px//radius,lw-1),min(py//radius,lh-1)
                ww=np.exp(-(i*i+j*j)/(2*(.4*radius)**2))
                samples.append((ww,high[py,px].mean(),low[ly,lx].astype(float),ch[py,px],cl[ly,lx]))
        ww=np.array([s[0] for s in samples]);ww/=ww.sum()
        xx=np.array([s[1] for s in samples],dtype=float)
        yy=np.array([s[2] for s in samples],dtype=float)
        g=float(high[cy,cx].mean())
        def fitted(x,y,center):
            mx=ww@x;my=ww@y
            den=max(ww@(x*x)-mx*mx,0)+3e-4
            a=((ww*x)@y-mx*my)/den
            out=a*(center-mx)+my
            return out
        fit=fitted(xx,yy,g)
        outL=fit.mean();normalizer=max(outL,.25*g,1e-6)
        Jfit=np.eye(3)*(g/normalizer)
        Jg=fit/normalizer
        if outL>=max(.25*g,1e-6):Jfit-=np.outer(fit*g/normalizer**2,L)
        elif .25*g>=1e-6:Jg-=fit*.25*g/normalizer**2
        active=(fit*g/normalizer>0).astype(float)
        Jfit=active[:,None]*Jfit;Jg*=active
        eps=1e-6
        # Scalar beta can be obtained from any fitted channel's low perturbation.
        betas=[]
        for i in range(len(samples)):
            plus=yy.copy();minus=yy.copy();plus[i,0]+=eps;minus[i,0]-=eps
            betas.append((fitted(xx,plus,g)[0]-fitted(xx,minus,g)[0])/(2*eps))
        lowbound=Jfit@(sum(abs(b)*s[4] for b,s in zip(betas,samples))*sum(abs(b) for b in betas))@Jfit.T
        # Guide derivatives are vectors; choose k_i=||J_i|| for matrix Cauchy.
        derivatives=[]
        center_derivative=Jfit@(fitted(xx,yy,g+eps)-fitted(xx,yy,g-eps))/(2*eps)+Jg
        derivatives.append((center_derivative,L@ch[cy,cx]@L))
        for i,s in enumerate(samples):
            plus=xx.copy();minus=xx.copy();plus[i]+=eps;minus[i]-=eps
            der=Jfit@(fitted(plus,yy,g)-fitted(minus,yy,g))/(2*eps)
            derivatives.append((der,L@s[3]@L))
        kval=sum(np.linalg.norm(a) for a,_ in derivatives)
        guidebound=sum(np.outer(a,a)*v/np.linalg.norm(a) for a,v in derivatives if np.linalg.norm(a)>0)*kval
        expected=2*(lowbound+guidebound)
        np.testing.assert_allclose(got[cy,cx],expected,rtol=5e-3,atol=2e-7)
    cases+=1

# PC3D output helper: actual compiled GLSL vs finite-difference RGB Jacobians.
helper=source('kGlslPc3dOutputNoise')
shader='''#version 300 es
precision highp float;
layout(location=0) out vec4 oVariance;
layout(location=1) out vec4 oCovariance;
uniform vec3 uCenter;uniform vec3 uA;uniform vec3 uB;uniform float uMix;
'''+helper+'''
void main(){mat3 c=mat3(.03,.002,-.001,.002,.02,.003,-.001,.003,.04);
mat3 r=pc3dOutputNoise(uCenter,uA,uB,c,c*0.7,c*1.3,uMix);
oVariance=vec4(r[0][0],r[1][1],r[2][2],1);
oCovariance=vec4(r[1][0],r[2][0],r[2][1],1);}'''
base=np.array([[.03,.002,-.001],[.002,.02,.003],[-.001,.003,.04]])
LW=np.array([.25,.5,.25])
for mix in [0,.8,1]:
    center=np.array([.2,.3,.4]);A=np.array([.4,.25,.3]);B=np.array([.3,.4,.15])
    got=gpu(shader,{},dict(uCenter=tuple(center),uA=tuple(A),uB=tuple(B),uMix=mix),(1,1))[0,0]
    def f(v):
        x,a,b=v.reshape(3,3)
        return np.maximum(b/max(b@LW,1e-6)*((1-mix)*(x@LW)+mix*(a@LW)),0)
    flat=np.concatenate([center,A,B]);jac=np.zeros((3,9));eps=1e-6
    for i in range(9):
        plus=flat.copy();minus=flat.copy();plus[i]+=eps;minus[i]-=eps
        jac[:,i]=(f(plus)-f(minus))/(2*eps)
    expected=(1+(mix!=1)+(mix!=0))*sum(jac[:,3*i:3*i+3]@cov@jac[:,3*i:3*i+3].T for i,cov in enumerate([base*1.3,base,base*.7]))
    np.testing.assert_allclose(got,expected,rtol=2e-5,atol=1e-8);cases+=1
print(f'PASS actual GPU noise filters: {cases} cases; down full6cov; guided finite-difference full guide/low Jacobian; PC3D output Jacobian bounds')
ctx.release()
if window:glfw.destroy_window(window);glfw.terminate()
