# 取证清单（逐字原文，附 URL）

采集方式说明：全部内容均来自本次实际抓取（web_fetch 或 curl，curl 用于 web_fetch 超时/拒收 MIME 的 URL，下载后用 pypdf 提取 PDF 文本）。抓不到的条目一律写“未找到”，并列出实际尝试的 URL 与失败原因。行号为本次抓取版本的快照行号。

---

## 1. Android camera2：TONEMAP_MODE

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#TONEMAP_MODE
**状态：未找到（fetch 失败）**
- web_fetch 连续 3 次返回：`Error: web fetch failed: TypeError: fetch failed`
- curl 重试（--max-time 25 / 30，带浏览器 UA）均 `curl: (28) Connection timed out`

### 替代实际抓取 URL（同一份官方 javadoc 文本，AOSP 源码）
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CaptureRequest.java （HTTP 200，4488 行）
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraMetadata.java （HTTP 200，4429 行）

**原文（CameraMetadata.java 行 3927-3928 + 3952-3957，章节：`// Enumeration values for CaptureRequest#TONEMAP_MODE`）**

```
    //
    // Enumeration values for CaptureRequest#TONEMAP_MODE
    //
...
    /**
     * <p>High-quality gamma mapping and color enhancement will be applied, at
     * the cost of possibly reduced frame rate compared to raw sensor output.</p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_HIGH_QUALITY = 2;
```

**同段其它枚举（CameraMetadata.java 行 3931-3950）**

```
    /**
     * <p>Use the tone mapping curve specified in
     * the {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}* entries.</p>
     * <p>All color enhancement and tonemapping must be disabled, except
     * for applying the tonemapping curve specified by
     * {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}.</p>
     * <p>Must not slow down frame rate relative to raw
     * sensor output.</p>
     *
     * @see CaptureRequest#TONEMAP_CURVE
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_CONTRAST_CURVE = 0;

    /**
     * <p>Advanced gamma mapping and color enhancement may be applied, without
     * reducing frame rate compared to raw sensor output.</p>
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final int TONEMAP_MODE_FAST = 1;
```

**原文（CaptureRequest.java 行 4168-4195，android.tonemap.mode 的 Key javadoc）**

```
    /**
     * <p>High-level global contrast/gamma/tonemapping control.</p>
     * <p>When switching to an application-defined contrast curve by setting
     * {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} to CONTRAST_CURVE, the curve is defined
     * per-channel with a set of <code>(in, out)</code> points that specify the
     * mapping from input high-bit-depth pixel value to the output
     * low-bit-depth value.  Since the actual pixel ranges of both input
     * and output may change depending on the camera pipeline, the values
     * are specified by normalized floating-point numbers.</p>
     * <p>More-complex color mapping operations such as 3D color look-up
     * tables, selective chroma enhancement, or other non-linear color
     * transforms will be disabled when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>When using either FAST or HIGH_QUALITY, the camera device will
     * emit its own tonemap curve in {@link CaptureRequest#TONEMAP_CURVE android.tonemap.curve}.
     * These values are always available, and as close as possible to the
     * actually used nonlinear/nonglobal transforms.</p>
     * <p>If a request is sent with CONTRAST_CURVE with the camera device's
     * provided curve in FAST or HIGH_QUALITY, the image's tonemap will be
     * roughly the same.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #TONEMAP_MODE_CONTRAST_CURVE CONTRAST_CURVE}</li>
     *   <li>{@link #TONEMAP_MODE_FAST FAST}</li>
     *   <li>{@link #TONEMAP_MODE_HIGH_QUALITY HIGH_QUALITY}</li>
     *   <li>{@link #TONEMAP_MODE_GAMMA_VALUE GAMMA_VALUE}</li>
     *   <li>{@link #TONEMAP_MODE_PRESET_CURVE PRESET_CURVE}</li>
     * </ul>
```

**“local tone mapping / 局部对比”检索结果（负面证据）**
- 在 CaptureRequest.java 全文与 CameraMetadata.java 全文中，大小写不敏感检索 `local tone` / `tone mapping` / `contrast curve`：
  - CaptureRequest.java 仅 1 处命中，行 4170：`* <p>When switching to an application-defined contrast curve by setting`
  - CameraMetadata.java 仅 1 处命中，行 3932：`* <p>Use the tone mapping curve specified in`
- 即：`TONEMAP_MODE_HIGH_QUALITY` 的官方描述中**没有** local tone mapping / 局部对比 字样；HIGH_QUALITY 的原文仅称 “High-quality gamma mapping and color enhancement”。

---

## 2. Android camera2：CONTROL_AE_METERING_MODE

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_METERING_MODE
**状态：未找到（该 Key/枚举在 Android camera2 官方 API 中不存在）**
- 官方页面同域 fetch 失败（同上）。
- 在 AOSP 官方源码中检索：
  - CaptureRequest.java 全文检索 `METERING|metering` 共 35 处命中，全部为 `precapture metering sequence`、`metering areas`（aeRegions/afRegions/awbRegions 描述）、`exposure metering regions` 等；**没有任何** `AE_METERING_MODE` / `aeMeteringMode` 常量。
  - CameraMetadata.java 全文检索 `METERING|metering` 共 10 处命中，同样**没有** `AE_METERING_MODE_FRAME_AVERAGE / CENTER_WEIGHTED / SPOT / PARTIAL` 这类枚举。
- 因此 FRAME_AVERAGE / CENTER_WEIGHTED / SPOT / PARTIAL 这四个枚举名的官方描述：**未找到**（Android camera2 API 未定义它们；AE 区域是通过加权 MeteringRectangle 数组实现的，见下）。

**替代的官方 AE 区域机制原文（CaptureRequest.java 行 1467-1502，android.control.aeRegions 的 Key javadoc）**

