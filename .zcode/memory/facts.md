---
summary: 环境约束、关键路径与可复用命令
created: 2026-09-18 10:20
updated: 2026-10-26 10:17
status: active
---
# 事实
<!-- 原子事实:环境约束、关键路径、可复用命令、版本号。一条一事,带日期前缀。 -->
<!-- 2026-10-26 d3 整理:压缩折行、删除被后续版本取代的"当时现状",保留当前有效值与口径。 -->
## 环境
## 路径与约定
## 引擎实现现状
## PhotonCamera 参照事实（/tmp/photoncamera、/tmp/pc-full）
## 对标数据与测量口径（10-03 → 10-08）
## 相机能力与标定
- [2026-10-22] **X300 后置 camera id**：0 = 逻辑主摄（0.67–10x）；2 = 物理主摄（RAW 4080×3072，SupportSATRemosaic=1）；3 = 长焦 70.3mm；4 = 超广 15.4mm；5 = 潜望 165mm；6 = 逻辑组（0.6–100x）；三个 23.6mm = id 0/2/6
- [2026-10-22] **ISZ**：remosaic key 全可下发且 set 成功，但 RAW 尺寸/crop 不变，`vsdofFeatureOpticalZoomSets=0` ⇒ 标准版主摄不可达
- [2026-10-22] **相册/删除事实**：`createTrashRequest` 在 vivo 落成真删；`mediaStillExists` = 单条 URI query + `is_trashed`；`saveJpeg` 写盘 + `IS_PENDING=0` + EXIF 完成后才发布 `lastPhotoUri`；`ThumbCache` 全方法 `@Synchronized`
## 相册与预设（10-11 → 10-13）
## 引擎插槽与调研（10-13 → 10-14）
## HDR 交付与自动曝光（10-20，压缩）
- [2026-10-20] **gainmap 数学两版可切**（prefs `gainmap_math`）：0 = PC（offset 1e-4）/ 1 = 官方（offset 1e-7 + sdr<2/255 钳 gain≤2.3）；都归一化 [0.25,4.0]
- [2026-10-20] **App 内 HDR 显示三条件**（`inSampleSize=1` + `findActivity()` 解包 + `colorMode=COLOR_MODE_HDR`）；**导出 SDR** 用 `uhdr_dec_get_base_image` 无损取 base。
- [2026-10-20] **AE 判据常量**：`kAeRawWhiteEv 2.438307`、`kAeMiddleGrey 0.1845`、headroom 0.1、限幅 ±2EV、高光判据 P99.5；**渲染端 AE 已在 0.12.71 全删**（只剩 ExposurePlanner）
- [2026-10-22] **teq 9 档**（q=i−8 EV；云第 8 档、天空主体第 6 档，压深只给第 6 档）；**局部对比度基线**：rawpy 19.4% / 我们 17.6% / PC 25.9%。
- [2026-10-24] **测光实现**：`MeteringTargets.kt`；`SPOT_AE_FRACTION=0.06`、`HIGHLIGHT_AE_FRACTION=0.16`；高光点来自 32×32 网格
## P4 基线与徕卡一瞬（10-25/26）
- [2026-10-25] **`tools/hdr-baseline-eval.py`**：JPEG/R 无损解析（MPF 对照 vendored libultrahdr）+ base/gainmap/HDR 重建统计；报告 `docs/reports/hdr-baseline-20261025-REPORT.md`
- [2026-10-25] **P4 现状**：`kFsGainmap` 三档（0=真实比值/1=旧 sceneY/2=官方）；`gainTrue` clamp[1,4]；0.12.230 起改为 `sdrLin + max(0, hdrLin − 1/anchor)`（标准语义，中灰 ratio 恒 1）
- [2026-10-25] **LUT 加载链**：`parseBinary` size∈2..128；引擎 `setLut` → `glTexImage3D RGBA16F size³`；采样 = `clamp(c×(N−1))` + 手写三线性 texelFetch（格点口径 i/(N−1)）
- [2026-10-25] **色温槽语义**：`[88] temp_mired` = 目标白点 mired 偏移（基准 6500K、恒等 0，`EditorExport.kt:495`）；UI K 滑杆右移 = 变暖；**6500K = 不做补偿，不是锁 WB**
- [2026-10-25] **recipe-fit**：`--mode camera` 单链（TARGETS[leica_m9] 六点）；`--white-ev 4.0 --lut-min 1.0 --lat-split --hi-floor 3.5`；`--export` 生成 `fit/builtin-curves.json`
- [2026-10-25/26] **真机指纹**：M9 样张库彩色 p25 中位 20、≥240 0.08%、C\*P90 ≈29；我们一期 C\*P90 33.2、≥240 0.02%；MainActivity 锁竖屏（0.12.86 起）。
## PC 影调与暗部软边（10-29）
- [2026-10-29] **PC 有两套 tone 引擎**：`AdobeCurveToneAlgorithm`（DCP profile 的 ToneCurve，逐通道 + `adobeRgbTone`）与 `DarktableFilmicToneAlgorithm` / `DarktableSigmoidToneAlgorithm`（与同源，系数按 darktable `computeFilmicToneCurveUniforms` 算）；`assets/dcp/` 只有 10 个 profile，**没有 vivo** ⇒ 该机上不适用 DCP 路径
- [2026-10-29] **亮缝宽度实测**（FILMKIT_20261029-100221 红框 x1626-1942/y3139-3677）：RAW 2×2 块 34px（0.035→0.150）、我们 D4 26px、成片 24px、libraw AHD 14px；对比度 RAW 4.3× / D4 4.8× / 成片 1.5× / AHD 2.6×
## 高光三症与饱和掩码（10-30）
- [2026-10-30] **sat mask 判据**：`wb >= gain[c] − 3σ`，1px 膨胀 + 2×2 聚合，日志 satmP/satmRaw/satmHit/satmCov。
- [2026-10-30] **gainmap 读数口径**：`raw.render` 的 gm* 恒 0（设计）；真值在 `gainmap.readback` 的 gmMin/gmMean/gmMax，`ratio = 0.25 × 16^g`。
- [2026-10-30] **徕卡标定边界**：`recipe-fit.py` 从 filmic 之后接起，RAW/CCM/WB/kFsFilm 的 sat·split·mono 是"未验证段" ⇒ 预设不在饱和度补偿之后标定。
- [2026-11-13] 版本：**设备实际 0.13.38(746)**；最新包 0.13.40(748) **未安装成功**（install 被 echo 掩盖，见 lessons）；0.13.0(708) 是最后打 tag 的版本；**7 条内置预设**（键总 100）；自烘 64³ LUT 三个 + `ricoh_negative_ye` 33³。
- [2026-11-02] **`diag.stats.sat` = `[20][21][22]` 的实际下发值**（判断预设饱和是否生效的唯一日志字段）；`setFilmParams` 换数组后必须把 21 个 applier 缓存置哨兵；改 assets 后单测必须 `--rerun-tasks`。
## 预设群与 LUT 特征（11-02）
- [2026-11-02] **理光正片 / 富士NC**：`ricoh_positive_v1` 灰轴 暗 +1.36 / 中 −1.06 / 高光 +0.64，色相 红 29°/绿 134°/蓝 303°；`fuji_nc` 原资产压白 8.2%，`fuji_nc_v2` 归一后 L\* 恒等 0.26、白点 1.43/255。
- [2026-11-02] **哈苏自然 / 柯达5219**：哈苏探针色相 0、灰轴 0、肤 C×1.23，官方 13 张 C\* p50 11.3；5219 原资产抬黑 4.7%、`kodak_5219_deep` 暗 b −6.2、黑点 0.5/255。
- [2026-11-02] **暗角与资料入口**：darktable 余弦 `vw=0.5−0.5cos(πt)`、`dscale=radius/100`、`fscale=feather/100`，4:3 竖图角落 cplen 1.252；M9 机内默认开暗角校正。柯达样片 `kodak.com/en/motion/product/camera-films/<id>/`（单数 product），哈苏 `hasselblad.com/learn/sample-images/x-system/`，fujifilm-x.com 是 SPA 抓不到。
## 显示域闭环与蓝天（11-04）
- [2026-11-04] **显示域闭环实测**（成片中心 1/2 鲁棒中灰 vs 取景器 `pvDispMid`）：0.1375/0.130、0.0997/0.093、0.0344/0.034 ⇒ 差 0.02~0.13 EV；改前 0.9 EV。日志字段 `dispMid`/`dispAdd`/`dispErr`（0.12.233 起拆开）与 `preview.filmic` 的 `pvDispMid`。
- [2026-11-04] **闭环收敛数据**：15:42:29 `dispAdd 1.18 → dispErr −0.9`（全量步进过冲）；0.7 阻尼+两轮后 16:42/16:49 的 `dispErr` 均在 0.04~0.09。
- [2026-11-04] **PC ML-AE 规格**：`assets/mgc_ae/` 三个 tflite（short 62272B / long 61264B / portrait 62272B）；输入 64×64×9（`ln RGB` + `ln scene` + 语义哨兵，long 拆 4+5 两输入）；输出 logGain，`gain = exp(logGain) − 1e-6`；Mac CPU 三模型一套 6 ms。
- [2026-11-04] **官方理光负片蓝天**：晴天深蓝天 `IP025159` h 270.9 / C\* 17.8 / L\* 46.8；同场景正片 `IP025354` C\* 25.2；阴天街景（6509 第 2 格）h 258.1 / C\* 13.1 / L\* 77.8。样片存 `test_temp/_official_{neg,pos}_sky_*.jpg`。
- [2026-11-04] **LUT 判据 3 改口径**：p99.9 <12（原 max<12 永远被 sRGB 色域边界顶到 29.3）；当前 p99.9 9.3、max 22.8、边界对 166 万个已排除。
## LUT 换血与采集端判据（11-11）
- [2026-11-11] **`ricoh_negative_ye.lut`**（33³、107846 B、title「理光 负片 · 杨子祯」）：由「理光负片 杨子祯(1).cube」逐字节转换，**自带影调**——灰阶 0.25→0.31 / 0.50→0.57 / 0.75→0.78。同场景天空 hue 260.8（旧的归一版 276.2、Filmix 263.2）。
- [2026-11-11] **采集端判据两处修正**：`nativeEngineGetStats` 新增 `pw999`（当帧预览链 P99.9 ÷ `g_domain_ratio` 换算到 RAW 等效域；实测预览 1.03 vs 成片 2.91，低 1.88EV 因 ISP tonemap ⇒ 只当"有没有超白点"的粗判据）；`hlOn` 条件只剩 `hl_protect >= 1`（原来还要求 `!ae_auto && !bracket && hdr_fusion<=0` ⇒ 出厂恒假、从未生效）。
## 天空闭环与诊断（11-12）
- [2026-11-12] **预览 vs 成片的 filmic 输入中位**：`pvLastMid 0.1267 / stillMid 0.0276 = 4.59`（+2.2EV）⇒ vivo 的 YUV 去 gamma 后仍被 ISP tonemap；`ratio` 日志（`g_domain_ratio`）实测 2.0→4.59。**闭环护栏参数**：每步补 EV 后测成片顶部天空 P99（`skyProfile` u8），涨 >0.03（≈8 LSB）或 >0.88 就撤销该步；硬上限 `kDispAddMax 0.60`；`dispErr` 只记账不递归。
- [2026-11-12] **诊断开关**：prefs `sky_diag`（默认 false）→ `nativeEngineSetSkyDiag` → `g_sky_diag`；控制 `skyD4/skyFm/skyOut/d4Y/fmY`，关闭时零额外读回；闭环护栏的 `skyP99` 不受控。工具 `tools/sky-diag.py` 读三段表。
- [2026-11-12] **去马赛克调研（PC / Spektrafilm / 开源）**：PC 的 RAW 去马赛克**不是独立级**——MGC Spatial/Sabre 融合内置全分辨率绿重建 + VGN；源码 `RcdDemosaicAlgorithm.kt` **零引用**（死代码），AMaZE 只在 LibRaw（`dcraw_process.cpp:189` 的 quality=14），服务 `processDngNative` 的 DNG 文件路径；`RawLinearRcdPass` 只做 CCM+gain（命名误读，06 号文档已改）。Spektrafilm（rawpy）/SpektraLab（C++ libraw）都只是 LibRaw 调用方，**无自研 demosaic**；开源无成熟 GPU 高质量实现。我方 RCD 方向场是全分辨率、**从无画质 A/B**（RT 是 `tileSize²/2` + lpf 输入）；v0.5.29 的 `dirMs 54ms` 与现在 0.13.5 实测的 65–85ms 同量级——旧的 1202–1433ms 是「HR+P0+P1」合计（计时口径错）。详见 `docs/reports/raw-demosaic-benchmark-RESEARCH.md`。
- [2026-11-12] **成片链真实耗时剖面（0.13.6/0.13.9 冷机，4080×3072 4 帧 MFNR+HDR，同场景支架固定）**：`fuseMs`(DFT) 1201–1561、`p0Ms` **300–352**、`hlMs` 212–250、`preMs` 949–1223（含 ~340ms 未归因的 satmask/inpaint）、`dnMs` 542–1131、`dirMs` **39–66**（GPU 31–66）、`gMs` 9–33、闭环 `dispMs` 378–423、编码 **189–290**（libjpeg-turbo）、`totalMs` 5162–6523、快门→落盘 **6.15–7.27s**。**RCD 不是热点**；热点 = DFT / P0 / P4。预览体感：快门后 4 次 218–264ms 画面冻结（录屏实测），`preview_gap` 的 400ms 阈值会漏报；`preview_arrive_gap` 与它同时出现才算 HAL 停帧。
- [2026-11-12] **0.13.16(724) 当前**：`mfnr_dft` 默认 **false**（空间域 mismatch+fuse），设置页可切回 DFT；`files/mfnr_spatial` 哨兵强制 DFT，`files/burst_dump` 导出 4 帧 `.raw/.meta`（25MB/帧）。
- [2026-11-12] **融合两条链实测**（同场景同曝光）：明亮 ISO 1163 DFT 6033ms → 空间域 4233ms；暗光 ISO 662 DFT 8000ms → 空间域 5240ms。暗部平场 σ 2.08→1.71 LSB、全局高频 −18%，100% 裁剪无可见差。
- [2026-11-12] **EGL_IMG_context_priority 在 X300 可用**：`eglPriExt=1, eglLow=1`（still context 以 LOW 创建）；`engine.still_start` 日志带这两个字段。
## 预览体感实测（11-13）
- [2026-11-13] **warmup 实测**：相机页可见 2s 后启动，冷启动 `engine.still_warmup` 992~1197ms（全量 ensure + 35 个 FBO clear）；第一张 3805→3129ms、与热态差 1261→398ms；teq/hl 与热态一致；剩 323ms 在采集侧 `upload`。
- [2026-11-13] **保活实测**：停 87s 不重建；HOME 立即 `render.still_release {"why":"lifecycle"}`；回前台重建 + warmup（init 204ms + warmup 333ms）。
- [2026-11-13] **fence/时间片实验**：在飞 2 的 total 3124 vs 3121、fps 不变；sleep 10ms/30ms 让路 total +231ms、fps 不变 ⇒ 掉帧不是队列深度造成。
- [2026-11-13] **PC 实测**：专业模式 RAW 拍照 tap→落盘 6.59s（我们 3.2~3.5s）；PC 无帧率日志、`SurfaceFlinger --latency` 对 BLAST layer 无效。
- [2026-11-13] **界面动画**：相机页请求最高刷新率（`preferredDisplayModeId`），`dumpsys display` 实测 `appRequestBaseModeRefreshRate=120`；快门飞入 560ms、动画结束即 `resumePreviewNow`（`preview_gap` 607~633ms）。
## HDR 灯盘实测（11-13）
- [2026-11-13] **4 帧 RAW 真实饱和率**（`files/burst_dump` 导出的 f0-f3，4080×3072）：f0/f1 参考帧 1.84%、f2 长帧(+2.2EV) 13.2%、f3 短帧(-1.5EV) **0.02%**；离线复算融合权重 f0/f1/f2=0、f3=0.98 ⇒ 融合后灯面 scene p50 1.52 / max 3.94。
- [2026-11-13] **灯面白的三段数据**：融合后 1.52 → 白点 2.95（whiteEv 4.0 相对中灰 0.1845）→ filmic 输出 0.80 线性（8bit 232）；PC 同场景 base 139。`satmCov 3.15%`、`hlCnt 94189`、`wmax 4.87`。
- [2026-11-13] **预览统计与 RAW 的偏差**：`hl_stat` 实测 `pw999=0.889 / p999pv=0.6835 / dom=2.0 / wmax(预览)=1.97`；换算 `log2(p999pv/dom/0.1845)` 与日志逐位一致。历史 `pw999` 在同一场景抖动 0.10~5.65。
## 预设覆盖层与 HDR 对齐（11-14）
- [2026-11-14] **「标准」预设漏洞的实际数值**：logcat `save_slots slots=2 "engine_slot=0.0;auto_white_pt=1.0"` 后同一毫秒 `save_full target.slots=0` —— 写进去了但列表里查不到。
- [2026-11-14] **gainmap 修正前后**：改前 `gmMean 0.79`（ratio 2.23×，HDR 重建 p50 0.140）；改后 `gmMean 0.51`（1.01×，HDR p50 0.137 = base）。PC 同场景 `gmMean 0.509`、boost p90 1.04×。
- [2026-11-14] **灯面灯罩边缘的物理渐变**：缩略图径向 r=140→200，亮度 204→130（60px 过渡），灯面核心 247 顶白；HDR ratio 在这段从 4.0 降到 1.18。
- [2026-11-14] **紫边判据修正效果**：`min(R−G,B−G)` 峰值 **85 → 11**、`>20` 的像素 **0 个**（SDR base 实测）。
- [2026-11-14] **film spline 白点外回落**（`filmic-spline-ref.py`，black −7.82 / white 4.56）：e=1.02→0.998、1.10→0.949、1.20→0.768、1.30→0.417、1.50→**−1.001**。
