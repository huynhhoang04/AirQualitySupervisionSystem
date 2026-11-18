import assert from "node:assert/strict";
import { test } from "node:test";

import { buildChartRows, gapBreakMs } from "../src/lib/chartRows.ts";

const T0 = Date.parse("2026-11-18T12:00:00Z");
const at = (seconds: number) => new Date(T0 + seconds * 1000).toISOString();

function sample(seconds: number, pm25: number | null) {
  return {
    id: seconds,
    device_id: "dev",
    recorded_at: at(seconds),
    received_at: at(seconds + 1),
    time_quality: "device",
    pm25,
    co2: 700,
    temperature: 24,
    humidity: 55,
    wifi_rssi: -60,
    pm25_saturated: false,
    dust_fault: false,
    uptime_sec: 1,
    free_heap: 1,
    min_free_heap: 1,
  };
}

function path(anchorSeconds: number, values: number[], anchorPm = values[0]) {
  return {
    run_id: "run",
    anchor_at: at(anchorSeconds),
    anchor_pm25: anchorPm,
    step_seconds: 5,
    values,
    q10: null,
    q90: null,
    created_at: at(anchorSeconds),
  };
}

test("a 10-minute gap breaks the measured line", () => {
  // Two samples ten minutes apart: the report's exact case.
  const samples = [sample(0, 20), sample(600, 30)];
  const rows = buildChartRows(samples, [], undefined, T0 + 600_000, 30);

  const measured = rows.filter((row) => row.at <= T0 + 601_000);
  const nulls = measured.filter((row) => row.measured === null);

  assert.ok(nulls.length >= 1, "no null row was inserted, so the line will join the two samples");
  assert.ok(
    measured.some((row) => row.at > T0 && row.at < T0 + 600_000 && row.measured === null),
    "the break must sit between the two samples, not at the end",
  );
});

test("a regular 5 s stream has no breaks", () => {
  const samples = Array.from({ length: 60 }, (_, index) => sample(index * 5, 20 + index * 0.1));
  const rows = buildChartRows(samples, [], undefined, T0 + 295_000, 25.9);

  assert.equal(rows.filter((row) => row.at <= T0 + 295_000 && row.measured === null).length, 0);
});

test("an isolated single null keeps the gap", () => {
  const samples = [sample(0, 20), sample(10, 22), sample(15, 24)];
  const rows = buildChartRows(samples, [], undefined, T0 + 15_000, 24);
  const measured = rows.filter((row) => row.measured != null).map((row) => row.measured);
  assert.deepEqual(measured, [20, 22, 24]);
});

test("the forecast line starts at the newest measurement, not the old anchor", () => {
  // The model issued for 12:00 with 25; a newer reading of 70 arrived at 12:01.
  const oldPath = path(0, Array.from({ length: 360 }, () => 25));
  const samples = [sample(0, 25), sample(60, 70)];

  const rows = buildChartRows(samples, [], oldPath, T0 + 60_000, 70);

  const start = rows.find((row) => row.forecast != null);
  assert.ok(start, "the forecast has no start point");
  assert.equal(start.at, T0 + 60_000, "the forecast does not start at the newest sample");
  assert.equal(start.forecast, 70, "the forecast start carries the stale anchor value");
});

test("stored curve values after the new anchor are kept", () => {
  const values = Array.from({ length: 360 }, (_, index) => 25 + index * 0.01);
  const oldPath = path(0, values);
  const samples = [sample(0, 25), sample(60, 70)];

  const rows = buildChartRows(samples, [], oldPath, T0 + 60_000, 70);

  // 60 s after the old anchor is index 11 (step 5). Earlier values are superseded, later ones stay.
  const later = rows.filter((row) => row.at > T0 + 60_000 && row.forecast != null);
  assert.ok(later.length > 50, "the remainder of the stored curve disappeared");
  assert.equal(later[later.length - 1].forecast, values[values.length - 1]);
});

test("three-point fallback still draws a line", () => {
  const points = [
    { horizon_minutes: 5, target_at: at(300), pm25: 26, actual_pm25: null, error: null, evaluation_status: "PENDING" },
    { horizon_minutes: 15, target_at: at(900), pm25: 28, actual_pm25: null, error: null, evaluation_status: "PENDING" },
  ];
  const rows = buildChartRows([sample(0, 25)], points, undefined, T0, 25);

  assert.deepEqual(
    rows.filter((row) => row.forecast != null).map((row) => row.forecast),
    [25, 26, 28],
  );
});

test("gap threshold follows the stream cadence, not a hard-coded 5 s", () => {
  const fiveSecond = Array.from({ length: 20 }, (_, index) => T0 + index * 5_000);
  const tenSecond = Array.from({ length: 20 }, (_, index) => T0 + index * 10_000);

  assert.equal(gapBreakMs(fiveSecond), 15_000);
  assert.equal(gapBreakMs(tenSecond), 30_000);
});

test("two samples still produce a finite break threshold", () => {
  // The regression: Infinity here meant the two-sample gap was never broken. A single 10-minute gap
  // is the gap itself, so the cadence falls back to the 5 s default and the break is inserted.
  assert.ok(Number.isFinite(gapBreakMs([T0, T0 + 600_000])));
  assert.equal(gapBreakMs([T0, T0 + 600_000]), 15_000);
});

test("a long gap next to a normal stream still breaks", () => {
  // 60 regular packets, then a 9-minute hole, then more packets.
  const times = [
    ...Array.from({ length: 60 }, (_, index) => T0 + index * 5_000),
    ...Array.from({ length: 60 }, (_, index) => T0 + 300_000 + 540_000 + index * 5_000),
  ];
  // The lower quartile is the 5 s cadence, so the hole is detected.
  assert.equal(gapBreakMs(times), 15_000);
});
