#include "film/spektrafilm/Render.h"
#include "film/spektrafilm/Profile.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <sys/resource.h>

using namespace filmkit::spektrafilm;

namespace {
template <class Range>
void read(std::istream& in, Range& range) {
    const auto bytes = std::as_writable_bytes(std::span(range));
    if (!in.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) throw std::runtime_error("truncated render fixture");
}

SpectralProjection projection(std::istream& in) {
    SpectralProjection p;
    for (auto& b : p.bands) {
        std::array<double, 8> v;
        read(in, v);
        std::copy_n(v.begin(), 3, b.dyeDensity.begin());
        b.baseDensity = v[3];
        b.illuminant = v[4];
        std::copy_n(v.begin() + 5, 3, b.response.begin());
    }
    std::array<double, 7> v;
    read(in, v);
    std::copy_n(v.begin(), 3, p.gain.begin());
    std::copy_n(v.begin() + 3, 3, p.preflash.begin());
    p.normalizer = v[6];
    return p;
}

struct Tape {
    std::size_t pixels = 0, calls = 0, failures = 0;
    double meanError = 0.0, pError = 0.0;
    std::vector<double> mean, probability, glare;
    std::vector<std::uint64_t> counts;
};

std::uint64_t replay(void* ctx, std::size_t c, std::size_t layer, std::size_t pixel, double mean, double p) {
    auto& t = *static_cast<Tape*>(ctx);
    const auto i = (c * 3 + layer) * t.pixels + pixel;
    const auto meanError = std::abs(mean - t.mean.at(i));
    const auto pError = std::abs(p - t.probability.at(i));
    t.meanError = std::max(t.meanError, meanError);
    t.pError = std::max(t.pError, pError);
    t.failures += !std::isfinite(mean) || !std::isfinite(p) ||
        meanError > 1e-11 * std::max(1.0, std::abs(t.mean[i])) || pError > 1e-12;
    ++t.calls;
    return t.counts.at(i);
}
}  // namespace

