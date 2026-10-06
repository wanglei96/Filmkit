> 存档自 /tmp/jpeg444,2026-10-06

# 成片 JPEG 色度采样 4:2:0 → 4:4:4：归因实验、实现与代价实测

任务：判定并消除"强亮度对比边缘的紫/绿镶边"（vivo X300，暗室逆光拍窗外）。
结论先行：**紫边的主因是成片 JPEG 的 4:2:0 色度下采样（编码域），不是渲染链**；
已实现 4:4:4 编码路径并**默认开启**。

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 紫边归因 | **4:2:0 编码**：在中性强亮度边缘处贡献了色度误差的 **83.8%（p99）/ 94.6%（max）** |
| 渲染链贡献 | 高质量去马赛克（DCB/AHD）下边缘色度与平坦区同量级；双线性去马赛克会额外引入（比 4:2:0 小一个数量级） |
| 硬侧证 | 同机同量化表：PhotonCamera 成片 SOF0 = **4:4:4**，FilmKit 成片 = **4:2:0** |
| 选定路径 | native **stb_image_write**（v1.16，MIT/Public Domain）直编 4:4:4，q>90 时输出 4:4:4 |
| 默认行为 | **切成 4:4:4**（prefs `jpeg_444` 默认 true；设置页可切回） |
| 体积 | 4:4:4 q100 = 现状 4:2:0 q100 的 **1.81×**（6 个真实渲染样本 1.69–2.00×） |
| 耗时（host M1） | libjpeg 4:2:0 q100 56ms → stb 4:4:4 q100 **338ms**（+282ms；真机由 `photo.encode` 日志实测） |
| 质量 | stb 4:4:4 q100 的边缘色度误差 **优于** libjpeg 4:4:4 q100（p99 1.36 vs 1.58），体积还小 10% |
| APK | `filmkit-0.10.171.apk`（versionCode 310） |

---

## 1. 第 1 步：RAW 离线对照实验

### 1.1 方法与严格性

**输入**：`/tmp/red/shot.dng`（4080×3072，CFA RGGB，black 64/63/64/64，white 1023）——
即用户报告紫边的暗室逆光场景（缩略图已确认：窗框/晾衣杆/衣架/枝条 vs 过曝天空）。

**CPU 链**（`/tmp/jpeg444/exp1_render.py`）：
`libraw 去马赛克（4 种算法对照）→ 相机 WB（DNG AsShotNeutral）→ camera→sRGB CCM（libraw 内嵌 ColorMatrix）
→ 线性显示域 → filmic 式影调 → sRGB OETF → 8bit`

影调两条版本用于稳健性检查：`filmicD8`（log 归一化 + 显示域 8 档展开，接近引擎的 filmic 骨架）、
`hard1ev`（线性 + 1EV，硬截断高光）。去马赛克四条：`linear`（双线性，最差）、`ahd`、`dcb`、外加 AMAZE 不可用（GPL3 pack）。

**严格性来源（这是本实验能下硬结论的原因）**：
三个对比版本由**同一份 8bit RGB 同源**产生，唯一差别是编码方式——
`PNG`（无损，色度不降采样）/ `JPEG 4:4:4`（PIL/libjpeg-turbo）/ `JPEG 4:2:0`（同上）。
于是逐像素差分 `ΔC = hypot(ΔCb, ΔCr)` 精确等于"该编码引入的颜色误差"，无混淆项。

**编码器一致性已核验**：PIL 的 JPEG 编码器底层是 libjpeg-turbo，与 Android `Bitmap.compress`（Skia）**同一个库**；
且 q100 时量化表退化为全 1。实测 app 原生成片（`test_temp/FILMKIT_20261004-153558.jpg`）的 DQT 正是
`T0=(1,1,…,1)`、`T1=(1,1,…,1)`，SOF0 = `(2,2)(1,1)(1,1)` ⇒ **离线 4:2:0 q100 复现的就是 app 成片的编码配置**。

