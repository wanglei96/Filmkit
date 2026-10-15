# HEIF 交付档码率标定 —— 质量与体积都要（卡 FIX4）

> **状态说明（必读）**：卡 FIX4 的子代理**中途失败**（无 closing message），留下 `HeifQuality.kt`（288 行）+
> 5 个文件改动 + `tools/heif-quality-gate.py`，但**没有报告、没有任何测试、没有出包**。
> 架构师接手补完：① 审查发现并修掉"最坏 5 次编码 ≈ 9 s 落盘"；② 补 `HeifQualityTest`（10 条）+
> 三处变异检验；③ 出包；④ 本报告。
>
> **凡真机数字都是 2026-10-15 及之前回收的一手数据；本卡改动本身尚未真机验证**（设备未连），
> 凡属真机项一律标「待实测」，不写成已验证。

## 1. 任务与目标（用户拍板）

用户原话：**"为什么做不到和 PC 一样，质量保证 + 体积"** ⇒ 明确要求**两者都要，不许取舍**。

硬目标（写死在 `HeifQuality`）：

| 项 | 目标 | 判据 |
|---|---|---|
| 体积 | 同场景 JPEG 的 **40~60%** | 低于 40% = 压过头；高于 60% = 失去 HEIF 意义 |
| 质量 | **接近无损** | 解码后高频能量 ≥ JPEG 的 0.7；影调分位数偏差 ≤ 1 LSB |

## 2. 根因（算术层面，不是"格式不行"）

事故对照（同场景、都是 4:2:0）：

| | 文件 | 体积 | 与 JPEG 比 |
|---|---|---|---|
| JPEG q100 4:4:4 | `FILMKIT_20261015-163839.jpg` | 5,109,807 B | — |
| HEIF 8bit | `FILMKIT_20261015-163849.heic` | 197,926 B | **1/25** |

逐像素比过：影调色彩无偏（p50 164.63 vs 164.65、p90 207.59 vs 209.00）。**高频损失要分口径说**：

| 口径 | 比值 | 含义 |
|---|---|---|
| 全图拉普拉斯均值（架构师手算） | **0.30** | 把平滑区也计入 —— 那里 HEIF 把噪声压掉，比值天然低（分块看：平滑区 4~11%、细节区 26~57%） |
| **细节区**（`tools/heif-quality-gate.py`，筛出 4240 个细节块） | **0.859** | 结构性细节**保住了 86%**；被压掉的主要是细颗粒/噪声 |

⇒ 准确说法：**画质损失比"30%"给人的印象小**（细节区 86%），但**体积比 0.039（= JPEG 的 1/25）仍是硬伤** ——
HEVC 相对 JPEG 通常只省 30~60%，1/25 就是压过头。两条判据（体积比 + 细节区高频比）必须一起看，
只看后者会漏掉"过度压缩"这件事。

**算术根因两条**：

1. **漏了 frame-rate 折算**：`KEY_BIT_RATE` 单位是 **bit/s**，而静态图只编 1 帧 ⇒ 单帧预算 = `bitrate / frame-rate`。
   想给 12MP 单帧 2.5 MB（20 Mbit）就必须报 **600 Mbps**。
2. **上限夹太低**：旧公式 `w·h·30·2` 对 12MP = 737 Mbps，却被夹到 **240 Mbps** ⇒ 单帧预算仅
   240/30 = 8 Mbit = **1 MB**，编码器再一省就落到 198 KB。

> 一句话：HEVC 的体积优势是**格式自带**的，跟控制路径无关；只要把质量拉到接近无损，
> 体积会**自然**落在 JPEG 的 40~60% —— 不需要在两者之间取舍。

## 3. PC（PhotonCamera）口径对照

| | PC | 我们（旧） | 我们（本卡） |
|---|---|---|---|
| API | `HeifWriter`（androidx） | `MediaCodec` | `MediaCodec`（**要 10bit 就只能它**：`HeifWriter.useBitDepth10` 硬编码 false） |
| 控制量 | `.setQuality(100)`（`INTERNAL_HEIC_QUALITY`，`GalleryManager.kt:133` → `:952`，写死最高） | `KEY_BIT_RATE` + 上限 240 Mbps | QP（vendor）→ CQ+quality → 高码率 CBR，三路自动降级 |
| 分片 | `setGridEnabled(true)` | 无 | 无（单 item；grid 非必需） |
| EXIF | **写**（`addExifData`） | 不写 | 不写（见 §8 遗留） |

