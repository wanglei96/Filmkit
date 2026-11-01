---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-11-01 18:45
status: active
---

# 项目状态

<!-- 2026-11-01 d3 整理：压缩历史段、合并里程碑、删除被取代的中间状态；新增「预设开关归位与理光负片」。 -->

## 目标
胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学，风格 = 用户提供的理光系 LUT。

## 结构
- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（预览渲染）+ StillRenderClient（拍照渲染）+ CameraAudit + `camera/StdColorMatrix.kt` + `ui/CameraPresets.kt`（FilmParams 槽位唯一来源，**kCount=102**）+ `ui/ParamControls.kt`（参数表单一来源，拍照与编辑器共用）
- `core/engine/`：C++20 引擎——render/（FrameRenderer + Align）、gl/、spike/（SpikeFftMerge）、log/、jni/Bridge.cpp、`film/FilmParams.h`、`film/EngineRegistry.{h,cpp}`
- `assets/`：`luts/` 44 个内置 .lut（42 个 33³ + `leica_m9_ccd` 128³ + `ricoh_negative_v2` 64³）+ `noise_models/` 三摄标定 + `presets/builtin.json` 三条内置预设
- `tools/`：validate-shaders.sh、check-diag-fields.py、check-log-json.py、bake-m9-lut.py、bake-ricoh-lut.py、preset-exp/recipe-fit.py、hdr-baseline-eval.py 及调参工具族

## 进行中（长期挂起）
- [2026-10-07] 亮部亮度降噪（对标 PC 唯一成像短板）、超广畸变自校正（**待拍板**）；[2026-10-30] HDR 205 共存包观感对照未做

## 里程碑沿革（压缩）
- [2026-09-18→27] **M0–M2 与基础链**：YUV 全链路 + M 档 UI；RAW 端到端 + DngCreator；RCD 三段 fragment（12MP 1969→477ms）；LSC 接去马赛克后 CCM 前；P2 标准色彩链；M4 LUT（display 域、OETF 后）；真 sRGB OETF
- [2026-09-27→30] **域映射与 MFNR**：EV 单一来源、预览 WYSIWYG、管线顺序定稿；MFNR 4 帧 Bayer 域对齐 + robust merge；C 专项噪声模型（残余 4.4×/2.2× → 1.22×/1.45×）
- [2026-09-30] **对齐/P0 亚像素 + 性能**：最细层 1/4→1/2 + 逐通道网格亚像素（同帧降噪 +43.7%/+46.5%）；DFT 合并（目标必须 glTexStorage2D）；快门间隔 2.5s→0.54s
- [2026-10-02/03] **曝光决策 + 降噪链 + 影调固定**：曝光 ×N 收益 √N；σ 恒真值；三摄 GCam 标定；NN 判死转 HDR+；影调白点四旋钮全撤（唯一固定 darktable 默认）
- [2026-10-06] **品红/紫边机理闭环**：品红带 = B−G 边缘过渡错位（RCD 类固有）；[56][57][58] 默认关存档
- [2026-10-08] **边缘彩边专项**：`[59]` color_smooth 移植（判据 peakK 1.05 / sideK 0.5 / 尺度 [3,6,12]）
- [2026-10-13/14] **引擎插槽阶段 0–3**：`kFsFilm` 单真源切片 + `[98]` 契约 → sigmoid 引擎 → 全局切换（`EngineScope.kt` 14 槽）；判据 0（新引擎必须配正向判据）；包 0.11.29
- [2026-10-19/20] **期 B 域切换 + HDR 交付**：工作空间 Rec.2020 线性、交付 sRGB；三条 HDR 路径（4:4:4/4:2:0/HEIC）+ gainmap GPU 1/2 分辨率
- [2026-10-22] **高光链路收口 + 相册/拍照收口**：压深挪到天空档、删三层事后开关、判据改 AE 后 P95、sat-fix 0.94、MFNR 频域合并转默认开；M 档裁切、相册多选删除（vivo 回收站不可用）、ISZ 不可达
- [2026-10-23] **带天空偏暗定性**：EV 补偿生效（+1EV ⇒ ISO 490→931）；EXIF 对拍证明采集一致、差距在渲染链；`ev_mid` 只提不压；bracket 收到 −2.6EV；白点 6.22 是雷 → 4.8
- [2026-10-24] **AE 退役 + 测光三档 + HDR+ 立项**：渲染端 AE 全删（0.12.71）⇒ 曝光 = HAL + planner + baseEv + 用户 EV；三档测光 + AF/AE 分离 + 长按锁定；HDR+ P3 定稿（4 帧进引擎、零新 shader）
- [2026-10-25/26] **P4 真实 gainmap + 徕卡一瞬**：`gainTrue` 中灰自锚定（后改固定 2.56）；`bake-m9-lut.py` 把固件配方烘 128³ LUT；影调重标三版（双链 14.75 → 单链 0.51 → 真机 p50 +9.3 反馈后重拟合 3.13）；发黄 = AWB 波动被 LUT 放大
- [2026-10-28/29] **灰斑/绿晕/渗水专项（0.12.102→177，约 60 包）**：定案两件——Bento collar flow 自 0.12.122 起是 no-op（161 修复）；锐化过冲 ±0.4→±0.12（163）让渗水边回到可接受。灰斑本体未定案；D4 大半径 USM 是错误方向已默认关
- [2026-10-29/30] **天空品红 + 高光三症**：回 WB 域重建（nsat 参考比）收口品红/灯面/窗台；`satmHit=0` 是 `jnum(x,0)` 假象，掩码一直在工作；品红/绿边由「短帧 2×2 块整块接管」修掉（196 漏乘通道 gain → 197 修复）；叶子糊层 = teq 压深，关掉即好；HDR 增益锚定漂移 → 固定 2.56（观感选择、非规范语义）

## 预设开关归位与理光负片（11-01，0.12.208→214）

- [2026-11-01] **一类 bug：预设数组 vs prefs 脱钩**：`setFilmParams` 换数组后只有 5 个 applier 缓存有哨兵，其余 21 个「值没变不写」⇒ 基线值静默生效（027 的 local_tone 0.25 / color_smooth 0 / sat 0.76 三处同源）。已全部置哨兵；`neutral()` 的 [36]/[42]/[44]/[59] 对齐设置页默认。
- [2026-11-01] **开关归设置页、饱和度归预设**：删设置页「饱和补偿」滑杆与 `applySatCompPref`；ESD3D2 默认回替换档（3 处兜底 + 文案）；**只有徕卡一瞬锁 engine_slot=filmic**，理光/黑白待重标不锁。
- [2026-11-01] **三段饱和拆三条滑杆**：`satShadow/satMid/satHigh` 各一槽；基础档 10→12（三行四列、清晰度最右）；徕卡三段按用户定档 1.22。
- [2026-11-01] **理光负片重标**：19 张官方标准样片 + 2 组 Positive↔Negative 配对实测（灰轴暗暖亮冷、蓝 ×0.67、绿 +13°），重烘 64³ `ricoh_negative_v2`；亮度传递显示 Negative≈Positive ⇒ 曲线用引擎默认；预设压到「sat 0.92/0.90/0.80 + 黑白点 −8/+4 + LUT 1.0」。
