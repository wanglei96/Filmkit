# libultrahdr（vendored，v2.0.2）—— JPEG/R（Ultra HDR）gainmap 封装。
#
# 为什么 vendor 而不是用平台 API（2026-10-19 定案，实测确认）：
#   Android 的 `Bitmap.compress` + `Gainmap`（UltraHdrWriter 路径）base 恒 **4:2:0**，
#   与本项目"成片必须 4:4:4"的质量红线冲突（4:2:0 在中性强边缘引入的色度误差
#   p99 9.77 / max 49.17，是 4:4:4 的 1.36 / 1.94 的 7~25 倍 —— docs/reports/jpeg444-REPORT.md）。
#
# 本项目只用它的 **封装** 能力（uhdr_enc_set_compressed_image(UHDR_BASE_IMG) +
# uhdr_enc_set_gainmap_image → MPF + XMP 元数据），base 用**我们自己的 stb 4:4:4 JPEG**
# （src/jni/JpegEncoder.cpp），gainmap 同样由我们按官方 gainmapmath 公式算好后自编。
# 实测（tools/uhdr-probe，2026-10-19）：封装后 base 的 SOF0 仍是 4:4:4，
# uhdr_is_supported_gainmap_image = 1，官方解码器能回读出正确的 gainmap 元数据。
#
# 许可：Apache-2.0 / MIT 双许可（LICENSE-APACHE / LICENSE-MIT 随源码一并保留）。

cmake_minimum_required(VERSION 3.22)

set(UHDR_DIR ${CMAKE_CURRENT_LIST_DIR})
set(UHDR_SOURCE_DIR ${UHDR_DIR}/lib)
set(UHDR_THIRD_PARTY_DIR ${UHDR_DIR})

# ── libjpeg-turbo ────────────────────────────────────────────────────
# libultrahdr 的 jpegdecoderhelper / jpegencoderhelper / jpegr 都 include <jpeglib.h>，
# 整库编译必须提供；NDK 不自带（NDK 29 sysroot 无 jpeglib.h，已实测）。
# 只编 jpeg-static（静态、不开 shared），不装、不导出。
#
# ⚠ 不能用 FetchContent/add_subdirectory：libjpeg-turbo 的 CMakeLists.txt 第 61 行
# **主动拒绝**被 add_subdirectory 集成（"cannot be integrated into another build
# system using add_subdirectory(). Use ExternalProject_Add() instead."）—— upstream
# libultrahdr 自己也是 ExternalProject_Add。这里改用「配置期同步构建」：
# 用 execute_process 在 CMake 配置阶段把 clone + configure + build 跑完，
# 否则首次构建会因 jpeg-static 未就绪而在链接期失败。
set(JPEGTURBO_TAG 3.1.0)   # 与 upstream libultrahdr CMakeLists.txt 的 ExternalProject 同版本
set(JPEGTURBO_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo)
set(JPEGTURBO_SOURCE_DIR ${JPEGTURBO_PREFIX}/src)
set(JPEGTURBO_BINARY_DIR ${JPEGTURBO_PREFIX}/build)

