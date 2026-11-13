"""Run with: python -m unittest discover -s ml/tests"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import pseudo_labels as pl  # noqa: E402

BIKE = (100.0, 200.0, 160.0, 300.0)  # 60 x 100 px, seen from the front
RIDER = (110.0, 150.0, 150.0, 280.0)  # head above the bike, legs inside it


class RiderTests(unittest.TestCase):
    def test_a_person_sitting_on_the_bike_is_a_rider(self):
        self.assertTrue(pl.is_rider(RIDER, BIKE))

    def test_a_person_walking_beside_the_bike_is_not(self):
        self.assertFalse(pl.is_rider((175.0, 150.0, 215.0, 300.0), BIKE))

    def test_a_person_far_behind_the_bike_is_not(self):
        # Small and entirely above the bike: someone further up the road.
        self.assertFalse(pl.is_rider((120.0, 120.0, 140.0, 205.0), BIKE))

    def test_a_giant_person_in_front_of_a_small_bike_is_not(self):
        self.assertFalse(pl.is_rider((60.0, 0.0, 200.0, 600.0), BIKE))

    def test_unit_box_wraps_the_bike_and_its_riders(self):
        self.assertEqual(pl.unit_box(BIKE, [RIDER]), (100.0, 150.0, 160.0, 300.0))

    def test_a_parked_bike_gets_no_unit(self):
        self.assertIsNone(pl.unit_box(BIKE, []))


class NewUnitTests(unittest.TestCase):
    def test_an_unlabelled_ridden_bike_is_added(self):
        self.assertEqual(pl.new_units([], [(BIKE, 0.8)], [RIDER], 1280, 720), [(100.0, 150.0, 160.0, 300.0)])

    def test_a_labelled_bike_is_not_added_again(self):
        existing = [(95.0, 145.0, 165.0, 305.0)]
        self.assertEqual(pl.new_units(existing, [(BIKE, 0.8)], [RIDER], 1280, 720), [])

    def test_a_tiny_far_away_bike_is_left_out(self):
        bike = (10.0, 10.0, 13.0, 16.0)
        rider = (10.5, 7.0, 12.5, 15.0)
        self.assertEqual(pl.new_units([], [(bike, 0.9)], [rider], 1280, 720), [])

    def test_two_detections_of_one_bike_give_one_box(self):
        twin = (101.0, 201.0, 161.0, 299.0)
        self.assertEqual(len(pl.new_units([], [(BIKE, 0.8), (twin, 0.7)], [RIDER], 1280, 720)), 1)

    def test_boxes_are_kept_inside_the_image(self):
        bike = (1240.0, 600.0, 1300.0, 700.0)
        rider = (1250.0, 550.0, 1290.0, 680.0)
        (box,) = pl.new_units([], [(bike, 0.9)], [rider], 1280, 720)
        self.assertEqual(box[2], 1280.0)


class DropUncoveredTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.stage1 = Path(self.tmp.name)
        for kind in ("images", "labels"):
            (self.stage1 / kind / "train").mkdir(parents=True)

    def add(self, stem, label_lines):
        (self.stage1 / "images" / "train" / f"{stem}.jpg").write_bytes(b"jpg")
        (self.stage1 / "labels" / "train" / f"{stem}.txt").write_text("\n".join(label_lines), encoding="utf-8")

    def test_an_image_whose_heads_are_now_inside_boxes_is_kept(self):
        self.add("a", ["0 0.5 0.5 0.4 0.6"])
        stats = pl.drop_uncovered(self.stage1, {"a.jpg": [[0.5, 0.3, 0.05, 0.05]]})
        self.assertEqual(stats, {"recovered_train": 1})
        self.assertTrue((self.stage1 / "images" / "train" / "a.jpg").exists())

    def test_an_image_with_a_head_still_outside_every_box_is_removed(self):
        self.add("b", ["0 0.2 0.5 0.2 0.4"])
        stats = pl.drop_uncovered(self.stage1, {"b.jpg": [[0.2, 0.4, 0.05, 0.05], [0.8, 0.4, 0.05, 0.05]]})
        self.assertEqual(stats, {"dropped_train": 1})
        self.assertFalse((self.stage1 / "images" / "train" / "b.jpg").exists())
        self.assertFalse((self.stage1 / "labels" / "train" / "b.txt").exists())


class LabelFileTests(unittest.TestCase):
    def test_yolo_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.txt"
            path.write_text(pl.yolo_line(BIKE, 1280, 720), encoding="utf-8")
            (box,) = pl.read_yolo(path, 1280, 720)
            for got, want in zip(box, BIKE):
                self.assertAlmostEqual(got, want, places=2)

    def test_missing_label_file_means_no_boxes(self):
        self.assertEqual(pl.read_yolo(Path("does-not-exist.txt"), 10, 10), [])


if __name__ == "__main__":
    unittest.main()
