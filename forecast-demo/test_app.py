import unittest
from datetime import datetime, timezone
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient
from app import app, estimate

NOW = datetime(2026, 11, 14, 10, 0, tzinfo=timezone.utc).timestamp()


def rows(value=25):
    return [{"recorded_at": datetime.fromtimestamp(NOW - (287 - i) * 300, timezone.utc).isoformat(),
             "pm25": value, "pm25_saturated": False} for i in range(288)]


class ForecastTests(unittest.TestCase):
    def test_constant_day_fits_and_predicts_six_steps(self):
        result = estimate(rows(), NOW)
        self.assertEqual(result["status"], "ready")
        self.assertEqual(result["training_rows"], 282)
        self.assertEqual(len(result["predicted"]), 7)
        self.assertTrue(all(p["pm25"] == 25 for p in result["predicted"]))
        self.assertEqual(result["predicted"][-1]["at"], (NOW + 1800) * 1000)
        self.assertEqual(result["validation"]["mae_5min"], 0)

    def test_missing_and_old_data_have_no_forecast(self):
        self.assertEqual(estimate([], NOW)["predicted"], [])
        self.assertEqual(estimate(rows()[:-2], NOW)["status"], "stale_data")
        self.assertEqual(estimate(rows()[-8:], NOW)["status"], "insufficient_data")

    def test_gaps_at_end_block_recursive_forecast(self):
        data = rows()
        del data[-4]
        self.assertEqual(estimate(data, NOW)["status"], "insufficient_data")

    def test_invalid_and_saturated_samples_are_excluded(self):
        self.assertEqual(estimate([{**r, "pm25_saturated": True} for r in rows()], NOW)["predicted"], [])
        self.assertEqual(estimate([{**r, "pm25": None} for r in rows()], NOW)["predicted"], [])

    def test_http_endpoint_only_reads_existing_backend_api(self):
        response = httpx.Response(200, json=rows(), request=httpx.Request("GET", "http://backend/telemetry"))
        with patch("app.httpx.get", return_value=response) as get:
            with TestClient(app) as client:
                result = client.get("/forecast/node01")
        self.assertEqual(result.status_code, 200)
        self.assertIn("/api/v1/devices/node01/telemetry", get.call_args.args[0])
        self.assertIn("from", get.call_args.kwargs["params"])

    def test_upstream_failure_is_visible(self):
        with patch("app.httpx.get", side_effect=httpx.ConnectError("offline")):
            with TestClient(app) as client:
                self.assertEqual(client.get("/forecast/node01").status_code, 502)


if __name__ == "__main__":
    unittest.main()
