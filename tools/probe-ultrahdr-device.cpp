// Decode an actual JPEG/R with production libultrahdr at 1x/2x/4x display headroom.
// Link libuhdr_core.a, libuhdr_image_io.a, libjpeg.a, -lz -llog (NDK, C++20).
#include "../core/engine/third_party/ultrahdr/ultrahdr_api.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
void check(uhdr_error_info_t status) {
  if (status.error_code != UHDR_CODEC_OK)
    throw std::runtime_error(status.has_detail ? status.detail : "libultrahdr error");
}
void require(bool ok, const char* message) {
  if (!ok) throw std::runtime_error(message);
}
using Decoder = std::unique_ptr<uhdr_codec_private_t, decltype(&uhdr_release_decoder)>;
Decoder decoder(std::vector<char>& data, bool hdr, float boost) {
  Decoder dec(uhdr_create_decoder(), uhdr_release_decoder);
  require(bool(dec), "decoder allocation failed");
  uhdr_compressed_image_t input{};
  input.data = data.data();
  input.data_sz = input.capacity = data.size();
  check(uhdr_dec_set_image(dec.get(), &input));
  check(uhdr_dec_set_out_img_format(dec.get(), hdr ? UHDR_IMG_FMT_64bppRGBAHalfFloat :
                                  UHDR_IMG_FMT_32bppRGBA8888));
  check(uhdr_dec_set_out_color_transfer(dec.get(), hdr ? UHDR_CT_LINEAR : UHDR_CT_SRGB));
  check(uhdr_dec_set_out_max_display_boost(dec.get(), boost));
  check(uhdr_dec_probe(dec.get()));
  return dec;
}
double linear(double v) {
  v /= 255.;
  return v <= .04045 ? v / 12.92 : std::pow((v + .055) / 1.055, 2.4);
}
}
int main(int argc, char** argv) {
  try {
    require(argc == 2, "usage: probe-ultrahdr-device image.jpg");
    std::ifstream input(argv[1], std::ios::binary);
    require(bool(input), "cannot open JPEG/R");
    std::vector<char> data(std::istreambuf_iterator<char>(input), {});
    auto sdr = decoder(data, false, 1.f);
    const auto* meta = uhdr_dec_get_gainmap_metadata(sdr.get());
    require(meta != nullptr, "missing gainmap metadata");
    std::printf("METADATA capacity=%.9g,%.9g baseCg=%d size=%dx%d gainmap=%dx%d\n",
        meta->hdr_capacity_min, meta->hdr_capacity_max, meta->use_base_cg,
        uhdr_dec_get_image_width(sdr.get()), uhdr_dec_get_image_height(sdr.get()),
        uhdr_dec_get_gainmap_width(sdr.get()), uhdr_dec_get_gainmap_height(sdr.get()));
    for (int c = 0; c < 3; ++c)
      std::printf("CHANNEL c=%d min=%.9g max=%.9g gamma=%.9g offsetSdr=%.9g offsetHdr=%.9g\n",
          c, meta->min_content_boost[c], meta->max_content_boost[c], meta->gamma[c],
          meta->offset_sdr[c], meta->offset_hdr[c]);
    check(uhdr_decode(sdr.get()));
    const auto* base = uhdr_get_decoded_image(sdr.get());
    require(base && base->planes[0], "missing SDR pixels");
    for (float boost : {1.f, 2.f, 4.f}) {
      auto hdr = decoder(data, true, boost);
      check(uhdr_decode(hdr.get()));
      const auto* image = uhdr_get_decoded_image(hdr.get());
      require(image && image->planes[0] && image->w == base->w && image->h == base->h &&
              image->cg == base->cg, "HDR/SDR dimension or gamut mismatch");
      std::vector<float> ratios;
      ratios.reserve(size_t(base->w) * base->h);
      double sumSdr = 0, sumHdr = 0, maxAbsAtOne = 0;
      size_t darkened = 0, invalid = 0;
      constexpr double weights[]{.2126, .7152, .0722};
      for (unsigned y = 0; y < base->h; ++y) {
        const auto* s = static_cast<const uint8_t*>(base->planes[0]) + size_t(y) * base->stride[0] * 4;
        const auto* h = static_cast<const uint16_t*>(image->planes[0]) + size_t(y) * image->stride[0] * 4;
        for (unsigned x = 0; x < base->w; ++x) {
          double sy = 0, hy = 0;
          for (int c = 0; c < 3; ++c) {
            _Float16 v;
            std::memcpy(&v, h + x * 4 + c, sizeof(v));
            invalid += !std::isfinite(float(v));
            sy += weights[c] * linear(s[x * 4 + c]);
            hy += weights[c] * float(v);
          }
          sumSdr += sy;
          sumHdr += hy;
          maxAbsAtOne = std::max(maxAbsAtOne, std::abs(hy - sy));
          if (sy > .01) {
            ratios.push_back(hy / sy);
            darkened += hy < sy * .9;
          }
        }
      }
      require(!ratios.empty() && invalid == 0, "invalid/black decoded pixels");
      std::sort(ratios.begin(), ratios.end());
      auto percentile = [&](double p) { return ratios[size_t(p * (ratios.size() - 1))]; };
      std::printf("DECODE boost=%.1f cg=%d ct=%d meanRatio=%.9g min=%.9g p01=%.9g p50=%.9g p99=%.9g max=%.9g darkened10pct=%zu/%zu maxAbs=%.9g\n",
          boost, image->cg, image->ct, sumHdr / sumSdr, ratios.front(), percentile(.01),
          percentile(.5), percentile(.99), ratios.back(), darkened, ratios.size(), maxAbsAtOne);
      require(darkened == 0, "gainmap darkens nonblack pixels by over 10 percent");
      if (boost == 1.f)
        require(maxAbsAtOne < .015, "SDR headroom does not preserve the base");
    }
    std::puts("PASS official JPEG/R decoding at SDR/partial/full HDR headroom");
    return 0;
  } catch (const std::exception& e) {
    std::fprintf(stderr, "FAIL %s\n", e.what());
    return 1;
  }
}
