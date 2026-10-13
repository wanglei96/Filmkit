---
summary: OETF修正与色度差定位到前置链
created: 2026-09-28 22:25
updated: 2026-09-29 12:00
status: archived
---

# OETF修正与色度差定位到前置链

## Summary

两个独立缺陷落地 + 一个根因收敛。① 输出 OETF 用 `pow(c,1/2.2)` 而非真 sRGB（黑场被抬
8~9/255）→ 改真 sRGB，三处同步（v0.10.23）。② "灰"的亮度账：LSC 校正实测抬亮
**0.52EV**（中位、边缘 0.69EV），与 `base_ev +0.5` 叠加 = 暗部/中间调被双重抬亮。
③ 色度差（同亮度 C\* 低 17~21%）定位到 **filmic 之前的色彩矩阵**，逐项排除 min/降噪/
LSC/去马赛克/工作域；实测我们两条链（std 32.5 / hal 29.2）**都低于 PC 37.2**。
PC 本次也选 filmic、不经 DCP（用户确认），其矩阵来源未定位（用户指示不必再查）。

## Done

- **v0.10.23-m4（174）**：`kFsOut`/`kFsHist`/`measureSceneStats.to_lin` 三处同步改真
  sRGB；**版本号单一来源修根因**（`FilmKitApp.VERSION` 改读 `BuildConfig.VERSION_NAME`
  + `buildConfig = true`）；commit `0e6cb44`、`aac5c98` 已推
- **v0.10.24-m4（175）**：`raw.meta` 同时记录 `ccmStd`/`ccmHal` 两条链的 3×3 矩阵
  （一张照片即可离线对拍，此前要来回切开关）；`halCcmOf()` 抽出复用；commit `d1dcb34` 已推
- **OETF 效果离线预演**（同张成片重编码）：P1 0.022 → **0.003 ≈ PC 0.004**；
  P50/P95 几乎不动
- **LSC 实测增益**（DNG 链 vs 成片逐像素对拍，r=0.95；中心比值 0.981≈1.0 自证）：
  全图中位 **1.432×（+0.52EV）**、边缘 1.616×（+0.69EV）
- **色度差的排除清单**（每条都有判据，见 Pitfalls）
- 新增 `tools/check-srgb-oetf.py`（含变异测试：写反 step 即 exit 1）、
  `tools/ccm-chain-ab.py`、`test_temp/analysis/` 下 9 个分析脚本

## Decisions

- **OETF 用真 sRGB（IEC 61966-2-1）**，不用 `pow(1/2.2)`——JPEG 打的本来就是 sRGB ICC
- **用户实测确认 PC 本次也选 filmic、不经 DCP** ⇒ 本轮早前"PC 色彩来自 DCP 等效标定"
  的推断**作废**
- **用户指示：不必再查 `HncsRenderPlan` 矩阵字段**
- **全局 EV 路线维持关闭**（用户 -1.1EV 实测：等比变暗、局部对比反降）——本轮我一度
  又建议调 `base_ev`，与已证结论自相矛盾，已撤回

## Pitfalls

- **`pow(1/2.2) ≠ sRGB`**：中灰以上几乎重合，**暗部差最多 8~9/255**（pow 在 0 处斜率
  无穷，极暗值被抬成灰）；sRGB 线性段（12.92x）才让黑是黑
- **统计折回必须与编码同步**：编码改 sRGB 而 `to_lin` 仍用 pow，会让 linMid/pLo/pHi
  系统性失真（显示域 0.1 处 0.0100 vs 0.0063，差 1.59×）
- **版本号第二处硬编码**：`FilmKitApp.VERSION` 与 `build.gradle.kts` 各漂各的 ⇒ 174 的包
  日志仍报 0.10.22，真机版本判读被误导（本轮据此误判过一次"装的是旧包"）
- **不同保真度的数据不能做 A/B**：旧包读的是原图、新包是 DSH 重编码副本
  （1777×2360、ICC 丢失）⇒ 暗部极端分位不可比，据此得出的"OETF 没生效"结论无效
- **min 的作用域（实测 `fin/orig` 分布）**：只在 tone 后 **L\* > 50 的高亮区（15% 像素）**
  压低色度；L\* < 50 的 85% 像素 `fin ≥ orig` ⇒ min 取 orig、**色度保持** ⇒
  红杯（L\*≈21）的色度 = CCM 输出色度（脚本 31.76 ≈ 成片 32.48 自证）
- **大色块核心区判据**：腐蚀到远离所有边缘的纯色块，可把"降噪抹色度"与"矩阵偏淡"
  分开（红杯核心 C\* 32.03 vs 38.63、L\* 相同 ⇒ 不是降噪）
- **LSC 两边同构**（PC 亦 `rgb *= vec3(shading.r, 0.5(g+b), shading.a)`）⇒ LSC 不是差异来源
- **工作域非主因**：数值验证 `BT2020→LMS × (sRGB→BT2020) ≡ sRGB→LMS`（偏差 **0.0000**），
  Ych 色度是物理量（两域差 1.6%，远小于 17%）
- **日志 pLo 不足以区分编码**：P0.1% 在 64-bin 直方图 + 下采样后只代表 ~24 像素
- **PC 的暗部处理是"低光去饱和"**（`HncsToneAlgorithm` 的 `uHncsLowLightDesaturation`），
  Hncs 链里**没有** filmic 后加饱和的环节

## Next

1. **色度差（主）**：在 filmic 之前找第三条色彩矩阵来源（既非 std ForwardMatrix 双光源
   插值、也非 HAL `COLOR_CORRECTION_TRANSFORM`）。已知事实：PC 选 filmic 也高 15%；
   PC 的 Hncs 体系有 `EquivalentCameraCalibration`/`HncsRenderPlan`，但**用户已排除 DCP 路线**
2. **亮度配平**：LSC（+0.52EV）+ `base_ev`（+0.5）叠加需重新配平；**不能用全局 EV**
   （已证无效），须从曲线工作点/采集曝光入手
3. **高光不足 0.3~0.66EV**：PC 采集曝光更高（`ISO 2011/30.3ms` vs 我们 `1457/20ms`）
4. gamut min 的取舍：数据已齐（暗部/中间调根本不触发），**暂不动**

## Refs

- 版本：**v0.10.24-m4（175）**；commits `0e6cb44` / `aac5c98` / `d1dcb34`，均已推 main，**未打 tag**
- 代码：`FrameRenderer.cpp`（`srgbEncode`/`measureSceneStats.to_lin`/`gamutMapV5`）、
  `FilmKitApp.kt`、`CameraController.kt`（`halCcmOf`/`raw.meta`）
- 脚本：`tools/check-srgb-oetf.py`、`tools/ccm-chain-ab.py`、
  `test_temp/analysis/{g_check,g_inv,g_vig,g_oetf,g_quant,g_dng_chain,g_end2end,g_chroma}.py`
- 样片：`test_temp/FILMKIT_20260928-{184956.dng,184958.jpg, 205833.dng,205835.jpg,
  211545.dng,211547.jpg, 220634.dng,220637.jpg(std链), 220701.dng,220702.jpg(hal链)}`；
  PC 参照 `test_temp/PhotonCamera_20260928_185013.无.heic`（8bit sRGB、非 HDR）
- 日志：`filmkit-logs-20260928-{200141,205924,211611,220728}`
- PC 源码：`/tmp/photoncamera`（`HncsProfile.kt`、`HncsToneAlgorithm.kt`、
  `EquivalentCameraCalibration.kt`、`DarktableFilmicToneAlgorithm.kt`、
  `GlesMgcRawSpatialShaders.kt`）
