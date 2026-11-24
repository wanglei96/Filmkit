# Spektrafilm 0.14.40: memory correction and gallery investigation

Date: 2026-11-23. Device: Vivo V2509A, serial 10AG6Y0DEH009DA.

## Delivery Status

- 0.14.39 (836) failed motion acceptance: process exit at 10:11:34 was
  `LOW_MEMORY`, foreground RSS about 5.7 GB. No photo was saved. A user motion
  gesture is not established as the cause.
- 0.14.40 (837) built, signed, verified and installed. Both acceptance captures
  saved successfully; the user reported no crash. Visual color/exposure and
  subjective preview smoothness were not explicitly confirmed. Capture peak
  memory was not measured successfully, so full acceptance remains incomplete.
- APK SHA-256: `5ea570eea790d4eae96cce1a7bce37ba990b73a0f41d52f6ea7514aaad8aab82`.
- Packaged arm64 engine matches the build:
  `ff7694c6e7d4647dc88a5dbd30921f1be727d73c86b8fd83a635c8b43ca001fc`.
- Signature v2/v3 verified, DEX contains 0.14.40, versionCode 837, and the
  Spektrafilm load, RAW burst, gainmap-read and Ultra HDR encode JNI symbols exist.
- Root APKs retain 0.14.40 and baseline 0.14.38. Older root packages were copied,
  byte-compared and SHA-256 checked in `test_temp/spektrafilm-perf-01440-delivery/older-apks/`
  before removing the duplicate root files. No phone originals or app data cleared.

## Changes

- Release dead FP32 highlight/chroma covariance textures before allocating the
  full FP64 spectral image. Original covariance and filter slot 3 alias, so slot
  3 must not be deleted twice. Reset all handles, dimensions and model validity.
- Real 12.5 MP replay logs show 1,203,240,960 bytes of released texture storage.
  This is calculated allocation size, not a measured app RSS reduction.
- Retain the final scene texture and full HDR pipeline. Next captures rebuild
  covariance normally. No sample, kernel, precision or frame-count reduction.
- Other changes carried by this delivery: per-render exact spatial scratch reuse,
  row-parallel output packing, bounded two-size preview allocation reuse, and
  finer GPU submission windows for heavy striped passes.

## Quality Gates

- Full real-RAW A/B: Gold 200 / Endura and Provia 100F direct, old/new/new/old.
  RGBA, gainmap and encoded SDR JPEG byte-identical for every comparison.
  `test_temp/spektrafilm-perf-01440-final-ab-rerun/`
- Same renderer: repeated identical input, eight covariance/noise/PC3D cases,
  eight dimensions/rotation/noise-strength cases, both recipes. Old/new RGBA,
  gainmap and JPEG byte-identical; repeated identical input also self-identical.
  `test_temp/spektrafilm-perf-01440-repeated/`
- Full/scaled preview, both existing preview engines, two rounds: byte-identical.
  Release and new renderer recreation succeeds. Gold/Provia packed 10-bit RGBA
  and gainmap are byte-identical. `test_temp/spektrafilm-perf-01440-extended/`
- JVM: 851 tests, no failures/errors, 2 skipped.
- Prior unchanged spectral checks: 147 recipes, max absolute FP64 reference
  difference 3.4265570762748165e-10; filter regression and integer output checks
  passed. Evidence: `test_temp/spektrafilm-perf-01439-check/`.
- Test runner now validates GL errors, packed opaque alpha and unpacked 10-bit
  image range, and supports repeat captures. The A/B script runner basename
  mismatch was corrected before the successful .40 tests.

## Timing Interpretation

Real-RAW CLI timings, milliseconds, baseline .38 versus .40:

| Recipe | Old | New | New | Old |
| --- | ---: | ---: | ---: | ---: |
| Gold / Endura | 8848 | 6199 | 5748 | 5916 |
| Provia direct | 4919 | 4667 | 4794 | 4986 |

First Gold old run was cold. Warm Gold does not establish an overall speed gain;
Provia difference is modest, about 4.5 percent at these medians. These are not
shutter-to-save times and do not isolate thermal/scheduler effects. The proven
benefit of the new correction is shorter allocation lifetime with identical
output. The two real captures below passed the no-crash/save check, but do not
establish longer-term stability or a measured peak-RSS reduction.

## Real Camera Acceptance

