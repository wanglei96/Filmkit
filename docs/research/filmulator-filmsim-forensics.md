# Filmulator "film simulation" — forensic source evidence

Repo: `https://github.com/CarVac/filmulator-gui`, local copy `/private/tmp/eng/filmulator-gui`.
**Note on environment:** the task stated the repo was already cloned at that path; it was **not present** when I started (`/private/tmp/eng` held only darktable, dcamprof, open-display-transform, rawproc, vkBasalt; `/private/tmp/clone-filmulator-gui.log` showed a completed clone). I re-cloned `git clone --depth 1` myself. Repo root contains a nested `filmulator-gui/` source dir; all paths below are relative to the repo root.

## (a) Commit

```
57fbaec57555432d86d3aa632990cd8fa09114ad Sat Apr 4 10:44:33 2026 -0400 Merge branch 'dev' for 0.12
```
(`git log -1 --format='%H %ad %s'`; branch `master`)

## (b) Relevant source files

| Path | Role |
|---|---|
| `filmulator-gui/core/filmulate.cpp` | `ImagePipeline::filmulate()` — the film simulation itself (242 lines) |
| `filmulator-gui/core/exposure.cpp` | `exposure()` — activates crystals ("akin to exposing film to light") |
| `filmulator-gui/core/develop.cpp` | `develop()` — crystal growth / developer + silver-salt consumption |
| `filmulator-gui/core/diffuse.cpp` | `diffuse()` — 2D Gaussian developer diffusion (repeated box blurs) |
| `filmulator-gui/core/layerMix.cpp` | `layer_mix()` — active-layer ↔ reservoir exchange |
| `filmulator-gui/core/agitate.cpp` | `agitate()` — equalizes developer concentration |
| `filmulator-gui/core/imagePipeline.cpp` | pipeline; `raw_to_sRGB` call, filmulate call site, post-film stages |
| `filmulator-gui/core/colorSpaces.cpp` | `raw_to_sRGB()`, sRGB gamma functions, `sRGB_gammacurve()` |
| `filmulator-gui/core/filmSim.hpp` | declarations, `struct filmulateParams` |
| `filmulator-gui/core/lut.hpp` | generic 65536-entry `LUT<numberType>` template |
| `filmulator-gui/core/curves.cpp` | `default_tonecurve()`, `film_like_curve()` (LUT consumer) |
| `filmulator-gui/ui/parameterManager.h` | `enum Valid` pipeline order, `struct FilmParams` |
| `filmulator-gui/qml/filmulator-gui/EditTools.qml` | UI tooltips using the string "film simulation" |
| `README.md`, `ARCHITECTURE.md` | project docs |

## (c) Verbatim evidence

### README.md:4-11 — project's own description of the model
```
4:A Qt Quick GUI adaptation of Filmulator --- a film emulator with all of the positives and none of the negatives.
5:
6:Filmulator accepts raw files from cameras and simulates the development of film as if exposed to the same light as the camera's sensor. For various reasons, this inherently brings about several benefits:
7:
8:* Large bright regions become darker, compressing the output dynamic range.
9:* Small bright regions make their surroundings darker, enhancing local contrast.
10:* In bright regions, saturation is enhanced, helping retain color in blue skies, brighter skin tones, and sunsets.
11:* In extremely saturated regions, the brightness is attenuated, helping retain detail e.g. in flowers.
```

### filmulator-gui/core/imagePipeline.cpp:1669-1674 — the call site ("film simulation")
```
1669:    // Here we do the film simulation on the image...
1670:    // If filmulate detects an abort, it returns true.
1671:    if (filmulate(film_input_image, filmulated_image, paramManager, this)) {
1672:      cout << "imagePipeline aborted at filmulation" << endl;
1673:      return emptyMatrix();
1674:    }
```

