#include "FrameRenderer.h"

#include <cmath>
#include <string>

#include "../log/Logger.h"

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace filmkit {
namespace {

// 顶点：全屏三角 + 90° 步进旋转 + center-crop，最后经 SurfaceTexture 矩阵
// （含生产者翻转）映射到 OES 纹理坐标。
const char* kVertRot = R"(#version 300 es
precision highp float;
out vec2 vUV0;
uniform mat4 uST;
uniform int uRot;
uniform vec2 uScale;
void main() {
    int id = gl_VertexID;
    vec2 p = vec2(float((id << 1) & 2), float(id & 2));
    vec2 c = p - 0.5;
    vec2 r = c;
    if (uRot == 1) r = vec2(-c.y, c.x);
    else if (uRot == 2) r = vec2(-c.x, -c.y);
    else if (uRot == 3) r = vec2(c.y, -c.x);
    vec2 uv = r * uScale + 0.5;
    vUV0 = (uST * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// 顶点：无变换全屏三角（内部 pass 输出用）
const char* kVertPlain = R"(#version 300 es
precision highp float;
out vec2 vUV0;
void main() {
    int id = gl_VertexID;
    vec2 p = vec2(float((id << 1) & 2), float(id & 2));
    vUV0 = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// Pass1：SurfaceTexture(OES) → 场景线性 RGB。
// 参照 PhotonCamera：不做 YUV 平面手动上传（X300 Mali 上 RG8 TexSub
// 静默失败），由系统 SurfaceTexture 路径完成 YUV→RGB。
const char* kFsOes = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform samplerExternalOES uTex;
uniform vec3 uWb;
uniform float uExposure;
void main() {
    vec3 rgb = clamp(texture(uTex, vUV0).rgb, 0.0, 1.0);
    vec3 lin = pow(rgb, vec3(2.2)) * uWb * uExposure;
    oColor = vec4(lin, 1.0);
})";

// 静拍 pass1：采样 CPU 转换后的 RGB888 全分辨率纹理。
// 坐标系对齐：普通纹理 (0,0) 在左上、OES 在左下——vUV0.y 取反对齐 OES 习惯，
// 使静拍与预览共用同一套 uRot 旋转几何（静拍 rot = 界面方向 + 传感器旋转）。
const char* kFsStill = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform sampler2D uTex;
uniform vec3 uWb;
uniform float uExposure;
void main() {
    vec2 uv = vec2(vUV0.x, 1.0 - vUV0.y);
    vec3 rgb = clamp(texture(uTex, uv).rgb, 0.0, 1.0);
    vec3 lin = pow(rgb, vec3(2.2)) * uWb * uExposure;
    oColor = vec4(lin, 1.0);
})";

// Pass1·填充测试：绕过 OES 采样（真机二分定位用）。
const char* kFsFillTest = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
void main() {
    float v = 0.5 + 0.25 * sin(vUV0.x * 40.0) + 0.25 * cos(vUV0.y * 40.0);
    oColor = vec4(vUV0, v, 1.0);
})";

// Pass2：胶片模拟内核（M1 简化模型；M3 升级密度域完整模型，§5）。
const char* kFsFilm = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform float uContrast;
uniform vec3 uShoulder;
uniform vec3 uToe;
uniform mat3 uCross;
uniform vec3 uSat;    // 阴影/中间/高光 饱和度
uniform vec2 uSplit;  // 阴影暖 / 高光冷
uniform float uMono;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
// ±4EV 内为有效域（toe/shoulder 只在这里数值安全）；域外线性延展——
// 硬截断曾把 -4.5EV 的暗部 14% 像素钉死在同一灰度（直方图尖峰+色带）
float filmCurve(float x, float toe, float shoulder) {
    if (x < 0.0 || x > 1.0) return x; // 域外延展段：无 toe/shoulder（exp 会发散）
    x = clamp((x - 0.5) * uContrast + 0.5, 0.0, 1.0);
    float y = x;
    y -= toe * exp(-x * 6.0) * x * (1.0 - x);
    y += shoulder * exp(-(1.0 - x) * 6.0) * x * (1.0 - x);
    return clamp(y, 0.0, 1.0);
}
void main() {
    vec3 lin = texture(uTex, vUV0).rgb;
    // log 域：以 0.18 中灰为轴。±4EV 有效域斜率 0.125（对比不变），
    // 域外 -6.5..-4 / 4..6.5EV 半斜率延展，更暗/更亮继续线性外推到 ~±9EV
    vec3 e = log2(max(lin, vec3(1e-6)) / 0.18);
    vec3 lg = e * 0.125 + 0.5;
    lg = mix(lg, 1.0 + (e - 4.0) * 0.0625, step(4.0, e));
    lg = mix(lg, (e + 4.0) * 0.0625, step(e, -4.0));
    lg = uCross * lg;
    vec3 g;
    g.r = filmCurve(lg.r, uToe.r, uShoulder.r);
    g.g = filmCurve(lg.g, uToe.g, uShoulder.g);
    g.b = filmCurve(lg.b, uToe.b, uShoulder.b);
    vec3 out3 = 0.18 * exp2((g - 0.5) * 8.0);
    float l = luma(out3);
    float ws = exp(-l * 3.0);
    float wh = smoothstep(0.5, 1.0, l);
    float wm = max(0.0, 1.0 - ws - wh);
    float sat = clamp(uSat.x * ws + uSat.y * wm + uSat.z * wh, 0.0, 2.0);
    out3 = mix(vec3(l), out3, sat);
    out3 += uSplit.x * ws * vec3(0.05, 0.02, -0.04)
          + uSplit.y * wh * vec3(-0.03, 0.0, 0.05);
    out3 = mix(out3, vec3(luma(clamp(out3, 0.0, 4.0))), uMono);
    oColor = vec4(out3, 1.0);
})";

// Pass3：输出变换（OETF + 颗粒 + dither），量化到 8bit
const char* kFsOut = R"(#version 300 es
precision highp float;
in vec2 vUV0;
layout(location = 0) out vec4 oColor;
uniform highp sampler2D uTex;
uniform vec2 uPx;
uniform float uGrainAmount;
uniform float uGrainSize;
uniform float uTime;
uniform float uFlipY;  // 拍照读回 GL 底行在前，翻转后 JPEG 方向正确
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i), b = hash(i + vec2(1, 0));
    float c = hash(i + vec2(0, 1)), d = hash(i + vec2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
void main() {
    vec2 uv = vUV0;
    if (uFlipY > 0.5) uv.y = 1.0 - uv.y;
    vec3 c = texture(uTex, uv).rgb;
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
    if (uGrainAmount > 0.0) {
        vec2 gp = gl_FragCoord.xy * 6.0 / max(uGrainSize, 0.25) + uTime;
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float w = 4.0 * l * (1.0 - l);
        c += (vnoise(gp) - 0.5) * uGrainAmount * w;
    }
    c += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
    oColor = vec4(clamp(c, 0.0, 1.0), 1.0);
})";

