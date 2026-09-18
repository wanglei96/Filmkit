---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-09-18 11:09
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务

- [2026-09-18] M2 RAW 端到端（镜头发现/设置页/控制层已交付） | 进行中 | project.md
- [2026-09-18] 对焦与镜头切换修复（v0.4.5 待真机复验） | 待复验 | handoffs/修复对焦黑屏与出包约定.memo.md
- [2026-09-18] 相机 UI 专项重设计 | 待启动 | handoffs/修复对焦黑屏与出包约定.memo.md

## 下一步

- 真机复验 v0.4.5：对焦圆圈套住点击点（重点四角）、AF 落点一致（focus.set 含 rot/sx/sy）、15/24/70 切换不黑屏
- 启动 UI 专项窗口：先 /recall 载入，设计定稿（参照 DAZZ/PhotonCamera）再重构 CameraScreen
- 回归 M2-3：RAW 采集（RAW_SENSOR 4080×3072）+ DngCreator 存 DNG，读设置页 dng_enabled 开关

## 最近快照

- [2026-09-18 11:09] handoffs/修复对焦黑屏与出包约定.memo.md — active
- [2026-09-18 10:20] handoffs/M1完成M2镜头与专业UI攻坚.memo.md — absorbed
