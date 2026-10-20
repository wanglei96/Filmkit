---
summary: 期B域切换落地与域错配修复
created: 2026-10-19 15:55
updated: 2026-10-20 09:45
status: absorbed
---

# 期B域切换落地与域错配修复

## Summary

期 B（公共工作空间切线性 Rec.2020）从零落地到修复轮：矩阵与域按**构造法 + 跨实现对拍**
重建（真源 `tools/rec2020-domain-matrices.py`），输出端补域收口，并修掉**三处域错配**
（预览不进 CCM / HAL 回退链终点 / U-1 跨链路）。四个提交（`3fa65af`→`a3aedfe`），三个包
（0.11.47/0.11.48/0.11.49），16 个守卫全绿。用户真机验收：② 饱和修正、④ `[40]` 隐藏、
对比度滑杆三项**通过**；**预览与成片仍有架构性差距**（饱和度 +39%、暗部更黑），用户决定不再挖。

## Done

- [2026-10-19] **矩阵真源** `tools/rec2020-domain-matrices.py`：构造法（非硬编码）+ 三重对拍
  —— 内部（sRGB→LMS 2.22e-16 / XYZ(D50)→sRGB 3.38e-04）+ 跨实现（PhotonCamera 的 BT2020 版
  LMS 9.8e-11、逆 5.8e-09、sRGB→Rec2020 4.4e-07）+ 产出的 Rec2020↔sRGB 矩阵与行业标准值吻合。
- [2026-10-19] **filmic LMS 整组**换 Rec.2020：实读确认**只有 6 个 vec3** 随工作空间变；
  色域边界解析解的原色经 `coeffs` 参数传入 ⇒ **自动联动、无需改数值**（§5 那句"`:4334-4367`
  也要改"应修正）。
- [2026-10-19] **CCM 链终点** → Rec.2020；**HAL 回退链**同步补 `srgbToWork`
  （`StdColorMatrix.halToWorking`，依据 = 调用点是 `stdCcm ?: halCcm` ⇒ 主链与回退链必须同终点）。
- [2026-10-19] **预览/静拍输入**补 `srgbToWork`（实读：`uCcm` 只在 `kFsRaw`/`kFsCcm`
  ⇒ 预览**根本不进 CCM**，数据停在 sRGB 线性却被下游按 Rec.2020 消费）。
- [2026-10-19] **D1 债清偿**：预览/静拍去 gamma `pow(2.2)` → **真 sRGB EOTF**（暗部差约 1 LSB）。
- [2026-10-19] **输出域收口**：`kFsOut`×2 / `kFsHist`×1 在 OETF 前做 Rec.2020→线性 sRGB；
  域外色从 clamp 硬切 → `gamutFit` 去饱和压缩（保亮度/保色相）；三处统一走
  `workToDisplayLinear` 唯一入口（防漏调用）。
- [2026-10-19] **U-1 跨链路 bug 修复**：split `[23][24]`/mono `[25]` 从 `kFsFilm`（线性域）
  挪到 `kFsOut` 显示域段（暗角后、LUT 前，与 `kFsStyle` **逐字同式**）。零回归证据：
  `film_d5`/`film_d5_sigmoid` 的 CRC 与挪动前**完全相同**（默认参数下该段恒等）。
- [2026-10-19] **引擎 1 对比度补齐**：新增 `uContrastOut`（引擎 1 = `[4]`、引擎 0 = 1.0 恒等）。
  此前 `[4]` 在引擎 1 下**完全没有实现**（唯一消费点 `updateFilmicEV` 产出的全是引擎 0 私有 uniform）。
- [2026-10-19] **`[40]` 色域映射补 gate**：它**本来就在** `EngineScope.FILMIC_OWNED_SLOTS`，
  是设置页漏挂 ⇒ 引擎 1 下"显示但完全无效"（`uGamut` 只在 filmic 段被消费）。
- [2026-10-19] **守卫**：16 个全绿；`softglow`（2a 阈值 1e-6→1e-4）、`u4`（③④ 判据去字面量锚定）、
  `style-passthrough`（⑰d 比对对象 kFsFilm→kFsOut）三处适配。
- [2026-10-19] **归档** `docs/reports/phaseB-old-domain-baseline-ARCHIVE.md`：旧域基线全量、
  守卫两类分类、空间相关落点普查（含 §5 未列的 B/C 类 luma）、**待重标 7 项**。

## Decisions

- [2026-10-19] **交付域保持 sRGB**（期 A R1 不变）：内部大盘子、输出转回小盘子 —— 与 PC
  一致（实读 PC：AgX/Sigmoid/Filmic 跑 BT2020，输出统一 `computeWorkingToOutputTransform(…, SRGB)`
  存 8bit JPEG；且 PC 的实时预览用另一套简化实现 ⇒ 它在"预览 vs 成片"上比我们更不一致）。
- [2026-10-19] **预设不重标**（沿用用户 10-14"预设先不动"）：本期只保证链路正确，观感变化
  留到"域完成后重估"。理由：重标的目的是"恢复旧观感"，与本期目的（改善高饱和色）方向相反。
- [2026-10-19] **U-1 修法对齐 U-4 的既有做法**（放显示域、两链共用同一段数学），**不是**把
  `kFsStyle` 改成线性域 —— 后者违反 ADR-7（禁止 `kFsStyle` 内单程 EOTF，⑬f 有调用次数哨兵）。
