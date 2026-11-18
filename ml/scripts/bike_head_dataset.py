#!/usr/bin/env python3
"""Adds a dataset that boxes the motorbike and every head separately (bike, helmet, no helmet).

The Karachi traffic-camera dataset (Kaggle "Helmet-violation" by Uzair Ahmad, exported from the
Roboflow project helmet-vs-no-helmet, version 3, CC BY 4.0) boxes only the motorbike itself, and
each head on its own. HelmetGuard's stage 1 box covers the bike and everyone riding it, so each
bike box is joined with the heads just above it. That joined box is also the stage 2 crop, with
its heads as labels, the same way prepare_public_dataset.py builds crops from its dataset.

Roboflow squeezed every frame into a 512x512 square, so frames are stretched back to their real
shape first. Its own split puts neighbouring frames of the same few videos in train and in test,
which inflates scores, so the frames are split again here, by video.
"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

from PIL import Image, ImageOps

import prepare_public_dataset as prep

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
RAW_SPLITS = ("train", "valid", "test")

# A head rides a bike when it is above the bike's middle, no further sideways than this share of
# the bike's width, and no higher above the bike box than this share of its height (the torso).
HEAD_REACH_X = 0.75
HEAD_REACH_Y = 1.2
# A bike whose riders' heads weren't boxed still has a rider on it: about this much of its own
# height above the box, which is where the head was in this dataset's labelled examples.
RIDER_ABOVE_BIKE = 0.9


def kind_of(name: str) -> str | None:
    key = re.sub(r"[\s_\-]+", "", name.lower())
    return {
        "bike": "bike",
        "motorbike": "bike",
        "motorcycle": "bike",
        "helmet": "helmet",
        "withhelmet": "helmet",
        "nohelmet": "no_helmet",
        "withouthelmet": "no_helmet",
    }.get(key)


def find_dataset(search_roots: list[Path]) -> Path | None:
    """The folder whose data.yaml has a bike class and both head classes."""
    for root in search_roots:
        if not root.exists():
            continue
        for yaml_path in sorted(root.rglob("data.yaml")):
            try:
                kinds = {kind_of(n) for n in prep.read_class_names(yaml_path)}
            except (ValueError, OSError, SyntaxError):
                continue
            if {"bike", "helmet", "no_helmet"} <= kinds:
                return yaml_path.parent
    return None


def read_boxes(path: Path, names: list[str]) -> list[prep.Box]:
    if not path.exists():
        return []
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        kind = kind_of(names[int(float(parts[0]))])
        if kind is not None:
            boxes.append(prep.Box(kind, *(float(v) for v in parts[1:])))
    return boxes


def bike_of(head: prep.Box, bikes: list[prep.Box]) -> prep.Box | None:
    """The bike this head rides on: the closest one just below it, or None."""
    best, best_score = None, None
    for bike in bikes:
        gap = bike.top - head.bottom  # positive when the head is above the bike box
        if head.cy >= bike.cy or abs(head.cx - bike.cx) > HEAD_REACH_X * bike.w:
            continue
        if gap > HEAD_REACH_Y * bike.h or gap < -0.5 * bike.h:
            continue
        score = abs(gap) / bike.h + abs(head.cx - bike.cx) / bike.w
        if best_score is None or score < best_score:
            best, best_score = bike, score
    return best


def unit_of(bike: prep.Box, heads: list[prep.Box]) -> prep.Box:
    """The bike and everyone on it, as one box, kept inside the picture."""
    left, right, bottom = bike.left, bike.right, bike.bottom
    top = min([h.top for h in heads], default=bike.top - RIDER_ABOVE_BIKE * bike.h)
    for head in heads:
        left, right = min(left, head.left), max(right, head.right)
    left, top = max(0.0, left), max(0.0, top)
    right, bottom = min(1.0, right), min(1.0, bottom)
    return prep.Box("rider", (left + right) / 2, (top + bottom) / 2, right - left, bottom - top)


def padded(unit: prep.Box) -> prep.Box:
    """The area prepare_public_dataset.crop_rider() cuts out for this box."""
    return prep.Box(unit.kind, unit.cx, unit.cy, unit.w * (1 + 2 * prep.CROP_PADDING), unit.h * (1 + 2 * prep.CROP_PADDING))


def restore_shape(image: Image.Image, aspect: float | None) -> Image.Image:
    """Undoes Roboflow's stretch to a square. Labels are 0..1, so they stay right."""
    if not aspect or image.width != image.height:
        return image
    return image.resize((round(image.height * aspect), image.height), Image.BICUBIC)


