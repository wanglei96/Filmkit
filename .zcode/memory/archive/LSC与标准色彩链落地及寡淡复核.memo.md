---
summary: LSC与标准色彩链落地及寡淡复核
created: 2026-09-21 19:46
updated: 2026-09-22 10:15
status: archived
---

# LSC与标准色彩链落地及寡淡复核

## Summary

M3 完成 P0 镜头阴影校正（LSC）与实时直方图，落地 DNG 标准色彩链（自建标定矩阵），并用真机 A/B 复核**推翻了 v0.5.42 的"寡淡"诊断**（那是 libraw 自动提亮造成的测量伪结论）。共发 v0.5.43–v0.5.47 五版（versionCode 74–78），全部真机验证并推送。

## Done

- v0.5.43-m3 **P0 LSC**：`kFsRaw` / `kFsRcdChroma` 在 CCM 前乘 HAL 增益网格（17×13 折 RGB、手写 4-tap 双线性、关闭时 1×1 恒等零分支）；真机均匀白柜面角落/中心 **0.61–0.67 → 0.90–0.99**、中心增益 1.00
- v0.5.44-m3 **实时直方图**：`kFsHist` 下采样 256×96、**与 `kFsOut` 同 OETF**（显示域）、24KB 读回 + 256 桶；UI ~7fps 轮询 + Canvas（峰值开方压缩）
- v0.5.44-m3 **色彩外观层（P1）**：`kFsFilm` 插入"亮度保持饱和增益 + 色度 S 曲线"，`FilmParams[31..33]`；v0.5.47 参数回中性占位
- v0.5.45-m3 **标定矩阵探针**：`raw.calib` 实测 X300 在 CameraCharacteristics 给全 ColorMatrix1/2、ForwardMatrix1/2、CameraCalibration（单位阵）、illuminant 21/17；`raw.meta ccm` 补全 9 元素
- v0.5.46-m3 **DNG 标准色彩链**：`app/.../camera/StdColorMatrix.kt` 自建 camera(白平衡后)→线性 sRGB（双光源插值 + Bradford D50→D65）；A/B 开关 `ccm_std`（默认开）、缺失回退 HAL CCM；单测 `StdColorMatrixTest`
- v0.5.47-m3 外观层回中性 + 出包命名改 `filmkit-<versionName>.apk`
- 工具：`tools/check-lsc-mapping.py`、`tools/dng-color-dump.py`、`tools/color-metrics.py`（新增 CIELAB C*）
- 提交 **ca3e9aa**（code）/ **64646df**（tools）/ **c39467c**（docs），已 push

## Decisions

- [2026-09-21] **LSC 接在去马赛克之后、CCM 之前**：网格低频（单元≈240px），与 mosaic 域逐像素应用差异 <1%，却省掉 5 个 shader 的改动；采样手写 4-tap（X300 float 纹理过滤不可依赖）
- [2026-09-21] **色彩外观层参数回中性 = 占位**：真机复核证明"寡淡"是测量口径造成的伪结论；浓淡属风格，按 §0-D8/D9 交给 M4 的理光系 LUT（开关与通路保留）
- [2026-09-21] **P2 = 用 ColorMatrix/ForwardMatrix 自建标准链**：数据在 `CameraCharacteristics`（DNG 里没有只是 DngCreator 不写）；默认开、可 A/B、缺失回退 HAL
- [2026-09-21] **出包命名改 `filmkit-<versionName>.apk`，新包覆盖旧包**（更新 2026-09-18「单文件覆盖制」条的命名部分）
- [2026-09-21] **P1 归属修正**：先按用户要求放 M3 做，复核后确认前提不成立 → 实质归 M4（LUT 引擎）

## Pitfalls

- **rawpy 默认 `auto_bright=on`** 会把 libraw 输出亮度自动拉高约 2.5×（37.6→94.4）：v0.5.42 的"饱和 −21% / 分离 −29%"是**亮度差被当成色彩差**。测色度首选 **CIELAB C\***（与亮度解耦）；`sep`/`neutral` 是绝对量，跨图比较必须同亮度，而"把暗方拉亮"本身又会抬高其 C*
- **"文件里没有" ≠ "API 拿不到"**：DNG 无 ForwardMatrix 是 DngCreator 不写，characteristics 里齐全 —— 从产物反推 API 会得出错误结论
- **Camera2 矩阵键只在 `CameraCharacteristics`**（不在 `CaptureResult`）；`SENSOR_REFERENCE_ILLUMINANT2` 类型是 **Byte** —— 凭记忆写必错，javap 一查就清楚
- **Kotlin 字符串里别用英文双引号**（会截断字符串报 `Unresolved reference`），设置页文案同理
- **离线参照必须与实现同式**：Python 参照"先插值矩阵" vs Kotlin"先算各光源相机白点"，差异被单测抓出（0.0023 > 容差）
- **Gradle 需写工作区外的 `~/.gradle`**：沙箱 workspace-write 下直接失败，需更宽权限

## Next

1. 打 tag `v0.5.47-m3` 并 push（本窗口已提交 3 个 commit，tag 待补）
2. **M4 LUT 引擎**（域感知插桩、.cube/Hald 导入导出、融合烘焙、预设包）—— 注意 `luts/` 已有 6 个 .cube（理光负片/正片、富士CC/NC、徕卡M9/黑白，**未入库**），涉及商标/版权（§10 风险表要求命名中性化），入库与命名需用户拍板
3. 真机项：Lightroom 开 DNG、色温灰卡精校（WB_CAL_R/B）、MF 滑杆手感复验
4. 若仍要"更浓的画面"：走 M4 的 LUT（D9），不要回基准层加饱和

## Refs

- tags v0.5.43-m3 … v0.5.47-m3（versionCode 74–78）；包 `filmkit-0.5.47-m3.apk`
- commits：ca3e9aa / 64646df / c39467c
- `docs/开发计划.md`：v0.5.43 LSC 节、v0.5.44 节（含复核）、v0.5.45 探针节、v0.5.46 标准链节、调研结论订正
- `tools/`：check-lsc-mapping.py、dng-color-dump.py、color-metrics.py
- 真机样张：174940/943（LSC 台灯）、184019/028（LSC 均匀白柜）、191500 + 191459.dng（色彩）、192613/622（std A/B）、193703/709（base_tone A/B）
- `luts/`（未入库）：理光负片/正片、富士CC/NC、徕卡M9/黑白
