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
// Timed production PC3D draws and diagnostic ablations (no app/camera required):
// adb shell /data/local/tmp/check-highlight-device --pc3d-bench /data/local/tmp/kFsPc3d.frag --size 1024 --repeats 3
// A failed numerical, compile/link, EGL or FBO check returns EXIT_FAILURE.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <chrono>
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
#include <utility>
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

// Diagnostic-only source variant: reuse the same six covariance components and
// pair matrices. No tap, inverse, regularization or propagation term is removed.
std::string cachedPc3d(std::string source) {
    const auto replace = [&](const std::string& from, const std::string& to) {
        const auto at = source.find(from);
        require(at != std::string::npos, "PC3D source contract changed: " + from);
        require(source.find(from, at + from.size()) == std::string::npos,
                "ambiguous PC3D source contract: " + from);
        source.replace(at, from.size(), to);
    };
    replace("float localDistance(vec3 delta, ivec2 p, ivec2 q, float extra) {\n"
            "    mat3 pair = localCovariance((vec2(p)+.5)/vec2(uSize)) +\n"
            "                localCovariance((vec2(q)+.5)/vec2(uSize));",
            "float localDistance(vec3 delta, mat3 pair, float extra) {");
    const std::string center = "    mat3 centerCovariance = uLocalNoiseOn != 0 ? localCovariance((vec2(xy)+.5)/vec2(uSize)) : mat3(0);\n";
    replace(center, "");
    replace("    vec3 gl = lscAt(xy);", center + "    vec3 gl = lscAt(xy);");
    replace("localDistance(c-cin,xy,qg,0.0)",
            "localDistance(c-cin,centerCovariance+localCovariance((vec2(qg)+.5)/vec2(uSize)),0.0)");
    std::string loads;
    for (int i = 0; i < 4; ++i) {
        const std::string n = std::to_string(i);
        const std::string actual = "                mat3 c" + n + " = localCovariance((vec2(q" + n + ")+.5)/vec2(uSize));\n";
        replace(actual, "");
        loads += "            mat3 c" + n + " = uLocalNoiseOn != 0 ? localCovariance((vec2(q" + n + ")+.5)/vec2(uSize)) : mat3(0);\n";
        for (const std::string extra : {"0.0", "extra"}) {
            replace("localDistance(cc" + n + "-cref,xy,q" + n + "," + extra + ")",
                    "localDistance(cc" + n + "-cref,centerCovariance+c" + n + "," + extra + ")");
        }
    }
    replace("            vec4 d = vec4(length(abs(cc0 - cref)), length(abs(cc1 - cref)),",
            loads + "            vec4 d = vec4(length(abs(cc0 - cref)), length(abs(cc1 - cref)),");
    return source;
}

std::string sharedPc3dDistance(std::string source) {
    const std::string from =
        "                vec4 dz = vec4(localDistance(cc0-cref,xy,q0,extra),\n"
        "                               localDistance(cc1-cref,xy,q1,extra),\n"
        "                               localDistance(cc2-cref,xy,q2,extra),\n"
        "                               localDistance(cc3-cref,xy,q3,extra));";
    const auto at=source.find(from);
    require(at!=std::string::npos,"PC3D dual-distance source contract changed");
    require(source.find(from,at+from.size())==std::string::npos,"ambiguous dual distance block");
    source.replace(at,from.size(),
        "                vec4 dz = dy;\n"
        "                if (extra > 0.0) {\n" + from.substr(from.find("vec4 dz")+5) + "\n                }");
    return source;
}

