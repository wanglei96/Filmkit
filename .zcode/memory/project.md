---
summary: FilmKit 胶片相机:RAW/YUV 双输入,M 档专业拍摄,X300 专项
created: 2026-09-18 10:20
updated: 2026-11-16 15:59
status: active
---

# 项目状态

## 目标
胶片相机 App（Android 首发，vivo X300 专项适配）：Camera2 RAW/YUV 双输入 → 引擎胶片风格化（基准色彩科学 + 理光系 LUT 风格层）→ 全分辨率成片。核心卖点：M 档专业拍摄 + RAW 底片 + 胶片渲染全 GPU。里程碑 M0–M6 见 docs/开发计划.md。**色彩口径（D8/D9）**：基准 = 中性正确的色彩科学，风格 = 用户提供的理光系 LUT。

## 结构
- `app/`：Kotlin/Compose UI（相机/设置/能力/日志四 Tab）+ CameraController（Camera2 双流）+ RenderClient（预览渲染）+ StillRenderClient（拍照渲染）+ CameraAudit + `camera/StdColorMatrix.kt` + `ui/CameraPresets.kt`（FilmParams 槽位唯一来源，**kCount=102**）+ `ui/ParamControls.kt`（参数表单一来源，拍照与编辑器共用）
- `core/engine/`：C++20 引擎——render/（FrameRenderer + Align）、gl/、spike/（SpikeFftMerge）、log/、jni/Bridge.cpp、`film/FilmParams.h`、`film/EngineRegistry.{h,cpp}`
- `assets/`：`luts/` 46 个 .lut（33³ 为主 + `leica_m9_ccd`128³ + 3×64³ 自烘 + `ricoh_negative_ye` 33³）+ `noise_models/` 三摄 + `presets/builtin.json` **七条**预设；`tools/`：validate-shaders / check-diag-* / bake-{m9,ricoh}-lut / normalize-lut-luma / recipe-fit / tuning 工具族

**当前（2026-11-16 15:59）**：0.14.20(817) 已安装、提交并推送；性能目标 3--5s/优选≤4s
与拍照期间预览几乎不掉帧**仍未达成**。已落地的相关优化：预览回调专用线程 + `PreviewFrameGate`、
去重时间戳、拍照不再主动暂停预览、实际 EGL 优先级查询、首拍大纹理接入 warmup、协方差 slot3
生命周期复用、histogram 固定几何权重缓存、mismatch 条带、highlight guide 当前调用行缓存。
0.14.20 保存中位 5.599s，PC3D 仍 1.8--2.3s；下一轮重点 = PC3D/共享 GPU 调度、warmup 分配争用、
RAW producer 空档、histogram 同步等待。0.14.19 云边品红已由两张长焦天空实拍验收；扩展场景仍待验。
旧账：Spektrafilm 引擎 + 预设标定、理光目标曲线重标、绿植/绿段、亮部亮度降噪、超广畸变。