### 1.2 指标定义

```
Cb = −0.168736R − 0.331264G + 0.5B + 128
Cr =  0.5R    − 0.418688G − 0.081312B + 128
chroma_mag = hypot(Cb−128, Cr−128)
ΔC         = hypot(Cb−Cb_PNG, Cr−Cr_PNG)       ← 纯编码误差（无 clip 偏置）
中性强边缘 mask：|∇Y| ≥ P99  且  邻域(半径14px)存在"平坦(|∇Y| ≤ P40)且近中性(chroma_mag < 3)"的区域
```
第一版曾用 `clip((R+B)/2 − G, 0)`，其 `clip` 在差分统计中产生负偏置（420 的 Δ 均值为负），已弃用，
改用上面的无偏量。全部数据：`exp1_results.json` / `exp2_unbiased.json`。

### 1.3 数据（`dcb_filmicD8`，12.5MP，中性强边缘 115,807 px）

| 编码 | 体积 | ΔC mean | ΔC p90 | **ΔC p99** | **ΔC max** |
|---|---|---|---|---|---|
| 参考 = PNG（无损） | — | — | — | — | — |
| **stb 4:4:4 q100** | 13.06 MB | 0.537 | 0.93 | **1.36** | **1.94** |
| libjpeg 4:4:4 q100 | 14.53 MB | 0.540 | 1.01 | 1.58 | 2.67 |
| **libjpeg 4:2:0 q100（= 现状）** | 7.73 MB | 2.305 | 4.48 | **9.77** | **49.17** |
| stb 4:4:4 q95 | 4.28 MB | 1.648 | 3.10 | 5.45 | 15.18 |

跨渲染变体（420 q100 的 ΔC p99）：dcb/filmic 9.77、ahd/filmic 9.08、linear/filmic 24.10、
dcb/hard 21.84 —— 而 444 q100 在**所有**变体下恒为 1.58（全 1 量化表 ⇒ 只剩 DCT 舍入）。

### 1.4 回答：紫边是渲染链还是 4:2:0？

**4:2:0 编码占绝对主导**：
- p99 层面：`(9.77 − 1.58) / 9.77 = 83.8%`
- max 层面：`(49.17 − 2.67) / 49.17 = 94.6%`；对最优 4:4:4（max 1.94）则为 `96.1%`
- mean 层面：`(2.305 − 0.540) / 2.305 = 76.6%`

**渲染链侧**（同一 mask 内的色度幅度 chroma_mag，含场景真实色）：
DCB 6.445 / AHD 6.475 / 双线性 9.705，平坦区 3.556。
⇒ 高质量去马赛克（引擎默认 `demosaic_quality=true` 走 RCD，优于 AHD/DCB）下边缘色度与平坦区同量级；
**双线性去马赛克会额外加 3.26**，但仍比 4:2:0 的 max 误差（49.17）小一个数量级。

**4:2:0 的物理指纹**（与场景无关的独立验证，比值 `R = E1(Cb)/E1(Y)`，E1 = 水平 1px 差分均方）：

| 源 | PNG | 4:4:4 | 4:2:0 |
|---|---|---|---|
| dcb_filmicD8 | 0.410 | 0.432 (1.05×) | **0.097 (0.24×)** |
| ahd_filmicD8 | 0.383 | 0.403 (1.05×) | **0.077 (0.20×)** |
| linear_filmicD8 | 0.914 | 0.908 (0.99×) | **0.141 (0.15×)** |
| dcb_hard1ev | 0.234 | 0.249 (1.06×) | **0.058 (0.25×)** |

4:4:4 编码在任何渲染下都**不改变**色度高频能量（0.99–1.06×），4:2:0 把它砍到 **15–25%**。
这是色度下采样"搬运色度"的直接证据。

### 1.5 结论强度（诚实披露）

