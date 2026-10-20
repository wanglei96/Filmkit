// Ultra HDR（JPEG/R）成片封装（v0.11.50）。
//
// ## 路线（2026-10-19 定案 + 实测确认）
// base = **我们自己的 4:4:4 JPEG**（`JpegEncoder.cpp` 的 stb 直编），
// gainmap = 我们按 **libultrahdr 官方 gainmapmath.cpp 的公式**自算后自编灰度 JPEG，
// libultrahdr 只做**封装**（MPF 多图 + XMP 元数据）。
//
// 为什么不让 libultrahdr 自己编 base：它的 `JpegEncoderHelper::compressImage` 走
// `jpeg_set_defaults()` + `jpeg_set_quality()`，公开 API 无采样因子参数（与 Android
// `Bitmap.compress` 同一死结，见 `JpegEncoder.cpp` 头注）⇒ base 会退回 4:2:0，
// 与本项目"成片必须 4:4:4"的质量红线冲突。而 `uhdr_enc_set_compressed_image(
// UHDR_BASE_IMG)` 接受**已编码字节流**并原样封装 —— 实测（2026-10-19）：
// 封装后 base 的 SOF0 仍是 4:4:4（h=1 v=1 三分量），`uhdr_is_supported_gainmap_image`
// 返回 1，官方解码器回读 gainmap 元数据（maxBoost/minBoost/gamma）全部正确。
//
// ## gainmap 数学（逐字对齐 lib/src/gainmapmath.cpp）
//   computeGain:   gain = log2((hdr + 1e-7) / (sdr + 1e-7))
//                  sdr < 2/255 时钳到 2.3（防暗部噪声放大成巨大 gain）
//   affineMapGain: m = (gain - log2(minBoost)) / (log2(maxBoost) - log2(minBoost))
//                  m = pow(m, gamma);  out8 = clip(m*255 + 0.5, 0, 255)
//   applyGain(解码侧，供核对)：
//                  logBoost = log2(min)*(1-g) + log2(max)*g
//                  hdr = (sdr + offset_sdr) * exp2(logBoost) - offset_hdr
//
// ## 输入
// `hdrLinear` = tone map **之前**的场景线性（本项目 = `tex_scene_` D4，RGBA16F 读回），
// `sdrLinear` = 同一像素经胶片链后的显示线性（0..1，由成片 8bit 反 sRGB EOTF 得来）。
// 两者必须**同像素对齐**：gainmap 描述的是"成片的这个像素在 HDR 下应该更亮多少"。

#include <jni.h>

#include <android/bitmap.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "ultrahdr_api.h"

#include "../render/FrameRenderer.h"

// stb（与 JpegEncoder.cpp 同一份，4:4:4 直编；gainmap 用它编单通道灰度）
// ⚠ **不定义** STB_IMAGE_WRITE_IMPLEMENTATION：那份实现已经在 `JpegEncoder.cpp` 里
// 有一份（同库内重复定义会导致 duplicate symbol，2026-10-19 实测）。这里只要声明
// —— stb 头默认就是纯声明，去掉宏即可复用同一份实现。
#define STBIW_ASSERT(x) ((void)0)
#define STBI_WRITE_NO_STDIO
#include "../../third_party/stb/stb_image_write.h"

