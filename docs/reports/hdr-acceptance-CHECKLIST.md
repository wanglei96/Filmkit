# HDR 真机验证操作清单（v0.11.53）

包：`filmkit-0.11.53-HDR.apk`（arm64-v8a，46MB，`FILMKIT_HDR=ON`，release + debug 签名）

---

## 0. 安装与准备（1 分钟）

```bash
adb install -r filmkit-0.11.53-HDR.apk
```

装好后**先清一次旧日志**（避免和上版混在一起）：

```bash
adb shell rm -rf /sdcard/Android/data/com.filmkit.app/files/logs
```

然后打开 App（日志目录会自动重建）。

---

## 1. 打开开关（设置页）

进 **设置 → 相机**，找到：

- **「HDR 成片（Ultra HDR / gainmap）」** → **打开**
  - subtitle 会显示当前能力，例如 `4:4:4 ✓ · 4:2:0 ✓ · HEIC ✓`
  - 如果显示 `4:4:4 ✗`，说明这个包没编进 HDR 能力 —— 停下告诉我

- **「成片 4:4:4 色度」** → 保持**打开**（默认）

> 相册 HDR 显示是默认开的（`hdr_screen`），不用单独设。

---

## 2. 拍摄（每组 2 张，固定机位）

### 场景选择（重要）

**必须有高光**，否则 gainmap 无内容（HDR 看不出差别）：

- ✅ 窗边（窗外过曝、室内暗）
- ✅ 台灯 / 灯泡入画
- ✅ 逆光（太阳在画面里）
- ❌ 均匀光线的白墙（gainmap 全 0，会走普通 JPEG）

### 2A. JPEG 4:4:4 + HDR（主推路径）

1. 照片格式 = **JPEG**
2. 「成片 4:4:4 色度」= **开**
3. 拍 2 张（1 张 4:3，1 张 16:9 用于验裁剪）

**预期日志**：`photo.encode` 里 `"hdr":"jpegR444"`

### 2B. JPEG 4:2:0 + HDR

1. 「成片 4:4:4 色度」= **关**
2. 拍 2 张

**预期日志**：`photo.encode` 里 `"hdr":"platform420"`

### 2C. HEIC + HDR（本次新接，接受 8bit 降位）

1. 照片格式 = **HEIF**
2. 拍 2 张

**预期日志**：`photo.heif_hdr`（成功）或 `photo.heif_hdr_failed`（回退）

### 2D. HDR 关闭对照（重要）

1. 把「HDR 成片」**关掉**
2. 同机位再拍 1 张 JPEG

**预期日志**：`photo.encode` 里 `"hdr":"none"`
→ 这张是**对照组**，用来看 HDR 到底有没有带来差别

---

## 3. 看效果（4 个观察点）

### ① 系统相册（最关键）

打开系统相册看 2A 那张：
- **HDR 屏会看到窗外/灯更亮、更"通透"**
- 下拉通知栏如果能调亮度，HDR 图在亮处应该明显更亮
- **对照**：2D 那张（HDR 关）应该明显"平"一些

### ② 第三方查看器

- **Chrome**：把图拖进浏览器（或文件管理器里用 Chrome 打开）
- **Google Photos**（如果装了）
- 看是否显示 HDR 增益

### ③ 不支持的场景应该回落 SDR（不能偏色）

- **微信**：发送到文件传输助手，看是否正常（应显示普通 SDR，**不能**发灰/过曝/偏色）
- **macOS / Windows**：拷到电脑打开

### ④ App 内相册

- 打开 App 相册，点开 2A 那张，看是否正常显示（不偏色、不黑）
- 如果出现"一坨五颜六色"（期 A 的霓虹伪彩），记下来

---

## 4. 日志回传

### 方式 A：adb（推荐，最全）

```bash
adb pull /sdcard/Android/data/com.filmkit.app/files/logs/ ./filmkit-logs-0.11.50/
```

### 方式 B：App 内导出

设置 → 日志 → 「导出 JSONL」

---

## 5. 日志里我要看的关键字段

