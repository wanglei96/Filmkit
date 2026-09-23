#pragma once

#include <cstring>

namespace filmkit {

// 胶片模拟参数（M1 简化模型，docs/开发计划.md §5 的子集）。
// 通过 JNI double 数组传输，顺序由 kIdx* 常量与 Kotlin 侧约定保持一致。
struct FilmParams {
  // v0.6.34 起实际为 37 项（[35] auto_tone + [36] local_tone）。
  // 此常量当前无引用者，但正因它停在 36，记忆库 facts 曾按它记成 "kCount = 36"，
  // 与 Kotlin 侧 `DoubleArray(37)` 不符 —— 留着会继续误导，按实际项数维护。
  static constexpr int kCount = 37;

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
  // 开启后引擎按场景**中位亮度**自动算线性增益（归一到 baseTone 的中间调不动点
  // 0.2184，限幅 [1,4]×、只提亮不压暗）；预览 / YUV 静拍 / RAW 静拍三条路径
  // 共用 passFilm 内的同一处统计。
  float auto_tone = 1.0f;      // 35 0 = 关（退回固定 ev 0.35），1 = 开
  // ── M4-7 局部色调映射（默认开、可关）────────────────────────────
  // 全局曲线在高 DR 场景（边缘高光 + 中央暗部）里两难：保高光则中央暗、
  // 提中央则高光爆（离线实测中位锚定会让 6.6%–13% 像素过曝）。
  // 本项 = 暗部线性提亮比例（局部亮度越低越满额，到 knee 以上归零）：
  // 0 = 关（A/B 通路），>0 = 强度。高光区强度为 0，故与自适应影调互不冲突。
  float local_tone = 0.25f;    // 36 0 = 关；>0 = 暗部线性提亮比例（标定旋钮）

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
  }
};

}  // namespace filmkit
