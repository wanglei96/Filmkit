# E4-3 颗粒（grain）引擎按 darktable `grain.c` 重写 —— 交付报告

> 实现子代理：DSH 会话（E4-3，2026-10-10）。工作目录 `/Users/wl/Downloads/胶片相机`。
> 规格：`docs/reports/editor-E4-film-params-DESIGN.md` §3.1（第 180–315 行，唯一权威）。
> 架构决策：`docs/reports/editor-ADR.md`（ADR-10 默认值红线 / ADR-11 颗粒归一化 / ADR-12 禁止假滑杆）。
> **代码改动全部留在工作区，未 commit**（等架构师独立复核）。
> 参考实现：darktable `src/iop/grain.c`（master，2026-10-10 拉取逐行对照）。

> ⚠️ **工作区里还有并行 agent 的改动，不是本卡的**：`EditorExport.kt`、
> `EditorPresetAmountTest.kt`、`EditorPresetTest.kt`、`FilmParamKeysTest.kt`、
> `docs/reports/preset-*.md`、`docs/reports/p1-preset-schema-REPORT.md`。
> 本卡**只**碰了这 6 处：`core/engine/src/render/{FrameRenderer.cpp,FrameRenderer.h,GrainLut.h}`、
> `tools/{check-diag-fields.py,check-style-passthrough.py,grain_lut_ref.py}`、
> `tools/grain-exp/*`、`.gitignore`，以及 `EditorEngineSession.kt` 的 KDoc 清单同步（§6.14，
> 为满足既有 `EditorStyleWhitelistTest` 三方一致护栏，纯注释改动）。

---

## 0. 结论摘要

1. **6 项差异全部落地**（§2），`[26]=0` 默认关逐位零回归（离线 2831 万像素 + 与改前同口径）。
2. **颗粒现在是"颗粒"而不是"块状"**：低频能量占比 8.8–14.7%（旧实现同尺度 51.7%），
   频谱斜率 +0.07（旧实现 −3.78），目视截图见 §4.5。
3. **`[27]` 严格单调**：ISO 100/400/1600/6400 实测特征周期 5.24/5.66/7.61/15.97 px。
4. **`[60]` 生效且形态正确**：mb=100 三段灰 RMS 6.39/11.89/5.74（中间调最重、两端趋 0）；
   mb=0 → 11.76/11.97/11.74（覆盖全影调）。
5. **`[61]=0` 与 darktable 原版逐点相同**（误差 0.000e+00，不是"接近"）。
6. **构建全绿**：`validate-shaders.sh` / `check-diag-fields.py` / `check-style-passthrough.py`
   全通过；`assembleDebug testDebugUnitTest` **退出码 0**（删 `.o`/`.so` 后强制真编，见 §5.4）。
7. **真机未验证**（本机无连接设备、无模拟器、SDK 无 emulator 组件）—— 见 §7，
   已用"真跑引擎 shader 的离线执行器 + glslang 静态校验"替代到能力上限。

**需要架构师裁决的 3 件事**（详见 §6）：
- **A. `[61]` 的方向**：规格 §3.1.5 的**公式**（`pow(l, 1+3p)`）与**文字**（"峰值移向暗部"）
  互相矛盾；按公式字面实现的结果是**峰值移向亮部**（实测 l: 0.496 → 0.693）。本次按公式字面实现。
- **B. 规格 §3.1.5 的 LUT 减数**：照字面只替换 `paper_resp_inverse` 的输入、保留 `- l`，会让
  `[61]` 变成**整体亮度**旋钮（pivot=0.3 时全图 −23.5 L 单位 ≈ −60/255）。本实现取 `- l'`（无偏移）。
- **C. 规格 §3.1.3 第 1 项的事实描述**：旧实现实测是"**size 越大越粗**"（质心周期
  3.99→68.8 px），不是规格写的"越大越细"；"必须改"的判定仍成立，但真实理由是
  **数值标定与物理尺度脱节**（默认 size=1.0 时像素空间格子 0.167 px，全是亚像素噪声）。

---

## 1. 改了什么（文件 : 行号）

### 1.1 新增文件

| 文件 | 作用 |
|---|---|
| `core/engine/src/render/GrainLut.h`（新建，106 行） | 相纸响应 LUT 的**纯数学**（darktable `paper_resp` / `paper_resp_inverse` / `evaluate_grain_lut` 逐行移植）+ `floatToHalf` + `grainZoom`。无 GL / 无日志依赖 ⇒ 引擎与离线 dump 工具**共用同一份实现**（否则"离线对照"对照的是两份各自实现的代码，证明不了真机数据） |
| `tools/grain_lut_ref.py`（新建） | 上述数学的 numpy 独立复算（供 `check-style-passthrough.py` 与 `grain-exp.py` 共用）+ 7 项解析自检 |
| `tools/grain-exp/grain-lut-dump.cpp`（新建） | 从 `GrainLut.h` 导出 LUT（`<mb> <pivot> <out.raw>`）供逐点对照 |
| `tools/grain-exp/grain-exp.py`（新建） | E4-3 专用离线验收（真跑 shader + 频谱 + 调制曲线 + 目视截图） |

### 1.2 `core/engine/src/render/FrameRenderer.cpp`

> 行号为**最终状态**（`git diff` 后实测，非改动过程中的中间值）。