## 里程碑沿革（压缩）
- [2026-09-18→30] **M0–M2 + 域映射 + MFNR + 亚像素**：YUV/RAW 双链、RCD 12MP 477ms、LSC 接去马赛克后 CCM 前、M4 display 域 LUT；EV 单一来源与 WYSIWYG 定稿；MFNR 4 帧 Bayer 域对齐（残余 4.4×/2.2×→1.22×/1.45×）；P0 亚像素 +43.7%/+46.5%、快门间隔 2.5s→0.54s
- [2026-10-02→08] **曝光/降噪/影调 + 品红紫边**：曝光 ×N 收益 √N、σ 恒真值、三摄 GCam 标定、NN 判死转 HDR+；影调白点四旋钮全撤；品红带 = B−G 边缘错位（RCD 固有）、`[59]` color_smooth 移植
- [2026-10-13/14] **引擎插槽阶段 0–3**：`kFsFilm` 单真源切片 + `[98]` 契约 → sigmoid 引擎 → `EngineScope.kt` 14 槽全局切换；判据 0（新引擎必须配正向判据）；包 0.11.29
- [2026-10-19/20] **期 B 域切换 + HDR 交付**：工作空间 Rec.2020 线性、交付 sRGB；三条 HDR 路径 + gainmap GPU 1/2 分辨率
- [2026-10-22] **高光链路收口 + 相册/拍照收口**：压深挪到天空档、删三层事后开关、判据改 AE 后 P95、sat-fix 0.94、MFNR 频域合并转默认开；M 档裁切、相册多选删除（vivo 回收站不可用）、ISZ 不可达
- [2026-10-23] **带天空偏暗定性**：EV 补偿生效（+1EV ⇒ ISO 490→931）；EXIF 对拍证明采集一致、差距在渲染链；`ev_mid` 只提不压；白点 6.22 是雷 → 4.8
- [2026-10-24] **AE 退役 + 测光三档 + HDR+ 立项**：渲染端 AE 全删（0.12.71）⇒ 曝光 = HAL + planner + baseEv + 用户 EV；三档测光 + AF/AE 分离 + 长按锁定；HDR+ P3 定稿（4 帧进引擎、零新 shader）
- [2026-10-25/26] **P4 gainmap + 徕卡一瞬**：`gainTrue` 中灰自锚定（后改固定 2.56）；固件配方烘 128³ LUT；影调重标三版（14.75→0.51→3.13）；发黄 = AWB 波动被 LUT 放大
- [2026-10-28→30] **灰斑/渗水 + 天空品红 + 高光三症（0.12.102→205，约 70 包）**：Bento collar flow 自 0.12.122 起 no-op（161 修）；锐化过冲 ±0.4→±0.12（163）；品红/灯面/窗台回 WB 域重建；品红绿边由「短帧 2×2 块整块接管」修掉（197）；叶子糊层 = teq 压深。灰斑本体未定案，D4 USM 已默认关

- [2026-11-01] **预设数组 vs prefs 脱钩（一类 bug）**：`setFilmParams` 换数组后只有 5 个 applier 有哨兵，其余 21 个「值没变不写」⇒ 基线值静默生效（027 三处同源）；已全部置哨兵。
- [2026-11-01] **开关归设置页、饱和度归预设**：删设置页饱和补偿；ESD3D2 默认回替换档；只有徕卡一瞬锁 engine_slot。
- [2026-11-01] **三段饱和拆三条滑杆**（`satShadow/satMid/satHigh`，基础档 10→12；徕卡 1.22）；**理光负片重标**：19 张标准样片 + 配对实测重烘 64³，预设压到「sat 0.92/0.90/0.80 + 黑白点 −8/+4 + LUT 1.0」。

## 五条胶片预设（11-01→02，0.12.215→227）

- [2026-11-01→02] **五条胶片预设（0.12.215→227）**：理光正片（GR 三代样片 + 正负配对实测，锁 filmic、contrast 1.15 / 曝光 −0.25）；富士NC（官方样片不可得 ⇒ 亮度归一 + `min(RGB)` 高光去色）；哈苏自然（复用 classic、只补饱和 C\* p50 7.5→11.3）；柯达5219（不归一、压黑 0.5/255 + 颗粒 12/500 + 高光加暖）；徕卡暗角（末端加速落 8bit 色带 ⇒ 去掉，M9 机内默认校正）。

## 取景器对齐与蓝天定档（11-04，0.12.230→242）

- [2026-11-04] **显示域闭环（0.12.232→235）**：成片 `passOutput` 后量中心 1/2 的显示域鲁棒中灰，与取景器 `g_pv_disp_p50` 比，差多少补到 `exposure_ev`，只重跑 filmic+output；0.7 阻尼 + 两轮迭代（实测增益 1.0~1.76）。真机差 0.02~0.13 EV（改前 0.9）。
- [2026-11-04] **理光负片蓝天定档 + 方案纪律**：官方晴天样片 h 270.9 / C\* 17.8（阴天街景 h 258 / C\* 13.1 不能当蓝天目标），蓝段回 0.12.233 参数；白块已闭环（见下段）。AGENTS.md 新增「方案选择纪律」：禁省事版、禁半成品、对标实现读完再动手。

