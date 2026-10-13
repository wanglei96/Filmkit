---
summary: 对标PhotonCamera与方向链路修复
created: 2026-09-24 10:20
updated: 2026-09-24 10:20
status: archived
---

# 对标PhotonCamera与方向链路修复

## Summary

本窗口从用户报的"室外整体偏暗 + 天空饱和比 DNG 明显太高"出发，先做量化诊断，随后按用户指定把**色彩基准从 libraw 换成 PhotonCamera 同机位成片**并据此重标定高光去饱和与自适应影调（v0.6.38）；再修方向链路——**改了五版才收敛**（v0.6.39→44），根因是两个独立缺陷：旋转语义取错（`Display.getRotation()` 与设备物理旋转相反）与画幅裁切取错方向源；同时按用户要求规整了全部项目文档（删废弃目录、重写 README、补写开发计划 18 个版本、给调研目录加索引）。**方向链路尚未收尾：横拿时取景框仍是竖的、预览只看得到画面中央（WYSIWYG 不成立）**，正解是照抄 PC 的 UI 旋转方案。全程未打 tag，等真机验收。

## Done

- **诊断（两个基准、两轮）**：① DNG+libraw 同亮度配对 → 我们**暗部 C\* 只有参照的 56%、rel 低 40–60%**，且缺口随亮度单调恶化（参照亮度 75–90 段比值 0.80、0–15 段 **0.17**）；② 换成 PhotonCamera 同机位成片后，用户的两条反馈**全部坐实**——天空 C\* 19.20 / sat 0.232 vs PC **8.45 / 0.114（2.3×）**，全图 P50 161.9 vs PC **196.9（低 22%）**，暗部 rel 0.086 vs PC 0.181
- **降噪 A/B（用户实拍 090754 开 / 090807 关）**：暗部 C\* 1.84 vs 2.45 → **降噪抹掉 24.8% 的暗部色度**（rel 29.6%），与离线在 libraw 域测的 25%（P0 11% + P4 15%）吻合
- **v0.6.38-m4 色彩重标定**（`80dade3`）：① 高光去饱和区间 **(0.85,1.30) → (0.40,0.75)**——判据是线性域 max 通道，天空反推 `n0≈0.59`，**旧区间在实拍里 w 恒为 0、从未触发过**；逐像素反推+模拟验证：天空 C\* 19.13→**8.02**、sat 0.232→**0.100**、luma 198→218.9（PC 8.46/0.114/222.8），中间调（n0=0.177）w=0 不受影响 ② `kTarget` 0.26→**0.38**、`kMax` 1.6→**1.9**（该场景 gain 只到 1.237，卡住提亮的是目标值而非上限）
- **方向链路五版**（详见 Pitfalls，这是本窗口最大的教训）：`v0.6.39` 改 `OrientationEventListener` 修"`defaultDisplay.rotation` 快照不更新"；`v0.6.40` 预览旋转恒 0（修 v0.6.39 引入的预览多转 90°）；`v0.6.41` **旋转语义修正**（改设备物理步进）+ 抄 PC 的 gravity 平放过滤 + 新增 `OrientationStepTest`；`v0.6.42` **画幅裁切与 DNG 方向改跟设备姿态**（真机日志 `photo.crop srcA:1.328 dstA:0.750` 定案）；`v0.6.43` 转窗口（**错误方案**）；`v0.6.44` **回退转窗口**、只保留成片侧修复
- **文档规整**（`ec06019`）：删 `handoff/` 整棵目录（40 文件、未入 git；独有内容 12 张 LUT 审计图表 + 19 张历史样片先移到 `test_temp/archive/`）；重写 `README.md`（原写"当前进度 M0 骨架"）；`docs/开发计划.md` 补 **M4-9** 一节覆盖 v0.6.23→v0.6.41 共 18 个版本；两个撞名的"取证清单"改名（`07-`/`08-`）；新增 `docs/research/README.md` 索引（分层说明 + 3 个过时点 + 2 个独有结论）

## Decisions

