#include "HighlightRecovery.h"
#include "ParallelRows.h"

// CPU reference of PhotonCamera GlesMgcRawSpatialShaders: guide (13-110),
// bentoGenerateHighlightMask (965-995), bentoAdjustHighlightMask (1112-1183),
// alignedRawClippingMask (1525-1588), convertAlignment (2711-2755).
// Calibration/LUT/admission follow GlesMgcRawSpatialStacker calibrationForFrame,
// renderGuide/createNoiseLut/assessBentoMasks and MgcSpatialNoiseEstimatesLut.
// All RGB moments and the RGB-vector one-sided residual are retained; this module
// intentionally generates masks only, never a cross-CFA scalar reconstruction.

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <utility>

namespace filmkit {
namespace {
constexpr float kClip = 250.f / 255.f;
constexpr float kInpaintMinimum = 128.f / 255.f;
constexpr float kErrorThreshold = .9f;
constexpr std::size_t kMaximumHole = 80;

bool fail(std::string* error, const char* message) {
  if (error) *error = message;
  return false;
}
float unit(float x) { return std::max(0.f, std::min(x, 1.f)); }
int bounded(int x, int hi) { return std::max(0, std::min(x, hi)); }

// IEEE binary32 -> binary16 -> binary32. Round-to-nearest, ties-to-even,
// including half subnormals; independent of the process floating-point mode.
float halfStorage(float value) {
  uint32_t b;
  std::memcpy(&b, &value, sizeof(b));
  const uint32_t sign = b & 0x80000000u;
  const int exponent = static_cast<int>((b >> 23) & 255u) - 127;
  uint32_t mantissa = b & 0x7fffffu;
  uint32_t h = 0;
  if (exponent > 15) {
    h = 0x7c00u;
    if (((b >> 23) & 255u) == 255u && mantissa) h |= 0x200u;
  } else if (exponent >= -14) {
    uint32_t m = mantissa >> 13;
    const uint32_t rem = mantissa & 0x1fffu;
    if (rem > 0x1000u || (rem == 0x1000u && (m & 1u))) ++m;
    h = static_cast<uint32_t>(exponent + 15) * 1024u + m;
  } else if (exponent >= -25) {
    mantissa |= 0x800000u;
    const int shift = -exponent - 1;
    uint32_t m = mantissa >> shift;
    const uint32_t rem = mantissa & ((1u << shift) - 1u);
    const uint32_t halfway = 1u << (shift - 1);
    if (rem > halfway || (rem == halfway && (m & 1u))) ++m;
    h = m;
  }
  const uint32_t e = (h >> 10) & 31u;
  const uint32_t m = h & 1023u;
  float out;
  if (e == 31u) {
    const uint32_t bits = sign | 0x7f800000u | (m ? 0x400000u : 0u);
    std::memcpy(&out, &bits, sizeof(out));
    return out;
  }
  out = e ? std::ldexp(static_cast<float>(1024u + m), static_cast<int>(e) - 25)
          : std::ldexp(static_cast<float>(m), -24);
  return sign ? -out : out;
}

bool dimensions(int w, int h, std::size_t* n) {
  if (w <= 0 || h <= 0) return false;
  const std::size_t sw = static_cast<std::size_t>(w);
  const std::size_t sh = static_cast<std::size_t>(h);
  if (sw > std::numeric_limits<std::size_t>::max() / sh / (4 * sizeof(float)))
    return false;
  *n = sw * sh;
  return true;
}

bool validRaw(const HighlightRawView& raw, std::string* error) {
  std::size_t count;
  if (!raw.data || raw.width < 8 || raw.height < 8 ||
      raw.stride < raw.width || !dimensions(raw.width, raw.height, &count) ||
      raw.stride > std::numeric_limits<std::ptrdiff_t>::max() / raw.height)
    return fail(error, "RAW requires nonnull data, >=8x8 and valid uint16 stride");
  if (!std::isfinite(raw.white) || raw.white <= 0.f || raw.white > 65535.f)
    return fail(error, "RAW white must be finite in (0,65535]");
  int channels[4];
  int counts[3] = {0, 0, 0};
  if (raw.cfaLut > 255u) return fail(error, "CFA LUT must contain four 2-bit RGB phases");
  for (int p = 0; p < 4; ++p) {
    if (!std::isfinite(raw.black_phase[p]) || raw.black_phase[p] < 0.f ||
        raw.black_phase[p] >= raw.white)
      return fail(error, "RAW black phase must be finite and below white");
    channels[p] = static_cast<int>((raw.cfaLut >> (2 * p)) & 3u);
    if (channels[p] > 2) return fail(error, "CFA contains non-RGB channel");
    ++counts[channels[p]];
  }
  if (counts[0] != 1 || counts[1] != 2 || counts[2] != 1 ||
      channels[0] + channels[3] != 2 || channels[1] + channels[2] != 2 ||
      ((channels[0] == 1) != (channels[3] == 1)))
    return fail(error, "CFA must be RGGB, GRBG, GBRG or BGGR");
  return true;
}

int mirrorGuideCenter(int coordinate, int extent) {
  const int maximum = extent - 2;
  coordinate = coordinate < 1 ? 2 - coordinate : coordinate;
  return coordinate > maximum ? 2 * maximum - coordinate : coordinate;
}

// Same clamp as guide.rawQuad (not the aligned native clipping footprint).
std::array<float, 4> rawQuad(const HighlightRawView& raw, int qx, int qy,
                           const std::array<float, 4>& gains,
                           const std::array<float, 4>& blackTerms) {
  const int x = bounded(qx * 2, raw.width - 2);
  const int y = bounded(qy * 2, raw.height - 2);
  std::array<float, 4> rgbg{{0.f, 0.f, 0.f, 0.f}};
  for (int phase = 0; phase < 4; ++phase) {
    const int c = (raw.cfaLut >> (phase * 2)) & 3u;
    const int canonical = c == 0 ? 0 : (c == 2 ? 3 : ((phase < 2) ? 1 : 2));
    const float code = raw.data[static_cast<std::ptrdiff_t>(y + (phase >> 1)) *
                               raw.stride + x + (phase & 1)];
    rgbg[canonical] = (code + 1.e-4f) * gains[phase] + blackTerms[phase];
  }
  return rgbg;
}

bool quadClipped(const HighlightRawView& raw, int x, int y,
                 const std::array<uint32_t, 4>& thresholds) {
  x = bounded(x, (raw.width - 1) / 2);
  y = bounded(y, (raw.height - 1) / 2);
  for (int p = 0; p < 4; ++p) {
    const int sx = std::min(x * 2 + (p & 1), raw.width - 1);
    const int sy = std::min(y * 2 + (p >> 1), raw.height - 1);
    if (raw.data[static_cast<std::ptrdiff_t>(sy) * raw.stride + sx] >= thresholds[p])
      return true;
  }
  return false;
}
std::array<uint32_t, 4> clippingLevels(const HighlightRawView& raw) {
  std::array<uint32_t, 4> levels;
  for (int p = 0; p < 4; ++p)
    levels[p] = static_cast<uint32_t>(std::ceil(raw.black_phase[p] +
                              (raw.white - raw.black_phase[p]) * kClip));
  return levels;
}
bool footprintClipped(const HighlightRawView& raw, int x, int y,
                      const std::array<uint32_t, 4>& levels) {
  for (int dy = -1; dy <= 1; ++dy)
    for (int dx = -1; dx <= 1; ++dx)
      if (quadClipped(raw, x + dx, y + dy, levels)) return true;
  return false;
}
uint8_t storeR8(float x) {
  return static_cast<uint8_t>(std::floor(unit(x) * 255.f + .5f));
}
float quantizeBase(float v) { return std::round(65535.f * unit(v)) / 65535.f; }

bool validGuide(const HighlightGuide& g, int w, int h) {
  std::size_t n;
  if (g.width != w || g.height != h || !dimensions(w, h, &n) ||
      g.rgb.size() != n * 3 || g.variance.size() != n) return false;
  for (float v : g.rgb) if (!std::isfinite(v)) return false;
  for (float v : g.variance) if (!std::isfinite(v)) return false;
  return true;
}
float mirroredUv(float v) {
  v = std::fmod(v, 2.f);
  if (v < 0.f) v += 2.f;
  return v > 1.f ? 2.f - v : v;
}
std::array<float, 3> sampleMirrored(const HighlightGuide& g, float u, float v) {
  const float x = mirroredUv(u) * g.width - .5f;
  const float y = mirroredUv(v) * g.height - .5f;
  const int ix = static_cast<int>(std::floor(x));
  const int iy = static_cast<int>(std::floor(y));
  const float fx = x - ix, fy = y - iy;
  std::array<float, 3> rgb{{0.f, 0.f, 0.f}};
  for (int dy = 0; dy < 2; ++dy)
    for (int dx = 0; dx < 2; ++dx) {
      const float weight = (dx ? fx : 1.f - fx) * (dy ? fy : 1.f - fy);
      const std::size_t p = static_cast<std::size_t>(bounded(iy + dy, g.height - 1)) *
                           g.width + bounded(ix + dx, g.width - 1);
      for (int c = 0; c < 3; ++c) rgb[c] += weight * g.rgb[3 * p + c];
    }
  return rgb;
}

// GLSL roundEven, without relying on ambient fenv. Clamp before conversion to
// integer: out-of-image flow has clamp-to-edge native clipping semantics.
int anchorEven(float position, int maximum) {
  if (position <= -1.f) return -1;
  if (position >= maximum + 1.f) return maximum + 1;
  const float low = std::floor(position);
  const float fraction = position - low;
  int i = static_cast<int>(low);
  if (fraction > .5f || (fraction == .5f && (i & 1))) ++i;
  return i;
}

std::size_t largestHole(const std::vector<uint8_t>& mask, int w, int h) {
  std::vector<uint8_t> visited(mask.size(), 0);
  std::vector<std::size_t> pending;
  std::size_t largest = 0;
  for (std::size_t start = 0; start < mask.size(); ++start) {
    if (!mask[start] || visited[start]) continue;
    pending.clear();
    pending.push_back(start);
    visited[start] = 1;
    for (std::size_t i = 0; i < pending.size(); ++i) {
      const int x = static_cast<int>(pending[i] % w);
      const int y = static_cast<int>(pending[i] / w);
      for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
          const int nx = x + dx, ny = y + dy;
          if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
          const std::size_t next = static_cast<std::size_t>(ny) * w + nx;
          if (!mask[next] || visited[next]) continue;
          visited[next] = 1;
          pending.push_back(next);
        }
    }
    largest = std::max(largest, pending.size());
  }
  return largest;
}
}  // namespace

