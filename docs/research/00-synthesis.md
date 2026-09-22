# 调研汇总：其他开源项目如何做「RAW → 基础色彩外观」，以及如何承载风格 3D LUT

> 独立调研。所有结论附逐字证据（`文件:行号` + 原文，或 URL + 原文）。
> 逐字原文一律不翻译。明确区分【源码事实】/【文档事实】/【推断】/【未找到】。
> 详细分项报告见同目录 `01–06`。本文件是汇总与结论。

## 0. 取证环境（所有 commit 均为本次实际 clone 的 HEAD）

| 项目 | 本地路径 | commit | 日期 |
|---|---|---|---|
| darktable 源码 | `/private/tmp/dtsrc` | `92f6c282845943ad8f242c3dcb040a3c75c877b9` | 2026-09-22 |
| darktable 手册源 | `/private/tmp/dtdocs` | `79c10958aee76654dcac55a7c5e645a05621657b` | 2026-09-22 |
| libplacebo | `/private/tmp/lpsrc` | `e2972fdd09adacd383656738d7d280f0cd84a761` | 2026-09-18 |
| OpenColorIO | `/private/tmp/ociosrc` | `93878f9a8630081a6e8d866acf9fd119400c7a4e` | 2026-09-16 |
| Blender（子代理取证） | `/private/tmp/blender-agx/bsrc` | `a7e89d18c15dbd688f532a24aca8352b94b9ace6`（Blender 5.3.0 alpha） | 2026-09-22 |
| PhotonCamera | `/tmp/photoncamera` | `2557a1d6e5fa0c2017fa4797b3a3557c54137dbe` | 2026-09-21 |
| Adobe DNG SDK（PhotonCamera 内嵌） | `/tmp/photoncamera/app/src/main/cpp/dcp/dng_sdk` | 随 PhotonCamera commit | — |
| RawTherapee `rtengine/dcp.cc` | `/private/tmp/rt_dcp_new.cc`（`dev` 分支单文件） | 未固定 commit（单文件抓取） | — |
| mpv 文档 | `/private/tmp/mpv_opts.rst`、`mpv_vf.rst`（`master` 单文件） | 未固定 commit | — |

**网络限制（重要）**：本环境 `web.archive.org`、`html.duckduckgo.com`、`docs.acescentral.com` 均连接超时；
Adobe 官方 Cube LUT Specification 1.0 PDF 的多个 URL 均 **HTTP 404**。因此规范原文类证据改由
**实现源码 + 官方手册源仓库（GitHub raw）** 提供，凡未能取得者一律标注【未找到】。

---

## Q1. 影调映射与 3D LUT 的标准先后顺序，以及为什么这样排

### 结论

**业界公认的默认位置是：tone mapping 之后、显示编码域（display-referred）。
但这不是绝对律令——ACES 的 LMT 与 ART 的默认 film simulation 就在显示变换之前，
因为它们承载的是 scene-referred 语义。真正的判据是 LUT 的构建域（见证据 5 的对照表）。**

### 证据 1（最强）：darktable 官方手册逐字规定默认在 tone mapping 之后

URL（GitHub raw，可自行核验）：
`https://raw.githubusercontent.com/darktable-org/dtdocs/master/content/module-reference/processing-modules/lut-3D.md`
本地：`/private/tmp/dtdocs/content/module-reference/processing-modules/lut-3D.md:42`

```
LUTs are most commonly used in darktable for color grading or film look simulation. For this reason, by default, the module is placed after the [_filmic rgb_](./filmic-rgb.md) module in the pixelpipe and should be applied to a neutral image (without first applying a specific look).
```

【文档事实】关键三点：
1. `by default, the module is placed **after** the filmic rgb module in the pixelpipe` —— **LUT 在 tone mapping 之后**；
2. `should be applied to a **neutral image** (without first applying a specific look)` —— **LUT 前的基础外观必须是"中性"的**；
3. `the module clips all values outside of the range [0,1]`（同文件 `:36`）—— 模块假定输入已是 **[0,1] 显示范围**。

同文件 `:44` 给出**唯一例外**（这也是"LUT 也可以接在场景线性/log 域"的官方指引）：

```
Camera log LUTs (as F-log or S-Log3) are different to color-grading and film-look-simulation LUTs, and are intended to convert the camera log raw data into something (linear raw data or other color space) that darktable is able to understand. In this case the _LUT 3D_ module should be manually placed between the [_demosaic_](./demosaic) and [_input color profile_](./input-color-profile.md) modules.
```

【文档事实】即：**"相机 log → 图像"的还原型 LUT 放在 demosaic 与 input color profile 之间（scene-linear 侧）；
"风格"型 LUT 放在 filmic rgb 之后（display 侧）**。两种 LUT 的**语义不同，位置也不同**。

### 证据 2：darktable 源码里的默认管线位置与之一致

`src/common/iop_order.c` 的默认顺序表 `v50_order[]`（起始于 `:306`）与 `v30_order[]`（`:183`）中，
`lut3d` 的条目出现在 `filmicrgb`/`spektrafilm` 之后：

`/private/tmp/dtsrc/src/common/iop_order.c:392`（v50_order 内）：
```c
  { { 36.0f }, "lut3d", 0},           // apply a creative style or film emulation, possibly non-linear
```

其**前后邻居**（同表，`:381–397` 区段逐字）：
```c
  { {44.0f }, "basecurve", 0},       // conversion from scene-referred to display referred, reverse-engineered
  { {45.0f }, "filmic", 0},          // same, but different (parametric) approach
  { {45.3f }, "sigmoid", 0},
  { {45.5f }, "agx", 0},
  { {46.0f }, "filmicrgb", 0},       // same, upgraded
  { { 46.7f }, "spektrafilm", 0 },
  { { 36.0f }, "lut3d", 0},         // apply a creative style or film emulation, possibly non-linear
  { { 47.0f }, "colisa", 0},        // edit contrast while damaging colour
  { { 48.0f }, "tonecurve", 0},     // same
```

**【重要陷阱，本次核实的独立发现】**：`lut3d` 这一行的 order **值**写的是 `36.0f`，
比 `filmic`(45.0)/`sigmoid`(45.3)/`agx`(45.5)/`filmicrgb`(46.0) **都小**，但它在**数组中的位置**在它们之后。
两者矛盾。核实结果：

- `dt_iop_order_entry_t` 的 order 是 union（`src/common/iop_order.h:142–152`）：
```c
typedef struct dt_iop_order_entry_t
{
  union {
    double iop_order_f;  // only used for backward compatibility while migrating db
    int iop_order;       // order from 1 and incrementing
  } o;
```
- 表里只初始化 `iop_order_f`，而**排序与取用走 int**，注释明说（`src/common/iop_order.c:75–77`）：
```c
// in the new code only the iop-order as int is used to order the
// module on the GUI.
```
- `_table_to_list()`（`:1125–1143`）只复制 `o.iop_order_f`，**不排序**；随后
`_ioppr_reset_iop_order()`（`:1266–1276`）把列表**按当前列表顺序**重新编号为 100、200、300…：
```c
static void _ioppr_reset_iop_order(GList *iop_order_list)
{
  // iop-order must start with a number > 0 and be incremented. There is no
  // other constraints.
  int iop_order = 100;
  for(const GList *l = iop_order_list; l; l = g_list_next(l))
  {
    dt_iop_order_entry_t *e = l->data;
    e->o.iop_order = iop_order;
```

