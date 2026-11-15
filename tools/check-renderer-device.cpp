// Real production FrameRenderer integration test: no camera, APK or private API.
// Build (NDK 29, API 26; run from repo root):
// NDK=$HOME/Library/Android/sdk/ndk/29.0.14206865
// $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++ \
//   -std=c++17 -O2 tools/check-renderer-device.cpp \
//   -Lapp/build/filmengine/arm64-v8a -lfilmengine -lEGL -lGLESv3 -llog \
//   -o /tmp/check-renderer-device
// adb shell mkdir -p /data/local/tmp/fkrender
// adb push /tmp/check-renderer-device app/build/filmengine/arm64-v8a/libfilmengine.so \
//   $NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
//   /data/local/tmp/fkrender/
// adb shell 'cd /data/local/tmp/fkrender && chmod 755 check-renderer-device && LD_LIBRARY_PATH=. ./check-renderer-device'
// Defaults: synthetic 256x256 HDR four-frame burst, production default FilmParams,
// RCD + DFT + full still pipeline. Optional: --plain (equal exposure), --no-dft,
// --size N, --rotation 0|1|2|3, --demosaic 0|1|2, --timer-only,
// --output PATH (default current directory).
// Exit failure on init, render, byte count, image-range, JPEG or file-write error.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>
#include "../core/engine/src/render/FrameRenderer.h"
#include "../core/engine/src/log/Logger.h"
#include "../core/engine/src/log/GpuTimer.h"
#include "../core/engine/src/jni/JpegEncode.h"

