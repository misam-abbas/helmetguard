#!/usr/bin/env python3
"""Compares two model folders the way the app runs them, on labelled pictures and on videos.

Each folder holds models.json and its ONNX files, as in android/app/src/main/assets/models.
Only onnxruntime, numpy and Pillow are needed (plus imageio-ffmpeg for videos):

    python ml/scripts/compare_models.py --old OLD_DIR --new NEW_DIR --samples eval_sample [--video a.mp4 ...]

Pictures: every folder under --samples with images/ and labels/ is scored at the app's
motorcycle cut-off. Videos: about one frame a second is checked, and the number of
motorcycles each model finds is counted; a few side-by-side pictures are saved.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

APP_CUTOFF = 0.35  # stage1Confidence in the Android app and the website
IOU_MATCH = 0.5


class Stage1:
    def __init__(self, folder: Path):
        manifest = json.loads((folder / "models.json").read_text(encoding="utf-8"))
        entry = manifest["stage1"]
        self.name = manifest.get("version", folder.name)
        self.size = int(entry["input"])
        self.session = ort.InferenceSession(str(folder / entry["file"]), providers=["CPUExecutionProvider"])
        self.input = self.session.get_inputs()[0].name

    def detect(self, image: Image.Image, cutoff: float) -> list[tuple[float, float, float, float, float]]:
        """Boxes (x1, y1, x2, y2, score) in image pixels, letterboxed with black bars like the app."""
        width, height = image.size
        scale = min(self.size / width, self.size / height)
        new_w, new_h = round(width * scale), round(height * scale)
        pad_x, pad_y = (self.size - new_w) / 2, (self.size - new_h) / 2
        canvas = Image.new("RGB", (self.size, self.size))
        canvas.paste(image.resize((new_w, new_h), Image.BILINEAR), (int(pad_x), int(pad_y)))
        tensor = (np.asarray(canvas, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]
        output = self.session.run(None, {self.input: tensor})[0][0]  # (4 + classes, candidates)
        scores = output[4:].max(axis=0)
        keep = scores >= cutoff
        cx, cy, w, h = output[:4, keep]
        boxes = np.stack([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], axis=1)
        boxes[:, [0, 2]] = (boxes[:, [0, 2]] - int(pad_x)) / scale
        boxes[:, [1, 3]] = (boxes[:, [1, 3]] - int(pad_y)) / scale
        return nms([(*b, s) for b, s in zip(boxes.tolist(), scores[keep].tolist())])


def iou(a, b) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def nms(boxes, threshold: float = 0.5):
    kept = []
    for box in sorted(boxes, key=lambda b: -b[4]):
        if all(iou(box, k) <= threshold for k in kept):
            kept.append(box)
    return kept


def read_labels(path: Path, width: int, height: int):
    if not path.exists():
        return []
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 5:
            cx, cy, w, h = (float(v) for v in parts[1:])
            boxes.append(((cx - w / 2) * width, (cy - h / 2) * height, (cx + w / 2) * width, (cy + h / 2) * height))
    return boxes


def score_folder(model: Stage1, folder: Path) -> dict:
    """Precision and recall at the app's cut-off, and AP50 over all cut-offs."""
    all_detections, total_truth, tp_app, fp_app = [], 0, 0, 0
    for image_path in sorted((folder / "images").iterdir()):
        image = Image.open(image_path).convert("RGB")
        truth = read_labels(folder / "labels" / f"{image_path.stem}.txt", *image.size)
        total_truth += len(truth)
        found = model.detect(image, 0.01)
        used = set()
        for box in sorted(found, key=lambda b: -b[4]):
            best, best_iou = None, IOU_MATCH
            for index, t in enumerate(truth):
                overlap = iou(box, t)
                if index not in used and overlap >= best_iou:
                    best, best_iou = index, overlap
            hit = best is not None
            if hit:
                used.add(best)
            all_detections.append((box[4], hit))
            if box[4] >= APP_CUTOFF:
                tp_app += hit
                fp_app += not hit
    all_detections.sort(key=lambda d: -d[0])
    tp = np.cumsum([d[1] for d in all_detections])
    fp = np.cumsum([not d[1] for d in all_detections])
    recall = tp / max(total_truth, 1)
    precision = tp / np.maximum(tp + fp, 1)
    envelope = np.maximum.accumulate(precision[::-1])[::-1] if len(precision) else np.array([0.0])
    ap50 = float(np.sum(np.diff(np.concatenate([[0.0], recall])) * envelope)) if len(recall) else 0.0
    return {
        "images": len(list((folder / "images").iterdir())),
        "bikes": total_truth,
        "precision": round(tp_app / max(tp_app + fp_app, 1), 3),
        "recall": round(tp_app / max(total_truth, 1), 3),
        "AP50": round(ap50, 3),
    }


def video_frames(path: Path, every_s: float = 1.0, max_frames: int = 90):
    import imageio_ffmpeg

    reader = imageio_ffmpeg.read_frames(str(path), output_params=["-vf", f"fps=1/{every_s}"])
    meta = next(reader)
    width, height = meta["size"]
    for index, raw in enumerate(reader):
        if index >= max_frames:
            break
        yield index * every_s, Image.frombytes("RGB", (width, height), raw)


def compare_video(old: Stage1, new: Stage1, path: Path, out: Path) -> dict:
    counts = {"old": 0, "new": 0, "frames": 0, "frames_new_found_more": 0, "frames_old_found_more": 0}
    saved = 0
    for second, frame in video_frames(path):
        a = old.detect(frame, APP_CUTOFF)
        b = new.detect(frame, APP_CUTOFF)
        counts["frames"] += 1
        counts["old"] += len(a)
        counts["new"] += len(b)
        counts["frames_new_found_more"] += len(b) > len(a)
        counts["frames_old_found_more"] += len(a) > len(b)
        if saved < 3 and len(b) != len(a):
            pair = Image.new("RGB", (frame.width * 2, frame.height))
            for offset, boxes, label in ((0, a, old.name), (frame.width, b, new.name)):
                panel = frame.copy()
                draw = ImageDraw.Draw(panel)
                for x1, y1, x2, y2, s in boxes:
                    draw.rectangle([x1, y1, x2, y2], outline=(255, 40, 40), width=max(2, frame.width // 300))
                draw.text((10, 10), f"{label}: {len(boxes)} bikes", fill=(255, 255, 0))
                pair.paste(panel, (offset, 0))
            pair.thumbnail((1920, 1080))
            pair.save(out / f"{path.stem}_{int(second):03d}s.jpg", quality=85)
            saved += 1
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old", type=Path, required=True)
    parser.add_argument("--new", type=Path, required=True)
    parser.add_argument("--samples", type=Path, help="Folder of image/label sets, such as eval_sample from the training zip")
    parser.add_argument("--video", type=Path, nargs="*", default=[])
    parser.add_argument("--out", type=Path, default=Path("model_comparison"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    old, new = Stage1(args.old), Stage1(args.new)
    report = {"old": f"{old.name} at {old.size}px", "new": f"{new.name} at {new.size}px", "cutoff": APP_CUTOFF}
    if args.samples:
        for folder in sorted(p for p in args.samples.iterdir() if (p / "images").is_dir()):
            report[f"pictures_{folder.name}"] = {"old": score_folder(old, folder), "new": score_folder(new, folder)}
            print(folder.name, json.dumps(report[f"pictures_{folder.name}"]))
    for video in args.video:
        report[f"video_{video.name}"] = compare_video(old, new, video, args.out)
        print(video.name, json.dumps(report[f"video_{video.name}"]))
    (args.out / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
