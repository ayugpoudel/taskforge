# Phase 6 plan: diagnose stages, not just total latency

Add bounded-label queue-wait, execution and end-to-end histograms; active slots/utilization per worker; publication, recovery/redelivery counters; and cached durable job/attempt/outbox gauges. Sample SQL/Redis in background so a monitoring scrape cannot block on an unavailable dependency. State counters/gauges are emitted only by the API, preventing N-fold double counting across workers.

Use JSON logs with random request correlation IDs and job/worker MDC in execution. Do not log headers, CSV rows, credentials or original filenames. Provision Prometheus with Docker DNS discovery for scaled workers and a Grafana dashboard covering stage latency, worker activity, queue depth/age and outbox health. Validate the metrics in integration tests and use a controlled worker pause to diagnose queued work with no running workers, then recover it.
