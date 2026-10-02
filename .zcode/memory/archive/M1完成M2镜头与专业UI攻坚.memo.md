---
summary: M1完成M2镜头与专业UI攻坚
created: 2026-09-18 10:20
updated: 2026-09-18 11:09
status: archived
---

# M1完成M2镜头与专业UI攻坚

## Summary

本窗口完成 M0 骨架、M1 YUV 全链路（SurfaceTexture+OES 预览、全分辨率静拍、M 档专业 UI）与 M2 前半（镜头发现、设置页、采集控制层），vivo X300 真机验收通过（预览 30fps、拍照 3072×4096 全彩、后台恢复正常）。M2 剩余：RAW/DNG 采集 + 引擎 RAW 链（解包→黑阶→WB→bilinear→汇流胶片链）。两轮 grilling 评审后需求基线 v2.0 已写入开发计划。

## Done

- docs/开发计划.md：v2.0 需求基线（§0 决策 D1–D13）+ M0–M2 全程进度记录 + X300 适配结论
- docs/x300-能力审计.md：GL 快照 + 实测性能基线（1024² 三段 0.4/0.3/0.2ms）+ quirk 清单
- app/：M 档专业 UI（黑底、顶部实测参数条、预览内滑杆面板、AF/EXP/EV/WB 按钮、焦段条 15–85MM、大快门）
- app/…/camera/CameraController.kt：SurfaceTexture 预览 + ImageReader 全分辨率静拍 + 双流会话 + 生命周期重启 + 镜头发现（暴力探测 ID 0–6 隐藏镜头）+ EV/WB(K)/OIS/变焦/触屏对焦
- core/engine/：FrameRenderer 三段胶片链（OES→线性→log 域胶片曲线→输出）、RGB8 静拍直填路径、LensDiscovery 静态探测
- app/…/ui/SettingsScreen.kt：DNG 开关（持久化）、镜头配置开关（按 ID 启停）、审计导出入口

## Decisions

- 需求基线 v2.0（docs/开发计划.md §0 D1–D13）：爱好者向；「M 档」模式；DNG 设置开关默认关；RAW 用 binned 4080×3072（200MP 待审计）；接受预览/成片差异；基准色彩科学+理光 LUT 风格层；胶片柔感优先；样张集=公开库 CI+用户 X300 实拍；多帧融合 Backlog M6 后
- 全 GPU 路线确认：RCD/降噪均在 GPU，手动三件套+色温+焦段自定义列 M3
- 内置预设 = 用户理光系 .cube（原版名），M4 接入；LUT 默认显示域+per-LUT 覆盖
- 专业参数 UI 叠加预览内、英文缩写、实测读数（CaptureResult 回填）

## Pitfalls

- 中文路径（胶片相机/）致 AGP externalNativeBuild 崩溃：自定义 CMake Exec 任务直驱 NDK，经 jniLibs 打包
- Mali RG8 TexSubImage2D 静默输出全黑：预览改 SurfaceTexture 系统路径，静拍 CPU 转 RGB888 上传
- RGBA8 纹理误配 GL_HALF_FLOAT → 捕获 FBO 永不完整 → 照片全黑：按内部格式选类型
- API36 移除 COLOR_CORRECTION_RB_GAINS：手动 WB 改用 CCT 模式直填色温
- GLSL ES3.1 image uniform 无默认精度（Mali 强制 highp）；RGBA16F 无线性过滤（NEAREST）
- 预设 DoubleArray 沉默零值（wb/contrast/cross 全零=黑/灰板）：中性基线+引擎防呆
- log 域映射须以 0.18 中灰为轴 ±4EV，旧映射致暗部整体灰化
- 静拍旋转/画布交换必须用同一 effective rotation（界面方向+传感器）；OES 与 2D 纹理坐标原点相反
- Surface 生命周期：回调内直接 start（幂等）+ ON_PAUSE/ON_RESUME 感知，勿依赖 LaunchedEffect key
- rebuildRepeating 需签名去重（防每帧重复合并刷屏）；pending 闸需 post 失败回滚+会话复位（后台恢复黑屏）
- X300 HAL 不上报屈光度：手动对焦默认 10D 试值；超广隐藏镜头黑帧：设置页按镜头启停

## Next

- M2-3：RAW 采集（ImageReader RAW_SENSOR 4080×3072）+ DngCreator 存 DNG，读设置页 dng_enabled 开关
- M2-4：引擎 submitRaw：u16 解包→黑阶(BL 64/WL 按 dynamicWhiteLevel)→WB→bilinear（CFA=GRBG）→汇流胶片链→JPEG
- M2 收尾：X300 审计报告归档 docs/（隐藏镜头 ID 2/3/4/5/6 分类表已在本快照 Refs）
- M3 首项：手动对焦实测确认（屈光度 10D 试值）；随后 RCD 全 GPU + 基准色彩内核
- M4 前向用户收取理光系 .cube LUT 文件

## Refs

- docs/开发计划.md（v2.0 需求基线 §0 + 全部进度记录）
- docs/x300-能力审计.md（GL/性能/quirk 清单）
- X300 相机审计：设备 /storage/emulated/0/Android/data/com.filmkit.app/files/audit/*.json（7 相机：id0 逻辑主摄、id6 第二逻辑组、id3 长焦 4096×3072+双档 OIS、id4 超广 maxISO768、id5 CFA=GBRG、id1 前置）
- 外部参考：github.com/bjzhou/PhotonCamera（镜头暴力探测/SurfaceTexture 路径）、DAZZ 截图（UI 参照）
