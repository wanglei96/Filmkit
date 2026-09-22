# darktable 色彩管线源码取证报告（sigmoid / filmic rgb / AgX / LUT 3D / 域边界）

取证方式：只读本地源码 + 官方文档仓库。每条结论均附 `文件:行号` 与逐字原文；找不到的条目明确标注
"源码/文档中未找到"。代码/标识符一律保留原文，不翻译。

---

## 0. 取证范围与版本

### 0.1 darktable 源码 commit

```
$ git -C /private/tmp/dtsrc log -1 --format=%H
92f6c282845943ad8f242c3dcb040a3c75c877b9
```

```
$ git -C /private/tmp/dtsrc log -1 --format='%cd %s' --date=iso
2026-09-22 07:53:33 +0200 satcurvergb: Fix compilation when OpenCL is not available.
```

`/private/tmp/dtsrc/.git` 是 **shallow clone（depth=1，无 tag）**：

```
$ git -C /private/tmp/dtsrc rev-list --count HEAD
1
$ git -C /private/tmp/dtsrc branch -a
* master
  remotes/origin/HEAD -> origin/master
  remotes/origin/master
$ git -C /private/tmp/dtsrc tag
（无输出）
$ git -C /private/tmp/dtsrc status --porcelain
（无输出）
```

工作树干净、无本地改动。远端为官方仓库：

```
$ git -C /private/tmp/dtsrc remote -v
origin	https://github.com/darktable-org/darktable.git (fetch) [blob:none]
origin	https://github.com/darktable-org/darktable.git (push)
```

**版本号无法从本 clone 推导**（`darktable_package_string` 由构建时 `version_gen.c` 生成，
shallow clone 无 tag）。可用的版本线索：

```
README.md:153
### Latest release
```

```
README.md:155
5.6.1 (stable)
```

本树包含 `src/iop/agx.c`、`spektrafilm`、`satcurvergb`、`contrastntexture`、`v50_order`
等条目，即 **5.6.1 之后的 master**。已核对上游 master 的 `src/common/iop_order.c` 与本树**逐字一致**
（见 §4.3 的说明），故本树不是被改过的 fork。

模块接口版本：

```
src/common/darktable.h:203
#define DT_MODULE_VERSION 25 // version of dt's module interface
```

```
src/common/iop_order.c:31
#define DT_IOP_ORDER_VERSION 5
```

### 0.2 本次查看的文件清单（全部位于 /private/tmp/dtsrc）

| 路径 | 行数 |
|---|---|
| `src/iop/sigmoid.c` | 982 |
| `src/iop/filmicrgb.c` | 4735 |
| `src/iop/agx.c` | 2866 |
| `src/iop/lut3d.c` | 1866 |
| `src/iop/colorout.c` | 889 |
| `src/iop/basecurve.c` | （仅取 description / default_colorspace） |
| `src/common/iop_order.c` | 2818 |
| `src/common/iop_order.h` | |
| `src/common/iop_profile.h` | |
| `src/common/iop_profile.c` | |
| `src/common/colorspaces.h` | |
| `src/common/math.h` | |
| `src/develop/imageop.h` | |
| `src/develop/format.c` | 101 |
| `src/develop/pixelpipe_hb.c` | |
| `src/iop/iop_api.h` | |
| `src/common/module_api.h` | |
| `data/kernels/sigmoid.cl` | |
| `data/kernels/lut3d.cl` | |
| `data/kernels/agx.cl` | |
| `src/tests/benchmark/*.xmp` | |

文档仓库（另 clone）：

```
$ git -C /private/tmp/dtdocs log -1 --format='%H %ad' --date=iso
79c10958aee76654dcac55a7c5e645a05621657b 2026-09-22 00:15:50 +0200
```

---

## 1. sigmoid 模块

### 1.1 声明与元数据

```
src/iop/sigmoid.c:34
DT_MODULE_INTROSPECTION(3, dt_iop_sigmoid_params_t)
```

```
src/iop/sigmoid.c:37
#define MIDDLE_GREY 0.1845f
```

```
src/iop/sigmoid.c:200-208
const char **description(dt_iop_module_t *self)
{
  return dt_iop_set_description(self,
                                _("apply a view transform to make an image displayable\n"
                                  "on a screen or print using a robust and smooth\n"
                                  "tone curve with optional color preservation methods"),
                                _("corrective and creative"), _("linear, RGB, scene-referred"),
                                _("non-linear, RGB"), _("linear, RGB, display-referred"));
}
```

`dt_iop_set_description` 的 5 个参数依次是 description / purpose / input / processing / output，
即：**输入 "linear, RGB, scene-referred"，输出 "linear, RGB, display-referred"**。

```
src/iop/sigmoid.c:220-225
dt_iop_colorspace_type_t default_colorspace(dt_iop_module_t *self,
                                            dt_dev_pixelpipe_t *pipe,
                                            dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_RGB;
}
```

### 1.2 参数（skew / target white / target black / contrast / hue preservation / per-channel vs RGB ratio / primaries）

```
src/iop/sigmoid.c:40-45
typedef enum dt_iop_sigmoid_methods_type_t
{
  DT_SIGMOID_METHOD_PER_CHANNEL = 0, // $DESCRIPTION: "per channel"
  DT_SIGMOID_METHOD_RGB_RATIO = 1,   // $DESCRIPTION: "RGB ratio"
} dt_iop_sigmoid_methods_type_t;
```

```
src/iop/sigmoid.c:47-53
typedef enum dt_iop_sigmoid_base_primaries_t
{
  DT_SIGMOID_WORK_PROFILE = 0, // $DESCRIPTION: "working profile"
  DT_SIGMOID_REC2020 = 1, // $DESCRIPTION: "Rec2020"
  DT_SIGMOID_DISPLAY_P3 = 2, // $DESCRIPTION: "Display P3"
  DT_SIGMOID_ADOBE_RGB = 3, // $DESCRIPTION: "Adobe RGB (compatible)"
  DT_SIGMOID_SRGB = 4, // $DESCRIPTION: "sRGB"
} dt_iop_sigmoid_base_primaries_t;
```

```
src/iop/sigmoid.c:57-69
typedef struct dt_iop_sigmoid_params_t
{
  float middle_grey_contrast; // $MIN: 0.1  $MAX: 10.0 $DEFAULT: 1.5 $DESCRIPTION: "contrast"
  float contrast_skewness;    // $MIN: -1.0 $MAX: 1.0 $DEFAULT: 0.0 $DESCRIPTION: "skew"
  float display_white_target; // $MIN: 20.0  $MAX: 1600.0 $DEFAULT: 100.0 $DESCRIPTION: "target white"
  float display_black_target; // $MIN: 0.0  $MAX: 15.0 $DEFAULT: 0.0152 $DESCRIPTION: "target black"
  dt_iop_sigmoid_methods_type_t color_processing; // $DEFAULT: DT_SIGMOID_METHOD_PER_CHANNEL $DESCRIPTION: "color processing"
  float hue_preservation; // $MIN: 0.0 $MAX: 100.0 $DEFAULT: 100.0 $DESCRIPTION: "preserve hue"
  float red_inset;        // $MIN:  0.0  $MAX: 0.99 $DEFAULT: 0.0 $DESCRIPTION: "red attenuation"
  float red_rotation;     // $MIN: -0.4  $MAX: 0.4  $DEFAULT: 0.0 $DESCRIPTION: "red rotation"
  float green_inset;      // $MIN:  0.0  $MAX: 0.99 $DEFAULT: 0.0 $DESCRIPTION: "green attenuation"
  float green_rotation;   // $MIN: -0.4  $MAX: 0.4  $DEFAULT: 0.0 $DESCRIPTION: "green rotation"
  float blue_inset;       // $MIN:  0.0  $MAX: 0.99 $DEFAULT: 0.0 $DESCRIPTION: "blue attenuation"
  float blue_rotation;    // $MIN: -0.4  $MAX: 0.4  $DEFAULT: 0.0 $DESCRIPTION: "blue rotation"
  float purity;           // $MIN:  0.0  $MAX: 1.0  $DEFAULT: 0.0 $DESCRIPTION: "recover purity"
  dt_iop_sigmoid_base_primaries_t base_primaries; // $DEFAULT: DT_SIGMOID_WORK_PROFILE $DESCRIPTION: "base primaries"
} dt_iop_sigmoid_params_t;
```

注意：`target white` / `target black` 单位是 **百分比**（`$MAX: 1600.0` / `$MAX: 15.0`），
在 `commit_params` 里乘 0.01 变成线性显示值（见 1.4）。

### 1.3 曲线本身：generalized log-logistic

```
src/iop/sigmoid.c:298-316
// Declared here as it is used in the commit params function
DT_OMP_DECLARE_SIMD(uniform(magnitude, paper_exp, film_fog, film_power, paper_power))
static inline float _generalized_loglogistic_sigmoid(const float value,
                                                     const float magnitude,
                                                     const float paper_exp,
                                                     const float film_fog,
                                                     const float film_power,
                                                     const float paper_power)
{
  const float clamped_value = fmaxf(value, 0.0f);
  // The following equation can be derived as a model for film + paper but it has a pole at 0
  // magnitude * powf(1.0f + paper_exp * powf(film_fog + value, -film_power), -paper_power);
  // Rewritten on a stable around zero form:
  const float film_response = powf(film_fog + clamped_value, film_power);
  const float paper_response = magnitude * powf(film_response / (paper_exp + film_response), paper_power);

  // Safety check for very large floats that cause numerical errors
  return dt_isnan(paper_response) ? magnitude : paper_response;
}
```

### 1.4 commit_params：参数如何被求解（skew / contrast / target white / target black）

```
src/iop/sigmoid.c:318-330
void commit_params(dt_iop_module_t *self,
                   dt_iop_params_t *p1,
                   dt_dev_pixelpipe_t *pipe,
                   dt_dev_pixelpipe_iop_t *piece)
{
  const dt_iop_sigmoid_params_t *params = (dt_iop_sigmoid_params_t *)p1;
  dt_iop_sigmoid_data_t *module_data = piece->data;
  /* Calculate actual skew log logistic parameters to fulfill the following:
   * f(scene_zero) = display_black_target
   * f(scene_grey) = MIDDLE_GREY
   * f(scene_inf)  = display_white_target
   * Slope at scene_grey independent of skewness i.e. only changed by the contrast parameter.
   */
```

```
src/iop/sigmoid.c:347-348
  // Add skew
  module_data->paper_power = powf(5.0f, -params->contrast_skewness);
```

```
src/iop/sigmoid.c:363-379
  // Figure out what film power fulfills the target slope
  // (linear when assuming display_black = 0.0)
  module_data->film_power = ref_slope / temp_slope;

  // Calculate the other parameters now that both film and paper power is known
  module_data->white_target = 0.01f * params->display_white_target;
  module_data->black_target = 0.01f * params->display_black_target;
  const float white_grey_relation
      = powf(module_data->white_target / MIDDLE_GREY, 1.0f / module_data->paper_power) - 1.0f;
  const float white_black_relation
      = powf(module_data->black_target / module_data->white_target, -1.0f / module_data->paper_power) - 1.0f;

  module_data->film_fog = MIDDLE_GREY * powf(white_grey_relation, 1.0f / module_data->film_power)
                          / (powf(white_black_relation, 1.0f / module_data->film_power)
                             - powf(white_grey_relation, 1.0f / module_data->film_power));
  module_data->paper_exposure
      = powf(module_data->film_fog + MIDDLE_GREY, module_data->film_power) * white_grey_relation;
```

```
src/iop/sigmoid.c:381-391
  module_data->color_processing = params->color_processing;
  module_data->hue_preservation = fminf(fmaxf(0.01f * params->hue_preservation, 0.0f), 1.0f);

  module_data->purity = params->purity;
  module_data->inset[0] = params->red_inset;
  module_data->inset[1] = params->green_inset;
  module_data->inset[2] = params->blue_inset;
  module_data->rotation[0] = params->red_rotation;
  module_data->rotation[1] = params->green_rotation;
  module_data->rotation[2] = params->blue_rotation;
  module_data->base_primaries = params->base_primaries;
```

数据结构（进 GPU 的常数）：

```
src/iop/sigmoid.c:160-174
typedef struct dt_iop_sigmoid_data_t
{
  float white_target;
  float black_target;
  float paper_exposure;
  float film_fog;
  float film_power;
  float paper_power;
  dt_iop_sigmoid_methods_type_t color_processing;
  float hue_preservation;
  float inset[3];
  float rotation[3];
  float purity;
  dt_iop_sigmoid_base_primaries_t base_primaries;
} dt_iop_sigmoid_data_t;
```

### 1.5 per-channel vs rgb-ratio 两条实现路径

分派：

```
src/iop/sigmoid.c:774-782
  if(module_data->color_processing == DT_SIGMOID_METHOD_PER_CHANNEL)
  {
    process_loglogistic_per_channel(self->dev, piece, ivoid, ovoid, roi_in, roi_out);
  }
  else // DT_SIGMOID_METHOD_RGB_RATIO
  {
    process_loglogistic_rgb_ratio(piece, ivoid, ovoid, roi_in, roi_out);
  }
```

**RGB ratio**（在 luma 上做曲线，再按比例缩放 RGB，然后做 hyperbolic gamut compression）：

```
src/iop/sigmoid.c:592-605
    // Force negative values to zero
    _desaturate_negative_values(pix_in, pix_in_strict_positive);

    // Preserve color ratios by applying the tone curve on a luma estimate and then scale the RGB tripplet uniformly
    const float luma = (pix_in_strict_positive[0] + pix_in_strict_positive[1] + pix_in_strict_positive[2]) / 3.0f;
    const float mapped_luma
        = _generalized_loglogistic_sigmoid(luma, white_target, paper_exp, film_fog, contrast_power, skew_power);

    if(luma > 1e-9)
    {
      const float scaling_factor = mapped_luma / luma;
      for_each_channel(c, aligned(pix_in_strict_positive, pix_out))
      {
        pre_out[c] = scaling_factor * pix_in_strict_positive[c];
      }
    }
```

```
src/iop/sigmoid.c:634-649
    // Hyperbolic gamut compression
    // Small chroma values, i.e., colors close to the acromatic axis are preserved while large chroma values are
    // compressed.

    const float pixel_chroma_adjustment = 1.0f / (chroma_vs_mapping_border * display_border_vs_chroma + epsilon);
    const float hyperbolic_chroma = 2.0f * chroma_vs_mapping_border
                                    / (1.0f - chroma_vs_mapping_border * chroma_vs_mapping_border + epsilon)
                                    * pixel_chroma_adjustment;

    const float hyperbolic_z = sqrtf(hyperbolic_chroma * hyperbolic_chroma + 1.0f);
    const float chroma_factor = hyperbolic_chroma / (1.0f + hyperbolic_z) * display_border_vs_chroma;

    for_each_channel(c, aligned(pre_out, pix_out))
    {
      pix_out[c] = mapped_luma + chroma_factor * (pre_out[c] - mapped_luma);
    }
```

**per-channel**（先按 inset/rotation 生成 "rendering" 基色 → 逐通道曲线 → hue 修正 → 变换回管线空间）：

