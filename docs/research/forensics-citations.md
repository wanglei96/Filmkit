# 取证清单（逐字引用）— HDR+ / Night Sight / ISP LTM / MFNR

工具与环境说明（影响可引用性，务必先读）：

- `web_fetch` 在本会话中**可**访问：`arxiv.org`、`ar5iv.labs.arxiv.org`、`export.arxiv.org`。
- `web_fetch` 在本会话中**不可**访问（均返回 `Error: web fetch failed: TypeError: fetch failed`）：`www.hdrplusdata.org`、`hdrplusdata.org`、`research.google`、`ai.googleblog.com`、`www.qualcomm.com`、`static.googleusercontent.com`。
- `web_fetch` **不支持 PDF**：对 PDF 返回 `Error: unsupported content type "application/pdf"`。因此 HDR+ 论文正文是用 `curl` 下载 PDF 后经 `pypdf` 提取的文本（下文每条已标注）。
- 因此下文区分三种来源标记：`[web_fetch]`、`[curl+pypdf，web_fetch 不支持 PDF]`、`[curl]`。
- PDF 提取文本保留了原文件的连字（`ﬁ`/`ﬂ`）与个别断行粘连（如 `signalto-noise`、`ﬁxed`），未做改写。

---

## 0. 编号核验：任务给出的三个 arXiv 编号全部指向无关论文

### https://arxiv.org/abs/1609.01618  `[web_fetch]`

页面标题逐字：

> # Quantum Physics
> **arXiv:1609.01618** (quant-ph)
> \[Submitted on 6 Sep 2016\]
> # Title:Valid lower bound for all estimators in quantum parameter estimation
> Authors:Jing Liu, Haidong Yuan

Abstract 逐字（章节：Abstract）：

> The widely used quantum Cramer-Rao bound (QCRB) sets a lower bound for the mean square error of unbiased estimators in quantum parameter estimation, however, in general QCRB is only tight in the asymptotical limit.

→ 与 "Burst photography for high dynamic range and low-light imaging on mobile cameras" 无关。HDR+ 论文**没有** arXiv 版本（见下）。

### https://ar5iv.labs.arxiv.org/html/1609.01618  `[web_fetch]`

正文标题逐字（章节：页面标题）：

> # Valid lower bound for all estimators in quantum parameter estimation
> Haidong Yuan ... Department of Mechanical and Automation Engineering, The Chinese University of Hong Kong

### https://arxiv.org/abs/2104.00674  `[web_fetch]`

页面标题逐字：

> # Computer Science > Computer Vision and Pattern Recognition
> **arXiv:2104.00674** (cs)
> \[Submitted on 1 Apr 2021\]
> # Title:PhySG: Inverse Rendering with Spherical Gaussians for Physics-based Material Editing and Relighting

Abstract 逐字（章节：Abstract）：

> We present PhySG, an end-to-end inverse rendering pipeline that includes a fully differentiable renderer and can reconstruct geometry, materials, and illumination from scratch from a set of RGB input images.

→ 与 "Night Sight" 无关。

### https://ar5iv.labs.arxiv.org/html/2104.00674  `[web_fetch]`

正文标题逐字：

> # PhySG: Inverse Rendering with Spherical Gaussians for
> Physics-based Material Editing and Relighting
> Kai Zhang∗ Fujun Luan∗ Qianqian Wang Kavita Bala Noah Snavely
> Cornell University

### https://arxiv.org/abs/1704.05173  `[web_fetch]`

页面标题逐字：

> # Astrophysics > Astrophysics of Galaxies
> **arXiv:1704.05173** (astro-ph)
> # Title:Discovery of ram-pressure stripped gas around an elliptical galaxy in Abell 2670

→ 与 Gharbi et al. "Deep Bilateral Learning for Real-Time Image Enhancement" (HDRNet) 无关。

### https://export.arxiv.org/api/query?search_query=ti:%22Night+Sight%22&max_results=10  `[web_fetch]`

逐字（XML）：

> `<opensearch:totalResults>0</opensearch:totalResults>`

→ arXiv 中**标题含 "Night Sight" 的记录为 0**；该论文不存在于 arXiv。

### https://export.arxiv.org/api/query?search_query=ti:%22Deep+Bilateral+Learning%22&max_results=5  `[web_fetch]`

逐字（XML 条目）：

