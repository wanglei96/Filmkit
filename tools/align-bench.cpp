// 对齐离线基准 · 驱动（D-2 对齐精度专项，2026-09-30）
//
// 读 tools/align-bench-gen.py 生成的 .alb（真实 DNG 金字塔 + 精确已知位移场），
// 调用**真实实现** render/Align.cpp 的 mfnrAlign，输出位移估计误差分位数。
// --no-l1 = 清空 1/2 域最细层（旧路径基线）；默认 = 新路径（L1 精化）。
//
// 构建（mac 上纯 CPU，无 GL 依赖）：
//   clang++ -O2 -std=c++17 -Icore/engine/src/render tools/align-bench.cpp \
//           core/engine/src/render/Align.cpp -o /tmp/align-bench
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "Align.h"

using filmkit::MfnrAlignOut;
using filmkit::MfnrPyramid;
using filmkit::mfnrAlign;

namespace {
bool readI32(FILE* f, int32_t* v) { return std::fread(v, 4, 1, f) == 1; }

float pct(std::vector<float> v, float q) {
  if (v.empty()) return -1.f;
  std::sort(v.begin(), v.end());
  size_t i = static_cast<size_t>(q * (v.size() - 1) + 0.5f);
  return v[i];
}

void readLayer(FILE* f, std::vector<uint8_t>* v, int w, int h) {
  v->resize(static_cast<size_t>(w) * h);
  if (!v->empty()) {
    if (std::fread(v->data(), 1, v->size(), f) != v->size()) {
      std::fprintf(stderr, "short read\n");
      std::exit(1);
    }
  }
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "usage: align-bench file.alb [--no-l1]\n");
    return 2;
  }
  bool use_l1 = true;
  const char* dump = nullptr;
  bool conf_stats = false;
  for (int i = 2; i < argc; ++i) {
    if (!std::strcmp(argv[i], "--no-l1")) use_l1 = false;
    else if (!std::strcmp(argv[i], "--dump") && i + 1 < argc) dump = argv[++i];
    else if (!std::strcmp(argv[i], "--conf-stats")) conf_stats = true;
  }
  FILE* f = std::fopen(argv[1], "rb");
  if (!f) {
    std::perror("open");
    return 1;
  }
  char magic[4] = {0};
  std::fread(magic, 1, 4, f);
  if (std::memcmp(magic, "ALB1", 4) != 0) {
    std::fprintf(stderr, "bad magic\n");
    return 1;
  }
  int32_t nf = 0;
  if (!readI32(f, &nf) || nf != 2) {
    std::fprintf(stderr, "bad frame count\n");
    return 1;
  }
  MfnrPyramid py[2];
  for (int k = 0; k < nf; ++k) {
    int32_t d[8] = {0};
    if (std::fread(d, 4, 8, f) != 8) return 1;
    py[k].w1 = d[0];
    py[k].h1 = d[1];
    py[k].w2 = d[2];
    py[k].h2 = d[3];
    py[k].w3 = d[4];
    py[k].h3 = d[5];
    py[k].w4 = d[6];
    py[k].h4 = d[7];
    readLayer(f, &py[k].l1, py[k].w1, py[k].h1);
    readLayer(f, &py[k].l2, py[k].w2, py[k].h2);
    readLayer(f, &py[k].l3, py[k].w3, py[k].h3);
    readLayer(f, &py[k].l4, py[k].w4, py[k].h4);
  }
  int32_t tx = 0, ty = 0;
  if (!readI32(f, &tx) || !readI32(f, &ty)) return 1;
  std::vector<float> truth(static_cast<size_t>(tx) * ty * 2);
  if (std::fread(truth.data(), 4, truth.size(), f) != truth.size()) return 1;
  std::fclose(f);

  if (!use_l1) {
    for (int k = 0; k < 2; ++k) {
      py[k].l1.clear();
      py[k].l1.shrink_to_fit();
      py[k].w1 = py[k].h1 = 0;
    }
  }

  const int n_tiles = tx * ty;
  const auto t0 = std::chrono::steady_clock::now();
  const MfnrAlignOut a = mfnrAlign(py[0], py[1], tx, ty);
  const double ms = std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - t0)
                        .count();

  std::vector<float> ex_all, ey_all, ex_ok, ey_ok, d_ok;
  int n_ok = 0, n_bad1 = 0, n_bad2 = 0;
  for (int t = 0; t < n_tiles; ++t) {
    const float dx = a.off[static_cast<size_t>(t) * 2] - truth[static_cast<size_t>(t) * 2];
    const float dy =
        a.off[static_cast<size_t>(t) * 2 + 1] - truth[static_cast<size_t>(t) * 2 + 1];
    const float e = std::sqrt(dx * dx + dy * dy);
    // P0 口径：同相位邻域半径 ±4 mosaic px，错 1px 就已经改变邻域内容
    if (e > 1.f) ++n_bad1;
    if (e > 2.f) ++n_bad2;
    ex_all.push_back(std::fabs(dx));
    ey_all.push_back(std::fabs(dy));
    if (a.conf[static_cast<size_t>(t)] > 0.2f) {
      ex_ok.push_back(std::fabs(dx));
      ey_ok.push_back(std::fabs(dy));
      d_ok.push_back(std::sqrt(dx * dx + dy * dy));
      ++n_ok;
    }
  }
  const char* tag = use_l1 ? "L1" : "L2";
  std::printf(
      "[%s] tiles=%d conf>0.2=%d (%.0f%%) confMed=%.3f | all |ex| p50=%.2f p90=%.2f "
      "|ey| p50=%.2f p90=%.2f | >1px=%.0f%% >2px=%.0f%% | "
      "conf>0.2 err p50=%.2f p90=%.2f max=%.2f | "
      "gx=%.1f gy=%.1f | %.0fms\n",
      tag, n_tiles, n_ok, 100.0 * n_ok / std::max(n_tiles, 1), a.conf_med,
      pct(ex_all, 0.5f), pct(ex_all, 0.9f), pct(ey_all, 0.5f), pct(ey_all, 0.9f),
      100.0 * n_bad1 / std::max(n_tiles, 1), 100.0 * n_bad2 / std::max(n_tiles, 1),
      pct(ex_ok, 0.5f), pct(ex_ok, 0.9f),
      d_ok.empty() ? -1.f : *std::max_element(d_ok.begin(), d_ok.end()),
      a.gx, a.gy, ms);
  // 离群 tile（误差 > 4 mosaic px）：打印位置/位移/真值，用于判断是"边界
  // 不完整 tile""无纹理区"还是"匹配失败"。
  int shown = 0;
  for (int t = 0; t < n_tiles && shown < 6; ++t) {
    const float dx = a.off[static_cast<size_t>(t) * 2] - truth[static_cast<size_t>(t) * 2];
    const float dy =
        a.off[static_cast<size_t>(t) * 2 + 1] - truth[static_cast<size_t>(t) * 2 + 1];
    if (std::sqrt(dx * dx + dy * dy) <= 4.f) continue;
    std::printf("   outlier tile(%d,%d) est=(%.2f,%.2f) truth=(%.2f,%.2f) conf=%.3f\n",
                t % tx, t / tx, a.off[static_cast<size_t>(t) * 2],
                a.off[static_cast<size_t>(t) * 2 + 1],
                truth[static_cast<size_t>(t) * 2], truth[static_cast<size_t>(t) * 2 + 1],
                a.conf[static_cast<size_t>(t)]);
    ++shown;
  }
  if (dump) {
    FILE* g = std::fopen(dump, "wb");
    if (g) {
      std::fwrite(a.off.data(), 4, a.off.size(), g);
      std::fwrite(a.conf.data(), 4, a.conf.size(), g);
      std::fclose(g);
    }
  }
  // 置信度判据判别力：以"误差 > 2 mosaic px"为失败，扫 conf 阈值看检出/误杀。
  // 判据换层（1/4 域 → 1/2 域）后必须重新标定——此表就是标定依据。
  if (conf_stats) {
    int nf = 0, ng = 0;
    std::vector<char> fail(static_cast<size_t>(n_tiles), 0);
    for (int t = 0; t < n_tiles; ++t) {
      const float dx = a.off[static_cast<size_t>(t) * 2] - truth[static_cast<size_t>(t) * 2];
      const float dy =
          a.off[static_cast<size_t>(t) * 2 + 1] - truth[static_cast<size_t>(t) * 2 + 1];
      fail[static_cast<size_t>(t)] = std::sqrt(dx * dx + dy * dy) > 2.f;
      fail[static_cast<size_t>(t)] ? ++nf : ++ng;
    }
    std::printf("  conf 判别力(失败=误差>2px, 共%d失败/%d好):\n", nf, ng);
    for (float th = 0.05f; th < 0.95f; th += 0.15f) {
      int hit = 0, fp = 0;
      for (int t = 0; t < n_tiles; ++t) {
        const bool low = a.conf[static_cast<size_t>(t)] < th;
        if (fail[static_cast<size_t>(t)]) {
          if (low) ++hit;
        } else if (low) {
          ++fp;
        }
      }
      std::printf("    conf<%.2f → 检出 %d/%d (%.0f%%), 误杀 %d/%d (%.0f%%)\n", th,
                  hit, nf, nf ? 100.0 * hit / nf : 0.0, fp, ng,
                  ng ? 100.0 * fp / ng : 0.0);
    }
  }
  return 0;
}
