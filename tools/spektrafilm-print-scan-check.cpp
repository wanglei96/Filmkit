#include "film/spektrafilm/PrintScan.h"
#include "spektrafilm-check-io.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>

using namespace filmkit::spektrafilm;

namespace {
template <class Range>
void read(std::istream& in, Range& range) {
    const auto bytes = std::as_writable_bytes(std::span(range));
    if (!in.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) {
        throw std::runtime_error("truncated print/scan fixture");
    }
}

SpectralProjection readProjection(std::istream& in) {
    SpectralProjection result;
    for (auto& band : result.bands) {
        std::array<double, 8> values;
        read(in, values);
        std::copy_n(values.begin(), 3, band.dyeDensity.begin());
        band.baseDensity = values[3];
        band.illuminant = values[4];
        std::copy_n(values.begin() + 5, 3, band.response.begin());
    }
    std::array<double, 7> values;
    read(in, values);
    std::copy_n(values.begin(), 3, result.gain.begin());
    std::copy_n(values.begin() + 3, 3, result.preflash.begin());
    result.normalizer = values[6];
    return result;
}

double compare(std::span<const Triplet> image, std::span<const Triplet> expected) {
    double maximum = 0.0;
    for (std::size_t i = 0; i < image.size(); ++i) {
        for (std::size_t c = 0; c < 3; ++c) {
            if (!std::isfinite(image[i][c]) || !std::isfinite(expected[i][c])) {
                throw std::runtime_error("nonfinite print/scan sample");
            }
            maximum = std::max(maximum, std::abs(image[i][c] - expected[i][c]));
        }
    }
    return maximum;
}
}  // namespace

int main(int argc, char** argv) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(sizeof(Triplet) == 3 * sizeof(double));
    if (argc < 2 || argc > 4) return 2;
    try {
        std::ifstream in(argv[1], std::ios::binary);
        std::array<std::uint32_t, 3> header;
        read(in, header);
        const auto [cases, width, height] = header;
        const std::size_t pixels = std::size_t(width) * height;
        if (!pixels || pixels > (argc == 4 ? 16000000 : 1000000) || cases != (argc == 4 ? 1 : 4)) return 2;
        std::vector<Triplet> film(pixels), work(pixels), expected(pixels);
        std::vector<double> glareField(pixels);
        std::vector<double> plane(pixels), filtered(pixels), scratch(pixels);
        read(in, film);
        if (argc >= 3) spektrafilm_check::load(argv[2], film);
        bool failed = false;
        for (std::size_t i = 0; i < cases; ++i) {
            std::array<std::uint32_t, 6> flags;
            read(in, flags);
            const auto [black, white, glare, knots, modelLayers, morph] = flags;
            if (knots < 2 || knots > 4096 || !modelLayers || modelLayers > 32) return 2;
            std::array<double, 23> data;
            read(in, data);
            const auto enlarger = readProjection(in);
            const auto scanner = readProjection(in);
            std::vector<double> axis(knots);
            std::vector<Triplet> stock(knots);
            read(in, axis);
            read(in, stock);
            PaperModel model;
            for (auto& channel : model) {
                for (std::size_t s = 0; s < modelLayers; ++s) {
                    Triplet layer;
                    read(in, layer);
                    channel.push_back({layer[0], layer[1], layer[2]});
                }
            }
            read(in, glareField);
            const ScanLevels levels{black != 0, white != 0, data[0], data[1]};
            const PaperGlare glareParams{glare != 0, data[4], data[5], data[6]};
            const auto refs = preparePrintReferences(enlarger, scanner, {data[7], data[8], data[9]},
                {data[10], data[11], data[12]}, axis, stock, levels);
            const auto curve = preparePaperDevelopment(axis, model,
                {morph != 0, data[13], data[14], data[15], {data[16], data[17], data[18]}, data[19]},
                EmulsionType::Negative);
            const auto backend = hasSpectralBackend(SpectralBackend::AdvSimd) ?
                SpectralBackend::AdvSimd : SpectralBackend::Scalar;
            if (!exposePaperRaw(enlarger, film, work, data[2], refs, backend)) return 2;
            read(in, expected);
            double logError = 0.0;
            for (std::size_t j = 0; j < pixels; ++j) {
                Triplet logExposure = work[j];
                for (auto& value : logExposure) value = std::log10(std::fmax(value, 0.0) + 1e-10);
                logError = std::max(logError, compare(std::span(&logExposure, 1), std::span(&expected[j], 1)));
            }
            if (!developPaper(work, curve)) return 2;
            read(in, expected);
            const double cmyError = compare(work, expected);
            if (!projectLogSpectral(scanner, work, work, backend)) return 2;
            read(in, expected);
            const double scanError = compare(work, expected);
            const auto logXyz = work;
            if (!finishPaperScanXYZ(scanner, work, width, height, refs, glareParams, 64, glareField)) return 2;
            read(in, expected);
            const double xyzError = compare(work, expected);
            work = logXyz;
            if (!finishPaperScanSrgb(scanner, work, width, height, refs, glareParams, 64,
                                     data[20], data[21], data[22], plane, filtered, scratch, glareField)) return 2;
            read(in, expected);
            const double rgbError = compare(work, expected);
            const double correctionError = std::abs(refs.exposureScale - data[3]);
            std::cout << std::setprecision(10) << "black=" << black << " white=" << white
                      << " max_print_correction_error=" << correctionError << " max_print_log_error=" << logError
                      << " max_paper_cmy_error=" << cmyError << " max_scan_log_error=" << scanError
                      << " max_xyz_error=" << xyzError << " max_encoded_rgb_error=" << rgbError << '\n';
            failed |= std::max({correctionError, logError, cmyError, scanError, xyzError, rgbError}) > 1e-10;
        }
        if (in.peek() != std::char_traits<char>::eof()) return 2;
        if (!failed && argc == 4) spektrafilm_check::save(argv[3], work);
        return failed;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 2;
    }
}
