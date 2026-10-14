#!/usr/bin/env python3
"""10bit HEIF 静态图容器 —— **可复跑的原型参照**（2026-10-14 验证通过）。

## 为什么有这个文件
真机探针（`app/src/main/java/com/filmkit/app/diag/Heif10BitProbe.kt`）证明：
`MediaCodec`(HEVC Main10 + P010) 编码**成功**、`colr/nclx` 标签**正确落盘**、
`hvcC` 声明 **10bit**（profile_idc=2 / bitDepthLumaMinus8=2），
但 `MediaMuxer(MUXER_OUTPUT_HEIF)` 产出的容器是 **MP4**（`ftyp=mp42` + `moov/trak` 视频轨），
**不是 HEIF 图片**（无顶层 `meta/iinf/iloc/iprp`）⇒ 相册不认。

本文件把"自己拼 HEIF 图片容器"这条路**端到端验证**了一遍：
从探针产出的 MP4 里取 `hvcC` + 帧数据 → 拼 HEIF → **macOS 原生解码器强制解码出正确像素**。
⇒ 期 A 的 10bit 路线 = 自研容器写入器（无需引入 libheif）。

## 用法（需要先有探针产出的文件）
    adb pull /sdcard/Android/data/com.filmkit.app/files/heif10/ /tmp/heif10/
    python3 tools/heif10-container-ref.py /tmp/heif10/heif10
    sips -s format png /tmp/heif10/heif10/selfmade-*.heic --out /tmp/out.png   # 真解码验证

## 关键事实（实现时照抄）
- **帧数据格式天然一致**：`hvcC.lengthSizeMinusOne=3` ⇒ mdat 内是 4 字节长度前缀的 NAL
  ⇒ HEIF item data **直接搬运，无需重打包**。
- **CICP 必须显式写**：Android 的 `COLOR_STANDARD_BT2020=6`/`COLOR_TRANSFER_LINEAR=1`
  **不是** CICP；HEIF 的 `nclx` 要写 **primaries=9 / transfer=8 / matrix=9 / full_range=1**
  （实测：平台自己做了这个映射，但自研容器里必须由我们写死）。
- **`iloc` 的 offset 是绝对文件偏移** ⇒ 两遍构造：先算 `meta` 长度，再回填 `mdat` 内数据偏移。
- **`ipma` 的关联是 1-based**，顺序必须与 `ipco` 内 property 顺序一致。
- **`pixi`** 声明通道位深（3 通道 × 10bit）⇒ macOS 读 `bitsPerSample=10`。
- 自研容器**不需要** `MediaMuxer` 那个 `free` box（它只是 muxer 的预留空间）。

## 验证记录
`selfmade-*.heic`（650 B）→ `sips -s format png` 成功（12026 B PNG）→
PIL 读出 1024×256 RGB、左上亮度 208、非全黑 ⇒ 容器合法且像素有效。
"""
import os
import struct
import sys


def boxes(b, start, end):
    off = start
    while off + 8 <= end:
        size, typ = struct.unpack('>I4s', b[off:off + 8])
        hdr = 8
        if size == 1:
            size = struct.unpack('>Q', b[off + 8:off + 16])[0]
            hdr = 16
        elif size == 0:
            size = end - off
        if size < hdr or off + size > end:
            return
        yield typ, off, off + hdr, off + size
        off += size


def find_all(b, start, end, want, path=()):
    """递归找 box（容器/FullBox/VisualSampleEntry 的头部长度各不相同）。"""
    for typ, o, body, bend in boxes(b, start, end):
        p = path + (typ,)
        if typ == want:
            yield o, body, bend, p
        if typ in (b'moov', b'trak', b'mdia', b'minf', b'stbl', b'dinf', b'edts', b'iprp', b'ipco'):
            yield from find_all(b, body, bend, want, p)
        elif typ in (b'meta', b'hdlr', b'pitm', b'iinf', b'iloc'):
            yield from find_all(b, body + 4, bend, want, p)
        elif typ == b'stsd':
            yield from find_all(b, body + 8, bend, want, p)
        elif typ in (b'hvc1', b'hev1'):
            yield from find_all(b, body + 78, bend, want, p)


def box(typ, payload):
    return struct.pack('>I4s', len(payload) + 8, typ) + payload


def fbox(typ, ver, flags, payload):
    return box(typ, struct.pack('>B3s', ver, flags) + payload)


