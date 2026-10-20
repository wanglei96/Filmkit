# 自动曝光期 B：bracket 采集 + 饱和感知融合（设计）

> 2026-10-20 立项。前置：0.11.61–0.11.64 已落地「渲染端自动曝光（本张 P50/P99.5）」
> 与 P99.5 判据，真机验证两张同场景一致；剩余差距是**单帧动态范围不够**
> （`aeEv` 仍压 1.1 EV、`mean 45` vs PC `89`）。
>
> 本设计解决的是物理问题，不是调参数：**用真实的短曝光帧拿回高光信息**，而不是
> 让固定曲线在"压暗"和"顶白"之间二选一。

---

## 0. 目标（可测量）

| 维度 | 判据 | 当前基线（0.11.64，台灯+窗） |
|---|---|---|
| 高光不过曝 | 成片 `≥240` 占比 ≤ 0.5%；RAW 饱和像素 ≤ 0.5% | `hi240 0.00%`（靠压暗换来） |
| 暗部有细节 | P5 区域 σ 不高于当前 1.1×；`confMed ≥ 0.95` | `confMed 0.998` |
| 亮度接近肉眼 | 中灰锚定 18.45%，`|aeMid| ≤ 0.3 EV` | `aeMid +1.41`（被高光压住） |
| 同场景一致性 | 两张 `aeEv` 差 ≤ 0.1 EV | 0.00（已达标） |
| 不退化 | 融合耗时 ≤ 当前 1.5×；`neff ≥ 3.5` | `neff 4.0` |

目标态：`aeEv ≈ 0`（不再需要压暗），`mean` 回到 70~90 量级，`hi240 ≤ 0.5%`。

---

## 1. 物理依据

- **单帧上限**：X300 RAW 动态范围约 12–13 EV；「窗边台灯 + 室内」15–18 EV。单帧曝光
  只能取一个点，高光与暗部不可兼得（0.11.61–0.11.64 的 `aeEv=-1.1` 就是这笔账）。
- **多曝光是唯一出路**：HDR+（Hasinoff 2016）§3 用不同曝光帧做鲁棒合并，§4"曝光设到
  刚好不烧高光"；Night Sight 的短帧专门保高光。PC/MGC 用短帧 + HDRNet 重分配，我们
  用短帧 + 固定曲线，前者是生成式、后者是记录式 —— 后者更符合"真实记录肉眼所见"。
- **不用 NN 的理由**：MGC 的三个 `*.tflite` 是逆向的 Google 专有资产，不能分发；
  而决定"高光是否可救"的是**真实短帧的光子**，不是预测模型。

---

## 2. 现状架构（改动落点）

```
startBurst (CameraController.kt:1984)
  └─ captureBurst 4 帧同参（AE_OFF + 手动 ISO/曝光）
       └─ onImageAvailable → burstImages/burstBuffers → tryProcessBurst
            └─ processRawBurst → submitRawBurst(4 buffers, strides, ..., refNoiseScale)
                 └─ FrameRenderer::submitRawBurst
                      ├─ Align：3 张 alt 对齐到 ref（CPU 位移场，mosaic 域亚像素）
                      ├─ 融合二选一（[55] fuse）：
                      │    · kFsMfnrFuse（FrameRenderer.cpp:2304）置信度加权平均
                      │    · kFsMfnrFft（:2884）HDR+ §5 式 6/7 pairwise DFT + neff
                      └─ tex_fused_ → P0/P4 → … → passFilm
```

关键事实：
- `MFNR_FRAMES = 4`，两两同曝光（v0.10.1 起 ref 可压短，但**曝光量守恒**）。
- 两条融合路径都**假设所有帧同曝光、同噪声水平**。
- 渲染端 AE（`FrameRenderer::passAutoExposure`）已经能吃到本张统计，bracket 只需让
  `p_hi995` 落回白点内，`aeEv` 自然归零，无需再改 AE。

---

## 3. 方案

### 3.1 采集（`CameraController.startBurst`）

```
帧 0：t_short = t_long / 2^k    （k = 2，先固定；饱和预算成熟后再动态）
帧 1-3：t_long（= 现有 alt 帧曝光）
ISO：四帧相同（保持噪声模型有效；只改曝光时间）
```

- 总曝光时间 `= 3·t_long + t_long/4 = 3.25·t_long` < 现在 `4·t_long` ⇒ 运动预算更宽。
- `k=2` 的依据：台灯场景 `aeP995 = 2.03`（超白点），−2 EV 后短帧 `P99.5 ≈ 0.51`，
  白点内有余量；且 2 EV 是 gainmap `[0.25,4.0]` 的同一量级，便于统一口径。
