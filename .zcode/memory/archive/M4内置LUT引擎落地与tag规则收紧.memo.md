---
summary: M4内置LUT引擎落地与tag规则收紧
created: 2026-09-22 10:15
updated: 2026-09-22 10:15
status: archived
---

# M4内置LUT引擎落地与tag规则收紧

## Summary

M4-1 完成：6 个用户自备 .cube 接成**相机内置 LUT**（原版名、未中性化），全链路走通（资产入库 → 纯 Kotlin 解析 → 引擎 display 域四面体插桩 → 强度滑杆 → 卡片预览）；期间修掉一个 sampler 绑定导致的**全黑回归**和一个 ExifInterface **静默丢弃曝光时间**的旧缺陷，并把 LUT/预设选择页重构为底部弹出。另定下「每轮对话只打一个 tag」规则、清理 56 个中间 tag，并用 filter-repo 从历史移除误提交的 3 张截图（仓库 25M→13M）。

## Done

- **EXIF 曝光时间修复（v0.5.48-m3）**：21 张成片全缺 `ExposureTime`(33434) —— `ExifInterface` 1.3.7 的兼容标签集合含 ExposureTime，`setAttribute` 内部走 `Double.parseDouble`，旧代码传分数串 `"1/125"`（`liveExposureNs=0` 时更退化成 `"1/2147483647"`）→ `NumberFormatException` → `Log.w` + 静默 return。改为传**十进制秒数** `"%.6f"`（Locale.US），取值 `liveExposureNs>0 ? : exposureNs`，两者皆无则跳过写入
- **M4-1 内置 LUT 引擎（v0.6.0-m4 → v0.6.8-m4）**：
  - 资产 `app/src/main/assets/luts/`（6×33³，ASCII 文件名；**显示名取 .cube 的 TITLE**＝原版名，用户拍板暂不中性化；aapt 压缩后约 1.9MB）
  - 解析 `lut/CubeLut.kt`（纯 Kotlin、手写扫描取值；畸形输入抛异常，由 `LutLibrary` 记 `lut.parse_failed` 并回退「无 LUT」，不静默降级）+ 单测 10 例（含 6 个真实资产端到端、黑白 LUT 中性灰）
  - 引擎：`setLut()` 任意线程暂存 → 渲染线程上传 **RGBA16F GL_TEXTURE_3D**；shader 内手写 **tetrahedral** 六例插值，**插在输出 OETF 之后**（display 域，§0-D10）；`kFsHist` 同源走 LUT；强度 `FilmParams[34]`
  - 接线：`nativeEngineSetLut` → `RenderClient.setLut` → `CameraController.setLut`（**会话重建后 start() 自动重传**，切镜头会释放引擎纹理）
  - UI：LUT 强度滑杆（0–100%），接入既有「偏好覆盖」路径（预览每帧 / YUV 静拍 / RAW 静拍三处统一回写数组，无需重建相机请求）
- **选择页重构（v0.6.4–v0.6.6）**：整机层**底部弹出**面板（拦截层覆盖全屏＝点外部任意处收起）+ LUT/预设分类 pill + 横向卡片；卡片预览＝**固定测试图（CC0 照片，随包） × 该 LUT 一次性生成并缓存**（`LutPreview`，320px，纯 CPU 三线性，运行时零渲染）—— 内置与将来导入的 LUT **共用同一路径**
- **焦段条 UI（v0.6.7–v0.6.8）**：✦ LUT 入口不参与横滚（不再被长焦焦段顶出屏幕）；焦段/倍率 chip 10sp；**只显示一种信息**（悬浮菜单「焦段条显示」切换：焦段号 or 倍率）；整行居中且 ✦ 紧邻（`weight(1f, fill=false)`）；悬浮菜单改 **3 列**开关格（`QuickTile`，底色表达状态）
- **仓库治理**：`docs/开发计划.md` 新增 v0.5.48–v0.6.8 各节 + 「版本与 tag 约定」；删掉仓库根 `luts/` 原件（内容已入 assets）；新增 `tools/gen-lut-previews.py`（生成测试图 / `--input` 换图 / `--dump` 离线审查）

## Decisions

- [2026-09-22] **每轮对话只打一个 tag**（指向该轮验收版），中间验证包只出 APK 不打 tag；出包与打 tag 解耦 —— 本轮清理掉 56 个中间 tag，只留 v0.5.27-m2 / v0.5.47-m3 / v0.5.48-m3 / v0.6.8-m4；完整清单（含 commit / 日期 / 标题 + 恢复命令）在 `docs/git-tags-backup-20260922.txt`
- [2026-09-22] **内置 LUT 用原版名、暂不中性化** —— 更新 §0-D9 的"命名中性化待定"；资产文件名用 ASCII（避路径编码风险），显示名取 TITLE
- [2026-09-22] **LUT 插桩＝display 域、输出 OETF 之后**（放在 `kFsOut` 内，同一 pass、不额外加 pass）；`kFsHist` **必须同源**，否则选了 LUT 后直方图/斑马纹与画面不符
- [2026-09-22] **LUT 卡片预览＝固定测试图 × 一次性生成并缓存**（编译期或首次运行/导入时），**不做实时缩略图**（每次开面板 N 次渲染 + GPU 读回，费电）
- [2026-09-22] **误提交大文件用 `git filter-repo --invert-paths` 重写历史 + force push**；**重写前必须先备份工作区副本**（filter-repo 的 `reset --hard` 会删掉被移除路径的工作区文件）

