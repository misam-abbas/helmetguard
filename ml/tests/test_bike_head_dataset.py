"""Run with: python -m unittest discover -s ml/tests"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import bike_head_dataset as bh  # noqa: E402
import prepare_public_dataset as prep  # noqa: E402

BIKE = "0 0.50 0.70 0.10 0.20"  # bike box: x 0.45-0.55, y 0.60-0.80
HEAD_OVER_BIKE = "2 0.50 0.45 0.04 0.06"  # no helmet, y 0.42-0.48, just above the bike
HELMET_OVER_BIKE = "1 0.53 0.47 0.04 0.06"  # a pillion with a helmet
FAR_HEAD = "1 0.10 0.10 0.04 0.06"  # nobody's bike is boxed under it


class BikeHeadDatasetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.root = base / "input" / "Helmet vs No Helmet.v3i.yolov11"
        self.stage1, self.stage2 = base / "stage1", base / "stage2"
        self.root.mkdir(parents=True)
        (self.root / "data.yaml").write_text("nc: 3\nnames: ['bike', 'helmet', 'no helmet']\n", encoding="utf-8")

    def add(self, split, video, frame, rows):
        name = f"{video}_mp4-{frame:04d}_jpg.rf.{frame:032x}"
        for kind in ("images", "labels"):
            (self.root / split / kind).mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (512, 512), (90, 90, 90)).save(self.root / split / "images" / f"{name}.jpg")
        (self.root / split / "labels" / f"{name}.txt").write_text("\n".join(rows), encoding="utf-8")
        return name

    def box(self, row):
        c, *v = row.split()
        return prep.Box(bh.kind_of(["bike", "helmet", "no helmet"][int(c)]), *(float(x) for x in v))

    def test_finds_the_dataset_by_its_classes(self):
        self.assertEqual(bh.find_dataset([Path(self.tmp.name) / "input"]), self.root)
        (self.root / "data.yaml").write_text("names: ['helmet', 'licenseplate', 'motorcyclist', 'nohelmet']\n", encoding="utf-8")
        self.assertIsNone(bh.find_dataset([Path(self.tmp.name) / "input"]))

    def test_a_head_rides_the_bike_just_below_it(self):
        bike = self.box(BIKE)
        other = self.box("0 0.80 0.70 0.10 0.20")
        self.assertEqual(bh.bike_of(self.box(HEAD_OVER_BIKE), [other, bike]), bike)
        self.assertIsNone(bh.bike_of(self.box(FAR_HEAD), [bike, other]))
        # Below the bike's middle is not a rider's head.
        self.assertIsNone(bh.bike_of(self.box("2 0.50 0.75 0.04 0.06"), [bike]))

    def test_the_unit_box_covers_the_bike_and_its_riders(self):
        unit = bh.unit_of(self.box(BIKE), [self.box(HEAD_OVER_BIKE), self.box(HELMET_OVER_BIKE)])
        self.assertAlmostEqual(unit.top, 0.42, places=5)
        self.assertAlmostEqual(unit.bottom, 0.80, places=5)
        self.assertAlmostEqual(unit.left, 0.45, places=5)
        self.assertAlmostEqual(unit.right, 0.55, places=5)
        # No boxed head: the rider is assumed to sit above the bike anyway.
        bare = bh.unit_of(self.box(BIKE), [])
        self.assertAlmostEqual(bare.top, 0.60 - bh.RIDER_ABOVE_BIKE * 0.20, places=5)

    def test_frames_are_unsquashed_split_by_video_and_cropped_for_stage_2(self):
        a = self.add("train", "CamA", 1, [BIKE, HEAD_OVER_BIKE, HELMET_OVER_BIKE])
        b = self.add("train", "CamB", 2, [BIKE, HEAD_OVER_BIKE])  # Roboflow put it in train, but CamB is a test video
        c = self.add("valid", "CamA", 3, [BIKE, HEAD_OVER_BIKE, FAR_HEAD])
        stats = bh.add_bike_head_dataset(self.root, self.stage1, self.stage2, "pk", {"CamB_mp4": "test"}, stage1_repeats=2, stage2_repeats=3)

        with Image.open(self.stage1 / "images" / "train" / f"pk_{a}.jpg") as image:
            self.assertEqual(image.size, (910, 512))
        self.assertTrue((self.stage1 / "images" / "train" / f"pk_{a}_r1.jpg").exists())
        self.assertTrue((self.stage1 / "images" / "test" / f"pk_{b}.jpg").exists())
        self.assertFalse((self.stage1 / "images" / "test" / f"pk_{b}_r1.jpg").exists())
        # CamA's frame from Roboflow's "valid" stays with its video, in train.
        self.assertTrue((self.stage1 / "images" / "train" / f"pk_{c}.jpg").exists())

        label = (self.stage1 / "labels" / "train" / f"pk_{a}.txt").read_text().split()
        self.assertEqual(label[0], "0")
        self.assertAlmostEqual(float(label[2]) - float(label[4]) / 2, 0.42, places=4)

        crop_labels = sorted((self.stage2 / "labels" / "train" / f"pk_{a}__0.txt").read_text().splitlines())
        self.assertEqual([line.split()[0] for line in crop_labels], ["0", "1"])  # helmet and no_helmet
        self.assertEqual(len(list((self.stage2 / "images" / "train").glob(f"pk_{a}__0*.jpg"))), 3)
        self.assertEqual(stats["pk_stage2_no_helmet_test"], 1)

        # Both copies of the training picture wait for the box-filling step to cover that head.
        unboxed = json.loads((self.stage1 / prep.UNBOXED_FILE).read_text())
        self.assertEqual(sorted(unboxed), [f"pk_{c}.jpg", f"pk_{c}_r1.jpg"])
        self.assertEqual(stats["pk_heads_without_a_boxed_bike"], 1)

    def test_an_unrelated_dataset_is_refused(self):
        (self.root / "data.yaml").write_text("names: ['car', 'bus']\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "no bike"):
            bh.add_bike_head_dataset(self.root, self.stage1, self.stage2, "pk", {})


if __name__ == "__main__":
    unittest.main()
