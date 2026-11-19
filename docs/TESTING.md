# Testing evidence

Initial environment: macOS arm64; installed Microsoft OpenJDK 21.0.8 selected explicitly because the default Java is 23. Docker Desktop was initially stopped and was started. No application tests executed yet.

Plan: JUnit 5 unit tests; Testcontainers PostgreSQL/Redis/MinIO integration tests; barriers for deterministic claim/fencing races; Compose subprocess smoke/crash tests; independent Python CSV oracle; controlled 1/2/4-worker benchmarks. Required integration tests must fail, not silently skip, when Docker is unavailable.

## Phase 1 executed evidence

`JAVA_HOME=<installed Java 21> ./gradlew test integrationTest` passed: 8 unit tests and 5 real-service integration tests, 0 skipped. PostgreSQL 17, Redis 7.4.2, source-built MinIO security release; required containers fail setup rather than skip if unavailable. Tests verify upload → durable job → independent worker logic → authenticated download with independently hand-calculated aggregates; ownership on status/attempts/result; malformed input failure; competing worker claims; duplicate/stale completion rejection. Build output and XML are under ignored `build/`.

Failures fixed before passing: MinIO image distribution changed (built source); ambiguous Testcontainers Container imports; direct field access on a Spring repository proxy in tests (inject JdbcTemplate instead); removed a duplicate exception handler introduced during editing. No benchmark or cloud deployment claim.

## Phase 2 executed evidence

8 unit + 6 integration tests passed, 0 skipped, then `docker compose up -d --build --scale worker=2 --wait` succeeded. `scripts/e2e.py --jobs 20 --rows 20000 --require-processes 2` completed 20/20 real CSV jobs and independently verified row counts, sums, and means. Persisted attempts identify two independent worker process IDs, not merely two threads. Raw smoke evidence: `docs/evidence/phase2-e2e.json`. Its short-run jobs/sec figure is smoke-test timing, not a controlled benchmark or scaling result. Redis stream group distributes one pending delivery to a consumer; the PostgreSQL claim independently controls execution ownership.

## Phase 3 executed evidence

9 unit + 17 integration tests passed, 0 skipped. Actual Testcontainers Redis and PostgreSQL stop/start outages were exercised at stable mapped endpoints: jobs remain durably accepted during Redis outage; outbox dispatch resumes; PostgreSQL restart preserves accepted state; an unavailable heartbeat cannot authorize stale completion. Additional deterministic tests cover atomic rollback on failed outbox insertion, eight simultaneous same-key submissions, conflicting request payloads, post-publication dispatcher crash, newer-generation fencing, attempt-specific object isolation, live-lease pending protection, completion before acknowledgement, retry timing/exhaustion, Redis data loss/reconciliation, outbox lease-token replacement, and authorized administrative replay preserving history.

Testcontainers initially used dynamically remapped ports that changed across explicit stop/start on Docker Desktop; stable reserved mappings fixed the restart fixture. Worker death is modeled by persisted expired lease in these tests; actual worker process termination remains part of the later E2E audit. Guarantees do not extend to permanent loss of PostgreSQL/object storage or arbitrary external effects.

## Phase 4 executed evidence

10 unit + 23 integration tests passed, 0 skipped (33 total). Added concurrent admission at the capacity boundary, idempotent replay while full, per-owner concurrency without blocking another owner, ownership/idempotence of cancellation, 20 barrier-driven cancellation/completion races, queued/running/retrying total deadlines, and a latch-driven graceful worker drain test. A Java overloaded method-reference compile error in the lifecycle test was fixed before these results. Tests use explicit barriers/state/time adjustments; no timing-based race winner is assumed.

## Phase 4 and 5 executed evidence

Phase 4: 10 unit + 23 integration tests passed, including 20 completion/cancellation races, simultaneous capacity checks, per-owner isolation and graceful-drain behavior.

Phase 5: 12 unit + 30 integration tests passed, 0 skipped. The 50 MiB+1-byte upload is generated as a streaming HTTP body, receives 413, and leaves no dataset reservation. Other checks cover authentication before malformed multipart parsing, unsupported owner fields, path-like filenames, concurrent quota reservations, unready input rejection, changed-object hash detection, strict quoted CSV/binary checks, live/canonical retention protection, 410 for expired reports, abandoned input cleanup and a late stale-object reappearance. Clean compilation verified the moved filter class is actually present; the fixture explicitly requires that bean.

## Phase 6 executed evidence

12 unit + 31 integration tests passed, 0 skipped. Full stack with pinned Prometheus 3.16.0 and Grafana 13.3.1 started successfully. The provisioned dashboard has 13 panels. The controlled worker-pause diagnostic observed four queued jobs, increasing queue age and zero outbox backlog; after restart all four reports were verified and queue depth returned to zero. See `docs/evidence/phase6-observability.json`. The actual Prometheus targets include both worker processes, and captured JSON execution logs include job/worker correlation fields (`phase6-log-sample.json`).

## Phase 7 executed evidence

43 tests remain passing after consistent Google Java formatting and local storage configuration changes. The actual Redis-outage integration scenario confirms `/actuator/health/readiness` stays HTTP 200 while durable job acceptance returns 202. The CloudFormation JSON passes cfn-lint 1.46.0 locally, shell syntax and Compose schema checks pass, and no AWS resources were created. Runtime IAM/S3/Redis-TLS/RDS-certificate paths remain untested in AWS. Main hosted CI passed for dfc58ae at https://github.com/ayugpoudel/taskforge/actions/runs/44607116098 ; later cloud/format changes require their own hosted check.
