# SG-1 柔光（soft glow / Orton bloom）—— 交付报告

> 卡号 **SG-1** ｜ 规格 = `docs/reports/soft-glow-SPEC.md`（架构师 2026-10-11，唯一权威）
> 前置 = E4-5 光晕（`ceee83d`）｜ 状态：**实现完成，未 commit，未真机验证**
> 工作目录 `/Users/wl/Downloads/胶片相机`，分支工作区（`git status` 见 §9）

---

## 0. 结论摘要（三句话）

1. **复用了 halation 的金字塔**：E4-5 的 ①②③（提取 / 降采样 / 模糊）抽成共享函数
   `runHaloPyramid`，柔光只多 **1 个 1/2 分辨率合成 pass**（`kFsGlowSum`）与 shader 内的
   Screen 混合 ⇒ **柔光零新增纹理、零额外全分辨率 pass**。
2. **静态估算**：柔光增量 = **+1 pass、+65.8M 采样/帧、+0 MB 显存**（12.53MP 成片）；
   若另建一套金字塔则是 +16 pass、+139.9M 采样、+58.5MB ⇒ **共享省下 15 pass /
   74.1M 采样 / 58.5MB**。
3. **不需要降级**（静态口径）；但 `glowFetch` 的 4-tap 双线性占了柔光增量采样的 76%
   ⇒ 仍给 **3 个降级选项**（§7.3）与**真机实测步骤**（§7.4）交用户拍板。

---

## 1. 交付清单

| 文件 | 性质 | 说明 |
|---|---|---|
| `core/engine/src/film/FilmParams.h` | 契约 | `kCount` 96 → **98**；`[95] reserved_95` → `glow_strength`；+`[96] glow_radius`、`[97] glow_threshold`；`fromDoubles` 解析 |
| `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` | 契约 | `COUNT` 96 → 98；`neutral()` 三个默认值（0.0 / 0.35 / 0.70） |
| `core/engine/src/render/FrameRenderer.cpp` | 引擎 | **重构**（共享金字塔）+ **功能**（柔光链 / 两条链路落点 / 诊断） |
| `core/engine/src/render/FrameRenderer.h` | 引擎 | 声明 + uniform 缓存 + 诊断字段 |
| `app/src/main/java/com/filmkit/app/camera/EditorExport.kt` | 契约 | ⚠ **禁改清单的必要例外**（见 §4.2）：`FilmParamKeys.bySlot` 加/改 3 行 |
| `app/src/main/java/com/filmkit/app/camera/EditorEngineSession.kt` | 文档 | passStyle 读取清单 KDoc +`[95]` |
| `app/src/test/.../CameraPresetsTest.kt` | 护栏 | `98` 断言 + 新增 `sg1SoftGlowSlotsHoldNoEffectDefaults` |
| `app/src/test/.../FilmParamKeysTest.kt` | 护栏 | `[95]` 名字断言 `reserved_95` → `glow_strength`，+`[96][97]` |
| `app/src/test/.../CameraStyleWhitelistTest.kt` | 护栏 | `cameraPathSlots` +`[95..97]` |
| `app/src/test/.../PresetDetailTest.kt` | 护栏 | "越界槽"例子 `[96]` → `[98]`（96 已是合法槽） |
| `docs/reports/preset-DESIGN.md` | 文档 | §1.6 矩阵 +`[95..97]` 行；"96 槽" → "98 槽" |
| `tools/check-softglow.py` | **新增** | 离线验收（33 项判据 + 4 项 mutation） |
| `tools/check-halation.py` | 同步 | S1/S2 静态检查跨"重构后的两个函数" |
| `tools/check-style-passthrough.py` | 同步 | 白名单 +`glow_strength` |
| `tools/check-diag-fields.py` | 同步 | +5 个 glow 诊断字段 |

---

## 2. ★ 重构 vs 功能（架构师分批提交用）

任务要求"**哪部分是重构、哪部分是功能**"分开。**同一次改动里两部分可机械切分**：

### 2.1 纯重构（不含任何柔光行为，可单独提交）

