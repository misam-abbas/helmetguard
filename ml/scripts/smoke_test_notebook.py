#!/usr/bin/env python3
"""Runs the Kaggle notebook's own code on this PC, on a tiny fake dataset for one epoch.

It catches mistakes before a long GPU run. It needs torch and ultralytics,
internet (for a few HELMET frames and the model weights) and a few minutes:

    python ml/scripts/smoke_test_notebook.py

The fake dataset stands in for the Roboflow download, which needs a private key.
"""
import argparse
import io
import json
import os
import random
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ML = Path(__file__).resolve().parents[1]
NOTEBOOK = ML / "notebooks" / "train_yolov8_kaggle.ipynb"

# Small, fast settings. Everything else comes from the notebook.
OVERRIDES = """
STAGE1_EPOCHS = 1
STAGE2_EPOCHS = 1
STAGE1_BATCH = 4
STAGE2_BATCH = 8
WORKERS = 0
CACHE = False
DEVICE = "cpu"
LABEL_HELPER = "yolo11n.pt"
LABEL_HELPER_SIZE = 640
HELMET_EVERY_TRAIN = 50
HELMET_EVERY_EVAL = 50
HELMET_LIMIT_CLIPS = 3
"""


def fake_raw(root: Path) -> None:
    """A Roboflow-style export with the real dataset's class names and a few images per split."""
    sys.path.insert(0, str(ML / "scripts"))
    import prepare_public_dataset as prep
    from PIL import Image, ImageDraw

    root.mkdir(parents=True, exist_ok=True)
    (root / "data.yaml").write_text("names: ['helmet', 'licenseplate', 'motorcyclist', 'nohelmet']\nnc: 4\n", encoding="utf-8")
    rng = random.Random(1)
    wanted = {"train": 16, "val": 6, "test": 6}
    n = 0
    while any(wanted.values()):
        n += 1
        name = f"street{n}"
        split = prep.assign_split(prep.group_key(f"{name}.jpg"))
        if not wanted[split]:
            continue
        wanted[split] -= 1
        folder = root / {"train": "train", "val": "valid", "test": "test"}[split]
        (folder / "images").mkdir(parents=True, exist_ok=True)
        (folder / "labels").mkdir(parents=True, exist_ok=True)
        image = Image.new("RGB", (640, 640), tuple(rng.randrange(60, 200) for _ in range(3)))
        draw = ImageDraw.Draw(image)
        cx, cy, w, h = rng.uniform(0.3, 0.7), rng.uniform(0.4, 0.6), rng.uniform(0.15, 0.3), rng.uniform(0.3, 0.45)
        draw.rectangle([(cx - w / 2) * 640, (cy - h / 2) * 640, (cx + w / 2) * 640, (cy + h / 2) * 640], fill=(30, 30, 30))
        head = 0 if n % 2 else 3
        image.save(folder / "images" / f"{name}.jpg", quality=90)
        rows = [
            f"2 {cx:.4f} {cy:.4f} {w:.4f} {h:.4f}",
            f"{head} {cx:.4f} {cy - h / 2 + 0.05:.4f} {w / 3:.4f} {w / 3:.4f}",
            f"1 {cx:.4f} {cy + h / 3:.4f} {w / 4:.4f} {w / 8:.4f}",
        ]
        if n % 4 == 0:
            rows.append("3 0.9200 0.1500 0.0500 0.0500")  # a rider whose bike the dataset didn't box
        (folder / "labels" / f"{name}.txt").write_text("\n".join(rows), encoding="utf-8")


def fake_kaggle_dataset(root: Path) -> None:
    """Stands in for the Indian traffic dataset on Kaggle: same class names, a few pictures."""
    from PIL import Image

    folder = root / "violations"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "data.yaml").write_text("nc: 4\nnames: ['WithHelmet', 'WithoutHelmet', 'TripleRiding', 'Plate']\n", encoding="utf-8")
    for split, count in (("train", 6), ("valid", 2), ("test", 2)):
        (folder / split / "images").mkdir(parents=True, exist_ok=True)
        (folder / split / "labels").mkdir(parents=True, exist_ok=True)
        for n in range(count):
            Image.new("RGB", (640, 480), (120, 110 + n * 10, 100)).save(folder / split / "images" / f"road{n}.jpg")
            (folder / split / "labels" / f"road{n}.txt").write_text("2 0.5 0.55 0.3 0.5\n1 0.5 0.35 0.08 0.1", encoding="utf-8")


