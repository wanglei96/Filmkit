# E4-4 趾部/肩部形状（filmic `latitude` per-channel）接线 —— 交付报告

> **任务卡**：E4-4｜**唯一权威规格**：`docs/reports/editor-E4-film-params-DESIGN.md` §3.4（574–660 行）
> **日期**：2026-10-11｜**工作区状态**：代码留在工作区，**未 commit**（按要求等架构师独立复核）
> **真机**：⚠️ **未真机验证**（本机无设备、无 AVD，见 §7.1）

---

## 0. 结论摘要

`shoulder[3]`（`[5..7]`）/ `toe[3]`（`[8..10]`）这两个**死槽位**（旧 log 域链 v0.7.9 已删）已按规格
§3.4 复活为 **darktable v3 filmic `latitude` 的 per-channel 拆分**（`lat_sh` R/G/B + `lat_toe` R/G/B）：

| 项 | 结果 |
|---|---|
| 默认零回归 | ✅ **逐位相同**（GPU 侧 RGBA16F 输出 0/176 个分量不同；CPU 侧 60 个十六进制浮点系数 0 个不同） |
| 形状可控 | ✅ `lat_toe` 0.01/0.1/0.3 三档：过渡区 `toe_log` 单调 0.6228→0.5896→0.5157，端点 f(0)/f(1) 逐位不动 |
| per-channel | ✅ 只改 `[8] toe_r` ⇒ R 变、**G/B/A 逐位不变**；暗部 8bit 色偏最大 **1.89 LSB**、黑位 f(0) **逐位不抬** |
| 正交性 | ✅ 固定 `[37][38]`/`[4]`，4 组 `[5..10]`（含极端 0/1）下 f(0)=7.58767e-5、f(1)=1.0 **逐位不变** |
| 工具链 | ✅ `validate-shaders.sh` / `check-diag-fields.py` / `check-style-passthrough.py` 退出码 0 |
| 构建 + 单测 | ✅ `assembleDebug testDebugUnitTest --rerun-tasks` 退出码 0；**46 tasks executed**；**408 tests / 0 failures / 0 errors** |
| 真机 | ⚠️ **未验证**（无设备无 AVD） |
| 编辑器链路 | ❌ **无落点**（`kFsStyle` 链内无 filmic）⇒ 本卡**只对拍照链路生效**（规格 §要点 5 的第二种情形，已写进 `preset-DESIGN.md` §1.6） |

**新增/改动文件**：`FrameRenderer.h`、`FrameRenderer.cpp`、`FilmParams.h`（仅注释）、
`tools/check-toeshoulder.py`（新）、`tools/check-diag-fields.py`、`tools/tuning/filmic_verify.py`、
`docs/reports/preset-DESIGN.md`。**Kotlin 侧零改动**（`neutral()` 的 0.01 未动，符合任务卡纪律）。

---

## 1. 为什么现在做（架构师说明，落到本卡的依据）

`docs/reports/preset-sample-fingerprint-RESEARCH.md` 的样片指纹调研证明：现有 LUT 的
**影调曲线形状**与目标风格不匹配 —— 调 `lut_strength` 无解（逐点反解 `leica_m9` 需要
s = 1.42 / 0 / −2.04，三个互斥的解），结论是"影调与饱和应由引擎参数重建"。

而**拍照链路的影调曲线控制旋钮只有本卡的 `[5..10]`**，已用代码核实：

```
$ grep -n "p\.contrast\|p\.shoulder\|p\.toe" core/engine/src/render/FrameRenderer.cpp
7554:  kv += ",\"ct\":" + jnum(p.contrast, 5);            ← 诊断日志
12508:                 p.exposure_ev, p.shoulder, p.toe);  ← 本卡接线点（passFilm）
12759:  glUniform1f(u_style_.contrast, p.contrast);        ← 编辑器链路 kFsStyle
12760:  glUniform1f(u_style_.contrast_on, p.contrast != 1.0f ? 1.0f : 0.0f);
```