```
src/iop/sigmoid.c:722-756
  const dt_iop_order_iccprofile_info_t *pipe_work_profile = dt_ioppr_get_pipe_work_profile_info(piece->pipe);
  const dt_iop_order_iccprofile_info_t *base_profile = _get_base_profile(dev, pipe_work_profile, module_data->base_primaries);
  dt_colormatrix_t pipe_to_base, base_to_rendering, rendering_to_pipe;
  _calculate_adjusted_primaries(module_data, pipe_work_profile, base_profile, pipe_to_base, base_to_rendering, rendering_to_pipe);
...
    // Convert to "base primaries"
    dt_apply_transposed_color_matrix(pix_in, pipe_to_base, pix_in_base);

    // Force negative values to zero
    _desaturate_negative_values(pix_in_base, pix_in_strict_positive);

    dt_aligned_pixel_t rendering_RGB;
    dt_apply_transposed_color_matrix(pix_in_strict_positive, base_to_rendering, rendering_RGB);

    for_each_channel(c, aligned(rendering_RGB, per_channel))
    {
      per_channel[c] = _generalized_loglogistic_sigmoid(rendering_RGB[c], white_target, paper_exp, film_fog,
                                                        contrast_power, skew_power);
    }

    // Hue correction by scaling the middle value relative to the max and min values.
    dt_iop_sigmoid_value_order_t pixel_value_order;
    dt_aligned_pixel_t per_channel_hue_corrected;
    _pixel_channel_order(rendering_RGB, &pixel_value_order);
    _preserve_hue_and_energy(rendering_RGB, per_channel, per_channel_hue_corrected, pixel_value_order,
                             hue_preservation);
    dt_apply_transposed_color_matrix(per_channel_hue_corrected, rendering_to_pipe, pix_out);
```

inset / rotation / purity 矩阵构造：

```
src/iop/sigmoid.c:401-413
  // Make adjusted primaries for generating the inset matrix
  //
  // References:
  // AgX by Troy Sobotka - https://github.com/sobotka/AgX-S2O3
  // Related discussions on Blender Artists forums -
  // https://blenderartists.org/t/feedback-development-filmic-baby-step-to-a-v2/1361663
  //
  // The idea is to "inset" the work RGB data toward achromatic
  // along spectral lines before per-channel curves. This makes
  // handling of bright, saturated colors much better as the
  // per-channel process desaturates them.
  // The primaries are also rotated to compensate for Abney etc.
  // and achieve a favourable shift towards yellow.
```

```
src/iop/sigmoid.c:444-468
  // Rotated, scaled primaries are calculated based on the "base profile"
  float custom_primaries[3][2];
  for(size_t i = 0; i < 3; i++)
    dt_rotate_and_scale_primary(base_profile, 1.f - module_data->inset[i], module_data->rotation[i], i,
                                custom_primaries[i]);

  dt_colormatrix_t custom_to_XYZ;
  dt_make_transposed_matrices_from_primaries_and_whitepoint(custom_primaries, base_profile->whitepoint,
                                                            custom_to_XYZ);
  dt_colormatrix_mul(base_to_rendering, custom_to_XYZ, base_profile->matrix_out_transposed);

  for(size_t i = 0; i < 3; i++)
  {
    const float scaling = 1.f - module_data->purity * module_data->inset[i];
    dt_rotate_and_scale_primary(base_profile, scaling, module_data->rotation[i], i, custom_primaries[i]);
  }

  dt_make_transposed_matrices_from_primaries_and_whitepoint(custom_primaries, base_profile->whitepoint,
                                                            custom_to_XYZ);
  dt_colormatrix_t tmp;
  dt_colormatrix_mul(tmp, custom_to_XYZ, base_profile->matrix_out_transposed);
  dt_colormatrix_t rendering_to_base;
  mat3SSEinv(rendering_to_base, tmp);
  dt_colormatrix_mul(rendering_to_pipe, rendering_to_base, base_to_pipe);
}
```

即：`inset` / `rotation` 参与入程矩阵 `base_to_rendering`；`purity` 只参与回程矩阵 `rendering_to_pipe`。

hue preservation 的中间通道修正 + 能量守恒：

```
src/iop/sigmoid.c:656-670
// Linear interpolation of hue that also preserve sum of channels
// Assumes hue_preservation strictly in range [0, 1]
static inline void _preserve_hue_and_energy(const dt_aligned_pixel_t pix_in,
                                            const dt_aligned_pixel_t per_channel,
                                            dt_aligned_pixel_t pix_out,
                                            const dt_iop_sigmoid_value_order_t order,
                                            const float hue_preservation)
{
  // Naive Hue correction of the middle channel
  const float chroma = pix_in[order.max] - pix_in[order.min];
  const float midscale = chroma != 0.f ? (pix_in[order.mid] - pix_in[order.min]) / chroma : 0.f;
  const float full_hue_correction
      = per_channel[order.min] + (per_channel[order.max] - per_channel[order.min]) * midscale;
  const float naive_hue_mid
      = (1.0f - hue_preservation) * per_channel[order.mid] + hue_preservation * full_hue_correction;
```

```
src/iop/sigmoid.c:678-679
  // Preserve hue constrained to maintain the same energy as the per channel result
  if(naive_hue_mid <= per_channel[order.mid])
```

### 1.6 OpenCL / GPU kernel

```
src/iop/sigmoid.c:184-187
typedef struct dt_iop_sigmoid_global_data_t
{
  int kernel_sigmoid_loglogistic_per_channel;
  int kernel_sigmoid_loglogistic_rgb_ratio;
} dt_iop_sigmoid_global_data_t;
```

```
src/iop/sigmoid.c:846-847
  gd->kernel_sigmoid_loglogistic_per_channel = dt_opencl_create_kernel(program, "sigmoid_loglogistic_per_channel");
  gd->kernel_sigmoid_loglogistic_rgb_ratio = dt_opencl_create_kernel(program, "sigmoid_loglogistic_rgb_ratio");
```

Kernel 文件：`data/kernels/sigmoid.cl`（程序号在 `src/iop/sigmoid.c` 的 `init_global` 中给出）。

```
data/kernels/sigmoid.cl:177-215
sigmoid_loglogistic_per_channel (read_only image2d_t in,
                                 write_only image2d_t out,
                                 const int width,
                                 const int height,
                                 const float white_target,
                                 const float paper_exp,
                                 const float film_fog,
                                 const float contrast_power,
                                 const float skew_power,
                                 const float hue_preservation,
                                 constant const float *const pipe_to_base,
                                 constant const float *const base_to_rendering,
                                 constant const float *const rendering_to_pipe)
{
  const unsigned int x = get_global_id(0);
  const unsigned int y = get_global_id(1);

  if(x >= width || y >= height) return;

  float4 i = Areadpixel(in, x, y);
  float alpha = i.w;

  i = matrix_product_float4(i, pipe_to_base);

  // Force negative values to zero
  i = _desaturate_negative_values(i);

  // Convert to rendering primaries
  i = matrix_product_float4(i, base_to_rendering);
  float pix_array[3] = {i.x, i.y, i.z};

  i = _generalized_loglogistic_sigmoid_vector(i, white_target, paper_exp, film_fog, contrast_power, skew_power);
```

```
data/kernels/sigmoid.cl:226-236
sigmoid_loglogistic_rgb_ratio(read_only image2d_t in,
                              write_only image2d_t out,
                              const int width,
                              const int height,
                              const float white_target,
                              const float black_target,
                              const float paper_exp,
                              const float film_fog,
                              const float contrast_power,
                              const float skew_power)
{
```

### 1.7 sigmoid 在管线中的默认位置

本版本**模块不再声明 order**（见 §4.3.1），位置完全由 `src/common/iop_order.c` 的内置表决定。
默认表 = `v50_order`（`DT_DEFAULT_IOP_ORDER_RAW = DT_IOP_ORDER_V50`）：

```
src/common/iop_order.h:139-140
#define DT_DEFAULT_IOP_ORDER_RAW DT_IOP_ORDER_V50
#define DT_DEFAULT_IOP_ORDER_JPG DT_IOP_ORDER_V50_JPG
```

`v50_order` 中 tone mapping 段落逐字：

```
src/common/iop_order.c:384-394
  { {43.0f }, "rgblevels", 0},       // same
  { {44.0f }, "basecurve", 0},       // conversion from scene-referred to display referred, reverse-engineered
                                  //    on camera JPEG default look
  { {45.0f }, "filmic", 0},          // same, but different (parametric) approach
  { {45.3f }, "sigmoid", 0},
  { {45.5f }, "agx", 0},
  { {46.0f }, "filmicrgb", 0},       // same, upgraded
  { { 46.7f }, "spektrafilm", 0 },
  { {36.0f }, "lut3d", 0},           // apply a creative style or film emulation, possibly non-linear
  { {47.0f }, "colisa", 0},          // edit contrast while damaging colour
  { {48.0f }, "tonecurve", 0},       // same
```

sigmoid 的预设（`scene-referred (sigmoid)` 工作流自动应用）：

```
src/iop/sigmoid.c:227-232
void init_presets(dt_iop_module_so_t *self)
{
  // auto-applied scene-referred default
  self->pref_based_presets = TRUE;

  const char *workflow = dt_conf_get_string_const("plugins/darkroom/workflow");
  const gboolean auto_apply_sigmoid = strcmp(workflow, "scene-referred (sigmoid)") == 0;
```

---

## 2. filmic rgb 模块

### 2.1 声明与元数据

```
src/iop/filmicrgb.c:67
DT_MODULE_INTROSPECTION(6, dt_iop_filmicrgb_params_t)
```

```
src/iop/filmicrgb.c:340-349
const char **description(dt_iop_module_t *self)
{
  return dt_iop_set_description(self, _("apply a view transform to prepare the scene-referred pipeline\n"
                                        "for display on SDR screens and paper prints\n"
                                        "while preventing clipping in non-destructive ways"),
                                      _("corrective and creative"),
                                      _("linear or non-linear, RGB, scene-referred"),
                                      _("non-linear, RGB"),
                                      _("non-linear, RGB, display-referred"));
}
```

```
src/iop/filmicrgb.c:361-366
dt_iop_colorspace_type_t default_colorspace(dt_iop_module_t *self,
                                            dt_dev_pixelpipe_t *pipe,
                                            dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_RGB;
}
```

### 2.2 参数

```
src/iop/filmicrgb.c:168-200
typedef struct dt_iop_filmicrgb_params_t
{
  float grey_point_source;     // $MIN: 0 $MAX: 100 $DEFAULT: 18.45 $DESCRIPTION: "middle gray luminance"
  float black_point_source;    // $MIN: -16 $MAX: -0.1 $DEFAULT: -8.0 $DESCRIPTION: "black relative exposure"
  float white_point_source;    // $MIN: 0.1 $MAX: 16 $DEFAULT: 4.0 $DESCRIPTION: "white relative exposure"
  float reconstruct_threshold; // $MIN: -6.0 $MAX: 6.0 $DEFAULT: 0.0 $DESCRIPTION: "threshold"
  float reconstruct_feather;   // $MIN: 0.25 $MAX: 6.0 $DEFAULT: 3.0 $DESCRIPTION: "transition"
  float reconstruct_bloom_vs_details; // $MIN: -100.0 $MAX: 100.0 $DEFAULT: 100.0 $DESCRIPTION: "bloom ↔ reconstruct"
  float reconstruct_grey_vs_color; // $MIN: -100.0 $MAX: 100.0 $DEFAULT: 100.0 $DESCRIPTION: "gray ↔ colorful details"
  float reconstruct_structure_vs_texture; // $MIN: -100.0 $MAX: 100.0 $DEFAULT: 0.0 $DESCRIPTION: "structure ↔ texture"
  float security_factor;                  // $MIN: -50 $MAX: 200 $DEFAULT: 0 $DESCRIPTION: "dynamic range scaling"
  float grey_point_target;                // $MIN: 1 $MAX: 50 $DEFAULT: 18.45 $DESCRIPTION: "target middle gray"
  float black_point_target; // $MIN: 0.000 $MAX: 20.000 $DEFAULT: 0.01517634 $DESCRIPTION: "target black luminance"
  float white_point_target; // $MIN: 0 $MAX: 1600 $DEFAULT: 100 $DESCRIPTION: "target white luminance"
  float output_power;       // $MIN: 1 $MAX: 10 $DEFAULT: 4.0 $DESCRIPTION: "hardness"
  float latitude;           // $MIN: 0.01 $MAX: 99 $DEFAULT: 0.01 $DESCRIPTION: "linear region"
  float contrast;           // $MIN: 0 $MAX: 5 $DEFAULT: 1.0
  float saturation;         // $MIN: -200 $MAX: 200 $DEFAULT: 0 $DESCRIPTION: "extreme luminance saturation"
  float balance;            // $MIN: -50 $MAX: 50 $DEFAULT: 0.0 $DESCRIPTION: "shadows ↔ highlights balance"
  float noise_level;        // $MIN: 0.0 $MAX: 6.0 $DEFAULT: 0.2f $DESCRIPTION: "add noise in highlights"
  dt_iop_filmicrgb_methods_type_t preserve_color; // $DEFAULT: DT_FILMIC_METHOD_POWER_NORM $DESCRIPTION: "preserve chrominance"
  dt_iop_filmicrgb_colorscience_type_t version; // $DEFAULT: DT_FILMIC_COLORSCIENCE_V5 $DESCRIPTION: "color science"
  gboolean auto_hardness;                       // $DEFAULT: TRUE $DESCRIPTION: "auto adjust hardness"
  gboolean custom_grey;                         // $DEFAULT: FALSE $DESCRIPTION: "use custom middle-gray values"
  int high_quality_reconstruction;       // $MIN: 0 $MAX: 10 $DEFAULT: 1 $DESCRIPTION: "iterations of high-quality reconstruction"
  dt_iop_filmic_noise_distribution_t noise_distribution; // $DEFAULT: DT_NOISE_GAUSSIAN $DESCRIPTION: "type of noise"
  dt_iop_filmicrgb_curve_type_t shadows; // $DEFAULT: DT_FILMIC_CURVE_POLY_4 $DESCRIPTION: "contrast in shadows"
  dt_iop_filmicrgb_curve_type_t highlights; // $DEFAULT: DT_FILMIC_CURVE_POLY_4 $DESCRIPTION: "contrast in highlights"
  gboolean compensate_icc_black; // $DEFAULT: FALSE $DESCRIPTION: "compensate output ICC profile black point"
  dt_iop_filmicrgb_spline_version_type_t spline_version; // $DEFAULT: DT_FILMIC_SPLINE_VERSION_V3 $DESCRIPTION: "spline handling"
  gboolean enable_highlight_reconstruction; // $DEFAULT: FALSE $DESCRIPTION: "enable highlight reconstruction"
} dt_iop_filmicrgb_params_t;
```

```
src/iop/filmicrgb.c:98-123
typedef enum dt_iop_filmicrgb_methods_type_t
...
  DT_FILMIC_METHOD_POWER_NORM = 3,        // $DESCRIPTION: "RGB power norm"
...
  DT_FILMIC_COLORSCIENCE_V5 = 4, // $DESCRIPTION: "v7 (2023)"
```

### 2.3 算法结构（1）log 编码

```
src/iop/filmicrgb.c:897-903
static inline float log_tonemapping_v2_1ch(const float x,
                                           const float grey,
                                           const float black,
                                           const float dynamic_range)
{
  return clamp_simd((log2f(x / grey) - black) / dynamic_range);
}
```

```
src/iop/filmicrgb.c:927-933
DT_OMP_DECLARE_SIMD(uniform(grey, black, dynamic_range))
static inline float exp_tonemapping_v2(const float x, const float grey, const float black,
                                       const float dynamic_range)
{
  // inverse of log_tonemapping
  return grey * exp2f(dynamic_range * x + black);
}
```

### 2.4 算法结构（2）曲线（spline，多项式 / 有理）

