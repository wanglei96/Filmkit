#include "render/AeMetering.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

namespace filmkit {
namespace {

float evOf(float linear, float middle_grey) {
  return std::log2(std::max(linear, 1e-9f) / middle_grey);
}

bool near(float a, float b, float eps = 1e-4f) {
  return std::fabs(a - b) <= eps;
}

}  // namespace

AeDecision decideAe(const AeInputs& in) {
  AeDecision d;
  if (!in.valid || !(in.p_mid > 1e-9f) || !(in.p_hi995 > 1e-9f)) {
    d.why = "invalid";
    return d;
  }
  // ① 中灰锚定：最终渲染输入里 P50 必须落在 18.45%。`user_ev` 已经包含在
  //    film pass 的 exposure_ev 里，所以这里要扣掉它，只返回"还差多少"。
  d.ev_mid = evOf(kAeMiddleGrey, in.p_mid) - in.user_ev;
  // ② 高光约束：P99.5 提亮后 ≤ 白点 − 余量（允许 0.5% 像素裁切）。写成 EV 差，与 ① 同单位。
  d.ev_hl = (kAeRawWhiteEv - kAeHighlightHeadroomEv) - evOf(in.p_hi995, kAeMiddleGrey) -
            in.user_ev;
  // ③ 高光优先：宁可画面偏暗，也不接受 RAW 已饱和的输入顶到显示白点。
  d.ev = std::min(d.ev_mid, d.ev_hl);
  d.why = (d.ev_hl < d.ev_mid) ? "highlight" : "mid";
  // ④ 安全限幅：欠曝超过 4 EV 等于为 0.1% 像素牺牲全图（本项目 2026-10-03 教训）；
  //    提亮超过 2 EV 是在用 EV 拉噪声，应该交给多帧/降噪。
  const float clamped = std::clamp(d.ev, -kAeMaxUnderEv, kAeMaxOverEv);
  if (clamped != d.ev) d.why = (d.ev < clamped) ? "highlight_clamped" : "mid_clamped";
  d.ev = clamped;
  return d;
}

bool aeMeteringSelfTest(char* err, int err_cap) {
  const auto fail = [&](const char* what, float got, float want) {
    if (err != nullptr && err_cap > 0) {
      std::snprintf(err, static_cast<size_t>(err_cap), "%s: got %.4f want %.4f", what, got,
                    want);
    }
    return false;
  };
  // 1) 曝光正确的场景：P50 = 中灰，P99.9 恰在白点前 ⇒ 不介入（只剩余量 0.1EV 的欠曝）。
  {
    AeInputs in;
    in.valid = true;
    in.p_mid = kAeMiddleGrey;
    in.p_hi995 = 1.0f / std::exp2(kAeHighlightHeadroomEv);  // 2^(白点-余量) 对应线性
    const AeDecision d = decideAe(in);
    if (!near(d.ev, 0.0f, 2e-3f)) return fail("baseline", d.ev, 0.0f);
  }
  // 2) 过曝场景（真机 2026-10-20：P99.9 达 4.085EV）⇒ 高光判据按 P99.5 的同量级值计算，
  //    必须走欠曝分支；这里直接用 4.085 验证公式本身。
  {
    AeInputs in;
    in.valid = true;
    in.p_mid = 0.05f;  // 中灰偏暗（室内）
    in.p_hi995 = kAeMiddleGrey * std::exp2(4.085f);
    const AeDecision d = decideAe(in);
    if (d.why[0] != 'h') return fail("highlight path", 0.0f, 1.0f);
    if (!near(d.ev, (kAeRawWhiteEv - kAeHighlightHeadroomEv) - 4.085f, 2e-3f)) {
      return fail("highlight ev", d.ev, -1.747f);
    }
  }
  // 3) 暗场景：中灰提亮受上限约束（不允许把噪声拉爆）。
  {
    AeInputs in;
    in.valid = true;
    in.p_mid = 0.01f;
    in.p_hi995 = 0.05f;
    const AeDecision d = decideAe(in);
    if (d.ev > kAeMaxOverEv + 1e-5f) return fail("clamp over", d.ev, kAeMaxOverEv);
  }
  // 4) 无效统计：不介入（与历史行为一致）。
  {
    AeInputs in;
    const AeDecision d = decideAe(in);
    if (d.active() || d.ev != 0.0f) return fail("invalid", d.ev, 0.0f);
  }
  if (err != nullptr && err_cap > 0) err[0] = '\0';
  return true;
}

}  // namespace filmkit