- [2026-10-19] **D1 从"期间不动"改为"清偿"**：先按"一次只改一件事"保留 `pow(2.2)`；
  用户实测"预览与成片仍有差距"后改成真 sRGB EOTF。
- [2026-10-19] **域外色压缩先用简版**（去饱和、保亮度保色相），不直接上 `gamutClipChroma`
  解析解（要搬 LMS 函数进 `kFsOut`）⇒ 数据说不够再升级。

## Pitfalls

- [2026-10-19] **uniform 默认值必须等于"跳过"**：`uContrastOut` 的恒等条件写成 `!= 1.0`，
  而默认值是 0 ⇒ 任何没走到下发点的路径（离线执行器、测试夹具）都执行
  `clamp((c−0.5)×0+0.5)` 把画面拉成**平坦灰 0.5**（`check-u4` 抓到 max|Δ| = 119.5 LSB）。
  ⇒ 判据写 `> 0.0`。同型于"探针取到恒等值"陷阱。
- [2026-10-19] **`check-film-offline` 需要配套参数生成的产物**（`--film-tone-k 1 --film-ev 0
  --film-gamut 0`）；参数不匹配会误报红（我误判过一次）。
- [2026-10-19] **注释里的数值要实测后再写**：曾在注释写"≈2 LSB"，实测 ~1 LSB ⇒ 已修正。
- [2026-10-19] **预判被实测推翻**：`check-toeshoulder` 我以为换域必红（60 个 spline 系数），
  实际**绿** —— 它不依赖工作空间（纯 CPU spline 数学）。
- [2026-10-19] **"恰好 N 项"是里程碑式断言**：设置页 gate 计数"恰好 5 项"因补 `[40]` 变 6
  ⇒ 两个 UI 测试红。补挂是对的（`[40]` 本就在真源表），测试改 6 并写明依据。
- [2026-10-19] **`git stash` 回 baseline 对比**是分清"预期红 vs 真红"的可靠手段：本次用它确认
  `srgb-oetf`/`hr-pre` 预先就红，而 `softglow`/`u4`/`style-passthrough` 是我改红的。

## Next

1. **真机待验**：判据 F（HEIC 四端）+ 阶段 3 判据 0。`0.11.49` 已验证三项（② 饱和修正、
   ④ `[40]` 隐藏、对比度滑杆可用）。
2. **待重标 7 项**（归档 §7 有清单）：U-1 `[23][24][25]`（**生效域变了**）、`[20..22]` 分区饱和、
   `kFsHaloThr` 阈值、`kFsHist` 统计 / 自动 EV、`kFsLocal` knee、内置预设。
3. **`FILM_SRC_BASELINE` 尚未写入守卫脚本**（仍钉旧值 ⇒ `--splice-selftest` 会误报）；
   应"旧值保留 + 新值填入"。
4. **HEIC 编辑器显示异常（未复现，用户决定"有出现再说"）**：症状 = "一坨五颜六色"（= 记忆库
   0.11.34 同款霓虹伪彩）。已排查到：`ui/ImageDecode.kt` 用 `BitmapFactory.decodeStream` +
   `inSampleSize` 且**对 HEIC 无特判**，怀疑 10bit HEIC 走该路径出错。**验收缺口**：期 A 判据 F
   只测四端、**没测 App 内回看**。
5. **预览与成片的架构性差距**（用户决定不再挖）：饱和度 +39%（0.1294 vs 0.0930）、暗部更黑
   （P5 8.9 vs 34.2）、动态跨度基本相同（191.1 vs 187.9 ⇒ **不是对比度问题**）。根因 = 预览走
   厂商 ISP、成片走我们的 CCM，且 ISP 已做过一次 tone。⚠ 测量本身有局限（截图含 UI/网格、
   视野未必对齐）。
6. **阶段 4（ACES）/ filmic 去留 / 原厂风格引擎**：域空间已完成 ⇒ **技术门已开**，可排期。
   ⚠ 原厂引擎调研先弄清：PC 只有 Lumix/Canon/Leica/Hncs（**无哈苏**），且**富士已被 PC 自己
   移除**（`56d33aa`）⇒ 可能有法务风险。

## Refs

- 设计与归档：`docs/rec2020-heif-design.md`（§5 爆炸半径）·
  `docs/reports/phaseB-old-domain-baseline-ARCHIVE.md`（旧域基线 + 落点普查 + 待重标清单）
- 工具：`tools/rec2020-domain-matrices.py`（域矩阵真源）· `tools/check-*.py`（16 个守卫）
- 包：`filmkit-0.11.49-REC2020-FIX2.apk`(398) · `filmkit-0.11.48-REC2020-FIX.apk`(397) ·
  `filmkit-0.11.47-REC2020.apk`(396)
- 提交：`3fa65af`（域切换 + 收口）· `c0724ff`（UI/守卫适配）· `ec817b9`（工具 + 归档）·
  `a3aedfe`（版本号）
- 真机样本：`test_temp/FILMKIT_20261019-154653.jpg`（成片）·
  `test_temp/Screenshot_20261019_154645.jpg`（预览截图）·
  `test_temp/_diag/heic_213046_decode_check.jpg`（期 A HEIC 解码验证）
