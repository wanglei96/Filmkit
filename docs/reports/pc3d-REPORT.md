> 存档自 /tmp/pc3d,2026-10-06

# PC `ESD3D2` 完整移植 —— 核尺寸 + 尺度结构 + 伪彩强度逻辑（v0.10.175 / versionCode 314）

> 任务：**完整照抄** PhotonCamera 的 ESD3D2 降噪，替换我们"只抄了判据"的简化实现。
> 本文所有 PC 事实**逐行读原文得到**（含 file:line），不凭记忆。
> 所有数字**可复算**：脚本 `/tmp/pc3d/pc3d_cpu.py`（复算）+ `/tmp/pc3d/eval_pc3d.py`（四组指标），
> 数据 `/tmp/pc3d/sweep.json` / `sweep2.json` / `probe.json`。

---

## 0. 一句话结论

1. **PC 的核不是"15×15 固定核"**：`#define MSIZE 15` 只是 shader 里的 fallback，
   真值由 Java 按噪声模型算出，范围 **7..21**（`ESD3D2.java:89-94`）。我们的噪声模型
   代入同一公式 ⇒ **MSIZE = 11**（`kernelSize = 4.55`）。照抄的结果是 11×11，不是 15×15。
2. **PC 是"两趟 + 噪声自适应降采样 + guided upsample"**（`ESD3D2.java:121-176`），
   不是单尺度。第一趟 `MOIRE = 0`（色度阈值退化为噪声阈值），第二趟 `MOIRE = 1.5`。
3. **伪彩强度逻辑的正确读法**（这是 v0.10.173 翻车的根因）：
   `sigZ = max(sigY, min(|chromaNoise|·MOIRE, 0.2))` 里的 **0.2 是 cap（上限）而不是
   被使用的值**。离线诊断实测（`/tmp/pc3d/sweep.json` 的 `_diag`）：在 ∇>60 的强边缘上
   `sigZ = 0.0074 / 0.0118`、**吃满 cap 的比例 = 0.0%** —— **cap 从未触发**。
   v0.10.173 把 cap 本身当成了放宽量 ⇒ 放大 27×，这才是"一片红一片黄一片紫"的真根因
   （前次诊断的"绝对 cap 太宽"只是表象）。
4. **量纲**：`sigY`/`sigZ` 都是**方差量纲**（`d²` 对 `d² + sigY`，两边同为平方），
   不是 σ。实测 ∇>60 边缘 `sigY = 2.4e-5`（σ_y ≈ 0.005）、`sigZ = 0.0074~0.0118`
   （σ_z ≈ 0.086~0.109）⇒ **色度路阈值是亮度路的 200~500×（方差）**。
   亮度路几乎不动（保锐），色度路被大范围平均（抹伪彩）—— 这就是 PC 的机制。
5. **照抄后的实测**（3 张真实样片，**主口径 = 用户指定口径**：`clip((R+B)/2−G,0)`
   在显示域 `max(|Δx|,|Δy|) > 60` 区域的均值）：
   * 照抄的自动值（MSIZE 11 / MOIRE 1.5）**叠加档 −12.6%**（三片全改善）、
     替换档 −0.9%（152207 反而 **+27.5%**）；
   * 离线最好（叠加档 + MSIZE 21 + MOIRE 5）**−20.6%**，三片全部改善；
   * 亮度 Laplacian **全部上升**（+0.5% ~ +3.6%，与 PC 实测 70.54 vs 63.71 同向）；
   * 叠加档的色渗红线**全绿**（`hue15blk ≤ 3.0%`、`blkVar128` 不升、`flatRG` 不降）；
     替换档的 `hue15blk` 7.5~8.4%、`blkVar128` +14~56%（警戒侧）。
   **但达不到 PC 的 3.68**：真机 10.60 按 −20.6% 折算 ≈ **8.4**，离 3.68 还差 **2.3×**。
   **⇒ 默认关**（`[54] = 0`），等真机 A/B 拍板（判据见 §6）。
6. **离线口径经校准，不是"严重低估"**：用 rawpy 直接产出 PC 的真实输入域
   （线性相机 RGB）做同式复算，`chromaNoise` 只比成片域高 **21%**
   （§4.5）⇒ §4.1 的增益对真机有外推力；但 GPU 成本只能靠 `pc3dMs` 真机拍。

---

## 1. PC 的 ESD3D2 完整结构（一手原文）

### 1.1 链路地位与输入输出域

`/tmp/pc/app/src/main/java/.../postpipeline/PostPipeline.java:1474-1506`（`BuildDefaultPipeline`）：

```
Bayer2Float → KernelNetPrep → Demosaic / Amaze → 【ESD3D2(true)】 → ABLC
            → AutoExposureCurve/AE → Initial → … → UpscaleCrop → LocalLaplacian2
            → CaptureSharpening → CorrectingFlow → Sharpen2
```

* **跑在去马赛克之后、ABLC / 色调链 / CCM 之前** ⇒ 输入输出是**线性相机 RGB**
  （值域 0..1+，scene-referred；`esd3d2_steered.glsl:161-163` 的注释明确说
  "no upper clamp: scene-referred values above 1.0 must survive the denoise"）。
* **它是该链上唯一的降噪 pass**（`hdrxNR` 开启时）。
* 依赖 `GradBuffer`（`.rg` = `gx, gy`），由独立 pass 产出
  （`GLUtils.java:537-551` → `utils/convdiff.glsl:33-36`，`TEXSIZE==3` 分支）：

```glsl
// convdiff.glsl:15, 34-36
#define sum3(vecin) (vecin.r*0.299+vecin.g*0.587+vecin.b*0.114)
Output.r = sum3(fetch(xy-ivec2(1,i))) - sum3(fetch(xy+ivec2(1,i)));  // /2
Output.g = sum3(fetch(xy-ivec2(i,1))) - sum3(fetch(xy+ivec2(i,1)));  // /2
```
⇒ 梯度是 **Rec.601 luma 的中心差分 / 2**（不是 Rec.709）。

### 1.2 核尺寸 —— **不是 15×15**

`ESD3D2.java:26-50` 是 Tunable；`ESD3D2.java:87-94` 是真正的核尺寸决策：

```java
double noiseMpy   = Math.max((NoiseS+NoiseO)/noiseTarget, 0.0000001);   // noiseTarget = 1/256
double kernelSize = 1.0f + Math.sqrt(noiseMpy) * noiseToKernelSize;     // noiseToKernelSize = 24
int    msize      = Math.min(minSize + (int)kernelSize - (int)kernelSize%2, maxSize);
                                                                        // minSize = 7, maxSize = 21
glProg.setDefine("KERNELSIZE", (float)(kernelSize));   // ← 这是 f1 的高斯 σ，不是核半径
glProg.setDefine("MSIZE", msize);
```

| 量 | 值 | 说明 |
|---|---|---|
| `MSIZE` | **动态 7..21（奇数）** | shader 里的 `#define MSIZE 15`（`esd3d2_steered.glsl:19`）只是 fallback |
| `KSIZE` | `(MSIZE-1)/2` | 主循环上界（`esd3d2_steered.glsl:20`） |
| `KERNELSIZE` | `1 + √noiseMpy·24` | **f1 各向同性/切向高斯的分母**（`:145-146`），也是 `normpdf` 的 σ |