PC 的 `MediaFormat` 里**没有 quality 键**可用 —— 这正是我们必须用"码率逼近恒定质量"的原因。

## 4. 实现（FIX4 落下的部分）

**新增 `app/src/main/java/com/filmkit/app/heif/HeifQuality.kt`（288 行，零 android import ⇒ JVM 可测）**：

- **真源常量**：`TARGET_MIN_RATIO=0.40` / `TARGET_MAX_RATIO=0.60` / `JPEG_REFERENCE_BPP=3.25`
  （来历：真机 JPEG 5,109,807 B ÷ 12,533,760 px × 8 = 3.26 bpp，保守取 3.25）/ `FRAME_RATE=30`。
- **纯函数**：`targetRange` / `bitrateFor`（含 frame-rate 折算与 cap 夹取）/ `verdict` / `distance` /
  `stepQp` / `refine` / `strategies` / `pickBest` / `isBetter` / `Stats.kv()`。
- **三路策略**（按稳妥度）：① `qp` —— `bitrate-mode=CQ` + MTK vendor 键 `vendor.mtk.ext.venc.visual.quant`
  （真机 `outputFormat` 里出现过、当前 -1 未设）② `cq` —— `bitrate-mode=CQ` + 标准 `quality` 键
  ③ `cbr` —— 高码率兜底。**每路都靠 `verdict` 判"设了是否生效"**，而不是靠"设成功了"。
- **落键**在 `Hevc10Encoder.applyQuality`（android 相关留在那里，纯逻辑留在这里）。

`Hevc10Encoder` 侧：`encodeWithQuality` 推进多路 + QP 自校正，达标即返回；都不达标时用 `pickBest`
留**最接近**目标区间的那份，并把 `verdict != OK` 与 `tries` 一起进日志（**不许静默交付没达标的片子**）。

同步改了 `Heif8Encoder`（8bit 档质量口径）、`Heif10Pipeline`、`CameraController`（日志字段）。

## 5. 架构师接手补充（两处）

### 5.1 修掉"最坏 5 次编码 ≈ 9 s"

原实现：`qp` 路最多 `MAX_QP_TRIES=3` 次 + `cq` + `cbr` ⇒ **最坏 5 次编码**，每次 12MP 实测约 1.8 s
（还要每次重建一个 `MediaCodec` 实例）⇒ **约 9 s 才落盘**，这是用户可感的硬伤。

修法（最小改动，两处）：`HeifQuality.MAX_TOTAL_TRIES = 3` + 循环条件
`while (i < plans.size && tried.size < HeifQuality.MAX_TOTAL_TRIES)`。

- 正常路径**仍是 1 次**（`QP_DEFAULT` 标定合格时命中 `verdict==OK` 立刻返回）；
- 最坏 3 次 ≈ **5.4 s**；超限时用 `pickBest` 留最接近的那份 + 日志带 `verdict`/`tries`。
- 注释里写明：这是**防御性上限，不是替用户做的质量取舍**；真机标定后若一次即命中可收到 2，
  若某平台需更多次收敛再放宽（改动只此一处）。

### 5.2 补 `HeifQualityTest`（10 条，原卡零测试）

覆盖：目标区间模型自洽（12MP ⇒ 2,036,736~3,055,104 B）/ **码率 frame-rate 折算精确值 611,020,800 bps** /
cap 夹取三态 / `verdict` 边界（含真机事故片 197,926 B 必须判 `TOO_SMALL`）/ `stepQp` 方向与撞界 /
`refine` 只对 QP 路且受次数约束 / 三路顺序与共用码率 / `pickBest` 取最接近 /
**`MAX_TOTAL_TRIES ≤ 3` 防回归** / `Stats.kv()` 字段齐。

> 其中第二条是**本卡根因的守护**：漏掉 `FRAME_RATE` 折算会让码率只有 1/30，必压过头。

## 6. 判据实测

