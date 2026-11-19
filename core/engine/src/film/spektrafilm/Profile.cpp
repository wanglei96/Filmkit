#include "Profile.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <stdexcept>

namespace filmkit::spektrafilm {
namespace {

std::shared_ptr<const NegativeRenderParams> installed;
std::mutex profileMutex;

class Reader {
public:
    explicit Reader(std::span<const std::byte> bytes) : bytes_(bytes) {}

    template <class Range> void read(Range& range, bool spectral = false) {
        auto out = std::as_writable_bytes(std::span(range));
        if (out.size() > bytes_.size()) throw std::runtime_error("truncated profile");
        std::memcpy(out.data(), bytes_.data(), out.size());
        bytes_ = bytes_.subspan(out.size());
        if constexpr (!std::is_integral_v<typename Range::value_type>) {
            for (std::size_t i = 0; i < out.size(); i += sizeof(double)) {
                double v;
                std::memcpy(&v, out.data() + i, sizeof(v));
                if (std::isinf(v) || (!spectral && std::isnan(v)))
                    throw std::runtime_error("nonfinite profile parameter");
            }
        }
    }

    SpectralProjection projection() {
        SpectralProjection p;
        for (auto& band : p.bands) {
            std::array<double, 8> v;
            read(v, true);
            std::copy_n(v.begin(), 3, band.dyeDensity.begin());
            band.baseDensity = v[3]; band.illuminant = v[4];
            std::copy_n(v.begin() + 5, 3, band.response.begin());
            // Dye/base endpoints remain NaN. CMFs also retain the reference's
            // tiny signed interpolation residuals; neither is clamped/filled.
            if (!std::isfinite(band.illuminant) || band.illuminant < 0.0 ||
                !std::all_of(band.response.begin(), band.response.end(), [](double x) { return std::isfinite(x); }))
                throw std::runtime_error("invalid spectral illumination/response");
        }
        std::array<double, 7> v;
        read(v);
        std::copy_n(v.begin(), 3, p.gain.begin());
        std::copy_n(v.begin() + 3, 3, p.preflash.begin());
        p.normalizer = v[6];
        if (!(p.normalizer > 0.0)) throw std::runtime_error("invalid spectral normalizer");
        return p;
    }

