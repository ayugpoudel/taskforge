# ADR-0008: Validate locally, prepare an unapplied ECS design

Date: 2026-11-19. Status: local deployment accepted; AWS deployment proposed/unapplied.

Problem: an inspectable portfolio platform needs repeatable operation without unapproved recurring costs or a false cloud-deployment claim.

Requirements: one-command local setup, independent process roles, real CI, explicit cloud budget approval, minimal managed services. Alternatives: one EC2/Compose host, ECS Fargate plus managed state, Kubernetes, serverless handlers with SQS.

Decision: Compose is the tested deployment; Fargate/RDS/ElastiCache/S3 is the reviewable cloud template. Existing network/certificate/image prerequisites are explicit. No cloud create/apply calls are made. Prefer task-role S3 credentials, TLS/auth for Redis, RDS verify-full, private data services, scoped bucket/secrets permissions, HTTPS ingress and restricted client CIDR. Readiness depends on PostgreSQL, permitting outbox acceptance during Redis outage.

Tradeoffs: managed services have meaningful baseline cost; single-AZ database/cache are demo cost choices, not HA. Public task egress avoids NAT fixed cost but requires strict ingress SGs. Kubernetes adds no justified value here. One EC2 host would cost less but concentrates failure and patching duties.

Evidence: Compose two-worker E2E and dashboard diagnosis, real main CI run, local cfn-lint success. AWS runtime paths, regional engine availability, DNS/certificate and IAM behavior remain untested. Cost assumptions and current references are in DEPLOYMENT.md.

Revisit: explicit budget approval, measured capacity/availability requirements or different deployment ownership. Use SQS if reducing AWS broker operations matters more than the Redis learning target.
