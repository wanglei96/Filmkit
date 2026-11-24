#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <exception>
#include <future>
#include <mutex>
#include <utility>

namespace filmkit::spektrafilm {

// One render owns its workers. Independent callers and nested jobs never
// share a queue, and worker completion is a barrier between image stages.
class ScopedPixelWorkers {
public:
    explicit ScopedPixelWorkers(std::size_t workers) : previous_(current_), workers_(workers) {
        try {
            for (std::size_t i = 1; i < workers_; ++i) {
                threads_[i - 1] = std::async(std::launch::async, [this, i] { workerLoop(i); });
            }
        } catch (...) {
            stop();
            throw;
        }
        current_ = this;
    }

    ~ScopedPixelWorkers() {
        current_ = previous_;
        stop();
    }

    static ScopedPixelWorkers* current() { return current_; }
    bool supports(std::size_t workers) const { return workers <= workers_ && !running_; }

    template <class Function>
    bool run(std::size_t workers, const Function& function) {
        {
            std::lock_guard lock(mutex_);
            running_ = true;
            remaining_ = workers - 1;
            active_ = workers;
            job_ = &function;
            invoke_ = [](const void* job, std::size_t worker) {
                return (*static_cast<const Function*>(job))(worker);
            };
            ++generation_;
        }
        wake_.notify_all();
        execute(0);
        {
            std::unique_lock lock(mutex_);
            done_.wait(lock, [&] { return remaining_ == 0; });
            running_ = false;
        }
        bool ok = true;
        for (std::size_t i = 0; i < workers; ++i) {
            if (auto error = std::exchange(errors_[i], {})) std::rethrow_exception(error);
            ok = results_[i] && ok;
        }
        return ok;
    }

private:
    void execute(std::size_t worker) {
        try {
            results_[worker] = invoke_(job_, worker);
            errors_[worker] = nullptr;
        } catch (...) {
            errors_[worker] = std::current_exception();
        }
    }

    void workerLoop(std::size_t worker) {
        std::unique_lock lock(mutex_);
        std::size_t seen = 0;
        for (;;) {
            wake_.wait(lock, [&] { return stopping_ || generation_ != seen; });
            if (stopping_) return;
            seen = generation_;
            if (worker >= active_) continue;
            lock.unlock();
            execute(worker);
            lock.lock();
            if (--remaining_ == 0) done_.notify_one();
        }
    }

    void stop() {
        {
            std::lock_guard lock(mutex_);
            stopping_ = true;
        }
        wake_.notify_all();
        for (auto& thread : threads_) if (thread.valid()) thread.wait();
    }

    inline static thread_local ScopedPixelWorkers* current_ = nullptr;
    ScopedPixelWorkers* previous_;
    std::size_t workers_;
    std::mutex mutex_;
    std::condition_variable wake_, done_;
    std::array<std::future<void>, 7> threads_;
    std::array<std::exception_ptr, 8> errors_;
    std::array<bool, 8> results_;
    const void* job_ = nullptr;
    bool (*invoke_)(const void*, std::size_t) = nullptr;
    std::size_t generation_ = 0, active_ = 0, remaining_ = 0;
    bool stopping_ = false, running_ = false;
};

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
    if (auto* group = ScopedPixelWorkers::current(); group && group->supports(workers)) {
        return group->run(workers, run);
    }
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