> `<id>http://arxiv.org/abs/1707.02880v2</id>`
> `<title>Deep Bilateral Learning for Real-Time Image Enhancement</title>`
> `<arxiv:comment>12 pages, 14 figures, Siggraph 2017</arxiv:comment>`
> `<arxiv:journal_ref>ACM Trans. Graph. 36, 4, Article 118 (2017)</arxiv:journal_ref>`

→ HDRNet 的正确编号是 **1707.02880**，不是 1704.05173。

### https://export.arxiv.org/api/query?search_query=all:%22Night+Sight%22&max_results=10  `[web_fetch]`

逐字（唯一命中条目的摘要片段）：

> `<id>http://arxiv.org/abs/1905.03277v2</id>`
> `<title>Handheld Multi-Frame Super-Resolution</title>`
> "Specifically, the algorithm is the basis of the Super-Res Zoom feature, as well as the default merge method in Night Sight mode (whether zooming or not) on Google's flagship phone."

### https://export.arxiv.org/api/query?search_query=all:%22Handheld+Mobile+Photography+in+Very+Low+Light%22&max_results=5  `[web_fetch]`

逐字（XML 条目）：

> `<id>http://arxiv.org/abs/1910.11336v1</id>`
> `<title>Handheld Mobile Photography in Very Low Light</title>`
> `<arxiv:journal_ref>ACM Trans. Graph.38, 6, Article 164 (November 2019)</arxiv:journal_ref>`

→ 这是 Google Night Sight 系统的**一手论文**（作者含 Marc Levoy、Samuel W. Hasinoff、Jonathan T. Barron 等），下文第 2 节使用。

---

## 1. HDR+ 论文（Hasinoff et al., SIGGRAPH Asia 2016）

### https://www.hdrplusdata.org/hdrplus.pdf

**未找到（fetch 失败）。** 具体证据：

- `web_fetch`：`Error: web fetch failed: TypeError: fetch failed`
- `curl`：HTTP 302，`location: https://static.googleusercontent.com/media/hdrplusdata.org/en//hdrplus.pdf`；该目标主机连接超时（curl `000`）。

### https://graphics.stanford.edu/papers/hdrp/hasinoff-hdrplus-sigasia16.pdf  `[curl+pypdf，web_fetch 不支持 PDF]`

- `web_fetch` 结果：`Error: unsupported content type "application/pdf"`
- `curl` 结果：HTTP 200，21,741,330 bytes，PDF 1.5，12 页；`pypdf` 提取 70,886 字符。
- 该 PDF 标题页逐字：`Burst photography for high dynamic range and low-light imaging on mobile cameras / Samuel W. Hasinoff Dillon Sharlet Ryan Geiss Andrew Adams Jonathan T. Barron Florian Kainz Jiawen Chen Marc Levoy / Google Research`
- 版权行逐字：`SA '16 Technical Papers, December 05 - 08, 2016, Macao ... DOI: http://dx.doi.org/10.1145/2980179.2980254`

论文实际章节标题（逐字）：`1 Introduction`、`2 Overview of capture and processing`、`3 Auto-exposure`、`4 Aligning`、`5 Merging Frames`、`6 Finishing`、`7 Results`、`8 Conclusions`。

#### a) 连拍张数、快门/ISO（增益）选择策略

章节：**3 Auto-exposure → "Exposure factorization"**（逐字）：

> Exposure factorization Translating the exposure for capture into sensor settings entails factoring it into exposure time and gain (ISO setting). For this step we use a ﬁxed schedule that balances motion blur against noise. For the brightest scenes we hold gain at its minimum level, allowing exposure times to increase up to 8 ms. Next, as scenes become darker, we hold exposure time at 8 ms and increase gain up to 4×. Finally, we increase exposure time and gain simultaneously, up to our maximums of 100 ms exposure time and 96× gain, increasing them proportionally in log space. To maximize SNR, we apply as much gain in analog form as possible [Martinec 2008]. Any gain above the limit supported by the camera sensor we apply digitally in our pipeline.

章节：**3 Auto-exposure → "Burst size"**（逐字）：

> Burst size In addition to determining exposure time, gain, and dynamic range compression, we must also decide how many frames to capture in a burst. The number we capture, N, is a tradeoff. In low light, or in very high dynamic range scenes where we will be boosting the shadows later, we want more frames to improve signalto-noise ratio, but they take more time and memory to capture, buffer, and process. In bright scenes, capturing 1–2 images is usually sufﬁcient, although more images are still generally beneﬁcial in combating camera shake blur. In practice, we limit our bursts to 2–8 images, informing the decision using our model for raw image noise (see section 5 for more detail).

