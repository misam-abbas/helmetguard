# Training the HelmetGuard models

Nothing here is built yet. This folder is where Phases 4 and 5 of the plan happen, and they need your own road footage.

Before recording, get your supervisor's or ethics board's answer on where you may film (decision 6 in the plan).

The app expects two exported models in `android/app/src/main/assets/models/`:

| File | Input | Classes |
|---|---|---|
| `stage1.onnx` | 416×416 full frame | `motorcycle_unit` |
| `stage2.onnx` | 320×320 motorcycle crop | `rider`, `helmet`, `no_helmet`, `plate` |

Add a `models.json` beside them. The format is in `android/app/src/main/assets/models/README.md`.

Datasets and checkpoints never go into Git.