**能硬下的结论**：
1. 在**给定渲染结果**上，4:2:0 引入的边缘色度误差是 4:4:4 的 6.2×（p99）/ 25×（max）；该量在 6 个渲染变体上稳定。
2. 4:4:4 编码在 q100 下几乎不引入任何可测色度误差（ΔC p99 ≤ 1.58，即 ±2 LSB 量级）。
3. 4:2:0 的下采样/上采样**不可逆**：已发表的 4:2:0 成片无法通过任何后处理恢复（本实验的差分是"信息已丢"的度量）。

**结论强度的限制**：
- 我的 CPU 链是 libraw + 自建影调，**不是引擎的完整链**（引擎含 MFNR 时域融合、RCD、defringe[47]、
  moire[48]、hr_raw[45]/hl_grecon[49]、look/LUT 等）。因此"**引擎链自身**在窗框边缘贡献多少紫边"
  我**没有直接测到**。能保证的是：**4:2:0 的贡献是在引擎输出之上叠加的、且量级压倒性**，
  并且它与"紫边随半径递减"（LCA 反证）等既往观测一致。
- 结论对**影调曲线形状不敏感**（两条影调下方向一致、量级同阶），但对**渲染噪声/锐化程度敏感**
  （linear/hard 变体把 420 的 p99 从 9.77 推到 24.10）—— 即渲染越"硬"，4:2:0 的串色越明显。

### 1.6 真实成片侧证（不依赖我的渲染链）

`test_temp/` 内成片的 SOF0 逐张解析：

| 文件 | 尺寸 | SOF0 分量采样 | DQT T0[0:4] | 体积 |
|---|---|---|---|---|
| `PhotonCamera_20261004_130807.无.jpg` | 4080×3072 | **(1,1)(1,1)(1,1) = 4:4:4** | (1,1,1,1) | 7.97 MB |
| `FILMKIT_20261003/04-*.jpg`（全部 20+ 张） | 4080×3072 | (2,2)(1,1)(1,1) = 4:2:0 | (1,1,1,1) | 4.69–6.34 MB |

**同机、同量化表、同分辨率，唯一差别是采样因子与色彩链** —— 这解释了用户"PC 少紫边"的观察。
前一次诊断判定"PhotonCamera 的 ChromaticFlow 未接线 ⇒ 不能用它解释 PC 少紫边"，
但**真正的原因不在色彩链，在编码器**：PC 写 4:4:4，FilmKit 写 4:2:0。

**关于 `/tmp/red/*.jpg`（含用户报告的 shot.jpg）**：
它们的 DQT 是**非标准自定义表**（`T0[0:4]=(5,3,4,4)`，尾部 30 而非 99；libjpeg 任何 quality 都产生不出这张表），
尺寸 **1777×2360**（≠ app 成片的 4080×3072）⇒ 判定为**经过二次传输/重编码的副本**（IM 工具一类）。
含义：用户当时看到的紫边里**叠加了那一次更粗的编码**的贡献。但**app 原生成片本身也是 4:2:0**
（上表实证），故本次改动的方向与必要性不变；同时也提示用户后续复现时应直接取相册原图。

---

## 2. 编码路径选择与依据

### 2.1 Android 平台核实（为什么不用平台 API）

| 路径 | 结论 |
|---|---|
| `Bitmap.compress(JPEG, q, out)` | Skia → libjpeg-turbo，`jpeg_set_defaults()` 后不再触碰采样因子 ⇒ **恒 4:2:0**，无公开参数可控 |
| `YuvImage.compressToJpeg` | 同样落到 Skia 编码器 ⇒ 恒 4:2:0 |
| `MediaCodec`（`image/jpeg`） | 输入色彩格式由厂商编码器决定，**输出采样因子无法离线确证**；本任务无法做真机实验 ⇒ 排除 |
| NDK 自带 libjpeg | **实测不存在**：NDK 29.0.14206865 的 sysroot 中没有 `jpeglib.h`，也没有 `libjpeg.so` |
| 系统 `/system/lib64/libjpeg.so` | 非 NDK 公开 API（链接不可用，只能 dlopen + 手抄 struct 布局），脆弱 ⇒ 排除 |
| 无损 JPEG（SOF3） | 体积过大且 **Android BitmapFactory 不解码** ⇒ 排除 |
| 自研编码器 | 项目铁律"禁止引入不必要的重实现"⇒ 用成熟实现 |

