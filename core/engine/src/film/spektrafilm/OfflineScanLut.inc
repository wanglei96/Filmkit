struct ScanCamLut {
    static constexpr int intervals = 64;
    static constexpr int side = intervals + 1;
    static constexpr double rootMax = 1.25;
    static constexpr double scale = intervals / rootMax;
    static constexpr int stride[3]{side * side, side, 1};
    static constexpr std::size_t nodeCount = side * side * side;
    static constexpr std::size_t cellCount = intervals * intervals * intervals;
    static constexpr std::size_t byteCount = nodeCount * sizeof(Triplet) + cellCount;
    const std::byte* values;
    const uint8_t* good;

    static int offset(int r, int g, int b) { return (r * side + g) * side + b; }
    static int cell(int r, int g, int b) { return (r * intervals + g) * intervals + b; }
    static double linear(double index) { const auto root = index / scale; return root * root; }
    static double oetf(double x) { return x <= .0031308 ? 12.92*x : 1.055 * std::pow(x, 1/2.4) - .055; }

    Triplet node(int index) const {
        Triplet result;
        // APK assets are not guaranteed to start at an aligned double address.
        std::memcpy(&result, values + static_cast<std::size_t>(index) * sizeof(Triplet), sizeof(result));
        return result;
    }

    Triplet interpolate(int base, const Triplet& f) const {
        int hi = 0, mid = 1, lo = 2;
        if (f[hi] < f[mid]) std::swap(hi, mid);
        if (f[mid] < f[lo]) std::swap(mid, lo);
        if (f[hi] < f[mid]) std::swap(hi, mid);
        const auto a = node(base);
        const auto b = node(base + stride[hi]);
        const auto c = node(base + stride[hi] + stride[mid]);
        const auto d = node(base + stride[0] + stride[1] + stride[2]);
        Triplet result;
        for (int i = 0; i < 3; ++i) {
            result[i] = a[i] + f[hi] * (b[i] - a[i]) + f[mid] * (c[i] - b[i]) + f[lo] * (d[i] - c[i]);
        }
        return result;
    }

    bool sample(Triplet linearRgb, Triplet& result) const {
        Triplet f;
        int index[3];
        for (int c = 0; c < 3; ++c) {
            if (!(linearRgb[c] > 0.0 && linearRgb[c] < rootMax * rootMax)) return false;
            const double p = std::sqrt(linearRgb[c]) * scale;
            index[c] = std::min(static_cast<int>(p), intervals - 1);
            f[c] = p - index[c];
        }
        if (!good[cell(index[0], index[1], index[2])]) return false;
        result = interpolate(offset(index[0], index[1], index[2]), f);
        return true;
    }
};
