#!/usr/bin/env python3
"""手写 JPEG 段解析器：不依赖 PIL，直接读字节流。
输出 SOF 采样因子、DQT 量化表、尺寸、EXIF 摘要、各段字节占用。
"""
import sys, struct, collections

MARKER_NAMES = {
    0xC0: "SOF0", 0xC1: "SOF1", 0xC2: "SOF2", 0xC3: "SOF3",
    0xC4: "DHT", 0xC5: "SOF5", 0xC6: "SOF6", 0xC7: "SOF7",
    0xC8: "JPG", 0xC9: "SOF9", 0xCA: "SOF10", 0xCB: "SOF11",
    0xCC: "DAC", 0xCD: "SOF13", 0xCE: "SOF14", 0xCF: "SOF15",
    0xD8: "SOI", 0xD9: "EOI", 0xDA: "SOS", 0xDB: "DQT", 0xDC: "DNL",
    0xDD: "DRI", 0xDE: "DHP", 0xDF: "EXP", 0xFE: "COM",
}

def parse(path):
    data = open(path, "rb").read()
    n = len(data)
    out = {"file": path, "bytes": n}
    assert data[:2] == b"\xff\xd8", "not a JPEG"
    i = 2
    segs = []           # (marker, offset, payload_len)
    sof = None
    dqts = []           # list of (table_id, precision, values)
    exif = None
    app_segs = []
    sos_offset = None
    while i < n - 1:
        if data[i] != 0xFF:
            i += 1
            continue
        m = data[i + 1]
        if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7:
            i += 2
            continue
        if m == 0xD9:
            segs.append(("EOI", i, 0)); break
        L = struct.unpack(">H", data[i + 2:i + 4])[0]
        payload = data[i + 4:i + 2 + L]
        name = MARKER_NAMES.get(m, f"0x{m:02X}")
        segs.append((name, i, L))
        if m in (0xC0, 0xC1, 0xC2):
            prec = payload[0]
            h, w = struct.unpack(">HH", payload[1:5])
            ncomp = payload[5]
            comps = []
            for c in range(ncomp):
                cid, hv, tq = payload[6 + c * 3: 9 + c * 3]
                comps.append({"id": cid, "h": hv >> 4, "v": hv & 0xF, "tq": tq})
            sof = {"marker": name, "precision": prec, "w": w, "h": h, "ncomp": ncomp, "comps": comps}
        elif m == 0xDB:
            k = 0
            while k < len(payload):
                pq, tq = payload[k] >> 4, payload[k] & 0xF
                k += 1
                if pq == 0:
                    vals = list(payload[k:k + 64]); k += 64
                else:
                    vals = list(struct.unpack(">64H", payload[k:k + 128])); k += 128
                dqts.append({"id": tq, "precision": pq, "values": vals})
        elif m == 0xE1:
            app_segs.append(("APP1", L, payload[:6]))
            if payload[:6] == b"Exif\x00\x00":
                exif = payload[6:]
        elif 0xE0 <= m <= 0xEF:
            app_segs.append((name, L, payload[:16]))
        elif m == 0xFE:
            out["comment"] = payload.decode("utf-8", "replace")
        if m == 0xDA:
            sos_offset = i
            break
        i += 2 + L
    out["sof"] = sof
    out["dqt"] = dqts
    out["segments"] = segs
    out["app_segs"] = app_segs
    out["sos_offset"] = sos_offset
    # 熵编码数据量估算 = 文件尾 - SOS 头之后
    if sos_offset is not None:
        # find SOS header length
        L = struct.unpack(">H", data[sos_offset + 2:sos_offset + 4])[0]
        scan_start = sos_offset + 2 + L
        out["scan_bytes"] = n - scan_start - 2  # 减去 EOI
        out["scan_start"] = scan_start
    out["exif_len"] = len(exif) if exif is not None else 0
    # 简单 EXIF TIFF 解析：列出 tag 号
    if exif:
        out["exif_tags"] = parse_tiff(exif)
    return out

