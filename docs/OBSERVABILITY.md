# Observability and stage diagnosis

Prometheus: http://127.0.0.1:19090 . Grafana: http://127.0.0.1:13000/d/taskforge . Grafana user `taskforge`; password is the private `.env` value `TF_GRAFANA_PASSWORD`. Setup generates it without printing it. Services and dashboards are local-only.

| Signal | Meaning / scope |
| --- | --- |
| `taskforge_jobs_current{status=...}` | Cached authoritative job-state counts. Only API emits these, so worker scaling does not multiply totals. |
| `taskforge_history_submitted_total` | Retained accepted job records; idempotent request repeats do not increment it. |
| `taskforge_history_succeeded_total` | Successful durable attempt records, one authoritative success per job. |
| `taskforge_history_retried_total` | Attempts with generation > 1, including deliberate administrative replay. |
| `taskforge_history_failed_total` | Failed attempt outcomes, excluding cancellation. This is not the number of currently failed jobs; use the status gauge for that. |
| `taskforge_job_queue_wait_seconds_*` | Due/available time → claim start, per attempt; excludes intentionally delayed backoff before availability. |
| `taskforge_job_execution_seconds_*` | Worker execution wall time, including input, calculation, output and finalization; includes failed/lost attempts. |
| `taskforge_job_stage_seconds_*{stage=...}` | Separate input-open/read, calculation, serialization, output-write and SQL-finalization timing; bounded stage names. |
| `taskforge_job_end_to_end_seconds_*` | Accepted/created → successful finalization; includes retries. |
| `taskforge_worker_active` / `taskforge_worker_utilization` | Occupied worker slots and occupied/configured ratio, not CPU utilization. |
| `taskforge_queue_oldest_age` | Age in seconds of the oldest currently due queued/retrying job. |
| `taskforge_outbox_pending` / `taskforge_outbox_oldest_age` | Unpublished durable event count (including future retry events), and oldest due age in seconds. |
| `taskforge_redis_entries` / `taskforge_redis_pending` | Transport stream length / pending acknowledgements. Unread entries are not the same as PEL pending entries. |
| `taskforge_queue_publications_total` / `taskforge_queue_redeliveries_total` / `taskforge_jobs_recovered_total` | Process-local publication, reclaimed-delivery, and recovered-expired-attempt counters; reset when that process restarts. |

Histogram p50/p95/p99 charts use Prometheus bucket interpolation and need enough observations. They are not the exact raw-sample benchmark percentiles. Function counters backed by retained SQL history depend on that metadata not being purged; a future metadata-retention design must account for counter resets.

API samples SQL and Redis in a background task every two seconds. Scrapes read an in-memory snapshot. Dependency failure produces unavailable/NaN values rather than fabricated zeros; a scrape itself does not perform database/network work. This matters during outages: monitoring should not consume every request thread while trying to diagnose the outage.

## Read the stages

- Growing outbox backlog/age and low publication rate: dispatcher or Redis failure.
- Empty outbox, growing queued age, workers down/zero active slots: work reached transport but workers cannot claim it.
- High execution duration/CPU with busy slots: inspect CSV workload and resource limits before adding workers.
- High input/output transfer time (correlate execution traces/logs and storage health): investigate object storage/network. Compare input-open/read, calculation, output-write and finalization stage timers with worker/storage health. Read timing measures time in source stream reads; calculation subtracts that read time and still includes parser/decoder overhead.
- Repeated lease recoveries/lost attempts: compare lease budget, DB timeouts, heartbeat failures and process exits.

Docker DNS service discovery scrapes every worker instance rather than a single round-robin hostname. Labels are limited to bounded statuses, roles and scrape targets; job IDs/user IDs are not metric labels. Logs use JSON with request IDs and job/worker MDC. No bearer tokens, row contents or filenames are logged.

Run `python3 scripts/observability_check.py` after the full stack is healthy. It intentionally stops only TaskForge workers, accepts four real CSV jobs, observes queued age, restarts workers and verifies all reports. Evidence is saved in `docs/evidence/phase6-observability.json` after an actual successful run.
