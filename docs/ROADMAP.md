# Roadmap

Follow phases 0–10 in REQUIREMENTS.md sequentially. Each phase gets a plan, implementation, executed checks, journal entry, and local commit. Do not substitute generated code for passing acceptance checks.

Completed: phase 0 design and phase 1 durable CSV/API slice (8 unit + 5 real-service tests). Phase 2 complete: Redis transport, 14 tests and two-process CSV batch verified. Phase 3 complete: outbox, keys, fencing, durable retries and real-service outage tests (26 tests). Current milestone: phase 4 scheduling/backpressure. Later phases remain planned.

Evidence and exact blockers are recorded in TESTING.md; cloud deployment is configuration-only until budget/permission exists.
