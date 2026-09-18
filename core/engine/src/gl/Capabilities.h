#pragma once

#include <string>
#include <vector>

namespace filmkit {

// 设备 GL 能力快照（M0 验收项）。M1+ 采集层会在此追加 Camera2 能力。
struct Capabilities {
  std::string gl_version;
  std::string renderer;
  std::string vendor;
  std::string sl_version;
  int es_major = 0;
  int es_minor = 0;

  bool es31_compute = false;                    // ES >= 3.1：compute shader
  bool ext_color_buffer_float = false;          // RGBA32F 可渲染
  bool ext_color_buffer_half_float = false;     // RGBA16F 可渲染（管线前提）
  bool ext_disjoint_timer_query = false;        // GPU 逐 pass 计时
  bool oes_texture_half_float_linear = false;   // 半精度纹理可线性过滤

  int max_texture_size = 0;
  int max_renderbuffer_size = 0;
  int max_compute_work_group_invocations = 0;
  int max_compute_shared_memory = 0;
  int max_image_units = 0;

  std::vector<std::string> extensions;

  bool hasExtension(const char* name) const;
  std::string toJson() const;  // 单行 JSON
};

// 必须在持有当前 GL 上下文的线程调用；无上下文时返回空快照。
Capabilities detectCapabilities();

}  // namespace filmkit
