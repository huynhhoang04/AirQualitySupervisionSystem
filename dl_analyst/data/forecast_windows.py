import numpy as np
import pandas as pd


def resample_pm25(frame: pd.DataFrame, timestamp_column="timestamp", value_column="pm25") -> pd.Series:
    data = frame[[timestamp_column, value_column]].copy()
    data[timestamp_column] = pd.to_datetime(data[timestamp_column], utc=True, errors="coerce")
    data[value_column] = pd.to_numeric(data[value_column], errors="coerce")
    data = data.dropna().set_index(timestamp_column).sort_index()
    data = data[(data[value_column] >= 0) & (data[value_column] <= 500)]
    return data[value_column].resample("15min", origin="epoch").median()


def make_windows(series: pd.Series, input_steps=96, output_steps=96):
    values = series.to_numpy(dtype=np.float32)
    windows = []
    for start in range(len(values) - input_steps - output_steps + 1):
        x = values[start : start + input_steps]
        y = values[start + input_steps : start + input_steps + output_steps]
        if np.isfinite(x).all() and np.isfinite(y).all():
            windows.append((x, y))
    if not windows:
        return np.empty((0, input_steps), np.float32), np.empty((0, output_steps), np.float32)
    inputs, targets = zip(*windows)
    return np.stack(inputs), np.stack(targets)


def temporal_split(series: pd.Series, input_steps=96, output_steps=96, train_ratio=0.7, validation_ratio=0.15):
    values = series.to_numpy(dtype=np.float32)
    train_end = int(len(values) * train_ratio)
    validation_end = train_end + int(len(values) * validation_ratio)
    train = make_windows(pd.Series(values[:train_end]), input_steps, output_steps)
    validation = make_windows(pd.Series(values[train_end:validation_end]), input_steps, output_steps)
    test = make_windows(pd.Series(values[validation_end:]), input_steps, output_steps)
    return train, validation, test
