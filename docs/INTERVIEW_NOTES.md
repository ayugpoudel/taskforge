# Interview notes

TaskForge accepts CSV analysis jobs and returns a job ID while an independent worker streams the data into a report. PostgreSQL describes durable truth; a queue tells workers to look for work. Delivery can repeat, so completion must be guarded rather than relying on queue acknowledgement alone.

Phase 0: this is the accepted design, not yet a completed feature. Add code-grounded examples and measured bottlenecks as phases pass.

## Phase 1 walkthrough

The API first streams a multipart CSV into a temporary disk file and computes SHA-256 as it reads. It stores immutable input bytes in a private object bucket and inserts owner-scoped dataset metadata. A job references that dataset. Accepting a job persists a QUEUED row and returns HTTP 202; report generation runs in a different process. The client polls its own job and downloads a report only after SUCCEEDED.

**Why not store the CSV in Redis?** A notification is a tiny identifier. Large inputs would increase broker memory, transmission, and retry cost; object storage is the appropriate byte store.

**What does the database lock protect?** The short claim transaction. It chooses a queued row with SKIP LOCKED, updates ownership/generation, and records an attempt. It does not hold a row lock while parsing the CSV. Conditional completion rejects an expired/wrong owner or changed generation.

**What is bounded-memory processing?** We keep accumulators per column and one current record, not a list of all rows. Each field and the number of columns also have limits; saying streaming without those bounds would still permit a malicious giant field.

**What does a numeric statistic mean in a mixed column?** It summarizes the numeric cells only; inferred type is STRING if any nonmissing value is neither numeric nor an all-boolean column. Missing counts cover valid-width records; invalid-width records are counted separately and excluded from aggregates. Header-only input produces zero rows. JSON is the supported report format.

**What is not reliable yet in phase 1?** A process crash leaves a live attempt until its lease expires; no recovery loop or heartbeat exists yet. SQL polling makes work durable, but it is not yet the final distributed queue/recovery design. These boundaries must be kept separate from completed features.
