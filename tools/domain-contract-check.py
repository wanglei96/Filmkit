#!/usr/bin/env python3
"""域契约静态检查（重构第 1 期工具，对应 docs/refactor-architecture.md §3.3）。

做三件事：
  1. 纹理台账 —— 提取所有 makeTex 创建的 render target（名字 / 格式 / 尺寸表达式 / 行号）
  2. 域标签   —— 读取纹理创建处的 `@domain: Dn` 注解（重构期过渡手段）
  3. 复用告警 —— 统计每个纹理被哪些 pass 绑定；被 >=3 个 pass 使用的标为"域重载嫌疑"

为什么需要它：域审计（/tmp/refactor-audit/domain-audit.md）发现链路有 12 个域、
且 `fbo_scene_` 一个 target 承载 3 个域，而"用错域"历来只能靠真机拍片才发现。
本脚本把这一步提前到开发期。

用法：
    python3 tools/domain-contract-check.py [core/engine/src/render/FrameRenderer.cpp]

约定（第 1 期引入）：在 makeTex 行（或其上方 3 行内）的注释写 `@domain: D2`。
未标注的纹理列为 unlabeled —— 先补标签，再谈运行期断言。

退出码：0 = 无 unlabeled 且无重载嫌疑；1 = 存在其一（提醒补契约）。
"""
import collections
import re
import sys

DEFAULT = 'core/engine/src/render/FrameRenderer.cpp'


def parse(path):
    src = open(path, encoding='utf-8', errors='replace').read().splitlines()

    # ---- 1. 纹理台账 ----
    tex_re = re.compile(r'makeTex\(\s*&(\w+)\s*,\s*&(\w+)\s*,\s*(GL_\w+)\s*,\s*([^;]+?)\)\s*;')
    textures = {}
    for i, line in enumerate(src):
        m = tex_re.search(line)
        if not m:
            continue
        name, fbo, fmt, size = m.groups()
        dom = None
        for k in range(i, max(-1, i - 4), -1):          # 本行或上方 3 行找 @domain
            d = re.search(r'@domain:\s*(D\d+)', src[k])
            if d:
                dom = d.group(1)
                break
        textures[name] = dict(fbo=fbo, fmt=fmt, size=size.strip(), line=i + 1, domain=dom)

    # ---- 2. pass 函数 -> 绑定的纹理 ----
    pass_re = re.compile(r'^\s*(?:bool|void|HlRawResult|RcdResult|[A-Z]\w*)\s+FrameRenderer::(\w+)\s*\(')
    bind_re = re.compile(r'glBindTexture\(\s*GL_TEXTURE_2D\s*,\s*(\w+)\s*\)')
    funcs, cur = [], None
    for i, line in enumerate(src):
        m = pass_re.match(line)
        if m:
            if cur:
                funcs.append(cur)
            cur = dict(name=m.group(1), line=i + 1, binds=[])
        if cur:
            for b in bind_re.findall(line):
                if b not in cur['binds']:
                    cur['binds'].append(b)
    if cur:
        funcs.append(cur)

    # ---- 3. 逆索引 ----
    users = collections.defaultdict(list)
    for f in funcs:
        for b in f['binds']:
            users[b].append(f['name'])
    return textures, funcs, users


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    textures, funcs, users = parse(path)

    print(f'=== 纹理台账（{len(textures)} 个 render target）===')
    by_dom = collections.Counter(t['domain'] or 'unlabeled' for t in textures.values())
    for dom, n in sorted(by_dom.items()):
        print(f'  {dom:10s} {n}')
    unlabeled = [(n, t) for n, t in textures.items()
                 if not t['domain'] and users.get(n)]
    if unlabeled:
        print(f'\n  ⚠ 有 {len(unlabeled)} 个**在用**但未标 @domain 的纹理：')
        for n, t in sorted(unlabeled, key=lambda kv: -len(users.get(kv[0], [])))[:15]:
            print(f'      {n:22s} :{t["line"]:<5} 被 {len(users.get(n,[]))} 个 pass 使用  {t["fmt"]}')

    print(f'\n=== 域重载嫌疑（被 >=3 个 pass 绑定的纹理）===')
    hot = [(n, u) for n, u in users.items() if len(u) >= 3 and n in textures]
    if not hot:
        print('  无')
    for n, u in sorted(hot, key=lambda kv: -len(kv[1])):
        dom = textures[n]['domain'] or 'unlabeled'
        print(f'  {n:22s} ({dom}, :{textures[n]["line"]}) 被 {len(u)} 个 pass 使用：')
        print(f'      {", ".join(u)}')

    print(f'\n=== pass 数量 ===')
    print(f'  FrameRenderer::* 共 {len(funcs)} 个；其中绑定纹理的 '
          f'{sum(1 for f in funcs if f["binds"])} 个')

    bad = bool(unlabeled) or bool(hot)
    print(f'\n结论：{"需要补域契约" if bad else "域契约完整"}')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