GLuint compileShader(GLenum type, const char* src) {
  GLuint sh = glCreateShader(type);
  glShaderSource(sh, 1, &src, nullptr);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char buf[512];
    glGetShaderInfoLog(sh, sizeof(buf), nullptr, buf);
    Logger::instance().log(LogLevel::kError, "engine", "render.shader_error",
                           "{\"log\":\"" + jsonEscape(buf) + "\"}");
    glDeleteShader(sh);
    return 0;
  }
  return sh;
}

GLuint linkProgram(GLuint vs, GLuint fs) {
  GLuint prog = glCreateProgram();
  glAttachShader(prog, vs);
  glAttachShader(prog, fs);
  glLinkProgram(prog);
  GLint ok = 0;
  glGetProgramiv(prog, GL_LINK_STATUS, &ok);
  if (!ok) {
    char buf[512];
    glGetProgramInfoLog(prog, sizeof(buf), nullptr, buf);
    Logger::instance().log(LogLevel::kError, "engine", "render.link_error",
                           "{\"log\":\"" + jsonEscape(buf) + "\"}");
    glDeleteProgram(prog);
    return 0;
  }
  return prog;
}

// IEEE754 半精度 → 单精度（诊断读回用）
float halfToFloat(unsigned short h) {
  const unsigned int sign = (h >> 15) & 1u;
  const unsigned int e = (h >> 10) & 0x1fu;
  const unsigned int m = h & 0x3ffu;
  float v;
  if (e == 0) {
    v = std::ldexp(static_cast<float>(m), -24);
  } else if (e == 31) {
    v = m != 0 ? 0.0f : 1e30f;
  } else {
    v = std::ldexp(static_cast<float>(m | 0x400u), static_cast<int>(e) - 25);
  }
  return sign != 0u ? -v : v;
}

}  // namespace

