---
summary: HDR+包围曝光落地与真机三轮收敛
created: 2026-10-24 22:00
updated: 2026-10-24 22:00
status: active
---

# HDR+ 包围曝光落地与真机三轮收敛

## Summary

0.12.75→0.12.84:HDR 曝光融合专项从 P0 诊断走到 P3 架构对齐 PC RAW——最终形态 = 4 帧
(0EV×2 + 2.2EV + −1.5EV)**全部进既有对齐+DFT 跨曝光堆叠**(frame_scale=2^−ev 归一),
Mertens 侧帧直通架构经三轮真机迭代后退役;0.12.84 UI 两档化(「HDR+ 包围曝光」默认开,
旧 bracket 开关删除)。真机确认融合生效(hdrFs/confMed 0.833/brkRatio 2.83)。

## Done

- **0.12.75 P0 诊断**:`bracket.actual` 日志(每帧实测 EV=log2(sec·iso/帧0) + RAW 抽样
  mean/p995/sat);`rawSampleStats`/`evOffsetSecIso` companion 化 + BracketDiagTest。
- **0.12.76 P1 采集**:帧序 0/0/+2.2/−1.5(PC buildHdrBracketEvOffsets 同构,参考帧恒帧 0);
  `planBracketExposure` 逐行照抄 PC planManualExposure(先动快门、正 EV 10ms 顶、钳后 ISO 补偿);
  AE 全帧锁定(2026-09-29 教训);参考帧压短/旧 bracket 旁路。
- **0.12.77-81 P2(教训密集,架构已被 P3 取代)**:Mertens 侧帧直通(侧帧独立前链→D4→
  kFsHdrMertens 逐像素加权,权重/三级归一/deghost 逐行照抄 PC GlesYuvStacker.kt:3451)。
  三轮真机:① 侧帧链跑 zero 数据(D4-hdr≈D4-scene)→ 调序修复;② 长帧 ISO 14336 数字
  增益区 + 侧帧零降噪 → 噪点爆炸/体积翻倍(ISO 钳模拟顶 + 侧帧 P0 B×gr²);③ 角色判定
  自创(实测 EV>0.3)暗场失效 → 改调用方显式 hdrHighIdx;④ 高光斑块 → PC 完整 deghost
  shader。最终仍结构性不足(单帧 ISO 3200 读噪声 10×zero 且无时域,contrast 权重选噪声)。
- **0.12.82 P3 架构对齐 PC RAW(定稿)**:侧帧直通退场;4 帧原样进引擎,frame_scale[i]=
  2^−ev[i] 逐帧归一,全部进对齐(capturePyr 逐帧)+ DFT 跨曝光堆叠——复用 bracket 机制
  (satW 饱和权重让裁切像素退出、短帧接管高光;mismatch 拒运动)。零新 shader/纹理。
- **0.12.83 per-frame σ**:DFT/mismatch 读噪声项 B×(gr·s)²(gr=ISO_i/ISO_0;长帧 gr≈9.3,
  不修则 σ 低估 86× → 长帧整帧回退参考帧);散粒项 A·s。旧 bracket gr=1 零回归。
- **0.12.84 UI 两档化**:旧 bracket 开关删除(代码路径保留无入口)、P1 采集档删除、
  改名「HDR+ 包围曝光」**默认开**(多帧融合触发即自动走);prefs 旧值 2→1 兼容。
- 每包全量单测 0 失败 + validate-shaders + 签名/版本/JNI 三验证。

## Decisions

- **架构对齐 PC RAW 而非 YUV bracket(用户指正"PC 有专门的 raw 成像模式"后重查源码)**:
  PC 的 `shouldUseJpgMaxHdrCapture` 有 `!isRawCapture` 门槛(YUV bracket 只在非 RAW 走);
  RAW 模式 = `RawmaxExposurePlanner`(实测曝光中位数分组的 base/normal/short/long)+
  `processBurstRaw` 全帧进 MGC 融合。我们的跨曝光帧因此全部进 DFT 堆叠。
- **帧角色由采集端显式指定**(PC setTag 同构):按实测 EV 推断是自创,暗场长帧被 10ms
  顶钳到 −0.1EV 时误判关闭(真机 203750 实锤"融合没工作")。
