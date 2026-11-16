# 0.14.19：无有效短帧时的云边品红

## 结论与边界

修复 HDR 没有更短曝光观测时绕过 RGB CameraWhite resolve、重新进入旧 CFA sat-fix 的生产分支缺口。未减少帧数、邻域采样或处理精度，未改 WB、CCM、预设、降噪参数或预览算法。

同输入阶段重放和真实 Mali 生产引擎全链均已验证；**安装后实际相机拍摄、理光标定预设和相册 HDR 显示仍需用户验收**。不能把默认 FilmParams 的 CLI JPEG 当作原理光 LUT 的逐位重现，也不能据此保证所有彩色高光无去色风险。

## 证据归属

- 原始反馈：0.14.18 / 815，长焦镜头 3，`FILMKIT_20261116-143807_理光正片标定.jpg`，云边粉/紫色轮廓。
- 用户手动诊断：2026-11-16 14:48:56.709 快门，14:49:02.560 JPEG 保存；用户主动开启 DNG。
- 诊断 JPEG：`FILMKIT_20261116-144902_理光正片标定.jpg`。
- DNG：`FILMKIT_20261116-144857.dng`；四帧均为 4096×3072 GRBG、白阶 1023。
- 阶段 token：`21963092116`，四个 RAW dump 完成事件均已核实；本次不是 11 月 15 日的历史槽位。
- 所有输入及报告：`test_temp/magenta-01418-20261116/`。
- 采集完成立即删除 `burst_dump`；`edge_dump.enable` 已被本次消费，两者均核实不存在。未改用户 DNG 偏好。

本次诊断包含大型同步读回、RAW 和 DNG 写盘，不是性能样本。

## 根因链路

1. 原始问题照片实测 EV 为 `[0, 0, +2.2, +0.251]`；复拍为 `[0, 0, +2.2, +0.662]`。两组都没有更短的侧帧。
2. 复拍 DNG 的参考曝光为 63218 ns；当前规划器在硬件申报的手动快门/ISO 下限钳制后，已经将名义短帧规划成正 EV。不是请求 -1.5 EV 后 HAL 无故改成正 EV。旧日志四位小数秒会掩盖这个差别。
3. `submitRawBurst` 的完整短帧准入要求 `frame_scale[short_idx] > 1.001`。无短帧时该分支不运行，这是正确的观测约束，不能靠放宽门槛伪造真值。
4. 但 RGB resolve 同样被 `highlight_merge_ready_` 门控，导致 CameraWhite 完全不执行；同时 `satfix_on && !highlight_merge_ready_` 会重新触发旧的逐 CFA 相位修复。
5. 问题照片没有执行新局部噪声/协方差路径，因此本次不是协方差纹理复用直接污染像素。

参考 `/private/tmp/PhotonCamera` 中 `GlesMgcRawSpatialStacker.kt` 的 source CameraWhite 初始化与 no-ultrashort sampler 绑定，以及 `GlesMgcSpatialRgbMergeShaders.kt::resolve`：无短帧仍执行完整 RGB 源白界，只有短帧 share 为零。

## 修改

- 将 source-white resolve 就绪状态与短帧 merge 就绪状态分离。每次 bracket 从本张源 neutral 建立 CameraWhite、参考尺度；无短帧也消费该白界。
- 没有接受的短帧时绑定本张完整浮点 LSC 纹理作为不采样的 typed dummy，并强制 short share 为零，避免读取上一张 mask。
- 单帧与 release 清理 resolve 状态；每次 burst 重新建立，覆盖尺寸/曝光分支切换。
- 删除无有效短帧时可达的旧 sat-fix 调用块。真正较短的候选仍走原完整 guide/双向几何/整帧准入/原相位融合，失败不偷偷回退旧修复。
- bilinear 请求在需要 bracket 白界时路由共享 RCD 消费链；RCD/AMaZE 均在完整 RGB、undo LSC → 白界 → redo LSC、CA/CCM 之前执行。
- 增加白界前后一次性阶段 dump、`highlight.resolve` 生产日志，以及 bracket 的纳秒曝光/申报硬件上下限日志。
- 新增离线诊断脚本及真实四帧生产引擎 replay 输入；扩充跨张 GPU 回归与硬件下限单测。

## 同一输入的阶段验证

