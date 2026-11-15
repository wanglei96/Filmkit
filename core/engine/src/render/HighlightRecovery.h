#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace filmkit {

// All RAW coordinates/flow displacements are native mosaic pixels. stride is a
// count of uint16_t elements, NOT bytes. black_phase/noise are native (x&1)+2*(y&1)
// order. cfaLut stores 2 bits per phase: R=0, G=1, B=2 (same as FrameRenderer).
struct HighlightRawView {
  const uint16_t* data = nullptr;
  int width = 0, height = 0;
  std::ptrdiff_t stride = 0;
  std::array<float, 4> black_phase{{0.f, 0.f, 0.f, 0.f}};
  float white = 65535.f;
  unsigned cfaLut = 0x94;  // RGGB: {R,G,G,B}
};

// Camera2/DNG normalized sensor variance = shot * signal + read, BEFORE WB,
// exposure normalization and user denoise-strength scaling. A known noiseless
// synthetic input may use zeros; production callers must provide their profile.
struct HighlightNoise {
  std::array<float, 4> shot{{0.f, 0.f, 0.f, 0.f}};
  std::array<float, 4> read{{0.f, 0.f, 0.f, 0.f}};
};

struct HighlightGuide {
  int width = 0, height = 0;  // ceil(RAW/2)
  std::vector<float> rgb;    // interleaved RGB, linear WB/exposure-scaled
  std::vector<float> variance;  // signed referenceVariance * 1024 (PC guide alpha)
};

struct HighlightGuideSet {
  HighlightGuide base;
  HighlightGuide short_unscaled;    // own exposure, scale=1, used by mask assessment
  HighlightGuide short_normalized;  // separately generated at shortExposureScale;
                                    // NOT short_unscaled * ratio (noise branch differs)
  std::vector<uint8_t> native_clipping;  // base native 3x3 quad clipping footprint
  std::vector<uint8_t> highlight;        // R8 max(native clip, quantized RGB ramp)
  float refExposureScale = 1.f;
  float shortExposureScale = 1.f;
};

// Exact PC guide kernel: canonical R/G1/G2/B, 3x3 moments, binomial RGB filter,
// 18-observation green variance vs the 10-texel current-row noise LUT, signed
// variance, clipping sentinel. LUT and guide use binary16 round-to-nearest-even
// storage just like PC RGBA16F. No grayscale substitute or hue neutralization.
// RAW >=8x8; standard four Bayer layouts only; malformed/nonfinite input fails
// closed and clears output. WB is RGB (both native greens use wb[1]).
bool highlightGenerateGuide(const HighlightRawView& raw,
                            const std::array<float, 3>& wb,
                            float exposureScale, const HighlightNoise& noise,
                            HighlightGuide* output, std::string* error = nullptr);

// Split preparation from geometry: call this first, pass base.rgb,
// short_normalized.rgb and highlight to HighlightGeometry, then assess with its
// corrected flow and hasSupport. Exposure scales are calibration gains relative
// to the same output domain (normally ref=1, short=exposure ratio >1).
bool highlightGenerateGuides(const HighlightRawView& base,
                             const HighlightRawView& short_frame,
                             const std::array<float, 3>& wb,
                             float refExposureScale, float shortExposureScale,
                             const HighlightNoise& base_noise,
                             const HighlightNoise& short_noise,
                             HighlightGuideSet* output,
                             std::string* error = nullptr);

struct HighlightFlowView {
  const float* xy = nullptr;  // 2*width*height floats, tightly packed (dx,dy)
  int width = 0, height = 0;
  int strideRaw = 0;  // tile spacing in mosaic pixels, e.g. PC=16, Align=128
  // PC Spatial ConvertAlignment expands each tile into a constant full-resolution
  // flow texture with NEAREST filtering. This is deliberately NOT tile-center
  // bilinear flow (only the warped short RGB guide uses mirrored bilinear).
};

enum class HighlightAdmission {
  InvalidInput,
  NoHighlights,       // successful no-op, accepted=false, regardless of support
  NoAlignmentSupport, // reject entire short frame
  LargeInpaintingHole,// reject entire short frame, largest 8-connected area >=80
  Accepted
};

struct HighlightMaskAssessment {
  HighlightAdmission status = HighlightAdmission::InvalidInput;
  std::vector<uint8_t> adjusted;   // PC R8 M; caller must honor whole-frame status
  std::vector<uint8_t> inpainting;// binary R8 hole mask, NOT scalar CFA inpainting
  std::vector<uint8_t> short_clipping; // native aligned 3x3 footprint; diagnostic ONLY
  std::vector<float> normalized_error; // length(min(gainedShort-base,0)) in RGB
  std::size_t clipped_pixels = 0;
  std::size_t short_clipped_highlight_pixels = 0;
  std::size_t largest_inpainting_area = 0;
  float clipped_ratio = 0.f;
  float short_clipping_overlap = 0.f;
  bool has_support = false;
  bool accepted() const { return status == HighlightAdmission::Accepted; }
};

// PC GenerateHighlightMask + AdjustHighlightMaskAndGenerateInpaintingMask +
// alignedRawClipping + assessBentoMasks. Mask constants: ramp/clip=250/255,
// min RGB=128/255, one-sided full RGB error>=0.9, hole area>=80 rejects frame.
// Flow is corrected base->short mosaic displacement (never raw plateau flow on
// geometry failure). Null flow is allowed ONLY if hasSupport=false: identity is
// used for diagnostics and the frame can never be accepted. Short clipping does
// not zero M or reject a region. No RAW synthesis/inpaint is done by this API.
// RGB assessment follows PC U16 round(base), floor(gained short), R8 mask floor,
// mirrored-UV GL_LINEAR sampling, normalized binary16 converted flow, ties-even
// native clip footprint anchor. Returns false only for invalid input; status
// distinguishes valid rejection/no-highlight from acceptance.
bool highlightAssessMasks(const HighlightRawView& short_frame,
                          const HighlightGuideSet& guides,
                          const HighlightFlowView& corrected_flow,
                          bool hasSupport,
                          HighlightMaskAssessment* output,
                          std::string* error = nullptr);

// PC merge consumption: sample RAW/2 adjusted M with GL_LINEAR/CLAMP_TO_EDGE
// at RAW/4 destination pixel centers, write R8 reference weight (1-M), then
// complement that quantized byte back to selected-short weight. The linear
// kernel mask is the strict 3x3 local-min != local-max test on that R8 domain.
// sw/sh must equal ceil(rawW/2),ceil(rawH/2). Outputs have floor(rawW/4) *
// floor(rawH/4) bytes, matching PC MgcSpatialRejectionGeometry (NOT ceil RAW/4).
// A rejected frame yields all-zero selected/linear masks. Output vectors must
// be nonnull, distinct, and must not alias adjusted. Invalid input fails closed.
bool highlightBuildMergeMasks(const std::vector<uint8_t>& adjusted,
                              int sw, int sh, int rawW, int rawH, bool accepted,
                              std::vector<uint8_t>* selected,
                              std::vector<uint8_t>* linear,
                              std::string* error = nullptr);

const char* highlightAdmissionName(HighlightAdmission status);

}  // namespace filmkit
