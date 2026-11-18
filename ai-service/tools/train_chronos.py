"""Fine-tune Chronos-2 with LoRA on the synthetic sessions.

Protocol: docs/upgrade-plan/08 §7-§8.

* inputs are whole 720 -> 360 step windows on the 5 s grid;
* splits are by *session*, never by overlapping window;
* the test split is scored once, for the frozen candidate and the baselines;
* a checkpoint is chosen on validation only;
* the run writes a manifest with seed, config, code revision, row counts and metrics.

Usage:
    .venv-chronos/bin/python tools/train_chronos.py --data data/synthetic \\
        --output models/chronos-lora --steps 20            # smoke
    .venv-chronos/bin/python tools/train_chronos.py --data data/synthetic \\
        --output models/chronos-lora --steps 500
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import subprocess
import time
from pathlib import Path
from typing import Any, Iterator

import numpy as np
import pandas as pd
import pyarrow.parquet as pq
import torch

from app.chronos_model import (
    CONTEXT_STEPS,
    MODEL_ID,
    PREDICTION_STEPS,
    STEP_SECONDS,
    ChronosContext,
    build_context,
    ensure_ready,
)


def code_revision() -> str | None:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    except Exception:  # noqa: BLE001 — the manifest is still useful outside a git checkout
        return None


def load_split(root: Path, split: str) -> pd.DataFrame:
    files = [f for f in sorted(glob.glob(str(root / split / "*.parquet")))
             if "_debug" not in f]
    if not files:
        raise SystemExit(f"no parquet files under {root / split}")
    frames = []
    for file in files:
        columns = ["episode_id", "timestamp", "received_at", "pm25", "co2", "temperature",
                   "humidity", "fan_level", "pm25_saturated", "dust_fault", "scenario"]
        table = pq.read_table(file, columns=columns)
        frames.append(table.to_pandas())
    frame = pd.concat(frames, ignore_index=True)
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], utc=True)
    frame["received_at"] = pd.to_datetime(frame["received_at"], utc=True)
    return frame


def windows_for(frame: pd.DataFrame, limit: int | None, stride: int) -> list[dict[str, Any]]:
    """
    Whole 720 -> 360 windows, one per session, at most `limit` of them.

    The window is built by `build_context` so training and inference share the same grid logic
    (as-of alignment, fault masking, no interpolation).
    """
    rng = np.random.default_rng(11)
    samples: list[dict[str, Any]] = []
    usable: dict[str, pd.Timestamp] = {}
    for episode, group in frame.groupby("episode_id"):
        ordered = group.sort_values("timestamp")
        # Cut the session so the window still has 360 steps of real target afterwards.
        last = ordered["timestamp"].iloc[-1] - pd.Timedelta(seconds=STEP_SECONDS * PREDICTION_STEPS)
        if last <= ordered["timestamp"].iloc[0]:
            continue
        usable[episode] = last

    episodes = sorted(usable)
    if limit is not None:
        rng.shuffle(episodes)
        episodes = episodes[:limit]

    for episode in episodes:
        group = frame[frame["episode_id"] == episode].sort_values("timestamp")
        last = usable[episode]
        first = group["timestamp"].iloc[0] + pd.Timedelta(seconds=STEP_SECONDS * (CONTEXT_STEPS - 1))
        starts = []
        at = first
        while at <= last:
            starts.append(at)
            at = at + pd.Timedelta(seconds=STEP_SECONDS * stride)
        # One to three windows per session keeps sessions balanced without overlapping heavily.
        chosen = starts[:: max(1, len(starts) // 3)][:3] if len(starts) > 3 else starts[:1]
        for anchor_at in chosen:
            issued = anchor_at + pd.Timedelta(seconds=STEP_SECONDS)
            window = group[group["timestamp"] <= anchor_at]
            rows = window.rename(columns={"timestamp": "recorded_at"})[
                ["recorded_at", "received_at", "pm25", "co2", "temperature", "humidity",
                 "fan_level", "pm25_saturated", "dust_fault"]
            ].to_dict("records")
            try:
                context = build_context(rows, now=pd.Timestamp(issued), require_fresh=False)
                ensure_ready(context)
            except Exception:  # noqa: BLE001 — a window that cannot be ready is not a training row
                continue
            future = group[(group["timestamp"] > anchor_at)
                           & (group["timestamp"] <= anchor_at
                              + pd.Timedelta(seconds=STEP_SECONDS * PREDICTION_STEPS))]
            target = future["pm25"].to_numpy(dtype=np.float32)
            if len(target) < PREDICTION_STEPS:
                continue
            samples.append(
                {
                    "episode_id": episode,
                    "anchor_at": context.anchor_at,
                    "context": context,
                    "target": target[:PREDICTION_STEPS],
                }
            )
    return samples


def to_chronos_inputs(samples: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    Each training sample is one series whose *target* is context + future.

    Chronos-2 fine-tuning learns to continue a series, so the future values are part of `target`;
    past covariates cover only the context part, which matches how the model is used at inference
    (no future fan/CO2 is ever supplied).
    """
    prepared = []
    for sample in samples:
        context: ChronosContext = sample["context"]
        series = np.concatenate([context.target.astype(np.float32), sample["target"]])
        entry: dict[str, Any] = {"target": series.reshape(1, -1)}
        covariates = {name: values.astype(np.float32)
                      for name, values in context.covariates.items()
                      if np.isfinite(values).any()}
        if covariates:
            # Pad to the full series length; the future part stays NaN, which Chronos treats as
            # "unknown", exactly like inference.
            padded = {
                name: np.concatenate([values, np.full(len(sample["target"]), np.nan)])
                for name, values in covariates.items()
            }
            entry["past_covariates"] = padded
        prepared.append(entry)
    return prepared