【推断，已由手册独立印证】因此 **实际顺序由表中数组位置决定**，`{36.0f}` 是不生效的历史遗留值；
`lut3d` 实际位置 = `filmicrgb` 之后。该推断与证据 1 的手册原文完全一致。
**结论：不能只读 order 数值来判断 darktable 的模块顺序。**

### 证据 3：libplacebo 同时提供「tone mapping 前」与「后」两个 LUT 插槽

libplacebo 有**三个** LUT 插槽，分别在管线不同阶段：

| 插槽 | 位置 | 证据 |
|---|---|---|
| `image->lut`（输入帧） | 最早，`pass_read_image` 内 | `src/renderer.c:2046–2096` |
| `params->lut`（渲染参数） | **tone mapping 之前** | `src/renderer.c:2345–2392` |
| `target->lut`（输出帧） | **tone mapping 之后**，且在 ICC encode 之后 | `src/renderer.c:2421–2423`、`:2931–2940` |

`params->lut` 与 tone mapping 的相邻关系（`src/renderer.c`，`pass_convert_colors`，函数起始 `:2304`）：

```c
    if (params->lut) {                                    // :2345
        ...
        pl_shader_custom_lut(sh, params->lut, &rr->lut_state[LUT_PARAMS]);   // :2381
        ...
    }

    if (need_conversion) {
        struct pl_color_space target_csp = target->color;
        if (target->icc)
            target_csp.transfer = PL_COLOR_TRC_LINEAR;
        ...
        // current -> target
        pl_shader_color_map_ex(sh, params->color_map_params, pl_color_map_args(   // :2405
            .src           = image->color,
            .dst           = target_csp,
            .prelinearized = prelinearized,
            .state         = &rr->tone_map_state,          // tone mapping 状态
            .feature_map   = feature_map,
        ));

        if (target->icc)
            pl_icc_encode(sh, target->icc, &rr->icc_state[ICC_TARGET]);
    }

    enum pl_lut_type lut_type = guess_frame_lut_type(target, true);   // :2421
    if (lut_type == PL_LUT_NORMALIZED || lut_type == PL_LUT_CONVERSION)
        pl_shader_custom_lut(sh, target->lut, &rr->lut_state[LUT_TARGET]);
```

【源码事实】`params->lut` 在 `pl_shader_color_map_ex(..., .state = &rr->tone_map_state)`（即 tone mapping）**之前**；
`target->lut` 在**之后**，且在 `pl_icc_encode` 之后。
（tone mapping 由 `pl_shader_color_map_ex` 通过 `.state = &rr->tone_map_state` 承担，见 `src/renderer.c:68, 993, 2413`。）

### 证据 4：PhotonCamera 的 LUT 在 tone curve 与色彩配方之后

【源码事实】`app/src/main/java/com/hinnka/mycamera/lut/LutImageProcessor.kt:3106`：
```glsl
                // === LUT 处理（在色彩配方之后） ===
```
同段 `:3126`：
```glsl
                    // 在非线性 sRGB 空间混合；超过 1.0 时沿 LUT 色差外推，支持最高 200% 强度。
```
即 **色彩配方（ColorRecipe，含对比/饱和/tone LUT）→ 用户 LUT**，且混合发生在**非线性 sRGB** 域。

### 为什么这样排（证据层面对"为什么"的回答）

1. **用户 LUT 是"创作/风格"语义，不是"技术转换"语义。**
   darktable 明确把它归为 "color grading or film look simulation"，并要求输入是 "a neutral image"
   （`lut-3D.md:42`）。技术上必须先把 scene-linear 压进显示可呈现范围（tone mapping + OETF），
   风格 LUT 才能在一个稳定的 [0,1] 显示分布上做**绝对映射**。
2. **域语义必须与 LUT 的构建域一致。** libplacebo 用类型显式表达这件事（`src/include/libplacebo/renderer.h:84–98`）：
```c
    PL_LUT_UNKNOWN = 0,
    PL_LUT_NATIVE,      // applied to raw image contents (after fixing bit depth)
    PL_LUT_NORMALIZED,  // applied to normalized (HDR) RGB values
    PL_LUT_CONVERSION,  // LUT fully replaces color conversion
```
   并在 `:239–248` 进一步给出域的**物理含义**：
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
```
   【源码事实】"gamma light"（= 显示编码）对应 `PL_LUT_NATIVE`，"linear light" 对应 `PL_LUT_NORMALIZED`。
   **一个 display-referred 风格 LUT 的输入就是 gamma light，因此它必须接在 OETF 之后。**
3. **tone mapping 会改变每个像素的坐标，LUT 必须在其后以保持"所见即所得"。**
   darktable 的 `application color space` 默认就是 sRGB（见 Q2），即 LUT 索引直接吃显示编码值；
   若在 tone mapping 之前套用，tone mapping 之后输出分布会与 LUT 设计输入分布不一致。

### 证据 5：ACES 把"风格"放在显示变换**之前**——因为它是 scene-referred 的 LMT

**【关键区分】ACES 的 LMT/Look 与 darktable/libplacebo 的"风格 LUT"不是同一种东西：
前者是 scene-referred，后者是 display-referred。位置相反，原因正是域不同。**

ACES 的三段结构（OCIO 官方文档，`docs/configurations/aces_1.0.3.rst`）：

`:104`（Input Transform = IDT）：
```
Colorspaces and transforms that implement the ACES Input Transforms. These colorspaces are used to convert from camera-specific formats and encodings to ACES.
```
`:87–90`（Output Transform = RRT + ODT）：
```
Colorspaces and transforms implementing the ACES Output Transforms. These
colorspaces produce code values ready for display on hardware devices calibrated
to the standard used to name the colorspace.
```
`:196–206`（Look = LMT 的位置，逐字）：
```
Look
----

**Colorspaces**

- ACES 1.0 to 0.1 emulation
- ACES 1.0 to 0.2 emulation
- ACES 1.0 to 0.7 emulation

**Description**

Colorspaces and transforms emulating the look of the ACES 0.1, 0.2 and 0.7 release.

- Should be applied to data in the ACES2065-1 colorspace.
- Should be used before an ACES Output Transform.
```
`:188`（LMT 的 shaper 范围）：
```
- The LMT shaper spaces cover the linear range going from 10 stops below 18% grey (0.00017578125) to 6.5 stops above 18% grey (16.291740)
```

OCIO 的实现把 RRT 与 ODT 明确分开（`src/OpenColorIO/transforms/builtins/ACES.cpp`）：
```
:144  void Generate_RRT_preamble_ops(OpRcPtrVec & ops)
:164      static constexpr double RRT_SAT_MAT[4 * 4]
:179      // Apply RRT shaper using the same quadratic B-spline as the CTL.
:203      // Apply SDR ODT shaper using the same quadratic B-spline as the CTL.
```

**「LUT 在 ACES 体系里接在哪一步」的直接答案**（`docs/guides/using_ocio/tool_overview.rst:278–289`）：
```
    $ ociomakeclf lut_file.cube lut_file.clf

The --csc argument may be used to convert the LUT into an ACES compliant Look
Modification Transform (LMT) that may be referenced from an ACES Metadata File.
An ACES LMT requires ACES2065-1 color space values on input and output.  The
--csc argument is used to specify the standard color space that the Look LUT
expects on input and output and the tool will prepend a transform from ACES2065-1
to the LUT color space and postpend a transform from that color space back to
ACES2065-1::

    $ ociomakeclf my_ACEScct_look.cube my_LMT.clf --csc ACEScct
