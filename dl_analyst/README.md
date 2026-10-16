# AQSS Layer 3 AI

FastAPI inference service and reproducible dataset/model preparation tools. Layer 2 remains the decision authority and keeps rule-based fallbacks; Layer 3 predictions never actuate hardware directly.

## Current readiness and findings

UCI Beijing PM2.5 (381) and Beijing Multi-Site Air Quality (501) have been downloaded to `data/raw/` (ignored by Git), verified by SHA-256 and normalized into ignored, source/site/year/month-partitioned Parquet under `data/processed/canonical/`. Inventory and source attribution are in `data/reports/uci_inventory.json`.

- UCI 381: 43,824 hourly records, 2010–2014, one ambient site, 2,067 missing PM2.5 values (4.72%); CC BY 4.0.
- UCI 501: 12 ambient sites, each with 35,064 hourly records spanning 2013–2017; per-site PM2.5 missingness is about 1.76–2.72%; CC BY 4.0.
- These are **outdoor Beijing** data, not AQSS indoor ground truth. Values above 500 µg/m³ are retained and quality-flagged; they are not silently discarded as impossible source observations.
- `OPENAQ_API_KEY` was unavailable during the audit, so Vietnam OpenAQ coverage is not yet verified. The registered API discovery script requires that key and reports sensor coverage/license metadata before any measurement download.
- No AQSS deployment telemetry export or production-trained model is present. Do not promote any model based on these external datasets alone.

Forecast API v1 remains exactly 96 chronological PM2.5 points at 15-minute cadence, matching the current backend contract. Hourly UCI data is benchmarked at hourly cadence; it is never upsampled into invented 15-minute samples. Separate AQSS in-domain validation is required before a 15-minute forecast is production-ready.

## Setup

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
pytest
```

The default forecast backend is `joblib`, loaded from `models/forecast.joblib`. To explicitly try pretrained Chronos-2 locally, install the optional model package and set:

```bash
pip install 'chronos-forecasting>=2.0'
AQSS_FORECAST_BACKEND=chronos2 AQSS_CHRONOS_MODEL=amazon/chronos-2 uvicorn app.main:app --port 8000
```

Chronos-2 weights are not bundled or loaded by default. The published model card lists Apache-2.0, CPU/GPU inference, covariate support and multi-step quantiles. Pin and record a model revision before repeatable experiments. TimesFM 3.0 self-hosted weights are not selected because upstream states non-commercial/non-production restrictions; TimesFM 2.5 could be a later benchmark candidate.

`/health` is process liveness, not deployment approval. It reports selected backend, per-model loaded status and model versions. Inference returns HTTP 503 for absent models. No model is enabled for automated control merely because it loads.

## Dataset inventory and pipeline

Sources and license/attribution metadata are tracked in `data/source_registry.json`; canonical fields/provenance are specified in `data/canonical_schema.json`. Raw, canonical data and most reports are gitignored; source manifests, scripts and the audited inventory are tracked.

Rebuild canonical UCI partitions and inventory:

```bash
python -m data.ingest_uci
```

The adapter retains each source’s native hourly cadence, uses `Asia/Shanghai` for the source-local Beijing timestamps then stores UTC, keeps separate site IDs, preserves missing targets and records units, source/version/license/attribution, retrieval time and QC flags.

Discover candidate PM2.5 sensors in Vietnam through the registered OpenAQ API (requires a registered key):

```bash
export OPENAQ_API_KEY=...
python -m data.openaq --output data/reports/openaq_vietnam_inventory.json
```

The inventory call is a metadata/coverage audit only. Review each underlying provider license and attribution before acquiring measurements. Do not scrape OpenAQ Explorer or commit API keys.

For arbitrary CSV quality checks:

```bash
python -m data.audit_dataset path/to/source.csv --timestamp-column timestamp --value-column pm25
```

The 15-minute `data.forecast_windows` tools apply only to data genuinely sampled at a cadence that supports such aggregation. Gaps remain missing; training windows crossing a gap are rejected. Split time series before windowing to avoid leakage.

## Forecast benchmark

Run cadence-specific baseline backtesting on one hourly UCI site (example):

```bash
python -m training.backtest \
  --parquet data/processed/canonical \
  --source-id uci_beijing_multisite_501 \
  --site-id Aotizhongxin \
  --context 168 --horizon 24 --origins 20
```

Baselines are persistence and seasonal-naive; metrics include MAE/RMSE, pinball losses, q10–q90 coverage and interval width. Baseline intervals are heuristic and **not calibrated quantiles**. On Aotizhongxin (168-hour context, 24-hour horizon, 487 hourly origins), persistence MAE/RMSE were 39.89/47.32 µg/m³ versus seasonal-naive 56.30/66.71; persistence q10–q90 coverage was only 50.4%, so it is not a calibrated probabilistic forecast. This benchmark is saved in `data/reports/uci_aotizhongxin_backtest.json`. Add `--chronos` only when the optional dependency/weights are intentionally available. Compare on the same source cadence and held-out time/site. This is an ambient Beijing experiment, not evidence of AQSS indoor performance.

## Threshold and dosage

`data.generate_synthetic_labels` emits reproducible physics-informed surrogate data and a manifest. This is neither observed ground truth nor ready-to-train production labeling. ML#1/ML#2 require review of assumptions, measured fan CADR and physical validation before they can influence automation.

## Contract endpoints

- `POST /api/v1/threshold`
- `POST /api/v1/dosage`
- `POST /api/v1/forecast`
- `GET /health`

Request/response schemas are in `app/schemas.py`; `docs/io_contract.md` is the cross-layer source of truth. Adding covariates requires a separately versioned API/Java DTO change.

## References

- [UCI Beijing PM2.5](https://archive.ics.uci.edu/dataset/381/beijing+pm2+5+data), DOI: 10.24432/C5JS49
- [UCI Beijing Multi-Site Air Quality](https://archive.ics.uci.edu/dataset/501/beijing+pm2+5+data), DOI: 10.24432/C5RK5G
- [OpenAQ API measurements](https://docs.openaq.org/resources/measurements) · [licenses](https://docs.openaq.org/resources/licenses) · [terms](https://docs.openaq.org/about/terms)
- [Chronos-2 model card](https://huggingface.co/amazon/chronos-2) · [source](https://github.com/amazon-science/chronos-forecasting)
- [CAMS forecast dataset](https://www.ecmwf.int/en/forecasts/dataset/cams-global-atmospheric-composition-forecasts) and [data license](https://atmosphere.copernicus.eu/sites/default/files/repository/CAMS_data_license.pdf)