def evaluate(pipeline: Any, samples: list[dict[str, Any]], batch_size: int = 8) -> dict[str, Any]:
    """MAE/RMSE at the three evaluation points plus the whole path, for one frozen model."""
    errors = {5: [], 15: [], 30: [], "path": []}
    for sample in samples:
        context: ChronosContext = sample["context"]
        inputs = [{
            "target": context.target.astype(np.float32).reshape(1, -1),
            **({"past_covariates": {name: values.astype(np.float32)
                                    for name, values in context.covariates.items()
                                    if np.isfinite(values).any()}}
               if any(np.isfinite(v).any() for v in context.covariates.values()) else {}),
        }]
        tensor = pipeline.predict(inputs, prediction_length=PREDICTION_STEPS,
                                  context_length=CONTEXT_STEPS)[0]
        values = tensor.detach().cpu().numpy() if hasattr(tensor, "detach") else np.asarray(tensor)
        while values.ndim > 2:
            values = values[0]
        levels = list(getattr(pipeline, "quantiles", [0.5]))
        median = values[min(range(len(levels)), key=lambda i: abs(levels[i] - 0.5))]
        median = np.clip(median, 0.0, 500.0)
        actual = sample["target"]
        # A window may contain a gap in the *future* PM trace; that target is unknown, not zero.
        # Averaging NaN into the path metric would silently invalidate the whole number.
        valid = np.isfinite(actual)
        errors["path"].extend(np.abs(median[valid] - actual[valid]).tolist())
        for horizon in (5, 15, 30):
            index = int(horizon * 60 / STEP_SECONDS) - 1
            if valid[index]:
                errors[horizon].append(abs(float(median[index]) - float(actual[index])))

    def summarize(values: list[float]) -> dict[str, float] | None:
        if not values:
            return None
        array = np.asarray(values, dtype=float)
        return {"mae": round(float(array.mean()), 4),
                "rmse": round(float(np.sqrt(np.mean(array**2))), 4),
                "samples": int(len(array))}

    return {
        "mae_5min": summarize(errors[5]),
        "mae_15min": summarize(errors[15]),
        "mae_30min": summarize(errors[30]),
        "path": summarize(errors["path"]),
    }


def persistence(samples: list[dict[str, Any]]) -> dict[str, Any]:
    """The honest baseline: keep the last measured value for all 360 steps."""
    errors = {5: [], 15: [], 30: [], "path": []}
    for sample in samples:
        context: ChronosContext = sample["context"]
        flat = np.full(PREDICTION_STEPS, context.anchor_pm25)
        actual = sample["target"]
        valid = np.isfinite(actual)
        errors["path"].extend(np.abs(flat[valid] - actual[valid]).tolist())
        for horizon in (5, 15, 30):
            index = int(horizon * 60 / STEP_SECONDS) - 1
            if valid[index]:
                errors[horizon].append(abs(float(flat[index]) - float(actual[index])))
    return {
        "mae_5min": _summary(errors[5]),
        "mae_15min": _summary(errors[15]),
        "mae_30min": _summary(errors[30]),
        "path": _summary(errors["path"]),
    }


