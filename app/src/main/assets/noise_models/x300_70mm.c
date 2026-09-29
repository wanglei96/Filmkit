/* X300 70MM（cameraId 3，4096x3072）实测噪声标定 —— 2026-09-29
 *
 * 来源：真机 DNG 平坦块水平差分拟合（FILMKIT_20260929-205727.dng @ISO1605），
 *       按"实测比值 x HAL 主摄模型"合成（差分法的绝对值含 PSF 相关偏差，
 *       比值法可抵消；O 沿用 HAL 读出噪声——拟合 O 含场景污染不可信）。
 *
 * 为什么需要：X300 的 HAL 对隐藏物理镜头（id 3/4）返回**主摄的噪声模型**
 * （三摄 pairs 完全相同、O 逐通道精确一致），而长焦的真实 shot 噪声是主摄的
 * 3.6x（成片实测 4.4x，含 MFNR neff 差异）——模型低估使 P0/P4 降噪严重不足。
 *
 * 模型：S = A*iso + B（shot），O = C*iso^2 + D（read）；通道序 R, Gr, B, Gb。
 * 无 digital-gain divisor（iso 未超模拟增益上限，dg=1）。
 */
double compute_noise_model_entry_S(int plane, int sens) {
    static double noise_model_A[] = { 1.4134e-06, 1.1848e-06, 1.4107e-06, 1.1848e-06 };
    static double noise_model_B[] = { 0.0, 0.0, 0.0, 0.0 };
    double A = noise_model_A[plane];
    double B = noise_model_B[plane];
    double s = A * sens + B;
    return s < 0.0 ? 0.0 : s;
}
double compute_noise_model_entry_O(int plane, int sens) {
    static double noise_model_C[] = { 0.0, 0.0, 0.0, 0.0 };
    static double noise_model_D[] = { 3.789e-07, 3.906e-07, 3.812e-07, 3.906e-07 };
    double C = noise_model_C[plane];
    double D = noise_model_D[plane];
    double o = C * sens * sens + D;
    return o < 0.0 ? 0.0 : o;
}