- **侧帧 ISO 钳模拟增益顶 3200**(v0.10.4 纪律):range 顶 19200 让暗场长帧进数字增益区,
  +2.2EV 全是数字放大(光子不增、读噪声 ×46)。**不自动退化**(用户明确否决):长帧无增益
  也照采照融合,堆叠 σ/权重自动处理。
- **per-frame σ = HDR+ "按实际合并更新噪声方差"**:σ²=A·s·x′+B·(gr·s)²。
- **6 帧档暂缓**:maxImages=4 闪退史 + DFT 4 输入固定(shared 重写)+ 4 帧真跨度验收未做。
  PC 语义确认(用户问):帧数 = 多帧设置数 + 2,提帧数只提 0EV 降噪,两条路径皆然。
- **UI 语义**:「HDR+ 包围曝光」= bracketing(0/−1.5/+2.2 EV 序列采集)+ HDR 合成;默认开
  (用户拍板"开多帧融合直接自动走")。

## Pitfalls

- **GL feedback loop**:Mertens 的输入不能与 fbo attachment 同纹理(tex_scene_ 既读又写)
  → zero D4 先拷独立纹理再融合。
- **固定输入纹理陷阱**:passRawDenoise/RCD 固定读 raw_tex_ —— 侧帧传独立 Bayer 纹理时
  侧帧链实际跑在 raw_tex_ 的 zero 数据上(D4-hdr≈D4-scene 融合空转,0.12.77 真机)。
  修法 = 调序:zero D4 存走后 high 重传 raw_tex_。
- **自创机制三连**:①实测 EV 推角色(暗场失效);②自动退化(用户否决"PC 没有这个机制");
  ③"HR 修复的饱和区会被 highlightValidity 拒绝"的判断(错:0.96 门只拒 0.96 以上,
  0.5~0.96 肩部全放行)——项目纪律"先照抄一手实现"反复被真机验证。
- **对比测量必须配准**:手持 15s 间隔构图漂移(实测 dy−73 dx−164),未配准 ROI 对比无效。
- **Edit 长文件两坑**:注释替换混入乱字符(`# 渚`);submitRaw 与 submitRawBurst 的
  ChainCtx 尾部代码同构,replace 命中错误位置(编译期才暴露)——改后必须全 grep 核对。

## Next

1. **逆光/日落三档验收(J1–J10)**:等天气,用户验完通知。判据:hdrFs 出现 ≈0.22(长帧
   真 +2.2EV)、天空层次(J1 ≤200/J2 ≤1%)、高光边缘干净、暗部噪声(J8)。
2. **6 帧档**(0EV×4 + 2 侧帧):DFT 扩 6 输入(shared ×1.5 需真机验证 Mali 上限)+
  maxImages=4 流式采集(6 burst 请求共用 4 槽)+ Align 6 帧,约 2-3 天。
3. **P4**:gainmap 改用融合后的跨曝光 HDR 参考(现从 SDR base 反推、中灰基线 1.1 无长帧);
  teq/曲线重标定。
4. 暗场(长帧无增益)行为已确认正确(输出≈同曝光堆叠,无斑块无提亮)。

## Refs

- 设计:docs/engine-hdr-exposure-fusion-design.md(§4.2/4.3 的 P2/P3 形态已被 P3 实际
  架构取代,以本快照为准)
- PC 源码:/tmp/pc-full(MultiFrameStacker.kt:292 processBurstRaw/enableHdrFusion、
  RawmaxExposurePlanner.kt(实测曝光中位数分组)、GlesMgcRawFusion.kt:227、
  Camera2Controller.kt:8199(!isRawCapture)、HdrBracketConfig.kt(planManualExposure)、
  GlesYuvStacker.kt:3451(Mertens,供 P3 对照))
- 包:filmkit-0.12.84-HDRUI.apk(533);回退 0.12.76-HDRP1(525,两档开关的"关"= 同曝光 MFNR)
- 日志:vivo办公套件 filmkit-logs-20261024-215159(0.12.84 默认开验证)、213540(0.12.83 σ)、
  203823(0.12.78 斑块)、201931(0.12.78 噪点)、211358(0.12.80 角色修复)