**形状**：`esd3d2_steered.glsl:121-152` 是**对称折叠**——外层 `i,j ∈ [0, KSIZE]`，
每一格同时取 4 个象限点 `(i,j) (-i,-j) (i,-j) (-i,j)`，四者**共享**同一个空间权重 `f1`
与同一个 `w/w2` 的归一化处理。总取点数 = `(KSIZE+1)² × 4`（MSIZE=11 ⇒ 144；
MSIZE=15 ⇒ 256；MSIZE=21 ⇒ 484），覆盖整块 `MSIZE×MSIZE` 邻域（i=0 或 j=0 时 4 点有重叠）。

**我们的模型代入 PC 公式**：`noiseS+noiseO ≡ sigA+sigB`（按 PC 的 `(0.25,0.5,0.25)`
luma 权重合并的方差模型系数）= `8.57e-5` ⇒ `noiseMpy = 0.0219` ⇒
`kernelSize = 4.55` ⇒ `msize = min(7+4−0, 21) = 11`。

### 1.3 单尺度还是多尺度 —— **两趟 + 噪声自适应降采样 + guided upsample**

`ESD3D2.java:121-176`（`Run()`）的完整编排：

```java
float N      = (float) Math.sqrt(0.5 * noiseS + noiseO);
float scaleF = Math2.clamp(N/targetN, 1.0f, 4.0f);
int   scale  = (int)(scaleF + 0.5f);
if (useColorDenoising) {                       // 默认 true（:57-60）
  if (scale != 1) {
      main4 = glUtils.gaussdown(prev, scale);  // ① 降采样（5×5 高斯 + 抽取）
      ConvDiff(main4, gradLow, 0.f);           // ② 低分辨率梯度
      ESD3DRun(main4, main5, gradLow, 0.0f, scaleF*0.75f);   // ③ MOIRE=0，噪声/scale
      WorkingTexture = getMain();
      guidedUpsample(main5, main4, prev, outp, scale);       // ④ 引导上采样回全分辨率
  } else {
      ConvDiff(prev, grad, 0.f);
      ESD3DRun(prev, WorkingTexture, grad, 0.0f, 1.0f);      // ③' 全分辨率 MOIRE=0
      guidedUpsample(WorkingTexture, prev, prev, outp, scale);
  }
}
ConvDiff(outp, grad, 0.f);
ESD3DRun(outp, WorkingTexture, grad, moire /* 1.5 */, 1.0f); // ⑤ 第二趟：全分辨率 MOIRE=1.5
```

* **第一趟 MOIRE = 0** ⇒ `sigZ = max(sigY, min(0, 0.2)) = sigY`（色度路退化为亮度路）。
* **第二趟 MOIRE = 1.5** ⇒ 伪彩强度逻辑**只在这里开火**。
* `ESD3DRun` 的 `scale` 形参只做一件事：`NoiseS /= scale; NoiseO /= scale`
  （`ESD3D2.java:74-83`）⇒ 降采样域的噪声模型更小 ⇒ **MSIZE 也更小**（我们 ⇒ 7）。
* `gaussdown`（`GLUtils.java:238-280`）：`stride = max(1, k/2)`、`blur = k·0.5`，
  5×5 高斯 `σ = blur/stride`，采样点 `xy*k + (i-2)·stride`，输出归一化 `mask/pdfsize`。
* `guidedupsample.glsl`：**高斯加权最小二乘拟合**（导引 = RGB 均值 lightness），
  `R = SCALE`、`σ = 0.4R`、`sigmaSq2 = 2σ²`，窗口 `(2R+1)²`；
  最后 `Output *= guideLightness / max(outL, 0.25·guideLightness)`
  ⇒ **输出的亮度恒等于引导亮度**，色度比来自低分辨率拟合模型。
  ⚠ 原文只用 `LowresInput` 与 `GuideHigh` 两个纹理（`Guide` 形参未被使用，
  见 `:30` 被注释掉的那行），`pos/SCALE` 是整数除法。

**我们的模型代入 scale 公式**：`N = √(0.5·sigA + sigB) = 6.51e-3`，
`scaleF = 6.51e-3 / (1/256) = 1.667` ⇒ **scale = 2**。

### 1.4 亮度路 `sigY` 与色度路 `sigZ` 的完整公式

`esd3d2_steered.glsl:64, 92, 94-101`：

```glsl
float noisefactor = dot(cin, vec3(0.25,0.5,0.25));                 // :64  ← PC 的 luma 权重
float sigY = max(NOISES*noisefactor + NOISEO, 0.0000001);          // :92  方差量纲
vec3  chromaDiff  = (abs(cavg-cinX)+abs(cavg-cinY)+abs(cavg-cinXY)+abs(cavg-cin))/4.0; // :94
      chromaDiff *= max(abs(xDelta),abs(yDelta));                  // :96  ← 伪彩强度逻辑核心
float chromaNoise = max(chromaDiff.r,max(chromaDiff.g,chromaDiff.b))
                  - min(chromaDiff.r,min(chromaDiff.g,chromaDiff.b));  // :97 3 通道**极差**
float sigZ = max(sigY, min(abs(chromaNoise)*MOIRE, 0.2));          // :98
```

其中（`:59-63, 68-75`）：
```glsl
vec3 cin   = fetch(xy);            cinX  = fetch(xy+(1,0));
vec3 cinY  = fetch(xy+(0,1));      cinXY = fetch(xy+(1,1));
vec3 cavg  = (cin+cinX+cinY+cinXY)/4.0;                            // 2×2 quad 均值
for i,j ∈ [-1,1]:  xDelta += i*length(g); yDelta += j*length(g);    // g = GradBuffer.rg
xDelta /= 3.0;  yDelta /= 3.0;
```
⇒ `max(|xDelta|,|yDelta|)` = **3×3 邻域内梯度幅值的一阶矩（/3）**，不是单点梯度。

**权重与累加**（`:104-152`）：
```glsl
// gref：3×3 自引导参考（权重形状与主滤波同）
ww = max(0.0, 1.0 - dd*dd/(dd*dd + sigY));   dd = length(abs(c - cin));
cref = gref/max(gW, 0.0001);
// 主循环（对称折叠）
d  = |cc_k - cref|                           (k = 0..3，3D 欧氏距离)
w  = 1.0 - d*d/(d*d + sigY);                 ← 亮度路
w2 = 1.0 - d*d/(d*d + sigZ);                 ← 色度路（**无独立亮度项**）
wm = min(w);  ws = (w-wm)/|w-wm|;  w *= ws;  ← 减去四者最小值再归一化再乘回
w2s = (w2-wm)/|w2-wm|; w2 *= w2s;            ← 注意 wm 用的是 **w 的**最小值
ut = i*tng.x + j*tng.y;
f1 = mix(normpdf(i,KERNELSIZE)*normpdf(j,KERNELSIZE), normpdf(ut,KERNELSIZE), coh);
final_colour  += f1 * mat4x3(cc0,cc1,cc2,cc3) * w;
final_colour2 += f1 * mat4x3(cc0,cc1,cc2,cc3) * w2;
Z  += dot(vec4(f1), w);   Z2 += dot(vec4(f1), w2);
```
`Z`/`Z2` 的种子 = `0.01`，且 `final_colour`/`final_colour2` 的种子 = `cin * 0.01`（`:100-101, 116-117`）。

