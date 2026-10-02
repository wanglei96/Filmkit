/* Generated test code to dump a table of data for external validation
 * of the noise model parameters.
 *
 * VIVO X300 潜望长焦 (Periscope Telephoto) 降噪模型 - 初版 v1
 * 传感器: 索尼 LYT-602 (蔡司 APO 认证)
 * 规格: 1/1.95", 5000万像素 (50MP), f/2.57, OIS, 70mm (3x)
 * 像素: ~0.8um
 *
 * 参数来源: Xiaomi12SUltra_IMX586_3 (索尼 IMX586, 0.8um, 1/2.0", 潜望式)
 *   - LYT-602 与 IMX586/IMX882 同属 0.8um 像素 1/2.0"-1/1.95" 规格
 *   - 来源为潜望长焦用途, 与 X300 的 3x 潜望长焦定位一致
 *
 * 噪声模型: noise_std = sqrt(S * signal + O)
 *   S = A * sensitivity + B   (shot noise, 光子散粒噪声)
 *   O = C * sensitivity^2 + D * digital_gain^2  (read noise, 读出噪声)
 *
 * Bayer 平面顺序: R, Gr, Gb, B
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
        for (int sens = 100; sens <= 6400; sens += 100) {
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
    static double noise_model_A[] = { 1.0487481803370184e-06, 1.199221284977127e-06, 6.151257450907479e-07, 6.796902182557934e-07 };
    static double noise_model_B[] = { -7.990003038173501e-06, 5.203134638183505e-06, 5.1638717312693055e-06, -8.482227135801929e-07 };
    double A = noise_model_A[plane];
    double B = noise_model_B[plane];
    double s = A * sens + B;
    return s < 0.0 ? 0.0 : s;
}

double compute_noise_model_entry_O(int plane, int sens) {
    static double noise_model_C[] = { 6.78172923600348e-12, 2.4895575205031214e-12, 1.2080214453270063e-12, 1.6084871967378975e-12 };
    static double noise_model_D[] = { 4.532680928312652e-07, 1.2880237028628905e-07, 2.873698694748262e-07, 4.491787775315671e-07 };
    double digital_gain = (sens / 3200.0) < 1.0 ? 1.0 : (sens / 3200.0);
    double C = noise_model_C[plane];
    double D = noise_model_D[plane];
    double o = C * sens * sens + D * digital_gain * digital_gain;
    return o < 0.0 ? 0.0 : o;
}