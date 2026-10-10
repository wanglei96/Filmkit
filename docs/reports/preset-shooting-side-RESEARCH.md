# FilmKit 拍摄前（in-camera）预设调研报告

> 调研日期：2026-10-10 ｜ 调研子代理交付 ｜ 唯一产出文件 = 本报告
> **本次任务**：补齐 `docs/reports/editor-preset-RESEARCH.md`（791 行，覆盖**后期预设**）**未覆盖的一侧** ——
> **拍摄前（拍照界面）选风格的产品形态与专业做法**。
> 用户口径：**「拍照界面的预设」与「查看页的胶片面板」应该是同一套**。
> **本次不写代码、不改代码**，只产出本报告。

---

## 0. 摘要（先看这 10 条）

| # | 结论 | 关键证据 |
|---|---|---|
| 1 | **Fuji 机内 recipe 是"拍摄前预设"的社区标准**，字段是一组固定的 **JPEG 参数**（共 14 项）：Film Simulation / Dynamic Range / Grain Effect / Color Chrome Effect / Color Chrome FX Blue / White Balance + WB Shift / Highlight / Shadow / Color / Sharpness / Noise Reduction(High ISO NR) / Clarity / ISO / Exposure Compensation | Fuji X Weekly 编程指南原文（§2.1 引文 A）|
| 2 | **recipe 确实是"拍前专属"**：必须存进相机 **Custom Settings 槽（C1–C7，部分机型 C1–C4 / C1–C6）** 才生效；改完**不保存**、一切换模式就回退 | X-Alchemy 编程指南（§2.3 引文 C）|
| 3 | **但"拍前专属"≠"后期不能改"**：Fuji **官方**明说 Color Chrome「**can either be applied while shooting or when processing RAW files in-camera (or when using FUJIFILM X RAW STUDIO software)**」；X RAW STUDIO 的定义是「**harness the power of the camera's image processing engine when processing RAW pictures on a computer**」⇒ **同一套参数定义、两种应用时机** | Fuji 官方 learning centre（§3.2 引文 D）；Fuji X-T5 说明书（§3.2 引文 E）|
| 4 | **只有拍摄侧能定的参数**：`Dynamic Range`（**改变传感器曝光**，且带 ISO 下限：DR200 需 ≥2×base ISO、DR400 需 ≥4×base ISO）、`High ISO NR`、`Sharpness`、**曝光补偿**（硬件旋钮，**根本不能存进 recipe**）| X-Alchemy DR 指南（§3.1 引文 F）；Fuji X Weekly（§2.3 引文 B）|
| 5 | **两侧共有的参数**：Film Simulation 本体、WB + WB Shift、Highlight/Shadow(Tone Curve)、Color、Clarity、Grain、Color Chrome。**判据 = 官方说"拍摄时或机内 RAW 处理时都能应用"** | Fuji 官方 learning centre（§3.2）|
| 6 | **"强度"在拍照侧有三种真实形态**：**(a) 完全没有**（Fuji recipe、Dazz 相机型号）；**(b) 有但离散**（Fuji Grain 的 OFF/WEAK/STRONG × SMALL/LARGE）；**(c) 有且连续实时**（**VSCO Capture**：摄影师官方引用「**I love that I can adjust the intensity in real time**」；bloom/halation 有 intensity/size/range，grain 有 strength/size/color）| App Store 官方描述（§4 引文 G/H）|
| 7 | **拍照界面预设 UI 的 4 个真实做法**：**X half**＝取景画面**右滑** → 选 `FILM SIMULATION` 或 `FILTER` → 在**副屏**选择；**VSCO Capture**＝50+ **live presets**（实时预览）+ **独立分层的 Manual controls**；**Dazz**＝**one tap** 选"相机"（29 个型号），WB/曝光/ISO/快门/焦距独立可调；**Filmix Camera**＝**49 内置预设**，browse/search/favorites/quick select + `link effect strengths` + 一键 `pause effects` | X half 官方说明书（§5.1）；Engadget（§5.2）；Dazz App Store（§5.3）；Filmix Camera App Store（§5.4）|
| 8 | **胶片质感在拍照侧：独立滤镜层 vs 打包进预设，业界两种都有人做**。<br>**X half 官方把 `LIGHT LEAK` / `HALATION` / `VIGNETTE` / `SOFT GLOW` 做成与 FILM SIMULATION 并列的独立 FILTER 条目，且说明书未提任何强度参数**（用户抱怨 halation「way too strong」却无法调）；<br>**VSCO Capture 把 grain / bloom / halation 放进 Manual controls，有连续强度**；<br>**Dazz 打包进相机型号**（官方描述未提强度） | X half 官方 FILTER 列表（§6.1）；Reddit 用户反馈（§6.1）；VSCO App Store（§6.2）；Dazz App Store（§6.3）|
| 9 | **"一套预设三处复用"没有查到完全一致的先例**，但有 3 个**部分**先例：<br>**(a) Filmix（最接近）**：官方原话「**brings film simulations and pro camera controls together, from the moment you shoot to the final look**」+「Custom presets: **save the looks you create**」⇒ 拍照侧与编辑侧同一 App；<br>**(b) VSCO**：Capture（50+ presets）与主 App（200+ presets）**照片同步**，但**预设数量不同 ⇒ 不是同一套**（推断）；<br>**(c) Fuji**：App→相机是**单向传输**（Recipe Transfer，走 USB，非官方/无授权，作者自述「may void your camera's manufacture warranty」）；RAW 元数据→Lightroom profile 要**第三方插件**（X-LR）才能自动套用 | Filmix App Store（§7.1）；Engadget + 两处 App Store（§7.2）；Fuji X Weekly Recipe Transfer（§7.3）；X-LR 插件页（§7.3）|
| 10 | **★ schema 裁决：持久化/交换格式应改为"语义名 → 值"，内存模型保留"索引键 → 值"**。<br>决定性理由 = **槽位语义漂移在本项目已真实发生两次**（`[5..10]` shoulder/toe → filmic latitude；`[26][27]` 颗粒语义修正）：**索引键 JSON 在漂移后无法自动迁移**（看不出 `"5":0.0` 是旧语义的 shoulder 还是新语义的 latitude），**语义名可以**。业界对标物（Lightroom `.xmp`、RawTherapee `.pp3`）**都是命名键值** | 本仓库 `FilmParams.h:37-44`（ADR-19 记录）；`EditorExport.kt:1860-1865`（现格式）；RawTherapee `procparams.cc`（§8.2）|

---

## 1. 调研方法与证据口径

### 1.1 工具与可用性（**实测，供复核**）

| 工具 | 状态 | 备注 |
|---|---|---|
| `web_search` 工具 | ❌ **不可用** | 缺 API key（与 `editor-preset-RESEARCH.md` §1 一致）|
| `web_fetch` 工具 | ✅ 可用 | 但对 Cloudflare 站点返回 403 |
| `curl` + **Brave Search 端点** | ✅ **可用（本次主力）** | `https://search.brave.com/search?q=...`，脚本见 §9.3 |
| DuckDuckGo HTML / lite 端点 | ❌ **返回空结果**（被拦） | 与上一轮调研报告的说法不同，本次实测**已失效** |
| Bing / Ecosia / Marginalia / Startpage / Searx | ❌ 全部被拦或无结果 | — |
| `reddit.com/.../.rss` | ✅ **可用（重要突破口）** | `.json` 端点被拦（返回 "Blocked"），**`.rss` 可用**，能拿到帖子正文 + 评论 |
| `r.jina.ai` 文本代理 | ❌ 自身被 Cloudflare 拦 | — |

**被 Cloudflare 拦、本次**没能**取得正文的站点**（诚实标注）：
`support.vsco.co`（VSCO Capture 两份官方指南）、`fujifilm-x.com` 的部分路径（后经 `en-gb` 路径成功）、
`shopusa.fujifilm-x.com`、`dpreview.com`、`digitalcameraworld.com`、`appshunter.io`、`vsco.co/vsco/journal/`。

### 1.2 证据分级（本报告统一口径）

| 级别 | 含义 | 本报告中的例子 |
|---|---|---|
| **官方一手** | 厂商说明书 / 官方帮助页 / 官方产品页 / 官方 App Store 描述 | Fuji X-T5 说明书、Fuji X half 说明书、Fuji learning centre、VSCO/RND/Dazz/Filmix 的 App Store 描述、RNI 官方 FAQ |
| **官方二手（可信社区权威）** | 长期维护、被厂商生态默认引用的社区源 | Fuji X Weekly（Ritchie Roesch）、X-Alchemy |
| **社区主观** | 用户评测 / 论坛帖，**明确标注为个人观点** | Reddit 的 App 横评、用户 bug 报告 |
| **本仓库** | 项目内代码 / 文档，可 `grep` 复核 | `FilmParams.h`、`EditorExport.kt`、`editor-ADR.md` |

### 1.3 诚实性约定

- **查不到的一律写"没查到"**，不做推测性陈述。
- 推测性判断**显式标注"（推断）"**并给出推断依据。
- 所有 URL 均为本次**实际抓取成功**的地址（§9 给出完整索引与复现命令）。

---

## 2. Fuji X Weekly recipe：完整字段样本 + 逐字段含义

### 2.1 字段全集（**官方社区口径**）

Fuji X Weekly 作者 Ritchie Roesch 在其编程指南中给出的**完整字段清单**（原文引文 A）：

> "On the latest cameras, a Film Simulation Recipe contains: **Film Simulation, Dynamic Range, Grain Effect, Color Chrome Effect, Color Chrome FX Blue, White Balance and WB Shift, Highlight, Shadow, Color, Sharpness, Noise Reduction, Clarity, ISO, and Exposure Compensation**. All of those settings together make up a Recipe. The older the camera, the fewer parameters there are, and might only include: Film Simulation, Dynamic Range, White Balance and WB Shift, Highlight, Shadow, Color, Sharpness, Noise Reduction, ISO, and Exposure Compensation. On the newer models, **Noise Reduction was renamed High ISO NR** (it is the same thing) and **Highlight and Shadow were combined into Tone Curve**."
> —— <https://fujixweekly.com/2024/02/27/how-to-program-film-simulation-recipes-to-your-fujifilm-camera/>

