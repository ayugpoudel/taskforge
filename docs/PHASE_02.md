# Phase 2 plan: distributed execution

Start only after the phase-1 real-service tests pass.

Replace SQL polling with Redis Stream consumer-group notifications. Keep the same JDBC claim/finalization path so changing transport cannot weaken ownership. Extract a workload interface and runner from the temporary local worker. Run independent worker, dispatcher, and API containers from the same application artifact, with configurable bounded worker concurrency.

Initial dispatcher republishes queued notifications periodically; phase 3 replaces this with a transactional outbox and durable recovery. A stream message contains only a job ID. A busy/terminal database row is not permission to run it. Acknowledgement follows durable completion (or a known durable terminal state). Stream retention must not blindly trim unread/pending work.

Acceptance: a Compose batch processed by at least two independent worker identities, one durable committed result per job, correct output, restart-persistent records. Reliability beyond the tested ownership path remains incomplete until phase 3.
