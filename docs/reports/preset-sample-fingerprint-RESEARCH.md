# 目标风格「样片指纹」调研 —— Leica M9 直出 / 理光负片 / 徕卡黑白 HC

> 调研卡 2026-10-11 ｜ 状态：**已交付**
> 前置：`docs/reports/editor-E4-film-params-DESIGN.md` §4.1（现有 LUT 资产来源不可追溯）、
> `docs/reports/preset-DESIGN.md`（三条内置预设的定位）
> 交付物：本文档 + `tools/preset-exp/sample-fingerprint.py` + `tools/preset-exp/fingerprint/*.json`
>
> **一句话回答用户的质疑**（"是不是从网上找样片来分析比较好？"）：
> **走得通，而且比"从第三方 LUT 反推"强得多** —— 本卡实际拿到了
> ① Ricoh 的**同场景三联成对样片**（Negative / Positive / Standard，同一台 GR IIIx）、
> ② Leica M9 的**标准化场景样片**（Imaging Resource still-life，含 ColorChecker + 灰阶，机内 JPEG 全分辨率）、
> ③ Ricoh 官方 12 张 Negative Film 样片。
> 但 **Leica B&W HC 的成对样片没查到**，那一条只能给间接参考。

---

## §0 摘要（每条挂证据）

1. **① 成对样片：Ricoh 找到了，Leica M9 找到了"设置序列"型，Leica B&W HC 没查到。**
   Ricoh = `i.redd.it/sbgt92p0e8da1.jpg`（10000×2400，同一台 GR IIIx 同场景的 Negative / Positive / Standard 三联，[Reddit 帖](https://www.reddit.com/r/ricohGR/comments/10h2e94/)）。
   M9 = Imaging Resource 的 `M9hSLCON1..5`（Contrast −2…+2）与 `M9hSLSAT1..5`（Saturation −2…+2），
   同场景同机同光、**唯一变量是机内设置**，且 IR 页面明确标注了档位含义（证据见 §2.2）。

2. **② 标准化场景样片：M9 拿到了，而且是机内 JPEG 全分辨率。**
   Imaging Resource 的 M9 全套 191 个图片文件在 Wayback 有存档，含 `M9hSLI0160.JPG`（still-life 场景，
   **画面内含 X-Rite ColorChecker 24 色卡 + 6 级灰阶行**）与同场景 RAW 转换版
   （`_DCR.JPG` / `_ACRu100r03.JPG`）。EXIF `Software=1.176` = M9 固件 → **确为机内直出**。
   ⚠️ **IR 原站被 Cloudflare 拦死（403），Wayback 只存了 `FULLRES/` 下的图片**（见 §1）。

3. **③ dpreview 的 M9 路线是死的。** dpreview 全站（含 `www/2/3/4.img-dpreview.com` 图片 CDN）对 curl 一律 403；
   Wayback 上 M9 的 27 张 gallery 图**只存了 60×60 缩略图**和 1 张 1200×630 的 og:image 裁剪版
   （CDX 实测，见 §2.2）。**dpreview 从未做过 M9 完整评测**，只有 2009-09-09 的 Hands-on preview，
   所以**它根本没有 M9 的 studio scene**。

4. **M9 直出风格的真实指纹（本卡最重要的定量结果）**：把 IR still-life 的机内 JPEG 灰阶行
   与 ColorChecker 标称 sRGB 对齐（IR 的曝光把中灰 N5 打得很准：实测 luma 122.1 vs 标称 121.9），
   得到 **S 形影调曲线**：暗部陡（52→85 段斜率 **1.31**）、中灰陡（85→122 段 **1.36**）、
   **高光强压（200→243 段斜率仅 0.64）**；黑场 52→28.6、白场 243→233.4。
   色彩侧：**饱和度增益中位数 1.178（+17.8%）**，灰阶基本中性（R−G ≈ −4，略偏冷）。

5. **理光负片的净效应（同场景相除，完全排除场景与光线）**：
   **对比度几乎不变**（p95−p5 比 **1.0247**）、**平均亮度 +5.53**、**饱和度 ×0.772（−22.8%）**、
   中性区 **b\* 下降 1.7~1.8**（去黄/偏蓝）。影调映射是"中间调提亮、两端收敛"，
   峰值提亮在输入 170~195（+14）。

6. **我们的三条 LUT 与目标的差距是"结构性"的，不是强度问题。**
   - `leica_m9.lut`：中灰（输入 122）压到 **103.6**（目标 **122.1**，差 **−18.6**），
     白场 243→**247.8**（目标 **233.4**，差 **+14.5**），暗部 52→**35.5**（目标 **28.6**，差 **+6.9**）；
     分段斜率（暗→亮）我们 `[0.83, 1.10, 1.33, 1.20, 1.07]` vs 目标 `[1.31, 1.36, 1.24, 0.92, 0.64]` —— **形状相反**。
     饱和度增益 **0.98** vs 目标 **1.178**。
   - `ricoh_negative.lut`：高光段方向反了 —— 输入 235 目标 **241.1**、我们 **220.9**（**−20.2**）；
     输入 250 目标 **251.9**、我们 **231.7**（**−20.2**）。饱和度 **0.663** vs 目标 **0.772**（降太多）。
   - `leica_b_w_hc.lut`：中灰斜率 **1.15**，而 M9 Contrast +2（间接参考）在中灰段是 **1.6~1.75**；
     输入 85 处我们 **82.6** vs 参考 **52.7**（**+29.9**，远不够黑）。

7. **"从第三方 LUT 反推"这条路被本卡数据否掉了。** 因为 `lut_strength` 是线性混合
   （`out = in + s·(lut(in) − in)`），要让 `leica_m9.lut` 匹配目标，暗部需要 `s = 1.42`、
   中间调需要 `s ≈ 0`、高光需要 `s = −2.0` —— **同一个 s 无法同时满足**。
   三条 LUT 都算出了同样的矛盾（§4.4 有逐点反解表）。**结论：LUT 只能当"色彩风味"用，影调必须由引擎参数重建。**

8. **可执行的替代路线**：以本卡的**目标曲线数据**（§3，已落成 JSON）为基准，
   用引擎的 `contrast[4]` + `toe[5..7]` / `shoulder[8..10]` + `sat_shadow/mid/high[20..22]`
   重建影调与饱和，`lut_strength[34]` 降到 **0.3~0.5** 只保留色彩风味。逐条数值见 §5。

9. **颗粒维度：本卡没能定量目标风格的颗粒。** IR 的 M9 样片是 ISO 160 的 still-life，
   30 张实测平场噪声 RMS **中位数仅 1.288/255**（`sample-leica-m9-ir-stilllife.json`），量级不足；
   Ricoh 官方样片是 747×1120 的 web 尺寸，**脚本直接返回 `skipped: resolution too low`**
   （下采样会抹掉颗粒，无法做 FFT 功率谱）。
   → **颗粒仍应沿用 `e4-3-grain-REPORT.md` 的 darktable 对齐路线**，本卡不提供颗粒标定。

10. **版权与合规**：本卡所有样片**只下载到 `/tmp/fk-samples/`**（含 `ir-m9/`、`ricoh/`、`ricoh-pairs/`），
    **未写入仓库、未进 git、未打包进 assets**。仓库里只提交分析脚本、指纹 JSON 与本报告。

---

## §1 方法与工具实测

### 1.1 证据强度分级（按架构师定的口径执行）

| 优先级 | 样本类型 | 本卡实际拿到 |
|---|---|---|
| **① 成对样片** | 同场景、同机、开/关该风格 | **Ricoh 三联图 ✓**；**M9 Contrast/Saturation 序列 ✓**；**Leica B&W HC ✗ 没查到** |
| **② 标准化场景** | 灰阶卡 + ColorChecker | **IR M9 still-life ✓**（30 张全分辨率机内 JPEG + RAW 转换版） |
| **③ 普通样片** | Flickr / Reddit / 官方样张 | **Ricoh 官方 12 张 ✓**（grblog.jp，747×1120） |

### 1.2 工具实测结果（本轮真实探测，2026-10-11）

| 目标 | 结果 | 备注 |
|---|---|---|
| `web_search` 工具 | **不可用**（缺 API key） | 本轮未调用 |
| `search.brave.com/search?q=` + curl | **可用**（200） | 频率过高会被限流，返回 tor 手册页（实测遇到） |
| `web_fetch` 工具 | 本轮**未使用**（curl 已覆盖全部通路，故未验证） | — |
| `www.dpreview.com` / `m.dpreview.com` / `2.img-dpreview.com` | **403 Cloudflare**（`Just a moment...`） | 带完整浏览器头（Accept/Referer/Sec-Fetch）仍 403 |
| `www.imaging-resource.com`（含图片直链） | **403 Cloudflare** | 同上 |
| `web.archive.org/web/{ts}id_/{url}` | **可用，图片也能取原始字节** | 本轮主要通路 |
| `web.archive.org/cdx/search/cdx` | **可用**（会间歇 503 `Temporarily Offline`，重试即可） | 靠它列出了 IR 的 191 个图片 URL |
| `archive.org/wayback/available` | 可用 | 但它查不到 `FULLRES/` 路径下的图（路径不对会返回空） |
| Reddit `.rss`（搜索 / 单帖评论） | **可用** | `.json` 与 `old.reddit.com` 均 403；`.rss` 会 429 限流，需间隔 ~10s |
| `i.redd.it` / `preview.redd.it` 图片直链 | **可用**（200 image/jpeg） | 本轮成对样片的来源 |
| `www.grblog.jp`（Ricoh 官方 GR 社区站） | **可用**（200） | 官方 Negative Film 样片 + 官方默认参数 |
| `www.ricoh-imaging.co.jp` | 可用（200） | 本轮未深挖 |
| `www.flickr.com/groups/...` | 302 重定向，拿不到内容 | 放弃 |
| `www.kenrockwell.com` / `www.l-camera-forum.com` | 403 | 放弃 |
| `macfilos.com` | 可用（200） | 但其 M9 样片是 DNG 经 Lightroom 处理，**不适合做直出风格分析**，未采用 |
| DuckDuckGo / Bing / Ecosia / Startpage / `r.jina.ai` | 不可用（沿用上一轮结论，本轮未重复验证） | — |

### 1.3 分析工具

`tools/preset-exp/sample-fingerprint.py`（本卡新增，依赖仅 `numpy` + `pillow`）：

```bash
python3 -m pip install numpy pillow

# 自检（合成图，不需要任何素材）
python3 tools/preset-exp/sample-fingerprint.py selftest
#   → selftest OK {'identity_mid_slope': 1.0, 'gray_mean': 127.5, 'noise_rms': 2.951}

# LUT 指纹
python3 tools/preset-exp/sample-fingerprint.py lut --ids leica_m9,ricoh_negative,leica_b_w_hc \
    --out tools/preset-exp/fingerprint/lut-fingerprint.json

# 样片目录指纹（影调分位数 / 色彩 / 颗粒 FFT）
python3 tools/preset-exp/sample-fingerprint.py samples --name <名字> --dir <目录> --out <json>

# 成对样片净效应
python3 tools/preset-exp/sample-fingerprint.py pair --styled a.jpg --ref b.jpg --out <json>

# ColorChecker 24 色块采样（--ref 则算净效应；--mark 输出采样框校验图）
python3 tools/preset-exp/sample-fingerprint.py chart --styled a.JPG --ref b.JPG \
    --out <json> --mark /tmp/cc_mark.png
```

**口径声明（引用本卡数据时必须一起引用）**：

- **样片侧没有"输入参考"**，所以样片只产出**分布型指标**（分位数、chroma、色偏）；
  `mid_slope` 这类"曲线斜率"只有 LUT 侧与色卡侧（有标称输入）才有。
- 亮度一律用 **Rec.709 luma（gamma 域加权）**，不是线性光 —— 这样与直方图/常见工具的读数一致。
  Lab 相关的计算另走 `rgb_to_lab()`（那里才线性化）。
- ColorChecker 采样坐标（`CC_COLS/CC_ROWS`）是在 IR still-life 场景上**目视标定 + 色块内 sd 校验**得到的
  （实测 sd 0.6~2.3/255，说明采样窗完全落在色块内部；校验图见 `--mark`）。
- RAW 转换版与机内 JPEG 有 1~8 像素位移，`chart` 子命令用"色块内方差最小"自动对齐后才比较。

---

## §2 三条各自的样本获取结果

### 2.1 理光负片（Ricoh GR "Negative Film"）

#### ① 成对样片 —— **找到**（黄金标准）

| 项 | 内容 |
|---|---|
| URL | `https://i.redd.it/sbgt92p0e8da1.jpg`（来自 [Reddit r/ricohGR 帖 10h2e94](https://www.reddit.com/r/ricohGR/comments/10h2e94/how_the_new_negative_film_simulation_compares_to/)） |
| 本地 | `/tmp/fk-samples/ricoh-pairs/sbgt92p0e8da1.jpg` |
| 尺寸 | **10000×2400**（三联横拼；切分后每张约 **3188×2128**） |
| 场景 | 室内书架 + 地球仪 + 百叶窗（逆光窗），同一机位 |
| 三联内容 | 左 `GR IIIx Negative Film` / 中 `GR IIIx Positive Film` / 右 `GR IIIx Standard`（图片自带文字标签） |
| 为什么算成对 | 三张构图完全一致（互相关最佳偏移仅 **1~2 像素**），**同机同镜同场景同光**，唯一变量是 Image Control 模式 |

切分脚本（本轮实际执行）：
```bash
# 白色间隔检测结果：列 (0,111) (3300,3407) (6592,6699) (9888,9999)；行 (0,103) (2232,2271) (2351,2399)
# → 内容区 y 104..2231；三张 x 分别 112..3299 / 3408..6591 / 6700..9887
python3 - <<'PY'
from PIL import Image
im=Image.open('/tmp/fk-samples/ricoh-pairs/sbgt92p0e8da1.jpg').convert('RGB')
for k,b in {'neg':(112,104,3300,2232),'pos':(3408,104,6592,2232),'std':(6700,104,9888,2232)}.items():
    im.crop(b).save(f'/tmp/fk-samples/ricoh-pairs/gr3x_{k}.png')
PY
```

净效应（`pair` 子命令，Standard 为参考）落盘在
`tools/preset-exp/fingerprint/pair-ricoh-neg-vs-std.json`、`pair-ricoh-neg-vs-pos.json`。

> ⚠️ **一个必须说明的保留**：三联图的三张平均亮度分别是 neg 90.6 / pos 111.0 / std 85.1，
> 无法 100% 排除"分三次拍摄、曝光有微差"的可能（虽然偏移只有 1~2px）。
> **保守用法**：`chroma 比 0.772` 与 `b* 位移 −1.7` 是强信号（远超曝光微差能造成的量级）；
> `平均亮度 +5.53` 则与 Ricoh 官方文档里 Negative Film 默认 `High/Low Key = +1` 相互印证（见下），可信。

#### ③ 官方单张样片 —— **找到 12 张**

| 项 | 内容 |
|---|---|
| 站点 | Ricoh 官方 GR 社区站 `grblog.jp` |
| 文章 | [Customize Negative Film (Asakura)](https://www.grblog.jp/en/article/7151/) |
| 本地 | `/tmp/fk-samples/ricoh/IP02*.jpg`（12 张） |
| 尺寸 | **747×1120** 或 **1120×747**（web 尺寸，非原图） |
| URL 样例 | `https://www.grblog.jp/en/wp-content/uploads/sites/2/article/7151/IP024734_R-rotated-1.jpg` 等 12 条（全部实测 200，200KB~560KB） |

#### 官方参数（一手文档，非推测）

[grblog 6509（GR III Diary Edition Part 2，产品企划 Okubo）](https://www.grblog.jp/en/article/6509/) 原文给出
**Negative Film 的默认参数**：

> Negative film: **Saturation 0, Hue 0, Key +2, Contrast 0, Contrast (Highlights) 0,
> Contrast (Shadows) −1, Sharpness 0, Shading 0, Clarity 0**

[grblog 7151（Asakura）](https://www.grblog.jp/en/article/7151/) 给出 Asakura 自定义版的参数与默认值对照：

> Saturation: −2 (**default: ±0**) / Hue: ±0 (default: ±0) / High/Low Key: ±0 (**default: +1**) /
> Contrast: +3 (**default: +1**) / Contrast (Highlight): +4 (**default: +0**) /
> Contrast (Shadow): −4 (**default: −1**)

> 📌 两篇对 Key 的默认值说法不一致（6509 说 +2，7151 说 +1）。**本卡不裁定哪个对**，两条都原样记录。
> 但两篇一致指向同一件事：**Negative Film 的默认设定是"提亮 + 阴影降对比 + 略增全局对比"**，
> 这与 §3.2 从成对样片实测出来的净效应（平均亮度 +5.5、对比度比 1.02、高光不压）**方向完全一致**。

#### 没查到的

- **Negative Film 的标准化场景样片（灰卡/色卡）：没查到。** GR III 是 2019 年机型、Negative Film 是 2023-01 固件功能，
  而 Imaging Resource 已于 2023 年停止运营，没有该模式的 still-life 样片。
- **Negative Film 的官方 RAW+JPEG 成对文件：没查到。**

### 2.2 徕卡一瞬（Leica M9 直出）

#### ② 标准化场景 —— **找到，而且是机内 JPEG 全分辨率**

| 项 | 内容 |
|---|---|
| 来源 | Imaging Resource（原站 403），**经 Wayback 取回** |
| 目录 | `https://www.imaging-resource.com/PRODS/M9/FULLRES/` |
| 本地 | `/tmp/fk-samples/ir-m9/*.JPG`（30 张，全部 5216×3472） |
| EXIF | `Make=Leica Camera AG` / `Model=M9 Digital Camera` / **`Software=1.176`（M9 固件）= 机内直出** |
| 核心文件 | `M9hSLI0160.JPG`（still-life @ ISO160，**画面内含 X-Rite ColorChecker 24 色 + 6 级灰阶行 + WhiBal 灰卡**）<br>`M9hSLI0160_DCR.JPG`（同场景 dcraw 转换）<br>`M9hSLI0160_ACRu100r03.JPG`（同场景 ACR 转换）<br>`M9hSLCON1..5.JPG`（Contrast −2/−1/Default/+1/+2）<br>`M9hSLSAT1..5.JPG`（Saturation −2/−1/Default/+1/+2）<br>`M9hSLI0080/0200/0400/0800/1600/2500.JPG`（ISO 序列）<br>`M9hHOUSE.JPG` / `M9hVFA*.JPG` / `M9INBI0160.JPG` / `M9LL*.JPG` / `M9OUTB*.JPG` |

复现命令（本轮实际执行）：
```bash
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
# 1) 列出 IR M9 目录下所有存档（191 个图片条目）
curl -sS -A "$UA" --max-time 120 \
  'https://web.archive.org/cdx/search/cdx?url=www.imaging-resource.com/PRODS/M9/&matchType=prefix&fl=original,mimetype,statuscode&collapse=urlkey&limit=500'
# 2) 取原始字节（Wayback 的 id_ 修饰符 = 不做 Wayback 改写）
curl -sS -A "$UA" -L --max-time 120 -o M9hSLI0160.JPG \
  'https://web.archive.org/web/2016id_/https://www.imaging-resource.com/PRODS/M9/FULLRES/M9hSLI0160.JPG'
```

**IR 自己标注的测试序列含义**（原文抓取自 `M9PICS.HTM` 存档）：

> ISO Series ISO 160 / ISO 200 / ISO 400 / ISO 800 / ISO 1,600 / ISO 2,500
> **Contrast Series −2 / −1 / Default / +1 / +2**
> **Saturation Series −2 / −1 / Default / +1 / +2**
> …Still Life This is our new "Still Life" test target…

**本卡对档位映射的实测校验**：`M9hSLCON3D.JPG` 的灰阶 L\* = `[92.37, 82.69, 69.19, 51.07, 30.06, 10.43]`，
与独立拍摄的 `M9hSLI0160.JPG`（Default 设置）= `[92.47, 82.72, 69.32, 51.29, 30.53, 10.56]` 在 0.5 L\* 内一致
→ **确认 `CON3D` = Default**，且整条 CON 序列单调（白场 220.1→244.4、黑场 44.4→20.5）→ 档位方向正确。

#### ① 成对样片（"设置序列"型）—— **找到**

`M9hSLCON1..5` 与 `M9hSLSAT1..5` 就是**同场景、同机、同光、唯一变量为机内设置**的成对样片，
用于标定"Contrast / Saturation 参数 → 影调/饱和变化"的映射（数据见 §3.3）。
另有一组 **机内 JPEG ↔ 同场景 RAW 转换版**（`M9hSLI0160.JPG` vs `_DCR.JPG` / `_ACRu100r03.JPG`）。

#### ⚠️ RAW 转换版**不能**当"中性参考"用（重要负面结论）

| 参考 | 参考的灰阶 luma | JPEG 相对参考的变化 |
|---|---|---|
| dcraw (`_DCR`) | `[246.4, 198.4, 153.4, 111.3, 75.4, 42.7]` | `[−13.1, +7.5, +15.9, +10.9, −3.6, −14.2]`（S 形增强） |
| ACR (`_ACRu100r03`) | `[233.4, 205.9, 169.0, 121.9, 71.8, 28.6]`… 实为 JPEG 侧 | `[−8.9, −9.9, −8.6, −6.7, +3.6, +12.2]`（整体压暗 + 抬黑） |

**两个 RAW 转换器给出的净效应符号相反** → 说明"用 RAW 转换版当绝对中性基准"不成立。
本卡因此改用 **ColorChecker 标称 sRGB 值**作为输入基准（IR 的曝光把中灰 N5 打得极准：
实测 luma **122.1** vs 标称 **121.9**，差 0.2/255），口径见 §3.1。

#### 没查到的

- **dpreview 的 M9 studio scene：不存在**。dpreview 对 M9 只有 2009-09-09 的 *Hands-on preview*
  （`https://www.dpreview.com/reviews/leicam9`，9 页存档），**从未做完整评测**，也就没有 studio scene。
- **dpreview 的 M9 sample gallery 原图：拿不到**。CDX 实测该 gallery（id `7451529144`，27 张，原图 3472×5216）
  在 Wayback 上**只存了 `TC60x60S60x60~` 缩略图**和 1 张 `TC1200x630S1200x630~` 的 og:image 裁剪版；
  `TS1800x1800~` 版本（照片页 og:image）**未存档**（404 实测）。
- **M9 的 B&W / High-Contrast B&W 模式样片：没查到。** IR 的 Saturation 序列只测 −2…+2 五档，
  **不含 M9 机内 Saturation 选项里的 `B&W` / `Vintage`**（M9 规格表列出 Saturation 有 7 档：
  Low/Medium Low/Standard/Medium High/High/**B&W**/Vintage —— 抓自 dpreview preview 页存档）。

### 2.3 徕卡黑白 HC（Leica B&W HC）

**结论：没查到成对样片，也没查到标准化场景样片。**

尝试过的路径与失败原因：

| 路径 | 结果 |
|---|---|
| Imaging Resource M9 全套（191 个文件） | 无任何 B&W 模式样片（Saturation 序列只到 ±2） |
| dpreview M9 相关页面 | 无完整评测；`irtest/M9_filter.jpeg` 等同场景图 **Wayback 未存档**（404 实测） |
| `petapixel.com/2012/05/29/leica-m-monochrom-compared-to-the-leica-m9/` | **M Monochrom ≠ M9 的 B&W HC**（前者无 CFA、是另一套影调），**不能当目标样本**，未采用 |
| `macfilos.com` M9 长文 | 图片是 DNG 经 Lightroom 处理，非直出，未采用 |
| Brave 搜 `leica m9 black and white high contrast jpeg sample` | 返回 tor 手册页（限流），未取得有效结果 |
| `kenrockwell.com` / `l-camera-forum.com` | 403 |

**本卡能给的最接近的东西**：M9 的 **Contrast +2** 序列（§3.3），它是"M9 机内最高对比"的实测曲线。
B&W HC = "黑白 + 高对比"，所以 **Contrast +2 的影调曲线可作为 B&W HC 影调部分的间接参考（推断，非实测）**；
色彩部分（亮度权重）本卡无法标定。

---

## §3 目标指纹数据

所有数据落盘在 `tools/preset-exp/fingerprint/`，可被后续卡直接读取。

### 3.1 Leica M9 直出（Default 机内 JPEG）

来源：`chart-m9-sli-jpeg-vs-dcraw.json`（`styled` = `M9hSLI0160.JPG`）。
输入 = ColorChecker 标称 sRGB；输出 = 实测（口径 Rec.709 luma）。

| 灰阶块 | 输入(标称 luma) | M9 输出 luma | 分段斜率 |
|---|---|---|---|
| White | 242.9 | **233.4** | — |
| Neutral 8 | 200.0 | **205.9** | 200→243 段：**0.640** |
| Neutral 6.5 | 160.0 | **169.2** | 160→200 段：**0.916** |
| Neutral 5 | 121.9 | **122.1** | 122→160 段：**1.236** |
| Neutral 3.5 | 85.0 | **71.8** | 85→122 段：**1.364** |
| Black | 52.0 | **28.6** | 52→85 段：**1.310** |

**读法**：暗部斜率 1.31~1.36（压黑）、中灰几乎 1:1 通过、高光斜率 0.64（强压白）。
→ **典型的高对比 + 高光软肩 + 黑场下沉**。

**色彩（18 个彩色块）**：

- **饱和度增益**（实测 chroma ÷ 标称 chroma）：**中位数 1.178**、均值 1.175、范围 0.884（light_skin）~1.499（foliage）
- **色相偏移** `|Δh|`：中位数 **4.35°**、均值 5.60°；最大的几个：
  `cyan +16.0°`、`bluish_green +11.0°`、`light_skin −10.3°`、`orange_yellow +9.4°`、`orange +8.9°`
- **灰阶通道中性性**：`R−G` = −2 ~ −6（R 略低）、`B−G` = −3 ~ +1 → **基本中性、极轻微偏冷**
- **副色分量被大幅压低**（M9 风格最直观的表现）：

  | 色块 | 标称 RGB | M9 实测 RGB | 最大 Δ |
  |---|---|---|---|
  | orange_yellow | 224,163,46 | 219,177,**0** | B **−46** |
  | blue | 56,61,150 | **10**,46,153 | R **−46** |
  | orange | 214,126,44 | 215,137,**4** | B **−40** |
  | yellow | 231,199,31 | 223,205,**1** | B **−30** |
  | cyan | 8,133,161 | 2,140,**186** | B **+25** |
  | bluish_green | 103,189,170 | 99,199,**190** | B **+20** |

### 3.2 理光负片净效应（GR IIIx Negative ÷ Standard）

来源：`pair-ricoh-neg-vs-std.json`。

| 指标 | 值 |
|---|---|
| 对比度比（p95−p5） | **1.0247**（几乎不变） |
| 平均亮度差 | **+5.53**（提亮） |
| chroma 比 | **0.7724**（**−22.8% 饱和度**） |
| 中性区 a\*b\* 位移（风格 − 参考） | shadow `(+0.16, −0.44)`；mid `(+0.52, **−1.68**)`；high `(+0.54, **−1.80**)` |

**影调映射（Standard → Negative，sRGB 0-255）**：

| 输入 | 40 | 60 | 80 | 100 | 120 | 145 | 170 | 195 | 215 | 235 | 250 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **目标输出** | 42.7 | 64.8 | 86.4 | 109.1 | 131.1 | 157.8 | 183.7 | 207.7 | 225.2 | 241.1 | **251.9** |

分段斜率：`1.02`(4→16) `1.08` `1.10` `1.10` `1.09` `1.11` `1.14`(87→110) `1.06`(110→144) **`0.96`**(144→220)
→ **"中间调提亮 + 高光不压"**，峰值提亮 +14 出现在输入 170~195。

### 3.3 M9 Contrast / Saturation 序列（成对样片实测）

来源：`chart-m9-M9hSLCON{1,2,3D,4,5}.json`、`chart-m9-M9hSLSAT{1,2,3D,4,5}.json`。
灰阶行 luma（输入标称 = `[242.9, 200.0, 160.0, 121.9, 85.0, 52.0]`）：

| 设置 | White | N8 | N6.5 | N5 | N3.5 | Black |
|---|---|---|---|---|---|---|
| **Contrast −2** | 220.1 | 193.8 | 163.1 | 126.3 | 87.2 | 44.4 |
| **Contrast −1** | 227.1 | 200.8 | 167.1 | 125.8 | 80.9 | 35.4 |
| **Contrast Default** | 233.1 | 205.8 | 168.9 | 121.6 | 70.7 | 28.3 |
| **Contrast +1** | 239.1 | 212.8 | 174.7 | 121.3 | 61.7 | 23.6 |
| **Contrast +2** | 244.4 | 219.8 | 178.7 | 117.3 | 52.7 | 20.5 |

**这是一条绕中灰旋转的干净对比度序列**：白场 220→244 单调升、黑场 44→20 单调降、中灰 126→117 几乎不动。

Saturation 序列（`SAT1..5`，以 SAT1 为参考）的**灰阶 L\* 几乎完全不变**
（`[92.47, 82.69, 69.19, 51.15, 30.06, 10.56]` → `[92.28, 82.81, 69.39, 50.99, 30.16, 10.36]`），
而净 ΔE 均值从 5.94 单调升到 18.89 → **验证了分析管线：饱和度参数确实只动色彩、不动影调**。

### 3.4 样片分布统计（③ 普通样片 / 全批次，仅作方向性参考）

来源：`sample-ricoh-negative-grblog-official.json`、`sample-leica-m9-ir-stilllife.json`。

| 批次 | n | luma p5 / p50 / p95 | luma 均值 | 平均 chroma | 中性像素 a\* / b\* | 颗粒 RMS（中位） |
|---|---|---|---|---|---|---|
| Ricoh 官方 12 张（747×1120） | 12 | 7.07 / **58.42** / 190.0 | 70.98 | 6.21 | −0.16 / −0.21 | **无法测**（分辨率不足，脚本返回 `skipped`） |
| IR M9 30 张（5216×3472） | 30 | 14.34 / **141.01** / 216.89 | 130.22 | 15.37 | −1.19 / −0.51 | **1.288 / 255**（min 1.13、max 10.71） |

- Ricoh 那批 **luma p50 只有 58.4/255**、`mean_chroma 6.21`、`neutral_frac 0.851`
  → 与 §3.2 的"低饱和"净效应方向一致，但**场景各异（多为暗调夜景/室内），不能当定量依据**。
- M9 那批的 **颗粒 RMS 中位数仅 1.288/255**（FFT 径向谱 log-log 斜率中位数 −1.618）
  → **这就是 §6.3 "M9 样片不足以标定颗粒" 的直接证据**。

---

## §4 我们 LUT 的指纹与差距

### 4.1 我们三条 LUT 的指纹（`lut-fingerprint.json`）

| LUT | mid_slope（输入 0.35–0.65） | 黑场 | 白场 | 饱和增益均值 | 单色？ |
|---|---|---|---|---|---|
| `leica_m9` | **1.2786** | 0 | **255** | **0.9798** | 否 |
| `ricoh_negative` | **0.8659** | 0 | **237**（曲线末点 248→232） | **0.6628** | 否 |
| `leica_b_w_hc` | **1.1587** | 0 | 254 | 0（单色） | **是** |
| `leica_b_w_true` | 1.0458 | 0 | 255 | 0（单色） | 是 |
| `leica_classic` | 1.1336 | 0,1,2 | 247,252,243 | 0.8374 | 否 |
| `leica_chrome` | 1.1176 | 16,14,14 | 237,243,239 | 0.6958 | 否 |

### 4.2 Leica M9 —— 逐维度差距

（目标 = §3.1；我们 = `leica_m9.lut` 灰阶曲线，线性插值到同一输入）

| 输入 | M9 目标 | 我们的 LUT | 差 |
|---|---|---|---|
| 242.9（白） | 233.4 | **247.8** | **+14.5**（我们太白） |
| 200.0 | 205.9 | 202.0 | −3.9 |
| 160.0 | 169.2 | **154.0** | **−15.2**（我们太暗） |
| 121.9（中灰） | 122.1 | **103.6** | **−18.6**（我们太暗） |
| 85.0 | 71.8 | 63.0 | −8.8 |
| 52.0 | 28.6 | **35.5** | **+6.9**（我们不够黑） |

| 分段（暗→亮） | 52→85 | 85→122 | 122→160 | 160→200 | 200→243 |
|---|---|---|---|---|---|
| **M9 目标斜率** | **1.310** | **1.364** | 1.236 | 0.916 | **0.640** |
| **我们的斜率** | 0.833 | 1.099 | 1.325 | 1.200 | 1.068 |

**形状对比**：目标是"**暗部陡 + 高光平**"的 S 形；我们是"**暗部平 + 中灰陡 + 高光不压**"的驼峰形。
→ **两者不是同一条曲线的强弱差别，而是形状不同。**

| 色彩维度 | M9 目标 | 我们的 LUT | 差 |
|---|---|---|---|
| 饱和度增益（18 色中位数） | **1.178** | 0.980（min 0.701 / max 1.252） | **−0.198** |
| 灰阶中性 | R−G ≈ −4（略冷） | `warm_cool_r_minus_b = −1.21` | 方向一致，量级接近 |
| 单色？ | 否 | 否 | ✓ |

### 4.3 理光负片 —— 逐维度差距

| 输入 | 目标输出（净效应） | 我们的 `ricoh_negative` | 差 |
|---|---|---|---|
| 10 | 10.7 | 6.8 | −3.9 |
| 25 | 26.5 | 21.2 | −5.3 |
| 40 | 42.7 | 37.0 | −5.7 |
| 60 | 64.8 | 66.2 | +1.4 |
| 80 | 86.4 | 95.3 | **+8.9** |
| 100 | 109.1 | 122.5 | **+13.4** |
| 120 | 131.1 | 142.7 | **+11.6** |
| 145 | 157.8 | 163.0 | +5.3 |
| 170 | 183.7 | 181.5 | −2.2 |
| 195 | 207.7 | 197.5 | **−10.1** |
| 215 | 225.2 | 209.8 | **−15.4** |
| 235 | 241.1 | 220.9 | **−20.2** |
| 250 | **251.9** | 231.7 | **−20.2** |

| 维度 | 目标 | 我们 | 差 |
|---|---|---|---|
| 对比度（p95−p5 比 / mid_slope） | **1.0247** / ~1.08 | mid_slope **0.866** | **方向相反** |
| 饱和度增益 | **0.772** | **0.663** | **−0.109**（降过头） |
| 白场 | 250→**251.9**（几乎不动） | 248→**232** | **−20**（我们压太狠） |
| 中性区 b\* | −1.7 ~ −1.8 | `warm_cool_r_minus_b = −2.06` | 方向一致，量级接近 |

**一句话**：我们的 `ricoh_negative.lut` 是"**低对比 + 高光压暗 + 重降饱和**"，
目标是"**略增对比 + 高光保持 + 中度降饱和**"。

### 4.4 为什么"调 LUT 强度"救不了 —— 逐点反解

`lut_strength` 若为线性混合 `out = in + s·(lut(in) − in)`，反解出每个输入点需要的 `s`：

| LUT | 输入 | 目标 | LUT 输出 | 需要的 s |
|---|---|---|---|---|
| `leica_m9` | 52 | 28.6 | 35.5 | **1.42** |
| `leica_m9` | 122 | 122.1 | 103.6 | **≈ 0.0** |
| `leica_m9` | 160 | 169.2 | 154.0 | **−1.53** |
| `leica_m9` | 243 | 233.4 | 247.8 | **−2.04** |
| `ricoh_negative` | 100 | 109.1 | 122.5 | **0.40** |
| `ricoh_negative` | 235 | 241.1 | 220.9 | **−0.43** |

**同一个 `s` 无法同时满足暗部、中灰、高光** → **三条 LUT 的曲线形状与目标不匹配，调强度无效**。
（`leica_b_w_hc` 同理：输入 85 处我们 82.6、Contrast+2 参考 52.7，需要 `s ≈ 1.9`；输入 243 处我们 247.5、参考 244.4，需要 `s ≈ −0.9`。）

### 4.5 Leica B&W HC —— 差距（**间接参考，推断**）

**没有目标样片**。下表用 M9 `Contrast +2` 作为"高对比"的间接参考（口径：输入 = ColorChecker 标称 luma）。

| 输入 | Contrast +2 参考 | 我们的 `leica_b_w_hc` | 差 |
|---|---|---|---|
| 52 | **20.5** | 42.5 | **+22.0** |
| 85 | **52.7** | 82.6 | **+29.9** |
| 122 | 117.4 | 125.2 | +7.8 |
| 160 | 178.7 | 170.0 | −8.7 |
| 200 | 219.8 | 216.0 | −3.8 |
| 243 | 244.4 | 247.5 | +3.1 |

| 分段斜率（暗→亮） | 52→85 | 85→122 | 122→160 | 160→200 | 200→243 |
|---|---|---|---|---|---|
| **Contrast +2 参考** | 0.976 | **1.749** | **1.613** | 1.028 | 0.572 |
| **我们的 `leica_b_w_hc`** | 1.215 | 1.151 | 1.179 | 1.150 | 0.733 |

→ **我们的 B&W HC 在中灰段斜率只有参考的一半**（1.15 vs 1.6~1.75），暗部也远不够黑。
即：**它不"高对比"**。

---

## §5 ★ 参数修正建议（逐条给数值与依据）

> **总原则（依据 §4.4）**：三条 LUT 的**影调形状**与目标不匹配，`lut_strength` 无法修正；
> 建议**影调与饱和由引擎参数重建，LUT 降级为"色彩风味"层**。
> 槽位号沿用 `docs/reports/editor-E4-film-params-DESIGN.md` 的 `FilmParams` 约定。

### 5.1 徕卡一瞬（Leica M9 直出）

| # | 参数 | 建议值 | 依据 |
|---|---|---|---|
| 1 | `lut` | 保留 `leica_m9` | 色彩方向（暖冷 R−B=−1.21 vs 目标 −4 量级）大致对 |
| 2 | `lut_strength` [34] | **0.30 ~ 0.45** | §4.4：中灰处 LUT 把 122 压到 103.6（−18.6），目标只到 122.1；强度 0.4 时残差约 −7.4，可接受 |
| 3 | `contrast` [4] | **1.30** | §3.1：目标中灰段斜率 1.236~1.364；当前 LUT 中灰段斜率 1.325 但位置错，需引擎侧接管 |
| 4 | 白场 / 肩部 [8..10] 或 `filmic_white_ev` [38] | **让 243 落到 233.4**（200→243 段目标斜率仅 **0.640**） | §3.1：高光软肩是 M9 最鲜明的特征 |
| 5 | 黑场 / 趾部 [5..7] 或 `filmic_black_ev` [37] | **让 52 落到 28.6**（52→85 段目标斜率 **1.310**） | §3.1：黑场下沉、暗部拉陡 |
| 6 | `sat_mid` [21] / `sat_high` [22] | **1.15 / 1.10** | §3.1：目标饱和度增益中位数 **1.178**；LUT 只给 0.980，缺口约 +18% 需引擎补 |
| 7 | `sat_shadow` [20] | **1.10** | 同上；M9 的 `dark_skin` 增益 1.393、`foliage` 1.499，暗部饱和提升明显 |
| 8 | 白平衡 R/G/B [1..3] | **R ×0.985 或 B ×1.01（极轻微偏冷）** | §3.1：M9 灰阶 `R−G ≈ −4`、`B−G ≈ 0`，即略偏冷 |
| 9 | 颗粒 [26][27] | **本卡不提供标定** | §6.3：M9 样片是 ISO160 still-life，颗粒量级不足 |

**验收用的目标曲线**（可直接当回归基线，输入 sRGB → 期望输出 sRGB，容差 ±4）：

```
52 → 28.6   85 → 71.8   122 → 122.1   160 → 169.2   200 → 205.9   243 → 233.4
```

### 5.2 理光负片（Ricoh GR Negative Film）

| # | 参数 | 建议值 | 依据 |
|---|---|---|---|
| 1 | `lut` | 保留 `ricoh_negative`（色彩方向对：`warm_cool −2.06` vs 目标 −1.7~−1.8） | §4.3 |
| 2 | `lut_strength` [34] | **0.40 ~ 0.50** | §4.4：LUT 在高光段方向错误（235 处 −20.2），强度越高错得越多；0.45 时中间调提亮残差约 +6 |
| 3 | `contrast` [4] | **1.05 ~ 1.08** | §3.2：目标净效应分段斜率 1.02~1.14，p95−p5 比 **1.0247**（**不是降对比！**） |
| 4 | **High/Low Key / 曝光** [0] 或 key | **+0.10 ~ +0.15 EV**（目标平均亮度 +5.53/255） | §3.2；与 Ricoh 官方文档 `High/Low Key default +1` 一致 |
| 5 | 肩部 [8..10] / `filmic_white_ev` [38] | **不要压高光**：250 应映射到 **251.9** | §3.2：目标高光段斜率 0.96、白场几乎不动 |
| 6 | `sat_mid` [21] / `sat_high` [22] | **0.85 / 0.90** | §3.2：目标 chroma 比 **0.772**；LUT 已给 0.663，缺口约 +0.11 需引擎补回 |
| 7 | `sat_shadow` [20] | **0.90** | 目标暗部 b\* 位移仅 −0.44（暗部色偏最小），暗部饱和不宜再压 |
| 8 | 分离色调 / WB（冷） [23][24] 或 [1..3] | **B 增益 +0.7% 或 `split_cool` 小量** | §3.2：中性区 mid/high 的 **b\* −1.68 / −1.80**（去黄偏蓝），这是 Negative Film 最稳的色偏信号 |
| 9 | 颗粒 [26][27] | **本卡不提供标定** | §6.3：官方样片只有 747×1120，分辨率不足以做 FFT 功率谱 |

**验收用的目标曲线**（输入 sRGB → 期望输出 sRGB，容差 ±4）：

```
40 → 42.7   60 → 64.8   80 → 86.4   100 → 109.1   120 → 131.1
145 → 157.8   170 → 183.7   195 → 207.7   215 → 225.2   235 → 241.1   250 → 251.9
```
外加：**饱和度整体 ×0.77**、**中性区 b\* −1.7**。

### 5.3 徕卡黑白 HC（Leica B&W HC）

**先说结论：这条没有可靠目标，建议不要按"精确复刻"承诺。** 可选两条路：

| 方案 | 内容 | 依据 |
|---|---|---|
| **A（推荐，诚实）** | 定位改成"**徕卡风格高对比黑白**"，参数按 M9 `Contrast +2` 的影调走，不宣称复刻某个具体机型模式 | §3.3 有实测曲线；§6.1 记录"没查到 B&W HC 样片" |
| **B** | 继续找 M9 B&W HC 样片（本卡已排除 IR / dpreview 两条路；剩下的大概只有用户实拍） | §2.3 |

**若走方案 A，建议值**：

| # | 参数 | 建议值 | 依据（vs M9 Contrast +2 间接参考） |
|---|---|---|---|
| 1 | `monochrome` [25] | 1.0 | 已是单色 LUT；保持 |
| 2 | `lut_strength` [34] | **0.30 ~ 0.40** | §4.5：中灰段我们 1.15 vs 参考 1.6~1.75，LUT 拖后腿 |
| 3 | `contrast` [4] | **1.40 ~ 1.50** | §4.5：目标中灰段斜率 **1.613~1.749**，是我们当前（1.15）的 1.4~1.5 倍 |
| 4 | 黑场 / 趾部 [5..7] | **让 52 落到 20.5、85 落到 52.7** | §4.5：当前 52→42.5、85→82.6，暗部差 22~30/255 |
| 5 | 肩部 [8..10] | **轻微**：243 落到 244.4（几乎不动） | §4.5：参考在 200→243 段斜率 0.572，但白场本身不压 |
| 6 | 颗粒 [26][27] | 高对比黑白通常配明显颗粒；**具体值本卡不给**，走 `e4-3-grain-REPORT.md` | §6.3 |

**验收用的目标曲线（间接参考，标注为推断）**：

```
52 → 20.5   85 → 52.7   122 → 117.4   160 → 178.7   200 → 219.8   243 → 244.4
```

### 5.4 三条的共同建议

1. **给每条预设留一条"目标曲线"回归测试**：把 §5.1/§5.2/§5.3 的 `输入→期望输出` 表写进单测
   （项目已有 `EditorPresetTest.kt` / `EditorPresetAmountTest.kt` 的模式），
   任何后续调参一旦把曲线拉偏超过 ±4/255 就报错。**这是本卡最可复用的产出。**
2. **LUT 来源问题仍未解决**（`editor-E4-film-params-DESIGN.md` §4.1）：
   本卡证明了"可以不用这些 LUT 也能定标" —— 至少影调与饱和部分完全可以用引擎参数重建。
3. **不要用"从 LUT 反推"作为主路径**：本卡数据说明 LUT 的形状与目标不一致（§4.4），
   反推出来的参数会把 LUT 的缺陷一起固化进去。

---

## §6 诚实标注

### 6.1 没查到的（明确记录）

1. **Leica B&W HC 的成对样片 / 标准化场景样片：没查到。** 所有尝试路径与失败原因见 §2.3。
   §4.5 与 §5.3 里凡涉及 B&W HC 的目标数字，都是**用 M9 Contrast +2 做的间接推断**，不是该模式本身的实测。
2. **dpreview 的 M9 studio scene：不存在**（dpreview 从未完整评测 M9）。
3. **dpreview 的 M9 gallery 原图：拿不到**（Wayback 只存 60×60 缩略图与 1 张 1200×630 og:image）。
4. **Imaging Resource 的原站图片：拿不到**（Cloudflare 403）；本卡用的是 Wayback 存档副本。
5. **理光 Negative Film 的标准化场景样片（灰卡/色卡）：没查到。**
6. **三条风格的目标颗粒指纹：没查到可定量样本**（原因见 §6.3）。

### 6.2 无法定量的 / 口径限制

1. **Ricoh 三联图不能 100% 排除"分三次拍摄、曝光有微差"**。构图偏移仅 1~2px 支持"同一机位"，
   但三张平均亮度 90.6 / 111.0 / 85.1 的差异里，有多少是风格、有多少是曝光，**本卡无法分离**。
   → 因此 **`chroma 比 0.772` 与 `b* 位移` 可信度高**（曝光微差造不成 23% 的 chroma 差），
   **`平均亮度 +5.53` 可信度中等**（有官方文档 `Key +1` 佐证，方向可信）。
2. **M9 的"绝对"影调曲线依赖 IR 的照明与曝光标定**。本卡用"中灰 N5 实测 122.1 vs 标称 121.9"作为
   "IR 曝光是标准的"这一假设的证据（差 0.2/255，吻合度很高），但这是**间接证据**，不是 IR 的书面声明。
3. **ColorChecker 标称 sRGB 值是 X-Rite 公布值**，实际色卡有批次差异；IR 用的色卡品牌本卡未核实。
4. **RAW 转换版不能当绝对中性基准**（§2.2 已给实测反例：dcraw 与 ACR 给出的净效应符号相反）。
5. **样片代表性局限**：
   - Ricoh 成对样片只有 **1 个场景**（室内书架逆光）。Negative Film 在其它场景（尤其绿色植被、肤色）
     的表现本卡没有样本。
   - M9 的 still-life 是 **ISO 160 单张 + 固定照明**；高 ISO 的影调/色彩漂移本卡未分析。
   - Ricoh 官方 12 张样片是 **747×1120 web 尺寸、场景各异**，只能看方向，不能定量。

### 6.3 颗粒维度为什么没做

本卡的分析脚本**具备**颗粒度量能力（平场检测 + 按亮度分箱 RMS + FFT 径向功率谱斜率，
`selftest` 里用注入 σ=4/255 的合成噪声验证过，实测回收 2.951），但**目标侧没有可用样本**：

- **M9**：IR still-life 是 ISO 160，30 张实测平场 RMS 中位数 **1.288/255**（min 1.13 / max 10.71）
  —— 这个量级基本就是 JPEG 量化噪声，标不出 ISO 400/800 的颗粒曲线。
- **Ricoh**：官方样片 747×1120，`grain_metrics()` 直接返回 `{'skipped': 'resolution too low'}`
  （脚本对长边 < 1200 的图拒绝做颗粒分析）；Reddit 三联图是三张拼成 10000×2400 后再压缩，
  颗粒同样不可信。
- → **颗粒仍应沿用 `docs/reports/e4-3-grain-REPORT.md` 的 darktable `grain.c` 对齐路线**，
  本卡不提供颗粒标定，也不建议用本卡的样片去标定颗粒。

### 6.4 版权与合规

- 本卡所有样片**只下载到 `/tmp/fk-samples/`**（`ir-m9/` 30 张、`ricoh/` 12 张、`ricoh-pairs/` 若干），
  **未写入仓库、未提交 git、未打包进 App assets**。
- 仓库内只新增/修改三类文件：**分析脚本**（`tools/preset-exp/sample-fingerprint.py`）、
  **指纹数据**（`tools/preset-exp/fingerprint/*.json`）、**本报告**。
- 报告内所有样片 URL 均为**本次实际抓取成功**的地址（HTTP 200 + 图像字节）；引用原文处标注了出处。
- 本卡**未改动任何 App 源码、未改动任何生产代码**。

---

## §7 证据索引

### 7.1 实际抓取成功的 URL（按用途）

**① 成对样片**
| 用途 | URL | 结果 |
|---|---|---|
| Ricoh 三联成对图 | `https://i.redd.it/sbgt92p0e8da1.jpg` | 200，image/jpeg，1,878,767 B，10000×2400 |
| （帖子） | `https://www.reddit.com/r/ricohGR/comments/10h2e94/how_the_new_negative_film_simulation_compares_to/` | 经 `.rss` 取得图片直链 |
| M9 Contrast 序列 | `https://web.archive.org/web/2016id_/https://www.imaging-resource.com/PRODS/M9/FULLRES/M9hSLCON{1,2,3D,4,5}.JPG` | 5×200，各 ~5~7 MB |
| M9 Saturation 序列 | 同上 `M9hSLSAT{1,2,3D,4,5}.JPG` | 5×200 |
| M9 JPEG↔RAW 对 | 同上 `M9hSLI0160_DCR.JPG`（15.2 MB）、`M9hSLI0160_ACRu100r03.JPG`（11.9 MB） | 2×200 |

**② 标准化场景（IR M9 still-life，30 张全部 5216×3472）**
| 文件 | 大小 |
|---|---|
| `M9hSLI0160.JPG` / `M9hSLI0080.JPG` / `M9hSLI0200.JPG` | 6.9 / 7.2 / 7.1 MB |
| `M9hHOUSE.JPG` | 10.6 MB |
| `M9hVFAI0160.JPG` / `M9hVFAB.JPG` / `M9hVFAS.JPG` / `M9hVFAS_ACR.JPG` / `M9hVFAB_ACR.JPG` / `M9hVFA_ADOBE.JPG` | 6.3 / 6.0 / 6.4 / 8.9 / 8.5 / 6.1 MB |
| `M9INBI0160.JPG` / `M9INBI0160_ACRu100r03.JPG` | 5.6 / 9.0 MB |
| `M9LL01603.JPG` / `M9OUTBCON{1,2,3D,4,5}.JPG` | 5.8 / 5.1~6.4 MB |

**③ 官方样片与文档**
| 用途 | URL |
|---|---|
| Ricoh 官方 Negative Film 样片（12 张） | `https://www.grblog.jp/en/wp-content/uploads/sites/2/article/7151/IP024734_R-rotated-1.jpg` 等 12 条 |
| Ricoh 官方参数（默认值） | `https://www.grblog.jp/en/article/6509/` |
| Ricoh 官方参数（Asakura 自定义 + 默认对照） | `https://www.grblog.jp/en/article/7151/` |

**检索/元数据端点（实际用到）**
| 用途 | URL |
|---|---|
| IR M9 目录存档清单（191 图片条目） | `https://web.archive.org/cdx/search/cdx?url=www.imaging-resource.com/PRODS/M9/&matchType=prefix&fl=original,mimetype,statuscode&collapse=urlkey&limit=500` |
| dpreview M9 gallery 照片页清单（27 个 photoId） | `https://web.archive.org/cdx/search/cdx?url=www.dpreview.com/sample-galleries/7451529144/&matchType=prefix&fl=original&collapse=urlkey&limit=200` |
| dpreview M9 review 页存档 | `https://web.archive.org/web/2016/http://www.dpreview.com/reviews/leicam9/{2,5,8}` |
| IR 测试序列含义原文 | `https://web.archive.org/web/2016/http://www.imaging-resource.com/PRODS/M9/M9PICS.HTM` |
| Reddit 检索（可用的入口） | `https://www.reddit.com/r/ricohGR/search.rss?q=<关键词>&restrict_sr=1&sort=relevance&t=all` |

### 7.2 复现命令

```bash
UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

# ---- 1. 抓 M9 标准化场景（30 张全分辨率机内 JPEG）----
mkdir -p /tmp/fk-samples/ir-m9 && cd /tmp/fk-samples/ir-m9
for n in M9hSLI0160.JPG M9hSLI0160_DCR.JPG M9hSLI0160_ACRu100r03.JPG M9hSLI0080.JPG M9hSLI0200.JPG \
         M9hSLCON1.JPG M9hSLCON2.JPG M9hSLCON3D.JPG M9hSLCON4.JPG M9hSLCON5.JPG \
         M9hSLSAT1.JPG M9hSLSAT2.JPG M9hSLSAT3D.JPG M9hSLSAT4.JPG M9hSLSAT5.JPG \
         M9hHOUSE.JPG M9hVFAI0160.JPG M9hVFAB.JPG M9hVFAS.JPG M9hVFAS_ACR.JPG \
         M9hVFAB_ACR.JPG M9hVFA_ADOBE.JPG M9INBI0160.JPG M9INBI0160_ACRu100r03.JPG \
         M9LL01603.JPG M9OUTBCON1.JPG M9OUTBCON2.JPG M9OUTBCON3D.JPG M9OUTBCON4.JPG M9OUTBCON5.JPG; do
  curl -sS -A "$UA" -L --max-time 120 \
    "https://web.archive.org/web/2016id_/https://www.imaging-resource.com/PRODS/M9/FULLRES/$n" -o "$n"
done

# ---- 2. 抓 Ricoh 成对三联图 + 官方 12 张 ----
mkdir -p /tmp/fk-samples/ricoh-pairs /tmp/fk-samples/ricoh
curl -sS -A "$UA" -o /tmp/fk-samples/ricoh-pairs/sbgt92p0e8da1.jpg 'https://i.redd.it/sbgt92p0e8da1.jpg'
for n in IP024734_R-rotated-1 IP024771_R IP024854_R-rotated-1 IP025005_R-rotated-1 IP025159_R-rotated-1 \
         IP025284_R-rotated-1 IP025310_R-rotated-1 IP025311_R-rotated-1 IP025346_R-rotated-1 \
         IP025347_R-rotated-1 IP025354_R-rotated-1 IP025904_R; do
  curl -sS -A "$UA" -o "/tmp/fk-samples/ricoh/$n.jpg" \
    "https://www.grblog.jp/en/wp-content/uploads/sites/2/article/7151/$n.jpg"
done

# ---- 3. 切分三联图 ----
python3 - <<'PY'
from PIL import Image
im = Image.open('/tmp/fk-samples/ricoh-pairs/sbgt92p0e8da1.jpg').convert('RGB')
for k, b in {'neg': (112,104,3300,2232), 'pos': (3408,104,6592,2232), 'std': (6700,104,9888,2232)}.items():
    im.crop(b).save(f'/tmp/fk-samples/ricoh-pairs/gr3x_{k}.png')
PY

# ---- 4. 跑分析（在仓库根目录）----
F=tools/preset-exp/fingerprint
python3 tools/preset-exp/sample-fingerprint.py selftest
python3 tools/preset-exp/sample-fingerprint.py lut --ids leica_m9,ricoh_negative,leica_b_w_hc,leica_b_w_true,leica_classic,leica_chrome --out $F/lut-fingerprint.json
python3 tools/preset-exp/sample-fingerprint.py chart --styled /tmp/fk-samples/ir-m9/M9hSLI0160.JPG \
    --ref /tmp/fk-samples/ir-m9/M9hSLI0160_DCR.JPG --out $F/chart-m9-sli-jpeg-vs-dcraw.json --mark /tmp/cc_mark.png
python3 tools/preset-exp/sample-fingerprint.py chart --styled /tmp/fk-samples/ir-m9/M9hSLI0160_ACRu100r03.JPG \
    --ref /tmp/fk-samples/ir-m9/M9hSLI0160.JPG --out $F/chart-m9-sli-acr-vs-jpeg.json
for n in M9hSLCON1 M9hSLSAT1; do   # 这两个用自己当 ref（只为落盘 styled 数据）
  python3 tools/preset-exp/sample-fingerprint.py chart --styled /tmp/fk-samples/ir-m9/$n.JPG \
      --ref /tmp/fk-samples/ir-m9/$n.JPG --out $F/chart-m9-$n.json
done
for n in M9hSLCON2 M9hSLCON3D M9hSLCON4 M9hSLCON5; do   # 以 CON1(=Contrast −2) 为参考
  python3 tools/preset-exp/sample-fingerprint.py chart --styled /tmp/fk-samples/ir-m9/$n.JPG \
      --ref /tmp/fk-samples/ir-m9/M9hSLCON1.JPG --out $F/chart-m9-$n.json
done
for n in M9hSLSAT2 M9hSLSAT3D M9hSLSAT4 M9hSLSAT5; do   # 以 SAT1(=Saturation −2) 为参考
  python3 tools/preset-exp/sample-fingerprint.py chart --styled /tmp/fk-samples/ir-m9/$n.JPG \
      --ref /tmp/fk-samples/ir-m9/M9hSLSAT1.JPG --out $F/chart-m9-$n.json
done
python3 tools/preset-exp/sample-fingerprint.py pair --styled /tmp/fk-samples/ricoh-pairs/gr3x_neg.png \
    --ref /tmp/fk-samples/ricoh-pairs/gr3x_std.png --out $F/pair-ricoh-neg-vs-std.json
python3 tools/preset-exp/sample-fingerprint.py pair --styled /tmp/fk-samples/ricoh-pairs/gr3x_neg.png \
    --ref /tmp/fk-samples/ricoh-pairs/gr3x_pos.png --out $F/pair-ricoh-neg-vs-pos.json
python3 tools/preset-exp/sample-fingerprint.py samples --name ricoh-negative-grblog-official \
    --dir /tmp/fk-samples/ricoh --out $F/sample-ricoh-negative-grblog-official.json
python3 tools/preset-exp/sample-fingerprint.py samples --name leica-m9-ir-stilllife \
    --dir /tmp/fk-samples/ir-m9 --out $F/sample-leica-m9-ir-stilllife.json
```

### 7.3 落盘的指纹数据文件

| 文件 | 内容 |
|---|---|
| `lut-fingerprint.json` | 6 条 LUT 的灰阶曲线、分段斜率、饱和增益、中性偏移 |
| `chart-m9-sli-jpeg-vs-dcraw.json` | M9 机内 JPEG 的 24 色卡值（含标称对照）+ 对齐偏移 + 净 ΔE |
| `chart-m9-sli-acr-vs-jpeg.json` | ACR 版 vs JPEG 的对照 |
| `chart-m9-M9hSLCON{1,2,3D,4,5}.json` | M9 Contrast 五档的色卡/灰阶实测 |
| `chart-m9-M9hSLSAT{1,2,3D,4,5}.json` | M9 Saturation 五档的色卡/灰阶实测 |
| `pair-ricoh-neg-vs-std.json` | **理光负片净效应**（分位数映射、对比度比、chroma 比、分区 a\*b\* 位移） |
| `pair-ricoh-neg-vs-pos.json` | 理光负片 vs 正片的净效应 |
| `sample-ricoh-negative-grblog-official.json` | Ricoh 官方 12 张的分布统计 + 逐图明细 |
| `sample-leica-m9-ir-stilllife.json` | IR M9 30 张的分布统计 + 逐图明细（含颗粒 RMS / 径向谱斜率） |
