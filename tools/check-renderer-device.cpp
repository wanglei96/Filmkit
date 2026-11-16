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
// --output PATH (default current directory), --preview (synthetic full/scaled preview),
// --warmup (preallocate production still resources before the synthetic burst).
// --allocation-only (compare full-resolution mutable/immutable covariance storage).
// --covariance-cases (repeated low/mid/high-noise captures, PC3D off/replace/stack).
// --no-short (HDR with a physically clamped +0.662 EV fourth observation).
// --highlight-cases (no-short -> accepted short -> no-short -> plain -> single -> no-short).
// --raw-directory DIR (four fN.raw files and generated replay.txt calibration).
// --expect-source-white (assert production resolve event for each HDR capture).
// --edge-dump (save production diagnostic stages in the output directory).
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
#include <fstream>
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
  explicit Egl(int surfaceSize = 16) {
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
    const EGLint pbuffer[] = {EGL_WIDTH, surfaceSize, EGL_HEIGHT, surfaceSize, EGL_NONE};
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
void checkAllocation() {
  using Clock = std::chrono::steady_clock;
  auto ms = [](Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
  };
  for (int immutable : {0, 1, 1, 0}) {
    GLuint textures[7] = {}, fbo = 0;
    glGenTextures(7, textures);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    const auto batch = Clock::now();
    for (int i = 0; i < 7; ++i) {
      glBindTexture(GL_TEXTURE_2D, textures[i]);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
      glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
      const auto allocation = Clock::now();
      if (immutable) glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, 4080, 3072);
      else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4080, 3072, 0, GL_RGBA, GL_FLOAT, nullptr);
      const double allocationMs = ms(allocation);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[i], 0);
      require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "allocation FBO incomplete");
      const auto clear = Clock::now();
      glClearColor(.25f, .5f, .75f, 1.f);
      glClear(GL_COLOR_BUFFER_BIT);
      glFinish();
      const double clearMs = ms(clear);
      float pixel[4] = {};
      glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, pixel);
      require(pixel[0] == .25f && pixel[1] == .5f && pixel[2] == .75f && pixel[3] == 1.f,
              "allocation pixel mismatch");
      require(glGetError() == GL_NO_ERROR, "allocation GL error");
      std::printf("ALLOC immutable=%d texture=%d allocation_ms=%.3f clear_finish_ms=%.3f\n", immutable, i, allocationMs, clearMs);
    }
    std::printf("ALLOC_BATCH immutable=%d total_ms=%.3f bytes=%zu\n", immutable, ms(batch), size_t(4080)*3072*16*7);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(7, textures);
    glFinish();
  }
}
}
int run(int argc, char** argv) {
  std::setvbuf(stdout, nullptr, _IONBF, 0);
  try {
    int size = 256, demosaic = 1, rotation = 0;
    bool hdr = true, dft = true, timerOnly = false, allocationOnly = false, preview = false, warmup = false, covarianceCases = false;
    bool noShort = false, highlightCases = false, expectSourceWhite = false, edgeDump = false;
    std::string output = ".", rawDirectory;
    for (int i = 1; i < argc; ++i) {
      const std::string arg = argv[i];
      if (arg == "--timer-only") timerOnly = true;
      else if (arg == "--allocation-only") allocationOnly = true;
      else if (arg == "--covariance-cases") covarianceCases = true;
      else if (arg == "--highlight-cases") highlightCases = true;
      else if (arg == "--no-short") noShort = true;
      else if (arg == "--expect-source-white") expectSourceWhite = true;
      else if (arg == "--edge-dump") edgeDump = true;
      else if (arg == "--raw-directory" && i + 1 < argc) rawDirectory = argv[++i];
      else if (arg == "--preview") preview = true;
      else if (arg == "--warmup") warmup = true;
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
    require(!highlightCases || (!covarianceCases && rawDirectory.empty()), "highlight cases require synthetic input and no covariance cases");
    require(filmkit::Logger::instance().init(output), "Logger::init failed (output directory must exist)");
    Egl egl(preview ? size : 16);
    if (allocationOnly) { checkAllocation(); return EXIT_SUCCESS; }
    if (timerOnly) { checkTimer(); return EXIT_SUCCESS; }
    filmkit::FrameRenderer renderer;
    filmkit::FrameRenderer::setDiagStats(false);
    renderer.setStillContext(!preview);
    std::printf("STAGE renderer.init\n");
    const auto initStart = std::chrono::steady_clock::now();
    const bool initialized = renderer.init();
    std::printf("INIT ok=%d elapsed_ms=%.3f glError=0x%x\n", initialized,
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - initStart).count(), glGetError());
    if (!initialized) { printLogs(); return EXIT_FAILURE; }
    if (warmup) {
      require(!preview, "--warmup requires a still render");
      require(renderer.warmup(size, size, rotation), "still warmup failed");
      require(glGetError() == GL_NO_ERROR, "still warmup GL error");
    }

    if (preview) {
      filmkit::FrameRenderer::setToneEq(true);
      const float st[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
      for (int slot : {0, 1}) for (int scaled : {0, 1}) {
        filmkit::FilmParams params;
        params.engine_slot = static_cast<float>(slot);
        params.auto_black_pt = params.auto_white_pt = 1.f;
        filmkit::FrameRenderer::setPreviewScale(scaled ? .5f : 1.f);
        std::vector<uint8_t> rgba(size_t(size) * size * 4);
        for (int frame = 0; frame < 3; ++frame) {
          require(renderer.submitFrame(st, 0, 1.f, 1.f, size, size, params, true),
                  "synthetic preview submit failed");
          glReadPixels(0, 0, size, size, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
          require(glGetError() == GL_NO_ERROR, "synthetic preview GL error");
        }
        uint8_t low = 255, high = 0;
        for (size_t i = 0; i < rgba.size(); ++i) if ((i & 3) != 3) {
          low = std::min(low, rgba[i]); high = std::max(high, rgba[i]);
        }
        require(high > low && high > 0, "synthetic preview constant/black");
        save(output + "/preview-" + std::to_string(slot) + "-" +
             std::to_string(scaled) + ".rgba", rgba.data(), rgba.size());
        std::printf("PREVIEW engine=%d scaled=%d frames=3 min=%u max=%u bytes=%zu\n",
                    slot, scaled, low, high, rgba.size());
      }
      std::printf("PASS full/scaled preview, both engines, full current-frame statistics\n");
      return EXIT_SUCCESS;
    }

    float ev[4] = {0.f, 0.f, 2.2f, noShort ? .662f : -1.5f};
    float gain[4] = {1.f, 1.f, 1.f, 1.f};
    float neutral[3] = {.5f, 1.f, .667f};
    float black[4] = {64, 64, 64, 64};
    float ccm[9] = {1,0,0, 0,1,0, 0,0,1};
    float noise[6] = {0.0007f,0.0007f,0.0007f,0.000002f,0.000002f,0.000002f};
    int width = size, height = size, cfa = 1, white = 1023, lscCols = 2, lscRows = 2;
    int strides[4] = {size, size, size, size};
    float noiseStrength = 1.f;
    std::vector<float> lsc(12, 1.f);
    if (!rawDirectory.empty()) {
      std::ifstream input(rawDirectory + "/replay.txt");
      std::string magic;
      input >> magic >> width >> height >> cfa >> white >> rotation >> noiseStrength;
      require(magic == "FILMKIT_RAW_REPLAY_1", "bad replay calibration header");
      for (int& value : strides) input >> value;
      for (float& value : black) input >> value;
      for (float& value : neutral) input >> value;
      for (float& value : ccm) input >> value;
      for (float& value : noise) input >> value;
      for (float& value : ev) input >> value;
      for (float& value : gain) input >> value;
      input >> lscCols >> lscRows;
      require(width >= 32 && width <= 8192 && height >= 32 && height <= 8192 &&
              cfa >= 0 && cfa <= 3 && white > 64 && rotation >= 0 && rotation <= 3 &&
              lscCols >= 1 && lscCols <= 256 && lscRows >= 1 && lscRows <= 256,
              "invalid replay dimensions/calibration");
      for (int stride : strides)
        require(stride >= width && stride <= 16384, "invalid replay stride");
      lsc.resize(size_t(lscCols) * lscRows * 3);
      for (float& value : lsc) input >> value;
      require(bool(input), "truncated replay calibration");
    }
    const int grbg[4] = {1, 0, 2, 1};
    std::array<std::vector<uint16_t>, 4> raw;
    const void* frames[4];
    filmkit::FilmParams params;
    const float shotCases[] = {.0007f, .000004f, .00008f, .00008f, .000004f, .0007f, .000004f, .0007f};
    const float pc3dCases[] = {1.f, 1.f, 1.f, 2.f, 0.f, 2.f, 1.f, 1.f};
    for (int capture = 0; capture < (covarianceCases ? 8 : highlightCases ? 6 : 1); ++capture) {
    const bool single = highlightCases && capture == 4;
    if (highlightCases) {
      hdr = capture != 3 && !single;
      ev[3] = capture == 1 ? -1.5f : .662f;
      // Exercise retained resources across aspect ratio and RAW/output changes.
      width = capture == 2 ? size + 32 : size;
      height = size;
      for (int& stride : strides) stride = width;
      std::printf("HIGHLIGHT_CASE capture=%d hdr=%d single=%d short_ev=%.3f size=%dx%d\n",
                  capture, hdr, single, ev[3], width, height);
    }
    for (int f = 0; f < 4; ++f) {
      raw[f].resize(size_t(strides[f]) * height);
      if (!rawDirectory.empty()) {
        require(strides[f] >= width && strides[f] <= 16384, "invalid replay stride");
        std::ifstream input(rawDirectory + "/f" + std::to_string(f) + ".raw", std::ios::binary);
        input.read(reinterpret_cast<char*>(raw[f].data()), raw[f].size() * sizeof(uint16_t));
        require(bool(input), "truncated replay RAW frame");
        frames[f] = raw[f].data();
        continue;
      }
      size_t clipped = 0;
      for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) {
        const int channel = grbg[(y & 1) * 2 + (x & 1)];
        const float u = float(x) / (width - 1), v = float(y) / (height - 1);
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
        raw[f][size_t(y) * strides[f] + x] = uint16_t(std::clamp(sample, 64, 1023));
      }
      frames[f] = raw[f].data();
      save(output + "/synthetic-" + std::to_string(f) + ".raw", raw[f].data(), raw[f].size() * sizeof(uint16_t));
      std::printf("INPUT frame=%d size=%dx%d cfa=GRBG EV=%.3f clipped=%zu/%zu black=64 white=1023 neutral=.5,1,.667\n",
                  f, width, height, hdr ? ev[f] : 0.f, clipped, raw[f].size());
    }
    if (covarianceCases) {
      for (int c = 0; c < 3; ++c) noise[c] = shotCases[capture];
      params.pc3d = pc3dCases[capture];
      std::printf("CASE capture=%d noiseShot=%.9f pc3d=%.1f\n", capture, noise[0], params.pc3d);
    }
    std::vector<uint8_t> rgba;
    auto resolveCount = [&]() {
      const auto log = filmkit::Logger::instance().recentLines(1000);
      size_t count = 0, pos = 0;
      while ((pos = log.find("\"event\":\"highlight.resolve\"", pos)) != std::string::npos) {
        ++count; ++pos;
      }
      return count;
    };
    const auto resolvesBefore = resolveCount();
    if (edgeDump) save(output + "/edge_dump.enable", "", 0);
    std::printf("STAGE submitRawBurst hdr=%d dft=%d demosaic=%d diagStats=0 stillContext=1 engine_slot=%.1f tone_strength=%.1f\n",
                hdr, dft, demosaic, params.engine_slot, params.tone_strength);
    const auto start = std::chrono::steady_clock::now();
    const bool ok = single ? renderer.submitRaw(frames[0], width, height, strides[0], cfa,
      black, white, neutral[0], neutral[1], neutral[2], ccm, lsc.data(), lscCols, lscRows,
      rotation, params, demosaic, noiseStrength, noise, .015f, false, &rgba, false)
      : renderer.submitRawBurst(frames, 4, strides, width, height, cfa,
      black, white, neutral[0], neutral[1], neutral[2], ccm, lsc.data(), lscCols, lscRows, rotation,
      params, demosaic, noiseStrength, noise, .015f, 1.f, dft, true, false, true,
      false, 1.f, hdr ? 0.f : -1.f, hdr ? ev : nullptr, hdr ? gain : nullptr,
      hdr ? 2 : -1, &rgba, false);
    std::printf("RENDER ok=%d bytes=%zu expected=%zu elapsed_ms=%.3f glError=0x%x scene=%dx%d\n",
      ok, rgba.size(), size_t(width) * height * 4,
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count(),
      glGetError(), renderer.sceneW(), renderer.sceneH());
    printLogs();
    require(ok && rgba.size() == size_t(width) * height * 4, "full-chain render failed or returned wrong byte count");
    if (expectSourceWhite) {
      const size_t expected = hdr && !single ? 1 : 0;
      require(resolveCount() == resolvesBefore + expected, "wrong per-capture source-white resolve count");
      std::printf("SOURCE_WHITE expected=%zu observed=%zu\n", expected, resolveCount() - resolvesBefore);
    }
    uint8_t low = 255, high = 0;
    double sum = 0;
    for (size_t i = 0; i < rgba.size(); i += 4) for (int c = 0; c < 3; ++c) {
      low = std::min(low, rgba[i + c]); high = std::max(high, rgba[i + c]); sum += rgba[i + c];
    }
    std::printf("OUTPUT rgb_min=%u rgb_max=%u rgb_mean=%.3f\n", low, high, sum / (size_t(width) * height * 3));
    require(high > low && high > 0, "full-chain output is constant/black");
    save(output + "/render.rgba", rgba.data(), rgba.size());
    std::vector<uint8_t> jpeg;
    const int outWidth = ((rotation + 1) % 2) ? height : width;
    const int outHeight = ((rotation + 1) % 2) ? width : height;
    const bool encoded = filmkit::encodeJpegTurbo444(rgba.data(), outWidth, outHeight, 4, 100, &jpeg);
    std::printf("JPEG encoder=libjpeg-turbo444 available=%d ok=%d bytes=%zu\n",
                filmkit::jpegTurboAvailable(), encoded, jpeg.size());
    require(encoded && jpeg.size() > 4 && jpeg[0] == 0xff && jpeg[1] == 0xd8 &&
            jpeg[jpeg.size()-2] == 0xff && jpeg.back() == 0xd9, "JPEG encode or markers failed");
    save(output + "/render.jpg", jpeg.data(), jpeg.size());
    if (covarianceCases || highlightCases) {
      save(output + "/case-" + std::to_string(capture) + ".rgba", rgba.data(), rgba.size());
      save(output + "/case-" + std::to_string(capture) + ".jpg", jpeg.data(), jpeg.size());
    }
    }
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
