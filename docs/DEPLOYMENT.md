# Local deployment, CI and an unapplied AWS plan

## Executed locally

Java 21 and Docker with Compose are required. `./scripts/start-local.sh` generates random private credentials, compiles the application, builds the pinned MinIO source fixture, and starts the full stack with two workers. Run `./gradlew clean check bootJar` after building the MinIO image to reproduce the required checks; integration setup fails instead of skipping if Docker is absent. `docker compose stop` stops this stack without deleting its volumes. Deleting volumes is destructive and is not part of routine setup.

The one application image supports API, worker and dispatcher roles. All published local ports bind to loopback. PostgreSQL and Redis use persistent named volumes; MinIO uses a private data volume. Worker heap is bounded by its container budget, executor slots are bounded, and input/field/column/storage/admission limits are explicit. These limits are development settings, not measured production capacity.

## Hosted CI

The public repository is https://github.com/ayugpoudel/taskforge . A real main-branch CI run passed at [dfc58ae](https://github.com/ayugpoudel/taskforge/actions/runs/44607116098), before the later formatter/cloud-configuration additions. Subsequent commits must pass their own runs; the badge is the live status.

The workflow builds the source-pinned MinIO fixture, compiles on Java 21, runs required unit/integration/concurrency/fault tests and format checks, validates Compose without printing secrets, builds the application image, and validates the CloudFormation schema. Dependency update PRs are reviewed and tested; their results are separate from main-branch CI. Do not merge major updates merely because they are available.

## AWS design — NOT DEPLOYED

`infra/aws/taskforge-stack.json` passes local cfn-lint schema validation. No AWS stack, registry, S3 bucket, RDS instance, Redis cache, container task or paid resource was created. No credentials were needed for validation. CloudFormation schema validity does not prove a deployment can succeed.

The template expects existing VPC/public/private subnets, an existing ACM certificate and a prebuilt ECR image pinned by digest. These prerequisites are parameters rather than silently creating another network/domain. Check that private subnets span two AZs and public subnets have Internet Gateway routes.

| Component | Choice and reason | Limits / alternative |
| --- | --- | --- |
| Compute | ECS Fargate, independent API/dispatcher and 1–4 workers from one digest-pinned image | More baseline cost than one EC2 host; single EC2 Compose is cheaper for a temporary demo but has one-host failure/patching responsibility. |
| State | Encrypted private RDS PostgreSQL 17, managed master secret, seven-day backups, deletion protection | One small single-AZ instance is a cost-conscious demo, not database HA. Choose Multi-AZ and a separate application/migration DB role before production use. |
| Queue | Private ElastiCache Redis 7.1, transport encryption/auth, one node | No failover node. Queue notifications can be reconstructed, but cache outages delay processing. SQS would reduce broker operations on an AWS-only platform. |
| Objects | Private encrypted/versioned S3 bucket and task-role credentials | Current objects expire through guarded application cleanup; S3 rules cover aborted uploads/noncurrent versions only. Versioning extends physical retention. |
| Ingress | HTTPS ALB using an existing ACM certificate; allowed client CIDR | Only `/api/*` is forwarded. Actuator endpoints are not exposed by the listener. Point the certificate's hostname to the ALB; its AWS DNS name will not match your certificate. |
| Egress/network | Tasks in public subnets with public IPs; inbound restricted to the ALB SG | Avoids NAT Gateway fixed cost for this demo. Private task subnets with NAT/VPC endpoints are a different cost/security tradeoff. Database/cache stay private. |
| Secrets/IAM | Task execution role reads only the configured runtime secrets; task role accesses only the artifact bucket | Static development bearer mappings remain a small demo auth mechanism; replace with reviewed OIDC/JWT and appropriate ownership mapping before public multi-user deployment. |
| Operations | CloudWatch JSON logs, seven-day retention; ECS CPU/memory health | Local Prometheus/Grafana are validated. A managed metrics deployment and alarms are not included or claimed deployed. |

The app accepts `TF_REDIS_TLS=true` and `TF_REDIS_PASSWORD`. Without static storage credentials it uses the MinIO Java SDK's AWS IAM provider for ECS task credentials. The S3 region/endpoint are explicit. RDS uses `sslmode=verify-full` and the downloaded AWS CA bundle included in the image; its source/hash are recorded in `docs/evidence/rds-ca-bundle.json`. Refresh the public bundle before a real deployment and check certificate rotation requirements.

Readiness intentionally includes PostgreSQL and Spring readiness state, not Redis. The API can durably accept a job/outbox while the broker is unavailable. Overall health still exposes dependency health for local diagnosis. Workers/dispatcher retry network failures; restarting them is not a replacement for state recovery.

## Cost estimate — planning allowances, not a quote

Model: US East (N. Virginia), 730 hours/month, default two workers, API and dispatcher each 0.25 vCPU/0.5 GiB, each worker 0.5 vCPU/1 GiB. The ARM64 default totals 1.5 vCPU and 3 GiB. AWS's published ARM example rates imply about **$43/month for Fargate compute**; x86 would be about $54. No Spot/free-tier/credits assumed. [AWS Fargate pricing](https://aws.amazon.com/fargate/pricing/).

Four task public IPv4 addresses plus two ALB addresses at the published $0.005/address-hour add approximately **$22/month**, before transient rolling-deployment/scaling addresses. [VPC pricing](https://aws.amazon.com/vpc/pricing/).

Reserve these additional **unpriced planning allowances**, to be replaced by a fresh regional AWS Calculator estimate before approval: RDS+storage $15–35/month, one small managed Redis node $12–30/month, ALB/capacity $20–30/month, S3/logs/secrets/image storage $5–15/month. This puts the default demonstration at roughly **$120–180/month**, with data transfer, unusually large logs/storage, backups/snapshots, taxes and prerequisite network/domain costs potentially adding more. These ranges are not verified SKU quotes. See [RDS PostgreSQL pricing](https://aws.amazon.com/rds/postgresql/pricing/), [ElastiCache pricing](https://aws.amazon.com/elasticache/pricing/) and the [AWS Pricing Calculator](https://calculator.aws/).

For a short portfolio demo, prefer the already working local stack or a deliberately scheduled temporary deployment. A managed always-on stack is not necessary to demonstrate the code. Stopping ECS workers does not stop RDS/cache/ALB/IP/retained-storage charges. Retained buckets and snapshots need a separate approved cleanup plan.

## Before any paid deployment

Obtain explicit budget/spending approval, region, allowed client CIDR, domain/certificate, network and ECR image details. Confirm an available RDS PostgreSQL 17 minor version via `describe-db-engine-versions`; the template does not invent regional availability. Verify image architecture matches ARM64/X86_64, review IAM/network/secrets and the CloudFormation change set, and arrange retention/teardown ownership. Provisioning, DNS writes and image pushes have not been run.

After an approved deployment, execute the same upload/report/ownership/failure scenarios against it; verify ECS credentials, Redis TLS/auth, RDS certificate validation, S3 permissions, restart/scale behavior and actual costs. Those cloud-specific paths are currently compiled/configured but **runtime untested**. Local MinIO/plain-Redis tests do not validate them.
