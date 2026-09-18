#pragma once

#include <string>
#include <vector>

namespace filmkit {

struct SpikePassSummary {
  std::string name;
  double gpu_median_ms = -1.0;
  double gpu_p90_ms = -1.0;
  double cpu_median_ms = -1.0;
  double cpu_p90_ms = -1.0;
};

struct SpikeResult {
  bool ok = false;
  int width = 0;
  int height = 0;
  int iterations = 0;
  bool use_compute = false;
  bool gpu_timer_used = false;
  bool rgba16f_renderable = false;  // RGBA16F FBO 完整性（管线位深前提）
  bool readback_ok = false;         // 输出内容正确性（梯度单调、范围合法）
  std::string error;
  std::vector<SpikePassSummary> passes;

  std::string toJson() const;
};

// 架构 spike（M0 验收）：空管线跑通「生成 → 变换 → 输出」三段 GPU pass，
// 逐 pass 计 GPU/CPU 耗时并写入日志。首轮为预热不计入统计。
// w/h 建议 1024；iters 为计入统计的轮数。
// useCompute=false 时第一段走 fragment 路径（验证降级路线，开发计划 §10）。
SpikeResult runSpikePass(int w, int h, int iters, bool use_compute);

}  // namespace filmkit
