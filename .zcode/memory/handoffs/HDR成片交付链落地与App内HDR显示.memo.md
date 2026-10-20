---
summary: HDR成片交付链落地与App内HDR显示
created: 2026-10-20 09:29
updated: 2026-10-20 09:29
status: active
---

# HDR成片交付链落地与App内HDR显示

## Summary

按 PhotonCamera 一手源码（`bjzhou/PhotonCamera@27d4f78`）实现 HDR 成片交付：**JPEG 4:4:4 走
自研 stb base + libultrahdr 封装**（保住 4:4:4 红线），4:2:0 与 HEIC 走平台 Gainmap API。
gainmap 在 **GPU 算出**（`kFsGainmap`）—— 因为 ES 3.0 读不回浮点纹理。数学用 **PC 版**
（offset 1e-4），两版可运行时切换供真机 A/B。系统相册验证通过；App 内 HDR 显示修掉三个 bug
后也通了。发现"高光顶白"其实是 **SDR base 比 PC 亮 1.7~1.9 倍**（自动曝光策略），与 gainmap
无关，用户决定单独开期。

## Done

- [2026-10-20] **vendored libultrahdr 2.0.2 + libjpeg-turbo 3.1.0**（Apache-2.0/MIT，1.6MB）：
  `core/engine/third_party/ultrahdr/`。CMake 开关 `-DFILMKIT_HDR`（**默认 OFF**，避免日常构建付
  libjpeg-turbo 的 ~1-2 分钟）。Gradle 侧 `-PfilmkitHdr=true`。
- [2026-10-20] **`UltraHdrEncoder.cpp`**：stb 编 4:4:4 base（复用 `JpegEncoder.cpp` 的实现，
  不重复 define `STB_IMAGE_WRITE_IMPLEMENTATION`）+ 预计算 gainmap → `uhdr_enc_set_compressed_image
  (UHDR_BASE_IMG)` + `set_gainmap_image` + `set_output_format(JPG)` + 产物 `is_uhdr_image` 自检。
  实测：base SOF0 仍是 **4:4:4**、元数据回读正确、官方解码器能出 half-float。
- [2026-10-20] **`kFsGainmap` GPU pass**：tex_scene_（Rec.2020 线性）÷ tex_cap_（sRGB 8bit）→
  1/2 分辨率 8bit 比值图。域口径：HDR 侧先 `workToSrgb`、SDR 侧先反 OETF，再各取 Rec.709 luma。
  采样核 **4×4 步长 2**（相邻像素输入重叠 50% ⇒ 隐式平滑）。
- [2026-10-20] **两版 gainmap 数学**（`setGainmapMath`，0=PC / 1=官方）：PC 版 offset `1e-4`；
  官方版 offset `1e-7` + 暗部 `sdr<2/255` 钳 gain≤2.3。两版都归一化到固定 `[0.25, 4.0]`
  （官方自适应范围需两遍扫描，单 pass 做不到）。**默认 PC 版**。设置页有切换项，重启按 prefs 恢复。
- [2026-10-20] **三条交付路径**（`saveJpeg` 优先级）：4:4:4 开 + 有 gainmap ⇒ 自研 JPEG/R；
  4:4:4 关 + Android 14+ ⇒ 平台 `Bitmap.setGainmap` + `compress`；否则普通 SDR JPEG。
  **任何一步失败都落回 SDR**（照片优先红线）。
- [2026-10-20] **交付链线程模型**：`nativeReadGainmap` 必须在**拍照渲染线程**、下一次 submit
  **之前**读（纹理会被覆盖）⇒ 在 `StillRenderClient` 的 onDone lambda 里读，随成片传给 saver 线程。
- [2026-10-20] **gainmap 随成片同步裁剪**（`cropGainmap`）：成片画幅居中裁切后，gainmap 也按
  `(x/2, y/2)` 同步裁；越界返回 null（宁可无 HDR，不可错位 HDR）。
- [2026-10-20] **HEIC + HDR**（用户拍板**接受 8bit 降位**）：平台 `HeifWriter` + gainmap
  （`PlatformGainmapWriter.writeHeic`）。现有 HEIC 是自研 10bit 容器，开 HDR 会降到 8bit ——
  用户的理由：HEIC 的 4:2:0 色度损失是主导短板，10bit 的量化精度被稀释。
- [2026-10-20] **App 内 HDR 显示（三个 bug 依次修掉）**：
  ① `ImageDecoder` **不解析 MPF 多图**（gainmap 是第二张 JPEG）⇒ 改用 `BitmapFactory` +
  **`inSampleSize = 1`**（>1 会丢 gainmap）；
  ② `context as? Activity` 在 Compose 里拿不到（被 ContextWrapper 包着）⇒ `findActivity()` 逐层解包；
  ③ 我多加的 `configuration.isScreenHdr` 条件在这台设备上恒 **false** ⇒ 窗口永远停在
  `COLOR_MODE_DEFAULT`。官方只按 `hasGainmap()` 决定，去掉该条件后 `colorMode=2` 生效。
