#!/usr/bin/env bash
# 重新应用 "$sessionId 占位符" 补丁到 dsh-llm-pi-ai。
#
# 背景：该补丁改的是 npx 缓存里的 DSH 本体包，升级/重装 DSH 后会丢失。
# 升级后如果发现「不同对话又共用同一个 session ID」，跑一次本脚本即可。
#
# 用法：bash tools/reapply-sessionid-patch.sh
set -euo pipefail

PKG="$HOME/.npm/_npx/1e7f6d9597241db0/node_modules/@deepseek-ai/dsh-llm-pi-ai/lib/index.js"

if [ ! -f "$PKG" ]; then
  echo "❌ 找不到 $PKG"
  echo "   npx 缓存 hash 可能变了，请用下面命令重新定位："
  echo "   find ~/.npm/_npx -path '*@deepseek-ai/dsh-llm-pi-ai/lib/index.js'"
  exit 1
fi

if grep -q 'function requestHeaders(headers, sessionId)' "$PKG"; then
  echo "✅ 补丁已在位，无需重打。"
  exit 0
fi

# 备份原件（仅首次）
[ -f "$PKG.orig-backup" ] || cp "$PKG" "$PKG.orig-backup"

python3 - "$PKG" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

old_fn = re.search(r'/\*\* Merge deployment headers.*?\nfunction requestHeaders\(headers\) \{.*?\n\}', s, re.S)
if not old_fn:
    sys.exit('❌ 未找到原始 requestHeaders 定义，版本可能已变，请人工处理。')

new_fn = '''/** Merge deployment headers while removing case-insensitive attribution collisions.
 * `$sessionId` in a header value expands to the live session identity; a request
 * without one drops the header rather than sending the literal placeholder. */
function requestHeaders(headers, sessionId) {
\tconst attribution = attributionHeaders();
\tconst reserved = new Set(Object.keys(attribution).map((name) => name.toLowerCase()));
\tconst resolved = Object.entries(headers ?? {}).filter(([name]) => !reserved.has(name.toLowerCase())).flatMap(([name, value]) => {
\t\tif (typeof value !== "string" || !value.includes("$sessionId")) return [[name, value]];
\t\tif (sessionId === void 0) return [];
\t\treturn [[name, value.replaceAll("$sessionId", sessionId)]];
\t});
\treturn {
\t\t...Object.fromEntries(resolved),
\t\t...attribution
\t};
}'''
s = s[:old_fn.start()] + new_fn + s[old_fn.end():]

old_call = 'headers: requestHeaders(profile.headers)'
new_call = 'headers: requestHeaders(profile.headers, options.sessionId === void 0 ? void 0 : String(options.sessionId))'
if old_call not in s:
    sys.exit('❌ 未找到调用点 requestHeaders(profile.headers)，请人工处理。')
s = s.replace(old_call, new_call, 1)

open(p, 'w', encoding='utf-8').write(s)
print('✅ 补丁已写入')
PY

node --check "$PKG" && echo "✅ 语法校验通过"
echo "请重启 DSH 使补丁生效。"
