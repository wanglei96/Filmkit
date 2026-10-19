---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-10-14 15:10
status: active
---

# 项目状态

<!-- 2026-10-05 d3 整理：全量吸收 37 份快照 + 5 份归档，压缩折行、删除被取代的中间状态。 -->

## 目标
胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学（追平 Adobe/libraw 标准链），风格（浓淡、胶片味）= 用户提供的理光系 LUT（M4）。

## 结构
- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（预览渲染线程）+ StillRenderClient（拍照渲染线程，v0.10.85）+ CameraAudit + `camera/StdColorMatrix.kt`（DNG 标准链）+ `ui/CameraPresets.kt`（FilmParams 槽位唯一来源，**kCount=99**）
- `core/engine/`：C++20 引擎——render/（FrameRenderer + Align）、gl/（EglCore/Capabilities）、spike/（SpikeFftMerge）、log/（JSONL+GPU 计时）、jni/Bridge.cpp、`film/FilmParams.h`、`film/EngineRegistry.{h,cpp}`（阶段 0 骨架）
- `docs/`：开发计划（§0 需求基线 D1–D13）、x300-能力审计.md、research/（12 个编号调研：PhotonCamera 管线 / darktable / RawTherapee / Blender AgX / ACES-libplacebo / 相机外观引擎 / filmulator / ISP 标准链与 AE·MFNR / 09=频域降噪 / 10=ARRI 引擎计划 + 内建编辑器与预设）
- `app/src/main/assets/`：`luts/` 42 个内置 .lut（uint8、33³、4.3MB，格式 `[size][titleLen][title][catLen][category][data]`）+ `noise_models/` 三摄标定（GCam .c 格式）
- `tools/`：validate-shaders.sh、check-diag-fields.py（出包前必跑）、check-log-json.py（分析前先跑）、still-thread-verify.py+run.sh、align-bench、p0-subpixel-eval、ab-100pct、chroma-scale-eval、texture-fidelity-eval、denoise-strength-eval、check-fft-tile-cover、chroma-denoise-eval、tone-scan、gen-lut-assets、dng-color-dump、color-metrics、ccm-chain-ab、check-srgb-oetf、check-lsc-mapping、lut-audit、tuning/

## 进行中（2026-10-15，期 A 10bit HEIF 已定稿）
- [2026-10-15] **期 B：公共工作空间切 Rec.2020 线性** | **待开工（用户已确认下一步做域空间）** | 方案 §5 的 8 面爆炸半径：filmic gamut primaries / CCM 链终点（`StdColorMatrix.kt:29-33`）/ OETF 5 处（4 处 shader `srgbEncode` + CPU `:14245`）/ LUT 域 / 预设重打标 / 输出映射 / 数值阈值 / 零回归基线 ⇒ **A 组守卫会整体变红，必须一次性重定基线**；⚠ 必须**单独一期** | docs/rec2020-heif-design.md §5 + handoffs/10bitHEIF交付链定稿与期A收官.memo.md
- [2026-10-14] **阶段 3 真机验收** | 待用户执行 | 清单 `docs/reports/stage3-acceptance-CHECKLIST.md` 判据 0–7；**先验判据 0**（引擎 1 必须真的改变画面 / 日志 `tone=1.0`）；判据 ①（高光可分辨跨度）需**固定机位 + 画面里有真正过曝区**（用户现有样片 `≥250` 只占 0.001%）| 包 `filmkit-0.11.29-ENGINE-SWITCH-GLOBAL.apk`（378 / 0.11.29，项目根目录）

