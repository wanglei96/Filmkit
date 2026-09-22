# 04 · ACES 体系与 libplacebo/mpv 色彩管线源码取证

任务：独立源码/规范调研，禁止臆测。每条结论附逐字证据（`路径:行号` 或 `URL` + 原文片段）。
找不到的写"未找到"。区分**事实**（有逐字证据）与**推断**（由事实推导，标注为推断）。

调研时间：本次会话。macOS。所有 clone 位于 `/private/tmp/`。

---

## 0. 本次取证用到的仓库与 commit hash（全部实测）

| 仓库 | clone 结果 | commit hash | 日期 | 备注 |
|---|---|---|---|---|
| `https://github.com/haasn/libplacebo` | 成功 | `e2972fdd09adacd383656738d7d280f0cd84a761` | 2026-09-18 | `renderer: always clip the output to the permissible signal range` |
| `https://github.com/mpv-player/mpv` | 成功 | `c64675679925df40c3cbecb49bdfb9c93f918745` | 2026-09-22 | `demux_lavf: fix stack-use-after-scope in edition titles`（后经 `git fetch --unshallow` 拉全历史，55498 个提交） |
| `https://github.com/AcademySoftwareFoundation/OpenColorIO` | 成功 | `93878f9a8630081a6e8d866acf9fd119400c7a4e` | 2026-09-16 | `Adsk Contrib - Add RGB-to-HMJ FixedFunction (#2336)` |
| `https://github.com/AcademySoftwareFoundation/OpenColorIO-Config-ACES` | 成功 | `b7b08c8c2750f524c66a0ef24174b4ffaf8efe3c` | 2026-08-29 | `PR: Use Unified Transform Source (#177)` |
| `https://github.com/colour-science/OpenColorIO-Config-ACES` | 成功（**不同仓库**） | `ebd5df1e747a58d018a78a9b2a08fec74f60dd98` | 2022-08-08 | `PR: Studio Config Generator (#59)`；不是 ASWF 仓库的重定向，见 §A.1 |
| `https://github.com/ampas/aces-core` | 成功 | `069b0bc3e1f6c62820f19fdae2fecec3f4fc0f80` | 2026-09-01 | `docs: update README (#163)` |
| `https://github.com/ampas/aces-dev` | 成功，但**内容与 aces-core 完全相同**（同一 commit `069b0bc3…`） | 同上 | — | 事实：`git log -1` 两仓库同为 `069b0bc3e1f6c62820f19fdae2fecec3f4fc0f80`，说明 GitHub 已把 `ampas/aces-dev` 重定向到 `ampas/aces-core` |
| `https://github.com/ampas/aces-core` tag `v1.3` | 成功（`git clone --branch v1.3`） | `1256fee50ee35548c6eab8eca854ff3349008489` | 2021-04-30 | `merge branch 'release/v1.3'`（ACES 1.3 CTL 源码，含 `transforms/ctl/{rrt,odt}`） |

URL 可达性实测（`curl -sSL --max-time N -o /dev/null -w "HTTP=%{http_code}"`）：

```
https://libplacebo.org/                                            HTTP=200
https://mpv.io/manual/master/                                      HTTP=200
https://docs.acescentral.com/                                      HTTP=200
https://docs.acescentral.com/system-components/look-transforms/     HTTP=200
https://docs.acescentral.com/system-components/look-transforms/specification/  HTTP=200
https://docs.acescentral.com/system-components/input-transforms/    HTTP=200
https://docs.acescentral.com/system-components/output-transforms/   HTTP=200
https://docs.acescentral.com/system-components/output-transforms/technical-details/display-encoding/    HTTP=200
https://docs.acescentral.com/system-components/output-transforms/technical-details/rendering-overview/  HTTP=200
https://docs.acescentral.com/background/about-aces-2/               HTTP=200
https://docs.acescentral.com/background/about-rendering/            HTTP=200
https://docs.acescentral.com/glossary/                              HTTP=200
https://www.adobe.com/content/dam/acom/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf    HTTP=404
https://wwwimages2.adobe.com/content/dam/acom/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf  HTTP=404
https://www.adobe.com/content/dam/cc/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf      HTTP=404
https://download.adobe.com/pub/adobe/speedgrade/cube-lut-specification-1.0.pdf                          HTTP=404
```

注：本机 `curl` 访问 `github.com` 的**网页**路径会超时（`curl: (28) Connection timed out`），但 `git clone` / `git ls-remote` 与 `raw.githubusercontent.com` 正常。因此 GitHub 的"可达性"以 git 协议实测为准。

---

# A 部分 · ACES 体系

## A.1 OCIO config 的来源与真实可达性

### A.1.1 事实：OCIO 内置 ACES config 在 OpenColorIO 仓库的 `builtinconfigs/configs/`

实测目录（OpenColorIO @ `93878f9a`）：

```
src/OpenColorIO/builtinconfigs/configs/
  cg-config-v1.0.0_aces-v1.3_ocio-v2.1.ocio
  cg-config-v2.1.0_aces-v1.3_ocio-v2.3.ocio
  cg-config-v2.2.0_aces-v1.3_ocio-v2.4.ocio
  cg-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio
  studio-config-v1.0.0_aces-v1.3_ocio-v2.1.ocio
  studio-config-v2.1.0_aces-v1.3_ocio-v2.3.ocio
  studio-config-v2.2.0_aces-v1.3_ocio-v2.4.ocio
  studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio
```

逐字（`src/OpenColorIO/builtinconfigs/BuiltinConfigRegistry.cpp:23-25`，OpenColorIO @ `93878f9a`）：

```cpp
static constexpr char DEFAULT_BUILTIN_CONFIG_URI[] = "ocio://cg-config-v4.0.0_aces-v2.0_ocio-v2.5";
static constexpr char LATEST_CG_BUILTIN_CONFIG_URI[] = "ocio://cg-config-v4.0.0_aces-v2.0_ocio-v2.5";
static constexpr char LATEST_STUDIO_BUILTIN_CONFIG_URI[] = "ocio://studio-config-v4.0.0_aces-v2.0_ocio-v2.5";
```

### A.1.2 事实：**没有 `aces_1.2` config**

- 在 OpenColorIO @ `93878f9a` 全仓 grep `aces_1.2` / `aces_1\.2`：**无匹配**。
- 内置 config 只有 `aces-v1.3` 与 `aces-v2.0` 两代（见上表）。
- 结论：任务描述中提到的 "OCIO 内置的 `aces_1.2`/`aces_2.0` config" 里，**`aces_1.2` 未找到**；实际存在的是 `aces-v1.3` 与 `aces-v2.0`。

### A.1.3 事实：`colour-science/OpenColorIO-Config-ACES` 与 ASWF 仓库是两个不同的仓库

```
$ git ls-remote --heads https://github.com/colour-science/OpenColorIO-Config-ACES
532ad3d109ab139e79e1f8eebd623a8f7d52c595	refs/heads/aces-1.x-varnishing
cc41f1fbfd92483a5c4b2a91a5c0e5981d75a88b	refs/heads/feature/aces-1.x
...
$ cd /private/tmp/occaces_cs && git log -1 --format='%H%n%ci%n%s'
ebd5df1e747a58d018a78a9b2a08fec74f60dd98
2022-08-08 15:55:35 -0400
PR: Studio Config Generator (#59)
```

`colour-science/OpenColorIO-Config-ACES` 的默认分支 HEAD 停在 2022-08-08（`ebd5df1e…`），而 `AcademySoftwareFoundation/OpenColorIO-Config-ACES` 的 HEAD 是 2026-08-29（`b7b08c8c…`）。两个 URL 都真实可访问，但**不是同一个内容**；ASWF 的那个是现行仓库，colour-science 的那个是旧快照。

### A.1.4 事实：`ampas/aces-dev` 已重定向为 `ampas/aces-core`

```
$ cd /private/tmp/acesdev && git log -1 --format='%H%n%ci%n%s'
069b0bc3e1f6c62820f19fdae2fecec3f4fc0f80
2026-09-01 12:01:05 -0700
docs: update README (#163)
$ cd /private/tmp/acescore && git log -1 --format='%H%n%ci%n%s'
069b0bc3e1f6c62820f19fdae2fecec3f4fc0f80
2026-09-01 12:01:05 -0700
docs: update README (#163)
```

两仓库内容一致（顶层均为 `CHANGELOG.md lib LICENSE README.md`）。ACES 1.x 的规范/CTL 源码需通过 tag `v1.3`（`1256fee50ee35548c6eab8eca854ff3349008489`）取得。

---

## A.2 working space 是哪个？IDT 的目标空间是哪个？

### A.2.1 事实：OCIO config 的 roles 明确给出 interchange / scene_linear / color_timing

逐字（`src/OpenColorIO/builtinconfigs/configs/studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:13-24`，OpenColorIO @ `93878f9a`）：

```yaml
roles:
  aces_interchange: ACES2065-1
  cie_xyz_d65_interchange: CIE XYZ-D65 - Display-referred
  color_picking: sRGB Encoded Rec.709 (sRGB)
  color_timing: ACEScct
  compositing_log: ACEScct
  data: Raw
  matte_paint: ACEScct
  scene_linear: ACEScg
  texture_paint: sRGB Encoded Rec.709 (sRGB)
```

同一文件 ACES 1.3 版（`studio-config-v2.2.0_aces-v1.3_ocio-v2.4.ocio:13-24`）内容逐字相同（`aces_interchange: ACES2065-1` / `scene_linear: ACEScg` / `color_timing: ACEScct`）。

### A.2.2 事实：ACES2065-1 / ACEScg / ACEScc / ACEScct 的 ColorSpace 定义（含 `working-space` category）

逐字（`studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:443-508`）：

```yaml
colorspaces:
  - !<ColorSpace>
    name: ACES2065-1
    aliases: [aces2065_1, aces, ACES - ACES2065-1, lin_ap0, lin_ap0_scene]
    interop_id: lin_ap0_scene
    family: ACES
    equalitygroup: ""
    bitdepth: 32f
    description: The "Academy Color Encoding System" reference colorspace.
    isdata: false
    categories: [file-io, texture]
    encoding: scene-linear
    allocation: uniform

  - !<ColorSpace>
    name: ACEScc
    aliases: [ACES - ACEScc, acescc_ap1, ocio:acescc_ap1_scene]
    interop_id: ocio:acescc_ap1_scene
    family: ACES
    equalitygroup: ""
    bitdepth: 32f
    description: Convert ACEScc to ACES2065-1
    isdata: false
    categories: [file-io]
    encoding: log
    ...
    to_scene_reference: !<BuiltinTransform> {style: ACEScc_to_ACES2065-1}

  - !<ColorSpace>
    name: ACEScct
    ...
    description: Convert ACEScct to ACES2065-1
    isdata: false
    categories: [file-io, working-space]
    encoding: log
    ...
    to_scene_reference: !<BuiltinTransform> {style: ACEScct_to_ACES2065-1}

  - !<ColorSpace>
    name: ACEScg
    aliases: [ACES - ACEScg, lin_ap1, lin_ap1_scene]
    ...
    description: Convert ACEScg to ACES2065-1
    isdata: false
    categories: [file-io, working-space, texture]
    encoding: scene-linear
    ...
    to_scene_reference: !<BuiltinTransform> {style: ACEScg_to_ACES2065-1}
```