```
【事实】ACES 把任意 LUT 变成 LMT 的方式是**在它前后各包一层 ACES2065-1↔LUT空间 的转换**，
使 LMT 的对外接口固定为 **ACES2065-1 (scene-referred)**。流程为：
**IDT → (LMT/Look，接口=ACES2065-1) → Output Transform (RRT+ODT) → display**。

LMT 还可以带 `process_space`（`docs/guides/authoring/looks.rst:54–60`）：
```yaml
      - !<Look>
        name: ACES 1.3 Reference Gamut Compression
        process_space: ACES2065-1
        description: LMT (applied in ACES2065-1) to compress scene-referred values from common cameras into the AP1 gamut
```

**【综合 Q1/Q2 的最终答案】业界并不存在"唯一的先后顺序"，而是两条**语义明确区分**的路线：**

| LUT 语义 | 位置 | 域 | 项目证据 |
|---|---|---|---|
| **风格/调色（display-referred）** | 显示变换（tone mapping + OETF）**之后** | display-encoded | darktable 手册 `lut-3D.md:42`；PhotonCamera `LutImageProcessor.kt:3106/3126`；libplacebo `PL_LUT_NATIVE`="gamma light" |
| **技术还原 / scene-referred LMT（含相机 log 还原）** | IDT 之后、显示变换**之前** | scene-linear / ACES2065-1 / camera log | ACES `tool_overview.rst:278–289`；darktable `lut-3D.md:44`（相机 log LUT 放 demosaic 与 input color profile 之间）；ART 默认 `after_tone_curve=false` |

**判断依据永远是"这个 LUT 是按哪个域构建的"，而不是某个项目的规定。**

【未找到】`docs.acescentral.com` 本次连接超时，**ACES 官方文档（ACES Central）的 IDT→LMT→RRT→ODT 规范表述未能直接取得**；
上述 ACES 结论基于 OCIO 官方文档与源码。

---

## Q2. 各项目里 3D LUT 被应用在哪种域

| 项目 | LUT 域（源码/文档事实） | 证据 |
|---|---|---|
| **darktable** `lut3d` | 默认 **sRGB 编码**；可选 gamma Rec709 / AdobeRGB / **linear** Rec709 / linear Rec2020 / linear ProPhoto；输入裁剪到 [0,1]。模块元数据自称输入是 "linear, RGB, display-referred"，但代码不做域断言：**总是先把 work profile（默认线性 Rec2020）转成 `application color space` 再查表，查完转回** | `src/iop/lut3d.c:55–63, 75, 1028–1034, 1156–1157`；`lut-3D.md:36, 51–52`；`01-darktable.md` §1 |
| **libplacebo** | "normalized RGB space"；`NATIVE`=gamma light，`NORMALIZED`=linear light，`CONVERSION`=自带色彩空间转换 | `src/include/libplacebo/renderer.h:84–98, 239–248` |
| **PhotonCamera** | **display 编码 sRGB**（非线性域混合）；域可由 `uLutCurve`/`uLutColorSpace` 指定（sRGB/Linear/VLog/SLog3/FLog2/LOGC4/AppleLog/HLG/ACEScct/Log3G10/LLog × sRGB/DCI-P3/Rec2020/ARRI4/AppleLog2/S-Gamut3.Cine/AP1/V-Gamut/RED） | `lut/PreviewColorShader.kt:311–329`；`lut/PreviewColorShaderModules.kt:287–362`；`color/TransferCurve.kt:14–83` |
| **Blender AgX 的末端** | display-referred `.cube`，其输出自述为 **power 2.4 编码**，再由 display colorspace 施加最终 OETF | `release/datafiles/colormanagement/luts/AgX_Base_sRGB.cube:4`；`config.ocio:243–253, 330–331` |
| **mpv** | 用户侧已无独立 `vf=lut3d`（`filters/` 目录内无 `f_lut3d.c`）；3D LUT 仅作为 ICC 色彩管理载体存在 | mpv `filters/` 目录清单（GitHub API，2026-09）；`DOCS/man/options.rst:7939` `--icc-3dlut-size` |

### darktable 的 `application color space`（逐字）

`src/iop/lut3d.c:55–63`：
```c
typedef enum dt_iop_lut3d_colorspace_t
{
  DT_IOP_SRGB = 0,    // $DESCRIPTION: "sRGB"
  DT_IOP_ARGB,        // $DESCRIPTION: "Adobe RGB"
  DT_IOP_REC709,      // $DESCRIPTION: "gamma Rec709 RGB"
  DT_IOP_LIN_REC709,  // $DESCRIPTION: "linear Rec709 RGB"
  DT_IOP_LIN_REC2020, // $DESCRIPTION: "linear Rec2020 RGB"
  DT_IOP_LIN_PROPHOTO,// $DESCRIPTION: "linear ProPhoto RGB"
} dt_iop_lut3d_colorspace_t;
```
默认值（`:75`）：
```c
  dt_iop_lut3d_colorspace_t colorspace; // $DEFAULT: DT_IOP_SRGB $DESCRIPTION: "application color space"
```
应用到图像时在 `work_profile` 与 `lut_profile` 之间转换（`:1028–1036`、`:1068–1084`）：
```c
  const int colorspace
    = (d->params.colorspace == DT_IOP_SRGB) ? DT_COLORSPACE_SRGB
    : (d->params.colorspace == DT_IOP_REC709) ? DT_COLORSPACE_REC709
    : (d->params.colorspace == DT_IOP_ARGB) ? DT_COLORSPACE_ADOBERGB
    : (d->params.colorspace == DT_IOP_LIN_PROPHOTO) ? DT_COLORSPACE_PROPHOTO_RGB
    : (d->params.colorspace == DT_IOP_LIN_REC709) ? DT_COLORSPACE_LIN_REC709
    : DT_COLORSPACE_LIN_REC2020;
  const dt_iop_order_iccprofile_info_t *const lut_profile
    = dt_ioppr_add_profile_info_to_list(self->dev, colorspace, "", INTENT_PERCEPTUAL);