std::string symmetricPc3d(std::string source, int mode) {
    const std::string from = "    return max(dot(delta, inverse(pair) * delta),0.0);";
    const auto at = source.find(from);
    require(at != std::string::npos, "PC3D inverse source contract changed");
    const char* cofactor = R"GLSL(
    vec3 d = vec3(pair[0][0],pair[1][1],pair[2][2]);
    vec3 o = vec3(pair[1][0],pair[2][0],pair[2][1]);
    vec3 adjD = d.yzx*d.zxy - o.zyx*o.zyx;
    vec3 adjO = o.yxx*o.zzy - o*d.zyx;
    float determinant = d.x*adjD.x + o.x*adjO.x + o.y*adjO.y;
    vec3 solved = vec3(dot(vec3(adjD.x,adjO.x,adjO.y),delta),
                       dot(vec3(adjO.x,adjD.y,adjO.z),delta),
                       dot(vec3(adjO.y,adjO.z,adjD.z),delta)) / determinant;
    return max(dot(delta,solved),0.0);
)GLSL";
    const char* ordered = R"GLSL(
    vec3 d = vec3(pair[0][0],pair[1][1],pair[2][2]);
    vec3 o = vec3(pair[1][0],pair[2][0],pair[2][1]);
    vec3 adjD = d.yzx*d.zxy - o.zyx*o.zyx;
    vec3 adjO = o.yxx*o.zzy - o*d.zyx;
    float determinant = dot(vec3(d.x,o.x,o.y),vec3(adjD.x,adjO.x,adjO.y));
    adjD /= determinant;
    adjO /= determinant;
    mat3 inv = mat3(adjD.x,adjO.x,adjO.y,adjO.x,adjD.y,adjO.z,adjO.y,adjO.z,adjD.z);
    return max(dot(delta, inv*delta),0.0);
)GLSL";
    const char* cross = R"GLSL(
    vec3 c0 = cross(pair[1],pair[2]);
    vec3 c1 = cross(pair[2],pair[0]);
    vec3 c2 = cross(pair[0],pair[1]);
    mat3 inv = transpose(mat3(c0,c1,c2)) / dot(pair[0],c0);
    return max(dot(delta, inv*delta),0.0);
)GLSL";
    const char* solve = R"GLSL(
    float a = pair[0][0];
    float l10 = pair[1][0]/a, l20 = pair[2][0]/a;
    float b = pair[1][1] - l10*pair[1][0];
    float v21 = pair[2][1] - l20*pair[1][0];
    float l21 = v21/b;
    float c = pair[2][2] - l20*pair[2][0] - l21*v21;
    float y1 = delta.y - l10*delta.x;
    float y2 = delta.z - l20*delta.x - l21*y1;
    return max(delta.x*delta.x/a + y1*y1/b + y2*y2/c,0.0);
)GLSL";
    const char* pivoted = R"GLSL(
    vec3 d = vec3(pair[0][0],pair[1][1],pair[2][2]);
    vec3 o = vec3(pair[1][0],pair[2][0],pair[2][1]);
    if(d.y > d.x && d.y >= d.z) {d=d.yxz;o=o.xzy;delta=delta.yxz;}
    else if(d.z > d.x) {d=d.zyx;o=o.zyx;delta=delta.zyx;}
    float l10=o.x/d.x, l20=o.y/d.x;
    float b=d.y-l10*o.x, c=d.z-l20*o.y;
    float v=o.z-l20*o.x;
    if(c > b) {float temp=b;b=c;c=temp;temp=l10;l10=l20;l20=temp;delta=delta.xzy;}
    float l21=v/b;
    float y1=delta.y-l10*delta.x;
    float y2=delta.z-l20*delta.x-l21*y1;
    return max(delta.x*delta.x/d.x+y1*y1/b+y2*y2/(c-l21*v),0.0);
)GLSL";
    std::string branchless=pivoted;
    const auto replace=[&](const std::string& from,const std::string& to) {
        const auto at=branchless.find(from);
        require(at!=std::string::npos,"pivoted solve source changed");
        branchless.replace(at,from.size(),to);
    };
    replace("    if(d.y > d.x && d.y >= d.z) {d=d.yxz;o=o.xzy;delta=delta.yxz;}\n"
            "    else if(d.z > d.x) {d=d.zyx;o=o.zyx;delta=delta.zyx;}",
            "    bvec3 sy=bvec3(d.y>d.x && d.y>=d.z);\n"
            "    d=mix(d,d.yxz,sy);o=mix(o,o.xzy,sy);delta=mix(delta,delta.yxz,sy);\n"
            "    bvec3 sz=bvec3(d.z>d.x);\n"
            "    d=mix(d,d.zyx,sz);o=mix(o,o.zyx,sz);delta=mix(delta,delta.zyx,sz);");
    replace("    if(c > b) {float temp=b;b=c;c=temp;temp=l10;l10=l20;l20=temp;delta=delta.xzy;}",
            "    bool sc=c>b;vec2 bc=mix(vec2(b,c),vec2(c,b),bvec2(sc));\n"
            "    vec2 ls=mix(vec2(l10,l20),vec2(l20,l10),bvec2(sc));\n"
            "    b=bc.x;c=bc.y;l10=ls.x;l20=ls.y;delta=mix(delta,delta.xzy,bvec3(sc));");
    source.replace(at,from.size(),mode==1 ? solve : mode==2 ? ordered : mode==3 ? cross : mode==4 ? pivoted : mode==5 ? branchless.c_str() : cofactor);
    return source;
}

