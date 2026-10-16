import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

import requests

API_ROOT = "https://api.openaq.org/v3"


def get_json(path: str, params: dict | None = None, api_key: str | None = None) -> dict:
    key = api_key or os.getenv("OPENAQ_API_KEY")
    if not key:
        raise RuntimeError("Set OPENAQ_API_KEY to use the registered OpenAQ API")
    response = requests.get(
        f"{API_ROOT}/{path.lstrip('/')}",
        params=params,
        headers={"X-API-Key": key},
        timeout=30,
    )
    response.raise_for_status()
    return response.json()


def discover_vietnam_pm25(api_key: str | None = None) -> list[dict]:
    limit = 1000
    page = 1
    locations = []
    while True:
        payload = get_json("locations", {
            "iso": "VN",
            "parameters_id": 2,
            "limit": limit,
            "page": page,
        }, api_key)
        locations.extend(payload.get("results", []))
        found = payload.get("meta", {}).get("found")
        if isinstance(found, int):
            if len(locations) >= found:
                break
        elif len(payload.get("results", [])) < limit:
            break
        page += 1

    inventory = []
    seen = set()
    for location in locations:
        for sensor in location.get("sensors", []):
            parameter = sensor.get("parameter", {})
            sensor_id = sensor.get("id")
            if str(parameter.get("name", "")).lower() != "pm25" or sensor_id in seen:
                continue
            seen.add(sensor_id)
            details = get_json(f"sensors/{sensor_id}", api_key=api_key).get("results", [{}])[0]
            inventory.append({
                "location_id": location.get("id"),
                "location_name": location.get("name"),
                "locality": location.get("locality"),
                "timezone": location.get("timezone"),
                "coordinates": location.get("coordinates"),
                "sensor_id": sensor_id,
                "parameter": parameter,
                "provider": location.get("provider"),
                "owner": location.get("owner"),
                "licenses": location.get("licenses", []),
                "datetime_first": details.get("datetimeFirst"),
                "datetime_last": details.get("datetimeLast"),
                "coverage": details.get("coverage"),
                "summary": details.get("summary"),
            })
    return inventory


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("data/reports/openaq_vietnam_inventory.json"))
    args = parser.parse_args()
    sensors = discover_vietnam_pm25()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "source": "OpenAQ API v3",
        "country": "VN",
        "parameter": "pm25",
        "retrieved_at_utc": datetime.now(timezone.utc).isoformat(),
        "sensor_count": len(sensors),
        "sensors": sensors,
        "note": "Review each underlying provider license and attribution before measurement download or redistribution.",
    }
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"sensor_count": len(sensors), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
