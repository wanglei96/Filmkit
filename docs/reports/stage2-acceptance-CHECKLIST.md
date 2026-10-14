# 阶段 2 真机验收清单（引擎插槽：第 2 个影调引擎 = darktable sigmoid）

> 包：**已构建并已真机验收** —— `test_temp/filmkit-0.11.27-ENGINE0-filmic-BASE.apk`（基准包）与
> `test_temp/filmkit-0.11.27-ENGINE1-sigmoid-VERIFY.apk`（验证包）；`versionCode` 376 / `versionName` 0.11.27。
> 变更：加引擎 1（sigmoid）的 GLSL 注入段 + 注册表第二项；**引擎 0 路径零改动**（`filmSource(0)` 仍拼回 `kFsFilm` 原文）
> 本阶段验收 = **阶段 1 的零回归判据必须继续成立**（判据 E）+ **引擎 1 的源码指纹与观感**（判据 F / ④）
>
> ✅ **真机验收已执行（2026-10-14，vivo V2509A）**：判据 **E / F / G / ④ 全部 PASS**，逐条实测数据见
> [stage2-sigmoid-REPORT.md](stage2-sigmoid-REPORT.md) **§9** —— 含"红杯 +1.5 EV 反光白带"的技术判读（§9.4，
> 结论：**线性域过曝 1.5 档的物理正确表现，两包共有，不构成不通过项**）。
> **本清单保留为可复跑的验收步骤**（不是未完成项）。
>
> 设计依据：[engine-slots-design.md](../engine-slots-design.md) §7（五条判据）· 本机证据见
> [stage2-sigmoid-REPORT.md](stage2-sigmoid-REPORT.md)

---

## 判据 E（硬，最高优先级）：引擎 0 零回归 —— 看一条日志就够

App 内 **日志 Tab** 搜 `film_src`（或导出 `/sdcard/Android/data/com.filmkit.app/files/logs/` 下的 JSONL 后 grep）：

```
engine.film_src  {"slot":0,"engine":"filmic","bytes":35154,"chars":25140,"crc32":"0x4fa1ebd3","identical":true,"zero_regression":"ok"}
```

| 字段 | 期望值 | 含义 |
|---|---|---|
| `slot` | **0** | 走内置 filmic（旧预设/默认值都是 0） |
| `bytes` / `chars` | **35154** / **25140** | 拼接后 shader 的字节数 / 码位数（两个口径并列，防混淆） |
| `crc32` | **`0x4fa1ebd3`** | 与阶段 1 / 阶段 0 基线**逐字节相同**的指纹 |
| `identical` | **`true`** | 拼接结果 == 改造前的 `kFsFilm` 原文 |
| `zero_regression` | **`"ok"`** | 阶段 1 的硬判据继续成立 |

- ⚠ **每次引擎 init 记一条**（不是整个 App 生命周期一条）：真机实测一次会话约 6 条（`app.start` 1 条 + 切前后台/重建 context 各 1 条）。
  **判据是"每一条都必须 identical:true"**，条数本身不是判据。
- ⚠ 若看到 `identical:false` 且 `zero_regression:"FAILED (stage-1 criterion)"`（level = **Error**）：
  **立即停止验收并回报** —— 那是零回归失效（阶段 2 的改动碰坏了引擎 0 路径），不是可接受偏差。
- 说明：`"engine":"filmic"` 字段是**阶段 2 新增**的（阶段 1 的日志没有它）。旧包日志缺这个字段属正常。

## 判据 F（新）：引擎 1 的源码指纹 —— 注意 `identical:false` 是**正常状态**

切到引擎 1 后（切换办法见下节），日志应出现：

```
engine.film_src  {"slot":1,"engine":"sigmoid","bytes":23211,"chars":15781,"crc32":"0xbf298386","identical":false,"zero_regression":"n/a (engine 1)"}
```

| 字段 | 期望值 | 含义 |
|---|---|---|
| `slot` / `engine` | **1** / **`"sigmoid"`** | 走注册表第二项 |
| `bytes` / `chars` | **23211** / **15781** | 引擎 1 拼接结果的字节 / 码位口径 |
| `crc32` | **`0xbf298386`** | 引擎 1 拼接结果的指纹（与离线链**逐字节一致** ⇒ 真机与判据链同源） |
| `identical` | **`false`** | **这是正常状态，不是错误** |
| `zero_regression` | **`"n/a (engine 1)"`** | 同上：零回归判据**只对 slot 0 生效** |
| level | **Info（不是 Error）** | 若这条是 Error ⇒ 才是真问题，需回报 |