同文的定性（原文引文 B）：

> "A Film Simulation Recipe is simply a set of **JPEG parameters** that produce a certain look **straight-out-of-camera**"
> "**One parameter that cannot be saved is 'Typical Exposure Compensation.'** On most Fujifilm cameras, there is an Exposure Compensation knob on the top of the camera"

⇒ **14 项字段 = 一份 recipe 的完整构成**。注意其中 **Exposure Compensation 是"不能存进 recipe"的**（硬件旋钮）。

### 2.2 两个真实配方样本（**逐字段原文**）

#### 样本 1：CineStill 800T v2（X-Trans V，2026-09-29）

来源：<https://fujixweekly.com/2026/09/29/cinestill-800t-v2-a-fujifilm-recipes-for-x-trans-v-cameras/>

```
Film Simulation: Reala Ace
Dynamic Range: DR200
Grain Effect: Strong, Small
Color Chrome Effect: Off
Color Chrome FX Blue: Off
White Balance: 4500K, -1 Red & -3 Blue
Highlight: +2
Shadow: 0
Color: +4
Sharpness: -1
High ISO NR: -4
Clarity: -3
ISO: Auto, up to ISO 6400
Exposure Compensation: -1/3 to +1/3 (typically)
```

**同一篇文章里的一条关键观察**（对 §6 很重要）：

> "Also, **halation is glaringly missing**. The Fujifilm X *half* has a halation filter; **if Fujifilm ever added it as an effect that could be applied to Recipes on X-series cameras, that would help to complete the look.**"

⇒ **机内 recipe 至今没有 halation 字段**；作者明确希望它成为"可以应用到 recipe 上的效果"（即：**目前 halation 在 Fuji 生态里是独立滤镜，不是 recipe 字段**）。

#### 样本 2：Kodachrome 64（X-Trans IV / X100V，2020-05-27）

来源：<https://fujixweekly.com/2020/05/27/my-fujifilm-x100v-kodachrome-64-film-simulation-recipe/>

```
Classic Chrome
Dynamic Range: DR200
Highlight: 0
Shadow: 0
Color: +2
Noise Reduction: -4
Sharpening: +1
Clarity: +3
Grain Effect: Weak, Small
Color Chrome Effect: Strong
Color Chrome Effect Blue: Weak
White Balance: Daylight, +2 Red & -5 Blue
ISO: Auto, up to ISO 6400
Exposure Compensation: 0 to +2/3 (typically)
```

**该文的作者自述（对 §3/§8 的"表达力边界"有价值）**：

> "there are always compromises when recreating looks **in-camera** because **the tools available to me are limited**"
> "Both of the Color Chrome Effects, the lower Dynamic Range setting, and Clarity **add contrast**, so I changed Highlight and Shadow to compensate."
> "The X-T4 has .5 Highlight and Shadow adjustments"（⇒ **档位粒度随机身变化**）

### 2.3 逐字段含义表

档位范围来自 X-Alchemy 的编程指南（<https://x-alchemy.app/academy/programming-recipes>，原文引文 C）与 Fuji 官方 learning centre（§3.2）。

| 字段 | 档位 / 取值 | 作用 | 属于哪一侧 | 依据 |
|---|---|---|---|---|
| `Film Simulation` | 枚举（PROVIA / Velvia / ASTIA / CLASSIC CHROME / REALA ACE / CLASSIC Neg. / NOSTALGIC Neg. / ETERNA / ACROS(+Ye/R/G) / SEPIA）| 整套色彩与影调的基础 | **两侧共有**（可后期机内重跑）| X half 说明书 §FILM SIMULATION；X-Alchemy「This is the foundation. Every other setting is tuned to work with this specific simulation」|
| `Dynamic Range` | DR100 / DR200 / DR400 / Auto | **拍摄侧改曝光**：DR200/400 让传感器少收 1–2 档光，JPEG 阶段再用色调曲线把中间调/阴影推回；高光多留 1–2 档 | **只有拍摄侧**（曝光部分）；tone curve 部分后期可重跑 | §3.1 引文 F |
| `Grain Effect` | 强度 `OFF/WEAK/STRONG` × 大小 `SMALL/LARGE`（旧机身只有 OFF/WEAK/STRONG）| 加"受控的随机噪声"模拟胶片颗粒 | **两侧共有** | Fuji learning centre 原文（§3.2 引文 D）|
| `Color Chrome Effect` | `OFF/WEAK/STRONG` | 高对比场景下**防止 RGB 单通道过载**，保住色调层次；灵感来自 FUJICHROME fortia | **两侧共有**（官方明说两种时机都行）| 同上 |
| `Color Chrome FX Blue` | `OFF/WEAK/STRONG` | 同上，**只作用于蓝色区域**（类偏振镜效果）| **两侧共有** | 同上 |
| `White Balance` + `WB Shift` | Kelvin 值（或 Daylight 等预设）+ R/B 偏移 | 色温 + 色调；**recipe 色彩性格的主要来源** | **两侧共有** | X-Alchemy「**This is where much of a recipe's colour character comes from**」|
| `Highlight` / `Shadow`（新机身合并为 `Tone Curve`）| 各 −2..+4（部分机身有 .5 步进）| 分别控制亮部/暗部对比；**正值增加对比，负值降低** | **两侧共有** | X-Alchemy Step 5 |
| `Color` | −4..+4 | 饱和度 | **两侧共有** | X-Alchemy Step 6 |
| `Sharpness` | −4..+4 | 边缘锐化 | **只有拍摄侧**（机内 JPEG 链路）| X-Alchemy Step 6 |
| `High ISO NR`（旧名 `Noise Reduction`）| −4..+4 | 降噪强度 | **只有拍摄侧**（RAW 解码相关）| X-Alchemy Step 6 |
| `Clarity` | −5..+5（X-Pro3 及以后）| 中间调对比 | **只有拍摄侧**（有性能代价）| X-Alchemy Step 6；Fuji X Weekly「**Clarity causes a short storing pause when set to anything other than 0**」+「**Clarity isn't available with HEIF**」|
| `ISO` | Auto ISO 参数（默认/最高/最低快门）| 感光度范围 | **只有拍摄侧**（曝光）| X-Alchemy Step 7 |
| `Exposure Compensation` | 停止数 | 曝光补偿 | **只有拍摄侧，且不能存进 recipe** | 引文 B |

**一个重要的反直觉事实**（对 §8 的"预设该存什么"有直接价值）：

> "For those with the newest cameras, **Custom Settings saves more than just your Recipes, but literally most camera settings, including focus preferences and even sound settings.**"
> —— Fuji X Weekly（引文 B 同文）

⇒ **Fuji 的 C 槽是"整机设定快照"，粒度太粗**（含对焦偏好、声音设置）。这与我们 ADR-19 已定的"预设只存风格（不存裁剪 / RAW 解码 / 局部调整）"形成**正面反例教材**：**拍照侧的"预设"若粒度太粗，就无法与后期侧共用同一套数据**。

### 2.4 机身差异（**字段可用性不是常量**）

| 差异 | 事实 | 来源 |
|---|---|---|
| C 槽数量 | 多数机型 C1–C7；X-S10 / X-S20 只有 C1–C4；部分 GFX 是 C1–C6 | 引文 B |
| WB Shift 能否存进 C 槽 | **X-T3 / X-T30（不含 X-T30 II）及更早机型不能**，只能在 IQ 菜单里设；且那些机型**每种 WB 类型只记一个 Shift** | 引文 B |
| 模拟可用性 | NOSTALGIC Neg. / REALA ACE 仅 X-Trans V；CLASSIC Neg. / ETERNA Bleach Bypass 从 X-Trans IV 起；ACROS 从 X-Trans III 起 | X-Alchemy |
| 跨代差异 | 同代机身输出一致；跨代（X-Trans IV↔V）色彩与噪声处理有细微差异 | X-Alchemy |

---

## 3. 拍照侧 vs 后期侧：参数差异表

### 3.1 ★ 决定性证据：Dynamic Range 到底改了什么

来源：<https://x-alchemy.app/academy/dynamic-range-guide>（原文引文 F）

> "**The tone curve half of this feature applies to JPEG processing.** If you shoot RAW+JPEG, the JPEG gets the DR tone curve and **a RAW opened in third-party software does not**, which is why the two can look so different on screen. **The exposure half of it reaches the RAW regardless**: at DR200 and DR400 the camera gives the sensor one or two stops less light, so a RAW shot at DR400 carries that extra highlight headroom and arrives looking dark until you lift it."
>
> "**Reprocessing in-camera or in X RAW Studio applies the DR tone curve properly.**"

**DR 的 ISO 下限**（硬约束，同一来源）：

| 基础 ISO | DR200 最低 ISO | DR400 最低 ISO | 典型机身 |
|---|---|---|---|
| 125 | 250 | 500 | X-Trans V（X-T5 / X100VI / X-H2）|
| 160 | 320 | 640 | X-Trans IV（X-T4 / X100V / X-S10）|
| 200 | 400 | 800 | 更老的基础 ISO 200 机身 |

> "**If your recipe specifies DR200 or DR400, make sure your ISO meets the minimum or the camera will not let you select the setting.**"（X-Alchemy 编程指南）

⇒ **DR 是"拍摄侧独有"的最硬证据**：它**改变传感器实际接收的光量**，后期无法还原（后期只能拿到"已经少曝 1–2 档"的 RAW）。

