import pytest

from data import openaq


def test_openaq_requires_registered_api_key(monkeypatch):
    monkeypatch.delenv("OPENAQ_API_KEY", raising=False)
    with pytest.raises(RuntimeError, match="OPENAQ_API_KEY"):
        openaq.discover_vietnam_pm25()


def test_openaq_discovery_uses_vietnam_pm25_filters_and_sensor_details(monkeypatch):
    calls = []

    def fake_get_json(path, params=None, api_key=None):
        calls.append((path, params))
        if path == "locations":
            return {
                "meta": {"found": 1},
                "results": [{
                    "id": 7,
                    "name": "Hanoi",
                    "locality": "Hanoi",
                    "timezone": "Asia/Ho_Chi_Minh",
                    "coordinates": {"latitude": 21.0, "longitude": 105.8},
                    "provider": {"id": 1, "name": "Provider"},
                    "owner": {"id": 2, "name": "Owner"},
                    "licenses": [{"id": 5, "name": "CC BY 4.0"}],
                    "sensors": [{
                        "id": 123,
                        "parameter": {"name": "pm25", "units": "µg/m³"},
                    }],
                }],
            }
        if path == "sensors/123":
            return {"results": [{
                "datetimeFirst": {"utc": "2020-01-01T00:00:00Z"},
                "datetimeLast": {"utc": "2026-01-01T00:00:00Z"},
                "coverage": {"percentComplete": 90},
            }]}
        raise AssertionError(path)

    monkeypatch.setattr(openaq, "get_json", fake_get_json)
    sensors = openaq.discover_vietnam_pm25(api_key="test-key")

    assert len(sensors) == 1
    assert sensors[0]["coverage"]["percentComplete"] == 90
    assert sensors[0]["licenses"][0]["name"] == "CC BY 4.0"
    assert calls[0][0] == "locations"
    assert calls[0][1]["iso"] == "VN"
    assert calls[0][1]["parameters_id"] == 2
    assert calls[1][0] == "sensors/123"
