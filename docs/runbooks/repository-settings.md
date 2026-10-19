# Repository Settings (GitHub)

- Status: Partly applied on 2026-10-19; see "Applied" below. Branch protection, secret scanning, and private vulnerability reporting are blocked by the GitHub plan.

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
Applied by Claude Code with `gh api` at the product owner's instruction. The repository is private on a plan without GitHub Pro.

| Setting | Result |
|---|---|
| CI workflow and `dependabot.yml` | Merged (PR #2); all four jobs passed on the PR |
| Dependabot alerts | Enabled (204) |
| Dependabot security updates | Enabled (`enabled: true`) |
| Actions allowed | Selected: GitHub-owned and `gitleaks/gitleaks-action@*` only |
| Default workflow token | `read`; Actions cannot approve PRs |
| Branch protection on `main` | **Not applied.** HTTP 403: requires GitHub Pro or a public repository. Rulesets are blocked the same way |
| Secret scanning and push protection | **Not applied.** HTTP 422: not available for this repository. The `secret-scan` CI job (gitleaks) is the interim control, but it runs after a push and does not block one |
| Private vulnerability reporting | **Not applied.** HTTP 404 from the enable endpoint |
| Signed commits | Not enabled (preferred only) |

Consequence: until protection is available, `main` is protected by convention only. Merge through PRs and wait for the four checks; nothing technically prevents a direct push. Options: upgrade to GitHub Pro, or make the repository public (only after a secret review of the full history).
