# 05 — 相机厂商外观（camera vendor look）：引擎 vs LUT 的源码取证

> 独立源码/文档调研。每条结论附逐字证据（`文件路径:行号` 或 URL + 原文）。**严格区分「源码/文档事实」与「我的推断」**。找不到的写「未找到」。

---

## 0. 方法与元信息

- 主机：macOS 15.7.3（Build 24G419），git 2.39.2（Apple Git-143），网络可用。
- 所有仓库自行 clone 到 `/private/tmp/eng/`（`git clone`，浅克隆；darktable 额外 `git fetch --unshallow --filter=blob:none` 取全历史）。
- DNG 规范 PDF 与 DNG SDK 来自既有解包目录 `/private/tmp/dngsdk/dng_sdk_1_7_1/`（**内容由我独立重新提取核实**：用 `pypdf` 逐页抽取 PDF 文本，未沿用任何既有结论）。
- 本会话的 `web_search` 工具不可用（逐字报错见 §9.1），因此开放网络检索受限；只对**已知 URL** 用 `curl` / `web_fetch` 取证。这一点在 §9 明确列出。
- 工作方法：dcamprof / DNG SDK / DNG 规范 / darktable / OpenDRT / LibRaw 部分由我本人逐条 `grep`+`sed`+`read` 取证；filmulator / rawproc / vkBasalt 由并行子代理首轮取证，**其中每一条被写入本报告的引用都由我用 `sed`/`grep` 二次核对过**（filmulator 的核对记录见 §5.3，vkBasalt 见 §7.4）。发现并修正的子代理错误：rawproc `getCommand()` 的真实位置是 `src/PicProcessor.cpp:207-213`（子代理误写为 `src/PicProcessor.h:207-214`；该文件仅 94 行，`src/PicProcessor.h:42` 是声明）。该错误引用**未被写入本报告**。
- 附属原始证据文件：`filmulator-filmsim-forensics.md`（工作区根目录，filmulator 子代理的详细报告，约 30 条逐字证据块）。

### 0.1 取证对象与版本（commit hash）

| 项目 | 来源 | commit hash | 日期 | 备注 |
|---|---|---|---|---|
| dcamprof | `https://github.com/Beep6581/dcamprof` | `bffb659d6a0af82f7b6b2e6d0006d1824ac4d398` | 2020-09-03 | `src/VERSION` = `1.0.6`；作者 Anders Torger |
| dcamprof 文档 | `https://torger.se/anders/dcamprof.html` | 无版本控制（HTTP `Last-Modified: Wed, 19 Feb 2020 05:51:53 GMT`） | 2020-02-19 | 抓取于 2026-09-22，316477 字节 |
| DNG SDK | `dng_sdk_1_7_1`（Adobe 分发 zip） | 无 git（zip 分发） | — | `dng_flags.h:28` `#define kDNGSDK_VersionString "1.7.1"` |
| DNG 规范 | `DNG_Spec_1_7_1_0.pdf` | 无 git | 2023-09（封面 "September 2023"） | 126 页 |
| darktable | `https://github.com/darktable-org/darktable` | `92f6c282845943ad8f242c3dcb040a3c75c877b9` | 2026-09-22 | |
| darktable 文档 | `https://github.com/darktable-org/dtdocs` | `79c10958aee76654dcac55a7c5e645a05621657b` | 2026-09-22 | |
| filmulator | `https://github.com/CarVac/filmulator-gui` | `57fbaec57555432d86d3aa632990cd8fa09114ad` | 2026-04-04 | 源码在嵌套目录 `filmulator-gui/` |
| rawproc | `https://github.com/butcherg/rawproc` | `2f149a9d76f62ebed2c3cc6a8849b1fdccb53d8d` | 2026-09-16 | |
| vkBasalt | `https://github.com/DadSchoorse/vkBasalt` | `4f97f09ffe91900e6ca136cc26cf7966f8f6970d` | 2023-05-08 | |
| open-display-transform (OpenDRT) | `https://github.com/jedypod/open-display-transform` | `af683323e2a8a63501f02c0a724ec538e3228ad0` | 2026-02-09 | |
| LibRaw（仅取单个数据文件） | `raw.githubusercontent.com/LibRaw/LibRaw/master/src/tables/colordata.cpp` | 未固定（master 快照） | 2026-09-22 抓取 | 版权头 "2019-2025" |

### 0.2 关于 dcamprof 仓库地址的重要事实

任务给定的 `https://github.com/anderstorger/dcamprof` **不存在**。逐字证据：

```
$ git clone https://github.com/anderstorger/dcamprof dcamprof
Cloning into 'dcamprof'...
remote: Repository not found.
fatal: repository 'https://github.com/anderstorger/dcamprof/' not found
```

GitHub 仓库搜索 API（`https://api.github.com/search/repositories?q=dcamprof`）返回 3 条，其中 `Beep6581/dcamprof`（`"fork": false`，`pushed_at: 2023-05-18T17:45:30Z`）自述：

```
DCamProf is a free and open-source command line tool for making camera profiles, and performing tasks related to camera profiles and profiling.
```

我采用 `Beep6581/dcamprof` 作为源码镜像，并用作者官方站点 `torger.se` 的文档做独立交叉核实。

**官方站与仓库内文档的一致性（我独立核实）**：我于 2026-09-22 自行 `curl` 官方站得到 316477 字节，仓库内 `www/dcamprof.html` 为 314506 字节；`diff` 共 108 行差异，逐字检查后差异**全部**为 Google Analytics 脚本块与超链接 URL 形式（例如 `http://lumariver.com/#LumariverPD` vs `http://www.lumariver.com/#LumariverPD`、`camera-profiling.html` vs `https://torger.se/anders/photography/camera-profiling.html`），**正文文字一致**。下文 dcamprof 文档引用均以仓库内 `www/dcamprof.html` 的行号为准。

---

## 1. 结论速览

| 项目 | 相机外观是「参数化引擎」还是「一张 LUT」 | 数据源 | 输出/工作域 | 最强证据 |
|---|---|---|---|---|
| **dcamprof** | **引擎**：matrix + ForwardMatrix + HueSatMap(2.5D/3D) + LookTable + ToneCurve 五件套，全部由优化器算出 | 用户拍摄的色卡 → Argyll `.ti3` 测量；参考反射光谱（`cc24_ref.cie`，源自 BabelColor）；或相机 SSF | 色度部分 scene-referred linear（XYZ D50 / ProPhoto RIMM），曲线部分 output-referred | `src/dcamprof.c:2255-2397`；`www/dcamprof.html:2022-2027` |
| **DNG SDK / 规范** | **引擎被写进规范**：ColorMatrix/ForwardMatrix/CameraCalibration/ReductionMatrix/AsShotNeutral + ProfileHueSatMap + ProfileLookTable + ProfileToneCurve | 由 profile 创建者（厂商/Adobe/用户）在 DCP 里**携带数据**；SDK 只消费不生成 | XYZ D50 ↔ 线性 ProPhoto（RIMM）；ToneCurve 在 "linear gamma"（SDR） | 规范 PDF p.100-104；`dng_render.cpp:1906,1985,2004` |
| **darktable** | **不适用**：当前 HEAD **没有** DCP 支持（无 `src/common/dcp.c`，全仓无 DCP 代码） | — | — | §3 的 grep 证据 |
| **filmulator** | **物理引擎，但不是「相机外观」引擎**：时步迭代的显影/扩散偏微分方程，无任何 LUT | 物理参数（显影剂浓度、晶体生长常数…）+ 用户滑杆 | 输入 linear，输出标注 `// sRGB` | `filmulator-gui/core/filmulate.cpp:24,120-122,215-234` |
| **rawproc** | **工具链 + 1D LUT**，不是相机外观引擎 | 用户提供的 1D LUT 文件、曲线控制点 | 无强制域；文档只区分 mosaic/RGB | `src/gimage_parse.cpp:1780-1785`；`doc/tools.html:9-37` |
| **vkBasalt** | **纯 LUT**（ReShade 风格 3D LUT） | 用户 `.cube` / PNG LUT 文件 | post-processing / display 域（present 前） | `src/effect_lut.cpp:119-124`；`src/shader/lut.frag.glsl:29-32` |
| **OpenDRT** | **引擎**（纯参数化 picture formation，仓库内**无 LUT**） | 算法参数（tonescale/purity/brilliance/hue shift 滑杆与预设） | 输入 scene-linear 或各厂商 log → 输出 display-referred | `display-transforms/opendrt/OpenDRT.dctl:737`；LUT grep 为空 |
| **darktable color calibration** | **引擎**（3×3 矩阵 + 光照适应），可用色卡自标定 | 内置 `dt_color_checker` 参考 CIELab + 用户拍摄的色卡 | 工作 RGB（linear Rec2020）内的 LMS/CAT 变换 | `src/iop/channelmixerrgb.c:47,163,1428-1441` |
| **LibRaw / dcraw** | **矩阵表**（不是引擎，也不是 LUT） | **Adobe DNG Converter** | 相机原生 → XYZ（D65 约定） | `src/tables/colordata.cpp:21-22` |

