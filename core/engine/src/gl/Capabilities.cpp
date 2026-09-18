#include "Capabilities.h"

#include <GLES3/gl31.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "../log/Logger.h"

namespace filmkit {

namespace {
std::string glStr(GLenum e) {
  const GLubyte* s = glGetString(e);
  return s ? std::string(reinterpret_cast<const char*>(s)) : std::string();
}
}  // namespace

bool Capabilities::hasExtension(const char* name) const {
  for (const auto& e : extensions) {
    if (e == name) return true;
  }
  return false;
}

Capabilities detectCapabilities() {
  Capabilities c;
  c.gl_version = glStr(GL_VERSION);
  c.renderer = glStr(GL_RENDERER);
  c.vendor = glStr(GL_VENDOR);
  c.sl_version = glStr(GL_SHADING_LANGUAGE_VERSION);

  if (sscanf(c.gl_version.c_str(), "OpenGL ES %d.%d", &c.es_major,
             &c.es_minor) != 2) {
    c.es_major = c.es_minor = 0;
  }
  c.es31_compute = c.es_major > 3 || (c.es_major == 3 && c.es_minor >= 1);

  GLint n = 0;
  glGetIntegerv(GL_NUM_EXTENSIONS, &n);
  if (n > 0) {
    c.extensions.reserve(static_cast<size_t>(n));
    for (GLint i = 0; i < n; ++i) {
      const GLubyte* e = glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i));
      if (e) c.extensions.emplace_back(reinterpret_cast<const char*>(e));
    }
  }

  c.ext_color_buffer_float = c.hasExtension("GL_EXT_color_buffer_float");
  c.ext_color_buffer_half_float =
      c.hasExtension("GL_EXT_color_buffer_half_float");
  c.ext_disjoint_timer_query = c.hasExtension("GL_EXT_disjoint_timer_query");
  c.oes_texture_half_float_linear =
      c.hasExtension("GL_OES_texture_half_float_linear");

  GLint v = 0;
  glGetIntegerv(GL_MAX_TEXTURE_SIZE, &v);
  c.max_texture_size = v;
  glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &v);
  c.max_renderbuffer_size = v;
  if (c.es31_compute) {
    glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &v);
    c.max_compute_work_group_invocations = v;
    glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, &v);
    c.max_compute_shared_memory = v;
    glGetIntegerv(GL_MAX_IMAGE_UNITS, &v);
    c.max_image_units = v;
  }
  return c;
}

std::string Capabilities::toJson() const {
  std::string j;
  j.reserve(512 + extensions.size() * 40);
  j += "{\"glVersion\":\"" + jsonEscape(gl_version) + "\"";
  j += ",\"renderer\":\"" + jsonEscape(renderer) + "\"";
  j += ",\"vendor\":\"" + jsonEscape(vendor) + "\"";
  j += ",\"slVersion\":\"" + jsonEscape(sl_version) + "\"";
  j += ",\"esMajor\":" + std::to_string(es_major);
  j += ",\"esMinor\":" + std::to_string(es_minor);
  auto b = [](bool v) { return v ? "true" : "false"; };
  j += std::string(",\"es31Compute\":") + b(es31_compute);
  j += ",\"extColorBufferFloat\":" + std::string(b(ext_color_buffer_float));
  j += ",\"extColorBufferHalfFloat\":" +
       std::string(b(ext_color_buffer_half_float));
  j += ",\"extDisjointTimerQuery\":" +
       std::string(b(ext_disjoint_timer_query));
  j += ",\"halfFloatLinear\":" + std::string(b(oes_texture_half_float_linear));
  j += ",\"maxTextureSize\":" + std::to_string(max_texture_size);
  j += ",\"maxRenderbufferSize\":" + std::to_string(max_renderbuffer_size);
  j += ",\"maxComputeInvocations\":" +
       std::to_string(max_compute_work_group_invocations);
  j += ",\"maxComputeSharedMemory\":" +
       std::to_string(max_compute_shared_memory);
  j += ",\"maxImageUnits\":" + std::to_string(max_image_units);
  j += ",\"extensionCount\":" + std::to_string(extensions.size());
  j += ",\"extensions\":[";
  for (size_t i = 0; i < extensions.size(); ++i) {
    if (i) j += ",";
    j += "\"" + jsonEscape(extensions[i]) + "\"";
  }
  j += "]}";
  return j;
}

}  // namespace filmkit
