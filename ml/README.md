# Training the HelmetGuard models

## Models from public data

`notebooks/train_yolov8_kaggle.ipynb` trains both models on Kaggle's free GPUs, and `notebooks/train_yolov8_colab.ipynb` does the same in Google Colab. Both need a free Roboflow account. Instructions are at the top of each notebook. (The file names are kept from the first version; the second version trains YOLO11n.)

| | First version (`yolov8n-public-1`) | Second version (`yolo11n-v2`) |
|---|---|---|
| Model | YOLOv8n | YOLO11n |
| Stage 1 input | 416 px | 640 px, so small and distant bikes keep enough pixels |
| Stage 1 data | One Roboflow dataset | The same, plus HELMET frames from Myanmar, with missing bike boxes filled in |
| Stage 2 classes | `helmet`, `no_helmet`, `plate` | `helmet`, `no_helmet`. Plates are not detected |
| Stage 1 test mAP50 | 0.698 | Reported in the run's `metrics.json`, on the same test images too |

The notebooks are generated, so edit `scripts/build_notebook.py` and run it rather than changing the `.ipynb` files. Before spending GPU time, check a change with the unit tests and a one-epoch run of the notebook's own code on a tiny fake dataset (needs `torch` and `ultralytics`, and internet for a few HELMET frames):

```powershell
python -m unittest discover -s ml/tests
python ml/scripts/smoke_test_notebook.py
```

After a run, compare the new models with the ones in the app, on the test pictures that come in the zip and on any videos:

```powershell
python ml/scripts/compare_models.py --old android/app/src/main/assets/models --new path\to\unzipped --samples path\to\unzipped\eval_sample --video clip.mp4
```

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
