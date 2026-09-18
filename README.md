# FilmKit —— 胶片相机

RAW / YUV 双输入的胶片成像模拟 App。开发计划见 [docs/开发计划.md](docs/开发计划.md)。

当前进度：**M0 骨架**（详见开发计划 §11）——工程搭建、GL 能力探测、日志框架 v1、
空管线 spike（GPU pass + 逐段计时）。

## 运行 M0 验收

1. Android Studio 打开本目录（或命令行 `./gradlew assembleDebug`）。
2. 真机/模拟器（Android 8.0+）安装运行 `app`。
3. 「能力 / Spike」页：自动执行 GL 能力探测 + 三段 GPU pass spike；
   「重新运行 spike」可反复计时（取中位数）。
4. 「日志」页：查看最近 500 条 JSONL 日志，可一键导出。
   完整日志文件在应用外部专属目录 `Android/data/com.filmkit.app/files/logs/`。

M0 验收标准：spike 三段 pass 全部成功（`readbackOk=true`），逐段 GPU 耗时
出现在结果页与日志（`spike.run` 事件）。

## 环境要求

- JDK 17、Android SDK（compileSdk 36）、NDK（AGP 默认或 29.x）、CMake 3.22.1
- 引擎 C++20，OpenGL ES 3.1（compute）；无 ES 3.1 的设备自动降级 fragment 路径

## 目录

```
app/          Compose UI、JNI 调用侧
core/engine/  C++20 引擎（pipeline/ log/ gl/ spike/，后续扩充 raw/ yuv/ film/ lut/）
docs/         开发计划与文档
```
