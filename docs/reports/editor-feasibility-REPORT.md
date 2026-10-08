# 内建编辑器与胶片预设 · 技术可行性勘察报告

- **勘察日期**：2026-10-08
- **勘察性质**：**只读**（未修改任何源码；临时统计脚本仅在 shell 内联执行，未落盘到仓库）
- **代码基线**：`app` versionName 0.10.206（`filmkit-logs-20261008-152327.jsonl.txt` 的 `app.start.version`）
- **实测数据来源**：`~/Downloads/vivo办公套件/filmkit-*.log` + `filmkit-logs-*.jsonl.txt`（60MB，真机 X300 导出日志）
- **关联调研**：`docs/research/10-内建编辑器与胶片预设.md`

---

## 1. 结论摘要

| # | 问题 | 一句话结论 | 状态 |
|---|---|---|---|
| A1 | 哪个入口最接近"输入已渲染 sRGB 图→只重跑后段" | `nativeEngineSubmitStill`（Bridge.cpp:618）最接近，但**不能直接用**——它会重跑整条 filmic 影调链（双重映射）并做域比归一 | ⚠️ 需新增 |
| A2 | 新增"RGBA8→风格化后段→输出"通路要动什么 | 4 个文件、约 120–150 行新增；`kFsOut` 的 LUT/颗粒/dither 段（FrameRenderer.cpp:4416-4442/4548-4559）可直接复用 | ✅ 可行 |
| A3 | 后段（OETF 之后）是否已封装成独立 pass | **没有**。LUT+颗粒+dither 与 OETF/锐化同处 `kFsOut` 一个 fragment shader 的 `main()`（4539-4571），只能整体调用 | ⚠️ 需改 shader |
| B4 | `g_preview_scale` 语义与耗时日志字段 | 只影响**预览链**（唯一读取点 submitFrame:6338），拍照降载用；耗时字段 = `render.single_done.ms` / `render.still_done.ms` / `raw.render.{uploadMs,passMs,totalMs}` | ✅ 已验证 |
| B5 | 全链渲染耗时基线 | 预览 1216×1621 = **33.3 ms/帧**（n=5725, p50 30fps）；全分辨率 4080×3072 单帧 RAW = **passMs p50 502 ms**（bilinear）/ **1088 ms**（RCD） | ✅ 实测 |
| B6 | 只重跑"LUT 之后"的耗时量级 | 全分辨率下 `passOutput`+读回+诊断 的**上界 p50 = 55.2 ms**（n=174）；预览分辨率下必然 < 33 ms | ✅ 实测（上界） |
| C7 | 成片参数快照是否持久化 | **完全没有**。`saveJpeg` 的参数表里根本没有 filmParams；唯一 per-photo 痕迹是文件名里的 LUT 名后缀 | ❌ 需新增 |
| C8 | DNG 通路与反查 | **已实现**（saveDng:3272-3326，`FILMKIT_yyyyMMdd-HHmmss.dng`）；但与 JPEG **不同名**（实测时间戳差 0–6 s），只能启发式反查 | ⚠️ 有局限 |
| D9 | 胶卷位当前实现 | `CameraScreen.kt:1243-1263` 的 Box **无任何 clickable/onTap**；`photoUri` 是 `remember` 内存态，**不持久化**，冷启动即丢 | ❌ 需新增 |
| D10 | MediaStore 读取用例与权限 | 用例齐全（`loadThumbnail`/`openInputStream`/`BitmapFactory`/`OpenDocument`）；Manifest **只有 CAMERA 一条权限**，读自己写入的 URI 免权限，读别人图片需新增 | ⚠️ 部分需新增 |
| D11 | `Pictures/FilmKit` 实际文件名 | **路径不可读**（CloudStorage 只有 `OPPO互联-DeviceSpace`，无 vivo X300 挂载点）；改用日志 `photo.saved` 的 name 字段取到 1369 条真实文件名 | ⚠️ 未验证（有替代证据） |
| E12 | 7 组参数是否真正生效 | `split_warm/split_cool/monochrome/grain_amount/grain_size` **生效**；`shoulder[3]/toe[3]` **解析但零引用（死参数）**。默认值全部 0.0 | ✅ 已定论 |
| E13 | 颗粒调制曲线 | `w = 4·l·(1−l)` **抛物线，中灰最强、两端归零**——与"暗部重亮部轻"的负片密度关系**相反**；且用帧计数当种子（不可复现） | ✅ 已定论 |
| E14 | 暗角 / halation 是否存在 | **两者都不存在**（代码零实现，仅注释提及）。存在的是**反向**的 LSC（消除暗角）和两个高光重建模块（非 halation） | ❌ 需新增 |
| F15 | 预设机制是死是活 | **半活**：UI 与调用链完整（4 处引用），但 `names` 只有 1 个元素且 `byIndex(i)` **忽略参数**恒返回中性基线 | ⚠️ 有骨架 |
| F16 | prefs 机制能否存预设列表 | `JsonKv.kt` **不是** KV 存储（只是日志 JSON 构造器）；真 prefs = `getSharedPreferences("filmkit", 0)`，**能存 JSON 字符串**（有现成用例），不支持数组 | ✅ 可用 |

**总体判断**：**可行性高，无架构级阻塞**。基础设施（RGB8 纹理上传 → 多 pass → RGBA8 读回、LUT 上传、prefs、MediaStore 写入）全部现成；主要工作是**新增一条"显示域输入"的渲染通路** + **新增参数快照持久化** + **新增 3 个胶片效果**（halation/暗角/颗粒密度调制）。

---

## 2. 逐条详细答案

### A. 编辑器能不能从"已保存的成片 JPEG"重跑风格化链？

#### A1. 现有渲染入口全清单与输入域

全部 JNI 入口（声明见 `app/src/main/java/com/filmkit/app/EngineBridge.kt`）：

| JNI 函数 | 定义位置 | 输入域 | 输出 |
|---|---|---|---|
| `nativeEngineSubmitST` | `core/engine/src/jni/Bridge.cpp:466-530` | **OES 外部纹理**（相机预览流，HAL 显示域） | 窗口（不读回） |
| `nativeEngineSubmitStill` | `Bridge.cpp:618-650` | **YUV 8bit**（NV21/NV12，HAL 显示域）→ CPU 转 RGB888 → `GL_RGB8` 纹理 | RGBA8 `jbyteArray` |
| `nativeEngineSubmitRaw` | `Bridge.cpp:659-735` | **u16 Bayer RAW**（单帧，传感器线性域） | RGBA8 |
| `nativeEngineSubmitRawBurst` | `Bridge.cpp:742-835` | **u16 Bayer RAW ×N**（多帧，传感器线性域） | RGBA8 |
| `nativeEngineHistogram` | `Bridge.cpp:836-847` | 当前 `tex_work_`（显示域线性） | `IntArray` |
| `nativeEncodeJpeg444` | `core/engine/src/jni/JpegEncoder.cpp` | `Bitmap` | `ByteArray?` |

**哪一个最接近"输入一张已渲染好的 sRGB 图，只重跑后段"？→ `nativeEngineSubmitStill`**，理由（这是本次勘察最关键的一条）：

- 它的 pass1 是 `kFsStill`（`FrameRenderer.cpp:223-234`），**输入就是一张 8bit RGB 纹理**，且 shader 内做 `pow(rgb, vec3(2.2))` 反 gamma —— 即"已编码图片 → 近似线性"的展开，**完全跳过 RAW 前段**（无 P0/去马赛克/LSC/CCM）。
- 上传路径也现成：`submitStill` 内 `still_tex_`（`GL_RGB8`，`FrameRenderer.cpp:6449-6463` 建纹理、6500-6504 `glTexSubImage2D` 上传）。
- 输出路径也现成：`passOutput(..., flip_y=true, zebra=false)` → `fbo_cap_` → `glReadPixels` RGBA8（`6533-6536`）。

**但不能直接复用**，有两处会破坏成片（关键风险，编辑器立项必须知道）：

1. **双重影调映射**：`submitStill` 在 pass1 之后调 `passFilm(..., still=true, hal_domain=true)`（`6520`），而 `passFilm` 会跑完整的 filmic 曲线（`kFsFilm`，`FrameRenderer.cpp:3946-4392`）、gamut、分区饱和、split、look、mono。对一张**已经过 filmic + OETF 的成片**再跑一遍 ⇒ 画面显著变暗、对比度被二次压缩。
2. **错误的域比归一**：`hal_domain=true` 会让 `kFsFilm` 把输入当作 HAL 显示域，除以在线估计的 `uDomainRatio`（`FrameRenderer.cpp:4287`，实测范围 1.2–4.5，见 `11422`）⇒ 亮度整体漂移。

**结论**：`submitStill` 证明了"图片进出引擎"的**全部基础设施都在**，但它的**后段起点选错了**。编辑器需要的是从 `kFsOut` 的 `srgbEncode` **之后**接管。

#### A2. 新增"RGBA8/RGBA16F 图像 → 风格化后段 → 输出"通路的改动清单

**管线切点（精确定位）**：`kFsOut` 的 `main()`（`FrameRenderer.cpp:4539-4571`）执行顺序为

```
4542  c = texture(uTex, uv).rgb          // 输入 = tex_work_（显示域线性 RGBA16F）
4543  c = highlightRolloff(c)
4544  if (uSharpen > 0.0) sharpenLuma(c, uv)
4545  c = srgbEncode(c)                   ← ★ OETF 分界线
4547  if (uLutOn > 0.5) c = mix(c, lutApply(c), uLutStrength)   // 3D LUT
4548-4553  颗粒
4559       TPDF dither
4570  oColor = vec4(clamp(c, 0.0, 1.0), 1.0)
```

