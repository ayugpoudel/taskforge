# ADR-0001: Separate execution from acceptance; persist truth in PostgreSQL

Date: 2026-11-19. Status: accepted for implementation.

Problem: CSV report requests can outlive HTTP/process lifetimes. Need durable acceptance and independent compute scaling without excessive microservices.

Alternatives: synchronous HTTP, API background threads, independent workers; Redis-only state versus PostgreSQL metadata; database-only versus object-file storage. Detailed operational/performance comparisons are in ARCHITECTURE.md.

Decision: independent workers, one modular codebase; PostgreSQL jobs/attempts/outbox; immutable objects in MinIO/S3. Redis is a learning-focused work-notification transport.

Tradeoffs: adds queue/storage failure domains and requires outbox, reconciliation and orphan cleanup. PostgreSQL-only scheduling is simpler and might be preferred for a real small deployment. Reject Redis-only state because audit/reconciliation would depend on transport retention.

Evidence: phase 0 audit/requirements only so far. Update with implementation/tests. Revisit if measured throughput does not justify Redis or if object sizes remain trivially small.

Interview explanation: The request commits a durable job quickly. Workers own temporary leases and commit a result pointer conditionally, so a process crash is recoverable and stale execution cannot publish an authoritative result.
