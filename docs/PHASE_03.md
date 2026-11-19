# Phase 3 plan: durability under partial failure

Acceptance and queue publication are different commits. Replace the initial periodic publisher with job+outbox atomic inserts, short outbox leases, publish outside the SQL transaction, and conditional delivery marking. A post-publish crash must be tested and documented as duplicate delivery, not hidden by a transaction claim.

Add owner-scoped request keys and payload fingerprints, unique constraints, and serialization of competing same-key requests. Independently preserve worker idempotency through generation/live-lease completion checks and attempt-specific output keys. Heartbeats run separately from CSV processing. On uncertain heartbeat failure, stop committing; database expiry plus recovery can safely schedule a new attempt.

Persist retry availability with bounded exponential jitter; classify invalid CSV as permanent. Reconcile expired attempts and missing Redis notifications from PostgreSQL. Pending-entry inspection must consult the database lease and must not steal legitimate long-running work based only on Redis idle time. Scan pending entries in bounded pages to avoid starving entries behind busy jobs. Retain one consumer group; delete only acknowledged messages. Track last notification IDs so paused workers do not cause unlimited repeated publication.

Tests: simultaneous identical/conflicting request keys; post-publish crash/restart; stale owner versus a new generation; heartbeat renewal; permanent versus transient retry; exhausted attempts; live-lease pending protection; Redis data loss and republishing; completion before acknowledgement. Use explicit database time overrides/barriers instead of arbitrary sleeps where possible. Persisted job/attempt/outbox states are the assertions.