### 3.2 ★ 决定性证据：Fuji 官方说"同一套参数、两种应用时机"

来源：Fuji 官方 learning centre <https://www.fujifilm-x.com/en-gb/learning-centre/color-chrome-and-film-grain-effects/>（原文引文 D）

> "The effect **can either be applied while shooting or when processing RAW files in-camera (or when using FUJIFILM X RAW STUDIO software)** and is available in WEAK and STRONG settings."
> "It has **no effect on RAW files**."（指 Color Chrome 不改变 RAW 数据本身）

来源：Fuji 官方 X-T5 说明书 <https://fujifilm-dsc.com/en/manual/x-t5/connections/raw/>（原文引文 E）

> "Using X RAW STUDIO, you can **harness the power of the camera's image processing engine when processing RAW pictures on a computer**."

⇒ **这是"一套预设两处用"的官方实现范式**：**参数定义完全相同**（OFF/WEAK/STRONG），**区别只在"什么时候应用"**（拍摄时 vs 后期机内重跑）。

### 3.3 ★ 决定性证据：拍摄侧设定如何流入后期（以及它为什么需要专门做）

来源：X-LR 插件官方页 <http://lightroomsolutions.com/jb-xlr/>（John Beardsworth）

> "X-LR is a plugin for Fujifilm users which uses **the film simulation settings from Fujifilm raw files** to automatically: **Apply the Lightroom profile corresponding to the film simulation**; Apply Develop Presets based on the film simulation; ..."
> "When you import Fuji raw files into Lightroom, you see the results of Fuji's film simulation in the Import dialog box and also briefly in Library. **Adobe's own raw conversion then takes over and you get the Adobe look.**"
> "If you want Fuji's film simulation colours in Lightroom, Develop's Camera Calibration panel offers 'profiles'. **Adobe developed these in conjunction with Fujifilm engineers and they are quite close.**"
> "But how can you remember which film simulation you used for a photo or set of photos? ... **the information is not shown anywhere in Lightroom**"

同源报道（2017）：<https://www.fujirumors.com/lightroom-plug-reads-fujifilms-film-simulation-raw-file-automatically-applies-corresponding-profile-lightroom/>

> "「Film simulations」 are Fujispeak for the camera settings which fine tune JPEG output and **which are recorded in the raw files**（just like Nikon's "picture controls" and Canon "picture styles"）"

⇒ **完整证据链**：
1. **拍摄侧的 recipe 设定确实被写进 RAW 文件**（元数据）；
2. **Adobe 的 RAW 转换默认不读它**（你会得到 "Adobe look"）；
3. **Lightroom 提供的 Camera Matching profiles 是 Adobe 与 Fuji 工程师合作开发的，"quite close" 但不等同**；
4. **Lightroom 自身不显示这个信息** ⇒ 想"拍摄侧设定 → 后期自动套用"，**需要第三方插件专门做**。

### 3.4 参数分侧总表

| 参数 | 只有拍摄侧 | 两侧共有 | 只有后期侧 | 依据 |
|---|:---:|:---:|:---:|---|
| **Dynamic Range（曝光部分）** | ✅ | | | 改传感器曝光，后期无法还原（§3.1）|
| **Dynamic Range（tone curve 部分）** | | ✅ | | 官方：「reprocessing in-camera or in X RAW Studio applies the DR tone curve properly」（§3.1）|
| **High ISO NR / Noise Reduction** | ✅ | | | 机内 RAW 解码链路（§2.3）|
| **Sharpness** | ✅ | | | 机内 JPEG 链路（§2.3）|
| **Clarity** | ✅ | | | 机内 JPEG 链路，有存储停顿；HEIF 下不可用（§2.3）|
| **Exposure Compensation** | ✅（且**不能存进 recipe**）| | | 硬件旋钮（引文 B）|
| **ISO** | ✅ | | | 曝光三要素（X-Alchemy Step 7）|
| **Film Simulation 本体** | | ✅ | | 官方说可机内重跑（§3.2）|
| **White Balance + WB Shift** | | ✅ | | 同上；且是 recipe 色彩性格主要来源（§2.3）|
| **Highlight / Shadow（Tone Curve）** | | ✅ | | 同上 |
| **Color（饱和度）** | | ✅ | | 同上 |
| **Grain Effect** | | ✅ | | 官方学习中心把它与 Color Chrome 并列讲"可在两种时机应用"（§3.2）|
| **Color Chrome Effect / FX Blue** | | ✅ | | 官方**明文**「while shooting or when processing RAW files in-camera (or X RAW STUDIO)」（§3.2）|
| **局部调整 / 蒙版** | | | ✅ | Lightroom 真实预设 `.xmp` 无 crop/mask 字段（上一轮调研 §3.1b）；Capture One 走 Style Brush |
| **裁剪 / 几何** | | | ✅ | ADR-16 已隔离；Lightroom 把几何类排除在 Amount 之外 |
| **连续强度（Amount）** | | | ✅（**拍照侧通常没有**，见 §4）| §4 |
| **Halation** | ⚠ **Fuji 机内 recipe 没有此字段** | | | Fuji X Weekly 原文「halation is glaringly missing」（§2.2 样本 1）|

### 3.5 业界怎么处理"同一套预设、两种用途"——三种范式

| 范式 | 代表 | 机制 | 是否"同一套数据" |
|---|---|---|---|
| **A. 拍前专属，后期不可改** | Fuji **机内 recipe**（直出 JPEG 场景）| 设定存 C 槽，只在拍摄时生效 | 是（但后期用不上）|
| **B. 拍前设定 → 后期"重跑引擎"** | Fuji **X RAW STUDIO** / 机内 RAW 处理 | 后期把相机当协处理器，用**同一套参数定义**重新出片 | ✅ **是（官方范式）** |
| **C. 拍前设定 → 后期"近似 profile"** | Fuji RAW → Lightroom Camera Matching profile（需 **X-LR** 插件才自动）| 元数据记录 film simulation，Adobe 用"合作开发、quite close"的 profile 近似 | ❌ 近似，不等同 |
| **D. 拍前/后期各一套预设库** | **VSCO**（Capture 50+ vs 主 App 200+）| 只同步**照片**，不同步**预设定义** | ❌（推断，依据是数量差异，见 §7.2）|
| **E. 拍摄界面只是"滤镜预览层"** | **RNI Films / VSCO / Dazz / Kuji**（被社区横评归为 "filter-stage"）| look 来自捕获后叠加的层 | — |

> **社区横评的分类框架**（**社区主观**，非官方；来源 <https://www.reddit.com/r/mobilephotography/comments/1t3mgia/>）：
> "**Capture-stage apps** (the look comes from how the file is captured)… **Filter-stage apps** (the look comes from a layer applied after capture)：**RNI Films**、**VSCO**、**Dazz Cam**、**Kuji Cam**"
> "The visual difference between capture-stage and filter-stage shows up most in three places: **shadow detail under push, grain rendering at high ISO, and skin tone behavior under editing**. Filter apps look great straight to social. **Capture apps survive grading.**"
>
> ⚠ **口径提醒**：这是**单个 Reddit 用户的横评帖**（自述测了 8 个月、iPhone 16 Pro、5 种场景），**不是官方也不是同行评议**。它的价值在于**提供了"capture-stage vs filter-stage"这个分野词汇**，不应被当作产品事实的直接证据。

---

## 4. 强度（Amount）在拍照侧怎么表达

### 4.1 三种真实形态

| 形态 | 产品 | 证据 |
|---|---|---|
| **(a) 完全没有强度** | **Fuji 机内 recipe**、**Dazz 相机型号** | Fuji：recipe 字段里没有任何强度项，全是离散档位（§2.1）；Dazz：App Store 描述通篇讲"选相机/配件"，**未提强度**（§5.3）|
| **(b) 有，但离散档位** | **Fuji `Grain Effect`**（OFF/WEAK/STRONG × SMALL/LARGE）、**Color Chrome**（OFF/WEAK/STRONG）| Fuji 官方 learning centre（§3.2 引文 D）|
| **(c) 有，且连续实时** | **VSCO Capture** | App Store 官方描述（引文 G/H，见下）|

### 4.2 ★ VSCO Capture：拍照侧连续强度的直接证据

来源：<https://apps.apple.com/us/app/vsco-capture-preset-camera/id6741483219>（**官方 App Store 描述**，原文引文 G）

> "**The presets deliver incredible results straight out of camera, and I love that I can adjust the intensity in real time.**" — Jaime Angel, Photographer（官方引用的摄影师评价）

同一页面的功能描述（原文引文 H）：

> "**REAL-TIME PHOTO EFFECTS** Shape light as you shoot. Add live Bloom and Halation photo effects to soften highlights, lift glow, and bring a cinematic, film-inspired character to your photos. **Dial in intensity, size, and range in real time**, so what you see on screen is exactly what you capture."
>
> "**LIVE FILM GRAIN** Add analog texture in the moment. Apply live, film-inspired Grain… **Customize strength, size, and color as you shoot** for an authentic aesthetic…"

⇒ **拍照侧的"强度"在 VSCO Capture 上是三个独立维度**：
- **preset 强度**：实时可调（摄影师引文）
- **bloom / halation**：`intensity` + `size` + `range`（三参数）
- **grain**：`strength` + `size` + `color`（三参数）

⇒ **对照我们的 `FilmParams`**（`docs/reports/editor-preset-RESEARCH.md` §4.1 已列）：
我们已有 `[34] lut_strength`（preset 强度）、`[26][27][60][61]`（颗粒：amount/size/midtone_bias/pivot）、`[62..65]`（halation：strength/threshold/radius/warmth）。
**我们的颗粒与 halation 参数维度与 VSCO Capture 同量级或更细**（我们多了 midtone_bias / pivot / threshold / warmth）。

### 4.3 拍照侧"强度"的一个交互陷阱（**真实 bug 记录**）