| 位置 | 改动 |
|---|---|
| 28–57 | 新增 `grainFingerprint`（37–44，FNV-1a 采样）/ `grainSeedFrom`（49–57，种子派生），**必须在匿名 namespace 内**（见 §6.11） |
| 4501–4509 | `kFsOut`：删 `uGrainSize`/`uTime`，加 `uGrainSeed`/`uGrainZoom`/`uGrainNorm`/`uGrainLut` |
| 4552–4564 | `kFsOut`：`dhash` 注释更新（原 sin 版 `hash` 已删） |
| 4565–4660 | `kFsOut`：**删 `hash()` / `vnoise()`**，加颗粒函数段（`kGrainPerm[256]` 4581 / `grainPerm` 4596 / `kGrainGrad3[12]` 4598 / `grainSimplex3` 4604 / `grainNoise` 4642 / `grainLutFetch` 4649） |
| 4763–4774 | `kFsOut`：主段替换（`if (uGrainAmount > 0.0)` 在 4769） |
| 4822–4830 | `kFsStyle`：同款 uniform 声明（**与 kFsOut 逐字相同**） |
| 5028–5122 | `kFsStyle`：同款颗粒函数段（**逐字相同**，由脚本从 kFsOut 提取后植入） |
| 5211–5222 | `kFsStyle`：同款主段（**逐字相同**） |
| 6573–6577 / 6597–6601 | uniform 定位：`u_out_` / `u_style_` 的 `grain_seed/zoom/norm/lut`（删 `grain_size`/`time`/`seed`） |
| 6727–6730 | `release()`：删 `tex_grain_lut_` + 复位缓存键 |
| 7068 / 7301 | `submitFrame` / `submitDisplay`：`grain_seed_ = 0`（预览不闪） |
| 7273 | `submitStill`：`grain_seed_ = grainSeedFrom(grainFingerprint(rgb_stage_…), dst_w, dst_h)` |
| 7485–7487 | `submitDisplay` 日志出口：新增 `grainMb` / `grainPivot` / `grainSeed` |
| 9271 | `submitRaw`：`c.grain_fp = grainFingerprint(raw, w·h·2)` |
| 9847 | `finishStillChain`：`grain_seed_ = grainSeedFrom(c.grain_fp, dst_w, dst_h)` |
| 10101–10103 | `finishStillChain` 日志出口（`raw.render`）：同名字段 |
| 11790 | `submitRawBurst`：`c.grain_fp = grainFingerprint(frames[0], w·h·2)` |
| 12556–12585 | 新增 `FrameRenderer::ensureGrainLut(mb, pivot)`（128² R16F，`glTexStorage2D` + `glTexSubImage2D`，缓存键 `(mb,pivot)`，内含 `glActiveTexture(GL_TEXTURE2)`，见 §6.6） |
| 12598–12620 / 12732–12754 | `passStyle` / `passOutput`：颗粒 uniform 下发段（**逐字相同**，仅 `u_out_`/`u_style_` 前缀不同） |

### 1.3 `core/engine/src/render/FrameRenderer.h`

| 位置 | 改动 |
|---|---|
| 362–368 | `ChainCtx` 加 `uint32_t grain_fp`（内容指纹） |
| 601–604 | 声明 `ensureGrainLut(float mb, float pivot)` |
| 638–646 | 成员 `tex_grain_lut_` / `grain_lut_mb_` / `grain_lut_pivot_` / `grain_seed_` |
| 1170–1176 / 1184–1190 | `u_out_` / `u_style_` 的 grain uniform 字段（删 `grain_size`/`time`/`seed`） |

### 1.4 工具与契约同步

| 文件 | 改动 |
|---|---|
| `tools/check-style-passthrough.py` | `run_style` 支持 `grain_size/grain_mb/grain_pivot` + 建 `uGrainLut` 纹理；`seed` 参数改名 `grain_seed`；白名单加 `grain_midtone_bias`/`grain_pivot`；新增 `check_grain_parity()`（⑨b 四段逐字比对）；新增 ⑯a/⑯c 判据；测试 ③ 的强度 0.5→25（新语义） |
| `tools/check-diag-fields.py` | EXPECT 加 `grainMb` / `grainPivot` / `grainSeed` |
| `app/.../EditorEngineSession.kt` | KDoc 槽位清单加 `[60]`/`[61]`，29 → 31 个槽位（`EditorStyleWhitelistTest` 三方一致护栏要求） |
| `.gitignore` | 排除 `tools/grain-exp/out/` 与编译出的 dump 工具（只提交脚本 + 报告） |

**未改**：`FilmParams.h`（槽位早已就绪）、`CameraPresets.kt`（Kotlin 侧默认值与 C++ 兜底一致，
见 §5.5 核对）、颗粒落点（仍在 3D LUT 之后、sRGB 编码显示域）、luma 权重（仍 `0.299/0.587/0.114`，
**未**改 Rec.709 —— `kFsOut` 的 `luma()` 服务斑马纹判定，改了会连带影响）。

---

## 2. 规格 §3.1.3 六项差异逐条落地