章节：**Abstract**（逐字）：

> First, we do not use bracketed exposures. Instead, we capture frames of constant exposure, which makes alignment more robust, and we set this exposure low enough to avoid blowing out highlights.

#### b) 噪声随帧数下降的表述

章节：**Figure 1 caption**（逐字）：

> Fusing a burst of images increases the signal-to-noise ratio, making aggressive spatial denoising unnecessary.

章节：**5 Merging Frames**（首段，逐字）：

> The key premise of burst photography is that we can realize noise reduction by combining multiple observations of the scene over time.

章节：**Abstract**（逐字）：

> We describe a computational photography pipeline that captures, aligns, and merges a burst of frames to reduce noise and increase dynamic range.

**明确结论：全文检索未找到 `√N`、`square root`、`averaging reduces noise` 的字面表述。** 检索计数：`√` = 0 次、`square root` = 0 次、`1/N` = 0 次。唯一与根号相关的字面是 tile 噪声方差估计用的 `root-mean-square (RMS)`（章节 5 Merging Frames → "Noise model and tiled approximation"）：

> For each tile, we compute the variance by evaluating our noise model using a single value, the root-mean-square (RMS) of the samples in the tile.

（未找到 √N 量化表述 ≠ 论文否认降噪；论文只给了定性表述，如 `less noise`：章节 8 Conclusions 逐字 "…fewer blown-out highlights or crushed shadows, less noise, less motion blur, better color, sharper details, and more texture."）

#### c) 对齐方式（分层/金字塔、运动/鬼影处理）

章节：**4 Aligning → "Hierarchical alignment"**（逐字）：

> Hierarchical alignment To align an alternate frame to our reference frame, we perform a coarse-to-ﬁne alignment on four-level Gaussian pyramids of the downsampled-to-gray raw input. As ﬁgure 5 illustrates, we produce a tile-based alignment for each pyramid level, using the alignments from the coarser scale as an initial guess.

章节：**Figure 5 caption**（逐字）：

> At the ﬁnest pyramid level (bottom right), tiles are 32× 32 pixels and the maximum displacement is 64 pixels. The large regions of saturated colors show that a hierarchical algorithm is essential; our method supports displacements up to 169 pixels.

章节：**4 Aligning**（下采样策略，逐字）：

> We implement this strategy by averaging 2×2 blocks of Bayer RGGB samples, so that we align downsampled 3 Mpix grayscale images instead of 12 Mpix raw images.

章节：**5 Merging Frames**（运动/对齐失败的鲁棒性，逐字）：

> However, to be useful in a photographic application, our merging method must be robust to alignment failures. As ﬁgure 6 shows, while alignment is important to help compensate for camera and object motion, we cannot rely on alignment alone, which can fail for a variety of reasons, such as occlusions, non-rigid motion, or changes in lighting. With our performance goals in mind, we develop a merging method that is robust to misalignment, based on a pairwise freq[uency]…

章节：**1 Introduction**（鬼影，逐字）：

> The most frequent artifact caused by incorrect fusion is ghosting (ﬁgure 2a), due to the difﬁculty of aligning images captured at different times.

章节：**Figure 6 caption**（离群帧/鬼影鲁棒性，逐字）：

> …even in the presence of a single outlier (frame 5). In the DFT domain (d), the outlier raises all coefﬁcients above the noise level, i.e., makes the signal non-sparse, which reduces the effectiveness of shrinkage. In contrast, our pairwise temporal ﬁlter (f) allows shrinkage to be effective on all but the misaligned frame. The net result is that our robust pairwise merge has signiﬁcantly more denoising than the DFT, producing an output signal closer to the true signal (b).

#### d) 局部色调映射（local tone mapping）的存在与做法

章节：**6 Finishing → "Dynamic range compression"**（逐字）：