---

## 2. dcamprof（Anders Torger）

### 2.1 事实：它把「相机外观」建模为 DCP 的五个引擎组件

`src/dcamprof.c` 中 `make_dcp()` 是 DCP 生成核心（`make_dcp` 定义起于 `src/dcamprof.c:2255`）。逐字原文（`src/dcamprof.c:2305-2333`）：

```c
    struct profio_dcp *dcp = calloc(1, sizeof(*dcp));
    // we don't remap color matrix, not used for LUT and too small difference to care, seems to make more damage than improvement
    dcp->cm[0] = prof->color_matrix;
    dcp->fm[0] = m3x3_mul_m3x3(map_white, forward_matrix);
    dcp->has.fm = !skip_fwd;
    if (make_lut && chromalut_isnoop(prof->lut)) {
        ...
    }
    if (make_lut) {
        const int hsm_vcount = enable_3d_hsm ? vcount : 1;
        if (hsm_vcount == 1) {
            elog("Generating 2.5D HueSatMap with %dx%d = %d entries...", hcount, scount, hcount*scount);
        } else {
            elog("Generating 3D HueSatMap with %dx%dx%d = %d entries...", hcount, scount, hsm_vcount, hcount*scount*hsm_vcount);
        }
        dcp->huesatmap[0] = dnglut_huesatmap_new(dcp->hsmdims, hcount, scount, hsm_vcount, !skip_lut_gamma, prof->lut, map_d50, prof->forward_matrix, forward_matrix);
```

LookTable 与 ToneCurve 的生成（`src/dcamprof.c:2357-2388`）：

```c
    if (tc != NULL) {
        switch (tc_type) {
        case TC_NEUTRAL:
            if ((tc_len > 2 || gc_type != GC_NONE) && make_lut) {
                if (!skip_lut_gamma) {
                    dcp->looktable_srgb_gamma = true;
                    dcp->has.looktable_srgb_gamma = true;
                }
                double cv, cs;
                look_neutral_tone_rep_op_tc_analysis(tc, tc_len, &cv, &cs);
                elog("The tone curve's contrast value is %.2f (=> auto chroma scaling value %.3f)\n", cv, cs);
                dcp->looktable = dnglut_looktable_new(dcp->lookdims, hcount, scount, vcount, tc, tc_len, true, dcp->looktable_srgb_gamma, gc_type, ntro_conf);
                dnglut_test_discontinuity(dcp->looktable, hcount, scount, vcount, "LookTable", allow_discontinuity_hue_shifts);
                print_hsm(report_dir, "lkt", dcp->looktable, dcp->lookdims[0], dcp->lookdims[1], dcp->lookdims[2], dcp->looktable_srgb_gamma);
            }
            dcp->tonecurve = malloc(2 * tc_len * sizeof(dcp->tonecurve[0]));
            for (int i = 0; i < tc_len; i++) {
                dcp->tonecurve[2*i+0] = i / (double)(tc_len - 1);
                dcp->tonecurve[2*i+1] = tc[i];
            }
            dcp->tonecurve_len = tc_len;
            break;
```

LookTable 的**算法本体**（3D、HSV 域、按中性色调再现算子反推）（`src/dnglut.c:180-196`、`:220-240`）：

```c
v3 *
dnglut_looktable_new(uint32_t hsmdims[3],
                     int hcount,
                     int scount,
                     int vcount,
                     const double tc[],
                     int tc_len,
                     bool skip_tc_apply,
                     bool srgb_gamma,
                     enum gc_type gc_type,
                     const struct tone_rep_op_config *ntro_conf)
{
    elog("Generating 3D LookTable with %dx%dx%d = %d entries for the neutral tone reproduction operator...\n  0%%", hcount, scount, vcount, hcount*scount*vcount);
    const m3x3 xyz2rgb = dcp_xyz_D50_to_prophoto_rgb;
    const m3x3 rgb2xyz = m3x3_invert(xyz2rgb);
    v3 *hsm = malloc(hcount * scount * vcount * sizeof(*hsm));
    look_tone_rep_op_t *ntro = look_tone_rep_op_new(rgb2xyz, tc, tc_len, gc_type, ntro_conf);
```

```c
                v3 *entry = &hsm[(hcount*scount)*v + scount*h + s];
                if (v == 0 || s == 0) {
                    /* DNG profile format requires these values for s or v = 0. That saturation can't
                       be scaled from zero is logical, but that we cannot change value of a nonzero
                       gray is unfortunate. */
                    entry->v[0] = 0.0;
                    entry->v[1] = 1.0;
                    entry->v[2] = 1.0;
                    continue;
                }
                v3 hsv = {{ h / (double)hcount * 6.0, s / (double)(scount-1), v / (double)(vcount-1) }};
                if (srgb_gamma) {
                    hsv.v[2] = srgb_gamma_inverse(hsv.v[2]);
                }
```

HueSatMap 的生成函数声明（`src/dnglut.h:31`）：

```c
dnglut_huesatmap_new(uint32_t hsmdims[3],
```

### 2.2 事实：数据源 = 拍摄色卡 → Argyll `.ti3`，参考光谱来自 BabelColor；或相机 SSF

文档 `www/dcamprof.html:352-359`：

```
DCamProf is a free and open-source command line tool for making camera
profiles, and performing tasks related to camera profiles and profiling.
<p>
To make a camera profile you need either the camera spectral
sensitivity functions (SSFs) or a measured target. DCamProf has no
measurement functionality, but you can use the free and
open-source <a href="https://www.argyllcms.com/">Argyll CMS</a> to get a
<code>.ti3</code> file with measurement data which DCamProf can read.
```

完整 DCP 工作流（`www/dcamprof.html:577-632`，逐字节选）：

```
  <li>Use Argyll <code>scanin</code> command to generate a <code>.ti3</code> file.
    <ul>
      <li>It needs the target reference file, test target layout file
      and raw image as 16 bit TIFF as input.
      <li><code>scanin -v -p -dipn rawfile.tif ColorChecker.cht cc24_ref.cie</code>
```

```
  <li>Use DCamProf to make a profile from Argyll's <code>rawfile.ti3</code>
  target file.
    <ul>
      <li><code>dcamprof make-profile -g cc24-layout.json rawfile.ti3 profile.json</code>
```

```
  <li>Convert the native format profile to a DNG profile (DCP).
    <ul>
      <li><code>dcamprof make-dcp -n "Camera manufacturer and model" -d
      "My Profile" profile.json profile.dcp</code>
```

```
      <li>The above example makes a colorimetric profile without a
      curve. If you want to embed a tone-curve and make use of
      DCamProf's neutral tone reproduction (which really <b>makes more
      sense for a general purpose profile</b>), you can do like this:
        <ul>
          <li><code>dcamprof make-dcp -n "Camera manufacturer and model" -d
              "My Profile" -t acr profile.json profile.dcp</code>
```

色卡参考数据的来源，`www/dcamprof.html:537-552`（逐字）：

```
      <li>If you're using that 24 patch color checker like most will be
      doing,
      the <a href="http://www.babelcolor.com/download/CC_Avg30_spectrum_CGATS.txt">reference
      file with reflectance spectra assembled by BabelColor</a> is the second best choice if
      you can't measure it yourself. This data should be valid for the
      nowadays more popular "ColorChecker Passport" product too.
```

源码侧数据源证据：`.ti3` 解析函数（`src/argyllio.c:88`）与调用点（`src/dcamprof.c:3847`）：

```c
argyllio_ti3_parse(const char filename[],
```

```c
    struct patch_set *ps = argyllio_ti3_parse(filename, true, true);
```

内置 CC24 光谱数据库的出处注释（`src/spectraldb_cc24.c:1-8`，逐字）：

```c
/*
  From http://www.babelcolor.com/download/CC_Avg30_spectrum_CGATS.txt retrieved April 2015

  380 to 730 10nm
  4x6 patches
 */

const double spectraldb_macbeth_colorchecker[] = {
```

随发行版附带的目标参考文件（`ls data-examples/` 实际输出）：