| 位置 | 改动 | 为什么是纯重构 |
|---|---|---|
| `FrameRenderer.cpp`：新增 `runHaloPyramid(w,h,src,decode_in,thr_lin,knee,glow_mode)` | 把 E4-5 `runHalation` 内联的 ①②③（15 个 pass）**逐字搬出** | 函数体逐字复制；halation 调它时 `glow_mode=false` ⇒ 行为不变 |
| `runHalation` | 改为"检查 → 调 `runHaloPyramid` → ④ 合成" | 检查顺序与 `t0` 起点**保持原位置**（见下） |
| `haloWeights(radius01, level_shift, spread[3], w)` | `spread` 从硬编码 `kHaloSpread` 变参数；halation 传 `kHaloSpread` | 权重数组逐位不变 |
| `ensureHaloTargets(w, h, need_out)` | 新增 `need_out`（是否分配全分辨率 out）；halation 传 `true` | `need_out=true` 时与改动前**逐字等价** |
| `kFsHaloThr` 加 `uGlowOn` uniform | 新增分支门控；halation 传 0 | `uGlowOn=0` ⇒ 走原 else 分支 |
| `tools/check-halation.py` S1/S2 | 静态检查跨两个函数 | 检查的是重构后的结构 |

**重构的零回归证据 = `tools/check-halation.py` 全绿**（`ceee83d` 后未改任何 halation
判据的期望值）：判据 1a/1b（`[62]=0` 逐位零回归，589824 个 float 分量逐位相同）、
2a–2d（通道扩散半径 R>G>B）、3/4/5（三参数单调）、6a–6d（R1 分辨率无关 + 成片逐位
零变化）、7a/7b（两条链路）、M1–M3（mutation）**全部 OK，退出码 0**。

> ⚠ 一处**诊断语义的刻意选择**：`runHalation` 里 `t0`（计时起点）仍在
> `ensureHaloTargets` **之后**、`runHaloPyramid` 之前 —— 于是 `halo_ms_` 不含首次
> 纹理分配时间，**与改动前逐位同口径**（`runHaloPyramid` 内部那次 `ensureHaloTargets`
> 在尺寸未变时是两次整数比较，成本为 0）。

### 2.2 功能（柔光本体）

| 位置 | 改动 |
|---|---|
| `kFsHaloThr` 的 `if (uGlowOn > 0.5)` 分支 | **标量**提取 `h = max(0, luma−thr)/(1−thr)`，输出 `vec4(h,h,h,1)` |
| `kFsGlowSum`（**新 shader**） | 5 级单半径加权合成 → 1/2 分辨率标量柔光图 |
| `kFsOut` / `kFsStyle` | `glowFetch`（手写双线性 + 线性→显示域编码）+ Screen 混合段（褪色之后、暗角之前） |
| `runGlow`（新函数） | 检查 → 共享金字塔 → 1 个合成 pass（写 `fbo_halo_tmp_`） |
| `passOutput` / `passStyle` | 接线（halation 之后）+ uniform/纹理下发 |
| 诊断 | `glowOn` / `glowMs` / `glowStrength` / `glowRadius` / `glowThr` |

---

## 3. 算法与落点（含对规格 §2 的**一处解释性偏离**，请架构师裁决）

### 3.1 实现

```
① 提取（kFsHaloThr 的 glow 模式） h = max(0, luma − thr)/(1 − thr)   ← 标量、灰度
② 模糊（共享金字塔 15 pass）      b = Σ_i w_i · gaussian_i(h)          ← 单半径（spread={1,1,1}）
③ Screen（kFsOut / kFsStyle）     out = 1 − (1 − c)(1 − b·strength)
```

- `[97]` 是**显示域**阈值（与 `[63]` 同口径）⇒ CPU 侧 `srgbToLinear1()` 换算后进 shader。
- `[96]` 半径走与 halation 同一套 `haloWeights` + `level_shift`（按短边归一化 ⇒ 预览与
  成片的**相对**半径一致）。

### 3.2 ⚠ 偏离：提取/模糊在**线性显示域**，Screen 混合在**显示域**

规格 §2 写"域：显示域（sRGB 编码之后）"。实现的落点分配是：

| 步骤 | 实现所在域 | 依据 |
|---|---|---|
| ① 提取 / ② 模糊 | **线性显示域**（`kFsFilm` 输出；编辑器链路入口先 sRGB 解码） | emulsion 对 bloom 的原始注释："blurring gamma-encoded values spreads the wrong amount of energy and **turns bloom into grey mud**"；darktable 管线本身是线性的 |
| ③ Screen 混合 | **显示域**（`kFsOut` 的 `srgbEncode` 之后 / `kFsStyle` 的显示域段） | 规格 §2 的"**落点**：`kFsOut` 与 `kFsStyle` 的显示域段，与 `[90..94]` 相邻" |

