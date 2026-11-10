# Layer 3 — Removed

> **Status:** this layer no longer exists. Kept as a record of the decision, so the next person does
> not rebuild it by accident.

## What it was

A separate FastAPI service with three models:

| Model | Question | Where it went |
| :--- | :--- | :--- |
| ML#1 Threshold | *What PM2.5 level is unsafe in this room?* | Replaced by `ThresholdService` — a formula over the room profile, computed in the backend |
| ML#2 Dosage | *Which fan level, and for how long?* | Replaced by a constant: a breach means level 3 |
| DL Forecast | *Will the room breach in the next 24 h?* | Removed; nothing replaced it |

## Why each was removed

**ML#1 — the formula beat the model.** The threshold depends on dose: how long a person is in the
room, how large it is, how well it ventilates, and how sealed it is right now. Those are four
observable quantities and one guideline (WHO 2021, 15 µg/m³ for 24 h). A model trained on generated
labels would have learned a formula and then approximated it — strictly worse than evaluating the
formula. It now runs in-process, costs one method call, and reports every factor so the dashboard can
explain the number.

**ML#2 — there was nothing to decide.** With a breach, the room is already dirty. The fastest remedy
is the highest duty the fan supports. A model returning one of three levels, trained on synthetic
labels derived from the same decay equation, produced a constant in practice — so the constant is
what the code uses.

**DL forecast — the data said no.** Measured on the deployed node's own telemetry (808 samples over
6.3 hours, 1-minute resampled):

| Lag | Autocorrelation of PM2.5 |
| :---: | ---: |
| 1 min | 0.86 |
| 10 min | 0.65 |
| 30 min | 0.48 |
| 60 min | **0.07** |
| 120 min | −0.48 |

Beyond roughly 30 minutes the series is indistinguishable from white noise. Measured against a naive
persistence baseline over 281 thirty-minute windows:

| Predictor | RMSE |
| :--- | ---: |
| Persistence (repeat the last value) | **39.52 µg/m³** |
| Linear trend extrapolation | 46.48 µg/m³ |

The obvious baseline beats the obvious "model". A 24-hour forecast would need at least two weeks of
continuous data to train, and would still be predicting noise. CO₂ does not lead PM2.5 either —
correlation at a 30-minute lag was −0.19, i.e. nothing.

## What was kept

The CO₂ sensor and the room model are still central — not for forecasting, but for the threshold:
CO₂ measures how sealed and occupied the room is right now, and the room's purpose, volume and
ventilation drive the dose calculation. Removing the forecast removed the *prediction* claim, not the
reason the hardware exists.

## If someone wants to rebuild it

Requirements, in order:

1. **Two weeks of continuous per-room telemetry** before a forecast model is worth training. There
   is no shortcut; 6 hours cannot train a 24-hour horizon.
2. **A target that is predictable.** PM2.5 at 5 s cadence is not. Either aggregate to 15-minute
   medians and predict the *next hour* (autocorrelation 0.48 gives some signal), or predict a
   classification ("will this cross the threshold in 30 min") rather than a full curve.
3. **Beat persistence on held-out windows**, not on windows selected for having a trend. The 97 %
   hit rate quoted for trend extrapolation during the investigation came from survivorship bias: it
   only counted windows where a trend existed.
4. **Do not reintroduce a separate service** unless the model needs a GPU or has its own release
   cadence. `ThresholdService` shows how much fits in-process.