```
手册对该控件的说明（`lut-3D.md:51–52`）：
```
application color space
: A 3D LUT is defined relative to a specific color space. Choose the color space for which the selected 3D LUT file has been built. Cube files are usually related to Rec. 709 while most others are related to sRGB.
```
【文档事实】darktable 明确承认 **`.cube` 通常按 Rec.709 构建**，而其它格式多按 sRGB —— 这正是"LUT 必须声明并校验输入域"的业界共识。

### libplacebo 的域适配实现（颜色空间怎么被用上）

`src/renderer.c:2345–2392`：
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
【源码事实】这就是"LUT 的 colorspace/trc 域字段如何生效"的标准答案：
**LUT 携带 `color_in`/`color_out`（`pl_color_space` = primaries + transfer），
引擎在 LUT 前把图像映射到 `lut_in`，在 LUT 后映射回原空间**；
`NORMALIZED` 模式还会按 `pl_color_transfer_nominal_peak` 做 HDR 峰值归一化。
不存在"把 domain 当 TRC"的做法——**TRC 由独立的 color space 字段承担**。

### PhotonCamera 的域字段（与 libplacebo 同构，但为移动端自定义）

见 `06-photoncamera-baseline-engine.md` §4。要点：`uLutCurve`（TRC）+ `uLutColorSpace`（gamut）两个 uniform，
调用顺序为 `srgbToLinear → applyLutColorSpace(矩阵) → applyLutCurve(编码) → 采样 LUT`，
默认 `curve = TransferCurve.SRGB`、`colorSpace = ColorSpace.SRGB`（`lut/LutConfig.kt:24–25`）。

### RawTherapee / ART 的域（子代理取证，报告 `02-rawtherapee-art.md`）

| 项目 | 结论 |
|---|---|
| **RawTherapee DCP 顺序** | WB×CCM → HueSatMap(**linear ProPhoto**) → baseline exposure → LookTable → ToneCurve。证据 `rtengine/dcp.cc:1624-1650`；与 Adobe 同序 `dng_sdk/source/dng_render.cpp:1817/1902/1927/1946` |
| **RawTherapee Film Simulation（HaldCLUT）** | 在 **Tone Curve 之后**、**Lab tone mapping 之前**；`rtengine/clutstore.cc` + `improcfun.cc:3138`（tone curve 在 `:2544/:2591`，Lab 转换在 `:3276`）。**LUT 前先做 sRGB gamma 编码**（`improcfun.cc:3187-3191`，注释 `// Apply gamma sRGB (default RT)`），查表后逆编码（`:3207-3210`） |
| **RawTherapee `.cube` LUT** | **源码中未找到**（1566 个受控文件 0 个匹配 `cube|lut3d`，无 `lut.cc`，无 `DOMAIN_MIN`）⇒ RawTherapee 只支持 HaldCLUT 图像，不解析 `.cube` |
| **ART** | `improcfun.cc:636-712` 明列 STAGE_3 顺序：`logEncoding → saturationVibrance → dcpProfile → filmSimulation → toneCurve → …`；`after_tone_curve` **默认 false**（`procparams.cc:2280`）⇒ **ART 默认把胶片模拟放在 Tone Curve 之前**；`ppversion.h:19-21` 记录 "changed position of the film simulation tool in the pipeline" ⇒ 该位置是可配置的设计选择 |

【该报告的核心结论，逐字转述】"二者数值载体是 **scene-referred 线性工作空间 RGB**，
但 **LUT 查找坐标是 display-referred 的 sRGB gamma 编码**。"

**【重要】**：这条结论把"LUT 应用域"拆成了两个独立问题——**载体空间**（管线里图像用什么空间存放）
与**查找坐标**（喂给 LUT 索引的值用什么编码）。RawTherapee/ART 的答案与 PhotonCamera 完全同构：
**载体是线性工作空间，查找坐标是 sRGB 编码**。这也解释了为什么 darktable 的 `application color space`
是一个独立控件而不是"当前管线空间"。

**并且 ART 默认把风格模拟放在 tone curve 之前**，说明"LUT 在 tone mapping 之后"并非唯一正确路线；
它是**取决于 LUT 自身构建域**的选择，与 Q1 证据 2/3（libplacebo 同时提供前后两个插槽）互相印证。

---

## Q3. 有没有项目把「相机厂商外观」做成引擎而不是 LUT？数据源是什么？

### 有。最强的两个证据源：Adobe DNG SDK / DCP 体系，以及 PhotonCamera。

### 3.1 Adobe DNG SDK：厂商外观 = 参数化引擎（7 段），不是一张 LUT

【源码事实】`/tmp/photoncamera/app/src/main/cpp/dcp/dng_sdk/source/dng_render.cpp` 的渲染主循环里，
每个阶段的**注释本身就是顺序说明书**：

```
:1893   DoBaselineABCDtoRGB (...)              // 相机 RGB → 工作空间（CCM）
:1906   // Apply Hue/Sat map, if any.
:1908   DoBaselineHueSatMap (... *fHueSatMap.Get () ...)
:1929   // Apply ProfileGainTableMap / ProfileGainTableMap2.
:1960   // Apply exposure curve.
:1985   // Apply look table, if any.
:2004   // Apply baseline tone curve.
:2027   // Apply RGBTables, if any.
```

即完整顺序：
**CCM(ABCDtoRGB) → HueSatMap → ProfileGainTableMap → ExposureRamp → LookTable → ToneCurve → RGBTables**

数据结构（同文件 `:797–817`）：
```c
		AutoPtr<dng_hue_sat_map> fHueSatMap;
		AutoPtr<dng_hue_sat_map> fLookTable;
		dng_1d_table fToneCurve;
		AutoPtr<dng_line_real32> fToneCurveSlopeExtension;
		AutoPtr<dng_1d_table> fHueSatMapEncode;
		AutoPtr<dng_1d_table> fHueSatMapDecode;
		AutoPtr<dng_1d_table> fLookTableEncode;
		AutoPtr<dng_1d_table> fLookTableDecode;
```
注意 `fHueSatMapEncode/Decode` 与 `fLookTableEncode/Decode`：profile 可以声明 HueSatMap/LookTable 的
value 通道使用**非线性编码**（`:980–997` 依 `profile.HueSatMapEncoding()` / `LookTableEncoding()` 建表）。
**【源码事实】这就是"引擎"而非"LUT"的标志：同一份表可以被声明在不同编码域中求值。**

### 3.2 RawTherapee：同一顺序，且显式承认"HueSatMap 在 sRGB gamma 域查表"

`/private/tmp/rt_dcp_new.cc`（RawTherapee `dev:rtengine/dcp.cc`）：

```cpp
:2013 inline void DCPProfile::hsdApply(const HsdTableInfo& table_info, const std::vector<HsbModify>& table_base, float& h, float& s, float& v) const
:2015 {
:2016     // Apply the HueSatMap. Ported from Adobes reference implementation.
```
```cpp
:2035         if (table_info.srgb_gamma) {
:2036             v_encoded = Color::gammatab_srgb1[v * 65535.f];
:2037         }
```
【源码事实】`table_info.srgb_gamma` 控制 value 通道是否先经 **sRGB gamma 表**再取索引 —— 与 DNG SDK 的
`HueSatMapEncoding` 是同一机制的两个实现。**HueSatMap/LookTable 工作在 HSV 域，其 V 通道可带编码。**

### 3.3 PhotonCamera：把厂商外观做成"可插拔引擎枚举"，每个引擎自带工作色彩空间

