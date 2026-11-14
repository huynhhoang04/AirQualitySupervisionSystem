"""AI service — Air Quality Supervision System.

A local worker that learns from the node's raw telemetry and uploads forecasts to the cloud backend.
The fan control loop is independent: stopping this process must never affect measurement or control.
"""
