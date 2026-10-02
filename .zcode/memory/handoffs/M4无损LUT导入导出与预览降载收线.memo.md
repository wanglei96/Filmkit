---
summary: M4无损LUT导入导出与预览降载收线
created: 2026-10-01 22:05
updated: 2026-10-01 22:05
status: absorbed
---

# M4无损LUT导入导出与预览降载收线

## Summary

帧率定位实验判读 → **方案 A 落地**（拍照期预览降载 0.5× + 单帧 RAW/YUV 迁独立线程，
v0.10.93–97：MFNR 最低 fps 冷机 14→19/热机 20→23-27，单帧 2→28-30 且预览 gap 消失）；
**M4 §6.2 无损 LUT 导入导出**（.cube 1D/3D + Hald PNG + 用户库 + 分类管理，
v0.10.94–99）。滑杆跳变三轮定位到 M3 Slider 按下 snap，换相对拖动滑杆根除。
验收通过，tag **v0.10.99-m4**（versionCode 250）。

## Done

- **实验判读（四组开关）**：降噪排除（关掉 fps 曲线不变 14=14）；RCD 只占 ~160ms；
  MFNR 链路（DFT GPU ~550ms + 融合 + 上传带宽）是耗时大头。单帧两组意外走预览
  线程（口径不可比，gap 877~1038ms、fps 掉到 2）——单帧迁移优先级因此提上来。
- **预览降载**：`g_preview_scale` 进程级 atomic，submitFrame 中间链 0.5×；pass3
  渲到小尺寸 RGBA8 + `glBlitFramebuffer(GL_LINEAR)` 升采（16F 无线性过滤）。
  快门即降/合成完即升（6 个出口全覆盖 + 15s 兜底）。成片链零改动。
- **单帧 RAW/YUV 迁独立线程**：buffer 语义（拷贝 direct buffer 后 close Image，
  失败可回退预览线程重放）；`render.single_done` 诊断；顺手修 nativeStillStart
  漏 YUV 矩阵快照（BT.709 会被按 601 解码）。
- **M4 LUT 导入导出**：`CubeLut` 1D 展开成 3D（INPUT_RANGE 折进格点）+ MAX_SIZE
  64→128；`LutIO`（嗅探/Hald·Unwrapped 解包/三线性重采样/流式 writeCube/writeHaldPng）；
  `LutLibrary` 用户库（files/luts 原文件字节存档、u_ 前缀 id、导入/删除/lut.import
  等日志、ConcurrentHashMap 缓存）；UI（导入 pill、归类弹窗含"新建分类并归入"、
  分类重命名、导出 .cube/Hald、删除确认）。
- **滑杆三轮**：①引擎 bindLut clamp 1.0→1.5（100–150% 外推区间此前整体无效）；
  ②拖动中不写 prefs（`setLutStrength` 直通 + `lutStrengthFromPrefs` 哨兵防回拉）；
  ③ **RelativeSlider** 替换 M3 Slider（按下即 snap 是跳变根因，诊断日志实锤
  state 收到 1.051）。
- 提交 62e5e7e / edfdb6a / a6a153d / 954f5b6 / a682a90 / 993d6f3 / 9d3a335 /
  d6c3d3f，全部已 push；单测全绿。

## Decisions

- [2026-10-01] **无损口径**（对照 PC）：.cube float 解析不重采样不量化、DOMAIN
  原样保留（shader 按 dmin/dinv 归一）、原文件字节存档。PC 的 >65→33 重采样是
  其有损点，不学；唯一妥协 = 超引擎上限（Hald L12=144³）三线性重采样到 128³。
- [2026-10-01] **预览降载落点 = 引擎中间纹理**（非 Camera2 流）：帧级切换无需重建
  session；16F 无线性过滤 → 输出 pass 先渲到小尺寸 RGBA8（OETF 后 display 域）
  再 blit LINEAR 升采，避免 NEAREST 像素块。
- [2026-10-01] **单帧迁移用 buffer 语义**（25MB 拷贝后 close Image）——回退重放
  的前提（img 已 close 不能二次消费），与 MFNR copyRawAndClose 同构。
- [2026-10-01] **LUT 强度滑杆 = 相对拖动**（按下不跳、增量累加）：绝对定位滑杆在
  拇指微调场景必然误跳，专业相机 App 标准交互。
