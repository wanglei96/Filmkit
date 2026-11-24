#pragma once

#include <array>
#include <cstddef>
#include <memory>
#include <span>

namespace filmkit::spektrafilm {

// Per-render storage only. Each consumer must overwrite a plane before reading it.
class SpatialWorkspace {
public:
    explicit SpatialWorkspace(std::size_t pixels) : pixels_(pixels) {}

    std::span<double> plane(std::size_t index) {
        auto& storage = planes_.at(index);
        if (!storage) storage = std::make_unique_for_overwrite<double[]>(pixels_);
        return {storage.get(), pixels_};
    }

    void release() { planes_ = {}; }
    std::size_t size() const { return pixels_; }

private:
    std::size_t pixels_;
    std::array<std::unique_ptr<double[]>, 4> planes_;
};

}  // namespace filmkit::spektrafilm
