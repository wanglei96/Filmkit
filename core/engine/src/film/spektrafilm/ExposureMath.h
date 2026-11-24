#pragma once

#include "Spectral.h"

#include <cmath>

#if defined(FILMKIT_SPEKTRAFILM_ARM_LOG10)
#include <arm_neon.h>
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2v_log10(float64x2_t);
#endif

namespace filmkit::spektrafilm {

inline void logExposure(std::span<Triplet> pixels, double scale) {
    constexpr double floor = 1e-10;
#if defined(FILMKIT_SPEKTRAFILM_ARM_LOG10)
    while (pixels.size() >= 2) {
        auto values = vld3q_f64(pixels.front().data());
        for (auto& x : values.val) {
            x = _ZGVnN2v_log10(vaddq_f64(vmaxnmq_f64(vmulq_n_f64(x, scale), vdupq_n_f64(0.0)),
                                         vdupq_n_f64(floor)));
        }
        vst3q_f64(pixels.front().data(), values);
        pixels = pixels.subspan(2);
    }
#endif
    for (auto& pixel : pixels) {
        for (auto& x : pixel) x = std::log10(std::fmax(x * scale, 0.0) + floor);
    }
}

}  // namespace filmkit::spektrafilm
