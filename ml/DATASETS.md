# Datasets

Every dataset is recorded here before it is used.

| Dataset | Source | Licence | Size | Used for |
|---|---|---|---|---|
| helmet-detection-and-license-plate-recognition, version 1, by on Gia Capital | [Roboflow Universe](https://universe.roboflow.com/on-gia-capital/helmet-detection-and-license-plate-recognition/dataset/1) | CC BY 4.0 | 6,562 images; exported classes `helmet`, `licenseplate`, `motorcyclist`, `nohelmet` (the project page shows `motorcyclist` as `Rider` and `licenseplate` as `Number plate`) | Both models, first and second version (the second version's notebook skips it, and stage 2, when no Roboflow key is set up in Kaggle). Plates are never a training label in the second version |
| HELMET, by Felix Wilhelm Siebert and Hanhe Lin | [OSF](https://osf.io/4pwj8/), from "Detecting motorcycle helmet use with deep learning", *Accident Analysis & Prevention* 134 (2020) | CC BY 4.0 | 910 clips of 10 s, 1920×1080 at 10 fps, from 12 sites in Myanmar; every nearby motorcycle boxed with its riders, plus who wears a helmet. The second model uses about one frame per second of the training clips and one every two seconds of the others, read straight out of the zip files | Stage 1 of the second model. The dataset's own train/validation/test split by clip is kept |
| Trafffic_Violations_Triple_Riding_No_Helmet_Plate_, version 1, by DevguruCodes | [Kaggle](https://www.kaggle.com/datasets/devgurucodes/trafffic-violations-triple-riding-no-helmet-plate) | Database: ODbL 1.0; contents: Database Contents License | About 11,700 pictures of Indian two-wheeler traffic, in YOLO format, with classes `WithHelmet`, `WithoutHelmet`, `TripleRiding`, `Plate`. It was compiled from several sources that the page doesn't list | Stage 1 of the second model. Only the pictures and the `TripleRiding` boxes are used; the other bikes get boxes from the label-filling step below. Its own split is kept |

CC BY 4.0 requires attribution, which this table provides. Credit them the same way in the project report. The ODbL asks that a model trained on that database credits it: "Contains information from Trafffic_Violations_Triple_Riding_No_Helmet_Plate_ by DevguruCodes, made available under the ODbL". Its page doesn't name the sources it was compiled from, so their own terms can't be checked; replace it with your own footage before any use beyond the project.

### Filling in missing boxes

Neither dataset boxes every motorcycle: far-away bikes and bikes half outside the picture are often left out, which teaches a model to ignore them. For the second model, a COCO-trained YOLO11x (Ultralytics, AGPL-3.0) looks at every stage 1 image and adds a `motorcycle_unit` box for each ridden motorcycle it finds that has no box (`scripts/pseudo_labels.py`). Parked bikes stay unlabelled. The big model only labels; it isn't shipped. The test scores are reported twice: on the completed labels, and on the original test images with their original labels, so they can be compared with the first model.

## Checked and not used

Looked at on 2026-11-10 while searching for Sindh images. None of these has helmet labels or motorcycle riders from Sindh.

| Source | What it is | Why it isn't used |
|---|---|---|
| [Fahad-H36/ALPR-Pakistan](https://github.com/Fahad-H36/ALPR-Pakistan) | A number-plate reading program with one 8-second video of cars and its own plate model | Not a dataset, and it has no licence, so nothing from it can be included here. The video was run through the models once on a local machine as a check |
| [ALPR, version 24](https://universe.roboflow.com/alpr-qggma/alpr-akctv/dataset/24) (the data the repo above credits) | 593 grayscale images, public domain | Cars and plate close-ups only. The plates seen on its page were Punjab and Islamabad |
| [Sindh plate detection](https://universe.roboflow.com/demo-ex0ce/sindh-plate-detection) | 29 images, CC BY 4.0 | Too small, and the images seen were cars |

## How this data differs from the plan

The plan's main training data is 2–4 hours of your own footage from Pakistani roads. That is still the plan for the final model. This public dataset gives a working first model. Its page doesn't say where it was filmed and it isn't known to include Pakistani roads or plates, so expect lower accuracy there than the scores this data reports.

Its `motorcyclist` box wraps the whole motorcycle and everyone on it, so it is used as `motorcycle_unit` for stage 1. It has no box per person, so stage 2 detects heads only, `helmet` and `no_helmet`, and the app treats each detected head as one rider. (The first version also learned `plate`, which the app ignores.)
