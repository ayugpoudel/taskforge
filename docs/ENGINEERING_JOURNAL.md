# Engineering journal

## Phase 0 — 2026-11-19

Audit: no existing TaskForge repo. Created an isolated `~/taskforge` repository; unrelated project code and resume artifacts remain unchanged. Read the supplied engineering notes and preserved a copy. No applicable root AGENTS.md found. Java 21 exists but default Java is 23, so build commands select the installed Java 21 explicitly. Docker Desktop needed starting; sandbox access requires approval. Gradle download and Maven version verification begun.

Decision: JDBC for short atomic claims; independently deployed process roles; PostgreSQL authoritative, Redis notification transport, MinIO artifacts. Chose Redis for learning, not an unsupported performance claim. Identified the stale-worker overwrite flaw in a deterministic shared object key; require attempt-specific outputs and a fenced pointer commit.

Validation: architecture alternatives and per-phase acceptance matrix written. Application code not yet implemented/tested. Cloud resources not created.

## Phase 1 — implementation in progress

Implemented a streaming Univocity CSV calculator with 256-column/65,536-character field bounds, UTF-8 decoding, BOM handling, logical invalid-record numbers, missing-value counts and DECIMAL128 aggregation. Six unit tests executed successfully. Numeric statistics cover numeric cells even in mixed string columns; header duplicates and structural parsing failures are permanent errors. No input contents appear in public parser diagnostics.

Added the JDBC/Flyway job schema, private MinIO input/report storage, upload/status/report endpoints, owner-scoped development bearer tokens, and a SQL-polling independent worker role. Guarded completion already checks live ownership/generation rather than waiting to introduce an unsafe pattern. Local workers currently have no heartbeat/recovery; this is explicitly a phase-1 limitation. Real-service acceptance tests are running; phase 1 is not yet marked complete.

Phase 1 acceptance: 8 unit + 5 real-service tests passed, zero skipped. The actual CSV/upload/status/download path, two competing claims, duplicate finalization and expired ownership were exercised against PostgreSQL and MinIO. The source-built MinIO fixture took about 100 seconds after downloads; Docker Hub and Quay were unavailable as image sources. A test-only NPE was caused by reading a field on Spring's exception-translation proxy, not a database failure; injecting JdbcTemplate fixed it. Phase 1 stable; proceed to phase 2.

## Phase 2 — 2026-11-19

Extracted JobProcessor and JobRunner; added Redis Streams consumer-group transport and independent bounded-concurrency workers/dispatcher. Same application image runs by role. Added Compose health checks, random local credential generation, and an executable HTTP E2E test with independent arithmetic. Kept SQL-polling mode for an explicit comparison.

Executed: 8 unit + 6 integration tests; clean application/container startup; 20 CSV jobs × 20,000 rows completed correctly across two independent worker process identities. No duplicate committed output pointer observed. This is a short functional batch, not a throughput benchmark. Phase 2 stable. Heartbeat, outbox and pending recovery are the next phase; no general crash-recovery guarantee claimed yet.

## Phase 3 — in progress

Implemented job+outbox atomic acceptance; owner-scoped request idempotency; leased outbox dispatch; heartbeats; fenced output/failure; bounded durable retries; pending-page recovery gated by PostgreSQL state; notification reconstruction after Redis loss; and explicit admin replay with history retained. Initial unit/integration recovery tests passed before introducing actual infrastructure restarts.

A subsequent real Redis restart test failed because Docker Desktop reassigned an automatically published host port on explicit container stop/start, leaving the client pointed at the old endpoint. The test fixture now reserves stable host mappings, matching Compose's stable service endpoints. This is recorded as a fixture defect, not falsely reported as application recovery success. Full restart tests are being rerun. PostgreSQL socket/connection/query timeouts are bounded so outages cannot indefinitely hang heartbeats.

Phase 3 acceptance: full suite passed (9 unit + 17 integration, zero skipped), including actual Redis/PostgreSQL outages after the stable-endpoint fixture correction. Request and worker idempotency are separately tested. Existing public phase-2 smoke remains historical evidence, not retitled as a phase-3 benchmark. Next: admission/deadlines/cancellation/shutdown, including jobs that reach deadlines before being dispatched.

Phase 3 closure: actual Redis/PostgreSQL restart tests passed after the stable-port fixture correction, along with replay/history checks: 9 unit + 17 integration tests, zero skipped. Committed as `2d03e57`.

## Phase 4 — 2026-11-19

Added atomic admission control, owner-scoped live-lease concurrency caps, logical cancellation, total deadlines and bounded graceful shutdown. Kept approximate FIFO/due ordering; priority has no demonstrated need. Owner locks precede job claims consistently; cancellation and completion serialize on the job row. A successful logical cancellation cannot be followed by result finalization, even if physical parsing stops later.

Executed: 10 unit + 23 integration tests passed, including 20 cancellation/completion races, competing capacity claims, deadline sweeps, separate-owner progress and graceful draining. One overloaded Java method reference in the test was replaced by an explicit Runnable lambda. Phase 4 stable; next validate the storage/security boundary and retention.

