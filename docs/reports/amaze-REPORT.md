> 存档自 /tmp/amaze,2026-10-06

# AMaZE 去马赛克：实现、集成与量化对比（FilmKit v0.10.172，demosaic 档 = 2）

日期：2026-10-05 · 执行：成像工程师子任务 · 权威参考：RawTherapee `dev` 分支
`rtengine/amaze_demosaic_RT.cc`（1610 行，GPL-3.0，Emil Martinec 2008–2010 /
Ingo Weyrich 优化版），本地副本 `/tmp/amaze/amaze_demosaic_RT.cc`。

---

## 0. 一句话结论

**AMaZE 按用户要求做出来了、可在设置里与 RCD 二选一、clean 构建通过、APK 已出**；
但**离线量化对比不支持"Amaze 能减轻紫边"这一预期** —— 在 ∇>60 强边缘伪彩口径下，
Amaze(本实现) 在两张真实样张上**不优于** DCB/AHD（改善为**负**），
**没有任何证据表明它相对 RCD 有 ≥10% 的改善**。这与任务给定的先验一致：
**换去马赛克不是紫边的解法**（算法间同口径极差仅 1.6×，而紫边主因在编码/色度抑制）。

---

## 1. 交付物

| 项 | 位置 |
|---|---|
| AMaZE 算法实现（CPU，C++20，多线程 tile 并行） | `core/engine/src/render/AmazeDemosaic.cpp`（~830 行）+ `.h` |
| 引擎接入（demosaic 档 = 2） | `core/engine/src/render/FrameRenderer.cpp` / `.h` |
| 离线评测脚本与产物 | `/tmp/amaze/bench.py`、`/tmp/amaze/out/*.metrics.json`、`/tmp/amaze/amaze_cli*` |
| APK | `filmkit-0.10.172.apk`（仓库根，40.7MB，arm64-v8a 含新代码，已用 `strings` 校验 `amazeMs`/`amazeSrc` 在 `.so` 内） |
| 设置项 | `app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt`（RAW 去马赛克三选一） |

### 落码坐标（函数 + 行号 + grep 串）

| 改动 | 文件:行 | grep 串 |
|---|---|---|
| AMaZE 主函数（tile 调度 + 多线程） | `AmazeDemosaic.cpp:797` | `void AmazeDemosaic(` |
| AMaZE 单 tile 全流程（⑯ 个阶段） | `AmazeDemosaic.cpp:~100` | `void processTile(` |
| 与参考实现的偏离点 1（hvwt 定型 ÷4） | `AmazeDemosaic.cpp:480` | `hvwtalt = 0.25f` |
| 与参考实现的偏离点 2（pmwt 定型 ÷4） | `AmazeDemosaic.cpp:631` | `pmwtalt = 0.25f` |
| 新 shader（CPU 结果 → 成片几何） | `FrameRenderer.cpp:679` | `kFsAmazeUp` |
| CPU AMaZE + LSC + 上传 | `FrameRenderer.cpp:6801` | `bool FrameRenderer::runAmazeCpu` |
| 旋转 pass | `FrameRenderer.cpp:6892` | `void FrameRenderer::passAmazeUp` |
| 成片链分支（amaze 优先于 rcd） | `FrameRenderer.cpp:7157` | `if (c.amaze) {` |
| ctx 填充（单帧 / burst） | `FrameRenderer.cpp:6968`、`9326` | `c.amaze = demosaic == 2;` |
| 日志字段 | `FrameRenderer.cpp:7457` | `"amazeMs"` / `"amazeSrc"` / `"dem"` |
| ChainCtx 字段 | `FrameRenderer.h:314` | `bool amaze = false;` |
| 纹理成员 | `FrameRenderer.h:581` | `GLuint tex_amaze_` |
| 设置项（三选一，含旧键迁移） | `SettingsScreen.kt:249,268` | `demosaic_algo` |
| 档位读取（3 处） | `CameraController.kt:1605,2452,2610` | `demosaic_algo` |
| CMake | `core/engine/CMakeLists.txt:20` | `AmazeDemosaic.cpp` |