FrameRenderer::~FrameRenderer() { release(); }

bool FrameRenderer::init() {
  vs_rot_ = compileShader(GL_VERTEX_SHADER, kVertRot);
  vs_plain_ = compileShader(GL_VERTEX_SHADER, kVertPlain);
  const GLuint fs_oes = compileShader(GL_FRAGMENT_SHADER, kFsOes);
  const GLuint fs_still = compileShader(GL_FRAGMENT_SHADER, kFsStill);
  const GLuint fs_film = compileShader(GL_FRAGMENT_SHADER, kFsFilm);
  const GLuint fs_out = compileShader(GL_FRAGMENT_SHADER, kFsOut);
  const GLuint fs_fill = compileShader(GL_FRAGMENT_SHADER, kFsFillTest);
  if (!vs_rot_ || !vs_plain_ || !fs_oes || !fs_still || !fs_film || !fs_out ||
      !fs_fill)
    return false;

  prog_oes_ = linkProgram(vs_rot_, fs_oes);
  prog_still_ = linkProgram(vs_rot_, fs_still);
  prog_film_ = linkProgram(vs_plain_, fs_film);
  prog_out_ = linkProgram(vs_plain_, fs_out);
  prog_fill_ = linkProgram(vs_rot_, fs_fill);
  glDeleteShader(fs_oes);
  glDeleteShader(fs_still);
  glDeleteShader(fs_film);
  glDeleteShader(fs_out);
  glDeleteShader(fs_fill);
  if (!prog_oes_ || !prog_still_ || !prog_film_ || !prog_out_ || !prog_fill_)
    return false;

  u_oes_.st = glGetUniformLocation(prog_oes_, "uST");
  u_oes_.rot = glGetUniformLocation(prog_oes_, "uRot");
  u_oes_.scale = glGetUniformLocation(prog_oes_, "uScale");
  u_oes_.tex = glGetUniformLocation(prog_oes_, "uTex");
  u_oes_.wb = glGetUniformLocation(prog_oes_, "uWb");
  u_oes_.exposure = glGetUniformLocation(prog_oes_, "uExposure");

  u_still_.st = glGetUniformLocation(prog_still_, "uST");
  u_still_.rot = glGetUniformLocation(prog_still_, "uRot");
  u_still_.scale = glGetUniformLocation(prog_still_, "uScale");
  u_still_.tex = glGetUniformLocation(prog_still_, "uTex");
  u_still_.wb = glGetUniformLocation(prog_still_, "uWb");
  u_still_.exposure = glGetUniformLocation(prog_still_, "uExposure");
  if (u_still_.st >= 0) {  // uST 置单位阵（uniform 默认全 0）
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_still_);
    glUniformMatrix4fv(u_still_.st, 1, GL_FALSE, ident);
  }

  u_film_.tex = glGetUniformLocation(prog_film_, "uTex");
  u_film_.contrast = glGetUniformLocation(prog_film_, "uContrast");
  u_film_.shoulder = glGetUniformLocation(prog_film_, "uShoulder");
  u_film_.toe = glGetUniformLocation(prog_film_, "uToe");
  u_film_.cross = glGetUniformLocation(prog_film_, "uCross");
  u_film_.sat = glGetUniformLocation(prog_film_, "uSat");
  u_film_.split = glGetUniformLocation(prog_film_, "uSplit");
  u_film_.mono = glGetUniformLocation(prog_film_, "uMono");

  u_out_.tex = glGetUniformLocation(prog_out_, "uTex");
  u_out_.px = glGetUniformLocation(prog_out_, "uPx");
  u_out_.grain_amount = glGetUniformLocation(prog_out_, "uGrainAmount");
  u_out_.grain_size = glGetUniformLocation(prog_out_, "uGrainSize");
  u_out_.time = glGetUniformLocation(prog_out_, "uTime");
  u_out_.flip_y = glGetUniformLocation(prog_out_, "uFlipY");

  u_fill_st_ = glGetUniformLocation(prog_fill_, "uST");
  if (u_fill_st_ >= 0) {  // 填充测试不采样：uST 置单位阵（uniform 默认全 0）
    const float ident[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    glUseProgram(prog_fill_);
    glUniformMatrix4fv(u_fill_st_, 1, GL_FALSE, ident);
  }
  u_fill_.rot = glGetUniformLocation(prog_fill_, "uRot");
  u_fill_.scale = glGetUniformLocation(prog_fill_, "uScale");

  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);

  Logger::instance().log(LogLevel::kInfo, "engine", "render.init", "{}");
  return true;
}