**一张 JPEG 的像素值 = 第 4545 行之后的 `c`**（sRGB 编码、8bit）。所以编辑器要重跑的正是 **4547–4559**，跳过的正是 **4543–4545**。

**可复用的现成 shader 片段**（全部在 `FrameRenderer.cpp`）：

| 复用项 | 位置 | 说明 |
|---|---|---|
| 3D LUT 四面体插值 `lutTetra` | `4416-4426` | 8×`texelFetch` + 四面体权重，直接拷 |
| `lutApply` | `4427-4442` | DOMAIN_MIN/INV/SIZE 归一 + 采样 |
| LUT 混合 | `4547` | `mix(c, lutApply(c), uLutStrength)` |
| 颗粒 `vnoise`/`hash` | `4443-4458` | 1 次 `vnoise`（内含 4 次 `hash`） |
| 颗粒调制 | `4548-4553` | 见 E13 |
| dither `dhash` | `4447-4451` + `4559` | TPDF，2 次 `dhash` |
| `srgbEncode`（反向需要） | `4533-4538` | 若输入 RGBA16F 线性则直接跳过 |

**需改动的文件与工作量**：

| # | 文件 | 改动 | 估算 |
|---|---|---|---|
| 1 | `core/engine/src/render/FrameRenderer.cpp` | ① `kFsOut` 加 `uniform float uInDisplay;` + `main()` 开头包一层 `if (uInDisplay < 0.5) { 滚降/锐化/OETF }`（约 6 行）<br>② `passOutput`（11527）加 `bool in_display` 形参 + 1 次 `glUniform1f`（约 5 行）<br>③ 新增 `submitDisplay(const void* rgba8, int w, int h, const FilmParams&, std::vector<uint8_t>*)`：复用 `still_tex_` 上传（拷贝 6449-6463 建纹理 + 6500-6504 上传，约 40 行）+ `ensureTargets` + `passOutput(in_display=true)` + `glReadPixels`（约 15 行） | **~70 行** |
| 2 | `core/engine/src/render/FrameRenderer.h` | 在 `submitStill` 声明（166）旁加 `submitDisplay` 声明 | **~4 行** |
| 3 | `core/engine/src/jni/Bridge.cpp` | 新增 `nativeEngineSubmitDisplay`：`paramsFromJava` + `GetDirectBufferAddress` + `submitDisplay` + `rgbaToJavaArray`（可整段照抄 618-650 的骨架）+ 一条 `render.single_done` 风格日志 | **~35 行** |
| 4 | `app/src/main/java/com/filmkit/app/EngineBridge.kt` | 新增 `external fun nativeEngineSubmitDisplay(...)`（在 81 行 `nativeEngineSubmitStill` 旁） | **~8 行** |

**合计约 120 行新增、4 个文件，无删除、无契约变更**（`FilmParams.kCount = 60` 不动）。

**备选（更保守，推荐用于首次落地）**：不修改 `kFsOut`，而是**新增一个 `kFsStyle` shader**（只含 4547–4559 三段，从 `kFsOut` 拷），新建 `prog_style_` + `passStyle()`。优点：成片路径**零回归风险**（`kFsOut` 一个字节都不动）；缺点：多一个 program、LUT 采样代码重复一份（约 35 行）。考虑到本项目"成片质量唯一优先级 + 严禁回归"的铁律（`AGENTS.md`），**建议走这条**。

#### A3. 后段 pass 是否已封装成独立函数、能否单独调用

**两个独立函数，但后段没有独立成 pass**：

- `FrameRenderer::passFilm(int w, int h, const FilmParams& p, GLuint src_tex, bool still, bool hal_domain)` — `FrameRenderer.cpp:11379-11525`。**是独立函数**，但内部含 `updateFilmicEV`、HR 小波、局部色调图渲染、`kFsFilm` 一整个 pass。
- `FrameRenderer::passOutput(int w, int h, const FilmParams& p, bool flip_y, bool zebra)` — `FrameRenderer.cpp:11527-11553`。**是独立函数**，且只依赖 `tex_work_` + `p` + LUT 状态，**理论上可以单独调用**。

**但"OETF 之后的段"（3D LUT + 颗粒 + dither）并没有被拆成独立 pass** —— 它们与 `highlightRolloff`、`sharpenLuma`、`srgbEncode` **共用同一个 fragment shader `kFsOut` 的同一个 `main()`**（4539-4571），中间没有 FBO 往返。

**所以**：
- ❌ 不能在不改 shader 的前提下"只跑 LUT+颗粒"。
- ✅ 但改动极小：`kFsOut` 的 `main()` 已经是线性顺序，插一个 `if (uInDisplay < 0.5) { ... }` 包住前三步即可（见 A2）。
- ✅ `passOutput` 的函数签名与 uniform 下发是干净的，加一个形参不会波及任何现有调用点（现有 3 处调用：`6365`、`6384`、`6533`、`8841`）。

---

### B. 性能基线

#### B4. `nativeEngineSetPreviewScale` / `g_preview_scale` 的作用范围与语义

**定义与语义**：

| 项 | 位置 | 内容 |
|---|---|---|
| 进程级 atomic | `FrameRenderer.cpp:55` | `std::atomic<float> g_preview_scale{1.0f};` |
| setter | `FrameRenderer.cpp:6306-6308` | `setPreviewScale(float s)` → `clamp(s, 0.25f, 1.0f)` |
| getter | `FrameRenderer.cpp:6310-6312` | `previewScale()` |
| **唯一读取点** | `FrameRenderer.cpp:6338` | 在 `submitFrame` 内：`const float s = previewScale();` |
| JNI | `Bridge.cpp:550-554` | 只调 `FrameRenderer::setPreviewScale(scale)` |
| Kotlin 下发 | `CameraController.kt:1835` → `RenderClient.kt:211` → `EngineBridge.kt:162` | `previewScaleCapture = 0.5f`（`CameraController.kt:1831`），带 15 s 强制回升守护（`1836-1848`） |

**取值语义**：`s ∈ [0.25, 1.0]`，默认 `1.0`。`submitFrame` 内（`6338-6341`）：

```cpp
const float s = previewScale();
const int rw = max(1, lround(dst_w * s));
const int rh = max(1, lround(dst_h * s));
const bool scaled = rw != dst_w || rh != dst_h;
```

即把**预览链的 pass1/pass2/pass3 中间渲染**缩到 `s×`（`0.5 → 1/4 像素数`），最后 `glBlitFramebuffer` 线性升到全窗口（`6364-6372`）。`scale=1` 时走原路径（零改动）。

**作用范围（关键）**：**只影响预览链，完全不影响成片链**。三处独立证据：
- `Bridge.cpp:549` 注释："成片链不经 submitFrame，画质不受影响"
- `FrameRenderer.cpp:6337` 注释："成片链不经此函数"
- 静态验证：`g_preview_scale` 全仓**只有 6338 一个读取点**，而 `submitStill`(6442)/`submitRaw`(8209)/`submitRawBurst`(10266) 都不经过 `submitFrame`。

**耗时日志字段**：

| 事件 | 打点位置 | 字段 | 语义 |
|---|---|---|---|
| `render.single_done` | `Bridge.cpp:641-647`（YUV）/ `726-732`（RAW） | `kind`(yuv/raw), `path`(still/preview), **`ms`**, `ok`, `bytes` | 只包住 `r->submitStill/submitRaw` 调用本身；**不含 JPEG 编码** |
| `render.still_done` | `StillRenderClient.kt:245/323/389` | **`ms`**, `queuedMs`, `bytes`, `size` | Kotlin 侧端到端（含排队 + 编码 + 落盘） |
| `raw.render` | `FrameRenderer.cpp:8853+` | **`uploadMs`**(t0→t1), **`passMs`**(t1→t2), **`totalMs`**(t0→t3), 外加 20+ 分段（`dirMs`/`gMs`/`ccmMs`/`dnMs`/`hrMs`/`pc3dMs`/`alignMs`/`fuseMs`/`dftGpu`…） | `passMs` = P0/AMaZE → … → `passFilm` 结束（`t2` 在 8825，**不含 passOutput**）；`totalMs` 含 `passOutput` + `glFinish` + `glReadPixels` + 诊断统计 |
| `render.fps` | 预览帧率 | `fps`（5 个采样逗号分隔） | 预览链实测帧率 |
| `preview.frame` | `Bridge.cpp:523-527` | `frames`, `dstW`, `dstH`, `rs`(=previewScale) | 每 120 帧一条 |
| `render.preview_scale` | `RenderClient.kt:213` | `scale` | 降载下发确认 |
| `photo.encode` | `CameraController.kt:3417-3421` | `chroma`(444/420), `q`, `ms`, `bytes` | JPEG 编码耗时 |

#### B5. 全链渲染耗时实测

**数据源**：`~/Downloads/vivo办公套件/` 下 17 个 `filmkit-engine-*.log` + 60 个 `filmkit-logs-*.jsonl.txt`。

**统计命令**（内联 Python，未落盘）：

```python
import json,glob
rows=[]
for f in glob.glob("filmkit-*.log")+glob.glob("filmkit-logs-*.jsonl.txt"):
    for line in open(f,errors='ignore'):
        if not line.strip().startswith('{'): continue
        try: o=json.loads(line)
        except: continue
        if o.get('event')=='raw.render': rows.append(o['kv'])
# 分位数 = 排序后取 round(p*(n-1)) 下标
```

**① 预览分辨率 1216×1621（`engine.start w=1216 h=1621`）**

| 指标 | 样本数 | p10 | **p50** | p90 | max |
|---|---|---|---|---|---|
| `render.fps` | 5725 | 17 | **30** | 31 | 33 |

