# Phase 9 plan: final reliability audit

Review transaction/lock ordering, stale ownership, upload/result boundaries, memory/connection limits, source/CI hygiene and clean setup. Preserve all executed evidence, including slow recovery. Re-run the complete clean unit/integration/concurrency/fault suite and the final container E2E. Verify hosted main CI for the final code rather than relying on an earlier green run.

Audit findings to fix before closure:

1. A paused upload whose reservation was already expired could mark zero rows ready yet return HTTP 201. Make the ready transition observable; reject expired reservations and keep cleanup safe.
2. Result storage was opened inside an asynchronous response body, potentially committing HTTP 200 before discovering a missing object. Open the authenticated resource before returning success and let Spring close it after streaming. A connection failing mid-transfer can still require a safe client GET retry; no streaming protocol can promise otherwise.
3. Fail fast for unknown process roles and explicitly bound connection establishment. Preserve the observed slow Redis-load result; a connection timeout is not a proven recovery SLA.

Add targeted regression tests, then validate full behavior and role/container operation. No new unrelated features or cloud spending.
