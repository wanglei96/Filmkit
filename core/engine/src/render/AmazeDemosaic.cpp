#include "render/AmazeDemosaic.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

namespace filmkit {
namespace {

// ── 常量（与参考实现 RawTherapee amaze_demosaic_RT.cc 同值）───────────────
constexpr int kTs = 160;        // tile 边长（参考实现 AMAZETS 默认 160）
constexpr int kTsh = kTs / 2;   // 半网格行宽
constexpr int kStep = kTs - 32; // tile 步进（重叠 32px 边界）
constexpr float kEps = 1e-5f;
constexpr float kEpsSq = 1e-10f;
constexpr float kArThresh = 0.75f;   // 自适应比值阈值 arthresh
constexpr float kNyqThresh = 0.5f;   // nyquist 纹理判定阈值（已乘进 kGaussGrad）

// 5×5 梅花形高斯 σ=1.2（gaussodd）
constexpr float kGaussOdd[4] = {0.14659727707323927f, 0.103592713382435f,
                                0.0732036125103057f, 0.0365543548389495f};
// 5×5 高斯 σ≈1.7，已预乘 nyqthresh（gaussgrad）
constexpr float kGaussGrad[6] = {kNyqThresh * 0.07384411893421103f,
                                 kNyqThresh * 0.06207511968171489f,
                                 kNyqThresh * 0.0521818194747806f,
                                 kNyqThresh * 0.03687419286733595f,
                                 kNyqThresh * 0.03099732204057846f,
                                 kNyqThresh * 0.018413194161458882f};
// 5×5 另一梅花形高斯 σ=1.5（gausseven）
constexpr float kGaussEven[2] = {0.13719494435797422f, 0.05640252782101291f};
// 梅花网格高斯（gquinc）
constexpr float kGQuinc[4] = {0.169917f, 0.108947f, 0.069855f, 0.0287182f};

inline float sq(float x) { return x * x; }
// 参考实现 rt_math.h 的 intp：a·c + (1−a)·b
inline float intp(float a, float b, float c) { return a * c + (1.f - a) * b; }
// 三值中位数（参考实现 median.h 同式）
inline float median3(float a, float b, float c) {
  return std::max(std::min(a, b), std::min(std::max(a, b), c));
}
// reflect-101 边界扩展：−1→1、n→n−2（与参考实现的 16px 镜像 tile 边界等价）
inline int reflect101(int i, int n) {
  if (n <= 1) return 0;
  while (i < 0 || i >= n) i = (i < 0) ? -i : (2 * n - 2 - i);
  return i;
}

// tile 内全部中间场（14 个 ts×ts 全网格 + 12 个 ts×tsh 半网格 + 2 个字节标志）
struct Scratch {
  std::vector<float> f;
  std::vector<uint8_t> nyqflags, nyq2;
  float *rgbgreen, *delhvsqsum, *dirwts0, *dirwts1, *vcd, *hcd, *vcdalt,
      *hcdalt, *cddiffsq, *hvwt, *dgintv, *dginth, *cfaT, *nyqutest, *Dgrb0,
      *Dgrb1, *delp, *delm, *rbint, *Dgrbsq1m, *Dgrbsq1p, *pmwt, *rbm, *rbp,
      *Dgrb2h, *Dgrb2v;