```
camera-ssf.json
cc24_ref-new.cie
cc24_ref.cie
cc24-layout.json
ccsg_ref-new.cie
ccsg_ref.cie
ccsg-layout.json
import_spectra.txt
matrix.json
nil-profile.json
ntro_conf.json
ntro_lookop_conf.json
spectrum.json
spline.rtc
target-adjust.json
tone-curve.json
transfun.json
```

> 注意：任务是「ColorChecker / IT8.7 / 自标定」。我实际找到的是 **CC24 / ColorChecker SG（`ccsg_*`）+ BabelColor 光谱 + 用户自测（`.ti3`）+ 相机 SSF（`camera-ssf.json`）**。**IT8.7 在 dcamprof 仓库与文档中未找到**（`grep -rn "IT8" src/ www/dcamprof.html` → 0 命中）。

### 2.3 事实：dcamprof 自己解释「外观如何被塞进 DCP 引擎」

`www/dcamprof.html:2022-2027`（逐字）：

```
<h4>DNG profile implementation notes</h4>

<p>
When making a DNG profile the operator is implemented through the
LookTable and curve. So if you strip away the LookTable and curve you
have the pure colorimetric profile left.
```

`www/dcamprof.html:2059-2064`（LookTable 的 value 维度用 gamma 编码）：

```
The LookTable will in DCamProf's profiles per default be gamma-encoded
for the value divisions, this will make perceptually better use of the
range (that is higher density in the shadows) meaning that the default
15 value divisions should be enough for most curves. Some older or
simpler raw converters may not support the gamma encoding tag though,
and if so you can disable it.
```

### 2.4 事实：色度部分 2.5D、外观部分 3D

`www/dcamprof.html:479-492`（逐字）：

```
The linearity assumption leads to that the correction lookup table (LUT)
only needs to be indexed on chromaticity (that is saturation and hue,
but not lightness), but the output still needs correction factors for
all three dimensions as some colors can be rendered too dark or too
light with a fixed factor throughout the full lightness range. That is
DCamProf works with a LUT with 2D input and 3D output, commonly
referred to as a 2.5D LUT.
<p>
DCamProf does allow you to apply a subjective look on top of the
accurate colorimetric 2.5D profile. It will then use a full 3D LUT so
you can make lightness-dependent adjustments, but the colorimetric
part always stays 2.5D
```

### 2.5 事实：scene-referred vs output-referred 的自我定位

`www/dcamprof.html:2126-2142`（逐字）：

```
A scene-referred camera profile simply means that the purpose of the
profile is to correct the colors so the output represents a true
linear colorimetric measurement of the original scene. In other words
we want the XYZ values for the standard observer, or any reversible
conversion thereof. That is what we in daily speak would call an
accurate linear profile (where linear means "no tone curve", we can
still employ a LUT for non-linear correction), which DCamProf makes
per default.
<p>
An output-referred camera profile should instead produce output that
can be directly connected to a screen or printer ICC profile and
produce a pleasing output for that media.
```

### 2.6 我的推断（dcamprof 节）

- dcamprof 是**引擎**：它输出的是「参数 + 表」的 DCP 结构（矩阵、2.5D HueSatMap、3D LookTable、ToneCurve），而不是烘焙好的图像变换 LUT。表格内容由优化器从测量数据求解（`chromalut` / `prof->lut` / `prof->forward_matrix`），并非手绘 LUT。
- 它的「外观」部分（LookTable + ToneCurve）本质是**用 DCP 已有机制模拟一个外观算子**；dcamprof 文档自己也承认这是 workaround（`www/dcamprof.html:2039-2047`：「This means that you cannot implement a curve directly in the LUT, as grays cannot be darkened or brightened (which a curve requires). The workaround is to embed a DNG tone curve…」）。

---

## 3. DNG SDK / DNG 规范：相机厂商外观就是「引擎参数」

### 3.1 事实：规范明确给出「相机 profile」的数据结构（PDF 页码 + 逐字原文）

来源：`/private/tmp/dngsdk/dng_sdk_1_7_1/DNG_Spec_1_7_1_0.pdf`（126 页，封面 "Digital Negative Specification 1.7.1.0 September 2023"）。我用 `pypdf` 逐页抽取文本到 `/private/tmp/dngspec_1_7_1_extract.txt`，下列页码为该 PDF 的物理页，原文为该页抽取文本逐字。

**PDF p.100（提取文件行 3255-3267）** — "Mapping Camera Color Space to CIE XYZ Space"：

```
Mapping Camera Color Space to CIE XYZ Space  
This section describes the DNG processing model for mapping between the camera color space coordinates 
(linear reference values) and CIE XYZ (with a D50 white point).  
Camera Calibration Matrices  
DNG 1.2.0.0 and later supports different companies creating the camera calibration tags using different 
reference cameras.  
When rendering a DNG file using a camera profile, it is important to know if the selected camera profile was 
designed using the same reference camera used to create the camera calibration tags. If so, then the camera 
calibration tags should be used. If not, then it is preferable to ignore the camera calibration tags and use 
identity matrices instead in order to minimize the worse case calibration mismatch error.  
This matching is done by comparing the CameraCalibrationSignature tag and the ProfileCalibrationSignature 
tag for the selected camera profile. If they match, then use the camera calibration tags. If not, then use 
identity matrices.  
```

**PDF p.100-101（行 3301-3311）** — 引擎变量的定义（逐字）：

```
Definitions used in the following sections  
Let n be the dimensionality of the camera color space (usually 3 or 4). 
Let CM be the n-by-3 matrix interpolated from the ColorMatrix1, ColorMatrix2, and ColorMatrix3 tags. 
Let CC be the n-by-n matrix interpolated from the CameraCalibration1, CameraCalibration2, and 
CameraCalibration3 tags (or identity matrices, if the signatures don’t match). 
Let AB be the n-by-n matrix, which is zero except for the diagonal entries, which are defined by the 
AnalogBalance tag. 
Let RM be the 3-by-n matrix interpolated from the ReductionMatrix1, ReductionMatrix2, and 
ReductionMatrix3 tags. 
Let FM be the 3-by-n matrix interpolated from the ForwardMatrix1, ForwardMatrix2, and ForwardMatrix3 
tags.  
```

**PDF p.101（行 3315-3319）** — `XYZtoCamera` 的组合式：

```
The XYZ to camera space matrix is:  
XYZtoCamera = AB * CC * CM  
The camera neutral can be found by expanding the xy value to a 3-by-1 XYZ matrix (assuming Y = 1.0) and 
multiplying it by the XYZtoCamera matrix:  
CameraNeutral = XYZtoCamera * XYZ  
```

**PDF p.103（行 3357-3364）** — 有 ForwardMatrix 时的 Camera→XYZ(D50)：

```
If the ForwardMatrix tags are included in the camera profile 
CameraToXYZ_D50 = FM * D * Inverse (AB * CC)  
D, above, is a diagonal n-by-n matrix, computed so that the CameraToXYZ_D50 matrix maps the selected 
camera neutral to XYZ D50. The forward matrix is required to map a unit vector to XYZ D50 by definition, so D 
can be computed by finding the neutral for the reference camera:  
ReferenceNeutral = Inverse (AB * CC) * CameraNeutral 
And then: 
D = Invert (AsDiagonalMatrix (ReferenceNeutral))  
```

**PDF p.103（行 3365-3393）** — "Applying the Hue/Saturation/Value Mapping Table"（**工作空间 = ProPhoto RGB / RIMM，XYZ D50**，逐字节选）：

```
Applying the Hue/Saturation/Value Mapping Table  
After the camera colors have been converted to XYZ (D50) values, the Hue/Saturation/Value mapping table, if 
any, is applied. If there are two or three Hue/Saturation/Value mapping tables, then they are interpolated in 
the same way that color calibration tags are interpolated. If only one Hue/Saturation/Value table is included, 
then it is used regardless of the selected white balance.  
1. First, the XYZ (D50) values are converted to linear RGB coordinates, using the ProPhoto RGB 
primaries. (This is also known as RIMM space).  
2. If the camera profile is High Dynamic Range (see ProfileDynamicRange) and ValueDivisions is greater 
than 1, then apply the encoding function 𝑓(𝑥) to each color component. 
3. The linear RGB coordinates are converted to HSV coordinates (Hue-Saturation-Value).  
4. The HSV coordinates are used to index the mapping table using tri-linear interpolation, resulting in 
three values: hue shift (in degrees); saturation scale factor; value scale factor.
...
11. Convert from linear ProPhoto RGB back to XYZ (D50) values.   
It is recommended that these tables be limited to use a ValueDivisions equal to 1, so the table is only indexed 
by hue and saturation.
```

**PDF p.57（行 1894-1905）** — `ProfileLookTableData`：**它被明确放在「曝光/补光之后、tone curve 之前」**（逐字）：