⇒ **含 `passOES` + `passFilm`（完整 filmic 全链）+ `passOutput`（OETF+锐化+LUT+颗粒+dither）的预览帧 = 33.3 ms/帧**。
注意：预览链输入是 OES 相机流，**不含** RAW 前段，也不需要 `glReadPixels` 读回。

**② 全分辨率 4080×3072 单帧 RAW → 成片**（`raw.render`，按 `mfnr` 是否为空分组）

| 组 | n | `uploadMs` p50 | **`passMs` p50** | `passMs` p90 | `totalMs` p50 |
|---|---|---|---|---|---|
| 无 MFNR + bilinear | 156 | 3.59 | **502.29** | 765.49 | 559.19 |
| 无 MFNR + RCD | 18 | 3.87 | **1087.90** | 1202.60 | 1250.18 |
| MFNR(dft) + RCD | 226 | 756.35 | **1868.90** | 3380.00 | 3999.05 |
| MFNR(dft) + bilinear | 276 | 773.58 | **1577.00** | 2964.90 | 3924.49 |

**③ 全分辨率 YUV → 成片**（`render.single_done` kind=yuv path=still，`bytes=50331648` ⇒ 4096×3072）

| n | min | **p50** | p90 | max |
|---|---|---|---|---|
| 12 | 510 | **1062** | 1677 | 1726 |

⇒ 含 CPU YUV→RGB 转换（12.6 MP）+ `passStill` + `passFilm` + `passOutput` + `glReadPixels`(50 MB)。

**④ 端到端（含排队 + JPEG 编码 + MediaStore 落盘）**：`render.still_done.ms`，**n=504，min=1436，p50=3952，p90=5842，p99=9042，max=19674 ms**（4080×3072）。

**⑤ 分段耗时样例**（`filmkit-engine-20261007.log` 13:55:31 一条真实 `raw.render`，4080×3072，MFNR+RCD）：

```
uploadMs 650.06 | passMs 1285.4 | totalMs 3022.82
dirMs 613.88 | gMs 6.18 | ccmMs 88.91 | dnMs 132.80 | hrMs 409.85
pc3dMs 132.40 | alignMs 650.0 | fuseMs 912.9 | dftGpu 892.9
```

**对编辑器最重要的三条基线**：
1. **预览分辨率（1216×1621）跑完整风格化链 = 33.3 ms** —— 这已经**满足**调研文档 §4.3 提出的"预览分辨率全链 <300 ms"要求（好 9 倍）。
2. **全分辨率（4080×3072）单帧 RAW 全链 = 0.5–1.9 s**（取决于 bilinear/RCD/MFNR）—— 这是"改影调/WB 参数要重跑全链"的真实代价。
3. **只改风格化后段 = 55 ms 上界**（见 B6）—— 与全链差 10–35 倍，**这正是"LUT 之后参数可实时、影调参数需降级预览"的量化依据**。

#### B6. 只重跑"LUT 之后"段落的耗时量级

**方法**：`raw.render` 的 `passMs` 截止于 `passFilm` 结束（`FrameRenderer.cpp:8825` 的 `t2`），而 `totalMs` 到 `t3`（8849，含 `passOutput` + `glFinish` + `glReadPixels` + 两个诊断统计）。故

```
残差 = totalMs − uploadMs − passMs
     = passOutput(OETF+锐化+LUT+颗粒+dither) + glFinish + glReadPixels(50MB)
       + diagStatsTex("FILM-out") + diagStatsU8("D5-out-u8", 12.5M px CPU 统计)
```

**实测**（单帧 RAW，4080×3072，无 MFNR，n=174）：

| min | **p50** | p90 | max |
|---|---|---|---|
| 24.4 | **55.2** | 191.7 | 314.1 ms |

⇒ **全分辨率下，"LUT+颗粒+dither+编码读回"整段的耗时上界 = 55 ms（中位）**，且其中还包含 50 MB 的 `glReadPixels` 与 12.5 M 像素的 CPU 诊断统计（`g_diag_stats{true}` 默认开，`FrameRenderer.cpp:4769`）⇒ **纯 `passOutput` 的 GPU 时间显著低于 55 ms**。

**从 shader 复杂度交叉验证**：
- LUT：8 次 `texelFetch` + 四面体插值（`4416-4442`）
- 颗粒：1 次 `vnoise`（内部 4 次 `hash`，每次 1 个 `sin`）+ 1 次 `luma`（`4452-4458`, `4548-4553`）
- dither：2 次 `dhash`（无 `sin`，纯 fract/dot，`4447-4451`, `4559`）
- **总计 1 个全屏 pass，无邻域采样、无循环、无多 pass 往返** —— 与 `passFilm`（含 filmic spline + gamut + 手写双线性局部图）相比是"轻量级"。

**从预览数据交叉验证**：预览分辨率下**整条链**（OES + filmic + OETF + LUT + 颗粒 + dither）只要 33.3 ms ⇒ 只跑后段必然 **< 33 ms**。

**结论**：
- 预览分辨率（1216×1621）重跑后段：**预计 < 10 ms 量级**（未单独实测，标注为估计）
- 全分辨率（4080×3072）重跑后段 + 读回：**p50 ≈ 55 ms**
- **两者都足以支撑"拖动滑杆实时预览"**。

---

### C. 成片参数快照

#### C7. 成片的 FilmParams / LUT / 强度有没有被持久化？

**结论：完全没有。** 逐条证据：

**① 保存链路**（`CameraController.kt`）：

```
queueJpegSave(bytes, w, h, extraTag)          3332-3340
  ├─ val iso = liveIso                        3333   ← 只快照了 ISO
  ├─ val secNs = liveExposureNs               3334   ← 只快照了快门
  └─ saver.execute { saveJpeg(bytes, w, h, iso, secNs, extraTag) }   3339

saveJpeg(rgba, w, h, iso, secNs, extraTag)    3343-3486
  ├─ val prefs = context.getSharedPreferences("filmkit", 0)   3388   ← prefs 在手
  ├─ val name  = "FILMKIT_...${suffix}.jpg"   3430   ← 文件名在手
  ├─ lastPhotoUri = uri                       3449   ← URI 在手
  ├─ writeExif(savedUri, savedFile, now)      3467
  └─ log "photo.saved"                        3468
```

**`filmParams` 数组从未进入这条链路** —— `saveJpeg` 的形参表里没有它，`queueJpegSave` 也没有快照它（只快照 `liveIso` / `liveExposureNs` 两个标量）。

**② prefs 里只有"全局当前选择"，没有"某一张用了什么"**：

| prefs 键 | 写入点 | 性质 |
|---|---|---|
| `lut_id` | `CameraScreen.kt:413`（选 LUT 时）/ `410`、`1354`、`1373`（清除时） | **全局当前值**，非 per-photo |
| `lut_strength` | `CameraScreen.kt:1343`（滑杆松手时） | **全局当前值** |
| `lut_in_name` | 设置页开关（默认 true） | 控制是否把 LUT 名写进文件名 |
| `base_ev`、`sharpen`、`gamut`、`hr_raw`… | `CameraController.kt` 的 `applyXxxPref` 系列（1069-1400+） | 全部是"当前设置"，非快照 |

**③ 唯一的 per-photo 关联 = 文件名后缀**（`CameraController.kt:3422-3430`）：

```kotlin
val lutTag = currentLut?.title
    ?.takeIf { prefs.getBoolean("lut_in_name", true) }
    ?.replace(Regex("[^\\p{L}\\p{N}_-]"), "")
val suffix = extraTag + if (lutTag.isNullOrEmpty()) "" else "_$lutTag"
val name = "FILMKIT_${SimpleDateFormat("yyyyMMdd-HHmmss").format(now)}$suffix.jpg"
```

即成片文件名可能形如 `FILMKIT_20261008-095952_Portra400.jpg` —— **只能反推 LUT 名字，反推不出 60 项参数与强度**。

**④ FilmParams 的组装与下发**（这决定了快照能否复用同一套逻辑）：

```
CameraController.filmParams (成员, 初值 CameraPresets.standard())     :77
  ← setFilmParams(p)   1033-1050    预设切换时整体替换 + 触发 applyTonePref/applyZebraPref/applyLutStrengthPref
  ← applyBaseEvPref()  1069-        写 [0]
  ← applyTonePref()    1165-        写 [28]
  ← applyLutStrengthPref() 1195-    写 [34]
  ← applyZebraPref()   1221-        写 [30]
  ← applyFilmicRangePrefs() 1257-   写 [37]/[38]
  ← applyAutoTonePref() 1281-       写 [35]
  ← applyLocalTonePref() 1303-      写 [36]
  ← applySharpenPref() 1316-        写 [39]
  ← applyGamutPref()   1329-        写 [40]
  ← applyHrWaveletPref() 1349-      写 [41]
  ...（另有 hr_raw/defringe/moire/ca_corr/chroma_gate/pc3d 等）
filmParamsForStill()  179-186      成片专用：高光保护欠曝量叠加到 [0]
```

**最小改动位置（推荐）**：

| 改动 | 位置 | 说明 |
|---|---|---|
| 快照 | `queueJpegSave`（`CameraController.kt:3332`） | 此处 `filmParamsForStill()` 与 `currentLut` / `lutStrength` 都在作用域内。加一行 `val snap = filmParamsForStill().copyOf()` + `val lutId = currentLut?.id`，作为新形参传下去 |
| 落盘 | `saveJpeg`（`3343`）内，**紧接 `lastPhotoUri = uri`（3449）之后** | 此时 `savedUri`（3456）已确定。写 prefs：`putString("shot_params_$name", json)`，或写一份 `uri→json` 的映射（需限制条数，避免 prefs 无限增长） |
| 更省（推荐先做） | `writeExif`（`3488`）内 | EXIF `TAG_USER_COMMENT` 是**天然的 per-photo 存储**，随文件走、不占 prefs、删除照片即消失。60 个 double 序列化后约 400–700 字符，EXIF UserComment 无硬长度限制（JPEG APP1 段 64KB）。**这是 ponytail 意义上的最省方案** |

