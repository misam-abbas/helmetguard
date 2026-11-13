#!/usr/bin/env python3
"""Generates ml/notebooks/train_yolov8_colab.ipynb and train_yolov8_kaggle.ipynb.

Each notebook carries its own copies of the scripts in this folder, so a single
uploaded file is all the platform needs. Edit this script, not the notebooks.
Check a change locally before spending GPU time with:

    python ml/scripts/smoke_test_notebook.py
"""
import json
from pathlib import Path

ML = Path(__file__).resolve().parents[1]
SCRIPTS = ML / "scripts"
MODULES = ["prepare_public_dataset.py", "helmet_dataset.py", "extra_datasets.py", "pseudo_labels.py"]

DATA_NOTE = """**What this run uses**

- *HELMET* by Siebert and Lin (2020), CC BY 4.0, https://osf.io/4pwj8/: 910 clips of road traffic in Myanmar with every nearby motorcycle boxed. About one frame per second is used.
- *Trafffic_Violations_Triple_Riding_No_Helmet_Plate_* by DevguruCodes on Kaggle, ODbL: Indian traffic pictures. Add it under **Add Input** if this notebook can't fetch it by itself.
- *helmet-detection-and-license-plate-recognition* by on Gia Capital, CC BY 4.0, via Roboflow Universe, **only if a Roboflow key is set up** (see below). It has the head labels, so without it stage 2 isn't retrained and the app keeps its current head model.
- A large COCO-trained YOLO11x only labels ridden motorcycles that the datasets left without a box. It is not shipped.

Number plates are never a training label, so the models can't detect them.

**Accuracy warning.** Neither dataset was filmed in Pakistan, so expect real results on your roads to be somewhat worse than these scores. Your own footage is still the best way to improve the model.
"""

COLAB_INTRO = """# Train HelmetGuard's models (version 2)

Trains the two detectors the Android app and the website need, and packages them.

**To run it**
1. Check **Runtime → Change runtime type** says **T4 GPU**.
2. Choose **Runtime → Run all**.
3. Allow Google Drive access when asked.
4. Paste your free [Roboflow](https://app.roboflow.com) **Private API key** when asked. It is under Settings → API Keys, and it is not saved in this notebook.

Training takes several hours. Progress is saved to Google Drive after every epoch, so if Colab disconnects, just choose **Run all** again and it carries on.

""" + DATA_NOTE

KAGGLE_INTRO = """# Train HelmetGuard's models on Kaggle (version 2)

Trains the two detectors the Android app and the website need, and packages them.

**Set up, once, in this editor**
1. Under **Settings → Accelerator**, choose **GPU T4 x2**.
2. Under **Settings**, check that internet is on.
3. Optional, to retrain stage 2 as well: under **Add-ons → Secrets**, add a secret with the label `ROBOFLOW_API_KEY` and your free [Roboflow](https://app.roboflow.com) **Private API key** as the value, then tick it so this notebook can use it.

**To run it**

Click **Save Version**, choose **Save & Run All**, then **Save**. It runs in the background, so you can close the browser. Kaggle allows 12 hours and this run should take about 5.

**When it finishes**

Open the saved version, look at the sample pictures, then download **helmetguard_models.zip** from its **Output** section.

""" + DATA_NOTE


def gpu_check(help_text):
    return f"""import torch

if not torch.cuda.is_available():
    raise RuntimeError("No GPU found. {help_text}")
print("GPU:", torch.cuda.get_device_name(0))
"""


COLAB_GPU = gpu_check("Choose Runtime > Change runtime type > T4 GPU, then Run all again.")
KAGGLE_GPU = gpu_check("Choose Settings > Accelerator > GPU T4 x2, then Save & Run All again.")

INSTALL = """%pip install -q ultralytics==8.4.208 onnx==1.23.2 onnxruntime==1.31.1 onnxslim==0.1.99
"""

