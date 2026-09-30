---
summary: A专项DFT合并与Mali写入丢失攻坚
created: 2026-09-30 12:30
updated: 2026-09-30 12:30
status: active
---

# A专项DFT合并与Mali写入丢失攻坚

## Summary

本窗口完成降噪专项 A（HDR+ §5 式 6/7 DFT 逐频收缩合并）的完整实现与真机
12 轮黑屏攻坚：kFsMfnrFft compute 内核按 CFA 平面独立合并、半重叠升余弦窗、
spike v2 寄存器 FFT，输出经收口 pass 进 tex_fused_（后段链零改动）。连续
"成像全黑"经诊断链 + spike 对照实验定位为**规律：init 创建的 compute program
在该 Mali 上写入全丢、现场创建的一直正常**，0.10.49 改为拍照时现场创建
（spike 模式），待真机验证。回退机制（store_lost → mismatch）已实锤保证
成片不黑。

## Done

- **内核实现**（0.10.37–0.10.49，14 提交全推 main）：按 CFA 平面独立合并
  （HDR+ 原文语义，16×16 平面 tile = mosaic 32×32、半重叠步长 8、修改升余弦
  窗、tile 网格含 -1 行/列使窗和恒 1）；位移场 tile 中心采样 round 到最近
  偶数（像素级对齐 = HDR+ 原版，免相位校正）；spike v2 寄存器 FFT + twiddle
  查表；内核内归约参考帧 tile RMS（未加窗）代入 σ²=A·x+B（c=256）；输出 =
  4 张 RGBA16F image2D writeonly（相位组静态分支）→ kFsMfnrFftSum 收口渲染
  进 tex_fused_；prefs mfnr_dft + 设置页开关 + JNI 接线。
- **验收工具**：validate-shaders.sh 支持 compute（.comp,430 core 转换校验）；
  tools/fft-merge-mirror.py 离线镜像（窗和=1 精确、抑制比 0.581 ≈ spike
  0.578、DC 错位帧被逐频拒绝、neff 静态 ~3.56）。
- **诊断与回退**：store_check（sum/micro/microProg/spikeOk/spikeGpu）+
  store_lost 自检回退 + dftProbe；0.10.44 起成片机制上不黑（store_lost →
  mismatch 回退，avgLuma 105–115 正常，实测 4 轮）。
- **对照实验收敛**（0.10.47/48）：spike 完整路径搬进拍照上下文 **spikeOk:true
  两次**（readback 数值精确）→ 上下文/GL 状态无罪；输入 usampler2DArray →
  4 张 usampler2D 仍失败 → 锁定 **program 创建时机**（init 创建的两个 compute
  program 一致丢失，现场创建的 spike 一致成功，跨 0.10.38–48 十一轮稳定）。
- **0.10.49**：kFsMfnrFft 改为拍照时现场编译/链接/dispatch/删除（spike 模式，
  编译为 CPU 侧几十 ms 可接受）；微测试同样现场创建。
- **销项**：高光线复验通过（0.10.31，用户肉眼验收，不打 tag）；CI 删 APK
  artifact 上传（500MB 存储配额根治）；根目录旧 APK 清理（重申单文件覆盖制）。

## Decisions

- [2026-09-30] **A 落地形态**：输出 = 4 张 RGBA16F image2D writeonly（相位组
  静态分支，spike 验证过的格式）；neff 恒 4（HDR+ 原版"完美平均"σ²/N，NS §3.3
  按实际合并留 SSBO 后续）；不叠 mismatch/conf 权重（逐频收缩自身鲁棒）。
- [2026-09-30] **compute program 现场创建、用完删除**（spike 模式）——init
  创建的 compute program 写入丢失为该 Mali 驱动行为；机理疑似预览链大量
  program 创建损坏早期 compute pipeline。
- [2026-09-30] **实验路径严禁进 init 阻塞检查**：shader 编译/链接失败仅日志
  + 运行时回退 mismatch（0.10.37 首包黑屏教训）。
