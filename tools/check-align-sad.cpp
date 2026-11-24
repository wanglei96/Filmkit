// Build scalar reference separately with FILMKIT_ALIGN_SCALAR and renamed
// mfnrAlign/mfnrAlignResidual symbols, then link it with this test translation unit.
#include "../core/engine/src/render/Align.cpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <stdexcept>

namespace filmkit {
MfnrAlignOut mfnrAlignReference(const MfnrPyramid&, const MfnrPyramid&, int, int, bool, int);
float mfnrAlignResidualReference(const MfnrPyramid&, const MfnrPyramid&,
                                const std::vector<float>&, int, int);
}

namespace {
void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

int scalarSad(const uint8_t* a, const uint8_t* b, int w, int h, int x0,
              int y0, int dx, int dy, int tw, int th, int step) {
  int sum = 0;
  for (int y = 0; y < th; y += step) {
    const int ay = std::clamp(y0 + y, 0, h - 1);
    const int by = std::clamp(y0 + y + dy, 0, h - 1);
    for (int x = 0; x < tw; x += step)
      sum += std::abs(a[static_cast<size_t>(ay) * w + std::clamp(x0 + x, 0, w - 1)] -
                      b[static_cast<size_t>(by) * w + std::clamp(x0 + x + dx, 0, w - 1)]);
  }
  return sum;
}

void same(const filmkit::MfnrAlignOut& a, const filmkit::MfnrAlignOut& b) {
  require(a.off == b.off && a.conf == b.conf, "alignment field changed");
  require(a.gx == b.gx && a.gy == b.gy && a.gcost == b.gcost &&
          a.conf_med == b.conf_med && a.dx_med == b.dx_med &&
          a.dx_p90 == b.dx_p90 && a.used_l1 == b.used_l1,
          "alignment summary changed");
}

filmkit::MfnrPyramid pyramid(int w, int h, std::mt19937& random) {
  filmkit::MfnrPyramid p;
  p.w1 = w; p.h1 = h;
  p.w2 = (w + 1) / 2; p.h2 = (h + 1) / 2;
  p.w3 = (p.w2 + 1) / 2; p.h3 = (p.h2 + 1) / 2;
  p.w4 = (p.w3 + 1) / 2; p.h4 = (p.h3 + 1) / 2;
  for (auto item : {std::make_pair(&p.l1, p.w1 * p.h1), {&p.l2, p.w2 * p.h2},
                    {&p.l3, p.w3 * p.h3}, {&p.l4, p.w4 * p.h4}}) {
    item.first->resize(item.second);
    for (auto& v : *item.first) v = static_cast<uint8_t>(random());
  }
  return p;
}
}

int main(int argc, char** argv) {
  try {
    std::mt19937 random(0x534144);
    for (int trial = 0; trial < 12000; ++trial) {
      const int w = 1 + random() % 257, h = 1 + random() % 131;
      std::vector<uint8_t> a(size_t(w) * h), b(a.size());
      for (auto& v : a) v = trial % 11 == 0 ? 0 : static_cast<uint8_t>(random());
      for (auto& v : b) v = trial % 11 == 0 ? 255 : static_cast<uint8_t>(random());
      const int x = static_cast<int>(random() % (w + 64)) - 32;
      const int y = static_cast<int>(random() % (h + 64)) - 32;
      const int dx = static_cast<int>(random() % 81) - 40;
      const int dy = static_cast<int>(random() % 81) - 40;
      const int tw = 1 + random() % 129, th = 1 + random() % 129;
      const int step = 1 + trial % 3;
      require(filmkit::sadTile(a.data(), b.data(), w, h, x, y, dx, dy, tw, th, step) ==
              scalarSad(a.data(), b.data(), w, h, x, y, dx, dy, tw, th, step),
              "SAD mismatch");
    }
    int fullWidthCases = 0;
    for (int w : {31, 32, 33, 191, 192, 193, 255, 256, 257}) {
      constexpr int h = 17;
      std::vector<uint8_t> a(size_t(w) * h), b(a.size());
      for (auto& v : a) v = static_cast<uint8_t>(random());
      for (auto& v : b) v = static_cast<uint8_t>(random());
      for (int x : {-1, 0, 1}) for (int dx : {-16, -3, -1, 0, 1, 3, 16}) {
        for (int dy : {-1, 0, 1}) for (int step : {1, 2}) {
          require(filmkit::sadTile(a.data(), b.data(), w, h, x, 0, dx, dy, w, h, step) ==
                  scalarSad(a.data(), b.data(), w, h, x, 0, dx, dy, w, h, step),
                  "full-width shifted SAD mismatch");
          ++fullWidthCases;
        }
      }
    }
    int cases = 0;
    for (const auto& dims : {std::make_pair(65, 67), {129, 131}, {256, 192}, {513, 385}}) {
      auto a = pyramid(dims.first, dims.second, random), b = a;
      for (int mode = 0; mode < 3; ++mode) {
        if (mode == 1) b = pyramid(dims.first, dims.second, random);
        if (mode == 2) {
          for (auto* p : {&a, &b})
            for (auto* layer : {&p->l1, &p->l2, &p->l3, &p->l4})
              std::fill(layer->begin(), layer->end(), 37);
        }
        for (bool l1 : {false, true}) for (int tile : {16, 32, 64}) {
          const int tx = (a.w1 + tile - 1) / tile, ty = (a.h1 + tile - 1) / tile;
          const auto ref = filmkit::mfnrAlignReference(a, b, tx, ty, l1, tile);
          const auto result = filmkit::mfnrAlign(a, b, tx, ty, l1, tile);
          same(ref, result);
          require(filmkit::mfnrAlignResidualReference(a, b, ref.off, tx, ty) ==
                  filmkit::mfnrAlignResidual(a, b, result.off, tx, ty), "residual changed");
          ++cases;
        }
      }
    }
    std::printf("PASS 12000 random SAD cases, %d full-width SAD cases, %d full alignments, "
                "exact fields and residuals\n", fullWidthCases, cases);
    if (argc == 2 && std::strcmp(argv[1], "--benchmark") == 0) {
      const auto a = pyramid(2048, 1536, random), b = pyramid(2048, 1536, random);
      for (int block = 0; block < 4; ++block) for (int item = 0; item < 4; ++item) {
        const bool ref = ((item == 0 || item == 3) != (block % 2 != 0));
        const auto start = std::chrono::steady_clock::now();
        const auto value = ref ? filmkit::mfnrAlignReference(a, b, 32, 24, true, 64)
                               : filmkit::mfnrAlign(a, b, 32, 24, true, 64);
        const double ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
        std::printf("BENCH block=%d reference=%d ms=%.3f cost=%.3f\n", block, ref, ms, value.gcost);
      }
    }
    return 0;
  } catch (const std::exception& e) {
    std::fprintf(stderr, "FAIL %s\n", e.what());
    return 1;
  }
}
