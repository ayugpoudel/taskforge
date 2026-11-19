# ADR-0006: Bound acceptance and fence logical cancellation

Date: 2026-11-19. Status: accepted.

Problem: unlimited acceptance makes latency/storage unbounded; per-process concurrency alone cannot bound one owner's work across multiple processes. Cancelling a thread does not safely decide whether a result may commit.

Requirements: bounded active backlog, bounded process slots and owner leases, race-safe cancellation, total deadline and bounded shutdown. Alternatives: reject only when Redis is full, unbounded executors, terminate worker threads, priorities, database admission/state transitions.

Decision: serialize the short active-count admission check with a PostgreSQL advisory transaction lock, returning 429 plus Retry-After. Existing idempotent requests return their accepted job before capacity checking. Owner scheduling rows serialize claims for that owner, with owner-before-job lock ordering; live RUNNING leases are capped. Worker executors remain bounded. Approximate due/FIFO ordering is sufficient; priority and strict fairness are not claimed.

Cancellation locks the job and atomically changes a nonterminal state to CANCELLED. Completion has a conflicting guarded update: whichever commits first determines the final state. A successful logical cancellation fences new output commits but only checkpoints/heartbeat loss stop computation cooperatively. A completion that already won remains SUCCEEDED. Total deadline covers queue waiting, processing and retries; the dispatcher finalizes expired nonterminal rows with DEADLINE_EXCEEDED.

Shutdown stops reads, drains existing attempts for a configurable interval, then interrupts; unfinished attempts recover by lease expiry. Broker/database outages can delay physical cleanup.

Tradeoffs: the global admission lock/count is deliberately simple and can become a measured bottleneck; per-owner serialization reduces contention clarity at some throughput cost. Cancellation is not instantaneous OS process termination. Deadlines require the dispatcher to run for eventual terminal classification; finalization guards enforce them even before a sweep.

Evidence: JobStore.admission/claim/cancel/expireDeadlines, StreamWorker lifecycle; capacity/concurrent-admission/per-owner/cancellation-race/deadline integration tests and WorkerLifecycleTest. See TESTING.md for executed results.

Revisit: admission-lock contention, queue age or tenant unfairness is measured; replace count/lock with transactional capacity counters or a more explicit scheduler only then.