### filmulator-gui/core/filmulate.cpp:120-147 — main development/diffusion loop ("approximates the differential equation")
```
120:    //Now we begin the main development/diffusion loop, which approximates the
121:    //differential equation of film development.
122:    for(int i = 0; i <= development_steps; i++)
123:    {
124:        //Check for cancellation
125:        abort = paramManager->claimFilmAbort();
126:        if(abort == AbortStatus::restart)
127:        {
128:            return true;
129:        }
130:
131:        //Updating for starting the development simulation. Valid is one too high here.
132:        pipeline->updateProgress(Valid::partfilmulation, float(i)/float(development_steps));
133:
134:        develop_start = std::chrono::steady_clock::now();
135:
136:        //This is where we perform the chemical reaction part.
137:        //The crystals grow.
138:        //The developer in the active layer is consumed.
139:        //So is the silver salt in the film.
140:        // The amount consumed increases as the crystals grow larger.
141:        //Because the developer and silver salts are consumed in bright regions,
142:        // this reduces the rate at which they grow. This gives us global
143:        // contrast reduction.
144:        develop(crystal_radius,crystal_growth_const,active_crystals_per_pixel,
145:                silver_salt_density,developer_concentration,
146:                active_layer_thickness,developer_consumption_const,
147:                silver_salt_consumption_const,timestep);
```

### filmulator-gui/core/filmulate.cpp:69-76 — input is turned into activated crystals
```
69:    //Now we activate some of the crystals on the film. This is literally
70:    //akin to exposing film to light.
71:    matrix<float> active_crystals_per_pixel = input_image;
72:    exposure(active_crystals_per_pixel,
73:             crystals_per_pixel,
74:             rolloff_boundary,
75:             toe_boundary,
76:             highlight_crosstalk);
```

### filmulator-gui/core/filmulate.cpp:162-168 + 180-203 — diffusion, layer mix, agitation
```
162:        //Now, we are going to perform the diffusion part.
163:        //Here we mix the layer among itself, which grants us the
164:        // local contrast increases.
165:        diffuse(developer_concentration,
166:                sigma_const,
167:                pixels_per_millimeter,
168:                timestep);
```
```
180:        layer_mix_start = std::chrono::steady_clock::now();
181:        //This performs mixing between the active layer adjacent to the film
182:        // and the reservoir.
183:        //This keeps the effects from getting too crazy.
184:        layer_mix(developer_concentration,
185:                  active_layer_thickness,
186:                  reservoir_developer_concentration,
187:                  reservoir_thickness,
188:                  layer_mix_const,
189:                  layer_time_divisor,
190:                  pixels_per_millimeter,
191:                  timestep);
```
```
200:        if((i+half_agitate_period) % agitate_period ==0)
201:            agitate(developer_concentration, active_layer_thickness,
202:                    reservoir_developer_concentration, reservoir_thickness,
203:                    pixels_per_millimeter);
```

### filmulator-gui/core/filmulate.cpp:215-236 — output is film density
```
215:  // Now we compute the density (opacity) of the film.
216:  // We assume that overlapping crystals or dye clouds are
217:  // nonexistent. It works okay, for now...
218:  // The output is crystal_radius^2 * active_crystals_per_pixel
```
```
230:#pragma omp parallel for
231:  for (int i = 0; i < numRows; ++i) {
232:    for (int j = 0; j < numCols; ++j) {
233:      output_density(i, j) = crystal_radius(i, j) * crystal_radius(i, j) *
234:                             active_crystals_per_pixel(i, j);
235:    }
236:  }
```

