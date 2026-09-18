---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-09-18 10:20
status: active
---

# 事实

<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->

## 环境

- [2026-09-18] 构建链：AGP 8.13.2 / Gradle 8.13 / Kotlin 2.3.21 / Compose BOM 2026.06.01 / NDK 29 / CMake 3.22.1 / minSdk 26 / compileSdk 36；JDK17+Gradle 在 ~/.filmkit-dev/（系统无 JDK17/Gradle）
- [2026-09-18] 项目路径含中文（Downloads/胶片相机）：AGP externalNativeBuild 路径解析会丢中文段（CXX1400）——已改自定义 CMake Exec 任务直驱 NDK，见 app/build.gradle.kts
- [2026-09-18] 依赖上限：2026.09 版 AndroidX 需 AGP 9.1+/SDK 37；当前锁定 Compose BOM 2026.06.01 / core-ktx 1.17.0 / activity-compose 1.11.0
- [2026-09-18] X300：Android 16/OriginOS 6，Mali-G1-Ultra MC12，GL ES 3.2，7 相机（0=逻辑主摄、6=第二逻辑组、2/3/4/5=隐藏物理、1=前置）；主摄 RAW 4080×3072、CFA=GRBG、黑阶 64、maxISO 3200、OIS [0,1]；16F 无线性过滤
- [2026-09-18] X300 实测 GL 性能：1024² spike 三段 0.3–0.5ms/段（GPU 中位数）

## 路径与位置

- [2026-09-18] APK 产物：app/build/outputs/apk/debug/app-debug.apk；出包覆盖仓库根 filmkit.apk（单文件覆盖制，2026-09-18 起不再按版本号新建副本）
- [2026-09-18] 设备日志导出：/storage/emulated/0/Android/data/com.filmkit.app/files/logs（用户经 vivo办公套件回传）
- [2026-09-18] 审计导出：同上 files/audit/camera-audit-*.json
- [2026-09-18] 引擎 so：libfilmengine.so，JNI 入口 com.filmkit.app.EngineBridge

## 命令与操作

- [2026-09-18] 构建+单测：`JAVA_HOME=$HOME/.filmkit-dev/jdk-17.0.20.1+1/Contents/Home ./gradlew assembleDebug testDebugUnitTest`
- [2026-09-18] 命令行构建替代 JDK：系统默认 Java 8 跑不动 AGP，可用 `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`（Homebrew JDK23，实测 assembleDebug 通过）
- [2026-09-18] X300 焦段事实：启用镜头等效焦段 15/24/70（超广 id4、主摄 id2/0、长焦 id3）；焦段条由 lensEquivs() 生成
- [2026-09-18] adb 安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`