- `[4] contrast` **只被编辑器链路 `passStyle` 读**，拍照链路 `passFilm` 不读（`kFsFilm` 无 `uContrast`）；
- `[37][38]` 只定义曲线**端点**（black/white EV → `xmin`/`xmax`）；
- ⇒ 拍照链路的影调**中段形状**此前没有任何用户可控旋钮。**本卡是"影调重建"的前置条件。**

---

## 2. 改了什么（文件 : 行号）

### 2.1 `core/engine/src/render/FrameRenderer.h`

| 位置 | 改动 |
|---|---|
| `:574-596` | `struct FilmicSpline`：`m1..m5` 由 `float[3]`（段）扩为 **`float[3][3]`（[通道][段]）**，通道 0=R/1=G/2=B、段 0=toe/1=shoulder/2=中间线性段；`lat_min`/`lat_max` 由标量扩为 `float[3]`；**新增 `lat_sh[3]`/`lat_toe[3]`**（实际生效的 latitude，clamp 后，供诊断日志取"生效值"） |
| `:597-602` | `computeFilmicSpline` 签名：`float latitude = 0.01f` → `const float lat_sh[3], const float lat_toe[3]`（调用方显式传，无默认实参） |
| `:606-610` | `updateFilmicEV` 签名：+`const float lat_sh[3], const float lat_toe[3]` |
| `:1178-1202` | `u_film_` uniform 缓存：`filmic_m1..m5`（5 个 vec3）+ `filmic_lat_min/max`（2 个 float）→ **`filmic_toe_m1..m5` + `filmic_sh_m1..m5` + `filmic_lin_m1/m2`（12 个 vec3）+ `filmic_lat_min/max`（2 个 vec3）** |

### 2.2 `core/engine/src/render/FrameRenderer.cpp` — GPU 侧（`kFsFilm`）

| 位置 | 改动 |
|---|---|
| `:4136-4152` | uniform 声明替换为 per-channel 布局（12+2 个 vec3），并把**数学依据 + 与 `[37][38]`/`[4]`/`[28]` 的正交性 + GPU 三禁**写进实现注释（规格 §3.4.3 的硬要求） |
| `:4171-4192` | **手写 R/G/B 三份** `filmicSplineR/G/B`（Horner 三段；vec3 的 `.r/.g/.b` = 三层乳剂）—— 不用数组参数、不做动态索引、不用多行宏 |
| `:4194-4211` | **手写三份** `filmicScalarR/G/B`（log 编码 + 三段求值 + `pow`），三份**逐字复制**接线前的表达式序列（只换 spline 函数）⇒ 默认三通道相同时运算序列完全一致 |
| `:4216-4230` | `filmicTone`：`naive` 逐通道用各自系数；**norm 分支（max-RGB 归一）用 G 通道系数**（理由见 §6.1） |

### 2.3 `core/engine/src/render/FrameRenderer.cpp` — CPU 侧（spline 求解）

| 位置 | 改动 |
|---|---|
| `:12225-12248` | 函数头/注释：`latitude_in` → `lat_sh[3]`/`lat_toe[3]`；删除 `kLatitude` |
| `:12263-12328` | **新增 per-channel 循环**：逐通道 `clamp(v,0,1)`（非有限值回退 0.01）→ 各自算 `toe_log`/`sh_log`/`toe_d`/`sh_d` → 各解 **2 个 5×5 系统**（共 3×2=6 个）→ 各自写 `m1..m5[ch][0..2]` 与 `lat_min/lat_max/lat_sh/lat_toe[ch]`。**求解代码（矩阵、右端项、`solve5`）逐字未改**，只加了通道维下标 |
| `:12480` | `filmic_ = computeFilmicSpline(ev_black_, ev_white_, cp, lat_sh, lat_toe)` |

### 2.4 `core/engine/src/render/FrameRenderer.cpp` — 下发与诊断

