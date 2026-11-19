#include "film/spektrafilm/FilmExposure.h"
#include "spektrafilm-check-io.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <vector>

using namespace filmkit::spektrafilm;

namespace {
template <class Range>
void read(std::istream& in, Range& range) {
    const auto bytes = std::as_writable_bytes(std::span(range));
    if (!in.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) {
        throw std::runtime_error("truncated film exposure fixture");
    }
}

double maxError(std::span<const Triplet> actual, std::span<const Triplet> expected) {
    double result = 0.0;
    for (std::size_t i = 0; i < actual.size(); ++i) {
        for (std::size_t c = 0; c < 3; ++c) {
            if (!std::isfinite(actual[i][c]) || !std::isfinite(expected[i][c])) {
                throw std::runtime_error("nonfinite film exposure");
            }
            result = std::max(result, std::abs(actual[i][c] - expected[i][c]));
        }
    }
    return result;
}
}  // namespace

int main(int argc, char** argv) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(sizeof(Triplet) == 3 * sizeof(double));
    if (argc != 2 && argc != 3) return 2;
    try {
        std::ifstream in(argv[1], std::ios::binary);
        std::array<std::uint32_t, 3> header;
        read(in, header);
        const auto [cases, width, height] = header;
        const auto pixels = std::size_t(width) * height;
        if (!pixels || pixels > (argc == 3 ? 16000000 : 1000000) || cases != (argc == 3 ? 1 : 3)) return 2;
        std::vector<Triplet> rgb(pixels), raw(pixels), expected(pixels);
        read(in, rgb);
        bool failed = false;
        for (std::size_t i = 0; i < cases; ++i) {
            std::array<std::uint32_t, 4> flags;
            read(in, flags);
            const auto [side, active, bounces, renormalize] = flags;
            if (side < 2 || side > 1024 || bounces > 32) return 2;
            std::array<double, 26> p;
            read(in, p);
            std::array<Triplet, 3> matrix;
            read(in, matrix);
            std::vector<Triplet> response(std::size_t(side) * side);
            read(in, response);
            if (!projectHanatosRaw(rgb, raw, matrix, response, side)) return 2;
            read(in, expected);
            const double rawError = maxError(raw, expected);
            FilmHalation h;
            h.active = active;
            h.bounces = bounces;
            h.renormalize = renormalize;
            h.scatterAmount = p[6];
            h.scatterScale = p[7];
            h.halationAmount = p[8];
            h.halationScale = p[9];
            h.bounceDecay = p[10];
            std::copy_n(p.begin() + 11, 3, h.scatterCoreUm.begin());
            std::copy_n(p.begin() + 14, 3, h.scatterTailUm.begin());
            std::copy_n(p.begin() + 17, 3, h.scatterTailWeight.begin());
            std::copy_n(p.begin() + 20, 3, h.halationStrength.begin());
            std::copy_n(p.begin() + 23, 3, h.halationFirstSigmaUm.begin());
            if (!boostFilmRaw(raw, p[1], {p[3], p[4], p[5]}) ||
                !finishFilmLogExposure(raw, width, height, p[0], p[2], h, 1.0)) return 2;
            read(in, expected);
            const double logError = maxError(raw, expected);
            std::cout << std::setprecision(10) << "case=" << i << " pixels=" << pixels
                      << " max_raw_error=" << rawError << " max_log_exposure_error=" << logError << '\n';
            failed |= std::max(rawError, logError) > 1e-10;
        }
        if (in.peek() != std::char_traits<char>::eof()) return 2;
        if (!failed && argc == 3) spektrafilm_check::save(argv[2], raw);
        return failed;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 2;
    }
}
