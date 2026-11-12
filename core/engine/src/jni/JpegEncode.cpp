#include "JpegEncode.h"

#include <algorithm>
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

}  // namespace filmkit
