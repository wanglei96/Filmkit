#pragma once

#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace filmkit {

struct PassTiming {
  std::string name;
  double gpu_ms = -1.0;
  double cpu_ms = -1.0;
};

// M0 管线骨架：顺序 pass 执行 + 计时收集。
// M1+ 按计划演进：纹理池复用、异步管线图、pass 合并（docs/开发计划.md §3.3）。
class Pipeline {
 public:
  // pass 执行体：跑完把自己的计时写进 *out，成功返回 true。
  using Fn = std::function<bool(PassTiming* out)>;

  void addPass(std::string name, Fn fn);
  // 依次执行全部 pass；任一失败立即中止，返回 false。
  // timings 依次记录每个已执行 pass 的名称与耗时。
  bool run(std::vector<PassTiming>* timings);
  void clear();

 private:
  std::vector<std::pair<std::string, Fn>> passes_;
};

}  // namespace filmkit