def parse_tiff(t):
    tags = []
    try:
        if t[:2] == b"II": e = "<"
        elif t[:2] == b"MM": e = ">"
        else: return tags
        off = struct.unpack(e + "I", t[4:8])[0]
        if off + 2 > len(t): return tags
        cnt = struct.unpack(e + "H", t[off:off + 2])[0]
        for k in range(cnt):
            base = off + 2 + k * 12
            if base + 12 > len(t): break
            tag, typ, num = struct.unpack(e + "HHI", t[base:base + 8])
            size = {1:1,2:1,3:2,4:4,5:8,6:1,7:1,8:2,9:4,10:8,11:4,12:8}.get(typ,1)*num
            voff = base + 8 if size <= 4 else off + struct.unpack(e + "I", t[base+8:base+12])[0]
            val = None
            try:
                if typ == 2:
                    val = t[voff:voff+num].split(b"\x00")[0].decode("ascii","replace")
                elif typ == 3:
                    val = list(struct.unpack(e + f"{num}H", t[voff:voff+2*num]))
                elif typ == 4:
                    val = list(struct.unpack(e + f"{num}I", t[voff:voff+4*num]))
                elif typ in (5,10):
                    val = [struct.unpack(e+"II", t[voff+8*j:voff+8*j+8]) for j in range(num)]
            except Exception:
                val = "<unreadable>"
            tags.append((tag, typ, num, val))
            if tag == 0x8769:  # ExifIFD
                sub = None
                try:
                    sub = struct.unpack(e + "I", t[base+8:base+12])[0]
                except Exception: pass
                if sub: tags.extend(parse_ifd(t, e, off + sub, "ExifIFD"))
    except Exception as ex:
        tags.append(("ERR", 0, 0, str(ex)))
    return tags

def parse_ifd(t, e, off, name):
    tags = []
    try:
        cnt = struct.unpack(e + "H", t[off:off+2])[0]
        for k in range(cnt):
            base = off + 2 + k*12
            if base+12 > len(t): break
            tag, typ, num = struct.unpack(e+"HHI", t[base:base+8])
            size = {1:1,2:1,3:2,4:4,5:8,6:1,7:1,8:2,9:4,10:8,11:4,12:8}.get(typ,1)*num
            voff = base+8 if size <= 4 else off + struct.unpack(e+"I", t[base+8:base+12])[0]
            val = None
            try:
                if typ == 2: val = t[voff:voff+num].split(b"\x00")[0].decode("ascii","replace")
                elif typ == 3: val = list(struct.unpack(e+f"{num}H", t[voff:voff+2*num]))
                elif typ == 4: val = list(struct.unpack(e+f"{num}I", t[voff:voff+4*num]))
                elif typ in (5,10): val = [struct.unpack(e+"II", t[voff+8*j:voff+8*j+8]) for j in range(num)]
                elif typ == 7: val = t[voff:voff+num]
            except Exception: val = "<unreadable>"
            tags.append((f"{name}:{tag}", typ, num, val))
    except Exception as ex:
        tags.append(("ERR",0,0,str(ex)))
    return tags

EXIF_TAG_NAMES = {
 0x0100:"ImageWidth",0x0101:"ImageLength",0x010F:"Make",0x0110:"Model",0x0112:"Orientation",
 0x011A:"XResolution",0x011B:"YResolution",0x0128:"ResolutionUnit",0x0131:"Software",
 0x0132:"DateTime",0x0213:"YCbCrPositioning",0x8769:"ExifIFDPointer",0x8825:"GPSInfoIFDPointer",
 0x829A:"ExposureTime",0x829D:"FNumber",0x8827:"ISOSpeedRatings",0x9000:"ExifVersion",
 0x9003:"DateTimeOriginal",0x9004:"DateTimeDigitized",0x9201:"ShutterSpeedValue",
 0x9202:"ApertureValue",0x9204:"ExposureBiasValue",0x9207:"MeteringMode",0x9209:"Flash",
 0x920A:"FocalLength",0x927C:"MakerNote",0x9286:"UserComment",0xA000:"FlashpixVersion",
 0xA001:"ColorSpace",0xA002:"PixelXDimension",0xA003:"PixelYDimension",0xA405:"FocalLengthIn35mmFilm",
 0xA430:"CameraOwnerName",0xA431:"BodySerialNumber",0xA432:"LensSpecification",0xA433:"LensMake",
 0xA434:"LensModel",0xA435:"LensSerialNumber",0x0102:"BitsPerSample",0x0103:"Compression",
 0x0106:"PhotometricInterpretation",0x0115:"SamplesPerPixel",0x011C:"PlanarConfiguration",
 0xA406:"SceneCaptureType",0xA402:"ExposureMode",0xA403:"WhiteBalance",0xA401:"CustomRendered",
 0xA404:"DigitalZoomRatio",0x9291:"SubSecTimeOriginal",0x9292:"SubSecTimeDigitized",
 0x0001:"GPSLatitudeRef",0x0002:"GPSLatitude",0x0003:"GPSLongitudeRef",0x0004:"GPSLongitude",
 0x0006:"GPSAltitude",0x001D:"GPSDateStamp",
}