### 2.2 选定：stb_image_write v1.16（单头文件 1724 行）

`core/engine/third_party/stb/stb_image_write.h`，sha256 `cbd5f0ad…4a05`。

关键代码（`stbi_write_jpg_core`）：`subsample = quality <= 90 ? 1 : 0`，
SOF0 写 `subsample ? 0x22 : 0x11` ⇒ **q>90 输出 4:4:4**；q100 时 `(YQT*0+50)/100 → clamp 1` ⇒ **全 1 量化表**，
与 Android 现状的表逐项相同。

**许可**：MIT（备选 A）或 Public Domain / Unlicense（备选 B），商用友好，已在文件头/尾保留原文。

### 2.3 stb vs libjpeg-turbo（host M1 实测，4080×3072 真实样片）

| 编码器 | 采样 | q | 体积 | 耗时 | 边缘 ΔC p99 |
|---|---|---|---|---|---|
| libjpeg-turbo | 4:2:0 | 100 | 7.73 MB | **56.4 ms** | 9.77 |
| libjpeg-turbo | 4:4:4 | 100 | 14.53 MB | **99.8 ms** | 1.58 |
| libjpeg-turbo | 4:4:4 | 100 + optimize | 13.65 MB | 309.7 ms | — |
| **stb（选定）** | **4:4:4** | **100** | **13.06 MB** | **337.9 ms** | **1.36** |
| stb | 4:4:4 | 95 | 4.28 MB | 169.1 ms | 5.45 |

**选 stb 而不是 vendor libjpeg-turbo 的理由**：
1. **质量不降反升**（ΔC p99 1.36 < 1.58，max 1.94 < 2.67），体积还小 10%；
2. **零构建风险**：无 CMake 子项目、无 nasm（x86_64 ABI 也要构建）、不影响现有 22s 的构建；
   native 新增目标文件仅 **47,960 B**（arm64），而 vendor libjpeg-turbo 需 ~10MB 源码 + ~400KB .so；
3. 代价是**速度**（337.9ms vs 99.8ms，host M1）。这一项**没有拍脑袋**：真机耗时由日志实测决定
   （见 §5 真机判据）。若真机实测不可接受，升级路径明确 —— 换成 libjpeg-turbo 的 `add_subdirectory`
   并把 `jpeg_mem_dest` + `comp_info[0].h/v_samp_factor=1` 接进同一个 JNI 函数即可（接口不变）。

### 2.4 为什么开关放 prefs 而不是 FilmParams

`FilmParams.kCount = 51`，索引 29 是项目约定"留空不复用"。JPEG 编码发生在 **Java 侧**
（`CameraController.saveJpeg`），渲染器根本读不到该位；塞进 FilmParams 只会让 kCount 变 52、
并触碰历史上出过真机 AIOOBE 的"`CameraPresets.neutral()` 数组长度必须与 kCount 同步"硬契约。
故用 prefs（与既有 `jpeg_quality` 同族），**kCount 与 `DoubleArray(51)` 均未改动**
（已复核两者当前同步：`CameraPresets.kt:24 DoubleArray(51)` ↔ `FilmParams.h:14 kCount = 51`）。

---

## 3. 改动坐标（函数 + 行号 + grep 串）

