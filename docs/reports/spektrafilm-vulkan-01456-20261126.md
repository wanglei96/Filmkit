# FilmKit 0.14.56：Vulkan 设置、四原片回归与全链优先级

2026-11-26，vivo V2509A / Mali-G1-Ultra MC12。0.14.56（853）已覆盖安装，签名、Manifest/DEX、Vulkan 与 HDR JNI 验证通过，手机 APK SHA-256 与本地一致。Release JVM 测试 852 项，850 通过、2 跳过。根目录保留 0.14.56 与回退版 0.14.55。未代操作快门。

## 设置与回退

「设置 → 影调与色彩」里启用 Spektrafilm 后可见「Vulkan 成片加速」。默认关。开关在后台检查硬件计算队列、生产规格缓冲区和实际计算管线；检测中及不支持设备均不可开启。偏好随快门冻结，通过成片 JNI 进入 Vulkan Hanatos，不改变取景设置。GPU 未完成时从原 GLES 纹理重新走整帧 CPU FP64，不用部分 GPU 结果继续。真机回放及实拍确认探测通过并处理全帧；未拿另一台不支持设备验 UI。

## 固定画质回归

| 原片（均在项目 test_temp/） | 场景 |
|---|---|
| FILMKIT_20261119-110832.dng | 人像／肤色 |
| FILMKIT_20261119-110846.dng | 室内绿植／叶片 |
| FILMKIT_20261119-110904.dng | 室外建筑／屋顶、天空 |
| FILMKIT_20261119-110936.dng | 鲜花／高饱和花瓣 |

文件名与 SHA-256 登记于 tools/prepare-spektrafilm-regression-dng.py 和项目记忆。灯泡只作高光专项。用 RawPy 解原始 Bayer mosaic（没有用 LibRaw 去马赛克），读 DNG 中性点与 LSC，沿用对应捕获日志的 CCM、黑白位，按原片方向重跑生产单 RAW RCD、Spektrafilm、HDR 增益图及 Q100 4:4:4 回放 JPEG。不声称重现历史 JPEG；无重复伪造帧，不能代表 MFNR。

四张 × Gold 200 / Portra Endura、Provia 100F 直扫 × 8 位、RGB10_A2，**16 组 CPU/Vulkan A/B**。热状态均 0→0。测试 ARM64 库与 APK 封装的库字节相同。原尺寸 RGB 最大差 **1 码**，受影响通道不超过 **0.0056%**。**16 组 HDR 增益图全都逐字节一致**。普通回放 JPEG 解码最大单通道差 6 码；最坏全图平均差 0.000334 码。肤色、叶片、建筑、天空及花瓣区域的原始像素最大差也是 1 码；8 位 A/B 裁块目测无明显偏色或细节变化。此回放未包含实际 App JPEG/R 封装或保存。

复现：构建 ./gradlew :app:assembleRelease -PfilmkitHdr=true；手机空闲时运行 bash tools/run-spektrafilm-vulkan-regression.sh test_temp/<新目录>；用 python3 tools/compare-spektrafilm-vulkan-regression.py test_temp/<新目录> --require-count 16 核对输出。依赖 RawPy、NumPy、Pillow、现有 LSC 工具的 moderngl。

## 耗时：局部稳定，整链不作夸大归因

四场景各一次。以下是 8 位跨场景中位数，不是重复整链测速。各组「读回＋Hanatos」省 39–103 ms；10 位 8 对中 7 对更快、一对慢约 1 ms。没有合成取景，不从此表推断取景。

| 8 位（ms） | CPU 读回＋Hanatos → Vulkan | CPU RAW 引擎 → Vulkan |
|---|---:|---:|
| Gold 200 / Endura | 189 → 123 | 2208 → 2132 |
| Provia 100F | 180 → 117 | 1917 → 1839 |

上轮同四帧 RAW 的 [交错回放](spektrafilm-vulkan-hanatos-20261126.md) 更适合评估热态：读回＋Hanatos 少 116–140 ms，但 Provia RAW 引擎只少 30–59 ms，且部分首张及合成预览样本变慢。实拍沿用历史关闭样本作体验参照，不要求重复拍摄；Vulkan 的净变化以同 RAW 回放为准。

### 0.14.56 手拍结果（11 月 26 日 15:00，UTC+08）

用户操作快门，两张均开 Vulkan。与历史关闭样本同为 3072×4080、四帧 MFNR、关颗粒、8 位 Q100 4:4:4 JPEG/R，均带 HDR 增益图。日志确认两张的 Hanatos 均处理了全部 12,533,760 像素，无 CPU 回退。下表单位 ms，关闭 → 开启。

| 指标 | Gold 200 / Portra Endura（0.14.54 → 0.14.56） | Provia 100F 直扫（0.14.55 → 0.14.56） |
|---|---:|---:|
| 快门到保存 | **6203 → 6818（+615）** | **5386 → 6326（+940）** |
| 快门到入保存队列 | 5443 → 6250 | 4851 → 5836 |
| 入保存队列到保存完成 | 760 → 568 | 535 → 490 |
| RAW 引擎 | 4738 → 5417 | 4261 → 5180 |
| 读回＋Hanatos | 351 → 417 | 411 → 262 |
| 胶片曝光／DIR | 615／826 → 1355／783 | 817／649 → 820／1469 |