void FrameRenderer::release() {
  if (tex_scene_) glDeleteTextures(1, &tex_scene_);
  if (tex_work_) glDeleteTextures(1, &tex_work_);
  if (tex_cap_) glDeleteTextures(1, &tex_cap_);
  if (fbo_scene_) glDeleteFramebuffers(1, &fbo_scene_);
  if (fbo_work_) glDeleteFramebuffers(1, &fbo_work_);
  if (fbo_cap_) glDeleteFramebuffers(1, &fbo_cap_);
  if (prog_oes_) glDeleteProgram(prog_oes_);
  if (prog_still_) glDeleteProgram(prog_still_);
  if (prog_film_) glDeleteProgram(prog_film_);
  if (prog_out_) glDeleteProgram(prog_out_);
  if (prog_fill_) glDeleteProgram(prog_fill_);
  if (vs_rot_) glDeleteShader(vs_rot_);
  if (vs_plain_) glDeleteShader(vs_plain_);
  if (still_tex_) glDeleteTextures(1, &still_tex_);
  still_tex_ = 0;
  tex_scene_ = tex_work_ = tex_cap_ = 0;
  fbo_scene_ = fbo_work_ = fbo_cap_ = 0;
  prog_oes_ = prog_still_ = prog_film_ = prog_out_ = prog_fill_ = 0;
  vs_rot_ = vs_plain_ = 0;
  oes_tex_ = 0;
  still_w_ = still_h_ = 0;
  scene_w_ = scene_h_ = cap_w_ = cap_h_ = 0;
}

void FrameRenderer::makeTex(GLuint* tex, GLuint* fbo, GLenum internal, int w,
                            int h) {
  if (*tex == 0) glGenTextures(1, tex);
  if (*fbo == 0) glGenFramebuffers(1, fbo);
  glBindTexture(GL_TEXTURE_2D, *tex);
  // RGBA16F 无线性过滤（X300 实测 quirk）；管内 1:1 采样 NEAREST 即可
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  // 类型必须与内部格式匹配：RGBA8 用 UNSIGNED_BYTE，RGBA16F 用 HALF_FLOAT。
  // （X300 实测：RGBA8 + HALF_FLOAT 非法组合静默失败 → 捕获 FBO 永不完整 → 照片全黑）
  const GLenum type = internal == GL_RGBA8 ? GL_UNSIGNED_BYTE : GL_HALF_FLOAT;
  glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internal), w, h, 0, GL_RGBA,
               type, nullptr);
  glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         *tex, 0);
}

