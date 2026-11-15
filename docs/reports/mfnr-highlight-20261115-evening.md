# 极高光品红：11 月 15 日晚的证据与未完成项

## 状态

- 设备安装版本：0.14.4 (801)，诊断包，非高光修复包。
- 用户反馈：白天已拍样片；19:53 表示夜晚无法继续验证，下一次日光实测待明天。
- 未完成：完整 clipping-aware 高光恢复、配准可信度、无观测回退及真机验收。
- 不应将本轮标为「品红已修复」，不应通过重新启用 defringe 把问题去色成灰。
- RAW HL 关闭后云朵灰斑消失是同场景 A/B 事实；「一定是 opposed 台阶被 P0 扩散」仍是细分机制假设，不是阶段证据定论。

## 数据保全

目录：`test_temp/magenta-0144-20261115/`。

1. 三张 JPEG：17:01:07、17:01:19、17:07:43；对应 DNG：17:01:05、17:01:17、17:07:40。
2. 四帧原始 RAW 仅保留最后一次 17:07:40，不能与前两张 DNG 或 16:39 的 stage 混用。
3. 17:07:40 的 `burst/f0.raw` 与 `FILMKIT_20261115-170740_f0.dng` 的 RAW 数组完全相等。
4. `burst/*.meta` 尺寸 4080×3072、stridePx=4080；镜头 id=2。前两张镜头 id=3、尺寸 4096×3072。
5. 备份清单含每文件大小与 SHA256：`manifest.json`；日志已一起复制。
6. 设备 0.14.4 的三次拍摄没有新 `edge_dump.stage` 事件，也没有 MFNR 权重/位移文件。最后一个 stage token 仍是 16:39 的 `337127732395`。

**诊断安排遗漏**：edge_dump.enable 是一次性哨兵，0.14.4 新增 dump 不等于自动启用。发包后未重新启用，用户已天黑无法补拍，不得要求夜间照片替代日光验收。后续必须先核对启用，拍后核 token/时间/帧序身份；长期方案要避免无 token 的 burst 被连续快门覆盖。

## 已确定的缺陷 A：剪裁差异被当作运动

`tools/check-mfnr-clipping.py` 直接执行生产 `kFsMfnrMismatch`，构造静态 GRBG 中性场景，WB=(2,1,1.5)，零位移：

- 同曝光 control：m=0。
- reference G RAW=1023（剪裁），short G RAW=352，scale=4 后真实值 1.20125：m=0.97119140625。
- 生产硬拒绝阈值为 m>=0.35。静止 HDR 观测可以仅因参考帧剪裁而丢掉有用短帧。
- 这项重放在本轮位移网格修正后依然为 0.971，证明坐标修正不是完整高光修复。

证据：`clipping-reproduction-after-grid-fix.json`。

此前同次 16:39:21 RAW + token `337127732395` stage 已证实：品红在 fused 中就有 G=1 平台；品红内核约 62% 的位置，短帧同相位 ±8 mosaic px 窗口内的所有 native tap 都未饱和，而 fused G 仍精确等于参考值。不是所有品红区都属于短帧也烧白。

本轮最后一次 RAW 的保守统计（原坐标 ±8px 窗口，**不是**设备位移场的逐像素重放）：

| 相位 | 参考剪裁内核 photosite 数 | 短帧窗口全部低于 satW 全权区的占比 | 短帧窗口全部剪裁的占比 |
|---|---:|---:|---:|
| G0 | 223280 | 44.09% | 35.21% |
| R | 143331 | 49.61% | 27.16% |
| B | 178726 | 55.02% | 23.47% |
| G1 | 223936 | 44.16% | 35.12% |

因此最后一次也同时包含「可恢复」和「短帧无观测」两类高光，不能用一条强制短帧权重修全部。

统计结果：`native-clipping-analysis.json`。判据按现生产 satW 语义：sensor-normalized 0.98 开始全拒，0.98×0.92 以下全权；black=64、white=1023。此统计不证明最新三张的具体品红 ROI，也不代表完整真机融合重放。

## 已确定的缺陷 B：mismatch 位移纹理网格用错

CPU 向 mismatch 下发：

- `uTiles = 4 × (tiles_w, tiles_h)`：32px 残差 cell 网格。
- `uOffTiles = (tiles_w, tiles_h)`：128px 实际位移纹理尺寸。

原生产 shader：

- `off1At` 坐标乘正确 `uOffTiles`，但 clamp 用错误 `uTiles`。
- `off2At`/`off3At` 坐标和 clamp 均使用错误 `uTiles`。
- texelFetch 不服从纹理 wrap 的 CLAMP_TO_EDGE，越界读取没有可依赖的值；不能将某 GPU 返回零当正确位移。
- 融合 shader 使用 tile 网格正确，因此 mismatch 与 fusion 采样位置失配。

本轮只将 mismatch 三个函数内的 8 处尺寸引用改为真实 `uOffTiles`，不改变 motion/clip 权重算法，不重新启用旧 HL 或 defringe。

离线 GPU 与 CPU 双线性对照：实际 offset 8×6，mismatch 32×24，768 cells，空间变化非零位移场。

- 旧 off1：108/768 cells 不同，最大误差 4.1133px。
- 旧 off2、off3：各 767/768 cells 不同，最大误差 6.75px。
- 修正后 offset 对照误差为 0（fixture 数值在 half 精度可精确表示）。
- 完整 mismatch 原版与 CPU 最大差约 0.154；修正后约 0.000122（half 输出量化范围）。
- 这是 Apple M1 离线 GL 结果，不是手机 Mali 真机结果，不足以承诺品红消失。

持久化检查工具：`tools/check-mfnr-offset-grid.py`；运行结果另附 `offset-grid-check.json`。

## 本轮验证

- `git diff --check` 通过。
- 全部生产 shader 静态校验通过。
- arm64-v8a 与 x86_64 Release 构建通过；仍有已存在 `cw` 未使用警告。
- 没有签发新 APK，没有覆盖用户手机；手机仍是 0.14.4。
- 位移网格修复尚未真机验证；剪裁感知完整融合尚未实现。

## 后续实现边界

参考读取对象：PC/Bento 的 native CFA clipping、flow forward/backward support、8 连通高光区 collar admission、adjust/inpainting mask、phase-preserving MergeBayer。

必须区分：

1. 双方未剪裁，可比较残差。
2. reference clip、short valid：可恢复高光候选，不用曝光剪裁差当运动。
3. 双方 clip：没有真实观测，不能伪造高光亮度或跨 CFA 平均回填。
4. 几何未知：需有效 collar/FB 支持，不能把 UNKNOWN=m0 后强行融短帧。

饱和本身不证明静止；clip mask、motion 支持、曝光权重不能塞进一个 scalar。不能只跳过 clip residual 就当完整修复，也不能仅调 defringe 或恢复旧跨 CFA scalar inpaint。

## 明日验收

只在离线完整修复后出升版包，并先核实诊断采集已启用且 RAW/stage/权重/位移严格同次。真机验证：极高光品红、云朵灰斑、粉色花瓣去色/绿边、栏杆紫边、灯边灰带，必要补移动主体重影检查。