    bool empty() const { return bytes_.empty(); }

private:
    std::span<const std::byte> bytes_;
};

void checkAxis(std::span<const double> axis) {
    for (std::size_t i = 1; i < axis.size(); ++i)
        if (!(axis[i] > axis[i - 1])) throw std::runtime_error("unordered density axis");
}

}  // namespace

std::shared_ptr<const NegativeRenderParams> loadNegativeProfile(
    std::span<const std::byte> bytes, std::string* error,
    std::span<const std::byte> responseBytes) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(sizeof(Triplet) == 24 && sizeof(GrainLayers) == 72);
    if (error) error->clear();
    try {
        if (bytes.size() > 64 * 1024 * 1024) throw std::runtime_error("oversized profile");
        Reader in(bytes);
        std::array<std::uint32_t, 9> header;
        in.read(header);
        const auto [magic, version, width, height, side, filmKnots, paperKnots, modelLayers, oracle] = header;
        if (magic != 0x534b4231 || (version != 2 && version != 3) || width || height || oracle ||
            side < 2 || side > 1024 || filmKnots < 2 || filmKnots > 4096 ||
            paperKnots < 2 || paperKnots > 4096 || !modelLayers || modelLayers > 32)
            throw std::runtime_error("unsupported profile header");
        std::array<std::uint32_t, 3> h;
        std::array<double, 27> f;
        std::array<Triplet, 3> matrix;
        in.read(h); in.read(f); in.read(matrix);
        std::vector<Triplet> response(std::size_t(side) * side);
        if (version == 3) {
            if (responseBytes.size() != response.size() * sizeof(Triplet))
                throw std::runtime_error("mismatched film response table");
            Reader shared(responseBytes);
            shared.read(response);
        } else in.read(response);
        std::array<std::uint32_t, 1> d;
        std::array<double, 15> dir;
        in.read(d); in.read(dir);
        std::vector<double> filmAxis(filmKnots);
        std::vector<Triplet> filmStock(filmKnots);
        in.read(filmAxis); in.read(filmStock); checkAxis(filmAxis);
        std::array<std::uint32_t, 3> grain;
        std::array<double, 17> g;
        in.read(grain); in.read(g);
        std::vector<GrainLayers> layers(filmKnots);
        in.read(layers);
        std::array<std::uint32_t, 4> flags;
        std::array<double, 22> s;
        in.read(flags); in.read(s);
        auto enlarger = in.projection(), scanner = in.projection();
        std::vector<double> paperAxis(paperKnots);
        std::vector<Triplet> paperStock(paperKnots);
        in.read(paperAxis); in.read(paperStock); checkAxis(paperAxis);
        PaperModel model;
        for (auto& channel : model) for (std::size_t j = 0; j < modelLayers; ++j) {
            Triplet v;
            in.read(v);
            if (!(v[2] > 0.0)) throw std::runtime_error("invalid paper sigma");
            channel.push_back({v[0], v[1], v[2]});
        }
        std::array<std::uint32_t, 3> mode{};
        Triplet midgrayRaw{};
        if (version == 3) { in.read(mode); in.read(midgrayRaw); }
        if (std::any_of(mode.begin(), mode.end(), [](auto v) { return v > 1; }) ||
            (mode[0] && !mode[1]) || (mode[1] && (flags[0] || flags[1] || flags[2])))
            throw std::runtime_error("unsupported scan configuration");
        if (!in.empty() || !(f[0] > 0.0) || !(f[26] > 0.0) ||
            h[0] > 1 || h[2] > 1 || h[1] > 32 || d[0] > 1 ||
            grain[0] > 1 || grain[1] > 1 || !grain[2] || grain[2] > 32 ||
            std::any_of(flags.begin(), flags.end(), [](auto v) { return v > 1; }))
            throw std::runtime_error("invalid profile configuration");
        auto p = std::make_shared<NegativeRenderParams>(preparePaperDevelopment(paperAxis, model,
            {flags[3] != 0, s[12], s[13], s[14], {s[15], s[16], s[17]}, s[18]}, EmulsionType::Negative));
        p->filmType = mode[0] ? EmulsionType::Positive : EmulsionType::Negative;
        p->directScan = mode[1]; p->compensatePrintExposure = mode[2];
        p->midgrayRaw = midgrayRaw;
        p->pixelUm = f[0]; p->exposureEv = f[1]; p->lensBlurUm = f[2];
        p->boost = {f[3], f[4], f[5]}; p->xyzFromRgb = matrix;
        p->responseSide = side; p->response = std::move(response);
        auto& halation = p->halation;
        halation.active = h[0]; halation.bounces = h[1]; halation.renormalize = h[2];
        halation.scatterAmount = f[6]; halation.scatterScale = f[7];
        halation.halationAmount = f[8]; halation.halationScale = f[9]; halation.bounceDecay = f[10];
        std::copy_n(f.begin() + 11, 3, halation.scatterCoreUm.begin());
        std::copy_n(f.begin() + 14, 3, halation.scatterTailUm.begin());
        std::copy_n(f.begin() + 17, 3, halation.scatterTailWeight.begin());
        std::copy_n(f.begin() + 20, 3, halation.halationStrength.begin());
        std::copy_n(f.begin() + 23, 3, halation.halationFirstSigmaUm.begin());
        p->exposureCorrection = f[26];
        p->dir.active = d[0]; p->dir.diffusionUm = dir[0]; p->dir.tailUm = dir[1]; p->dir.tailWeight = dir[2];
        std::copy_n(dir.begin() + 3, 3, p->filmGamma.begin());
        for (std::size_t c = 0; c < 3; ++c) std::copy_n(dir.begin() + 6 + 3 * c, 3, p->dir.inhibition[c].begin());
        p->filmAxis = std::move(filmAxis); p->filmStock = std::move(filmStock);
        p->grainTotal = p->filmStock;
        for (std::size_t c = 0; c < 3; ++c) {
            double minimum = std::numeric_limits<double>::infinity();
            for (const auto& knot : p->filmStock) minimum = std::min(minimum, knot[c]);
            for (auto& knot : p->grainTotal) knot[c] -= minimum;
        }
        p->grainLayers = std::move(layers);
        p->grain.active = grain[0]; p->grain.layered = grain[1]; p->grain.singleModelLayers = grain[2];
        p->grain.particleAreaUm2 = g[0];
        std::copy_n(g.begin() + 1, 3, p->grain.particleScale.begin());
        std::copy_n(g.begin() + 4, 3, p->grain.layerScale.begin());
        std::copy_n(g.begin() + 7, 3, p->grain.densityFloor.begin());
        std::copy_n(g.begin() + 10, 3, p->grain.uniformity.begin());
        p->grain.finalBlurPx = g[13]; p->grain.dyeCloudBlur = g[14];
        p->grain.microBlurUm = g[15]; p->grain.microDeviationNm = g[16];
        p->enlarger = enlarger; p->scanner = scanner; p->printExposure = s[2];
        p->references = preparePrintReferences(enlarger, scanner, {s[6], s[7], s[8]}, {s[9], s[10], s[11]},
            paperAxis, paperStock, {flags[0] != 0, flags[1] != 0, s[0], s[1]});
        p->glare = {flags[2] != 0, s[3], s[4], s[5]};
        p->scanLensBlur = s[19]; p->unsharpSigma = s[20]; p->unsharpAmount = s[21];
        return p;
    } catch (const std::exception& e) {
        if (error) *error = e.what();
        return {};
    }
}

bool installNegativeProfile(std::span<const std::byte> bytes, std::string* error) {
    auto profile = loadNegativeProfile(bytes, error);
    if (!profile) return false;
    std::lock_guard lock(profileMutex);
    installed = std::move(profile);
    return true;
}

std::shared_ptr<const NegativeRenderParams> negativeProfile() {
    std::lock_guard lock(profileMutex);
    return installed;
}

}  // namespace filmkit::spektrafilm
