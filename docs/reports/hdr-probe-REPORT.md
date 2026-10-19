# HDR 能力探针报告

## 设备

```
List of devices attached
```

**没有已连接设备** ⇒ 只能给出待办清单，不能给结论。

1. **系统相册**：把一张 HDR HEIC（或 HDR JPEG gainmap）拷进相册，看是否正常显示、是否亮部更亮、有无发灰/过曝。
2. **微信**：同图发送到文件传输助手/朋友圈，看是否被转成 SDR、有无偏色。
3. **Windows / macOS**：同图在桌面端打开，确认色彩/亮度。
4. **App 内回看**：拍一张 HEIC 后在 App 编辑器里打开（期 A 已知缺口：`ui/ImageDecode.kt` 对 HEIC 无特判）。
5. **gainmap**：确认系统/相册是否支持 Android Ultra HDR / ISO gain map；若不支持，HDR 交付只能走 PQ/HLG 单层。
6. **内容端**：拍一张窗/灯的高动态范围 RAW DNG（固定机位），供 HDR 内容链评估。