**档位取值**：`demosaic` = 0 快速(bilinear) / 1 RCD 质量（默认）/ **2 Amaze**。
`raw.render` 日志新增 `"dem": "bilinear"|"rcd"|"amaze"`、`"amazeMs"`、`"amazeSrc"`。

---

## 2. 移植策略与依据（为什么是 CPU 而不是 GLSL 多 pass）

### 2.1 参考实现的结构（决定了 GPU 版成本）

`amaze_demosaic_RT.cc` 在**一个 tile（ts=160，含 16px 镜像边界，步进 ts−32）内顺序
执行 16 个阶段**，中间场见源码 130–200 行的 buffer 清单：

```
14 × ts×ts 全网格 float 场（rgbgreen/delhvsqsum/dirwts0/dirwts1/vcd/hcd/vcdalt/
  hcdalt/cddiffsq/hvwt/dgintv/dginth/cfa/nyqutest）
+ 12 × ts×tsh 半网格（Dgrb0/Dgrb1/delp/delm/rbint/Dgrbsq1m/Dgrbsq1p/pmwt/rbm/rbp/
  Dgrb2h/Dgrb2v）+ 2 个字节标志（nyquist/nyquist2）
```

阶段：① H/V 梯度 → ② 方向色差（HA vs 自适应比值 arthresh=0.75）→ ③ 方差择优 +
高饱和 median 限幅 → ④ hvwt 方向判别 → ⑤ nyqutest（gaussodd 卷积 cddiffsq −
gaussgrad 卷积 delhvsqsum）→ ⑥ Nyquist 标志/包围盒 → ⑦ nyquist2 邻域投票 + 13×13
area 插值 → ⑧ 初始 G@R/B（Dgrb0）+ G 曲率 → ⑨ Nyquist 区曲率精修 → ⑩ 对角梯度
→ ⑪ rbm/rbp/pmwt → ⑫ rbint → ⑬ 用 R+B 重插值 G → ⑭ Dgrb 拆分 G−R / G−B →
⑮ chrominance 插值（1.325/−0.175/−0.075）→ ⑯ 输出 RGB。

**关键结论（回答"有没有跨像素顺序依赖"）**：除 ⑮ 是**同行 in-place 更新**
（依赖扫描顺序，但可多 pass 展开为"读旧值写新值"两份纹理）外，其余阶段都是
**有限邻域、逐像素可并行**的 ⇒ 原理上可映射为 GPU 多 pass。CPU 版因此天然
等价（tile 内顺序与参考实现一致）。

### 2.2 为什么最终选 CPU 实现

| | CPU（本实现） | GLES 3.0 fragment 多 pass |
|---|---|---|
| 与参考实现一致性 | 逐段同构、逐像素同序（tile ts=160 步进 128 完全照搬） | 需把 ⑮ 拆双缓冲、Nyquist 包围盒跨 pass 传递，等价性需逐项证明 |
| 中间场驻留 | 线程本地 2 MB／tile（`Scratch`），随用随清 | **全图级**：每阶段一张全分辨率纹理 |
| 显存（12.6 MP，4080×3072） | ~2 MB × 线程数（≤8 → 16 MB） | 峰值 20–24 张 R16F ≈ **500–600 MB**；半网格可 RG16F 打包 → 300–400 MB ⇒ **中端机 GPU 内存预算内不可行** |
| 每像素采样 | — | 逐阶段累加 ≈ **240 tap/px**（②17 ③14 ④26 ⑤27 ⑦49* ⑧7 ⑨35* ⑩8 ⑪36 ⑫7 ⑬14 ⑮22 ⑯9；*仅 Nyquist 区）⇒ 12.6 MP×240 ≈ **3.0 Gtexel**，按移动 GPU 有效 2–4 Gtexel/s 估 **0.8–1.5 s**（估算，非实测） |
| 实测耗时 | **247 ms**（4080×3072，Apple Silicon 8 线程）/ 237 ms（3072×4080）；真机待测 | — |
| 精度 | float32 全程 | R16F 半精度会让 ⑤ Nyquist 判据（乘了 0.5 阈值的 5×5 卷积，量级 1e-2）出现判据翻转 |

