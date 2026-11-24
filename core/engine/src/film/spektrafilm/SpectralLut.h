#pragma once

#include "ParallelPixels.h"
#include "Spectral.h"

#include <cmath>
#include <limits>
#include <vector>

namespace filmkit::spektrafilm {

// Upstream's stage-local log-light LUT: PCHIP on each axis, linear cross-axis
// slope interpolation, and a clamp to the eight corners. Never clamp input
// densities: the caller uses the complete spectral model outside the lattice.
class SpectralLut {
public:
    bool prepare(const SpectralProjection& projection, SpectralBackend backend,
                 Triplet lo, Triplet hi, std::size_t side, std::size_t workers) {
        if (side < 3 || side > 65) return false;
        side_ = side;
        lo_ = lo;
        hi_ = hi;
        for (int c = 0; c < 3; ++c) {
            if (!std::isfinite(lo[c]) || !std::isfinite(hi[c]) || hi[c] <= lo[c]) return false;
            scale_[c] = (side - 1) / (hi[c] - lo[c]);
            if (!(scale_[c] > 0.0) || !std::isfinite(scale_[c])) return false;
        }
        const auto count = side * side * side;
        nodes_.resize(count);
        if (!parallelPixels(count, workers, [&](std::size_t begin, std::size_t size) {
            std::array<Triplet, 64> block;
            for (std::size_t i = begin; i < begin + size;) {
                const auto n = std::min(block.size(), begin + size - i);
                const auto span = std::span(block).first(n);
                for (std::size_t j = 0; j < n; ++j) {
                    const auto k = i + j;
                    span[j] = {point(0, k / (side * side)), point(1, k / side % side), point(2, k % side)};
                }
                if (!projectLogSpectral(projection, span, span, backend)) return false;
                for (std::size_t j = 0; j < n; ++j) {
                    for (const auto value : span[j]) if (!std::isfinite(value)) return false;
                    nodes_[i + j].value = span[j];
                }
                i += n;
            }
            return true;
        }, 512)) return false;
        return parallelPixels(count, workers, [&](std::size_t begin, std::size_t size) {
            const std::array strides{side * side, side, std::size_t{1}};
            for (auto i = begin; i < begin + size; ++i) {
                for (int axis = 0; axis < 3; ++axis) {
                    const auto stride = strides[axis];
                    const auto coordinate = i / stride % side;
                    for (int c = 0; c < 3; ++c) {
                        double m;
                        if (coordinate == 0) {
                            const double near = value(i + stride, c) - value(i, c);
                            const double far = value(i + 2 * stride, c) - value(i + stride, c);
                            m = endpoint(near, far);
                        } else if (coordinate == side - 1) {
                            const double near = value(i, c) - value(i - stride, c);
                            const double far = value(i - stride, c) - value(i - 2 * stride, c);
                            m = endpoint(near, far);
                        } else {
                            const double left = value(i, c) - value(i - stride, c);
                            const double right = value(i + stride, c) - value(i, c);
                            const double product = left * right;
                            m = product <= 0.0 ? 0.0 : 2.0 * product / (left + right);
                        }
                        nodes_[i].slope[axis][c] = m;
                    }
                }
            }
            return true;
        }, 512);
    }

    bool sample(const Triplet& density, Triplet& logLight) const {
        std::array<Weights, 3> weights;
        std::array<std::size_t, 3> lower;
        for (int c = 0; c < 3; ++c) {
            if (!(density[c] >= lo_[c] && density[c] <= hi_[c])) return false;
            const double x = (density[c] - lo_[c]) * scale_[c];
            lower[c] = std::min(static_cast<std::size_t>(x), side_ - 2);
            const double t = std::min(x - lower[c], 1.0);
            const double t2 = t * t, t3 = t2 * t;
            const double h = 3.0 * t2 - 2.0 * t3;
            weights[c] = {{1.0 - h, h}, {t3 - 2.0 * t2 + t, t3 - t2}, {1.0 - t, t}};
        }
        const auto* base = &nodes_[(lower[0] * side_ + lower[1]) * side_ + lower[2]];
        Triplet out{}, min = base->value, max = base->value;
        const auto& [x, y, z] = weights;
        for (std::size_t i = 0; i < 2; ++i) {
            for (std::size_t j = 0; j < 2; ++j) {
                for (std::size_t k = 0; k < 2; ++k) {
                    const auto& node = base[(i * side_ + j) * side_ + k];
                    const double w = x.h[i] * y.h[j] * z.h[k];
                    const double dx = x.d[i] * y.h[j] * z.h[k];
                    const double dy = x.l[i] * y.d[j] * z.h[k];
                    const double dz = x.l[i] * y.l[j] * z.d[k];
                    for (int c = 0; c < 3; ++c) {
                        out[c] += w * node.value[c] + dx * node.slope[0][c] +
                                  dy * node.slope[1][c] + dz * node.slope[2][c];
                        min[c] = std::min(min[c], node.value[c]);
                        max[c] = std::max(max[c], node.value[c]);
                    }
                }
            }
        }
        for (int c = 0; c < 3; ++c) logLight[c] = std::clamp(out[c], min[c], max[c]);
        return true;
    }

private:
    struct Node { Triplet value; std::array<Triplet, 3> slope; };
    struct Weights { double h[2]; double d[2]; double l[2]; };
    std::size_t side_ = 0;
    Triplet lo_, hi_, scale_;
    std::vector<Node> nodes_;

    static double endpoint(double near, double far) {
        const double slope = 0.5 * (3.0 * near - far);
        if (slope * near <= 0.0) return 0.0;
        if (near * far < 0.0 && std::abs(slope) > std::abs(3.0 * near)) return 3.0 * near;
        return slope;
    }
    double value(std::size_t i, int c) const { return nodes_[i].value[c]; }
    double point(int c, std::size_t i) const {
        return std::lerp(lo_[c], hi_[c], static_cast<double>(i) / (side_ - 1));
    }
};

}  // namespace filmkit::spektrafilm