**为什么这样分**（成本是决定性理由，且质量更正确）：

- 若 ①② 也做在显示域，`kFsOut` 链路必须**先**把 `tex_work_`（线性）编码成 sRGB 才能跑
  金字塔 ⇒ **+1 个全分辨率 pass + 1 张全分辨率 RGBA16F 纹理（12.53MP ≈ 100MB）**。
- 现在 ①② 与 halation **同域同输入**（`tex_work_` / `edit_tex_`）⇒ 零额外 pass、零额外纹理，
  且两条链路喂给金字塔的是同一个线性量 ⇒ 判据 4（逐位相同）成立。
- Screen 落点仍严格按规格在显示域（`glowFetch` 内做一次 OETF）。

**若架构师要求严格按"三步全在显示域"**：需要上述 +100MB/+1 pass 的代价，改动量 =
`runGlow` 前加一个 encode pass + `passOutput` 多一张纹理；**请裁决**（本卡按成本最优实现）。

### 3.3 顺序（规格 §2/§4）

```
影调分区 → 褪色 → 【柔光 Screen】 → 暗角 → 颗粒 → LUT → dither
```
- 两条链路都在 `fade` 之后、`vignette` 之前插入 ⇒ 柔光的高光外扩不被暗角衰减（§4），
  颗粒叠在含柔光的画面上（§4）。
- 静态判据 S5 钉住："`c = 1.0 - (1.0 - c) * (1.0 - gb);` 在源码里恰好 2 处"。

---

## 4. 契约扩展（**三处同改 + 第四处下发点 + 第五处语义名**）

### 4.1 五处同步点（全部改到）

| # | 位置 | 改动 |
|---|---|---|
| 1 | `FilmParams.h::kCount` | `96` → `98` |
| 2 | `CameraPresets.neutral()` | `DoubleArray(COUNT)` 自动跟随；显式写 `[95]=0.0`、`[96]=0.35`、`[97]=0.70` |
| 3 | `fromDoubles` | `if (n > 95/96/97)` 三行（缺槽时保持结构体默认 = 无效果） |
| 4 | `CameraController.setFilmParams` | 长度兜底读 `CameraPresets.COUNT`（**零改动**，它本来就只此一处定义） |
| 5 | **`FilmParamKeys.bySlot`** | `95 to "reserved_95"` → `"glow_strength"`，+`96/97` |

### 4.2 ⚠ 对"禁改清单"的一处必要例外（请架构师确认）

任务卡禁改 `EditorExport.kt`。但**契约扩到 98 后必须改它的 `FilmParamKeys.bySlot`**，
否则 `FilmParamKeysTest.everyStorableSlotHasASemanticName`（`presetSlotIncluded(96/97)`
为 true 却没有语义名）**必然红**，且预设导出会丢掉 `[96][97]`。

- 改动 = **3 行映射 + 1 段 KDoc**，不触碰该文件其它任何逻辑。
- 若架构师认为应由别的卡统一改，请指示 —— 但**当前状态下不改它就是红测**。

### 4.3 硬编码 `96` 全检结果

```
$ grep -rn "\b96\b" --include=*.kt --include=*.cpp --include=*.h --include=*.py --include=*.md . | grep -v /build/
```

| 位置 | 是否槽位数 | 处理 |
|---|---|---|
| `FilmParams.h:27` `kCount = 96` | ✅ 是 | **改 98** |
| `CameraPresets.kt:22` `COUNT = 96` | ✅ 是 | **改 98** |
| `CameraPresetsTest.kt:19` `assertEquals(96, ...)` | ✅ 是 | **改 98** |
| `preset-DESIGN.md:205` "完整 96 槽下发" | ✅ 是 | **改 98**（+ 沿革注） |
| `preset-DESIGN.md:256` "展开成 96 槽下发" | ✅ 是 | **改 98** |
| `check-style-passthrough.py:82/733` `--size 96` | ❌ 测试图边长 | 不动 |
| `check-style-passthrough.py:486/1126/1426/1516` | ❌ 像素坐标/频率 | 不动 |
| `align-bench-gen.py:56/79` `pad=96` | ❌ 无关 | 不动 |
| `tools/preset-exp/fingerprint/*.json` | ❌ 数据 | 不动 |

