#pragma once

#include <EGL/egl.h>

namespace filmkit {

// EGL 环境封装（M1）：display/context 全局复用，surface 按需创建。
// 所有方法必须在持有它的渲染线程调用。
class EglCore {
 public:
  // 初始化 display/context（幂等）；share 传入可共享上下文（M1 用不到，预留）。
  // background=true：请求 EGL_IMG_context_priority 低优先级上下文（拍照链专用），
  // 让预览 context 在 GPU 调度上插队；扩展不支持/创建失败时静默回退普通上下文。
  // 0.13.18：high=true 请求 HIGH 优先级（预览链专用，压过 still 的条带 job）。
  bool init(bool background = false, bool high = false);
  void release();
  bool lowPriorityContext() const { return low_priority_; }
  bool highPriorityContext() const { return high_priority_; }
  bool contextPriorityExt() const { return priority_ext_; }

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
  bool priority_ext_ = false;   // EGL_IMG_context_priority 扩展可用
  bool low_priority_ = false;   // 本 context 实际以 LOW 优先级创建
  bool high_priority_ = false;  // 本 context 实际以 HIGH 优先级创建
};

}  // namespace filmkit