## LUT 换血与 AE 高光保护（11-11，0.12.243→251）

- [2026-11-11] **白块五路归因 → 落在 LUT；LUT 换血 + AE 高光保护**：白点 probe 闸造 8bit 平台（243 修）、gainmap `max(0,·)` 折点造硬边（244 修）、显示域闭环补 1.24EV 推白（245 加 ±0.6 上限）；而 LUT strength=0 的同机位样片同样 48% ≥240 ⇒ 真因是「LUT 归一削掉影调」。理光改用 LUMIX Lab「杨子祯」33³（灰阶自带 0.25→0.31，天空 hue 261 vs 276）；`hlOn` 改为真正生效、判据换当帧 `pw999`。改动全在 **dev**，包 0.12.251(700)。

## 天空白块收口与 0.13.0（11-12）

- [2026-11-12] **白块真因是显示域闭环**：成片中灰比取景器低 0.86EV（首轮读数 0.084 vs 0.152），闭环一轮补满 0.60EV，把 D4 天空（+1.28EV）推到 8bit 230+/L* 90。改法 = **当帧补 EV + 天空 P99 护栏**（涨 >0.03 或 >0.88 撤销该步），逆光场景宁留 `dispErr` 也不推白。
- [2026-11-12] **vivo 预览被 ISP tonemap 4.59×**（`pvLastMid/stillMid`，+2.2EV）：PC 的"YUV 即场景线性 + 单位阵还原"假设在 X300 不成立；域比只影响预览、不跨场景复用。
- [2026-11-12] **0.13.0(708) 发版**（tag `v0.13.0`）：诊断开关 `sky_diag` 默认关 + 关于页版本号；Spektrafilm 定为 **slot=2 引擎**。 性能专项首轮（0.13.1→0.13.9）见下：修正 `dirMs` 口径（原含 HR+P0）⇒ RCD 65~85ms 不是热点、DFT 1.2~1.5s 才是；编码换 libjpeg-turbo（复用 libultrahdr 的 `jpeg-static`）492→190~290ms；闭环读回 1/2→1/3；对齐 CPU 多线程；新增 `preview_arrive_gap` 与 `tools/preview-stall.py`，把体感卡顿量化为快门后 4×218~264ms 冻结。同场景快门→落盘 8.03/8.10s → 6.15~7.27s。

## 预览卡顿与 T3（11-12 晚，0.13.10→0.13.16）

- [2026-11-12] **卡顿根因不是 strip**：PC 的 strip = LOW 优先级 EGL context（`EGL_IMG_context_priority`，X300 Mali 支持）+ 线程降级 + fence 窗口；上轮只抄了「条带 + glFlush」且加在 P0（300ms），而冻结来自 DFT 单 dispatch ~300ms。补齐后用户确认「好很多」；DFT 按 tile 行拆 2 块 + 在飞限 2，单 job 300→150ms，代价 dftGpu +400ms。
- [2026-11-12] **融合默认切空间域**（0.13.16，`mfnr_dft=false`）：明亮 ISO 1163 6033→4233ms、暗光 ISO 662 8000→5240ms；暗部 σ 2.08→1.71、高频 −18% 但 100% 裁剪无可见差。写新融合的模拟（1~30px 位移）两条权重路径差 0~1.7 LSB ⇒ 不做。

## HDR 灯盘与 gainmap 语义（11-13）

- [2026-11-13] **gainmap 改回标准乘性比值**：`gainTrue` 从加性 `sdr+max(0,hdr−1/anchor)`
  改成 `hdrDisp/sdrLin`（libultrahdr `encodeGain` 口径，clamp [1,4]）。加性版实测灯盘
  63% 像素 ratio=1.00、只有灯泡核心有增益（HDR 屏上灯盘不亮、灯泡四周一圈灰），PC 同场景
  整盘 3.9；改后 0% 恒 1、中位 2.2~2.9、p90 4.0。
