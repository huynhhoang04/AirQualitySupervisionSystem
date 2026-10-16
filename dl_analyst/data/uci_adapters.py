import hashlib
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZipFile

import pandas as pd

UCI_381 = {
    "source_id": "uci_beijing_pm25_381",
    "dataset_version": "2017-01-18",
    "license_id": "CC BY 4.0",
    "attribution": "Song Chen. Beijing PM2.5. UCI Machine Learning Repository, 2017. https://doi.org/10.24432/C5JS49.",
    "source_url": "https://archive.ics.uci.edu/dataset/381/beijing+pm2+5+data",
}
UCI_501 = {
    "source_id": "uci_beijing_multisite_501",
    "dataset_version": "2019-09-19",
    "license_id": "CC BY 4.0",
    "attribution": "Chen, S. (2017). Beijing Multi-Site Air Quality [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C5RK5G.",
    "source_url": "https://archive.ics.uci.edu/dataset/501/beijing+pm2+5+data",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonicalize(frame: pd.DataFrame, source: dict, site_id: str, timestamp: pd.Series,
                 pm25: pd.Series, temperature: pd.Series, humidity: pd.Series | None,
                 pressure: pd.Series | None, source_path: Path, retrieved_at: str) -> pd.DataFrame:
    original = pd.to_numeric(pm25, errors="coerce")
    flags = [[] for _ in range(len(frame))]
    for index, value in enumerate(original):
        if pd.isna(value):
            flags[index].append("missing")
        elif value < 0:
            flags[index].append("invalid_range")
        elif value > 500:
            flags[index].append("above_aqss_sensor_valid_range")
        else:
            flags[index].append("source_sensor_quality_unknown")

    canonical = pd.DataFrame({
        "timestamp_utc": pd.to_datetime(timestamp, errors="coerce", utc=True),
        "source_id": source["source_id"],
        "dataset_version": source["dataset_version"],
        "site_id": site_id,
        "sensor_id": None,
        "setting": "ambient_outdoor",
        "latitude": None,
        "longitude": None,
        "pm25_ug_m3": original,
        "pm10_ug_m3": pd.to_numeric(frame["PM10"], errors="coerce") if "PM10" in frame else None,
        "co2_ppm": None,
        "co2_kind": None,
        "temperature_c": pd.to_numeric(temperature, errors="coerce"),
        "humidity_pct": pd.to_numeric(humidity, errors="coerce") if humidity is not None else None,
        "pressure_hpa": pd.to_numeric(pressure, errors="coerce") if pressure is not None else None,
        "original_value": original,
        "original_unit": "ug/m3",
        "quality_flags": flags,
        "license_id": source["license_id"],
        "attribution": source["attribution"],
        "source_url": source["source_url"],
        "retrieved_at_utc": retrieved_at,
        "transform_version": "uci-canonical-v1",
    })
    canonical = canonical.dropna(subset=["timestamp_utc"])
    return canonical.sort_values("timestamp_utc").reset_index(drop=True)


def adapt_uci_381(frame: pd.DataFrame, source_path: Path, retrieved_at: str | None = None) -> pd.DataFrame:
    local = pd.to_datetime(frame[["year", "month", "day", "hour"]], errors="coerce")
    timestamp = local.dt.tz_localize("Asia/Shanghai", ambiguous="NaT", nonexistent="NaT")
    return _canonicalize(
        frame, UCI_381, "us_embassy_beijing", timestamp, frame["pm2.5"], frame["TEMP"],
        None, frame["PRES"], source_path,
        retrieved_at or datetime.now(timezone.utc).isoformat(),
    )


def adapt_uci_501(frame: pd.DataFrame, source_path: Path, retrieved_at: str | None = None) -> pd.DataFrame:
    local = pd.to_datetime(frame[["year", "month", "day", "hour"]], errors="coerce")
    timestamp = local.dt.tz_localize("Asia/Shanghai", ambiguous="NaT", nonexistent="NaT")
    site = str(frame["station"].iloc[0])
    return _canonicalize(
        frame, UCI_501, site, timestamp, frame["PM2.5"], frame["TEMP"],
        None, frame["PRES"], source_path,
        retrieved_at or datetime.now(timezone.utc).isoformat(),
    )


def load_uci_381(archive_path: Path) -> pd.DataFrame:
    with ZipFile(archive_path) as archive:
        csv_name = next(name for name in archive.namelist() if name.lower().endswith(".csv"))
        with archive.open(csv_name) as stream:
            raw = pd.read_csv(stream)
    return adapt_uci_381(raw, archive_path)


def load_uci_501(archive_path: Path) -> dict[str, pd.DataFrame]:
    output = {}
    with ZipFile(archive_path) as outer:
        nested_name = next(name for name in outer.namelist()
                           if name.lower().endswith(".zip") and "20130301" in name)
        nested_bytes = outer.read(nested_name)
    import io
    with ZipFile(io.BytesIO(nested_bytes)) as inner:
        for csv_name in inner.namelist():
            if not csv_name.lower().endswith(".csv"):
                continue
            with inner.open(csv_name) as stream:
                raw = pd.read_csv(stream)
            site_frame = adapt_uci_501(raw, archive_path)
            output[site_frame["site_id"].iloc[0]] = site_frame
    return output


def write_parquet_partitions(frames: dict[str, pd.DataFrame], output_root: Path) -> list[Path]:
    paths = []
    for site_id, frame in frames.items():
        for (year, month), group in frame.groupby([
            frame["timestamp_utc"].dt.year.rename("year"),
            frame["timestamp_utc"].dt.month.rename("month"),
        ]):
            target = output_root / f"source_id={group['source_id'].iloc[0]}" / f"site_id={site_id}" / f"year={year}" / f"month={month:02d}" / "observations.parquet"
            target.parent.mkdir(parents=True, exist_ok=True)
            group.to_parquet(target, index=False)
            paths.append(target)
    return paths
