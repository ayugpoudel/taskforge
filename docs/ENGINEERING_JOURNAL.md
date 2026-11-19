# Engineering journal

## Phase 0 — 2026-11-19

Audit: no existing TaskForge repo. Created an isolated `~/taskforge` repository; unrelated project code and resume artifacts remain unchanged. Read the supplied engineering notes and preserved a copy. No applicable root AGENTS.md found. Java 21 exists but default Java is 23, so build commands select the installed Java 21 explicitly. Docker Desktop needed starting; sandbox access requires approval. Gradle download and Maven version verification begun.

Decision: JDBC for short atomic claims; independently deployed process roles; PostgreSQL authoritative, Redis notification transport, MinIO artifacts. Chose Redis for learning, not an unsupported performance claim. Identified the stale-worker overwrite flaw in a deterministic shared object key; require attempt-specific outputs and a fenced pointer commit.

Validation: architecture alternatives and per-phase acceptance matrix written. Application code not yet implemented/tested. Cloud resources not created.

## Phase 1 — implementation in progress

Implemented a streaming Univocity CSV calculator with 256-column/65,536-character field bounds, UTF-8 decoding, BOM handling, logical invalid-record numbers, missing-value counts and DECIMAL128 aggregation. Six unit tests executed successfully. Numeric statistics cover numeric cells even in mixed string columns; header duplicates and structural parsing failures are permanent errors. No input contents appear in public parser diagnostics.

Added the JDBC/Flyway job schema, private MinIO input/report storage, upload/status/report endpoints, owner-scoped development bearer tokens, and a SQL-polling independent worker role. Guarded completion already checks live ownership/generation rather than waiting to introduce an unsafe pattern. Local workers currently have no heartbeat/recovery; this is explicitly a phase-1 limitation. Real-service acceptance tests are running; phase 1 is not yet marked complete.

Phase 1 acceptance: 8 unit + 5 real-service tests passed, zero skipped. The actual CSV/upload/status/download path, two competing claims, duplicate finalization and expired ownership were exercised against PostgreSQL and MinIO. The source-built MinIO fixture took about 100 seconds after downloads; Docker Hub and Quay were unavailable as image sources. A test-only NPE was caused by reading a field on Spring's exception-translation proxy, not a database failure; injecting JdbcTemplate fixed it. Phase 1 stable; proceed to phase 2.