void pc3dMetricTests(const std::string& source) {
    constexpr int size=128;
    constexpr size_t count=size*size;
    std::vector<float> diag(count*4),off(count*4),delta(count*4);
    std::vector<double> expected(count);
    for(size_t k=0;k<count;++k) {
        const double angle=double(k)*.731;
        std::array<double,3> u={std::cos(angle)*.6,std::sin(angle)*.6,.8};
        std::array<double,3> v={-std::sin(angle),std::cos(angle),0};
        std::array<double,3> w={-.8*std::cos(angle),-.8*std::sin(angle),.6};
        const double scale=std::pow(10.,-10.+double(k%11));
        const double ratio=std::pow(10.,-double(k%8));
        double m[3][3];
        for(int i=0;i<3;++i) for(int j=0;j<3;++j)
            m[i][j]=scale*(u[i]*u[j]+ratio*v[i]*v[j]+ratio*ratio*w[i]*w[j]);
        for(int i=0;i<3;++i) diag[4*k+i]=float(m[i][i]);
        off[4*k]=float(m[0][1]);off[4*k+1]=float(m[0][2]);off[4*k+2]=float(m[1][2]);
        const float regularizer=std::max((diag[4*k]*2+diag[4*k+1]*2+diag[4*k+2]*2)*1e-6f,1e-12f);
        const double amp=std::sqrt(scale*(ratio*ratio+1e-6))*std::pow(10.,double(k%5)-2);
        for(int i=0;i<3;++i) delta[4*k+i]=float(w[i]*amp);
        double a[3][4];
        const float packed[3][3]={{diag[4*k],off[4*k],off[4*k+1]},
            {off[4*k],diag[4*k+1],off[4*k+2]},{off[4*k+1],off[4*k+2],diag[4*k+2]}};
        for(int i=0;i<3;++i) {
            for(int j=0;j<3;++j) a[i][j]=float(packed[i][j]*2+(i==j?regularizer:0.f));
            a[i][3]=delta[4*k+i];
        }
        // Independent double partial-pivot Gaussian solve of the float input.
        for(int i=0;i<3;++i) {
            int pivot=i;
            for(int j=i+1;j<3;++j) if(std::abs(a[j][i])>std::abs(a[pivot][i])) pivot=j;
            for(int j=0;j<4;++j) std::swap(a[i][j],a[pivot][j]);
            for(int j=i+1;j<3;++j) {
                const double f=a[j][i]/a[i][i];
                for(int c=i;c<4;++c) a[j][c]-=f*a[i][c];
            }
        }
        double x[3]={};
        for(int i=2;i>=0;--i) {
            double b=a[i][3];
            for(int j=i+1;j<3;++j) b-=a[i][j]*x[j];
            x[i]=b/a[i][i];
        }
        double distance=0;
        for(int i=0;i<3;++i) distance+=delta[4*k+i]*x[i];
        expected[k]=1./(1.+std::max(distance,0.));
    }
    GLuint d=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT,diag.data());
    GLuint o=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT,off.data());
    GLuint dt=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT,delta.data());
    GLuint out=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT);
    target({out},size,size,"isolated full matrix distance");
    const auto begin=source.find("uniform highp sampler2D uLocalVariance;");
    const auto end=source.find("mat3 pc3dOutputNoise(",begin);
    require(begin!=std::string::npos && end!=std::string::npos,"metric source extraction failed");
    const std::string metric="#version 300 es\nprecision highp float;\nlayout(location=0) out vec4 oColor;\nuniform ivec2 uSize;\nuniform highp sampler2D uDelta;\n"+
        source.substr(begin,end-begin)+R"GLSL(