| 文件 | 位置 | grep 串 |
|---|---|---|
| `core/engine/third_party/stb/stb_image_write.h` | 新增（vendor，1724 行） | `stb_image_write - v1.16 - public domain` |
| `core/engine/src/jni/JpegEncoder.cpp` | 新增 | `Java_com_filmkit_app_EngineBridge_nativeEncodeJpeg444` |
| `core/engine/CMakeLists.txt:13` | `add_library` 源列表 | `src/jni/JpegEncoder.cpp` |
| `core/engine/CMakeLists.txt:26-29` | link jnigraphics | `find_library(jnigraphics-lib jnigraphics)` |
| `app/.../EngineBridge.kt:193` | `external fun` 声明 | `nativeEncodeJpeg444` |
| `app/.../camera/CameraController.kt:3212-3235` | `saveJpeg()` 内：读 prefs + 预编码 + 日志 | `val jpeg444 = prefs.getBoolean` / `"photo.encode"` |
| `app/.../camera/CameraController.kt:3247-3250` | `saveJpeg()` 内：`writeJpeg` 落盘闭包 | `val writeJpeg:` |
| `app/.../camera/CameraController.kt:3266 / 3277` | 两个落盘分支（API 29+ / 旧） | `writeJpeg(it)` |
| `app/.../ui/SettingsScreen.kt:135-150` | 设置页开关 | `成片 4:4:4 色度` |
| `app/build.gradle.kts:87-88` | versionCode 310 / versionName 0.10.171 | `versionCode = 310` |

**实现要点**：
- `nativeEncodeJpeg444(bitmap, quality)`：`AndroidBitmap_lockPixels` **零拷贝**读 ARGB_8888 像素，
  stb 以 `comp=4`（RGBA，自动跳过 alpha）直编；`stride != width*4` 时逐行压紧后再编。
  返回 `null` = 编码失败，**调用方必须回退 `Bitmap.compress`**（丢成片比退到 4:2:0 严重）。
- Java 侧：`runCatching { nativeEncodeJpeg444(...) }.getOrNull()`，兜住 `UnsatisfiedLinkError` 等一切异常；
  编码**只做一次**，两个落盘分支共用同一份字节流。
- `quality < 91` 在 native 侧钳到 91（stb 在 ≤90 会退回 4:2:0，本函数承诺 4:4:4）。
- 未触碰色彩链 / `FrameRenderer.cpp` / `FilmParams`；全部改动为精确 `edit`，无文件全量重写。

**未改动的确认**：`grep -n "compress(Bitmap.CompressFormat.JPEG" CameraController.kt`
只剩 `writeJpeg` 闭包内那一处 fallback（原 3236/3247 两处已收敛为共用闭包）。

---

## 4. 体积与耗时实测

### 4.1 体积（12.5MP，同一份 RAW 渲染源，只有编码不同）

| 渲染样本 | 4:2:0 q100（现状） | stb 4:4:4 q100 | 倍率 | stb 4:4:4 q95 | 倍率 |
|---|---|---|---|---|---|
| dcb_filmicD8 | 7.73 MB | 13.06 MB | 1.689× | 4.28 MB | 0.553× |
| ahd_filmicD8 | 7.96 MB | 13.93 MB | 1.749× | 4.39 MB | 0.551× |
| linear_filmicD8 | 7.28 MB | 14.07 MB | 1.931× | 4.16 MB | 0.571× |
| dcb_hard1ev | 8.83 MB | 15.19 MB | 1.720× | 5.32 MB | 0.602× |
| ahd_hard1ev | 9.10 MB | 16.36 MB | 1.797× | 5.45 MB | 0.598× |
| linear_hard1ev | 8.27 MB | 16.56 MB | 2.002× | 5.29 MB | 0.639× |
| **均值** | — | — | **1.81×** | — | **0.59×** |

**真实成片重编码（下界，仅供参考）**：app 成片 4.69–6.34 MB → 4:4:4 重编码 6.07–8.18 MB，
倍率 **1.278–1.295×**。这是**下界**：原图已经被 4:2:0 平滑过，色度更简单 ⇒ 编码后偏小。
**预估真机成片：4:2:0 的 6.3MB → 4:4:4 的约 11MB。**

### 4.2 耗时（host：Apple M1，4080×3072；同一份数据、取 5 次最小值）