来源：Reddit r/vsco <https://www.reddit.com/r/vsco/comments/1r732qk/>（**社区主观**，但有 VSCO 官方人员回复）

> 用户：**"Applying Bloom or Halation removes the Preset Effect!"**
> "in VSCO Capture app if I select a preset first and then proceed to apply some degree of Bloom or Halation as part of **Manual controls**, then **the preset effect immediately gets removed**!! Only after I reset bloom or halation back to zero then the preset effect comes back."
> 复现步骤（用户给出）："open the app & select any preset. Then change to **Manual settings > Add some bloom/halation**, the preset effect goes away."
> **VSCO 官方人员（u/ZachVSCO）**："**That definitely sounds like a bug!**"

**这条证据的两个用途**：
1. **UI 结构证据**：VSCO Capture 里 **preset 选择** 与 **Manual controls（含 bloom/halation）是分层/分面板的**，不是同一个列表；
2. **设计警示**：**"预设"与"质感效果"如果是两层且各自有强度，两层之间的交互必须显式定义**（叠加？互斥？谁覆盖谁？）—— 否则就会出这种"加 halation 把 preset 弄没了"的 bug。

---

## 5. 拍照界面预设 UI 的 4 个真实做法

### 5.1 Fujifilm X half（**官方说明书，一手**）

来源：<https://fujifilm-dsc.com/en/manual/x-hf1/taking_photo/fs_filter/>

**交互流程（官方原文）**：

> 1. "**Swipe right on the LCD monitor in the shooting display.** The menu is displayed."
> 2. "Tap **FILM SIMULATION** or **FILTER**. Return to the shooting display."
> 3. "**Select the desired film simulation or filter on the secondary LCD monitor.**"

**形态总结**：

| 维度 | X half 的做法 |
|---|---|
| 预设呈现 | **在取景画面上右滑**唤出菜单 → 二选一入口（`FILM SIMULATION` / `FILTER`）→ **在第二块（副）LCD 上选择** |
| 分类 | 顶层二分：**胶片模拟** 与 **滤镜** 是**并列的两个入口**，不是层级包含关系 |
| 缩略图 | **没查到**（说明书只给了 `osd_sm_swipe02_x-hf1.png` 等界面示意图的文件名，未描述缩略图内容）|
| 实时生效 | **没查到**（说明书未说明是否实时预览；从"选完返回拍摄显示"的流程描述看，选择发生在副屏、取景在主屏 —— **推断**选择结果会反映到取景，但无直接证据）|
| 强度 | **没有**（说明书全篇未提任何强度参数）|
| 参数调节 | **X half 没有 highlight / shadow / DR / colour 控制**（社区帖标题即为 "no highlights / shadows / DR controls"）|

**社区对 halation 强度的抱怨**（**社区主观**，来源 <https://www.reddit.com/r/fujifilm/comments/1ku2avt/>）：

> "It's overcooked and undercooked at the same time. **Halation effect is way too strong**, but the image is also way too sharp. Film (especially 35mm and half-frame) has an inherent softness that's hard to automate."

⇒ **产品教训**：**把 halation 做成无强度的开关，用户会觉得"太强但没法调"**。

### 5.2 VSCO Capture（**官方产品页 + 官方 App Store + 媒体实测**）

来源：<https://www.vsco.co/capture>、<https://apps.apple.com/us/app/vsco-capture-preset-camera/id6741483219>、<https://www.engadget.com/mobile/vsco-launches-dedicated-capture-app-with-live-previews-184005036.html>

| 维度 | VSCO Capture 的做法 | 证据 |
|---|---|---|
| 定位 | "Photograph in RAW with a **live-preset camera app**"；"Shoot with **50+ live presets** and film effects" | 官方产品页 |
| 预设呈现 | "a camera with **live previews** where users can choose from **over 50 different presets with real-time film effects**" | Engadget 实测 |
| **缩略图来源** | **实时预览**（"live previews"、"what you see on screen is exactly what you capture"）| Engadget + 官方 App Store |
| 实时生效 | ✅ 实时 | 同上 |
| 强度 | ✅ **实时可调**（preset intensity）；bloom/halation 有 intensity/size/range；grain 有 strength/size/color | 官方 App Store（§4.2）|
| 参数分层 | **preset 层** 与 **Manual controls 层**（含 bloom/halation）**分开** | Reddit bug 报告（§4.3）|
| 保存语义 | "The app's **non-destructive presets retain the original captured image so users can modify them later and change filters after the fact**"；支持 RAW / ProRAW | Engadget |
| 与编辑侧关系 | "**Capture syncs instantly with the main VSCO app** for additional edits and sharing" | Engadget |
| **具体面板布局（横滑条 / 网格 / 图标行）** | **没查到**（官方 support 页 `support.vsco.co/en/articles/12698469-your-guide-to-presets-in-vsco-capture` 被 Cloudflare 拦，两次尝试均 403）| — |

### 5.3 Dazz Cam（**官方 App Store + 社区深度指南**）

来源：<https://apps.apple.com/us/app/dazz-cam-vintage-camera/id1422471180>、<https://www.reddit.com/r/DazzCam/comments/1tuyemk/>（社区主观，作者自述更新至 2026-06-16 / app 2.17.3）

| 维度 | Dazz 的做法 | 证据 |
|---|---|---|
| 定位 | "keeping it simple, **no need for post-editing**. With just **one tap**, you can capture the most authentic vintage photos" | 官方 App Store |
| 预设呈现 | **"相机"（型号）为单位**，共 **29 个照片相机**，分 6 类（Digital / Vintage 135 / Vintage 120 / Inst / Collection / Video / Legacy）| 社区指南 |
| 缩略图来源 | **没查到**（社区指南自述"unfortunately text-only to keep it editable"；官方描述未提）| — |
| 实时生效 | 社区指南描述了**拍摄管线**（见下），**但未描述 UI 是否实时预览** ⇒ **没查到** | — |
| 强度 | **没有**（官方描述与社区指南均未提强度滑杆）| — |
| 可调项（**独立于型号**）| **White Balance**（温度计图标，temperature + tint，单位 Kelvin；预设 Auto / Daylight 5500K / Tungsten 3200K）、**Aspect Ratio**（4:3 → 3:2 / 1:1）、曝光补偿、ISO、快门、**焦距**（预设档或自由）、闪光、配件（Fisheye / Starburst / Prism / ND / Color flash）| 官方 App Store + 社区指南 |
| **拍摄管线**（★ 对"拍照侧处理链"有参考价值）| "Dazzcam first captures a 12-megapixel photo in the form of **HEIF or RAW (or ProRAW)**. It then goes through **colour adjustments, tonality adjustments, film grain and effects, and cropping**. Finally, it's saved as a **JPEG**." | 社区指南 |
| 机型分层 | **HEIF 相机**（多数）：正常 iPhone 照片（好动态范围、tone mapping、低噪声、重锐化），有 `import` 按钮可批处理已有照片；**RAW 相机**（带蓝色 "R"，共 9 个）：动态范围更低、噪声更多，但更像胶片/数码相机，支持 **Film Negative**（Dazz Pro，启用应用内后处理与自定义色彩 profile）| 社区指南 |
| **"底片"机制**（★ 拍照↔后期的连接点）| "**Save the negative and reprocess it to achieve different exposure and color temperature.** (Available on select cameras)" | 官方 App Store |
| 部分型号默认 WB | "some cameras (e.g. FQS R, CCD R) **default to daylight WB as part of its style**" ⇒ **WB 可以是预设的一部分** | 社区指南 |

### 5.4 Filmix Camera（**官方 App Store**；与 ADR-14 的 Filmix 编辑器截图同一 App）

来源：<https://apps.apple.com/us/app/filmix-camera/id6761850384>

| 维度 | Filmix Camera 的做法 | 官方原文 |
|---|---|---|
| 预设数量 | **49 内置预设** | "Find your film look with **49 built-in presets**" |
| 浏览交互 | **browse / search / favorites / quick select** | "**Browse, search, save favorites, and switch quickly.**" |
| 效果开关 | **一键暂停效果** + **联动强度** | "**Pause effects, link strengths**, and organize photos by preset"；更新说明："**Pause film effects with one tap, link effect strengths**" |
| 预设构成 | **filters + grain + tone + color temperature** 组合 | "Film-inspired looks: **combine filters, grain, tone, and color temperature** for a natural analog feel" |
| 自定义预设 | **可保存自己创建的 look 并分享** | "**Custom presets: save the looks you create and share them with friends.**" |
| LUT 导入 | **支持导入 LUT** | "**LUT workflow: import LUTs** to bring your favorite color styles into Filmix" |
| 拍照专业控制 | Pro 模式：快门 / ISO / 曝光补偿 / 白平衡 / 对焦 + 直方图 | "Pro mode: adjust shutter speed, ISO, exposure compensation, white balance, and focus. Use the histogram and other tools to guide your shot." |
| 照片组织 | **按预设自动归档** | "**Photos can also be archived automatically by preset.**" |
| **具体面板布局** | **没查到**（ADR-14 有 Filmix **编辑器**截图记录，但**没有拍照界面截图**；官方描述未给布局）| — |

### 5.5 RNI Films（**结论：没查到机内拍摄模式**）

来源：<https://apps.apple.com/us/app/rni-films-photo-raw-editor/id1017098672>、<https://reallyniceimages.com/faq.html>

- **App Store 官方描述的标题是 "RNI Films: **Photo & RAW Editor**"，副标题 "Analog & Vintage Film Filters"**；描述全篇讲 **REAL FILM FILTERS / REAL FILM GRAIN / REAL FILM BLOOM & HALATION / ADJUSTMENT TOOLS / RAW EDITOR / VIDEO GRADING / SHARING / IN-APP WORKFLOW**，
  **没有任何"拍摄 / 取景 / shoot"功能的描述**。
