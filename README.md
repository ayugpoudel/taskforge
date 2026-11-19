# TaskForge

A Java 21/Spring Boot CSV analytics platform for studying durable asynchronous work, competing workers, and failure recovery.

**Status: phase 0 design. Not yet a working platform.** Follow [ROADMAP](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md), [requirements](docs/REQUIREMENTS.md), and the incremental [engineering journal](docs/ENGINEERING_JOURNAL.md). Executed evidence is in [TESTING](docs/TESTING.md); proposed features are not accomplishments.

Local-first: PostgreSQL owns job state, Redis Streams carry work notifications, MinIO stores immutable inputs and attempt-specific reports. One modular application runs as independent API, worker, and dispatcher processes. No cloud resources or costs are authorized.
