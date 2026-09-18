#include "SpikePass.h"

#include <GLES3/gl31.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <vector>

#include "../gl/Capabilities.h"
#include "../log/GpuTimer.h"
#include "../log/Logger.h"
#include "../log/Timer.h"
#include "../pipeline/Pipeline.h"

namespace filmkit {

namespace {

// ── spike 着色器：程序化内容，保证真实的 ALU/带宽压力 ──────────────────
const char* kVertSrc = R"(#version 300 es
precision highp float;
out vec2 vUV;
void main() {
    int id = gl_VertexID;
    vec2 p = vec2(float((id << 1) & 2), float(id & 2));
    vUV = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// 第一段·compute 路径：写入 RGBA16F（场景线性，M0 的汇流点载体）。
// 注意：GLSL ES 3.1 的 image 类型无默认精度，必须显式 highp（Mali 实测强制）。
const char* kComputeFillSrc = R"(#version 310 es
layout(local_size_x = 8, local_size_y = 8) in;
layout(rgba16f, binding = 0) uniform writeonly highp image2D uImage;
uniform highp vec2 uSize;
void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    if (pix.x >= int(uSize.x) || pix.y >= int(uSize.y)) return;
    vec2 uv = (vec2(pix) + 0.5) / uSize;
    float v = 0.5 + 0.25 * sin(uv.x * 40.0) + 0.25 * cos(uv.y * 40.0);
    imageStore(uImage, pix, vec4(uv, v, 1.0));
})";

// 第一段·fragment 降级路径（无 ES 3.1 compute 的设备）。
const char* kFragmentFillSrc = R"(#version 300 es
precision highp float;
in vec2 vUV;
layout(location = 0) out vec4 oColor;
uniform highp vec2 uSize;
void main() {
    vec2 uv = vUV;
    float v = 0.5 + 0.25 * sin(uv.x * 40.0) + 0.25 * cos(uv.y * 40.0);
    oColor = vec4(uv, v, 1.0);
})";

// 第二段：采样 RGBA16F → filmic 变换 → 写回 RGBA16F。
const char* kTransformSrc = R"(#version 300 es
precision highp float;
in vec2 vUV;
layout(location = 0) out vec4 oColor;
uniform sampler2D uTex;
vec3 filmic(vec3 x) { return x / (x + 0.155) * 1.019; }
void main() {
    vec3 c = texture(uTex, vUV).rgb;
    oColor = vec4(filmic(c * 2.0), 1.0);
})";

// 第三段：输出变换（gamma + dither）→ 量化到 RGBA8。
const char* kOutputSrc = R"(#version 300 es
precision highp float;
in vec2 vUV;
layout(location = 0) out vec4 oColor;
uniform sampler2D uTex;
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
    vec3 c = texture(uTex, vUV).rgb;
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
    c += (hash(gl_FragCoord.xy) - 0.5) / 255.0;  // dither 占位，M3 换蓝噪声
    oColor = vec4(c, 1.0);
})";

GLuint compileShader(GLenum type, const char* src, std::string* err) {
  GLuint sh = glCreateShader(type);
  glShaderSource(sh, 1, &src, nullptr);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char buf[512];
    glGetShaderInfoLog(sh, sizeof(buf), nullptr, buf);
    if (err) *err = std::string("shader 编译失败: ") + buf;
    glDeleteShader(sh);
    return 0;
  }
  return sh;
}

GLuint linkProgram(GLuint a, GLuint b, std::string* err) {
  // compute 路径传 b=0（单 shader program）
  GLuint prog = glCreateProgram();
  glAttachShader(prog, a);
  if (b) glAttachShader(prog, b);
  glLinkProgram(prog);
  GLint ok = 0;
  glGetProgramiv(prog, GL_LINK_STATUS, &ok);
  if (!ok) {
    char buf[512];
    glGetProgramInfoLog(prog, sizeof(buf), nullptr, buf);
    if (err) *err = std::string("program 链接失败: ") + buf;
    glDeleteProgram(prog);
    return 0;
  }
  return prog;
}

GLuint makeTex(int w, int h, GLenum internal_format) {
  GLuint t = 0;
  glGenTextures(1, &t);
  glBindTexture(GL_TEXTURE_2D, t);
  glTexStorage2D(GL_TEXTURE_2D, 1, internal_format, w, h);
  // 管线内为 1:1 采样，NEAREST 即可，避免依赖 half-float 线性过滤扩展
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  return t;
}

GLuint makeFbo(GLuint tex) {
  GLuint f = 0;
  glGenFramebuffers(1, &f);
  glBindFramebuffer(GL_FRAMEBUFFER, f);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         tex, 0);
  return f;
}

bool fboComplete(GLuint fbo) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
}

double pct(std::vector<double> v, double p) {
  if (v.empty()) return -1.0;
  std::sort(v.begin(), v.end());
  const auto idx = static_cast<size_t>(
      std::lround(p * static_cast<double>(v.size() - 1)));
  return v[idx];
}

