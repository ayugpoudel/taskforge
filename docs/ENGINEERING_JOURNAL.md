# Engineering journal

## Phase 0 — 2026-11-19

Audit: no existing TaskForge repo. Created an isolated `~/taskforge` repository; unrelated project code and resume artifacts remain unchanged. Read the supplied engineering notes and preserved a copy. No applicable root AGENTS.md found. Java 21 exists but default Java is 23, so build commands select the installed Java 21 explicitly. Docker Desktop needed starting; sandbox access requires approval. Gradle download and Maven version verification begun.

Decision: JDBC for short atomic claims; independently deployed process roles; PostgreSQL authoritative, Redis notification transport, MinIO artifacts. Chose Redis for learning, not an unsupported performance claim. Identified the stale-worker overwrite flaw in a deterministic shared object key; require attempt-specific outputs and a fenced pointer commit.

Validation: architecture alternatives and per-phase acceptance matrix written. Application code not yet implemented/tested. Cloud resources not created.