| 编码 | q | 耗时 |
|---|---|---|
| libjpeg-turbo 4:2:0（≈ 现状路径） | 100 | 56.4 ms |
| libjpeg-turbo 4:4:4 | 100 | 99.8 ms |
| **stb 4:4:4（选定实现，RGBA comp=4）** | 100 | **337.9 ms** |
| stb 4:4:4 | 95 | 169.1 ms |

**取舍必须由实测拍板，这里给三个选项**：

| 选项 | 体积 | 边缘 ΔC p99 | 耗时 | 评价 |
|---|---|---|---|---|
| **A. 4:4:4 q100（当前默认）** | 1.81× | **1.36** | 338ms (host) | 紫边消除最彻底；体积最大 |
| B. 4:4:4 q95 | **0.59×**（比现状还小 41%） | 5.45 | 169ms | 体积反而下降，但 q95 的量化自带色度误差（改善仅 ~44%）；且项目历史 v0.10.68 记录 q95 会让 dither 被 DCT 滤掉 ⇒ 边缘色带回归 |
| C. 保持 4:2:0（关开关） | 1.00× | 9.77 | 现状 | 紫边保留 |
| D.（未实现，备选）libjpeg-turbo 4:4:4 q100 | 1.88× | 1.58 | 100ms (host) | 若真机嫌 A 慢，换编码器即可，接口不变 |

> 结论按项目铁律"质量优先"取 A 为默认；体积/耗时的最终取舍留给用户用真机日志数据拍板。

### 4.3 构建产物

- clean 构建：`export JAVA_HOME=/opt/homebrew/opt/openjdk@23 && ./gradlew clean :app:buildFilmEngine :app:assembleDebug` → **BUILD SUCCESSFUL**
- native 新增目标文件 `JpegEncoder.o`（arm64, -O3）= **47,960 B**
- APK：`filmkit-0.10.170.apk` 40,259,356 B → `filmkit-0.10.171.apk` **40,472,348 B**（+212,992 B ≈ +0.21 MB；
  该数字含并发任务在同一工作区的改动，native 侧净增以 47,960 B 为准）
- 符号已核验：`llvm-nm -D libfilmengine.so` → `T Java_com_filmkit_app_EngineBridge_nativeEncodeJpeg444`

---

## 5. 真机判据（用户跑一次即可判定）

1. **编码是否真的走了 4:4:4**：日志事件 `photo.encode`
   ```
   {"chroma":"444","q":100,"ms":NNN,"bytes":NNNNNNN}
   ```
   - `chroma":"444"` + `bytes > 0` ⇒ native 路径生效；
   - `chroma":"420"` ⇒ native 返回了 null（**回退发生了**，需查 logcat），此时成片仍是 4:2:0；
   - `ms` = **编码耗时实测**（这是拍板 §4.2 选项的唯一依据，请把它贴回来）。

2. **成片文件的采样因子**（不依赖日志的独立判据）：
   ```
   python3 - <<'EOF'
   import struct,sys
   d=open(sys.argv[1],'rb').read(); i=2
   while i<len(d)-1:
       if d[i]!=0xFF: i+=1; continue
       m=d[i+1]
       if m in (0xD8,0xD9) or 0xD0<=m<=0xD7: i+=2; continue
       L=struct.unpack('>H',d[i+2:i+4])[0]; s=d[i+4:i+2+L]
       if m in (0xC0,0xC1,0xC2):
           print(hex(m),[ (s[7+c*3]>>4, s[7+c*3]&15) for c in range(s[5])]); break
       i+=2+L
   EOF
   ```
   期望 `[(1,1),(1,1),(1,1)]`（4:4:4）；`[(2,2),(1,1),(1,1)]` = 仍是 4:2:0。

3. **紫边是否消失**：同一机位重拍"暗室逆光窗框/晾衣杆/枝条 vs 天空"，
   放大 200% 看边缘两侧是否还有紫/绿镶边；建议开/关开关各拍一张同机位 A/B。