- [2026-10-20] **导出 SDR 版**（`nativeExtractSdrBase`）：从 JPEG/R 里**无损取出 base**
  （`uhdr_dec_get_base_image`），不重压。查看页 HDR 面板里的「导出 SDR 版」按钮。
- [2026-10-20] **HDR 面板**：遮罩改**全透明**（半透明黑罩会把高光压暗，HDR 开关看不出差别）+
  紧凑布局（说明压一行、padding 收紧，可视面积优先）。
- [2026-10-20] **出包纪律固化进 `AGENTS.md`**：每个交付包必升 `versionCode/versionName`、
  出包后只留"最新 + 上一稳定版"、禁止用 `-fix1/-fix2` 代替版本号、打包后必验三项
  （签名 / 版本号 / 关键 JNI 符号）。

## Decisions

- [2026-10-20] **gainmap 数学默认 PC 版**：离线 A/B（`tools/gainmap-math-ab.py`）口径统一到
  8bit `[0,1]` 后，A'官方(固定范围) vs B PC 的逐像素差 mean 0.0069 / p99 0.127；但官方
  **自适应范围**在无高光场景会把码值浪费在极窄区间。真机证据：用户切到官方版后 gainmap JPEG
  达 **570KB**（正常几十 KB）、`>=0.90` 像素占 1.61% 且散布，经 IDW 上采样连成环 ⇒
  "太阳把栏杆覆盖"。PC 版 offset 1e-4 天然抑制暗部抖动。
- [2026-10-20] **gainmap 必须在 GPU 算**：ES 3.0 只保证 `GL_RGBA+UNSIGNED_BYTE` 读回，浮点纹理
  读回真机不可用（2026-10-05 实测，且失败还破坏渲染状态导致成片全黑）。所以只读回 1/2 分辨率
  8bit（12MP ≈ 3MB），不读 100MB 浮点。
- [2026-10-20] **HEIC HDR 接受 8bit 降位**（用户拍板）：用可见的 HDR 动态范围换不可见的量化精度。
  HDR 的正确载体是 JPEG 4:4:4 + gainmap（既有色度又有动态范围），HEIC HDR 只作"已选 HEIC 时顺手带上"。
- [2026-10-20] **不检查 `isScreenHdr`**：它在用户设备上恒 false 而系统相册能显示 HDR ⇒ 不可靠。
  只按 `bitmap.hasGainmap()` 决定窗口模式；设备不支持时系统自己降级。
- [2026-10-20] **不再用 `inPreferredColorSpace = EXTENDED_SRGB`**：实测设了也不生效（解出来仍是
  sRGB）。Ultra HDR 的 base 本来就是 sRGB、HDR 增量由 gainmap 承担。
- [2026-10-20] **"高光顶白"归因到自动曝光策略，单独开期**：同场景对比（08:52~08:54）——
  我们的 SDR base 平均亮度 **85.2/98.4** vs PC **50.7**（1.7~1.9×）；`>=240` 占比 **3.38%/2.22%**
  vs PC **0.37%**（6~9×）；而 **HDR 输出几乎一样**（P90 0.714 vs 0.697、P99 0.861 vs 0.866）。
  即 PC = "暗 base + 大增益"，我们 = "亮 base + 小增益"，高光在 8bit 截断处已丢信息。

## Pitfalls

- [2026-10-20] **`passGainmap` 破坏 FBO 绑定 ⇒ 成片全黑**：`passOutput` 把 `fbo_cap_` 绑上后
  **故意不恢复**，后面 `glReadPixels` 靠"当前 FBO"读成片。我插的 pass 结尾写了
  `glBindFramebuffer(0)` ⇒ 读默认帧缓冲 ⇒ `avgLuma=0`。修法：保存/恢复 FBO+viewport，**并且**
  在 `glReadPixels` 前显式绑回 `fbo_cap_`（双保险）。
- [2026-10-20] **`nativeReadGainmap` 用错实例 ⇒ HDR 静默不生效**：拍照走**独立实例**
  `g_renderer_still`（`use_still=true`），用 `g_renderer` 会读到空纹理、返回 null 且**不报错**
  （日志 `hdr:"none"`）。修法：`thread_local t_last_submit` 记录本线程最后一次 submit 的实例。
- [2026-10-20] **两张输入纹理方向相反 ⇒ gainmap 上下错位**：`kFsFilm` 用 `vUV0` 直采 `tex_scene_`；
  `kFsOut` 带 `uFlipY=1.0` 翻转输出到 `tex_cap_` ⇒ 两者差一个 flip。用同一 UV 采样 = "上方像素的
  HDR ÷ 下方像素的 SDR" ⇒ 增益颠倒（真机症状：床面出现键盘鬼影）。修法：HDR 侧用 `(x,1-y)`。
- [2026-10-20] **统计时机晚于日志输出 ⇒ 字段恒 0**：`raw.render` 在 native 返回前就输出了，
  而 gainmap 统计发生在 Kotlin 回调的 `readGainmap()` 里 ⇒ `gmMax` 恒 0，无法区分"场景无高光"
  与"统计没生效"。修法：在 `readGainmap()` 内独立输出 `gainmap.readback` 日志。
