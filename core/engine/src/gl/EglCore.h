#pragma once

#include <EGL/egl.h>

namespace filmkit {

// EGL 环境封装（M1）：display/context 全局复用，surface 按需创建。
// 所有方法必须在持有它的渲染线程调用。
class EglCore {
 public:
  // 初始化 display/context（幂等）；share 传入可共享上下文（M1 用不到，预留）。
  bool init();
  void release();

  EGLSurface createWindowSurface(void* anative_window);
  EGLSurface createPbufferSurface(int w, int h);
  void destroySurface(EGLSurface surface);
  bool makeCurrent(EGLSurface surface);  // EGL_NO_SURFACE = 释放当前
  bool swapBuffers(EGLSurface surface);

 private:
  bool ensureDisplay();

  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLContext context_ = EGL_NO_CONTEXT;
  EGLConfig config_ = nullptr;
  EGLSurface pbuffer_ = EGL_NO_SURFACE;
};

}  // namespace filmkit
