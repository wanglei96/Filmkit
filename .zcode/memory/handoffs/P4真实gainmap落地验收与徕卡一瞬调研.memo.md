---
summary: P4真实gainmap落地验收与徕卡一瞬调研
created: 2026-10-25 20:30
updated: 2026-10-25 20:30
status: active
---

# P4 真实 gainmap 落地验收与徕卡一瞬调研

## Summary

P4 从基线重测走到真机验收：先固化测量口径（`tools/hdr-baseline-eval.py`），用 21 张
旧样张钉出旧 gainmap 三大指纹（sceneY 纯函数，中灰 1.1 平台 + 2.5 封顶，SDR base
根本不参与），随后落地 `gainTrue`（真实跨曝光比值 + 中灰自锚定，0.12.85），真机 5 张
验收指纹全消（128 针 79.7%、255 针 9%、4x 恢复、HDR≥1.0 追平 PC 量级）。另修方向
锁死 bug（0.12.86）。徕卡一瞬（M9）预设标定调研完成：PC 固件考古配方解码、现有引擎
+ 烘焙 LUT 路线定案、11 张样张指纹库建立，未动工。

## Done

- **P4 基线重测**：`tools/hdr-baseline-eval.py`（MPF 解析逐条对照 vendored libultrahdr
  `hasValidMpfAssociation`；base/gainmap/HDR 重建统计固化口径）；test_temp 21 张出数。
  旧版指纹：p90 恒 0.533、码值 136 占 96.5%（1.1 平台针）、码值 212 钉死 2.5 封顶、
  max 恒 0.847。`ratioFromScene = clamp(max(1.1, sceneY*1.6), 1.1, 2.5)`——SDR base
  参数声明但未用；git 全历史仅 2 提交（0.11.76 建 / 0.12.21 定型）。
  报告 `docs/reports/hdr-baseline-20261025-REPORT.md` + json。
- **P4 实现（0.12.85，534，提交 `2c57e34` + tag v0.12.85）**：kFsGainmap 新增
  `gainTrue`（默认档 0）= `hdrAligned/sdrY`，`hdrAligned = hdrY×(0.1845/ae_p50_)`——
  scene 锚用本张 ae_p50_（与 uHdr 同一 film_src 测得、同 submit 内先赋后用）、display
  锚 kAeMiddleGrey；`clamp(ratio, 1.0, 4.0)`：下限 1 = 暗部零放大（offset 陷阱消失），
  上限 4 = 元数据恢复全量程。旧 sceneY 塑形/官方公式留作档 1/2（三档循环，prefs
  迁移 1→2 防语义漂移）。`uGainmapMath` 三档 + 新 uniform `uSceneMid`。
- **P4 真机验收（5 张 0.12.85）**：三大指纹全消——码值 128 占 79.7%（ratio=1 锚定针）、
  码值 255 占 9%（灯区 4x）、max 4.0x×4 张（柔和场景 2.25x 真实值非钳位）、p90/mean
  全部随场景变化、HDR≥1.0 占比 0.2~12.7%（PC 量级）。柔黑确认不需要（黑位紧）。
  用户目视通过。
- **方向锁 bug（0.12.86，535，提交 `2cc055b`）**：MainActivity 无 screenOrientation、
  Manifest 注释声称的 requestedOrientation 不存在——系统自动旋转开时窗口转横，
  guideAspect 跟窗翻横 + 渲染链按竖窗设计 ⇒ 取景框横框预览压扁。修复 = Manifest
  锁 `screenOrientation="portrait"`（aapt 验证产物）。横拍成片走 orientationStep 不受影响。
- **徕卡一瞬（M9）调研（`docs/research/19-预设标定调研-徕卡一瞬M9.md`，提交 `dae7bb0`）**：
  ① 目标拆解=德味四要素（克制影调/浓郁色彩/一点点偏色/暗角）+ WB 死盯日光；
  ② PC `/tmp/pc-full` 有 M9 固件考古工程（m9-1_216.upd → LUTS.bin：20×2048 曲线 +
  R≥G 分支双 3×3 矩阵 + 传感器标定，`LeicaProfile.kt`）；③ 分支矩阵已解码验证
  （行和=1 保 WB 色彩矩阵），曲线域语义不明；④ leica_m9.lut 审计：数据合格但影调
  斜率 1.19（42 枚中第二重）= 与 filmic 抢职责；⑤ 11 张 M9 彩色样张指纹库
  （`docs/research/leica-m9-samples/`）：p25≈21 黑位紧、≥240 中位 0.1%（顶白只留
  强光源）、C*P90 24.5 双向浓郁（暗部也带色）；⑥ 结论=现有引擎够：烘焙 LUT 承载
  CCD 色彩响应（分支矩阵→64³ 纯色彩 display 域 LUT），新引擎为备选；柔黑不需要
  新槽位（black_ev+toe 承载）；WB 锁定是唯一新机制；旧 builtin「徕卡一瞬」预设仅
  参考不作起点（用户明示重新标定）。
