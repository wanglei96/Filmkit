// Checks the native kernel against a little-endian fixture from the pinned reference.
#include "film/spektrafilm/Spectral.h"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <string_view>
#include <sys/resource.h>
#include <sys/stat.h>
#include <thread>
#include <type_traits>
#include <unistd.h>
#include <vector>

using namespace filmkit::spektrafilm;

namespace {
using Clock = std::chrono::steady_clock;
using Seconds = std::chrono::duration<double>;
struct Sample { Triplet input, expected; };
struct Result { double maxError{}, compute{}; std::size_t failed{}; };

bool readAt(int fd, void* data, std::size_t bytes, off_t offset) {
    auto* cursor = static_cast<unsigned char*>(data);
    while (bytes > 0) {
        const auto n = pread(fd, cursor, bytes, offset);
        if (n <= 0) return false;
        cursor += n;
        offset += n;
        bytes -= n;
    }
    return true;
}

double error(double got, double expected) {
    if (got == expected) return 0.0;
    if (!std::isfinite(got) || !std::isfinite(expected)) return INFINITY;
    return std::abs(got - expected);
}
}  // namespace

int main(int argc, char** argv) {
    static_assert(std::endian::native == std::endian::little);
    static_assert(std::is_trivially_copyable_v<SpectralProjection>);
    static_assert(sizeof(SpectralProjection) == (81 * 8 + 7) * sizeof(double));
    static_assert(sizeof(Sample) == 6 * sizeof(double));
    if (argc != 4) {
        std::fprintf(stderr, "usage: %s fixture scalar|simd workers\n", argv[0]);
        return 2;
    }
    const std::string_view mode(argv[2]);
    if (mode != "scalar" && mode != "simd") return 2;
    const auto backend = mode == "scalar" ? SpectralBackend::Scalar : SpectralBackend::AdvSimd;
    const int workers = std::atoi(argv[3]);
    if (!hasSpectralBackend(backend) || workers < 1 || workers > 8) return 2;

    constexpr off_t header = 3 * sizeof(std::uint32_t) + sizeof(SpectralProjection);
    std::uint32_t shape[3]{};
    SpectralProjection projection;
    struct stat st{};
    const int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { std::perror(argv[1]); return 2; }
    if (fstat(fd, &st) || st.st_size < header ||
        !readAt(fd, shape, sizeof(shape), 0) || shape[2] != projection.bands.size() ||
        !readAt(fd, &projection, sizeof(projection), sizeof(shape))) return 2;
    const auto pixels = std::uint64_t{shape[0]} * shape[1];
    const auto payload = std::uint64_t(st.st_size - header);
    if (!pixels || payload % sizeof(Sample) || pixels != payload / sizeof(Sample)) return 2;

    std::vector<Result> results(workers);
    std::vector<std::thread> threads;
    const auto start = Clock::now();
    for (int i = 0; i < workers; ++i) {
        threads.emplace_back([&, i] {
            constexpr std::size_t block = 4096;
            std::vector<Sample> samples(block);
            std::vector<Triplet> input(block), output(block);
            const auto begin = pixels * i / workers, end = pixels * (i + 1) / workers;
            auto& result = results[i];
            for (auto pos = begin; pos < end;) {
                const auto n = std::min<std::uint64_t>(block, end - pos);
                if (!readAt(fd, samples.data(), n * sizeof(Sample), header + pos * sizeof(Sample))) {
                    std::fprintf(stderr, "fixture read failed at pixel %llu\n", (unsigned long long)pos);
                    ++result.failed;
                    return;
                }
                for (std::size_t j = 0; j < n; ++j) input[j] = samples[j].input;
                const auto computeStart = Clock::now();
                const bool ok = projectLogSpectral(projection, {input.data(), n}, {output.data(), n}, backend);
                result.compute += Seconds(Clock::now() - computeStart).count();
                if (!ok) { ++result.failed; return; }
                for (std::size_t j = 0; j < n; ++j) {
                    for (int k = 0; k < 3; ++k) {
                        const double diff = error(output[j][k], samples[j].expected[k]);
                        result.maxError = std::max(result.maxError, diff);
                        result.failed += diff > 1e-12;
                    }
                }
                pos += n;
            }
        });
    }
    for (auto& thread : threads) thread.join();
    const double wall = Seconds(Clock::now() - start).count();
    close(fd);
    Result result{};
    for (const auto& r : results) {
        result.maxError = std::max(result.maxError, r.maxError);
        result.compute = std::max(result.compute, r.compute);
        result.failed += r.failed;
    }
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
#if defined(__APPLE__)
    usage.ru_maxrss /= 1024;
#endif
    std::printf("backend=%s workers=%d pixels=%llu wall_s=%.6f max_worker_compute_s=%.6f "
                "max_abs_error=%.9g failed=%zu peak_rss_kib=%ld\n", argv[2], workers,
                (unsigned long long)pixels, wall, result.compute, result.maxError,
                result.failed, usage.ru_maxrss);
    return result.failed != 0;
}
