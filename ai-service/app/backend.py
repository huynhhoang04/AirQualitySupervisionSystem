"""HTTP client for the backend's public and internal APIs."""

from __future__ import annotations

from typing import Any
from urllib.parse import quote

import httpx

from .config import Settings


class BackendError(RuntimeError):
    pass


class BackendClient:
    def __init__(self, settings: Settings):
        self.settings = settings
        headers = {"Content-Type": "application/json"}
        if settings.worker_token:
            headers["Authorization"] = f"Bearer {settings.worker_token}"
        self._client = httpx.Client(
            base_url=settings.backend_url,
            headers=headers,
            timeout=settings.request_timeout,
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> "BackendClient":
        return self

    def __exit__(self, *args: Any) -> None:
        self.close()

    # ── transport ──────────────────────────────────────────────────────────

    def _get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        response = self._client.get(path, params=params)
        if response.status_code == 204:
            return None
        if response.status_code >= 400:
            raise BackendError(f"GET {path} -> {response.status_code}: {response.text[:300]}")
        return response.json()

    def _post(self, path: str, json: Any = None, params: dict[str, Any] | None = None) -> Any:
        response = self._client.post(path, json=json, params=params)
        if response.status_code == 204:
            return None
        if response.status_code >= 400:
            raise BackendError(f"POST {path} -> {response.status_code}: {response.text[:300]}")
        return response.json()

    # ── public reads ───────────────────────────────────────────────────────

    def devices(self) -> list[dict[str, Any]]:
        return self._get("/api/v1/devices")

    def latest_forecast(self, device_id: str) -> dict[str, Any] | None:
        try:
            return self._get(f"/api/v1/devices/{quote(device_id, safe='')}/forecast")
        except BackendError as error:
            if "404" in str(error):
                return None
            raise

    def models(self, device_id: str) -> list[dict[str, Any]]:
        return self._get("/api/v1/ai/models", params={"device_id": device_id})

    def alerts(self, device_id: str) -> list[dict[str, Any]]:
        return self._get(f"/api/v1/devices/{quote(device_id, safe='')}/alerts")

    def workers_status(self, device_id: str | None = None) -> dict[str, Any]:
        params = {"device_id": device_id} if device_id else None
        return self._get("/api/v1/ai/status", params=params)

    def create_job(self, device_id: str, job_type: str = "train") -> dict[str, Any]:
        return self._post("/api/v1/ai/jobs", {"type": job_type, "device_id": device_id, "params": {}})

    # ── internal API ───────────────────────────────────────────────────────

    def create_snapshot(self, device_id: str, from_at: str, to_at: str) -> dict[str, Any]:
        return self._post("/internal/v1/ai/dataset-snapshots",
                          {"device_id": device_id, "from": from_at, "to": to_at})

    def fetch_telemetry(self, device_id: str, snapshot_id: str, page_size: int = 2000) -> list[dict[str, Any]]:
        return self._fetch_pages(f"/internal/v1/ai/devices/{quote(device_id, safe='')}/telemetry",
                                 snapshot_id, page_size)

    def fetch_actuator(self, device_id: str, snapshot_id: str, page_size: int = 2000) -> list[dict[str, Any]]:
        return self._fetch_pages(
            f"/internal/v1/ai/devices/{quote(device_id, safe='')}/actuator-history",
            snapshot_id, page_size)

    def fetch_events(self, device_id: str, snapshot_id: str, page_size: int = 2000) -> list[dict[str, Any]]:
        return self._fetch_pages(f"/internal/v1/ai/devices/{quote(device_id, safe='')}/events",
                                 snapshot_id, page_size)

    def _fetch_pages(self, path: str, snapshot_id: str, page_size: int) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        cursor = None
        while True:
            params: dict[str, Any] = {"snapshot_id": snapshot_id, "limit": page_size}
            if cursor:
                params["cursor"] = cursor
            page = self._get(path, params=params)
            items.extend(page.get("items", []))
            cursor = page.get("next_cursor")
            if not cursor:
                return items

    def context(self, device_id: str) -> dict[str, Any]:
        return self._get(f"/internal/v1/ai/devices/{quote(device_id, safe='')}/context")

    def recent(self, device_id: str, minutes: int = 90) -> list[dict[str, Any]]:
        return self._get(f"/internal/v1/ai/devices/{quote(device_id, safe='')}/recent",
                         params={"minutes": minutes})

    def recent_actuator(self, device_id: str, minutes: int = 90) -> list[dict[str, Any]]:
        return self._get(f"/internal/v1/ai/devices/{quote(device_id, safe='')}/recent-actuator",
                         params={"minutes": minutes})

    def heartbeat(self, worker_id: str, model_artifact: str | None,
                  device_status: dict[str, Any] | None = None) -> dict[str, Any]:
        return self._post("/internal/v1/ai/workers/heartbeat", {
            "worker_id": worker_id,
            "capabilities": {"features": "feature-v2", "algorithms": ["persistence", "ridge", "boosting"],
                             "device_status": dict(device_status or {})},
            "model_artifact": model_artifact,
        })

    def claim(self, worker_id: str) -> dict[str, Any] | None:
        return self._post("/internal/v1/ai/jobs/claim", params={"worker_id": worker_id})

    def progress(self, job_id: str, lease_token: str, progress: int, phase: str) -> dict[str, Any]:
        return self._post(f"/internal/v1/ai/jobs/{job_id}/progress",
                          {"progress": progress, "phase": phase},
                          params={"lease_token": lease_token})

    def complete(self, job_id: str, lease_token: str, result: dict[str, Any] | None,
                 error: str | None) -> dict[str, Any]:
        return self._post(f"/internal/v1/ai/jobs/{job_id}/complete",
                          {"result": result, "error": error},
                          params={"lease_token": lease_token})

    def register_model(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._post("/internal/v1/ai/models", payload)

    def upload_forecast(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._post("/internal/v1/ai/forecast-runs", payload)

    def create_alert(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self._post("/internal/v1/ai/alerts", payload)

    def resolve_alerts(self, device_id: str, lifecycle: str = "RESOLVED") -> dict[str, Any]:
        return self._post("/internal/v1/ai/alerts/resolve", params={
            "device_id": device_id,
            "lifecycle": lifecycle,
        })
