// Real production FrameRenderer integration test: no camera, APK or private API.
// Build (NDK 29, API 26; run from repo root):
// NDK=$HOME/Library/Android/sdk/ndk/29.0.14206865
// $NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++ \
//   -std=c++20 -O2 tools/check-renderer-device.cpp \
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
// --expect-chroma (require the full multiscale pass, not only nonblack output).
// --lens-switch-cases (warmup/RAW-size/rotation/strength changes in one renderer).
// --edge-dump (save production diagnostic stages in the output directory).
// --no-stage-sync (disable profiling-only P0/RCD/CCM waits; not image readbacks).
// --no-align-l1 (skip fine temporal alignment, leaving highlight admission intact).
// --diag-stats (enable production stage diagnostics; off by default in this tool).
// --pc3d 0|1|2 --pc3d-msize 0|7|11|15|21 (still replay overrides only).
// --chroma-gate 0..2 (same production edge-chroma setting).
// --film-params FILE (exactly FilmParams::kCount finite double slots, no LUT upload).
// --color-smooth 0|1 --color-median 0|1 (independent CS/CM still overrides).
// --prefilter-cases (24 repeated burst/single x CS/CM x PC3D captures).
// --ae-hal (production HAL exposure mode; avoid uninitialized preview AE history).
// --synthetic-feedback 0|1 (prime real preview statistics for slot 0 or 1 with a test pattern).
// --spektrafilm PROFILE RESPONSE (production recipe, test RGBA and HDR gainmap).
// --print-ev EV (Spektrafilm enlarger exposure, independent of capture EV).
// --spektrafilm-grain (enable seeded grain for Spektrafilm replay).
// --ten-bit (check packed RGB10_A2 before converting a copy for JPEG).
// --preview-reinit (also release and recreate a preview renderer).
// --concurrent-preview (30 Hz synthetic preview alongside one RAW replay).
// --repeat N (repeat identical input on one renderer, saving each result).
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
#include <cstring>
#include <fstream>
#include <future>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include "../core/engine/src/film/EngineRegistry.h"
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
  explicit Egl(int width = 16, int height = 16, EGLint priority = 0,
               EGLDisplay sharedDisplay = EGL_NO_DISPLAY)
      : ownsDisplay_(sharedDisplay == EGL_NO_DISPLAY) {
    display = ownsDisplay_ ? eglGetDisplay(EGL_DEFAULT_DISPLAY) : sharedDisplay;
    require(display != EGL_NO_DISPLAY, "eglGetDisplay failed");
    EGLint major = 0, minor = 0;
    if (ownsDisplay_) require(eglInitialize(display, &major, &minor), "eglInitialize failed");
    require(eglBindAPI(EGL_OPENGL_ES_API), "eglBindAPI failed");
    const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_RED_SIZE, 8,
      EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config = nullptr;
    EGLint count = 0;
    require(eglChooseConfig(display, attributes, &config, 1, &count) && count == 1,
            "no ES3 pbuffer EGL config");
    const EGLint pbuffer[] = {EGL_WIDTH, width, EGL_HEIGHT, height, EGL_NONE};
    surface = eglCreatePbufferSurface(display, config, pbuffer);
    require(surface != EGL_NO_SURFACE, "eglCreatePbufferSurface failed");
    const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (priority && extensions && std::strstr(extensions, "EGL_IMG_context_priority")) {
      const EGLint attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG,
                              priority, EGL_NONE};
      context = eglCreateContext(display, config, EGL_NO_CONTEXT, attrs);
      if (context == EGL_NO_CONTEXT) eglGetError();
      EGLint actual = 0;
      if (context != EGL_NO_CONTEXT &&
          !eglQueryContext(display, context, EGL_CONTEXT_PRIORITY_LEVEL_IMG, &actual)) eglGetError();
      std::printf("EGL_PRIORITY requested=%d actual=%d\n", priority, actual);
    }
    if (context == EGL_NO_CONTEXT) context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctx);
    require(context != EGL_NO_CONTEXT, "eglCreateContext failed");
    require(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent failed");
    if (ownsDisplay_)
      std::printf("EGL=%d.%d GL_VENDOR=%s GL_RENDERER=%s GL_VERSION=%s\n",
                  major, minor, glGetString(GL_VENDOR), glGetString(GL_RENDERER), glGetString(GL_VERSION));
  }
  ~Egl() {
    if (display != EGL_NO_DISPLAY) {
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
      if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
      if (ownsDisplay_) eglTerminate(display);
    }
  }
 private:
  bool ownsDisplay_;
};
using Clock = std::chrono::steady_clock;
double ms(Clock::duration elapsed) {
  return std::chrono::duration<double, std::milli>(elapsed).count();
}
void concurrentPreview(std::stop_token stop, EGLDisplay display, std::promise<void>& ready,
                       std::vector<std::pair<Clock::time_point, Clock::time_point>>& samples,
                       std::exception_ptr& error) {
  try {
    constexpr int width = 1216, height = 1621;
    const float st[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    Egl egl(width, height, EGL_CONTEXT_PRIORITY_HIGH_IMG, display);
    filmkit::FrameRenderer renderer;
    require(renderer.init(), "concurrent preview init failed");
    filmkit::FilmParams params;
    params.auto_black_pt = params.auto_white_pt = 1.f;
    auto next = Clock::now();
    constexpr auto period = std::chrono::nanoseconds(33333333);
    for (int i = 0; !stop.stop_requested(); ++i) {
      std::this_thread::sleep_until(next);
      if (stop.stop_requested()) break;
      const auto start = Clock::now();
      require(renderer.submitFrame(st, 0, 1.f, 1.f, width, height, params, true),
              "concurrent preview submit failed");
      // Measure GPU completion, not just CPU submission. No pixel readback.
      glFinish();
      const auto end = Clock::now();
      require(glGetError() == GL_NO_ERROR, "concurrent preview GL error");
      samples.emplace_back(start, end);
      if (i == 7) ready.set_value();
      do { next += period; } while (next < end);
    }
  } catch (...) {
    error = std::current_exception();
    try { ready.set_exception(error); } catch (const std::future_error&) {}
  }
}
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
    int size = 256, demosaic = 1, rotation = 0, pc3d = -1, pc3dMsize = -1, repeat = 1;
    bool hdr = true, dft = true, timerOnly = false, allocationOnly = false, preview = false, warmup = false, covarianceCases = false;
    bool noShort = false, highlightCases = false, expectSourceWhite = false, edgeDump = false;
    bool stageSync = true, diagStats = false, aeHal = false, expectChroma = false, alignL1 = true;
    bool lensSwitchCases = false, tenBit = false, previewReinit = false;
    bool prefilterCases = false, concurrent = false, spektraGrain = false;
    int feedbackSlot = -1;
    int colorSmooth = -1, colorMedian = -1;
    float chromaGate = -1.f;
    double printEv = 0.0;
    std::string output = ".", rawDirectory, filmParamsFile, spektraProfile, spektraResponse;
    for (int i = 1; i < argc; ++i) {
      const std::string arg = argv[i];
      if (arg == "--timer-only") timerOnly = true;
      else if (arg == "--ten-bit") tenBit = true;
      else if (arg == "--preview-reinit") previewReinit = true;
      else if (arg == "--concurrent-preview") concurrent = true;
      else if (arg == "--repeat" && i + 1 < argc) repeat = std::stoi(argv[++i]);
      else if (arg == "--prefilter-cases") prefilterCases = true;
      else if (arg == "--color-smooth" && i + 1 < argc) colorSmooth = std::stoi(argv[++i]);
      else if (arg == "--color-median" && i + 1 < argc) colorMedian = std::stoi(argv[++i]);
      else if (arg == "--allocation-only") allocationOnly = true;
      else if (arg == "--covariance-cases") covarianceCases = true;
      else if (arg == "--highlight-cases") highlightCases = true;
      else if (arg == "--lens-switch-cases") lensSwitchCases = true;
      else if (arg == "--no-short") noShort = true;
      else if (arg == "--expect-source-white") expectSourceWhite = true;
      else if (arg == "--expect-chroma") expectChroma = true;
      else if (arg == "--edge-dump") edgeDump = true;
      else if (arg == "--no-stage-sync") stageSync = false;
      else if (arg == "--no-align-l1") alignL1 = false;
      else if (arg == "--diag-stats") diagStats = true;
      else if (arg == "--ae-hal") aeHal = true;
      else if (arg == "--synthetic-feedback" && i + 1 < argc) feedbackSlot = std::stoi(argv[++i]);
      else if (arg == "--film-params" && i + 1 < argc) filmParamsFile = argv[++i];
      else if (arg == "--print-ev" && i + 1 < argc) printEv = std::stod(argv[++i]);
      else if (arg == "--spektrafilm-grain") spektraGrain = true;
      else if (arg == "--spektrafilm" && i + 2 < argc) {
        spektraProfile = argv[++i];
        spektraResponse = argv[++i];
      }
      else if (arg == "--raw-directory" && i + 1 < argc) rawDirectory = argv[++i];
      else if (arg == "--preview") preview = true;
      else if (arg == "--warmup") warmup = true;
      else if (arg == "--plain") hdr = false;
      else if (arg == "--no-dft") dft = false;
      else if (arg == "--size" && i + 1 < argc) size = std::stoi(argv[++i]);
      else if (arg == "--demosaic" && i + 1 < argc) demosaic = std::stoi(argv[++i]);
      else if (arg == "--rotation" && i + 1 < argc) rotation = std::stoi(argv[++i]);
      else if (arg == "--pc3d" && i + 1 < argc) pc3d = std::stoi(argv[++i]);
      else if (arg == "--pc3d-msize" && i + 1 < argc) pc3dMsize = std::stoi(argv[++i]);
      else if (arg == "--chroma-gate" && i + 1 < argc) chromaGate = std::stof(argv[++i]);
      else if (arg == "--output" && i + 1 < argc) output = argv[++i];
      else throw std::runtime_error("unknown or incomplete argument: " + arg);
    }
    require(size >= 128 && size <= 4096 && size % 32 == 0, "size must be a multiple of 32 in [128,4096]");
    require(repeat >= 1 && repeat <= 16 && (repeat == 1 ||
            (!preview && !prefilterCases && !lensSwitchCases && !highlightCases && !covarianceCases)),
            "repeat must be in [1,16] without preview or case sets");
    require(colorSmooth >= -1 && colorSmooth <= 1 && colorMedian >= -1 && colorMedian <= 1,
            "prefilter overrides must be 0 or 1");
    require(!prefilterCases || (!preview && !lensSwitchCases && !highlightCases &&
            !covarianceCases && rawDirectory.empty()), "prefilter cases require synthetic still input");
    require(demosaic >= 0 && demosaic <= 2, "demosaic must be 0, 1 or 2");
    require(rotation >= 0 && rotation <= 3, "rotation must be 0, 1, 2 or 3");
    require(pc3d >= -1 && pc3d <= 2, "pc3d must be 0, 1 or 2");
    require(std::isfinite(chromaGate) && (chromaGate == -1.f ||
            (chromaGate >= 0.f && chromaGate <= 2.f)), "chroma-gate must be in [0,2]");
    require(pc3dMsize == -1 || pc3dMsize == 0 || pc3dMsize == 7 ||
            pc3dMsize == 11 || pc3dMsize == 15 || pc3dMsize == 21,
            "pc3d-msize must be 0 (auto), 7, 11, 15 or 21");
    require((pc3d == -1 && pc3dMsize == -1) || (!preview && !covarianceCases),
            "pc3d overrides require a still replay without covariance cases");
    require(!warmup || !preview, "--warmup requires a still render");
    require(!highlightCases || (!covarianceCases && rawDirectory.empty()), "highlight cases require synthetic input and no covariance cases");
    require(!lensSwitchCases || (!preview && !covarianceCases && !highlightCases && rawDirectory.empty()),
            "lens switch cases require synthetic still input without other case sets");
    require(filmkit::Logger::instance().init(output), "Logger::init failed (output directory must exist)");
    require(feedbackSlot >= -1 && feedbackSlot <= 1, "invalid synthetic feedback slot");
    const bool syntheticFeedback = feedbackSlot >= 0;
    require(!syntheticFeedback || (!rawDirectory.empty() && !preview && !warmup && filmParamsFile.empty()),
            "synthetic feedback requires a real RAW replay without warmup");
    require(!concurrent || (!rawDirectory.empty() && !preview && !syntheticFeedback &&
            !covarianceCases && !prefilterCases && repeat == 1),
            "concurrent preview requires one RAW replay");
    const int surfaceSize = preview || syntheticFeedback ? size : 16;
    Egl egl(surfaceSize, surfaceSize, concurrent ? EGL_CONTEXT_PRIORITY_LOW_IMG : 0);
    if (allocationOnly) { checkAllocation(); return EXIT_SUCCESS; }
    if (timerOnly) { checkTimer(); return EXIT_SUCCESS; }
    filmkit::FilmParams params;
    if (!filmParamsFile.empty()) {
      std::ifstream input(filmParamsFile);
      std::array<double, filmkit::FilmParams::kCount> slots;
      for (double& value : slots)
        require(bool(input >> value) && std::isfinite(value), "invalid film parameter slots");
      std::string extra;
      require(!(input >> extra), "extra film parameter slots");
      params.fromDoubles(slots.data(), slots.size());
    }
    filmkit::FrameRenderer::setDiagStats(diagStats);
    filmkit::FrameRenderer::setStillStageTimingSync(stageSync);
    filmkit::FrameRenderer::setAeHal(aeHal);
    if (syntheticFeedback) {
      params.engine_slot = static_cast<float>(feedbackSlot);
      const float st[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
      filmkit::FrameRenderer previewRenderer;
      require(previewRenderer.init(), "synthetic feedback preview init failed");
      for (int i = 0; i < 10; ++i)
        require(previewRenderer.submitFrame(st, 0, 1.f, 1.f, size, size, params, true),
                "synthetic feedback preview submit failed");
      const float anchor = filmkit::FrameRenderer::displayP50();
      require(std::isfinite(anchor) && anchor > .005f, "synthetic feedback did not produce an anchor");
      std::printf("PREVIEW_FEEDBACK source=synthetic_fill frames=10 anchor=%.9g engine=%.1f\n",
                  anchor, params.engine_slot);
    }
    filmkit::FrameRenderer renderer;
    renderer.setStillContext(!preview);
    if (!spektraProfile.empty()) {
      require(!preview && !syntheticFeedback, "Spektrafilm requires a still replay");
      const auto load = [](const std::string& path) {
        std::ifstream input(path, std::ios::binary);
        require(bool(input), "cannot open " + path);
        return std::vector<char>(std::istreambuf_iterator<char>(input), {});
      };
      const auto profileBytes = load(spektraProfile), responseBytes = load(spektraResponse);
      std::string error;
      const auto loaded = filmkit::spektrafilm::loadNegativeProfile(std::as_bytes(std::span(profileBytes)),
          &error, std::as_bytes(std::span(responseBytes)));
      require(bool(loaded), "Spektrafilm profile rejected: " + error);
      auto recipe = std::make_shared<filmkit::spektrafilm::NegativeRenderParams>(*loaded);
      const std::array<double, 12> options{1, spektraGrain ? 1.0 : 0.0, .45, .8, .5, .5, 1, printEv, 0, .7, .7, 35};
      require(filmkit::spektrafilm::applyRenderOptions(*recipe, options), "Spektrafilm options rejected");
      renderer.setSpektrafilmProfile(std::move(recipe));
      params.engine_slot = filmkit::kSpektrafilmSlot;
      params.exposure_ev = .7f;
    }
    std::printf("STAGE renderer.init\n");
    const auto initStart = std::chrono::steady_clock::now();
    const bool initialized = renderer.init();
    std::printf("INIT ok=%d elapsed_ms=%.3f glError=0x%x\n", initialized,
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - initStart).count(), glGetError());
    if (!initialized) { printLogs(); return EXIT_FAILURE; }
    if (preview) {
      filmkit::FrameRenderer::setToneEq(true);
      const float st[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
      for (int round = 0; round < 2; ++round) {
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
        save(output + "/preview-" + std::to_string(round) + "-" + std::to_string(slot) + "-" +
             std::to_string(scaled) + ".rgba", rgba.data(), rgba.size());
        std::printf("PREVIEW engine=%d scaled=%d frames=3 min=%u max=%u bytes=%zu\n",
                    slot, scaled, low, high, rgba.size());
      }
      }
      if (previewReinit) {
        renderer.release();
        require(glGetError() == GL_NO_ERROR, "preview release GL error");
        filmkit::FrameRenderer rebuilt;
        require(rebuilt.init(), "preview recreation failed");
        filmkit::FilmParams params;
        for (float scale : {1.f, .5f, 1.f}) {
          filmkit::FrameRenderer::setPreviewScale(scale);
          require(rebuilt.submitFrame(st, 0, 1.f, 1.f, size, size, params, true),
                  "recreated preview submit failed");
          glFinish();
          require(glGetError() == GL_NO_ERROR, "recreated preview GL error");
        }
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
    if (warmup) {
      std::printf("STAGE warmup size=%dx%d rotation=%d\n", width, height, rotation);
      const bool usePc3d = (pc3d >= 0 ? pc3d : params.pc3d) > .5f;
      const bool usePrefilter = (colorSmooth >= 0 ? colorSmooth : params.color_smooth) > .5f ||
          (colorMedian >= 0 ? colorMedian : params.color_median) > .5f;
      require(renderer.warmup(width, height, rotation, spektraProfile.empty() || usePc3d,
                             spektraProfile.empty() || usePrefilter), "still warmup failed");
      require(glGetError() == GL_NO_ERROR, "still warmup GL error");
    }
    const int grbg[4] = {1, 0, 2, 1};
    std::array<std::vector<uint16_t>, 4> raw;
    const void* frames[4];
    if (lensSwitchCases && filmParamsFile.empty()) {
      // Match the production allocation order without a private preset snapshot.
      params.color_smooth = 1.f;
      if (spektraProfile.empty()) params.engine_slot = 0.f;
    }
    if (pc3d >= 0) params.pc3d = static_cast<float>(pc3d);
    if (pc3dMsize >= 0) params.pc3d_msize = static_cast<float>(pc3dMsize);
    if (chromaGate >= 0.f) params.chroma_gate = chromaGate;
    if (colorSmooth >= 0) params.color_smooth = static_cast<float>(colorSmooth);
    if (colorMedian >= 0) params.color_median = static_cast<float>(colorMedian);
    std::printf("PARAMS pc3d=%.1f pc3d_msize=%.1f chroma_gate=%.1f\n",
                params.pc3d, params.pc3d_msize, params.chroma_gate);
    const float shotCases[] = {.0007f, .000004f, .00008f, .00008f, .000004f, .0007f, .000004f, .0007f};
    const float pc3dCases[] = {1.f, 1.f, 1.f, 2.f, 0.f, 2.f, 1.f, 1.f};
    for (int capture = 0; capture < (prefilterCases ? 24 : covarianceCases ? 8 : highlightCases ? 6 : lensSwitchCases ? 8 : repeat); ++capture) {
    const bool single = (highlightCases && capture == 4) || (prefilterCases && capture >= 12);
    if (prefilterCases) {
      const int mode = (capture/3)%4;
      params.color_smooth = float(mode&1);
      params.color_median = float((mode>>1)&1);
      params.pc3d = float(capture%3);
      std::printf("PREFILTER_CASE capture=%d single=%d CS=%.0f CM=%.0f PC3D=%.0f\n",
                  capture,single,params.color_smooth,params.color_median,params.pc3d);
    }
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
    if (lensSwitchCases) {
      const bool tele = (capture >= 1 && capture <= 3) || capture == 6;
      width = size == 4096 ? (tele ? 4096 : 4080) : size + (tele ? 32 : 0);
      height = size * 3 / 4;
      rotation = capture >= 5 ? 1 : 0;
      noiseStrength = capture == 2 || capture == 5 ? 0.f : capture == 3 ? 1.f : .5f;
      params.sat_mask = 1.f;
      params.ca_corr = 1.f;
      params.contrast = capture == 3 || capture == 6 ? 1.15f : 1.f;
      for (int& stride : strides) stride = width;
      const bool prewarm = capture == 0 || warmup;
      std::printf("LENS_SWITCH capture=%d size=%dx%d rotation=%d noise=%.2f contrast=%.2f warmup=%d\n",
                  capture, width, height, rotation, noiseStrength, params.contrast, prewarm);
      if (prewarm) {
        require(renderer.warmup(width, height, rotation, true, true), "lens-switch warmup failed");
        require(glGetError() == GL_NO_ERROR, "lens-switch warmup GL error");
      }
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
    std::printf("STAGE submitRawBurst hdr=%d dft=%d demosaic=%d diagStats=%d stageSync=%d stillContext=1 engine_slot=%.1f tone_strength=%.1f\n",
                hdr, dft, demosaic, diagStats, stageSync, params.engine_slot, params.tone_strength);
    std::vector<std::pair<Clock::time_point, Clock::time_point>> previewSamples;
    std::exception_ptr previewError;
    std::promise<void> previewReady;
    std::jthread previewThread;
    if (concurrent) {
      previewSamples.reserve(512);
      filmkit::FrameRenderer::setPreviewScale(.5f);
      auto ready = previewReady.get_future();
      previewThread = std::jthread(concurrentPreview, egl.display, std::ref(previewReady),
                                 std::ref(previewSamples), std::ref(previewError));
      ready.get();
    }
    const auto wallStart = std::chrono::system_clock::now();
    const auto start = Clock::now();
    const bool ok = single ? renderer.submitRaw(frames[0], width, height, strides[0], cfa,
      black, white, neutral[0], neutral[1], neutral[2], ccm, lsc.data(), lscCols, lscRows,
      rotation, params, demosaic, noiseStrength, noise, .015f, false, &rgba, tenBit)
      : renderer.submitRawBurst(frames, 4, strides, width, height, cfa,
      black, white, neutral[0], neutral[1], neutral[2], ccm, lsc.data(), lscCols, lscRows, rotation,
      params, demosaic, noiseStrength, noise, .015f, 1.f, dft, alignL1, false, true,
      false, 1.f, hdr ? 0.f : -1.f, hdr ? ev : nullptr, hdr ? gain : nullptr,
      hdr ? 2 : -1, &rgba, tenBit);
    const auto end = Clock::now();
    const GLenum renderError = glGetError();
    if (concurrent) {
      previewThread.request_stop();
      previewThread.join();
      if (previewError) std::rethrow_exception(previewError);
      std::printf("CONCURRENT start_epoch_ms=%.3f end_ms=%.3f\n",
          std::chrono::duration<double, std::milli>(wallStart.time_since_epoch()).count(), ms(end - start));
      for (const auto& [begin, done] : previewSamples)
        std::printf("PREVIEW_GPU start_ms=%.3f done_ms=%.3f\n", ms(begin - start), ms(done - start));
    }
    std::printf("RENDER ok=%d bytes=%zu expected=%zu elapsed_ms=%.3f glError=0x%x scene=%dx%d\n",
      ok, rgba.size(), size_t(width) * height * 4,
      ms(end - start),
      renderError, renderer.sceneW(), renderer.sceneH());
    printLogs();
    require(ok && rgba.size() == size_t(width) * height * 4, "full-chain render failed or returned wrong byte count");
    require(renderError == GL_NO_ERROR, "full-chain render GL error");
    if (prefilterCases) {
      const auto logs = filmkit::Logger::instance().recentLines(1000);
      const size_t last = logs.rfind("\"event\":\"raw.render\"");
      require(last != std::string::npos, "missing prefilter render diagnostics");
      const auto line = logs.substr(last, logs.find('\n', last)-last);
      require(line.find(params.color_smooth > .5f ? "\"csOn\":1," : "\"csOn\":0,") != std::string::npos,
              "CS gate/consumer mismatch");
      require(line.find(params.color_median > .5f ? "\"cmOn\":1," : "\"cmOn\":0,") != std::string::npos,
              "CM gate/consumer mismatch");
    }
    if (expectChroma) {
      const auto logs = filmkit::Logger::instance().recentLines(1000);
      const size_t last = logs.rfind("\"event\":\"raw.render\"");
      require(last != std::string::npos, "missing final render diagnostics");
      const auto finalLog = logs.substr(last,logs.find('\n',last)-last);
      require(finalLog.find("\"cms\":1,") != std::string::npos,
              "multiscale chroma pass did not complete");
    }
    if (expectSourceWhite) {
      const size_t expected = hdr && !single ? 1 : 0;
      require(resolveCount() == resolvesBefore + expected, "wrong per-capture source-white resolve count");
      std::printf("SOURCE_WHITE expected=%zu observed=%zu\n", expected, resolveCount() - resolvesBefore);
    }
    save(output + "/render.rgba", rgba.data(), rgba.size());
    if (repeat > 1 || covarianceCases || highlightCases || lensSwitchCases)
      save(output + "/case-" + std::to_string(capture) + ".rgba", rgba.data(), rgba.size());
    if (tenBit) {
      for (size_t i = 0; i < rgba.size(); i += 4) {
        uint32_t packed;
        std::memcpy(&packed, rgba.data() + i, sizeof(packed));
        require((packed >> 30) == 3u, "packed RGB10_A2 alpha is not opaque");
        for (int c = 0; c < 3; ++c)
          rgba[i + c] = static_cast<uint8_t>((((packed >> (10 * c)) & 1023u) * 255u + 511u) / 1023u);
        rgba[i + 3] = 255;
      }
    }
    uint8_t low = 255, high = 0;
    double sum = 0;
    for (size_t i = 0; i < rgba.size(); i += 4) for (int c = 0; c < 3; ++c) {
      low = std::min(low, rgba[i + c]); high = std::max(high, rgba[i + c]); sum += rgba[i + c];
    }
    std::printf("OUTPUT rgb_min=%u rgb_max=%u rgb_mean=%.3f\n", low, high, sum / (size_t(width) * height * 3));
    require(high > low && high > 0, "full-chain output is constant/black");
    if (lensSwitchCases)
      require(sum / (size_t(width) * height * 3) > 10.,
              "known bright lens-switch fixture became black (dither is not image signal)");
    if (!spektraProfile.empty()) {
      std::vector<uint8_t> gainmap;
      require(renderer.readGainmap(&gainmap) && !gainmap.empty(), "Spektrafilm HDR gainmap unavailable");
      save(output + "/render.gainmap", gainmap.data(), gainmap.size());
      if (repeat > 1 || covarianceCases || highlightCases || lensSwitchCases)
        save(output + "/case-" + std::to_string(capture) + ".gainmap", gainmap.data(), gainmap.size());
    }
    std::vector<uint8_t> jpeg;
    const int outWidth = ((rotation + 1) % 2) ? height : width;
    const int outHeight = ((rotation + 1) % 2) ? width : height;
    const bool encoded = filmkit::encodeJpegTurbo444(rgba.data(), outWidth, outHeight, 4, 100, &jpeg);
    std::printf("JPEG encoder=libjpeg-turbo444 available=%d ok=%d bytes=%zu\n",
                filmkit::jpegTurboAvailable(), encoded, jpeg.size());
    require(encoded && jpeg.size() > 4 && jpeg[0] == 0xff && jpeg[1] == 0xd8 &&
            jpeg[jpeg.size()-2] == 0xff && jpeg.back() == 0xd9, "JPEG encode or markers failed");
    save(output + "/render.jpg", jpeg.data(), jpeg.size());
    if (repeat > 1 || covarianceCases || highlightCases || lensSwitchCases) {
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
