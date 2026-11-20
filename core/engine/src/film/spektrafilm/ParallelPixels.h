#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstddef>
#include <future>

namespace filmkit::spektrafilm {

template <class Function>
bool parallelPixels(std::size_t pixels, std::size_t workers, Function operation,
                    std::size_t blockSize = 16384) {
    std::array<std::future<bool>, 7> pending;
    if (!workers || workers > pending.size() + 1 || !blockSize) return false;
    workers = std::min(workers, pixels);
    if (workers <= 1) return operation(0, pixels);
    std::atomic<std::size_t> next{0};
    const auto run = [&](std::size_t worker) {
        if ((pixels - 1) / blockSize < workers) {
            const auto begin = pixels * worker / workers;
            return operation(begin, pixels * (worker + 1) / workers - begin);
        }
        // Cores have different throughput; keep independent blocks available
        // until the whole stage is complete instead of waiting on a fixed stripe.
        for (;;) {
            const auto begin = next.fetch_add(blockSize, std::memory_order_relaxed);
            if (begin >= pixels) return true;
            if (!operation(begin, std::min(blockSize, pixels - begin))) return false;
        }
    };
    for (std::size_t i = 1; i < workers; ++i) pending[i - 1] = std::async(std::launch::async, run, i);
    bool ok = run(0);
    for (std::size_t i = 1; i < workers; ++i) ok = pending[i - 1].get() && ok;
    return ok;
}

template <class Function>
bool parallelPixelIndices(std::size_t pixels, std::size_t workers, Function operation) {
    return parallelPixels(pixels, workers, [&](std::size_t begin, std::size_t count) {
        for (std::size_t i = begin; i < begin + count; ++i) operation(i);
        return true;
    });
}

}  // namespace filmkit::spektrafilm
