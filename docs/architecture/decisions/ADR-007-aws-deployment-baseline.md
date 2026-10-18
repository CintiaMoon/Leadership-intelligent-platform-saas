# ADR-007: AWS Deployment and Operations Baseline

- Status: Proposed — the inputs below were given by the user on 2026-10-17; the AWS design built on them is a proposed industry-practice baseline that still needs review
- Date: 2026-10-17
- Decision owners: DevOps lead, Architect (roles, see the decision register)
- Related: decision register D-08, D-40, D-41, D-50, D-42; ADR-001, ADR-003, ADR-004; NFR-SEC-005, NFR-OPS-001, NFR-REL-003

## Context
The BFF topology (ADR-001) needs a public BFF, a private API, a trusted-proxy policy, a rotated BFF-to-API secret, and tested cookie behavior in an environment that matches production. ADR-003 and ADR-004 put outbox workers and rate-limit counters on PostgreSQL, so the database and connection pools matter. `CLAUDE.md` requires a dedicated test database with isolated credentials and no secrets in source control.

## Inputs (given by the user, 2026-10-17)
- Cloud provider: AWS. Region: us-east-1.
- Availability target: 99%.
- Operating model: business-hours support, no 24x7 on-call.
- CI: GitHub Actions.
- Production email provider: Amazon SES.
- Backup retention window: 40 days.
- Budget ceiling: US$300 per month.
- Recovery point objective (RPO): 15 minutes. Recovery time objective (RTO): 4 hours.

- Database availability: single-AZ for development and staging, Multi-AZ for production, provided the budget allows it.
- Source hosting: a private GitHub repository, with Git initialized before any further implementation.
- The RTO is kept at 4 hours and is defined as a recovery-capability target, separate from human support coverage (see "Availability, recovery, and operations").

Git was initialized locally on 2026-10-17 (branch `main`, with a `.gitignore`); the first commit was pushed on 2026-10-17 to the private GitHub repository `Leadership-intelligent-platform-saas` (remote `origin`).

## Decision (proposed baseline on AWS)
Items marked **(P)** are proposals that nothing in the specs or the inputs requires.

### Accounts and environments
- Environments: local (Docker Compose and Testcontainers), CI, staging, and production.
- Separate AWS accounts for staging and production under AWS Organizations, so a mistake or compromise in one does not reach the other **(P)**.
- Each environment has its own database, secrets, and credentials. Staging matches production routing so ADR-001's cookie and CSRF tests are meaningful.

### Compute
- Amazon ECS on AWS Fargate runs three services from container images stored in Amazon ECR: the Next.js BFF, the Spring Boot API, and a worker profile of the API for outbox delivery and the purge job **(P)**.
- Images are non-root, use a minimal pinned base image, are built once and promoted unchanged between environments, and are scanned by ECR. Tags are immutable.
- At least two tasks per web service, spread across two Availability Zones, so shared rate-limit counters and `SKIP LOCKED` workers are exercised.
- Alternatives: EKS (more operating cost than the current team size justifies) and App Runner (less network control).

### Network and ingress
- One VPC in us-east-1 across at least two Availability Zones with three subnet tiers: public (load balancer), private application, and isolated database.
- Amazon CloudFront with AWS WAF in front of an Application Load Balancer is the only public entry point **(P)**. The load balancer forwards only to the BFF.
- The API has no public route. It is reachable only from the BFF's security group (internal load balancer or ECS Service Connect).
- TLS: ACM certificates, TLS 1.2 or higher with a current security policy, HSTS set by the BFF.
- Forwarded headers: the application trusts `X-Forwarded-For` only from the load balancer and CloudFront address ranges, and takes the rightmost untrusted hop, as ADR-001 specifies. Configure the load balancer's forwarded-header handling deliberately and test it; do not rely on defaults.
- VPC endpoints for ECR, S3, Secrets Manager, and CloudWatch Logs keep traffic off the public internet and reduce NAT use. Outbound access is limited to Amazon SES.
- AWS WAF rate-based rules complement, and do not replace, the PostgreSQL limiter in ADR-004.