## 已收尾（2026-10-13，相册与预设）
- [2026-10-13] **相册 / 预设面板（本窗口主线）** | 进行中 | 相册：切图转圈（PV-2）、上一张图闪现（PV-3）、缩放锚点（PV-4）已修；预设：横向预设条 + 一级页只给常用参数 + 完整参数二级页 + 返回键分层（PS-6a/6b/7/8/9/10），内置白点 2.5~2.7 → 4.0 修高光死白（R-2/R-3）。**待真机验收**：白杯子高光是否不再死白、亮部变暗幅度可否接受 | handoffs/相册切图闪修与预设面板重做至v0.11.26.memo.md
- [2026-10-13] **内建编辑器与预设** | 已完成（本窗口） | 取代 10-04 的"待立项"：拍照界面预设可查看/可逐项改/可增删（内置可改只禁删 + 恢复默认）；参数表单一来源 `ParamControls.kt`；`[37][38]` 黑白点可调 | 同上
- [2026-10-13] **影调 whiteEv 默认值** | 已定案 4.0 | 取代 10-03"4.0 vs 6.x 待用户试"：R-2 实测证明 2.5~2.7 时死白起点 2.40~2.60 EV、真机高光常到 2.47~3.0 ⇒ 余量不足；R-3 定 4.0（死白起点 3.55 EV），代价亮部变暗 −15.9~−18.0 LSB | docs/reports/r3-white-point-refit-REPORT.md
- [2026-10-13] **引擎切换（公共管线 + 引擎插槽）** | **阶段 0/1 已完成 + 真机验收通过**（`6a11833` / `f098ba8` / `ef1f50e`），下一步 = 阶段 2 sigmoid 引擎 | 用户拍板：**引擎切换要做、第 2 步直接对齐 PC、交付上 10bit HEIF**。方案 `docs/engine-slots-design.md`（`kFsFilm` 三段拆解 / `[98] engine_slot` + 引擎私有 KV / 引擎注册表 / 4 阶段 + **CRC32 零回归判据**）。**阶段 0** = `[98]` 契约（kCount 99，六处同步）+ `EngineRegistry` 骨架 + 基线 + `tools/check-filmparams-contract.py`；**离线链补齐** = `tools/engine-shader-offline.py` 接 FILM 段 + PC3D（`film_d5` 基线 `0x589f8364`）；**阶段 1** = `kFsFilm` 单真源切片（拼接逐字节等价 35154 B / `0x4fa1ebd3`）+ `[98]` 契约登记（新增 `CAMERA_ENGINE_SELECT_SLOTS`，参数面板零 UI 变化）。**真机验收通过**（包 `filmkit-0.11.27.apk`）：`engine.film_src` 6/6 条 `identical:true`、无 error/fatal、拍照出片正常。**⚠ 剩余缺口**：拍照链无 filmic 参数日志；HR/`uLocal*`/`kFsOut` 不在离线链内 ⇒ 成片零回归仍需真机同帧 A/B | docs/engine-slots-design.md + docs/reports/{stage0-engine-slot-BASELINE,stage1-acceptance-CHECKLIST}.md + 提交 `6a11833`/`f098ba8`/`ef1f50e`

