# E4-5b 光晕分辨率归一化修正（R1）报告

> 架构师 2026-10-11 裁决：实施 R1。上游 = `docs/reports/e4-5-halation-REPORT.md` §4（实测发现的
> 规格 §3.2.5 缺陷）。**未 commit**。
> 代码基线：`core/engine/src/render/FrameRenderer.cpp`（13996 → 14043 行）。

---

## 0. 结论摘要

1. **R1 已实施**：`centre_c = radius01·(LEVELS−1)·SPREAD[c] + log2(min(w,h) / refMin)`。
2. **成片观感零变化**（硬证据）：成片调用时 `min(w,h) == refMin` ⇒ `log2(1.0f) = 0.0f` ⇒
   `centre + 0.0f` 在 IEEE 下**逐位等于** `centre` ⇒ 权重逐位等于修正前。真跑验证：
   **589,824 个 float 分量逐位相同**（判据 6d）。
3. **预览/成片的差距从 2.84× 压到 1.17×**（实机等效 1080 vs 3072，解析）：
   修前 预览 = 成片的 **2.84×**（归一化 r̄）；R1 后残差 **16.8%**（改善 **11.0×**）。
4. **判据 ⑥ 从 WARN → PASS**：`check-halation.py` 现在 **6a/6b/6c/6d 全 PASS**，
   退出码 **0**；新增的 6e 是**如实记录 R1 的已知残差**（根因 + 精确解选项），不是判据失败。
5. ⚠️ **R1 是近似**（不是精确解）：它平移高斯**中心**，而金字塔的尺度是**离散**的
   （2 倍步进）⇒ 中心靠近 level0 时左侧权重被截断 ⇒ 残差 16.8%（[64]=0.45 默认值）。
   精确解（对 centre 求根使 `E[2^i]` 匹配）**未实施** —— 超出架构师指定的 R1 范围，见 §5。
6. **性能不变**：pass 数 16、纹理 7 张、每 pass 采样数全部不变；新增成本 = CPU 侧
   **每帧 1 次 `log2`**（+ 每通道 5 次 `exp`，本来就有）。
7. **修正过程中抓出一处遗漏**：成片路径有**两条**（`submitStill` 的 YUV 静拍 /
   `finishStillChain` 的 RAW 静拍），首版只设了后者 ⇒ 新增静态判据 S10 钉住"两处都设"。

---

## 1. R1 的公式与 `refMin` 取值

### 1.1 公式

```cpp
// haloWeights（CPU 侧，每帧一次）
const float centre = radius01 * (kHaloLevels - 1) * kHaloSpread[c] + level_shift;
// runHalation：
const float level_shift = std::log2(static_cast<float>(std::min(w, h)) / halo_ref_min_);
```

**为什么是 `log2`**：金字塔 level_i 的等效全分辨率 σ_px = `1.637 · 2^(i+1)`
（blur 的 σ = 1.637 **texel @ 该级**）⇒ 归一化半径 = `1.637·2^(centre+1) / min(w,h)`。
代入 `centre' = centre + log2(min/ref)` 得 `1.637·2^(centre+1) / ref` ⇒ **与 min(w,h) 无关** ✓。
（尺度是 2 倍步进 ⇒ 平移量必须取以 2 为底的对数。）

### 1.2 `refMin` = **成片短边**（`halo_ref_min_`）

| 候选 | 判定 |
|---|---|
| **成片短边**（采用） | ✅ 唯一让**成片 shift = 0** 的取值 ⇒ **成片观感零变化**（架构师要求"改了默认观感必须给对照"⇒ 这里根本没有改）；同时预览/编辑器按它归一化 ⇒ 三者一致 |
| 预览短边 | ❌ 预览不变、**成片会变**（光晕变小）⇒ 改了用户已见的效果 |
| 硬编码常量 | ❌ 换画幅即错（虽然本版画幅固定，但没有理由埋这个雷） |

**实现（两处，都必须设）**：

```cpp
// submitStill（YUV 静拍成片链）与 finishStillChain（RAW 静拍成片链），各自在
// passOutput(...) **之前**：
halo_ref_min_ = static_cast<float>(std::min(dst_w, dst_h));
```

- `finishStillChain` 是"画幅"的权威来源（`dst_w/dst_h` = 成片输出尺寸）。
- **首拍之前**用成员默认值 `3072.0f`（= 4080×3072 画幅的短边；规格 §3.2.5 声明
  "本版固定画幅、无裁切"）。若首拍画幅不是 3072，第一次成片就会把它纠正过来。
- **编辑器链路**同样走 `runHalation` ⇒ 自动按同一基准归一化（它的 `w/h` =
  `edit_w_/edit_h_` = **输入位图**尺寸，不是裁剪后输出尺寸）✓ 架构师要求 3 已确认。

