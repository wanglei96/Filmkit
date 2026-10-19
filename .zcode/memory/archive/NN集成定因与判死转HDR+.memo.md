---
summary: NN集成定因与判死转HDR+
created: 2026-10-02 15:05
updated: 2026-10-02 15:05
status: archived
---

# NN集成定因与判死转HDR+

## Summary

NN 降噪集成第五轮收线：253→257 逐层修口径（JNI 桥、官方 KSigma 量纲、训练口径
anchor、MFNR neff 折减、通道序），**nnGain 全部核对吻合但用户观感零改善**。定量定因
为"跨域模型不可直接用"：文字梯度 -24%、平坦区呈斑块状伪影（非白噪）。随后建成完整
微调管线并跑四次微调（监督 ×3 + Noise2Noise），**留出集全部过拟合**，判定瓶颈是数据量
（4 场景 vs 官方 3 万+ 对）。结论：NN 路线判死，转 HDR+ bracketing。

## Done

- **NN 集成五轮（v0.10.102–0.10.106，versionCode 253→257）**：
  - 253 `0.10.102-nnint`：NN pass 全链落地（pack→tile 推理→ramp 融合→unpack）、
    NnInferEngine 常驻线程、失败整张回退 P0。**JNI 桥从不初始化 → 全回退**
  - 254 `0.10.103-nnint2`：修 4 bug 后首出片，但发绿 + 4×3 网格 + 16px 点阵
  - 255 `0.10.104-nnint3`：官方口径（cvt_k×256 + BGGR 语义）；画面正常但细节全糊
  - 256 `0.10.105-nnint4`：`kNnAnchorS` 4.5e-4 → **1.0093e-3**（训练口径）+ `× neff`
    + clamp 上限 4→16；nnGain 181.9→963/1073 与理论逐位吻合
  - 257 `0.10.106-nnint5`：通道序对齐官方 (R,Gr,Gb,B)，四张 CFA 相位表重推并通过
    ph_sem↔ph_chmap 互逆校验
  - 提交：27923f7 / 0b9b0b2 / 77ef496 / cc6f18d / b59228c / 35448e5
- **真机 A/B 定量定因**（256/257，ISO3444/3660，neff=4）：
  - 文字区强边缘(梯度>30)占比 3.76% → 2.41%（**-36%**），梯度均值 -24%
  - 平坦墙面 nn-on 呈**斑块状结构**而 nn-off 是白噪；全图 4px 能量反而 +34%
  - JPG 5.66MB → 4.21MB（-26%）**不等于丢细节**，是白噪→结构化导致的熵降低
  - 排除项：tile 接缝（992/1984 位置能量与墙面中位一致）、顶部 2 行黑边（相机固有）
- **微调管线建成**（`tools/nn/`）：
  - `pmrid_torch.py`：PyTorch 版 Network，141 权重与 ONNX 1:1 同名映射
    （missing/unexpected 全 0），与 onnxruntime 等价 max abs **2.0e-6**；1.032M 参数；
    M1 MPS 推理 48ms / 训练一步 271ms
  - `multiframe_clean.py`：CFA 平面各自亚像素对齐（Gauss-Newton LK），位移误差
    **0.0115px**、6 帧噪声 ×3.22、异场景剔除（wn 绝对判据）；`--interp` 四核 +
    `--ref-exclude`
  - `finetune_pmrid.py`：`--pairs-dir` + **per-scene cvt_k**（5 场景 S 差 6 倍）
  - `eval_finetune.py` / `verify_pairs.py` / `make_n2n_pairs.sh`
  - 提交：f34c0f3 / 71d87c1 / 5b4d68c / f6592fe
- **真机数据采集与处理**：93 帧 / 5 场景（ISO 216/771/892/1709/4781，每场景 18–20 帧），
  噪声模型从 DNG tag 51041 NoiseProfile 取（= Camera2 SENSOR_NOISE_PROFILE，与引擎
  `nzSrc:"cam2"` 同源）；场景 5 报废（位移 103–114px + wn 0.36）

## Decisions

- [2026-10-02] **cvt_k = S_train·neff/S_input，S_train = 1.0093e-3**（= 官方
  run_benchmark.py K(1600)/V，img01 域）。anchor 必须是**训练数据的噪声水平**，不是
  本机传感器自己的 S(ISO1600)=4.5e-4 —— 后者让输入噪声只剩训练水平的 1/2.24。
- [2026-10-02] **cvt_k 必须计入 MFNR 的 ÷neff**：NN 输入是 tex_fused_（4 帧融合，
  方差已折减），P0/P4 系数早已 ÷neff，唯独 NN pass 漏了 → 又差 4×，两层合计 ~9×。
- [2026-10-02] **通道序 = 官方语义 (R,Gr,Gb,B)**：官方 `bggr2rggb`(rot180) 只是
  "把 BGGR 摆成 RGGB"的手段，等价于不翻转、直接按语义取相位（逐通道核对一致）；
  打包图空间朝向仍不翻转（官方翻转）——离线实测仅值 ~1%，按最小改动暂留。
