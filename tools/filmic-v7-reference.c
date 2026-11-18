/* Test I/O adapter only. reference.inc is generated from pinned Git blobs. */
#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include "common/gamut_mapping.h"

/* Only the module fields read by _compute_output_power and commit_params. */
typedef void dt_iop_params_t;
typedef void dt_dev_pixelpipe_t;
typedef struct { void *data; } dt_dev_pixelpipe_iop_t;
typedef struct { struct { float Min, Max; } Float; } float_property;
typedef struct { const float_property *(*get_f)(const char *); } introspection;
typedef struct { const introspection *so; } dt_iop_module_t;

#include "reference.inc"
#include "colorout.inc"

static const float_property *get_power_limits(const char *field)
{
  static const float_property limits = {{ POWER_MIN, POWER_MAX }};
  if(strcmp(field, "output_power") != 0) abort();
  return &limits;
}

static int load_matrix_profile(dt_iop_order_iccprofile_info_t *info, cmsHPROFILE profile)
{
  if(!profile) return 1;
  /* The upstream input helper requires LUT buffers even when only its matrix is used. */
  float trc[3][2];
  int error = dt_colorspaces_get_matrix_from_input_profile(profile, info->matrix_in,
                                                           trc[0], trc[1], trc[2], 2)
           || dt_colorspaces_get_matrix_from_output_profile(profile, info->matrix_out, NULL, NULL, NULL, 0);
  cmsCloseProfile(profile);
  return error;
}

int render_v7(int target, const float *input, float *output, size_t width, size_t height,
              const float *overrides, float *effective, float *derived, float *to_target)
{
  if(((uintptr_t)input | (uintptr_t)output) & 15u) return 1;
  if(target < 0 || target > 3) return 1;
  dt_iop_filmicrgb_params_t p = defaults;
  apply_overrides(&p, overrides);
  if((p.custom_grey && p.grey_point_source <= 0.0f)
     || p.black_point_target >= p.white_point_target) return 1;
  static const introspection meta = {get_power_limits};
  dt_iop_module_t module = {&meta};
  if(p.auto_hardness) _compute_output_power(&module, &p);

  dt_iop_filmicrgb_data_t data = {0};
  dt_dev_pixelpipe_iop_t piece = {&data};
  commit_params(&module, &p, NULL, &piece);
  const dt_iop_filmic_rgb_spline_t *s = &data.spline;
  const float black = powf(s->y[0], data.output_power);
  const float white = powf(s->y[4], data.output_power);

  const float params[] = {p.grey_point_source, p.black_point_source, p.white_point_source,
    p.grey_point_target, p.black_point_target, p.white_point_target, p.output_power, p.latitude,
    p.contrast, p.saturation, p.balance, p.auto_hardness, p.custom_grey, p.shadows, p.highlights};
  if(n_active != sizeof(params) / sizeof(*params)) abort();
  memcpy(effective, params, sizeof(params));
  const float tone[] = {data.grey_source, data.black_source, data.dynamic_range, data.saturation, black, white,
    exp_tonemapping_v2(0.f, data.grey_source, data.black_source, data.dynamic_range),
    exp_tonemapping_v2(1.f, data.grey_source, data.black_source, data.dynamic_range),
    s->latitude_min, s->latitude_max};
  memcpy(derived, tone, sizeof(tone));
  memcpy(derived + 10, s->x, sizeof(s->x));
  memcpy(derived + 15, s->y, sizeof(s->y));
  const float *coeff[] = {s->M1, s->M2, s->M3, s->M4, s->M5};
  for(size_t i = 0; i < 5; ++i) memcpy(derived + 20 + i * 3, coeff[i], 3 * sizeof(float));
  for(size_t i = 0; i < 35; ++i)
    if(!isfinite(derived[i])) return 1;

  dt_iop_order_iccprofile_info_t work = {0}, export = {0};
  if(load_matrix_profile(&work, _colorspaces_create_linear_rec2020_rgb_profile())) return 1;
  if(target == 0 && load_matrix_profile(&export, _colorspaces_create_srgb_profile(TRUE))) return 1;
  if(target == 1 && load_matrix_profile(&export, _colorspaces_create_display_p3_rgb_profile())) return 1;
  if(target >= 2) export = work;
  dt_colormatrix_t matrix = {0};
  dt_colormatrix_mul(matrix, export.matrix_out, work.matrix_in);
  pack_3xSSE_to_3x3(matrix, to_target);
  for(size_t i = 0; i < width * height * 4; ++i)
    if(!isfinite(input[i])) return 1;
  filmic_v5(input, output, &work, target == 3 ? NULL : &export, &data, *s, width, height, black, white);
  for(size_t i = 0; i < width * height * 4; i += 4)
  {
    for(size_t c = 0; c < 3; ++c)
      if(!isfinite(output[i + c])) return 1;
    output[i + 3] = input[i + 3];
  }
  return 0;
}

typedef struct {
  dt_colormatrix_t work_to_xyz_transposed;
  dt_iop_colorout_data_t data;
} colorout_reference;

void *create_colorout(int target)
{
  if(target != 0 && target != 1) return NULL;
  const size_t bytes = (sizeof(colorout_reference) + 63u) & ~(size_t)63u;
  colorout_reference *ref = aligned_alloc(64, bytes);
  if(!ref) return NULL;
  memset(ref, 0, sizeof(*ref));

  dt_iop_order_iccprofile_info_t work = {0};
  cmsHPROFILE profile = target == 0 ? _colorspaces_create_srgb_profile(TRUE)
                                   : _colorspaces_create_display_p3_rgb_profile();
  dt_iop_colorout_data_t *d = &ref->data;
  const int error = load_matrix_profile(&work, _colorspaces_create_linear_rec2020_rgb_profile())
    || dt_colorspaces_get_matrix_from_output_profile(profile, d->cmatrix,
                                                      d->lut[0], d->lut[1], d->lut[2], LUT_SAMPLES);
  if(profile) cmsCloseProfile(profile);
  if(error || d->lut[0][0] < 0.f || d->lut[1][0] < 0.f || d->lut[2][0] < 0.f)
  {
    free(ref);
    return NULL;
  }
  transpose_3xSSE(work.matrix_in, ref->work_to_xyz_transposed);
  init_colorout_extrapolation(d);
  return ref;
}

void run_colorout(const void *handle, const float *work, float *lab, float *linear, float *encoded, size_t count)
{
  const colorout_reference *ref = handle;
  for(size_t k = 0; k < count; ++k)
  {
    dt_aligned_pixel_t xyz;
    dt_apply_transposed_color_matrix(work + 4 * k, ref->work_to_xyz_transposed, xyz);
    dt_XYZ_to_Lab(xyz, lab + 4 * k);
  }
  _transform_cmatrix_linear(&ref->data, linear, lab, count);
  memcpy(encoded, linear, count * 4 * sizeof(float));
  _process_fastpath_apply_tonecurves(&ref->data, encoded, count);
}

void free_colorout(void *handle)
{
  free(handle);
}
