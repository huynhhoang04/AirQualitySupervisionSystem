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

## Chronos-2 foundation model (optional, separate environment)

`app/chronos_model.py` and the three tools under `tools/` implement the direction in
`docs/upgrade-plan/07`/`08`: a 720-step (60 min) context on the 5-second grid, a 360-step (30 min)
median forecast, and LoRA fine-tuning on synthetic sessions.

It needs its own environment because it installs a CUDA build of torch:

```bash
uv venv .venv-chronos --python 3.12
uv pip install --python .venv-chronos/bin/python -r requirements-chronos.txt
```

Measured on the RTX 3050 Laptop (4 GB): **P95 0.017 s** per 720 → 360 forecast, **466 MiB** peak
VRAM. Fine-tune LoRA r=8/alpha=16, 500 steps: 171 s, 589.824 trainable parameters (0.49 %). The
promotion gate rejected the adapter (validation gain 4.2 % < 10 %), so pretrained remains the
served checkpoint; the adapter is kept for analysis. Details, numbers and what is **not** proven are
in [docs/upgrade-plan/09](../docs/upgrade-plan/09-step3-benchmark-and-training.md).

```bash
cd ai-service
.venv-chronos/bin/python tools/benchmark_chronos.py --device cuda
.venv-chronos/bin/python tools/generate_air_quality.py --output data/synthetic
.venv-chronos/bin/python tools/analyze_air_quality.py --synthetic data/synthetic --output reports/eda
PYTHONPATH=. .venv-chronos/bin/python tools/train_chronos.py --data data/synthetic \
    --output models/chronos-lora --steps 500
PYTHONPATH=. .venv-chronos/bin/python tools/evaluate_chronos.py --data data/synthetic \
    --model models/chronos-lora --output reports/chronos-eval
```

The Chronos path is wired into the worker behind `FORECAST_ENGINE=chronos`. It uses an incremental
context cache (`app/context_cache.py`) instead of reloading 60 minutes per packet, and uploads the
360-value curve to `forecast_paths`:

```bash
FORECAST_ENGINE=chronos CHRONOS_INFERENCE_SECONDS=5 \
BACKEND_URL=http://localhost:8080 PYTHONPATH=. \
.venv-chronos/bin/python -m app.worker_entry
```

`CHRONOS_ADAPTER=/path/to/adapter` serves a LoRA adapter instead of the pretrained checkpoint. The
adapter from step 3 is **not promoted** (validation gain 4.2 % < 10 %), so leaving it unset is the
current correct configuration. Details: [docs/upgrade-plan/10](../docs/upgrade-plan/10-step4-forecast-path-and-worker.md).

## Layout

```
app/
  config.py       environment settings
  backend.py      HTTP client for public + internal APIs
  data.py         minute buckets, labels, actuator alignment
  features.py     feature-v2 (train and inference share this file)
  models.py       persistence, Ridge, boosting, artifact save/load
  training.py     split, walk-forward, metrics, promotion gate
  inference.py    recent context → forecast run
  chronos_model.py 720 → 360 step context/pipeline adapter (Chronos-2)
  chronos_runner.py one task per device, newest request wins, uploads the curve
  context_cache.py  incremental after_id reads per device
  worker.py       claim/train/infer loop
  main.py         loopback /health only
configs/
  air_quality_synthetic.yaml  generator parameters (scenario, faults, split)
tools/
  benchmark_chronos.py        latency/VRAM measurement
  generate_air_quality.py     seeded synthetic sessions → Parquet shards
  analyze_air_quality.py      EDA, figures, real-vs-synthetic comparison
  train_chronos.py            LoRA fine-tune + validation gate + manifest
  evaluate_chronos.py         frozen test evaluation for a saved adapter
tests/
  test_pipeline.py
  test_chronos_context.py     readiness, gaps and causality of the Chronos context
  test_context_cache.py       incremental cursor, dedupe and fallback reload
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
