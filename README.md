# FilmKit —— 胶片相机

RAW / YUV 双输入的胶片成像模拟 App（Android 首发，vivo X300 专项适配）。

Camera2 采集 → 全 GPU 胶片引擎（RCD 去马赛克 / 降噪 / 基准色彩科学 / 3D LUT 风格层）→ 全分辨率成片（JPEG，可选 DNG 底片）。
核心卖点：**M 档专业拍摄 + RAW 底片 + 全 GPU 胶片渲染**。

- 当前版本：**v0.6.40-m4**（versionCode 120）
- 里程碑计划与逐版本记录：[docs/开发计划.md](docs/开发计划.md)
- X300 能力审计（GPU/驱动实测事实）：[docs/x300-能力审计.md](docs/x300-能力审计.md)

## 构建

系统默认 `java` 是 JDK 8，而 AGP 8.13 要求 ≥11，必须显式指定：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
```

出包（**务必先删旧产物** —— 增量打包会把旧 `libfilmengine.so` 残留在 APK 里，体积虚高约 5MB）：

```bash
rm -rf app/build/outputs/apk
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk filmkit-<versionName>.apk
```

改动过引擎 shader（`core/engine/src/render/FrameRenderer.cpp` 内的 GLSL 原始字符串）**必须**先跑：

```bash
bash tools/validate-shaders.sh      # 15 条 shader 全量静态校验（构建与单测都拦不住 GLSL 错误）
```

安装：`~/Library/Android/sdk/platform-tools/adb install -r filmkit-<versionName>.apk`

## 目录

```
app/            Compose UI + Camera2 采集（CameraController/RenderClient/CameraAudit）+ JNI 调用侧
core/engine/    C++20 全 GPU 引擎（render/ gl/ log/ jni/）
docs/           开发计划、X300 能力审计、research/（独立调研取证报告）
tools/          离线分析脚本：shader 校验、色彩度量、DNG 取证、LUT 生成、成片反推
.zcode/memory/  AI 会话记忆库（项目状态 / 决策 / 教训 / 交接快照）—— 接手前建议先读 INDEX.md
```

## 开发约定（踩坑沉淀，务必遵守）

- **影调 / 色彩类改动必须能 A/B**（设置页开关）再发版；判据类改动先问"这个功能的意图是什么"。
- **改 shader 必须真机验证**：构建、单测、静态校验全绿也可能在设备端全黑。
- **跨实现比色度只用亮度无关口径**（`rel = max|cc|/luma`）；`C*` 随 `L*` 增长，跨亮度直接比会得出假结论。
- **离线参照不是标尺**：libraw / 参考实现的数值只用来定位缺口，判断"该多浓"必须用同一机位的 PhotonCamera 成片或用户基准。
- 出包前 `rm -rf app/build/outputs/apk`；样片放 `test_temp/`（已 gitignore，不入库）。
