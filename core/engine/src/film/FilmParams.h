#pragma once

#include <cstring>

namespace filmkit {

// 胶片模拟参数（M1 简化模型，docs/开发计划.md §5 的子集）。
// 通过 JNI double 数组传输，顺序由 kIdx* 常量与 Kotlin 侧约定保持一致。
struct FilmParams {
  // v0.6.34 起实际为 37 项（[35] auto_tone + [36] local_tone）。
  // 此常量当前无引用者，但正因它停在 36，记忆库 facts 曾按它记成 "kCount = 36"，
  // 与 Kotlin 侧 `CameraPresets.neutral()` 的 `DoubleArray(42)` 保持一致（v0.10.15 起）；
  // 数组越界会直接崩，改这里必须同步改那边。
  static constexpr int kCount = 45;  // v0.10.111：+[43] white_floor（v0.10.27 +[42] white_hl、v0.10.15 +[41] hr_wavelet）

  float exposure_ev = 0.0f;   // 0
  float wb_r = 1.0f;          // 1
  float wb_g = 1.0f;          // 2
  float wb_b = 1.0f;          // 3
  float contrast = 1.0f;      // 4  log 域对比系数
  float shoulder[3] = {0.0f, 0.0f, 0.0f};  // 5..7 高光软肩强度（per-channel）
  float toe[3] = {0.0f, 0.0f, 0.0f};       // 8..10 阴影 toe 强度
  float cross[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // 11..19 log 域染料串扰
  float sat_shadow = 1.0f;    // 20
  float sat_mid = 1.0f;       // 21
  float sat_high = 1.0f;      // 22
  float split_warm = 0.0f;    // 23 阴影偏暖
  float split_cool = 0.0f;    // 24 高光偏冷
  float monochrome = 0.0f;    // 25
  float grain_amount = 0.0f;  // 26
  float grain_size = 1.0f;    // 27
  // M3 色彩内核：基准影调强度（0 = 恒等/旧行为，1 = 完整基准 S 曲线）。
  // 默认 1.0 —— 中灰锚定、只补黑位与高光 rolloff，不是风格化。
  float tone_strength = 1.0f;  // 28
  // M3 高光重建强度（0 = 关）：CCM 前用未饱和通道外推饱和通道，默认 1.0。
  // ⚠️ v0.10.15 审计定性：这是对 darktable 原生高光处理的**自研简化替代**
  // （darktable 的 RAW 域对应 = highlights 模块；filmic 内的原生小波重建已由
  // [41] hr_wavelet 完整实现）。保留待 RAW 域 highlights 模块对齐后移除。
  float hl_recon = 1.0f;  // 29
  // 过曝提示阈值（显示域亮度，0 = 关）：仅预览 passOutput 使用，成片一律 0。
  float zebra_thr = 0.95f;  // 30
  // ── M3 P1 色彩外观层（治"寡淡"）──────────────────────────────────
  // 诊断：中性色准（R/G 0.987）但饱和 -21%、通道分离 -29%、近中性像素多 10pp
  // —— 颜色全挤在中性附近。外观层 = 亮度保持的饱和增益 + 色度 S 曲线。
  float look_strength = 1.0f;  // 31 强度（0 = 恒等，A/B 通路）
  float look_sat = 1.0f;       // 32 饱和增益（luma 不动，色差放大）；1.0 = 中性占位
  float look_chroma = 1.0f;    // 33 色度 S 曲线端点增益；1.0 = 中性占位
  // ── M4 3D LUT（display 域，插在输出 OETF 之后）─────────────────────
  // LUT 资产本身走独立 JNI（nativeEngineSetLut，一次性上传 3D 纹理）；
  // 这里只带**混合强度**：0 = 旁路（A/B 通路），1 = 全量。未选 LUT 时无意义。
  float lut_strength = 1.0f;   // 34
  // ── M4-6 自适应影调（默认开、可关）──────────────────────────────
  // 真机实测：M 档实拍场景的**中间调**普遍偏低（与 PhotonCamera 同机位对照，
  // 中位 79/255 vs 131，而高光只差 1.1×）—— 固定曝光补偿救不了。
  // ── v0.7.3 起语义变更，v0.7.6 默认改为 0 ─────────────────────────────
  // ⚠️ v0.10.16 语义升级 = **黑白点成对自动**（darktable autotune 原生语义：
  // black = P0.5 → clamp(log2(p/grey), −16, −1)；white = P99.5 → clamp(.., 1, 16)；
  // security 0）。⚠️ v0.10.19 **默认关**（三版真机证伪：0.10.16 中间调低/水壶过曝/
  // 天空过饱和、0.10.17 全面糟糕化、0.10.18 power 5.17 无改善——自动分位定端点把
  // power 推离固定曲线的 3.62 设计值；PC 的 SDR 链实锤无场景归一，固定 filmic 默认
  // 参数 + DngBaselineExposure + AE 曝光决策）。HR 裁剪阈值已与曲线解耦（恒 1.0），
  // 本开关降级为可选实验工具；亮度校准 = [0] base_ev。
  float auto_tone = 0.0f;      // 35 0 = 关（黑白点用 [37]/[38]，默认）；1 = 成对自动
  // ── M4-7 局部色调映射（默认开、可关）────────────────────────────
  // 全局曲线在高 DR 场景（边缘高光 + 中央暗部）里两难：保高光则中央暗、
  // 提中央则高光爆（离线实测中位锚定会让 6.6%–13% 像素过曝）。
  // 本项 = 暗部线性提亮比例（局部亮度越低越满额，到 knee 以上归零）：
  // 0 = 关（A/B 通路），>0 = 强度。高光区强度为 0，故与自适应影调互不冲突。
  float local_tone = 0.25f;    // 36 0 = 关；>0 = 暗部线性提亮比例（标定旋钮）
  // ── v0.7.6：filmic 的黑白点交给用户配置（PC 的做法）─────────────────
  // 单位 = **相对中灰 18.45% 的 EV**，物理量，与机型/曲线无关：
  //   black_ev = "多暗算黑"（黑点是曲线的 toe 端点，不是硬截断）
  //   white_ev = "多亮算白"（显示上限锚在哪）
  // 默认 −7.65 / +4.56 = darktable 默认值，也是 PhotonCamera 的固化值（两边一致）。
  // 为什么不自动：v0.7.3–v0.7.5 实测自动黑点（P0.5 分位）本质是"把每个场景最暗的
  // 0.5% 钉在显示黑"—— 与已被否掉的 kTarget 中位锚定同型（抹掉"这个场景暗部有多深"），
  // 实测比 PC 的固定值高 2EV ⇒ 暗部压得更死、画面更暗。
  // auto_tone 开时黑点改由场景分位自动，白点仍用这里配置的值。
  float filmic_black_ev = -7.65f;  // 37
  float filmic_white_ev = 4.56f;   // 38
  // ── v0.9.6：轻度锐化（luma USM，OETF 前线性显示域）─────────────────────
  // 管线定稿顺序：滚降后、OETF 前（kFsOut 内）。为什么不在 OETF 后（PC 的
  // sharpen 在 sRGB 域）：sRGB 斜率在暗部 >1，会放大暗部噪声。乘法式相对增益
  // 保色度坐标。0 = 关（A/B 通路）；默认 0 = 缺参/旧长度时保持旧行为，
  // Kotlin 侧 CameraPresets[39] 给标定值（0.5）。
  float sharpen = 0.0f;        // 39
  // ── v0.10.13/14：filmic 之后的 gamut 收尾（标准算法补全 + 实测修正）──────
  // v0.10.13 实装 darktable v7 完整五步；真机 A/B 泛白（暗部色度 -17~33%，链级
  // 模拟定位 = 跨域的 Ych 色度 min + desaturate 在无场景归一链上成全局去饱和）。
  // v0.10.14 只保留保险丝职责：Y 显示域 clamp / Yrg 锥空间检查 / 恒 Y 恒色相
  // 解析 clip chroma（sRGB 色域映射）——只作用于病态与域外像素，域内零改动。
  // 默认 1.0（开），0 = A/B 通路。
  float gamut = 1.0f;          // 40
  // ── v0.10.15：darktable filmic 高光重建（à trous 小波）───────────────────
  // darktable filmicrgb.c 的原生功能（enable_highlight_reconstruction +
  // mask/inpaint/wavelets 全链，参数 = darktable 默认）；PC 完整移植、我们此前
  // 用自研"中性外推"（[29] hl_recon，RAW 域）替代并在审核中定为简化。
  // 本层作用于线性 RGB 域（CCM 后、filmic 前），修复部分通道裁剪高光
  // （灯管/过曝边缘的品红残留即此类）。默认开；0 = A/B 通路。
  float hr_wavelet = 1.0f;     // 41
  // ── v0.10.27：白点高光感知（auto_tone 开时生效）──────────────────────────
  // 真机定案："高光太白压不住"的根因 = 显示域统计 clamp 使 P99 对线性 ≥1.0 的
  // 强光源失明 → whiteEv 恒钉 2.438 下限 → dr 被压到 ~8EV → 肩部只覆盖 2.5EV，
  // 高光平顶白墙（151748 高光块 7.99%）。修正 = 对数域统计（256 桶）+ P99.9 驱动
  // 白点上探（≤+1.5EV、物理钳制不变）+ darktable contrast 参数按 power 前后比
  // 补偿中间调回退。1 = 开（默认）；0 = A/B 通路（v0.10.18 行为）。
  float white_hl = 1.0f;       // 42
  // 43：filmic 白点下限的场景自适应边距（EV）。0 = 旧行为（下限恒为 RAW 饱和数据
  // 白点 2.438）；>0 = 下限改为 min(2.438, w99 + 本值) —— 治"场景高光远低于饱和点、
  // 白点却被物理下限钉高 1.5EV ⇒ 高光全落在肩部以下不被压缩"（2026-10-03 真机：
  // 红杯釉面密集高光点变白斑，w99 仅 1.03EV 而白点 2.53EV）。
  float white_floor = 0.0f;    // 43
  // 44：白点上探幅度上限（EV）。旧硬编码 +1.5 —— 真机实测（2026-10-03）该闸**每次
  // 都满额**：红杯场景 w99 仅 1.03EV，白点被 wmax（单点最大值）推到 w99+1.5 = 2.53EV，
  // 比场景高光高 1.5EV ⇒ 1% 以上的高光全落在肩部以下不被压缩 ⇒ 密集高光点变白斑。
  // 降到 0.5 后白点 = w99+0.5，既保留"P99 看不见的近饱和高光"上探能力（白壶案），
  // 又不再把整段高光推白。
  float white_probe = 0.5f;    // 44

  void fromDoubles(const double* v, int n) {
    // 防呆：按实际长度读取（26 = M1 核心段 0..25 的下限）。旧长度不丢核心参数，
    // 缺失的新参数保持默认 —— 契约长度不一致时"整包静默全丢"比越界更危险。
    if (!v || n < 26) return;
    exposure_ev = static_cast<float>(v[0]);
    wb_r = static_cast<float>(v[1]);
    wb_g = static_cast<float>(v[2]);
    wb_b = static_cast<float>(v[3]);
    // 防呆：全零白平衡 = 全黑输出（X300 事故），回退中性
    if (wb_r == 0.0f && wb_g == 0.0f && wb_b == 0.0f) {
      wb_r = wb_g = wb_b = 1.0f;
    }
    contrast = static_cast<float>(v[4]);
    if (contrast <= 0.0f) contrast = 1.0f;  // 0 = 全画面同一灰度，同族事故
    for (int i = 0; i < 3; ++i) {
      shoulder[i] = static_cast<float>(v[5 + i]);
      toe[i] = static_cast<float>(v[8 + i]);
    }
    for (int i = 0; i < 9; ++i) cross[i] = static_cast<float>(v[11 + i]);
    // 防呆：全零串扰矩阵 = log 域信号清零，回退单位阵
    bool cross_zero = true;
    for (int i = 0; i < 9; ++i) {
      if (cross[i] != 0.0f) {
        cross_zero = false;
        break;
      }
    }
    if (cross_zero) {
      const float ident[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
      memcpy(cross, ident, sizeof(ident));
    }
    sat_shadow = static_cast<float>(v[20]);
    sat_mid = static_cast<float>(v[21]);
    sat_high = static_cast<float>(v[22]);
    split_warm = static_cast<float>(v[23]);
    split_cool = static_cast<float>(v[24]);
    monochrome = static_cast<float>(v[25]);
    grain_amount = static_cast<float>(v[26]);
    if (n > 27) grain_size = static_cast<float>(v[27]);
    if (n > 28) tone_strength = static_cast<float>(v[28]);
    if (n > 29) hl_recon = static_cast<float>(v[29]);
    if (n > 30) zebra_thr = static_cast<float>(v[30]);
    if (n > 31) look_strength = static_cast<float>(v[31]);
    if (n > 32) look_sat = static_cast<float>(v[32]);
    if (n > 33) look_chroma = static_cast<float>(v[33]);
    if (n > 34) lut_strength = static_cast<float>(v[34]);
    if (n > 35) auto_tone = static_cast<float>(v[35]);
    if (n > 36) local_tone = static_cast<float>(v[36]);
    // v0.7.6：filmic 黑白点（用户可配，默认 −7.65/+4.56）；旧长度时保持默认值
    if (n > 37) filmic_black_ev = static_cast<float>(v[37]);
    if (n > 38) filmic_white_ev = static_cast<float>(v[38]);
    if (n > 39) sharpen = static_cast<float>(v[39]);
    if (n > 40) gamut = static_cast<float>(v[40]);
    if (n > 41) hr_wavelet = static_cast<float>(v[41]);
    if (n > 42) white_hl = static_cast<float>(v[42]);
    if (n > 43) white_floor = static_cast<float>(v[43]);
    if (n > 44) white_probe = static_cast<float>(v[44]);
  }
};

}  // namespace filmkit