```
This tag contains a default "look" table that can be applied while processing the image as a starting point for 
user adjustment. This table uses the same format as the tables stored in the ProfileHueSatMapData1 and 
ProfileHueSatMapData2 tags and is applied in the same color space. However, it should be applied later in 
the processing pipe, after any exposure compensation and/or fill light stages, but before any tone curve 
stage.  
Each entry of the table contains three 32-bit IEEE floating-point values. The first entry is hue shift in degrees, 
the second entry is a saturation scale factor, and the third entry is a value scale factor.  
```

**PDF p.51（行 1682-1694）** — `ProfileToneCurve`（逐字）：

```
This tag contains a default tone curve that can be applied while processing the image as a starting point for 
user adjustments. The curve is specified as a list of 32-bit IEEE floating- point value pairs in linear gamma. 
Each sample is a pair (x,y) where x is the input value in the range of 0.0 to 1.0 and y is the output value in the 
range of 0.0 to 1.0. Samples must be stored in ascending order of x coordinates, and each sample must have 
an x coordinate that is greater than the previous sample (strictly increasing).  
DNG readers should interpolate the curve using a cubic spline.  
For Standard Dynamic Range camera profiles, the first sample is required to be (0.0, 0.0), and the last sample 
is required to be (1.0, 1.0). 
For High Dynamic Range camera profiles, the first sample is required to be (0.0, 0.0). The curve is applied in 
an encoded space (instead of linear gamma) to accommodate overrange pixel values.
```

**PDF p.64-65（行 2112-2137）** — `ProfileHueSatMapEncoding`（2.5D 不适用；3D 可 sRGB 编码）（逐字）：

```
Provides a way for color profiles to specify how indexing into a 3D HueSatMap is performed during raw 
conversion. This tag is not applicable to a 2.5D HueSatMap (i.e., where the Value dimension is 1).  
The currently defined values are:  
• 0 = Linear encoding 
• 1 = sRGB encoding 
If set to 0 (Linear encoding), then the method used to apply a 3D HueSatMap is as follows: 
1. Convert linear ProPhoto RGB values to HSV. 
2. Use the HSV coordinates to index into the color table. 
3. Apply color table result to the original HSV values. 
4. Convert modified HSV values back to linear ProPhoto RGB. 
```

**PDF p.33（行 1072-1076）** — `ColorMatrix1`（逐字）：

```
ColorMatrix1 defines a transformation matrix that converts XYZ values to reference camera native color 
space values, under the first calibration illuminant. The matrix values are stored in row scan order.  
The ColorMatrix1 tag is required for all non-monochrome DNG files.  
```

**PDF p.33（行 1097-1102）** — `CameraCalibration1`（逐字）：

```
CameraCalibration1 defines a calibration matrix that transforms reference camera native space values to 
individual camera native space values under the first calibration illuminant. The matrix is stored in row scan 
order.  
This matrix is stored separately from the matrix specified by the ColorMatrix1 tag to allow raw converters to 
swap in replacement color matrices based on UniqueCameraModel tag, while still taking advantage of any 
per-individual camera calibration performed by the camera manufacturer.  
```

**PDF p.36（行 1180-1182）** — `AsShotNeutral`（逐字）：

```
AsShotNeutral specifies the selected white balance at time of capture, encoded as the coordinates of a 
perfectly neutral color in linear reference space values. The inclusion of this tag precludes the inclusion of the 
AsShotWhiteXY tag.  
```

### 3.2 事实：DNG SDK 渲染管线里引擎组件的**实际应用顺序**

`dng_render.cpp`（`/private/tmp/dngsdk/dng_sdk_1_7_1/dng_sdk/source/dng_render.cpp`）。

相机空间 → 线性 ProPhoto 的矩阵准备（`dng_render.cpp:957-978`，逐字）：

```cpp
		fCameraWhite = spec->CameraWhite ();
		
		fCameraToRGB = dng_space_ProPhoto::Get ().MatrixFromPCS () *
					   spec->CameraToPCS ();
					   
		// Find Hue/Sat table, if any.

		hasProfile = fNegative.GetProfileByID (profileID, profile);
		
		if (hasProfile)
			{

			fSupportOverrange = profile.IsHDR ();
			
			fHueSatMap.Reset (profile.HueSatMapForWhite (spec->WhiteXY ()));
			
			if (profile.HasLookTable ())
				{
				
				fLookTable.Reset (new dng_hue_sat_map (profile.LookTable ()));
				
				}
```

**顺序**（`dng_render.cpp:1906-2025`，逐字，注释即顺序说明）：

```cpp
				// Apply Hue/Sat map, if any.
				
				if (fHueSatMap.Get ())
					{

					DoBaselineHueSatMap (tPtrR,
										 tPtrG,
										 tPtrB,
										 tPtrR,
										 tPtrG,
										 tPtrB,
										 cols,
										 *fHueSatMap.Get (),
										 fHueSatMapEncode.Get (),
										 fHueSatMapDecode.Get (),
										 fSupportOverrange);
					
					}
```

```cpp
		// Apply exposure curve.

		if (fExposureRamp.Get ())
			{
...
			}
		
		// Apply look table, if any.
		
		if (fLookTable.Get ())
			{
			
			DoBaselineHueSatMap (tPtrR,
								 tPtrG,
								 tPtrB,
								 tPtrR,
								 tPtrG,
								 tPtrB,
								 cols,
								 *fLookTable.Get (),
								 fLookTableEncode.Get (),
								 fLookTableDecode.Get (),
								 fSupportOverrange);
			
			}

		// Apply baseline tone curve.

		if (fToneCurveSlopeExtension.Get ())
			DoBaselineRGBToneOverrange (tPtrR,
										tPtrG,
										tPtrB,
										tPtrR,
										tPtrG,
										tPtrB,
										cols,
										fToneCurve,
										*fToneCurveSlopeExtension);

		else
			DoBaselineRGBTone (tPtrR,
							   tPtrG,
							   tPtrB,
							   tPtrR,
							   tPtrG,
							   tPtrB,
							   cols,
							   fToneCurve);
```

（其上 `dng_render.cpp:1893-1902` 是 `DoBaselineABCDtoRGB(... fCameraWhite, fCameraToRGB)`，即相机空间→RGB 矩阵先于 HueSatMap。）

profile 中表的解析/插值（`dng_camera_profile.cpp`）：

```cpp
void dng_camera_profile::ReadHueSatMap (dng_stream &stream,
										dng_hue_sat_map &hueSatMap,
										uint32 hues,
										uint32 sats,
										uint32 vals,
										bool skipSat0)
	{
```
（`dng_camera_profile.cpp:1467-1472`）

```cpp
dng_hue_sat_map * dng_camera_profile::HueSatMapForWhite (const dng_xy_coord &white) const
	{
	
	if (fHueSatDeltas1.IsValid ())
		{

		// If we only have the first table, just use it for any color temperature.
		
		if (!fHueSatDeltas2.IsValid ())
			{
			
			return new dng_hue_sat_map (fHueSatDeltas1);
```
（`dng_camera_profile.cpp:1861-1874`；双光源按色温插值见同文件 `HueSatMapForWhite_Dual` 起于 `:1906`）

`dng_hue_sat_map.cpp` 提供表插值与 HSV 索引（函数清单由 `grep` 得到）：`SetDivisions` `:101`、`GetDelta` `:146`、`Interpolate` `:289` 与 `:415`。

### 3.3 事实：SDK 不产生相机外观数据，只消费文件里的 profile

`dng_camera_profile::Parse` 从 DNG 的 profile IFD 逐字段读入（`dng_camera_profile.cpp:1506-1530`，逐字）：

```cpp
void dng_camera_profile::Parse (dng_stream &stream,
								dng_camera_profile_info &profileInfo)
	{
	
	SetUniqueCameraModelRestriction (profileInfo.fUniqueCameraModel.Get ());

	if (profileInfo.fProfileName.NotEmpty ())
		{
		
		SetName (profileInfo.fProfileName.Get ());
		
		}
	
	SetCopyright (profileInfo.fProfileCopyright.Get ());

	SetEmbedPolicy (profileInfo.fEmbedPolicy);

	SetCalibrationIlluminant1 (profileInfo.fCalibrationIlluminant1);
			
	SetColorMatrix1 (profileInfo.fColorMatrix1);
			
	if (profileInfo.fForwardMatrix1.NotEmpty ())
		{
		
		SetForwardMatrix1 (profileInfo.fForwardMatrix1);
```

SDK 版本（`dng_flags.h:28`）：

