# Filmic V7 / Spektrafilm 首阶段执行记录

日期：2026-11-18。范围：D0 基线、D1-A 官方 V7 桌面参考、D1-B / G0–G1 上游 Spektrafilm 与证据准备。未进入四路排名、手机移植、配方变更或发布。

## D0：单组固定 EV 真机基线通过，完整生产回归待验

- `test_temp/engine-reference-20261118/d0-inventory.json` 记录当前 HEAD、已修改文件的二进制 diff hash、未追踪文件 hash 和输入/资产清单。`d0-worktree.patch` 保留所盘点时点的已跟踪 diff；这是库存快照，不是已验收构建。
- 对 2026-11-17 灯罩历史样本重新核验 SHA-256：13 个文件，包括真实四帧 RAW、帧元数据、DNG、`prepared-hdr` 与 SDR 诊断产物；另外登记 48 个本地预设或 LUT 资产。历史 `prepared-hdr` 为 3072×4080、底行优先 RGBA16F，只能作历史证据；未验证它等于当前固定 EV 的真实 `base_engine_input`。
- 历史采集缺私有生效配方、原始 LUT 接线及完整预览反馈快照。不能用下述独立渲染器回放声称复现历史 JPEG，或得出旧 L/G 零回归结论。

### 2026-11-18 真机回放

- 设备 `V2509A`（Mali-G1-Ultra），已安装 `0.14.27 / 824`。独立 runner 使用现有四帧与标定，设备上的输入、runner、原 APK 提取库均与本地 SHA-256 一致。未调用 App 私有数据、覆盖安装或变更设置。
- 三轮均使用 `--raw-directory <raw> --ae-hal --pc3d 0 --pc3d-msize 0 --chroma-gate 0 --color-smooth 1 --color-median 0 --rotation 1 --edge-dump --output <out>`。实际为原生 Bayer HDR（短帧索引 3）、DFT、RCD、CS 生效、CM / PC3D 关闭、多尺度色度降噪和 TEQ 生效。使用默认 native FilmParams（sigmoid），无 LUT 注入，实际 tone 前 EV = 0、增益 = 1；无预览历史，`dispN=0`，只有一次被采纳的 tone draw。
- 诊断库仅在 RAW 静拍选中 edge dump 且输出目录存在 `base_engine_input.enable` 时，以正常 draw 的输入和 uniforms 复用同一个准备分支，写 `GL_RGBA32F`，不依赖 HDR 曲线探针成功。上游采样附件仍为 `GL_RGBA16F`（34842）；并未扩大上游精度。3072×4080，底行优先 RGBA32F LE，场景线性 Rec.2020/D65，alpha 恒为 1；所有像素有限，RGB 范围 0 至 3.173828125。FP32 含 30,800,946 个不能由 FP16 精确表示的通道值，舍入为 FP16 后与同次 `prepared-hdr` 全像素一致。
- 原 APK 库、同一诊断库标记开启和关闭共三轮均成功，最终 SDR RGBA 及 JPEG 的 SHA-256 分别完全一致；`film-src-pre-TEQ`（前链观察点，原生半精度）和既有 `prepared-hdr` 也一致。含同步和大体积导出的耗时不用于推断生产性能。
- 运行日志、设备哈希、侧车、像素文件及核验结果在 `test_temp/engine-reference-20261118/d0-device/`（`verified.json`）。本组可作为固定 EV 全尺寸真实夹具；还缺多场景输入与保留预览反馈、完整配方和最终采纳重绘记录的旧 L/G 回归。要补后者，应做新的同步采集，不能用旧样本缺失字段的默认值代替。

### 旧槽位反馈覆盖（不是生产配方回归）

- `tools/check-renderer-device.cpp --synthetic-feedback 0|1` 各用 10 帧测试图进入原预览统计，再用同一组真 RAW 进入原静拍链。两槽位的显示域锚分别为 0.179718331、0.186394110；各有一次补偿尝试及三次 tone draw，最终补偿归零，覆盖了高光护栏撤销路径。两轮均生成全尺寸 RGBA / JPEG，日志 `d0-device/feedback-{0,1}.log`。
- 现装 0.14.27 不支持 `run-as`。此轮没有从应用内读取同步的私有参数、LUT、实景预览统计或最终重绘记录，也未覆盖安装。带真实预览反馈的旧 L/G 完整回归仍待诊断构建同步采集。

## D1-A：锁定 V7 的隔离 CPU 参考已运行