```
    /**
     * <p>List of metering areas to use for auto-exposure adjustment.</p>
     * <p>Not available if {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AE android.control.maxRegionsAe} is 0.
     * Otherwise will always be present.</p>
     * <p>The maximum number of regions supported by the device is determined by the value
     * of {@link CameraCharacteristics#CONTROL_MAX_REGIONS_AE android.control.maxRegionsAe}.</p>
...
     * <p>The weight must be within <code>[0, 1000]</code>, and represents a weight
     * for every pixel in the area. This means that a large metering area
     * with the same weight as a smaller area will have more effect in
     * the metering result. Metering areas can partially overlap and the
     * camera device will add the weights in the overlap region.</p>
     * <p>The weights are relative to weights of other exposure metering regions, so if only one
     * region is used, all non-zero weights will have the same effect. A region with 0
     * weight is ignored.</p>
     * <p>If all regions have 0 weight, then no specific metering area needs to be used by the
     * camera device.</p>
```

---

## 3. Android camera2：EDGE_MODE

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#EDGE_MODE
**状态：未找到（fetch 失败，同域超时）**

### 替代实际抓取 URL
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraMetadata.java （HTTP 200）

**原文（CameraMetadata.java 行 3473-3521，章节：`// Enumeration values for CaptureRequest#EDGE_MODE`）**

```
    //
    // Enumeration values for CaptureRequest#EDGE_MODE
    //

    /**
     * <p>No edge enhancement is applied.</p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_OFF = 0;

    /**
     * <p>Apply edge enhancement at a quality level that does not slow down frame rate
     * relative to sensor output. It may be the same as OFF if edge enhancement will
     * slow down frame rate relative to sensor.</p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_FAST = 1;

    /**
     * <p>Apply high-quality edge enhancement, at a cost of possibly reduced output frame rate.</p>
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_HIGH_QUALITY = 2;

    /**
     * <p>Edge enhancement is applied at different
     * levels for different output streams, based on resolution. Streams at maximum recording
     * resolution (see {@link android.hardware.camera2.CameraDevice#createCaptureSession })
     * or below have edge enhancement applied, while higher-resolution streams have no edge
     * enhancement applied. The level of edge enhancement for low-resolution streams is tuned
     * so that frame rate is not impacted, and the quality is equal to or better than FAST
     * (since it is only applied to lower-resolution outputs, quality may improve from FAST).</p>
     * <p>This mode is intended to be used by applications operating in a zero-shutter-lag mode
     * with YUV or PRIVATE reprocessing, where the application continuously captures
     * high-resolution intermediate buffers into a circular buffer, from which a final image is
     * produced via reprocessing when a user takes a picture.  For such a use case, the
     * high-resolution buffers must not have edge enhancement applied to maximize efficiency of
     * preview and to avoid double-applying enhancement when reprocessed, while low-resolution
     * buffers (used for recording or preview, generally) need edge enhancement applied for
     * reasonable preview quality.</p>
     * <p>This mode is guaranteed to be supported by devices that support either the
     * YUV_REPROCESSING or PRIVATE_REPROCESSING capabilities
     * ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} lists either of those capabilities) and it will
     * be the default mode for CAMERA3_TEMPLATE_ZERO_SHUTTER_LAG template.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#EDGE_MODE
     */
    public static final int EDGE_MODE_ZERO_SHUTTER_LAG = 3;
```

---

## 4. Android camera2：NOISE_REDUCTION_MODE（含多帧检索）

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#NOISE_REDUCTION_MODE
**状态：未找到（fetch 失败，同域超时）**

### 替代实际抓取 URL
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraMetadata.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CaptureRequest.java

**原文（CameraMetadata.java 行 3597-3653，章节：`// Enumeration values for CaptureRequest#NOISE_REDUCTION_MODE`）**

```
    //
    // Enumeration values for CaptureRequest#NOISE_REDUCTION_MODE
    //

    /**
     * <p>No noise reduction is applied.</p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_OFF = 0;

    /**
     * <p>Noise reduction is applied without reducing frame rate relative to sensor
     * output. It may be the same as OFF if noise reduction will reduce frame rate
     * relative to sensor.</p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_FAST = 1;

    /**
     * <p>High-quality noise reduction is applied, at the cost of possibly reduced frame
     * rate relative to sensor output.</p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_HIGH_QUALITY = 2;

    /**
     * <p>MINIMAL noise reduction is applied without reducing frame rate relative to
     * sensor output. </p>
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_MINIMAL = 3;

    /**
     * <p>Noise reduction is applied at different levels for different output streams,
     * based on resolution. Streams at maximum recording resolution (see {@link android.hardware.camera2.CameraDevice#createCaptureSession })
     * or below have noise reduction applied, while higher-resolution streams have MINIMAL (if
     * supported) or no noise reduction applied (if MINIMAL is not supported.) The degree of
     * noise reduction for low-resolution streams is tuned so that frame rate is not impacted,
     * and the quality is equal to or better than FAST (since it is only applied to
     * lower-resolution outputs, quality may improve from FAST).</p>
     * <p>This mode is intended to be used by applications operating in a zero-shutter-lag mode
     * with YUV or PRIVATE reprocessing, where the application continuously captures
     * high-resolution intermediate buffers into a circular buffer, from which a final image is
     * produced via reprocessing when a user takes a picture.  For such a use case, the
     * high-resolution buffers must not have noise reduction applied to maximize efficiency of
     * preview and to avoid over-applying noise filtering when reprocessing, while
     * low-resolution buffers (used for recording or preview, generally) need noise reduction
     * applied for reasonable preview quality.</p>
     * <p>This mode is guaranteed to be supported by devices that support either the
     * YUV_REPROCESSING or PRIVATE_REPROCESSING capabilities
     * ({@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES android.request.availableCapabilities} lists either of those capabilities) and it will
     * be the default mode for CAMERA3_TEMPLATE_ZERO_SHUTTER_LAG template.</p>
     *
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CaptureRequest#NOISE_REDUCTION_MODE
     */
    public static final int NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG = 4;
```

**原文（CaptureRequest.java 行 3259-3285，android.noiseReduction.mode 的 Key javadoc）**

