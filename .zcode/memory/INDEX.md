---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-11-15 22:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-11-15 22:20）

- [2026-11-15 22:20] **0.14.6 出片回归已修复，性能回归待测** | 进行中 | 用户验收 JPEG 正常、DNG 不再误存。
  0.14.5 的无 JPEG 根因 = `GpuTimer` 手写 `GL_GPU_DISJOINT_EXT=0x8FBD`（标准 `0x8FBB`）产生
  `GL_INVALID_ENUM`，被新硬失败检查判死；意外 DNG = 遗留 `burst_dump` 哨兵绕过开关。两处均已修。
  用户新反馈拍照变慢 + 预览卡，尚未测量；先取 p0Ms/dirMs/totalMs/preview_gap 与 0.14.0 基线对比，
  禁止在无剖面时重写条带化。详见 `docs/reports/capture-fix-0.14.6.md` 与
  `handoffs/高光融合落地与出片回归.memo.md`。
- [2026-11-15] **日光待验高光融合外观** | 待条件 | 极高光品红/云朵灰斑/花瓣/栏杆/灯边/运动重影；
  CameraWhite 是输出白界政策、保守 cov 非精确噪声，风险见 `docs/reports/highlight-fusion-0.14.5.md`。
- [2026-11-12] **Spektrafilm 引擎 + 预设标定** | 待做 | slot=2、场景线性输入、65³ LUT；
  先 Portra 400，再 Ektar / Vision3 500T / Velvia
- [2026-11-11] **理光目标曲线重标** | 待做 | 用新 LUT 样片重跑 `recipe-fit.py`，把摘掉的
  `BuiltinPresetCurveTest` 理光断言挂回去
- [2026-11-11] **绿植/绿段对齐** | 待量 | 同一张图分别量绿植与天空（掩码两次取错区域）

## 下一步

0. **性能回归先测后改**：对比 0.14.0 同场景的 `p0Ms/dirMs/gMs/hlMs/dnMs/fuseMs/totalMs` 与
   `preview_arrive_gap`/fps；重点查新增全分辨率 RGBA32F noise/cov、PC3D 三 attachment、
   逐 pass GL 同步、新 pass 是否绕过 `drawStriped`、LOW 优先级 context 是否仍生效。
1. 日光验收高光外观；应用单测仍有 1 个既有失败 `Pv3StaleFrameTest`（`under` 层归属校验）。
2. 旧账：编辑器整页路由；Spektrafilm；理光曲线重标；绿植/绿段；AE 高光保护效果。

## 最近快照

- [2026-11-15 22:20] handoffs/高光融合落地与出片回归.memo.md — active
- [2026-11-15 14:20] handoffs/HDR灰带定位到高光inpaint并0.14.0发版.memo.md — absorbed
- [2026-11-14 22:55] handoffs/HDR灰带参考源与短帧归一.memo.md — absorbed
- [2026-11-14 18:42] handoffs/预设覆盖层、删除切图与HDR灰带.memo.md — absorbed
- [2026-11-13 15:40] handoffs/HDR-gainmap与高光判据归因.memo.md — absorbed

> **本次交接（s2 × d3，2026-11-15 22:20）**：新建快照 1 份（高光融合落地与出片回归，113 行）；
> 全量吸收进 project/facts/decisions/lessons；旧 active「HDR灰带定位到高光inpaint并0.14.0发版」
> 标 absorbed；无超 14 天快照需归档（最早 11-10，均未过期）。
> 版本事实更新为 0.14.6(803)；新增 7 条决策、7 条教训，重点：真实 host 出片才是交付判据。
