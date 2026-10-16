from data.generate_synthetic_labels import generate


def test_synthetic_labels_are_reproducible_and_in_domain():
    first = generate(100, 7)
    second = generate(100, 7)
    assert first.equals(second)
    assert first["threshold_label_pm25"].between(5, 100).all()
    assert first["dosage_level_label"].between(1, 3).all()
    assert first["dosage_duration_minutes_label"].between(1, 120).all()
    assert first["region_code"].between(0, 2).all()
    assert first["purpose_code"].between(0, 4).all()