- [2026-09-24] **色彩基准 = 同机位 PhotonCamera 成片**（用户指定："这才是要对标的，不用管相册 DNG"）。libraw / 相册 DNG 都不是标尺：前者与我们的链路差着 LSC（角落增益 5.1×，libraw 不做）、CCM（ForwardMatrix vs ColorMatrix）、demosaic（RCD vs AHD）三条，且**它的暗部"色度"里混着彩噪**（不降噪）
- [2026-09-24] **高光去饱和的判据要用 max 通道 + 反推实际值**：蓝色天空 luma 只有 198/255 却因 B 通道高（线性 0.883）而 `n0=0.59` 命中；暖色亮部 luma 更高却只擦到边——这个方向正是想要的。**改这类区间前必须先反推判据点在真实成片上的取值**，否则会像旧区间那样"写了却从未触发"
- [2026-09-24] **方向：`orientationStep` 必须是设备物理旋转步进**（0=竖/1=顺时针 90°/2=倒竖/3=逆时针 90°），对齐 PC 的 `captureRotationDegrees/90`；引擎与 `focusAt` 按 `(sensorOrientation/90 + step) % 4` 消费它
- [2026-09-24] **成片裁切比例跟随设备姿态，与窗口方向解耦**（v0.6.44）；预览取景框仍跟随窗口。代价是横拿时 WYSIWYG 暂不成立
- [2026-09-24] **相机页不转窗口**：正解是 PC 的做法——GL 预览渲染器直接收设备旋转（`glSurfaceView.setDeviceRotation`）+ UI 用 `Modifier.autoRotate` 整体旋转并在 90° 时**交换布局约束**（`PuzzleScreen` 外，`CameraScreen.kt:2577` 起）
- [2026-09-24] **`docs/research/20260922-…原始报告.txt` 不可删**（原判"中间产物"已改判）：它的 `lut_domain` 段是全库唯一记录——我们 42 个 `.lut` 与 `~/Downloads/LUT/滤镜.cube/*.cube`（64³）**逐点一致**（maxd 0.0020）、与 PC 同名 `.plut` **78–90% 分量不同** ⇒ "同一个 LUT 在 PC 上更浓"的对照**前提不成立**。已改名 `90-工作流原始转储-未整理.txt` 并在索引里标红

## Pitfalls

- **`Display.getRotation()` 的语义与设备物理旋转相反**（文档原文：the angle is the rotation of the **drawn graphics**, which is the **opposite direction** of the physical rotation）。竖拍(0)与倒竖(2)下两种语义**恰好重合**，所以这个 bug 从 v0.6.37 之前就藏着，只有横拍差 180°——**"竖拍正确"会掩盖一切，方向类判据必须四姿态全验**（已固化为 `OrientationStepTest` 4 断言）
- **不要用 `LocalConfiguration.orientation` 判断设备姿态**：它反映的是**窗口**方向；系统"自动旋转"关闭时窗口与设备姿态**解耦**，横拿拍会被按竖幅裁（真机 `photo.crop dstA:0.750`）
- **SurfaceView 的内容不随 View 变换旋转**（它是独立图层，View 旋转只改它的位置/形状）→ 想让预览内容转，必须让 GL 自己转（PC 的 `setDeviceRotation` 正是为此）
- **转窗口是错方案**：横屏只剩 ~347dp 高，顶栏+参数+快门吃掉大半 → 取景框缩成 **524×393**（日志 `preview.frame dstW:524 dstH:393`）；且窗口转了 STMatrix 不变 → 预览内容相对屏幕躺倒。两个症状同一个错误方案
- **先看日志再动手**：本窗口两个独立缺陷（旋转语义 / 画幅方向源）被混成一个"方向不对"猜了三版；`photo.crop` 那行日志**本来就在代码里**，一取即分清
- **JPG 与 DNG 是两条独立的方向链路**：JPG = 旋转像素 + EXIF 恒 1；DNG = 存传感器原始像素 + 用 EXIF 表达方向。改一处必须同时检查另一处（`DngCreator.setOrientation` 曾只按 `sensorOrientation` 恒写 6）
- **离线复现的域差异会掩盖真相**：本窗口用 libraw 域测 P4 只抹 15%，而真机 A/B 显示降噪整体抹 24.8% —— 趋势可信、绝对值不可照搬
- **`weight(1f, fill=false)` + `aspectRatio` 在空间不足时不会报错**，只会把取景框缩得很小（不崩但不可用）

