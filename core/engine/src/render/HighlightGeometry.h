#pragma once

#include "Align.h"

#include <cstdint>
#include <limits>
#include <vector>

namespace filmkit {

// Coordinates are in the (normally RAW/2) RGB guide. Input/output flow remains in
// the caller's domain: for MfnrAlignOut::off use flow_to_guide=0.5, tile_stride=64.
// For PC's quad-domain flow use flow_to_guide=1 and its actual tile stride (8).
struct HighlightGeometryGrid {
  int width = 0, height = 0;
  int grid_width = 0, grid_height = 0;
  int tile_stride = kMfnrTile;
  float flow_to_guide = 0.5f;
};

struct HighlightBidirectionalFlow {
  bool valid = false;  // false: invalid pyramid/grid; neither flow may be used.
  MfnrAlignOut forward;  // base p -> short p + forward[p]
  MfnrAlignOut reverse;  // short q -> base q + reverse[q], NOT -forward[q]
};

// Reuses Align without changing its solver. Validates all pyramid buffers before
// entering it (Align itself assumes matching, nonempty levels). tile_stride is
// measured at RAW/2 even when allow_l1=false; use 8 for PC's eight-quad grid.
// This reuses the existing HDR+ SAD solver, NOT PC's LK solver: matching grid and
// admission semantics do not imply solver-quality equivalence. No GL dependency.
HighlightBidirectionalFlow highlightAlignBidirectional(
    const MfnrPyramid& base, const MfnrPyramid& short_frame,
    int grid_width, int grid_height, bool allow_l1 = true,
    int tile_stride = kMfnrTile);

// Same four observables as PC bentoCollarSupport RGBA. An invalid/unobservable
// mapping has confidence=0 and fb_error=+inf (never a fabricated perfect cycle).
struct HighlightTileSupport {
  float confidence = 0.f;
  float fb_error = std::numeric_limits<float>::infinity();  // guide pixels
  float valid_fraction = 0.f;
  float clipped_fraction = 0.f;
};

// CPU reference of PC GlesMgcRawSpatialShaders.bentoCollarSupport: full vector
// forward/backward cycle at the mapped reverse tile; 16 spatial RGB observations;
// weighted RGB residual; unclipped-reference mask; continuous FB/agreement weight.
// base_rgb/short_rgb are tightly packed linear, identically exposure/WB-normalized
// RGB guides, NOT grayscale/sqrt alignment pyramids. mask has width*height bytes,
// nonzero means reference highlight (derive from all native CFA phases before WB).
// RGB short samples use bilinear/clamp-to-edge like GL_LINEAR. Nonfinite guide
// observations are excluded fail-closed. Returns false/clears output on bad shape.
// Flow vectors have 2*grid_width*grid_height entries, in caller units.
bool highlightObservableSupport(
    const HighlightGeometryGrid& grid,
    const std::vector<float>& base_rgb, const std::vector<float>& short_rgb,
    const std::vector<uint8_t>& highlight_mask,
    const std::vector<float>& forward, const std::vector<float>& reverse,
    std::vector<HighlightTileSupport>* output);

enum class HighlightGeometryStatus {
  InvalidInput,
  NoObservableSupport,  // highlight exists, fewer than four supported exterior tiles
  NoHighlights,         // successful no-op even if frame has no supported tiles
  Resolved
};

struct HighlightGeometryResult {
  HighlightGeometryStatus status = HighlightGeometryStatus::InvalidInput;
  // Empty on either failure; the caller MUST reject short highlight injection.
  // Never interpret failure as permission to use the original plateau flow.
  std::vector<float> flow;
  int regions = 0, collar_regions = 0, global_regions = 0;
  int highlight_tiles = 0, supported_tiles = 0;
  bool usable() const {
    return status == HighlightGeometryStatus::Resolved ||
           status == HighlightGeometryStatus::NoHighlights;
  }
};

// Direct CPU port of PC BentoRegionAdmission (177-line reference): group actual
// highlight tiles (8-connected), not all unsupported tiles. Exclude ALL highlight
// tiles from donors even if the 4x4 support samples missed a clipped pixel. A
// radius-4 unique collar with >=4 donors uses confidence/distance^2 interpolation;
// otherwise >=4 global donors supply a confidence-weighted per-axis median.
// Non-highlight flow is preserved exactly, including unobservable exterior flow.
// GPU-produced support can also enter here after unpacking its confidence channel.
HighlightGeometryResult highlightResolveGeometry(
    const HighlightGeometryGrid& grid,
    const std::vector<uint8_t>& highlight_mask,
    const std::vector<float>& forward,
    const std::vector<HighlightTileSupport>& support);

}  // namespace filmkit
