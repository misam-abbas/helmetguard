#!/usr/bin/env python3
"""Adds frames from the HELMET dataset (Myanmar road traffic) to the stage 1 training set.

HELMET: Siebert and Lin, "Detecting motorcycle helmet use with deep learning",
Accident Analysis & Prevention 134 (2020). https://osf.io/4pwj8/ CC BY 4.0.

Its 910 clips are 10 seconds of 1920x1080 video at 10 fps, and every motorcycle
has a box around the bike and its riders, like this project's `motorcycle_unit`.
Neighbouring frames are almost identical, so only a few frames per clip are used,
and they are read straight out of the 29 GB of zip files with HTTP range requests
instead of downloading everything.

The dataset's own split (by clip) is kept, so no clip is in two splits.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import struct
import threading
import time
import urllib.error
import urllib.request
import zipfile
import zlib
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

OSF = "https://osf.io/download/{}/"
ANNOTATIONS = "buh57"
SPLITS = "q7rmb"
IMAGE_PARTS = ["452nq", "muzgb", "9dmw3", "39ynb", "v3rch", "jyg9s", "6h5ka"]
SOURCE_SIZE = (1920, 1080)
SPLIT_NAMES = {"training": "train", "validation": "val", "test": "test"}
MIN_BOX_PX = 6
USER_AGENT = "HelmetGuard-training/1.0"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_no_redirect = urllib.request.build_opener(_NoRedirect)


def _request(url: str, headers: dict | None = None) -> urllib.request.Request:
    return urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})


def download(file_id: str, attempts: int = 5) -> bytes:
    """Downloads a small OSF file whole."""
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(_request(OSF.format(file_id)), timeout=300) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == attempts - 1:
                raise
            time.sleep(5 * (attempt + 1))
    raise AssertionError("unreachable")


class OsfFile:
    """Random access to one OSF file.

    OSF redirects to a storage link that only works for about a minute, so the
    link is fetched again whenever it is older than LINK_AGE_S.
    """

    LINK_AGE_S = 40

    def __init__(self, file_id: str):
        self.file_id = file_id
        self._link = None
        self._link_time = 0.0
        self._lock = threading.Lock()
        self.size = int(self._get_raw(0, 0)[1].rsplit("/", 1)[-1])

    def _fresh_link(self, force: bool = False) -> str:
        with self._lock:
            if not force and self._link and time.time() - self._link_time < self.LINK_AGE_S:
                return self._link
            url = OSF.format(self.file_id)
            for _ in range(6):
                try:
                    with _no_redirect.open(_request(url), timeout=60):
                        break  # Answered without a redirect: use it as is.
                except urllib.error.HTTPError as error:
                    if error.code not in (301, 302, 303, 307, 308):
                        raise
                    url = error.headers["Location"]
                    if "osf.io" not in url.split("/")[2]:
                        break
            self._link, self._link_time = url, time.time()
            return url

    def _get_raw(self, start: int, end: int) -> tuple[bytes, str]:
        for attempt in range(6):
            link = self._fresh_link(force=attempt > 0)
            try:
                with urllib.request.urlopen(_request(link, {"Range": f"bytes={start}-{end}"}), timeout=120) as response:
                    if response.status != 206:
                        raise RuntimeError(f"Storage ignored the byte range (HTTP {response.status})")
                    return response.read(), response.headers.get("Content-Range", "")
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                time.sleep(2 * (attempt + 1))
        raise RuntimeError(f"Couldn't read bytes {start}-{end} of OSF file {self.file_id}")

    def get(self, start: int, end: int) -> bytes:
        return self._get_raw(start, min(end, self.size - 1))[0]


class _Reader(io.RawIOBase):
    """Lets zipfile read the archive's table of contents from an OsfFile."""

    def __init__(self, remote: OsfFile):
        self.remote = remote
        self.pos = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset if whence == 0 else self.pos + offset if whence == 1 else self.remote.size + offset
        return self.pos

    def readinto(self, buffer):
        if self.pos >= self.remote.size:
            return 0
        data = self.remote.get(self.pos, self.pos + len(buffer) - 1)
        buffer[: len(data)] = data
        self.pos += len(data)
        return len(data)