### filmulator-gui/core/develop.cpp:70-85 — reaction rate equations
```
70:                //This is the rate of thickness accumulating on the crystals.
71:                dCrystalRadR = develConcentration( row, col ) * silverSaltDensity( row, colr ) * cgc;
72:                dCrystalRadG = develConcentration( row, col ) * silverSaltDensity( row, colg ) * cgc;
73:                dCrystalRadB = develConcentration( row, col ) * silverSaltDensity( row, colb ) * cgc;
74:    
75:                //The volume change is proportional to 4*pi*r^2*dr.
76:                //We kinda shuffled around the constants, so ignore the lack of
77:                //the 4 and the pi.
78:                //However, there are varying numbers of crystals, so we also
79:                //multiply by the number of crystals per pixel.
80:                dCrystalVolR = dCrystalRadR * crystalRad( row, colr ) * crystalRad( row, colr ) *
81:                        activeCrystalsPerPixel( row, colr );
82:                dCrystalVolG = dCrystalRadG * crystalRad( row, colg ) * crystalRad( row, colg ) *
83:                        activeCrystalsPerPixel( row, colg );
84:                dCrystalVolB = dCrystalRadB * crystalRad( row, colb ) * crystalRad( row, colb ) *
85:                        activeCrystalsPerPixel( row, colb );
```
```
92:                //Here is where we consume developer. The 3 layers of film,
93:                //(one per color) share the same developer.
94:                develConcentration( row ,col ) -= dcc * ( dCrystalVolR +
95:                                                          dCrystalVolG +
96:                                                          dCrystalVolB );
```
```
104:                silverSaltDensity( row, colr ) -= sscc * dCrystalVolR;
105:                silverSaltDensity( row, colg ) -= sscc * dCrystalVolG;
106:                silverSaltDensity( row, colb ) -= sscc * dCrystalVolB;
```

### filmulator-gui/core/diffuse.cpp:27-30 + 44-50 — physical diffusion, Gaussian via box blurs
```
27://In real film, developer diffuses in a 2D gaussian on the film surface.
28://Since 2D gaussians are separable, we perform two 1D gaussians in x and y.
29://Since approximating a gaussian with a convolution is fairly computationally
30://expensive, we approximate it using repeated box blurs. 
```
```
44:void diffuse(matrix<float> &developer_concentration, 
45:		     float sigma_const,
46:		     float pixels_per_millimeter,
47:            float timestep)
48:{
49:    //This is the standard deviation we want for the blur in pixels.
50:    float sigma = sqrt(timestep*pow(sigma_const*pixels_per_millimeter,2));
```

### filmulator-gui/core/agitate.cpp:21-36 — conservation of developer mass
```
21:void agitate ( matrix<float> &developerConcentration,
22:               float activeLayerThickness,
23:               float &reservoirDeveloperConcentration,
24:               float reservoirThickness,
25:               float pixelsPerMillimeter )
26:{
27:    int npixels = developerConcentration.nc()*
28:        developerConcentration.nr();
29:    float totalDeveloper = sum( developerConcentration ) *
30:        activeLayerThickness / pow( pixelsPerMillimeter, 2 ) +
31:        reservoirDeveloperConcentration * reservoirThickness;
32:    float contactLayerSize = npixels * activeLayerThickness /
33:        pow( pixelsPerMillimeter, 2 );
34:    reservoirDeveloperConcentration = totalDeveloper / ( reservoirThickness +
35:        contactLayerSize );
36:    developerConcentration = reservoirDeveloperConcentration;
```

### filmulator-gui/core/layerMix.cpp:23-45
```
23://This function implements diffusion between the active developer layer
24:// adjacent to the film and the reservoir of inactive developer.
25:
26:void layer_mix(matrix<float> &developer_concentration,
27:               float active_layer_thickness,
28:               float &reservoir_developer_concentration,
29:               float reservoir_thickness,
30:               float layer_mix_const,
31:               float layer_time_divisor,
32:               float pixels_per_millimeter,
33:               float timestep)
34:{
35:    int length = developer_concentration.nr();
36:    int width = developer_concentration.nc();
37:    
38:    //layer_time_divisor adjusts the ratio between the timestep used to compute
39:    //the diffuse within the layer and this diffuse.
40:    float layer_mix = pow(layer_mix_const,timestep/layer_time_divisor);
41:
42:    //layer_mix is the proportion of developer that stays in the layer.
43:
44:    //This gives us the amount of developer that comes from the reservoir.
45:    float reservoir_portion = (1-layer_mix) * reservoir_developer_concentration;
```

