#!/usr/bin/env python3
"""Compile actual diagnostic state/scope definitions and test thread isolation."""

import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent.parent
    source = (root / "core/engine/src/render/FrameRenderer.cpp").read_text()
    declarations = []
    for name in ("g_diag_stats", "g_diag_params", "g_diag_chain",
                 "g_diag_seq", "g_diag_next_seq"):
        declaration = re.search(r"^[^\n;]*\b" + name + r"[^\n;]*;", source, re.M)
        assert declaration, name
        declarations.append(declaration.group())
    begin = source.index("void diagBeginFrame() {")
    scope = source.index("struct DiagFrameScope {", begin)
    end = source.index("\n};", scope) + 3
    definitions = source[begin:end]
    program = r'''
#include <atomic>
#include <cassert>
#include <string>
#include <thread>
DECLARATIONS
bool diagOn() { return g_diag_stats.load(std::memory_order_relaxed); }
DEFINITIONS
void earlyReturn() { const DiagFrameScope scope; assert(g_diag_chain); }
int main() {
  assert(!g_diag_chain);
  earlyReturn();
  assert(!g_diag_chain);
  for (int iteration = 0; iteration < 500; ++iteration) {
    const DiagFrameScope still;
    const int sequence = g_diag_seq;
    g_diag_params = "still";
    std::atomic<int> phase{0};
    std::thread preview([&] {
      assert(!g_diag_chain && g_diag_params.empty() && g_diag_seq == 0);
      {
        const DiagFrameScope independent;
        assert(g_diag_chain && g_diag_seq != sequence);
        g_diag_params = "other";
        phase.store(1);
        while (phase.load() != 2) std::this_thread::yield();
      }
      assert(!g_diag_chain);
      phase.store(3);
    });
    while (phase.load() != 1) std::this_thread::yield();
    assert(g_diag_chain && g_diag_seq == sequence && g_diag_params == "still");
    phase.store(2);
    while (phase.load() != 3) std::this_thread::yield();
    assert(g_diag_chain && g_diag_seq == sequence && g_diag_params == "still");
    preview.join();
  }
  assert(!g_diag_chain);
  g_diag_stats.store(false);
  { const DiagFrameScope off; assert(!g_diag_chain); }
  assert(!g_diag_chain);
}
'''.replace("DECLARATIONS", "\n".join(declarations)).replace("DEFINITIONS", definitions)
    with tempfile.TemporaryDirectory(prefix="filmkit-diag-threading-") as tmp:
        cpp = Path(tmp) / "check.cpp"
        cpp.write_text(program)
        binary = Path(tmp) / "check"
        subprocess.run([os.environ.get("CXX", "c++"), "-std=c++17", "-O2",
                        "-pthread", "-Wall", "-Wextra", "-Werror",
                        str(cpp), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print("PASS: 500 concurrent diagnostic scopes, unique IDs, early return, disabled mode")


if __name__ == "__main__":
    main()
