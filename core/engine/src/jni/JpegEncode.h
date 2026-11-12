#pragma once

// libjpeg-turbo 的成片 JPEG 编码（4:4:4），供 JpegEncoder.cpp 与 UltraHdrEncoder.cpp 共用。
//
// 为什么换掉 stb（2026-11-12，性能专项）：
//   stb_image_write 是单线程浮点 DCT，12.5MP / q100 在 X300 上约 300ms；libjpeg-turbo 的
//   整数 DCT + NEON 是同一任务的 3~4×。而且 **libjpeg-turbo 已经在本项目的构建里**
//   （libultrahdr 依赖，编成 `jpeg-static`），不新增任何依赖。
//   质量代价：jpeg444-REPORT 实测 4:4:4 q100 下色度误差 p99 stb 1.36 / libjpeg 1.58（/255），
//   两者都远低于 4:2:0 的 9.77 —— 用户已拍板接受该取舍。
//
// 失败返回 false（调用方回退 stb 或 Bitmap.compress；丢成片比退一档编码严重得多）。

#include <cstdint>
#include <vector>

namespace filmkit {

// channels: 3 = RGB 紧密排列；1 = 灰度；4 = RGBA（走 JCS_EXT_RGBA，零拷贝）。
// quality 1..100，恒定输出 4:4:4（不降采样色度）。
bool encodeJpegTurbo444(const uint8_t* data, int w, int h, int channels, int quality,
                        std::vector<uint8_t>* out);

// 编译期是否带 libjpeg（FILMKIT_HDR 关闭时没有 jpeg-static）。
bool jpegTurboAvailable();

}  // namespace filmkit