```
    /**
     * <p>Mode of operation for the noise reduction algorithm.</p>
     * <p>The noise reduction algorithm attempts to improve image quality by removing
     * excessive noise added by the capture process, especially in dark conditions.</p>
     * <p>OFF means no noise reduction will be applied by the camera device, for both raw and
     * YUV domain.</p>
     * <p>MINIMAL means that only sensor raw domain basic noise reduction is enabled ,to remove
     * demosaicing or other processing artifacts. For YUV_REPROCESSING, MINIMAL is same as OFF.
     * This mode is optional, may not be support by all devices. The application should check
     * {@link CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES android.noiseReduction.availableNoiseReductionModes} before using it.</p>
     * <p>FAST/HIGH_QUALITY both mean camera device determined noise filtering
     * will be applied. HIGH_QUALITY mode indicates that the camera device
     * will use the highest-quality noise filtering algorithms,
     * even if it slows down capture rate. FAST means the camera device will not
     * slow down capture rate when applying noise filtering. FAST may be the same as MINIMAL if
     * MINIMAL is listed, or the same as OFF if any noise filtering will slow down capture rate.
     * Every output stream will have a similar amount of enhancement applied.</p>
     * <p>ZERO_SHUTTER_LAG is meant to be used by applications that maintain a continuous circular
     * buffer of high-resolution images during preview and reprocess image(s) from that buffer
     * into a final capture when triggered by the user. In this mode, the camera device applies
     * noise reduction to low-resolution streams (below maximum recording resolution) to maximize
     * preview quality, but does not apply noise reduction to high-resolution streams, since
     * those will be reprocessed later if necessary.</p>
     * <p>For YUV_REPROCESSING, these FAST/HIGH_QUALITY modes both mean that the camera device
     * will apply FAST/HIGH_QUALITY YUV domain noise reduction, respectively. The camera device
     * may adjust the noise reduction parameters for best image quality based on the
     * {@link CaptureRequest#REPROCESS_EFFECTIVE_EXPOSURE_FACTOR android.reprocess.effectiveExposureFactor} if it is set.</p>
```

**关于“多帧降噪是否放在 NOISE_REDUCTION_MODE_HIGH_QUALITY”的检索结果**
- 官方 API 中**不存在** `NOISE_REDUCTION_MODE_MULTI_FRAME` 之类的枚举（CameraMetadata.java 该章节只有 OFF / FAST / HIGH_QUALITY / MINIMAL / ZERO_SHUTTER_LAG）。
- `NOISE_REDUCTION_MODE_HIGH_QUALITY` 的官方描述原文（见上）**没有** multi-frame / 多帧 字样。
- 全文检索 `multi.frame|multiframe`：
  - CameraMetadata.java：0 处命中
  - CaptureRequest.java：2 处命中（行 4351、4358），出现在 **android.reprocess.effectiveExposureFactor** 的 javadoc 中，不是 NOISE_REDUCTION_MODE 的描述。

**原文（CaptureRequest.java 行 4337-4363，android.reprocess.effectiveExposureFactor 的 Key javadoc）**

```
    /**
     * <p>The amount of exposure time increase factor applied to the original output
     * frame by the application processing before sending for reprocessing.</p>
...
     * <p>For some YUV reprocessing use cases, the application may choose to filter the original
     * output frames to effectively reduce the noise to the same level as a frame that was
     * captured with longer exposure time. To be more specific, assuming the original captured
     * images were captured with a sensitivity of S and an exposure time of T, the model in
     * the camera device is that the amount of noise in the image would be approximately what
     * would be expected if the original capture parameters had been a sensitivity of
     * S/effectiveExposureFactor and an exposure time of T*effectiveExposureFactor, rather
     * than S and T respectively. If the captured images were processed by the application
     * before being sent for reprocessing, then the application may have used image processing
     * algorithms and/or multi-frame image fusion to reduce the noise in the
     * application-processed images (input images). By using the effectiveExposureFactor
     * control, the application can communicate to the camera device the actual noise level
     * improvement in the application-processed image. With this information, the camera
     * device can select appropriate noise reduction and edge enhancement parameters to avoid
     * excessive noise reduction ({@link CaptureRequest#NOISE_REDUCTION_MODE android.noiseReduction.mode}) and insufficient edge
     * enhancement ({@link CaptureRequest#EDGE_MODE android.edge.mode}) being applied to the reprocessed frames.</p>
     * <p>For example, for multi-frame image fusion use case, the application may fuse
     * multiple output frames together to a final frame for reprocessing. When N image are
     * fused into 1 image for reprocessing, the exposure time increase factor could be up to
     * square root of N (based on a simple photon shot noise model). The camera device will
     * adjust the reprocessing noise reduction and edge enhancement parameters accordingly to
     * produce the best quality images.</p>
```

---

## 5. Android camera2：SENSOR_SENSITIVITY

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SENSOR_SENSITIVITY
**状态：未找到（fetch 失败，同域超时）**

### 替代实际抓取 URL
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CaptureRequest.java （HTTP 200）

**原文（CaptureRequest.java 行 3681-3705，android.sensor.sensitivity 的 Key javadoc）**

```
    /**
     * <p>The amount of gain applied to sensor data
     * before processing.</p>
     * <p>The sensitivity is the standard ISO sensitivity value,
     * as defined in ISO 12232:2006.</p>
     * <p>The sensitivity must be within {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}, and
     * if if it less than {@link CameraCharacteristics#SENSOR_MAX_ANALOG_SENSITIVITY android.sensor.maxAnalogSensitivity}, the camera device
     * is guaranteed to use only analog amplification for applying the gain.</p>
     * <p>If the camera device cannot apply the exact sensitivity
     * requested, it will reduce the gain to the nearest supported
     * value. The final sensitivity used will be available in the
     * output capture result.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} or {@link CaptureRequest#CONTROL_MODE android.control.mode} is set to
     * OFF; otherwise the auto-exposure algorithm will override this value. However, in the
     * case that {@link CaptureRequest#CONTROL_AE_PRIORITY_MODE android.control.aePriorityMode} is set to SENSOR_SENSITIVITY_PRIORITY, this
     * control will be effective and not controlled by the auto-exposure algorithm.</p>
...
     * <p><b>Units</b>: ISO arithmetic units</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraCharacteristics#SENSOR_INFO_SENSITIVITY_RANGE android.sensor.info.sensitivityRange}</p>
```

---

## 6. Android camera2：CONTROL_AE_EXPOSURE_COMPENSATION 与 CONTROL_AE_PRECAPTURE_TRIGGER

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION
**状态：未找到（fetch 失败，同域超时）**

### 替代实际抓取 URL
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CaptureRequest.java （HTTP 200）

