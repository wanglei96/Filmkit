---
summary: 彩边前置与Filmic V7定案
created: 2026-11-18 21:38
updated: 2026-11-19 21:15
status: absorbed
---

# 彩边前置与Filmic V7定案

## Summary
完成 0.14.27 的独立 CS/CM 彩边前置、协方差传播、同 RAW 真机诊断与候选包；默认只开 CS，CM 保持可选。
完成 Filmic V7 固定提交参考、原版 CPU 整模块对拍和两张真实 DNG 的同工作点 A/B，结论是当前没有足够观感收益支持升级。
Spektrafilm 改为独立可选风格引擎，保留完整原版验证，但停止与旧引擎做逐预设四路排名。

## Done
- [2026-11-18] `63e5d4b`：0.14.27(824) 将 `[59]` 明确为 CS、新增独立 CM `[104]`，`FilmParams::kCount=105`；CM 不进入预设，保存的用户 CS 开关不被升级覆盖。
- [2026-11-18] CS/CM 在 D4 BT.2020 域计算，输出上限改为 64，保护 alpha 有效点；CS 含平滑梯度导数，CM 跟踪实际中值源和亮度补偿 Jacobian，二者同步输出 RGB 与完整六分量协方差。
- [2026-11-18] 同 RAW 真机四档：线性灯弧总量关/CS/CM/CS+CM 为 843.331/200.413/653.462/158.793；最终 SDR JPEG 编码域为 74.051/42.008/75.757/30.667。CS 在最终编码域降低 43.27%，组合为 58.59%；CM 单开编码域反增 2.30%。
- [2026-11-18] 真机 24 组 CS/CM×PC3D(0/1/2)×单/多帧、连续尺寸/旋转/warmup 回归通过；Kotlin 842 项单测 0 failure/0 error/2 skipped，HDR release 构建通过。
- [2026-11-18] APK `filmkit-0.14.27-CHROMA.apk` SHA256 `dec44714e3c9ba4e3846134ee36e2147ebb0073d74b7a6c0b7cb55c606e5d6e5`；签名、DEX 版本、RAW/Burst/Warmup/UltraHDR JNI 已验，`adb install -r` 成功且冷启动正常。
- [2026-11-18] `e70592a`：补入 Filmic V7/Spektrafilm 总体设计、V7 覆盖表、固定提交参考工具和参考执行记录。darktable 锁定 `395aa7d5156b68a69d8f2d25a3e6fc66c525a3c2`。
- [2026-11-18] Filmic V7 原版 CPU 整模块与隔离参考已对拍：sRGB/P3 编码端到端最大差约 `1.0687e-4`/`1.0771e-4`；sRGB 原版重跑逐字节一致。差异主要集中在 Filmic，不把该数值写成设备误差预算。
- [2026-11-18] 灯罩 D0 与 `test_temp/PhotonCamera_20261006_133348.dng` 均固定黑/白点 `-7.65/+4.56` 做旧 Filmic/V7 对照；色彩、亮部、暗部无肉眼可见差异，全图任一 RGB 通道最多差 1 个 8-bit 阶。
- [2026-11-18] Spektrafilm 原版冒烟和 4080×3072 Provia 100F 直扫完成；Portra 400+Endura 在相纸曝光阶段因磁盘低于 5 GiB 保护线终止，没有完整 `rgb_out`，不算全尺寸负片通过。

## Decisions
- [2026-11-18] 0.14.27 默认只开去彩边 CS，CM 独立可选且默认关；两段不强制一起开启，升级保留用户原选择。
- [2026-11-18] 彩边前置作为可关闭的缓解功能交付，不称紫边根因修复；强边缘 1--2px 真色细线明显褪色是已知代价，需真机场景验收。
- [2026-11-18] Filmic V7 当前不升级、不设默认、不替换旧 Filmic 或 sigmoid；两张样本无观感收益，继续扩参数矩阵没有决策价值。
- [2026-11-18] Spektrafilm 是独立可选风格引擎，以完整原版、稳定性、设备成本和分发边界验收；取消逐预设四路质量排名，不再阻塞 Filmic 主线。

## Pitfalls
- [2026-11-18] “压掉 80%”是本张线性灯弧指标，不是最终画质总分；最终 JPEG 编码域为 43.27%/58.59%，且 CS 会损失已知 1--2px 真色线。
- [2026-11-18] 旧 CS/CM 命中分支使用 Rec.709 亮度系数而输入是 Rec.2020，并把上限写成 16 而非 D4 的 64；本包已修域问题，但没有因此消除算法本身的真色线褪色。
- [2026-11-18] 评测 Filmic V7 不能拿紫边是否消失当画质结论；必须在相同黑白点与输入上分别看色彩、亮部过渡和暗部层次。
- [2026-11-18] V7 结果是隔离 tone/gamut 与 CPU 原版模块，不是完整应用、Mali、LUT、HDR 或最终 JPEG 验收；一张白天 DNG 也只能支持当前样本结论。
- [2026-11-18] 0.14.27 安装和冷启动成功后 ADB 断开，应用内实拍/预览日志补采未完成，不能写成全场景真机验收。

## Next
- 用 0.14.27 在真实细彩纹、肤色/色准、噪声、高光层次和运动场景做 CS 默认/关闭、CM 单独开关的同场景对照，并补采应用日志。
- 条件合适时复测日光栏杆长帧裁切保护；不把受控机制修复写成现场唯一归因。
- Spektrafilm 先做 G0/G1 分发边界与原版一致性，再接入独立风格引擎；不为排名重跑全尺寸印放。
- Filmic V7 维持现状；只有出现真实可见收益、明确标准化需求或白点以上/HDR 适配任务时再重开。

## Refs
- 提交：`63e5d4b`（0.14.27 CS/CM 与诊断）、`e70592a`（V7/Spektrafilm 参考证据），均已推送 `origin/dev`。
- 报告：`docs/reports/purple-lampshade-20261117.md` §15--18、`docs/reports/engine-reference-start-20261118.md`、`docs/reports/filmic-v7-spektrafilm-roadmap-DESIGN-20261117.md`。
- 设计：`docs/reports/spektrafilm-manufacturer-engine-PLAN-20261117.md`；覆盖：`docs/reports/filmic-v7-coverage.json`。
- 真机证据：`test_temp/chroma-01427-device/`、`test_temp/engine-reference-20261118/d0-device/`。
- V7 对照：`test_temp/engine-reference-20261118/filmic-final-ab/`、`test_temp/filmic-daylight-ab-20261006/`。
- 上游：darktable `395aa7d5156b68a69d8f2d25a3e6fc66c525a3c2`；Spektrafilm `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`。
