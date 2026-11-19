# Testing evidence

Initial environment: macOS arm64; installed Microsoft OpenJDK 21.0.8 selected explicitly because the default Java is 23. Docker Desktop was initially stopped and was started. No application tests executed yet.

Plan: JUnit 5 unit tests; Testcontainers PostgreSQL/Redis/MinIO integration tests; barriers for deterministic claim/fencing races; Compose subprocess smoke/crash tests; independent Python CSV oracle; controlled 1/2/4-worker benchmarks. Required integration tests must fail, not silently skip, when Docker is unavailable.

## Phase 1 executed evidence

`JAVA_HOME=<installed Java 21> ./gradlew test integrationTest` passed: 8 unit tests and 5 real-service integration tests, 0 skipped. PostgreSQL 17, Redis 7.4.2, source-built MinIO security release; required containers fail setup rather than skip if unavailable. Tests verify upload → durable job → independent worker logic → authenticated download with independently hand-calculated aggregates; ownership on status/attempts/result; malformed input failure; competing worker claims; duplicate/stale completion rejection. Build output and XML are under ignored `build/`.

Failures fixed before passing: MinIO image distribution changed (built source); ambiguous Testcontainers Container imports; direct field access on a Spring repository proxy in tests (inject JdbcTemplate instead); removed a duplicate exception handler introduced during editing. No benchmark or cloud deployment claim.
