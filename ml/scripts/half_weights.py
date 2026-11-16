#!/usr/bin/env python3
"""Stores an ONNX model's weights at half precision, so the file is about half the size.

The network still computes in full precision: each weight goes through a Cast back to
float32, which ONNX Runtime folds away when it loads the model. It works on any CPU and
in the browser, unlike a true float16 model. The script checks the outputs barely move.

    python ml/scripts/half_weights.py IN.onnx OUT.onnx
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

MIN_ELEMENTS = 64  # tiny tensors (scales, shapes) aren't worth it


def half_weights(source: Path, target: Path) -> tuple[int, int]:
    model = onnx.load(str(source))
    graph = model.graph
    graph_inputs = {i.name for i in graph.input}
    casts, halves, converted = [], [], 0
    for init in list(graph.initializer):
        if init.data_type != TensorProto.FLOAT or init.name in graph_inputs:
            continue
        values = numpy_helper.to_array(init)
        if values.size < MIN_ELEMENTS or np.abs(values).max() > 60000:
            continue
        half = numpy_helper.from_array(values.astype(np.float16), name=f"{init.name}__f16")
        halves.append(half)
        casts.append(helper.make_node("Cast", [half.name], [init.name], to=TensorProto.FLOAT, name=f"{init.name}__to_f32"))
        graph.initializer.remove(init)
        converted += 1
    graph.initializer.extend(halves)
    for node in reversed(casts):
        graph.node.insert(0, node)
    onnx.checker.check_model(model)
    onnx.save(model, str(target))
    return converted, len(graph.initializer)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    converted, _ = half_weights(args.source, args.target)

    import onnxruntime as ort

    full = ort.InferenceSession(str(args.source), providers=["CPUExecutionProvider"])
    half = ort.InferenceSession(str(args.target), providers=["CPUExecutionProvider"])
    size = full.get_inputs()[0].shape[2]
    rng = np.random.default_rng(0)
    worst_score = worst_box = 0.0
    for _ in range(3):
        sample = rng.random((1, 3, size, size), dtype=np.float32)
        a = full.run(None, {full.get_inputs()[0].name: sample})[0]
        b = half.run(None, {half.get_inputs()[0].name: sample})[0]
        worst_box = max(worst_box, float(np.abs(a[:, :4] - b[:, :4]).max()))
        worst_score = max(worst_score, float(np.abs(a[:, 4:] - b[:, 4:]).max()))
    before, after = args.source.stat().st_size, args.target.stat().st_size
    print(f"{args.source.name}: {converted} weight tensors halved, {before / 1e6:.1f} MB -> {after / 1e6:.1f} MB; "
          f"largest change: score {worst_score:.4f}, box {worst_box:.2f} px")
    assert worst_score < 0.01 and worst_box < 2.0, "Half-precision weights changed the outputs too much"


if __name__ == "__main__":
    main()
