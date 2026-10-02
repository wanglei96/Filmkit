---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-02 22:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-02 深夜）

- [2026-10-02] **超广角畸变校正** | 最高 | 桶形畸变未校正（实测 5.7%，RAW 流 HAL 不做
  几何校正）；先探测 `DISTORTION_CORRECTION_MODE` + `LENS_DISTORTION`，不行则引擎加 GPU
  自校正 pass（去马赛克前、逐 CFA 平面采样保相位）。**需拍板**：校正后裁边的视场损失 5~10%
- [2026-10-02] **帧数档位 4→8** | 高 | SNR ∝ √(N×T)；用户提分档（日间 2-3 / 较暗 4-8 /
  很暗 8-16）；前提 = 流式对齐累加（否则 8×25MB direct buffer 撞 ART OOM）+ 处理时间降载
  （当前 4 帧渲染已 4.3s）
- [2026-10-02] **exp6 真机复验** | 待做 | 主摄应显示 `src:"builtin"`；盘沿 σY 应从 4.48
  落到 1~2 量级
- [2026-10-02] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、成像会话摘要、golden image
  接 CI（开发计划 §11）
- [2026-10-02] **NN 微调（暂缓）** | 降级 | 管线全通（PyTorch 移植/对齐/N2N/评估）但需
  几十场景数据；代码与开关保留（`nn_dn` 默认关）

## 下一步

1. **超广角畸变**：先做能力探测（一次编译看日志）→ 定 HAL 请求还是 GPU 自校正
2. **exp6 复验**：拍主摄看 `camera.raw.noise` 的 `src` + 盘沿 σY（降噪调到 0.45 测最严格）
3. **帧数档位**：先做流式累加架构，再上 8 帧；验收口径照旧（出包前
   `tools/check-diag-fields.py`、分析日志前 `tools/check-log-json.py`）

## 最近快照

- [2026-10-02 22:20] handoffs/曝光决策落地与亮部降噪修正.memo.md — active
- [2026-10-02 15:05] handoffs/NN集成定因与判死转HDR+.memo.md — absorbed
- [2026-10-02 12:20] handoffs/NN集成口径定案离线恒等.memo.md — absorbed
- [2026-10-02 10:05] handoffs/NN降噪spike达标与中频归因定案.memo.md — absorbed
- [2026-10-01 22:05] handoffs/M4无损LUT导入导出与预览降载收线.memo.md — absorbed