> ### ⚠⚠ 验收人必读：`identical:false` + `zero_regression:"n/a (engine 1)"` **不是故障**
>
> 引擎 1 的 shader **本来就应该**与 `kFsFilm` 不同 —— 它是另一个影调引擎的源码。
> 阶段 1 的"逐字节等价"判据（判据 E）**只对 `engine_slot=0` 有意义**：那是在证明"重构没改动旧行为"。
> 对引擎 1 而言，`identical:false` 恰恰是**功能生效的证据**（源码确实换了）。
> 代码里这条日志的 level 也刻意是 **kInfo**（只有 slot=0 且不相等时才升到 kError）。
> **看到它就报故障 = 误判。**
>
> ⚠ 若 `bytes`/`crc32` 与上表不符：先确认是否卡 B 又调过 GLSL（那三个数是**内容指纹**，改一个字符就会变）。
> 用离线链复跑一次即可得到当前真值（`--engine 1`，见下节命令），**不要**直接当成缺陷。

## 判据 G：两条"不该出现"的日志（出现即异常）

| 日志 | 出现意味着 |
|---|---|
| `engine.film_split_missing` | `kFsFilm` 的切分锚点没找到（模板结构被改坏）⇒ 已**自动退回**整体字面量（不崩），但模板化失效，需回报 |
| `engine.slot_unavailable` | `[98]` 被写成了注册表里不存在的 slot（如 2）。阶段 2 只有 0/1 两个引擎 ⇒ 正常验收中不该出现 |

## 判据 B：不崩、不黑屏（两条"绝不接受"）

1. **启动不崩**：装包后能正常进入相机页。
2. **不黑屏**：预览/拍照画面正常（= 设备端 GLSL 编译链接成功）。
   - v0.5.13 的历史教训：shader 设备端编译失败 = 全链路黑屏，而构建与单测全绿都看不出来。
   - 引擎 1 是**新字面量**（`kFsSigmoidDef` / `kFsSigmoidMain`），它的编译失败只会黑屏、不会崩溃
     ⇒ 切到引擎 1 后**第一件事就是看画面**。若黑屏，日志里会有 `prog_film_` 相关失败，附日志回报。

## 判据 C：常规功能走查（确认没有连带损伤）

相机页（预览流畅、EV/白平衡滑杆、预设切换、M 档参数）· 拍照（出图、连拍、DNG 底片）·
相册（切图/缩放/翻页）· 编辑器（改参数、导出）· 设置页（四个抽屉）。
> 本次改动只碰 film pass 的**源码组装**，理论上不影响其它链路 —— 走查是为了证明这一点。

---

## 怎么切到引擎 1（⚠ 阶段 2 **没有 UI 入口**，先读这段）

> ✅ **两个包已构建好，直接装即可**（架构师已代为构建，构建后源码已恢复 `[98]=0.0`）：
>
> | 包 | 用途 |
> |---|---|
> | `test_temp/filmkit-0.11.27-ENGINE0-filmic-BASE.apk` | **基准包**（引擎 0 filmic）= 现行行为；验判据 E、A/B 的对照臂 |
> | `test_temp/filmkit-0.11.27-ENGINE1-sigmoid-VERIFY.apk` | **验证包**（引擎 1 sigmoid）；验判据 F、④ 的实验臂 |
>
> ⚠ 验证包的 `[98]` 默认值被改成 1 ⇒ `CameraPresetsTest` 会红 ⇒ **验证包不得合并**，验完即弃。

`[98] engine_slot` 在阶段 2 **不是可调参数**（`CameraPresetFullEdit.kt:203` 明确排除），
详情页只是**只读显示**"引擎 N"；正式入口（引擎选择面）是**阶段 3** 的范围。
所有预设（内置 + 用户）的 `[98]` 都是 `0`（`CameraPresets.neutral()` 里写死）。

因此真机验判据 F / ④ 只有一条**可控**路径：

**构建一个"引擎 1 验证包"**：把 `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` 里
`neutral()` 的 `this[98] = 0.0` 改成 `1.0`，重新构建安装。

- 这是**只改默认值、不改任何渲染逻辑**的最小改动；判据 E（slot 0）不受影响（要验判据 E 请用**正常包**）。
- ⚠ 改后 `CameraPresetsTest`（断言 `[98]` 必须为 0）会红 ⇒ **验证包不得合并**，验完即弃。
- 另一条路是 adb 改 `SharedPreferences("filmkit")` 里的快照 JSON（`[98]` → 1），但需 debug 包 + `run-as`，
  且会被下一次拍照覆盖 ⇒ **不推荐**。

## 判据④：真机 A/B 场景清单（引擎 1 vs 引擎 0 的观感）

同场景、同参数、同曝光，**用"基准包"（引擎 0）与"验证包"（引擎 1）各拍一组**。
**只要 3 个场景、共 4 张**（每包），不需要特定天气。

| # | 场景 | 曝光 | **看什么** |
|---|---|---|---|
| **1** | **釉面红杯**（就是那个红杯子，桌上顺光/侧光都行） | **0 / +1.5 EV** 各一张 | ① 釉面高光里**还能不能分辨层次**（引擎 0 死白一片，引擎 1 应能看出渐变）—— **判据① 的核心真机证据**；② 红色高光区**有没有偏橙 / 偏品红**、边缘有无彩边（hue preservation 的观感）；③ 顺带看**中间调亮度两包是否一样**（判据②） |
| **2** | **暗光室内**（不开灯的角落，或画面里的暗部） | 0 EV 一张 | 暗部是否**发灰 / 浮起** —— ⚠ 这是**预期现象**（sigmoid 黑点 0.000152 vs 引擎 0 的 0，本机实测极暗区两引擎差中位 8.6 LSB），**不是缺陷**，只需判断"能不能接受" |

