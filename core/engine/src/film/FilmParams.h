#pragma once

#include <cstring>

namespace filmkit {

// 胶片模拟参数（M1 简化模型，docs/开发计划.md §5 的子集）。
// 通过 JNI double 数组传输，顺序由 kIdx* 常量与 Kotlin 侧约定保持一致。
struct FilmParams {
  static constexpr int kCount = 31;

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
  }
};

}  // namespace filmkit
