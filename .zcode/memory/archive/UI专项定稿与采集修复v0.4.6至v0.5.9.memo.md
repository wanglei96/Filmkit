---
summary: UI专项定稿与采集修复v0.4.6至v0.5.9
created: 2026-09-19 22:30
updated: 2026-09-20 10:50
status: archived
---

# UI专项定稿与采集修复v0.4.6至v0.5.9

## Summary

本窗口完成相机 UI 专项全流程（三稿定方向 C → CameraScreen 四轮反馈迭代 + 一轮主动审计）与采集侧连续修复（WB/变焦/画幅/对焦/EV/闪光灯/触感动效），发布 v0.4.6–v0.5.9 共 14 个版本并全部推送 GitHub（tag 至 v0.5.9-m2）。手动白平衡最终拍板架构：HAL 恒 AWB AUTO + 引擎侧增益补偿（v0.5.9），待真机验证。前两份快照（M1/M2 前半、对焦修复）内容已全部吸收进分层文件。

## Done

- UI 专项（v0.4.6–0.4.9）：三版布局稿用户选 C（Photon 骨架+DAZZ 质感）；CameraScreen 重构（面板去重单实例、焦段条固定、沉浸化、FilmTheme 色板）；控制钮改实时读数瓦片 EV/Tv/ISO/AF/WB；✦LUT 二级菜单；导航去全局 Tab（设置/镜头/系统颜色/日志/Spike 子页+返回键）；A/手动参数模型（EV、AE=Tv+ISO 联动、WB 独立，默认 A）；TickSlider 自适应刻度；强调色 Accent 可配置（8 色板持久化）
- 快捷与触感（v0.5.0–0.5.1）：顶部快捷区（闪光灯三档/直方图占位/快门声/菜单☰）；音量键拍照；捏合变焦接 setZoomEquiv；三分构图线；最近样张缩略图；快门触感+黑闪+脉冲；pressScale 全局按压反馈；FilmMotion 统一浮层动效
- 采集修复（v0.5.2–0.5.5）：MF 屈光度滑杆（AF 面板 A=连续）；setZoomEquiv 无缝跨镜头变焦；画幅=取景窗口（预览 center-crop 四象限公式修正）；预览拉伸根因=STMatrix 换轴未计入
- 收尾迭代（v0.5.6–0.5.9）：画幅菜单锚定屏幕全宽；崩溃捕获（crash-latest.txt + app.crash）；倍率基线改主摄（70mm≈2.9x/15mm≈0.6x）；自定义焦段子页（≤3 个，总显示 ≤6）；EV 滑杆自适应 HAL 实际范围；手动 WB 重构为引擎侧补偿
- docs/开发计划.md：新增「UI 设计规范」（色板/字体/导航/浮层/交互反馈/A-手动模型）+「主动审计」工作约定 + v0.4.6–v0.5.9 全部进度节

## Decisions

- [2026-09-19] **手动 WB = HAL 恒 AWB AUTO + 引擎侧补偿**——补偿=wbGains(目标K)/wbGains(自动估计K)，改写 filmParams wb 位（预览/成片同链路）；HAL 三路径（CCT 直填被忽略、GAINS 结果键 X300 不回填、变换矩阵行列缩放观感不可控）全部证伪后拍板，不再回头
- [2026-09-19] **主动审计工作约定**——每次 UI 改动必须先按规范+交互清单自查并列清单与用户确认，动效/触感/按压反馈为默认检查项（用户明确要求，不再等批注）
- [2026-09-19] 变焦倍率基线 = 主摄等效焦距（mainEquiv 取启用镜头中最接近 24mm 者）：70mm≈2.9x、15mm≈0.6x
- [2026-09-19] 导航无全局 Tab：相机沉浸主屏；设置二级菜单化（镜头配置/系统颜色/自定义焦段/日志/Spike 为子页）
- [2026-09-19] 参数 A/手动模型：EV、AE(Tv+ISO 联动，Camera2 AE OFF 同时锁两参)、WB 三组独立，默认 A，拖刻度进手动、点 A 回自动（260ms 线性过渡）
- [2026-09-19] 自定义焦段 ≤3 个（20–120mm），与原生焦段合并去重后总显示 ≤6；自定义点按=setZoomEquiv、原生=setFocal
- [2026-09-19] 强调色 FilmTheme.Accent 全局动态（prefs accent_argb），FilmMotion 统一浮层动效 token

## Pitfalls

- [2026-09-19] X300 HAL 手动 WB 三路径全不可用：CCT 直填被忽略；GAINS/COLOR_TEMPERATURE 结果键不回填（GAINS 作为结果键 API36 仍在，但 X300 不给值）；直接下发变换矩阵（对角/行/列缩放都试过）观感不可控——解法是引擎侧补偿，勿再回头调 HAL
- [2026-09-19] 预览 center-crop 的 sx/sy 分配依赖**着色器自转奇偶**（四象限映射），且 X300 把 sensor 90° 藏在 STMatrix（|m[4]|>0.5 换轴）——只看 rotation 参数必错；4:3 时 a==srcAspect 会掩盖 bug，务必多画幅验证
- [2026-09-19] 跨镜头变焦后 focalBase 慢半拍更新 → 倍率显示虚高；读数处须即时同步 currentLensEquiv()
- [2026-09-19] ColorSpaceTransform：构造收 Rational[]（行主序）、读取用 getElement(col,row)，没有数组下标/getColor
- [2026-09-19] Box 内调 AnimatedVisibility 会被 ColumnScope 扩展遮蔽 → 全限定 androidx.compose.animation.AnimatedVisibility
- [2026-09-19] XPAN 切换闪退无 Java 栈：已装未捕获异常处理器（files/logs/crash-latest.txt + JSONL app.crash）后再复现即有栈；v0.5.6 起未再复现
- [2026-09-19] 快门黑闪/脉冲等 Animatable 用 captureTick 驱动；TickSlider 非拖动变化走 260ms LinearEasing、拖动 snap

## Next

- 真机验证 v0.5.9：WB 拖动应平滑偏暖/偏冷且拉到自动数字处与自动观感一致、倍率 2.9x/0.6x、自定义焦段条、EV 范围与 session_ready 日志 evRange 核对
- 色温估计校准复核：矩阵对角线兜底系数（WB_CAL_R=0.923/WB_CAL_B=1.238）系由单点推得，M3 灰卡+标准光源精校；GAINS 结果键其他机型可能回填（已优先读取）
- WB 补偿只影响 JPEG 成片与预览；DNG 底片不带补偿——M2-3 RAW 主线接入时明确口径
- 回归 M2-3 RAW 主线：RAW_SENSOR 4080×3072 + DngCreator 存 DNG（读设置页 dng_enabled），引擎 submitRaw

## Refs

- docs/开发计划.md：「UI 设计规范」章节（含主动审计约定）+ v0.4.6–v0.5.9 全部进度节
- UI token：app/src/main/java/com/filmkit/app/ui/FilmTheme.kt（色板/字体/FilmMotion）
- handoffs/M1完成M2镜头与专业UI攻坚.memo.md（absorbed，M0–M2 前半与引擎/采集坑全集）
- handoffs/修复对焦黑屏与出包约定.memo.md（absorbed，v0.4.4/0.4.5 对焦修复全集）
- GitHub git@github.com:wanglei96/Filmkit.git（main，tag v0.5.9-m2 = versionCode 40）；根目录 filmkit.apk 覆盖制
- 用户回传日志：~/Downloads/vivo办公套件/filmkit-logs-*.jsonl.txt（本窗口 4 份）+ crash-latest.txt（若复现闪退）
