# 降噪强度档的 σ 语义修正（v0.10.108-exp5 · 2026-10-02）

## 1. 现象（用户目视 + 定量）

三图对比（Filmix / 我们 / PhotonCamera，同一客厅场景，19:21–19:23 拍摄），
**只有我们的盘子边缘（白瓷，Y≈211–224）有细密颗粒**：

| 盘沿亮白段（Y≈224） | σY | 相对我们 |
|---|---|---|
| **我们** | **4.48** | — |
| Filmix | 1.72 | 我们 2.6× |
| PC | 0.92 | 我们 4.9× |

对照中间调（茶几木面 Y≈162）：我们 **1.06（最好）**，Filmix 2.67，PC 1.12。

**亮部/中间调 σY 比：我们 4.2×（↑）** vs Filmix 0.64× / PC 0.82×（↓）。
后者才符合物理（OETF 在亮部压缩噪声）⇒ 我们的链在亮部"加了东西"。

> 注：更早一版分析里"碗内壁有噪点"是**误判**——那里 lag-1 自相关 0.939 ≈ Filmix 0.938，
> 是真实纹理（碗内壁污渍），我们保留得更好。**盘沿**才是真问题。

## 2. 根因（`FrameRenderer::denoiseParams`）

```cpp
const float k_p0 = strength;
out->p0a[c] = wb.S[c] * k_p0 * k_p0;   // ← σ 被强度档平方缩放
```

强度滑杆被当成 **σ 乘子**。σ 报小 ⇒ bilateral 的 `d = (c−c0)/σ` 被放大 ⇒
`w = exp(−0.5d²)` **指数级塌掉** ⇒ 降噪不是"少降一半"而是**近似归零**。

**噪声越大的地方越惨**：散粒噪声 ∝ √信号 ⇒ 亮部噪声最大 ⇒ 受害最重。

### 真机铁证（`FILMKIT_20261002-192226.jpg`，19:22，完整日志第 6448 行）

```
dn = 0.45   kP0 = 0.45
p0A = [3.53e-5, 9.03e-6, 3.18e-5]   ← 引擎以为的 A
nzS = [3.58e-4, 1.78e-4, 3.58e-4]   ← 噪声模型真实 A
  ⇒ p0A/nzS = 0.099 ⇒ σ 只有真值的 0.31×
nzPreMed 0.001792 → nzPostMed 0.000888   （中位降 2.0×）
nzPreP90 0.006035 → nzPostP90 0.005806   （最噪的 10% 几乎没降）✗
```

**P90 那行是决定性证据**：噪声最大的 10%（= 亮部）根本没被降噪。

## 3. 修正

σ **恒为真实噪声**，强度分层承担：

- **k > 1**：作 σ 乘子（σ 放大 ⇒ 权重曲线更宽容 ⇒ 更激进的降噪）
- **k < 1**：作输出混合 `out = mix(orig, denoised, k)`（线性、各亮度段一致）

```cpp
const float sg_p0 = std::max(1.0f, strength);      // σ 乘子
const float mix_k = std::min(1.0f, strength);      // 输出混合
out->p0a[c] = wb.S[c] * sg_p0 * sg_p0;
out->mix_p0  = mix_k;
```

shader 侧（4 个 pass：P0 / P0-fused / P0-fused-ts / P4）：

```glsl
uniform float uMix;
v = mix(c0, acc / wsum, uMix);      // P4 是对色差 cd0 做 mix，Y 不变
```

**改动文件**：`FrameRenderer.h`（`NoiseCoef` + 4 个 uniform 结构）、
`FrameRenderer.cpp`（`denoiseParams` + 4 个 shader + 4×`glGetUniformLocation` + 4×`glUniform1f`）。

**含义变化**：日志里 `p0A/p4A` 不再含 k²（k<1 时等于真实模型值）—— 旧分析里
`p0A/nzS = 0.099` 这种比值在新版应回到 ≈(WB 因子) 量级。

## 4. 验证

* `tools/validate-shaders.sh` 全部通过 —— 过程中**抓到并修正**了 P0FusedTs 里中心值
  变量名是 `ref` 而非 `c0` 的错误（正是该脚本存在的意义，v0.5.13 事故同类）
* native 重编已核对：APK 内 `libfilmengine.so` md5 `529384d5…` → `0e6a5d62…`（变了）
* 待真机：同场景降噪 0.45 拍一张，看盘沿 σY 是否落到 Filmix/PC 量级，
  以及 `nzPostP90` 是否明显低于 `nzPreP90`

## 5. 三摄噪声标定：改用 GCam 生态模型（同版接入）

用户提供三份 GCam 格式标定，已内置为 X300 三摄标定（`app/src/main/assets/noise_models/`）：

| 镜头 | 文件 | 传感器 | A[0]（shot 斜率） | dg_ref | 来源 |
|---|---|---|---|---|---|
| 主摄 23mm | `x300_23mm.c` | 三星 HPB | 2.72e-7 | 6400 | **100 张实测**（暗场 50 + 亮场 50，相邻帧差法） |
| 超广 15mm | `x300_15mm.c` | 三星 JN1 | 3.07e-7 | 1600 | 同传感器借用（NubiaZ50Ultra_JN1_2_new）⚠️ 非本机实测 |
| 长焦 70mm | `x300_70mm.c` | 索尼 LYT602 | 1.05e-6 | 3200 | 社区标定 |

**为什么换**：主摄此前走 HAL 的 `cam2`（SENSOR_NOISE_PROFILE），其 G 通道比 R/B 低一半
（ISO 984：G 1.78e-4 vs R/B 3.58e-4）——物理上同一传感器四平面的散粒噪声应彼此接近；
用户这份四通道独立拟合后接近（2.68e-4）。而且 `BUILT_IN` 映射里**原本没有主摄条目**
（只有 70/15mm），所以主摄一直在用 HAL 模型。

**装配优先级**（`CameraController` 2556 行）：用户选定 > 内置标定（`nzSrc:"builtin"`）
> Camera2（`"cam2"`）。

**验证指纹**：拍照后 `camera.raw.noise` 的 `src` 应从 `cam2` 变成 **`builtin`**。

**注意**：长焦那份的 `noise_model_B[]` 含负值（-8.0e-6 / -8.5e-7），我们的 `evaluate`
按 `max(A·iso+B, 0)` clamp —— 在 ISO≥50 的实用区间不会退化，逻辑无需改。

