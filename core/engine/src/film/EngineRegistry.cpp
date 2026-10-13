#include "EngineRegistry.h"

namespace filmkit {
namespace {

// 按 slot 升序；阶段 0 只有内置 filmic。
// working_space = "LinearSRGB"：阶段 1 的 `uProfileToEngineTransform` 传单位阵
// ⇒ 引擎域 = D4 线性 sRGB（方案 §3.2）。
constexpr EngineEntry kEngines[kEngineCount] = {
    {0, "filmic", "LinearSRGB", ExposureDomain::kLinear, 0.0f},
};

}  // namespace

const EngineEntry* engines() { return kEngines; }

const EngineEntry* findEngine(int slot) {
  // 表只有 1~几项：线性扫描（不引入 map / 排序）。
  for (int i = 0; i < kEngineCount; ++i) {
    if (kEngines[i].slot == slot) return &kEngines[i];
  }
  return nullptr;
}

bool isValidEngineSlot(int slot) { return findEngine(slot) != nullptr; }

const char* exposureDomainName(ExposureDomain d) {
  switch (d) {
    case ExposureDomain::kLinear:
      return "Linear";
    case ExposureDomain::kCurve:
      return "Curve";
  }
  return "Unknown";
}

}  // namespace filmkit
