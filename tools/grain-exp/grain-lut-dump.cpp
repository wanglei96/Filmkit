// E4-3 颗粒：从**引擎自己的** LUT 实现（core/engine/src/render/GrainLut.h）导出相纸响应
// LUT，供 Python 侧（tools/grain-exp/grain-exp.py）逐点对照。
//
// 为什么必须走 C++ 侧而不是"Python 再写一遍"：任务卡的离线验证要求"numpy 独立复算 ↔
// **C++ 侧输出**逐点对照"。若两边都是 Python，对照的是两份各自实现的代码，证明不了
// 真机跑的那份数学。本工具 include 的就是 FrameRenderer::ensureGrainLut 用的同一份头。
//
// 用法：grain-lut-dump <mb> <pivot> <out.raw>
//   输出 = 128×128 个 little-endian float32，行主序（行 = 亮度轴 j，列 = 噪声轴 i），
//   与 darktable 的 grain_lut[j*SIZE + i] 同布局 ⇒ numpy: np.fromfile(f,'<f4').reshape(128,128)
// 编译：clang++ -O2 -std=c++17 -o grain-lut-dump grain-lut-dump.cpp
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../../core/engine/src/render/GrainLut.h"

int main(int argc, char** argv) {
  if (argc < 4) {
    std::fprintf(stderr, "用法: %s <mb> <pivot> <out.raw>\n", argv[0]);
    return 2;
  }
  const float mb = static_cast<float>(std::atof(argv[1]));
  const float pivot = static_cast<float>(std::atof(argv[2]));
  const int n = filmkit::grain::kLutSize;
  std::vector<float> lut(static_cast<size_t>(n) * n);
  filmkit::grain::buildGrainLut(lut.data(), mb, pivot);
  FILE* f = std::fopen(argv[3], "wb");
  if (f == nullptr) {
    std::fprintf(stderr, "无法写 %s\n", argv[3]);
    return 1;
  }
  const size_t wrote = std::fwrite(lut.data(), sizeof(float), lut.size(), f);
  std::fclose(f);
  if (wrote != lut.size()) {
    std::fprintf(stderr, "写入不完整: %zu/%zu\n", wrote, lut.size());
    return 1;
  }
  // 顺带把标量常量打出来（Python 侧核对 grainZoom / delta 等派生量）
  std::printf("{\"mb\":%.6f,\"pivot\":%.6f,\"size\":%d,\"zoom400\":%.10f,"
              "\"delta\":%.10g,\"lut_00\":%.6f,\"lut_nn\":%.6f}\n",
              mb, pivot, n, filmkit::grain::grainZoom(400.0f),
              filmkit::grain::grainDelta(mb), lut[0], lut[lut.size() - 1]);
  return 0;
}