| 位置 | 改动 |
|---|---|
| `:12506-12508` | `passFilm` → `updateFilmicEV(..., p.exposure_ev, p.shoulder, p.toe)`（**接线点**） |
| `:6596-6611` | 14 个新 uniform 的 `glGetUniformLocation` |
| `:12587-12602` | 下发：12 个系数 vec3 + 2 个 lat vec3（`glUniform3f`，逐通道手写，不用数组上传） |
| `:10087-10119` | 诊断日志（`raw.render` 出口）：`fM1..fM5` 由 3 个数改 **9 个数**（通道优先 `[R.toe,R.sh,R.lin, G…, B…]`）；`fLat` 由 2 个数改 **6 个数**（`[toeR,toeG,toeB, shR,shG,shB]`）；**新增 `latToe` / `latSh`**（各 3 个数 = 实际生效值，clamp 后） |

### 2.5 `core/engine/src/film/FilmParams.h`

`:34-57` 注释更新：`[5..10]` 标注 **★ v0.11.5（E4-4）已接线**（消费点链 + **只对拍照链路生效**），
并把 E4-1 留下的"旧值 0 必须处理"警告改为**已核实不存在迁移风险**的三条证据链（§6.4）。
**字段与默认值一行未改**（`shoulder[3]`/`toe[3]` 仍是 0.01）。

### 2.6 工具与文档

| 文件 | 改动 |
|---|---|
| `tools/check-toeshoulder.py` | **新增**（离线验收：真跑新旧 `kFsFilm` + CPU 侧系数 hex 逐位对拍，覆盖判据 1–4） |
| `tools/check-diag-fields.py` | 白名单 +2 项（`latToe`/`latSh`，owner=`finishStillChain`，前置调用 `passFilm(`） |
| `tools/tuning/filmic_verify.py` | 适配 per-channel 日志格式（`fM*` 9 个数、`fLat` 6 个数、`latToe`/`latSh` 驱动参考复算；旧日志无 `latToe` 时回退 0.01） |
| `docs/reports/preset-DESIGN.md` | §1.6 能力矩阵 `[5..10]` 行 + 表下说明 + §5 任务表状态 |

### 2.7 纪律核对（未动的部分）

- ❌ 未动 `kFsOut` 的 **E4-3 颗粒段**与 **E4-2 暗角段**（`git diff` 里无 `uGrain*`/`uVig*`/`kFsOut`/`kFsStyle` 的任何增删行）；
- ❌ 未动 Kotlin 侧任何数值（`CameraPresets.neutral()` 的 `put3(this, 5, 0.01…)` 未动）；
- ❌ 未动槽位契约（`kCount = 96`、槽位语义、`fromDoubles` 的解析逻辑与默认值）；
- ❌ 未 commit。

---

## 3. 数学依据与正交性（规格 §3.4.2 / §3.4.3 的落实）

**原版就是两个独立表达式**（darktable v3 `dt_iop_filmic_rgb_compute_spline`）：

```
toe_log = (1 − lat)·grey_log + lat·xmin
sh_log  = (1 − lat)·grey_log + lat·xmax
```

v3 里这两行**本来就各自成式**，只是共用同一个 `lat` ⇒ 拆成 `lat_toe`/`lat_sh` 并各给 R/G/B 一份，
是**原版参数维度的自然展开**（三层乳剂的 toe/shoulder 位置不同 = 阴影/高光色偏的物理机制），
**不是自研机制、不新增曲线、不新增 pass、不新增 shader 求值次数**。该论证已写进实现注释
（`FrameRenderer.cpp:4136-4152` 与 `:12263-12277`）。

**正交性**（写进同一段注释，且判据 4 有实测）：

| 旋钮 | 控制的数学量 | 是否改变端点 | 与 `[5..10]` 的关系 |
|---|---|---|---|
| `[37][38]` black/white EV | `black`/`white` → `dr` → `xmin`/`xmax` | ✅ **定义端点** | **[5..10] 不改端点**：五条约束（`f(0)=black_disp` / `f'(0)=0` / `f(Tl)=toe_d` / `f'(Tl)=contrast` / `f''(Tl)=0`）一条不动，只改 `Tl` 的**位置** |
| `[4]` contrast | `slope → contrast` → 5×5 系统的 `b[3]` | ❌ | **有交互**（都影响中部）：`contrast` 改**斜率**、`latitude` 改**过渡区位置** —— 实测见判据 4 |
| `[28]` tone_strength | `mix(lin, toned, k)` | ❌ | **正交**（本卡改 `toned` 的形状，`[28]` 改混合比） |

