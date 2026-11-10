"""The three models behind the closed loop (docs/dl.md §3–§5).

ML#1 threshold  : Gaussian process, space profile -> PM2.5 threshold + its own uncertainty.
ML#2 dosage     : XGBoost, space + current level -> fan level and run duration.
DL forecast     : quantile Seq2Seq LSTM over the last 24 h -> next 24 h of q10/q50/q90.

All three are trained on a physics-informed surrogate (`physics.py`), because no public dataset
labels "the PM2.5 level at which this particular room should be ventilated". The surrogate is
explicit: the model approximates latent parameters a room cannot be instrumented for.
"""

from __future__ import annotations

import math

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

PURPOSE_CODES = {"bedroom": 0, "classroom": 1, "office": 2, "living_room": 3, "dorm": 4}
# Regions map onto a climate belt; an unknown name falls back to the mildest belt.
REGION_CODES = {
    "north": 0,
    "central": 1,
    "south": 2,
    "hanoi": 0,
    "hanoi_caugiay": 0,
    "hcmc": 2,
    "danang": 1,
}
VENTILATION_CODES = {"low": 0, "medium": 1, "high": 2, "unknown": 3}

QUANTILES = (0.1, 0.5, 0.9)


def encode_threshold(space_id: str, volume_m3: float, purpose: str, region: str,
                     ventilation_estimate: str | None, occupancy_typical: int) -> np.ndarray:
    purpose_code = PURPOSE_CODES.get(purpose.strip().lower())
    if purpose_code is None:
        raise ValueError(f"unknown purpose: {purpose}")
    region_code = REGION_CODES.get(region.strip().lower(), 1)
    ventilation_code = VENTILATION_CODES.get((ventilation_estimate or "unknown").strip().lower(),
                                             VENTILATION_CODES["unknown"])
    return np.asarray([[volume_m3, purpose_code, region_code, ventilation_code, occupancy_typical]],
                      dtype=np.float64)


def encode_dosage(volume_m3: float, purpose: str, current_pm25: float, base_threshold: float,
                  outdoor_pm25_estimate: float, filter_cadr: float) -> np.ndarray:
    purpose_code = PURPOSE_CODES.get(purpose.strip().lower())
    if purpose_code is None:
        raise ValueError(f"unknown purpose: {purpose}")
    return np.asarray([[volume_m3, purpose_code, current_pm25, base_threshold,
                        outdoor_pm25_estimate, filter_cadr]], dtype=np.float64)


# ── DL forecast ──────────────────────────────────────────────────────────────


class QuantileSeq2Seq(nn.Module):
    """LSTM encoder → additive attention → LSTM decoder → three quantile heads.

    The heads are linear and raw. A ReLU here (the V1 bug) clips valid values and produces a
    degenerate "confidence interval"; monotonicity is enforced by construction instead.
    """

    def __init__(self, in_steps: int = 96, out_steps: int = 96, input_dim: int = 1,
                 hidden_dim: int = 96, num_layers: int = 2, dropout: float = 0.2):
        super().__init__()
        self.out_steps = out_steps
        self.encoder = nn.LSTM(input_dim, hidden_dim, num_layers=num_layers, batch_first=True,
                               dropout=dropout if num_layers > 1 else 0.0)
        self.attention = nn.Linear(hidden_dim, 1)
        self.decoder = nn.LSTM(hidden_dim, hidden_dim, num_layers=num_layers, batch_first=True,
                               dropout=dropout if num_layers > 1 else 0.0)
        self.head = nn.Linear(hidden_dim, 3)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        encoded, _ = self.encoder(x)                      # (B, 96, H)
        weights = F.softmax(self.attention(encoded), dim=1)
        context = torch.sum(weights * encoded, dim=1)     # (B, H)
        repeated = context.unsqueeze(1).repeat(1, self.out_steps, 1)
        decoded, _ = self.decoder(repeated)
        raw = self.head(decoded)                          # (B, 96, 3)

        q50 = raw[..., 1]
        q10 = torch.minimum(raw[..., 0], q50)
        q90 = torch.maximum(raw[..., 2], q50)
        return torch.stack([q10, q50, q90], dim=-1)


def quantile_loss(prediction: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    """Pinball loss summed over the three quantiles (docs/dl.md §5.4)."""
    total = prediction.new_zeros(())
    for index, tau in enumerate(QUANTILES):
        error = target - prediction[..., index]
        total = total + torch.maximum(tau * error, (tau - 1.0) * error).mean()
    return total


# ── Baselines (a model that cannot beat "repeat the last value" is not worth deploying) ──


class PersistenceBaseline:
    model_version = "baseline-persistence-v1"

    def predict(self, context: np.ndarray, horizon: int) -> np.ndarray:
        values = np.asarray(context, dtype=np.float32).reshape(-1)
        level = float(values[-1])
        spread = float(np.median(np.abs(np.diff(values)))) if values.size > 1 else 0.0
        sigma = max(spread, 1.0) * np.sqrt(np.arange(1, horizon + 1, dtype=np.float32))
        quantiles = np.column_stack((level - 1.2816 * sigma, np.full(horizon, level),
                                     level + 1.2816 * sigma))
        return monotone(np.clip(quantiles, 0, 500))


class SeasonalNaiveBaseline:
    def __init__(self, season_length: int = 96):
        self.season_length = season_length
        self.model_version = f"baseline-seasonal-naive-{season_length}v1"

    def predict(self, context: np.ndarray, horizon: int) -> np.ndarray:
        values = np.asarray(context, dtype=np.float32).reshape(-1)
        if values.size < self.season_length:
            raise ValueError("context shorter than one season")
        level = np.resize(values[-self.season_length:], horizon)
        spread = float(np.median(np.abs(np.diff(values))))
        sigma = max(spread, 1.0) * np.sqrt(np.arange(1, horizon + 1, dtype=np.float32))
        quantiles = np.column_stack((level - 1.2816 * sigma, level, level + 1.2816 * sigma))
        return monotone(np.clip(quantiles, 0, 500))


def monotone(quantiles: np.ndarray) -> np.ndarray:
    """q10 ≤ q50 ≤ q90, column-wise, and non-negative."""
    q50 = quantiles[:, 1]
    return np.column_stack((np.minimum(quantiles[:, 0], q50), q50,
                            np.maximum(quantiles[:, 2], q50)))


def pinball(actual: np.ndarray, predicted: np.ndarray, tau: float) -> float:
    error = actual - predicted
    return float(np.mean(np.maximum(tau * error, (tau - 1.0) * error)))


def score(actual: np.ndarray, quantiles: np.ndarray) -> dict[str, float]:
    median = quantiles[:, 1]
    return {
        "mae": float(np.mean(np.abs(actual - median))),
        "rmse": float(np.sqrt(np.mean((actual - median) ** 2))),
        "pinball_q10": pinball(actual, quantiles[:, 0], 0.1),
        "pinball_q50": pinball(actual, quantiles[:, 1], 0.5),
        "pinball_q90": pinball(actual, quantiles[:, 2], 0.9),
        "coverage_80": float(np.mean((actual >= quantiles[:, 0]) & (actual <= quantiles[:, 2]))),
        "mean_q90_minus_q10": float(np.mean(quantiles[:, 2] - quantiles[:, 0])),
    }


def mape(actual: np.ndarray, predicted: np.ndarray) -> float:
    mask = actual > 1e-6
    if not mask.any():
        return math.nan
    return float(np.mean(np.abs((actual[mask] - predicted[mask]) / actual[mask])) * 100.0)