**移植策略（若将来要 GPU 版，按此顺序）**：
1. 先把 ①②③④⑤⑥ 做成 6 个 pass（输出 R16F，半网格量打 RG16F 包）；
2. ⑦⑧⑨（Nyquist 区）单独 3 个 pass —— 包围盒用 CPU 归约（一次 4×4 降采样纹理 + 读回 16 个数）而不是 GPU 内动态分支；
3. ⑩–⑯ 8 个 pass，⑮ 用双缓冲；
4. 用 **tile 化 dispatch**（把 16 个 pass 串成一个 tile 的 16 次 draw，tile 间并行）规避全图显存 —— 代价是 draw call 数 = 16 × ⌈4080/128⌉ × ⌈3072/128⌉ ≈ **12288 次/张**。
   该方案的可行性**必须真机实测**（本项目铁律：性能取舍由用户拍板，不做预判）。

### 2.3 供用户拍板的选项（成本/收益）

| 选项 | 成本 | 质量代价 | 可逆 |
|---|---|---|---|
| **A. 现状：CPU AMaZE（已交付）** | +0.25 s/张（桌面实测；真机待测），+16 MB | 无（与参考实现一致） | 设置里随时切回 RCD |
| B. 接 CPU 版 P0 降噪（同色 bilateral 的 CPU 等价物） | ~+0.1–0.3 s/张，~80 行 | **无代价，是补短板**：消除"Amaze 档比 RCD 档更噪" | 是 |
| C. GLSL 全图多 pass 版 | 15–18 个 pass，500–600 MB 显存 | R16F 精度风险（Nyquist 判据） | 是（保留 CPU 版） |
| D. GLSL tile 化多 pass 版 | 12288 draw/张，实现 2–3 周 | 同上 | 是 |
| E. 不做 GPU 版（保持 A） | 0 | 无 | — |

> **推荐 A + B**，C/D 的收益上界受第 4 节数据限制（离线看不到优势）。

---

## 3. 与参考实现的偏离点（如实列出，不静默简化）

**偏离点 1/2（同一处数学，出现在两个阶段）**：hvwt / pmwt 的"邻域定型"。

- 参考实现写法：`xdivf(hvwt[(indx-m1)>>1] + hvwt[(indx+p1)>>1] + hvwt[(indx-p1)>>1] + hvwt[(indx+m1)>>1], 2)`（`amaze_demosaic_RT.cc:976`，pmwt 同型在 1244 行）。
- 本实现：`0.25f * (…)`（即 **÷4**）。
- **依据（实测，不是推断）**：
  - 若按字面 ÷2，`hvwtalt ∈ [0,2]` ⇒ 写回后 hvwt 越界 ⇒ ⑯ 的归一化分母
    `hvwt[up] + 2 − hvwt[right] − hvwt[left] + hvwt[down]` 可过零/为负 ⇒ R/B 数值爆炸。
  - 同源 DNG（4080×3072）实测：

    | 口径 | max | p99.99 | >3 的像素数 |
    |---|---|---|---|
    | ÷2（字面） | 7572 | 717 | 1 264 718 |
    | ÷4（本实现） | 4.99 | 2.12 | 367 |
    | ÷2，`/tmp/red/shot.dng` | 11936 | 652 | 1 869 949 |
    | ÷4，同上 | 9.04 | 2.16 | 1052 |

  - PhotonCamera 的独立 GLSL 移植（`/tmp/pc/.../shaders/amaze/green.glsl` 的
    `firm()`、`pmrbint` 路径）用的正是 `0.25 *`。**两处独立实现在同一点上一致**。
  - 若 RT 的 `xdivf(x,n)` 语义是 `x / 2^n`，本实现与 RT **完全一致**；无论哪种读法，
    ÷4 都是唯一能跑通的取值。原始 `xdivf` 定义未能在 RT 仓库检索到（`rt_math.h` /
    `opthelper.h` / `helpermath.h` 均无，网络检索工具本次不可用）——**这是唯一未能
    100% 坐实的点，已如实标注**。

