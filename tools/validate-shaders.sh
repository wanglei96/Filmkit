#!/usr/bin/env bash
# M3 工程保障（v0.5.13 教训固化）：
#   引擎 shader 是设备运行时编译，构建/单测全绿不代表 GLSL 合法——
#   v0.5.13 的非法 step(vec3,float) 重载导致 prog_film_ 设备端编译失败全链路黑屏。
# 本脚本从 FrameRenderer.cpp 提取全部 GLSL 字符串（R"(...)" 原始字面量），
# 逐个交给 glslang(-Validator) 做 ES 3.1 fragment shader 静态校验，
# 任何一条失败即非零退出。
#
# 用法：tools/validate-shaders.sh
# 依赖：glslangValidator 或 glslang（PATH 中，或 tools/bin/glslang 本地副本，
#       下载自 KhronosGroup/glslang releases；tools/bin/ 已 gitignore）
set -euo pipefail

cd "$(dirname "$0")/.."
SRC="core/engine/src/render/FrameRenderer.cpp"

GLSLANG_BIN=""
for c in glslangValidator glslang tools/bin/glslang; do
  if command -v "$c" >/dev/null 2>&1; then GLSLANG_BIN="$c"; break; fi
done
if [ -z "$GLSLANG_BIN" ]; then
  echo "错误：找不到 glslang(-Validator)。安装：brew install glslang，" >&2
  echo "或下载 KhronosGroup/glslang release 的 macOS 版放到 tools/bin/glslang" >&2
  exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 提取 R"( ... )" 原始字符串（GLSL 均以 #version 开头，可精确切分）；
# 含 gl_Position 的是顶点着色器（.vert）、含 local_size 的是 compute（.comp），
# 其余按 fragment（.frag）校验
python3 - "$SRC" "$TMP" <<'PY'
import re, sys, pathlib
src = pathlib.Path(sys.argv[1]).read_text()
out = pathlib.Path(sys.argv[2])
shaders = re.findall(r'R"\((.*?)\)"', src, re.DOTALL)
glsl = [s for s in shaders if s.lstrip().startswith("#version")]
for i, s in enumerate(glsl):
    if "gl_Position" in s:
        stage = "vert"
    elif "local_size" in s:
        stage = "comp"
    else:
        stage = "frag"
    if stage == "comp":
        # glslang 的 ES profile 只放行 r32f image；ES 3.1/3.2 规范与 X300 实机
        # 支持的 rgba16f 等格式会被误拒 —— compute 转成 430 core 做语法/类型校验
        #（image 限定符等语义 GL4.3 ⊇ ES3.2，等价性足够）。
        s = s.replace("#version 310 es", "#version 430 core").replace(
            "#version 320 es", "#version 430 core")
    (out / f"shader_{i}.{stage}").write_text(s)
print(f"提取 {len(glsl)} 个 GLSL 字符串")
PY

FAIL=0
for f in "$TMP"/shader_*.vert "$TMP"/shader_*.frag "$TMP"/shader_*.comp; do
  [ -e "$f" ] || continue
  STAGE="${f##*.}"
  if OUT="$("$GLSLANG_BIN" -S "$STAGE" "$f" 2>&1)"; then
    echo "OK   $(basename "$f")"
  else
    echo "FAIL $(basename "$f")"
    echo "$OUT" | sed 's/^/     /'
    FAIL=1
  fi
done

if [ "$FAIL" -ne 0 ]; then
  echo "存在校验失败的 shader——设备端会编译失败并黑屏（v0.5.13 事故同类），禁止发版" >&2
  exit 1
fi
echo "全部 shader 校验通过"