【源码事实】`app/src/main/java/com/hinnka/mycamera/raw/RawRenderingEngine.kt:10–70`（节选）：
```kotlin
enum class RawRenderingEngine(
    val shaderId: Int,
    val workingColorSpace: ColorSpace,
    ...
) {
    AdobeCurve(shaderId = 0, workingColorSpace = ColorSpace.ProPhoto, ...),
    Hncs(shaderId = 6, workingColorSpace = ColorSpace.HNCS, ...),
    Lumix(
        shaderId = 7,
        // Output/adjustment space only: calibration maps source WB camera RGB
        // to S9 camera RGB before the PhotoStyle curve/LUT, without sRGB encoding.
        workingColorSpace = ColorSpace.SRGB, ...),
    Canon(
        shaderId = 8,
        // Output/adjustment space; the renderer owns its Picture Style input domain.
        workingColorSpace = ColorSpace.SRGB, ...),
    AgX(shaderId = 1, workingColorSpace = ColorSpace.BT2020, ...),
    Spektrafilm(shaderId = 2, workingColorSpace = ColorSpace.ProPhoto, ...),
    DarktableSigmoid(shaderId = 3, workingColorSpace = ColorSpace.BT2020, ...),
```
引擎实现（`raw/` 目录）：`AgXToneAlgorithm.kt`、`DarktableSigmoidToneAlgorithm.kt`、
`DarktableFilmicToneAlgorithm.kt`、`CanonToneAlgorithm.kt`、`CanonIccProfile.kt`、`CanonPictureStyle.kt`、
`LumixToneAlgorithm.kt`、`LumixPhotoStyle.kt`、`HncsToneAlgorithm.kt`、`HncsProfile.kt`、
`SpektrafilmToneAlgorithm.kt`、`DcpProfile.kt`、`DcpHueSatMapGl.kt`、`DngProfileToneCurve.kt`、
`ACR3Curve.kt`；以及 C++ 的光谱物理模型 `app/src/main/cpp/spektrafilm/`（`spectral_filming.cpp`、
`spectral_printing.cpp`、`spectral_gamut.cpp`、`spectral_data.cpp`）。

### 数据源（逐条证据）

| 数据源 | 证据 |
|---|---|
| **Adobe DCP / DNG profile** | PhotonCamera 内嵌完整 Adobe DNG SDK（`app/src/main/cpp/dcp/dng_sdk/`）+ `dcp_parser.cpp`；`raw/DcpProfile.kt`、`DngEmbeddedProfile.kt`、`DngSdkColorSpec.kt` |
| **厂商 PictureStyle / ICC** | `CanonPictureStyle.kt`、`CanonIccProfile.kt`、`LumixPhotoStyle.kt` |
| **从厂商软件恢复的矩阵（"recovered"）** | `raw/ColorSpace.kt:74–85` 注释逐字：`Chromaticities are derived from the recovered CRawColorCorrection::GetColorSpaceChangeMatrix RGB-to-XYZ(D50) matrix [0.79767, 0.13519, 0.03134; 0.28804, 0.71188, 0.00009; 0, 0, 0.82491]` |
| **规范定义的理论变换（darktable/Blender 复刻）** | `AgXToneAlgorithm.kt`、`DarktableSigmoidToneAlgorithm.kt`、`DarktableFilmicToneAlgorithm.kt` |
| **光谱级物理模型（非 LUT）** | `spektrafilm/spectral_data.cpp` 等 |

### 数据源的第四种：**用户自标定**（dcamprof），以及 darktable 走的是 ICC 而非 DCP

**dcamprof**（现行地址 `Beep6581/dcamprof`；原 `anderstorger/dcamprof` 本次 API 返回 Not Found）
是"把相机外观做成引擎参数"的**生成工具**，其源码结构本身即为证据
（GitHub API 目录清单：`dnglut.c`、`dngref.c`、`look.c`、`lut3d.c`、`icclut.c`、`target.c`、
`matopt.c`、`spectraldb_cc24.c`、`spectraldb_munsell.c`）：

README（`https://raw.githubusercontent.com/Beep6581/dcamprof/master/README.md`，本次 HTTP 200）逐字：
```
DCamProf is a free and open-source command line tool for making camera profiles, and performing tasks related to camera profiles and profiling.
...
A tutorial on creating DCP profiles using DCamProf and RawTherapee can be found in RawPedia:
http://rawpedia.rawtherapee.com/How_to_create_DCP_color_profiles
```

其官方文档 `www/camera-profiling.html`（本次 HTTP 200，307KB）逐字给出 HueSatMap 的**内部色彩空间**：
```
Floating point linear RGB with ProPhoto primaries is the internal color space of Adobe Camera Raw. Apply color adjustments via the "HueSatMap" lookup table, if it's available. If dual-illuminant there are two tables (one for each illuminant) and those are mixed according to the calculated whitepoint. The table can adjust RGB hue, saturation and value, that is a full 3D LUT. The table divides the HSV space in equisized sections and make adjustments by scaling input values.
```
以及 LookTable 的位置（曝光之后）：
```
If the total exposure adjustment sum is negative, it's postponed to the tone curve step, to avoid feeding the "LookTable" with an overly dark image.
```
文档目录还包含 `Ways to manage looks`、`Designing your own look`、`Copying a look`、`Step 8: choose a tone curve and exposure offsets`、
`Matching the curve used by the camera` —— 即 **look 是可设计、可复制的引擎参数**。

**数据源**（同文档逐字）：`The classic 24 patch color checker, here in the form of X-Rite's ColorChecker Passport. Reflectance spectra for the CC24 target.`
⇒ **实测色卡光谱 + 用户自标定**，产物是 DCP（HueSatMap / LookTable / ToneCurve）或 ICC。

**darktable 则完全不支持 DCP**：`grep -rli "dcp" src/` 在 darktable 源码树中**无任何命中**；
其 input color profile 只使用 `dt_colorspaces_color_profile_type_t`（默认 `DT_COLORSPACE_ENHANCED_MATRIX`，
`src/iop/colorin.c:77`）。⇒ **darktable 的相机外观数据源是 ICC profile（而非 DCP）**。

【源码事实】**结论：存在至少三个层次的"引擎化"：**
1. **规范/厂商 profile 引擎**（Adobe DCP：参数表 + 编码声明 + 顺序化管线）——数据源是原厂或 Adobe 提供的 profile；
2. **厂商外观引擎**（Canon PictureStyle / Lumix PhotoStyle / Hasselblad HNCS）——数据源是原厂曲线/ICC/恢复矩阵；
3. **物理模型引擎**（PhotonCamera spektrafilm、Blender AgX 的烘焙 LUT）——数据源是理论模型。

### Blender AgX：把"显示变换"本身实现为一张 display-referred `.cube`（引擎 → LUT 的极限形态）

【源码事实，子代理取证 + 本次复核】Blender 的 AgX **没有任何 C++/GLSL 实现**，
全部是 OCIO config + 预烘焙 LUT：

`release/datafiles/colormanagement/config.ocio:1241–1268`（`AgX Log`）：
```yaml
    name: AgX Log
    description: |
      Log Encoding with Chroma inset and rotation of primaries, and with 25 Stops of Dynamic Range
    from_scene_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: ACES2065-1, dst: Luminance Compensation Rec.2020}
        # Rec.2020 generated parameters rotate = [2.13976149, -1.22827335, -3.05174246], inset = [0.32965205, 0.28051336, 0.12475368]
        - !<MatrixTransform> {matrix: [0.856627153315983, 0.0951212405381588, 0.0482516061458583, 0, 0.137318972929847, 0.761241990602591, 0.101439036467562, 0, 0.11189821299995, 0.0767994186031903, 0.811302368396859, 0, 0, 0, 1]}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
```
`config.ocio:243–253`（`AgX Base sRGB`，反向定义）：
```yaml
  - !<ColorSpace>
    name: AgX Base sRGB
    from_display_reference: !<GroupTransform>
      children:
        - !<ColorSpaceTransform> {src: cie_xyz_d65_interchange, dst: Linear FilmLight E-Gamut}
        - !<AllocationTransform> {allocation: lg2, vars: [-12.47393, 12.5260688117]}
        - !<FileTransform> {src: AgX_Base_sRGB.cube, interpolation: tetrahedral}
        - !<ColorSpaceTransform> {src: Rec.1886, dst: sRGB}
```
`config.ocio:1343–1351`（look 在 **AgX Log** 域）：
```yaml
  - !<Look>
    name: AgX - Punchy
    process_space: AgX Log
    description: A darkening punchy look
    transform: !<GroupTransform>
      children:
        - !<GradingToneTransform>
         shadows: {rgb: [0.2, 0.2, 0.2], master: 0.35, start: 0.4, pivot: 0.1}
        - !<CDLTransform> {power: [1.0912, 1.0912, 1.0912]}
```
【源码事实】AgX 的 "sigmoid + primaries inset/outset" 被**烘焙进 `AgX_Base_*.cube`**，
其输出编码为 **power 2.4**（`luts/AgX_Base_sRGB.cube:4`：`# But the end image formation will be from -10 to 6.5 encoded in power 2.4`），
最终 sRGB 分段 OETF 由 display colorspace 施加。
**这对本课题的意义：业界把"基础显示变换"本身实现为 display-referred 3D LUT，是常见且成熟的做法；
用户风格 LUT 与它串联时，基础在前、风格在后。**

