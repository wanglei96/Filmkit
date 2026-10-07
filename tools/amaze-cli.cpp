// AMaZE 离线 CLI —— 与 tools/rcd-linecompare.py（RCD 直译）配对使用，
// 用于在同一 DNG 的同一 CFA 输入上对比 AMaZE 与 RCD 的去马赛克输出。
//
// 为什么需要：PC（PhotonCamera）用 AMaZE，我们用 RCD；用户样片显示 PC 无淡紫线、
// 我们有 +118 尖峰。要判定"紫线是 RCD 特有还是两者共有"，必须在同一输入上
// 直接对比两种算法的输出。
//
// 用法（由 tools/demosaic-ab.py 调用，也可独立使用）：
//   ./amaze-cli <cfa.bin> <out_rgb.f32> [threads]
//   cfa.bin 格式：int32 w, int32 h, int32 pattern[4], float32 clip_pt, 然后 w*h 个 float32 CFA
//   out_rgb.f32 ：w*h*3 个 float32（RGB 交错，与 AMaZE 接口一致）
//
// 编译：
//   g++ -O2 -std=c++20 -I core/engine/src tools/amaze-cli.cpp \
//       core/engine/src/render/AmazeDemosaic.cpp -o tools/bin/amaze-cli
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "render/AmazeDemosaic.h"

int main(int argc, char** argv) {
  if (argc < 3) {
    std::fprintf(stderr, "usage: %s <cfa.bin> <out_rgb.f32> [threads]\n", argv[0]);
    return 2;
  }
  FILE* f = std::fopen(argv[1], "rb");
  if (!f) { std::perror("open cfa"); return 1; }
  int w = 0, h = 0, pattern[4] = {0, 1, 1, 2};
  float clip_pt = 1.f;
  if (std::fread(&w, sizeof(int), 1, f) != 1) return 1;
  if (std::fread(&h, sizeof(int), 1, f) != 1) return 1;
  if (std::fread(pattern, sizeof(int), 4, f) != 4) return 1;
  if (std::fread(&clip_pt, sizeof(float), 1, f) != 1) return 1;
  const size_t n = (size_t)w * (size_t)h;
  std::vector<float> cfa(n), rgb(n * 3);
  if (std::fread(cfa.data(), sizeof(float), n, f) != n) { std::fprintf(stderr, "short cfa\n"); return 1; }
  std::fclose(f);

  const int threads = argc > 3 ? std::atoi(argv[3]) : 0;
  filmkit::AmazeDemosaic(cfa.data(), w, h, pattern, clip_pt, rgb.data(), threads);

  FILE* o = std::fopen(argv[2], "wb");
  if (!o) { std::perror("open out"); return 1; }
  std::fwrite(rgb.data(), sizeof(float), rgb.size(), o);
  std::fclose(o);
  std::fprintf(stderr, "amaze-cli: %dx%d pattern=[%d,%d,%d,%d] clip=%.4f -> %s\n",
               w, h, pattern[0], pattern[1], pattern[2], pattern[3], clip_pt, argv[2]);
  return 0;
}
