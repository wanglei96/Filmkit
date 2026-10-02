#!/usr/bin/env python3
"""PMRID 官方网络 → 可训练 PyTorch 模型（结构逐层照搬 net_mge_ref.py）+ 数值自检。

M.* → torch.nn.* 一一对应；MegEngine 用 OrderedDict 命名的子模块（conv /
depthwise / pointwise / relu）在 PyTorch 里用同样的键名，因此 ONNX initializer
名与 model.state_dict() 键名 1:1 同名同形，无需重排。

用法: python3 tools/nn/pmrid_torch.py
"""
from collections import OrderedDict
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

ONNX_PATH = Path(__file__).resolve().parent / "out" / "pmrid_512.onnx"
TILE = 512  # ONNX 输入是静态 [1,4,512,512]，onnxruntime 只吃这个尺寸


def Conv2D(in_channels: int, out_channels: int, kernel_size: int, stride: int,
           padding: int, is_seperable: bool = False, has_relu: bool = False):
    modules = OrderedDict()
    if is_seperable:
        modules["depthwise"] = nn.Conv2d(
            in_channels, in_channels, kernel_size, stride, padding,
            groups=in_channels, bias=False)
        modules["pointwise"] = nn.Conv2d(
            in_channels, out_channels, kernel_size=1, stride=1, padding=0, bias=True)
    else:
        modules["conv"] = nn.Conv2d(
            in_channels, out_channels, kernel_size, stride, padding, bias=True)
    if has_relu:
        modules["relu"] = nn.ReLU()
    return nn.Sequential(modules)


class EncoderBlock(nn.Module):

    def __init__(self, in_channels: int, mid_channels: int, out_channels: int, stride: int = 1):
        super().__init__()
        self.conv1 = Conv2D(in_channels, mid_channels, kernel_size=5, stride=stride,
                            padding=2, is_seperable=True, has_relu=True)
        self.conv2 = Conv2D(mid_channels, out_channels, kernel_size=5, stride=1,
                            padding=2, is_seperable=True, has_relu=False)
        self.proj = (
            nn.Identity()
            if stride == 1 and in_channels == out_channels else
            Conv2D(in_channels, out_channels, kernel_size=3, stride=stride,
                   padding=1, is_seperable=True, has_relu=False)
        )
        self.relu = nn.ReLU()

    def forward(self, x):
        proj = self.proj(x)
        x = self.conv1(x)
        x = self.conv2(x)
        x = x + proj
        return self.relu(x)


def EncoderStage(in_channels: int, out_channels: int, num_blocks: int):
    blocks = [
        EncoderBlock(in_channels=in_channels, mid_channels=out_channels // 4,
                     out_channels=out_channels, stride=2)
    ]
    for _ in range(num_blocks - 1):
        blocks.append(
            EncoderBlock(in_channels=out_channels, mid_channels=out_channels // 4,
                         out_channels=out_channels, stride=1)
        )
    return nn.Sequential(*blocks)


class DecoderBlock(nn.Module):

    def __init__(self, in_channels: int, out_channels: int, kernel_size: int = 3):
        super().__init__()
        padding = kernel_size // 2
        self.conv0 = Conv2D(in_channels, out_channels, kernel_size=kernel_size,
                            padding=padding, stride=1, is_seperable=True, has_relu=True)
        self.conv1 = Conv2D(out_channels, out_channels, kernel_size=kernel_size,
                            padding=padding, stride=1, is_seperable=True, has_relu=False)

    def forward(self, x):
        inp = x
        x = self.conv0(x)
        x = self.conv1(x)
        x = x + inp  # 相加后无 ReLU
        return x


class DecoderStage(nn.Module):

    def __init__(self, in_channels: int, skip_in_channels: int, out_channels: int):
        super().__init__()
        self.decode_conv = DecoderBlock(in_channels, in_channels, kernel_size=3)
        self.upsample = nn.ConvTranspose2d(in_channels, out_channels, kernel_size=2,
                                           stride=2, padding=0)
        self.proj_conv = Conv2D(skip_in_channels, out_channels, kernel_size=3, stride=1,
                                padding=1, is_seperable=True, has_relu=True)

    def forward(self, inputs):
        inp, skip = inputs
        x = self.decode_conv(inp)
        x = self.upsample(x)
        y = self.proj_conv(skip)
        return x + y