# 优先 tarball（比 git clone 快且不受 git 代理/空回复影响），失败回退 git clone。
if(NOT EXISTS ${JPEGTURBO_SOURCE_DIR}/CMakeLists.txt)
    message(STATUS "Fetching libjpeg-turbo ${JPEGTURBO_TAG} ...")
    file(MAKE_DIRECTORY ${JPEGTURBO_PREFIX})
    set(_jt_url
            "https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/${JPEGTURBO_TAG}.tar.gz")
    execute_process(
            COMMAND ${CMAKE_COMMAND} -DURL=${_jt_url} -DOUT=${JPEGTURBO_PREFIX}/jt.tar.gz
            -P ${CMAKE_CURRENT_LIST_DIR}/download.cmake
            RESULT_VARIABLE jt_r OUTPUT_QUIET)
    if(jt_r EQUAL 0 AND EXISTS ${JPEGTURBO_PREFIX}/jt.tar.gz)
        file(MAKE_DIRECTORY ${JPEGTURBO_PREFIX}/src)
        execute_process(
                COMMAND ${CMAKE_COMMAND} -E tar xzf ${JPEGTURBO_PREFIX}/jt.tar.gz
                WORKING_DIRECTORY ${JPEGTURBO_PREFIX}/src
                RESULT_VARIABLE jt_r)
        # tarball 顶层目录形如 libjpeg-turbo-<tag> ⇒ 归一到 src/
        # ⚠ 不能用 file(RENAME)：中文路径下它会失败（同一 CXX1400 坑），走 shell mv。
        file(GLOB _jt_top "${JPEGTURBO_PREFIX}/src/libjpeg-turbo-*")
        if(_jt_top)
            list(GET _jt_top 0 _jt_d)
            if(NOT _jt_d STREQUAL ${JPEGTURBO_SOURCE_DIR})
                execute_process(
                        COMMAND sh -c "mv \"$2\" \"$1.tmp\" && rm -rf \"$1\" && mv \"$1.tmp\" \"$1\""
                        _ "${JPEGTURBO_SOURCE_DIR}" "${_jt_d}"
                        RESULT_VARIABLE jt_r)
            endif()
        endif()
    else()
        find_package(Git REQUIRED)
        execute_process(
                COMMAND ${GIT_EXECUTABLE} clone --depth 1 --branch ${JPEGTURBO_TAG}
                https://github.com/libjpeg-turbo/libjpeg-turbo.git ${JPEGTURBO_SOURCE_DIR}
                RESULT_VARIABLE jt_r OUTPUT_QUIET)
    endif()
    if(NOT jt_r EQUAL 0 OR NOT EXISTS ${JPEGTURBO_SOURCE_DIR}/CMakeLists.txt)
        message(FATAL_ERROR "libjpeg-turbo fetch failed (${jt_r}); src=${JPEGTURBO_SOURCE_DIR}")
    endif()
endif()

set(JPEG_LIB
        ${JPEGTURBO_BINARY_DIR}/${CMAKE_STATIC_LIBRARY_PREFIX}jpeg${CMAKE_STATIC_LIBRARY_SUFFIX})
set(JPEG_INCLUDE_DIRS ${JPEGTURBO_SOURCE_DIR}/src ${JPEGTURBO_BINARY_DIR})

if(NOT EXISTS ${JPEG_LIB})
    message(STATUS "Configuring libjpeg-turbo ...")
    execute_process(
            COMMAND ${CMAKE_COMMAND} -S ${JPEGTURBO_SOURCE_DIR} -B ${JPEGTURBO_BINARY_DIR}
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
            -DENABLE_SHARED=0 -DENABLE_STATIC=1
            -DWITH_TURBOJPEG=0 -DWITH_JPEG8=0
            RESULT_VARIABLE jt_r)
    if(NOT jt_r EQUAL 0)
        message(FATAL_ERROR "libjpeg-turbo configure failed (${jt_r})")
    endif()
    message(STATUS "Building libjpeg-turbo (jpeg-static) ...")
    execute_process(
            COMMAND ${CMAKE_COMMAND} --build ${JPEGTURBO_BINARY_DIR}
            --target jpeg-static -- -j8
            RESULT_VARIABLE jt_r)
    if(NOT jt_r EQUAL 0)
        message(FATAL_ERROR "libjpeg-turbo build failed (${jt_r})")
    endif()
endif()

if(NOT EXISTS ${JPEG_LIB})
    message(FATAL_ERROR "libjpeg-turbo static lib not found: ${JPEG_LIB}")
endif()

# 以 IMPORTED 目标接入（后续统一 link jpeg-static）
add_library(jpeg-static STATIC IMPORTED GLOBAL)
set_target_properties(jpeg-static PROPERTIES
        IMPORTED_LOCATION ${JPEG_LIB}
        INTERFACE_INCLUDE_DIRECTORIES "${JPEG_INCLUDE_DIRS}")