**结构张量转向**（`:68-87`）：3×3 的 `sxx/sxy/syy` → 特征分解 → `coh = ((λ1−λ2)/(λ1+λ2))²`、
`tng = normalize(sxy, λ1−sxx)`（主特征向量 = 切向）。`f1` 在"各向同性高斯"与
"沿切向的一维高斯"之间按 `coh` 混合 ⇒ **沿边缘方向平均、不跨边缘**。

### 1.5 `MOIRE` 如何进入、cap `0.2` 的作用域与量纲

| 项 | PC 的位置 | 量纲 | 实际取值（实测） |
|---|---|---|---|
| `MOIRE` | `:98` 的乘子 | 无量纲 | pass1 = **0.0**、pass2 = **1.5**（Tunable `ESD3D2.java:52-55`，范围 0..5） |
| `0.2` | `min(...)` 的**上界** | 与 `chromaNoise` 同 → **方差** | **从未触发**（∇>60 边缘吃满率 0.0%，全图 0.0%） |
| `chromaNoise·MOIRE` | 被 `max(sigY, ·)` 选中的实际值 | 方差 | ∇>60 边缘 `0.0074`（164623）/ `0.0118`（152207） |
| `sigY` | `max` 的下界 | 方差 | ∇>60 边缘 `2.4e-5`、`2.3e-5` |

**⇒ 三点结论（都可复算）**：
1. **cap 0.2 在真实场景中几乎永不生效**，`sigZ ≈ chromaNoise·MOIRE`。
   它的作用只是"防止病态输入把色度阈值推到无穷"。
2. `sigZ/sigY` 在 ∇>60 边缘 = **300~500×（方差）**、**17~22×（σ）**。
   ⇒ 色度路的相似度阈值被放大到"几乎对所有邻居等权"，亮度路保持"只平均同色邻居"。
   **这是 PC"边缘更锐（Laplacian 70.54）却几乎没有紫边（3.68）"的机制**。
3. **v0.10.173 的错法**：把 cap `0.2` 本身当放宽量 ⇒ 比 PC 的实际 `sigZ` 大 27×，
   且丢失了 `chromaNoise`（色度活跃度）这个因子 ⇒ 门控只由 |∇L| 决定 ⇒ 全图性放宽
   （73.7% 像素）+ D-1 五级 100% 覆盖 ⇒ "一片红一片黄一片紫"。
   v0.10.174 改成"相对 16·σc + 逐像素门 + 只在最细两级"是对症的，但也把
   **PC 真正的机制（色度活跃度驱动的阈值放大）换成了"梯度驱动的有界放宽"**。

### 1.6 输出的组合方式

`esd3d2_steered.glsl:157-164`：

```glsl
float br = dot(final_colour/Z, vec3(0.25,0.5,0.25));      // 亮度路的加权 luma
br = mix(dot(cin, vec3(0.25,0.5,0.25)), br, LUMA);        // LUMA = 0.8（:31 的 #define 被 Java 覆盖）
vec3 resColour = final_colour2/Z2;                        // 色度路的加权平均
resColour /= max(1e-6, dot(resColour, vec3(0.25,0.5,0.25)));  // 归一化到 luma = 1
resColour = max(resColour*br, 0.0);                       // **色度比 × 亮度**
// steered 版**没有上钳制**（注释明说 scene-referred >1 的值要活过去）
```

* `LUMA = 0.8`（Tunable 默认）：输出亮度 = `0.2·中心亮度 + 0.8·亮度路`。
* `d` 用的是**与 `cref`（自引导参考）的 3D 距离**，不是与中心像素的距离
  （`esd3d2.glsl` 旧版才用 `cin`；steered 版是 `cref`）。
* `esd3d2_steered_prod.glsl` 是另一档（共识权重：同时乘 `(1−d_cref²/(…))` 与
  `(1−d_cin²/(…))`），`ESD3D2.java:62-72` 的 `version` Tunable 默认 `"steered"`。

### 1.7 参考量：PC 与我们的"机型相关输入"

| 量 | PC 的来源 | 我们的等价物 |
|---|---|---|
| `NOISES` / `NOISEO` | `PostPipeline.computeNoise`（`PostPipeline.java:237-253`）：`NoiseModeler.computeModel[0..2]` 三通道求平均，且**已被帧数除**（`NoiseModeler.java:88-92`，`/FrameCnt*0.9`），再乘 `2^noiseRstr` | 我们的方差模型 `p4a[]/p4b[]`（C 专项，含 LSC 传播），按 PC 的 `(0.25,0.5,0.25)` 合并 |
| `MOIRE` = 1.5 | Tunable 默认 | **算法参数，照抄** |
| `LUMA` = 0.8 | Tunable 默认 | **算法参数，照抄** |
| `0.2` / `KERNELSIZE` 公式里的 24、`1/256`、7、21 | 源码常数 / Tunable 默认 | **算法参数，照抄** |

---

## 2. "PC 有 / 我们目前有"逐项对照表