class Network(nn.Module):

    def __init__(self):
        super().__init__()
        self.conv0 = Conv2D(in_channels=4, out_channels=16, kernel_size=3, padding=1,
                            stride=1, is_seperable=False, has_relu=True)
        self.enc1 = EncoderStage(in_channels=16, out_channels=64, num_blocks=2)
        self.enc2 = EncoderStage(in_channels=64, out_channels=128, num_blocks=2)
        self.enc3 = EncoderStage(in_channels=128, out_channels=256, num_blocks=4)
        self.enc4 = EncoderStage(in_channels=256, out_channels=512, num_blocks=4)

        self.encdec = Conv2D(in_channels=512, out_channels=64, kernel_size=3, padding=1,
                             stride=1, is_seperable=True, has_relu=True)
        self.dec1 = DecoderStage(in_channels=64, skip_in_channels=256, out_channels=64)
        self.dec2 = DecoderStage(in_channels=64, skip_in_channels=128, out_channels=32)
        self.dec3 = DecoderStage(in_channels=32, skip_in_channels=64, out_channels=32)
        self.dec4 = DecoderStage(in_channels=32, skip_in_channels=16, out_channels=16)

        self.out0 = DecoderBlock(in_channels=16, out_channels=16, kernel_size=3)
        self.out1 = Conv2D(in_channels=16, out_channels=4, kernel_size=3, stride=1,
                           padding=1, is_seperable=False, has_relu=False)

    def forward(self, inp):
        conv0 = self.conv0(inp)
        conv1 = self.enc1(conv0)
        conv2 = self.enc2(conv1)
        conv3 = self.enc3(conv2)
        conv4 = self.enc4(conv3)

        conv5 = self.encdec(conv4)

        up3 = self.dec1((conv5, conv3))
        up2 = self.dec2((up3, conv2))
        up1 = self.dec3((up2, conv1))
        x = self.dec4((up1, conv0))

        x = self.out0(x)
        x = self.out1(x)
        return inp + x  # 残差学习


def load_official_weights(path=ONNX_PATH) -> nn.Module:
    """读 ONNX initializer → PyTorch state_dict（同名同形 1:1），返回 eval 模式模型。"""
    import onnx
    from onnx.numpy_helper import to_array

    graph = onnx.load(str(path), load_external_data=True).graph
    onnx_sd = OrderedDict(
        (init.name, torch.from_numpy(np.array(to_array(init), dtype=np.float32, copy=True)))
        for init in graph.initializer
    )

    model = Network()
    torch_sd = model.state_dict()

    print(f"[ONNX] {path}")
    print(f"[ONNX] initializer={len(onnx_sd)}  PyTorch state_dict keys={len(torch_sd)}")
    print(f"{'ONNX initializer':42s} {'shape':20s} | {'PyTorch key':42s} shape")
    print("-" * 112)
    for name, tensor in onnx_sd.items():
        pt = torch_sd.get(name)
        shape = str(list(tensor.shape))
        if pt is None:
            right, rshape = "<UNMAPPED>", "-"
        else:
            rshape = str(list(pt.shape))
            right = name if tuple(pt.shape) == tuple(tensor.shape) else "<SHAPE MISMATCH>"
        print(f"{name:42s} {shape:20s} | {right:42s} {rshape}")
    print("-" * 112)

    report = model.load_state_dict(onnx_sd, strict=False)
    print(f"missing_keys    ({len(report.missing_keys)}): {list(report.missing_keys)}")
    print(f"unexpected_keys ({len(report.unexpected_keys)}): {list(report.unexpected_keys)}")
    assert not report.missing_keys and not report.unexpected_keys, \
        "权重映射不完整：ONNX initializer 名与 PyTorch state_dict 键名不是 1:1"

    model.eval()
    return model