## 进行中（2026-10-07，成像专项）
- [2026-10-07] **品红带机理闭环与替换档转正** | 进行中 | 机理定案：成片品红带 = B 通道 ~8px 缓爬 vs G ~2px 陡跳的错位（管线制造，RAW 干净，融合赦免，P0 缓解者，**RCD 类算法固有**，原版同款）；`[54]`=1 pc3d 替换档已转正（用户拍板：紫边细、红笔不色散）；`[56]`/`[57]`/`[58]` 三实验开关默认关存档。**待办**：[58] 加钳后灯盘回平确认；P0 边缘结构保护评估；pc3dK 色渗观察 | handoffs/品红带机理闭环与替换档转正.memo.md
- [2026-10-08] **边缘彩边残留（少量）** | 低 · 优化项 | v0.10.209 后仍有少量彩边（用户："观感影响不大，先这样"）。**已定案的机理**：伪彩 = 边缘**色度过冲**（中心色度 > 两侧最大值），其中一部分**由 PC3D 自身产生**（D4 域无过冲、成片有）。**现行判据**：peakK=1.05 / sideK=0.5 / 尺度 [3,6,12] / 去饱和 k=0.15，跑在 PC3D **之前**（color_smooth 前置，v0.10.205）。**优化方向（按代价排序）**：① peakK 1.05→1.0（覆盖↑、色度代价约 −3%，最省事）；② 若仍不足，去 PC3D 内部查它的色度重建（过冲源头，比在外面补 pass 根本）；③ **不要**加"单侧中性"判据 —— 实测代价 −18~22%（19.5 万像素里大部分是真实色度边缘）。**已判死的路**：v0.10.208 的"PC3D 之后再加一次 + 搬最中性点"会把背景色搬进中性区（中性灰染蓝、全图过冲 932→1209）。
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 4.8ms 短曝光；修正方向：有界给足 / D-1 低频扩展 / 暂缓（替换档下 D-1 被旁路，方向随之改变） | handoffs/紫边归因闭环与对标基准修正.memo.md（absorbed）
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 = 4.8ms 短曝光（ISO 下限锁死 Planner 拉长）；方案 A 无饱和预算毁图（RAW 饱和 4.2% + HR 品红洪水）已回滚；**修正方向待拍板**：有界给足（饱和预算）/ D-1 低频扩展 / 暂缓 | handoffs/紫边归因闭环与对标基准修正.memo.md（已 absorbed）
- [2026-10-03] **亮部亮度降噪** | 挂起 | 对标 PC 唯一成像短板（亮部 σY 1.67×）；P0 bilateral 全图只降 21~32%，亮部为何更弱待查（`σ²=A·x+B` 在亮部更大 ⇒ 权重应更大，实测相反）| handoffs/亮部降噪短板定位与小波重建待修.memo.md
- [2026-10-03] **超广畸变自校正** | 待拍板 | HAL 路线已全关 ⇒ 必须引擎 GPU 自校正（去马赛克前、逐 CFA 平面反向映射保相位）；**需拍板**：完整校正（视场窄 5~10%）/ 部分校正 / 不校正 | handoffs/超广探测高光保护与白斑攻坚.memo.md
- [2026-10-03] **影调 whiteEv 默认值** | 低 | 4.0（darktable 默认、有白点）vs 6.x（白点消失、更柔）等用户多场景试后定
- [2026-10-01] **M5 诊断与基准** | 低 | 逐 pass GPU 计时、成像会话摘要、一键诊断包、性能基准脚本 + golden image 接 CI | decisions.md「色彩链、管线与 MFNR」

