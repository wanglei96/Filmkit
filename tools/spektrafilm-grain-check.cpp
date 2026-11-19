#include "film/spektrafilm/Grain.h"
#include "spektrafilm-check-io.h"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <sys/resource.h>

using namespace filmkit::spektrafilm;

namespace {
template <class Range>
void read(std::istream& in, Range& values) {
    const auto bytes = std::as_writable_bytes(std::span(values));
    if (!in.read(reinterpret_cast<char*>(bytes.data()), bytes.size())) {
        throw std::runtime_error("truncated grain fixture");
    }
}

struct Error {
    double max = 0;
    std::size_t failed = 0;
    void check(double actual, double expected, double tolerance) {
        if (!std::isfinite(actual) || !std::isfinite(expected)) {
            ++failed;
            return;
        }
        const double diff = std::abs(actual - expected);
        max = std::max(max, diff);
        failed += diff > tolerance;
    }
};

struct Tape {
    std::size_t pixels, layers;
    double deviation;
    std::vector<double> mean, p;
    std::vector<std::uint64_t> draws;
    std::vector<std::uint32_t> compactDraws;
    std::vector<Triplet> clumps;
    Error meanError, pError, clumpError;
    std::size_t particleCalls = 0, clumpCalls = 0;
};

std::uint64_t particle(void* ctx, std::size_t c, std::size_t layer, std::size_t pixel,
                       double mean, double p) {
    auto& tape = *static_cast<Tape*>(ctx);
    const auto i = (c * tape.layers + layer) * tape.pixels + pixel;
    ++tape.particleCalls;
    if (!tape.compactDraws.empty()) return tape.compactDraws.at(i);
    tape.meanError.check(mean, tape.mean.at(i), 1e-11 * std::max(1.0, std::abs(tape.mean.at(i))));
    tape.pError.check(p, tape.p.at(i), 1e-12);
    return tape.draws.at(i);
}

double clumping(void* ctx, std::size_t c, std::size_t pixel, double deviation) {
    auto& tape = *static_cast<Tape*>(ctx);
    ++tape.clumpCalls;
    tape.clumpError.check(deviation, tape.deviation, 1e-14);
    return tape.clumps.at(pixel).at(c);
}

struct Statistics { double mean, sd, rhoX, rhoY; };

Statistics flatStatistics(std::span<const Triplet> image, std::size_t width, std::size_t height,
                          std::size_t patch, std::size_t channel, double nominal) {
    const std::size_t patchWidth = width / 3;
    double sum = 0.0, sum2 = 0.0, lagX = 0.0, lagY = 0.0;
    std::size_t samples = 0;
    for (std::size_t y = 16; y < height - 16; ++y) {
        for (std::size_t x = patch * patchWidth + 16; x < (patch + 1) * patchWidth - 16; ++x) {
            const auto i = y * width + x;
            const double d = image[i][channel] - nominal;
            sum += d;
            sum2 += d * d;
            lagX += d * (image[i + 1][channel] - nominal);
            lagY += d * (image[i + width][channel] - nominal);
            ++samples;
        }
    }
    const double mean = sum / static_cast<double>(samples);
    const double variance = sum2 / static_cast<double>(samples) - mean * mean;
    return {nominal + mean, std::sqrt(variance),
            (lagX / static_cast<double>(samples) - mean * mean) / variance,
            (lagY / static_cast<double>(samples) - mean * mean) / variance};
}
}  // namespace