【未找到】Blender 官方手册/源码中**没有**关于"用户 display-referred 3D LUT 应放在 AgX 之前还是之后"的说明
（子代理对 Blender 手册全文 grep `3d lut|\.cube|color lookup|lut file|apply.*lut` 仅命中 `cube_project` 等无关词）。

---

## Q4. `.cube` 的 DOMAIN_MIN / DOMAIN_MAX 实际怎么用？是否真被当作"输入域标注"？

### 结论

**它确实被当作"输入数值范围"（input value range）标注，但各家实现并不一致；没有任何一家把它当作 TRC 或色彩空间标注：**

| 实现 | 对 DOMAIN_MIN/MAX 的处理 |
|---|---|
| **libplacebo** | 解析后**仿射归一化**表值到 [0,1]，之后丢弃 domain |
| **OpenColorIO** | 转成一个 **MinMax 算子**插在 LUT 前（反向时在 LUT 后） |
| **PhotonCamera** | 解析时**归一化**到 [0,1]，`LutConfig` 不保留 domain |
| **darktable** | **不做归一化**：只断言必须等于 `0/1`，否则**拒绝整个 `.cube`**（见证据 4） |

**没有任何一个实现把 domain 与 gamma/primaries 关联。**

### 证据 1：libplacebo

`src/shaders/lut.c:91–131`（解析与校验）：
```c
        if (pl_str_eatstart0(&line, "DOMAIN_MIN")) {
            ...
            continue;
        }

        if (pl_str_eatstart0(&line, "DOMAIN_MAX")) {
            ...
            continue;
        }
        ...
    for (int i = 0; i < 3; i++) {
        if (max[i] - min[i] < 1e-6) {
            pl_err(log, "Invalid domain range: [%f, %f]", min[i], max[i]);
            goto error;
        }
    }
```
`src/shaders/lut.c:164–166`（唯一用途）：
```c
            // Rescale to range 0.0 - 1.0
            *data++ = (num - min[c]) / (max[c] - min[c]);
```

### 证据 2：OpenColorIO

`src/OpenColorIO/fileformats/FileFormatIridasCube.cpp:546–563`：
```cpp
    const double dmin[]{ cachedFile->domain_min[0], cachedFile->domain_min[1], cachedFile->domain_min[2] };
    const double dmax[]{ cachedFile->domain_max[0], cachedFile->domain_max[1], cachedFile->domain_max[2] };

    switch (newDir)
    {
    case TRANSFORM_DIR_FORWARD:
    {
        CreateMinMaxOp(ops, dmin, dmax, newDir);
        if(lut1D) { CreateLut1DOp(ops, lut1D, newDir); }
        else if(lut3D) { CreateLut3DOp(ops, lut3D, newDir); }
        break;
    }
```
即 domain 被实现为一个 **min/max（换轴/归一化）算子**，紧接在 LUT 采样之前 / 之后。

同一文件 `:37–41` 是 OCIO 对 `.cube` 格式的**内嵌文档**，逐字给出 domain 的语义：
```
#Default input value range (domain) is 0.0 (black) to 1.0 (white)
#Specify other min/max values to map the cube to any custom input
#range you wish to use, for example if you're working with HDR data
DOMAIN_MIN 0.0 0.0 0.0
DOMAIN_MAX 1.0 1.0 1.0
```

### 证据 3：PhotonCamera

`app/src/main/java/com/hinnka/mycamera/lut/CubeLutParser.kt:148–157`：
```kotlin
    /**
     * 将值标准化到 [0, 1] 范围
     */
    private fun normalizeValue(value: Float, min: Float, max: Float): Float {
        return if (min == 0f && max == 1f) {
            value.coerceIn(0f, 1f)
        } else {
            ((value - min) / (max - min)).coerceIn(0f, 1f)
        }
    }
```
`LutConfig` 最终**不保留 domain 字段**（`lut/LutConfig.kt:18–27`）。

### 证据 4：darktable 反而**拒绝**非 0/1 的 domain（由子代理独立取证，报告 `01-darktable.md`）

`src/iop/lut3d.c:790` 用 `strtod(token[1], NULL)` 取值后**直接丢弃**，只在 `:802` 断言其必须等于 0/1：

```
（子代理逐字引证，见 01-darktable.md 第 3 节）
src/iop/lut3d.c:790   strtod(token[1], NULL)
src/iop/lut3d.c:802   断言必须为 0/1，否则拒绝加载
src/iop/lut3d.c:257   查表输入硬 clip 到 [0,1]
```

【事实】darktable 既不归一化也不接受自定义范围，而是**把"输入域必须是 [0,1]"当作硬约束**。
这与 libplacebo/OCIO/PhotonCamera 的"兼容任意范围"策略相反，但**四者都不把 domain 当域标注使用**。

### 对第 4 问的精确回答

- **是"输入域标注"，但只是数值范围（level）标注**：语义 = "我的表值覆盖 [min,max] 这个输入区间"，
  典型用途是 HDR/超白数据（OCIO 注释原文：`for example if you're working with HDR data`）。
- **不是 TRC 标注，也不是色彩空间标注**：没有任何一个实现把 domain 与 gamma/primaries 关联；
  承载 TRC/gamut 的是**独立的域字段**（libplacebo 的 `pl_color_space color_in/color_out`、
  PhotonCamera 的 `uLutCurve`/`uLutColorSpace`、darktable 的 `application color space`）。
- **规范原文未取得**：Adobe Cube LUT Specification 1.0 的官方 PDF URL 本次全部 404，
  `web.archive.org` 超时，因此**未能引用该规范的逐字原文**；上述结论基于**四个实现**的源码
  （libplacebo、OpenColorIO、PhotonCamera、darktable）与 OCIO 内嵌的 `.cube` 格式文档。
  （注：Adobe **DNG** 规范本次已成功取得并核验，见上文"规范证据"；但 DNG 规范不含 Cube LUT 的 domain 标签。）

---

## Q5. 结论：一个专业 RAW 相机 App 的「基础色彩引擎」最小正确结构

以下结构由上面各项目的证据交叉归纳，每一层都标注了**域**与该层的**项目证据**。
顺序即数据流顺序（自上而下）。