在 `test_temp/engine-reference-20261118/darktable` 检出并核对 `395aa7d5156b68a69d8f2d25a3e6fc66c525a3c2`，未改上游。UI V7 对应 `DT_FILMIC_COLORSCIENCE_V5` / `filmic_v5`，样条 v3 是另一套版本编号。

macOS 原生构建在 GCC 14.2.0 下通过编译器和 OpenMP 检查，随后停在缺 `intltool-merge`；未改宿主 Homebrew 权限。外置盘挂载后，Docker 内的原版 CPU Release 已完整构建、安装并对拍，见下文。未运行 `process_cl`。

新增 `tools/filmic-v7-reference.py` 和 C I/O 适配器，从固定提交的 Git blobs 提取原版 `commit_params`、spline、hardness、`filmic_v5` 和相关的矩阵/色域实现，使用已有 Python、NumPy 和 Little CMS 2.17 编译与运行，产物仅在 `test_temp`。它是固定输入、指定 profile、关闭可选高光重建的独立 CPU tone 参考；不能等同上游整程序的 Release/OpenCL。有效参数、曲线节点/系数、源文件哈希与编译选项见输出 manifest；独立的阶段覆盖与剩余验证见 `filmic-v7-coverage.json`。

- `v7-fixtures-verified/manifest.json`：256 个配置组合冒烟，覆盖四种 profile 目标、九种肩趾组合、七个 highlights saturation mix（含 -50/0/+50 分支纯值与 ±200% 外插），及四组手动 hardness / 自定义灰。每个配置检查系数和 RGB 有限、目标线性 RGB 上下界；默认配置重复调用逐值一致。此处未建立 GPU 数值预算，不将边界容差当作移植误差门槛。
- `v7-d0-{srgb,p3}-verified/manifest.json`：同一份 SHA-256 `0452d6f65a738cc5908ab97036bc6f688697201d6255e4f65ce5768dbb86d450` 的 3072×4080 真机 D0 输入，输出完整 FP32。sRGB/P3 转入目标线性域后的范围分别为约 `[-1.0e-9, 1.00000155]` / `[-1.3e-11, 1.00000119]`；保留这些浮点残差，不夹值刷通过。sRGB 独立重编译并重跑，输出全字节一致。其中黑白 EV 为原版参数的 -8/+4，自动计算 hardness，非旧 Filmic 生产配方。明确只采用带 `-verified` 的输出：早期探测未给输入 ICC 矩阵助手传 TRC 缓冲，误走输出反矩阵分支，结果不作参考。
- V7 的 UI saturation 参数只用于前段 naive/max-RGB 混合，不是第二个独立控件；后段 hue/chroma/gamut 仍固定以 `saturation=0` 调用，提亮且增色时的特殊分支依然生效。不能把前段 mix 填给后段 gamut。

从仓库根目录复跑（`--out` 使用一个新的输出目录）：

```sh
root=test_temp/engine-reference-20261118
"$root/spektrafilm-env/bin/python" tools/filmic-v7-reference.py \
  --upstream "$root/darktable" --cc /opt/homebrew/bin/gcc-14 \
  --out "$root/v7-d0-new-run" --profile srgb \
  --input "$root/d0-device/edge_dump_109412869576_base_engine_input.rgba32f"
```

### SDR 输出编码对照

给复跑命令加 `--check-colorout`。参考工具执行锁定源码的 `colorout` 矩阵及 TRC 函数（原版 ICC 65,536 项表、暗部插值、超白外推），分别检查 RGB→Lab→目标线性 RGB 与线性 RGB→编码值。用独立 D65 基色矩阵核对主色方向，用分段 sRGB 函数核对 sRGB/P3 的表内编码。夹具包括灰阶、基色、分段点两侧、高光、负值与超白；合成夹具的最大表内端到端误差为 `2.46e-6`。这一节只覆盖 SDR 矩阵快速路径和浮点输出，不覆盖强制 Little CMS、软打样、HDR、整数导出或胶片 App 的最终编码。

| 同一 D0 的目标 | D65 矩阵最大系数差 | Lab 往返最大差 | TRC 表内最大差 | 矩阵＋TRC 表内最大差 |
|---|---:|---:|---:|---:|
| sRGB | 2.14e-5 | 9.46e-7 | 5.19e-7 | 5.66e-7 |
| Display P3 | 8.84e-6 | 9.42e-7 | 5.16e-7 | 5.59e-7 |