def list_members(remote: OsfFile) -> list[zipfile.ZipInfo]:
    with zipfile.ZipFile(io.BufferedReader(_Reader(remote), buffer_size=1 << 20)) as archive:
        return archive.infolist()


def read_member(remote: OsfFile, info: zipfile.ZipInfo) -> bytes:
    """One range request per file: the local header, then the data."""
    slack = 30 + len(info.filename.encode("utf-8")) + 1024
    blob = remote.get(info.header_offset, info.header_offset + slack + info.compress_size)
    signature, *_, name_len, extra_len = struct.unpack("<4sHHHHHIIIHH", blob[:30])
    if signature != b"PK\x03\x04":
        raise ValueError(f"Bad zip entry for {info.filename}")
    start = 30 + name_len + extra_len
    if start + info.compress_size > len(blob):
        blob = remote.get(info.header_offset, info.header_offset + start + info.compress_size)
    data = blob[start : start + info.compress_size]
    if info.compress_type == zipfile.ZIP_STORED:
        return data
    if info.compress_type == zipfile.ZIP_DEFLATED:
        return zlib.decompress(data, -15)
    raise ValueError(f"Unsupported compression {info.compress_type} for {info.filename}")


def clip_and_frame(name: str) -> tuple[str, int] | None:
    """'part_7/Mandalay_1_28/05.jpg' -> ('Mandalay_1_28', 5)."""
    path = Path(name)
    if path.suffix.lower() not in {".jpg", ".jpeg", ".png"}:
        return None
    digits = "".join(ch for ch in path.stem if ch.isdigit())
    if not digits:
        return None
    return path.parent.name, int(digits)


def read_annotations(blob: bytes) -> dict[str, dict[int, list[tuple[float, float, float, float]]]]:
    """{clip: {frame: [(x, y, w, h) in source pixels]}}"""
    boxes: dict[str, dict[int, list]] = defaultdict(lambda: defaultdict(list))
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        for name in archive.namelist():
            if not name.lower().endswith(".csv"):
                continue
            clip = Path(name).stem
            rows = csv.DictReader(io.StringIO(archive.read(name).decode("utf-8-sig")))
            for row in rows:
                boxes[clip][int(row["frame_id"])].append(
                    (float(row["x"]), float(row["y"]), float(row["w"]), float(row["h"]))
                )
    return boxes


def read_splits(blob: bytes) -> dict[str, str]:
    rows = csv.DictReader(io.StringIO(blob.decode("utf-8-sig")))
    return {row["video_id"].strip(): SPLIT_NAMES[row["Set"].strip().lower()] for row in rows}


def yolo_lines(boxes, width: int, height: int) -> list[str]:
    lines = []
    for x, y, w, h in boxes:
        x1, y1 = max(0.0, x), max(0.0, y)
        x2, y2 = min(float(width), x + w), min(float(height), y + h)
        if x2 - x1 < MIN_BOX_PX or y2 - y1 < MIN_BOX_PX:
            continue
        lines.append(
            f"0 {(x1 + x2) / 2 / width:.6f} {(y1 + y2) / 2 / height:.6f} {(x2 - x1) / width:.6f} {(y2 - y1) / height:.6f}"
        )
    return lines


def pick_frames(frames_with_boxes: set[int], available: set[int], every: int) -> list[int]:
    """Every `every`-th frame that has a file, preferring frames that show at least one bike."""
    picked = []
    for start in range(1, 101, every):
        window = [f for f in range(start, start + every) if f in available]
        if not window:
            continue
        busy = [f for f in window if f in frames_with_boxes]
        picked.append(busy[0] if busy else window[0])
    return picked