**注意**：`lastPhotoUri` 本身**不持久化**（见 D9），所以"编辑器打开最近成片"这件事目前**冷启动就断了** —— 这是 C7 之外必须一并解决的（见第 4 节）。

#### C8. DNG 保存通路

**已实现**，位置 `CameraController.kt:3272-3326`：

```kotlin
private fun saveDng(img: Image, result: TotalCaptureResult, ch: CameraCharacteristics) {
    val creator = DngCreator(ch, result)                    // 3277
    creator.setOrientation(...)                             // 3283-3290
    val name = "FILMKIT_${yyyyMMdd-HHmmss}.dng"             // 3292
    // API≥29: MediaStore 插入, RELATIVE_PATH="Pictures/FilmKit", MIME=image/x-adobe-dng, IS_PENDING 流程
    // API<29: File(getExternalFilesDir(PICTURES), "FilmKit")/name
    creator.writeImage(it, img)                             // 3304 / 3318
    log "dng.saved" { name, out }                           // 3309-3312
}
```

- **触发**：设置页开关 `dng_enabled`（`SettingsScreen.kt:79`，prefs 键 `dng_enabled`，默认 `false`），仅在 M 档（RAW 采集）下生效（`CameraController.kt:51-56`、`2304`）。
- **命名**：`FILMKIT_yyyyMMdd-HHmmss.dng`（到秒，无后缀）。
- **JPEG 命名**：`FILMKIT_yyyyMMdd-HHmmss[_LUT名].jpg`（`3430`）。

**是否成对保存？→ 不是严格成对。** 实测日志（`photo.saved` / `dng.saved`，共 1369 条）中的真实配对样例：

| `dng.saved` | `photo.saved` | 时间戳差 |
|---|---|---|
| `FILMKIT_20261008-095946.dng` | `FILMKIT_20261008-095952.jpg` | **6 s** |
| `FILMKIT_20261008-095957.dng` | `FILMKIT_20261008-100000.jpg` | 3 s |
| `FILMKIT_20261008-100005.dng` | `FILMKIT_20261008-100008.jpg` | 3 s |
| `FILMKIT_20261005-095941.dng` | `FILMKIT_20261005-095941.jpg` | **0 s** |
| `FILMKIT_20261005-100001.dng` | `FILMKIT_20261005-100005.jpg` | 4 s |

根因：`saveDng` 用 **DNG 落盘时刻**的 `Date()`（3291），`saveJpeg` 用 **渲染完成时刻**的 `Date()`（3387），两者相差 = 渲染 + 编码耗时（0.5–6 s）。

**能否从 JPEG 反查同批 DNG？**
- ❌ **不能靠文件名精确匹配**（时间戳通常不同）。
- ⚠️ **可做启发式**：同一 `RELATIVE_PATH`（`Pictures/FilmKit`）+ `DATE_TAKEN` 落在 `[jpegTime − 10s, jpegTime]` 区间内 + 同一拍摄会话。**但这会在连拍时歧义**（相邻两张间隔可能 < 5 s）。
- ❌ 无 MediaStore 关联字段、无 sidecar 文件、无数据库。
- ✅ **正解（需新增）**：在 `saveDng`（3292 附近）与 `saveJpeg`（3449 附近）各写一条**共享的 shot id**（例如把 DNG 的 MediaStore `_ID` 写进 JPEG 的 EXIF UserComment，或反之），即可精确配对。改动约 10 行。

---

### D. 入口与素材访问

#### D9. 主页"胶卷位"缩略图当前实现

**实际代码**（`app/src/main/java/com/filmkit/app/ui/CameraScreen.kt:1243-1263`）：

```kotlin
val t = thumb                                              // 1244
Box(
    Modifier
        .size(44.dp)                                       // 1246
        .clip(RoundedCornerShape(10.dp))
        .background(FilmTheme.ChipBg)
        .alpha(if (t == null) 0.5f else 1f),               // 1249
    contentAlignment = Alignment.Center,
) {
    if (t != null) {
        Image(bitmap = t, contentDescription = "最近样张",  // 1254-1258
              contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Text("▦", color = FilmTheme.Cream, fontSize = 20.sp)  // 1261
    }
}
```

**① 点击有没有回调？→ 没有。** 该 `Box` 的 `Modifier` 链只有 `size / clip / background / alpha`，**无 `.clickable`、无 `.pressScale`、无 `onTap`**（对比同文件 1265-1276 的快门按钮就用了 `.pressScale(onTap = { doCapture() })`）。

**② `photoUri` 从哪来、何时赋值/读取？**

| 项 | 位置 | 内容 |
|---|---|---|
| 定义 | `CameraScreen.kt:293` | `var photoUri by remember { mutableStateOf<android.net.Uri?>(null) }` |
| 读取 | `CameraScreen.kt:512` | `LaunchedEffect(Unit)` 里 400 ms 轮询：`val newUri = controller.lastPhotoUri; if (newUri != photoUri) photoUri = newUri` |
| 缩略图 | `CameraScreen.kt:536-546` | `LaunchedEffect(photoUri)` → `contentResolver.loadThumbnail(uri, Size(128,128), null)` → `thumb = bmp?.asImageBitmap()` |
| 源头 | `CameraController.kt:500` | `var lastPhotoUri: android.net.Uri? = null` |
| 唯一赋值 | `CameraController.kt:3449` | `saveJpeg` 内 MediaStore `insert` 成功后 `lastPhotoUri = uri` |

**③ 是否持久化？→ 不持久化。** 三层证据：
- `CameraController.lastPhotoUri` 是**普通成员 `var`**（500），无 prefs 读写（全仓 grep `lastPhotoUri` 只有 4 处：声明 500、赋值 3449、读取 CameraScreen 512、注释 498）。
- `CameraScreen.photoUri` 用 **`remember`（不是 `rememberSaveable`）**（293）⇒ 进程重建即丢。
- `thumb` 同样是 `remember`（294）。

⇒ **App 冷启动后，胶卷位是空的（显示 "▦" 占位符）**。这与调研文档 §4.1 的"先本 App 最近成片"直接冲突，**必须一并解决**。

**④ 缩略图当前显示什么、怎么加载？** 显示最近一张成片的 **128×128 系统缩略图**（`loadThumbnail`，`540`），非全尺寸解码 —— 内存友好，且**不需要任何读权限**（见 D10）。无 Coil/Glide 依赖，纯 Compose `Image` + `ImageBitmap`。

#### D10. MediaStore 读取用例与权限

**① 现成用例（全部在 `app/src/main`）**：

| API | 位置 | 用途 |
|---|---|---|
| `contentResolver.loadThumbnail(uri, Size(128,128), null)` | `CameraScreen.kt:540` | **读回自己写入的成片缩略图** |
| `contentResolver.openInputStream(uri)` | `LutLibrary.kt:175` | 读 `.cube` LUT 文件 |
| `contentResolver.openInputStream(uri)` | `NoiseCalib.kt:196` | 读噪声标定文件 |
| `BitmapFactory.decodeByteArray` | `LutIO.kt:44-48` | LUT 预览图解码（带 `inSampleSize`） |
| `BitmapFactory.decodeFile` / `decodeStream` | `LutPreview.kt:37` / `57` | LUT 预览图 |
| `ActivityResultContracts.OpenDocument()` | `CameraScreen.kt:344`、`SettingsScreen.kt:937` | **SAF 文件选择器**（导入 LUT），**免权限** |
| `ActivityResultContracts.CreateDocument(...)` | `CameraScreen.kt:368`、`LogScreen.kt:50` | 导出日志 |
| `ActivityResultContracts.RequestPermission()` | `CameraScreen.kt:174` | 相机权限 |

**缺失**：无 `PickVisualMedia`（Android 13+ 照片选择器）、无全尺寸 `BitmapFactory` 解码成片的用例（只有 128×128 `loadThumbnail`）。

**② `AndroidManifest.xml` 权限（`app/src/main/AndroidManifest.xml`）—— 只有一条**：

```xml
4:    <uses-permission android:name="android.permission.CAMERA" />
```

逐条核对：

| 权限 | 是否存在 |
|---|---|
| `CAMERA` | ✅ 有（第 4 行） |
| `READ_MEDIA_IMAGES`（API 33+） | ❌ **无** |
| `READ_EXTERNAL_STORAGE`（API 26–32） | ❌ **无** |
| `WRITE_EXTERNAL_STORAGE` | ❌ **无** |
| `MANAGE_EXTERNAL_STORAGE` | ❌ **无** |
| `READ_MEDIA_VISUAL_USER_SELECTED`（API 34 部分授权） | ❌ **无** |
| `<provider>` / FileProvider | ❌ **无**（grep 无匹配） |

**SDK 版本**（`app/build.gradle.kts:81,85,86`）：`compileSdk = 36`、`minSdk = 26`、`targetSdk = 36`。

**③ 读"App 自己写入 MediaStore 的图片"是否需要读权限？→ 不需要，且本仓库已有实证。**
`CameraScreen.kt:540` 用 `loadThumbnail(uri)` 读回成片，而 Manifest 里**没有任何媒体读权限** —— 在 `targetSdk=36`（分区存储强制）下这能工作，正是 Android 10+ 的语义：**应用对自己插入的 MediaStore 条目拥有完全访问权，无需 `READ_MEDIA_*`**。

