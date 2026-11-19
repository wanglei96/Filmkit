#include "film/spektrafilm/SpatialFilter.h"

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

using namespace filmkit::spektrafilm;

int main() {
    const std::array<std::array<std::size_t, 2>, 6> sizes{{{1, 1}, {3, 5}, {35, 17}, {151, 129}, {257, 65}, {65, 257}}};
    constexpr std::array sigmas{0.0, 0.16, 0.5, 2.33143, 2.999, 3.0, 12.496, 35.522, 64.543};
    std::size_t checked = 0;
    for (const auto& [w, h] : sizes) {
        std::vector<double> input(w * h), reference(w * h), output(w * h), scratch(w * h);
        for (std::size_t i = 0; i < input.size(); ++i) {
            input[i] = 0.5 * std::sin(static_cast<double>(i) * 0.17) + static_cast<double>(i % 73) * 0.03;
        }
        input.front() = 8.0;
        input.back() = -0.25;
        for (double sigma : sigmas) {
            if (!gaussianBlur(input, reference, scratch, w, h, sigma, 1)) return 2;
            for (std::size_t workers : {4, 8}) {
                if (!gaussianBlur(input, output, scratch, w, h, sigma, workers)) return 2;
                for (std::size_t i = 0; i < output.size(); ++i) {
                    if (!std::isfinite(output[i]) || std::bit_cast<std::uint64_t>(output[i]) !=
                        std::bit_cast<std::uint64_t>(reference[i])) {
                        std::cerr << "filter mismatch " << w << 'x' << h << " sigma=" << sigma
                                  << " workers=" << workers << " pixel=" << i << '\n';
                        return 1;
                    }
                    ++checked;
                }
            }
        }
        if (gaussianBlur(input, output, scratch, w, h, 1.0, 0) ||
            gaussianBlur(input, output, scratch, w, h, 1.0, 9)) return 1;
    }
    std::cout << "filter_parallel_bitwise_values=" << checked << " failed=0\n";
}
