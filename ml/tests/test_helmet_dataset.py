"""Run with: python -m unittest discover -s ml/tests"""
import io
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import helmet_dataset as hd  # noqa: E402


class NamingTests(unittest.TestCase):
    def test_clip_and_frame_come_from_the_path(self):
        self.assertEqual(hd.clip_and_frame("part_7/Yangon_II_39/24.jpg"), ("Yangon_II_39", 24))
        self.assertEqual(hd.clip_and_frame("part_1/Bago_highway_1/frame_007.JPG"), ("Bago_highway_1", 7))

    def test_folders_and_other_files_are_ignored(self):
        self.assertIsNone(hd.clip_and_frame("part_7/Yangon_II_39/"))
        self.assertIsNone(hd.clip_and_frame("part_7/readme.txt"))


class AnnotationTests(unittest.TestCase):
    def test_reads_boxes_per_clip_and_frame(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("annotation/Mandalay_1_28.csv", "track_id,frame_id,x,y,w,h,label\n_a,2,312,354,60,99,DNoHelmet\n_b,2,10,20,30,40,DHelmet\n")
        boxes = hd.read_annotations(buffer.getvalue())
        self.assertEqual(boxes["Mandalay_1_28"][2], [(312.0, 354.0, 60.0, 99.0), (10.0, 20.0, 30.0, 40.0)])

    def test_reads_the_split_file(self):
        splits = hd.read_splits(b"video_id,Set\nBago_highway_1,validation\nBago_highway_10,test\nBago_highway_11,training\n")
        self.assertEqual(splits, {"Bago_highway_1": "val", "Bago_highway_10": "test", "Bago_highway_11": "train"})


class LabelTests(unittest.TestCase):
    def test_top_left_boxes_become_centred_yolo_lines(self):
        (line,) = hd.yolo_lines([(960.0, 540.0, 192.0, 108.0)], 1920, 1080)
        self.assertEqual(line, "0 0.550000 0.550000 0.100000 0.100000")

    def test_boxes_are_clipped_to_the_frame(self):
        (line,) = hd.yolo_lines([(-20.0, 1000.0, 120.0, 200.0)], 1920, 1080)
        cls, cx, cy, w, h = (float(v) for v in line.split())
        self.assertAlmostEqual(w * 1920, 100.0, places=2)
        self.assertAlmostEqual(h * 1080, 80.0, places=2)

    def test_slivers_left_after_clipping_are_dropped(self):
        self.assertEqual(hd.yolo_lines([(1916.0, 10.0, 50.0, 50.0)], 1920, 1080), [])


class PickTests(unittest.TestCase):
    def test_one_frame_per_window_preferring_frames_with_bikes(self):
        available = set(range(1, 101))
        with_boxes = {5, 6, 33}
        self.assertEqual(hd.pick_frames(with_boxes, available, 25), [5, 33, 51, 76])

    def test_missing_frames_are_skipped(self):
        self.assertEqual(hd.pick_frames(set(), {3, 60}, 50), [3, 60])


if __name__ == "__main__":
    unittest.main()
