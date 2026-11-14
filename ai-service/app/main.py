"""Diagnostic surface. The worker is the real process; this is only loopback health."""

from __future__ import annotations

import json
import logging
from pathlib import Path

from fastapi import FastAPI

from .config import Settings

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

settings = Settings.from_env()
app = FastAPI(title="AI service — Air Quality Supervision System", version="1.0.0")


@app.get("/health")
def health() -> dict:
    pointers = []
    for path in sorted(settings.artifact_dir.glob("*/current.json")):
        try:
            pointers.append(json.loads(Path(path).read_text(encoding="utf-8")))
        except Exception:  # noqa: BLE001
            pointers.append({"artifact": str(path), "error": "unreadable"})
    return {
        "status": "ok",
        "worker_id": settings.worker_id,
        "backend_url": settings.backend_url,
        "artifact_dir": str(settings.artifact_dir),
        "models": pointers,
    }


def main() -> None:
    import uvicorn

    uvicorn.run("app.main:app", host="127.0.0.1", port=8001, reload=False)
