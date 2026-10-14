#pragma once

namespace filmkit {

// 引擎注册表（docs/engine-slots-design.md §6）—— **引擎的唯一维护点**。
// 加/删引擎只改：本表 + 资产 + UI 列表；**不改 `FilmParams` 槽位布局、不改 Room DB
// schema**（PC 的 Fuji 下线 = 33 文件 + Room DB v49→v50 的教训：引擎元数据一旦散进
// 参数契约与 DB 列，下线一个引擎就变成一次跨层迁移）。
//
// 阶段 0：只建表与查询，**不接线** —— 渲染路径不得调用本表，shader 注入、资产加载与
// 参数下发全部留到阶段 1。因此本文件刻意不依赖任何 GL/EGL 头，可独立编译与单测。
//
// 阶段 2（2026-10-13）：本表**已接线** —— `FrameRenderer::filmSource()` 按 slot 查表，
// 表项的 4 个注入段指针决定该引擎的 GLSL 片段（见 `EngineEntry` 字段注释）。
// ⚠ 由此产生的**链接期**依赖：注入段指针指向 `FrameRenderer.cpp` 里的 GLSL 字面量
//   （`kFsSigmoidDef` / `kFsSigmoidMain`）⇒ `EngineRegistry.cpp` 不再是"可单独链接的
//   独立单元"（**头文件级**仍无 GL/EGL 依赖，语法与类型检查不受影响）。
//   取舍理由：注册表是引擎的唯一维护点，若注入段由调用方旁路提供，"加引擎 = 加一行"
//   就退化成"改注册表 + 改 filmSource 两处"。代价是单测需连同 render 目标一起链接。

// 曝光补偿的作用域（对齐 PC 的 `exposureCompensationDomain`）：
//   kLinear = EV 在**线性**域施加（亮度按倍数缩放）；
//   kCurve  = EV 在**曲线/影调**域施加（走 filmic 的 log 域斜率）。
// 切换引擎时据此决定 `[0] exposure_ev` 落到哪一侧，避免中灰漂移。
enum class ExposureDomain { kLinear, kCurve };

// 阶段 0 只有内置引擎 0（filmic）；阶段 2 起 = 2（filmic / sigmoid）；每加一个引擎 +1。
constexpr int kEngineCount = 2;

struct EngineEntry {
  int slot;                     // = `FilmParams[98] engine_slot` 的取值
  const char* name;             // 持久化名（Kotlin 侧 `EngineRegistry` 必须同名）
  const char* working_space;    // 引擎工作色彩空间（阶段 1 = 单位阵 ⇒ D4 线性 sRGB）
  ExposureDomain exposure_domain;
  float default_ev;             // 选中该引擎时 `[0] exposure_ev` 的默认值
  // ── 阶段 2：GLSL 注入段（`docs/engine-slots-design.md` §3.1）──────────────────
  // 组装顺序（`FrameRenderer::filmSource`）= head + uniforms_src + profile_src +
  //   functions_src + mid + main_src + tail。
  // **nullptr 语义 = "用 `kFsFilm` 的对应切片"**：引擎 0 的四项全 nullptr ⇒ 拼接
  // 结果与 `kFsFilm` 原文**逐字节相同**（阶段 1 的零回归硬门槛，不许破）。
  // 引擎 1（sigmoid）只用后两项：7 个 uniform 声明直接写在 `functions_src` 段首
  // （GLSL 全局作用域合法）⇒ `uniforms_src` / `profile_src` 仍为 nullptr。
  // ⚠ 指针指向的**必须是 null-terminated 字面量**（`filmSource` 内部转
  //   `std::string_view` 时按 `strlen` 取长）；而 `kFsFilm` 的切片是 `string_view`
  //   （**非** null-terminated）⇒ 两者在 `filmSource` 里统一成 `string_view` 组装。
  const char* uniforms_src;
  const char* profile_src;
  const char* functions_src;
  const char* main_src;
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
