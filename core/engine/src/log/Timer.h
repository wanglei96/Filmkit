#pragma once

#include <chrono>

namespace filmkit {

// 单调时钟毫秒（进程内计时，用于 CPU 段计时）。
inline double nowMs() {
  using namespace std::chrono;
  return duration<double, std::milli>(steady_clock::now().time_since_epoch())
      .count();
}

// RAII：作用域结束时把耗时（毫秒）写入 out_ms。
class ScopedCpuTimer {
 public:
  explicit ScopedCpuTimer(double* out_ms) : out_(out_ms), start_(nowMs()) {}
  ~ScopedCpuTimer() {
    if (out_) *out_ = nowMs() - start_;
  }
  ScopedCpuTimer(const ScopedCpuTimer&) = delete;
  ScopedCpuTimer& operator=(const ScopedCpuTimer&) = delete;

 private:
  double* out_;
  double start_;
};

}  // namespace filmkit
