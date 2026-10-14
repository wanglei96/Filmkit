// 输出交付位深：**单一真源**（10bit HEIF 交付链的准备，卡 A1）。
//
// 为什么单独成头：位深此前是**编译期常量**，散落在成片 target 格式、readback 类型、
// dither 量化常量、Kotlin 位图 Config 各处（见 docs/reports/r-next-10bit-heif-REPO-AUDIT.md
// §4.2 的 8bit 假设清单）。提位深时必须一次全改，漏一处 = 静默降位。本头是唯一改动点，
// 由 tools/check-bitdepth-single-source.py 强制检查（防止再次散落）。
//
// 数值纪律（卡 A1 铁律 = **行为零变化**）：
//   · 8bit 下 kOutputMax = 255、GL 交付对 =（GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE），
//     与收口前的裸字面量**逐位同值**（同 enum 值 / 同一个 2 的幂次减一）⇒ 画面逐位不变。
//
// ⚠ GLSL 侧的特殊约束（不许"顺手改成拼接引用"）：
//   GLSL 无法引用 C++ 常量，而 shader 文本是被**冻结的契约**——
//     · kFsFilm 整体 crc32 0x4fa1ebd3（tools/engine-shader-offline.py FILM_SRC_BASELINE）；
//     · tools/validate-shaders.sh 按 `R"(...)"` 正则逐字提取全部 shader 字面量；
//     · tools/check-halation.py / check-softglow.py / check-style-passthrough.py 直接
//       `gl.run(sh["kFsOut"], …)` 执行提取出的原文。
//   把 `R"(...)"` 拆成拼接会破坏上述**所有**提取器（正则 `(.*?)\)";` 会跨段吞掉 C++ 源码）。
//   ⇒ shader 内的 dither 除数保持裸字面量，由守卫脚本锚定 == kOutputMaxGlsl：
//     改位深却忘了改 shader ⇒ 守卫立刻红。
#pragma once

#include <GLES3/gl32.h>

