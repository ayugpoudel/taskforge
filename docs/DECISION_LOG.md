# Decision log

2026-11-19: One modular Spring Boot application, process roles API/worker/dispatcher. Java 21, Spring Boot 3.5.16, Gradle 8.14.3; use explicit JDBC SQL rather than ORM magic for observable atomic claims. Redis Streams chosen for learning; PostgreSQL-only queue is a defensible simpler alternative. Immutable object keys with DB pointers. See ARCHITECTURE.md for comparisons.

Original notes are preserved in ORIGINAL_ENGINEERING_NOTES.md. Their suggestion of a stable overwritable object result key is superseded: use attempt-specific keys plus fenced database finalization, so a stale worker cannot corrupt a newer result.
