#pragma once

#include "Render.h"

#include <cstddef>
#include <memory>
#include <string>

namespace filmkit::spektrafilm {

// V2 profile assets contain only configuration, never image/oracle/random data.
// pixelUm holds the film's long-side size in micrometres until instantiated.
std::shared_ptr<const NegativeRenderParams> loadNegativeProfile(
    std::span<const std::byte> bytes, std::string* error = nullptr,
    std::span<const std::byte> responseBytes = {});

bool installNegativeProfile(std::span<const std::byte> bytes, std::string* error = nullptr);
std::shared_ptr<const NegativeRenderParams> negativeProfile();

}  // namespace filmkit::spektrafilm