### 第 0 层：RAW 域
- **域**：camera-native scene-linear（CFA 去马赛克后的相机 RGB，白平衡增益已施加）。
- **证据**：PhotonCamera `RawLinearRcdPass` 的输入即为相机 RGB，随后 `rgb = uColorCorrectionMatrix * rgb`
  （`raw/RawLinearRcdPass.kt:263, 273`）。

### 第 1 层：输入变换 / 相机 profile（IDT 等价物）
- **域**：scene-linear，工作空间由相机 profile 决定。
- **内容**：CCM（色彩校正矩阵）→ profile 的色相/饱和度映射（HueSatMap）→ 可选的 profile 增益表。
- **证据（顺序）**：Adobe DNG SDK `dng_render.cpp:1893`（ABCDtoRGB）→ `:1906/:1908`（Hue/Sat map）
  → `:1929`（ProfileGainTableMap）。
- **证据（HueSatMap 在 HSV 域、V 通道可带编码）**：DNG SDK `dng_render.cpp:797–817, 980–997`；
  RawTherapee `rt_dcp_new.cc:2013–2016, 2035–2037`。
- **证据（这一层必须发生在 scene-linear）**：PhotonCamera 的 `uLinearDcpHueSatMap` 在线性 pass
  （`raw/RawLinearRcdPass.kt:162, 277–286`）。

### 第 2 层：基础影调映射（tone mapping / scene→display 的核心）
- **域**：输入 scene-linear（或线性工作空间），输出 **display-linear**。
- **内容**：log 编码 → primaries inset → sigmoid/曲线 → outset。可插拔。
- **证据**：darktable `filmic`/`sigmoid`/`agx` 均在 order 45.0–46.0（`src/common/iop_order.c`），
  注释 `conversion from scene-referred to display referred`；
  PhotonCamera `RawRenderingEngine` 枚举（`raw/RawRenderingEngine.kt:10–70`）每个引擎自带 `workingColorSpace`；
  Blender AgX 的 `AgX Log`（`config.ocio:1241–1268`：inset/rotate 矩阵 + `lg2 [-12.47393, 12.5260688117]` = 25 stops）。
- **关键约束**：这一层**不应包含最终显示 OETF**。
  证据：PhotonCamera 引擎最后的 `uOutputTransform` 只是纯 gamut 矩阵、无传递函数
  （`raw/RawDemosaicProcessor.kt:7544–7552`）；Blender AgX 的 cube 输出是 power 2.4，
  最终 sRGB OETF 由 display colorspace 单独施加（`config.ocio:243–253`）。

### 第 3 层：显示编码（OETF）
- **域**：display-linear → display-encoded。
- **内容**：sRGB 分段 / gamma 2.4 / Rec.1886 / HLG / PQ。
- **证据**：PhotonCamera `PreviewColorShaderModules.kt:8–18`（标准 sRGB 分段）；
  `color/TransferCurve.kt:29–33`；Blender `config.ocio` display colorspace 的 `from_display_reference`。
- **【最强支撑，darktable】显示编码在这一层是独立模块，tone mapper 一律输出线性显示值：**
  - sigmoid 与 AgX 的模块元数据声明 output 为 `linear, RGB, display-referred`（`src/iop/sigmoid.c:207`、`src/iop/agx.c:60`）；
    AgX 在曲线之后还有 `powf(..., curve_gamma)` 做线性化（`src/iop/agx.c:1078–1082`）；
  - filmic rgb 的**代码**同样线性化（`src/iop/filmicrgb.c:1805–1809`，中间灰落 0.1845，`:2184`），
    尽管其元数据自称 "non-linear"——**元数据与代码不一致，属该项目的已知瑕疵**（`01-darktable.md` §4 并列了两者）；
  - **最终显示 OETF/TRC 只由 `colorout` 从输出 ICC profile 施加**：`src/iop/colorout.c:700–703`、`:361–403`。
  - 另：`dt_iop_colorspace_type_t` 只有 `RAW/LAB/RGB/LCH/HSL/JZCZHZ`，**没有 scene/display 枚举**
    （`src/common/colorspaces.h:42–51`）；scene→display 的分界靠"模块 description 字符串 +
    内置 order 表中 tone mapper 与 colorout 的相对位置 + colorout 施加 profile TRC"三者隐式表达。
  - **对本项目的含义：如果 FilmKit 把 OETF 与影调曲线揉在同一层、且用纯 2.2 幂，
    它同时违反了两条业界惯例——OETF 是独立层，且应当是明确声明、与 LUT 构建域一致的 TRC。**

> **对本项目症状的直接含义**：若 FilmKit 在这一层用**纯 2.2 幂**，而 LUT 是按 **sRGB 分段**构建的，
> 则两者在中间调几乎一致（<1%），但**阴影区差异巨大**（线性 0.005 处 +47.5%，0.001 处 +235%；
> 数值为本次自算，见 `06-photoncamera-baseline-engine.md` §6）。
> 这正好会削弱"压暗阴影、提高对比/饱和"型风格 LUT 的效果，表现为**"更淡、更灰"**。

### 第 4 层：风格 3D LUT（display-referred）
- **域**：display-encoded（与第 3 层输出同域），且在**非线性显示域**混合。
- **顺序证据**：darktable 手册 `lut-3D.md:42` `placed after the filmic rgb module ... should be applied to a neutral image`；
  PhotonCamera `LutImageProcessor.kt:3106, 3126`（"在色彩配方之后"、"在非线性 sRGB 空间混合"）；
  libplacebo `renderer.h:239–248`（`PL_LUT_NATIVE` = "gamma light"）。
- **承载域标注**：LUT 必须携带并校验其输入域（TRC + gamut）。**不要**用 `.cube` 的 DOMAIN_MIN/MAX 承担这件事
  （Q4 已证明它只是数值范围）。可选做法：
  - 侧车元数据 / 私有头（PhotonCamera 的 `.plut`：`lut/LutParser.kt:20–37` 在 version≥2 存 curve、≥3 存 gamut）；
  - 运行时参数（darktable 的 `application color space`，`src/iop/lut3d.c:55–63, 75`）；
  - 显式 color space 结构（libplacebo 的 `pl_color_space color_in/color_out`，`renderer.h:239–248`）。
- **混合域**：应在**显示编码域**做 `mix(display, lut_out, intensity)`，与 PhotonCamera 一致
  （`LutImageProcessor.kt:3126–3130`；`PreviewColorShader.kt:328`）。

### 可选第 5 层：look / 分级
- **域**：与实现域一致（log 域或显示域）。
- **证据**：Blender 的 `AgX - Punchy` 明确 `process_space: AgX Log`（`config.ocio:1344–1351`）——
  即 **look 可以在 log 域**；而 PhotonCamera 的 ColorRecipe（三向调色、LCH mixer、vibrance）
  在显示编码域、LUT 之前（`lut/PreviewColorShader.kt:184–281`）。

### 一句话的最小正确结构

```
camera RGB (scene-linear)
  → [CCM → HueSatMap → ProfileGainTableMap]        scene-linear，相机 profile 引擎
  → tone mapping (log encode → primaries inset → sigmoid → outset)
                                                   scene-linear → display-linear，不含 OETF
  → OETF (sRGB 分段 或 明确声明的 TRC)              display-linear → display-encoded
  → 风格 3D LUT（域=sRGB/Rec.709 显示编码，域必须显式声明）   display-encoded，非线性域混合
  → [可选 look/分级：log 域或显示域]
```