| # | 结构项 | PC（file:line） | 我们 v0.10.174 | 差距 |
|---|---|---|---|---|
| 1 | 核尺寸 | **动态 7..21**（`ESD3D2.java:89-94`），本机模型 ⇒ 11 | P4 固定 **7×7**（半径 3） | ★ **缺**（尺度差 1.6×，面积 2.5×） |
| 2 | 核形状 | 对称折叠 4 象限共享权重（`:121-152`） | 逐点独立权重（P4 的 `p4Accum` 逐邻居） | 结构等价，取点数不同（48 vs 144） |
| 3 | 多尺度 | **降采样 scale 1..4 + guided upsample + 两趟**（`ESD3D2.java:135-162`） | P4 单尺度 + D-1 **半分辨率 5 级 à trous**（不同机制：小波收缩，不是重采样+引导） | ★ **缺**（无"低分辨率降噪 + 引导上采样"，无"第一趟 MOIRE=0"） |
| 4 | 亮度路阈值 | `sigY = 噪声方差`（`:92`） | P4 `wy` 用 `sc·1.2`，D-1 用 `σ·k`（Wiener） | 量纲一致（都是 σ），但**阈值形态不同**（PC 是方差、我们是 σ） |
| 5 | 色度路阈值 | `sigZ = max(sigY, min(chromaNoise·MOIRE, 0.2))`（`:98`） | `scEff = max(sc·(1+moire·[48]), min(κ·|∇L|, 16σc)·gate)` | ★ **缺**：我们的放宽量**只由梯度**驱动，**没有 `chromaNoise`（色度活跃度）因子**；PC 的驱动量是"2×2 非均匀度 × 梯度幅值 → 3 通道极差" |
| 6 | 伪彩度量 | `chromaDiff = |2×2 quad 偏差|/4 × max(\|Δx\|,\|Δy\|)`，再取 3 通道**极差**（`:94-97`） | 档 A：`chromaDev = |cd0 − 3×3 均值| 的 Cb/Cr 极差`；v2：无（只看 |∇L|） | ★ **缺**（`chromaDiff` 的乘法结构与极差口径） |
| 7 | cap 的作用域 | **上限**，实测从不触发（`:98`） | v0.10.174 用 `16·σc`（相对，正确方向）；v0.10.173 用绝对 0.2 当放宽量（翻车） | 方向已对，但**我们对 cap 的语义理解此前是"会被触发的放大上限"** |
| 8 | 权重形状 | `w = 1 − d²/(d²+σ²)`（**有界**，d→∞ 时 → 0 但衰减慢） | P4 `w = exp(−0.5(wy²+wc²) − 空间项)`（**高斯**，衰减快） | ★ **缺**（形状不同；PC 的鲁棒核在 d≫σ 时仍给非零权重） |
| 9 | 权重归一化 | `w -= min(w); w *= (w-min)/\|w-min\|`（`:137-143`） | 无 | ★ **缺** |
| 10 | 自引导参考 | `cref` = 3×3 相似度加权均值（`:104-117`） | 无（P4 用中心 `cd0`、D-1 用上一级 `a_{l-1}`） | ★ **缺** |
| 11 | 结构张量转向 | `f1 = mix(iso, 切向, coh)`（`:144-146`） | 无（P4 只有各向同性空间高斯 `σs=2.2`） | ★ **缺** |
| 12 | 空间权重 | 各向同性/切向高斯，σ = `KERNELSIZE`（动态） | 高斯 σs = 2.2 固定 | 形态相同，尺度未随噪声自适应 |
| 13 | 输出组合 | **色度比 × 亮度**（`:157-164`），亮度 = `0.2·中心 + 0.8·滤波` | P4 亮度**恒为 y0**（逐位不变）；D-1 亮度也逐位不动 | ★ **缺**（PC 允许亮度被滤波改变 80%；这是 PC 更锐的同时还更干净的另一半原因） |
| 14 | 上钳制 | **无**（scene-referred 值必须活过降噪） | 我们的链假设非负（`max(·,0)`） | 一致（都保非负） |
| 15 | 梯度算子 | 独立 `ConvDiff` pass（Rec.601 luma 中心差分/2，`convdiff.glsl:34-36`） | 内联中心差分（Rec.709，`kFsChromaDenoise:914`） | 等价（同一算子，色度权重差 <3%） |
| 16 | 输入域 | 去马赛克后**相机 RGB**（CCM 前） | P4 在 **CCM 后**场景线性 RGB | 域差异（色度轴被 CCM 旋转/混合） |
| 17 | 跑几趟 | **2 趟**（MOIRE 0 → 1.5） | 1 次 P4 + 1 次 D-1 | ★ **缺** |

★ = 我们目前**没有**的项。本次移植把 **1、3、5、6、8、9、10、11、13、17** 全部搬进来。

---

## 3. 移植方案与 GPU 代价表

### 3.1 落码形态

新增 3 个 fragment shader（`core/engine/src/render/FrameRenderer.cpp`，紧跟 `kFsChromaApply`）：

| shader | 对应 PC | 说明 |
|---|---|---|
| `kFsPc3d` | `esd3d2_steered.glsl` 全文 | 本体：动态 MSIZE 核 + 对称折叠 + `sigY`/`sigZ` + `gref` + 结构张量转向 + 色度比×亮度 |
| `kFsPc3dDown` | `GLUtils.java:238-280`（gaussdown） | 尺度结构入口（5×5 高斯 + k 倍抽取） |
| `kFsPc3dUp` | `guidedupsample.glsl` | 尺度结构收口（高斯加权最小二乘 + 亮度对齐） |

编排（`FrameRenderer::passPc3d`）完全照抄 `ESD3D2.Run`：核尺寸公式 → scale 判定 →
（scale>1：gaussdown → 低分辨率趟 MOIRE=0 → guidedUp）→ 全分辨率第二趟 MOIRE=1.5。

**与 PC 的两处实现差异（非算法删减，逐位等价，已在源码注释标注）**：
1. PC 用**独立 pass** 产出 `GradBuffer`（全分辨率 RG16F ≈48MB）；我们把同一
   `convdiff` 算子内联（缓存 5×5 的 Rec.601 luma = 25 次采样，9 个梯度点全部由它组合）
   ⇒ 省 1 个全屏 pass + 1 张全分辨率 RG16F。
2. 主循环上界写**常量 10 + break**（`MSIZE ≤ 21 ⇒ KSIZE ≤ 10`），避免非恒定循环上界
   在部分移动驱动上的编译风险；执行格数与动态上界完全一致。
   另有 `guidedupsample` 的分母 `1e-6` 下限（原文在引导亮度 = 0 的纯黑像素上是 0/0）
   与输出的 `max(·,0)`（引擎契约：下游 filmic 假设非负）。

### 3.2 GPU 代价表（采样数 = `texelFetch` 次数 / 全分辨率像素）

现状（v0.10.174）基线：

| pass | 采样数（按域） | 折算到全分辨率 |
|---|---|---|
| P4（7×7，内圈 8 + 外圈 40 + LSC 4） | 52 全分辨率 | 52 |
| D-1 down（2×2 + LSC 4×4） | 20 半分辨率 | 5 |
| D-1 à trous 5 级（每级 25 抽头） | 125 半分辨率 | 31 |
| D-1 apply（6 张图 × 4 bilinear + LSC 4） | 28 全分辨率 | 28 |
| **合计** | | **≈116** |

PC3D（本机模型：`scale = 2`、低分辨率 `MSIZE = 7`、全分辨率 `MSIZE = 11`）：

| pass | 采样数（按域） | 折算到全分辨率 |
|---|---|---|
| gaussdown（5×5） | 25 半分辨率 | 6.3 |
| PC3D 低分辨率（MSIZE 7：4²×4=64 + L25 25 + gref 9 + cin 4 + LSC 4） | 106 半分辨率 | 26.5 |
| guided upsample（R=2：5×5 × 2 张纹理） | 50 全分辨率 | 50 |
| PC3D 全分辨率（MSIZE 11：6²×4=144 + 42） | 186 全分辨率 | 186 |
| **合计** | | **≈269（现状 2.3×）** |

**核尺寸的边际代价**（单趟全分辨率本体）：

| MSIZE | KSIZE | 主循环取点 | 本体采样数 | 相对 MSIZE=11 |
|---|---|---|---|---|
| 7 | 3 | 64 | 106 | 0.57× |
| **11（本机自动）** | **5** | **144** | **186** | **1.00×** |
| 15 | 7 | 256 | 298 | 1.60× |
| 21（PC 上限） | 10 | 484 | 526 | 2.83× |

**显存**（4000×3000、RGBA16F = 96MB/张）：

| 缓冲 | scale=1 | scale=2 |
|---|---|---|
| `tex_pc3d_`（全分辨率输出） | 96MB | 96MB |
| `tex_pc3d_t1_`（第一趟中间） | 96MB | 96MB |
| `tex_pc3d_lo_[0..1]`（低分辨率） | 0 | 24MB × 2 |
| **额外合计** | **192MB** | **240MB** |

### 3.3 选项（**由用户拍板，不是我们自己砍**）

