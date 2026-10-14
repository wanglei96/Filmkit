#!/usr/bin/env bash
# M3 工程保障（v0.5.13 教训固化）：
#   引擎 shader 是设备运行时编译，构建/单测全绿不代表 GLSL 合法——
#   v0.5.13 的非法 step(vec3,float) 重载导致 prog_film_ 设备端编译失败全链路黑屏。
# 本脚本从 FrameRenderer.cpp 提取全部 GLSL 字符串（R"(...)" 原始字面量），
# 逐个交给 glslang(-Validator) 做 ES 3.1 fragment shader 静态校验，
# 任何一条失败即非零退出。
#
# 用法：tools/validate-shaders.sh [FrameRenderer.cpp 路径] [--selftest]
# 依赖：glslangValidator 或 glslang（PATH 中，或 tools/bin/glslang 本地副本，
#       下载自 KhronosGroup/glslang releases；tools/bin/ 已 gitignore）
#
# ── 引擎插槽阶段 2（2026-10-13）：补上"引擎 1 完全不在覆盖范围内"的缺口 ──────────
# 缺口原样：本脚本此前只按 `以 #version 开头` 过滤字面量，而引擎 1 的两个注入段
# （`kFsSigmoidDef` / `kFsSigmoidMain`）**都不以 `#version` 开头**（它们是拼进 head
# 之后的段）⇒ 引擎 1 的 GLSL 语法错误**只能到真机才发现**（= 又一次 v0.5.13 同类）。
# 现在：head + 引擎 1 def + mid + 引擎 1 main + tail **先拼回整份**再校验（含
# `#version 300 es` 与 `main`），并加语义哨兵 —— 少一段就报错，**不静默漏检**。
#   ⚠ 既有教训（阶段 1）：逐段过滤 `#version` 会把只有 head 才带的 `#version` 留下、
#     把 `main` 丢掉 ⇒ glslang 对"没有 main 的 fragment shader"**不报错**（假绿）。
#     所以任何"分段校验"都必须先拼回整份。
#
# ── `--selftest`（负向自检 / mutation test）────────────────────────────────────
# 项目教训："验证工具自身会假绿，只有负向自检能抓"。本模式做三件事并断言结果：
#   ① 基线（未变异）必须**绿**；
#   ② 在引擎 1 的 GLSL 里注入一个语法错误（删一个分号）⇒ 必须**红**；
#   ③ 把引擎 1 的 main 段改名（= 少一段）⇒ 必须**红**（防"悄悄跳过引擎 1"）。
# 任一断言不成立即非零退出。变异只作用于临时副本，**不改工作区文件**。
set -euo pipefail

cd "$(dirname "$0")/.."
# 可选参数：被校验的源文件（默认 FrameRenderer.cpp）。用于验证本脚本自身
# 在「kFsFilm 已拆成模板常量」形态下的行为（C++ 侧落盘前无法用真实文件覆盖）。
SRC=""
SELFTEST=0
for arg in "$@"; do
  case "$arg" in
    --selftest) SELFTEST=1 ;;
    *) SRC="$arg" ;;
  esac
done
SRC="${SRC:-core/engine/src/render/FrameRenderer.cpp}"

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

