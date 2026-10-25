---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-10-22 22:20
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

## 进行中（2026-10-20）


## 已收尾（10-13，相册与预设，详见快照）

- [2026-10-13] **相册 / 预设面板 + 内建编辑器**（v0.11.15~26）：PV-1~4（切图转圈/闪现/
  缩放锚点）、PS-3~10（预设条/一级页/二级页/返回分层）、`ParamControls.kt` 参数表单一
  来源、白点 R-3 定 4.0。**待真机验收**：白杯子高光死白 | handoffs/相册切图闪修与预设面板重做至v0.11.26.memo.md

## 进行中（2026-10-07，成像专项，长期挂起）

- [2026-10-07] **挂起两件**：亮部亮度降噪（对标 PC 唯一成像短板，P0 bilateral 只降 21~32%，亮部为何更弱未查）；超广畸变自校正（HAL 全关 ⇒ 需引擎 GPU 自校正，**待拍板**：完整/部分/不校正）| 各自旧快照

## 里程碑沿革（压缩）
- [2026-10-13/14] **引擎插槽阶段 0-3**（详见快照与 decisions）：`kFsFilm` 单真源切片 + `[98]` 契约（`ef1f50e`）→ sigmoid 引擎（可分辨跨度 2.00EV、五判据全过）→ 引擎切换全局落地（`EngineScope.kt` 14 槽 + 设置页三档 `engine_override`；验证卡抓出 `[28]=0` 空转 P0 ⇒ **新增判据 0：新引擎必须配正向判据**）；664/0，包 0.11.29（378）


- [2026-10-13] **v0.11.15–0.11.26（10-11~10-13，相册 + 预设）**：相册 PV-1/2/3/4（切图转圈 → 上一张图闪现 → 缩放锚点）、预设 PS-3→PS-10 + PR-2 调研 + R-2/R-3 白点；`ParamControls.kt` 成为参数表单一来源；`CameraPresetFullEdit.kt` 新增二级页；全量 595 用例 0 失败；包 `filmkit-0.11.26.apk`（375）。**交互方向全部来自四家开源调研，不再自拟**