void main(){
    ivec2 xy=ivec2(gl_FragCoord.xy);
    float distance=localDistance(texelFetch(uDelta,xy,0).rgb,xy,xy,0.0);
    oColor=vec4(1.0/(1.0+distance),distance,0,1);
})GLSL";
    const bool productionSolve=source.find("    return max(dot(delta, inverse(pair) * delta),0.0);")==std::string::npos;
    for(int mode=-1;mode<(productionSolve?0:6);++mode) {
        const std::string variant=mode<0?metric:symmetricPc3d(metric,mode);
        GLuint p=program(variant.c_str(),"isolated metric");
        use(p);bind(p,"uLocalVariance",d,0);bind(p,"uLocalCovariance",o,1);bind(p,"uDelta",dt,2);
        u2i(p,"uSize",size,size);uf(p,"uLocalNoiseScale",1);
        draw();auto got=read(0,size,size);
        double maxError=0,sum=0;size_t invalid=0;
        for(size_t k=0;k<count;++k) {
            const double error=std::abs(got[4*k]-expected[k]);
            if(!std::isfinite(got[4*k]) || !std::isfinite(got[4*k+1])) ++invalid;
            maxError=std::max(maxError,error);sum+=error*error;
        }
        std::printf("METRIC_JSON {\"mode\":%d,\"cases\":%zu,\"maxWeightError\":%.12g,\"rmsWeightError\":%.12g,\"nonfinite\":%zu}\n",
            mode,count,maxError,std::sqrt(sum/count),invalid);
        if(productionSolve || mode==4 || mode==5) {
            result("full pivoted metric finite",invalid,0);
            result("full pivoted metric vs double solve worst case",maxError,.005);
            result("full pivoted metric vs double solve RMS",std::sqrt(sum/count),.0006);
        }
        glDeleteProgram(p);
    }
}

