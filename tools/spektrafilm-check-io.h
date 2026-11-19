#pragma once

#include <fstream>
#include <span>
#include <stdexcept>

namespace spektrafilm_check {
template <class Range>
void load(const char* path, Range& range) {
    std::ifstream in(path, std::ios::binary);
    const auto bytes = std::as_writable_bytes(std::span(range));
    if (!in.read(reinterpret_cast<char*>(bytes.data()), bytes.size()) ||
        in.peek() != std::char_traits<char>::eof()) {
        throw std::runtime_error("invalid native stage input");
    }
}

template <class Range>
void save(const char* path, const Range& range) {
    std::ofstream out(path, std::ios::binary);
    const auto bytes = std::as_bytes(std::span(range));
    out.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    out.close();
    if (!out) throw std::runtime_error("failed writing native stage output");
}
}