---

## 4. 七条验收判据逐条证据

离线验收工具：`python3 tools/check-toeshoulder.py`（**真跑引擎自己的 shader**：moderngl 执行
`FrameRenderer.cpp` 里的 `kFsFilm`，旧版从 `git show HEAD` 提取，只做 `#version 300 es → 330 core`
的机械改写，与 `tools/engine-shader-offline.py` 共用同一套 GL 封装；系数用
`tools/tuning/filmic_offline.py` 的 `compute_spline` 复算，新旧两版**同一份**系数 ⇒ 对比的唯一
变量就是 shader 重构本身）。

### 判据 1：默认零回归（`[5..10]` 全 0.01 时输出与改前逐位相同）

**方法**（两半）：

- **GPU 侧**：新 `kFsFilm`（三通道 lat 都 0.01）vs `git HEAD` 的旧 `kFsFilm`，同一输入、同一组
  filmic 标量、输出 **RGBA16F 逐位比较**；
- **CPU 侧**：把工作区与 `git HEAD` 的 **`solve5` + `computeFilmicSpline` 源码**括号配平提取出来
  （机械改名后放进同一个 TU，结构体从各自的 `.h` 提取），**编译真跑**，打印全部系数的
  十六进制浮点（`%a`）逐位比较。

```
OK   ① 默认零回归（新 vs 改前 shader，RGBA16F 逐位）: 11×4×4×4 个分量，不同 = 0；max|Δ| = 0.000e+00
OK   ① CPU 侧系数逐位（新 vs 改前 computeFilmicSpline）: 比较 60 个 hex 浮点
     （15 个多项式系数 × 3 通道 + lat_min/max + 9 个标量）；不同 = 0
```

⇒ CPU 侧"per-channel 三次求解 = 旧版单套求解"与 GPU 侧"逐通道求值 = 旧版单套求值"**都逐位成立**。

### 判据 2：形状可控（`lat_toe` = 0.01 / 0.1 / 0.3 的曲线采样 + 端点）

输入亮度 = `0.1845·2^(blackEv + e·dr)`（blackEv=−7.65、dr=12.21，e 为 log 编码坐标，11 个采样点 ≥ 6 个）：

```
    e      v_in        lat=0.01   lat=0.1    lat=0.3
  0.00   0.000459     0.000114  0.000114  0.000114   ← 端点 f(0) 三档逐位相同
  0.10   0.002141     0.000260  0.000244  0.000197
  0.20   0.004992     0.000957  0.000853  0.000587
  0.30   0.011636     0.004257  0.003864  0.002926
  0.40   0.027124     0.016937  0.016068  0.014290
  0.50   0.063227     0.055328  0.054443  0.053619
  0.60   0.147388     0.147095  0.147095  0.147095
  0.70   0.343574     0.322021  0.322021  0.322021
  0.80   0.800899     0.583984  0.583984  0.583984
  0.90   1.866962     0.863770  0.863770  0.863770
  1.00   8.704085     1.000000  1.000000  1.000000   ← 端点 f(1) 三档逐位相同
OK   ② 过渡区随 lat_toe 单调移动: toe_log(R) = [0.622842, 0.589599, 0.515725]
     （lat 增大 ⇒ 向暗端移动）；暗部输出逐点不增 = True；
     8bit 可见差异(>1LSB)最早采样序号 = [3, 2]（e = [0.3, 0.2]）
OK   ② 端点不动 f(0)/f(1): f(0) = 0.00007588（三档逐位相同 = True）；f(1) = 1.00000000
```

**读法**：`lat_toe` 增大 ⇒ `toe_log` 单调向暗端移动（0.6228 → 0.5157，`xmin ≈ 0.2508`）、
暗部输出**逐点不增**（过渡区变窄 ⇒ 同斜率下更凹）、8bit 观感上的可见差异起点**单调向暗端移动**
（0.3 档从 e=0.2 起、0.1 档从 e=0.3 起可见）。e ≥ 0.6 三档重合是**正确**的：那里已在线性段，
而 `f''(Tl)=0` 保证 toe 段与线性段在 `toe_log` 处**二阶相切**（float16 下不可分辨）。