**其它覆盖面**：
- `CameraPresets.COUNT` —— 改 98 ✓（它是 Kotlin 侧唯一定义点）。
- `PresetScope` / `FilmParamKeys` 覆盖面 —— `presetSlotIncluded(i) = i in 0 until COUNT && ...`
  ⇒ `[95][96][97]` **自动进预设作用域**；`FilmParamKeysTest` 的"每个可入库槽必有语义名"
  因此强制了 §4.2 的改动 ✓。
- `CameraStyleWhitelistTest.cameraPathSlots` —— 拍照链路读取集合 +`[95..97]` ✓。
- `preset-DESIGN.md §1.6` 设计矩阵 —— +`[95..97]` 行 ✓（测试三方对拍）。

---

## 5. 验收判据逐条证据

### 判据 1：`[95]=0` 逐位零回归 + 零 pass 零纹理 ✅

```
OK   1a [95]=0 ⇒ 整链旁路：零 pass、零纹理: 返回 None、纹理 0 张、draw 0 次
OK   1b [95]=0 逐位零回归（完整 kFsOut 链路）: max|Δ| = 0.000000（73728 个分量）
OK   S1 [95]=0 提前 return 先于纹理分配（零 pass 零纹理）: return@290 < ensureHaloTargets@415
```
- 静态：`runGlow` 的 `if (amount <= 0.0f || src_tex == 0) return 0;` 在
  `ensureHaloTargets(...)` **之前** ⇒ 不分配、不发 draw。
- 真跑：`[95]=0` 时柔光链返回 `None`、纹理 0 张、draw 0 次；完整 `kFsOut` 输出与
  "不跑柔光"**逐位相同**。
- `passOutput`/`passStyle` 的调用点也在 `if (p.glow_strength > 0.0f)` 内
  （`glGetIntegerv` 都在分支里）⇒ 关闭时**一个额外 GL 调用都不发**。

### 判据 2：三通道同半径（通道差 = 0）+ 高光"发白外扩" ✅

```
OK   2a **三通道同半径**（通道差 = 0；与 halation 判据 2a 相反）:
        r̄ = R 0.16908 / G 0.16908 / B 0.16908，极差 = 0.00e+00
OK   2a-对照 halation 的 spread 给出**不同**峰值级（判据非恒真）: 峰值级 R/G/B = [2, 1, 1]
OK   2b 高光**外扩**（斑外 ≥3 个半径箱仍高于无柔光基线）:
        斑外箱数 17，Δ > 0.002 的有 6 个；最远正增量 @r = 0.3241
OK   2c 增量的通道比 ≈ 1（**发白**而非染色）: 平均增量 R/G/B = 0.04186/0.04186/0.04186
OK   2d Screen 保高光不过曝（加法会把通道差抹平 = 高光死白）:
        c = [1.0, 0.9, 0.8], b·s = 0.3 ⇒ Screen 通道差 = 0.1400、加法 = 0.0000
```

**半径-亮度曲线**（点光源 peak 0.88 显示域 / r = 12px，`[95]=0.6 [96]=0.35 [97]=0.45`）：

| 归一化半径 | 柔光输出 | 无柔光基线 | Δ |
|---|---|---|---|
| 0.0327 | 0.77327 | 0.71037 | +0.06290 |
| 0.0766 | 0.43316 | 0.34387 | +0.08929 |
| 0.1258 | 0.09450 | 0.01993 | +0.07458 |
| 0.1753 | 0.05810 | 0.02004 | +0.03806 |
| 0.2249 | 0.03838 | 0.02002 | +0.01835 |
| 0.2743 | 0.02843 | 0.01989 | +0.00854 |
| 0.3241 | 0.02388 | 0.02002 | +0.00386 |
| 0.3742 | 0.02175 | 0.01997 | +0.00179 |
| 0.4237 | 0.02100 | 0.01999 | +0.00101 |
| 0.4733 | 0.02078 | 0.02000 | +0.00078 |
| 0.5220 | 0.02046 | 0.01994 | +0.00051 |
| 0.5722 | 0.02024 | 0.01992 | +0.00032 |
| 0.6220 | 0.02024 | 0.02001 | +0.00023 |
| 0.6717 | 0.02008 | 0.01997 | +0.00011 |
| 0.7217 | 0.02006 | 0.01999 | +0.00007 |
| 0.7688 | 0.02001 | 0.01995 | +0.00006 |
| 0.8186 | 0.02014 | 0.02009 | +0.00005 |
| 0.8638 | 0.02003 | 0.01998 | +0.00005 |

