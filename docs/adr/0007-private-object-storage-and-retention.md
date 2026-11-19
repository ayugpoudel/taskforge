# ADR-0007: Private immutable objects, reserved quotas, eventual cleanup

Date: 2026-11-19. Status: accepted.

Problem: CSVs should not inflate PostgreSQL/Redis; storage and SQL do not share a transaction. Upload failures and losing workers can leave orphan objects, while cleanup must not delete live or canonical results.

Requirements: 50 MiB input bound, owner isolation, immutable input/report keys, safe expiry and resource budgets. Alternatives: SQL blobs, local shared disk, public object URLs, presigned transfers, private MinIO/S3 with authenticated API proxy.

Decision: private bucket, server-generated keys, SHA-256 input metadata verified again during processing. Multipart input spools to a temporary disk file, with bearer authentication before parsing and bounded server threads/connections. A per-owner SQL reservation precedes object upload; only ready/unexpired owned datasets can be submitted. Ambiguous failed uploads retain reservation evidence until cleanup.

Results use attempt-specific keys and authorized streaming downloads. Input expiry uses row locks that conflict with job creation's FOR SHARE lock, preventing deletion after an accepted job starts referencing the input. Canonical report expiry is recorded before deletion; GET then returns 410. Cleanup protects live attempts and unexpired canonical output, deleting old losing candidates in bounded batches. Eligible keys are rechecked hourly so a very late stale upload can be removed again. Retention is eventual and depends on cleanup/storage availability; there is no exact deletion deadline guarantee.

Default input/output retention is seven days, orphan grace one hour, owner input quota 200 MiB. Job/attempt metadata remains for audit/idempotency; metadata lifecycle must be chosen before production-scale operation. These are documented development limits, not production capacity claims.

Tradeoffs: proxying input consumes API disk/bandwidth; quotas and connection limits bound exposure but are not a complete internet-facing abuse system. Reserved uploads can temporarily consume quota after failure. Shared service credentials remain server-side; clients never receive object keys or write credentials.

Presigned transfers considered but deferred: a correct upload-finalization endpoint must verify object size, hash and identity before marking it ready. Adding a URL alone weakens validation. At larger file sizes use that explicit upload protocol, scoped credentials and a gateway/body limit.

Evidence: ObjectStorage, ApiAuthentication filter, LimitedInputStream, JobStore storage reservations, StorageCleanup, V5 migration; tests cover pre-multipart auth, identity-field rejection, path-like filenames, concurrent quota reservations, integrity corruption, live/canonical cleanup protection, report expiry and late stale-object cleanup. See TESTING.md for executed status.

Revisit: larger uploads, production identity/IAM, compliance deletion requirements, large metadata volume, or measured proxy bottlenecks. Local source-built archived MinIO is only a test fixture; production design uses managed S3.
