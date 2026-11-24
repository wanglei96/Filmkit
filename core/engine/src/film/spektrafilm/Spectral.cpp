#include "Spectral.h"

#include <cmath>

#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
#include <arm_neon.h>
extern "C" __attribute__((aarch64_vector_pcs)) float64x2_t _ZGVnN2v_exp10(float64x2_t);
#endif

namespace filmkit::spektrafilm {
namespace {

constexpr double kLogFloor = 1e-10;

template <bool LogOutput>
double finishLight(double v) noexcept {
    const double light = std::fmax(v, 0.0) + kLogFloor;
    if constexpr (LogOutput) return std::log10(light);
    return light;
}

template <bool LogOutput>
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
        sum[k] = finishLight<LogOutput>(v);
    }
    return sum;
}

#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
template <bool LogOutput, std::size_t Pairs>
void projectBatch(const SpectralProjection& p, std::span<const SpectralBand* const> bands,
                   const Triplet* density, Triplet* output) noexcept {
    std::array<float64x2_t, Pairs> c, m, y;
    std::array<std::array<float64x2_t, 3>, Pairs> sum{};
    for (std::size_t i = 0; i < Pairs; ++i) {
        c[i] = {density[2 * i][0], density[2 * i + 1][0]};
        m[i] = {density[2 * i][1], density[2 * i + 1][1]};
        y[i] = {density[2 * i][2], density[2 * i + 1][2]};
    }
    for (const auto* entry : bands) {
        const auto& band = *entry;
        const auto& dye = band.dyeDensity;
        for (std::size_t i = 0; i < Pairs; ++i) {
            const auto d = ((c[i] * dye[0] + m[i] * dye[1]) + y[i] * dye[2]) + band.baseDensity;
            auto light = _ZGVnN2v_exp10(-d) * band.illuminant;
            light = vbslq_f64(vceqq_f64(light, light), light, vdupq_n_f64(0.0));
            for (int k = 0; k < 3; ++k) sum[i][k] += light * band.response[k];
        }
    }
    for (std::size_t i = 0; i < Pairs; ++i) {
        for (int k = 0; k < 3; ++k) {
            const auto v = (sum[i][k] * p.gain[k] + p.preflash[k]) / p.normalizer;
            output[2 * i][k] = finishLight<LogOutput>(v[0]);
            output[2 * i + 1][k] = finishLight<LogOutput>(v[1]);
        }
    }
}
#endif

template <bool LogOutput>
bool project(const SpectralProjection& projection, std::span<const Triplet> density,
             std::span<Triplet> output, SpectralBackend backend) noexcept {
    if (density.size() != output.size() || !hasSpectralBackend(backend)) return false;
    std::size_t i = 0;
#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
    if (backend == SpectralBackend::AdvSimd) {
        std::array<const SpectralBand*, 81> contributing;
        std::size_t count = 0;
        for (const auto& p : projection.bands) {
            const bool zeroLight = p.illuminant == 0.0 || std::isnan(p.illuminant) ||
                std::isnan(p.baseDensity) || std::isnan(p.dyeDensity[0]) ||
                std::isnan(p.dyeDensity[1]) || std::isnan(p.dyeDensity[2]);
            // Zero light with finite response contributes only signed zero.
            // Keep nonfinite responses and the order of every other addition.
            if (zeroLight && std::isfinite(p.response[0]) && std::isfinite(p.response[1]) &&
                std::isfinite(p.response[2])) continue;
            contributing[count++] = &p;
        }
        const std::span<const SpectralBand* const> bands(contributing.data(), count);
        for (; density.size() - i >= 4; i += 4) {
            projectBatch<LogOutput, 2>(projection, bands, density.data() + i, output.data() + i);
        }
        for (; density.size() - i >= 2; i += 2) {
            projectBatch<LogOutput, 1>(projection, bands, density.data() + i, output.data() + i);
        }
    }
#endif
    for (; i < density.size(); ++i) output[i] = projectScalar<LogOutput>(projection, density[i]);
    return true;
}

}  // namespace

bool hasSpectralBackend(SpectralBackend backend) noexcept {
    if (backend == SpectralBackend::Scalar) return true;
#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
    if (backend == SpectralBackend::AdvSimd) return true;
#endif
    return false;
}

bool projectLogSpectral(const SpectralProjection& projection, std::span<const Triplet> density,
                        std::span<Triplet> output, SpectralBackend backend) noexcept {
    return project<true>(projection, density, output, backend);
}

bool projectSpectralLight(const SpectralProjection& projection, std::span<const Triplet> density,
                          std::span<Triplet> output, SpectralBackend backend) noexcept {
    return project<false>(projection, density, output, backend);
}

void decodeLogSpectralLight(std::span<Triplet> pixels, SpectralBackend backend) noexcept {
    std::size_t i = 0;
#if defined(FILMKIT_SPEKTRAFILM_ARM_EXP10)
    if (backend == SpectralBackend::AdvSimd) {
        for (; pixels.size() - i >= 2; i += 2) {
            const auto a = _ZGVnN2v_exp10(float64x2_t{pixels[i][0], pixels[i][1]});
            const auto b = _ZGVnN2v_exp10(float64x2_t{pixels[i][2], pixels[i + 1][0]});
            const auto c = _ZGVnN2v_exp10(float64x2_t{pixels[i + 1][1], pixels[i + 1][2]});
            pixels[i] = {a[0], a[1], b[0]};
            pixels[i + 1] = {b[1], c[0], c[1]};
        }
    }
#endif
    for (; i < pixels.size(); ++i) for (auto& v : pixels[i]) v = std::pow(10.0, v);
}

}  // namespace filmkit::spektrafilm
