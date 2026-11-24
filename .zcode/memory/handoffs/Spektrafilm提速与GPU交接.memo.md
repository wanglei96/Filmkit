---
summary: Spektrafilm提速与GPU交接
created: 2026-11-24 20:55
updated: 2026-11-24 20:55
status: active
---

# Spektrafilm提速与GPU交接

## Summary
0.14.50（847）已真机验收：Gold 200 / Endura 7.442s、Provia 100F 直扫 6.274s，均为快门到保存。成片正常，取景几乎不卡，仍需显著提速。

## Done
- [2026-11-24] 眩光并行局部省 252/109ms，整链收益未复现；曝光回写合并及双 PBO 未提速，三项撤回，RGBA、HDR 增益图和 JPEG 在同 RAW 比较中逐字节一致。恢复库 SHA-256 与 0.14.50 APK 一致，未发包或安装。
- [2026-11-24] GPU 调研：Mali-G1-Ultra MC12 的 Vulkan shaderFloat64 为 false，同族双队列不保证预览隔离；GPU 算法原型尚未实现或实测。
- [2026-11-23] PhotonCamera 和上游把直扫与片型分开；负片透射直扫得负像，不是数字转正。FilmKit 只开放正片直扫是功能范围限制，负片印相是有效路径。

## Decisions
- [2026-11-24] CPU 优化不停，重点曝光、DIR、扫描输出；GPU 独立验证。成像核心不用 ponytail，不删流程、减采样或降精度换速度；有量化和目视证据的微差可以接受。
- [2026-11-24] 离线验全部 132 个可选配方与固定种子；人工只拍 Gold 印相、Provia 直扫各一张，处理时移动手机。

## Pitfalls
- [2026-11-24] 分开报同 RAW 引擎时间、实拍保存、合成取景与真实取景；局部加速不等于整链收益，双 PBO 变慢不能否定 GPU 算法。
- [2026-11-23] 个别照片相册反复查看后整图变暗，App 内和分享正常，同字节复制件在文件管理器正常；根因未定，不归为 HDR 增益。印相曝光本身用户已确认正常。
- [2026-11-24] 清理保留原片与证据，重复件先校验留存副本 SHA-256，不清应用数据或删除手机原片。

## Next
1. 从 0.14.50 做同 RAW 交错回放，控制热状态；拆扫描 CAM16、滤波、编码、传输，查曝光/DIR 的全尺寸读写。
2. 在现有成片 GLES 上下文试完整 16 tap Hanatos 或完整扫描公式；保留 CPU FP64 对照/回退，测传输、同步和预览。现有 CPU Hanatos 约 105–126ms，扫描输出含打包约 405–453ms。
3. 有整链净收益后跑全配方与双路线真机验收；发包升版本并验签名、版本和 JNI。相册问题另行复现比对，勿改变曝光掩盖。

## Refs
- 代码和报告：`360d3c1`、`c1c1692`。
- 实拍：`test_temp/spektrafilm-01450/device-20261124-performance.json` 及同目录日志；CPU/GPU 回放与恢复哈希：`test_temp/spektrafilm-01451/cpu-gpu-probes.md`。均为本地证据。
- `docs/reports/photoncamera-spektrafilm-research-20261123.md`、`docs/reports/spektrafilm-01440-memory-gallery-20261123.md`。