TRAINING_SETTINGS = """VERSION = "yolo11n-v2"
STAGE1_MODEL = "yolo11n.pt"
STAGE2_MODEL = "yolo11n.pt"
STAGE1_SIZE = 640  # was 416: small and distant bikes need the extra pixels
STAGE2_SIZE = 320
STAGE1_EPOCHS = 150
STAGE2_EPOCHS = 100
PATIENCE = 30
STAGE1_BATCH = 32
STAGE2_BATCH = 64
WORKERS = 4
CACHE = "ram"  # Ultralytics checks free memory first and skips the cache if it won't fit
DEVICE = 0
SEED = 42
STAGE2_NAMES = ["helmet", "no_helmet"]

# Fills in boxes for ridden bikes the datasets didn't label.
LABEL_HELPER = "yolo11x.pt"
LABEL_HELPER_SIZE = 1280

# HELMET: one frame every N frames (10 fps video). None means every clip.
HELMET_EVERY_TRAIN = 10
HELMET_EVERY_EVAL = 20
HELMET_LIMIT_CLIPS = None

# Where Kaggle puts datasets added under Add Input.
EXTRA_SEARCH = [Path("/kaggle/input")]
"""

COLAB_SETUP = """from pathlib import Path

from google.colab import drive

drive.mount("/content/drive")

""" + TRAINING_SETTINGS + """
SAVE_DIR = Path("/content/drive/MyDrive/helmetguard_v2")
RUNS = SAVE_DIR / "runs"
RUNS.mkdir(parents=True, exist_ok=True)
ZIP = SAVE_DIR / "dataset_yolov8.zip"
RAW = Path("/content/raw")
PREPARED = Path("/content/prepared")
VAL_DIR = Path("/content/val")
print("Saving progress to", SAVE_DIR)
"""

KAGGLE_SETUP = """import os
from pathlib import Path

from ultralytics import settings

# Kaggle has logging tools installed. Left on, one could stop a background run to ask for a login.
os.environ["WANDB_MODE"] = "disabled"
settings.update({"wandb": False, "mlflow": False, "comet": False, "clearml": False})

""" + TRAINING_SETTINGS + """
# Kaggle keeps everything in /kaggle/working as the run's output, so the images stay out of it.
SAVE_DIR = Path("/kaggle/working")
TEMP = Path("/kaggle/temp")
RUNS = SAVE_DIR / "runs"
RUNS.mkdir(parents=True, exist_ok=True)
TEMP.mkdir(parents=True, exist_ok=True)
ZIP = TEMP / "dataset_yolov8.zip"
RAW = TEMP / "raw"
PREPARED = TEMP / "prepared"
VAL_DIR = TEMP / "val"
print("Saving results to", SAVE_DIR)
"""

COLAB_API_KEY = """import getpass


def read_api_key():
    # Optional: press Enter without a key to train stage 1 only.
    return getpass.getpass("Paste your Roboflow Private API key, or just press Enter to skip it: ").strip() or None
"""

KAGGLE_API_KEY = """from kaggle_secrets import UserSecretsClient


def read_api_key():
    # Optional. Without it, the Roboflow dataset is left out and stage 2 isn't retrained.
    try:
        return UserSecretsClient().get_secret("ROBOFLOW_API_KEY").strip() or None
    except Exception:
        return None
"""

DOWNLOAD = """import time
import zipfile

import requests

DATASET_URL = "https://api.roboflow.com/on-gia-capital/helmet-detection-and-license-plate-recognition/1/yolov8"


def export_link(api_key):
    # Roboflow answers 202 while it prepares the export. Errors never print the URL, which holds the key.
    for _ in range(120):
        try:
            response = requests.get(DATASET_URL, params={"api_key": api_key}, timeout=60)
        except requests.RequestException as error:
            raise RuntimeError(f"Couldn't reach Roboflow ({type(error).__name__}). Check that this notebook has internet access, then run it again.") from None
        if response.status_code == 200:
            return response.json()["export"]["link"]
        if response.status_code != 202:
            raise RuntimeError(f"Roboflow refused the request (HTTP {response.status_code}). Check your Private API key, then Run all again.")
        print("Roboflow is preparing the download...")
        time.sleep(10)
    raise RuntimeError("Roboflow took too long to prepare the download. Run all again in a few minutes.")


if not (RAW / "data.yaml").exists() and not ZIP.exists():
    key = read_api_key()
    if key:
        link = export_link(key)
        del key
        part = ZIP.with_suffix(".part")
        with requests.get(link, stream=True, timeout=120) as response:
            response.raise_for_status()
            with open(part, "wb") as file:
                for chunk in response.iter_content(chunk_size=1 << 20):
                    file.write(chunk)
        part.rename(ZIP)

if not (RAW / "data.yaml").exists() and ZIP.exists():
    RAW.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(ZIP) as archive:
        archive.extractall(RAW)

ROBOFLOW = (RAW / "data.yaml").exists()
if ROBOFLOW:
    print("Roboflow images:", sum(1 for _ in RAW.glob("*/images/*")))
else:
    print("No Roboflow key, so its dataset is left out and stage 2 won't be retrained.")
"""