| 关键字 | 含义 | 正常值 |
|---|---|---|
| `photo.encode` 的 `hdr` | 走了哪条路径 | `jpegR444` / `platform420` / `none` |
| `photo.heif_hdr` | HEIC HDR 成功 | 有这条 = 成功 |
| `photo.heif_hdr_failed` | HEIC HDR 失败 | 应回退到 `photo.heif_encoded` |
| `hdr.gainmap_read_failed` | gainmap 读回失败 | **不应出现** |
| `hdr.gainmap_crop_oob` | gainmap 裁剪越界 | **不应出现** |
| `hdr.window_mode_failed` | 窗口 HDR 模式失败 | 出现则相册 HDR 不生效 |
| `photo.crop` | 画幅裁剪 | 16:9 那张应有 |
| `photo.encode` 的 `ms` | 编码耗时 | 记录数值 |

### 特别关注

- **`"hdr":"jpegR_failed"`** 或 **`"platform420_failed"`** ⇒ HDR 编码失败，说明 libultrahdr 或平台 API 有问题
- **`photo.encode` 里 `bytes` 明显变大**（HDR 会比普通 JPEG 大 30~60%）⇒ 说明 gainmap 真的写进去了

---

## 5b. gainmap 数学 A/B（v0.11.53 新增）

设置 → 相机 → HDR 打开后，会出现 **「gainmap 数学」** 一行，点一次切一档：

| 档 | 公式 | offset | 暗部处理 |
|---|---|---|---|
| **PC** | PhotonCamera `RawGainmapMath` | `1e-4` | 靠 offset 垫高分母，天然抑制 |
| **官方** | libultrahdr `gainmapmath.cpp` | `1e-7` | `sdr<2/255` 时硬钳 gain ≤ 2.3 |

两版**最终都归一化到固定 [0.25, 4.0]**（官方自适应范围需两遍扫描，单 pass 做不到）。
差异只在 offset 与暗部钳位 —— 这正是离线 A/B 里唯一剩下的分歧点。

**怎么比**：同机位（高光场景）各拍一张，看 `raw.render` 的：
- `gmMath`：0 = PC / 1 = 官方（确认设置真的生效了）
- `gmMax` / `gmMean`：gainmap 的实际分布

| `gmMax` | 含义 |
|---|---|
| ≈ 0.5 | 比值恒 1，**该场景无 HDR 内容**（不是链路坏了） |
| 0.6 ~ 0.8 | 有增益（约 1.5~2.5 倍） |
| > 0.9 | 强增益（接近 4 倍上限） |

**结论判据**：肉眼挑"高光不过曝、暗部不脏"的那一档；`gmMax` 用来确认"是不是场景本身没内容"。

---

## 5c. 为什么我拍的场景看不出 HDR？（v0.11.53 实测）

日志里 `raw.render` 的 **`pHi`**（显示域 P95）：

| 场景 | `pHi` | 结果 |
|---|---|---|
| 台灯未开的室内（本次） | **0.65 / 0.61** | 最亮的 5% 都没到 0.65 ⇒ **没有高光**，HDR/SDR 比值≈1，gainmap 恒≈0.5 |

**HDR 的价值是让高光更亮，不是凭空造出层次。** 场景本身没有过曝区域时，SDR 已经包含了全部信息，HDR 自然"没效果" —— 这是**场景**决定的，不是链路坏了。

**必须拍有明确高光的场景**：
- ✅ 窗边逆光（窗外过曝、室内暗）
- ✅ 开着的台灯 / 灯泡入画
- ✅ 太阳在画面里
- ❌ 均匀白墙、关着的灯、阴天室外

## 6. 如果出问题，额外给我这些

```bash
# 崩溃日志（如果有）
adb pull /sdcard/Android/data/com.filmkit.app/files/logs/crash-latest.txt

# 设备信息（判断 HDR 屏支持）
adb shell dumpsys display | grep -i "hdr\|colorMode" | head -20

# 拍出来的图（我看 SOF0 色度和 MPF/XMP 结构）
adb pull /sdcard/Pictures/FilmKit/ ./photos-0.11.50/
```

---

## 已知的、不用报的

- **HDR 图在微信里看起来和普通图一样** —— 预期行为（微信转 SDR）
- **HEIC + HDR 的图比普通 HEIC 小** —— 可能（8bit + 不同编码器），不是 bug
- **App 内相册看不出 HDR 效果** —— 可能，取决于窗口 HDR 模式是否生效，以系统相册为准