### 判据 3：per-channel 生效（只改 `toe_r`）

```
OK   ③ per-channel：只改 [8] toe_r: R 变 = True；G 逐位不变 = True；B 逐位不变 = True；A 不变 = True
OK   ③ 暗部色偏可测 + 黑位不抬: 暗部(e∈[0.1,0.5]) R/G = [0.87947, 0.80668, 0.84364, 0.92207, 0.98456]；
     8bit sRGB 的 G−R = [0.1, 0.61, 1.89, 1.63, 0.52]（最大 1.89 LSB > 1）；黑位 f(0) 逐位相同 = True
```

- **通道隔离**：`lat_toe = [0.3, 0.01, 0.01]` ⇒ R 通道变，**G/B/A 逐位不变**（`np.array_equal`）；
- **色偏**：暗部线性域 R/G 最低到 **0.807**（19% 通道差），折算 8bit sRGB 观感 **最大 1.89 LSB**；
- **黑位不抬**：`f(0) = black_disp` 是五条约束之一 ⇒ 与基线**逐位相同**（规格 §3.4 的验收口径）。

> ⚠️ 色偏幅度只有 naive 通道的一半，是**颜色模式的固有折中**：`filmicTone` 的
> `0.5·naive + 0.5·normed`，其中 `normed` 用 max-RGB 归一后的**标量**曲线（见 §6.1）——
> 这是 darktable `preserve_color` 折中模式的既有行为，不是本卡的实现偏差。

### 判据 4：正交性实测（固定 `[37][38]`/`[4]`，改 `[5..10]`）

固定 `[37] = −7.65`、`[38] = 4.56`、`[4] = 1.0`（`cp = 1.0`），端点输入 = 线性 0（e→0）与 1e4（e→1）：

```
  判据 ④ 端点（固定 [37][38]=-7.65/4.56、[4]=1.0，只改 [5..10]）
    默认 0.01    f(0) = 0.0000758767   f(1) = 1.0000000000
    toe=0.3      f(0) = 0.0000758767   f(1) = 1.0000000000
    sh=0.3       f(0) = 0.0000758767   f(1) = 1.0000000000
    极端 0/1     f(0) = 0.0000758767   f(1) = 1.0000000000
OK   ④ 端点不随 [5..10] 变化（含极端 0/1）: 4 组参数下 f(0)/f(1) 与默认逐位相同 = True
```

**数字结论**：`[5..10]` 取 0.01 / 0.3 / 混合 / 极端 0 与 1，端点值 f(0)=7.58767e-5、f(1)=1.0
**一个 bit 都没动**（4 组参数的端点输出与默认逐位相同）。`[4]` 与 `[5..10]` 的**交互**是设计内
（都作用在中段：前者改斜率、后者改过渡区位置），不是"互相干扰"——两者不会把曲线推出
`[black_disp, 1]` 值域，端点与端点斜率始终由五条约束钉住。

### 判据 5：工具链退出码 0

```
$ bash tools/validate-shaders.sh            → 全部 shader 校验通过           EXIT=0
$ python3 tools/check-diag-fields.py        → 全部通过（44 项 OK）           EXIT=0
      OK   latToe 行 10116 函数 finishStillChain（定义 9511），前置调用齐备且更早
      OK   latSh  行 10117 函数 finishStillChain（定义 9511），前置调用齐备且更早
$ python3 tools/check-style-passthrough.py  → 全部通过                       EXIT=0
$ python3 tools/check-toeshoulder.py        → 全部通过（判据 1–4）           EXIT=0
```

### 判据 6：构建 + 单测真跑

```
# 先删 native 产物（绝对路径已核对，均在 app/build/filmengine/ 下）：
#   .../{arm64-v8a,x86_64}/CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
#   .../jniLibs/{arm64-v8a,x86_64}/libfilmengine.so + .../{arm64-v8a,x86_64}/libfilmengine.so
#   → 删除后 find 计数 = 6（只剩 intermediates 下由 gradle 重新生成的 6 个）
$ rm -rf app/build/test-results
$ JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest --rerun-tasks
GRADLE_EXIT=0
BUILD SUCCESSFUL in 25s
46 actionable tasks: 46 executed
```

