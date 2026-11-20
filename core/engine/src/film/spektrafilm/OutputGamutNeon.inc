// Two independent CAM16-UCS samples; all lanes retain the scalar FP64 formula.
using Pair = float64x2_t;
using PairRgb = std::array<Pair, 3>;

Pair pairPower(Pair value, double exponent) {
    return _ZGVnN2vv_pow(value, vdupq_n_f64(exponent));
}

Pair pairSign(Pair magnitude, Pair sign) {
    const auto mask = vdupq_n_u64(std::uint64_t{1} << 63);
    return vreinterpretq_f64_u64(vorrq_u64(vbicq_u64(vreinterpretq_u64_f64(magnitude), mask),
                                          vandq_u64(vreinterpretq_u64_f64(sign), mask)));
}

Pair pairSignedPower(Pair value, double exponent) {
    return pairSign(pairPower(vabsq_f64(value), exponent), value);
}

PairRgb pairMultiply(const double matrix[3][3], const PairRgb& value) {
    PairRgb result{};
    for (std::size_t row = 0; row < 3; ++row) {
        for (std::size_t col = 0; col < 3; ++col) result[row] += matrix[row][col] * value[col];
    }
    return result;
}

Pair pairKnee(Pair distance, double threshold, double power) {
    const double scale = 1.0 - threshold;
    const auto active = vcgtq_f64(distance, vdupq_n_f64(threshold));
    if (threshold > 0.0 && !(active[0] || active[1])) return distance;
    const Pair x = vbslq_f64(active, (distance - threshold) / scale, vdupq_n_f64(0.0));
    const Pair squared = x * x;
    const Pair powered = power == 6.0 ? (squared * squared) * squared : pairPower(x, power);
    const Pair compressed = threshold + scale * x / pairPower(1.0 + powered, 1.0 / power);
    return vbslq_f64(active, compressed, distance);
}