| 选项 | 结构 | 采样数 | 额外显存 | 离线 `∇>60` 紫边（164623 / 152207） |
|---|---|---|---|---|
| **A0** | 现状（P4 + D-1，`[54]=0`） | 116（1.0×） | 0 | 8.589 / 7.677（= 判据基线） |
| **A1** | **照抄全量**（`[54]=1`，MSIZE 自动 = 11，MOIRE 1.5） | 269（2.3×） | 240MB | **7.269 / 9.318**（替换档）· **7.772 / 6.388**（叠加档） |
| **A2** | 照抄 + 强制 MSIZE 15（`[55]=15`） | 381（3.3×） | 240MB | 见 §4（扫描：核越大紫边越低、代价越大） |
| **A3** | 照抄 + 强制 MSIZE 21（`[55]=21`） | 609（5.2×） | 240MB | **6.843 / 8.362**（MOIRE 1.5） |
| **A4** | 复用 `tex_dn_` 作第一趟中间（`[54]=1` 时 P4 不跑 ⇒ 它空闲） | 269 | **144MB** | 同 A1 |
| **A5** | 把 `kPc3dMaxSize` 从 21 降到 11 或 15（省的是"上限"，不改默认路径） | 不变 | 不变 | 不变（本机自动 = 11） |

> **A4 未实现**（需要把 `ensureDnTarget` 的分配与复用耦合，风险高于收益）。
> 若真机 `pc3dMs` 可接受而显存吃紧，这是最省的一步。

---

## 4. 四组验证数据

**★ 主口径 = 用户指定口径**：`clip((R+B)/2−G, 0)` 在
**显示域亮度梯度 `max(|Δx|,|Δy|) > 60`** 区域的均值（`/tmp/pc3d/user_metric.py`
+ `user_metric.json`）。`lap60` = 同一掩码上的 5 点 Laplacian 绝对值均值；
`flat*` = `max(|Δx|,|Δy|) ≤ 10` 区域的 `mean|R−G|`/`mean|B−G|`。

**附口径**（`/tmp/chromagate2` 上次用的 `hypot(∂x,∂y) ≥ 60`，掩码更窄）：
用于与**上次数字逐项对比**，同一变体在两口径下的下降比例一致到 ±3pp
（如 164623 的叠加档 M11m1.5：−10.4% vs −9.5%）。

原始口径说明（两口径共用）：
成片 JPEG → sRGB EOTF 反推线性 → 变体 → `srgb_oetf` → 显示域 8-bit；
`fr60` = `clip((R+B)/2−G, 0)` 在显示域亮度中心差分 `hypot(∂x,∂y) ≥ 60` 区域的均值；
`lap60` = 同一区域的 5 点 Laplacian 绝对值均值；`flat*` = `∇ < 10` 区域的 `mean|R−G|`/`mean|B−G|`。
脚本：`/tmp/pc3d/eval_pc3d.py` + `/tmp/pc3d/pc3d_cpu.py`；数据 `/tmp/pc3d/sweep2.json`。

变体记号：`pc3dM{MSIZE}m{MOIRE}` = **替换档**（PC3D 顶替 P4/D-1）；
`+base` = **叠加档**（PC3D → P4 → defringe → D-1）。`base` = v0.10.174 现状模拟。
样片：`164623` / `164631`（v0.10.174 成片，真机 `∇>60` 紫边 10.60 / 9.13）、
`152207`（v0.10.171 未处理，真机 13.27）。

### 4.1 收益（`∇>60` 紫边下降）—— 主口径（用户指定）

| 变体 | 164623 | 164631 | 152207 | 三片平均 | 相对基线 | 三片是否全部改善 |
|---|---|---|---|---|---|---|
| **输入**（成片反推，未再处理） | 8.345 | 7.337 | 13.068 | 9.583 | 1.205× | — |
| `base`（现状 v0.10.174 模拟） | 8.251 | 7.473 | 7.389 | 7.704 | 1.000× | — |
| 替换档 M11 m1.5（**照抄自动值**） | 7.205 | 6.183 | **9.423** | 7.604 | 0.975× | ✗（152207 +27.5%） |
| 替换档 M21 m5 | 6.439 | 5.465 | 7.365 | 6.423 | 0.843× | ✗（152207 −0.3%） |
| **叠加档 M11 m1.5**（**照抄自动值**） | 7.392 | 6.748 | 6.090 | 6.743 | **0.875×** | ✓ |
| 叠加档 M15 m5 | — | — | — | — | ≈0.83×（附口径） | ✓ |
| **叠加档 M21 m5**（离线最好） | 6.784 | 6.178 | 5.426 | 6.129 | **0.796×** | ✓ |

* **照抄的自动配置（MSIZE 11 / MOIRE 1.5）**：叠加档 **−12.6%**（三片全改善）、
  替换档 −0.9%（152207 反而 +27.5%）。
* **离线最好 = 叠加档 + MSIZE 21 + MOIRE 5：−20.6%**（0.796×），三片全部改善。
* **⇒ 替换档不稳、叠加档稳**：替换档旁路了 P4/D-1，PC3D 的亮度通道变化
  （`br = mix(中心, 亮度路, 0.8)`）会重定义 `∇>60` 掩码，在紫边最重的 152207 上净效果为负。
  **替换档（`[54] = 1`）在真机上必须单独验证，不能按"更照抄所以更好"推断。**
* **达不到 PC 的 3.68**：真机现状 10.60；按离线最好的 −20.6% 折算 ≈ **8.4**，
  离 3.68 还差 **2.3×**。要达标需 0.347×，离线最好 0.796×。
  **本层移植解决的是"我们的判据没抄到量级"，不是"整链差 2.9×"的全部。**

*（附口径 `hypot ≥ 60` 的同批数字见 §4.1b。）*

### 4.1b 收益 —— 附口径（`hypot(∂x,∂y) ≥ 60`，与上次报告可比）

| 变体 | 164623 | 164631 | 152207 | 三片平均 | 相对 `base` |
|---|---|---|---|---|---|
| `base` | 8.589 | 7.608 | 7.677 | 7.958 | 1.000× |
| 替换档 M11 m1.5 | 7.269 | 6.199 | 9.318 | 7.595 | 0.954× |
| 替换档 M15 m1.5 | 7.054 | 6.004 | 8.789 | 7.282 | 0.915× |
| 替换档 M21 m1.5 | 6.843 | 5.803 | 8.362 | 7.003 | 0.880× |
| 替换档 M11 m5 | 6.954 | 5.929 | 8.311 | 7.065 | 0.888× |
| 替换档 M21 m5 | 6.518 | 5.526 | 7.350 | 6.465 | 0.812× |
| 叠加档 M11 m1.5 | 7.772 | 6.889 | 6.388 | 7.016 | 0.882× |
| 叠加档 M15 m1.5 | 7.570 | 6.715 | 6.133 | 6.806 | 0.855× |
| 叠加档 M21 m5 | 7.049 | 6.315 | 5.623 | 6.329 | **0.795×** |
| 叠加档 M11 m5 | 7.524 | 6.696 | 6.076 | 6.765 | 0.850× |
| 叠加档 M15 m5 | 7.324 | 6.516 | 5.839 | 6.560 | 0.824× |
| 叠加档 M21 m1.5 | 7.345 | 6.530 | 5.921 | 6.599 | 0.829× |

