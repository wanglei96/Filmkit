#pragma once

#include <cstddef>
#include <span>

namespace filmkit::spektrafilm {

// Filters one row-major plane. All spans must be distinct and exactly width * height.
// The scratch plane is reusable between channels and between mixture components.
bool gaussianBlur(std::span<const double> input, std::span<double> output,
                  std::span<double> scratch, std::size_t width, std::size_t height,
                  double sigma, std::size_t workers = 1);

}  // namespace filmkit::spektrafilm