离线重放从本次保存的真实 P0 开始，执行生产 RCD/CameraWhite/CCM。DNG 四个 GainMap opcode 还原 17×13 RGB LSC，按当前 Kotlin 公式共同比例阻尼并量化 RGB16F。

- 与手机保存 D4 的绝对误差：均值 0.0001188，P99 0.0009766，最大 0.0053711。存在桌面 GL 与日志舍入误差，未声称逐位相同。
- 指标为 `Y > .8 && min(R-G, B-G) > .1`，Rec.2020 luma 权重。
- 同一 P0、同一生产色彩链，D4 高亮品红像素 **156442 → 71**。
- 这只隔离 RGB 白界，不测试 sat-fix 删除，也不包含后继 PC3D/film/LUT/JPEG/HDR 显示。

真实 Mali 上用修复生产 `FrameRenderer` 从相同四帧 RAW 重放，包含 P0、去马赛克、噪声处理、默认 film、RGBA 与 JPEG 编码；保存的 D4 高亮品红指标为 **0**。标定包含部分日志舍入值，预设使用默认 FilmParams，不是原理光 LUT 成片的逐位复现。

## 回归

- 真实 Mali，RCD、请求 bilinear、AMaZE 三组各 6 次，共 **18 次**完整成片成功，所有生产白界调用次数断言通过、无 GL 错误。
- 每组顺序：无短帧 → 有短帧 → 无短帧且尺寸变化 → 普通四帧 → 单帧 → 无短帧；AMaZE 使用另一旋转方向。
- RCD 有短帧、普通多帧、单帧共 3 组 RGBA/JPEG，与原 0.14.18 引擎 **6 份文件逐字节一致**。
- RCD 首末无短帧结果逐字节一致，没有继承中间短帧/单帧的状态。
- 两个预览引擎 × 全尺寸/缩放，**4 份像素输出逐字节一致**。
- source-white CPU 独立参考：317520 组合通过，并增加生产调用条件、typed dummy、单帧 reset、demosaic routing 守卫。不是用 CPU 测试替代生产 GPU 调用。
- HDR 规划 10 项 + 预览门控 3 项定向单测，**13/13 通过**；包括参考曝光低于手动下限时名义短帧实际正 EV。
- 诊断/DNG 独立性、快门不暂停预览、诊断并发检查通过。
- 既有高光 recovery 4635 assertions、geometry 301 checks 通过；本轮未修改其算法。
- arm64-v8a/x86_64 Release 编译通过；保留一个已有 `cw` unused-variable warning。
- 0.14.19 / 816 APK v2/v3 签名有效，DEX 含新版本字符串，RAW burst/HDR available/UltraHDR encode/gainmap readback JNI 符号齐全。
- 根目录保留 `filmkit-0.14.19-HIGHLIGHT.apk` 和上一稳定版 `filmkit-0.14.4-MFNR-DIAG.apk`，删除已知问题的 0.14.18 包。
- 覆盖安装返回 Success，`dumpsys package` 实核 `versionName=0.14.19 / versionCode=816`，应用数据及用户 DNG 偏好保留。
- 安装确认后 ADB 连接断开，后续 `am start` 未执行成功；没有宣称新版本相机页启动或实际新拍已经验收，已请用户手动打开试拍。

没有跑完整应用单测；本轮只报告上述定向测试，不能声称全仓库测试全绿。没有在并行 GPU 诊断负载下宣称任何性能提升。

## 实拍验收与覆盖边界

2026-11-16 用户补拍两张并明确确认“均已达预期，可以继续性能”。设备日志核实
0.14.19 / 816，15:17:19 与 15:17:44 两次快门均成功保存；本次云边品红修复记为
用户实拍通过。两张快门到保存分别 5.892s / 4.760s，不作为性能达标。

安装后同一长焦/天空/理光标定预设手动试拍，检查云边品红、灰带、亮度过渡与相册 HDR；再扩展彩灯、粉色花瓣、栏杆、跨镜头。CameraWhite 是源白界输出政策，会约束部分尚未达到传感器满阱但超过源中性白界的真实亮色，不能宣称真实颜色完全不变。当前修复不恢复所有帧均已剪裁区域的真实纹理。

已恢复成片与预览性能优化；仍以不削减质量管线为前提。