- [2026-10-08] **v0.10.182–209（10-08，边缘彩边专项）**：`[59]` color_smooth（darktable color_smoothing 移植：色差域中值 → 后演化为"多尺度两侧一致性 + 局部色度峰 + 去饱和"）、前置到 PC3D 之前（v0.10.205）、判据定案 peakK=1.05/sideK=0.5/尺度[3,6,12]（v0.10.206/209）、替换策略去饱和（v0.10.209，修 208 的"搬背景色"）；kCount 60；**v0.10.196/197 事故**：FilmParams 数组长度 59→60 未同步 Kotlin ⇒ 启动崩溃；**v0.10.198**：GLSL 多行宏续行不生效 ⇒ 静默失效，改完全内联；**v0.10.200**：梯度门 20/50 致紫边大面积回归（真机判死）；提交 8577cd9 等，包 `filmkit-0.11.0.apk`
- [2026-10-07] **v0.10.178–181（10-06/07）**：`[56]` rcd_g_orig（P2/G 位回归原版开关）、`[57]` hl_rb_recon（HR 部分满阱反推）、`[58]` rcd_p3_split（P3 拆双 pass + 满阱钳）、`[54]` **替换档转正（默认 1）**；kCount 59；`docs/research/11-专项调研-紫边修复方案检索.md`；提交 2a67c42 / c7fef2d，包 `filmkit-0.10.181.apk`（320）
- [2026-10-02/03] **曝光决策 + 降噪链 + 影调固定**：离线证明曝光 ×N 收益 = √N（真机 250ms 档 ISO167，噪声降到 1/3.1）；σ 恒真值 + 强度<1 走输出 mix（修"亮部降噪近似归零"）；三摄 GCam 标定接入；NN 路线判死转 HDR+；高光保护（clipB 890→163）；影调白点 4 个互相干扰的旋钮全撤（标准模式唯一、固定 darktable 默认 −8/+4 EV）；曝光判据改用成片域 `sw999`
- [2026-09-30] **对齐/P0 亚像素 + 性能收口**：最细对齐层 1/4→1/2 域 + 逐通道网格亚像素（同帧降噪 +43.7%/+46.5%，两者协同而非叠加）；A 专项 DFT 合并验收（根因 = imageStore 目标必须 `glTexStorage2D`）+ D-1 色度多尺度超 PC；快门间隔 2.5s→0.54s；拍照独立线程落地（同帧 A/B CRC32 逐字节等价）
- [2026-09-27/29] **域映射与 MFNR**：EV 单一来源（filmic 前线性域）、预览域映射真 WYSIWYG、管线顺序定稿；MFNR 阶段一（4 帧 Bayer 域对齐 + 等权均值 + 逐通道亚像素）验收、阶段二 robust merge（mismatch + 一致性门控，手部达标）；C 专项噪声模型全链（σ²=A·x+B + 三摄标定，残余噪声 4.4×/2.2× → 1.22×/1.45×）
- [2026-09-21/24] **基础链奠基**：M2 RAW 端到端 + DngCreator 底片、RCD 三段 fragment 管线（12MP 1969→477ms）、LSC 接去马赛克后 CCM 前、P2 标准色彩链（ColorMatrix/ForwardMatrix 双光源）、M4 LUT 引擎（display 域、OETF 后）+ 无损导入导出、基础引擎三旋钮正交化、真 sRGB OETF、哈苏形 Reinhard 换底 → filmic 原生件接管
- [2026-09-18/20] **M0–M2 前半**：YUV 全链路（SurfaceTexture+OES 预览、全分辨率静拍）、M 档专业 UI、镜头发现与设置页、手动 WB = HAL 恒 AWB + 引擎侧补偿、变焦画质优先（±3% 迟滞）

## 品红/紫边与曝光（10-04 → 10-06，压缩）
- [2026-10-06] **机理闭环**：品红带 = B−G 边缘过渡错位（RAW 干净、融合赦免、P0 缓解、RCD 类固有）；紫边 A 窄峰/B 台灯品红分治；`[54]` pc3d 替换档转正、`[56][57][58]` 默认关存档；`tools/purple-fringe-kpi.py` 落地 | handoffs/品红带机理闭环与替换档转正.memo.md
- [2026-10-04] **曝光给足事故**：无饱和预算的给足在逆光窗 = RAW 饱和 4.2% + HR 品红洪水 + 白点失控——"曝光给足"类规则必须有饱和预算。

## 期 B：公共工作空间切 Rec.2020（10-19，压缩）
- [2026-10-19] **期 B 落地 + 三处域错配修复**（`3fa65af`→`a3aedfe`，包 0.11.47~49）：filmic LMS 整组 + CCM 终点切 Rec.2020；输出端域收口；预览/静拍输入补 `srgbToWork`。16 个守卫全绿。
- [2026-10-19] **真机验收通过②④**（饱和修正/[40] 隐藏/对比度）；**预览 vs 成片架构性差距不再挖**（根因 = 预览走厂商 ISP）。
- [2026-10-19] **HEIC 编辑器显示异常未复现**（"一坨五颜六色" = 0.11.34 同款霓虹伪彩；`ui/ImageDecode.kt` 对 HEIC 无特判已排查），用户"有出现再说"。

## 色相路线评估与 HDR 路线（10-19，压缩）
- [2026-10-19] **ACES 2.0 与 Oklab L-only 均不做**（D5 域口径修正后无色相优势 / ΔE 恶化）；sigmoid 回滚。
- [2026-10-19] **HDR 路线定案 = 按 PC 做**（libultrahdr `packageJpegR`，4:4:4 + gainmap；不做探针）。