bool highlightGenerateGuide(const HighlightRawView& raw,
                            const std::array<float, 3>& wb,
                            float exposureScale, const HighlightNoise& noise,
                            HighlightGuide* output, std::string* error) {
  if (error) error->clear();
  if (!output) return fail(error, "guide output is null");
  *output = HighlightGuide{};
  if (!validRaw(raw, error)) return false;
  if (!std::isfinite(exposureScale) || exposureScale <= 0.f)
    return fail(error, "exposure scale must be finite and positive");
  for (float gain : wb)
    if (!std::isfinite(gain) || gain <= 0.f)
      return fail(error, "WB gains must be finite and positive");
  std::array<float, 4> gains, blackTerms, shot, read;
  float greenClipping = 0.f;
  for (int p = 0; p < 4; ++p) {
    if (!std::isfinite(noise.shot[p]) || !std::isfinite(noise.read[p]) ||
        noise.shot[p] < 0.f || noise.read[p] < 0.f)
      return fail(error, "noise shot/read must be finite nonnegative normalized variance");
    const int c = (raw.cfaLut >> (2 * p)) & 3u;
    const float relativeGain = wb[c] * exposureScale;
    gains[p] = relativeGain / std::max(raw.white - raw.black_phase[p], 1.f);
    blackTerms[p] = -raw.black_phase[p] * gains[p];
    shot[p] = noise.shot[p] * relativeGain;
    read[p] = noise.read[p] * relativeGain * relativeGain;
    if (!std::isfinite(gains[p]) || !std::isfinite(shot[p]) || !std::isfinite(read[p]))
      return fail(error, "calibration overflow");
    if (c == 1) greenClipping += .5f * (raw.white * gains[p] + blackTerms[p]);
  }
  // LUT's current-row green component, stored in RGBA16F. The reference row
  // cannot leak in: v=.5*1+.25=.75 is exactly row 1's texel center.
  std::array<float, 10> greenLut;
  for (int x = 0; x < 10; ++x) {
    float green = 0.f;
    const float signal = static_cast<float>(x) / 9.f;
    for (int p = 0; p < 4; ++p)
      if (((raw.cfaLut >> (2 * p)) & 3u) == 1)
        green += .25f * (shot[p] * signal + read[p]);
    greenLut[x] = halfStorage(green);
    if (!std::isfinite(greenLut[x])) return fail(error, "noise LUT binary16 overflow");
  }
  HighlightGuide result;
  result.width = raw.width / 2 + raw.width % 2;
  result.height = raw.height / 2 + raw.height % 2;
  const std::size_t n = static_cast<std::size_t>(result.width) * result.height;
  result.rgb.resize(n * 3);
  result.variance.resize(n);
  std::vector<uint8_t> row_error(result.height, 0);
  parallelRows(result.height, [&](int row0, int row1) {
  // Cache calibrated CFA quads, not reduced moments: every output retains the
  // reference's nine samples and exact accumulation order. Each worker owns
  // its rows; reflected borders and odd RAW extents still use rawQuad's clamp.
  std::array<std::vector<std::array<float, 4>>, 3> quadRows;
  std::array<int, 3> cachedY{{-1, -1, -1}};
  for (auto& row : quadRows) row.resize(result.width);
  for (int y = row0; y < row1; ++y) {
    const int cy = mirrorGuideCenter(y, result.height);
    const std::array<float, 4>* neighbors[3];
    for (int dy = -1; dy <= 1; ++dy) {
      const int qy = cy + dy;
      const int slot = qy % 3;
      if (cachedY[slot] != qy) {
        for (int qx = 0; qx < result.width; ++qx)
          quadRows[slot][qx] = rawQuad(raw, qx, qy, gains, blackTerms);
        cachedY[slot] = qy;
      }
      neighbors[dy + 1] = quadRows[slot].data();
    }
    for (int x = 0; x < result.width; ++x) {
      const int cx = mirrorGuideCenter(x, result.width);
      std::array<float, 3> m0{{0.f, 0.f, 0.f}}, m1 = m0, average = m0;
      float g0 = 0.f, g1 = 0.f, centerGreen = 0.f;
      for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
          const auto& q = neighbors[dy + 1][cx + dx];
          const std::array<float, 3> rgb{{q[0], .5f * (q[1] + q[2]), q[3]}};
          const float weight = (dx ? .25f : .5f) * (dy ? .25f : .5f);
          for (int c = 0; c < 3; ++c) {
            average[c] += rgb[c] * weight;
            m0[c] += rgb[c];
            m1[c] += rgb[c] * rgb[c];
          }
          if (!dx && !dy) centerGreen = rgb[1];
          g0 += q[1] + q[2];
          g1 += q[1] * q[1] + q[2] * q[2];
        }
      std::array<float, 3> variance;
      for (int c = 0; c < 3; ++c) {
        m0[c] /= 9.f;
        m1[c] /= 9.f;
        variance[c] = std::max(0.f, m1[c] - m0[c] * m0[c]);
      }
      g0 /= 18.f;
      g1 /= 18.f;
      const float greenVariance = std::max(0.f, g1 - g0 * g0);
      const float luma = .25f * average[0] + .5f * average[1] + .25f * average[2];
      // texture coordinate (.9*luma+.05)*10-.5 = 9*luma; clamp before
      // integer conversion also handles guides above the LUT's signal range.
      const float lookup = std::max(0.f, std::min((.9f * luma + .05f) * 10.f - .5f, 9.f));
      const int ix = static_cast<int>(std::floor(lookup));
      const float f = lookup - ix;
      const float noiseVariance = 2.f * ((1.f - f) * greenLut[ix] +
                                      f * greenLut[std::min(ix + 1, 9)]);
      float referenceVariance;
      if (greenVariance > 3.f * noiseVariance) {
        average[1] = centerGreen;
        referenceVariance = -std::max(variance[1], greenVariance);
      } else {
        referenceVariance = (variance[0] + variance[1] + variance[2]) / 3.f;
      }
      if (centerGreen >= greenClipping) average = {{10000.f, 10000.f, 10000.f}};
      const std::size_t p = static_cast<std::size_t>(y) * result.width + x;
      for (int c = 0; c < 3; ++c) {
        result.rgb[3 * p + c] = halfStorage(average[c]);
        if (!std::isfinite(result.rgb[3 * p + c])) { row_error[y] = 1; return; }
      }
      result.variance[p] = halfStorage(referenceVariance * 1024.f);
      if (!std::isfinite(result.variance[p])) { row_error[y] = 2; return; }
    }
  }
  });
  for (uint8_t code : row_error) {
    if (code) return fail(error, code == 1 ? "guide RGB binary16 overflow" : "guide variance binary16 overflow");
  }
  *output = std::move(result);
  return true;
}

