"""Run with: python -m unittest discover -s ml/tests"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import prepare_public_dataset as prep  # noqa: E402

NAMES = "names: ['Number plate', 'Rider', 'helmet', 'nohelmet']\nnc: 4\n"
PLATE, RIDER, HELMET, NOHELMET = 0, 1, 2, 3


def key_in(split):
    """A photo name whose hash lands in the given split."""
    for n in range(10_000):
        name = f"photo{n}.jpg"
        if prep.assign_split(prep.group_key(name)) == split:
            return f"photo{n}"
    raise AssertionError(split)


class Fixture:
    def __init__(self, root: Path):
        self.raw = root / "raw"
        self.out = root / "out"
        (self.raw / "train" / "images").mkdir(parents=True)
        (self.raw / "train" / "labels").mkdir(parents=True)
        (self.raw / "data.yaml").write_text(NAMES, encoding="utf-8")

    def add(self, stem, labels, size=(1000, 1000)):
        Image.new("RGB", size, (90, 90, 90)).save(self.raw / "train" / "images" / f"{stem}.jpg")
        text = "\n".join(" ".join(str(v) for v in row) for row in labels)
        (self.raw / "train" / "labels" / f"{stem}.txt").write_text(text, encoding="utf-8")

    def one_per_split(self):
        for split in ("train", "val", "test"):
            self.add(key_in(split), [(RIDER, 0.5, 0.5, 0.4, 0.4), (NOHELMET, 0.5, 0.4, 0.1, 0.1)])


class NamingTests(unittest.TestCase):
    def test_class_aliases(self):
        self.assertEqual(prep.canonical_class("Number plate"), "plate")
        self.assertEqual(prep.canonical_class("nohelmet"), "no_helmet")
        self.assertEqual(prep.canonical_class("No-Helmet"), "no_helmet")
        self.assertEqual(prep.canonical_class("Rider"), "rider")
        self.assertIsNone(prep.canonical_class("car"))

    def test_reads_both_yaml_list_forms(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "data.yaml"
            path.write_text(NAMES, encoding="utf-8")
            self.assertEqual(prep.read_class_names(path), ["Number plate", "Rider", "helmet", "nohelmet"])
            path.write_text("nc: 2\nnames:\n  - Rider\n  - 'Number plate'\ntrain: x\n", encoding="utf-8")
            self.assertEqual(prep.read_class_names(path), ["Rider", "Number plate"])
            path.write_text("names:\n  0: Rider\n  1: helmet\n", encoding="utf-8")
            self.assertEqual(prep.read_class_names(path), ["Rider", "helmet"])

    def test_frames_of_one_video_share_a_group(self):
        a = prep.group_key("2023_09_28_15_08_IMG_6078_MOV-0043_jpg.rf.abc123.jpg")
        b = prep.group_key("2023_09_28_15_08_IMG_6078_MOV-0912_jpg.rf.def456.jpg")
        c = prep.group_key("2023_09_28_15_09_IMG_6079_MOV-0043_jpg.rf.abc123.jpg")
        self.assertEqual(a, b)
        self.assertNotEqual(a, c)

    def test_unrelated_photos_stay_separate(self):
        self.assertNotEqual(
            prep.group_key("Without_Helmet-54-_png.rf.a.jpg"),
            prep.group_key("Without_Helmet-55-_png.rf.b.jpg"),
        )


class PrepareTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.fx = Fixture(Path(self.tmp.name))

    def run_prepare(self):
        self.fx.one_per_split()
        return prep.prepare(self.fx.raw, self.fx.out)

    def labels(self, stage, stem):
        for path in (self.fx.out / stage / "labels").rglob(f"{stem}*.txt"):
            return path.read_text(encoding="utf-8").splitlines()
        return None

    def test_rider_box_becomes_the_stage1_label(self):
        stem = key_in("train") + "x"
        self.fx.add(stem, [(RIDER, 0.5, 0.5, 0.4, 0.6), (HELMET, 0.5, 0.3, 0.1, 0.1), (PLATE, 0.5, 0.7, 0.1, 0.05)])
        self.run_prepare()
        self.assertEqual(self.labels("stage1", stem), ["0 0.500000 0.500000 0.400000 0.600000"])

    def test_stage2_labels_are_relative_to_the_crop(self):
        stem = "video_mov-1"
        self.fx.add(stem, [(RIDER, 0.5, 0.5, 0.5, 0.5), (NOHELMET, 0.5, 0.3, 0.1, 0.1)])
        self.run_prepare()
        cls, cx, cy, w, h = self.labels("stage2", f"{stem}__0")[0].split()
        self.assertEqual(cls, "1")
        self.assertAlmostEqual(float(cx), 0.5, delta=0.01)
        # Crop spans y 0.21..0.79 of the frame, so a head at 0.30 sits near the top.
        self.assertAlmostEqual(float(cy), (0.30 - 0.21) / 0.58, delta=0.02)
        self.assertAlmostEqual(float(w), 0.1 / 0.58, delta=0.02)
        self.assertAlmostEqual(float(h), 0.1 / 0.58, delta=0.02)

    def test_a_head_goes_to_the_tightest_overlapping_bike(self):
        stem = "video_mov-2"
        self.fx.add(stem, [(RIDER, 0.5, 0.5, 0.8, 0.8), (RIDER, 0.7, 0.5, 0.2, 0.4), (HELMET, 0.7, 0.4, 0.05, 0.05)])
        self.run_prepare()
        self.assertIsNone(self.labels("stage2", f"{stem}__0"))
        self.assertEqual(len(self.labels("stage2", f"{stem}__1")), 1)

    def test_unboxed_bike_is_left_out_of_stage1_only(self):
        stem = "video_mov-3"
        self.fx.add(stem, [(RIDER, 0.3, 0.5, 0.2, 0.4), (HELMET, 0.3, 0.4, 0.05, 0.05), (NOHELMET, 0.9, 0.1, 0.05, 0.05)])
        stats = self.run_prepare()
        self.assertEqual(stats["stage1_skipped_unboxed_bike"], 1)
        self.assertIsNone(self.labels("stage1", stem))
        self.assertIsNotNone(self.labels("stage2", f"{stem}__0"))

    def test_a_car_plate_outside_every_bike_box_keeps_the_image(self):
        stem = "video_mov-8"
        self.fx.add(stem, [(RIDER, 0.3, 0.5, 0.2, 0.4), (HELMET, 0.3, 0.4, 0.05, 0.05), (PLATE, 0.85, 0.8, 0.08, 0.04)])
        stats = self.run_prepare()
        self.assertEqual(self.labels("stage1", stem), ["0 0.300000 0.500000 0.200000 0.400000"])
        self.assertEqual(stats["stage1_kept_with_unboxed_plate"], 1)
        first_rule = (self.fx.out / "stage1" / prep.FIRST_RULE_FILE).read_text(encoding="utf-8").splitlines()
        self.assertNotIn(f"{stem}.jpg", first_rule)
        self.assertEqual(len(first_rule), 3)  # the three images one_per_split adds

    def test_an_unboxed_bike_can_be_kept_for_the_box_filling_to_settle(self):
        stem = "video_mov-9"
        self.fx.add(stem, [(RIDER, 0.3, 0.5, 0.2, 0.4), (HELMET, 0.3, 0.4, 0.05, 0.05), (NOHELMET, 0.9, 0.1, 0.05, 0.06)])
        self.fx.one_per_split()
        stats = prep.prepare(self.fx.raw, self.fx.out, keep_unboxed=True)
        self.assertEqual(stats["stage1_unboxed_bike_images_kept_for_now"], 1)
        self.assertNotIn("stage1_skipped_unboxed_bike", stats)
        self.assertEqual(self.labels("stage1", stem), ["0 0.300000 0.500000 0.200000 0.400000"])
        unboxed = json.loads((self.fx.out / "stage1" / prep.UNBOXED_FILE).read_text(encoding="utf-8"))
        self.assertEqual(unboxed, {f"{stem}.jpg": [[0.9, 0.1, 0.05, 0.06]]})
        first_rule = (self.fx.out / "stage1" / prep.FIRST_RULE_FILE).read_text(encoding="utf-8").splitlines()
        self.assertNotIn(f"{stem}.jpg", first_rule)

    def test_empty_road_scene_is_kept_as_background(self):
        stem = key_in("val") + "y"
        self.fx.add(stem, [])
        self.run_prepare()
        self.assertEqual(self.labels("stage1", stem), [])

    def test_tiny_or_partless_crops_are_dropped(self):
        tiny = "video_mov-4"
        bare = "video_mov-5"
        self.fx.add(tiny, [(RIDER, 0.5, 0.5, 0.02, 0.02), (HELMET, 0.5, 0.5, 0.01, 0.01)])
        self.fx.add(bare, [(RIDER, 0.5, 0.5, 0.4, 0.4)])
        stats = self.run_prepare()
        self.assertEqual(stats["stage2_crops_skipped"], 2)

    def test_all_frames_of_a_video_land_in_one_split(self):
        for n in range(6):
            self.fx.add(f"clip_mov-{n}_jpg.rf.h{n}", [(RIDER, 0.5, 0.5, 0.4, 0.4), (HELMET, 0.5, 0.4, 0.1, 0.1)])
        self.run_prepare()
        folders = {p.parent.name for p in (self.fx.out / "stage1" / "images").rglob("clip_mov-*")}
        self.assertEqual(len(folders), 1)

    def test_yaml_lists_the_planned_classes(self):
        stats = self.run_prepare()
        self.assertIn("0: motorcycle_unit", Path(stats["stage1_yaml"]).read_text(encoding="utf-8"))
        text = Path(stats["stage2_yaml"]).read_text(encoding="utf-8")
        for line in ("0: helmet", "1: no_helmet", "test: images/test"):
            self.assertIn(line, text)
        self.assertNotIn("plate", text)

    def test_plates_never_become_stage2_labels(self):
        stem = "video_mov-6"
        self.fx.add(stem, [(RIDER, 0.5, 0.5, 0.5, 0.5), (HELMET, 0.5, 0.35, 0.1, 0.1), (PLATE, 0.5, 0.7, 0.1, 0.05)])
        self.run_prepare()
        self.assertEqual([line.split()[0] for line in self.labels("stage2", f"{stem}__0")], ["0"])

    def test_a_crop_with_only_a_plate_is_dropped(self):
        stem = "video_mov-7"
        self.fx.add(stem, [(RIDER, 0.5, 0.5, 0.5, 0.5), (PLATE, 0.5, 0.7, 0.1, 0.05)])
        stats = self.run_prepare()
        self.assertIsNone(self.labels("stage2", f"{stem}__0"))
        self.assertEqual(stats["stage2_crops_skipped"], 1)

    def test_the_class_names_roboflow_really_exports(self):
        (self.fx.raw / "data.yaml").write_text("names: ['helmet', 'licenseplate', 'motorcyclist', 'nohelmet']\n", encoding="utf-8")
        helmet, licenseplate, motorcyclist, nohelmet = 0, 1, 2, 3
        for split in ("train", "val", "test"):
            self.fx.add(key_in(split), [(motorcyclist, 0.5, 0.5, 0.4, 0.4), (nohelmet, 0.5, 0.4, 0.1, 0.1), (helmet, 0.45, 0.4, 0.05, 0.05), (licenseplate, 0.5, 0.6, 0.1, 0.05)])
        stats = prep.prepare(self.fx.raw, self.fx.out)
        self.assertEqual(stats["stage1_boxes_train"], 1)
        self.assertEqual(stats["stage2_no_helmet_train"], 1)
        self.assertEqual(stats["stage2_helmet_train"], 1)
        self.assertNotIn("stage2_plate_train", stats)

    def test_wrong_dataset_is_refused(self):
        (self.fx.raw / "data.yaml").write_text("names: ['car', 'bus']\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "missing classes"):
            prep.prepare(self.fx.raw, self.fx.out)

    def test_an_empty_split_is_refused(self):
        self.fx.add(key_in("train"), [(RIDER, 0.5, 0.5, 0.4, 0.4), (HELMET, 0.5, 0.4, 0.1, 0.1)])
        with self.assertRaisesRegex(ValueError, "came out empty"):
            prep.prepare(self.fx.raw, self.fx.out)


if __name__ == "__main__":
    unittest.main()
