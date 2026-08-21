# Air Quality Supervision System (AQSS)

An end-to-end IoT project that monitors, evaluates, and predicts air quality in real-time. The system is built across four distinct layers to ensure high performance, scalability, and accurate predictions using Deep Learning.

## Architecture Overview

| Layer | Component | Tech Stack | Description |
| :---: | :--- | :--- | :--- |
| **1** | Embedded IoT | ESP32-S3, C++17, FreeRTOS | Sensor acquisition (SCD40, GP2Y1023AU0F), OLED display, MQTT telemetry via Mosquitto. |
| **2** | Backend Services | Java 21, Spring Boot 3.3, PostgreSQL 16 | MQTT ingestion, REST API, JWT auth, RBAC, batch writes, STOMP WebSocket. |
| **3** | AI / Deep Learning | Python 3.11, PyTorch 2.2, FastAPI | Seq2Seq LSTM forecasting (24h), Autoencoder anomaly evaluation. |
| **4** | Frontend Dashboard | React 18, Vite, TailwindCSS, Zustand | Real-time dark-mode dashboard, AQI gauge, forecast charts with confidence intervals. |

## Key Features

- **Real-Time Monitoring:** Live telemetry ingested via MQTT and pushed to the frontend with <5ms latency using STOMP WebSockets.
- **AI-Powered Forecasting:** Sequence-to-Sequence (Seq2Seq) LSTM models predict PM2.5 levels for the next 24 hours with upper and lower confidence bounds.
- **Sensor Anomaly Detection:** LSTM Autoencoder evaluates the health of incoming sensor data and alerts on irregularities.
- **High Performance:** Backend optimized with PostgreSQL native range partitioning, materialized views, and Spring Boot batch inserts.
- **Responsive UI:** A premium dark-mode web dashboard built with React, Vite, and Recharts.

## Documentation

Detailed documentation and planning for each layer can be found in the `docs/` directory:

- [Input / Output Contract (I/O Contract)](docs/io_contract.md) — Single source of truth for all data payloads and APIs.
- [Layer 1 Plan (Embedded)](docs/plan_layer1_embedded.md)
- [Layer 2 Plan (Backend)](docs/plan_layer2_backend.md)
- [Layer 3 Plan (Deep Learning)](docs/plan_layer3_dl.md)
- [Layer 4 Plan (Frontend)](docs/plan_layer4_frontend.md)
- [Hardware Datasheet Reference](docs/hardware_datasheet_reference.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
