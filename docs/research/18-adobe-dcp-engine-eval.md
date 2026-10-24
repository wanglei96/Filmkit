# 18 · AdobeCurve 引擎 + DCP 评估(对照现有 sigmoid)

> 2026-10-24。起因:0.12.66 验收通过后,用户提出"评估是否做 Adobe 引擎配合 DCP 使用,和现有 sigmoid 优缺点对比"。
> 证据源:PC 快照 `/tmp/pc-bjzhou`(main Kotlin 被剥离,以下 PC 侧接口结论来自 test 文件 + 本仓 06 号调研的历史取证);本仓 `docs/research/05/06/13`;Adobe 社区/生态检索。
> 本文只做评估,不含实现。

## 0. 结论速览

1. **值得做,但分两期,且不解决当前已挂账的两个痛点**(暗部对比度、PC 渲染差距定性 —— 前者已定案在 demosaic 档,后者需要的是对拍数据不是新引擎)。
2. **期 A(小)**:纯 ACR3Curve 引擎(引擎 2,零资产),对齐 PC 默认观感的载体,工作量 ≈ sigmoid 引擎一期。
3. **期 B(大)**:DCP 资产管线(TIFF 解析 + HueSatMap/LookTable GPU 采样 + 按镜头绑定/导入 UI)。战略价值:富士胶片模拟等**正版外观生态**直通 —— 与"胶片相机"定位天然契合。
4. **sigmoid 的两个短板(色相保真、暗部对比度)AdobeCurve 都不直接修**:sigmoid 曲线端已到天花板(ACES/Oklab/contrast 1.7/1.8 四轮全回退),换 display-referred 范式是另一条路,不是 sigmoid 的补丁。

## 1. PC 的 AdobeCurve + DCP 到底是什么(一手事实)

### 1.1 管线(test + 06 号调研交叉)

- **线性 pass(公共)**:CCM → clamp → **线性域 DCP HueSatMap**(`RawLinearRcdPass.kt:277-286`,`uLinearDcpHueSatMap` sampler3D)。
- **引擎 pass(AdobeCurve,工作空间 ProPhoto)**:**HueSatMap → LookTable(与 ProfileExposureRamp 次序可切换)→ ToneCurve(`applyAdobeCurve` = ACR3Curve)→ gamut 矩阵输出,无 OETF**(`AdobeCurveToneAlgorithm.kt:374-393`)。
- 查表在 **HSV 域**(`sampleDcpMap(sampler3D, divisions, hsv)`),与 DNG 规范 HueSatMap 插值语义一致。
- **曝光语义**:`RawProfileExposureGl` 移植 ACR 的 `DcpDefaultBlackRender`/`dngBaselineExposure`(≥2.0EV 时关 black render ramp)。

### 1.2 DCP 数据源三层(优先级策略)

`EmbeddedDngProfilePolicy.resolve(hasEmbeddedProfile, colorEngine, hasDcpSelection)`:

| 条件 | 行为 |
|---|---|
| 引擎 = AdobeCurve 且 DNG 有嵌入 profile 且用户未选外部 DCP | 应用 DNG 嵌入 profile |
| 用户已选外部 .dcp | 用外部 DCP(**按镜头绑定**,prefs `raw_dcp_ids_by_lens`) |
| 都没有 | ACR3Curve 兜底(引擎自带,零资产) |

Camera2 `DngCreator` 写的 DNG 只嵌 ColorMatrix/ForwardMatrix(**无** ToneCurve/LookTable/HueSatMap)⇒ X300 上"嵌入 profile"只贡献矩阵,观感件永远来自外部 DCP 或 ACR3 兜底。

### 1.3 HDR:PGTM(Profile Gain Table Map)

- PC 实现了 Adobe 的 HDR 增益表机制:`DngProfileGainTableMap`(v2,80B 头 + 48×64×257 float 增益体 ≈3.16MB)+ 自算 `photonPgtmToneCurvePoints()`(257 点、单调、中灰 0.483)。
- PC 还把 Camera Raw Look(ProfileGainTableMap=100)写进 DNG XMP(`DngCameraRawProfileXmp`,UUID `F73E46FD…`),让 ACR/Lightroom 打开 PC 的 DNG 时恢复同款 HDR。
- **与我们"高光顶白"差距的关系(推断,未证)**:PC 的 HDR 观感 = 暗 SDR base(50.7 vs 我们 85.2)+ 大增益,HDR 输出几乎与我们相同 —— 这正是 PGTM"base 压暗、增益进表"语义的自然结果。**若要追 PC 的 HDR 观感,AdobeCurve+PGTM 是最可疑的机制,需单独取证。**

### 1.4 C++ 侧成本(PC 的做法)

vendored **Adobe DNG SDK**(`cpp/dcp/dng_sdk`)+ `dcp_parser.cpp` 解析 .dcp。DNG SDK 体量大,**我们不应 vendor**;替代 = 按 DNG 1.7 规范自写 TIFF tag 读取(tag 结构公开)。⚠ RawTherapee `dcp.cc`/dcamprof 均为 GPL,**只能对照行为不能抄码**(与 PC 的 GPL 同理)。

## 2. X300 的 DCP 可用性(生态事实)

- **vivo 无 Adobe 官方 DCP**:X300 不在 ACR 支持列表;社区反馈 DNG 进 Lightroom 色彩差(sRAW 甚至丢色),解法均为自建 DCP(ColorChecker)或先转标准 DNG。
- ⇒ **矩阵部分继续用我们的 `StdColorMatrix`(DNG 双光源标准链,已有)**;DCP 只取观感件(ToneCurve/LookTable/HueSatMap)。
- 挂其他相机的 DCP(如富士 Classic Chrome)时,色彩基准偏富士传感器 ⇒ 但作为**风格层**可接受(PC 的按镜头绑定正是这么用的),且与我们 M4 LUT 层(display 域、风格定位)职责清晰分开:DCP 管引擎域外观,LUT 管显示域风格。