- 官方 FAQ 的平台清单是：**Adobe Lightroom / ACR**、**Capture One**、**Mobile（iOS）**；并明确：
  > "**Do you have RNI Films for Android?** … Developing for Android is what we would really love to do. But unfortunately that's not viable for RNI right now. … the best way to experience RNI film simulation on Android is choosing one of our products for **Adobe Lightroom**."
- ⇒ **没查到 RNI Films App 有机内拍摄（相机）模式**。（**推断**依据：官方 App Store 描述与官方 FAQ 均只描述"编辑已有照片"，无任何拍摄功能表述；但 RNI 未明文说"我们没有相机模式"，故不做确定性断言。）

**RNI 对"强度"的官方说法**（对 §4 有参考价值）：

> "**Native amount adjustment**—as mentioned above—the strength of each film look can now be adjusted."（All Films 5，**profile-based design**）
> "RNI All Films 4 - Pro for Capture One implements the best possible support for Capture One layers allowing you to **adjust film intensity**… However some of the Capture One adjustments, **like film grain, can't be applied to Capture One layers**"

⇒ **注意这条**：**颗粒（grain）在 Capture One 的分层模型里无法参与强度混合** —— 与上一轮调研"强度有参数域缩放 vs 输出混合两条路线"呼应：**grain 是"输出混合"路线的天然成员**。

### 5.6 横向对比表

| 产品 | 预设呈现方式 | 缩略图来源 | 实时生效 | 强度 | 质感参数（颗粒/halation/暗角）位置 |
|---|---|---|---|---|---|
| **Fuji X half** | 取景右滑 → `FILM SIMULATION`/`FILTER` 二选一 → **副屏选择** | **没查到** | **没查到** | ❌ 无 | **独立 FILTER 条目**（LIGHT LEAK / HALATION / VIGNETTE / SOFT GLOW…），**与 film sim 并列** |
| **VSCO Capture** | 50+ live presets（**live previews**）+ **独立 Manual controls 层** | **实时取景预览**（"live previews"）| ✅ | ✅ **连续实时**（preset intensity + bloom/halation 三参数 + grain 三参数）| **Manual controls 层**（独立于 preset），有强度 |
| **Dazz Cam** | **29 个"相机"型号**，one tap | **没查到** | **没查到** | ❌ 无 | **打包进相机型号**；WB / 画幅 / 曝光 / ISO / 快门 / 焦距独立可调 |
| **Filmix Camera** | **49 内置预设**，browse/search/favorites/quick select | **没查到** | **没查到**（但 "Pause effects / link strengths" 暗示实时）| ✅ 有"联动强度"（`link effect strengths`）| **打包进预设**（"combine filters, grain, tone, and color temperature"），可一键 pause |
| **RNI Films** | **没查到机内拍摄模式**（纯编辑器）| — | — | 后期有 amount | 后期独立（grain / bloom & halation）|

**从这张表能提炼的产品结论（3 条，每条挂上表证据）**：

1. **"预设"与"质感效果"是否分层，业界两种都有人做**：X half 分层（FILTER 独立）、VSCO Capture 分层（Manual controls 独立）、Dazz 与 Filmix 打包。
   ⇒ **分层的好处**（推断，依据 = VSCO 把 halation/grain 的 intensity/size/range 暴露为独立参数，说明分层才能给出细粒度控制）；**打包的好处** = "one tap" 的简洁性（Dazz 官方明说 "keeping it simple… With just one tap"）。
2. **实时取景预览在拍照侧是可实现的**：VSCO Capture 官方明说 "live previews" / "what you see on screen is exactly what you capture" ⇒ **缩略图不必是预烘焙样张**。
   ⚠ 但**我们是否要做实时预览**是性能决策，必须真机实测后由用户拍板（项目铁律：性能取舍不许拍脑袋替用户决定）。
3. **"一键暂停效果"是一个低成本高价值的交互**（Filmix 官方把它列进更新说明的第一条）⇒ 值得抄。

---

## 6. 胶片质感参数在拍照侧的地位

### 6.1 ★ 决定性证据：X half 官方把质感做成"独立滤镜"，且无强度

来源：<https://fujifilm-dsc.com/en/manual/x-hf1/taking_photo/fs_filter/>（**Fuji 官方说明书**）

**官方 `FILTER` 完整列表**（原文照录，顺序保持）：

```
LIGHT LEAK, HALATION *, EXPIRED FILM, SOFT GLOW *, TOY CAMERA, MINIATURE *,
POP COLOR, HIGH-KEY, LOW-KEY, DYNAMIC TONE *, SOFT FOCUS *,
PARTIAL COLOR (RED), PARTIAL COLOR (ORANGE), PARTIAL COLOR (YELLOW),
PARTIAL COLOR (GREEN), PARTIAL COLOR (BLUE), PARTIAL COLOR (PURPLE),
CANVAS, RETRO, VIGNETTE, BLUR *, FISH EYE, COLOR SHIFT *, MIRROR *, DOUBLE EX. *
（* = Only during still photography）
```

**这份列表回答了 §6 的核心问题**：

| 质感 | X half 里的地位 | 是否有强度 |
|---|---|---|
| **漏光 LIGHT LEAK** | **独立 FILTER 条目**，与 `FILM SIMULATION` **并列** | ❌ 说明书未提任何强度参数 |
| **Halation** | **独立 FILTER 条目** | ❌ 同上 |
| **暗角 VIGNETTE** | **独立 FILTER 条目** | ❌ 同上 |
| **柔光 SOFT GLOW** | **独立 FILTER 条目** | ❌ 同上 |
| **颗粒** | **不在 FILTER 列表里**（X half 的颗粒来自 `FILM SIMULATION` 或未列出的设置）| — |

**官方对 FILTER 的定性**：

> "Take photos with filter effects."（与 `FILM SIMULATION` 的 "Simulate the effects of different kinds of film" 明确区分）
> "Depending on the subject and camera settings, images may in some cases be **grainy or vary in brightness or hue**."（官方免责声明）

⇒ **结论**：**Fuji 在 X half 上把"胶片质感"做成与"胶片模拟"并列的独立滤镜开关，并且不给强度**。
**产品代价已被用户实测指出**：halation "way too strong" 却无法调（§5.1）。

### 6.2 VSCO Capture：质感在 Manual controls 层，有连续强度

见 §4.2 引文 H。要点重述：

| 质感 | VSCO Capture 的参数 |
|---|---|
| **Grain** | `strength` + `size` + `color`（**三个**，拍照时实时可调）|
| **Bloom** | `intensity` + `size` + `range` |
| **Halation** | `intensity` + `size` + `range` |

⇒ **与 X half 形成完整对照**：同为拍照侧，**X half = 独立开关无强度；VSCO Capture = 独立层有连续强度**。

### 6.3 Dazz：质感打包进"相机型号"

官方描述（<https://apps.apple.com/us/app/dazz-cam-vintage-camera/id1422471180>）：

> "Dazz camera is inspired by classic film cameras from the 80s. **We've sampled real film stock to recreate the colors and textures of vintage photography.**"

社区指南描述的管线（§5.3）把 `film grain and effects` 列为**捕获后的处理步骤**，**不是用户可调项**。
官方描述里的可调项清单**没有**颗粒/漏光/halation/暗角强度。

⇒ **Dazz 的质感 = 型号的一部分，用户不可调**。

### 6.4 Filmix：质感打包进预设，但可"一键暂停"

官方描述："Film-inspired looks: **combine filters, grain, tone, and color temperature**"；更新说明："**Pause film effects with one tap, link effect strengths**"。

⇒ **Filmix 把 grain 打包进预设**（与我们的 `FilmParams` 把颗粒作为槽位、随预设一起存的做法**同构**），并提供"暂停效果"作为整体开关。

### 6.5 小结：四种真实做法

| 做法 | 代表 | 用户可控性 |
|---|---|---|
| **独立滤镜条目，无强度** | Fuji X half | 只能开/关（且 halation 被抱怨太强）|
| **独立效果层，连续强度** | VSCO Capture | 最高（3 参数/效果）|
| **打包进"型号"** | Dazz | 最低（选型号即定死）|
| **打包进预设 + 整体暂停** | Filmix | 中（可整体开关 + 联动强度）|

**对我们的含义**（**推断**，依据 = 上表 + 我们已有参数）：
我们的 `FilmParams` 把颗粒 `[26][27][60][61]`、halation `[62..65]`、暗角 `[66..68]` 都做成了**有语义的独立槽位** ⇒
**天然支持"打包进预设"（Filmix 路线）与"独立调节"（VSCO 路线）两种产品形态**，二者不冲突：
预设里带上它们的值 = 打包；拍照界面额外暴露滑杆 = 独立调节。**这正是"一套数据、多种 UI 暴露方式"**。

---

## 7. "一套预设三处复用"的先例

> **问题**：有没有产品做到"拍照 / 查看页 / 编辑器"三处复用同一套预设定义？
> **答案**：**没有查到完全一致的先例**。查到 **3 个部分先例**，按接近程度排序如下。

### 7.1 Filmix（**最接近**：拍照 + 编辑，同一 App）

来源：<https://apps.apple.com/us/app/filmix-camera/id6761850384>

> "**Filmix brings film simulations and pro camera controls together, from the moment you shoot to the final look**"
> "**Custom presets: save the looks you create** and share them with friends."
> "**Photos can also be archived automatically by preset.**"