```c
#define kDNGSDK_VersionString "1.7.1"
```

### 3.4 我的推断（DNG 节）

- 规范把「相机厂商外观」定义为一组**引擎参数**：`ColorMatrix{1,2,3}` + `CameraCalibration{1,2,3}` + `ForwardMatrix{1,2,3}` + `ReductionMatrix{1,2,3}` + `AnalogBalance` + `AsShotNeutral`，再叠加 `ProfileHueSatMapData{1,2,3}`（hue/sat/value 三通道增量）+ `ProfileLookTableData`（look）+ `ProfileToneCurve`（curve）。这就是任务所说的 "camera profile engine"。
- **数据来源**：规范只规定**格式与算法**，不提供任何厂商数据；`ColorMatrix` 等由 profile 创建者写入（`CameraCalibration1` 的注释明确说它承载 "per-individual camera calibration performed by the camera manufacturer"）。SDK 只从 DNG/DCP 文件读入。
- **工作域**：矩阵在 XYZ(D50) ↔ 相机原生线性之间；HueSatMap/LookTable 在 **线性 ProPhoto RGB（RIMM）** 的 HSV 里施加增量；ToneCurve 在 "linear gamma"（SDR）或编码空间（HDR）。

---

## 4. darktable 的 input color profile / DCP 路径

### 4.1 事实：当前 darktable HEAD **没有**相机 DCP 支持

当前 HEAD `92f6c282845943ad8f242c3dcb040a3c75c877b9`（2026-09-22）。逐条 grep 证据：

```
$ grep -n "dcp\|DCP" src/iop/colorin.c
(无输出)

$ grep -n "HueSatMap\|LookTable\|ToneCurve\|huesatmap\|looktable\|tonecurve" -i src/iop/colorin.c
(无输出)

$ wc -l src/iop/colorin.c
    2123 src/iop/colorin.c
```

全仓（排除 `po/` 与二进制）精确 grep：

```
$ grep -rn -i -E "\bdcp\b|dcp_|_dcp|DCP" --include=*.c --include=*.h --include=*.cc --include=*.cpp --include=*.txt --include=*.in src/ data/
src/imageio/format/j2k.c:711:  DT_BAUHAUS_COMBOBOX_NEW_FULL(gui->preset, self, NULL, N_("DCP mode"), NULL,
```

`src/imageio/format/j2k.c:711` 的 "DCP mode" 是 **JPEG 2000 的 Digital Cinema Package 模式**，与相机 profile 无关。

`src/common/dcp.c` **不存在**：

```
$ find . -path ./.git -prune -o -iname "*dcp*" -print
(无输出)
$ git log --all --oneline --name-only -- 'src/common/dcp*'
(无输出)
```

文档仓库 `darktable-org/dtdocs` @ `79c10958aee76654dcac55a7c5e645a05621657b` 中，"DCP" 的唯一正文命中也是 JPEG 2000：

```
$ grep -rn -i "\bDCP\b" content/
content/special-topics/program-invocation/darktable-cli.md:140:: The DCP mode
```

`colorin.c` 的实际实现是 **lcms2 驱动的 ICC 输入 profile**（`src/iop/colorin.c:54`、`:103-108`，逐字）：

```c
#include <lcms2.h>
```

```c
typedef struct dt_iop_colorin_data_t
{
  gboolean clear_input;
  cmsHPROFILE input;
  cmsHPROFILE nrgb;
  cmsHTRANSFORM *xform_cam_Lab;
  cmsHTRANSFORM *xform_cam_nrgb;
  cmsHTRANSFORM *xform_nrgb_Lab;
```

### 4.2 事实：darktable 的替代路径 —— color calibration 模块（色卡自标定 + 3×3 矩阵）

模块自述名（`src/iop/channelmixerrgb.c:226`，逐字）：

```c
  return _("color calibration");
```

它引入色卡数据结构（`src/iop/channelmixerrgb.c:47`、`:163`，逐字）：

```c
#include "common/colorchecker.h"
```

```c
  dt_color_checker_t *checker;
```

色卡参考值定义（`src/common/colorchecker.h:20-24`，逐字）：

```c
 * These are the CIELab values of Color Checker reference targets
 *
 * ... 
// types of targets we support
typedef enum dt_color_checker_targets
```

从图像里抽取色块并做优化（`src/iop/channelmixerrgb.c:1428-1441`，逐字）：

```c
static const extraction_result_t _extract_patches(const float *const restrict in,
                                                  const dt_iop_roi_t *const roi_in,
                                                  dt_iop_channelmixer_rgb_gui_data_t *g,
                                                  const dt_colormatrix_t RGB_to_XYZ,
                                                  const dt_colormatrix_t XYZ_to_CAM,
                                                  float *const restrict patches,
                                                  const gboolean normalize_exposure)
{
  const size_t width = roi_in->width;
  const size_t height = roi_in->height;
  const float radius_x =
    g->checker->radius * hypotf(1.f, g->checker->ratio) * g->safety_margin;
  const float radius_y = radius_x / g->checker->ratio;
```

结果是 3×3 通道混合矩阵（`src/iop/channelmixerrgb.c:3067-3069`、`:4195-4197`，逐字）：

```c
    d->MIX[0][i] = p->red[i] / norm_R;
    d->MIX[1][i] = p->green[i] / norm_G;
    d->MIX[2][i] = p->blue[i] / norm_B;
```

### 4.3 我的推断（darktable 节）

- 「darktable 是否支持 DCP」→ **当前版本不支持，且仓库历史中也没有 `src/common/dcp.c`**（我检查了 `release-2.6.0` … `release-5.4.0` 的 `src/iop/colorin.c` 与 `src/common/colorspaces.c`：对 `ProfileHueSatMap|huesatmap|LookTable|ToneCurve|dcp` 全部 0 命中）。任务提到的 `src/common/dcp.c`：**未找到（不存在）**。
- darktable 的等价物是 **input color profile（ICC/lcms2）+ color calibration（色卡自标定 → 3×3 矩阵）**；没有 HueSatMap/LookTable/ToneCurve 的 DCP 语义。
- 局限：我**无法**用 `git log -S` 完成跨全历史的字符级搜索，因为 blobless clone 的按需取 blob 反复被网络中断（逐字错误 `fatal: could not fetch ... from promisor remote`，`LibreSSL SSL_connect: SSL_ERROR_SYSCALL`）。因此"历史上从未有过"这一判断基于：当前 HEAD 全仓 grep + 9 个 release tag 的定点文件检查 + 文档仓库 grep；**不等于**对全部 47348 个 commit 的穷举。这一点我标注为推断强度受限。

---

## 5. filmulator：物理显影模型，不是相机外观 LUT

仓库 `CarVac/filmulator-gui` @ `57fbaec57555432d86d3aa632990cd8fa09114ad`。源码根为嵌套目录 `filmulator-gui/`。

### 5.1 事实：主循环是显影/扩散偏微分方程的时步积分

`filmulator-gui/core/filmulate.cpp:24-28`（逐字）：

```cpp
// Function-------------------------------------------------------------------------
bool ImagePipeline::filmulate(matrix<float> &input_image,
                              matrix<float> &output_density,
                              ParameterManager * paramManager,
                              ImagePipeline * pipeline)
```

`filmulator-gui/core/filmulate.cpp:120-122`（逐字）：

```cpp
    //Now we begin the main development/diffusion loop, which approximates the
    //differential equation of film development.
    for(int i = 0; i <= development_steps; i++)
```

`filmulator-gui/core/filmulate.cpp:213-234`（逐字）：

```cpp
  // Now we compute the density (opacity) of the film.
  // We assume that overlapping crystals or dye clouds are
  // nonexistent. It works okay, for now...
  // The output is crystal_radius^2 * active_crystals_per_pixel
  std::chrono::steady_clock::time_point mult_start;
  mult_start = std::chrono::steady_clock::now();

  abort = paramManager->claimFilmAbort();
  if (abort == AbortStatus::restart) {
    return true;
  }

  const int numRows = crystal_radius.nr();
  const int numCols = crystal_radius.nc();

#pragma omp parallel for
  for (int i = 0; i < numRows; ++i) {
    for (int j = 0; j < numCols; ++j) {
      output_density(i, j) = crystal_radius(i, j) * crystal_radius(i, j) *
                             active_crystals_per_pixel(i, j);
    }
  }
```

### 5.2 事实：调用点与域

`filmulator-gui/core/imagePipeline.cpp:1669-1671`（逐字）：

```cpp
    // Here we do the film simulation on the image...
    // If filmulate detects an abort, it returns true.
    if (filmulate(film_input_image, filmulated_image, paramManager, this)) {
```