其余全部按参考实现标量分支逐段实现，包括：`clip_pt8 = 0.8·clip_pt` 的近裁剪回退、
median 限幅的四种情况（G 位 / R-B 位 × 水平 / 垂直）、⑬ 的
`rbint[indx1 ± v1]`（v1 = ts = 2·tsh，半网格里是上/下**两行**；实现中逐字保留该
索引，未擅自改成 ±tsh）。

---

## 4. 离线量化对比（Amaze vs 经典算法）

### 4.1 口径（可复现）

- 输入域：**相机 RGB、线性、白平衡后**（`use_camera_wb=True, gamma=(1,1), no_auto_bright,
  output_bps=16, user_flip=0`，Amaze 用同源 CFA：扣黑阶 ÷ 白阶 × WB 增益）。
- 亮度 `L=(R+2G+B)/4`；梯度中心差分；**强边缘集合 E = {|∇L| > 60}**（0–255 尺度）。
- **伪彩强度** `fake = √((R−G)² + (B−G)²)`，报 E 上的 mean / median。
- **边缘色度误差** `edgeErr = mean over E of √((cr−LP(cr))² + (cb−LP(cb))²)`，
  `LP` = 16px box 低通（色度的低频参考）。
- **非边缘色度保真度（红线）** = 在 `|∇L| ≤ 60` 上同口径量。
- **过曝掩码**（6.65% / 2.69% 像素）：`cfa/gain[chan] > 0.98` 再膨胀 2px。
  **必须排除**：libraw 把饱和区渲染为中性白（R=G=B），而 AMaZE/RCD 保留 WB 域真实
  饱和值（R=2.078·G）——两者在饱和区本就不可比（这是域差异，不是伪彩）。
- 样张：`test_temp/FILMKIT_20261004-150628.dng`（我们的机器，4080×3072 逆光室内）、
  `/tmp/red/shot.dng`。命令：`python3 /tmp/amaze/bench.py <tag> <dng> --amaze <rgb.f32> --amaze-meta <json>`

### 4.2 结果

**FILMKIT_20261004-150628.dng**（逆光室内，排除过曝后强边缘 6073 px）

| 算法 | 伪彩 mean | 伪彩 median | 边缘色度误差 | 非边缘色度误差（红线） | 高频能量 | 边缘占比 |
|---|---|---|---|---|---|---|
| **AMAZE(本实现)** | **30.005** | **29.167** | **57.008** | **0.903** | 0.828 | 0.05% |
| **RCD(本实现，离线真 shader 执行)** | **22.838** | **21.758** | **60.467** | **0.894** | 0.790 | 0.04% |
| DCB | 19.243 | 18.615 | 14.391 | 0.576 | 0.672 | 0.04% |
| AHD | 20.668 | 19.707 | 15.457 | 0.572 | 0.700 | 0.04% |
| DHT | 22.478 | 21.392 | 17.491 | 0.551 | 0.692 | 0.04% |
| VNG | 24.956 | 23.689 | 20.324 | 0.545 | 0.672 | 0.04% |
| PPG | 22.468 | 21.310 | 17.195 | 0.590 | 0.690 | 0.04% |
| LINEAR | 25.047 | 24.266 | 20.531 | 0.755 | 0.569 | 0.03% |

**/tmp/red/shot.dng**（排除过曝后强边缘 46k px 量级）