**事实**：这个 config 里，`ACEScct` 与 `ACEScg` 被打上 `working-space` category；`ACES2065-1` 是 `aces_interchange` role 与所有 `to_scene_reference` 的目标。

### A.2.3 事实：IDT 的目标空间是 ACES2065-1

ACES 官方文档（URL: <https://docs.acescentral.com/system-components/input-transforms/>，页面底部日期 `September 10, 2025`）逐字原文：

```
The Input Transform, sometimes called an Input Device Transform or IDT, converts non-color-rendered RGB image values from a given camera system or other image capture device to ACES RGB relative exposure values.
The responsibilities of an IDT include: 
linearization, 
white balancing,
clipping, 
color analysis, 
neutral chromaticity difference compensation, and 
encoding as ACES RGB relative exposure values
```

OCIO config 中每个 IDT 的落点也是 `ACES2065-1`，逐字（`studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:561-580`）：

```yaml
    name: ARRI LogC3 (EI800)
    aliases: [arri_logc3_ei800, logc3ei800_alexawide, Input - ARRI - V3 LogC (EI800) - Wide Gamut, ocio:arrilogc3_awg3_scene]
    ...
    description: Convert ARRI LogC3 (EI800) to ACES2065-1
    isdata: false
    categories: [file-io]
    encoding: log
    ...
    to_scene_reference: !<GroupTransform>
      name: ARRI LogC3 (EI800) to ACES2065-1
      children:
        - !<LogCameraTransform> {base: 10, log_side_slope: 0.247189638318671, log_side_offset: 0.385536998692443, lin_side_slope: 5.55555555555556, lin_side_offset: 0.0522722750251688, lin_side_break: 0.0105909904954696, direction: inverse}
        - !<MatrixTransform> {matrix: [0.680205505106279, 0.236136601606481, 0.0836578932872398, 0, 0.0854149797421404, 1.01747087860704, -0.102885858349182, 0, 0.00205652166929683, -0.0625625003847921, 1.06050597871549, 0, 0, 0, 0, 1]}
```

**结论（事实）**：ACES 的 IDT 目标空间 = `ACES2065-1`（AP0 线性、场景参考）。"working space" 在 OCIO 的 ACES config 里不是单一值：`ACES2065-1` 是 interchange（参考）空间，`ACEScg` 是 `scene_linear` role（渲染/CG 工作空间），`ACEScct` 是 `color_timing`/`compositing_log` role（调色工作空间），二者都带 `working-space` category。

---

## A.3 LMT / Look 在 ACES 管线中的位置

### A.3.1 事实（ACES 官方文档）：Look Transform 在 Output Transform **之前**

URL: <https://docs.acescentral.com/system-components/look-transforms/>（HTTP 200，页面日期 `September 10, 2025`）逐字原文：

```
The ACES Look Transform imparts an image-wide creative 'look' to the appearance of ACES images through an Output Transform and can offer extraordinary flexibility in ACES-based workflows. A Look Transform precedes the selected ACES Output Transform. 
```

```
"Look Transform" is the preferred nomenclature but are often referred to using the acronym, "LMT", which is derived from their original name, "Look Modification Transform". 
```

```
A Look Transform is defined as "an ACES-to-ACES transform that systematically changes the appearance of ACES-encoded data to be viewed through an Output Transform".
```

```
The LMT precedes the sequential application of an ACES Output Transform, and allows custom and systematic color changes to a set of clips or entire timeline to realize a chosen creative intent.
LMTs are always ACES-to-ACES transforms. In other words, ACES2065-1 data directly translated from camera-native image data via an Input Transform is manipulated by the LMT to output new ACES2065-1 data (designated in diagrams as ACES') that is then viewed through an ACES Output Transform.
```

同页 "Where Does It Go?" 小节逐字（含 flowchart 文本化）：

```
As part of the ACES viewing pipeline, the Look Transform takes ACES color-encoded values as inputs, and outputs modified ACES-encoded values that may then be immediately processed by an Output Transform (in this case, the Output Transform appropriate for the colorist's display).

flowchart LR
 n1["Original
 ACES"] --> n2["Look
 Transform"];
 n2 --> n3["Modified
 ACES"]
 n3 --> n4["Output
 Transform"];
 n4 --> n5["Display
 Code
 Values"]
 n5 --> n6("Display")
 ...
Figure 1. Placement of a Look Transform before an Output Transform
```

### A.3.2 事实（ACES LMT 规范页）：LMT 的输入/输出空间与完整管线顺序

URL: <https://docs.acescentral.com/system-components/look-transforms/specification/>（HTTP 200）逐字原文：

```
Specification¶
Inputs¶
The inputs to a Look Transform are ACES2065 RGB relative exposure values.
Outputs¶
The outputs of a Look Transform are ACES2065 RGB relative exposure values.
Working Space¶
The internal working space of a Look Transform is unrestricted. A Look Transform may transform its inputs internally into a non-ACES2065 space and perform color operations there, as long as it transforms the results of those back to ACES2065 before yielding them as outputs.
Syntax¶
Look Transforms are specified in Common LUT Format XML.
```

同页 "Nondestructive preview of displayed image appearance" 小节逐字给出**完整顺序（IDT → pre-grades → grade → LMT → RRT → ODT）**：

```
Look Transforms are one of several types of transform applied to either camera-specific files in proprietary or standard format that have been converted into ACES by an IDT, or to ACES files in OpenEXR containers. In the most general of cases, the input image data will be processed first by zero or more pre-grades, then by the grading transform, then by zero or more Look Transforms, then by the RRT, then by a selected ODT, and then displayed to the user. This most general case is shown in Figure 9.
```

同页 "Optimizations" 小节逐字（说明"把 Look 烘成 3D LUT"是规范允许的优化）：

```
When there are multiple Look Transforms making up a Look Transform, the hardware or software implementing the ACES viewing pipeline may optimize the application of the Look Transform by combining the Look Transforms into a single LUT; it may also combine this Look Transform LUT with the RRT and a selected ODT.
Composite transform sampling using a 3D LUT¶
Applications may also send a lattice of sample values through some set of adjacent transforms (anything from two or more adjacent Look Transforms in the Look Transform, to the full concatenation of Look Transform plus RRT and a selected ODT) to derive a single 3D LUT.
Artifact-free processing across the large range of ACES RGB relative exposure values requires shaper LUTs before and after the 3D LUT to minimize interpolation error.
```

同页 "Empirical Look Transforms" 小节逐字（说明"风格 LUT"的经验型 LMT 用什么形式表达）：

```
Empirical Look Transforms usually are derived by sampling the results of some other color reproduction process, such as normal or special film processing. Empirical Look Transforms are often provided as 3D LUTs that record a regular subsampling of the results of such processes.
A challenge arises when using ACES values to index the 3D LUT, as ACES values are radiometrically linear and have a very wide floating point range. The Common LUT Format provides for forward and inverse 'shaper LUT' operations that (when wrapped around an appropriately constructed 3D LUT) effectively solve this problem. Implementers should review the LUT1D section of the Common LUT Format specification.
```

同页 "Importing 'looks' from non-ACES-color-managed workflows" 小节逐字（直接回答"风格 LUT 通常接在哪一步"）：

```
Importing a 'look file' or LUT from a color system not defined using ACES can be quite difficult. Such look files contain transforms to be applied in color spaces other than ACES or the ACES working space, often presume the relationship between scene and encoded values is encoded with some (possibly proprietary) power function or log function, and likely are designed to supply a display device with code values directly rather than hand off the image to an ACES Output Transform.
For all of these reasons, it is typically better (and often much more efficient over the course of a production) to establish new Look Transforms within a workflow that is built around ACES-based color management rather than to try and mathematically translate a 'look file' or LUT intended for use with a workflow based on some other color management system.
```

### A.3.3 事实（OCIO config）：Look 用 `process_space: ACES2065-1` 定义，没有 `LookTransform` 条目

逐字（`src/OpenColorIO/builtinconfigs/configs/studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:87-96`，OpenColorIO @ `93878f9a`）：

```yaml
looks:
  - !<Look>
    name: ACES 1.3 Reference Gamut Compression
    process_space: ACES2065-1
    description: LMT (applied in ACES2065-1) to compress scene-referred values from common cameras into the AP1 gamut
    interchange:
      amf_transform_ids: |
        urn:ampas:aces:transformId:v2.0:InvLook.Academy.ReferenceGamutCompress.a2.v1
        urn:ampas:aces:transformId:v2.0:Look.Academy.ReferenceGamutCompress.a2.v1
    transform: !<BuiltinTransform> {style: ACES-LMT - ACES 1.3 Reference Gamut Compression}
```

ACES 1.3 config 版逐字（`studio-config-v2.2.0_aces-v1.3_ocio-v2.4.ocio:84-96`）：

```yaml
looks:
  - !<Look>
    name: ACES 1.3 Reference Gamut Compression
    process_space: ACES2065-1
    description: |
      LMT (applied in ACES2065-1) to compress scene-referred values from common cameras into the AP1 gamut

      ACEStransformID: urn:ampas:aces:transformId:v1.5:LMT.Academy.ReferenceGamutCompress.a1.v1.0

      AMF Components
      --------------
      ACEStransformID: urn:ampas:aces:transformId:v1.5:InvLMT.Academy.ReferenceGamutCompress.a1.v1.0
    transform: !<BuiltinTransform> {style: ACES-LMT - ACES 1.3 Reference Gamut Compression}
```

**事实**：在内置 ACES config 中 grep `LookTransform`（`grep -rn "LookTransform" src/OpenColorIO/builtinconfigs/configs/`）**无任何匹配**。config 用 `!<Look>` + `process_space` 表达 LMT，而**不是**用 `!<LookTransform>`。

### A.3.4 事实（OCIO 源码）：`LookTransform` 的 `src`/`dst`/`looks` 三个字段

逐字（`include/OpenColorIO/OpenColorTransforms.h:1755-1768`，OpenColorIO @ `93878f9a`）：

```cpp
    const char * getSrc() const;
    void setSrc(const char * src);

    const char * getDst() const;
    void setDst(const char * dst);

    const char * getLooks() const;
    /**
     * Specify looks to apply.
     * Looks is a potentially comma (or colon) delimited list of look names,
     * Where +/- prefixes are optionally allowed to denote forward/inverse
     * look specification. (And forward is assumed in the absence of either)
     */
    void setLooks(const char * looks);
```

`LookTransform` 的 `getSrc`/`getDst` 只是 color space 名字符串（`src/OpenColorIO/transforms/LookTransform.cpp:111-126`）：

