---
summary: HDR gainmap与高光判据归因
created: 2026-11-13 15:40
updated: 2026-11-13 15:40
status: active
---

# HDR gainmap与高光判据归因

## Summary

一个窗口里先收掉预览体感的三件 UI（飞入重做 / 三分线入框 / 页面切换动画 / 相册覆盖层），
后半天全部投在 HDR 灯盘：**gainmap 改回标准乘性比值**（推翻 0.12.230 的加性"标准语义"），
沿途修掉三个真 bug（gainmap 缺 exposure、pw999 读错实例、log2 负值被当无统计）。
灯面白的根因定到 **filmic 白点 2.95 把融合后 scene 1.52 映射到 0.8**，不是融合、不是填白；
采集端高光保护则被证明**判据源（预览统计）不可用**。隔张紫已消失，残留 pw999 抖动。

## Done

- **0.13.41 飞入重做**：起点 = 取景框真实像素（`boxW/boxH`，删掉 `screenWidth*0.92` 与
  `1.333`），`FillBounds` 保证起点与预览重合；1.04x 浮起 → 缩到胶卷位（终点 scale =
  `44dp/取景框宽`）；白边 draw 阶段画（起飞满亮、落格前 30% 淡出）；落格后把该帧留在格子
  等成片（不再立刻转圈）；恢复预览先压暗 0.34 再淡出。
- **0.13.42 三分线**：从整屏 `matchParentSize` 挪进取景框 Box，4:3/16:9 都贴边。
- **0.13.43 页面切换**：`Crossfade` 会让两层不透明页面在中间帧混合（整屏闪一下）→ 改
  **单页替换 + 新页从黑底淡入 200ms + 24dp 上滑**，同一时刻只有一层。
- **0.13.44 相册改覆盖层**：`gallery/viewer` 变成 `AnimatedVisibility` 滑上来的覆盖层，
  相机页常驻不销毁 ⇒ 返回不再重建相机（原先 EGL + openCamera + session ≈1~2s 黑屏）。
- **0.13.46** 修「成片后首次点胶卷位看大图不能左右滑」：pending 轮询原先把
  `viewerUris = listOf(u)`（单张，Pager 只有一页），改为查完整相册列表并定位索引。
- **0.13.45~0.13.53 HDR**：
  - `gainTrue` 改回**标准乘性比值**（`hdrDisp/sdrLin`，clamp [1,4]），删掉加性门限；
  - gainmap pass 补 `uExposure`（SDR 已乘曝光，HDR 参考没乘 ⇒ EV 大时整张退化 ratio=1、
    第二张发紫）；
  - `pw999` 改从**预览实例**读（原读 `g_renderer_still` 恒 0）；
  - `sceneHighlightEv()` 有效性判据 `>0` → `!=0`（log2 值可为负，负值被当"无统计"⇒
    expDv 在 0 与 -3.3 之间**隔张交替**，这就是"间隔一张必紫"）；
  - 撤掉我加的 1EV 欠曝硬限（自创、砍合法逆光欠曝），保留 `exposure_ev` 2EV 总限幅；
  - 诊断字段：`pw999/pw999sr/hasSr/hasPv/p999pv/dom` + 每次拍摄一条 `hl_stat`。

## Decisions

- [2026-11-13] **gainmap = 标准乘性比值**：对照 `third_party/ultrahdr/lib/src/gainmapmath.cpp:775`
  （`gain = y_hdr/y_sdr`，clamp `[min_content_boost=1, max=hdr_white/sdr_white]`），规范里
  没有「中灰以下 ratio 恒 1」，也没有白点门限。0.12.230 的加性写法实为自创。
- [2026-11-13] **不设欠曝硬上限**：相机工程的通行做法是「判据带面积权重」，不是钳 EV；
  安全钳（总提亮 2EV）只做兜底。
- [2026-11-13] **相册/大图 = 覆盖层**（相机常驻）：整页替换会连 SurfaceView 一起销毁，
  返回要重建 EGL + 相机 ≈1~2s 黑屏。
- [2026-11-13] **跨页动画用单层淡入**，不用 Crossfade（两页共存时中间帧混合 = 整屏闪）。

## Pitfalls

- [2026-11-13] **`pw999/sw999` 是 log2 值（EV），可为负**。用 `> 0` 判有效性会把"高光低于
  中灰"当成"没统计"⇒ 一张压 3.3EV、一张不压，表现为隔张紫（13:57:01 `hlEv=-99` /
  13:57:27 `hlEv=4.948` / 13:57:56 `hlEv=1.027`）。0.0 才是"没写过"的哨兵。
- [2026-11-13] **单锚点线性换算近似不了 filmic 的 S 曲线**：固定 anchor 2.56 与"本张
  stillMid/aeP50=0.42"都让整张 ratio 顶满 4x（中灰对了暗部就崩）。HDR 参考必须是
  "同曲线、更宽白点"渲染出来的第二张图，不能用 `scene × 常数` 冒充。
- [2026-11-13] **预览统计不能代表 RAW 高光**：`p999pv=0.68`、`dom=2.0` ⇒ 换算 0.89EV；
  同张离线复算融合后灯面 scene=1.52（≈3.04EV），**低报 2.15EV**。`dom` 是"中位比"，
  而 ISP tonemap 在高光段压 7~14×，中位比修不了肩部。
- [2026-11-13] **gainmap 必须与 SDR 同 exposure**：SDR base 已乘 `uExposure`，参考帧是
  曝光前场景值，不补 ⇒ EV 一大 ratio 全 <1、整张退化成 1.0。
- [2026-11-13] **`Crossfade` 不等于"过渡动画"**：两层不透明全屏页共存时中间帧是
  "半页 + 半页"，观感是整屏闪一次。

## Next

1. **新窗口重拍灯盘，用干净 burst/日志查 `pw999` 抖动**（历史值 0.10~5.65，同一场景跳
   4EV 以上）——这是"有的张压得多、有的不压"的直接原因。
2. 高光判据要么**加面积项**（引擎多返回"超白点像素占比"），要么**换源**（预览统计已被
   证明不可用）；两条都要先拿样片定阈值。
3. 灯面白（SDR）：融合后 scene 1.52 → filmic 白点 2.95 → 输出 0.8（8bit 232），PC 同场景
   0.55（139）。用户暂定**不许调 shoulder/白点**，`auto_white_pt`（已有自动档）尚未验证。
4. 旧账：编辑器仍走整页路由（退出后回相机仍会重建一次）；Spektrafilm、理光曲线重标、
   绿植/绿段、AE 高光保护效果。

## Refs

- 包：`filmkit-0.13.53-NEGEV.apk`（设备 0.13.53/761）、回退 0.13.52
- 提交：`b57dc7c`（HDR 修复组）、`7dd8804`（胶卷位滑动）、`8cde949`（相册覆盖层）、
  `73e76ed`（飞入重做）
- 代码：`FrameRenderer.cpp`（`gainTrue` 8282 / `kFsGainmap` 8214 / `kFsMfnrFuse` 3230 /
  `updateFilmicEV` 19258）、`Bridge.cpp:1060`（stats）、`CameraController.sceneHighlightEv()`
  236、`ExposurePlanner.plan()` 131、`CameraScreen.kt`（飞入 1075/1417、三分线 1427）
- 工具：`tools/hdr-baseline-eval.py`、`tools/validate-shaders.sh`、`files/burst_dump`
  哨兵（导出 4 帧 DNG + `files/burst/fN.raw`）
