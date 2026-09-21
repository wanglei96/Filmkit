# FilmKit 项目交接文档（供 Agent 快速接手）

> 更新：2026-09-21 ｜ 来源：`.zcode/memory/` 记忆库全量快照（本目录为拷贝）+ 仓库实际状态
> 接手前建议：先读本文档，再按需读 `docs/开发计划.md` 与本目录最新快照（按文件名日期倒序，最新为 `RCD色偏修复收官M3降噪待优化.memo.md`）。

## 一、项目是什么

**FilmKit 胶片相机**：Android 相机 App（Kotlin + Compose UI + C++20 全 GPU 引擎），vivo X300 专项适配（Tier-0 机型）。核心链路：

```
Camera2 RAW_SENSOR/YUV 双输入 → GPU 引擎（去马赛克 → WB/CCM → 胶片风格化）→ 全分辨率成片 JPEG（+可选 DNG 底片）
```

卖点：M 档专业拍摄（EV/AE/WB/ISO/快门/手动对焦）+ RAW 底片 + 全 GPU 胶片渲染。里程碑 M0–M6 见 `docs/开发计划.md`（需求基线 v2.0 §0 D1–D13）。

## 二、当前状态

- **M0–M2 已全部完成**（含 RAW 端到端、M 档 UI、DNG 底片）。
- **M3（RAW 质量与性能）进行中**：
  - ✅ M3-1 RCD 质量档去马赛克已验收（真机 477ms ≤900ms 出口标准；与 bilinear 颜色一致；版本 v0.5.28–31）。
  - ⬜ **下一步主线：M3-2 降噪优化**（用户点名；先对真机 ISO~1200 暗光样张量化噪声，再选型 RAW 域方法，预算 ≤900ms）。
  - ⬜ 基准色彩内核 + 高光重建（治"整体发灰"：直方图无黑无白、P99≈185——tone curve/黑位/norm-controlled 高光，Filmic RGB/AgX 思路自研）。
- 最新版本 **v0.5.31-m3（versionCode 62）**，tag 与 main 均已推送，根目录 `filmkit.apk` 已同步。

## 三、代码结构

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/filmkit/app/` | Kotlin 侧：`ui/`（CameraScreen/SettingsScreen/FilmTheme）、`camera/`（CameraController 双流采集、RenderClient 渲染线程）、`EngineBridge.kt`（JNI 声明） |
| `core/engine/src/` | C++20 引擎：`render/FrameRenderer.cpp`（全部 GLSL 与三段链，**改 shader 只动这个文件**）、`jni/Bridge.cpp`、`gl/`、`log/`（JSONL+GPU 计时） |
| `docs/开发计划.md` | **活文档**：需求基线、里程碑、每次发版插入版本节（教训：插入时旧节标题要保留在 new_string 尾部） |
| `tools/validate-shaders.sh` | 发版前必跑的 shader 静态校验（提取 FrameRenderer.cpp 全部 GLSL 交 glslang，当前 11/11） |
| `.zcode/memory/` | Agent 记忆库（`/recall` 载入、`/handoff` 收尾）；分层：project/decisions/lessons/facts |

## 四、构建 / 验证 / 发版（照抄即可）

```bash
# 构建 + 单测（系统无 JDK17/Gradle，在 ~/.filmkit-dev/ 下）
JAVA_HOME=$HOME/.filmkit-dev/jdk-17.0.20.1+1/Contents/Home ./gradlew assembleDebug testDebugUnitTest

# shader 静态校验（发版前必跑；任何 GLSL 改动后必跑）
bash tools/validate-shaders.sh

# 出包（单文件覆盖制，filmkit.apk 已 gitignore）
cp app/build/outputs/apk/debug/app-debug.apk filmkit.apk

# 发版：git commit（英文 Conventional Commits）→ 附注标签 v<versionName> → push main + tag
# 版本号三处同步：app/build.gradle.kts(versionCode/versionName) + FilmKitApp.kt(启动日志/崩溃文件两处)
```

真机验证：adb 安装（`~/Library/Android/sdk/platform-tools/adb install -r filmkit.apk`）；日志在手机 `/storage/emulated/0/Android/data/com.filmkit.app/files/logs`（用户经 vivo 办公套件回传，Mac 也可直读 `~/Library/CloudStorage/文件管理系统-伯牙的vivoX300/`）；成片在 `Pictures/FilmKit/`。

## 五、致命坑速查（全量见 `.zcode/memory/lessons.md`）

1. **项目路径含中文**：AGP externalNativeBuild 会丢路径段（CXX1400）——已改自定义 CMake 任务（`app/build.gradle.kts` 的 `buildFilmEngine`），勿改回。
2. **shader 是设备端编译**：构建/单测全绿 ≠ 能用（v0.5.13 全黑屏事故）。任何 GLSL 改动：跑 validate-shaders.sh + 真机验证后才发版。
3. **行主序 CCM 数组传 `glUniformMatrix3fv` 必须 `transpose=GL_TRUE`**，否则全局品红。
4. **X300 Mali quirks**：RG8 TexSub 静默失败（预览走 SurfaceTexture）；RGBA16F 无线性过滤（管内全 texelFetch/NEAREST）；RGBA8 必配 UNSIGNED_BYTE、RGBA16F 必配 HALF_FLOAT。
5. **Bayer 结构**：G 位的 R/B 在四正邻 ±1（两种相位轴不同）；R/B 位的对角才是对侧色。
6. **CFA LUT 编码要解码侧验证**（v0.5.31 发红事故：位序反=R/B 对调+增益错乘）。
7. **跨语言参数沉默零值**与"设置器必须触发应用"是两类高危模式，新参数一律中性基线+防呆。
8. DngCreator.setOrientation 只收 EXIF 1..8（0/90/180/270→1/6/3/8）。

## 六、红线与约定

- **GPL-3.0 隔离**：PhotonCamera / RCD 参考实现（LuisSR/RawTherapee）只学算法思想与管线结构，**不抄代码**。
- 成片样张（`FILMKIT_*.jpg/dng`）与 `tools/bin/` 不入库（gitignore）。
- 预设/风格用中性命名，规避胶片商标。
- RCD/bilinear 双档：prefs `demosaic_quality`（默认 RCD），bilinear 是 A/B 基线与速度档，勿删。
- 性能出口标准：12MP RAW 全链 ≤900ms（看 `raw.render` 日志：uploadMs/passMs/totalMs/dirMs/gMs）。

## 七、接手后从哪开始

1. **M3-2 降噪**：用户明确点名。第一步做噪声量化（对真机暗光样张如 `FILMKIT_20260920-220231.jpg` 做 ISO~1200 噪声分析），再在 ≤900ms 预算内选 RAW 域方法（noiseProfile 自适应；NN+多帧是 M6 后立项，勿提前）。
2. **基准色彩内核 + 高光重建**：治发灰（定量证据：亮部 >224 占 0.00%、直方图挤中间）。
3. **真机项清单**：色温灰卡精校（WB_CAL_R=0.923/B=1.238 单点值待灰卡复核）、MF 屈光度手感复验、桌面 Lightroom 开 DNG 随手确认。
4. 已搁置：变焦轮盘灵敏度（待用户手感反馈）、超广角黑帧（已规避根因未明，勿主动碰）。

## 八、发版历史速览

v0.4.x UI 专项定稿 → v0.5.9 采集修复 → v0.5.10–19 成像质量/变焦/水平仪 → v0.5.20–27 RAW 端到端 + M2 收口 → v0.5.28–31 M3-1 RCD（实现→性能→G 位修复→LUT 修复）。细节见本目录对应快照与 `docs/开发计划.md` 版本节。
