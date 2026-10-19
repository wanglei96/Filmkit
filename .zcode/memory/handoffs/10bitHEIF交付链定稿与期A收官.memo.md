---
summary: 10bit HEIF 交付链定稿与期 A 收官
created: 2026-10-15 22:05
updated: 2026-10-15 22:05
status: absorbed
---

# 10bit HEIF 交付链定稿与期 A 收官

## Summary

期 A（10bit HEIF 交付链）从零打通到定稿并收官：引擎 10bit readback → CPU BT.709 全范围
4:2:0 → P010 → 硬件 HEVC Main10（**VBR + 官方 QP 键，QP=4**）→ 自研 HEIF 容器 →
落盘 + 读头自检；三级回退 `10bit → 8bit HEIF → JPEG`。15 个包（0.11.32→0.11.46）迭代，
其中含一次**用户真实照片损坏的 P0 事故**。四轮真机把编码器的质量旋钮查清并定标
（**编码器不读码率；QP 键 + VBR 是唯一强旋钮；画质在 QP=4 饱和**）。
判据 F 三端全部人工通过（macOS / 微信 / 系统相册 + Windows）。下一期 = 期 B 域空间。

## Done

- [2026-10-15] **期 A 交付链定稿**：设置页「照片格式」（默认 JPEG / 可选 HEIF 10bit）；
  包 `filmkit-0.11.46-HEIF-FINAL.apk`（395）。链路与全部实测见 `docs/rec2020-heif-design.md`
  §4、§6c–§6g。
- [2026-10-15] **自研 HEIF 容器**（`HeifContainerWriter` 纯函数 + golden 单测，
  与原型逐字节相同）：`ftyp(heic) + meta(hdlr/pitm/iinf/iloc/iprp(ipco(hvcC/ispe/pixi/colr)/ipma)) + mdat`，
  `colr/nclx = 1/13/1/1`（R1 口径）、`pixi` 3×10bit。**不引入 libheif**。
- [2026-10-15] **Annex-B → hvcC 转换**（`HevcAnnexB`）：真机编码器给的是 Annex-B（80 B），
  之前"106 B hvcC"是 `MediaMuxer` 的转换产物；自研容器必须自己转（H.265 SPS 完整语法解析 +
  帧长度前缀化）。往返测试逐字节相同。
- [2026-10-15] **编码器标定（四轮真机，全部一手）**：
  · 码率无效 —— VBR 20.4 Mbps ⇒ 219 KB、VBR **100 Mbps ⇒ 187 KB**（预算 ×5 更小）；
  · `bitrate-mode` 必须 VBR —— 官方 QP 键在 CQ 下直接被拒（拿不到 `csd-0`）；
  · 官方 QP 键（`video-qp-i-min`/`-max`）是**唯一**强质量旋钮；`video-qp-average` 回显 0（未生效）；
  · `quality` 键有效但天花板低（0 ⇒ 8 KB、100 ⇒ 649 KB）；
  · vendor 键（`vendor.mtk.ext.venc.visual.*`）全是 -1 ⇒ 无隐藏旋钮；
  · **画质在 QP≈4 饱和**（QP4 ↔ QP0 同场景逐像素 p99 仅差 1 LSB，体积 +36%）。
- [2026-10-15] **判据 F 三端通过**：macOS ✅ / 微信 ✅ / 系统相册 ✅ / Windows ✅（用户实测）。
- [2026-10-15] **P0 事故修复 + 防线**：MFNR 落盘回调硬编码走 JPEG 分派、绕过该张下发时冻结的
  交付档 ⇒ 10bit packed 被当 8bit RGBA 编码，两张照片永久损坏（第 4 字节被 JPEG 丢弃 +
  位打包跨通道 + 有损放大 ⇒ 两种反解都试过，不可恢复）。修法：删掉共享成员 `stillTenBit`
  （会被渲染期间的第二张覆盖，与当年 `stillRot`/`stillAspect` 同型），每张随回调传自己的档 +
  唯一分派点 `deliverStill` + 格式参数**不留默认值**（编译期强制）+ 进 Bitmap 前断言
  （不匹配记 `photo.deliver_format_mismatch` 并降位，失败而非静默、也不丢片）。