**原文（CaptureRequest.java 行 1314-1335，android.control.aeExposureCompensation 的 Key javadoc）**

```
    /**
     * <p>Adjustment to auto-exposure (AE) target image
     * brightness.</p>
     * <p>The adjustment is measured as a count of steps, with the
     * step size defined by {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_STEP android.control.aeCompensationStep} and the
     * allowed range by {@link CameraCharacteristics#CONTROL_AE_COMPENSATION_RANGE android.control.aeCompensationRange}.</p>
     * <p>For example, if the exposure value (EV) step is 0.333, '6'
     * will mean an exposure compensation of +2 EV; -3 will mean an
     * exposure compensation of -1 EV. One EV represents a doubling
     * of image brightness. Note that this control will only be
     * effective if {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} <code>!=</code> OFF. This control
     * will take effect even when {@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} <code>== true</code>.</p>
     * <p>In the event of exposure compensation value being changed, camera device
     * may take several frames to reach the newly requested exposure target.
     * During that time, {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} field will be in the SEARCHING
     * state. Once the new exposure target is reached, {@link CaptureResult#CONTROL_AE_STATE android.control.aeState} will
     * change from SEARCHING to either CONVERGED, LOCKED (if AE lock is enabled), or
     * FLASH_REQUIRED (if the scene is too dark for still capture).</p>
     * <p><b>Units</b>: Compensation steps</p>
```

### URL：https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
**状态：未找到（fetch 失败，同域超时）**

**原文（CaptureRequest.java 行 1594-1617，android.control.aePrecaptureTrigger 的 Key javadoc；常量定义在行 1670）**

```
    /**
     * <p>Whether the camera device will trigger a precapture
     * metering sequence when it processes this request.</p>
     * <p>This entry is normally set to IDLE, or is not
     * included at all in the request settings. When included and
     * set to START, the camera device will trigger the auto-exposure (AE)
     * precapture metering sequence.</p>
     * <p>When set to CANCEL, the camera device will cancel any active
     * precapture metering trigger, and return to its initial AE state.
     * If a precapture metering sequence is already completed, and the camera
     * device has implicitly locked the AE for subsequent still capture, the
     * CANCEL trigger will unlock the AE and return to its initial AE state.</p>
     * <p>The precapture sequence should be triggered before starting a
     * high-quality still capture for final metering decisions to
     * be made, and for firing pre-capture flash pulses to estimate
     * scene brightness and required final capture flash power, when
     * the flash is enabled.</p>
...
     * <p>When a precapture metering sequence is finished, the camera device
     * may lock the auto-exposure routine internally to be able to accurately expose the
     * subsequent still capture image (<code>{@link CaptureRequest#CONTROL_CAPTURE_INTENT android.control.captureIntent} == STILL_CAPTURE</code>).
```

---

## 7. AOSP camera3.h：AE 状态机与“AE target”检索

### URL：https://raw.githubusercontent.com/aosp-mirror/platform_hardware_libhardware/master/include/hardware/camera3.h
**状态：抓取到的是 git 符号链接内容，不是头文件本体。原文全文（逐字）如下：**

```
../../include_all/hardware/camera3.h
```

### 实际抓取 URL（符号链接目标，HTTP 200，正文被截断但完整文本已落盘可检索）
https://raw.githubusercontent.com/aosp-mirror/platform_hardware_libhardware/master/include_all/hardware/camera3.h

**原文（camera3.h，章节 `S4.2. Auto-exposure settings and result entries:`）**

```
 * S4.2. Auto-exposure settings and result entries:
 *
 *  Main metadata entries:
 *
 *   ANDROID_CONTROL_AE_MODE: Control for selecting the current auto-exposure
 *       mode. Set by the framework in the request settings.
 *
 *     AE_MODE_OFF: Autoexposure is disabled; the user controls exposure, gain,
 *         frame duration, and flash.
 *
 *     AE_MODE_ON: Standard autoexposure, with flash control disabled. User may
 *         set flash to fire or to torch mode.
 *
 *     AE_MODE_ON_AUTO_FLASH: Standard autoexposure, with flash on at HAL's
 *         discretion for precapture and still capture. User control of flash
 *         disabled.
 *
 *     AE_MODE_ON_ALWAYS_FLASH: Standard autoexposure, with flash always fired
 *         for capture, and at HAL's discretion for precapture.. User control of
 *         flash disabled.
 *
 *     AE_MODE_ON_AUTO_FLASH_REDEYE: Standard autoexposure, with flash on at
 *         HAL's discretion for precapture and still capture. Use a flash burst
 *         at end of precapture sequence to reduce redeye in the final
 *         picture. User control of flash disabled.
 *
 *   ANDROID_CONTROL_AE_STATE: Dynamic metadata describing the current AE
 *       algorithm state, reported by the HAL in the result metadata.
 *
 *     AE_STATE_INACTIVE: Initial AE state after mode switch. When the device is
 *         opened, it must start in this state.
 *
 *     AE_STATE_SEARCHING: AE is not converged to a good value, and is adjusting
 *         exposure parameters.
 *
 *     AE_STATE_CONVERGED: AE has found good exposure values for the current
 *         scene, and the exposure parameters are not changing. HAL may
 *         spontaneously leave this state to search for better solution.
 *
 *     AE_STATE_LOCKED: AE has been locked with the AE_LOCK control. Exposure
 *         values are not changing.
 *
 *     AE_STATE_FLASH_REQUIRED: The HAL has converged exposure, but believes
 *         flash is required for a sufficiently bright picture. Used for
 *         determining if a zero-shutter-lag frame can be used.
 *
 *     AE_STATE_PRECAPTURE: The HAL is in the middle of a precapture
 *         sequence. Depending on AE mode, this mode may involve firing the
 *         flash for metering, or a burst of flash pulses for redeye reduction.
 *
 *   ANDROID_CONTROL_AE_PRECAPTURE_TRIGGER: Control for starting a metering
 *       sequence before capturing a high-quality image. Set by the framework in
 *       the request settings.
 *
 *      PRECAPTURE_TRIGGER_IDLE: No current trigger.
 *
 *      PRECAPTURE_TRIGGER_START: Start a precapture sequence. The HAL should
 *         use the subsequent requests to measure good exposure/white balance
 *         for an upcoming high-resolution capture.
 *
 *  Additional metadata entries:
 *
 *   ANDROID_CONTROL_AE_LOCK: Control for locking AE controls to their current
 *       values
 *
 *   ANDROID_CONTROL_AE_EXPOSURE_COMPENSATION: Control for adjusting AE
 *       algorithm target brightness point.
 *
 *   ANDROID_CONTROL_AE_TARGET_FPS_RANGE: Control for selecting the target frame
 *       rate range for the AE algorithm. The AE routine cannot change the frame
 *       rate to be outside these bounds.
 *
 *   ANDROID_CONTROL_AE_REGIONS: Control for selecting the regions of the FOV
 *       that should be used to determine good exposure levels. This applies to
 *       all AE modes besides OFF.
```

