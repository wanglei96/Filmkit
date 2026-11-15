#pragma once

#include <array>
#include <cstdint>
#include <vector>

namespace filmkit {

// A CPU reference/host boundary, not a replacement for PC's noise-model AOT.
// All public results are in the actual image's units. Native Bayer merge output
// already includes WB and reference exposure: do not apply either a second time.
struct LocalNoiseValue {
  double signal = 0;
  double variance = 0;
  double shot = 0;
  double read = 0;
};

// source identifies a PHYSICAL observation (frame + native photosite), not a
// stencil index. Mirror/clamp duplicates must use the same id. Inputs sharing an
// id must have identical signal, native model, and gain within one output.
struct LocalNoiseTap {
  uint64_t source = 0;
  double signal = 0;  // native sensor-normalized, before gain
  double shot = 0;
  double read = 0;
  double gain = 1;    // reference exposure scale * WB
  double weight = 0;  // complete unnormalized spatial * temporal * global weight
};

bool localNoiseMerge(const std::vector<LocalNoiseTap>& taps,
                     LocalNoiseValue* output);
// Exact frozen-linear-weight covariance for two outputs sharing source samples.
// This is information NOT recoverable from the four marginal MRT channels.
bool localNoiseCrossCovariance(const std::vector<LocalNoiseTap>& left,
                              const std::vector<LocalNoiseTap>& right,
                              double* covariance);

struct LocalNoiseObservation {
  LocalNoiseValue value;
  int phase = 0;            // native x + 2*y, NOT canonical RGGB
  double sample_weight = 1; // actual represented pixel area (edge-aware)
};
struct LocalNoiseRgbModel {
  std::array<double, 3> variance{};
  std::array<double, 3> shot{};
  std::array<double, 3> read{};
  std::array<double, 3> signal{};
  std::array<double, 3> support{};
};
// Summarizes GPU V/A/B with coverage weighting, including BOTH green phases.
// This is an arithmetic reduction of marginal models, not another image merge:
// sample_weight is NOT squared. Preserves actual V separately from A*mean(x)+B.
// Rejects missing channels, non-finite/negative models, and invalid CFA layouts.
bool localNoiseReduce(const std::vector<LocalNoiseObservation>& observations,
                      uint8_t native_cfa_lut, LocalNoiseRgbModel* output);

using LocalNoiseCovariance = std::array<double, 9>; // row-major symmetric RGB
// Standard linear noise propagation: D_lsc Sigma D_lsc; then C Sigma C^T.
// Camera covariance is the demosaic/model-propagation contract, NOT inferred
// by copying one mosaic variance into RGB. Existing cross-channel terms survive.
bool localNoiseToD4(const LocalNoiseCovariance& camera_covariance,
                    const std::array<double, 3>& lsc,
                    const std::array<double, 9>& row_major_ccm,
                    LocalNoiseCovariance* output);
// Sigma_delta = Sigma_p + Sigma_q - Cov(p,q) - Cov(p,q)^T.
LocalNoiseCovariance localNoiseDifferenceCovariance(
    const LocalNoiseCovariance& p, const LocalNoiseCovariance& q,
    const LocalNoiseCovariance& cross);
double localNoiseProjectedVariance(const LocalNoiseCovariance& covariance,
                                   const std::array<double, 3>& direction);
// For frozen scalar coefficients and arbitrary inter-sample correlation,
// Cov(sum a_i X_i) <= (sum |a_i|) * sum |a_i| Sigma_i in PSD order.
// Convex normalized weights reduce to sum a_i Sigma_i. Handles guided fit's
// negative coefficients as well as Gaussian/PC3D weights. NOT an exact variance
// or a bound on noise-dependent coefficient changes; linearize those separately.
bool localNoiseLinearCovarianceBound(
    const std::vector<LocalNoiseCovariance>& covariance,
    const std::vector<double>& coefficients, LocalNoiseCovariance* output);

struct LocalNoiseCorrelation {
  std::array<double, 128> spectrum{};
  double diagnostic_ratio = 0;
  double outer_tap = 0;
  double center_tap = 1;
};
// Port of MgcSpatialDenoiseModel.fromGreenDiagnostics. Requires the REAL
// source-weight cross diagnostics; V/A/B or rejection masks cannot supply them.
bool localNoiseCorrelationFromDiagnostics(
    const std::array<double, 3>& diagonal0,
    const std::array<double, 3>& diagonal1, LocalNoiseCorrelation* output);

// Destination-space pixel -> native source pixel; rotate BOTH image/noise and
// LSC lookup coordinates through this exact mapping. Dimensions are native.
bool localNoiseNativeCoordinate(int x, int y, int native_width,
                                int native_height, int clockwise_degrees,
                                int* source_x, int* source_y);

}  // namespace filmkit