### Database
- Amazon RDS for PostgreSQL in the isolated subnets. Production is Multi-AZ, provided the budget allows it (decided 2026-10-17). If the AWS Pricing Calculator check shows the ceiling would be exceeded, production falls back to single-AZ with point-in-time recovery until the budget allows Multi-AZ. Development and staging use single-AZ.
- Encryption at rest with AWS KMS, TLS enforced for connections, automated backups with point-in-time recovery.
- Backup retention is 40 days (D-40). RDS automated backups and point-in-time recovery retain at most 35 days, so scheduled snapshots through AWS Backup retain recovery points for the full 40 days. Point-in-time recovery therefore covers the most recent 35 days only.
- Aurora PostgreSQL is the alternative if failover speed or scale later requires it.
- Three database roles: a migration role used only by the deploy step, an application role with data access only, and a read-only operations role.
- A small separate connection pool for rate-limit counters with a short timeout (ADR-004).
- Flyway migrations run as a gated deploy step before the new application version starts, using expand/migrate/contract changes (NFR-OPS-001).
- RDS Proxy is not required at the start. Add it if connection counts become a problem.

### Secrets and configuration
- AWS Secrets Manager holds the secrets: database credentials for the migration and application roles (with automatic rotation), the BFF-to-API shared secret (D-50, rotated at least every 90 days with two valid during rotation), and the SES credentials if SMTP is used.
- ECS task roles give each service least-privilege access to only its own secrets. SES sending uses the task role through the SES API where possible, which avoids storing SMTP credentials **(P)**.
- Non-secret configuration, including the purge-enabled flag (default off, D-42), lives in SSM Parameter Store per environment.
- Secrets never appear in images or the repository, and CI scans for them.

### Email (Amazon SES)
- Verify the sending domain with DKIM, and publish SPF and DMARC records.
- Request production access (leaving the SES sandbox) before launch, and plan within the SES sending quotas for us-east-1.
- Handle bounce and complaint notifications (through Amazon SNS) by suppressing the affected addresses, recording the event in the outbox, and never retrying suppressed addresses.
- Delivery goes through the outbox (ADR-003), so a failed send is retried and does not run inside a database transaction.

### Delivery pipeline (GitHub Actions)
- The repository is a private GitHub repository, and Git must be initialized before any further implementation (decided 2026-10-17). Git is initialized and the first commit is pushed to the private GitHub repository `Leadership-intelligent-platform-saas`.
- CI authenticates to AWS with GitHub's OIDC federation and a separate least-privilege role per environment, so no long-lived AWS keys are stored.
- Third-party actions are pinned to full commit hashes. Branch protection requires review and passing checks. Production deployments use a protected GitHub environment with a required approver **(P)**.
- Enable GitHub secret scanning with push protection and dependency updates through Dependabot **(P)**.
- The pipeline uses the Maven wrapper (ADR-002), runs backend and frontend tests, scans dependencies and images, and produces an SBOM.
- Deployments are rolling or blue-green through ECS with health and readiness checks, graceful shutdown, and a documented rollback.

### Observability and security services
- Structured JSON logs to CloudWatch Logs with redaction (no passwords, session cookies, or raw tokens) and a set retention period.
- CloudWatch metrics and alarms for error rates, latency, database health, and rate-limit store failures.
- AWS CloudTrail enabled in all accounts. Amazon GuardDuty enabled **(P)**.

