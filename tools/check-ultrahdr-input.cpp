// Link baseline/candidate UltraHdrEncoder.cpp with renamed JNI entry points,
// production libultrahdr/libjpeg-turbo, and JpegEncode.cpp renamed to *Real.
#include "../core/engine/src/jni/JpegEncode.h"
#include "../core/engine/src/render/FrameRenderer.h"
#include "../core/engine/third_party/ultrahdr/ultrahdr_api.h"
#include <android/bitmap.h>
#include <jni.h>
#include <jpeglib.h>

#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <random>
#include <stdexcept>

#define STBIW_ASSERT(x) ((void)0)
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include "../core/engine/third_party/stb/stb_image_write.h"

extern "C" jbyteArray referenceEncode(JNIEnv*, jclass, jobject, jbyteArray, jint, jint, jint);
extern "C" jbyteArray candidateEncode(JNIEnv*, jclass, jobject, jbyteArray, jint, jint, jint);

namespace {
void require(bool ok, const char* message) {
  if (!ok) throw std::runtime_error(message);
}
struct Bytes { std::vector<uint8_t> data; };
struct Bitmap {
  AndroidBitmapInfo info{};
  std::vector<uint8_t> data;
  int locks = 0, unlocks = 0;
  bool locked = false, nullPixels = false, failLock = false, failInfo = false;
  bool poisonOnUnlock = false;
};
bool pendingException = false, failRead = false, failNew = false, failWrite = false;
int deletedRefs = 0, forceTurboFailure = 0, mathMode = 0;
Bitmap* activeBitmap = nullptr;
bool candidateActive = false;
jsize JNICALL arrayLength(JNIEnv*, jarray a) {
  return static_cast<jsize>(reinterpret_cast<Bytes*>(a)->data.size());
}
void JNICALL readArray(JNIEnv*, jbyteArray a, jsize start, jsize n, jbyte* out) {
  if (failRead) { pendingException = true; return; }
  const auto& data = reinterpret_cast<Bytes*>(a)->data;
  require(start >= 0 && n >= 0 && static_cast<size_t>(start) + n <= data.size(), "array read bounds");
  std::memcpy(out, data.data() + start, n);
}
jboolean JNICALL exceptionCheck(JNIEnv*) { return pendingException; }
jbyteArray JNICALL newArray(JNIEnv*, jsize n) {
  require(!activeBitmap || !activeBitmap->locked, "Bitmap locked during Java allocation");
  if (failNew) return nullptr;
  return reinterpret_cast<jbyteArray>(new Bytes{std::vector<uint8_t>(n)});
}
void JNICALL writeArray(JNIEnv*, jbyteArray a, jsize start, jsize n, const jbyte* in) {
  if (failWrite) { pendingException = true; return; }
  auto& data = reinterpret_cast<Bytes*>(a)->data;
  require(start >= 0 && n >= 0 && static_cast<size_t>(start) + n <= data.size(), "array write bounds");
  std::memcpy(data.data() + start, in, n);
}
void JNICALL deleteRef(JNIEnv*, jobject a) {
  ++deletedRefs;
  delete reinterpret_cast<Bytes*>(a);
}
JNIEnv* environment() {
  static const JNINativeInterface table = [] {
    JNINativeInterface t{};
    t.GetArrayLength = arrayLength;
    t.GetByteArrayRegion = readArray;
    t.ExceptionCheck = exceptionCheck;
    t.NewByteArray = newArray;
    t.SetByteArrayRegion = writeArray;
    t.DeleteLocalRef = deleteRef;
    return t;
  }();
  static JNIEnv env{&table};
  return &env;
}
Bitmap image(int w, int h, int padding, int mode, std::mt19937& rng) {
  Bitmap b;
  b.info.width = w;
  b.info.height = h;
  b.info.stride = w * 4 + padding;
  b.info.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
  b.data.resize(static_cast<size_t>(b.info.stride) * h, 0xa5);
  for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x)
    for (int c = 0; c < 4; ++c) {
      b.data[static_cast<size_t>(y) * b.info.stride + x * 4 + c] =
          c == 3 || mode == 0 ? static_cast<uint8_t>(rng()) :
          mode == 1 ? static_cast<uint8_t>((x * 5 + y * 3 + c * 51) % 256) : 127;
    }
  return b;
}
Bytes gainmap(int w, int h, std::mt19937& rng) {
  Bytes b{std::vector<uint8_t>(static_cast<size_t>(w) * h)};
  for (auto& v : b.data) v = static_cast<uint8_t>(rng());
  return b;
}
std::vector<uint8_t> encode(Bitmap& b, Bytes& gm, int gw, int gh, int q, bool candidate) {
  const int beforeLocks = b.locks, beforeUnlocks = b.unlocks;
  activeBitmap = &b;
  candidateActive = candidate;
  auto fn = candidate ? candidateEncode : referenceEncode;
  std::unique_ptr<Bytes> out(reinterpret_cast<Bytes*>(fn(environment(), nullptr,
      reinterpret_cast<jobject>(&b), reinterpret_cast<jbyteArray>(&gm), gw, gh, q)));
  activeBitmap = nullptr;
  require(!b.locked, "Bitmap lock leaked");
  require(b.unlocks - beforeUnlocks == b.locks - beforeLocks, "unbalanced Bitmap locks");
  return out ? out->data : std::vector<uint8_t>{};
}
void verify444(const uhdr_mem_block_t* jpeg) {
  require(jpeg && jpeg->data && jpeg->data_sz, "missing base JPEG");
  jpeg_decompress_struct c{};
  jpeg_error_mgr err{};
  c.err = jpeg_std_error(&err);
  jpeg_create_decompress(&c);
  jpeg_mem_src(&c, static_cast<const uint8_t*>(jpeg->data), jpeg->data_sz);
  require(jpeg_read_header(&c, TRUE) == JPEG_HEADER_OK && c.num_components == 3, "bad base JPEG");
  for (int i = 0; i < 3; ++i)
    require(c.comp_info[i].h_samp_factor == 1 && c.comp_info[i].v_samp_factor == 1, "base is not 444");
  jpeg_destroy_decompress(&c);
}
std::vector<uint8_t> decode(const std::vector<uint8_t>& jpeg, int w, int h,
                            int gw, int gh, bool hdr) {
  std::unique_ptr<uhdr_codec_private_t, decltype(&uhdr_release_decoder)>
      dec(uhdr_create_decoder(), uhdr_release_decoder);
  require(dec != nullptr, "create decoder failed");
  uhdr_compressed_image_t input{};
  input.data = const_cast<uint8_t*>(jpeg.data());
  input.data_sz = input.capacity = jpeg.size();
  require(uhdr_dec_set_image(dec.get(), &input).error_code == UHDR_CODEC_OK, "set input failed");
  require(uhdr_dec_set_out_img_format(dec.get(), hdr ? UHDR_IMG_FMT_64bppRGBAHalfFloat :
          UHDR_IMG_FMT_32bppRGBA8888).error_code == UHDR_CODEC_OK, "output format failed");
  require(uhdr_dec_set_out_color_transfer(dec.get(), hdr ? UHDR_CT_LINEAR : UHDR_CT_SRGB).
          error_code == UHDR_CODEC_OK, "output transfer failed");
  require(uhdr_dec_probe(dec.get()).error_code == UHDR_CODEC_OK, "JPEG/R probe failed");
  require(uhdr_dec_get_image_width(dec.get()) == w && uhdr_dec_get_image_height(dec.get()) == h,
          "base dimensions changed");
  require(uhdr_dec_get_gainmap_width(dec.get()) == gw && uhdr_dec_get_gainmap_height(dec.get()) == gh,
          "gainmap dimensions changed");
  verify444(uhdr_dec_get_base_image(dec.get()));
  const auto* meta = uhdr_dec_get_gainmap_metadata(dec.get());
  require(meta != nullptr, "missing gainmap metadata");
  const float offset = mathMode == 1 ? 1e-7f : 1e-4f;
  for (int c = 0; c < 3; ++c) {
    require(meta->min_content_boost[c] == 0.25f && meta->max_content_boost[c] == 4.0f &&
            meta->gamma[c] == 1.0f && std::abs(meta->offset_sdr[c] - offset) < 1e-9f &&
            std::abs(meta->offset_hdr[c] - offset) < 1e-9f, "gainmap metadata changed");
  }
  require(meta->hdr_capacity_min == 1.0f && meta->hdr_capacity_max == 4.0f && meta->use_base_cg == 1,
          "HDR capacity changed");
  require(uhdr_decode(dec.get()).error_code == UHDR_CODEC_OK, "JPEG/R decode failed");
  const auto* out = uhdr_get_decoded_image(dec.get());
  require(out && out->w == static_cast<unsigned>(w) && out->h == static_cast<unsigned>(h), "decode dimensions");
  const size_t bytesPerPixel = hdr ? 8 : 4;
  std::vector<uint8_t> pixels(static_cast<size_t>(w) * h * bytesPerPixel);
  for (int y = 0; y < h; ++y)
    std::memcpy(pixels.data() + static_cast<size_t>(y) * w * bytesPerPixel,
        static_cast<uint8_t*>(out->planes[0]) + static_cast<size_t>(y) * out->stride[0] * bytesPerPixel,
        static_cast<size_t>(w) * bytesPerPixel);
  return pixels;
}
void failures(std::mt19937& rng) {
  auto b = image(64, 48, 0, 1, rng);
  auto gm = gainmap(32, 24, rng);
  const auto original = b.info;
  b.failInfo = true;
  require(encode(b, gm, 32, 24, 100, true).empty(), "getInfo failure ignored");
  b.failInfo = false;
  for (auto field : {0, 1, 2, 3, 4}) {
    b.info = original;
    if (field == 0) b.info.format = ANDROID_BITMAP_FORMAT_RGB_565;
    if (field == 1) b.info.width = 0;
    if (field == 2) b.info.height = 0;
    if (field == 3) b.info.stride = 1;
    if (field == 4) b.info.width = std::numeric_limits<uint32_t>::max();
    require(encode(b, gm, 32, 24, 100, true).empty(), "invalid bitmap accepted");
  }
  b.info = original;
  require(encode(b, gm, 33, 24, 100, true).empty(), "short gainmap accepted");
  require(encode(b, gm, std::numeric_limits<jint>::max(), 2, 100, true).empty(), "gainmap overflow accepted");
  require(encode(b, gm, 0, 24, 100, true).empty(), "zero gainmap accepted");
  failRead = true;
  require(encode(b, gm, 32, 24, 100, true).empty(), "read exception ignored");
  failRead = pendingException = false;
  require(b.locks == 0, "invalid input locked Bitmap");
  b.failLock = true;
  require(encode(b, gm, 32, 24, 100, true).empty(), "lock failure ignored");
  b.failLock = false;
  b.nullPixels = true;
  require(encode(b, gm, 32, 24, 100, true).empty(), "null pixels accepted");
  b.nullPixels = false;
  failNew = true;
  require(encode(b, gm, 32, 24, 100, true).empty(), "allocation failure ignored");
  failNew = false;
  failWrite = true;
  require(encode(b, gm, 32, 24, 100, true).empty() && deletedRefs == 1, "write exception cleanup");
  failWrite = pendingException = false;
  require(!encode(b, gm, 32, 24, 100, true).empty(), "failed to recover after errors");
}
std::vector<uint8_t> readFile(const char* path) {
  std::ifstream file(path, std::ios::binary);
  require(file.good(), "could not open input file");
  return std::vector<uint8_t>((std::istreambuf_iterator<char>(file)), {});
}
void benchmark(Bitmap& b, Bytes& gm) {
  const auto expected = encode(b, gm, 1536, 2040, 100, false);
  require(!expected.empty(), "full-size reference failed");
  require(encode(b, gm, 1536, 2040, 100, true) == expected, "full-size warmup bytes changed");
  for (int block = 0; block < 4; ++block) for (int item = 0; item < 4; ++item) {
    const bool candidate = ((item == 1 || item == 2) != (block % 2 != 0));
    const auto start = std::chrono::steady_clock::now();
    const auto jpeg = encode(b, gm, 1536, 2040, 100, candidate);
    const double ms = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start).count();
    require(jpeg == expected, "full-size JPEG/R bytes changed");
    std::printf("BENCH block=%d candidate=%d ms=%.3f bytes=%zu\n", block, candidate, ms, jpeg.size());
    std::fflush(stdout);
  }
}
}