> Dynamic range compression For high dynamic range scenes we use local tone mapping to reduce the contrast between highlights and shadows while preserving local contrast. The tone mapping method we have chosen is a variant of exposure fusion [Mertens et al . 2007]. Given input images that depict the same scene at different brightness levels, exposure fusion uses image pyramids to blend the best-exposed parts of the input images to produce a single output image that looks natural and has fewer badly exposed areas than the inputs. Exposure fusion is typically applied to images captured using bracketing. In our pipeline we capture multiple frames with constant exposure, not bracketing. To adapt exposure fusion to our pipeline, we derive “synthetic exposures” from our intermediate HDR image by applying gain and gamma correction to it, then fuse these as if they had been captured using bracketing. We perform these extractions in grayscale, and we create only two synthetic exposures–one short and one long. The short exposure tells us how many pixels will blow out, and becomes the overall exposure used during capture, while the ratio between the short and long exposures tells us how much dynamic range compression we are applying. Both values come from our auto-exposure algorithm. Fusing grayscale instead of color images, and using only two synthetic exposures, reduces computation and memory requirements. It also allows us to simplify the per-pixel blend-weights compared to those in the work by Mertens et al. [2007]. In particular, we use a ﬁxed weighting function of luma that favors moderately bright pixels.

章节：**3 Auto-exposure**（三步骤之一，逐字）：

> Our approach for handling HDR scenes consists of three steps: 1. deliberately underexpose so that fewer pixels saturate, 2. capture multiple frames to reduce noise in the shadows, and 3. compress the dynamic range using local tone mapping.

章节：**1 Introduction → design principle "Be natural"**（逐字）：

> In high-dynamic-range situations we must therefore limit the amount of local tone mapping we do to avoid cartoony or surrealistic images.

章节：**Figure 3 caption / pipeline 文字**（逐字，局部与全局色调映射为两个独立阶段）：

> white balance, demosaic, chroma denoise dehaze, global tone map local tone map (exposure fusion) sharpen, hue & saturation

#### e) "手机在低光下使用连拍"的条件

章节：**3 Auto-exposure → "Burst size"**（逐字，已见上）：

> In low light, or in very high dynamic range scenes where we will be boosting the shadows later, we want more frames to improve signalto-noise ratio…

章节：**Figure 1 caption**（逐字）：

> In this low-light setting (about 0.7 lux), the conventional camera pipeline underexposes (left).

章节：**Figure 10 caption**（逐字）：

> living room: low-light (3 lux) child: motion church: HDR our result … The middle row shows a dark scene (3 lux is roughly equivalent to candlelight). Here our method produces a brighter image than a standard pipeline.

**注意：HDR+ 论文中没有给出"低于某 lux 自动启用连拍"的阈值式触发条件**（检索 `lux` 全文仅 3 处，均为图注中的场景照度示例：0.7 lux、3 lux）。阈值式表述出现在 Night Sight 论文中（见下）。

---

## 2. Night Sight 论文（Google 一手论文，arXiv:1910.11336）

### https://arxiv.org/abs/1910.11336  `[web_fetch]`

Abstract 逐字（章节：Abstract）：

> Taking photographs in low light using a mobile phone is challenging and rarely produces pleasing results. Aside from the physical limits imposed by read noise and photon shot noise, these cameras are typically handheld, have small apertures and sensors, use mass-produced analog electronics that cannot easily be cooled, and are commonly used to photograph subjects that move, like children and pets. In this paper we describe a system for capturing clean, sharp, colorful photographs in light as low as 0.3~lux, where human vision becomes monochromatic and indistinct. To permit handheld photography without flash illumination, we capture, align, and combine multiple frames. Our system employs "motion metering", which uses an estimate of motion magnitudes (whether due to handshake or moving objects) to identify the number of frames and the per-frame exposure times that together minimize both noise and motion blur in a captured burst. We combine these frames using robust alignment and merging techniques that are specialized for high-noise imagery.

### https://ar5iv.labs.arxiv.org/html/1910.11336  `[web_fetch]`

（正文全文另经 `curl` 抓取 `https://arxiv.org/html/1910.11336v1` 以定位被 `web_fetch` 截断的章节，逐字文本一致。）

#### 帧数

章节：**Figure 2 caption**（逐字）：

> Based on this exposure time, we capture and merge a burst of 6–13 frames (Section 3). The reference frame is down-sampled for the computation of the white-balance gains (Section 4). White balance and tone mapping (Section 5) are applied at the “Finish” stage, as well as demosaicing, spatial and chroma denoising and sharpening. Total capture time is between 1 and 6 seconds after the shutter press and processing time is under 2 seconds.

章节：**Figure 1 caption**（逐字）：

> Our pipeline (c) produces detailed images by selecting a longer exposure time due to the low scene motion (in this setting, extended from 0.14 s to 0.33 s), robustly aligning and merging a larger number of frames (13 frames instead of 6)…

