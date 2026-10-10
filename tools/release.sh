#!/usr/bin/env bash
# 出包：改版本号 → 编译（失败即停）→ 核对包内版本 → 才复制交付包。
#
#   tools/release.sh <versionCode> <versionName>
#   tools/release.sh --check <apk> <versionCode> <versionName>    # 只核对（自检用）
#
# 为什么存在：手写这串命令时丢过管道退出码 —— 编译失败、复制了旧包、还照常提交
# （v0.11.10 事故：filmkit-0.11.10.apk 里装的其实是 0.11.9）。防呆不能靠记性。
set -euo pipefail
cd "$(dirname "$0")/.."
AAPT=$(ls ~/Library/Android/sdk/build-tools/*/aapt | tail -1)

matches() { # matches <apk> <versionCode> <versionName>
  local line
  line=$("$AAPT" dump badging "$1" 2>/dev/null | head -1)
  [[ "$line" == *"versionCode='$2'"* && "$line" == *"versionName='$3'"* ]]
}

if [[ "${1:-}" == "--check" ]]; then
  matches "$2" "$3" "$4" && echo "✓ $2 = $3 / $4" || { echo "✗ $2 不是 $3 / $4"; exit 1; }
  exit 0
fi

# ── JVM 版本校验（放在 --check 之后：纯核对只需要 aapt，不需要 JVM）────────────
# 为什么需要：AGP 8.13 要求 JVM ≥ 11，而 `${JAVA_HOME:-…}` 在"**已设置但版本过低**"时
# **不会**回退到 openjdk@23 —— 本脚本首次实战（v0.11.12）就踩了这条：环境里 JAVA_HOME
# 指向 Java 8，白跑一次构建才从 gradle 报错里看出来。
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@23}"
_JAVA="$JAVA_HOME/bin/java"
[[ -x "$_JAVA" ]] || { echo "✗ JAVA_HOME 下没有 java：$JAVA_HOME"; exit 1; }
# 版本号两种形态都要认：`1.8.0_361`（Java 8 及以前）与 `23.0.1`（9 起）
_JRAW=$("$_JAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([^"]+)".*/\1/')
_JVER=$(echo "$_JRAW" | awk -F'[._]' '{ print ($1 == 1) ? $2 : $1 }')
[[ "${_JVER:-0}" -ge 11 ]] || {
  echo "✗ 需要 JVM ≥ 11（AGP 8.13 要求），当前 JAVA_HOME=$JAVA_HOME（Java $_JRAW）"
  echo "  → JAVA_HOME=/opt/homebrew/opt/openjdk@23 tools/release.sh <versionCode> <versionName>"
  exit 1
}

VC="${1:?用法: tools/release.sh <versionCode> <versionName>}"
VN="${2:?用法: tools/release.sh <versionCode> <versionName>}"

python3 - "$VC" "$VN" <<'PY'
import re, sys
p = 'app/build.gradle.kts'
s = open(p, encoding='utf-8').read()
s = re.sub(r'versionCode\s*=?\s*\d+', f'versionCode = {sys.argv[1]}', s)
s = re.sub(r'versionName\s*=?\s*"[^"]*"', f'versionName = "{sys.argv[2]}"', s)
open(p, 'w', encoding='utf-8').write(s)
PY

./gradlew assembleDebug            # set -e：编译失败立刻停，不复制、不提交
SRC=app/build/outputs/apk/debug/app-debug.apk
matches "$SRC" "$VC" "$VN" || { echo "✗ 构建产物版本不是 $VC/$VN —— 中止（不复制）"; exit 1; }

APK="filmkit-${VN}.apk"
rm -f "$APK"
cp "$SRC" "$APK"
matches "$APK" "$VC" "$VN" || { echo "✗ 交付包核对失败 —— 已删除"; rm -f "$APK"; exit 1; }
echo "✓ $APK （$VC / $VN）—— 记得单独提交版本号改动"