## Next

1. **UI 旋转方案（最高优先，照抄 PC）**：`Modifier.autoRotate`（整页旋转 + 90° 交换约束）+ 预览内容旋转（对应 PC 的 `setDeviceRotation`，走引擎 rot 参数）。**第 2 步必须先读清 PC 预览 shader 里 `deviceRotation` 的用法再动 `passOES`** —— v0.6.39 就是在这里翻的车（与 STMatrix 叠加导致预览错乱）。做完 WYSIWYG 才真正成立
2. **暗部提色**：我们暗部 rel 只有 PC 的 52%，其中降噪占 24.8%；剩余候选是外观层对暗部加权不足（暗部 +11% vs 天空 +17%）与 CCM 负值截断（`clamp(rgb,0,64)`）。**注意乘性放大白平衡残余的历史事故**（v0.6.14 全局 ×1.5），要单独一轮做
3. **v0.6.38 色彩改动仍未经真机验证**：去饱和 (0.40,0.75) 与 `kTarget 0.38/kMax 1.9` 只有离线模拟（天空 C\* 命中 PC），需真机确认天空是否过曝死白、暗部是否因提亮 1.5× 而彩噪变明显
4. **俯拍冻结阈值待验**：`isNearFlat`（`|gravity.z| > 7.5`，抄 PC）在手机与地面夹角 <40° 时冻结方向，俯拍可能朝向不跟随；用户反馈不对就把阈值调高

## Refs

- 版本：**v0.6.44-m4（versionCode 124）**，`filmkit-0.6.44-m4.apk`（根目录，`*.apk` 已 gitignore）；**本轮未打 tag**（等真机验收）；远端仍是 5 个 tag：v0.5.27-m2 / v0.5.47-m3 / v0.5.48-m3 / v0.6.8-m4 / v0.6.23-m4
- commits：`80dade3`(v0.6.38 色彩) → `43e44d7`(v0.6.39 方向) → `ec06019`(文档规整) → `455ece0`(v0.6.41) → `bcdd04a`(v0.6.42) → `ecf4eae`(v0.6.43) → `a78e31c`(v0.6.44)
- 代码：`core/engine/src/render/FrameRenderer.cpp`（`kFsFilm` 高光去饱和 `smoothstep(0.40,0.75,n0)` / `updateAutoGain` 的 `kTarget 0.38`、`kMax 1.9`）；`app/.../camera/CameraController.kt`（`orientationToStep`、gravity 平放过滤、`DngCreator.setOrientation` 随姿态）；`app/.../ui/CameraScreen.kt`（`guideAspect` 跟窗口 / `captureAspectValue` 跟姿态）；`app/src/test/java/com/filmkit/app/camera/OrientationStepTest.kt`（**本窗口新增**）
- 工具（都在 `test_temp/`，已 gitignore）：`pair_diag.py`（逐像素配对 + 同亮度 C\*）、`vs_pc.py`（对标 PhotonCamera + 降噪 A/B）、`p4_chroma_check.py`（P4 抹色量化）、`p0_check.py`（P0 复现）、`sim_chain.py`（链路复现 + 旋钮扫描）、`make_compare.py`（并排对比图）
- 文档：`README.md`（**本窗口重写**）、`docs/开发计划.md`（新增 M4-9）、`docs/research/README.md`（**本窗口新增**索引）
- 外部参照：PhotonCamera 本地副本 `/tmp/photoncamera`（`2557a1d`）——`utils/OrientationObserver.kt`（角度映射 + gravity 平放过滤）、`gallery/ExifWriter.kt`（写 `ORIENTATION_NORMAL`）、`viewmodel/CameraViewModel.kt:2995`（`(sensorOrientation + deviceRotation) % 360`）、`ui/camera/CameraPreviewGL.kt:230`（`setDeviceRotation`）、`ui/camera/CameraScreen.kt:2577`（`Modifier.autoRotate`）
