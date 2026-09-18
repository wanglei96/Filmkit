#include "GpuTimer.h"

#include <EGL/egl.h>
#include <GLES3/gl31.h>

#include <string>

#ifndef GL_EXT_disjoint_timer_query
#define GL_TIME_ELAPSED_EXT 0x88BF
#define GL_GPU_DISJOINT_EXT 0x8FBD
#endif

// 64bit 查询结果读取入口属于扩展函数，NDK 头文件不保证声明，动态取地址。
using GlGetQueryObjectUi64vEXT = void (*)(unsigned int, unsigned int,
                                          unsigned long long*);

namespace filmkit {

namespace {
GlGetQueryObjectUi64vEXT queryUi64Proc() {
  // eglGetProcAddress 与 glXGetProcAddress 不同，Android 上扩展入口
  // 也可直接链接；这里统一走 eglGetProcAddress 以兼容驱动差异。
  auto p = reinterpret_cast<GlGetQueryObjectUi64vEXT>(
      eglGetProcAddress("glGetQueryObjectui64vEXT"));
  return p;
}
}  // namespace

bool GpuTimer::supported() {
  GLint n = 0;
  glGetIntegerv(GL_NUM_EXTENSIONS, &n);
  for (GLint i = 0; i < n; ++i) {
    const GLubyte* e = glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i));
    if (e && std::string(reinterpret_cast<const char*>(e)) ==
                 "GL_EXT_disjoint_timer_query") {
      return true;
    }
  }
  return false;
}

GpuTimer::GpuTimer() {
  glGenQueries(1, &query_);
}

GpuTimer::~GpuTimer() {
  if (query_) glDeleteQueries(1, &query_);
}

void GpuTimer::begin() {
  if (!query_) return;
  glBeginQuery(GL_TIME_ELAPSED_EXT, query_);
  active_ = true;
}

double GpuTimer::endMs() {
  if (!query_ || !active_) return -1.0;
  glEndQuery(GL_TIME_ELAPSED_EXT);
  active_ = false;

  // M0：glFinish 保证查询结果就绪，代价是打断流水线（产品化后改延迟收集）。
  glFinish();

  GLint disjoint = 0;
  glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
  if (disjoint) return -1.0;

  unsigned long long ns = 0;
  if (auto p64 = queryUi64Proc()) {
    p64(query_, GL_QUERY_RESULT, &ns);
  } else {
    GLuint v32 = 0;
    glGetQueryObjectuiv(query_, GL_QUERY_RESULT, &v32);
    ns = v32;  // 低 32bit 纳秒，~4.3s 内不溢出，M0 的 pass 量级远低于此
  }
  return static_cast<double>(ns) / 1e6;
}

}  // namespace filmkit
