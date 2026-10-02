---
summary: 记忆库索引,活跃任务与最近快照指针
created: 2026-09-18 10:20
updated: 2026-10-02 12:20
status: active
---

# 记忆索引

> 本文件是记忆库唯一入口,≤80 行。只放指针与状态,细节在分层文件与快照里。

## 活跃任务（2026-10-02 晚：NN 判死，转 HDR+）

- [2026-10-02] **HDR+ bracketing 评估** | 最高 | 1×/4×/16× 曝光阶梯实拍（超越 FX 的
  进光杠杆，**NN 判死后的算法层主线**）；hdr-plus-swift 为参考实现
- [2026-10-02] **M5 诊断与基准** | 待做 | 逐 pass GPU 计时、成像会话摘要、golden
  image 接 CI（开发计划 §11）
- [2026-10-02] **NN 微调（暂缓）** | 降级 | 管线全通（PyTorch 移植/对齐/N2N/评估）
  但需几十场景数据；4 场景实测全部过拟合。代码与开关保留（`nn_dn` 默认关）
- [2026-10-01] **更强 NN 模型评估** | 降级 | GPU 裕量 5–10×,AP-BSN/CPR <2s
- [2026-10-01] **可选：NN 强度滑杆** | 待定 | `out = α·nn + (1−α)·原图`（~30 分钟）

## 下一步

- **HDR+ bracketing 最小可用版**：1×/4×/16× 曝光阶梯采集 + 现有对齐/融合兜底
- 场景 5 若需复用则重拍（手机固定、位移 <10px）；其余 4 场景数据已就绪
- 验收口径照旧：出包前 `tools/check-diag-fields.py`，分析日志前
  `tools/check-log-json.py`，拍照线程 `tools/still-thread-verify.py`

## 最近快照

- [2026-10-02 15:05] handoffs/NN集成定因与判死转HDR+.memo.md — active
- [2026-10-02 12:20] handoffs/NN集成口径定案离线恒等.memo.md — absorbed
- [2026-10-02 10:05] handoffs/NN降噪spike达标与中频归因定案.memo.md — absorbed
- [2026-10-01 22:05] handoffs/M4无损LUT导入导出与预览降载收线.memo.md — absorbed
- [2026-10-01 13:05] handoffs/拍照独立线程落地与连拍崩溃定案.memo.md — absorbed
