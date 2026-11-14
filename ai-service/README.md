# AI service — Air Quality Supervision System

A local Python worker that learns from the node's **raw** telemetry and uploads forecasts to the
Spring backend. It is not on the control path: stopping it never affects measurement, the threshold
or the fan loop.

## What it does

1. Claims a `train` job from the backend (`POST /internal/v1/ai/jobs/claim`).
2. Freezes a dataset window (`POST /internal/v1/ai/dataset-snapshots`) and pages the ordered replay.
3. Builds complete minute buckets → `feature-v2` → labels at +5/+15/+30 minutes.
4. Runs a temporal split with a 30-minute purge, walk-forward validation for Ridge and gradient
   boosting, including candidates that learn the change from the current PM anchor.
5. Selects/promotes using validation (mean MAE 15/30 must improve ≥ 10 %; MAE 5 may not regress
   > 5 %). The final development fit purges labels crossing the test boundary. Frozen test metrics
   are reported independently, not used to select/promote the model.
6. Saves the artifact locally and registers model metadata + metrics on the backend.
7. Every `INFERENCE_SECONDS`, reads the last 90 minutes, predicts, and uploads one forecast run
   before its target times. Without a compatible model/history it explicitly uses persistence,
   which needs only a recent valid complete minute. Forecasts include measured-data advisories.

A model that does not beat the baseline is still registered, with its metrics and the reason. The UI
shows it; nothing is invented.

Training needs at least 19.2 hours of valid minute buckets (the equivalent of 24 hours at 80%
coverage), plus the temporal development/test history. Calendar gaps never count as training
hours. The data card distinguishes the full minute grid, valid minute buckets and ready origins.

## Layout

```
app/
  config.py      environment settings
  backend.py     HTTP client for public + internal APIs
  data.py        minute buckets, labels, actuator alignment
  features.py    feature-v2 (train and inference share this file)
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
python -m app.worker_entry
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

- One background training task at a time; inference, heartbeat and lease renewal keep running.
- Inference uses the last local artifact it created. If the artifact is missing, it falls back to
  persistence rather than pretending a model is available.
- Features are causal: only buckets ending at or before the origin, forward-fill limited to two
  minutes, no backward fill. Low-coverage buckets are missing; late replay packets can be labels
  but are not treated as features available at the historical origin. Partial minutes are excluded.
- Feature-v1 artifacts must be retrained; incompatible artifacts fall back to an explicit baseline.
- Recommendations distinguish existing PM breaches, learned future breaches and CO₂ ventilation
  reminders. They do not send actuator commands. The 1,000 ppm reminder is project policy.
- A forecast uploaded after a target is stored as `LATE_SUBMISSION` and is not scored prospectively.
- This is a one-room model on one node. It does not prove sensor accuracy, RPM, power savings or a
  causal effect of the fan.
