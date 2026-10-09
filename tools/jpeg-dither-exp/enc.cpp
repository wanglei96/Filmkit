// 第 4 项隔离实验：用仓库真实的 stb_image_write.h，按真机 JpegEncoder.cpp 的调用方式
// （comp=4 / q=100 / STBI_WRITE_NO_STDIO + to_func）编码给定的 RGBA raw。
// 用法: ./enc <in.rgba> <w> <h> <out.jpg>   -> 打印精确字节数
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#define STBIW_ASSERT(x) ((void)0)
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include "stb_image_write.h"

namespace {
struct ByteSink { std::vector<unsigned char> buf; };
void sinkWrite(void* ctx, void* data, int size) {
  ByteSink* s = static_cast<ByteSink*>(ctx);
  const unsigned char* p = static_cast<const unsigned char*>(data);
  s->buf.insert(s->buf.end(), p, p + size);
}
}  // namespace

int main(int argc, char** argv) {
  if (argc != 5) { fprintf(stderr, "usage: %s in.rgba w h out.jpg\n", argv[0]); return 2; }
  const char* in = argv[1];
  const int w = atoi(argv[2]), h = atoi(argv[3]);
  const char* out = argv[4];
  const size_t need = (size_t)w * h * 4;
  std::vector<unsigned char> px(need);
  FILE* f = fopen(in, "rb");
  if (!f) { perror("open in"); return 1; }
  size_t got = fread(px.data(), 1, need, f);
  fclose(f);
  if (got != need) { fprintf(stderr, "short read %zu != %zu\n", got, need); return 1; }

  ByteSink sink;
  sink.buf.reserve(need / 3 + 4096);
  // 与 JpegEncoder.cpp:89 完全一致的调用形态：comp=4, quality=100
  int ok = stbi_write_jpg_to_func(sinkWrite, &sink, w, h, 4, px.data(), 100);
  if (!ok) { fprintf(stderr, "encode failed\n"); return 1; }
  FILE* g = fopen(out, "wb");
  if (!g) { perror("open out"); return 1; }
  fwrite(sink.buf.data(), 1, sink.buf.size(), g);
  fclose(g);
  printf("%s\t%zu\t%d\t%d\n", out, sink.buf.size(), w, h);
  return 0;
}
