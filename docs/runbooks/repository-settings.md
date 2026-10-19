# Repository Settings (GitHub)

- Status: Applied on 2026-10-19; see "Applied" below.

## Branch protection for `main`
- Require a pull request before merging.
- Require status checks to pass, once CI exists (build, tests, consistency check, secret scan).
- Block force pushes and branch deletion.
- Code-owner review is required once a second reviewer exists (see `.github/CODEOWNERS`). With one maintainer, requiring approvals would block all merges.
- Preferred: signed commits.

## Security features
- Secret scanning and push protection: on.
- Dependabot alerts and security updates: on. Add `.github/dependabot.yml` when `pom.xml` and `package.json` exist.
- Private vulnerability reporting: on.
- Actions: restrict to GitHub-owned and explicitly allowed actions; default workflow token permissions read-only; deployment uses OIDC roles (ADR-008).

## Applied 2026-10-19 (read back with `gh api`)
Applied by Claude Code with `gh api` at the product owner's instruction. The first attempts were refused (HTTP 403/422/404) while the repository was private without GitHub Pro; they succeeded on retry after the repository became public (`private: false` in the API response).

| Setting | Result |
|---|---|
| CI workflow and `dependabot.yml` | Merged (PR #2); all four jobs passed |
| Branch protection on `main` | PR required (0 approvals, single maintainer); strict required checks `api`, `web`, `contract`, `secret-scan`; force pushes and deletion blocked; not enforced for admins |
| Secret scanning and push protection | Enabled |
| Dependabot alerts and security updates | Enabled |
| Private vulnerability reporting | Enabled |
| Actions allowed | Selected: GitHub-owned and `gitleaks/gitleaks-action@*` only |
| Default workflow token | `read`; Actions cannot approve PRs |
| Signed commits | Not enabled (preferred only) |

Notes: the repository is public, so the full history is visible; the `secret-scan` job scans full history and passed, but that is not a substitute for a manual review. With `enforce_admins: false` the owner can still bypass protection; turn it on once a second maintainer exists or if bypass is not wanted.
