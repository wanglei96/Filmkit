---
summary: 预设覆盖层、删除切图与HDR灰带
created: 2026-11-14 18:42
updated: 2026-11-14 18:42
status: active
---

# 预设覆盖层、删除切图与HDR灰带

## Summary

本窗口四件事：① 修「标准」预设改动杀进程就还原（覆盖层漏了硬编码预设）；② 相册删除后留在查看页切下一张 + 过渡动画 + 消除闪现；③ HDR gainmap 对齐 PhotonCamera 源码（纯比值 + LUT 补偿 + 曲线延伸），中灰 ratio 从 2.23× 回到 1.01×；④ 紫边判据扩到品红象限（85→11）。
**遗留未解决**：灯面边缘在 HDR 下有明显灰带，把延伸斜率从 4.0× 降到 2.66× 再到 1.414× 三次都"一点用都没有"——说明根因不在 gainmap 斜率，下个窗口不要重复这条路。

## Done

- **0.13.54/55 预设覆盖层**：「标准」是 `CameraPresetApply.kt` 里硬编码的 `CAMERA_STANDARD_PRESET`，**不在 `builtin.json`**，而 `applyBuiltinOverrides` 只对出厂列表里的 id 生效 ⇒ 用户对它的修改写进了 prefs 却永远匹配不上。新增 `BuiltinPresets.forCamera()`（标准 + 用户 + 内置全走覆盖层），`resolveCameraPreset` 先查列表再回落硬编码（`savedId` 为空时也查——选中标准会 `remove("camera_preset_id")`）。日志铁证：`save_slots slots=2` 后同一毫秒 `save_full target.slots=0`。
- **0.13.56 胶卷位回落**：相册改覆盖层后相机页常驻，`last_photo_uri` 被清掉时本地 state 不更新 ⇒ 缩略图占位、点开提示失效。加 `OnSharedPreferenceChangeListener` 同步 + 解码失败 3 次后自愈清 prefs；`onOpenLastPhoto` 不再把死链塞进查看页。
- **0.13.57~60 删除交互**：删除后**留在查看页切下一张**（`onDeleted(removed)` 只摘掉该下标，删最后一张夹到前一张，删空才退出）；同页换图走 PV-1 交叉淡入（`shownPage == page` 判据）；空列表/越界一律当过渡态不报错（不再闪"照片已失效"）；闪烁根因是 `visibleBitmapFor` 在 uri 变更时先画了新图缓存档、随即淡入又切回旧图，加 `samePageSwap` 单帧判据后消失。
- **0.13.61 HDR gainmap 重写**：`gainTrue` 删掉 `anchor = uDispMid/uSceneMid`（显示域中灰 ÷ RAW 域中灰 ≈ 2.4，给全图灌常数增益）与重复的 `uExposure`，改为纯比值 + `lutGain = (sdrLin+ε)/(baseLin+ε)` 把 LUT 亮度差从 HDR 侧除掉。实测 `gmMean` 0.79 → 0.51（PC 同场景 0.509），HDR p50 回到 base。
- **0.13.61 filmic 保留 headroom**：`filmicTone` 在白点之上按 `1 + 0.828·overEv` 线性外推（SDR 侧在 `kFsOut` 入口 `min(c,1)` 截断，成片逐位不变）。
- **0.13.63 紫边判据扩象限**：`defringe` 原本只认 `br0 = B−R > thr`（紫 B>R>G），灯面边缘实测 R>B>G ⇒ `br0 = −30` 被结构性排除。改为 `mag0 = min(R−G, B−G)`（品红强度），实测峰值 R−G 85 → 11、`>20` 像素 0 个。

## Decisions

- [2026-11-14] **「标准」也必须走进覆盖层**：它是硬编码出厂定义，但语义上与内置预设同类（可改不可删）；用 `BuiltinPresets.forCamera()` 单独给拍照页，不并进 `all()`（编辑器/查看页/设置页都读 `all()`，多一条空配方会污染）。
- [2026-11-14] **删除后留在查看页**：列表摘掉被删下标，`currentPage` 原地显示原下一张（零滚动）；删最后一张才夹到前一张；删空才回来源页。
- [2026-11-14] **同页换图 ≠ 翻页**：翻页必须丢旧像素（防错图），删除换图可以保留旧图当淡出底（有 `under` 兜底、不会黑）。判据 `shownPage == page`。
- [2026-11-14] **gainmap = 纯比值 + LUT 补偿，无 anchor**：对照 `bjzhou/PhotonCamera` 的 `hdr/GpuReferenceGainmapProducer.kt`（RAW_LUMA_RESIDUAL 片元）与 `hdr/RawGainmapMath.kt`（`encodeLuminance`）：`ratio = (hdrLuma+ε)/(sdrLuma+ε)`，`targetHdrLuma = (hdrLuma+ε)·lutGain−ε`，`[0.25,4]`，offset 1e-4。**同域是同一条曲线**，所以中灰 ratio≡1 是自然结果，不是调出来的。
- [2026-11-14] **defringe 判据用 `min(R−G, B−G)`**：紫（B>R>G）与品红（R>B>G）的共性是两通道都高于 G；单看 B−R 会把品红整个排除。零误伤仍靠③邻域色差标准差 + ④亮度梯度。