// 逐 pass 诊断：GL 错误码 + 目标(0,0)像素，定位渲染断点层级
void FrameRenderer::logPassDiag(const char* stage, GLuint fbo, bool half_float) {
  const GLenum err = glGetError();
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  std::string kv =
      std::string("{\"stage\":\"") + stage + "\",\"glErr\":" + std::to_string(err);
  if (half_float) {
    GLushort raw[4] = {0, 0, 0, 0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_HALF_FLOAT, raw);
    kv += ",\"r\":" + std::to_string(halfToFloat(raw[0]));
    kv += ",\"g\":" + std::to_string(halfToFloat(raw[1]));
    kv += ",\"b\":" + std::to_string(halfToFloat(raw[2])) + "}";
  } else {
    GLubyte b4[4] = {0, 0, 0, 0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, b4);
    kv += ",\"r\":" + std::to_string(b4[0]);
    kv += ",\"g\":" + std::to_string(b4[1]);
    kv += ",\"b\":" + std::to_string(b4[2]);
    kv += ",\"a\":" + std::to_string(b4[3]) + "}";
  }
  Logger::instance().log(LogLevel::kInfo, "engine", "render.diag", kv);
}

bool FrameRenderer::ensureTargets(int w, int h) {
  if (w <= 0 || h <= 0) return false;
  if (w != scene_w_ || h != scene_h_) {
    if (tex_scene_) {
      glDeleteTextures(1, &tex_scene_);
      glDeleteFramebuffers(1, &fbo_scene_);
      glDeleteTextures(1, &tex_work_);
      glDeleteFramebuffers(1, &fbo_work_);
    }
    tex_scene_ = tex_work_ = 0;
    fbo_scene_ = fbo_work_ = 0;
    makeTex(&tex_scene_, &fbo_scene_, GL_RGBA16F, w, h);
    makeTex(&tex_work_, &fbo_work_, GL_RGBA16F, w, h);
    scene_w_ = w;
    scene_h_ = h;
  }
  // 两个 16F 目标都要完整：任一不完整时该 pass 的绘制会被驱动整个丢弃
  bool ok = true;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  ok = ok && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  if (!ok) {
    Logger::instance().log(
        LogLevel::kError, "engine", "render.fbo_incomplete",
        "{\"w\":" + std::to_string(w) + ",\"h\":" + std::to_string(h) + "}");
  }
  return ok;
}

bool FrameRenderer::submitFrame(const float* st, int rot, float sx, float sy,
                                int dst_w, int dst_h, const FilmParams& p,
                                bool fill_test) {
  if (dst_w <= 0 || dst_h <= 0) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);
  if (!ensureTargets(dst_w, dst_h)) return false;

  // 吞掉上一帧/诊断残留的 GL 错误，保证诊断只反映本帧问题
  while (glGetError() != GL_NO_ERROR) {
  }
  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);
  const bool diag = frames_.load(std::memory_order_relaxed) == 1;

  if (fill_test) {
    passFill(dst_w, dst_h);
    if (diag) logPassDiag("pass1_fill", fbo_scene_, true);
  } else {
    passOES(dst_w, dst_h, st, rot, sx, sy, p);
    if (diag) logPassDiag("pass1_oes", fbo_scene_, true);
  }
  passFilm(dst_w, dst_h, p);
  if (diag) logPassDiag("pass2_film", fbo_work_, true);

  // 必须显式绑回窗口默认帧缓冲：上一 pass 留下的绑定是 fbo_work_
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  passOutput(dst_w, dst_h, p, false);
  if (diag) logPassDiag("pass3_out", 0, false);
  return true;
}

// （拍照走 submitStill 全分辨率路径；captureLastFrame 已移除）