```cpp
    return getImpl()->m_src.c_str();
...
    getImpl()->m_src = src ? src : "";
...
    return getImpl()->m_dst.c_str();
...
    getImpl()->m_dst = dst ? dst : "";
```

以及 `Look::processSpace` 的语义，逐字（`include/OpenColorIO/OpenColorIO.h:2390-2392`）：

```cpp
 * The processSpace defines the ColorSpace the image is required to be
 * in, for the math to apply correctly.
 */
```

### A.3.5 事实（ACES 1.x CTL 源码）：RRT 的输入/输出

逐字（`transforms/ctl/rrt/RRT.ctl:1-10`，aces-core tag `v1.3` = `1256fee5…`）：

```
// <ACEStransformID>urn:ampas:aces:transformId:v1.5:RRT.a1.0.3</ACEStransformID>
// <ACESuserName>ACES 1.0 - RRT</ACESuserName>

// 
// Reference Rendering Transform (RRT)
//
//   Input is ACES
//   Output is OCES
//
```

**回答任务 A.2 的"ACES 规范里 display pipeline 的顺序（IDT → LMT → RRT → ODT）"**：ACES 1.x 的文档原文（§A.3.2）逐字给出 `… then by zero or more Look Transforms, then by the RRT, then by a selected ODT …`，即 **IDT → (pre-grades → grade → LMT) → RRT → ODT**；ACES 2.0 把 RRT+ODT 统一为 **Output Transform**（见 §A.4），顺序为 **IDT → LMT → Output Transform**。

---

## A.4 ACES 2.0 与 1.x 的差异（官方文档逐字）

### A.4.1 事实：术语与结构差异

URL: <https://docs.acescentral.com/system-components/output-transforms/>（HTTP 200）逐字原文：

```
The Output Transform is a concatenation of two separate submodules, the "Rendering Transform" and the "Display Encoding Transform" (Figure 1). The Rendering Transform is informed by the luminance of peak white and the primary chromaticities and white point of the limiting gamut (i.e. the target for the rendering). 
Independent of the Rendering Transform is the Display Encoding Transform, which encodes the colorimetry produced by the Rendering Transform according to the characteristics (display primaries, calibrated white point, EOTF) of the display device on which the rendering will be viewed.
```

```
ACES 1 vs ACES 2 Terminology
ACES 1 defined a Reference Rendering Transform (RRT) and an Output Device Transform (ODT). In ACES 1.1 and beyond, the RRT and ODT were concatenated and designated an Output Transform (RRT+ODT). The combination of the two simplified some steps in the rendering such as the tone scale mapping.
ACES 2 maintains the simpler umbrella term of "Output Transform" as a the name for the overall transformation from scene-linear ACES data to an output-referred image specifically encoded for display on a specific device. 
```

同页逐字（ACES 1 与 ACES 2 渲染算法差异）：

```
The Rendering Transform in ACES 2 applies tone-mapping and adjusts colorfulness based on the luminance and boundaries defined by the choice of target rendering gamut. 
While ACES 1 applied tonemapping directly to AP1 RGB channels, the ACES 2 Rendering Transform translates ACES RGB into an intermediary "JMh" color correlate space derived using a simplified version of the Hellwig 2022 Color Appearance Model (CAM). In the JMh color space, J correlates to lightness, M is colorfulness, and h is hue.
```

### A.4.2 事实：ACES 2 的设计目标（tone scale 更缓、色域映射）

URL: <https://docs.acescentral.com/background/about-rendering/>（HTTP 200）逐字原文：

```
The ACES 2 rendering transforms are designed to:
Provide tone mapping with a slightly lower mid-slope contrast and gentler highlight rolloff than ACES v1 (See Figure 1)
Minimize hue shifts across exposure levels within regions of uniform hue
Include robust gamut mapping to eliminate harsh clipping and preserve perceptual continuity (See Figure 2)
Fully utilize output encoding ranges, where appropriate (reach extents of display gamut volume)
Maintain round-trip consistency (Output → Inverse ACES → Output)
Use a unified algorithmic structure across all display-targeted transforms
Offer simple parameterization for unsupported or custom display configurations
Deliver a predictable, high-quality default rendering
```

URL: <https://docs.acescentral.com/background/about-aces-2/>（HTTP 200）逐字原文：

```
The most significant change in ACES 2 is the introduction of a completely redesigned suite of rendering transforms. However, ACES 2 introduces a number of important improvements and additions, including:
New Output Transforms for rendering ACES images to displays, featuring:
A less aggressive tone scale with reduced mid-tone contrast and a gentler highlight rolloff
More intuitive parameter controls for supporting custom or uncommon display configurations
Robust gamut mapping for improved perceptual uniformity and reduced clipping artifacts
Optimized performance for inverse transforms
Additional transforms with preset parameters targeting common display calibration standards
Enhanced AMF (ACES Metadata File) Specification
Updated specification of ACES Transform IDs, plus a manifest of Transform IDs available at each ACES version tag since v1.0
Recommendations for using compression in ACES OpenEXRs
```

### A.4.3 事实：OCIO 中 ACES 2.0 的 view transform 用 `ACES-OUTPUT` BuiltinTransform

逐字（`studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:110-129`）：

```yaml
  - !<ViewTransform>
    name: ACES 2.0 - SDR 100 nits (Rec.709)
    description: Component of ACES 2 Output Transforms for 100 nit SDR Rec709
    interchange:
      amf_transform_ids: |
        urn:ampas:aces:transformId:v2.0:InvOutput.Academy.Rec709-D65_100nit_in_P3-D65_ST2084.a2.v1
        ...
    from_scene_reference: !<BuiltinTransform> {style: ACES-OUTPUT - ACES2065-1_to_CIE-XYZ-D65 - SDR-100nit-REC709_2.0}
```

对比 ACES 1.3 config 的 view transform 引用的是 ACES 1.x 的 ODT，逐字（`studio-config-v2.2.0_aces-v1.3_ocio-v2.4.ocio:102-110`）：

```yaml
  - !<ViewTransform>
    name: ACES 1.0 - SDR Video
    description: |
      Component of ACES Output Transforms for SDR D65 video

      ACEStransformID: urn:ampas:aces:transformId:v1.5:ODT.Academy.RGBmonitor_100nits_dim.a1.0.3
      ACEStransformID: urn:ampas:aces:transformId:v1.5:ODT.Academy.DisplayP3_dim.a1.0.0
      ACEStransformID: urn:ampas:aces:transformId:v1.5:ODT.Academy.Rec709_100nits_dim.a1.0.3
      ACEStransformID: urn:ampas:aces:transformId:v1.5:ODT.Academy.Rec2020_100nits_dim.a1.0.3
```

---

## A.5 结论：风格 LUT 在 ACES 体系里通常接在哪一步？

**事实依据（逐字，见 §A.3）**：

1. ACES 官方把经验型（empirical）look 明确定义为 **LMT** 的实现形式："`Empirical Look Transforms are often provided as 3D LUTs that record a regular subsampling of the results of such processes.`"（<https://docs.acescentral.com/system-components/look-transforms/specification/>）
2. LMT 的位置固定：`A Look Transform precedes the selected ACES Output Transform.` / `The LMT precedes the sequential application of an ACES Output Transform`（<https://docs.acescentral.com/system-components/look-transforms/>）
3. LMT 的输入/输出都必须是 ACES2065：`The inputs to a Look Transform are ACES2065 RGB relative exposure values.` / `The outputs of a Look Transform are ACES2065 RGB relative exposure values.`（specification 页）
4. OCIO 的 ACES config 里唯一的 Look 条目 `process_space: ACES2065-1`，描述逐字为 `LMT (applied in ACES2065-1) to compress scene-referred values from common cameras into the AP1 gamut`（`studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:87-98`）
5. 规范明确反对把"其他色彩管理体系的 look LUT（display-code 输出型）"直接搬进 ACES：`likely are designed to supply a display device with code values directly rather than hand off the image to an ACES Output Transform.`（specification 页）

**结论（事实 + 推断分列）**：

- **事实**：在 ACES 体系内，风格 LUT 的"正规位置"是 **LMT 位置**（ACES2065-1 → ACES2065-1），位于 Output Transform（ACES 1.x 的 RRT+ODT / ACES 2 的 Output Transform）**之前**。规范原文明确写出该位置与其输入输出色彩空间。
- **事实**：ACES 规范同时允许把 LMT 与 RRT/ODT **烘成单个 3D LUT**（"may optimize … by combining the Look Transforms into a single LUT; it may also combine this Look Transform LUT with the RRT and a selected ODT"），此时 LUT 已经横跨到 display code 域，不再是"纯 LMT 位置"。
- **推断（标注）**：把风格 LUT 接在 **ODT 之后（display-encoded 域）** 的做法，在 ACES 术语里**不是** LMT，而是"把 look file 从非 ACES 体系搬进来"的情形；规范对该做法给出的是明确的负面评价（见上面第 5 条）。本次取证**未找到**任何 ACES 官方文档把"风格 LUT"定义为"ODT 之后"的位置。
- **在 OCIO config 里 Look/LMT 的 src/dst 色彩空间**：config 用 `process_space: ACES2065-1`（逐字见上），即 **src = ACES2065-1、dst = ACES2065-1**；OCIO 的 `LookTransform` 对象则显式带 `getSrc()` / `getDst()` / `getLooks()` 三个字段（`include/OpenColorIO/OpenColorTransforms.h:1755-1768`），其语义由 `Look::processSpace` 注释界定：`The processSpace defines the ColorSpace the image is required to be in, for the math to apply correctly.`（`include/OpenColorIO/OpenColorIO.h:2390-2392`）。

---

## A.6 ACES 的 ODT 是否包含显示 OETF？

### A.6.1 事实（ACES 2.0）：Display Encoding 用 inverse EOTF

URL: <https://docs.acescentral.com/system-components/output-transforms/technical-details/display-encoding/>（HTTP 200）逐字原文：

```
Overview¶
The display encoding step prepares colorimetry from the rendering portion of the Output Transform so that it will produce the expected result after the signal is decoded by a display. 
Colorimetry is encoded relative to the configured color gamut (i.e. color primaries and white chromaticity) of the display and using the inverse EOTF.
Primaries and White Chromaticities¶
[Placeholder: Charts with various display primary sets]
Inverse EOTF¶
```

同一页的流程图中该步骤被标注为 `F["Display Encoding"]`，位于 `H["White Limiting"]` 之后、`G(Display RGB Output)` 之前。

### A.6.2 事实（ACES 1.x）：ODT CTL 源码显式做 BT.1886 编码

逐字（`transforms/ctl/odt/rec709/ODT.Academy.Rec709_100nits_dim.ctl:57` 与 `:113-117`，aces-core tag `v1.3` = `1256fee5…`）：