| # | 维度 | 落地方式 | 证据 |
|---|---|---|---|
| 1 | **尺寸语义** | `gp = 像素坐标 × uGrainNorm`（`uGrainNorm = 1/min(W,H)`，短边归一化）+ `uGrainZoom = (1+8·clamp(ISO,20,6400)/213.2/100)/800`。**CPU 侧算好**（shader 内不做 W/H 除法与 clamp，与 E4-2 暗角同款）；`clamp` 上下限都在（旧实现只防 0，size=1e6 会让所有像素落进同一格 = 全图加同一灰度） | §4.3 实测周期 5.24→15.97 px 严格单调；`grain_lut_ref.py` ⑦ 验证 ISO=1e6 与 6400 同值 |
| 2 | **噪声源** | 单八度 `vnoise`（sin-hash + smoothstep）→ **3 八度 3D simplex**：`f={0.4910,0.9441,1.7280}`、`a={0.2340,0.7850,1.2150}`、z 轴传八度序号、输出 `32·(n0+n1+n2+n3)`。perm 用**整数常量数组** `kGrainPerm[256]` + `& 255`（= darktable 的 `perm[512]`），梯度用 `kGrainGrad3[12]` 常量数组，**零 sin-hash** | §4.4 低频占比 8.8–14.7% vs 旧 51.7%；§4.5 目视 |
| 3 | **密度调制** | `delta = 2·exp((mb/100)·ln(0.0001))`；`density(e)` / `inverse(d)` 逐式移植；**预计算 128×128 LUT**（`grain_lut[j][i] = 100·(paper_resp(gu + inverse(l')) − l')`，`gu = i/127 − 0.5`，`l = j/127`），`[60]`/`[61]` 变化时 CPU 侧重算（16384 次 exp/log，亚毫秒）；上传 128² **R16F** 纹理；应用式 `c += lut2d(noise·strength·0.15, luma) × 0.01` | §4.1 C++ ↔ numpy **逐位相同**；§4.5 调制曲线 |
| 4 | **hash 质量** | sin 版 `hash()` 整个删除（它只服务颗粒）；simplex 用整数 permutation 表 | `grep "float hash(" FrameRenderer.cpp` = 0 命中 |
| 5 | **位置/种子** | 删 `uTime`（`frames_ % 512`），新增引擎内部 uniform `uGrainSeed`：**预览/编辑器恒 0**，成片由"输出尺寸 + 输入内容指纹"派生 | §4.7 同 seed 逐位相同、不同 seed 差异 71.5 LSB；§4.8 |
| 6 | **单色性** | **保持现状**（RGB 同加一个标量，无假彩噪）—— 规格 §3.1.3 第 6 项判定为"待实测"，本卡不改 | 主段 `c += …`（标量加） |

---

## 3. 实现要点（与 darktable 的逐行对应）

- **simplex 顶点次序**：darktable 的六路 `if-else` **逐字照抄**（未用 Gustavson 的 `step/min/max`
  无分支形式）—— 理由是"先 100% 按参考实现跑通"，且省掉一份"等价性证明"负担。
- **`FASTFLOOR` → `floor()`**：两者仅在**精确整数**输入上不同（`FASTFLOOR(-3.0) = -4`），
  噪声坐标不可能命中（连续值 + 无理数频率）。
- **`dt_lut_lookup_2d_1c` 逐字移植**：x 轴 `(x+0.5)·127`、y 轴 `y·127`、两端 clamp 到 127、
  索引上界 126（末格靠 `x_diff/y_diff = 1` 外推）。
- **手写双线性（`texelFetch` ×4）而非 `texture()`**：Mali 上 16F 纹理的硬件 LINEAR 不可靠
  （项目多处实测，见 `makeTex` / LSC / `kFsRcdChroma` 注释）⇒ 依赖它会在 128 级台阶上
  静默退化成最近邻。纹理过滤因此设为 NEAREST。
- **`[26] = 0` 门控保留**：`if (uGrainAmount > 0.0)` 整段跳过 ⇒ 零采样、零额外 pass。
  但 LUT 纹理**仍然绑定**（关闭态也绑）—— 沿用 `bindLut` 的既有教训：Mali 对未绑定的
  sampler 会预测执行采样 ⇒ draw 报 1282、整帧全黑。

---

## 4. 离线证据（数据 + 命令）

### 4.1 LUT 逐点对照（判据：C++ ↔ numpy）

```
$ python3 tools/grain-exp/grain-exp.py --quick
① LUT mb=100 pivot=0：C++ ↔ numpy 相对误差 max = 0.000e+00（判据 ≤1e-5）、绝对误差 max = 0.000e+00、|LUT| max = 46.21 L 单位
   ⑥ pivot=0 ↔ darktable 原版公式（无 pivot 项）：max|Δ| = 0.000e+00（判据 ≤1e-5 ⇒ pivot=0 是原版逐点复现）
   gu≈0 处 |LUT| max = 1.20e-03 L 单位（判据 <1e-2 ⇒ [61] 不是"整体亮度"旋钮）
① LUT mb=0 pivot=0：… 相对误差 max = 0.000e+00 … |LUT| max = 49.83 L 单位
① LUT mb=100 pivot=0.3：… 相对误差 max = 0.000e+00 …
```
**逐位相同**（不是"接近"）—— 两边都是 float32 且共用系统 libm。
`grain-lut-dump` include 的就是 `ensureGrainLut` 用的同一份 `GrainLut.h`。

### 4.2 `[26]=0` 默认零回归（判据 1）

