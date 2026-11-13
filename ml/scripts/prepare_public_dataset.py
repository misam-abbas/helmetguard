#!/usr/bin/env python3
"""Turn a Roboflow YOLOv8 export into HelmetGuard's two training sets.

Stage 1 finds whole motorcycles in the road scene. Stage 2 looks inside one
motorcycle crop for bare heads and helmets. In the public dataset the "Rider"
box already wraps the bike and everyone on it, so it becomes the stage 1 label
and the crop for stage 2.

The dataset also boxes number plates. HelmetGuard doesn't detect plates, so they
never become a training label; they are only used to notice a bike that the
dataset forgot to box.

The original train/valid/test split is thrown away. Frames from one video look
almost identical, so a random split would inflate the scores; here every frame
of a video stays together in one split.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import shutil
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageOps

RAW_SPLITS = ("train", "valid", "test")
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
CROP_PADDING = 0.08
MIN_CROP_HEIGHT_PX = 32
MIN_PART_SIZE_PX = 3
STAGE2_NAMES = ["helmet", "no_helmet"]
# Images the first model's stricter rule would also have kept, one name per line.
FIRST_RULE_FILE = "first_model_rule_images.txt"
# Images kept despite a head outside every bike box: {image name: [[cx, cy, w, h], ...]}.
UNBOXED_FILE = "unboxed_heads.json"


@dataclass(frozen=True)
class Box:
    """A box in 0..1 image coordinates."""

    kind: str
    cx: float
    cy: float
    w: float
    h: float

    @property
    def left(self) -> float:
        return self.cx - self.w / 2

    @property
    def right(self) -> float:
        return self.cx + self.w / 2

    @property
    def top(self) -> float:
        return self.cy - self.h / 2

    @property
    def bottom(self) -> float:
        return self.cy + self.h / 2

    def contains_center_of(self, other: "Box") -> bool:
        return self.left <= other.cx <= self.right and self.top <= other.cy <= self.bottom


def canonical_class(name: str) -> str | None:
    key = re.sub(r"[\s_\-]+", "", name.lower())
    return {
        "rider": "rider",
        "motorcyclist": "rider",
        "helmet": "helmet",
        "nohelmet": "no_helmet",
        "numberplate": "plate",
        "licenseplate": "plate",
        "plate": "plate",
    }.get(key)


def read_class_names(yaml_path: Path) -> list[str]:
    """Reads names from data.yaml in either list form Roboflow writes."""
    lines = yaml_path.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        if not line.startswith("names:"):
            continue
        inline = line.split(":", 1)[1].strip()
        if inline:
            return [str(n) for n in ast.literal_eval(inline)]
        names: list[str] = []
        for entry in lines[index + 1 :]:
            match = re.match(r"\s+(?:-\s*|\d+:\s*)(.+)$", entry)
            if not match:
                break
            names.append(match.group(1).strip().strip("'\""))
        return names
    raise ValueError(f"No names found in {yaml_path}")


def read_labels(path: Path, names: list[str]) -> list[Box]:
    if not path.exists():
        return []
    boxes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        kind = canonical_class(names[int(parts[0])])
        if kind is not None:
            boxes.append(Box(kind, *(float(v) for v in parts[1:])))
    return boxes


def group_key(filename: str) -> str:
    """Frames of one video share a key. Unrelated photos each get their own."""
    stem = Path(filename).name.split(".rf.")[0]
    if not re.search(r"(?i)mov|mp4|avi", stem):
        return stem
    stem = re.sub(r"(?i)_(jpg|jpeg|png)$", "", stem)
    return re.sub(r"[-_]*\d*[-_]*$", "", stem)


def assign_split(key: str) -> str:
    bucket = int(hashlib.md5(key.encode("utf-8")).hexdigest()[:8], 16) % 100
    if bucket < 70:
        return "train"
    return "val" if bucket < 85 else "test"


def owner_of(part: Box, riders: list[Box]) -> Box | None:
    """Where bikes overlap, a part belongs to the tightest bike around it."""
    candidates = [r for r in riders if r.contains_center_of(part)]
    return min(candidates, key=lambda r: r.w * r.h) if candidates else None


def write_yaml(root: Path, names: list[str]) -> Path:
    body = [f"path: {root.resolve().as_posix()}", "train: images/train", "val: images/val", "test: images/test", "names:"]
    body += [f"  {index}: {name}" for index, name in enumerate(names)]
    path = root / "data.yaml"
    path.write_text("\n".join(body) + "\n", encoding="utf-8")
    return path


def make_dirs(root: Path) -> None:
    for split in ("train", "val", "test"):
        (root / "images" / split).mkdir(parents=True, exist_ok=True)
        (root / "labels" / split).mkdir(parents=True, exist_ok=True)


def crop_rider(image: Image.Image, rider: Box, parts: list[Box]):
    """Returns the padded crop and its YOLO label lines, or None if unusable."""
    width, height = image.size
    pad_x = rider.w * CROP_PADDING
    pad_y = rider.h * CROP_PADDING
    left = max(0, int((rider.left - pad_x) * width))
    top = max(0, int((rider.top - pad_y) * height))
    right = min(width, int((rider.right + pad_x) * width) + 1)
    bottom = min(height, int((rider.bottom + pad_y) * height) + 1)
    crop_w = right - left
    crop_h = bottom - top
    if crop_h < MIN_CROP_HEIGHT_PX or crop_w < MIN_CROP_HEIGHT_PX / 2:
        return None

    lines = []
    for part in parts:
        if part.kind not in STAGE2_NAMES:
            continue
        x1 = max(part.left * width, left)
        y1 = max(part.top * height, top)
        x2 = min(part.right * width, right)
        y2 = min(part.bottom * height, bottom)
        if x2 - x1 < MIN_PART_SIZE_PX or y2 - y1 < MIN_PART_SIZE_PX:
            continue
        cx = ((x1 + x2) / 2 - left) / crop_w
        cy = ((y1 + y2) / 2 - top) / crop_h
        lines.append(f"{STAGE2_NAMES.index(part.kind)} {cx:.6f} {cy:.6f} {(x2 - x1) / crop_w:.6f} {(y2 - y1) / crop_h:.6f}")
    if not lines:
        return None
    return image.crop((left, top, right, bottom)), lines


def prepare(raw: Path, out: Path, keep_unboxed: bool = False) -> dict:
    """Builds stage1/ and stage2/ under `out`.

    With keep_unboxed, images with a head outside every bike box stay in stage 1 and are
    listed in stage1/UNBOXED_FILE, for pseudo_labels.drop_uncovered() to settle later.
    """
    names = read_class_names(raw / "data.yaml")
    missing = {"rider", "helmet", "no_helmet", "plate"} - {canonical_class(n) for n in names}
    if missing:
        raise ValueError(f"Dataset is missing classes: {sorted(missing)}. It has: {names}")

    stage1 = out / "stage1"
    stage2 = out / "stage2"
    make_dirs(stage1)
    make_dirs(stage2)

    stats: Counter = Counter()
    seen_groups: dict[str, set] = {"train": set(), "val": set(), "test": set()}
    first_rule: list[str] = []
    unboxed: dict[str, list[list[float]]] = {}

    for raw_split in RAW_SPLITS:
        image_dir = raw / raw_split / "images"
        if not image_dir.exists():
            continue
        for image_path in sorted(image_dir.iterdir()):
            if image_path.suffix.lower() not in IMAGE_SUFFIXES:
                continue
            boxes = read_labels(raw / raw_split / "labels" / f"{image_path.stem}.txt", names)
            riders = [b for b in boxes if b.kind == "rider"]
            parts = [b for b in boxes if b.kind != "rider"]
            key = group_key(image_path.name)
            split = assign_split(key)
            seen_groups[split].add(key)
            stats["images_total"] += 1

            owners = {part: owner_of(part, riders) for part in parts}

            # A head outside every rider box means a bike nobody boxed. Teaching stage 1
            # that this bike is background would hurt it. Plates don't count: the dataset
            # also boxes the plates of cars, which are rightly outside every bike box.
            unowned_heads = [part for part, owner in owners.items() if owner is None and part.kind in STAGE2_NAMES]
            if unowned_heads and not keep_unboxed:
                stats["stage1_skipped_unboxed_bike"] += 1
            else:
                if unowned_heads:
                    # Kept for now: pseudo_labels.drop_uncovered() removes it again unless
                    # the filled-in boxes end up covering every one of these heads.
                    unboxed[image_path.name] = [[h.cx, h.cy, h.w, h.h] for h in unowned_heads]
                    stats["stage1_unboxed_bike_images_kept_for_now"] += 1
                shutil.copy2(image_path, stage1 / "images" / split / image_path.name)
                lines = [f"0 {r.cx:.6f} {r.cy:.6f} {r.w:.6f} {r.h:.6f}" for r in riders]
                (stage1 / "labels" / split / f"{image_path.stem}.txt").write_text("\n".join(lines), encoding="utf-8")
                stats[f"stage1_images_{split}"] += 1
                stats[f"stage1_boxes_{split}"] += len(riders)
                # The first model also left out images with an unboxed plate. Note which images it
                # would have kept, so its test score can be compared on exactly the same pictures.
                if any(owner is None for owner in owners.values()):
                    if not unowned_heads:
                        stats["stage1_kept_with_unboxed_plate"] += 1
                else:
                    first_rule.append(image_path.name)

            if not riders:
                continue
            with Image.open(image_path) as opened:
                image = ImageOps.exif_transpose(opened).convert("RGB")
                for index, rider in enumerate(riders):
                    own = [part for part, owner in owners.items() if owner == rider]
                    made = crop_rider(image, rider, own)
                    if made is None:
                        stats["stage2_crops_skipped"] += 1
                        continue
                    crop, lines = made
                    crop_name = f"{image_path.stem}__{index}"
                    crop.save(stage2 / "images" / split / f"{crop_name}.jpg", quality=95)
                    (stage2 / "labels" / split / f"{crop_name}.txt").write_text("\n".join(lines), encoding="utf-8")
                    stats[f"stage2_crops_{split}"] += 1
                    for line in lines:
                        stats[f"stage2_{STAGE2_NAMES[int(line.split()[0])]}_{split}"] += 1

    for split in ("train", "val", "test"):
        stats[f"source_groups_{split}"] = len(seen_groups[split])
        if stats[f"stage1_images_{split}"] == 0 or stats[f"stage2_crops_{split}"] == 0:
            raise ValueError(f"Split '{split}' came out empty: {dict(stats)}")

    (stage1 / FIRST_RULE_FILE).write_text("\n".join(first_rule), encoding="utf-8")
    (stage1 / UNBOXED_FILE).write_text(json.dumps(unboxed), encoding="utf-8")
    result = dict(sorted(stats.items()))
    result["stage1_yaml"] = str(write_yaml(stage1, ["motorcycle_unit"]))
    result["stage2_yaml"] = str(write_yaml(stage2, STAGE2_NAMES))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, required=True, help="Folder holding data.yaml and train/valid/test")
    parser.add_argument("--out", type=Path, required=True, help="Where to write stage1/ and stage2/")
    args = parser.parse_args()
    print(json.dumps(prepare(args.raw, args.out), indent=2))


if __name__ == "__main__":
    main()