```c
const float DISPGAMMA = 2.4; 
...
    // Handle out-of-gamut values
    // Clip values < 0 or > 1 (i.e. projecting outside the display primaries)
    linearCV = clamp_f3( linearCV, 0., 1.);

    // Encode linear code values with transfer function
    float outputCV[3];
    outputCV[0] = bt1886_r( linearCV[0], DISPGAMMA, L_W, L_B);
    outputCV[1] = bt1886_r( linearCV[1], DISPGAMMA, L_W, L_B);
    outputCV[2] = bt1886_r( linearCV[2], DISPGAMMA, L_W, L_B);

    // Default output is full range, check if legalRange param was set to true
    if (legalRange == 1) {
        outputCV = fullRange_to_smpteRange_f3( outputCV);
    }
```

**结论（事实）**：**是**。ACES 1.x 的 ODT 与 ACES 2.0 的 Display Encoding Transform 都包含"把线性显示光编码成显示 code value"的步骤（ACES 2 文档称 `using the inverse EOTF`；ACES 1.3 的 Rec.709 ODT 源码调用 `bt1886_r(..., DISPGAMMA=2.4, ...)`，注释为 `Encode linear code values with transfer function`）。

**补充事实**：在 OCIO 的 ACES config 中，显示编码还额外由 display colorspace 表达（`Rec.1886 Rec.709 - Display` 等出现在 `displays:` 段，`studio-config-v4.0.0_aces-v2.0_ocio-v2.5.ocio:50-83`），而 ACES 2 的 view transform 自身输出到 `CIE-XYZ-D65`（`from_scene_reference: !<BuiltinTransform> {style: ACES-OUTPUT - ACES2065-1_to_CIE-XYZ-D65 - SDR-100nit-REC709_2.0}`，同文件 129 行），即**在该 OCIO config 的 ACES 2 路径里，显示 EOTF 由 display colorspace 施加，而不是由 view transform 施加**。

---
---

# B 部分 · libplacebo / mpv：tone mapping 与 3D LUT 的先后，以及 LUT 的 colorspace/trc 域字段

libplacebo @ `e2972fdd09adacd383656738d7d280f0cd84a761`（2026-09-18），mpv @ `c64675679925df40c3cbecb49bdfb9c93f918745`（2026-09-22）。

## B.1 任务中点名的文件是否存在（事实）

```
$ ls /private/tmp/lp/src/tone_mapping.c /private/tmp/lp/src/gamut_mapping.c /private/tmp/lp/src/3dlut.c
src/tone_mapping.c      （存在）
src/gamut_mapping.c     （存在）
ls: src/3dlut.c: No such file or directory
```

**事实**：libplacebo @ `e2972fd` **没有** `src/3dlut.c`、也没有 `src/lut.c`。`.cube` LUT 的解析与 shader 应用在 **`src/shaders/lut.c`**（820 行），gamut mapping 在 `src/gamut_mapping.c`，tone mapping 在 `src/tone_mapping.c`。公共头为 `src/include/libplacebo/shaders/lut.h`、`src/include/libplacebo/shaders/colorspace.h`、`src/include/libplacebo/tone_mapping.h`。

---

## B.2 渲染顺序：pass 划分与 tone mapping 的落点

### B.2.1 事实：`pl_render_image` 的 pass 序列

逐字（`src/renderer.c:3652-3658`，libplacebo @ `e2972fd`）：

```c
    pass_begin_frame(&pass);
    if (!pass_read_image(&pass))
        goto error;
    if (!pass_scale_main(&pass))
        goto error;
    pass_convert_colors(&pass);
    if (!pass_output_target(&pass))
        goto error;
```

四个阶段的职责（源码逐字注释）：

- `src/renderer.c:2303`（`pass_convert_colors` 的头注释）：
  ```c
  // Transforms image into the output color space (tone-mapping, ICC 3DLUT, etc)
  static void pass_convert_colors(struct pass_state *pass)
  ```
- `src/renderer.c:2045`（`pass_read_image` 中）：
  ```c
      // Apply LUT logic and colorspace conversion
  ```

### B.2.2 事实：tone mapping 的调用点在 `pass_convert_colors` 的 `need_conversion` 分支

逐字（`src/renderer.c:2396-2419`）：

```c
    if (need_conversion) {
        struct pl_color_space target_csp = target->color;
        if (target->icc)
            target_csp.transfer = PL_COLOR_TRC_LINEAR;

        if (pass->need_peak_fbo && !img_tex(pass, img))
            return;

        // generate HDR feature map if required
        pl_tex feature_map = get_feature_map(pass);
        sh = img_sh(pass, img); // `get_feature_map` dispatches previous shader

        // current -> target
        pl_shader_color_map_ex(sh, params->color_map_params, pl_color_map_args(
            .src           = image->color,
            .dst           = target_csp,
            .prelinearized = prelinearized,
            .state         = &rr->tone_map_state,
            .feature_map   = feature_map,
        ));

        if (target->icc)
            pl_icc_encode(sh, target->icc, &rr->icc_state[ICC_TARGET]);
    }
```

### B.2.3 事实：`pl_shader_color_map_ex` 内部 tone mapping 先于 gamut mapping

逐字（`src/shaders/colorspace.c:1904` 与 `2021-2023`）：

```c
    if (need_tone_map) {
        const struct pl_tone_map_function *fun = tone.function;
        sh_describef(sh, "%s tone map (%.0f -> %.0f)", fun->name,
                     pl_hdr_rescale(PL_HDR_PQ, PL_HDR_NITS, tone.input_max),
                     pl_hdr_rescale(PL_HDR_PQ, PL_HDR_NITS, tone.output_max));
```

```c
    if (need_gamut_map) {
        const struct pl_gamut_map_function *fun = gamut.function;
        sh_describef(sh, "gamut map (%s)", fun->name);
```

（`need_tone_map` 块在 1904 行，`need_gamut_map` 块在 2021 行；GLSL 生成顺序即执行顺序。）

---

## B.3 关键问题：3D LUT 在 tone mapping 之前还是之后？libplacebo 有几个 LUT 插槽？

### B.3.1 事实：`pl_render_params` 里的 LUT 字段与注释

逐字（`src/include/libplacebo/renderer.h:238-250`，libplacebo @ `e2972fd`）：

```c
    // Color mapping LUT. If present, this will be applied as part of the
    // image being rendered, in normalized RGB space.
    //
    // Note: In this context, PL_LUT_NATIVE means "gamma light" and
    // PL_LUT_NORMALIZED means "linear light". For HDR signals, normalized LUTs
    // are scaled so 1.0 corresponds to the `pl_color_transfer_nominal_peak`.
    //
    // Note: A PL_LUT_CONVERSION fully replaces the color adaptation from
    // `image` to `target`, including any tone-mapping (if necessary) and ICC
    // profiles. It has the same representation as PL_LUT_NATIVE, so in this
    // case the input and output are (respectively) non-linear light RGB.
    const struct pl_custom_lut *lut;
    enum pl_lut_type lut_type;
```

（注意：**字段名是 `lut`，不是 `lut3d`**；`lut3d` 在本版本中只出现在 gamut-mapping 的 3D LUT 尺寸字段里，见 §B.3.5。）

### B.3.2 事实：`pl_frame` 也带 LUT 字段（源帧与目标帧各一个）

逐字（`src/include/libplacebo/renderer.h:590-592`）：

```c
    // Optional LUT associated with this frame.
    const struct pl_custom_lut *lut;
    enum pl_lut_type lut_type;
```

### B.3.3 事实：`enum pl_lut_type` 的四种取值与语义

逐字（`src/include/libplacebo/renderer.h:83-99`）：

```c
enum pl_lut_type {
    PL_LUT_UNKNOWN = 0,
    PL_LUT_NATIVE,      // applied to raw image contents (after fixing bit depth)
    PL_LUT_NORMALIZED,  // applied to normalized (HDR) RGB values
    PL_LUT_CONVERSION,  // LUT fully replaces color conversion

    // Note: When using a PL_LUT_CONVERSION to replace the YUV->RGB conversion,
    // `pl_render_params.color_adjustment` is no longer applied. Similarly,
    // when using a PL_LUT_CONVERSION to replace the image->target color space
    // conversion, `pl_render_params.color_map_params` are ignored.
    //
    // Note: For LUTs attached to the output frame, PL_LUT_CONVERSION should
    // instead perform the inverse (RGB->native) conversion.
    //
    // Note: PL_LUT_UNKNOWN tries inferring the meaning of the LUT from the
    // LUT's tagged metadata, and otherwise falls back to PL_LUT_NATIVE.
};
```

### B.3.4 事实：主 LUT（`params->lut`）在 tone mapping **之前**应用；`PL_LUT_CONVERSION` 直接取代 tone mapping

逐字（`src/renderer.c:2345-2394`，位于 `pass_convert_colors` 中，且**在** 2396 行的 `if (need_conversion)` tone-mapping 块**之前**）：

```c
    if (params->lut) {
        struct pl_color_space lut_in = params->lut->color_in;
        struct pl_color_space lut_out = params->lut->color_out;
        switch (params->lut_type) {
        case PL_LUT_UNKNOWN:
        case PL_LUT_NATIVE:
            pl_color_space_merge(&lut_in, &image->color);
            pl_color_space_merge(&lut_out, &image->color);
            break;
        case PL_LUT_CONVERSION:
            pl_color_space_merge(&lut_in, &image->color);
            need_conversion = false; // conversion LUT the highest priority
            break;
        case PL_LUT_NORMALIZED:
            if (!prelinearized) {
                // PL_LUT_NORMALIZED wants linear input data
                pl_shader_linearize(sh, &img->color);
                img->color.transfer = PL_COLOR_TRC_LINEAR;
                prelinearized = true;
            }
            pl_color_space_merge(&lut_in, &img->color);
            pl_color_space_merge(&lut_out, &img->color);
            break;
        }

        pl_shader_color_map_ex(sh, params->color_map_params, pl_color_map_args(
            .src = image->color,
            .dst = lut_in,
            .prelinearized = prelinearized,
        ));

        if (params->lut_type == PL_LUT_NORMALIZED) {
            GLSLF("color.rgb *= vec3(1.0/"$"); \n",
                  SH_FLOAT(pl_color_transfer_nominal_peak(lut_in.transfer)));
        }

        pl_shader_custom_lut(sh, params->lut, &rr->lut_state[LUT_PARAMS]);

        if (params->lut_type == PL_LUT_NORMALIZED) {
            GLSLF("color.rgb *= vec3("$"); \n",
                  SH_FLOAT(pl_color_transfer_nominal_peak(lut_out.transfer)));
        }

        if (params->lut_type != PL_LUT_CONVERSION) {
            pl_shader_color_map_ex(sh, params->color_map_params, pl_color_map_args(
                .src = lut_out,
                .dst = img->color,
            ));
        }
    }
```

注意上面前后两次 `pl_shader_color_map_ex` **都没有传 `.state`**，因此它们不做 tone mapping（证据见 §B.5.2）。

### B.3.5 事实：源帧 LUT（`image->lut`）在 `pass_read_image` 中、缩放之前应用