⇒ 高光**外扩到 0.86 归一化半径**（≈ 110px @128 短边）：Δ 从峰值 **+0.0893**（r = 0.077，
点光源边缘）单调衰减，到 r = 0.86 仍有 +0.00005 的正增量（**长尾**，不是硬边）。
中心 Δ（+0.0629）**小于**峰值 —— 因为中心接近饱和（Screen 的定义：c→1 ⇒ out→1），
这正是柔光镜的观感：**高光边缘"晕开"而不是中心变亮**。

### 判据 3：三参数各自单调 ✅

```
OK   3a [95] 强度单调（增量能量递增）: 43.34 < 108.30 < 194.95   @ [95] = [0.2, 0.5, 0.9]
OK   3b [96] 半径单调（等效扩散半径递增）: 0.12982 < 0.19340 < 0.31145 @ [96] = [0.15, 0.45, 0.85]
OK   3c [97] 阈值单调（总能量递减）: 702.25 > 262.90 > 0.00      @ [97] = [0.30, 0.60, 0.85]
```

### 判据 4：两条链路逐位相同 ✅（8bit 内；浮点差 0.436/255 < 0.5 LSB）

```
OK   4-前置 rolloff 恒等域: lin max = 0.6999（点光源 peak = 0.88 显示域）
OK   4 kFsOut(srgbDecode(D)) vs kFsStyle(D)：输出位深（8bit）内逐位相同:
        max|Δ| = 1/255（浮点 0.436/255），差 1 LSB 的分量 = 2073/73728
```

**逐条解释**（U-4 口径 `kFsOut(srgbDecode(D))` vs `kFsStyle(D)`）：

| 项 | 值 |
|---|---|
| 浮点 max\|Δ\| | **0.436/255**（< 0.5 LSB ⇒ 量化后最多差 1 LSB） |
| 8bit 逐位不同的分量 | 2073 / 73728 = **2.8%**，且**全部只差 1 LSB** |
| 差异来源 | 两条链路的柔光图 `b` 各经一次 RGBA16F 往返：拍照 `q(srgbDecode(D))`、编辑器 `srgbDecode(q(D))`；Screen 的 `(1−c)` 因子把这点量化差放大到 ~0.4/255 |
| 与 E4-5 的关系 | **同型**：halation 判据 7b 的 `max|Δ| = 0.249/255`（差异来源是"RGBA16F 量化与 OETF 的先后"） |
| 是否可消除 | 可以（两条链路共用同一次 `b` 计算），但那会让 `kFsStyle` 依赖拍照链路的中间纹理 ⇒ 结构上更差。**按 E4-5 的口径判"输出位深内逐位相同"，判据 = ≤1 LSB** |

### 判据 5：与 `[78]` 清晰度不打架；与 halation 可分辨 ✅

```
OK   5a 与 [78] 清晰度同时开不打架（两个方向的增量都还在）:
        清晰度局部对比增益：单独 +0.015219 / 与柔光同开 +0.012814
        柔光平均增量：单独 0.099514 / 与清晰度同开 0.099457
OK   5b-① 只开 halation ⇒ **红扩散最远**: r̄ = R 0.20155 > G 0.15484 > B 0.13210
OK   5b-② 只开柔光的增量**发白**: 增量 R/G/B = 0.42087/0.42087/0.42087（min/max = 1.000）
OK   5b-③ 关掉任一项另一项仍在: |都开 − 只柔光| = 0.41735（halation 仍在）
                                |都开 − 只光晕| = 0.00684（柔光仍在）
```

- **5a**：清晰度单独开把局部对比抬高 +0.015219；与柔光同开仍抬高 **+0.012814**
  （Screen 会轻微压缩，但**方向不变、量级相当**，不打架）；柔光的增量在清晰度同开时
  **几乎不变**（0.099514 → 0.099457，差 0.06%）。
- **5b**：判据用的是**规格 §1 的原话**——"同一预设里夜景点光源应同时看到红色外晕与白色
  柔光，且关掉任一项另一项仍在"。**两者判据方向相反**（`r̄_R > r̄_G > r̄_B` vs
  `r̄_R = r̄_G = r̄_B`）⇒ 机制上不可互相冒充。