# ── image_io（XMP 元数据读写，upstream 必需）────────────────────────
set(IMAGEIO_TARGET_NAME uhdr_image_io)
# ⚠ 全部用**相对 core/engine 的路径**：本项目仓库路径含中文，add_subdirectory 出来的
# 深层作用域里 add_library 解析不了这些路径（同 build.gradle.kts 的 CXX1400 坑），
# 故本文件由主 CMakeLists 用 include() 引入 —— 作用域仍是 core/engine，相对路径稳定。
file(GLOB_RECURSE IMAGE_IO_SRCS_LIST "third_party/ultrahdr/image_io/src/*.cc")
list(LENGTH IMAGE_IO_SRCS_LIST _n)
message(STATUS "uhdr image_io sources: ${_n}")
add_library(${IMAGEIO_TARGET_NAME} STATIC ${IMAGE_IO_SRCS_LIST})
target_include_directories(${IMAGEIO_TARGET_NAME} PRIVATE
        third_party/ultrahdr/image_io/includes
        third_party/ultrahdr/image_io/src/modp_b64
        third_party/ultrahdr/image_io/src/modp_b64/modp_b64)

# ── core ─────────────────────────────────────────────────────────────
set(UHDR_CORE_LIB_NAME uhdr_core)
file(GLOB UHDR_CORE_SRCS_LIST "third_party/ultrahdr/lib/src/*.cpp")
# NEON 加速（gainmapmath / editorhelper）：arm64 必需，x86 版已在上游 vendoring 时删除。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    file(GLOB UHDR_CORE_NEON_SRCS_LIST "third_party/ultrahdr/lib/src/dsp/arm/*.cpp")
    list(APPEND UHDR_CORE_SRCS_LIST ${UHDR_CORE_NEON_SRCS_LIST})
endif()

add_library(${UHDR_CORE_LIB_NAME} STATIC ${UHDR_CORE_SRCS_LIST})
target_include_directories(${UHDR_CORE_LIB_NAME} PRIVATE
        third_party/ultrahdr/lib/include/
        third_party/ultrahdr
        ${JPEG_INCLUDE_DIRS}
        third_party/ultrahdr/image_io/includes/)
# 公开 API 头（ultrahdr_api.h 在 UHDR_DIR 根）
target_include_directories(${UHDR_CORE_LIB_NAME} PUBLIC ${UHDR_DIR})
if(${CMAKE_SYSTEM_NAME} MATCHES "Android")
    find_library(log-lib log)
    target_link_libraries(${UHDR_CORE_LIB_NAME} PUBLIC ${log-lib})
endif()
find_package(Threads REQUIRED)
# 元数据格式（lib/src/jpegr.cpp 的 static_assert 要求二者至少开一个）：
#   UHDR_WRITE_XMP = XMP 包（**Adobe gain map 标准**）—— Android 相册 / Chrome /
#                    Google Photos 认的就是它 ⇒ 本项目必须开。
#   UHDR_WRITE_ISO = ISO 21496-1 包（upstream 默认开）
# 两个都开：兼容面最大，代价只是多一段 XMP（~1KB）。实测（2026-10-19）只开 ISO 时
# 输出里查不到 XMP（APP1 http://ns.adobe.com/xap/1.0/），下游按 Adobe 标准解析会拿不到 gainmap。
target_compile_definitions(${UHDR_CORE_LIB_NAME} PRIVATE UHDR_WRITE_XMP UHDR_WRITE_ISO)
target_link_libraries(${UHDR_CORE_LIB_NAME} PRIVATE jpeg-static Threads::Threads ${IMAGEIO_TARGET_NAME})
# 上游用 -Werror 编译；本项目主库开 -Wall -Wextra，这里对齐但不开 -Werror
target_compile_options(${UHDR_CORE_LIB_NAME} PRIVATE -Wall -Wextra)

# ── 对外门面：一个库名搞定（调用方只 link 这个）──────────────────────
add_library(ultrahdr INTERFACE)
target_link_libraries(ultrahdr INTERFACE ${UHDR_CORE_LIB_NAME} jpeg-static)
target_include_directories(ultrahdr INTERFACE ${UHDR_DIR})