- [2026-09-30] **CI 不上传 APK artifact**（私有仓库 Actions 存储 500MB 配额，
  每 push 29MB 很快占满且清理只解一次）；出包 = 本地单文件覆盖制。

## Pitfalls

- **GLSL ES 3.2 的 r16f/rgba16f image 禁止 load+store 混合**（readonly/
  writeonly only）；image2DArray 的 imageStore 在该 Mali 上"计算照跑、写入
  全丢"（dftGpu 477–798ms vs 输出恒 0）。
- **glslang ES profile 的 image 格式只放行 r32f**（rgba16f/r16f 误拒）——
  compute 校验改用 430 core 转换；**盲区：GL 4.x 允许 int→uint 隐式而 ESSL
  不允许**（0.10.45 微测试 shader 编译失败漏检），转换校验不能替代真机。
- **Mali 拒绝混链 GLSL 版本**（vs 300 es + fs 310 es → "Shader languages do
  not match"→ init 失败黑屏）——新增 shader 必须与 program 内其它 shader
  同版本。
- **批量脚本编辑中途 assert 失败 → 部分修改未写盘 → 0.10.41 坏提交**
  （shader 与 host 状态不一致，多轮调试被其污染）——多点修改必须逐项落地
  + 端到端状态核对（shader 源码 grep + glslang + host grep）后才出包。
- **dftProbe 的读回组合（R16F fbo + GL_RGBA + GL_FLOAT）是 v0.6.48 已知的
  静默回零组合**，probe 数据不可信；成片黑的硬证据 = avgLuma（JPEG 统计）
  与 rms（采样链）。
- **"计算照跑、写入全丢"的二分**：把真机验证过的完整参考路径（spike）原样
  搬进现场跑一次，spikeOk 一次定案上下文无罪——比逐个猜格式/绑定快得多。
- glCopyImageSubData（R16UI 2D→2D_ARRAY）与 glClearTexImage 前者疑似静默
  不拷贝、后者根本不是 ES 命令（NDK 头无声明）——均已绕开。

## Next

1. **0.10.49 真机验证**（装 filmkit-0.10.49-m4.apk，开「频域合并」拍一组）：
   store_check `sum>0` → DFT 首次工作，进验收（σ/edge 同刻 A/B + 100% 裁剪
   + 无鬼影/环状伪影；dftGpu 对照 250ms 门槛，超了动 8×8 tile 储备）；
   `micro:true + sum=0` → 与 spike 逐行 diff（差异空间已极小）。
2. 验收通过打 tag 收线；随后 **D 专项**（补偿性空间降噪）或 MFNR 阶段二
   收尾（参考帧回最锐帧 + 8 帧档位）。
3. NS §3.3 per-tile neff（SSBO 方案）在输出链跑通后做。

## Refs

- 提交链（全推 main）：8af8028（A 实现）→ 18b353d（300es 混链修复）→
  518cb6c（uAccum）→ 4bf91ca（barrier）→ f8b3da3（直传）→ 885055c（分层
  输出，**坏提交**）→ edf0fc3/3a039a0（真修正）→ b647d84（4×r32f）→
  2a24ab3（rgba16f+自检回退）→ 98e1a17（uOut0..3 unit）→ 55f3c1e（微测试
  uint）→ 8d79bf4（spike 对照）→ 5bf6ea7（usampler2D）→ c1368cc（现场创建）
- 版本：0.10.37–0.10.49（versionCode 189–201）；当前包
  filmkit-0.10.49-m4.apk（201）；tag 未打
- 日志：`~/Downloads/vivo办公套件/filmkit-logs-20260930-{093830..120900}`
  共 8 轮真机
- 工具：`tools/fft-merge-mirror.py`、`tools/validate-shaders.sh`（comp 支持）
- 引擎：`core/engine/src/render/FrameRenderer.cpp`（kFsMfnrFft/kFsMfnrFftSum/
  kFsMicroStore、passMfnrFft/ensureMfnrFftTargets）；Kotlin：EngineBridge/
  RenderClient/CameraController/SettingsScreen（prefs `mfnr_dft`）
