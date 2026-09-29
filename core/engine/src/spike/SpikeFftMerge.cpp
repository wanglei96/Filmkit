#include "SpikeFftMerge.h"

#include <GLES3/gl31.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "../gl/Capabilities.h"
#include "../log/GpuTimer.h"
#include "../log/Logger.h"
#include "../log/Timer.h"

namespace filmkit {

namespace {

// ── 被测内核：HDR+ §5 式 6/7 的最小完整 pairwise DFT 合并链 ────────────
// 坐标约定：workgroup(16×16 线程) 处理 2 个水平相邻 tile；uStride = tile 起点
// 间隔（16 = 非重叠基线，8 = 半重叠真实密度）。4 帧经 sampler2DArray 一次绑定。
// 全部复数中间态在 shared（2×4×16×16×2×4B = 16KB），无全局内存放大。
const char* kFftMergeCs = R"(#version 310 es
precision highp float;
precision highp int;
precision highp sampler2DArray;

layout(local_size_x = 16, local_size_y = 16) in;

uniform highp sampler2DArray uFrames;
layout(rgba16f, binding = 0) uniform writeonly highp image2D uOut;
uniform highp ivec2 uSize;
uniform highp int uStride;
uniform highp float uSigma;
uniform highp float uC;

shared float sre[2][4][16][16];
shared float sim[2][4][16][16];

const float TWO_PI = 6.283185307179586;

void fftRow(int t, int z, int y, float sgn) {
    int j = 0;
    for (int i = 1; i < 16; ++i) {
        int bit = 8;
        for (int b = 0; b < 4; ++b) {
            if ((j & bit) == 0) { break; }
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            float tr = sre[t][z][y][i];
            sre[t][z][y][i] = sre[t][z][y][j];
            sre[t][z][y][j] = tr;
            float ti = sim[t][z][y][i];
            sim[t][z][y][i] = sim[t][z][y][j];
            sim[t][z][y][j] = ti;
        }
    }
    for (int len = 2; len <= 16; len <<= 1) {
        int hlen = len >> 1;
        for (int blk = 0; blk < 16; blk += len) {
            for (int k = 0; k < hlen; ++k) {
                float ang = TWO_PI * float(k) / float(len);
                float wr = cos(ang);
                float wi = sgn * sin(ang);
                int i0 = blk + k;
                int i1 = i0 + hlen;
                float xr = sre[t][z][y][i1];
                float xi = sim[t][z][y][i1];
                float tr = xr * wr - xi * wi;
                float ti = xr * wi + xi * wr;
                sre[t][z][y][i1] = sre[t][z][y][i0] - tr;
                sim[t][z][y][i1] = sim[t][z][y][i0] - ti;
                sre[t][z][y][i0] += tr;
                sim[t][z][y][i0] += ti;
            }
        }
    }
}

void fftCol(int t, int z, int x, float sgn) {
    int j = 0;
    for (int i = 1; i < 16; ++i) {
        int bit = 8;
        for (int b = 0; b < 4; ++b) {
            if ((j & bit) == 0) { break; }
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            float tr = sre[t][z][i][x];
            sre[t][z][i][x] = sre[t][z][j][x];
            sre[t][z][j][x] = tr;
            float ti = sim[t][z][i][x];
            sim[t][z][i][x] = sim[t][z][j][x];
            sim[t][z][j][x] = ti;
        }
    }
    for (int len = 2; len <= 16; len <<= 1) {
        int hlen = len >> 1;
        for (int blk = 0; blk < 16; blk += len) {
            for (int k = 0; k < hlen; ++k) {
                float ang = TWO_PI * float(k) / float(len);
                float wr = cos(ang);
                float wi = sgn * sin(ang);
                int i0 = blk + k;
                int i1 = i0 + hlen;
                float xr = sre[t][z][i1][x];
                float xi = sim[t][z][i1][x];
                float tr = xr * wr - xi * wi;
                float ti = xr * wi + xi * wr;
                sre[t][z][i1][x] = sre[t][z][i0][x] - tr;
                sim[t][z][i1][x] = sim[t][z][i0][x] - ti;
                sre[t][z][i0][x] += tr;
                sim[t][z][i0][x] += ti;
            }
        }
    }
}

void main() {
    ivec2 lid = ivec2(gl_LocalInvocationID.xy);
    ivec2 wg = ivec2(gl_WorkGroupID.xy);
    int tid = lid.y * 16 + lid.x;
    int stride = uStride;
    ivec2 origin = ivec2(wg.x * 2 * stride, wg.y * stride);

    if (tid < 128) {
        int tt = tid / 64;
        int zz = (tid / 16) % 4;
        int yy = tid % 16;
        ivec2 base = origin + ivec2(tt * stride, 0);
        float wy = 0.5 - 0.5 * cos(TWO_PI * (float(yy) + 0.5) / 16.0);
        for (int x = 0; x < 16; ++x) {
            ivec2 p = clamp(base + ivec2(x, yy), ivec2(0), uSize - 1);
            float v = texelFetch(uFrames, ivec3(p, zz), 0).r;
            float wx = 0.5 - 0.5 * cos(TWO_PI * (float(x) + 0.5) / 16.0);
            sre[tt][zz][yy][x] = v * wx * wy;
            sim[tt][zz][yy][x] = 0.0;
        }
        fftRow(tt, zz, yy, -1.0);
    }
    barrier();

    if (tid < 128) {
        int tt = tid / 64;
        int zz = (tid / 16) % 4;
        int xx = tid % 16;
        fftCol(tt, zz, xx, -1.0);
    }
    barrier();

    for (int rep = 0; rep < 2; ++rep) {
        int idx = tid * 2 + rep;
        int tt = idx / 256;
        int bin = idx % 256;
        int by = bin / 16;
        int bx = bin % 16;
        float r0 = sre[tt][0][by][bx];
        float i0 = sim[tt][0][by][bx];
        float accR = r0;
        float accI = i0;
        for (int zz = 1; zz < 4; ++zz) {
            float rz = sre[tt][zz][by][bx];
            float iz = sim[tt][zz][by][bx];
            float dr = r0 - rz;
            float di = i0 - iz;
            float d2 = dr * dr + di * di;
            float a = d2 / (d2 + uC * uSigma * uSigma + 1e-12);
            accR += rz + a * dr;
            accI += iz + a * di;
        }
        sre[tt][0][by][bx] = accR * 0.25;
        sim[tt][0][by][bx] = accI * 0.25;
    }
    barrier();

    if (tid < 32) {
        int tt = tid / 16;
        int xx = tid % 16;
        fftCol(tt, 0, xx, 1.0);
    }
    barrier();

    if (tid < 32) {
        int tt = tid / 16;
        int yy = tid % 16;
        fftRow(tt, 0, yy, 1.0);
        ivec2 base = origin + ivec2(tt * stride, 0);
        for (int x = 0; x < 16; ++x) {
            ivec2 p = base + ivec2(x, yy);
            if (p.x < uSize.x && p.y < uSize.y) {
                imageStore(uOut, p, vec4(sre[tt][0][yy][x] * 0.00390625, 0.0, 0.0, 1.0));
            }
        }
    }
}
)";

GLuint compileCs(const char* src, std::string* err) {
  GLuint sh = glCreateShader(GL_COMPUTE_SHADER);
  glShaderSource(sh, 1, &src, nullptr);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char buf[1024];
    glGetShaderInfoLog(sh, sizeof(buf), nullptr, buf);
    if (err) *err = std::string("compute shader 编译失败: ") + buf;
    glDeleteShader(sh);
    return 0;
  }
  return sh;
}