## 里程碑沿革（压缩）
- [2026-10-15] **期 A：10bit HEIF 交付链定稿（10-14/10-15，本窗口）**：设置页「照片格式」（默认 JPEG / 可选 HEIF 10bit）；链路 = 引擎 10bit readback（`GL_RGB10_A2` + `GL_UNSIGNED_INT_2_10_10_10_REV`）→ CPU BT.709 全范围 4:2:0 → P010 → 硬件 HEVC Main10（**VBR + 官方 QP 键，QP=4 = 画质饱和点**）→ **自研 HEIF 容器**（`ftyp+meta+mdat`，`colr/nclx=1/13/1/1`，**不引入 libheif**）→ 落盘 + 读头自检；三级回退 `10bit → 8bit HEIF → JPEG`。判据 F 三端全过（macOS/微信/系统相册+Windows）。**四轮真机标定出三条硬结论**：编码器**不读码率**（100 Mbps ⇒ 187 KB）· QP 键**只在 VBR 下生效** · 画质在 **QP≈4 饱和**（QP4↔QP0 逐像素 p99 差 1 LSB 而体积 +36%）。途中**一次 P0 事故**（MFNR 落盘绕过交付档 ⇒ 用户 2 张真实照片损坏，修法 = 交付档随回调传递 + 唯一分派点 + 格式参数不留默认值）。781 单测 / 0 失败；包 `filmkit-0.11.46-HEIF-FINAL.apk`（395）；提交 `152304d`/`362ef24`/`42b6c31`/`5bcefc6`/`925a505`/`258a32f`/`16686fc`
- [2026-10-14] **引擎插槽阶段 3 + 3b（10-14，本窗口）**：契约 `EngineScope.kt`（14 槽旁路表 + `KNOWN_ENGINE_SLOTS` + `effectiveEngineSlot`）+ 拍照链 5 个下发点与编辑器唯一出口的旁路收口 + UI 按引擎显隐（参数面板 4 项 / 设置页 5 道 gate / 「饱和度」由通用判据自动隐藏）+ 「完整参数」页**引擎选择面**（写预设 `[98]`）+ **设置页全局三档开关**（prefs `engine_override`；渲染侧每次下发前读、并把 `[98]` 真改写）。**验证卡抓出阻断级 P0**（`[28]=0` 让引擎 1 完全空转）并连带修 P1（`[22]` 半旁路）/ P2（未注册值第三态）；**新增判据 0**（引擎必须真的在工作）。全量 **664 条 / 0 失败**；包 `filmkit-0.11.29-ENGINE-SWITCH-GLOBAL.apk`（378）；提交 `c801f0a` / `1cf94a2` / `e3b7116`
- [2026-10-14] **阶段 2 sigmoid 引擎（10-14，本窗口）**：darktable `process_loglogistic_per_channel` 移植（CPU 侧 `computeSigmoidParams` 三锚点求解 + 7 个 `uSig*` uniform + float32 精度）+ 运行期热切换 `buildFilmProgram(slot)`。**五条判据真机验收全过**：可分辨跨度 **2.00 EV**（filmic 1.70）/ 中灰偏差 **0.000 LSB** / 线性域**严格单调**（filmic 4.02 EV 起硬平顶、350 个非正差分）/ 真机 A/B / `[98]=0` 时 `film_d5` `0x589f8364` 与拼接 `0x4fa1ebd3` 逐字节不变
- [2026-10-13] **阶段 1 真机验收通过（10-13，本窗口）**：包 `filmkit-0.11.27.apk`（376 / 0.11.27）；真机日志 `filmkit-logs-20261013-215923` 的 `engine.film_src` **6/6 条** `identical:true` / 35154 字节 / `0x4fa1ebd3`，`film_split_missing`/`slot_unavailable` 各 0，无 error/fatal，拍照出片正常 ⇒ **零回归在设备上坐实**
- [2026-10-13] **引擎插槽阶段 1（10-13，本窗口）**：`kFsFilm` 单真源切片拆成公共三段 + 引擎注入段（拼接逐字节等价 35154 B / `0x4fa1ebd3`）+ `[98]` 契约登记（第三种东西 ⇒ 新增 `CAMERA_ENGINE_SELECT_SLOTS`）+ 离线拼接判据链（`--verify-splice` 两路径 + 6 项 mutation）；597/0、validate-shaders 66/66；提交 `ef1f50e`
- [2026-10-13] **离线链补齐（10-13，本窗口）**：`tools/engine-shader-offline.py` 补 FILM 段 + PC3D 真接入（纯增量，原 295 行未动）+ `filmic-spline-ref.py` / `check-film-offline.py` / `film-offline-REPORT.md`；`film_d5` 两组基线入档；旁路档逐位恒等自证；提交 `f098ba8`
- [2026-10-13] **引擎插槽阶段 0（10-13，本窗口）**：`[98] engine_slot` + 六处同步 + `EngineRegistry` 骨架（零行为变化）+ 离线基线 + 契约守卫脚本；提交 `6a11833`。**实测发现两个缺口**（方案未写全）：离线执行器其实**没接 `kFsFilm`**（注释与实现不符）、拍照链无 filmic 日志 ⇒ 用户拍板先补离线 `kFsFilm`/PC3D 再开阶段 1

- [2026-10-13] **引擎切换调研与方案（10-13）**：针对「黑白点反复手调」发起专项 —— 6 份调研（12–17 号：神经 ISP / 学习型影调 / **vivo X300 真机引擎取证** / 引擎架构 / 影调科学 / **独立复核**）+ 方案 `docs/engine-slots-design.md`；核心结论「白点难调 = filmic 参数化缺陷」+「工业界两样本（vivo/Google）都分层且成片零 NN」；拍板引擎切换 / 对齐 PC / 10bit HEIF；判据改「可分辨跨度」