bool highlightGenerateGuides(const HighlightRawView& base,
                             const HighlightRawView& short_frame,
                             const std::array<float, 3>& wb,
                             float refExposureScale, float shortExposureScale,
                             const HighlightNoise& base_noise,
                             const HighlightNoise& short_noise,
                             HighlightGuideSet* output, std::string* error) {
  if (error) error->clear();
  if (!output) return fail(error, "guide-set output is null");
  *output = HighlightGuideSet{};
  if (base.width != short_frame.width || base.height != short_frame.height ||
      base.cfaLut != short_frame.cfaLut)
    return fail(error, "base and short must have matching RAW size and CFA");
  HighlightGuideSet result;
  if (!highlightGenerateGuide(base, wb, refExposureScale, base_noise, &result.base, error) ||
      !highlightGenerateGuide(short_frame, wb, 1.f, short_noise, &result.short_unscaled, error) ||
      !highlightGenerateGuide(short_frame, wb, shortExposureScale, short_noise,
                              &result.short_normalized, error)) return false;
  result.refExposureScale = refExposureScale;
  result.shortExposureScale = shortExposureScale;
  const std::size_t n = result.base.variance.size();
  result.native_clipping.resize(n);
  result.highlight.resize(n);
  const auto levels = clippingLevels(base);
  parallelRows(result.base.height, [&](int row0, int row1) {
  for (int y = row0; y < row1; ++y)
    for (int x = 0; x < result.base.width; ++x) {
      const std::size_t p = static_cast<std::size_t>(y) * result.base.width + x;
      const bool clipped = footprintClipped(base, x, y, levels);
      result.native_clipping[p] = clipped ? 255 : 0;
      float maximum = 0.f;
      for (int c = 0; c < 3; ++c)
        maximum = std::max(maximum, quantizeBase(result.base.rgb[3 * p + c]));
      result.highlight[p] = clipped ? 255 : storeR8((maximum - kClip) / (1.f - kClip));
    }
  });
  *output = std::move(result);
  return true;
}