- [2026-10-01] **拖动中不写 prefs**：直通链路 post 到渲染线程写 filmParams[34]，
  prefs 只在 onValueChangeFinished 写一次；哨兵保证每帧 prefs 同步不拉回直通值。
- [2026-10-01] **用户 LUT 存档原文件**（无损闭环：导出可取回原样），id 加 u_ 前缀。

## Pitfalls

- **M3 Slider 按下即 snap 到手指位置**：拇指附近"按住继续微调"必误跳（指腹落点
  偏目标方向几 dp）——临时诊断日志 `lut.strength_drag` 抓到 state=1.051 实锤，
  纯靠猜修了三轮没中。凡"数值固定跳 N"类 UI 报障先上回调日志再修。
- **foundation `drag()` 原语真机不产生拖动回调**（0.10.98a 整条滑杆拖不动）：
  手势语义不可控时手写 `awaitPointerEvent` 循环 + `positionChangeIgnoreConsumed`
  （位移不受他人消费影响），up/id 不匹配退出自愈。
- **X300 RGBA16F 无线性过滤**的新对策场景：降分辨率后放大显示必须中间 RGBA8
  （OETF 后 [0,1] 无截断）+ blitFramebuffer(GL_LINEAR)，NEAREST 直放 = 像素块。
- **暗光下空闲 fps 17–21 是曝光时间封顶**（50ms 曝光 → ≤20fps），不是回归——
  帧率对比必须看 blackEv/场景亮度（16:37 暗场 20fps vs 21:18 亮场 30+）。
- **prefs"变化才写"守卫会吃默认值**：lutStrengthPref 哨兵初值恰等于 prefs 默认值
  → 数组 [34] 恒 0 → 强度全失效；后改 `lutStrengthFromPrefs`（上次读到的 prefs 值）
  做变化检测，直通值不被每帧同步拉回。
- **手势内状态用局部变量累加**：rememberUpdatedState 的回流慢一帧，同一手势内
  连续增量事件必须基于本地累加值。

## Next

1. **M5 诊断与基准**（开发计划下一里程碑）：逐 pass GPU 计时、成像会话摘要、
   一键诊断包、性能基准脚本 + golden image 接 CI。
2. 渲染链后段 GPU（`passMs` 711~2021 大头；动前必须画质 A/B，降级）。
3. .3dl 导入（M6 可选）；多帧堆栈（§0-D12，M6 后评估）；16bit TIFF（M6 可选）。
4. 留观：温控嫌疑（13:07 空闲 17fps 出现过一次未复现）；`mfnr.mode=2` 却
   `trigger=false` 的成因未查；用户导入大尺寸 LUT 时留意 `lut.load err`
  （128³ 纹理未在真机验证）。
5. 验收口径照旧：出包前 `tools/check-diag-fields.py`，分析日志前
   `tools/check-log-json.py`。

## Refs

- 版本：**250（`0.10.99-m4`）**，验收 tag **v0.10.99-m4** 已 push；
  APK `filmkit-0.10.99-m4.apk`
- 提交：62e5e7e（方案A）/ edfdb6a（LUT 导入导出）/ a6a153d（clamp+Toast）/
  954f5b6（滑杆+分类）/ a682a90（直通+诊断）/ 993d6f3（RelativeSlider）/
  9d3a335（手写手势循环）/ d6c3d3f（删诊断）
- 代码：`app/src/main/java/com/filmkit/app/lut/`（CubeLut / LutIO / LutLibrary）、
  `ui/CameraScreen.kt`（LutSheet / RelativeSlider / StrengthRow）、
  `core/engine/src/render/FrameRenderer.cpp`（preview scale / blit / bindLut 1.5）、
  `jni/Bridge.cpp`（use_still / setPreviewScale）、`camera/StillRenderClient.kt`
  （单帧 buf 入口）、`CameraController.kt`（setLutStrength / 预览降载触发链）
- 关键实测：MFNR 冷 19 / 热 23–27、单帧 28–30 无 gap；暗光空闲 17–21（曝光封顶）
- PC 参照：/tmp/photoncamera `LutConverter.kt`（重采样到 33 是其有损点，未学）
