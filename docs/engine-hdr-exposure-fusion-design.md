# HDR 曝光融合（+2.2EV 长帧 + Mertens）设计方案

> 2026-10-24。目标窗口：**独立专项**，从 0.12.75 起。本文只做设计，不含实现。
> 出处：PhotonCamera 源码快照 `/tmp/pc-full`（`git clone --depth 1 https://github.com/bjzhou/PhotonCamera.git`，main）。
> 行号仅对该快照有效；新窗口若快照不在，先重新 clone。

## 0. 一句话

把采集从「1 短 3 长、亮度守恒」改成「3 帧不同曝光（0 / +2.2 / −1.5EV）」，用 **Mertens 曝光融合**
（逐像素按对比度/饱和度/曝光可信度加权）合成 SDR base，再把融合结果接进现有 gainmap 链。

**非目标**（本专项不做）：

- 不做场景识别（天空/日落分类），已被用户多次否决；
- 不改 sigmoid/曲线白点（PC 白点同样是 1.0，不是差异点）；
- 不引入神经网络（HDRNet 不可得，且与"真实记录"冲突）；
- 不动预览链的 OES 直通。

## 1. 为什么单帧不够（实测数据）

同机位 17:19（阳台逆光，点测光已生效）：

| 项 | 我们（0.12.7x） | PC（同机位） |
|---|---|---|
| 采集 | ISO 65 / 0.010s（曝光量 0.65） | HDR 三帧 |
| 天空 RGB | **227, 221, 216** | **196, 187, 183** |
| 天空 B/G | 0.978 | 0.976 |
| `aeP995` | 3.49（= 4.24EV，白点 4.56 的 93%） | — |
| `aeHl` | **0.005**（高光约束已卡死） | — |
| 成片 `hi240` | **3.35%** | **0.22%** |
| 全图 mean | 51.6 | 62.2 |
| 底部（室内）mean | — | 46.1 |

结论：点测光把曝光压了约 3.2EV（对比默认场景 6.1 → 0.65），天空仍落在 4.24EV/4.56EV 的肩部末端。
**不是白点或曲线错，是这一帧装不下 12EV**（室内 5EV + 天空 7EV）。

白平衡不是主因（B/G 0.976 vs 0.978）；"金黄"消失是亮度太高导致的高光去饱和。

## 2. PC 的做法（一手取证）

### 2.1 采集：三帧曝光

```kotlin
// app/src/main/java/com/hinnka/mycamera/camera/HdrBracketConfig.kt:8-9
const val YUV_LONG_EV = 2.2f      // 长帧 +2.2EV（捞暗部）
const val YUV_SHORT_EV = -1.5f    // 短帧 −1.5EV（保高光）
```

```kotlin
// app/src/main/java/com/hinnka/mycamera/camera/Camera2Controller.kt:7774-7781
add(0f); add(YUV_LONG_EV); add(YUV_SHORT_EV)   // 0EV 为参考帧
```

### 2.2 合成：Mertens 曝光融合，不是"按位置选帧"

```kotlin
// app/src/main/java/com/hinnka/mycamera/processor/GlesYuvStacker.kt:433-438
renderMertensFusionToOutput(
    inputTextures = intArrayOf(hdrZeroTexture, hdrHighTexture, hdrLowTexture),
    exposureProducts = exposureProducts,
    enableDeghostMask = true,
)
```

权重 = 对比度 × 饱和度 × 曝光良好度（`MERTENS_WEIGHT_FRAGMENT_SHADER`，GlesYuvStacker.kt:3451 起），
两个关键门：

```glsl
// 高光门：任一通道 >0.96 ⇒ 这个曝光的色度不可信，权重归零
float highlightValidity(vec3 rgb) {
    return 1.0 - smoothstep(0.960, 0.995, max3(rgb));
}
// 鬼影门：黑门 0.055~0.145 + 白门 0.865~0.975
float deghostValidity(vec3 rgb) {
    float blackGate = smoothstep(0.055, 0.145, luma(rgb));
    float whiteGate = 1.0 - smoothstep(0.865, 0.975, max3(rgb));
    return clamp(blackGate * whiteGate, 0.0, 1.0);
}
```