bool highlightAssessMasks(const HighlightRawView& short_frame,
                          const HighlightGuideSet& guides,
                          const HighlightFlowView& corrected_flow,
                          bool hasSupport, HighlightMaskAssessment* output,
                          std::string* error) {
  if (error) error->clear();
  if (!output) return fail(error, "assessment output is null");
  *output = HighlightMaskAssessment{};
  if (!validRaw(short_frame, error)) return false;
  const int w = short_frame.width / 2 + short_frame.width % 2;
  const int h = short_frame.height / 2 + short_frame.height % 2;
  const std::size_t n = static_cast<std::size_t>(w) * h;
  if (!validGuide(guides.base, w, h) || !validGuide(guides.short_unscaled, w, h) ||
      !validGuide(guides.short_normalized, w, h) || guides.highlight.size() != n ||
      guides.native_clipping.size() != n || !std::isfinite(guides.shortExposureScale) ||
      guides.shortExposureScale <= 0.f || !std::isfinite(guides.refExposureScale) ||
      guides.refExposureScale <= 0.f)
    return fail(error, "guide set shape, calibration or finite samples invalid");
  if (hasSupport && !corrected_flow.xy)
    return fail(error, "supported assessment requires corrected flow");
  if (corrected_flow.xy) {
    std::size_t flowCount;
    if (!dimensions(corrected_flow.width, corrected_flow.height, &flowCount) ||
        corrected_flow.strideRaw <= 0 || corrected_flow.strideRaw % 2 != 0 ||
        corrected_flow.width != (w - 1) / (corrected_flow.strideRaw / 2) + 1 ||
        corrected_flow.height != (h - 1) / (corrected_flow.strideRaw / 2) + 1)
      return fail(error, "flow grid must cover guide with an even positive RAW tile stride");
    for (std::size_t p = 0; p < flowCount * 2; ++p)
      if (!std::isfinite(corrected_flow.xy[p])) return fail(error, "flow contains nonfinite displacement");
  }
  HighlightMaskAssessment result;
  result.has_support = hasSupport;
  result.adjusted.resize(n);
  result.inpainting.resize(n);
  result.short_clipping.resize(n);
  result.normalized_error.resize(n);
  const auto levels = clippingLevels(short_frame);
  std::vector<uint8_t> row_error(h, 0);
  std::vector<size_t> row_clipped(h, 0), row_short_clipped(h, 0);
  parallelRows(h, [&](int row0, int row1) {
  for (int y = row0; y < row1; ++y)
    for (int x = 0; x < w; ++x) {
      const std::size_t p = static_cast<std::size_t>(y) * w + x;
      float du = 0.f, dv = 0.f;
      if (corrected_flow.xy) {
        const int tx = x / (corrected_flow.strideRaw / 2);
        const int ty = y / (corrected_flow.strideRaw / 2);
        const std::size_t tile = static_cast<std::size_t>(ty) * corrected_flow.width + tx;
        // PC ConvertAlignment stores normalized XY in RGBA16F before use.
        du = halfStorage((corrected_flow.xy[2 * tile] * .5f) / w);
        dv = halfStorage((corrected_flow.xy[2 * tile + 1] * .5f) / h);
        if (!std::isfinite(du) || !std::isfinite(dv)) { row_error[y] = 1; return; }
      }
      const auto shortRgb = sampleMirrored(guides.short_unscaled,
                                           (x + .5f) / w + du, (y + .5f) / h + dv);
      std::array<float, 3> base8;
      float errorSquared = 0.f;
      for (int c = 0; c < 3; ++c) {
        const float base = quantizeBase(guides.base.rgb[3 * p + c]);
        const float gained = std::floor(65535.f *
                       unit(unit(shortRgb[c]) * guides.shortExposureScale)) / 65535.f;
        const float difference = std::min(gained - base, 0.f);
        errorSquared += difference * difference;
        base8[c] = std::round(unit(base) * 255.f) / 255.f;
      }
      const float normalizedError = std::sqrt(errorSquared);
      result.normalized_error[p] = normalizedError;
      const float highlight = guides.highlight[p] / 255.f;
      const float fallback = highlight > 0.f && normalizedError >= kErrorThreshold
                           ? std::floor(normalizedError * 255.f) / 255.f : 0.f;
      result.adjusted[p] = storeR8(std::floor((1.f - fallback) * highlight * 255.f) / 255.f);
      const float smallest = std::min(base8[0], std::min(base8[1], base8[2]));
      const float largest = std::max(base8[0], std::max(base8[1], base8[2]));
      const float middle = base8[0] + base8[1] + base8[2] - smallest - largest;
      result.inpainting[p] = fallback > 0.f && (highlight >= 1.f ||
                       (smallest >= kInpaintMinimum && middle >= kClip)) ? 255 : 0;
      const int ax = anchorEven(x + du * w, w - 1);
      const int ay = anchorEven(y + dv * h, h - 1);
      result.short_clipping[p] = footprintClipped(short_frame, ax, ay, levels) ? 255 : 0;
      if (guides.highlight[p]) {
        ++row_clipped[y];
        if (result.short_clipping[p]) ++row_short_clipped[y];
      }
    }
  });
  for (int y = 0; y < h; ++y) {
    if (row_error[y]) return fail(error, "converted flow binary16 overflow");
    result.clipped_pixels += row_clipped[y];
    result.short_clipped_highlight_pixels += row_short_clipped[y];
  }
  result.clipped_ratio = static_cast<float>(result.clipped_pixels) / n;
  result.short_clipping_overlap = result.clipped_pixels
      ? static_cast<float>(result.short_clipped_highlight_pixels) / result.clipped_pixels : 0.f;
  result.largest_inpainting_area = largestHole(result.inpainting, w, h);
  result.status = !result.clipped_pixels ? HighlightAdmission::NoHighlights
                : !hasSupport ? HighlightAdmission::NoAlignmentSupport
                : result.largest_inpainting_area >= kMaximumHole
                    ? HighlightAdmission::LargeInpaintingHole : HighlightAdmission::Accepted;
  *output = std::move(result);
  return true;
}