- [2026-10-14] **默认引擎切 sigmoid 落地**（用户拍板 P2）：`CameraPresets.kt` 的
  `DEFAULT_ENGINE_SLOT = 1` + `FilmParams.h` `engine_slot = 1.0f` + **三处读取缺省收口到同一常量**
  （否则会出现"C++ 按引擎 1 渲染、Kotlin 按 0 判旁路"的第三态 + 设置页假读数）。
  顺带把引擎 1 默认 contrast 1.2→1.5（对齐 PC/darktable）。

## Decisions

- [2026-10-15] **交付域 = sRGB 显示域 10bit**（R1 选项 A，用户拍板）：取数点 = 现有 `tex_cap_`
  （OETF 后、LUT/颗粒/锐化之后），**不新增取数点**；`colr` = primaries 1 / transfer 13 / matrix 1 /
  full_range 1。线性档（9/8）不进本期 ⇒ 期 A 与期 B 解耦。
- [2026-10-15] **QP = 4 定稿，且禁自校正**（`MAX_QP_TRIES = 1`）。**理由 = 质量优先**：
  QP 固定在画质饱和点后，同一个 QP 在不同场景给 3.13~4.34 MB ⇒ **体积是结果、不是目标**；
  若还让 `verdict` 驱动自校正，`TOO_BIG` 会把 QP 往高推 = 为了体积主动降质量。区间从此只作日志警示。
- [2026-10-15] **`TARGET_MAX_RATIO` 0.60 → 0.70**（同理由）；**`JPEG_REFERENCE_BPP` 3.25 → 4.2**
  （三张同场景 JPEG 实测 5.11/6.08/6.86 MB ⇒ 3.26/3.88/4.38 bpp；旧值取自最小的那张 ⇒ 区间整体偏低）。
- [2026-10-15] **三级回退** `10bit → 8bit HEIF（平台 muxer + `image/heic` track）→ JPEG`，
  每级留痕，不允许"10bit 失败后连 8bit 都不试"（用户 10-14 原话）。
- [2026-10-15] **照片优先红线**：运行时读头自检（`colr`/`hvcC`/`pixi`）不过就删文件并回退 JPEG；
  诊断/对照档落盘失败只留 WARN，绝不影响已通过自检的主片。
- [2026-10-14] HEIF 档下 4:4:4 开关：按 PC 实机行为 —— 切 HEIF **自动备份并关掉**该开关 + UI 灰显，
  切回 JPEG **精确恢复**（用户拍板；不永久改用户设置）。

## Pitfalls

- [2026-10-15] **`frame-rate` 必须 = 1**（静态图只编 1 帧）。照抄视频口径取 30 ⇒ 单帧预算
  = `bitrate / 30`，且"想要 2.45 MB/帧"换算出的 611 Mbps 会撞编码器上限（真机 100 Mbps）被夹
  ⇒ 只剩 416 KB/帧 ⇒ 真机落盘 392 KB、高频仅 JPEG 的 32%。`HeifQualityTest` 有守护。
- [2026-10-15] **日志只留最后一路 = 无法定位**。0.11.39 时三路策略只 emit 最终生效那路 ⇒
  "哪条路真的驱动质量"只能反推。现有设施：`photo.heif_try`（**每路一条**）、
  `photo.heif_try_failed`（失败原因，此前被 `runCatching` 吞掉）、
  `photo.heif_enc_caps`（编码器自述能力）、`photo.heif_out_format`（`outputFormat` 全键
  含 vendor，**回显 = 编码器认这个键**）。