### filmulator-gui/core/exposure.cpp:42-66 — crystal activation + highlight crosstalk/rolloff
```
42:                const float inputR = max(0.0f,input_image(row,colr));
43:                const float inputG = max(0.0f,input_image(row,colg));
44:                const float inputB = max(0.0f,input_image(row,colb));
45:                //crosstalk
46:                const float rsurp = max(0.0f, inputR-rolloff_boundary);
```
```
58:                float rOutput = max(0.0f, crosstalkR - toe_boundary + (toe_boundary*toe_boundary)/(crosstalkR + toe_boundary+1/65535.0f));
59:                rOutput = rOutput > rolloff_boundary ? 65535.f - ((crystal_headroom * crystal_headroom) / (rOutput + crystal_headroom - rolloff_boundary)) : rOutput;
60:                input_image(row,colr) = rOutput * crystals_per_pixel;
```

### filmulator-gui/core/filmSim.hpp:56-74 — the tunable physical parameters
```
56:struct filmulateParams { // TODO: adjust variable names.
57:  float initialDeveloperConcentration;
58:  float reservoirThickness;   // once reservoir_size
59:  float activeLayerThickness; // once developer_thickness
60:  float crystalsPerPixel;
61:  float initialCrystalRadius;
62:  float initialSilverSaltDensity;
63:  float developerConsumptionConst;
64:  float crystalGrowthConst;
65:  float silverSaltConsumptionConst;
66:  float totalDevelTime; // once was int
67:  int agitateCount;
68:  int developmentSteps; // once was float; development_resolution
69:  float filmArea;
70:  float sigmaConst;
71:  float layerMixConst;
72:  float layerTimeDivisor;
73:  float rolloffBoundary;
74:};
```

### filmulator-gui/ui/parameterManager.h:103-123 — same parameters in the live pipeline
```
103:struct FilmParams {
104:    float initialDeveloperConcentration;
105:    float reservoirThickness;
106:    float activeLayerThickness;
107:    float crystalsPerPixel;
108:    float initialCrystalRadius;
109:    float initialSilverSaltDensity;
110:    float developerConsumptionConst;
111:    float crystalGrowthConst;
112:    float silverSaltConsumptionConst;
113:    float totalDevelopmentTime;
114:    int agitateCount;
115:    int developmentSteps;
116:    float filmArea;
117:    float sigmaConst;
118:    float layerMixConst;
119:    float layerTimeDivisor;
120:    float rolloffBoundary;
121:    float toeBoundary;
122:    float highlightCrosstalk;
123:};
```

### filmulator-gui/ui/parameterManager.h:25-45 — stage order (where filmulation sits)
```
25:  partdemosaic,
26:  demosaic,
27:  partpostdemosaic,
28:  postdemosaic,
29:  partnrnlmeans,
30:  nrnlmeans,
31:  partnrimpulse,
32:  nrimpulse,
33:  partnrchroma,
34:  nrchroma,
35:  partprefilmulation,
36:  prefilmulation,
37:  partfilmulation,
38:  filmulation,
39:  partblackwhite,
40:  blackwhite,
41:  partcolorcurve,
42:  colorcurve,
43:  partfilmlikecurve,
44:  filmlikecurve,
45:  count
```

### filmulator-gui/core/imagePipeline.h:108-120 — in-code domain annotations
```
108:  matrix<float> demosaiced_image;     // raw
109:  matrix<float> post_demosaic_image;  // raw
110:  matrix<float> nlmeans_nr_image;     // lab
111:  matrix<float> impulse_nr_image;     // lab
112:  matrix<float> chroma_nr_image;      // lab
113:  matrix<float> pre_film_image;       // back to raw
114:  matrix<float> pre_film_image_small; //
115:  Exiv2::ExifData exifData;
116:  Exiv2::ExifData basicExifData;  // for tiff writing
117:  matrix<float> filmulated_image; // sRGB
118:  matrix<unsigned short> contrast_image;
119:  matrix<unsigned short> color_curve_image;
120:  matrix<unsigned short> vibrance_saturation_image;
```

