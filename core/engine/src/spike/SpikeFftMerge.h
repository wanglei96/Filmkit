#pragma once

#include <string>

namespace filmkit {

// 频域降噪专项（2026-09-29）· GPU 成本实测串。
// 被测对象 = HDR+ §5 式 6/7 的最小完整 pairwise DFT 合并链：
//   载入 4 帧 16×16 tile（半重叠可选）→ 窗 → 行/列 DFT（radix-2，shared 内原地）
//   → 逐频收缩合并（Az=|Dz|²/(|Dz|²+cσ²)，T̃0=(1/N)Σ[Tz+Az(T0−Tz)]）
//   → IDFT → 写回。每个 compute workgroup（16×16 线程）处理 2 个水平相邻 tile，
//   复数中间态全在 shared（16KB），不产生全局内存放大。
//
// 口径：iters 轮计入统计（预热 1 轮不计）；GPU timer 不可用时退回 CPU 时间
// （含 glFinish）。overlap=true 时按半重叠密度（步长 8）dispatch——tile 数 ×4，
// 即 HDR+「半重叠 + 升余弦窗」的真实工作量。
struct FftSpikeResult {
  bool ok = false;
  int width = 0;
  int height = 0;
  int iterations = 0;
  bool overlap = false;
  long long tiles = 0;      // 本次 dispatch 的 tile 总数
  int shared_bytes = 0;     // 每 workgroup shared 用量
  int max_shared = 0;       // 设备 GL_MAX_COMPUTE_SHARED_MEMORY_SIZE
  bool gpu_timer_used = false;
  bool readback_ok = false;  // 输出有限、与合成基准一致（仅非重叠模式校验）
  double gpu_median_ms = -1.0;
  double gpu_p90_ms = -1.0;
  double cpu_median_ms = -1.0;
  double cpu_p90_ms = -1.0;
  std::string error;

  std::string toJson() const;
};

// w/h 须为 32 的倍数（非重叠）/ 16 的倍数（重叠），否则自动收缩到最大合法尺寸。
// 须在持有 GL 上下文的线程调用。
FftSpikeResult runSpikeFftMerge(int w, int h, int iters, bool overlap);

}  // namespace filmkit
