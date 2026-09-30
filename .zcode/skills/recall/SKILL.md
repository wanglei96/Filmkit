---
name: recall
description: DSH 客户端里 `/recall` 的入口(仅用户可调用)——从记忆库 `.zcode/memory/` 按时间列出历史交接快照,用户选择后载入并继续未完成的任务。流程正文委托给 meow-recall 技能。
disable-model-invocation: true
---

# /recall(DSH 入口)

本技能只是命令入口,流程正文只有一份,在 `.zcode/skills/meow-recall/SKILL.md`(ZCode 与 DSH 共用)。

1. 立即读取 `.zcode/skills/meow-recall/SKILL.md`。
2. 严格按其中的执行流程与边界执行;用户参数(快照序号 / `latest`)原样带过去。

为什么存在:DSH 把技能名注册成 `/` 命令,而 ZCode 的 `/recall` 来自命令文件 `.zcode/commands/recall.md`,DSH 不读该目录,所以补一个同名技能。`disable-model-invocation: true` 让它只出现在用户的命令菜单里,不占模型的技能目录。
