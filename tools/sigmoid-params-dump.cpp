// 引擎 1（sigmoid）CPU 侧参数求解的**独立** dump 程序 —— 对拍用。
//
// 只 include 契约头 + 链接 `SigmoidParams.cpp`，**不碰 GL/EGL/Android**：
//   g++ -std=c++20 -O2 -o /tmp/sig-dump tools/sigmoid-params-dump.cpp \
//       core/engine/src/film/SigmoidParams.cpp
//   /tmp/sig-dump > /tmp/sig-cpp.csv
//   python3 tools/sigmoid-params-ref.py --dump > /tmp/sig-py.csv
//
// 输出格式与 `tools/sigmoid-params-ref.py --dump` **逐字同格式**（CSV，`%.9g`），
// 便于 `diff` 或逐项算相对误差。

#include <cstdio>

#include "../core/engine/src/film/SigmoidParams.h"

namespace {

// 与 Python 侧 `FIELDS` 同序
void printRow(float contrast, float skew, float white, float black, float hue) {
  const filmkit::SigmoidParams p =
      filmkit::computeSigmoidParams(contrast, skew, white, black, hue);
  std::printf("%.9g,%.9g,%.9g,%.9g,%.9g,", contrast, skew, white, black, hue);
  std::printf("%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g\n",
              p.white_target, p.black_target, p.paper_exposure, p.film_fog,
              p.film_power, p.paper_power, p.hue_preservation);
}

}  // namespace

int main() {
  std::printf("contrast,skew,white,black,hue,"
              "white_target,black_target,paper_exposure,film_fog,"
              "film_power,paper_power,hue_preservation\n");

  // 任务卡要求的 7 组：(contrast, skew)；white=100 / black=0.0152 / hue=100
  const float kWhite = 100.0f, kBlack = 0.0152f, kHue = 100.0f;
  printRow(1.2f, 0.0f, kWhite, kBlack, kHue);
  printRow(1.5f, 0.0f, kWhite, kBlack, kHue);
  printRow(1.0f, 0.0f, kWhite, kBlack, kHue);
  printRow(2.2f, 0.0f, kWhite, kBlack, kHue);
  printRow(1.5f, -0.3f, kWhite, kBlack, kHue);
  printRow(1.5f, 0.65f, kWhite, kBlack, kHue);
  printRow(1.22f, 0.65f, kWhite, kBlack, kHue);   // darktable "neutral gray" 官方预设

  // 边界与变体（非判据，只用于暴露钳位/异常行为，防"只在默认值上正确"）
  printRow(1.5f, 0.0f, 99.4f, kBlack, kHue);      // 判据①严格对齐白纸 204.0 LSB 用的档
  printRow(1.2f, 0.0f, 99.4f, kBlack, kHue);
  printRow(1.5f, 0.0f, kWhite, 0.0f, kHue);       // black_target = 0 ⇒ film_fog 应为 0
  printRow(1.5f, 1.0f, kWhite, kBlack, kHue);     // skew 上界
  printRow(1.5f, -1.0f, kWhite, kBlack, kHue);    // skew 下界
  printRow(0.1f, 0.0f, kWhite, kBlack, kHue);     // contrast 下界
  printRow(10.0f, 0.0f, kWhite, kBlack, kHue);    // contrast 上界
  printRow(1.5f, 0.0f, kWhite, kBlack, 0.0f);     // hue 0%
  printRow(1.5f, 0.0f, kWhite, kBlack, 150.0f);   // hue 越界 ⇒ 应被 clamp 到 1.0
  printRow(1.5f, 0.0f, 20.0f, kBlack, kHue);      // white 下界
  printRow(1.5f, 0.0f, 1600.0f, kBlack, kHue);    // white 上界
  return 0;
}