历史关闭样本取 [11 月 25 日 0.14.54 的同配方 Endura](spektrafilm-raw-highlight-simd-20261125.md) 和 [11 月 26 日 08:45 的 0.14.55 Provia](spektrafilm-exposure-dir-jpegr-20261126.md)。0.14.55 的 Gold 实拍配的是 Vision 2383，未拿来替代 Endura；0.14.54 的 JPEG/R 编码路径也与本次不同。两张均没有观察到快门到保存提速，增加的时间在入队前，不在保存段。这不是同 RAW 或同版本 A/B，不把差值归给 Vulkan：Provia 的读回＋Hanatos 少约 149 ms，但仍走 CPU 的 DIR 多约 820 ms；Gold 首次 Vulkan 初始化约 166 ms，Provia 约 0.4 ms，shader 本身仅约 15／11 ms。

日志中的取景交付间隔峰值，Gold／Provia 在采集期分别为 551／310 ms，均早于 Vulkan 投影；排除采集首窗，保存前完成的统计窗口分别为 179／187 ms，均出现在 Vulkan 投影完成后。这些峰值不代替主观取景反馈，也不能用历史不同场景单拍归因。

本次原始日志 `test_temp/spektrafilm-vulkan-01456/01456-device-20261126.log` 第 2507–2594、2655–2733 行，SHA-256 `12d982b8d61d69198c54addc87e6c97ec1dd8af7c53d87a388e615be101cee8f`。

## 全链：按数据驻留安排下一项，而非替换 API 即算收益

| 阶段 / 数据位置 | 下一步与限制 |
|---|---|
| 采集、校准与齐帧：Camera HAL / CPU | 快门等待不是 GPU 算力。可独立考虑预备缓冲或早到帧交接，不减少四帧或推测实际 EV 和标定。 |
| RAW 对齐、高光引导、融合、RCD、CCM：CPU 与 GLES | 已有 GPU 部分不机械移植。最值得查 CPU 对齐与 GPU 金字塔的往返；比较完整整数匹配、置信流程、同搜索／边界／tie-break／拒绝行为，并用真实四帧回放。 |
| GLES → Vulkan → FP64：成片交界 | 本版已省 GPU 前 FP64 展开。设备报告 AHardwareBuffer 导入、foreign queue 与 sync-fd 扩展。**优先测 RGBA16F 共享纹理，再合并相邻计算。** 扩展存在不等于此格式零拷贝可用或更快；需核对原码值、所有权及同步。RGBA32F 原路径保留。 |
| 胶片曝光＋DIR：CPU FP64 | 仍是最大光谱开销；旧版 Provia 手拍同张约 817＋649 ms。实测下述 Vulkan IIR，单独搬滤波没有收益，**未合入**。若再试，应让光晕、散射、DIR 混合与多个 tap 驻留同一套工作平面，不每 tap 回读。 |
| 颗粒、印相、光谱扫描：CPU FP64 | 保留颗粒随机流和原曲线。可试印相光谱投影＋纸显影、扫描投影连续驻留；全尺寸 CPU 约 134＋46 ms 和 133–142 ms，不为 GPU 只计算 LUT 内域。 |
| CAM16、扫描滤波、RGBA、HDR：CPU → GLES | **另一个优先实验：扫描颜色／OETF／量化与 HDR 基底合段**，省 CPU RGBA 全图上传。8 位扫描输出约 129–139 ms、打包 38–42 ms、HDR 21–23 ms。扣掉 FP64 输入上传、给 JPEG 的读回；验证 10→8 舍入和 HDR 增益图。 |
| JPEG/R、EXIF、媒体库：CPU / 系统 | 0.14.55 已有并行底图，固定输入热态约 99–104 ms。优先减少交接复制；本轮不改 MediaStore。 |

### 曝光／DIR Vulkan IIR 实测

建筑原片的全尺寸 Bayer 强度平面，四个代表性递归半径（σ 7.58、12.50、35.52、64.53），完整横／竖正反扫。FP64 对照直接调用生产 gaussianBlur（8 worker）。只测共用 IIR，不代表完整曝光或 DIR。

| 单平面、单 tap，三次中位数范围 | 用时 |
|---|---:|
| CPU FP64 | 15–17 ms |
| Vulkan 直接行列递归，含双向转换／同步 | 118–122 ms |
| Vulkan 32×32 共享内存转置，提交到 fence＋映射失效 | 28–31 ms |
| Vulkan 转置，含上传／FP64 回写 | 38–41 ms |

不砍 tap、不截递归。直接用 FP32 三阶系数在 σ 64.53 的归一强度最大误差 0.01438。按同一传递函数拆为一实极点与共轭对、用增量状态实现后，最大误差降为 4.45×10⁻⁷。但最快原型的 GPU 段本身仍比现有 CPU 慢，不接成片。

证据：test_temp/spektrafilm-vulkan-01456/ 的 quality.json、quality/（日志、原片与传输哈希、审计及压缩输出）、roi-ab-review.jpg、iir-probe/、verify-apk.log、installed-apk.sha256。两版 IIR 探针的起止热状态均为 0。
