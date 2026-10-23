---
summary: M档裁切、相册多选与ISZ收口
created: 2026-10-22 22:15
updated: 2026-10-22 22:15
status: absorbed
---

# M档裁切、相册多选与ISZ收口

## Summary

0.12.34→0.12.42 修完用户报的三个 bug（M 档非原生焦段裁切从未生效、相册缺多选批量删除、左下角缩略图偶发丢失），并把「保持原分辨率」这条线从 PC 源码一路查到 X300 的 vendor tag 与 camera id，最后**拍板不做**（2x 成片是真实像素，插值只花存储不增信息）。途中确认 vivo 的系统回收站对第三方不可用，删除改成"重闸门 + 删后核实"。收尾时发现并修掉镜头表的真缺陷：X300 有三个 23.6mm，其中两个是逻辑相机。

## Done

- **M 档非原生焦段裁切**（0.12.34）：根因 = HAL 对 `RAW_SENSOR` 流不执行 `CONTROL_ZOOM_RATIO`（预览/YUV 都执行）。`capture()` 冻结 `stillZoom`（与 `stillRot`/`stillAspect` 同型），交付前居中裁 `1/z`；尺寸计算收进纯函数 `stillCropSize`（`StillCropTest` 6 条）。JPEG 在 `saveJpeg` 里与画幅**合成一次裁**；HEIF 在 `queueHeifSave` 的 saver 任务里 `cropForZoom`（行拷贝 + 同步裁 gainmap）；两条路 + gainmap 全覆盖。成片 = 裁切区原始像素，不插值。
- **相册多选 + 批量删除**（0.12.34→0.12.36）：长按或「选择」进多选、点格切换、每段日期「全选本日」、顶栏「全选/取消」；二次确认对话框（选中=全部时**红色标题 + "全部删除"按钮** + "这会清空相册当前显示的全部照片"）+ API 30+ 系统确认。删除后 `mediaStillExists` **核实**（单条 query + `is_trashed` 列），没删掉的留在列表并提示"可重试"。
- **删除不做回收站**（0.12.36）：先试 `createTrashRequest` 想让照片进系统「最近删除」，日志 `gone:true` 但系统回收站为空 ⇒ vivo 把它落成真删。用户拍板不做恢复、也不自建回收站；回到 `createDeleteRequest`，文案统一"删除后无法恢复"。系统回收站目录是 MediaProvider 内部实现，第三方不可写。
- **缩略图丢失根因**（0.12.34）：`saveJpeg` 在 `insert` 之后、**写字节之前**就发布 `lastPhotoUri` ⇒ UI 400ms 轮询可能读到空文件，解码失败一次即永久空白（`LaunchedEffect` 只在 URI 变化时跑）。改成「写盘 + `IS_PENDING=0` + `writeExif`」全部完成后才发布；解码退避重试 3 次 + `camera.thumb_failed` 日志。删掉胶卷位那张时清 prefs，且**回落相册最新一张**（不再显示 ▦）。
- **查看页路由**（0.12.35→0.12.37）：胶卷位直开大图（先按相册口径查列表定位，可左右滑）；顶栏「相册」按钮移到返回键右侧、日期改 Box 叠层**严格居中**；`backTarget` 增 `viewerFrom`，**相册返回直回相机**（原先跳回上次查看页）；删除成功清空 `viewerUris`（已删图不再被弹回）。
- **ISZ 调研**（0.12.38→0.12.41）：查清 PC 的 `Restore digital zoom resolution` = 拍摄时记物理镜头输出尺寸 + 渲染时 Lanczos3 / MGC RAISR / guided upsample 放大回该尺寸（默认关）；X300 是 MTK 平台、主摄 `insensorzoomPlatformSupport=1`、`vsdofFeatureISZSupport=1`、remosaic key 全在 `availableCaptureRequestKeys`，逐一 set 成功但 **RAW 尺寸与 crop 不变**（2.115x 时 `in=4080x3072 crop=0,0,4080x3072`）。网上证据：MotionCam 在 **X300 Ultra + 长焦**上用 ISZ 拍 RAW，标准版主摄无先例。
- **镜头去重**（0.12.42）：X300 的 id 0/2/6 全是 23.6mm（0/6 = 逻辑相机，2 = 物理主摄）⇒ 镜头表三份 "24MM"、`setFocal`/`setZoomEquiv` 在等效焦距相同时取到不确定的 id（zoomRange 0.67–10 vs 0.6–100），行为随会话漂移。新增 `dedupeLenses`（等效焦距分组、物理优先、同优先级取小 id）+ `LensDedupeTest` 5 条；默认主摄从 id 0 换成 id 2。
- **移除无效实验**（0.12.42）：`applyIszProbe` 与设置页「ISZ 传感器内变焦（实验）」开关整段删除；保留 `camera.isz_probe` / `camera.lens_list` 两条诊断日志。

