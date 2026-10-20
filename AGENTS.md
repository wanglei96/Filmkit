# 项目大前提（用户 2026-09-28 拍板）

## 一切以质量为先！严禁简化关键质量管线！

- **质量 > 成本/工期**：成片质量（噪声、细节、色准、影调）是本项目唯一优先级；性能与工期让路。
- **严禁简化关键质量管线**：降噪/MFNR 融合、去马赛克、色彩科学（CCM/影调曲线/gamut）、锐化等成像链路，一律按**正确的标准做法**（论文一手算法 + 成熟开源实现逐行对照）实现，不做"先简化后补"的折衷。
- **实现顺序铁律（2026-09-28 用户批评后固化）：先 100% 按原版完整实现并跑通 → 真机实测 → 用实测数据评估是否偏离**。严禁基于自研推演"预删/预改"原版功能后再验证——每次都先做全量，删不删让数据说话。
- **性能取舍必须实测，不许拍脑袋替用户做决定**：任何"GPU 成本不现实/先不做"的判断必须给出实测数据与选项，由用户拍板（2026-09-28 用户严厉指正过的同型错误不得再犯）。
- **对标口径**：PhotonCamera（PC）样片是"当前质量不行"的证据，**不是目标**——本项目目标是全面超越 PC。实现的对齐对象是标准做法（HDR+/Night Sight 论文、darktable 源码、GCam 等）。
- 项目状态与历史见 `.zcode/memory/`（INDEX.md 入口）。


## 出包纪律（2026-10-19 用户多次提醒后固化，不得再犯）

- **每个交付包必须升版本号**：`versionCode` +1、`versionName` +0.0.1。哪怕是"只改一行的修复包"也升 —— 用户靠版本号区分"这是第几个包、哪个修了什么"，版本号不动等于让用户自己猜。
- **出包后立刻清理过期包**：项目根目录只保留 **最新一版 + 上一个稳定版**（回退用）。中间迭代包（尤其带已知 bug 的）一律删除。
- **不要用 `-fix1/-fix2/-fix3` 这类后缀代替版本号**：修复包就是新版本（0.11.51、0.11.52…），不是同一版本的补丁。
- 打包命令模板（release + debug 签名）：
  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
    ./gradlew :app:assembleRelease -PfilmkitHdr=true -q
  apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out filmkit-<version>-<TAG>.apk app/build/outputs/apk/release/app-release-unsigned.apk
  ```
- **打包后必须验证三件事**：签名（`apksigner verify`）、版本号（dex 里 `strings` 能查到）、关键 JNI 符号（`nm -D`）—— 尤其是带编译开关的功能（如 `FILMKIT_HDR`），缓存命中会导致符号静默缺失。
