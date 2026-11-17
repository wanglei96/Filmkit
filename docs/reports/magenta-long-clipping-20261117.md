# 日光栏杆品红：排查、复现与 0.14.26 修复包

日期：2026-11-17，Asia/Shanghai。排查基线：`debf3042e21f790e14f63aa22a22046e03226e9e`，生产代码版本 0.14.25(822)。

以下第 1--6 节保留修复前的排查记录；用户随后要求先出包、有条件时复测，第 7 节记录 0.14.26(823) 的实现与交付状态。

## 1. 结论与边界

- **已确认的代码缺口**：当前高光 MergeBayer 路径没有接入参考实现对 `SHADOW_LONG` 的原生裁切排除。普通 temporal mismatch 会跳过已裁切样本，但不会因此排除同一 cell 内这些位置的长帧融合贡献。
- **已完成的受控复现**：当前实际 mismatch 和 MergeBayer shader 在 Apple M1 桌面 GL 上，将长帧已裁切、参考/短帧未裁切的中性反光条融合成明显绿色不足的 CFA 信号。独立接入完整原版 source clipping mask 与 weight rewrite shader 后，该合成条件下恢复中性。
- **实拍关联仍是待验证假设**：两张失败拍摄均有长帧裁切、均进入该融合路径，条件吻合；但没有对应 RAW burst 或阶段 dump，不能证明这就是两张照片的唯一原因，也不能把合成复现称作实拍修复。
- 本轮没有修改生产 shader、没有切换降噪设置、没有构建或安装新包。失败照片和日志已保存并核验；用户可断开手机，无需为本轮排查等待同样阳光。

## 2. 证据归档

目录：`test_temp/magenta-20261117/`，受 `.gitignore` 忽略。两张 JPEG 与当日日志拉取时均与手机端 SHA256 对照一致，本次收线再次核对本地 hash：

| 文件 | SHA256 |
|---|---|
| `FILMKIT_20261117-153311_理光正片标定.jpg` | `9907d716c8dd00eb98bcf3086b6a1b8979c08ef174b37850660f1fee6cbbdf62` |
| `FILMKIT_20261117-153340_理光正片标定.jpg` | `3b57e45982a7bbc13db69652b0a6392ce7ff71821145e5158ef6a3c087265340` |
| `logs/filmkit-engine-20261117.log` | `5611bb078db1d90c8ab2c0fc99428a6cbfdc8a17d2db92d93f36ce77eb52c73b` |

归档还包含两张 `-overview.jpg`、完整拉取的日志目录、`long-clipping-repro.json` 与 `temporal-cpu-oracle.json`。历史 edge dumps 不是这两次拍摄的输入，不得混作同 RAW 回放。

原 JPEG 中栏杆亮面与部分植物亮边可见粉紫带，不只是用户截图中的显示问题。主摄与长焦均出现，不能归因于单个镜头设置。是否还叠加光学色差、去马赛克或其他处理影响，尚未排除。

### 拍摄对应关系

下表以 `mfnr.burst_actual` / `raw.meta` 为采集真值；`photo.saved.sec` 不是本次 bracket 的逐帧曝光，不用于反推倍率。

| 项目 | 15:33:11 保存的照片 | 15:33:40 文件名的照片 |
|---|---|---|
| capture.begin | 15:33:07.473 | 15:33:37.819 |
| 镜头 | camera 3，长焦 | camera 2，主摄 |
| JPEG 尺寸 | 3072x4096 | 3025x4018 |
| 四帧秒数 | .0024, .0024, .0100, .0008 | .0029, .0029, .0100, .0010 |
| 四帧 ISO | 72, 72, 79, 72 | 50, 50, 66, 50 |
| 四帧实际 EV | 0, 0, +2.192, -1.500 | 0, 0, +2.199, -1.500 |
| bracket.actual.sat | 0, 0, .1946, 0 | 0, 0, .1690, 0 |
| CFA / 黑白阶 | GRBG / 64 / 1023 | GRBG / 64 / 1023 |
| neutral | .4937, 1, .5447 | .5095, 1, .6514 |
| highlight.admission | accepted=true，clippedPixels=215 | accepted=true，clippedPixels=144 |
| highlight.merge | native_bayer，shortIndex=3 | native_bayer，shortIndex=3 |
| highlight.resolve | hasShort=true | hasShort=true |
| PC3D / defringe / cgK | 0 / 0 / 0 | 0 / 0 / 0 |

