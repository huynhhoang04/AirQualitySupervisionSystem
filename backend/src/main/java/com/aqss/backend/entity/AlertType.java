package com.aqss.backend.entity;

/** Alert categories. CO2_ALERT is advisory only — it never actuates. */
public enum AlertType {
    PM25_THRESHOLD,
    CO2_ALERT,
    SENSOR_OFFLINE,
    SENSOR_SATURATED
}
