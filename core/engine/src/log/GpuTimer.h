#pragma once

namespace filmkit {

// GL_EXT_disjoint_timer_query 封装（docs/开发计划.md §3.3-7）。
// begin()/endMs() 必须在持有当前 GL 上下文的线程调用。
// M0 约束：endMs() 内部用 glFinish 强制收结果，计得的是干净的逐 pass 耗时，
// 但会打断 GPU 流水线；M1+ 改为延迟收集（下一帧再读结果）。
class GpuTimer {
 public:
  // 每次构造时查询扩展是否可用（须有当前上下文）。
  static bool supported();

  GpuTimer();
  ~GpuTimer();

  GpuTimer(const GpuTimer&) = delete;
  GpuTimer& operator=(const GpuTimer&) = delete;

  void begin();
  // 返回 GPU 耗时毫秒；-1.0 表示结果无效（不支持 / disjoint / 未 begin）。
  double endMs();

 private:
  unsigned int query_ = 0;
  bool active_ = false;
};

}  // namespace filmkit