int main(int argc, char** argv) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(sizeof(Triplet) == 24 && sizeof(GrainLayers) == 72);
    if (argc != 6 && argc != 7 && argc != 8) return 2;
    try {
        RenderWorkers workers{std::stoul(argv[2]), std::stoul(argv[3]), std::stoul(argv[4]), std::stoul(argv[5])};
        std::ifstream in(argv[1], std::ios::binary);
        std::array<std::uint32_t, 9> header;
        read(in, header);
        const auto [magic, version, width, height, side, filmKnots, paperKnots, modelLayers, oracle] = header;
        const auto n = std::size_t(width) * height;
        if (magic != 0x534b4231 || version != 1 || !n || n > 16000000 || side < 2 || side > 1024 ||
            filmKnots < 2 || filmKnots > 4096 || paperKnots < 2 || paperKnots > 4096 ||
            !modelLayers || modelLayers > 32 || oracle > 1 || (oracle && n > 1000000)) return 2;
        std::array<std::uint32_t, 3> h;
        read(in, h);
        std::array<double, 27> f;
        read(in, f);
        std::array<Triplet, 3> matrix;
        read(in, matrix);
        std::vector<Triplet> response(side * side);
        read(in, response);
        std::array<std::uint32_t, 1> d;
        read(in, d);
        std::array<double, 15> dir;
        read(in, dir);
        std::vector<double> filmAxis(filmKnots);
        std::vector<Triplet> filmStock(filmKnots);
        read(in, filmAxis);
        read(in, filmStock);
        std::array<std::uint32_t, 3> grain;
        read(in, grain);
        std::array<double, 17> g;
        read(in, g);
        std::vector<GrainLayers> layers(filmKnots);
        read(in, layers);
        std::array<std::uint32_t, 4> flags;
        read(in, flags);
        std::array<double, 22> s;
        read(in, s);
        const auto enlarger = projection(in), scanner = projection(in);
        std::vector<double> paperAxis(paperKnots);
        std::vector<Triplet> paperStock(paperKnots);
        read(in, paperAxis);
        read(in, paperStock);
        PaperModel model;
        for (auto& c : model) for (std::size_t j = 0; j < modelLayers; ++j) {
            Triplet v;
            read(in, v);
            c.push_back({v[0], v[1], v[2]});
        }
        NegativeRenderParams p(preparePaperDevelopment(paperAxis, model,
            {flags[3] != 0, s[12], s[13], s[14], {s[15], s[16], s[17]}, s[18]}, EmulsionType::Negative));
        p.pixelUm = f[0]; p.exposureEv = f[1]; p.lensBlurUm = f[2];
        p.boost = {f[3], f[4], f[5]};
        p.xyzFromRgb = matrix; p.responseSide = side; p.response = std::move(response);
        auto& halation = p.halation;
        halation.active = h[0]; halation.bounces = h[1]; halation.renormalize = h[2];
        halation.scatterAmount = f[6]; halation.scatterScale = f[7];
        halation.halationAmount = f[8]; halation.halationScale = f[9]; halation.bounceDecay = f[10];
        std::copy_n(f.begin() + 11, 3, halation.scatterCoreUm.begin());
        std::copy_n(f.begin() + 14, 3, halation.scatterTailUm.begin());
        std::copy_n(f.begin() + 17, 3, halation.scatterTailWeight.begin());
        std::copy_n(f.begin() + 20, 3, halation.halationStrength.begin());
        std::copy_n(f.begin() + 23, 3, halation.halationFirstSigmaUm.begin());
        p.exposureCorrection = f[26];
        p.dir.active = d[0]; p.dir.diffusionUm = dir[0]; p.dir.tailUm = dir[1]; p.dir.tailWeight = dir[2];
        std::copy_n(dir.begin() + 3, 3, p.filmGamma.begin());
        for (std::size_t c = 0; c < 3; ++c) std::copy_n(dir.begin() + 6 + 3 * c, 3, p.dir.inhibition[c].begin());
        p.filmAxis = std::move(filmAxis); p.filmStock = std::move(filmStock);
        p.grainTotal = p.filmStock;
        for (std::size_t c = 0; c < 3; ++c) {
            double minimum = std::numeric_limits<double>::infinity();
            for (const auto& knot : p.filmStock) minimum = std::min(minimum, knot[c]);
            for (auto& knot : p.grainTotal) knot[c] -= minimum;
        }
        p.grainLayers = std::move(layers);
        p.grain.active = grain[0]; p.grain.layered = grain[1]; p.grain.singleModelLayers = grain[2];
        p.grain.particleAreaUm2 = g[0];
        std::copy_n(g.begin() + 1, 3, p.grain.particleScale.begin());
        std::copy_n(g.begin() + 4, 3, p.grain.layerScale.begin());
        std::copy_n(g.begin() + 7, 3, p.grain.densityFloor.begin());
        std::copy_n(g.begin() + 10, 3, p.grain.uniformity.begin());
        p.grain.finalBlurPx = g[13]; p.grain.dyeCloudBlur = g[14];
        p.grain.microBlurUm = g[15]; p.grain.microDeviationNm = g[16];
        p.enlarger = enlarger; p.scanner = scanner; p.printExposure = s[2];
        p.references = preparePrintReferences(enlarger, scanner, {s[6], s[7], s[8]}, {s[9], s[10], s[11]},
            paperAxis, paperStock, {flags[0] != 0, flags[1] != 0, s[0], s[1]});
        p.glare = {flags[2] != 0, s[3], s[4], s[5]};
        p.scanLensBlur = s[19]; p.unsharpSigma = s[20]; p.unsharpAmount = s[21];
        if (argc == 8) {
            std::ifstream asset(argv[7], std::ios::binary);
            const std::vector<char> bytes{std::istreambuf_iterator<char>(asset), {}};
            std::string error;
            auto profile = loadNegativeProfile(std::as_bytes(std::span(bytes)), &error);
            if (!profile) throw std::runtime_error(error);
            const double pixelUm = p.pixelUm;
            p = *profile;
            p.pixelUm = pixelUm;
        }
        std::vector<Triplet> image(n), expected;
        read(in, image);
        Tape tape;
        tape.pixels = n;
        if (oracle) {
            tape.mean.resize(9 * n); tape.probability.resize(9 * n); tape.counts.resize(9 * n);
            tape.glare.resize(n); expected.resize(n);
            read(in, tape.mean); read(in, tape.probability); read(in, tape.counts);
            read(in, tape.glare); read(in, expected);
        }
        if (in.peek() != std::char_traits<char>::eof()) throw std::runtime_error("extra fixture data");
        const auto backend = hasSpectralBackend(SpectralBackend::AdvSimd) ? SpectralBackend::AdvSimd : SpectralBackend::Scalar;
        const GrainReplay draws{&tape, replay, nullptr};
        RenderTimings t;
        if (!renderNegativeSrgb(image, width, height, p, backend, workers, 63, 64, &t,
                                oracle ? &draws : nullptr, tape.glare)) throw std::runtime_error("render rejected");
        rusage usage{};
        getrusage(RUSAGE_SELF, &usage);
#if defined(__APPLE__)
        usage.ru_maxrss /= 1024;
#endif
        double minimum = std::numeric_limits<double>::infinity(), maximum = -minimum, error = 0.0;
        std::size_t nonfinite = 0;
        std::uint64_t hash = 14695981039346656037ULL;
        for (std::size_t i = 0; i < n; ++i) for (std::size_t c = 0; c < 3; ++c) {
            const double v = image[i][c];
            nonfinite += !std::isfinite(v);
            minimum = std::min(minimum, v); maximum = std::max(maximum, v);
            hash = (hash ^ std::bit_cast<std::uint64_t>(v)) * 1099511628211ULL;
            if (oracle) error = std::max(error, std::abs(v - expected[i][c]));
        }
        if (argc >= 7) {
            std::ofstream out(argv[6], std::ios::binary);
            out.write(reinterpret_cast<const char*>(image.data()), image.size() * sizeof(Triplet));
            out.close();
            if (!out) throw std::runtime_error("output write failed");
        }
        const bool failed = nonfinite || !(maximum > minimum) ||
            (oracle && (error > 1e-10 || tape.failures || tape.calls != 9 * n));
        std::cout << std::setprecision(10) << "pixels=" << n << " backend=" << (backend == SpectralBackend::AdvSimd ? "neon" : "scalar")
            << " pixel_workers=" << workers.pixels << " grain_workers=" << workers.grain << " output_workers=" << workers.output
            << " filter_workers=" << workers.filters << " render_s=" << t.total << " hanatos_s=" << t.hanatos << " film_exposure_s=" << t.filmExposure
            << " dir_s=" << t.dir << " grain_s=" << t.grain << " grain_particles_s=" << t.grainParts.particlesSeconds
            << " grain_dye_s=" << t.grainParts.dyeCloudSeconds << " grain_finish_s=" << t.grainParts.finishSeconds
            << " paper_exposure_s=" << t.paperExposure << " paper_development_s=" << t.paperDevelopment
            << " scan_spectral_s=" << t.scanSpectral << " scan_glare_s=" << t.scanGlare << " scan_output_s=" << t.scanOutput
            << " peak_rss_kib=" << usage.ru_maxrss << " nonfinite=" << nonfinite << " min=" << minimum << " max=" << maximum
            << " oracle=" << oracle;
        if (oracle) std::cout << " max_rgb_error=" << error << " max_mean_error=" << tape.meanError
                             << " max_probability_error=" << tape.pError;
        std::cout << " failed=" << failed << " checksum=" << std::hex << hash << '\n';
        return failed;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 2;
    }
}