日志入口：`bracket.actual` 位于第 20310、20457 行，`raw.render` 第 20348、20503 行；admission 第 20318、20465 行，merge 第 20326、20471 行，resolve 第 20329、20474 行。

`sat` 是整帧统计，不提供品红位置是否裁切的空间证据。尤其不能把 19.46% / 16.90% 说成栏杆区域的裁切比例。`raw.render.hlOk/hr/hlPre=0` 属旧统计，不能据此否认上表已确认执行的新融合/resolve 链。

## 3. 源码路径与缺口

### 生产代码

1. `core/engine/src/render/FrameRenderer.cpp` 的 `kFsMfnrMismatch`：`nativeValid` / `alignedValid` 在原生码域检查裁切，`comparePair` 跳过不可比样本；`observedMismatch` 在 cell 仍有四相位有效比较时可以输出很低的 residual。它解决的是运动比较可观测性，不等于逐位置长帧裁切保护。
2. CPU mismatch 后处理会 dilation、median 并转换 temporal weight；UNKNOWN 被拒绝，但 cell 内部分裁切、部分有效时，仍可保留高置信度。
3. `core/engine/src/render/HighlightMergeShader.h` 对非所选短帧使用 `(1-M)*temporalWeight*globalWeight`，没有长帧自身裁切 exclusion。曝光归一后，长帧饱和平台可能低于参考域 ceiling，并以高权重进入融合。
4. `HighlightResolveShader.h` 的 CameraWhite 在去马赛克后的 RGB 域执行，不能假定它能补回已经混入的逐通道饱和失真。不能通过在 Bayer 域提前套 CameraWhite 来代替缺失的长帧保护。

现有注释中“clipping is diagnostic, never a second local veto”不能当作省略 long guard 的依据：所选、已接受短帧的 admission 合同，与普通长曝光帧自身裁切 exclusion 是两件事。

### 固定参考

参考仓库 `https://github.com/bjzhou/PhotonCamera.git`，提交 `f3449a1a82b9f3ce0d757c5ea49002e33d123fca`，本次本地检出 `/private/tmp/PhotonCamera`。

- `GlesMgcRawSpatialStacker.kt:1647` 的 `SHADOW_LONG` 分支同时消费 `referenceHighlightMask` 与对齐后的 source RAW clipping mask，逐张调用 weight rewrite 后再融合。
- 同文件 `renderAlignedRawClippingMask`（约 5206 行）生成 R8 掩码，按 native CFA phase 映射黑阶；阈值为 `black + (white-black)*250/255`，不是 WB 后或曝光归一后的值。
- `GlesMgcRawSpatialShaders.kt:1525` 的 `alignedRawClippingMask` 完整处理 RAW/4 中心、Bayer quad 坐标、converted flow、`roundEven`、3x3 quads 邻域及所有四相位、整数原生码比较与边界 clamp。
- 同文件 `bentoRewriteWeight`（约 1186 行）执行 `existingWeight*(1-maskWeight)`，在相应输出域经过 R8 量化后供融合采样。

这里确认的是该固定版本参考实现的合同，不把它泛称为所有 HDR 算法的唯一标准。

## 4. 受控 GPU 复现

新增诊断工具 `tools/repro-highlight-long-clipping.py`，复用现有 offline GL、temporal 检查器与 MergeBayer GPU/独立 CPU oracle，不修改生产代码。

