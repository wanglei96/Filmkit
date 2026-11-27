#pragma once

#include <GLES3/gl3.h>
#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace filmkit::vkprobe {

// Diagnostic modes are compiled out of shipped engine builds.
bool mode(const char* name);
struct RcdOutput {
    GLuint dir = 0, green = 0;
    ~RcdOutput();
};
bool rcd(GLuint mosaic, GLuint dirFbo, GLuint greenFbo,
         int w, int h, int cfaLut, float greenOriginal, RcdOutput& output);

struct ScanJob {
    explicit ScanJob(bool tenBit);
    ~ScanJob();
    ScanJob(const ScanJob&) = delete;
    ScanJob& operator=(const ScanJob&) = delete;
    void close();
    bool tenBit;
    std::vector<std::uint8_t> bytes;
    GLuint hdrBase = 0; // GL texture name, deleted at the end of the still.
    static ScanJob* current();
private:
    ScanJob* previous_;
};

using Float4 = std::array<float,4>;
using Triplet = std::array<double,3>;
struct ScanParams {
    Float4 scanToRgb[3], adaptationFl, nbbZAwScale, view;
    std::span<const double,64> lightness;
    std::span<const double,46080> cmax;
    std::span<const std::byte> lutNodes;
    std::span<const uint8_t> lutMask;
    double blur, sharpen, amount;
};
void warmScan();
bool scan(std::span<const Triplet> xyz, int w, int h, const ScanParams& params, ScanJob& job);

}  // namespace filmkit::vkprobe