void FrameRenderer::passOES(int dst_w, int dst_h, const float* st, int rot,
                            float sx, float sy, const FilmParams& p) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_oes_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, oes_tex_);
  glUniformMatrix4fv(u_oes_.st, 1, GL_FALSE, st);
  glUniform1i(u_oes_.rot, rot);
  glUniform2f(u_oes_.scale, sx, sy);
  glUniform1i(u_oes_.tex, 0);
  glUniform3f(u_oes_.wb, p.wb_r, p.wb_g, p.wb_b);
  glUniform1f(u_oes_.exposure, std::exp2(p.exposure_ev));
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 静拍 pass1：采样 CPU 转换后的 RGB888 全分辨率纹理
void FrameRenderer::passStill(int dst_w, int dst_h, int rot,
                              const FilmParams& p) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_still_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, still_tex_);
  glUniform1i(u_still_.tex, 0);
  glUniform1i(u_still_.rot, rot);
  glUniform2f(u_still_.scale, 1.0f, 1.0f);  // 静拍不裁剪，整幅输出
  glUniform3f(u_still_.wb, p.wb_r, p.wb_g, p.wb_b);
  glUniform1f(u_still_.exposure, std::exp2(p.exposure_ev));
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// 全分辨率静拍：CPU YUV→RGB888（BT.601 limited）→ RGB8 上传 → 胶片链 → 读回。
// 单帧约 30–60ms CPU 转换（12.6MP），拍照场景可接受；NEON 优化记 M3。
bool FrameRenderer::submitStill(const void* y, const void* u, const void* v,
                                int w, int h, int y_stride, int uv_stride,
                                int uv_pix, int rotation, const FilmParams& p,
                                std::vector<uint8_t>* out_rgba) {
  if (w <= 0 || h <= 0 || !y || !u) return false;
  frames_.fetch_add(1, std::memory_order_relaxed);

  if (still_w_ != w || still_h_ != h) {
    if (still_tex_) glDeleteTextures(1, &still_tex_);
    still_tex_ = 0;
    glGenTextures(1, &still_tex_);
    glBindTexture(GL_TEXTURE_2D, still_tex_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB8, w, h, 0, GL_RGB, GL_UNSIGNED_BYTE,
                 nullptr);
    still_w_ = w;
    still_h_ = h;
    rgb_stage_.resize(static_cast<size_t>(w) * h * 3);
  }

  // CPU YUV→RGB（limited range 展开）。矩阵可选 BT.601（默认，旧行为）/
  // BT.709（新平台 HAL 常用）——错配时高饱和区 G 被低估 → 偏品红，
  // 设置页开关实拍 A/B 定夺
  const auto* yp = static_cast<const uint8_t*>(y);
  const auto* up = static_cast<const uint8_t*>(u);
  const auto* vp = static_cast<const uint8_t*>(v);
  const int cw = w / 2;
  // 归一化色度（±0.5）系数：R←Cr、G←Cb/Cr、B←Cb
  const float kRCr = yuv_matrix_ == 1 ? 1.793f : 1.596f;
  const float kGCb = yuv_matrix_ == 1 ? 0.213f : 0.391f;
  const float kGCr = yuv_matrix_ == 1 ? 0.533f : 0.813f;
  const float kBCb = yuv_matrix_ == 1 ? 2.112f : 2.017f;
  uint8_t* dst = rgb_stage_.data();
  for (int r = 0; r < h; ++r) {
    const uint8_t* yrow = yp + static_cast<size_t>(r) * y_stride;
    const uint8_t* urow = up + static_cast<size_t>(r / 2) * uv_stride;
    const uint8_t* vrow = v ? vp + static_cast<size_t>(r / 2) * uv_stride
                            : urow + 1;  // 半平面 V 在 U+1
    uint8_t* orow = dst + static_cast<size_t>(r) * w * 3;
    for (int c = 0; c < w; ++c) {
      // Y 必须先归一化到 [0,1] 再减 16 黑阶——若用原始 0–255 值，
      // 亮度比色度大 255 倍，色度可忽略 → 灰度照片（X300 实测事故）
      const float yv = (yrow[c] / 255.0f) * 1.164f - 0.0625f;
      const int cc = c >> (uv_pix >= 2 ? 1 : 0);
      const float cb = (uv_pix >= 2 ? urow[cc * uv_pix] : urow[cc]) / 255.0f - 0.5f;
      const float cr = (uv_pix >= 2 ? vrow[cc * uv_pix] : vrow[cc]) / 255.0f - 0.5f;
      float R = (yv + kRCr * cr) * 255.0f;
      float G = (yv - kGCb * cb - kGCr * cr) * 255.0f;
      float B = (yv + kBCb * cb) * 255.0f;
      orow[c * 3] = static_cast<uint8_t>(R < 0 ? 0 : R > 255 ? 255 : R);
      orow[c * 3 + 1] = static_cast<uint8_t>(G < 0 ? 0 : G > 255 ? 255 : G);
      orow[c * 3 + 2] = static_cast<uint8_t>(B < 0 ? 0 : B > 255 ? 255 : B);
    }
  }

  glBindTexture(GL_TEXTURE_2D, still_tex_);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGB, GL_UNSIGNED_BYTE,
                  rgb_stage_.data());
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

  // 实际渲染旋转 = 界面方向 + 传感器旋转（CPU 帧为传感器原始方向）。
  // 输出尺寸的横竖交换必须用同一旋转值判断——否则内容旋转了、画布没换向，
  // 竖向内容被塞进横画布 → 压扁（X300 实测事故）。
  const int effective_rot = (rotation + 1) % 4;
  const bool swap_wh = (effective_rot % 2) == 1;
  const int dst_w = swap_wh ? h : w;
  const int dst_h = swap_wh ? w : h;
  if (!ensureTargets(dst_w, dst_h)) return false;
  while (glGetError() != GL_NO_ERROR) {
  }

  passStill(dst_w, dst_h, effective_rot, p);
  passFilm(dst_w, dst_h, p);

  if (cap_w_ != dst_w || cap_h_ != dst_h) {
    if (tex_cap_) {
      glDeleteTextures(1, &tex_cap_);
      glDeleteFramebuffers(1, &fbo_cap_);
    }
    tex_cap_ = fbo_cap_ = 0;
    makeTex(&tex_cap_, &fbo_cap_, GL_RGBA8, dst_w, dst_h);
    cap_w_ = dst_w;
    cap_h_ = dst_h;
  }
  passOutput(dst_w, dst_h, p, true);
  logPassDiag("pass3_still", fbo_cap_, false);
  out_rgba->resize(static_cast<size_t>(dst_w) * dst_h * 4);
  glReadPixels(0, 0, dst_w, dst_h, GL_RGBA, GL_UNSIGNED_BYTE, out_rgba->data());
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  return true;
}

