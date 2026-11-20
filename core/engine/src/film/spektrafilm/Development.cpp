#include "Development.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numbers>
#include <numeric>
#include <stdexcept>

namespace filmkit::spektrafilm {
namespace {

bool positiveFinite(double x) { return x > 0.0 && std::isfinite(x); }

double paperDensity(double exposure, const std::vector<EmulsionLayer>& layers,
                    EmulsionType type, double exhaustion, double offset = 0.0) {
    const double gumbelLocation = -std::log(std::numbers::ln2);
    const double gumbelWidth = 0.5 * std::numbers::ln2 * std::sqrt(2.0 * std::numbers::pi);
    double sum = 0.0;
    for (const auto& layer : layers) {
        const double z = (exposure - (layer.center + offset)) / layer.sigma;
        const double signedZ = type == EmulsionType::Positive ? -z : z;
        double cdf = 0.5 * std::erfc(-signedZ * std::numbers::sqrt2 / 2.0);
        if (exhaustion > 0.0) {
            const double gumbel = std::exp(-std::exp(-(signedZ / gumbelWidth + gumbelLocation)));
            cdf = (1.0 - exhaustion) * cdf + exhaustion * gumbel;
        }
        sum += layer.amplitude * cdf;
    }
    return sum;
}

double exhaustionOffset(const std::vector<EmulsionLayer>& layers, EmulsionType type, double mix) {
    if (mix <= 1e-8) return 0.0;
    const double target = paperDensity(0.0, layers, type, 0.0);
    const auto residual = [&](double offset) {
        return paperDensity(0.0, layers, type, mix, offset) - target;
    };
    if (std::abs(residual(0.0)) <= 1e-12) return 0.0;
    // Match the reference's expanding bracket and anchor to the pre-exhaustion D(0).
    for (double radius = 0.25; radius <= 512.0; radius *= 2.0) {
        double lo = -radius, hi = radius;
        double rLo = residual(lo);
        const double rHi = residual(hi);
        if (rLo == 0.0) return lo;
        if (rHi == 0.0) return hi;
        if ((rLo < 0.0) == (rHi < 0.0)) continue;
        while (hi - lo > 1e-12) {
            const double middle = std::midpoint(lo, hi);
            const double r = residual(middle);
            if (r == 0.0 || middle == lo || middle == hi) return middle;
            if ((r < 0.0) == (rLo < 0.0)) {
                lo = middle;
                rLo = r;
            } else {
                hi = middle;
            }
        }
        return std::midpoint(lo, hi);
    }
    return 0.0;
}

}  // namespace

DensityCurve::DensityCurve(std::span<const double> exposure, std::span<const Triplet> density,
                           Triplet gamma) {
    if (exposure.size() < 2 || exposure.size() != density.size()) {
        throw std::invalid_argument("invalid density curve size");
    }
    for (std::size_t c = 0; c < channels_.size(); ++c) {
        if (!positiveFinite(gamma[c])) throw std::invalid_argument("invalid development gamma");
        auto& curve = channels_[c];
        curve.reserve(exposure.size());
        for (std::size_t i = 0; i < exposure.size(); ++i) {
            const double x = exposure[i] / gamma[c];
            if (!std::isfinite(x) || (!curve.empty() && x < curve.back().x)) {
                throw std::invalid_argument("unordered or non-finite exposure knots");
            }
            if (!curve.empty() && x != curve.back().x) {
                curve.back().inverseNextStep = 1.0 / (x - curve.back().x);
            }
            curve.push_back({x, density[i][c], 0.0});
        }
        const double extent = curve.back().x - curve.front().x;
        const double scale = kSearchBins / extent;
        if (!(extent > 0) || !positiveFinite(scale)) continue;
        auto& index = search_[c];
        index.scale = scale;
        index.lower.resize(kSearchBins);
        std::size_t lo = 0;
        for (std::size_t bin = 0; bin < kSearchBins; ++bin) {
            const double x = std::lerp(curve.front().x, curve.back().x,
                                       (bin + 0.5) / kSearchBins);
            while (lo + 2 < curve.size() && curve[lo + 1].x <= x) ++lo;
            index.lower[bin] = lo;
        }
    }
}

bool DensityCurve::develop(std::span<const Triplet> exposure, std::span<Triplet> density) const noexcept {
    if (exposure.size() != density.size() || channels_[0].empty()) return false;
    for (std::size_t i = 0; i < exposure.size(); ++i) {
        for (std::size_t c = 0; c < channels_.size(); ++c) {
            const auto& curve = channels_[c];
            const double x = exposure[i][c];
            if (std::isnan(x)) {
                density[i][c] = x;
            } else if (x <= curve.front().x) {
                density[i][c] = curve.front().y;
            } else if (x >= curve.back().x) {
                density[i][c] = curve.back().y;
            } else {
                const auto& index = search_[c];
                std::size_t lo = 0;
                if (!index.lower.empty()) {
                    const double bin = std::min((x - curve.front().x) * index.scale,
                                                static_cast<double>(kSearchBins - 1));
                    lo = index.lower[static_cast<std::size_t>(bin)];
                    if (x < curve[lo].x && lo > 0) --lo;
                    else if (x >= curve[lo + 1].x && lo + 2 < curve.size()) ++lo;
                }
                // The index only suggests a bracket. Keep upper-bound semantics
                // at exact knots, including repeated or non-uniform knots.
                if (!(curve[lo].x <= x && x < curve[lo + 1].x)) {
                    lo = std::upper_bound(curve.begin(), curve.end(), x,
                                          [](double value, const Knot& k) { return value < k.x; }) - curve.begin() - 1;
                }
                const auto& a = curve[lo];
                const double t = (x - a.x) * a.inverseNextStep;
                density[i][c] = a.y + t * (curve[lo + 1].y - a.y);
            }
        }
    }
    return true;
}

DensityCurve preparePaperDevelopment(std::span<const double> exposure, const PaperModel& model,
                                     const PaperMorph& morph, EmulsionType type) {
    std::vector<Triplet> density(exposure.size());
    if (morph.active && !(morph.exhaustion >= 0.0 && morph.exhaustion <= 1.0)) {
        throw std::invalid_argument("invalid paper developer exhaustion");
    }
    for (std::size_t c = 0; c < model.size(); ++c) {
        auto layers = model[c];
        if (layers.empty()) throw std::invalid_argument("missing paper emulsion layers");
        for (const auto& layer : layers) {
            if (!std::isfinite(layer.center) || !std::isfinite(layer.amplitude) ||
                !positiveFinite(layer.sigma)) throw std::invalid_argument("invalid paper emulsion layer");
        }
        const double mix = morph.active ? morph.exhaustion : 0.0;
        if (morph.active) {
            if (!positiveFinite(morph.gamma) || !positiveFinite(morph.channelGamma[c]) ||
                !positiveFinite(morph.fastGamma) || !positiveFinite(morph.slowGamma)) {
                throw std::invalid_argument("invalid paper gamma");
            }
            const double gamma = morph.gamma * morph.channelGamma[c];
            const std::array factors{gamma * morph.fastGamma, gamma * morph.slowGamma,
                                     gamma * morph.slowGamma};
            std::vector<std::size_t> speed(layers.size());
            std::iota(speed.begin(), speed.end(), 0);
            std::stable_sort(speed.begin(), speed.end(), [&](std::size_t a, std::size_t b) {
                return layers[a].center < layers[b].center;
            });
            const std::array indices{speed.front(), speed[speed.size() / 2], speed.back()};
            for (std::size_t i = 0; i < indices.size(); ++i) {
                if (!positiveFinite(factors[i])) throw std::invalid_argument("invalid effective paper gamma");
                auto& layer = layers[indices[i]];
                layer.sigma = std::max(layer.sigma / factors[i], 0.05);
                layer.center /= factors[i];
            }
        }
        const double offset = exhaustionOffset(layers, type, mix);
        for (std::size_t i = 0; i < exposure.size(); ++i) {
            density[i][c] = paperDensity(exposure[i], layers, type, mix, offset);
        }
    }
    return DensityCurve(exposure, density);
}

DensityCurve prepareInitialFilmDevelopment(std::span<const double> exposure,
                                           std::span<const Triplet> stock, Triplet gamma) {
    Triplet minimum;
    minimum.fill(std::numeric_limits<double>::infinity());
    for (const auto& knot : stock) {
        for (std::size_t c = 0; c < minimum.size(); ++c) minimum[c] = std::fmin(minimum[c], knot[c]);
    }
    std::vector<Triplet> normalized(stock.begin(), stock.end());
    for (auto& knot : normalized) {
        for (std::size_t c = 0; c < minimum.size(); ++c) knot[c] -= minimum[c];
    }
    return DensityCurve(exposure, normalized, gamma);
}

}  // namespace filmkit::spektrafilm