- [2026-10-13] **v0.11.15–0.11.26（10-11~10-13，相册 + 预设）**：相册 PV-1/2/3/4（切图转圈 → 上一张图闪现 → 缩放锚点）、预设 PS-3→PS-10 + PR-2 调研 + R-2/R-3 白点；`ParamControls.kt` 成为参数表单一来源；`CameraPresetFullEdit.kt` 新增二级页；全量 595 用例 0 失败；包 `filmkit-0.11.26.apk`（375）。**交互方向全部来自四家开源调研，不再自拟**

- [2026-10-08] **v0.10.182–209（10-08，边缘彩边专项）**：`[59]` color_smooth（darktable color_smoothing 移植：色差域中值 → 后演化为"多尺度两侧一致性 + 局部色度峰 + 去饱和"）、前置到 PC3D 之前（v0.10.205）、判据定案 peakK=1.05/sideK=0.5/尺度[3,6,12]（v0.10.206/209）、替换策略去饱和（v0.10.209，修 208 的"搬背景色"）；kCount 60；**v0.10.196/197 事故**：FilmParams 数组长度 59→60 未同步 Kotlin ⇒ 启动崩溃；**v0.10.198**：GLSL 多行宏续行不生效 ⇒ 静默失效，改完全内联；**v0.10.200**：梯度门 20/50 致紫边大面积回归（真机判死）；提交 8577cd9 等，包 `filmkit-0.11.0.apk`
- [2026-10-07] **v0.10.178–181（10-06/07）**：`[56]` rcd_g_orig（P2/G 位回归原版开关）、`[57]` hl_rb_recon（HR 部分满阱反推）、`[58]` rcd_p3_split（P3 拆双 pass + 满阱钳）、`[54]` **替换档转正（默认 1）**；kCount 59；`docs/research/11-专项调研-紫边修复方案检索.md`；提交 2a67c42 / c7fef2d，包 `filmkit-0.10.181.apk`（320）
- [2026-10-06] **v0.10.177**：`[54]` 默认叠加档（后被 v0.10.181 转正取代）、`[51]` bool→κ 滑杆、`tools/purple-fringe-kpi.py` + `docs/reports/` 存档；提交 c5f1475 / a503d7e
- [2026-10-05] **v0.10.162–176（10-05）**：小波 HR 阈值域修复（真机 Y 加权和差 +2.03%→+0.03%、clipB 289→60）、`uStat` 接线 + HR 前置到 P0 之前、档位 2 硬限回退 + `hr_wavelet` 默认关（省 ≈752MB 纹理 + ≈103 全分辨率 pass/张）、`uGRecon` 开关、4:4:4 JPEG、AMaZE 去马赛克、强边缘色度门（0.173 全局放宽翻车 → 0.174 逐像素）、ESD3D2 完整移植 + 设置页入口；HEAD `a91c9d6`，包 `filmkit-0.10.176.apk`（315）
- [2026-10-04] **v0.10.126–136**：设置页 35 项平铺 → 4 抽屉 + 光圈 vector 图标（PC 同构完整资源套，验收 tag v0.10.133）；defringe 落地（0.10.134，离线紫边 ↓63%）；曝光给足 0.10.135 真机毁图 → 0.10.136 当日回滚
- [2026-10-02/03] **曝光决策 + 降噪链 + 影调固定**：离线证明曝光 ×N 收益 = √N（真机 250ms 档 ISO167，噪声降到 1/3.1）；σ 恒真值 + 强度<1 走输出 mix（修"亮部降噪近似归零"）；三摄 GCam 标定接入；NN 路线判死转 HDR+；高光保护（clipB 890→163）；影调白点 4 个互相干扰的旋钮全撤（标准模式唯一、固定 darktable 默认 −8/+4 EV）；曝光判据改用成片域 `sw999`
- [2026-09-30] **对齐/P0 亚像素 + 性能收口**：最细对齐层 1/4→1/2 域 + 逐通道网格亚像素（同帧降噪 +43.7%/+46.5%，两者协同而非叠加）；A 专项 DFT 合并验收（根因 = imageStore 目标必须 `glTexStorage2D`）+ D-1 色度多尺度超 PC；快门间隔 2.5s→0.54s；拍照独立线程落地（同帧 A/B CRC32 逐字节等价）
- [2026-09-27/29] **域映射与 MFNR**：EV 单一来源（filmic 前线性域）、预览域映射真 WYSIWYG、管线顺序定稿；MFNR 阶段一（4 帧 Bayer 域对齐 + 等权均值 + 逐通道亚像素）验收、阶段二 robust merge（mismatch + 一致性门控，手部达标）；C 专项噪声模型全链（σ²=A·x+B + 三摄标定，残余噪声 4.4×/2.2× → 1.22×/1.45×）
- [2026-09-21/24] **基础链奠基**：M2 RAW 端到端 + DngCreator 底片、RCD 三段 fragment 管线（12MP 1969→477ms）、LSC 接去马赛克后 CCM 前、P2 标准色彩链（ColorMatrix/ForwardMatrix 双光源）、M4 LUT 引擎（display 域、OETF 后）+ 无损导入导出、基础引擎三旋钮正交化、真 sRGB OETF、哈苏形 Reinhard 换底 → filmic 原生件接管
- [2026-09-18/20] **M0–M2 前半**：YUV 全链路（SurfaceTexture+OES 预览、全分辨率静拍）、M 档专业 UI、镜头发现与设置页、手动 WB = HAL 恒 AWB + 引擎侧补偿、变焦画质优先（±3% 迟滞）