void FrameRenderer::passFill(int dst_w, int dst_h) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_scene_);
  glViewport(0, 0, dst_w, dst_h);
  glUseProgram(prog_fill_);
  glUniform1i(u_fill_.rot, 0);
  glUniform2f(u_fill_.scale, 1.0f, 1.0f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passFilm(int w, int h, const FilmParams& p) {
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_work_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_film_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_scene_);
  glUniform1i(u_film_.tex, 0);
  glUniform1f(u_film_.contrast, p.contrast);
  glUniform3f(u_film_.shoulder, p.shoulder[0], p.shoulder[1], p.shoulder[2]);
  glUniform3f(u_film_.toe, p.toe[0], p.toe[1], p.toe[2]);
  glUniformMatrix3fv(u_film_.cross, 1, GL_FALSE, p.cross);
  glUniform3f(u_film_.sat, p.sat_shadow, p.sat_mid, p.sat_high);
  glUniform2f(u_film_.split, p.split_warm, p.split_cool);
  glUniform1f(u_film_.mono, p.monochrome);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

void FrameRenderer::passOutput(int w, int h, const FilmParams& p, bool flip_y) {
  // flip_y 仅在拍照路径使用（渲染到 fbo_cap_）；预览直接渲染到当前窗口面
  if (flip_y) glBindFramebuffer(GL_FRAMEBUFFER, fbo_cap_);
  glViewport(0, 0, w, h);
  glUseProgram(prog_out_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, tex_work_);
  glUniform1i(u_out_.tex, 0);
  glUniform2f(u_out_.px, 1.0f / static_cast<float>(w),
              1.0f / static_cast<float>(h));
  glUniform1f(u_out_.grain_amount, p.grain_amount);
  glUniform1f(u_out_.grain_size, p.grain_size);
  glUniform1f(u_out_.time, static_cast<float>(frames_.load() % 512));
  glUniform1f(u_out_.flip_y, flip_y ? 1.0f : 0.0f);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

}  // namespace filmkit