两路中灰与重复套 TRC 的结果均至少相差 `0.204`（归一化编码值），排除本参考链的二次编码。原有 Filmic 线性输出哈希完全不变。略超 1 的通道走上游外推，分别有 116,491／116,353 个，不计入表内误差；直接超白夹具验证没有被压回 1。对应产物目录为 `v7-colorout-fixtures-verified` 和 `v7-colorout-d0-{srgb,p3}-verified`。

### 原版 CPU Release 整模块对拍

外置盘上的 Docker（Ubuntu 26.04 arm64，镜像摘要 `sha256:d66b366dac61b575bee2aef07e9c26e8070096fbe84a3d4d3eb0da069b5d76db`）用 GCC 16.0.1、Little CMS 2.17、上游 Release 默认优化和 OpenMP 完成构建；OpenCL 明确关闭。锁定源码以只读挂载，未修改。探针 `tools/filmic-v7-darktable-probe.c` 使用原版 introspection 默认参数，传入固定设置的 auto-hardness 求解结果 `4.168313980102539`，用原版 profile 注册表和 `colorout::commit_params()` 发布目标 profile，再调用原版 `filmicrgb::process()`、RGB→Lab 转换和 `colorout::process()`。可选重建按原版默认关闭；未调用 GUI 自动取样、完整像素管线或文件编码器。D0 导入容器后核对 SHA-256 与宿主一致。

| 目标 | Filmic 线性最大差 | 编码端到端最大差 | 同一份原版线性输入时的 colorout 最大差 |
|---|---:|---:|---:|
| sRGB | 2.2638e-4 | 1.0687e-4 | 5.9605e-7 |
| Display P3 | 2.4229e-4 | 1.0771e-4 | 4.7684e-7 |

每路有 37,601,280 个 RGB 通道值。Filmic 线性差超过 `1e-4` 的通道分别为 124,379／137,723；编码端到端为 252／249；固定 colorout 输入后，两路均无通道超过 `1e-5`。差异主要发生在 Filmic，不是重复 TRC；尚未在同一编译器和浮点选项下分解剩余差异。以原版模块输出作移植基准，不能把跨环境差异当成手机端允许误差。两路完整比较和 SHA-256 见 `v7-native-parity-{srgb,p3}-verified/manifest.json`。原版 `libfilmicrgb.so`/`libcolorout.so` 的 SHA-256 分别为 `3c505b35a580a8cfa19671f403ba1ba640fbddf3ac6541f952ccee4cd11a19c4` / `0fe79a34606cb8959bc0788b595e99be79e54b1f27d8c3803d8c83ed10`。

构建容器 `filmkit-darktable-395aa7d` 已正常退出，原配置可用 `docker inspect` 查看。依赖快照 `filmkit-darktable-ref:395aa7d` 和同名构建卷 `filmkit-darktable-395aa7d` 留于 Docker；离线复跑不需重装依赖或改 Docker 镜像位置：

```sh
docker run --rm --network none \
  --mount type=volume,source=filmkit-darktable-395aa7d,target=/build \
  filmkit-darktable-ref:395aa7d /build/filmic-v7-darktable-probe \
  srgb 3072 4080 4.168313980102539 /build/d0.rgba32f \
  /build/retry-tone.rgba32f /build/retry-encoded.rgba32f -- \
  --library :memory: --configdir /build/probe-retry-conf --cachedir /build/probe-retry-cache \
  --datadir /build/install/share/darktable --moduledir /build/install/lib/darktable \
  --disable-opencl --conf plugins/lighttable/export/force_lcms2=FALSE
```

将 probe 的两个 RGBA32F 输出拷回宿主，在隔离参考复跑命令中追加 `--check-colorout --native-outputs <tone> <encoded>`。探针禁止覆盖已有输出；P3 用 `p3` 及不同输出路径。sRGB 在新临时目录离线复跑，两个原版输出均通过 `cmp` 逐字节一致。构建和探针都不接触 FilmKit 的生产代码、手机或预设。

## D1-B：Spektrafilm 原版冒烟通过，未完成 G1