### Availability, recovery, and operations
- Availability target: 99% (decided 2026-10-17). In a 30-day month that allows about 7 hours of downtime.
- Operating model: business-hours support with no 24x7 on-call. Alarms notify a monitored channel during business hours, and the escalation steps are written in a runbook. An outage outside business hours may last until the next business day, which fits a 99% target only if outages are rare and short.
- Recovery targets (decided 2026-10-17): data loss up to 15 minutes (RPO) and recovery within 4 hours (RTO). Point-in-time recovery supports the RPO for the most recent 35 days; recovery points older than that are snapshot-based.
- The 4-hour RTO is kept (decided 2026-10-17) and is defined as a recovery-capability target, separate from human support coverage:
  - Recovery time (RTO): the time from when recovery begins to when service is restored, achievable through automation and a documented, rehearsed runbook. The restore drill measures it.
  - Support coverage: business hours only, with no 24x7 on-call. The RTO does not promise a human response outside business hours.
  - Automation restores service without a human where it can: ECS replaces failed tasks, and Multi-AZ failover in production recovers the database. A failure that needs a person starts its recovery clock when that person starts the runbook, which for an out-of-hours incident is the start of the next business day unless the alert is acknowledged earlier.
  - Availability is measured against the 99% target and counts outage time, including time before recovery starts.
- A restore drill on a schedule you choose (NFR-REL-003).
- Cross-Region disaster recovery is out of scope for the MVP.

## Cost against the US$300 per month ceiling
The figures below are my rough estimates of us-east-1 list prices from memory. They are not verified; confirm them with the AWS Pricing Calculator before approving this ADR.
- The full baseline above (Fargate with two tasks per web service and a separate worker service, an Application Load Balancer, CloudFront and WAF, NAT gateways in two zones, interface VPC endpoints, RDS Multi-AZ, Secrets Manager, GuardDuty, logs) would cost roughly US$300 to 400 per month for production alone. With a staging account it would exceed the ceiling.
- Lean profile **(P)**, which should fit the ceiling:
  - Production: two small tasks each for the BFF and API, with the outbox and purge worker run inside the API tasks instead of a separate service; one NAT gateway; the S3 gateway endpoint (free) and no interface endpoints; AWS WAF attached to the load balancer, with no CloudFront; GuardDuty deferred; a small Multi-AZ RDS instance (single-AZ fallback if over budget).
  - Staging: one task per service, a small single-AZ database, and scheduled shutdown outside business hours.
  - Rough estimate with Multi-AZ production: US$180 to 280 per month for production and US$40 to 80 for staging. The sum (about US$220 to 360) can reach or exceed the US$300 ceiling, so the Pricing Calculator check decides whether production keeps Multi-AZ.
- Multi-AZ RDS for production is the largest optional cost and is included provided the budget allows it. Single-AZ RDS still meets a 99% availability target, a 15-minute RPO (point-in-time recovery), and a 4-hour RTO (restoring from a backup typically fits), so it is the fallback.
- Where the lean profile and the baseline above differ, the lean profile applies until the budget allows more.

## Inputs still needed
- The AWS Pricing Calculator check of the lean profile against the US$300 ceiling, including whether production keeps Multi-AZ.
- Confirmation of the lean profile.
- The legal and privacy position on purged data remaining in backups for up to 40 days (D-40).
- Enabling branch protection, secret scanning with push protection, and Dependabot on the GitHub repository.

## Alternatives considered
- EKS or self-managed Kubernetes: more flexibility, much more operating cost.
- A single AWS account for all environments: simpler, but weaker isolation.
- Aurora from the start: faster failover and scaling, higher cost.

## Consequences
- A NAT gateway and several VPC endpoints add recurring cost; this needs measuring against the budget.
- Production behavior depends on the forwarded-header and security-group configuration, so those tests are release gates (ADR-001).
- Purged company data can persist in backups for up to 40 days after a purge. SPEC-DEL and the privacy documentation must state this accurately (NFR-PRIV-003).
- Several operator runbooks are required before launch: enabling the purge job, operator plan changes, ownership resolution after an Owner account is disabled, manual recovery, and database restore.

## Verification
- A staging deployment reproduces the production routing and passes the cookie, CSRF, forwarded-header, and rate-limit tests from ADR-001 and ADR-004.
- A restore drill recovers the database to a chosen point in time, and a restore from a snapshot older than 35 days succeeds.
- The migration role is the only one able to change the schema, verified by attempting DDL as the application role.
- A secrets scan of the repository and images finds nothing.
- A test email is delivered through SES from the verified domain, and a simulated bounce is suppressed.
