import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from data.uci_adapters import load_uci_381, load_uci_501, sha256, write_parquet_partitions


def _summary(source_id: str, frame, raw_path: Path, license_id: str, attribution: str) -> dict:
    pm = frame["pm25_ug_m3"]
    times = frame["timestamp_utc"]
    cadence = times.sort_values().diff().dropna().dt.total_seconds() / 3600
    missing = int(pm.isna().sum())
    return {
        "source_id": source_id,
        "file": raw_path.name,
        "sha256": sha256(raw_path),
        "license": license_id,
        "attribution": attribution,
        "rows": int(len(frame)),
        "sites": sorted(frame["site_id"].unique().tolist()),
        "timestamp_start_utc": times.min().isoformat(),
        "timestamp_end_utc": times.max().isoformat(),
        "median_cadence_hours": float(cadence.median()),
        "missing_pm25": missing,
        "missing_pm25_pct": 100 * missing / len(frame),
        "pm25_min": float(pm.min()),
        "pm25_median": float(pm.median()),
        "pm25_max": float(pm.max()),
        "above_aqss_valid_range_count": int((pm > 500).sum()),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", type=Path, default=Path("data/raw"))
    parser.add_argument("--output-dir", type=Path, default=Path("data/processed/canonical"))
    parser.add_argument("--report", type=Path, default=Path("data/reports/uci_inventory.json"))
    args = parser.parse_args()
    retrieved = datetime.now(timezone.utc).isoformat()
    archive_381 = args.raw_dir / "uci_beijing_381.zip"
    archive_501 = args.raw_dir / "uci_beijing_multisite_501.zip"
    frame_381 = load_uci_381(archive_381)
    frames_501 = load_uci_501(archive_501)
    output_paths = write_parquet_partitions({"us_embassy_beijing": frame_381, **frames_501}, args.output_dir)
    report = {
        "retrieved_at_utc": retrieved,
        "notes": [
            "Both sources are ambient outdoor and are not AQSS indoor ground truth.",
            "Original observations are hourly; no 15-minute data was synthesized.",
            "Beijing local timestamps were localized as Asia/Shanghai then stored in UTC.",
            "PM2.5 above 500 ug/m3 is retained and flagged because it is source data outside AQSS sensor-valid range.",
        ],
        "datasets": [
            _summary("uci_beijing_pm25_381", frame_381, archive_381, "CC BY 4.0",
                     "Song Chen. Beijing PM2.5. UCI Machine Learning Repository, 2017. https://doi.org/10.24432/C5JS49."),
            *[_summary("uci_beijing_multisite_501", frame, archive_501, "CC BY 4.0",
                       "Chen, S. (2017). Beijing Multi-Site Air Quality [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C5RK5G.")
              for frame in frames_501.values()],
        ],
        "canonical_parquet_files": [str(path) for path in output_paths],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"datasets": len(report["datasets"]), "parquet_files": len(output_paths), "report": str(args.report)}))


if __name__ == "__main__":
    main()