On 2026-11-23, the user completed Gold / Endura followed by Provia direct after
being asked to move the phone during the second capture, and reported no crash.

| Recipe | Shutter | Saved | Shutter to save | Spectral render |
| --- | --- | --- | ---: | ---: |
| Gold / Endura | 11:06:07.190 | 11:06:18.781 | 11.591 s | 6.051 s |
| Provia direct | 11:06:39.666 | 11:06:48.198 | 8.532 s | 4.635 s |

- Both JPEG files exist on the phone at 3072 x 4080: Gold 8,487,620 bytes,
  Provia 8,104,359 bytes. Both saves report avgLuma 125; this scalar is not a
  substitute for visual quality acceptance.
- Each capture released 1,203,240,960 bytes of calculated covariance texture
  storage before spectral processing. This is not a measured RSS delta.
- Exit history contains no new exit for these captures. Its latest entry is
  the 10:44:38 package update; the previous LOW_MEMORY exit remains 10:11:34.
- Preview timing windows overlapping the front chain still show stalls:
  Gold about 14-27 fps with a maximum frame interval of 384 ms; Provia about
  21-28 fps with a maximum frame interval of 314 ms. During the subsequent
  CPU spectral phase, windows return to roughly 28-30 fps (Gold) and 30 fps
  (Provia). Therefore do not describe the entire capture as stutter-free.
- The memory sampler's first records are around 11:07, after both saves. It
  missed both capture peaks; post-capture samples cannot establish peak memory
  or quantify the actual RSS benefit. No repeat shot requested just to replace
  this missed measurement.
- Evidence: `test_temp/spektrafilm-perf-01439-baseline/` contains
  `after-01440-acceptance-engine.log`, `after-01440-exit-info.txt` and
  `acceptance-01440-memory.jsonl`.

## System Gallery Darkening

User observations: individual photos initially normal, become globally dark
after repeated system-gallery viewing; sharing, our viewer and file-manager
viewing remain normal. This is not merely missing highlight enhancement.

Affected original: `FILMKIT_20261121-161306_KodakGold200KodakVision2383.jpg`.
SHA-256 before and during the dark state:
`23579f5ec0a3ab651dc78ae99066cb15ff32b0e362246da687dba29d7646aa61`.

- Original file is unchanged, SDR base visually normal, MPF association valid.
- Official production libultrahdr decodes at display boosts 1/2/4 successfully.
  Mean linear HDR/base ratios 1.00009 / 1.04048 / 1.08325. No nonblack pixel
  darkened over 10 percent. Actual maximum encoded gain about 1.26, not 4.
- On-device BitmapFactory full/half and ImageDecoder full/fit all retain the
  correct gainmap. Three repeats of each yield stable base/gainmap CRCs and
  metadata. Thus old comments claiming those APIs necessarily drop the gainmap
  are false on this device; not used as evidence for an app change.
- User screenshots show approximately quarter linear brightness in the dark
  sky region. The bright screenshot is our app, not another system-gallery mode.
- Dark system-gallery capture: requested HDR ratio 4, actual 4.00681, display
  brightness 284.7195 nits, SDR white 71.05885 nits. Alone this is not proof of
  the darkening mechanism.
- Restarting only `com.vivo.gallery` did not restore the original. No data/cache
  clearing performed.
- A byte-identical diagnostic copy, new MediaStore ID 1000061781, displayed
  normally in the same system gallery with the same display brightness and HDR
  ratio. Original ID is 1000061649. The copy retains Nov 21 EXIF date, so it is
  sorted there, not under today's shots.
- Subsequent direct VIEW intent alternation also captured bright frames of the
  original. This is state-dependent, not established permanent cache corruption.
  Short capture intervals may include loading/SDR fallback; a stable reappearance
  of the fault must still be traced.
- No production HDR/exposure alteration made for this issue. Do not disable HDR,
  alter gain metadata, overwrite originals or clear gallery data to mask it.

Evidence: `test_temp/spektrafilm-perf-01439-baseline/` contains the original,
official/platform probes, dark/copy/reopen screenshots, SurfaceFlinger and
display/window dumps. Diagnostic copy remains in `Pictures/` for comparison.

Next: address remaining front-chain preview stalls and spectral processing
cost without changing output; obtain visual acceptance and valid capture-peak
memory data when the next test is needed. Reproduce the gallery dark transition
on the same MediaStore item and distinguish per-item cache, decoder and render
state before selecting any compatibility change.
