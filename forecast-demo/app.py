"""Read-only forecast demo; the existing Spring backend and device control are independent."""
import math
import os
from datetime import datetime, timedelta, timezone
from statistics import median
from urllib.parse import quote

import httpx
import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080").rstrip("/")
STEP = 300  # five-minute buckets, in seconds
LAGS = 6
app = FastAPI(title="Air Quality Supervision System — Forecast demo")
app.add_middleware(
    CORSMiddleware,
    allow_origins=os.getenv("CORS_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173").split(","),
    allow_methods=["GET"],
    allow_headers=["*"],
)


def model():
    return make_pipeline(StandardScaler(), Ridge(alpha=10, solver="svd"))


def estimate(rows, now):
    buckets = {}
    latest = None
    for row in rows:
        if not isinstance(row, dict):
            continue
        try:
            value = row.get("pm25")
            at = datetime.fromisoformat(row["recorded_at"].replace("Z", "+00:00")).timestamp()
            if (value is None or isinstance(value, bool) or not math.isfinite(float(value))
                    or not 0 <= float(value) <= 500 or row.get("pm25_saturated")
                    or not now - 86400 <= at <= now):
                continue
            key = int(at // STEP) * STEP
            buckets.setdefault(key, []).append(float(value))
            latest = at if latest is None else max(latest, at)
        except (ValueError, TypeError, KeyError, OverflowError):
            continue
    values = {at: median(samples) for at, samples in buckets.items()}
    ordered = sorted(values)
    visible = [at for at in ordered if at >= ordered[-1] - 3600] if ordered else []
    result = {
        "status": "insufficient_data", "message": "Chưa có số đo PM2.5 hợp lệ trong 24 giờ qua.",
        "observed": [{"at": at * 1000, "pm25": values[at]} for at in visible],
        "predicted": [], "model": "Ridge regression · 6 độ trễ", "training_rows": 0,
        "window_hours": 24, "validation": None,
    }
    if not ordered:
        return result
    if now - latest > STEP:
        return {**result, "status": "stale_data", "message": "Dữ liệu mới nhất đã cũ hơn 5 phút. Chờ thiết bị gửi mẫu mới."}
    xs, ys = [], []
    for at in ordered:
        past = [at - lag * STEP for lag in range(1, LAGS + 1)]
        if all(key in values for key in past):
            xs.append([values[key] for key in past])
            ys.append(values[at])
    result["training_rows"] = len(xs)
    anchor = ordered[-1]
    if len(xs) < 6 or not all(anchor - lag * STEP in values for lag in range(LAGS)):
        return {**result, "message": "Chưa đủ dữ liệu liên tục để học. Cần khoảng 60 phút có số đo đều trong ngày."}
    x, y = np.asarray(xs), np.asarray(ys)
    if len(xs) >= 24:
        holdout = model().fit(x[:-6], y[:-6])
        predicted = np.clip(holdout.predict(x[-6:]), 0, 500)
        result["validation"] = {
            "mae_5min": round(float(np.mean(np.abs(predicted - y[-6:]))), 2),
            "persistence_mae_5min": round(float(np.mean(np.abs(x[-6:, 0] - y[-6:]))), 2),
            "points": 6,
        }
    fitted = model().fit(x, y)
    history = [values[anchor - lag * STEP] for lag in range(LAGS)]
    projected = [{"at": anchor * 1000, "pm25": values[anchor]}]
    for step in range(1, 7):
        value = float(np.clip(fitted.predict([history])[0], 0, 500))
        projected.append({"at": (anchor + step * STEP) * 1000, "pm25": round(value, 1)})
        history = [value, *history[:-1]]
    return {**result, "status": "ready", "message": "", "predicted": projected}


@app.get("/health")
def health():
    return {"status": "ok", "model": "sklearn.Ridge"}


@app.get("/forecast/{device_id}")
def forecast(device_id: str):
    now = datetime.now(timezone.utc)
    try:
        response = httpx.get(
            f"{BACKEND_URL}/api/v1/devices/{quote(device_id, safe='')}/telemetry",
            params={"from": (now - timedelta(days=1)).isoformat(), "to": now.isoformat()},
            timeout=15,
        )
        if response.status_code == 404:
            raise HTTPException(404, "Không tìm thấy thiết bị hoặc lịch sử.")
        response.raise_for_status()
        rows = response.json()
        if not isinstance(rows, list):
            raise ValueError("Expected telemetry list")
        return estimate(rows, now.timestamp())
    except (httpx.HTTPError, ValueError) as exc:
        raise HTTPException(502, "Không lấy được lịch sử từ backend.") from exc