## HDR 交付（10-20，已真机验收，压缩）
- [2026-10-20] **三条 HDR 路径落地**：JPEG 4:4:4（自研 stb base + libultrahdr）+ 4:2:0 与 HEIC（平台 API）；开关 `-DFILMKIT_HDR`；gainmap GPU 算（`kFsGainmap`，1/2 分辨率 8bit）。
- [2026-10-20] **真机验收通过**（系统相册 + App 内查看 + 导出 SDR 版 `nativeExtractSdrBase`）。
- [2026-10-20] **"高光顶白"归因自动曝光策略**（base 亮度 1.7~1.9×、≥240 6~9×，HDR 输出几乎相同）⇒ 单独开期（即后来的 P4）。

## 自动曝光（10-20 → 10-24 退役，压缩）
- [2026-10-24] **渲染端 AE 全链退役**：0.11.59-64 的 `AeMetering` 与 `g_still_ae_ema` 在 0.12.71 全删 ⇒ 曝光 = HAL + planner + baseEv + 用户 EV，逐张独立；0.11.64 的「burst 超时误杀」修复保留。

## 高光链路收口（10-22，用户已真机验收）

- [2026-10-22] **0.12.4→0.12.33 收口**：压深从白点档（第 8）挪到天空档（第 6）+ 删掉三层 wTop 事后开关 + 高光只压超出白点的部分 + 判据改用 AE 之后的 P95 + 去掉暗部无条件 +0.30 + `sat-fix` 进入条件 0.98→0.94 + `allSat` 用实测 luma + MFNR 频域合并修好并转默认开。**真机全部高光场景通过**（灰块/品红/乌云/亮区斑块全消）。tag `contrast1.5`，包 `filmkit-0.12.31-LTLOG.apk`。
- [2026-10-22] **暗部“发灰/发亮”的定性**：不是亮度（同区域只差 0.1~0.3EV），是**局部对比度低**（详见 facts 的实测基线）。候选根因 = 去马赛克档（我们 RCD、PC 为 AMaZE 系），**未验证**；用户决定先留到预设里调对比度。
- [2026-10-22] **相册闪退已修**：`ThumbCache` 的 `LinkedHashMap(accessOrder=true)` 连 `get` 都改结构，而 `put` 淘汰循环持迭代器 ⇒ 并发加载缩略图时 `NoSuchElementException`。全部方法 `@Synchronized` + 并发回归单测。同批加了 native 崩溃取证（`native-crash.txt`，信号号+故障地址）。

## 拍照/相册收口与 ISZ（10-22 晚，0.12.34→0.12.42）

- [2026-10-22] **M 档非原生焦段裁切**：HAL 对 RAW_SENSOR 不执行 `CONTROL_ZOOM_RATIO` ⇒ 交付前居中裁 `1/z`（`stillCropSize` 纯函数 + 单测；JPEG/HEIF/gainmap 全覆盖，变焦与画幅合成一次裁）。**成片保持裁切区原始像素，不插值**（12.5MP→6.4MP @1.9x）。
- [2026-10-22] **相册多选批量删除**：长按/「选择」进多选 + 按日期全选 + 全库红色警示 + 系统确认；删后**核实**（`mediaStillExists` 含 `is_trashed`）。曾试 `createTrashRequest` 进系统回收站，vivo 落成真删 ⇒ **不做恢复、不自建回收站**，文案统一"删除后无法恢复"。
- [2026-10-22] **缩略图丢失与查看页**：`lastPhotoUri` 改为「写盘 + `IS_PENDING=0` + EXIF」完成后再发布（原先 insert 后就发 ⇒ 偶发永久空白）+ 解码退避重试；胶卷位直开大图（可左右滑）、顶栏相册按钮靠左、日期严格居中；相册返回**直回相机**，删除后清 `viewerUris`。
- [2026-10-22] **ISZ 调研收口 + 镜头去重**：X300 主摄 `insensorzoomPlatformSupport=1` 且 remosaic key 可下发，但 set 成功后 RAW 尺寸/crop 不变 ⇒ **标准版主摄 ISZ 对第三方 RAW 不可达**（`vsdofFeatureOpticalZoomSets=0`）。**"保持原分辨率"不做**（PC 的做法是纯插值，默认关）。镜头表按等效焦距去重、物理优先（X300 的 0/2/6 都是 23.6mm，0/6 是逻辑相机）。