def writefile(name):
    return f"%%writefile {name}\n" + (SCRIPTS / name).read_text(encoding="utf-8")


RUN_PREPARE = """import json
import shutil

import prepare_public_dataset

if PREPARED.exists():
    shutil.rmtree(PREPARED)
STAGE1 = PREPARED / "stage1"
if ROBOFLOW:
    # Images where a rider's bike has no box stay in for now; after the missing boxes are
    # filled in below, each is kept only if every such rider is inside a box.
    stats = prepare_public_dataset.prepare(RAW, PREPARED, keep_unboxed=True)
    print(json.dumps(stats, indent=2))
    waiting = stats.get("stage1_unboxed_bike_images_kept_for_now", 0)
    print(f"{waiting} images ({waiting / stats['images_total']:.0%}) have a bike without a box; the box filling below decides about them.")
    STAGE2_YAML = stats["stage2_yaml"]
else:
    prepare_public_dataset.make_dirs(STAGE1)
    stats = {"skipped": "no Roboflow key: stage 2 is not retrained"}
    STAGE2_YAML = None
STAGE1_YAML = str(prepare_public_dataset.write_yaml(STAGE1, ["motorcycle_unit"]))
LABEL_STATS = {"prepare": stats}


def subset(name, keep, label_root=STAGE1):
    \"\"\"A test-only dataset with the stage 1 test images that `keep` accepts, for separate scores.\"\"\"
    root = PREPARED / name
    for kind in ("images", "labels"):
        (root / kind / "test").mkdir(parents=True, exist_ok=True)
    count = 0
    for image in sorted((STAGE1 / "images" / "test").iterdir()):
        if not keep(image.name):
            continue
        shutil.copy2(image, root / "images" / "test" / image.name)
        label = label_root / "labels" / "test" / f"{image.stem}.txt"
        if label.exists():
            shutil.copy2(label, root / "labels" / "test" / label.name)
        count += 1
    lines = [f"path: {root.resolve().as_posix()}", "train: images/test", "val: images/test", "test: images/test", "names:", "  0: motorcycle_unit"]
    (root / "data.yaml").write_text("\\n".join(lines) + "\\n", encoding="utf-8")
    return str(root / "data.yaml"), count


ORIGINAL_TEST_YAML = None
if ROBOFLOW:
    # The first model was scored on these images with these labels (mAP50 0.698), so keep an untouched copy.
    FIRST_RULE = set((STAGE1 / prepare_public_dataset.FIRST_RULE_FILE).read_text(encoding="utf-8").splitlines())
    ORIGINAL_TEST_YAML, count = subset("stage1_original_test", lambda name: name in FIRST_RULE)
    print("Test images the first model was scored on:", count)
"""

RUN_EXTRA = """import extra_datasets

EXTRA = "devgurucodes/trafffic-violations-triple-riding-no-helmet-plate"
try:
    root = extra_datasets.find_dataset(EXTRA_SEARCH, "TripleRiding")
    if root is None:
        import kagglehub

        root = extra_datasets.find_dataset([Path(kagglehub.dataset_download(EXTRA))], "TripleRiding")
    if root is None:
        raise FileNotFoundError(f"{EXTRA} has no data.yaml with a TripleRiding class")
    LABEL_STATS["devguru"] = extra_datasets.add_yolo_dataset(root, STAGE1, "dg", {"TripleRiding"})
    print(json.dumps(LABEL_STATS["devguru"], indent=2))
except Exception as error:
    LABEL_STATS["devguru"] = {"error": f"{type(error).__name__}: {error}"}
    print("WARNING: the Indian traffic pictures could not be added, so this run goes on without them.", error)
"""

