#include "film/spektrafilm/DirDevelopment.h"
#include "spektrafilm-check-io.h"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <sys/resource.h>

using namespace filmkit::spektrafilm;

namespace {
template <class T>
void read(std::istream& stream, std::span<T> values) {
    if (!stream.read(reinterpret_cast<char*>(values.data()), values.size_bytes())) {
        throw std::runtime_error("truncated DIR fixture");
    }
}
}  // namespace

int main(int argc, char** argv) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(sizeof(Triplet) == 3 * sizeof(double));
    if (argc != 2 && argc != 4) return 2;
    try {
        std::ifstream file(argv[1], std::ios::binary);
        std::array<std::uint32_t, 4> shape;
        read(file, std::span<std::uint32_t>(shape));
        const auto [width, height, knots, active] = shape;
        const auto count = std::uint64_t(width) * height;
        if (!count || count > 16000000 || knots < 2 || knots > 4096 || active > 1) return 2;
        std::array<double, 16> params;
        read(file, std::span<double>(params));
        const Triplet gamma{params[4], params[5], params[6]};
        DirCouplers dir;
        dir.active = active != 0;
        dir.diffusionUm = params[1];
        dir.tailUm = params[2];
        dir.tailWeight = params[3];
        for (std::size_t j = 0; j < 3; ++j) std::copy_n(params.begin() + 7 + 3 * j, 3, dir.inhibition[j].begin());
        std::vector<double> axis(knots);
        std::vector<Triplet> stock(knots), exposure(count), output(count);
        read(file, std::span<double>(axis));
        read(file, std::span<Triplet>(stock));
        read(file, std::span<Triplet>(exposure));
        if (argc == 4) spektrafilm_check::load(argv[2], exposure);
        const auto start = std::chrono::steady_clock::now();
        if (!developFilmBeforeGrain(exposure, output, width, height, params[0], axis, stock,
                                    gamma, EmulsionType::Negative, dir)) return 2;
        const std::chrono::duration<double> elapsed = std::chrono::steady_clock::now() - start;
        std::array<Triplet, 1024> expected;
        double maxError = 0.0;
        std::size_t failed = 0;
        constexpr double tolerance = 1e-10;
        for (std::size_t i = 0; i < output.size();) {
            const auto n = std::min(expected.size(), output.size() - i);
            read(file, std::span<Triplet>(expected.data(), n));
            for (std::size_t j = 0; j < n; ++j) {
                for (int c = 0; c < 3; ++c) {
                    const double actual = output[i + j][c];
                    const double ref = expected[j][c];
                    if (!std::isfinite(actual) || !std::isfinite(ref)) {
                        ++failed;
                    } else {
                        const double diff = std::abs(actual - ref);
                        maxError = std::max(maxError, diff);
                        failed += diff > tolerance;
                    }
                }
            }
            i += n;
        }
        if (file.peek() != std::char_traits<char>::eof()) return 2;
        if (!failed && argc == 4) spektrafilm_check::save(argv[3], output);
        rusage usage{};
        getrusage(RUSAGE_SELF, &usage);
#if defined(__APPLE__)
        usage.ru_maxrss /= 1024;
#endif
        std::cout << std::setprecision(10) << "pixels=" << count << " compute_s=" << elapsed.count()
                  << " max_abs_error=" << maxError << " failed=" << failed
                  << " peak_rss_kib=" << usage.ru_maxrss << '\n';
        return failed != 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
}
