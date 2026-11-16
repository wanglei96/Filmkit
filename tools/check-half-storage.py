#!/usr/bin/env python3
"""Compare production binary16 round-trip against its original ldexp decoder."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


ORIGINAL_DECODE = r'''
  const uint32_t e = (h >> 10) & 31u;
  const uint32_t m = h & 1023u;
  float out;
  if (e == 31u) {
    const uint32_t bits = sign | 0x7f800000u | (m ? 0x400000u : 0u);
    std::memcpy(&out, &bits, sizeof(out));
    return out;
  }
  out = e ? std::ldexp(static_cast<float>(1024u + m), static_cast<int>(e) - 25)
          : std::ldexp(static_cast<float>(m), -24);
  return sign ? -out : out;
}
'''

DIRECT_DECODE = r'''
  const uint32_t e = (h >> 10) & 31u;
  const uint32_t m = h & 1023u;
  float out;
  if (e != 0u) {
    const uint32_t bits = sign | (e == 31u
        ? 0x7f800000u | (m ? 0x400000u : 0u)
        : ((e + 112u) << 23) | (m << 13));
    std::memcpy(&out, &bits, sizeof(out));
    return out;
  }
  out = std::ldexp(static_cast<float>(m), -24);
  return sign ? -out : out;
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", action="store_true")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--binary", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    source = (root / "core/engine/src/render/HighlightRecovery.cpp").read_text()
    start = source.index("float halfStorage(float value) {")
    end = source.index("\n}\n", start) + 2
    production = source[start:end]
    encoder = production[:production.index("  const uint32_t e = (h >> 10)")]
    reference = (encoder + ORIGINAL_DECODE).replace("halfStorage(", "reference(", 1)
    candidate = encoder + DIRECT_DECODE if args.candidate else production
    program = r'''
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
__attribute__((noinline)) REFERENCE
__attribute__((noinline)) CANDIDATE
uint32_t bits(float f) { uint32_t b; std::memcpy(&b,&f,4); return b; }
float value(uint32_t b) { float f; std::memcpy(&f,&b,4); return f; }
uint64_t checks = 0;
void check(uint32_t b) {
  const uint32_t a=bits(reference(value(b))), c=bits(halfStorage(value(b)));
  if(a!=c) { std::printf("FAIL input=%08x reference=%08x actual=%08x\n",b,a,c); std::exit(1); }
  ++checks;
}
volatile double sink;
int main() {
  // Every half value and every halfway-rounding boundary, both signs, +/- 1 ULP.
  for(uint32_t h=0;h<0x7c00u;++h) {
    unsigned e=h>>10, m=h&1023u;
    float lo=e ? std::ldexp(float(1024u+m),int(e)-25) : std::ldexp(float(m),-24);
    unsigned next=h+1, ne=next>>10, nm=next&1023u;
    float hi=next==0x7c00u ? 65536.f : ne ? std::ldexp(float(1024u+nm),int(ne)-25) : std::ldexp(float(nm),-24);
    for(float v : {lo, (lo+hi)*.5f}) {
      uint32_t b=bits(v);
      for(int d=-1;d<=1;++d) { check(b+d); check((b+d)|0x80000000u); }
    }
  }
  for(uint32_t m=0;m<=0x7fffffu;m+=127u) { check(0x7f800000u|m); check(0xff800000u|m); }
  uint32_t random=1234567u;
  for(int i=0;i<4000000;++i) { random=random*1664525u+1013904223u; check(random); }
  std::printf("PASS %llu bit-identical round trips\n",(unsigned long long)checks);
  std::vector<float> samples(1024*1024);
  for(size_t i=0;i<samples.size();++i) samples[i]=float(int(i%131071)-65535)/16384.f;
  for(int round=0;round<6;++round) {
    const bool old=round%2==0;
    const auto start=std::chrono::steady_clock::now();
    double sum=0;
    for(int repeat=0;repeat<8;++repeat)
      for(float v:samples) sum+=old?reference(v):halfStorage(v);
    sink=sum;
    double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
    std::printf("BENCH decoder=%s ms=%.6f checksum=%.6f\n",old?"original":"candidate",ms,sum);
  }
}
'''.replace("REFERENCE", reference).replace("CANDIDATE", candidate)
    with tempfile.TemporaryDirectory(prefix="filmkit-half-storage-") as tmp:
        cpp = Path(tmp) / "check.cpp"
        cpp.write_text(program)
        binary = args.binary or Path(tmp) / "check"
        compiler = str(args.ndk / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang++") if args.ndk else os.environ.get("CXX", "c++")
        subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                        str(cpp), "-o", str(binary)], check=True)
        if not args.ndk:
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
