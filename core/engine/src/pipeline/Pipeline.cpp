#include "Pipeline.h"

namespace filmkit {

void Pipeline::addPass(std::string name, Fn fn) {
  passes_.emplace_back(std::move(name), std::move(fn));
}

bool Pipeline::run(std::vector<PassTiming>* timings) {
  if (timings) timings->clear();
  for (auto& [name, fn] : passes_) {
    PassTiming t;
    t.name = name;
    if (!fn(&t)) {
      if (timings) timings->push_back(t);
      return false;
    }
    if (timings) timings->push_back(t);
  }
  return true;
}

void Pipeline::clear() {
  passes_.clear();
}

}  // namespace filmkit