## 带天空偏暗的定性（10-23，0.12.43→0.12.61）

- [2026-10-23] **EV 补偿生效 + 渲染端 AE 四版回滚**（0.12.43→0.12.59）：+1EV ⇒ ISO 490→931（推翻 v0.7.8 结论）；预览链闭环与 HAL 的 AE 互相激发、固定目标 0.18 在暗场景必爆 ⇒ 全部回滚。**`ev_mid` 只提不压**（0.12.60）：压暗只由高光约束做，修掉双重欠曝。
- [2026-10-23] **EXIF 对拍定性 + 鲁棒测光判定不接**：PC 2.02ms 与我们 2.20ms @ISO50 ⇒ 采集一致，成片 p50 98.7 vs 171.4 差在 tone mapping；鲁棒档 186 样本中位 +0.16EV、散布 ±0.5 ⇒ 会让画面更暗，留在诊断位。
- [2026-10-23] **相册编辑器饱和度修复**（0.12.61）：`applyEngineScope` 把公共段 `[20]/[21]/[22]` 归零，编辑器照搬导致旋钮失效 ⇒ 新增 `applyEngineScopeForEditor`。方案定为用户自调 `baseEv`（提亮与保天空饱和在固定曲线上互斥，PC 同样更淡）。
- [2026-10-23] **bracket 跨度与噪声的权衡**（0.12.62→0.12.65）：短帧 −2EV 救不回日落天空（融合后 p99.5 3.1~5.4，白点 2.44）⇒ 放到 −3EV（ratio 8），噪点 ×8 明显 ⇒ 收到 **−2.6EV（ratio 6）**，噪点恢复。
- [2026-10-23] **白点 6.22 是雷**（0.12.49 埋、0.12.64 拆）：6.22 是曲线死白点，等于授权 AE 把天空推到死白（夕阳窗景 `aeEv` 2.0、天空 p95 246、8.6% 平顶）⇒ 回退 **4.8**（≈243 LSB，还能分辨）。
- [2026-10-24] **长帧过曝放宽必须回退**（0.12.63→0.12.66）：为"日落楼暗"给的长帧 +2EV 会在**阴天**同样生效（ISO 贴底是常态）⇒ 长帧 41% 饱和、成片 hi240 35.9%。跨张统计（看上一张 `p50` 决定是否放宽）被用户否决 ⇒ **整条回退**，曝光回到亮度守恒。

## HDR 真实 gainmap（10-25，P4 已真机验收）

- [2026-10-25] **P4 落地与验收**（0.12.85，提交 `2c57e34` tag v0.12.85）：基线重测固化
  `tools/hdr-baseline-eval.py`，旧 gainmap 三大指纹（p90 恒 0.533 / 码值 136 占 96.5% /
  码值 212 钉 2.5 封顶）→ `gainTrue`（真实跨曝光比值、中灰自锚定 ae_p50_/0.1845、
  clamp[1,4]）→ 真机 5 张指纹全消（128 针 79.7%、255 针 9%、HDR≥1.0 0.2~12.7% 追平
  PC 量级）。**待天空场景验收**（5619 复现与否唯一未测），预案 kHdrCeil。快照
  「P4真实gainmap落地验收与徕卡一瞬调研」。
- [2026-10-25] **方向锁死 bug**（0.12.86，`2cc055b`）：MainActivity 无 screenOrientation
  ⇒ 系统自动旋转转横窗 ⇒ 取景框/预览变形；修复 = Manifest 锁 portrait。横拍成片
  走 orientationStep 不受影响。