另有 `censusMismatch`（3×3 census 变换比对，运动区降权）与 `referenceStructure`（参考帧梯度/拉普拉斯做结构门控）。

**"位置"是逐像素判断的，不需要语义分割。** 这是本方案能抄、且不违反"不做场景识别"的关键。

### 2.3 SDR base 与 HDR

PC 的 HDR 输出 = SDR base + gainmap（`RawOutputPass` 的 `uSdrBase` / `uApplyHdrGain`）。
SDR base 就是 Mertens 融合结果；gainmap 里装的是真实长帧数据。所以 PC 敢把 SDR base 压到天空 196。

## 3. 现状盘点

### 3.1 已有的

| 资产 | 位置 | 状态 |
|---|---|---|
| bracket 采集（1 短 3 长，短帧 −2.6EV） | `CameraController.kt` `bracketDiv`、`bracket.short` 日志 | 在跑 |
| MFNR 融合（4 帧同曝光，加权 + DFT 两条路径） | `FrameRenderer.cpp` `kFsMfnrFuse` / `kFsMfnrFft` | 在跑，默认 DFT |
| gainmap（SDR base + 增益，中灰基线 1.1） | `FrameRenderer.cpp:7036-7130`，`hdr_gainmap` prefs | 在跑（默认关） |
| teq（9 档分区影调） | `applyToneEq` | 在跑，默认保守（`teqHl −0.16`, `sh 0.5`） |
| HDR 交付（JPEG-R / 10bit HEIF） | `photo.deliver gainmap:true` | 在跑 |

### 3.2 缺的

1. **长帧没有额外光**：`ExposurePlanner` 的 `nIso` 是亮度守恒（快门×2、ISO÷2），三张长帧曝光量相同；
2. **融合是"同亮度加权"**：没有"该像素在这帧是否可信"的判据，长帧一过曝就整块炸（0.12.63 阴天：`aeP50 0.52`、`aeSatRaw 41%`、`hi240 35.9%`）；
3. **gainmap 从 SDR base 反推**，没有真实长帧参考。

## 4. 方案

### 4.1 采集（阶段 1）

```
帧序（参考帧固定第 0 张，短帧/长帧不得作参考帧 —— 0.11.67 教训）：
  [0] 0 EV    （参考帧，堆叠 2 张降噪）
  [1] 0 EV
  [2] +2.2 EV （长帧，捞暗部）
  [3] −1.5 EV （短帧，保高光）
```

约束：

- 0EV 基线的曝光由 HAL 决定（`liveIso/liveExposureNs`），**不改 HAL**；
- `+2.2EV` 优先加曝光时间，到 `YUV_LONG_MAX_SHUTTER_NS`（PC = 10 ms）后再加 ISO；
- `−1.5EV` 只用曝光时间，ISO 不变；
- 若 0EV 已贴 ISO 顶/快门顶，按实际可达到的 EV 记录（`bracket.actual` 日志，合成端用**实测** EV，不用请求值）。

> ⚠ 0.12.63 的教训：只放宽长帧曝光、没有融合兜底 ⇒ 阴天炸片。本方案阶段 1 **只采集不合成**，
> 先验证 `aeSatRaw` / `hi240` 与曝光跨度的对应关系，再进阶段 2。

#### 4.1.1 帧数：PC 的 3~20 帧是怎么用的（新增，2026-10-24 查证）

```kotlin
// MultiFrameConfig.kt:13-19
const val MIN_DENOISE_FRAME_COUNT = 3
const val DEFAULT_DENOISE_FRAME_COUNT = 5
const val MAX_FRAME_COUNT = 20
```

```kotlin
// CameraViewModel.kt:3680 —— 总帧数减去固定的 2 张侧帧（high/low）
zeroEvFrameCount = photo.frames.size - 2
```

```kotlin
// Camera2Controller.kt:7774-7781 —— 0EV 帧数由用户设置，侧帧恒 2 张
add(0f); add(YUV_LONG_EV); add(YUV_SHORT_EV); repeat(zeroCount - 1) { add(0f) }
```

**融合方式（GlesYuvStacker.kt:346-460 `processHdr`）**：