**对编辑器的直接含义**：
- ✅ **编辑"App 自己拍的成片" → 零权限新增**（只要 URI 能拿到，见 D9 的持久化缺口）。
- ❌ **"二期接系统相册选择器"（调研文档 §4.1）→ 必须新增权限或改用 SAF**：
  - 方案 A（推荐，免权限）：`ActivityResultContracts.PickVisualMedia`（Android 13+ 原生照片选择器，无需权限）或已有的 `OpenDocument()`（全版本可用，代码里已有用例）。
  - 方案 B：新增 `READ_MEDIA_IMAGES`（API 33+）+ `READ_EXTERNAL_STORAGE`（maxSdkVersion=32），需处理运行时授权 UI。

#### D11. `Pictures/FilmKit` 实际文件名

**结论：路径不可读，本项标注"未验证"。**

尝试过的路径：
- `ls ~/Library/CloudStorage/` → **只有 `OPPO互联-DeviceSpace`**，**没有 vivo X300 挂载点**（与任务描述中的 `文件管理系统-伯牙的vivoX300` 不符）。
- `find ~/Library/CloudStorage/ -maxdepth 6 -type d -name "FilmKit"` → **无结果**
- `find ~/Library/CloudStorage/ -maxdepth 7 -type d -name "Pictures"` → **无结果**

**替代证据（等效可信）**：从真机日志的 `photo.saved` / `dng.saved` 事件提取 `name` 字段，共 **1369 条**。最近的真实文件名样例（按日志时间倒序）：

```
FILMKIT_20261008-095946.dng        (4080x3072)
FILMKIT_20261008-095952.jpg
FILMKIT_20261008-095957.dng        (4080x3072)
FILMKIT_20261008-100000.jpg
FILMKIT_20261008-100005.dng        (4080x3072)
FILMKIT_20261008-100008.jpg
FILMKIT_20261002-163452.jpg
FILMKIT_20261002-163503.jpg
FILMKIT_20261005-095853.dng        (4080x3072)
FILMKIT_20261005-095854.jpg
FILMKIT_20261005-095941.dng        (4080x3072)
FILMKIT_20261005-095941.jpg
FILMKIT_20261005-100001.dng        (4080x3072)
FILMKIT_20261005-100005.jpg
FILMKIT_20261005-100101.dng        (4080x3072)
FILMKIT_20261005-100105.jpg
FILMKIT_20261005-100143.dng        (4080x3072)
FILMKIT_20261005-100145.jpg
FILMKIT_20261005-100229.dng        (4080x3072)
FILMKIT_20261005-100232.jpg
```

**命名规则**：
- JPEG：`FILMKIT_<yyyyMMdd>-<HHmmss>[_<LUT名>].jpg`（LUT 后缀受 `lut_in_name` 控制，默认开）
- DNG：`FILMKIT_<yyyyMMdd>-<HHmmss>.dng`
- 目录：`Pictures/FilmKit`（`MediaStore.Images.Media.RELATIVE_PATH`，`CameraController.kt:3297` / `3443`）
- **DNG 与 JPEG 混在同一目录**，无子目录区分
- 时间戳为**设备本地时间**（`SimpleDateFormat` 无时区参数，用默认时区）
- 成片分辨率：**4080×3072**（DNG 日志 `out` 字段确认；JPEG 因画幅裁切可能不同，见 `CameraController.kt:3366-3384`）

---

### E. 已有但未启用的胶片参数

#### E12. 7 组参数的 shader 实现核查

**核查方法**：`grep -nE "[A-Za-z_]+\.(contrast|cross|shoulder|toe)\b" render/FrameRenderer.cpp` + 逐字段引用计数。

| 槽位 | 参数 | C++ 解析 | shader 实现 | 判定 | `neutral()` 默认值 |
|---|---|---|---|---|---|
| 5..7 | `shoulder[3]` | ✅ `FilmParams.h:342` | ❌ **零引用** | **死参数** | **0.0**（未设置） |
| 8..10 | `toe[3]` | ✅ `FilmParams.h:343` | ❌ **零引用** | **死参数** | **0.0**（未设置） |
| 23 | `split_warm` | ✅ `FilmParams.h:361` | ✅ `FrameRenderer.cpp:4343`（`kFsFilm` main） | **生效** | **0.0** |
| 24 | `split_cool` | ✅ `FilmParams.h:362` | ✅ `FrameRenderer.cpp:4344` | **生效** | **0.0** |
| 25 | `monochrome` | ✅ `FilmParams.h:363` | ✅ `FrameRenderer.cpp:4390` | **生效** | **0.0** |
| 26 | `grain_amount` | ✅ `FilmParams.h:364` | ✅ `FrameRenderer.cpp:4538,4548-4552`（`kFsOut`） | **生效** | **0.0** |
| 27 | `grain_size` | ✅ `FilmParams.h:365` | ✅ `FrameRenderer.cpp:4539,4549` | **生效** | **0.0** ⚠️ |

**生效项的 shader 代码**：

```glsl
// FrameRenderer.cpp:4343-4344  (kFsFilm main，分区饱和之后)
out3 += uSplit.x * ws * vec3(0.05, 0.02, -0.04)      // split_warm：阴影偏暖
      + uSplit.y * wh * vec3(-0.03, 0.0, 0.05);      // split_cool：高光偏冷
// 其中 ws = exp(-l*3.0)（暗部权重）, wh = smoothstep(0.5,1.0,l)（高光权重）, l = luma(out3)

// FrameRenderer.cpp:4390  (kFsFilm main 末行)
out3 = mix(out3, vec3(luma(clamp(out3, 0.0, 4.0))), uMono);   // monochrome
```

**死参数的根因**（`FrameRenderer.cpp:3941` 的一手注释）：

```
// 旧的 log 域胶片链（±4EV 编码 + contrast/toe/shoulder/cross 染料串扰）已整体删除：
```

**⇒ `shoulder` / `toe` / `cross` 属于被删除的旧 log 域链，`FilmParams` 里的槽位保留只是为了"契约不移位"，渲染器从不读取。**

**⚠️ 顺带发现的两个额外死参数（超出 E12 提问范围，但对预设 schema 设计是决定性的）**：

| 槽位 | 参数 | 判定 | 证据 |
|---|---|---|---|
| **4** | `contrast` | ❌ **零引用，死参数** | 全仓 `grep -nE "[A-Za-z_]+\.contrast\b"` 只命中 `9034`（`filmic_.contrast`）与 `11226`（`sp.contrast` = filmic spline 结构体），**均非 `FilmParams::contrast`** |
| **11..19** | `cross[9]` 染料串扰 | ❌ **零引用，死参数** | 引用计数 0；3941 行注释明确"已整体删除" |

**这直接推翻了调研文档 §3.1 的两条假设**：
- 文档把 `[4] 对比度` 列为"已覆盖，拍板保留，不需新做" → **实际是死参数，需要新做**
- 文档把 `[11-19] 染料串扰矩阵` 列为"**胶片色彩科学核心，槽位已在**" → **实际是死参数，需要新做**（这是"层间效应"的实现载体，文档 §3.2 把它列为 ★ 优先级）

**⚠️ `neutral()` 默认值的一处不一致**：`grain_size[27]` 在 Kotlin 侧是 **0.0**（`CameraPresets.kt` 未设置该槽），而 C++ 兜底是 **1.0f**（`FilmParams.h:31`）。因为 `grain_amount=0` 时颗粒分支整段不执行（`4548` 的 `if (uGrainAmount > 0.0)`），当前**无实际影响**；但编辑器一旦开放颗粒强度，`grain_size=0` 会被 shader 的 `max(uGrainSize, 0.25)` 兜到 0.25（**最细颗粒**），与 C++ 默认的 1.0 语义不同。**建议编辑器落地前统一为 1.0**。

#### E13. 颗粒实现细节

**完整实现**（`FrameRenderer.cpp:4548-4553`，`kFsOut` 的 `main()`）：

```glsl
if (uGrainAmount > 0.0) {
    vec2 gp = gl_FragCoord.xy * 6.0 / max(uGrainSize, 0.25) + uTime;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    float w = 4.0 * l * (1.0 - l);
    c += (vnoise(gp) - 0.5) * uGrainAmount * w;
}
```

配套（`4443-4458`）：

```glsl
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {                    // 值噪声，双线性插值 + smoothstep 权重
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    ... mix(mix(a,b,f.x), mix(c,d,f.x), f.y)
}
```

**逐项回答**：

| 问题 | 答案 |
|---|---|
| 调制曲线是什么？ | **`w = 4·l·(1−l)`** —— 顶点在 `l=0.5` 的**抛物线**，`w ∈ [0,1]` |
| 是否与亮度/密度相关？ | **是**，`l` = `dot(c, vec3(0.299,0.587,0.114))`，且 `c` **已经过 `srgbEncode`**（`4545`）⇒ `l` 是 **sRGB 显示域亮度**，不是线性密度 |
| 有没有"暗部颗粒重、亮部轻"的调制？ | ❌ **没有，而且是相反的**。`4l(1−l)` 在 `l→0`（暗部）和 `l→1`（亮部）**都趋于 0**，只有**中灰最强**。这与负片的"密度越高银盐簇越显"关系（调研文档 §3.2 的"颗粒密度调制：暗部多、亮部少"）**方向相反** |
| 颗粒尺寸怎么控制？ | `uGrainSize` 是噪声空间频率的**除数**：`gp = fragCoord * 6.0 / size` ⇒ `size` 越大，噪声图案被拉得越粗（颗粒越大）。下限 `max(size, 0.25)` 防除零 |
| 是否可复现？ | ❌ **不可复现**。`uTime = frames_.load() % 512`（`FrameRenderer.cpp:11540`）用**帧序号**当噪声相位 ⇒ 同一张图在不同帧渲染会得到**不同的颗粒图案**。对单次拍照无影响，但编辑器"同一参数重复导出应一致"需要固定种子 |