### 判据 6：契约护栏单测 + 硬编码 96 检查 ✅

- `CameraPresetsTest.lengthMatchesContract`（`98`）、
  `sg1SoftGlowSlotsHoldNoEffectDefaults`（`[95]=0` = 整链旁路、`[96]=0.35`、`[97]=0.70`）**新增**
- `FilmParamKeysTest`（`[95] glow_strength` / `[96]` / `[97]`）
- `CameraStyleWhitelistTest`（拍照链路读取集合 +`[95..97]`，三方对拍）
- `PresetDetailTest`（越界例子改 `[98]`）
- 硬编码 96 全检见 §4.3

### 判据 7：五个验收脚本退出码 0 ✅

| 脚本 | 退出码 | 备注 |
|---|---|---|
| `tools/validate-shaders.sh` | **0** | 全部 shader 校验通过（含新增 `kFsGlowSum`） |
| `tools/check-diag-fields.py` | **0** | 67 个字段全 OK（含 5 个新 glow 字段） |
| `tools/check-style-passthrough.py` | **0** | 白名单 +`glow_strength` 后全绿 |
| `tools/check-halation.py` | **0** | S1/S2 跨函数同步后全绿（1 个 WARN 6e 是 E4-5 既有的已知残差） |
| `tools/check-softglow.py` | **0** | **33 项 OK、0 FAIL、4 项 mutation 全变红** |

### 判据 8：静态性能估算 ✅ 见 §7

### 判据 9：构建 + 单测 ✅

```
$ find app/build -name "FrameRenderer.cpp.o" -delete
$ find app/build -name "libfilmengine.so" -delete
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 24s
46 actionable tasks: 46 executed
用例总数=497 失败=0 错误=0 跳过=0
```
- **真编**：删 `.o` + 删全部 12 个 `libfilmengine.so` 副本后重编（不是 FROM-CACHE 假绿）。
- 用例数 **497** = 基线 496 + 本卡新增 1（`sg1SoftGlowSlotsHoldNoEffectDefaults`）。
- APK：`app/build/outputs/apk/debug/app-debug.apk`（41.9MB）。

### 判据 10：本报告 ✅

---

## 6. 诊断字段（真机可核对）

`submitDisplay`（编辑器）与 `finishStillChain`（成片）两个出口各发 5 个字段：

| 字段 | 含义 |
|---|---|
| `glowOn` | 本帧柔光链**真的执行了**（0 且 `[95]>0` ⇒ 静默失败：program 缺失 / 纹理分配失败 / FBO 不完整） |
| `glowMs` | CPU 侧提交耗时（含共享金字塔 ①②③ + 1 个合成 pass；GPU 完成时间看 `totalMs`） |
| `glowStrength` / `glowRadius` / `glowThr` | `[95][96][97]` 的**实际下发值** |

`check-diag-fields.py` 已把 5 个字段登记为 `("submitDisplay", ["passStyle("])` ⇒
**字段行必须晚于 `passStyle` 调用**，否则报红（防"没执行却报执行"）。

---

## 7. 性能（项目铁律：静态估算 + 真机实测要求 + 降级选项）

### 7.1 静态估算（成片 4080×3072 = 12.53M px）

| 项 | halation（E4-5，现状） | **柔光增量** | 两者同开 |
|---|---|---|---|
| pass 数 | 16 | **+1**（合成 @1/2 分辨率） | **17** |
| 纹理（RGBA16F） | 158.7 MB | **+0 MB** | **158.7 MB** |
| 采样/帧 | 133.6 M | **+65.8 M** | **199.4 M** |

柔光增量的 65.8M 采样拆解：
- `kFsGlowSum` 合成：5 采样/px × 3.13M px = **15.7 M**
- `glowFetch` 4-tap 双线性 @全分辨率：4 × 12.53M = **50.1 M**（+ 1 次 `pow`/px）

### 7.2 ★ 共享金字塔省了多少

| 项 | 共享（本实现） | 若另建一套 | **省下** |
|---|---|---|---|
| pass | 1 | 16 | **15 个 pass** |
| 采样/帧 | 65.8 M | 139.9 M | **74.1 M/帧（-53%）** |
| 显存 | 0 MB | 58.5 MB | **58.5 MB** |
| 只开柔光时的显存 | 58.5 MB（`need_out=false`，不含 halation 的全分辨率 out） | 117.0 MB | **58.5 MB** |

