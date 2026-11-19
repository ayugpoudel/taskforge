# Phase 4 plan: bounded work and cancellation races

Admission checks active-job capacity in a serialized short transaction, after checking whether an idempotent request already exists. Return 429 with Retry-After when full. Add a per-owner scheduling row to serialize competing claims for that owner and cap live RUNNING leases; use a consistent owner-before-job lock order. Keep approximate FIFO/due-time ordering rather than adding unmeasured priority complexity.

Cancellation is a logical DB transition, not immediate physical termination. A queued/retrying/running job can atomically become CANCELLED, fencing any result commit. If completion wins first, cancellation returns the existing SUCCEEDED state. Checkpoints/heartbeats cooperatively stop work. A total job deadline includes queue wait and retries; expired nonterminal jobs become FAILED with a deadline code.

Stop accepting new work during graceful shutdown, allow in-flight work a bounded drain, then interrupt cooperatively. A killed worker's lease is recoverable by the dispatcher. Tests must cover concurrent admission, per-owner claims, deadline expiry, cancellation/completion, and worker drain behavior.