## Pitfalls

- [2026-11-14] **⛔ HDR 灰带：调 gainmap 斜率无效（三次实证）**。同场景灯面边缘在 HDR 下的灰带，把 `exp2(overEv)`（4.0×）→ 线性 0.828/EV（2.66×）→ +0.5 stop 封顶（1.414×）三次改动后用户反馈都是"一点用都没有"。量到的 ratio 剖面确实是 30px 内 4.0→1.18 单调降，但改成 1.414 后视觉无变化 ⇒ **根因不在 gainmap 的增益幅度**，可能在：SDR base 本身那条 60px 物理渐变（灯罩边缘，r=140→200 亮度 204→130）、手机 HDR 显示的 tonemap 口径、或 gainmap 上采样与 base 的配准。下个窗口先离线复现"Android 真机 HDR 显示"的口径，别再先动斜率。
- [2026-11-14] **PhotonCamera 的 HDR 参考不是"曲线自身输出"**：`RawHdrReferenceMath.kt` 的 `solve()` 对引擎曲线采 1024 点，从 0.305 起找**切线能到白**的接点，用二次式保证 C1 连续、scene white 处到 `√2`（+0.5 stop），之后按端点切线线性延伸。它和 SDR 是同一条引擎曲线 + 显式延伸，不是"未 clamp 的曲线值"。
- [2026-11-14] **darktable filmic spline 在白点之外是回落的**（离线 `filmic-spline-ref.py`：e=1.02→0.998 / 1.10→0.949 / 1.20→0.768 / 1.30→0.417 / 1.50→−1.001）⇒ 不能靠"放开 e 的 clamp"拿 headroom，必须显式构造延伸。
- [2026-11-14] **`anchor = uDispMid/uSceneMid` 是域错配**：`uDispMid` 是显示域中灰（≈0.16），`uSceneMid = ae_p50_` 是 RAW 域中灰（≈0.066），相除 ≈2.4 被当成"域换算系数"乘进 HDR 参考 ⇒ 全图 2.23×。`film_src` 本来就是显示域（`pass2_film 0.106` ↔ `pass3_out 93` 反解 0.110），不需要任何换算。
- [2026-11-14] **`color_smooth` 3×3 中值救不了 3~5px 的宽带**：它是 1px 半径，对灯面边缘那圈品红无效；真正的解是判据象限（defringe）。用户实测"色差中值滤波无效、关紫边抑制紫边更强"正是这个分工的证据。
- [2026-11-14] **相册覆盖层带来的同类风险**：相机页常驻后，任何"外层改了 prefs"都不会反映到相机页 state。已修 `last_photo_uri` 一处，其余（LUT、预设、设置页开关）如果发现"改了没生效"优先查这条。

## Next

1. **HDR 灰带（唯一未解）**：先离线复现 Android 真机 HDR 显示口径（gainmap 应用 + 屏幕 tonemap），确认灰带是像素层的东西还是显示层的东西；再对比 SDR/HDR 同图在灯面边缘的差异。**不要**再调 `overEv` 斜率。
2. 灯面白（SDR 顶白 247，PC 199）：白点 4.56 把所有高光压进 220-247；用户暂不允许调 shoulder/白点，`auto_white_pt` 是否该介入待定。
3. 旧账：编辑器仍走整页路由（退出回相机仍重建一次）；Spektrafilm 引擎 + 预设标定；理光曲线重标（`recipe-fit.py`）；绿植/绿段对齐；AE 高光保护效果。
4. 设备 adb 长期掉线（多轮 `no devices/emulators`）：安装/取日志需用户配合，`dumpsys package` 验版本仍要照做。

## Refs

- 包：`filmkit-0.13.64-HDRHALF.apk`（772/0.13.64）；回退 `filmkit-0.13.63-MAGENTA.apk`
- 提交：`0f16ff1`（预设）、`1f07f60`（相册删除）、`f9ff090`（HDR）已 push dev
- 代码：`BuiltinPresets.forCamera()`、`CameraPresetApply.resolveCameraPreset`、`CameraScreen`（`DisposableEffect(prefs)` 监听 + 缩略图自愈）、`PhotoViewScreen`（`onDeleted(Int)`、`samePageSwap`、`LaunchedEffect(pageCount)` clamp）、`FrameRenderer.cpp`（`filmicTone` 白点延伸、`kFsGainmap`/`gainTrue`、`kFsOut` 入口 `min(c,1)`、`kFsDefringeApply` 的 `mag0`）
- 对标源码：`https://github.com/bjzhou/PhotonCamera`（`hdr/GpuReferenceGainmapProducer.kt`、`hdr/RawGainmapMath.kt`、`raw/RawHdrReferenceMath.kt`、`raw/RawEngineTonePass.kt` 的 `applyHdrExtendedCurve`）
- 工具：`tools/hdr-baseline-eval.py`、`tools/filmic-spline-ref.py`、`tools/validate-shaders.sh`
- 样片：`test_temp/FILMKIT_20261114-183015.jpg`（灯面，213KB gainmap 1/2）；日志 `~/Downloads/vivo办公套件/filmkit-logs-20261114-183051.jsonl.txt`