```
② [26]=0（含 [60]=0/[61]=1 极端值）3072²：max|Δ| = 1 LSB，>1LSB 像素 = 0/28311552
```
1 LSB = TPDF dither（与颗粒无关，改前既有）；`[60]`/`[61]` 取极端值也不影响 ⇒ 门控只由 `[26]` 决定。
另有 `check-style-passthrough.py` ⑯a（96² 图）同判据。

### 4.3 `[27]` 单调（判据 3）

```
$ python3 tools/grain-exp/grain-exp.py          # 默认 3072²
③ ISO  100：特征周期 =   5.24 px（谱质心 0.1909 cycles/px）、谱斜率 = -0.366
③ ISO  400：特征周期 =   5.66 px（谱质心 0.1767 cycles/px）、谱斜率 = -0.480
③ ISO 1600：特征周期 =   7.61 px（谱质心 0.1314 cycles/px）、谱斜率 = -1.073
③ ISO 6400：特征周期 =  15.97 px（谱质心 0.0626 cycles/px）、谱斜率 = -3.375
③ [27] 单调（ISO 越大 ⇒ 质心越低 ⇒ 周期越长）= True
```
特征周期 = 径向平均功率谱的质心倒数。与设计文档 §3.1.1 表格（短边 3072px：100→8.1px、
6400→26.6px，那是**最低频八度**的理论周期）方向与比例一致。

### 4.4 频谱形态：颗粒 vs 块状（判据 2 前半）

```
④ 谱形态（低频能量占比，k<0.05 cyc/px = 周期>20px 的"块"）：新实现 ISO400 9.0% / ISO1600 8.8% / ISO6400 14.7%（全部 <15% = 宽带颗粒）
   ｜旧实现 size=24（格子 4px = 同量级视觉尺度） 51.7% = 块状
   判据 a（新实现全档 <15%）= True；判据 b（旧实现 >30%）= True
```

### 4.5 目视截图（判据 2 后半）

`tools/grain-exp/out/`（**不入库**，`.gitignore` 已排除）：

| 文件 | 内容 |
|---|---|
| `crop_new_iso1600_s25_rmsnorm.png` | 新实现 ISO 1600、强度 25、512² 中心 256² 裁切、RMS 归一化 |
| `crop_new_iso100_s25_rmsnorm.png` | 同上、ISO 100（更细档） |
| `crop_old_size24_rmsnorm.png` | 旧实现 `size=24`（唯一能达到同量级视觉尺度的档位） |

两者 RMS 已归一化到同一值（12 L 单位）⇒ 差异只剩**形态**：新实现是细密的多尺度颗粒；
旧实现是模糊的大块斑块（"块状糊"）。

### 4.6 `[60]` 调制曲线（判据 4）

```
⑤ [60] 调制曲线（三段灰 RMS，暗/中/亮）：mb=100 → 6.39/11.89/5.74（中间最大 = True）；
   mb=0 → 11.76/11.97/11.74；两端/中间比 mb=0 = 1.963 > mb=100 = 1.021
```
mb=100（darktable 默认）：中间调最重、两端趋 0（falloff 最强）；
mb=0：曲线平缓、覆盖整个影调范围。两条曲线形状确实不同。

### 4.7 种子（判据 6）

```
⑦ 种子：同 seed 两帧逐位相同 = True（预览不闪）；seed 0 vs 7.5 图案差异 max|Δ| = 71.5 LSB
```
- 预览不闪：`submitFrame` / `submitDisplay` 各自置 `grain_seed_ = 0` ⇒ 同一输入同一参数
  ⇒ 逐位相同。
- 成片可复现：`grain_seed_ = grainSeedFrom(内容指纹, dst_w, dst_h)` ⇒ 同一张两次导出逐位相同。
- 不同照片不同图案：指纹来自 RAW / RGB 数据内容。

### 4.8 短边归一化（ADR-11）

```
⑧ 短边归一化（ISO 6400）：3072px 上周期/短边 = 0.00520、1536px 上 = 0.00520（相对偏差 0.1%，判据 <15%）
```
预览（降采样）与导出（全分辨率）的颗粒**相对**空间频率一致 ⇒ ADR-11 的"跨分辨率不一致"
问题一并修掉（旧实现用屏幕像素坐标）。

### 4.9 工具链全绿

```
$ bash tools/validate-shaders.sh                 → 全部 shader 校验通过
$ python3 tools/check-diag-fields.py             → 全部通过
$ python3 tools/check-style-passthrough.py       → 全部通过（含新增 ⑨b 颗粒两处逐字一致、⑯a、⑯c）
$ python3 tools/grain_lut_ref.py                 → 自检通过（7 项）
```

---

## 5. 构建与契约核对

### 5.1 `[26]=0` 与改前一致的"日志回读"

诊断字段已就位（`submitDisplay` 出口 + `finishStillChain` 的 `raw.render` 出口）：
`grain` / `grainSz` / `grainMb` / `grainPivot` / `grainSeed`。
`[26]=0` 时 shader 整段跳过 ⇒ 成片与改前**逐位相同**（离线已证；真机日志核对见 §7 限制）。

### 5.2 Kotlin 侧默认值核对（未改，仅核对）

`CameraPresets.neutral()` 与 `FilmParams.h` 兜底一致：`[26]=0`（ADR-10 红线守住）、
`[27]=400`、`[60]=100`、`[61]=0`。**Kotlin 侧未做任何数值改动。**

### 5.3 槽位契约未动