- [2026-10-25] **徕卡一瞬（M9）预设标定调研**（19 号调研，`dae7bb0`）：PC 固件考古
  配方（分支 3×3 矩阵/曲线/标定）解码验证；结论 = 现有引擎 + 烘焙 LUT（分支矩阵 →
  64³ 纯色彩 LUT）承载 CCD 色彩响应；11 张样张指纹库（p25≈21、≥240 中位 0.1%、
  C*P90 24.5 双向浓郁）；WB 锁定唯一新机制；柔黑不需要新槽位。**未动工，下一步**。

## 测光、AE 架构与 DCP 评估（10-24）

- [2026-10-24] **测光三档 + 长按锁定 + 渲染端 AE 清零**（0.12.67→0.12.74）：三档 = 系统默认 / 点测（PC 的 6% 框）/ 高光优先（PC 的最亮簇：32×32 网格 + top2% 阈值 + 5×5 簇 + EMA/8% 门限）；`AE_REGIONS` 与 `AF_REGIONS` 分离，点按只对焦；长按 = 对焦+曝光一起锁（黄框 + 1s 提示），点按自动解锁。**渲染端 AE 全删**（`g_still_ae_ema` + `decideAe` 不再参与曝光）⇒ 曝光 = HAL + planner + baseEv + 用户 EV，逐张独立、预览即成片。
- [2026-10-24] **DCP 调研评估 = 不做**：DCP 是相机原厂色彩身份、不是胶片 stock；跨相机套 HueSatMap 是二次校正；破坏 D8/D9 分工；Adobe camera matching 不可分发。AdobeCurve 降级为 P2 可选实验（hue-preserving + over-range 是它真正的价值，与 DCP 无关）。
- [2026-10-24] **HDR 曝光融合专项立项**：采集改 0/0/+2.2/−1.5 + Mertens 逐像素融合（PC 同款：`highlightValidity` >0.96 拒绝、deghost 黑白门、census mismatch），4 帧起步 6 帧封顶；设计 `docs/engine-hdr-exposure-fusion-design.md`，P0 从 0.12.75 起，开关默认关。
- [2026-10-24] **单次高光斑块（5902）不定案**：短帧满阱（`p999 1.0`）+ 手动压 EV −0.54 + `hlChroma B −0.318` 的重建边界；同参数 0612 正常。按纪律等复现，不改算法。

## HDR+ 包围曝光（10-24 晚，0.12.75-84）

- [2026-10-24] **P0-P1 诊断与采集**：`bracket.actual`（每帧实测 EV + RAW 抽样统计）；帧序 0/0/+2.2/−1.5（PC buildHdrBracketEvOffsets 同构、参考帧恒帧 0、AE 全帧锁）；`planBracketExposure` 照抄 PC planManualExposure（正 EV 快门 10ms 顶、钳后 ISO 补偿）。
- [2026-10-24] **P2 迭代后退役**：Mertens 侧帧直通（独立前链→D4→kFsHdrMertens，权重照抄 PC）三轮真机四坑（侧帧链跑 zero 数据、ISO 14336 数字增益、角色自创判定、高光斑块）→ 结构性不足（单帧 ISO 3200 读噪声 10×zero 且无时域）。
- [2026-10-24] **P3 架构对齐 PC RAW（定稿）**：4 帧原样进引擎，frame_scale[i]=2^−ev[i] 归一，全部进对齐+DFT 跨曝光堆叠（复用 satW 饱和权重/mismatch；零新 shader）；0.12.83 per-frame σ（B×(gr·s)²，长帧 gr≈9.3 不修则整帧回退）；0.12.84 UI 两档化（旧 bracket 开关删除、改名「HDR+ 包围曝光」默认开）。真机确认：hdrFs/confMed 0.833/brkRatio 2.83。