namespace {

// 官方常量（lib/include/ultrahdr/gainmapmath.h）—— 官方版公式用，保留不删
constexpr float kHdrOffset = 1e-7f;
constexpr float kSdrOffset = 1e-7f;
// PC 常量（PhotonCamera hdr/RawGainmapMath.kt）—— 当前默认

struct ByteSink {
  std::vector<unsigned char> buf;
};

void sinkWrite(void* ctx, void* data, int size) {
  auto* s = static_cast<ByteSink*>(ctx);
  const auto* p = static_cast<const unsigned char*>(data);
  s->buf.insert(s->buf.end(), p, p + size);
}

// RGBA8（ARGB_8888 内存序）→ 紧密 RGB8（stb 编 3 通道需要）
void rgbaToRgb(const unsigned char* rgba, int w, int h, std::vector<unsigned char>& rgb) {
  rgb.resize(static_cast<size_t>(w) * h * 3);
  for (size_t i = 0, n = static_cast<size_t>(w) * h; i < n; ++i) {
    rgb[i * 3 + 0] = rgba[i * 4 + 0];
    rgb[i * 3 + 1] = rgba[i * 4 + 1];
    rgb[i * 3 + 2] = rgba[i * 4 + 2];
  }
}

std::vector<unsigned char> stbEncodeRgb(const unsigned char* rgb, int w, int h, int q) {
  ByteSink sink;
  sink.buf.reserve(static_cast<size_t>(w) * h / 3 + 4096);
  // stb 在 quality > 90 时输出 4:4:4（subsample = quality <= 90 ? 1 : 0）—— 本函数承诺
  // 4:4:4，故与 JpegEncoder.cpp 一样把低质量钳到 91。
  const int qq = q < 91 ? 91 : (q > 100 ? 100 : q);
  if (stbi_write_jpg_to_func(sinkWrite, &sink, w, h, 3, rgb, qq) == 0) return {};
  return sink.buf;
}

std::vector<unsigned char> stbEncodeGray(const unsigned char* g, int w, int h, int q) {
  ByteSink sink;
  sink.buf.reserve(static_cast<size_t>(w) * h / 3 + 4096);
  const int qq = std::clamp(q, 1, 100);
  if (stbi_write_jpg_to_func(sinkWrite, &sink, w, h, 1, g, qq) == 0) return {};
  return sink.buf;
}

// sRGB 反 EOTF（IEC 61966-2-1，与 FrameRenderer 的 srgbEncode 互逆）：
// 成片 8bit → 显示线性，供 gainmap 的 SDR 侧使用（必须与 base 逐像素同源）。
float srgbInvOetf(float x) {
  x = std::clamp(x, 0.0f, 1.0f);
  return x <= 0.04045f ? x / 12.92f : std::pow((x + 0.055f) / 1.055f, 2.4f);
}

// ── 两版 gainmap 数学（可切换，2026-10-19 定案：**两版都实现，实测对比后再选**）──
//
// 版本 A = libultrahdr 官方（lib/src/gainmapmath.cpp）：
//   gain = log2((hdr + 1e-7) / (sdr + 1e-7))，sdr < 2/255 时钳到 2.3
// 版本 B = PhotonCamera（hdr/RawGainmapMath.kt）：
//   ratio = (hdr + 1e-4) / (sdrLinear + 1e-4)，钳到 [0.25, 4.0]
//   out   = (ln(ratio) - ln(0.25)) / (ln(4.0) - ln(0.25))
//
// 两者数学等价（ln 与 log2 只差常数倍），差异在三处**数值行为**：
//   ① offset：1e-7（官方）vs 1e-4（PC）—— 后者大 1000 倍，暗部小分母被"垫高"
//      ⇒ PC 版暗部增益更保守（不容易把噪声放大成巨大 gain）
//   ② 范围：官方由内容自适应；PC 固定 0.25~4.0（超出的直接截断）
//   ③ 暗部钳位：官方对 sdr<2/255 钳 gain≤2.3；PC 靠 ratio 钳位间接实现
//
// 哪一个更好必须**实测**（同一张真机 RAW 的高光/暗部 ROI 对比 ΔE 与亮度），
// 不在实现阶段拍脑袋删掉任何一版。
//
// ⚠ 当前状态（v0.11.50）：**实际算 gainmap 的是 GPU 的 `kFsGainmap`**
// （因为 ES 3.0 读不回浮点纹理，见 FrameRenderer.cpp 的注释）。本文件里的两个
// computeGain* 保留作**离线验证/单测参照**（tools/gainmap-math-ab.py 用的就是同一
// 套公式），JNI 路径只做封装。改公式时**两处都要改**，否则离线结论与真机不一致。

enum class GainmapMath { kOfficial, kPhotonCamera };

// 版本 A：官方（libultrahdr gainmapmath.cpp:790，逐字）
[[maybe_unused]] float computeGainOfficial(float sdr, float hdr) {
  float gain = std::log2((hdr + 1e-7f) / (sdr + 1e-7f));
  if (sdr < 2.0f / 255.0f) gain = std::min(gain, 2.3f);
  return gain;
}

// 版本 B：PhotonCamera（hdr/RawGainmapMath.kt，逐字；strength=1.0 默认）
// sdrEncoded 是 **8bit 成片值归一化到 0..1**（PC 传的是 encoded 值，内部再转线性）
[[maybe_unused]] float computeGainPhotonCamera(float sdrEncoded, float hdrLinear) {
  const float kMinRatio = 0.25f, kMaxRatio = 4.0f, kOffset = 1e-4f;
  const float sdrLinear = srgbInvOetf(sdrEncoded);
  const float ratio = std::clamp((std::max(hdrLinear, 0.0f) + kOffset) / (sdrLinear + kOffset),
                                 kMinRatio, kMaxRatio);
  return (std::log(ratio) - std::log(kMinRatio)) / (std::log(kMaxRatio) - std::log(kMinRatio));
}

// 官方 computeGain（逐字，lib/src/gainmapmath.cpp:790）
[[maybe_unused]] float computeGain(float sdr, float hdr) {
  float gain = std::log2((hdr + kHdrOffset) / (sdr + kSdrOffset));
  if (sdr < 2.0f / 255.0f) gain = std::min(gain, 2.3f);
  return gain;
}

// 官方 affineMapGain（逐字，lib/src/gainmapmath.cpp:801）
[[maybe_unused]] unsigned char affineMapGain(float gainlog2, float mingainlog2,
                                             float maxgainlog2, float gamma) {
  float m = (gainlog2 - mingainlog2) / (maxgainlog2 - mingainlog2);
  if (gamma != 1.0f) m = std::pow(m, gamma);
  m *= 255.0f;
  return static_cast<unsigned char>(std::clamp(m + 0.5f, 0.0f, 255.0f));
}

// 盒式 1/scale 下采样（gainmap 只需低频；官方同样是先降分辨率再算 gain）
// ponytail: 盒式平均而非二次采样——gainmap 对噪声敏感，平均天然抑制；
// 若将来追求高光边缘精度，升级路径 = 面积平均 + 高光保护（先 max 后 mean 的混合）。
[[maybe_unused]] void boxDownsampleLuma(const float* linRgb, int w, int h, int scale,
                       std::vector<float>& outY, int& ow, int& oh) {
  ow = std::max(1, w / scale);
  oh = std::max(1, h / scale);
  outY.assign(static_cast<size_t>(ow) * oh, 0.0f);
  for (int y = 0; y < oh; ++y) {
    for (int x = 0; x < ow; ++x) {
      double sum = 0.0;
      int n = 0;
      const int x0 = x * scale, y0 = y * scale;
      const int x1 = std::min(w, x0 + scale), y1 = std::min(h, y0 + scale);
      for (int yy = y0; yy < y1; ++yy) {
        for (int xx = x0; xx < x1; ++xx) {
          const size_t i = (static_cast<size_t>(yy) * w + xx) * 3;
          // Rec.709 luma（官方 srgbLuminance 同系数）
          sum += 0.2126 * linRgb[i] + 0.7152 * linRgb[i + 1] + 0.0722 * linRgb[i + 2];
          ++n;
        }
      }
      outY[static_cast<size_t>(y) * ow + x] =
          n > 0 ? static_cast<float>(sum / n) : 0.0f;
    }
  }
}

}  // namespace