double pctOf(std::vector<double> v, double p) {
  if (v.empty()) return -1.0;
  std::sort(v.begin(), v.end());
  const auto idx = static_cast<size_t>(
      std::lround(p * static_cast<double>(v.size() - 1)));
  return v[idx];
}

// 合成基准图案（host 与读回校验共用；值域约 [0.03, 0.67]）。
float basePattern(int x, int y) {
  return 0.35f + 0.20f * std::sin(x * 0.061f) * std::cos(y * 0.053f) +
         0.12f * std::sin((x + y) * 0.017f);
}

// 整数哈希噪声（[-0.5, 0.5)），避免 12M 像素级三角函数。
float hashNoise(uint32_t x, uint32_t y, uint32_t z) {
  uint32_t h = x * 0x9E3779B1u ^ y * 0x85EBCA77u ^ z * 0xC2B2AE3Du;
  h ^= h >> 15;
  h *= 0x2545F491u;
  h ^= h >> 13;
  return static_cast<float>(h & 0xFFFFu) / 65536.0f - 0.5f;
}

}  // namespace

FftSpikeResult runSpikeFftMerge(int w, int h, int iters, bool overlap) {
  FftSpikeResult r;
  r.overlap = overlap;
  auto& logger = Logger::instance();
  auto fail = [&](const std::string& msg) {
    r.error = msg;
    logger.log(LogLevel::kError, "engine", "spike.fft.error",
               "{\"message\":\"" + jsonEscape(msg) + "\"}");
    return r;
  };

  const int stride = overlap ? 8 : 16;
  const int unit = 2 * stride;
  if (w < unit || h < stride || iters <= 0) return fail("非法尺寸/轮数");
  w = (w / unit) * unit;
  h = (h / stride) * stride;
  r.width = w;
  r.height = h;
  r.iterations = iters;
  r.tiles = static_cast<long long>(w / stride) * (h / stride);
  r.shared_bytes = 2 * 4 * 16 * 16 * 2 * 4;  // 16384

  const Capabilities caps = detectCapabilities();
  r.max_shared = caps.max_compute_shared_memory;
  r.gpu_timer_used = caps.ext_disjoint_timer_query;
  if (!caps.es31_compute) return fail("设备无 ES 3.1 compute");
  if (caps.max_compute_shared_memory > 0 &&
      caps.max_compute_shared_memory < r.shared_bytes) {
    return fail("shared 16KB 超出设备 GL_MAX_COMPUTE_SHARED_MEMORY_SIZE");
  }

  std::string err;
  const GLuint cs = compileCs(kFftMergeCs, &err);
  if (!cs) return fail(err);
  GLuint prog = glCreateProgram();
  glAttachShader(prog, cs);
  glLinkProgram(prog);
  glDeleteShader(cs);
  GLint linked = 0;
  glGetProgramiv(prog, GL_LINK_STATUS, &linked);
  if (!linked) {
    char buf[1024];
    glGetProgramInfoLog(prog, sizeof(buf), nullptr, buf);
    glDeleteProgram(prog);
    return fail(std::string("program 链接失败: ") + buf);
  }

  GLuint tex_in = 0, tex_out = 0, fbo = 0;
  glGenTextures(1, &tex_in);
  glBindTexture(GL_TEXTURE_2D_ARRAY, tex_in);
  glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R16F, w, h, 4);
  glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  std::vector<float> buf(static_cast<size_t>(w) * h);
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      buf[static_cast<size_t>(y) * w + x] = basePattern(x, y);
    }
  }
  for (int layer = 0; layer < 4; ++layer) {
    const uint32_t lz = static_cast<uint32_t>(layer + 1);
    for (int y = 0; y < h; ++y) {
      for (int x = 0; x < w; ++x) {
        const size_t i = static_cast<size_t>(y) * w + x;
        buf[i] += 0.02f * hashNoise(static_cast<uint32_t>(x),
                                    static_cast<uint32_t>(y), lz);
      }
    }
    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, w, h, 1, GL_RED,
                    GL_FLOAT, buf.data());
  }

  glGenTextures(1, &tex_out);
  glBindTexture(GL_TEXTURE_2D, tex_out);
  glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
  glGenFramebuffers(1, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex_out, 0);
  const bool out_renderable =
      glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;

  const GLint u_frames = glGetUniformLocation(prog, "uFrames");
  const GLint u_size = glGetUniformLocation(prog, "uSize");
  const GLint u_stride = glGetUniformLocation(prog, "uStride");
  const GLint u_sigma = glGetUniformLocation(prog, "uSigma");
  const GLint u_c = glGetUniformLocation(prog, "uC");

  const int gx = w / unit;
  const int gy = h / stride;
  auto dispatch = [&] {
    glUseProgram(prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, tex_in);
    glUniform1i(u_frames, 0);
    glUniform2i(u_size, w, h);
    glUniform1i(u_stride, stride);
    glUniform1f(u_sigma, 0.02f);
    glUniform1f(u_c, 256.0f);
    glBindImageTexture(0, tex_out, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA16F);
    glDispatchCompute(gx, gy, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
  };

  dispatch();  // 预热 1 轮不计入统计
  glFinish();
  while (glGetError() != GL_NO_ERROR) {
  }

  std::vector<double> gpu_ms, cpu_ms;
  GpuTimer gpu;
  for (int i = 0; i < iters; ++i) {
    const double c0 = nowMs();
    if (r.gpu_timer_used) gpu.begin();
    dispatch();
    const double g = r.gpu_timer_used ? gpu.endMs() : -1.0;
    const double c = nowMs() - c0;
    if (g >= 0) gpu_ms.push_back(g);
    cpu_ms.push_back(c);
  }
  r.gpu_median_ms = pctOf(gpu_ms, 0.5);
  r.gpu_p90_ms = pctOf(gpu_ms, 0.9);
  r.cpu_median_ms = pctOf(cpu_ms, 0.5);
  r.cpu_p90_ms = pctOf(cpu_ms, 0.9);

  // 读回校验：非重叠模式下输出应回到"压噪后的参考帧 × 窗"（单 tile 语义，
  // 半重叠 overlap-add 才恢复无窗图）。取 tile 内坐标 8（窗值 ≈0.98）避开门窗零区。
  if (!overlap && out_renderable) {
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    const int xs[4] = {16 * 3 + 8, w / 2 + 8, w / 4 + 8, w - 16 * 3 - 8};
    const int ys[4] = {16 * 3 + 8, h / 2 + 8, h / 4 + 8, h - 16 * 3 - 8};
    auto winAt = [](int v) {
      const int t = v & 15;
      return 0.5f - 0.5f * std::cos(6.283185307179586f * (t + 0.5f) / 16.0f);
    };
    bool finite = true;
    bool close = true;
    for (int k = 0; k < 4; ++k) {
      float px[4] = {0, 0, 0, 0};
      glReadPixels(xs[k], ys[k], 1, 1, GL_RGBA, GL_FLOAT, px);
      if (!std::isfinite(px[0])) finite = false;
      const float expected =
          basePattern(xs[k], ys[k]) * winAt(xs[k]) * winAt(ys[k]);
      if (std::fabs(px[0] - expected) > 0.06f) close = false;
    }
    r.readback_ok = finite && close;
    if (glGetError() != GL_NO_ERROR) r.readback_ok = false;
  } else {
    r.readback_ok = out_renderable;
  }
  while (glGetError() != GL_NO_ERROR) {
  }

  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glDeleteFramebuffers(1, &fbo);
  glDeleteTextures(1, &tex_out);
  glDeleteTextures(1, &tex_in);
  glDeleteProgram(prog);
  r.ok = true;
  logger.log(LogLevel::kInfo, "engine", "spike.fft", r.toJson());
  return r;
}

