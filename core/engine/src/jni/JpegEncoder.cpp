// 成片 JPEG 4:4:4 编码（v0.10.171）。
//
// 为什么需要它：Android 的 `Bitmap.compress(JPEG, q, out)` 底层是 Skia → libjpeg-turbo，
// 内部 `jpeg_set_defaults()` 后不再触碰采样因子 ⇒ 输出恒为 4:2:0（SOF0 分量 2×2/1×1/1×1），
// 公开 API 无任何参数可改；`YuvImage.compressToJpeg` 同样固定 4:2:0；NDK 亦不提供 libjpeg
// （NDK 29 的 sysroot 中无 jpeglib.h / libjpeg.so，已实测）。4:2:0 的色度下采样会把强亮度
// 边缘两侧的色度平均串到另一侧 ⇒ 中性边缘出现紫/绿镶边（离线对照实验归因见
// /tmp/jpeg444/REPORT.md：中性强边缘处编码引入的色度误差 p99 由 4:4:4 的 1.36 升到
// 4:2:0 的 9.77、max 由 1.94 升到 49.17）。
//
// 实现选择：stb_image_write（v1.16，单头文件 1724 行，MIT / Public Domain 双许可）。
//   - 它在 quality > 90 时输出 4:4:4（`subsample = quality <= 90 ? 1 : 0`），且 q100 时
//     量化表退化为全 1（与 Android 现状的 libjpeg q100 表逐项相同）；
//   - host 实测（4080×3072 真实样片）：p99 色度误差 1.36 < libjpeg 4:4:4 的 1.58，
//     体积 13.06MB < libjpeg 4:4:4 的 14.53MB ⇒ 质量不降、体积更小；
//   - 不引入构建期依赖（无 CMake 子项目、无 nasm、不影响 x86_64 ABI 的构建），
//     .so 增量 ~30KB，而 vendor libjpeg-turbo 需 ~10MB 源码 + ~400KB .so。
// 速度代价已实测并在 REPORT.md 中列出（stb q100 358ms vs libjpeg 4:4:4 100ms，
// host M1；真机耗时由 photo.saved 的 encMs 字段实测确认）。
#include <android/bitmap.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <vector>

// stb 内部一致性断言：Release 下若未定义 NDEBUG 会 abort（拍完即崩、成片丢失），
// 而本函数的失败路径已由返回值覆盖（nullptr ⇒ 调用方回退 Bitmap.compress），故置空。
#define STBIW_ASSERT(x) ((void)0)
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include "../../third_party/stb/stb_image_write.h"

namespace {

struct ByteSink {
  std::vector<unsigned char> buf;
};

void sinkWrite(void* ctx, void* data, int size) {
  ByteSink* s = static_cast<ByteSink*>(ctx);
  const unsigned char* p = static_cast<const unsigned char*>(data);
  s->buf.insert(s->buf.end(), p, p + size);
}

}  // namespace

// 成片 RGBA（ARGB_8888 Bitmap）→ JPEG 4:4:4 字节流。
// quality：JPEG 质量（1..100）。<91 时钳到 91 —— stb 在 ≤90 会退回 4:2:0，本函数承诺 4:4:4。
// 返回 nullptr 表示编码失败（格式不符/ stride 异常/ 内存不足），调用方必须回退到
// Bitmap.compress —— 丢成片比退到 4:2:0 严重得多。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEncodeJpeg444(JNIEnv* env, jclass,
                                                      jobject bitmap,
                                                      jint quality) {
  if (!bitmap) return nullptr;

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    return nullptr;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 ||
      info.height == 0) {
    return nullptr;  // 非 RGBA_8888（如 HARDWARE / RGB_565）不在本路径覆盖范围
  }

  void* pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    return nullptr;
  }
  if (!pixels) {
    AndroidBitmap_unlockPixels(env, bitmap);
    return nullptr;
  }

  const int w = static_cast<int>(info.width);
  const int h = static_cast<int>(info.height);
  const size_t tight = static_cast<size_t>(w) * 4;
  const int q = quality < 91 ? 91 : (quality > 100 ? 100 : quality);

  ByteSink sink;
  sink.buf.reserve(static_cast<size_t>(w) * h / 3 + 4096);

  // stb 按紧密打包读取（p = row*width*comp + col*comp），不认 stride。
  // 常规 ARGB_8888 的 stride 就是 width*4（零拷贝直编）；ABI/对齐异常时逐行压紧，
  // 宁可多一次拷贝也不产出错行图像。
  bool ok;
  if (info.stride == tight) {
    ok = stbi_write_jpg_to_func(sinkWrite, &sink, w, h, 4, pixels, q) != 0;
  } else {
    std::vector<unsigned char> packed(tight * h);
    const unsigned char* src = static_cast<const unsigned char*>(pixels);
    for (int y = 0; y < h; ++y) {
      memcpy(packed.data() + tight * y, src + static_cast<size_t>(info.stride) * y,
             tight);
    }
    ok = stbi_write_jpg_to_func(sinkWrite, &sink, w, h, 4, packed.data(), q) != 0;
  }

  AndroidBitmap_unlockPixels(env, bitmap);

  if (!ok || sink.buf.empty()) return nullptr;

  const jsize n = static_cast<jsize>(sink.buf.size());
  jbyteArray out = env->NewByteArray(n);
  if (!out) return nullptr;  // OOM：交给调用方回退
  env->SetByteArrayRegion(out, 0, n,
                          reinterpret_cast<const jbyte*>(sink.buf.data()));
  return out;
}