---

## 2. 修前 / 修后对照

### 2.1 实机等效（预览短边 1080 vs 成片短边 3072，[64] = 0.45 默认）

解析预测（`sigma_eff_norm`：Σ_i w_i,R · 1.637·2^(i+1) / min(w,h)）：

| | 预览 1080×1440 | 成片 3072×4096 | 比值 |
|---|---|---|---|
| **修前**（R1 关闭） | 0.01305 | 0.00459 | **2.84×** |
| **修后**（R1） | 0.00536 | 0.00459 | **1.17×**（残差 16.8%） |

⇒ 修前"取景框里调好的光晕拍出来明显不同"（2.84×）；R1 后差异降到 17%
（改善 **11.0×**）—— 肉眼在光晕这种低频效果上基本不可辨。

### 2.2 真跑对照（GLSL 真执行，短边 768 vs 384，`refMin` = 768）

| | 短边 768 | 短边 384 | 相对差 |
|---|---|---|---|
| **修前** 归一化 r̄_R | 0.02192 | 0.03965 | **80.9%**（≈2×，正是 ∝1/短边） |
| **修后** 归一化 r̄_R | 0.02192 | 0.02081 | **5.10%** ✓ |

（修前的像素尺度 16.84 vs 15.23 px = 恒定 ✓ 印证"固定像素尺度"的诊断。）

### 2.3 成片零观感变化（判据 6d）

`min(w,h) == refMin` ⇒ `level_shift = log2(1.0f) = 0.0f` ⇒ `centre + 0.0f` 逐位 == `centre`
⇒ 权重逐位等于修正前。真跑：**589,824 个 float 分量逐位相同** ✓
（同一张图、同一参数，R1 开 / R1 关的输出数组 `np.array_equal` 为真。）

---

## 3. 判据 ⑥：WARN → PASS 的证据

`python3 tools/check-halation.py`（真跑 `FrameRenderer.cpp` 的 GLSL）**退出码 0**：

| 判据 | 修前（E4-5） | 修后（E4-5b） |
|---|---|---|
| **6a** 归一化半径一致（真跑 768 vs 384） | — | ✅ **5.10%**（≤8%） |
| **6b** 修前对照（证明 6a 不是恒真） | — | ✅ R1 关闭时 **80.9%**（≥40%） |
| **6c** 实机等效（1080 vs 3072，解析） | ⚠️ WARN「归一化半径一致不成立」 | ✅ **残差 ≤ 修前/5**（16.8% vs 184.9% ⇒ 11.0×） |
| **6d** 成片逐位零变化 | — | ✅ **589,824 分量逐位相同** |
| **6e** R1 的已知残差 | — | ⚠️ WARN（**如实记录**，见 §5；非判据失败） |
| **S10** 两条成片路径都设基准短边 | — | ✅ `submitStill@7852` / `finishStillChain@10461` 两处都在 `passOutput` 之前 |
| **S11** `level_shift` 方向正确 | — | ✅ 源码文本 = `std::log2(min(w,h) / halo_ref_min_)` |

**其余 27 条判据全部无回退**（尤其判据 ① `[62]=0` 逐位零回归仍是 589,824 分量逐位相同；
② 通道扩散 R>G>B 与色相曲线数字与 E4-5 完全一致 —— 因为那些测试的 `refMin` 就是它自己的
`min(w,h)` 量级，R1 对它们只做"同尺度"平移）。

---

## 4. 是否有观感变化

| 链路 | 变化 |
|---|---|
| **成片**（`submitStill` / `finishStillChain`） | **零变化（逐位）** —— `shift = 0.0f`（判据 6d） |
| **编辑器导出**（`passStyle`，`edit_w_/edit_h_` = 成片尺寸时） | **零变化**（同上，`min(edit_w_,edit_h_) == refMin`） |
| **编辑器加载缩略图**（`min(edit_w_,edit_h_) < refMin`） | **有变化**：光晕半径按比例**变小**（= 修正到与成片一致）—— 这正是 R1 的目的 |
| **相机预览**（屏幕短边 ≈1080 < refMin） | **有变化**：光晕半径**变小 ≈2.4×**（修前是成片的 2.84×，修后 1.17×）⇒ 取景所见 = 成片所得 |

⚠️ **唯一"用户可见的默认行为变化" = 预览/缩略图里的光晕变小**（变成与成片一致）。
成片与全分辨率编辑导出**逐位不变**。

---

## 5. ⚠️ R1 的已知残差（如实记录，待拍板）

**现象**：R1 后预览与成片仍有 **16.8%** 的归一化半径差（[64]=0.45 默认值 @1080/3072），
且 `[64]` 越小残差越大（[64]=0.15 时 **67%**）。