struct GlResources {
  GLuint vert = 0;
  GLuint prog_fill = 0;       // compute 或 fragment 二选一
  GLuint prog_transform = 0;
  GLuint prog_output = 0;
  GLuint t_scene = 0, t_work = 0, t_out = 0;
  GLuint f_scene = 0, f_work = 0, f_out = 0;

  ~GlResources() {
    if (vert) glDeleteShader(vert);
    if (prog_fill) glDeleteProgram(prog_fill);
    if (prog_transform) glDeleteProgram(prog_transform);
    if (prog_output) glDeleteProgram(prog_output);
    if (t_scene) glDeleteTextures(1, &t_scene);
    if (t_work) glDeleteTextures(1, &t_work);
    if (t_out) glDeleteTextures(1, &t_out);
    if (f_scene) glDeleteFramebuffers(1, &f_scene);
    if (f_work) glDeleteFramebuffers(1, &f_work);
    if (f_out) glDeleteFramebuffers(1, &f_out);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
  }
};

}  // namespace

SpikeResult runSpikePass(int w, int h, int iters, bool use_compute) {
  SpikeResult r;
  r.width = w;
  r.height = h;
  r.iterations = iters;
  r.use_compute = use_compute;
  auto& logger = Logger::instance();
  auto fail = [&](const std::string& msg) {
    r.error = msg;
    logger.log(LogLevel::kError, "engine", "spike.error",
               "{\"message\":\"" + jsonEscape(msg) + "\"}");
    return r;
  };

  if (w <= 0 || h <= 0 || iters <= 0) return fail("非法尺寸/轮数");

  const Capabilities caps = detectCapabilities();
  r.gpu_timer_used = caps.ext_disjoint_timer_query;
  if (use_compute && !caps.es31_compute) {
    use_compute = false;  // 降级 fragment 路径（开发计划 §10）
    r.use_compute = false;
  }

  GlResources glr;
  std::string err;
  glr.vert = compileShader(GL_VERTEX_SHADER, kVertSrc, &err);
  if (!glr.vert) return fail(err);

  if (use_compute) {
    const GLuint cs = compileShader(GL_COMPUTE_SHADER, kComputeFillSrc, &err);
    if (!cs) return fail(err);
    glr.prog_fill = linkProgram(cs, 0, &err);
    glDeleteShader(cs);
  } else {
    const GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentFillSrc, &err);
    if (!fs) return fail(err);
    glr.prog_fill = linkProgram(glr.vert, fs, &err);
    glDeleteShader(fs);
  }
  if (!glr.prog_fill) return fail(err);

  const GLuint fs_tr = compileShader(GL_FRAGMENT_SHADER, kTransformSrc, &err);
  if (!fs_tr) return fail(err);
  glr.prog_transform = linkProgram(glr.vert, fs_tr, &err);
  glDeleteShader(fs_tr);
  if (!glr.prog_transform) return fail(err);

  const GLuint fs_out = compileShader(GL_FRAGMENT_SHADER, kOutputSrc, &err);
  if (!fs_out) return fail(err);
  glr.prog_output = linkProgram(glr.vert, fs_out, &err);
  glDeleteShader(fs_out);
  if (!glr.prog_output) return fail(err);

  glr.t_scene = makeTex(w, h, GL_RGBA16F);
  glr.t_work = makeTex(w, h, GL_RGBA16F);
  glr.t_out = makeTex(w, h, GL_RGBA8);
  glr.f_scene = makeFbo(glr.t_scene);
  glr.f_work = makeFbo(glr.t_work);
  glr.f_out = makeFbo(glr.t_out);
  r.rgba16f_renderable = fboComplete(glr.f_scene) && fboComplete(glr.f_work);
  if (!r.rgba16f_renderable) {
    return fail("RGBA16F FBO 不完整（缺 GL_EXT_color_buffer_half_float），"
                "管线位深前提不成立");
  }

  const GLint u_size_fill =
      glGetUniformLocation(glr.prog_fill, "uSize");
  const GLint u_tex_transform =
      glGetUniformLocation(glr.prog_transform, "uTex");
  const GLint u_tex_output = glGetUniformLocation(glr.prog_output, "uTex");

  // 每 pass 一个 GpuTimer；扩展不可用时只记 CPU 时间
  const bool use_gpu_timer = caps.ext_disjoint_timer_query;
  GpuTimer gpu1, gpu2, gpu3;

  const auto fill_scene_compute = [&] {
    glUseProgram(glr.prog_fill);
    glUniform2f(u_size_fill, static_cast<float>(w), static_cast<float>(h));
    glBindImageTexture(0, glr.t_scene, 0, GL_FALSE, 0, GL_WRITE_ONLY,
                       GL_RGBA16F);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
  };
  const auto fill_scene_fragment = [&] {
    glBindFramebuffer(GL_FRAMEBUFFER, glr.f_scene);
    glViewport(0, 0, w, h);
    glUseProgram(glr.prog_fill);
    glUniform2f(u_size_fill, static_cast<float>(w), static_cast<float>(h));
    glDrawArrays(GL_TRIANGLES, 0, 3);
  };

  Pipeline pipe;
  const auto addPass = [&](const char* name, GpuTimer* gpu,
                           const std::function<void()>& body) {
    pipe.addPass(name, [gpu, body](PassTiming* t) {
      const double c0 = nowMs();
      if (gpu) gpu->begin();
      body();
      t->gpu_ms = gpu ? gpu->endMs() : -1.0;
      // cpu_ms 含 endMs 内 glFinish（M0 约束，见 GpuTimer.h）
      t->cpu_ms = nowMs() - c0;
      return true;
    });
  };

  const char* scene_name =
      use_compute ? "1.scene_fill_compute" : "1.scene_fill_fragment";
  addPass(scene_name, use_gpu_timer ? &gpu1 : nullptr,
          use_compute ? std::function<void()>(fill_scene_compute)
                      : std::function<void()>(fill_scene_fragment));
  addPass("2.transform", use_gpu_timer ? &gpu2 : nullptr, [&] {
    glBindFramebuffer(GL_FRAMEBUFFER, glr.f_work);
    glViewport(0, 0, w, h);
    glUseProgram(glr.prog_transform);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, glr.t_scene);
    glUniform1i(u_tex_transform, 0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  });
  addPass("3.output", use_gpu_timer ? &gpu3 : nullptr, [&] {
    glBindFramebuffer(GL_FRAMEBUFFER, glr.f_out);
    glViewport(0, 0, w, h);
    glUseProgram(glr.prog_output);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, glr.t_work);
    glUniform1i(u_tex_output, 0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
  });

  // 预热 1 轮不计入统计
  std::vector<PassTiming> warmup;
  if (!pipe.run(&warmup)) return fail("预热 pass 失败");

  std::vector<std::vector<double>> gpu_stats(3), cpu_stats(3);
  for (int i = 0; i < iters; ++i) {
    std::vector<PassTiming> ts;
    if (!pipe.run(&ts)) return fail("pass 执行失败");
    for (size_t k = 0; k < ts.size() && k < 3; ++k) {
      if (ts[k].gpu_ms >= 0) gpu_stats[k].push_back(ts[k].gpu_ms);
      cpu_stats[k].push_back(ts[k].cpu_ms);
    }
  }

  // 输出正确性：x 向梯度单调、alpha 全 1（验证三段链路真的通了）
  glBindFramebuffer(GL_FRAMEBUFFER, glr.f_out);
  GLubyte px[3][4];
  const int xs[3] = {w / 4, w / 2, (3 * w) / 4};
  for (int k = 0; k < 3; ++k) {
    glReadPixels(xs[k], h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px[k]);
  }
  r.readback_ok = px[0][0] <= px[1][0] && px[1][0] <= px[2][0] &&
                  (px[2][0] - px[0][0]) > 10 && px[0][3] == 255;
  if (!r.readback_ok) {
    char buf[128];
    snprintf(buf, sizeof(buf), "读回校验失败: r=%u/%u/%u a=%u", px[0][0],
             px[1][0], px[2][0], px[0][3]);
    return fail(buf);
  }
  r.ok = true;

  const char* names[3] = {scene_name, "2.transform", "3.output"};
  for (int k = 0; k < 3; ++k) {
    SpikePassSummary s;
    s.name = names[k];
    s.gpu_median_ms = pct(gpu_stats[k], 0.5);
    s.gpu_p90_ms = pct(gpu_stats[k], 0.9);
    s.cpu_median_ms = pct(cpu_stats[k], 0.5);
    s.cpu_p90_ms = pct(cpu_stats[k], 0.9);
    r.passes.push_back(s);
  }

  logger.log(LogLevel::kInfo, "engine", "spike.run", r.toJson());
  return r;
}

std::string SpikeResult::toJson() const {
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
  j += ",\"useCompute\":" + boolean(use_compute);
  j += ",\"gpuTimerUsed\":" + boolean(gpu_timer_used);
  j += ",\"rgba16fRenderable\":" + boolean(rgba16f_renderable);
  j += ",\"readbackOk\":" + boolean(readback_ok);
  j += ",\"passes\":[";
  for (size_t i = 0; i < passes.size(); ++i) {
    if (i) j += ",";
    j += "{\"name\":\"" + jsonEscape(passes[i].name) + "\"";
    j += ",\"gpuMedianMs\":" + num(passes[i].gpu_median_ms);
    j += ",\"gpuP90Ms\":" + num(passes[i].gpu_p90_ms);
    j += ",\"cpuMedianMs\":" + num(passes[i].cpu_median_ms);
    j += ",\"cpuP90Ms\":" + num(passes[i].cpu_p90_ms) + "}";
  }
  j += "]";
  j += ",\"error\":" +
       (error.empty() ? "null" : "\"" + jsonEscape(error) + "\"");
  j += "}";
  return j;
}

}  // namespace filmkit
