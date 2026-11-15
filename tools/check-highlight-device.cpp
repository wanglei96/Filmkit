// Standalone Android arm64 GPU contract test; no app, camera, GBM or window.
// Build from the repository root (replace NDK/HOST with the installed paths):
// $NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android26-clang++ \
//   -std=c++17 -O2 tools/check-highlight-device.cpp -o /tmp/check-highlight-device \
//   -lEGL -lGLESv3 -llog
// adb push /tmp/check-highlight-device /data/local/tmp/check-highlight-device
// adb shell chmod 755 /data/local/tmp/check-highlight-device
// adb shell /data/local/tmp/check-highlight-device
// Optional extra real-driver compile/link check for every .frag in a directory:
// adb shell /data/local/tmp/check-highlight-device --shader-dir /data/local/tmp/device-production-shaders
// A failed numerical, compile/link, EGL or FBO check returns EXIT_FAILURE.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include "../core/engine/src/render/HighlightMergeShader.h"
#include "../core/engine/src/render/HighlightResolveShader.h"
#include "../core/engine/src/render/HighlightNoiseShader.h"

namespace {
constexpr int N = 16;
int checks = 0, failures = 0, draws = 0, readbacks = 0, productionLinks = 0;
std::vector<GLuint> textures, programs, fbos;
EGLDisplay display = EGL_NO_DISPLAY;
EGLContext context = EGL_NO_CONTEXT;
EGLSurface surface = EGL_NO_SURFACE;
GLuint vao = 0;
std::string renderer, version;

void require(bool condition, const std::string& what) {
    if (!condition) throw std::runtime_error(what);
}
void glCheck(const char* operation) {
    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        char message[256];
        std::snprintf(message, sizeof(message), "%s: GL error 0x%04x", operation, error);
        throw std::runtime_error(message);
    }
}
void result(const std::string& name, double error, double tolerance) {
    ++checks;
    const bool ok = std::isfinite(error) && error <= tolerance;
    failures += !ok;
    std::printf("%s %s max_error=%.9g tolerance=%.9g\n", ok ? "PASS" : "FAIL",
                name.c_str(), error, tolerance);
}
void errorAt(double& maximum, float actual, double expected) {
    if (!std::isfinite(actual)) maximum = INFINITY;
    else maximum = std::max(maximum, std::abs(double(actual) - expected));
}
GLuint shader(GLenum type, const char* source, const char* name) {
    GLuint object = glCreateShader(type);
    glShaderSource(object, 1, &source, nullptr);
    glCompileShader(object);
    GLint ok = 0, length = 0;
    glGetShaderiv(object, GL_COMPILE_STATUS, &ok);
    glGetShaderiv(object, GL_INFO_LOG_LENGTH, &length);
    if (length > 1) {
        std::vector<char> log(length);
        glGetShaderInfoLog(object, length, nullptr, log.data());
        std::printf("SHADER_LOG %s %s\n", name, log.data());
    }
    if (!ok) { glDeleteShader(object); throw std::runtime_error(std::string(name) + " compile failed"); }
    return object;
}
// Exact production kVertRot interface, including a sixteen-float identity uST.
constexpr const char* vertex = R"(#version 300 es
precision highp float;
out vec2 vUV0;
uniform mat4 uST;
uniform int uRot;
uniform vec2 uScale;
void main() {
    int id = gl_VertexID;
    vec2 p = vec2(float((id << 1) & 2), float(id & 2));
    vec2 c = p - 0.5;
    vec2 r = c;
    if (uRot == 1) r = vec2(-c.y, c.x);
    else if (uRot == 2) r = vec2(-c.x, -c.y);
    else if (uRot == 3) r = vec2(c.y, -c.x);
    vec2 uv = r * uScale + 0.5;
    vUV0 = (uST * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";
GLuint program(const char* fragment, const char* name) {
    GLuint vs = shader(GL_VERTEX_SHADER, vertex, "production-compatible vertex");
    GLuint fs = shader(GL_FRAGMENT_SHADER, fragment, name);
    GLuint p = glCreateProgram();
    programs.push_back(p);
    glAttachShader(p, vs); glAttachShader(p, fs); glLinkProgram(p);
    glDeleteShader(vs); glDeleteShader(fs);
    GLint ok = 0, length = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    glGetProgramiv(p, GL_INFO_LOG_LENGTH, &length);
    if (length > 1) {
        std::vector<char> log(length);
        glGetProgramInfoLog(p, length, nullptr, log.data());
        std::printf("LINK_LOG %s %s\n", name, log.data());
    }
    require(ok, std::string(name) + " link failed");
    std::printf("PASS compile/link %s\n", name);
    glCheck("compile/link");
    return p;
}
GLint uniform(GLuint p, const char* name) {
    GLint location = glGetUniformLocation(p, name);
    require(location >= 0, std::string("missing active uniform: ") + name);
    return location;
}
void ui(GLuint p, const char* name, int value) { glUniform1i(uniform(p, name), value); }
void uf(GLuint p, const char* name, float value) { glUniform1f(uniform(p, name), value); }
void u2i(GLuint p, const char* name, int x, int y) { glUniform2i(uniform(p, name), x, y); }
void u3(GLuint p, const char* name, float x, float y, float z) { glUniform3f(uniform(p, name), x, y, z); }
void u4(GLuint p, const char* name, float x, float y, float z, float w) { glUniform4f(uniform(p, name), x, y, z, w); }
void use(GLuint p, int rotation = 0) {
    glUseProgram(p);
    // Merge/resolve do not consume vUV0, so the linker may remove these uniforms.
    const GLint st = glGetUniformLocation(p, "uST");
    const GLint scale = glGetUniformLocation(p, "uScale");
    const GLint rot = glGetUniformLocation(p, "uRot");
    constexpr float identity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    if (st >= 0) glUniformMatrix4fv(st, 1, GL_FALSE, identity);
    if (scale >= 0) glUniform2f(scale, 1, 1);
    if (rot >= 0) glUniform1i(rot, rotation);
}
GLuint texture(GLenum internal, int w, int h, GLenum format, GLenum type,
               const void* data = nullptr, GLenum filter = GL_NEAREST) {
    GLuint t = 0; glGenTextures(1, &t); textures.push_back(t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data);
    glCheck("texture allocation/upload");
    return t;
}
void bind(GLuint p, const char* name, GLuint t, int unit) {
    glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, t); ui(p, name, unit);
}
GLuint target(const std::vector<GLuint>& attachments, int w, int h, const char* name) {
    GLuint f = 0; glGenFramebuffers(1, &f); fbos.push_back(f);
    glBindFramebuffer(GL_FRAMEBUFFER, f);
    std::vector<GLenum> buffers;
    for (size_t i = 0; i < attachments.size(); ++i) {
        GLenum attachment = GL_COLOR_ATTACHMENT0 + i;
        glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, attachments[i], 0);
        buffers.push_back(attachment);
    }
    glDrawBuffers(GLsizei(buffers.size()), buffers.data());
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    std::printf("FBO %s status=0x%04x attachments=%zu size=%dx%d\n", name, status, attachments.size(), w, h);
    require(status == GL_FRAMEBUFFER_COMPLETE, std::string(name) + " incomplete FBO");
    glViewport(0, 0, w, h); glCheck("FBO setup");
    return f;
}
void draw() {
    glDrawArrays(GL_TRIANGLES, 0, 3); glFinish(); glCheck("actual GPU draw/finish");
    ++draws;
}
std::vector<float> read(int attachment, int w, int h) {
    glReadBuffer(GL_COLOR_ATTACHMENT0 + attachment);
    GLint format = 0, type = 0;
    glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &format);
    glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &type);
    std::printf("READ attachment=%d implementation_format=0x%x type=0x%x requested=RGBA/FLOAT\n", attachment, format, type);
    std::vector<float> pixels(w * h * 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, pixels.data());
    glCheck("real floating-point readback");
    ++readbacks;
    return pixels;
}
void init() {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    require(display != EGL_NO_DISPLAY, "eglGetDisplay(EGL_DEFAULT_DISPLAY) failed");
    EGLint major = 0, minor = 0;
    require(eglInitialize(display, &major, &minor), "eglInitialize failed");
    require(eglBindAPI(EGL_OPENGL_ES_API), "eglBindAPI failed");
    constexpr EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config = nullptr; EGLint count = 0;
    require(eglChooseConfig(display, attributes, &config, 1, &count) && count == 1, "no ES3 pbuffer EGL config");
    constexpr EGLint pbuffer[] = {EGL_WIDTH, N, EGL_HEIGHT, N, EGL_NONE};
    surface = eglCreatePbufferSurface(display, config, pbuffer);
    require(surface != EGL_NO_SURFACE, "eglCreatePbufferSurface failed");
    constexpr EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    require(context != EGL_NO_CONTEXT, "eglCreateContext ES3 failed");
    require(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent failed");
    renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    std::printf("EGL %d.%d vendor=%s\nGL_VENDOR=%s\nGL_RENDERER=%s\nGL_VERSION=%s\nGLSL=%s\n",
        major, minor, eglQueryString(display, EGL_VENDOR), glGetString(GL_VENDOR),
        renderer.c_str(), version.c_str(), glGetString(GL_SHADING_LANGUAGE_VERSION));
    require(renderer.find("Mali") != std::string::npos, "expected a real Mali GPU, not a software/other renderer");
    GLint attachments = 0, drawBuffers = 0;
    glGetIntegerv(GL_MAX_COLOR_ATTACHMENTS, &attachments);
    glGetIntegerv(GL_MAX_DRAW_BUFFERS, &drawBuffers);
    std::printf("GL_MAX_COLOR_ATTACHMENTS=%d GL_MAX_DRAW_BUFFERS=%d\n", attachments, drawBuffers);
    require(attachments >= 2 && drawBuffers >= 2, "MRT requires two color attachments");
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glDisable(GL_BLEND); glDisable(GL_DITHER); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1); glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glCheck("ES3 initialization");
}
void mergeTests(GLuint p) {
    std::vector<uint16_t> reference(N*N, 60100), shortRaw(N*N, 60100);
    GLuint r0 = texture(GL_R16UI, N,N,GL_RED_INTEGER,GL_UNSIGNED_SHORT,reference.data());
    GLuint r1 = texture(GL_R16UI, N,N,GL_RED_INTEGER,GL_UNSIGNED_SHORT,shortRaw.data());
    const float zero4[] = {0,0,0,0};
    GLuint zero = texture(GL_RGBA32F,1,1,GL_RGBA,GL_FLOAT,zero4);
    const uint8_t one = 255, z = 0;
    GLuint mask = texture(GL_R8,N/4,N/4,GL_RED,GL_UNSIGNED_BYTE,
        std::vector<uint8_t>((N/4)*(N/4),one).data(),GL_LINEAR);
    GLuint linear = texture(GL_R8,1,1,GL_RED,GL_UNSIGNED_BYTE,&z,GL_LINEAR);
    GLuint color = texture(GL_R16F,N,N,GL_RED,GL_HALF_FLOAT);
    GLuint noise = texture(GL_RGBA32F,N,N,GL_RGBA,GL_FLOAT);
    target({color,noise},N,N,"merge R16F+RGBA32F MRT");
    use(p);
    bind(p,"uRaw0",r0,0); bind(p,"uRaw1",r1,1);
    bind(p,"uRaw2",r0,2); bind(p,"uRaw3",r0,3);
    bind(p,"uOff1",zero,4); bind(p,"uOff2",zero,5); bind(p,"uOff3",zero,6);
    bind(p,"uW",zero,7); bind(p,"uBentoFlow",zero,8);
    bind(p,"uBentoMask",mask,9); bind(p,"uLinearMask",linear,10);
    u2i(p,"uSize",N,N); glUniform2f(uniform(p,"uTiles"),1,1);
    u4(p,"uBlack",100,100,100,100); uf(p,"uWhite",60100);
    u3(p,"uGain",2,1,1.5); u4(p,"uFrameScale",1,4,1,1);
    u4(p,"uKernelSigma",1,1,1,1); u4(p,"uGlobalWeight",1,1,0,0);
    u3(p,"uNoiseShot",.002f,.003f,.004f); u3(p,"uNoiseRead",.0001f,.0002f,.0003f);
    u4(p,"uNoiseGainRatio",1,1,1,1);
    constexpr std::array<int,4> layouts = {0x61,0x94,0x49,0x16}; // GRBG first.
    constexpr double gain[] = {2,1,1.5};
    for (int lut : layouts) {
        ui(p,"uCfaLut",lut); ui(p,"uShortIndex",1);
        draw(); auto image = read(0,N,N); auto stats = read(1,N,N);
        double e = 0, noiseError = 0, shareError = 0, invalid = 0;
        for (int y=0;y<N;++y) for (int x=0;x<N;++x) {
            int i = 4*(y*N+x), phase=(x&1)+2*(y&1), channel=(lut>>(2*phase))&3;
            errorAt(e,image[i],4*gain[channel]);
            errorAt(shareError,stats[i+3],1);
            for (int c=0;c<3;++c) if (!std::isfinite(stats[i+c]) || stats[i+c]<0) invalid=INFINITY;
            errorAt(noiseError,stats[i],stats[i+1]*(4*gain[channel])+stats[i+2]);
        }
        char name[128]; std::snprintf(name,sizeof(name),"merge CFA=0x%x native WB no cap (R8/G4/B6)",lut);
        result(name,e,.002); result("merge physical variance=A*constant+B",noiseError,2e-6);
        result("merge accepted short support=1",shareError,2e-6);
        result("merge variance finite/nonnegative",invalid,0);
        if (lut==0x61) std::printf("MERGE_GRBG first_quad=[%.9g,%.9g;%.9g,%.9g] noise=[%.9g,%.9g,%.9g,%.9g]\n",
            image[0],image[4],image[N*4],image[N*4+4],stats[0],stats[1],stats[2],stats[3]);
    }
    // Physically neutral native short: R=.5, G=1, B=2/3; WB then scale=4.
    ui(p,"uCfaLut",0x61);
    for (int y=0;y<N;++y) for(int x=0;x<N;++x) {
        int c=(0x61>>(2*((x&1)+2*(y&1))))&3;
        shortRaw[y*N+x]=uint16_t(100 + (c==0?30000:(c==1?60000:40000)));
    }
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D,r1);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,N,N,GL_RED_INTEGER,GL_UNSIGNED_SHORT,shortRaw.data());
    draw(); auto neutral=read(0,N,N); double neutralError=0;
    for(int i=0;i<N*N;++i) errorAt(neutralError,neutral[4*i],4);
    result("merge GRBG native-neutral short rawG=1 -> WB RGB4",neutralError,.002);
    // Rejected whole frame must ignore even a stale M=1 texture.
    ui(p,"uShortIndex",-1); u4(p,"uGlobalWeight",1,0,0,0);
    draw(); auto rejected=read(0,N,N); auto rejectedStats=read(1,N,N);
    double rejectedError=0, rejectedShare=0;
    for(int y=0;y<N;++y) for(int x=0;x<N;++x) {
        int i=4*(y*N+x), c=(0x61>>(2*((x&1)+2*(y&1))))&3;
        errorAt(rejectedError,rejected[i],gain[c]); errorAt(rejectedShare,rejectedStats[i+3],0);
    }
    result("merge whole-short rejection ignores stale M1",rejectedError,.002);
    result("merge rejected short support=0",rejectedShare,0);
    // Accepted index with M=0 must use the reference, not the short.
    std::vector<uint8_t> zeros((N/4)*(N/4),0);
    glActiveTexture(GL_TEXTURE9); glBindTexture(GL_TEXTURE_2D,mask);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,N/4,N/4,GL_RED,GL_UNSIGNED_BYTE,zeros.data());
    ui(p,"uShortIndex",1); u4(p,"uGlobalWeight",1,1,0,0);
    draw(); auto m0=read(0,N,N); double m0Error=0;
    for(int y=0;y<N;++y) for(int x=0;x<N;++x) {
        int c=(0x61>>(2*((x&1)+2*(y&1))))&3;
        errorAt(m0Error,m0[4*(y*N+x)],gain[c]);
    }
    result("merge M0 accepted short contributes zero",m0Error,.002);
}
void resolveTests(GLuint p) {
    std::vector<float> source(N*N*4);
    for(int i=0;i<N*N;++i) { source[4*i]=8;source[4*i+1]=4;source[4*i+2]=6;source[4*i+3]=.375f; }
    GLuint input=texture(GL_RGBA16F,N,N,GL_RGBA,GL_FLOAT,source.data());
    const uint8_t one=255; GLuint mask=texture(GL_R8,1,1,GL_RED,GL_UNSIGNED_BYTE,&one,GL_LINEAR);
    const float unity[]={1,1,1,1}; GLuint lsc=texture(GL_RGBA32F,1,1,GL_RGBA,GL_FLOAT,unity);
    GLuint output=texture(GL_RGBA32F,N,N,GL_RGBA,GL_FLOAT);
    target({output},N,N,"post-demosaic RGB resolve"); use(p);
    bind(p,"uTex",input,0);bind(p,"uBentoMask",mask,1);bind(p,"uLsc",lsc,2);
    u2i(p,"uDstSize",N,N);u2i(p,"uRawSize",N,N);u2i(p,"uLscSize",1,1);ui(p,"uRot",0);
    u3(p,"uCameraWhite",.5f,1,2.f/3);u3(p,"uGain",2,1,1.5f);
    uf(p,"uReferenceScale",1);uf(p,"uShortScale",4);ui(p,"uHasShort",1);
    draw();auto image=read(0,N,N);double e=0,alpha=0;
    for(int i=0;i<N*N;++i) {
        for(int c=0;c<3;++c)errorAt(e,image[4*i+c],4);
        errorAt(alpha,image[4*i+3],.375);
    }
    std::printf("RESOLVE neutral=[%.9g,%.9g,%.9g,%.9g]\n",image[0],image[1],image[2],image[3]);
    result("resolve neutral sourceclip WB2/1/1.5 scale4 -> RGB4",e,2e-6);
    result("resolve preserves alpha",alpha,0);
    ui(p,"uHasShort",0);draw();image=read(0,N,N);e=0;
    for(int i=0;i<N*N;++i)for(int c=0;c<3;++c)errorAt(e,image[4*i+c],1);
    result("resolve rejected short ignores stale M1 -> reference ceiling",e,2e-6);
    for(int i=0;i<N*N;++i) {source[4*i]=.25f;source[4*i+1]=.5f;source[4*i+2]=.75f;}
    glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,input);
    glTexSubImage2D(GL_TEXTURE_2D,0,0,0,N,N,GL_RGBA,GL_FLOAT,source.data());
    draw();image=read(0,N,N);e=0;
    for(int i=0;i<N*N;++i)for(int c=0;c<4;++c)errorAt(e,image[4*i+c],source[4*i+c]);
    result("resolve below-ceiling color unchanged",e,0);
}
void noiseTests(GLuint p) {
    std::vector<float> map(N*N*4,0);
    constexpr int lut=0x61;
    for(int y=0;y<N;++y)for(int x=0;x<N;++x) {
        int phase=(x&1)+2*(y&1);
        map[4*(y*N+x)]=.01f*(phase+1)+.001f*(x/2)+.002f*(y/2);
        map[4*(y*N+x)+1]=-100; // A/B/share are NOT the actual variance input.
    }
    const float shading[]={1,1.1f,1.2f,1, 1.3f,1.4f,1.5f,1,
                           1.6f,1.7f,1.8f,1, 1.9f,2,2.1f,1};
    GLuint input=texture(GL_RGBA32F,N,N,GL_RGBA,GL_FLOAT,map.data());
    GLuint lsc=texture(GL_RGBA32F,2,2,GL_RGBA,GL_FLOAT,shading);
    GLuint diagonal=texture(GL_RGBA32F,N/2,N/2,GL_RGBA,GL_FLOAT);
    GLuint off=texture(GL_RGBA32F,N/2,N/2,GL_RGBA,GL_FLOAT);
    target({diagonal,off},N/2,N/2,"noise signed covariance RGBA32F MRT");
    use(p); bind(p,"uNoise",input,0);bind(p,"uLsc",lsc,1);
    u2i(p,"uRawSize",N,N);u2i(p,"uLscSize",2,2);ui(p,"uCfaLut",lut);
    constexpr float matrix[]={1.2f,-.1f,0, -.2f,1.1f,-.2f, 0,0,1.2f}; // column major
    glUniformMatrix3fv(uniform(p,"uCcm"),1,GL_FALSE,matrix);
    for(int rot=0;rot<4;++rot) {
        use(p,rot);draw();auto diag=read(0,N/2,N/2);auto cov=read(1,N/2,N/2);
        double e=0,finite=0;bool negativeRG=false,negativeGB=false;
        for(int y=0;y<N/2;++y)for(int x=0;x<N/2;++x) {
            const double cx=(x+.5)/(N/2)-.5,cy=(y+.5)/(N/2)-.5;
            double rx=cx,ry=cy;
            if(rot==1){rx=-cy;ry=cx;}else if(rot==2){rx=-cx;ry=-cy;}else if(rot==3){rx=cy;ry=-cx;}
            int px=std::clamp(int((rx+.5)*N),0,N-1), py=std::clamp(int((.5-ry)*N),0,N-1);
            int qx=(px/2)*2,qy=(py/2)*2;
            double v[3]={0,0,0};
            for(int phase=0;phase<4;++phase){
                int c=(lut>>(phase*2))&3;
                v[c]+=map[4*((qy+(phase>>1))*N+qx+(phase&1))]*(c==1?.5:1);
            }
            const double gx=(px+.5)/N*2-.5,gy=(py+.5)/N*2-.5;
            const int bx=int(std::floor(gx)),by=int(std::floor(gy));
            const double tx=gx-bx,ty=gy-by;
            for(int c=0;c<3;++c) {
                auto s=[&](int xx,int yy){return shading[4*(std::clamp(yy,0,1)*2+std::clamp(xx,0,1))+c];};
                double g=(s(bx,by)*(1-tx)+s(bx+1,by)*tx)*(1-ty)
                         +(s(bx,by+1)*(1-tx)+s(bx+1,by+1)*tx)*ty;
                v[c]*=g*g;
            }
            double sigma[3][3]={};
            for(int a=0;a<3;++a)for(int b=0;b<3;++b)for(int c=0;c<3;++c)
                sigma[a][b]+=double(matrix[3*c+a])*v[c]*matrix[3*c+b];
            const int i=4*(y*(N/2)+x);
            for(int c=0;c<3;++c){errorAt(e,diag[i+c],sigma[c][c]);if(!std::isfinite(diag[i+c])||diag[i+c]<0)finite=INFINITY;}
            errorAt(e,cov[i],sigma[0][1]);errorAt(e,cov[i+1],sigma[0][2]);errorAt(e,cov[i+2],sigma[1][2]);
            errorAt(e,diag[i+3],1);errorAt(e,cov[i+3],1);
            negativeRG|=cov[i]<0;negativeGB|=cov[i+2]<0;
        }
        char name[128];std::snprintf(name,sizeof(name),"noise rot%d UV/ST16 + spatial LSC + CCM six components",rot);
        result(name,e,2e-6);result("noise diagonal finite/nonnegative",finite,0);
        result("noise RG and GB signed covariance retained",negativeRG&&negativeGB?0:INFINITY,0);
        std::printf("NOISE rot=%d diag=[%.9g,%.9g,%.9g] cov=[%.9g,%.9g,%.9g]\n",
            rot,diag[0],diag[1],diag[2],cov[0],cov[1],cov[2]);
    }
}
void pc3dDrawTests(GLuint p) {
    constexpr int size = 32;
    std::vector<float> source(size*size*4);
    for (int i=0;i<size*size;++i) {
        source[4*i]=.2f; source[4*i+1]=.3f; source[4*i+2]=.4f; source[4*i+3]=1;
    }
    const float d[] = {.001f,.0015f,.002f,1};
    const float o[] = {-.00015f,.0001f,-.0002f,1};
    const float unity[] = {1,1,1,1};
    const uint8_t zero = 0;
    GLuint input=texture(GL_RGBA16F,size,size,GL_RGBA,GL_FLOAT,source.data());
    GLuint variance=texture(GL_RGBA32F,1,1,GL_RGBA,GL_FLOAT,d);
    GLuint covariance=texture(GL_RGBA32F,1,1,GL_RGBA,GL_FLOAT,o);
    GLuint lsc=texture(GL_RGBA32F,1,1,GL_RGBA,GL_FLOAT,unity);
    GLuint mask=texture(GL_R8,1,1,GL_RED,GL_UNSIGNED_BYTE,&zero);
    GLuint color=texture(GL_RGBA16F,size,size,GL_RGBA,GL_HALF_FLOAT);
    GLuint outDiag=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT);
    GLuint outOff=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT);
    target({color,outDiag,outOff},size,size,"production PC3D RGBA16F+2xRGBA32F three MRT");
    use(p);
    bind(p,"uTex",input,0); bind(p,"uOrig",input,1);
    bind(p,"uLocalVariance",variance,2); bind(p,"uLocalCovariance",covariance,3);
    bind(p,"uLsc",lsc,4); bind(p,"uSatMask",mask,5);
    u2i(p,"uSize",size,size); u2i(p,"uFullSize",size,size); u2i(p,"uLscSize",1,1);
    ui(p,"uK",2); uf(p,"uKernel",2); uf(p,"uSigA",.002f); uf(p,"uSigB",.0001f);
    uf(p,"uLuma",.8f); ui(p,"uMaskOn",0); ui(p,"uDown",1); ui(p,"uSatMaskOn",0);
    ui(p,"uLocalNoiseOn",1); uf(p,"uLocalNoiseScale",1);
    // Actual half-float upload values. Constant signal => all four weights equal,
    // ws=0, both PC3D branches reduce exactly to their seed/center observations.
    const double center[]={.199951171875,.300048828125,.39990234375};
    const double L[]={.25,.5,.25};
    const double C[3][3]={{d[0],o[0],o[1]},{o[0],d[1],o[2]},{o[1],o[2],d[2]}};
    const double t=.25*center[0]+.5*center[1]+.25*center[2];
    double J[3][3][3]={}, expected[3][3]={};
    for(int a=0;a<3;++a)for(int b=0;b<3;++b) {
        const double outer=center[a]/t*L[b];
        J[0][a][b]=outer*(1-double(.8f));
        J[1][a][b]=outer*double(.8f);
        J[2][a][b]=(a==b?1:0)-outer;
    }
    for(int a=0;a<3;++a)for(int b=0;b<3;++b)
        for(int branch=0;branch<3;++branch)for(int i=0;i<3;++i)for(int j=0;j<3;++j)
            expected[a][b]+=3*J[branch][a][i]*C[i][j]*J[branch][b][j];
    for(float moire : {0.f,1.5f}) {
        uf(p,"uMoire",moire);
        std::printf("PC3D_DRAW_BEGIN size=32 K=2 kernel=2 localNoise=1 moire=%.1f\n",moire);
        draw();
        auto image=read(0,size,size), diag=read(1,size,size), off=read(2,size,size);
        double colorError=0, covarianceError=0, invalid=0, psdViolation=0;
        bool signedOff=false;
        for(int n=0;n<size*size;++n) {
            const int k=4*n;
            for(int c=0;c<3;++c) {
                errorAt(colorError,image[k+c],center[c]);
                errorAt(covarianceError,diag[k+c],expected[c][c]);
                if(!std::isfinite(diag[k+c])||diag[k+c]<0||!std::isfinite(off[k+c])) invalid=INFINITY;
                signedOff|=off[k+c]<0;
            }
            errorAt(covarianceError,off[k],expected[0][1]);
            errorAt(covarianceError,off[k+1],expected[0][2]);
            errorAt(covarianceError,off[k+2],expected[1][2]);
            errorAt(colorError,image[k+3],1);
            errorAt(covarianceError,diag[k+3],1);errorAt(covarianceError,off[k+3],1);
            const double a=diag[k],b=diag[k+1],c=diag[k+2],xy=off[k],xz=off[k+1],yz=off[k+2];
            psdViolation=std::max({psdViolation,-(a*b-xy*xy),-(a*c-xz*xz),-(b*c-yz*yz),
                -(a*b*c+2*xy*xz*yz-a*yz*yz-b*xz*xz-c*xy*xy)});
        }
        result("production PC3D actual draw constant RGB preserved",colorError,.0005);
        result("production PC3D actual three-MRT covariance CPU Jacobian oracle",covarianceError,2e-6);
        result("production PC3D output covariance finite/nonnegative diagonal",invalid,0);
        result("production PC3D covariance PSD all principal minors",psdViolation,1e-10);
        result("production PC3D signed off-diagonal retained",signedOff?0:INFINITY,0);
        std::printf("PC3D_DRAW_RESULT moire=%.1f rgb=[%.9g,%.9g,%.9g] diag=[%.9g,%.9g,%.9g] off=[%.9g,%.9g,%.9g]\n",
            moire,image[0],image[1],image[2],diag[0],diag[1],diag[2],off[0],off[1],off[2]);
    }
}
void productionShaderTests(const std::string& directory) {
    DIR* dir = opendir(directory.c_str());
    require(dir != nullptr, "cannot open shader directory: " + directory);
    std::vector<std::string> names;
    while (dirent* entry = readdir(dir)) {
        std::string name(entry->d_name);
        if (name.size() > 5 && name.compare(name.size()-5, 5, ".frag") == 0)
            names.push_back(name);
    }
    closedir(dir);
    std::sort(names.begin(), names.end());
    require(!names.empty(), "shader directory contains no .frag files: " + directory);
    std::printf("PRODUCTION_SHADER_DIR %s count=%zu mode=real-driver-compile-link-plus-PC3D-draw\n", directory.c_str(), names.size());
    for (const std::string& name : names) {
        // Announce before entering the driver, so a driver crash identifies the source.
        std::printf("PRODUCTION_SHADER_BEGIN %s\n", name.c_str());
        try {
            std::ifstream stream(directory + "/" + name, std::ios::binary);
            require(stream.is_open(), "cannot read fragment shader: " + name);
            std::string source((std::istreambuf_iterator<char>(stream)), std::istreambuf_iterator<char>());
            require(!stream.bad() && !source.empty(), "empty/unreadable fragment shader: " + name);
            GLuint p = program(source.c_str(), name.c_str());
            GLint uniforms = 0, attributes = 0;
            glGetProgramiv(p, GL_ACTIVE_UNIFORMS, &uniforms);
            glGetProgramiv(p, GL_ACTIVE_ATTRIBUTES, &attributes);
            glCheck("production linked program introspection");
            ++productionLinks;
            std::printf("PRODUCTION_SHADER_LINKED %s bytes=%zu active_uniforms=%d active_attributes=%d\n",
                name.c_str(), source.size(), uniforms, attributes);
            result("production compile/link " + name, 0, 0);
            if (name == "kFsPc3d.frag") pc3dDrawTests(p);
        } catch (const std::exception& e) {
            std::printf("PRODUCTION_SHADER_ERROR %s %s\n", name.c_str(), e.what());
            result("production compile/link " + name, INFINITY, 0);
        }
    }
    // Only kFsPc3d gets the explicit draw fixture above; all other directory
    // entries are compile/link checks, NOT fabricated draw or output checks.
}
void cleanup() {
    if(context!=EGL_NO_CONTEXT) {
        for(GLuint p:programs)glDeleteProgram(p);
        glDeleteTextures(GLsizei(textures.size()),textures.data());
        glDeleteFramebuffers(GLsizei(fbos.size()),fbos.data());
        if(vao)glDeleteVertexArrays(1,&vao);
    }
    if(display!=EGL_NO_DISPLAY){
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        eglTerminate(display);
    }
}
std::string jsonString(const std::string& s) {
    std::string out="\"";
    for(unsigned char c:s){if(c=='"'||c=='\\')out+='\\';if(c>=32)out+=char(c);}
    return out+'"';
}
} // namespace
int main(int argc, char** argv) {
    std::setvbuf(stdout,nullptr,_IONBF,0);
    try {
        std::string shaderDirectory;
        if (argc == 3 && std::string(argv[1]) == "--shader-dir") shaderDirectory = argv[2];
        else require(argc == 1, "usage: check-highlight-device [--shader-dir DIRECTORY]");
        init();
        GLuint merge=program(filmkit::kFsHighlightMerge,"HighlightMergeShader.h");
        GLuint resolve=program(filmkit::kFsHighlightResolve,"HighlightResolveShader.h");
        GLuint noise=program(filmkit::kFsHighlightNoise,"HighlightNoiseShader.h");
        mergeTests(merge);resolveTests(resolve);noiseTests(noise);
        if (!shaderDirectory.empty()) productionShaderTests(shaderDirectory);
    } catch(const std::exception& e) {
        ++failures;
        std::printf("FATAL %s EGL_error=0x%04x\n",e.what(),eglGetError());
    }
    cleanup();
    std::printf("SUMMARY_JSON {\"passed\":%s,\"checks\":%d,\"failures\":%d,\"renderer\":%s,\"gl_version\":%s,\"real_gpu_draw_readback\":%s,\"draws\":%d,\"float_readbacks\":%d,\"production_shader_links\":%d}\n",
        failures==0?"true":"false",checks,failures,jsonString(renderer).c_str(),jsonString(version).c_str(),
        draws>0&&readbacks>0?"true":"false",draws,readbacks,productionLinks);
    return failures==0?EXIT_SUCCESS:EXIT_FAILURE;
}
