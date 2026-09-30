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

val engineAbis = listOf("arm64-v8a", "x86_64")
val engineSrcDir = rootProject.projectDir.resolve("core/engine")
val engineBuildRoot = layout.buildDirectory.dir("filmengine")
val engineJniLibsDir = engineBuildRoot.map { it.dir("jniLibs") }

val buildFilmEngine by tasks.registering {
    val libsOut = engineJniLibsDir
    outputs.dir(libsOut)
    inputs.dir(engineSrcDir.resolve("src"))
    inputs.file(engineSrcDir.resolve("CMakeLists.txt"))

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
        versionCode = 206
        versionName = "0.10.54-m4"
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
    testImplementation(libs.junit)
}