章节：**Appendix A / 正文（13 帧合并）**（逐字）：

> In (b), the burst of 13 frames is merged using a low temporal strength. The resulting image has significant noise but no motion blur.

#### 曝光时间范围

章节：**2.3 Camera stability detection**（逐字）：

> When the device is handheld, we capture up to 333 ms exposures; when we detect the device is stabilized, we capture up to 1 s exposures.

章节：**Figure 5 caption**（逐字）：

> The measured light levels at the tree were 0.06 lux. (a) A handheld capture uses 13× 333 ms exposures. (b) When stabilized on a tripod, our algorithm uses 6× 1 s exposures. The longer individual exposures greatly reduce noise. (c) Camera stability detection could be used for even longer captures, such as the 25× 4 s exposures in this example. Noise is significantly reduced, at the cost of a much longer total capture time.

章节：**1 Introduction（设计要求 4）**（逐字）：

> Support total scene acquisition times of up to ∼6 seconds.

章节：**2.3 Camera stability detection（陀螺仪稳定性判据）**（逐字）：

> To assess camera stability from gyro measurements, we temporally smooth angular speeds by averaging them over the t0=1.466 s before shutter press. … We therefore mask out the t1=0.400 s of gyroscope readings nearest to the shutter press signal.

#### 对齐与合并方法

章节：**3.1 Alignment**（逐字）：

> Alignment and merging are performed with respect to a reference frame, chosen as the sharpest frame in the burst. We use a tile-based alignment algorithm similar to (Hasinoff et al., 2016) with slight modifications to improve performance in low light and high noise. The alignment is performed by a coarse-to-fine algorithm on four-level Gaussian pyramids of the raw input. In our implementation, the size of the tiles for alignment is increased as the noise level in the image rises, at the cost of slightly increased computation for larger tile sizes. Whereas the previous alignment method used 8 or 16 pixel square tiles, we use square tiles of 16, 32, and 64 pixels, depending on the noise level. For example, in a well-lit indoor setting, the size of the alignment tiles is 16 while in the dark it is 64 pixels.

章节：**3.2 Spatially varying temporal merging**（逐字）：

> As described in (Hasinoff et al., 2016), after alignment, tiles of size 16×16 pixels are merged by performing a weighted average in the Fourier domain. This merging algorithm is intrinsically resilient to temporal changes in the scene and to alignment errors, owing to the reduced weight given to frames and frequency bands that differ from the reference frame significantly above the noise variance.

章节：**3.2（式 7）**（逐字公式）：

> A_tz(ω) = |D_tz(ω)|² / ( |D_tz(ω)|² + c σ_tz² )

章节：**3.2（temporal strength 与 mismatch map，式 8）**（逐字）：

> We call this parameter the “temporal strength”, where a strength of 0 represents no temporal denoising, and a strength of infinity represents averaging without any motion robustness (see Figure 9 a–d). … In order to reduce noise in very low-light scenes without suffering from artifacts, we introduce a spatially varying scaling factor to c, the “temporal strength factor”, f_tz, where t and z represent the tile and frame respectively. The modified temporal strength is c · f_tz. … (8) m_tz = d_tz² / ( d_tz² + s σ_tz² ) . The mismatch, m_tz, is calculated between the corresponding tile in frame z to tile t in the reference frame, and has a value between zero (no mismatch) and one (high mismatch).

章节：**3 Motion-adaptive burst merging（贡献列表）**（逐字）：

> Our contributions on top of (Hasinoff et al., 2016) are 1) Calculating mismatch maps, in which high mismatch represents dynamic regions in the scene; 2) Using these maps to enable spatially varying Fourier-domain burst merging, in which merging is increased in static regions and reduced in dynamic regions; and 3) Increasing spatial denoising where merging was limited, to compensate for the reduced temporal denoising.

#### 噪声下降的量化说法

章节：**3.3 Spatially varying denoising**（逐字——本清单中唯一明确的帧数-噪声定量关系）：

> Originally, the strength of denoising was calculated assuming that all N frames were averaged perfectly and the estimate of the noise variance of the image after merging was updated to be σ²/N. In our modified implementation, we calculate the noise variance of the merged image based on the actual merging that occurred in each tile, which increases the noise variance in regions of high mismatch. As a result, these regions get increased spatial denoising, via the shrinkage function, which compensates for their reduced temporal denoising.

