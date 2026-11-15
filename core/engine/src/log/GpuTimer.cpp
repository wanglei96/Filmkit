#include "GpuTimer.h"

#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>

#include <string>

// Use the platform EXT enums. GL_GPU_DISJOINT_EXT is 0x8FBB, not 0x8FBD.


// 64bit 查询结果读取入口属于扩展函数，NDK 头文件不保证声明，动态取地址。
using GenQueriesExt = void (*)(int, unsigned int*);
using DeleteQueriesExt = void (*)(int, const unsigned int*);
using BeginQueryExt = void (*)(unsigned int, unsigned int);
using EndQueryExt = void (*)(unsigned int);
using GetQueryUi64Ext = void (*)(unsigned int, unsigned int, unsigned long long*);

namespace filmkit {
namespace {
struct TimerApi {
  GenQueriesExt gen = reinterpret_cast<GenQueriesExt>(eglGetProcAddress("glGenQueriesEXT"));
  DeleteQueriesExt del = reinterpret_cast<DeleteQueriesExt>(eglGetProcAddress("glDeleteQueriesEXT"));
  BeginQueryExt begin = reinterpret_cast<BeginQueryExt>(eglGetProcAddress("glBeginQueryEXT"));
  EndQueryExt end = reinterpret_cast<EndQueryExt>(eglGetProcAddress("glEndQueryEXT"));
  GetQueryUi64Ext result = reinterpret_cast<GetQueryUi64Ext>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
  bool valid() const { return gen && del && begin && end && result; }
};
const TimerApi& api() { static const TimerApi value; return value; }
}  // namespace

bool GpuTimer::supported() {
  GLint n = 0;
  glGetIntegerv(GL_NUM_EXTENSIONS, &n);
  for (GLint i = 0; i < n; ++i) {
    const GLubyte* e = glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i));
    if (e && std::string(reinterpret_cast<const char*>(e)) ==
                 "GL_EXT_disjoint_timer_query") {
      return api().valid();
    }
  }
  return false;
}

GpuTimer::GpuTimer() {
  if (supported()) api().gen(1, &query_);
}

GpuTimer::~GpuTimer() {
  if (query_) { if (active_) api().end(GL_TIME_ELAPSED_EXT); api().del(1, &query_); }
}

void GpuTimer::begin() {
  if (!query_) return;
  api().begin(GL_TIME_ELAPSED_EXT, query_);
  active_ = true;
}

double GpuTimer::endMs() {
  if (!query_ || !active_) return -1.0;
  api().end(GL_TIME_ELAPSED_EXT);
  active_ = false;

  // M0：glFinish 保证查询结果就绪，代价是打断流水线（产品化后改延迟收集）。
  glFinish();

  GLint disjoint = 0;
  glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
  if (disjoint) return -1.0;

  unsigned long long ns = 0;
  api().result(query_, GL_QUERY_RESULT, &ns);
  return static_cast<double>(ns) / 1e6;
}

}  // namespace filmkit