逐字（`src/renderer.c:2045-2093`）：

```c
    // Apply LUT logic and colorspace conversion
    enum pl_lut_type lut_type = guess_frame_lut_type(image, false);
    sh = img_sh(pass, &pass->img);
    bool needs_conversion = true;

    if (lut_type == PL_LUT_NATIVE || lut_type == PL_LUT_CONVERSION) {
        // Fix bit depth normalization before applying LUT
        float scale = pl_color_repr_normalize(&pass->img.repr);
        GLSL("color *= vec4("$"); \n", SH_FLOAT(scale));
        pl_shader_custom_lut(sh, image->lut, &rr->lut_state[LUT_IMAGE]);

        if (lut_type == PL_LUT_CONVERSION) {
            pass->img.repr.sys = PL_COLOR_SYSTEM_RGB;
            pass->img.repr.levels = PL_COLOR_LEVELS_FULL;
            needs_conversion = false;
        }
    }

    if (needs_conversion) {
        ...
        pl_shader_decode_color_ex(sh, pl_color_decode_args(
            .repr              = &pass->img.repr,
            .color_adjustment  = params->color_adjustment,
            .enhancement_layer = el_sh,
        ));
        pl_dispatch_abort(rr->dp, &el_sh);
    }

    if (lut_type == PL_LUT_NORMALIZED)
        pl_shader_custom_lut(sh, image->lut, &rr->lut_state[LUT_IMAGE]);
```

### B.3.6 事实：目标帧 LUT（`target->lut`）在 tone mapping **之后**（两条路径）

路径 1 —— `pass_convert_colors` 末尾，逐字（`src/renderer.c:2421-2425`）：

```c
    enum pl_lut_type lut_type = guess_frame_lut_type(target, true);
    if (lut_type == PL_LUT_NORMALIZED || lut_type == PL_LUT_CONVERSION)
        pl_shader_custom_lut(sh, target->lut, &rr->lut_state[LUT_TARGET]);

    img->color = target->color;
```

路径 2 —— `pass_output_target` 中（输出编码之后），逐字（`src/renderer.c:2931-2940`）：

```c
    enum pl_lut_type lut_type = guess_frame_lut_type(target, true);
    if (lut_type != PL_LUT_CONVERSION) {
        pl_shader_encode_color(sh, &repr);
        if (repr.sys == PL_COLOR_SYSTEM_XYZ) {
            img->color.transfer = PL_COLOR_TRC_ST428;
            pl_shader_delinearize(sh, &img->color);
        }
    }
    if (lut_type == PL_LUT_NATIVE)
        pl_shader_custom_lut(sh, target->lut, &rr->lut_state[LUT_TARGET]);
```

### B.3.7 结论（事实）：libplacebo 有 **三个** LUT 插槽，且相对于 tone mapping 的位置明确

| 插槽 | 字段 | 应用位置（源码） | 相对 tone mapping |
|---|---|---|---|
| 源帧 LUT | `pl_frame.lut`（image） | `pass_read_image`，`src/renderer.c:2050-2093` | 远早于 tone mapping（在 `pass_scale_main` 与 `pass_convert_colors` 之前） |
| 主 LUT | `pl_render_params.lut` | `pass_convert_colors`，`src/renderer.c:2345-2394` | **在 tone mapping 之前**；若 `lut_type == PL_LUT_CONVERSION` 则**取代** tone mapping（`need_conversion = false`，见 2356 行；另见 `src/renderer.c:1238-1239`） |
| 目标帧 LUT | `pl_frame.lut`（target） | `pass_convert_colors:2421-2423`（NORMALIZED / CONVERSION）与 `pass_output_target:2931-2940`（NATIVE） | **在 tone mapping 之后**（NATIVE 更在目标编码 `pl_shader_encode_color` 之后） |

`src/renderer.c:1238-1239` 逐字（佐证 CONVERSION 取代 tone mapping）：

```c
    if (params->lut && params->lut_type == PL_LUT_CONVERSION)
        goto cleanup; // LUT handles tone mapping
```

**回答"libplacebo 是否有多个 3D LUT 插槽（例如 pre- vs post- tone mapping）"**：**是**。存在 pre-tone-mapping 的主 LUT（`pl_render_params.lut`）与 post-tone-mapping 的目标帧 LUT（`pl_frame.lut` on target），另有更早的源帧 LUT（`pl_frame.lut` on image）。注释级证据是 `renderer.h:238-250`（"A PL_LUT_CONVERSION fully replaces the color adaptation from `image` to `target`, including any tone-mapping"）与 `renderer.h:83-99`；代码级证据是 §B.3.4/§B.3.6 的行号。

**补充事实（另一类 3D LUT）**：libplacebo 的 tone mapping / gamut mapping 内部还会生成自己的 LUT。逐字（`src/include/libplacebo/shaders/colorspace.h:249-292`）：

```c
struct pl_color_map_params {
    // --- Gamut mapping options

    // Gamut mapping function to use to handle out-of-gamut colors, including
    // colors which are out-of-gamut as a consequence of tone mapping.
    const struct pl_gamut_map_function *gamut_mapping;

    // Gamut mapping constants, for expert tuning. Leave as default otherwise.
    struct pl_gamut_map_constants gamut_constants;

    // Gamut mapping 3DLUT size, for channels ICh. Defaults to {48, 32, 256}
    int lut3d_size[3];

    // Use higher quality, but slower, tricubic interpolation for gamut mapping
    // 3DLUTs. May substantially improve the 3DLUT gamut mapping accuracy, in
    // particular at smaller 3DLUT sizes. Shouldn't have much effect at the
    // default size.
    bool lut3d_tricubic;
    ...
    // --- Tone mapping options

    // Tone mapping function to use to handle out-of-range colors.
    const struct pl_tone_map_function *tone_mapping_function;
    ...
    // Tone mapping LUT size. Defaults to 256.
    int lut_size;
```

以及默认值逐字（同文件 336-345）：

```c
#define PL_COLOR_MAP_DEFAULTS                                   \
    .gamut_mapping          = &pl_gamut_map_perceptual,         \
    .tone_mapping_function  = &pl_tone_map_spline,              \
    .gamut_constants        = { PL_GAMUT_MAP_CONSTANTS },       \
    .tone_constants         = { PL_TONE_MAP_CONSTANTS },        \
    .metadata               = PL_HDR_METADATA_ANY,              \
    .lut3d_size             = {48, 32, 256},                    \
    .lut_size               = 256,                              \
```

`pl_tone_map_params` 逐字（`src/include/libplacebo/tone_mapping.h:129-160`）：

```c
struct pl_tone_map_params {
    // If `function` is NULL, defaults to `pl_tone_map_clip`.
    const struct pl_tone_map_function *function;

    // Common constants, should be initialized to PL_TONE_MAP_CONSTANTS if
    // not intending to override them further.
    struct pl_tone_map_constants constants;

    // The desired input/output scaling of the tone map. If this differs from
    // `function->scaling`, any required conversion will be performed.
    //
    // Note that to maximize LUT efficiency, it's *highly* recommended to use
    // either PL_HDR_PQ or PL_HDR_SQRT as the input scaling, except when
    // using `pl_tone_map_sample`.
    enum pl_hdr_scaling input_scaling;
    enum pl_hdr_scaling output_scaling;

    // The size of the resulting LUT. (For `pl_tone_map_generate` only)
    size_t lut_size;

    // The characteristics of the input, in `input_scaling` units.
    float input_min;
    float input_max;
    float input_avg; // or 0 if unknown

    // The desired characteristics of the output, in `output_scaling` units.
    float output_min;
    float output_max;
    ...
```

---

## B.4 `.cube` 的 `DOMAIN_MIN` / `DOMAIN_MAX`：libplacebo 是否解析、如何被使用

### B.4.1 事实：解析在 `src/shaders/lut.c`，且解析后**校验**并用于**表项数据归一化**

逐字（`src/shaders/lut.c:35-43`，函数入口与默认域）：

```c
struct pl_custom_lut *pl_lut_parse_cube(pl_log log, const char *cstr, size_t cstr_len)
{
    struct pl_custom_lut *lut = pl_zalloc_ptr(NULL, lut);
    pl_str str = (pl_str) { (uint8_t *) cstr, cstr_len };
    lut->signature = pl_str_hash(str);
    int entries = 0;

    float min[3] = { 0.0, 0.0, 0.0 };
    float max[3] = { 1.0, 1.0, 1.0 };
```

逐字（`src/shaders/lut.c:91-113`，解析）：

```c
        if (pl_str_eatstart0(&line, "DOMAIN_MIN")) {
            line = pl_str_strip(line);
            if (!pl_str_parse_float(pl_str_split_char(line, ' ', &line), &min[0]) ||
                !pl_str_parse_float(pl_str_split_char(line, ' ', &line), &min[1]) ||
                !pl_str_parse_float(line, &min[2]))
            {
                pl_err(log, "Failed parsing domain: '%.*s'", PL_STR_FMT(line));
                goto error;
            }
            continue;
        }

        if (pl_str_eatstart0(&line, "DOMAIN_MAX")) {
            line = pl_str_strip(line);
            if (!pl_str_parse_float(pl_str_split_char(line, ' ', &line), &max[0]) ||
                !pl_str_parse_float(pl_str_split_char(line, ' ', &line), &max[1]) ||
                !pl_str_parse_float(line, &max[2]))
            {
                pl_err(log, "Failed parsing domain: '%.*s'", PL_STR_FMT(line));
                goto error;
            }
            continue;
        }
```

逐字（`src/shaders/lut.c:129-134`，仅做合法性校验）：

```c
    for (int i = 0; i < 3; i++) {
        if (max[i] - min[i] < 1e-6) {
            pl_err(log, "Invalid domain range: [%f, %f]", min[i], max[i]);
            goto error;
        }
    }
```

逐字（`src/shaders/lut.c:162-169`，**domain 被用来 rescale LUT 表项数据**）：

```c
            float num;
            if (!pl_str_parse_float(entry, &num)) {
                pl_err(log, "Failed parsing float value '%.*s'", PL_STR_FMT(entry));
                goto error;
            }

            // Rescale to range 0.0 - 1.0
            *data++ = (num - min[c]) / (max[c] - min[c]);
```

### B.4.2 事实：shader 端把 LUT 当 [0,1] 归一化输入采样（不记录、也不反作用输入域）

逐字（`src/shaders/lut.c:250-272`，GLSL 生成）：

```c
    GLSL("// pl_shader_custom_lut \n");
    ...
    switch (dims) {
    case 1:
        sh_describe(sh, "custom 1DLUT");
        GLSL("color.rgb = vec3("$"(color.r).r,  \n"
             "                 "$"(color.g).g,  \n"
             "                 "$"(color.b).b); \n",
             fun, fun, fun);
        break;
    case 3:
        sh_describe(sh, "custom 3DLUT");
        GLSL("color.rgb = "$"(color.rgb).rgb; \n", fun);
        break;
    }
```

