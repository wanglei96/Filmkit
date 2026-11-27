#include "JpegEncode.h"

#include <algorithm>
#include <array>
#include <future>
#include <csetjmp>
#include <cstdlib>
#include <cstring>

#ifdef FILMKIT_HDR
#include <jpeglib.h>
#if JPEG_LIB_VERSION >= 70 || defined(JCS_EXTENSIONS)
#define FK_JPEG_RGBA 1
#endif
#endif

namespace filmkit {

#ifdef FILMKIT_HDR
namespace {

struct JpegErrMgr {
  jpeg_error_mgr pub;
  jmp_buf jb;
};

// libjpeg 的默认 error_exit 会 exit() —— 必须换成 longjmp，否则一张坏图能把进程带走。
void onJpegError(j_common_ptr cinfo) {
  auto* e = reinterpret_cast<JpegErrMgr*>(cinfo->err);
  longjmp(e->jb, 1);
}

void onJpegOutput(j_common_ptr cinfo) { (void)cinfo; }  // 静默，不往 stderr 写

}  // namespace
#endif

bool jpegTurboAvailable() {
#ifdef FILMKIT_HDR
  return true;
#else
  return false;
#endif
}

bool encodeJpegTurbo444(const uint8_t* data, int w, int h, int channels,
                        int quality, std::vector<uint8_t>* out) {
  if (!data || !out || w <= 0 || h <= 0) return false;
  if (channels != 1 && channels != 3 && channels != 4) return false;
#ifdef FILMKIT_HDR
  jpeg_compress_struct c;
  std::memset(&c, 0, sizeof(c));
  JpegErrMgr e;
  std::memset(&e, 0, sizeof(e));
  c.err = jpeg_std_error(&e.pub);
  e.pub.error_exit = onJpegError;
  e.pub.output_message = onJpegOutput;
  if (setjmp(e.jb)) {
    jpeg_destroy_compress(&c);
    return false;
  }
  jpeg_create_compress(&c);
  unsigned char* mem = nullptr;
  unsigned long mem_len = 0;
  jpeg_mem_dest(&c, &mem, &mem_len);
  c.image_width = w;
  c.image_height = h;
  c.input_components = channels;
  if (channels == 1) {
    c.in_color_space = JCS_GRAYSCALE;
  } else if (channels == 4) {
#if defined(FK_JPEG_RGBA)
    c.in_color_space = JCS_EXT_RGBA;   // libjpeg-turbo：直接吃 ARGB_8888，省一次 RGB 拷贝
#else
    jpeg_destroy_compress(&c);
    return false;
#endif
  } else {
    c.in_color_space = JCS_RGB;
  }
  jpeg_set_defaults(&c);
  jpeg_set_quality(&c, std::clamp(quality, 1, 100), TRUE);
  // 4:4:4：所有分量的采样因子 1×1（jpeg_set_quality 不会动它，但显式钉死更可靠）。
  for (int i = 0; i < c.num_components; ++i) {
    c.comp_info[i].h_samp_factor = 1;
    c.comp_info[i].v_samp_factor = 1;
  }
  jpeg_start_compress(&c, TRUE);
  const size_t stride = static_cast<size_t>(w) * channels;
  while (c.next_scanline < c.image_height) {
    JSAMPROW row = const_cast<JSAMPROW>(data + stride * c.next_scanline);
    jpeg_write_scanlines(&c, &row, 1);
  }
  jpeg_finish_compress(&c);
  if (mem && mem_len > 0) out->assign(mem, mem + mem_len);
  std::free(mem);
  jpeg_destroy_compress(&c);
  return mem_len > 0;
#else
  (void)quality;
  return false;
#endif
}

#ifdef FILMKIT_HDR
namespace {
struct JpegStrip {
    std::vector<uint8_t> bytes;
    size_t sofHeight = 0, scanHeader = 0, scan = 0;