（即方差降为 σ²/N，等价于标准差按 √N 下降；但论文**未**写出 `√N` 字面。全文检索 `√` = 0 次。）

章节：**1 Introduction**（逐字）：

> Alternatively, noise can be improved by merging more frames and increasing the contribution of each merged frame, effectively increasing temporal denoising, however, doing so will cause moving objects to appear blurry in the output image.

#### 局部色调映射（local tone mapping）

章节：**Figure 1 caption**（逐字）：

> Additionally, we apply local tone mapping that brightens the shadows without over-clipping highlights or sacrificing global contrast.

章节：**5 Tone Mapping**（逐字）：

> In our final rendition, we employ the following set of carefully tuned heuristics atop the tone mapping of (Hasinoff et al., 2016) to maintain a nighttime aesthetic: • Allow higher overall gains to render brighter photos at the expense of revealing noise. • Limit boosting of the shadows, keeping a significant amount of the darkest regions of the image near black to maintain global contrast and preserve a feeling of nighttime. • Allow compression of higher dynamic ranges to better preserve highlights in challenging conditions, such as night scenes with light sources. • Boost the color saturation in inverse proportion to scene brightness to produce vivid colors in low-light scenes. • Add brightness-dependent vignetting.

章节：**5 Tone Mapping（lux 阈值 + 阴影增益公式）**（逐字）：

> (Hasinoff et al., 2016) uses a variant of synthetic exposure fusion (Mertens et al., 2007) that splits the full tonal range into a shorter exposure (“highlights”) and longer exposure (“shadows”), determines the gains that are applied to each exposure, and then fuses them together to compress the dynamic range into an 8-bit output. For scenes with illuminance E_v below log-threshold L_max, we gradually increase the gain A_s applied to the shadows (the longer synthetic exposure) with the lux level, reaching a peak of over 1 additional stop (2.2×) of shadow brightness at log-threshold L_min: (15) A_s = 2.2^(1−max(0, min(1, (log(E_v) − L_min)/(L_max − L_min)))) , where the illuminance thresholds L_max and L_min correspond to a scene brightness of 200 lux and 0.1 lux, respectively.

章节：**5 Tone Mapping（式 16、暗光渐晕）**（逐字）：

> The highlight gain A_h is: (16) A_h = 1 + 0.2 · (A_s − 1) · (1 − D) . … To suppress these artifacts, we apply vignetting in scenes darker than 5 lux, gradually increasing in darker scenes.

章节：**5 Tone Mapping（与 CLAHE 的对比，局部对比 vs 全局对比）**（逐字）：

> The amount of detail in that rendition can be increased by locally increasing its contrast, for example by applying adaptive histogram equalization. In Figure 14 b we use CLAHE (Zuiderveld, 1994) on the V channel following an HSV decomposition of the image (Smith, 1978). This increases local contrast but the global contrast is flattened, resulting in a low-contrast appearance. The result of our technique (Figure 14 c) reveals shadow details without significant loss of contrast and maintains a natural nighttime appearance.

#### 低光条件与 Night Sight 的关系

章节：**1 Introduction**（逐字）：

> Our system, which launched to the public in November 2018 as Night Sight on Google Pixel smartphones, is designed to satisfy the following requirements…

章节：**1 Introduction（HDR+ 的低光极限，3 lux）**（逐字）：

> Simply extending it to collect more frames allows it to generate high-quality photographs down to about 3 lux. However, to produce high-quality images in even lower light, tradeoffs between noise and motion blur must be addressed.

章节：**6 Results**（逐字）：

> Our system was launched publicly in November 2018 as a mode called “Night Sight” in the camera app on Google Pixel smartphones.

---

## 3. 手机 ISP 的局部色调映射（LTM）一手证据

### https://arxiv.org/abs/1704.05173（任务给定编号）

**未找到。** 该编号实为天体物理论文（见第 0 节），非 HDRNet。HDRNet 正确编号为 1707.02880。

### https://ar5iv.labs.arxiv.org/html/1707.02880  `[web_fetch]`（HDRNet 正确编号）

Abstract 逐字（章节：Abstract）：