def fmt_tags(tags):
    lines = []
    for tag, typ, num, val in tags:
        if isinstance(tag, str) and ":" in tag:
            t = tag.split(":")[1]
            try: nm = EXIF_TAG_NAMES.get(int(t), tag)
            except Exception: nm = tag
        else:
            nm = EXIF_TAG_NAMES.get(tag, f"0x{tag:04X}")
        if isinstance(val, bytes):
            val = f"<{len(val)} bytes> " + val[:24].hex()
        lines.append(f"    {nm} (tag={tag}) type={typ} n={num} = {val}")
    return "\n".join(lines)

def report(p):
    o = parse(p)
    print("=" * 78)
    print(f"FILE: {o['file']}")
    print(f"  size_bytes          = {o['bytes']:,}")
    print(f"  scan(entropy)bytes  = {o.get('scan_bytes',0):,}")
    print(f"  header_bytes        = {o['bytes'] - o.get('scan_bytes',0):,}")
    sof = o["sof"]
    print(f"  SOF                 = {sof['marker']}  precision={sof['precision']}bit  {sof['w']}x{sof['h']}  ncomp={sof['ncomp']}")
    tot = 0; desc = []
    for c in sof["comps"]:
        desc.append(f"id={c['id']} H={c['h']} V={c['v']} Tq={c['tq']}")
        tot += c["h"] * c["v"]
    hmax = max(c["h"] for c in sof["comps"]); vmax = max(c["v"] for c in sof["comps"])
    # 采样描述
    subs = []
    for c in sof["comps"]:
        subs.append(f"{c['h']*vmax}:{hmax*c['v']}")
    print(f"  components          = " + " | ".join(desc))
    print(f"  sampling ratio      = {hmax}x{vmax} -> per-comp H:V = {', '.join(subs)}")
    if hmax == 1 and vmax == 1:
        verdict = "4:4:4 (no subsampling)"
    elif hmax == 2 and vmax == 1:
        verdict = "4:2:2 (h subsampled)"
    elif hmax == 2 and vmax == 2:
        verdict = "4:2:0 (both subsampled)"
    else:
        verdict = f"{hmax}x{vmax} unknown"
    print(f"  CHROMA VERDICT      = {verdict}")
    for q in o["dqt"]:
        v = q["values"]
        print(f"  DQT id={q['id']} prec={q['precision']} first16 = {v[:16]}")
        print(f"      all-ones? {all(x==1 for x in v)}   min={min(v)} max={max(v)} distinct={len(set(v))}")
        print(f"      table = {v}")
    print(f"  EXIF len            = {o['exif_len']:,} bytes")
    print(f"  APP segments        = " + ", ".join(f"{a[0]}(len={a[1]})" for a in o["app_segs"]))
    if o.get("comment"): print(f"  COM                 = {o['comment']!r}")
    segcount = collections.Counter(s[0] for s in o["segments"])
    print(f"  segment histogram   = {dict(segcount)}")
    print(f"  DHT count           = {segcount.get('DHT',0)}")
    if o.get("exif_tags"):
        print("  EXIF tags:")
        print(fmt_tags(o["exif_tags"]))
    return o

if __name__ == "__main__":
    for p in sys.argv[1:]:
        report(p)
