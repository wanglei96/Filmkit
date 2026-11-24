---
summary: Spektrafilm落地与提速交接
created: 2026-11-19 21:15
updated: 2026-11-24 20:55
status: absorbed
---

# Spektrafilm落地与提速交接

## Summary
Spektrafilm已在0.14.29(826)打通RAW多帧、完整光谱负片印放/正片直扫和SDR/HDR JPEG/R落盘，实拍19--26s仍慢。
用户要求DIR默认恢复1后先做s2 d3交接，再开展保质量提速。本轮改默认/3测试通过，未出新APK或开始新性能实验。

## Done
- [2026-11-19] slot2独立引擎复用完整RAW前链，不替换默认sigmoid/旧Filmic；Kotlin快门冻结配方/参数/HDR意图，JNI每renderer独立profile，重试透传。
- [2026-11-19] 20胶片、8印相介质、16负片×8+4正片直扫=132配方；私有double[12] ABI，共享FilmParams仍105。正片跳过相纸曝光/显影，保留曝光/DIR/颗粒/扫描。
- [2026-11-19] 参数面板已接胶片/相纸/直扫、颗粒开关/面积/模糊、晕光、散射、DIR、印相EV、扫描模糊/锐化、胶片长边。修复v3纸面中灰随实际EV重算和HDR中性响应RGB亮度。
- [2026-11-19] 历史对照：147配方/控制host及生产ARM64对象通过，最坏RGB误差约3.43e-10；HDR5配方×3EV×65灰块=975，约1.12e-10。构建时849 JVM测试0失败/2跳过，签名/版本/JNI/资产hash通过。
- [2026-11-19] 设备10AG6Y0DEH009DA（vivo X300/V2509A）当前安装0.14.29(826)，根目录`filmkit-0.14.29-SPEKTRA.apk`约80MB。本轮只读确认版本，未接管UI或拍照。
- [2026-11-19] 最终源码默认：颗粒关闭、面积0.45、模糊0.8px、晕光0.5、散射0.5、DIR1。DIR先按用户要求0.5，随后恢复1；已有自定义值不覆盖。SpektrafilmSettingsTest本轮3项通过，尚未进入安装包。
- [2026-11-19] 颗粒保留三染料×三子层、泊松/二项、染料云/条件微结构/最终模糊完整模型。灰块色度RMS差≤0.64%、亮度SD差≤1.08%，不是全部实景验收。
- [2026-11-19] 同DNG/Q100444/同眩光完整链：颗粒关10676174B，开20246324B=1.8964倍；300MB线性中间输入及两张完整JPEG已删，保留JSON/脚本。
- [2026-11-19] 实拍21.05MB中主图20873520B、gainmap178260B；量化表全1，无重复颗粒调用，旧颗粒分支绕过。内存jpegtran优化主图省10.94%且解码一致，但未修MPF偏移，不是交付HDR文件，也未改生产编码。

## Decisions
- [2026-11-19] 完整按原版处理顺序自研落地，先原版一致性再优化，禁止用混色简化替代光谱、删波段/采样/核/精度换速度；成像关键链显式禁ponytail。
- [2026-11-19] 用户无指定优先胶片/相纸，沿用Portra400/Endura；保留原设置和默认引擎。先接受RAW多帧SDR，随后明确要求接HDR，现均保留。
- [2026-11-19] 颗粒默认关，不降低JPEG保存质量解决文件体积；最终参数见Done。新默认未测时不能宣称性能收益。
- [2026-11-19] 用户要求“先交接、接下来优化速度”；本轮不再出包。下一交付升0.14.30(827)，验签名/版本/JNI，不发未授权公开Release。