| 算法 | 伪彩 mean | 边缘色度误差 | 非边缘色度误差 | 高频能量 |
|---|---|---|---|---|
| **AMAZE(本实现)** | 25.541 | 18.297 | 0.911 | 0.728 |
| **RCD(本实现)** | **24.473** | **17.691** | **0.901** | 0.682 |
| DCB | 23.622 | 14.077 | 0.794 | 0.647 |
| AHD | **22.785** | **13.195** | 0.780 | 0.693 |
| DHT | 23.966 | 15.659 | 0.775 | 0.685 |
| PPG | 23.414 | 14.012 | 0.805 | 0.677 |
| VNG | 27.004 | 19.625 | 0.761 | 0.642 |
| LINEAR | 30.165 | 23.435 | 1.031 | 0.483 |

### 4.3 明确回答任务提出的问题

> **Amaze 相对 RCD 在强边缘伪彩上改善多少？**

**RCD 的离线复现已补做**（不是 numpy 重写）：`/tmp/amaze/rcd_offline.py` 用
moderngl 离屏 OpenGL 4.1 core 上下文，**真执行** `FrameRenderer.cpp` 里提取的
`kFsRcdDir` / `kFsRcdG` / `kFsRcdChroma` 三段 GLSL（仅把 `#version 300 es` 改写为
`#version 330 core`；`uRaw` = R16UI 原始 u16、`uMos/uMosaic` 关、`uLsc` 恒等、
`uCdFix=1`（原版行为）、`uCcm` 被编译器消除）。
**朝向自检**：读回后与 AMaZE 输出比较，`flipud` 后 `mean|RCD−AMAZE| = 0.0014`、
G 通道相关系数 **0.99986**（两个实现高度一致）⇒ 复现可信。

| | AMAZE | RCD | 差值（Amaze 相对 RCD） |
|---|---|---|---|
| FILMKIT 强边缘伪彩 mean | 30.005 | 22.838 | **+31.4%（更差）** |
| FILMKIT 边缘色度误差 | 57.008 | 60.467 | **−5.7%（略好）** |
| FILMKIT 非边缘色度误差（红线） | 0.9028 | 0.8943 | +0.95%（更差） |
| shot 强边缘伪彩 mean | 25.541 | 24.473 | **+4.4%（更差）** |
| shot 边缘色度误差 | 18.297 | 17.691 | **+3.4%（更差）** |
| shot 非边缘色度误差（红线） | 0.9113 | 0.9012 | +1.1%（更差） |

**直说：Amaze 相对 RCD 在强边缘伪彩上没有任何改善** —— 六项里唯一的"改善"是
FILMKIT 图的边缘色度误差 −5.7%，**仍 < 10% 的门槛**；其余五项全部为负（+0.95% ~
+31.4%）。**非边缘色度保真度（红线）两图都轻微变差（+0.95% / +1.1%）**，
即"没变好，还略退"。

相对 DCB/AHD（libraw 基线）：FILMKIT 图 Amaze 30.005 vs DCB 19.243（+56%）、
AHD 20.668（+45%）；shot 图 25.541 vs AHD 22.785（+12.1%）、DCB 23.622（+8.1%）。
**同样没有任何一项显示 Amaze 占优。**

**口径局限（必须一起读，否则会误判）**：`fake = |R−G|,|B−G|` 的幅度**无法区分
"真实的锐利色度突变"与"伪彩"**。证据：RCD 也系统性高于 libraw 家族
（hf 0.790 vs DCB 0.672），而 RCD 的伪彩在真机目视里并不比 DCB 差 —— 说明这批指标
在测量"我们这条链（保留更多色度细节）"与"libraw（色度更平滑）"的风格差异。
因此上表**不能单独证明 Amaze 的伪彩更严重**，但它**足以否定"Amaze 明显更干净"**：
没有任何一项指标显示 Amaze 占优，且相对 RCD 多数为负。

