"""Run with: python -m unittest discover -s ml/tests"""
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import extra_datasets as ed  # noqa: E402


class ExtraDatasetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "input" / "violations"
        self.stage1 = Path(self.tmp.name) / "stage1"
        (self.root).mkdir(parents=True)
        (self.root / "data.yaml").write_text("nc: 4\nnames: ['WithHelmet', 'WithoutHelmet', 'TripleRiding', 'Plate']\n", encoding="utf-8")

    def add(self, split, stem, rows, size=(640, 640), suffix=".jpg"):
        (self.root / split / "images").mkdir(parents=True, exist_ok=True)
        (self.root / split / "labels").mkdir(parents=True, exist_ok=True)
        Image.new("RGB", size, (90, 90, 90)).save(self.root / split / "images" / f"{stem}{suffix}")
        (self.root / split / "labels" / f"{stem}.txt").write_text("\n".join(rows), encoding="utf-8")

    def test_reads_both_name_forms(self):
        self.assertEqual(ed.read_names(self.root / "data.yaml"), ["WithHelmet", "WithoutHelmet", "TripleRiding", "Plate"])
        (self.root / "data.yaml").write_text("names:\n  0: rider\n  1: helmet\ntrain: x\n", encoding="utf-8")
        self.assertEqual(ed.read_names(self.root / "data.yaml"), ["rider", "helmet"])

    def test_finds_the_dataset_by_a_class_name(self):
        self.assertEqual(ed.find_dataset([Path(self.tmp.name) / "input"], "Triple Riding"), self.root)
        self.assertIsNone(ed.find_dataset([Path(self.tmp.name) / "input"], "motorcycle"))

    def test_only_whole_bike_boxes_are_kept_and_splits_are_mapped(self):
        self.add("train", "a", ["2 0.5 0.5 0.4 0.6", "1 0.5 0.3 0.1 0.1", "3 0.5 0.8 0.1 0.05"])
        self.add("valid", "b", ["0 0.5 0.3 0.1 0.1"])
        stats = ed.add_yolo_dataset(self.root, self.stage1, "dg", {"TripleRiding"})
        self.assertEqual((self.stage1 / "labels" / "train" / "dg_a.txt").read_text(), "0 0.5 0.5 0.4 0.6")
        self.assertEqual((self.stage1 / "labels" / "val" / "dg_b.txt").read_text(), "")
        self.assertTrue((self.stage1 / "images" / "val" / "dg_b.jpg").exists())
        self.assertEqual(stats["dg_images_train"], 1)
        self.assertEqual(stats["dg_boxes_train"], 1)

    def test_big_or_png_images_become_jpgs_no_larger_than_the_limit(self):
        self.add("test", "big", [], size=(4000, 3000), suffix=".png")
        ed.add_yolo_dataset(self.root, self.stage1, "dg", {"TripleRiding"})
        with Image.open(self.stage1 / "images" / "test" / "dg_big.jpg") as image:
            self.assertEqual(max(image.size), 1280)

    def test_an_empty_dataset_is_refused(self):
        with self.assertRaisesRegex(ValueError, "No images"):
            ed.add_yolo_dataset(self.root, self.stage1, "dg", {"TripleRiding"})


if __name__ == "__main__":
    unittest.main()
