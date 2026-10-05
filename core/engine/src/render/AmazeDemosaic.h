// AMaZE 去马赛克（Aliasing Minimization and Zipper Elimination）。
//
// 算法出处：Emil Martinec, "AMaZE" (2008-2010)；Ingo Weyrich 的优化版；
// 参考实现 RawTherapee `rtengine/amaze_demosaic_RT.cc`（GPL-3.0，本文件为
// 独立重写，只采用其公开数学步骤与常量参数，不复制其代码/结构表达 —— 与
// 本仓 RCD（kFsRcdDir/G/Chroma）的 GPL 隔离做法一致）。
//
// 本实现的分工：**CPU 侧**（本文件）做完整的 AMaZE；GPU 侧只接"Amaze 输出
// → 旋转到成片几何 → CCM"（kFsAmazeUp / passCcm）。选择 CPU 而非 GLSL 的
// 依据见 /tmp/amaze/REPORT.md「移植策略」：AMaZE 在 tile 内需要 14 张
// ts×ts 中间场 + 12 张半网格场，GLES 3.0 fragment 版相当于 15+ 个 pass、
// 全图级中间纹理显存峰值 ~1.7GB(12MP)，而 CPU 版与参考实现逐段同构、无
// 纹理格式/精度折损。代价是耗时（真机实测见报告），按项目铁律由用户拍板。
//
// 结构（与参考实现逐段对应，标量分支）：
//   tile 化（ts=160，步进 ts-32，重叠 32px）+ 镜像边界 → 整图等价
//   ① H/V 梯度 dirwts0/dirwts1/delhvsqsum
//   ② 方向色差 vcd/hcd/vcdalt/hcdalt（HA vs 自适应比值 arthresh=0.75）
//   ③ 方差择优 + 高饱和 median 限幅 + cddiffsq
//   ④ hvwt（方向判别权重）+ dgintv/dginth
//   ⑤ nyqutest（gaussodd 卷积 cddiffsq − gaussgrad 卷积 delhvsqsum）
//   ⑥ Nyquist 标志 + nyquist2（邻域投票）
//   ⑦ Nyquist 区 area 插值（13×13 同相位块）与 G 曲率精修
//   ⑧ 初始 G @ R/B 位（Dgrb + rgbgreen）
//   ⑨ 对角 delp/delm → pmwt → rbm/rbp → rbint
//   ⑩ 用 R+B 重新插值 G（对角色差信息）
//   ⑪ Dgrb 拆分 G-R / G-B + chrominance 插值（fancier 权重 1.325/-0.175/-0.075）
//   ⑫ 输出 RGB：G 位用 hvwt 加权邻域色差，R/B 位直接取色差
//
// ⚠ 未完成项（如实标注，不静默简化）：
//   - 未接 P0 RAW 域 bilateral 降噪（GPU pass 的 CPU 版没有实现）⇒ Amaze 档
//     输入是**未降噪**的线性化 CFA，高 ISO 下比 RCD 档噪（Amaze 本身也不是
//     降噪算法）。见 REPORT.md「未完成」。
#pragma once

#include <cstdint>

namespace filmkit {

// AMaZE 去马赛克（CPU，多线程按 tile 并行）。
//
// cfa     : 输入 CFA，w*h 个 float，行主序。值 = (raw − 黑阶)/白阶 × WB 增益
//           （即"线性化 + 白平衡后"，与 kFsRaw/kFsRcd 的 s() 同域）。
// w, h    : 尺寸（≥ 32×32；内部按 16px 镜像边界处理）。
// pattern : 4 元 CFA 相位→通道表，索引 k = (y&1)*2 + (x&1)，值 0=R 1=G 2=B。
//           例：RGGB → {0,1,1,2}；GRBG → {1,0,2,1}；GBRG → {1,2,0,1}；BGGR → {2,1,1,0}。
// clip_pt : 过曝阈值（WB 域裁剪点，参考实现的 clip_pt = 1/initialGain）。
// out_rgb : 输出 w*h*3 个 float（RGB 交错），0..1+ 线性域（**不**做 clamp，
//           下游 CCM/gamut 收口）。
// threads : 工作线程数；≤0 = hardware_concurrency（上限 8）。
void AmazeDemosaic(const float* cfa, int w, int h, const int pattern[4],
                   float clip_pt, float* out_rgb, int threads = 0);

}  // namespace filmkit