**⇒ 编辑器要做的颗粒工作**：
1. 把调制曲线从"中灰最强"改为可配的**密度调制**（新增槽位，如 `grain_density_mod`），默认保持现值以免回归；
2. 新增"种子"uniform（或让 `uTime` 可被外部固定），保证导出可复现；
3. `grain_size` 默认值统一（见 E12 末尾）。

#### E14. 暗角（vignette）/ halation（高光光晕）是否存在？

**结论：两者在引擎里都不存在，必须新写。**

**grep 证据**（全仓 `--include="*.cpp" --include="*.h" --include="*.kt" --include="*.xml"`，排除 `docs/`）：

```
core/engine/src/render/FrameRenderer.cpp:129   // 注释："并引入光晕"（讲局部色调映射的低频图尺寸，非 halation 效果）
app/src/main/java/com/filmkit/app/ui/SettingsScreen.kt:339
      subtitle = "HAL 增益网格消暗角与角落色偏（RAW 链、CCM 前，17×13 手写双线性）；关 = 同机位 A/B 对照"
app/src/main/java/com/filmkit/app/camera/CameraController.kt:3225
      // 代价：保留 ~0.4EV 的边缘晕影（阻尼后校正不足），温和暗角。
```

- `vignette` / `halation` / `glow` / `bloom` 作为**功能标识符**：**零命中**（没有 `uVignette`、没有 `uHalation`、没有对应 shader 函数、没有 `prog_*`）。
- 命中项全是**注释或 UI 文案**，且语义是：
  - `SettingsScreen.kt:339` 是 **LSC（镜头阴影校正）** 的开关说明 —— **消除**暗角，方向相反；
  - `CameraController.kt:3225` 是 LSC 阻尼后**残留**的暗角，是被容忍的副作用，不是效果；
  - `FrameRenderer.cpp:129` 的"光晕"指局部色调映射低频图过大时的副作用；
  - `FrameRenderer.cpp:3245` 的 `uniform float uDelta; // bloom_vs_details` 是 **darktable filmic 高光重建（à trous 小波）** 的频段命名，与 halation 无关。

**容易误认但确实不是 halation 的现成设施**：

| 设施 | 位置 | 实际职责 |
|---|---|---|
| `runHighlightReconstruct`（HR 小波） | `FrameRenderer.cpp:11399-11405` 调用 | darktable `filmicrgb.c` 的 à trous 小波高光重建；参数 `FilmParams[41] hr_wavelet`，**默认关**（`neutral()[41]=0.0`） |
| `kFsHlApply` / `kFsHlMask` / `kFsHlChroma` … | `3672` / `3353` / `3461` | darktable highlights "inpaint opposed" 的 **RAW 域**裁剪通道外推（`FilmParams[45] hr_raw`）；作用在去马赛克**前**的 mosaic，与"光晕扩散"无关 |
| LSC 增益网格 | `passRaw` 的 `lsc_tex_`（`6563-6566`） | 消除镜头失光/角落色偏 |

**⇒ 编辑器要做的两个新效果**：
- **halation**：需要"高光提取 → 阈值 → 大半径模糊 → 红橙色相染色 → 加回"。引擎里**已有可复用的高光提取设施**（`kFsHlMask` 的 mask 逻辑 + `kFsHrBlur` 的高斯模糊 pass，`FrameRenderer.cpp:3188`），可省掉一半工作。建议插在 `kFsOut` 的 OETF **之前**（线性域加回，物理上更接近银盐散射）或之后（显示域，更可控），需 A/B 定夺。
- **暗角**：最简单的实现 —— `kFsOut` 里加 3 行（`vec2 d = vUV0 - 0.5; c *= 1.0 - strength * dot(d,d) * k;`），**无需新 pass**。放在 OETF 前（线性域）物理上更正确。

---

### F. 预设机制现状

#### F15. `CameraPresets` 的全部引用与"死活"判断

**全部引用点**（`grep -rn "CameraPresets" app/src/main`）：

| 文件:行号 | 代码 | 作用 |
|---|---|---|
| `ui/CameraPresets.kt:13` | `object CameraPresets {` | 定义 |
| `ui/CameraScreen.kt:425` | `controller.setFilmParams(CameraPresets.byIndex(presetIdx))` | **启动即应用**当前预设 |
| `ui/CameraScreen.kt:1347` | `presetIdx = i; controller.setFilmParams(CameraPresets.byIndex(i))` | 预设卡片点击 `onPick` |
| `ui/CameraScreen.kt:1925` | `CameraPresets.names.getOrElse(presetIdx) { "标准" }` | 当前预设名显示 |
| `ui/CameraScreen.kt:2003` | `itemsIndexed(CameraPresets.names) { i, name -> LutCard(...) }` | 预设选择条 UI |
| `camera/CameraController.kt:28` | `import com.filmkit.app.ui.CameraPresets` | 导入 |
| `camera/CameraController.kt:77` | `private var filmParams: DoubleArray = CameraPresets.standard()` | 成员初值 |
| `ui/SettingsScreen.kt:428,434` | 仅注释引用（说明默认值同步） | — |

**预设机制是"死的"还是"活的"？→ 半活（骨架活、内容死）。**

**活的部分**：
- UI 完整：`LazyRow` + `itemsIndexed(CameraPresets.names)` + `LutCard`，选中态 `selected = i == presetIdx`（`CameraScreen.kt:2003-2006`）
- 调用链完整：点击 → `presetIdx = i` → `controller.setFilmParams(CameraPresets.byIndex(i))`（`1347`）
- 启动应用完整：`LaunchedEffect(Unit) { controller.setFilmParams(CameraPresets.byIndex(presetIdx)) }`（`425`）

**死的部分（决定性的）**：

```kotlin
// CameraPresets.kt:15-17
val names = listOf("标准")                                    // ← 只有 1 个元素
fun byIndex(i: Int): DoubleArray = standard()                 // ← 忽略参数 i，恒返回中性基线
```

**⇒ 无论选哪个预设（当前只有"标准"一个），拿到的都是同一份 `neutral()` 数组 ⇒ 拍摄时实际效果 = 恒等于中性基线，预设机制对画质零影响。**

**此外，预设的作用还被 prefs 覆盖层稀释**：`setFilmParams`（`CameraController.kt:1033-1050`）之后会立即调

```kotlin
applyBaseEvPref()        // 覆盖 [0]
applyTonePref()          // 覆盖 [28]
applyZebraPref()         // 覆盖 [30]
applyLutStrengthPref()   // 覆盖 [34]
```

并且启动后还有 `applyFilmicRangePrefs`(→[37]/[38])、`applyAutoTonePref`(→[35])、`applyLocalTonePref`(→[36])、`applySharpenPref`(→[39])、`applyGamutPref`(→[40])、`applyHrWaveletPref`(→[41]) 等一整套 prefs → 槽位覆盖层。

**⇒ 现状语义**：`CameraPresets` 提供的是"**基线 + 默认值**"，而**真正的当前参数值由 prefs 决定**。这对编辑器的含义是：**"存为预设"必须能表达"哪些槽位被预设接管、哪些仍归 prefs"**，否则预设与设置页开关会互相打架。

#### F16. prefs 持久化机制的接口形态

**先纠正一个可能的误解**：`app/src/main/java/com/filmkit/app/JsonKv.kt`（34 行）**不是** KV 存储，它是**日志行的 kv JSON 构造器**：

```kotlin
fun kv(vararg pairs: Pair<String, Any?>): String   // 拼 {"k":v,...}，用于 nativeAppendLog
fun jsonEscape(s: String): String                  // 手写转义（为 JVM 单测无 Android 依赖）
```

**真正的 prefs 机制 = Android 原生 `SharedPreferences`**：

| 项 | 位置 |
|---|---|
| CameraScreen 侧 | `CameraScreen.kt:200` `val prefs = remember { context.getSharedPreferences("filmkit", 0) }` |
| CameraController 侧 | `CameraController.kt:1052` `private val appPrefs by lazy { context.getSharedPreferences("filmkit", 0) }` |
| SettingsScreen 侧 | `SettingsScreen.kt:79` 等（同一文件名 `"filmkit"`） |

**接口形态**（读）：`prefs.getBoolean(k, def)` / `getInt` / `getFloat` / `getString(k, def)` / `getStringSet`
**接口形态**（写）：`prefs.edit().putBoolean/putInt/putFloat/putString(k, v).apply()`

**能否直接用来存预设列表？→ 能，但有约束。**

| 能力 | 支持情况 | 证据 |
|---|---|---|
| 存 JSON **字符串** | ✅ 完全支持，**有多个现成用例** | `lut_id`（`CameraScreen.kt:413`）、`capture_aspect`（`144`/`1416`）、`focal_strip`（`267`/`1408`）、`custom_focals`（`993`） |
| 存 **float/int/bool** | ✅ | `lut_strength`（`1307`/`1343`）、`base_ev`、`sharpen`… |
| 存 **数组**（如 `DoubleArray(60)`） | ❌ **原生不支持** | 无 `putDoubleArray`；需序列化为字符串（逗号分隔或 JSON） |
| 存**列表**（预设集合） | ⚠️ 需自行设计键名 | 可用 `"preset_<id>"` → JSON 字符串 + `"preset_ids"` → 逗号分隔 id 列表；或用 `getStringSet` |
| 容量 | ✅ 足够 | SharedPreferences 是 XML 文件，无硬上限（60 个 double ≈ 700 字符/预设） |
| 原子性/一致性 | ⚠️ `apply()` 异步 | 多键写入需注意；建议**一个预设 = 一个 JSON 字符串 = 一个键**，避免多键不一致 |

**推荐形态**（对齐调研文档 §3.3 的 JSON 草案）：

