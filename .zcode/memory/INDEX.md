---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-02 12:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-02 晚：NN 集成口径定案，0.10.104 待真机复测）

- [2026-10-02] **NN 降噪集成** | 最高 | 0.10.104-nnint3 (255) 已修官方口径
  （cvt_k×256 + BGGR 语义，离线恒等 0.997）：真机复测（发绿/网格应消、nnGain
  核对）→ nn_ab 正式 A/B → 验收收线
- [2026-10-02] **X300 噪声模型 finetune PMRID** | 高 | σ²=A·x+B 合成训练对微调，
  纹理保留率再上一档；兼治极端暗部 R/B 相位向 G 收敛的残余
- [2026-10-02] **HDR+ bracketing 评估** | 高 | 1×/4×/16× 曝光阶梯实拍（超越
  FX 的进光杠杆）；hdr-plus-swift 为参考实现
- [2026-10-01] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、成像会话摘要、
  golden image 接 CI（开发计划 §11），与上面并行
- [2026-10-01] **更强 NN 模型评估** | 降级 | GPU 裕量 5–10×,AP-BSN/CPR <2s

## 下一步

- **真机复测 255**：暗光 ISO3900 + 白天 ≤1600 各拍一张；核对 nnGain（~107 /
  ~256）、发绿/网格/点阵消失；DNG 回传做离线同刻对比
- **nn_ab 正式 A/B** → texture-fidelity/ab-100pct 对比 4px 纹理（锚点 PC 2.24 /
  FX 2.19 / 我们 P0 1.47）；R/B 暗部抬亮观感裁决
- **finetune 训练管线**：X300 噪声模型合成训练对 → 云 GPU 微调 PMRID
- 留观：128³ 用户 LUT 真机纹理验证；`mfnr.mode=2 trigger=false` 成因未查
- 验收口径照旧：出包前 `tools/check-diag-fields.py`，分析日志前
  `tools/check-log-json.py`，拍照线程 `tools/still-thread-verify.py`

## 最近快照

- [2026-10-02 12:20] handoffs/NN集成口径定案离线恒等.memo.md — active
- [2026-10-02 10:05] handoffs/NN降噪spike达标与中频归因定案.memo.md — absorbed
- [2026-10-01 22:05] handoffs/M4无损LUT导入导出与预览降载收线.memo.md — absorbed
- [2026-10-01 13:05] handoffs/拍照独立线程落地与连拍崩溃定案.memo.md — absorbed
- [2026-09-30 21:50] handoffs/对齐精度亚像素落地与快门异步化.memo.md — absorbed