4. **体积**：成片应从约 6MB 涨到约 11MB（1.81×）。

5. **回退安全性**：`photo.save_failed` 不应出现；若出现说明编码异常且 fallback 也失败（另有 bug）。

---

## 6. 无法判定的部分（明确列出）

1. **引擎完整渲染链自身的紫边贡献未测**。我离线复现的是 libraw + 自建影调，不是引擎的
   MFNR/RCD/defringe/moire/LUT 全链。结论"4:2:0 是主因"建立在
   "4:2:0 在任一渲染结果之上叠加，且其量级远大于高质量去马赛克引入的假色"这一可测事实上；
   **引擎链在窗框边缘具体贡献几个 LSB 色度，本次没有数据**。
2. **真机编码耗时未实测**（用户明确没空做真机实验）。host M1 = 338ms；手机（天玑 9500）单核与 M1 同级，
   但 AOSP 的 Bitmap/JNI 开销未知 ⇒ 真机可能 200ms–1.5s。**必须靠 `photo.encode` 日志确认**。
   若真机 >800ms，建议切 §4.2 选项 D（libjpeg-turbo）。
3. **真机 Bitmap 的 stride 分支未验证**。常规 `Bitmap.createBitmap(w,h,ARGB_8888)` 与
   `createBitmap(src,x,y,w,h)` 的 stride 应为 `width*4`（走零拷贝直编）；异常时的逐行压紧分支
   只在代码上存在，真机未触发过。
4. **`MediaCodec image/jpeg` 路径未评估**：其输出采样因子与输入色彩格式均依赖厂商实现，
   无法离线确证，故整条排除（若将来要评估，判据同 §5.2）。
5. **`E1(Cb)/E1(Y)` 指纹不能跨场景使用**。真实成片测得 PC(4:4:4) 0.0127 vs FilmKit(4:2:0) 0.0147–0.0167，
   **方向与离线结论相反** —— 因为两张图是不同的场景（E1_Y 31.3 vs 25.7–28.5），
   场景内容对该比值的影响大于编码。该指纹只在**同内容**对照时有效（离线部分：4:4:4 保持 0.99–1.06×，
   4:2:0 掉到 0.15–0.25×）。判定 PC/FilmKit 的差别请直接用 SOF0（§1.6），不要用指纹。
6. **`/tmp/red/*.jpg` 的真实来源只能推断**（DQT 非标准 + 尺寸与 app 成片不符 ⇒ 二次重编码副本），
   无法确定具体经过哪个工具。
7. **q95 4:4:4 的边缘色带风险未复测**。项目 v0.10.68 记录过 q95 让 dither 被 DCT 滤掉导致色带，
   本次没有重测该现象，故选项 B 未设为默认。

---

## 附：可复核产物

| 路径 | 内容 |
|---|---|
| `/tmp/jpeg444/exp1_render.py` | RAW → 线性 sRGB 渲染（4 种去马赛克） |
| `/tmp/jpeg444/exp1_compare.py` / `exp1_results.json` | 首版对照（clip 指标，含体积/耗时） |
| `/tmp/jpeg444/exp2_unbiased.py` / `exp2_unbiased.json` | 无偏 ΔC 归因 + 中性边缘 mask |
| `/tmp/jpeg444/exp3_fingerprint.py` / `exp3.json` | 4:2:0 频域指纹 + 全样本体积 |
| `/tmp/jpeg444/bench_jpeg.c` | libjpeg vs stb 编码基准 |
| `/tmp/jpeg444/rgba_check.c` | **RGBA(comp=4) 与 RGB(comp=3) 输出逐字节相同**的验证（JNI 调用方式正确性） |
| `/tmp/jpeg444/out/*.png / *.jpg` | 对照实验的全部图像 |
| `/tmp/jpeg444/device_qtables.json` | `/tmp/red/*.jpg` 的自定义量化表 |