def build_heif(hvcc, frame, w, h, primaries=9, transfer=8, matrix=9, full_range=1):
    """把 `hvcC` + 一帧 length-prefixed HEVC 数据封成 HEIF 图片。"""
    ftyp = box(b'ftyp', b'heic' + struct.pack('>I', 0) + b'mif1' + b'heic')
    hdlr = fbox(b'hdlr', 0, b'\0\0\0', struct.pack('>I', 0) + b'pict' + b'\0' * 12 + b'\0')
    pitm = fbox(b'pitm', 0, b'\0\0\0', struct.pack('>H', 1))
    iinf = fbox(b'iinf', 0, b'\0\0\0',
                struct.pack('>H', 1) + fbox(b'infe', 2, b'\0\0\0', struct.pack('>HH', 1, 0) + b'hvc1' + b'\0'))
    ipco = box(b'ipco',
               box(b'hvcC', hvcc)
               + fbox(b'ispe', 0, b'\0\0\0', struct.pack('>II', w, h))
               + fbox(b'pixi', 0, b'\0\0\0', bytes([3, 10, 10, 10]))
               + box(b'colr', b'nclx' + struct.pack('>HHHB', primaries, transfer, matrix,
                                                   0x80 if full_range else 0x00)))
    ipma = fbox(b'ipma', 0, b'\0\0\0',
                struct.pack('>I', 1) + struct.pack('>H', 1) + bytes([4, 0x81, 0x82, 0x83, 0x84]))

    def iloc(off):  # v0: offset_size|length_size = 4|4, base_offset_size = 0, 1 item / 1 extent
        return fbox(b'iloc', 0, b'\0\0\0',
                    bytes([0x44, 0x00]) + struct.pack('>H', 1)
                    + struct.pack('>HH', 1, 0) + struct.pack('>H', 1)
                    + struct.pack('>II', off, len(frame)))

    meta_empty = fbox(b'meta', 0, b'\0\0\0', hdlr + pitm + iinf + iloc(0) + box(b'iprp', ipco + ipma))
    mdat_off = len(ftyp) + len(meta_empty) + 8
    meta = fbox(b'meta', 0, b'\0\0\0', hdlr + pitm + iinf + iloc(mdat_off) + box(b'iprp', ipco + ipma))
    assert len(meta) == len(meta_empty), 'iloc 回填不能改变 meta 长度'
    return ftyp + meta + box(b'mdat', frame)


def extract_from_mp4(b):
    """从 MediaMuxer 产出的 MP4 里取 hvcC / 尺寸 / 第一帧数据。"""
    _, hb, he, _ = next(find_all(b, 0, len(b), b'hvcC'))
    hvcc = b[hb:he]
    _, vb, _, _ = next(find_all(b, 0, len(b), b'hvc1'))
    w, h = struct.unpack('>HH', b[vb + 24:vb + 28])
    _, sb, _, _ = next(find_all(b, 0, len(b), b'stsz'))
    default_size = struct.unpack('>I', b[sb + 4:sb + 8])[0]
    n = struct.unpack('>I', b[sb + 8:sb + 12])[0]
    sizes = [default_size] * n if default_size else \
        [struct.unpack('>I', b[sb + 12 + 4 * i:sb + 16 + 4 * i])[0] for i in range(n)]
    _, cb, _, _ = next(find_all(b, 0, len(b), b'co64'))
    m = struct.unpack('>I', b[cb + 4:cb + 8])[0]
    offs = [struct.unpack('>Q', b[cb + 8 + 8 * i:cb + 16 + 8 * i])[0] for i in range(m)]
    return hvcc, w, h, b[offs[0]:offs[0] + sizes[0]]


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    done = 0
    for name in sorted(os.listdir(root)):
        if not (name.startswith('probe-') and name.endswith('.heic')):
            continue
        src = os.path.join(root, name)
        b = open(src, 'rb').read()
        hvcc, w, h, frame = extract_from_mp4(b)
        out = build_heif(hvcc, frame, w, h)
        dst = os.path.join(root, 'selfmade-' + name)
        open(dst, 'wb').write(out)
        print(f'{name}: {w}x{h} hvcC={len(hvcc)}B frame={len(frame)}B → {dst} ({len(out)}B)')
        done += 1
    if not done:
        print(f'没找到 probe-*.heic（目录 {root}）；先跑探针并 adb pull', file=sys.stderr)
        sys.exit(1)
    print('\n真解码验证：sips -s format png <selfmade-*.heic> --out /tmp/out.png')


if __name__ == '__main__':
    main()