- [2026-11-13] **两个 gainmap 必修**：① 输入必须同 exposure（SDR 已乘，参考帧没乘 ⇒
  EV+3.8 时整张退化 ratio=1、画面发紫）；② 用单锚点线性换算近似不了 filmic S 曲线
  （固定 2.56 与本张 0.42 都让整张顶满 4x）。
- [2026-11-13] **灯面白的根因在曲线不在融合**：4 帧离线复算证明 satW 正常（f0/f1/f2 权重 0、
  短帧 f3 权重 0.98，融合后灯面 scene 1.52），而 filmic 白点 2.95 把 1.52 映射到 0.8
  （8bit 232）；PC 同场景映射到 0.55（139）。
- [2026-11-13] **采集端高光保护的判据源不可用**：`p999pv=0.68 / dom=2.0` 换算 0.89EV，
  真实 3.04EV，低报 2.15EV；`dom` 是中位比，ISP tonemap 在高光段压 7~14×，修不了肩部。

## 预设覆盖层与相册删除（11-14）

- [2026-11-14] **「标准」预设的覆盖层漏洞**：`CAMERA_STANDARD_PRESET` 是硬编码对象、不在 `builtin.json`，`applyBuiltinOverrides` 匹配不到它 ⇒ 用户改的引擎/自动白点每次冷启动还原。`BuiltinPresets.forCamera()` 让标准也走覆盖层；`resolveCameraPreset` 先查列表（`savedId` 为空时也查，因为选中标准会删 `camera_preset_id`）。
- [2026-11-14] **相册改覆盖层的连带修复**：相机页常驻 ⇒ 外层改 prefs 不再反映到 state。胶卷位加 prefs 监听 + 解码失败自愈；查看页删除改为"留在页内切下一张"（`onDeleted(Int)`），同页换图走交叉淡入，空列表不再报"照片已失效"。
- [2026-11-14] **HDR gainmap 对齐 PhotonCamera**：删 `anchor`（显示域中灰 ÷ RAW 域中灰 ≈2.4）与重复 exposure，改纯比值 + LUT 亮度补偿，`gmMean` 0.79→0.51；`filmicTone` 白点之上保留线性 headroom（SDR 侧 `min(c,1)` 截断）。

## HDR 灰带追因与短帧归一（11-14 晚，0.13.65→0.13.77）

- [2026-11-14] **灰带真因是 reference 与 SDR 不同形**：SDR 在灯面被 filmic 肩部压成 80px 平台（8bit 246 恒定），任何更有结构的 HDR reference（裸 scene / 曲线延伸 / HL 重建值 / 邻域最大）都会在平台边界显形——换 reference 只换灰带形状。
- [2026-11-14] **两处代码级错位**：① gainmap 的 `uHdr` 从 0.11.50 起绑的是 `film_src`（filmic **之前**），`ratio` 退化成 `scene/clamp(scene,1)`；② `filmicTone` 里的白点延伸被下游 `gamutMapV5` 吃干净（Y 钳 ≈1.058 + `gamutCheckRgb` 钳 [0,1]），实测 `gmMax=0.50`。
- [2026-11-14] **定版 0.13.77 用短帧**：`tex_hdr_side_`（−1.5EV 短帧 D4）× `frame_scale[short_idx]`(2.82) 归一到 frame 0 曝光域，4×4 盒式平均 + 完整 PC `CurveExtension`；side 链后用 `fbo_hdr_zero_` blit 恢复 zero D4，SDR 亮度不变。灯面 HDR ≈1.4× SDR 白点 = 短帧能给的物理真实值。
- [2026-11-14] **紫边判据扩到品红象限**：`defringe` 原本只认 `B−R>thr`（紫 B>R>G），灯面边缘是 R>B>G 被结构性排除；改用 `min(R−G, B−G)`，峰值 85→11。

## HDR 语义对齐与灰带消除（11-15，0.14.0 发版）