真编确证（日志 + 产物时间戳，arm64-v8a 与 x86_64 各一遍）：

```
[ 15%] Building CXX object CMakeFiles/filmengine.dir/src/render/FrameRenderer.cpp.o
[ 23%] Linking CXX shared library libfilmengine.so
FrameRenderer.cpp.o            2026-10-10T11:38:00（本次）
jniLibs/arm64-v8a/libfilmengine.so  2026-10-10T11:38:00（本次）
jniLibs/x86_64/libfilmengine.so     2026-10-10T11:38:04（本次）
```

单测（本次新生成的 38 个 XML，`timestamp="2026-10-10T03:38:20Z"`）：

```
tests=408  skipped=0  failures=0  errors=0
```

### 判据 7：真机

⚠️ **未真机验证**。本机无 Android 设备、无 AVD（已知事实），本卡的全部结论来自：
① 引擎自身 shader 的离线真跑（moderngl）；② 引擎自身 C++ 源码的离线真编真跑（clang++）；
③ 全量单测 + 强制真编。**真机待验证项见 §7.1**。

---

## 5. 边界分析（`lat` = 0/1、奇异、越界）

1. **`lat = 0`**（规格 §3.4.4 的"极端但合法"）：`toe_log = grey_log` ⇒ toe 段从中灰开始、
   中间线性段宽度归零；`sh_log = grey_log` 同理。曲线仍连续（两段在 `grey_log` 处同为
   `grey_disp`，`f''` 相切），不会 NaN。**UI 需要提示"0 = 极端 S 曲线"**（规格原文要求）。
2. **`lat = 1`**：`toe_log = xmin` / `sh_log = xmax` ⇒ 过渡区贴住端点，中段近乎线性。
3. **`solve5` 奇异在数学上不可达**（本卡新增的边界分析，per-channel 后值得确认）：
   `xmin = (black_disp + safety·(1−black_disp) − li)/contrast`，代入 `li = grey_disp − contrast·grey_log`
   并用 `contrast` 的 clamp 下界（`min_c ≥ (grey_disp − black_disp)/grey_log + safety`）得
   `xmin ≥ safety·(1−black_disp)/contrast > 0`；同法用 `min_c` 的第三项得 `xmax < 1`。
   ⇒ `toe_log ∈ (0, grey_log]`、`sh_log ∈ [grey_log, 1)`，**永不等于 0 或 1**（奇异点），
   三通道各自独立求解也不会撞上奇异。
4. **越界/非有限输入**：`computeFilmicSpline` 内 `clamp(v, 0, 1)`，非有限值（NaN）回退 **0.01**
   （输入来自 JNI/预设，属信任边界）；生效值回填 `sp.lat_sh`/`sp.lat_toe`，日志的
   `latToe`/`latSh` 打印的就是它（**不是** `FilmParams` 的裸值）。

---

## 6. 超出规格文档的判断（逐条，请架构师审核）

### 6.1 ★ norm 分支（max-RGB 归一半段）用 **G 通道**系数

`filmicTone` 的 `normed` 分量输入是**标量** `max(r,g,b)`，per-channel 后必须挑一套系数。
规格未规定，我取 **G**（主感光层、luma 权重 0.7152 最大，且是 max-RGB 的最常见通道）。
**默认三通道相同时与接线前逐位一致**（判据 1 已证）。副作用：per-channel 色偏幅度约为
naive 通道的一半（判据 3 的 1.89 LSB 即此结果）。**若架构师希望色偏更强**，可改为
"用 max 通道自己的系数"（需要一次三路比较选系数，成本 +3 ALU/px）——**本卡按最小改动未做**。

### 6.2 uniform 布局 = **按段分组**（12 个 vec3）而不是 mat3

