from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_reports_not_ready_without_artifacts():
    assert client.get("/health").json() == {
        "status": "ok",
        "ready": False,
        "forecast_backend": "joblib",
        "model_versions": {"threshold": None, "dosage": None, "forecast": None},
        "models": {"threshold": False, "dosage": False, "forecast": False},
    }


def test_threshold_returns_unavailable_without_model():
    response = client.post("/api/v1/threshold", json={
        "space_id": "SP_201",
        "volume_m3": 25,
        "purpose": "classroom",
        "region": "hanoi",
        "ventilation_estimate": "low",
        "occupancy_typical": 30,
    })
    assert response.status_code == 503


def test_forecast_requires_exactly_96_ordered_samples():
    now = datetime.now(timezone.utc)
    series = [
        {"timestamp": (now + timedelta(minutes=15 * i)).isoformat(), "pm25": 10}
        for i in range(95)
    ]
    response = client.post("/api/v1/forecast", json={"space_id": "SP_201", "series": series})
    assert response.status_code == 422


def test_forecast_rejects_inconsistent_cadence():
    now = datetime.now(timezone.utc)
    series = [
        {"timestamp": (now + timedelta(minutes=16 * i)).isoformat(), "pm25": 10}
        for i in range(96)
    ]
    response = client.post("/api/v1/forecast", json={"space_id": "SP_201", "series": series})
    assert response.status_code == 422


def test_forecast_returns_unavailable_without_model():
    now = datetime.now(timezone.utc)
    series = [
        {"timestamp": (now + timedelta(minutes=15 * i)).isoformat(), "pm25": 10}
        for i in range(96)
    ]
    response = client.post("/api/v1/forecast", json={"space_id": "SP_201", "series": series})
    assert response.status_code == 503
