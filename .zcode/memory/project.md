---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-10-05 20:30
status: active
---

# 项目状态

<!-- 2026-10-05 d3 整理：全量吸收 37 份快照 + 5 份归档，压缩折行、删除被取代的中间状态。 -->

## 目标

胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md（需求基线 v2.0 §0 D1–D13）。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学（追平 Adobe/libraw 标准链），风格（浓淡、胶片味）= 用户提供的理光系 LUT（M4）。

## 结构

- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（预览渲染线程）+ StillRenderClient（拍照渲染线程，v0.10.85）+ CameraAudit + `camera/StdColorMatrix.kt`（DNG 标准链）+ `ui/CameraPresets.kt`（FilmParams 槽位唯一来源，kCount=56）
- `core/engine/`：C++20 引擎——render/（FrameRenderer + Align）、gl/（EglCore/Capabilities）、spike/（SpikeFftMerge）、log/（JSONL+GPU 计时）、jni/Bridge.cpp、`film/FilmParams.h`
- `docs/`：开发计划（§0 需求基线 D1–D13）、x300-能力审计.md、research/（12 个编号调研：PhotonCamera 管线 / darktable / RawTherapee / Blender AgX / ACES-libplacebo / 相机外观引擎 / filmulator / ISP 标准链与 AE·MFNR / 09=频域降噪 / 10=ARRI 引擎计划 + 内建编辑器与预设）
- `app/src/main/assets/`：`luts/` 42 个内置 .lut（uint8、33³、4.3MB，格式 `[size][titleLen][title][catLen][category][data]`）+ `noise_models/` 三摄标定（GCam .c 格式）
- `tools/`：validate-shaders.sh、check-diag-fields.py（出包前必跑）、check-log-json.py（分析前先跑）、still-thread-verify.py+run.sh、align-bench、p0-subpixel-eval、ab-100pct、chroma-scale-eval、texture-fidelity-eval、denoise-strength-eval、check-fft-tile-cover、chroma-denoise-eval、tone-scan、gen-lut-assets、dng-color-dump、color-metrics、ccm-chain-ab、check-srgb-oetf、check-lsc-mapping、lut-audit、tuning/

## 进行中（2026-10-06）

- [2026-10-06] **紫边两类分治排查（主场景 = 台灯逆光）** | 进行中 | **色散已定案 = 管线产生**（DNG RAW 域三通道对齐、成片 dRG +0.9~1.0px；非光学 LCA 非 [51]，治法在 RCD 相位）；A 类 4px 窄峰红笔场景锐化是主放大器（结论不外推）；B 类台灯窗框品红窄峰 +10@3px 主因未定。**待办**：要 13:5x 日志 → 主场景单变量矩阵重跑；补拍"pc3d 关+锐化关"组合轮定 `[54]` 去留（真机两轮关闭优于叠加、与离线矛盾） | handoffs/紫边排查翻案与色散定案.memo.md
- [2026-10-06] **[51] κ 下调与 defringe 重估** | 待数据 | κ0 紫边更低（+1.15 vs +1.89）且与色散无关；defringe = 压绿边/红渗有效、代价紫边升——均待主场景重测后定 | 同上
- [2026-10-05] **低频色度噪声云（曝光给足事故）** | 待拍板 | 根因 = 4.8ms 短曝光（ISO 下限锁死 Planner 拉长）；方案 A 无饱和预算毁图（RAW 饱和 4.2% + HR 品红洪水）已回滚；**修正方向待拍板**：有界给足（饱和预算）/ D-1 低频扩展 / 暂缓 | handoffs/紫边归因闭环与对标基准修正.memo.md（已 absorbed）
- [2026-10-03] **亮部亮度降噪** | 挂起 | 对标 PC 唯一成像短板（亮部 σY 1.67×）；P0 bilateral 全图只降 21~32%，亮部为何更弱待查（`σ²=A·x+B` 在亮部更大 ⇒ 权重应更大，实测相反）| handoffs/亮部降噪短板定位与小波重建待修.memo.md
- [2026-10-03] **超广畸变自校正** | 待拍板 | HAL 路线已全关 ⇒ 必须引擎 GPU 自校正（去马赛克前、逐 CFA 平面反向映射保相位）；**需拍板**：完整校正（视场窄 5~10%）/ 部分校正 / 不校正 | handoffs/超广探测高光保护与白斑攻坚.memo.md
- [2026-10-04] **内建编辑器与预设** | 待立项 | 调研完成（docs/research/10 号）：编辑器与预设一体做、同一参数源；预设 schema 增量（颗粒调制/halation/暗角/趾肩/层间效应）；E1–E5 待立项，优先级低于成像缺陷 | handoffs/设置页抽屉化与光圈图标落地.memo.md
- [2026-10-03] **影调 whiteEv 默认值** | 低 | 4.0（darktable 默认、有白点）vs 6.x（白点消失、更柔）等用户多场景试后定
- [2026-10-01] **M5 诊断与基准** | 低 | 逐 pass GPU 计时、成像会话摘要、一键诊断包、性能基准脚本 + golden image 接 CI | decisions.md「色彩链、管线与 MFNR」

## 里程碑沿革（压缩）

- [2026-10-06] **v0.10.177（未打 tag）**：`[54]` 默认叠加档（新 KPI 重估建议；真机两轮关闭优于叠加，去留待组合轮）、`[51]` bool→κ 滑杆 0..2（prefs bool→float 兼容）、`tools/purple-fringe-kpi.py` + `docs/紫边窄峰真机AB方案.md` + `docs/reports/` 存档（27 处 /tmp 引用改写）；提交 c5f1475 / a503d7e，包 `filmkit-0.10.177.apk`（316）
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