> **场景已按"实际拍得到"精简**（用户反馈：从未拍过白杯子）：
> - 去掉方案 §7 原文里的「白瓷 / 白墙逆光 / 阴天天空」—— 白瓷从未被实拍过，阴天依赖天气；
> - 原「高饱和红光」并入**釉面红杯**（红杯本身就是高饱和红 + 釉面高光，一景同时覆盖
>   "高光层次"与"色相保持"两条）；
> - 最终 **2 个场景、每包 3 张**。

**判定口径**（三条，按重要性排序）：

1. **高光层次（判据① 的真机证据 · 本清单的核心）**：引擎 1 在引擎 0 的死白区**应能分辨更多层次**。
   ⚠ 本机**无法**验证这一条（整张测试 DNG 都没有高光：三个 ROI 的引擎 0 最大都 < 175 LSB，
   ≥200 LSB 像素 = 0）⇒ **这一条只能靠真机**。
2. **中灰不漂移（判据②）**：同场景同曝光下，画面**中间调**亮度肉眼不应有可见差
   （本机判据 ≤1.3 LSB；成片域实测偏差 ≤0.016 LSB）——看场景 1 的 0EV 那张即可。
3. **无色相异常**：高饱和区、高光边缘无彩边 / 色相偏移——看场景 2。

## 回报模板（若发现问题）

```
包：filmkit-x.y.z.apk（versionCode / versionName；并注明是"正常包"还是"引擎 1 验证包"）
现象：（一句话）
复现：场景 + 操作步骤 + 是否每次必现
日志：engine.film_src 那一行的原文 / 其它相关行
照片：若有成片异常，附一张原图
```

---

## 附：本包已通过的本机判据（详见 [stage2-sigmoid-REPORT.md](stage2-sigmoid-REPORT.md)）

| 判据 | 结果 |
|---|---|
| **判据⑤** `--engine 0` 零回归 | `film_d5.npy` crc32 **`0x589f8364`** / sha256 `ce1c9a90…5391`（= 阶段 1 基线）✅ |
| 引擎 0 拼接 == `kFsFilm` 原文 | 25140 字符 / **35154 字节** / `0x4fa1ebd3` ✅ |
| 引擎 1 可跑性 | 无 NaN / 无 Inf；线性值域 `[0.0430, 0.4136]` ✅ |
| 引擎 1 拼接结果 | **23211 字节** / 15781 字符 / `0xbf298386` ✅（架构师独立拼装同值） |
| GLSL == CPU 模型 | 701 点 ramp，**0 点超过 1 个 float16 ULP**；8bit 最大差 0.077 LSB ✅ |
| 判据① 可分辨跨度（纯曲线） | sigmoid c=1.2 **2.00 EV** > filmic **1.70 EV**（同 204.0 LSB 白纸口径）✅ |
| 判据② 中灰 0EV | **0.000 LSB**（判据 ≤1.3）✅ |
| 判据③ 严格单调（纯曲线 float32） | c=1.0/1.2/1.5/1.8 成立；c≥2.0 出现非正差（double 全段单调）⚠ 边界已记录 |
| `validate-shaders.sh` | 67/67 全绿（含引擎 1 拼回整份 + 负向自检）✅ |
| **整机编译** | `./gradlew :app:assembleDebug` **BUILD SUCCESSFUL**；`.so`（arm64-v8a + x86_64 双 ABI）含 `kFsSigmoidDef` / `kFsSigmoidMain` / `computeSigmoidParams` ✅ |
| **真机项（待你）** | **判据 E/F/G/B/C/④ 本机无法执行** —— ⚠ 口径更正：adb **存在**（`~/Library/Android/sdk/platform-tools/adb`，只是不在 PATH），但 `adb devices` **为空 = 没接设备** |

**复跑命令**（本机 0.6 s）：

```bash
# 判据⑤：引擎 0 基线
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/chain-c0 --engine 0     # ⇒ film_d5.npy = 0x589f8364

# 判据 F：引擎 1 的指纹（真机日志应与此一致）
python3 tools/engine-shader-offline.py --dng test_temp/FILMKIT_20261009-090803.dng \
  --roi 1800,2100,600,1000 --out /tmp/chain-c1 --engine 1     # ⇒ 23211 字节 / 0xbf298386

# 引擎 0 vs 引擎 1 的逐像素差异（成片域口径）
python3 tools/stage2-engine-diff.py --a /tmp/chain-c0/film_d5.npy \
  --b /tmp/chain-c1/film_d5_sigmoid.npy
```
