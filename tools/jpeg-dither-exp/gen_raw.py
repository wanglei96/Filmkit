#!/usr/bin/env python3
"""第 4 项：生成实验输入 raw。
- raw_plain.rgba  : 原片解码 → RGBA (A=255)          [变体 B 输入]
- raw_dither.rgba : 原片解码 → +一次 TPDF dither → RGBA [变体 C 输入]

dither 与引擎逐字同式（FrameRenderer.cpp:4579 / 4703）：
  c += (dhash(gl_FragCoord.xy) + dhash(gl_FragCoord.xy + vec2(37,17)) - 1.0) / 255.0
dhash（Dave Hoskins 无 sin hash，float32 复现）：
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);

坐标：gl_FragCoord.xy = (col+0.5, H-1-row+0.5)  —— GL 原点在左下角。
"""
import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None
SRC = "test_temp/FILMKIT_20261009-090810.jpg"
W, H = 3072, 4080

def dhash(px, py):
    """float32 精确复现 GLSL dhash。px/py 用广播（(1,W) 与 (H,1)），逐元素值与
    逐点计算完全相同。"""
    f32 = np.float32

    def fract(a):
        # GLSL fract(x) = x - floor(x)（对负数同样成立）
        return a - np.floor(a).astype(np.float32)
    p3x = fract(px * f32(0.1031))
    p3y = fract(py * f32(0.1031))
    p3z = p3x.copy()                       # vec3(p.xyx) → (x, y, x)
    # p3 += dot(p3, p3.yzx + 33.33)
    d = (p3x * (p3y + f32(33.33)) + p3y * (p3z + f32(33.33)) + p3z * (p3x + f32(33.33)))
    p3x = p3x + d; p3y = p3y + d; p3z = p3z + d
    return fract((p3x + p3y) * p3z)

def dither_field(w, h):
    """返回 shape (h, w) 的 float32 dither，单位 = LSB（即乘 255 后的域）。
    引擎里是 /255 后加到 [0,1] 的 c 上；量化到 RGBA8 时等价于对整数像素值加
    (dhash+dhash-1)。"""
    rows = np.arange(h, dtype=np.float32)
    cols = np.arange(w, dtype=np.float32)
    # gl_FragCoord: x = col+0.5 ; y = (h-1-row)+0.5 = h-row-0.5
    # 用广播而非 repeat：逐元素运算值与逐点计算完全相同，内存 O(H+W)
    gx = (cols + np.float32(0.5))[None, :]                       # (1, W)
    # 实测（diag.py 坐标搜索）：编辑器链路有效坐标 = (col+0.5, row+0.5)，即 gl_FragCoord.y
    # 与 JPEG 行号同向（分离度 +1.72/理论+2、命中率 79.95%）；H1(H-row-0.5) 分离度 0.001。
    gy = (rows + np.float32(0.5))[:, None]                       # (H, 1)  H2
    a = dhash(gx, gy)
    b = dhash(gx + np.float32(37.0), gy + np.float32(17.0))
    return (a + b) - np.float32(1.0)      # ∈ [-1, 1]，单位 LSB

def main():
    im = Image.open(SRC)
    rgb = np.asarray(im, dtype=np.uint8)
    assert rgb.shape == (H, W, 3), rgb.shape

    rgba = np.empty((H, W, 4), dtype=np.uint8)
    rgba[..., :3] = rgb
    rgba[..., 3] = 255
    rgba.tofile("tools/jpeg-dither-exp/raw_plain.rgba")
    print("wrote raw_plain.rgba", rgba.nbytes)

    d = dither_field(W, H)
    print(f"dither: min={d.min():.6f} max={d.max():.6f} mean={d.mean():.6f} std={d.std():.6f}")
    nz = np.abs(d) > 0.5
    print(f"  |d|>0.5 占比 = {nz.mean()*100:.3f}%  (理论 TPDF 25.000%)")
    print(f"  P(d>0.5)={ (d>0.5).mean()*100:.3f}%  P(d<-0.5)={ (d<-0.5).mean()*100:.3f}%  (理论各 12.5%)")

    # 引擎：c = p/255 + d/255，clamp 后写 RGBA8 ⇒ round(c*255)
    # 等价于 round(clamp(p + d))  (p 为整数像素值)
    src = rgb.astype(np.float32)
    v = np.clip(src + d[..., None], 0.0, 255.0)
    out = np.rint(v).astype(np.uint8)

    # 记录"编码前"的预测差 Δ_pre = out - src（整数）
    dpre = out.astype(np.int16) - rgb.astype(np.int16)
    print(f"  预测 Δ_pre: =0 {(dpre==0).mean()*100:.3f}%  =±1 {(np.abs(dpre)==1).mean()*100:.3f}%  "
          f"=±2 {(np.abs(dpre)==2).mean()*100:.3f}%  max|Δ|={np.abs(dpre).max()}")

    rgba2 = np.empty((H, W, 4), dtype=np.uint8)
    rgba2[..., :3] = out
    rgba2[..., 3] = 255
    rgba2.tofile("tools/jpeg-dither-exp/raw_dither.rgba")
    print("wrote raw_dither.rgba", rgba2.nbytes)
    np.save("tools/jpeg-dither-exp/dither_field.npy", d)
    print("wrote dither_field.npy")

if __name__ == "__main__":
    main()