# 提取 + 拼装 + glslang 校验。$1 = 源文件，$2 = 本次的输出目录。
# 返回 0 = 全绿；非 0 = 有失败（提取/拼装阶段出错也算失败，不静默跳过）。
run_check() {
  local S="$1" T="$2" rc=0
  mkdir -p "$T"
  if ! python3 - "$S" "$T" <<'PY'
import re, sys, pathlib
src = pathlib.Path(sys.argv[1]).read_text()
out = pathlib.Path(sys.argv[2])

named = {m.group(1): m.group(2) for m in
         re.finditer(r'const char\*\s+(\w+)\s*=\s*R"\((.*?)\)";', src, re.DOTALL)}
raw = re.findall(r'R"\((.*?)\)"', src, re.DOTALL)
TEMPLATE = ("kFilmTemplateHead", "kFilmTemplateMid", "kFilmTemplateTail")
# 引擎 1（sigmoid）的两个注入段：名字与 EngineRegistry.cpp 的表项、离线链
# （engine-shader-offline.py 的 _collect_raw_strings）三处**逐字一致**。
ENGINE1 = ("kFsSigmoidDef", "kFsSigmoidMain")


def unescape(s):
    """C++ 字符串字面量反转义（锚点里只用到 \\n / \\t / \\" / \\\\）。"""
    res, i = [], 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            res.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\'}.get(s[i + 1], s[i + 1]))
            i += 2
        else:
            res.append(s[i])
            i += 1
    return ''.join(res)


# 切分锚点**从 C++ 源码里读**（不在这里复制字面量 ⇒ 不可能与产品代码漂移）。
anchors = {m.group(1): unescape(m.group(2)) for m in re.finditer(
    r'constexpr std::string_view (kFilmSplit\w+)\s*=\s*"((?:[^"\\]|\\.)*)";', src)}
ANCHOR_NAMES = ('kFilmSplitDefAnchor', 'kFilmSplitMidAnchor',
                'kFilmSplitMainAnchor', 'kFilmSplitMainBlockEnd')


def split_film(body):
    """复刻 C++ `filmSlices()` 的锚点切分，返回 (head, mid, tail)。锚点缺失返回 None。"""
    if any(n not in anchors for n in ANCHOR_NAMES):
        return None
    a = body.find(anchors['kFilmSplitDefAnchor'])
    b = body.find(anchors['kFilmSplitMidAnchor'], a) if a >= 0 else -1
    c = body.find(anchors['kFilmSplitMainAnchor'], b) if b >= 0 else -1
    e = body.find(anchors['kFilmSplitMainBlockEnd'], c) if c >= 0 else -1
    if min(a, b, c, e) < 0 or not (a < b < c < e):
        return None
    d = e + len(anchors['kFilmSplitMainBlockEnd'])
    return body[:a], body[b:c], body[d:]


glsl = []      # [(文件名, 标签, 源码)]
skip = set()   # 已纳入校验的整份源码（散段不再单独校验）

# ── 引擎 0（filmic）──────────────────────────────────────────────────────────
# 两种形态都必须校验到 filmic，否则会漏检或误报：
#   形态 1（当前）：kFsFilm 仍是完整字面量 ⇒ 整份直接校验，行为一字不变；
#   形态 2（真拆成独立字面量）：单独校验任一段都会误报 FAIL（缺 main）/漏检（无
#     #version）⇒ 按 head + engine_def + mid + engine_main + tail 拼回整份再校验，
#     并把散段从候选池剔除。锚点缺一段即报错，不静默漏检。
if "kFsFilm" in named:
    glsl.append(("shader_kFsFilm.frag", "kFsFilm（引擎 0 filmic，整份）", named["kFsFilm"]))
elif all(n in named for n in TEMPLATE):
    order = [n for n in named if n.startswith("kFilm")]
    h, m, t = (order.index(n) for n in TEMPLATE)
    parts = ([named[TEMPLATE[0]]] + [named[n] for n in order[h + 1:m]]
             + [named[TEMPLATE[1]]] + [named[n] for n in order[m + 1:t]]
             + [named[TEMPLATE[2]]])
    film_src = "".join(parts)   # 必须**先拼回整份再判断**：逐段过滤只有 head 带
    assert film_src not in {c for n, c in named.items() if n.startswith("kFilm")}
    # `#version` 会把 main 丢掉 ⇒ 校验一个残缺 shader（假绿）
    glsl.append(("shader_kFsFilm.frag", "kFilmTemplate*（引擎 0 filmic，拼回整份）", film_src))
    print(f"kFsFilm 已拆为 {len(parts)} 段：拼回整份后校验 filmic（散段不再单独校验）")
elif any(n in named for n in TEMPLATE):
    sys.exit("错误：kFilmTemplate* 只落盘了一部分，且 kFsFilm 不在 —— 无法校验 filmic shader")
skip |= {s for _, _, s in glsl}   # 已拼回整份的不再按散段重复校验（kFsFilm 尤其）
skip |= {c for n, c in named.items() if n in TEMPLATE or n in ENGINE1}

# ── 引擎 1 uniform 契约核对（GLSL 声明 ↔ glGetUniformLocation ↔ glUniform1f 下发）──
# 为什么值得单独守：三处名字一旦漂移，**设备端不报错** —— `glGetUniformLocation` 返回
# −1，`glUniform1f(−1, …)` 是合法 no-op ⇒ shader 侧拿到 uniform 默认值 0.0 ⇒
# `white_target = 0` ⇒ 输出恒 0（**全黑照片**），而构建、单测、其它 shader 校验全绿。
if all(n in named for n in ENGINE1):
    decls = set(re.findall(r'^uniform float (uSig\w+);', named["kFsSigmoidDef"], re.M))
    queried = set(re.findall(r'glGetUniformLocation\(prog_film_, "(uSig\w+)"\)', src))
    pushes = re.findall(r'glUniform1f\(u_film_\.(sig_\w+), sigmoid_\.(\w+)\);', src)
    problems = []
    if len(decls) != 7:
        problems.append("GLSL 声明 %d 个 uSig*（应为 7）" % len(decls))
    if decls != queried:
        problems.append("GLSL 声明与 glGetUniformLocation 名不一致：只声明未查询 %s / "
                        "只查询未声明 %s" % (sorted(decls - queried), sorted(queried - decls)))
    if len(pushes) != 7:
        problems.append("glUniform1f 下发 %d 条（应为 7）" % len(pushes))
    for field, member in pushes:
        if member != field[len("sig_"):]:
            problems.append("%s 下发的 SigmoidParams 成员名 %s 不符约定" % (field, member))
        want = "uSig" + "".join(p.capitalize() for p in field[len("sig_"):].split("_"))
        if want not in decls:
            problems.append("%s 按约定应对应 %s，但 GLSL 里没有该声明" % (field, want))
    if len({f for f, _ in pushes}) != len(pushes):
        problems.append("同一个 u_film_.sig_* 被下发多次")
    if problems:
        sys.exit("错误：引擎 1 的 uniform 契约核对未过 —— " + "；".join(problems))
    print("引擎 1 uniform 契约：GLSL 声明 %d 个 = 查询 %d 个 = 下发 %d 条，名字逐字一致"
          % (len(decls), len(queried), len(pushes)))

# ── 引擎 1（sigmoid）─────────────────────────────────────────────────────────
# 拼装顺序与 C++ `filmSource()` 一致：head + def + mid + main + tail。
if all(n in named for n in ENGINE1):
    body = named.get("kFsFilm")
    parts = None
    if body is not None:
        parts = split_film(body)
        if parts is None:
            sys.exit("错误：引擎 1 的注入段在，但 kFsFilm 的切分锚点缺失/错序 —— "
                     "无法拼出引擎 1 的整份 shader（不许静默跳过）")
    elif all(n in named for n in TEMPLATE):
        parts = (named[TEMPLATE[0]], named[TEMPLATE[1]], named[TEMPLATE[2]])
    if parts is None:
        sys.exit("错误：引擎 1 的注入段在，但取不到 head/mid/tail —— 无法拼出整份 shader")
    head, mid, tail = parts
    e1 = head + named["kFsSigmoidDef"] + mid + named["kFsSigmoidMain"] + tail
    # 语义哨兵：拼装结果必须真的像"一份引擎 1 的完整 shader"（防拼错位置后仍绿）
    sentinels = [
        ("以 #version 开头（head 在最前）", e1.lstrip().startswith("#version")),
        ("含 void main() {", "void main() {" in e1),
        ("含引擎 1 入口 sigmoidTone 定义", "vec3 sigmoidTone(" in e1),
        ("main 里调用 sigmoidTone(out3)", "sigmoidTone(out3)" in e1),
        ("含引擎 1 的 uSig* 声明", "uniform float uSigWhiteTarget;" in e1),
        # 注入段没串台：引擎 0 的**函数定义与 uniform 声明**必须都不在（注意不能用
        # `"filmicTone" not in e1` —— head 段的注释里就出现过这个词，会误报）。
        ("不含引擎 0 的 filmic 函数定义", "float filmicScalarR(" not in e1),
        ("不含引擎 0 的 gamut 函数定义", "vec3 gamutMapV5(" not in e1),
        ("不含引擎 0 的 uFilmic* 声明", "uniform vec3 uFilmicToeM1" not in e1),
    ]
    bad = [n for n, ok in sentinels if not ok]
    if bad:
        sys.exit("错误：引擎 1 拼装结果哨兵未过：%s" % "；".join(bad))
    glsl.append(("shader_kFsSigmoid.frag",
                 "kFsSigmoid（引擎 1 sigmoid，head+def+mid+main+tail 拼回整份）", e1))
elif any(n in named for n in ENGINE1):
    sys.exit("错误：引擎 1 的注入段只落盘了一部分（%s）—— 无法校验引擎 1，"
             "且**不许静默跳过**" % "、".join(n for n in ENGINE1 if n in named))
skip |= {c for n, c in named.items() if n in ENGINE1}

# ── 其余字面量（顶点 / compute / 其它 fragment）──────────────────────────────
for i, s in enumerate(raw):
    if s.lstrip().startswith("#version") and s not in skip:
        glsl.append((f"shader_extra{i}.frag", f"其它字面量 #{i}", s))

labels = []
for fname, label, s in glsl:
    stage = "vert" if "gl_Position" in s else ("comp" if "local_size" in s else "frag")
    fname = fname if fname.endswith("." + stage) else re.sub(r'\.\w+$', '.' + stage, fname)
    if stage == "comp":
        # glslang 的 ES profile 只放行 r32f image；ES 3.1/3.2 规范与 X300 实机
        # 支持的 rgba16f 等格式会被误拒 —— compute 转成 430 core 做语法/类型校验
        #（image 限定符等语义 GL4.3 ⊇ ES3.2，等价性足够）。
        s = s.replace("#version 310 es", "#version 430 core").replace(
            "#version 320 es", "#version 430 core")
    (out / fname).write_text(s)
    labels.append((fname, label))

(out / "labels.tsv").write_text(
    "".join(f"{n}\t{l}\n" for n, l in labels), encoding="utf-8")
for n, l in labels:
    if "kFsSigmoid" in n or "kFsFilm" in n:
        print(f"纳入校验：{n}  ←  {l}")
print(f"提取 {len(glsl)} 个 GLSL 字符串（其中引擎 shader："
      f"{sum(1 for n, _ in labels if 'kFsFilm' in n or 'kFsSigmoid' in n)} 份）")
PY
  then
    echo "FAIL 提取/拼装阶段（见上面的错误；不做静默跳过）"
    return 1
  fi

  local f STAGE OUT LABEL
  for f in "$T"/shader_*.vert "$T"/shader_*.frag "$T"/shader_*.comp; do
    [ -e "$f" ] || continue
    STAGE="${f##*.}"
    LABEL="$(grep -F "$(basename "$f")"$'\t' "$T/labels.tsv" | cut -f2- || true)"
    if OUT="$("$GLSLANG_BIN" -S "$STAGE" "$f" 2>&1)"; then
      echo "OK   $(basename "$f")${LABEL:+  [$LABEL]}"
    else
      echo "FAIL $(basename "$f")${LABEL:+  [$LABEL]}"
      echo "$OUT" | sed 's/^/     /'
      rc=1
    fi
  done
  return $rc
}