```
src/iop/filmicrgb.c:936-946（节选，函数体续见 948-999）
DT_OMP_DECLARE_SIMD(aligned(M1, M2, M3, M4 : 16) uniform(M1, M2, M3, M4, M5, latitude_min, latitude_max))
static inline float filmic_spline(const float x, const dt_aligned_pixel_t M1, const dt_aligned_pixel_t M2,
                                  const dt_aligned_pixel_t M3, const dt_aligned_pixel_t M4,
                                  const dt_aligned_pixel_t M5, const float latitude_min,
                                  const float latitude_max, const dt_iop_filmicrgb_curve_type_t type[2])
{
  // if type polynomial :
  // y = M5 * x⁴ + M4 * x³ + M3 * x² + M2 * x¹ + M1 * x⁰
  // but we rewrite it using Horner factorisation, to spare ops and enable FMA in available
  // else if type rational :
  // y = M1 * (M2 * (x - x_0)² + (x - x_0)) / (M2 * (x - x_0)² + (x - x_0) + M3)
```

### 2.5 算法结构（3）chroma preservation：norm tone mapping（v4/v5 主路径）

```
src/iop/filmicrgb.c:1775-1814
DT_OMP_DECLARE_SIMD(
  uniform(work_profile, data, spline, norm_min, norm_max, display_black, display_white, type)
  aligned(pix_in, pix_out:16))
static inline void norm_tone_mapping_v4(const dt_aligned_pixel_t pix_in,
                                        dt_aligned_pixel_t pix_out,
                                        const dt_iop_filmicrgb_methods_type_t type,
                                        const dt_iop_order_iccprofile_info_t *const work_profile,
                                        const dt_iop_filmicrgb_data_t *const data,
                                        const dt_iop_filmic_rgb_spline_t spline,
                                        const float norm_min,
                                        const float norm_max,
                                        const float display_black,
                                        const float display_white)
    {
  // Norm must be clamped early to the valid input range, otherwise it will be clamped
  // later in log_tonemapping_v2 and the ratios will be then incorrect.
  // This would result in colorful patches darker than their surrounding in places
  // where the raw data is clipped.
  float norm = CLAMPF(get_pixel_norm(pix_in, type, work_profile), norm_min, norm_max);

  // Save the ratios
  dt_aligned_pixel_t ratios = { 0.0f };
  for_each_channel(c,aligned(pix_in))
    ratios[c] = pix_in[c] / norm;

  // Log tone-mapping
  norm = log_tonemapping_v2_1ch(norm, data->grey_source, data->black_source, data->dynamic_range);

  // Filmic S curve on the max RGB
  // Apply the transfer function of the display
  norm = powf(CLAMP(filmic_spline(norm, spline.M1, spline.M2, spline.M3, spline.M4, spline.M5,
                                        spline.latitude_min, spline.latitude_max, spline.type),
                    display_black,
                    display_white),
              data->output_power);

  // Restore RGB
  for_each_channel(c,aligned(pix_out))
    pix_out[c] = ratios[c] * norm;
}
```

`output_power`（UI 名 "hardness"）就是这里的显示幂指数：

```
src/iop/filmicrgb.c:2571-2581
static inline void _compute_output_power(const dt_iop_module_t *self,
                                         dt_iop_filmicrgb_params_t *p)
{
  const float min = self->so->get_f("output_power")->Float.Min;
  const float max = self->so->get_f("output_power")->Float.Max;

  p->output_power =
    CLAMPF(logf(p->grey_point_target / 100.0f)
           / logf(-p->black_point_source / (p->white_point_source - p->black_point_source)),
           min, max);
}
```

```
src/iop/filmicrgb.c:3042-3054
  // source and display greys
  float grey_source = 0.1845f, grey_display = 0.4638f;
  if(p->custom_grey)
  {
    // user set a custom value
    grey_source = p->grey_point_source / 100.0f; // in %
    grey_display = powf(p->grey_point_target / 100.0f, 1.0f / (p->output_power));
  }
  else
  {
    // use 18.45% grey and don't bother
    grey_source = 0.1845f; // in %
    grey_display = powf(0.1845f, 1.0f / (p->output_power));
  }
```

注意这里的对称性：spline 的 y 域是 `powf(线性值, 1/output_power)`，输出端再 `powf(..., output_power)`
把中间灰还原成 0.1845（线性显示值）。见 §2.8 的讨论。

v5 主循环（默认 color science）：

```
src/iop/filmicrgb.c:1947-2005
static inline void filmic_v5(const float *const restrict in, float *const restrict out,
                                    const dt_iop_order_iccprofile_info_t *const work_profile,
                                    const dt_iop_order_iccprofile_info_t *const export_profile,
                                    const dt_iop_filmicrgb_data_t *const data,
                                    const dt_iop_filmic_rgb_spline_t spline,
                                    const size_t width, const size_t height,
                                    const float display_black, const float display_white)

{
  // See colorbalancergb.c for details
  dt_colormatrix_t input_matrix_trans;         // pipeline RGB -> LMS 2006
...
  const float norm_min = exp_tonemapping_v2(0.f, data->grey_source, data->black_source, data->dynamic_range);
  const float norm_max = exp_tonemapping_v2(1.f, data->grey_source, data->black_source, data->dynamic_range);

  DT_OMP_FOR()
  for(size_t k = 0; k < height * width * 4; k += 4)
  {
    const float *const restrict pix_in = in + k;

    dt_aligned_pixel_t max_rgb = { 0.f };
    dt_aligned_pixel_t naive_rgb = { 0.f };

    RGB_tone_mapping_v4(pix_in, naive_rgb, data, spline, display_black, display_white);
    norm_tone_mapping_v4(pix_in, max_rgb, DT_FILMIC_METHOD_MAX_RGB, work_profile, data,
                         spline, norm_min, norm_max, display_black, display_white);

    // Mix max RGB with naive RGB
    dt_aligned_pixel_t pix_out;
    for_each_channel(c, aligned(pix_out, max_rgb, naive_rgb))
      pix_out[c] = (0.5f - data->saturation) * naive_rgb[c] + (0.5f + data->saturation) * max_rgb[c];

    // Save Ych in Kirk/Filmlight Yrg
    dt_aligned_pixel_t Ych_original = { 0.f };
    RGB_to_Ych(pix_in, input_matrix_trans, Ych_original);

    // Get final Ych in Kirk/Filmlight Yrg
    dt_aligned_pixel_t Ych_final = { 0.f };
    RGB_to_Ych(pix_out, input_matrix_trans, Ych_final);

    Ych_final[1] = fminf(Ych_original[1], Ych_final[1]);

    gamut_mapping(Ych_final, Ych_original, pix_out, input_matrix_trans, output_matrix, output_matrix_trans,
                  export_input_matrix_trans, export_output_matrix, export_output_matrix_trans,
                  display_black, display_white, 0.0f, use_output_profile);
    copy_pixel_nontemporal(out + k, pix_out);
  }
  dt_omploop_sfence();	// ensure that nontemporal writes complete before we attempt to read output
}
```

### 2.6 算法结构（4）desaturation / gamut mapping

```
src/iop/filmicrgb.c:1615-1627
static inline void filmic_desaturate_v4(const dt_aligned_pixel_t Ych_original,
                                        dt_aligned_pixel_t Ych_final,
                                        const float saturation)
{
  // Note : Ych is normalized through the LMS conversion,
  // meaning c is actually a saturation (saturation ~= chroma / brightness).
  // So copy-pasting c and h from a different Y is equivalent to
  // tonemapping with a norm, which is equivalent to doing exposure compensation :
  // it's saturation-invariant, aka chroma will get increased
  // if Y is increased, and the other way around.
  const float chroma_original = Ych_original[1] * Ych_original[0];  // c2
  float chroma_final = Ych_final[1] * Ych_final[0];                 // c1
```

```
src/iop/filmicrgb.c:1653-1658
static inline void gamut_check_RGB(const dt_colormatrix_t matrix_in_trans,
                                   const dt_colormatrix_t matrix_out,
                                   const dt_colormatrix_t matrix_out_trans,
                                   const float display_black,
                                   const float display_white,
                                   const dt_aligned_pixel_t Ych_in,
                                   dt_aligned_pixel_t RGB_out)
{
  // Heuristic: if there are negatives, calculate the amount (luminance) of white light that
  // would need to be mixed in to bring the pixel back in gamut.
```

```
src/iop/filmicrgb.c:1748-1764
static int filmic_v4_prepare_matrices(dt_colormatrix_t input_matrix_trans,
                                      dt_colormatrix_t output_matrix,
...
{
  dt_colormatrix_t input_temp = { { 0.0f } };
  prepare_RGB_Yrg_matrices(work_profile, input_temp, output_matrix);
  dt_colormatrix_transpose(input_matrix_trans, input_temp);
  dt_colormatrix_transpose(output_matrix_trans, output_matrix);

  // If the pipeline output profile is supported (matrix profile), we gamut map against it
  const int use_output_profile = (export_profile != NULL);
```

### 2.7 算法结构（5）highlight reconstruction

```
src/iop/filmicrgb.c:2115-2117
  // build a mask of clipped pixels
  const gboolean recover_highlights = data->enable_highlight_reconstruction
    && mask_clipped_pixels(in, mask, data->normalize, data->reconstruct_feather, roi_out->width, roi_out->height);
```

```
src/iop/filmicrgb.c:2138-2152
  // if fast mode is not in use and we we able to allocate buffer
  if(!run_fast && recover_highlights && mask && reconstructed)
  {
    // init the blown areas with noise to create particles
    float *const restrict inpainted =  dt_alloc_align_float((size_t)roi_out->width * roi_out->height * 4);
    gboolean success_1 = FALSE;
    gboolean success_2 = TRUE;
    if(inpainted)
    {
      inpaint_noise(in, mask, inpainted, data->noise_level / scale, data->reconstruct_threshold,
                    data->noise_distribution, roi_out->width, roi_out->height);

      // diffuse particles with wavelets reconstruction
      // PASS 1 on RGB channels
      success_1 = reconstruct_highlights(inpainted, mask, reconstructed, DT_FILMIC_RECONSTRUCT_RGB,
                                         data, piece, roi_in, roi_out);
```

```
src/iop/filmicrgb.c:198
  gboolean enable_highlight_reconstruction; // $DEFAULT: FALSE $DESCRIPTION: "enable highlight reconstruction"
```

### 2.8 filmic rgb 输出到哪个域（重要）

`description()` 元数据自称输出 "non-linear, RGB, display-referred"（`src/iop/filmicrgb.c:348`），
但代码里 spline 的 y 域是"显示幂空间"，最后再乘回 `output_power` 还原为线性显示值：

```
src/iop/filmicrgb.c:2184-2185
  const float white_display = powf(data->spline.y[4], data->output_power);
  const float black_display = powf(data->spline.y[0], data->output_power);
```

```
src/iop/filmicrgb.c:427-428
    n->black_point_target = powf(black_display, hardness) * 100.0f;
    n->white_point_target = powf(white_display, hardness) * 100.0f;
```

即 `spline.y` 是"gamma 空间"，`powf(y, output_power)` 是线性化，用户可见的 target black/white 是**线性百分比**。
中间灰 `grey_display = powf(0.1845, 1/output_power)` → 输出 `powf(grey_display, output_power) = 0.1845`。
**结论：filmic rgb 输出的是线性显示亮度值（0..1 左右，unbounded），不内置显示 OETF**；
显示编码留给 `colorout`（见 §5）。元数据里的 "non-linear" 与代码行为不一致，两者均已逐字列出。

### 2.9 filmic rgb 在管线中的默认位置

见 §1.7 的 `v50_order` 片段：数组顺序 `basecurve → filmic → sigmoid → agx → filmicrgb → spektrafilm → lut3d`。

`v30_order`（旧格式）：

```
src/common/iop_order.c:262-269
  { {44.0f }, "basecurve", 0},       // conversion from scene-referred to display referred, reverse-engineered
                                  //    on camera JPEG default look
  { {45.0f }, "filmic", 0},          // same, but different (parametric) approach
  { {45.3f }, "sigmoid", 0},
  { {45.5f }, "agx", 0},
  { {46.0f }, "filmicrgb", 0},       // same, upgraded
  { { 46.7f }, "spektrafilm", 0 },
  { {36.0f }, "lut3d", 0},           // apply a creative style or film emulation, possibly non-linear
```

---

## 3. AgX 模块

**该版本存在 `src/iop/agx.c`**（2866 行），`DT_MODULE_INTROSPECTION(7, ...)`。版本核实见 §0.1：
commit `92f6c282845943ad8f242c3dcb040a3c75c877b9`（2026-09-22），README 标注最新 release 为 5.6.1。

### 3.1 声明与元数据

```
src/iop/agx.c:41
DT_MODULE_INTROSPECTION(7, dt_iop_agx_params_t)
```

```
src/iop/agx.c:53-61
const char **description(dt_iop_module_t *self)
{
  return dt_iop_set_description(self,
                                _("applies a tone mapping curve.\n"
                                    "inspired by Blender's AgX tone mapper"),
                                _("corrective and creative"),
                                _("linear, RGB, scene-referred"),
                                _("non-linear, RGB"),
                                _("linear, RGB, display-referred"));
}
```

```
src/iop/agx.c:74-79
dt_iop_colorspace_type_t default_colorspace(dt_iop_module_t *self,
                                            dt_dev_pixelpipe_t *pipe,
                                            dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_RGB;
}
```

```
src/iop/agx.c:81-85
static const float _epsilon = 1E-6f;
static const float _default_gamma = 2.2f;
static const int _red_index = 0;
static const int _green_index = 1;
static const int _blue_index = 2;
```

### 3.2 参数（log 编码 / primaries / look / purity-attenuation）

```
src/iop/agx.c:88-96
typedef enum dt_iop_agx_base_primaries_t
{
  DT_AGX_EXPORT_PROFILE = 0, // $DESCRIPTION: "export profile"
  DT_AGX_WORK_PROFILE = 1,   // $DESCRIPTION: "working profile"
  DT_AGX_REC2020 = 2,        // $DESCRIPTION: "Rec2020"
  DT_AGX_DISPLAY_P3 = 3,     // $DESCRIPTION: "Display P3"
  DT_AGX_ADOBE_RGB = 4,      // $DESCRIPTION: "Adobe RGB (compatible)"
  DT_AGX_SRGB = 5,           // $DESCRIPTION: "sRGB"
} dt_iop_agx_base_primaries_t;
```