`kCount = 96`、槽位语义、`fromDoubles` 解析 —— 全部未改（本卡只消费既有契约）。

### 5.4 强制真编 + 构建退出码（判据 8）

```
# 先删除 native 产物（绝对路径已核对，均在 app/build/ 下）：
#   app/build/filmengine/{arm64-v8a,x86_64}/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
#   app/build/**/libfilmengine.so（10 个）    → 删除后 find 计数 = 0
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
GRADLE_EXIT=0
```
- 首次构建曾**失败**：`ld.lld: undefined symbol: filmkit::(anonymous namespace)::grainFingerprint`
  —— 我把定义放在了匿名 namespace 之外（§6.11），已修。
- 第二次失败：`EditorStyleWhitelistTest > kdocListMatchesScriptWhitelist` —— 白名单加了两项
  但 `EditorEngineSession.kt` 的 KDoc 清单没同步（三方一致护栏，§1.4），已修。
- **最终一次（删 `.o`/`.so` 之后）**：`GRADLE_EXIT=0`，且日志里确有真编动作 ——
  ```
  > Task :app:buildFilmEngine
  [  7%] Building CXX object CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
  [ 15%] Linking CXX shared library libfilmengine.so
  （arm64-v8a 与 x86_64 各一遍）
  BUILD SUCCESSFUL in 26s
  ```
  产物时间戳 = 本次：`FrameRenderer.cpp.o`（4.66 MB）、`libfilmengine.so`（8.69 MB）、
  `app/build/outputs/apk/debug/app-debug.apk`（50.2 MB）。
  单测 XML 为本次新生成（`timestamp="2026-10-10T02:56:10Z"`）：**378 tests / 0 failures**，
  其中 `EditorStyleWhitelistTest` 3 个用例（三方一致护栏）全通过。

---

## 6. 超出规格文档的判断（逐条，请架构师审核）

> 以下每条都是我在规格之外**自己做的决定**，按任务卡要求全部列出。

### 6.1 ★ `[61] grain_pivot` 折进 LUT（而非在 shader 里 `pow`）

**规格**：`l' = pow(l, 1+3·pivot)`，"作用在 `paper_resp_inverse` 的亮度输入前"。

**我的做法**：把 `pow` 折进 **CPU 侧的 128×128 LUT**（LUT 行 j 存 `100·(paper_resp(gu + inverse(l'ᵢ)) − l'ᵢ)`，
`l'ᵢ = pow(lⱼ, k)`），shader 里没有 `pow`。

**理由**：
1. **`pivot=0` 的逐位保证更强**：CPU 侧 `k = 1.0f` 时走短路分支（不调 `pow`）⇒ **逐位**等于
   darktable 原版（离线实测误差 `0.000e+00`）。若放 shader 里，`pow(x, 1.0)` 在 ES 3.0 下
   精度"继承自 `exp2(y·log2(x))`"，**不保证**返回 x ⇒ 验收判据 5 有被浮点误差打破的风险。
2. **零 GPU 成本**：12.5M px × 1 次 `pow` 归零，改为 `[61]` 变化时 16384 次 CPU `pow`（亚毫秒）。

**代价**（如实记录）：`pivot > 0` 时插值发生在**原始亮度空间**而非 `pow` 后空间，与"shader 内 pow"
有 ~1/127 采样分辨率的离散化差异。`pivot = 0` 时两者完全相同。第一版不标定，实际影响可忽略。

### 6.2 ★★ LUT 的减数是 `l'`（pow 之后）而不是 `l`

**规格字面**："把 `paper_resp_inverse(l)` 的**亮度输入**做一次亮度重映射" ⇒ 只替换输入、保留 `− l`。

**问题**：这样 `gu = 0` 处 `LUT = 100·(l' − l) ≠ 0` ⇒ `[61]` 变成一个**整体亮度**旋钮。
**实测**（`tools/grain_lut_ref.py` 的中间版本）：

| pivot | `gu≈0` 处 LUT（减 `l`） | `gu≈0` 处 LUT（减 `l'`） |
|---|---|---|
| 0 | −0.39 / +0.39（对称，无偏移） | 同左 |
| 0.1 | **−9.7 / −9.0** | ±0.0012 |
| 0.3 | **−23.5 / −22.9**（≈ −60/255 全图压暗） | ±0.0012 |

**我的做法**：取 `− l'`。**理由**：LUT 的物理含义是"噪声 `gu` 带来的亮度增量"，
`paper_resp(inverse(y)) ≡ y` ⇒ `gu = 0` 时增量必须恒 0（这是 darktable 设计的性质：
`pivot = 0` 时 `− l` 与 `− l'` 是同一个式子）。带偏移的版本会让"颗粒旋钮改画面亮度"，
与 `[61]` 的用途（移动调制峰值）完全不符。
**离线判据已固化**：`grain-exp.py` ① 与 `grain_lut_ref.py` ④ 都检查 `|gu≈0 处 LUT| < 1e-2`。

### 6.3 ★★ 规格 §3.1.5 的公式与文字自相矛盾 → 按**公式**字面实现

**规格文字**："`pivot > 0` ⇒ 低亮度 `l` 被**抬**到 `l'` 的高位 ⇒ 颗粒峰值移向**暗部**"。
**规格公式**：`l' = pow(l, 1 + 3·pivot)`，指数 > 1 对 `l ∈ (0,1)` 是**压暗**（规格自己举的例子
`l=0.18 → l'=0.00105` 也印证是压暗）。