逐字（`src/shaders/lut.c:282-295`，纹理坐标缩放宏，把 [0,1] 映射到 LUT 网格）：

```c
// Defines a LUT position helper macro. This translates from an absolute texel
// scale (either in texels, or normalized to [0,1]) to the texture coordinate
// scale for the corresponding sample in a texture of dimension `lut_size`.
static ident_t texel_scale(pl_shader sh, int lut_size, bool normalized)
{
    const float base = 0.5f / lut_size;
    const float end = 1.0f - 0.5f / lut_size;
    const float scale = (end - base) / (normalized ? 1.0f : (lut_size - 1));
```

### B.4.3 结论（事实）

- libplacebo **解析** `DOMAIN_MIN` / `DOMAIN_MAX`（`src/shaders/lut.c:91-113`）。
- 它把 domain 用于**把 LUT 表项数据 rescale 到 [0,1]**（`src/shaders/lut.c:168-169`），并只对 domain 做非零宽度校验（129-134）。
- 它**没有**把 domain 保存到 `pl_custom_lut` 的任何字段（结构体见 §B.5.1，只有 `size`/`data`/`shaper_*`/`repr_*`/`color_*`，**无 domain 字段**），也**没有**用 domain 去映射输入像素：shader 只是 `color.rgb = LUT(color.rgb)`（`src/shaders/lut.c:270`），采样坐标由 `texel_scale` 按 [0,1] 归一化域生成。
- 因此：在 libplacebo 中，`.cube` 的 domain 是**表项数值的解释范围**，不是"输入域标注"。

---

## B.5 LUT 的 colorspace / trc 域字段如何生效

### B.5.1 事实：`pl_custom_lut` 携带 `repr_in/out` 与 `color_in/out`，但 `pl_shader_custom_lut` **忽略**它们

逐字（`src/include/libplacebo/shaders/lut.h:32-57`）：

```c
struct pl_custom_lut {
    // Some unique signature identifying this LUT, needed to detect state
    // changes (for cache invalidation). This should ideally be a hash of the
    // file contents. (Which is what `pl_lut_parse_*` will set it to.)
    uint64_t signature;

    // Size of each dimension, in the order R, G, B. For 1D LUTs, only the R
    // dimension should be specified (the others left as 0).
    int size[3];

    // Raw LUT data itself, in properly scaled floating point format. For 3D
    // LUTs, the innermost dimension is the first dimension (R), and the
    // outermost dimension is the last dimension (B). Individual color samples
    // are in the order R, G, B.
    const float *data;

    // Extra input/output shaper matrices. Ignored if equal to {0}. This is
    // mostly useful for 1D LUTs, since 3D LUTs can bake the shaper matrix into
    // the LUT itself - but it can still help optimize LUT precision.
    pl_matrix3x3 shaper_in, shaper_out;

    // Nominal metadata for the input/output of a LUT. Left as {0} if unknown.
    // Note: This is purely informative, `pl_shader_custom_lut` ignores it.
    struct pl_color_repr repr_in, repr_out;
    struct pl_color_space color_in, color_out;
};
```

逐字（`src/include/libplacebo/shaders/lut.h:65-74`，调用方责任）：

```c
// Apply a `pl_custom_lut`. The user is responsible for ensuring colors going
// into the LUT are in the expected format as informed by the LUT metadata.
//
// `lut_state` must be a pointer to a NULL-initialized shader state object that
// will be used to encapsulate any required GPU state.
//
// Note: `lut` does not have to be allocated by `pl_lut_parse_*`. It can be a
// struct filled out by the user.
PL_API void pl_shader_custom_lut(pl_shader sh, const struct pl_custom_lut *lut,
                                 pl_shader_obj *lut_state);
```

**事实**：`pl_lut_parse_cube` 只填 `signature` / `size` / `data`，**从不填** `repr_in/repr_out/color_in/color_out`（见 `src/shaders/lut.c:35-181` 全文，无任何对这四个字段的赋值）。

### B.5.2 事实：`color_in` / `color_out` 的 transfer 会驱动 LUT 前后的色彩空间转换（含 gamma 编解码），但**只在有 tone-mapping state 时才会 tone map**

逐字（`src/include/libplacebo/shaders/colorspace.h:358-378`）：

```c
struct pl_color_map_args {
    // Input/output color space for the mapping.
    struct pl_color_space src;
    struct pl_color_space dst;

    // If true, the logic will assume the input has already been linearized by
    // the caller (e.g. as part of a previous linear light scaling operation).
    bool prelinearized;

    // Object to be used to store generated LUTs. Note that this is the same
    // state object used by `pl_shader_detect_peak`, and if that function has
    // been called on `state` prior to `pl_shader_color_map`, the detected
    // values will be used to guide the tone mapping algorithm. If this is not
    // provided, tone/gamut mapping are disabled.
    pl_shader_obj *state;

    // Low-resolution intensity feature map, as generated by
    // `pl_shader_extract_features`. Optional. No effect if
    // `params->contrast_recovery` is disabled.
    pl_tex feature_map;
};
```

逐字（`src/shaders/colorspace.c:1694-1707`，无 state 时的行为）：

```c
    struct pl_color_space src = args->src, dst = args->dst;
    struct sh_color_map_obj *obj = NULL;
    if (args->state) {
        pl_get_detected_hdr_metadata(*args->state, &src.hdr);
        obj = SH_OBJ(sh, args->state, PL_SHADER_OBJ_COLOR_MAP, struct sh_color_map_obj,
                     sh_color_map_uninit);
        if (!obj)
            return;
    }

    pl_color_space_infer_map(&src, &dst);
    if (pl_color_space_equal(&src, &dst)) {
        if (args->prelinearized)
            pl_shader_delinearize(sh, &dst);
        return;
    }
```

逐字（`src/shaders/colorspace.c:1831-1838`，无 state 时强制降级）：

```c
    bool can_fast = !params->force_tone_mapping_lut;
    if (!args->state) {
        // No state object provided, forcibly disable advanced methods
        can_fast = true;
        if (tone.function != &pl_tone_map_clip)
            tone.function = &pl_tone_map_linear;
        if (gamut.function != &pl_gamut_map_clip)
            gamut.function = &pl_gamut_map_saturation;
    }
```

逐字（`src/shaders/colorspace.c:1850-1851`，线性化输入）：

```c
    if (!args->prelinearized)
        pl_shader_linearize(sh, &src);
```

**事实链**：`pass_convert_colors` 中 LUT 前后的两次 `pl_shader_color_map_ex`（`src/renderer.c:2370-2374` 与 `2388-2393`）**不传 `.state`**，所以：
- 若 `params->lut->color_in.transfer`（经 `pl_color_space_merge` 保留 LUT 自己的 trc）与图像 trc 不同，`pl_shader_color_map_ex` 会执行 `pl_shader_linearize(sh, &src)` / `pl_shader_delinearize`，即**在 LUT 前后插入 gamma/传输函数编解码**；
- 但**不会**执行真正的 tone mapping（tone function 被强制为 `pl_tone_map_linear`，且 `need_tone_map` 通常为 noop）。

**因此"`trc` 字段是否会导致 libplacebo 在 LUT 前后做 gamma 编解码（即把 LUT 当作 display-encoded 输入）"的答案是**：

- **在 libplacebo API 层面：会**（只要调用方把 `pl_custom_lut.color_in/color_out` 填成非零的 `pl_color_space`）。逐字证据：`src/renderer.c:2346-2392` 使用 `params->lut->color_in/color_out` 构造 src/dst 并调用 `pl_shader_color_map_ex`，后者在 src≠dst 时做线性化/编码转换（`src/shaders/colorspace.c:1694-1707`、`1850-1851`）。
- **在 mpv 的实际路径上：不会**，因为 mpv 从不填充这些字段。见 §B.5.3。

### B.5.3 事实：mpv 从不设置 LUT 的 color_in/color_out；`auto` 因此恒退化为 `native`

mpv 加载 LUT 的唯一入口，逐字（`video/out/vo_gpu_next.c:2717-2745`，mpv @ `c6467567`）：

```c
static void update_lut(struct priv *p, struct user_lut *lut)
{
    if (!lut->opt || !lut->opt[0]) {
        pl_lut_free(&lut->lut);
        TA_FREEP(&lut->path);
        return;
    }

    if (lut->path && strcmp(lut->path, lut->opt) == 0)
        return; // no change

    // Update cached path
    pl_lut_free(&lut->lut);
    talloc_replace(p, lut->path, lut->opt);

    // Load LUT file
    char *fname = mp_get_user_path(NULL, p->global, lut->path);
    MP_VERBOSE(p, "Loading custom LUT '%s'\n", fname);
    const int lut_max_size = 1536 << 20; // 1.5 GiB, matches lut cache limit
    struct bstr lutdata = stream_read_file(fname, NULL, p->global, lut_max_size);
    if (!lutdata.len) {
        MP_ERR(p, "Failed to read LUT data from %s, make sure it's a valid file "
                  "and smaller or equal to %d bytes\n", fname, lut_max_size);
    } else {
        lut->lut = pl_lut_parse_cube(p->pllog, lutdata.start, lutdata.len);
    }
    talloc_free(fname);
    talloc_free(lutdata.start);
}
```

（全仓 grep `color_in` / `color_out` 在 `video/out/` 下**无匹配**。）

mpv 的三个 LUT 选项与插槽映射，逐字（`video/out/vo_gpu_next.c:202-208` 与 `226-234`）：

```c
const struct m_opt_choice_alternatives lut_types[] = {
    {"auto",        PL_LUT_UNKNOWN},
    {"native",      PL_LUT_NATIVE},
    {"normalized",  PL_LUT_NORMALIZED},
    {"conversion",  PL_LUT_CONVERSION},
    {0}
};
```

```c
        {"lut", OPT_STRING(lut.opt), .flags = M_OPT_FILE},
        {"lut-type", OPT_CHOICE_C(lut.type, lut_types)},
        {"image-lut", OPT_STRING(image_lut.opt), .flags = M_OPT_FILE},
        {"image-lut-type", OPT_CHOICE_C(image_lut.type, lut_types)},
        {"target-lut", OPT_STRING(target_lut.opt), .flags = M_OPT_FILE},
        ...
        // No `target-lut-type` because we don't support non-RGB targets
```

映射到 libplacebo 三插槽，逐字（`video/out/vo_gpu_next.c:1106-1108`、`1165-1167`、`1217-1219`）：

```c
    update_lut(p, &p->next_opts->image_lut);
    frame->lut = p->next_opts->image_lut.lut;
    frame->lut_type = p->next_opts->image_lut.type;
```

```c
    update_lut(p, &p->next_opts->lut);
    pars->params.lut = p->next_opts->lut.lut;
    pars->params.lut_type = p->next_opts->lut.type;
```

```c
    update_lut(p, &p->next_opts->target_lut);
    target->lut = p->next_opts->target_lut.lut;
    target->lut_type = p->next_opts->target_lut.type;
```

