#include "Spectral.h"

#include <cmath>

#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
#include <arm_neon.h>
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2v_exp10(float64x2_t);
#endif

namespace filmkit::spektrafilm {
namespace {

constexpr double kLogFloor = 1e-10;

Triplet projectScalar(const SpectralProjection& p, const Triplet& density) noexcept {
    Triplet sum{};
    for (const auto& band : p.bands) {
        const auto& dye = band.dyeDensity;
        const double d = ((density[0] * dye[0] + density[1] * dye[1]) +
                          density[2] * dye[2]) + band.baseDensity;
        const double transmitted = std::pow(10.0, -d) * band.illuminant;
        const double light = std::isnan(transmitted) ? 0.0 : transmitted;
        for (int k = 0; k < 3; ++k) sum[k] += light * band.response[k];
    }
    for (int k = 0; k < 3; ++k) {
        const double v = (sum[k] * p.gain[k] + p.preflash[k]) / p.normalizer;
        sum[k] = std::log10(std::fmax(v, 0.0) + kLogFloor);
    }
    return sum;
}

#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
void projectPair(const SpectralProjection& p, const Triplet* density, Triplet* output) noexcept {
    const float64x2_t c{density[0][0], density[1][0]};
    const float64x2_t m{density[0][1], density[1][1]};
    const float64x2_t y{density[0][2], density[1][2]};
    std::array<float64x2_t, 3> sum{};
    for (const auto& band : p.bands) {
        const auto& dye = band.dyeDensity;
        const auto d = ((c * dye[0] + m * dye[1]) + y * dye[2]) + band.baseDensity;
        auto light = _ZGVnN2v_exp10(-d) * band.illuminant;
        light = vbslq_f64(vceqq_f64(light, light), light, vdupq_n_f64(0.0));
        for (int k = 0; k < 3; ++k) sum[k] += light * band.response[k];
    }
    for (int k = 0; k < 3; ++k) {
        const auto v = (sum[k] * p.gain[k] + p.preflash[k]) / p.normalizer;
        output[0][k] = std::log10(std::fmax(v[0], 0.0) + kLogFloor);
        output[1][k] = std::log10(std::fmax(v[1], 0.0) + kLogFloor);
    }
}
#endif

}  // namespace

bool hasSpectralBackend(SpectralBackend backend) noexcept {
    if (backend == SpectralBackend::Scalar) return true;
#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
    if (backend == SpectralBackend::AdvSimd) return true;
#endif
    return false;
}

bool projectLogSpectral(const SpectralProjection& projection,
                        std::span<const Triplet> density,
                        std::span<Triplet> output,
                        SpectralBackend backend) noexcept {
    if (density.size() != output.size() || !hasSpectralBackend(backend)) return false;
    std::size_t i = 0;
#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
    if (backend == SpectralBackend::AdvSimd) {
        for (; density.size() - i >= 2; i += 2) {
            projectPair(projection, density.data() + i, output.data() + i);
        }
    }
#endif
    for (; i < density.size(); ++i) output[i] = projectScalar(projection, density[i]);
    return true;
}

}  // namespace filmkit::spektrafilm