**分析**：
- ✅ **拍照侧**（"pro camera controls"、"from the moment you shoot"）与**编辑侧**（"the final look"）在**同一个 App** 里；
- ✅ **用户自建预设可保存**（"save the looks you create"）⇒ 存在"一套用户预设"的概念；
- ✅ 与 ADR-14 记录的 Filmix **编辑器**截图（底部导航 `调整/预设/滤镜/裁剪/相框/转化`、顶栏 `保存预设`）**是同一个 App**；
- ⚠ **但"查看页"这一处没查到**（Filmix 的照片浏览页是否有"胶片"入口，**没查到**）；
- ⚠ **拍照侧与编辑侧是否读同一份预设数据，官方未明说**（**推断**为是，依据 = "from the moment you shoot to the final look" 的一体化表述 + "save the looks you create" 未限定在哪一侧）。

⇒ **Filmix 是"一套预设两处用（拍照 + 编辑）"的强候选先例**，但不是三处的完整先例。

### 7.2 VSCO（拍照 App 与编辑 App **分离**，只同步照片）

来源：<https://www.engadget.com/mobile/vsco-launches-dedicated-capture-app-with-live-previews-184005036.html>、两处官方 App Store 描述

| 事实 | 出处 |
|---|---|
| **VSCO Capture（拍照）**："**50+ live presets**"、"Photograph in RAW with a live-preset camera app" | VSCO Capture 官方 App Store / 产品页 |
| **VSCO 主 App（编辑）**："Experiment with **200+ high-quality presets**" | vsco.co 全站顶部导航文案（`/features/halation`、`/capture` 页面均出现）|
| **两者同步**："**Capture syncs instantly with the main VSCO app** for additional edits and sharing" | Engadget 实测 |
| 编辑侧衔接："Sync your photos seamlessly with the VSCO photo and video editor where you can fine tune photos with our full suite powerful tools and **200+ presets**" | VSCO Capture 官方 App Store |

**分析**：
- ✅ **照片**从拍照 App 流入编辑 App；
- ❌ **预设数量不同（50+ vs 200+）⇒ 不是同一套预设库**（**推断**，依据 = 数量差异；官方未说明两者预设是否共享）；
- ⇒ **VSCO 是"两套预设 + 照片同步"，不是"一套预设两处用"**。

### 7.3 Fuji 生态（三处都有动作，但**没有一处是"同一套数据自由流动"**）

| 路径 | 机制 | 是否"同一套数据" | 来源 |
|---|---|---|---|
| **App → 相机（拍照侧）** | **Recipe Transfer**：Fuji X Weekly App 通过 **USB** 把 recipe 传到相机的 **Custom Settings 槽（C1–C7 / C1–C6 / C1–C4）**；单个 recipe 免费、**Recipe Sets 需 Patron 订阅** | ✅ 是（同一套 recipe 定义）| <https://fujixweekly.com/2026/09/18/how-to-use-the-new-recipe-transfer-feature-in-the-fuji-x-weekly-app-a-practical-guide/> |
| **相机 → 后期（机内重跑）** | **X RAW STUDIO**：用相机引擎在电脑上重处理 RAW | ✅ 是（官方范式）| <https://fujifilm-dsc.com/en/manual/x-t5/connections/raw/> |
| **相机 → 后期（第三方）** | RAW 元数据里的 film simulation → Lightroom profile，**需 X-LR 插件** | ❌ **近似**（"quite close"）| <http://lightroomsolutions.com/jb-xlr/> |

**Recipe Transfer 的关键限制（诚实标注，来自该文作者自述）**：

> "It **may voids your camera's manufacture warranty**; … This is also an **unofficial, unlicensed program**, and it carries a small risk, and **the use of it is at your own risk**."
> "The Recipe Transfer feature is compatible with **all X-Trans V cameras, all X-Trans IV models except the X-T3 and X-T30, and newer GFX cameras**. **You can't use this feature with older models**"

⇒ **即便是 Fuji 生态，"预设从 App 流到相机"也是一个非官方、有风险、且受机型限制的通道** —— 这反过来说明**"一套数据跨端复用"在业界并不容易，没有成熟标准**。

### 7.4 §7 结论

| 问题 | 答案 |
|---|---|
| 有没有产品做到"拍照 / 查看页 / 编辑器"**三处**复用同一套预设？ | **没查到** |
| 有没有"拍照 + 编辑"**两处**复用？ | ✅ **Filmix**（最强候选，官方一体化表述）；VSCO 是"两套预设 + 照片同步"（❌）|
| 有没有"预设定义从 App 流到拍摄端"？ | ✅ **Fuji Recipe Transfer**（单向、非官方、有风险、限机型）|
| 有没有"拍摄端设定流回后期"？ | ✅ **Fuji RAW 元数据 + X RAW STUDIO**（官方）；Lightroom 需**第三方插件**且只是近似 |

⇒ **我们要做的"一套数据三处用"在业界没有直接对标物** ⇒ **这既是差异化机会，也意味着没有现成经验可抄，必须自己把数据模型定对**（这正是 §8 的重要性）。

---

## 8. ★ 两套 schema 不一致的裁决建议

### 8.1 分歧的确切内容（**先把事实钉死**）

| | **A. 代码 E3-1a（已实现）** | **B. E4 §4.2 设计（未实现）** |
|---|---|---|
| 位置 | `app/src/main/java/com/filmkit/app/camera/EditorExport.kt:1588-1598` | `docs/reports/editor-E4-film-params-DESIGN.md` §4.2/§4.3 |
| 参数表示 | `slots: Map<Int, Double>` —— **槽位索引 → 值** | `params: {语义名: 值}` —— **语义名 → 值** |
| 序列化形态 | `"slots":{"26":55.0,"27":800.0}`（`EditorExport.kt:1860-1865`）| `"grain_amount": 55.0, "grain_size": 800.0` |
| 稀疏语义 | ✅ 只存与恒等值不同的槽（`presetSlotIncluded`）| ✅ 同（"params 只允许 FilmParams 槽位"）|
| 映射表 | **不需要**（索引即键）| **需要** `FilmParamKeys`（语义名 ↔ 槽位，"代码里唯一维护"）|
| 未知键处理 | 越界索引丢弃（`EditorExport.kt:1569-1573`）| "解析时遇未知键必须报错" |
| 非引擎字段 | `id/name/group/favorite/createdAt/source/lut{id,strength}`（**已实现**）| `id/name/category/base/note/lut/lut_strength/ui{...}`（**设计**）|
| 版本字段 | `schema: 1`（`PRESET_SCHEMA`），未知版本**拒绝** | `schema: 1` + 逐版本迁移链（留给 E3-2）|

**共同点**（**不要推翻的部分**）：两套都是**稀疏字段集**、都**引用 LUT 资源**、都**带 schema 版本**、都**排除 RAW 解码槽 [39..59]**。这些是上一轮调研的结论（Lightroom 勾选 / RawTherapee `ParamsEdited` / darktable `style_items`），**两套 schema 在这四点上完全一致**。

**真正的分歧只有一条**：**参数键用"槽位索引"还是"语义名"**。

### 8.2 裁决：**持久化/交换格式用语义名（B），内存模型保留索引键（A）**

即 **"索引键 = 内存模型，语义名 = 线格式"** 的双层结构。

#### 理由 1（**决定性**）：槽位语义漂移已真实发生两次，索引键 JSON 无法自动迁移

本仓库已记录的事实（`editor-ADR.md` ADR-19 + `EditorExport.kt:1613-1618` 的注释）：

> `FilmParams.h:37-44` 记录过一次真实的**槽位语义漂移** —— `[5..10]` 从"高光软肩 / 阴影 toe（0 = 关）"**重定义**为"filmic latitude（0.01 = darktable 默认，**0 = 极端 S 曲线**）"：同一批数值在新语义下是**完全不同的画面**。

对比两套 schema 在漂移时的表现：

| | 索引键（A）| 语义名（B）|
|---|---|---|
| 漂移后读旧 JSON | 看到 `"5":0.0` —— **无法判断**这是旧语义的 "shoulder 关闭" 还是新语义的 "极端 S 曲线" | 看到 `"shoulder_r":0.0` —— **明确知道**这是旧的 shoulder 语义 |
| 能否自动迁移 | ❌ **不能**（信息已丢失）| ✅ **能**（写一个 `shoulder_r → toe_r` 或丢弃的迁移函数）|
| 后果 | 只能像现在这样**整条拒绝**（`presetSchemaUsable`，`EditorExport.kt:1622`）| 可以像 darktable 那样**逐版本迁移**（`legacy_params` 模式）|

⇒ **索引键 schema 把"槽位语义"这个最关键的信息丢在了代码里，没写进数据**。一旦漂移，**数据就成了不可解释的字节**。

**这条不是理论风险**：`FilmParams` 在 E4 还会继续改语义（`editor-preset-RESEARCH.md` §5.9 风险 1 已列："E4-3~E4-6 还会接线颗粒/halation/层间效应"）。**漂移必然再发生**。

#### 理由 2：**"一套数据三处用"要求数据自解释**

三处的**消费方不同**：

| 消费方 | 它需要什么 |
|---|---|
| **拍照界面** | 风格名 + 缩略图/预览 + **用户可读的参数读数**（"颗粒：强/小"）|
| **查看页「胶片」面板** | 全量参数 + 强度 |
| **编辑器** | 全量参数 + 强度 + 读写 |

**索引键 schema 迫使三处都硬编码槽位号**（`26` 是颗粒量、`34` 是 LUT 强度…）⇒ **槽位号一变，三处同时崩**，且每处都要重复一份"槽位号 → 文案"的映射。
**语义名 schema 让三处各自只读自己关心的字段**，UI 文案映射只需一处。

**业界对标物全部是命名键值**：
- **Lightroom `.xmp`**：命名键值（上一轮调研 §3.1b 实测真实预设文件）；
- **RawTherapee `.pp3`**：命名键值（`procparams.cc:257-286` 的 `saveToKeyfile(!pedited || edited.x, …)`，200+ 处按语义名稀疏写入）；
- **darktable**：`op_params` 是**不透明二进制 blob** ⇒ 所以它**只能做输出混合**，做不了参数域缩放。

