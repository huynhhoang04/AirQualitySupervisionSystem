from __future__ import annotations

import math
import unittest
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd

from app.data import build_dataset, minute_buckets, with_labels
from app.features import REQUIRED_AT_ORIGIN, build_features, latest_origin, ready_origins
from app.models import ModelBundle, PersistenceBundle
from app.training import fit_bundle, promotion_gate, train_and_evaluate

START = datetime(2026, 1, 1, tzinfo=timezone.utc)


def synthetic_rows(hours: float, interval_seconds: int = 5) -> list[dict]:
    rows = []
    count = int(hours * 3600 / interval_seconds)
    for index in range(count):
        at = START + timedelta(seconds=index * interval_seconds)
        pm = 20.0 + 8.0 * math.sin(2 * math.pi * index / 720.0)
        rows.append(
            {
                "recorded_at": at.isoformat(),
                "received_at": at.isoformat(),
                "pm25": pm,
                "co2": 600.0 + 50.0 * math.sin(2 * math.pi * index / 1440.0),
                "temperature": 25.0,
                "humidity": 50.0,
                "pm25_saturated": False,
                "dust_fault": False,
            }
        )
    return rows


class MinuteBucketTest(unittest.TestCase):
    def test_median_and_validity_use_half_the_expected_samples(self) -> None:
        rows = synthetic_rows(0.05)  # 3 minutes
        buckets = minute_buckets(rows, 60, interval_seconds=5)
        self.assertEqual(len(buckets), 3)
        self.assertEqual(int(buckets.iloc[0]["expected_count"]), 12)
        self.assertTrue(bool(buckets.iloc[0]["pm_valid"]))

    def test_saturated_and_faulty_samples_are_not_measurements(self) -> None:
        rows = synthetic_rows(0.02)
        for row in rows:
            row["pm25_saturated"] = True
        buckets = minute_buckets(rows, 60, interval_seconds=5)
        self.assertFalse(bool(buckets.iloc[0]["pm_valid"]))
        self.assertTrue(pd.isna(buckets.iloc[0]["pm25_median"]))


class LabelTest(unittest.TestCase):
    def test_label_is_taken_from_a_real_target_bucket(self) -> None:
        rows = synthetic_rows(0.2)  # 12 minutes
        frame = with_labels(minute_buckets(rows, 60, 5), (5,))
        first = frame.index.min()
        self.assertAlmostEqual(frame.loc[first, "y5"], frame.loc[first + pd.Timedelta(minutes=5), "pm25_median"])

    def test_gap_breaks_the_label(self) -> None:
        rows = synthetic_rows(0.1)
        # Remove every sample from the target minute so the label must be absent.
        target = START + timedelta(minutes=5)
        rows = [row for row in rows if not row["recorded_at"].startswith(target.strftime("%Y-%m-%dT%H:%M"))]
        frame = with_labels(minute_buckets(rows, 60, 5), (5,))
        self.assertTrue(pd.isna(frame.loc[frame.index.min(), "y5"]))


class FeatureTest(unittest.TestCase):
    def test_features_do_not_use_future_samples(self) -> None:
        rows = synthetic_rows(3)
        frame = with_labels(minute_buckets(rows, 60, 5), (5, 15, 30))
        features = build_features(frame)
        origin = features.index[100]

        mutated = [dict(row) for row in rows]
        later = START + timedelta(minutes=101)
        for row in mutated:
            if datetime.fromisoformat(row["recorded_at"]) >= later:
                row["pm25"] = 300.0
        mutated_frame = with_labels(minute_buckets(mutated, 60, 5), (5, 15, 30))
        mutated_features = build_features(mutated_frame)

        pd.testing.assert_series_equal(features.loc[origin], mutated_features.loc[origin])

    def test_ready_origins_require_the_longest_lag(self) -> None:
        rows = synthetic_rows(3)
        frame = with_labels(minute_buckets(rows, 60, 5), (5, 15, 30))
        ready = ready_origins(build_features(frame))
        self.assertFalse(ready.empty)
        self.assertTrue(ready[REQUIRED_AT_ORIGIN].notna().all().all())

    def test_stale_origin_is_rejected(self) -> None:
        rows = synthetic_rows(2)
        frame = with_labels(minute_buckets(rows, 60, 5), (5, 15, 30))
        features = build_features(frame)
        now = START + timedelta(hours=10)
        self.assertIsNone(latest_origin(features, pd.Timestamp(now)))


class ModelTest(unittest.TestCase):
    def test_persistence_returns_the_current_value(self) -> None:
        features = pd.DataFrame({"pm_now": [30.0, 40.0]}, index=pd.date_range(START, periods=2, freq="1min", tz="UTC"))
        predicted = PersistenceBundle().predict(features, 30)
        np.testing.assert_allclose(predicted, [30.0, 40.0])

    def test_ridge_bundle_fits_and_predicts_in_range(self) -> None:
        rows = synthetic_rows(6)
        frame = with_labels(minute_buckets(rows, 60, 5), (5, 15, 30))
        features = build_features(frame)
        ready = ready_origins(features)
        features, frame = ready, frame.loc[ready.index]
        cut = features.index.min() + pd.Timedelta(hours=4)
        dev = features.index[features.index <= cut]
        test = features.index[features.index > cut]
        bundle = fit_bundle(features, frame, "ridge", {"alpha": 10.0}, dev)
        predicted = bundle.predict(features.loc[test], 15)
        self.assertTrue(np.all(predicted >= 0.0))
        self.assertTrue(np.all(predicted <= 500.0))


class PromotionTest(unittest.TestCase):
    def test_rejects_less_than_ten_percent_gain(self) -> None:
        candidate = {"horizons": {"15": {"mae": 9.5}, "30": {"mae": 10.0}, "5": {"mae": 5.0}}}
        baseline = {"horizons": {"15": {"mae": 10.0}, "30": {"mae": 10.0}, "5": {"mae": 5.0}}}
        status, reason = promotion_gate(candidate, [baseline])
        self.assertEqual(status, "REJECTED")
        self.assertIn("gain", reason)

    def test_promotes_a_clear_win(self) -> None:
        candidate = {"horizons": {"15": {"mae": 7.0}, "30": {"mae": 7.0}, "5": {"mae": 5.0}}}
        baseline = {"horizons": {"15": {"mae": 10.0}, "30": {"mae": 10.0}, "5": {"mae": 5.0}}}
        status, _ = promotion_gate(candidate, [baseline])
        self.assertEqual(status, "PRODUCTION")

    def test_zero_baseline_error_is_not_a_percentage_game(self) -> None:
        candidate = {"horizons": {"15": {"mae": 0.0}, "30": {"mae": 0.0}, "5": {"mae": 0.0}}}
        baseline = {"horizons": {"15": {"mae": 0.0}, "30": {"mae": 0.0}, "5": {"mae": 0.0}}}
        status, _ = promotion_gate(candidate, [baseline])
        self.assertEqual(status, "REJECTED")


class TrainingTest(unittest.TestCase):
    def test_training_reports_data_card_and_baselines(self) -> None:
        rows = synthetic_rows(30)
        outcome = train_and_evaluate(rows, [], interval_seconds=5, folds=1)
        metrics = outcome["metrics"]
        self.assertGreater(metrics["data"]["minute_buckets"], 1000)
        self.assertIn("persistence", metrics["test"])
        self.assertIn("ridge", metrics["test"])
        self.assertIn("candidate", metrics["test"])
        self.assertIn(outcome["status"], {"PRODUCTION", "REJECTED"})
        self.assertIsInstance(outcome["bundle"], ModelBundle)


if __name__ == "__main__":
    unittest.main()