| 帧角色 | 处理 | 产物 |
|---|---|---|
| 0EV × N | 全部走 `prepareSpatialTemporalFrame` + `accumulateFrame`（空间/时域堆叠） | `hdrZeroTexture`（1 张） |
| +2.2EV × 1 | `renderHdrSideFrameToTexture`（**单帧，不堆叠**） | `hdrHighTexture` |
| −1.5EV × 1 | 同上 | `hdrLowTexture` |
| — | `renderMertensFusionToOutput(zero, high, low)` | SDR base |

⇒ **提高帧数只提升 0EV 的降噪，不扩大曝光跨度。** 跨度恒为 −1.5 ~ +2.2EV（3.7EV），
N 帧全部用于压低 0EV 的噪声。MAX 20 是上限，默认 5（= 3×0EV + 2 侧帧）。

**我们的帧数策略**：

| 方案 | 帧构成 | 适用 |
|---|---|---|
| 4 帧（起步） | 0EV×2 + 2.2×1 + 1.5×1 | P1 验证，与现有 4 帧预算一致 |
| 6 帧（推荐） | 0EV×4 + 2.2×1 + 1.5×1 | 0EV 降噪接近现有 MFNR 4 帧，P3 后 |
| 8 帧以上 | 0EV×6 | 仅在实测噪声不达标时 |

⚠ 我们的限制与 PC 不同：PC 的 HDR bracket 走 **YUV** 帧，RAW 路径是另一套；
我们的静拍 burst 受 HAL ImageReader 槽位（`maxImages=4`，见 `CameraController` 注释）
与采集时间（每帧 ~0.5s）约束，**不要直接抄 20 帧**。先跑 4 帧，用 `render.burst_done.ms`
和暗部 σY 定要不要加到 6。

### 4.2 合成（阶段 2-3）

新增一个 pass：`kFsHdrMertens`，输入 3 张纹理（zero / high / low），输出融合图，替代现有
`kFsMfnrFuse` 的输出（DFT 路径保留作降噪，可串联在 0EV 堆叠前）。

**域**：与 PC 一致，在**归一化线性 RGB（0..1，与 film pass 输入同域）**做。理由：
`highlightValidity` 的 0.96 阈值语义来自这个域；换域需要重标阈值，风险大于收益。

**权重**（照抄 Mertens，参数可调）：

```
W(x) = contrast^wc · saturation^ws · wellExposed^we · highlightValidity · deghostValidity
wellExposed = exp(−(L−0.5)² / (2σ²))，σ = 0.2（PC 可调，见 uExposureScale）
```

按 pyramid 融合（Laplacian + Gaussian，PC 同款）而不是逐像素直接加权——直接加权会出现接缝。
**若阶段 2 时间不够，允许先做"两帧（0 / +2.2）+ 逐像素加权"的 A/B 版**，但要标 `ponytail:` 注释说明这是临时形态。

### 4.3 与现有 MFNR 的关系

| 路径 | 职责 | 本方案后 |
|---|---|---|
| MFNR（同曝光 4 帧） | 降噪（σY / 细节） | **保留**，只服务 0EV 堆叠 |
| Mertens（跨曝光 3 帧） | 动态范围 | 新增，接在堆叠之后 |

推荐顺序：`0EV×2 堆叠降噪` → `Mertens(zero, high, low)` → `film 曲线` → `teq` → `LUT` → 输出。
不要反过来（Mertens 之后再堆叠会引入跨曝光鬼影）。

### 4.4 输出与 gainmap（阶段 4）

- SDR base = Mertens 输出（现有 8bit JPEG 链不变）；
- gainmap 改为从 **融合后的 HDR 参考**生成（现有 `kFsGainmap` 的输入从 base 换成融合前的高光参考）；
- 这一层放最后做，因为它不影响"SDR 变好看"，只影响 HDR 显示的亮度上限。

## 5. 验收判据（每阶段都要跑）

工具：`tools/color-metrics`、`tools/tone-scan`、`tools/ab-100pct.py`、日志 `raw.render` / `photo.stats`。

