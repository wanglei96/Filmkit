#include <android/native_window_jni.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "../gl/Capabilities.h"
#include "../gl/EglCore.h"
#include "../log/Logger.h"
#include "../render/FrameRenderer.h"
#include "../spike/SpikeFftMerge.h"
#include "../spike/SpikePass.h"

// JNI 桥：
//  - 日志/能力/spike：任意线程（detect/spike 需持有 GL 上下文，当前在 GLSurfaceView 线程调用）
//  - engine*：必须全部在 RenderClient 渲染线程调用（EGL 上下文 per-thread current）

using filmkit::Capabilities;
using filmkit::detectCapabilities;
using filmkit::EglCore;
using filmkit::FilmParams;
using filmkit::FrameRenderer;
using filmkit::jsonEscape;
using filmkit::Logger;
using filmkit::LogLevel;
using filmkit::runSpikePass;
using filmkit::runSpikeFftMerge;

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace {

std::string toStd(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* p = env->GetStringUTFChars(s, nullptr);
  std::string r = p ? p : "";
  if (p) env->ReleaseStringUTFChars(s, p);
  return r;
}

jstring toJString(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

// 引擎单实例（M1：单相机会话；多实例随多会话需求引入）
EglCore& engineEgl() {
  static EglCore egl;
  return egl;
}
FrameRenderer* g_renderer = nullptr;
EGLSurface g_win_surface = EGL_NO_SURFACE;
EGLSurface g_pb_surface = EGL_NO_SURFACE;
ANativeWindow* g_window = nullptr;
unsigned int g_oes_tex = 0;

// ── 拍照侧引擎（v0.10.85）：独立线程 + 独立 EGL context + 独立 FrameRenderer ──
// 起因（真机）：预览与拍照共用一个渲染线程/context 时，拍照那条 1.6~3.4s 的
// 合成链把预览帧的 updateTexImage 全排在它后面 —— "拍完挪手机画面完全冻住
// 1~2 秒"。PhotonCamera 正是每个后处理器各自 eglCreateContext
// （GlesMgcRawSpatialStacker / GlesMgcRawBaseFrameSelector / OglBokehProcessor
// 均可查证），预览线程因此不受影响。
// 拍照链的输入是 CPU 侧 4 帧 ByteBuffer、输出是读回的 RGBA，**全程不碰预览
// 纹理**，所以两侧天然隔离，不需要 PC 那套 context 共享。
// v0.10.83 曾按此实现后在真机崩溃+丢照片（stop 时旧线程仍在跑合成，Kotlin
// 侧已置空 handler → 新线程复用同一 g_renderer_still → 并发/释放后使用）。
// 本版把生命周期钉死：**只有 start，没有 stop**（线程常驻、实例常驻），
// 且全部调用都排在同一条 Handler 队列上串行 —— 不存在"杀掉正在跑 GL 的线程"
// 的路径（Kotlin 侧见 StillRenderClient）。
EglCore& stillEgl() {
  static EglCore egl;
  return egl;
}
// 拍照实例指针必须原子（v0.10.85 审查 R3）：它在拍照线程写（nativeStillStart），
// 却在预览线程读（nativeEngineSetLut 下发 LUT）—— 非原子就是形式上的 data race。
// 指针本身原子化即可：实例只创建、从不销毁，不存在"读到已释放实例"的窗口。
std::atomic<FrameRenderer*> g_renderer_still{nullptr};
EGLSurface g_still_surface = EGL_NO_SURFACE;

// 单帧 YUV 解码矩阵（0=BT.601 / 1=BT.709）：改成进程级快照后，设置页在主线程
// 调 nativeEngineSetYuvMatrix 不再直接碰 g_renderer（那与预览线程 delete
// g_renderer 是 UAF，v0.10.85 审查 R4）；实际下发在 nativeEngineStart 里。
std::atomic<int> g_yuv_matrix{0};

// burst 序号（native 侧自增）：把 raw.render / render.burst_done 等日志按"第几张"
// 串起来，连拍时"第三张按不动"才能归因（v0.10.85 审查 P2）。
std::atomic<int> g_burst_seq{0};

// ── NN 推理回桥（v0.10.102）：C++ → NnInferEngine.inferTile ─────────────
// 契约：g_nn.env 在**每次** nativeEngineSubmitRaw/Burst（以及 nativeRunNnRoundtrip）
// 入口刷新为当前线程的 JNIEnv —— nnBridgeInfer 只会发生在同一条 native 调用栈内
//（FrameRenderer::runNnPass 的 tile 循环），env 必然有效。jclass 持 global ref，
// tile 缓冲地址由 NnInferEngine 常驻 direct buffer 提供（getBufs 一次性取回）。
struct NnBridge {
  JNIEnv* env = nullptr;
  jclass cls = nullptr;         // global ref
  jmethodID m_infer = nullptr;  // ()I
  jmethodID m_info = nullptr;   // ()Ljava/lang/String;
  jmethodID m_bufs = nullptr;   // ()[Ljava/nio/ByteBuffer;
  float* in_addr = nullptr;
  float* out_addr = nullptr;
  char del_buf[8] = {0};        // delegateInfo 的 C 字符串缓存
  bool tried = false;
  bool ready = false;
};
NnBridge g_nn;

void nnBridgeRefreshDelegate(JNIEnv* env);

bool nnBridgeInit(JNIEnv* env) {
  if (g_nn.ready) return true;
  if (g_nn.tried) return false;
  g_nn.tried = true;
  jclass lc = env->FindClass("com/filmkit/app/nn/NnInferEngine");
  if (lc == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  g_nn.cls = static_cast<jclass>(env->NewGlobalRef(lc));
  env->DeleteLocalRef(lc);
  if (!g_nn.cls) return false;
  g_nn.m_infer = env->GetStaticMethodID(g_nn.cls, "inferTile", "()I");
  g_nn.m_info =
      env->GetStaticMethodID(g_nn.cls, "delegateInfo", "()Ljava/lang/String;");
  g_nn.m_bufs =
      env->GetStaticMethodID(g_nn.cls, "getBufs", "()[Ljava/nio/ByteBuffer;");
  if (!g_nn.m_infer || !g_nn.m_info || !g_nn.m_bufs || env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  auto bufs = static_cast<jobjectArray>(
      env->CallStaticObjectMethod(g_nn.cls, g_nn.m_bufs));
  if (bufs == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  jobject inb = env->GetObjectArrayElement(bufs, 0);
  jobject outb = env->GetObjectArrayElement(bufs, 1);
  g_nn.in_addr =
      inb ? static_cast<float*>(env->GetDirectBufferAddress(inb)) : nullptr;
  g_nn.out_addr =
      outb ? static_cast<float*>(env->GetDirectBufferAddress(outb)) : nullptr;
  if (inb) env->DeleteLocalRef(inb);
  if (outb) env->DeleteLocalRef(outb);
  env->DeleteLocalRef(bufs);
  g_nn.ready = g_nn.in_addr != nullptr && g_nn.out_addr != nullptr;
  return g_nn.ready;
}

// FrameRenderer 的 NnInferFn：拷入 tile → 阻塞推理 → 拷出结果。
// 失败（Java 侧错误码 / 异常 / 未就绪）返回 false，调用方整张回退 P0 原链。
bool nnBridgeInfer(const float* in, float* out) {
  // 首次调用时初始化桥（FindClass/getBufs 在拍照线程的调用栈内，env 有效）——
  // 0.10.102 真机教训：只检查 ready 却从不 init，NN 整轮静默回退
  if (!g_nn.env) return false;
  if (!g_nn.ready && !nnBridgeInit(g_nn.env)) return false;
  constexpr size_t kTileBytes = static_cast<size_t>(512) * 512 * 4 * sizeof(float);
  std::memcpy(g_nn.in_addr, in, kTileBytes);
  const jint r = g_nn.env->CallStaticIntMethod(g_nn.cls, g_nn.m_infer);
  if (g_nn.env->ExceptionCheck()) {
    g_nn.env->ExceptionClear();
    return false;
  }
  if (r != 0) return false;
  std::memcpy(out, g_nn.out_addr, kTileBytes);
  // 首次成功后缓存 delegate 串（nnBridgeInfo 的返回值）
  if (g_nn.del_buf[0] == 0) nnBridgeRefreshDelegate(g_nn.env);
  return true;
}

// FrameRenderer 的 NnInfoFn：返回缓存的 delegate 串（"gpu"/"cpu"）。
const char* nnBridgeInfo() { return g_nn.del_buf; }

void nnBridgeRefreshDelegate(JNIEnv* env) {
  if (!g_nn.ready || !g_nn.m_info) return;
  auto s = static_cast<jstring>(
      env->CallStaticObjectMethod(g_nn.cls, g_nn.m_info));
  if (s != nullptr && !env->ExceptionCheck()) {
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (p) {
      std::snprintf(g_nn.del_buf, sizeof(g_nn.del_buf), "%s", p);
      env->ReleaseStringUTFChars(s, p);
    }
  } else {
    env->ExceptionClear();
  }
  if (s) env->DeleteLocalRef(s);
}

int64_t nowMs() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// 3D LUT 进程级快照：拍照侧实例是**后建**的，必须能补齐当前风格层 ——
// 否则独立线程拍出的照片会静默丢掉 LUT（画质红线，v0.10.83 的隐藏回归）。
std::mutex g_lut_mu;
std::vector<float> g_lut_data;   // RGB 交错（.cube 顺序，红最快）
int g_lut_size = 0;
float g_lut_dmin[3] = {0.0f, 0.0f, 0.0f};
float g_lut_dmax[3] = {1.0f, 1.0f, 1.0f};

// 把当前 LUT 快照下发到指定实例（setLut 内部有锁 + dirty 标志，
// 任意线程可调；纹理上传发生在该实例自己的渲染线程）。
void applyLutSnapshot(FrameRenderer* r) {
  if (!r) return;
  std::lock_guard<std::mutex> lk(g_lut_mu);
  if (g_lut_size <= 1 || g_lut_data.empty()) {
    r->setLut(nullptr, 0, nullptr, nullptr);
  } else {
    r->setLut(g_lut_data.data(), g_lut_size, g_lut_dmin, g_lut_dmax);
  }
}

// 成片 RGBA 交回 Java（v0.10.86 OOM 防护）。
// NewByteArray/SetByteArrayRegion 在 Java 堆不足时会**留下一个待决的
// OutOfMemoryError**。带着 pending exception 返回 Kotlin 后，紧接着的任何 JNI
// 调用（或 ART 的 JNI 校验）都会直接 abort —— 真机 2026-10-01 11:22:59 实测：
// SIGABRT in FilmStill，Java 崩溃捕获根本没机会写 crash-latest.txt，现场只能靠
// tombstone。这里清掉异常并返回 nullptr：照片这一张失败，但进程活着、日志有记录。
jbyteArray rgbaToJavaArray(JNIEnv* env, const std::vector<uint8_t>& rgba) {
  const jsize n = static_cast<jsize>(rgba.size());
  jbyteArray out = env->NewByteArray(n);
  if (out != nullptr && !env->ExceptionCheck()) {
    env->SetByteArrayRegion(out, 0, n,
                            reinterpret_cast<const jbyte*>(rgba.data()));
  }
  if (out == nullptr || env->ExceptionCheck()) {
    env->ExceptionClear();
    Logger::instance().log(LogLevel::kError, "engine", "render.oom_drop",
                           "{\"bytes\":" + std::to_string(rgba.size()) + "}");
    return nullptr;
  }
  return out;
}

FilmParams paramsFromJava(JNIEnv* env, jdoubleArray film) {
  FilmParams p;
  if (film) {
    const jsize n = env->GetArrayLength(film);
    std::vector<double> v(static_cast<size_t>(n));
    env->GetDoubleArrayRegion(film, 0, n, v.data());
    p.fromDoubles(v.data(), n);
  }
  return p;
}

}  // namespace

// ── 日志 / 能力 / spike（M0）──────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_filmkit_app_EngineBridge_nativeInitLogger(JNIEnv* env, jobject,
                                                   jstring dir) {
  return Logger::instance().init(toStd(env, dir)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeAppendLog(JNIEnv* env, jobject,
                                                  jint level, jstring module,
                                                  jstring event,
                                                  jstring kv_json) {
  Logger::instance().log(static_cast<LogLevel>(level), toStd(env, module),
                         toStd(env, event), toStd(env, kv_json));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_filmkit_app_EngineBridge_nativeRecentLogs(JNIEnv* env, jobject,
                                                   jint max_lines) {
  return toJString(env, Logger::instance().recentLines(max_lines));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_filmkit_app_EngineBridge_nativeFlushLogs(JNIEnv*, jobject) {
  return Logger::instance().flush() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_filmkit_app_EngineBridge_nativeDetectCapabilities(JNIEnv* env,
                                                           jobject) {
  const Capabilities caps = detectCapabilities();
  Logger::instance().log(LogLevel::kInfo, "engine", "caps.detected",
                         caps.toJson());
  return toJString(env, caps.toJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_filmkit_app_EngineBridge_nativeRunSpike(JNIEnv* env, jobject, jint w,
                                                 jint h, jint iters,
                                                 jboolean use_compute) {
  const filmkit::SpikeResult r =
      runSpikePass(w, h, iters, use_compute == JNI_TRUE);
  return toJString(env, r.toJson());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_filmkit_app_EngineBridge_nativeRunFftSpike(JNIEnv* env, jobject, jint w,
                                                    jint h, jint iters,
                                                    jboolean overlap,
                                                    jint variant) {
  const filmkit::FftSpikeResult r =
      runSpikeFftMerge(w, h, iters, overlap == JNI_TRUE, variant);
  return toJString(env, r.toJson());
}

// NN 链路自检（v0.10.102，SpikeUi 按钮）：须在**拍照线程**（有引擎 context，
// StillRenderClient 队列）调用。with_infer=false = pack→unpack 域往返恒等；
// true = 含真推理（GPU/CPU delegate 端到端 + 输出有限性）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_filmkit_app_EngineBridge_nativeRunNnRoundtrip(JNIEnv* env, jobject,
                                                       jboolean with_infer) {
  // NN 回桥注入（往返自检的推理路径也要走 NnInferEngine）
  g_nn.env = env;
  FrameRenderer::setNnInferFn(&nnBridgeInfer);
  FrameRenderer::setNnInfoFn(&nnBridgeInfo);
  // 优先拍照实例（自带 context 的线程上才有意义）；回退预览实例（SpikeUi 走
  // StillRenderClient 队列时必为前者）
  FrameRenderer* r = g_renderer_still.load();
  if (r == nullptr) r = g_renderer;
  if (r == nullptr) {
    return toJString(env, "{\"ok\":false,\"error\":\"no_renderer\"}");
  }
  return toJString(env, r->runNnRoundtrip(with_infer == JNI_TRUE));
}

// ── 相机引擎（M1：SurfaceTexture + OES，参照 PhotonCamera 路径）──────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineStart(JNIEnv* env, jobject,
                                                    jobject surface) {
  if (!engineEgl().init()) return JNI_FALSE;

  if (g_renderer == nullptr) {
    g_renderer = new FrameRenderer();
  }
  if (g_window) {
    ANativeWindow_release(g_window);
    g_window = nullptr;
  }
  g_window = ANativeWindow_fromSurface(env, surface);
  if (!g_window) return JNI_FALSE;

  if (g_win_surface != EGL_NO_SURFACE) {
    engineEgl().destroySurface(g_win_surface);
    g_win_surface = EGL_NO_SURFACE;
  }
  g_win_surface = engineEgl().createWindowSurface(g_window);
  if (g_win_surface == EGL_NO_SURFACE) return JNI_FALSE;
  if (!engineEgl().makeCurrent(g_win_surface)) return JNI_FALSE;

  // 渲染器随 start 重建（stop 时已 release+delete，program 需重新编译）
  if (!g_renderer->init()) {
    Logger::instance().log(LogLevel::kError, "engine", "engine.init_failed",
                           "{}");
    return JNI_FALSE;
  }
  // LUT 是进程级设置（v0.10.85）：实例重建后补齐当前风格层。Kotlin 侧
  // 预览就绪时也会重传，这里是第二道保险。
  applyLutSnapshot(g_renderer);
  // YUV 解码矩阵同理（v0.10.85，审查 R4）：设置页只写快照，这里下发。
  g_renderer->setYuvMatrix(g_yuv_matrix.load());
  Logger::instance().log(
      LogLevel::kInfo, "engine", "engine.start",
      "{\"w\":" + std::to_string(ANativeWindow_getWidth(g_window)) +
          ",\"h\":" + std::to_string(ANativeWindow_getHeight(g_window)) + "}");
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineStop(JNIEnv*, jobject) {
  // 渲染器重建放回 start：release 需 current context，stop 时临时挂 pbuffer
  if (g_renderer) {
    if (g_pb_surface == EGL_NO_SURFACE) {
      g_pb_surface = engineEgl().createPbufferSurface(1, 1);
    }
    engineEgl().makeCurrent(g_pb_surface);
    g_renderer->release();
    delete g_renderer;
    g_renderer = nullptr;
  }
  if (g_win_surface != EGL_NO_SURFACE) {
    engineEgl().destroySurface(g_win_surface);
    g_win_surface = EGL_NO_SURFACE;
  }
  engineEgl().makeCurrent(EGL_NO_SURFACE);
  if (g_window) {
    ANativeWindow_release(g_window);
    g_window = nullptr;
  }
  Logger::instance().log(LogLevel::kInfo, "engine", "engine.stop", "{}");
}

// 拍照渲染线程的引擎初始化（v0.10.85）：在**调用线程**上建独立 context，挂
// 1×1 pbuffer（拍照链只渲染到自己的 FBO，不上屏，不需要 window surface），
// 并建独立 FrameRenderer。必须由 still 渲染线程自己调用 —— EGL context 是
// per-thread current 的。
// 幂等：已就绪时只重新 makeCurrent（线程复用后 current 可能漂移），不重建实例
// —— 重建会丢掉 LUT/程序，且与本函数"只 start 不 stop"的生命周期约定一致。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_filmkit_app_EngineBridge_nativeStillStart(JNIEnv*, jobject) {
  if (!stillEgl().init()) return JNI_FALSE;
  if (g_still_surface == EGL_NO_SURFACE) {
    g_still_surface = stillEgl().createPbufferSurface(1, 1);
  }
  if (g_still_surface == EGL_NO_SURFACE) return JNI_FALSE;
  if (!stillEgl().makeCurrent(g_still_surface)) return JNI_FALSE;
  if (g_renderer_still.load() == nullptr) {
    FrameRenderer* r = new FrameRenderer();
    if (!r->init()) {
      delete r;
      Logger::instance().log(LogLevel::kError, "engine",
                             "engine.still_init_failed", "{}");
      return JNI_FALSE;  // 调用方（Kotlin）回退预览线程
    }
    g_renderer_still.store(r);
    // 补齐风格层：后建实例默认没有 LUT，不补就是"照片少一层"的静默回归
    applyLutSnapshot(g_renderer_still.load());
    // YUV 解码矩阵同理（v0.10.93 补齐）：快照在 nativeEngineSetYuvMatrix 里写，
    // 这里不下发的话 BT.709 视频会被按 BT.601 解码（色偏）。幂等，每次 start 都补。
    g_renderer_still.load()->setYuvMatrix(g_yuv_matrix.load());
    Logger::instance().log(LogLevel::kInfo, "engine", "engine.still_start",
                           "{\"fresh\":true}");
  } else {
    Logger::instance().log(LogLevel::kInfo, "engine", "engine.still_start",
                           "{\"fresh\":false}");
  }
  return JNI_TRUE;
}

// 空闲释放（v0.10.88）：合成链在全分辨率下会留下约 2GB 中间纹理（RCD 三段 +
// DFT + 高光重建的 RGBA16F 全分辨率 target），实测拍完常驻 2418MB 不回落
// （对照：单实例路径拍完回到 459MB）。这里在**空闲一段时间后**把拍照实例整体
// 销毁、下次拍照再重建：
//   - context / pbuffer **保留**（只销毁 FrameRenderer，省掉 EGL 重建）
//   - 所有 GL 调用都排在同一条 Handler 队列上串行，销毁必然排在在飞合成之后
//     —— 不存在 v0.10.83 那个"新线程复用旧实例"的竞态
//   - 重建 ~60ms（实测 initMs 50~117ms，Mali shader 缓存命中），首张可接受
// 与 nativeStillStart 的幂等约定配套：置空后下一次 start 会重新 new + init，
// 并由 start 里的 applyLutSnapshot 补齐风格层。
//
// v0.10.89 修正：**context 也一起销毁**。真机实测（0.10.88 日志）发现只销毁
// FrameRenderer 时，重建后的首次合成会在 `ensureRawTex` 的 glTexImage2D
// （4080×3072 R16UI，25MB）上失败（`raw.upload_failed`），进而回退预览线程、
// 造成 3.4s 停更——同一个 context 内反复创建/销毁大批全分辨率纹理后，Mali 的
// 分配状态不干净。整条销毁掉、下次 init 重建，则与"首次启动"完全同构
//（实测首次 initMs 59ms，重建 67ms，代价相同）。
extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeStillDestroy(JNIEnv*, jobject) {
  FrameRenderer* r = g_renderer_still.load();
  if (r != nullptr) {
    if (stillEgl().makeCurrent(g_still_surface)) r->release();
    delete r;
    g_renderer_still.store(nullptr);
  }
  if (g_still_surface != EGL_NO_SURFACE) {
    stillEgl().destroySurface(g_still_surface);
    g_still_surface = EGL_NO_SURFACE;
  }
  stillEgl().makeCurrent(EGL_NO_SURFACE);
  stillEgl().release();  // 连 context 一起销毁（display 常驻，下次 init 重建）
  Logger::instance().log(LogLevel::kInfo, "engine", "engine.still_destroy", "{}");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_filmkit_app_EngineBridge_nativeCreateOesTexture(JNIEnv*, jobject) {
  if (!g_renderer) return 0;
  glGenTextures(1, &g_oes_tex);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_oes_tex);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  g_renderer->setOesTexture(g_oes_tex);
  Logger::instance().log(LogLevel::kInfo, "engine", "oes.created",
                         "{\"id\":" + std::to_string(g_oes_tex) + "}");
  return static_cast<jint>(g_oes_tex);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSubmitST(
    JNIEnv* env, jobject, jfloatArray st, jint src_w, jint src_h, jint rotation,
    jdoubleArray film, jboolean fill_test) {
  if (!g_renderer || !g_window) return JNI_FALSE;
  (void)src_w;
  (void)src_h;

  float m[16] = {0};
  if (st) env->GetFloatArrayRegion(st, 0, 16, m);
  const FilmParams p = paramsFromJava(env, film);

  const int dst_w = ANativeWindow_getWidth(g_window);
  const int dst_h = ANativeWindow_getHeight(g_window);

  // 预览 center-crop：按（旋转换轴后的）源宽高比适配目标视口——铺满、不拉伸。
  // v0.3.5 的旧问题在于用未换轴的名义尺寸算错裁剪方向（横竖轴颠倒 → 双重裁剪）；
  // uScale 是绕中心采样窗：sx/sy 分别为 u/v 轴采样比例，按 rot 奇偶与源/目标
  // 宽高比关系选取缩轴，保证显示内容比例 = 视口比例。
  float sx = 1.0f, sy = 1.0f;
  if (src_w > 0 && src_h > 0 && dst_h > 0) {
    // 四象限组合（v0.5.4 教训：sx/sy 分配依赖着色器自转奇偶，不能只看净旋转）：
    //   even+无ST换轴: 显示比例 = sx*W/(sy*H)   odd+无ST换轴: 显示比例 = sy*H/(sx*W)
    //   even+ST换轴:   显示比例 = sx*H/(sy*W)   odd+ST换轴:   显示比例 = sy*W/(sx*H)
    // srcAspect 取换轴后的源宽高比；sx/sy 谁缩由 shaderSwap 决定，与已知正确的
    // v0.5.2 状态（even+stSwap → 全 1）自洽。
    const bool stSwap = m[4] > 0.5f || m[4] < -0.5f;
    const bool shaderSwap = (rotation % 2) != 0;
    const bool swapped = stSwap != shaderSwap;
    const float srcAspect =
        swapped ? (float)src_h / (float)src_w : (float)src_w / (float)src_h;
    const float a = (float)dst_w / (float)dst_h;
    if (!shaderSwap) {
      sx = (a / srcAspect < 1.0f) ? a / srcAspect : 1.0f;
      sy = (srcAspect / a < 1.0f) ? srcAspect / a : 1.0f;
    } else {
      sx = (srcAspect / a < 1.0f) ? srcAspect / a : 1.0f;
      sy = (a / srcAspect < 1.0f) ? a / srcAspect : 1.0f;
    }
  }

  const bool ok = g_renderer->submitFrame(m, rotation, sx, sy, dst_w, dst_h, p,
                                          fill_test == JNI_TRUE);
  if (!ok) return JNI_FALSE;

  if (!engineEgl().swapBuffers(g_win_surface)) {
    Logger::instance().log(LogLevel::kError, "engine", "render.swap_failed",
                           "{\"frames\":" +
                               std::to_string(g_renderer->frameCount()) + "}");
    return JNI_FALSE;
  }
  static int64_t last_logged = 0;
  const int64_t f = g_renderer->frameCount();
  if (f == 1 || f - last_logged >= 120) {
    last_logged = f;
    char rs[16];
    std::snprintf(rs, sizeof(rs), "%.2f", FrameRenderer::previewScale());
    Logger::instance().log(
        LogLevel::kInfo, "engine", "preview.frame",
        "{\"frames\":" + std::to_string(f) +
            ",\"dstW\":" + std::to_string(dst_w) +
            ",\"dstH\":" + std::to_string(dst_h) + ",\"rs\":" + rs + "}");
  }
  return JNI_TRUE;
}

// 静拍 YUV 解码矩阵：0 = BT.601（默认），1 = BT.709
// v0.10.85（审查 R4）：设置页在**主线程**调本函数，而 g_renderer 由预览线程
// 创建/delete —— 直接写 g_renderer->yuv_matrix_（无锁内联）是既存的 UAF 崩溃点。
// 现在只写进程级快照，实际下发在 nativeEngineStart（预览线程）。代价：切换开关
// 需下次进入相机生效（设置页文案已注明）。
// 注：YUV 单帧静拍路径仍在预览渲染线程上（未迁移到拍照侧实例），所以只作用于
// g_renderer；将来迁移这条路径时要一并同步到 g_renderer_still。
extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSetYuvMatrix(JNIEnv* env, jobject,
                                                           jint m) {
  (void)env;
  g_yuv_matrix.store(m);
}

// 预览降载（v0.10.93）：拍照期间把预览链中间渲染缩到 s×。任意线程可调——
// 写方是进程级 atomic（FrameRenderer.cpp 的 g_preview_scale），渲染线程每帧
// submitFrame 开头 load，下一帧生效；不需要排队到渲染线程，也没有与
// nativeEngineStop 的生命周期竞态。成片链不经 submitFrame，画质不受影响。
extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSetPreviewScale(JNIEnv*, jobject,
                                                              jfloat scale) {
  FrameRenderer::setPreviewScale(scale);
}

// M4：3D LUT（display 域）。data = RGB 交错（.cube 顺序，红最快），长度 size³×3；
// size ≤ 1 或 data 为空 = 关闭。任意线程可调（引擎内部暂存，渲染线程上传纹理）。
// v0.10.85：同时写入进程级快照并下发到预览 / 拍照两个实例 —— 拍照侧实例可能
// 还没建（懒创建），快照保证它建起来时立刻补齐风格层。
extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSetLut(JNIEnv* env, jobject, jint size,
                                                     jfloatArray data,
                                                     jfloatArray domain_min,
                                                     jfloatArray domain_max) {
  if (data == nullptr || size <= 1) {
    {
      std::lock_guard<std::mutex> lk(g_lut_mu);
      g_lut_data.clear();
      g_lut_size = 0;
    }
    applyLutSnapshot(g_renderer);
    applyLutSnapshot(g_renderer_still.load());
    return;
  }
  const jsize want = static_cast<jsize>(size) * size * size * 3;
  if (env->GetArrayLength(data) < want) {
    // 契约不符：宁可不生效也不越界读（真机日志可由 lut.load 缺项反查）
    return;
  }
  std::vector<float> buf(static_cast<size_t>(want));
  env->GetFloatArrayRegion(data, 0, want, buf.data());
  float dmin[3] = {0.0f, 0.0f, 0.0f};
  float dmax[3] = {1.0f, 1.0f, 1.0f};
  if (domain_min != nullptr && env->GetArrayLength(domain_min) >= 3) {
    env->GetFloatArrayRegion(domain_min, 0, 3, dmin);
  }
  if (domain_max != nullptr && env->GetArrayLength(domain_max) >= 3) {
    env->GetFloatArrayRegion(domain_max, 0, 3, dmax);
  }
  {
    std::lock_guard<std::mutex> lk(g_lut_mu);
    g_lut_data = buf;
    g_lut_size = size;
    for (int i = 0; i < 3; ++i) {
      g_lut_dmin[i] = dmin[i];
      g_lut_dmax[i] = dmax[i];
    }
  }
  applyLutSnapshot(g_renderer);
  applyLutSnapshot(g_renderer_still.load());
}

// 静拍：全分辨率 YUV → CPU 转 RGB → 胶片链 → 读回 RGBA（旋转后尺寸）
// use_still（v0.10.93）：走拍照侧实例 —— 单帧路径迁出预览线程，亮光下拍一张
// 不再把预览渲染线程占住 ~1s（实测单帧 RCD passMs 766ms → 预览 gap 1038ms、
// fps 掉到 2）。路由语义与 nativeEngineSubmitRawBurst 完全一致。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSubmitStill(
    JNIEnv* env, jobject, jobject y_buf, jobject u_buf, jobject v_buf, jint w,
    jint h, jint y_stride, jint uv_stride, jint uv_pix, jint rotation,
    jdoubleArray film, jboolean use_still) {
  FrameRenderer* r = g_renderer;
  if (use_still == JNI_TRUE) {
    if (g_renderer_still.load() == nullptr) {
      Logger::instance().log(LogLevel::kError, "engine", "engine.still_missing",
                             "{}");
      return nullptr;
    }
    if (!stillEgl().makeCurrent(g_still_surface)) return nullptr;
    r = g_renderer_still.load();
  }
  if (!r) return nullptr;
  const FilmParams p = paramsFromJava(env, film);
  std::vector<uint8_t> rgba;
  const int64_t t0 = nowMs();
  const bool ok = r->submitStill(
      env->GetDirectBufferAddress(y_buf), env->GetDirectBufferAddress(u_buf),
      v_buf ? env->GetDirectBufferAddress(v_buf) : nullptr, w, h, y_stride,
      uv_stride, uv_pix, rotation, p, &rgba);
  Logger::instance().log(
      LogLevel::kInfo, "camera", "render.single_done",
      "{\"kind\":\"yuv\",\"path\":\"" +
          std::string(use_still == JNI_TRUE ? "still" : "preview") +
          "\",\"ms\":" + std::to_string(nowMs() - t0) +
          ",\"ok\":" + (ok ? "true" : "false") +
          ",\"bytes\":" + std::to_string(rgba.size()) + "}");
  if (!ok) return nullptr;
  return rgbaToJavaArray(env, rgba);
}

// 静拍 RAW（M2-3）：u16 Bayer → 黑阶/WB/demosaic/CCM（GPU）→ 胶片链 → RGBA
// demosaic：0=bilinear（速度档）1=RCD 三段管线（质量档，M3-1）
// noise_strength：降噪强度 0-2（C 专项滑杆：0=关 1=真实 σ 2=2×σ，仅 RCD 生效）
// noise_model：6 float [S_r,S_g,S_b,O_r,O_g,O_b]（C 专项，[0,1] 归一化 raw 域）；
//              null/长度不足 = 无效 → 走 noise_sigma legacy 回退
// noise_sigma：归一化域噪声 σ（legacy 回退；≤0 时引擎内估计）
// lsc/lsc_cols/lsc_rows：镜头阴影增益网格（RGB 交错，null = 关闭/无数据 → 恒等）
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSubmitRaw(
    JNIEnv* env, jobject, jobject raw_buf, jint w, jint h, jint row_stride_px,
    jint cfa, jfloatArray black_phase, jint white_level, jfloat neutral_r,
    jfloat neutral_g, jfloat neutral_b, jfloatArray ccm, jfloatArray lsc,
    jint lsc_cols, jint lsc_rows, jint rotation, jint demosaic,
    jfloat noise_strength, jfloatArray noise_model, jfloat noise_sigma,
    jdoubleArray film, jboolean use_still, jboolean nn_dn) {
  // NN 回桥（v0.10.102）：注入推理/信息回调 + 刷新 env 快照（契约见 g_nn 注释）
  g_nn.env = env;
  FrameRenderer::setNnInferFn(&nnBridgeInfer);
  FrameRenderer::setNnInfoFn(&nnBridgeInfo);
  // v0.10.93：单帧 RAW 与 MFNR burst 同构的拍照侧实例路由（use_still），单帧
  // 合成（passMs 610~766ms）不再占住预览渲染线程。调用线程已在 nativeStillStart
  // makeCurrent，这里再确认一次（幂等）。
  FrameRenderer* r = g_renderer;
  if (use_still == JNI_TRUE) {
    if (g_renderer_still.load() == nullptr) {
      Logger::instance().log(LogLevel::kError, "engine", "engine.still_missing",
                             "{}");
      return nullptr;
    }
    if (!stillEgl().makeCurrent(g_still_surface)) return nullptr;
    r = g_renderer_still.load();
  }
  if (!r || !raw_buf) return nullptr;
  float black4[4] = {0, 0, 0, 0};
  if (black_phase) {
    const jsize bn = env->GetArrayLength(black_phase);
    if (bn >= 4) env->GetFloatArrayRegion(black_phase, 0, 4, black4);
  }
  float ccm9[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  if (ccm) {
    const jsize cn = env->GetArrayLength(ccm);
    if (cn >= 9) env->GetFloatArrayRegion(ccm, 0, 9, ccm9);
  }
  // C 专项：噪声模型（6 float；长度不足 = 无效，防呆）
  float model6[6] = {0, 0, 0, 0, 0, 0};
  bool model_ok = false;
  if (noise_model != nullptr && env->GetArrayLength(noise_model) >= 6) {
    env->GetFloatArrayRegion(noise_model, 0, 6, model6);
    model_ok = true;
  }
  // LSC 网格：长度不足视为无数据（防呆，避免越界读）
  std::vector<float> lsc_buf;
  const float* lsc_ptr = nullptr;
  if (lsc != nullptr && lsc_cols > 1 && lsc_rows > 1) {
    const jsize want = static_cast<jsize>(lsc_cols) * lsc_rows * 3;
    if (env->GetArrayLength(lsc) >= want) {
      lsc_buf.resize(static_cast<size_t>(want));
      env->GetFloatArrayRegion(lsc, 0, want, lsc_buf.data());
      lsc_ptr = lsc_buf.data();
    }
  }
  const FilmParams p = paramsFromJava(env, film);
  std::vector<uint8_t> rgba;
  const int64_t t0 = nowMs();
  const bool ok = r->submitRaw(
      env->GetDirectBufferAddress(raw_buf), w, h, row_stride_px, cfa, black4,
      white_level, neutral_r, neutral_g, neutral_b, ccm ? ccm9 : nullptr,
      lsc_ptr, lsc_cols, lsc_rows, rotation, p, demosaic, noise_strength,
      model_ok ? model6 : nullptr, noise_sigma, nn_dn == JNI_TRUE, &rgba);
  Logger::instance().log(
      LogLevel::kInfo, "camera", "render.single_done",
      "{\"kind\":\"raw\",\"path\":\"" +
          std::string(use_still == JNI_TRUE ? "still" : "preview") +
          "\",\"ms\":" + std::to_string(nowMs() - t0) +
          ",\"ok\":" + (ok ? "true" : "false") +
          ",\"bytes\":" + std::to_string(rgba.size()) + "}");
  if (!ok) return nullptr;
  return rgbaToJavaArray(env, rgba);
}

// 多帧融合（MFNR 阶段一，v0.9.0）：count 帧 u16 Bayer → Bayer 域对齐 + 加权
// 均值（引擎内）→ 与 nativeEngineSubmitRaw 相同的成片链。
// frames[i] = 第 i 帧的 direct ByteBuffer（Image plane[0]，参考帧 = frames[0]）；
// row_strides_px[i] = 像素单位行距；其余元数据语义同 nativeEngineSubmitRaw，
// 统一取帧 0（burst 恒曝光）。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSubmitRawBurst(
    JNIEnv* env, jobject, jobjectArray frames, jint count,
    jintArray row_strides_px, jint w, jint h, jint cfa, jfloatArray black_phase,
    jint white_level, jfloat neutral_r, jfloat neutral_g, jfloat neutral_b,
    jfloatArray ccm, jfloatArray lsc, jint lsc_cols, jint lsc_rows,
    jint rotation, jint demosaic, jfloat noise_strength, jfloatArray noise_model,
    jfloat noise_sigma, jfloat ref_noise_scale, jboolean use_dft, jboolean align_l1,
    jboolean align_ab, jboolean p0_sub, jboolean nn_dn, jdoubleArray film,
    jboolean use_still) {
  // NN 回桥（v0.10.102）：注入推理/信息回调 + 刷新 env 快照（契约见 g_nn 注释）
  g_nn.env = env;
  FrameRenderer::setNnInferFn(&nnBridgeInfer);
  FrameRenderer::setNnInfoFn(&nnBridgeInfo);
  // v0.10.85：use_still = 走拍照侧实例（独立线程 + 独立 context），预览线程不再
  // 被这条 1.6~3.4s 的链阻塞。context 是 per-thread current，调用线程已在
  // nativeStillStart 里 makeCurrent；这里再确认一次（幂等）。
  FrameRenderer* r = g_renderer;
  if (use_still == JNI_TRUE) {
    if (g_renderer_still.load() == nullptr) {
      // Kotlin 侧 ensureStarted 成功才会走这条路，走到这里说明生命周期出错：
      // 明确报错而不是偷偷退回预览实例（那个 context 不在本线程上 current）
      Logger::instance().log(LogLevel::kError, "engine", "engine.still_missing",
                             "{}");
      return nullptr;
    }
    if (!stillEgl().makeCurrent(g_still_surface)) return nullptr;
    r = g_renderer_still.load();
  }
  if (!r || !frames || count <= 0) return nullptr;
  std::vector<const void*> bufs(static_cast<size_t>(count), nullptr);
  for (int i = 0; i < count; ++i) {
    jobject buf = env->GetObjectArrayElement(frames, i);
    if (!buf) return nullptr;
    bufs[static_cast<size_t>(i)] = env->GetDirectBufferAddress(buf);
    env->DeleteLocalRef(buf);
    if (!bufs[static_cast<size_t>(i)]) return nullptr;
  }
  std::vector<int> strides(static_cast<size_t>(count), 0);
  if (env->GetArrayLength(row_strides_px) >= count) {
    env->GetIntArrayRegion(row_strides_px, 0, count, strides.data());
  }
  float black4[4] = {0, 0, 0, 0};
  if (black_phase) {
    const jsize bn = env->GetArrayLength(black_phase);
    if (bn >= 4) env->GetFloatArrayRegion(black_phase, 0, 4, black4);
  }
  float ccm9[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
  if (ccm) {
    const jsize cn = env->GetArrayLength(ccm);
    if (cn >= 9) env->GetFloatArrayRegion(ccm, 0, 9, ccm9);
  }
  // C 专项：噪声模型（6 float；长度不足 = 无效，防呆）
  float model6[6] = {0, 0, 0, 0, 0, 0};
  bool model_ok = false;
  if (noise_model != nullptr && env->GetArrayLength(noise_model) >= 6) {
    env->GetFloatArrayRegion(noise_model, 0, 6, model6);
    model_ok = true;
  }
  std::vector<float> lsc_buf;
  const float* lsc_ptr = nullptr;
  if (lsc != nullptr && lsc_cols > 1 && lsc_rows > 1) {
    const jsize want = static_cast<jsize>(lsc_cols) * lsc_rows * 3;
    if (env->GetArrayLength(lsc) >= want) {
      lsc_buf.resize(static_cast<size_t>(want));
      env->GetFloatArrayRegion(lsc, 0, want, lsc_buf.data());
      lsc_ptr = lsc_buf.data();
    }
  }
  const FilmParams p = paramsFromJava(env, film);
  std::vector<uint8_t> rgba;
  const int64_t t0 = nowMs();
  const bool ok = r->submitRawBurst(
      bufs.data(), count, strides.data(), w, h, cfa, black4, white_level,
      neutral_r, neutral_g, neutral_b, ccm ? ccm9 : nullptr, lsc_ptr, lsc_cols,
      lsc_rows, rotation, p, demosaic, noise_strength,
      model_ok ? model6 : nullptr, noise_sigma, ref_noise_scale,
      use_dft == JNI_TRUE, align_l1 == JNI_TRUE, align_ab == JNI_TRUE,
      p0_sub == JNI_TRUE, nn_dn == JNI_TRUE, &rgba);
  // 每次 burst 落一条路径/耗时/结果记录（v0.10.85 审查 P2）：raw.render 不区分实例，
  // 靠这条把"哪条路、第几张、多久、成没成"钉住；失败也必须留痕。
  Logger::instance().log(
      LogLevel::kInfo, "camera", "render.burst_done",
      "{\"seq\":" + std::to_string(g_burst_seq.fetch_add(1) + 1) +
          ",\"path\":\"" + (use_still == JNI_TRUE ? "still" : "preview") +
          "\",\"ms\":" + std::to_string(nowMs() - t0) +
          ",\"ok\":" + (ok ? "true" : "false") +
          ",\"bytes\":" + std::to_string(rgba.size()) + "}");
  if (!ok) return nullptr;
  return rgbaToJavaArray(env, rgba);
}

// 预览实时直方图（M3）：out 长度 ≥2（建议 256），填充显示域亮度分布；
// 返回 false = 还没有可用的预览帧（纹理未就绪）
extern "C" JNIEXPORT jboolean JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineHistogram(JNIEnv* env, jobject,
                                                        jintArray out) {
  if (!g_renderer || !out) return JNI_FALSE;
  const jsize n = env->GetArrayLength(out);
  if (n < 2) return JNI_FALSE;
  std::vector<int> h(static_cast<size_t>(n), 0);
  if (!g_renderer->histogram(h.data(), n)) return JNI_FALSE;
  env->SetIntArrayRegion(out, 0, n, h.data());
  return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineGetStats(JNIEnv* env, jobject) {
  const int64_t frames = g_renderer ? g_renderer->frameCount() : 0;
  const int w = g_renderer ? g_renderer->sceneW() : 0;
  const int h = g_renderer ? g_renderer->sceneH() : 0;
  return toJString(env, "{\"frames\":" + std::to_string(frames) +
                            ",\"w\":" + std::to_string(w) +
                            ",\"h\":" + std::to_string(h) + "}");
}
