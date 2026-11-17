#!/usr/bin/env python3
"""Run actual HighlightNoiseShader GLSL and independent full-covariance oracle.
Tests every native Bayer arrangement, all four rotations, RAW and RAW/2 output,
strong nonuniform per-channel LSC, signed CCM covariance, and two-green bound.
Requires the same numpy/moderngl/glfw environment as check-highlight-merge.py.
"""
from pathlib import Path
import re
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
text = (ROOT/'core/engine/src/render/HighlightNoiseShader.h').read_text()
shader = re.search(r'kFsHighlightNoise\s*=\s*R"\((.*?)\)";', text, re.S).group(1)
window = None
if sys.platform == 'darwin':
    import glfw
    if not glfw.init():
        raise RuntimeError('glfw.init failed')
    glfw.window_hint(glfw.VISIBLE, glfw.FALSE)
    glfw.window_hint(glfw.CONTEXT_VERSION_MAJOR, 4)
    glfw.window_hint(glfw.CONTEXT_VERSION_MINOR, 1)
    glfw.window_hint(glfw.OPENGL_PROFILE, glfw.OPENGL_CORE_PROFILE)
    glfw.window_hint(glfw.OPENGL_FORWARD_COMPAT, glfw.TRUE)
    window = glfw.create_window(32, 32, 'local-noise-regression', None, None)
    if not window:
        raise RuntimeError('glfw.create_window failed')
    glfw.make_context_current(window)
    ctx = moderngl.create_context()
else:
    ctx = moderngl.create_standalone_context(backend='egl', require=330)
vs = '''#version 330 core
out vec2 vUV0;
uniform int uRotation;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec2 source = p;
    if (uRotation == 90) source = vec2(p.y, 1.0-p.x);
    else if (uRotation == 180) source = 1.0-p;
    else if (uRotation == 270) source = vec2(1.0-p.y,p.x);
    // Avoid exact integer native-pixel boundaries in RAW/2 tests: GLSL
    // raster interpolation may round either side. A subpixel offset exercises
    // the identical texture-coordinate contract without assuming that tie.
    source += vec2(0.01 / 16.0, 0.01 / 12.0);
    vUV0 = vec2(source.x,1.0-source.y);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}'''
program = ctx.program(vertex_shader=vs, fragment_shader=shader.replace('#version 300 es','#version 330 core'))
vao = ctx.vertex_array(program,[])
rng=np.random.default_rng(1837)
w,h=16,12
noise=rng.uniform(.000001,.02,(h,w,4)).astype('f4')
# Garbage A/B makes accidentally consuming A*signal+B fail very obviously.
noise[:,:,1:]=1000
lsc=rng.uniform(1,5,(3,4,3)).astype('f4')
ccm=np.array([[1.7,-.5,-.2],[-.3,1.6,-.3],[-.1,-.7,1.8]],dtype='f4')
textures=[]
for unit,(name,array) in enumerate([('uNoise',noise),('uLsc',lsc)]):
    tex=ctx.texture((array.shape[1],array.shape[0]),array.shape[2],array.tobytes(),dtype='f4')
    tex.filter=(moderngl.NEAREST,moderngl.NEAREST)
    tex.repeat_x=tex.repeat_y=False
    tex.use(unit);program[name].value=unit;textures.append(tex)