### filmulator-gui/core/imagePipeline.cpp:1630-1634 — film input is built by a matrix conversion
```
1630:      if (!isMonochrome) {
1631:        raw_to_sRGB(stealVictim->pre_film_image, film_input_image, camToRGB);
1632:      } else {
1633:        film_input_image = stealVictim->pre_film_image;
1634:      }
```

### filmulator-gui/core/colorSpaces.cpp:369-393 — `raw_to_sRGB` is a pure 3x3 matrix, no gamma
```
369://Convert raw color to sRGB, don't clip negatives.
370:void raw_to_sRGB(matrix<float> &input,
371:                 matrix<float> &output,
372:                 const float cam2rgb[3][3])
373:{
...
386:                output(i, j  ) = cam2rgb[0][0]*input(i, j) + cam2rgb[0][1]*input(i, j+1) + cam2rgb[0][2]*input(i, j+2);
387:                output(i, j+1) = cam2rgb[1][0]*input(i, j) + cam2rgb[1][1]*input(i, j+1) + cam2rgb[1][2]*input(i, j+2);
388:                output(i, j+2) = cam2rgb[2][0]*input(i, j) + cam2rgb[2][1]*input(i, j+1) + cam2rgb[2][2]*input(i, j+2);
```

### filmulator-gui/core/imread.cpp:46-51 — raw decode forced to linear, no auto-bright
```
46:    PARAM.no_auto_bright = 1;//Don't autoadjust brightness (-W)
47:    PARAM.output_bps = 16;//16 bits per channel (-6)
48:    PARAM.gamm[0] = 1;
49:    PARAM.gamm[1] = 1;//Linear gamma (-g 1 1)
50:    PARAM.output_color = 1;//1: Use sRGB regardless.
51:    PARAM.use_camera_wb = 1;//1: Use camera WB setting (-w)
```

### filmulator-gui/core/imreadJpeg.cpp:60-72 — JPEG/TIFF input linearized on load
```
60:				read_in = float(row_pointer[0][col*num_chan + channel])/255;
61:				/*Apply reverse sRGB gamma transformation:
62:				http://en.wikipedia.org/wiki/SRGB#The_reverse_transformation*/
63:				if( read_in < 0.04045)
64:				{
65:					returnmatrix(row,col*num_chan + channel) =
66:						read_in*5072.36842105; //(2^16 -1)/12.92 
67:				}
68:				else
69:				{
70:					returnmatrix(row,col*num_chan + channel) =
71:						65535*pow((read_in+0.055)/1.055, 2.4);
72:				}
```

### filmulator-gui/core/imagePipeline.cpp:1121-1134 and 1165-1182 — the only gamma round-trip before filmulation (NR), undone afterwards
```
1121:      // we don't want to apply nonexistent WB multipliers to monochrome images
1122:      const float rMulTemp = isMonochrome ? 1.0f : rUserMul;
1123:      const float gMulTemp = isMonochrome ? 1.0f : gUserMul;
1124:      const float bMulTemp = isMonochrome ? 1.0f : bUserMul;
1125:
1126:#pragma omp parallel for
1127:      for (int row = 0; row < preconditioned.nr(); row++) {
1128:        for (int col = 0; col < preconditioned.nc(); col += 3) {
1129:          preconditioned(row, col + 0) = sRGB_forward_gamma_unclipped(
1130:              preconditioned(row, col + 0) / (rMulTemp * 65535.0f));
```
```
1165:      // Undo the preconditioning
1166:#pragma omp parallel for
1167:      for (int row = 0; row < denoised.nr(); row++) {
1168:        for (int col = 0; col < denoised.nc(); col += 3) {
1169:          denoised(row, col + 0) =
1170:              sRGB_inverse_gamma_unclipped(scale * denoised(row, col + 0) -
1171:                                           offset) *
1172:              rMulTemp * 65535.0f;
```