extern "C" int AndroidBitmap_getInfo(JNIEnv*, jobject obj, AndroidBitmapInfo* info) {
  auto& b = *reinterpret_cast<Bitmap*>(obj);
  if (b.failInfo) return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  *info = b.info;
  return ANDROID_BITMAP_RESULT_SUCCESS;
}
extern "C" int AndroidBitmap_lockPixels(JNIEnv*, jobject obj, void** pixels) {
  auto& b = *reinterpret_cast<Bitmap*>(obj);
  require(!b.locked, "double lock");
  if (b.failLock) return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  b.locked = true;
  ++b.locks;
  *pixels = b.nullPixels ? nullptr : b.data.data();
  return ANDROID_BITMAP_RESULT_SUCCESS;
}
extern "C" int AndroidBitmap_unlockPixels(JNIEnv*, jobject obj) {
  auto& b = *reinterpret_cast<Bitmap*>(obj);
  require(b.locked, "unlock without lock");
  b.locked = false;
  ++b.unlocks;
  if (b.poisonOnUnlock) std::fill(b.data.begin(), b.data.end(), 0xdd);
  return ANDROID_BITMAP_RESULT_SUCCESS;
}
namespace filmkit {
int FrameRenderer::gainmapMath() { return mathMode; }
bool encodeJpegTurbo444Real(const uint8_t*, int, int, int, int, std::vector<uint8_t>*);
bool encodeJpegTurbo444(const uint8_t* data, int w, int h, int channels, int q, std::vector<uint8_t>* out) {
  if (candidateActive && channels == 4)
    require(activeBitmap && activeBitmap->locked, "RGBA read outside Bitmap lock");
  if ((forceTurboFailure & 1) && channels != 1) { *out = {1, 2, 3}; return false; }
  if ((forceTurboFailure & 2) && channels == 1) { *out = {1, 2, 3}; return false; }
  return encodeJpegTurbo444Real(data, w, h, channels, q, out);
}
}
int main(int argc, char** argv) {
  try {
    if (argc == 4 && std::strcmp(argv[1], "--benchmark-input") == 0) {
      Bitmap b;
      b.info.width = 3072;
      b.info.height = 4080;
      b.info.stride = 3072 * 4;
      b.info.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
      b.data = readFile(argv[2]);
      Bytes gm{readFile(argv[3])};
      require(b.data.size() == 3072u * 4080 * 4 && gm.data.size() == 1536u * 2040,
              "wrong full-size input length");
      benchmark(b, gm);
      return 0;
    }
    if (argc > 2 && std::strcmp(argv[1], "--inspect") == 0) {
      for (int i = 2; i < argc; ++i) {
        const auto jpeg = readFile(argv[i]);
        require(!jpeg.empty(), "empty saved JPEG/R");
        // Current full-size portrait output; catches gainmap rotation/stride regressions.
        const auto sdr = decode(jpeg, 3072, 4080, 1536, 2040, false);
        const auto hdr = decode(jpeg, 3072, 4080, 1536, 2040, true);
        require(!sdr.empty() && !hdr.empty(), "empty decoded image");
        std::printf("INSPECT PASS %s bytes=%zu 3072x4080 gainmap=1536x2040 444 SDR HDR metadata\n",
                    argv[i], jpeg.size());
      }
      return 0;
    }
    std::mt19937 rng(0x55484452);
    int cases = 0;
    for (int math : {0, 1}) for (int padding : {0, 28})
      for (const auto& dims : {std::array<int, 2>{{8, 8}}, {{63, 47}}, {{128, 96}}})
        for (int mode : {0, 1, 2}) for (int q : {-1, 1, 90, 95, 100, 101})
          for (int fallback : {0, 1, 2, 3}) {
            mathMode = math;
            forceTurboFailure = fallback;
            auto b = image(dims[0], dims[1], padding, mode, rng);
            const int gw = std::max(1, dims[0] / 2), gh = std::max(1, dims[1] / 2);
            auto gm = gainmap(gw, gh, rng);
            const auto ref = encode(b, gm, gw, gh, q, false);
            require(!ref.empty(), "reference encode failed");
            b.poisonOnUnlock = true;
            const auto out = encode(b, gm, gw, gh, q, true);
            require(out == ref, "complete JPEG/R bytes differ");
            if (mode == 1 && q == 100) for (bool hdr : {false, true})
              require(decode(ref, dims[0], dims[1], gw, gh, hdr) ==
                      decode(out, dims[0], dims[1], gw, gh, hdr), "SDR/HDR decoded pixels differ");
            ++cases;
          }
    mathMode = forceTurboFailure = 0;
    failures(rng);
    std::printf("PASS %d full JPEG/R comparisons; 444/metadata/SDR/HDR/stride/fallback/lock/errors\n", cases);
    if (argc == 2 && std::strcmp(argv[1], "--benchmark") == 0) {
      auto b = image(3072, 4080, 0, 1, rng);
      auto gm = gainmap(1536, 2040, rng);
      benchmark(b, gm);
    }
    return 0;
  } catch (const std::exception& error) {
    std::fprintf(stderr, "FAIL %s\n", error.what());
    return 1;
  }
}