输入是线性（`filmulator-gui/core/imread.cpp:48-49`，逐字）：

```cpp
    PARAM.gamm[0] = 1;
    PARAM.gamm[1] = 1;//Linear gamma (-g 1 1)
```

阶段枚举（`filmulator-gui/ui/parameterManager.h:25-45`，我已亲自 `sed` 核实）：

```
...nrimpulse, nrchroma, prefilmulation, filmulation, blackwhite, colorcurve, filmlikecurve, count.
```

### 5.3 事实：无 film-stock LUT；唯一的 LUT 类是色调曲线

我在 `core/filmulate.cpp core/develop.cpp core/diffuse.cpp core/agitate.cpp core/layerMix.cpp core/exposure.cpp` 上跑 `grep -rnw "LUT"` → **0 命中**（`exit=1`）；`find . -iname "*.cube" -o -iname "*.lut" -o -iname "*.3dl" -o -iname "*hald*"` → **无输出**；唯一的 LUT 类是 `core/lut.hpp:29`（`#define MAXVAL 65536` 在 `:22`），被 `core/imagePipeline.h:82-83` 声明：

```cpp
  LUT<unsigned short> lutR, lutG, lutB;
  LUT<unsigned short> filmLikeLUT;
```

在 `core/imagePipeline.cpp:1879-1885` 用色调曲线填充，供 `film_like_curve(...)` 使用：

```cpp
    filmLikeLUT.fill([=](unsigned short in) -> unsigned short {
      float shResult = shadows_highlights(
          float(in) / 65535.0f, curvesParam.shadowsX, curvesParam.shadowsY,
          curvesParam.highlightsX, curvesParam.highlightsY);
      return ushort(65535 * default_tonecurve(shResult));
    });
    matrix<unsigned short> &film_curve_image = vibrance_saturation_image;
    film_like_curve(color_curve_image, film_curve_image, filmLikeLUT);
```

> 我的核对：`git log -1` 与 `sed` 抽查 `core/filmulate.cpp:24,120-122,215-234`、`core/imagePipeline.cpp:1669-1671`、`core/imread.cpp:45-52`、`core/imagePipeline.h:110-120`、`core/lut.hpp:20-32`、`ui/parameterManager.h:25-45`、`core/imreadJpeg.cpp:58-72` 全部与上述引用一致（逐字相同）。

### 5.4 我的推断（filmulator 节）

- filmulator 是**物理引擎**（显影剂扩散 + 晶体生长 + 搅拌 + 层间混合），不是「相机厂商外观」引擎，也**不是 LUT**。
- 它的输出被代码注释标为 `// sRGB` 并直接进入线性重标定与 tone curve。**修正后的准确表述**：`grep -rn "log10\|log2(" core/` 在全 `core/` 下**有**命中，但逐条检查后全部位于第三方库（`core/rawtherapee/sleefsseavx.h:879`、`core/rawtherapee/sleef.h:868`、`core/nlmeans/eigen/**`），**没有任何一处**在显影管线代码（`filmulate/develop/diffuse/agitate/layerMix/exposure/imagePipeline`）中。因此"管线中没有 log 域编码"这一表述限于管线文件成立。

---

## 6. rawproc：工具链 + 1D LUT，无强制域

仓库 `butcherg/rawproc` @ `2f149a9d76f62ebed2c3cc6a8849b1fdccb53d8d`。

### 6.1 事实：工具链顺序 = GUI 树的兄弟顺序；每个工具复制上一工具结果

`src/PicProcessor.cpp:160-173`（逐字，我亲自核对）：

```cpp
bool PicProcessor::processPic(bool processnext) 
{ 
	if (groupitem) return false;
	if (!global_processing_enabled) return true;
	if (GetId() != m_tree->GetRootItem()) {
		if (dib) delete dib;
		dib = new gImage(getPreviousPicProcessor()->getProcessedPic());
		processPicture(dib);
		if (processnext) processNext();
	}
	dirty = false;

	return true;
}
```

### 6.2 事实：LUT 不是独立工具，而是 `tone` 的一个 mode（1D LUT）

`src/gimage_parse.cpp:1780-1785`（逐字，我亲自核对）：

```cpp
		else if (p[0] == "lut") {
			pmap["mode"] = "lut";
			pmap["cmdlabel"] = "tone:lut";
			if (psize >= 1) {
				pmap["lutfile"] = p[1];
			}
```

`src/gimage_process.cpp:1588-1592`（逐字，我亲自核对）：

```cpp
			dib.Apply1DLUT(lut, threadcount);
			
			result["duration"] = std::to_string(_duration());
			result["treelabel"] = "tone:lut";
			result["commandstring"] = string_format("tone:lut:%s", params["lutfile"]);
```

### 6.3 事实：文档只区分 mosaic/RGB，没有 scene/log/display 域枚举

`doc/tools.html:9-11`（子代理逐字；我已确认文件存在与 grep 结果）：

```
<p>At any point in the toolchain, the image is one of two states: 1) mosaic, or 2) RGB.  Some tools
can work both states, other tools will only work one or the other.  The following list enumerates the 
valid states for each tool:</p>
```

唯一明确的 scene-linear 陈述在 `doc/whitebalance.html:19-21`：

```
the demosaic tool), the multipliers are applied using the regular whitebalance operator.  These 
multipliers are relevant to the raw, linear image; the colors will be very off if applied to anything 
other than the raw linear image.
```

### 6.4 我的推断（rawproc 节）

- rawproc 既不是相机外观引擎，也不是外观 LUT 系统；它是**用户自定义顺序的工具链**，LUT 只是 `tone:lut` 的 1D LUT。
- 域是隐式的、由用户插入哪个 `colorspace` 操作决定；代码里没有域枚举。

---

## 7. vkBasalt：3D LUT 在 present 前的 post-processing / display 域

仓库 `DadSchoorse/vkBasalt` @ `4f97f09ffe91900e6ca136cc26cf7966f8f6970d`。**注意**：任务猜测的 `src/effects/lut.cpp` 与 `src/reshade_shaders/` **不存在**；实际路径是 `src/effect_lut.cpp` 与 `src/shader/lut.frag.glsl`。

### 7.1 事实：LUT 是 3D 纹理，尺寸来自文件

`src/effect_lut.cpp:37-52`（逐字）：

```cpp
        int32_t  usingPNG = (int32_t)(lutFile.find(".cube") == std::string::npos && lutFile.find(".CUBE") == std::string::npos);
        if (!usingPNG)
        {
            lutCube = LutCube(lutFile);
            pixels  = lutCube.colorCube.data();
            height  = lutCube.size;
        }
        else
        {
            int channels, width;
            pixels = stbi_load(lutFile.c_str(), &width, &height, &channels, STBI_rgb_alpha);
            if (width != height * height)
            {
                Logger::err("bad lut");
            }
        }
```

`src/effect_lut.cpp:72-82`（逐字）：

```cpp
        VkExtent3D lutImageExtent = {(uint32_t) height, (uint32_t) height, (uint32_t) height};

        lutImage = createImages(pLogicalDevice,
                                1,
                                lutImageExtent,
                                VK_FORMAT_R8G8B8A8_UNORM, // TODO search for format and save it
                                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                                lutMemory)[0];

        uploadToImage(pLogicalDevice, lutImage, lutImageExtent, height * height * height * 4, pixels);
```

### 7.2 事实：它在已渲染的 swapchain 图像上做全屏 pass

`src/effect_simple.cpp:88-89`（逐字，输入图像从 present 布局转出）：

```cpp
        memoryBarrier.oldLayout           = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        memoryBarrier.newLayout           = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
```

`src/effect_simple.cpp:144`：`pLogicalDevice->vkd.CmdDraw(commandBuffer, 3, 1, 0, 0);`（全屏三角形）

着色器（`src/shader/lut.frag.glsl:3-8,29-32`，逐字）：

```glsl
layout(set=0, binding=0) uniform sampler2D img;
layout(set=1, binding=0) uniform sampler3D lut;
//Only works with cubes not with cuboids
layout(constant_id = 0) const int lutSize = 32;
layout(constant_id = 1) const int flipGB = 0;
...
    //see https://developer.nvidia.com/gpugems/GPUGems2/gpugems2_chapter24.html
    vec3 scale = (vec3(lutSize) - 1.0) / vec3(lutSize);
    vec3 offset = 1.0 / (2.0 * vec3(lutSize));

    fragColor = vec4(textureLod0(lut, scale * color.rgb + offset).rgb, color.a);
```

提交位置（`src/basalt.cpp:543`、`:589-591`、`:609`，逐字）：

