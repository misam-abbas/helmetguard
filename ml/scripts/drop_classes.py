#!/usr/bin/env python3
"""Cuts classes off the end of a raw YOLO ONNX model, so it can no longer report them.

Used to remove the number-plate class from the first stage 2 model without retraining:
the output [1, 4 + 3, N] (helmet, no_helmet, plate) becomes [1, 4 + 2, N].

    python ml/scripts/drop_classes.py IN.onnx OUT.onnx --keep 2
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper


def drop_classes(source: Path, target: Path, keep: int) -> None:
    model = onnx.load(str(source))
    graph = model.graph
    output = graph.output[0]
    dims = [d.dim_value for d in output.type.tensor_type.shape.dim]
    if len(dims) != 3 or dims[1] <= 4 + keep:
        raise ValueError(f"Expected a raw YOLO output with more than {keep} classes, got {dims}")

    full = f"{output.name}_all_classes"
    for node in graph.node:
        node.output[:] = [full if name == output.name else name for name in node.output]
    constants = {"drop_starts": [0], "drop_ends": [4 + keep], "drop_axes": [1]}
    for name, values in constants.items():
        graph.initializer.append(helper.make_tensor(name, TensorProto.INT64, [1], values))
    graph.node.append(helper.make_node("Slice", [full, "drop_starts", "drop_ends", "drop_axes"], [output.name], name="drop_classes"))
    output.type.tensor_type.shape.dim[1].dim_value = 4 + keep
    onnx.checker.check_model(model)
    onnx.save(model, str(target))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--keep", type=int, required=True, help="How many classes to keep, from the first")
    args = parser.parse_args()
    drop_classes(args.source, args.target, args.keep)

    import onnxruntime as ort

    before = ort.InferenceSession(str(args.source), providers=["CPUExecutionProvider"])
    after = ort.InferenceSession(str(args.target), providers=["CPUExecutionProvider"])
    size = before.get_inputs()[0].shape[2]
    sample = np.random.default_rng(0).random((1, 3, size, size), dtype=np.float32)
    a = before.run(None, {before.get_inputs()[0].name: sample})[0]
    b = after.run(None, {after.get_inputs()[0].name: sample})[0]
    assert np.array_equal(a[:, : 4 + args.keep], b), "The kept outputs changed"
    print(f"{args.source.name} {a.shape} -> {args.target.name} {b.shape}, kept outputs identical")


if __name__ == "__main__":
    main()
