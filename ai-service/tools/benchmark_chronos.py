"""Benchmark Chronos-2 on this machine: 720 context steps -> 360 forecast steps.

This is the gate the plan (docs/upgrade-plan/07-ai-live-dashboard-v2.md §4) requires before any
fine-tuning decision. It measures what the hardware actually does; it does not tune anything.

Usage:
    .venv-chronos/bin/python tools/benchmark_chronos.py --device cuda
    .venv-chronos/bin/python tools/benchmark_chronos.py --device cpu --iterations 20
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path

import numpy as np
import torch

MODEL = "amazon/chronos-2"


def percentiles(values: list[float]) -> dict[str, float]:
    ordered = sorted(values)
    return {
        "p50": round(statistics.median(ordered), 3),
        "p95": round(ordered[max(0, min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1)))))], 3),
        "min": round(ordered[0], 3),
        "max": round(ordered[-1], 3),
        "mean": round(statistics.fmean(ordered), 3),
    }


def vram_mib(device: str) -> int | None:
    if device != "cuda" or not torch.cuda.is_available():
        return None
    return int(torch.cuda.max_memory_allocated() / 1024**2)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="cuda", choices=["cuda", "cpu"])
    parser.add_argument("--context", type=int, default=720)
    parser.add_argument("--prediction", type=int, default=360)
    parser.add_argument("--iterations", type=int, default=10)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--output", default=None)
    args = parser.parse_args()

    if args.device == "cuda" and not torch.cuda.is_available():
        raise SystemExit("CUDA is not available in this environment")

    from chronos import Chronos2Pipeline

    print(f"loading {MODEL} on {args.device} ...", flush=True)
    load_start = time.perf_counter()
    pipeline = Chronos2Pipeline.from_pretrained(MODEL, device_map=args.device)
    load_seconds = time.perf_counter() - load_start
    model = pipeline.model
    total_params = sum(p.numel() for p in model.parameters())
    print(f"loaded in {load_seconds:.1f}s · {total_params / 1e6:.1f}M parameters", flush=True)

    rng = np.random.default_rng(7)
    # A bounded random walk looks more like a PM trace than white noise and keeps the shape path honest.
    # Chronos-2 expects (n_series, n_variates, history_length).
    steps = rng.normal(0, 0.6, size=(args.batch_size, args.context)).cumsum(axis=1)
    context = np.clip(35.0 + steps, 0.0, 500.0).astype(np.float32)[:, None, :]

    if args.device == "cuda":
        torch.cuda.reset_peak_memory_stats()
        torch.cuda.synchronize()

    for _ in range(args.warmup):
        pipeline.predict(context, prediction_length=args.prediction)
    if args.device == "cuda":
        torch.cuda.synchronize()

    latencies: list[float] = []
    for _ in range(args.iterations):
        start = time.perf_counter()
        forecast = pipeline.predict(context, prediction_length=args.prediction)
        if args.device == "cuda":
            torch.cuda.synchronize()
        latencies.append(time.perf_counter() - start)

    quantiles = forecast[0]
    result = {
        "model": MODEL,
        "device": args.device,
        "device_name": torch.cuda.get_device_name(0) if args.device == "cuda" else "cpu",
        "parameters_millions": round(total_params / 1e6, 1),
        "context_steps": args.context,
        "prediction_steps": args.prediction,
        "batch_size": args.batch_size,
        "iterations": args.iterations,
        "warmup": args.warmup,
        "load_seconds": round(load_seconds, 3),
        "latency_seconds": percentiles(latencies),
        "peak_vram_mib": vram_mib(args.device),
        "output_shape": list(quantiles.shape),
        "quantile_levels": list(pipeline.quantiles),
        "torch": torch.__version__,
        "timed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    print(json.dumps(result, indent=2))

    target_p95 = 3.0
    meets = result["latency_seconds"]["p95"] <= target_p95
    print(f"\nP95 {result['latency_seconds']['p95']}s vs plan target <= {target_p95}s: "
          f"{'PASS' if meets else 'FAIL'}")

    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(f"wrote {path}")
    return 0 if meets else 1


if __name__ == "__main__":
    raise SystemExit(main())