if [ "$SELFTEST" -eq 1 ]; then
  echo "══ 负向自检（mutation test）：证明本校验器对引擎 1 有牙 ══"
  echo "── ① 基线（未变异，预期绿）──"
  if run_check "$SRC" "$TMP/base"; then
    echo "✅ 基线绿（预期）"
  else
    echo "❌ 基线就红 ⇒ 环境或源码有问题，自检无法继续" >&2
    exit 1
  fi
  # 变异只作用于临时副本（不改工作区文件）。
  python3 - "$SRC" "$TMP/mut1.cpp" "$TMP/mut2.cpp" "$TMP/mut3.cpp" <<'PY'
import pathlib, sys
src = pathlib.Path(sys.argv[1]).read_text()
# ① 引擎 1 的 GLSL 里删一个分号（语法错误，必须被 glslang 抓到）
old = "    return clamp(paperResponse, 0.0, uSigWhiteTarget);"
new = "    return clamp(paperResponse, 0.0, uSigWhiteTarget)"
if src.count(old) != 1:
    sys.exit("自检失效：锚点 %r 在源码里出现 %d 次（应为 1）" % (old, src.count(old)))
pathlib.Path(sys.argv[2]).write_text(src.replace(old, new))
# ② 引擎 1 的 main 段改名 = "少一段"（必须报错，不许静默跳过引擎 1）
old2 = "kFsSigmoidMain = R\"("
if src.count(old2) != 1:
    sys.exit("自检失效：锚点 %r 出现 %d 次（应为 1）" % (old2, src.count(old2)))