**合成图（有 ground truth）交叉验证**：256×256 合成图（色块 + 硬阶跃 + 斜纹 +
极亮/极暗块）上本实现 **PSNR = 30.64**、无 NaN/Inf、无热像素（max 1.083）。
该值对含硬边缘的合成图属"正常偏好"，说明实现**没有整体性错误**；
同图上 `÷2` 变体会出现 2570 个热像素、峰值 1030 ⇒ 偏离点 1/2 是必需的。

### 4.4 低照度/高噪声

- 本次两张样张的噪声都很低（CFA 相邻同色像素 |diff| 中位数 0.00104 ≈ 0.26 级/255），
  **没有可用的高 ISO 样张**，故"高噪声表现"**未实测**（未完成项）。
- 已知机理（供判读）：AMaZE 的 ④（hvwt 方向判别）与 ⑦（Nyquist 纹理判据）都依赖
  局部梯度/方差，噪声会让方向判别翻转 ⇒ 产生低频色斑/迷宫纹。**而本档当前不接
  P0 RAW 域降噪**（见第 6 节），因此真机上 Amaze 档的噪声表现**预期差于 RCD 档**。

---

## 5. 真机判据（请按此验收）

1. **档位生效**：设置 → RAW 去马赛克 → 循环切换（RCD 质量 → Amaze → 快速）。
   `raw.render` 日志应出现 `"dem":"amaze"`（对应 `"amosaic":"rcd"` 的旧字段仍保留），
   并带 `"amazeMs":<毫秒>` 与 `"amazeSrc":"single"|"burst0"`。
2. **耗时**：关注 `amazeMs`。桌面（Apple Silicon，8 线程）实测 247 ms @4080×3072；
   真机预期 1–4 s（待实测，报告里不预判）。
3. **同场景 A/B（关键）**：同一场景、同一曝光，分别用 RCD 与 Amaze 出片各一张，
   比较：
   - `diag.stats` 的 D3-camrgb（去马赛克后、CCM 前的相机 RGB）色度统计；
   - **目视**：暗衣物/晾衣杆 vs 窗外天空的强边缘是否有紫/绿镶边差异；
   - 纹理区（织物、文字）细节是否更实；
   - 暗部噪声与色斑（预期 Amaze 档更差 —— 因为没接 P0 降噪）。
4. **回归红线**：RCD 档与双线性档的行为必须**逐位不变**（本改动对这两档只增加了
   1 个 shader 程序 + 1 张纹理的编译/分配，不进入其渲染路径；`c.amaze` 为 false 时
   所有新代码不执行）。

---

## 6. 未完成 / 无法判定（明确区分）

**未完成（本可做、本次没做）**

1. ~~RCD 的离线复现~~ —— **已补做**（第 4.3 节，moderngl 真执行三段 shader，
   朝向自检 G 相关系数 0.99986）。仍缺的是：**带 ground truth 的标准数据集
   （Kodak/IMAX）上的 PSNR/SSIM + 伪彩分离**，那才能区分"真实色度"与"伪彩"。
2. **Amaze 档未接 P0（RAW 域 bilateral）降噪与 HR 前置** —— 两者都是 GPU pass，
   CPU 侧没有等价物；且真机已实测 GLES 3.0 的浮点纹理 `glReadPixels` 不可用
   （v0.10.163/166 两次失败），所以"GPU 前置 → 读回 CPU → AMaZE"这条路**在真机上不可行**。
   影响：**高 ISO 下 Amaze 档噪点/色斑多于 RCD 档**。
   可选补法（选项 B，第 2.3 节）：在 `runAmazeCpu` 里实现 kFsRawDenoise 的 CPU 等价物
   （同色 3×3 网格 bilateral，σ² = A·x+B 同式），~80 行。
3. **MFNR（多帧融合）路径下 AMaZE 退化为参考帧单帧**：融合结果在 GPU（`tex_fused_`），
   CPU 侧拿不到。日志用 `"amazeSrc":"burst0"` 显式标注（**不是静默降级**）。