## Pitfalls

- **sampler3D 未绑定 = 整帧丢弃（v0.6.0-m4 全黑）**：`kFsOut` 新增 sampler3D 后，关闭态 `bindLut()` 提前 return、从不绑定纹理，纹理单元 1 残留上一个 pass 的 **2D 纹理**；Mali **预测执行**分支里的 `texelFetch` → draw 报 `GL_INVALID_OPERATION(1282)`、输出全黑。诊断线索：`render.diag` 里 pass1/pass2 正常而 **pass3_out glErr=1282 且 r=g=b=0**，且**没有** shader 编译/链接错误。修法＝init() 建 **1×1×1 恒等 3D 纹理** + 无论开关都绑定并下发全部 uniform（与 LSC「关闭档绑 1×1 全 1 网格」同类坑）
- **ExifInterface 兼容标签只吃十进制**：`{FNumber, DigitalZoomRatio, ExposureTime, SubjectDistance, GPSTimeStamp}` 走 `Double.parseDouble`，分数串被静默丢弃（`Log.w` + return，无异常外露）
- **Kotlin 块注释支持嵌套**：KDoc 里写 `assets/luts/*.cube` 时那个 `/*` 会开启嵌套注释、把整个文件吞掉（报 `Unclosed comment`，且指向文件末尾而非真凶行）
- **卡片预览 108px 在 3–3.5x 密度下发糊**：卡片 76dp 需 228–266px → 提到 320px，且缓存文件名带尺寸（`<key>@320.png`），改分辨率时旧缓存自动失效
- **filter-repo 会删掉工作区副本**（本轮 3 张截图因此不可恢复；GitHub 也不允许按任意 SHA fetch 旧对象）→ 重写前先复制到 /tmp

## Next

1. **色彩专项（用户明确要开新窗口做）** —— 现 LUT 效果**不满意**。建议起手三件事：① 用 6 套 LUT 数据核对**域假设**（display vs scene-linear，错域是"效果不对"的头号根因）；② **强度混合曲线**（线性 mix vs 感知均匀）；③ **基准链与 LUT 的职责边界**（§0-D8/D9：基准只做中性正确，浓淡全归 LUT）
2. **噪点问题待收口**：用户反馈开 LUT 后"彩噪/亮噪明显增加"；离线算得 LUT 放大率仅 **0.67–1.30×**（理光负片暗部色度增益 1.30 最大），不足以独立解释；**缺同场景关 LUT 的 A/B**（可用强度滑杆拖 0% vs 100% 背靠背拍）→ 用户说"噪点先不动"
3. **M4 后续**：.cube / Hald **导入导出**、链式融合烘焙 65³、预设包（`.filmkit` zip）、胶片模型烘焙导出
4. 真机项（M3 遗留）：桌面 Lightroom 开 DNG、色温灰卡精校、MF 滑杆手感复验
5. 历史重写后的注意：其他 clone 需重新 clone；GitHub 未引用的旧对象待其 gc

## Refs

- 版本：v0.5.48-m3（EXIF）→ v0.6.0…v0.6.8-m4（M4-1 全链路）；保留 tag 4 个（v0.5.27-m2 / v0.5.47-m3 / v0.5.48-m3 / v0.6.8-m4）；包 `filmkit-0.6.8-m4.apk`（versionCode 88）
- commits：`efa0ead`（HEAD，备份 hash 重映射）、`e0d298b`（tag 规则）、`49f68f2`（焦段条/3 列菜单）、`4b61837`（v0.6.8 代码）、`175e333`（EXIF 修复）
- 代码：`app/src/main/java/com/filmkit/app/lut/{CubeLut,LutLibrary,LutPreview}.kt`、`app/src/test/java/com/filmkit/app/lut/CubeLutParserTest.kt`、`core/engine/src/render/FrameRenderer.{h,cpp}`、`core/engine/src/jni/Bridge.cpp`、`app/src/main/assets/luts/`、`app/src/main/assets/lut-preview-src.png`（+ `.LICENSE.txt`）、`tools/gen-lut-previews.py`
- 文档：`docs/开发计划.md`（v0.5.48 节、M4-1 节、全黑热修节、预览图节、清晰度节、焦段条两节、版本与 tag 约定）、`docs/git-tags-backup-20260922.txt`
- 日志事件：`lut.parse` / `lut.load`（state/size/ms/err）/ `lut.preview`（key/ms/cached）/ `settings.lut` / `settings.lut_in_name` / `quick.focal_strip`
- prefs 新增：`lut_id`、`lut_strength`、`lut_in_name`（默认开）、`focal_strip`（默认 focal）