```
src/iop/agx.c:98-139
typedef struct dt_iop_agx_params_t
{
  float look_lift;                   // $MIN: -1.f $MAX: 1.f $DEFAULT: 0.f $DESCRIPTION: "lift"
  float look_slope;                  // $MIN: 0.f $MAX: 10.f $DEFAULT: 1.f $DESCRIPTION: "slope"
  float look_brightness;             // $MIN: 0.f $MAX: 100.f $DEFAULT: 1.f $DESCRIPTION: "brightness"
  float look_saturation;             // $MIN: 0.f $MAX: 10.f $DEFAULT: 1.f $DESCRIPTION: "saturation"
  float look_original_hue_mix_ratio; // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.6f $DESCRIPTION: "preserve hue"

  // log mapping
  float range_black_relative_ev;  // $MIN: -20.f $MAX: -0.1f  $DEFAULT: -10.f $DESCRIPTION: "black relative exposure"
  float range_white_relative_ev;  // $MIN: 0.1f $MAX: 20.f $DEFAULT: 6.5f $DESCRIPTION: "white relative exposure"
  float dynamic_range_scaling;    // $MIN: -0.5f $MAX: 2.f $DEFAULT: 0.1f $DESCRIPTION: "dynamic range scaling"

  // curve params - comments indicate the original variables from https://www.desmos.com/calculator/yrysofmx8h
  // Corresponds to p_x; is displayed as EV using slider offset and scale.
  // 0.606060606061f = 10/16.5, mid-gray's position if black relative exposure is -10 EV, white is +6.5 EV
  float curve_pivot_x;                    // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.606060606061f $DESCRIPTION: "pivot relative exposure"
  // Corresponds to p_y, but not directly -- needs application of gamma
  float curve_pivot_y_linear_output;      // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.18f $DESCRIPTION: "pivot target output"
  // P_slope
  float curve_contrast_around_pivot;      // $MIN: 0.1f $MAX: 10.f $DEFAULT: 3.0f $DESCRIPTION: "contrast"
  // related to P_tlength; the number expresses the portion of the y range below the pivot
  float curve_linear_ratio_below_pivot;   // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.f $DESCRIPTION: "toe start"
  // related to P_slength; the number expresses the portion of the y range below the pivot
  float curve_linear_ratio_above_pivot;   // $MIN: 0.f $MAX: 1.f $DEFAULT: 0.f $DESCRIPTION: "shoulder start"
  // t_p
  float curve_toe_power;                  // $MIN: 0.f $MAX: 10.f $DEFAULT: 1.5f $DESCRIPTION: "toe power"
  // s_p
  float curve_shoulder_power;             // $MIN: 0.f $MAX: 10.f $DEFAULT: 3.3f $DESCRIPTION: "shoulder power"
  float curve_gamma;                      // $MIN: 0.01f $MAX: 100.f $DEFAULT: 2.2f $DESCRIPTION: "curve y gamma"
  gboolean auto_gamma;                    // $DEFAULT: FALSE $DESCRIPTION: "keep the pivot on the diagonal"
  // t_ly
  float curve_target_display_black_ratio; // $MIN: 0.f $MAX: 0.15f $DEFAULT: 0.f $DESCRIPTION: "target black"
  // s_ly
  float curve_target_display_white_ratio; // $MIN: 0.2f $MAX: 1.f $DEFAULT: 1.f $DESCRIPTION: "target white"
```

```
src/iop/agx.c:140-158
  // custom primaries; rotation limits below: +/- 0.5236 radian => +/- 30 degrees
  dt_iop_agx_base_primaries_t base_primaries; // $DEFAULT: DT_AGX_REC2020 $DESCRIPTION: "base primaries"
  gboolean disable_primaries_adjustments; // $DEFAULT: FALSE $DESCRIPTION: "disable adjustments"
  float red_inset;        // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "red attenuation"
  float red_rotation;     // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "red rotation"
  float green_inset;      // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "green attenuation"
  float green_rotation;   // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "green rotation"
  float blue_inset;       // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "blue attenuation"
  float blue_rotation;    // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "blue rotation"

  float master_outset_ratio;     // $MIN:  0.f  $MAX: 2.f $DEFAULT: 1.f $DESCRIPTION: "master purity boost"
  float master_unrotation_ratio; // $MIN:  0.f  $MAX: 2.f $DEFAULT: 1.f $DESCRIPTION: "master rotation reversal"
  float red_outset;              // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "red purity boost"
  float red_unrotation;          // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "red reverse rotation"
  float green_outset;            // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "green purity boost"
  float green_unrotation;        // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "green reverse rotation"
  float blue_outset;             // $MIN:  0.f  $MAX: 0.99f $DEFAULT: 0.f $DESCRIPTION: "blue purity boost"
  float blue_unrotation;         // $MIN: -0.5236f $MAX: 0.5236f  $DEFAULT: 0.f $DESCRIPTION: "blue reverse rotation"

  // v5
  gboolean completely_reverse_primaries; // $DEFAULT: FALSE $DESCRIPTION: "reverse all"
} dt_iop_agx_params_t;
```

### 3.3 算法步骤（1）log 编码

```
src/iop/agx.c:679-690
static inline float _apply_log_encoding(const float x,
                                        const float range_in_ev,
                                        const float black_relative_ev)
{
  // Assume input is linear RGB relative to 0.18 mid-gray
  // Ensure value > 0 before log
  const float x_relative = fmaxf(_epsilon, x / 0.18f);
  // normalise to [0, 1] based on black_relative_ev and range_in_ev
  const float mapped = (log2f(fmaxf(x_relative, 0.f)) - black_relative_ev) / range_in_ev;
  // Clamp result to [0, 1] - this is the input domain for the curve
  return CLIP(mapped);
}
```

```
src/iop/agx.c:803-809
static void _set_log_mapping_params(const dt_iop_agx_params_t *p,
                                    tone_mapping_params_t *curve_and_look_params)
{
  curve_and_look_params->white_relative_ev = p->range_white_relative_ev;
  curve_and_look_params->black_relative_ev = p->range_black_relative_ev;
  curve_and_look_params->range_in_ev = curve_and_look_params->white_relative_ev - curve_and_look_params->black_relative_ev;
}
```

### 3.4 算法步骤（2）曲线（toe / linear / shoulder）

```
src/iop/agx.c:594-618
static inline float _apply_curve(const float x,
                                 const tone_mapping_params_t *params)
{
  float result = 0.f;

  if(x < params->toe_transition_x)
  {
    result = params->need_convex_toe
               ? _fallback_toe(x, params)
               : _scaled_sigmoid(x, params->toe_scale, params->slope, params->toe_power,
                                 params->toe_transition_x, params->toe_transition_y);
  }
  else if(x <= params->shoulder_transition_x)
  {
    result = _line(x, params->slope, params->intercept);
  }
  else
  {
    result = params->need_concave_shoulder
               ? _fallback_shoulder(x, params)
               : _scaled_sigmoid(x, params->shoulder_scale, params->slope, params->shoulder_power,
                                 params->shoulder_transition_x, params->shoulder_transition_y);
  }
  return CLAMPF(result, params->target_black, params->target_white);
}
```

```
src/iop/agx.c:556  static inline float _sigmoid(const float x,
src/iop/agx.c:563  static inline float _scaled_sigmoid(const float x,
src/iop/agx.c:575  static inline float _fallback_toe(const float x,
src/iop/agx.c:584  static inline float _fallback_shoulder(const float x,
（以上四行为各函数定义首行，逐字复制；函数体见 556-593）
```

```
data/kernels/agx.cl:97-104
static inline float _agx_sigmoid(const float x, const float power)
...
static inline float _agx_scaled_sigmoid(const float x, const float scale, const float slope, const float power, const float transition_x, const float transition_y)
{
  return scale * _agx_sigmoid(slope * (x - transition_x) / scale, power) + transition_y;
}
```

### 3.5 算法步骤（3）look（slope/lift/power/saturation）

```
src/iop/agx.c:651-677
DT_OMP_DECLARE_SIMD(aligned(pixel_in_out : 16))
static inline void _agx_look(dt_aligned_pixel_t pixel_in_out,
                             const tone_mapping_params_t *params,
                             const dt_colormatrix_t rendering_to_xyz_transposed)
{
  const float slope = params->look_slope;
  const float lift = params->look_lift;
  const float power = params->look_power;
  const float sat = params->look_saturation;

  for_three_channels(k, aligned(pixel_in_out : 16))
  {
    const float value_with_slope_and_lift = _apply_slope_lift(pixel_in_out[k], slope, lift);
    pixel_in_out[k] =
      value_with_slope_and_lift > 0.f
      ? powf(value_with_slope_and_lift, power)
      : value_with_slope_and_lift;
  }

  const float luma = _luminance_from_matrix(pixel_in_out, rendering_to_xyz_transposed);

  // saturation
  for_three_channels(k, aligned(pixel_in_out : 16))
  {
    pixel_in_out[k] = luma + sat * (pixel_in_out[k] - luma);
  }
}
```

```
src/iop/agx.c:640-649
static inline float _apply_slope_lift(const float x,
                                        const float slope,
                                        const float lift)
{
  // https://www.desmos.com/calculator/8a26bc7eb8
  const float m = slope / (1.f + lift);
  const float b = lift * m;
  // m * x + b
  return DT_FMA(m, x, b);
}
```

```
src/iop/agx.c:845-852
  const float brightness = p->look_brightness;
  tone_mapping_params.look_power = brightness < 1 ? 1.f / sqrtf(fmaxf(brightness, _epsilon)) : 1.f / brightness;
  tone_mapping_params.look_original_hue_mix_ratio = p->look_original_hue_mix_ratio;
  tone_mapping_params.look_tuned = p -> look_slope != 1.f
                                   || p->look_brightness != 1.f
                                   || p -> look_lift != 0.f
                                   || p -> look_saturation != 1.f;
  tone_mapping_params.restore_hue = p->look_original_hue_mix_ratio != 0.f;
```

### 3.6 算法步骤（4）primaries / inset 矩阵

```
src/iop/agx.c:254-266
typedef struct primaries_params_t
{
  dt_iop_agx_base_primaries_t base_primaries;

  float inset[3];
  float rotation[3];

  float master_outset_ratio;
  float master_unrotation_ratio;

  float outset[3];
  float unrotation[3];
} primaries_params_t;
```

```
src/iop/agx.c:1222-1243
static void _create_matrices(const primaries_params_t *params,
                             const dt_iop_order_iccprofile_info_t *pipe_work_profile,
                             const dt_iop_order_iccprofile_info_t *base_profile,
                             // outputs
                             dt_colormatrix_t rendering_to_xyz_transposed,
                             dt_colormatrix_t pipe_to_base_transposed,
                             dt_colormatrix_t base_to_rendering_transposed,
                             dt_colormatrix_t rendering_to_pipe_transposed)
{
  // Make adjusted primaries for generating the inset matrix
  //
  // References:
  // AgX by Troy Sobotka - https://github.com/sobotka/AgX-S2O3
  // Related discussions on Blender Artists forums -
  // https://blenderartists.org/t/feedback-development-filmic-baby-step-to-a-v2/1361663
  //
  // The idea is to "inset" the work RGB data toward achromatic
  // along spectral lines before per-channel curves. This makes
  // handling of bright, saturated colors much better as the
  // per-channel process desaturates them.
  // The primaries are also rotated to compensate for Abney etc.
  // and achieve a favourable shift towards yellow.
```

```
src/iop/agx.c:1247-1279
  dt_colormatrix_mul(pipe_to_base_transposed,
                     pipe_work_profile->matrix_in_transposed, // pipe->XYZ
                     base_profile->matrix_out_transposed);    // XYZ->base

  dt_colormatrix_t base_to_pipe_transposed;
  mat3SSEinv(base_to_pipe_transposed, pipe_to_base_transposed);

  // inbound path, base RGB->inset and rotated rendering space for the curve

  // Rotated, scaled primaries are calculated based on the base profile.
  float inset_and_rotated_primaries[3][2];
  for(size_t i = 0; i < 3; i++)
    dt_rotate_and_scale_primary(base_profile,
                                1.f - params->inset[i],
                                params->rotation[i],
                                i,
                                inset_and_rotated_primaries[i]);
...
  dt_make_transposed_matrices_from_primaries_and_whitepoint(inset_and_rotated_primaries,
                                                            base_profile->whitepoint,
                                                            rendering_to_xyz_transposed);
...
  dt_colormatrix_mul(base_to_rendering_transposed,
                     rendering_to_xyz_transposed,
                     base_profile->matrix_out_transposed);
```

Blender 等价 primaries（preset `blender-like|base` 使用的数值）：

```
src/iop/agx.c:317-345
static void _set_blenderlike_primaries(dt_iop_agx_params_t *p)
{
  p->disable_primaries_adjustments = FALSE;
  p->completely_reverse_primaries = FALSE;
  p->base_primaries = DT_AGX_REC2020;

  // AgX primaries settings that produce the same matrices under D50
  // as those used in the Blender OCIO config.
  // https://discuss.pixls.us/t/blender-agx-in-darktable-proof-of-concept/48697/1018
  // https://github.com/EaryChow/AgX_LUT_Gen/blob/main/AgXBaseRec2020.py
  p->red_inset = 0.29462451f;
  p->green_inset = 0.25861925f;
  p->blue_inset = 0.14641371f;
  p->red_rotation = 0.03540329f;
  p->green_rotation = -0.02108586f;
  p->blue_rotation = -0.06305724f;

  p->master_outset_ratio = 1.f;
  // Blender doesn't reverse rotations; we set up an exact unrotation below,
  // but let the user turn it on gradually
  p->master_unrotation_ratio = 0.f;

  p->red_outset = 0.290776401758f;
  p->green_outset = 0.263155400753f;
  p->blue_outset = 0.045810721815f;
  p->red_unrotation = p->red_rotation;
  p->green_unrotation = p->green_rotation;
  p->blue_unrotation = p->blue_rotation;
}
```

出程（outset / unrotation / purity 回收）：

```
src/iop/agx.c:1281-1293
  // outbound path, inset and rotated working space for the curve->base RGB

  // Rotated, primaries, with optional restoration of purity. This is
  // to be applied after the sigmoid curve; it can undo the skew and
  // recover purity (saturation).
  float outset_and_unrotated_primaries[3][2];
  for(size_t i = 0; i < 3; i++)
  {
    const float scaling = 1.f - params->master_outset_ratio * params->outset[i];
    dt_rotate_and_scale_primary(base_profile, scaling,
                                params->master_unrotation_ratio * params->unrotation[i],
                                i,
                                outset_and_unrotated_primaries[i]);
  }
```

### 3.7 算法步骤（5）gamut 压缩 + 主管线

```
src/iop/agx.c:707-716
static inline void _compress_into_gamut(dt_aligned_pixel_t pixel_in_out)
{
  // Blender: https://github.com/EaryChow/AgX_LUT_Gen/blob/main/luminance_compenstation_bt2020.py
  // Calculate original luminance
  const float luminance_coeffs[] = { 0.2658180370250449f, 0.59846986045365f, 0.1357121025213052f };

  const float input_y = pixel_in_out[0] * luminance_coeffs[0]
                      + pixel_in_out[1] * luminance_coeffs[1]
                      + pixel_in_out[2] * luminance_coeffs[2];
  const float max_rgb = max3f(pixel_in_out);
```

```
src/iop/agx.c:1057-1101
static void _agx_tone_mapping(dt_aligned_pixel_t rgb_in_out,
                              const tone_mapping_params_t *params,
                              const dt_colormatrix_t rendering_to_xyz_transposed)
{
  // record current chromaticity angle
  dt_aligned_pixel_t hsv_pixel = { 0.f };
  if(params->restore_hue)
    dt_RGB_2_HSV(rgb_in_out, hsv_pixel);
  const float h_before = hsv_pixel[0];

  dt_aligned_pixel_t transformed_pixel = { 0.f };

  for_three_channels(k, aligned(rgb_in_out, transformed_pixel : 16))
  {
    const float log_value = _apply_log_encoding(rgb_in_out[k], params->range_in_ev, params->black_relative_ev);
    transformed_pixel[k] = _apply_curve(log_value, params);
  }

  if(params->look_tuned)
    _agx_look(transformed_pixel, params, rendering_to_xyz_transposed);

  // Linearize
  for_three_channels(k, aligned(transformed_pixel : 16))
  {
    transformed_pixel[k] = powf(fmaxf(0.f, transformed_pixel[k]), params->curve_gamma);
  }

  // get post-curve chroma angle
  if(params->restore_hue)
  {
    dt_RGB_2_HSV(transformed_pixel, hsv_pixel);

    float h_after = hsv_pixel[0];

    // Mix hue back if requested
    h_after = _lerp_hue(h_before, h_after, params->look_original_hue_mix_ratio);

    hsv_pixel[0] = h_after;
    dt_HSV_2_RGB(hsv_pixel, rgb_in_out);
  }
  else
  {
    copy_pixel(rgb_in_out, transformed_pixel);
  }
}
```

