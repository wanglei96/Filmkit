/* Generated test code to dump a table of data for external validation
 * of the noise model parameters.
 *
 * VIVO X300 超广角 (Ultra-Wide) 降噪模型 - 初版 v1
 * 传感器: 三星 ISOCELL JN1
 * 规格: 1/2.76", 5000万像素 (50MP), f/2.0, AF
 * 像素: ~0.64um
 *
 * 参数来源: NubiaZ50Ultra_JN1_2_new (三星 JN1, 1/2.76", 0.64um)
 *   - 同为 JN1 传感器, 硬件规格完全一致, 噪声特性直接匹配
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
    static double noise_model_A[] = { 3.0713472052246765e-07, 3.222510945094307e-07, 3.223227961544274e-07, 3.012216810953878e-07 };
    static double noise_model_B[] = { 1.8906526875045943e-05, 1.8055743674984094e-05, 1.7891396193557188e-05, 2.5690638430403927e-05 };
    double A = noise_model_A[plane];
    double B = noise_model_B[plane];
    double s = A * sens + B;
    return s < 0.0 ? 0.0 : s;
}

double compute_noise_model_entry_O(int plane, int sens) {
    static double noise_model_C[] = { 2.879785304535712e-13, 2.69641561411859e-13, 2.641313545919538e-13, 3.0998058395299843e-13 };
    static double noise_model_D[] = { 1.9206132455957786e-07, 1.3059985243909104e-07, 1.3975736866687755e-07, 8.52423482814126e-08 };
    double digital_gain = (sens / 1600.0) < 1.0 ? 1.0 : (sens / 1600.0);
    double C = noise_model_C[plane];
    double D = noise_model_D[plane];
    double o = C * sens * sens + D * digital_gain * digital_gain;
    return o < 0.0 ? 0.0 : o;
}