## Phase 5 — in progress

Added a pre-multipart bearer filter, strict public JSON fields, bounded HTTP resources, per-owner upload reservations/quotas, ready/unexpired dataset checks, input byte/hash verification, stricter CSV quote/binary validation, and safe eventual storage retention. Retention row locks are coordinated with job acceptance; outputs cannot be deleted just because a worker's attempt is old if it is still live/canonical.

A refactor exposed a Gradle incremental-output issue: after moving the old authentication class out of Api.java, the new filter class was missing from compiled output, causing owner-null SQL failures. A clean build restored it and all then-current tests passed. The integration fixture now explicitly injects the filter and includes an unauthenticated malformed multipart request so a missing filter fails visibly. The additional 50 MiB streaming request limit check is running; no file contents/secrets are logged for diagnosis.

Phase 5 acceptance: 12 unit + 30 real-service integration tests passed, zero skipped, including an actual streaming oversized HTTP request and storage cleanup/integrity scenarios. No public bucket or presigned bypass was added. The stored input hash is checked at execution, and metadata-only idempotent responses still work for already accepted requests even after the input has expired. Cleanup remains eventual and metadata is intentionally retained; these limits are documented rather than described as production compliance.

## Phase 6 — 2026-11-19

Added process-local worker timers/counters plus cached authoritative SQL state/history/outbox metrics emitted only by the API. Metrics sampling is outside HTTP scrapes so outages do not turn monitoring into synchronous DB load. Added separate input-open/read, calculation, serialization, output-write/finalization stage instrumentation, bounded labels and JSON request/job correlation. Provisioned Prometheus DNS discovery and a 13-panel Grafana dashboard. Corrected combined dashboard queries so PromQL `or` does not silently hide a second series with identical labels.

Executed: 43 tests passed. Prometheus/Grafana images were pinned from official release metadata and booted locally. Worker pause produced four queued jobs, queue age >1 s, and empty outbox, correctly isolating the worker stage; restarting workers completed all four accurate reports. Two worker scrape targets and structured log fields were verified. These are diagnosis/functional observations, not throughput claims. Phase 6 stable.

## Phase 7 — 2026-11-19

Published the user-requested public repository and pushed validated milestones. Hosted main CI executed successfully, not just locally simulated; automatic dependency PR runs are separate. Added consistent Java formatting as a required check, a one-command local launcher, and an unapplied CloudFormation stack with scoped IAM/secrets, private managed data services, digest-pinned application image, HTTPS ingress and S3. Added optional IAM credential-provider and Redis TLS configuration, and the public AWS CA bundle for PostgreSQL verify-full; local static-credential/plain-Redis behavior remains tested.

Deployment review found a readiness issue: overall health includes Redis, which would defeat outbox acceptance if used by an ALB. Readiness now includes only application readiness and PostgreSQL. The real Redis-outage test verifies readiness 200 and job acceptance 202. Local schema lint passed without AWS credentials/resources. Cost estimate is explicit planning math/allowances, not a quote; regional availability and cloud runtime behavior remain untested. Phase 7 local/CI acceptance stable; no spending approval sought because deployment is not required to continue.

## Phase 8 — baseline measured

Completed three batches of 18 jobs × 500,000 rows for each of 1/2/4 independent workers. Each worker had one slot, one vCPU, 512 MiB and a six-second heartbeated lease; Docker VM had 11 CPUs and ~8.2 GB. All reports were checked against independent arithmetic. Baseline median throughput: 1.4176 / 2.7307 / 4.4712 jobs/s, or 3.154x from one to four workers. Raw data and a contemporaneous stage snapshot are in docs/benchmarks. The snapshot's mean calculation time (~0.704 s) dominated input reads (~0.013 s) and finalization (~0.0014 s); it aggregates current worker lifetimes, including warmup, and is not a per-trial profiler.

Candidate optimization: the numeric validator recompiles its regex via String.matches for every cell. Replace that with one immutable precompiled Pattern, keeping the exact same expression and matching semantics. No speedup is claimed until repeated measurements finish. Raw baseline is retained before the change.

Phase 8 results: isolated precompilation preserved validation tests and changed observed one-worker median throughput from 1.4176 to 5.2338 jobs/s (3.692x). Four optimized workers measured 11.5818 jobs/s; optimized scaling is 2.213x, not linear. Three trials per configuration and every report retained/verified. Generated standard Matplotlib PNG/SVG artifacts and inspected the chart.

Executed eight additional load/failure scenarios: 768 accepted jobs completed accurately, including a 100-job burst, 600 jobs at target 60 submissions/s, mixed/large CSVs, actual active-worker SIGKILL, Redis interruption and PostgreSQL interruption. The worker crash produced one persisted expired attempt and one successful next generation. Redis's 3-second interruption caused a 53.882-second batch; SQL timestamps show its only publication was delayed ~52.6 seconds. This is a real recovery-latency limit, not a fast-failover success claim. The DB load case had no extra attempts; in-flight failed-heartbeat behavior is covered separately by integration tests. Raw evidence and resource snapshots are committed without filtering out slower trials. Phase 8 stable; final audit follows.
