# ADR-0002: Redis Streams as reconstructable work notifications

Date: 2026-11-19. Status: accepted.

Problem: distribute queued work across independent workers and learn delivery/recovery mechanics without placing durable job truth in broker memory.

Requirements: competing consumers, acknowledgement, inspectable pending state, local reproducibility, no paid infrastructure. Alternatives: Redis Streams, RabbitMQ work queues, SQS standard, PostgreSQL SKIP LOCKED polling. See the explicit comparison in ARCHITECTURE.md.

Decision/reasoning: Redis Streams with one consumer group, while PostgreSQL retains accepted jobs and outbox state. Consumer IDs include a unique process ID and slot. Notifications contain only job IDs. A bounded scan consults DB eligibility before XCLAIM; every tenth receive turn gives pending work a chance even during sustained new arrivals.

Tradeoffs: more moving parts than PostgreSQL-only scheduling; AOF everysec is not lossless; unread/pending messages must not be blindly MAXLEN-trimmed. Terminal acknowledgements delete their entries because exactly one consumer group is supported. PostgreSQL reconciliation republishes due jobs whose last notification is older than 30 seconds. Brief duplicates are normal. Pending terminal duplicates are cleaned by the bounded scan.

Rejected alternatives: Redis Pub/Sub has no durable pending/ack model. PostgreSQL-only is simpler and remains a valid deployment choice; retained local-worker mode demonstrates it. RabbitMQ offers dedicated broker features, SQS reduces AWS operations, but neither is the requested learning target.

Evidence: RedisQueue, StreamWorker, Dispatcher; `redisGroupDistributesNotificationsAndAcknowledgesDurableCompletion`, `redisLossIsReconciledFromDurableQueuedState`, phase-2 two-process E2E artifact. Pending/live-lease and restart tests are in PlatformIntegrationTest; see TESTING.md for executed status.

Revisit: DB scheduling load, Redis memory use, operational cost, or durable notification requirements exceed this design; managed SQS may be preferable on AWS.
