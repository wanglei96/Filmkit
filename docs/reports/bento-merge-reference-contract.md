# Bento 完整融合实现合同：生产代码证据与本仓库差距

日期：2026-11-15。范围：只读审计与实现合同；未修改主线独占的 [FrameRenderer.cpp](../../core/engine/src/render/FrameRenderer.cpp)。本文以本次实际读取的 `/tmp/pc-full` 生产 Kotlin/GLSL 为一手证据；**不是凭旧注释复述，也不声称已独立验证其注释所指 MGC 二进制/AOT**。现有工作树正在并行修改，下面“当前”指本次读取时状态，集成时需重新核对行号。

## 0. 必须先统一的结论

1. native clipping 从 **R16UI 原始传感器码、各帧自己的四相位黑阶、静态 sensor white** 获取，禁止在 WB、曝光放大或 RGB/guide 平均之后判断。
2. `inpaintingMask` 是 **ghost/fallback 洞的整帧准入统计输入**，不是已经执行的填洞图。生产消费链只有生成 → 读回 → 8 连通最大面积 → 接受/拒绝；没有对应扩散填洞 pass。
3. `bentoMask=M` 是 **所选 ultrashort 的接受权重**；reference 权重是 `1-M`，普通 temporal 权重是 `existing*(1-M)`。不能反过来，也不能再用普通 mismatch 把所选 short 打掉。
4. short 本身饱和的 native clip 统计是 **diagnostic only**。既不整区拒绝，也不逐像素将 short 权重清零。饱和 short 仍可提供其自身曝光域的裁切平台；把它清零再回退参考帧会制造 `1/ratio` 灰洞。
5. 接受 short 后，整幅融合结果统一乘 `1/ratio`，输出到 short 曝光域，并携带 `baselineExposureEv=log2(ratio)`。拒绝时整图仍在 reference 域。不允许逐像素切换输出曝光域。
6. 接受后的 short `globalFrameWeight=1`、`rejectionWeightScale=1`，并按此重新算 kernel sigma；不能继续沿用曝光归一后的低噪声权重。
7. **无观测不等于必须 inpaint/opposed。** 本 Bento 生产链不合成不存在的亮度：大 fallback hole 拒绝整帧 Bento；小 hole 允许参考平台残留；short 也饱和则保留 short 域裁切。opposed 是另外一套可选后处理，既不是 Bento 的隐藏必要步骤，也不能替代缺失的准入/权重/曝光合同。

## 1. 一手证据索引

| 证据 | 生产位置 | 实际行为 |
|---|---|---|
| K-gate | [Stacker:920–965](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L920-L965>) | 找第一个 `HIGHLIGHT_SHORT`；reference guide mask 合并 native sensor clipping；ratio 有限且 >1 |
| K-prepare | [Stacker:1025–1233](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L1025-L1233>) | normalized guide/双向 flow/collar admission/unscaled guide/adjusted mask/native short clipping |
| K-select | [Stacker:1250–1268](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L1250-L1268>) | 接受后所选 short 权重强置 1，sigma 重算 |
| K-weights | [Stacker:1338–1448](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L1338-L1448>) | 单张 linear-kernel mask；base/short 双互补量化 |
| K-consume | [Stacker:1473–1604](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L1473-L1604>) | base、short 分别直接参与 merge，噪声 strength 捕获使用一致权重 |
| K-temporal | [Stacker:1612–1785](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L1612-L1785>) | short 不再进 temporal loop；普通帧/long 采用不同 exclusion 分支 |
| K-assess | [Stacker:6372–6422](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L6372-L6422>) | 三条整帧拒绝理由；short clipping overlap 只统计 |
| S-adjust | [Shaders:1112–1183](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1112-L1183>) | 非对称负差 ghost fallback、R8 adjusted mask、洞标记 |
| S-native | [Shaders:1525–1589](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1525-L1589>) | 对齐后 3×3 quad × 四相位整数 near-clipping 检测 |
| S-merge | [Shaders:1597–1847](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1597-L1847>) | Bayer quad flow、dual spatial kernel、始终启用双绿色格点 |
| K-cal | [Stacker:3375–3504](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L3375-L3504>) | 各帧黑阶、guide WB 与 Bayer 非 WB calibration、噪声曝光传播 |