| 判据 | 实测 |
|---|---|
| 全量单测 | **779 / 0 失败 / 0 错误**（769 基线 + 本卡 10 条） |
| JPEG 零回归 | ✅ `--engine 0` ⇒ `film_d5.npy`=**0x589f8364**、shader=**0x4fa1ebd3**；`--engine 1` ⇒ `film_d5_sigmoid.npy`=**0x7c2646ef**（三个期望值全中） |
| 守卫 | ✅ 全部 RC=0：`check-bitdepth-single-source.py`（含 `--selftest`）/ `validate-shaders.sh` / `check-diag-fields.py` / `check-filmparams-contract.py` |
| 出包 | `filmkit-0.11.39-FIX-HEIF-RATE.apk`（388 / 0.11.39） |

## 7. 变异检验（三处，全部让测试变红；还原后全绿）

| 变异 | 结果 |
|---|---|
| M1 `bitrateFor` 去掉 `FRAME_RATE` 折算 | **2 红**（`bitrateFor_scalesByFrameRate`、`bitrateFor_clampsToCaps`） |
| M2 `MAX_TOTAL_TRIES` 3 → 5 | **1 红**（`totalTriesIsBounded`） |
| M3 `stepQp` 方向反转 | **2 红**（`stepQp_directionAndLimits`、`refine_onlyQpPathAndBounded`） |

## 8. 待真机验证（**本卡核心未验证项**）

1. **三路哪条真正生效**：MTK 的 `vendor.mtk.ext.venc.visual.quant` 是否被接受（非标准键，可能**静默忽略**）；
   若不生效则看 `cq` 路的 `quality` 键；再不行看高码率 CBR。
2. **体积是否落进 40~60%**：由 `photo.heif_encoded` 的 `bytes` 与 `targetMin/targetMax` 直接判定。
3. **`QP_DEFAULT=16` 是否需要标定**：若 `verdict=TOO_SMALL` ⇒ 按 `QP_STEP=4` 往下调；`TOO_BIG` ⇒ 往上调。
4. **耗时**：`tries` 与 `ms` 进日志 ⇒ 确认正常路径是 1 次、最坏不超过 3 次（≈5.4 s）。
5. **10bit 主线不许回归**：`awaitInput`（输入缓冲等 5 s 的修复）必须仍在。

> 辅助工具：`tools/heif-quality-gate.py`（原卡所写，用于拿真机 JPEG 与 HEIC 体积复核目标区间）。

## 9. 用户验证步骤

1. 装 `filmkit-0.11.39-FIX-HEIF-RATE.apk`（覆盖安装，prefs 保留）。
2. 同一场景各拍一张：**一张 JPEG、一张 HEIF**（顺序无所谓，别动相机位置）。
3. 看日志 `photo.heif_encoded`：
   - 期望 `"verdict":"OK"`、`"bytes"` 落在 `"targetMin"`~`"targetMax"`、`"tries":1`；
   - `"bitrateMode"` 会告诉你哪条路生效（`qp` / `cq` / `cbr`）。
4. 若 `TOO_SMALL`（还偏小）⇒ 把 `HeifQuality.QP_DEFAULT` 调低 4；`TOO_BIG` ⇒ 调高 4；
   重拍复验。**这一步是标定，不是缺陷**（QP 起始点本来就是待实测值）。
5. 达标判据（人工）：HEIC 解码后与同场景 JPEG 比 —— 影调分位数偏差 ≤1 LSB、高频能量比 ≥0.7、
   体积在 JPEG 的 40~60%。

## 10. 遗留

- **HEIF 不写 EXIF**（PC 写）。要补需在自研容器里加 Exif item，不能事后写（`ExifInterface` 会重写整个文件，
  违背"照片优先"）。**待用户决定是否要**。
- **grid 分片**未做（PC 有）。单 item 对 12MP 可用；grid 的价值在解码端局部刷新，非本期必需。
- 目标区间用的是**模型**（`JPEG_REFERENCE_BPP`）；同场景 JPEG 的真实体积随噪声涨跌（真机见过 7.5 MB 与
  37 MB 的片子）⇒ 复核时以 `tools/heif-quality-gate.py` 按真实 JPEG 体积为准。