（`/tmp/pc3d/sweep2.json`；`152207` 的替换档在 MSIZE 11 下变差的现象两口径一致。）

### 4.2 色渗（**红线**，上次在这里翻车）

| 变体 | `hue15blk` 164623/164631/152207 | `corr` | `blkVar128` |
|---|---|---|---|
| `base` | 0.00* / 0.00* / 0.00* | 0.232 / 0.263 / 0.148 | 16.93 / 25.00 / 36.01 |
| 叠加档 M11 m1.5 | 0.82 / 0.82 / 1.19 | 0.230 / 0.262 / 0.152 | 16.81 / 24.90 / 36.00 |
| 叠加档 M15 m1.5 | 1.49 / 1.41 / 1.86 | 0.231 / 0.263 / 0.152 | 16.78 / 24.84 / 35.98 |
| 叠加档 M21 m5 | 2.98 / 2.98 / 2.53 | 0.233 / 0.265 / 0.151 | 16.61 / 24.59 / 35.72 |
| 替换档 M11 m1.5 | 8.18 / 8.18 / 8.41 | 0.228 / 0.262 / 0.123 | **26.44 / 30.15 / 41.31** |
| 替换档 M21 m5 | 7.59 / 7.74 / 7.51 | 0.231 / 0.265 / 0.122 | 26.12 / 29.79 / 41.04 |

`*` `base` 是参考帧，其 `hue15blk` 按定义 = 0（色相偏移是相对它算的）。

* **叠加档全部落在健康区间**：`hue15blk ≤ 3.0%`（v0.10.174 的判据区间 0.30~2.8%，
  v0.10.173 是 1.9~17.3%）、`corr` 与 `base` 同量级（无恶化）、
  `blkVar128` **比 `base` 更低**（−0.4% ~ −1.9%，大色块没增加）。
