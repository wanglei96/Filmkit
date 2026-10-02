/* Generated test code to dump a table of data for external validation
 * of the noise model parameters.
 *
 * VIVO X300 主摄 (Main) 降噪模型 - 实测校准版 v2
 * 传感器: 三星 ISOCELL HPB (蓝图 x HPBlue 定制版)
 * 规格: 1/1.4", 2亿像素 (200MP), f/1.68, OIS
 * 出图: 12.5MP (16合1 quad-bayer binning), 等效像素 2.24um, 10-bit
 *
 * 数据来源 (实测):
 *   - 暗场 50 张: ISO 50/100/200/400/800/1600/3200/6400/12800/19200 x 5
 *   - 亮场 50 张: 同上, 均匀光源, 信号 300-700 DN
 *   - 提取方法: 相邻帧差法 (帧差消除 FPN, 保留纯时域噪声)
 *
 * 拟合结果 (四通道独立拟合):
 *   - Shot noise (S): S = A*sens, 过原点拟合, R² = 1.0000
 *   - Read noise (O): O = C*sens² + D*dg², 模拟增益段 R² = 1.000
 *
 * 关键发现:
 *   传感器模拟增益在 ISO 6400 封顶, ISO 12800/19200 为纯数字增益。
 *   故 digital_gain 参考点 dg_ref = 6400。
 *
 * 噪声模型: noise_std = sqrt(S * signal + O)
 *   S = A * sensitivity + B   (shot noise, 光子散粒噪声)
 *   O = C * sensitivity² + D * digital_gain²  (read noise, 读出噪声)
 *   signal ∈ [0, 1] (归一化到白点)
 *   digital_gain = max(1, sens / 6400)
 *
 * Bayer 平面顺序: R, Gr, Gb, B
 *
 * === 实测噪声特性 (R 通道, 18% 灰) ===
 *   ISO 100:   噪声 0.24%,  SNR 76:1
 *   ISO 400:   噪声 0.47%,  SNR 38:1
 *   ISO 1600:  噪声 0.93%,  SNR 19:1
 *   ISO 6400:  噪声 1.81%,  SNR 10:1
 *   ISO 19200: 噪声 3.27%,  SNR 5.5:1
 *
 * 调优参考:
 *   User Noise Model = ON
 *   Dynamic Noise Factor = 1.00
 *   若偏糊 -> 0.90; 若偏噪 -> 1.10
 */
#include <stdio.h>
#include <assert.h>
double compute_noise_model_entry_S(int plane, int sens);
double compute_noise_model_entry_O(int plane, int sens);
int main(void) {
    for (int plane = 0; plane < 4; plane++) {
        for (int sens = 50; sens <= 19200; sens += 100) {
            double o = compute_noise_model_entry_O(plane, sens);
            double s = compute_noise_model_entry_S(plane, sens);
            printf("%d,%d,%lf,%lf\n", plane, sens, o, s);
        }
    }
    return 0;
}

/* Generated functions to map a given sensitivity to the O and S noise
 * model parameters in the DNG noise model. The planes are in
 * R, Gr, Gb, B order.
 */
double compute_noise_model_entry_S(int plane, int sens) {
    static double noise_model_A[] = { 2.722096757e-07, 2.716955350e-07, 2.842075944e-07, 2.734728074e-07 };
    static double noise_model_B[] = { 0.0, 0.0, 0.0, 0.0 };
    double A = noise_model_A[plane];
    double B = noise_model_B[plane];
    double s = A * sens + B;
    return s < 0.0 ? 0.0 : s;
}

double compute_noise_model_entry_O(int plane, int sens) {
    static double noise_model_C[] = { 3.319163139e-13, 3.326880237e-13, 3.576199356e-13, 3.315749289e-13 };
    static double noise_model_D[] = { 6.879113026e-07, 6.818144293e-07, 7.273720832e-07, 6.826691841e-07 };
    double digital_gain = (sens / 6400.0) < 1.0 ? 1.0 : (sens / 6400.0);
    double C = noise_model_C[plane];
    double D = noise_model_D[plane];
    double o = C * sens * sens + D * digital_gain * digital_gain;
    return o < 0.0 ? 0.0 : o;
}