#!/usr/bin/env python3
"""Adds boxes for ridden motorcycles that the datasets left unlabelled.

Neither public dataset boxes every motorcycle: bikes far away, or partly outside
the picture, are often left out. Trained on that, the model learns to ignore
exactly those bikes. A large detector trained on COCO looks at every image here,
and where it sees a motorcycle with a rider that has no box, the union of the
bike and its riders becomes a new `motorcycle_unit` box. Parked bikes with
nobody on them stay unlabelled, as in the original data.

The rules below are plain functions so they can be tested without a GPU.
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path

PERSON = 0
MOTORCYCLE = 3
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

# A bike is only added when the detector is fairly sure about it.
MOTO_CONF = 0.40
PERSON_CONF = 0.30
# A detected bike counts as already labelled when half of it lies inside an existing box.
COVERED_SHARE = 0.5
# New boxes smaller than this share of the image's long side are left out.
MIN_HEIGHT_SHARE = 0.015
MIN_WIDTH_SHARE = 0.008

Box = tuple[float, float, float, float]  # x1, y1, x2, y2 in pixels


def area(box: Box) -> float:
    return max(0.0, box[2] - box[0]) * max(0.0, box[3] - box[1])


def intersection(a: Box, b: Box) -> float:
    return area((max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])))


def iou(a: Box, b: Box) -> float:
    shared = intersection(a, b)
    union = area(a) + area(b) - shared
    return shared / union if union > 0 else 0.0


def is_rider(person: Box, moto: Box) -> bool:
    """A person sitting on the bike, not someone walking past it."""
    mw, mh = moto[2] - moto[0], moto[3] - moto[1]
    ph = person[3] - person[1]
    if mw <= 0 or mh <= 0 or ph <= 0 or area(person) <= 0:
        return False
    if intersection(person, moto) / area(person) < 0.10:
        return False
    centre_x = (person[0] + person[2]) / 2
    if not moto[0] - 0.15 * mw <= centre_x <= moto[2] + 0.15 * mw:
        return False
    # The rider's body reaches into the bike and the head sits above or near its top.
    if person[3] < moto[1] + 0.25 * mh or person[1] > moto[1] + 0.35 * mh:
        return False
    return 0.3 * mh <= ph <= 2.5 * mh


def unit_box(moto: Box, persons: list[Box]) -> Box | None:
    riders = [p for p in persons if is_rider(p, moto)]
    if not riders:
        return None
    boxes = [moto, *riders]
    return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))


def new_units(existing: list[Box], motos: list[tuple[Box, float]], persons: list[Box], width: int, height: int) -> list[Box]:
    """Boxes to add for ridden bikes that no existing box covers."""
    long_side = max(width, height)
    added: list[Box] = []
    for moto, _ in sorted(motos, key=lambda item: -item[1]):
        if any(intersection(moto, box) / max(area(moto), 1e-6) >= COVERED_SHARE for box in existing):
            continue
        unit = unit_box(moto, persons)
        if unit is None:
            continue
        unit = (max(0.0, unit[0]), max(0.0, unit[1]), min(float(width), unit[2]), min(float(height), unit[3]))
        if unit[3] - unit[1] < MIN_HEIGHT_SHARE * long_side or unit[2] - unit[0] < MIN_WIDTH_SHARE * long_side:
            continue
        if any(iou(unit, box) >= 0.3 for box in existing + added):
            continue
        added.append(unit)
    return added


def read_yolo(path: Path, width: int, height: int) -> list[Box]:
    if not path.exists():
        return []
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        cx, cy, w, h = (float(v) for v in parts[1:])
        boxes.append(((cx - w / 2) * width, (cy - h / 2) * height, (cx + w / 2) * width, (cy + h / 2) * height))
    return boxes


def yolo_line(box: Box, width: int, height: int) -> str:
    x1, y1, x2, y2 = box
    return f"0 {(x1 + x2) / 2 / width:.6f} {(y1 + y2) / 2 / height:.6f} {(x2 - x1) / width:.6f} {(y2 - y1) / height:.6f}"


def drop_uncovered(stage1: Path, unboxed: dict[str, list[list[float]]]) -> dict:
    """Keeps an image whose dataset left a rider's bike unboxed only if the boxes now cover every such head.

    `unboxed` maps image names to the heads (cx, cy, w, h, 0..1) that had no bike box.
    """
    stats: Counter = Counter()
    for name, heads in unboxed.items():
        found = [(split, stage1 / "images" / split / name) for split in ("train", "val", "test")]
        found = [(split, path) for split, path in found if path.exists()]
        if not found:
            continue
        split, image = found[0]
        label = stage1 / "labels" / split / f"{Path(name).stem}.txt"
        boxes = []
        if label.exists():
            for line in label.read_text(encoding="utf-8").splitlines():
                parts = line.split()
                if len(parts) == 5:
                    boxes.append(tuple(float(v) for v in parts[1:]))
        covered = all(any(abs(hx - bx) <= bw / 2 and abs(hy - by) <= bh / 2 for bx, by, bw, bh in boxes) for hx, hy, *_ in heads)
        if covered:
            stats[f"recovered_{split}"] += 1
        else:
            image.unlink()
            label.unlink(missing_ok=True)
            stats[f"dropped_{split}"] += 1
    return dict(sorted(stats.items()))


def complete_labels(stage1: Path, splits=("train", "val", "test"), weights: str = "yolo11x.pt", imgsz: int = 1280,
                    device=0, batch: int = 8, log=print) -> tuple[dict, dict[str, list[Box]]]:
    """Adds pseudo boxes to stage1/labels/<split>. Returns stats and the added boxes per image path."""
    from ultralytics import YOLO

    model = YOLO(weights)
    stats: Counter = Counter()
    added_by_image: dict[str, list[Box]] = {}
    for split in splits:
        images = sorted(p for p in (stage1 / "images" / split).iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
        log(f"Checking {len(images)} {split} images for unlabelled bikes")
        for start in range(0, len(images), 256):
            chunk = images[start : start + 256]
            results = model.predict([str(p) for p in chunk], imgsz=imgsz, conf=min(MOTO_CONF, PERSON_CONF),
                                    classes=[PERSON, MOTORCYCLE], device=device, batch=batch, verbose=False,
                                    stream=True, half=str(device) != "cpu")
            for image_path, result in zip(chunk, results):
                height, width = result.orig_shape
                boxes = result.boxes
                xyxy = boxes.xyxy.cpu().numpy().tolist()
                classes = boxes.cls.cpu().numpy().astype(int).tolist()
                scores = boxes.conf.cpu().numpy().tolist()
                motos = [(tuple(b), s) for b, c, s in zip(xyxy, classes, scores) if c == MOTORCYCLE and s >= MOTO_CONF]
                persons = [tuple(b) for b, c, s in zip(xyxy, classes, scores) if c == PERSON and s >= PERSON_CONF]
                label_path = stage1 / "labels" / split / f"{image_path.stem}.txt"
                existing = read_yolo(label_path, width, height)
                extra = new_units(existing, motos, persons, width, height)
                source = "helmet" if image_path.name.startswith("helmet_") else "original"
                stats[f"{source}_{split}_images"] += 1
                stats[f"{source}_{split}_boxes_before"] += len(existing)
                if extra:
                    lines = [line for line in label_path.read_text(encoding="utf-8").splitlines() if line.strip()] if label_path.exists() else []
                    lines += [yolo_line(box, width, height) for box in extra]
                    label_path.write_text("\n".join(lines), encoding="utf-8")
                    stats[f"{source}_{split}_boxes_added"] += len(extra)
                    stats[f"{source}_{split}_images_changed"] += 1
                    added_by_image[str(image_path)] = extra
    return dict(sorted(stats.items())), added_by_image
