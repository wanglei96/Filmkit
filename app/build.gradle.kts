import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── 引擎 NDK 构建（自定义 CMake 任务）────────────────────────────────
// 不用 AGP externalNativeBuild：项目路径含非 ASCII（中文）时其路径解析会
// 丢失该段（CXX1400），改为直接调用 CMake + NDK 工具链，产物进 jniLibs。
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val sdkDirPath = localProps.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: throw GradleException("未找到 Android SDK：请配置 local.properties 的 sdk.dir")
val sdkDir = File(sdkDirPath)

val engineNdkVersion = "29.0.14206865"
val ndkDir = File(sdkDir, "ndk/$engineNdkVersion").takeIf { it.exists() }
    ?: File(sdkDir, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
    ?: throw GradleException("未找到 NDK：请在 SDK Manager 安装（优先 $engineNdkVersion）")

val engineCmakeBin = sequenceOf(
    File(sdkDir, "cmake/3.22.1/bin/cmake"),
    File("/opt/homebrew/bin/cmake"),
    File("/usr/local/bin/cmake"),
    File("/usr/bin/cmake"),
).firstOrNull { it.exists() }
    ?: throw GradleException("未找到 cmake")

// HDR（Ultra HDR / JPEG-R）编译开关：默认关。
// 打开后 native 侧会 vendor 编译 libjpeg-turbo（首次约 1~2 分钟），.so 增大约 1MB。
// 用 -PfilmkitHdr=true 打开：`./gradlew assembleDebug -PfilmkitHdr=true`
val engineHdr = (project.findProperty("filmkitHdr") as String?)?.toBoolean() ?: false

val engineAbis = listOf("arm64-v8a", "x86_64")
val engineSrcDir = rootProject.projectDir.resolve("core/engine")
val engineBuildRoot = layout.buildDirectory.dir("filmengine")
val engineJniLibsDir = engineBuildRoot.map { it.dir("jniLibs") }

val buildFilmEngine by tasks.registering {
    val libsOut = engineJniLibsDir
    outputs.dir(libsOut)
    inputs.dir(engineSrcDir.resolve("src"))
    inputs.file(engineSrcDir.resolve("CMakeLists.txt"))
    // ⚠ **必须把 HDR 开关纳入 inputs**（v0.11.50 事故）：它是 `-P` 属性，不在源码里，
    // 不声明的话"先编 debug(带 HDR) 再编 release(不带)"会命中 up-to-date 缓存 ⇒
    // release 包里 .so 缺 nativeEncodeUltraHdr，HDR 静默失效（真机表现为
    // `photo.encode hdr=none`，且看不出任何报错）。
    inputs.property("filmkitHdr", engineHdr)
    inputs.dir(engineSrcDir.resolve("third_party/ultrahdr"))

    doLast {
        engineAbis.forEach { abi ->
            val buildDir = engineBuildRoot.get().asFile.resolve(abi)
            exec {
                commandLine(
                    engineCmakeBin.absolutePath,
                    "-S", engineSrcDir.absolutePath,
                    "-B", buildDir.absolutePath,
                    "-DCMAKE_TOOLCHAIN_FILE=" + File(ndkDir, "build/cmake/android.toolchain.cmake").absolutePath,
                    "-DANDROID_ABI=$abi",
                    "-DANDROID_PLATFORM=android-26",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DFILMKIT_HDR=" + (if (engineHdr) "ON" else "OFF"),
                )
            }
            exec {
                commandLine(
                    engineCmakeBin.absolutePath,
                    "--build", buildDir.absolutePath,
                    "--target", "filmengine",
                    "--", "-j8",
                )
            }
            copy {
                from(buildDir.resolve("libfilmengine.so"))
                into(libsOut.get().asFile.resolve(abi))
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(buildFilmEngine) }

// ── Android ─────────────────────────────────────────────────────────
android {
    namespace = "com.filmkit.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.filmkit.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 407
        versionName = "0.11.58"
        // 只打真机用的 arm64：CMake 有时会把 x86_64 也编进 jniLibs（产物不稳定），
        // 那会让 APK 平白多 5.4MB（v0.6.31 实测 33.6MB → 39.2MB）。
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(engineJniLibsDir)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // v0.10.23：开 BuildConfig，让 FilmKitApp.VERSION 直接读 versionName —— 此前是
        // 另一处硬编码字面量（注释写着"必须与 build.gradle.kts 一致"，实际各漂各的：
        // 174 的包在日志里仍报 0.10.22，直接导致真机版本判读错误）。
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // HDR 交付（v0.11.50）：HEIC + gainmap 走平台 HeifWriter（对齐 PhotonCamera 的
    // HeicExportEncoder）。**只在 HDR 路径用** —— 非 HDR 的 10bit HEIF 仍走项目自研容器
    // （Heif10Pipeline/HeifContainerWriter），那条链已真机验收，不在此改动范围内。
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    // NN spike（v0.10.100）：TFLite 独立 runtime（不用 Play Services 版，验证包离线可用）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    testImplementation(libs.junit)
}
