# ADR-0003: Commit job acceptance and its outbox together

Date: 2026-11-19. Status: accepted.

Problem: committing SQL and publishing Redis cannot be one ordinary local transaction. A crash between them can lose work; publishing before SQL can notify nonexistent jobs.

Requirements: durable acceptance even if Redis is unavailable; safe competing dispatchers; repeatable client retries. Alternatives: direct dual write, distributed transaction, PostgreSQL-only queue, transactional outbox.

Decision: JobStore.create inserts job and pending outbox in one PostgreSQL transaction. Outbox rows are claimed with short leases and SKIP LOCKED, then published outside that transaction. Delivery marking checks a unique lease token and live expiry. Job-row-before-outbox locking is used where both are updated. PostgreSQL due-state reconciliation reconstructs lost notifications.

Request idempotency is separate: owner-scoped optional keys, a stable fingerprint of dataset ID/content hash, transaction advisory lock for a competing key, and a unique index. Same key/payload returns the existing job; conflicting payload gets 409. Keys must be 1–128 restricted printable characters. Runtime retry settings are not part of public payload identity.

Tradeoffs: at-least-once publication, not exactly once. Crash after publish/before marking leaves an unmarked event that can publish again after its five-second lease expires. A partial unique index permits only one unpublished event per job. Request keys do not deduplicate unrelated requests without a key or arbitrary side effects.

Rejected: distributed SQL/Redis transaction complexity; direct dual write cannot provide acceptance durability. A PostgreSQL-only queue avoids the gap altogether but does not teach broker transport semantics.

Evidence: V3 migration, JobStore.create/leaseOutbox/published, Dispatcher.publishOne; tests inject failure in outbox insert (job rolls back), concurrent duplicate keys, and crash after publish. See TESTING.md for execution results.

Revisit: higher event throughput needs batch publication, different lease duration, or a CDC-based outbox; measure before changing.