std::string FftSpikeResult::toJson() const {
  auto num = [](double v) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%.3f", v);
    return std::string(buf);
  };
  auto boolean = [](bool v) { return std::string(v ? "true" : "false"); };
  std::string j = "{\"ok\":" + boolean(ok);
  j += ",\"width\":" + std::to_string(width);
  j += ",\"height\":" + std::to_string(height);
  j += ",\"iterations\":" + std::to_string(iterations);
  j += ",\"overlap\":" + boolean(overlap);
  j += ",\"tiles\":" + std::to_string(tiles);
  j += ",\"sharedBytes\":" + std::to_string(shared_bytes);
  j += ",\"maxSharedMemory\":" + std::to_string(max_shared);
  j += ",\"gpuTimerUsed\":" + boolean(gpu_timer_used);
  j += ",\"readbackOk\":" + boolean(readback_ok);
  j += ",\"gpuMedianMs\":" + num(gpu_median_ms);
  j += ",\"gpuP90Ms\":" + num(gpu_p90_ms);
  j += ",\"cpuMedianMs\":" + num(cpu_median_ms);
  j += ",\"cpuP90Ms\":" + num(cpu_p90_ms);
  j += ",\"error\":" +
       (error.empty() ? "null" : "\"" + jsonEscape(error) + "\"");
  j += "}";
  return j;
}

}  // namespace filmkit