int main(int argc, char** argv) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(sizeof(Triplet) == 3 * sizeof(double));
    static_assert(sizeof(GrainLayers) == 9 * sizeof(double));
    if (argc < 2 || argc > 4) return 2;
    try {
        const auto workers = argc == 3 ? std::stoul(argv[2]) : 1;
        std::ifstream in(argv[1], std::ios::binary);
        std::array<std::uint32_t, 8> header;
        read(in, header);
        const auto [widthFlags, height, knots, positive, active, layered, singleLayers, clumps] = header;
        const bool full = (widthFlags >> 31) != 0;
        const bool flat = ((widthFlags >> 30) & 1) != 0;
        const bool countsOnly = ((widthFlags >> 29) & 1) != 0;
        const auto width = widthFlags & 0x1fffffffU;
        const auto pixels = std::size_t(width) * height;
        if (!pixels || pixels > (full || countsOnly ? 16000000 : 1000000) || knots < 2 || knots > 4096 || positive > 1 || active > 1 ||
            (countsOnly && (argc != 4 || full || flat || !active || !layered || clumps)) ||
            layered > 1 || clumps > 1 || singleLayers > 64 || (!layered && !singleLayers) ||
            (flat && (full || width != 1536 || height != 512))) return 2;
        std::array<double, 18> data;
        read(in, data);
        GrainParams p;
        p.active = active;
        p.layered = layered;
        p.singleModelLayers = singleLayers;
        p.particleAreaUm2 = data[1];
        std::copy_n(data.begin() + 2, 3, p.particleScale.begin());
        std::copy_n(data.begin() + 5, 3, p.layerScale.begin());
        std::copy_n(data.begin() + 8, 3, p.densityFloor.begin());
        std::copy_n(data.begin() + 11, 3, p.uniformity.begin());
        p.finalBlurPx = data[14];
        p.dyeCloudBlur = data[15];
        p.microBlurUm = data[16];
        p.microDeviationNm = data[17];
        std::vector<Triplet> total(knots), input(pixels), expected(full ? 0 : pixels), output(pixels);
        std::vector<GrainLayers> layers(knots);
        read(in, total);
        read(in, layers);
        read(in, input);
        if (!full) read(in, expected);

        Tape tape{};
        tape.pixels = pixels;
        tape.layers = layered ? 3 : singleLayers;
        tape.deviation = p.microDeviationNm * 0.001 / data[0];
        const auto entries = active && !full && !flat ? pixels * 3 * tape.layers : 0;
        tape.mean.resize(countsOnly ? 0 : entries);
        tape.p.resize(countsOnly ? 0 : entries);
        tape.draws.resize(countsOnly ? 0 : entries);
        tape.compactDraws.resize(countsOnly ? entries : 0);
        tape.clumps.resize(clumps ? pixels : 0);
        if (entries && !countsOnly) {
            read(in, tape.mean);
            read(in, tape.p);
        }
        if (clumps) read(in, tape.clumps);
        if (countsOnly) read(in, tape.compactDraws);
        else if (entries) read(in, tape.draws);
        if (in.peek() != std::char_traits<char>::eof()) return 2;
        if (argc == 4) {
            if (full || flat) return 2;
            spektrafilm_check::load(argv[2], input);
        }

        const auto type = positive ? EmulsionType::Positive : EmulsionType::Negative;
        const GrainReplay replay{&tape, particle, clumping};
        Error density;
        if (!full && !flat) {
            if (!applyFilmGrain(input, output, width, height, data[0], total, layers, type, p, 63, &replay)) {
                throw std::runtime_error("rejected grain fixture");
            }
            for (std::size_t i = 0; i < pixels; ++i) {
                for (std::size_t c = 0; c < 3; ++c) density.check(output[i][c], expected[i][c], 1e-10);
            }
        }
        if (argc == 4) {
            const auto failed = density.failed + tape.meanError.failed + tape.pError.failed + tape.clumpError.failed +
                (tape.particleCalls != entries) + (tape.clumpCalls != 3 * tape.clumps.size());
            std::cout << std::setprecision(10) << "pixels=" << pixels << " draws=" << tape.particleCalls
                      << " max_density_error=" << density.max;
            if (!countsOnly) std::cout << " max_poisson_mean_error=" << tape.meanError.max
                                      << " max_binomial_p_error=" << tape.pError.max;
            std::cout << " failed=" << failed << '\n';
            if (!failed) spektrafilm_check::save(argv[3], output);
            return failed != 0;
        }
        GrainTimings timings;
        const auto before = std::chrono::steady_clock::now();
        if (!applyFilmGrain(input, output, width, height, data[0], total, layers, type, p, 63,
                            nullptr, full ? &timings : nullptr, workers)) return 2;
        const std::chrono::duration<double> elapsed = std::chrono::steady_clock::now() - before;
        std::size_t nativeNonfinite = 0;
        for (const auto& pixel : output) for (const double value : pixel) nativeNonfinite += !std::isfinite(value);
        if (flat) {
            Error mean, deviation, rho;
            for (std::size_t patch = 0; patch < 3; ++patch) {
                for (std::size_t c = 0; c < 3; ++c) {
                    const double nominal = input[patch * (width / 3)][c];
                    const auto ref = flatStatistics(expected, width, height, patch, c, nominal);
                    const auto actual = flatStatistics(output, width, height, patch, c, nominal);
                    mean.check((actual.mean - ref.mean) / ref.sd, 0.0, 0.025);
                    deviation.check(actual.sd / ref.sd, 1.0, 0.03);
                    rho.check(actual.rhoX, ref.rhoX, 0.03);
                    rho.check(actual.rhoY, ref.rhoY, 0.03);
                }
            }
            const auto failed = nativeNonfinite + mean.failed + deviation.failed + rho.failed;
            std::cout << std::setprecision(10) << "flat_pixels=" << pixels << " max_mean_delta_over_ref_sd=" << mean.max
                      << " max_relative_sd_error=" << deviation.max << " max_lag1_rho_error=" << rho.max
                      << " failed=" << failed << '\n';
            return failed != 0;
        }
        const auto failed = density.failed + tape.meanError.failed + tape.pError.failed + tape.clumpError.failed +
            nativeNonfinite + (tape.particleCalls != entries) + (tape.clumpCalls != 3 * tape.clumps.size());
        rusage usage{};
        getrusage(RUSAGE_SELF, &usage);
#if defined(__APPLE__)
        usage.ru_maxrss /= 1024;
#endif
        if (full) {
            std::uint64_t hash = 14695981039346656037ULL;
            for (const auto& pixel : output) {
                for (const double value : pixel) hash = (hash ^ std::bit_cast<std::uint64_t>(value)) * 1099511628211ULL;
            }
            std::cout << std::setprecision(10) << "full_pixels=" << pixels << " native_compute_s=" << elapsed.count()
                      << " interpolate_and_sample_s=" << timings.particlesSeconds
                      << " dye_cloud_and_sum_s=" << timings.dyeCloudSeconds << " finish_s=" << timings.finishSeconds
                      << " peak_rss_kib=" << usage.ru_maxrss << " nonfinite=" << nativeNonfinite
                      << " workers=" << workers << " output_checksum=" << std::hex << hash << '\n';
            return nativeNonfinite != 0;
        }
        std::cout << std::setprecision(10) << "pixels=" << pixels << " draws=" << tape.particleCalls
                  << " clumps=" << tape.clumpCalls << " max_density_error=" << density.max
                  << " max_poisson_mean_error=" << tape.meanError.max << " max_binomial_p_error=" << tape.pError.max
                  << " native_compute_s=" << elapsed.count() << " peak_rss_kib=" << usage.ru_maxrss
                  << " failed=" << failed << '\n';
        return failed != 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 2;
    }
}