- [2026-10-20] **`-PfilmkitHdr` 不在 gradle task inputs ⇒ 缓存错乱**：先编 debug(带 HDR) 再编
  release(不带) 会命中 up-to-date 缓存，产出**缺 `nativeEncodeUltraHdr`** 的包（`.so` 仅 9.2MB），
  HDR 静默失效。修法：`inputs.property("filmkitHdr", engineHdr)` + `inputs.dir(third_party/ultrahdr)`。
- [2026-10-20] **中文路径下 `add_subdirectory` 失效**：深层作用域里 `file(GLOB)` / `add_library`
  都解析不出源文件（`No SOURCES`），`file(RENAME)` 也失败。修法：主 CMakeLists 用 **`include()`**
  （作用域仍在 `core/engine`），全部用相对 `core/engine` 的路径。
- [2026-10-20] **libjpeg-turbo 拒绝 `add_subdirectory`**（其 CMakeLists 第 61 行主动报错）⇒
  必须 `execute_process` 在**配置期**同步 clone+configure+build；tarball 下载比 git clone 稳。
- [2026-10-20] **两个 TU 都 define `STB_IMAGE_WRITE_IMPLEMENTATION` ⇒ duplicate symbol**：
  第二个 TU 只 `#include` 头文件（stb 头默认是纯声明）复用第一份实现。
- [2026-10-20] **upstream libultrahdr 默认只写 ISO 21496-1、不写 XMP**：而 Android 相册/Chrome
  认的是 **Adobe XMP gainmap** ⇒ 必须 `UHDR_WRITE_XMP`（两个都开，多 ~1KB）。
- [2026-10-20] **`androidx.heifwriter` 要求 minSdk 28**，项目是 26 ⇒ `tools:overrideLibrary` +
  运行时版本守卫（HDR 入口都有 `SDK_INT >= 34` 检查）。
- [2026-10-20] **比色脚本必须先统一口径**：未归一化的 `log2` 值与归一化的 `[0,1]` 值不能直接
  比大小（首版 `gainmap-math-ab.py` 就这么错了，结论完全带偏）。同型于 D5 域口径坑。

## Next

1. **高光顶白**（用户已定"单独开一期"）：SDR base 比 PC 亮 1.7~1.9×，`>=240` 占比 6~9×。
   候选方向：HDR 模式单独降曝光（只影响 HDR 成片）/ 全局改 tone map shoulder（回归风险大）/
   不动。**需要用户拍板**，按铁律不许拍脑袋改。
2. **HEIC + HDR 未真机验证**：代码已接（`saveHeif` 的 HDR 分支 + `persistHdrHeic`），
   但用户本轮只验了 JPEG 4:4:4 路径。
3. **JPEG 4:2:0 + HDR 未真机验证**（平台 `Bitmap.setGainmap` + `compress` 路径）。
4. **导出 SDR 版未真机验证**（`nativeExtractSdrBase` 已编译进包，查看页有按钮）。
5. **gainmap 数学 A/B 未做完**：切换项已上，但用户只用了默认 PC 版，没做同场景双档对比。
6. **期B 遗留**：`FILM_SRC_BASELINE` 尚未写入守卫脚本（仍钉旧值 ⇒ `--splice-selftest` 会误报）；
   待重标 5 项（分离色调/黑白 `[23][24][25]`、分区饱和 `[20..22]`、光晕 `[62..65]`、
   `kFsHist`、`kFsLocal`）。
7. **HEIC UI 决策**（INDEX 挂着）：用户明确不喜欢 HEIC 的 4:2:0 色度；加提示 or 移除选项。
8. **阶段 4（ACES）/ filmic 去留 / 原厂风格引擎**：域空间已完成 ⇒ 技术门已开，可排期。

## Refs

- 报告：`docs/reports/hdr-jpegr-v0.11.53-REPORT.md`（实施与工程坑）·
  `docs/reports/hdr-acceptance-CHECKLIST.md`（验收清单，含 gainmap A/B 判据与高光场景指引）
- 工具：`tools/gainmap-math-ab.py`（两版数学 A/B，注意口径统一）
- 包：`filmkit-0.11.58-HDR.apk`（versionCode 407，已验证签名/版本/13 个 HDR JNI 符号）
- PC 一手源码：`/tmp/pc-bjzhou`（`app/src/main/cpp/jpeg_r_encoder.cpp`、
  `gallery/Jpeg444ExportEncoder.kt`、`gallery/HeicExportEncoder.kt`、`hdr/RawGainmapMath.kt`、
  `hdr/UltraHdrWriter.kt`、`ui/gallery/GalleryDetailScreen.kt`）
- 提交：`ffa1c1f`（feat HDR）· `13df792`（docs/chore，已 push）
- 官方依据：developer.android.com/media/grow/ultra-hdr/display（只按 `hasGainmap()` 切窗口模式）
