# 哈苏自然（Hasselblad Natural Colour Solution）预设标定报告 · 2026-11-01

> 目标：内置「哈苏自然」预设，贴近 HNCS 的原厂观感。产出包 `filmkit-0.12.218-HB1.apk`。
> 与前两轮（理光正片 / 富士 NC）方法论相同，但**样片这次拿到了官方一手**（哈苏官网 84 张 X 系统样片）。

## 1. 官方定义（HNCS 页面，已取到全文）

- “render genuine, true-to-life colours with **smooth, detailed transitions**”
- “**uncompromising skin tone reproduction**” · “smooth tonal transitions”
- “True-to-life colours **by default**” —— 单一通用 profile，不按题材分档
- 组成：Hasselblad LUT + **Hasselblad Film Curve** + colour processing；“true contrast, **rich saturation**, subtle tones such as skin kept smooth between highlights and shadows”

⇒ 两个可验证的结论：**① 色彩接近中性（不系统偏色）；② 饱和不低（rich），影调是 film curve（暗部实、高光不堆、过渡平顺）。**

## 2. 样片与实测

来源 `hasselblad.com/learn/sample-images/x-system/`，84 张官方 X2D 直出 JPEG（机内 HNCS）。
下载 13 张覆盖人像 / 风景 / 建筑 / 静物（原图 20MB 级，缩到 1600px 后统计）。

### 2.1 灰轴：**不系统偏色**（这条最重要）

逐张中性像素（C\*<8）中位：

| 样片 | a* | b* |
|---|---|---|
| hans-strand（风景） | +0.08 | **−4.75** |
| linus-englund（人像） | **−3.50** | −0.42 |
| albrecht-voss（建筑） | **+5.11** | −0.12 |
| irene-rudnyk（人像） | −2.16 | **+3.33** |
| weimin-chu（风景） | +1.07 | +1.13 |

跨 13 张的 a\*/b\* 散布在 ±5 之间、**没有统一符号** ⇒ 那些偏色来自场景/光线（AWB 与拍摄对象），
**不是 HNCS 的系统性色调**。⇒ 目标灰轴 = **接近 0**（中性），不要"给哈苏加一层冷调"。

### 2.2 亮度与饱和

| 指标 | 哈苏官方样片（13 张） | 我们现有成片（test_temp 37 张） |
|---|---|---|
| C\* p50 中位 | **11.8** | **7.5** |
| L\* p1 / p50 / p99 | 4.5 / 30.9 / 81.7 | — |
| 黑（L<5）占比 | 11.95% | — |
| 白（L>95）占比 | 0.85% | — |

⇒ 哈苏的黑位很实（12% 落在 L<5）、高光几乎不堆（0.85%），且**饱和度比我们现在的渲染高约 1.5 倍**
（场景不同不可直接相减，但方向明确：我们的基线偏灰）。

## 3. LUT：现有可用，不重烘

| 候选 | 实测 | 判定 |
|---|---|---|
| `hasselblad_classic`（33³，TITLE「哈苏 Classic」） | 灰阶 0→0 / 50→50.2 / 100→100（轻微 S）；**色相探针全部 0° 偏移**；灰轴 a\*/b\* 全 0；唯一动作 = 肤色 C\* ×1.23 | ✅ **用**：中性 + 肤色优先，正是 HNCS 的两个特征 |
| `hasselblad_blue`（33³） | 暗部 b\* −9.4、红 C×0.74、绿 0.30、蓝 0.39、白点压到 91% | ❌ 冷调风格片，且压白（与富士 NC 那次同型问题） |

**为什么不重烘**：HNCS 的定义就是"中性、不偏色"，而 `hasselblad_classic` 已经几乎恒等（色相 0°、灰轴 0）。
在没有"我们渲染 vs 哈苏直出"同场景对照的前提下，凭空重烘一个"中性 LUT"只会引入无依据的偏移。
它的轻 S（50→50.2，端点不动）也与 HNCS film curve 的方向一致，保留。

## 4. 引擎：filmic（`[98] = 0`）

HNCS 的 "film curve + wide-ranging contrast + 平滑过渡" 需要可调肩/趾与黑白点；且暗部实（黑 12%）必须由
`[37] filmic_black_ev` 控制。sigmoid 无端点参数，表达不了"暗部实但高光不堆"。

## 5. 预设配方

| 槽 | 值 | 依据 |
|---|---|---|
| `[98] engine_slot` | 0（filmic） | §4 |
| `[4] contrast` | 1.05 | “wide-ranging contrast”，但 HNCS 比理光正片/富士 NC 克制 |
| `[37][38]` | −8 / +4 EV | 与其余预设同；黑位实（样片 12% L<5）由它保证 |
| `[20..22] sat` | 1.05 / **1.10** / 1.05 | 我们的成片 C\* p50 7.5 vs 哈苏样片 11.8（1.57×）；取温和 +10%，避免把"自然"做成"艳" |
| LUT | `hasselblad_classic`，强度 **1.0** | 中性 LUT，无需像徕卡（0.75）那样压强度 |
| 肩/趾 | 不写（默认 0.01） | HNCS 的过渡平顺由 filmic 默认 + LUT 的轻 S 共同给出，不再加柔化 |

## 6. 未决项

| 项 | 为什么没定 | 怎么定 |
|---|---|---|
| sat 1.10 是否合适 | 我们与哈苏的样片**场景不同**，C\* 中位不能直接相减 | 实拍后看"是否寡淡/是否发艳"，0 → 1.10 → 1.2 步进 |
| 肤色 +23%（LUT 自带）+ sat 1.10 是否叠过头 | 纯色探针不代表实拍肤色 | 拍人像，看脸颊是否发橙 |
| 对比 1.05 | 无同场景对照 | 同场景拍一张哈苏直出对比 |
| 是否需要"冷调"补偿 | 13 张灰轴散布 ±5，证明不系统偏色 | **不做**（做了就是自创风格） |

## 7. 实拍验证清单

1. 人像：肤色应"准而不艳"（哈苏的招牌），不该发粉或发橙；
2. 白墙/灰卡：中性，不该有可察觉的冷暖偏；
3. 高光（窗、灯）：不堆、不死白（样片白仅 0.85%）；
4. 暗部：实但要有细节（黑 12% 是 L<5，不是全黑）；
5. 和「理光正片」并排：哈苏应明显更"中性、少风格"，色彩更准确但更少味道——这才是 HNCS。

## 8. 产物

- 预设：`assets/presets/builtin.json` 第 6 条 `builtin_hasselblad_natural`（group「哈苏」）
- LUT：复用 `assets/luts/hasselblad_classic.lut`（33³，未改动）
- 单测：828 全绿（6 条预设的契约计数同步）
- 样片统计脚本：`/tmp` 临时（需要可复用 `pos_stats.py` 的 Lab 统计；未入库，样片未入库）