规格建议"`uFilmicM1..M5` 扩为 `mat3`"，同时警告不能用 `m[i]` 动态索引。我选**更保守**的形态：
`uFilmicToeM1..M5` / `uFilmicShM1..M5` / `uFilmicLinM1,M2` 各一个 vec3（`.r/.g/.b` = R/G/B），
共 **12 个 vec3 + 2 个 lat vec3**（vs 旧 5 vec3 + 2 float）—— 零索引、零数组、零矩阵，
`glUniform3f` 直接下发，完全绕开 GPU 三禁。成本 +9 个 vec3 uniform ≈ 108 B。

### 6.3 诊断字段格式**破坏性变更**（已同步受影响工具）

- `fM1..fM5`：3 个数 → **9 个数**（通道优先）；`fLat`：2 个数 → **6 个数**；
- **新增** `latToe`/`latSh`（各 3 个数 = 生效值）；
- 同步：`tools/tuning/filmic_verify.py`（已适配，并用合成日志自测通过）、
  `tools/check-diag-fields.py`（白名单 +2）；
- `tools/log-ab-diff.py` 只做字段相等性比较（`INVARIANT_KEYS` 按名索引），形状变化**不影响**它。

### 6.4 旧值 `0` **不做迁移**（E4-1 的警告已核实不成立）

`FilmParams.h` 原注释要求"E4-4 接线时必须处理旧数组（旧快照/预设里 `[5..10]` 存的是旧语义的 0）"。
核实结论：**该风险不存在**，本卡不写迁移函数（写进注释的注释链）：

1. **拍照链路**：`CameraController.filmParams` 从 `CameraPresets.standard()` 起步，全仓只有
   `neutral()` 写 `[5..10]`（**无 prefs 覆盖层、无滑杆写入点**）⇒ 运行时恒 0.01；
2. **预设**：v1（索引键）被 `presetSchemaUsable` **一律拒绝**（`EditorExport.kt:1835-1848`）；
   v2 用语义名 `shoulder[0..2]`/`toe[0..2]`，且 `presetSparseSlots` 的恒等值**逐槽取
   `CameraPresets.standard()`（= 0.01）** ⇒ 只有用户显式调过才会存非 0.01；
3. **内置预设**：`app/src/main/assets/presets/builtin.json` 三条**均不含** `[5..10]`
   （`grep -o "shoulder\[[0-9]\]\|toe\[[0-9]\]"` 命中 0 次）。

### 6.5 新增 `tools/check-toeshoulder.py`（本卡证据的主要来源）

任务卡允许"说明你用的方法"。既有 `tools/engine-shader-offline.py` **并未实现 FILM pass**
（docstring 列了第 4 步，代码只到 CCM/defringe），`tools/check-style-passthrough.py` 只覆盖
`kFsStyle`（编辑器链路，无 filmic）⇒ 本卡新写一个**只针对 `kFsFilm` 的离线执行器**，并复用
前者的 GL 封装与后者的"真跑引擎 shader"口径。它同时包含 CPU 侧 hex 逐位对拍（判据 1 的另一半）。

---

## 7. 未做与存疑

### 7.1 ⚠️ 未真机验证（本机无设备无 AVD）

真机需要核对的项（建议架构师在有设备时按此清单验，全部靠日志字段）：

| 项 | 判据 |
|---|---|
| `[5..10]` 真的进了引擎 | `raw.render` 日志的 `latToe`/`latSh` 与 UI 值一致（clamp 后） |
| 系数真被求解 | `filmic_verify.py <日志>` 对 `fM1..fM5`（9 个数 × 5）、`fLat`（6 个数）全项一致 |
| 预览 WYSIWYG | `preview.filmic` 与 `raw.render` 的 `latToe`/`latSh` 相同（同一 `passFilm` 路径） |
| 默认零回归 | `[5..10]` 全 0.01 时成片与上一版逐位一致（离线已证；真机复拍复核） |
| 无黑屏/无着色器编译失败 | 真机首帧正常（`kFsFilm` 的 14 个新 uniform 在 Mali 上链接成功） |
| 观感方向 | `lat_toe` 增大 ⇒ 暗部更沉、阴影偏青（R 层）；`lat_sh` 增大 ⇒ 高光更早压缩 |

