cmake_minimum_required(VERSION 3.22)
project(filmengine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# FilmKit 引擎：M0（日志/能力/spike）+ M1（EGL、SurfaceTexture+OES 预览、共享后段）。
# 说明：手动 YUV 平面上传（yuv/YuvUploader）在 X300 Mali 上 RG8 TexSub 静默失败，
# 预览已改走 SurfaceTexture 系统路径（参照 PhotonCamera），零拷贝 AHardwareBuffer
# 记 M3 优化项。后续按 docs/开发计划.md 扩充 raw/ lut/ color/ io/。
add_library(filmengine SHARED
        src/jni/Bridge.cpp
        src/jni/JpegEncoder.cpp
        src/jni/JpegEncode.cpp
        src/gl/Capabilities.cpp
        src/gl/EglCore.cpp
        src/log/Logger.cpp
        src/log/GpuTimer.cpp
        src/pipeline/Pipeline.cpp
        src/film/EngineRegistry.cpp
        src/film/SigmoidParams.cpp
        src/film/spektrafilm/Development.cpp
        src/film/spektrafilm/DirDevelopment.cpp
        src/film/spektrafilm/FilmExposure.cpp
        src/film/spektrafilm/Grain.cpp
        src/film/spektrafilm/OutputGamut.cpp
        src/film/spektrafilm/PrintScan.cpp
        src/film/spektrafilm/Profile.cpp
        src/film/spektrafilm/Render.cpp
        src/film/spektrafilm/SpatialFilter.cpp
        src/film/spektrafilm/Spectral.cpp
        src/render/Align.cpp
        src/render/HighlightGeometry.cpp
        src/render/HighlightRecovery.cpp
        src/render/AeMetering.cpp
        src/render/AmazeDemosaic.cpp
        src/render/FrameRenderer.cpp
        src/spike/SpikePass.cpp
        src/spike/SpikeFftMerge.cpp)

target_include_directories(filmengine PRIVATE src)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(arm_math "${CMAKE_CURRENT_SOURCE_DIR}/third_party/arm-math")
    add_library(spektrafilm_exp10 STATIC
        "${arm_math}/math/aarch64/advsimd/exp10.c"
        "${arm_math}/math/aarch64/advsimd/atan2.c"
        "${arm_math}/math/aarch64/advsimd/expm1.c"
        "${arm_math}/math/aarch64/advsimd/hypot.c"
        "${arm_math}/math/aarch64/advsimd/log1p.c"
        "${arm_math}/math/aarch64/v_exp_data.c"
        "${arm_math}/math/aarch64/advsimd/pow.c"
        "${arm_math}/math/aarch64/v_pow_log_data.c"
        "${arm_math}/math/aarch64/v_pow_exp_data.c")
    set_target_properties(spektrafilm_exp10 PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_include_directories(spektrafilm_exp10 PRIVATE
        "${arm_math}/math/include" "${arm_math}/math" "${arm_math}/math/aarch64/advsimd")
    # Inline the pinned vector exponent into the spectral pixel loop without
    # enabling LTO for RAW, shaders or the rest of the engine.
    set_property(SOURCE "${arm_math}/math/aarch64/advsimd/exp10.c" APPEND PROPERTY
        COMPILE_OPTIONS -flto=thin)
    set_property(SOURCE src/film/spektrafilm/Spectral.cpp APPEND PROPERTY
        COMPILE_OPTIONS -flto=thin)
    target_link_libraries(filmengine PRIVATE spektrafilm_exp10)
    set_property(SOURCE src/film/spektrafilm/Spectral.cpp APPEND PROPERTY
        COMPILE_DEFINITIONS FILMKIT_SPEKTRAFILM_ARM_EXP10)
    set_property(SOURCE src/film/spektrafilm/OutputGamut.cpp APPEND PROPERTY
        COMPILE_DEFINITIONS FILMKIT_SPEKTRAFILM_ARM_CAM16)
endif()

# Match the FP64 oracle's operation order without changing unrelated pipelines.
set_property(SOURCE
        src/film/spektrafilm/Development.cpp
        src/film/spektrafilm/DirDevelopment.cpp
        src/film/spektrafilm/FilmExposure.cpp
        src/film/spektrafilm/Grain.cpp
        src/film/spektrafilm/OutputGamut.cpp
        src/film/spektrafilm/PrintScan.cpp
        src/film/spektrafilm/Profile.cpp
        src/film/spektrafilm/Render.cpp
        src/film/spektrafilm/SpatialFilter.cpp
        src/film/spektrafilm/Spectral.cpp
        APPEND PROPERTY COMPILE_OPTIONS -ffp-contract=off)

# jnigraphics：JpegEncoder.cpp 的 AndroidBitmap_lockPixels（成片 4:4:4 直编，零拷贝）
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(filmengine PRIVATE GLESv3 EGL android ${log-lib} ${jnigraphics-lib})

# ── HDR（JPEG/R Ultra HDR）—— 默认 OFF ─────────────────────────────────
# 为什么做成开关：libultrahdr 要 vendor libjpeg-turbo（FetchContent，首次约 1~2 分钟），
# 日常构建不该为一条还没接进交付链的新路径付这份成本；**接交付链时再打开**。
# 打开后本目标多出 src/jni/UltraHdrEncoder.cpp（base 用我们的 stb 4:4:4，
# libultrahdr 只做 gainmap 封装 —— 定案与实测见 third_party/ultrahdr/CMakeLists.txt 头注）。
option(FILMKIT_HDR "Build Ultra HDR (JPEG/R) gainmap packaging" OFF)
if(FILMKIT_HDR)
    # ⚠ include() 而不是 add_subdirectory()：仓库路径含中文，add_subdirectory 出来的
    # 深层作用域里 add_library 解析不了源文件路径（同 build.gradle.kts 的 CXX1400 坑）。
    # include() 保持作用域在 core/engine，third_party/ultrahdr/CMakeLists.txt 内的相对路径稳定。
    include(third_party/ultrahdr/CMakeLists.txt)
    target_sources(filmengine PRIVATE src/jni/UltraHdrEncoder.cpp)
    target_link_libraries(filmengine PRIVATE ultrahdr jpeg-static)
    target_compile_definitions(filmengine PRIVATE FILMKIT_HDR=1)
endif()

target_compile_options(filmengine PRIVATE -Wall -Wextra -Wno-unused-parameter)