### filmulator-gui/core/imagePipeline.cpp:1833-1835 + 1848-1851 — post-film stages operate on a 16-bit integer image
```
1833:    whitepoint_blackpoint(cropped_image, // filmulated_image,
1834:                          contrast_image, blackWhiteParam.whitepoint,
1835:                          blackWhiteParam.blackpoint);
```
```
1848:    lutR.setUnity();
1849:    lutG.setUnity();
1850:    lutB.setUnity();
1851:    colorCurves(contrast_image, color_curve_image, lutR, lutG, lutB);
```

### filmulator-gui/core/lut.hpp:29-40 — the (only) LUT class in the codebase
```
29:template <class numberType>
30:class LUT
31:{
32:private:
33:    numberType table[MAXVAL];
34:    bool unity;
35:    bool linear;
36:    float slope;
37:    float y_intercept;
38:    float brightest;
39:    float darkest;
40:public:
```
(`#define MAXVAL 65536` at `core/lut.hpp:22`.)

### filmulator-gui/core/imagePipeline.cpp:1879-1886 — the LUT is filled with a *tone curve*, after filmulation
```
1879:    filmLikeLUT.fill([=](unsigned short in) -> unsigned short {
1880:      float shResult = shadows_highlights(
1881:          float(in) / 65535.0f, curvesParam.shadowsX, curvesParam.shadowsY,
1882:          curvesParam.highlightsX, curvesParam.highlightsY);
1883:      return ushort(65535 * default_tonecurve(shResult));
1884:    });
1885:    matrix<unsigned short> &film_curve_image = vibrance_saturation_image;
1886:    film_like_curve(color_curve_image, film_curve_image, filmLikeLUT);
```

### filmulator-gui/core/curves.cpp:130-137 — what that LUT is for
```
130://The algorithm applies the designated tone curve on the highest and the lowest
131:// values.
132://On the middle value, instead of using the tone curve, which would induce hue
133:// shifts, it simply maintains the relative spacing between the color components.
134://It makes no difference for linear tone "curves".
135:void film_like_curve(matrix<unsigned short> &input,
136:                      matrix<unsigned short> &output,
137:                      LUT<unsigned short> &lookup)
```

### filmulator-gui/core/imagePipeline.cpp:1908-1924 — final stage returns the integer image as-is
```
1908:  default: // output
1909:  {
1910:    cout << "imagePipeline beginning output" << endl;
...
1917:    if (WithHisto == histo) {
1918:      histoInterface->updateHistFinal(vibrance_saturation_image);
1919:    }
1920:    valid = paramManager->markFilmLikeCurvesComplete();
1921:    updateProgress(valid, 0.0f);
1922:
1923:    exifOutput = exifData;
1924:    return vibrance_saturation_image;
```

### filmulator-gui/core/colorSpaces.cpp:74-76 — forward gamma exists, but see call sites below
```
74://Gamma-compresses linear into sRGB.
75://http://stackoverflow.com/questions/6475373/optimizations-for-pow-with-const-non-integer-exponent
76:float sRGB_forward_gamma(float c)
```

### `sRGB_gammacurve` — complete list of call sites (`grep -rn "sRGB_gammacurve" core/ ui/`)
```
core/colorSpaces.cpp:270:void sRGB_gammacurve(matrix<float> &in,
ui/thumbWriteWorker.cpp:51:    sRGB_gammacurve(small, gammaCurved);
```

### filmulator-gui/ui/parameterManager.h:1-4 / build membership — which sources are compiled
`/private/tmp/eng/filmulator-gui/CMakeLists.txt:75-100` lists `filmulator-gui/core/filmulate.cpp` (line 84); the legacy CLI `core/filmulator.cpp` (which calls a *non-member* `filmulate`, `core/filmulator.cpp:120`) is **not** in that list.

### filmulator-gui/qml/filmulator-gui/EditTools.qml:742 and 984 — UI wording
```
742:                            tooltipText: qsTr("This is a histogram of the input to the film simulation.")
```
```
984:                            tooltipText: qsTr("This is a histogram of the output from the film simulation.")
```