### 7.2 编辑器链路**无落点**（规格 §要点 5 的第二种情形，明确记录）

勘察结论：**编辑器链路不走 `filmicSpline`** —— `passStyle` 用的是 `kFsStyle`
（输入 = 已成片的 sRGB 显示域，链内只有 LUT/颗粒/dither + E2-2 的 ev/WB/contrast/sat/clarity
+ E2-11 的影调分区/褪色，**没有 filmic、没有 log 编码、没有 `uFilmic*` uniform**）。

⇒ **本卡只对拍照链路生效**；编辑器侧的影调由 `[4] contrast` + 影调分区 `[90..93]` 承担。
**不新增曲线**是规格的硬约束（"不要做两套互相打架的影调旋钮"），所以在编辑器里补一套
filmic 是**超规格**的，本卡不做。该结论已写进：
- `preset-DESIGN.md` §1.6 的 `[5..10]` 行（`✅ passFilm` / `❌ 无落点` / `⚠ 仅拍照链路有效`）；
- 表下新增说明（P-5 的灰显文案应为"**仅拍照**"，不是"待接线"）；
- `FilmParams.h:34-57` 的字段注释。

### 7.3 其它存疑/未做

1. **GPU 性能未实测**：+9 个 vec3 uniform（108 B）、`filmicScalar` 调用次数不变（仍 4 次：
   3 naive + 1 normed）；CPU 侧 +4 个 5×5 系统（微秒级）。规格 §3.4.5 判定"可忽略"，但
   **真机 GPU 时间未测**（离线执行器不测时延）。
2. **UI 范围与提示未做**：`[5..10]` 的 UI 滑杆范围应为 `[0,1]`、`0` 需提示"极端 S 曲线"
   （规格 §3.4.4）；`PRESET_SLOT_CLAMP`（`EditorExport.kt:1977`）目前**没有** `[5..10]` 的
   clamp ⇒ 预设可带越界值，引擎侧 clamp 兜住（不报错）。这两项属于 UI/P-5 卡，本卡不动 Kotlin。
3. **`fLat` 依赖场景 EV**：`lat_min/lat_max` 是**求解结果**（随 black/white EV 变），不能反推
   用户设的参数值 —— 核对参数请用 `latToe`/`latSh`（已写进日志注释）。
4. **`[5..10]` 在 `passStyle` 侧无任何读取**：本卡未给编辑器链路加"忽略该参数"的显式日志；
   编辑器套用带 `[5..10]` 的预设时**静默无效果**（P-5 的灰显是唯一的用户可见提示）。
5. **Kotlin 侧有一条注释已过时（本卡按纪律未改，建议后续卡顺手更正）**：
   `CameraPresets.kt:38-43` 仍写着"`computeFilmicSpline` 声明 `latitude = 0.01f`，
   `FrameRenderer.cpp:11555` 调用只传 3 个实参 ⇒ 恒取该默认"与"shader 当前零引用（E4-4 才接线）"。
   **数值结论（必须显式写 0.01）仍然正确且未变**，但两处描述已失效：签名不再有默认实参
   （`FrameRenderer.h:597-602` 改为显式传 `lat_sh[3]`/`lat_toe[3]`），调用点现为
   `FrameRenderer.cpp:12480`；shader 也已接线。**本卡未动 Kotlin 任何一行**（含注释），
   因为任务卡的纪律是"只改引擎 + 文档"。
6. **未 commit**：按任务卡要求，代码留在工作区等架构师独立复核。

---

## 附：一句话总结

`[5..10]` 从死槽位变成 **darktable v3 filmic `latitude` 的 per-channel 拆分**（原版两个独立
表达式的自然展开），默认 0.01 时 **CPU 系数与 GPU 输出都逐位等于接线前**，三通道可独立移动
阴影/高光过渡区（暗部色偏 1.89 LSB @ toe_r=0.3）而**端点分毫不动**；**只对拍照链路生效**，
编辑器侧由 `[4]`+`[90..93]` 承担，已在能力矩阵中标注。
