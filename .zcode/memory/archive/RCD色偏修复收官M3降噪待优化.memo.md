---
summary: RCD色偏修复收官M3降噪待优化
created: 2026-09-21 00:10
updated: 2026-09-21 10:05
status: archived
---

# RCD色偏修复收官M3降噪待优化

## Summary

本窗口完成 M3-1 RCD 质量档去马赛克全流程（v0.5.28–v0.5.31 四版）：三段管线实现 → 真机性能收敛（1969ms→477ms，≤900ms 达标）→ 连修两个色偏 bug（P3 G 位填充的 Bayer 结构错、CFA LUT 位序反），用户真机确认 RCD 与 bilinear 颜色已一致。算法出处调研更正（Karacan 论文不存在）。用户反馈下一项：降噪效果待优化。

## Done

- v0.5.28-m3 M3-1 RCD 落地：P1 方向场（五点修正高通 V/H+对角）→ P2 G@RB（CFA 低通图乘性比值+梯度反比+方向置信度混合+限幅）→ P3 色度填充（色差+方向加权+限幅）+CCM；WB 前置线性化；质量档 prefs demosaic_quality 默认 RCD，设置页「RAW 去马赛克」行；raw.render 日志加 demosaic/dirMs/gMs；shader 校验 8→11 条；算法出处更正写入开发计划（RCD=Luis Sanz Rodriguez 开源算法，非"Karacan 论文"，参考实现无迭代）
- v0.5.29-m3 性能定向优化（真机 gMs=1414ms 定位 P2 瓶颈）：CFA 相位改 uniform 查表 uCfaLut、P1 输出预归一化 (vConf,pConf,lpf) 全图一次算（P2/P3 读 1 tap）、P2 梯度两项式、采样 ~91→~24 taps → RCD 1969→417ms（后续 477ms）
- v0.5.30-m3：P3 G 位填充修复——Bayer 中 G 位的 R/B 在四正邻 ±1（按 chanAt 分派轴，±1 为主 ±3 弱混），原"对角/±2"采到的全是 G → 936"饱和度减半"根因
- v0.5.31-m3：CFA LUT 位序修复——cfaLutOf 把 k1/k2 写反（GRBG 误编码 0x49=GBRG），R/B 相位对调+增益错乘（R×1.33/B×0.75）= 149/231 发红真因；**用户真机确认颜色已一致**
- 真机验收：RCD 477ms ≤900ms 达标（dirMs 58/gMs 7/其余 ~238）；DNG sips/vivo 相册可开（M2 尾巴关闭，桌面 Lightroom 随手验即可）

## Decisions

- [2026-09-20] **RCD 单趟三段 fragment 管线**（P1 方向场+LPF / P2 G@RB / P3 色度+CCM），中间图 RGBA16F，WB 在线性化前置（色差恒定假设）；无迭代——参考实现本就是单趟流水
- [2026-09-20] **质量档 prefs demosaic_quality 默认 RCD**；bilinear 保留 A/B 基线与速度档；submitRaw 签名加 demosaic 参数贯通 Kotlin→JNI→引擎
- [2026-09-20] **性能取向**：方向场/低通图预归一化全图算一次供 1-tap 读取；CFA 查表；两项式梯度（HA 主体）；伪彩抑制用"色差域插值+邻域实测限幅"而非独立滤波 pass
- [2026-09-21] **降噪两步走延续**：用户反馈"降噪效果待优化"→ M3-2 RAW 域降噪（noiseProfile 自适应）为下一步主线；NN+多帧仍 M6 后

## Pitfalls

- [2026-09-20] **Bayer 结构事实**：G 位的 R/B 都在四正邻 ±1（两种 G 相位哪个轴是 R 不同）；R/B 位的对角才是对侧色。G 位按"对角/±2"采样必采到 G（936 饱和度 3.15→1.48 根因）
- [2026-09-21] **CFA LUT 引入时必须用解码侧最小脚本逐相位验证**：k1/k2 位写反（GRBG→0x49=GBRG 的编码）= R/B 相位对调+增益错乘 = 系统性发红；bilinear/旧版不受影响（无 LUT）正是排查线索
- [2026-09-21] **色偏类离线复现必须选彩色显著区域**：中性区域对 R/B 对调不敏感，复现"无差异"会误导排查（v0.5.30 复现漏检即此）
- [2026-09-21] 逐行翻译 GLSL 做端到端复现时，LUT/查表编码也要照翻，否则复现不了编码错误
- [2026-09-20] **fragment 内联重复卷积是性能杀手**：estAx 内联 9-tap LPF×4 方向未共享 → 12MP gMs 1414ms；中间图预计算+1-tap 读取 → 7ms（G 位像素走轻路径+L2 命中）
- [2026-09-20] 自定义 Gradle 任务（buildFilmEngine）的 so 增量重编正常，但确认重编要看产物时间戳而非构建耗时（9s 构建也可能只重编单文件）

## Next

- **M3-2 降噪（用户点名）**：真机 ISO~1200 暗光场景噪声/伪彩表现待优化；按开发计划做 RAW 域降噪（noiseProfile 自适应），先量化现状（对 231 类样张做噪声分析）再选型（引导滤波/NLM 等 ≤900ms 预算内）
- **基准色彩内核 + 高光重建**：治"整体发灰"（直方图无黑无白、P99≈185、亮部 0.00%）——tone curve/黑位/norm-controlled 高光（Filmic RGB/AgX 思路自研）
- 真机项（延续）：色温灰卡精校（WB_CAL_R/B）、MF 屈光度手感复验、桌面 Lightroom 开 DNG 随手确认

## Refs

- tags v0.5.28-m3 / v0.5.29-m3 / v0.5.30-m3 / v0.5.31-m3（versionCode 59–62 全推送）；根目录 filmkit.apk 同步 0.5.31
- docs/开发计划.md：「M3-1 RCD 质量档去马赛克」「RCD 真机首轮+性能定向优化」「RCD 色偏修复：P3 G 位填充」「发红真因：CFA LUT 位序反」四节
- 真机日志：filmkit-logs-20260920-220302.jsonl.txt（231 RCD 477ms vs 237 bilinear 267ms）；上轮 A/B 分析数据在会话记录
- 上手接续快照：handoffs/M2完成M3开工RAW端到端交付.memo.md（本窗口已吸收）
- 离线复现方法：rawpy 解 DNG + numpy 逐行翻译 GLSL（含 LUT 编码），临时脚本未入库，思路记 Pitfalls