- [2026-10-15] **Kotlin 块注释可嵌套** —— 注释里出现 `/**`（如 `bit/**秒**`、`i-max/**average**`）
  会开启嵌套注释、吞掉文件后半段，报"Unclosed comment / Missing '}'"。**踩了两次**。
- [2026-10-15] **不许把用户复述我的话当参数依据**（用户当场纠正："我说 8 是因为你给我的就是 8"）。
  正确做法：拿数据定（做过 QP 扫描 + 同场景 A/B 才定到 4）。另：我的外推两次偏
  （"QP=8 约 3.2 MB"实际 2.55 MB；JPEG 基准取自最小张 ⇒ 区间偏低）。
- [2026-10-15] **共享成员跨帧被覆盖**是反复出现的同型 bug（`stillRot`/`stillAspect` → `stillTenBit`）：
  每张照片的交付档必须**随回调传递**，不许放共享成员。
- [2026-10-15] **`| tail` 会吞掉退出码**：`gradle ... | tail -6 && cp ...` 在编译失败时仍会
  `cp` 出旧包（把 0.11.41 的内容贴上 0.11.42 的名字）。出包脚本必须显式检查退出码。
- [2026-10-15] 诊断设施要按"一次真机拿全数据"设计：0.11.44 扫描只落 best ⇒ QP=0 那张无从比较，
  多花一轮；0.11.45 改成两档同时落盘 ⇒ 一轮定案。**用户的每一次插线都是稀缺资源**。

## Next

1. **期 B：公共工作空间切 Rec.2020 线性**（`docs/rec2020-heif-design.md` §5 的 8 面爆炸半径）。
   ⚠ 必须**单独一期**（与插槽重构同期会毁掉零回归判据）；A 组守卫会**整体变红**，需要一次性重定基线：
   filmic gamut primaries · CCM 链终点（`StdColorMatrix.kt:29-33`）· OETF 5 处
   （4 处 shader `srgbEncode` + CPU `:14245`）· LUT 域（无需改但要重验）· 预设重打标 ·
   输出映射 · 数值阈值 · 零回归基线。
2. **判据 F 的长期回看**：系统相册/Windows 已过；换域或换编码参数后需重看（下游解释风险
   是用代码消不掉的那类风险）。
3. 期 A 未动的两处已知边界：**4:2:0 色度是 P010 硬件硬限**（无 4:2:2/4:4:4 10bit 路径）
   ⇒ 色度细节 HEIF 档必然不如 JPEG 4:4:4；**HEIC 不写 EXIF**（自研容器无 Exif item，
   不为元数据冒"照片优先"的险）。

## Refs

- 方案与全部实测：`docs/rec2020-heif-design.md`（§1b 拍板 · §4 路线 · §5 期 B 爆炸半径 ·
  §6b 事故 · §6c 端到端打通 · §6d/§6e 根因与走死 · §6f QP 键发现 · §6g 定稿）
- 代码：`app/src/main/java/com/filmkit/app/heif/`（`HeifQuality` 质量口径真源 ·
  `Hevc10Encoder` · `HevcAnnexB` · `HeifContainerWriter` · `Heif10Pipeline` · `Heif8Encoder` ·
  `HeifDeliverCheck`）；`core/engine/src/render/OutputBitDepth.h`（位深单一真源）
- 工具：`tools/check-bitdepth-single-source.py`（位深守卫）· `tools/heif-quality-gate.py` ·
  `tools/check-log-json.py`（分析日志前必跑）
- 包：`filmkit-0.11.46-HEIF-FINAL.apk`（395，定稿）；对照样片与差异图 `test_temp/_diag/`
- 关键提交：`152304d`（10bit 链 + 默认 sigmoid）· `362ef24`（P0 修复）· `42b6c31`（Annex-B→hvcC）·
  `5bcefc6`（输入缓冲超时）· `925a505`（码率标定）· `258a32f`（CSD 断言 + 8bit 回退）·
  `16686fc`（QP=4 定稿）
