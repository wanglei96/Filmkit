---
summary: 品红修复与HDR增益归属
created: 2026-10-30 15:55
updated: 2026-10-30 15:55
status: active
---

# 品红修复与HDR增益归属

## Summary

2026-10-30 全天（0.12.193→207，共 15 个包）：追了三条线。①**品红/绿边修掉了**——根因不是门控，是短帧真值没进高光（0.12.129 删注入 + inpaint 默认关）；打开并扩到"部分饱和整块接管"后消失。②**叶子高光"糊一层"**定位到 teq 高光压深（AE 推 +0.85EV、P95 超白点 1.11、teq 又压 −1.0EV），关掉即好。③**HDR 增益**反复了 7 个包：锚定随 ae_p50 漂移导致 10-27 与 10-30 同场景差 4 倍；一度按"原版只提亮高光"改到 205 被用户否决，最终回退 201 的整体提亮并**如实标注它是观感选择、不是原版语义**。

## Done

- **[2026-10-30] satmHit=0 是假象（0.12.193→194）**：`jnum(v, n)` 第二参是**字符预算**，`jnum(x, 0)` ⇒ `resize(0)` ⇒ 永远输出 `"0"`。`satmHit`/`satmCen`/`satmMax`/`satmW` 从 0.12.192 起全是假零。铁证 = 同一函数里 `satmCov` 是 `jnum(..., 2)`，实测 4（=4.xx 截断）而 hit 显示 0，逻辑上不可能并存。
- **[2026-10-30] 掩码一直在工作**：修好截断后实测 `satmHit` 1649 / 122113 / 269956，`satmCov` 0.02%~4.48% —— 与离线复算 4.23% 吻合。"三处消费者等于没接"的旧结论撤回。
- **[2026-10-30] sat mask 判据改传感器域（0.12.194）**：旧式 `wb >= gain − 3σ` 是自创，σ→0 时退化成 `v ≥ 1.0`（比任何真实峰值严），σ 大时随 ISO 漂到 0.9 以下过判。改为 `raw >= ceil(black + (white−black)×250/255)`、逐相位、整数比较（BENTO_MAX_RGB_CLIPPING 口径）。
- **[2026-10-30] 品红修掉（0.12.195→197）**：打开 `kHlInpaintOn`，并把"只有三通道全饱和才填"扩到"短帧在本 2×2 块四相位都有效 ⇒ 整块用短帧"（长帧部分饱和也覆盖）。0.12.196 漏乘通道 gain 导致整圈绿边（R/B 被除 2.1/1.46），197 修复后品红与绿边同时消失。
- **[2026-10-30] 叶子"糊一层"（0.12.198）**：真机日志 `aeEv +0.847 / aeP95 1.1148 / teqHl −1.0045` —— AE 为把 P50 0.056 提到 0.12 推了近 1EV，高光越界 1.11EV，teq 又压回 −1.0EV，层次被压塌。关闭 teq 高光压深后用户确认"叶子正常了"。
- **[2026-10-30] HDR 增益漂移定位（0.12.199→200）**：`gainTrue` 的锚定 `0.1845/ae_p50` 让 HDR 强度随曝光跑——同机同场景 10-27（ae_p50 小）`boost p90 4.00×`，10-30 楼景（0.2304）`1.49×`。203 前一路改到"固定常数 2.56"才恢复亮度。
- **[2026-10-30] Ultra HDR 语义修正（0.12.201）**：`kMin=0.25` 而 `gainTrue` 把 ratio clamp 到 `[1.0, 4.0]`，编码只用了元数据声明范围的一半；下限改回 `kMin` 后 `[0.25,4]` 用满。
- **[2026-10-30] 202-205 走错一段（已回退）**：按"暗部/中灰 ratio=1、只延展 shoulder 以上"把 gainmap 改成 `sdr + max(0, scene − sceneWhite)`。用户否决：观感偏好整体提亮。0.12.206 回退 201，0.12.207 保留行为并把注释改成"**整体提亮是观感选择，不是原版语义**"。
- **[2026-10-30] AMaZE 饱和度查证**：`sat_comp` 写 FilmParams[20]/[21]，由 `kFsFilm` 的 `uSatShadow/uSatMid` 消费，位置在 film pass（解马赛克之后）⇒ **两档都吃**。RCD 更饱和是解马赛克本身的色度重建差异（RCD 三段 Chroma，AMaZE 由 CPU 整帧输出后 `passAmazeUp` 旋转上传）。
- **[2026-10-30] 徕卡预设标定边界**：`tools/preset-exp/recipe-fit.py` 头部明写"**未验证的段**：RAW 解码 / CCM / 白平衡 / `kFsFilm` 的 sat·split·mono（线性域三段）"，工具**从 filmic 之后接起** ⇒ 预设不是在"补完基础饱和度"之后标定的。