## 品红/紫边与曝光（10-04 → 10-06）
- [2026-10-04] **定性修正**：品红 = 逆光边缘紫边渐晕（上窗口"色度高、亮度平坦"判据被真实样片否决：品红处亮度带通 = 全图 14×）；14.74% 基准系 uint8 回绕口径污染，紫边安全口径 0.65~0.78%
- [2026-10-04] **defringe 落地（v0.10.134，c5133d9）**：4 pass 半分辨率（down+Lab a 门 / 11×11 检测 / 16×16 均值读回 chromave / 全分辨率替换），RT `PF_correct_RT` 机制复现（GPL 不抄码）；prefs `defringe` 默认开、FilmParams[47]、日志 `df`/`dfCavg`；对 >32px 低频云结构性无效
- [2026-10-05] **真机剖面**：我们 = 窄峰（FWHM 4px）、PC = 宽缓斜坡（20+px、无峰）⇒ 高频窄峰与 >32px 低频云是两件事；1px 色度噪点 10.5%→0.04%（融合+P0/P4/D-1 有效）
- [2026-10-05] **口径自证**：复现 PC 的 3.68413/0.44607%；零假设检验证明 3.684 里只有 +1.668 与边缘有关；`∇>60` 均值同地点相隔 7 秒可差 25.8× ⇒ 禁作跨图 KPI
- [2026-10-04] **曝光给足事故**：无饱和预算的给足（target 333ms + 砍 hl 上限）在逆光窗 = RAW 饱和 4.2%（R 24.3 万像素）+ HR 品红洪水 + 白点失控（hrWhiteEv 8.52 / pHi 5.1），成片与 DNG 高光永久丢失；修正方向待拍板
- [2026-10-06] **排查翻案**：红笔场景"锐化主因"（关锐化 ≈PC）不能外推台灯逆光场景（用户实测推翻：关锐化照样品红窄峰 +10@3px）；色散 = 管线产生（RAW 域证据）；紫边分 A 窄峰 / B 台灯品红两类分治；**排查主场景转移至台灯逆光**；KPI 尺子 `tools/purple-fringe-kpi.py` 落地（自检 PASS）
- [2026-10-07] **机理闭环与转正**：品红带 = B−G 边缘过渡错位（RAW 干净、融合赦免、P0 缓解、RCD 类固有；直译 LIM 削顶假象已更正）；`[56]`/`[57]`/`[58]` 三轮真机 A/B 均否定、默认关存档；`[54]` pc3d **替换档转正**（用户拍板：紫边细、红笔不色散）

## 期 B：公共工作空间切 Rec.2020（10-19）