- [2026-11-15] **灰带根因 = 跨 CFA scalar 短帧 inpaint**：`kFsHlInpaint` 把各相位 `v/gain[c]` 当同一亮度平均、无观测回 `uShortK=2.828` ⇒ 平台/圆弧。PC/Bento 的 short clipping mask 只做**整帧接受判定**（8 连通 ≥80px 拒绝），接受后走 phase-preserving MergeBayer。已 `kHlInpaintOn=false` 从生产链移除。
- [2026-11-15] **证据链**：分段 dump 显示灰环在 D4 已存在；正确 R16F 读取后 P0 前后 408,098 个高光 photosite `delta=0`；真实 GLSL 重放显示平台首次出现于 HL 输出（`fused=1.0` → HL 2.4~2.8）。短帧有效按相位 `[G 7%, R 89%, B 7.6%, G 7%]`。
- [2026-11-15] **四次核形实验全部失败**（离散 8/16/24、连续 1..24、连续盘 24、5×5 双边）：只换灰带形状；语义错时调核无效。
- [2026-11-15] **0.14.0(797) 发版**（tag `v0.14.0`）：`main`/`dev`/tag 同点 `465a4b4` 已 push；真机 `hlInp=0/hlOk=1/gainmapOk=1`，boost p99 3.05×/3.11×，用户判定"完全正常"。
- [2026-11-15] **ponytail 与成像链冲突**：全局规则默认加载 ponytail（最懒可行），项目铁律要求最完整实现；HDR 融合链曾有 `ponytail:` 临时形态授权，本次根因代码无该标记但同属"标量近似"模式 ⇒ 成像关键链禁用 ponytail。

## 高光融合落地与出片回归（11-15 晚，0.14.5→0.14.6）

- [2026-11-15] **PC/Bento 高光融合完整落地**：native CFA clipping 有效性（四相位、扣黑前整数判据）、真实双向 FB+RGB 可观测配准、radius4 collar/全局中位回退、8 连通 hole ≥80 整帧拒绝、phase-preserving dual-kernel MergeBayer、去马赛克后 RGB CameraWhite 白界、局部 V 与六分量 cov 经 LSC²+CCM 传到 PC3D/P4/小波。两组原 RAW 重放：一组按 285px 大孔拒绝，另一组 24px 孔洞接受且 G 高光 ~97% 恢复。
- [2026-11-15] **0.14.5 交付事故**：`GpuTimer` 手写 `GL_GPU_DISJOINT_EXT=0x8FBD`（标准 `0x8FBB`）⇒ `GL_INVALID_ENUM` 被新硬失败检查判死，融合成功但 `bytes=0`（无 JPEG）；同时遗留 `burst_dump` 哨兵绕过 DNG 开关往相册写 DNG。
- [2026-11-15] **0.14.6(803) 修复并验收**：改用平台头文件枚举 + 成套 EXT 入口；删除调试 DNG 旁路；新增真实 so 全链出片测试（256/512/2048/rotation/bilinear 全 PASS，JPEG 到 1.66MB）。用户验收「JPEG 正常、DNG 不再出现」。
- [2026-11-15] **新开口：拍照变慢 + 预览卡**：用户反馈，怀疑条带化被吃掉；尚未测量，下窗口先取剖面再决定，禁止先重写。

## 性能收线与云边品红（11-16，0.14.6→0.14.20）

- [2026-11-16] **0.14.20 性能收线**：预览专用回调线程 + gate、去重时间戳、取消 capture 暂停预览、
  warmup 预建 full-size noise/cov、slot3 协方差借用、histogram 权重缓存、mismatch 条带、
  highlight guide 当前调用三行缓存；质量数值回归逐字节一致，但目标仍差约 1--2s 且启动/采集有停顿。
- [2026-11-16] **0.14.19 云边品红**：无真实短帧时 RGB CameraWhite resolve 与短帧 merge readiness
  混用，导致 resolve 被跳过且旧 CFA sat-fix 重开；分离 readiness 后用户两张长焦天空验收通过。
- [2026-11-16] **仓库与临时材料**：`d6f6ede` 已 push `origin/dev`；`test_temp` 大图/RAW/stage dump
  已清理到 203MB，日志/JSON/parity/校验和保留；历史样片路径不再保证可重放。