  void alloc() {
    const size_t full = (size_t)kTs * kTs, half = (size_t)kTs * kTsh;
    f.assign(14 * full + 12 * half, 0.f);
    nyqflags.assign(half, 0);
    nyq2.assign(half, 0);
    float* p = f.data();
    auto take = [&p](size_t n) {
      float* r = p;
      p += n;
      return r;
    };
    rgbgreen = take(full);
    delhvsqsum = take(full);
    dirwts0 = take(full);
    dirwts1 = take(full);
    vcd = take(full);
    hcd = take(full);
    vcdalt = take(full);
    hcdalt = take(full);
    cddiffsq = take(full);
    hvwt = take(full);
    dgintv = take(full);
    dginth = take(full);
    cfaT = take(full);
    nyqutest = take(full);
    Dgrb0 = take(half);
    Dgrb1 = take(half);
    delp = take(half);
    delm = take(half);
    rbint = take(half);
    Dgrbsq1m = take(half);
    Dgrbsq1p = take(half);
    pmwt = take(half);
    rbm = take(half);
    rbp = take(half);
    Dgrb2h = take(half);
    Dgrb2v = take(half);
  }
  void clear() {
    std::fill(f.begin(), f.end(), 0.f);
    std::fill(nyqflags.begin(), nyqflags.end(), 0);
    std::fill(nyq2.begin(), nyq2.end(), 0);
  }
};

// 单个 tile 的完整 AMaZE（与参考实现的 tile 内循环逐段对应）。
// top/left 允许为负（首 tile 的 −16 边界）；(top,left) 恒为偶数，故 CFA 相位
// 与 tile 内坐标 rr/cc 的奇偶一致（与参考实现用 fc(cfarray,rr,cc) 同口径）。
void processTile(const float* src, int w, int h, const int pattern[4],
                 float clip_pt, float* out, int top, int left, Scratch* S) {
  const int rr1 = std::min(kTs, h + 16 - top);
  const int cc1 = std::min(kTs, w + 16 - left);
  const float clip_pt8 = 0.8f * clip_pt;
  const int v1 = kTs, v2 = 2 * kTs, v3 = 3 * kTs;
  const int m1 = -kTs + 1, m2 = -2 * kTs + 2, m3 = -3 * kTs + 3;
  const int p1 = kTs + 1, p2 = 2 * kTs + 2, p3 = 3 * kTs + 3;

  S->clear();
  float* rgbgreen = S->rgbgreen;
  float* delhvsqsum = S->delhvsqsum;
  float* dirwts0 = S->dirwts0;
  float* dirwts1 = S->dirwts1;
  float* vcd = S->vcd;
  float* hcd = S->hcd;
  float* vcdalt = S->vcdalt;
  float* hcdalt = S->hcdalt;
  float* cddiffsq = S->cddiffsq;
  float* hvwt = S->hvwt;
  float* dgintv = S->dgintv;
  float* dginth = S->dginth;
  float* cfa = S->cfaT;
  float* nyqutest = S->nyqutest;
  float* Dgrb0 = S->Dgrb0;
  float* Dgrb1 = S->Dgrb1;
  float* delp = S->delp;
  float* delm = S->delm;
  float* rbint = S->rbint;
  float* Dgrbsq1m = S->Dgrbsq1m;
  float* Dgrbsq1p = S->Dgrbsq1p;
  float* pmwt = S->pmwt;
  float* rbm = S->rbm;
  float* rbp = S->rbp;
  float* Dgrb2h = S->Dgrb2h;
  float* Dgrb2v = S->Dgrb2v;
  uint8_t* nyqflags = S->nyqflags.data();
  uint8_t* nyq2 = S->nyq2.data();

  auto fc = [&pattern](int r, int c) { return pattern[((r & 1) << 1) | (c & 1)]; };

  // ── tile 初始化：CFA 填入 + 16px 镜像边界（等价参考实现的上/下/左/右/角填充）
  for (int rr = 0; rr < rr1; ++rr) {
    const int gy = reflect101(top + rr, h);
    for (int cc = 0; cc < cc1; ++cc) {
      const int gx = reflect101(left + cc, w);
      const float v = src[(size_t)gy * w + gx];
      const int indx = rr * kTs + cc;
      cfa[indx] = v;
      rgbgreen[indx] = v;
    }
  }

  // ── ① H/V 梯度 + 梯度平方和
  for (int rr = 2; rr < rr1 - 2; ++rr) {
    for (int cc = 2, indx = rr * kTs + 2; cc < cc1 - 2; ++cc, ++indx) {
      const float delh = std::fabs(cfa[indx + 1] - cfa[indx - 1]);
      const float delv = std::fabs(cfa[indx + v1] - cfa[indx - v1]);
      dirwts0[indx] = kEps + std::fabs(cfa[indx + v2] - cfa[indx]) +
                      std::fabs(cfa[indx] - cfa[indx - v2]) + delv;
      dirwts1[indx] = kEps + std::fabs(cfa[indx + 2] - cfa[indx]) +
                      std::fabs(cfa[indx] - cfa[indx - 2]) + delh;
      delhvsqsum[indx] = sq(delh) + sq(delv);
    }
  }

  // ── ② 方向色差（自适应比值 vs Hamilton-Adams）
  for (int rr = 4; rr < rr1 - 4; ++rr) {
    bool fcswitch = (fc(rr, 4) & 1) != 0;
    for (int cc = 4, indx = rr * kTs + 4; cc < cc1 - 4; ++cc, ++indx) {
      const float cur = cfa[indx];
      const float cru = cfa[indx - v1] * (dirwts0[indx - v2] + dirwts0[indx]) /
                        (dirwts0[indx - v2] * (kEps + cur) +
                         dirwts0[indx] * (kEps + cfa[indx - v2]));
      const float crd = cfa[indx + v1] * (dirwts0[indx + v2] + dirwts0[indx]) /
                        (dirwts0[indx + v2] * (kEps + cur) +
                         dirwts0[indx] * (kEps + cfa[indx + v2]));
      const float crl = cfa[indx - 1] * (dirwts1[indx - 2] + dirwts1[indx]) /
                        (dirwts1[indx - 2] * (kEps + cur) +
                         dirwts1[indx] * (kEps + cfa[indx - 2]));
      const float crr = cfa[indx + 1] * (dirwts1[indx + 2] + dirwts1[indx]) /
                        (dirwts1[indx + 2] * (kEps + cur) +
                         dirwts1[indx] * (kEps + cfa[indx + 2]));

      const float guha = cfa[indx - v1] + 0.5f * (cur - cfa[indx - v2]);
      const float gdha = cfa[indx + v1] + 0.5f * (cur - cfa[indx + v2]);
      const float glha = cfa[indx - 1] + 0.5f * (cur - cfa[indx - 2]);
      const float grha = cfa[indx + 1] + 0.5f * (cur - cfa[indx + 2]);

      float guar = (std::fabs(1.f - cru) < kArThresh) ? cur * cru : guha;
      float gdar = (std::fabs(1.f - crd) < kArThresh) ? cur * crd : gdha;
      float glar = (std::fabs(1.f - crl) < kArThresh) ? cur * crl : glha;
      float grar = (std::fabs(1.f - crr) < kArThresh) ? cur * crr : grha;

      const float hwt =
          dirwts1[indx - 1] / (dirwts1[indx - 1] + dirwts1[indx + 1]);
      const float vwt =
          dirwts0[indx - v1] / (dirwts0[indx + v1] + dirwts0[indx - v1]);

      const float Gintvha = vwt * gdha + (1.f - vwt) * guha;
      const float Ginthha = hwt * grha + (1.f - hwt) * glha;

      if (fcswitch) {  // G site
        vcd[indx] = cur - (vwt * gdar + (1.f - vwt) * guar);
        hcd[indx] = cur - (hwt * grar + (1.f - hwt) * glar);
        vcdalt[indx] = cur - Gintvha;
        hcdalt[indx] = cur - Ginthha;
      } else {  // R/B site
        vcd[indx] = (vwt * gdar + (1.f - vwt) * guar) - cur;
        hcd[indx] = (hwt * grar + (1.f - hwt) * glar) - cur;
        vcdalt[indx] = Gintvha - cur;
        hcdalt[indx] = Ginthha - cur;
      }
      fcswitch = !fcswitch;

      if (cur > clip_pt8 || Gintvha > clip_pt8 || Ginthha > clip_pt8) {
        // 过曝（近）裁剪时回退 Hamilton-Adams
        guar = guha;
        gdar = gdha;
        glar = glha;
        grar = grha;
        vcd[indx] = vcdalt[indx];
        hcd[indx] = hcdalt[indx];
      }

      dgintv[indx] = std::min(sq(guha - gdha), sq(guar - gdar));
      dginth[indx] = std::min(sq(glha - grha), sq(glar - grar));
    }
  }

  // ── ③ 方差择优 + 高饱和 median 限幅
  for (int rr = 4; rr < rr1 - 4; ++rr) {
    int c = fc(rr, 4) & 1;
    for (int cc = 4, indx = rr * kTs + 4; cc < cc1 - 4; ++cc, ++indx, c = !c) {
      const float hcdvar =
          3.f * (sq(hcd[indx - 2]) + sq(hcd[indx]) + sq(hcd[indx + 2])) -
          sq(hcd[indx - 2] + hcd[indx] + hcd[indx + 2]);
      const float hcdaltvar =
          3.f * (sq(hcdalt[indx - 2]) + sq(hcdalt[indx]) + sq(hcdalt[indx + 2])) -
          sq(hcdalt[indx - 2] + hcdalt[indx] + hcdalt[indx + 2]);
      const float vcdvar =
          3.f * (sq(vcd[indx - v2]) + sq(vcd[indx]) + sq(vcd[indx + v2])) -
          sq(vcd[indx - v2] + vcd[indx] + vcd[indx + v2]);
      const float vcdaltvar =
          3.f * (sq(vcdalt[indx - v2]) + sq(vcdalt[indx]) + sq(vcdalt[indx + v2])) -
          sq(vcdalt[indx - v2] + vcdalt[indx] + vcdalt[indx + v2]);

      // 取方差更小的一支（更平滑）
      if (hcdaltvar < hcdvar) hcd[indx] = hcdalt[indx];
      if (vcdaltvar < vcdvar) vcd[indx] = vcdalt[indx];

      float Gintv, Ginth;
      if (c) {  // G site
        Ginth = -hcd[indx] + cfa[indx];
        Gintv = -vcd[indx] + cfa[indx];
        if (hcd[indx] > 0.f) {
          if (3.f * hcd[indx] > (Ginth + cfa[indx])) {
            hcd[indx] = -median3(Ginth, cfa[indx - 1], cfa[indx + 1]) + cfa[indx];
          } else {
            const float hwt = 1.f - 3.f * hcd[indx] / (kEps + Ginth + cfa[indx]);
            hcd[indx] = hwt * hcd[indx] +
                        (1.f - hwt) * (-median3(Ginth, cfa[indx - 1], cfa[indx + 1]) +
                                       cfa[indx]);
          }
        }
        if (vcd[indx] > 0.f) {
          if (3.f * vcd[indx] > (Gintv + cfa[indx])) {
            vcd[indx] = -median3(Gintv, cfa[indx - v1], cfa[indx + v1]) + cfa[indx];
          } else {
            const float vwt = 1.f - 3.f * vcd[indx] / (kEps + Gintv + cfa[indx]);
            vcd[indx] = vwt * vcd[indx] +
                        (1.f - vwt) * (-median3(Gintv, cfa[indx - v1], cfa[indx + v1]) +
                                       cfa[indx]);
          }
        }
        if (Ginth > clip_pt) {
          hcd[indx] = -median3(Ginth, cfa[indx - 1], cfa[indx + 1]) + cfa[indx];
        }
        if (Gintv > clip_pt) {
          vcd[indx] = -median3(Gintv, cfa[indx - v1], cfa[indx + v1]) + cfa[indx];
        }
      } else {  // R/B site
        Ginth = hcd[indx] + cfa[indx];
        Gintv = vcd[indx] + cfa[indx];
        if (hcd[indx] < 0.f) {
          if (3.f * hcd[indx] < -(Ginth + cfa[indx])) {
            hcd[indx] = median3(Ginth, cfa[indx - 1], cfa[indx + 1]) - cfa[indx];
          } else {
            const float hwt = 1.f + 3.f * hcd[indx] / (kEps + Ginth + cfa[indx]);
            hcd[indx] = hwt * hcd[indx] +
                        (1.f - hwt) * (median3(Ginth, cfa[indx - 1], cfa[indx + 1]) -
                                       cfa[indx]);
          }
        }
        if (vcd[indx] < 0.f) {
          if (3.f * vcd[indx] < -(Gintv + cfa[indx])) {
            vcd[indx] = median3(Gintv, cfa[indx - v1], cfa[indx + v1]) - cfa[indx];
          } else {
            const float vwt = 1.f + 3.f * vcd[indx] / (kEps + Gintv + cfa[indx]);
            vcd[indx] = vwt * vcd[indx] +
                        (1.f - vwt) * (median3(Gintv, cfa[indx - v1], cfa[indx + v1]) -
                                       cfa[indx]);
          }
        }
        if (Ginth > clip_pt) {
          hcd[indx] = median3(Ginth, cfa[indx - 1], cfa[indx + 1]) - cfa[indx];
        }
        if (Gintv > clip_pt) {
          vcd[indx] = median3(Gintv, cfa[indx - v1], cfa[indx + v1]) - cfa[indx];
        }
        cddiffsq[indx] = sq(vcd[indx] - hcd[indx]);
      }
    }
  }

  // ── ④ hvwt：方向判别权重（色差方差 + 上下/左右插值差波动）
  for (int rr = 6; rr < rr1 - 6; ++rr) {
    for (int cc = 6 + (fc(rr, 2) & 1), indx = rr * kTs + cc; cc < cc1 - 6;
         cc += 2, indx += 2) {
      float uave = vcd[indx] + vcd[indx - v1] + vcd[indx - v2] + vcd[indx - v3];
      float dave = vcd[indx] + vcd[indx + v1] + vcd[indx + v2] + vcd[indx + v3];
      float lave = hcd[indx] + hcd[indx - 1] + hcd[indx - 2] + hcd[indx - 3];
      float rave = hcd[indx] + hcd[indx + 1] + hcd[indx + 2] + hcd[indx + 3];

      float vvaru = sq(vcd[indx] - uave) + sq(vcd[indx - v1] - uave) +
                    sq(vcd[indx - v2] - uave) + sq(vcd[indx - v3] - uave);
      float vvard = sq(vcd[indx] - dave) + sq(vcd[indx + v1] - dave) +
                    sq(vcd[indx + v2] - dave) + sq(vcd[indx + v3] - dave);
      float hvarl = sq(hcd[indx] - lave) + sq(hcd[indx - 1] - lave) +
                    sq(hcd[indx - 2] - lave) + sq(hcd[indx - 3] - lave);
      float hvarr = sq(hcd[indx] - rave) + sq(hcd[indx + 1] - rave) +
                    sq(hcd[indx + 2] - rave) + sq(hcd[indx + 3] - rave);

      const float hwt =
          dirwts1[indx - 1] / (dirwts1[indx - 1] + dirwts1[indx + 1]);
      const float vwt =
          dirwts0[indx - v1] / (dirwts0[indx + v1] + dirwts0[indx - v1]);

      const float vcdvar = kEpsSq + vwt * vvard + (1.f - vwt) * vvaru;
      const float hcdvar = kEpsSq + hwt * hvarr + (1.f - hwt) * hvarl;

      vvaru = dgintv[indx] + dgintv[indx - v1] + dgintv[indx - v2];
      vvard = dgintv[indx] + dgintv[indx + v1] + dgintv[indx + v2];
      hvarl = dginth[indx] + dginth[indx - 1] + dginth[indx - 2];
      hvarr = dginth[indx] + dginth[indx + 1] + dginth[indx + 2];

      const float vcdvar1 = kEpsSq + vwt * vvard + (1.f - vwt) * vvaru;
      const float hcdvar1 = kEpsSq + hwt * hvarr + (1.f - hwt) * hvarl;

      const float varwt = hcdvar / (vcdvar + hcdvar);
      const float diffwt = hcdvar1 / (vcdvar1 + hcdvar1);

      // 两判据同向时取判别力更强者，否则取上下/左右波动权重
      if ((0.5f - varwt) * (0.5f - diffwt) > 0.f &&
          std::fabs(0.5f - diffwt) < std::fabs(0.5f - varwt)) {
        hvwt[indx >> 1] = varwt;
      } else {
        hvwt[indx >> 1] = diffwt;
      }
    }
  }

  // ── ⑤ nyqutest：cddiffsq 的梅花高斯 − delhvsqsum 的高斯（Nyquist 纹理判据）
  for (int rr = 6; rr < rr1 - 6; ++rr) {
    for (int cc = 6 + (fc(rr, 2) & 1), indx = rr * kTs + cc; cc < cc1 - 6;
         cc += 2, indx += 2) {
      nyqutest[indx >> 1] =
          (kGaussOdd[0] * cddiffsq[indx] +
           kGaussOdd[1] * (cddiffsq[indx - m1] + cddiffsq[indx + p1] +
                           cddiffsq[indx - p1] + cddiffsq[indx + m1]) +
           kGaussOdd[2] * (cddiffsq[indx - v2] + cddiffsq[indx - 2] +
                           cddiffsq[indx + 2] + cddiffsq[indx + v2]) +
           kGaussOdd[3] * (cddiffsq[indx - m2] + cddiffsq[indx + p2] +
                           cddiffsq[indx - p2] + cddiffsq[indx + m2])) -
          (kGaussGrad[0] * delhvsqsum[indx] +
           kGaussGrad[1] * (delhvsqsum[indx - v1] + delhvsqsum[indx + 1] +
                            delhvsqsum[indx - 1] + delhvsqsum[indx + v1]) +
           kGaussGrad[2] * (delhvsqsum[indx - m1] + delhvsqsum[indx + p1] +
                            delhvsqsum[indx - p1] + delhvsqsum[indx + m1]) +
           kGaussGrad[3] * (delhvsqsum[indx - v2] + delhvsqsum[indx - 2] +
                            delhvsqsum[indx + 2] + delhvsqsum[indx + v2]) +
           kGaussGrad[4] * (delhvsqsum[indx - v2 - 1] + delhvsqsum[indx - v2 + 1] +
                            delhvsqsum[indx - kTs - 2] + delhvsqsum[indx - kTs + 2] +
                            delhvsqsum[indx + kTs - 2] + delhvsqsum[indx + kTs + 2] +
                            delhvsqsum[indx + v2 - 1] + delhvsqsum[indx + v2 + 1]) +
           kGaussGrad[5] * (delhvsqsum[indx - m2] + delhvsqsum[indx + p2] +
                            delhvsqsum[indx - p2] + delhvsqsum[indx + m2]));
    }
  }

  // ── ⑥ Nyquist 标志 + 有效包围盒
  int nystartrow = 0, nyendrow = 0, nystartcol = kTs + 1, nyendcol = 0;
  for (int rr = 6; rr < rr1 - 6; ++rr) {
    for (int cc = 6 + (fc(rr, 2) & 1), indx = rr * kTs + cc; cc < cc1 - 6;
         cc += 2, indx += 2) {
      if (nyqutest[indx >> 1] > 0.f) {
        nyqflags[indx >> 1] = 1;
        nystartrow = nystartrow ? nystartrow : rr;
        nyendrow = rr;
        nystartcol = nystartcol > cc ? cc : nystartcol;
        nyendcol = nyendcol < cc ? cc : nyendcol;
      }
    }
  }
  const bool doNyquist = nystartrow != nyendrow && nystartcol != nyendcol;
  if (doNyquist) {
    nyendrow++;
    nyendcol++;
    nystartcol -= (nystartcol & 1);
    nystartrow = std::max(8, nystartrow);
    nyendrow = std::min(rr1 - 8, nyendrow);
    nystartcol = std::max(8, nystartcol);
    nyendcol = std::min(cc1 - 8, nyendcol);

    // ⑦ nyquist2：邻域投票（多数邻居是 Nyquist 则自己也是；平局保持原值）
    for (int rr = nystartrow; rr < nyendrow; ++rr) {
      for (int indx = rr * kTs + nystartcol + (fc(rr, 2) & 1);
           indx < rr * kTs + nyendcol; indx += 2) {
        const int nyqtemp = nyqflags[(indx - v2) >> 1] + nyqflags[(indx - m1) >> 1] +
                            nyqflags[(indx + p1) >> 1] + nyqflags[(indx - 2) >> 1] +
                            nyqflags[(indx + 2) >> 1] + nyqflags[(indx - p1) >> 1] +
                            nyqflags[(indx + m1) >> 1] + nyqflags[(indx + v2) >> 1];
        nyq2[indx >> 1] =
            nyqtemp > 4 ? 1 : (nyqtemp < 4 ? 0 : nyqflags[indx >> 1]);
      }
    }

    // Nyquist 区：13×13 同相位块做 area 插值（重整 hvwt）
    for (int rr = nystartrow; rr < nyendrow; ++rr) {
      for (int indx = rr * kTs + nystartcol + (fc(rr, 2) & 1);
           indx < rr * kTs + nyendcol; indx += 2) {
        if (!nyq2[indx >> 1]) continue;
        float sumcfa = 0.f, sumh = 0.f, sumv = 0.f, sumsqh = 0.f, sumsqv = 0.f,
              areawt = 0.f;
        for (int i = -6; i < 7; i += 2) {
          int indx1 = indx + (i * kTs) - 6;
          for (int j = -6; j < 7; j += 2, indx1 += 2) {
            if (nyq2[indx1 >> 1]) {
              const float cfatemp = cfa[indx1];
              sumcfa += cfatemp;
              sumh += (cfa[indx1 - 1] + cfa[indx1 + 1]);
              sumv += (cfa[indx1 - v1] + cfa[indx1 + v1]);
              sumsqh += sq(cfatemp - cfa[indx1 - 1]) + sq(cfatemp - cfa[indx1 + 1]);
              sumsqv += sq(cfatemp - cfa[indx1 - v1]) + sq(cfatemp - cfa[indx1 + v1]);
              areawt += 1.f;
            }
          }
        }
        sumh = sumcfa - 0.5f * sumh;
        sumv = sumcfa - 0.5f * sumv;
        areawt = 0.5f * areawt;
        const float hvar = kEpsSq + std::fabs(areawt * sumsqh - sumh * sumh);
        const float vvar = kEpsSq + std::fabs(areawt * sumsqv - sumv * sumv);
        hvwt[indx >> 1] = hvar / (vvar + hvar);
      }
    }
  }

  // ── ⑧ 初始 G @ R/B 位（Dgrb0 = G−CFA）+ G 曲率（Nyquist 精修准备）
  for (int rr = 8; rr < rr1 - 8; ++rr) {
    for (int cc = 8 + (fc(rr, 2) & 1), indx = rr * kTs + cc; cc < cc1 - 8;
         cc += 2, indx += 2) {
      const int i1 = indx >> 1;
      // 邻域"定型"：对角 4 邻居的**平均**（÷4）。⚠ 参考实现此处写作
      // `xdivf(a+b+c+d, 2)`（amaze_demosaic_RT.cc:976），若按字面 ÷2 解释，
      // hvwtalt ∈ [0,2] ⇒ 本行写回后 hvwt 越界 ⇒ 输出阶段的归一化分母
      // `hvwt[up] + 2 − hvwt[right] − hvwt[left] + hvwt[down]` 可过零/为负 ⇒
      // R/B 通道数值爆炸。真机同源 DNG 实测（4080×3072）：
      //   ÷2：max 7572、p99.99 717、>3 的像素 126 万（shot.dng 为 11936/652/187 万）
      //   ÷4：max 4.99、p99.99 2.12、>3 仅 367（shot.dng 9.04/2.16/1052）
      // PhotonCamera 的 GLSL 移植（amaze/green.glsl 的 firm()）用的正是 0.25×，
      // 两处独立实现一致 ⇒ 取 ÷4（这同时等价于 xdivf(a,n)=a/2^n 的读法）。
      const float hvwtalt = 0.25f * (hvwt[(indx - m1) >> 1] + hvwt[(indx + p1) >> 1] +
                                     hvwt[(indx - p1) >> 1] + hvwt[(indx + m1) >> 1]);
      // 邻域更确定时改用它
      hvwt[i1] = std::fabs(0.5f - hvwt[i1]) < std::fabs(0.5f - hvwtalt) ? hvwtalt
                                                                        : hvwt[i1];
      Dgrb0[i1] = intp(hvwt[i1], vcd[indx], hcd[indx]);
      rgbgreen[indx] = cfa[indx] + Dgrb0[i1];
      Dgrb2h[i1] = nyq2[i1] ? sq(rgbgreen[indx] - 0.5f * (rgbgreen[indx - 1] +
                                                          rgbgreen[indx + 1]))
                            : 0.f;
      Dgrb2v[i1] = nyq2[i1] ? sq(rgbgreen[indx] - 0.5f * (rgbgreen[indx - v1] +
                                                          rgbgreen[indx + v1]))
                            : 0.f;
    }
  }

  // ── ⑨ Nyquist 区用 G 曲率做精修插值
  if (doNyquist) {
    for (int rr = nystartrow; rr < nyendrow; ++rr) {
      for (int indx = rr * kTs + nystartcol + (fc(rr, 2) & 1);
           indx < rr * kTs + nyendcol; indx += 2) {
        if (!nyq2[indx >> 1]) continue;
        const float gvarh =
            kEpsSq +
            (kGQuinc[0] * Dgrb2h[indx >> 1] +
             kGQuinc[1] * (Dgrb2h[(indx - m1) >> 1] + Dgrb2h[(indx + p1) >> 1] +
                           Dgrb2h[(indx - p1) >> 1] + Dgrb2h[(indx + m1) >> 1]) +
             kGQuinc[2] * (Dgrb2h[(indx - v2) >> 1] + Dgrb2h[(indx - 2) >> 1] +
                           Dgrb2h[(indx + 2) >> 1] + Dgrb2h[(indx + v2) >> 1]) +
             kGQuinc[3] * (Dgrb2h[(indx - m2) >> 1] + Dgrb2h[(indx + p2) >> 1] +
                           Dgrb2h[(indx - p2) >> 1] + Dgrb2h[(indx + m2) >> 1]));
        const float gvarv =
            kEpsSq +
            (kGQuinc[0] * Dgrb2v[indx >> 1] +
             kGQuinc[1] * (Dgrb2v[(indx - m1) >> 1] + Dgrb2v[(indx + p1) >> 1] +
                           Dgrb2v[(indx - p1) >> 1] + Dgrb2v[(indx + m1) >> 1]) +
             kGQuinc[2] * (Dgrb2v[(indx - v2) >> 1] + Dgrb2v[(indx - 2) >> 1] +
                           Dgrb2v[(indx + 2) >> 1] + Dgrb2v[(indx + v2) >> 1]) +
             kGQuinc[3] * (Dgrb2v[(indx - m2) >> 1] + Dgrb2v[(indx + p2) >> 1] +
                           Dgrb2v[(indx - p2) >> 1] + Dgrb2v[(indx + m2) >> 1]));
        Dgrb0[indx >> 1] = (hcd[indx] * gvarv + vcd[indx] * gvarh) / (gvarv + gvarh);
        rgbgreen[indx] = cfa[indx] + Dgrb0[indx >> 1];
      }
    }
  }

  // ── ⑩ 对角梯度（± 方向）与对角色差平方
  for (int rr = 6; rr < rr1 - 6; ++rr) {
    if ((fc(rr, 2) & 1) == 0) {
      for (int cc = 6, indx = rr * kTs + 6; cc < cc1 - 6; cc += 2, indx += 2) {
        const int i1 = indx >> 1;
        delp[i1] = std::fabs(cfa[indx + p1] - cfa[indx - p1]);
        delm[i1] = std::fabs(cfa[indx + m1] - cfa[indx - m1]);
        Dgrbsq1p[i1] = sq(cfa[indx + 1] - cfa[indx + 1 - p1]) +
                       sq(cfa[indx + 1] - cfa[indx + 1 + p1]);
        Dgrbsq1m[i1] = sq(cfa[indx + 1] - cfa[indx + 1 - m1]) +
                       sq(cfa[indx + 1] - cfa[indx + 1 + m1]);
      }
    } else {
      for (int cc = 6, indx = rr * kTs + 6; cc < cc1 - 6; cc += 2, indx += 2) {
        const int i1 = indx >> 1;
        Dgrbsq1p[i1] = sq(cfa[indx] - cfa[indx - p1]) + sq(cfa[indx] - cfa[indx + p1]);
        Dgrbsq1m[i1] = sq(cfa[indx] - cfa[indx - m1]) + sq(cfa[indx] - cfa[indx + m1]);
        delp[i1] = std::fabs(cfa[indx + 1 + p1] - cfa[indx + 1 - p1]);
        delm[i1] = std::fabs(cfa[indx + 1 + m1] - cfa[indx + 1 - m1]);
      }
    }
  }

  // ── ⑪ 对角色差 rbm/rbp + pmwt（± 对角方向的 R−B）
  for (int rr = 8; rr < rr1 - 8; ++rr) {
    for (int cc = 8 + (fc(rr, 2) & 1), indx = rr * kTs + cc, i1 = indx >> 1;
         cc < cc1 - 8; cc += 2, indx += 2, ++i1) {
      const float cur = cfa[indx];
      const float crse = 2.f * cfa[indx + m1] / (kEps + cur + cfa[indx + m2]);
      const float crnw = 2.f * cfa[indx - m1] / (kEps + cur + cfa[indx - m2]);
      const float crne = 2.f * cfa[indx + p1] / (kEps + cur + cfa[indx + p2]);
      const float crsw = 2.f * cfa[indx - p1] / (kEps + cur + cfa[indx - p2]);

      const float rbse = (std::fabs(1.f - crse) < kArThresh)
                             ? cur * crse
                             : cfa[indx + m1] + 0.5f * (cur - cfa[indx + m2]);
      const float rbnw = (std::fabs(1.f - crnw) < kArThresh)
                             ? cur * crnw
                             : cfa[indx - m1] + 0.5f * (cur - cfa[indx - m2]);
      const float rbne = (std::fabs(1.f - crne) < kArThresh)
                             ? cur * crne
                             : cfa[indx + p1] + 0.5f * (cur - cfa[indx + p2]);
      const float rbsw = (std::fabs(1.f - crsw) < kArThresh)
                             ? cur * crsw
                             : cfa[indx - p1] + 0.5f * (cur - cfa[indx - p2]);

      const float wtse = kEps + delm[i1] + delm[(indx + m1) >> 1] + delm[(indx + m2) >> 1];
      const float wtnw = kEps + delm[i1] + delm[(indx - m1) >> 1] + delm[(indx - m2) >> 1];
      const float wtne = kEps + delp[i1] + delp[(indx + p1) >> 1] + delp[(indx + p2) >> 1];
      const float wtsw = kEps + delp[i1] + delp[(indx - p1) >> 1] + delp[(indx - p2) >> 1];

      rbm[i1] = (wtse * rbnw + wtnw * rbse) / (wtse + wtnw);
      rbp[i1] = (wtne * rbsw + wtsw * rbne) / (wtne + wtsw);

      const float rbvarm =
          kEpsSq +
          (kGaussEven[0] * (Dgrbsq1m[(indx - v1) >> 1] + Dgrbsq1m[(indx - 1) >> 1] +
                            Dgrbsq1m[(indx + 1) >> 1] + Dgrbsq1m[(indx + v1) >> 1]) +
           kGaussEven[1] * (Dgrbsq1m[(indx - v2 - 1) >> 1] + Dgrbsq1m[(indx - v2 + 1) >> 1] +
                            Dgrbsq1m[(indx - 2 - v1) >> 1] + Dgrbsq1m[(indx + 2 - v1) >> 1] +
                            Dgrbsq1m[(indx - 2 + v1) >> 1] + Dgrbsq1m[(indx + 2 + v1) >> 1] +
                            Dgrbsq1m[(indx + v2 - 1) >> 1] + Dgrbsq1m[(indx + v2 + 1) >> 1]));
      const float rbvap =
          kEpsSq +
          (kGaussEven[0] * (Dgrbsq1p[(indx - v1) >> 1] + Dgrbsq1p[(indx - 1) >> 1] +
                            Dgrbsq1p[(indx + 1) >> 1] + Dgrbsq1p[(indx + v1) >> 1]) +
           kGaussEven[1] * (Dgrbsq1p[(indx - v2 - 1) >> 1] + Dgrbsq1p[(indx - v2 + 1) >> 1] +
                            Dgrbsq1p[(indx - 2 - v1) >> 1] + Dgrbsq1p[(indx + 2 - v1) >> 1] +
                            Dgrbsq1p[(indx - 2 + v1) >> 1] + Dgrbsq1p[(indx + 2 + v1) >> 1] +
                            Dgrbsq1p[(indx + v2 - 1) >> 1] + Dgrbsq1p[(indx + v2 + 1) >> 1]));
      pmwt[i1] = rbvarm / (rbvap + rbvarm);

      // 高饱和区限幅
      if (rbp[i1] < cur) {
        if (2.f * rbp[i1] < cur) {
          rbp[i1] = median3(rbp[i1], cfa[indx - p1], cfa[indx + p1]);
        } else {
          const float pwt = 2.f * (cur - rbp[i1]) / (kEps + rbp[i1] + cur);
          rbp[i1] = pwt * rbp[i1] +
                    (1.f - pwt) * median3(rbp[i1], cfa[indx - p1], cfa[indx + p1]);
        }
      }
      if (rbm[i1] < cur) {
        if (2.f * rbm[i1] < cur) {
          rbm[i1] = median3(rbm[i1], cfa[indx - m1], cfa[indx + m1]);
        } else {
          const float mwt = 2.f * (cur - rbm[i1]) / (kEps + rbm[i1] + cur);
          rbm[i1] = mwt * rbm[i1] +
                    (1.f - mwt) * median3(rbm[i1], cfa[indx - m1], cfa[indx + m1]);
        }
      }
      if (rbp[i1] > clip_pt) {
        rbp[i1] = median3(rbp[i1], cfa[indx - p1], cfa[indx + p1]);
      }
      if (rbm[i1] > clip_pt) {
        rbm[i1] = median3(rbm[i1], cfa[indx - m1], cfa[indx + m1]);
      }
    }
  }

  // ── ⑫ pmwt 邻域修正 + R+B 对角插值（rbint）
  for (int rr = 10; rr < rr1 - 10; ++rr) {
    for (int cc = 10 + (fc(rr, 2) & 1), indx = rr * kTs + cc, i1 = indx >> 1;
         cc < cc1 - 10; cc += 2, indx += 2, ++i1) {
      // 同阶段 ⑧ 的定型口径（参考实现 xdivf(…, 2) → ÷4，见该处注释与实测数据）
      const float pmwtalt = 0.25f * (pmwt[(indx - m1) >> 1] + pmwt[(indx + p1) >> 1] +
                                     pmwt[(indx - p1) >> 1] + pmwt[(indx + m1) >> 1]);
      if (std::fabs(0.5f - pmwt[i1]) < std::fabs(0.5f - pmwtalt)) pmwt[i1] = pmwtalt;
      rbint[i1] = 0.5f * (cfa[indx] + rbm[i1] * (1.f - pmwt[i1]) +
                          rbp[i1] * pmwt[i1]);
    }
  }

  // ── ⑬ 用 R+B 重新插值 G（对角色差携带更多高频信息）
  for (int rr = 12; rr < rr1 - 12; ++rr) {
    for (int cc = 12 + (fc(rr, 2) & 1), indx = rr * kTs + cc, i1 = indx >> 1;
         cc < cc1 - 12; cc += 2, indx += 2, ++i1) {
      if (std::fabs(0.5f - pmwt[i1]) < std::fabs(0.5f - hvwt[i1])) continue;
      // ⚠ 参考实现此处用 rbint[indx1 ± v1]（v1 = ts = 2·tsh，半网格里等于
      //   上/下**两行**）；本实现逐字保留该索引，不擅自改成 ±tsh。
      const float cru = cfa[indx - v1] * 2.f / (kEps + rbint[i1] + rbint[i1 - v1]);
      const float crd = cfa[indx + v1] * 2.f / (kEps + rbint[i1] + rbint[i1 + v1]);
      const float crl = cfa[indx - 1] * 2.f / (kEps + rbint[i1] + rbint[i1 - 1]);
      const float crr = cfa[indx + 1] * 2.f / (kEps + rbint[i1] + rbint[i1 + 1]);

      const float gu = (std::fabs(1.f - cru) < kArThresh)
                           ? rbint[i1] * cru
                           : cfa[indx - v1] + 0.5f * (rbint[i1] - rbint[i1 - v1]);
      const float gd = (std::fabs(1.f - crd) < kArThresh)
                           ? rbint[i1] * crd
                           : cfa[indx + v1] + 0.5f * (rbint[i1] - rbint[i1 + v1]);
      const float gl = (std::fabs(1.f - crl) < kArThresh)
                           ? rbint[i1] * crl
                           : cfa[indx - 1] + 0.5f * (rbint[i1] - rbint[i1 - 1]);
      const float gr = (std::fabs(1.f - crr) < kArThresh)
                           ? rbint[i1] * crr
                           : cfa[indx + 1] + 0.5f * (rbint[i1] - rbint[i1 + 1]);

      float Gintv = (dirwts0[indx - v1] * gd + dirwts0[indx + v1] * gu) /
                    (dirwts0[indx + v1] + dirwts0[indx - v1]);
      float Ginth = (dirwts1[indx - 1] * gr + dirwts1[indx + 1] * gl) /
                    (dirwts1[indx - 1] + dirwts1[indx + 1]);

      if (Gintv < rbint[i1]) {
        if (2.f * Gintv < rbint[i1]) {
          Gintv = median3(Gintv, cfa[indx - v1], cfa[indx + v1]);
        } else {
          const float vwt = 2.f * (rbint[i1] - Gintv) / (kEps + Gintv + rbint[i1]);
          Gintv = vwt * Gintv +
                  (1.f - vwt) * median3(Gintv, cfa[indx - v1], cfa[indx + v1]);
        }
      }
      if (Ginth < rbint[i1]) {
        if (2.f * Ginth < rbint[i1]) {
          Ginth = median3(Ginth, cfa[indx - 1], cfa[indx + 1]);
        } else {
          const float hwt = 2.f * (rbint[i1] - Ginth) / (kEps + Ginth + rbint[i1]);
          Ginth = hwt * Ginth +
                  (1.f - hwt) * median3(Ginth, cfa[indx - 1], cfa[indx + 1]);
        }
      }
      if (Ginth > clip_pt) Ginth = median3(Ginth, cfa[indx - 1], cfa[indx + 1]);
      if (Gintv > clip_pt) Gintv = median3(Gintv, cfa[indx - v1], cfa[indx + v1]);

      rgbgreen[indx] = Ginth * (1.f - hvwt[i1]) + Gintv * hvwt[i1];
      Dgrb0[i1] = rgbgreen[indx] - cfa[indx];
    }
  }

  // ── ⑭ Dgrb 拆分：B 位存 G−B 到 Dgrb1，R 位保留 G−R 于 Dgrb0
  {
    // (ey,ex) = R 位在 2×2 内的偏移
    int ey, ex;
    if (pattern[0] == 1) {
      if (pattern[1] == 0) {
        ey = 0;
        ex = 1;
      } else {
        ey = 1;
        ex = 0;
      }
    } else {
      if (pattern[0] == 0) {
        ey = 0;
        ex = 0;
      } else {
        ey = 1;
        ex = 1;
      }
    }
    for (int rr = 13 - ey; rr < rr1 - 12; rr += 2) {
      for (int i1 = (rr * kTs + 13 - ex) >> 1; i1 < (rr * kTs + cc1 - 12) >> 1;
           ++i1) {
        Dgrb1[i1] = Dgrb0[i1];
        Dgrb0[i1] = 0.f;
      }
    }
  }

  // ── ⑮ chrominance 插值（fancier 权重）
  for (int rr = 14; rr < rr1 - 14; ++rr) {
    for (int cc = 14 + (fc(rr, 2) & 1), indx = rr * kTs + cc; cc < cc1 - 14;
         cc += 2, indx += 2) {
      // R 位 → 插 G−B（c=1）；B 位 → 插 G−R（c=0）
      float* D = (1 - fc(rr, cc) / 2) ? Dgrb1 : Dgrb0;
      const float wtnw =
          1.f / (kEps + std::fabs(D[(indx - m1) >> 1] - D[(indx + m1) >> 1]) +
                 std::fabs(D[(indx - m1) >> 1] - D[(indx - m3) >> 1]) +
                 std::fabs(D[(indx + m1) >> 1] - D[(indx - m3) >> 1]));
      const float wtne =
          1.f / (kEps + std::fabs(D[(indx + p1) >> 1] - D[(indx - p1) >> 1]) +
                 std::fabs(D[(indx + p1) >> 1] - D[(indx + p3) >> 1]) +
                 std::fabs(D[(indx - p1) >> 1] - D[(indx + p3) >> 1]));
      const float wtsw =
          1.f / (kEps + std::fabs(D[(indx - p1) >> 1] - D[(indx + p1) >> 1]) +
                 std::fabs(D[(indx - p1) >> 1] - D[(indx + m3) >> 1]) +
                 std::fabs(D[(indx + p1) >> 1] - D[(indx - p3) >> 1]));
      const float wtse =
          1.f / (kEps + std::fabs(D[(indx + m1) >> 1] - D[(indx - m1) >> 1]) +
                 std::fabs(D[(indx + m1) >> 1] - D[(indx - p3) >> 1]) +
                 std::fabs(D[(indx - m1) >> 1] - D[(indx + m3) >> 1]));
      D[indx >> 1] =
          (wtnw * (1.325f * D[(indx - m1) >> 1] - 0.175f * D[(indx - m3) >> 1] -
                   0.075f * D[(indx - m1 - 2) >> 1] - 0.075f * D[(indx - m1 - v2) >> 1]) +
           wtne * (1.325f * D[(indx + p1) >> 1] - 0.175f * D[(indx + p3) >> 1] -
                   0.075f * D[(indx + p1 + 2) >> 1] - 0.075f * D[(indx + p1 + v2) >> 1]) +
           wtsw * (1.325f * D[(indx - p1) >> 1] - 0.175f * D[(indx - p3) >> 1] -
                   0.075f * D[(indx - p1 - 2) >> 1] - 0.075f * D[(indx - p1 - v2) >> 1]) +
           wtse * (1.325f * D[(indx + m1) >> 1] - 0.175f * D[(indx + m3) >> 1] -
                   0.075f * D[(indx + m1 + 2) >> 1] - 0.075f * D[(indx + m1 + v2) >> 1])) /
          (wtnw + wtne + wtsw + wtse);
    }
  }

  // ── ⑯ 输出：G 位用 hvwt 加权的邻域色差，R/B 位直接取色差
  for (int rr = 16; rr < rr1 - 16; ++rr) {
    const int row = top + rr;
    if (row < 0 || row >= h) continue;
    for (int cc = 16, indx = rr * kTs + 16; cc < cc1 - 16; ++cc, ++indx) {
      const int col = left + cc;
      if (col < 0 || col >= w) continue;
      const float g = rgbgreen[indx];
      float d0, d1;
      if (fc(rr, cc) & 1) {  // G 位：两个色差都要插值
        const float temp = 1.f / (hvwt[(indx - v1) >> 1] + 2.f -
                                  hvwt[(indx + 1) >> 1] - hvwt[(indx - 1) >> 1] +
                                  hvwt[(indx + v1) >> 1]);
        d0 = (hvwt[(indx - v1) >> 1] * Dgrb0[(indx - v1) >> 1] +
              (1.f - hvwt[(indx + 1) >> 1]) * Dgrb0[(indx + 1) >> 1] +
              (1.f - hvwt[(indx - 1) >> 1]) * Dgrb0[(indx - 1) >> 1] +
              hvwt[(indx + v1) >> 1] * Dgrb0[(indx + v1) >> 1]) *
             temp;
        d1 = (hvwt[(indx - v1) >> 1] * Dgrb1[(indx - v1) >> 1] +
              (1.f - hvwt[(indx + 1) >> 1]) * Dgrb1[(indx + 1) >> 1] +
              (1.f - hvwt[(indx - 1) >> 1]) * Dgrb1[(indx - 1) >> 1] +
              hvwt[(indx + v1) >> 1] * Dgrb1[(indx + v1) >> 1]) *
             temp;
      } else {  // R/B 位：直接取该位置已算好的色差
        d0 = Dgrb0[indx >> 1];
        d1 = Dgrb1[indx >> 1];
      }
      float* o = out + ((size_t)row * w + col) * 3;
      o[0] = std::max(0.f, g - d0);
      o[1] = std::max(0.f, g);
      o[2] = std::max(0.f, g - d1);
    }
  }
}

}  // namespace

void AmazeDemosaic(const float* cfa, int w, int h, const int pattern[4],
                   float clip_pt, float* out_rgb, int threads) {
  if (!cfa || !out_rgb || !pattern || w < 32 || h < 32) return;
  if (!(clip_pt > 0.f)) clip_pt = 1.f;

  // tile 网格（与参考实现同构：起点 −16，步进 ts−32，重叠 32px）
  struct Tile {
    int top, left;
  };
  std::vector<Tile> tiles;
  tiles.reserve(((w + 16) / kStep + 2) * ((h + 16) / kStep + 2));
  for (int top = -16; top < h; top += kStep)
    for (int left = -16; left < w; left += kStep) tiles.push_back({top, left});

  int nth = threads > 0 ? threads : (int)std::thread::hardware_concurrency();
  if (nth < 1) nth = 1;
  if (nth > 8) nth = 8;
  if (nth > (int)tiles.size()) nth = (int)tiles.size();

  std::atomic<size_t> next{0};
  auto worker = [&]() {
    Scratch S;
    S.alloc();
    size_t i;
    while ((i = next.fetch_add(1, std::memory_order_relaxed)) < tiles.size()) {
      processTile(cfa, w, h, pattern, clip_pt, out_rgb, tiles[i].top,
                  tiles[i].left, &S);
    }
  };
  if (nth <= 1) {
    worker();
  } else {
    std::vector<std::thread> pool;
    pool.reserve(nth);
    for (int i = 0; i < nth; ++i) pool.emplace_back(worker);
    for (auto& t : pool) t.join();
  }
}

}  // namespace filmkit