void pc3dBenchmark(const std::string& path, int size, int repeats) {
    std::ifstream stream(path, std::ios::binary);
    require(stream.is_open(), "cannot read production PC3D source");
    std::string source((std::istreambuf_iterator<char>(stream)), std::istreambuf_iterator<char>());
    pc3dMetricTests(source);
    const GLuint original = program(source.c_str(), "production PC3D benchmark");
    const GLuint cached = program(cachedPc3d(source).c_str(), "covariance reuse experiment");
    const GLuint shared = program(sharedPc3dDistance(source).c_str(), "identical pair distance reuse experiment");
    const GLuint symmetric = program(symmetricPc3d(source,0).c_str(), "symmetric full cofactor experiment");
    const GLuint ldlt = program(symmetricPc3d(source,1).c_str(), "symmetric full LDLT experiment");
    const GLuint ordered = program(symmetricPc3d(source,2).c_str(), "symmetric full inverse ordered experiment");
    const GLuint cross = program(symmetricPc3d(source,3).c_str(), "full inverse cross experiment");
    const GLuint pivoted = program(symmetricPc3d(source,4).c_str(), "full pivoted LDLT experiment");
    const GLuint branchless = program(symmetricPc3d(source,5).c_str(), "full pivoted LDLT select experiment");
    std::string distanceOnly=source;
    for(const std::string name:{"oNoiseDiag","oNoiseOff"}) {
        const std::string from="out vec4 "+name+";";
        const auto at=distanceOnly.find(from);
        require(at!=std::string::npos,"missing covariance output declaration");
        const auto start=distanceOnly.rfind("layout(",at);
        require(start!=std::string::npos,"missing covariance output layout");
        distanceOnly.replace(start,at+from.size()-start,"vec4 "+name+";");
    }
    const GLuint metric=program(distanceOnly.c_str(),"diagnostic distances without output propagation");
    std::string propagationOnly=source;
    const auto function=propagationOnly.find("float localDistance(");
    const auto start=propagationOnly.find('{',function), end=propagationOnly.find("\n}",start);
    require(function!=std::string::npos && start!=std::string::npos && end!=std::string::npos,
            "missing localDistance body");
    propagationOnly.replace(start+1,end-start-1,"\n    return dot(delta,delta);");
    const GLuint propagation=program(propagationOnly.c_str(),"diagnostic propagation without pair metric");
    const size_t count = size_t(size) * size * 4;
    std::vector<float> image(count), diagonal(count), offDiagonal(count);
    std::vector<uint8_t> masks(size_t(size)*size);
    for (int y=0; y<size; ++y) for (int x=0; x<size; ++x) {
        const size_t k=(size_t(y)*size+x)*4;
        const float u=float(x)/size, v=float(y)/size;
        const float base=.02f+.7f*u+.02f*std::sin(float(x)*.37f)*std::cos(float(y)*.29f);
        image[k]=base; image[k+1]=base*(.8f+.2f*v); image[k+2]=base*(.7f+.15f*u); image[k+3]=1;
        if (x>size/2 && y>size/2) {
            const float t=float((x*13+y*7)%17)/16.f;
            image[k]=.02f+.9f*t; image[k+1]=.8f-.7f*t; image[k+2]=.05f+.45f*t;
        }
        const float n=.0001f+.001f*(.1f+u+v);
        diagonal[k]=n; diagonal[k+1]=n*1.3f; diagonal[k+2]=n*1.7f; diagonal[k+3]=1;
        offDiagonal[k]=-n*.15f; offDiagonal[k+1]=n*.1f; offDiagonal[k+2]=-n*.2f; offDiagonal[k+3]=1;
        masks[size_t(y)*size+x]=(x>size/2 && x<size/2+3) ? 255 : 0;
    }
    const float unity[]={1,1,1,1};
    const GLuint input=texture(GL_RGBA16F,size,size,GL_RGBA,GL_FLOAT,image.data());
    const GLuint variance=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT,diagonal.data());
    const GLuint covariance=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT,offDiagonal.data());
    const GLuint lsc=texture(GL_RGBA32F,1,1,GL_RGBA,GL_FLOAT,unity);
    const GLuint mask=texture(GL_R8,size,size,GL_RED,GL_UNSIGNED_BYTE,masks.data());
    const GLuint color=texture(GL_RGBA16F,size,size,GL_RGBA,GL_HALF_FLOAT);
    const GLuint outDiag=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT);
    const GLuint outOff=texture(GL_RGBA32F,size,size,GL_RGBA,GL_FLOAT);
    target({color,outDiag,outOff},size,size,"benchmark full-size three MRT");
    const GLenum buffers[]={GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1,GL_COLOR_ATTACHMENT2};
    const auto setup=[&](GLuint p, int local, float moire) {
        use(p);
        bind(p,"uTex",input,0); bind(p,"uOrig",input,1);
        bind(p,"uLocalVariance",variance,2); bind(p,"uLocalCovariance",covariance,3);
        bind(p,"uLsc",lsc,4); bind(p,"uSatMask",mask,5);
        u2i(p,"uSize",size,size); u2i(p,"uFullSize",size,size); u2i(p,"uLscSize",1,1);
        ui(p,"uK",5); uf(p,"uKernel",2.4f); uf(p,"uSigA",.002f); uf(p,"uSigB",.0001f);
        uf(p,"uLuma",.8f); ui(p,"uMaskOn",1); ui(p,"uDown",1); ui(p,"uSatMaskOn",1);
        ui(p,"uLocalNoiseOn",local); uf(p,"uLocalNoiseScale",1); uf(p,"uMoire",moire);
    };
    const auto striped=[&](int strips) {
        if (strips==1) { glDrawArrays(GL_TRIANGLES,0,3); ++draws; }
        else {
            glEnable(GL_SCISSOR_TEST);
            const int rows=(size+strips-1)/strips;
            for(int y=0;y<size;y+=rows) {
                glScissor(0,y,size,std::min(rows,size-y));
                glDrawArrays(GL_TRIANGLES,0,3); ++draws; glFlush();
            }
            glDisable(GL_SCISSOR_TEST);
        }
        glFinish(); glCheck("benchmark draw");
    };
    const auto measure=[&](const char* name,GLuint p,int local,int attachments,int strips,float moire) {
        setup(p,local,moire); glDrawBuffers(attachments,buffers);
        striped(strips); // Driver specialization/page commitment are excluded.
        std::vector<double> timings;
        for(int i=0;i<repeats;++i) {
            const auto start=std::chrono::steady_clock::now();
            striped(strips);
            timings.push_back(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count());
        }
        std::sort(timings.begin(),timings.end());
        const double median=(timings[(timings.size()-1)/2]+timings[timings.size()/2])*.5;
        std::printf("BENCH_JSON {\"variant\":\"%s\",\"size\":%d,\"localNoise\":%d,\"attachments\":%d,\"strips\":%d,\"moire\":%.1f,\"repeats\":%d,\"medianMs\":%.6f,\"minMs\":%.6f,\"maxMs\":%.6f}\n",
            name,size,local,attachments,strips,moire,repeats,median,timings.front(),timings.back());
    };
    for (float moire : {0.f,1.5f}) {
        measure("production",original,1,3,16,moire);
        std::array<std::vector<float>,3> expected={read(0,size,size),read(1,size,size),read(2,size,size)};
        const std::array<std::pair<const char*,GLuint>,8> candidates={{{"reuse_full_covariance",cached},{"reuse_identical_distance",shared},
            {"symmetric_full_cofactor",symmetric},{"symmetric_full_ldlt",ldlt},
            {"symmetric_full_ordered",ordered},{"full_cross_inverse",cross},{"full_pivoted_ldlt",pivoted},{"full_pivoted_ldlt_select",branchless}}};
        for(const auto& candidate:candidates) {
            measure(candidate.first,candidate.second,1,3,16,moire);
            for(int attachment=0;attachment<3;++attachment) {
                auto got=read(attachment,size,size);
                double maxError=0, maxScaled=0; size_t different=0;
                for(size_t k=0;k<count;++k) {
                    errorAt(maxError,got[k],expected[attachment][k]);
                    different+=std::memcmp(&got[k],&expected[attachment][k],sizeof(float))!=0;
                    const size_t px=(k/4)*4;
                    const double trace=std::max(double(expected[1][px]+expected[1][px+1]+expected[1][px+2]),1e-10);
                    // A signed off-diagonal may cross zero. Normalize by the
                    // full PSD covariance trace, not that single component.
                    errorAt(maxScaled,got[k]/trace,expected[attachment][k]/trace);
                }
                const bool exact = candidate.second==cached || candidate.second==shared;
                result(std::string(candidate.first)+" attachment "+std::to_string(attachment)+(exact?" bit equality":" numerical agreement"),
                    exact ? (different?INFINITY:maxError) : (attachment==0?maxError:maxScaled),exact?0:(attachment==0?1./2048:.0005));
                std::printf("EQUALITY_JSON {\"variant\":\"%s\",\"moire\":%.1f,\"attachment\":%d,\"values\":%zu,\"differentBits\":%zu,\"maxAbsError\":%.12g}\n",
                    candidate.first,moire,attachment,count,different,maxError);
            }
        }
        // Ablations below are measurements only, NEVER image-quality candidates.
        measure("diagnostic_single_attachment",original,1,1,16,moire);
        measure("diagnostic_metric_no_propagation",metric,1,1,16,moire);
        measure("diagnostic_propagation_no_pair_metric",propagation,1,3,16,moire);
        measure("diagnostic_global_noise",original,0,1,16,moire);
        measure("production",original,1,3,1,moire);
        measure("production",original,1,3,64,moire);
        measure("production_repeat",original,1,3,16,moire);
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
            if (name == "kFsPc3d.frag") pc3dMetricTests(source);
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
        std::string shaderDirectory, benchmarkPath;
        int benchmarkSize=512, benchmarkRepeats=3;
        if (argc == 3 && std::string(argv[1]) == "--shader-dir") shaderDirectory = argv[2];
        else if (argc >= 3 && std::string(argv[1]) == "--pc3d-bench") {
            benchmarkPath=argv[2];
            for(int i=3;i<argc;i+=2) {
                require(i+1<argc,"missing benchmark option value");
                if(std::string(argv[i])=="--size") benchmarkSize=std::stoi(argv[i+1]);
                else if(std::string(argv[i])=="--repeats") benchmarkRepeats=std::stoi(argv[i+1]);
                else require(false,"unknown benchmark option");
            }
            require(benchmarkSize>=256 && benchmarkSize<=4096 && benchmarkRepeats>=1 && benchmarkRepeats<=20,
                    "benchmark size must be [256,4096], repeats [1,20]");
        }
        else require(argc == 1, "usage: check-highlight-device [--shader-dir DIRECTORY | --pc3d-bench FILE [--size N] [--repeats N]]");
        init();
        if (!benchmarkPath.empty()) {
            pc3dBenchmark(benchmarkPath,benchmarkSize,benchmarkRepeats);
        } else {
            GLuint merge=program(filmkit::kFsHighlightMerge,"HighlightMergeShader.h");
            GLuint resolve=program(filmkit::kFsHighlightResolve,"HighlightResolveShader.h");
            GLuint noise=program(filmkit::kFsHighlightNoise,"HighlightNoiseShader.h");
            mergeTests(merge);resolveTests(resolve);noiseTests(noise);
            if (!shaderDirectory.empty()) productionShaderTests(shaderDirectory);
        }
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