```bash
python3 tools/repro-highlight-long-clipping.py \
  --reference /private/tmp/PhotonCamera \
  --out test_temp/magenta-20261117/long-clipping-repro.json
```

依赖现有 Python numpy、moderngl、GLFW 环境和上述固定参考检出。临时目录不是永久资产；若检出消失，须从上述仓库恢复该提交，不要换成漂移的上游 HEAD。macOS 本次需在获准访问图形会话的环境运行，沙箱内 GLFW 初始化未完成；成功运行已正常退出。

条件：128x128、GRBG、静态零位移、中性 WB 信号 .08 背景 / .70 的 16px 反光条，使用首张日志的 WB、长短曝光倍率及 ISO 比；参考/短帧未裁切、长帧条带裁切。参考未裁切，故此实验 `M=0`，不声称覆盖完整 admission/reference-highlight 路径。

- 实际 mismatch 量化码：alt1=0，long=1，short=2..3，全部处于生产全接受范围；零运动场景下 dilation/median 不会把这些低值变成拒绝，因此此合成条件下 temporal weight 全 255。
- long 全局权重按当前公式计算为 17.3431；这是合成输入的权重，不是两张实拍的逐位置权重测量。
- 当前 GPU merge 与独立 CPU oracle 最大绝对误差断言 `<.001` 通过。

下面是条带内部的 **WB 后 CFA 通道均值，不是去马赛克 RGB 或最终 JPEG**：

| 条件 | R | G | B | min(R-G, B-G) |
|---|---:|---:|---:|---:|
| 当前生产 shader | .46973 | .26807 | .43262 | .16455 |
| 独立原版 source clipping guard 实验 | .69873 | .69922 | .70020 | -.00049 |
| long 贡献置零的因果控制 | .69873 | .69922 | .70020 | -.00049 |
| 四帧同曝光控制 | .69873 | .69922 | .70020 | -.00049 |

long 贡献置零只用于隔离原因，**不是修复方案**；不能把全帧弃长曝光作为交付。原版 guard 组使用完整原版 mask / rewrite shader，但仅在独立测试纹理上运行，尚未接入生产。

输出状态 `SYNTHETIC_LONG_CLIPPING_LEAK_REPRODUCED`，renderer `Apple M1`。生产 mismatch hash 为 `04c17a7cda5f2645c9a7a42738fb8f90495248364e0f9fe5b732b4422d4cfef2`，merge hash 为 `fa1b777f76416c4092ff4a34b2df6cc6b107427910576249ae713f916c80fb2b`；原版 mask hash、输入和其余结果见 JSON。

限制：没有运行 P0/RCD/resolve/CCM/P4/D1/film/LUT/JPEG；共享 mismatch helper 先经过 RGBA16F 再显式量化 RGBA8，不是 Mali 逐位 parity；没有真实运动、饱和参考、跨镜头生命周期或完整尺寸验证。

另运行 `tools/check-highlight-temporal.py --cpu-only`，21/21 通过，输出 `temporal-cpu-oracle.json`，状态 `CPU_ONLY`。这不是生产 GPU 全套回归，更不是设备质量验收。

## 5. PC3D 与阳光条件怎么判断

阳光照到细窄亮面后更易出现局部高信号，与本次候选机制相容；光线消失后不复现，不构成该缺口不存在的证据。但还没有实拍空间 RAW 证据证明栏杆恰好跨过了长帧裁切边界。

PC3D 位于融合之后，切换它可能改变色斑外观或可见程度；当前没有同 RAW、同其他参数的开关 A/B，不能说它一定会消除，更不能用它替代融合输入的正确性修复。也不擅自修改用户的降噪选择。

## 6. 具体修复与验收要求（尚未实施）