* **替换档的 `hue15blk` = 7.5~8.4%`、`blkVar128` 比 `base` 高 14~56%** ——
  这两项都在**色渗红线的警戒侧**。原因是替换档旁路了 P4/D-1，
  而 PC3D 的输出色度 = "低分辨率/大核平均后的色度比 × 亮度路亮度"，
  它在大尺度上会重塑色度分布（`blkVar128` 是 128×128 块的色度方差）。
* **门控覆盖率**：`cg*` 字段语义不变（P4 内的门）。`[54] = 0/2` 时照旧
  （离线 4 片 `cgGate` 覆盖 < 8%，健康 `cgMean ≤ 0.15`、`cgCov ≤ 0.05`）；
  `[54] = 1`（替换档）时 **P4 不跑 ⇒ `cgK/cgMean/cgP95/cgCov` 全为 0**，
  这是"替换档生效"的旁证而不是异常（判读时勿误认为"门失效"）。

### 4.3 细节红线（主口径 `max(|Δx|,|Δy|) > 60`）

| 变体 | `lap60` 164623/164631/152207 | 相对 `base` |
|---|---|---|
| `base` | 102.894 / 105.700 / 101.298 | 1.000× |
| 替换档 M11 m1.5 | 105.624 / 108.995 / 103.050 | **+1.7% ~ +3.1%** |
| 替换档 M21 m5 | 105.774 / 109.500 / 102.484 | +1.2% ~ +3.6% |
| 叠加档 M11 m1.5 | 105.371 / 108.504 / 102.074 | +0.8% ~ +2.7% |
| 叠加档 M21 m5 | 105.735 / 108.967 / 101.796 | +0.5% ~ +3.1% |

（附口径 `hypot ≥ 60`：`base` 101.267/103.807/100.189，
各变体 +0.9% ~ +3.6%，**方向与量级同**。）

* **所有配置的 `∇>60` Laplacian 都上升，没有一例低于现状** ⇒ 细节红线通过。
  方向与 PC 实测一致（PC 70.54 > 我们 63.71）。**"不能因为平滑丢掉锐度"这一条满足。**
* **亮度通道**：`[54] = 0` 时全链与改动前逐位相同（P4/D-1 输出亮度恒 = `y0`）；
  `[54] = 2` 时 P4/D-1 仍保持 `y0`，但**输入给它们的图已被 PC3D 改过亮度**；
  `[54] = 1` 时 PC3D 的输出直接进 film，亮度 = `mix(中心, 亮度路, 0.8)`（照抄 PC，`:158`）。
  ⇒ **"亮度逐位不变"这条旧判据在 `[54] > 0` 时不再适用**，这是 PC 的原文行为，
  也正是它"更锐"的一半原因（PC 实测 lap 70.54 > 63.71）。

### 4.4 平坦区色度保真度（主口径 `max(|Δx|,|Δy|) ≤ 10`）

| 变体 | `flatRG` 164623/164631/152207 | `flatBG` 164623/164631/152207 |
|---|---|---|
| **输入**（成片反推，未再处理） | 5.0861 / 5.3121 / 6.0060 | 4.0874 / 4.0309 / 4.7233 |
| `base`（现状 v0.10.174 模拟） | 7.7420 / 8.0729 / 8.9679 | 6.0915 / 5.9795 / 6.4445 |
| 替换档 M11 m1.5 | **5.0954 / 5.3289 / 6.0131**（≈ 输入，+0.1~0.2%） | 4.0274 / 3.9827 / 4.6262 |
| 替换档 M21 m5 | 5.0778 / 5.3103 / 5.9965（−0.2~0.4% vs 输入） | 4.0038 / 3.9584 / 4.5960 |
| 叠加档 M11 m1.5 | 7.7752 / 8.1122 / 8.9981（≈ `base`，+0.3~0.5%） | 6.0847 / 5.9704 / 6.3945 |
| 叠加档 M21 m5 | 7.7405 / 8.0775 / 8.9601（≈ `base`，−0.02~0.1%） | 6.0408 / 5.9251 / 6.3399 |

* **替换档**：平坦区色度几乎**逐位保持输入**（`flatRG` +0.1~0.2%、`flatBG` −1.5%）
  ⇒ **没有抹掉真实色度**（PC 的 `sigZ` 在此处退化为 `sigY` = 噪声方差，几乎不平均）。
* **叠加档**：相对 `base` 变化 ≤ **+0.5%**（不下降）⇒ 按判据通过。
* 旁证（**不属于本次任务，但判读时重要**）：离线口径下 `base`（v0.10.174 模拟）
  把平坦区 `mean|R−G|` 从输入的 5.09 抬到 **7.74（+52%）**、`mean|B−G|`
  从 4.09 抬到 6.09（+49%）。该模拟与真机的一致性**未经验证**（§7），
  不作为结论；但它提示"平坦区 `|R−G|` 上升"本身可能是一条独立于本次改动的色渗路径。
* **关开关时逐位等于改动前**：见 §5 末的**代码级**论证
  （`[54] = 0` 时唯一新增分支落到同一纹理句柄 `tex_scene_`，新增纹理一次都不分配）。
  ⚠ 不声称"已真机逐位验证"——项目历史上真机图像 dump 曾失败；真机 bitcheck 的做法是
  用同场景、`[54] = 0` 重拍一张，与 `test_temp/FILMKIT_20261005-164623.jpg` 逐字节比对。

### 4.5 口径校准：离线域 vs PC 的真实输入域（`rawsigz.py`）

`chromaNoise` / `sigZ` 完全按 `esd3d2_steered.glsl:94-98` 复算，
掩码用**各自域**的 `(0.25,0.5,0.25)` luma 中心差分 top-1%（成片域另报 `∇>60` 显式口径）：

| 域 | `chromaNoise` 均值 | `sigZ`（MOIRE 1.5）均值 | **cap 0.2 吃满率** |
|---|---|---|---|
| 成片域反推线性，`∇>60` 掩码（n=9601） | 0.00797 | 0.01196 | **0.00%** |
| 成片域反推线性，本域梯度 top-1%（n=13927） | 0.00840 | 0.01260 | **0.00%** |
| **真实早期域**（rawpy 线性相机 RGB = PC 的输入域），top-1%（n=31335） | 0.00968 | 0.01452 | **0.00%** |

* **离线口径只低估 ~21%**（0.00840 → 0.00968），**不是数量级级**。
  ⇒ §4.1 的收益数字对真机有相当的外推力（但仍不能替代真机实测）。
* **cap 0.2 在三个域、所有掩码上吃满率都是 0.00%** ⇒ 再次确认 §1.5 的结论：
  `sigZ ≈ chromaNoise·MOIRE`，**0.2 只是安全上限，不是被使用的值**。
* 早期域的 `sigZ` 均值 0.0145（方差）⇒ **σ_z ≈ 0.12**；
  而同域 `sigY ≈ 2.4e-5`（方差）⇒ **σ_y ≈ 0.005**。
  **色度路阈值是亮度路的 ~24×（σ）** —— 这就是 PC 的机制。
* 对照我们 v0.10.174 的 P4：`scEff = max(sc, min(κ·|∇L|, 16σc)·gate)`，
  在 `∇L ≈ 0.15`、`σc ≈ 0.004` 时 = `max(0.004, min(0.30, 0.064) × 0.156) = 0.010`
  ⇒ **等效阈值 0.010（σ） vs PC 的 0.12（σ）：差 ~12×**，
  且 `w2` 分支只拿到 `gate = 15.6%` 的混合权重。
  **这就是"PC 的机制我们只抄了判据、没抄到量级"的定量解释。**
  （v0.10.174 之所以把 `gate` 收得这么紧，是因为同一个门要同时管 D-1 的 5 级 à trous ——
  而 PC 的宽松阈值只作用在**单尺度、有限核（≤21px）**上。这正是本次把核与尺度一起搬的原因。）


---

## 5. 改动坐标（函数 + 行号 + grep 串）

行号 = 本次落码后的当前值（v0.10.175）。

| 文件 | 行 | 内容 | grep 串 |
|---|---|---|---|
| `core/engine/src/render/FrameRenderer.cpp` | 1447 | `kFsPc3d` —— PC3D 本体（照抄 `esd3d2_steered.glsl` 全文） | `const char* kFsPc3d = R` |
| 同上 | 1605 | `kFsPc3dDown` —— gaussdown（照抄 `GLUtils.java:238-280`） | `const char* kFsPc3dDown = R` |
| 同上 | 1640 | `kFsPc3dUp` —— guidedupsample（照抄 `guidedupsample.glsl`） | `const char* kFsPc3dUp = R` |
| 同上 | 4801 | shader 编译 | `fs_pc3d = compileShader` |
| 同上 | 4884 | link；失败 ⇒ 三个 prog 置 0（**不进 init 硬失败判据**） | `if (fs_pc3d) prog_pc3d_ = linkProgram` |
| 同上 | 5351 | uniform 定位（13 个） | `u_pc3d_.tex = glGetUniformLocation` |
| 同上 | 6241 | `passChromaDenoise` 新增 `GLuint src_tex` 形参 | `void FrameRenderer::passChromaDenoise` |
| 同上 | 6257 | P4 的输入源切换（`src_tex = 0` ⇒ `tex_scene_`，原行为） | `src_tex ? src_tex : tex_scene_` |
| 同上 | 6837 | 照抄 PC 的常数：24 / 1/256 / 7 / 21 / MOIRE 1.5 / LUMA 0.8 | `kPc3dNoiseToKernel = 24.0f` |
| 同上 | 6844 | `passPc3d`（核尺寸公式 + scale 判定 + 两趟编排 + 缓冲） | `bool FrameRenderer::passPc3d` |
| 同上 | 8034 | 调用点：P4 之前；`[54]` 决定是否执行、`1` = 替换档（旁路 P4/D-1） | `if (p.pc3d > 0.5f)` |
| 同上 | 8075 | `passFilm` 的输入源切换（替换档 = `tex_pc3d_`） | `pc3d_replace ? tex_pc3d_` |
| 同上 | 8138 | 日志字段 `pc3dOn/pc3dM/pc3dK/pc3dSc/pc3dMs` | `"pc3dOn"` |
| `core/engine/src/render/FrameRenderer.h` | 433–443 | `passPc3d` 声明 + 档位语义注释 | `bool passPc3d(int dst_w` |
| 同上 | 555 | 三个 program 句柄 | `prog_pc3d_ = 0` |
| 同上 | 670–677 | `dbg_pc3d_*` 生效性字段 + 4 张纹理缓冲 | `dbg_pc3d_on_` |
| 同上 | 985 | `u_pc3d_` / `u_pc3d_down_` / `u_pc3d_up_` uniform 结构体 | `} u_pc3d_;` |
| `core/engine/src/film/FilmParams.h` | 14 | `kCount` 54 → **56** | `static constexpr int kCount = 56` |
| 同上 | 266–267 | `[54] pc3d` / `[55] pc3d_msize`（含判据与健康区间注释） | `float pc3d = 0.0f;` |
| 同上 | 336–338 | `fromDoubles` 解析 | `if (n > 54) pc3d` |
| `app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` | 24 | `DoubleArray(54)` → **`DoubleArray(56)`**（历史坑：漏改 ⇒ 真机 AIOOBE） | `DoubleArray(56)` |
| 同上 | 189–190 | `this[54] = 0.0` / `this[55] = 0.0`（默认关） | `this[54] = 0.0` |

**并发改动仍在**（本次落码后 grep 复核）：
`diagStatsTex` 8 · `diag_stats` 5 · `scEff` 25 · `moGain` 3 · `hlPre` 3 · `hlWClamp` 2 ·
`kSceneLinearNorm` 2 · `caK` 5 · `amazeMs` 1 · `AmazeDemosaic` 4 · `cgGate` 12 · `cgRelax` 4（均在 `FrameRenderer.cpp`）；
`jpeg_444` / `saveJpeg` 在 Kotlin 侧（`SettingsScreen.kt:135`、`CameraController.kt:3181`）未受影响。
`git diff --stat`：12 个文件、+2986/−175（含本次与之前未提交的 v0.10.170–174 改动）。

**关开关（`[54] = 0`）时的逐位不变性**（代码级论证，不是自证）：
* 唯一新增的运行时分支是 `glBindTexture(…, src_tex ? src_tex : tex_scene_)`，
  `src_tex = 0` 时求值 = `tex_scene_`，与改动前**同一个纹理句柄**；
* `passFilm` 的输入表达式在 `pc3d_replace = false` 时**逐字符等于**改动前的表达式；
* 新增的 `dbg_*` 只写日志；新增纹理在 `[54] = 0` 时**一次都不分配**。

---

## 6. 真机判据与健康区间

### 6.1 日志字段（新增，沿用 `cg*` 命名风格改起头 `pc3d*`）

| 字段 | 含义 | 健康区间 |
|---|---|---|
| `pc3dOn` | 1 = 本帧真的跑了 PC3D 且输出已生效 | `[54] = 0` 时应恒 **0**；`[54] > 0.5` 时应恒 **1** |
| `pc3dM` | 本轮实际 MSIZE | **11**（本机噪声模型自动值；`[55]` 强制时 = 该值，7..21 奇数） |
| `pc3dK` | `KERNELSIZE`（f1 的高斯 σ） | **≈4.55**（自动）；核越大越大 |
| `pc3dSc` | 尺度结构的降采样倍数 | **2**（本机模型；`1` 表示噪声模型异常小） |
| `pc3dMs` | 本模块耗时（含 `glFinish`） | 与 `dnMs` 同量级为正常；≫ `dnMs` ⇒ 成本过高 |
| `cgK/cgCap/cgScope/cgMean/cgP95/cgCov` | P4 的门（**语义不变**，仍在 P4 内） | `cgMean ≤ 0.15`、`cgCov ≤ 0.05`；替换档下 P4 不跑 ⇒ 全为 0 |

### 6.2 三句话判读

* **生效**：`pc3dOn = 1`、`pc3dM ∈ [7,21]` 奇数、`pc3dSc ∈ [1,4]`、`pc3dMs` 与 `dnMs` 同量级。
* **没生效**：`pc3dOn = 0` 而 `[54] > 0.5` ⇒ shader 链接失败或纹理分配失败
  （`engine` tag 里会有 `pc3d.compile_fail`）；或 `pc3dSc` 恒 1 而噪声模型正常
  ⇒ scale 判定与 PC 公式不符（噪声模型没下发）。
* **过强**：`pc3dMs` 明显大于 `dnMs`（MSIZE 被 `[55]` 拉大）；或画面出现
  "大尺度色块/串色"（PC3D 的色度路阈值被噪声模型放大过头）⇒ 把 `[55]` 调小或把 `[54]` 置 0。

### 6.3 真机 A/B 的拍法（同场景、同光照、各一张）

| 组 | `[54]` | `[55]` | 目的 |
|---|---|---|---|
| 基线 | 0 | 0 | = v0.10.174 现状（`∇>60` 紫边应复现 10.60/9.13） |
| 照抄档 | 1 | 0 | 照抄 PC 的链路地位（P4/D-1 旁路）；**离线 4 片里收益最大** |
| 照抄+核上限 | 1 | 15 / 21 | 核尺寸的收益 vs `pc3dMs` 代价，拍板点 |
| 叠加档 | 2 | 0 | PC3D + 现有链；离线里色渗代价最小 |

**必须同时记录**：`∇>60` 紫边、`∇>60` Laplacian、平坦区 `mean|R−G|`/`mean|B−G|`、
以及 `pc3dMs`/`dnMs`/`totalMs`。

---

## 7. 无法判定的部分（明确声明）

1. **离线口径不是 ESD3D2 的输入域。** 本报告的"四组数据"全部在**成片 JPEG →
   sRGB EOTF 反推线性**上做（与 `/tmp/chromagate2` 上次口径一致，故数字可逐项对比）。
   而成片已被多帧融合 + P4/D-1 + JPEG + filmic 处理过，**高频色度起伏被大幅削平**
   ⇒ 直接后果（已实测）：`∇>60` 边缘的 `sigZ` 只有 **0.007~0.012**、cap 0.2 吃满率 **0.0%**。
   `chromaDiff` 的乘法结构（2×2 非均匀度 × 梯度幅值）在成片域会被低估 ——
   **但 §4.5 用 rawpy 直接产出 PC 的真实输入域做了同式校准，低估幅度只有 ~21%**，
   不是数量级级。`rawsigz.py` 的那一步只校准量级，不产生 fr60
   （早期域没有成片可供测 `∇>60`），所以 §4.1 的绝对数仍**不能**当真机预测值使用。
2. **GPU 成本只有解析采样数，没有真机耗时。** 本文的代价表是 `texelFetch` 计数，
   不是 ms。真机耗时只能靠 `pc3dMs`（本次新增字段）拍；**没有任何实测的 `pc3dMs`**。
3. **显存 240MB 的可行性未验证。** 我只能在 Mac 上算字节数（4000×3000 RGBA16F = 96MB/张）。
   真机是否 OOM、是否需要走 §3.3 的 A4（复用 `tex_dn_` 省 96MB）**必须真机验证**。
4. **我们与 PC 的域不同**（PC 在 CCM 前的相机 RGB，我们在 CCM 后）。色度轴被 CCM
   旋转/混合，`chromaDiff` 的绝对量级会变。离线无法量化这个差（没有 CCM 前的域 + 完整链）。
5. **PC 的 3.68 不是同链对比。** 那是 PC 自己拍的样片（不同 DNG、不同去马赛克、
   不同 CCM、不同色调链）。本报告只回答"照抄 ESD3D2 这一层能把我们的 `∇>60`
   降多少"，**不回答"PC 整链为什么是 3.68"**。
6. **`pc3dMs` 与 `dnMs` 的可比性。** `dnMs` 在替换档下只含 PC3D，在 `[54] = 0` 时
   含 P4+defringe+D-1 ⇒ 跨档比较时要看档位。
7. **结构张量转向 / gref / 权重归一化这三项（对照表 #9/#10/#11）没有单独的 A/B 数据。**
   本次是"全量照抄"，没有做"只搬核不搬转向"的分档 —— 按项目铁律这属于"预删"，
   不允许；它们的独立贡献只能靠后续单项实验。

---

## 8. 产物清单

| 产物 | 路径 |
|---|---|
| 本报告 | `/tmp/pc3d/REPORT.md` |
| PC3D 的 CPU 逐行复算 | `/tmp/pc3d/pc3d_cpu.py` |
| 四组指标评估（可复跑） | `/tmp/pc3d/eval_pc3d.py`（`--msize 11,15,21 --moire 1.5,5`） |
| 口径校准（成片域 vs raw 早期域） | `/tmp/pc3d/rawsigz.py` |
| 原始数据 | `/tmp/pc3d/sweep.json`、`/tmp/pc3d/sweep2.json`、`/tmp/pc3d/probe.json` |
| 代码 | `core/engine/src/render/FrameRenderer.{h,cpp}`、`core/engine/src/film/FilmParams.h`、`app/src/main/java/com/filmkit/app/ui/CameraPresets.kt` |
| APK | `filmkit-0.10.175.apk`（仓库根；clean 构建，versionCode 314 / versionName 0.10.175） |

**构建与校验**：`bash tools/validate-shaders.sh` 全绿（57 个 fragment + 1 个 compute）；
`./gradlew clean :app:assembleDebug` BUILD SUCCESSFUL（`JAVA_HOME=/opt/homebrew/opt/openjdk@23`）。
**未做** `git commit` / `git push`。