```
prefs: filmkit
  "preset_ids"        -> "portra400_warm,fuji_cool"        (String, 逗号分隔)
  "preset_portra400_warm" -> "{...完整 JSON...}"            (String)
  "preset_active"     -> "portra400_warm"                  (String)
  "shot_params_<name>"-> "{...快照 JSON...}"                (String，见 C7)
```

---

## 3. 风险与未知

> 本节严格区分"已验证"与"未验证"。凡标注 **未验证** 的，都是本次勘察**没有拿到直接证据**的项，不应被当作结论使用。

### 3.1 未验证项（必须补测）

| # | 未验证内容 | 为什么没验证 | 建议的验证方式 |
|---|---|---|---|
| U1 | **预览分辨率（1216×1621）下"只重跑 LUT 之后"的精确 ms** | 引擎没有为该段单独打点；`passMs` 截止于 `passFilm`，`passOutput` 与 `glFinish`/`glReadPixels`/诊断统计混在一起 | 在 `passOutput` 前后各加一次 `glFinish` + 计时，打一条 `style.ms` 日志；或在 `tools/` 下用离线 shader 复现 |
| U2 | **`passOutput` 纯 GPU 时间** | 只能用残差法给上界（p50 55.2 ms @4080×3072），其中含 50 MB `glReadPixels` 与 12.5 M 像素 CPU 诊断统计（`g_diag_stats` 默认开） | 关闭 `diag_stats`（`prefs("diag_stats")`）后重测残差，差值即诊断开销；再单独测 `glReadPixels` |
| U3 | **RGBA8 输入通路在真实设备上的端到端耗时** | 该通路尚不存在 | 落地 A2 后，用 A/B 日志（现有 `render.single_done` 模式）实测 |
| U4 | **halation / 暗角的效果参数标定** | 引擎里零实现 | 需先实现最小版本，再用样片标定（可参考 `docs/research/10` §2.4 的外部调研） |
| U5 | **DNG↔JPEG 精确配对的可行性** | 当前无关联字段，只能靠时间启发式 | 落地 C8 建议的共享 shot id 后即可精确配对 |
| U6 | **`Pictures/FilmKit` 的真实文件列表** | CloudStorage 无 vivo X300 挂载点（只有 `OPPO互联-DeviceSpace`） | 用 `adb shell ls /sdcard/Pictures/FilmKit` 或让用户手工导出目录列表 |
| U7 | **编辑全分辨率成片时的内存峰值** | 未测 | 4080×3072 RGBA8 = 50 MB/张，若编辑器同时持有"原图 + 预览 + 导出缓冲"需实测 OOM 边界 |

### 3.2 已识别风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **把 JPEG 喂给 `passFilm` 会双重影调映射** | 画面显著变暗/变灰，用户会认为"编辑器毁了照片" | A2 的切点必须严格落在 `srgbEncode` **之后**；**禁止**复用 `submitStill` 的 `passFilm` 调用 |
| R2 | **`pow(2.2)` 不是精确 sRGB EOTF** | `kFsStill`（`FrameRenderer.cpp:232`）用 `pow(rgb, 2.2)` 近似反 gamma，而 `kFsOut` 用的是**真 sRGB**（IEC 61966-2-1，`4533-4538`）。若编辑器要"读 JPEG → 转线性 → 再编码"，会引入往返误差（暗部最大约 9/255，`4522-4531` 的实测记录） | 编辑器走 A2 的"跳过 OETF"路径（**不往返**），天然规避 |
| R3 | **颗粒不可复现**（`uTime = frames_ % 512`） | 同一参数两次导出得到不同颗粒图案，A/B 对比失真 | 新增可外部固定的种子 uniform |
| R4 | **`shoulder`/`toe`/`contrast`/`cross` 是死参数** | 若按调研文档 §3.1 直接做 UI 滑杆，**滑了没反应** | 要么在 `kFsFilm` 里重新实现（趾肩 = filmic spline 的参数化；染料串扰 = 矩阵乘），要么从 schema 里删掉。**不要做假滑杆** |
| R5 | **`lastPhotoUri` 不持久化** | 冷启动后编辑器入口无图可编（胶卷位显示占位符） | 见第 4 节第 1 步 |
| R6 | **预设与 prefs 覆盖层冲突** | "存为预设"后重新选中，部分槽位被 prefs 覆盖回旧值 | 预设 schema 需明确"接管哪些槽位"；建议预设 JSON 存**完整 60 槽快照**，选中时整体下发并**跳过对应 prefs 覆盖**（或让 prefs 覆盖层只在用户显式改动时生效） |
| R7 | **`FilmParams.kCount = 60` 是硬契约** | `CameraPresets.kt:24-28` 记录了一次真机事故：C++ 加槽到 60 而 Kotlin 仍是 59 ⇒ 启动即崩 | 编辑器新增槽位（颗粒密度调制/halation/暗角）必须**两处同改**（`FilmParams.h::kCount` + `CameraPresets.neutral()` 的 `DoubleArray(N)`） |
| R8 | **DNG 与 JPEG 时间戳不同** | 无法靠文件名配对，连拍时启发式会错配 | 落地共享 shot id |
| R9 | **`neutral()` 里 `grain_size = 0.0` 与 C++ 兜底 1.0 不一致** | 编辑器开放颗粒后语义漂移（0.25 vs 1.0） | 统一为 1.0 |

### 3.3 与调研文档 `docs/research/10` 的差异（需回写文档）

| 文档 § | 原文 | 勘察结论 |
|---|---|---|
| §3.1 | "影调：基准曝光、filmic 黑白点、**对比度**、局部色调映射 [0][37/38][**4**][36]" | ❌ **`[4] contrast` 是死参数**（FrameRenderer.cpp 零引用），需新做 |
| §3.1 | "**染料串扰矩阵**（胶片色彩科学核心，槽位已在）[11-19]" | ❌ **`[11-19] cross` 是死参数**（3941 行注释：旧 log 域链已整体删除），需新做 |
| §3.2 | "★★★ **颗粒强度** / **颗粒尺寸** 需新增" | ⚠️ **已存在且已生效**（`[26]`/`[27]`，`FrameRenderer.cpp:4548-4553`）；真正缺的是**密度调制** |
| §3.2 | "★★★ 颗粒密度调制：暗部多、亮部少（负片密度关系）" | ✅ **确实缺失**，且当前曲线 `4l(1−l)` 方向**相反**（中灰最强） |
| §4.1 | "入口：胶卷位缩略图点击 → 编辑器" | ⚠️ 胶卷位**当前无点击回调**（`CameraScreen.kt:1243-1263`），且 URI 不持久化 |
| §4.3 | "编辑器必须先落'预览分辨率全链 <300ms'的实测基线" | ✅ **已落**：预览 1216×1621 全链 **33.3 ms**（n=5725），远优于要求 |
| §4.2 | "DNG 底片开着时，编辑器从 DNG 重跑全链" | ⚠️ DNG 通路已实现，但**与 JPEG 无法精确配对**（时间戳差 0–6 s） |

### 3.4 与 `docs/reports/editor-ADR.md` 的对齐与冲突（★ 派工前必读）

勘察期间工作区已存在并行产出的 `docs/reports/editor-ADR.md`（180 行）与 `EditorScreen.kt` / `StillSnapshot.kt` 骨架。逐条对齐：

| ADR | 内容 | 本报告结论 | 判定 |
|---|---|---|---|
| **ADR-7** | 输入 = 已过 OETF 的 sRGB 成片；**不重跑 OETF**；不重跑 filmic/WB/去马赛克；不"再锐一次" | **完全一致**。本报告 A1/A3 给出了该约定的**精确 shader 切点**：`FrameRenderer.cpp:4545`（`c = srgbEncode(c)`）**之后**、`4547-4559`（LUT/颗粒/dither）之前 | ✅ 一致，本报告补上实现坐标 |
| **ADR-8** | 必须新增一条引擎输入通路，接口形态"由可行性勘察报告确定" | **本报告 A2 即为该答案**：4 文件 / ~120–150 行；推荐"新增 `kFsStyle`，不动 `kFsOut`"（成片路径零回归） | ✅ 已交付 |
| **ADR-4** | 快照存 prefs 单键 `still_snapshots`，键 = 文件名，JSON，不设上限不清理 | 方向一致（本报告 C7 亦判定"当前无任何快照"）。**差异**：本报告推荐 EXIF `UserComment`（随文件走、删除照片即消失、不占 prefs、无无限增长） | ⚠️ 两种都可行；prefs 方案的"不设上限"在长期使用后有 XML 膨胀与 `commit()` 卡顿风险（`StillSnapshot.kt:80` 用的是同步 `commit()`，在 IO 线程上可接受，但键值会随张数线性增长） |
| **ADR-6** | 预设存 prefs 键 `presets`，与快照共用槽位语义 | 一致。本报告 F16 补充：`JsonKv.kt` **不是** KV 存储（易误用），真 prefs 是 `getSharedPreferences("filmkit", 0)`；且需处理**预设 vs prefs 覆盖层的仲裁**（本报告 R6） | ⚠️ 一致，附仲裁建议 |
| **ADR-5** | "已有但未启用槽位**复用**：[5..10] shoulder/toe、[23][24] split_warm/cool、[25] monochrome、[26][27] grain_amount/grain_size" | ❌ **`[5..10] shoulder/toe` 不能复用** —— 它们是**死参数**（`FrameRenderer.cpp` 零引用；`3941` 行注释：旧 log 域胶片链已整体删除）。`[23][24][25][26][27]` 五项**确实可复用**（均已生效） | ❌ **冲突，必须修正 ADR-5** |
| **ADR-5**（追加） | "新增参数从 [60] 起追加" | 一致。**提醒**：`FilmParams.h::kCount` 与 `CameraPresets.neutral()` 的 `DoubleArray(N)` 必须**同改**（`CameraPresets.kt:24-28` 记录了一次"只改一处 ⇒ 启动即崩"的真机事故） | ✅ 一致 |