1. 先完整接通参考 `SHADOW_LONG` 双 exclusion 合同：核对现有 reference-highlight 掩码是否完全等价，再生成对齐 source 原生四相位裁切掩码。保留 RAW/4、原版采样坐标/邻域/阈值/边界/R8 量化，不能改成逐通道 satW 或全帧关闭 long。
2. 当前 `uW` 是 32 RAW px 的 cell 网格，不能把 RAW/4 mask 粗暴缩入粗网格。按参考在正确域重写权重，并完整消费于融合支持、归一化及噪声传播；不得只改像素权重而保留错误的 noise covariance/support。
3. 不将 long guard 套到已通过独立 admission 的所选短帧，不破坏现有相位保真 kernel、短帧拒绝回退与 CameraWhite RGB 域合同。
4. 补固定输入 oracle：四种 CFA、不同 phase 黑阶、10/16-bit 临界阈值、非零与小数 flow、四边/非整 tile 尺寸、单相位裁切、reference-highlight 重叠、短帧接受/拒绝；先比较原版掩码/权重，再比较完整融合像素及噪声输出。
5. 完整同 RAW 管线比较：品红、细节、噪声、亮度、其他高光/运动重影，以及 PC3D 开/关独立 A/B；覆盖全尺寸、主摄/长焦/其他镜头和预设。两张失败 JPEG 不能逆推为 RAW，不伪造这项验收。
6. 桌面通过后仍需真机完整尺寸、跨镜头/引擎/前后台生命周期和实际日光高反差场景验收；性能变化实测记录，不为速度删质量步骤。可在下次方便且获准时采集可回放 burst，不强求复现同一束阳光，也不自动拍摄或改变保存偏好。

本轮收线在“代码缺口 + 合成 GPU 机制复现 + 具体修复要求”，不是“实拍根因唯一确定”或“修复交付”。Spektrafilm 方案已独立整理，前端品红问题修复前不将这些失败输入用于厂商风格标定。

## 7. 0.14.26(823) 修复包

用户批准先交付修复包，真实日光场景留待有条件时复测。没有重新连接手机、自动拍摄或更改设备设置。

### 已接通的生产链

- 新增 `HighlightLongShader.h`：完整 3x3 Bayer quads / 四相位原生整数裁切判断，`ceil(black+(white-black)*250/255)`，RAW/4 中心、ties-even 对齐锚点、clamp 边界。对齐输入沿用既有 coarse bilinear mosaic-pixel flow，而不是声称替换成 PC 的整套对齐器。
- 复用本张 `guides.highlight` 原始 RAW/2 参考高光掩码，不拿 adjusted selected-short M 代替。以 NEAREST 上传，先将 temporal confidence 乘参考掩码补集并量化，再乘 source clipping 补集并再次量化。
- RGB 三通道分别承载 frame 1/2/3 的独立 R8 精度切片，使用两张实际 RGBA8 权重中间资源；不是缩进原 32px confidence cell，也不是单个曝光权重标量。
- 长帧按实际 `frame_scale[i] < frame_scale[0]` 识别，排除已接受短帧角色，不硬编码帧 2。最终 RAW/4 权重由 MergeBayer 在每个 RAW 像素中心 LINEAR 采样；长帧不再重复乘 adjusted M，其他帧保持原合同。
- 相同 `frameWeight` 同时进入融合支持、总归一化、MRT 方差及短帧支持份额。没有只改颜色而遗留旧 noise/support。
- 新资源覆盖预热、尺寸重建、每张重写和释放；数据写入暂时关闭 dither 并恢复，merge 使用 12 个纹理单元。新增 `highlight.long_guard` / `longGuardFrames` 供之后日志复核。
- 不修改 PC3D、defringe、曝光规划、LUT/预设、降噪强度、核/样本/帧数或精度。

### 已完成的验证

