#!/usr/bin/env python3
"""PMRID 官方权重 → TFLite FP16 转换 + 数值守卫。

链路:torch(net_torch.py + torch_pretrained.ckp,pickle state_dict)
  → ONNX(opset 17,固定 [1,4,512,512])
  → TF saved_model(onnx2tf)
  → TFLite FP16(tf.lite converter,权重/激活 FP16)
守卫:CPU Interpreter(FP16 模型)vs onnxruntime(FP32)同输入对比,
      相对误差超阈值即失败退出——转换链静默走样会让 spike 数据全错。

用法: python3 tools/nn/convert_pmrid.py [--pmrid-repo /tmp/pmrid] [--out tools/nn/out]
"""
import argparse
import pickle
import subprocess
import sys
from pathlib import Path

import numpy as np

TILE = 512
ERR_MAX = 0.05  # FP16 + 转换链的相对误差上限(0.01 级别为正常,0.05 已宽松)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pmrid-repo", default="/tmp/pmrid")
    ap.add_argument("--out", default="tools/nn/out")
    ap.add_argument("--onnx", default=None,
                    help="已有 ONNX（如微调导出的 pmrid_finetuned.onnx），跳过第 1 步；"
                         "同名 .onnx.data 须在同目录")
    args = ap.parse_args()
    repo = Path(args.pmrid_repo)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    # 1) torch → onnx
    if args.onnx:
        onnx_path = Path(args.onnx)
        if not onnx_path.exists():
            sys.exit(f"ONNX 不存在: {onnx_path}")
        print(f"[1/4] 跳过 torch→onnx，直接用 {onnx_path} "
              f"({onnx_path.stat().st_size/1e6:.1f}MB)")
    else:
        sys.path.insert(0, str(repo / "models"))
        import torch
        from net_torch import Network

        net = Network()
        # ckp 是 torch.save 产物(persistent id 存 storage),pickle.load 会报
        # "persistent id instruction";torch.load 的 weights_only=True 正好吃这种文件。
        states = torch.load(repo / "models" / "torch_pretrained.ckp", map_location="cpu", weights_only=True)
        net.load_state_dict(states)
        net.eval()
        dummy = torch.randn(1, 4, TILE, TILE)
        onnx_path = out / "pmrid_512.onnx"
        torch.onnx.export(
            net, dummy, str(onnx_path), opset_version=17,
            input_names=["input"], output_names=["output"],
            do_constant_folding=True,
        )
        print(f"[1/4] onnx 导出完成: {onnx_path} ({onnx_path.stat().st_size/1e6:.1f}MB)")

    # 2) onnx → tf saved_model(onnx2tf)
    sm_dir = out / ("saved_model" if onnx_path.stem == "pmrid_512"
                    else f"{onnx_path.stem}_saved_model")
    cmd = ["onnx2tf", "-i", str(onnx_path), "-o", str(sm_dir)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0 or not (sm_dir / "saved_model.pb").exists():
        print(res.stdout[-3000:])
        print(res.stderr[-3000:])
        sys.exit("onnx2tf 失败")
    print(f"[2/4] saved_model 完成: {sm_dir}")

    # 3) saved_model → tflite fp16
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_saved_model(str(sm_dir))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    fp16 = converter.convert()
    fp16_path = out / f"{onnx_path.stem}_fp16.tflite"
    fp16_path.write_bytes(fp16)
    print(f"[3/4] tflite fp16 完成: {fp16_path} ({len(fp16)/1e6:.1f}MB)")

    # 4) 数值守卫:CPU Interpreter(FP16) vs onnxruntime(FP32)
    rng = np.random.default_rng(2026)
    x = rng.random((1, 4, TILE, TILE), dtype=np.float32) * 0.3

    interp = tf.lite.Interpreter(model_path=str(fp16_path))
    interp.allocate_tensors()
    idet = interp.get_input_details()[0]
    # onnx2tf 输出的 tflite 是 NHWC(TF 惯例);PyTorch/ONNX 侧是 NCHW
    assert list(idet["shape"]) == [1, TILE, TILE, 4], idet["shape"]
    interp.set_tensor(idet["index"], x.transpose(0, 2, 3, 1))
    interp.invoke()
    odet = interp.get_output_details()[0]
    y_tfl = interp.get_tensor(odet["index"]).transpose(0, 3, 1, 2)

    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name
    y_onnx = sess.run(None, {iname: x})[0]

    diff = np.abs(y_tfl - y_onnx)
    rel = diff / (np.abs(y_onnx) + 1e-3)
    print(f"[4/4] 数值守卫: |Δ|max={diff.max():.5f} 相对误差 mean={rel.mean():.5f} p99={np.percentile(rel,99):.5f}")
    if rel.mean() > ERR_MAX:
        sys.exit(f"数值守卫失败: 相对误差均值 {rel.mean():.5f} > {ERR_MAX}")
    print("守卫通过。把以下文件拷入 App assets:")
    print(f"  cp {fp16_path} app/src/main/assets/{fp16_path.name}")


if __name__ == "__main__":
    main()