def _summary(values: list[float]) -> dict[str, float] | None:
    if not values:
        return None
    array = np.asarray(values, dtype=float)
    return {"mae": round(float(array.mean()), 4),
            "rmse": round(float(np.sqrt(np.mean(array**2))), 4),
            "samples": int(len(array))}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data/synthetic")
    parser.add_argument("--output", default="models/chronos-lora")
    parser.add_argument("--steps", type=int, default=500)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--gradient-accumulation", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=1e-5)
    parser.add_argument("--lora-rank", type=int, default=8)
    parser.add_argument("--lora-alpha", type=int, default=16)
    parser.add_argument("--context", type=int, default=CONTEXT_STEPS)
    parser.add_argument("--prediction", type=int, default=PREDICTION_STEPS)
    parser.add_argument("--train-sessions", type=int, default=None)
    parser.add_argument("--eval-sessions", type=int, default=6)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--seed", type=int, default=20261114)
    parser.add_argument("--stride", type=int, default=12)
    parser.add_argument("--skip-train", action="store_true",
                        help="Only evaluate the pretrained checkpoint and the baselines.")
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    started = time.time()

    print("loading splits ...", flush=True)
    train_frame = load_split(Path(args.data), "train")
    validation_frame = load_split(Path(args.data), "validation") if (Path(args.data) / "validation").exists() else None
    test_frame = load_split(Path(args.data), "test")
    print(f"rows: train={len(train_frame):,} test={len(test_frame):,} "
          f"validation={0 if validation_frame is None else len(validation_frame):,}", flush=True)

    train_samples = windows_for(train_frame, args.train_sessions, args.stride)
    test_samples = windows_for(test_frame, args.eval_sessions, 10 * args.stride)
    validation_samples = (
        windows_for(validation_frame, max(2, args.eval_sessions // 2), 10 * args.stride)
        if validation_frame is not None else []
    )
    print(f"windows: train={len(train_samples)} validation={len(validation_samples)} test={len(test_samples)}",
          flush=True)
    if not train_samples or not test_samples:
        raise SystemExit("not enough ready windows; generate more sessions first")

    from chronos import Chronos2Pipeline

    print(f"loading {MODEL_ID} ...", flush=True)
    pipeline = Chronos2Pipeline.from_pretrained(MODEL_ID, device_map=args.device)

    pretrained_test = evaluate(pipeline, test_samples)
    persistence_test = persistence(test_samples)
    print("pretrained test:", json.dumps(pretrained_test), flush=True)
    print("persistence test:", json.dumps(persistence_test), flush=True)

    result: dict[str, Any] = {
        "model_id": MODEL_ID,
        "device": args.device,
        "context_steps": args.context,
        "prediction_steps": args.prediction,
        "step_seconds": STEP_SECONDS,
        "seed": args.seed,
        "windows": {"train": len(train_samples), "validation": len(validation_samples),
                    "test": len(test_samples)},
        "sessions": {"train": int(train_frame["episode_id"].nunique()),
                     "test": int(test_frame["episode_id"].nunique()),
                     "validation": 0 if validation_frame is None
                     else int(validation_frame["episode_id"].nunique())},
        "rows": {"train": int(len(train_frame)), "test": int(len(test_frame))},
        "baselines": {"persistence": persistence_test, "pretrained": pretrained_test},
    }

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    if not args.skip_train:
        inputs = to_chronos_inputs(train_samples)
        validation_inputs = to_chronos_inputs(validation_samples) if validation_samples else None
        print(f"fine-tuning LoRA r={args.lora_rank} alpha={args.lora_alpha} steps={args.steps} ...",
              flush=True)
        started_training = time.perf_counter()
        from peft import LoraConfig

        # Chronos-2 manages save/eval strategy itself when validation is supplied (it sets
        # save_strategy=steps, eval_steps=100 and load_best_model_at_end). Passing our own values
        # makes TrainingArguments reject the combination, so only training knobs go here.
        tuned = pipeline.fit(
            inputs,
            prediction_length=args.prediction,
            validation_inputs=validation_inputs,
            finetune_mode="lora",
            lora_config=LoraConfig(r=args.lora_rank, lora_alpha=args.lora_alpha,
                                   lora_dropout=0.05, bias="none"),
            context_length=args.context,
            learning_rate=args.learning_rate,
            num_steps=args.steps,
            batch_size=max(1, args.batch_size // max(1, args.gradient_accumulation)),
            output_dir=output / "trainer",
            finetuned_ckpt_name="adapter",
            disable_data_parallel=True,
            per_device_train_batch_size=max(1, args.batch_size // max(1, args.gradient_accumulation)),
            gradient_accumulation_steps=args.gradient_accumulation,
            logging_steps=max(1, args.steps // 10),
            report_to=[],
        )
        training_seconds = time.perf_counter() - started_training

        # The pipeline saves the fine-tuned checkpoint under output_dir/<finetuned_ckpt_name>.
        adapter_dir = output / "trainer" / "adapter"
        trainable = sum(p.numel() for p in tuned.model.parameters() if p.requires_grad)
        total = sum(p.numel() for p in tuned.model.parameters())
        tuned_test = evaluate(tuned, test_samples)
        tuned_validation = evaluate(tuned, validation_samples) if validation_samples else None
        pretrained_validation = evaluate(pipeline, validation_samples) if validation_samples else None

        gain = None
        if tuned_validation and pretrained_validation:
            base = np.mean([pretrained_validation["mae_15min"]["mae"],
                            pretrained_validation["mae_30min"]["mae"]])
            candidate = np.mean([tuned_validation["mae_15min"]["mae"],
                                 tuned_validation["mae_30min"]["mae"]])
            gain = round(100.0 * (base - candidate) / base, 2) if base else None

        result.update(
            {
                "adapter": {
                    "path": str(adapter_dir),
                    "sha256": sha256(adapter_dir / "adapter_model.safetensors")
                    if (adapter_dir / "adapter_model.safetensors").exists() else None,
                    "lora_rank": args.lora_rank,
                    "lora_alpha": args.lora_alpha,
                    "steps": args.steps,
                    "learning_rate": args.learning_rate,
                    "batch_size": args.batch_size,
                    "gradient_accumulation": args.gradient_accumulation,
                    "trainable_parameters": int(trainable),
                    "total_parameters": int(total),
                    "trainable_percent": round(100.0 * trainable / total, 3),
                    "training_seconds": round(training_seconds, 1),
                },
                "validation": {"fine_tuned": tuned_validation, "pretrained": pretrained_validation,
                               "mae_15_30_gain_pct": gain},
                "test": {"fine_tuned": tuned_test, "pretrained": pretrained_test,
                         "persistence": persistence_test},
            }
        )
        # The gate reads `validation`, so it must run after the update above.
        result["promotion"] = promotion(result)

    result["elapsed_seconds"] = round(time.time() - started, 1)
    result["code_revision"] = code_revision()
    result["torch"] = torch.__version__
    result["cuda"] = torch.cuda.is_available()
    (output / "manifest.json").write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    print(json.dumps(result, indent=2, default=str))
    print(f"wrote {output / 'manifest.json'}")
    return 0


def promotion(result: dict[str, Any]) -> dict[str, Any]:
    """The gate from plan 08 §8: validation only, test is reported afterwards."""
    validation = result.get("validation") or {}
    tuned = validation.get("fine_tuned")
    base = validation.get("pretrained")
    if not tuned or not base:
        return {"status": "NOT_EVALUATED", "reason": "no validation split in this run"}
    candidate = np.mean([tuned["mae_15min"]["mae"], tuned["mae_30min"]["mae"]])
    baseline = np.mean([base["mae_15min"]["mae"], base["mae_30min"]["mae"]])
    gain = 100.0 * (baseline - candidate) / baseline if baseline else 0.0
    horizon_5_ok = tuned["mae_5min"]["mae"] <= base["mae_5min"]["mae"] * 1.05
    if gain >= 10.0 and horizon_5_ok:
        return {"status": "CANDIDATE", "reason":
                f"validation mean MAE(15,30) improved {gain:.1f}%, MAE5 within 5%"}
    if not horizon_5_ok:
        return {"status": "REJECTED", "reason":
                f"MAE5 regressed more than 5% (gain {gain:.1f}% on 15/30)"}
    return {"status": "REJECTED", "reason": f"validation gain {gain:.1f}% < 10%"}


if __name__ == "__main__":
    raise SystemExit(main())
