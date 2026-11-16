#!/usr/bin/env python3
"""Adds the pictures of a YOLO-format dataset from Kaggle to the stage 1 training set.

Such datasets rarely have a box for every motorcycle with its riders, so only the
classes that do mean exactly that are kept (for example `TripleRiding`), and
pseudo_labels.py fills in the rest. The dataset's own train/valid/test split is kept.
"""
from __future__ import annotations

import re
import shutil
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
SPLITS = {"train": "train", "valid": "val", "val": "val", "test": "test"}


def key(name: str) -> str:
    return re.sub(r"[^a-z]", "", name.lower())


def read_names(yaml_path: Path) -> list[str]:
    """Class names from a data.yaml in list or mapping form."""
    text = yaml_path.read_text(encoding="utf-8")
    inline = re.search(r"^names:\s*\[(.*?)\]", text, re.M | re.S)
    if inline:
        return [n.strip().strip("'\"") for n in inline.group(1).split(",") if n.strip()]
    names = []
    block = re.search(r"^names:\s*\n((?:[ \t]+.*\n?)+)", text, re.M)
    for line in (block.group(1) if block else "").splitlines():
        match = re.match(r"\s+(?:-\s*|\d+:\s*)(.+)$", line)
        if match:
            names.append(match.group(1).strip().strip("'\""))
    if not names:
        raise ValueError(f"No class names in {yaml_path}")
    return names


def find_dataset(search_roots: list[Path], marker: str) -> Path | None:
    """The folder holding a data.yaml whose class names include `marker`."""
    for root in search_roots:
        if not root.exists():
            continue
        for yaml_path in sorted(root.rglob("data.yaml")):
            try:
                if key(marker) in {key(n) for n in read_names(yaml_path)}:
                    return yaml_path.parent
            except (ValueError, OSError):
                continue
    return None


def add_yolo_dataset(root: Path, stage1: Path, prefix: str, unit_classes: set[str], max_long_side: int = 1280,
                     workers: int = 16) -> dict:
    """Copies images into stage1/images/<split>, keeping only boxes of the unit classes as class 0."""
    names = read_names(root / "data.yaml")
    keep = {index for index, name in enumerate(names) if key(name) in {key(c) for c in unit_classes}}

    def copy_one(job):
        image_path, folder, split = job
        stem = f"{prefix}_{image_path.stem}"
        target = stage1 / "images" / split / f"{stem}.jpg"
        try:
            with Image.open(image_path) as opened:
                if image_path.suffix.lower() in {".jpg", ".jpeg"} and max(opened.size) <= max_long_side:
                    shutil.copy2(image_path, target)
                else:
                    image = opened.convert("RGB")
                    image.thumbnail((max_long_side, max_long_side))
                    image.save(target, quality=92)
        except OSError:
            return split, None
        lines = []
        label = folder / "labels" / f"{image_path.stem}.txt"
        if label.exists():
            for row in label.read_text(encoding="utf-8").splitlines():
                parts = row.split()
                if len(parts) == 5 and int(float(parts[0])) in keep:
                    lines.append("0 " + " ".join(parts[1:]))
        (stage1 / "labels" / split / f"{stem}.txt").write_text("\n".join(lines), encoding="utf-8")
        return split, len(lines)

    jobs = []
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        split = SPLITS.get(folder.name.lower())
        if split is None or not (folder / "images").is_dir():
            continue
        (stage1 / "images" / split).mkdir(parents=True, exist_ok=True)
        (stage1 / "labels" / split).mkdir(parents=True, exist_ok=True)
        jobs += [(p, folder, split) for p in sorted((folder / "images").iterdir()) if p.suffix.lower() in IMAGE_SUFFIXES]

    stats: Counter = Counter()
    # Kaggle's dataset folders are slow per file, so many files are read at once.
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for split, box_count in pool.map(copy_one, jobs):
            if box_count is None:
                stats["unreadable_images"] += 1
                continue
            stats[f"{prefix}_images_{split}"] += 1
            stats[f"{prefix}_boxes_{split}"] += box_count
    if not any(k.startswith(f"{prefix}_images_") for k in stats):
        raise ValueError(f"No images found under {root}")
    return dict(sorted(stats.items()))
