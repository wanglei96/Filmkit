/* Offline test driver. Compile against the pinned darktable build, not FilmKit. */
#include "common/darktable.h"
#include "common/iop_profile.h"
#include "develop/develop.h"
#include "develop/imageop.h"
#include "develop/pixelpipe_hb.h"

#include <errno.h>
#include <glib/gstdio.h>
#include <limits.h>

static dt_iop_module_so_t *find_module(const char *op)
{
  for(GList *it = darktable.iop; it; it = it->next)
  {
    dt_iop_module_so_t *so = it->data;
    if(!strcmp(so->op, op) && so->have_introspection) return so;
  }
  return NULL;
}

static int dimension(const char *value)
{
  char *end;
  errno = 0;
  const long n = strtol(value, &end, 10);
  return errno || end == value || *end || n < 1 || n > INT_MAX ? 0 : (int)n;
}

static gboolean write_rgb(const char *path, const float *pixels, size_t n)
{
  FILE *file = g_fopen(path, "wbx");
  if(!file) return FALSE;
  const gboolean written = fwrite(pixels, sizeof(float), n, file) == n;
  return fclose(file) == 0 && written;
}

int main(int argc, char **argv)
{
  if(argc < 9 || strcmp(argv[8], "--"))
  {
    fprintf(stderr, "usage: %s {srgb|p3} width height effective_power input tone encoded -- darktable-options\n", argv[0]);
    return 2;
  }
  const dt_colorspaces_color_profile_type_t target = !strcmp(argv[1], "srgb") ? DT_COLORSPACE_SRGB
    : !strcmp(argv[1], "p3") ? DT_COLORSPACE_DISPLAY_P3 : DT_COLORSPACE_NONE;
  const int width = dimension(argv[2]), height = dimension(argv[3]);
  char *end;
  const float power = strtof(argv[4], &end);
  if(target == DT_COLORSPACE_NONE || !width || !height || end == argv[4] || *end
     || !isfinite(power) || power <= 0.f || (size_t)width > SIZE_MAX / height / 16u) return 2;
  const size_t n = (size_t)width * height * 4;
  const char *input_path = argv[5], *tone_path = argv[6], *encoded_path = argv[7];
  argv[8] = argv[0];
  if(dt_init(argc - 8, argv + 8, FALSE, FALSE, NULL)) return 1;

  dt_iop_module_so_t *filmic = find_module("filmicrgb");
  dt_iop_module_so_t *colorout = find_module("colorout");
  if(!filmic || !colorout) return 1;
  dt_develop_t dev = {0};
  char profile_filename[] = "";
  dt_dev_pixelpipe_t pipe = { .type = DT_DEV_PIXELPIPE_EXPORT, .icc_type = target,
                              .icc_intent = DT_INTENT_PERCEPTUAL, .icc_filename = profile_filename };
  pipe.work_profile_info = dt_ioppr_add_profile_info_to_list(&dev, DT_COLORSPACE_LIN_REC2020, "",
                                                           DT_INTENT_PERCEPTUAL);
  if(!pipe.work_profile_info || pipe.work_profile_info->nonlinearlut != 0) return 1;
  dt_iop_module_t tone_module = { .so = filmic, .dev = &dev };
  dt_iop_module_t output_module = { .so = colorout, .dev = &dev };
  g_strlcpy(tone_module.op, filmic->op, sizeof(tone_module.op));
  g_strlcpy(output_module.op, colorout->op, sizeof(output_module.op));
  dt_iop_default_init(&tone_module);
  dt_iop_default_init(&output_module);
  dt_introspection_field_t *p = filmic->get_f("output_power");
  if(!p || p->header.type != DT_INTROSPECTION_TYPE_FLOAT || power < p->Float.Min || power > p->Float.Max)
    return 1;
  // The recorded power is after the upstream auto-hardness calculation.
  memcpy((char *)tone_module.default_params + p->header.offset, &power, sizeof(power));

  dt_dev_pixelpipe_iop_t tone_piece = { .pipe = &pipe, .colors = 4, .iscale = 1.f };
  dt_dev_pixelpipe_iop_t output_piece = { .pipe = &pipe, .colors = 4, .iscale = 1.f };
  colorout->init_pipe(&output_module, &pipe, &output_piece);
  colorout->commit_params(&output_module, output_module.default_params, &pipe, &output_piece);
  if(pipe.colorout_encoding.type != target || !pipe.output_profile_info
     || pipe.output_profile_info->type != target) return 1;
  filmic->init_pipe(&tone_module, &pipe, &tone_piece);
  filmic->commit_params(&tone_module, tone_module.default_params, &pipe, &tone_piece);

  const dt_iop_roi_t roi = { .width = width, .height = height, .scale = 1.f };
  float *input = dt_alloc_align_float(n), *tone = dt_alloc_align_float(n), *lab = dt_alloc_align_float(n);
  if(!input || !tone || !lab) return 1;
  FILE *file = g_fopen(input_path, "rb");
  if(!file || fread(input, sizeof(float), n, file) != n || fgetc(file) != EOF || ferror(file)) return 1;
  fclose(file);
  for(size_t i = 0; i < n; i++) tone[i] = NAN;
  filmic->process_plain(&tone_module, &tone_piece, input, tone, &roi, &roi);
  for(size_t i = 0; i < n; i++)
    if((i & 3) != 3 && !isfinite(tone[i])) return 1;
    else if((i & 3) == 3) tone[i] = input[i]; // RGB is under test; isolate unused alpha from file hashes.
  if(!write_rgb(tone_path, tone, n)) return 1;
  dt_iop_colorspace_type_t converted = IOP_CS_NONE;
  dt_ioppr_transform_image_colorspace(&output_module, tone, lab, width, height,
                                     IOP_CS_RGB, IOP_CS_LAB, &converted, pipe.work_profile_info);
  if(converted != IOP_CS_LAB) return 1;
  for(size_t i = 0; i < n; i++) tone[i] = NAN;
  colorout->process_plain(&output_module, &output_piece, lab, tone, &roi, &roi);
  for(size_t i = 0; i < n; i++)
    if((i & 3) != 3 && !isfinite(tone[i])) return 1;
    else if((i & 3) == 3) tone[i] = input[i];
  if(!write_rgb(encoded_path, tone, n)) return 1;

  filmic->cleanup_pipe(&tone_module, &pipe, &tone_piece);
  colorout->cleanup_pipe(&output_module, &pipe, &output_piece);
  dt_iop_default_cleanup(&tone_module);
  dt_iop_default_cleanup(&output_module);
  g_list_free_full(dev.allprofile_info, (GDestroyNotify)dt_ioppr_free_profile_info);
  dt_free_align(lab);
  dt_free_align(tone);
  dt_free_align(input);
  dt_cleanup();
  return 0;
}