def merge_unboxed(stage1: Path, unboxed: dict[str, list[list[float]]]) -> None:
    """Adds heads without a boxed bike to the list pseudo_labels.drop_uncovered() settles."""
    path = stage1 / prep.UNBOXED_FILE
    existing = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    existing.update(unboxed)
    path.write_text(json.dumps(existing), encoding="utf-8")


def add_bike_head_dataset(
    root: Path,
    stage1: Path,
    stage2: Path,
    prefix: str,
    splits_by_video: dict[str, str],
    aspect: float | None = 16 / 9,
    stage1_repeats: int = 1,
    stage2_repeats: int = 1,
) -> dict:
    """Writes stage 1 images and labels and stage 2 crops, named `<prefix>_...`.

    `splits_by_video` maps a video's key (prepare_public_dataset.group_key) to "val" or "test";
    every other video goes to train. Training pictures are written `*_repeats` times, so a small
    dataset still carries weight next to large ones.
    """
    names = prep.read_class_names(root / "data.yaml")
    if not {"bike", "helmet", "no_helmet"} <= {kind_of(n) for n in names}:
        raise ValueError(f"{root} has no bike/helmet/no helmet classes: {names}")
    for folder in (stage1, stage2):
        prep.make_dirs(folder)

    stats: Counter = Counter()
    unboxed: dict[str, list[list[float]]] = {}
    for raw_split in RAW_SPLITS:
        image_dir = root / raw_split / "images"
        if not image_dir.is_dir():
            continue
        for image_path in sorted(image_dir.iterdir()):
            if image_path.suffix.lower() not in IMAGE_SUFFIXES:
                continue
            key = prep.group_key(image_path.name)
            split = splits_by_video.get(key, "train")
            boxes = read_boxes(root / raw_split / "labels" / f"{image_path.stem}.txt", names)
            bikes = [b for b in boxes if b.kind == "bike"]
            heads = [b for b in boxes if b.kind in prep.STAGE2_NAMES]
            riders: dict[prep.Box, list[prep.Box]] = {bike: [] for bike in bikes}
            loose = []
            for head in heads:
                bike = bike_of(head, bikes)
                if bike is None:
                    loose.append(head)
                else:
                    riders[bike].append(head)
            units = [(unit_of(bike, own), own) for bike, own in riders.items()]

            stem = f"{prefix}_{image_path.stem}"
            try:
                with Image.open(image_path) as opened:
                    image = restore_shape(ImageOps.exif_transpose(opened).convert("RGB"), aspect)
            except OSError:
                stats["unreadable_images"] += 1
                continue
            copies = stage1_repeats if split == "train" else 1
            for copy in range(copies):
                name = stem if copy == 0 else f"{stem}_r{copy}"
                image.save(stage1 / "images" / split / f"{name}.jpg", quality=92)
                lines = [f"0 {u.cx:.6f} {u.cy:.6f} {u.w:.6f} {u.h:.6f}" for u, _ in units]
                (stage1 / "labels" / split / f"{name}.txt").write_text("\n".join(lines), encoding="utf-8")
                if loose:
                    unboxed[f"{name}.jpg"] = [[h.cx, h.cy, h.w, h.h] for h in loose]
            stats[f"{prefix}_stage1_images_{split}"] += 1
            stats[f"{prefix}_stage1_boxes_{split}"] += len(units)
            stats[f"{prefix}_bikes_without_a_boxed_head"] += sum(1 for _, own in units if not own)
            stats[f"{prefix}_heads_without_a_boxed_bike"] += len(loose)

            for index, (unit, _) in enumerate(units):
                # Every head the crop shows is labelled, not just this bike's, so the model is
                # never taught to ignore a neighbour's head that falls inside the picture.
                made = prep.crop_rider(image, unit, [h for h in heads if padded(unit).contains_center_of(h)])
                if made is None:
                    stats[f"{prefix}_stage2_crops_skipped"] += 1
                    continue
                crop, lines = made
                for copy in range(stage2_repeats if split == "train" else 1):
                    name = f"{stem}__{index}" if copy == 0 else f"{stem}__{index}_r{copy}"
                    crop.save(stage2 / "images" / split / f"{name}.jpg", quality=95)
                    (stage2 / "labels" / split / f"{name}.txt").write_text("\n".join(lines), encoding="utf-8")
                stats[f"{prefix}_stage2_crops_{split}"] += 1
                for line in lines:
                    stats[f"{prefix}_stage2_{prep.STAGE2_NAMES[int(line.split()[0])]}_{split}"] += 1

    if not any(k.startswith(f"{prefix}_stage1_images_") for k in stats):
        raise ValueError(f"No images found under {root}")
    if unboxed:
        merge_unboxed(stage1, unboxed)
    return dict(sorted(stats.items()))