```
src/iop/agx.c:1375-1413
  DT_OMP_FOR()
  for(size_t k = 0; k < 4 * n_pixels; k += 4)
  {
    const float *const restrict pix_in = in + k;
    dt_aligned_pixel_t sanitised_in = { 0.f };
    for_each_channel(c)
    {
      const float component = pix_in[c];
      // allow about 22.5 EV above mid-gray, including out-of-gamut pixels, getting rid of NaNs
      sanitised_in[c] = isnan(component) ? 0.f : CLAMPF(component, -1e6f, 1e6f);
    }
    float *const restrict pix_out = out + k;

    // Convert from pipe working space to base space
    dt_aligned_pixel_t base_rgb = { 0.f };
    if(base_working_same_profile)
    {
      copy_pixel(base_rgb, sanitised_in);
    }
    else
    {
      dt_apply_transposed_color_matrix(sanitised_in, pipe_to_base_transposed, base_rgb);
    }

    _compress_into_gamut(base_rgb);

    dt_aligned_pixel_t rendering_rgb = { 0.f };
    dt_apply_transposed_color_matrix(base_rgb, base_to_rendering_transposed, rendering_rgb);

    // Apply the tone mapping curve and look adjustments
    _agx_tone_mapping(rendering_rgb, &d->tone_mapping_params,
                      rendering_profile.matrix_in_transposed);

    // Convert from internal rendering space back to pipe working space
    dt_apply_transposed_color_matrix(rendering_rgb, rendering_to_pipe_transposed, pix_out);

    // Copy over the alpha channel
    pix_out[3] = sanitised_in[3];
  }
```

注意 `// Linearize` + `powf(..., params->curve_gamma)`（`src/iop/agx.c:1078-1082`）：
曲线 y 域是 gamma 空间（`curve_gamma` 默认 2.2），最后线性化，**输出 "linear, RGB, display-referred"**（与元数据一致）。

### 3.8 AgX 在管线中的默认位置

同 §1.7：`v50_order` 数组顺序 `basecurve → filmic → sigmoid → agx → filmicrgb → spektrafilm → lut3d`。
另外 `dt_ioppr_migrate_legacy_iop_order_list` 对**已存储**的顺序表强制插入：

```
src/common/iop_order.c:763-764
  _insert_before_after(iop_order_list, "filmicrgb", "sigmoid", TRUE);
  _insert_before_after(iop_order_list, "filmicrgb", "agx", TRUE);
```

AgX 的自动预设（`scene-referred (AgX)` 工作流）：

```
src/libs/modulegroups.c:1696
    dt_conf_is_equal("plugins/darkroom/workflow", "scene-referred (AgX)");
```

```
src/iop/agx.c:2586-2587
  // auto-applied scene-referred default
  self->pref_based_presets = TRUE;
```

```
src/iop/agx.c:2569-2573
static void _set_scene_referred_default_params(dt_iop_agx_params_t *p)
{
  _set_default_curve_and_look_params(p);
  _set_blenderlike_primaries(p);
}
```

---

## 4. lut3d 模块（最关键）

### 4.1 它把 LUT 应用在哪个域 / 色彩空间？

**元数据（模块自述的 input/output 域）：**

```
src/iop/lut3d.c:138-145
const char **description(dt_iop_module_t *self)
{
  return dt_iop_set_description(self, _("perform color space corrections and apply look"),
                                      _("corrective or creative"),
                                      _("linear, RGB, display-referred"),
                                      _("defined by profile, RGB"),
                                      _("linear or non-linear, RGB, display-referred"));
}
```

即：输入声明为 `linear, RGB, display-referred`，processing 为 `defined by profile, RGB`，
输出 `linear or non-linear, RGB, display-referred`。

**`default_colorspace`：**

```
src/iop/lut3d.c:157-162
dt_iop_colorspace_type_t default_colorspace(dt_iop_module_t *self,
                                            dt_dev_pixelpipe_t *pipe,
                                            dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_RGB;
}
```

（`input_colorspace` / `output_colorspace` 在本模块**未定义**，因此按 `default_input_colorspace` 回落到
`default_colorspace`：）

```
src/develop/format.c:78-88
int default_input_colorspace(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe,
                     dt_dev_pixelpipe_iop_t *piece)
{
  return self->default_colorspace(self, pipe, piece);
}

int default_output_colorspace(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe,
                      dt_dev_pixelpipe_iop_t *piece)
{
  return self->default_colorspace(self, pipe, piece);
}
```

**"application color space" 参数与默认值：**

```
src/iop/lut3d.c:55-63
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

```
src/iop/lut3d.c:72-78
typedef struct dt_iop_lut3d_params_t
{
  char filepath[DT_IOP_LUT3D_MAX_PATHNAME];
  dt_iop_lut3d_colorspace_t colorspace; // $DEFAULT: DT_IOP_SRGB $DESCRIPTION: "application color space"
  dt_iop_lut3d_interpolation_t interpolation; // $DEFAULT: DT_IOP_TETRAHEDRAL
  int nb_keypoints; // $DEFAULT: 0 >0 indicates the presence of compressed lut
  char c_clut[DT_IOP_LUT3D_MAX_KEYPOINTS*2*3];
  char lutname[DT_IOP_LUT3D_MAX_LUTNAME];
} dt_iop_lut3d_params_t;
```

**CPU 路径：先把 work profile 转成 "LUT profile"，再查表，再转回：**

```
src/iop/lut3d.c:1130-1181
void process(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const ibuf, void *const obuf,
             const dt_iop_roi_t *const roi_in, const dt_iop_roi_t *const roi_out)
{
  dt_iop_lut3d_data_t *d = piece->data;
...
  const int colorspace
    = (d->params.colorspace == DT_IOP_SRGB) ? DT_COLORSPACE_SRGB
    : (d->params.colorspace == DT_IOP_REC709) ? DT_COLORSPACE_REC709
    : (d->params.colorspace == DT_IOP_ARGB) ? DT_COLORSPACE_ADOBERGB
    : (d->params.colorspace == DT_IOP_LIN_PROPHOTO) ? DT_COLORSPACE_PROPHOTO_RGB
    : (d->params.colorspace == DT_IOP_LIN_REC709) ? DT_COLORSPACE_LIN_REC709
    : DT_COLORSPACE_LIN_REC2020;
  const dt_iop_order_iccprofile_info_t *const lut_profile
    = dt_ioppr_add_profile_info_to_list(self->dev, colorspace, "", INTENT_PERCEPTUAL);
  const dt_iop_order_iccprofile_info_t *const work_profile
    = dt_ioppr_get_iop_work_profile_info(self, self->dev->iop);
  const gboolean transform = (work_profile != NULL && lut_profile != NULL) ? TRUE : FALSE;
  if(clut)
  {
    if(transform)
    {
      dt_ioppr_transform_image_colorspace_rgb(ibuf, obuf, width, height,
        work_profile, lut_profile, "work profile to LUT profile");
      if(interpolation == DT_IOP_TETRAHEDRAL)
        _correct_pixel_tetrahedral(obuf, obuf, (size_t)width * height, clut, level);
      else if(interpolation == DT_IOP_TRILINEAR)
        _correct_pixel_trilinear(obuf, obuf, (size_t)width * height, clut, level);
      else
        _correct_pixel_pyramid(obuf, obuf, (size_t)width * height, clut, level);
      dt_ioppr_transform_image_colorspace_rgb(obuf, obuf, width, height,
        lut_profile, work_profile, "LUT profile to work profile");
    }
    else
    {
      if(interpolation == DT_IOP_TETRAHEDRAL)
        _correct_pixel_tetrahedral(ibuf, obuf, (size_t)width * height, clut, level);
      else if(interpolation == DT_IOP_TRILINEAR)
        _correct_pixel_trilinear(ibuf, obuf, (size_t)width * height, clut, level);
      else
        _correct_pixel_pyramid(ibuf, obuf, (size_t)width * height, clut, level);
    }
  }
  else  // no clut
  {
    dt_iop_image_copy_by_size(obuf, ibuf, width, height, ch);
  }
}
```

**OpenCL 路径同构（额外需要 scratch 图像）：**

```
src/iop/lut3d.c:1054-1075
    /* A kernel may not take one image object as both its read_only source and its
       write_only target, so the profile conversions and the lookup cannot pass dev_out
       for both. dev_out belongs to the caller and cannot be swapped for another buffer,
       so a scratch image carries the intermediate: convert into it, apply the lookup
       from it back into dev_out, then convert dev_out into it and back once more. */
    if(transform)
    {
      scratch = dt_opencl_alloc_device(devid, width, height, 4 * sizeof(float));
...
      if(!dt_ioppr_transform_image_colorspace_rgb_cl(devid, dev_in, scratch, width, height,
        work_profile, lut_profile, "work profile to LUT profile"))
        transform = FALSE;
    }

    if(transform)
      err = dt_opencl_enqueue_kernel_2d_args(devid, kernel, width, height,
              CLARG(scratch), CLARG(dev_out), CLARG(width), CLARG(height), CLARG(clut_cl), CLARG(level));
```

**work profile 是什么（管线的工作色彩空间）**：默认线性 Rec2020：

```
src/iop/colorin.c:83
  dt_colorspaces_color_profile_type_t type_work; // $DEFAULT: DT_COLORSPACE_LIN_REC2020
```

```
src/common/iop_profile.c:885-893
  if(profile_info == NULL
     || !dt_is_valid_colormatrix(profile_info->matrix_in[0][0])
     || !dt_is_valid_colormatrix(profile_info->matrix_out[0][0]))
  {
    dt_print(DT_DEBUG_ALWAYS,
             "[dt_ioppr_set_pipe_work_profile_info] unsupported working profile %s %s, "
             "it will be replaced with linear Rec2020",
             dt_colorspaces_get_name(type, NULL), filename);
    profile_info = dt_ioppr_add_profile_info_to_list(dev, DT_COLORSPACE_LIN_REC2020, "", intent);
  }
```

`dt_ioppr_get_iop_work_profile_info` 只在模块位于 `colorin` 与 `colorout` 之间时返回 work profile：

```
src/common/iop_profile.c:833-855
  // first check if the module is between colorin and colorout
  gboolean in_between = FALSE;

  for(GList *modules = iop_list; modules; modules = g_list_next(modules))
  {
    dt_iop_module_t *mod = modules->data;

    // we reach the module, that's it
    if(dt_iop_module_is(mod, module->op)) break;

    // if we reach colorout means that the module is after it
    if(dt_iop_module_is(mod, "colorout"))
    {
      in_between = FALSE;
      break;
    }

    // we reach colorin, so far we're good
    if(dt_iop_module_is(mod, "colorin"))
    {
      in_between = TRUE;
      break;
    }
  }
```

**结论（§4.1）**：LUT 不是"在管线当前域直接查表"。它总是先把管线 work profile 的数据
**转换成用户声明的 "application color space"（默认 gamma 编码 sRGB）**，在该空间查表，
再转回 work profile。因此"LUT 的输入编码"由 `colorspace` 参数决定，**不是**由模块在管线中的位置决定。
默认 `DT_IOP_SRGB`（非线性的 gamma sRGB），尽管模块在默认顺序中位于 filmic rgb 之后。

官方文档对同一个参数的说法：

```
/private/tmp/dtdocs/content/module-reference/processing-modules/lut-3D.md:51-52
application color space
: A 3D LUT is defined relative to a specific color space. Choose the color space for which the selected 3D LUT file has been built. Cube files are usually related to Rec. 709 while most others are related to sRGB.
```

（URL: https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/lut-3d/
 与本地 dtdocs 同源；`lut-3D.md` 对应线上页面 `.../lut-3d/`。）

### 4.2 相对于 tone mapping（filmic/sigmoid/agx/base curve）的先后顺序

#### 4.2.1 本版本模块**不声明 order**（关键事实）

```
$ grep -rn "default_order" --include=*.c --include=*.h /private/tmp/dtsrc/src/
（无输出）
```

**源码中未找到 `default_order`**（本版本已无此字段/函数）。`dt_iop_module_so_t` 也不含
`default_colorspace` 之类的数据成员，而是通过 `#include "iop/iop_api.h"` 把 API 函数指针嵌进结构体：

```
src/develop/imageop.h:141-168
/** part of the module which only contains the cached dlopen stuff. */
typedef struct dt_iop_module_so_t
{
  dt_action_t actions; // !!! NEEDS to be FIRST (to be able to cast convert)

#define INCLUDE_API_FROM_MODULE_H
#include "iop/iop_api.h"

  /** opened module. */
  GModule *module;
  /** string identifying this operation. */
  dt_dev_operation_t op;
  /** other stuff that may be needed by the module, not only in gui mode.
   * inited only once, has to be read-only then. */
  dt_iop_global_data_t *data;
  /** button used to show/hide this module in the plugin list. */
  dt_iop_module_state_t state;

  void (*process_plain)(struct dt_iop_module_t *self,
                        struct dt_dev_pixelpipe_iop_t *piece,
                        const void *const i, void *const o,
                        const struct dt_iop_roi_t *const roi_in,
                        const struct dt_iop_roi_t *const roi_out);

  // introspection related data
  gboolean have_introspection;
  // contains preset which is depending on preference (workflow)
  gboolean pref_based_presets;
} dt_iop_module_so_t;
```

```
src/iop/iop_api.h:100-108
/** what default colorspace this iop use? */
REQUIRED(dt_iop_colorspace_type_t, default_colorspace,
         struct dt_iop_module_t *self,
         struct dt_dev_pixelpipe_t *pipe,
         struct dt_dev_pixelpipe_iop_t *piece);
/** what input colorspace it expects? */
DEFAULT(dt_iop_colorspace_type_t, input_colorspace,
        struct dt_iop_module_t *self,
        struct dt_dev_pixelpipe_t *pipe,
        struct dt_dev_pixelpipe_iop_t *piece);
/** what will it output? */
DEFAULT(dt_iop_colorspace_type_t, output_colorspace,
        struct dt_iop_module_t *self,
        struct dt_dev_pixelpipe_t *pipe,
        struct dt_dev_pixelpipe_iop_t *piece);
```

`dt_iop_load_module` 本身不设置 order：

```
src/develop/imageop.c:1965-1975
gboolean dt_iop_load_module(dt_iop_module_t *module,
                            dt_iop_module_so_t *module_so,
                            dt_develop_t *dev)
{
  memset(module, 0, sizeof(dt_iop_module_t));
  if(dt_iop_load_module_by_so(module, module_so, dev))
  {
    free(module);
    return TRUE;
  }
  return FALSE;
}
```

order 由 `dt_ioppr_resync_modules_order` / `dt_ioppr_set_default_iop_order` 事后赋值：

```
src/common/iop_order.c:1303-1326
void dt_ioppr_resync_modules_order(dt_develop_t *dev)
{
  _ioppr_reset_iop_order(dev->iop_order_list);

  // and reset all module iop_order

  GList *modules = dev->iop;
  while(modules)
  {
    dt_iop_module_t *mod = modules->data;
    GList *next = g_list_next(modules);

    // modules with iop_order set to INT_MAX we keep them as they will
    // be removed (non visible)
    // _lib_modulegroups_update_iop_visibility.
    if(mod->iop_order != INT_MAX)
      mod->iop_order = dt_ioppr_get_iop_order(dev->iop_order_list,
                                              mod->op, mod->multi_priority);

    modules = next;
  }

  dev->iop = g_list_sort(dev->iop, dt_sort_iop_by_order);
}
```

#### 4.2.2 内置 order 表逐字（含 filmic/sigmoid/agx/lut3d/colorout）

默认表 `v50_order`（RAW）：

