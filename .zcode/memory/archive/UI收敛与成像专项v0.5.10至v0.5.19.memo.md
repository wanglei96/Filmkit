---
summary: UI收敛与成像专项v0.5.10至v0.5.19
created: 2026-09-20 10:50
updated: 2026-09-20 17:10
status: archived
---

# UI收敛与成像专项v0.5.10至v0.5.19

## Summary

本窗口完成变焦交互三轮迭代（半圆轮盘 + 缩小跳变根因修复 + 切镜提速）与成像质量专项（像素级对标 Filmix 定位四根因并修复），发布 v0.5.10–v0.5.19 共 10 个版本（tag v0.5.19-m2 = versionCode 50）全部推送。UI 问题已收敛（用户确认），手动 WB 真机验证通过、变焦画质优先回归、暗部截断/EXIF/色彩解码定论。下次可回主线 M2-3 RAW 端到端。

## Done

- v0.5.10 半圆变焦轮盘：新增 ui/ZoomWheel.kt（弧顶固定指针、原生焦段+数码档主刻度、对数细分、轻触感）；缩小跳变根因=LaunchedEffect(focalBase) 重置 zoomEquiv（跨镜头时拽回 1x）+ 死 pinching 守卫，删除修复
- v0.5.11 切镜提速：SurfaceTexture/OES/reader 跨镜头复用（画面冻结不黑屏）、onClosed 串行重开、snapshotFlow 帧节拍 + 0.2% 量化、松手惯性（EMA+指数衰减 τ220ms）
- v0.5.12 画质优先回归：撤销数码延展（用户否定），切换点 ±3% 迟滞带防抖；轮盘弧顶被 Canvas 裁剪修复（半径加高度约束）+ 位置下移
- v0.5.13 成像质量专项：kFsFilm log 域分段延展修 ±4EV 硬截断（14.2% 像素钉死 33→暗部 50 级渐变）；EXIF 写回（exifinterface，ISO/快门/F/焦距/35mm 等效/WB）+ 质量 95；YUV 解码矩阵参数化开关；预设收敛
- v0.5.14 热修：kFsFilm 非法 step 重载致 shader 设备端编译失败全链路黑屏（step(e,-4.0)→step(e,vec3(-4.0))）+ start() JNI 调用 runCatching 防御
- v0.5.15 复核定论：601/709 实拍 A/B 定量（暗部 R/G 差 0.001）确认 601 正确；JPEG 质量设置（95/100，prefs jpeg_quality）；EXIF ORIENTATION 恒写 1
- v0.5.16–19 UI 收尾：构图线/水平仪移入菜单悬浮面板「构图辅助」区；水平仪三轮打磨终态=旋转线式（重力方位角 360° 连续 + 最短角差 EMA 0.22 + 模 90° 双向对齐 + 滞回 0.4/1.2°，对齐 Accent/不对齐白不透明，44% 线长）
- 100 档样片复核：5.87MB、暗部渐变 50/50 级、细节能量 6.43 超 Filmix，符合预期

## Decisions

- [2026-09-20] **变焦画质优先**（更新 v0.5.11 数码延展条）——到长焦焦段即切光学镜头，仅切换点 ±3% 迟滞带保持当前镜头防抖；数码延展被用户明确否定
- [2026-09-20] **预设收敛为单一「标准」=全中性**（含中间调饱和 1.1→1.0）；负片/黑白删除；预设机制定位=用户自建（LUT+参数保存，M4 接入）
- [2026-09-20] **BT.601 静拍解码正确**——实拍 A/B 定量排除 709 错配假说；设置页开关保留（注明结论）供未来机型复查
- [2026-09-20] **JPEG 成像质量用户可选 95/100**（prefs jpeg_quality，默认 95）；100 档体积 ~2.4 倍为预期行为
- [2026-09-20] 水平仪=旋转线式（平移气泡被否定）；对齐判定模 90°（横竖屏皆可）+ 滞回 0.4/1.2°

## Pitfalls

- [2026-09-20] **引擎 shader 改动构建/单测全绿仍可能设备端全黑**：shader 是设备运行时编译，GLSL 无本地覆盖——v0.5.13 非法 step(vec3,float) 重载致 prog_film_ 编译失败→init 失败→openCamera 不执行（黑屏+焦段全空）。铁律：shader 改动必须设备验证后才发版；M3 考虑抽独立 .glsl 用 glslangValidator 构建期静态校验
- [2026-09-20] **log 域 ±4EV 硬截断的观感代价**：14.2% 像素钉死同一灰度=直方图单尖峰+暗部色带+文件体积 1/4 的共同主因；域外须分段延展（filmCurve 的 toe/shoulder exp 在域外发散，延展段必须绕开）
- [2026-09-20] **Bitmap.compress 不写任何 EXIF**——保存后须 ExifInterface 经可写 FileDescriptor（MediaStore openFileDescriptor "rw"）补写；InputStream 构造的 ExifInterface 不支持 saveAttributes；ORIENTATION 不显式写会落非法值 0
- [2026-09-20] 水平仪对齐判定无滞回会在阈值附近持续闪色；平移气泡式结构上不可用（滑出刻度区半截+无旋转语义）
- [2026-09-20] docs/开发计划.md 插入新版本节时 old_string 含「旧标题+空行」会吞掉旧节标题（本窗口发生 3 次，均已补回）——插入新节应把旧标题包含在 new_string 里

## Next

- **回归主线 M2-3 RAW 端到端**：RAW_SENSOR 4080×3072 采集 + DngCreator 存 DNG（设置页 dng_enabled 已有）+ 引擎 submitRaw；明确 WB 补偿不进 DNG 的口径
- 色温估计校准精校（M3 灰卡+标准光源；WB_CAL_R=0.923/WB_CAL_B=1.238 单点推得）
- 轮盘优化已搁置（用户确认）：灵敏度 wheelSpanPx（1.4 屏宽）、吸附等参数可按手感再调
- MF 滑杆屈光度手感真机复验（v0.5.2 交付未复验）

## Refs

- docs/开发计划.md：v0.5.10–v0.5.19 每版独立小节（含四次定量分析数据）
- 变焦链：ui/ZoomWheel.kt、CameraScreen.kt（手势 snapshotFlow/fling）、CameraController.kt（覆盖则留已撤销、迟滞带）
- 成像链：core/engine/src/render/FrameRenderer.cpp（kFsFilm 分段延展、CPU YUV 矩阵参数化）、CameraController.saveJpeg/writeExif
- GitHub tag v0.5.19-m2（versionCode 50）；FilmKitApp version 字段已同步
- 对比基线：/tmp/fk-analysis/（临时目录，结论已入开发计划）；用户日志 filmkit-logs-20260920-094613.jsonl.txt
- 上手快照：handoffs/UI专项定稿与采集修复v0.4.6至v0.5.9.memo.md（本窗口已吸收）