program['uRawSize'].value=(w,h)
program['uLscSize'].value=(4,3)
program['uCcm'].write(ccm.T.copy().tobytes())
max_error=0
cases=0
for cfa in [0x94,0x61,0x49,0x16]:
    program['uCfaLut'].value=cfa
    for rotation in [0,90,180,270]:
        program['uRotation'].value=rotation
        for down in [1,2]:
            dw,dh=(h//down,w//down) if rotation%180 else (w//down,h//down)
            out=[ctx.texture((dw,dh),4,dtype='f4') for _ in range(2)]
            fbo=ctx.framebuffer(out);fbo.use();ctx.viewport=(0,0,dw,dh)
            ctx.disable(moderngl.BLEND)
            vao.render(moderngl.TRIANGLES,vertices=3)
            got=[np.frombuffer(fbo.read(attachment=i,components=4,dtype='f4'),dtype='f4').reshape(dh,dw,4) for i in range(2)]
            expected=np.zeros((dh,dw,3,3))
            for y in range(dh):
                for x in range(dw):
                    uv=np.array([(x+.5)/dw,(y+.5)/dh])
                    if rotation==90: uv=np.array([uv[1],1-uv[0]])
                    elif rotation==180: uv=1-uv
                    elif rotation==270: uv=np.array([1-uv[1],uv[0]])
                    uv += [.01/w,.01/h]
                    px,py=np.minimum((uv*[w,h]).astype(int),[w-1,h-1])
                    qx,qy=px//2*2,py//2*2
                    variance=np.zeros(3)
                    for phase in range(4):
                        ch=(cfa>>(2*phase))&3
                        variance[ch]+=noise[qy+phase//2,qx+phase%2,0]*(.5 if ch==1 else 1)
                    grid=(np.array([px+.5,py+.5])/[w,h])*[4,3]-.5
                    base=np.floor(grid).astype(int);frac=grid-base
                    gain=np.zeros(3)
                    for j in [0,1]:
                        for i in [0,1]:
                            ix,iy=np.clip(base+[i,j],[0,0],[3,2])
                            gain+=lsc[iy,ix]*(frac[0] if i else 1-frac[0])*(frac[1] if j else 1-frac[1])
                    expected[y,x]=ccm@np.diag(variance*gain**2)@ccm.T
            for i,j,k in [(0,0,0),(0,1,1),(0,2,2),(1,0,1),(1,0,2),(1,1,2)]:
                channel=([0,1,2].index(j) if i==0 else [(0,1),(0,2),(1,2)].index((j,k)))
                oracle=expected[:,:,j,k]
                actual=got[i][:,:,channel]
                np.testing.assert_allclose(actual,oracle,rtol=2e-5,atol=2e-7)
                max_error=max(max_error,float(np.max(abs(actual-oracle))))
            assert np.min(got[1][:,:,:3])<0, 'signed CCM covariance got lost'
            fbo.release()
            for target in out:target.release()
            cases+=1
# Cauchy upper bound used for unknown dual-green source overlap.
for v1,v2 in [(1,1),(1,9),(.001,.07)]:
    for rho in np.linspace(-1,1,101):
        exact=(v1+v2+2*rho*np.sqrt(v1*v2))/4
        assert exact <= (v1+v2)/2+1e-12
print(f'PASS actual GPU noise shader: {cases} CFA/rotation/resolution cases; max abs error {max_error:.3g}; signed 6cov, native LSC squared, green covariance bound')
# Profiled camera-RGB route: shot noise needs one additional LSC gain;
# read noise needs gain squared. Camera pixels are already WB/LSC corrected.
program['uInputMode'].value=1
shot=np.array([.0007,.0002,.0005],dtype='f4')
read=np.array([.000002,.000001,.000004],dtype='f4')
program['uNoiseA'].value=tuple(shot)
program['uNoiseB'].value=tuple(read)
for rotation in [0,90,180,270]:
    program['uRotation'].value=rotation
    dw,dh=(h,w) if rotation%180 else (w,h)
    camera=rng.uniform(.01,2,(dh,dw,3)).astype('f4')
    camtex=ctx.texture((dw,dh),3,camera.tobytes(),dtype='f4')
    camtex.use(2);program['uCameraRgb'].value=2
    out=[ctx.texture((dw,dh),4,dtype='f4') for _ in range(2)]
    fbo=ctx.framebuffer(out);fbo.use();ctx.viewport=(0,0,dw,dh)
    vao.render(moderngl.TRIANGLES,vertices=3)
    got=[np.frombuffer(t.read(),dtype='f4').reshape(dh,dw,4) for t in out]
    expected=np.zeros((dh,dw,3,3))
    for y in range(dh):
        for x in range(dw):
            uv=np.array([(x+.5)/dw,(y+.5)/dh])
            if rotation==90: uv=np.array([uv[1],1-uv[0]])
            elif rotation==180: uv=1-uv
            elif rotation==270: uv=np.array([1-uv[1],uv[0]])
            uv += [.01/w,.01/h]
            px,py=np.minimum((uv*[w,h]).astype(int),[w-1,h-1])
            grid=(np.array([px+.5,py+.5])/[w,h])*[4,3]-.5
            base=np.floor(grid).astype(int);frac=grid-base
            gain=np.zeros(3)
            for j in [0,1]:
                for i in [0,1]:
                    ix,iy=np.clip(base+[i,j],[0,0],[3,2])
                    gain+=lsc[iy,ix]*(frac[0] if i else 1-frac[0])*(frac[1] if j else 1-frac[1])
            expected[y,x]=ccm@np.diag(shot*gain*camera[y,x]+read*gain**2)@ccm.T
    for attachment,axes in [(0,[(0,0),(1,1),(2,2)]),(1,[(0,1),(0,2),(1,2)])]:
        for channel,(j,k) in enumerate(axes):
            np.testing.assert_allclose(got[attachment][:,:,channel],expected[:,:,j,k],rtol=2e-5,atol=2e-8)
    fbo.release();camtex.release()
    for t in out:t.release()
print('PASS profiled camera RGB: four rotations, native LSC shot/read propagation, full signed CCM covariance')
vao.release();program.release()
for tex in textures:tex.release()
ctx.release()
if window:
    glfw.destroy_window(window);glfw.terminate()