// 成片 RGBA8（= 已 tone map 的 SDR 显示）+ **预计算的 8bit gainmap** → JPEG/R。
//
// 为什么 gainmap 在外部算好再传进来：HDR 线性源（tex_scene_）在 GPU 上，
// 而 ES 3.0 **不能**读回浮点纹理（只保证 RGBA8 读回，真机实测见 FrameRenderer 的
// diagStatsTex 注释）。所以在 `kFsGainmap` 里直接算成 8bit 比值图，读回 CPU 后
// 传到这里 —— 传的是 1/2 分辨率的单通道字节（12MP 约 3MB），不是 100MB 浮点。
//
// gainmap 的数学（PC 版 / 官方版 A/B 实测结论）见 FrameRenderer.cpp 的 kFsGainmap
// 与 docs/reports/hdr-jpegr-v0.11.50-REPORT.md；本文件只负责**封装**。
//
// rgba8：    长度 w*h*4 的 ARGB_8888 内存序成片
// gainmap：  长度 gw*gh 的单通道 8bit 归一化比值（0..255）
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_filmkit_app_EngineBridge_nativeEncodeUltraHdr(JNIEnv* env, jclass,
                                                       jobject bitmap,
                                                       jbyteArray gainmap,
                                                       jint gw, jint gh,
                                                       jint quality) {
  if (!bitmap || !gainmap || gw <= 0 || gh <= 0) return nullptr;

  // ── 取成片 RGBA8 ──────────────────────────────────────────────────
  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height) return nullptr;

  void* pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
      !pixels) {
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
    return nullptr;
  }

  const int w = static_cast<int>(info.width);
  const int h = static_cast<int>(info.height);
  const size_t tight = static_cast<size_t>(w) * 4;

  std::vector<unsigned char> packed(static_cast<size_t>(w) * h * 4);
  const auto* src = static_cast<const unsigned char*>(pixels);
  if (info.stride == tight) {
    memcpy(packed.data(), src, tight * h);
  } else {
    for (int y = 0; y < h; ++y) {
      memcpy(packed.data() + tight * y, src + static_cast<size_t>(info.stride) * y, tight);
    }
  }
  AndroidBitmap_unlockPixels(env, bitmap);

  // ── 取 gainmap ────────────────────────────────────────────────────
  if (env->GetArrayLength(gainmap) < static_cast<jsize>(gw) * gh) return nullptr;
  std::vector<unsigned char> gm(static_cast<size_t>(gw) * gh);
  env->GetByteArrayRegion(gainmap, 0, static_cast<jsize>(gm.size()),
                          reinterpret_cast<jbyte*>(gm.data()));
  if (env->ExceptionCheck()) return nullptr;

  // ── ① base = 我们的 4:4:4 JPEG ───────────────────────────────────
  std::vector<unsigned char> rgb;
  rgbaToRgb(packed.data(), w, h, rgb);
  const int q = std::clamp(static_cast<int>(quality), 1, 100);
  auto baseJpeg = stbEncodeRgb(rgb.data(), w, h, q);
  if (baseJpeg.empty()) return nullptr;

  // ── ② gainmap 已是 8bit，直接编成灰度 JPEG ────────────────────────
  // gainmap 是辅助数据（8bit 量化 + 1/2 分辨率），q95 足够且省体积。
  auto gmJpeg = stbEncodeGray(gm.data(), gw, gh, 95);
  if (gmJpeg.empty()) return nullptr;

  // ── ③ 封装：base 原样 + gainmap + 元数据 → JPEG/R ─────────────────
  uhdr_codec_private_t* enc = uhdr_create_encoder();
  if (!enc) return nullptr;

  uhdr_compressed_image_t base{};
  base.data = baseJpeg.data();
  base.data_sz = baseJpeg.size();
  base.capacity = baseJpeg.size();
  base.cg = UHDR_CG_BT_709;
  base.ct = UHDR_CT_SRGB;
  base.range = UHDR_CR_FULL_RANGE;

  uhdr_compressed_image_t gimg{};
  gimg.data = gmJpeg.data();
  gimg.data_sz = gmJpeg.size();
  gimg.capacity = gmJpeg.size();
  // gainmap 是**单通道灰度辅助数据**，其色彩语义由 base 决定（use_base_cg=1），
  // 故 cg/ct/range 一律 UNSPECIFIED —— 对齐 PC `jpeg_r_encoder.cpp`（填具体值会让
  // libultrahdr 试图按彩色解释它）。
  gimg.cg = UHDR_CG_UNSPECIFIED;
  gimg.ct = UHDR_CT_UNSPECIFIED;
  gimg.range = UHDR_CR_UNSPECIFIED;

  // 元数据必须与 **kFsGainmap 的常量逐位一致**（shader 里 kMin=0.25 / kMax=4.0 /
  // kOff=1e-4）—— 不一致时解码端 exp2 还原会整体偏亮/偏暗，且不会有任何报错。
  constexpr float kMinBoost = 0.25f;
  constexpr float kMaxBoost = 4.0f;
  // offset 必须与 shader 的 gainmap 数学版本**一致**（见 kFsGainmap 的 uGainmapMath）：
  //   0 = PC 版 → 1e-4；1 = 官方版 → 1e-7
  // 不一致时解码端 exp2 还原会整体偏亮/偏暗，且**不会报任何错**。
  const float kMinOffset = (filmkit::FrameRenderer::gainmapMath() == 1) ? 1e-7f : 1e-4f;
  
  uhdr_gainmap_metadata_t meta{};
  for (int c = 0; c < 3; ++c) {
    // PC 版元数据（RawGainmapMath.MIN_GAIN_RATIO/MAX_GAIN_RATIO/OFFSET）：
    // 必须与 ② 里算 gainmap 用的常量**逐位一致**，否则解码端 exp2 还原会整体偏亮/偏暗。
    meta.max_content_boost[c] = kMaxBoost;
    meta.min_content_boost[c] = kMinBoost;
    meta.gamma[c] = 1.0f;
    meta.offset_sdr[c] = kMinOffset;
    meta.offset_hdr[c] = kMinOffset;
  }
  // 显示侧容量：1.0（SDR 显示）→ 4.0（HDR 显示全量应用）。
  // PC 用 `displayRatioSdr/Hdr` 两个独立字段（来自平台 Gainmap 对象），这里对齐成
  // 同一对端点 —— 我们是自己算的 gainmap，没有平台对象可借。
  meta.hdr_capacity_min = 1.0f;
  meta.hdr_capacity_max = kMaxBoost;
  meta.use_base_cg = 1;

  bool ok = true;
  ok = ok && uhdr_enc_set_compressed_image(enc, &base, UHDR_BASE_IMG).error_code == UHDR_CODEC_OK;
  ok = ok && uhdr_enc_set_gainmap_image(enc, &gimg, &meta).error_code == UHDR_CODEC_OK;
  // 显式选 JPEG/R 输出容器（对齐 PC `jpeg_r_encoder.cpp:packageJpegR`）。
  // 默认就是 JPG，但显式写出能防止上游改默认值时静默变成别种容器。
  ok = ok && uhdr_enc_set_output_format(enc, UHDR_CODEC_JPG).error_code == UHDR_CODEC_OK;
  ok = ok && uhdr_encode(enc).error_code == UHDR_CODEC_OK;

  jbyteArray result = nullptr;
  if (ok) {
    auto* out = uhdr_get_encoded_stream(enc);
    if (out && out->data && out->data_sz > 0) {
      // 产物自检（对齐 PC：编码后必须 `is_uhdr_image()` 通过才落盘）。
      // 不校验的话，元数据写坏/封装异常的图会以"看起来正常的 JPEG"流出去，
      // 下游相册按 SDR 显示 —— 静默降级比直接失败更难查。
      if (is_uhdr_image(out->data, static_cast<int>(out->data_sz))) {
        const jsize n = static_cast<jsize>(out->data_sz);
        result = env->NewByteArray(n);
        if (result) {
          env->SetByteArrayRegion(result, 0, n, static_cast<const jbyte*>(out->data));
          if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            result = nullptr;
          }
        }
      }
    }
  }
  uhdr_release_encoder(enc);
  return result;
}