def add_helmet_frames(stage1: Path, every_train: int = 10, every_eval: int = 20, long_side: int = 640,
                      workers: int = 16, limit_clips: int | None = None, log=print) -> dict:
    """Writes frames into stage1/images/<split> and labels into stage1/labels/<split>."""
    started = time.time()
    annotations = read_annotations(download(ANNOTATIONS))
    splits = read_splits(download(SPLITS))
    stats: Counter = Counter()

    jobs = []
    for part in IMAGE_PARTS:
        remote = OsfFile(part)
        members = {}
        for info in list_members(remote):
            parsed = clip_and_frame(info.filename)
            if parsed:
                members.setdefault(parsed[0], {})[parsed[1]] = info
        log(f"HELMET {part}: {len(members)} clips")
        for clip, frames in sorted(members.items()):
            split = splits.get(clip)
            if split is None or clip not in annotations:
                stats["clips_without_split_or_labels"] += 1
                continue
            every = every_train if split == "train" else every_eval
            for frame in pick_frames(set(annotations[clip]), set(frames), every):
                jobs.append((remote, frames[frame], clip, frame, split))
            stats[f"clips_{split}"] += 1
            if limit_clips and sum(v for k, v in stats.items() if k.startswith("clips_")) >= limit_clips:
                break
        if limit_clips and sum(v for k, v in stats.items() if k.startswith("clips_")) >= limit_clips:
            break

    def work(job):
        remote, info, clip, frame, split = job
        try:
            data = read_member(remote, info)
            with Image.open(io.BytesIO(data)) as opened:
                image = opened.convert("RGB")
            width, height = image.size
            sx, sy = width / SOURCE_SIZE[0], height / SOURCE_SIZE[1]
            boxes = [(x * sx, y * sy, w * sx, h * sy) for x, y, w, h in annotations[clip].get(frame, [])]
            scale = long_side / max(width, height)
            if scale < 1:
                image = image.resize((round(width * scale), round(height * scale)), Image.BILINEAR)
                boxes = [(x * scale, y * scale, w * scale, h * scale) for x, y, w, h in boxes]
            stem = f"helmet_{clip}_{frame:03d}"
            image.save(stage1 / "images" / split / f"{stem}.jpg", quality=90)
            lines = yolo_lines(boxes, image.width, image.height)
            (stage1 / "labels" / split / f"{stem}.txt").write_text("\n".join(lines), encoding="utf-8")
            return split, len(lines), None
        except Exception as error:  # One bad frame shouldn't stop the run.
            return split, 0, f"{clip}/{frame}: {type(error).__name__}: {error}"

    for split in ("train", "val", "test"):
        (stage1 / "images" / split).mkdir(parents=True, exist_ok=True)
        (stage1 / "labels" / split).mkdir(parents=True, exist_ok=True)

    log(f"HELMET: reading {len(jobs)} frames")
    errors = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for done, (split, box_count, error) in enumerate(pool.map(work, jobs), 1):
            if error:
                errors.append(error)
                continue
            stats[f"helmet_images_{split}"] += 1
            stats[f"helmet_boxes_{split}"] += box_count
            if done % 500 == 0:
                log(f"  {done}/{len(jobs)} frames")
    stats["helmet_frame_errors"] = len(errors)
    if errors:
        log("First errors:", *errors[:5], sep="\n  ")
    if len(errors) > 0.05 * max(1, len(jobs)):
        raise RuntimeError(f"Too many HELMET frames failed ({len(errors)} of {len(jobs)})")
    stats["helmet_seconds"] = round(time.time() - started)
    return dict(sorted(stats.items()))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage1", type=Path, required=True, help="The stage1 folder of a prepared dataset")
    parser.add_argument("--limit-clips", type=int, default=None, help="Only this many clips, for a quick test")
    parser.add_argument("--workers", type=int, default=16)
    args = parser.parse_args()
    print(json.dumps(add_helmet_frames(args.stage1, workers=args.workers, limit_clips=args.limit_clips), indent=2))


if __name__ == "__main__":
    main()
