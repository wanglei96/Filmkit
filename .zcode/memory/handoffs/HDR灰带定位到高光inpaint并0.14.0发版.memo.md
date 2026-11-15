---
summary: HDR灰带定位到高光inpaint并0.14.0发版
created: 2026-11-15 14:20
updated: 2026-11-15 14:20
status: absorbed
---

# HDR灰带定位到高光inpaint并0.14.0发版

## Summary

0.13.79→0.13.88 共 10 包，把 HDR 灯边灰带从"猜曲线/换 reference"一路定位到**本地自研的跨 CFA scalar 短帧 inpaint**（`kFsHlInpaint`）并移除。真机 0.13.88 两张同场景样片用户判定"完全正常"，而 HDR 增益照旧（p99 boost 3.05×/3.11×）。定版 **0.14.0(797) / tag v0.14.0**，已 push origin。

## Done

- **诊断基础设施**：`captureEdgeDumpTexture()` 一次性全分辨率分段导出（D4-CCM / film-src-pre-TEQ / prepared-hdr / film-out / sdr-final / gainmap / mosaic-fused-before-HL / mosaic-short-aligned / mosaic-after-P0），逐行 `glReadPixels` 写盘 + JSON 元数据（`orientation`/`components`/`complete`），`files/edge_dump.enable` 哨兵开启，普通拍照零开销。
- **阶段定位**：灰环在 **D4 已存在**（CCM 后、PC3D/去紫边/Filmic 前）；D4 灯边是中性亮度平台 `[1.54,1.53,1.51]`，非单通道截断，非紫边；prepared-hdr 只是 ×1.75 扩幅、film-out 压到 0.95。⇒ 排除 HDR 显示端、gainmap 编码、Filmic、defringe、输出锐化/清晰度。
- **P0 排除**：正确 R16F 单通道读取后，P0 前后对所有 408,098 个高光 photosite 逐像素 `delta=0`；饱和比例径向剖面完全重合 ⇒ P0 不产生环。
- **真实 GLSL 重放**（moderngl，生产 shader）：平台首次出现于 `kFsHlInpaint` 输出——中心 `fused=1.0`，HL 输出 2.4~2.8；旧版更大片直接 `≈uShortK 2.828`。
- **相位证据**：短帧有效性按 CFA 相位 `[G 7%, R 89%, B 7.6%, G 7%]`；`.r` 是各相位 sensor-normalized（`v/gain[c]`），不是同一光谱亮度。旧 fallback 偏移全偶数 ⇒ 只采同相位 G（几乎全无效）⇒ 回 `uShortK`；改成连续盘则把异相位 R/G/B 光谱混成标量 ⇒ 伪影换形状（0.13.84 连片、0.13.85 多圆弧、0.13.86 紫边回归）。
- **PC/Bento 一手对照**（`GlesMgcRawSpatialStacker.kt` / `GlesMgcRawSpatialShaders.kt`）：short clipping mask 只用于**整帧接受判定**（最大 8 连通 ≥80 guide px 则拒绝 Bento），不做逐像素填充；接受后按 `w_base=1−mask`、`w_short=1−w_base` 走 **phase-preserving MergeBayer**（每输出相位只采样同相位），输出 `1/ratio` 归一 + `baselineExposureEv` metadata。本地 `gain[c]×k` 无对应物。
- **修复**：`kHlInpaintOn = false`（常量切换，保留代码作诊断 A/B），标准 HL opposed 路径保留。
- **0.13.87 附带修复**：完整有效 2×2 块直接取已配准短帧值（去掉跨灯边的 5×5 双边）——真机紫边消失。
- **交付**：`filmkit-0.14.0.apk`（797/0.14.0），apksigner/`aapt2`/DEX 版本/`nm -D`（`nativeReadGainmap`/`captureHdrProbes`/`passGainmap`）四项核验通过；shader 校验 + `check-hdr-reference.py --gpu` 全绿。

## Decisions

- [2026-11-15] **禁止跨 CFA 相位的标量亮度 inpaint**：短帧各相位值除 gain 后仍是不同光谱响应，任何"求平均再乘回 `gain[c]`"都会污染色比；PC/Bento 只做整帧接受 + 相位保真融合。已用 `kHlInpaintOn=false` 从生产链移除。
- [2026-11-15] **不再用"加密采样半径/更大核"修灰带**（本窗口第四次实证）：离散 8/16/24、连续 1..24、连续盘 24 全部失败或换形状。真问题在语义（跨相位混盘 + 无观测回退常量），不在核形。
- [2026-11-15] **`hr_raw` 开关不等于高光链关闭**：`hr_on` 只控 `passHighlightRaw`，`kHlInpaintOn` 独立执行且 P0 直接消费 `fused_src` ⇒ `hlOk=0, hlInp=1` 是合法组合，不能用来"排除 inpaint"。
- [2026-11-15] **ponytail 不适用于成像关键链**：见下"ponytail 评估"。

## Pitfalls

