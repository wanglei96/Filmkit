#include "EglCore.h"

#include <EGL/eglext.h>
#include <android/log.h>
#include <cstring>

namespace filmkit {

namespace {
constexpr const char* kTag = "filmkit";
constexpr const char* kContextPriorityExt = "EGL_IMG_context_priority";
}  // namespace

bool EglCore::ensureDisplay() {
  if (display_ != EGL_NO_DISPLAY) return true;
  display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display_ == EGL_NO_DISPLAY) return false;
  EGLint maj = 0, min = 0;
  if (!eglInitialize(display_, &maj, &min)) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "eglInitialize 失败");
    display_ = EGL_NO_DISPLAY;
    return false;
  }
  return true;
}

bool EglCore::init(bool background, bool high) {
  if (!ensureDisplay()) return false;
  if (context_ != EGL_NO_CONTEXT) return true;  // 幂等

  // 不带 alpha：SurfaceView + 带 alpha 的窗口面在部分 OEM 合成器上会黑屏
  // （GLSurfaceView 同样倾向无 alpha 配置）
  const EGLint cfg_attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                              EGL_SURFACE_TYPE,
                              EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                              EGL_RED_SIZE, 8,
                              EGL_GREEN_SIZE, 8,
                              EGL_BLUE_SIZE, 8,
                              EGL_NONE};
  EGLint n = 0;
  EGLConfig cfg = nullptr;
  if (!eglChooseConfig(display_, cfg_attrs, &cfg, 1, &n) || n < 1) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "eglChooseConfig 失败");
    return false;
  }
  config_ = cfg;

  // client version 3 → 驱动返回其支持的 ES 3.x（3.0/3.1/3.2）
  const char* exts = eglQueryString(display_, EGL_EXTENSIONS);
  priority_ext_ = exts != nullptr && std::strstr(exts, kContextPriorityExt);
  requested_priority_ = high ? EGL_CONTEXT_PRIORITY_HIGH_IMG
                            : background ? EGL_CONTEXT_PRIORITY_LOW_IMG
                                         : EGL_CONTEXT_PRIORITY_MEDIUM_IMG;
  context_ = EGL_NO_CONTEXT;
  if ((background || high) && priority_ext_) {
    // PC GlesGpuScheduler.createBackgroundContext 同款：拍照链在低优先级 context
    // 里跑，GPU 调度器才肯让预览插队（0.13.8 只加条带没加这个，冻结只降 17ms）。
    // 0.13.18：预览 context 显式 HIGH —— still 处理 3~4s 期间 GPU 常满，MEDIUM
    // 对 LOW 的让路粒度仍不够（真机 fps 30→24~27）；HIGH 让预览的 job 排在
    // still 的条带边界前面。创建失败静默回退普通上下文（与 LOW 同处理）。
    const EGLint pri_attrs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_PRIORITY_LEVEL_IMG,
        requested_priority_, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, pri_attrs);
    if (context_ == EGL_NO_CONTEXT) eglGetError();  // 清掉失败码，回退普通上下文
  }
  if (context_ == EGL_NO_CONTEXT) {
    const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctx_attrs);
  }
  if (context_ == EGL_NO_CONTEXT) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "eglCreateContext 失败");
    return false;
  }
  actual_priority_ = 0;
  if (priority_ext_ && !eglQueryContext(display_, context_,
          EGL_CONTEXT_PRIORITY_LEVEL_IMG, &actual_priority_)) {
    actual_priority_ = 0;
    __android_log_print(ANDROID_LOG_WARN, kTag,
                        "eglQueryContext priority failed: 0x%x", eglGetError());
  }
  low_priority_ = actual_priority_ == EGL_CONTEXT_PRIORITY_LOW_IMG;
  high_priority_ = actual_priority_ == EGL_CONTEXT_PRIORITY_HIGH_IMG;

  // 1×1 pbuffer：窗口面创建前保持上下文可用（纹理/program 预备）
  const EGLint pb_attrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
  pbuffer_ = eglCreatePbufferSurface(display_, config_, pb_attrs);
  return true;
}

void EglCore::release() {
  if (display_ == EGL_NO_DISPLAY) return;
  eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (pbuffer_ != EGL_NO_SURFACE) {
    eglDestroySurface(display_, pbuffer_);
    pbuffer_ = EGL_NO_SURFACE;
  }
  if (context_ != EGL_NO_CONTEXT) {
    eglDestroyContext(display_, context_);
    context_ = EGL_NO_CONTEXT;
  }
  actual_priority_ = 0;
  low_priority_ = high_priority_ = false;
  // display 常驻；进程结束时由驱动回收
}

EGLSurface EglCore::createWindowSurface(void* anative_window) {
  const EGLSurface s = eglCreateWindowSurface(
      display_, config_, static_cast<EGLNativeWindowType>(anative_window),
      nullptr);
  if (s == EGL_NO_SURFACE) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "createWindowSurface 失败: 0x%x",
                        eglGetError());
  }
  return s;
}

EGLSurface EglCore::createPbufferSurface(int w, int h) {
  const EGLint attrs[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
  return eglCreatePbufferSurface(display_, config_, attrs);
}

void EglCore::destroySurface(EGLSurface s) {
  if (s != EGL_NO_SURFACE) eglDestroySurface(display_, s);
}

bool EglCore::makeCurrent(EGLSurface s) {
  return eglMakeCurrent(display_, s, s, s == EGL_NO_SURFACE ? EGL_NO_CONTEXT
                                                            : context_);
}

bool EglCore::swapBuffers(EGLSurface s) {
  return eglSwapBuffers(display_, s);
}

}  // namespace filmkit
