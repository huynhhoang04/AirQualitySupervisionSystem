import numpy as np
import pandas as pd
import pytest

from data.cams import build_cams_records, pm25_to_ug_m3


def test_cams_unit_conversion_and_forecast_lead_time_are_preserved():
    assert np.allclose(pm25_to_ug_m3([1e-8], "kg m-3"), [10.0])
    frame = build_cams_records(
        [1e-8, 2e-8],
        ["2026-01-01T01:00:00Z", "2026-01-01T02:00:00Z"],
        "2026-01-01T00:00:00Z",
        21.03,
        105.85,
        "kg m-3",
        "2026-01-02T00:00:00Z",
    )
    assert frame["pm25_ug_m3"].tolist() == [10.0, 20.0]
    assert frame["lead_time_hours"].tolist() == [1.0, 2.0]
    assert frame["setting"].iloc[0] == "ambient_outdoor_model"


def test_cams_requires_issue_time_before_valid_times():
    with pytest.raises(ValueError, match="precede"):
        build_cams_records([1.0], ["2026-01-01T00:00:00Z"], "2026-01-01T01:00:00Z", 21, 105, "ug/m3")
