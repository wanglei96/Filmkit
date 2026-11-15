# 0.14.6：JPEG无法生成及意外DNG修复

## 回归与根因

0.14.5 用户两次快门仅留下 `_f0.dng`，没有 JPEG。日志中 highlight admission/merge 成功，之后 `render.burst_done ok=false bytes=0`；不是相册刷新或 JPEG 编码问题。

新增真实 FrameRenderer 原生 EGL 测试在用户同一手机精确重现：合并后、去马赛克到RGB resolve入口有 `GL_INVALID_ENUM(1280)`。

进一步分段确认错误出现在 GPU timer `endMs`：`GpuTimer.cpp` 曾手写 `GL_GPU_DISJOINT_EXT=0x8FBD`，正确 Android/Khronos 值为 **0x8FBB**。该无效枚举污染 GL error 状态；0.14.5 新的硬失败检查把旧计时器错误判为图像处理失败。因此此前的独立 shader 验证不足以证明整个 C++ host 出片成功。

真机 A/B：

- 查询错误 0x8FBD：稳定 GL1280。
- 官方 GL_GPU_DISJOINT_EXT=0x8FBB：GL0，disjoint=0。
- 修复后真实 `GpuTimer.endMs`：GL0。

修复：直接 include 官方 GLES2/gl2ext.h，不再定义手写枚举；EXT query API 成套动态加载，仅支持且入口齐备时启用。保留图像处理失败检测，不通过吞掉GL错误伪装成功。

## DNG开关问题

前期排查留下的外部 `files/burst_dump` 哨兵仍存在，CameraController 调试分支会不经用户DNG开关把原帧保存到相册。该哨兵已在连接手机上删除；edge_dump.enable亦清理，未删除用户照片或数据。

代码上删除调试 RAW→MediaStore DNG 的旁路。四帧诊断仅写 App-specific `burst/*.raw`+metadata，公共相册 `saveDng` 仅由用户 `dngOn` 授权。补静态防回归 `tools/check-capture-diagnostics.py`。

## 实际验证，不止单shader

`tools/check-renderer-device.cpp` 动态调用真实 arm64 libfilmengine.so 的 `FrameRenderer::init/submitRawBurst`，真实 Mali/EGL、完整相机后段和 libjpeg-turbo444 编码；不调用摄像头、不安装APK、不修改用户设置。

| 合成fixture | RGBA字节 | JPEG字节 | 引擎处理时间 |
|---|---:|---:|---:|
| HDR 256² | 262144 | 31742 | 约908ms |
| plain 256² | 262144 | 32063 | 约555ms |
| HDR 512² | 1048576 | 112344 | 约1104ms |
| HDR 2048² | 16777216 | 1660859 | 约4593ms |
| HDR快速bilinear请求（新链明确路由RCD） | 262144 | 31742 | 通过 |
| HDR 512²、rotation=1 | 1048576 | 112233 | 通过 |

修复后所有上述测试返回true、正确像素数、非黑常量图、有效JPEG SOI/EOI、无stage GL错误。包括完整新高光准入/合并/RGB resolve/covariance/postprocessing/readback/JPEG，而非只测单个fragment程序。

日志和JPEG：`test_temp/no-jpeg-0145/renderer-*.log`、`renderer-*.jpg`。

工具装载Android App so（静态libc++）与独立CLI libc++的全局卸载出现Scudo冲突；已明确使用run()作用域完成FrameRenderer/EGL局部析构后 `_Exit(status)`，不让只属于测试进程全局卸载的冲突冒充图像管线错误。此处理只在测试工具，不修改App生命周期。

## 交付

- APK：filmkit-0.14.6-CAPTURE-FIX.apk。
- versionCode=803，versionName=0.14.6。
- Release双架构构建与所有shader静态校验通过。
- 签名验证、aapt2版本、DEX版本、包内arm64 HDR/highlight关键符号通过。
- 未替用户安装；用户选择自行安装验证。
- **完整App的快门→MediaStore保存仍待用户室内验证**，不要把native harness称为已通过HAL/Compose/相册端到端验收。日光高光外观也仍未完成验收。
