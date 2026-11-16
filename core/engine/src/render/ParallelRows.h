#pragma once

#include <algorithm>
#include <thread>
#include <vector>

namespace filmkit {

// Disjoint row ranges; reductions remain in caller-defined serial order.
template <typename Fn>
void parallelRows(int n, Fn fn) {
  const unsigned hc = std::thread::hardware_concurrency();
  const int workers = static_cast<int>(std::min<unsigned>(hc > 0 ? hc : 1, 8));
  if (workers <= 1 || n < 8) {
    fn(0, n);
    return;
  }
  const int nw = std::min(workers, n);
  const int chunk = (n + nw - 1) / nw;
  std::vector<std::thread> ts;
  ts.reserve(static_cast<size_t>(nw));
  for (int w = 0; w < nw; ++w) {
    const int b = w * chunk;
    const int e = std::min(n, b + chunk);
    if (b >= e) break;
    ts.emplace_back(fn, b, e);
  }
  for (auto& t : ts) t.join();
}

}  // namespace filmkit
