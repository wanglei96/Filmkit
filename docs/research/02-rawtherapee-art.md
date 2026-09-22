# RawTherapee / ART 色彩管线取证报告：DCP 顺序、Film Simulation、3D LUT

本报告为**独立源码调研**，每条结论都附逐字证据（本地源码给 `路径:行号`，规范给 URL + 页码 + 原文）。
所有代码/规范原文均按原样引用，未翻译。

---

## 0. 取证环境与仓库版本（commit hash）

| 仓库 | 来源 URL | commit hash | 取法 |
|---|---|---|---|
| RawTherapee | https://github.com/RawTherapee/RawTherapee | `6c4cb5964fcea91dd9bdef619d4825712e083cb8` | `git clone --depth 1 --filter=blob:none --sparse`，`git sparse-checkout set rtengine` |
| ART | https://github.com/artpixls/ART | `3bf5873c476cb1a12da10d3f59a829d64f3d1b1b` | `git clone --depth 1 --filter=blob:none`（完整） |
| RawPedia（RawTherapee **官方** wiki 源码） | https://github.com/RawTherapee/RawPedia | `142ec3433b232f821d8a286ef829f5a46df2cf11` | `git clone --depth 1` |
| Adobe DNG SDK（**第三方镜像**，非 Adobe 官方仓库） | https://github.com/emmcb/adobe-dng-sdk | `962a7028efdc8f8a651cf6335c3ec53053472339` | `git clone --depth 1 --filter=blob:none`；源码位于 `dng_sdk/source/` |
| Adobe DNG 规范 PDF 1.7.1.0 | https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf | — | 实测可下载：HTTP 200，`1,176,100` 字节，`PDF document, version 1.7, 126 pages` |

说明：
- 关于候选 URL：`agriggio/ART` 未验证；**`artpixls/ART` 实测有效**（clone 成功，见上表）。
- `adobe/dng-sdk`、`adobe/dng_sdk` 在 GitHub 上**均不存在**（`remote: Repository not found.`），因此改用上面的第三方镜像；镜像内含完整 `dng_sdk/source`（`dng_render.cpp`、`dng_camera_profile.cpp` 等）。镜像的可信度风险已在此显式标注：它是第三方搬运，不应视为 Adobe 官方发布物。
- 官方文档 PDF 的 404 陷阱：`.../dng_spec_1_7_1_0.pdf`（全小写）返回 404；可用的是 `.../photoshop/pdf/DNG_Spec_1_7_1_0.pdf`（大写 `DNG_Spec`）。
- 本机 `web_search` 工具不可用（`DeepSeek search has no API key for "DEEPSEEK_API_KEY"`），全部检索改用 `curl` + GitHub API 完成。
- `rawpedia.rawtherapee.com` 与 `web.archive.org` 在本机网络下**不可达**（连接超时）。因此官方 wiki 内容改从**其官方源码仓库 `RawTherapee/RawPedia`** 取原文（Hugo 站点，正文在 `content/`），下文的行号均指该仓库文件。

---

## 1. DCP（DNG Camera Profile）的应用顺序

### 1.1 RawTherapee 的 `dcp.cc`：实现顺序（逐字）

RawTherapee 的 DCP 应用被拆成**两个阶段**：`DCPProfile::apply`（step 1，矩阵 + HueSatMap）与 `DCPProfile::step2ApplyTile`（step 2，LookTable + ToneCurve）。

#### (a) step 1：`DCPProfile::apply`，`rtengine/dcp.cc:1404`

`rtengine/dcp.cc:1404-1424`：

```cpp
void DCPProfile::apply(
    Imagefloat* img,
    int preferred_illuminant,
    const Glib::ustring& working_space,
    const ColorTemp& white_balance,
    const Triple& pre_mul,
    const Matrix& cam_wb_matrix,
    bool apply_hue_sat_map
) const
{

    const TMatrix work_matrix = ICCStore::getInstance()->workingSpaceInverseMatrix(working_space);

    const Matrix xyz_cam = makeXyzCam(white_balance, pre_mul, cam_wb_matrix, preferred_illuminant); // Camera RGB to XYZ D50 matrix

    const std::vector<HsbModify> delta_base = makeHueSatMap(white_balance, preferred_illuminant);

    if (delta_base.empty()) {
        apply_hue_sat_map = false;
    }
```

矩阵部分 `rtengine/dcp.cc:1439`：

```cpp
        // Apply the matrix part
```

LUT 分支 `rtengine/dcp.cc:1455-1467`、`1481`：

```cpp
    } else {
        // LUT available --> Calculate matrix for conversion raw>ProPhoto
        float pro_photo[3][3] = {};
...
        // Convert to ProPhoto and apply LUT
```

HueSatMap 的应用点 `rtengine/dcp.cc:1492-1509`：

```cpp
                // If point is in negative area, just the matrix, but not the LUT. This is checked inside Color::rgb2hsvdcp
                float h;
                float s;
                float v;

                if (LIKELY(Color::rgb2hsvdcp(newr, newg, newb, h , s, v))) {

                    hsdApply(delta_info, delta_base, h, s, v);

                    // RT range correction
                    if (h < 0.0f) {
                        h += 6.0f;
                    } else if (h >= 6.0f) {
                        h -= 6.0f;
                    }

                    Color::hsv2rgbdcp(h, s, v, newr, newg, newb);
                }
```

> 结论（证据）：相机矩阵（CCM）与白平衡（`pre_mul` + `cam_wb_matrix` 合成 `xyz_cam`）**先**合并成一个矩阵，随即转换到 **ProPhoto**，再应用 **HueSatMap**。

#### (b) step 2：`DCPProfile::step2ApplyTile`，`rtengine/dcp.cc:1571`

`rtengine/dcp.cc:1571-1601`（曝光缩放先于一切）：

```cpp
void DCPProfile::step2ApplyTile(float* rc, float* gc, float* bc, int width, int height, int tile_width, const DCPProfileApplyState& as_in) const
{

#define FCLIP(a) ((a)>0.f?((a)<65535.5f?(a):65535.5f):0.f)
#define CLIP01(a) ((a)>0?((a)<1?(a):1):0)

    float exp_scale = as_in.data->bl_scale;
...
                r *= exp_scale;
                g *= exp_scale;
                b *= exp_scale;
```

`rtengine/dcp.cc:1604-1612`（转 ProPhoto）：

```cpp
                if (as_in.data->already_pro_photo) {
                    newr = r;
                    newg = g;
                    newb = b;
                } else {
                    newr = as_in.data->pro_photo[0][0] * r + as_in.data->pro_photo[0][1] * g + as_in.data->pro_photo[0][2] * b;
                    newg = as_in.data->pro_photo[1][0] * r + as_in.data->pro_photo[1][1] * g + as_in.data->pro_photo[1][2] * b;
                    newb = as_in.data->pro_photo[2][0] * r + as_in.data->pro_photo[2][1] * g + as_in.data->pro_photo[2][2] * b;
                }
```

`rtengine/dcp.cc:1614-1650` —— **LookTable 在前、ToneCurve 在后**，这是本问题最直接的顺序证据：

