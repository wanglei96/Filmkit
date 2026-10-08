# 内建编辑器 · 真机验证清单（E1 阶段）

> 架构师维护。**每出一版包，按本清单从上往下跑一遍**，把结果回填。
> 本机无 adb 设备，以下各项均由用户在 X300 上执行。
> 日志基线：`logs/` 与 `audit/` 在 `/storage/emulated/0/Android/data/com.filmkit.app/files/`，
> 经 vivo 办公套件回传后落 `~/Downloads/vivo办公套件/`。分析前先跑 `tools/check-log-json.py`。

## 0. 装包前的两道自检（出包方执行，用户可跳过）

```bash
cd /Users/wl/Downloads/胶片相机
JAVA_HOME=/opt/homebrew/opt/openjdk@23 ./gradlew assembleDebug testDebugUnitTest
bash tools/validate-shaders.sh
python3 tools/check-diag-fields.py
python3 tools/check-style-passthrough.py
# 包内版本号必须核对（历史事故：revert 连带回退 build.gradle，静默打出旧版本号）
~/Library/Android/sdk/build-tools/*/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionName
```

安装：`~/Library/Android/sdk/platform-tools/adb install -r <apk>`
（无 adb 时手动拷贝 APK 到手机安装）

---

## 1. 编辑器入口（E1-1 + E1-5）

| 步骤 | 期望 | 失败表现 |
|---|---|---|
| 相机页 → 左下角**胶卷位**点击 | 进入全屏编辑器：顶部标题「编辑」、中间显示成片、底部一行「文件名 · 宽 × 高」+「成片编辑 · 仅后段风格化（LUT / 颗粒 / 暗角），不改曝光与白平衡」 | 无反应 = `clickable(enabled = photoUri != null)` 没拿到 URI |
| **杀掉进程重开** → 看胶卷位 | 缩略图**仍在**（不是占位符 ▦） | 占位符 = `last_photo_uri` 恢复失败（E1-5） |
| 点返回（顶部箭头 / 系统返回） | 回到相机页，**预览正常恢复**（不出黑屏/卡住） | 预览黑屏 = 编辑器持有渲染资源没释放 |

---

## 2. 参数快照（E1-2）

拍一张带 LUT 的成片，然后看日志：

```bash
grep -E "photo.saved|snapshot.save_failed|snapshot.corrupt" <日志文件>
```

| 期望 | 说明 |
|---|---|
| 有 `photo.saved`，**没有** `snapshot.save_failed` | 快照写入必须静默成功 |
| 若出现 `snapshot.corrupt` | 说明 prefs 里的旧 JSON 坏了（会自动重建，但要告知架构师） |

---

## 3. 渲染通路（E1-3）★ 本次核心

**步骤**：打开编辑器 → **不选 LUT**（强度 0）→ 导出/保存 → 把导出图与原图对比。

```bash
grep -E "render.style|render.display_done" <日志文件>
```

| 字段 | 期望值 | 含义 |
|---|---|---|
| `styleOk` | `true` | `prog_style_` 链接成功（**恒应为 true** —— 它进了 init 硬失败列表，为 false 时引擎起不来） |
| `styleOn` | `true` | 本帧真的执行了（不是静默跳过） |
| `lutK` | `0.000000` | 下发的 LUT 强度 |
| `grain` / `grainSz` | `0.0` / `400.0` | 颗粒关闭（ADR-10 的回归红线） |
| `render.display_done` 的 `ok` | `true` | 读回成功 |

**画面核对（最重要）**：
- LUT 关 + 颗粒 0 ⇒ 导出图与原图应**肉眼无差**（离线已证 max|Δ| = 1 LSB）
- **绝不允许变暗/变灰** —— 那是双重影调映射（跑了 `passFilm`）的典型症状，是本次头号风险
- **绝不允许上下颠倒** —— 离线行序哨兵已验，真机再确认一次

---

## 4. 拍照链路回归（每版必做）

| 步骤 | 期望 |
|---|---|
| 正常拍 3 张（含逆光 1 张、暗光 1 张，暗光会触发 MFNR） | 成片观感与上一版**无差异** |
| 看日志 `raw.render.*` / `render.still_done` | 耗时无异常抬升 |
| 日志里**不应**出现 `render.style` | 拍照走 `kFsOut`，`kFsStyle` 只服务编辑器 |

> 为什么必须做：这一版动了 `FilmParams` 契约（60→78）。新槽位默认值全部等于"无效果"，
> 但"默认无害"必须由真机确认，不能只靠代码推理。

---

## 5. 待验证项（需要 UI 入口，等 E2）

以下效果**代码已就位但用户点不到**（还没有参数面板），所以本版无法验证，等 E2 参数面板落地后一并验：

| 项 | 参数 | 默认 | 验证方式（E2 后） |
|---|---|---|---|
| 暗角（E4-2） | `[66]` strength / `[67]` radius / `[68]` feather | `0 / 80 / 50` | 拉滑杆：中心不动、四角变暗、渐变平滑**无同心圆环**（banding） |
| 颗粒（E4-3，未实现） | `[26][27][60][61]` | 关 | 开颗粒后导出两次，**颗粒图案必须一致**（种子已固定） |
| 趾肩（E4-4，未实现） | `[5..10]` | `0.01` | 改 latitude 看曲线两端变化 |
| halation（E4-5，未实现） | `[62..65]` | 关 | 逆光/夜景看红橙光晕 |

---

## 6. 出问题时的排查路径

| 症状 | 先查 | 大概率原因 |
|---|---|---|
| 编辑器点不开 | prefs `last_photo_uri` 是否有值（日志搜 `snapshot`） | URI 恢复失败 / 缩略图加载失败 |
| 编辑器白屏 / 「图片已失效」 | URI 是否还有效（照片被删？） | 素材失效（已有失败态，不崩） |
| 导出图**变暗变灰** | 日志有无 `passFilm` 相关耗时 | 双重影调映射（R1）—— 立即回报 |
| 导出图**上下颠倒** | `render.style` 的 `h`/`w` | 行序问题（离线已验，真机复现则回报） |
| 应用**启动即崩** | `logs/crash-latest.txt` | 参数契约长度不同步（v0.10.196 同型）—— 立即回报 |
| 拍照变慢 | `raw.render.passMs` / `render.still_done.ms` | 新参数是否被意外启用（应全为默认无效果） |

---

## 7. 回填模板（跑完请照此回报）

```
包版本：
1. 入口（胶卷位/冷启动/返回）：通过 / 失败（现象）
2. 快照：通过 / 失败（日志行）
3. 渲染通路：通过 / 失败（render.style 原字段 + 画面观感）
4. 拍照回归：通过 / 失败（对比说明）
异常日志：
```
