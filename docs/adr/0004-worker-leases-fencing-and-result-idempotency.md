# ADR-0004: Fence completion and isolate every attempt's object

Date: 2026-11-19. Status: accepted.

Problem: a paused worker can resume after another worker recovers its expired lease. A timeout or Redis claim alone cannot stop the old process from writing.

Requirements: independent workers, short SQL transactions, live-lease checks, recoverability, one authoritative result pointer. Alternatives: long-held row locks, only Redis consumer ownership, shared deterministic object keys, generation-fenced completion with attempt keys.

Decision: PostgreSQL claims increment a monotonic generation and append an attempt. A dedicated renewal thread heartbeats independently of CSV computation. Completion/failure/renewal check job ID, generation, owner, RUNNING state and unexpired lease. Completion also checks cancellation and deadline. A failed/uncertain renewal stops that attempt from committing. Processing checkpoints notice lost ownership; they are cooperative, not immediate termination.

Before uploading a result, persist an attempt-specific candidate key. Every output key includes job ID, generation and a random suffix. Only a guarded DB completion exposes one key. Old workers cannot overwrite a newer worker's object because they use different keys. If finalization has an uncertain outcome, retain the candidate for safe cleanup rather than deleting a possibly authoritative object.

Tradeoffs: duplicate CPU work and orphan objects are possible. A live lease is not proof of process health; a dead process is detected only after expiry. Heartbeat traffic grows with active worker concurrency. No exactly-once execution or arbitrary external-side-effect guarantee is made.

Rejected: a deterministic shared output key lets a stale process corrupt correct bytes even if its DB update is rejected. Holding row locks throughout file processing monopolizes DB resources. Redis ownership does not fence PostgreSQL/S3 writes.

Evidence: JobStore.claim/heartbeat/candidateOutput/complete, JobRunner; stale-generation/object-isolation, competing-claim, completion-before-ack tests. Test status recorded in TESTING.md.

Revisit: long network pauses, clock skew, storage policies, or higher concurrency suggest different renewal/lease budgets. Correctness uses database time at the commit boundary; application time is not sufficient authority.
