"""Issue causal forecasts, with an explicit baseline fallback and measured-data advisories."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

import pandas as pd

from .data import build_dataset, parse_instant
from .features import FEATURE_VERSION, InsufficientData, build_features, latest_origin
from .models import HORIZONS, PersistenceBundle


class NotReady(Exception):
    """No fresh, complete context is available for this forecast type."""


def build_context(rows: list[dict[str, Any]], actuator_rows: list[dict[str, Any]] | None = None,
                  interval_seconds: float = 5.0, now: datetime | None = None,
                  require_history: bool = True) -> tuple[pd.DataFrame, pd.DataFrame, pd.Timestamp]:
    issued = now or datetime.now(timezone.utc)
    now_ts = pd.Timestamp(issued)
    # A received-late sample cannot have been available to the model before it arrived.
    available = [row for row in rows if (at := parse_instant(row.get("recorded_at"))) is not None
                 and at <= issued and (parse_instant(row.get("received_at"), at) or at) <= issued]
    frame = build_dataset(available, actuator_rows or [], interval_seconds=interval_seconds)
    frame = frame.loc[frame.index <= now_ts]
    if frame.empty:
        raise NotReady("Chưa có phút đo hoàn tất với dữ liệu hợp lệ.")
    actual = frame.loc[frame["pm_valid"].fillna(False).astype(bool)]
    if actual.empty or now_ts - actual.index.max() > pd.Timedelta(minutes=5):
        raise NotReady("Chưa có phút đo hợp lệ trong 5 phút gần nhất; kiểm tra kết nối và cảm biến bụi.")
    if not require_history:
        origin = actual.index.max()
        features = pd.DataFrame({"pm_now": [float(actual.loc[origin, "pm25_median"])]}, index=[origin])
        return features, frame, origin
    try:
        features = build_features(frame)
    except InsufficientData as exc:
        raise NotReady("Model cần lịch sử 60 phút; hiện chưa đủ dữ liệu liên tục.") from exc
    origin = latest_origin(features, now_ts)
    if origin is None:
        raise NotReady("Lịch sử PM còn khoảng trống ở các mốc trễ; tạm dùng số đo cuối làm baseline.")
    return features, frame, origin


def recommendations(pm: float, points: list[dict[str, Any]], threshold: float | None,
                    co2: float | None, learned: bool) -> list[dict[str, str]]:
    result = []
    if threshold is not None and pm >= threshold:
        result.append({"kind": "current_breach", "severity": "warning", "title": "Bụi đang vượt ngưỡng",
                       "detail": "Kiểm tra nguồn bụi và trạng thái quạt. Chế độ AUTO tiếp tục xử lý theo ngưỡng của phòng."})
    elif learned and threshold is not None:
        breach = [p for p in points if p["pm25"] >= threshold]
        if breach:
            earliest = min(breach, key=lambda p: p["horizon_minutes"])
            result.append({"kind": "forecast_breach", "severity": "warning",
                           "title": f"Dự kiến vượt ngưỡng ở mốc +{earliest['horizon_minutes']} phút",
                           "detail": "Kiểm tra hoạt động có thể phát sinh bụi; cân nhắc điều chỉnh quạt qua bảng điều khiển. Đây là dự báo, không phải số đo tương lai."})
    if co2 is not None and pd.notna(co2) and co2 >= 1000:
        result.append({"kind": "ventilation", "severity": "advisory", "title": "Nên kiểm tra thông gió",
                       "detail": f"CO₂ đang khoảng {co2:.0f} ppm, vượt mốc nhắc thông gió 1.000 ppm của dự án. Xem xét thông gió phù hợp; quạt HEPA không xử lý CO₂."})
    if not result:
        result.append({"kind": "monitor", "severity": "info", "title": "Tiếp tục theo dõi",
                       "detail": "Chưa có điều kiện cảnh báo từ số đo và dự báo hiện tại. Giữ AUTO nếu muốn backend điều khiển theo ngưỡng."})
    return result


def predict_run(bundle: Any, rows: list[dict[str, Any]], actuator_rows: list[dict[str, Any]] | None,
                interval_seconds: float, device_id: str, model_version: str,
                threshold_pm25: float | None, profile_version: int | None,
                worker_id: str, now: datetime | None = None,
                fallback_reason: str | None = None) -> dict[str, Any]:
    issued = now or datetime.now(timezone.utc)
    learned = not isinstance(bundle, PersistenceBundle)
    features, frame, origin = build_context(rows, actuator_rows, interval_seconds, issued, require_history=learned)
    row = features.loc[[origin]]
    points = []
    for horizon in HORIZONS:
        value = float(bundle.predict(row, horizon)[0])
        points.append({"horizon_minutes": horizon,
                       "target_at": (origin + pd.Timedelta(minutes=horizon)).isoformat(),
                       "pm25": round(float(min(500.0, max(0.0, value))), 2)})
    recent = frame.loc[(frame.index <= origin) & (frame.index > origin - pd.Timedelta(minutes=60))]
    pm = float(row.iloc[0]["pm_now"])
    co2 = frame.loc[origin, "co2"]
    quality = {
        "feature_version": FEATURE_VERSION, "origin": origin.isoformat(),
        "model_kind": "learned" if learned else "baseline", "pm_at_origin": pm,
        "fallback_reason": fallback_reason,
        "input_buckets": int(len(recent)),
        "coverage_60m": round(float(recent["coverage"].fillna(0).sum()) / 60.0, 3),
        "pm_valid_buckets_60m": int(recent["pm_valid"].sum()),
        "stale_minutes": round((pd.Timestamp(issued) - origin).total_seconds() / 60, 2),
        "recommendations": recommendations(pm, points, threshold_pm25, co2, learned),
    }
    return {"device_id": device_id, "model_version": model_version,
            "input_cutoff": origin.isoformat(), "issued_at": issued.isoformat(),
            "profile_version": profile_version, "threshold_pm25": threshold_pm25,
            "data_quality": quality, "worker_id": worker_id, "points": points}