| 检查 | 结果与范围 |
|---|---|
| Release 构建 | `:app:assembleRelease -PfilmkitHdr=true` 通过；保留既有 unused `cw` 编译警告，未夹带无关清理 |
| 全部 GLSL 静态校验 | `tools/validate-shaders.sh` 通过，含新增三个保护 shader；不是设备驱动编译结论 |
| 原版/独立 CPU 裁切对照 | `check-highlight-long-guard.py` 20 个 fixture 通过；原版 clipping mask 与 CPU stencil 均逐字节一致，覆盖 native phase、10/16-bit 临界、四 CFA、非整 tile/RAW4、flow/边界和 no-long 控制 |
| 权重与融合噪声 | 两次实际 UNORM 存储后，第二次源裁切保留/清零逐字节精确；80 组 short 模式的融合/MRT noise 对照通过 |
| 既有 MergeBayer 回归 | 75 组 GPU/NumPy 与额外 mask/tuning/dual-green/admission/noise 语义检查通过 |
| 原始品红机制回归 | `long-clipping-fixed-0.14.26.json`：保护旁路仍为 (.46973,.26807,.43262)，实际生产保护后为 (.69873,.69922,.70020)，与独立原版 guard 实验一致；数值仍是 WB 后 CFA 均值，不是最终 RGB/JPEG |
| CPU 参考高光合同 | C++ `check-highlight-recovery` 4635 个断言通过；RGB resolve 317520 项 CPU/合同检查通过，resolve GPU 静态编译由上列全量 GLSL 检查覆盖 |
| Release 单元测试 | 82 suites，841 项总数，839 通过、2 跳过，0 failures/errors |
| Android 合同 harness | NDK arm64 编译通过，更新新 sampler 的合法 dummy 与新增 shader 编译项；本轮没有运行设备 harness |

测试中发现并独立处理了两类 oracle 精度差异，没有放宽融合/噪声断言：UNORM 采样先单独对理想双线性结果约束在 1/255 内，测得最大 .0022176，再将测得系数用于独立 kernel/noise oracle；tile-anchor 加法按生产 FP32 后再判断严格 1.5px tap 边界，避免 float64 留下生产中已被舍入的微小余量。首遍权重 CPU 比较最大 1 个 UNORM code，第二遍 binary source mask 的保留/清零仍要求逐字节一致。

详细 JSON 位于 `test_temp/magenta-20261117/{long-guard-0.14.26,merge-0.14.26,long-clipping-fixed-0.14.26}.json`。没有声称完成这两张实拍的同 RAW 全管线重放、Mali parity、跨镜头生命周期或真机性能测量。

### 交付与验包

- APK：`filmkit-0.14.26-HLGUARD.apk`，57,584,596 bytes。
- SHA256：`12f64ceac816f6752bf6006d6304d1ff3b1eb99ce3b5bc0ce619b3dc348c6f0d`。
- aapt：`com.filmkit.app` / versionCode `823` / versionName `0.14.26` / arm64-v8a；DEX strings 含 `0.14.26`。
- v2/v3 签名验证通过，v4 关闭；签名沿用 Android Debug 证书，SHA256 `0d02d86c8cfd07a30d374ad4b0e10f55b0387018bccbd7c374157d7fa5f23349`。
- 包内 `libfilmengine.so` SHA256 `cc4f55ad9421bf5defb68ddeaf92477f5974a87907f4d8e32dc23613338e97e6`，与 Gradle stripped release 产物相同；`nativeEngineSubmitRawBurst`、`nativeStillWarmup`、`nativeEncodeUltraHdr` 等 JNI 导出存在，并含新 guard shader 与日志标识。
- 根目录保留本包和上版 `filmkit-0.14.25-LENS.apk` 用于回退，清理过期 0.14.20 包；不删除照片、日志或 RAW 证据。

**仍待用户复测**：真机安装/启动/出片、主摄与长焦受光细栏杆/植物亮边、光线变化及运动边缘、高光色相/噪声/细节和耗时。先保持原有 PC3D/预设设置检查修复效果，再单独做 PC3D A/B，避免把光线和设置变化混在一起。此包属于待实拍复测修复包，不将其标成日光问题已验收。
