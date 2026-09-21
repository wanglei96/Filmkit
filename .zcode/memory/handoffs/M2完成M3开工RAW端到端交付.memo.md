---
summary: M2完成M3开工RAW端到端交付
created: 2026-09-20 17:05
updated: 2026-09-21 00:10
status: absorbed
---

# M2完成M3开工RAW端到端交付

## Summary

本窗口完成 M2 收口与 M3 基建开工：v0.5.20–v0.5.27 共 8 个版本（tag v0.5.27-m2 = versionCode 58）全部推送。M2-3 RAW 端到端落地并真机收敛（RAW_SENSOR 采集 + DngCreator 底片 + 引擎 submitRaw 胶片链，修 CCM 转置偏色/DNG orientation/M 档快捷开关）；底部 UI 四轮重构收敛（预览框 weight(fill=false) 自适应）；M3 基建交付（raw.render 分段计时 + shader 静态校验）；开源对标调研固化进开发计划，M3 路线据此修正。

## Done

- v0.5.20 M2-3 RAW 端到端：dng_enabled 时静拍切 RAW_SENSOR（X300 主摄 4080×3072，无 RAW 能力回退 YUV）；Image+TotalCaptureResult 渲染线程双事件汇合；DngCreator 存 DNG；引擎 submitRaw（R16UI 整型纹理 texelFetch + UNPACK_ROW_LENGTH → 黑阶/白阶 → WB（SENSOR_NEUTRAL_COLOR_POINT 倒数，兜底 AWB 增益估计）→ bilinear demosaic 四 CFA 相位 → CCM → 汇流 pass2/3）
- v0.5.21 真机修复：M 档快捷开关（顶栏「M」，prefs raw_mode，与 DNG 底片开关解耦，restartSession 重建会话）；**CCM 转置修品红**（行主序数组传 glUniformMatrix3fv 须 transpose=GL_TRUE）；DngCreator orientation 映射（sensor 90°→EXIF 6）；raw.meta 诊断日志（真机确认 X300 回填 neutral/ccm）
- v0.5.22 画幅持久化（prefs capture_aspect——进出设置页销毁 CameraScreen 臂 rememberSaveable 丢失）；样张误入库移出 + gitignore FILMKIT_*.jpg/dng
- v0.5.23–26 底部 UI 四轮：快门行 navigationBarsPadding → 瓦片入预览框 → 瓦片/焦段互换紧凑 → **瓦片移出预览框单行 + 预览框 weight(1f, fill=false) 自适应**（4:3 不再吞空间快门跟随上移、16:9 被约束不挤压 UI；快门贴底根因即 weight(1f) 弹性占满）
- v0.5.27 M2 验收逐项确认完成 + M3 基建：raw.render 分段计时（uploadMs/passMs/totalMs，glFinish 后计时）；tools/validate-shaders.sh（提取 FrameRenderer.cpp 全部 GLSL 按 vert/frag 交 glslang 校验，8/8 过；glslang 16.6.0 本地副本 tools/bin 已 gitignore）
- 开源对标调研（PhotonCamera/降噪 SOTA/Filmic RGB/多帧）结论固化开发计划，M3 路线修正

## Decisions

- [2026-09-20] **M 档=RAW 拍摄模式快捷开关，与 DNG 底片开关解耦**（口径更新）：raw_mode 控制采集格式，dng_enabled 只管是否保留底片文件
- [2026-09-20] **预览框 weight(1f, fill=false) 自适应高度**：预览按画幅内容占高，底部 UI 跟随预览框下缘；4:3 留白落屏幕底、16:9 被可用高度约束
- [2026-09-20] **RCD 实现路线**：研读 eszdman/PhotonCamera demosaicp* 管线结构 + Karacan 论文自写；**GPL-3.0 隔离不抄码**；bilinear 保留 A/B 基线
- [2026-09-20] **降噪两步走**：M3 传统 RAW 域方法守 ≤900ms；NN 降噪 + 多帧融合（HDR+ 路线）M6 后立项评估（夜景一流的分水岭）
- [2026-09-20] 胶片内核 M3 补 norm-controlled 高光处理（Filmic RGB/AgX 思路自研）；风格层维持中性基准+LUT，M4 吃社区胶片 CLUT 资产
- [2026-09-20] 成片样张不入库：FILMKIT_*.jpg/dng 进 gitignore，tools/bin/（本地工具二进制）同

## Pitfalls

- [2026-09-20] **行主序 CCM 数组传 glUniformMatrix3fv 必须 transpose=GL_TRUE**——按列主序解释=作用转置矩阵，全局严重偏色（v0.5.20 品红根因）
- [2026-09-20] DngCreator.setOrientation 只收 EXIF 1..8：sensor 0/90/180/270 → 1/6/3/8，传角度值抛异常致 DNG 保存失败
- [2026-09-20] Camera2 RAW 元数据键名/类型易记错（javap 核实）：WB 键是 SENSOR_NEUTRAL_COLOR_POINT（非 JPEG_ 前缀）；SENSOR_DYNAMIC_BLACK_LEVEL 是 float[4] 顺序 [R,Ge,Go,B]；BlackLevelPattern 方法为 getOffsetForIndex(col,row)
- [2026-09-20] rememberSaveable 状态随子页进出销毁丢失——跨会话 UI 状态（画幅）必须 prefs 持久化
- [2026-09-20] 预览框 weight(1f) 弹性占满 → 快门行永远贴屏底，调 padding 无感；自适应需求用 weight(1f, fill=false)
- [2026-09-20] shader 校验须分 stage（gl_Position=vert），glslang 新版二进制名 glslang（非 glslangValidator）；brew 目录 root 属主装不了 → Khronos release 二进制入 tools/bin

## Next

- **M3-1 RCD 去马赛克**：研读 eszdman/PhotonCamera demosaicp* 管线 + RCD 论文（Karacan 2013）自写两遍迭代 + 伪彩抑制；bilinear A/B；设置页质量档开关
- 真机验：M 档拍摄看 raw.render 计时（12MP ≤900ms 出口标准基线）；DNG 用 Lightroom 打开确认（M2 唯一尾巴）
- M3 后续：高光重建 → RAW 域降噪（noiseProfile）→ 基准色彩内核（DNG 标准矩阵链）→ 零拷贝（PBO/AHardwareBuffer）
- 真机项：色温灰卡精校（WB_CAL_R/B）、MF 屈光度手感复验

## Refs

- docs/开发计划.md：「M2 完成确认 + M3 开工」节 + 「开源对标结论」节（含一流水平边界如实结论）
- tag v0.5.27-m2 = versionCode 58；根目录 filmkit.apk 同步
- tools/validate-shaders.sh（发版前必跑）；.zcode/skills/meow-recall（下窗口载入）
- 真机日志：filmkit-logs-20260920-151649.jsonl.txt（v0.5.21 RAW 验证，raw.meta 确认 X300 回填 neutral/ccm）
- 上手快照：handoffs/UI收敛与成像专项v0.5.10至v0.5.19.memo.md（本窗口已吸收）
