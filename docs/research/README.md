# docs/research/ —— 独立调研取证索引

2026-09-21 / 09-22 两轮**独立调研**的产物，用于指导引擎色彩科学实现（对标 PhotonCamera / darktable /
RawTherapee / Blender AgX / ACES-libplacebo / 厂商外观引擎 / filmulator）。

**分层约定**（这是健康分层，不是重复）：

- **`00-synthesis.md` = 活入口**：问题导向（Q1–Q5）的汇总与结论，唯一被 `docs/开发计划.md` 引用的文件。
- **`01`–`08` = 冻结的证据附录**：项目导向的全本，含完整算法、参数、逐字原文与 `文件:行号`。
  00 只搬结论、不搬证据，追溯"哪条结论基于哪段源码"必须回这里。
- **`90-…` = 机器原始转储**：AI workflow 的原始 JSON 返回，未整理，**但含独有内容**（见下）。

| 文件 | 主题 | 落地状态 |
|---|---|---|
| `00-synthesis.md` | 汇总（Q1–Q5）：影调/LUT 顺序、LUT 域、厂商外观是引擎还是 LUT、DOMAIN_MIN/MAX、最小正确引擎结构 | 已吸收；**唯一含实测就地订正** |
| `01-darktable.md` | darktable sigmoid / filmic rgb / AgX / lut3d / colorout 逐字源码全本 | 结论已落地 |
| `02-rawtherapee-art.md` | RawTherapee/ART：DCP 顺序、HaldCLUT film sim、3D LUT、管线分歧 | DCP 顺序已吸收 |
| `03-blender-agx.md` | Blender AgX 完整算法（OCIO config + SB2383 参考实现） | 思想已吸收 |
| `04-aces-libplacebo.md` | ACES（LMT、2.0 vs 1.x）+ libplacebo/mpv 的顺序、域字段、DOMAIN 解析 | **未落地**（前瞻储备，归档级） |
| `05-camera-look-engines.md` | 厂商外观"引擎 vs LUT"跨项目取证（dcamprof / DNG 规范 / filmulator / rawproc / vkBasalt） | Q3 结论已吸收 |
| `06-photoncamera-baseline-engine.md` | PhotonCamera RAW→外观管线、用户 LUT 承载域、与 FilmKit 量化差异 | 部分**已被实测推翻**，见下方"过时点" |
| `07-取证清单-HDR+夜景多帧.md` | 论文级逐字引用：HDR+ (Hasinoff 2016)、Night Sight、HDRNet、MFNR 阈值 | 未落地（MFNR 立项时用） |
| `08-取证清单-影调曝光与AGC.md` | 平台/源码侧：AOSP camera2 枚举、camera3.h AE 状态机、DNG/DCP、18% 灰、libcamera AGC | libcamera 0.16 已进 AE 决策 |
| `09-专项调研-频域降噪与降噪链对标.md` | 频域降噪专项（2026-09-29）：HDR+/NS 式 6/7 一手原文、PC（MGC）三条降噪链源码取证（**修正"PC 优势=频域维纳"旧归因**）、我方差距矩阵、DFT 合并内核 GPU 成本实测（三轮真机：优化版半重叠 **207.5ms 达标**） | **优化复测达标**（立项决策待用户） |
| `filmulator-filmsim-forensics.md` | Filmulator 显影物理模型 30 条英文逐字证据 | 未落地（本项目走参数化外观层 + LUT） |
| `90-工作流原始转储-未整理.txt` | workflow「color-engine-research」4 agents 的原始 JSON | **含独有结论，勿删**，见下 |

## ⚠️ 两个容易漏掉的独有结论

### 1. LUT 资产同源关系（只在 `90-…txt` 的 `lut_domain` 段）

二进制逐点比对证明：FilmKit 现有 42 个 `.lut` 与 `~/Downloads/LUT/滤镜.cube/*.cube`（64³）**逐点一致**
（maxd 0.0020 ≈ 半个 8-bit LSB）；而与 PhotonCamera 的同名 `.plut` **大面积不一致**
（`cc` 78% 分量 >0.02、`velvia` 83%、`cinestill800t` 89%、`ultramax400` 90%；仅
`ricoh_green` / `ricoh_yellow` / `Hasselblad` 真同源）。

⇒ **"同一个 LUT 在 PhotonCamera 上更浓"的对照实验前提不成立** —— 那两组根本不是同一份资产。
另：我们走纯 2.2 幂、PC 走精确 sRGB 分段，同值差 ≤6 个码（量级不足以解释"明显更浓"）。

### 2. 三个 arXiv 编号全部指向无关论文（在 `07`）

任务当时给定的三个编号经逐条核验**均非目标论文** —— 后续引用前必须重新核对，别再照抄。

## 已知过时点（读前必看）

1. **`00-synthesis.md` §Q5 第 3 层的行号已漂移**：文档写"三处同步 `FrameRenderer.cpp:780`(kFsOut) /
   `:845`(kFsHist) / `:2013`(反解码)"；文件已长到 2387 行，实测 kFsOut 在 **815**、kFsHist 在 **919**。
   **"三处必须同步"这条约束本身仍然有效**。
2. **`00` §附 第 7 条自相矛盾**：仍写 filmulator / rawproc / vkBasalt "取证仍未完成"，而 `05` 已于同日
   18:24 交付并含 §5/§6/§7 对应章节（00 的 mtime 晚于 05）。
3. **`06` §6 的数值用法已被推翻**：其"与 FilmKit 量化差异"曾被当作 `kTarget 0.26` 的依据 →
   v0.6.33 判定"域搞错了"回退 → v0.6.38 改按 PhotonCamera **同机位成片**取 0.38
   （见 `core/engine/src/render/FrameRenderer.cpp` 的 `updateAutoGain` 注释）。
   其照搬的 LUT 纹素中心补偿公式（PhotonCamera `BasicToneLutShader.kt:20-22`）在 v0.6.23 被
   **恒等 LUT 实测废弃**（现行 `t=c*(N-1)` 与硬件路径数学等价，照搬会二次偏移）。

## 核查说明

- 文档引用的外部 clone 位于 `/private/tmp/`（dtsrc / lpsrc / eng / blender-agx）与 `/tmp/photoncamera`，
  取证可复核；`app/src/main/java/com/hinnka/mycamera/...` 是 **PhotonCamera 仓库**路径，不是本仓库。
- 本仓库内的行号引用只有 `00` §Q5（已漂移，见上）与 `90` 的 `EngineBridge.kt:86`（仍准确）。
