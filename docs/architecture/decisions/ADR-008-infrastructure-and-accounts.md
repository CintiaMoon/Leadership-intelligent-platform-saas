# ADR-008: Infrastructure as Code, Domain, and AWS Account Structure

- Status: Proposed. The product owner approved the recommendations on 2026-10-17 (D-69, D-70, D-71); this ADR is not Accepted until it is reviewed together with ADR-007
- Date: 2026-10-17
- Decision owners: TBD
- Related: decision register D-69, D-70, D-71, D-08; ADR-001, ADR-007

## Context
ADR-007 fixes AWS, us-east-1, ECS Fargate, RDS PostgreSQL, GitHub Actions and a US$300 monthly budget. It does not say how infrastructure is defined, what the public names are, or how AWS accounts are separated.

## Decision

### Infrastructure as code
- All AWS resources are defined as code using Terraform (or OpenTofu; the choice is made in the implementation plan). Console changes are not a supported way to change an environment.
- State is stored in S3 with locking and versioning, encrypted, one state per environment. Secrets never appear in code or state outputs.
- A pull request shows the plan. Apply to production runs from GitHub Actions through an OIDC role after approval.

### Domain and DNS
- The product domain name is `liptech.io`, owned by the product owner (stated 2026-10-17). The registrar is not recorded; the nameservers must be delegated to the Route 53 zone before DNS work starts.
- Route 53 hosts the zone and ACM issues certificates.
- `app.liptech.io` is the public BFF. The Spring API is private and has no public DNS name (ADR-001).
- Staging and development use separate subdomains (for example `staging.liptech.io`).
- SES is configured with SPF, DKIM and DMARC before any production email is sent.

### AWS accounts
- Three accounts under AWS Organizations: management/shared (billing, service control policies, CloudTrail archive), staging, and production. Development environments live in the staging account for now; a separate account can be added later.
- Root users have MFA and are not used day to day. People sign in through IAM Identity Center. CI uses OIDC roles per account and no long-lived access keys.
- CloudTrail is enabled in every account. A billing alarm is set at 80% of the budget.

## Alternatives considered
- AWS CDK: ties the tooling to AWS and adds an abstraction layer to review.
- Four accounts: cleaner separation, more overhead. Deferred.
- One account: rejected because a staging mistake could affect production.

## Consequences
- Terraform state and the OIDC roles must be bootstrapped manually once, and documented in a runbook.
- Three accounts add small baseline costs (CloudTrail, NAT or endpoints per account). The cost estimate in ADR-007 must be rechecked against the US$300 ceiling.

## Verification
- A clean environment can be created from code only.
- No long-lived AWS keys exist in the repository settings.
- The API has no public address.