> Performance is a critical challenge in mobile image processing. Given a reference imaging pipeline, or even human-adjusted pairs of images, we seek to reproduce the enhancements and enable real-time evaluation. For this, we introduce a new neural network architecture inspired by bilateral grid processing and local affine color transforms. Using pairs of input/output images, we train a convolutional neural network to predict the coefficients of a locally-affine model in bilateral space. Our architecture learns to make local, global, and content-dependent decisions to approximate the desired image transformation. At runtime, the neural network consumes a low-resolution version of the input image, produces a set of affine transformations in bilateral space, upsamples those transformations in an edge-preserving fashion using a new slicing node, and then applies those upsampled transformations to the full-resolution image. Our algorithm processes high-resolution images on a smartphone in milliseconds, provides a real-time viewfinder at 1080p resolution, and matches the quality of state-of-the-art approximation techniques on a large class of image operators.

章节：**1 Introduction**（逐字，手机实时性能）：

> The forward pass of our network takes 14 ms to process a full screen resolution 1920×1080 image on a Google Pixel phone, thereby enabling real-time viewfinder effects at 50 Hz.

章节：**3.3 Upsampling with a trainable slicing layer**（逐字，bilateral grid 保持边缘）：

> Crucially, performing inference within a bilateral grid forces our model’s predictions to follow the edges in g, thereby regularizing our predictions towards edge-aware solutions (unlike standard networks based on transpose-convolutions or “deconvolution layers”, Figure 5).

**明确结论：HDRNet 论文正文未出现 `local tone mapping` 字面**（检索 `local tone` = 0 次、`tone mapping` = 0 次）。唯一 `tone-mapping` 出现在章节 **4.1 Reproducing image operators**，且是用来描述 HDR+ 被复现的任务：

> . HDR+ [Hasinoff et al., 2016] – a complex hand-engineered photographic pipeline that includes color correction, auto-exposure, dehazing, and tone-mapping.

→ 因此 HDRNet 只能作为"bilateral grid / local affine 变换 + 手机实时"的一手论文证据，**不能**作为"手机 ISP 使用 local tone mapping"字面证据。

### 厂商一手文档（Qualcomm / MediaTek / ARM）

**未找到。** 逐条记录尝试与失败原因：

- `https://www.qualcomm.com/` — `web_fetch`：`Error: web fetch failed: TypeError: fetch failed`
- `https://docs.qualcomm.com/` — `curl` HTTP 200，但页面为 **Qualcomm ID 登录墙**（逐字：`account.qualcomm.com - Sign In Qualcomm ID With a Qualcomm ID, you can access product information, such as documentation, tools, software, support communities, and more.`）；正文检索 `tone` = 0 次。
- `https://www.qualcomm.com/products/technology/spectra-isp` — HTTP **404**
- `https://www.qualcomm.com/products/snapdragon/camera` — HTTP **404**
- `https://www.qualcomm.com/products/features/spectra-isp` — HTTP **404**
- `https://www.qualcomm.com/snapdragon/features/spectra-isp` — HTTP **404**
- `https://www.mediatek.com/innovations/imagiq` — HTTP **404**
- `https://i.mediatek.com/imagiq` — HTTP **404**
- `https://www.mediatek.com/technologies/imagiq` — HTTP **404**
- `https://www.mediatek.com/products/smartphones/imagiq` — HTTP **404**
- `https://www.arm.com/products/silicon-ip-multimedia/image-signal-processor` — HTTP 200（427,620 bytes），正文检索 `tone` = **0** 次
- `https://www.arm.com/products/silicon-ip-multimedia/image-signal-processor/mali-c71` — HTTP 200（486,713 bytes），正文检索 `tone` = **0** 次
- `https://www.arm.com/products/silicon-ip-multimedia/image-signal-processor/mali-c71ae` — HTTP 200，`tone` = **0** 次
- `https://www.arm.com/markets/consumer-technologies/camera` — HTTP 200，`tone` = **0** 次
- `https://developer.arm.com/documentation/101888/latest/` — HTTP **403**；`https://developer.arm.com/documentation/102889/latest/` — HTTP **403**；`https://developer.arm.com/api/search?query=local%20tone%20mapping` — HTTP **403**
- 搜索通道：`web_search` 工具不可用（`DeepSeek search has no API key for "DEEPSEEK_API_KEY"`）；`patents.google.com` curl `000`；`freepatentsonline.com` `000`；`worldwide.espacenet.com` `403`；`patentscope.wipo.int` `403`；`patents.justia.com` `403`；`api.patentsview.org` 已改为返回 USPTO Open Data Portal 的 SPA HTML（非 JSON）；DuckDuckGo / Brave / Startpage / Qwant / searx 均 `000`；Yandex 返回 `Verification` 反爬页；Ecosia / Mojeek 被拦截；Bing 对本环境的中英文技术查询返回词典/百科结果（不可用）。