- [2026-11-15] **诊断导出必须按纹理真实格式读**：mosaic 是 R16F 单通道，用 `GL_RGBA` 读回会把数据读错位；helper 现按 `mono` 走 `GL_RED`，JSON 记 `components`。用了两轮才纠正，期间"P0 放大 P99 0.94→2.42"是错误结论（混了布局）。
- [2026-11-15] **D4 与 mosaic 差 90° 旋转 + 垂直翻转**：`kVertRot`（`rot1: r=(-c.y,c.x)`）+ `kFsRcdChroma` 内 `tuv=(x,1−y)`；直接同坐标比会得到"暗背景 vs 亮 ROI"的假结论。径向统计不受旋转影响，逐像素对齐必须按源码映射。
- [2026-11-15] **同一 token 的分段 dump 会被后续拍摄覆盖**：`prepared-hdr` 同名文件在一次快门内写两次；分析前先按 `edge_dump.stage` 事件与 mtime 确认是哪一份。
- [2026-11-15] **"关掉某模块症状还在" ≠ 该模块无关**：关 `hr_raw` 后灰带变成不均匀带，正说明 inpaint 在重塑它；判定必须看 `hlInp` 字段，不能只看 `hr`。
- [2026-11-15] **真机 A/B 反馈要逐版记录失败形态**（0.13.84 连片 / 0.13.85 多圆弧 / 0.13.86 紫边回归 / 0.13.87 紫边好灰带在 / 0.13.88 全好），否则容易把"换形状"误当"有改善"。

## Next

1. **回归抽样**（未做）：其他高光场景 / 不同镜头 / 不同预设各拍一张，确认 0.13.88 无新高光/紫边回归——本窗口只验了灯边一类场景。
2. **旧账未动**：编辑器整页路由、Spektrafilm 引擎 + 预设标定、理光曲线重标、绿植/绿段、AE 高光保护效果。
3. **可选收尾**：`kFsHlInpaint` 代码与 `edge_dump` 诊断保留（默认关/哨兵开），若长期不用可评估删除；`tools/check-hl-inpaint-kernel.py` 现在测的是已禁用的核，注意别当生产判据。

## ponytail 评估（用户提问的专项结论）

- **有 ponytail 痕迹但不是本次根因**：仓库现存 6 处 `ponytail:` 注释，其中 3 处是 HDR 曝光融合的"逐像素加权代替 pyramid"（`FrameRenderer.cpp:1295/15125`、`FrameRenderer.h:1631`），`docs/engine-hdr-exposure-fusion-design.md:190` 明确授权"时间不够可先做逐像素加权 A/B 版并标注释"。但 Mertens 已退役（`hdr_mertens=false`），不在成片链上。
- **本次根因（HL scalar inpaint）没有 ponytail 标记**：它是 `0.12.133→0.12.196` 自研迭代产物，注释风格是"真机实测 + 逐版打补丁"，属于 AGENTS.md 已明文禁止的"用一个标量近似替代参考实现的完整度量"那一类，而非"按 ponytail 阶梯偷懒"的直接产出。
- **结构性风险成立**：全局 `~/.dsh/AGENTS.md` 要求"任何编码任务动手前先加载 ponytail"（最懒可行方案），与本项目"质量优先、严禁简化关键质量管线"方向相反。若在 RAW/融合/HL/去马赛克/色彩这类链路上按 ponytail 阶梯执行，会系统性偏向"先跑起来再补"，而本项目铁律明确禁止该顺序。
- **成因占比判断**：ponytail 是**助力因素**（鼓励临时形态、弱化"先读参考实现"的强制），不是**单独充分原因**；更直接的原因是缺少一手参考对照 + 用"真机看着能过"当验收，导致标量近似一路被保留。

## Refs

- 包：`filmkit-0.14.0.apk`（797/0.14.0）；回退 `filmkit-0.13.88-NOINPAINT.apk`（796/0.13.88）
- 提交：`9748172` fix(hdr): disable unregistered scalar short-frame inpaint；`465a4b4` chore(release): 0.14.0（`main`/`dev`/`v0.14.0` 同点，已 push）
- 代码：`FrameRenderer.cpp` 的 `kHlInpaintOn`(≈318)、`kFsHlInpaint`(≈3043)、`kFsMfnrShortVal`(≈3029 存 `v/gain`)、`passHlInpaint`、`captureEdgeDumpTexture`；`app/build.gradle.kts` 版本
- 一手源码：`/tmp/pc-full` —— `processor/GlesMgcRawSpatialStacker.kt`（1211-1249 mask 评估、6372-6423 `assessBentoMasks`、1388-1422 权重、1250-1267 selected calibration）、`processor/GlesMgcRawSpatialShaders.kt`（1520-1587 clipping mask、1704-1836 MergeBayer）、`processor/MgcSpatialOutputExposure.kt`（21-29 metadata）
- 工具：`tools/analyze-edge-dump.py`、`tools/check-hdr-reference.py`、`tools/check-defringe-magenta.py`、`tools/check-hl-inpaint-kernel.py`、`tools/hdr-baseline-eval.py`、`/tmp/replay_hl.py`（真实 GLSL 重放）
- 样片：`test_temp/hdr-01388/FILMKIT_20261115-140138.jpg`、`-140155.jpg`（验收通过）；各版本反例在 `test_temp/hdr-01384…01387/`
- 报告：`docs/reports/hdr-reference-20261115-AUDIT.md`
