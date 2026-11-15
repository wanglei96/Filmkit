# 0.14.5：剪裁观测与高光融合修复

## 验收口径

本轮不因没有晴天而停止实现。生产消费链已接通并用保存的四帧 RAW 重放；日光实拍外观仍待后续验证，不能用合成测试替代。不是重新开启 defringe，也没有恢复跨 CFA 标量 inpaint。

**重要区分**：

- 已修复并测试的是融合观察有效性、配准准入、原始相位合并和后继消费契约。
- 所有帧都剪裁时不可能凭空恢复纹理；规范回退会失去部分 headroom，不保证一张图所有高光均能救回。
- 目前没有证明“全链在任意场景都无品红/灰斑/重影”；仍需不同场景回归。
- CameraWhite 是参考 RGB 输出白界政策，不是物理满阱检测；它会约束某些传感器未剪裁、但超过源中性白界的颜色。不能声称所有真实颜色逐位不变。

## 参考与明确的适配

完整参考合同见 `bento-merge-reference-contract.md`。主要读取 PC/Bento 的 guide、native integer clipping、forward/backward support、BentoRegionAdmission、adjusted/inpainting masks、整帧接受判定、MergeBayer dual kernel、source CameraWhite RGB resolve。

保留了完整度量，不用一个亮度 scalar 替代 RGB guide/误差，不使用跨 R/G/B 相位的平均回填。

与 PC 当前源码的差异明确记录：

1. 配准复用本项目完整 HDR+ SAD solver，并增加八 quad（16 native pixels）细网格与真实双向求解；**不是 PC LK solver**。FB+RGB 可观测支持、collar transfer 与 ghost-hole 整帧准入均保留。
2. 现有普通 temporal flow 保留其粗网格双线性方式，selected short 使用细网格、默认 tile-nearest。
3. PC 当前 RAW/2 mask 直接给 RAW/4 `texelFetch` 的核形域错位，改为先正确生成 RAW/4 selected slice，再做其 3×3 equality 检查。
4. 全部输出保持 reference radiance，等价取消 short-domain `1/ratio` 与下游 `baselineExposure=log2(ratio)` 配对单位转换，避免重复曝光。guide/admission 仍保留所需的两个曝光域。
5. source CameraWhite 白界在**去马赛克之后的完整 RGB 域**执行，undo LSC→白界→redo LSC，再进 CA/CCM。没有在 Bayer 相位内非线性截顶。
6. 噪声：融合提供实际固定权重 marginal variance，后继 RGB covariance 为明确 RAW quad model，经 LSC² 和完整 CCM 矩阵传播；多尺度滤波使用全六分量的一阶/冻结权重保守界。不是精确的非线性 RCD Jacobian，也不是未知空间相关的精确谱估计。

## 修复内容

### 1. 残差采样网格与观测有效性

`kFsMfnrMismatch` 的三个 offset 读取均用真实 `uOffTiles`，不把 4×细的 mismatch 网格当位移纹理尺寸。

原生 uint16 sensor codes 在扣黑/曝光归一前判断 clipping，采用逐 phase 黑电平与白电平。亚像素采样仅当实际非零权重 tap 均在图内且未剪裁时才有效。每个 cell 采全四 CFA 相位，而非原先 stride4 只采一个绿色格点。

每个 alternate 独立累计有效配对的 residual 和 noise variance；有效配对不足或缺某相位时为 UNKNOWN，普通 temporal 保守退出。UNKNOWN 不等于 motion，也不等于静止；CPU 层不允许 ISO residual fade 或中值滤波重新给它正权重。selected short 的高光恢复独立消费准入 M，不由该 UNKNOWN 标量决定。

### 2. 高光 guide 与几何准入

- 四相位原生 clipping footprint；WB、曝光缩放不能让剪裁 tap 变成有效观测。
- RAW/2 完整 RGB guide，3×3 RGB moments、18 green observations、binomial filter、noise LUT 分支和 clipping sentinel；binary16/U16/R8 的参考量化保留。
- 前后向分别真实求解，mapped reverse tile 与完整向量 cycle。
- 4×4 空间 RGB agreement、非剪裁观测、FB error 和 support 连续权重。
- 真实 highlight tiles 的 8 连通区；radius4 唯一 collar、至少4 donors；confidence/距离²插值；不足时 global confidence-weighted per-axis median；没有可观测支持则拒绝。
- 高光分支强制完整 L1，不受普通配准 A/B 开关跳过读回影响。

### 3. 整帧接受与融合

完整 RGB 单侧误差生成 adjusted M 与 inpainting mask。short 自身 clipping 是诊断，不把它的高光核再次清零。最大 8 连通 hole 达 80 guide pixels 时拒绝整帧，不用临时调阈值强行放行。

M 经正确 RAW/4 互补量化后：reference/ordinary temporal 乘 `(1-M)`，accepted selected short 由 M 接管，并使用单位 selected weight。拒绝时 selected global weight=0，不能从普通 temporal 途径偷渡回来。

GPU MergeBayer 保留 roundEven、mirror、原始 phase、两个绿色格点的支持、Gaussian/linear dual kernel、未归一化各帧 intensity/support 累加后统一相除，以及按源噪声/曝光调参的全局权与 kernel sigma。

### 4. 后继闭环

