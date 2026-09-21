#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "../gl/Capabilities.h"
#include "../gl/EglCore.h"
#include "../log/Logger.h"
#include "../render/FrameRenderer.h"
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
    Logger::instance().log(
        LogLevel::kInfo, "engine", "preview.frame",
        "{\"frames\":" + std::to_string(f) +
            ",\"dstW\":" + std::to_string(dst_w) +
            ",\"dstH\":" + std::to_string(dst_h) + "}");
  }
  return JNI_TRUE;
}

// 静拍 YUV 解码矩阵：0 = BT.601（默认），1 = BT.709
extern "C" JNIEXPORT void JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSetYuvMatrix(JNIEnv* env, jobject,
                                                           jint m) {
  if (g_renderer) g_renderer->setYuvMatrix(m);
}

// 静拍：全分辨率 YUV → CPU 转 RGB → 胶片链 → 读回 RGBA（旋转后尺寸）
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSubmitStill(
    JNIEnv* env, jobject, jobject y_buf, jobject u_buf, jobject v_buf, jint w,
    jint h, jint y_stride, jint uv_stride, jint uv_pix, jint rotation,
    jdoubleArray film) {
  if (!g_renderer) return nullptr;
  const FilmParams p = paramsFromJava(env, film);
  std::vector<uint8_t> rgba;
  const bool ok = g_renderer->submitStill(
      env->GetDirectBufferAddress(y_buf), env->GetDirectBufferAddress(u_buf),
      v_buf ? env->GetDirectBufferAddress(v_buf) : nullptr, w, h, y_stride,
      uv_stride, uv_pix, rotation, p, &rgba);
  if (!ok) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(rgba.size()));
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgba.size()),
                          reinterpret_cast<const jbyte*>(rgba.data()));
  return out;
}

// 静拍 RAW（M2-3）：u16 Bayer → 黑阶/WB/demosaic/CCM（GPU）→ 胶片链 → RGBA
// demosaic：0=bilinear（速度档）1=RCD 三段管线（质量档，M3-1）
// noise_level：0=关 1=标准 2=强（M3-2 降噪，仅 RCD 生效）；noise_sigma：归一化域噪声 σ
// lsc/lsc_cols/lsc_rows：镜头阴影增益网格（RGB 交错，null = 关闭/无数据 → 恒等）
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEngineSubmitRaw(
    JNIEnv* env, jobject, jobject raw_buf, jint w, jint h, jint row_stride_px,
    jint cfa, jfloatArray black_phase, jint white_level, jfloat neutral_r,
    jfloat neutral_g, jfloat neutral_b, jfloatArray ccm, jfloatArray lsc,
    jint lsc_cols, jint lsc_rows, jint rotation, jint demosaic, jint noise_level,
    jfloat noise_sigma, jdoubleArray film) {
  if (!g_renderer || !raw_buf) return nullptr;
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
  const bool ok = g_renderer->submitRaw(
      env->GetDirectBufferAddress(raw_buf), w, h, row_stride_px, cfa, black4,
      white_level, neutral_r, neutral_g, neutral_b, ccm ? ccm9 : nullptr,
      lsc_ptr, lsc_cols, lsc_rows, rotation, p, demosaic, noise_level,
      noise_sigma, &rgba);
  if (!ok) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(rgba.size()));
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(rgba.size()),
                          reinterpret_cast<const jbyte*>(rgba.data()));
  return out;
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