**实测**（`grain_lut_ref.py` ⑤，`|LUT(gu≈0,·)|` 峰值位置）：

| pivot | k | 峰值位置 l |
|---|---|---|
| 0 | 1.0 | 0.496 |
| 0.1 | 1.3 | 0.583 |
| 0.3 | 1.9 | **0.693**（移向**亮部**） |

**我的做法**：按**公式**字面实现（任务卡也明确写了这条公式），效果是**峰值移向亮部**，
与规格文字相反。`pivot = 0` 两种解释完全一致 ⇒ 不影响默认行为与验收判据 5。
**若要按文字意图**（峰值移向暗部），把 `GrainLut.h` 的 `k = 1.0f + 3.0f * pivot` 改成
`k = 1.0f / (1.0f + 3.0f * pivot)` 即可（一行，`pivot = 0` 时两式同为 1.0f）。
**请架构师裁决方向**；第一版不做标定（规格 §3.1.5 要求）。

### 6.4 ★ 种子 = **内容指纹**，而不是规格写的"拍摄时间戳 ms"

**规格 §3.1.6**：成片用"输出尺寸 + 拍摄时间戳 ms"派生。

**勘察结论**：**引擎内没有任何时间源** —— `grep -n "timestamp|time(nullptr)|std::time|exif"
core/engine/src/render/FrameRenderer.{cpp,h}` = **0 命中**；`ChainCtx` 与 `submitStill` 都没有
时间参数。要拿时间戳必须改 JNI 契约（超出本卡范围）。

**我的做法**：用**输入数据的内容指纹**（FNV-1a 采样 ≤1024 点，RAW 用 `w·h·2` 字节、
YUV 路径用 CPU 转换后的 `rgb_stage_`），与输出尺寸一起派生 `uGrainSeed ∈ [0, 64)`。
**理由**：
1. **更严格地满足验收判据 6**："同参数两次导出逐位相同"—— 时间戳方案若取"导出时刻"会
   **破坏**它（两次导出时刻不同）；内容指纹则天然满足。
2. 同时满足"不同照片不同图案"（darktable 用文件名 hash 达到同一目的）。
3. 零契约改动。

**已知边界**：连拍中两张**逐字节相同**的 RAW 会得到同一图案（可接受：内容相同）；
单帧档与 burst 档对同一场景会得到不同图案（输入不同，属预期）。

### 6.5 LUT 纹理：两条链路**共用一份**（纹理单元 2）

`tex_grain_lut_` 是 `FrameRenderer` 的单个成员，`passOutput`（预览 + 成片）与 `passStyle`
（编辑器）共用；缓存键 `(grain_lut_mb_, grain_lut_pivot_)`。
**理由**：LUT 内容只依赖 `[60]`/`[61]`，与输入无关，且两条链路在同一个 GL context 里 ⇒
没有理由各存一份（省 64KB 与一次重算）。
纹理单元 = **2**（0 = 输入帧，1 = 3D LUT，2 空闲）。
**格式 = `GL_R16F`**（规格首选）：LUT 值域 ±50 L 单位，half 的相对精度 2^-11 ⇒ 绝对误差
≤ 0.05 L 单位，远优于 LUT 自身 1/127 的采样分辨率（≈0.8 L 单位）⇒ **不需要退到 RGBA8**。
创建用 `glTexStorage2D`（不可变存储，v0.10.50 真机定案）+ `glTexSubImage2D` 更新。

### 6.6 ★ `ensureGrainLut` 内部显式 `glActiveTexture(GL_TEXTURE2)`（自查捕获的 bug）

`glBindTexture` / `glTexStorage2D` / `glTexSubImage2D` 全部作用于**当前 active 单元**，而
`ensureGrainLut` 是在调用方的 `glActiveTexture(GL_TEXTURE0)` + 绑定输入帧 `uTex` **之后**
被调用的 ⇒ 不切单元会把**单元 0 的输入帧纹理改绑成 grain LUT**，`uTex` 采样到 LUT 内容、
整帧画面错乱。只在**首帧**与 `[60]`/`[61]` 变化时发生，是最难查的间歇性错误。
（离线验收走 Python 侧纹理绑定，**覆盖不到这条路径** —— 这是本卡唯一靠人工审查发现的缺陷，
已修并留注释。**真机首帧必须目视确认**。）

### 6.7 perm 表用 256 项 + `& 255`（等价于 darktable 的 `perm[512]`）

darktable 的 `perm[512]` 本身就是 `permutation[i & 255]` 的查表结果。GLSL 里用
`kGrainPerm[256]` + `int grainPerm(int i) { return kGrainPerm[i & 255]; }` 更短且语义等价
（索引表达式 `grainPerm(ii + grainPerm(jj + grainPerm(kk))) % 12` 与 darktable 的
`perm_mod[ii + perm[jj + perm[kk]]]` 逐项对应）。**未用 sin-hash**。
常量数组 + 运行期索引在 GLSL ES 3.0 合法，且**本项目已有先例**（`kFsChromaDown` 的
`const float B3[5] = float[5](…)` 与循环内 `B3[i]*B3[j]`，v0.10.174 真机在跑）。
⚠ 注：`FrameRenderer.cpp:4574` 的注释"GLSL ES 不支持数组初始化器"是**过时/错误**的
（那是 ES 1.00 的限制）—— 本次未改该注释（不在本卡范围），但报告在此记录。