- ~~短帧放在**帧 0**：它同时是参考帧（最短曝光 = 最锐），对齐/mismatch 天然受益。~~
  **2026-10-20 真机实测推翻（0.11.67 → 0.11.68）**：短帧归一后噪声 ×ratio，而参考帧
  在两条融合路径里都是基准（DFT 式 6/7 的 `r0`、mismatch 的权重恒 1）⇒ 暗部被短帧
  噪声主导（实测 `neff 4.0 / confMed 0.999` 但暗部彩噪明显，`a≈0.11` 时输出仍有
  ~1/3 来自短帧，暗部方差 ×1.83）。改为**短帧放最后一帧、长帧作参考帧**：暗部
  `d²` 大 ⇒ 向参考帧（长帧）收缩；高光处短帧 SNR 高 ⇒ `d²` 小 ⇒ 采短帧。物理上
  与 HDR+ 原版一致（参考帧取最高 SNR 帧，而非最锐帧）。DNG 也随之回到帧 0（长帧）。
  现有 ref 压短逻辑（`MFNR_REF_MAX_EXPOSURE_NS`）在 bracket 开启时停用。
- 参数随 `submitRawBurst` 传入 native：`shortFrameIndex=0`、`shortExposureRatio=4.0`。
  用**显式传参**而不是从 requests 反推（HAL 可能 clamp，必须用实际值：从
  `CaptureResult` 读回四帧的 `SENSOR_EXPOSURE_TIME`，日志已有 `mfnr.burst_actual`）。

### 3.2 对齐（`Align`）

- 对齐前把短帧**乘 ratio**（4×）归一到长帧域，再走现有 SAD/位移场求解。
  - 不归一的后果：SAD 残差被 4× 亮度差淹没，`confMed` 会从 0.998 掉到 0（2026-09-29
    的 alt 帧不守恒事故同型）。
- 归一化的实现位置：`submitRawBurst` 上传前对 buffer 做一次标量缩放（CPU，12MP×2B
  ≈ 24MB 遍历，~10ms）；或对齐 shader 加 `uRefScale`。取后者（GPU，无 CPU 拷贝）。
- 归一后短帧的噪声也 ×4 ⇒ 融合阶段的 σ 必须按 ratio 缩放（见 3.3）。

### 3.3 融合

#### 路径 A：`kFsMfnrFuse`（加权平均，A/B 通路）

新增 uniform：

```glsl
uniform vec4 uFrameScale;   // 每帧曝光归一（短帧 = 4.0，其余 1.0）
uniform vec4 uFrameSat;     // 每帧饱和阈值（默认 0.98）
```

权重（在现有 mismatch 权重 `wv` 上乘）：

```glsl
// ① 饱和惩罚：本帧在该像素已饱和 ⇒ 权重 0（信息已丢）
w *= 1.0 - smoothstep(uFrameSat[i] * 0.92, uFrameSat[i], lin_i);

// ② 短帧暗部惩罚：短帧×4 后暗部噪声 4×，SNR 低 ⇒ 权重压低
//    只在归一化后的值低于长帧噪声底 3σ 时生效
if (is_short) w *= smoothstep(0.02, 0.08, lin_i);
```

输出 = `Σ w_i·lin_i / Σ w_i`。所有帧同域（长帧域），无需后处理。

#### 路径 B：`kFsMfnrFft`（默认路径）

- 输入数组上传时乘 `uFrameScale`（每帧一个标量），与路径 A 同口径。
- **σ 缩放**：`kFsMfnrFft` 用 `σ² = A·RMS + B`（per-plane）。短帧归一后噪声 ×ratio：
  - 正确做法：`σ²_short = ratio² · (A·RMS_short + B)`，其中 `RMS_short` 是短帧原始
    （未归一）的 tile RMS。
  - 实现：内核按帧索引查 `uPlaneA/uPlaneB` 时乘 `ratio²`；或在归一化时同时把
    `uPlaneA/B` 乘 `ratio²`（更省寄存器，只需一个 per-frame 系数）。
- **收缩**：式 6/7 形式不变（`Az = |Dz|²/(|Dz|²+cσ²)`），只是 σ 按帧缩放。
  - 物理含义：短帧在某像素的"可信度"由它的噪声决定；高光处它 SNR 高、权重自然大。
- **饱和**：收缩前把短帧饱和像素的 `|Dz|²` 视为 0（等价权重 0），避免把裁切值当信号。

#### 两条路径的共用约束

- `neff`（per-tile 有效帧数）语义不变：短帧参与时 `neff` 可以 >4 的等效吗？不会，
  `neff ∈ [1,4]`；短帧只在部分像素生效，`neff` 仍按现有公式。