namespace filmkit {

// ── 输出交付位深 ────────────────────────────────────────────────────────────
inline constexpr int kOutputBitDepth = 8;
inline constexpr int kOutputMax = (1 << kOutputBitDepth) - 1;              // 255
// 量化倒数：shader / CPU 侧把 [0,1] 浮点映射到输出整数域的乘性常量。
// 8bit 下 = 1/255；提位深时随 kOutputBitDepth 自动重标（dither 幅度同源）。
inline constexpr float kOutputInvMax = 1.0f / static_cast<float>(kOutputMax);

// GL 交付对：成片 target / 预览降档 target 的内部格式 + readback 的 (format, type)。
// ⚠ 三者必须同源一起改：internal = GL_RGBA8 却配 type = GL_HALF_FLOAT 会被驱动拒绝
//   或静默截断（ES 3.0 只保证 GL_RGBA + GL_UNSIGNED_BYTE 这一对可读回）。
inline constexpr GLenum kOutputGlInternalFormat = GL_RGBA8;
inline constexpr GLenum kOutputGlReadFormat = GL_RGBA;
inline constexpr GLenum kOutputGlReadType = GL_UNSIGNED_BYTE;

// GLSL 侧 dither 除数的**十进制文本**（理由见文件头：shader 文本冻结，不能拼接引用）。
// 守卫脚本据此逐字比对 shader 里 dither 行的除数。
inline constexpr const char* kOutputMaxGlsl = "255.0";

// ── 第二条交付档：10bit（HEIF，卡 H2 / 期 A）─────────────────────────────────
// 上面那组（8bit）仍是 **JPEG 默认链**的真源，语义与数值一律不动（零回归守卫）；
// 下面这组只在用户把「照片格式」选成 HEIF 时启用 —— 成片 FBO / readback / dither 除数
// **三处一起切**（[outputInternalOf] / [outputReadTypeOf] / [outputMaxOf]）。
//
// 为什么是 GL_RGB10_A2（而不是读回 RGBA16F）：交付的是**显示域 10bit 定点**，
// ES 3.0 只保证定点读回（GL_UNSIGNED_BYTE / GL_UNSIGNED_INT_2_10_10_10_REV），
// 16F 读回在真机上没有保证（卡 H2 任务 1 指定口径）。
// ⚠ alpha 位只有 2 bit ⇒ **不得**把需要 alpha 的东西塞进本档；HEIF/HEVC item 是 3 通道
//   （`pixi` = 3×10bit），alpha 不参与交付。
// ⚠ 位序（易错）：GL_UNSIGNED_INT_2_10_10_10_REV = **LSB-first** ——
//   bit0..9 = R、bit10..19 = G、bit20..29 = B、bit30..31 = A（与 P010 的 MSB-aligned
//   16bit word 不同，Kotlin 侧解包见 Heif10Pipeline.unpackRgb10 的注释）。
inline constexpr int kOutput10BitDepth = 10;
inline constexpr int kOutput10Max = (1 << kOutput10BitDepth) - 1;             // 1023
inline constexpr float kOutput10InvMax = 1.0f / static_cast<float>(kOutput10Max);
inline constexpr GLenum kOutput10GlInternalFormat = GL_RGB10_A2;
inline constexpr GLenum kOutput10GlReadFormat = GL_RGBA;
inline constexpr GLenum kOutput10GlReadType = GL_UNSIGNED_INT_2_10_10_10_REV;
inline constexpr const char* kOutput10MaxGlsl = "1023.0";

// ── 交付档选择：**唯一分支点**（成片 target / readback / dither 上限都走这里）────
// 调用方只传一个 bool（ten_bit），三个 GL/数值口径由本头一起给出 ⇒ 不会出现
// "target 切了、readback 忘了切"这类静默降位（正是卡 A1 收口要防的那类 bug）。
inline constexpr float outputMaxOf(bool ten_bit) {
  return ten_bit ? static_cast<float>(kOutput10Max) : static_cast<float>(kOutputMax);
}
inline constexpr GLenum outputInternalOf(bool ten_bit) {
  return ten_bit ? kOutput10GlInternalFormat : kOutputGlInternalFormat;
}
inline constexpr GLenum outputReadFormatOf(bool ten_bit) {
  return ten_bit ? kOutput10GlReadFormat : kOutputGlReadFormat;
}
inline constexpr GLenum outputReadTypeOf(bool ten_bit) {
  return ten_bit ? kOutput10GlReadType : kOutputGlReadType;
}

// 纹理上传类型：internal → 合法 GL type（makeTex 用）。ES 3.0 规则：type 必须与
// 内部格式匹配，否则驱动**静默失败**（X300 实测 RGBA8 + HALF_FLOAT ⇒ 捕获 FBO 永不
// 完整 ⇒ 照片全黑）。非交付档（RGBA16F 等中间纹理）恒为 HALF_FLOAT。
inline constexpr GLenum outputUploadTypeOf(GLenum internal) {
  return internal == kOutputGlInternalFormat
             ? kOutputGlReadType
             : (internal == kOutput10GlInternalFormat ? kOutput10GlReadType
                                                      : GL_HALF_FLOAT);
}

// 是否属于"交付档"内部格式（上传类型 / 逐 pass 诊断读回口径的共同判据）
inline constexpr bool isOutputInternal(GLenum internal) {
  return internal == kOutputGlInternalFormat ||
         internal == kOutput10GlInternalFormat;
}

// dither 除数 uniform 名：8bit/10bit **共用同一行 shader**，除数由 CPU 按交付档下发
// （[outputMaxOf]）—— 这是"dither 幅度随输出位深重标"的落点（设计 §2.5 / §4.4）。
// ⚠ shader 侧**兜底** = kOutputMaxGlsl：uniform 未下发时 GLSL 读 0 ⇒ shader 回退 8bit
//   数值 ⇒ **既有调用方（离线校验脚本 check-halation/check-softglow 等不传该 uniform）
//   输出逐位不变**（零回归的关键）。守卫脚本锚定"兜底 == kOutputMaxGlsl"。
inline constexpr const char* kOutputMaxUniform = "uOutMax";

}  // namespace filmkit
