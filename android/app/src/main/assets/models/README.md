# Models

The trained models live here, and the website copies them from here when it builds. `ml/README.md` explains how they are trained.

Expected files: `stage1.onnx`, `stage2.onnx` and a `models.json` like this:

```json
{
  "version": "yolo11n-v2",
  "stage1": { "file": "stage1.onnx", "input": 640, "classes": ["motorcycle_unit"] },
  "stage2": { "file": "stage2.onnx", "input": 320, "classes": ["helmet", "no_helmet"] }
}
```

Both models must output raw YOLO boxes, `[1, 4 + classes, candidates]`, or end-to-end boxes, `[1, N, 6]`. Number plates are not detected: a `plate` class in an older model is ignored.

Until these files exist the app still runs, and the live and video screens say the models are missing.
