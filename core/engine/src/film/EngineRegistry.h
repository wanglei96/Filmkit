#pragma once

namespace filmkit {

// 引擎注册表（docs/engine-slots-design.md §6）—— **引擎的唯一维护点**。
// 加/删引擎只改：本表 + 资产 + UI 列表；**不改 `FilmParams` 槽位布局、不改 Room DB
// schema**（PC 的 Fuji 下线 = 33 文件 + Room DB v49→v50 的教训：引擎元数据一旦散进
// 参数契约与 DB 列，下线一个引擎就变成一次跨层迁移）。
//
// 阶段 0：只建表与查询，**不接线** —— 渲染路径不得调用本表，shader 注入、资产加载与
// 参数下发全部留到阶段 1。因此本文件刻意不依赖任何 GL/EGL 头，可独立编译与单测。

// 曝光补偿的作用域（对齐 PC 的 `exposureCompensationDomain`）：
//   kLinear = EV 在**线性**域施加（亮度按倍数缩放）；
//   kCurve  = EV 在**曲线/影调**域施加（走 filmic 的 log 域斜率）。
// 切换引擎时据此决定 `[0] exposure_ev` 落到哪一侧，避免中灰漂移。
enum class ExposureDomain { kLinear, kCurve };

// 阶段 0 只有内置引擎 0（filmic）；每加一个引擎 +1。
constexpr int kEngineCount = 1;

struct EngineEntry {
  int slot;                     // = `FilmParams[98] engine_slot` 的取值
  const char* name;             // 持久化名（Kotlin 侧 `EngineRegistry` 必须同名）
  const char* working_space;    // 引擎工作色彩空间（阶段 1 = 单位阵 ⇒ D4 线性 sRGB）
  ExposureDomain exposure_domain;
  float default_ev;             // 选中该引擎时 `[0] exposure_ev` 的默认值
  // 阶段 1 在此扩展：`uniforms_src` / `profile_src` / `functions_src`（shader 注入）、
  // 资产加载与参数下发回调。阶段 0 **故意不引入** —— 未接线的字段就是死代码。
};

// 静态表，长度 kEngineCount，按 slot 升序。
const EngineEntry* engines();

// 未命中返回 nullptr；**不回落**到引擎 0 —— 回落是调用方策略（方案 §4 契约 5），
// 注册表只回答"这个 slot 存不存在"。
const EngineEntry* findEngine(int slot);

bool isValidEngineSlot(int slot);

// 日志用（"Linear"/"Curve"）。
const char* exposureDomainName(ExposureDomain d);

}  // namespace filmkit
