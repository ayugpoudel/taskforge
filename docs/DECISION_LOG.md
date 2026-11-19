# Decision log

2026-11-19: One modular Spring Boot application, process roles API/worker/dispatcher. Java 21, Spring Boot 3.5.16, Gradle 8.14.3; use explicit JDBC SQL rather than ORM magic for observable atomic claims. Redis Streams chosen for learning; PostgreSQL-only queue is a defensible simpler alternative. Immutable object keys with DB pointers. See ARCHITECTURE.md for comparisons.

Original notes are preserved in ORIGINAL_ENGINEERING_NOTES.md. Their suggestion of a stable overwritable object result key is superseded: use attempt-specific keys plus fenced database finalization, so a stale worker cannot corrupt a newer result.

2026-11-19 environment correction: Docker Hub `minio/minio` returned repository-not-found; Quay returned 401. Upstream MinIO is source-only and the repository is archived. Build the pinned `RELEASE.2025-10-15T17-29-55Z` security release from upstream with Go 1.24.8 for local testing. See `infra/Minio.Dockerfile`. This is a local compatibility fixture, not a recommendation to expose an unmaintained server publicly; S3 remains the intended managed cloud store. References: https://github.com/minio/minio and https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z .
