import argparse
import json
from pathlib import Path

import pandas as pd


def audit(path: Path, timestamp_column: str, value_column: str) -> dict:
    frame = pd.read_csv(path)
    required = {timestamp_column, value_column}
    missing_columns = sorted(required - set(frame.columns))
    if missing_columns:
        raise ValueError(f"missing required columns: {missing_columns}")

    timestamps = pd.to_datetime(frame[timestamp_column], utc=True, errors="coerce")
    values = pd.to_numeric(frame[value_column], errors="coerce")
    valid = timestamps.notna() & values.notna()
    valid_values = values[valid]
    cadence = timestamps[valid].sort_values().diff().dropna().dt.total_seconds() / 60
    return {
        "source_file": path.name,
        "rows": int(len(frame)),
        "invalid_timestamp_or_value": int((~valid).sum()),
        "duplicate_timestamps": int(timestamps[valid].duplicated().sum()),
        "timestamp_start_utc": timestamps[valid].min().isoformat() if valid.any() else None,
        "timestamp_end_utc": timestamps[valid].max().isoformat() if valid.any() else None,
        "value_min": float(valid_values.min()) if not valid_values.empty else None,
        "value_median": float(valid_values.median()) if not valid_values.empty else None,
        "value_max": float(valid_values.max()) if not valid_values.empty else None,
        "negative_values": int((valid_values < 0).sum()),
        "above_500_values": int((valid_values > 500).sum()),
        "median_cadence_minutes": float(cadence.median()) if not cadence.empty else None,
        "missing_by_column": {name: int(count) for name, count in frame.isna().sum().items()},
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument("--timestamp-column", default="timestamp")
    parser.add_argument("--value-column", default="pm25")
    parser.add_argument("--output", type=Path, default=Path("data/reports/dataset_audit.json"))
    args = parser.parse_args()
    report = audit(args.csv, args.timestamp_column, args.value_column)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
