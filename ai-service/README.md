# AI service — Air Quality Supervision System

A local Python worker that learns from the node's **raw** telemetry and uploads forecasts to the
Spring backend. It is not on the control path: stopping it never affects measurement, the threshold
or the fan loop.

## What it does

1. Claims a `train` job from the backend (`POST /internal/v1/ai/jobs/claim`).
2. Freezes a dataset window (`POST /internal/v1/ai/dataset-snapshots`) and pages the ordered replay.
3. Builds one-minute buckets → `feature-v1` → labels at +5/+15/+30 minutes.
4. Runs a temporal split with a 30-minute purge, walk-forward validation for Ridge and gradient
   boosting, and compares both against persistence.
5. Fits the selected candidate on development, scores the frozen test window once, and applies the
   promotion gate (mean MAE 15/30 must improve ≥ 10 %; MAE 5 may not regress > 5 %).
6. Saves the artifact locally and registers model metadata + metrics on the backend.
7. Every `INFERENCE_SECONDS`, reads the last 90 minutes, predicts, and uploads one forecast run
   before its target times. Without a promoted model it uploads the persistence baseline.

A model that does not beat the baseline is still registered, with its metrics and the reason. The UI
shows it; nothing is invented.

## Layout

```
app/
  config.py      environment settings
  backend.py     HTTP client for public + internal APIs
  data.py        minute buckets, labels, actuator alignment
  features.py    feature-v1 (train and inference share this file)
  models.py      persistence, Ridge, boosting, artifact save/load
  training.py    split, walk-forward, metrics, promotion gate
  inference.py   recent context → forecast run
  worker.py      claim/train/infer loop
  main.py        loopback /health only
tests/
  test_pipeline.py
```

## Run

```bash
cd ai-service
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

export BACKEND_URL=https://aqss-backend.onrender.com
export AI_WORKER_TOKEN=...       # must match the backend
export WORKER_ID=laptop
python -m app.worker
```

The worker only makes **outbound** connections; no inbound port or tunnel is needed. Diagnostics:
`uvicorn app.main:app --host 127.0.0.1 --port 8001` serves `GET /health`.

## Tests

```bash
cd ai-service
python -m unittest discover -s tests -v
```

They cover bucket validity, label gaps, causal features (mutating a future sample must not change a
past origin), persistence, a real Ridge fit, the promotion gate, and a 30-hour synthetic train to
check that the data card and all three baselines appear.

Synthetic data is for tests only. It never enters a reported metrics table.

## Configuration

| Variable | Default | Meaning |
| :--- | :--- | :--- |
| `BACKEND_URL` | `http://localhost:8080` | Spring backend |
| `AI_WORKER_TOKEN` | empty | Bearer token for `/internal/**` |
| `WORKER_ID` | `local-worker` | Unique worker id |
| `ARTIFACT_DIR` | `artifacts` | Model files + `current.json` pointer per device |
| `SNAPSHOT_DIR` | `snapshots` | Optional local snapshot copies |
| `POLL_SECONDS` | `10` | Job poll cadence |
| `INFERENCE_SECONDS` | `60` | Forecast cadence |
| `HEARTBEAT_SECONDS` | `15` | Worker liveness |
| `TRAIN_INTERVAL_HOURS` | `6` | Scheduled retrain spacing (scheduler job) |
| `MAX_HISTORY_DAYS` | `14` | Maximum training window |

## Guarantees and limits

- One training process at a time; one worker claims one job under a lease.
- Inference uses the last local artifact it created. If the artifact is missing, it falls back to
  persistence rather than pretending a model is available.
- Features are causal: only buckets ending at or before the origin, forward-fill limited to two
  minutes, no backward fill.
- A forecast uploaded after a target is stored as `LATE_SUBMISSION` and is not scored prospectively.
- This is a one-room model on one node. It does not prove sensor accuracy, RPM, power savings or a
  causal effect of the fan.