| # | 场景 | 判据 | 通过线 |
|---|---|---|---|
| J1 | 阳台逆光（17:19 同机位） | 天空 L* | ≤ 200（当前 227） |
| J2 | 同上 | `hi240` | ≤ 1.0%（当前 3.35%，PC 0.22%） |
| J3 | 同上 | 暗部 p5 | ≥ 当前 +0.5EV |
| J4 | 阴天（0.12.66 那组） | `aeSatRaw` | ≤ 5%（0.12.63 事故值 41%） |
| J5 | 阴天 | `hi240` | ≤ 1.5%（事故值 35.9%） |
| J6 | 室内暗场 | mean 变化 | ≤ ±0.3EV（不能因为 HDR 变亮） |
| J7 | 运动（人/车/树叶） | mismatch 中位 | ≤ 0.02（当前 `confMed` 0.67~0.74） |
| J8 | 任意 | 暗部 σY | ≤ 现有 ×1.15 |
| J9 | 任意 | 成片 p50 | 降幅 ≤ 0.2EV（防止"变全但变平"） |
| J10 | 关 HDR 开关 | 逐字节零回归 | 与 0.12.74 相同 |

判据 J1~J3 是"做成了"；J4~J6 是"没搞坏别的"；J10 是回退通路。

## 6. 实施步骤（每个阶段独立出包）

| 阶段 | 内容 | 交付 | 预计 |
|---|---|---|---|
| **P0** | 诊断：现有 4 帧的实测 EV / 饱和率写进日志（`bracket.actual.ev[]`），确认 HAL 给得准 | 0.12.75 | 0.5 天 |
| **P1** | 采集改成 0/0/+2.2/−1.5，**只采集不合成**（融合仍用 0EV 堆叠） | 0.12.76 | 1 天 |
| **P2** | `kFsHdrMertens`，先 2 帧（0/+2.2）逐像素加权，A/B 开关 | 0.12.77 | 1~2 天 |
| **P3** | 3 帧 + pyramid 融合 + highlightValidity + deghost | 0.12.78 | 2 天 |
| **P4** | 与 teq/曲线重标定；gainmap 改用融合参考 | 0.12.79 | 1~2 天 |

每阶段必须：全量单测 0 失败、`validate-shaders.sh` 通过、出包升版本、真机 A/B。

## 7. 风险与回退

| 风险 | 触发条件 | 回退 |
|---|---|---|
| 阴天炸片 | +2.2EV 长帧过曝、短帧不够短 | `hdr_fusion` 开关关掉即回 0.12.74 行为 |
| 鬼影 | 3 帧间运动 | deghost 门控 + `hdr_fusion` 关 |
| 噪声 | 长帧提亮 2EV | 长帧改 +1.5EV 或加堆叠帧数 |
| 变平 | 8bit 压平中间调 | teq 局部对比重标（P4） |
| 帧时间 | 4 帧 + 融合 | 先测 `render.burst_done.ms`，超预算减 0EV 堆叠帧 |

**回退通路必须是 prefs 开关（默认关），不是改代码。** 学 0.12.65/0.12.66 的教训。

## 8. 开放问题（P1 前必须定）

1. **帧预算**：4 帧够不够？0EV 只堆 2 张，降噪会比现在（4 帧）弱，σY 可能升。
2. **长帧 2.2 还是 1.5**：取决于 0EV 基线的保守程度（点测光后基线已经低了）。
3. **融合域**：线性 0..1 vs 显示域，0.96 阈值是否要重标。
4. **gainmap 的参考**：用融合后 HDR 还是保留现有 base 反推。
5. **与 teq 的先后**：teq 在曲线后（显示域），Mertens 在曲线前（线性域），顺序应保持。

## 9. 参考

- PC 源码：`HdrBracketConfig.kt:8-9`、`Camera2Controller.kt:7774-7781`、
  `GlesYuvStacker.kt:346-460`（processHdr）、`:3451`（Mertens 权重 shader）、
  `GalleryManager.kt:2960-3090`（帧角色与 exposure product）
- Mertens et al. 2007, *Exposure Fusion*
- 本仓：`docs/engine-bracket-design.md`（现有 bracket 设计与判据）、
  `docs/research/06-photoncamera-baseline-engine.md`（PC 管线）、
  `docs/research/17-独立复核-影调架构量化.md`（影调判据）
- 记忆：`.zcode/memory/INDEX.md`、`decisions.md`（"不许为单个场景加采集规则"）、
  `facts.md`（0.12.63 阴天炸片数据）
