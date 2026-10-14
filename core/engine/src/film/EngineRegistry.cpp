#include "EngineRegistry.h"

namespace filmkit {

// 引擎 1（sigmoid）的两个 GLSL 注入段 —— 定义在 `render/FrameRenderer.cpp`（与 `kFsFilm`
// 同一处，便于 `tools/validate-shaders.sh` 与离线链按字面量名提取）。此处只声明：
// 见 `EngineRegistry.h` 顶部"链接期依赖"说明（注册表因此不再是可单独链接的单元）。
extern const char* kFsSigmoidDef;
extern const char* kFsSigmoidMain;

namespace {

// 按 slot 升序。
//   · 引擎 0 = 内置 filmic：`working_space = "LinearSRGB"`（`uProfileToEngineTransform`
//     传单位阵 ⇒ 引擎域 = D4 线性 sRGB，方案 §3.2）；4 个注入段全 nullptr ⇒
//     `filmSource(0)` 用 `kFsFilm` 自身的切片拼回原文（阶段 1 零回归硬门槛）。
//   · 引擎 1 = darktable sigmoid（阶段 2）：
//       exposure_domain = kLinear —— 照 PhotonCamera 的 `DarktableSigmoid` 声明
//         （`RawRenderingEngine.kt:62-67`，exposureCompensationDomain = Linear）。
//       working_space = "LinearSRGB" —— 阶段 2 **不换工作空间**。PC 该引擎声明的是
//         BT2020，但"公共工作空间切 Rec.2020"已拍板单独一期（方案 §10 第 3 条），
//         与插槽重构同期会毁掉零回归判据。
//       default_ev = 0.0f —— **刻意不照抄 PC 的 0.7**：判据 ② 要求"中灰 0EV 偏差
//         ≤ 1.3 LSB"⇒ 两个引擎必须在**同一 EV** 下比较，给 0.7 EV 补偿会让中灰直接
//         漂移 0.7 EV，等于自证失败。PC 的 0.7 是配合其 BT2020 域与渲染意图的取值
//         （其影调引擎普遍偏暗），不是 sigmoid 的固有要求。另外阶段 2 不改
//         `[0] exposure_ev` 的赋值逻辑（那是阶段 3「切换与预设」的范围），本字段
//         现阶段只是声明。
//       注入段：7 个 uniform 声明写在 `kFsSigmoidDef` 段首 ⇒ 前两项仍 nullptr。
// ⚠ 这里是 `const` 而非 `constexpr`：表项要**读**上面两个 extern 指针变量的值，而
//   "读外部变量的值"不是常量表达式（实测 clang 报 "read of non-constexpr variable"）。
//   两者本身仍是**常量初始化**（初始化器 = 字符串字面量地址，加载期完成，先于任何
//   动态初始化）⇒ 不存在静态初始化顺序问题。
const EngineEntry kEngines[kEngineCount] = {
    {0, "filmic", "LinearSRGB", ExposureDomain::kLinear, 0.0f,
     nullptr, nullptr, nullptr, nullptr},
    {1, "sigmoid", "LinearSRGB", ExposureDomain::kLinear, 0.0f,
     nullptr, nullptr, kFsSigmoidDef, kFsSigmoidMain},
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