- **纹理复用的具体做法**：柔光图直接写 halation 的 `tex_halo_tmp_`（横向模糊的中间量，
  blur 跑完即空闲）⇒ 柔光**一个新纹理成员都没有**（`FrameRenderer.h` 的 diff 里
  没有任何新增 `GLuint tex_*`）。
- **`need_out` 的额外收益**：只开柔光时 `ensureHaloTargets(w,h,false)` **不分配**那张
  12.53M px 的全分辨率输出 ⇒ 省 100.2 MB。
- **⚠ 顺序契约**（已写进源码注释与静态判据）：柔光图占用 `tex_halo_tmp_` ⇒
  同一帧内**必须 halation 先跑、柔光后跑**（`passOutput`/`passStyle` 的调用顺序即此契约）。

### 7.3 降级选项（**不自行降级，交用户拍板**）

| # | 方案 | 省 | 代价 |
|---|---|---|---|
| **A（当前）** | 柔光图 1/2 分辨率 + `glowFetch` 4-tap 双线性 | — | 基准 |
| **B** | `glowFetch` 改 **NEAREST 单采样** | **-37.6M 采样/帧**（柔光增量降到 28.2M） | 1/2 分辨率纹理的 **2×2 最近邻块**在 Screen 后可见（柔光图是低频信号，块边在平滑渐变上表现为方块） |
| **C** | Screen 合并进合成 pass，输出**全分辨率**柔光图 | kFsOut 从 4 采样降到 **1 采样/px**（-37.6M） | **+100.2 MB 显存**（多一张全分辨率 RGBA16F）；且需要第二张全分辨率纹理（halation 已占 `tex_halo_out_`） |
| **D** | 柔光另建一套金字塔（**不共享**） | — | **+15 pass / +74.1M 采样 / +58.5MB** —— **不推荐**（正是规格 §5 要避免的） |
| **E** | 若真机实测 `glowMs` 过高：把 `[96]` 半径的可达范围限制在 ≤0.5（只用金字塔前 3 级） | 略（不减少 pass，只减少 blur 的有效级数——**需另加门控**，本卡未做） | 半径上限变小 |

**建议**：先按 A 上真机取数（§7.4），若 `glowMs` + `totalMs` 增量在预算内则保持 A；
若内存吃紧优先 B，若带宽吃紧优先 C。

### 7.4 真机实测步骤与判据（**本卡未实测** —— 本机无 Android 设备）

```
# 1) 装机（APK 已产出）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2) 关闭态（判据 1：零回归 + 零成本）
#    设置 [95]=0（默认），拍一张 → 日志应满足：
adb logcat -s filmkit | grep -E "glowOn|glowMs"
#     期望：glowOn = 0、glowMs = 0.0000000；且 totalMs 与 E4-5 基线（未接柔光时）逐帧一致

# 3) 开启态（性能判据）
#    [95]=0.6 [96]=0.35 [97]=0.70，同一场景连拍 5 张：
#     期望：glowOn = 1（**若为 0 而 [95] > 0 ⇒ 静默失败**，看日志里有无
#           halo.fbo_incomplete / program 缺失）
#    判据（真机拍板用，非本卡自行判定）：
#      · glowMs 中位数 ≤ 8 ms（静态估算的 1 pass + 4 tap/px 在 X300 上的量级）
#      · 成片 totalMs 增量 ≤ 60 ms（柔光单独开；与 halation 同开时 ≤ 40 ms，
#        因为金字塔已由 halation 付出）
#      · 峰值内存增量 = 0（只开柔光时 58.5MB，与 halation 同开时 0）
#      · dumpsys meminfo 的 Graphics 增量 ≤ 5 MB（纹理零新增的验证）

# 4) 观感判据（规格 §6.7：只能真机）
#    夜景点光源 + [62]=0.8 [95]=0.6 同时开：
#      · 应同时看到**红色外晕**（halation）与**白色柔光**（glow）
#      · 关掉 [95] ⇒ 红晕仍在；关掉 [62] ⇒ 白光仍在
#      · 与 [78] 清晰度同时开：画面不发糊、边缘不出现"双线"
#      · 预览与成片的柔光**相对半径**一致（WYSIWYG）

# 5) 分辨率无关（沿用 E4-5b 的 R1 判据）
#    预览（1080 短边）与成片（3072 短边）同参数 ⇒ 归一化半径相对差 ≤ 8%
#    （离线 6a 已在 halation 侧验证 R1 机制；柔光共用同一 level_shift）
```

