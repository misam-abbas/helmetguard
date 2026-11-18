# Training the HelmetGuard models

## Models from public data

`notebooks/train_yolov8_kaggle.ipynb` trains both models on Kaggle's free GPUs, and `notebooks/train_yolov8_colab.ipynb` does the same in Google Colab. Both need a free Roboflow account. Instructions are at the top of each notebook. (The file names are kept from the first version; the second version trains YOLO11n.)

| | First version (`yolov8n-public-1`) | Second version (`yolo11n-v2`) |
|---|---|---|
| Model | YOLOv8n | YOLO11n |
| Stage 1 input | 416 px | 640 px, so small and distant bikes keep enough pixels |
| Stage 1 data | One Roboflow dataset | The same, plus HELMET frames from Myanmar, with missing bike boxes filled in |
| Stage 2 classes | `helmet`, `no_helmet`, `plate` | `helmet`, `no_helmet`. Plates are not detected |
| Stage 1 data, extra | | HELMET frames from Myanmar and Indian traffic pictures, with missing bike boxes filled in (`DATASETS.md`) |
| Stage 1 test mAP50, original test pictures and labels | 0.698 | 0.628. These labels leave most bikes out, and nearly all of the new model's extra boxes are such bikes, which this score counts as mistakes |
| Stage 1 test mAP50, all test pictures with completed labels | | 0.933 (Myanmar 0.953, Indian traffic 0.923) |
| Labelled bikes found at the app's cut-off (0.35), 80 test pictures each: original / Myanmar / Indian traffic | 70% / 8% / 33% | 100% / 92% / 88% |
| Sample videos: frames with a bike found, front view / street / side view / kick scooters (should be none) | 0 of 14 / 5 of 10 / 23 of 23 / none | 14 of 14 / 10 of 10 / 23 of 23 / none |
| Stage 2 test mAP50, `helmet` / `no_helmet` | 0.969 / 0.915 | 0.943 / 0.933 |
| File size in the app | 12.7 MB each | 5.4 MB each (weights stored at half precision) |

Known weakness of the second version: it sometimes boxes a bicycle and its rider as a motorcycle (5 of its 31 extra boxes on the Indian traffic sample). An alert still needs a bare head in several frames (5 in the app, 4 on the website), and a person reviews every alert.

### Third version (`yolo11n-v3`): Karachi traffic cameras

The third version fine-tunes the second version's models (fetched from version 4 of the Kaggle notebook) on the same data plus 422 frames from Karachi traffic cameras with every bike and head boxed (`DATASETS.md`, `scripts/bike_head_dataset.py`). It is the first time either model sees Pakistani riders' heads. Two of the dataset's videos are kept back, and the notebook scores both the old and the new models on them, so `metrics.json` in the zip has before and after numbers on identical pictures, with confusion matrices and precision-recall curves under `plots/`.

Results of version 6 of the notebook (4.7 hours on Kaggle), second version → third version on the same test pictures:

| Test | Precision | Recall | mAP50 | mAP50-95 |
|---|---|---|---|---|
| Stage 1, all test pictures | 0.879 → 0.882 | 0.854 → 0.868 | 0.934 → 0.934 | 0.773 → 0.775 |
| Stage 1, Karachi test videos | 0.983 → 0.997 | 0.917 → 0.984 | 0.985 → 0.995 | 0.639 → 0.769 |
| Stage 2 `no_helmet`, all test crops | 0.891 → 0.896 | 0.708 → 0.848 | 0.784 → 0.915 | 0.470 → 0.563 |
| Stage 2 `helmet`, all test crops | 0.620 → 0.726 | 0.626 → 0.897 | 0.603 → 0.896 | 0.247 → 0.506 |
| Stage 2 `no_helmet`, Karachi test crops | 0.451 → 0.877 | 0.179 → 0.764 | 0.201 → 0.898 | 0.046 → 0.489 |
| Stage 2 `helmet`, Karachi test crops | 0.329 → 0.644 | 0.588 → 0.897 | 0.358 → 0.873 | 0.075 → 0.475 |

Stage 1 per source, third version: Myanmar mAP50 0.955, Indian traffic 0.921, Karachi 0.995. On the original Roboflow test pictures with their incomplete labels it scores 0.603 (second version 0.628). At the app's cut-off (0.35), on 80 test pictures per source, `compare_models.py` found the third version equal or better everywhere (Karachi recall 0.845 → 0.992, Indian traffic precision 0.726 → 0.781), but on two Pakistani street videos filmed on phones it boxed 178 bikes against 191: it skips some riders cut off at the edge of the picture and some very distant bikes. The second version barely recognised Karachi riders' heads (bare-head recall 0.18); that, not finding bikes, is what kept alerts from being raised on Pakistani roads.

The notebooks are generated, so edit `scripts/build_notebook.py` and run it rather than changing the `.ipynb` files. Before spending GPU time, check a change with the unit tests and a one-epoch run of the notebook's own code on a tiny fake dataset (needs `torch` and `ultralytics`, and internet for a few HELMET frames):

```powershell
python -m unittest discover -s ml/tests
python ml/scripts/smoke_test_notebook.py
```

After a run:

1. Compare the new models with the ones in the app, on the test pictures that come in the zip and on any videos:

   ```powershell
   python ml/scripts/compare_models.py --old android/app/src/main/assets/models --new path\to\unzipped --samples path\to\unzipped\eval_sample --video clip.mp4
   ```

2. Store the weights at half precision, which halves each file and leaves the results unchanged (the script checks):

   ```powershell
   python ml/scripts/half_weights.py path\to\unzipped\stage1.onnx android\app\src\main\assets\models\stage1.onnx
   ```

   Do the same for `stage2.onnx`, and copy `models.json`.

3. Commit, then point the website at the new files with `node web/scripts/lock-models.mjs`, commit that too, push, and deploy (see `docs/architecture.md`).

`scripts/drop_classes.py` cuts unwanted classes off the end of a model without retraining; it removed the plate class from the first stage 2 model.

## Final model, from your own footage

Your plan's main training data is your own footage from Pakistani roads. Before recording, get your supervisor's or ethics board's answer on where you may film (decision 6 in the plan).

## What the app and the website expect

Files in `android/app/src/main/assets/models/`. The website copies them when it builds.

| File | Input | Classes |
|---|---|---|
| `stage1.onnx` | Square full frame, size from `models.json` | `motorcycle_unit` |
| `stage2.onnx` | Square motorcycle crop with 8% margin, size from `models.json` | `helmet`, `no_helmet`, and `rider` if the data has it. A `plate` class, if present, is ignored |
| `models.json` | | Lists the above. See the README in that folder |

Datasets and training checkpoints never go into Git. The exported ONNX models do, because the app and the website are built from them. Each dataset's licence is recorded in `DATASETS.md`.