- [2026-10-02] **NN 路线判死（跨域模型不可直接用）**：PMRID 官方权重在 SIDD 上训练，
  与 X300 域不匹配；口径修正只让"输入域理论上正确"，改不了网络内部的陌生数据先验。
  实测零收益且负收益（解析力 -24%、斑块伪影），**不如 P0**。
- [2026-10-02] **微调需数据量，4 场景不够**：四次微调留出集全过拟合
  （+12.0% / +9.9% / +11.7% / N2N +5.06%），官方用 3 万+ 对，差 3~4 个数量级。
- [2026-10-02] **下一步转 HDR+ bracketing**：后处理造不出被噪声淹没的信息，FX 靠
  +3.6EV 进光赢我们属采集端差距，降噪算法补不回来。

## Pitfalls

- **多帧平均做训练目标 = "更干净"与"更平滑"不可分**：亚像素插值必然低通，网络把
  clean 的"干净"和"软"一起学了 —— 三次监督微调 ret4 全降（0.919→0.846 /
  0.920→0.835 / 0.896→0.826），三联图目视"更软"。**有 clean 参照就逃不掉这个耦合**。
- **换保高频插值核救不了**：bicubic 让 clean 的 4px 高频 +13.3%（96% 是真实纹理），
  但同核训练时 noisy/clean 同涨 ⇒ 网络要补的高频差不变。真正杠杆是
  `burst_mix`（双线性 noisy + bicubic clean，高频差 0→+13.3%），**但实测 ret4 仍降**。
- **Noise2Noise 是方向最正的**（过拟合 +5.06%，为监督路线一半），但 4 场景同样不够。
- **离线指标与用户观感会系统性不一致**：我一直评"网络输出"，用户看的是"成片"
  （中间隔着去马赛克/色彩/色调/锐化）。锐化会放大网络残余与伪影 —— 建立"离线指标
  ↔ 观感"的对应必须用成片层面的同帧 A/B（nn_ab）。
- **art4（输出/输入）判据受输入噪声干扰**：输入含噪时 art4>1 只说明"降噪不足"，
  不等于引入伪影。正解是看相对参照的 ret2/ret4/ret8。
- **ret4 口径（PIL blur2−blur4）中心在 mosaic ≈36px**，对插值核几乎不敏感
  （0.966→0.983）；严格的 4px（lag-1）口径下 bilinear 衰减 20.4%。用 ret4 评
  "4px 尺度"会低估核差异。
- **`timeout` 命令 macOS 不存在**（本窗口踩两次）；shell 管道吞退出码的老坑仍在。
- **构建命令应先查记忆库**：`JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew
  assembleDebug`（facts.md 早有记载，本窗口先撞了一次 Java 8 报错）。
- **native 改动必须核对进包**：比对 APK 内 `libfilmengine.so` 的 md5（257 与 256
  不同），并看构建日志有无 `buildFilmEngine` 重编 FrameRenderer.cpp.o。

## Next

1. **HDR+ bracketing 评估**（主方向）：1×/4×/16× 曝光阶梯实拍，参考实现 hdr-plus-swift；
   先做最小可用版（采集阶梯 + 现有对齐/融合兜底）
2. NN 代码与开关**保留不动**（`nn_dn` 默认关，本来就是）：数据充足时可直接捡起微调管线
3. 可选：加 "NN 强度" 滑杆（`out = α·nn + (1−α)·原图`），让真机自己找权衡点（~30 分钟）
4. 场景 5 若需复用：重拍（手机固定，位移 <10px）
5. 验收口径照旧：出包前 `tools/check-diag-fields.py`，分析日志前 `tools/check-log-json.py`

## Refs

- **版本**：253 `0.10.102-nnint` → 254 `0.10.103-nnint2` → 255 `0.10.104-nnint3`
  → 256 `0.10.105-nnint4` → **257 `0.10.106-nnint5`（当前包，NN 默认关）**
- **提交**：b59228c（anchor+neff）/ 35448e5（通道序）/ 43aec08（256 定因）
  / f34c0f3（PyTorch 移植）/ 71d87c1（burst 管线）/ 5b4d68c（重采样+N2N）
  / f6592fe（微调产物）
- **代码**：`core/engine/src/render/FrameRenderer.{h,cpp}`（runNnPass、nnSemTables、
  kNnAnchorS=1.0093e-3、cvt_k×neff）；`app/.../nn/NnInferEngine.kt`
- **工具**：`tools/nn/{pmrid_torch,finetune_pmrid,multiframe_clean,make_burst_pairs,
  make_n2n_pairs,make_burst_meta,verify_pairs,eval_finetune,compare_burst,diag_*}.py`
- **数据**：`test_temp/dng/`（93 帧 5 场景）、`test_temp/burst{,_hi,_mix,_n2n}/`、
  `test_temp/burst_hi_report.md`
- **外部**：github.com/MegEngine/PMRID（只有推理代码，**无训练脚本**）；SIDD 训练口径
  K_coeff=[0.0005995267, 0.00868861]、anchor=1600、V=959
- **关键实测**：nnGain 963.5(ISO3903)/1018.3(ISO3660)/1072.6(ISO3444) 全与公式吻合；
  P0 降噪 ÷3.35 vs NN ÷10.6（同帧 A/B）