    bool findBaselineScan() {
        // Only parse the known single-scan baseline output of our own encoder.
        if (bytes.size() < 4 || bytes[0] != 0xff || bytes[1] != 0xd8 ||
            bytes[bytes.size() - 2] != 0xff || bytes.back() != 0xd9) return false;
        for (size_t i = 2; i + 4 <= bytes.size();) {
            if (bytes[i] != 0xff) return false;
            const unsigned marker = bytes[i + 1];
            const size_t length = (bytes[i + 2] << 8) | bytes[i + 3];
            if (length < 2 || i + 2 + length > bytes.size() - 2) return false;
            if (marker == 0xc0 && length == 17 && bytes[i + 4] == 8 && bytes[i + 9] == 3) {
                sofHeight = i + 5;
            } else if (marker == 0xda) {
                if (!sofHeight || length != 12 || bytes[i + 4] != 3) return false;
                scanHeader = i;
                scan = i + 2 + length;
                return scan < bytes.size() - 2;
            } else if (marker != 0xe0 && marker != 0xdb && marker != 0xc4) {
                return false;  // In particular, do not accept a pre-existing DRI.
            }
            i += 2 + length;
        }
        return false;
    }
};
}  // namespace
#endif

bool encodeJpegTurbo444Striped(const uint8_t* data, int w, int h, int quality,
                              std::vector<uint8_t>* out) {
#ifdef FILMKIT_HDR
    if (!data || !out || w <= 0 || h <= 0 || w > JPEG_MAX_DIMENSION || h > JPEG_MAX_DIMENSION ||
        static_cast<uint64_t>(w) * h < 8'000'000) return false;
    constexpr int kWorkers = 3;
    constexpr int kMcu = 8; // Sequential baseline, sampling factors all 1×1.
    const int mcuRows = (h + kMcu - 1) / kMcu;
    const int rowsPerStrip = (mcuRows + kWorkers - 1) / kWorkers;
    const int fullRows = rowsPerStrip * kMcu;
    const int restartInterval = ((w + kMcu - 1) / kMcu) * rowsPerStrip;
    if (restartInterval > 65535 || h <= (kWorkers - 1) * fullRows) return false;

    std::array<JpegStrip, kWorkers> parts;
    const auto encode = [&](int i) {
        const int start = i * fullRows;
        return encodeJpegTurbo444(data + static_cast<size_t>(start) * w * 4,
                                  w, std::min(fullRows, h - start), 4, quality, &parts[i].bytes) &&
               parts[i].findBaselineScan();
    };
    try {
        auto a = std::async(std::launch::async, encode, 1);
        auto b = std::async(std::launch::async, encode, 2);
        if (!encode(0) || !a.get() || !b.get()) return false;

        // Match all stream parameters (DQT, DHT, sampling, SOS, etc.) except strip height.
        auto& first = parts[0];
        for (int i = 1; i < kWorkers; ++i) {
            const auto& part = parts[i];
            if (part.sofHeight != first.sofHeight || part.scan != first.scan ||
                !std::equal(first.bytes.begin(), first.bytes.begin() + first.sofHeight, part.bytes.begin()) ||
                !std::equal(first.bytes.begin() + first.sofHeight + 2, first.bytes.begin() + first.scan,
                            part.bytes.begin() + part.sofHeight + 2)) return false;
        }
        size_t size = first.scan + 6 + 2;
        for (int i = 0; i < kWorkers; ++i) size += parts[i].bytes.size() - parts[i].scan;
        out->clear();
        out->reserve(size);
        first.bytes[first.sofHeight] = h >> 8;
        first.bytes[first.sofHeight + 1] = h & 255;
        out->insert(out->end(), first.bytes.begin(), first.bytes.begin() + first.scanHeader);
        out->insert(out->end(), {0xff, 0xdd, 0, 4, static_cast<uint8_t>(restartInterval >> 8),
                                  static_cast<uint8_t>(restartInterval & 255)});
        out->insert(out->end(), first.bytes.begin() + first.scanHeader, first.bytes.begin() + first.scan);
        for (int i = 0; i < kWorkers; ++i) {
            const auto& part = parts[i];
            // Each compressor restarts DC prediction at zero and flushes its last entropy byte.
            if (i > 0) out->insert(out->end(), {0xff, static_cast<uint8_t>(0xd0 + i - 1)});
            out->insert(out->end(), part.bytes.begin() + part.scan, part.bytes.end() - 2);
        }
        out->insert(out->end(), {0xff, 0xd9});
        return true;
    } catch (const std::exception&) {
        return false;  // Single-stream libjpeg remains available.
    }
#else
    (void)data; (void)w; (void)h; (void)quality; (void)out;
    return false;
#endif
}

}  // namespace filmkit