**“AE target” 数值定义检索结果（负面证据）**
- 在 camera3.h 全文中检索 `target|histogram|converge|precapture` 共 41 处命中，全部为上述状态机文本、`android.control.aeTargetFpsRange`、`aePrecaptureTrigger`、`high speed FPS ranges` 等；**未出现** “AE target” 的数值定义（例如 0.5–1.0 的 target 比例）。

---

## 8. DNG / DCP：ProfileHueSatMap 的处理阶段与色彩空间

### URL：https://raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/dcp.cc （HTTP 200）
**原文（DCPProfile::apply，行 1408-1520，关键注释与调用）**

```
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
...
    if (!apply_hue_sat_map) {
        // The fast path: No LUT --> Calculate matrix for direct conversion raw -> working space
...
    } else {
        // LUT available --> Calculate matrix for conversion raw>ProPhoto
...
        // Convert to ProPhoto and apply LUT
...
                if (LIKELY(Color::rgb2hsvdcp(newr, newg, newb, h , s, v))) {

                    hsdApply(delta_info, delta_base, h, s, v);
...
                img->r(y, x) = work[0][0] * newr + work[0][1] * newg + work[0][2] * newb;
```

**原文（DCPProfile::step2ApplyTile，行 1496-1512 与 1575 起，说明 HueSatMap 之后的顺序：LookTable → ToneCurve）**

```
                // with looktable and tonecurve we need to clip
                if (as_in.data->apply_look_table || as_in.data->use_tone_curve) {
...
                if (as_in.data->apply_look_table) {
...
                    hsdApply(look_info, look_table, h, s, v);
...
                if (as_in.data->use_tone_curve) {
                    tone_curve.Apply(newr, newg, newb);
                }
```

**原文（DCPProfile::setStep2ApplyState，行 1523-1540，BaselineExposureOffset 的处理）**

```
void DCPProfile::setStep2ApplyState(const Glib::ustring& working_space, bool use_tone_curve, bool apply_look_table, bool apply_baseline_exposure, DCPProfileApplyState& as_out)
...
    if (has_baseline_exposure_offset && apply_baseline_exposure) {
        as_out.data->bl_scale = powf(2, baseline_exposure_offset);
```

**原文（HueSatMap 维度读取，行 1232-1245；TAG 常量见行 855-868）**

```
        TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS = 50937,
        TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1 = 50938,
        TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_2 = 50939,
        TAG_KEY_PROFILE_TONE_CURVE = 50940,
...
        TAG_KEY_BASELINE_EXPOSURE_OFFSET = 51109
...
    if (md.find(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS)) {
        delta_info.hue_divisions = md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS, 0);
        delta_info.sat_divisions = md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS, 4);
        delta_info.val_divisions = md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_DIMS, 8);

        delta_info.srgb_gamma = md.find(TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING) && md.toInt(TAG_KEY_PROFILE_HUE_SAT_MAP_ENCODING);

        delta_info.array_count = md.getCount(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1) / 3;
...
            deltas_1[i].hue_shift = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1, (i * 3) * tiff_float_size);
            deltas_1[i].sat_scale = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1, (i * 3 + 1) * tiff_float_size);
            deltas_1[i].val_scale = md.toDouble(TAG_KEY_PROFILE_HUE_SAT_MAP_DATA_1, (i * 3 + 2) * tiff_float_size);
```

**原文（hsdApply 行 2019 起，注释与 sRGB gamma 分支）**

```
    // Apply the HueSatMap. Ported from Adobes reference implementation.
...
        if (table_info.srgb_gamma) {
            v_encoded = Color::gammatab_srgb1[v * 65535.f];
        }
```

### URL：https://raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/dcp.h （HTTP 200）
**原文（HsdTableInfo 与 apply 签名）**

```
    void apply(
        Imagefloat* img,
        int preferred_illuminant,
        const Glib::ustring& working_space,
        const ColorTemp& white_balance,
        const Triple& pre_mul,
        const Matrix& cam_wb_matrix,
        bool apply_hue_sat_map = true
    ) const;
    void setStep2ApplyState(const Glib::ustring& working_space, bool use_tone_curve, bool apply_look_table, bool apply_baseline_exposure, DCPProfileApplyState& as_out);
    void step2ApplyTile(float* r, float* g, float* b, int width, int height, int tile_width, const DCPProfileApplyState& as_in) const;
...
    struct HsdTableInfo {
        int hue_divisions;
        int sat_divisions;
        int val_divisions;
        int hue_step;
        int val_step;
        unsigned int array_count;
        bool srgb_gamma;
```

### URL：https://raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/dcpcamconst.h
**状态：未找到（fetch 失败/404）**
原文响应（逐字）：
```
404: Not Found
```

### URL：https://helpx.adobe.com/content/dam/help/en/photoshop/pdf/dng_spec_1_6_0_0.pdf
**状态：未找到（Access Denied，HTTP 403）**
- web_fetch：`Error: unsupported content type "application/pdf"`
- curl 下载得到 HTML 错误页（逐字）：
```
<HTML><HEAD>
<TITLE>Access Denied</TITLE>
</HEAD><BODY>
<H1>Access Denied</H1>
 
You don't have permission to access "http&#58;&#47;&#47;helpx&#46;adobe&#46;com&#47;content&#47;dam&#47;help&#47;en&#47;photoshop&#47;pdf&#47;dng&#95;spec&#95;1&#95;6&#95;0&#95;0&#46;pdf" on this server.<P>
```
- 其他镜像尝试（均未取到规范 PDF）：`https://wwwimages2.adobe.com/.../dng_spec_1.4.0.0.pdf` → 404；`https://www.adobe.com/.../dng_spec_1.4.0.0.pdf` → 连接失败；`https://web.archive.org/...` → 连接超时。
- 因此 **DNG 规范原文（PDF 正文）未找到**；下面的阶段/顺序证据来自 DNG SDK 源码注释（第三方 GitHub 镜像）。

