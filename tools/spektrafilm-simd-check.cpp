#include "film/spektrafilm/OutputGamut.h"
#include "film/spektrafilm/Spectral.h"
#if defined(__aarch64__)
#include "film/spektrafilm/FixedPowerNeon.h"
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <vector>

namespace filmkit::spektrafilm {
bool scalarFinishScanSrgb(std::span<Triplet>, std::size_t, std::size_t, Triplet,
    double, double, double, bool, std::span<double>, std::span<double>, std::span<double>,
    std::size_t, std::size_t);
}

using namespace filmkit::spektrafilm;

namespace {
double maximum = 0;
std::size_t comparisons = 0, codesChanged = 0;

bool equal(double a, double b, double tolerance) {
    ++comparisons;
    if (a == b || (std::isnan(a) && std::isnan(b))) return true;
    if (!std::isfinite(a) || !std::isfinite(b)) return false;
    const double error = std::abs(a - b);
    maximum = std::max(maximum, error);
    for (double scale : {255.0, 1023.0}) {
        const auto code = [=](double x) { return std::floor(std::clamp(x, 0.0, 1.0) * scale + .5); };
        codesChanged += code(a) != code(b);
    }
    return error <= tolerance * std::max({1.0, std::abs(a), std::abs(b)});
}

bool spectral() {
    SpectralProjection p{};
    p.normalizer = 71;
    p.gain = {.9, 1.1, 1.3};
    p.preflash = {.001, .02, .003};
    for (std::size_t i = 0; i < p.bands.size(); ++i) {
        auto& band = p.bands[i];
        band.dyeDensity = {.1 + i * .003, .2 + i * .002, .3 + i * .001};
        band.baseDensity = .02;
        band.illuminant = .9 + i * .01;
        band.response = {.1 + i * .002, .2 + i * .001, .3 + i * .003};
    }
    constexpr double nan = std::numeric_limits<double>::quiet_NaN();
    constexpr double inf = std::numeric_limits<double>::infinity();
    const std::array<Triplet, 8> values{{{0, 0, 0}, {1, 2, 3}, {-1, .2, 4},
        {nan, 1, 2}, {inf, 0, 1}, {-inf, 1, 2}, {1000, 1000, 1000}, {-1000, -1000, -1000}}};
    for (int endpoint = 0; endpoint < 10; ++endpoint) {
        auto projection = p;
        auto& band = projection.bands[17];
        if (endpoint < 3) band.dyeDensity[endpoint] = nan;
        if (endpoint == 3) band.baseDensity = nan;
        if (endpoint == 4) band.illuminant = nan;
        if (endpoint == 5) { band.dyeDensity[0] = nan; band.response[1] = inf; }
        if (endpoint == 7) { band.illuminant = 0.0; band.response[2] = -1.0; }
        if (endpoint == 8) { band.illuminant = -0.0; band.response[2] = inf; }
        if (endpoint == 9) for (auto& item : projection.bands) item.illuminant = 0.0;
        for (std::size_t count : {0, 1, 2, 3, 4, 5, 7, 8, 9, 17, 16385}) {
            std::vector<Triplet> input(count), expected(count), actual(count);
            for (std::size_t i = 0; i < count; ++i) input[i] = values[i % values.size()];
            for (auto operation : {projectLogSpectral, projectSpectralLight}) {
                if (!operation(projection, input, expected, SpectralBackend::Scalar) ||
                    !operation(projection, input, actual, SpectralBackend::AdvSimd)) return false;
                for (std::size_t i = 0; i < count; ++i) for (int c = 0; c < 3; ++c)
                    if (!equal(expected[i][c], actual[i][c], 1e-12)) return false;
                actual = input;
                if (!operation(projection, actual, actual, SpectralBackend::AdvSimd)) return false;
                for (std::size_t i = 0; i < count; ++i) for (int c = 0; c < 3; ++c)
                    if (!equal(expected[i][c], actual[i][c], 1e-12)) return false;
            }
        }
    }
    return true;
}

bool cam16() {
    constexpr Triplet white{.9642119944211994, 1, .8251882845188288};
    constexpr double nan = std::numeric_limits<double>::quiet_NaN();
    constexpr double inf = std::numeric_limits<double>::infinity();
    constexpr std::array<double, 19> grid{-4, -1, -.1, -1e-10, -0.0, 0.0,
        1e-20, 1e-12, 1e-8, .001, .01, .1, .4, .7, 1, 2, 8, 64, 10000};
    std::vector<Triplet> samples;
    for (double x : grid) for (double y : grid) for (double z : grid) samples.push_back({x, y, z});
    samples.insert(samples.end(), {{nan, 1, 2}, {1, inf, 2}, {1, 2, -inf}});
    std::array<double, 3> plane{}, filtered{}, scratch{};
    for (const auto& value : samples) for (bool encoded : {false, true}) {
        // Mixed lanes and an odd tail exercise both the pair and scalar routes.
        std::array<Triplet, 3> expected{value, Triplet{.3, .2, .1}, value}, actual = expected;
        const bool scalar = scalarFinishScanSrgb(expected, 3, 1, white, 0, 0, 0, encoded,
                                                plane, filtered, scratch, 1, 1);
        const bool simd = finishScanSrgb(actual, 3, 1, white, 0, 0, 0, encoded,
                                       plane, filtered, scratch, 1, 1);
        if (scalar != simd) { std::cerr << "CAM16 status mismatch\n"; return false; }
        if (!scalar) continue;
        for (std::size_t i = 0; i < actual.size(); ++i) for (int c = 0; c < 3; ++c)
            if (!equal(expected[i][c], actual[i][c], 2e-8)) {
                std::cerr << "CAM16 mismatch " << value[0] << ' ' << value[1] << ' ' << value[2]
                          << " expected=" << expected[i][c] << " actual=" << actual[i][c] << '\n';
                return false;
            }
    }
    return true;
}

#if defined(__aarch64__)
template <class Exponent>
bool fixedPowerCheck(Exponent power) {
    std::mt19937_64 rng(761);
    double worst = 0;
    const auto compare = [&](double x, double y) {
        const auto result = fixedPower(float64x2_t{x, y}, power);
        for (int i = 0; i < 2; ++i) {
            const double ref = std::pow(i == 0 ? x : y, power());
            if (result[i] == ref || (std::isnan(result[i]) && std::isnan(ref))) continue;
            if (!std::isfinite(result[i]) || !std::isfinite(ref) || ref == 0) return false;
            worst = std::max(worst, std::abs((result[i] - ref) / ref));
        }
        return true;
    };
    for (int e = -129; e <= 128; ++e) for (int i = 0; i < 256; ++i) {
        const double boundary = std::ldexp(1.0 + i / 256.0, e);
        const double sample = std::ldexp(1.0 + std::generate_canonical<double, 53>(rng), e);
        if (!compare(std::nextafter(boundary, 0.0), boundary) ||
            !compare(std::nextafter(boundary, std::numeric_limits<double>::infinity()), sample)) return false;
    }
    for (double special : {0.0, -0.0, -1.0, std::numeric_limits<double>::infinity(),
                            std::numeric_limits<double>::quiet_NaN(), 0x1p-1074, 0x1p+1023}) {
        // Subnormal results from libm can differ by one representable bit.
        const auto reference = _ZGVnN2vv_pow(float64x2_t{special, .2}, vdupq_n_f64(power()));
        const auto result = fixedPower(float64x2_t{special, .2}, power);
        if (result[1] != reference[1] ||
            (result[0] != reference[0] && !(std::isnan(result[0]) && std::isnan(reference[0])))) return false;
    }
    std::cout << "Fixed power p=" << power() << " max_relative=" << worst << '\n';
    return worst <= 3e-15;
}

bool fixedPowers() {
    const auto viewingExponent = [] { return 0.69 * (1.48 + std::sqrt(0.2)); };
    return fixedPowerCheck([] { return .42; }) && fixedPowerCheck([] { return 1.0 / .42; }) &&
        fixedPowerCheck(viewingExponent) &&
        fixedPowerCheck([] { return 1.0 / (0.69 * (1.48 + std::sqrt(0.2))); }) &&
        fixedPowerCheck([] { return .9; }) && fixedPowerCheck([] { return 1.0 / .9; }) &&
        fixedPowerCheck([] { return 2.2; }) && fixedPowerCheck([] { return 1.0 / 2.2; }) &&
        fixedPowerCheck([] { return 1.0 / 6.0; }) && fixedPowerCheck([] { return 1.0 / 2.4; });
}
#endif
}

int main() {
#if defined(__aarch64__)
    if (!fixedPowers()) return 1;
#endif
    if (!hasSpectralBackend(SpectralBackend::AdvSimd) || !spectral() || !cam16()) return 1;
    std::cout << "SIMD parity OK comparisons=" << comparisons << " max_abs=" << maximum
              << " changed_8_10bit=" << codesChanged << '\n';
    return codesChanged != 0;
}