- legacy P0 Ts 不能重新混入未曝光归一或已拒绝的帧；新 stack 走空间 P0。
- 旧 RAW opposed 不再次重建已有准入观测；defringe 仍下线。
- 可选旧 NN 仅适用于单曝光训练域，不能截断恢复的 >1 RAW；新链明确记录回退到实测方差 P0，不假称 NN 已消费新的局部噪声分布。
- 快速 bilinear 旧渲染只读参考 RAW，无法消费融合；HDR 时明确路由到共享 mosaic/RCD 并记录日志，不静默丢弃融合。
- source white RGB resolve 位于去马赛克后、CA 前；协方差经源几何 LSC 与完整 CCM 后提供给空间降噪。
- P0 消费逐样本实测 V；关闭降噪仍保持零邻域直通。
- PC3D/P4 的配对范围比较消费完整协方差，不用一条中心 scalar 替代 RGB 色度度量。
- gaussdown、第一趟 PC3D、guided upsample、第二趟 PC3D 逐阶段传递完整六分量协方差；对未知相关使用明确保守矩阵界。
- 小波 Cb/Cr σ 各自投影完整协方差，不假定上游共源样本为独立白噪声。
- 单帧/尺寸变化清理和资源释放已接；新链失败清空输出并返回失败，不能复用上一张像素伪成功。
- 新增及原颜色矩阵统一显式转列主序，GLES `transpose=FALSE`，避免非法 `GL_TRUE` 依赖。

## 已有 RAW 重放

输入均使用实机 GRBG（LUT=97），保存的生产 GPU RGBA8 pyramids，生产 C++ guide/geometry/admission，生产 GLSL mismatch/merge/P0/RCD/RGB resolve/CCM。

| 项目 | 16:39 matched | 17:07 最后一次样片 |
|---|---:|---:|
| 尺寸 | 4096×3072 | 4080×3072 |
| 可观测 exterior tiles | 7755 | 14843 |
| highlight regions | 49 | 19 |
| 最大 inpainting hole | 285，拒绝 | 24，接受 |
| selected short index | -1 | 3 |
| RAW/4 M 非零像素 | 0 | 78846 |
| 参考剪裁 G 恢复到 >1.02 的占比 | 0（规范拒绝） | 约97.4% |

拒绝组不得通过放宽80阈值伪造恢复。接受组确实从短帧得到更高的 reference-domain G，未被普通 mismatch 清零。

去马赛克/白界/CCM 对照，高亮品红统计定义为 `Y>.8 && min(R-G,B-G)>.1`：

| 数据 | 白界解析前像素 | 解析后像素 |
|---|---:|---:|
| matched（拒绝组） | 2120896 | 854 |
| evening（接受组） | 493094 | 23546 |

这证明相关输出政策能抑制剪裁色比异常，**不证明余下像素都是伪影，也不证明最终手机显示无伪影**。该色彩重放使用单位 LSC（归档未含实机完整 shading grid）、P0 clipping proxy；未声称 PC3D/film/LUT/相册 HDR 显示逐位重放。

结果保存在 `test_temp/highlight-replay-matched/` 与 `test_temp/highlight-replay-evening/` 的 metadata、merge-report、color-report 和原始输出中。

## 回归与设备验证

- C++ 几何测试：301 checks，包含双向真对齐、FB/RGB support、collar/global/no-support。
- C++ guide/mask/准入：4635 assertions，含 79/80边界、四CFA、native clip、量化与mask域。
- 生产 temporal shader：21 GPU cases；红/蓝独立运动可检测，clipping/越界为 UNKNOWN。
- 原生 Bayer merge：59 GPU/NumPy cases，含 MRT V/A/B/provenance、mirror 重复 tap 相关、零噪声、拒绝短帧隔离。
- RAW covariance→RGB/CCM：32实际GPU组合，含四CFA/旋转/不同输出尺寸与负交叉项。
- 多尺度噪声 filter：10 GPU cases，完整矩阵及有限差分 Jacobian 对照。
- RGB resolve：317520独立CPU比较；另有真实 Mali draw/readback 检查。
- 手机上原生 EGL/ES3 pbuffer：Mali-G1-Ultra MC12，最终58 checks/0 failures，16 draws、29浮点readbacks；融合 MRT、RGB resolve、noise 六分量，以及真实生产PC3D三MRT实际执行，常量图误差0、协方差对CPU最大误差1.16e-9；12份生产shader真实compile/link。证据 `test_temp/highlight-device-pc3d-draw-gpu.json`，compile-only与数值draw分开计数。
- 后继生产消费链：desktop GPU 106 checks/108 draws通过，包含scale2 down→first PC3D MRT→guided up→second PC3D MRT、P4/D1 full covariance响应及P0关闭直通。证据 `test_temp/highlight-postprocess-gpu.json`。
- 全部 shader 静态校验及 arm64-v8a/x86_64 Release 构建。
- 应用单测832个：1个既有 `Pv3StaleFrameTest` 归属校验断言失败、2个跳过。该失败此前已在改动前基线复现，与本轮成像修改无关；不能称单测全绿。

## 尚待验证与风险

1. 完整 App 摄像头→HAL采集→成片落盘的日光效果、HDR相册显示仍待实拍，不以EGL数值测试代替。
2. SAD与PC LK的不同、保守协方差上界可能影响细节/速度；不能只凭公式声称性能或质量完全等同PC。
3. 新RGB白界是有意采用的参考输出政策；高饱和彩灯、花瓣和极亮彩色物体需回归，不能把去色风险隐藏。
4. 短帧大孔洞规范拒绝时不恢复真实高光纹理；禁止悄悄重新启用旧 scalar inpaint。
5. 新全精度噪声图增加显存与计算量，需记录真实 App处理时间/峰值内存，不能在未测前替用户删质量管线。

日光验收项：极高光品红、云朵灰斑、粉色花瓣灰斑/绿边、栏杆紫边、灯边灰带、移动物体重影、跨镜头与预设、降噪开关直通。