pathlib.Path(sys.argv[3]).write_text(src.replace(old2, "kFsSigmoidMainRenamed = R\"("))
# ③ 只改**查询名**（GLSL 里不动）= 最危险的静默失效：glslang 仍绿、设备端 location=−1、
#    uniform 取默认 0 ⇒ 全黑照片。uniform 契约核对必须抓到它。
old3 = 'glGetUniformLocation(prog_film_, "uSigFilmPower")'
if src.count(old3) != 1:
    sys.exit("自检失效：锚点 %r 出现 %d 次（应为 1）" % (old3, src.count(old3)))
pathlib.Path(sys.argv[4]).write_text(
    src.replace(old3, 'glGetUniformLocation(prog_film_, "uSigFilmPowr")'))
print("变异副本已生成：mut1（引擎 1 删分号）/ mut2（引擎 1 少 main 段）/ "
      "mut3（查询名漂移）")
PY
  echo "── ② 引擎 1 注入语法错误（删一个分号）⇒ 预期红 ──"
  if run_check "$TMP/mut1.cpp" "$TMP/mut1" >"$TMP/mut1.log" 2>&1; then
    echo "❌ 注入语法错误后校验器**仍然绿** ⇒ 校验器无牙（假绿）" >&2
    sed 's/^/     /' "$TMP/mut1.log" >&2
    exit 1
  fi
  echo "✅ 变异后变红（校验器有牙）。失败证据："
  grep -E "^(FAIL|错误)|ERROR:" "$TMP/mut1.log" | head -5 | sed 's/^/     /'
  echo "── ③ 引擎 1 少一段（main 段改名）⇒ 预期红 ──"
  if run_check "$TMP/mut2.cpp" "$TMP/mut2" >"$TMP/mut2.log" 2>&1; then
    echo "❌ 少一段后校验器**仍然绿** ⇒ 静默漏检" >&2
    exit 1
  fi
  echo "✅ 少一段变红（不静默漏检）。失败证据："
  grep -E "^(FAIL|错误)|ERROR:" "$TMP/mut2.log" | head -5 | sed 's/^/     /'
  echo "── ④ uniform 查询名漂移（GLSL 不动 ⇒ glslang 仍绿，最危险的静默失效）⇒ 预期红 ──"
  if run_check "$TMP/mut3.cpp" "$TMP/mut3" >"$TMP/mut3.log" 2>&1; then
    echo "❌ 查询名漂移后校验器**仍然绿** ⇒ uniform 契约核对无牙（设备端会全黑）" >&2
    exit 1
  fi
  echo "✅ 查询名漂移变红。失败证据："
  grep -E "^(FAIL|错误)|ERROR:" "$TMP/mut3.log" | head -5 | sed 's/^/     /'
  echo "负向自检全部通过（基线绿 / 语法变异红 / 少段红 / 名字漂移红）"
  exit 0
fi

if run_check "$SRC" "$TMP/run"; then
  echo "全部 shader 校验通过（含引擎 1 sigmoid）"
else
  echo "存在校验失败的 shader——设备端会编译失败并黑屏（v0.5.13 事故同类），禁止发版" >&2
  exit 1
fi
