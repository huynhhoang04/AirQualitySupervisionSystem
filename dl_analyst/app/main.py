from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from app.inference import ModelRegistry
from app.schemas import (
    DosageRequest,
    DosageResponse,
    ForecastPoint,
    ForecastRequest,
    ForecastResponse,
    ThresholdRequest,
    ThresholdResponse,
)

app = FastAPI(title="AQSS Layer 3 AI", version="0.1.0")
models = ModelRegistry()


@app.exception_handler(ValueError)
async def invalid_model_input(_, error: ValueError):
    return JSONResponse(status_code=422, content={"detail": str(error)})


@app.get("/health")
def health():
    return {
        "status": "ok",
        "ready": models.ready,
        "forecast_backend": models.forecast_backend,
        "model_versions": {
            "threshold": models.artifacts.get("threshold", {}).get("model_version"),
            "dosage": models.artifacts.get("dosage", {}).get("model_version"),
            "forecast": models.forecast_version,
        },
        "models": models.model_status,
    }


@app.post("/api/v1/threshold", response_model=ThresholdResponse)
def threshold(request: ThresholdRequest):
    prediction = models.threshold(request)
    if prediction is None:
        raise HTTPException(status_code=503, detail="threshold model is not available")
    return prediction


@app.post("/api/v1/dosage", response_model=DosageResponse)
def dosage(request: DosageRequest):
    prediction = models.dosage(request)
    if prediction is None:
        raise HTTPException(status_code=503, detail="dosage model is not available")
    return prediction


@app.post("/api/v1/forecast", response_model=ForecastResponse)
def forecast(request: ForecastRequest):
    prediction = models.forecast(request)
    if prediction is None:
        raise HTTPException(status_code=503, detail="forecast model is not available")
    return prediction