→ **Qualcomm / MediaTek / ARM 的公开 ISP 文档中"local tone mapping"的一手证据：未找到（fetch 失败/404/登录墙/正文无相关文本）。**

---

## 4. 多帧降噪（MFNR）在手机中的启用条件（ISO/lux 阈值）

### 一手学术证据（Google，可用）

- **https://arxiv.org/abs/1910.11336**（Night Sight，`[web_fetch]`）：Abstract 逐字 `in light as low as 0.3~lux`；Figure 1 caption 逐字 `Additionally, we apply local tone mapping that brightens the shadows without over-clipping highlights or sacrificing global contrast.`；Section 5 逐字 `where the illuminance thresholds L_max and L_min correspond to a scene brightness of 200 lux and 0.1 lux, respectively.`
- **https://arxiv.org/abs/1910.11336**：Section 1 逐字 `Simply extending it to collect more frames allows it to generate high-quality photographs down to about 3 lux.`
- **https://graphics.stanford.edu/papers/hdrp/hasinoff-hdrplus-sigasia16.pdf**（HDR+，`[curl+pypdf]`）：Section 3 "Burst size" 逐字 `In low light, or in very high dynamic range scenes where we will be boosting the shadows later, we want more frames to improve signalto-noise ratio…`；`In practice, we limit our bursts to 2–8 images…`

**注意：以上均为"低光下使用多帧"的定性/lux 示例证据，没有"ISO 超过 X 或 lux 低于 Y 即自动启用 MFNR"的阈值式规则表述。**

### 厂商白皮书 / 专利（Qualcomm 专利提到 MFNR 在 low light 启用）

**未找到。** 失败原因逐条：

- `https://patents.google.com/` 与 `https://patents.google.com/patent/US20150070693A1` — curl 连接超时（`000`）
- `https://www.freepatentsonline.com/` — curl `000`
- `https://worldwide.espacenet.com/` — HTTP `403`
- `https://patentscope.wipo.int/search/en/search.jsf` — HTTP `403`
- `https://patents.justia.com/search?q=%22multi-frame+noise+reduction%22` — HTTP `403`；`https://patents.justia.com/patent/20150070693` — HTTP `403`
- `https://ppubs.uspto.gov/pubwebapp/` — HTTP 200（需 JS 会话，未取得全文）
- `https://api.patentsview.org/patents/query?...` — HTTP 200，但返回的是 USPTO Open Data Portal 的 SPA HTML（`The Open Data Portal (ODP) is USPTO's data platform…`），**不再是 JSON 检索 API**
- `https://patentguru.com/search?q=local+tone+mapping` — HTTP `468`；`https://www.lens.org/` — HTTP `405`
- 厂商站点（Qualcomm/MediaTek/ARM）见第 3 节：公开页正文无 `tone`/相关技术文本，docs.qualcomm.com 为登录墙

→ **"厂商白皮书/专利中 MFNR 的 ISO/lux 启用阈值"一手证据：未找到（fetch 失败/403/登录墙/搜索通道不可用）。**

---

## 5. 本次未能覆盖的 URL 汇总（按要求明确写"未找到"）

| URL | 结果 |
| --- | --- |
| https://arxiv.org/abs/1609.01618 | 抓取成功，但内容是量子物理论文（编号错误） |
| https://ar5iv.labs.arxiv.org/html/1609.01618 | 抓取成功，内容是量子物理论文（编号错误） |
| https://arxiv.org/pdf/1609.01618 | 未抓取（同一量子物理论文；`web_fetch` 不支持 PDF） |
| https://www.hdrplusdata.org/hdrplus.pdf | 未找到（fetch 失败；302 指向不可达的 static.googleusercontent.com） |
| https://arxiv.org/abs/2104.00674 | 抓取成功，但内容是 PhySG（编号错误） |
| https://ar5iv.labs.arxiv.org/html/2104.00674 | 抓取成功，但内容是 PhySG（编号错误） |
| https://arxiv.org/abs/1704.05173 | 抓取成功，但内容是天体物理论文（编号错误） |
| Qualcomm / MediaTek / ARM "local tone mapping" 一手文档 | 未找到（404/登录墙/正文无相关文本） |
| Qualcomm 等 MFNR 低光启用阈值专利/白皮书 | 未找到（403/超时/登录墙/搜索通道不可用） |