bool compressSrgbCam16UcsPair(const Triplet& first, const Triplet& second,
                             Triplet& firstOut, Triplet& secondOut) {
    const auto fallback = [&] {
        return compressSrgbCam16Ucs(first, firstOut) && compressSrgbCam16Ucs(second, secondOut);
    };
    if (!finite(first) || !finite(second)) return false;
    const auto& state = kViewing;
    PairRgb xyz, adapted;
    for (std::size_t c = 0; c < 3; ++c) xyz[c] = {first[c], second[c]};
    xyz = pairMultiply(kSrgbToXyz, xyz);
    for (auto& channel : xyz) channel *= 100.0;
    const auto cone = pairMultiply(kCat16, xyz);
    for (std::size_t c = 0; c < 3; ++c) {
        const Pair value = state.adaptation[c] * cone[c];
        const Pair x = pairPower(state.fL * vabsq_f64(value) / 100.0, 0.42);
        adapted[c] = 400.0 * pairSign(vdupq_n_f64(1.0), value) * x / (27.13 + x) + 0.1;
    }
    const Pair a = adapted[0] - 12.0 * adapted[1] / 11.0 + adapted[2] / 11.0;
    const Pair b = (adapted[0] + adapted[1] - 2.0 * adapted[2]) / 9.0;
    const Pair radius = _ZGVnN2vv_hypot(a, b);
    if (!(radius[0] > 1e-12 && radius[1] > 1e-12)) return fallback();
    const Pair sinH = b / radius, cosH = a / radius;
    static const double cos2 = std::cos(2.0), sin2 = std::sin(2.0);
    const Pair eccentricity = 0.25 * (cosH * cos2 - sinH * sin2 + 3.8);
    const Pair originalA = (2.0 * adapted[0] + adapted[1] + 0.05 * adapted[2] - 0.305) * state.nbb;
    const Pair originalJ = 100.0 * pairSignedPower(originalA / state.aw, kSurroundC * state.z);
    const Pair denominator = adapted[0] + adapted[1] + 21.0 * adapted[2] / 20.0;
    const Pair t = vbslq_f64(vceqq_f64(denominator, vdupq_n_f64(0.0)), vdupq_n_f64(0.0),
                             (50000.0 / 13.0) * kSurroundNc * state.nbb * eccentricity * radius / denominator);
    const Pair C = pairSignedPower(t, 0.9) * pairSign(vsqrtq_f64(vabsq_f64(originalJ / 100.0)), originalJ) * state.chromaScale;
    const Pair M = C * state.colorfulnessScale;
    const Pair Jp = (1.0 + 100.0 * kUcsC1) * originalJ / (1.0 + kUcsC1 * originalJ);
    const Pair Mp = _ZGVnN2v_log1p(kUcsC2 * M) / kUcsC2;
    if (!(Mp[0] > 0.0 && Mp[1] > 0.0) || !std::isfinite(Mp[0]) ||
        !std::isfinite(Mp[1]) || !std::isfinite(Jp[0]) || !std::isfinite(Jp[1])) return fallback();

    const Pair normalizedLightness = Jp / 100.0;
    const Pair lightness = pairKnee(normalizedLightness, 0.7, 2.2) * 100.0;
    Pair hue = _ZGVnN2vv_atan2(b, a);
    for (std::size_t lane = 0; lane < 2; ++lane) {
        if (hue[lane] < 0.0) hue[lane] += 2.0 * std::numbers::pi;
        if (hue[lane] > std::numbers::pi) hue[lane] -= 2.0 * std::numbers::pi;
    }
    Pair maximum{lookupPreparedGamut(kSrgbCam16Gamut, lightness[0], hue[0]),
                 lookupPreparedGamut(kSrgbCam16Gamut, lightness[1], hue[1])};
    maximum = vmaxq_f64(maximum, vdupq_n_f64(1e-9));
    const Pair chroma = pairKnee(Mp / maximum, 0.0, 6.0) * maximum;

    const Pair J = -lightness / (kUcsC1 * lightness - 1.0 - 100.0 * kUcsC1);
    const Pair inverseM = _ZGVnN2v_expm1(kUcsC2 * chroma) / kUcsC2;
    const Pair inverseC = inverseM / state.colorfulnessScale;
    const Pair safeJ = vmaxq_f64(J, vdupq_n_f64(std::numeric_limits<double>::epsilon()));
    const Pair inverseT = pairPower(inverseC / (vsqrtq_f64(safeJ / 100.0) * state.chromaScale), 1.0 / 0.9);
    const auto preserveA = vandq_u64(vcgeq_f64(normalizedLightness, vdupq_n_f64(0.0)),
                                    vcleq_f64(normalizedLightness, vdupq_n_f64(0.7)));
    Pair A = originalA;
    if (!(preserveA[0] && preserveA[1])) {
        A = vbslq_f64(preserveA, originalA,
            state.aw * pairSignedPower(J / 100.0, 1.0 / (kSurroundC * state.z)));
    }
    const Pair p2 = A / state.nbb + 0.305;
    constexpr double p3 = 21.0 / 20.0;
    const Pair n = p2 * (2.0 + p3) * (460.0 / 1403.0);
    const auto nonzero = vmvnq_u32(vreinterpretq_u32_u64(vceqq_f64(inverseT, vdupq_n_f64(0.0))));
    const Pair p1 = vbslq_f64(vreinterpretq_u64_u32(nonzero),
        (50000.0 / 13.0) * kSurroundNc * state.nbb * eccentricity / inverseT, vdupq_n_f64(0.0));
    const auto vertical = vcgeq_f64(vabsq_f64(sinH), vabsq_f64(cosH));
    const Pair sinDen = vbslq_f64(vertical, sinH, vdupq_n_f64(1.0));
    const Pair cosDen = vbslq_f64(vertical, vdupq_n_f64(1.0), cosH);
    const Pair bv = n / (p1 / sinDen + (2.0 + p3) * (220.0 / 1403.0) * cosH / sinDen -
                         27.0 / 1403.0 + p3 * (6300.0 / 1403.0));
    const Pair ah = n / (p1 / cosDen + (2.0 + p3) * (220.0 / 1403.0) -
                         (27.0 / 1403.0 - p3 * (6300.0 / 1403.0)) * sinH / cosDen);
    Pair inverseA = vbslq_f64(vertical, bv * cosH / sinDen, ah);
    Pair inverseB = vbslq_f64(vertical, bv, ah * sinH / cosDen);
    inverseA = vbslq_f64(vreinterpretq_u64_u32(nonzero), inverseA, vdupq_n_f64(0.0));
    inverseB = vbslq_f64(vreinterpretq_u64_u32(nonzero), inverseB, vdupq_n_f64(0.0));
    const PairRgb responses{
        (460.0 * p2 + 451.0 * inverseA + 288.0 * inverseB) / 1403.0,
        (460.0 * p2 - 891.0 * inverseA - 261.0 * inverseB) / 1403.0,
        (460.0 * p2 - 220.0 * inverseA - 6300.0 * inverseB) / 1403.0,
    };
    PairRgb inverseCone;
    for (std::size_t c = 0; c < 3; ++c) {
        const Pair delta = responses[c] - 0.1;
        const Pair x = vabsq_f64(delta);
        inverseCone[c] = pairSign(vdupq_n_f64(100.0 / state.fL), delta) *
            pairSignedPower(27.13 * x / (400.0 - x), 1.0 / 0.42) / state.adaptation[c];
    }
    xyz = pairMultiply(kCat16Inverse, inverseCone);
    for (auto& channel : xyz) channel /= 100.0;
    const auto rgb = pairMultiply(kXyzToSrgb, xyz);
    for (std::size_t c = 0; c < 3; ++c) {
        firstOut[c] = rgb[c][0];
        secondOut[c] = rgb[c][1];
    }
    return finite(firstOut) && finite(secondOut);
}
