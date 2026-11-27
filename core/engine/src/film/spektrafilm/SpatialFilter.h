#pragma once

#include "Spectral.h"

#include <cstddef>
#include <span>

namespace filmkit::spektrafilm {

// Short scan kernels: filter RGB rows directly and write unsharp results to
// separate planes so adjacent workers can still read the source halo.
bool unsharpRgbFir(std::span<const Triplet> input, const std::array<std::span<double>, 3>& output,
                   std::size_t width, std::size_t height, double sigma, double amount,
                   std::size_t workers);

// For finite sigma, the original Gaussian kernel is exactly the identity
// when the short-kernel radius rounds to zero.
inline bool gaussianBlurIsIdentity(double sigma) noexcept {
    return sigma <= 0.0 || (sigma < 3.0 && 3.0 * sigma + 0.5 < 1.0);
}

// Filters one row-major plane. All spans must be distinct and exactly width * height.
// The scratch plane is reusable between channels and between mixture components.
bool gaussianBlur(std::span<const double> input, std::span<double> output,
                  std::span<double> scratch, std::size_t width, std::size_t height,
                  double sigma, std::size_t workers = 1);

// Writes weight * blur, or adds it to a previous tap. The accumulation plane
// must also be distinct from the input and both work planes.
bool gaussianBlurWeighted(std::span<const double> input, std::span<double> output,
                          std::span<double> scratch, std::span<double> accumulation,
                          double weight, bool add, std::size_t width, std::size_t height,
                          double sigma, std::size_t workers);

struct GaussianTap { double sigma, weight; };

// Evaluates all taps in order, keeping adjacent wide kernels together when possible.
bool gaussianBlurMixture(std::span<const double> input, std::span<double> output,
                         std::span<double> scratch, std::span<double> accumulation,
                         std::span<const GaussianTap> taps, std::size_t width, std::size_t height,
                         std::size_t workers);

}  // namespace filmkit::spektrafilm