def run_notebook(work: Path) -> dict:
    os.environ.setdefault("MPLBACKEND", "Agg")
    notebook = json.loads(NOTEBOOK.read_text(encoding="utf-8"))
    os.chdir(work)
    sys.path.insert(0, str(work))
    namespace: dict = {"__name__": "__main__"}
    for index, item in enumerate(notebook["cells"]):
        if item["cell_type"] != "code":
            continue
        source = "".join(item["source"])
        if source.startswith("%pip") or "No GPU found" in source or "kaggle_secrets" in source or "DATASET_URL" in source:
            print(f"--- cell {index}: skipped (Kaggle only)")
            continue
        if source.startswith("%%writefile"):
            first, body = source.split("\n", 1)
            (work / first.split()[1]).write_text(body, encoding="utf-8")
            print(f"--- cell {index}: wrote {first.split()[1]}")
            continue
        setup = "SAVE_DIR = Path(" in source
        if setup:
            # Keep Kaggle's folders inside the test folder.
            source = source.replace('Path("/kaggle/working")', f'Path(r"{work / "working"}")')
            source = source.replace('Path("/kaggle/temp")', f'Path(r"{work / "temp"}")')
        started = time.time()
        print(f"--- cell {index}: running")
        exec(compile(source, f"cell-{index}", "exec"), namespace)
        if setup:
            exec(OVERRIDES, namespace)
            namespace["RAW"] = work / "raw"
            namespace["EXTRA_SEARCH"] = [work / "kaggle_input"]
            # The Roboflow download cell is skipped, so say here whether the fake export exists.
            namespace["ROBOFLOW"] = (work / "raw" / "data.yaml").exists()
        print(f"--- cell {index}: done in {time.time() - started:.0f}s")
    return namespace


def check_package(path: Path, expect_stage2: bool) -> None:
    import numpy as np
    import onnxruntime as ort

    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        manifest = json.loads(archive.read("models.json"))
        print("package:", len(names), "files; manifest:", manifest)
        assert ("stage2" in manifest) == expect_stage2, "Stage 2 should be trained exactly when Roboflow data is there"
        if expect_stage2:
            assert manifest["stage2"]["classes"] == ["helmet", "no_helmet"], "Stage 2 must not have a plate class"
        for stage in [s for s in ("stage1", "stage2") if s in manifest]:
            entry = manifest[stage]
            session = ort.InferenceSession(archive.read(entry["file"]), providers=["CPUExecutionProvider"])
            size = entry["input"]
            output = session.run(None, {session.get_inputs()[0].name: np.zeros((1, 3, size, size), np.float32)})[0]
            assert output.shape[1] == 4 + len(entry["classes"]), output.shape
            print(stage, "onnx output", output.shape)
        for prefix in ["helmet", "indian_traffic"] + (["original"] if expect_stage2 else []):
            assert any(n.startswith(f"eval_sample/{prefix}/images/") for n in names), f"No {prefix} evaluation sample"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep", type=Path, help="Run in this folder and keep it, instead of a temporary one")
    parser.add_argument("--no-roboflow", action="store_true", help="Test a run without the Roboflow dataset, as with no key")
    args = parser.parse_args()
    # On a Windows PC without the Visual C++ runtime installed, point this at a folder that has its DLLs.
    for folder in filter(None, os.environ.get("HELMETGUARD_DLL_DIRS", "").split(os.pathsep)):
        os.add_dll_directory(folder)
    if args.keep:
        args.keep.mkdir(parents=True, exist_ok=True)
        work = args.keep.resolve()
        holder = None
    else:
        holder = tempfile.TemporaryDirectory(prefix="hg_smoke_")
        work = Path(holder.name)
    if not args.no_roboflow:
        fake_raw(work / "raw")
    fake_kaggle_dataset(work / "kaggle_input")
    namespace = run_notebook(work)
    check_package(namespace["SAVE_DIR"] / "helmetguard_models.zip", expect_stage2=not args.no_roboflow)
    print(json.dumps(json.loads((namespace["SAVE_DIR"] / "label_stats.json").read_text()), indent=2))
    print("SMOKE TEST PASSED")
    if holder:
        os.chdir(ML)
        holder.cleanup()


if __name__ == "__main__":
    main()
