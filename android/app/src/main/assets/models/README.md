# Models

The trained models go here. They are produced in Phases 4 and 5 of the plan.

Expected files: `stage1.onnx`, `stage2.onnx` and a `models.json` like this:

```json
{
  "version": "yolo26n-1",
  "stage1": { "file": "stage1.onnx", "input": 416, "classes": ["motorcycle_unit"] },
  "stage2": { "file": "stage2.onnx", "input": 320, "classes": ["rider", "helmet", "no_helmet", "plate"] }
}
```

Until these files exist the app still runs, and the live and video screens say the models are missing.