`auto` 的推断逻辑，逐字（`src/renderer.c:1461-1482`，libplacebo @ `e2972fd`）：

```c
static enum pl_lut_type guess_frame_lut_type(const struct pl_frame *frame,
                                             bool reversed)
{
    if (!frame->lut)
        return PL_LUT_UNKNOWN;
    if (frame->lut_type)
        return frame->lut_type;

    enum pl_color_system sys_in = frame->lut->repr_in.sys;
    enum pl_color_system sys_out = frame->lut->repr_out.sys;
    if (reversed)
        PL_SWAP(sys_in, sys_out);

    if (sys_in == PL_COLOR_SYSTEM_RGB && sys_out == sys_in)
        return PL_LUT_NORMALIZED;

    if (sys_in == frame->repr.sys && sys_out == PL_COLOR_SYSTEM_RGB)
        return PL_LUT_CONVERSION;

    // Unknown, just fall back to the default
    return PL_LUT_NATIVE;
}
```

`PL_COLOR_SYSTEM_UNKNOWN` 的值，逐字（`src/include/libplacebo/colorspace.h:30-31`）：

```c
enum pl_color_system {
    PL_COLOR_SYSTEM_UNKNOWN = 0,
```

**事实**：mpv 只调用 `pl_lut_parse_cube`，而 `pl_lut_parse_cube` 不写 `repr_in/repr_out`，故 `sys_in = sys_out = PL_COLOR_SYSTEM_UNKNOWN = 0`；`frame->lut_type` 在 `auto` 时也是 `PL_LUT_UNKNOWN = 0`（falsy）→ `guess_frame_lut_type` 走到最后一行返回 **`PL_LUT_NATIVE`**。

**推断（标注）**：mpv 的 `--lut` 走 `PL_LUT_NATIVE` 分支（`src/renderer.c:2350-2353`），`pl_color_space_merge(&lut_in, &image->color)` 让 `lut_in == lut_out == image->color`，于是 LUT 前后两次 `pl_shader_color_map_ex` 的 src/dst 相等，直接 `return`（`src/shaders/colorspace.c:1705-1708`）——**没有任何 gamma 编解码被插入**。所以"trc 域字段导致 LUT 前后做 gamma 编解码"这件事在 mpv 下不会发生，因为该字段根本没人填。

### B.5.4 事实：mpv 手册对三个 LUT 选项的原文（含各自套在哪个域）

URL: <https://mpv.io/manual/master/>（HTTP 200）；本机源码同文：`DOCS/man/options.rst`（mpv @ `c6467567`）。

`--image-lut` / `--image-lut-type`，逐字（`DOCS/man/options.rst:7323-7343`）：

```
``--image-lut=<file>``
    Specifies a custom LUT file (in Adobe .cube format) to apply to the colors
    during image decoding. The exact interpretation of the LUT depends on
    the value of ``--image-lut-type``. (Only for ``--vo=gpu-next``)

``--image-lut-type=<value>``
    Controls the interpretation of color values fed to and from the LUT
    specified as ``--image-lut``. Valid values are:

    auto
        Chooses the interpretation of the LUT automatically from tagged
        metadata, and otherwise falls back to ``native``. (Default)
    native
        Applied to the raw image contents in its native colorspace, before
        decoding to RGB. For example, for a HDR10 image, this would be fed
        PQ-encoded YCbCr values in the range 0.0 - 1.0.
    normalized
        Applied to the normalized RGB image contents, after decoding from
        its native color encoding, but before linearization.
    conversion
        Fully replaces the color decoding. A LUT of this type should ingest the
        image's native colorspace and output normalized non-linear RGB.
```

`--lut` / `--lut-type`，逐字（`DOCS/man/options.rst:7960-7982`）：

```
``--lut=<file>``
    Specifies a custom LUT (in Adobe .cube format) to apply to the colors
    as part of color conversion. The exact interpretation depends on the value
    of ``--lut-type``. (Only for ``--vo=gpu-next``)

``--lut-type=<value>``
    Controls the interpretation of color values fed to and from the LUT
    specified as ``--lut``. Valid values are:

    auto
        Chooses the interpretation of the LUT automatically from tagged
        metadata, and otherwise falls back to ``native``. (Default)
    native
        Applied to raw image contents in its native RGB colorspace (non-linear
        light), before conversion to the output color space.
    normalized
        Applied to the normalized RGB image contents, in linear light, before
        conversion to the output color space.
    conversion
        Fully replaces the conversion from the image color space to the output
        color space. If such a LUT is present, it has the highest priority, and
        overrides any ICC profiles, as well as options related to tone mapping
        and output colorimetry (``--target-prim``, ``--target-trc`` etc.).
```

`--target-lut`，逐字（`DOCS/man/options.rst:7612-7617`）：

```
``--target-lut=<file>``
    Specifies a custom LUT file (in Adobe .cube format) to apply to the colors
    before display on-screen. This LUT is fed values in normalized RGB, after
    encoding into the target colorspace, so after the application of
    ``--target-trc``. (Only for ``--vo=gpu-next``)
```

`--target-colorspace-hint`，逐字（`DOCS/man/options.rst:7346-7360`）：

```
``--target-colorspace-hint=<auto|yes|no>``
    When enabled, output colorspace metadata will be set on the swapchain
    depending on the GPU context and platform this may affect compositor/display.
    This can be used for "HDR passthrough" and to set the output colorspace
    for SDR content. In ``auto`` mode, the target colorspace is only set if the
    current display parameters are known. Currently, this is supported on
    Wayland, D3D11 and winvk contexts. The ``yes`` option will always try to set
    the colorspace, you may need to adjust the ``--target-*`` options to match
    your display capabilities.
    Requires a supporting driver and ``--vo=gpu-next``. (Default: ``auto``)

    .. note::
        Auto detected target colorspace metadata is not guaranteed to be always
        best choice. It depends on your compositor, driver, and display
        capabilities. However in most cases ``auto`` mode should work fine.
```

`--icc-*` 系列选项（ICC 路径，与 `.cube` LUT 是**两条独立机制**）：逐字见下。

`--icc-profile` / `--icc-3dlut-size` / `--icc-force-contrast`，逐字（`DOCS/man/options.rst:7894-7897`、`7939-7955`）：

```
``--icc-profile=<file>``
    Load an ICC profile and use it to transform video RGB to screen output.
    Needs LittleCMS 2 support compiled in. This option overrides the
    ``--target-prim``, ``--target-trc`` and ``--icc-profile-auto`` options.
```

```
``--icc-3dlut-size=<auto|RxGxB>``
    Size of the 3D LUT generated from the ICC profile in each dimension. The
    default of ``auto`` means to pick the size automatically based on the
    profile characteristics. Sizes may range from 2 to 512.

    NOTE: Setting this option to anything other than ``auto`` is **strongly**
    discouraged, except for testing.

``--icc-force-contrast=<no|0-1000000|inf>``
    Override the target device's detected contrast ratio by a specific value.
    This is detected automatically from the profile if possible, but for some
    profiles it might be missing, causing the contrast to be assumed as
    infinite. As a result, video may appear darker than intended. If this is
    the case, setting this option might help. This only affects BT.1886
    content. The default of ``no`` means to use the profile values. The special
    value ``inf`` causes the BT.1886 curve to be treated as a pure power gamma
    2.4 function.

``--icc-use-luma``
    Use ICC profile luminance value. (Only for ``--vo=gpu-next``)
```

**结论（事实）**：mpv 用三个选项把 LUT 明确挂到三个不同域：
- `--image-lut` → 源帧 LUT（native / 解码前，或 normalized / 解码后线性化前）
- `--lut` → 主 LUT（native = 原生非线性 RGB；normalized = 线性光；conversion = 取代整个转换，**优先于 tone mapping**）
- `--target-lut` → 目标帧 LUT（normalized RGB，**在 target colorspace 编码之后、即 `--target-trc` 之后**）

### B.5.5 事实：mpv **没有** `--vf=lut3d`

- `ls video/filter/`（mpv @ `c6467567`）不含任何 `vf_lut3d.c`：`refqueue.c refqueue.h vf_amf.c vf_d3d11vpp.c vf_fingerprint.c vf_format.c vf_gpu_egl.c vf_gpu_vulkan.c vf_gpu.c vf_gpu.h vf_sub.c vf_vapoursynth.c vf_vavpp.c vf_vdpaupp.c`。
- 全历史文件名搜索（`git fetch --unshallow` 后 55498 个提交）：
  ```
  $ git log --all --name-only --pretty=format: -- video/filter/ | sort -u | grep -i lut
  （无输出）
  $ git log --all --name-only --pretty=format: | sort -u | grep -i "lut3d"
  （无输出）
  ```
- 文档中亦无：`grep -rn "lut3d" DOCS/` 无匹配；抓取的官方手册页面 <https://mpv.io/manual/master/> 中 `grep -c "lut3d"` = `0`。
- 更早版本同样没有（GitHub contents API，`video/filter?ref=v0.29.0/v0.32.0/v0.35.0/v0.37.0` 四次查询均无 `vf_lut3d.c`）。

**事实**：mpv **从未**存在 `--vf=lut3d`（该 filter 属于 FFmpeg 的 libavfilter，见 §B.6.2）；mpv 侧的 LUT 支持是 `--lut` / `--image-lut` / `--target-lut`（仅 `--vo=gpu-next`）以及 ICC 路径（`video/out/gpu/lcms.c` 与 `gl_video_get_lut3d`）。因此任务中"vf_lut3d 的 colorspace / trc 之类选项"**在 mpv 中不存在**；`--lut-type` / `--image-lut-type` 也没有任何 `trc` 取值（取值只有 `auto|native|normalized|conversion`，见 §B.5.4 逐字）。

---

## B.6 Adobe Cube LUT Specification 1.0 与 DOMAIN_MIN/MAX 的业界使用

### B.6.1 事实：**未找到**可访问的 Adobe 官方原文

实测（`curl -sSL -o /dev/null -w "HTTP=%{http_code}"`）：

```
https://www.adobe.com/content/dam/acom/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf      HTTP=404
https://wwwimages2.adobe.com/content/dam/acom/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf  HTTP=404
https://www.adobe.com/content/dam/cc/en/products/speedgrade/cc/pdfs/cube-lut-specification-1.0.pdf        HTTP=404
https://download.adobe.com/pub/adobe/speedgrade/cube-lut-specification-1.0.pdf                            HTTP=404
```

其他尝试同样失败：`web.archive.org`（wayback available API 与 `web/…/id_/` 两种形式均连接超时）、`grep.app`（HTTP 429，Vercel 安全拦截）、`api.github.com/search/repositories?q=cube+lut+specification`（`total_count: 0`）、Bing/DuckDuckGo/Mojeek 搜索（返回本地化无关结果 / 403 / 超时）。

