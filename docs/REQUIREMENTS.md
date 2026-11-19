# Requirements and acceptance matrix

All phases must have executed evidence before being marked complete. Plan is not implementation.

| Phase | Scope | Acceptance evidence |
| --- | --- | --- |
| 0 | Audit, alternatives, architecture, initial docs | Repository isolated; choices documented; baseline toolchain checked |
| 1 | CSV streaming + durable async API vertical slice | Independently checked aggregates, upload/submit/status/result, restart-persistent records |
| 2 | Redis notification, independent worker/dispatcher roles | Multiple processes finish a batch; no duplicate result commits |
| 3 | Outbox leases, idempotency, fencing, heartbeats, retries, pending recovery | Deterministic race/failure tests, durable final-state assertions |
| 4 | Admission, deadlines, cancellation, shutdown | Capacity rejection, cancellation/completion race, bounded in-flight work |
| 5 | Storage/auth/security/cleanup | Cross-owner denial, oversized/malformed uploads, immutable artifacts |
| 6 | Metrics/logs/dashboard | Diagnose controlled delay/failure with bounded-label metrics |
| 7 | Compose/CI/cloud design | Clean local startup, actual checks, no unapproved cloud spending |
| 8 | 1/2/4-worker experiments | Raw data, environment, repeated workloads, charts, measured interpretation |
| 9 | Reliability audit | Full executed unit/integration/concurrency/E2E suite and limitations |
| 10 | Public docs, journal, ADRs, interview/resume evidence | Claims link to executed tests/measurements |

CSV reports: total valid/invalid rows, column names, inferred types, missing counts, numeric min/max/sum/mean, bounded parsing diagnostics, downloadable JSON. Exact semantics will be documented alongside the parser. Memory must be bounded by columns and maximum field size, not row count. Resource limits are explicit configuration.

No Kubernetes/Kafka, billing, arbitrary executable user jobs, production identity provider, real financial effects, or paid deployment are required. Optional UI is deferred until backend evidence is sufficient.