- [2026-10-19] **期 B 落地并完成修复轮**（提交 `3fa65af`→`a3aedfe`，包 0.11.47/48/49）：filmic LMS 整组 + CCM 终点切 Rec.2020（矩阵真源 `tools/rec2020-domain-matrices.py`，构造法 + 跨实现对拍）；输出端补域收口（`kFsOut`×2 / `kFsHist`）；预览/静拍输入补 `srgbToWork` + 去 gamma 换真 sRGB EOTF。**16 个守卫全绿**。
- [2026-10-19] **三处域错配修复**：① 预览不进 CCM（`uCcm` 只在 `kFsRaw`/`kFsCcm`）⇒ 数据停在 sRGB 线性却被下游按 Rec.2020 消费；② HAL 回退链终点是 sRGB（调用点 `stdCcm ?: halCcm` ⇒ 主链与回退链必须同终点）；③ U-1 split/mono 在 `kFsFilm` 线性域、与 `kFsStyle` 显示域差 43% ⇒ 挪到 `kFsOut` 显示域段（零回归证据：`film_d5` CRC 不变）。
- [2026-10-19] **引擎 1 对比度补齐 + `[40]` 补 gate**：`uContrastOut`（引擎 1 = `[4]`、引擎 0 = 1.0 恒等）—— 此前 `[4]` 在引擎 1 下**完全没有实现**；`[40]` 色域映射本就在 `FILMIC_OWNED_SLOTS`、是设置页漏挂（引擎 1 下"显示但完全无效"）。
- [2026-10-19] **真机验收（0.11.49）**：② 饱和修正、④ `[40]` 隐藏、对比度滑杆三项**通过**。**预览与成片仍有架构性差距**（饱和度 +39%：0.1294 vs 0.0930；暗部 P5 8.9 vs 34.2；动态跨度 191.1 vs 187.9 ⇒ 不是对比度问题），根因 = 预览走厂商 ISP、成片走我们的 CCM 且 ISP 已做过一次 tone ⇒ **用户决定不再挖**。
- [2026-10-19] **HEIC 编辑器显示异常（未复现，用户"有出现再说"）**：症状"一坨五颜六色" = 0.11.34 同款霓虹伪彩。已排查 `ui/ImageDecode.kt`（`BitmapFactory.decodeStream` + `inSampleSize`，**对 HEIC 无特判**）。**验收缺口**：判据 F 只测 macOS/微信/系统相册/Windows 四端、**没测 App 内回看**。

## 色相路线评估与 HDR 路线（10-19）

- [2026-10-19] **ACES 2.0 关闭**：离线实验（OCIO 官方 `ACES 2.0 - SDR 100 nits`）+ 红杯 ROI；先修正 **D5 域口径 bug**（`film_d5` 是 Rec.2020 线性，直接套 sRGB OETF 会得假结论）。修正后：sigmoid ΔE 3.69/Δh 4.43° · filmic 2.11/1.50° · ACES 重锚 4.69/1.58° ⇒ **ACES 无色相优势**，且需输入变换 + 中灰重锚（牺牲高光）+ 表。
- [2026-10-19] **Oklab L-only（C 方案）不做**：GLSL 已实现并离线跑通（Δh 4.43°→1.83°，高光不变），但 ΔE 3.69→5.42；变体（`a/b` 不缩放、`×0.8`）与混合 w=0.3~0.8 均无交点（ΔE≤3.7 且 Δh<2.5°）⇒ 回滚，`kFsSigmoid` 恢复 `0xfbab6a8c`。
- [2026-10-19] **HDR 路线定案 = 按 PC 做**：PC 的 HDR JPEG = 自研 4:4:4 JPEG + gainmap JPEG + **libultrahdr** `packageJpegR`（vendored `libultrahdr`/`libjpeg-turbo`）；`Bitmap.compress` + `Gainmap` 是 4:2:0 的另一条。x300 成片无 HEIC；用户不喜欢 HEIC（4:2:0 色度差、亮度优势不明显、细节差）。**不做探针**（PC 已验证下游）。
- [2026-10-19] **默认 AE 不读场景统计**：`sw999` 只在 `[35] auto_tone` 开启时写，默认 `[35]=0` 且引擎 1 归零 ⇒ 高光保护默认不参与，期 B 换域不影响默认 AE。