**两条最容易踩的坑（均由证据支撑）：**
1. **位置必须匹配 LUT 的构建域**——display-referred 的风格 LUT 放在显示变换**之后**
   （darktable `lut-3D.md:42`；PhotonCamera `LutImageProcessor.kt:3106/3126`）；
   scene-referred / camera-log 的 LUT 放在显示变换**之前**（ACES 的 LMT、darktable 的相机 log LUT、
   ART 默认的 film simulation）。libplacebo 同时提供 `params->lut`（前）与 `target->lut`（后）两个插槽，
   正是为了让**域**而不是惯例决定位置。**最危险的做法是照搬位置却不匹配域。**
2. **不要让基础 OETF 与 LUT 的构建 TRC 不一致**——这是"同一个 LUT 在不同 App 上浓淡不同"的
   头号机制（darktable 手册：`Cube files are usually related to Rec. 709 while most others are related to sRGB`）。

---

## 附：规范证据（本次自行下载并逐字核验）

**Adobe DNG Specification 1.7.1.0（126 页）**：
`https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf`
（本次实测 HTTP 200，`application/pdf`，PDF 1.7，126 页；用 `pypdf 6.19.0` 提取全文后逐字 grep，页码为 PDF 实际页）

**（1）LookTable 必须晚于曝光补偿、早于 tone curve —— p.57：**
```
However, it should be applied later in the processing pipe, after any exposure compensation and/or fill light stages, but before any tone curve stage.
```

**（2）ToneCurve 定义在 linear gamma —— p.51：**
```
This tag contains a default tone curve that can be applied while processing the image as a starting point for user adjustments. The curve is specified as a list of 32-bit IEEE floating-point value pairs in linear gamma.
```

**（3）HueSatMap 与 LookTable 各有独立的"索引编码"标签 —— p.64 / p.63：**
```
ProfileHueSatMapEncoding Tag 51107 (C7A3.H) ...
Description Provides a way for color profiles to specify how indexing into a 3D HueSatMap is performed during raw conversion.
```
```
ProfileLookTableEncoding Tag 51108 (C7A4.H) ...
Description Provides a way for color profiles to specify how indexing into a 3D LookTable is performed during raw conversion.
```

**（4）HDR profile 的通用施加方法（编码 → 施加 → 解码）—— p.94–95：**
```
For each of the above tags, the general method for applying them to HDR data is: • Apply an encoding function 𝑓(𝑥) to each color component. • Apply the tag normally (same as in SDR mode), but without clipping ...
```

**（5）最直接的一条：LUT 查找坐标的编码是规范规定的独立步骤 —— p.79（RGBTables 的 3D table 步骤）：**
```
6. Apply GammaEncoding curve.
7. Apply 3D table.
8. Apply inverse of Step 6 (undo GammaEncoding curve).
```
【结论】规范层面明确区分**数据载体空间**与**查找坐标编码**：3D table 不是直接吃管线里的线性值，
而是先用 `GammaEncoding` 曲线编码成查找坐标、查表、再解码回来。
这与 Q2 中 RawTherapee/ART 的"载体线性、查找坐标 sRGB gamma"完全一致，
也是 PhotonCamera `applyLutCurve` / darktable `application color space` 的规范依据。

**（6）关于 DOMAIN_MIN/MAX**：DNG 规范中**没有** `.cube` 的 DOMAIN_MIN/DOMAIN_MAX 概念
（它是 Adobe **Cube LUT** 规范的标签，与该 DNG 规范是两套文档）。
Adobe Cube LUT Specification 1.0 的官方 PDF URL 本次全部 404，**规范原文未能取得**（见下）。

---

## 附：明确「未找到 / 受限」清单

1. **Adobe Cube LUT Specification 1.0 原文未取得**。`wwwimages2.adobe.com`、`www.adobe.com` 相关 URL 均 404；
   `web.archive.org`、`html.duckduckgo.com`、`docs.acescentral.com` 连接超时。Q4 结论仅基于实现源码。
2. **ACES Central（docs.acescentral.com）本次连接超时**，未取得 ACES 官方站点的表述。
   但已取得**足以回答本课题的 ACES 一等证据**（均为本次自行读取的 OCIO 官方文档/源码，见 Q1 证据 5）：
   `docs/configurations/aces_1.0.3.rst:87–90, 104, 188, 196–206`（Input Transforms / Output Transforms / Look 的位置与空间）；
   `docs/guides/using_ocio/tool_overview.rst:278–289`（LMT 接口固定为 ACES2065-1）；
   `docs/guides/authoring/looks.rst:54–60`（`process_space: ACES2065-1`）；
   `src/OpenColorIO/transforms/builtins/ACES.cpp:144, 164, 179, 203`（RRT / ODT shaper 分离）。
   仍**未取得**的是 ACES 官方对 ACES 2.0 与 1.x 差异的规范级表述。
3. **mpv 侧无独立 `vf=lut3d`**：`filters/` 目录（GitHub API 2026-09）内不存在 `f_lut3d.c`；
   `DOCS/man/vf.rst` 与 `DOCS/man/options.rst` 中 grep `lut3d` 无命中。用户 3D LUT 在 mpv 上的承载路径
   **未能定位**；仅确认 `--icc-3dlut-size`（`options.rst:7939`）用于 ICC 色彩管理。
4. **Blender 官方未说明用户 LUT 与 AgX 的相对位置**（子代理对 Blender 手册全文 grep 后明确标注未找到）。
5. **子代理 10067815（Blender AgX）在收尾阶段失败**，但其 76KB 证据报告已完整落盘为 `03-blender-agx.md`，
   本汇总引用的 Blender 结论均已在该文件中给出 `文件:行号` 证据。
6. darktable 手册线上 URL（`docs.darktable.org/usermanual/{4.6,stable}/...`）在当前站点结构下返回 404/307，
   本汇总改用**手册源仓库** `darktable-org/dtdocs` 的 `content/module-reference/processing-modules/lut-3D.md`
   （commit `79c10958aee76654dcac55a7c5e645a05621657b`），原文可逐字核验。
7. **RawTherapee / ART 已完成**（子代理 82e50b9d，报告 `02-rawtherapee-art.md`，1261 行，含 DNG 规范页码引用；
   commit：RawTherapee `6c4cb5964fcea91dd9bdef619d4825712e083cb8`、ART `3bf5873c476cb1a12da10d3f59a829d64f3d1b1b`）。
   **子代理 1（darktable）亦已完成**（报告 `01-darktable.md`，2901 行；其独立结论与本汇总的 darktable 部分一致，
   并补充了 `lut3d.c` 对 domain 的拒绝行为与 `colorout` 的 OETF 归属）。
   **dcamprof 由本次自行取证完成**（Q3，README + `www/camera-profiling.html`，均 HTTP 200）。
   **仍未完成**：filmulator / rawproc / vkBasalt 的独立取证（子代理 d8342d5e、e627967c 仍在运行）；
   本汇总的 Q1–Q5 结论均**不依赖**这些未完成项（ACES 与 libplacebo 部分已由本次自行取证补齐，见 Q1 证据 5 与 Q2）。
8. **ART 的 `after_tone_curve` 与 RT 的默认位置不同**（RT 在 tone curve 之后；ART 默认在其之前），
   这是两个项目之间的**真实设计分歧**，本汇总如实并列，不做统一化处理。