namespace {
void require(bool condition, const std::string& message) {
  if (!condition) throw std::runtime_error(message);
}
struct Egl {
  EGLDisplay display = EGL_NO_DISPLAY;
  EGLContext context = EGL_NO_CONTEXT;
  EGLSurface surface = EGL_NO_SURFACE;
  Egl() {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    require(display != EGL_NO_DISPLAY, "eglGetDisplay failed");
    EGLint major = 0, minor = 0;
    require(eglInitialize(display, &major, &minor), "eglInitialize failed");
    require(eglBindAPI(EGL_OPENGL_ES_API), "eglBindAPI failed");
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RED_SIZE, 8,
      EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config = nullptr;
    EGLint count = 0;
    require(eglChooseConfig(display, attributes, &config, 1, &count) && count == 1,
            "no ES3 pbuffer EGL config");
    const EGLint pbuffer[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    surface = eglCreatePbufferSurface(display, config, pbuffer);
    require(surface != EGL_NO_SURFACE, "eglCreatePbufferSurface failed");
    const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx);
    require(context != EGL_NO_CONTEXT, "eglCreateContext failed");
    require(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent failed");
    std::printf("EGL=%d.%d GL_VENDOR=%s GL_RENDERER=%s GL_VERSION=%s\n",
                major, minor, glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));
  }
  ~Egl() {
    if (display != EGL_NO_DISPLAY) {
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
      if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
      eglTerminate(display);
    }
  }
};
void save(const std::string& path, const void* bytes, size_t count) {
  FILE* file = std::fopen(path.c_str(), "wb");
  require(file != nullptr, "cannot open " + path);
  const size_t written = std::fwrite(bytes, 1, count, file);
  const bool closed = std::fclose(file) == 0;
  require(written == count && closed, "short write " + path);
}
void checkTimer() {
  bool clean = true;
  auto error = [&](const char* stage) {
    const GLenum code = glGetError();
    const bool legacy = std::string(stage) == "legacy_wrong_disjoint_0x8FBD";
    clean &= legacy ? code == GL_INVALID_ENUM : code == GL_NO_ERROR;
    std::printf("TIMER stage=%s glError=%u/0x%x\n", stage, code, code);
  };
  std::printf("TIMER actual_supported=%d\n", filmkit::GpuTimer::supported());
  error("actual.supported");
  {
    filmkit::GpuTimer timer; error("actual.constructor");
    timer.begin(); error("actual.begin");
    glClear(GL_COLOR_BUFFER_BIT); error("actual.work");
    const double ms = timer.endMs(); error("actual.endMs");
    std::printf("TIMER actual_ms=%.9f\n", ms);
  }
  error("actual.destructor");
  constexpr GLenum elapsed = GL_TIME_ELAPSED_EXT, disjoint = GL_GPU_DISJOINT_EXT;
  GLint wrongDisjoint = -1;
  glGetIntegerv(0x8FBD, &wrongDisjoint);
  error("legacy_wrong_disjoint_0x8FBD");
  std::printf("TIMER correct_GL_GPU_DISJOINT_EXT=0x%x\n", disjoint);
  using Gen = void (*)(GLsizei, GLuint*);
  using Begin = void (*)(GLenum, GLuint);
  using End = void (*)(GLenum);
  using Result = void (*)(GLuint, GLenum, uint64_t*);
  using Delete = void (*)(GLsizei, const GLuint*);
  auto gen = reinterpret_cast<Gen>(eglGetProcAddress("glGenQueriesEXT"));
  auto begin = reinterpret_cast<Begin>(eglGetProcAddress("glBeginQueryEXT"));
  auto end = reinterpret_cast<End>(eglGetProcAddress("glEndQueryEXT"));
  auto result = reinterpret_cast<Result>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
  auto del = reinterpret_cast<Delete>(eglGetProcAddress("glDeleteQueriesEXT"));
  require(gen && begin && end && result && del, "missing timer EXT entry point");
  for (int ext = 0; ext < 2; ++ext) {
    std::printf("TIMER dispatch=%s\n", ext ? "EXT" : "core");
    GLuint q = 0;
    if (ext) gen(1, &q); else glGenQueries(1, &q);
    error("gen");
    if (ext) begin(elapsed, q); else glBeginQuery(elapsed, q);
    error("begin");
    glClear(GL_COLOR_BUFFER_BIT); error("work");
    if (ext) end(elapsed); else glEndQuery(elapsed);
    error("end");
    glFinish(); error("finish");
    GLint dj = -1; glGetIntegerv(disjoint, &dj); error("get_disjoint");
    uint64_t ns = 0; result(q, GL_QUERY_RESULT, &ns); error("result64_EXT");
    std::printf("TIMER disjoint=%d ns=%llu\n", dj, static_cast<unsigned long long>(ns));
    if (ext) del(1, &q); else glDeleteQueries(1, &q);
    error("delete");
  }
  require(clean, "production timer or canonical EXT timer emitted a GL error");
}
void printLogs() {
  filmkit::Logger::instance().flush();
  std::printf("ENGINE_LOG_BEGIN\n%s\nENGINE_LOG_END\n",
              filmkit::Logger::instance().recentLines(500).c_str());
}
}
int run(int argc, char** argv) {
  std::setvbuf(stdout, nullptr, _IONBF, 0);
  try {
    int size = 256, demosaic = 1, rotation = 0;
    bool hdr = true, dft = true, timerOnly = false;
    std::string output = ".";
    for (int i = 1; i < argc; ++i) {
      const std::string arg = argv[i];
      if (arg == "--timer-only") timerOnly = true;
      else if (arg == "--plain") hdr = false;
      else if (arg == "--no-dft") dft = false;
      else if (arg == "--size" && i + 1 < argc) size = std::stoi(argv[++i]);
      else if (arg == "--demosaic" && i + 1 < argc) demosaic = std::stoi(argv[++i]);
      else if (arg == "--rotation" && i + 1 < argc) rotation = std::stoi(argv[++i]);
      else if (arg == "--output" && i + 1 < argc) output = argv[++i];
      else throw std::runtime_error("unknown or incomplete argument: " + arg);
    }
    require(size >= 128 && size <= 4096 && size % 32 == 0, "size must be a multiple of 32 in [128,4096]");
    require(demosaic >= 0 && demosaic <= 2, "demosaic must be 0, 1 or 2");
    require(rotation >= 0 && rotation <= 3, "rotation must be 0, 1, 2 or 3");
    require(filmkit::Logger::instance().init(output), "Logger::init failed (output directory must exist)");
    Egl egl;
    if (timerOnly) { checkTimer(); return EXIT_SUCCESS; }
    filmkit::FrameRenderer renderer;
    filmkit::FrameRenderer::setDiagStats(false);
    renderer.setStillContext(true);
    std::printf("STAGE renderer.init\n");
    const auto initStart = std::chrono::steady_clock::now();
    const bool initialized = renderer.init();
    std::printf("INIT ok=%d elapsed_ms=%.3f glError=0x%x\n", initialized,
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - initStart).count(), glGetError());
    if (!initialized) { printLogs(); return EXIT_FAILURE; }

    const float ev[4] = {0.f, 0.f, 2.2f, -1.5f};
    const float gain[4] = {1.f, 1.f, 1.f, 1.f};
    const float neutral[3] = {.5f, 1.f, .667f};
    const int grbg[4] = {1, 0, 2, 1};
    std::array<std::vector<uint16_t>, 4> raw;
    const void* frames[4];
    const int strides[4] = {size, size, size, size};
    for (int f = 0; f < 4; ++f) {
      raw[f].resize(size_t(size) * size);
      size_t clipped = 0;
      for (int y = 0; y < size; ++y) for (int x = 0; x < size; ++x) {
        const int channel = grbg[(y & 1) * 2 + (x & 1)];
        const float u = float(x) / (size - 1), v = float(y) / (size - 1);
        // Textured gradient plus a smooth bright patch: reference clips while
        // frame 3 remains valid. CFA-native exposure and WB are retained.
        float scene = .03f + .7f * (.65f * u + .35f * v);
        scene += .025f * std::sin(u * 72.f) * std::sin(v * 51.f);
        if (u > .36f && u < .72f && v > .30f && v < .69f)
          scene = 1.55f + .25f * u;
        const float tint[3] = {1.08f, 1.f, .94f};
        float signal = scene * tint[channel] * neutral[channel] * std::exp2(hdr ? ev[f] : 0.f);
        const int noise = ((x * 13 + y * 7 + f * 11) % 5) - 2;
        const int sample = std::lround(64.f + 959.f * signal) + noise;
        clipped += sample >= 1023;
        raw[f][size_t(y) * size + x] = uint16_t(std::clamp(sample, 64, 1023));
      }
      frames[f] = raw[f].data();
      save(output + "/synthetic-" + std::to_string(f) + ".raw", raw[f].data(), raw[f].size() * sizeof(uint16_t));
      std::printf("INPUT frame=%d size=%dx%d cfa=GRBG EV=%.3f clipped=%zu/%zu black=64 white=1023 neutral=.5,1,.667\n",
                  f, size, size, hdr ? ev[f] : 0.f, clipped, raw[f].size());
    }
    const float black[4] = {64, 64, 64, 64};
    const float ccm[9] = {1,0,0, 0,1,0, 0,0,1};
    const float lsc[12] = {1,1,1, 1,1,1, 1,1,1, 1,1,1};
    const float noise[6] = {0.0007f,0.0007f,0.0007f,0.000002f,0.000002f,0.000002f};
    const filmkit::FilmParams params;
    std::vector<uint8_t> rgba;
    std::printf("STAGE submitRawBurst hdr=%d dft=%d demosaic=%d diagStats=0 stillContext=1 engine_slot=%.1f tone_strength=%.1f\n",
                hdr, dft, demosaic, params.engine_slot, params.tone_strength);
    const auto start = std::chrono::steady_clock::now();
    const bool ok = renderer.submitRawBurst(frames, 4, strides, size, size, 1,
      black, 1023, neutral[0], neutral[1], neutral[2], ccm, lsc, 2, 2, rotation,
      params, demosaic, 1.f, noise, .015f, 1.f, dft, true, false, true,
      false, 1.f, hdr ? 0.f : -1.f, hdr ? ev : nullptr, hdr ? gain : nullptr,
      hdr ? 2 : -1, &rgba, false);
    std::printf("RENDER ok=%d bytes=%zu expected=%zu elapsed_ms=%.3f glError=0x%x scene=%dx%d\n",
      ok, rgba.size(), size_t(size) * size * 4,
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count(),
      glGetError(), renderer.sceneW(), renderer.sceneH());
    printLogs();
    require(ok && rgba.size() == size_t(size) * size * 4, "full-chain render failed or returned wrong byte count");
    uint8_t low = 255, high = 0;
    double sum = 0;
    for (size_t i = 0; i < rgba.size(); i += 4) for (int c = 0; c < 3; ++c) {
      low = std::min(low, rgba[i + c]); high = std::max(high, rgba[i + c]); sum += rgba[i + c];
    }
    std::printf("OUTPUT rgb_min=%u rgb_max=%u rgb_mean=%.3f\n", low, high, sum / (size_t(size) * size * 3));
    require(high > low && high > 0, "full-chain output is constant/black");
    save(output + "/render.rgba", rgba.data(), rgba.size());
    std::vector<uint8_t> jpeg;
    const bool encoded = filmkit::encodeJpegTurbo444(rgba.data(), size, size, 4, 100, &jpeg);
    std::printf("JPEG encoder=libjpeg-turbo444 available=%d ok=%d bytes=%zu\n",
                filmkit::jpegTurboAvailable(), encoded, jpeg.size());
    require(encoded && jpeg.size() > 4 && jpeg[0] == 0xff && jpeg[1] == 0xd8 &&
            jpeg[jpeg.size()-2] == 0xff && jpeg.back() == 0xd9, "JPEG encode or markers failed");
    save(output + "/render.jpg", jpeg.data(), jpeg.size());
    std::printf("PASS full production FrameRenderer RAW burst -> RGBA -> JPEG\n");
    return EXIT_SUCCESS;
  } catch (const std::exception& e) {
    std::printf("FAIL %s EGL_error=0x%x\n", e.what(), eglGetError());
    printLogs();
    return EXIT_FAILURE;
  }
}
int main(int argc, char** argv) {
  const int status = run(argc, argv);
  // All run() locals, including FrameRenderer and EGL, have been destroyed.
  // The production Android app SO statically embeds libc++; this CLI uses the
  // required libc++_shared. Normal process-global __cxa_finalize across both
  // runtimes double-frees a runtime static on this device (Scudo SIGABRT).
  // Android app process death never unloads these globals; model that here,
  // without hiding pipeline failures or skipping renderer/context teardown.
  std::fflush(nullptr);
  std::_Exit(status);
}