⇒ **上一轮调研（ADR-19）已经定案"强度走参数域缩放（与 Lightroom 同构）"。参数域缩放的前提就是参数有语义名。** 索引键虽然在内存里有语义，但**序列化后语义丢失** ⇒ 与"参数域缩放"的定案存在**结构性张力**。

#### 理由 3：拍照侧 UI 必然要显示参数读数（ADR-14 已定）

ADR-14 的编辑器规格里明确要求"**物理量读数**"（`editor-ADR.md:537` 的 E2-10 行）。
拍照界面若要显示"颗粒 55 / ISO 800"这类读数，语义名让"值 → 文案"的映射天然存在；索引键则需要在 UI 层再建一张表。

#### 反方理由（**诚实列出**）与反驳

| 反方理由 | 评估 |
|---|---|
| "索引键零转换，直接与 `DoubleArray` 同构，套用最快" | ✅ **成立**，但**只影响内存模型，不影响线格式**。本裁决**保留**内存用 `Map<Int, Double>`（`presetSnapshot` 的 `for ((i, v) in slots)` 一行不用改）。|
| "语义名多一层映射表，多一个漂移点" | ✅ **成立且是真实代价**。缓解：**语义名一旦发布不可改名**，只能新增 + 迁移函数（与 darktable `legacy_params` 同型）；映射表加单测钉死双向一致性。|
| "体积变大"（`"26"` 3 字节 → `"grain_amount"` 14 字节）| ⚠ 需实测量化。粗算：稀疏槽平均 ~20 个 ⇒ 每预设多 ~200 字节。原估算 300–700 B/预设（ADR-19）⇒ 变成 ~500–900 B。**100 个预设 ≈ 50–90 KB**，仍在 prefs 单键可接受范围。**建议实测后再定**（不要拍脑袋）。|
| "E3-1a 已经写完了，改要返工" | 见 §8.4 迁移代价评估 —— **代价很小**，因为转换只发生在 `presetToJson` / `presetFromJson` 两个函数里。|

### 8.3 ★ 第二个建议（**同等重要，且本次调研新发现**）：预设本体 与 拍摄态 必须分离

本次调研发现的**最强反例**（§2.3）：

> Fuji 的 Custom Settings 槽 "**saves more than just your Recipes, but literally most camera settings, including focus preferences and even sound settings**"

⇒ **Fuji 的"拍照侧预设"粒度太粗**（含对焦偏好、声音设置），所以它**根本无法与后期侧共用** —— 后期侧不需要你的对焦偏好。

**同时**，本次调研也发现了"拍摄专属参数"的边界（§3.4）：

| 类别 | 例子 | 该不该进预设 |
|---|---|---|
| **风格参数** | film sim、WB+Shift、Highlight/Shadow、Color、Clarity、Grain、Color Chrome、LUT+强度、halation、暗角、颗粒 | ✅ **进预设本体**（三处共用）|
| **拍摄态** | 曝光补偿、ISO、快门、**焦距**、闪光、对焦模式、画幅 | ❌ **不进预设本体**，作为"拍照界面的当前状态" |
| **解码属性** | `[39..59]` RAW 解码链路 | ❌ 已排除（ADR-19）|
| **构图相关** | `[79..87]` 局部调整、裁剪 | ❌ 已排除（ADR-19）|

**证据支持这个分离**：

| 证据 | 含义 |
|---|---|
| Fuji："**One parameter that cannot be saved is 'Typical Exposure Compensation.'**"（硬件旋钮）| 曝光补偿**本来就不在 recipe 里** —— 业界已经这么做了 |
| Dazz：WB / 曝光 / ISO / 快门 / 焦距是**独立于相机型号**的调节项（§5.3）| 拍摄态与风格分离 |
| Dazz："some cameras (e.g. FQS R, CCD R) **default to daylight WB as part of its style**" | ⚠ **例外**：WB 可以是风格的一部分（默认值），但用户仍可覆盖 ⇒ **"预设提供默认值 + 拍摄态可覆盖"** |
| Fuji C 槽含对焦/声音设置（反例）| **粒度太粗就废了** |

⇒ **建议的数据模型（三段式）**：

```
EditorPreset {
  // ① 元数据（已实现）
  id, name, group, favorite, createdAt, source, schema
  // ② 风格参数（三处共用）—— 语义名
  params: Map<String, Double>        // "grain_amount" -> 55.0
  lut: { id, strength }              // 资源引用（已实现）
  // ③ 拍摄默认值（可选，仅拍照侧读；不改预设本体的语义）
  shooting: { wb?: ..., isoMax?: ... }   // ← 本次调研新增建议
}
```
**关键约束**：③ **必须是可选段**，且**拍照侧读它、查看页/编辑器忽略它** —— 这样"一套数据三处用"才不被拍摄专属字段污染。

### 8.4 迁移代价评估（**逐项，可执行**）

| 工作项 | 内容 | 规模估计 | 风险 |
|---|---|---|---|
| 1. 新增映射表 | `FilmParamKeys`：语义名 ↔ 槽位索引**双向**映射。E4 §4.3 **已有草表**（约 45 个名字：shoulder/toe/sat/split/monochrome/lut_strength/filmic_black_white/contrast/tone_strength/grain×4/halation×4/vignette×3/interlayer×9）| ~60–80 行 Kotlin + 单测 | 中（**表本身是新的漂移点** ⇒ 加"双向一致性"单测 + 契约变更门禁）|
| 2. 改序列化 | `presetToJson`（`EditorExport.kt:1852-1871`）与 `presetFromJson`（`:1890+`）各加一层 idx↔name 转换 | ~30–60 行 | 低 |
| 3. 未知键处理 | 解析遇未知语义名 ⇒ 按 E4 §4.2 原则**报错**（进 `skipped` 诊断，与现有 `PresetLoadResult.skipped` 机制天然吻合）| ~10 行 | 低 |
| 4. 旧数据迁移 | `slots{idx:val}` → `params{name:val}` 一次性迁移；`PRESET_SCHEMA` 1 → 2 | ~20 行 + 单测 | **需先确认**：`presets_v1` 是否已有真实用户数据（E3-1a 刚落地，**推断**无线上数据 ⇒ 可直接换格式，无需迁移函数）|
| 5. 内存模型 | **不改**（继续用 `Map<Int, Double>`；`presetSnapshot` / `scalePresetSlots` 不动）| 0 行 | 无 |
| 6. 内置预设库 | E4-4 的 15 个内置预设（E4 §4.4）**直接用语义名书写** ⇒ 可读、可评审、可 diff | — | 低（**这是收益**）|
| 7. `.fkpreset` 导入导出（E3-2）| 直接复用语义名格式 ⇒ **人可读、可手写、可分享** | — | 低（**这是收益**）|

**总代价估计**：**约 120–180 行新增/改动 + 2–3 个单测**，集中在 `EditorExport.kt` 的预设段（`:1520-2020`）。**不触及引擎、不触及 `FilmParams` 契约、不触及 shader。**

**关键判断**：**现在改是最便宜的时机**。
- E3-1a 刚落地，`PRESET_SCHEMA` 还是 1，**没有已发布的格式包袱**；
- E3-2（`.fkpreset` 导入导出）**尚未开工** ⇒ 等它开工再改，就要处理"已导出的文件"兼容；
- E3-3（查看页「胶片」）与 E3-4（内置预设包）**尚未开工** ⇒ 它们会直接受益于语义名（内置预设可读、可评审）。

### 8.5 裁决摘要（**给父代理直接引用**）

> **裁决**：采用 **E4 §4.2 的语义名 schema 作为持久化/交换格式**（`params: {"grain_amount": 55.0}`），
> **保留 E3-1a 的索引键作为内存模型**（`slots: Map<Int, Double>`，`presetSnapshot` 零改动）。
> **决定性理由**：槽位语义漂移（`[5..10]`、`[26][27]`）已真实发生，**索引键 JSON 在漂移后无法自动迁移**（信息已丢失），
> 而"一套数据三处用"与 ADR-19 已定案的"参数域缩放强度"**都要求参数带语义**。
> 业界对标物（Lightroom `.xmp` / RawTherapee `.pp3`）**全部是命名键值**。
> **附加裁决**：预设本体（风格）与**拍摄态**（曝光/ISO/焦距/闪光）**必须分离** ——
> 反例证据 = Fuji 的 C 槽因含"对焦偏好与声音设置"而无法与后期侧共用。
> **迁移代价**：约 120–180 行 + 2–3 单测，**不触及引擎/契约/shader**；**现在是最便宜的时机**（E3-2/3-3/3-4 均未开工）。

---

## 9. 证据索引

### 9.1 URL 清单（**全部为本次实际抓取成功**）