### 替代实际抓取 URL（Adobe DNG SDK 源码的第三方镜像，非 Adobe 官方域名）
- https://raw.githubusercontent.com/aizvorski/dng_sdk/master/source/dng_render.cpp （HTTP 200）
- https://raw.githubusercontent.com/aizvorski/dng_sdk/master/source/dng_hue_sat_map.cpp （HTTP 200）
- https://raw.githubusercontent.com/aizvorski/dng_sdk/master/source/dng_hue_sat_map.h （HTTP 200）

**原文（dng_hue_sat_map.h 行 31-48，类与 HSBModify 的注释）**

```
/// \brief A 3D table that maps HSV (hue, saturation, and value) floating-point
/// input coordinates in the range [0,1] to delta signals. The table must have at
/// least 1 sample in the hue dimension, at least 2 samples in the saturation
/// dimension, and at least 1 sample in the value dimension. Tables are stored in
/// value-hue-saturation order.

class dng_hue_sat_map
	{
...
		/// HSV delta signal. \param fHueShift is a delta value specified in degrees.
		/// This parameter, added to the original hue, determines the output hue. A
		/// value of 0 means no change. \param fSatScale and \param fValScale are
		/// scale factors that are applied to saturation and value components,
		/// respectively. These scale factors, multiplied by the original saturation
		/// and value, determine the output saturation and value. A scale factor of
		/// 1.0 means no change.
```

**原文（dng_hue_sat_map.cpp 行 100-123，SetDivisions）**

```
void dng_hue_sat_map::SetDivisions (uint32 hueDivisions,
									uint32 satDivisions,
									uint32 valDivisions)
	{

	DNG_ASSERT (hueDivisions >= 1, "Must have at least 1 hue division.");
	DNG_ASSERT (satDivisions >= 2, "Must have at least 2 sat divisions.");
	
	if (valDivisions == 0)
		valDivisions = 1;
...
	fHueStep = satDivisions;
	fValStep = hueDivisions * fHueStep;
```

**原文（dng_render.cpp 行 1055-1123，处理顺序：Hue/Sat map → exposure curve → look table → baseline tone curve → final color space）**

```
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
										 fHueSatMapDecode.Get ());
					
					}
...
		// Apply exposure curve.
		
		DoBaseline1DTable (tPtrR,
						   tPtrR,
						   srcCols,
						   fExposureRamp);
...
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
								 fLookTableDecode.Get ());
			
			}

		// Apply baseline tone curve.
		
		DoBaselineRGBTone (tPtrR,
					       tPtrG,
						   tPtrB,
						   tPtrR,
					       tPtrG,
						   tPtrB,
						   srcCols,
						   fToneCurve);
						   
		// Convert to final color space.
```

**原文（dng_render.cpp 行 861-871 与 906-946，ProPhoto 工作空间、BaselineExposure 与 tone curve 的关系）**

```
		fCameraToRGB = dng_space_ProPhoto::Get ().MatrixFromPCS () *
					   spec->CameraToPCS ();
					   
		// Find Hue/Sat table, if any.
		
		const dng_camera_profile *profile = fNegative.ProfileByID (profileID);
		
		if (profile)
			{
			
			fHueSatMap.Reset (profile->HueSatMapForWhite (spec->WhiteXY ()));
...
	// Compute exposure/shadows ramp.

	real64 exposure = fParams.Exposure () +
					  fNegative.TotalBaselineExposure (profileID) -
					  (log (fNegative.Stage3Gain ()) / log (2.0));
...
	// Compute tone curve.
	
		{
		
		// If there is any negative exposure compenation to perform
		// (beyond what the camera provides for with its baseline exposure),
		// we fake this by darkening the tone curve.
		
		dng_function_exposure_tone exposureTone (exposure);
		
		dng_1d_concatenate totalTone (exposureTone,
									  fParams.ToneCurve ());
		
		fToneCurve.Initialize (*allocator, totalTone);
				
		}
```

---

## 9. 18% 灰 / 12% 灰（目标 3）

### URL：https://www.itu.int/rec/R-REC-BT.2408
**状态：抓取成功但正文为空**
- web_fetch 返回 HTTP 200，正文为空字符串（无可用原文）。
- curl 探测 `https://www.itu.int/rec/R-REC-BT.2408/en` → HTTP 200，content-length 仅 245 字节（无正文）。

### URL：https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2408-7-2023-PDF-E.pdf
**web_fetch 状态：未找到（fetch 失败/406）。原文响应（逐字）：**

```
 406 - Client browser does not accept the MIME type of the requested page. 

# Server Error

406 - Client browser does not accept the MIME type of the requested page. 

The page you are looking for cannot be opened by your browser because it has a file name extension that your browser does not accept.
```

**实际抓取方式**：curl（带 `Accept: application/pdf,*/*` + 浏览器 UA）分多次续传，最终完整下载 `bt2408.pdf`：`PDF document, version 1.7, 88 pages`，大小 4,343,821 字节；用 pypdf 提取文本（`PdfReader('/tmp/bt2408.pdf')` → `pages: 88`）。

**原文（BT.2408-7，第 8 页，章节 §2.2 "Signal levels for line-up in production"，TABLE 1 "Nominal levels for PQ and HLG production"）**

```
TABLE 1 
Nominal levels for PQ and HLG production 
 
3  “Luminance factor” is the ratio of the luminance of the surface element in the given direction to the 
luminance of a perfect reflecting or transmitting diffuser identically illuminated. 
Reflectance object or reference 
(luminance factor, %)3 
Nominal luminance, 
cd/m2 
(for a PQ reference 
display, or a 1 000 cd/m2 
HLG display) 
Nominal signal level 
%PQ %HLG 
Grey Card (18%) (1) 26 38 38 
Greyscale Chart Max (83%) 162 56 71 
Greyscale Chart Max (90%) 179 57 73 
Reference Level: HDR Reference White 
(100%) (1) also diffuse white and Graphics 
White 
203 58 75 
(1) The actual signal levels for an 18% grey card may differ significantly where camera painting controls 
have been applied. 
(2) The signal level of “HDR Reference White” is not directly related to the signal level of SDR “peak 
white”.  
```

