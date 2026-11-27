#pragma once

#include "FilmExposure.h"

#include <functional>
#include <optional>

namespace filmkit::spektrafilm {

enum class HanatosVulkanMode { Off, CpuInput, RawReadback, SharedImage, SharedImageSimd };
HanatosVulkanMode hanatosVulkanMode();
bool supportsHanatosVulkan() noexcept;

struct HanatosReadback {
    std::size_t width;
    bool halfFloat;
    std::function<bool(std::size_t firstRow, std::size_t rows, void* destination)> read;
};

// Experimental: current GLES context must have the original RGBA16F source
// attached to its read FBO. The source is unchanged if sharing fails.
struct HanatosSharedSource {
    int width;
    int height;
    bool simd;
};

// Returns the converted prefix. The caller runs FP64 on the untouched suffix.
// nullopt indicates a rejected pixel, consistent with the CPU projection.
// A direct readback caller must repeat the original GL read if the whole
// frame was not converted, since its image started out uninitialized.
std::optional<std::size_t> projectHanatosVulkan(
    std::span<Triplet> pixels, const std::array<Triplet, 3>& xyzFromRgb,
    std::span<const Triplet> response, std::size_t side, double exposureGain,
    std::size_t workers, const HanatosReadback* readback = nullptr,
    const HanatosSharedSource* shared = nullptr);

}  // namespace filmkit::spektrafilm