## Pitfalls
- [2026-11-19] 真实0.14.29快门→保存：Portra/Endura关颗粒22.098s，开颗粒25.539s，Velvia直扫关颗粒19.309s；对应光谱renderMs17453.553/19451.609/13576.293，grainMs136.461/6146.226/125.201，bytes7841380/21052152/6282057。
- [2026-11-19] 编码仅129--254ms，前链submit约1.26--1.53s、upload约1.11--1.35s；勿把编码当主要延迟。各计时窗口可能嵌套，不可直接全加。
- [2026-11-19] 用户明确两次只切颗粒，自动曝光ISO/快门变化不能表述成用户改其他变量。不同采集仅是观察基线；因果收益必须同RAW/温控/参数验证。
- [2026-11-19] RenderTimings已有Hanatos/曝光/DIR/颗粒/纸曝光/显影/扫描/输出分段，生产日志主要只报renderMs/grainMs。下一步先暴露分段，勿未经测量重写热点。
- [2026-11-19] 历史独立整链滤波并行14.095→11.783s且FP64逐字节一致，不可挪作真实拍照加速；颗粒参数变轻/文件变小也不等于速度提升。
- [2026-11-19] 预览只是构图代理；自定义扩散/化学显影/全部纸面黑白点未开放。相纸切换、单帧RAW HDR、更多端点与实际HDR显示质量仍待验；用户所拍具体肤色/高光效果尚未完整分析。
- [2026-11-19] 分发许可审计未完成，本地验证/仓库checkpoint不等于公开分发许可已验收；不重开Filmic V7或已失败opposed高光重建。

## Next
1. 暴露已有完整分段计时，固定同RAW生产负载，记录温控、曝光/ISO、配方、颗粒参数；分别测RAW多帧前链、光谱子阶段、HDR/编码/保存，优先找真实热点。
2. 按原版完整链做质量不退化提速；同输入比数值/图像和实测速度，保留波段、采样、核、精度与当帧统计。不能跨张缓存统计或擅自减少MFNR帧数。
3. 分析现存关/开颗粒与Velvia照片，补相纸/参数端点/单帧HDR和实际HDR显示验收；不要让用户重新提供已有DNG。
4. 审计清理过期0.14.26/.28 APK和不用的临时二进制，仅留当前.29+稳定回退.27；保留原片与可复用证据、记录hash，禁止pm clear或清手机原图。
5. 新包须升版本并验签名/DEX/JNI；继续许可审计，保留CS/CM真色线、日光长帧保护、高光扩展和预设旧账，不冒称跨场景已验收。

## Refs
- 本地checkpoint：`b20ba04`完整引擎/资产/接入/默认参数，`8eab7b1`对照工具与报告；交接提交后统一推送dev，不创建tag/Release。
- 主报告：`docs/reports/spektrafilm-mobile-spectral-probe-20261119.md`；早期混色研究：`docs/reports/filmkit-print-study-20261119.md`，不是生产替代算法。
- 原版：`test_temp/engine-reference-20261118/spektrafilm-upstream`，commit `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`；Python：同父目录`spektrafilm-env/bin/python`。
- 实拍日志/照片：`test_temp/spektrafilm-apk-20261119/phone-controls.log`、`capture-grain-off.jpg`、`capture-grain-on.jpg`、`capture-velvia-direct.jpg`；`controls.png`为遮罩截图非UI验收。
- 配方/HDR对照：`test_temp/spektrafilm-recipes-android-20261119.json`、`test_temp/spektrafilm-hdr-recipes-android-20261119.json`。
- 颗粒证据：`test_temp/spektrafilm-grain-size-20261119.json`及同名`.png`、`test_temp/spektrafilm-grain-colour-device-20261119/midgrey.png`；工具`tools/spektrafilm-*`。
- 设置/测试：`app/src/main/java/com/filmkit/app/camera/SpektrafilmSettings.kt`、`app/src/test/java/com/filmkit/app/camera/SpektrafilmSettingsTest.kt`；引擎`core/engine/src/film/spektrafilm/`。
- 本次全量读15份旧handoff、67份archive和五层记忆；旧active已吸收，无超14天待归档。旧紫边/V7结论见`彩边前置与Filmic V7定案.memo.md`。