## 3. sigmoid vs AdobeCurve+DCP 优缺点对照

| 维度 | sigmoid(现有引擎 1) | AdobeCurve + DCP(拟新增引擎 2) |
|---|---|---|
| 范式 | scene-referred 物理曲线 | display-referred Adobe 观感基准 |
| 工作空间 | Rec.2020(期 B) | ProPhoto |
| 结构 | 一条曲线(7 个形状常量,无资产) | HueSatMap + LookTable + ToneCurve(+ 可选 PGTM),数据驱动 |
| 色相保真 | Δh 4.43° / ΔE 3.69(红杯 ROI;filmic 1.50°/2.11)| 未实测;ACR 曲线 15+ 年调校,HueSatMap 本身就是色相/饱和校正件 |
| 高光/超白 | 曲线滚降到白点外仍有层次,与 gainmap [0.25,4] 天然对齐(实测可分辨跨度 2.0EV、线性域严格单调)| display-referred 语义,高光在曲线内压缩;HDR 需 PGTM 配套(PC 路线)⇒ 衔接成本高 |
| 暗部对比度 | 低(17.6% vs PC 25.9%;contrast 1.7/1.8 两轮回退,曲线端无解)| **同样不解决** —— 差距已定案在 demosaic 档(AMaZE 系 vs RCD) |
| 外观/风格维度 | 无(胶片感全靠后段 LUT) | **DCP 生态**:Adobe 官方各相机外观、富士胶片模拟、第三方 ⇒ 即插即用 |
| 对标 PC | 与 PC sigmoid 逐行同参数(差异已证不在曲线) | **PC 默认引擎就是 AdobeCurve** ⇒ 追平 PC 主流观感的必经之路 |
| 实现成本 | 已完成(阶段 2) | 期 A ≈ sigmoid 同量级;期 B ≈ 2~3 窗口(解析+采样+UI+对拍) |
| 依赖 | 无 | .dcp 文件导入管线;X300 无官方 DCP(矩阵自给) |

### sigmoid 已到天花板的证据(为何"再调 sigmoid"不是选项)

- ACES 2.0、Oklab L-only、contrast 1.7/1.8 四轮全部回退(10-19/10-22);
- 与 PC 逐位同参数仍差 6.5pp 局部对比度 ⇒ 曲线不是瓶颈;
- 暗部对比度低、色相偏移大是这条曲线的**范式属性**。

## 4. 建议

1. **做,分两期**:
   - **期 A:ACR3Curve 引擎(引擎 2,零资产)** —— 先把 display-referred 引擎在插槽架构里跑通(引擎契约 5 项照旧:ProPhoto 域、Curve 域 EV、失败严格回落引擎 0),用红杯 ROI 判据链(ΔE/Δh/可分辨跨度)与 sigmoid/filmic 同台量化。**验收判据必须含正向判据**(引擎真的改变画面,10-14 教训)。
   - **期 B:DCP 资产管线** —— DNG 规范自写解析 + HSV 域 sampler3D 采样(可复用 kFsOut LUT 采样骨架)+ 按镜头绑定 UI。**先拿富士机身 DNG 的嵌入 profile 提取一枚真实 DCP 做端到端样例**再铺 UI。
2. **优先级**:当前挂账两项(demosaic 档验证 → 暗部对比度;PC 同场景 0EV 对拍 → 渲染差距定性)比 AdobeCurve 更直接对症;AdobeCurve 期 A 可与之并行(互不依赖),期 B 在期 A 验收后。
3. **HDR 衔接单独立项**:若期 A 上线,SDR base 亮度策略(我们亮 base vs PC 暗 base)与 PGTM 的取舍需真机对拍后再定,不动现有 gainmap 链。

## 5. 待确认(实现前必须取证)

- [ ] ACR3Curve 的数学本体(PC `ACR3Curve.kt`,本快照已剥离,需从上游 hinnka 仓库或 PC 发行 APK 反编译取证);
- [ ] AdobeCurve 引擎 pass 输出的编码语义(输出是否已是 gamma 后信号,决定与公共后段 sat/LUT 显示域的接缝);
- [ ] PC 双 HueSatMap(线性 pass + 引擎 pass)的职责分工与去重;
- [ ] PGTM 与我们 gainmap [0.25,4] 的数学等价性(若走 Adobe HDR 路线)。

## 参考

- 本仓:`docs/research/05-camera-look-engines.md`(DCP 五组件、dcamprof)、`06-photoncamera-baseline-engine.md`(AdobeCurve 管线取证)、`13-学习型影调`(ACR3 inverse 用法)
- PC test(接口级一手):`EmbeddedDngProfilePolicyTest` / `DngSourceColorMetadataTest` / `DngProfileToneCurveTest` / `DngProfileGainTableMapTest` / `DngCameraRawProfileXmpTest` / `RawProfileExposureGlTest` / `DeviceConfigurationTest`(`raw_dcp_ids_by_lens`)
- 生态:[Adobe 支持相机列表](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/camera-raw-plug-supported-cameras.html)、[X300 Ultra sRAW 丢色(Adobe 社区)](https://community.adobe.com/questions-680/vivo-x300-ultra-sraw-1640463)、[r/Vivo DNG 进 LR 观感差](https://www.reddit.com/r/Vivo/comments/1f436o8/issue_with_rawdng_photos_imported_into_lightroom)、[自建 DCP 教程](https://robsheaphotography.com/2025/04/13/installing-profiles.html)