```
src/common/iop_order.c:306
const dt_iop_order_entry_t v50_order[] = {
```

```
src/common/iop_order.c:384-394
  { {43.0f }, "rgblevels", 0},       // same
  { {44.0f }, "basecurve", 0},       // conversion from scene-referred to display referred, reverse-engineered
                                  //    on camera JPEG default look
  { {45.0f }, "filmic", 0},          // same, but different (parametric) approach
  { {45.3f }, "sigmoid", 0},
  { {45.5f }, "agx", 0},
  { {46.0f }, "filmicrgb", 0},       // same, upgraded
  { { 46.7f }, "spektrafilm", 0 },
  { {36.0f }, "lut3d", 0},           // apply a creative style or film emulation, possibly non-linear
  { {47.0f }, "colisa", 0},          // edit contrast while damaging colour
  { {48.0f }, "tonecurve", 0},       // same
```

```
src/common/iop_order.c:416-419
  { {69.0f }, "colorreconstruct", 0},// try to salvage blown areas before ICC intents in LittleCMS2 do things with them.
  { {69.4f }, "finalscale", 0},
  { {70.0f }, "colorout", 0},
  { {71.0f }, "clahe", 0},
```

`v30_order`（RAW，旧格式，数组顺序与 v50 相同）：

```
src/common/iop_order.c:262-269
  { {44.0f }, "basecurve", 0},       // conversion from scene-referred to display referred, reverse-engineered
                                  //    on camera JPEG default look
  { {45.0f }, "filmic", 0},          // same, but different (parametric) approach
  { {45.3f }, "sigmoid", 0},
  { {45.5f }, "agx", 0},
  { {46.0f }, "filmicrgb", 0},       // same, upgraded
  { { 46.7f }, "spektrafilm", 0 },
  { {36.0f }, "lut3d", 0},           // apply a creative style or film emulation, possibly non-linear
```

```
src/common/iop_order.c:294
  { {70.0f }, "colorout", 0},
```

`legacy_order`（display-referred 工作流，`DT_IOP_ORDER_LEGACY`）——注意这里是 **lut3d 在 basecurve 之后**：

```
src/common/iop_order.c:111-112
  { {23.0f }, "basecurve", 0},
  { {24.0f }, "bilateral", 0},
```

```
src/common/iop_order.c:145-149
  { {45.0f }, "monochrome", 0},
  { {45.3f }, "sigmoid", 0},
  { {45.5f }, "agx", 0},
  { {46.0f }, "filmic", 0},
  { {46.5f }, "filmicrgb", 0},
```

```
src/common/iop_order.c:162-165
  { {56.0f }, "grain", 0},
  { {56.5f }, "lut3d", 0},
  { {57.0f }, "colorcontrast", 0},
  { {58.0f }, "colorout", 0},
```

表选择与数组映射：

```
src/common/iop_order.c:681-689
const dt_iop_order_entry_t *const _iop_order_tables[DT_IOP_ORDER_LAST] =
{
  NULL,
  legacy_order,
  v30_order,
  v30_jpg_order,
  v50_order,
  v50_jpg_order
};
```

#### 4.2.3 数组位置决定管线顺序（不是括号里的浮点值）

这是本报告最容易误读的地方，逐条给证据。

`_table_to_list` **只按数组顺序**建表，把浮点值写进 union 的 `iop_order_f`：

```
src/common/iop_order.c:1125-1143
GList *_table_to_list(const dt_iop_order_entry_t entries[])
{
  GList *iop_order_list = NULL;
  int k = 0;
  while(entries[k].operation[0])
  {
    dt_iop_order_entry_t *entry = malloc(sizeof(dt_iop_order_entry_t));

    g_strlcpy(entry->operation, entries[k].operation, sizeof(entry->operation));
    entry->instance = 0;
    entry->o.iop_order_f = entries[k].o.iop_order_f;
    iop_order_list = g_list_prepend(iop_order_list, entry);

    k++;
  }

  // list was built in reverse order, so un-reverse it
  return g_list_reverse(iop_order_list);
}
```

union 定义（浮点与 int 共用存储）：

```
src/common/iop_order.h:141-150
typedef struct dt_iop_order_entry_t
{
  union {
    double iop_order_f;  // only used for backward compatibility while migrating db
    int iop_order;       // order from 1 and incrementing
  } o;
  // operation + instance is the unique id for an active module in the pipe
  char operation[20];
  int32_t instance;      // or previously named multi_priority
  char name[25];
} dt_iop_order_entry_t;
```

随后 `_ioppr_reset_iop_order` **按列表位置**重写 `iop_order`，浮点值被覆盖：

```
src/common/iop_order.c:1266-1279
static void _ioppr_reset_iop_order(GList *iop_order_list)
{
  // iop-order must start with a number > 0 and be incremented. There is no
  // other constraints.
  int iop_order = 100;
  for(const GList *l = iop_order_list; l; l = g_list_next(l))
  {
    dt_iop_order_entry_t *e = l->data;
    e->o.iop_order = iop_order;
    // keep some spaces between modules to ensure that we can insert some
    // modules in the middle if needed. (see dt_ioppr_check_duplicate_iop_order).
    iop_order += 100;
  }
}
```

注释也明说浮点值只用于旧 DB 迁移：

```
src/common/iop_order.c:72-79
// note legacy_order & v30_order have the original iop-order double
// that is used only for the initial database migration.
//
// in the new code only the iop-order as int is used to order the
// module on the GUI.

// @@_NEW_MODULE: For new module it is required to insert the new
//                module name in both lists below.
```

加载路径：内置表 → `_table_to_list` → `_ioppr_reset_iop_order`（**中间没有按浮点排序**）：

```
src/common/iop_order.c:1231-1245
      else if(version >= DT_IOP_ORDER_LEGACY
              && version < DT_IOP_ORDER_LAST)
      {
        iop_order_list = _table_to_list(_iop_order_tables[version]);
      }
      else
        dt_print(DT_DEBUG_ALWAYS,
                 "[dt_ioppr_get_iop_order_list] invalid iop order"
                 " version %d for imgid %d",
                 version, imgid);

      if(iop_order_list)
      {
        _ioppr_reset_iop_order(iop_order_list);
      }
```

```
src/common/iop_order.c:1332-1351
void dt_ioppr_set_default_iop_order(dt_develop_t *dev,
                                    const dt_imgid_t imgid)
{
  // get the iop-order for this image

  GList *iop_order_list = dt_ioppr_get_iop_order_list(imgid, FALSE);

  // we assign a single iop-order to each module

  _ioppr_reset_iop_order(iop_order_list);

  if(dev->iop_order_list)
    g_list_free_full(dev->iop_order_list, free);

  dev->iop_order_list = iop_order_list;

  // we now set the module list given to this iop-order

  dt_ioppr_resync_modules_order(dev);
}
```

`dt_sort_iop_list_by_order_f`（用浮点值排序）只在**从 DB 反序列化旧顺序表**时使用：

```
src/common/database.c:1329-1331
          // and finally reorder the full list based on the iop-order

          iop_order_list = g_list_sort(iop_order_list, dt_sort_iop_list_by_order_f);
```

顺序表"种类"判定也是**按数组位置**逐项比较，进一步确认数组顺序是规范：

```
src/common/iop_order.c:965-994
gboolean _check_iop_list_equal(GList *iop_order_list,
                               const dt_iop_order_entry_t order_table[])
{
  int k = 0;
  GList *l = iop_order_list;
  gboolean ok = TRUE;

  while(l)
  {
    const dt_iop_order_entry_t *const restrict entry = l->data;
    if(strcmp(order_table[k].operation, entry->operation))
    {
      ok = FALSE;
      break;
    }
```

**管线处理方向**：小 order = 先处理。列表升序，处理时从表尾递归到表头：

```
src/develop/pixelpipe_hb.c:3412-3415
  //  go through list of modules from the end:
  const guint pos = g_list_length(pipe->iop);
  GList *modules = g_list_last(pipe->iop);
  GList *pieces = g_list_last(pipe->nodes);
```

```
src/develop/pixelpipe_hb.c:2288
  /*  finally we don't recurse any longer but process modules in correct iop_order.
```

```
src/develop/pixelpipe_hb.c:2283-2285
  if(_dev_pixelpipe_process_rec(pipe, dev, &input, &cl_mem_input, &input_format, &roi_in,
                                g_list_previous(modules),
                                g_list_previous(pieces), pos - 1))
    return TRUE;
```

排序比较器（升序）：

```
src/common/iop_order.c:1948-1955
gint dt_sort_iop_by_order(gconstpointer a, gconstpointer b)
{
  const dt_iop_module_t *const restrict am = (const dt_iop_module_t *)a;
  const dt_iop_module_t *const restrict bm = (const dt_iop_module_t *)b;
  if(am->iop_order > bm->iop_order) return 1;
  if(am->iop_order < bm->iop_order) return -1;
  return 0;
}
```

gamma 必须是最后一个（也说明升序 = 处理顺序）：

```
src/common/iop_order.c:1885-1900
gboolean dt_ioppr_check_iop_order_ext(dt_develop_t *dev,
                                      const dt_imgid_t imgid,
                                      const char *msg)
{
  gboolean iop_order_ok = TRUE;

  // check if gamma is the last iop
  {
    GList *modules;
    for(modules = g_list_last(dev->iop);
        modules;
        modules = g_list_previous(modules))
    {
      const dt_iop_module_t *const restrict mod = modules->data;
      if(mod->iop_order != INT_MAX)
        break;
    }
    if(modules)
    {
      const dt_iop_module_t *const restrict mod = modules->data;

      if(!dt_iop_module_is_gamma(mod))
```

**结论（§4.2）**：在默认 `v50_order` 下，数组顺序是
`... → basecurve → filmic → sigmoid → agx → filmicrgb → spektrafilm → **lut3d** → colisa → tonecurve → ... → colorout → gamma`。
因此 **3D LUT 在 tone mapping（basecurve/filmic/sigmoid/agx/filmicrgb）之后**，`colorout` 之前。
括号里的 `{ {36.0f }, "lut3d", 0}` 是历史遗留浮点值，在本版本中**不参与排序**（`_ioppr_reset_iop_order`
用列表位置覆盖它）。

旁证 1（历史序列化数据，dt 3.4–4.2 时代的 XMP）：`iop_order_list` 里 lut3d 出现在 basecurve/filmicrgb **之前**，
说明当时的顺序确实由浮点值 36.0 决定，后来语义改了：

```
src/tests/benchmark/darktable-bench-4.2.xmp:21（节选）
...,highpass,0,sharpen,0,lut3d,0,colortransfer,0,colormapping,0,channelmixer,0,basicadj,0,colorbalance,0,colorbalancergb,0,rgbcurve,0,rgblevels,0,basecurve,0,filmic,0,filmicrgb,0,filmicrgb,1,colisa,0,...
```

旁证 2（官方文档，与本版本数组顺序一致）：

```
/private/tmp/dtdocs/content/module-reference/processing-modules/lut-3D.md:42
LUTs are most commonly used in darktable for color grading or film look simulation. For this reason, by default, the module is placed after the [_filmic rgb_](./filmic-rgb.md) module in the pixelpipe and should be applied to a neutral image (without first applying a specific look). While you can find hundreds of free LUTs on the internet, you should note that not all of them are compatible with the darktable environment and workflow -- incompatible LUTs will not produce the advertised look. To limit the risk, a color grading LUT should have been created to work with one of the available "application color spaces" (see below), for both the input and the output of the module.
```

旁证 3（手工重排 LUT3D 的官方说明，同样假定 LUT3D 默认在 tone mapping 之后）：

```
/private/tmp/dtdocs/content/module-reference/processing-modules/lut-3D.md:44
Camera log LUTs (as F-log or S-Log3) are different to color-grading and film-look-simulation LUTs, and are intended to convert the camera log raw data into something (linear raw data or other color space) that darktable is able to understand. In this case the _LUT 3D_ module should be manually placed between the [_demosaic_](./demosaic) and [_input color profile_](./colorin.md) modules. Once you have done this, you can no longer choose an "application color space". The "input profile" of input color profile module should be aligned with the output of the LUT. _Please note that this use case has not yet been tested._
```

（同一份文档另有 URL 形式：
https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/lut-3d/
 注：本次 `web_fetch` 该 URL 返回 HTTP 404；本地 dtdocs 仓库对应文件路径为
 `content/module-reference/processing-modules/lut-3D.md`，git hash `79c10958aee76654dcac55a7c5e645a05621657b`。）

### 4.3 LUT 文件解析：支持哪些格式

```
src/iop/lut3d.c:1246-1278
static int _calculate_clut(dt_iop_lut3d_params_t *const p, float **clut)
{
  uint16_t level = 0;
  const char *filepath = p->filepath;
  if(!_filepath_is_safe(filepath)) return level;
#ifdef HAVE_GMIC
...
    gchar *lutfolder = dt_conf_get_string("plugins/darkroom/lut3d/def_path");
    if(filepath[0] && lutfolder[0])
    {
      char *fullpath = g_build_filename(lutfolder, filepath, NULL);
      if(g_str_has_suffix (filepath, ".png") || g_str_has_suffix (filepath, ".PNG"))
      {
        level = _calculate_clut_haldclut(p, fullpath, clut);
      }
      else if(g_str_has_suffix (filepath, ".cube") || g_str_has_suffix (filepath, ".CUBE"))
      {
        level = _calculate_clut_cube(fullpath, clut);
      }
      else if(g_str_has_suffix (filepath, ".3dl") || g_str_has_suffix (filepath, ".3DL"))
      {
        level = _calculate_clut_3dl(fullpath, clut);
      }
```

```
src/iop/lut3d.c:1606-1612
static int
...
  if(!g_strcmp0(fext, ".png") || !g_strcmp0(fext, ".cube") || !g_strcmp0(fext, ".3dl")
      || !g_strcmp0(fext, ".gmz")) res = 1;
```

```
src/iop/lut3d.c:1693-1695
  gtk_file_filter_set_name(filter, _("hald CLUT (png), 3D LUT (cube or 3dl) or gmic compressed LUT (gmz)"));
...
  gtk_file_filter_set_name(filter, _("hald CLUT (png) or 3D LUT (cube or 3dl)"));
```

HaldCLUT 的内部网格级别：

```
src/iop/lut3d.c:52
#define DT_IOP_LUT3D_CLUT_LEVEL 48
```

```
src/iop/lut3d.c:485
  uint8_t level = DT_IOP_LUT3D_CLUT_LEVEL;
```

插值方式：

```
src/iop/lut3d.c:65-70
typedef enum dt_iop_lut3d_interpolation_t
{
  DT_IOP_TETRAHEDRAL = 0, // $DESCRIPTION: "tetrahedral"
  DT_IOP_TRILINEAR = 1,   // $DESCRIPTION: "trilinear"
  DT_IOP_PYRAMID = 2,     // $DESCRIPTION: "pyramid"
} dt_iop_lut3d_interpolation_t;
```

### 4.4 `.cube` 的 `DOMAIN_MIN` / `DOMAIN_MAX` —— 只做合法性校验，**从不参与映射**

解析函数全文关键段（逐字）：

```
src/iop/lut3d.c:762-782
static uint16_t _calculate_clut_cube(const char *const filepath, float **clut)
{
  char *line = NULL;
  size_t len = 0;
  ssize_t read;
  char token[3][50];
  uint16_t level = 0;
  float *lclut = NULL;
  uint32_t i = 0;
  size_t buf_size = 0;
  uint32_t out_of_range_nb = 0;

  FILE *cube_file = g_fopen(filepath, "r");

  if(!cube_file)
  {
    dt_print(DT_DEBUG_ALWAYS, "[lut3d] invalid cube file: %s", filepath);
    dt_control_log(_("error - invalid cube file: %s"), filepath);
    return 0;
  }
```

