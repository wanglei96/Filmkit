#include "LocalNoisePropagation.h"

#include <algorithm>
#include <cmath>
#include <map>

namespace filmkit {
namespace {
bool nonnegative(double v) { return std::isfinite(v) && v >= 0; }
struct Source {
  LocalNoiseTap tap;
  double weight = 0;
};
bool collect(const std::vector<LocalNoiseTap>& taps,
             std::map<uint64_t, Source>* sources, double* support) {
  *support = 0;
  for (const auto& tap : taps) {
    if (!std::isfinite(tap.signal) || !nonnegative(tap.shot) ||
        !nonnegative(tap.read) || !std::isfinite(tap.gain) || tap.gain <= 0 ||
        !nonnegative(tap.weight)) return false;
    if (tap.weight == 0) continue;
    auto [it, inserted] = sources->emplace(tap.source, Source{tap, 0});
    const auto& old = it->second.tap;
    if (!inserted && (old.signal != tap.signal || old.shot != tap.shot ||
                     old.read != tap.read || old.gain != tap.gain)) return false;
    it->second.weight += tap.weight;
    *support += tap.weight;
  }
  return std::isfinite(*support) && *support > 0;
}
bool sameNativeSource(const Source& a, const Source& b) {
  return a.tap.signal == b.tap.signal && a.tap.shot == b.tap.shot &&
         a.tap.read == b.tap.read;
}
}  // namespace

bool localNoiseMerge(const std::vector<LocalNoiseTap>& taps,
                     LocalNoiseValue* output) {
  if (!output) return false;
  *output = {};
  std::map<uint64_t, Source> sources;
  double support;
  if (!collect(taps, &sources, &support)) return false;
  LocalNoiseValue result;
  for (const auto& pair : sources) {
    const auto& s = pair.second;
    const auto& t = s.tap;
    const double a = s.weight / support;
    result.signal += a * t.gain * t.signal;
    result.variance += a * a * t.gain * t.gain *
                       (t.shot * std::max(t.signal, 0.0) + t.read);
    result.shot += a * a * t.gain * t.shot;
    result.read += a * a * t.gain * t.gain * t.read;
  }
  if (!std::isfinite(result.signal) || !nonnegative(result.variance) ||
      !nonnegative(result.shot) || !nonnegative(result.read)) return false;
  *output = result;
  return true;
}

bool localNoiseCrossCovariance(const std::vector<LocalNoiseTap>& left,
                              const std::vector<LocalNoiseTap>& right,
                              double* covariance) {
  if (!covariance) return false;
  *covariance = 0;
  std::map<uint64_t, Source> ls, rs;
  double lw, rw;
  if (!collect(left, &ls, &lw) || !collect(right, &rs, &rw)) return false;
  double result = 0;
  for (const auto& pair : ls) {
    const auto found = rs.find(pair.first);
    if (found == rs.end()) continue;
    const auto& l = pair.second;
    const auto& r = found->second;
    if (!sameNativeSource(l, r)) return false;
    result += (l.weight / lw) * (r.weight / rw) * l.tap.gain * r.tap.gain *
              (l.tap.shot * std::max(l.tap.signal, 0.0) + l.tap.read);
  }
  if (!nonnegative(result)) return false;
  *covariance = result;
  return true;
}

bool localNoiseReduce(const std::vector<LocalNoiseObservation>& observations,
                      uint8_t native_cfa_lut, LocalNoiseRgbModel* output) {
  if (!output) return false;
  *output = {};
  int counts[3] = {};
  for (int phase = 0; phase < 4; ++phase) {
    const int ch = (native_cfa_lut >> (2 * phase)) & 3;
    if (ch > 2) return false;
    ++counts[ch];
  }
  if (counts[0] != 1 || counts[1] != 2 || counts[2] != 1) return false;
  LocalNoiseRgbModel result;
  for (const auto& o : observations) {
    if (o.phase < 0 || o.phase > 3 || !nonnegative(o.sample_weight) ||
        !std::isfinite(o.value.signal) || !nonnegative(o.value.variance) ||
        !nonnegative(o.value.shot) || !nonnegative(o.value.read)) return false;
    const int ch = (native_cfa_lut >> (2 * o.phase)) & 3;
    result.signal[ch] += o.sample_weight * o.value.signal;
    result.variance[ch] += o.sample_weight * o.value.variance;
    result.shot[ch] += o.sample_weight * o.value.shot;
    result.read[ch] += o.sample_weight * o.value.read;
    result.support[ch] += o.sample_weight;
  }
  for (int ch = 0; ch < 3; ++ch) {
    if (!std::isfinite(result.support[ch]) || result.support[ch] <= 0) return false;
    result.signal[ch] /= result.support[ch];
    result.variance[ch] /= result.support[ch];
    result.shot[ch] /= result.support[ch];
    result.read[ch] /= result.support[ch];
    if (!std::isfinite(result.signal[ch]) || !nonnegative(result.variance[ch]) ||
        !nonnegative(result.shot[ch]) || !nonnegative(result.read[ch])) return false;
  }
  *output = result;
  return true;
}

bool localNoiseToD4(const LocalNoiseCovariance& camera,
                    const std::array<double, 3>& lsc,
                    const std::array<double, 9>& ccm,
                    LocalNoiseCovariance* output) {
  if (!output) return false;
  *output = {};
  for (double x : camera) if (!std::isfinite(x)) return false;
  for (double x : ccm) if (!std::isfinite(x)) return false;
  for (double x : lsc) if (!std::isfinite(x) || x <= 0) return false;
  for (int i = 0; i < 3; ++i) {
    if (camera[3 * i + i] < 0) return false;
    for (int j = 0; j < 3; ++j)
      if (std::abs(camera[3 * i + j] - camera[3 * j + i]) >
          1e-12 * std::max({1.0, std::abs(camera[3 * i + j]),
                          std::abs(camera[3 * j + i])})) return false;
  }
  LocalNoiseCovariance result{};
  for (int i = 0; i < 3; ++i)
    for (int j = 0; j < 3; ++j)
      for (int k = 0; k < 3; ++k)
        for (int m = 0; m < 3; ++m)
          result[3 * i + j] += ccm[3 * i + k] * lsc[k] *
                              camera[3 * k + m] * lsc[m] * ccm[3 * j + m];
  for (double v : result) if (!std::isfinite(v)) return false;
  *output = result;
  return true;
}

LocalNoiseCovariance localNoiseDifferenceCovariance(
    const LocalNoiseCovariance& p, const LocalNoiseCovariance& q,
    const LocalNoiseCovariance& cross) {
  LocalNoiseCovariance result;
  for (int i = 0; i < 3; ++i)
    for (int j = 0; j < 3; ++j)
      result[3 * i + j] = p[3 * i + j] + q[3 * i + j] -
                          cross[3 * i + j] - cross[3 * j + i];
  return result;
}

double localNoiseProjectedVariance(const LocalNoiseCovariance& c,
                                   const std::array<double, 3>& d) {
  double result = 0;
  for (int i = 0; i < 3; ++i)
    for (int j = 0; j < 3; ++j) result += d[i] * c[3 * i + j] * d[j];
  return result;
}

bool localNoiseLinearCovarianceBound(
    const std::vector<LocalNoiseCovariance>& covariance,
    const std::vector<double>& coefficients, LocalNoiseCovariance* output) {
  if (!output) return false;
  *output = {};
  if (covariance.empty() || covariance.size() != coefficients.size()) return false;
  LocalNoiseCovariance result{};
  double total = 0;
  for (size_t i = 0; i < covariance.size(); ++i) {
    if (!std::isfinite(coefficients[i])) return false;
    const double a = std::abs(coefficients[i]);
    total += a;
    for (int k = 0; k < 9; ++k) {
      if (!std::isfinite(covariance[i][k])) return false;
      result[k] += a * covariance[i][k];
    }
  }
  if (!std::isfinite(total)) return false;
  for (double& v : result) {
    v *= total;
    if (!std::isfinite(v)) return false;
  }
  *output = result;
  return true;
}

bool localNoiseCorrelationFromDiagnostics(
    const std::array<double, 3>& d0, const std::array<double, 3>& d1,
    LocalNoiseCorrelation* output) {
  if (!output) return false;
  *output = {};
  for (double x : d0) if (!nonnegative(x)) return false;
  for (double x : d1) if (!nonnegative(x)) return false;
  LocalNoiseCorrelation result;
  const double r = d1[1] / (4 * std::max(d0[1], 1e-6));
  if (!nonnegative(r)) return false;
  result.diagnostic_ratio = r;
  if (r >= 1e-4) {
    const double u = 3 * r - 1;
    const double v = 1 - 4 * r;
    const double root = std::sqrt(8 * r * u + 1);
    const double scale = std::sqrt(8 * r * r / (root + v));
    const double numerator = 1 - 8 * r + 20 * r * r + v * root;
    const double denominator = 16 * r * u + 2;
    result.outer_tap = 0.5 * scale * std::pow(numerator / denominator, 0.25);
    result.center_tap = scale * (0.5 / (r * std::pow(denominator, 0.25))) *
                        (1 / (v + root)) * numerator * std::pow(numerator, 0.25);
  }
  if (!nonnegative(result.outer_tap) || !nonnegative(result.center_tap)) return false;
  constexpr double pi = 3.1415926535897932384626433832795;
  double energy = 0;
  for (int i = 0; i < 128; ++i) {
    const double f = (i + 0.5) * 2 * pi / 128 - pi;
    const double response = result.center_tap + 2 * result.outer_tap * std::cos(f);
    result.spectrum[i] = response * response;
    energy += result.spectrum[i];
  }
  if (!std::isfinite(energy) || energy <= 0) return false;
  for (double& power : result.spectrum) power *= 128 / energy;
  *output = result;
  return true;
}

bool localNoiseNativeCoordinate(int x, int y, int w, int h, int rotation,
                                int* sx, int* sy) {
  if (!sx || !sy || w <= 0 || h <= 0) return false;
  if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
    return false;
  const int dw = (rotation == 90 || rotation == 270) ? h : w;
  const int dh = (rotation == 90 || rotation == 270) ? w : h;
  if (x < 0 || y < 0 || x >= dw || y >= dh) return false;
  switch (rotation) {
    case 90: *sx = y; *sy = h - 1 - x; break;
    case 180: *sx = w - 1 - x; *sy = h - 1 - y; break;
    case 270: *sx = w - 1 - y; *sy = x; break;
    default: *sx = x; *sy = y; break;
  }
  return true;
}
}  // namespace filmkit
