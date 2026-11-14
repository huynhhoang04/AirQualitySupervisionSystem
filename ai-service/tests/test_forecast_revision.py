from __future__ import annotations

import tempfile
import unittest
from datetime import timedelta
from pathlib import Path
from unittest.mock import patch

import pandas as pd

from app.config import Settings
from app.data import build_dataset, minute_buckets
from app.features import build_features
from app.inference import NotReady, predict_run, recommendations
from app.models import ModelBundle, PersistenceBundle
from app.training import NotEnoughData, Selection, train_and_evaluate
from app.worker import Worker
from test_pipeline import START, synthetic_rows


class ForecastRevisionTest(unittest.TestCase):
    def test_calendar_gaps_do_not_count_as_training_hours(self):
        rows = synthetic_rows(12)
        for row in rows[len(rows) // 2:]:
            for key in ("recorded_at", "received_at"):
                row[key] = (pd.Timestamp(row[key]) + pd.Timedelta(days=10)).isoformat()
        with self.assertRaisesRegex(NotEnoughData, "giờ PM hợp lệ"):
            train_and_evaluate(rows, [])

    def test_bucket_timestamp_is_end_not_start(self):
        frame = minute_buckets(synthetic_rows(0.05))
        self.assertEqual(frame.index[0], pd.Timestamp(START + timedelta(minutes=1)))

    def test_baseline_does_not_require_sixty_minutes(self):
        now = START + timedelta(minutes=10, seconds=30)
        result = predict_run(PersistenceBundle(), synthetic_rows(0.2), [], 5,
                             "device", "persistence-v2", 25, 1, "test", now)
        self.assertEqual(result["data_quality"]["model_kind"], "baseline")
        self.assertEqual(result["input_cutoff"], (START + timedelta(minutes=10)).isoformat())
        self.assertEqual(len(result["points"]), 3)
        self.assertEqual(len({p["pm25"] for p in result["points"]}), 1)

    def test_unreceived_and_partial_minute_do_not_change_forecast(self):
        now = START + timedelta(minutes=10, seconds=30)
        rows = synthetic_rows(0.2)
        original = predict_run(PersistenceBundle(), rows, [], 5, "d", "baseline", 25, 1, "w", now)
        for row in rows:
            if pd.Timestamp(row["recorded_at"]) >= pd.Timestamp(START + timedelta(minutes=10)):
                row["pm25"] = 500
        delayed = dict(rows[-1], recorded_at=(START + timedelta(minutes=9)).isoformat(),
                       received_at=(now + timedelta(minutes=1)).isoformat(), pm25=500)
        changed = predict_run(PersistenceBundle(), [*rows, delayed], [], 5, "d", "baseline", 25, 1, "w", now)
        self.assertEqual(original["points"], changed["points"])

    def test_low_coverage_is_not_a_real_current_pm_feature(self):
        rows = synthetic_rows(2)
        minute = START + timedelta(minutes=80)
        rows = [r for r in rows if not (minute < pd.Timestamp(r["recorded_at"]) < minute + timedelta(minutes=1))]
        frame = build_dataset(rows, [])
        feature = build_features(frame).loc[pd.Timestamp(minute + timedelta(minutes=1))]
        self.assertTrue(feature["missing_pm"])

    def test_learned_model_cannot_run_on_baseline_only_context(self):
        with self.assertRaises(NotReady):
            predict_run(ModelBundle("ridge", [], {}), synthetic_rows(0.2), [], 5,
                        "d", "model", 25, 1, "w", START + timedelta(minutes=12))

    def test_late_replay_is_ground_truth_but_not_a_historical_feature(self):
        rows = synthetic_rows(2)
        at = START + timedelta(minutes=80)
        for row in rows:
            if at <= pd.Timestamp(row["recorded_at"]) < at + timedelta(minutes=1):
                row["received_at"] = (at + timedelta(hours=1)).isoformat()
        frame = build_dataset(rows, [])
        end = pd.Timestamp(at + timedelta(minutes=1))
        self.assertFalse(frame.loc[end, "pm_valid"])
        self.assertTrue(pd.notna(frame.loc[end - pd.Timedelta(minutes=5), "y5"]))

    def test_no_early_warning_when_current_pm_already_breaches(self):
        result = recommendations(40, [{"pm25": 45, "horizon_minutes": 5}], 25, 1200, True)
        self.assertEqual({r["kind"] for r in result}, {"current_breach", "ventilation"})

    def test_baseline_is_not_presented_as_learned_early_warning(self):
        result = recommendations(20, [{"pm25": 30, "horizon_minutes": 5}], 25, None, False)
        self.assertNotIn("forecast_breach", [r["kind"] for r in result])

    def test_train_labels_stop_before_the_test_boundary(self):
        validation = {"horizons": {str(h): {"mae": 1.0, "rmse": 1.1, "samples": 50} for h in (5, 15, 30)}}
        selection = Selection("ridge", {"alpha": 10}, validation, {"alpha": 10}, validation, None, None)
        with patch("app.training.select_model", return_value=selection), \
                patch("app.training.promotion_gate", return_value=("REJECTED", "test")) as gate:
            outcome = train_and_evaluate(synthetic_rows(30), [])
        self.assertLess(pd.Timestamp(outcome["train_cutoff"]), pd.Timestamp(START + timedelta(hours=18)))
        self.assertIs(gate.call_args.args[0], validation)
        self.assertEqual(outcome["metrics"]["promotion"]["based_on"], "validation")

    def test_missing_history_falls_back_without_claiming_a_learned_model(self):
        with tempfile.TemporaryDirectory() as directory, patch("app.worker.BackendClient") as client_type:
            base = Settings.from_env()
            from dataclasses import replace
            worker = Worker(replace(base, artifact_dir=Path(directory)))
            client = client_type.return_value
            client.context.return_value = {"space": {"threshold_pm25": 25}, "device": {"telemetry_interval_ms": 5000}}
            now = pd.Timestamp.now(tz="UTC").floor("min").to_pydatetime()
            rows = synthetic_rows(0.2)
            shift = now - START - timedelta(minutes=12)
            for row in rows:
                for key in ("recorded_at", "received_at"):
                    row[key] = (pd.Timestamp(row[key]).to_pydatetime() + shift).isoformat()
            client.recent.return_value = rows
            client.recent_actuator.return_value = []
            client.upload_forecast.return_value = {"run": {"id": "id"}}
            with patch.object(worker, "load_bundle", return_value=(ModelBundle("ridge", [], {}), "learned")):
                worker.infer_device({"device_id": "device"})
            payload = client.upload_forecast.call_args.args[0]
            self.assertEqual(payload["data_quality"]["model_kind"], "baseline")
            self.assertTrue(payload["data_quality"]["fallback_reason"])
            worker._executor.shutdown(wait=True)


if __name__ == "__main__":
    unittest.main()