```cpp
                // with looktable and tonecurve we need to clip
                if (as_in.data->apply_look_table || as_in.data->use_tone_curve) {
                    newr = max(newr, 0.f);
                    newg = max(newg, 0.f);
                    newb = max(newb, 0.f);
                }
                // newr = FCLIP(newr);
                // newg = FCLIP(newg);
                // newb = FCLIP(newb);

                if (as_in.data->apply_look_table) {
                    float cnewr = FCLIP(newr);
                    float cnewg = FCLIP(newg);
                    float cnewb = FCLIP(newb);

                    float h, s, v;
                    Color::rgb2hsvtc(cnewr, cnewg, cnewb, h, s, v);

                    hsdApply(look_info, look_table, h, s, v);
                    s = CLIP01(s);
                    v = CLIP01(v);

                    // RT range correction
                    if (h < 0.0f) {
                        h += 6.0f;
                    } else if (h >= 6.0f) {
                        h -= 6.0f;
                    }

                    Color::hsv2rgbdcp( h, s, v, cnewr, cnewg, cnewb);

                    setUnlessOOG(newr, newg, newb, cnewr, cnewg, cnewb);
                }

                if (as_in.data->use_tone_curve) {
                    tone_curve.Apply(newr, newg, newb);
                }
```

`baseline exposure` 的来源，`rtengine/dcp.cc:1519-1535`：

```cpp
void DCPProfile::setStep2ApplyState(const Glib::ustring& working_space, bool use_tone_curve, bool apply_look_table, bool apply_baseline_exposure, DCPProfileApplyState& as_out)
{
    as_out.data->use_tone_curve = use_tone_curve;
    as_out.data->apply_look_table = apply_look_table;
    as_out.data->bl_scale = 1.0;
...
    if (has_baseline_exposure_offset && apply_baseline_exposure) {
        as_out.data->bl_scale = powf(2, baseline_exposure_offset);
    }
```

#### (c) 两个阶段在管线中的调用点

step 1 由 `RawImageSource::convertColorSpace` 调用，`rtengine/rawimagesource.cc:3237-3251`：

```cpp
    if (dcpProf != nullptr) {
        // DCP processing
        const DCPProfile::Triple pre_mul_row = {
            pre_mul[0],
            pre_mul[1],
            pre_mul[2]
        };
        const DCPProfile::Matrix cam_matrix = {{
                {camMatrix[0][0], camMatrix[0][1], camMatrix[0][2]},
                {camMatrix[1][0], camMatrix[1][1], camMatrix[1][2]},
                {camMatrix[2][0], camMatrix[2][1], camMatrix[2][2]}
            }
        };
        dcpProf->apply(im, cmp.dcpIlluminant, cmp.workingProfile, wb, pre_mul_row, cam_matrix, cmp.applyHueSatMap);
        return;
    }
```

step 2 的 apply-state 在 `rtengine/rawimagesource.cc:1104` 准备：

```cpp
    dcpProf->setStep2ApplyState(cmp.workingProfile, cmp.toneCurve, cmp.applyLookTable, cmp.applyBaselineExposureOffset, as);
```

step 2 在 `ImProcFunctions::rgbProc` 内执行，`rtengine/improcfun.cc:2520-2522`：

```cpp
                if (dcpProf) {
                    dcpProf->step2ApplyTile(rtemp, gtemp, btemp, tW - jstart, tH - istart, TS, asIn);
                }
```

紧随其后就是 RawTherapee 自己的 Tone Curve，`rtengine/improcfun.cc:2544-2552` 与 `2591-2601`：

```cpp
                if (histToneCurveThr) {
                    for (int i = istart, ti = 0; i < tH; i++, ti++) {
                        for (int j = jstart, tj = 0; j < tW; j++, tj++) {

                            //brightness/contrast
                            float r = tonecurve[ CLIP(rtemp[ti * TS + tj]) ];
                            float g = tonecurve[ CLIP(gtemp[ti * TS + tj]) ];
                            float b = tonecurve[ CLIP(btemp[ti * TS + tj]) ];
```

```cpp
                if (hasToneCurve1) {
                    customToneCurve(customToneCurve1, curveMode, rtemp, gtemp, btemp, istart, tH, jstart, tW, TS, ptc1ApplyState);
                }
...
                if (hasToneCurve2) {
                    customToneCurve(customToneCurve2, curveMode2, rtemp, gtemp, btemp, istart, tH, jstart, tW, TS, ptc2ApplyState);
                }
```

**RawTherapee 的 DCP 三段相对顺序（源码事实）**：

```
raw → (白平衡 pre_mul + CCM，合成 xyz_cam)
    → HueSatMap（在 ProPhoto 中）            [step 1, dcp.cc:1404]
    → baseline exposure 缩放（bl_scale）      [step 2, dcp.cc:1598]
    → 转 ProPhoto                             [step 2, dcp.cc:1604-1612]
    → LookTable（rgb2hsvtc → hsdApply → hsv2rgbdcp）  [step 2, dcp.cc:1624-1646]
    → ToneCurve（tone_curve.Apply）           [step 2, dcp.cc:1648-1650]
    → RawTherapee 自身 Tone Curve             [improcfun.cc:2544/2591/2599]
```

> 说明：`file:line` 中指出的 "the order is ..." 式函数内注释，在 RawTherapee 的 `dcp.cc` 中**未找到**（`grep -rniE "the order|in this order|order is|must be applied|should be applied" dcp.cc rawimagesource.cc` 无输出）。RawTherapee 用**代码结构 + `// Apply the matrix part`、`// Convert to ProPhoto and apply LUT`、`// with looktable and tonecurve we need to clip` 等短注释**表达顺序，而非一句话总结。

### 1.2 ART 的 `dcp.cc`：与 RawTherapee 同序（逐字）

ART 的 `DCPProfile::apply` 签名在 `rtengine/dcp.cc:1217`，与 RT 相同结构的 ProPhoto + HueSatMap，`rtengine/dcp.cc:1274`、`1295`、`1318-1330`：

```cpp
        // LUT available --> Calculate matrix for conversion raw>ProPhoto
...
        // Convert to ProPhoto and apply LUT
...
                if (LIKELY(Color::rgb2hsvdcp(newr, newg, newb, h, s, v))) {

                    hsdApply(delta_info, delta_base, h, s, v);
```

ART 的 `step2ApplyTile` 在 `rtengine/dcp.cc:1392`，LookTable → ToneCurve 的顺序与 RT 一致，`rtengine/dcp.cc:1443-1482`：

```cpp
                // with looktable and tonecurve we need to clip
                if (as_in.data->apply_look_table ||
                    as_in.data->use_tone_curve) {
...
                if (as_in.data->apply_look_table) {
                    float cnewr = FCLIP(newr);
                    float cnewg = FCLIP(newg);
                    float cnewb = FCLIP(newb);

                    float h, s, v;
                    Color::rgb2hsvdcp(cnewr, cnewg, cnewb, h, s, v);

                    hsdApply(look_info, look_table, h, s, v);
...
                if (as_in.data->use_tone_curve) {
                    tone_curve.Apply(newr, newg, newb);
                }
```

### 1.3 Adobe DNG SDK 的调用顺序（逐字，`dng_sdk/source/dng_render.cpp`）

镜像 commit `962a7028efdc8f8a651cf6335c3ec53053472339`，文件 `dng_sdk/source/dng_render.cpp`。

相机矩阵/白平衡 → HueSatMap，`dng_render.cpp:1815-1832`：

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
										 srcCols,
										 *fHueSatMap.Get (),
										 fHueSatMapEncode.Get (),
										 fHueSatMapDecode.Get (),
										 fSupportOverrange);
					
					}