RUN_HELMET = """import helmet_dataset

try:
    LABEL_STATS["helmet"] = helmet_dataset.add_helmet_frames(
        STAGE1, every_train=HELMET_EVERY_TRAIN, every_eval=HELMET_EVERY_EVAL, long_side=1280, limit_clips=HELMET_LIMIT_CLIPS
    )
    print(json.dumps(LABEL_STATS["helmet"], indent=2))
except Exception as error:
    # The original dataset still trains a better model than before, so carry on, but say so loudly.
    LABEL_STATS["helmet"] = {"error": f"{type(error).__name__}: {error}"}
    print("WARNING: the HELMET frames could not be added, so this run uses the original dataset only.", error)
"""

RUN_PSEUDO = """import gc

import torch

import pseudo_labels

pseudo_stats, ADDED_BOXES = pseudo_labels.complete_labels(STAGE1, weights=LABEL_HELPER, imgsz=LABEL_HELPER_SIZE, device=DEVICE)
LABEL_STATS["pseudo_labels"] = pseudo_stats
print(json.dumps(pseudo_stats, indent=2))

unboxed_file = STAGE1 / prepare_public_dataset.UNBOXED_FILE
if unboxed_file.exists():
    LABEL_STATS["unboxed_bikes"] = pseudo_labels.drop_uncovered(STAGE1, json.loads(unboxed_file.read_text(encoding="utf-8")))
    print("Images with an unboxed bike:", json.dumps(LABEL_STATS["unboxed_bikes"]))
    ADDED_BOXES = {path: boxes for path, boxes in ADDED_BOXES.items() if Path(path).exists()}
gc.collect()
if torch.cuda.is_available():
    torch.cuda.empty_cache()

SOURCE_TESTS = {}
for prefix, label in (("helmet_", "helmet"), ("dg_", "indian_traffic")):
    yaml_path, count = subset(f"stage1_{label}_test", lambda name, p=prefix: name.startswith(p))
    print(label, "test images:", count)
    if count:
        SOURCE_TESTS[label] = yaml_path
if not any((STAGE1 / "images" / "train").iterdir()):
    raise RuntimeError("No training pictures at all: every dataset failed to load. See the warnings above.")
(SAVE_DIR / "label_stats.json").write_text(json.dumps(LABEL_STATS, indent=2))
"""

SAMPLES = """import random

import matplotlib.pyplot as plt
from PIL import Image, ImageDraw


def show(paths, title):
    figure, axes = plt.subplots(2, 3, figsize=(15, 8))
    for axis in axes.flat:
        axis.axis("off")
    for axis, path in zip(axes.flat, paths):
        image = Image.open(path).convert("RGB")
        draw = ImageDraw.Draw(image)
        label = STAGE1 / "labels" / Path(path).parent.name / f"{Path(path).stem}.txt"
        added = ADDED_BOXES.get(str(path), [])
        for box in pseudo_labels.read_yolo(label, image.width, image.height):
            is_added = any(max(abs(a - b) for a, b in zip(box, other)) < 2 for other in added)
            draw.rectangle(box, outline="yellow" if is_added else "red", width=max(2, image.width // 300))
        axis.imshow(image)
    figure.suptitle(title)
    plt.show()


rng = random.Random(SEED)
changed = sorted(ADDED_BOXES)
show(rng.sample(changed, min(6, len(changed))), "Red: the dataset's own boxes. Yellow: boxes added for bikes it missed")
for prefix, title in (("helmet_", "HELMET frames from Myanmar"), ("dg_", "Indian traffic pictures")):
    frames = sorted((STAGE1 / "images" / "train").glob(f"{prefix}*"))
    show(rng.sample(frames, min(6, len(frames))), f"{title}, with all their boxes")
"""

