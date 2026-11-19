# Final reliability review

## Invariants inspected

- Acceptance writes job and pending outbox in the same PostgreSQL transaction; an injected outbox insert failure rolls the job back.
- Owner/request-key uniqueness is independent from worker delivery idempotency. Same-key competitors serialize; conflicting payloads fail.
- Worker computation holds no long database lock. Claim, heartbeat, failure and completion are short conditional state operations using database time.
- Ownership generation is monotonic, including administrative replay. Old workers cannot renew/finalize a newer generation.
- Every attempt uses a unique object key. Only one guarded database pointer is authoritative; physical orphan files are possible and cleanup is eventual.
- Acknowledgement follows known durable completion/terminal state. Pending reclamation consults PostgreSQL eligibility; queue age does not override a live lease.
- Retries persist availability and bounded attempt history. Invalid CSV is permanent; terminal failure retains diagnostics. Long outages can exhaust attempts/deadlines rather than guaranteeing eventual success.
- Cancellation and completion serialize on the job row. Logical cancellation fences output; cooperative processing may stop later.
- Active backlog, owner concurrency, process slots, HTTP connections, upload bytes and owner storage have explicit limits. Streaming also caps field length/column count.
- Upload-ready and dataset-expiry transitions are coordinated with acceptance. Final audit now rejects an expired reservation instead of returning a false 201.
- Report storage is opened before success headers and streamed through a Spring-managed resource; open failure returns sanitized 503. A network interruption during a transfer remains a safe GET-retry concern.
- Secrets/row contents are not logged or placed in queue messages; ownership precedes every job/attempt/result operation. Credentials remain ignored local configuration or proposed managed secrets.

## Lock ordering

Request acceptance holds a dataset share lock, then optional key advisory lock, then global admission lock. It does not acquire an owner scheduling lock. Storage reservation uses an owner scheduling lock before a new dataset insert. Worker claims lock owner scheduling before modifying a job. Completion/cancellation/failure lock the job; operations that also touch an outbox follow job-before-outbox order. Outbox leasing itself touches only the event in a short transaction and publishes outside SQL.

Expiry locks datasets before checking active job references, conflicting with acceptance's share lock. Report cleanup uses immutable attempt keys and cannot promote a losing key to canonical output. This ordering was reviewed for cycles and exercised in concurrent tests; it is not a proof against every future code change.

## Deliberately retained limitations

Single-host Compose and single-AZ cloud design are not high availability. Development bearer-token mappings are not a full production identity system. Arbitrary external effects are not exactly once. Metadata retention remains an operational decision; retained-history counters assume metadata is not purged. Storage cleanup is eventual and versioned S3 retains noncurrent bytes beyond logical expiry.

The archived Redis interruption took 53.882 seconds for a 16-job batch despite a three-second outage; its only publication was delayed ~52.6 seconds. Explicit connection establishment timeout was added during audit. A separate follow-up is recorded if executed; neither observation establishes an RTO. Client reconnect/DNS behavior and longer outage handling need additional isolation before any recovery-latency claim.

Load-test database interruption recovered accepted work without extra attempts in that run; it did not force every in-flight code boundary. Deterministic real-service tests separately exercise failed heartbeats, stale ownership, rollback and post-publish failure. Final evidence must distinguish those test scopes.

## Final execution evidence

See TESTING.md, the final audit journal entry, hosted CI and `docs/evidence/`. Only executed tests are reported as passing. Cloud-specific IAM/TLS/DNS/deployment behavior remains runtime untested; CloudFormation schema validation alone is not deployment evidence.