4. **高 ISO 样张的噪声对比未测**（手头样张噪声都低，见 4.4）。
5. **真机耗时与真机 A/B 未做**（需要装机与人工拍板）。
6. **CPU 内存峰值未优化（真机风险项）**：`runAmazeCpu` 在 12.6 MP 下的瞬时峰值
   = 线性化 CFA（float，50 MB）+ AMaZE 输出 RGB（float，**151 MB**）≈ **201 MB**。
   本机（macOS）无压力，但中端 Android 上属需要实测的风险项。**两个已验证可行、
   共可省 ~50–130 MB 的改法**（未实施，供拍板）：
   (a) 输入 CFA 以 uint16 量化传递（tile 内转 float）→ 省 25 MB；
   (b) AMaZE 输出改 half（GPU 侧本来就是 RGB16F）→ 省 76 MB（峰值降到 ~100 MB）。
   两者都不改算法数值路径（(b) 只影响末位精度）。

**无法判定**

- 参考实现 `xdivf(x, n)` 的确切语义（÷2 还是 ÷2ⁿ）。两种读法下本实现取的 ÷4 都是
  唯一可跑通的值（第 3 节有实测），但**不能 100% 断定与 RT 逐位一致**。
- **AMaZE 是否在某个指标维度上优于 RCD**：本报告的指标（伪彩幅度、边缘色度误差、
  非边缘色度保真）**都无法区分真实色度与伪彩**；要判定必须用带 ground truth 的
  数据集（Kodak/IMAX 等）做 PSNR/SSIM + 伪彩分离，或真机同场景目视 A/B。

---

## 7. 工程纪律核对

- `bash tools/validate-shaders.sh` → **全部 shader 校验通过**（含新增 `kFsAmazeUp`；
  注释中无半角 `)"`）。
- `./gradlew clean assembleDebug`（`JAVA_HOME=/opt/homebrew/opt/openjdk@23`）→
  **BUILD SUCCESSFUL**；APK 内 `lib/arm64-v8a/libfilmengine.so` 含 `amazeMs`/`amazeSrc`
  字符串（新代码确实进包）。
- 版本号：310 / 0.10.171 → **311 / 0.10.172**；APK 复制到仓库根 `filmkit-0.10.172.apk`。
- **未 git commit / push**。
- 并发改动全部仍在（`git diff --stat` 后逐项 grep 命中）：
  `diagStatsTex`(2) `diag_stats`(5) `scEff`(3) `moGain`(2) `hlPre`(1)
  `hlWClamp`(1) `kSceneLinearNorm`(1) `caK`(2) `jpeg_444`(2) `saveJpeg`(1) `writeJpeg`(1)。
  `CameraController.kt` **只做了 3 处 `demosaic` 读取的精确替换**（未触碰 JPEG 4:4:4 路径）。
- 所有改动均为精确 `edit`/脚本定点替换，无 read→write 全量重写。

---

## 8. 复现命令（离线）

```bash
# 1) 生成 CFA（真机同源线性化 + WB）与元数据
python3 /tmp/amaze/prep.py <xxx.dng> /tmp/amaze/<tag>
# 2) 编译 CLI（macOS 本机即可）
clang++ -std=c++20 -O2 -pthread -I core/engine/src \
  -o /tmp/amaze/amaze_cli /tmp/amaze/amaze_cli.cpp \
  core/engine/src/render/AmazeDemosaic.cpp
# 3) 跑 AMaZE
/tmp/amaze/amaze_cli /tmp/amaze/<tag>.cfa.f32 <w> <h> <cfa4> <clip> /tmp/amaze/<tag>.amaze.rgb.f32
# 4) 指标（含过曝掩码）
python3 /tmp/amaze/bench.py <tag> <xxx.dng> --amaze /tmp/amaze/<tag>.amaze.rgb.f32 \
  --amaze-meta /tmp/amaze/<tag>.json
```