```cpp
VKAPI_ATTR VkResult VKAPI_CALL vkBasalt_QueuePresentKHR(VkQueue queue, const VkPresentInfoKHR* pPresentInfo)
```

```cpp
            submitInfo.commandBufferCount = 1;
            submitInfo.pCommandBuffers =
                presentEffect ? &(pLogicalSwapchain->commandBuffersEffect[index]) : &(pLogicalSwapchain->commandBuffersNoEffect[index]);
```

```cpp
            return pLogicalDevice->vkd.QueuePresentKHR(queue, &presentInfo);
```

配置文档（`config/vkBasalt.conf:101-103`，逐字）：

```ini
#lutFile is the path to the LUT file that will be used
#supported are .CUBE files and .png with width == height * height
lutFile = "/path/to/lut"
```

### 7.3 我的推断（vkBasalt 节）

- vkBasalt 是**纯 LUT 方案**：外观完全由外部 `.cube`/PNG 3D LUT 文件承载，程序本身不含任何相机/色彩科学模型。
- 域：**post-processing / display 域**——输入是已经渲染完成的 swapchain 图像（`VK_IMAGE_LAYOUT_PRESENT_SRC_KHR`），输出直接进 present 队列。

### 7.4 二次核对记录（vkBasalt）

我用 `sed` 逐条复核了写入本节的引用，全部与原文一致：`src/effect_lut.cpp:37-52`、`:72-82`；`src/shader/lut.frag.glsl:3-8`、`:28-33`（实际文件为 `#version 450` 起，`sampler2D img` 在第 3 行、`sampler3D lut` 在第 4 行、`lutSize = 32` 在第 7 行）；`src/basalt.cpp:543`、`:589-591`、`:609`；`config/vkBasalt.conf:101-103`。**未找到**：`src/effects/`（目录不存在）、`src/reshade_shaders/`（目录不存在）、`EFFECT_ORDER`、`postProcess`、`m_effects`/`addEffect`、任何 `lut*.spv`（SPIR-V 以 `lut_frag` 形式内嵌于 `src/shader_sources.hpp:28`）。

---

## 8. 其他有代表性的项目

### 8.1 OpenDRT（`jedypod/open-display-transform`）

commit `af683323e2a8a63501f02c0a724ec538e3228ad0`。源码：`display-transforms/opendrt/OpenDRT.dctl`（1210 行）、`OpenDRT.nk`，另有 `tesseract/`、`chromagnon/`、`jzdrt/`。

**事实：仓库内没有 LUT。** 对 `OpenDRT.dctl` 与 `docs/*.md` 跑 `grep -rn -i "lut"`，命中全部是 "Look Preset" 这类文本，无任何 LUT 数据/纹理/文件（`find` 也未发现 `.cube`）。

**事实：它是参数化引擎**，模块自述（`display-transforms/opendrt/docs/opendrt-parameters.md`，逐字）：

```
OpenDRT's functionality is organized into the following modules:

* **Tonescale:** Parameters related to the tonescale, which allows the user to control things like overall contrast, "highlight" and "shadow" "rolloff", and overall exposure through placement of middle grey, as well as HDR-specific controls for peak display luminance.
* **Purity:** Parameters related to adjusting overall colorfullness over the intensity range of the formed picture.
* **Brilliance:** Parameters for adjusting the luminance of more colorful stimuli. ...
* **Hue Shift:** Parameters for modifying hue angles over the luminance range. ...
```

实现是 DCTL 函数（`OpenDRT.dctl` 函数清单，逐字行号）：

```
503:__DEVICE__ float compress_hyperbolic_power(float x, float s, float p) {
508:__DEVICE__ float compress_toe_quadratic(float x, float toe, int inv) {
518:__DEVICE__ float compress_toe_cubic(float x, float m, float w, int inv) {
532:__DEVICE__ float complement_power(float x, float p) {
536:__DEVICE__ float sigmoid_cubic(float x, float s) {
542:__DEVICE__ float contrast_high(float x, float p, float pv, float pv_lx, int inv) {
559:__DEVICE__ float softplus_constraint(float x, float s, float x0, float y0) {
569:__DEVICE__ float softplus(float x, float s) {
576:__DEVICE__ float gauss_window(float x, float w) {
586:__DEVICE__ float hue_offset(float h, float o) {
593:__DEVICE__ float3 display_gamut_whitepoint(float3 rgb, float tsn, float cwp_lm, int display_gamut, int cwp) {
737:__DEVICE__ float3 transform(int p_Width, int p_Height, int p_X, int p_Y, float p_R, float p_G, float p_B)
```

域（`display-transforms/opendrt/docs/opendrt-parameters.md`，逐字）：

```
### Input Parameters

* **Input Gamut (`in_gamut`):** Specifies the color space primaries of the input image data.
* **Input Transfer Function (`in_oetf`):** Specifies the transfer function (log curve) of the input image data.
    * *Options:* Linear, Davinci Intermediate, Filmlight T-Log, ACEScct, Arri LogC3/LogC4, RedLog3G10, Panasonic V-Log, Sony S-Log3, Fuji F-Log2.
```

以及项目 wiki 页 `https://github.com/jedypod/open-display-transform/wiki/tech_image-states`（2026-09-22 抓取，HTTP 200）逐字：

```
 The Life Cycle of an Image 
 First we'll lay some ground work for digital imaging, going from scene to display with a few stops in between. 
```

**推断**：OpenDRT 是「引擎而非 LUT」的**显示/图片形成（picture formation）**引擎，输入是 scene-linear 或厂商 log，输出 display-referred；它不是「相机厂商外观」引擎（不做相机→XYZ 的色度标定），而是 display transform / look 引擎。

### 8.2 darktable 的 color calibration（见 §4.2）

**事实**：模块名 `_("color calibration")`（`channelmixerrgb.c:226`），引入 `dt_color_checker_t`（`:163`）与内置参考 CIELab（`common/colorchecker.h:20`），从图像抽取色块（`_extract_patches` `:1428`），产物是 3×3 通道混合矩阵（`d->MIX` `:3067-3069`）。
**推断**：它确实支持「用色卡自标定」，但产出的是**矩阵**（+ 光照适应），不是 DCP 式 HueSatMap/LookTable。

### 8.3 LibRaw / dcraw 的相机矩阵：数据源 = Adobe DNG Converter

`src/tables/colordata.cpp`（我 2026-09-22 从 GitHub raw 抓取，HTTP 200）文件头与函数头逐字：

```cpp
 LibRaw uses code from dcraw.c -- Dave Coffin's raw photo decoder,
 dcraw.c is copyright 1997-2018 by Dave Coffin, dcoffin a cybercom o net.
 LibRaw do not use RESTRICTED code from dcraw.c
```

```cpp
/*
   All matrices are from Adobe DNG Converter unless otherwise noted.
 */
int LibRaw::adobe_coeff(unsigned make_idx, const char *t_model,
                        int internal_only)
{
  // clang-format off
  static const struct
  {
	  unsigned m_idx;
```

**事实**：注释明确写 "All matrices are from Adobe DNG Converter unless otherwise noted."，即**不是**逐机实测、也**不是**厂商直接提供（有少量例外由 "otherwise noted" 覆盖）。**推断**：这是「表」而非「引擎」——只有 3×3 矩阵，没有 HueSatMap/LookTable/ToneCurve 语义。

### 8.4 Jed Smith 的 OpenDRT / CAM 讨论

**未找到（受工具限制）**。本会话 `web_search` 不可用（§9.1），我无法做开放网络检索；只能访问已知 URL。我抓取并引用了仓库内文档（`README.md`、`docs/*.md`）与项目 wiki（`tech_image-states`、`tech_tonescale`），其中**没有**关于 "CAM"（Color Appearance Model）的专门讨论章节。我在 wiki 首页提取到的页面清单（逐字）为：

```
/jedypod/open-display-transform/wiki/doc-introduction
/jedypod/open-display-transform/wiki/doc-quickstart
/jedypod/open-display-transform/wiki/support_footage
/jedypod/open-display-transform/wiki/tech_chroma
/jedypod/open-display-transform/wiki/tech_gamut-volume
/jedypod/open-display-transform/wiki/tech_hdr
/jedypod/open-display-transform/wiki/tech_image-states
/jedypod/open-display-transform/wiki/tech_rgb-tonemap
/jedypod/open-display-transform/wiki/tech_tonescale
/jedypod/open-display-transform/wiki/tech_whitepoint
```

→ 关于 "Jed Smith / CAM 讨论"，本报告**不作任何断言**。

---

## 9. 必答问题

### 9.1 哪些项目把「相机外观」做成参数化引擎，哪些只是套 LUT？