bool highlightBuildMergeMasks(const std::vector<uint8_t>& adjusted,
                              int sw, int sh, int rawW, int rawH, bool accepted,
                              std::vector<uint8_t>* selected,
                              std::vector<uint8_t>* linear, std::string* error) {
  if (error) error->clear();
  if (!selected || !linear || selected == linear || selected == &adjusted || linear == &adjusted)
    return fail(error, "merge-mask outputs must be nonnull, distinct and not alias adjusted");
  selected->clear();
  linear->clear();
  std::size_t sourceCount, destinationCount;
  if (rawW < 8 || rawH < 8 || sw != rawW / 2 + rawW % 2 ||
      sh != rawH / 2 + rawH % 2 || !dimensions(sw, sh, &sourceCount) ||
      adjusted.size() != sourceCount || !dimensions(rawW / 4, rawH / 4, &destinationCount))
    return fail(error, "merge masks require ceil RAW/2 source and valid floor RAW/4 destination");
  const int mw = rawW / 4, mh = rawH / 4;
  std::vector<uint8_t> result(destinationCount, 0), kernel(destinationCount, 0);
  if (accepted) {
    for (int y = 0; y < mh; ++y)
      for (int x = 0; x < mw; ++x) {
        // Normalized destination pixel center -> GL_LINEAR source texel space.
        // For odd extents this is NOT the average of a fixed 2x2 source block.
        const float sx = (x + .5f) * sw / mw - .5f;
        const float sy = (y + .5f) * sh / mh - .5f;
        const int ix = static_cast<int>(std::floor(sx));
        const int iy = static_cast<int>(std::floor(sy));
        const float fx = sx - ix, fy = sy - iy;
        const auto at = [&](int xx, int yy) {
          return adjusted[static_cast<std::size_t>(bounded(yy, sh - 1)) * sw +
                          bounded(xx, sw - 1)] / 255.f;
        };
        const float top = at(ix, iy) * (1.f - fx) + at(ix + 1, iy) * fx;
        const float bottom = at(ix, iy + 1) * (1.f - fx) + at(ix + 1, iy + 1) * fx;
        const float mask = top * (1.f - fy) + bottom * fy;
        // MaskRejectionWithClippedHighlights writes reference acceptance to R8;
        // complement AFTER that storage quantization, not round(M*255) directly.
        const uint8_t reference = storeR8(1.f - mask);
        result[static_cast<std::size_t>(y) * mw + x] = static_cast<uint8_t>(255 - reference);
      }
    for (int y = 0; y < mh; ++y)
      for (int x = 0; x < mw; ++x) {
        uint8_t minimum = 255, maximum = 0;
        for (int dy = -1; dy <= 1; ++dy)
          for (int dx = -1; dx <= 1; ++dx) {
            const uint8_t value = result[static_cast<std::size_t>(bounded(y + dy, mh - 1)) *
                                         mw + bounded(x + dx, mw - 1)];
            minimum = std::min(minimum, value);
            maximum = std::max(maximum, value);
          }
        kernel[static_cast<std::size_t>(y) * mw + x] = maximum != minimum ? 255 : 0;
      }
  }
  *selected = std::move(result);
  *linear = std::move(kernel);
  return true;
}

const char* highlightAdmissionName(HighlightAdmission status) {
  switch (status) {
    case HighlightAdmission::InvalidInput: return "invalid_input";
    case HighlightAdmission::NoHighlights: return "no_clipped_pixels";
    case HighlightAdmission::NoAlignmentSupport: return "no_alignment_support";
    case HighlightAdmission::LargeInpaintingHole: return "large_hole_needing_inpainting";
    case HighlightAdmission::Accepted: return "none";
  }
  return "invalid_input";
}
}  // namespace filmkit