TRAIN_HELPER = """from ultralytics import YOLO


def train(name, data, imgsz, model, epochs, batch):
    run = RUNS / name
    done = run / "done.txt"
    last = run / "weights" / "last.pt"
    if done.exists():
        print(name, "is already trained.")
        return
    if last.exists():
        print("Carrying on from the last saved epoch of", name)
        try:
            YOLO(str(last)).train(resume=True)
        except AssertionError as error:
            # Ultralytics refuses to resume a run that had already finished.
            print(error)
    else:
        YOLO(model).train(
            data=data,
            imgsz=imgsz,
            epochs=epochs,
            patience=PATIENCE,
            batch=batch,
            device=DEVICE,
            workers=WORKERS,
            cache=CACHE,
            seed=SEED,
            deterministic=True,
            project=str(RUNS),
            name=name,
            exist_ok=True,
        )
    done.write_text("finished")
"""

TRAIN1 = """train("stage1", STAGE1_YAML, STAGE1_SIZE, STAGE1_MODEL, STAGE1_EPOCHS, STAGE1_BATCH)
"""

TRAIN2 = """if STAGE2_YAML:
    train("stage2", STAGE2_YAML, STAGE2_SIZE, STAGE2_MODEL, STAGE2_EPOCHS, STAGE2_BATCH)
else:
    print("Stage 2 is not retrained in this run: it needs the Roboflow dataset.")
"""

EVALUATE = """def evaluate(name, weights, data, imgsz):
    if not data:
        return None
    metrics = YOLO(str(RUNS / weights / "weights" / "best.pt")).val(data=data, split="test", imgsz=imgsz, device=DEVICE, project=str(VAL_DIR), name=name, exist_ok=True)
    rows = {}
    for index, class_id in enumerate(metrics.box.ap_class_index):
        precision, recall, map50, map50_95 = metrics.box.class_result(index)
        rows[metrics.names[int(class_id)]] = {
            "precision": round(float(precision), 3),
            "recall": round(float(recall), 3),
            "mAP50": round(float(map50), 3),
            "mAP50_95": round(float(map50_95), 3),
        }
    return rows


# Every test clip and video is one neither model saw during training.
results = {"stage1": evaluate("stage1", "stage1", STAGE1_YAML, STAGE1_SIZE)}
if ORIGINAL_TEST_YAML:
    results["stage1_original_test_original_labels"] = evaluate("stage1_original", "stage1", ORIGINAL_TEST_YAML, STAGE1_SIZE)
for label, yaml_path in SOURCE_TESTS.items():
    results[f"stage1_{label}_test"] = evaluate(f"stage1_{label}", "stage1", yaml_path, STAGE1_SIZE)
if STAGE2_YAML:
    results["stage2"] = evaluate("stage2", "stage2", STAGE2_YAML, STAGE2_SIZE)
print(json.dumps(results, indent=2))


def score(group, cls, key):
    return ((results.get(group) or {}).get(cls) or {}).get(key)


def target(label, value):
    print(f"Plan target, {label} >= 0.85:", "not measured" if value is None else ("met" if value >= 0.85 else "NOT met"), value)


print("Same test images as the first model (it scored mAP50 0.698):", score("stage1_original_test_original_labels", "motorcycle_unit", "mAP50"))
target("motorcycle mAP50", score("stage1", "motorcycle_unit", "mAP50"))
target("no_helmet precision", score("stage2", "no_helmet", "precision"))
target("no_helmet recall", score("stage2", "no_helmet", "recall"))
(SAVE_DIR / "metrics.json").write_text(json.dumps(results, indent=2))
"""

