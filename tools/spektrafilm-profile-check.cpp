#include "film/spektrafilm/Profile.h"

#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>

using namespace filmkit::spektrafilm;

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        std::ifstream input(argv[1], std::ios::binary);
        const std::vector<char> raw{std::istreambuf_iterator<char>(input), {}};
        const auto bytes = std::as_bytes(std::span(raw));
        std::string error;
        auto p = loadNegativeProfile(bytes, &error);
        if (!p || p->pixelUm != 35000.0 || !p->grain.active || !p->grain.layered || !p->halation.active)
            throw std::runtime_error("valid profile rejected: " + error);
        std::size_t rejected = 0;
        const auto reject = [&](auto data) {
            if (loadNegativeProfile(data, &error) || error.empty()) throw std::runtime_error("invalid profile accepted");
            ++rejected;
        };
        for (std::size_t n = 0; n < 64; ++n) reject(bytes.first(n));
        reject(bytes.first(bytes.size() / 2));
        reject(bytes.first(bytes.size() - 1));
        auto modified = raw;
        modified.push_back(0);
        reject(std::as_bytes(std::span(modified)));
        for (std::size_t index : {0, 1, 2, 3, 4, 5, 6, 7, 8}) {
            modified = raw;
            const std::uint32_t value = 0xffffffff;
            std::memcpy(modified.data() + index * 4, &value, 4);
            reject(std::as_bytes(std::span(modified)));
        }
        modified = raw;
        const double nan = std::numeric_limits<double>::quiet_NaN();
        std::memcpy(modified.data() + 48, &nan, sizeof(nan));
        reject(std::as_bytes(std::span(modified)));
        if (!installNegativeProfile(bytes, &error)) throw std::runtime_error(error);
        const auto installed = negativeProfile();
        if (installNegativeProfile({}, &error) || negativeProfile() != installed)
            throw std::runtime_error("failed replacement changed live profile");
        std::array<std::thread, 8> threads;
        for (auto& thread : threads) thread = std::thread([&] {
            for (int i = 0; i < 100; ++i) if (negativeProfile() != installed) std::terminate();
        });
        for (auto& thread : threads) thread.join();
        std::cout << "profile_bytes=" << bytes.size() << " rejected=" << rejected
                  << " concurrent_reads=800 passed=1\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
