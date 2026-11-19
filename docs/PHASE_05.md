# Phase 5 plan: protect files, identities and resource budgets

Move bearer authentication into a servlet filter before multipart parsing. Keep identity derived only from configured random tokens; no client owner/key fields. Reject unsupported JSON fields. Bound HTTP connections/threads and file size; reserve a per-owner storage budget before uploading bytes. Only ready, unexpired, owned datasets can create jobs. Persist upload reservations so ambiguous storage/database failures can be cleaned later.

Retain private immutable inputs and attempt-specific report keys. Add bounded retention sweeps: expire datasets only after protecting against concurrent job creation with dataset row locks; protect live attempt artifacts and the authoritative output; mark expired reports before deletion. Recheck eligible old candidates periodically so late stale uploads are eventually removed. Cleanup is eventual, not an exact deletion-time promise.

Validate malformed/oversized input, owner isolation, unsupported identity fields, path-like filenames, quota races, and cleanup safety. Keep reports behind authenticated streaming GET. Presigned uploads are deferred because safe finalization requires verifying uploaded object size/checksum/ownership, not just generating a URL; for 50 MiB local inputs a bounded proxy is sufficient. S3 production design is documented later; no cloud storage created.
