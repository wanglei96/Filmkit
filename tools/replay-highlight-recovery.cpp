// Offline production-CPU replay. No alternate guide, flow or admission algorithm.
// Build: c++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
// -Icore/engine/src/render tools/replay-highlight-recovery.cpp \
// core/engine/src/render/{HighlightRecovery,HighlightGeometry,Align}.cpp \
// -o /tmp/replay-highlight-recovery
// Usage: /tmp/replay-highlight-recovery INPUT_DIR OUT_DIR --pyramids GPU_PYR_DIR
// GPU_PYR_DIR must contain f{0..3}-l{1..4}.u8: tight single-channel production
// kFsMfnrPyr/Down RGBA8 R readback, in native GL row order (no vertical flip).
#include "HighlightGeometry.h"
#include "HighlightRecovery.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;
using namespace filmkit;
namespace {
using Clock = std::chrono::steady_clock;
struct Options {
  fs::path input, output, pyramids;
  std::array<float, 3> wb{{2.09375f, 1.f, 1.787109f}};
  std::array<float, 4> black{{64.f, 64.f, 64.f, 64.f}};
  std::array<float, 4> scales{{1.f, 1.f, .21f, 2.82f}};
  std::array<float, 4> gain_ratios{{1.f, 1.f, 100.f / 72.f, 1.f}};
  std::array<float, 3> shot{{6.752e-5f, 6.9818e-5f, 4.9453e-5f}};
  std::array<float, 3> read{{4.8842e-7f, 2.9961e-7f, 2.9363e-7f}};
  float white = 1023.f;
  unsigned cfa = 0x61;  // Both captured fixtures are native GRBG, verified by DNG.
  int short_index = -1;
};

std::vector<float> csv(const std::string& text) {
  std::stringstream stream(text);
  std::string item;
  std::vector<float> out;
  while (std::getline(stream, item, ',')) {
    size_t consumed = 0;
    const float v = std::stof(item, &consumed);
    if (consumed != item.size() || !std::isfinite(v)) throw std::runtime_error("invalid CSV: " + text);
    out.push_back(v);
  }
  return out;
}
template <size_t N>
void setCsv(std::array<float, N>* out, const std::string& value, bool scalar = false) {
  const auto values = csv(value);
  if (scalar && values.size() == 1) { out->fill(values[0]); return; }
  if (values.size() != N) throw std::runtime_error("wrong CSV component count: " + value);
  std::copy(values.begin(), values.end(), out->begin());
}
Options options(int argc, char** argv) {
  if (argc < 3) throw std::runtime_error(
      "usage: replay-highlight-recovery INPUT_DIR OUT_DIR --pyramids DIR "
      "[--wb R,G,B --black B[,B,B,B] --white CODE --cfa LUT "
      "--scales F0,F1,F2,F3 --gain-ratios F0,F1,F2,F3 "
      "--shot R,G,B --read R,G,B --short-index 1|2|3]");
  Options o;
  o.input = fs::absolute(argv[1]); o.output = fs::absolute(argv[2]);
  for (int i = 3; i < argc; i += 2) {
    if (i + 1 >= argc) throw std::runtime_error("missing option value");
    const std::string key = argv[i], value = argv[i + 1];
    if (key == "--pyramids") o.pyramids = fs::absolute(value);
    else if (key == "--wb") setCsv(&o.wb, value);
    else if (key == "--black") setCsv(&o.black, value, true);
    else if (key == "--scales") setCsv(&o.scales, value);
    else if (key == "--gain-ratios") setCsv(&o.gain_ratios, value);
    else if (key == "--shot") setCsv(&o.shot, value);
    else if (key == "--read") setCsv(&o.read, value);
    else if (key == "--white") o.white = std::stof(value);
    else if (key == "--cfa") o.cfa = static_cast<unsigned>(std::stoul(value, nullptr, 0));
    else if (key == "--short-index") o.short_index = std::stoi(value);
    else throw std::runtime_error("unknown option: " + key);
  }
  if (o.pyramids.empty()) throw std::runtime_error("--pyramids DIR is required: use exact production GPU readbacks");
  for (float v : o.wb) if (!(v > 0.f)) throw std::runtime_error("WB must be positive");
  for (float v : o.scales) if (!(v > 0.f)) throw std::runtime_error("scales must be positive");
  for (float v : o.gain_ratios) if (!(v > 0.f)) throw std::runtime_error("gain ratios must be positive");
  for (float v : o.shot) if (!(v >= 0.f)) throw std::runtime_error("shot must be nonnegative");
  for (float v : o.read) if (!(v > 0.f)) throw std::runtime_error("read must be positive");
  if (o.short_index == -1) {
    o.short_index = 1;
    for (int i = 2; i < 4; ++i) if (o.scales[i] > o.scales[o.short_index]) o.short_index = i;
  }
  if (o.short_index < 1 || o.short_index > 3) throw std::runtime_error("short index must be 1..3");
  if (fs::equivalent(o.input, o.pyramids)) throw std::runtime_error("GPU pyramid directory must be separate from RAW input");
  return o;
}

std::string text(const fs::path& path) {
  std::ifstream in(path);
  if (!in) throw std::runtime_error("cannot open: " + path.string());
  return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}
int metaInt(const std::string& json, const std::string& key) {
  std::smatch m;
  if (!std::regex_search(json, m, std::regex("\\\"" + key + "\\\"\\s*:\\s*([0-9]+)")))
    throw std::runtime_error("missing metadata field " + key);
  return std::stoi(m[1]);
}
template <typename T>
std::vector<T> load(const fs::path& path, size_t count) {
  if (count > std::numeric_limits<size_t>::max() / sizeof(T) ||
      fs::file_size(path) != count * sizeof(T))
    throw std::runtime_error("binary shape/byte-count mismatch: " + path.string());
  std::vector<T> data(count);
  std::ifstream in(path, std::ios::binary);
  if (!in.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(count * sizeof(T))))
    throw std::runtime_error("cannot read: " + path.string());
  return data;
}
template <typename T>
void save(const fs::path& path, const std::vector<T>& data) {
  std::ofstream out(path, std::ios::binary | std::ios::trunc);
  if (!out.write(reinterpret_cast<const char*>(data.data()),
                 static_cast<std::streamsize>(data.size() * sizeof(T))))
    throw std::runtime_error("cannot write: " + path.string());
}
std::string escaped(const std::string& s) {
  std::string out;
  for (unsigned char c : s) {
    if (c == '\\' || c == '"') { out += '\\'; out += static_cast<char>(c); }
    else if (c < 32) throw std::runtime_error("control character in JSON path");
    else out += static_cast<char>(c);
  }
  return out;
}
template <size_t N>
std::string jsonArray(const std::array<float, N>& values) {
  std::ostringstream o;
  o << std::setprecision(10) << '[';
  for (size_t i = 0; i < N; ++i) { if (i) o << ','; o << values[i]; }
  return o.str() + ']';
}
float milliseconds(Clock::time_point start) {
  return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}