**⇒ ADR-5 的修正建议**：把 `[5..10] shoulder/toe` 从"复用"改为"**需先在 `kFsFilm` 里实现**（或从 schema 删除）"。若按原 ADR 直接做 UI 滑杆，结果是**滑了没有任何反应**——而且因为 `neutral()` 里这两组默认值就是 0.0，连"看起来生效了"的假象都没有。

**另需注意**：`EditorScreen.kt`（213 行）目前**只做 UI 与 `BitmapFactory` 加载，尚未接引擎渲染**（全文件无 `nativeEngineSubmit*` 调用）。一旦接 E1-3，**必须走本报告 A2 的通路，绝不能复用 `submitStill`**（后者会调 `passFilm`，触发 R1 的双重影调映射）。ADR-7 已经写明这条纪律，本报告提供代码级坐标以免实现时走偏。

---

## 4. 建议的最小实现路径

> 原则（对齐 `AGENTS.md` 与 ponytail 阶梯）：**先复用，后新增；不改契约；成片路径零回归；每一步都可独立验证。**

### 第 0 步：先补两个"断点"（不做这两步，编辑器打不开）

| 步骤 | 位置 | 改动 | 验证 |
|---|---|---|---|
| **0a. 胶卷位加点击回调** | `CameraScreen.kt:1245-1250` 的 `Modifier` 链加 `.pressScale(onTap = { ... })`（同文件 1269 已有此工具函数） | ~3 行 | 点击有反应 |
| **0b. 持久化最近成片 URI** | `CameraController.kt:3449` 之后 `appPrefs.edit().putString("last_photo_uri", uri.toString()).apply()`；`lastPhotoUri` 改为 lazy 初值从 prefs 读 | ~5 行 | 冷启动后胶卷位仍有缩略图 |

### 第 1 步（E1）：JPEG 重跑后段 —— 引擎侧最小通路

**走 A2 的"备选方案"（新增 `kFsStyle`，不动 `kFsOut`）**，因为成片路径零回归是本项目的铁律。

| 文件 | 改动 | 行数 |
|---|---|---|
| `core/engine/src/render/FrameRenderer.cpp` | 新增 `kFsStyle` shader（从 `kFsOut` 拷 `lutTetra`/`lutApply`/`hash`/`dhash`/`vnoise` + `main()` 只保留 LUT/颗粒/dither）；新增 `prog_style_` 编译链接（照 5177/5269）；新增 `u_style_` uniform 结构；新增 `passStyle()`；新增 `submitDisplay()`（复用 `still_tex_` 上传 + `ensureTargets` + `passStyle` + `glReadPixels`） | ~150 行 |
| `core/engine/src/render/FrameRenderer.h` | `prog_style_` 成员、`u_style_` 结构、`passStyle`/`submitDisplay` 声明 | ~12 行 |
| `core/engine/src/jni/Bridge.cpp` | `nativeEngineSubmitDisplay`（照抄 618-650 骨架） | ~35 行 |
| `app/.../EngineBridge.kt` | `external fun nativeEngineSubmitDisplay(...)` | ~8 行 |

**验证**：Kotlin 侧用现有 `loadThumbnail` 改成全尺寸 `BitmapFactory` 解码 → 取 RGBA → 调 `nativeEngineSubmitDisplay` → 存新 JPEG。**A/B 对照**：同一张图、LUT 强度 0（应逐像素 ≈ 原图，验证通路无损）vs 强度 1（应有可见风格变化）。

### 第 2 步（E1 续）：编辑器 UI MVP

| 项 | 复用 | 新增 |
|---|---|---|
| 素材加载 | `contentResolver.openInputStream`（`LutLibrary.kt:175` 的模式）+ `BitmapFactory`（`LutIO.kt:44-48` 的模式） | 全尺寸解码 + RGBA 提取 |
| LUT 选择 | **完整复用**：`LutLibrary`（298 行）、`CubeLut`、`LutIO`、`LutPreview`、`CameraScreen.kt:1340-1380` 的 LUT 选择面板与 `LutCard` | 把面板从拍摄页抽成可复用 Composable（或直接复用同一 Composable） |
| LUT 上传 | `EngineBridge.nativeEngineSetLut`（`EngineBridge.kt:180`，已有 `applyLutSnapshot` 双实例下发） | 无需改动 |
| 强度滑杆 | `CameraController.setLutStrength`（`1213`）+ prefs `lut_strength` | 无需改动 |
| 导出 | `saveJpeg` 的 MediaStore 写入段（`3439-3465`）可抽成共用函数 | 抽函数 ~20 行 |

### 第 3 步（E2）：专业参数面板

**按耗时分级**（这是 B5/B6 实测数据给出的直接设计依据）：

| 参数组 | 引擎代价 | 交互策略 |
|---|---|---|
| **LUT 强度、颗粒、暗角、halation** | 后段 1 个 pass，**全分辨率 p50 ≈ 55 ms 上界** | ✅ **全分辨率实时**，滑杆直接重渲 |
| **曝光 [0]、WB [1-3]、影调 [37/38]、局部映射 [36]、分区饱和 [20-22]、split、mono** | 需重跑 `passFilm` 全链；全分辨率 **502–1868 ms** | ⚠️ 拖动时用**预览分辨率**（1216×1621 ⇒ 33 ms/帧，实时）；松手后跑一次全分辨率 |

**⇒ 建议引擎侧加一个 `nativeEngineSubmitDisplay` 的分辨率参数**（或复用 `previewScale` 的思路给编辑器一个独立的 `edit_scale`），让"拖动走小图、松手走大图"成为一次调用参数而不是两条通路。

### 第 4 步（E3）：预设机制激活

1. **填充 `CameraPresets`**：把 `names` 从 `listOf("标准")` 扩为从 prefs 读取；`byIndex(i)` 改为返回对应预设的 60 槽快照（无对应则回落 `standard()`）。**改动约 20 行**。
2. **预设 JSON schema**：直接采用调研文档 §3.3 的草案，但**修正两处**：
   - 删掉 `tone.toe` / `tone.shoulder`（死参数，见 R4）——除非同时在 `kFsFilm` 里实现；
   - `crosstalk` 标注为"需引擎新做"。
3. **落盘**：`prefs.putString("preset_<id>", json)` + `"preset_ids"`（见 F16）。
4. **与 prefs 覆盖层的仲裁**（R6）：建议"**预设 JSON 存完整快照；选中预设时整体下发，并清空对应的 prefs 覆盖哨兵**"（`tonePref`/`zebraPref`/`lutStrengthFromPrefs` 等置 `-1.0`/`NaN`，`setFilmParams` 的 `1033-1050` 已经是这个模式，直接复用）。

### 第 5 步（E4）：三个缺失的胶片效果（引擎新 pass）

| 效果 | 复用 | 新增 | 建议位置 |
|---|---|---|---|
| **颗粒密度调制** | 现有 `kFsOut` 颗粒段（`4548-4553`） | 1 个 uniform（调制指数）+ 改 `w` 的公式（如 `w = pow(4l(1−l), k) ` 或按 `(1−l)` 加权）；**同时加固定种子 uniform**（R3） | 原地改 |
| **暗角** | 无 | `kFsOut` 里 3 行（`c *= 1 - strength * dot(d,d)`）；1 个 uniform | OETF **前**（线性域） |
| **halation** | `kFsHlMask`（`3353`，高光提取）+ `kFsHrBlur`（`3188`，高斯模糊 pass）+ 多 pass 编排的现成范式（`passDefringe`/`passChromaMultiScale`） | 1 个新 shader（阈值 + 色相染色 + 加回）+ 1 个 pass 函数 + 1 组目标纹理 + 3 个 uniform（强度/阈值/半径） | OETF **前**（线性域加回，物理上更接近银盐散射）；半径大 ⇒ 用降采样模糊省成本 |

**每个效果都遵循项目纪律**：`0 = 关` 的 A/B 通路（见 `FilmParams.h` 里 `hr_wavelet`/`gamut`/`defringe` 等的写法）。

### 第 6 步（E5）：DNG 编辑模式

- 复用 `saveDng` 已建立的 DngCreator 元数据通路（`3272-3326`）；
- **从 DNG 重跑全链** = 直接复用 `nativeEngineSubmitRaw`（`Bridge.cpp:659-735`）—— **零引擎改动**；
- 前置条件：先落地 C8 的共享 shot id（否则找不到配对的 DNG）。

---

## 5. 附：勘察命令速查

```bash
# 渲染入口清单
grep -n "Java_com_filmkit_app_EngineBridge_native" core/engine/src/jni/Bridge.cpp
grep -n "external fun native" app/src/main/java/com/filmkit/app/EngineBridge.kt

# 死参数核查（应为空）
grep -nE "[A-Za-z_]+\.(shoulder|toe|cross)\b" core/engine/src/render/FrameRenderer.cpp

# 颗粒与后段 shader
sed -n '4395,4571p' core/engine/src/render/FrameRenderer.cpp     # kFsOut
sed -n '11379,11553p' core/engine/src/render/FrameRenderer.cpp   # passFilm + passOutput

# 实测日志统计（在 ~/Downloads/vivo办公套件/ 下执行）
#   统计 render.single_done / render.still_done 的 ms 分布
#   统计 raw.render 的 uploadMs/passMs/totalMs 分段
#   统计 render.fps 分布 → 预览帧耗时
```

---

*报告完 · 全部结论均附 `文件:行号` 或实测数据；未验证项已在 §3.1 显式标注。*