- 融合输出仍是 **RAW 线性域**，后续 P0/P4/D-1/去马赛克/filmic 零改动。

### 3.4 渲染

- 不改曲线、不改 AE 公式。bracket 让 `p_hi995` 回到白点内 ⇒ `aeEv → 0`，
  `aeMid` 与 `aeHl` 不再冲突。
- `gainmap` 自动受益：SDR base 不再被压暗 1 EV，gainmap 的 `[0.25,4.0]` 余量更大。

### 3.5 回退（照片优先红线）

| 失败条件 | 行为 |
|---|---|
| 短帧缺失（`burstImageCount < 4` 或短帧超时） | 退回 4 帧同曝光（现有行为），日志 `mfnr.bracket_fallback` |
| 短帧对齐 `confMed < 0.9` | 短帧权重全局置 0（等于不用），退回同曝光 |
| ratio 读回异常（HAL clamp 到 1.0） | 同上 |
| 融合后 `neff < 2.5` | 记 WARN，仍出片（不丢照片） |

---

## 4. 参数与标定

| 参数 | 初值 | 标定方法 |
|---|---|---|
| `k`（短帧欠曝 EV） | 2.0 | 台灯/逆光/夜景三场景，扫 `k ∈ {1.5, 2, 2.5, 3}`，取 `hi240 ≤ 0.5%` 且 `mean` 最大的点 |
| 短帧暗部门限（0.02/0.08） | 按 3σ 噪声底推 | 用 `raw.noise` 的 A/B 与 ISO 50 算 σ，取 `lin = 3σ` 附近 |
| 饱和阈值 | 0.98 | 与 `kWhitePointEv` 同源；HAL 白电平 1023 |
| σ 缩放系数 | `ratio²` | 离线用同场景 RAW 对比短帧/长帧 tile RMS，验证 ratio² 关系 |

---

## 5. 验收判据（真机，同场景 ×3 组）

1. **高光**：`photo.stats.hi240 ≤ 0.5%`，`aeEv ∈ [-0.3, +0.1]`，`clipB` 不高于当前 1.5×。
2. **暗部**：P5 σ ≤ 当前 1.1×（同场景同 ISO），`confMed ≥ 0.95`，`neff ≥ 3.5`。
3. **一致性**：同场景两张 `aeEv` 差 ≤ 0.1 EV，`mean` 差 ≤ 3。
4. **回归**：`[55] fuse=0`（加权路径）与 `fuse=1`（DFT）都要过；关闭 bracket
   （prefs）时输出与 0.11.64 **逐字节一致**（CRC32 或 PNG 对比）。
5. **性能**：`render.burst_done ms` ≤ 当前 1.5×（当前 6128ms/3030ms）。

---

## 6. 风险清单

| 风险 | 缓解 |
|---|---|
| 短帧暗部噪声拉低融合质量 | 暗部权重门限（3.3 ②）+ 验收判据 2 |
| 运动场景短帧与长帧内容错位 | 短帧作参考帧（3.1），mismatch 自动回退；判据 `confMed` |
| 频闪/闪光灯下各帧亮度不一致 | 闪光灯场景禁用 bracket（检测 `CONTROL_AE_MODE` 有 flash） |
| 4 帧总曝光变短导致暗部光子减少 | 长帧 3 张不变；短帧只占 1/4 时间，暗部由长帧承担 |
| DFT 内核寄存器压力（多一个 per-frame 系数） | 用 uniform 数组，不改线程映射 |
| HAL 对短帧曝光 clamp | 以 `CaptureResult` 实际值为准（`mfnr.burst_actual` 已有），ratio 用实际值 |

---

## 7. 实施步骤（每步都可独立出包验证）

1. **采集 + 传参 + 日志**（不改融合）：短帧采集、`shortRatio` 透传、日志
   `bracket.short/sec/ratio`；融合仍用 4 帧同曝光（短帧仅记录）。验证采集链路与回退。
2. **加权路径 A**：`uFrameScale/uFrameSat` + 权重；`[55]=0` 下真机标定 `k`。
3. **DFT 路径 B**：归一 + σ 缩放 + 饱和处理；`[55]=1` 下同判据。
4. **默认开启 + 判据回归**：`[55]` 两条路径全过，关 bracket 逐字节一致。

---

## 8. 不做的事（明确边界）

- 不做 HDRNet / 任何 NN 重分配（模型不可得，且与"真实记录"冲突）。
- 不改 filmic/sigmoid 曲线（曲线已与 PC 一致，问题不在曲线）。
- 不做多帧不同 ISO（HAL 的 ISO 范围窄，且噪声模型要重标）。
- 不改预览链（预览走 OES，域不同；WYSIWYG 是另一条线）。
