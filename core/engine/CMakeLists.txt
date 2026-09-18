cmake_minimum_required(VERSION 3.22)
project(filmengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# FilmKit 引擎：M0（日志/能力/spike）+ M1（EGL、SurfaceTexture+OES 预览、共享后段）。
# 说明：手动 YUV 平面上传（yuv/YuvUploader）在 X300 Mali 上 RG8 TexSub 静默失败，
# 预览已改走 SurfaceTexture 系统路径（参照 PhotonCamera），零拷贝 AHardwareBuffer
# 记 M3 优化项。后续按 docs/开发计划.md 扩充 raw/ lut/ color/ io/。
add_library(filmengine SHARED
        src/jni/Bridge.cpp
        src/gl/Capabilities.cpp
        src/gl/EglCore.cpp
        src/log/Logger.cpp
        src/log/GpuTimer.cpp
        src/pipeline/Pipeline.cpp
        src/render/FrameRenderer.cpp
        src/spike/SpikePass.cpp)

target_include_directories(filmengine PRIVATE src)

find_library(log-lib log)
target_link_libraries(filmengine PRIVATE GLESv3 EGL android ${log-lib})

target_compile_options(filmengine PRIVATE -Wall -Wextra -Wno-unused-parameter)