**原文（BT.2408-7，第 8 页，§2.2 正文中关于 18% 灰卡）**

```
It is important to know the reflectance of greyscale charts and white cards, to ensure that cameras are 
aligned to deliver the appropriate signal level and consistency in production. 
An 18% grey card is commonly used for camera set -up in non -live workflows as it is the closest 
standard reflectance card to skin tones. It may also be useful when trying to match SDR and HDR 
cameras as the 18% grey should not be affected by any SDR camera “knee”. However, as the 18% 
grey card is close to “middle grey” (i.e. halfway between black and diffuse white on a lightness scale) 
it is likely to be affected by camera painting in live production or colour grading in non -live 
production. So, the nominal signal levels for the 18% grey card will likely vary once artistic 
adjustments have been applied. 
A 75%-HLG or 58%-PQ marker on a waveform monitor, representing the reference level, will help 
the camera shader ensure that objects placed at the centre of interest within a scene are placed within 
the appropriate signal range, and that sufficient headroom is reserved for specular highlights. 
```

**原文（BT.2408-7，第 7 页，§2.1 "HDR Reference White"）**

```
The reference level, HDR Reference White, is defined in this Report as the nominal signa l level 
obtained from an HDR camera and a 100% reflectance white card resulting in a nominal luminance 
of 203 cd/m2 on a PQ display or on an HLG display that has a nominal peak luminance capability of 
1 000 cd/m2. That is the signal level that would result from a 100% Lambertian reflector placed at the 
centre of interest within a scene under controlled lighting, commonly referred to as diffuse white 1. 
```

**原文（BT.2408-7，第 46 页，Annex 1，18% 灰卡的 34%PQ / 45%PQ 数值）**

```
Using the BT.2100 reference PQ OOTF, 26 cd/m 2 may be related to relative scene exposure. Then 
the 25% and 18% reflectivity relationship may be used to solve for the appropriate 18% grey card 
level: 17 cd/m2 on a PQ reference display or 34% on the PQ scale. This is the expected luminance for 
a grey card anchor in HDR broadcast content for indoor scenes, for content consistent with existing 
practice. A diffuse white would be expected to yield 54%PQ. 
By segmenting HDR indoor and outdoor scenes, it was found that outdoor skin tones were an average 
of 1.7 stops brighter than indoor skin tones. Assuming a 1.7 stop increase in brightness from an indoor 
to outdoor scene, the exposure for an 18% grey card outdoors would be set to 45%PQ. 
The Table below summarizes Dolby’s findings for current content; these values could be considered 
tentative recommendations on settings of an 18% grey card and diffuse white objects in terms of both 
%PQ value and reference display brightness. 
 
 Indoor Outdoor 
 cd/m2 %PQ cd/m2 %PQ 
18% Grey Card 17 34 57 45 
Diffuse White 140 54 425 66 
```

**关于 “SDR 下 0.38 / 0.41 / 0.45” 的检索结果**
- 在 BT.2408-7 全文提取文本中检索 `0.38|0.41|0.45` 及含 `SDR` 且带数值的句子：**未找到** “SDR 下 18% 灰 = 0.38 / 0.41 / 0.45” 这类表述。
- 实际检索到的 18% 灰卡电平为：`38 %PQ`、`38 %HLG`（表 1，即 0.38），以及 Annex 1 的 `34%PQ`（室内）与 `45%PQ`（室外）。
- BT.2408-7 中与 SDR 电平相关的原文示例（第 9 页）：
```
Association football (soccer) grass is typically reproduced correctly with an HLG signal level of 40%, 
corresponding to the well -established level of 50% SDR after display -light down -mapping for a 
100 cd/m2 SDR display.
```

### 12% 灰（相机测光 12% 灰）——**未找到**
尝试过的 URL 与结果（均未取得可引用原文）：
- https://www.kodak.com/global/en/professional/support/techPubs/e277/e277.pdf → HTTP 404
- https://www.kodak.com/content/dam/kodak/professional/support/techPubs/e277/e277.pdf → HTTP 404
- https://www.kodak.com/en/motion/page/kodak-gray-card → HTTP 404
- https://www.sekonic.com/why-18-percent-gray → HTTP 404
- https://www.sekonic.com/blog/why-18-gray → HTTP 404
- https://www.sekonic.com/support/downloads → HTTP 200，但页面内未提取到任何 `.pdf` 链接（无 12%/18% 文本）
- https://tech.ebu.ch/publications/tech3320 → HTTP 403
- https://en.wikipedia.org/wiki/Gray_card → web_fetch 报错：`URL hostname "en.wikipedia.org" resolves to a non-public IP address`
- https://web.archive.org/... → 连接超时（`Failed to connect to web.archive.org port 443`）
- 本次会话 web_search 不可用：`Error: DeepSeek search has no API key for "DEEPSEEK_API_KEY"`，因此无法检索未预知的一手 URL。
- 结论：**未找到（fetch 失败/404）**，没有可逐字引用的 12% 灰一手文本。

---

## 10. libcamera AGC 目标亮度默认值

### URL：https://raw.githubusercontent.com/raspberrypi/libcamera/main/src/ipa/rpi/controller/rpi/agc.cpp （HTTP 200）
**说明**：该文件（Agc 顶层封装）内**没有** target 亮度默认值；检索 `target` 仅命中 `yTarget`/`targetExposure` 等调用点。target 来自 tuning 数据与 agc_channel.cpp。

**原文（agc.cpp，process 中调用 channel.process）**

```
void Agc::process(StatisticsPtr &stats, Metadata *imageMetadata)
{
	/*
	 * We want to generate values for the next channel in round robin fashion
...
	channelData.channel.process(*statsPtr, deviceStatus, imageMetadata, channelTotalExposures_);
```