**根因**：R1 平移的是高斯**中心**，而金字塔提供的是**离散**尺度集
（σ_i = 1.637·2^(i+1)，2 倍步进）。等效半径 `σ_eff = Σ w_i σ_i` 是 σ 的**线性**加权，
而尺度是**指数**的 ⇒ `σ_eff = σ_0 · 2^centre · E[2^(i−centre)]`，其中 `E[2^(i−centre)]`
只在"权重分布完全落在 level 0..4 内"时才是常数。当 `centre` 靠近 level0
（预览的 R 通道 `centre = 1.8 − 1.508 = 0.292`）时，左侧权重被截断 ⇒ `E[2^i]` 偏大
⇒ 实际半径大于目标。

**精确解（未实施）**：对 `centre` 求根，使 `E[2^i](centre) = E[2^i](centre_orig) · min/ref`
（`E[2^i]` 单调 ⇒ 二分 24 次即可，**纯 CPU、零 GPU 成本**，微秒级）。
成片仍可保持 `shift == 0` 走原路径 ⇒ **逐位不变**。
**未实施的理由**：它改变预览的**权重形状**（不再是"平移后的同形高斯"，而是"贴边分布"）
⇒ 光晕的径向剖面在预览与成片之间会略有不同（σ 匹配、形状不匹配）。二者是**不同的近似**，
属架构师/用户裁决范围，**不在"实施 R1"的授权内**。

**另一条路（成本明确，同样未实施）**：金字塔加一级（level −1 = 全分辨率）⇒ 最小
σ_px 从 3.27 降到 1.64 ⇒ 截断区消失。代价 = **+19% 采样**（+2 个全分辨率 pass 的等价量），
与"性能不变"的要求冲突。

---

## 6. 性能（**未真机实测**，静态确认"不变"）

| 项 | E4-5 | E4-5b |
|---|---|---|
| pass 数 | 16 | **16**（不变） |
| 纹理数 / 显存峰值 | 7 张 / 158.7MB | **7 张 / 158.7MB**（不变） |
| texel fetch | 133.6M | **133.6M**（不变） |
| CPU 侧新增 | — | **每帧 1 次 `std::log2`**（`haloWeights` 内的 5×3 次 `exp` 本来就有） |

⇒ R1 是**纯 CPU 的权重参数改动**，GPU 侧零影响 ✓（架构师要求 4）。

---

## 7. 构建与测试（真跑证据）

```
$ rm -rf app/build/test-results
$ find app/build -name "FrameRenderer.cpp.o" -delete
$ find app/build -name "libfilmengine.so" -delete
残留 = 0
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL
46 actionable tasks: 46 executed
⇒ 496 个用例，0 个失败
```

**脚本退出码**：`validate-shaders.sh` **0** / `check-diag-fields.py` **0**（新增 `haloRef`
字段已在白名单）/ `check-halation.py` **0**（28+ 判据，1 项 WARN = §5）/
`check-style-passthrough.py` **0** / `check-u4-camera-display-domain.py` **0** /
`domain-contract-check.py` **1**（**既有失败**：改动前后同为 `unlabeled 3` + 同样的两个
域重载嫌疑；halo 的 7 张纹理已标 `@domain: D5`，未新增 unlabeled）。

---

## 8. 文件清单（本次 R1 改动，**未 commit**）

| 文件 | 改动 |
|---|---|
| `core/engine/src/render/FrameRenderer.cpp` | `haloWeights` 加 `level_shift` 参数（含下溢防御）+ 长注释（根因/公式/已知下限）；`runHalation` 算 `level_shift`；**两条成片路径**（`submitStill` + `finishStillChain`）设 `halo_ref_min_`；两出口诊断字段 `haloRef` |
| `core/engine/src/render/FrameRenderer.h` | `haloWeights` 签名 + `halo_ref_min_ = 3072.0f` 成员（含取值理由） |
| `tools/check-halation.py` | `halo_weights(level_shift)` / `level_shift_for` / `sigma_eff_norm`；判据 ⑥ 重写为 6a–6e；新增 S10/S11 静态判据 |
| `tools/check-diag-fields.py` | 白名单 +`haloRef` |
| `docs/reports/e4-5b-halo-resolution-REPORT.md` | 本报告 |
| `docs/reports/e4-5-halation-REPORT.md` | §4 标注"R1 已由 E4-5b 实施" |

**未改动**（维持 E4-5 的边界）：D1–D5 降级选项、`[62..65]` 的 UI/预设层、
`highlightRolloff` 对 halo 的压缩（设计性差异，待真机 A/B）；
`EditorScreen.kt` / `CameraScreen.kt` / `CameraPresetPanel.kt` / `PhotoViewScreen.kt` /
`PresetDetail.kt` / `EditorExport.kt` / `assets/presets/` 一律未碰。