## Decisions

- **数码变焦裁切放交付层（Kotlin）而不是 native**：JPEG 与 HEIF 两条路各一处，避免改 native 的旋转/10bit 读回坐标；变焦与画幅两次居中裁**合并成一次**。
- **删除不提供恢复**：系统 trash 在这台机上等于真删，自建回收站 = 多一份磁盘与一条会坏的数据链。防误删靠闸门（二次确认 + 全库警示 + 系统确认 + 删后核实）。
- **"保持原分辨率"不做**：PC 的做法是纯插值（Lanczos3/RAISR），不增加细节且默认关；我们 2x 成片是 3.4MP 真实像素，补回 12.5MP 只花 4× 存储换 0 信息、同屏观感更软。要真清晰只有多帧超分，另开一期。
- **ISZ 不再深挖**：参数层面走通、流层面不生效（`vsdofFeatureOpticalZoomSets=0`，没有任何变焦档位暴露给第三方）。
- **镜头表按等效焦距去重、物理相机优先**：逻辑相机只是"能自动切镜头"的包装，而 `setZoomEquiv` 自己会物理切。

## Pitfalls

- **`RESULT_OK` ≠ 真的删掉了**：系统删除/回收站请求的返回码只代表"用户同意"，MediaProvider 可能没执行。删完必须单条查询核实；API 30+ 还要读 `is_trashed`（有些 ROM 单条查询不过滤回收站条目，只看"行是否存在"会误判）。
- **vendor tag 读值必须按类型逐个试**：`CameraCharacteristics.Key<T>` 构造要带类型、类型不符抛 `IllegalArgumentException`，而 `c.keys` 的 star projection 推不出 T。写成依次试 Int/Float/Long/Boolean/String/Byte/各数组的 `probeCharValue`。
- **RAW 输出尺寸恒定 ⇒ 不能从尺寸反推 HAL 有没有裁**：我一度加过"w ≈ active/z 就当 HAL 已裁"的自愈判据，但 binning 尺寸同样落进该区间 ⇒ 必然误判（已删）。判据只能来自真机行为（成片视角）。
- **逻辑相机与物理相机不能混在一张镜头表里**：等效焦距相同 ⇒ 去重/选择都变得不确定，且不同 id 的变焦范围完全不同。
- **先确认"授权点"再写实现**：想做"进系统最近删除"，我先动手写自建回收站（已删）；正确顺序是先验证系统能力是否可写（不可写 ⇒ 方案本身不成立）。
- **`c.get(key)` 在 star projection 下推不出类型**：只打 tag 名是安全的，读值必须显式构造 `Key<T>`。

## Next

- **真机验证 0.12.42**：镜头表应只剩 4 个（15.4 / 23.6 / 70.3 / 165.1）；重点看 3x 以上是否仍能自动切到长焦（原来靠逻辑相机代劳，现在靠 `setZoomEquiv` 自己切）。
- **暗部局部对比度**（未定论，用户暂定留预设里调）：同区域相对对比度 rawpy 19.4% / 我们 17.6% / PC 25.9%；候选 = 去马赛克档（RCD vs AMaZE）。
- **天空复验**（等晴天）：`hlGRecon 1` + `teqHl −2.2` + `hi240 ≤ 0.5%` + 天空是否蓝。
- **PC HEIC HDR 死链**：`passGainmap` 只在 `!c.ten_bit` 时跑，HEIC 恒拿不到本帧 gainmap（待定：修链路或移除 HEIC）。
- **bracket 采集 + 饱和感知融合**：设计已定（`docs/engine-bracket-design.md`），从"采集 + 传参 + 日志"开始。

## Refs

- 包：`filmkit-0.12.42-LENSDEDUPE.apk`（491 / 0.12.42，含 0.12.34→0.12.42 全部改动）
- 提交：`e5a3619`（裁切+多选+缩略图）·`6d3cc35`（去掉 HAL 已裁猜测）·`1fdb7cb`（相册入口/删后核实）·`acfaa3d`（删除不做回收站等）·`6607114`（相册返回直回相机）·`fecad03`/`d581d5c`/`20d1f9a`（ISZ 探测）·`959e334`（ISZ 试下发）·`e23d135`（镜头去重）
- 真机日志：`filmkit-logs-20261022-213626/214044/214633/215423.jsonl.txt`（ISZ 三轮探测）
- PC 源码：`raw/RawDigitalZoomResampling.kt`、`raw/RawOutputGeometry.kt`、`processor/GlesLanczosResampling.kt`、`camera/IszLensConfig.kt`（本地 `/tmp/pc-bjzhou` @ 27d4f78）