- 出包纪律执行：0.12.85/0.12.86 三验证（签名/版本 aapt/JNI 4 符号），过期包清理
  （根目录只留 0.12.85+0.12.86）。

## Decisions

- **P4 中灰自锚定**：scene 锚 = ae_p50_（与 uHdr 同源、随曝光自适应，不写死域常量）；
  display 锚 = 0.1845 常量（显示侧实测锚留作后续升级）。中灰 ratio 恒 1 防 0.11.74
  发灰复发。
- **上限 4.0 与 5619 天空灰白**：真实比值下 4x 只在小面积高光核心顶格（天空 ratio
  连续 1~4x 随 ae_p50_ 自适应），与旧版 t² 一刀切机理不同；不拍脑袋保证——A/B 档
  （=1）+ 预案 kHdrCeil 软限（HDR 亮度 ≤2×SDR 白按 sdrY 收，不全局砍）。
- **样片代表性判据**：旧样片对 gainmap 指纹有效（shader 未改的 git 证据 + ratio 只
  依赖 sceneY）；对 base 曝光账目无效（AE 删除 0.12.71 分界）。
- **徕卡一瞬路线**：现有引擎 + 烘焙 LUT（一期）；新引擎（原生分支矩阵 pass）仅在
  LUT 域偏差/精度不满意时上；判据双轨——管线正确性=配方逐位对照（不依赖小米样张），
  风格锚点=网络样张集指纹表。

## Pitfalls

- **MPF 解析两坑**（一手依据 = vendored `jpegr.cpp hasValidMpfAssociation`）：
  `kMpEndianSize=4`（"MM\x00*" 含 magic，IFD offset 在 tiff+4）；`kMPEntrySize=16`
  非 20（attributes/size/offset/dep 各 u32）。secondary 绝对位置 = tiff + dataOffset，
  校验用扫描出的第二张 JPEG 范围。
- **numpy 空间平滑度审计 axis 用错**：`np.diff(lut, axis=3)` 是通道轴（R-G 差天然
  >127，19059 处假警报）；空间轴是 0/1/2。
- **`tools/lut-audit.py` 只认 .cube 文本**，assets 已是 .lut 二进制
  （`[size:1B][titleLen:1B][title][catLen:1B][category][data]`）——工具欠升级。
- **PC M9 配方的 1D 曲线域语义不明**（线性 in 0.18→out 0.59 非线性域形状）——
  烘焙只烘分支矩阵不烘曲线；曲线域需逆向 PC shader 调用上下文才能确认。
- **版本串 `strings classes.dex | grep -c` 静默无输出**（第二次遇到）——
  `aapt dump badging`（版本号）与 `aapt dump xmltree`（manifest 属性）更可靠。
- grep 对二进制文件的 `-c` 行为不可靠，判资产特征用 python bytes.count。

## Next

1. **徕卡一瞬标定动工（新窗口）**：烘焙脚本（LUTS.bin 分支矩阵 → 64³ 纯色彩
   display 域 LUT，SHA256 锁定）→ 影调重标（黑位 p25≈21、高光压缩按指纹、
   ≥240 ≤1%）→ 色彩（C*P90 → 20+ 量级，暗部饱和不压）→ halation/颗粒/暗角 →
   WB 锁定机制立项（预设携带采集行为，唯一新机制）。判据双轨见 Decisions。
2. **天空场景 HDR 验收**（等天气）：强逆光/日落对照——「天空灰白」（5619）唯一
   未测场景；若复现 → kHdrCeil 预案（0.12.87）。base 账目（≥240）随天空样张重算。
3. 旧 builtin「徕卡一瞬」预设待重标定后覆盖。

## Refs

- 调研：`docs/research/19-预设标定调研-徕卡一瞬M9.md`、样张库
  `docs/research/leica-m9-samples/`（11 彩色 + 8 黑白）
- 基线：`docs/reports/hdr-baseline-20261025-REPORT.md` + `.json`、
  `tools/hdr-baseline-eval.py`
- PC M9 配方：`/tmp/pc-full/app/src/main/assets/leica/m9/`（manifest.json +
  PROCESS/LUTS.bin）、`java/com/hinnka/mycamera/raw/LeicaProfile.kt`
- 提交：`2c57e34`（P4）/ `2cc055b`（方向锁）/ `dae7bb0`（调研）；tag v0.12.85
- 包：`filmkit-0.12.86-ROTLOCK.apk`(535)、`filmkit-0.12.85-P4GAIN.apk`(534)