## Decisions

- **HDR 整体提亮是观感选择，不是规范语义**：ISO 21496-1 / Adobe hdrgm 的定义是暗部与中灰 ratio 恒 1、只在 SDR shoulder 之上延展（scene white 约 +0.5 stop）。我们有意偏离（固定 2.56 锚定，整张提亮 2.4~3.3 倍），因为真机对比确认这是要的观感。代码注释已如实标注，引用时不得当原版语义。
- **饱和判定一律在传感器域做**：`raw >= ceil(black + (white−black)×0.98)`，逐相位、整数比较，不含 WB 增益与噪声项。
- **短帧真值按 2×2 块整块接管**：只要短帧在该块四相位都未饱和，整块用短帧（保真实色比）；只有短帧也饱和时才走标量亮度 + 扩散。禁止"块内混帧"（G 用短帧、R/B 用长帧 ⇒ 色比撕裂）。
- **诊断字段的格式化参数必须核对语义**：`jnum` 第二参是字符数；任何 `jnum(x, 0)` 都是 bug。

## Pitfalls

- **`jnum(x, 0)` 永远返回 `"0"`**：`std::to_string` 后 `resize(0)` 再回落。`satmHit=0` 因此骗了整整一轮（含"判据零命中"的错误结论）。
- **`raw.render` 的 `gm*` 恒 0 是设计**：`readGainmap` 在日志之后才跑（`FrameRenderer.cpp:12960` 注释写明），真值在独立的 `gainmap.readback` 事件。
- **照注释推断外部实现会错**：先按"shoulder 连续延展"的注释把 gainmap 改成只提亮高光，读实现（`RawHdrReferenceMath.evaluate`：`if (x <= joinInput) return sampleCurve(baseCurve, x)`）才确认语义，但用户观感仍是整体提亮——结论：**观感以样片为准，语义以源码为准，两者可以不一致**。
- **`(void)mid;` 不是 GLSL**：Mali 宽容编译通过、glslang 直接报 `explicit typecast required extension`；该行在 0.12.199~205 的 `kFsGainmap` 里潜伏，出包时没跑 shader 校验才漏过。
- **改了 shader 里的常量必须重跑 `validate-shaders.sh`**：`constexpr` 在 GLSL 里不存在（写成 `const`）。
- **teq 全关的代价**：高动态场景（`aeP95 2.5`）SDR base 直接死白，gainmap 无可补；但那类场景压深也救不回层次，只能靠 gainmap 端表达。
- **同一槽位两个写入者**：`[20]/[21]` 被 `sat_comp`（`CameraController.kt:2048`）与用户"饱和度"滑杆（`EditorExport.kt:471`，写三槽）共用，后者覆盖前者。

## Next

1. **205-hdr 共存包（`com.filmkit.hdr205`）测完决定去留**：它是"只提亮高光"那版，供观感对照。
2. **AMaZE 色度补偿**（可选）：若要两档饱和一致，给 AMaZE 档一个 [20]/[21] 系数或等效色度补偿 pass。
3. **徕卡预设是否把 sat 段纳入标定**：当前 `recipe-fit` 从 filmic 之后接起，sat/split/mono 未验证。
4. **灰斑本体复验**：inpaint 打开后粉蘑菇/空调管是否改善（本轮只修了品红与绿边）。

## Refs

- 包：`filmkit-0.12.207-LIFT.apk`（658，当前）/ `filmkit-0.12.205-hdr-coexist.apk`（共存对照）
- 日志：`filmkit-engine-20261030(3).log`（全天，含 202 前后对比）、`103606`（叶子糊）、`143125`（HDR 弱）
- 关键代码：`FrameRenderer.cpp` 的 `passSatMask` / `kFsHlInpaint` / `gainTrue`；`CameraController.kt:2048`；`EditorExport.kt:471`；`tools/preset-exp/recipe-fit.py`
- 提交：`3d2e68b`(201) `5b9c60e`(202) `010e0c6`(203) `0bb7a52`(206) `35d25db`(207)
