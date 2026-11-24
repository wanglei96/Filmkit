#pragma once

#include <arm_neon.h>
#include <array>
#include <cmath>
#include <cstdint>

extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2vv_pow(float64x2_t, float64x2_t);

namespace filmkit::spektrafilm {

// Positive normal x = 2^e * m. Around the center c of a mantissa cell,
// x^p = 2^(e*p) * c^p * (1 + (m - c)/c)^p. A degree-five binomial expansion
// with 256 cells bounds |(m - c)/c| below 1/512. All arithmetic is FP64.
template <class Exponent>
inline float64x2_t fixedPower(float64x2_t x, Exponent power) {
    constexpr unsigned kBits = 8, kShift = 52 - kBits, kSize = 1u << kBits;
    constexpr std::uint64_t kCellMask = (UINT64_C(1) << kShift) - 1;
    constexpr unsigned kFirst = 1023 - 128, kLast = 1023 + 127;
    const auto bits = vreinterpretq_u64_f64(x);
    const auto exponent = vshrq_n_u64(bits, 52);
    if (exponent[0] < kFirst || exponent[0] > kLast ||
        exponent[1] < kFirst || exponent[1] > kLast) {
        return _ZGVnN2vv_pow(x, vdupq_n_f64(power()));
    }

    struct Tables {
        struct Cell { double scaledReciprocal, power; };
        std::array<Cell, kSize> center;
        std::array<double, kLast - kFirst + 1> scale;
        std::array<double, 5> coefficients;
        explicit Tables(double p) {
            for (unsigned i = 0; i < kSize; ++i) {
                const double c = 1.0 + (i + 0.5) / kSize;
                center[i] = {0x1p-52 / c, std::pow(c, p)};
            }
            for (unsigned i = kFirst; i <= kLast; ++i) {
                scale[i - kFirst] = std::pow(std::ldexp(1.0, static_cast<int>(i) - 1023), p);
            }
            double coefficient = 1.0;
            for (unsigned i = 1; i <= coefficients.size(); ++i) {
                coefficient *= (p - (i - 1)) / i;
                coefficients[i - 1] = coefficient;
            }
        }
    };
    static const Tables table(power());
    const auto cells = vandq_u64(vshrq_n_u64(bits, kShift), vdupq_n_u64(kSize - 1));
    const auto& c0 = table.center[cells[0]];
    const auto& c1 = table.center[cells[1]];
    const auto delta = vsubq_u64(vandq_u64(bits, vdupq_n_u64(kCellMask)),
                                 vdupq_n_u64(UINT64_C(1) << (kShift - 1)));
    const auto r = vcvtq_f64_s64(vreinterpretq_s64_u64(delta)) *
                   float64x2_t{c0.scaledReciprocal, c1.scaledReciprocal};
    auto poly = vdupq_n_f64(table.coefficients.back());
    for (int i = 3; i >= 0; --i) poly = vfmaq_f64(vdupq_n_f64(table.coefficients[i]), poly, r);
    poly = vfmaq_f64(vdupq_n_f64(1.0), poly, r);
    const float64x2_t scale{table.scale[exponent[0] - kFirst], table.scale[exponent[1] - kFirst]};
    return (poly * float64x2_t{c0.power, c1.power}) * scale;
}

}  // namespace filmkit::spektrafilm