### 补充抓取 URL：https://raw.githubusercontent.com/raspberrypi/libcamera/main/src/ipa/rpi/controller/rpi/agc_channel.cpp （HTTP 200）
**原文（AgcConfig::read，y_target 从 tuning 读取，无硬编码默认值）**

```
	yTarget = params["y_target"].get<ipa::Pwl>(ipa::Pwl{});
	if (yTarget.empty())
		return -EINVAL;
```

**原文（AgcChannel::computeGain，target 用法）**

```
	targetY = config_.yTarget.eval(config_.yTarget.domain().clamp(lux.lux));
	targetY = std::min(EvGainYTargetLimit, targetY * evGain);
...
		double initialY = computeInitialY(statistics, awb_, meteringMode_->weights, gain);
		double extraGain = std::min(10.0, targetY / (initialY + .001));
```

### 补充抓取 URL：https://raw.githubusercontent.com/raspberrypi/libcamera/main/src/ipa/rpi/vc4/data/imx219.json （HTTP 200）
**原文（imx219.json 行 292-296，默认 y_target = 0.16）**

```
                        "y_target":
                        [
                            0, 0.16,
                            1000, 0.165,
                            10000, 0.17
```

### URL：https://raw.githubusercontent.com/libcamera-org/libcamera/master/src/ipa/rkisp1/algorithms/agc.cpp （HTTP 200）
**原文（AgcTraits::estimateLuminance 的文档注释，说明 1.0 对应 100% 参考白）**

```
	/**
	 * \brief Estimate the relative luminance of the frame with a given gain
	 * \param[in] gain The gain to apply to the frame
...
	 * The values are normalized to the [0.0, 1.0] range, where 1.0 corresponds to a
	 * theoretical perfect reflector of 100% reference white.
	 *
	 * More detailed information can be found in:
	 * https://en.wikipedia.org/wiki/Relative_luminance
	 *
	 * \return The relative luminance
	 */
	double estimateLuminance(double gain) const override
```

**原文（rkisp1 agc.cpp，process 中把 yHist 交给 agc_ 基类）**

```
			.yHist = {
				/* The lower 4 bits are fractional and meant to be discarded. */
				{ params->hist.hist_bins, context.hw.numHistogramBins },
				[](uint32_t x) { return x >> 4; },
			},
```

### 关键补充抓取 URL：https://raw.githubusercontent.com/libcamera-org/libcamera/master/src/ipa/libipa/agc_mean_luminance.cpp （HTTP 200）
**原文（target 默认值 0.16 及其含义注释）**

```
/*
 * Default relative luminance target
 *
 * This value should be chosen so that when the camera points at a grey target,
 * the resulting image brightness looks "right". Custom values can be passed
 * as the relativeLuminanceTarget value in sensor tuning files.
 */
static constexpr double kDefaultRelativeLuminanceTarget = 0.16;

/*
 * Maximum relative luminance target
 *
 * This value limits the relative luminance target after applying the exposure
 * compensation. Targeting a value above this limit results in saturation
 * and the inability to regulate properly.
 */
static constexpr double kMaxRelativeLuminanceTarget = 0.95;
```

**原文（agc_mean_luminance.cpp，parseRelativeLuminanceTarget 使用该默认值）**

```
int AgcMeanLuminance::parseRelativeLuminanceTarget(const ValueNode &tuningData)
{
	auto &target = tuningData["relativeLuminanceTarget"];
	if (!target) {
		relativeLuminanceTarget_ = { { { { 0.0, kDefaultRelativeLuminanceTarget } } } };
		return 0;
	}
```

**原文（agc_mean_luminance.cpp，时间平滑 filterExposure）**

```
/*
 * Number of frames for which to run the algorithm at full speed, before slowing
 * down to prevent large and jarring changes in exposure from frame to frame.
 */
static constexpr uint32_t kNumStartupFrames = 10;
...
utils::Duration AgcMeanLuminance::filterExposure(utils::Duration exposureValue)
{
	double speed = 0.2;

	/* Adapt instantly if we are in startup phase. */
	if (frameCount_ < kNumStartupFrames)
		speed = 1.0;

	/*
	 * If we are close to the desired result, go faster to avoid making
	 * multiple micro-adjustments.
	 * \todo Make this customisable?
	 */
	if (filteredExposure_ < 1.2 * exposureValue &&
	    filteredExposure_ > 0.8 * exposureValue)
		speed = sqrt(speed);

	filteredExposure_ = speed * exposureValue +
			    filteredExposure_ * (1.0 - speed);

	return filteredExposure_;
}
```

---

## 附：本次抓取的成功/失败一览

成功（HTTP 200，可引用）：
- raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CaptureRequest.java
- raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/camera2/CameraMetadata.java
- raw.githubusercontent.com/aosp-mirror/platform_hardware_libhardware/master/include_all/hardware/camera3.h
- raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/dcp.cc
- raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/dcp.h
- raw.githubusercontent.com/aizvorski/dng_sdk/master/source/{dng_render.cpp, dng_hue_sat_map.cpp, dng_hue_sat_map.h}
- raw.githubusercontent.com/raspberrypi/libcamera/main/src/ipa/rpi/controller/rpi/{agc.cpp, agc_channel.cpp}
- raw.githubusercontent.com/raspberrypi/libcamera/main/src/ipa/rpi/vc4/data/imx219.json
- raw.githubusercontent.com/libcamera-org/libcamera/master/src/ipa/rkisp1/algorithms/agc.cpp
- raw.githubusercontent.com/libcamera-org/libcamera/master/src/ipa/libipa/agc_mean_luminance.{h,cpp}
- https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2408-7-2023-PDF-E.pdf （web_fetch 406；curl 完整下载 4,343,821 字节，pypdf 提取）

失败/未找到：
- developer.android.com 全部 5 个锚点 URL（web_fetch fetch failed；curl 超时）
- raw.githubusercontent.com/RawTherapee/RawTherapee/dev/rtengine/dcpcamconst.h → 404
- helpx.adobe.com DNG spec PDF → 403 Access Denied；web_fetch unsupported content type
- https://www.itu.int/rec/R-REC-BT.2408 → 200 但正文为空
- 12% 灰相关 URL → 全部 404/403/不可达（详见第 9 节）
- en.wikipedia.org / web.archive.org → 网络不可达
- web_search → 无 API key，不可用