EXPORT = """import numpy as np
import onnxruntime as ort
from PIL import Image


def check_onnx_format(path, imgsz, class_count):
    # These are exactly the assumptions the app's and website's parsers make.
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    blank = np.random.default_rng(SEED).random((1, 3, imgsz, imgsz), dtype=np.float32)
    output = session.run(None, {session.get_inputs()[0].name: blank})[0]
    assert output.ndim == 3 and output.shape[1] == 4 + class_count, f"Unexpected output shape {output.shape}"
    scores = output[0, 4:]
    assert scores.min() >= 0 and scores.max() <= 1, "Class scores are not 0..1"
    assert output[0, :4].max() > 1.5, "Boxes are not in pixels"
    return output.shape


def letterbox(path, imgsz):
    # Black bars, centred, like the app and the website.
    image = Image.open(path).convert("RGB")
    scale = min(imgsz / image.width, imgsz / image.height)
    resized = image.resize((round(image.width * scale), round(image.height * scale)), Image.BILINEAR)
    canvas = Image.new("RGB", (imgsz, imgsz))
    canvas.paste(resized, ((imgsz - resized.width) // 2, (imgsz - resized.height) // 2))
    return (np.asarray(canvas, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None].copy()


def raw_output(result):
    # The PyTorch model returns its raw boxes alongside other tensors.
    if isinstance(result, dict):
        result = result.get("one2many", next(iter(result.values())))
    while isinstance(result, (list, tuple)):
        result = result[0]
    return result.detach().cpu().numpy()


def check_onnx_matches(best, onnx_path, images, imgsz):
    # The plan's check: on the same input, the exported model must give the same output as the trained one.
    # Raw outputs are compared, so a box scored right at a cut-off can't make a good export look bad.
    import torch

    model = YOLO(str(best)).model.float().eval()
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    score_gap = box_gap = 0.0
    for image in images:
        tensor = letterbox(image, imgsz)
        with torch.no_grad():
            expected = raw_output(model(torch.from_numpy(tensor)))
        got = session.run(None, {session.get_inputs()[0].name: tensor})[0]
        assert expected.shape == got.shape, f"Shapes differ: {expected.shape} vs {got.shape}"
        score_gap = max(score_gap, float(np.abs(expected[:, 4:] - got[:, 4:]).max()))
        box_gap = max(box_gap, float(np.abs(expected[:, :4] - got[:, :4]).max()))
    print(f"{best.parent.parent.name}: largest difference on {len(images)} test images: score {score_gap:.5f}, box {box_gap:.3f} px")
    assert score_gap < 0.01 and box_gap < 1.0, "The exported model disagrees with the trained model"


def export(name, imgsz, class_count, data_root):
    best = RUNS / name / "weights" / "best.pt"
    onnx_path = Path(YOLO(str(best)).export(format="onnx", imgsz=imgsz, simplify=True, nms=False, device="cpu"))
    print(name, "output shape", check_onnx_format(onnx_path, imgsz, class_count))
    check_onnx_matches(best, onnx_path, sorted((data_root / "images" / "test").iterdir())[:20], imgsz)
    return onnx_path


stage1_onnx = export("stage1", STAGE1_SIZE, 1, STAGE1)
stage2_onnx = export("stage2", STAGE2_SIZE, len(STAGE2_NAMES), PREPARED / "stage2") if STAGE2_YAML else None
"""

PACKAGE = """import io
import json
import zipfile

from PIL import Image

manifest = {
    "version": VERSION,
    "stage1": {"file": "stage1.onnx", "input": STAGE1_SIZE, "classes": ["motorcycle_unit"]},
}
# Without the Roboflow dataset there is no new stage 2; the app keeps its current one.
if stage2_onnx:
    manifest["stage2"] = {"file": "stage2.onnx", "input": STAGE2_SIZE, "classes": STAGE2_NAMES}


def add_eval_sample(archive, dataset, prefix, count):
    # A few test images with labels, so the new model can be compared with the old one on a PC.
    images = sorted((dataset / "images" / "test").iterdir())
    for image in images[:: max(1, len(images) // count)][:count]:
        picture = Image.open(image).convert("RGB")
        picture.thumbnail((1280, 1280))
        buffer = io.BytesIO()
        picture.save(buffer, "JPEG", quality=90)
        archive.writestr(f"eval_sample/{prefix}/images/{image.stem}.jpg", buffer.getvalue())
        label = dataset / "labels" / "test" / f"{image.stem}.txt"
        archive.writestr(f"eval_sample/{prefix}/labels/{image.stem}.txt", label.read_text() if label.exists() else "")


output = SAVE_DIR / "helmetguard_models.zip"
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.write(stage1_onnx, "stage1.onnx")
    if stage2_onnx:
        archive.write(stage2_onnx, "stage2.onnx")
    archive.writestr("models.json", json.dumps(manifest, indent=2))
    archive.writestr("metrics.json", json.dumps(results, indent=2))
    archive.writestr("label_stats.json", json.dumps(LABEL_STATS, indent=2))
    if ORIGINAL_TEST_YAML:
        add_eval_sample(archive, PREPARED / "stage1_original_test", "original", 80)
    for label in SOURCE_TESTS:
        add_eval_sample(archive, PREPARED / f"stage1_{label}_test", label, 80)
print(f"{output.stat().st_size / 1e6:.1f} MB")
"""