size_t nonzero(const std::vector<uint8_t>& mask) {
  return static_cast<size_t>(std::count_if(mask.begin(), mask.end(), [](uint8_t v) { return v != 0; }));
}
float meanMask(const std::vector<uint8_t>& mask) {
  double total = 0;
  for (uint8_t v : mask) total += v;
  return mask.empty() ? 0.f : static_cast<float>(total / (255. * mask.size()));
}
const char* geometryName(HighlightGeometryStatus s) {
  switch (s) {
    case HighlightGeometryStatus::InvalidInput: return "invalid_input";
    case HighlightGeometryStatus::NoObservableSupport: return "no_observable_support";
    case HighlightGeometryStatus::NoHighlights: return "no_highlights";
    case HighlightGeometryStatus::Resolved: return "resolved";
  }
  return "invalid_input";
}
std::vector<float> packFlow(const std::vector<float>& xy, size_t tiles) {
  if (xy.size() != tiles * 2) throw std::runtime_error("cannot pack malformed flow");
  std::vector<float> rgba(tiles * 4, 1.f);
  for (size_t t = 0; t < tiles; ++t) { rgba[t * 4] = xy[t * 2]; rgba[t * 4 + 1] = xy[t * 2 + 1]; }
  return rgba;
}
MfnrPyramid readPyramid(const fs::path& dir, int frame, int w, int h) {
  MfnrPyramid p;
  p.w1 = (w + 1) / 2; p.h1 = (h + 1) / 2;
  p.w2 = (p.w1 + 1) / 2; p.h2 = (p.h1 + 1) / 2;
  p.w3 = (p.w2 + 1) / 2; p.h3 = (p.h2 + 1) / 2;
  p.w4 = (p.w3 + 1) / 2; p.h4 = (p.h3 + 1) / 2;
  const std::string prefix = "f" + std::to_string(frame) + "-l";
  p.l1 = load<uint8_t>(dir / (prefix + "1.u8"), static_cast<size_t>(p.w1) * p.h1);
  p.l2 = load<uint8_t>(dir / (prefix + "2.u8"), static_cast<size_t>(p.w2) * p.h2);
  p.l3 = load<uint8_t>(dir / (prefix + "3.u8"), static_cast<size_t>(p.w3) * p.h3);
  p.l4 = load<uint8_t>(dir / (prefix + "4.u8"), static_cast<size_t>(p.w4) * p.h4);
  return p;
}
}  // namespace

