"""Evaluate a frozen Chronos-2 checkpoint plus its LoRA adapter on the held-out split.

Plan 08 §9: the test split is touched once, for the frozen candidate and the baselines, and the
report states sample counts, coverage and the source of every number.

Usage:
    PYTHONPATH=. .venv-chronos/bin/python tools/evaluate_chronos.py \\
        --data data/synthetic --model models/chronos-lora --output reports/chronos-eval
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from tools.train_chronos import evaluate, load_split, persistence, windows_for


def load_tuned(base_model: str, adapter_dir: Path, device: str) -> Any:
    """
    Load the base checkpoint and attach the saved adapter.

    The pipeline's `from_pretrained` takes either a full checkpoint or a directory containing an
    adapter; passing the trainer output keeps the manifest's `adapter.path` reproducible.
    """
    from chronos import Chronos2Pipeline

    path = adapter_dir
    if not (adapter_dir / "adapter_config.json").exists():
        candidate = adapter_dir / "trainer" / "adapter"
        if candidate.exists():
            path = candidate
        else:
            raise SystemExit(f"no adapter_config.json under {adapter_dir}")
    return Chronos2Pipeline.from_pretrained(str(path), device_map=device)


def per_episode(pipeline: Any, samples: list[dict[str, Any]]) -> dict[str, Any]:
    """Worst-case episodes matter more than the average when a room is the unit of trust."""
    grouped: dict[str, list[dict[str, Any]]] = {}
    for sample in samples:
        grouped.setdefault(sample["episode_id"], []).append(sample)
    result = {}
    for episode, group in grouped.items():
        result[episode] = evaluate(pipeline, group)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default="data/synthetic")
    parser.add_argument("--model", default="models/chronos-lora")
    parser.add_argument("--base", default="amazon/chronos-2")
    parser.add_argument("--output", default="reports/chronos-eval")
    parser.add_argument("--sessions", type=int, default=30)
    parser.add_argument("--device", default="cuda")
    args = parser.parse_args()

    started = time.time()
    root = Path(args.data)
    test_frame = load_split(root, "test")
    samples = windows_for(test_frame, args.sessions, 120)
    if not samples:
        raise SystemExit("no ready test windows")
    print(f"test windows: {len(samples)} from {test_frame['episode_id'].nunique()} sessions", flush=True)

    from chronos import Chronos2Pipeline

    base = Chronos2Pipeline.from_pretrained(args.base, device_map=args.device)
    pretrained = evaluate(base, samples)
    print("pretrained:", json.dumps(pretrained), flush=True)

    tuned_path = Path(args.model)
    result: dict[str, Any] = {
        "data": str(root),
        "model_dir": str(tuned_path),
        "test_windows": len(samples),
        "test_sessions": int(test_frame["episode_id"].nunique()),
        "test_rows": int(len(test_frame)),
        "baselines": {"pretrained": pretrained, "persistence": persistence(samples)},
    }

    adapter = tuned_path / "adapter"
    if not adapter.exists():
        adapter = tuned_path / "trainer" / "adapter"
    if adapter.exists():
        tuned = load_tuned(args.base, tuned_path, args.device)
        fine_tuned = evaluate(tuned, samples)
        result["fine_tuned"] = fine_tuned
        result["per_episode_fine_tuned"] = per_episode(tuned, samples)

        # Report the comparison in the honest direction: gain on 15/30, regression check on 5.
        base_score = np.mean([pretrained["mae_15min"]["mae"], pretrained["mae_30min"]["mae"]])
        tuned_score = np.mean([fine_tuned["mae_15min"]["mae"], fine_tuned["mae_30min"]["mae"]])
        result["comparison"] = {
            "mean_mae_15_30_pretrained": round(float(base_score), 4),
            "mean_mae_15_30_fine_tuned": round(float(tuned_score), 4),
            "gain_pct": round(float(100.0 * (base_score - tuned_score) / base_score), 2)
            if base_score else None,
            "mae_5_regression_pct": round(
                float(100.0 * (fine_tuned["mae_5min"]["mae"] / pretrained["mae_5min"]["mae"] - 1.0)), 2
            ) if pretrained["mae_5min"]["mae"] else None,
            "note": "synthetic test split only; real-device accuracy is not implied",
        }
    else:
        result["fine_tuned"] = None
        result["comparison"] = {"note": "no adapter found; only baselines were evaluated"}

    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    result["elapsed_seconds"] = round(time.time() - started, 1)
    (output / "evaluation.json").write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("comparison", "fine_tuned") if key in result},
                     indent=2, default=str))
    print(f"wrote {output / 'evaluation.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