def main():
    net = load_official_weights()

    n_param = sum(p.numel() for p in net.parameters())
    n_conv = sum(1 for m in net.modules()
                 if isinstance(m, (nn.Conv2d, nn.ConvTranspose2d)))
    print(f"\n参数量: {n_param:,} ({n_param / 1e6:.3f} M)  "
          f"FP32 权重 ≈ {n_param * 4 / 2**20:.1f} MiB  "
          f"卷积层数(不含 Identity)={n_conv}")

    cases = []
    rng = np.random.default_rng(20261002)
    cases.append(("白噪声×0.5", rng.random((1, 4, TILE, TILE), dtype=np.float32) * 0.5))
    real = ONNX_PATH.parent / "eval_inputs.npy"  # (N,768,1024,4) NHWC packed Bayer
    if real.exists():
        img = np.load(real, mmap_mode="r")[0, :TILE, :TILE]
        cases.append(("真实 RAW tile", np.ascontiguousarray(img.transpose(2, 0, 1))[None]))

    import onnxruntime as ort

    sess = ort.InferenceSession(str(ONNX_PATH), providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name

    y_ref = None
    for tag, x in cases:
        ref = sess.run(None, {iname: x})[0]
        if y_ref is None:
            y_ref = ref
        with torch.no_grad():
            out = net(torch.from_numpy(x)).numpy()
        diff = np.abs(out - ref)
        rel_pt = diff / (np.abs(ref) + 1e-6)  # 逐点，近零像素会把绝对误差放大
        rel_l2 = np.linalg.norm(diff) / np.linalg.norm(ref)
        print(f"\n[等价性] {tag}: 输入{x.shape} |in|mean={np.abs(x).mean():.4f} "
              f"|out|mean={np.abs(ref).mean():.4f} |残差|mean={np.abs(ref - x).mean():.4f}")
        print(f"  max abs err = {diff.max():.3e}   mean abs err = {diff.mean():.3e}")
        print(f"  rel err: L2||Δ||/||ref|| = {rel_l2:.3e}   逐点 max = {rel_pt.max():.3e} "
              f"(p99.9={np.percentile(rel_pt, 99.9):.3e}, |ref|0.1%={np.percentile(np.abs(ref), 0.1):.1e} "
              f"→ 逐点 max 出现在被网络压到 ≈0 的像素上，属 FP32 噪声放大，非结构误差)")
        print(f"  结果: {'PASS' if diff.max() < 1e-3 else 'FAIL'} (阈值 1e-3)")
        assert diff.max() < 1e-3, "PyTorch 与 onnxruntime 不一致，检查结构/权重映射"

    if torch.backends.mps.is_available():
        import time

        x = cases[0][1]
        t = torch.from_numpy(x).to("mps")
        m = net.to("mps")
        with torch.no_grad():
            m(t)  # warmup
            torch.mps.synchronize()
            t0 = time.perf_counter()
            y = m(t)
            torch.mps.synchronize()
            dt = time.perf_counter() - t0
        print(f"\n[MPS] 推理 512×512: {dt * 1e3:.0f} ms，活跃张量 "
              f"{torch.mps.current_allocated_memory() / 2**20:.0f} MiB，"
              f"驱动分配 {torch.mps.driver_allocated_memory() / 2**20:.0f} MiB，"
              f"vs onnxruntime max abs err={np.abs(y.cpu().numpy() - y_ref).max():.3e}")

        # 训练一步(含梯度/Adam 状态)实测占用——决定 M1 上能开多大 tile/batch
        opt = torch.optim.Adam(m.parameters(), lr=1e-4)
        for step in range(2):  # 第 1 步含图构建开销，取第 2 步作稳态
            t0 = time.perf_counter()
            loss = torch.nn.functional.l1_loss(m(t), t)
            loss.backward()
            opt.step()
            opt.zero_grad(set_to_none=True)
            torch.mps.synchronize()
            dt_step = time.perf_counter() - t0
        print(f"[MPS] 训练一步 (fwd+bwd+Adam, batch=1, 稳态): {dt_step * 1e3:.0f} ms，"
              f"驱动分配 {torch.mps.driver_allocated_memory() / 2**20:.0f} MiB")
        net.to("cpu").eval()
    else:
        print("\n[MPS] 不可用")


if __name__ == "__main__":
    main()