int main(int argc, char** argv) {
  try {
    const Options o = options(argc, argv);
    const auto start = Clock::now();
    std::array<std::vector<uint16_t>, 4> raws;
    std::array<HighlightRawView, 4> views;
    std::array<MfnrPyramid, 4> pyramids;
    int w = 0, h = 0;
    for (int i = 0; i < 4; ++i) {
      const std::string name = "f" + std::to_string(i);
      const auto meta = text(o.input / "burst" / (name + ".meta"));
      const int wi = metaInt(meta, "w"), hi = metaInt(meta, "h"), stride = metaInt(meta, "stridePx");
      if (wi < 8 || hi < 8 || stride < wi || metaInt(meta, "slot") != i)
        throw std::runtime_error("invalid RAW metadata for " + name);
      if (i && (wi != w || hi != h)) throw std::runtime_error("burst size mismatch");
      w = wi; h = hi;
      raws[i] = load<uint16_t>(o.input / "burst" / (name + ".raw"), static_cast<size_t>(stride) * h);
      views[i] = {raws[i].data(), w, h, stride, o.black, o.white, o.cfa};
      pyramids[i] = readPyramid(o.pyramids, i, w, h);
    }
    fs::create_directories(o.output);
    const float input_ms = milliseconds(start);
    const int coarse_w = (pyramids[0].w1 + kMfnrTile - 1) / kMfnrTile;
    const int coarse_h = (pyramids[0].h1 + kMfnrTile - 1) / kMfnrTile;
    auto phase = Clock::now();
    for (int i = 1; i < 4; ++i) {
      const auto a = mfnrAlign(pyramids[0], pyramids[i], coarse_w, coarse_h, true);
      save(o.output / ("coarse-flow-" + std::to_string(i) + ".rgba32f"),
           packFlow(a.off, static_cast<size_t>(coarse_w) * coarse_h));
    }
    const float coarse_ms = milliseconds(phase);
    auto noiseFor = [&](int frame) {
      HighlightNoise noise;
      for (int p = 0; p < 4; ++p) {
        const unsigned c = (o.cfa >> (p * 2)) & 3u;
        if (c > 2) throw std::runtime_error("invalid CFA LUT");
        noise.shot[p] = o.shot[c] * o.gain_ratios[frame];
        noise.read[p] = o.read[c] * o.gain_ratios[frame] * o.gain_ratios[frame];
      }
      return noise;
    };
    phase = Clock::now();
    HighlightGuideSet guides;
    std::string error;
    if (!highlightGenerateGuides(views[0], views[o.short_index], o.wb,
                                  o.scales[0], o.scales[o.short_index],
                                  noiseFor(0), noiseFor(o.short_index), &guides, &error))
      throw std::runtime_error("production guide generation: " + error);
    const float guides_ms = milliseconds(phase);
    const int fine_stride = 8;
    const int fine_w = (guides.base.width + fine_stride - 1) / fine_stride;
    const int fine_h = (guides.base.height + fine_stride - 1) / fine_stride;
    const size_t fine_tiles = static_cast<size_t>(fine_w) * fine_h;
    const HighlightGeometryGrid grid{guides.base.width, guides.base.height,
                                    fine_w, fine_h, fine_stride, .5f};
    phase = Clock::now();
    const auto bidirectional = highlightAlignBidirectional(
        pyramids[0], pyramids[o.short_index], fine_w, fine_h, true, fine_stride);
    if (!bidirectional.valid) throw std::runtime_error("production bidirectional alignment rejected GPU pyramid/grid");
    const float fine_ms = milliseconds(phase);
    phase = Clock::now();
    std::vector<HighlightTileSupport> support;
    if (!highlightObservableSupport(grid, guides.base.rgb, guides.short_normalized.rgb,
                                     guides.highlight, bidirectional.forward.off,
                                     bidirectional.reverse.off, &support))
      throw std::runtime_error("production support failed");
    const auto geometry = highlightResolveGeometry(grid, guides.highlight,
                                                    bidirectional.forward.off, support);
    const float support_ms = milliseconds(phase);
    HighlightFlowView flow;
    if (geometry.usable()) flow = {geometry.flow.data(), fine_w, fine_h, fine_stride * 2};
    phase = Clock::now();
    HighlightMaskAssessment assessment;
    if (!highlightAssessMasks(views[o.short_index], guides, flow, geometry.usable(), &assessment, &error))
      throw std::runtime_error("production mask assessment: " + error);
    std::vector<uint8_t> selected_quarter, linear_quarter;
    if (!highlightBuildMergeMasks(assessment.adjusted, guides.base.width, guides.base.height,
                                  w, h, assessment.accepted(), &selected_quarter,
                                  &linear_quarter, &error))
      throw std::runtime_error("production RAW/4 merge mask conversion: " + error);
    const float masks_ms = milliseconds(phase);
    phase = Clock::now();
    save(o.output / "fine-flow.rgba32f",
         packFlow(assessment.accepted() ? geometry.flow : std::vector<float>(fine_tiles * 2, 0.f), fine_tiles));
    save(o.output / "forward-measured.rgba32f", packFlow(bidirectional.forward.off, fine_tiles));
    save(o.output / "reverse-measured.rgba32f", packFlow(bidirectional.reverse.off, fine_tiles));
    std::vector<float> support_rgba(fine_tiles * 4);
    for (size_t t = 0; t < fine_tiles; ++t) {
      support_rgba[t * 4] = support[t].confidence;
      support_rgba[t * 4 + 1] = support[t].fb_error;
      support_rgba[t * 4 + 2] = support[t].valid_fraction;
      support_rgba[t * 4 + 3] = support[t].clipped_fraction;
    }
    save(o.output / "support.rgba32f", support_rgba);
    save(o.output / "highlight-half.r8", guides.highlight);
    save(o.output / "adjusted-half.r8", assessment.adjusted);
    save(o.output / "inpainting-half.r8", assessment.inpainting);
    save(o.output / "short-clipping-half.r8", assessment.short_clipping);
    save(o.output / "normalized-error-half.f32", assessment.normalized_error);
    save(o.output / "mask-quarter.r8", selected_quarter);
    save(o.output / "mask-quarter-linear.r8", linear_quarter);

    // Exact MgcSpatialMergeTuning.maximumMergeWeight and kernelSigma for the
    // no-motion-metadata exponent=1 path. Positive-read validation is above.
    // RGB noise scales by ISO ratio/ratio^2, then WB^2 for shadow luma (gain
    // cancels in inter-frame ratio because all frames share source WB).
    // PC MgcSpatialMergeTuning.baseSpatialScale: full Bayer curve, merged
    // reference SNR at the host's representative green sensor signal=0.18.
    const float signal = .18f;
    const float reference_snr = signal / std::sqrt(std::max(
        o.shot[1] * signal + o.read[1], 1.e-12f));
    const float merged_snr = reference_snr * std::sqrt(4.f);
    const auto interpolate = [](float x, float x0, float y0, float x1, float y1) {
      const float t = std::max(0.f, std::min(1.f, (x - x0) / (x1 - x0)));
      return y0 + t * (y1 - y0);
    };
    const float base_scale = merged_snr <= 29.5f
        ? interpolate(merged_snr, 14.5f, .6f, 29.5f, .42f)
        : interpolate(merged_snr, 29.5f, .42f, 44.f, .35f);
    std::array<float, 4> global_weight{{1.f, 1.f, 1.f, 1.f}}, kernel_sigma;
    for (int i = 1; i < 4; ++i) {
      const float ratio = o.gain_ratios[i] / o.gain_ratios[0];
      const float exposure = o.scales[i] / o.scales[0];
      global_weight[i] = std::min(50.f, 1.f / (ratio * ratio * exposure * exposure));
    }
    // Host excludes the selected-short candidate from temporal merge on whole-
    // frame rejection; it must not sneak back via its ordinary coarse slice.
    global_weight[o.short_index] = assessment.accepted() ? 1.f : 0.f;
    for (int i = 0; i < 4; ++i) {
      const float amount = std::max(0.f, std::min(1.f, (global_weight[i] - 10.f) / 20.f));
      const float multiplier = 1.f + amount * (1.414000034332275f - 1.f);
      kernel_sigma[i] = 1.f / (base_scale * multiplier);
    }
    std::array<float, 3> camera_white{{1.f / o.wb[0], 1.f / o.wb[1], 1.f / o.wb[2]}};
    const float max_white = *std::max_element(camera_white.begin(), camera_white.end());
    for (float& v : camera_white) v /= max_white;
    const float output_ms = milliseconds(phase);
    const float total_ms = milliseconds(start);
    const size_t support_count = static_cast<size_t>(std::count_if(support.begin(), support.end(),
        [](const HighlightTileSupport& s) { return s.confidence >= .4f; }));
    std::ostringstream json;
    json << std::setprecision(10)
         << "{\n  \"input\":\"" << escaped(o.input.string()) << "\",\n"
         << "  \"pyramids\":\"" << escaped(o.pyramids.string()) << "\",\n"
         << "  \"pyramid_source\":\"production_kFsMfnrPyr_kFsMfnrDown_GPU_RGBA8_R\",\n"
         << "  \"solver\":\"existing_HDRplus_SAD_not_PC_LK\",\n"
         << "  \"w\":" << w << ",\"h\":" << h
         << ",\"guide_w\":" << guides.base.width << ",\"guide_h\":" << guides.base.height
         << ",\"fine_w\":" << fine_w << ",\"fine_h\":" << fine_h
         << ",\"fine_stride_raw\":16,\"coarse_w\":" << coarse_w << ",\"coarse_h\":" << coarse_h
         << ",\"coarse_stride_raw\":128,\"mask_w\":" << w / 4
         << ",\"mask_h\":" << h / 4 << ",\n"
         << "  \"binary_order\":\"native_little_endian_row_major_no_y_flip\",\n"
         << "  \"flow_units\":\"native_mosaic_pixels\",\"flow_rgba\":\"dx,dy,1,1\",\n"
         << "  \"wb\":" << jsonArray(o.wb) << ",\"black\":" << jsonArray(o.black)
         << ",\"white\":" << o.white << ",\"cfa_lut\":" << o.cfa << ",\n"
         << "  \"scales\":" << jsonArray(o.scales) << ",\"gain_ratios\":" << jsonArray(o.gain_ratios)
         << ",\"noise_shot_rgb\":" << jsonArray(o.shot) << ",\"noise_read_rgb\":" << jsonArray(o.read) << ",\n"
         << "  \"reference_signal\":" << signal << ",\"reference_snr\":" << reference_snr
         << ",\"merged_snr\":" << merged_snr << ",\"base_spatial_scale\":" << base_scale
         << ",\"base_scale_source\":\"PC_full_BAYER_curve_at_merged_green_sensor_SNR\",\n"
         << "  \"global_weight\":" << jsonArray(global_weight)
         << ",\"kernel_sigma\":" << jsonArray(kernel_sigma)
         << ",\"camera_white\":" << jsonArray(camera_white)
         << ",\"camera_white_source\":\"AsShotNeutral_from_capture_WB\",\n"
         << "  \"candidate_short_index\":" << o.short_index
         << ",\"short_index\":" << (assessment.accepted() ? o.short_index : -1)
         << ",\"accepted\":" << (assessment.accepted() ? "true" : "false")
         << ",\"admission\":\"" << highlightAdmissionName(assessment.status)
         << "\",\"geometry\":\"" << geometryName(geometry.status) << "\",\n"
         << "  \"support_tiles_threshold_0_4\":" << support_count
         << ",\"supported_exterior_tiles\":" << geometry.supported_tiles
         << ",\"highlight_tiles\":" << geometry.highlight_tiles
         << ",\"regions\":" << geometry.regions << ",\"collar_regions\":" << geometry.collar_regions
         << ",\"global_regions\":" << geometry.global_regions << ",\n"
         << "  \"highlight_pixels\":" << nonzero(guides.highlight)
         << ",\"adjusted_pixels\":" << nonzero(assessment.adjusted)
         << ",\"inpainting_pixels\":" << nonzero(assessment.inpainting)
         << ",\"largest_inpainting_area\":" << assessment.largest_inpainting_area
         << ",\"short_clipping_overlap\":" << assessment.short_clipping_overlap << ",\n"
         << "  \"mask_quarter_nonzero\":" << nonzero(selected_quarter)
         << ",\"mask_quarter_mean\":" << meanMask(selected_quarter)
         << ",\"linear_quarter_nonzero\":" << nonzero(linear_quarter) << ",\n"
         << "  \"timing_ms\":{\"input\":" << input_ms << ",\"coarse_align\":" << coarse_ms
         << ",\"guides\":" << guides_ms << ",\"fine_align\":" << fine_ms
         << ",\"support_geometry\":" << support_ms << ",\"masks_admission\":" << masks_ms
         << ",\"output\":" << output_ms << ",\"total\":" << total_ms << "}\n}\n";
    std::ofstream metadata(o.output / "metadata.json");
    if (!(metadata << json.str())) throw std::runtime_error("cannot write metadata JSON");
    std::cout << json.str();
    return 0;  // Valid rejection is a replay result, not a tool error.
  } catch (const std::exception& e) {
    std::cerr << "replay-highlight-recovery: " << e.what() << '\n';
    return 1;
  }
}