### 6.8 `grad3` 用 `const vec3 kGrainGrad3[12]` 常量数组

直接照抄 darktable 的 `grad3[12][3]`（12 个面心梯度），而不是算术展开 —— 零翻译风险。

### 6.9 `uGrainSize` 从 shader 移除（CPU 侧算 `uGrainZoom`）

shader 里不再有 `clamp` 与除法（与 E4-2 暗角的 CPU 侧域常数同款）。诊断字段 `grainSz`
仍在 CPU 侧读 `p.grain_size`（用户可见的 ISO 值），不受影响。

### 6.10 `[60]`/`[61]` 在 CPU 侧 clamp

`std::clamp(p.grain_midtone_bias, 0, 100)` / `std::clamp(p.grain_pivot, 0, 1)`。
**理由**：`[60]` 越界会让 `delta = 2·exp((mb/100)·ln(1e-4))` 落进非物理域（mb<0 ⇒ δ>2；
mb≫100 ⇒ δ 下溢），`[61]` 越界会让 `pow` 指数非正。`[26]` **未** clamp（保持既有语义，
`if (uGrainAmount > 0.0)` 门控已拦住非正数；上限由 UI 保证 0..100）。

### 6.11 ★ `grainFingerprint` / `grainSeedFrom` 必须定义在**匿名 namespace 内**

首次构建的链接错误：`undefined symbol: filmkit::(anonymous namespace)::grainFingerprint`。
原因：前置声明写在文件头部的匿名 namespace 里，但定义写在了文件后部（那里匿名 namespace
**已经结束**）⇒ 定义落到 `filmkit` 而非匿名 namespace，与声明是两个不同实体。
**已修**：定义前移到声明处（匿名 namespace 内），并留注释警告。
（这是纯 C++ 链接语义问题，与 GLSL 三禁无关。）

### 6.12 `check-style-passthrough.py` 的 ⑯b（[27] 单调）**移到** `grain-exp.py`

**原因**：噪声坐标按短边归一化 ⇒ 特征周期 = `zoom/f × 短边像素数`。该脚本默认 96²，
此时最低频八度周期 0.25 px（ISO 20）~ 0.83 px（ISO 6400）—— **全部欠采样**，四档 ISO
退化成同一档逐像素白噪声（实测差分/总量比 1.998/2.011/2.009/2.008，非单调）。
**这不是实现缺陷，是判据与分辨率的匹配问题**（真机短边 3072px 下同一 ISO 档周期 8.1~26.6 px）。
⇒ 尺度类判据（`[27]` 单调、短边归一化、FFT 径向平均谱、按亮度分箱 RMS）全部放在
`tools/grain-exp/grain-exp.py`（默认 3072²，与设计文档 §3.1.1 的表格同基准）。
`check-style-passthrough.py` 保留与分辨率无关的 ⑯a（零回归）与 ⑯c（调制曲线形状）。

### 6.13 `check-style-passthrough.py` 的测试 ③ 强度 0.5 → 25

`[26]` 语义变更后 0.5 = 强度 0.005（几乎不可见）⇒ 旧测试的"改动像素 > 5%"判据会失败。
改用 darktable 默认强度 25。**这是测试适配，不是实现标定。**

### 6.14 `EditorEngineSession.kt` 的 KDoc 槽位清单同步

`EditorStyleWhitelistTest`（E2-23 护栏）要求"脚本白名单 / 引擎实际读取 / KDoc 清单"三方一致。
我把 `[60]`/`[61]` 加进白名单与引擎读取后，KDoc 也必须加（29 → 31 个槽位），否则单测红。
**这是契约文档同步，不是功能改动。**

---

## 7. 真机验证状态：**未真机验证**

**原因（客观限制，不是跳过）**：
- `adb devices -l` ⇒ **无连接设备**（List of devices attached 为空）；
- `~/Library/Android/sdk/emulator/` **不存在**（SDK 无 emulator 组件），`~/.android/avd/` 不存在
  ⇒ 无法启动模拟器。

**已用能力上限内的替代手段**：
1. **`glslangValidator` 静态校验**全部 shader（含两条链路的颗粒段）⇒ 语法/类型合法；
2. **真跑引擎自己的 shader**（`tools/engine-shader-offline.py` 的 moderngl/GL 4.1 执行器）
   跑 `kFsStyle` 的**那份源码**（与 `kFsOut` 颗粒段逐字相同，由 ⑨b 守护）⇒ 数值行为已验证；
3. **C++ 侧 LUT 输出**逐点对照（`grain-lut-dump` include 引擎自己的 `GrainLut.h`）。

**真机上仍需确认（离线覆盖不到）**：
- `[26]=0` 时成片与改前**逐位一致**（日志回读 `grain`/`grainSz`/`grainMb`/`grainPivot`/`grainSeed`）；
- **首帧画面正常**（§6.6 的纹理单元 bug 已修，但离线覆盖不到该路径 —— 首帧必须目视）；
- 颗粒在真机显示域观感（含高饱和区是否"发灰"，规格 §3.1.3 第 6 项的"待实测"）；
- **GPU 成本**（3 次 simplex/px ≈ 250 ALU/px；规格 §3.1.8 要求实测；预览每帧跑）。
  ⇒ 这两条按项目铁律**必须实测后由用户拍板**，我不替用户决定。