COLAB_PACKAGE = "from google.colab import files\n\n" + PACKAGE + """print("Saved to Google Drive: MyDrive/helmetguard_v2/helmetguard_models.zip")
files.download(str(output))
"""

KAGGLE_PACKAGE = PACKAGE + """print("Done. Download helmetguard_models.zip from the Output section of this saved version.")
"""

COLAB_DONE = """## Finished

Your browser downloads **helmetguard_models.zip**, and a copy is in Google Drive under `helmetguard_v2/`. Keep it in your Downloads folder and tell Claude it's ready, so it can be installed in the app and the website.
"""

KAGGLE_DONE = """## Finished

Download **helmetguard_models.zip** from the **Output** section of this saved version. Keep it in your Downloads folder and tell Claude it's ready, so it can be installed in the app and the website.
"""


def cell(kind, text):
    lines = text.splitlines(keepends=True)
    if kind == "markdown":
        return {"cell_type": "markdown", "metadata": {}, "source": lines}
    return {"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": lines}


def write_notebook(filename, intro, opening, setup, api_key, package, done, platform_metadata):
    cells = [
        cell("markdown", intro),
        cell("markdown", "## 1. Set up\n"),
        *[cell("code", text) for text in opening],
        cell("code", setup),
        cell("markdown", "## 2. Get and prepare the data\n"),
        cell("code", api_key),
        cell("code", DOWNLOAD),
        *[cell("code", writefile(name)) for name in MODULES],
        cell("code", RUN_PREPARE),
        cell("markdown", "### Add frames from the HELMET dataset\n"),
        cell("code", RUN_HELMET),
        cell("markdown", "### Add Indian traffic pictures\n"),
        cell("code", RUN_EXTRA),
        cell("markdown", "### Box the ridden bikes the datasets missed\n"),
        cell("code", RUN_PSEUDO),
        cell("code", SAMPLES),
        cell("markdown", "## 3. Train\n\nStage 1 finds motorcycles. Stage 2 looks inside each one for heads with and without helmets.\n"),
        cell("code", TRAIN_HELPER),
        cell("code", TRAIN1),
        cell("code", TRAIN2),
        cell("markdown", "## 4. Measure on unseen videos\n"),
        cell("code", EVALUATE),
        cell("markdown", "## 5. Export for the phone and the website, and check it\n"),
        cell("code", EXPORT),
        cell("code", package),
        cell("markdown", done),
    ]
    for index, item in enumerate(cells):
        item["id"] = f"cell-{index}"

    notebook = {
        "cells": cells,
        "metadata": {
            **platform_metadata,
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python"},
        },
        "nbformat": 4,
        "nbformat_minor": 5,
    }
    target = ML / "notebooks" / filename
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(notebook, indent=1) + "\n", encoding="utf-8")
    print("Wrote", target)


if __name__ == "__main__":
    write_notebook(
        "train_yolov8_colab.ipynb",
        COLAB_INTRO,
        [COLAB_GPU, INSTALL],
        COLAB_SETUP,
        COLAB_API_KEY,
        COLAB_PACKAGE,
        COLAB_DONE,
        {"accelerator": "GPU", "colab": {"gpuType": "T4", "name": "train_yolov8_colab.ipynb"}},
    )
    # Install before torch loads here, in case pip changes a package torch depends on.
    write_notebook(
        "train_yolov8_kaggle.ipynb",
        KAGGLE_INTRO,
        [INSTALL, KAGGLE_GPU],
        KAGGLE_SETUP,
        KAGGLE_API_KEY,
        KAGGLE_PACKAGE,
        KAGGLE_DONE,
        {},
    )