## 2. 输入域与 calibration 合同

### 2.1 几何、角色与曝光

- RAW 是完整原生 Bayer mosaic；guide/rejection 是 `ceil(W/2) × ceil(H/2)`，每 texel 一 Bayer quad；最终 acceptance/merge-weight 是 `floor(W/4) × floor(H/4)`。不能把本仓库 32 mosaic-px mismatch cell 直接当成 PC RAW/4 mask。
- 证据：[MgcSpatialRejectionGeometry:26–49](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/MgcSpatialRejectionGeometry.kt#L26-L49>)。
- `ratio=valid(reference.exposureProduct)/valid(short.exposureProduct)`，Bento 路径检查有限且 `>1`，此处**没有**普通 temporal 的 `[1/64,64]` clamp；普通 temporal 才 clamp 这个范围，`SHADOW_LONG` 还要求 scale `<1`。证据：[Stacker:957–965](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L957-L965>)、[Stacker:4935–4947](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L4935-L4947>)。
- reference-only 模式令 ultrashort index=-1，禁用 bracket/temporal 贡献；普通模式只选第一个 `HIGHLIGHT_SHORT`；所有 `HIGHLIGHT_SHORT` 在 temporal loop 都跳过，拒绝的 short 不能悄悄回到常规融合。

### 2.2 每帧黑阶与三份 short calibration

设 `B_f,c` 为该帧 canonical R/Gr/Gb/B 黑阶，`W` 为静态传感器 white，`s_f` 为对 reference 的曝光尺度，`g_c` 为 guide calculation WB。

```text
guideGain[c] = g[c] * s_f / max(W-B_f,c, 1)
guideBlackTerm[c] = -B_f,c * guideGain[c]
bayerGain[phase] = s_f / max(W-B_f,canonical(phase), 1)
bayerBlackTerm[phase] = -B_f,canonical(phase) * bayerGain[phase]
```

- dynamic black 若四个 positional 值均有限、`0<=B<W` 则按 CFA phase→canonical 重排；否则使用静态 canonical black。
- calculation WB：两绿平均作基准，R/B 除此基准并 clamp `[1e-3,64]`，两绿都为 1。**guide 有 WB，Bayer merge 无 WB**。这是现有 `linAt()` 已乘 WB 与 PC Bayer 输出域的重要差异。
- short normalized calibration：`s=ratio`，用于 normalized guide、noise LUT、alignment 和后续 merge。
- short unscaled calibration：`s=1`，用于 ghost adjusted-mask 的原生曝光 guide，以及 native clipping。并不是把 normalized guide 除 ratio 就可宣称数值等价：guide 含 nonlinear noise/variance/green-clipping 分支。
- accepted selected calibration：保留 normalized 的增益与噪声，仅把 `globalFrameWeight/rejectionWeightScale` 置 1 并重算 `kernelSigma`。
- guide 构造也必须一致：3×3 quad、separable `[.25,.5,.25]` RGB 平均；绿 variance 超过 `3*(2*noiseLut.green)` 且 `forceReferenceColorRgb==0` 时使用中心绿、负 variance；否则 RGB 平均；中心绿达到 `greenClippingPoint` 时 RGB 全写 10000。证据：[guide shader:31–108](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L31-L108>)、[guide binding:4405–4436](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L4405-L4436>)、[WB:385–405](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L385-L405>)。

### 2.3 噪声与 spatial kernel 参数

- guide 噪声：`shot=sourceShot*(WB*s)`，`readVariance=sourceRead*(WB*s)^2`；camera RGB 噪声另经 Bayer→RGB 映射后分别乘 `s`、`s²`。
- 普通 spatial `maximumMergeWeight=min((baseGreenRead/(alternateGreenRead*s²))^exponent,50)`，默认缺 motion metadata 的 exponent=1；`rejectionWeightScale` 同值。`expectedMergeWeight` 只诊断，不是实际 AOT merge 权重。
- kernel multiplier 对 mergeWeight 在 `(10,1)`、`(30,1.414000034332275)` 间线性插值、两端钳位；`sigma=1/(baseSpatialScale*multiplier*selectedFrameMultiplier)`，selected multiplier 默认 1。
- `baseSpatialScale` 自 reference SNR×sqrt(frameCount) 取插值：BAYER `(14.5,.6),(29.5,.42),(44,.35)`；RGB `(2.3,.32),(40.1,.365),(51.1,.4),(71,.28)`。
- 接受 short 后 mergeWeight=1，所以 multiplier=1，sigma=`1/baseSpatialScale`。merge 与 strength/noise 模型必须共享这份 selected calibration。
- alignment gain=`s*16384/(W+1)`，不是仅 `s`。证据：[MergeTuning:45–178](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/MgcSpatialMergeTuning.kt#L45-L178>)、[AlignmentInputScale:14–17](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/MgcAlignmentInputScale.kt#L14-L17>)。

## 3. native clipping mask：逐操作合同

参考 [native shader](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1525-L1589>) 与 [CPU binding](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L5206-L5264>)：

1. 每相位 threshold code=`B_phase+(W-B_phase)*(250/255)`；比较为 `rawUint>=uint(ceil(thresholdCode))`。RAW 保持 integer 到比较，不能先 float/half 后比较（源码明确规避 Mali FP16 lowering）。
2. output `p` 对应 reference quad=`min(p*quadsPerTexel+quadsPerTexel/2, bayerSize-1)`。
3. 取其 quad 中心 normalized UV；按 `uFlowScaleOffset` 采 flow；`sourceFlow=flow.xy*bayerSize`；`sourceAnchor=roundEven(referenceQuad+sourceFlow)`。
4. 遍历 sourceAnchor 周围 `[-1,1]²` 共 9 个 quads，各 quad clamp 到有效范围、读取 4 CFA phases；**任一 phase** near clipping 就输出 1，否则 0。
5. Bento reference/short mask：RAW/2，`quadsPerTexel=1`。reference 是 zero flow，short 是已 admission 修正的 flow。Long acceptance：RAW/4，`quadsPerTexel=2`。
6. 输出 R8/NEAREST。输出坐标超界直接写 1。

reference highlight H 不是仅 native 二值 mask：先将 base guide RGB clamp `[0,1]`、round 到 16 bit；`ramp=clamp((max(rgb)-250/255)/(1-250/255),0,1)`，再 `H=max(ramp,nativeReferenceClip)`，落 R8/NEAREST。因此 guide ramp 尚未到 1 时也可有 H>0；native 任一相位 clipping 强制 H=1。证据：[highlight shader:965–994](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L965-L994>)。

候选 early gate 是 H 的非零像素数>0，不是 clipped ratio 最小阈值。GPU SSBO count 和 CPU R8 count 是同一条件的两条执行路径。

## 4. flow / collar admission：不能用“无裁切”替代几何证据

1. normalized short 和 reference 分别做 forward 与 reverse alignment；两网格大小相同。
2. support shader 在 guide/quad 坐标工作。forward 两轴绝对值 `<1e5`、映射 tile 中心在图内；reverse 取映射点所属 tile，也须两轴 `<1e5`；`fbError=length(forward+reverse)` 必须 `<=2`，否则 support=0。
3. tile 内 4×4 等距点：越界跳过；H>0 记 clipped 并跳过；base/short RGB 任一绝对值>2 跳过；其余 `delta=dot(abs(base-short),(.25,.5,.25))`，agreement 累加 `1-smoothstep(.04,.16,delta)`。
4. `valid>=4 && clipped==0` 才有 `confidence=(agreement/valid)*(1-smoothstep(.75,2,fbError))`；否则 0。输出 RGBA=`confidence,fbError,valid/16,clipped/16`。证据：[collar shader:1035–1107](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1035-L1107>)。
5. CPU `tileHasHighlight` 由 **H 的所有像素**投影到 tile OR 得出，不是只看上面的 16 个 sample，更不是将所有 unsupported tile 当成 highlight。
6. supported 必须非 highlight，confidence 有限且 `>=.4`，flow xy 有限。全局 supported 少于 4 时无 global flow；有 highlight components 且无 global flow，`resolveGeometry=null`，整帧 Bento 最终拒绝。
7. highlight tiles 按 8 连通分组；组外 radius=4 tiles collar 去重，仅纳入 supported。collar>=4 时每 tile 用 `confidence/distance²` 插值 flow；不足4时用 frame-wide confidence-weighted per-axis median。
8. 只改 highlight tile flow xy，保留其余 alignment 分量。geometry=null 时保留原 measured flow只是为了算完 mask/诊断，**不能借此继续接受 short**。
9. Spatial `createConvertedAlignment` 将每 tile flow 展开为 RAW/2 NEAREST、tile 内恒定 normalized UV flow，另存 3×3 tile min/max flow range 的 normalized length；不是任意双线性平滑场。证据：[Admission:37–176](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/BentoRegionAdmission.kt#L37-L176>)、[conversion:4323–4355](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L4323-L4355>)、[convert shader:2723–2753](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L2723-L2753>)。

## 5. adjusted mask 与 inpainting/hole mask：精确公式

全部在 RAW/2。base 为 reference guide；short 是 **unscaled guide**，按最终 admitted flow、mirror UV、LINEAR 采样。

```text
base = round(65535*clamp(baseGuide,0,1))/65535
short = clamp(sample(unscaledShortGuide, mirrorUv(uv+flow)),0,1)
gainedShort = floor(65535*clamp(short*ratio,0,1))/65535
e = length(min(gainedShort-base, vec3(0)))
fallback = e>=0.9 ? floor(e*255)/255 : 0
fallback = H>0 ? fallback : 0
M = floor((1-fallback)*H*255)/255      // R8 attachment最终约束为[0,1]
base8 = round(clamp(base,0,1)*255)/255
hole = fallback>0 &&
       (H>=1 || (min(base8)>=128/255 && median(base8)>=250/255))
```

- e 是 RGB **欧氏长度**、仅保留 short 比 base 暗的负差；不是相对误差、不是 max channel、不是对称 absolute difference，也没有 noise denominator。
- e/fallback 可能超过 1（上界 sqrt(3)），shader 没有显式 clamp fallback；写到 R8 时负 M 饱和到 0。不能擅自把 `.9` 改成 `.09`。
- hole 判断的 H>=1 分支很关键：native 任一 CFA phase clipping 可强制 H=1，因此不要求三色都近白；不能沿用“仅白色洞/三通道全饱和才 hole”的旧说法。
- hole 不是 `nativeShortClip`；短帧自身 clipping 不参与这条 ghost fallback 公式。
- 参数来自 [Stacker:8405–8410](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L8405-L8410>)；实际 shader 在 [S-adjust](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1141-L1180>)。

## 6. 整帧 acceptance 与全部退出分支

按顺序：

| 条件 | 结果 |
|---|---|
| reference-only 或没有 HIGHLIGHT_SHORT | 不评估 Bento，不插 short，不改输出曝光域 |
| H 非零像素数=0 | early reject `no_clipped_pixels` |
| ratio 非有限或<=1 | `check` 失败；不是静默接受一个任意 scale |
| 有 candidate，但 geometry=null | reject `no_alignment_support` |
| hole mask 最大 8 连通分量面积>=80 | reject `large_hole_needing_inpainting` |
| 其他情况 | accept，reason=`none` |

最大面积单位是 **RAW/2 guide 像素**，不是全分辨率 photosite，不是总 hole 面积；多个互不相连小洞不累加。79 可接受，80 拒绝；对角相接属于同一分量。实现见 [Topology:5–47](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/BentoFallbackTopology.kt#L5-L47>)。

`ultrashortClippingOverlap=#(H>0 && nativeShortClip>0)/#(H>0)` 只日志诊断，完全不在 reason/accepted 的条件中。拒绝后释放 Bento mask/临时 flow、关闭 short image，且 temporal loop跳过该角色，输出曝光参数保持 reference。

## 7. 接受后的 mask、merge 与所有特殊分支

### 7.1 mask 消费

- `bentoRewriteWeight` 就是 `existingWeight*(1-mask)`，无 existing 时 existing=1。证据：[rewrite:1196–1202](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1196-L1202>)。
- H/adjusted M 是 RAW/2，M texture 为 R8/LINEAR；`baseWeight=R8(1-sample(M))` 重采样到 RAW/4；`shortWeight=R8(1-sample(baseWeight))`，使二者处在同一量化域。
- reference 使用 baseWeight；selected short 使用 shortWeight；selected short 不走普通 rejection/mismatch 再乘一次。
- ordinary temporal 帧：`measuredAcceptance*(1-M)`。
- `SHADOW_LONG` 是独立分支：依次乘 `(1-referenceHighlightMask)` 与 `(1-alignedLongNativeClip)`，**不是**普通 `(1-M)` 分支；long即使 Bento拒绝仍执行这两项保护。
- 同一实际 merge acceptance 与 calibration 进入 strength/noise capture。不能只改采集/统计、不接消费。

### 7.2 linear-kernel mask：生产代码中必须公开的 domain 不一致

预期语义/GLSL 操作：只取 accepted selected slice，3×3 邻域 `min!=max` 输出严格二值1，否则0；同一张 mask供所有 Bayer merge，quad中心采样。它不是 temporal rejection 的 union，也不是按差值幅度渐变。

**但当前 PC 实际 wiring 存在不能隐瞒的矛盾**：[Stacker:1366–1369](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L1366-L1369>) 直接传 RAW/2 `bentoMask`；[renderLinearKernelMask:4906–4917](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L4906-L4917>) 将 uSize/输出设 RAW/4；[shader:940–961](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L940-L961>) 却 `texelFetch` 而非 normalized resampling。按实际代码，它检测的是 RAW/2 源的左上 RAW/4 大小范围，不能声称“已经先正确重采样为 RAW/4 selected plane”。

实现合同必须显式区分：

- **精确 PC 当前 replay**：复制上述实际 fetch/domain，记录此行为；
- **修正后的原生域一致合同**：先完成 RAW/4 shortWeight 重采样，再在同域做3×3 equality，或另显式重采样 M。这是需单列验证的修正，不能伪装成生产源码逐行等价。

diagnostic 优先级也明确：disableLinear→0；forceLinear→1；identityTemporal→0；否则 accepted Bento生成；否则0。identityTemporal 模式还强制 base/short/temporal acceptance=1、跳过 temporal exclusion，不能拿其图像当生产结果。

### 7.3 MergeBayer 完整 transport/kernel

- flow原生单位 Bayer quads。每 tile 8×8 quads；tileId=outputQuad/8，outputQuad=outputRawPixel/2，phase=outputRawPixel&1。
- 默认 `interpolation_flow_tolerance=-1`，直接取 tile flow，不插值。可选正值路径：四候选任一与本 tile flow **任轴差>=8*tolerance** 就取消整个像素插值；否则围 tile 中心双线性。证据：[flow interpolation:1660–1701](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1660-L1701>)、[默认常量:8370–8374](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L8370-L8374>)。
- tilePosition=tileId*8+flow；alignedTileQuad=roundEven(tilePosition)；subquadPixelOffset=2*(alignedTileQuad-tilePosition)；anchor=alignedTileQuad+offsetWithinTile。
- frameWeight 在 **每个 RAW pixel center UV** 对 RAW/4 acceptance LINEAR采样，再乘 globalFrameWeight；四相位不强行共享一个 weight texel。
- linear-kernel mix 在 quad中心采样；kernel=`mix(exp(-.5*sigma²*(dx²+dy²)), max(1-.4*length(offset),0),linearMix)`。注意 sigma 是倒空间尺度，不能误写成除 sigma²。
- 遍历3×3 source quads，quad边界 mirror；同相位 sampleOffset 两轴均 abs<=1.5 才贡献；外圈 y abs>2.5 可提前跳过。
- **两绿色格点始终互相贡献**，不由 linearKernelMask 开关：RGGB/BGGR phase1↔2，偏移(-1,+1)/(+1,-1)；GRBG/GBRG phase0↔3，偏移(+1,+1)/(-1,-1)；otherOffset也需两轴abs<=1.5。R/B保持自己的相位。顶部“only same CFA phase”注释不完整，应以1785–1835实码为准。
- 本项目若下游接口要求WB后mosaic，可在完整CFA merge结果后按phase乘WB：两绿WB统一为1，R/B不跨phase，故在无中间clamp且kernel/acceptance不依赖此改动时，这与每个相应phase样本乘固定WB线性等价；不能借此改变guide/noise域或宣称全clip已中性。
- normalized sample=`raw*bayerPhaseGain[phase]+blackTerm[phase]`，无 guide WB；每帧输出 `intensity*frameWeight, kernelWeightSum*frameWeight`；所有帧 additive blend 后统一求和相除，**不是各帧先归一化再平均**。证据：[merge:1704–1845](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1704-L1845>)、[binding/blend:6454–6503](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L6454-L6503>)。
- `mergeMethod=SABRE` 的 dispatcher走另一个 covariance RBF shader而非上述 spatial dual kernel；不能把本合同的 kernel声称适用所有 Sabre/RGB路径。Bento准入与权重仍共享。

## 8. 全曝光归一、裁切平台与 opposed 边界

### 8.1 输出曝光是整帧决定

[OutputExposure:16–29](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/MgcSpatialOutputExposure.kt#L16-L29>)：拒绝为 scale1/无baseline覆盖；接受为 scale=1/ratio、baseline=log2(ratio)；shot noise系数乘scale、read variance乘scale²。

BAYER收口 [normalizeBayer:1998–2002](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialShaders.kt#L1998-L2002>)：

```text
out = round(clamp(sumIntensity/max(sumWeight,1e-8) * outputExposureScale,0,1)*65535)
```

这是 integer-export合同；若本仓库保留HDR float scene-linear，可不做同一uint16量化，但必须公开相应camera white、WB与tone曝光换域，不能仅把fused乘1/ratio后让图像整体变暗，也不能在mark/mask阈值仍用reference白点。

RGB有额外真实生产行为，不能套Bayer旧注释：[RGB normalize:258–280](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcSpatialRgbMergeShaders.kt#L258-L280>) 先求merge，`shortShare=clamp(sample(actualSelectedShortWeight),0,1)`，`whiteScale=mix(outputScale,1,shortShare)`，`out=min(merged*outputScale,cameraWhite*whiteScale)*LSC`，最后clamp/uint16。参考fallback平台与short供应高光的有效白点连续过渡；LSC在camera-white clip之后。这是RGB生产分支，不可说所有输出都只有一个无条件最终clip。

### 8.2 本项目保留 reference radiance 的等价单位变换（主线集成约定）

本项目没有 PC 的 DNG baselineExposure 整链消费时，可以直接保留 reference radiance，但必须把它写成**完整单位变换**而非删除输出合同。设 `F=sumIntensity/sumWeight`（reference units）、`r=ratio`：

```text
PC short-domain float = clamp(F/r, 0, 1)
equivalent reference-domain float = r*clamp(F/r,0,1) = clamp(F,0,r)
```

这样最终无须显式除r再乘r、无需额外baseline `+log2(r)`，output noise模型也保持reference单位，不再施加1/r、1/r²。**但**所有依赖输出white/clip/headroom的阈值必须一并换域；如果不做`clamp(F,0,r)`，那是明确保留超shortwhite浮点headroom的扩展，不是PC integer-export逐位等价。PCuint16量化误差也不会因代数消去自动保留。

RGB有有效CameraWhite约束时，等价reference写法为 `clamp(min(merged,cameraWhite*mix(1,r,shortShare))*LSC,0,r)`，包含最终integer-export前的clamp换域，不是一个无条件`min(merged,r)`。guide/mask/admission计算仍保持原先reference与unscaled-short两域，不能因最终用reference单位而取消unscaledguide或整帧hole准入；base-onlyfallback平台仍是1，selectedshort自身clip平台为r，过大的ghosthole仍须整帧拒绝。

### 8.3 三种“没有真值”必须区分

| 场景 | 生产Bento处理 | 是否需要 opposed / inpaint |
|---|---|---|
| base饱和、short有几何且观测有效 | M高时直接merge short原生CFA观测 | 不需要；另做亮度合成会污染真值 |
| short也饱和，但不是ghost negative-fallback洞 | shortClip仅诊断；保留short在其自身域的饱和平台 | 不凭此启动inpaint，也不清short权重；超short动态范围信息本来不存在 |
| short被ghost fallback拒绝，base也只剩平台 | hole连通面积>=80整帧拒绝Bento；小洞允许接受但没有填洞pass | 本参考链不做inpaint；若要额外恢复需独立完整算法/有效性证据，不能归因于Bento |
| 无global/collar几何证据 | 整帧拒绝Bento，不把任意flow当真值 | opposed最多是单帧的独立选择，不是几何准入替代 |

**全clip的色彩风险也不能混淆**：PC guide的 `centerGreen>=greenClippingPoint → vec3(10000)` 只服务guide/H/fallback，不会替换merge的原始CFA观测；Bayer merge没有WB，饱和平台在随后WB之后仍可能成为 `(gainR,1,gainB)` 的非中性颜色。Bento本身不保证修正该品红/其他色偏。生产RGB分支通过真实source `cameraWhite`（要求三分量有限、>0且<=1，统一乘referenceWhite标量）做前述有效白点clip，而不是凭guide全白推断merge已中性；证据：[CameraWhite binding:6548–6561](</tmp/pc-full/app/src/main/java/com/hinnka/mycamera/processor/GlesMgcRawSpatialStacker.kt#L6548-L6561>)。若本项目需在Bayer/scene-linear链新增中性clip或opposed，应公开为独立成像选择，不能称原Bayer merge已有这一功能。

所谓“无观测”若指未裁切scene值不再可得，是信息论缺失；opposed只能推测色比/亮度，不可标成实测。Bento本身没有“必须用opposed填洞”的源码依据，也没有保证任何饱和灯面真实HDR细节可恢复。若本仓库保留opposed，必须把“有实际short贡献的观测”与“所有可用观测都clip/被几何拒绝”的 provenance分开，不能按WB后值>1就再重建恢复出的HDR观测。严格对齐所审Bayer路径无需新增opposed；是否启用独立单帧高光重建应由单独合同与真机效果决定。

## 9. 对照本仓库：需要主线逐项补齐的生产差异

| 本次读取的现状 | 与参考的实质差异 / 主线合同 |
|---|---|
| [Fuse:3267–3287](../../core/engine/src/render/FrameRenderer.cpp#L3267-L3287) `linAt`已WB，satW逐phase软裁切，short darkW `.02/.08` | PC Bayer无WB、accepted mask决定归属，没有这组额外short satW/darkW；若保留即为算法偏离，不能称Bento等价 |
| [Fuse:3347–3376](../../core/engine/src/render/FrameRenderer.cpp#L3347-L3376) 每帧同相位bilinear后按satW/mismatch平均，全权重零退r0 | 缺Bento H/M/hole/整帧准入；short clip处会退reference低平台。不能用简单`allSat→r0`代替short-domain饱和输出 |
| [Mismatch:3503–3519](../../core/engine/src/render/FrameRenderer.cpp#L3503-L3519) 4px步进的样点固定同一CFA phase，双向差平方、noise归一 | 不是Bento RGB负差mask；baseclip与short真实高光亮度差会被误判运动；selectedshort必须独立Bento slice取代，不再乘普通mismatch |
| [CollarSupport:1233–1262](../../core/engine/src/render/FrameRenderer.cpp#L1233-L1262) 只采reference RAW固定phase、0.98*white、二值support | 缺四phase黑阶native mask、所有H像素tile OR、normalized RGB agreement、forward/reverse consistency |
| [collarFlowFix:11641–11730](../../core/engine/src/render/FrameRenderer.cpp#L11641-L11730) unsupported全分组、普通中值、1/d² | PC只组highlight tiles、支持阈值.4、confidence weighted median与confidence/d²；unsupported但未highlight的tile不可等价视作highlight |
| [passCollarFlow:11805–11824](../../core/engine/src/render/FrameRenderer.cpp#L11805-L11824) 无论`fixed`真假最后return true | **失败被吃掉**；caller可能继续用unsupported flow。必须把geometry失败送整帧acceptance，不能只改日志 |
| [caller:18307–18328](../../core/engine/src/render/FrameRenderer.cpp#L18307-L18328) 修正flow只送satfix；[Fuse binding:16358–16361](../../core/engine/src/render/FrameRenderer.cpp#L16358-L16361)仍绑定原tex_off | 修正flow未统一消费于最终mismatch/merge；需统一最终flow或显式Bento专用flow，不能“算了未用” |
| [frame scale:18053–18107](../../core/engine/src/render/FrameRenderer.cpp#L18053-L18107) 全帧`2^-EV`归一到reference | 只是输入统一，不是accepted后整幅输出1/ratio；需要分清inputScale与outputExposureScale并传播噪声/white/tone曝光 |
| [production inpaint switch:314–318](../../core/engine/src/render/FrameRenderer.cpp#L314-L318) false | 与所审PC“未执行scalarinpaint”的事实一致；不能以旧[18037–18039](../../core/engine/src/render/FrameRenderer.cpp#L18037-L18039)注释声称PC通过扩散填饱和洞 |
| [HL gate:14833–14861](../../core/engine/src/render/FrameRenderer.cpp#L14833-L14861) `p.hr_raw && !bracket_info_ok` | opposed是本仓库独立后链；不能替代Bento准入，也不能从开关默认推断PC必须opposed |

主线实现不应继续调用先逐photosite satfix修改reference后再做Bento：参考生产保留原reference RAW，base/short各自calibration、flow与mask进入同一accumulator，避免双重注入与相位provenance混淆。

## 10. 完成判据与建议的逐项测试

必须输出同一张照片token下的 H、nativeReferenceClip、nativeShortClip、normalized/unscaled guides、admittedflow、collarSupport、M、hole、RAW/4base/shortacceptance、linearKernelMask、merge sums/weights和outputScale。日志至少包括：accept reason、ratio、H非零数、largesthole、shortclipoverlap、supportedtiles、collar/globalregions、selectedglobalweight、sigma、baseline EV。不能只有最终JPEG主观评价。

最小确定性回归矩阵：

1. 四种CFA、四相位逐一clip，含“两个绿平均未clip但其中一绿nativeclip”；WB>1但sensor未clip；动态phase黑阶改变threshold。
2. `raw=ceil(B+(W-B)*250/255)-1/0/+1`，以及Mali整数比较；short归一后>1但native未clip不得误判。
3. zero/no/nonfinite flow、FB error .75/2/略大2、confidence .4两侧、supported3/4、collar3/4、tilegroup对角连接、unsupported非highlight不入组。
4. fallback error .9两侧、只正差不触发、单通道负差与三色负差；H=0/ramp/1、min128/255、median250/255边界。
5. hole最大连通79/80、多小洞、仅对角相连；shortClipoverlap=0与1在其他输入相同时不改变accepted。
6. M=0/1及R8最后两级、base+short互补、所有normal/longexclusion分支、selectedweight强置1；shortclippedcore不出现reference平台骤降。
7. 8quad tile边界、roundEven半数值、全部CFA、双绿始终参与、linearvsGaussian选择、flow插值默认禁用及正阈值取消分支。
8. 输出接受/拒绝两域：常量scene信号除黑/每帧gain归一守恒；outputscale全图一次，噪声shot/read与baseline随域迁移；RGBcameraWhite随shortShare连续。
9. 单列linear-kernel的RAW/2→RAW/4生产domain不一致replay与修正对照，不能混入其他改动后说是“照抄原版”。

结论：正确闭环是 **native mask → 完整几何admission → ghost/hole → 整帧acceptance → selected calibration/互补mask → 原生CFA spatial merge → 全图输出曝光与噪声换域**。跳过任何一项后再加scalarinpaint/opposed补缝，不构成所审Bento生产实现的对齐。