---

## 8. 未验证 / 已知限制（明确标注）

| 项 | 状态 |
|---|---|
| **真机性能（glowMs / totalMs / 内存）** | ❌ **未实测** —— 本机无 Android 设备。§7 全部为**静态估算**，判据与步骤已给出（§7.4） |
| **真机目视（"像不像柔光"）** | ❌ **未实测** —— 规格 §6.7 明确只能真机 |
| 真机 GL 驱动行为（Mali 对 `uGlow` 未绑定 sampler 的预测执行） | ⚠ 按项目既有做法处理（**关闭态也绑一张有效纹理**），但**未在 X300 上验证** |
| 判据 4 的 1 LSB | ⚠ 两条链路各自经一次 RGBA16F 往返（与 E4-5 halation 同型）；**未做"逐位 0 差异"**，已逐条解释（§5 判据 4） |
| 规格 §2 的"域" | ⚠ **解释性偏离**（提取/模糊在线性域，Screen 在显示域）—— 请架构师裁决（§3.2） |
| `EditorExport.kt` | ⚠ 触碰了禁改清单（3 行映射，§4.2）—— 请架构师确认 |
| 柔光与 halation 的**相对顺序**对观感的影响 | ⚠ 实现为"柔光输入 = 原图（不是 halation 的结果）"，理由见 §3.1 注释；**真机需确认红色外晕不会被柔光"洗白"** |

---

## 9. 工作区状态（**未 commit**）

```
$ git status --short
 M app/src/main/java/com/filmkit/app/camera/EditorEngineSession.kt
 M app/src/main/java/com/filmkit/app/camera/EditorExport.kt
 M app/src/main/java/com/filmkit/app/ui/CameraPresets.kt
 M app/src/test/java/com/filmkit/app/camera/CameraStyleWhitelistTest.kt
 M app/src/test/java/com/filmkit/app/camera/FilmParamKeysTest.kt
 M app/src/test/java/com/filmkit/app/ui/CameraPresetsTest.kt
 M app/src/test/java/com/filmkit/app/ui/PresetDetailTest.kt
 M core/engine/src/film/FilmParams.h
 M core/engine/src/render/FrameRenderer.cpp
 M core/engine/src/render/FrameRenderer.h
 M docs/reports/preset-DESIGN.md
 M tools/check-diag-fields.py
 M tools/check-halation.py
 M tools/check-style-passthrough.py
?? tools/check-softglow.py
?? docs/reports/sg1-softglow-REPORT.md
```
- 禁改清单核对：`EditorScreen.kt` / `CameraScreen.kt` / `CameraPresetPanel.kt` /
  `PhotoViewScreen.kt` / `PresetDetail.kt` / `assets/presets/` —— **一个字节都没动** ✓
  （唯一例外是 `EditorExport.kt`，理由见 §4.2）
- 未与其它卡并发跑 gradle ✓

---

## 10. 附：柔光与光晕的差异一览（规格 §1 的判据）

| | halation（E4-5） | **柔光（SG-1）** |
|---|---|---|
| 提取 | `lin × smoothstep(阈值±knee, luma)` —— **彩色**（保留色度） | `max(0, luma−thr)/(1−thr)` —— **标量灰度** |
| 模糊 | 逐通道不同峰值（spread `{1.0, 0.6, 0.32}`） | 三通道同一峰值（spread `{1,1,1}`） |
| 混合 | `src + Σ halo_i·w_i·tint·amount` —— **加法**（线性域） | `1−(1−c)(1−b·s)` —— **Screen**（显示域） |
| 落点 | OETF **之前**（加光交给 `highlightRolloff`） | OETF **之后**、褪色与暗角之间 |
| 实测判据 | `r̄_R > r̄_G > r̄_B`（0.20155 > 0.15484 > 0.13210） | `r̄_R = r̄_G = r̄_B`（极差 **0.00e+00**） |
| 纹理 | 5 级金字塔 + tmp + 全分辨率 out = 158.7MB | **零新增**（复用前两者） |
| pass | 16 | **+1** |