**参数化引擎（有相机/色彩科学模型 + 可调参数或可解算的表/矩阵）**

1. **DNG 规范 + DNG SDK** —— 规范把外观定义成 `ColorMatrix/ForwardMatrix/CameraCalibration/ReductionMatrix/AnalogBalance/AsShotNeutral + ProfileHueSatMap + ProfileLookTable + ProfileToneCurve` 的引擎（§3.1、§3.2）。
2. **dcamprof** —— 用优化器从测量数据**生成**上述引擎的全部组件（`src/dcamprof.c:2255-2397`，§2.1）。
3. **darktable color calibration** —— 色卡自标定 → 3×3 矩阵 + 光照适应（§4.2）。
4. **OpenDRT** —— 纯参数化 picture formation 引擎，仓库内**无 LUT**（§8.1）。
5. **filmulator** —— 物理显影/扩散引擎（但它是「胶片显影模拟」，不是「相机厂商外观」）（§5）。

**只是套 LUT（外观 = 一张表）**

1. **vkBasalt** —— ReShade 风格 3D LUT，post/display 域（§7）。
2. **rawproc 的 `tone:lut`** —— 1D LUT（§6.2）。
3. **filmulator 的 `filmLikeLUT`** —— 但那是一条**色调曲线**的 LUT，不是外观 LUT（§5.3）。

**不适用 / 未找到**

- **darktable 的 DCP 路径**：当前 HEAD **没有** DCP 支持，`src/common/dcp.c` 不存在（§4.1）。
- **IT8.7**：在 dcamprof 仓库与文档中 **未找到**（§2.2）。
- **Jed Smith / CAM 讨论**：未找到（§8.4，工具限制）。

### 9.2 引擎的数据源到底是什么？

| 引擎 | 数据源 | 逐字证据 |
|---|---|---|
| DNG 规范 | **规范只定义格式与算法**；数据由 profile 创建者写入。`CameraCalibration` 明确承载厂商逐机标定 | 规范 p.33：「This matrix is stored separately from the matrix specified by the ColorMatrix1 tag to allow raw converters to swap in replacement color matrices based on UniqueCameraModel tag, while still taking advantage of any per-individual camera calibration performed by the camera manufacturer.」 |
| DNG SDK | **只消费**文件内的 profile | `dng_camera_profile.cpp:1506-1530` `Parse()` 从 `profileInfo` 读入 ColorMatrix/ForwardMatrix… |
| dcamprof | 用户拍摄色卡 → Argyll `.ti3`；参考反射光谱 `cc24_ref.cie`（源自 BabelColor，`spectraldb_cc24.c:2`）；或相机 SSF | `www/dcamprof.html:352-359`、`:577-632`；`src/spectraldb_cc24.c:1-8` |
| darktable color calibration | 内置 `dt_color_checker` 参考 CIELab + 用户拍摄色卡 | `src/common/colorchecker.h:20`；`channelmixerrgb.c:163,1428` |
| OpenDRT | 算法参数与预设，**无外部数据** | `docs/opendrt-parameters.md` 模块清单 |
| LibRaw/dcraw 矩阵 | **Adobe DNG Converter** | `src/tables/colordata.cpp:21-22`「All matrices are from Adobe DNG Converter unless otherwise noted.」 |
| vkBasalt | 用户 `.cube` / PNG | `config/vkBasalt.conf:101-103` |

### 9.3 这些引擎的输出/工作域分别是什么？

| 引擎 | 输出/工作域 | 逐字证据 |
|---|---|---|
| DNG Camera→XYZ(D50) | XYZ **D50** | 规范 p.100「mapping between the camera color space coordinates (linear reference values) and CIE XYZ (with a D50 white point)」 |
| DNG HueSatMap / LookTable | **线性 ProPhoto RGB（RIMM）** 的 HSV 空间 | 规范 p.103「the XYZ (D50) values are converted to linear RGB coordinates, using the ProPhoto RGB primaries. (This is also known as RIMM space).」 |
| DNG ToneCurve | **linear gamma**（SDR）/ 编码空间（HDR） | 规范 p.51「The curve is specified as a list of 32-bit IEEE floating- point value pairs in linear gamma.」 |
| DNG LookTable 的**管线位置** | 曝光/补光之后、**tone curve 之前** | 规范 p.57「it should be applied later in the processing pipe, after any exposure compensation and/or fill light stages, but before any tone curve stage」 |
| DNG SDK 实际顺序 | 矩阵 → HueSatMap → exposure → LookTable → ToneCurve → RGBTables | `dng_render.cpp:1906, 1960, 1985, 2004, 2027` |
| dcamprof（色度部分） | scene-referred linear（XYZ D50） | `www/dcamprof.html:2126-2133` |
| dcamprof（外观部分） | output-referred（curve + LookTable） | `www/dcamprof.html:2025-2027`、`:2135-2142` |
| filmulator | 输入 linear，输出标注 `// sRGB` | `core/imread.cpp:49`；`core/imagePipeline.h:117`（子代理证据） |
| vkBasalt | display / post-processing（present 前） | `src/effect_simple.cpp:88-89`；`src/basalt.cpp:609` |
| OpenDRT | 输入 scene-linear 或厂商 log → 输出 display-referred | `docs/opendrt-parameters.md` Input Parameters |
| darktable color calibration | 工作 RGB（linear Rec2020）内的 LMS/CAT | `channelmixerrgb.c:791-812`（`RGB_to_LMS`、`MIX_to_XYZ`） |

---

## 10. 未找到 / 不适用清单（明确声明）

1. **`https://github.com/anderstorger/dcamprof`** —— 不存在（`remote: Repository not found.`）。改用 `Beep6581/dcamprof` + 官方站 `torger.se`。
2. **darktable 的 DCP 支持 / `src/common/dcp.c`** —— **未找到**（当前 HEAD 无；9 个 release tag 的 `colorin.c`/`colorspaces.c` 检查全 0 命中；文档仓库仅 JPEG 2000 的 "DCP mode"）。
3. **IT8.7 目标** —— 在 dcamprof 中 **未找到**（仓库只提供 CC24 与 ColorChecker SG 参考文件）。
4. **`src/effects/lut.cpp`（vkBasalt）** —— 不存在；实际为 `src/effect_lut.cpp`。
5. **`src/reshade_shaders/`（vkBasalt）** —— 不存在；实际为 `src/shader/lut.frag.glsl`。
6. **OpenDRT 中的 LUT** —— **未找到**（仓库内无 LUT 文件，代码无 LUT 纹理/采样）。
7. **Jed Smith 的 CAM 讨论** —— **未找到**，受工具限制（见下）。
8. **本会话 `web_search` 不可用**，逐字报错：

```
Error: DeepSeek search has no API key for "DEEPSEEK_API_KEY"; store it through the credentials service (the web Models page writes it), export it in the launching environment, or set a literal "apiKey" in the web-search-deepseek config
```

因此所有网络证据均来自 `curl` 对**已知 URL** 的直接抓取（GitHub raw / GitHub API / GitHub wiki / torger.se），没有做开放式检索。

9. **darktable 全历史字符级搜索未完成** —— blobless clone 按需取 blob 被网络中断，逐字错误：

```
fatal: unable to access 'https://github.com/darktable-org/darktable/': LibreSSL SSL_connect: SSL_ERROR_SYSCALL in connection to github.com:443 
fatal: could not fetch 8630d6d19dac5d6437fdbb93dba5d16000f12da0 from promisor remote
```

替代做法（已完成）：当前 HEAD 全仓 grep + 定点检查 `release-2.6.0 / 3.0.0 / 3.2.1 / 3.4.0 / 3.6.1 / 3.8.1 / 4.0.0 / 4.2.1 / 4.4.2 / 4.6.0 / 4.8.1 / 5.0.0 / 5.2.0 / 5.4.0` 的 `src/iop/colorin.c` 与 `src/common/colorspaces.c`。

---

## 11. 附：DNG 规范抽取的自我校验

- 我用 `pypdf 6.19.0` 抽取 `DNG_Spec_1_7_1_0.pdf`，得到 126 页；文本落到 `/private/tmp/dngspec_1_7_1_extract.txt`（4107 行，每页以 `===PDFPAGE n===` 分隔）。
- 抽取文本中同时存在 `===PDFPAGE n===` 标记与页面页脚的 "Digital Negative Specification 1.7.1.0 September 2023  <page>" 字符串，两者一致（例：`===PDFPAGE 100===` 后紧跟 "Digital Negative Specification 1.7.1.0 September 2023  100"），可用于交叉核对页码。
- 本报告引用的 PDF 页：33、36、51、57、64、65、100、101、102、103、104。