| # | 主题 | URL | 级别 |
|---|---|---|---|
| 1 | **Fuji recipe 完整字段清单 + 编程流程 + "曝光补偿不能存"+ "C 槽是整机快照"** | <https://fujixweekly.com/2024/02/27/how-to-program-film-simulation-recipes-to-your-fujifilm-camera/> | 官方二手 |
| 2 | **配方样本 1：CineStill 800T v2**（含 "halation is glaringly missing"）| <https://fujixweekly.com/2026/09/29/cinestill-800t-v2-a-fujifilm-recipes-for-x-trans-v-cameras/> | 官方二手 |
| 3 | **配方样本 2：Kodachrome 64**（含 "in-camera tools are limited"）| <https://fujixweekly.com/2020/05/27/my-fujifilm-x100v-kodachrome-64-film-simulation-recipe/> | 官方二手 |
| 4 | **Fuji 官方：Color Chrome / Film Grain**（含"拍摄时或机内 RAW 处理/X RAW STUDIO 都能应用"）| <https://www.fujifilm-x.com/en-gb/learning-centre/color-chrome-and-film-grain-effects/> | **官方一手** |
| 5 | **Fuji 官方：X RAW STUDIO 定义**（"harness the power of the camera's image processing engine"）| <https://fujifilm-dsc.com/en/manual/x-t5/connections/raw/> | **官方一手** |
| 6 | **Fuji 官方：X half 的 FILM SIMULATION 与 FILTER 列表 + 右滑交互** | <https://fujifilm-dsc.com/en/manual/x-hf1/taking_photo/fs_filter/> | **官方一手** |
| 7 | **DR 机制 + ISO 下限 + "tone curve 只作用 JPEG、曝光部分进 RAW"** | <https://x-alchemy.app/academy/dynamic-range-guide> | 官方二手 |
| 8 | **recipe 逐字段档位 + 编程步骤 + "不保存就回退"** | <https://x-alchemy.app/academy/programming-recipes> | 官方二手 |
| 9 | **VSCO Capture 官方产品页**（"50+ live presets"、"live-preset camera app"）| <https://www.vsco.co/capture> | **官方一手** |
| 10 | **VSCO Capture 官方 App Store**（intensity real time / bloom+halation 三参数 / grain 三参数 / 200+ presets 编辑侧）| <https://apps.apple.com/us/app/vsco-capture-preset-camera/id6741483219> | **官方一手** |
| 11 | **VSCO Capture 实测**（"live previews"、非破坏性、**Capture 同步到主 App**、CEO 引语）| <https://www.engadget.com/mobile/vsco-launches-dedicated-capture-app-with-live-previews-184005036.html> | 媒体报道 |
| 12 | **VSCO Capture 的 preset/halation 分层 + bug**（含 VSCO 官方人员回复）| <https://www.reddit.com/r/vsco/comments/1r732qk/> | 社区主观 |
| 13 | **Dazz 官方 App Store**（one tap、29 相机、可调项清单、"save the negative and reprocess"）| <https://apps.apple.com/us/app/dazz-cam-vintage-camera/id1422471180> | **官方一手** |
| 14 | **Dazz 社区深度指南**（29 相机分类、RAW/HEIF 分层、拍摄管线、WB 可属风格）| <https://www.reddit.com/r/DazzCam/comments/1tuyemk/> | 社区主观 |
| 15 | **Filmix Camera 官方 App Store**（49 预设、browse/search/favorites、pause+link strengths、custom presets、LUT 导入、"from the moment you shoot to the final look"）| <https://apps.apple.com/us/app/filmix-camera/id6761850384> | **官方一手** |
| 16 | **RNI Films 官方 App Store**（纯编辑器，无拍摄功能描述）| <https://apps.apple.com/us/app/rni-films-photo-raw-editor/id1017098672> | **官方一手** |
| 17 | **RNI 官方 FAQ**（无 Android、All Films 5 amount、**grain 不能用于 C1 图层**、camera profile 由 preset 自动选）| <https://reallyniceimages.com/faq.html> | **官方一手** |
| 18 | **X-LR 插件**（读 RAW 的 film simulation → 自动套 Lightroom profile；"Adobe look" 问题；"information is not shown anywhere in Lightroom"）| <http://lightroomsolutions.com/jb-xlr/> | 第三方工具官方页 |
| 19 | X-LR 报道（"recorded in the raw files"）| <https://www.fujirumors.com/lightroom-plug-reads-fujifilms-film-simulation-raw-file-automatically-applies-corresponding-profile-lightroom/> | 媒体报道 |
| 20 | **Fuji Recipe Transfer**（App→相机 USB 传输、C 槽、非官方/有风险、限机型）| <https://fujixweekly.com/2026/09/18/how-to-use-the-new-recipe-transfer-feature-in-the-fuji-x-weekly-app-a-practical-guide/> | 官方二手 |
| 21 | **capture-stage vs filter-stage 分类**（社区横评）| <https://www.reddit.com/r/mobilephotography/comments/1t3mgia/> | 社区主观 |
| 22 | X half 无 highlight/shadow/DR 控制（社区应对策略）| <https://www.reddit.com/r/fujiXhalf/comments/1qqyxz2/> | 社区主观 |
| 23 | X half halation "way too strong"（社区反馈）| <https://www.reddit.com/r/fujifilm/comments/1ku2avt/> | 社区主观 |

**抓取失败（Cloudflare 403 / 无结果），本报告未使用其内容**：
`support.vsco.co/en/articles/12698469-…`、`support.vsco.co/hc/en-us/articles/37620811613965-…`、
`vsco.co/vsco/journal/vsco-capture`、`shopusa.fujifilm-x.com/discover/choosing-film-simulations-and-filters-on-fujifilm-x-half/`、
`dpreview.com/articles/2220768333/…`、`digitalcameraworld.com/tech/apps/the-retro-look-…`、`appshunter.io/ios/app/6741483219`。

### 9.2 本仓库证据（可 `grep` 复核）

| 证据 | 位置 |
|---|---|
| 预设 schema（**索引键稀疏**）| `app/src/main/java/com/filmkit/app/camera/EditorExport.kt:1588-1598` |
| JSON 序列化（`"slots":{"26":55.0}`）| 同上 `:1852-1871` |
| 稀疏白名单（排除 [34]/[39..59]/[79..87]）| 同上 `:1534-1573` |
| schema 版本拒绝策略 + 漂移记录 | 同上 `:1609-1622` |
| 强度按恒等值缩放（参数域缩放）| 同上 `:1807-1841`（`scalePresetSlots`，含"amount=1 原样返回"的零回归设计）|
| 部分套用映射 | 同上 `:1654-1666`（`presetSnapshot`）|
| 读取容错 + 诊断 | 同上 `:1884-2000`（`PresetLoadResult` 定义在 `:1884`，`presetsFromJson` 在 `:1902`）|
| E4 设计的**语义名** schema | `docs/reports/editor-E4-film-params-DESIGN.md` §4.2 |
| E4 的语义名↔槽位草表 | 同上 §4.3 |
| E4 内置预设清单（15 个）| 同上 §4.4 |
| ADR-19 预设规格 v2（定案）| `docs/reports/editor-ADR.md` ADR-19 |
| ADR-14 Filmix **编辑器**面板五层结构 | 同上 ADR-14（`:244-288`）|
| ADR-15 Filmix 查看页底栏「胶片」入口 | 同上 ADR-15（`:288-302`）|
| 上一轮后期预设调研（强度两条路线、稀疏字段集等）| `docs/reports/editor-preset-RESEARCH.md` §3–§5 |

### 9.3 复现命令

```bash
# ① Brave 检索脚本（本报告主力工具；web_search 不可用时的替代）
mkdir -p /tmp/preset_research && cat > /tmp/preset_research/s.sh <<'EOF'
#!/bin/bash
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
Q=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1")
curl -s -m 25 -A "$UA" "https://search.brave.com/search?q=$Q" | python3 -c "
import sys,re,html
t=sys.stdin.read()
for m in re.finditer(r'<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>', t, re.S):
    u,inner=m.group(1),m.group(2)
    if any(x in u for x in ['brave.com','w3.org','schema.org']): continue
    txt=html.unescape(re.sub(r'<[^>]+>','',inner)).strip()
    if not txt or len(txt)<8: continue
    print(f'{u}\t{txt[:110]}')
" | awk '!seen[$1]++' | head -12
EOF
chmod +x /tmp/preset_research/s.sh
/tmp/preset_research/s.sh "Fujifilm X half halation filter"

# ② Reddit 正文（.json 被拦，.rss 可用）
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
curl -s -m 25 -A "$UA" "https://www.reddit.com/r/DazzCam/comments/1tuyemk/.rss" | python3 -c "
import sys,re,html
t=sys.stdin.read()
for e in re.findall(r'<entry>(.*?)</entry>',t,re.S)[:3]:
    ti=re.search(r'<title>(.*?)</title>',e,re.S); co=re.search(r'<content type=\"html\">(.*?)</content>',e,re.S)
    print('---',html.unescape(ti.group(1)) if ti else '')
    if co:
        txt=re.sub(r'<[^>]+>',' ',html.unescape(co.group(1))); print(re.sub(r'\s+',' ',txt)[:3000])
"

# ③ Fuji 官方 X half 滤镜列表（§6 的决定性证据）
curl -s -m 25 "https://fujifilm-dsc.com/en/manual/x-hf1/taking_photo/fs_filter/" \
  | python3 -c "
import sys,re,html
t=sys.stdin.read(); t=re.sub(r'<[^>]+>',' ',t); t=html.unescape(t)
i=t.find('LIGHT LEAK'); print(re.sub(r'\s+',' ',t[i-200:i+1200]))
"

# ④ 本仓库两套 schema 对照
sed -n '1520,1622p' app/src/main/java/com/filmkit/app/camera/EditorExport.kt
sed -n '706,770p'   docs/reports/editor-E4-film-params-DESIGN.md
```

---

## 附录 A：本次调研**没有**做的事（明确边界）

1. **没有写任何代码、没有改任何代码**（任务要求）。
2. **没有实测任何 App**（本机为 macOS，Dazz / VSCO Capture / Filmix / RNI 均为 iOS App，未安装）。
   所有 UI 描述均来自**官方说明书 / 官方 App Store 描述 / 媒体实测报道 / 社区帖**，已逐条标注级别。
3. **没有做性能实测**（如"实时取景预览在我们设备上的成本"）—— 按项目铁律，这类取舍必须真机实测后由用户拍板，本报告不预设结论。
4. **没有验证 LUT 资产的色彩准确性**（`editor-E4-film-params-DESIGN.md` §4.1 已指出源 LUT 不可追溯，本报告沿用该结论）。
5. **没有覆盖 Android 侧竞品**（本次查到的拍照侧产品均为 iOS；RNI 官方明确无 Android 版，见 §5.5）。
6. **没有给出 UI 视觉稿**（本报告只归纳形态与交互，不产出设计稿）。
