#pragma once

#include "Spectral.h"

#include <cstdint>
#include <span>

namespace filmkit::spektrafilm {

struct PerceptualGamutTable {
    std::span<const double> lightness;
    std::span<const double> hue;
    std::span<const double> maximumChroma;
};

// Table layout is row-major [lightness][hue]. Hue wraps periodically.
bool lookupMaximumChroma(const PerceptualGamutTable& table, double lightness,
                         double hueRadians, double& result) noexcept;

double reinhardKnee(double distance, double threshold, double limit,
                    double power) noexcept;

// Normalized XYZ (Y=1 at reference white) and CAM16-UCS J'a'b'. The
// viewing conditions and adapting white match Spektrafilm's sRGB output.
bool xyzToCam16Ucs(Triplet xyz, Triplet& jab) noexcept;
bool cam16UcsToXyz(Triplet jab, Triplet& xyz) noexcept;

// Original Spektrafilm CAM16-UCS compression for linear sRGB.
bool compressSrgbCam16Ucs(Triplet linearRgb, Triplet& compressedRgb) noexcept;

// The descriptor may be closed after the call. Maps an immutable signed APK
// asset once per process, without reading its pages; on failure use exact CAM16.
bool installScanCam16Lut(int fd, std::int64_t offset, std::int64_t length) noexcept;

struct ScanOutputTimings {
    double cam16 = 0.0;
    std::size_t cam16LutHits = 0;
    double filters = 0.0;
    double encode = 0.0;
};

// Completes the scanner stage in place: scanner-white XYZ adaptation,
// output gamut compression, lens blur, unsharp, then optional sRGB encoding.
// The image and the three work planes must be disjoint. Discard the image on failure.
bool finishScanSrgb(std::span<Triplet> xyz, std::size_t width,
                    std::size_t height, Triplet scannerWhiteXyz,
                    double lensBlurSigma, double unsharpSigma,
                    double unsharpAmount, bool encodeSrgb,
                    std::span<double> plane,
                    std::span<double> filtered,
                    std::span<double> scratch,
                    std::size_t workerCount = 4, std::size_t filterWorkers = 1,
                    ScanOutputTimings* timings = nullptr, bool allowCam16Lut = false);

}  // namespace filmkit::spektrafilm