```

ProfileGainTableMap 与曝光曲线，`dng_render.cpp:1900-1923`：

```cpp
		// Apply exposure curve.

		if (fExposureRamp.Get ())
			{

			DoBaseline1DFunction (tPtrR,
								  tPtrR,
								  srcCols,
								  *fExposureRamp,
								  fSupportOverrange);
								
			DoBaseline1DFunction (tPtrG,
...
```

LookTable，`dng_render.cpp:1925-1942`：

```cpp
		// Apply look table, if any.
		
		if (fLookTable.Get ())
			{
			
			DoBaselineHueSatMap (tPtrR,
								 tPtrG,
								 tPtrB,
								 tPtrR,
								 tPtrG,
								 tPtrB,
								 srcCols,
								 *fLookTable.Get (),
								 fLookTableEncode.Get (),
								 fLookTableDecode.Get (),
								 fSupportOverrange);
			
			}
```

ToneCurve，`dng_render.cpp:1944-1965`：

```cpp
		// Apply baseline tone curve.

		if (fToneCurveSlopeExtension.Get ())
			DoBaselineRGBToneOverrange (tPtrR,
...
		else
			DoBaselineRGBTone (tPtrR,
							   tPtrG,
							   tPtrB,
							   tPtrR,
							   tPtrG,
							   tPtrB,
							   srcCols,
							   fToneCurve);
```

Adobe 的编码表（sRGB encoding 时启用），`dng_render.cpp:924-950`：

```cpp
			fHueSatMap.Reset (profile.HueSatMapForWhite (spec->WhiteXY ()));
			
			if (profile.HasLookTable ())
				{
				
				fLookTable.Reset (new dng_hue_sat_map (profile.LookTable ()));
				
				}

			if (profile.HueSatMapEncoding () != encoding_Linear)
				{
					
				BuildHueSatMapEncodingTable (*allocator,
											 profile.HueSatMapEncoding (),
											 fHueSatMapEncode,
											 fHueSatMapDecode,
											 false);
					
				}
			
			if (profile.LookTableEncoding () != encoding_Linear)
				{
					
				BuildHueSatMapEncodingTable (*allocator,
											 profile.LookTableEncoding (),
											 fLookTableEncode,
											 fLookTableDecode,
											 false);
					
				}
```

**一致**：RawTherapee/ART 与 Adobe DNG SDK 的三段顺序同为
`HueSatMap →（曝光/baseline exposure）→ LookTable → ToneCurve`。

### 1.4 Adobe DNG 规范 1.7.1.0 的原文（含页码）

URL：https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf（PDF 页码与文档页脚页码一致）

**(1) LookTable 相对曝光与 tone curve 的位置 —— ProfileLookTableData，PDF 第 57 页：**

> "This tag contains a default "look" table that can be applied while processing the image as a starting point for user adjustment. This table uses the same format as the tables stored in the ProfileHueSatMapData1 and ProfileHueSatMapData2 tags and is applied in the same color space. However, it should be applied later in the processing pipe, after any exposure compensation and/or fill light stages, but before any tone curve stage."

**(2) HueSatMap 相对相机矩阵的位置 —— "Applying the Hue/Saturation/Value Mapping Table"，PDF 第 103 页：**

> "After the camera colors have been converted to XYZ (D50) values, the Hue/Saturation/Value mapping table, if any, is applied. If there are two or three Hue/Saturation/Value mapping tables, then they are interpolated in the same way that color calibration tags are interpolated. If only one Hue/Saturation/Value table is included, then it is used regardless of the selected white balance."

同页步骤 1：

> "1. First, the XYZ (D50) values are converted to linear RGB coordinates, using the ProPhoto RGB primaries. (This is also known as RIMM space)."

**(3) ToneCurve 的色彩空间 —— ProfileToneCurve，PDF 第 51 页：**

> "This tag contains a default tone curve that can be applied while processing the image as a starting point for user adjustments. The curve is specified as a list of 32-bit IEEE floating-point value pairs in linear gamma. Each sample is a pair (x,y) where x is the input value in the range of 0.0 to 1.0 and y is the output value in the range of 0.0 to 1.0. Samples must be stored in ascending order of x coordinates, and each sample must have an x coordinate that is greater than the previous sample (strictly increasing)."

**(4) HueSatMap / LookTable 的编码开关 —— ProfileHueSatMapEncoding（Tag 51107），PDF 第 64 页：**

> "Provides a way for color profiles to specify how indexing into a 3D HueSatMap is performed during raw conversion. This tag is not applicable to a 2.5D HueSatMap (i.e., where the Value dimension is 1).
> The currently defined values are:
> • 0 = Linear encoding
> • 1 = sRGB encoding
> If set to 0 (Linear encoding), then the method used to apply a 3D HueSatMap is as follows:
> 1. Convert linear ProPhoto RGB values to HSV."

ProfileLookTableEncoding（Tag 51108），PDF 第 63 页，同构：

> "Provides a way for color profiles to specify how indexing into a 3D LookTable is performed during raw conversion. ... • 0 = Linear encoding • 1 = sRGB encoding ... 1. Convert linear ProPhoto RGB values to HSV."

同一段（PDF 第 63-64 页）还给出 sRGB encoding 分支：

> "2. Encode V coordinate using the sRGB encoding curve."
> "5. Decode V coordinate using the sRGB decoding curve (inverse of step 2)."

**(5) （DNG 1.7 新增的 RGBTables，作为"新表在 tone curve 之后"的旁证）PDF 第 81 页：**

> "The color transforms should be applied after the ProfileToneCurve tag."

### 1.5 每一段工作在什么色彩空间（源码 + 规范对照）

| 段 | 色彩空间（规范原文） | RawTherapee/ART 实现证据 |
|---|---|---|
| HueSatMap | **linear ProPhoto RGB（RIMM）**，输入由 XYZ(D50) 转来 | `dcp.cc:1456` "Calculate matrix for conversion raw>ProPhoto"；`dcp.cc:1497` `rgb2hsvdcp`（ProPhoto 值直接转 HSV） |
| LookTable | 与 HueSatMap **同一色彩空间**，但可选 **sRGB encoding**（仅对 V 坐标做 sRGB 编码） | `dcp.cc:1630` `Color::rgb2hsvtc(...)`、`dcp.cc:1632` `hsdApply(look_info, ...)`；RT 在 `hsdApply` 内按 tag 决定是否编码：`dcp.cc:2063-2065`、`2128-2132` |
| ToneCurve | **linear gamma**、输入输出 [0,1] | `dcp.cc:1648-1650` `tone_curve.Apply(newr, newg, newb);`，之前没有任何 gamma 预编码 |

RT 对 sRGB encoding tag 的实际处理（逐字），`rtengine/dcp.cc:2063-2065`：

```cpp
        if (table_info.srgb_gamma) {
            v_encoded = Color::gammatab_srgb1[v * 65535.f];
        }
```

`rtengine/dcp.cc:2128-2132`：

```cpp
    if (table_info.srgb_gamma) {
        v = Color::igammatab_srgb1[v_encoded * val_scale * 65535.f];
    } else {
        v *= val_scale;
    }
```

tag 解析处，`rtengine/dcp.cc:862-863` 与 `1203`、`1233`：

```cpp
        TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING = 51107,
        TAG_KEY_PROFILE_LOOK_TABLE_ENCODING = 51108,
```

```cpp
        look_info.srgb_gamma = md.find(TAG_KEY_PROFILE_LOOK_TABLE_ENCODING) && md.toInt(TAG_KEY_PROFILE_LOOK_TABLE_ENCODING);
```

```cpp
        delta_info.srgb_gamma = md.find(TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING) && md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING);
```

> 注意（证据支持的差异）：在 RT 的 **LookTable 路径**中，HSV 转换用的是 `Color::rgb2hsvtc`（`dcp.cc:1630`），而 sRGB 编码只发生在 `hsdApply` 内部对 **V 坐标** 的处理（`dcp.cc:2063`/`2128`）；这与规范 PDF 第 63 页 sRGB encoding 步骤完全对应。HueSatMap 路径同理在 `hsdApply` 内处理（`dcp.cc:1499` → `2063`/`2128`）。**RT 未实现规范中 High Dynamic Range profile 的 `f(x)` 编码函数分支**：`hsdApply` 中只有 `srgb_gamma` 一种编码（`dcp.cc:2019-2132`），`grep -n "HDR\|ProfileDynamicRange\|hdr" dcp.cc` 相关编码分支未在 `hsdApply` 出现。

### 1.6 RawTherapee 官方文档对管线的描述（交叉验证）

RawPedia 官方文档 `content/Toolchain_Pipeline/index.md:55-87`（仓库 commit `142ec343…`；对应网页 https://rawpedia.rawtherapee.com/Toolchain_Pipeline ）：

```
15. Process RGB
    1.  Channel mixer
    2.  Tone curve
    3.  Highlights
    4.  Shadows
    5.  RGB curves
    6.  HSV curves
    7.  Color toning
    8.  Film simulation
    9.  Black-and-white
    10. L\*a\*b\* color correction grid (Lab)
16. Process Lab
    1.  Shadows/Highlight (Lab)
    2.  Local contrast (Lab)
    3.  Lab adjustements
    4.  Vibrance
    5.  L\*a\*b\* color correction grid (Lab)
    6.  Vignette filter
    7.  Graduated filter
    8.  Tone mapping
    ...
17. Final Lab -\> RGB conversion
```

同文件 `:24-44` 的"Exposure/White balance/Convert colorspace"次序：

```
5.  White balance
...
8.  Convert colorspace
```

---

## 2. RawTherapee 的 "Film Simulation"（HaldCLUT）

### 2.1 文件与实现位置

- 应用点：`rtengine/improcfun.cc`（`ImProcFunctions::rgbProc` 内），`improcfun.cc:3138` 起。
- CLUT 存储/插值：`rtengine/clutstore.cc`（`HaldCLUT`、`CLUTStore`）。
- **不存在** `rtengine/filmsimulation.cc`，也**不存在** `rtengine/haldclut.cc`（实测：`ls filmsimulation.* haldclut.* lut.*` → `No such file or directory`）。因此题目中给出的两个候选文件名在 RawTherapee 最新源码中**不成立**；实际文件名是 `rtengine/clutstore.cc`。

### 2.2 应用点与上下文（逐字）—— Film Simulation 在 Tone Curve 之后

`rtengine/improcfun.cc:3137-3139`：

```cpp
                // Film Simulations
                if (hald_clut) {
```

`rtengine/improcfun.cc:3182-3211` —— **LUT 前的 sRGB gamma 编码与 LUT 后的逆编码**（本问题最关键的编码证据）：

```cpp
                        for (int j = jstart, tj = 0; j < tW; j++, tj++) {
                            float &sourceR = clutr[tj];
                            float &sourceG = clutg[tj];
                            float &sourceB = clutb[tj];

                            // Apply gamma sRGB (default RT)
                            sourceR = Color::gamma_srgbclipped(sourceR);
                            sourceG = Color::gamma_srgbclipped(sourceG);
                            sourceB = Color::gamma_srgbclipped(sourceB);
                        }

                        hald_clut->getRGB(
                            film_simulation_strength,
                            std::min(TS, tW - jstart),
                            clutr,
                            clutg,
                            clutb,
                            out_rgbx
                        );

                        for (int j = jstart, tj = 0; j < tW; j++, tj++) {
                            float &sourceR = clutr[tj];
                            float &sourceG = clutg[tj];
                            float &sourceB = clutb[tj];

                            // Apply inverse gamma sRGB
                            sourceR = Color::igamma_srgb(out_rgbx[tj * 4 + 0]);
                            sourceG = Color::igamma_srgb(out_rgbx[tj * 4 + 1]);
                            sourceB = Color::igamma_srgb(out_rgbx[tj * 4 + 2]);
                        }
```

工作空间与 LUT profile 的互转（逐字），`rtengine/improcfun.cc:3142-3143`、`3172-3174`、`3213-3214`、`3243-3245`：

```cpp
                        if (!clutAndWorkingProfilesAreSame) {
                            // Convert from working to clut profile
```

```cpp
                                Color::rgbxyz(sourceR, sourceG, sourceB, x, y, z, wprof);
                                Color::xyz2rgb(x, y, z, clutr[tj], clutg[tj], clutb[tj], xyz2clut);
```

```cpp
                        if (!clutAndWorkingProfilesAreSame) {
                            // Convert from clut to working profile
```

```cpp
                                Color::rgbxyz(sourceR, sourceG, sourceB, x, y, z, clut2xyz);
                                Color::xyz2rgb(x, y, z, sourceR, sourceG, sourceB, wiprof);
```

tile 收尾（写回 tile 缓冲），`rtengine/improcfun.cc:3249-3250`：

```cpp
                        for (int j = jstart, tj = 0; j < tW; j++, tj++) {
                            setUnlessOOG(rtemp[ti * TS + tj], gtemp[ti * TS + tj], btemp[ti * TS + tj], clutr[tj], clutg[tj], clutb[tj]);
```

**位置证据（在 RGB 处理段内、Lab 转换之前）**，`rtengine/improcfun.cc:3274-3277`：

```cpp
                    // ready, fill lab
                    for (int i = istart, ti = 0; i < tH; i++, ti++) {
                        Color::RGB2Lab(&rtemp[ti * TS], &gtemp[ti * TS], &btemp[ti * TS], &(lab->L[i][jstart]), &(lab->a[i][jstart]), &(lab->b[i][jstart]), toxyz, tW - jstart);
                    }
```

而 `rgbProc` 在 Lab 处理段**之前**被调用，`rtengine/improccoordinator.cc:1904-1908`：

```cpp
                DCPProfileApplyState as;
                DCPProfile *dcpProf = imgsrc->getDCP(params->icm, as);

                ipf.rgbProc(oprevi, oprevl, nullptr, hltonecurve, shtonecurve, tonecurve, params->toneCurve.saturation,
                            rCurve, gCurve, bCurve, colourToningSatLimit, colourToningSatLimitOpacity, ctColorCurve, ctOpacityCurve, opautili, clToningcurve, cl2Toningcurve, customToneCurve1, customToneCurve2, beforeToneCurveBW, afterToneCurveBW, rrm, ggm, bbm, bwAutoR, bwAutoG, bwAutoB, params->toneCurve.expcomp, params->toneCurve.hlcompr, params->toneCurve.hlcomprthresh, dcpProf, as, histToneCurve);
```

Lab 阶段（含 Lab 域 tone mapping）在其后，`rtengine/improccoordinator.cc:1983`、`1990-1996`：

```cpp
            ipf.shadowsHighlights(nprevl, params->sh.enabled, params->sh.lab, params->sh.highlights, params->sh.shadows, params->sh.radius, scale, params->sh.htonalwidth, params->sh.stonalwidth);
```

```cpp
            ipf.chromiLuminanceCurve(nullptr, pW, nprevl, nprevl, chroma_acurve, chroma_bcurve, satcurve, lhskcurve, clcurve, lumacurve, utili, autili, butili, ccutili, cclutili, clcutili, histCCurve, histLCurve);
            ipf.vibrance(nprevl, params->vibrance, params->toneCurve.hrenabled, params->icm.workingProfile);
            ipf.labColorCorrectionRegions(nprevl);

         //   if ((params->colorappearance.enabled && !params->colorappearance.tonecie) || (!params->colorappearance.enabled)) {
            if ((params->colorappearance.enabled && !params->colorappearance.tonecie) || (!cam02)) {
                ipf.EPDToneMap(nprevl, 0, scale);
            }
```

输出 profile 转换在 Lab 段之后，`rtengine/improccoordinator.cc:2736-2742`：

```cpp
                // Computing the preview image, i.e. converting from WCS->Monitor color space (soft-proofing disabled) or WCS->Printer profile->Monitor color space (soft-proofing enabled)
                ipf.lab2monitorRgb(nprevl, previmg);

                // Computing the internal image for analysis, i.e. conversion from WCS->Output profile
                delete workimg;
                workimg = nullptr;

                workimg = ipf.lab2rgb(nprevl, 0, 0, pW, pH, params->icm);
```

### 2.3 HaldCLUT 假定的输入编码（逐字）

**sRGB gamma 编码**，见 2.2 中 `improcfun.cc:3187-3191` 的 `// Apply gamma sRGB (default RT)` 与 `3207-3210` 的 `// Apply inverse gamma sRGB`。

`Color::gamma_srgbclipped` / `Color::igamma_srgb` 的实现，`rtengine/color.h:1390-1401`：

```cpp
    static inline float  gamma_srgbclipped       (float x)
    {
        return gamma2curve[x];
    }
...
    static inline float  igamma_srgb      (float x)
    {
        return igammatab_srgb[x];
    }
```

默认 profile 为 sRGB，`rtengine/clutstore.cc:118-124`：

```cpp
rtengine::HaldCLUT::HaldCLUT() :
    clut_level(0),
    flevel_minus_one(0.0f),
    flevel_minus_two(0.0f),
    clut_profile("sRGB")
{
}
```

也可以用文件名后缀指定 LUT 所在工作空间，`rtengine/clutstore.cc:292-307`：

```cpp
    if (checkProfile) {
        profile_name = "sRGB";

        if (!name.empty()) {
            for (const auto& working_profile : rtengine::ICCStore::getInstance()->getWorkingProfiles()) {
                if (
                    !working_profile.empty() // This isn't strictly needed, but an empty wp name should be skipped anyway
                    && std::search(name.rbegin(), name.rend(), working_profile.rbegin(), working_profile.rend()) == name.rbegin()
                ) {
                    profile_name = working_profile;
                    name.erase(name.size() - working_profile.size());
                    break;
                }
            }
        }
    }
```

插值方法（三线性，逐轴），`rtengine/clutstore.cc:178-230`（摘录）：

```cpp
        const unsigned int red = std::min(flevel_minus_two, *r * flevel_minus_one);
        const unsigned int green = std::min(flevel_minus_two, *g * flevel_minus_one);
        const unsigned int blue = std::min(flevel_minus_two, *b * flevel_minus_one);

        const unsigned int color = red + green * level + blue * level_square;
```

```cpp
        out_rgbx[0] = intp<float>(strength, out_rgbx[0], *r);
        out_rgbx[1] = intp<float>(strength, out_rgbx[1], *g);
        out_rgbx[2] = intp<float>(strength, out_rgbx[2], *b);
```

> `strength` 是**在 gamma 编码域**对原始输入与 LUT 输出做线性混合（`clutstore.cc:228-230`），随后才逆 gamma。

### 2.4 RawTherapee **官方文档**（HaldCLUT 说明）

来源：官方 wiki 源码仓库 `RawTherapee/RawPedia`（commit `142ec3433b232f821d8a286ef829f5a46df2cf11`），页面对应 https://rawpedia.rawtherapee.com/Film_Simulation 。

`content/Film_Simulation/index.md:16-20`：

> "This tool requires the use of reference images in the HaldCLUT pattern, in either PNG or TIFF format. Each HaldCLUT image corresponds to one "look". Though the look can be based on anything, most of the reference images we ship are based on classic film stock, ergo the tool's name."

`content/Film_Simulation/index.md:115-118`：

> "To generate an identity 12-level 16-bit HaldCLUT image using ImageMagick, run this command in a console:
> `convert hald:12 -depth 16 -colorspace sRGB hald12_16bit.tif`"

`content/Film_Simulation/index.md:145`：

> "3.  Save this image as an 8-bit sRGB TIFF or PNG in the HaldCLUT folder you pointed RawTherapee to. It's now ready for use. Restart RawTherapee so that your new HaldCLUT appears in the list."

`content/Film_Simulation/index.md:229-237`：

> "This archive contains a collection of film simulation HaldCLUTs which you can apply to your photos to instantly match their colors to the film stocks the HaldCLUTs are based on. Unless otherwise noted in the filename, they are all in the sRGB color space, 8-bit per channel, in the PNG image format."

官方文档**明确说明 HaldCLUT 的假定编码是 sRGB 色彩空间**（identity 用 `-colorspace sRGB` 生成、自制的存为 "8-bit sRGB TIFF or PNG"、官方集合 "all in the sRGB color space"），与源码中 LUT 前的 `// Apply gamma sRGB (default RT)` 互相印证。

官方文档**没有**用文字说明"Film Simulation 在管线中的位置"；该信息只在 `content/Toolchain_Pipeline/index.md:63`（`8. Film simulation`，见 1.6）给出。

---

## 3. RawTherapee 的 "LUT"（3D `.cube`）模块

**结论：源码中未找到。**

取证方法与结果：

1. 受版本控制的文件清单（`git ls-tree -r --name-only HEAD`，commit `6c4cb596…`）共 **1566** 个文件；匹配 `cube|lut3d` 的文件数为 **0**：

```
$ git ls-tree -r --name-only HEAD > /tmp/rt_files.txt; wc -l /tmp/rt_files.txt
    1566 /tmp/rt_files.txt
$ grep -icE "cube|lut3d" /tmp/rt_files.txt
0
```

2. `rtengine/` 下不存在 `lut.cc`、`LUT3D.cc`、`haldclut.cc`：

```
$ ls rtengine | grep -iE "film|clut|lut|cube"
clutstore.cc
clutstore.h
filmnegativeproc.cc
LUT.h
```

3. 全源码搜索 `.cube` / `DOMAIN_MIN` / `DOMAIN_MAX` 无命中（`rtengine`、`rtgui`、`rtdata` 全部）：

```
$ grep -rniE "\.cube|domain_min|domain_max" rtengine rtgui rtdata
(无输出)
```

4. `rtengine/LUT.h` 只是通用的 1D/2D LUT 模板（`class LUT`、`LUT(int s, int flags = LUT_CLIP_BELOW | LUT_CLIP_ABOVE, ...)` 等，`LUT.h:91`、`LUT.h:113`），**不解析 `.cube` 文件、也没有 `DOMAIN_MIN`/`DOMAIN_MAX` 处理**。

5. 因此：RawTherapee 无 `.cube` 3D LUT iop；其"LUT"能力只有 Film Simulation 的 HaldCLUT（PNG/TIFF，见第 2 节）。

---

## 4. ART：film simulation / LUT / Sigmoid / 管线顺序

### 4.1 ART 的管线顺序函数（逐字）—— `ImProcFunctions::process`

`rtengine/improcfun.cc:636-712`（commit `3bf5873c…`）：

```cpp
bool ImProcFunctions::process(Stage stage, Imagefloat *img)
{
    bool stop = false;

#define STEP_(op, s) apply<void>(#op, &ImProcFunctions::op, img, s)
#define STEP_s_(op, s) apply<bool>(#op, &ImProcFunctions::op, img, s)

    switch (stage) {
    case Stage::STAGE_0:
        STEP_(dehaze, !params->dehaze.enabled);
        STEP_(dynamicRangeCompression, !params->fattal.enabled);
        break;
    case Stage::STAGE_1:
        STEP_(channelMixer, !params->chmixer.enabled);
        STEP_(exposure, !params->exposure.enabled);
        STEP_(hslEqualizer, !params->hsl.enabled);
        stop = STEP_s_(toneEqualizer, !params->toneEqualizer.enabled);
        if (params->icm.workingProfile == "ProPhoto") {
            img->syncCpuForWrite();   // funnel bypass: CPU-only free function
            proPhotoBlue(img, multiThread);
        }
        break;
    case Stage::STAGE_2:
        if (params->icm.dcp_look_early && needsDCPProfile()) {
            img->syncCpuForWrite();   // funnel bypass: CPU-only free function
            dcpProfile(img, dcpProf, dcpApplyState, multiThread);
        }
        linked_mask_mgr_.init(*params);
        if (cur_pipeline == Pipeline::OUTPUT || cur_pipeline == Pipeline::PREVIEW) {
            stop = STEP_s_(sharpening, !params->sharpening.enabled);
            if (!stop) {
                STEP_(impulsedenoise, !params->impulseDenoise.enabled);
                STEP_(defringe, !params->defringe.enabled);
            }
        }
        stop = stop || STEP_s_(colorCorrection, !params->colorcorrection.enabled);
        stop = stop || STEP_s_(guidedSmoothing, !params->smoothing.enabled);
        break;
    case Stage::STAGE_3:
        STEP_(creativeGradients, !(needsGradient() || needsPCVignetting()));
        stop = stop || STEP_s_(textureBoost, !params->textureBoost.enabled);
        if (!stop) {
            STEP_(filmGrain, !params->grain.enabled);
            STEP_(logEncoding, !params->logenc.enabled);
            STEP_(saturationVibrance, !params->saturation.enabled);
            if (!params->icm.dcp_look_early && needsDCPProfile()) {
                img->syncCpuForWrite();   // funnel bypass
                dcpProfile(img, dcpProf, dcpApplyState, multiThread);
            }
            if (!params->filmSimulation.after_tone_curve) {
                STEP_(filmSimulation, !params->filmSimulation.enabled);
            }
            STEP_(toneCurve, !params->toneCurve.enabled);
            if (params->filmSimulation.after_tone_curve) {
                STEP_(filmSimulation, !params->filmSimulation.enabled);
            }
            STEP_(rgbCurves, !params->rgbCurves.enabled);
            STEP_(labAdjustments, !params->labCurve.enabled);
            STEP_(softLight, !params->softlight.enabled);
        }
        stop = stop || STEP_s_(localContrast, !params->localContrast.enabled);
        if (!stop) {
            STEP_(blackAndWhite, !params->blackwhite.enabled);
        }
        if (cur_pipeline == Pipeline::PREVIEW && params->prsharpening.enabled) {
            ...
        }
        break;
    }
    return stop;
}
```

**ART 的 stage 调用点**（`rtengine/improccoordinator.cc`）：

```cpp
449:            ipf.process(ImProcFunctions::Stage::STAGE_0, oprevi);
525:                    ipf.process(ImProcFunctions::Stage::STAGE_1, bufs_[0]);
539:                stop || ipf.process(ImProcFunctions::Stage::STAGE_2, bufs_[1]);
546:                stop || ipf.process(ImProcFunctions::Stage::STAGE_3, bufs_[2]);
589:                workimg = ipf.rgb2out(bufs_[2], 0, 0, pW, pH, params.icm);
```

输出 profile 转换在 STAGE_3 之后，`rtengine/improccoordinator.cc:581-589`：

```cpp
                // Computing the internal image for analysis, i.e. conversion
                // from WCS->Output profile
                delete workimg;
                workimg = ipf.rgb2out(bufs_[2], 0, 0, pW, pH, params.icm);
```

**ART 中 DCP / film simulation / tone curve 的相对顺序（源码事实）**：

```
STAGE_1: ... exposure ...
STAGE_2: [dcpProfile  只有当 dcp_look_early = true]
STAGE_3: filmGrain → logEncoding → saturationVibrance
       → [dcpProfile  当 dcp_look_early = false]
       → filmSimulation  (当 after_tone_curve = false，默认)
       → toneCurve
       → filmSimulation  (当 after_tone_curve = true)
       → rgbCurves → labAdjustments → softLight
       → localContrast → blackAndWhite
之后:   rgb2out (输出 profile)
```

DCP 的 step2 实现，`rtengine/improcfun.cc:411-429`：

```cpp
void dcpProfile(Imagefloat *img, DCPProfile *dcp,
                const DCPProfile::ApplyState *as, bool multithread)
{
    if (dcp && as) {
        img->setMode(Imagefloat::Mode::RGB, multithread);
...
            dcp->step2ApplyTile(r, g, b, W, 1, 1, *as);
```

### 4.2 ART 的 film simulation 实现与域

`rtengine/ipfilmsim.cc:33-48`（全文核心）：

```cpp
void ImProcFunctions::filmSimulation(Imagefloat *img)
{
    if (!params->filmSimulation.enabled) {
        return;
    }

    img->setMode(Imagefloat::Mode::RGB, multiThread);
...
    CLUTApplication clut(
        params->filmSimulation.clutFilename, params->icm.workingProfile,
        float(params->filmSimulation.strength) / 100.f, num_threads);
```

CLUT 的三条路径（HaldCLUT / OCIO / CTL），`rtengine/clutstore.cc:1085-1097`：

```cpp
void CLUTApplication::init(int num_threads)
{
    hald_clut_ = CLUTStore::getInstance().getHaldClut(clut_filename_);
    if (!hald_clut_) {
#ifdef ART_USE_OCIO
        if (!OCIO_init())
            if (!extlut_init())
#endif // ART_USE_OCIO
#ifdef ART_USE_CTL
                if (!CTL_init(num_threads))
#endif // ART_USE_CTL
                    ok_ = false;
        return;
    }
```

**HaldCLUT 路径与 RawTherapee 完全一致（含 sRGB gamma 编码）**，`rtengine/clutstore.cc:1518-1589`：

```cpp
inline void CLUTApplication::do_apply(int W, float *r, float *g, float *b)
{
...
    if (!clut_and_working_profiles_are_same_) {
        // Convert from working to clut profile
...
            Color::rgbxyz(sourceR, sourceG, sourceB, x, y, z, wprof_);
            Color::xyz2rgb(x, y, z, clutr[j], clutg[j], clutb[j], xyz2clut_);
...
    for (int j = 0; j < W; j++) {
        float &sourceR = clutr[j];
        float &sourceG = clutg[j];
        float &sourceB = clutb[j];

        // Apply gamma sRGB (default RT)
        sourceR = Color::gamma_srgbclipped(sourceR);
        sourceG = Color::gamma_srgbclipped(sourceG);
        sourceB = Color::gamma_srgbclipped(sourceB);
    }

    hald_clut_->getRGB(strength_, W, clutr, clutg, clutb, out_rgbx);

    for (int j = 0; j < W; j++) {
        float &sourceR = clutr[j];
        float &sourceG = clutg[j];
        float &sourceB = clutb[j];

        // Apply inverse gamma sRGB
        sourceR = Color::igamma_srgb(out_rgbx[j * 4 + 0]);
        sourceG = Color::igamma_srgb(out_rgbx[j * 4 + 1]);
        sourceB = Color::igamma_srgb(out_rgbx[j * 4 + 2]);
    }
```

**CTL 路径不做 sRGB gamma 编码**，而是做色彩空间矩阵转换 + PQ shaper，`rtengine/clutstore.cc:1687-1706`：

```cpp
        for (int x = 0; x < W; ++x) {
            v[0] = r[x] / 65535.f;
            v[1] = g[x] / 65535.f;
            v[2] = b[x] / 65535.f;
            v = dot_product(conv_, v);
            rgb[0][x] = v[0];
            rgb[1][x] = v[1];
            rgb[2][x] = v[2];
        }

        if (ctl_lut_) {
            for (int x = 0; x < W; ++x) {
                float r = CTL_shaper(rgb[0][x], false);
                float g = CTL_shaper(rgb[1][x], false);
                float b = CTL_shaper(rgb[2][x], false);
                ctl_lut_(r, g, b);
```

`CTL_shaper_func` 是 **PQ / SMPTE ST.2084** 曲线，`rtengine/clutstore.cc:1010-1034`：

```cpp
inline float CTL_shaper_func(float a, bool inv)
{
    constexpr float m1 = 2610.0 / 16384.0;
    constexpr float m2 = 2523.0 / 32.0;
    constexpr float c1 = 107.0 / 128.0;
    constexpr float c2 = 2413.0 / 128.0;
    constexpr float c3 = 2392.0 / 128.0;
    constexpr float scale = 100.0;
...
        a /= scale;
        float aa = pow_F(a, m1);
        return pow_F((c1 + c2 * aa) / (1.f + c3 * aa), m2);
```

LUT 声明的色彩空间（CTL 元数据 `@ART-colorspace`）通过 `init_matrices` 生效，`rtengine/clutstore.cc:1450-1478`：

```cpp
void CLUTApplication::init_matrices(const Glib::ustring &lut_profile)
{
    wprof_ = ICCStore::getInstance()->workingSpaceMatrix(working_profile_);
    wiprof_ =
        ICCStore::getInstance()->workingSpaceInverseMatrix(working_profile_);
    if (lut_profile.empty()) {
...
        auto lprof = ICCStore::getInstance()->workingSpaceMatrix(lut_profile);
        auto liprof =
            ICCStore::getInstance()->workingSpaceInverseMatrix(lut_profile);

        auto ws = dot_product(liprof, wprof_);
        auto iws = dot_product(wiprof_, lprof);
```

分派逻辑，`rtengine/clutstore.cc:1746-1773`：

```cpp
void CLUTApplication::apply(int thread_id, int W, float *r, float *g, float *b)
{
    if (!ok_) {
        return;
    }

#ifdef ART_USE_OCIO
    if (ocio_processor_) {
        OCIO_apply(W, r, g, b);
        return;
    }
#endif
#ifdef ART_USE_CTL
    if (!ctl_func_.empty()) {
        try {
            CTL_apply(thread_id, W, r, g, b);
...
    do_apply(W, r, g, b);
```

### 4.3 ART 的 `after_tone_curve`：官方版本注释与默认值

`rtgui/ppversion.h:19-21`（逐字）：

```
  1040  2023-08-30
        changed position of the film simulation tool in the pipeline
        (with "after_tone_curve" param to keep backwards compatibility)
```

默认值 `false`（即默认在 tone curve **之前**），`rtengine/procparams.cc:2279-2281`：

```cpp
FilmSimulationParams::FilmSimulationParams()
    : enabled(false), strength(100), after_tone_curve(false)
{
}
```

旧版 pp3 兼容逻辑，`rtengine/procparams.cc:5272-5274`：

```cpp
            if (!assignFromKeyfile(keyFile, "Film Simulation", "AfterToneCurve",
                                   filmSimulation.after_tone_curve)) {
                filmSimulation.after_tone_curve = (ppVersion < 1040);
            }
```

### 4.4 ART 的 "Sigmoid"

**不存在 `rtengine/sigmoid.cc`。** 全仓库搜索 `sigmoid` 的源码命中只有一处函数注释（与 Sigmoid tone mapper 无关）：

`rtengine/rt_algo.cc:47-51`：

```cpp
float calcBlendFactor(float val, float threshold)
{
    // sigmoid function
    // result is in ]0;1] range
    // inflexion point is at (x, y) (threshold, 0.5)
    return 1.f / (1.f + xexpf(16.f - 16.f * val / threshold));
}
```

ART 的 **Sigmoid 是一个 CTL LUT 文件**（借助 Film Simulation 工具的 CTL 路径应用），`rtdata/luts/sigmoid.ctl:1-6` 与 `:24-25`：

```
/* -*- C -*-
 * 
 * Simplified porting of darktable's sigmoid module to ART CTL
 * Copyright of the original code follows
 */
/*
    This file is part of darktable,
```

```
// @ART-label: "$CTL_SIGMOID"
// @ART-colorspace: "rec2020"
```

`rtdata/luts/sigmoid.ctl:60`：

```
const float MIDDLE_GREY = 0.1845;
```

界面字符串也把它标为 CTL tone mapper（`rtdata/languages/Japanese:40`）：

```
CTL_SIGMOID;シグモイド・トーンマッパー
```

其一，Sigmoid 的**色彩空间声明是 `rec2020`**（`sigmoid.ctl:25`），由 `CLUTApplication::init_matrices` 用于工作空间↔LUT 空间转换（见 4.2）；其二，CTL 路径的数值域经 **PQ shaper**（见 4.2）。因此 ART 的 Sigmoid 与 HaldCLUT film simulation 走的是不同的编码约定，但**共用同一个管线位置**（`filmSimulation` 步骤，`improcfun.cc:686`/`690`）。

### 4.5 ART 的 `.cube` 支持

**源码中未找到。**

```
$ grep -rniE "\.cube|domain_min|domain_max" rtengine rtgui rtdata
(无输出)
```

ART 的 3D LUT 相关实现是 `rtengine/LUT3D.cc` / `LUT3D.h`（内部表结构，输入域由 `input_is_01_` 决定）与 `rtengine/extclut.cc`（`ExternalLUT3D`，解析 JSON 中的 `"ART-lut3d"` 对象，`extclut.cc:410-412`），二者均**不解析 `.cube` 文本格式**，也不处理 `DOMAIN_MIN`/`DOMAIN_MAX`。

`rtengine/LUT3D.cc:29`、`35-39`、`57-70`（逐字）：

```cpp
LUT3D::LUT3D(): input_is_01_(true), dim_(0), dim_minus_one_(0) {}
```

```cpp
void LUT3D::init(int dim, initializer &f, bool input_is_01)
{
    dim_ = dim;
    dim_minus_one_ = dim - 1;
    input_is_01_ = input_is_01;
```

```cpp
bool LUT3D::operator()(float &r, float &g, float &b)
{
    if (!input_is_01_) {
        r /= 65535.f;
        g /= 65535.f;
        b /= 65535.f;
    }

    if (!lut_.isEmpty()) {
        apply_tetra(r, g, b);
        return true;
    }
    return false;
}
```

`rtengine/extclut.cc:410-413`（外部 LUT3D 的格式是 ART 私有 JSON，而非 `.cube`）：

```cpp
    root = cJSON_GetObjectItem(root, "ART-lut3d");
    if (!root || !cJSON_IsObject(root)) {
        return with_error_msg(filename, "ART-lut3d object not found");
    }
```

---

## 5. 结论性回答

### 5.1 DCP 三段顺序

**RawTherapee / ART 与 Adobe DNG SDK 一致**：

```
白平衡 × CCM（合成 camera→XYZ D50 矩阵）
   → HueSatMap（linear ProPhoto / RIMM）
   → baseline exposure
   → LookTable（同一 ProPhoto 空间）
   → ToneCurve（linear gamma，[0,1]）
```

- 代码证据：RawTherapee `rtengine/dcp.cc:1404`（apply）、`rtengine/dcp.cc:1624-1650`（LookTable→ToneCurve）；ART `rtengine/dcp.cc:1217`、`rtengine/dcp.cc:1454-1482`；Adobe `dng_sdk/source/dng_render.cpp:1817 / 1902 / 1927 / 1946`。
- 规范证据：DNG 1.7.1.0 PDF 第 57 页（LookTable 在曝光之后、tone curve 之前）、第 103 页（HueSatMap 在 XYZ(D50) 之后、用 linear ProPhoto）、第 51 页（ToneCurve 是 linear gamma）、第 63-64 页（两表的 0=Linear / 1=sRGB encoding）。

### 5.2 3D LUT / 胶片模拟在 tone mapping 之前还是之后？

- **RawTherapee**：Film Simulation 在**自身 Tone Curve 之后**（`improcfun.cc:2544/2591/2599` → `improcfun.cc:3138`），但在 **Lab 域的 tone mapping（EPDToneMap）、Shadows/Highlights、Vibrance 之前**（`improccoordinator.cc:1907` 的 `rgbProc` 早于 `1983/1996` 的 Lab 段）。官方管线文档同序：`Toolchain_Pipeline/index.md:55-87`（15.8 Film simulation，早于 16.8 Tone mapping 与 17. Final Lab→RGB conversion）。
- **ART**：默认 `after_tone_curve = false`（`procparams.cc:2280`），即 Film Simulation 在 **tone curve 之前**；勾选后在其之后（`improcfun.cc:685-691`）。它与 Tone Curve 都位于 STAGE_3，早于 `rgb2out` 输出 profile 转换（`improccoordinator.cc:589`），也早于后续的 Lab 类调整（`labAdjustments` 等）。
- 因此严格说法是：**RT 中胶片模拟在"Tone Curve 之后、Lab tone mapping 之前"；ART 中默认在"Tone Curve 之前、输出 profile 之前"**。两者都不在最终输出/显示编码之后，也都不在 Lab 域 tone mapping 之后。

### 5.3 scene-referred 还是 display-referred？

- **数值载体是 scene-referred 的线性工作空间 RGB**：Film Simulation 执行在 `rgbProc` 内、`RGB2Lab` 之前（`improcfun.cc:3138` → `3276`），此时像素是 working profile（如 ProPhoto）线性 RGB；DCP step2 亦在此线性段（`dcp.cc:1604`）。
- **但 LUT 的查找坐标是 display-referred 的 sRGB gamma 编码**：LUT 前执行 `Color::gamma_srgbclipped`（`improcfun.cc:3187-3191`；ART `clutstore.cc:1572-1575`），LUT 后 `Color::igamma_srgb` 逆变换（`improcfun.cc:3207-3210`；ART `clutstore.cc:1585-1588`）。
- 结论：**管线位置在 scene-referred 线性段；LUT 的输入编码约定是 display-referred（sRGB 编码）**。这一"线性位置上套 sRGB 编码查找"的做法与官方文档一致（LUT 存为 sRGB PNG/TIFF，`Film_Simulation/index.md:118/145/234-235`）。
- 例外：ART 的 **CTL 路径**不做 sRGB 编码，而是以 LUT 自己声明的色彩空间（如 `rec2020`）+ **PQ (ST.2084) shaper** 送入（`clutstore.cc:1687-1706`、`1010-1034`、`sigmoid.ctl:25`）。

---

## 6. 未找到 / 无法获取的条目（显式列出）

1. **RawTherapee 的 3D `.cube` LUT 模块**：源码中未找到（无 `lut.cc`/`LUT3D`，无 `DOMAIN_MIN`/`DOMAIN_MAX`，1566 个受控文件中 0 个含 `cube`）。
2. **ART 的 `.cube` LUT**：源码中未找到（同上前述 grep 无输出）。
3. **`rtengine/filmsimulation.cc`、`rtengine/haldclut.cc`（题目给定候选路径）**：RawTherapee 中不存在；实际为 `rtengine/clutstore.cc` + `improcfun.cc`。
4. **`rtengine/sigmoid.cc`（ART）**：不存在；Sigmoid 是 `rtdata/luts/sigmoid.ctl`。
5. **`dcp.cc` 内"the order is ..."式顺序注释**：RawTherapee/ART 的 `dcp.cc` 中未找到此类总结性注释；顺序由代码结构与短注释表达。
6. **`rawpedia.rawtherapee.com` 网页直连**：本机不可达（连接超时；`web.archive.org` 亦超时）。已改用官方源码仓库 `RawTherapee/RawPedia`（commit `142ec3433b232f821d8a286ef829f5a46df2cf11`）提供同样内容的逐字原文与行号。
7. **`adobe/dng-sdk`、`adobe/dng_sdk` GitHub 仓库**：不存在（`Repository not found`）。Adobe DNG SDK 源码取自第三方镜像 `emmcb/adobe-dng-sdk`（commit `962a7028efdc8f8a651cf6335c3ec53053472339`），此点已标注为可信度限制。
8. **全小写 URL `dng_spec_1_7_1_0.pdf`**：404。可用 URL 为 `https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/DNG_Spec_1_7_1_0.pdf`。
9. **DNG 规范中"LookTable 必须在 ToneCurve 之前"的直接 tag 级原文**：规范第 57 页只以 "before any tone curve stage" 表述，未给出 "LookTable → ToneCurve" 的字面排序语句；该字面顺序证据来自 Adobe DNG SDK 的 `dng_render.cpp` 调用次序（1927 → 1946）。