---

## 8. 验收判据逐条对照（任务卡 9 条）

| # | 判据 | 结论 | 证据 |
|---|---|---|---|
| 1 | `[26]=0`：离线逐位不变 + 真机成片与改前一致（日志回读） | **离线通过 / 真机未验** | 3072²：`max\|Δ\| = 1 LSB，>1LSB 像素 = 0/28311552`（1 LSB = 既有 TPDF dither，非颗粒）；96² 同判据见 ⑯a。**真机日志回读未做**（§7 无设备） |
| 2 | 开启后呈"颗粒感"而非"块状"（频谱 + 目视） | **通过** | 频谱：低频能量占比 新 8.8–14.7% vs 旧（同尺度）51.7%，斜率 +0.07 vs −3.78；目视：`out/crop_new_iso1600_s25_rmsnorm.png` vs `out/crop_old_size24_rmsnorm.png`（RMS 已归一化） |
| 3 | `[27]` 单调：ISO 越大越粗 | **通过** | 实测特征周期 **100→5.24 / 400→5.66 / 1600→7.61 / 6400→15.97 px**（严格单调） |
| 4 | `[60]` 生效：mb=0 与 mb=100 曲线不同；mb=100 中间调最重、两端趋 0 | **通过** | mb=100 三段灰 RMS 6.39/**11.89**/5.74（中间最大 ✓，两端 ≈ 峰值一半）；mb=0 → 11.76/11.97/11.74；两端/中间比 1.963 vs 1.021 |
| 5 | `[61]=0` 逐位等于"不启用 pivot"路径 | **通过（误差 0.000e+00）** | `grain-exp.py` ⑥：C++ 的 `buildGrainLut(mb, 0)` ↔ numpy 直译的 **darktable 原版公式**（不含 pivot 项）`max\|Δ\| = 0.000e+00`；另 `grain_lut_ref.py` ⑥ 验证 `pivot=0` 走短路分支（不调 `pow`） |
| 6 | 预览不闪（连续两帧 seed 相同）+ 成片可复现（同参数两次导出逐位相同） | **通过（离线）** | 同 seed 两帧 `逐位相同 = True`；预览/编辑器路径 `grain_seed_ ≡ 0`（`submitFrame`/`submitDisplay`）；成片 `grain_seed_ = f(输出尺寸, 内容指纹)` ⇒ 同一张两次导出必然同 seed ⇒ 逐位相同。**真机两次导出比对未做** |
| 7 | `validate-shaders.sh` + `check-diag-fields.py` + `check-style-passthrough.py` 全绿 | **通过** | 三者退出码均 0（`shaders=0 / diag=0 / style=0`）；`⑨b 颗粒实现逐字比对：差异段 = 无（0 处）` |
| 8 | `gradlew assembleDebug testDebugUnitTest` 退出码 0（单测 XML 为本次新生成） | **通过** | 删 `.o`/`.so` 后 `GRADLE_EXIT=0`；日志含 `Building CXX object …FrameRenderer.cpp.o` + `Linking CXX shared library libfilmengine.so`；XML `timestamp="2026-10-10T02:56:10Z"`（本次），**378 tests / 0 failures** |
| 9 | 真机/模拟器至少一次运行 + 日志核对 | **未真机验证** | §7：`adb devices` 无设备、SDK 无 emulator 组件、无 AVD。**明确记录为未验证**，并列出真机必须确认的 4 项 |

## 9. 未做 / 存疑项

| 项 | 状态 | 说明 |
|---|---|---|
| `[61]` 方向 | **存疑，待裁决** | §6.3：公式 ⇒ 峰值移向亮部，与规格文字相反 |
| `[61]` 标定 | **未做（按规格）** | 规格 §3.1.5 明确"第一版只实现、默认 0，不做标定" |
| 单色性（差异第 6 项） | **未改（按规格）** | 规格判定"待实测"，先保留现状（RGB 同加标量） |
| 真机验证 | **未做** | §7（无设备/无模拟器） |
| 性能实测 | **未做** | §7；需真机 GPU timer |
| `halation` / `vignette` 等其它 E4 项 | 不在本卡范围 | — |
| 旧 `hash()` 的删除 | 已完成 | 它只服务颗粒（`grep` 确认），dither 走 `dhash` |
| `FrameRenderer.cpp:4574` 的过时注释 | **未改** | 见 §6.7 末尾（不在本卡范围） |

---

## 10. 复现命令

```bash
# 离线验收（全部自带依赖说明）
python3 tools/grain_lut_ref.py                     # numpy 复算自检（7 项）
python3 tools/grain-exp/grain-exp.py               # 主实验：3072² 真跑 shader + 频谱 + 目视
python3 tools/grain-exp/grain-exp.py --quick       # 只跑 LUT 对照（秒级）
bash    tools/validate-shaders.sh                  # glslang 静态校验
python3 tools/check-diag-fields.py                 # 诊断字段可验证性
python3 tools/check-style-passthrough.py           # 编辑器通路 + 颗粒两处逐字一致

# 构建（强制真编）
rm -f app/build/filmengine/*/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
find app/build -name 'libfilmengine.so' -delete
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest; echo "EXIT=$?"
```

产物（**不入库**）：`tools/grain-exp/out/{report.json, psd_*.npy, crop_*.png, lut_*.raw}`。
