# Testing evidence

Initial environment: macOS arm64; installed Microsoft OpenJDK 21.0.8 selected explicitly because the default Java is 23. Docker Desktop was initially stopped and was started. No application tests executed yet.

Plan: JUnit 5 unit tests; Testcontainers PostgreSQL/Redis/MinIO integration tests; barriers for deterministic claim/fencing races; Compose subprocess smoke/crash tests; independent Python CSV oracle; controlled 1/2/4-worker benchmarks. Required integration tests must fail, not silently skip, when Docker is unavailable.