```
src/iop/lut3d.c:782-819
  while((read = getline(&line, &len, cube_file)) != -1)
  {
    const uint8_t nb_token = _parse_cube_line(line, token);
    if(nb_token)
    {
      if(token[0][0] == 'T') continue;
      else if(strcmp("DOMAIN_MIN", token[0]) == 0)
      {
        if(strtod(token[1], NULL) != 0.0f)
        {
          dt_print(DT_DEBUG_ALWAYS, "[lut3d] DOMAIN MIN other than 0 is not supported");
          dt_control_log(_("DOMAIN MIN other than 0 is not supported"));
          dt_free_align(lclut);
          free(line);
          fclose(cube_file);
          return 0;
        }
      }
      else if(strcmp("DOMAIN_MAX", token[0]) == 0)
      {
        if(strtod(token[1], NULL) != 1.0f)
        {
          dt_print(DT_DEBUG_ALWAYS, "[lut3d] DOMAIN MAX other than 1 is not supported");
          dt_control_log(_("DOMAIN MAX other than 1 is not supported"));
          dt_free_align(lclut);
          free(line);
          fclose(cube_file);
          return 0;
        }
      }
      else if(strcmp("LUT_1D_SIZE", token[0]) == 0)
      {
        dt_print(DT_DEBUG_ALWAYS, "[lut3d] 1D cube LUT is not supported");
        dt_control_log(_("1D cube LUT is not supported"));
        free(line);
        fclose(cube_file);
        return 0;
      }
```

```
src/iop/lut3d.c:820-844
      else if(strcmp("LUT_3D_SIZE", token[0]) == 0)
      {
        level = atoll(token[1]);
        if(level > 256)
        {
          dt_print(DT_DEBUG_ALWAYS, "[lut3d] error - LUT 3D size %d > 256", level);
          dt_control_log(_("error - LUT 3D size %d exceeds the maximum supported"), level);
          free(line);
          fclose(cube_file);
          return 0;
        }
        buf_size = level * level * level * 3;
        dt_print(DT_DEBUG_DEV, "[lut3d] allocating %zu bytes for cube LUT - level %d", buf_size, level);
        // for_each_channel() reads 4 floats per entry but we store 3; over-allocate by 1
        lclut = dt_alloc_align_float(buf_size + 1);
```

```
src/iop/lut3d.c:845-872
      else if(nb_token == 3)
      {
        if(!level)
        {
          dt_print(DT_DEBUG_ALWAYS, "[lut3d] error - cube LUT size is not defined");
          dt_control_log(_("error - cube LUT size is not defined"));
          free(line);
          fclose(cube_file);
          return 0;
        }
        for(int j=0; j < 3; j++)
        {
          lclut[i+j] = _dt_atof(token[j]);
          if(dt_isnan(lclut[i+j]))
          {
            dt_print(DT_DEBUG_ALWAYS, "[lut3d] error - invalid number line %d", (int)i/3);
            dt_control_log(_("error - cube LUT invalid number line %d"), (int)i/3);
            free(line);
            fclose(cube_file);
            return 0;
          }
          else if(lclut[i+j] < 0.0 || lclut[i+j] > 1.0)
            out_of_range_nb++;
        }
        i += 3;
      }
```

```
src/iop/lut3d.c:884-893
  if(out_of_range_nb)
  {
    dt_print(DT_DEBUG_ALWAYS, "[lut3d] warning - %u values out of range [0,1]", out_of_range_nb);
    dt_control_log(_("warning - cube LUT has %d values out of range [0,1]"), out_of_range_nb);
  }
  lclut[buf_size] = 0.0f; // padding
  *clut = lclut;
  free(line);
  fclose(cube_file);
  return level;
}
```

**逐条事实：**

1. `DOMAIN_MIN` / `DOMAIN_MAX` 的值被 `strtod(token[1], NULL)` **就地丢弃**，没有赋给任何变量
   （`src/iop/lut3d.c:790`、`src/iop/lut3d.c:802`）。函数内**不存在** domain 相关变量（见上面
   `_calculate_clut_cube` 的局部变量声明 `src/iop/lut3d.c:764-772`）。
2. 只检查 `token[1]`（第一个数值），**不检查** `token[2]`/`token[3]`。
3. 非 `DOMAIN_MIN 0 0 0` / `DOMAIN_MAX 1 1 1` 的文件被**整个拒绝**（`return 0`），而不是做重映射。
4. LUT 数据本身逐通道只做 `dt_isnan` 检查与 `[0,1]` 越界计数（仅告警，不修正）。
5. 查表时输入被硬性 clip 到 `[0,1]`，**与 DOMAIN 无关**：

```
src/iop/lut3d.c:251-262
    DT_ALIGNED_PIXEL int rgbi[4];
    dt_aligned_pixel_t rgbd;
    dt_aligned_pixel_t tmp1, tmp2, tmp3;

    // scale the input according to grid size
    for_each_channel(c, aligned(input))
      rgbd[c] = CLIP(input[c]) * flevel_1;
    // quantize to grid
    for_each_channel(c)
      rgbi[c] = (int)rgbd[c];
    for_each_channel(c)
      rgbi[c] = CLAMP(rgbi[c], 0, level_minus_2);
```

```
src/common/math.h:73
#define CLIP(x) (((x) >= 0) ? ((x) <= 1 ? (x) : 1) : 0)
```

GPU 侧同样硬编码 `[0,1]`：

```
data/kernels/lut3d.cl:35-40
  float4 input = clip4(readpixel(in, x, y));
  float4 output = (float4)(0.0f);

  rgbd = input * (float)(level - 1);
  rgbi = min( max( convert_int4(rgbd), (int4)0), (int4)(level - 2));

// delta r, g, b
  rgbd = rgbd - convert_float4(rgbi);
```

官方文档也确认裁剪行为：

```
/private/tmp/dtdocs/content/module-reference/processing-modules/lut-3D.md:36
**Note**: the module clips all values outside of the range [0,1]. You may have to reduce the range of the input before applying.
```

**结论（§4.4）**：`.cube` 的 `DOMAIN_MIN` / `DOMAIN_MAX` **没有被当作输入域标注来做映射**。
darktable 只把它们当作"必须是 0 / 1"的断言；任何非默认 domain 的 `.cube` 会被拒绝加载。
输入域的声明完全依赖 UI 参数 `application color space`。

`.3dl` 的解析则**会**根据观测到的最大值推断位深并归一化（与 `.cube` 的处理方式不同）：

```
src/iop/lut3d.c:996-1013
  // search bit depth: min 2^x > max_value
  int inorm = 1;
  while((inorm < max_value) && (inorm < 65536))  // bit depth 16
    inorm <<= 1;
  if(inorm < 128)  // bit depth 7
  {
    dt_print(DT_DEBUG_ALWAYS, "[lut3d] error - the maximum LUT value does not match any valid bit depth");
    dt_control_log(_("error - the maximum LUT value does not match any valid bit depth"));
    dt_free_align(lclut);
    return 0;
  }
  const float norm = 1.0f / (float)(inorm - 1);
  // normalize the lut
  for(i =0; i < buf_size; i++)
    lclut[i] = CLAMP(lclut[i] * norm, 0.0f, 1.0f);
```

### 4.5 lut3d 的其他相关事实

- 编译期依赖 GMIC 才能用 `.gmz`：`src/iop/lut3d.c:1248` / `:1258`（`#ifdef HAVE_GMIC` / `#endif  // HAVE_GMIC`），
  以及 `src/iop/lut3d.c:1606-1612` 的两条分支。
- LUT 根目录来自偏好 `plugins/darkroom/lut3d/def_path`：`src/iop/lut3d.c:1259`。
- 模块 flags：`src/iop/lut3d.c:147-150`

```
src/iop/lut3d.c:147-150
int flags()
{
  return IOP_FLAGS_INCLUDE_IN_STYLES | IOP_FLAGS_SUPPORTS_BLENDING;
}
```

- 模块组：`src/iop/lut3d.c:152-155`

```
src/iop/lut3d.c:152-155
int default_group()
{
  return IOP_GROUP_COLOR | IOP_GROUP_TECHNICAL;
}
```

---

## 5. scene-referred → display-referred 的分界点，以及 colorout

### 5.1 `dt_iop_colorspace_type_t` / `DT_IOP_CS_*` **不表达** scene/display 之分

```
src/common/colorspaces.h:40-51
/** colorspace enums, must be in synch with dt_iop_colorspace_type_t
 * in color_conversion.cl */
typedef enum dt_iop_colorspace_type_t
{
  IOP_CS_NONE = -1,
  IOP_CS_RAW = 0,
  IOP_CS_LAB = 1,
  IOP_CS_RGB = 2,
  IOP_CS_LCH = 3,
  IOP_CS_HSL = 4,
  IOP_CS_JZCZHZ = 5,
} dt_iop_colorspace_type_t;
```

**源码中未找到 `DT_IOP_CS_SCENE` / `DT_IOP_CS_DISPLAY` / 任何表示 scene-referred 或 display-referred
的枚举值。** `dt_iop_order_iccprofile_info_t` 也没有这类字段：

```
src/common/iop_profile.h:38-56
typedef struct dt_iop_order_iccprofile_info_t
{
  dt_colorspaces_color_profile_type_t type;
  char filename[DT_IOP_COLOR_ICC_LEN];
  dt_iop_color_intent_t intent;
  dt_colormatrix_t matrix_in; // don't align on more than 16 bits or OpenCL will fail
  dt_colormatrix_t matrix_out;
  int lutsize;
  float *lut_in[3];
  float *lut_out[3];
  float unbounded_coeffs_in[3][3] DT_ALIGNED_PIXEL;
  float unbounded_coeffs_out[3][3] DT_ALIGNED_PIXEL;
  int nonlinearlut;
  float grey;
  dt_colormatrix_t matrix_in_transposed;  // same as matrix_in, but stored such as to permit vectorization
  dt_colormatrix_t matrix_out_transposed; // same as matrix_out, but stored such as to permit vectorization
  float primaries[3][2];
  float whitepoint[2];
} dt_iop_order_iccprofile_info_t;
```

`dt_ioppr_get_iop_work_profile_info` 也不区分域，只判断模块是否在 `colorin` 与 `colorout` 之间
（见 §4.1 引用，`src/common/iop_profile.c:833-855`）。

因此 **"scene-referred → display-referred 的分界"在代码层面不是某个 enum 切换，而是三件事的组合**：
(a) 模块自身的 `description()` 元数据字符串；(b) 内置 order 表中 tone mapper 与 `colorout` 的相对位置；
(c) `colorout` 是唯一把 profile TRC/OETF 施加到像素上的模块。官方文档对术语的定义：

```
/private/tmp/dtdocs/content/special-topics/color-pipeline.md:66-84
linear
: Pixel values are proportional to the scene radiometric emission, in a way that enables accurate emulation of physical filters.

non-linear
: Pixel values are re-scaled such that low-lights are given a larger encoding range, usually to remap the 18.45% reference middle-gray to a value between 46 and 50%.

display-referred
: Pixel values are expected to lie between 0 and 100% of the display range, where 100% is understood to be the luminance of a 20% reflective white surface (the white patch of a Color Checker) and 0% is understood to be the maximum density of the output medium (saturated black ink or minimum LED panel backlighting).

scene-referred
: Pixel values are expected to be greater than zero up to +infinity. The meaning of specific pixel values needs to be defined at runtime by the user. Scene-referred values don't automatically imply a linear, radiometrically scaled, encoding.
```

```
/private/tmp/dtdocs/content/special-topics/color-pipeline.md:48
In darktable 3.4 and above, hovering the cursor over a module header shows a tooltip detailing the color spaces, ranges and encodings that the module expects, uses and produces. Here are the definitions of the terms used:
```

### 5.2 管线中的实际转换发生在模块边界（按 colorspace 枚举）

```
src/develop/pixelpipe_hb.c:1657-1688
  const dt_iop_colorspace_type_t cst_from = input_format->cst;
  const dt_iop_colorspace_type_t cst_to = module->input_colorspace(module, pipe, piece);
  const dt_iop_colorspace_type_t cst_out = module->output_colorspace(module, pipe, piece);
...
  if(cst_from != cst_to)
  {
    dt_print_pipe(DT_DEBUG_PIPE,
                  "transform colorspace",
                  pipe, module, DT_DEVICE_CPU, roi_in, NULL, "%s -> %s `%s'",
                  dt_iop_colorspace_to_name(cst_from),
                  dt_iop_colorspace_to_name(cst_to),
                  work_profile
                    ? dt_colorspaces_get_name(work_profile->type, work_profile->filename)
                    : "no work profile");

    // transform input cacheline to module input colorspace
    dt_ioppr_transform_image_colorspace(module, input, tmp,
                                      roi_in->width, roi_in->height,
                                      cst_tmp, cst_to, &cst_tmp,
                                      work_profile);
```

```
src/develop/format.c:42-49
void default_input_format(dt_iop_module_t *self, dt_dev_pixelpipe_t *pipe, dt_dev_pixelpipe_iop_t *piece,
                          dt_iop_buffer_dsc_t *dsc)
{
  dsc->channels = 4;
  dsc->datatype = TYPE_FLOAT;
  dsc->cst = self->input_colorspace(self, pipe, piece);

  if(dsc->cst != IOP_CS_RAW) return;
```

即 sigmoid / filmicrgb / agx / lut3d / basecurve 都声明 `IOP_CS_RGB`（见 §1.1、§2.1、§3.1、§4.1、
§5.3），因此彼此之间**不发生自动色彩空间转换**；只有 `colorout` 声明 `IOP_CS_LAB` 输入，才会触发
RGB → Lab 的自动转换。

### 5.3 base curve 的元数据（对照）

```
src/iop/basecurve.c:368-376
     _("apply a view transform based on personal or camera maker look,\n"
       "for corrective purposes, to prepare images for display"),
     _("corrective"),
     _("linear, RGB, display-referred"),
     _("non-linear, RGB"),
     _("non-linear, RGB, display-referred"));
```

```
src/iop/basecurve.c:388-393
dt_iop_colorspace_type_t default_colorspace(dt_iop_module_t *self,
                                            dt_dev_pixelpipe_t *pipe,
                                            dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_RGB;
}
```

### 5.4 colorout 做了什么（OETF / 编码）

```
src/iop/colorout.c:46
DT_MODULE_INTROSPECTION(5, dt_iop_colorout_params_t)
```

```
src/iop/colorout.c:66-71
typedef struct dt_iop_colorout_params_t
{
  dt_colorspaces_color_profile_type_t type; // $DEFAULT: DT_COLORSPACE_SRGB
  char filename[DT_IOP_COLOR_ICC_LEN];
  dt_iop_color_intent_t intent; // $DEFAULT: DT_INTENT_PERCEPTUAL
} dt_iop_colorout_params_t;
```

```
src/iop/colorout.c:82-88
const char **description(dt_iop_module_t *self)
{
  return dt_iop_set_description(self, _("convert pipeline reference RGB to any display RGB\n"
                                        "using color profiles to remap RGB values"),
                                      _("mandatory"),
                                      _("linear or non-linear, Lab, display-referred"),
                                      _("defined by profile"),
                                      _("non-linear, RGB or Lab, display-referred"));
}
```

```
src/iop/colorout.c:103-131
dt_iop_colorspace_type_t default_colorspace(dt_iop_module_t *self,
                                            dt_dev_pixelpipe_t *pipe,
                                            dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_LAB;
}