**结论：Adobe Cube LUT Specification 1.0 的官方原文 URL 未找到（四个候选 URL 全部 404，存档站不可达）。** 以下改用三份**实际解析器源码**与 Iridas 格式的转述注释作为 domain 语义的一手证据。

### B.6.2 事实：Iridas（.cube 起源）格式对 domain 的定义 —— 逐字保留在 OCIO 源码中

逐字（`src/OpenColorIO/fileformats/FileFormatIridasCube.cpp:22-41`，OpenColorIO @ `93878f9a`；该注释块首行给出上游文档 URL `http://doc.iridas.com/index.php/LUT_Formats`）：

```
/*

http://doc.iridas.com/index.php/LUT_Formats

#comments start with '#'
#title is currently ignored, but it's not an error to enter one
TITLE "title"

#LUT_1D_SIZE M or
#LUT_3D_SIZE M
#where M is the size of the texture
#a 3D texture has the size M x M x M
#e.g. LUT_3D_SIZE 16 creates a 16 x 16 x 16 3D texture
LUT_3D_SIZE 2

#Default input value range (domain) is 0.0 (black) to 1.0 (white)
#Specify other min/max values to map the cube to any custom input
#range you wish to use, for example if you're working with HDR data
DOMAIN_MIN 0.0 0.0 0.0
DOMAIN_MAX 1.0 1.0 1.0
```

**这是"输入域标注"语义的直接文字证据**：`Default input value range (domain) is 0.0 (black) to 1.0 (white)` / `Specify other min/max values to map the cube to any custom input range you wish to use`。

### B.6.3 事实：OCIO **确实**把 domain 用于输入映射（`CreateMinMaxOp`）

逐字（`src/OpenColorIO/fileformats/FileFormatIridasCube.cpp:546-556`，domain 被转成 op 并插在 LUT **之前**）：

```cpp
    const double dmin[]{ cachedFile->domain_min[0], cachedFile->domain_min[1], cachedFile->domain_min[2] };
    const double dmax[]{ cachedFile->domain_max[0], cachedFile->domain_max[1], cachedFile->domain_max[2] };

    switch (newDir)
    {
    case TRANSFORM_DIR_FORWARD:
    {
        CreateMinMaxOp(ops, dmin, dmax, newDir);
        if(lut1D)
        {
            CreateLut1DOp(ops, lut1D, newDir);
        }
```

逐字（`src/OpenColorIO/ops/matrix/MatrixOp.cpp:296-318`，实现即 `(in - min) / (max - min)`）：

```cpp
void CreateMinMaxOp(OpRcPtrVec & ops,
                    const double * from_min3,
                    const double * from_max3,
                    TransformDirection direction)
{
    double scale4[4] = { 1.0, 1.0, 1.0, 1.0 };
    double offset4[4] = { 0.0, 0.0, 0.0, 0.0 };

    bool somethingToDo = false;
    for (int i = 0; i < 3; ++i)
    {
        const double range = from_max3[i] - from_min3[i];
        if (range == 0.0)
        {
            throw Exception("CreateMinMaxOp: from_min and from_max must not be equal.");
        }
        scale4[i] = 1.0 / range;
        offset4[i] = -from_min3[i] * scale4[i];
        somethingToDo |= (scale4[i] != 1.0 || offset4[i] != 0.0);
    }

    if (somethingToDo)
    {
        CreateScaleOffsetOp(ops, scale4, offset4, direction);
    }
}
```

### B.6.4 事实：FFmpeg 的 `lut3d` 只把 domain 的**宽度**用于缩放（忽略 min 偏移）

逐字（`libavfilter/vf_lut3d.c`，FFmpeg master，本次从 <https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavfilter/vf_lut3d.c> 取得，HTTP 200，99822 字节）：

```c
                            if (!strncmp(line, "DOMAIN_", 7)) {
                                float *vals = NULL;
                                if      (!strncmp(line + 7, "MIN ", 4)) vals = min;
                                else if (!strncmp(line + 7, "MAX ", 4)) vals = max;
                                if (!vals)
                                    return AVERROR_INVALIDDATA;
                                if (av_sscanf(line + 11, "%f %f %f", vals, vals + 1, vals + 2) != 3)
                                    return AVERROR_INVALIDDATA;
                                av_log(ctx, AV_LOG_DEBUG, "min: %f %f %f | max: %f %f %f\n",
                                       min[0], min[1], min[2], max[0], max[1], max[2]);
                                goto try_again;
                            } else if (!strncmp(line, "TITLE", 5)) {
                                goto try_again;
                            }
```

```c
    lut3d->scale.r = av_clipf(1. / (max[0] - min[0]), 0.f, 1.f);
    lut3d->scale.g = av_clipf(1. / (max[1] - min[1]), 0.f, 1.f);
    lut3d->scale.b = av_clipf(1. / (max[2] - min[2]), 0.f, 1.f);
```

**事实**：FFmpeg 用 `1/(max-min)`（**不含 `min` 偏移**）作为输入缩放，即它把 domain 当输入域处理，但实现上只用了宽度。

### B.6.5 结论（事实）：业界是否真把 domain 当作"输入域标注"使用？

- **是，至少一个实际解析器这样做**：OpenColorIO 把 `DOMAIN_MIN`/`DOMAIN_MAX` 编译成 LUT 之前的 `MinMaxOp`，即 `(in - min)/(max - min)`（`src/OpenColorIO/fileformats/FileFormatIridasCube.cpp:546-556` + `src/OpenColorIO/ops/matrix/MatrixOp.cpp:296-318`）。其源码注释也逐字保留上游对 domain 的"input value range"定义（`FileFormatIridasCube.cpp:37-41`）。
- **另有解析器按"表项数据范围"使用**：libplacebo 用 domain 把 LUT 表项 rescale 到 [0,1]（`src/shaders/lut.c:168-169`），不保留也不使用 domain 作为输入域。
- **还有解析器只用宽度**：FFmpeg 用 `1/(max-min)`（`libavfilter/vf_lut3d.c` 中的 `lut3d->scale.*`）。
- **未找到**：Adobe 官方 spec 原文（§B.6.1），因此"spec 原话如何定义 DOMAIN_MIN/MAX 的用途"这一问题**无法用官方原文回答**；能给出的最接近的逐字文本是 OCIO 源码中保留的 Iridas 文档注释（§B.6.2）。

---

## B.7 事实 / 推断分界汇总

**事实（有逐字证据）**

1. libplacebo @ `e2972fd` 的渲染 pass 序列为 `pass_read_image → pass_scale_main → pass_convert_colors → pass_output_target`（`src/renderer.c:3652-3658`）。
2. 主 LUT（`pl_render_params.lut`）在 `pass_convert_colors` 内、tone mapping 块**之前**应用（`src/renderer.c:2345-2394` vs `2396-2419`）；`PL_LUT_CONVERSION` 时 `need_conversion = false`，即取代 tone mapping（`2356`、`1238-1239`）。
3. 目标帧 LUT（`pl_frame.lut` on target）在 tone mapping 之后应用（`src/renderer.c:2421-2423`、`2931-2940`）；源帧 LUT 更早（`src/renderer.c:2050-2093`）。
4. `pl_shader_custom_lut` 忽略 `pl_custom_lut` 的 `repr_in/out` 与 `color_in/out`（`src/include/libplacebo/shaders/lut.h:54` 逐字 `Note: This is purely informative, \`pl_shader_custom_lut\` ignores it.`）。
5. LUT 的 `color_in/color_out` 只在 `pl_render_params.lut` 路径被读取并用于构造 `pl_shader_color_map_ex` 的 src/dst（`src/renderer.c:2346-2392`）；无 `.state` 时不做 tone/gamut mapping（`src/include/libplacebo/shaders/colorspace.h:367-372`）。
6. `pl_lut_parse_cube` 解析 `DOMAIN_MIN`/`DOMAIN_MAX` 并用其 rescale 表项数据（`src/shaders/lut.c:91-113`、`168-169`），不保存 domain。
7. mpv 只调 `pl_lut_parse_cube`、不填 LUT 色彩元数据（`video/out/vo_gpu_next.c:2717-2745`），故 `auto` 恒为 `PL_LUT_NATIVE`（`src/renderer.c:1461-1482`）。
8. mpv 无 `vf_lut3d`（全历史文件名无匹配；`DOCS/` 无 `lut3d`；官方手册页面 `grep -c lut3d` = 0）。
9. mpv 的三个 LUT 选项与域语义逐字见手册（`--image-lut` 解码前、`--lut` 色彩转换前、`--target-lut` 目标编码/`--target-trc` 之后）。
10. ACES 官方文档：LMT 在 Output Transform 之前、ACES2065 进 ACES2065 出；ACES 1.x 为 RRT+ODT，ACES 2 为 Rendering Transform + Display Encoding。
11. ACES 1.3 的 Rec.709 ODT CTL 含 `bt1886_r(..., DISPGAMMA=2.4, ...)`（`transforms/ctl/odt/rec709/ODT.Academy.Rec709_100nits_dim.ctl:57,113-117`），注释 `Encode linear code values with transfer function`。

**推断（由事实推导，非原文）**

- I1. "mpv 的 `--lut` 实际不会在 LUT 前后插入任何 gamma 编解码"——由事实 6+7 推导：`lut_in/lut_out` 经 `pl_color_space_merge` 后与 `image->color` 相等，`pl_shader_color_map_ex` 在 src==dst 时直接 return（`src/shaders/colorspace.c:1705-1708`）。
- I2. "ACES 体系里风格 LUT 的正规接法是 LMT（在 Output Transform 之前），接在 ODT 之后不属于 ACES 的 LMT 定义"——由 §A.3/A.5 的官方原文推导；官方原文没有用"风格 LUT 必须接在 X"这种句式，而是通过 LMT 的定义与"不要直接搬 look file"的警告来表达。
- I3. FFmpeg 的 domain 处理忽略 `min` 偏移是否属于 bug，本次**未找到**官方说明，不作判断。

---

## 附：本报告用到的本地 clone 路径

```
/private/tmp/lp           libplacebo          e2972fdd09adacd383656738d7d280f0cd84a761
/private/tmp/mpvsrc       mpv                 c64675679925df40c3cbecb49bdfb9c93f918745
/private/tmp/ociosrc      OpenColorIO         93878f9a8630081a6e8d866acf9fd119400c7a4e
/private/tmp/occaces      OCIO-Config-ACES    b7b08c8c2750f524c66a0ef24174b4ffaf8efe3c
/private/tmp/occaces_cs   OCIO-Config-ACES    ebd5df1e747a58d018a78a9b2a08fec74f60dd98  (colour-science, 2022 旧快照)
/private/tmp/acescore     aces-core           069b0bc3e1f6c62820f19fdae2fecec3f4fc0f80
/private/tmp/aces13       aces-core v1.3      1256fee50ee35548c6eab8eca854ff3349008489
/private/tmp/ff_lut3d.c   FFmpeg vf_lut3d.c   master (raw.githubusercontent.com, HTTP 200)
```