### filmulator-gui/Halide/filmSim.m:1-19 — a MATLAB prototype, not in the CMake build
```
1:function outputCrystalRadii = filmSim(inputImage,filmArea,layerMixConst)
2:  
3:  defaultCrystalSize = 0.00101*ones(size(inputImage));
4:  
5:  developInfluence = 0.001./(1+(inputImage/100000).^(0.75))-0.001; % Actual peak will be at this + 3*0.0134
```

## (d) Verdict

### SOURCE FACTS (verbatim)

1. The "film simulation" is the function `ImagePipeline::filmulate` (`filmulator-gui/core/filmulate.cpp:24`), called from the pipeline as `filmulate(film_input_image, filmulated_image, paramManager, this)` (`filmulator-gui/core/imagePipeline.cpp:1671`), under the comment `// Here we do the film simulation on the image...` (line 1669).
2. It is an iterative time-stepped simulation, not a table lookup: `for(int i = 0; i <= development_steps; i++)` with the comment `//Now we begin the main development/diffusion loop, which approximates the //differential equation of film development.` (`core/filmulate.cpp:120-122`), each iteration calling `develop()` (`:144`), `diffuse()` (`:165`), `layer_mix()` (`:184`), and conditionally `agitate()` (`:201`).
3. Its state variables are physical film/chemistry quantities: `active_crystals_per_pixel` from `exposure()` ("This is literally //akin to exposing film to light", `:69-71`), `crystal_radius`, `silver_salt_density`, `developer_concentration`, `reservoir_developer_concentration` (`:78-97`).
4. The reactions are explicit differential equations: `dCrystalRadR = develConcentration(...) * silverSaltDensity(...) * cgc`, volume `∝ 4*pi*r^2*dr` (`develop.cpp:71-78`), developer consumed (`:94-96`), silver salt consumed (`:104-106`).
5. Diffusion is a physical 2D Gaussian: `//In real film, developer diffuses in a 2D gaussian on the film surface.` (`diffuse.cpp:27`), implemented as repeated box blurs (`#define ORDER 7`, `diffuse.cpp:24`).
6. Output is film density: `output_density(i, j) = crystal_radius(i, j) * crystal_radius(i, j) * active_crystals_per_pixel(i, j);` (`filmulate.cpp:233-234`), under `// Now we compute the density (opacity) of the film.` (`:215`).
7. `grep -rnw "LUT"` over `filmulate.cpp develop.cpp diffuse.cpp agitate.cpp layerMix.cpp exposure.cpp` returns **no matches**. The only `LUT` hits anywhere in the film-simulation source set are in `curves.cpp:137` and `curves.cpp:186`.
8. The one `LUT` class (`core/lut.hpp:29`, table size `MAXVAL 65536` at `:22`) is instantiated only as `LUT<unsigned short> lutR, lutG, lutB;` and `LUT<unsigned short> filmLikeLUT;` (`core/imagePipeline.h:82-83`).
9. That LUT is filled **after** filmulation with a tone curve: `filmLikeLUT.fill(...)` returning `ushort(65535 * default_tonecurve(shResult))` (`imagePipeline.cpp:1879-1884`), then consumed by `film_like_curve(...)` (`:1886`), whose header says it "applies the designated tone curve" (`curves.cpp:130`). `default_tonecurve` is a quadratic Bézier curve (`curves.cpp:23-53`).
10. Pipeline order is `... nrimpulse, nrchroma, prefilmulation, filmulation, blackwhite, colorcurve, filmlikecurve, count` (`ui/parameterManager.h:25-45`).
11. Input domain: raw is decoded to linear (`PARAM.gamm[1] = 1;//Linear gamma (-g 1 1)`, `imread.cpp:49`), JPEG input is linearized with the reverse sRGB transfer function (`imreadJpeg.cpp:61-71`), the in-code annotation for the film input is `matrix<float> pre_film_image; // back to raw` (`imagePipeline.h:113`), and the pre-film conversion `raw_to_sRGB` contains **only** a 3x3 matrix multiply with no transfer function (`colorSpaces.cpp:386-388`). The only sRGB gamma encoding before filmulation is an NR preconditioning step that is explicitly undone (`imagePipeline.cpp:1129-1134` then `// Undo the preconditioning` `:1165-1182`).
12. Output domain: the film output carries the annotation `matrix<float> filmulated_image; // sRGB` (`imagePipeline.h:117`); the next stage `whitepoint_blackpoint` is a linear rescale to `unsigned short` (`whitepointBlackpoint.cpp:15-17`: `subtracted*(65535/whitepoint)`); afterwards everything is `matrix<unsigned short>` (`contrast_image`, `color_curve_image`, `vibrance_saturation_image`, `imagePipeline.h:118-120`) and the final `default: // output` case simply `return vibrance_saturation_image;` (`imagePipeline.cpp:1908-1924`).
13. `sRGB_gammacurve` has exactly two appearances in the whole tree: its definition (`colorSpaces.cpp:270`) and one call in `ui/thumbWriteWorker.cpp:51` (thumbnail writing). It is not called in the main image pipeline.
14. The project's own description: `Filmulator accepts raw files from cameras and simulates the development of film as if exposed to the same light as the camera's sensor.` (`README.md:6`).

### MY INFERENCE

- **Physical development/diffusion model, not a LUT.** The film simulation is a numerically integrated photochemical model (crystal growth, developer/silver-salt depletion, 2D developer diffusion, layer mixing, agitation, density = r²·crystals). No LUT participates in it. The LUT in this codebase belongs to the *post*-film "film-like curve" (tone curve) stage and is a 65536-entry `unsigned short` table, not a film-stock lookup.
- **Where it sits:** after demosaic and noise reduction, before black/white point, color curve and film-like curve. In domain terms the model consumes a linear (raw-derived, camera-WB-applied, 0–65535, unclipped-negative-retaining) RGB signal — the code calls the conversion `raw_to_sRGB` but the function applies no sRGB transfer function, so the name refers to the color-primary matrix, not to gamma encoding.
- The result is *labelled* `// sRGB` and is consumed directly by a linear rescale + tone curve, with no explicit `sRGB_gammacurve` step in the main pipeline. So the film model's output is treated as a display-ready signal rather than being converted from linear to display in a separate stage; I found no Beer-Lambert transmittance conversion (`1 - 10^-D`) anywhere, even though the model's own comment calls its output "density (opacity)". Whether that is intentional (the model is calibrated to land near perceptual values) or an implicit convention is not stated in the source; I am not asserting either.
- Strictly speaking the input is linear *relative* light after camera white balance and highlight recovery, so the model runs in the (scene-)linear domain per this codebase's own usage, but I found no explicit log or tone-domain encoding before it.

