# 下载 URL → OUT（被 third_party/ultrahdr/CMakeLists.txt 用 execute_process 调用）。
# 为什么单独一个脚本：CMake 的 execute_process 里没法直接 curl，而 file(DOWNLOAD) 在
# 本项目里对 GitHub 会偶发 "Empty reply from server"（代理环境），包一层脚本便于重试。
set(attempts 0)
while(attempts LESS 3)
    math(EXPR attempts "${attempts} + 1")
    message(STATUS "download ${URL} (attempt ${attempts})")
    file(DOWNLOAD "${URL}" "${OUT}" STATUS st TLS_VERIFY OFF SHOW_PROGRESS)
    list(GET st 0 code)
    if(code EQUAL 0)
        file(SIZE "${OUT}" sz)
        if(sz GREATER 100000)
            message(STATUS "downloaded ${sz} bytes")
            return()
        endif()
    endif()
endwhile()
message(FATAL_ERROR "download failed: ${URL} (status ${st})")
