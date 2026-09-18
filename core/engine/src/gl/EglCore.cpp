#include "EglCore.h"

#include <android/log.h>

namespace filmkit {

namespace {
constexpr const char* kTag = "filmkit";
}

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

bool EglCore::init() {
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
  const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctx_attrs);
  if (context_ == EGL_NO_CONTEXT) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "eglCreateContext 失败");
    return false;
  }

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