`tools/spektrafilm-reference-smoke.py` 从干净的 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` 检出运行原版 `SimulationPipeline`。160×96 线性 Rec.2020 合成夹具保留全幅，关闭 preview/lut 快捷路径，不改波长/阶段/算法。三例均完成：Portra 400 + Portra Endura 固定曝光、同配方自动曝光、Provia 100F 正片直扫。每次执行被动记录正式 tap 的数组、尺寸、dtype 与 hash，结果均有限且非恒定。拓扑、光谱服务与光谱上采样的 16 项上游针对性测试通过。

结果位于 `test_temp/engine-reference-20261118/spektrafilm-smoke/manifest.json`，含精确依赖版本。环境使用项目临时目录中的 Python 3.13.2，未替换系统或用户的现有 Python；可以直接复用这份环境。冒烟未覆盖全尺寸生产输入、内部 service 状态观测、同随机场对照、原生随机统计或数值预算，因此不能视为完整 oracle 验收，也不能据此判断成片优劣或性能。

全尺寸 Provia 100F 固定曝光直扫：复用现有 Python 和锁定上游，从 D0 RGBA32F 仅翻转 GL 行序、去常量 alpha，不裁切、不降采样、不更改原版算法。首次在用户手动重启后中断；重跑完整产出 `rgb_pre`、`log_e_film`、`cmy_film`（均为 4080×3072×3 float64），随后扫描期间系统已用交换空间约 12.1 GiB、主机可用磁盘余量降至 2.7 GiB，主动终止以免写满系统盘。未取得 `rgb_out` 或完整进程峰值 RSS，不算上游全尺寸通过。重跑日志与中间阶段在 `test_temp/engine-reference-20261118/s-full-positive-rerun*`。原版完整扫描的单个全图 81 波段 FP64 数组约 7.56 GiB，且可能同时持有光谱密度与透射结果；应在更大内存环境重跑，不先缩波段或分辨率。

磁盘扩容后的带保护重跑完成：同一 D0 输入、同一提交和现有 Python，原版 Provia 100F 直扫产出 4080×3072×3 FP64 `rgb_out`，全有限、非恒定；文件 SHA-256 `401f19c83f8827430a9d210cc5e56e0851501354f7b2fd48c3a2546143ff6abb` 与清单一致。全程 194.73 s（含阶段导出与哈希），`time -l` 记录峰值 memory footprint 23.91 GiB、最大 RSS 8.53 GiB；可用磁盘从 25.37 GiB 最低降至 9.73 GiB，未触发 5 GiB 保护线。产物和完整清单位于 `test_temp/engine-reference-20261118/s-full-positive-guarded/`。这是单例全尺寸正片直扫通过，不覆盖负片印放、随机统计验收、官方 V7 对照或设备性能。

同一 D0 输入上的 Portra 400 + Portra Endura 固定曝光负片印放尝试：从 23.38 GiB 可用空间启动，沿用锁定上游和原精度，不启用预览或 LUT 快捷路径。底片阶段 `rgb_pre`、`log_e_film`、`cmy_film` 均已写出（4080×3072×3 FP64），但相纸曝光阶段尚未写出 `log_e_print` 时，监控采样发现磁盘余量已降至 4.36 GiB，低于 5 GiB 保护线，遂终止。没有相纸显影、最终 RGB、完整 manifest 或资源峰值。日志和中间产物位于 `test_temp/engine-reference-20261118/s-full-portra-fixed-guarded*`。现有实测只证明这次资源预算不足，不能据此倒推出最低通过配置；下一轮优先用物理内存更大的主机跑不变的完整原版。

## G0：目标资产与分发边界

已重新核对本地富士参考 ZIP 与方案所记录的 SHA-256 一致。其 transfer/range 与有效目标域仍待核实，只作研究候选，不是可发布资产或 X 系列静态直出真值。darktable 源码、Spektrafilm 源码/profile、内部表、富士教师及拟派生资产仍需按各自来源核对研究、集成及分发边界；本轮没有确认可分发路径，也没有将上游代码或资产接入生产。

## 下一个验收点

1. 扩展固定 EV 场景覆盖；同步记录新采集的预览反馈和完整配方，用同 RAW 重放旧 L/G，并单独验收真实系统静拍。
2. V7 优先：原版 CPU 整模块及 SDR colorout 已完成默认参数 D0 对拍；下一步补非默认参数的原版整模块验证、浮点差异归因与独立移植数值门槛。生产接入前确认上游使用及分发方式，再与真实 L/G 配方做同 RAW 核验。
3. Spektrafilm 定位为独立风格引擎，暂停四路收益排名和为此进行的全尺寸印放跑样。复用已取得的正片参考；后续只为接入配置的上游一致性、功能稳定性和设备资源成本补必要用例，不阻塞 V7。未变更旧引擎、用户预设或手机安装。
