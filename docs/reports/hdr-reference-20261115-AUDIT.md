# HDR reference audit — 2026-11-15

Status: 0.13.80(788) built and signed; device was disconnected before install. 0.13.79 daylight gain checks passed. Latest 0.13.79 sample confirmed SDR-origin magenta edge; strong-light gray-band acceptance remains pending.

## Corrections to previous conclusions

- Ultra HDR specifies reconstruction between SDR/HDR renditions. It does not require daytime gain=1, a Filmic-white threshold, or a 1.414 maximum.
- A short sensor frame's exposure normalization does not define HDR display luminance or a physical display boost ceiling.
- A constant SDR patch and spatially varying gain are valid: gain maps can restore highlight detail compressed out of SDR. This condition alone does not prove a gray-band bug.
- Do not infer PC's exposure ratio from measured output boost divided by sqrt(2). Curve output and PGTM also affect the result.

## Confirmed implementation differences

1. PC RawEngineTonePass hdrBaseCurveFragmentShader samples actual applyEngineTone(vec3(x)) for x in [0,1]. FilmKit computeHdrCurveExtension instead evaluates its CPU green scalar at sceneWhite*x. Those coordinates differ; dividing pixel coordinates by sceneWhite does not make the resulting HDR extension identical.
2. PC computes an extended/base neutral-response ratio using max prepared engine RGB. RawOutputPass applies that scalar to the finalized SDR colors. FilmKit substitutes max(actual film luma, extended short-frame luma), which mixes different responses.
3. FilmKit independently reprocesses frames[short_idx] without the MFNR displacement field. The SDR fused input uses that field. Raw short-frame texture therefore is not a pixel-aligned gain-map reference.
4. FilmKit assumes a CPU Filmic green curve for every engine; PC measures the selected engine's actual tone and gamut response on GPU, sanitizes it and includes half-float uncertainty in tangent selection.
5. passFilm can internally replace its source through highlight reconstruction. Its caller's film_src is not automatically that actual source.

## Required implementation/validation

- Same prepared scene source and geometry for SDR and HDR coordinates, including exposure and local tone preparation.
- Measure selected engine neutral response, preserve PC curve domain, monotonicization, conservative join, quadratic fallback and endpoint tangent.
- Preserve final SDR colors/detail and apply continuous scalar gain; do not let output sharpening or LUT become a new HDR residual.
- Do not retune SDR shoulder just to hide gain-map artifacts.
- Test both registered engines, EV positive/negative/zero, synthetic ramps and high-contrast edges. Verify floating target versus encoded/decoded reconstruction.
- Camera acceptance requires daylight highlights and lamp edges. Device was disconnected at audit time; absence of camera evidence must not be reported as a pass.

## Independent mathematical check

`python3 tools/check-hdr-reference.py` checks PC curve solve, monotonicity, join continuity, white derivative, and quantized gain-map reconstruction of an SDR plateau. Maximum plateau reconstruction relative error is 0.5447%; no brightness reversal is introduced. This is a mathematical check, not proof that the camera renderer uses that pipeline.

## Implemented in 0.13.79

- Film shader mode 0 preserves normal output; mode 1 measures 1024 neutral selected-engine tone/gamut responses; mode 2 captures the actual post-HR/exposure/local prepared RGB.
- Sanitized GPU curve and conservative half-float-aware PC solve replace the CPU green-only spline surrogate. Both Filmic and Sigmoid are measured from their real programs.
- Gain map evaluates the continuous extended/base curve ratio in raw max-RGB engine coordinates on the same prepared source. The ratio multiplies final SDR linear luminance before downsampling; independent unregistered short-frame input is removed.
- Half-float readback has an explicit RGBA8 float-bit packing fallback; failures prevent stale gain-map delivery and log SDR fallback.
- `python3 tools/check-hdr-reference.py --gpu`: SDR gray-ramp baseline bit-identical; prepared source EV -2/0/+2 passes; both engine probes pass; production gainmap versus independent oracle max relative error 1.1826% including dither. `bash tools/validate-shaders.sh` passes.
- Release build, debug signature verification, manifest 787/0.13.79, DEX version string and gainmap/probe JNI/native symbols verified. Installed device reported 787/0.13.79. Three daylight captures produced p99 boosts 1.95x, 1.29x and 3.08x; strongest capture reconstructed HDR luma >=1.0 for 14.11% of pixels. All three logged hdr.probe_ready with direct half-float readback.
- Latest sample `FILMKIT_20261115-112450.jpg`: SDR base had 277 magenta-edge candidates; candidate purple strips were 16–18 8-bit luminance levels below adjacent neutral pixels, and their local boost was only 1.16–1.21x; zero overlapped the gain=4 region. This proves SDR-origin fringe, not gainmap color creation. The production defringe detector had an inconsistent scale: Apply used `min(R-G,B-G)` but Detect variance and cavg used `B-R`; it also used Rec.709 luminance in a Rec.2020 working domain.
- 0.13.80 fixes the detector scale to magenta magnitude and uses BT.2020 luminance/gradient in defringe. Synthetic production-shader test: R=B>G edge mag 0.4501→0.0542, luminance preserved; neutral/warm/blue/green controls unchanged. 0.13.80 signature/version/JNI checks pass.
- 0.13.80 real-device captures at 12:30:43/12:31:07 show gainmap probe ready and purple-edge improvement. Latest base/gain analysis: lamp-center SDR Y≈245–248 with gain≈4x and continuous HDR Y≈3.70; no center gray band in the file. Visible edge transitions remain in SDR itself (latest top edge base Y≈215, bottom edge≈240; edge gain≈0.78), so HDR gainmap is not the source of the edge shape. Device disconnected before display screenshot; UI-vs-file gray-band acceptance remains open.
- 0.13.84 recapture made rings continuous; 0.13.85 returned multiple arcs; 0.13.86 continuous valid-short inpaint still failed acceptance and purple fringe returned. 0.13.87 changes the measured short-frame branch to direct registered short values for complete valid 2x2 blocks, eliminating the 5x5 bilateral edge mix. It is built, signed and installed; final gray-band recapture remains pending.

## References

- https://github.com/google/libultrahdr — SDR/HDR rendition quotient, API 0 tone maps HDR to SDR before gain generation.
- https://github.com/aosp-mirror/platform_frameworks_base/blob/master/graphics/java/android/graphics/Gainmap.java — display headroom weighting and reconstruction.
- Local reference checkout /tmp/pc-full: raw/RawEngineTonePass.kt, RawRenderingEngineToneAlgorithm.kt, RawHdrReferenceMath.kt, RawOutputPass.kt; hdr/GpuReferenceGainmapProducer.kt and RawGainmapMath.kt.