### NOT FOUND (with the exact greps run)

- **`viscosity`** — no occurrence of the word (there is a `sigmaConst` / diffusion constant instead). Grep: `grep -rni "viscosity" .` → no matches.
- **A film-simulation LUT / lookup table** — Greps: `grep -rnw "LUT" core/filmulate.cpp core/develop.cpp core/diffuse.cpp core/agitate.cpp core/layerMix.cpp core/exposure.cpp` → no matches; `find . -iname '*.cube' -o -iname '*.lut' -o -iname '*.3dl' -o -iname '*hald*'` → no files.
- **Identifier `FilmSimulation`** — Grep: `grep -rn "FilmSimulation" --include=*.cpp --include=*.h --include=*.hpp --include=*.qml .` → no matches (the type is spelled `filmulateParams` / `FilmParams`).
- **Grain simulation** — Grep: `grep -rni "grain" --include=*.cpp --include=*.h core/ ui/` → no matches (crystal/dye-cloud modelling exists conceptually, but no "grain" symbol).
- **Log-domain encoding** — Grep: `grep -rn "log10\|log2(" --include=*.cpp --include=*.h core/` → no matches.
- **Beer-Lambert / transmittance conversion** — Grep: `grep -rni "transmittance\|optical density\|exp(-" --include=*.cpp --include=*.h core/` → no matches.
