/* X300 15MM（cameraId 4，4080x3060）实测噪声标定 —— 2026-09-29
 *
 * 来源：真机 DNG 平坦块水平差分拟合（FILMKIT_20260929-205733.dng @ISO1410），
 *       按"实测比值 x HAL 主摄模型"合成（方法同 x300_70mm.c 的说明）。
 *
 * 为什么需要：同 x300_70mm.c —— HAL 对隐藏物理镜头返回主摄模型，而超广的
 * 真实 shot 噪声是主摄的 2.2x（成片实测 2.2x，含 MFNR 差异）。
 *
 * 模型：S = A*iso + B（shot），O = C*iso^2 + D（read）；通道序 R, Gr, B, Gb。
 */
double compute_noise_model_entry_S(int plane, int sens) {
    static double noise_model_A[] = { 9.1819e-07, 7.4795e-07, 9.1651e-07, 7.4795e-07 };
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