dt_iop_colorspace_type_t input_colorspace(dt_iop_module_t *self,
                                          dt_dev_pixelpipe_t *pipe,
                                          dt_dev_pixelpipe_iop_t *piece)
{
  return IOP_CS_LAB;
}

dt_iop_colorspace_type_t output_colorspace(dt_iop_module_t *self,
                                           dt_dev_pixelpipe_t *pipe,
                                           dt_dev_pixelpipe_iop_t *piece)
{
  int cst = IOP_CS_RGB;
  if(piece)
  {
    const dt_iop_colorout_data_t *const d = piece->data;
    if(d->type == DT_COLORSPACE_LAB) cst = IOP_CS_LAB;
  }
  else
  {
    dt_iop_colorout_params_t *p = self->params;
    if(p->type == DT_COLORSPACE_LAB) cst = IOP_CS_LAB;
  }
  return cst;
}
```

**OETF/TRC 的来源**：从输出 ICC profile 取矩阵 + 每通道 tone curve LUT（`LUT_SAMPLES = 0x10000`），
再在像素上应用：

```
src/iop/colorout.c:44
#define LUT_SAMPLES 0x10000
```

```
src/iop/colorout.c:52
  float lut[3][LUT_SAMPLES];
```

```
src/iop/colorout.c:700-703
  /* get matrix from profile, if softproofing or high quality exporting always go xform codepath */
  if(d->mode != DT_PROFILE_NORMAL || force_lcms2
     || dt_colorspaces_get_matrix_from_output_profile(output, d->cmatrix,
                                                      d->lut[0], d->lut[1], d->lut[2], LUT_SAMPLES))
```

```
src/iop/colorout.c:361-403
static void _process_fastpath_apply_tonecurves(dt_iop_module_t *self,
                                              dt_dev_pixelpipe_iop_t *piece,
                                              void *const ovoid,
                                              const dt_iop_roi_t *const roi_out)
{
  const dt_iop_colorout_data_t *const d = piece->data;

  if(dt_is_valid_colormatrix(d->cmatrix[0][0]))
  {
    const size_t npixels = (size_t)roi_out->width * roi_out->height;
    float *const restrict out = (float *const)ovoid;
    // out is already converted to RGB from Lab.

    // do we have any lut to apply, or is this a linear profile?
    if((d->lut[0][0] >= 0.0f) && (d->lut[1][0] >= 0.0f) && (d->lut[2][0] >= 0.0f))
    { // apply profile
      DT_OMP_FOR()
      for(size_t k = 0; k < 4 * npixels; k += 4)
      {
        for(int c = 0; c < 3; c++)
        {
          out[k + c] = (out[k + c] < 1.0f) ? _lerp_lut(d->lut[c], out[k + c])
                                           : dt_iop_eval_exp(d->unbounded_coeffs[c], out[k + c]);
        }
      }
    }
```

```
src/iop/colorout.c:522-547
void process(dt_iop_module_t *self, dt_dev_pixelpipe_iop_t *piece, const void *const ivoid,
             void *const ovoid, const dt_iop_roi_t *const roi_in, const dt_iop_roi_t *const roi_out)
{
  if(!dt_iop_have_required_input_format(4 /*we need full-color pixels*/, self, piece->colors,
                                         ivoid, ovoid, roi_in, roi_out))
    return;
  const dt_iop_colorout_data_t *const d = piece->data;
  const size_t width = roi_out->width;
  const size_t height = roi_out->height;
  const size_t npixels = width * height;
  float *const restrict out = (float *)ovoid;

  if(d->type == DT_COLORSPACE_LAB)
  {
    dt_iop_image_copy_by_size(ovoid, ivoid, roi_out->width, roi_out->height, piece->colors);
  }
  else if(dt_is_valid_colormatrix(d->cmatrix[0][0]))
  {
    if(!_transform_cmatrix(d, out, (float*)ivoid, npixels))
      _process_fastpath_apply_tonecurves(self, piece, ovoid, roi_out);
  }
  else
  {
    _transform_lcms(d, out, (float*)ivoid, npixels);
  }
}
```

（`_transform_cmatrix` 见 `src/iop/colorout.c:471-487`；Lab→线性 RGB 的矩阵路径见
`src/iop/colorout.c:405-431`；带 tone curve 的路径见 `src/iop/colorout.c:433-469`；lcms 回退见
`src/iop/colorout.c:489`。）

**结论（§5）**：darktable 的 display encoding（OETF/TRC）只发生在 `colorout`，
由输出 ICC profile 决定（默认 sRGB）；它位于内置顺序表的最后一段（`{ {70.0f }, "colorout", 0}`，
数组位置在 `finalscale` 之后、`gamma` 之前）。三个 tone mapper 自身都不施加显示编码。

---

## 6. 汇总结论（含未找到项）

### 6.1 直接回答任务里的具体问题

1. **darktable 的 lut3d 是 display-referred 还是 scene-referred？它的输入假定是什么编码？**
   模块**元数据自称** input = `linear, RGB, display-referred`（`src/iop/lut3d.c:142`），
   但 `default_colorspace()` 只返回 `IOP_CS_RGB`（`src/iop/lut3d.c:161`），代码里没有任何域断言。
   实际输入编码由参数 `application color space` 决定：像素先由 work profile 转到该 profile
   （`src/iop/lut3d.c:1156-1157`），**默认 `DT_IOP_SRGB`**（`src/iop/lut3d.c:75`，gamma 编码 sRGB），
   查表后再转回（`src/iop/lut3d.c:1164-1165`）。所以：**LUT 查表发生在声明的 application color space，
   不是管线当前域**；默认假定 LUT 是按 gamma sRGB 制作的。

2. **3D LUT 在 tone mapping 之前还是之后？**
   默认 `v50_order` 的**数组顺序**为 `basecurve → filmic → sigmoid → agx → filmicrgb → spektrafilm →
   lut3d → colisa → tonecurve → ... → colorout`（`src/common/iop_order.c:384-394`、`416-419`），
   即 **LUT3D 在 tone mapping 之后、colorout 之前**。括号中的 `{ {36.0f }, "lut3d", 0}` 不参与排序：
   `_ioppr_reset_iop_order` 按列表位置重写 `iop_order`（`src/common/iop_order.c:1266-1279`），
   浮点值仅在旧 DB 迁移时使用（`src/common/iop_order.c:72-76`）。官方文档同结论
   （`content/module-reference/processing-modules/lut-3D.md:42`）。
   legacy（display-referred）顺序里则是 `basecurve(23) → ... → lut3d(56.5) → colorout(58)`
   （`src/common/iop_order.c:111`、`162-165`），同样是 basecurve 之后。

3. **`.cube` 的 DOMAIN_MIN/MAX 有没有真的被当成输入域使用？**
   **没有。** 值被 `strtod(token[1], NULL)` 丢弃（`src/iop/lut3d.c:790`、`802`），
   只用来断言必须等于 0 / 1，否则整文件拒绝（`return 0`）。查表输入被硬 clip 到 `[0,1]`
   （`src/iop/lut3d.c:257` + `src/common/math.h:73`；GPU 为 `data/kernels/lut3d.cl:35`）。
   文档也承认裁剪（`content/module-reference/processing-modules/lut-3D.md:36`）。

4. **各 tone mapper 各自输出到哪个域，是否内置了显示编码？**
   - sigmoid：元数据 output = `linear, RGB, display-referred`（`src/iop/sigmoid.c:207`）；无 OETF。
   - AgX：元数据 output = `linear, RGB, display-referred`（`src/iop/agx.c:60`）；曲线后 `powf(..., curve_gamma)`
     线性化（`src/iop/agx.c:1078-1082`）；无 OETF。
   - filmic rgb：元数据 output = `non-linear, RGB, display-referred`（`src/iop/filmicrgb.c:348`），
     但代码把 spline 的 gamma 空间 y 值再 `powf(..., output_power)` 线性化
     （`src/iop/filmicrgb.c:1805-1809`、`2184`），中间灰落到 0.1845；**代码行为是线性**，与元数据字符串不一致。
   - base curve：元数据 output = `non-linear, RGB, display-referred`（`src/iop/basecurve.c:375`）。
   - 三者的 `default_colorspace()` 全是 `IOP_CS_RGB`（`src/iop/sigmoid.c:224`、`src/iop/filmicrgb.c:365`、
     `src/iop/agx.c:78`、`src/iop/basecurve.c:392`）。
   - **显示编码（OETF/TRC）只由 `colorout` 施加**，来自输出 ICC profile
     （`src/iop/colorout.c:700-703`、`361-403`）。

### 6.2 明确"未找到"的条目

| 条目 | 结论 |
|---|---|
| `default_order` | `grep -rn "default_order" src/` **无输出**；本版本模块不声明 order。 |
| `dt_iop_module_so_t` 的 `default_colorspace` / `default_order` 数据成员 | **不存在**；`default_colorspace` 是通过 `#include "iop/iop_api.h"`（`src/develop/imageop.h:144-145`）注入的函数指针，声明为 `REQUIRED`（`src/iop/iop_api.h:100-104`）。 |
| `dt_iop_order_iccprofile_info_t` 中的 scene/display 字段 | **不存在**（`src/common/iop_profile.h:38-56`）。 |
| `DT_IOP_CS_*` 中的 scene-referred / display-referred 枚举 | **不存在**（`src/common/colorspaces.h:42-51` 仅 RAW/LAB/RGB/LCH/HSL/JZCZHZ）。 |
| `.cube` 输入域重映射代码 | **不存在**；见 §4.4。 |
| darktable 自身可推导的版本字符串 | **不存在于本 checkout**（`darktable_package_string` 构建时生成；shallow clone 无 tag）。仅 `README.md:155` 标注 `5.6.1 (stable)`、`src/common/darktable.h:203` 的 `DT_MODULE_VERSION 25`、`src/common/iop_order.c:31` 的 `DT_IOP_ORDER_VERSION 5`。 |
| 上游文档 URL `https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/lut3d/` | 本次 `web_fetch` 返回 **HTTP 404**；改以 dtdocs 仓库 `content/module-reference/processing-modules/lut-3D.md`（commit `79c10958aee76654dcac55a7c5e645a05621657b`）作为文档证据。 |

### 6.3 与 `dt_iop_module_so_t` 相关的"字段值"说明

任务要求列出 `dt_iop_module_so_t` 的字段值（`default_colorspace` / `default_order`）。
本版本该结构体**不含**这些字段（见 §4.2.1）。各模块提供的等价信息为：

| 模块 | `DT_MODULE_INTROSPECTION` 版本 | `default_group()` | `default_colorspace()` | `flags()` |
|---|---|---|---|---|
| sigmoid | `3` (`src/iop/sigmoid.c:34`) | `IOP_GROUP_TONE \| IOP_GROUP_TECHNICAL` (`:215-218`) | `IOP_CS_RGB` (`:224`) | `INCLUDE_IN_STYLES \| SUPPORTS_BLENDING \| ALLOW_TILING` (`:210-213`) |
| filmic rgb | `6` (`src/iop/filmicrgb.c:67`) | `IOP_GROUP_TONE \| IOP_GROUP_TECHNICAL` (`:351-354`) | `IOP_CS_RGB` (`:365`) | 同上 (`:356-359`) |
| AgX | `7` (`src/iop/agx.c:41`) | `IOP_GROUP_TONE \| IOP_GROUP_TECHNICAL` (`:69-72`) | `IOP_CS_RGB` (`:78`) | 同上 (`:64-67`) |
| lut3d | `3` (`src/iop/lut3d.c:48`) | `IOP_GROUP_COLOR \| IOP_GROUP_TECHNICAL` (`:152-155`) | `IOP_CS_RGB` (`:161`) | `INCLUDE_IN_STYLES \| SUPPORTS_BLENDING` (`:147-150`) |
| colorout | `5` (`src/iop/colorout.c:46`) | `IOP_GROUP_COLOR \| IOP_GROUP_TECHNICAL` (`:93-96`) | `IOP_CS_LAB` (`:107`) | `ALLOW_TILING \| ONE_INSTANCE \| WRITE_PIPECACHE_IN` (`:98-101`) |
| base curve | — | `IOP_GROUP_BASIC \| IOP_GROUP_TECHNICAL` (`src/iop/basecurve.c:378-381`) | `IOP_CS_RGB` (`:392`) | `SUPPORTS_BLENDING \| ALLOW_TILING` (`:383-386`) |

`default_order` 一栏：**所有模块均为"源码中未找到"**。

---

## 附：本次实际执行的关键 grep（可复现）

```
$ git -C /private/tmp/dtsrc log -1 --format=%H
92f6c282845943ad8f242c3dcb040a3c75c877b9

$ grep -rn "default_order" --include=*.c --include=*.h /private/tmp/dtsrc/src/
（无输出）

$ grep -n "lut3d\|filmicrgb\|sigmoid\|\"agx\"\|colorout\|basecurve" /private/tmp/dtsrc/src/common/iop_order.c
111:  { {23.0f }, "basecurve", 0},
146:  { {45.3f }, "sigmoid", 0},
147:  { {45.5f }, "agx", 0},
149:  { {46.5f }, "filmicrgb", 0},
163:  { {56.5f }, "lut3d", 0},
165:  { {58.0f }, "colorout", 0},
262:  { {44.0f }, "basecurve", 0},       // conversion from scene-referred to display referred, reverse-engineered
265:  { {45.3f }, "sigmoid", 0},
266:  { {45.5f }, "agx", 0},
267:  { {46.0f }, "filmicrgb", 0},       // same, upgraded
269:  { {36.0f }, "lut3d", 0},           // apply a creative style or film emulation, possibly non-linear
294:  { {70.0f }, "colorout", 0},
385:  { {44.0f }, "basecurve", 0 },     // conversion from scene-referred to display referred, reverse-engineered
388:  { { 45.3f }, "sigmoid", 0},
389:  { {45.5f }, "agx", 0},
390:  { { 46.0f }, "filmicrgb", 0 },     // same, upgraded
392:  { { 36.0f }, "lut3d", 0 },         // apply a creative style or film emulation, possibly non-linear
418:  { { 70.0f }, "colorout", 0 },
509:  { { 44.0f }, "basecurve", 0 },     // conversion from scene-referred to display referred, reverse-engineered
512:  { {45.5f }, "agx", 0},
513:  { { 45.3f }, "sigmoid", 0},
514:  { { 46.0f }, "filmicrgb", 0 },     // same, upgraded
516:  { { 36.0f }, "lut3d", 0 },         // apply a creative style or film emulation, possibly non-linear
543:  { { 70.0f }, "colorout", 0 },
635:  { { 44.0f }, "basecurve", 0 },     // conversion from scene-referred to display referred, reverse-engineered
638:  { { 45.3f }, "sigmoid", 0},
639:  { {45.5f }, "agx", 0},
640:  { { 46.0f }, "filmicrgb", 0 },     // same, upgraded
642:  { { 36.0f }, "lut3d", 0 },         // apply a creative style or film emulation, possibly non-linear
670:  { { 70.0f }, "colorout", 0 },
763:  _insert_before_after(iop_order_list, "filmicrgb", "sigmoid", TRUE);
764:  _insert_before_after(iop_order_list, "filmicrgb", "agx", TRUE);

$ grep -n "DOMAIN_MIN\|DOMAIN_MAX" /private/tmp/dtsrc/src/iop/lut3d.c
788:      else if(strcmp("DOMAIN_MIN", token[0]) == 0)
800:      else if(strcmp("DOMAIN_MAX", token[0]) == 0)

$ grep -n "#define CLIP" /private/tmp/dtsrc/src/common/math.h
73:#define CLIP(x) (((x) >= 0) ? ((x) <= 1 ? (x) : 1) : 0)
```
